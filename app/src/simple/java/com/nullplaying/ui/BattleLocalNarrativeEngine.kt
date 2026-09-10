package com.nullplaying.ui

import com.nullplaying.engine.BattleTraitCatalog
import com.nullplaying.localization.AppLanguage
import com.nullplaying.model.BattleActionKind
import com.nullplaying.model.BattleActionResolution
import com.nullplaying.model.BattleEquipmentSlot
import com.nullplaying.model.BattleEquipmentSnapshot
import com.nullplaying.model.BattleHeroClass
import com.nullplaying.model.BattleOutcome
import com.nullplaying.model.BattleRound
import com.nullplaying.model.BattleRoundAction
import com.nullplaying.model.BattleSkillKind
import com.nullplaying.model.BattleSkillSnapshot
import com.nullplaying.model.BattleTraitDefinition
import com.nullplaying.remote.BattleQaNarrative
import com.nullplaying.remote.BattleQaScene
import com.nullplaying.remote.BattleQaUsage
import java.util.UUID
import kotlin.math.abs

/**
 * GPT-authored, runtime-local battle prose.
 *
 * The battle engine owns every fact. This renderer only selects language-specific prose whose
 * placeholders are filled from the immutable battle result. No network, model, clock, or random
 * source is used, so the same battle and recent-template history always produce the same text.
 */
internal object BattleLocalNarrativeEngine {
    const val TEMPLATE_VERSION = "gpt-local-trilingual-v5-readable-2000"
    const val RECENT_BATTLE_WINDOW = 10

    data class CatalogStats(
        val semanticTemplates: Int,
        val localizedTexts: Int,
        val languages: Int,
        val situationTypes: Int,
        val heroClasses: Int,
        val classMatchups: Int,
    )

    fun catalogStats(): CatalogStats {
        val pools = buildList {
            add(Catalog.openings)
            addAll(Catalog.flows.values)
            addAll(Catalog.skillActions.values)
            addAll(Catalog.equipmentCues.values)
            add(Catalog.basicActions)
            add(Catalog.guardActions)
            add(Catalog.traitObservations)
            add(Catalog.criticalTails)
            addAll(Catalog.outcomes.values)
            addAll(Catalog.classActions.values)
            addAll(Catalog.matchupFlows.values)
            add(Catalog.exchangeSummaries)
        }
        val templates = pools.flatten()
        val count = templates.size
        require(templates.distinct().size == count) { "Local narrative catalog contains duplicates" }
        require(templates.map(TriText::ko).distinct().size == count) { "Korean catalog contains duplicates" }
        require(templates.map(TriText::en).distinct().size == count) { "English catalog contains duplicates" }
        require(templates.map(TriText::ja).distinct().size == count) { "Japanese catalog contains duplicates" }
        return CatalogStats(
            semanticTemplates = count,
            localizedTexts = count * AppLanguage.entries.size,
            languages = AppLanguage.entries.size,
            situationTypes = Situation.entries.size,
            heroClasses = BattleHeroClass.entries.size,
            classMatchups = Catalog.matchupFlows.size,
        )
    }

    fun generate(
        result: BattlePreviewResult,
        language: AppLanguage,
        recentTemplateIds: List<String> = emptyList(),
        requestId: String = UUID.randomUUID().toString(),
    ): BattleQaNarrative {
        val phaseCount = battleNarrativeAnchorCount(result.battle.rounds.size)
        val roundIndexes = expandedLocalBattleRoundIndexes(
            rounds = result.battle.rounds,
            phaseCount = phaseCount,
            skipOpeningRound = battleStartsWithSilentExchange(
                battleId = result.battle.battleId,
                roundCount = result.battle.rounds.size,
                phaseCount = phaseCount,
            ),
        )
        val picker = TemplatePicker(
            seed = "${result.battle.battleId}:${result.battle.serverSeed}",
            recentIds = recentTemplateIds,
        )
        val traits = alternatingTraits(result)
        val scenes = roundIndexes.mapIndexed { sceneIndex, roundIndex ->
            val round = result.battle.rounds[roundIndex]
            val situation = classifySituation(
                round = round,
                result = result,
                sceneIndex = sceneIndex,
                phaseCount = phaseCount,
            )
            val sceneIds = mutableListOf<String>()
            val sentences = mutableListOf<String>()

            val userFirst = stableIndex(
                "${result.battle.battleId}:scene-$sceneIndex-order",
                2,
            ) == 0
            val actions = if (userFirst) {
                listOf(
                    ActionContext(result.userName, result.opponentName, round.userAction, result.battle.user.skills, result.battle.user.equipment, result.battle.user.heroClass),
                    ActionContext(result.opponentName, result.userName, round.opponentAction, result.battle.opponent.skills, result.battle.opponent.equipment, result.battle.opponent.heroClass),
                )
            } else {
                listOf(
                    ActionContext(result.opponentName, result.userName, round.opponentAction, result.battle.opponent.skills, result.battle.opponent.equipment, result.battle.opponent.heroClass),
                    ActionContext(result.userName, result.opponentName, round.userAction, result.battle.user.skills, result.battle.user.equipment, result.battle.user.heroClass),
                )
            }
            val values = valuesFor(result, language)
            when {
                sceneIndex == 0 -> {
                    val matchupKey = result.battle.user.heroClass to result.battle.opponent.heroClass
                    val matchup = picker.pick(
                        "matchup.${matchupKey.first.name.lowercase()}.${matchupKey.second.name.lowercase()}",
                        Catalog.matchupFlows.getValue(matchupKey),
                        "scene-$sceneIndex-matchup",
                    )
                    sceneIds += matchup.id
                    sentences += matchup.text.render(language, values)
                }
                sceneIndex < phaseCount - 1 && sceneIndex == 1 && traits.isNotEmpty() -> {
                    val trait = traits.first()
                    val observation = picker.pick("trait", Catalog.traitObservations, "scene-$sceneIndex-trait")
                    sceneIds += observation.id
                    sentences += observation.text.render(language, values + traitValues(trait, language))
                }
                sceneIndex < phaseCount - 1 && stableIndex("${result.battle.battleId}:scene-$sceneIndex-exchange", 3) == 0 -> {
                    val exchange = picker.pick("exchange", Catalog.exchangeSummaries, "scene-$sceneIndex-exchange")
                    sceneIds += exchange.id
                    sentences += exchange.text.render(language, values)
                }
                sceneIndex < phaseCount - 1 -> {
                    val flow = picker.pick(
                        "flow.${situation.name.lowercase()}",
                        Catalog.flows.getValue(situation),
                        "scene-$sceneIndex-flow",
                    )
                    sceneIds += flow.id
                    sentences += flow.text.render(language, values)
                }
            }

            val narratedSide = battleNarratedAction(round).actor
            val narratedAction = actions.first { it.action.actor == narratedSide }
            val actionLine = renderClassAction(
                context = narratedAction,
                language = language,
                picker = picker,
                salt = "scene-$sceneIndex-class-action",
            )
            sceneIds += actionLine.templateIds
            sentences += actionLine.text

            if (sceneIndex == phaseCount - 1) {
                val outcomePool = Catalog.outcomes.getValue(result.outcome)
                val outcome = picker.pick("outcome.${result.outcome.name.lowercase()}", outcomePool, "outcome")
                sceneIds += outcome.id
                sentences += outcome.text.render(language, values)
            }

            val visibleText = when {
                sceneIndex == phaseCount - 1 -> compactActionText(actionLine.text, language)
                sceneIndex == 0 -> compactOpeningText(sentences.first(), language)
                else -> compactActionText(actionLine.text, language)
            }

            BattleQaScene(
                phaseId = "P${sceneIndex + 1}",
                title = situation.title(language),
                text = visibleText,
                dialogue = null,
                effectKey = effectKey(round, sceneIndex == phaseCount - 1),
                templateIds = sceneIds,
            )
        }

        return BattleQaNarrative(
            schemaVersion = 2,
            requestId = requestId,
            battleId = result.battle.battleId,
            userName = result.userName,
            opponentName = result.opponentName,
            languageTag = language.languageTag,
            phaseCount = phaseCount,
            source = "local_template",
            modelValid = false,
            model = TEMPLATE_VERSION,
            syntheticOnly = true,
            productionDatabaseTouched = false,
            attemptCount = 1,
            latencyMs = 0L,
            usage = BattleQaUsage(),
            estimatedCostUsd = 0.0,
            scenes = scenes,
        )
    }

    fun actionTextForTest(
        name: String,
        action: BattleRoundAction,
        skills: List<BattleSkillSnapshot>,
        equipment: List<BattleEquipmentSnapshot>,
        language: AppLanguage,
        variation: Int,
        heroClass: BattleHeroClass = BattleHeroClass.WARRIOR,
        opponentName: String = when (language) {
            AppLanguage.KOREAN -> "상대"
            AppLanguage.ENGLISH -> "Opponent"
            AppLanguage.JAPANESE -> "対手"
        },
    ): String = conciseBattleActionText(
        context = ActionContext(name, opponentName, action, skills, equipment, heroClass),
        language = language,
        variation = variation,
    )

    private fun renderAction(
        context: ActionContext,
        language: AppLanguage,
        picker: TemplatePicker,
        salt: String,
    ): RenderedLine {
        val item = selectEquipment(context.equipment, context.action, "$salt:item")
        val equipment = item?.let {
            picker.pick(
                "equipment.${it.slot.name.lowercase()}",
                Catalog.equipmentCues.getValue(it.slot),
                "$salt:equipment:${it.itemId}",
            )
        }
        val localizedItem = item?.displayName?.let { localizedEquipmentName(it, language) }.orEmpty()
        val equipmentCue = equipment?.text?.render(
            language,
            mapOf(
                "item" to localizedItem,
                "itemObject" to battleKoreanObject(localizedItem),
                "itemDirection" to battleKoreanDirection(localizedItem),
            ),
            terminate = false,
        )?.let { rendered ->
            if (language == AppLanguage.JAPANESE) rendered else rendered.trimEnd() + " "
        }.orEmpty()
        val skill = context.skills.firstOrNull { it.skillId == context.action.skillId }
        val actionPool = when {
            context.action.kind == BattleActionKind.GUARD -> Catalog.guardActions
            context.action.kind == BattleActionKind.SKILL && skill != null ->
                Catalog.skillActions.getValue(skill.kind)
            else -> Catalog.basicActions
        }
        val group = when {
            context.action.kind == BattleActionKind.GUARD -> "action.guard"
            context.action.kind == BattleActionKind.SKILL && skill != null ->
                "action.skill.${skill.kind.name.lowercase()}"
            else -> "action.basic"
        }
        val actionTemplate = picker.pick(group, actionPool, "$salt:action")
        val critical = if (context.action.critical) {
            picker.pick("critical", Catalog.criticalTails, "$salt:critical")
        } else {
            null
        }
        val localizedSkill = skill?.let { localizedBattleSkillName(it, language) }.orEmpty()
        val values = mapOf(
            "actor" to context.name,
            "actorTopic" to battleKoreanTopic(context.name),
            "actorSubject" to battleKoreanSubject(context.name),
            "actorPossessive" to battleKoreanPossessive(context.name),
            "skill" to localizedSkill,
            "skillObject" to battleKoreanObject(localizedSkill),
            "equipmentCue" to equipmentCue,
            "criticalTail" to critical?.text?.render(language, emptyMap(), terminate = false).orEmpty(),
        )
        return RenderedLine(
            text = actionTemplate.text.render(language, values),
            templateIds = listOfNotNull(equipment?.id, actionTemplate.id, critical?.id),
        )
    }

    private fun renderClassAction(
        context: ActionContext,
        language: AppLanguage,
        picker: TemplatePicker,
        salt: String,
    ): RenderedLine {
        val group = "class.${context.heroClass.name.lowercase()}.${context.action.kind.name.lowercase()}"
        val selected = picker.pick(
            group = group,
            pool = Catalog.classActions.getValue(context.heroClass),
            salt = "$salt:${context.action.skillId}:${context.action.critical}",
        )
        return RenderedLine(
            text = conciseBattleActionText(
                context = context,
                language = language,
                variation = stableIndex("$salt:readable:${selected.id}", 6),
            ),
            templateIds = listOf(selected.id),
        )
    }

    private fun conciseBattleActionText(
        context: ActionContext,
        language: AppLanguage,
        variation: Int,
    ): String {
        val skill = context.skills.firstOrNull { it.skillId == context.action.skillId }
        val move = skill?.let { localizedBattleSkillName(it, language) }
            ?: language.pick("기술", "skill", "技")
        val action = context.action
        if (action.resolution in setOf(
                BattleActionResolution.BLOCKED,
                BattleActionResolution.EVADED,
                BattleActionResolution.MISSED,
            )
        ) {
            return battleZeroDamageActionText(
                name = context.name,
                opponentName = context.opponentName,
                action = action,
                skills = context.skills,
                language = language,
                variation = variation,
            )
        }
        if (action.kind == BattleActionKind.GUARD || action.resolution == BattleActionResolution.GUARDED) {
            return when (language) {
                AppLanguage.KOREAN -> "${battleKoreanSubject(context.name)} 방어 태세를 갖췄다."
                AppLanguage.ENGLISH -> "${context.name} took a guarded stance."
                AppLanguage.JAPANESE -> "${context.name}は防御態勢を取った。"
            }
        }
        if (action.healing > 0 || action.resolution == BattleActionResolution.RECOVERED || skill?.kind == BattleSkillKind.RECOVER) {
            return when (language) {
                AppLanguage.KOREAN -> "${battleKoreanTopic(context.name)} ${battleKoreanDirection(move)} 힘을 가다듬었다."
                AppLanguage.ENGLISH -> "${context.name} recovered with $move."
                AppLanguage.JAPANESE -> "${context.name}は${move}で態勢を立て直した。"
            }
        }
        if (action.finisherSucceeded) {
            return when (language) {
                AppLanguage.KOREAN -> "${battleKoreanPossessive(context.name)} ${battleKoreanSubject(move)} 결정타로 꽂혔다."
                AppLanguage.ENGLISH -> "${context.name} landed a decisive $move."
                AppLanguage.JAPANESE -> "${context.name}の${move}が決定打となった。"
            }
        }
        if (action.critical) {
            return when (language) {
                AppLanguage.KOREAN -> "${battleKoreanPossessive(context.name)} ${battleKoreanSubject(move)} 급소에 적중했다."
                AppLanguage.ENGLISH -> "${context.name}'s $move struck a weak point."
                AppLanguage.JAPANESE -> "${context.name}の${move}が急所を捉えた。"
            }
        }
        if (action.kind == BattleActionKind.SKILL) {
            val impact = conciseClassSkillImpacts(context.heroClass, language)[
                Math.floorMod(variation, 6)
            ]
            return when (language) {
                AppLanguage.KOREAN -> "${battleKoreanPossessive(context.name)} ${battleKoreanSubject(move)} $impact."
                AppLanguage.ENGLISH -> "${context.name}'s $move $impact."
                AppLanguage.JAPANESE -> "${context.name}は${move}で$impact。"
            }
        }
        return when (language) {
            AppLanguage.KOREAN -> "${battleKoreanSubject(context.name)} 짧게 공격했다."
            AppLanguage.ENGLISH -> "${context.name} struck quickly."
            AppLanguage.JAPANESE -> "${context.name}は素早く攻撃した。"
        }
    }

    private fun conciseClassSkillImpacts(
        heroClass: BattleHeroClass,
        language: AppLanguage,
    ): List<String> = when (language) {
        AppLanguage.KOREAN -> when (heroClass) {
            BattleHeroClass.WARRIOR -> listOf("정면을 돌파했다", "강하게 밀어붙였다", "방어선을 흔들었다", "묵직하게 적중했다", "주도권을 빼앗았다", "상대를 밀어냈다")
            BattleHeroClass.ROGUE -> listOf("빈틈을 찔렀다", "사각을 파고들었다", "허점을 베었다", "빠르게 적중했다", "시야 밖을 노렸다", "반응을 앞질렀다")
            BattleHeroClass.RANGER -> listOf("거리를 꿰뚫었다", "진로를 끊었다", "정확히 적중했다", "퇴로를 막았다", "움직임을 묶었다", "먼저 자리를 잡았다")
            BattleHeroClass.MAGE -> listOf("마력을 폭발시켰다", "전장을 뒤흔들었다", "방어를 무너뜨렸다", "강하게 적중했다", "움직임을 봉쇄했다", "흐름을 뒤집었다")
            BattleHeroClass.CLERIC -> listOf("성력을 터뜨렸다", "흐름을 되찾았다", "공세를 밀어냈다", "정확히 적중했다", "버틸 틈을 만들었다", "상대를 제압했다")
            BattleHeroClass.PALADIN -> listOf("공격선을 밀어냈다", "정면을 제압했다", "수비를 깨뜨렸다", "묵직하게 적중했다", "진형을 무너뜨렸다", "한 걸음 전진했다")
        }
        AppLanguage.ENGLISH -> when (heroClass) {
            BattleHeroClass.WARRIOR -> listOf("broke through", "pressed forward", "shook the guard", "landed heavily", "seized the initiative", "drove the opponent back")
            BattleHeroClass.ROGUE -> listOf("found the opening", "slipped inside", "cut the weak point", "landed quickly", "struck from the blind side", "beat the reaction")
            BattleHeroClass.RANGER -> listOf("pierced the distance", "cut off the path", "landed cleanly", "sealed the retreat", "pinned the movement", "claimed the range")
            BattleHeroClass.MAGE -> listOf("burst with power", "shook the field", "broke the defense", "landed forcefully", "locked down movement", "reversed the flow")
            BattleHeroClass.CLERIC -> listOf("released sacred force", "reclaimed the flow", "pushed back the assault", "landed cleanly", "created breathing room", "overpowered the opponent")
            BattleHeroClass.PALADIN -> listOf("pushed back the line", "controlled the front", "broke the guard", "landed heavily", "shattered the formation", "advanced one step")
        }
        AppLanguage.JAPANESE -> when (heroClass) {
            BattleHeroClass.WARRIOR -> listOf("正面を突破した", "力強く押し込んだ", "守りを揺さぶった", "重く命中した", "主導権を奪った", "相手を押し返した")
            BattleHeroClass.ROGUE -> listOf("隙を突いた", "死角へ潜り込んだ", "弱点を斬った", "素早く命中した", "視界の外を狙った", "反応を上回った")
            BattleHeroClass.RANGER -> listOf("間合いを射抜いた", "進路を断った", "正確に命中した", "退路を塞いだ", "動きを縛った", "先に間合いを取った")
            BattleHeroClass.MAGE -> listOf("魔力を爆発させた", "戦場を揺らした", "守りを崩した", "強く命中した", "動きを封じた", "流れを覆した")
            BattleHeroClass.CLERIC -> listOf("聖なる力を放った", "流れを取り戻した", "攻勢を押し返した", "正確に命中した", "立て直す隙を作った", "相手を制した")
            BattleHeroClass.PALADIN -> listOf("攻撃線を押し返した", "正面を制した", "守りを破った", "重く命中した", "陣形を崩した", "一歩前進した")
        }
    }

