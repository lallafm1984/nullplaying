package com.nullplaying.engine.arena

import com.nullplaying.engine.SkillCatalog
import com.nullplaying.localization.AppLanguage
import com.nullplaying.localization.GameLocalization
import java.util.Locale

/** Short factual logs for the isolated support slice; this never changes combat state. */
object ArenaSupportEventText {
    private val hiddenTraitLedgerReasons = setOf("accuracy_applied", "damage_bonus_applied")

    private data class Name(val ko: String, val en: String, val ja: String) {
        fun inLanguage(language: AppLanguage): String = when (language) {
            AppLanguage.KOREAN -> ko
            AppLanguage.ENGLISH -> en
            AppLanguage.JAPANESE -> ja
        }
    }

    fun text(
        event: ArenaSupportEvent,
        names: Map<String, String>,
        language: String = "ko",
    ): String? {
        if (event.reason in setOf("identity_mp_regeneration", "identity_mitigation")) return null
        if (event.type == ArenaSupportEventType.START || event.type == ArenaSupportEventType.CAST_PROGRESS) {
            // START can contain one snapshot per fighter; the UI owns the one shared intro.
            return null
        }
        if (event.type == ArenaSupportEventType.TRAIT_TRIGGERED && event.reason in hiddenTraitLedgerReasons) {
            // The preparation was already shown. Retain these ledger events, not a second log beat.
            return null
        }
        if (event.type == ArenaSupportEventType.EFFECT_EXPIRED && event.reason == "consumed") {
            // Spending a one-use effect is already explained by its resolved attack.
            return null
        }
        val lang = AppLanguage.fromLanguageTag(language) ?: AppLanguage.KOREAN
        fun line(ko: String, en: String, ja: String): String = Name(ko, en, ja).inLanguage(lang)
        fun participant(id: String?): String? = id?.takeIf { it.isNotBlank() }?.let {
            names[it]?.takeIf(String::isNotBlank) ?: it
        }

        if (event.type == ArenaSupportEventType.SAFETY_ABORT) {
            return line("검증 한도로 중단됐다. 승패는 미확정이다.",
                "Safety limit reached. No result was awarded.", "検証上限で中断した。勝敗は未確定。")
        }
        if (event.type == ArenaSupportEventType.END) {
            val winner = participant(event.actorId)
                ?: return line("무승부로 결투가 끝났다.", "The duel ended in a draw.", "決闘は引き分けで終了した。")
            return line("${subject(winner)} 승리했다.", "$winner won.", "${winner}が勝利した。")
        }

        val actor = participant(event.actorId) ?: return null
        val target = participant(event.targetId)
        val action = actionName(event.actionId, lang)
        val basic = event.actionId == "BASIC_ATTACK"
        if(event.reason=="identity_shield") return line(
            "${actor}의 보호막이 피해를 흡수했다.", "$actor’s shield absorbed the damage.", "${actor}のバリアがダメージを吸収した。")
        return when (event.type) {
            ArenaSupportEventType.CAST_START -> if (basic || event.castTurns <= 1) null else line(
                "${subject(actor)} ${obj(action)} 준비한다.",
                "$actor prepares $action.", "${actor}が「${action}」を準備する。",
            )
            ArenaSupportEventType.ATTACK_MISS -> if (basic) line(
                "${actor}의 ${subject(action)} 빗나갔다.",
                "$actor's basic attack missed.", "${actor}の「${action}」は外れた。",
            ) else line(
                "${actor}의 ${subject(action)} 빗나갔다.",
                "$actor missed with $action.", "${actor}の「${action}」は外れた。",
            )
            ArenaSupportEventType.ATTACK_EVADED -> {
                // This event's actor is the defender; actionId is the incoming attack.
                val attacker = target ?: return null
                line("${subject(actor)} ${attacker}의 ${obj(action)} 피했다.",
                    "$actor evaded $attacker's $action.", "${actor}が${attacker}の「${action}」をかわした。")
            }
            ArenaSupportEventType.ATTACK_HIT -> {
                val defender = target ?: return null
                when {
                    !event.amount.isFinite() -> null
                    event.amount <= 0.0 -> line(
                        "${actor}의 ${subject(action)} ${defender}에게 체력 피해를 주지 못했다.",
                        "$actor's $action dealt no HP damage to $defender.",
                        "${actor}の「${action}」は${defender}にHPダメージを与えられなかった。",
                    )
                    basic -> line(
                        "${actor}의 ${subject(action)} ${defender}에게 적중했다.",
                        "$actor's basic attack hit $defender.",
                        "${actor}の「${action}」が${defender}に命中した。",
                    )
                    else -> line(
                        "${actor}의 ${subject(action)} ${defender}에게 적중했다.",
                        "$actor hit $defender with $action.",
                        "${actor}の「${action}」が${defender}に命中した。",
                    )
                }
            }
            ArenaSupportEventType.SUPPORT_APPLIED -> {
                // Heal has a separate factual HEAL_APPLIED event; avoid duplicate success prose.
                if (event.actionId == "ARENA_SUP_CLERIC_01") null else line(
                    "${subject(actor)} ${obj(action)} 사용했다.",
                    "$actor used $action.", "${actor}が「${action}」を使った。",
                )
            }
            ArenaSupportEventType.DOT_DAMAGE -> if (positive(event.amount)) {
                val defender = target ?: return null
                val status = when (event.reason) {
                    "poison" -> line("독", "poison", "毒")
                    "bleed" -> line("출혈", "bleeding", "出血")
                    else -> line("화상", "burning", "火傷")
                }
                line("${subject(defender)} ${with(status)} 피해를 입었다.",
                    "$defender took damage from $status.", "${defender}が${status}でダメージを受けた。")
            } else null
            ArenaSupportEventType.MP_DRAINED -> if (positive(event.amount)) line(
                "${actor}의 MP가 ${amount(event.amount)} 감소했다.", "$actor lost ${amount(event.amount)} MP.",
                "${actor}のMPが${amount(event.amount)}減少した。") else null
            ArenaSupportEventType.STATUS_APPLIED -> line(
                "${target ?: actor}에게 ${action}의 상태 효과가 적용됐다.",
                "${target ?: actor} received the status from $action.", "${target ?: actor}に「${action}」の状態効果が付与された。")
            ArenaSupportEventType.STATUS_REMOVED -> line(
                "${actor}의 해로운 상태가 해제됐다.", "$actor's harmful status ended.", "${actor}の有害状態が解除された。")
            ArenaSupportEventType.STATUS_BLOCKED -> line(
                "${actor}의 ${subject(action)} 해로운 상태를 막았다.",
                "$actor's $action blocked a harmful status.", "${actor}の「${action}」が有害状態を防いだ。")
            ArenaSupportEventType.CONTROL_APPLIED -> line(
                "${target ?: actor}에게 ${action}의 제어 효과가 적용됐다.",
                "$action applied control to ${target ?: actor}.", "${target ?: actor}に「${action}」の制御効果が適用された。")
            ArenaSupportEventType.CONTROL_RESISTED -> if(event.reason == "control_immunity") line(
                "${actor}의 제어 내성이 ${obj(action)} 막았다.", "$actor's control immunity blocked $action.",
                "${actor}の制御耐性が「${action}」を防いだ。") else line(
                "${subject(actor)} ${action}에 저항했다.", "$actor resisted $action.", "${actor}が「${action}」に抵抗した。")
            ArenaSupportEventType.CAST_DELAYED -> line(
                "${actor}의 시전이 1턴 지연됐다.", "$actor's cast was delayed by 1 turn.", "${actor}の詠唱が1ターン遅延した。")
            ArenaSupportEventType.CAST_PAUSED -> line(
                "${subject(actor)} 잠들어 이번 턴 준비를 멈췄다.", "$actor slept through this turn's preparation.", "${actor}は眠り、このターンの準備を停止した。")
            ArenaSupportEventType.AWAKENED -> line(
                "${subject(actor)} HP 피해로 깨어났다.", "$actor awoke from HP damage.", "${actor}がHPダメージで目覚めた。")
            ArenaSupportEventType.SUPPORT_TRIGGERED -> line(
                "${actor}의 ${subject(action)} 실제 행동에 적용됐다.",
                "$actor's $action affected the resolved action.", "${actor}の「${action}」が実際の行動に適用された。")
            ArenaSupportEventType.HEAL_APPLIED -> if (positive(event.amount)) line(
                "${subject(actor)} ${with(action)} 체력을 ${amount(event.amount)} 되찾았다.",
                "$actor recovered ${amount(event.amount)} HP with $action.",
                "${actor}が「${action}」でHPを${amount(event.amount)}回復した。",
            ) else null
            ArenaSupportEventType.SHIELD_ABSORBED -> if (positive(event.amount)) line(
                "${actor}의 ${subject(action)} 피해를 흡수했다.",
                "$actor's $action absorbed damage.",
                "${actor}の「${action}」がダメージを吸収した。",
            ) else null
            ArenaSupportEventType.DAMAGE_REDUCED -> if (positive(event.amount)) line(
                "${actor}의 ${subject(action)} 피해를 줄였다.",
                "$actor's $action reduced damage.",
                "${actor}の「${action}」がダメージを軽減した。",
            ) else null
            ArenaSupportEventType.TRAIT_TRIGGERED -> {
                val trait = event.traitId?.let(ArenaProgressionCatalog::find)?.let { traitName(it, lang) }
                    ?: event.traitId?.takeIf { it.isNotBlank() } ?: return null
                // A prepared benefit is not yet saved MP or damage. Do not claim that outcome.
                line("${actor}의 ${subject(trait)} 발동했다.",
                    "$actor's $trait activated.", "${actor}の「${trait}」が発動した。")
            }
            ArenaSupportEventType.EFFECT_EXPIRED -> {
                val effect = event.traitId?.let(ArenaProgressionCatalog::find)?.let { traitName(it, lang) } ?: action
                line("${actor}의 ${subject(effect)} 끝났다.",
                    "$actor's $effect expired.", "${actor}の「${effect}」が終了した。")
            }
            ArenaSupportEventType.KO -> line("${subject(actor)} 쓰러졌다.",
                "$actor was defeated.", "${actor}が倒れた。")
            ArenaSupportEventType.CAST_CANCELLED_KO -> line(
                "${actor}의 ${subject(action)} 중단됐다.",
                "$actor's $action was interrupted.", "${actor}の「${action}」は中断された。",
            )
            ArenaSupportEventType.START, ArenaSupportEventType.CAST_PROGRESS,
            ArenaSupportEventType.END, ArenaSupportEventType.SAFETY_ABORT -> null
        }
    }

