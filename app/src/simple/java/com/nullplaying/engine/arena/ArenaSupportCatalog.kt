package com.nullplaying.engine.arena

import com.nullplaying.model.HeroClass
import java.util.Locale
import kotlin.math.roundToInt

/** Versioned executable arena support contract. Effects and UI read the same numbers. */
enum class ArenaSupportKind { IRON, SHOUT, BASIC_SHATTER, COUNTER, BASIC_PIERCE, CLEANSE_ACCURACY, TAUNT, BANDAGE, DAMAGE_CAP, PURSUIT, STEALTH, SMOKE, MP_DRAIN, EVASION, OPPORTUNITY, POISON_COAT, CLEANSE, POISON_ACCELERATE, HEAL_BLOCK_PREP, WRIST, AIM, REVEAL, RAPID, PIERCE, OBSERVE, BASIC_GUARD, SKILL_SHATTER, TRAP, HEAL_TRACK, SHIELD, MIRROR, STABILIZE, PHASE, DISPEL, SLEEP, CONDENSE, STATUS_GUARD, BURN_PREP, EMBER, HEAL, BLESS, REGEN, GRACE, RESTRAINT, TRUTH, SANCTUARY, LIFESTEAL, SEAL, SKILL_GUARD, RETRIBUTION, JUDGMENT, RESOLVE, LOW_SHIELD, LAY_HANDS, FOCUS, EXECUTE }