    private fun valuesFor(result: BattlePreviewResult, language: AppLanguage): Map<String, String> = mapOf(
        "user" to result.userName,
        "opponent" to result.opponentName,
        "userTopic" to battleKoreanTopic(result.userName),
        "opponentTopic" to battleKoreanTopic(result.opponentName),
        "userSubject" to battleKoreanSubject(result.userName),
        "opponentSubject" to battleKoreanSubject(result.opponentName),
        "winner" to winnerName(result),
        "loser" to loserName(result),
        "winnerSubject" to battleKoreanSubject(winnerName(result)),
        "loserSubject" to battleKoreanSubject(loserName(result)),
        "language" to language.languageTag,
    )

    private fun traitValues(
        trait: Pair<String, BattleTraitDefinition>,
        language: AppLanguage,
    ): Map<String, String> {
        val localizedTrait = localized(trait.second.nameKo, language).let { translated ->
            if (language != AppLanguage.KOREAN && translated == trait.second.nameKo) {
                when (language) {
                    AppLanguage.ENGLISH -> "a familiar combat habit"
                    AppLanguage.JAPANESE -> "いつもの戦い方"
                    AppLanguage.KOREAN -> translated
                }
            } else {
                translated
            }
        }
        return mapOf(
            "traitOwner" to trait.first,
            "traitOwnerTopic" to battleKoreanTopic(trait.first),
            "traitOwnerPossessive" to battleKoreanPossessive(trait.first),
            "trait" to localizedTrait,
            "traitTopic" to battleKoreanTopic(localizedTrait),
            "traitObject" to battleKoreanObject(localizedTrait),
        )
    }

    private fun alternatingTraits(result: BattlePreviewResult): List<Pair<String, BattleTraitDefinition>> {
        val user = result.battle.user.activeTraitIds.mapNotNull(BattleTraitCatalog.byId::get)
        val opponent = result.battle.opponent.activeTraitIds.mapNotNull(BattleTraitCatalog.byId::get)
        return buildList {
            repeat(maxOf(user.size, opponent.size)) { index ->
                user.getOrNull(index)?.let { add(result.userName to it) }
                opponent.getOrNull(index)?.let { add(result.opponentName to it) }
            }
        }
    }

    private fun winnerName(result: BattlePreviewResult): String = when (result.outcome) {
        BattleOutcome.USER_WIN -> result.userName
        BattleOutcome.USER_LOSS -> result.opponentName
        BattleOutcome.DRAW -> result.userName
    }

    private fun loserName(result: BattlePreviewResult): String = when (result.outcome) {
        BattleOutcome.USER_WIN -> result.opponentName
        BattleOutcome.USER_LOSS -> result.userName
        BattleOutcome.DRAW -> result.opponentName
    }

    private fun compactActionText(text: String, language: AppLanguage): String {
        var compact = when (language) {
            AppLanguage.KOREAN -> text.substringBeforeLast(',', text)
            AppLanguage.ENGLISH -> text.substringBeforeLast(" and ", text)
            AppLanguage.JAPANESE -> text.substringBeforeLast('、', text)
        }
        val styleCues = when (language) {
            AppLanguage.KOREAN -> listOf(
                "낮은 중심으로", "단단한 보폭으로", "소리 없는 반걸음으로", "시선의 반대편에서",
                "넓은 시야로", "한발 앞선 거리에서", "짧은 영창으로", "흔들리는 마력 사이로",
                "안정된 중심에서", "성광의 여운을 따라", "닫힌 수비선에서", "흔들리지 않는 보폭으로",
            )
            AppLanguage.ENGLISH -> listOf(
                "from a low center", "with grounded steps", "off a silent half-step", "from beyond the gaze",
                "across the open sightline", "from one step beyond", "with a clipped incantation", "through shifting mana",
                "from a steady center", "along the sacred afterglow", "from a sealed guard", "with unwavering steps",
            )
            AppLanguage.JAPANESE -> listOf(
                "低い重心から", "堅い歩幅で", "音のない半歩から", "視線の反対側から",
                "広い視界から", "一歩先の間合いから", "短い詠唱で", "揺れる魔力の間から",
                "安定した重心から", "聖光の余韻に沿って", "閉じた防御線から", "揺れない歩幅で",
            )
        }
        styleCues.forEach { cue -> compact = compact.replace(cue, "") }
        compact = compact.replace(Regex("[ \\t]+"), " ").trim().trimEnd('.', '!', '?', '。', '！', '？')
        val terminator = if (language == AppLanguage.JAPANESE) "。" else "."
        return compact + terminator
    }

    private fun compactOpeningText(text: String, language: AppLanguage): String {
        val tail = when (language) {
            AppLanguage.KOREAN, AppLanguage.ENGLISH -> text.substringAfterLast(',', text)
            AppLanguage.JAPANESE -> text.substringAfterLast('、', text)
        }
        val variation = when (language) {
            AppLanguage.KOREAN -> when {
                "거리" in tail -> 1
                "공격선" in tail || "시선" in tail -> 2
                "충돌" in tail || "속도" in tail -> 3
                "중앙" in tail -> 0
                else -> 4
            }
            AppLanguage.ENGLISH -> when {
                "distance" in tail || "ranges" in tail -> 1
                "attack line" in tail || "eyes" in tail -> 2
                "contact" in tail || "pace" in tail -> 3
                "center" in tail -> 0
                else -> 4
            }
            AppLanguage.JAPANESE -> when {
                "間合い" in tail -> 1
                "攻撃線" in tail || "視線" in tail -> 2
                "衝突" in tail || "速度" in tail -> 3
                "中央" in tail -> 0
                else -> 4
            }
        }
        val compact = when (language) {
            AppLanguage.KOREAN -> {
                val verb = listOf("맞붙었다", "맞섰다", "엇갈렸다", "충돌했다", "대치했다")[variation]
                text.substringBeforeLast(',', text).trim().removeSuffix("맞붙자") + verb
            }
            AppLanguage.ENGLISH -> {
                val verb = listOf(" met ", " faced ", " crossed ", " clashed with ", " challenged ")[variation]
                text.substringBeforeLast(',', text).trim().replace(" met ", verb)
            }
            AppLanguage.JAPANESE -> {
                val verb = listOf("が交差した", "が向き合った", "が交錯した", "がぶつかった", "がせめぎ合った")[variation]
                text.substringBeforeLast('、', text).trim().removeSuffix("が交差し") + verb
            }
        }.replace(Regex("[ \\t]+"), " ").trim().trimEnd('.', '!', '?', '。', '！', '？')
        return compact + if (language == AppLanguage.JAPANESE) "。" else "."
    }

    private fun selectEquipment(
        equipment: List<BattleEquipmentSnapshot>,
        action: BattleRoundAction,
        salt: String,
    ): BattleEquipmentSnapshot? {
        if (equipment.isEmpty()) return null
        val priority = if (action.kind == BattleActionKind.GUARD) {
            listOf(BattleEquipmentSlot.BODY, BattleEquipmentSlot.HANDS, BattleEquipmentSlot.HEAD,
                BattleEquipmentSlot.FEET, BattleEquipmentSlot.ACCESSORY, BattleEquipmentSlot.WEAPON)
        } else {
            listOf(BattleEquipmentSlot.WEAPON, BattleEquipmentSlot.HANDS, BattleEquipmentSlot.FEET,
                BattleEquipmentSlot.ACCESSORY, BattleEquipmentSlot.BODY, BattleEquipmentSlot.HEAD)
        }
        val candidates = priority.mapNotNull { slot -> equipment.firstOrNull { it.slot == slot } }
        return candidates[stableIndex(salt, candidates.size)]
    }

    private fun classifySituation(
        round: BattleRound,
        result: BattlePreviewResult,
        sceneIndex: Int,
        phaseCount: Int,
    ): Situation {
        if (sceneIndex == 0) {
            return when {
                round.userAction.kind == BattleActionKind.GUARD &&
                    round.opponentAction.kind == BattleActionKind.GUARD -> Situation.OPENING_CAUTIOUS
                round.userAction.kind == BattleActionKind.SKILL &&
                    round.opponentAction.kind == BattleActionKind.SKILL -> Situation.OPENING_AGGRESSIVE
                else -> Situation.OPENING
            }
        }
        if (round.userAction.finisher || round.opponentAction.finisher) {
            return Situation.FINAL_DECISIVE
        }
        val before = round.userHpBefore - round.opponentHpBefore
        val after = round.userHpAfter - round.opponentHpAfter
        if (sceneIndex == phaseCount - 1) {
            return if (abs(after) <= 120 || (round.userHpAfter <= 250 && round.opponentHpAfter <= 250)) {
                Situation.FINAL_CLOSE
            } else {
                Situation.FINAL_DECISIVE
            }
        }
        return when {
            round.userAction.kind == BattleActionKind.GUARD &&
                round.opponentAction.kind == BattleActionKind.GUARD -> Situation.DOUBLE_GUARD
            round.userAction.healing > 0 || round.opponentAction.healing > 0 -> Situation.RECOVERY
            round.userAction.critical && round.opponentAction.critical -> Situation.DOUBLE_CRITICAL
            round.userAction.critical || round.opponentAction.critical -> Situation.CRITICAL
            (round.userAction.kind == BattleActionKind.SKILL &&
                round.opponentAction.kind == BattleActionKind.GUARD) ||
                (round.opponentAction.kind == BattleActionKind.SKILL &&
                    round.userAction.kind == BattleActionKind.GUARD) -> Situation.SKILL_VS_GUARD
            round.userAction.kind == BattleActionKind.SKILL &&
                round.opponentAction.kind == BattleActionKind.SKILL -> Situation.SKILL_CLASH
            round.userAction.kind == BattleActionKind.GUARD ||
                round.opponentAction.kind == BattleActionKind.GUARD -> Situation.ATTACK_VS_GUARD
            round.userHpAfter <= 220 && round.opponentHpAfter <= 220 -> Situation.DESPERATE
            minOf(round.userHpAfter, round.opponentHpAfter) <= 150 &&
                maxOf(round.userHpAfter, round.opponentHpAfter) >= 300 -> Situation.LAST_STAND
            before < 0 && after > 0 -> Situation.USER_REVERSAL
            before > 0 && after < 0 -> Situation.OPPONENT_REVERSAL
            before == 0 && after != 0 -> Situation.REVERSAL
            after >= 400 -> Situation.USER_DOMINANT
            after <= -400 -> Situation.OPPONENT_DOMINANT
            abs(after) >= 300 -> Situation.DOMINANT
            result.battle.rounds.size >= 8 && sceneIndex >= 2 -> Situation.LONG_BATTLE
            abs(after) <= 90 -> Situation.EXCHANGE
            abs(after) >= abs(before) + 100 -> Situation.PRESSURE
            else -> Situation.TURNING_POINT
        }
    }

    private val Int.sign: Int get() = when {
        this > 0 -> 1
        this < 0 -> -1
        else -> 0
    }

    private fun effectKey(round: BattleRound, final: Boolean): String = when {
        round.userAction.finisher || round.opponentAction.finisher -> "FINISHER"
        final -> "FINISH"
        round.userAction.critical || round.opponentAction.critical -> "HEAVY_HIT"
        round.userAction.kind == BattleActionKind.GUARD || round.opponentAction.kind == BattleActionKind.GUARD -> "GUARD"
        round.userAction.kind == BattleActionKind.SKILL || round.opponentAction.kind == BattleActionKind.SKILL -> "SLASH"
        else -> "CLASH"
    }

    private data class ActionContext(
        val name: String,
        val opponentName: String,
        val action: BattleRoundAction,
        val skills: List<BattleSkillSnapshot>,
        val equipment: List<BattleEquipmentSnapshot>,
        val heroClass: BattleHeroClass = BattleHeroClass.WARRIOR,
    )

    private data class RenderedLine(val text: String, val templateIds: List<String>)

    private enum class Situation {
        OPENING,
        OPENING_AGGRESSIVE,
        OPENING_CAUTIOUS,
        SKILL_CLASH,
        SKILL_VS_GUARD,
        ATTACK_VS_GUARD,
        DOUBLE_GUARD,
        RECOVERY,
        CRITICAL,
        DOUBLE_CRITICAL,
        PRESSURE,
        EXCHANGE,
        REVERSAL,
        USER_REVERSAL,
        OPPONENT_REVERSAL,
        DESPERATE,
        LAST_STAND,
        DOMINANT,
        USER_DOMINANT,
        OPPONENT_DOMINANT,
        LONG_BATTLE,
        TURNING_POINT,
        FINAL_CLOSE,
        FINAL_DECISIVE;

        fun title(language: AppLanguage): String = when (this) {
            OPENING -> language.pick("첫 탐색", "Opening Measure", "最初の探り")
            OPENING_AGGRESSIVE -> language.pick("거센 개막", "Aggressive Opening", "激しい幕開け")
            OPENING_CAUTIOUS -> language.pick("신중한 개막", "Cautious Opening", "慎重な幕開け")
            SKILL_CLASH -> language.pick("기술의 교차", "Skills Collide", "技の交差")
            SKILL_VS_GUARD -> language.pick("기술과 방벽", "Skill Against Guard", "技と防壁")
            ATTACK_VS_GUARD -> language.pick("공격과 수비", "Attack and Guard", "攻撃と防御")
            DOUBLE_GUARD -> language.pick("고요한 대치", "Guarded Standoff", "静かな対峙")
            RECOVERY -> language.pick("호흡의 회복", "Recovered Rhythm", "呼吸の回復")
            CRITICAL -> language.pick("정확한 한 수", "Critical Moment", "鋭い一手")
            DOUBLE_CRITICAL -> language.pick("맞부딪친 결정타", "Critical Collision", "激突する決定打")
            PRESSURE -> language.pick("거세지는 압박", "Mounting Pressure", "高まる圧力")
            EXCHANGE -> language.pick("팽팽한 공방", "Even Exchange", "拮抗する攻防")
            REVERSAL -> language.pick("뒤집힌 흐름", "Reversal", "逆転する流れ")
            USER_REVERSAL -> language.pick("되찾은 주도권", "Momentum Reclaimed", "取り戻した主導権")
            OPPONENT_REVERSAL -> language.pick("빼앗긴 주도권", "Momentum Lost", "奪われた主導権")
            DESPERATE -> language.pick("한계의 공방", "At the Limit", "限界の攻防")
            LAST_STAND -> language.pick("마지막 버팀", "Last Stand", "最後の踏ん張り")
            DOMINANT -> language.pick("기울어진 전세", "Decisive Advantage", "傾いた戦況")
            USER_DOMINANT -> language.pick("밀어붙이는 공세", "Commanding Assault", "押し切る攻勢")
            OPPONENT_DOMINANT -> language.pick("좁아지는 퇴로", "Closing Escape", "狭まる退路")
            LONG_BATTLE -> language.pick("긴 호흡", "Battle of Endurance", "長い呼吸")
            TURNING_POINT -> language.pick("갈림길", "Turning Point", "分岐点")
            FINAL_CLOSE -> language.pick("마지막 한 치", "Final Margin", "最後の一寸")
            FINAL_DECISIVE -> language.pick("결정의 순간", "Decisive Finish", "決着の瞬間")
        }
    }

    private data class TriText(val ko: String, val en: String, val ja: String) {
        fun merge(tail: TriText): TriText = TriText(
            ko = mergeLanguageText(ko, tail.ko),
            en = mergeLanguageText(en, tail.en),
            ja = mergeLanguageText(ja, tail.ja),
        )

        fun appendRaw(tail: TriText): TriText = TriText(
            ko = ko + tail.ko,
            en = en + tail.en,
            ja = ja + tail.ja,
        )

        private fun mergeLanguageText(base: String, tail: String): String {
            val marker = "{criticalTail}"
            return if (marker in base) {
                base.replace(marker, tail + marker)
            } else {
                base.trimEnd('.', '!', '?', '。', '！', '？') + tail
            }
        }

        fun render(
            language: AppLanguage,
            values: Map<String, String>,
            terminate: Boolean = true,
        ): String {
            var rendered = language.pick(ko, en, ja)
            values.entries.sortedByDescending { it.key.length }.forEach { (key, value) ->
                rendered = rendered.replace("{$key}", value)
            }
            rendered = when (language) {
                AppLanguage.KOREAN, AppLanguage.ENGLISH, AppLanguage.JAPANESE ->
                    rendered.replace(Regex("[ \\t]+"), " ").trim()
            }
            if (!terminate || rendered.isBlank()) return rendered
            val terminator = if (language == AppLanguage.JAPANESE) "。" else "."
            return rendered.trimEnd('.', '!', '?', '。', '！', '？') + terminator
        }
    }

    private data class Selection(val id: String, val text: TriText)

