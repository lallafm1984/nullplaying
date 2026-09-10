package com.nullplaying.engine

import com.nullplaying.model.BattleHeroClass
import com.nullplaying.model.HeroPathBranch
import com.nullplaying.model.HeroPathBranchDefinition
import com.nullplaying.model.HeroPathCounterArchetype
import com.nullplaying.model.HeroPathEffectFamily
import com.nullplaying.model.HeroPathEffectStage
import com.nullplaying.model.HeroPathMatchupRelation
import com.nullplaying.model.HeroPathNodeDefinition
import com.nullplaying.model.HeroPathNodeSlot
import com.nullplaying.model.HeroPathNodeType
import com.nullplaying.model.HeroPathTier

/** Arena Talent Tree V2. 18 specializations x 8 nodes = 144 stable nodes. */
object HeroPathCatalog {
    val branches: List<HeroPathBranchDefinition> = listOf(
        branch(HeroPathBranch.WARRIOR_BERSERKER, BattleHeroClass.WARRIOR, HeroPathEffectFamily.RAGE_BURST, "광전사", "Berserker", "狂戦士", "피해를 받아 분노를 모으고 폭발시킨다."),
        branch(HeroPathBranch.WARRIOR_BULWARK, BattleHeroClass.WARRIOR, HeroPathEffectFamily.IMPACT_GUARD, "수호전사", "Bulwark", "防護戦士", "방어 충격을 반격으로 바꾼다."),
        branch(HeroPathBranch.WARRIOR_WARLORD, BattleHeroClass.WARRIOR, HeroPathEffectFamily.MORALE_COMMAND, "무기달인", "Weapon Master", "武器達人", "전투 흐름을 읽어 다음 공격을 지휘한다."),
        branch(HeroPathBranch.ROGUE_ASSASSIN, BattleHeroClass.ROGUE, HeroPathEffectFamily.OPENING_EXECUTION, "암살", "Assassination", "暗殺", "빈틈을 쌓아 치명적인 일격을 노린다."),
        branch(HeroPathBranch.ROGUE_SHADOW_DANCER, BattleHeroClass.ROGUE, HeroPathEffectFamily.EVASIVE_CHAIN, "잠행", "Subtlety", "隠密", "회피와 연속 행동으로 그림자 리듬을 만든다."),
        branch(HeroPathBranch.ROGUE_TRICKSTER, BattleHeroClass.ROGUE, HeroPathEffectFamily.DECEPTIVE_CONTROL, "전투도적", "Outlaw", "無法者", "속임수와 제어로 상대의 자원을 끊는다."),
        branch(HeroPathBranch.RANGER_MARKSMAN, BattleHeroClass.RANGER, HeroPathEffectFamily.FOCUSED_SHOT, "사격", "Marksmanship", "射撃", "안전한 거리를 유지해 조준을 완성한다."),
        branch(HeroPathBranch.RANGER_WINDWALKER, BattleHeroClass.RANGER, HeroPathEffectFamily.MOBILE_VOLLEY, "야수", "Wildrunner", "野駆け", "기동 공격을 이어 연사를 만든다."),
        branch(HeroPathBranch.RANGER_TRAPPER, BattleHeroClass.RANGER, HeroPathEffectFamily.CONTROLLED_HUNT, "생존", "Survival", "生存", "덫과 제어로 상대의 계획을 무너뜨린다."),
        branch(HeroPathBranch.MAGE_ELEMENTALIST, BattleHeroClass.MAGE, HeroPathEffectFamily.ELEMENTAL_BURST, "화염", "Fire", "火炎", "열기를 모아 폭발적인 주문으로 바꾼다."),
        branch(HeroPathBranch.MAGE_ARCANIST, BattleHeroClass.MAGE, HeroPathEffectFamily.ARCANE_CYCLE, "비전", "Arcane", "秘術", "주문 순환으로 비전 충전을 효율화한다."),
        branch(HeroPathBranch.MAGE_FORBIDDEN, BattleHeroClass.MAGE, HeroPathEffectFamily.FORBIDDEN_GAMBIT, "냉기", "Frost", "氷結", "냉기 제어와 위험한 승부수를 결합한다."),
        branch(HeroPathBranch.CLERIC_SANCTUARY, BattleHeroClass.CLERIC, HeroPathEffectFamily.GRACEFUL_RECOVERY, "신성", "Holy", "神聖", "회복과 보호로 패배 직전을 되돌린다."),
        branch(HeroPathBranch.CLERIC_JUDGMENT, BattleHeroClass.CLERIC, HeroPathEffectFamily.GRACE_JUDGMENT, "심판", "Judgment", "審判", "은총을 심판의 피해로 전환한다."),
        branch(HeroPathBranch.CLERIC_PROVIDENCE, BattleHeroClass.CLERIC, HeroPathEffectFamily.PROVIDENT_REVERSAL, "수양", "Discipline", "規律", "피해와 회복을 한 흐름으로 묶는다."),
        branch(HeroPathBranch.PALADIN_GUARDIAN, BattleHeroClass.PALADIN, HeroPathEffectFamily.OATHED_GUARD, "보호", "Protection", "防護", "방어로 서약을 쌓아 치명상을 버틴다."),
        branch(HeroPathBranch.PALADIN_AVENGER, BattleHeroClass.PALADIN, HeroPathEffectFamily.RETRIBUTIVE_COUNTER, "징벌", "Retribution", "報復", "막아낸 힘을 강한 반격으로 돌려준다."),
        branch(HeroPathBranch.PALADIN_DAWN, BattleHeroClass.PALADIN, HeroPathEffectFamily.DAWN_CYCLE, "신성기사", "Holy Knight", "聖騎士", "공격과 회복을 교차해 여명의 순환을 만든다."),
    )

