package com.nullplaying.engine.arena

import com.nullplaying.model.HeroClass
import java.util.Locale

enum class ArenaProgressionEffectUnit {
    MP_COST_REDUCTION_PERCENT, ACCURACY_PERCENTAGE_POINTS, MAX_HP_HEAL_PERCENT, DAMAGE_PERCENT,
    SHIELD_DAMAGE_PERCENT, DAMAGE_REDUCTION_PERCENT, DOT_DAMAGE_PERCENT, MAX_HP_SHIELD_PERCENT,
    HEAL_BONUS_PERCENT, DAMAGE_REDUCTION_IGNORE_PERCENT, SUPPORT_POWER_PERCENT,
    SUPPORT_POWER_PERCENTAGE_POINTS, CORE_TRANSFORM,
}

data class ArenaProgressionTraitDefinition(
    val id: String,
    val heroClass: HeroClass,
    val nameKo: String,
    val nameEn: String,
    val nameJa: String,
    val requiredSupportId: String?,
    val effectUnit: ArenaProgressionEffectUnit,
    val baseValues: List<Double>,
    val minHeroLevel: Long = 10,
    val windowTurns: Int = 0,
    val charges: Int = 0,
    val cooldownTurns: Int = 0,
    val stackGroup: String,
    val branch: String = "A",
    val isCore: Boolean = false,
    val requiredSupportIds: List<String> = listOfNotNull(requiredSupportId),
    val conditionKo: String = "",
    val effectKo: String = "",
    val limitationKo: String = "",
    val coreParameters: Map<String, Double> = emptyMap(),
    val coreFlags: Map<String, Boolean> = emptyMap(),
    val coreLists: Map<String, List<Double>> = emptyMap(),
    val coreTextParameters: Map<String, String> = emptyMap(),
) {
    val maxRank: Int get() = if (isCore) 1 else 5
    val maxEnhancement: Int get() = if (isCore) 0 else 3
    val baseValuePerRank: Double get() = if (isCore) 0.0 else if (baseValues.size > 1) baseValues[1] - baseValues[0] else baseValues.single()
    fun basePointCost(rank: Int): Int { require(rank in 0..maxRank); return rank * if (isCore) 5 else 1 }
    fun baseCost(rank: Int): Int = basePointCost(rank)
    fun value(rank: Int, enhancement: Int): Double {
        require(rank in 0..maxRank && enhancement in 0..maxEnhancement)
        require(rank > 0 || enhancement == 0)
        if (rank == 0) return 0.0
        return if (isCore) 1.0 else baseValues[rank - 1] * (1.0 + enhancement * .1)
    }
    fun conditionText(language: String): String = if (language == "ko") conditionKo else {
        val rule = ArenaGrowthRules.rules[id]
        if (rule != null) if (language == "ja") rule.conditionJa else rule.conditionEn
        else ArenaGrowthCoreCopy.condition(this, language)
    }
    fun effectText(language: String, rank: Int, enhancement: Int): String {
        val amount = value(rank, enhancement)
        if (isCore) return ArenaGrowthCoreCopy.effect(this, language)
        val number = formatValue(amount)
        if (language == "ko") return effectKo.replace("{value}", number)
        val rule = requireNotNull(ArenaGrowthRules.rules[id])
        if (id == "AT9_RANGER_B07") {
            val base = requireNotNull(ArenaSupportCatalog.find("ARENA_SUP_RANGER_05")).magnitude
            val final = formatValue(base + amount)
            return if (language == "ja") "弱点観察の補助ダメージ軽減を無視する割合に+$number ポイント（合計$final%）。"
                else "Observe Weakness ignores +$number percentage points of support damage reduction ($final% total)."
        }
        val target = targetText(rule, language)
        val effect = if (language == "ja") when (effectUnit) {
            ArenaProgressionEffectUnit.MP_COST_REDUCTION_PERCENT -> "$target：MP費用 −$number%"
            ArenaProgressionEffectUnit.ACCURACY_PERCENTAGE_POINTS -> "$target：命中率 +$number ポイント"
            ArenaProgressionEffectUnit.MAX_HP_HEAL_PERCENT -> "$target：最大HPの$number%を回復量に加算"
            ArenaProgressionEffectUnit.DAMAGE_PERCENT -> "$target：直接ダメージ +$number%"
            ArenaProgressionEffectUnit.SHIELD_DAMAGE_PERCENT -> "$target：シールド専用ダメージ +$number%"
            ArenaProgressionEffectUnit.DAMAGE_REDUCTION_PERCENT -> "$target：ダメージ軽減 +$number ポイント"
            ArenaProgressionEffectUnit.DOT_DAMAGE_PERCENT -> "$target：持続ダメージ +$number%"
            ArenaProgressionEffectUnit.MAX_HP_SHIELD_PERCENT -> "$target：最大HPの$number%を吸収量に加算"
            ArenaProgressionEffectUnit.HEAL_BONUS_PERCENT -> "$target：元の回復量 +$number%"
            ArenaProgressionEffectUnit.DAMAGE_REDUCTION_IGNORE_PERCENT -> "$target：補助ダメージ軽減を$number%無視"
            ArenaProgressionEffectUnit.SUPPORT_POWER_PERCENT -> "$target：元の変換率・追加量 +$number%"
            ArenaProgressionEffectUnit.SUPPORT_POWER_PERCENTAGE_POINTS -> "$target：ダメージ補正 +$number ポイント"
            ArenaProgressionEffectUnit.CORE_TRANSFORM -> error("Core handled above")
        } else when (effectUnit) {
            ArenaProgressionEffectUnit.MP_COST_REDUCTION_PERCENT -> "$target: MP cost −$number%"
            ArenaProgressionEffectUnit.ACCURACY_PERCENTAGE_POINTS -> "$target: accuracy +$number pp"
            ArenaProgressionEffectUnit.MAX_HP_HEAL_PERCENT -> "$target: add $number% of max HP to healing"
            ArenaProgressionEffectUnit.DAMAGE_PERCENT -> "$target: direct damage +$number%"
            ArenaProgressionEffectUnit.SHIELD_DAMAGE_PERCENT -> "$target: shield-only damage +$number%"
            ArenaProgressionEffectUnit.DAMAGE_REDUCTION_PERCENT -> "$target: damage reduction +$number pp"
            ArenaProgressionEffectUnit.DOT_DAMAGE_PERCENT -> "$target: damage over time +$number%"
            ArenaProgressionEffectUnit.MAX_HP_SHIELD_PERCENT -> "$target: add $number% of max HP to absorption"
            ArenaProgressionEffectUnit.HEAL_BONUS_PERCENT -> "$target: original healing +$number%"
            ArenaProgressionEffectUnit.DAMAGE_REDUCTION_IGNORE_PERCENT -> "$target: ignore $number% of support damage reduction"
            ArenaProgressionEffectUnit.SUPPORT_POWER_PERCENT -> "$target: original conversion or extra amount +$number%"
            ArenaProgressionEffectUnit.SUPPORT_POWER_PERCENTAGE_POINTS -> "$target: damage bonus +$number pp"
            ArenaProgressionEffectUnit.CORE_TRANSFORM -> error("Core handled above")
        }
        return if (rule.event == ArenaGrowthEvent.NONE || windowTurns == 0) effect else
            if (language == "ja") "$effect。発動後${windowTurns}ターン以内に1回。" else "$effect. Once within $windowTurns turns after triggering."
    }
    /** Compact, value-first row copy; full rules remain available in the detail sheet. */
    fun summaryText(language: String, rank: Int, enhancement: Int): String {
        if (isCore) return ArenaGrowthCoreCopy.summary(this, language)
        val number = formatValue(value(rank, enhancement))
        val ko = language == "ko"
        val ja = language == "ja"
        val rule = ArenaGrowthRules.rules.getValue(id)
        val target = when (rule.target) {
            ArenaGrowthTarget.BASIC -> if (ko) "평타" else if (ja) "通常攻撃" else "Basic attack"
            ArenaGrowthTarget.SKILL -> if (ko) "공격기" else if (ja) "攻撃スキル" else "Attack skill"
            ArenaGrowthTarget.ANY_SUPPORT, ArenaGrowthTarget.LINKED_SUPPORT -> if (ko) "보조" else if (ja) "補助" else "Support"
            else -> if (ko) "조건부" else if (ja) "条件達成時" else "When eligible"
        }
        if (id == "AT9_RANGER_B07") return if (ko) "피해 감소 무시 +$number%p" else if (ja) "軽減無視 +$number ポイント" else "Reduction ignored +$number pp"
        val label = when (effectUnit) {
            ArenaProgressionEffectUnit.MP_COST_REDUCTION_PERCENT -> "MP −$number%"
            ArenaProgressionEffectUnit.ACCURACY_PERCENTAGE_POINTS -> if (ko) "명중 +$number%p" else if (ja) "命中 +$number ポイント" else "accuracy +$number pp"
            ArenaProgressionEffectUnit.DAMAGE_PERCENT -> if (ko) "피해 +$number%" else if (ja) "ダメージ +$number%" else "damage +$number%"
            ArenaProgressionEffectUnit.DOT_DAMAGE_PERCENT -> if (ko) "지속 피해 +$number%" else if (ja) "継続ダメージ +$number%" else "periodic damage +$number%"
            ArenaProgressionEffectUnit.SHIELD_DAMAGE_PERCENT -> if (ko) "보호막 파쇄 +$number%" else if (ja) "シールド破砕 +$number%" else "shield damage +$number%"
            ArenaProgressionEffectUnit.DAMAGE_REDUCTION_PERCENT -> if (ko) "피해 감소 +$number%p" else if (ja) "軽減 +$number ポイント" else "reduction +$number pp"
            ArenaProgressionEffectUnit.MAX_HP_HEAL_PERCENT -> if (ko) "회복 +최대 HP $number%" else if (ja) "回復 +最大HP $number%" else "healing +$number% max HP"
            ArenaProgressionEffectUnit.MAX_HP_SHIELD_PERCENT -> if (ko) "보호막 +최대 HP $number%" else if (ja) "シールド +最大HP $number%" else "shield +$number% max HP"
            ArenaProgressionEffectUnit.HEAL_BONUS_PERCENT -> if (ko) "회복량 +$number%" else if (ja) "回復量 +$number%" else "healing +$number%"
            ArenaProgressionEffectUnit.DAMAGE_REDUCTION_IGNORE_PERCENT -> if (ko) "피해 감소 무시 $number%" else if (ja) "軽減を$number%無視" else "ignore $number% reduction"
            ArenaProgressionEffectUnit.SUPPORT_POWER_PERCENT -> if (ko) "연결 효과 +$number%" else if (ja) "連動効果 +$number%" else "linked effect +$number%"
            ArenaProgressionEffectUnit.SUPPORT_POWER_PERCENTAGE_POINTS -> if (ko) "연결 피해 +$number%p" else if (ja) "連動ダメージ +$number ポイント" else "linked damage +$number pp"
            ArenaProgressionEffectUnit.CORE_TRANSFORM -> error("Core handled above")
        }
        return "$target $label"
    }

    fun limitationText(language: String): String {
        if (language == "ko") return limitationKo
        if (isCore) return ArenaGrowthCoreCopy.limitation(this, language)
        val rule = ArenaGrowthRules.rules.getValue(id)
        val timing = if (rule.event != ArenaGrowthEvent.NONE) {
            if (language == "ja") "準備は${windowTurns}ターン、1回限り。再発動まで${cooldownTurns}ターン待機。未使用の準備は重複・延長しません。" else
                "One charge lasts $windowTurns turns; wait $cooldownTurns full turns before retriggering. An unused charge never stacks or refreshes."
        } else if (language == "ja") "条件を満たす処理にだけ適用。期間や使用回数は増えません。" else
            "Applies only while the stated condition holds. Does not extend duration or add uses."
        val boundary = when (effectUnit) {
            ArenaProgressionEffectUnit.MP_COST_REDUCTION_PERCENT -> if (language == "ja") "開始時に費用を確定。合計割引は30%まで、有料技は最低1MP。失敗や戦闘不能でも返還しません。" else "Cost locks at cast start. Combined discount caps at 30%; paid actions cost at least 1 MP. No refund on failure or KO."
            ArenaProgressionEffectUnit.SHIELD_DAMAGE_PERCENT -> if (language == "ja") "シールドへの追加分はHPへ持ち越しません。" else "Extra shield damage never overflows to HP."
            ArenaProgressionEffectUnit.ACCURACY_PERCENTAGE_POINTS -> if (language == "ja") "通常の命中上限、独立回避、分身判定を維持。" else "Normal accuracy caps, independent evasion and mirror checks still apply."
            ArenaProgressionEffectUnit.MAX_HP_HEAL_PERCENT, ArenaProgressionEffectUnit.HEAL_BONUS_PERCENT -> if (language == "ja") "最大HPを超えず、HP0からは回復しません。" else "Cannot exceed maximum HP or heal after HP reaches zero."
            else -> if (language == "ja") "追加行動は発生せず、HP0後は発動しません。" else "Does not create extra actions or activate after HP reaches zero."
        }
        return "$timing $boundary"
    }
    private fun targetText(rule: ArenaGrowthRule, language: String): String {
        val ja = language == "ja"
        return when (rule.target) {
            ArenaGrowthTarget.ANY_ATTACK -> if (ja) "直接攻撃" else "Direct attack"
            ArenaGrowthTarget.BASIC -> if (ja) "通常攻撃" else "Basic attack"
            ArenaGrowthTarget.SKILL -> if (ja) "所持する攻撃スキル" else "Owned attack skill"
            ArenaGrowthTarget.ONE_TURN_ATTACK -> if (ja) "元1ターンの直接攻撃" else "Originally 1-turn direct attack"
            ArenaGrowthTarget.ONE_TURN_SKILL -> if (ja) "元1ターンの攻撃スキル" else "Originally 1-turn attack skill"
            ArenaGrowthTarget.TWO_TURN_SKILL -> if (ja) "元2ターンの攻撃スキル" else "Originally 2-turn attack skill"
            ArenaGrowthTarget.LONG_SKILL -> if (ja) "元2～3ターンの攻撃スキル" else "Originally 2–3-turn attack skill"
            ArenaGrowthTarget.ANY_SUPPORT -> if (ja) "所持する有料補助スキル" else "Owned paid support skill"
            ArenaGrowthTarget.INCOMING_BASIC -> if (ja) "受ける通常攻撃" else "Incoming basic attack"
            ArenaGrowthTarget.INCOMING_SKILL -> if (ja) "受ける攻撃スキル" else "Incoming attack skill"
            else -> if (ja) "連動する補助スキル" else "Linked support skill"
        }
    }
    private fun formatValue(value: Double): String = String.format(Locale.ROOT, "%.3f", value).trimEnd('0').trimEnd('.')
}