    private class TemplatePicker(
        private val seed: String,
        recentIds: List<String>,
    ) {
        private val recency = recentIds.distinct().withIndex().associate { it.value to it.index }
        private val used = mutableSetOf<String>()

        fun pick(group: String, pool: List<TriText>, salt: String): Selection {
            require(pool.isNotEmpty()) { "Template pool $group must not be empty" }
            val candidates = pool.mapIndexed { index, text -> Selection("$group.$index", text) }
            val selected = candidates.minBy { candidate ->
                val recentIndex = recency[candidate.id]
                val recentPenalty = if (recentIndex == null) 0L else {
                    100_000L + (recency.size - recentIndex).coerceAtLeast(1) * 1_000L
                }
                val currentPenalty = if (candidate.id in used) 1_000_000L else 0L
                currentPenalty + recentPenalty + stableIndex("$seed:$salt:${candidate.id}", 997)
            }
            used += selected.id
            return selected
        }
    }

    private object Catalog {
        private val openingTails = listOf(
            tri(", 먼 곳의 종소리가 사라지는 동안 어느 쪽도 먼저 중심선을 내주지 않았다", ", and neither yielded the center line before a distant bell faded", "、遠い鐘の音が消えるまで、どちらも先に中心線を譲らなかった"),
            tri(", 흩어진 먼지가 발끝의 방향을 드러내며 첫 선택의 무게를 더했다", ", while drifting dust exposed the direction of their feet and gave the first choice greater weight", "、舞う砂埃が足先の向きを映し、最初の選択をさらに重くした"),
            tri(", 짧아진 숨소리만이 두 사람 사이의 정확한 거리를 알려 주었다", ", with only shortened breaths revealing the exact distance between them", "、短くなった呼吸だけが二人の正確な間合いを伝えていた"),
            tri(", 바뀌는 빛과 그림자가 정면보다 측면의 길을 먼저 열었다", ", as shifting light and shadow opened the flanks before the center", "、移ろう光と影が正面より先に側面の道を開いた"),
            tri(", 서로 다른 발의 박자가 겹치며 조용한 공간에 긴장이 번졌다", ", and the overlap of mismatched footsteps spread tension through the quiet space", "、異なる足音の拍子が重なり、静かな空間へ緊張が広がった"),
            tri(", 한 번의 시선 이동이 아직 시작되지 않은 공격의 방향을 감췄다", ", while a single shift of the eyes concealed the direction of an attack not yet begun", "、一度の視線移動が、まだ始まっていない攻撃の方向を隠した"),
            tri(", 좁은 안전지대가 두 사람의 접근을 서로 다른 각도로 갈라놓았다", ", and a narrow pocket of safety split their approaches along different angles", "、狭い安全地帯が二人の接近を異なる角度へ分けた"),
            tri(", 첫 충돌 직전의 정적이 오히려 다음 움직임을 더 선명하게 만들었다", ", and the silence before first contact made every coming movement sharper", "、最初の衝突直前の静寂が、かえって次の動きを鮮明にした"),
        )

        private val flowTails = listOf(
            tri(", 그 결과 다음 공격이 시작될 자리가 한쪽으로 좁아졌다", ", narrowing the ground from which the next attack could begin", "、その結果、次の攻撃を始められる場所が一方へ狭まった"),
            tri(", 남은 간격은 반격보다 먼저 발의 위치를 고치게 만들었다", ", and the remaining gap demanded a correction of footing before any counter", "、残った間合いは反撃より先に足場の修正を求めた"),
            tri(", 짧은 주도권은 오래 머물지 않았지만 다음 판단의 순서를 바꾸었다", ", and the brief initiative changed the order of the next decisions even though it did not last", "、短い主導権は長く続かなかったが、次の判断の順序を変えた"),
            tri(", 두 사람은 같은 실수를 피하려 서로 다른 퇴로를 남겨 두었다", ", leaving each fighter a different retreat to avoid repeating the same mistake", "、二人は同じ失敗を避けるため、それぞれ別の退路を残した"),
            tri(", 시선과 발끝이 엇갈리며 겉으로 보이는 우세와 실제 거리가 달라졌다", ", as crossed gazes and feet separated visible advantage from actual distance", "、視線と足先が交差し、見かけの優勢と実際の間合いがずれた"),
            tri(", 공격의 속도보다 끝난 뒤 남는 위치가 더 중요한 변수가 되었다", ", making the position left afterward more important than the speed of the attack", "、攻撃の速さより、終わった後に残る位置が重要な変数になった"),
            tri(", 한 박자 늦춘 움직임이 익숙해진 대응을 먼저 빗나가게 했다", ", and a movement delayed by one beat made the familiar response miss first", "、一拍遅らせた動きが、慣れた対応を先に外させた"),
            tri(", 중앙을 둘러싼 작은 회전이 공격선과 퇴로를 동시에 바꾸었다", ", while a small turn around center changed both attack line and escape route", "、中央を巡る小さな旋回が、攻撃線と退路を同時に変えた"),
            tri(", 이어진 침묵 속에서 다음 한 수의 정보는 발소리보다 호흡에 남았다", ", and in the silence that followed, the next move showed more in breath than footsteps", "、続く静寂の中、次の一手の情報は足音より呼吸に残った"),
            tri(", 좁혀진 선택지는 오히려 두 사람의 의도를 더 분명하게 드러냈다", ", and the reduced choices made both intentions easier to read", "、狭まった選択肢が、かえって二人の意図を明確にした"),
        )

        private val actionTails = listOf(
            tri(", 이어지는 각도까지 계산해 반격의 직선을 비켜 났다", ", already accounting for the next angle and slipping away from the counter's straight line", "、続く角度まで計算し、反撃の直線を外れた"),
            tri(", 끝동작을 짧게 거두어 다음 선택을 숨겼다", ", shortening the finish to conceal the next choice", "、終動を短く収め、次の選択を隠した"),
            tri(", 남은 반걸음으로 안전한 간격을 다시 만들었다", ", using the remaining half-step to rebuild a safe distance", "、残る半歩で安全な間合いを作り直した"),
            tri(", 같은 높이의 견제를 남겨 상대의 시선을 붙잡았다", ", leaving a probe at the same height to hold the opponent's gaze", "、同じ高さに牽制を残し、相手の視線を縛った"),
            tri(", 힘을 모두 싣지 않아 이어질 움직임의 여유를 보존했다", ", withholding full force to preserve room for the movement that followed", "、力を乗せ切らず、続く動きの余裕を残した"),
            tri(", 충돌 뒤의 위치를 먼저 차지해 공방이 끊기지 않게 했다", ", claiming the post-impact position first and keeping the exchange unbroken", "、衝突後の位置を先に取り、攻防を途切れさせなかった"),
        )

        private val equipmentTails = listOf(
            tri("어깨의 중심을 낮춘 채 ", "keeping the shoulders centered, ", "肩の軸を低く保ち、"),
            tri("반대쪽 발의 퇴로를 남겨 두고 ", "leaving an escape for the opposite foot, ", "反対側の足に退路を残し、"),
            tri("충격 뒤의 흔들림까지 눌러 ", "containing the recoil after impact, ", "衝撃後の揺れまで抑え、"),
            tri("시선이 먼저 흔들리지 않게 하며 ", "keeping the gaze from breaking first, ", "視線が先に揺れないようにし、"),
            tri("다음 회전의 축을 미리 세우고 ", "setting the pivot for the next turn, ", "次の旋回軸を先に作り、"),
            tri("짧은 체중 이동을 동작에 겹쳐 ", "layering a short weight shift into the motion, ", "短い重心移動を動作へ重ね、"),
        )

        private val criticalExtensions = listOf(
            tri(", 충격 뒤에도 정확한 자세가 남아 후속 대응까지 앞섰다", ", and the precise posture that remained after impact stayed ahead of the follow-up", "、衝撃後にも正確な姿勢が残り、続く対応まで先んじた"),
        )

        private data class ClassTechnique(
            val frames: List<TriText>,
            val vectors: List<TriText>,
            val identity: TriText,
        )

        private val classFinishers = listOf(
            tri("정면의 길을 열었다", "opened the center lane", "正面の道を開いた"),
            tri("반격의 각도를 지웠다", "erased the counter angle", "反撃の角度を消した"),
            tri("안전한 거리를 되찾았다", "recovered safe distance", "安全な間合いを取り戻した"),
            tri("상대의 발을 멈췄다", "stopped the opposing feet", "相手の足を止めた"),
            tri("다음 공격권을 잡았다", "claimed the next attack", "次の攻撃権を握った"),
            tri("퇴로 하나를 닫았다", "closed one escape lane", "退路を一つ閉じた"),
            tri("중앙의 주도권을 얻었다", "secured control of center", "中央の主導権を得た"),
            tri("상대의 박자를 늦췄다", "slowed the opposing rhythm", "相手の拍子を遅らせた"),
            tri("측면의 빈틈을 만들었다", "created an opening on the flank", "側面に隙を作った"),
            tri("추격할 공간을 남겼다", "left room to pursue", "追撃の空間を残した"),
            tri("방어 자세를 흔들었다", "shook the defensive stance", "防御姿勢を揺らした"),
            tri("공방의 순서를 바꿨다", "changed the order of exchange", "攻防の順序を変えた"),
            tri("짧은 우세를 굳혔다", "secured a brief advantage", "短い優勢を固めた"),
            tri("다음 회피를 읽어 냈다", "read the next evasion", "次の回避を読み切った"),
            tri("상대의 시선을 묶었다", "held the opponent's gaze", "相手の視線を縛った"),
            tri("공격선을 안쪽으로 돌렸다", "turned the attack line inward", "攻撃線を内側へ向けた"),
            tri("불리한 위치를 벗어났다", "escaped the weaker position", "不利な位置を抜けた"),
            tri("이어질 기술의 틈을 만들었다", "made room for the next skill", "次の技の余地を作った"),
            tri("상대의 중심을 뒤로 밀었다", "drove the opposing balance back", "相手の重心を後ろへ押した"),
            tri("남은 간격을 먼저 차지했다", "claimed the remaining gap first", "残る間合いを先に取った"),
        )

        private val classTechniques = mapOf(
            BattleHeroClass.WARRIOR to ClassTechnique(
                frames = listOf(
                    tri("방어선 뒤에서 {moveObject} 밀어냈다", "drove {move}", "防御線の後ろから{move}を押し出した"),
                    tri("무게를 실어 {moveObject} 내리쳤다", "brought {move} down", "重みを乗せて{move}を振り下ろした"),
                    tri("충돌을 버티며 {moveObject} 내질렀다", "thrust {move}", "衝突に耐えながら{move}を突き出した"),
                    tri("짧게 {moveObject} 휘둘렀다", "swung {move} in a short arc", "{move}を短い軌道で振った"),
                    tri("전진과 함께 {moveObject} 밀어붙였다", "pressed forward with {move}", "前進とともに{move}を押し込んだ"),
                ),
                vectors = listOf(
                    tri("낮은 중심으로", "from a low center", "低い重心から"),
                    tri("단단한 보폭으로", "with grounded steps", "堅い歩幅で"),
                ),
                identity = tri("중갑의 압박", "armored pressure", "重装の圧力"),
            ),
            BattleHeroClass.ROGUE to ClassTechnique(
                frames = listOf(
                    tri("사각으로 {moveObject} 흘려 보냈다", "slipped {move}", "{move}を死角へ滑らせた"),
                    tri("헛동작 뒤에 {moveObject} 찔러 넣었다", "threaded {move}", "フェイントの後に{move}を差し込んだ"),
                    tri("그림자 틈으로 {moveObject} 꺼냈다", "released {move} through shadow", "影の隙から{move}を放った"),
                    tri("반대 손으로 {moveObject} 이어 갔다", "redirected {move}", "{move}を逆の手へつないだ"),
                    tri("퇴로를 밟으며 {moveObject} 비껴 썼다", "cut across with {move}", "退路を踏みながら{move}を斜めに使った"),
                ),
                vectors = listOf(
                    tri("소리 없는 반걸음으로", "off a silent half-step", "音のない半歩から"),
                    tri("시선의 반대편에서", "from beyond the gaze", "視線の反対側から"),
                ),
                identity = tri("그림자 기동", "shadow footwork", "影の機動"),
            ),
            BattleHeroClass.RANGER to ClassTechnique(
                frames = listOf(
                    tri("빈 궤도를 따라 {moveObject} 쏘았다", "sent {move}", "空いた軌道へ{move}を放った"),
                    tri("이동할 자리에 {moveObject} 놓았다", "placed {move}", "移動先へ{move}を置いた"),
                    tri("먼 거리에서 {moveObject} 꺾어 보냈다", "curved {move}", "遠間から{move}を曲げて放った"),
                    tri("발끝의 방향을 따라 {moveObject} 날렸다", "guided {move}", "足先の向きへ{move}を導いた"),
                    tri("추격선 앞으로 {moveObject} 당겨 썼다", "drew and loosed {move}", "追撃線の前へ{move}を引き放った"),
                ),
                vectors = listOf(
                    tri("넓은 시야로", "across the open sightline", "広い視界から"),
                    tri("한발 앞선 거리에서", "from one step beyond", "一歩先の間合いから"),
                ),
                identity = tri("거리 통제", "ranged control", "間合いの支配"),
            ),
            BattleHeroClass.MAGE to ClassTechnique(
                frames = listOf(
                    tri("한 점에 {moveObject} 응축해 펼쳤다", "focused {move}", "{move}を一点へ凝縮して展開した"),
                    tri("빛의 결을 따라 {moveObject} 풀었다", "released {move}", "光の筋に沿って{move}を解き放った"),
                    tri("발밑에서 {moveObject} 솟게 했다", "raised {move}", "足元から{move}を立ち上げた"),
                    tri("{moveObject} 두 겹의 궤도로 나눴다", "split {move}", "{move}を二重の軌道へ分けた"),
                    tri("공간을 접듯 {moveObject} 돌려세웠다", "turned {move}", "空間を折るように{move}を向け直した"),
                ),
                vectors = listOf(
                    tri("짧은 영창으로", "with a clipped incantation", "短い詠唱で"),
                    tri("흔들리는 마력 사이로", "through shifting mana", "揺れる魔力の間から"),
                ),
                identity = tri("마력의 변칙", "arcane distortion", "魔力の変則"),
            ),
            BattleHeroClass.CLERIC to ClassTechnique(
                frames = listOf(
                    tri("호흡을 고르며 {moveObject} 펼쳤다", "guided {move}", "呼吸を整えながら{move}を展開した"),
                    tri("빛의 방벽과 함께 {moveObject} 보냈다", "carried {move}", "光の防壁とともに{move}を放った"),
                    tri("상처 난 자세를 세우며 {moveObject} 썼다", "used {move}", "崩れた姿勢を立て直しながら{move}を使った"),
                    tri("수비 동작 안에 {moveObject} 겹쳤다", "layered {move}", "防御動作の中へ{move}を重ねた"),
                    tri("고른 박자로 {moveObject} 이어 갔다", "sustained {move}", "整った拍子で{move}をつないだ"),
                ),
                vectors = listOf(
                    tri("안정된 중심에서", "from a steady center", "安定した重心から"),
                    tri("성광의 여운을 따라", "along the sacred afterglow", "聖光の余韻に沿って"),
                ),
                identity = tri("성광의 유지력", "sacred endurance", "聖光の持久力"),
            ),
            BattleHeroClass.PALADIN to ClassTechnique(
                frames = listOf(
                    tri("방패의 가장자리로 {moveObject} 밀었다", "drove {move}", "盾の縁から{move}を押し出した"),
                    tri("수호 자세를 유지하며 {moveObject} 내질렀다", "launched {move} from guard", "守護姿勢を保ち{move}を突き出した"),
                    tri("성광을 두른 채 {moveObject} 내리쳤다", "brought {move} down", "聖光をまとって{move}を振り下ろした"),
                    tri("막아 낸 힘으로 {moveObject} 돌려줬다", "returned {move}", "受け止めた力で{move}を返した"),
                    tri("한 걸음씩 {moveObject} 밀어붙였다", "advanced with {move}", "一歩ずつ{move}を押し込んだ"),
                ),
                vectors = listOf(
                    tri("닫힌 수비선에서", "from a sealed guard", "閉じた防御線から"),
                    tri("흔들리지 않는 보폭으로", "with unwavering steps", "揺れない歩幅で"),
                ),
                identity = tri("성갑의 전진", "hallowed advance", "聖鎧の前進"),
            ),
        )

        private val matchupFinishers = listOf(
            tri("중앙이 좁아졌다", "the center narrowed", "中央が狭まった"),
            tri("첫 공격선이 비껴 갔다", "the first attack line shifted", "最初の攻撃線がずれた"),
            tri("서로 다른 거리가 맞섰다", "two different ranges collided", "異なる間合いがぶつかった"),
            tri("빠른 쪽이 측면을 잡았다", "the faster side took the flank", "速い側が側面を取った"),
            tri("단단한 쪽이 길을 막았다", "the steadier side blocked the lane", "堅い側が道を塞いだ"),
            tri("첫 견제가 정보를 남겼다", "the first probe revealed a clue", "最初の牽制が手掛かりを残した"),
            tri("주도권이 한 박자 흔들렸다", "initiative wavered for one beat", "主導権が一拍揺れた"),
            tri("퇴로가 서로 반대로 갈렸다", "the retreats split apart", "退路が反対へ分かれた"),
            tri("정면보다 측면이 먼저 열렸다", "the flank opened before center", "正面より側面が先に開いた"),
            tri("첫 충돌이 긴장을 높였다", "first contact raised the tension", "最初の衝突が緊張を高めた"),
            tri("두 전법의 약점이 드러났다", "both styles exposed a weakness", "二つの戦法の弱点が見えた"),
            tri("안전한 거리가 빠르게 줄었다", "safe distance vanished quickly", "安全な間合いが急速に縮んだ"),
            tri("다음 선택이 더 중요해졌다", "the next choice gained weight", "次の選択が重くなった"),
            tri("시선과 발끝이 엇갈렸다", "eyes and feet crossed signals", "視線と足先が食い違った"),
            tri("공방의 속도가 일찍 정해졌다", "the battle found its pace early", "攻防の速度が早く決まった"),
        )