    val byBranch: Map<HeroPathBranch, HeroPathBranchDefinition> = branches.associateBy { it.branch }
    val nodes: List<HeroPathNodeDefinition> = branches.flatMap(::buildBranchNodes)
    val byTraitId: Map<String, HeroPathNodeDefinition> = nodes.associateBy { it.traitId }
    val byNodeId: Map<String, HeroPathNodeDefinition> = byTraitId

    init {
        check(nodes.size == 144)
        check(nodes.map { it.traitId }.distinct().size == nodes.size)
        branches.forEach { spec ->
            val specNodes = nodesFor(spec.branch)
            check(specNodes.size == 8)
            check(specNodes.sumOf { it.maxRank } == 11)
            check(specNodes.sumOf { it.maxRank } - 1 == 10) // one of the two choice nodes
            check(specNodes.count { it.nodeType == HeroPathNodeType.CORE } == 1)
        }
    }

    fun branchesFor(heroClass: BattleHeroClass) = branches.filter { it.heroClass == heroClass }
    fun nodesFor(heroClass: BattleHeroClass) = nodes.filter { it.heroClassAffinity == heroClass }
    fun nodesFor(branch: HeroPathBranch) = nodes.filter { it.branch == branch }

    fun counterArchetypeFor(effectFamily: HeroPathEffectFamily): HeroPathCounterArchetype = when (effectFamily) {
        HeroPathEffectFamily.RAGE_BURST,
        HeroPathEffectFamily.OPENING_EXECUTION,
        HeroPathEffectFamily.ELEMENTAL_BURST -> HeroPathCounterArchetype.EXECUTION
        HeroPathEffectFamily.GRACEFUL_RECOVERY,
        HeroPathEffectFamily.PROVIDENT_REVERSAL,
        HeroPathEffectFamily.DAWN_CYCLE -> HeroPathCounterArchetype.SUSTAIN
        HeroPathEffectFamily.DECEPTIVE_CONTROL,
        HeroPathEffectFamily.CONTROLLED_HUNT,
        HeroPathEffectFamily.FORBIDDEN_GAMBIT -> HeroPathCounterArchetype.CONTROL
        HeroPathEffectFamily.FOCUSED_SHOT,
        HeroPathEffectFamily.MORALE_COMMAND,
        HeroPathEffectFamily.GRACE_JUDGMENT -> HeroPathCounterArchetype.PRECISION
        HeroPathEffectFamily.IMPACT_GUARD,
        HeroPathEffectFamily.OATHED_GUARD,
        HeroPathEffectFamily.RETRIBUTIVE_COUNTER -> HeroPathCounterArchetype.FORTRESS
        HeroPathEffectFamily.EVASIVE_CHAIN,
        HeroPathEffectFamily.MOBILE_VOLLEY,
        HeroPathEffectFamily.ARCANE_CYCLE -> HeroPathCounterArchetype.TEMPO
    }