data class ArenaSupportDefinition(
    val id: String, val heroClass: HeroClass, val unlockLevel: Long,
    val kind: ArenaSupportKind, val mp: Int, val castTurns: Int, val durationTurns: Int,
    val cooldownTurns: Int, val charges: Int, val magnitude: Double, val secondary: Double,
    val threshold: Double, val oncePerBattle: Boolean,
    val nameKo: String, val nameEn: String, val nameJa: String,
    private val effectKo: String, private val effectEn: String, private val effectJa: String,
    val conditionKey: String,
    val corePreviewId: String? = null,
) {
    internal val cast get() = castTurns
    internal val duration get() = durationTurns
    internal val cooldown get() = cooldownTurns
    internal val unlock get() = unlockLevel

    fun name(language: String): String = pick(language, nameKo, nameEn, nameJa)
    fun effectText(language: String): String {
        corePreviewId?.let(ArenaProgressionCatalog::find)?.let { return it.effectText(language, 1, 0) }
        return pick(language, effectKo, effectEn, effectJa)
            .replace("{v}", number(magnitude)).replace("{s}", number(secondary)).replace("{t}", number(threshold))
    }
    fun summaryText(language: String): String {
        corePreviewId?.let(ArenaProgressionCatalog::find)?.let { return it.summaryText(language, 1, 0) }
        val v = number(magnitude); val s = number(secondary); val t = number(threshold)
        return when(kind) {
            ArenaSupportKind.IRON -> pick(language,
                "받는 피해 -$v%",
                "Damage taken -$v%",
                "受けるダメージ-$v%")
            ArenaSupportKind.RESTRAINT -> pick(language,
                "받는 피해 -$v%\n주는 공격 피해 -$s%",
                "Damage taken -$v%\nAttack damage dealt -$s%",
                "受けるダメージ-$v%\n与える攻撃ダメージ-$s%")
            ArenaSupportKind.SHIELD -> pick(language,
                "최대 HP $v% 보호막\n지속 피해도 흡수",
                "Shield equal to $v% max HP\nAlso absorbs damage over time",
                "最大HP$v%のシールド\n継続ダメージも吸収")
            ArenaSupportKind.LOW_SHIELD -> pick(language,
                "HP $t% 이하에서 사용\n매력에 따라 보호막 증가\n최대 HP $v%까지",
                "Use at $t% HP or less\nShield scales with Charisma\nUp to $v% max HP",
                "HP$t%以下で使用\n魅力に応じてシールド増加\n最大HP$v%まで")
            ArenaSupportKind.HEAL -> pick(language, "최대 HP $v% 회복", "Heal up to $v% max HP", "最大HPの$v%まで回復")
            ArenaSupportKind.BANDAGE -> pick(language,
                "HP $t% 이하에서 사용\n체력에 따라 회복량 증가\n최대 HP $v%까지",
                "Use at $t% HP or less\nHealing scales with Constitution\nUp to $v% max HP",
                "HP$t%以下で使用\n体力に応じて回復量増加\n最大HP$v%まで")
            ArenaSupportKind.LAY_HANDS -> pick(language,
                "HP $t% 이하에서 사용\n매력에 따라 회복량 증가\n최대 HP $v%까지",
                "Use at $t% HP or less\nHealing scales with Charisma\nUp to $v% max HP",
                "HP$t%以下で使用\n魅力に応じて回復量増加\n最大HP$v%まで")
            ArenaSupportKind.REGEN -> pick(language,
                "완료 다음 턴부터 매 턴 최대 HP $v% 회복",
                "Starting next turn, heal $v% max HP per turn",
                "完了の次ターンから毎ターン最大HP$v%回復")
            ArenaSupportKind.DAMAGE_CAP -> pick(language,
                "${nextKo("받는 공격")}: 보호막을 넘은 HP 피해를 최대 HP $v%까지 제한",
                "${nextEn("incoming attack", "incoming attacks")}: limit post-shield HP damage to $v% max HP",
                "${nextJa("受ける攻撃")}：シールド後のHPダメージを最大HP$v%までに制限")
            ArenaSupportKind.GRACE -> pick(language,
                "보호막 후 HP 피해가 최대 HP $t% 이상일 때\n받는 피해 -$v%\n감소량은 최대 HP $s%까지",
                "When post-shield HP damage reaches $t% max HP\nDamage taken -$v%\nReduction up to $s% max HP",
                "シールド後のHPダメージが最大HP$t%以上の時\n受けるダメージ-$v%\n軽減量は最大HP$s%まで")
            ArenaSupportKind.CLEANSE -> pick(language,
                "해로운 상태 ${v}개 제거\n독은 모든 중첩 제거",
                "Remove ${englishCount(v, "harmful status", "harmful statuses")}\nRemove all Poison stacks",
                "有害状態${v}件を解除\n毒は全重複を解除")
            ArenaSupportKind.CLEANSE_ACCURACY -> pick(language,
                "명중률 감소 ${v}개 제거",
                "Remove ${englishCount(v, "accuracy penalty", "accuracy penalties")}",
                "命中率低下${v}件を解除")
            ArenaSupportKind.REVEAL -> pick(language,
                "은신·분신 효과 ${v}개 제거\n은신 우선",
                "Remove ${englishCount(v, "Stealth or image effect", "Stealth or image effects")}\nStealth first",
                "隠密・分身効果を${v}件解除\n隠密を優先")
            ArenaSupportKind.DISPEL -> pick(language,
                "상대 공격·명중·피해 감소 강화 ${v}개 제거",
                "Remove ${englishCount(v, "enemy attack, accuracy, or damage-reduction effect", "enemy attack, accuracy, or damage-reduction effects")}",
                "相手の攻撃・命中・ダメージ軽減強化を${v}件解除")
            ArenaSupportKind.JUDGMENT -> pick(language,
                "첫 공격 적중 시 상대의 이로운 보조 효과 ${v}개 제거",
                "On the first attack hit, remove ${englishCount(v, "beneficial target support effect", "beneficial target support effects")}",
                "最初の攻撃命中時、相手の有益な補助効果を${v}件解除")
            ArenaSupportKind.SEAL -> pick(language,
                "상대 HP에 첫 피해 시 공격·명중·피해 감소 강화 ${v}개 제거",
                "On the first HP damage, remove ${englishCount(v, "target attack, accuracy, or damage-reduction buff", "target attack, accuracy, or damage-reduction buffs")}",
                "相手HPへの最初のダメージ時、攻撃・命中・ダメージ軽減強化を${v}件解除")
            ArenaSupportKind.SANCTUARY -> pick(language,
                "새 해로운 상태 지속 -${v}턴\n최소 1턴 유지",
                "New harmful statuses last ${englishCount(v, "turn", "turns")} less\nMinimum duration 1 turn",
                "新たな有害状態の持続-${v}ターン\n最低1ターン")
            ArenaSupportKind.TRUTH -> pick(language,
                "상대 은신 피해 증가 효과 $v% 완화\n또는 다음 ${charges}회 분신 무효 확률 $v% 완화",
                "Reduce target Stealth damage bonus by $v%\nOr weaken image negation by $v% for ${englishCount(charges.toString(), "check", "checks")}",
                "相手の隠密ダメージ増加を$v%弱化\nまたは次の${charges}回、分身無効確率を$v%弱化")
            ArenaSupportKind.STEALTH -> pick(language,
                "${nextKo("적중한 공격")} 피해 +$v%",
                "${nextEn("attack that hits", "attacks that hit")} ${useVerb("deals", "deal")} +$v% damage",
                "${nextJa("命中した攻撃")}のダメージ+$v%")
            ArenaSupportKind.MP_DRAIN -> pick(language,
                "${nextKo("적중한 공격")}: 상대 MP 최대 $v 감소",
                "${nextEn("attack that hits", "attacks that hit")}: reduce target MP by up to $v",
                "${nextJa("命中した攻撃")}：相手MPを最大${v}減少")
            ArenaSupportKind.EVASION -> pick(language,
                "${nextKo("받는 공격")}을 $v% 확률로 회피",
                "$v% chance to evade the ${nextEn("incoming attack", "incoming attacks").replaceFirst("Next", "next")}",
                "${nextJa("受ける攻撃")}を$v%で回避")
            ArenaSupportKind.MIRROR -> pick(language,
                "분신 ${charges}개 생성\n공격 무효 확률 ${mirrorChanceSequence(charges)}\n판정마다 분신 1개 소모",
                "Create ${englishCount(charges.toString(), "image", "images")}\nAttack negation chance ${mirrorChanceSequence(charges)}\nSpend 1 image per check",
                "分身${charges}体を生成\n攻撃無効確率${mirrorChanceSequence(charges)}\n判定ごとに分身1体消費")
            ArenaSupportKind.POISON_COAT -> pick(language,
                "${nextKo("상대 HP 피해")}에 독 부여\n최대 ${t}중첩\n중첩마다 ${s}턴 동안 매 턴 공격력의 $v% 피해",
                "${nextEn("HP-damaging attack", "HP-damaging attacks")} ${useVerb("inflicts", "inflict")} Poison\nUp to $t stacks\nEach stack deals $v% attack damage per turn for $s turns",
                "${nextJa("相手HPへのダメージ")}で毒付与\n最大${t}重複\n重複ごとに${s}ターン、毎ターン攻撃力の$v%ダメージ")
            ArenaSupportKind.POISON_ACCELERATE -> pick(language,
                "독 1~2중첩일 때 ${v}중첩 추가\n독 피해 +$s%\n남은 지속 시간 유지",
                "At 1-2 Poison stacks, add ${englishCount(v, "stack", "stacks")}\nPoison damage +$s%\nKeep remaining duration",
                "毒1～2重複時に${v}重複追加\n毒ダメージ+$s%\n残り時間を維持")
            ArenaSupportKind.HEAL_TRACK -> pick(language,
                "${nextKo("공격")}: 최근 상대 회복량의 $v% 추가 피해\n추가 피해는 기본 피해의 $s%까지",
                "${nextEn("attack", "attacks")}: add $v% of the target's recent healing as damage\nBonus damage up to $s% of base damage",
                "${nextJa("攻撃")}：相手の直近回復量の$v%を追加ダメージ\n追加ダメージは基礎ダメージの$s%まで")
            ArenaSupportKind.BURN_PREP -> pick(language,
                "${nextKo("공격 스킬로 상대 HP 피해")} 시 화상\n${s}턴 동안 매 턴 공격력의 $v% 피해",
                "${nextEn("skill that damages target HP", "skills that damage target HP")}: inflict Burn\n$v% attack damage each turn for $s turns",
                "${nextJa("攻撃スキルで相手HPにダメージ")}を与えると火傷\n${s}ターン、毎ターン攻撃力の$v%ダメージ")
            ArenaSupportKind.EMBER -> pick(language,
                "${nextKo("HP 피해")}를 받고 생존 시 공격자에게 화상\n${s}턴 동안 매 턴 공격력의 $v% 피해",
                "After surviving the ${nextEn("HP-damaging attack", "HP-damaging attacks").replaceFirst("Next", "next")}, Burn the attacker\n$v% attack damage each turn for $s turns",
                "${nextJa("HPダメージ")}を受けて生存すると攻撃者に火傷\n${s}ターン、毎ターン攻撃力の$v%ダメージ")
            ArenaSupportKind.CONDENSE, ArenaSupportKind.RETRIBUTION -> pick(language,
                "${nextKo("공격 스킬")} 피해 +$v%",
                "${nextEn("attack skill", "attack skills")} ${useVerb("deals", "deal")} +$v% damage",
                "${nextJa("攻撃スキル")}のダメージ+$v%")
            ArenaSupportKind.EXECUTE -> pick(language,
                "상대 HP $t% 이하: ${nextKo("공격 스킬")} 피해 +$v%",
                "At $t% enemy HP or less: ${nextEn("attack skill", "attack skills").replaceFirst("Next", "the next")} ${useVerb("deals", "deal")} +$v% damage",
                "相手HP$t%以下：${nextJa("攻撃スキル")}のダメージ+$v%")
            ArenaSupportKind.COUNTER, ArenaSupportKind.OPPORTUNITY -> pick(language,
                "${nextKo("공격")} 피해 +$v%",
                "${nextEn("basic attack", "basic attacks")} ${useVerb("deals", "deal")} +$v% damage",
                "${nextJa("通常攻撃")}のダメージ+$v%")
            ArenaSupportKind.PURSUIT -> pick(language,
                "${nextKo("공격")} 피해 +$v%\n명중률 +${accuracy(secondary)}%",
                "${nextEn("basic attack", "basic attacks")} ${useVerb("deals", "deal")} +$v% damage\nAccuracy +${accuracy(secondary)}%",
                "${nextJa("通常攻撃")}のダメージ+$v%\n命中率+${accuracy(secondary)}%")
            ArenaSupportKind.RAPID -> pick(language, "공격 피해 +$v%", "Basic attack damage +$v%", "通常攻撃ダメージ+$v%")
            ArenaSupportKind.BASIC_GUARD -> pick(language, "받는 공격 피해 -$v%", "Basic attack damage taken -$v%", "通常攻撃の被害-$v%")
            ArenaSupportKind.SKILL_GUARD -> pick(language,
                "${nextKo("공격 스킬")}로 받는 피해 -$v%",
                "Damage from the ${nextEn("attack skill", "attack skills").replaceFirst("Next", "next")} -$v%",
                "${nextJa("攻撃スキル")}から受けるダメージ-$v%")
            ArenaSupportKind.SHOUT -> pick(language,
                "모든 공격 피해 +$v%",
                "All attack damage +$v%",
                "すべての攻撃ダメージ+$v%")
            ArenaSupportKind.AIM, ArenaSupportKind.BLESS -> pick(language,
                "모든 공격 피해 +$v%\n명중률 +${accuracy(secondary)}%",
                "All attack damage +$v%\nAccuracy +${accuracy(secondary)}%",
                "すべての攻撃ダメージ+$v%\n命中率+${accuracy(secondary)}%")
            ArenaSupportKind.BASIC_SHATTER -> pick(language,
                "${nextKo("공격")}: 상대 보호막 피해의 $v%만큼 보호막 추가 피해",
                "${nextEn("basic attack", "basic attacks")}: deal $v% of shield damage again as bonus shield damage",
                "${nextJa("通常攻撃")}：相手のシールドダメージの$v%分を追加")
            ArenaSupportKind.SKILL_SHATTER -> pick(language,
                "${nextKo("공격 스킬")}: 상대 보호막 피해의 $v%만큼 보호막 추가 피해",
                "${nextEn("attack skill", "attack skills")}: deal $v% of shield damage again as bonus shield damage",
                "${nextJa("攻撃スキル")}：相手のシールドダメージの$v%分を追加")
            ArenaSupportKind.BASIC_PIERCE -> pick(language,
                "${nextKo("공격")}: 상대의 피해 감소 효과 $v% 무시",
                "${nextEn("basic attack", "basic attacks")}: ignore $v% of target damage reduction",
                "${nextJa("通常攻撃")}：相手のダメージ軽減効果を$v%無視")
            ArenaSupportKind.PIERCE, ArenaSupportKind.OBSERVE -> pick(language, "상대의 피해 감소 효과 $v% 무시", "Ignore $v% of target damage reduction", "相手のダメージ軽減効果を$v%無視")
            ArenaSupportKind.SMOKE -> pick(language, "상대 명중률 -${accuracy(magnitude)}%", "Target accuracy -${accuracy(magnitude)}%", "相手の命中率-${accuracy(magnitude)}%")
            ArenaSupportKind.STATUS_GUARD -> pick(language,
                "새 해로운 상태 ${v}회 차단",
                "Block ${englishCount(v, "new harmful status", "new harmful statuses")}",
                "新たな有害状態を${v}回防ぐ")
            ArenaSupportKind.HEAL_BLOCK_PREP -> pick(language,
                "${nextKo("적중한 공격")}: ${s}턴 동안 상대 회복량 -$v%",
                "${nextEn("attack that hits", "attacks that hit")}: target healing -$v% for $s turns",
                "${nextJa("命中した攻撃")}：${s}ターン、相手の回復量-$v%")
            ArenaSupportKind.FOCUS -> pick(language,
                "${nextKo("공격 스킬")} 명중률 +${accuracy(magnitude)}%",
                "${nextEn("attack skill", "attack skills")} ${useVerb("gains", "gain")} +${accuracy(magnitude)}% accuracy",
                "${nextJa("攻撃スキル")}の命中率+${accuracy(magnitude)}%")
            ArenaSupportKind.SLEEP -> pick(language,
                "제어 명중률 +${accuracy(secondary)}%\n성공 시 ${v}턴 수면\nHP 피해를 받으면 해제",
                "Control accuracy +${accuracy(secondary)}%\nOn success, Sleep for ${englishCount(v, "turn", "turns")}\nEnds on HP damage",
                "制御命中率+${accuracy(secondary)}%\n成功時${v}ターン睡眠\nHPダメージを受けると解除")
            ArenaSupportKind.TRAP -> pick(language,
                "제어 명중률 +${accuracy(secondary)}%\n성공 시 상대의 다음 공격 준비 +${v}턴",
                "Control accuracy +${accuracy(secondary)}%\nOn success, target's next attack preparation +${englishCount(v, "turn", "turns")}",
                "制御命中率+${accuracy(secondary)}%\n成功時、相手の次の攻撃準備+${v}ターン")
            ArenaSupportKind.TAUNT -> pick(language,
                "제어 명중률 +${accuracy(secondary)}%\n성공 시 상대의 다음 ${v}회 행동을 공격으로 유도",
                "Control accuracy +${accuracy(secondary)}%\nOn success, force the target's next ${englishCount(v, "action", "actions")} to be a basic attack",
                "制御命中率+${accuracy(secondary)}%\n成功時、相手の次の${v}回の行動を通常攻撃に誘導")
            ArenaSupportKind.WRIST -> pick(language,
                "상대의 공격 피해 -$v%",
                "Target basic attack damage -$v%",
                "相手の通常攻撃ダメージ-$v%")
            ArenaSupportKind.STABILIZE -> pick(language,
                "공격 스킬 기본 명중률 ${accuracy(magnitude)}%\n회피·분신 효과는 별도 적용",
                "Attack skill base accuracy ${accuracy(magnitude)}%\nEvasion and images still apply",
                "攻撃スキルの基本命中率${accuracy(magnitude)}%\n回避・分身効果は別に適用")
            ArenaSupportKind.PHASE -> pick(language,
                "피해 $v%가 상대 보호막 무시\n총 피해는 동일",
                "$v% of damage ignores the target's shield\nTotal damage is unchanged",
                "ダメージの$v%が相手のシールドを無視\n総ダメージは同じ")
            ArenaSupportKind.LIFESTEAL -> pick(language,
                "상대 HP에 준 피해의 $v% 회복\n공격마다 최대 HP $s%까지",
                "Heal $v% of damage dealt to target HP\nUp to $s% max HP per attack",
                "相手HPに与えたダメージの$v%回復\n攻撃ごとに最大HP$s%まで")
            ArenaSupportKind.RESOLVE -> pick(language,
                "HP $t% 이하에서 모든 공격 피해 +$v%",
                "At $t% HP or less, all attack damage +$v%",
                "HP$t%以下ですべての攻撃ダメージ+$v%")
        }
    }

    fun conditionText(language: String): String = when (conditionKey) {
        "RECENT_DAMAGE" -> pick(language, "이번 턴 또는 직전 턴에 직접 HP 피해를 받고 생존했을 때", "After surviving direct HP damage this turn or the previous turn", "このターンまたは直前ターンのHP直撃を耐えた時")
        "OWN_MISS" -> pick(language, "이번 턴 또는 직전 턴에 자신의 일반 명중 판정이 실패했을 때", "After your accuracy check missed this turn or the previous turn", "このターンまたは直前ターンに自身の通常命中判定が失敗した時")
        "ENEMY_MISS" -> pick(language, "이번 턴 또는 직전 턴에 상대의 일반 명중 판정이 실패했을 때", "After the enemy accuracy check missed this turn or the previous turn", "このターンまたは直前ターンに相手の通常命中判定が失敗した時")
        "RECENT_HEAL" -> pick(language, "이번 턴 또는 직전 턴의 아직 추적하지 않은 실제 상대 회복이 있을 때", "An unused actual enemy heal occurred this turn or the previous turn", "このターンまたは直前ターンに未追跡の相手の実回復がある時")
        "LOW_HP" -> pick(language, "자신의 HP가 ${number(threshold)}% 이하이며 효과가 유효할 때", "Your HP is at most ${number(threshold)}% and the effect has value", "自身のHPが${number(threshold)}%以下で効果が有効な時")
        "ENEMY_LOW_HP" -> pick(language, "상대 HP가 ${number(threshold)}% 이하이며 후속 공격 스킬을 쓸 수 있을 때", "Enemy HP is at most ${number(threshold)}% and a follow-up skill is affordable", "相手HPが${number(threshold)}%以下で後続スキルを使える時")
        "SHIELD" -> pick(language, "상대 보호막이 남아 있고 후속 공격이 가능할 때", "An enemy shield remains and a follow-up attack is available", "相手にシールドが残り、後続攻撃が可能な時")
        "DEFENSE" -> pick(language, "상대 보조 효과에 의한 피해 감소가 있을 때", "Enemy support effects are reducing damage", "相手の補助効果による軽減がある時")
        "ACCURACY_DEBUFF" -> pick(language, "자신의 명중 감소가 남아 있을 때", "An accuracy penalty remains on you", "自身の命中低下が残っている時")
        "DEBUFF" -> pick(language, "제거할 수 있는 해로운 상태가 남아 있을 때", "A removable harmful status remains", "解除可能な有害状態が残っている時")
        "POISON_ROOM" -> pick(language, "상대 독이 3층 미만이며 HP 타격이 가능할 때", "Enemy poison is below 3 stacks and an HP hit is possible", "相手の毒が3層未満でHP直撃が可能な時")
        "POISON_EXISTING" -> pick(language, "상대 독이 1~2층이며 남은 독 피해의 가치가 충분할 때", "Enemy poison has 1–2 stacks with enough remaining damage value", "相手の毒が1～2層で、残りの毒ダメージに十分な価値がある時")
        "ENEMY_ILLUSION" -> pick(language, "상대 은신 또는 미러가 실제로 유효할 때", "Enemy Stealth or Mirror is currently active", "相手の隠密またはミラーが実際に有効な時")
        "ENEMY_BUFF", "ENEMY_ANY_BUFF", "ENEMY_MITIGATION" -> pick(language, "제거하거나 완화할 수 있는 상대 강화가 남아 있을 때", "A relevant enemy beneficial effect remains", "対象となる相手の強化が残っている時")
        "MISSING_HP" -> pick(language, "실제 회복 여지가 있고 완료까지 생존할 가치가 있을 때", "Missing HP and surviving the cast make healing worthwhile", "回復余地があり、詠唱を耐えて回復する価値がある時")
        "CONTROL" -> pick(language, "상대 제어 내성이 없고 강제 행동·지연의 효용이 있을 때", "Enemy control immunity is absent and control has current value", "相手に制御耐性がなく、行動誘導や遅延が有効な時")
        "STATUS_THREAT" -> pick(language, "공개된 상대 준비·시전에서 새 해로운 상태 위험을 확인했을 때", "Visible enemy preparation or casting threatens a harmful status", "公開された相手の準備・詠唱で有害状態の危険を確認した時")
        "ENEMY_HEAL" -> pick(language, "상대에게 실제 사용 가능한 회복 수단이 있고 HP가 줄었을 때", "Enemy has an available healing method and missing HP", "相手に使用可能な回復手段がありHPが減っている時")
        "ENEMY_MP" -> pick(language, "상대 MP가 남아 있어 감소 효과가 유효할 때", "Enemy MP remains to be drained", "相手に減らせるMPが残っている時")
        "SKILL_FOLLOWUP", "FOLLOWUP" -> pick(language, "효과 만료 안에 후속 공격 스킬을 시작·완료할 MP와 시간이 있을 때", "Enough MP and time remain to complete a follow-up skill before expiry", "期限内に後続攻撃スキルを開始・完了できるMPと時間がある時")
        "BASIC_FOLLOWUP" -> pick(language, "평타를 이어 사용하는 것이 현재 상황에서 유리할 때", "Following with basic attacks is useful in the current state", "現在の状況で通常攻撃を続けることが有利な時")
        "ENEMY_BASIC" -> pick(language, "상대의 공개된 공격·MP 상태상 평타 압박이 유효할 때", "Visible enemy attacks and MP suggest meaningful basic pressure", "公開された相手の攻撃・MPから通常攻撃の脅威がある時")
        "ENEMY_SKILL", "BIG_HIT" -> pick(language, "관측된 상대 공격이 방어 비용보다 큰 위협일 때", "An observed enemy attack justifies the defensive cost", "観測された相手の攻撃が防御コストに見合う脅威の時")
        else -> pick(language, "현재 HP·MP·시전·재사용·남은 기간의 기대 효용이 공격보다 높을 때", "Current HP, MP, casts, cooldowns and duration give more value than attacking", "現在のHP・MP・詠唱・再使用・残り時間から攻撃以上の価値がある時")
    }
    fun limitationText(language: String): String {
        val timing = pick(language, "시전은 행동 1회를 사용하며 HP 0 이후 완료되지 않습니다. MP는 시작에 지불합니다.", "Casting uses an action and cannot complete after defeat. MP is paid at the start.", "詠唱は1行動を使い、HP0後は完了しません。MPは開始時に支払います。")
        val right = if (oncePerBattle) pick(language, " 결투당 1회.", " Once per duel.", " 決闘につき1回。") else if (charges > 0) pick(language, " 유효기간 안에 최대 ${charges}회 사용하며 갱신·중첩하지 않습니다.", " At most $charges uses before expiry; no stacking or refresh.", " 期限内に最大${charges}回。重複・更新しません。") else ""
        val shield = if (kind in setOf(ArenaSupportKind.BASIC_SHATTER, ArenaSupportKind.SKILL_SHATTER)) pick(language, " 추가 파쇄는 HP 피해로 넘기지 않습니다.", " Extra shield loss never spills into HP.", " 追加破砕はHPダメージになりません。") else ""
        return timing + right + shield
    }
    private fun pick(language: String, ko: String, en: String, ja: String) = when(language.substringBefore('-')) { "en" -> en; "ja" -> ja; else -> ko }
    private fun englishCount(number: String, singular: String, plural: String): String =
        "$number ${if (number == "1") singular else plural}"
    private fun nextKo(action: String): String =
        if (charges <= 1) "다음 $action" else "다음 ${charges}회의 $action"
    private fun nextEn(singular: String, plural: String): String =
        if (charges <= 1) "Next $singular" else "Next ${englishCount(charges.toString(), singular, plural)}"
    private fun useVerb(singular: String, plural: String): String =
        if (charges <= 1) singular else plural
    private fun nextJa(action: String): String =
        if (charges <= 1) "次の$action" else "次の${charges}回の$action"
    private fun accuracy(value: Double): String = value.roundToInt().toString()
    private fun mirrorChanceSequence(images: Int): String =
        (images downTo 1).joinToString("→") { remaining -> "${number(100.0 / (remaining + 1))}%" }
    private fun number(value: Double) = String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
}