        val classActions = classTechniques.mapValues { (_, technique) ->
            buildList {
                technique.frames.forEach { frame ->
                    technique.vectors.forEach { vector ->
                        classFinishers.forEach { finisher ->
                            add(
                                tri(
                                    "{actorTopic} ${vector.ko} ${frame.ko}, ${finisher.ko}",
                                    "{actor} ${frame.en} ${vector.en} and ${finisher.en}",
                                    "{actor}は${vector.ja}${frame.ja}、${finisher.ja}",
                                ),
                            )
                        }
                    }
                }
            }
        }

        val matchupFlows = buildMap<Pair<BattleHeroClass, BattleHeroClass>, List<TriText>> {
            BattleHeroClass.entries.forEach { userClass ->
                BattleHeroClass.entries.forEach { opponentClass ->
                    val userStyle = classTechniques.getValue(userClass).identity
                    val opponentStyle = classTechniques.getValue(opponentClass).identity
                    put(
                        userClass to opponentClass,
                        matchupFinishers.map { finisher ->
                            tri(
                                "{user}의 ${battleKoreanWith(userStyle.ko)} {opponent}의 ${battleKoreanSubject(opponentStyle.ko)} 맞붙자, ${finisher.ko}",
                                "{user}'s ${userStyle.en} met {opponent}'s ${opponentStyle.en}, and ${finisher.en}",
                                "{user}の${userStyle.ja}と{opponent}の${opponentStyle.ja}が交差し、${finisher.ja}",
                            )
                        },
                    )
                }
            }
        }

        private val exchangeCores = listOf(
            tri("두 공격선이 중앙에서 교차해", "Both attack lines crossed at center and", "二つの攻撃線が中央で交差し、"),
            tri("짧은 충돌 뒤 위치가 바뀌어", "Positions changed after brief contact and", "短い衝突の後に位置が入れ替わり、"),
            tri("서로의 견제가 같은 틈을 노려", "Both probes targeted the same opening and", "互いの牽制が同じ隙を狙い、"),
            tri("엇갈린 발걸음이 거리를 바꿔", "Crossed footwork changed the distance and", "交差する足運びが間合いを変え、"),
            tri("공격과 회피가 한 박자에 겹쳐", "Attack and evasion shared one beat and", "攻撃と回避が一拍に重なり、"),
        )

        private val exchangeFinishers = listOf(
            tri("다음 수가 늦어졌다", "delayed the next move", "次の手が遅れた"),
            tri("주도권이 다시 비었다", "left initiative open again", "主導権が再び空いた"),
            tri("측면의 길이 열렸다", "opened a path on the flank", "側面の道が開いた"),
            tri("안전한 거리가 줄었다", "reduced the safe distance", "安全な間合いが縮んだ"),
            tri("반격의 방향이 드러났다", "revealed the counter angle", "反撃の方向が見えた"),
            tri("양쪽의 호흡이 끊겼다", "broke both rhythms", "両側の呼吸が途切れた"),
            tri("중앙의 압박이 커졌다", "increased pressure at center", "中央の圧力が増した"),
            tri("퇴로가 좁아졌다", "narrowed the retreat", "退路が狭まった"),
            tri("다음 공격이 빨라졌다", "hurried the next attack", "次の攻撃を速めた"),
            tri("수비선이 안쪽으로 밀렸다", "pushed the guard inward", "防御線が内側へ押された"),
            tri("서로의 습관이 드러났다", "exposed both habits", "互いの癖が見えた"),
            tri("전장의 중심이 흔들렸다", "shook the center of the field", "戦場の中心が揺れた"),
            tri("한 걸음의 차이를 남겼다", "left a one-step difference", "一歩の差を残した"),
        )

        val exchangeSummaries = buildList {
            exchangeCores.forEach { core ->
                exchangeFinishers.forEach { finisher ->
                    add(
                        tri(
                            core.ko + " " + finisher.ko,
                            core.en + " " + finisher.en,
                            core.ja + finisher.ja,
                        ),
                    )
                }
            }
        }.take(57)

        private fun expandPool(base: List<TriText>, tails: List<TriText>, target: Int): List<TriText> {
            val expanded = buildList {
                addAll(base)
                tails.forEach { tail -> base.forEach { item -> add(item.merge(tail)) } }
            }.distinct()
            require(expanded.size >= target) { "Pool can only provide ${expanded.size} of $target templates" }
            return expanded.take(target)
        }

        private fun expandRawPool(base: List<TriText>, tails: List<TriText>, target: Int): List<TriText> {
            val expanded = buildList {
                addAll(base)
                tails.forEach { tail -> base.forEach { item -> add(item.appendRaw(tail)) } }
            }.distinct()
            require(expanded.size >= target) { "Raw pool can only provide ${expanded.size} of $target templates" }
            return expanded.take(target)
        }

        val openings = listOf(
            tri("비가 막 그친 돌바닥에서 얕은 물결이 두 방향으로 갈라지며 {user}와 {opponent}의 첫 발을 드러냈다", "On rain-darkened stone, shallow ripples split in two and revealed the first steps of {user} and {opponent}", "雨上がりの石床で浅い波紋が二つに分かれ、{user}と{opponent}の最初の一歩を映し出した"),
            tri("부서진 돌기둥 사이로 짧은 메아리가 번지자 {user}와 {opponent}는 소리가 돌아오기 전에 거리를 정했다", "A short echo passed between broken pillars, and {user} and {opponent} fixed their distance before it returned", "崩れた石柱の間に短い反響が走り、{user}と{opponent}は音が戻る前に間合いを定めた"),
            tri("성벽을 훑는 거센 바람이 먼지의 방향을 바꾸며 {user}와 {opponent} 사이의 빈 길을 선명하게 그렸다", "A hard wind swept the rampart, turning the dust and drawing a clear lane between {user} and {opponent}", "城壁をなぞる強風が砂埃の向きを変え、{user}と{opponent}の間に一本の道を描いた"),
            tri("새벽 안개가 발목 높이에서 갈라지자 {user}와 {opponent}는 감춰졌던 퇴로를 동시에 확인했다", "As dawn mist parted at ankle height, {user} and {opponent} noticed the hidden escape routes at the same instant", "夜明けの霧が足元で割れ、{user}と{opponent}は隠れていた退路を同時に見定めた"),
            tri("마른 낙엽 한 장이 먼저 구르며 조용한 뜰을 가로지르자 {user}와 {opponent}의 시선이 그 끝에서 마주쳤다", "A single dry leaf rolled across the quiet court, and the eyes of {user} and {opponent} met where it stopped", "乾いた落ち葉が静かな中庭を転がり、その止まった先で{user}と{opponent}の視線が重なった"),
            tri("지하 홀의 횃불이 크게 흔들려 그림자의 길이가 바뀌자 {user}와 {opponent}는 서로 다른 각도로 움직였다", "The torches in the underground hall lurched and stretched every shadow, sending {user} and {opponent} along different angles", "地下広間の松明が大きく揺れて影の長さが変わり、{user}と{opponent}は別々の角度へ動いた"),
            tri("낡은 목조 다리가 낮게 울리며 체중 이동을 전하자 {user}와 {opponent}는 첫 공격보다 발의 위치를 먼저 바꾸었다", "The old wooden bridge carried each shift of weight in a low hum, so {user} and {opponent} moved their feet before striking", "古い木橋が低く軋んで重心の移動を伝え、{user}と{opponent}は攻撃より先に足の位置を変えた"),
            tri("빈 장터의 천막이 한꺼번에 부풀어 시야를 잘게 나누자 {user}와 {opponent}는 짧게 열린 틈을 따라 접근했다", "Market awnings billowed at once and broke the view into narrow gaps, which {user} and {opponent} used to close in", "無人の市場で天幕が一斉に膨らみ、細く開いた視界を縫って{user}と{opponent}が近づいた"),
            tri("깨진 석상 사이로 굴러간 돌조각이 멈추기도 전에 {user}와 {opponent}는 직선에서 비껴선 자리를 차지했다", "Before a stone chip stopped rolling among shattered statues, {user} and {opponent} had already claimed positions off the center line", "砕けた石像の間を転がる欠片が止まる前に、{user}と{opponent}は正面線を外れた位置を取った"),
            tri("해안 절벽의 물보라가 발소리를 삼키자 {user}와 {opponent}는 눈앞의 어깨와 발끝만으로 다음 움직임을 읽었다", "Sea spray swallowed every footfall on the cliff, leaving {user} and {opponent} to read only shoulders and toes", "海岸の崖で飛沫が足音を消し、{user}と{opponent}は肩とつま先だけから次の動きを読んだ"),
            tri("얇게 쌓인 눈 위로 서로 다른 발자국이 이어지며 {user}와 {opponent}가 숨기려던 접근 경로를 밝혀냈다", "Two trails crossed the thin snow and exposed the approaches that {user} and {opponent} had meant to conceal", "薄雪の上で二つの足跡が交差し、{user}と{opponent}が隠そうとした接近経路を明かした"),
            tri("해질녘 광장의 긴 그림자가 겹쳤다가 갈라지는 순간 {user}와 {opponent}는 첫 교환의 거리를 완성했다", "When the long shadows of the dusk plaza overlapped and split, {user} and {opponent} completed the distance for their first exchange", "夕暮れの広場で長い影が重なり、再び分かれた瞬間、{user}と{opponent}は最初の攻防の間合いを完成させた"),
            tri("버려진 온실의 깨진 유리마다 달빛이 다른 각도로 튕기자 {user}와 {opponent}는 반사광 사이에서 서로의 실루엣을 골라냈다", "Moonlight broke at a different angle in every pane of the abandoned glasshouse, and {user} and {opponent} picked out each other's silhouettes between the reflections", "廃温室の割れたガラスごとに月光が異なる角度へ弾け、{user}と{opponent}は反射光の間から互いの輪郭を見つけ出した"),
        )