    /** Execution > Sustain > Control > Precision > Fortress > Tempo > Execution. */
    fun matchupRelation(attacker: HeroPathBranch, defender: HeroPathBranch): HeroPathMatchupRelation {
        val attackerArchetype = byBranch.getValue(attacker).counterArchetype
        val defenderArchetype = byBranch.getValue(defender).counterArchetype
        if (attackerArchetype == defenderArchetype) return HeroPathMatchupRelation.NEUTRAL
        val size = HeroPathCounterArchetype.entries.size
        return when (defenderArchetype.ordinal) {
            (attackerArchetype.ordinal + 1) % size -> HeroPathMatchupRelation.FAVORABLE
            (attackerArchetype.ordinal - 1 + size) % size -> HeroPathMatchupRelation.UNFAVORABLE
            else -> HeroPathMatchupRelation.NEUTRAL
        }
    }

    private fun buildBranchNodes(spec: HeroPathBranchDefinition): List<HeroPathNodeDefinition> {
        val prefix = "ARENA_${spec.branch.name}"
        val choiceGroup = "$prefix:CHOICE"
        val signature = signatureNames(spec.branch)
        data class Blueprint(
            val suffix: String, val slot: HeroPathNodeSlot, val kind: HeroPathNodeType,
            val tier: HeroPathTier, val maxRank: Int, val stage: HeroPathEffectStage,
            val ko: String, val en: String, val ja: String,
        )
        val blueprints = listOf(
            Blueprint("FOUNDATION_A", HeroPathNodeSlot.FOUNDATION_A, HeroPathNodeType.FOUNDATION, HeroPathTier.TIER_1, 2, HeroPathEffectStage.ROUND_START, "${signature[0].first}의 기세", "${signature[0].second} Instinct", "${signature[0].third}の気勢"),
            Blueprint("FOUNDATION_B", HeroPathNodeSlot.FOUNDATION_B, HeroPathNodeType.FOUNDATION, HeroPathTier.TIER_1, 2, HeroPathEffectStage.AFTER_ACTION, "${signature[1].first}의 준비", "${signature[1].second} Discipline", "${signature[1].third}の備え"),
            Blueprint("CHOICE_A", HeroPathNodeSlot.CHOICE_A, HeroPathNodeType.CHOICE, HeroPathTier.TIER_2, 1, HeroPathEffectStage.PLAN_ACTION, "유리 상성 강화", "Press Advantage", "有利相性強化"),
            Blueprint("CHOICE_B", HeroPathNodeSlot.CHOICE_B, HeroPathNodeType.CHOICE, HeroPathTier.TIER_2, 1, HeroPathEffectStage.RESOLVE_ACTION, "불리 상성 완화", "Mitigate Disadvantage", "不利相性軽減"),
            Blueprint("SPECIAL_A", HeroPathNodeSlot.SPECIAL_A, HeroPathNodeType.SPECIAL, HeroPathTier.TIER_2, 1, HeroPathEffectStage.PLAN_ACTION, signature[0].first, signature[0].second, signature[0].third),
            Blueprint("SPECIAL_B", HeroPathNodeSlot.SPECIAL_B, HeroPathNodeType.SPECIAL, HeroPathTier.TIER_3, 1, HeroPathEffectStage.AFTER_ACTION, signature[1].first, signature[1].second, signature[1].third),
            Blueprint("ADVANCED", HeroPathNodeSlot.ADVANCED_TACTIC, HeroPathNodeType.TACTICAL, HeroPathTier.TIER_3, 2, HeroPathEffectStage.ROUND_END, "${signature[2].first} 숙련", "${signature[2].second} Mastery", "${signature[2].third}の熟練"),
            Blueprint("CORE", HeroPathNodeSlot.CORE, HeroPathNodeType.CORE, HeroPathTier.CORE, 1, HeroPathEffectStage.ROUND_END, signature[2].first, signature[2].second, signature[2].third),
        )
        return blueprints.map { bp ->
            val summary = nodeSummary(bp.slot, signature, spec.effectFamily)
            HeroPathNodeDefinition(
                traitId = "$prefix:${bp.suffix}", heroClassAffinity = spec.heroClass,
                branch = spec.branch, effectFamily = spec.effectFamily, nodeType = bp.kind,
                slot = bp.slot, tier = bp.tier, maxRank = bp.maxRank,
                minimumLevel = bp.tier.minimumLevel,
                requiredBranchInvestments = bp.tier.requiredSpecializationPoints,
                choiceGroupId = if (bp.kind == HeroPathNodeType.CHOICE) choiceGroup else "",
                effectStage = bp.stage,
                nameKo = "${spec.nameKo} · ${bp.ko}", nameEn = "${spec.nameEn} · ${bp.en}",
                nameJa = "${spec.nameJa} · ${bp.ja}", summaryKo = summary.first,
                summaryEn = summary.second, summaryJa = summary.third,
            )
        }
    }