object ArenaProgressionCatalog {
    const val WARRIOR_A01 = "AT9_WARRIOR_A01"
    const val WARRIOR_A02 = "AT9_WARRIOR_A02"
    const val ROGUE_A01 = "AT9_ROGUE_A01"
    const val ROGUE_A02 = "AT9_ROGUE_A02"
    const val RANGER_A01 = "AT9_RANGER_A01"
    const val RANGER_A02 = "AT9_RANGER_A02"
    const val MAGE_A01 = "AT9_MAGE_A01"
    const val MAGE_A02 = "AT9_MAGE_A02"
    const val CLERIC_A01 = "AT9_CLERIC_A01"
    const val CLERIC_A02 = "AT9_CLERIC_A02"
    const val PALADIN_A01 = "AT9_PALADIN_A01"
    const val PALADIN_A02 = "AT9_PALADIN_A02"

    val values: List<ArenaProgressionTraitDefinition> = ArenaGrowthCatalogData.values
        .map { definition -> if (definition.id != "AT9_WARRIOR_B06") definition else definition.copy(
            nameKo = "철벽 너머의 파쇄", nameEn = "Breaking beyond the Guard", nameJa = "鉄壁の先の破砕",
            requiredSupportId = "ARENA_SUP_FIGHTER_01", requiredSupportIds = listOf("ARENA_SUP_FIGHTER_01"),
            minHeroLevel = 10,
            conditionKo = "철벽 자세가 해제되지 않고 자연 만료할 때 상대 보호막이 남아 있으면 발동합니다.",
            limitationKo = "철벽 자세의 자연 만료에만 준비됩니다. 3턴 안의 다음 직접 공격 완료 시 명중 여부와 무관하게 소비합니다. 추가 파쇄는 HP로 넘기지 않으며, 준비 중첩·갱신 없이 재발동까지 4턴을 기다립니다.",
        ) }
        .sortedWith(compareBy({ it.heroClass.ordinal }, { it.branch }, { it.isCore }, { it.id }))
    private val byId = values.associateBy { it.id }
    init {
        check(values.size == 144 && byId.size == 144)
        check(values.count { it.isCore } == 18)
        check(HeroClass.entries.all { forClass(it).size == 24 })
        check(values.all { if (it.isCore) it.baseValues.isEmpty() else it.baseValues.size == 5 })
        check(values.filterNot { it.isCore }.all { it.id in ArenaGrowthRules.rules })
    }
    fun initialTrait(heroClass: HeroClass) = forClass(heroClass).first { it.id.endsWith("_A01") }
    fun forClass(heroClass: HeroClass) = values.filter { it.heroClass == heroClass }
    fun find(id: String): ArenaProgressionTraitDefinition? = byId[id]
}