        private val authoredFlows = mapOf(
            Situation.OPENING to listOf(
                tri("첫 움직임은 공격보다 탐색에 가까웠고, 두 방향의 발끝이 서로의 안전한 거리를 조금씩 지웠다", "The first movements were closer to measurement than attack, each set of feet shaving away the other's safe distance", "最初の動きは攻撃より探りに近く、二つの足運びが互いの安全な間合いを少しずつ削った"),
                tri("한 번의 헛동작이 먼저 반응을 끌어내며 정면보다 옆선이 빠른 길이라는 답을 남겼다", "A single feint drew the first reaction and showed that the flank offered a faster route than the center", "一度のフェイントが先に反応を引き出し、正面より側面が速い道だと示した"),
                tri("두 사람은 서두르지 않은 반걸음으로 시선과 무게중심을 시험하며 첫 공방의 규칙을 세웠다", "Both fighters tested gaze and balance with an unhurried half-step, setting the rules of the opening exchange", "二人は急がない半歩で視線と重心を探り、最初の攻防の規則を作った"),
                tri("서로 다른 박자의 접근이 중앙에서 겹치자 먼저 멈춘 쪽이 오히려 다음 선택을 숨길 수 있었다", "Their mismatched rhythms met at the center, and the one who stopped first gained room to hide the next choice", "異なる拍子の接近が中央で重なり、先に止まった側がかえって次の選択を隠した"),
                tri("짧은 견제 뒤에 남은 간격은 어느 쪽에도 편하지 않았고, 다음 발이 곧 주도권이 되었다", "The gap left after a brief probe favored neither side, making the next step itself the initiative", "短い牽制の後に残った間合いはどちらにも楽ではなく、次の一歩そのものが主導権になった"),
                tri("공격선이 한 차례 엇갈린 뒤 두 사람은 같은 자리를 다시 밟지 않으며 서로의 습관을 살폈다", "After their attack lines crossed once, neither fighter stepped in the same place again while they studied each other's habits", "攻撃線が一度交差した後、二人は同じ場所を踏まずに互いの癖を探った"),
            ),
            Situation.SKILL_CLASH to listOf(
                tri("서로 다른 기술의 궤적이 중앙에서 겹치며 힘보다 먼저 방향과 타이밍을 겨뤘다", "The paths of two different skills overlapped at center, testing direction and timing before raw force", "異なる二つの技の軌道が中央で重なり、力より先に方向とタイミングを競った"),
                tri("두 기술이 거의 동시에 펼쳐져 한쪽의 끝동작이 다른 쪽의 시작을 곧바로 밀어냈다", "Both skills opened almost together, and the finish of one immediately displaced the start of the other", "二つの技がほぼ同時に放たれ、一方の終動がもう一方の初動をすぐに押し返した"),
                tri("효과가 다른 두 기술이 같은 공간을 차지하려 하자 전장의 중심이 짧게 흔들렸다", "Two skills with different effects fought for the same space, making the center of the field waver", "性質の異なる二つの技が同じ空間を奪い合い、戦場の中心が短く揺れた"),
                tri("한 기술이 만든 틈을 다른 기술이 즉시 메우며 공방이 끊기지 않고 다음 합으로 이어졌다", "One skill immediately filled the gap left by the other, carrying the exchange into the next beat without a pause", "一つの技が作った隙を別の技がすぐに埋め、攻防は途切れず次の合へ続いた"),
            ),
            Situation.ATTACK_VS_GUARD to listOf(
                tri("공격선이 단단한 수비에 닿자 충격은 멈추지 않고 양옆의 빈 공간으로 갈라졌다", "When the attack line met a firm guard, the force split into the open space on both sides", "攻撃線が堅い防御に触れると、衝撃は止まらず左右の空間へ分かれた"),
                tri("막는 쪽은 힘을 정면에서 받지 않았고, 밀어붙이는 쪽은 바뀐 각도를 따라 공격을 고쳤다", "The defender refused the force head-on, while the attacker corrected course along the changed angle", "守る側は力を正面で受けず、攻める側は変わった角度に沿って攻撃を修正した"),
                tri("수비가 닫히는 순간 공격은 가장자리로 미끄러져 다음 빈틈을 찾는 견제로 바뀌었다", "As the guard closed, the attack slid toward its edge and became a probe for the next opening", "防御が閉じる瞬間、攻撃は端へ滑り、次の隙を探る牽制へ変わった"),
                tri("한쪽이 자리를 지키고 다른 쪽이 각도를 바꾸며 힘과 위치의 싸움이 선명해졌다", "One side held ground while the other changed angles, making the contest between force and position unmistakable", "片方が場所を守り、もう片方が角度を変え、力と位置の争いが鮮明になった"),
            ),
            Situation.DOUBLE_GUARD to listOf(
                tri("두 사람 모두 공격을 거두자 짧은 정적 속에서 발끝과 시선만 다음 수를 주고받았다", "Both fighters withheld their attacks, leaving only feet and eyes to exchange the next move in the silence", "二人とも攻撃を収め、短い静寂の中で足先と視線だけが次の手を交わした"),
                tri("서로의 수비가 동시에 닫혀 전장은 멈춘 듯 보였지만 안전한 거리는 조금씩 줄어들었다", "Both guards closed together; the field seemed still, yet the safe distance continued to shrink", "互いの防御が同時に閉じ、戦場は止まったように見えたが、安全な間合いは少しずつ縮んだ"),
                tri("급한 공격 대신 중심을 지키는 선택이 겹치며 다음 한 번의 중요성이 더 커졌다", "Both chose balance over a rushed attack, increasing the weight of the next exchange", "性急な攻撃より重心を守る選択が重なり、次の一度の重要さが増した"),
                tri("방어 자세가 마주 선 사이 작은 체중 이동 하나도 공격의 예고처럼 또렷하게 보였다", "With guarded stances facing each other, every small shift of weight looked like a declaration of attack", "防御姿勢が向き合う中、小さな重心移動さえ攻撃の予告のように鮮明に見えた"),
            ),
            Situation.RECOVERY to listOf(
                tri("흐트러졌던 호흡이 짧게 고르게 돌아오며 끊길 듯한 움직임이 다시 이어졌다", "A briefly steadied breath restored movement that had been close to breaking", "乱れていた呼吸が短く整い、途切れかけた動きが再びつながった"),
                tri("회복에 쓴 한순간 동안 공격선은 줄었지만 다음 교환을 버틸 자세는 더 단단해졌다", "The attack line receded for an instant of recovery, but the stance for the next exchange grew firmer", "回復に使った一瞬だけ攻撃線は下がったが、次の攻防に耐える姿勢は強くなった"),
                tri("거칠어진 움직임이 정돈되자 발의 간격과 시선이 다시 같은 방향을 향했다", "As rough movement settled, footing and gaze aligned in the same direction once more", "荒れた動きが整い、足幅と視線が再び同じ方向を向いた"),
                tri("공세 사이에 만든 짧은 여유가 호흡을 되찾는 시간으로 바뀌며 전투의 속도가 달라졌다", "A small pause carved out between attacks became time to recover, changing the pace of the fight", "攻勢の間に作った短い余裕が呼吸を取り戻す時間へ変わり、戦いの速度が変化した"),
            ),
            Situation.CRITICAL to listOf(
                tri("정확히 맞은 한 번의 충격이 두 사람 사이의 박자를 크게 벌려 다음 대응을 늦췄다", "One precisely placed impact opened a wide break in the rhythm and delayed the next response", "正確に決まった一撃が二人の拍子を大きく引き離し、次の対応を遅らせた"),
                tri("작은 빈틈을 통과한 공격이 중심을 흔들며 평범하던 교환을 결정적인 순간으로 바꾸었다", "An attack through a narrow opening shook the balance and turned an ordinary exchange into a critical moment", "小さな隙を通った攻撃が重心を揺らし、通常の攻防を決定的な瞬間へ変えた"),
                tri("궤적의 끝이 정확한 지점에 닿자 이어질 예정이던 움직임들이 한 박자씩 밀렸다", "When the end of the line found its exact point, every planned follow-up slipped a beat late", "軌道の先が正確な地点へ届き、続くはずだった動きが一拍ずつ遅れた"),
                tri("응축된 힘이 짧은 순간에 풀리며 전장의 거리와 시선이 동시에 다시 정렬됐다", "Compressed force released in an instant, realigning distance and attention across the field", "凝縮された力が一瞬で解け、戦場の間合いと視線が同時に並び直された"),
            ),
            Situation.PRESSURE to listOf(
                tri("퇴로가 하나씩 닫히며 넓던 전장은 한 번의 회피만 허용하는 좁은 길로 바뀌었다", "Escape routes closed one by one until the broad field allowed room for only a single evasion", "退路が一つずつ閉じ、広かった戦場は一度の回避しか許さない細道へ変わった"),
                tri("짧은 공격이 연달아 겹치자 수비하는 쪽은 반격보다 먼저 발을 놓을 자리를 찾아야 했다", "Short attacks overlapped in quick succession, forcing the defender to find footing before attempting a counter", "短い攻撃が重なるにつれ、守る側は反撃より先に足場を探さなければならなかった"),
                tri("공세의 속도보다 끊어지는 박자가 더 큰 압박이 되어 다음 반응을 서둘러 끌어냈다", "The broken rhythm pressed harder than raw speed and hurried the next response into view", "攻勢の速さより途切れる拍子が強い圧力となり、次の反応を急がせた"),
                tri("한쪽이 중앙을 차지하자 다른 쪽의 움직임은 공격이 아니라 남은 공간을 지키는 선택부터 시작됐다", "Once one side claimed the center, the other had to protect the remaining space before thinking of attack", "片方が中央を取ると、もう片方は攻撃より残された空間を守る選択から始めた"),
                tri("연속된 전진이 시야와 거리를 함께 좁혀 작은 실수도 곧바로 다음 공세의 길이 되었다", "Repeated advances narrowed both sight and distance, turning every small mistake into a lane for the next assault", "連続する前進が視界と間合いを同時に狭め、小さなミスさえ次の攻勢への道になった"),
                tri("숨을 고를 틈이 사라지자 방어 동작 하나가 끝나기 전에 다음 공격선이 이미 겹쳐 들어왔다", "With no pause left to breathe, the next attack line arrived before the defensive motion had fully ended", "息を整える間が消え、防御動作が終わる前に次の攻撃線が重なってきた"),
            ),
            Situation.EXCHANGE to listOf(
                tri("공격과 방어가 같은 자리에서 맞물려 두 사람의 위치만 처음과 반대로 바뀌었다", "Attack and defense locked at the same point, leaving only the fighters' positions reversed", "攻撃と防御が同じ場所で噛み合い、二人の位置だけが最初と逆になった"),
                tri("짧은 충돌 뒤에도 누구도 멀리 떨어지지 않아 다음 수가 한 호흡 안에서 이어졌다", "Neither fighter gave ground after the brief collision, and the next choices followed within the same breath", "短い衝突の後も誰も離れず、次の一手が同じ呼吸の中で続いた"),
                tri("서로 다른 사거리의 장점이 번갈아 살아나며 우세는 한쪽에 오래 머물지 않았다", "Each range took its turn offering an advantage, and control refused to stay with either side", "異なる間合いの長所が交互に働き、優勢はどちらにも長く留まらなかった"),
                tri("맞부딪친 힘이 비슷해지자 작은 발의 위치와 시선의 방향이 다음 교환을 갈랐다", "With force nearly even, the placement of a foot and the direction of a glance divided the next exchange", "ぶつかる力が拮抗すると、足の位置と視線の向きが次の攻防を分けた"),
                tri("빠른 움직임과 느린 판단이 교차하며 어느 쪽도 완전히 흐름을 장악하지 못했다", "Fast movement crossed with patient judgment, preventing either side from taking complete control", "速い動きと慎重な判断が交差し、どちらも流れを完全には握れなかった"),
                tri("한 차례씩 길을 내주고 되찾는 공방이 반복되어 중앙의 빈 공간만 계속 자리를 바꾸었다", "Each side yielded and reclaimed a lane in turn, so only the empty center kept changing hands", "道を譲っては奪い返す攻防が続き、中央の空間だけが何度も持ち主を変えた"),
            ),
            Situation.REVERSAL to listOf(
                tri("밀리던 발이 남겨 둔 반걸음이 되받아칠 공간으로 바뀌며 공격선의 방향이 뒤집혔다", "A half-step left during retreat became room for a counter, reversing the direction of the attack line", "押されながら残した半歩が反撃の空間に変わり、攻撃線の向きが反転した"),
                tri("앞서 보인 반복 습관을 읽은 대응이 정면의 힘을 비껴 보내며 주도권을 되찾았다", "A response built on a repeated habit redirected the frontal force and reclaimed the initiative", "先に見えた反復の癖を読んだ対応が正面の力を逸らし、主導権を取り戻した"),
                tri("막아 내는 동작이 끝나기 전에 새로운 공격 각도가 열려 수세가 곧바로 공세로 변했다", "A new attack angle opened before the block was complete, turning defense directly into offense", "受け止める動作が終わる前に新しい攻撃角度が開き、守勢がそのまま攻勢へ変わった"),
                tri("위기에서 선택한 한 걸음이 가장 강한 공격을 허공으로 흘리고 전장의 중심을 바꾸었다", "One step chosen under pressure sent the strongest attack wide and shifted the center of the field", "危機で選んだ一歩が最も強い攻撃を空へ流し、戦場の中心を変えた"),
                tri("실수처럼 보였던 빈틈이 추격을 끌어들이자 미리 남겨 둔 반격의 길이 선명해졌다", "What looked like an opening drew the pursuit in, revealing a counter lane prepared in advance", "隙に見えた場所が追撃を誘い込み、あらかじめ残していた反撃の道が鮮明になった"),
                tri("급하게 이어진 추격이 예상보다 깊어지며 유리하던 거리가 오히려 빠져나오기 어려운 간격이 되었다", "The hurried pursuit ran deeper than intended, turning a favorable distance into one that was hard to escape", "急いだ追撃が予想以上に深くなり、有利だった間合いがかえって抜け出しにくい距離へ変わった"),
            ),
            Situation.DESPERATE to listOf(
                tri("두 사람의 움직임이 모두 짧아졌지만 남은 한 걸음을 먼저 내주려는 쪽은 없었다", "Both fighters shortened every movement, yet neither would surrender the final step", "二人の動きはどちらも短くなったが、残る一歩を先に譲る者はいなかった"),
                tri("여유가 거의 사라진 거리에서 공격과 회피는 시작하자마자 다음 선택을 요구했다", "At a distance with no room left, every attack and evasion demanded the next choice as soon as it began", "余裕のほとんどない間合いで、攻撃も回避も始まると同時に次の選択を迫った"),
                tri("한 번의 흔들림도 되돌리기 어려운 순간이라 두 사람은 가장 작은 동작만 남겼다", "With no room to recover from a stumble, both fighters reduced their movements to the smallest essentials", "一度の揺らぎも戻しにくい局面で、二人は最小限の動きだけを残した"),
                tri("남은 힘보다 정확한 발의 위치가 중요해져 공방은 느려졌지만 긴장은 더 높아졌다", "Foot placement mattered more than remaining strength, slowing the exchange while sharpening its tension", "残る力より正確な足の位置が重要になり、攻防は遅くなったが緊張はさらに高まった"),
            ),
            Situation.DOMINANT to listOf(
                tri("기울어진 전세 속에서 우세한 쪽은 넓은 길을 차지하고 다른 쪽은 한 줄의 퇴로만 지켰다", "With the battle tilted, the leading side held the broad lanes while the other protected a single route out", "傾いた戦況の中、優勢側は広い道を取り、もう一方は一本の退路だけを守った"),
                tri("연속된 성공이 전장의 중심을 한쪽으로 밀어 수세에 놓인 움직임을 가장자리로 몰았다", "Repeated successes pushed the center toward one side and forced the defending movement to the edge", "連続する成功が戦場の中心を一方へ押し、守勢の動きを端へ追いやった"),
                tri("넓어진 체력 차이만큼 선택할 수 있는 공간도 달라져 한쪽은 압박하고 다른 쪽은 길을 만들었다", "As the health gap widened, so did the difference in available space: one pressed while the other carved a path", "体力差が広がるほど選べる空間も変わり、一方は圧力をかけ、もう一方は道を作った"),
                tri("우세를 잡은 움직임은 서두르지 않고 퇴로부터 지워 반격이 시작될 위치를 제한했다", "The leading movement stayed patient and erased escape routes first, limiting where a counter could begin", "優勢を取った側は急がず退路から消し、反撃が始まる位置を制限した"),
            ),
            Situation.LONG_BATTLE to listOf(
                tri("긴 공방으로 처음의 빠른 동작은 사라졌지만 두 사람의 판단은 오히려 더 짧고 정확해졌다", "The long exchange had stripped away the early speed, but both fighters now made shorter, sharper decisions", "長い攻防で序盤の速い動きは消えたが、二人の判断はかえって短く正確になった"),
                tri("여러 합 동안 드러난 습관이 쌓여 작은 자세 변화만으로도 다음 공격의 방향을 읽을 수 있었다", "Habits exposed across many exchanges made the direction of the next attack readable from the smallest shift", "幾合もの間に現れた癖が積み重なり、小さな姿勢の変化だけで次の攻撃方向が読めた"),
                tri("힘을 아끼는 움직임과 남은 거리를 관리하는 발걸음이 장기전의 새로운 박자를 만들었다", "Conserved movement and careful distance management formed a new rhythm for the extended fight", "力を温存する動きと残る間合いを管理する足運びが、長期戦の新しい拍子を作った"),
                tri("반복된 충돌 뒤에는 화려한 동작보다 같은 실수를 피하는 선택이 더 큰 차이를 만들었다", "After repeated collisions, avoiding the same mistake mattered more than any elaborate motion", "繰り返す衝突の後では、派手な動きより同じ誤りを避ける選択が大きな差を作った"),
            ),
            Situation.TURNING_POINT to listOf(
                tri("같은 박자가 두 번 이어진 뒤 세 번째 움직임만 늦어져 익숙해진 방어가 먼저 빗나갔다", "After the same rhythm appeared twice, only the third movement came late, causing the familiar defense to miss first", "同じ拍子が二度続いた後、三度目だけが遅れ、慣れた防御の方が先に外れた"),
                tri("지금까지 아껴 둔 거리 변화가 가장 자신 있던 공격을 끊으며 공방의 규칙을 새로 썼다", "A withheld change of distance cut off the most confident attack and rewrote the terms of the exchange", "温存していた間合いの変化が得意な攻撃を断ち、攻防の規則を書き換えた"),
                tri("장비가 버틴 짧은 순간이 반격의 시작점이 되어 흩어지던 움직임을 한 방향으로 모았다", "The brief instant held by the equipment became the start of a counter and gathered scattered movement into one direction", "装備が耐えた短い瞬間が反撃の起点となり、散っていた動きを一つの方向へ集めた"),
                tri("반복되던 공방의 순서가 한 번 깨지자 준비한 다음 행동들이 서로 다른 시간에 도착했다", "Once the repeated order broke, the prepared follow-ups arrived at different times", "繰り返された攻防の順序が一度崩れ、準備していた次の動きが別々の時間に届いた"),
                tri("눈앞의 공격보다 그 뒤에 남을 위치를 고른 판단이 다음 장면의 유리함을 먼저 가져왔다", "Choosing the position left after the attack mattered more than the attack itself and secured the next advantage", "目の前の攻撃より、その後に残る位置を選んだ判断が次の優位を先に取った"),
                tri("잠깐 멎은 발소리가 오히려 방향을 숨겨 두 사람의 대응을 서로 다른 쪽으로 갈라놓았다", "A brief silence in the footsteps concealed direction and split the two responses apart", "一瞬止まった足音がかえって方向を隠し、二人の対応を別々の側へ分けた"),
            ),
            Situation.FINAL_CLOSE to listOf(
                tri("앞선 공방에서 쌓인 거리와 박자의 차이가 마지막 한 걸음에 모두 모였다", "Every difference in distance and rhythm built through the fight gathered into the final step", "それまでの攻防で積み重なった間合いと拍子の差が、最後の一歩に集まった"),
                tri("남은 거리가 거의 사라지자 회피와 추격이 한 호흡 안에서 끝을 향해 이어졌다", "With almost no distance left, evasion and pursuit ran toward the finish within a single breath", "残る間合いがほとんど消え、回避と追撃が一息の中で決着へ続いた"),
                tri("처음에 읽어 둔 습관이 마지막 빈틈으로 돌아와 어느 쪽도 수를 거둘 수 없게 했다", "A habit read at the opening returned as the final gap, leaving neither side able to withdraw a move", "最初に読んだ癖が最後の隙として戻り、どちらも手を引けなくなった"),
                tri("빠르게 오가던 발소리가 동시에 멎고, 두 사람은 남겨 둔 한 수를 같은 순간에 꺼냈다", "The rapid footsteps stopped together, and both fighters released their reserved move at the same instant", "激しく行き交った足音が同時に止まり、二人は残していた一手を同じ瞬間に出した"),
                tri("여러 번 바뀐 주도권 끝에 작은 체중 이동 하나가 마지막 방어의 방향을 먼저 정했다", "After control changed hands again and again, one small weight shift chose the direction of the final defense", "何度も主導権が入れ替わった末、小さな重心移動が最後の防御の向きを先に決めた"),
                tri("흩어진 먼지가 가라앉기 전에 공격과 방어가 다시 겹치며 결말로 가는 길을 하나만 남겼다", "Before the scattered dust could settle, attack and defense overlapped again and left only one path to the end", "舞い上がった砂埃が落ち着く前に攻撃と防御が再び重なり、結末への道を一つだけ残した"),
            ),
            Situation.FINAL_DECISIVE to listOf(
                tri("앞서 넓어진 차이가 마지막 교환의 선택지를 줄여 결말로 향하는 길을 선명하게 만들었다", "The advantage built earlier narrowed the choices in the final exchange and made the path to the finish clear", "先に広がった差が最後の攻防の選択肢を減らし、決着への道を鮮明にした"),
                tri("한쪽이 지켜 온 주도권이 마지막 거리까지 이어져 공격과 퇴로의 방향을 함께 정했다", "Control held by one side carried into the final distance and dictated both attack and escape", "一方が保ってきた主導権が最後の間合いまで続き、攻撃と退路の方向を同時に決めた"),
                tri("여러 차례 닫힌 퇴로 끝에 남은 공간은 마지막 방어가 설 한 자리뿐이었다", "After escape routes closed again and again, only one place remained for the final defense", "退路が何度も閉じられ、残った空間は最後の防御が立つ一か所だけだった"),
                tri("쌓인 압박이 마지막 박자에 풀리며 앞선 모든 선택을 하나의 공격선으로 모았다", "Accumulated pressure released on the final beat and gathered every prior choice into one attack line", "積み重なった圧力が最後の拍子で解け、それまでの選択を一本の攻撃線へ集めた"),
                tri("결정적인 위치를 먼저 차지한 움직임이 마지막 교환에서도 흔들리지 않고 이어졌다", "The movement that claimed the decisive position first remained unbroken through the final exchange", "決定的な位置を先に取った動きが、最後の攻防でも揺らがず続いた"),
                tri("방어가 되돌아갈 공간까지 사라지자 마지막 공격은 처음 정한 길을 끝까지 통과했다", "With no space left for the guard to recover, the final attack completed the route chosen at its start", "防御が戻る空間まで消え、最後の攻撃は最初に定めた道を最後まで通り抜けた"),
            ),
            Situation.OPENING_AGGRESSIVE to listOf(
                tri("첫 호흡부터 두 기술이 중앙을 향해 뻗어 탐색할 시간 없이 공격선이 겹쳤다", "Both skills reached for the center on the first breath, overlapping before either side had time to probe", "最初の呼吸から二つの技が中央へ伸び、探る間もなく攻撃線が重なった"),
                tri("거리를 재는 반걸음 대신 빠른 진입이 맞부딪치며 전투의 속도를 곧바로 끌어올렸다", "Fast entries collided in place of measuring half-steps and immediately raised the battle's pace", "間合いを測る半歩の代わりに速い踏み込みが衝突し、戦いの速度を一気に上げた"),
            ),
            Situation.OPENING_CAUTIOUS to listOf(
                tri("두 수비가 동시에 닫히며 첫 공격보다 상대의 습관을 읽는 시간이 길어졌다", "Both guards closed together, extending the time spent reading habits before the first attack", "二つの防御が同時に閉じ、最初の攻撃より相手の癖を読む時間が長くなった"),
                tri("누구도 중심을 먼저 비우지 않아 작은 시선 이동과 발끝만이 첫 정보를 주고받았다", "Neither fighter abandoned center first, so only eyes and feet exchanged the opening information", "誰も先に中心を空けず、視線と足先だけが最初の情報を交わした"),
            ),
            Situation.SKILL_VS_GUARD to listOf(
                tri("펼쳐진 기술이 닫힌 수비에 닿자 힘은 멈추지 않고 방벽의 가장자리를 따라 흘렀다", "The released skill met a closed guard, and its force flowed along the barrier's edge instead of stopping", "放たれた技が閉じた防御へ触れ、力は止まらず防壁の縁を流れた"),
                tri("수비는 기술의 중심을 피하고 끝동작만 받아 내며 반격할 간격을 남겼다", "The guard avoided the skill's center and caught only its finish, preserving distance for a counter", "防御は技の中心を避けて終動だけを受け、反撃の間合いを残した"),
            ),
            Situation.DOUBLE_CRITICAL to listOf(
                tri("두 결정타가 같은 박자에 닿아 충격과 중심 이동이 전장의 양쪽에서 동시에 일어났다", "Two critical blows landed on the same beat, shifting impact and balance on both sides of the field", "二つの決定打が同じ拍子で届き、衝撃と重心移動が戦場の両側で同時に起きた"),
                tri("서로의 빈틈을 놓치지 않은 공격이 교차하며 어느 쪽도 온전한 자세로 남지 못했다", "Attacks that caught both openings crossed, leaving neither fighter in a complete stance", "互いの隙を逃さない攻撃が交差し、どちらも完全な姿勢では残れなかった"),
            ),
            Situation.USER_REVERSAL to listOf(
                tri("밀리던 쪽의 짧은 각도 변화가 추격선을 비껴 가며 잃었던 중앙을 되찾았다", "A short change of angle slipped past the pursuit and reclaimed the center that had been lost", "押されていた側の短い角度変更が追撃線を外れ、失った中央を取り戻した"),
                tri("수세에서 남겨 둔 한 걸음이 반격의 시작점이 되어 체력 차이와 위치를 함께 뒤집었다", "One step preserved under pressure became the counter's starting point and reversed both health gap and position", "守勢で残した一歩が反撃の起点となり、体力差と位置を同時に覆した"),
            ),
            Situation.OPPONENT_REVERSAL to listOf(
                tri("앞서가던 움직임이 한 걸음 깊어지자 상대가 남겨 둔 반격선이 중앙을 가로질렀다", "The leading movement went one step too deep, allowing the reserved counter line to cross the center", "先行していた動きが一歩深くなり、相手が残した反撃線が中央を横切った"),
                tri("유리했던 거리가 추격과 함께 무너지며 주도권이 반대쪽 발끝으로 넘어갔다", "A favorable distance collapsed during pursuit, passing initiative to the opposing feet", "有利だった間合いが追撃とともに崩れ、主導権が反対側の足元へ移った"),
            ),
            Situation.LAST_STAND to listOf(
                tri("물러날 체력이 거의 남지 않은 쪽은 움직임을 줄이고 마지막 반격선 하나만 지켰다", "With almost no strength left to retreat, one side reduced every motion and protected a single counter line", "退く力がほとんど残らない側は動きを減らし、最後の反撃線一つだけを守った"),
                tri("한 번의 충돌이 끝이 될 수 있는 거리에서 방어와 공격의 구분이 사라졌다", "At a distance where one collision could end everything, the distinction between defense and attack disappeared", "一度の衝突が終わりになり得る間合いで、防御と攻撃の区別が消えた"),
            ),
            Situation.USER_DOMINANT to listOf(
                tri("연속된 전진이 상대의 퇴로를 가장자리로 몰아 중앙의 넓은 선택지를 차지했다", "Repeated advances drove the opposing retreat to the edge and claimed the broad choices at center", "連続する前進が相手の退路を端へ追い、中央の広い選択肢を占めた"),
                tri("쌓인 우세를 서두르지 않고 거리로 바꾸며 반격이 시작될 자리부터 지웠다", "The accumulated advantage became controlled distance, erasing the places from which a counter could begin", "積み重ねた優勢を急がず間合いへ変え、反撃が始まる場所から消していった"),
            ),
            Situation.OPPONENT_DOMINANT to listOf(
                tri("상대의 압박이 중앙과 측면을 차례로 닫아 남은 움직임을 좁은 퇴로에 묶었다", "Opposing pressure closed center and flank in sequence, binding the remaining movement to a narrow retreat", "相手の圧力が中央と側面を順に閉じ、残る動きを狭い退路へ縛った"),
                tri("되찾으려던 거리가 다시 끊기며 수세의 선택은 공격보다 생존할 위치에 집중됐다", "Every attempt to restore distance was cut off, forcing the defense to choose survival ground before attack", "取り戻そうとした間合いが再び断たれ、守勢の選択は攻撃より生き残る位置へ集中した"),
            ),
        )