    private fun nodeSummary(
        slot: HeroPathNodeSlot,
        signature: List<Triple<String, String, String>>,
        family: HeroPathEffectFamily,
    ): Triple<String, String, String> = when (slot) {
        HeroPathNodeSlot.FOUNDATION_A -> Triple("직업 자원을 얻는 추가 조건을 연다.", "Unlocks additional class-resource gain triggers.", "クラス資源を得る追加条件を開放する。")
        HeroPathNodeSlot.FOUNDATION_B -> Triple("자원을 잃거나 특수기를 쓴 뒤, 일부 자원을 지킨다.", "Preserves some resource after disruption or a special technique.", "資源喪失や特殊技の後に、一部の資源を保つ。")
        HeroPathNodeSlot.CHOICE_A -> Triple("유리 판정에서 상성 보너스를 더 키웁니다.", "In a favorable matchup, its bonus is increased.", "有利判定なら、相性ボーナスをさらに強化します。")
        HeroPathNodeSlot.CHOICE_B -> Triple("불리 판정에서 상성 페널티를 줄입니다.", "In an unfavorable matchup, its penalty is reduced.", "不利判定なら、相性ペナルティを軽減します。")
        HeroPathNodeSlot.SPECIAL_A -> Triple("충전 3에서 보유 스킬로 ${signature[0].first} 기술을 발동한다.", "At 3 charge, an owned skill triggers ${signature[0].second}.", "充填3で所持スキルから${signature[0].third}を発動する。")
        HeroPathNodeSlot.SPECIAL_B -> Triple("위기나 장기전에서 ${signature[1].first} 효과가 전세를 바꾼다.", "In danger or a long fight, ${signature[1].second} turns the tide.", "危機や長期戦で${signature[1].third}が戦況を覆す。")
        HeroPathNodeSlot.ADVANCED_TACTIC -> Triple("특수기의 피해·회복·반격 효율을 높인다.", "Improves special damage, healing, and counter efficiency.", "特殊技のダメージ・回復・反撃効率を高める。")
        HeroPathNodeSlot.CORE -> if (family in setOf(HeroPathEffectFamily.IMPACT_GUARD, HeroPathEffectFamily.GRACEFUL_RECOVERY, HeroPathEffectFamily.PROVIDENT_REVERSAL, HeroPathEffectFamily.OATHED_GUARD)) {
            Triple("충전 3을 소모해 치명상을 한 번 버틴다.", "Spend 3 charge to survive one lethal blow.", "充填3を消費し致命傷を一度耐える。")
        } else {
            Triple("전투당 한 번, ${signature[2].first} 효과가 결정적 규칙을 발동한다.", "Once per battle, ${signature[2].second} invokes a decisive rule.", "戦闘ごとに一度${signature[2].third}が決定的な効果を発動する。")
        }
    }