object ArenaSupportCatalog {
    val values: List<ArenaSupportDefinition> = listOf(
        ArenaSupportDefinition("ARENA_SUP_FIGHTER_01", HeroClass.WARRIOR, 5L, ArenaSupportKind.IRON, 25, 1, 4, 5, 0, 25.0, 0.0, 0.0, false, "철벽 자세", "Ironwall Stance", "鉄壁の構え", "받는 직접 피해·지속 피해 {v}% 감소", "Reduce direct and periodic damage taken by {v}%", "受ける直接・継続ダメージを{v}%軽減", "UTILITY"),
        ArenaSupportDefinition("ARENA_SUP_FIGHTER_02", HeroClass.WARRIOR, 10L, ArenaSupportKind.SHOUT, 20, 1, 6, 6, 0, 20.0, 0.0, 0.0, false, "전투 함성", "Battle Cry", "戦いの雄叫び", "직접 공격 피해 +{v}%", "Direct attack damage +{v}%", "直接攻撃ダメージ+{v}%", "UTILITY"),
        ArenaSupportDefinition("ARENA_SUP_FIGHTER_03", HeroClass.WARRIOR, 50L, ArenaSupportKind.BASIC_SHATTER, 20, 1, 4, 5, 1, 100.0, 0.0, 0.0, false, "파쇄 준비", "Shatter Preparation", "破砕の構え", "첫 성공 평타가 일반 흡수량의 {v}%만큼 보호막 추가 파쇄", "First successful basic hit removes {v}% of its absorption again from shields", "最初に成功した通常攻撃の吸収量の{v}%分、シールドを追加破砕", "SHIELD"),
        ArenaSupportDefinition("ARENA_SUP_FIGHTER_04", HeroClass.WARRIOR, 60L, ArenaSupportKind.COUNTER, 20, 1, 3, 4, 1, 30.0, 0.0, 0.0, false, "반격 준비", "Counter Preparation", "反撃の構え", "다음 평타 피해 +{v}%", "Next basic attack damage +{v}%", "次の通常攻撃ダメージ+{v}%", "RECENT_DAMAGE"),
        ArenaSupportDefinition("ARENA_SUP_FIGHTER_05", HeroClass.WARRIOR, 70L, ArenaSupportKind.BASIC_PIERCE, 20, 1, 5, 4, 3, 40.0, 0.0, 0.0, false, "정면 돌파", "Frontal Breakthrough", "正面突破", "지속 중 성공 평타가 보조 피해 감소율 {v}% 무시", "Successful basic hits during the effect ignore {v}% of damage reduction from support skills", "効果中に成功した通常攻撃が補助スキルのダメージ軽減率を{v}%無視", "DEFENSE"),
        ArenaSupportDefinition("ARENA_SUP_FIGHTER_06", HeroClass.WARRIOR, 20L, ArenaSupportKind.CLEANSE_ACCURACY, 15, 1, 0, 4, 0, 1.0, 0.0, 0.0, false, "호흡 정돈", "Steady Breathing", "呼吸を整える", "명중 감소 상태 {v}개 제거", "Remove {v} accuracy penalty", "命中低下を{v}件解除", "ACCURACY_DEBUFF"),
        ArenaSupportDefinition("ARENA_SUP_FIGHTER_11", HeroClass.WARRIOR, 40L, ArenaSupportKind.TAUNT, 20, 1, 2, 6, 1, 1.0, 0.0, 0.0, false, "도발의 포효", "Taunting Roar", "挑発の咆哮", "저항 판정 성공 시 다음 새 행동 {v}회를 직접 공격으로 제한", "On a successful control check, force the next {v} new action to be a direct attack", "制御判定に成功すると、次の新しい行動{v}回を直接攻撃に限定", "CONTROL"),
        ArenaSupportDefinition("ARENA_SUP_FIGHTER_08", HeroClass.WARRIOR, 30L, ArenaSupportKind.BANDAGE, 30, 2, 0, 0, 1, 12.0, 7.0, 35.0, true, "응급 붕대", "Emergency Bandage", "応急手当", "CON 제곱근 ×{s} 회복, 최대 HP의 {v}% 상한", "Heal √CON ×{s}, capped at {v}% max HP", "√CON ×{s}回復。最大HPの{v}%が上限", "LOW_HP"),
        ArenaSupportDefinition("ARENA_SUP_FIGHTER_09", HeroClass.WARRIOR, 90L, ArenaSupportKind.DAMAGE_CAP, 35, 1, 3, 6, 1, 20.0, 0.0, 0.0, false, "충격 흘리기", "Deflect Impact", "衝撃を受け流す", "첫 직접 공격의 보호막 후 HP 피해를 최대 HP의 {v}%로 제한", "Cap the first direct hit after shields at {v}% max HP", "最初の直接攻撃によるシールド後のHPダメージを最大HPの{v}%に制限", "BIG_HIT"),
        ArenaSupportDefinition("ARENA_SUP_FIGHTER_10", HeroClass.WARRIOR, 80L, ArenaSupportKind.PURSUIT, 15, 1, 3, 4, 1, 35.0, 15.0, 0.0, false, "추격 준비", "Pursuit Preparation", "追撃の構え", "다음 평타 피해 +{v}%, 명중률 +{s}퍼센트포인트", "Next basic attack damage +{v}%, accuracy +{s} percentage points", "次の通常攻撃ダメージ+{v}%、命中率+{s}ポイント", "OWN_MISS"),
        ArenaSupportDefinition("ARENA_SUP_ROGUE_01", HeroClass.ROGUE, 10L, ArenaSupportKind.STEALTH, 25, 1, 4, 6, 1, 100.0, 0.0, 0.0, false, "은신", "Stealth", "隠密", "다음 성공 직접 공격의 피해 배율에 +{v}%", "Next successful direct attack gains +{v}% base damage", "次に成功した直接攻撃のダメージ倍率に+{v}%", "FOLLOWUP"),
        ArenaSupportDefinition("ARENA_SUP_ROGUE_02", HeroClass.ROGUE, 40L, ArenaSupportKind.SMOKE, 15, 1, 4, 6, 0, 15.0, 0.0, 0.0, false, "연막", "Smoke Screen", "煙幕", "상대 직접 공격 명중률 −{v}퍼센트포인트", "Enemy direct-attack accuracy −{v} percentage points", "相手の直接攻撃の命中率−{v}ポイント", "UTILITY"),
        ArenaSupportDefinition("ARENA_SUP_ROGUE_03", HeroClass.ROGUE, 50L, ArenaSupportKind.MP_DRAIN, 15, 1, 4, 5, 1, 20.0, 0.0, 0.0, false, "교란 준비", "Disruption Preparation", "撹乱の構え", "다음 성공 직접 공격에 상대 MP 최대 {v} 감소", "Next successful direct attack drains up to {v} enemy MP", "次に成功した直接攻撃で相手のMPを最大{v}減少", "ENEMY_MP"),
        ArenaSupportDefinition("ARENA_SUP_ROGUE_04", HeroClass.ROGUE, 5L, ArenaSupportKind.EVASION, 20, 1, 3, 5, 1, 40.0, 0.0, 0.0, false, "몸놀림", "Footwork", "身のこなし", "명중 판정을 통과한 첫 직접 공격을 {v}% 확률로 회피", "{v}% chance to evade the first direct attack that passes accuracy", "命中判定を通過した最初の直接攻撃を{v}%の確率で回避", "UTILITY"),
        ArenaSupportDefinition("ARENA_SUP_ROGUE_05", HeroClass.ROGUE, 60L, ArenaSupportKind.OPPORTUNITY, 15, 1, 3, 4, 1, 50.0, 0.0, 0.0, false, "기회 포착", "Seize Opportunity", "好機をつかむ", "다음 평타 피해 +{v}%", "Next basic attack damage +{v}%", "次の通常攻撃ダメージ+{v}%", "ENEMY_MISS"),
        ArenaSupportDefinition("ARENA_SUP_ROGUE_11", HeroClass.ROGUE, 30L, ArenaSupportKind.POISON_COAT, 20, 1, 4, 5, 2, 40.0, 4.0, 3.0, false, "독 도포", "Poison Coating", "毒の塗布", "직접 HP 타격마다 독 1층. 층당 {s}턴 동안 매 턴 공격력 {v}%, 최대 {t}층", "Each HP hit adds poison: {v}% attack power per turn for {s} turns, up to {t} stacks", "HP直撃ごとに毒を1層付与。{s}ターン毎ターン攻撃力の{v}%、最大{t}層", "POISON_ROOM"),
        ArenaSupportDefinition("ARENA_SUP_ROGUE_07", HeroClass.ROGUE, 20L, ArenaSupportKind.CLEANSE, 15, 1, 0, 4, 0, 1.0, 0.0, 0.0, false, "탈출술", "Escape Art", "脱出術", "해로운 상태 {v}개 제거. 독은 모든 층 제거", "Remove {v} harmful status; poison loses every stack", "有害状態を{v}件解除。毒は全層解除", "DEBUFF"),
        ArenaSupportDefinition("ARENA_SUP_ROGUE_12", HeroClass.ROGUE, 90L, ArenaSupportKind.POISON_ACCELERATE, 20, 1, 0, 4, 0, 1.0, 0.0, 3.0, false, "독성 촉진", "Toxic Acceleration", "毒性促進", "독이 1~2층이면 {v}층 추가. 기존 최종 만료 시각 유지", "Add {v} poison stack when 1–2 exist; keep the latest existing expiry", "毒が1～2層なら{v}層追加。既存の最終終了時刻を維持", "POISON_EXISTING"),
        ArenaSupportDefinition("ARENA_SUP_ROGUE_09", HeroClass.ROGUE, 70L, ArenaSupportKind.HEAL_BLOCK_PREP, 20, 1, 4, 5, 1, 40.0, 4.0, 0.0, false, "회복 저지", "Healing Interdiction", "回復妨害", "다음 성공 직접 공격에 {s}턴 회복량 −{v}% 표식", "Next successful direct hit marks healing received −{v}% for {s} turns", "次に成功した直接攻撃で{s}ターン回復量−{v}%を付与", "ENEMY_HEAL"),
        ArenaSupportDefinition("ARENA_SUP_ROGUE_10", HeroClass.ROGUE, 80L, ArenaSupportKind.WRIST, 20, 1, 5, 6, 0, 30.0, 0.0, 0.0, false, "손목 견제", "Wrist Check", "手首への牽制", "상대 평타 피해 −{v}%", "Enemy basic attack damage −{v}%", "相手の通常攻撃ダメージ−{v}%", "ENEMY_BASIC"),
        ArenaSupportDefinition("ARENA_SUP_RANGER_01", HeroClass.RANGER, 10L, ArenaSupportKind.AIM, 15, 1, 6, 6, 0, 10.0, 10.0, 0.0, false, "정조준", "Steady Aim", "精密照準", "직접 공격 피해 +{v}%, 명중률 +{s}퍼센트포인트", "Direct attack damage +{v}%, accuracy +{s} percentage points", "直接攻撃ダメージ+{v}%、命中率+{s}ポイント", "UTILITY"),
        ArenaSupportDefinition("ARENA_SUP_RANGER_02", HeroClass.RANGER, 50L, ArenaSupportKind.REVEAL, 25, 1, 0, 6, 0, 1.0, 0.0, 0.0, false, "간파", "Expose", "看破", "상대 은신 또는 미러 {v}개 제거. 은신 우선", "Remove {v} enemy Stealth or Mirror; Stealth first", "相手の隠密またはミラーを{v}件解除。隠密を優先", "ENEMY_ILLUSION"),
        ArenaSupportDefinition("ARENA_SUP_RANGER_03", HeroClass.RANGER, 30L, ArenaSupportKind.RAPID, 20, 1, 6, 6, 0, 25.0, 0.0, 0.0, false, "속사 준비", "Rapid Shot Preparation", "速射の構え", "평타 피해 +{v}%. 추가 행동 없음", "Basic attack damage +{v}%; no extra actions", "通常攻撃ダメージ+{v}%。追加行動なし", "BASIC_FOLLOWUP"),
        ArenaSupportDefinition("ARENA_SUP_RANGER_04", HeroClass.RANGER, 80L, ArenaSupportKind.PIERCE, 20, 1, 6, 6, 0, 40.0, 0.0, 0.0, false, "관통 조준", "Piercing Aim", "貫通照準", "직접 공격이 보조 피해 감소율 {v}% 무시", "Direct attacks ignore {v}% of damage reduction from support skills", "直接攻撃が補助ダメージ軽減率の{v}%を無視", "DEFENSE"),
        ArenaSupportDefinition("ARENA_SUP_RANGER_05", HeroClass.RANGER, 70L, ArenaSupportKind.OBSERVE, 20, 1, 4, 5, 0, 50.0, 0.0, 0.0, false, "약점 관찰", "Observe Weakness", "弱点観察", "상대 보조 피해 감소율 {v}% 무시", "Ignore {v}% of enemy support damage reduction", "相手の補助によるダメージ軽減率の{v}%を無視", "ENEMY_MITIGATION"),
        ArenaSupportDefinition("ARENA_SUP_RANGER_06", HeroClass.RANGER, 5L, ArenaSupportKind.BASIC_GUARD, 20, 1, 5, 6, 0, 30.0, 0.0, 0.0, false, "경계 태세", "Vigilance", "警戒態勢", "받는 평타 피해 −{v}%", "Basic attack damage taken −{v}%", "受ける通常攻撃ダメージ−{v}%", "ENEMY_BASIC"),
        ArenaSupportDefinition("ARENA_SUP_RANGER_07", HeroClass.RANGER, 20L, ArenaSupportKind.CLEANSE_ACCURACY, 15, 1, 0, 4, 0, 1.0, 0.0, 0.0, false, "시야 회복", "Clear Vision", "視界回復", "명중 감소 상태 {v}개 제거", "Remove {v} accuracy penalty", "命中低下を{v}件解除", "ACCURACY_DEBUFF"),
        ArenaSupportDefinition("ARENA_SUP_RANGER_08", HeroClass.RANGER, 60L, ArenaSupportKind.SKILL_SHATTER, 20, 1, 5, 6, 1, 100.0, 0.0, 0.0, false, "방벽 사냥", "Barrier Hunt", "防壁狩り", "다음 공격 스킬이 일반 흡수량의 {v}%만큼 보호막 추가 파쇄", "Next attack skill removes {v}% of its absorption again from shields", "次の攻撃スキルの吸収量の{v}%分、シールドを追加破砕", "SHIELD"),
        ArenaSupportDefinition("ARENA_SUP_RANGER_11", HeroClass.RANGER, 40L, ArenaSupportKind.TRAP, 20, 1, 3, 6, 1, 1.0, 0.0, 0.0, false, "충격 덫", "Shock Trap", "衝撃の罠", "상대의 첫 새 직접 공격 시작에 저항 판정. 성공 시 시전 +{v}턴", "Check control at the first new enemy direct attack; success delays it by {v} turn", "相手の最初の新しい直接攻撃開始時に制御判定。成功で詠唱+{v}ターン", "CONTROL"),
        ArenaSupportDefinition("ARENA_SUP_RANGER_10", HeroClass.RANGER, 90L, ArenaSupportKind.HEAL_TRACK, 20, 1, 4, 5, 1, 40.0, 40.0, 0.0, false, "회복 추적", "Track Recovery", "回復追跡", "최근 실제 회복량의 {v}% 추가 피해, 기본 피해 +{s}% 상한. 같은 사건 재사용 불가", "Add {v}% of a recent heal, capped at +{s}% base damage; each heal is used once", "直近の実回復量の{v}%を加算。基礎ダメージ+{s}%が上限。同じ回復は再利用不可", "RECENT_HEAL"),
        ArenaSupportDefinition("ARENA_SUP_MAGE_01", HeroClass.MAGE, 5L, ArenaSupportKind.SHIELD, 25, 1, 5, 6, 0, 20.0, 0.0, 0.0, false, "마력 실드", "Mana Shield", "魔力シールド", "최대 HP {v}% 보호막. 지속 피해도 흡수", "Shield for {v}% max HP; also absorbs periodic damage", "最大HPの{v}%のシールド。継続ダメージも吸収", "UTILITY"),
        ArenaSupportDefinition("ARENA_SUP_MAGE_02", HeroClass.MAGE, 50L, ArenaSupportKind.MIRROR, 35, 2, 6, 8, 4, 20.0, 0.0, 0.0, false, "미러이미지", "Mirror Image", "ミラーイメージ", "명중한 직접 공격마다 20→25→33⅓→50% 무효 판정, 매 판정 분신 1개 소모", "Negate accurate direct hits at 20→25→33⅓→50%; spend one image per check", "命中した直接攻撃を20→25→33⅓→50%で無効化。判定ごとに分身1体消費", "UTILITY"),
        ArenaSupportDefinition("ARENA_SUP_MAGE_03", HeroClass.MAGE, 60L, ArenaSupportKind.STABILIZE, 20, 1, 5, 6, 0, 100.0, 0.0, 0.0, false, "주문 안정화", "Spell Stabilization", "呪文安定化", "공격 스킬 일반 명중 {v}%. 회피·미러는 별도 판정", "Attack skill base accuracy {v}%; evasion and mirrors still apply", "攻撃スキルの通常命中{v}%。回避・ミラーは別判定", "ACCURACY_DEBUFF"),
        ArenaSupportDefinition("ARENA_SUP_MAGE_04", HeroClass.MAGE, 70L, ArenaSupportKind.PHASE, 20, 1, 5, 6, 0, 40.0, 0.0, 0.0, false, "위상 조율", "Phase Alignment", "位相調律", "직접 피해 {v}%가 보호막 우회. 총 피해 증가 없음", "{v}% of direct damage bypasses shields; total damage unchanged", "直接ダメージの{v}%がシールドを貫通。総ダメージは増加しない", "SHIELD"),
        ArenaSupportDefinition("ARENA_SUP_MAGE_05", HeroClass.MAGE, 80L, ArenaSupportKind.DISPEL, 25, 1, 0, 5, 0, 1.0, 0.0, 0.0, false, "역마법", "Countermagic", "対抗魔法", "상대 공격·명중·피해 감소 강화 {v}개 제거", "Remove {v} enemy attack, accuracy, or damage-reduction buff", "相手の攻撃・命中・ダメージ軽減の強化を{v}件解除", "ENEMY_BUFF"),
        ArenaSupportDefinition("ARENA_SUP_MAGE_13", HeroClass.MAGE, 40L, ArenaSupportKind.SLEEP, 25, 1, 1, 6, 1, 1.0, 0.0, 0.0, false, "수면의 안개", "Mist of Sleep", "眠りの霧", "저항 판정 성공 시 다음 {v}턴 선택·시전 진행 정지. HP 피해로 각성", "On control success, pause choices and casting next {v} turn; HP damage wakes the target", "制御成功で次の{v}ターンの選択・詠唱進行を停止。HPダメージで覚醒", "CONTROL"),
        ArenaSupportDefinition("ARENA_SUP_MAGE_07", HeroClass.MAGE, 10L, ArenaSupportKind.CONDENSE, 15, 1, 5, 6, 1, 40.0, 0.0, 0.0, false, "마력 응축", "Mana Focus", "魔力凝縮", "다음 공격 스킬 피해 +{v}%", "Next attack skill damage +{v}%", "次の攻撃スキルダメージ+{v}%", "SKILL_FOLLOWUP"),
        ArenaSupportDefinition("ARENA_SUP_MAGE_08", HeroClass.MAGE, 20L, ArenaSupportKind.STATUS_GUARD, 20, 1, 4, 6, 1, 1.0, 0.0, 0.0, false, "주문 방벽", "Spell Ward", "呪文障壁", "새 해로운 지속 상태 {v}회 차단. 독·화상·제어 포함", "Block {v} new harmful status, including poison, burn and control", "新たな有害持続状態を{v}回防ぐ。毒・火傷・制御を含む", "STATUS_THREAT"),
        ArenaSupportDefinition("ARENA_SUP_MAGE_11", HeroClass.MAGE, 30L, ArenaSupportKind.BURN_PREP, 20, 1, 5, 5, 1, 70.0, 2.0, 0.0, false, "화염 각인", "Flame Inscription", "火炎刻印", "다음 공격 스킬 HP 타격에 화상. {s}턴 매 턴 공격력 {v}%", "Next skill HP hit burns for {v}% attack power per turn for {s} turns", "次のスキルHP直撃で火傷。{s}ターン毎ターン攻撃力の{v}%", "SKILL_FOLLOWUP"),
        ArenaSupportDefinition("ARENA_SUP_MAGE_12", HeroClass.MAGE, 90L, ArenaSupportKind.EMBER, 25, 1, 4, 6, 1, 70.0, 2.0, 0.0, false, "잿불 결계", "Ember Ward", "残り火の結界", "첫 직접 HP 피격 후 생존 시 상대에게 {s}턴 화상, 매 턴 공격력 {v}%", "After surviving the first direct HP hit, burn the attacker for {v}% attack power over each of {s} turns", "最初のHP直撃を耐えると、相手に{s}ターン毎ターン攻撃力の{v}%の火傷", "UTILITY"),
        ArenaSupportDefinition("ARENA_SUP_CLERIC_01", HeroClass.CLERIC, 5L, ArenaSupportKind.HEAL, 25, 2, 0, 4, 0, 20.0, 0.0, 0.0, false, "회복", "Heal", "回復", "완료 시 최대 HP {v}% 회복", "On completion, restore {v}% max HP", "完了時に最大HPの{v}%回復", "MISSING_HP"),
        ArenaSupportDefinition("ARENA_SUP_CLERIC_02", HeroClass.CLERIC, 30L, ArenaSupportKind.BLESS, 20, 1, 6, 6, 0, 20.0, 5.0, 0.0, false, "축복", "Blessing", "祝福", "직접 공격 피해 +{v}%, 명중률 +{s}퍼센트포인트", "Direct attack damage +{v}%, accuracy +{s} percentage points", "直接攻撃ダメージ+{v}%、命中率+{s}ポイント", "UTILITY"),
        ArenaSupportDefinition("ARENA_SUP_CLERIC_03", HeroClass.CLERIC, 20L, ArenaSupportKind.CLEANSE, 15, 1, 0, 4, 0, 1.0, 0.0, 0.0, false, "정화", "Purify", "浄化", "해로운 상태 {v}개 제거. 독은 모든 층 제거", "Remove {v} harmful status; poison loses every stack", "有害状態を{v}件解除。毒は全層解除", "DEBUFF"),
        ArenaSupportDefinition("ARENA_SUP_CLERIC_04", HeroClass.CLERIC, 40L, ArenaSupportKind.REGEN, 25, 2, 4, 6, 0, 5.0, 0.0, 0.0, false, "재생의 기도", "Prayer of Regeneration", "再生の祈り", "완료 다음 턴부터 매 턴 최대 HP {v}% 회복", "Starting next turn, restore {v}% max HP each turn", "完了の次ターンから毎ターン最大HPの{v}%回復", "MISSING_HP"),
        ArenaSupportDefinition("ARENA_SUP_CLERIC_05", HeroClass.CLERIC, 70L, ArenaSupportKind.GRACE, 25, 1, 4, 6, 1, 35.0, 12.0, 15.0, false, "일격의 가호", "Grace against Impact", "一撃の加護", "보호막 후 HP 피해가 최대 HP {t}% 이상인 첫 직접 공격을 {v}% 경감, 절감량 HP {s}% 상한", "Reduce the first post-shield hit of at least {t}% max HP by {v}%, saving at most {s}% max HP", "シールド後の被害が最大HPの{t}%以上の最初の直撃を{v}%軽減。軽減量はHPの{s}%まで", "BIG_HIT"),
        ArenaSupportDefinition("ARENA_SUP_CLERIC_06", HeroClass.CLERIC, 10L, ArenaSupportKind.RESTRAINT, 20, 1, 5, 6, 0, 20.0, 10.0, 0.0, false, "절제의 서약", "Vow of Restraint", "節制の誓い", "받는 피해 −{v}%, 주는 직접 피해 −{s}%", "Damage taken −{v}%, direct damage dealt −{s}%", "受けるダメージ−{v}%、与える直接ダメージ−{s}%", "UTILITY"),
        ArenaSupportDefinition("ARENA_SUP_CLERIC_07", HeroClass.CLERIC, 50L, ArenaSupportKind.TRUTH, 20, 1, 4, 5, 2, 50.0, 0.0, 0.0, false, "진실의 빛", "Light of Truth", "真実の光", "상대 은신 추가 증폭 또는 다음 두 미러 판정 확률을 {v}% 완화", "Weaken enemy Stealth bonus or its next two mirror probabilities by {v}%", "相手の隠密追加倍率、または次の2回のミラー確率を{v}%弱める", "ENEMY_ILLUSION"),
        ArenaSupportDefinition("ARENA_SUP_CLERIC_08", HeroClass.CLERIC, 60L, ArenaSupportKind.SANCTUARY, 20, 1, 5, 6, 0, 1.0, 0.0, 0.0, false, "성역의 기도", "Prayer of Sanctuary", "聖域の祈り", "새 해로운 상태의 지속 −{v}턴, 최소 1턴", "New harmful statuses last {v} fewer turn, minimum 1", "新たな有害状態の持続−{v}ターン。最低1ターン", "STATUS_THREAT"),
        ArenaSupportDefinition("ARENA_SUP_CLERIC_09", HeroClass.CLERIC, 80L, ArenaSupportKind.LIFESTEAL, 15, 1, 5, 6, 0, 25.0, 6.0, 0.0, false, "생명의 연결", "Life Link", "命のつながり", "직접 HP 피해의 {v}% 회복, 공격당 최대 HP {s}% 상한", "Heal {v}% of direct HP damage, capped at {s}% max HP per attack", "直接HPダメージの{v}%を回復。1攻撃につき最大HPの{s}%まで", "MISSING_HP"),
        ArenaSupportDefinition("ARENA_SUP_CLERIC_10", HeroClass.CLERIC, 90L, ArenaSupportKind.SEAL, 15, 1, 4, 5, 1, 1.0, 0.0, 0.0, false, "징계의 인장", "Seal of Discipline", "戒めの印", "첫 직접 HP 타격 후 상대 공격·명중·피해 감소 강화 {v}개 제거", "After the first HP hit, remove {v} enemy attack, accuracy or reduction buff", "最初のHP直撃後、相手の攻撃・命中・軽減の強化を{v}件解除", "ENEMY_BUFF"),
        ArenaSupportDefinition("ARENA_SUP_PALADIN_01", HeroClass.PALADIN, 5L, ArenaSupportKind.SKILL_GUARD, 25, 1, 4, 6, 1, 40.0, 0.0, 0.0, false, "수호 서약", "Guardian's Vow", "守護の誓約", "첫 명중 공격 스킬 피해 −{v}%", "First accurate attack skill damage taken −{v}%", "最初に命中した攻撃スキルのダメージ−{v}%", "ENEMY_SKILL"),
        ArenaSupportDefinition("ARENA_SUP_PALADIN_02", HeroClass.PALADIN, 60L, ArenaSupportKind.RETRIBUTION, 25, 1, 5, 6, 1, 25.0, 0.0, 0.0, false, "응징 준비", "Retribution Preparation", "応報の構え", "다음 공격 스킬 피해 +{v}%", "Next attack skill damage +{v}%", "次の攻撃スキルダメージ+{v}%", "RECENT_DAMAGE"),
        ArenaSupportDefinition("ARENA_SUP_PALADIN_03", HeroClass.PALADIN, 50L, ArenaSupportKind.JUDGMENT, 25, 1, 4, 6, 1, 1.0, 0.0, 0.0, false, "신성 판결", "Divine Judgment", "神聖な裁き", "첫 성공 직접 공격 피해 후 상대 이로운 보조 상태 {v}개 제거", "After the first successful direct hit, remove {v} enemy beneficial support", "最初に成功した直接攻撃の処理後、相手の有益な補助を{v}件解除", "ENEMY_ANY_BUFF"),
        ArenaSupportDefinition("ARENA_SUP_PALADIN_04", HeroClass.PALADIN, 20L, ArenaSupportKind.CLEANSE, 20, 1, 0, 4, 0, 1.0, 0.0, 0.0, false, "정결의 서약", "Vow of Purity", "清浄の誓い", "해로운 상태 {v}개 제거. 독은 모든 층 제거", "Remove {v} harmful status; poison loses every stack", "有害状態を{v}件解除。毒は全層解除", "DEBUFF"),
        ArenaSupportDefinition("ARENA_SUP_PALADIN_05", HeroClass.PALADIN, 70L, ArenaSupportKind.RESOLVE, 20, 1, 4, 6, 0, 25.0, 0.0, 35.0, false, "불굴의 결의", "Indomitable Resolve", "不屈の決意", "현재 HP {t}% 이하에서 직접 공격 피해 +{v}%", "While current HP is at most {t}%, direct damage +{v}%", "現在HP{t}%以下で直接攻撃ダメージ+{v}%", "LOW_HP"),
        ArenaSupportDefinition("ARENA_SUP_PALADIN_06", HeroClass.PALADIN, 30L, ArenaSupportKind.STATUS_GUARD, 25, 1, 4, 6, 1, 1.0, 0.0, 0.0, false, "성역의 맹세", "Oath of Sanctuary", "聖域の誓い", "새 해로운 지속 상태 {v}회 차단. 독·화상·제어 포함", "Block {v} new harmful status, including poison, burn and control", "新たな有害持続状態を{v}回防ぐ。毒・火傷・制御を含む", "STATUS_THREAT"),
        ArenaSupportDefinition("ARENA_SUP_PALADIN_07", HeroClass.PALADIN, 40L, ArenaSupportKind.LOW_SHIELD, 30, 1, 4, 6, 0, 15.0, 8.0, 30.0, false, "약자의 수호", "Shelter the Weak", "弱者の守護", "CHA 제곱근 ×{s} 보호막, 최대 HP {v}% 상한", "Shield for √CHA ×{s}, capped at {v}% max HP", "√CHA ×{s}のシールド。最大HPの{v}%が上限", "LOW_HP"),
        ArenaSupportDefinition("ARENA_SUP_PALADIN_08", HeroClass.PALADIN, 80L, ArenaSupportKind.LAY_HANDS, 35, 2, 0, 0, 1, 15.0, 8.0, 35.0, true, "안수", "Lay on Hands", "按手", "CHA 제곱근 ×{s} 회복, 최대 HP {v}% 상한", "Heal √CHA ×{s}, capped at {v}% max HP", "√CHA ×{s}回復。最大HPの{v}%が上限", "LOW_HP"),
        ArenaSupportDefinition("ARENA_SUP_PALADIN_09", HeroClass.PALADIN, 10L, ArenaSupportKind.FOCUS, 15, 1, 5, 6, 1, 15.0, 0.0, 0.0, false, "성스러운 집중", "Sacred Focus", "聖なる集中", "다음 공격 스킬 명중률 +{v}퍼센트포인트", "Next attack skill accuracy +{v} percentage points", "次の攻撃スキル命中率+{v}ポイント", "SKILL_FOLLOWUP"),
        ArenaSupportDefinition("ARENA_SUP_PALADIN_10", HeroClass.PALADIN, 90L, ArenaSupportKind.EXECUTE, 25, 1, 5, 6, 1, 20.0, 0.0, 30.0, false, "집행 선고", "Sentence of Execution", "執行宣告", "다음 공격 스킬 피해 +{v}%. 상대 HP {t}% 이하에서 준비", "Next skill damage +{v}%; prepare when enemy HP is at most {t}%", "次の攻撃スキルダメージ+{v}%。相手HP{t}%以下で準備", "ENEMY_LOW_HP"),
    )
    private val byId = values.associateBy { it.id }
    init {
        check(values.size == 60 && byId.size == 60)
        check(HeroClass.entries.all { forClass(it).size == 10 })
        check(values.all { it.mp >= 1 && it.castTurns in 1..3 && it.durationTurns >= 0 && it.cooldownTurns >= 0 })
    }
    /** Static core replacements. Conditional follow-up transformations remain described by the linked core. */
    fun effectiveDefinition(id: String, traits: List<ArenaSupportTraitRank>): ArenaSupportDefinition? {
        val base = find(id) ?: return null
        val core = traits.mapNotNull { findCore(it, base.heroClass) }.singleOrNull() ?: return base
        fun p(key: String, fallback: Double) = core.coreParameters[key] ?: fallback
        val current = when(core.id) {
            "AT9_WARRIOR_A_CORE" -> if (base.kind == ArenaSupportKind.SHOUT) base.copy(magnitude = p("replacementShoutDamageBonusPercent", 10.0)) else base
            "AT9_WARRIOR_B_CORE" -> if (base.kind == ArenaSupportKind.BASIC_SHATTER) base.copy(charges = p("replacementApplications", 2.0).toInt(), magnitude = 100 * p("perApplicationShatterMultiplier", .6)) else base
            "AT9_WARRIOR_C_CORE" -> if (base.kind == ArenaSupportKind.COUNTER) base.copy(magnitude = p("replacementRecentHpDamagePercent", 50.0), secondary = p("maximumExtraPercentOwnMaxHp", 8.0),
                effectKo = "기록한 직접 HP 피격량의 {v}%를 다음 평타에 추가, 자신의 최대 HP {s}% 상한. 기존 +30%는 대체",
                effectEn = "Next basic adds {v}% of captured HP damage, capped at {s}% own max HP; replaces +30%",
                effectJa = "記録したHP直撃の{v}%を次の通常攻撃に加算。自身の最大HP{s}%が上限。従来の+30%を置換") else base
            "AT9_ROGUE_A_CORE" -> if (base.kind == ArenaSupportKind.STEALTH) base.copy(magnitude = (p("replacementDamageMultiplier", 1.5) - 1) * 100,
                effectKo = "다음 원래 2~3턴 공격기 피해 +{v}%, 시전 1턴 단축. 시작에 사용권 소비",
                effectEn = "Next originally 2–3-turn skill: damage +{v}%, cast 1 turn shorter; consume at start",
                effectJa = "次の元2～3ターンのスキル：ダメージ+{v}%、詠唱1ターン短縮。開始時消費") else base
            "AT9_ROGUE_B_CORE" -> if (base.kind == ArenaSupportKind.POISON_COAT) base.copy(magnitude = base.magnitude * p("tickDamageMultiplier", .8), secondary = p("replacementLayerDurationTurns", 6.0)) else base
            "AT9_ROGUE_C_CORE" -> if (base.kind == ArenaSupportKind.MP_DRAIN) base.copy(magnitude = p("replacementMaximumManaLoss", 30.0),
                effectKo = "다음 성공 직접 공격에 MP 최대 {v} 감소. 전달 공격의 직접 피해 25% 감소",
                effectEn = "Next successful direct hit drains up to {v} MP; its direct damage is reduced by 25%",
                effectJa = "次に成功した直接攻撃でMPを最大{v}減少。その直撃ダメージは25%減少") else base
            "AT9_RANGER_A_CORE" -> if (base.kind == ArenaSupportKind.AIM) base.copy(magnitude = 0.0,
                effectKo = "명중률 +{s}퍼센트포인트. 상대 긴 공격 잔여 1턴에 원래 1턴 공격기 시작 시 피해 +35%, 추가 MP 4",
                effectEn = "Accuracy +{s} percentage points. Start an original 1-turn skill against a long cast with 1 turn left: +35% damage, +4 MP",
                effectJa = "命中+{s}ポイント。相手の長い詠唱が残り1ターンの時に元1ターンスキルを開始：被害+35%、追加MP4") else base
            "AT9_RANGER_B_CORE" -> if (base.kind == ArenaSupportKind.REVEAL) base.copy(mp = p("sourceSupportMp", 35.0).toInt()) else base
            "AT9_RANGER_C_CORE" -> if (base.kind == ArenaSupportKind.HEAL_TRACK) base.copy(mp = p("sourceSupportMp", 25.0).toInt(), magnitude = p("actualHealingConversionPercent", 30.0), secondary = p("targetMaxHpCapPercent", 8.0),
                effectKo = "이번·직전 턴 실제 회복 합계의 {v}% 추가 피해, 상대 최대 HP {s}% 상한. 사건 재사용 불가",
                effectEn = "Add {v}% of actual healing this or last turn, capped at {s}% enemy max HP; no event reuse",
                effectJa = "このターンと直前ターンの実回復合計の{v}%を追加。相手最大HP{s}%が上限。再利用不可") else base
            "AT9_MAGE_A_CORE" -> if (base.kind == ArenaSupportKind.CONDENSE) base.copy(magnitude = p("damageBonusPercent", 60.0),
                effectKo = "다음 원래 3턴 공격기 피해 +{v}%, 추가 MP 12. 사용권은 시작에 소비",
                effectEn = "Next originally 3-turn skill: damage +{v}%, +12 MP; consume at start",
                effectJa = "次の元3ターンスキル：ダメージ+{v}%、追加MP12。開始時消費") else base
            "AT9_MAGE_B_CORE" -> if (base.kind == ArenaSupportKind.BURN_PREP) base.copy(magnitude = base.magnitude * p("burnTotalMultiplier", 1.4),
                effectKo = "다음 공격 스킬 HP 타격에 {s}턴 매 턴 공격력 {v}% 화상. 전달 직접 피해 20% 감소",
                effectEn = "Next skill HP hit burns for {v}% attack each of {s} turns; delivery direct damage −20%",
                effectJa = "次のスキルHP直撃で{s}ターン毎ターン攻撃力{v}%の火傷。直撃ダメージ−20%") else base
            "AT9_MAGE_C_CORE" -> if (base.kind == ArenaSupportKind.MIRROR) base.copy(castTurns = p("sourceCastTurns", 1.0).toInt(), charges = p("illusionCount", 2.0).toInt(),
                effectKo = "명중한 직접 공격마다 33⅓→50% 무효 판정, 매 판정 분신 1개 소비",
                effectEn = "Negate accurate direct hits at 33⅓→50%; spend one image per check",
                effectJa = "命中した直接攻撃を33⅓→50%で無効化。判定ごとに分身1体消費") else base
            "AT9_CLERIC_A_CORE" -> if (base.kind == ArenaSupportKind.HEAL) base.copy(magnitude = p("instantHealMaxHpPercent", 8.0), durationTurns = 4,
                effectKo = "완료 시 최대 HP {v}% 회복, 다음 4턴 매 턴 3% 회복",
                effectEn = "Restore {v}% max HP on completion, then 3% each of the next 4 turns",
                effectJa = "完了時最大HP{v}%回復。その後4ターン毎ターン3%回復") else base
            "AT9_CLERIC_B_CORE" -> if (base.kind == ArenaSupportKind.LIFESTEAL) base.copy(charges = 1, magnitude = p("actualHpDamageHealPercent", 30.0), secondary = p("ownMaxHpHealCapPercent", 8.0),
                effectKo = "첫 공격 스킬 실제 HP 피해의 {v}% 회복, 최대 HP {s}% 상한. 평타 제외",
                effectEn = "Heal {v}% of the first skill HP hit, capped at {s}% max HP; excludes basic attacks",
                effectJa = "最初のスキル実HPダメージの{v}%回復。最大HP{s}%まで。通常攻撃除外") else base
            "AT9_CLERIC_C_CORE" -> if (base.kind == ArenaSupportKind.CLEANSE) base.copy(mp = p("sourceSupportMp", 25.0).toInt(),
                effectKo = "해로운 상태 {v}개 제거 후 3턴 안 같은 종류의 새 상태 1회 차단",
                effectEn = "Remove {v} harmful status, then block one new status of the same kind within 3 turns",
                effectJa = "有害状態{v}件解除後、3ターン以内に同種の新たな状態を1回防ぐ") else base
            "AT9_PALADIN_A_CORE" -> if (base.kind == ArenaSupportKind.SKILL_GUARD) base.copy(magnitude = p("replacementSkillDamageReductionPercent", 25.0),
                effectKo = "첫 공격 스킬 피해 −{v}%. 실제 절감량 50%를 다음 공격기에 추가, 최대 HP 4% 상한",
                effectEn = "First skill damage −{v}%; add 50% of actual prevented damage to the next skill, capped at 4% max HP",
                effectJa = "最初のスキル被害−{v}%。実軽減量50%を次のスキルに加算、最大HP4%まで") else base
            "AT9_PALADIN_C_CORE" -> if (base.kind == ArenaSupportKind.LAY_HANDS) base.copy(durationTurns = p("shieldDurationTurns", 4.0).toInt(),
                effectKo = "CHA 제곱근 ×{s} 보호막, 최대 HP {v}% 상한. 실제 HP 회복 없음",
                effectEn = "Shield for √CHA ×{s}, capped at {v}% max HP; no HP healing",
                effectJa = "√CHA ×{s}のシールド。最大HP{v}%まで。HPは回復しない") else base
            else -> base
        }
        return if (current === base) base else current.copy(corePreviewId = core.id)
    }
    private fun findCore(rank: ArenaSupportTraitRank, heroClass: HeroClass) = ArenaProgressionCatalog.find(rank.id)
        ?.takeIf { it.heroClass == heroClass && it.isCore && rank.rank == 1 }

    fun find(id: String): ArenaSupportDefinition? = byId[id]
    fun forClass(heroClass: HeroClass): List<ArenaSupportDefinition> = values.filter { it.heroClass == heroClass }.sortedBy { it.unlockLevel }
    fun unlockedIds(heroClass: HeroClass, heroLevel: Long): Set<String> = forClass(heroClass).filter { it.unlockLevel <= heroLevel }.map { it.id }.toSet()
}