        val flows = Situation.entries.associateWith { situation ->
            matchupFinishers.take(4).map { finisher ->
                tri(
                    "${situation.title(AppLanguage.KOREAN)}에서 ${finisher.ko}",
                    "${situation.title(AppLanguage.ENGLISH)}: ${finisher.en}",
                    "${situation.title(AppLanguage.JAPANESE)}では${finisher.ja}",
                )
            }
        }

        val skillActions = BattleSkillKind.entries.associateWith { kind -> skillPool(kind) }

        val basicActions = listOf(
            tri("{actorTopic} {equipmentCue}짧은 공격으로 반응을 먼저 끌어냈다{criticalTail}", "{actor} {equipmentCue}drew the first reaction with a compact attack{criticalTail}", "{actor}は{equipmentCue}短い攻撃で先に反応を引き出した{criticalTail}"),
            tri("{actorTopic} {equipmentCue}어깨를 노리는 척하다가 비어 있는 옆선으로 파고들었다{criticalTail}", "{actor} {equipmentCue}feinted toward the shoulder and entered through the open flank{criticalTail}", "{actor}は{equipmentCue}肩を狙うふりから空いた側面へ踏み込んだ{criticalTail}"),
            tri("{actorTopic} {equipmentCue}닿기 직전 공격을 거두고 반대쪽 발을 묶었다{criticalTail}", "{actor} {equipmentCue}withdrew the attack just before contact and pinned the opposite foot{criticalTail}", "{actor}は{equipmentCue}届く直前に攻撃を引き、反対側の足を止めた{criticalTail}"),
            tri("{actorTopic} {equipmentCue}연속된 견제로 안전한 거리를 조금씩 지웠다{criticalTail}", "{actor} {equipmentCue}used repeated probes to erase the safe distance little by little{criticalTail}", "{actor}は{equipmentCue}連続する牽制で安全な間合いを少しずつ削った{criticalTail}"),
            tri("{actorTopic} {equipmentCue}낮게 들어간 뒤 몸을 세우며 공격선을 위로 바꾸었다{criticalTail}", "{actor} {equipmentCue}entered low, then rose and redirected the attack line upward{criticalTail}", "{actor}は{equipmentCue}低く入り、体を起こしながら攻撃線を上へ変えた{criticalTail}"),
            tri("{actorTopic} {equipmentCue}먼저 내민 발을 미끼로 삼아 반대 각도의 공격을 이었다{criticalTail}", "{actor} {equipmentCue}used the leading foot as bait and followed from the opposite angle{criticalTail}", "{actor}は{equipmentCue}先に出した足を囮にし、反対の角度から攻撃を続けた{criticalTail}"),
            tri("{actorTopic} {equipmentCue}빠른 첫 동작 뒤에 느린 두 번째 동작을 붙여 방어의 박자를 흔들었다{criticalTail}", "{actor} {equipmentCue}followed a fast opening motion with a slow second one, disturbing the defensive rhythm{criticalTail}", "{actor}は{equipmentCue}速い初動の後に遅い二動目を重ね、防御の拍子を崩した{criticalTail}"),
            tri("{actorTopic} {equipmentCue}정면을 한 번 두드린 뒤 곧바로 비어 있는 아래쪽을 노렸다{criticalTail}", "{actor} {equipmentCue}tested the center once, then immediately targeted the opening below{criticalTail}", "{actor}は{equipmentCue}正面を一度叩き、すぐに空いた下側を狙った{criticalTail}"),
        )

        val guardActions = listOf(
            tri("{actorTopic} {equipmentCue}몸을 반걸음 틀어 들어오는 충격을 옆으로 흘렸다", "{actor} {equipmentCue}turned half a step and guided the incoming force aside", "{actor}は{equipmentCue}半歩だけ体をひねり、迫る衝撃を横へ流した"),
            tri("{actorTopic} {equipmentCue}한 치만 물러나 공격 끝이 지나갈 길을 비워 두었다", "{actor} {equipmentCue}gave back an inch and opened a lane for the tip of the attack to pass", "{actor}は{equipmentCue}わずかに下がり、攻撃の先が通り過ぎる道を空けた"),
            tri("{actorTopic} {equipmentCue}급한 반격을 참으며 다음 움직임이 시작될 자리를 지켰다", "{actor} {equipmentCue}held back a rushed counter and protected the ground needed for the next move", "{actor}は{equipmentCue}性急な反撃を抑え、次の動きが始まる場所を守った"),
            tri("{actorTopic} {equipmentCue}정면의 힘을 받지 않고 낮은 자세로 공격선을 비껴 냈다", "{actor} {equipmentCue}refused the force head-on and slipped the attack line past a lowered stance", "{actor}は{equipmentCue}正面から力を受けず、低い姿勢で攻撃線を外した"),
            tri("{actorTopic} {equipmentCue}두 발의 간격을 넓혀 흔들린 중심을 빠르게 되찾았다", "{actor} {equipmentCue}widened the stance and quickly recovered a shaken center", "{actor}は{equipmentCue}両足の幅を広げ、揺れた重心を素早く取り戻した"),
            tri("{actorTopic} {equipmentCue}공격이 완성되기 전 안쪽으로 붙어 가장 강한 궤적을 막았다", "{actor} {equipmentCue}closed inside before the attack formed and smothered its strongest line", "{actor}は{equipmentCue}攻撃が完成する前に内側へ入り、最も強い軌道を封じた"),
            tri("{actorTopic} {equipmentCue}연속된 충격을 한곳에서 버티지 않고 발을 옮겨 나누어 받았다", "{actor} {equipmentCue}shifted position instead of absorbing repeated impacts at one point", "{actor}は{equipmentCue}連続する衝撃を一か所で受けず、足を運んで分散させた"),
            tri("{actorTopic} {equipmentCue}시선을 공격 끝에 두면서도 몸은 다음 각도를 향해 열어 두었다", "{actor} {equipmentCue}kept eyes on the attack's edge while leaving the body open toward the next angle", "{actor}は{equipmentCue}攻撃の先を見ながら、体は次の角度へ開いておいた"),
        )

        val equipmentCues = mapOf(
            BattleEquipmentSlot.WEAPON to listOf(
                tri("{itemObject} 비스듬히 세워 ", "with {item} set on a slant, ", "{item}を斜めに構え、"),
                tri("{item}의 끝을 낮게 두고 ", "keeping the tip of {item} low, ", "{item}の先を低く保ち、"),
                tri("{item}의 궤적을 짧게 접어 ", "shortening the arc of {item}, ", "{item}の軌道を小さく畳み、"),
            ),
            BattleEquipmentSlot.HEAD to listOf(
                tri("{item} 아래로 시선을 고정한 채 ", "with a steady gaze beneath {item}, ", "{item}の下で視線を定め、"),
                tri("{item}에 스친 빛으로 거리를 재며 ", "measuring distance by the light across {item}, ", "{item}をかすめる光で距離を測り、"),
                tri("{item}가 가린 시야의 경계를 따라 ", "working along the edge of vision beneath {item}, ", "{item}が遮る視界の境をたどり、"),
            ),
            BattleEquipmentSlot.BODY to listOf(
                tri("{item}에 닿은 충격을 옆으로 흘리며 ", "letting an impact slide across {item}, ", "{item}に触れた衝撃を横へ流し、"),
                tri("{item}가 지켜 준 짧은 순간을 이용해 ", "using the brief instant protected by {item}, ", "{item}が守った短い瞬間を使い、"),
                tri("{item}의 무게로 흔들림을 눌러 ", "using the weight of {item} to steady the body, ", "{item}の重みで揺れを抑え、"),
            ),
            BattleEquipmentSlot.HANDS to listOf(
                tri("{itemDirection} 손목의 흔들림을 눌러 ", "using {item} to steady the wrists, ", "{item}で手首の揺れを抑え、"),
                tri("{item}의 마찰로 손끝의 방향을 붙잡아 ", "letting {item} hold the direction of the hands, ", "{item}の摩擦で手先の向きを保ち、"),
                tri("{item}에 힘을 고르게 나누어 ", "spreading force evenly through {item}, ", "{item}へ力を均等に分け、"),
            ),
            BattleEquipmentSlot.FEET to listOf(
                tri("{itemDirection} 바닥을 힘껏 밀어 ", "driving against the ground through {item}, ", "{item}で床を強く蹴り、"),
                tri("{item}의 밑창으로 미끄러짐을 멈춰 ", "using the soles of {item} to stop the slide, ", "{item}の底で滑りを止め、"),
                tri("{item}에 체중을 짧게 실어 ", "placing a brief burst of weight through {item}, ", "{item}へ一瞬だけ体重を乗せ、"),
            ),
            BattleEquipmentSlot.ACCESSORY to listOf(
                tri("{item}의 흔들림을 가라앉히며 ", "as the motion of {item} settled, ", "{item}の揺れを静めながら、"),
                tri("{item}가 되돌린 작은 빛을 따라 ", "following the small glint returned by {item}, ", "{item}が返した小さな光を追い、"),
                tri("{item}의 무게를 움직임의 기준으로 삼아 ", "using the weight of {item} as a point of balance, ", "{item}の重みを動きの基準にして、"),
            ),
        )

        val traitObservations = listOf(
            tri("{traitOwnerTopic} {trait}의 면모를 보이며 같은 실수를 두 번 허용하지 않았다", "{traitOwner} showed {trait}, refusing to allow the same mistake twice", "{traitOwner}は{trait}を見せ、同じ誤りを二度は許さなかった"),
            tri("공방이 거칠어질수록 {traitOwnerPossessive} 움직임에는 {trait}의 습관이 더 선명해졌다", "As the exchange intensified, {traitOwner}'s movement revealed {trait} more clearly", "攻防が激しくなるほど、{traitOwner}の動きには{trait}がはっきり表れた"),
            tri("{traitOwnerTopic} 짧은 판단마다 {traitObject} 드러내며 다음 자리를 미리 남겨 두었다", "Each brief decision from {traitOwner} displayed {trait} and preserved space for the next move", "{traitOwner}は短い判断のたびに{trait}を示し、次の動きの場所を残した"),
            tri("{traitOwnerPossessive} {traitTopic} 힘보다 움직임의 순서를 고르는 방식에서 나타났다", "{traitOwner}'s {trait} appeared not in raw force but in the order of movement", "{traitOwner}の{trait}は力ではなく、動きの順序を選ぶ形で現れた"),
            tri("{traitOwnerTopic} {trait}답게 흔들린 순간에도 먼저 시선과 발의 위치를 바로잡았다", "True to {trait}, {traitOwner} corrected gaze and footing first even when balance wavered", "{traitOwner}は{trait}らしく、崩れた瞬間にも視線と足場を先に整えた"),
            tri("{trait}의 전투 습관이 {traitOwnerPossessive} 다음 선택에 자연스럽게 이어졌다", "The fighting habit of {trait} carried naturally into {traitOwner}'s next choice", "{trait}という戦い方が、{traitOwner}の次の選択へ自然につながった"),
        )

        val criticalTails = listOf(
            tri(", 마지막 충격은 예상보다 깊게 중심을 흔들었다", ", and the final impact shook the balance harder than expected", "、最後の衝撃は予想以上に重心を揺らした"),
            tri(", 정확히 맞은 끝동작이 방어의 박자를 크게 어긋나게 했다", ", and the precisely timed finish knocked the defensive rhythm far off beat", "、正確に決まった終動が防御の拍子を大きくずらした"),
            tri(", 빈틈을 놓치지 않은 한 번이 다음 대응까지 늦췄다", ", and the strike through the opening delayed even the next response", "、隙を逃さない一撃が次の対応まで遅らせた"),
            tri(", 힘이 모인 순간의 충돌이 남아 있던 간격을 단번에 지웠다", ", and the focused collision erased the remaining distance at once", "、力が集まった瞬間の衝突が残る間合いを一気に消した"),
            tri(", 짧게 압축된 힘이 풀리며 전장의 흐름을 크게 흔들었다", ", and the release of compressed force jolted the flow of the field", "、短く圧縮された力が解け、戦場の流れを大きく揺らした"),
            tri(", 궤적의 끝이 정확히 닿아 상대의 다음 발을 묶었다", ", and the exact end of the line caught the opponent's next step", "、軌道の先が正確に届き、相手の次の一歩を縛った"),
        )

        val outcomes = mapOf(
            BattleOutcome.USER_WIN to outcomePool(userWins = true),
            BattleOutcome.USER_LOSS to outcomePool(userWins = false),
            BattleOutcome.DRAW to listOf(
                tri("마지막 공격이 같은 자리에서 멈추며 승부는 무승부로 끝났다", "The final attacks stopped at the same point, and the battle ended in a draw", "最後の攻撃が同じ場所で止まり、勝負は引き分けに終わった"),
                tri("어느 쪽도 마지막 간격을 넘지 못해 승부는 무승부로 끝났다", "Neither side crossed the final gap, so the battle ended in a draw", "どちらも最後の間合いを越えられず、勝負は引き分けに終わった"),
                tri("공격과 방어가 끝까지 맞물린 채 풀리지 않아 승부는 무승부로 끝났다", "Attack and defense remained locked to the end, leaving the battle a draw", "攻撃と防御が最後まで噛み合ったまま解けず、勝負は引き分けに終わった"),
                tri("남은 힘과 거리가 나란히 소진되며 승부는 무승부로 끝났다", "Strength and distance ran out together, and the battle ended in a draw", "残る力と間合いが同時に尽き、勝負は引き分けに終わった"),
                tri("마지막 교환에서도 어느 쪽의 공격선도 완전히 열리지 않아 승부는 무승부로 끝났다", "No attack line opened completely in the final exchange, and the battle ended in a draw", "最後の攻防でもどちらの攻撃線も開き切らず、勝負は引き分けに終わった"),
                tri("두 사람의 마지막 한 수가 서로의 길을 막아 승부는 무승부로 끝났다", "Each fighter's last move blocked the other's path, and the battle ended in a draw", "二人の最後の一手が互いの道を塞ぎ、勝負は引き分けに終わった"),
            ),
        )