    private fun actionName(id: String?, language: AppLanguage): String {
        if (id == "BASIC_ATTACK") return Name("공격", "basic attack", "通常攻撃").inLanguage(language)
        id?.let(ArenaSupportCatalog::find)?.let { return it.name(languageTag(language)) }
        // All 120 current attacks have exact KO/EN/JA entries in the existing app catalog.
        // AlarmQuestApplication initializes GameLocalization before displaying the QA activity.
        val attack = id?.let(SkillCatalog::find)
        if (attack != null) return GameLocalization.translate(attack.name, language)
        return Name("기술", "skill", "技").inLanguage(language)
    }

    private fun languageTag(language: AppLanguage): String = when(language) {
        AppLanguage.KOREAN -> "ko"; AppLanguage.ENGLISH -> "en"; AppLanguage.JAPANESE -> "ja"
    }
    private fun traitName(definition: ArenaProgressionTraitDefinition, language: AppLanguage): String = when(language) {
        AppLanguage.KOREAN -> definition.nameKo; AppLanguage.ENGLISH -> definition.nameEn; AppLanguage.JAPANESE -> definition.nameJa
    }

    private fun positive(value: Double): Boolean = value.isFinite() && value > 0.0

    private fun amount(value: Double): String = when {
        value < 0.01 -> "<0.01"
        else -> String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
    }

    private fun finalConsonant(value: String): Int {
        val last = value.lastOrNull() ?: return 0
        return when {
            last in '\uAC00'..'\uD7A3' -> (last.code - 0xAC00) % 28
            last in "178" -> 8 // 일, 칠, 팔: ㄹ
            last in "036" -> 21 // 영, 삼, 육: non-ㄹ final consonant
            else -> 0
        }
    }

    private fun subject(value: String): String = value + if (finalConsonant(value) == 0) "가" else "이"
    private fun obj(value: String): String = value + if (finalConsonant(value) == 0) "를" else "을"
    private fun with(value: String): String = value + if (finalConsonant(value) in listOf(0, 8)) "로" else "으로"
}