    private fun signatureNames(branch: HeroPathBranch) = when (branch) {
        HeroPathBranch.WARRIOR_BERSERKER -> sig("피의 쇄도|Blood Rush|血の奔流", "붉은 보복|Crimson Reprisal|紅の報復", "무모한 폭풍|Reckless Tempest|無謀の嵐")
        HeroPathBranch.WARRIOR_BULWARK -> sig("방패 강타|Shield Ram|盾撃", "불굴의 전열|Unbroken Line|不屈の戦列", "최후의 성채|Last Bastion|最後の砦")
        HeroPathBranch.WARRIOR_WARLORD -> sig("무기 기만|Weapon Feint|武器の欺き", "지휘의 박자|Commanding Tempo|指揮の拍子", "무기고의 군주|Lord of Arms|武庫の主")
        HeroPathBranch.ROGUE_ASSASSIN -> sig("독니 개방|Venom Opening|毒牙開放", "심장 추적|Heartseeker|心臓追跡", "완전한 처형|Perfect Execution|完全処刑")
        HeroPathBranch.ROGUE_SHADOW_DANCER -> sig("그림자 도약|Shadowstep|影渡り", "잔상 연무|Afterimage Dance|残影の舞", "사라진 칼날|Vanishing Blade|消失の刃")
        HeroPathBranch.ROGUE_TRICKSTER -> sig("속임수 카드|Loaded Trick|仕込み札", "흐름 절단|Tempo Cut|流れ断ち", "운명의 속임수|Fate's Cheat|運命の詐術")
        HeroPathBranch.RANGER_MARKSMAN -> sig("고정 조준|Deadeye Focus|必中照準", "관통 표식|Piercing Mark|貫通の印", "지평선의 한 발|Horizon Shot|地平の一矢")
        HeroPathBranch.RANGER_WINDWALKER -> sig("질풍 연사|Gale Volley|疾風連射", "바람 되감기|Wind Reprise|風の反復", "천공의 사냥|Sky Hunt|天空の狩り")
        HeroPathBranch.RANGER_TRAPPER -> sig("사슬 덫|Chain Snare|鎖罠", "사냥터 봉쇄|Hunting Ground|狩場封鎖", "도망 없는 숲|Inescapable Wilds|逃れなき森")
        HeroPathBranch.MAGE_ELEMENTALIST -> sig("화염 파동|Flame Surge|炎波", "재점화|Rekindle|再点火", "태양 낙하|Falling Sun|太陽落とし")
        HeroPathBranch.MAGE_ARCANIST -> sig("비전 연쇄|Arcane Sequence|秘術連鎖", "마력 환류|Mana Reflow|魔力還流", "완전한 순환|Perfect Cycle|完全循環")
        HeroPathBranch.MAGE_FORBIDDEN -> sig("빙결 낙인|Frost Brand|氷結刻印", "금단의 반전|Forbidden Reversal|禁断反転", "영원의 겨울|Endless Winter|永劫の冬")
        HeroPathBranch.CLERIC_SANCTUARY -> sig("은총의 손길|Touch of Grace|恩寵の手", "성역 회귀|Sanctuary Return|聖域回帰", "두 번째 새벽|Second Dawn|第二の夜明け")
        HeroPathBranch.CLERIC_JUDGMENT -> sig("빛의 선고|Radiant Verdict|光の宣告", "죄업 반사|Sin Reversal|罪業反射", "최후 심판|Final Judgment|最後の審判")
        HeroPathBranch.CLERIC_PROVIDENCE -> sig("고통 분배|Pain Dividend|苦痛分配", "섭리 전환|Provident Turn|摂理転換", "예정된 기적|Fated Miracle|定めの奇跡")
        HeroPathBranch.PALADIN_GUARDIAN -> sig("서약 방벽|Oathwall|誓約障壁", "수호의 반향|Guardian Echo|守護の反響", "쓰러지지 않는 맹세|Undying Oath|不倒の誓い")
        HeroPathBranch.PALADIN_AVENGER -> sig("응징의 망치|Avenging Hammer|報復の槌", "정의의 반격|Justice Repaid|正義の反撃", "천벌 집행|Wrath Incarnate|天罰執行")
        HeroPathBranch.PALADIN_DAWN -> sig("여명 베기|Dawn Slash|黎明斬り", "빛의 순환|Cycle of Light|光の循環", "불멸의 일출|Immortal Sunrise|不滅の日の出")
    }

    private fun sig(vararg encoded: String): List<Triple<String, String, String>> = encoded.map { value ->
        val parts = value.split('|')
        Triple(parts[0], parts[1], parts[2])
    }

    private fun branch(
        branch: HeroPathBranch, heroClass: BattleHeroClass, family: HeroPathEffectFamily,
        ko: String, en: String, ja: String, descriptionKo: String,
    ) = HeroPathBranchDefinition(
        branch, heroClass, family, counterArchetypeFor(family), ko, en, ja, descriptionKo,
        "$en changes automatic combat through its class resource.",
        "${ja}はクラス資源で自動戦闘のルールを変える。",
    )
}