        private fun skillPool(kind: BattleSkillKind): List<TriText> = when (kind) {
            BattleSkillKind.STRIKE -> listOf(
                tri("{actorTopic} {equipmentCue}{skillObject} 짧고 무거운 궤도로 펼쳐 정면의 빈틈을 눌렀다{criticalTail}", "{actor} {equipmentCue}drove {skill} through a short, heavy arc and pressed the opening in front{criticalTail}", "{actor}は{equipmentCue}{skill}を短く重い軌道で繰り出し、正面の隙を押さえた{criticalTail}"),
                tri("{actorTopic} {equipmentCue}{skillObject} 몸의 회전과 함께 이어 방어선 안쪽으로 밀어 넣었다{criticalTail}", "{actor} {equipmentCue}carried {skill} through a turn of the body and drove it inside the guard{criticalTail}", "{actor}は{equipmentCue}体の回転に{skill}を重ね、防御線の内側へ押し込んだ{criticalTail}"),
                tri("{actorTopic} {equipmentCue}{skillObject} 위에서 아래로 접어 내려 상대의 발을 멈추게 했다{criticalTail}", "{actor} {equipmentCue}folded {skill} downward and forced the opposing feet to stop{criticalTail}", "{actor}は{equipmentCue}{skill}を上から下へ畳み、相手の足を止めた{criticalTail}"),
                tri("{actorTopic} {equipmentCue}{skillObject} 맞부딪친 힘에 겹쳐 다음 동작이 나올 공간을 줄였다{criticalTail}", "{actor} {equipmentCue}layered {skill} over the collision and reduced the room for a follow-up{criticalTail}", "{actor}は{equipmentCue}ぶつかる力に{skill}を重ね、次の動作の空間を削った{criticalTail}"),
                tri("{actorTopic} {equipmentCue}{skillObject} 반걸음 늦게 꺼내 먼저 움직인 쪽의 빈틈을 파고들었다{criticalTail}", "{actor} {equipmentCue}released {skill} half a step late and entered the gap left by the first mover{criticalTail}", "{actor}は{equipmentCue}{skill}を半歩遅らせ、先に動いた側の隙へ踏み込んだ{criticalTail}"),
                tri("{actorTopic} {equipmentCue}{skillObject} 한 번 멈춘 뒤 다시 이어 방어의 두 번째 박자를 끊었다{criticalTail}", "{actor} {equipmentCue}paused {skill} once, then resumed it to break the defense's second beat{criticalTail}", "{actor}は{equipmentCue}{skill}を一度止めてから再開し、防御の二拍目を断った{criticalTail}"),
            )
            BattleSkillKind.ARCANE -> listOf(
                tri("{actorTopic} {equipmentCue}{skillObject} 좁은 빛의 결로 모아 시야와 공격선을 함께 갈랐다{criticalTail}", "{actor} {equipmentCue}compressed {skill} into a narrow seam of light that divided sight and attack line together{criticalTail}", "{actor}は{equipmentCue}{skill}を細い光の筋へ集め、視界と攻撃線を同時に分けた{criticalTail}"),
                tri("{actorTopic} {equipmentCue}{skillObject} 발밑에서부터 펼쳐 안전하던 자리를 바꾸었다{criticalTail}", "{actor} {equipmentCue}unfolded {skill} from the ground up and displaced the previously safe ground{criticalTail}", "{actor}は{equipmentCue}{skill}を足元から展開し、安全だった場所を変えた{criticalTail}"),
                tri("{actorTopic} {equipmentCue}{skillObject} 짧게 응축한 뒤 한 방향으로 풀어 움직임의 길을 좁혔다{criticalTail}", "{actor} {equipmentCue}condensed {skill}, then released it in one direction to narrow the lane of movement{criticalTail}", "{actor}は{equipmentCue}{skill}を短く凝縮し、一方向へ放って動きの道を狭めた{criticalTail}"),
                tri("{actorTopic} {equipmentCue}{skillObject} 흔들리는 그림자에 겹쳐 실제 도달 시점을 감췄다{criticalTail}", "{actor} {equipmentCue}layered {skill} over the shifting shadows and concealed the instant it would arrive{criticalTail}", "{actor}は{equipmentCue}{skill}を揺れる影に重ね、届く瞬間を隠した{criticalTail}"),
                tri("{actorTopic} {equipmentCue}{skillObject} 낮게 흘려 보낸 뒤 반사된 빛을 따라 각도를 바꾸었다{criticalTail}", "{actor} {equipmentCue}sent {skill} low, then changed its angle along the reflected light{criticalTail}", "{actor}は{equipmentCue}{skill}を低く流し、反射した光に沿って角度を変えた{criticalTail}"),
                tri("{actorTopic} {equipmentCue}{skillObject} 넓게 퍼뜨리지 않고 한 지점에 집중해 방어의 중심을 흔들었다{criticalTail}", "{actor} {equipmentCue}focused {skill} on one point instead of spreading it wide, shaking the center of the guard{criticalTail}", "{actor}は{equipmentCue}{skill}を広げず一点へ集中し、防御の中心を揺らした{criticalTail}"),
            )
            BattleSkillKind.PIERCE -> listOf(
                tri("{actorTopic} {equipmentCue}{skillObject} 낮고 곧은 선으로 보내 발밑의 빈틈을 꿰뚫었다{criticalTail}", "{actor} {equipmentCue}sent {skill} along a low, straight line through the opening near the feet{criticalTail}", "{actor}は{equipmentCue}{skill}を低く真っ直ぐ放ち、足元の隙を射抜いた{criticalTail}"),
                tri("{actorTopic} {equipmentCue}{skillObject} 가장 짧은 경로로 밀어 넣어 회피할 방향을 하나 지웠다{criticalTail}", "{actor} {equipmentCue}drove {skill} along the shortest route and erased one direction of escape{criticalTail}", "{actor}は{equipmentCue}{skill}を最短の経路へ通し、回避方向を一つ消した{criticalTail}"),
                tri("{actorTopic} {equipmentCue}{skillObject} 시선보다 먼저 뻗어 수비가 닫히기 전의 틈을 노렸다{criticalTail}", "{actor} {equipmentCue}extended {skill} ahead of the gaze and targeted the gap before the guard closed{criticalTail}", "{actor}は{equipmentCue}視線より先に{skill}を伸ばし、防御が閉じる前の隙を狙った{criticalTail}"),
                tri("{actorTopic} {equipmentCue}{skillObject} 한 점에 모아 넓은 방어선의 연결부를 끊었다{criticalTail}", "{actor} {equipmentCue}focused {skill} on one point and severed the joint in the broad defensive line{criticalTail}", "{actor}は{equipmentCue}{skill}を一点へ集め、広い防御線の継ぎ目を断った{criticalTail}"),
                tri("{actorTopic} {equipmentCue}{skillObject} 비껴가는 듯 보내다 마지막 순간 안쪽으로 꺾었다{criticalTail}", "{actor} {equipmentCue}sent {skill} as if it would pass wide, then bent it inward at the last instant{criticalTail}", "{actor}は{equipmentCue}外れるように{skill}を放ち、最後の瞬間に内側へ曲げた{criticalTail}"),
                tri("{actorTopic} {equipmentCue}{skillObject} 앞선 견제와 같은 높이에서 시작해 전혀 다른 끝점을 노렸다{criticalTail}", "{actor} {equipmentCue}started {skill} at the same height as the earlier probe but aimed for a wholly different end point{criticalTail}", "{actor}は{equipmentCue}先ほどの牽制と同じ高さから{skill}を始め、まったく別の終点を狙った{criticalTail}"),
            )
            BattleSkillKind.CONTROL -> listOf(
                tri("{actorTopic} {equipmentCue}{skillObject} 이동 경로에 펼쳐 상대가 고를 수 있는 발판을 줄였다{criticalTail}", "{actor} {equipmentCue}spread {skill} across the movement lanes and reduced the available footing{criticalTail}", "{actor}は{equipmentCue}{skill}を移動経路へ広げ、相手が選べる足場を減らした{criticalTail}"),
                tri("{actorTopic} {equipmentCue}{skillObject} 공격보다 먼저 놓아 다음 회피의 방향을 제한했다{criticalTail}", "{actor} {equipmentCue}placed {skill} before attacking and restricted the direction of the next evasion{criticalTail}", "{actor}は{equipmentCue}攻撃より先に{skill}を置き、次の回避方向を制限した{criticalTail}"),
                tri("{actorTopic} {equipmentCue}{skillObject} 두 사람 사이에 남겨 접근과 이탈의 박자를 갈랐다{criticalTail}", "{actor} {equipmentCue}left {skill} between the fighters and split the rhythms of approach and retreat{criticalTail}", "{actor}は{equipmentCue}二人の間に{skill}を残し、接近と離脱の拍子を分けた{criticalTail}"),
                tri("{actorTopic} {equipmentCue}{skillObject} 넓게 쓰지 않고 퇴로 하나에 집중해 움직임을 예측 가능하게 만들었다{criticalTail}", "{actor} {equipmentCue}focused {skill} on one escape route rather than spreading it wide, making movement predictable{criticalTail}", "{actor}は{equipmentCue}{skill}を広げず一つの退路へ集中し、動きを読みやすくした{criticalTail}"),
                tri("{actorTopic} {equipmentCue}{skillObject} 발이 닿을 자리에 겹쳐 공격보다 위치 선택을 먼저 흔들었다{criticalTail}", "{actor} {equipmentCue}overlaid {skill} on the next landing point and disrupted positioning before attack{criticalTail}", "{actor}は{equipmentCue}次に足が着く場所へ{skill}を重ね、攻撃より先に位置選びを崩した{criticalTail}"),
                tri("{actorTopic} {equipmentCue}{skillObject} 짧게 끊어 사용하며 익숙해진 이동 박자를 무너뜨렸다{criticalTail}", "{actor} {equipmentCue}used {skill} in short intervals and broke the familiar movement rhythm{criticalTail}", "{actor}は{equipmentCue}{skill}を短く区切って使い、慣れた移動の拍子を崩した{criticalTail}"),
            )
            BattleSkillKind.RECOVER -> listOf(
                tri("{actorTopic} {equipmentCue}{skillObject} 호흡에 맞춰 펼쳐 흐트러진 자세를 빠르게 바로잡았다", "{actor} {equipmentCue}used {skill} with the rhythm of the breath and quickly restored a broken stance", "{actor}は{equipmentCue}呼吸に合わせて{skill}を使い、崩れた姿勢を素早く整えた"),
                tri("{actorTopic} {equipmentCue}{skillObject} 움직임을 멈추지 않은 채 이어 다음 교환을 준비했다", "{actor} {equipmentCue}maintained movement while using {skill} and prepared for the next exchange", "{actor}は{equipmentCue}動きを止めずに{skill}を使い、次の攻防へ備えた"),
                tri("{actorTopic} {equipmentCue}{skillObject} 짧게 집중해 흔들린 중심과 발의 간격을 되찾았다", "{actor} {equipmentCue}focused {skill} briefly and recovered balance and footing", "{actor}は{equipmentCue}{skill}へ短く集中し、揺れた重心と足幅を取り戻した"),
                tri("{actorTopic} {equipmentCue}{skillObject} 방어 동작 안에 겹쳐 빈틈을 크게 열지 않고 흐름을 정돈했다", "{actor} {equipmentCue}layered {skill} into a defensive motion and restored order without opening a large gap", "{actor}は{equipmentCue}防御動作に{skill}を重ね、大きな隙を見せず流れを整えた"),
                tri("{actorTopic} {equipmentCue}{skillObject} 한 호흡 동안 유지해 다음 움직임에 필요한 여유를 만들었다", "{actor} {equipmentCue}sustained {skill} for one breath and made room for the next movement", "{actor}は{equipmentCue}一呼吸の間{skill}を保ち、次の動きに必要な余裕を作った"),
                tri("{actorTopic} {equipmentCue}{skillObject} 급하게 펼치지 않고 안전한 간격에서 완성했다", "{actor} {equipmentCue}completed {skill} from a safe distance without rushing it", "{actor}は{equipmentCue}急がず安全な間合いで{skill}を完成させた"),
            )
        }

        private fun outcomePool(userWins: Boolean): List<TriText> {
            val winner = if (userWins) "{user}" else "{opponent}"
            val loser = if (userWins) "{opponent}" else "{user}"
            val winnerSubject = if (userWins) "{userSubject}" else "{opponentSubject}"
            val loserSubject = if (userWins) "{opponentSubject}" else "{userSubject}"
            return listOf(
                tri("마지막 일격으로 ${winnerSubject} 승리하고 ${loserSubject} 패배했다", "$winner won the final clash; $loser was defeated", "最後の一撃で${winner}が勝利し、${loser}は敗北した"),
                tri("빈틈을 찔러 ${winnerSubject} 승리하고 ${loserSubject} 패배했다", "$winner seized the opening and won; $loser was defeated", "隙を突いて${winner}が勝利し、${loser}は敗北した"),
                tri("한발 먼저 닿아 ${winnerSubject} 승리하고 ${loserSubject} 패배했다", "$winner struck first and won; $loser was defeated", "一手早く届き、${winner}が勝利し、${loser}は敗北した"),
                tri("마지막 방어를 깨며 ${winnerSubject} 승리하고 ${loserSubject} 패배했다", "$winner broke the last guard and won; $loser was defeated", "最後の守りを破り、${winner}が勝利し、${loser}は敗北した"),
                tri("퇴로를 닫아 ${winnerSubject} 승리하고 ${loserSubject} 패배했다", "$winner closed the escape and won; $loser was defeated", "退路を塞ぎ、${winner}が勝利し、${loser}は敗北した"),
                tri("정확한 한 수로 ${winnerSubject} 승리하고 ${loserSubject} 패배했다", "$winner landed cleanly and won; $loser was defeated", "正確な一手で${winner}が勝利し、${loser}は敗北した"),
            )
        }
    }

    private fun tri(ko: String, en: String, ja: String): TriText = TriText(ko, en, ja)

    private fun AppLanguage.pick(ko: String, en: String, ja: String): String = when (this) {
        AppLanguage.KOREAN -> ko
        AppLanguage.ENGLISH -> en
        AppLanguage.JAPANESE -> ja
    }
}

internal fun battleNarratedAction(round: BattleRound): BattleRoundAction =
    listOf(round.userAction, round.opponentAction).maxWith(
        compareBy<BattleRoundAction> { if (it.finisher) 1 else 0 }
            .thenBy { if (it.critical) 1 else 0 }
            .thenBy { it.damage + it.healing + it.selfDamage }
            .thenBy { if (it.kind == BattleActionKind.SKILL) 1 else 0 }
            .thenBy { if (it.actor == com.nullplaying.model.BattleSide.USER) 1 else 0 },
    )

internal fun battleDeflectedActionText(
    actionText: String,
    defenderName: String,
    language: AppLanguage,
    variation: Int,
): String {
    val base = actionText.trim().removeSuffix(".").removeSuffix("。")
    val tails = when (language) {
        AppLanguage.KOREAN -> listOf(
            "${battleKoreanSubject(defenderName)} 방어선을 닫아 피해는 없었다",
            "${battleKoreanSubject(defenderName)} 궤적을 흘려 충격은 닿지 않았다",
            "${battleKoreanSubject(defenderName)} 사거리 밖으로 빠져 공격은 빗나갔다",
            "${battleKoreanSubject(defenderName)} 정면에서 받아 내 에너지는 줄지 않았다",
        )
        AppLanguage.ENGLISH -> listOf(
            "$defenderName closed the guard and took no damage",
            "$defenderName turned the arc aside before impact",
            "$defenderName slipped beyond reach and the attack missed",
            "$defenderName stopped it cleanly and lost no energy",
        )
        AppLanguage.JAPANESE -> listOf(
            "${defenderName}が守りを閉じ、ダメージはなかった",
            "${defenderName}が軌道を受け流し、衝撃は届かなかった",
            "${defenderName}が間合いの外へ退き、攻撃は外れた",
            "${defenderName}が正面で受け止め、エネルギーは減らなかった",
        )
    }
    val tail = tails[Math.floorMod(variation, tails.size)]
    return when (language) {
        AppLanguage.JAPANESE -> "${base}が、$tail。"
        else -> "$base; $tail."
    }
}

internal fun battleZeroDamageActionText(
    name: String,
    opponentName: String,
    action: BattleRoundAction,
    skills: List<BattleSkillSnapshot>,
    language: AppLanguage,
    variation: Int,
    defenderDefeated: Boolean = false,
    actorDefeated: Boolean = false,
): String {
    val move = skills.firstOrNull { it.skillId == action.skillId }
        ?.let { localizedBattleSkillName(it, language) }
        ?: when (language) {
            AppLanguage.KOREAN -> "공격"
            AppLanguage.ENGLISH -> "attack"
            AppLanguage.JAPANESE -> "攻撃"
        }
    val index = Math.floorMod(variation, 4)
    if (action.finisher && !action.finisherSucceeded && action.selfDamage > 0) {
        return when (language) {
            AppLanguage.KOREAN -> if (actorDefeated) {
                "${battleKoreanPossessive(name)} ${battleKoreanSubject(move)} 빗나가자, ${battleKoreanTopic(name)} 반동을 견디지 못했다."
            } else {
                "${battleKoreanPossessive(name)} ${battleKoreanSubject(move)} 빗나가며 힘이 크게 빠졌다."
            }
            AppLanguage.ENGLISH -> if (actorDefeated) {
                "$name's $move missed, and $name could not withstand the recoil."
            } else {
                "$name's $move missed, draining much of their strength."
            }
            AppLanguage.JAPANESE -> if (actorDefeated) {
                "${name}の${move}は外れ、${name}は反動に耐えられなかった。"
            } else {
                "${name}の${move}は外れ、力を大きく消耗した。"
            }
        }
    }
    if (action.resolution == BattleActionResolution.BLOCKED && defenderDefeated) {
        return when (language) {
            AppLanguage.KOREAN -> "${battleKoreanSubject(opponentName)} ${battleKoreanPossessive(name)} ${battleKoreanObject(move)} 막았지만 남은 힘이 다했다."
            AppLanguage.ENGLISH -> "$opponentName blocked $name's $move, but had no strength left."
            AppLanguage.JAPANESE -> "${opponentName}は${name}の${move}を防いだが、力が尽きた。"
        }
    }
    if (action.resolution == BattleActionResolution.EVADED) {
        return when (language) {
            AppLanguage.KOREAN -> listOf(
                "${battleKoreanSubject(opponentName)} ${battleKoreanPossessive(name)} ${battleKoreanObject(move)} 피했다.",
                "${battleKoreanSubject(opponentName)} 몸을 틀어 ${battleKoreanPossessive(name)} ${battleKoreanObject(move)} 흘렸다.",
                "${battleKoreanSubject(opponentName)} 한발 비켜 ${battleKoreanPossessive(name)} ${battleKoreanObject(move)} 피했다.",
                "${battleKoreanSubject(opponentName)} 사거리 밖으로 빠져 ${battleKoreanPossessive(name)} ${battleKoreanSubject(move)} 닿지 않았다.",
            )[index]
            AppLanguage.ENGLISH -> listOf(
                "$opponentName evaded $name's $move.",
                "$opponentName turned aside and slipped past $name's $move.",
                "$opponentName sidestepped $name's $move.",
                "$opponentName moved beyond the reach of $name's $move.",
            )[index]
            AppLanguage.JAPANESE -> listOf(
                "${opponentName}は${name}の${move}をかわした。",
                "${opponentName}は身をひねり、${name}の${move}を受け流した。",
                "${opponentName}は一歩ずれて${name}の${move}を避けた。",
                "${opponentName}は間合いの外へ退き、${name}の${move}は届かなかった。",
            )[index]
        }
    }
    if (action.resolution == BattleActionResolution.MISSED) {
        return when (language) {
            AppLanguage.KOREAN -> listOf(
                "${battleKoreanPossessive(name)} ${battleKoreanSubject(move)} 빗나갔다.",
                "${battleKoreanTopic(name)} 거리를 잘못 읽어 ${battleKoreanObject(move)} 놓쳤다.",
                "${battleKoreanPossessive(name)} ${battleKoreanSubject(move)} 허공을 갈랐다.",
                "${battleKoreanPossessive(name)} ${battleKoreanSubject(move)} 사거리에 닿지 않았다.",
            )[index]
            AppLanguage.ENGLISH -> listOf(
                "$name's $move missed.",
                "$name misjudged the distance and missed with $move.",
                "$name's $move cut through empty air.",
                "$name's $move fell short of its target.",
            )[index]
            AppLanguage.JAPANESE -> listOf(
                "${name}の${move}は外れた。",
                "${name}は間合いを読み違え、${move}を外した。",
                "${name}の${move}は空を切った。",
                "${name}の${move}は間合いに届かなかった。",
            )[index]
        }
    }
    if (action.resolution == BattleActionResolution.BLOCKED) {
        return when (language) {
            AppLanguage.KOREAN -> listOf(
                "${battleKoreanSubject(opponentName)} ${battleKoreanPossessive(name)} ${battleKoreanObject(move)} 막았다.",
                "${battleKoreanSubject(opponentName)} ${battleKoreanPossessive(name)} ${battleKoreanObject(move)} 받아 냈다.",
                "${battleKoreanPossessive(opponentName)} 방어가 ${battleKoreanPossessive(name)} ${battleKoreanObject(move)} 흘렸다.",
                "${battleKoreanPossessive(name)} ${battleKoreanSubject(move)} ${battleKoreanPossessive(opponentName)} 방어선을 뚫지 못했다.",
            )[index]
            AppLanguage.ENGLISH -> listOf(
                "$opponentName blocked $name's $move.",
                "$opponentName absorbed $name's $move behind the guard.",
                "$opponentName's guard turned aside $name's $move.",
                "$name's $move failed to break through $opponentName's guard.",
            )[index]
            AppLanguage.JAPANESE -> listOf(
                "${opponentName}は${name}の${move}を防いだ。",
                "${opponentName}は守りを固め、${name}の${move}を受け止めた。",
                "${opponentName}の防御が${name}の${move}を受け流した。",
                "${name}の${move}は${opponentName}の守りを破り切れなかった。",
            )[index]
        }
    }
    return when (language) {
        AppLanguage.KOREAN -> listOf(
            "${battleKoreanSubject(opponentName)} ${battleKoreanPossessive(name)} ${battleKoreanObject(move)} 막았다.",
            "${battleKoreanSubject(opponentName)} ${battleKoreanPossessive(name)} ${battleKoreanObject(move)} 흘려냈다.",
            "${battleKoreanPossessive(name)} ${battleKoreanSubject(move)} 간발의 차로 빗나갔다.",
            "${battleKoreanPossessive(name)} ${battleKoreanSubject(move)} 사거리에 닿지 않았다.",
        )[index]
        AppLanguage.ENGLISH -> listOf(
            "$name's $move was blocked.",
            "$name's $move was turned aside.",
            "$name's $move narrowly missed.",
            "$name's $move fell short of its target.",
        )[index]
        AppLanguage.JAPANESE -> listOf(
            "${name}の${move}は防がれた。",
            "${name}の${move}は受け流された。",
            "${name}の${move}は紙一重で外れた。",
            "${name}の${move}は間合いに届かなかった。",
        )[index]
    }
}

private data class LocalizedBattleSkillName(
    val ko: String,
    val en: String,
    val ja: String,
)

private val localizedBattleSkillNames = mapOf(
    "luen-shadow-chain" to LocalizedBattleSkillName("그림자 연격", "Shadow Flurry", "影連撃"),
    "luen-fog-step" to LocalizedBattleSkillName("안개 걸음", "Mist Step", "霧歩"),
    "luen-moon-feint" to LocalizedBattleSkillName("달빛 속임수", "Moonlit Feint", "月光のフェイント"),
    "luen-silent-pierce" to LocalizedBattleSkillName("무음 관통", "Silent Pierce", "無音貫通"),
    "mira-orbit-shot" to LocalizedBattleSkillName("궤도 사격", "Orbital Shot", "軌道射撃"),
    "mira-wind-pin" to LocalizedBattleSkillName("바람 쐐기", "Wind Spike", "風の楔"),
    "mira-hawk-volley" to LocalizedBattleSkillName("매의 연사", "Hawk Volley", "鷹の連射"),
    "mira-sky-turn" to LocalizedBattleSkillName("하늘 선회", "Skyward Turn", "天空旋回"),
    "kain-ice-cleave" to LocalizedBattleSkillName("빙벽 가르기", "Icewall Cleave", "氷壁斬り"),
    "kain-iron-counter" to LocalizedBattleSkillName("철갑 반격", "Iron Counter", "鉄甲反撃"),
    "kain-frost-charge" to LocalizedBattleSkillName("서리 돌진", "Frost Charge", "霜の突進"),
    "kain-glacier-roar" to LocalizedBattleSkillName("빙하의 포효", "Glacier Roar", "氷河の咆哮"),
    "sera-eclipse-wave" to LocalizedBattleSkillName("월식 파동", "Eclipse Wave", "月蝕波動"),
    "sera-gravity-knot" to LocalizedBattleSkillName("중력 매듭", "Gravity Knot", "重力結び"),
    "sera-star-fragment" to LocalizedBattleSkillName("성운 파편", "Nebula Shard", "星雲の欠片"),
    "sera-night-orbit" to LocalizedBattleSkillName("밤의 궤도", "Night Orbit", "夜の軌道"),
    "eve-holy-ripple" to LocalizedBattleSkillName("성광 파문", "Holy Ripple", "聖光波紋"),
    "eve-calm-prayer" to LocalizedBattleSkillName("고요한 기도", "Calm Prayer", "静かな祈り"),
    "eve-silver-verdict" to LocalizedBattleSkillName("은빛 심판", "Silver Judgment", "銀光の審判"),
    "eve-sanctuary-bell" to LocalizedBattleSkillName("성역의 종", "Sanctuary Bell", "聖域の鐘"),
    "arin-dawn-counter" to LocalizedBattleSkillName("서광 반격", "Dawn Counter", "暁光反撃"),
    "arin-oath-guard" to LocalizedBattleSkillName("맹세의 수호", "Oathguard", "誓いの守護"),
    "arin-radiant-wall" to LocalizedBattleSkillName("찬란한 방벽", "Radiant Barrier", "輝く防壁"),
    "arin-daybreak-charge" to LocalizedBattleSkillName("여명 돌진", "Daybreak Charge", "黎明突進"),
    "noa-smoke-thrust" to LocalizedBattleSkillName("연무 찌르기", "Smoke Thrust", "煙霧突き"),
    "noa-false-step" to LocalizedBattleSkillName("거짓 발걸음", "False Step", "偽りの足運び"),
    "noa-ash-cross" to LocalizedBattleSkillName("재의 교차", "Ash Cross", "灰の交差"),
    "noa-haze-swap" to LocalizedBattleSkillName("아지랑이 전환", "Haze Shift", "陽炎転換"),
    "raon-blue-trail" to LocalizedBattleSkillName("청람 궤적", "Azure Trail", "青藍の軌跡"),
    "raon-crosswind" to LocalizedBattleSkillName("엇바람 사격", "Crosswind Shot", "横風射撃"),
    "raon-rain-volley" to LocalizedBattleSkillName("소나기 연사", "Rain Volley", "豪雨連射"),
    "raon-tailwind-step" to LocalizedBattleSkillName("순풍 걸음", "Tailwind Step", "追い風歩法"),
    "core-warrior-rush" to LocalizedBattleSkillName("돌진 가르기", "Charging Cleave", "突進斬り"),
    "core-warrior-bash" to LocalizedBattleSkillName("방패 밀치기", "Shield Bash", "盾打ち"),
    "core-warrior-breath" to LocalizedBattleSkillName("불굴의 호흡", "Unyielding Breath", "不屈の呼吸"),
    "core-rogue-vital" to LocalizedBattleSkillName("급소 찌르기", "Vital Thrust", "急所突き"),
    "core-rogue-afterimage" to LocalizedBattleSkillName("잔상 교란", "Afterimage Feint", "残像攪乱"),
    "core-rogue-return" to LocalizedBattleSkillName("그림자 되치기", "Shadow Counter", "影返し"),
    "core-ranger-volley" to LocalizedBattleSkillName("연속 사격", "Rapid Volley", "連続射撃"),
    "core-ranger-trap" to LocalizedBattleSkillName("바람 덫", "Wind Trap", "風の罠"),
    "core-ranger-breath" to LocalizedBattleSkillName("매의 호흡", "Hawk's Breath", "鷹の呼吸"),
    "core-mage-flame-ring" to LocalizedBattleSkillName("화염 고리", "Flame Ring", "炎の輪"),
    "core-mage-frost-ward" to LocalizedBattleSkillName("서리 결계", "Frost Ward", "霜の結界"),
    "core-mage-cycle" to LocalizedBattleSkillName("마력 순환", "Mana Cycle", "魔力循環"),
    "core-cleric-judgment" to LocalizedBattleSkillName("심판의 빛", "Judgment Light", "審判の光"),
    "core-cleric-sanctuary" to LocalizedBattleSkillName("성역 파동", "Sanctuary Wave", "聖域波動"),
    "core-cleric-heal" to LocalizedBattleSkillName("치유 기도", "Healing Prayer", "癒やしの祈り"),
    "core-paladin-radiance" to LocalizedBattleSkillName("성광 베기", "Radiant Slash", "聖光斬り"),
    "core-paladin-vow" to LocalizedBattleSkillName("철벽 맹세", "Ironwall Oath", "鉄壁の誓い"),
    "core-paladin-prayer" to LocalizedBattleSkillName("수호의 기도", "Guardian's Prayer", "守護の祈り"),
)

private fun localizedBattleSkillName(
    skill: BattleSkillSnapshot,
    language: AppLanguage,
): String {
    val named = localizedBattleSkillNames[skill.skillId]
    return if (named == null) {
        localized(skill.displayName, language)
    } else {
        when (language) {
            AppLanguage.KOREAN -> named.ko
            AppLanguage.ENGLISH -> named.en
            AppLanguage.JAPANESE -> named.ja
        }
    }
}

internal fun localizedBattleSkillNameForTest(
    skillId: String,
    language: AppLanguage,
): String = localizedBattleSkillName(
    skill = BattleSkillSnapshot(skillId = skillId, displayName = "한국어 기술명"),
    language = language,
)

internal fun expandedLocalBattleActionText(
    name: String,
    action: BattleRoundAction,
    skills: List<BattleSkillSnapshot>,
    equipment: List<BattleEquipmentSnapshot>,
    variation: Int,
): String = BattleLocalNarrativeEngine.actionTextForTest(
    name = name,
    action = action,
    skills = skills,
    equipment = equipment,
    language = AppLanguage.KOREAN,
    variation = variation,
)

internal fun expandedLocalBattleRoundIndexes(
    rounds: List<BattleRound>,
    phaseCount: Int,
    skipOpeningRound: Boolean = false,
): List<Int> {
    if (rounds.size <= 1) return List(phaseCount) { 0 }
    if (rounds.size < phaseCount) {
        return List(phaseCount) { index ->
            (index * rounds.lastIndex.toDouble() / (phaseCount - 1).coerceAtLeast(1))
                .toInt()
                .coerceIn(0, rounds.lastIndex)
        }
    }
    val firstNarratedRound = if (skipOpeningRound && rounds.size > phaseCount) 1 else 0
    val selected = linkedSetOf(firstNarratedRound, rounds.lastIndex)
    val representedSkills = selected
        .flatMap { index -> listOf(rounds[index].userAction.skillId, rounds[index].opponentAction.skillId) }
        .filter(String::isNotBlank)
        .toMutableSet()
    while (selected.size < phaseCount) {
        val next = rounds.indices
            .filterNot { index -> skipOpeningRound && firstNarratedRound > 0 && index < firstNarratedRound }
            .filterNot(selected::contains)
            .maxWithOrNull(
                compareBy<Int> { index ->
                    (if (rounds[index].userAction.finisher) 1 else 0) +
                        (if (rounds[index].opponentAction.finisher) 1 else 0)
                }.thenBy { index ->
                    (if (rounds[index].userAction.powerAttack) 1 else 0) +
                        (if (rounds[index].opponentAction.powerAttack) 1 else 0)
                }.thenBy { index ->
                    listOf(rounds[index].userAction.skillId, rounds[index].opponentAction.skillId)
                        .count { it.isNotBlank() && it !in representedSkills }
                }.thenBy { index ->
                    listOf(rounds[index].userAction.skillId, rounds[index].opponentAction.skillId)
                        .count(String::isNotBlank)
                }.thenBy { index ->
                    (if (rounds[index].userAction.critical) 1 else 0) +
                        (if (rounds[index].opponentAction.critical) 1 else 0)
                }.thenBy { index ->
                    rounds[index].userAction.damage + rounds[index].opponentAction.damage
                }.thenByDescending { it },
            ) ?: break
        selected += next
        listOf(rounds[next].userAction.skillId, rounds[next].opponentAction.skillId)
            .filter(String::isNotBlank)
            .forEach(representedSkills::add)
    }
    return selected.sorted()
}

internal fun battleStartsWithSilentExchange(
    battleId: String,
    roundCount: Int,
    phaseCount: Int,
): Boolean = roundCount > phaseCount && Math.floorMod(battleId.hashCode(), 2) == 0

/**
 * Readability contract for the local narrator. A clause boundary is a strong separator or an
 * explicit connective; every rendered sentence is covered by the four-clause regression test.
 */
internal fun battleNarrativeClauseCount(text: String, language: AppLanguage): Int {
    if (text.isBlank()) return 0
    val boundaryPattern = when (language) {
        AppLanguage.KOREAN -> Regex("[,;:—]|(?:하지만|하면서|하며|으나|는데)\\s")
        AppLanguage.ENGLISH -> Regex(
            "[,;:—]|\\b(?:and|but|while|although|because|whereas|so)\\b",
            RegexOption.IGNORE_CASE,
        )
        AppLanguage.JAPANESE -> Regex("[、；：—]|(?:けれど|ながら|ので)")
    }
    return boundaryPattern.findAll(text).count() + 1
}

private fun battleKoreanHasFinalConsonant(value: String): Boolean {
    val last = value.trim().lastOrNull() ?: return false
    val code = last.code
    return if (code in 0xAC00..0xD7A3) (code - 0xAC00) % 28 != 0 else last in "013678lLmMnNrR"
}

private fun battleKoreanTopic(value: String): String =
    value + if (battleKoreanHasFinalConsonant(value)) "은" else "는"

private fun battleKoreanSubject(value: String): String =
    value + if (battleKoreanHasFinalConsonant(value)) "이" else "가"

private fun battleKoreanObject(value: String): String =
    value + if (battleKoreanHasFinalConsonant(value)) "을" else "를"

private fun battleKoreanWith(value: String): String =
    value + if (battleKoreanHasFinalConsonant(value)) "과" else "와"

private fun battleKoreanPossessive(value: String): String = "${value}의"

private fun battleKoreanDirection(value: String): String {
    val last = value.trim().lastOrNull() ?: return value
    val finalIndex = if (last.code in 0xAC00..0xD7A3) (last.code - 0xAC00) % 28 else 0
    return value + if (finalIndex != 0 && finalIndex != 8) "으로" else "로"
}

private fun stableIndex(value: String, size: Int): Int {
    require(size > 0)
    var hash = -3_750_763_034_362_895_579L
    value.forEach { character ->
        hash = hash xor character.code.toLong()
        hash *= 1_099_511_628_211L
    }
    return Math.floorMod(hash, size.toLong()).toInt()
}
