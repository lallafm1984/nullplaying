package com.nullplaying.engine.arena

import com.nullplaying.engine.SkillCatalog
import com.nullplaying.model.HeroClass
import java.util.Locale
import kotlin.math.roundToInt

const val ARENA_SKILL_TREE_SCHEMA_VERSION = 3
const val ARENA_SKILL_TREE_CATALOG_VERSION = 13
const val ARENA_SKILL_TREE_MAX_RANK = 10

enum class ArenaSkillNodeKind { ATTACK, SUPPORT }

enum class ArenaAttackEffectKind {
    ACCURATE,
    PREPARATION_PUNISH,
    SHIELD_SHATTER,
    MISS_RECOVERY,
    ATTACK_SUPPRESSION,
    MIRROR_PRESSURE,
    MANA_PRESSURE,
    CAST_INTERCEPT,
    /** Retained for replaying V5 resolved attacks, where only support reduction was pierced. */
    MITIGATION_PIERCE,
    /** V6 A08: pierces the target's complete damage-reduction result. */
    TOTAL_MITIGATION_PIERCE,
    FOLLOW_UP_MARK,
    SHIELD_BYPASS,
    HEALING_REDUCTION,
    SPELL_STABILITY,
    STATUS_EXPLOIT,
    CAST_DELAY,
    LOW_HP_DRAIN,
    HEAVY_CHANNEL,
    EXECUTE,
    DISPEL_OR_DRAIN,
    CLASS_COUNTER,
    ULTIMATE,
    COUNTER_BARRIER,
    COUNTER_SUPPORT,
    COUNTER_ILLUSION_HEAL,
    COUNTER_RUSH,
    COUNTER_STATUS,
    COUNTER_PIERCE,
}

data class ArenaAttackEffectParameter(
    val key: String,
    val rankOne: Double,
    val rankTen: Double = rankOne,
    val rankValues: List<Double> = emptyList(),
) {
    init {
        require(key.isNotBlank())
        require(rankOne.isFinite() && rankTen.isFinite())
        require(rankValues.isEmpty() || rankValues.size == ARENA_SKILL_TREE_MAX_RANK)
        require(rankValues.all(Double::isFinite))
    }

    fun value(rank: Int): Double {
        require(rank in 1..ARENA_SKILL_TREE_MAX_RANK)
        if (rankValues.isNotEmpty()) return rankValues[rank - 1]
        return rankOne + (rankTen - rankOne) * (rank - 1) / (ARENA_SKILL_TREE_MAX_RANK - 1)
    }
}

data class ArenaAttackEffectSpec(
    val kind: ArenaAttackEffectKind,
    val parameters: List<ArenaAttackEffectParameter> = emptyList(),
    val durationTurns: Int = 0,
    val charges: Int = 0,
    val textKo: String,
    val textEn: String,
    val textJa: String,
) {
    init {
        require(durationTurns >= 0 && charges >= 0)
        require(parameters.map { it.key }.distinct().size == parameters.size)
        require(textKo.isNotBlank() && textEn.isNotBlank() && textJa.isNotBlank())
    }

    fun parameter(key: String, rank: Int): Double =
        requireNotNull(parameters.firstOrNull { it.key == key }) { "Unknown effect parameter: $key" }
            .value(rank)

    fun text(language: String, rank: Int): String {
        require(rank in 1..ARENA_SKILL_TREE_MAX_RANK)
        val languageKey = language.substringBefore('-').lowercase(Locale.ROOT)
        var rendered = when (languageKey) {
            "en" -> textEn
            "ja" -> textJa
            else -> textKo
        }
        parameters.forEach { parameter ->
            val value = parameter.value(rank)
            val formatted = formatEffectNumber(parameter.key, value)
            if (languageKey == "en") {
                val countToken = Regex(
                    // Android's ICU regex parser requires the closing literal brace to be
                    // escaped even though the desktop JVM accepts the unescaped form.
                    "\\{${Regex.escape(parameter.key)}:([^|{}]+)\\|([^{}]+)\\}",
                )
                rendered = countToken.replace(rendered) { match ->
                    "$formatted ${if (value == 1.0) match.groupValues[1] else match.groupValues[2]}"
                }
            }
            rendered = rendered.replace("{${parameter.key}}", formatted)
        }
        return rendered
    }
}

data class ArenaAttackClassOverride(
    val heroClass: HeroClass,
    val effect: ArenaAttackEffectSpec,
)

data class ArenaAttackProfile(
    val slot: Int,
    /** Zero resolves in the selected turn; positive values are complete preparation turns. */
    val prepareTurns: Int,
    val mpCost: Int,
    /** Turns after completion before the action may be selected again. */
    val cooldownTurns: Int,
    val baseDamagePercent: Int,
    val effect: ArenaAttackEffectSpec,
    val maxHpDamageCapPercent: Int,
    val damagePercentRanks: List<Int> = emptyList(),
    val oncePerBattle: Boolean = false,
    val earliestTurn: Int = 1,
    val classOverrides: Map<HeroClass, ArenaAttackClassOverride> = emptyMap(),
    val classDamagePercentRanks: Map<HeroClass, List<Int>> = emptyMap(),
) {
    init {
        require(slot in 1..20)
        require(prepareTurns in 0..3 && mpCost > 0 && cooldownTurns >= 0)
        require(baseDamagePercent > 0 && maxHpDamageCapPercent in 1..100)
        require(damagePercentRanks.isEmpty() || damagePercentRanks.size == ARENA_SKILL_TREE_MAX_RANK)
        require(damagePercentRanks.all { it > 0 })
        require(damagePercentRanks.zipWithNext().all { (before, after) -> before <= after })
        require(earliestTurn >= 1)
        require(classOverrides.all { (heroClass, override) -> heroClass == override.heroClass })
        require(classDamagePercentRanks.values.all { ranks ->
            ranks.size == ARENA_SKILL_TREE_MAX_RANK && ranks.all { it > 0 } &&
                ranks.zipWithNext().all { (before, after) -> before <= after }
        })
    }

    val profileId: String get() = "A${slot.toString().padStart(2, '0')}"
    val rankOneDamagePercent: Int get() = damagePercent(1)
    val rankTenDamagePercent: Int get() = damagePercent(10)
    val damagePercentByRank: List<Int> get() = (1..ARENA_SKILL_TREE_MAX_RANK).map(::damagePercent)

    fun damagePercent(rank: Int): Int {
        require(rank in 1..ARENA_SKILL_TREE_MAX_RANK)
        if (damagePercentRanks.isNotEmpty()) return damagePercentRanks[rank - 1]
        val adeptMultiplier = if (rank >= 5) ADEPT_DAMAGE_MULTIPLIER else 1.0
        return (baseDamagePercent * DAMAGE_RANK_MULTIPLIERS[rank - 1] * adeptMultiplier).roundToInt()
    }

    fun damagePercent(heroClass: HeroClass, rank: Int): Int =
        classDamagePercentRanks[heroClass]?.get(rank - 1) ?: damagePercent(rank)

    /** Higher ranks gain substantially more power and therefore require progressively more MP. */
    fun effectiveMpCost(rank: Int): Int {
        require(rank in 1..ARENA_SKILL_TREE_MAX_RANK)
        return arenaRankedMpCost(mpCost, rank)
    }

    /** Reusable attacks may shorten at R10, but always leave at least one cooldown turn. */
    fun effectiveCooldownTurns(rank: Int): Int {
        require(rank in 1..ARENA_SKILL_TREE_MAX_RANK)
        if (oncePerBattle || rank < ARENA_SKILL_TREE_MAX_RANK || slot in RANK_TEN_MASTERY_SLOTS) {
            return cooldownTurns
        }
        return (cooldownTurns - 1).coerceAtLeast(1)
    }

    /** The non-lethal A20 safety band remains stable at every rank. */
    fun effectiveMaxHpDamageCapPercent(rank: Int): Int {
        require(rank in 1..ARENA_SKILL_TREE_MAX_RANK)
        return maxHpDamageCapPercent
    }

    /** Exact R5/R10 copy for structured milestone UI. Other ranks have no milestone. */
    fun milestoneText(language: String, milestoneRank: Int): String? {
        require(milestoneRank in 1..ARENA_SKILL_TREE_MAX_RANK)
        val languageKey = language.substringBefore('-').lowercase(Locale.ROOT)
        return when (milestoneRank) {
            5 -> when (languageKey) {
                "en" -> "Skill damage +12%"
                "ja" -> "スキルダメージ+12%"
                else -> "스킬 피해 +12%"
            }
            ARENA_SKILL_TREE_MAX_RANK -> rankTenMilestoneText(languageKey)
            else -> null
        }
    }

    private fun rankTenMilestoneText(languageKey: String): String {
        val cooldown = if (!oncePerBattle && effectiveCooldownTurns(10) < effectiveCooldownTurns(9)) when (languageKey) {
            "en" -> "Cooldown reduced by 1 turn"
            "ja" -> "再使用待機が1ターン短縮"
            else -> "재사용 대기 1턴 감소"
        } else null
        val mastery = when (slot) {
            1 -> effect.text(languageKey, ARENA_SKILL_TREE_MAX_RANK)
            2 -> when (languageKey) {
                "en" -> "Hit a preparing enemy to inflict Bleed\n18% attack damage each turn for 2 turns"
                "ja" -> "準備中の相手に命中すると出血\n2ターン、毎ターン攻撃力の18%ダメージ"
                else -> "준비 중인 상대에게 적중 시 출혈\n2턴 동안 매 턴 공격력의 18% 피해"
            }
            3 -> when (languageKey) {
                "en" -> "On shield break, the target's next damage taken within 4 turns +15%"
                "ja" -> "シールド破壊時、4ターン以内に相手が受ける次のダメージ+15%"
                else -> "보호막 파괴 시 상대가 4턴 안에 받는 다음 피해 +15%"
            }
            5 -> when (languageKey) {
                "en" -> "One additional attack is weakened"
                "ja" -> "弱化する攻撃回数+1回"
                else -> "약화 적용 횟수 +1회"
            }
            8 -> when (languageKey) {
                "en" -> "Ignore 55% of the target's damage reduction"
                "ja" -> "相手のダメージ軽減効果を55%無視"
                else -> "상대의 피해 감소 효과 55% 무시"
            }
            9 -> when (languageKey) {
                "en" -> "One additional attack is empowered"
                "ja" -> "強化する攻撃回数+1回"
                else -> "강화 적용 횟수 +1회"
            }
            10 -> when (languageKey) {
                "en" -> "55% of damage ignores the target's shield"
                "ja" -> "ダメージの55%が相手のシールドを無視"
                else -> "피해 55%가 상대 보호막 무시"
            }
            11 -> when (languageKey) {
                "en" -> "Damage the target's HP to inflict Bleed\n12% attack damage each turn for 3 turns"
                "ja" -> "相手HPにダメージを与えると出血\n3ターン、毎ターン攻撃力の12%ダメージ"
                else -> "상대 HP에 피해 시 출혈\n3턴 동안 매 턴 공격력의 12% 피해"
            }
            12 -> when (languageKey) {
                "en" -> "Base accuracy is at least 100%\nEvasion and images still apply"
                "ja" -> "基本命中率は最低100%\n回避と分身効果は別に適用"
                else -> "기본 명중률 최소 100%\n회피·분신 효과는 별도 적용"
            }
            13 -> when (languageKey) {
                "en" -> "Damage +60% against a target with a harmful status\nOtherwise, its next damage taken within 4 turns +20%"
                "ja" -> "有害状態の相手へのダメージ+60%\n状態がなければ4ターン以内に受ける次のダメージ+20%"
                else -> "해로운 상태의 상대에게 피해 +60%\n상태가 없으면 상대가 4턴 안에 받는 다음 피해 +20%"
            }
            14 -> when (languageKey) {
                "en" -> "Successful control delays preparation by 2 turns total"
                "ja" -> "制御成功時の準備遅延が合計2ターン"
                else -> "제어 성공 시 준비 지연 총 2턴"
            }
            15 -> when (languageKey) {
                "en" -> "Applies at 50% HP or less\nHealing up to 6% max HP"
                "ja" -> "HP50%以下で適用\n回復量は最大HPの6%まで"
                else -> "HP 50% 이하에서 적용\n최대 HP 6%까지 회복"
            }
            16 -> when (languageKey) {
                "en" -> "Damage the target's HP to inflict Bleed\n20% attack damage each turn for 3 turns"
                "ja" -> "相手HPにダメージを与えると出血\n3ターン、毎ターン攻撃力の20%ダメージ"
                else -> "상대 HP에 피해 시 출혈\n3턴 동안 매 턴 공격력의 20% 피해"
            }
            17 -> when (languageKey) {
                "en" -> "Execute condition expands to 40% HP or less"
                "ja" -> "処刑条件がHP40%以下に拡大"
                else -> "처형 조건이 HP 40% 이하로 확대"
            }
            18 -> when (languageKey) {
                "en" -> "Remove up to 2 enemy buffs"
                "ja" -> "敵の強化を最大2個解除"
                else -> "상대 강화 최대 2개 제거"
            }
            20 -> when (languageKey) {
                "en" -> "Ignore 40% of the target's damage reduction"
                "ja" -> "相手のダメージ軽減効果を40%無視"
                else -> "상대의 피해 감소 효과 40% 무시"
            }
            else -> null
        }
        return listOfNotNull(cooldown, mastery).joinToString("\n")
    }

    fun effectFor(heroClass: HeroClass): ArenaAttackEffectSpec =
        classOverrides[heroClass]?.effect ?: effect

    fun effectText(language: String, rank: Int, heroClass: HeroClass): String =
        effectFor(heroClass).text(language, rank)

    fun compactText(language: String, rank: Int, heroClass: HeroClass): String {
        val damageLabel = when (language.substringBefore('-').lowercase(Locale.ROOT)) {
            "en" -> "Damage"
            "ja" -> "ダメージ"
            else -> "피해"
        }
        return "$damageLabel ${damagePercent(heroClass, rank)}%\n${effectText(language, rank, heroClass)}"
    }

    fun currentToNextText(language: String, rank: Int, heroClass: HeroClass): String {
        require(rank in 1..ARENA_SKILL_TREE_MAX_RANK)
        if (rank == ARENA_SKILL_TREE_MAX_RANK) return compactText(language, rank, heroClass)
        val damageLabel = when (language.substringBefore('-').lowercase(Locale.ROOT)) {
            "en" -> "Damage"
            "ja" -> "ダメージ"
            else -> "피해"
        }
        return "$damageLabel ${damagePercent(heroClass, rank)}→${damagePercent(heroClass, rank + 1)}%\n" +
            "${effectText(language, rank, heroClass)}\n→ ${effectText(language, rank + 1, heroClass)}"
    }

    companion object {
        private const val ADEPT_DAMAGE_MULTIPLIER = 1.12
        private val RANK_TEN_MASTERY_SLOTS =
            setOf(2, 3, 5, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 20)
        val DAMAGE_RANK_MULTIPLIERS: List<Double> =
            listOf(1.00, 1.12, 1.25, 1.39, 1.54, 1.70, 1.87, 2.05, 2.24, 2.45)
    }
}

data class ArenaSkillTreeNodeDefinition(
    val id: String,
    val heroClass: HeroClass,
    val kind: ArenaSkillNodeKind,
    val slotKey: String,
    val row: Int,
    val column: Int,
    /** Any one direct parent at this rank satisfies the prerequisite. */
    val parentAnyOf: Set<String>,
    val minParentRank: Int,
    /** Points spent strictly above this row before rank one may be learned. */
    val minimumSpentPoints: Int,
    val maxRank: Int,
    val sourceAttackId: String? = null,
    val supportId: String? = null,
    val nameKo: String,
    val nameEn: String,
    val nameJa: String,
    val attackProfile: ArenaAttackProfile? = null,
) {
    val isRoot: Boolean get() = parentAnyOf.isEmpty()

    init {
        require(id.isNotBlank() && slotKey.matches(Regex("[AS]\\d{2}")))
        require(row in 0..9 && column in 0..2)
        require(minParentRank in 0..ARENA_SKILL_TREE_MAX_RANK)
        require(minimumSpentPoints >= 0 && maxRank == ARENA_SKILL_TREE_MAX_RANK)
        require(nameKo.isNotBlank() && nameEn.isNotBlank() && nameJa.isNotBlank())
        when (kind) {
            ArenaSkillNodeKind.ATTACK -> {
                require(sourceAttackId == id && supportId == null && attackProfile != null)
            }
            ArenaSkillNodeKind.SUPPORT -> {
                require(supportId == id && sourceAttackId == null && attackProfile == null)
            }
        }
        require((isRoot && minParentRank == 0) || (!isRoot && minParentRank == 3))
    }
}

object ArenaSkillTreeCatalog {
    const val schemaVersion: Int = ARENA_SKILL_TREE_SCHEMA_VERSION
    const val catalogVersion: Int = ARENA_SKILL_TREE_CATALOG_VERSION

    val rowSpendThresholds: List<Int> = listOf(0, 3, 7, 12, 18, 25, 33, 42, 52, 63)
    val attackProfiles: List<ArenaAttackProfile> = createAttackProfiles()
    private val attackProfileBySlot = attackProfiles.associateBy(ArenaAttackProfile::slot)
    val values: List<ArenaSkillTreeNodeDefinition> = HeroClass.entries.flatMap(::buildClassTree)
    private val byId = values.associateBy(ArenaSkillTreeNodeDefinition::id)
    private val byClass = values.groupBy(ArenaSkillTreeNodeDefinition::heroClass)

    init {
        require(rowSpendThresholds.size == 10 && rowSpendThresholds.first() == 0)
        require(rowSpendThresholds.zipWithNext().all { (before, after) -> before < after })
        require(attackProfiles.size == 20 && attackProfileBySlot.keys == (1..20).toSet())
        require(ArenaAttackProfile.DAMAGE_RANK_MULTIPLIERS.size == ARENA_SKILL_TREE_MAX_RANK)
        require(values.size == HeroClass.entries.size * 30 && byId.size == values.size)
        HeroClass.entries.forEach { heroClass ->
            val nodes = forClass(heroClass)
            require(nodes.size == 30)
            require(nodes.count { it.kind == ArenaSkillNodeKind.ATTACK } == 20)
            require(nodes.count { it.kind == ArenaSkillNodeKind.SUPPORT } == 10)
            require(nodes.groupingBy { it.row }.eachCount() == (0..9).associateWith { 3 })
            require(nodes.map { it.row to it.column }.distinct().size == 30)
            require(nodes.filter(ArenaSkillTreeNodeDefinition::isRoot).map { it.slotKey }.toSet() ==
                setOf("A01", "A02", "A03"))
            require(nodes.all { it.maxRank == ARENA_SKILL_TREE_MAX_RANK })
            require(nodes.filter { it.kind == ArenaSkillNodeKind.ATTACK }.map { it.id }.toSet() ==
                SkillCatalog.forClass(heroClass).map { it.catalogId }.toSet())
            require(nodes.filter { it.kind == ArenaSkillNodeKind.SUPPORT }.map { it.id }.toSet() ==
                ArenaSupportCatalog.forClass(heroClass).map { it.id }.toSet())
            nodes.forEach { node ->
                require(node.minimumSpentPoints == rowSpendThresholds[node.row])
                node.parentAnyOf.forEach { parentId ->
                    val parent = requireNotNull(byId[parentId])
                    require(parent.heroClass == heroClass &&
                        (parent.row < node.row || parent.row == node.row && parent.column < node.column))
                }
            }
            val reached = nodes.filter(ArenaSkillTreeNodeDefinition::isRoot).mapTo(mutableSetOf()) { it.id }
            while (true) {
                val before = reached.size
                nodes.filter { it.id !in reached && it.parentAnyOf.any(reached::contains) }
                    .forEach { reached += it.id }
                if (reached.size == before) break
            }
            require(reached.size == nodes.size)
        }
    }

    fun find(id: String): ArenaSkillTreeNodeDefinition? = byId[id]

    fun forClass(heroClass: HeroClass): List<ArenaSkillTreeNodeDefinition> =
        byClass.getValue(heroClass).sortedWith(compareBy(ArenaSkillTreeNodeDefinition::row, ArenaSkillTreeNodeDefinition::column))

    fun attackProfile(slot: Int): ArenaAttackProfile = requireNotNull(attackProfileBySlot[slot])

    fun rowThreshold(row: Int): Int {
        require(row in rowSpendThresholds.indices)
        return rowSpendThresholds[row]
    }

    fun effectiveSupport(definition: ArenaSupportDefinition, rank: Int): ArenaSupportDefinition {
        require(rank in 1..ARENA_SKILL_TREE_MAX_RANK)
        require(ArenaSupportCatalog.find(definition.id) == definition) { "Support must come from ArenaSupportCatalog" }
        val multiplier = SUPPORT_RANK_MULTIPLIERS[rank - 1]
        val count = if (rank >= 7) 2 else 1
        var magnitude = definition.magnitude * multiplier
        var secondary = definition.secondary * multiplier
        var charges = definition.charges + if (rank == 10 && definition.charges > 0) 1 else 0

        when (definition.kind) {
            in COUNT_SUPPORT_KINDS -> {
                magnitude = count.toDouble()
                // STATUS_GUARD spends one charge per blocked status. JUDGMENT and
                // SEAL instead remove [magnitude] effects in one hit and then end.
                charges = if (definition.kind == ArenaSupportKind.STATUS_GUARD) count
                    else definition.charges
            }
            ArenaSupportKind.TAUNT, ArenaSupportKind.TRAP, ArenaSupportKind.SLEEP -> {
                magnitude = 1.0
                secondary = CONTROL_ACCURACY_BONUS[rank - 1]
                charges = definition.charges
            }
            ArenaSupportKind.MIRROR -> {
                magnitude = definition.magnitude
                secondary = definition.secondary
                charges = MIRROR_IMAGES[rank - 1]
            }
            ArenaSupportKind.DAMAGE_CAP -> {
                magnitude = DAMAGE_CAP_PERCENT[rank - 1]
                secondary = definition.secondary
            }
            ArenaSupportKind.POISON_ACCELERATE -> {
                magnitude = 1.0
                secondary = POISON_ACCELERATION_BONUS[rank - 1]
                charges = definition.charges
            }
            ArenaSupportKind.STABILIZE -> {
                magnitude = STABILIZE_ACCURACY[rank - 1]
                secondary = definition.secondary
            }
            ArenaSupportKind.RESTRAINT -> {
                // The secondary number is a self-damage penalty, so higher ranks reduce it.
                secondary = RESTRAINT_DAMAGE_PENALTY[rank - 1]
            }
            ArenaSupportKind.POISON_COAT,
            ArenaSupportKind.BURN_PREP,
            ArenaSupportKind.EMBER,
            ArenaSupportKind.HEAL_BLOCK_PREP,
            -> {
                // These definitions repeat their fixed effect duration in secondary for copy/UI.
                secondary = definition.secondary
            }
            else -> Unit
        }

        magnitude = capSupportMagnitude(definition.kind, magnitude)
        secondary = capSupportSecondary(definition.kind, secondary)
        val rankTenExtraUse = rank == ARENA_SKILL_TREE_MAX_RANK && when (definition.kind) {
            ArenaSupportKind.MIRROR -> true
            in COUNT_SUPPORT_KINDS -> false
            ArenaSupportKind.TAUNT, ArenaSupportKind.TRAP, ArenaSupportKind.SLEEP -> false
            else -> definition.charges > 0
        }
        val cooldownReduction = when {
            rank < 5 -> 0
            rankTenExtraUse -> 1
            rank == ARENA_SKILL_TREE_MAX_RANK -> 2
            else -> 1
        }
        val cooldown = if (definition.oncePerBattle) definition.cooldownTurns else
            (definition.cooldownTurns - cooldownReduction).coerceAtLeast(2)
        val cast = if (rank >= 5 && definition.oncePerBattle && definition.castTurns > 1) {
            definition.castTurns - 1
        } else {
            definition.castTurns
        }
        return definition.copy(
            mp = arenaRankedMpCost(definition.mp, rank),
            castTurns = cast,
            cooldownTurns = cooldown,
            charges = charges.coerceAtMost(5),
            magnitude = roundOneDecimal(magnitude),
            secondary = roundOneDecimal(secondary),
        )
    }

    /** Exact additional R5/R10 bonus copy; ordinary per-rank MP cost growth is shown in timing. */
    fun supportMilestoneText(
        definition: ArenaSupportDefinition,
        language: String,
        milestoneRank: Int,
    ): String? {
        require(milestoneRank in 1..ARENA_SKILL_TREE_MAX_RANK)
        if (milestoneRank != 5 && milestoneRank != ARENA_SKILL_TREE_MAX_RANK) return null
        val effective = effectiveSupport(definition, milestoneRank)
        val previous = effectiveSupport(definition, milestoneRank - 1)
        val castReduction = previous.castTurns - effective.castTurns
        val cooldownReduction = previous.cooldownTurns - effective.cooldownTurns
        val chargeIncrease = if (milestoneRank == ARENA_SKILL_TREE_MAX_RANK) {
            effective.charges - effectiveSupport(definition, milestoneRank - 1).charges
        } else {
            0
        }
        val languageKey = language.substringBefore('-').lowercase(Locale.ROOT)
        val parts = when (languageKey) {
            "en" -> buildList {
                if (castReduction > 0) add("Cast time reduced by $castReduction turn")
                if (cooldownReduction > 0) add(
                    if (milestoneRank == ARENA_SKILL_TREE_MAX_RANK) {
                        "Cooldown reduced by $cooldownReduction additional turn"
                    } else {
                        "Cooldown reduced by $cooldownReduction turn"
                    },
                )
                if (chargeIncrease > 0) add("$chargeIncrease additional use")
            }
            "ja" -> buildList {
                if (castReduction > 0) add("詠唱時間が${castReduction}ターン短縮")
                if (cooldownReduction > 0) add(
                    if (milestoneRank == ARENA_SKILL_TREE_MAX_RANK) "再使用待機がさらに${cooldownReduction}ターン短縮"
                    else "再使用待機が${cooldownReduction}ターン短縮",
                )
                if (chargeIncrease > 0) add("使用回数が${chargeIncrease}回増加")
            }
            else -> buildList {
                if (castReduction > 0) add("시전 시간 ${castReduction}턴 감소")
                if (cooldownReduction > 0) add(
                    if (milestoneRank == ARENA_SKILL_TREE_MAX_RANK) "재사용 대기 ${cooldownReduction}턴 추가 감소"
                    else "재사용 대기 ${cooldownReduction}턴 감소",
                )
                if (chargeIncrease > 0) add("사용 횟수 ${chargeIncrease}회 증가")
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun buildClassTree(heroClass: HeroClass): List<ArenaSkillTreeNodeDefinition> {
        val attacks = SkillCatalog.forClass(heroClass)
        val supports = ArenaSupportCatalog.forClass(heroClass)
        require(attacks.size == 20 && supports.size == 10)
        val keyToId = buildMap {
            attacks.forEachIndexed { index, attack -> put(attackKey(index + 1), attack.catalogId) }
            supports.forEachIndexed { index, support -> put(supportKey(index + 1), support.id) }
        }
        return treeLayout().map { slot ->
            val id = keyToId.getValue(slot.key)
            val parents = slot.parents.mapTo(linkedSetOf()) { keyToId.getValue(it) }
            if (slot.key.startsWith("A")) {
                val index = slot.key.drop(1).toInt() - 1
                val attack = attacks[index]
                val localizedName = ArenaAttackNameLocalization.get(attack.name)
                ArenaSkillTreeNodeDefinition(
                    id = id,
                    heroClass = heroClass,
                    kind = ArenaSkillNodeKind.ATTACK,
                    slotKey = slot.key,
                    row = slot.row,
                    column = slot.column,
                    parentAnyOf = parents,
                    minParentRank = if (parents.isEmpty()) 0 else 3,
                    minimumSpentPoints = rowSpendThresholds[slot.row],
                    maxRank = ARENA_SKILL_TREE_MAX_RANK,
                    sourceAttackId = id,
                    nameKo = attack.name,
                    nameEn = localizedName.english,
                    nameJa = localizedName.japanese,
                    attackProfile = attackProfile(index + 1),
                )
            } else {
                val index = slot.key.drop(1).toInt() - 1
                val support = supports[index]
                ArenaSkillTreeNodeDefinition(
                    id = id,
                    heroClass = heroClass,
                    kind = ArenaSkillNodeKind.SUPPORT,
                    slotKey = slot.key,
                    row = slot.row,
                    column = slot.column,
                    parentAnyOf = parents,
                    minParentRank = 3,
                    minimumSpentPoints = rowSpendThresholds[slot.row],
                    maxRank = ARENA_SKILL_TREE_MAX_RANK,
                    supportId = id,
                    nameKo = support.name("ko"),
                    nameEn = support.name("en"),
                    nameJa = support.name("ja"),
                )
            }
        }
    }

    private fun createAttackProfiles(): List<ArenaAttackProfile> {
        fun parameter(key: String, one: Double, ten: Double = one, values: List<Double> = emptyList()) =
            ArenaAttackEffectParameter(key, one, ten, values)
        fun rankTen(value: Double): List<Double> = List(ARENA_SKILL_TREE_MAX_RANK - 1) { 0.0 } + value
        fun effect(
            kind: ArenaAttackEffectKind,
            ko: String,
            en: String,
            ja: String,
            parameters: List<ArenaAttackEffectParameter> = emptyList(),
            duration: Int = 0,
            charges: Int = 0,
        ) = ArenaAttackEffectSpec(kind, parameters, duration, charges, ko, en, ja)
        fun profile(
            slot: Int,
            prepare: Int,
            mp: Int,
            cooldown: Int,
            damage: Int,
            effect: ArenaAttackEffectSpec,
            damageRanks: List<Int> = emptyList(),
            once: Boolean = false,
            earliest: Int = 1,
            overrides: Map<HeroClass, ArenaAttackClassOverride> = emptyMap(),
            damageOverrides: Map<HeroClass, List<Int>> = emptyMap(),
        ) = ArenaAttackProfile(
            slot = slot,
            prepareTurns = prepare,
            mpCost = mp,
            cooldownTurns = cooldown,
            baseDamagePercent = damage,
            damagePercentRanks = damageRanks,
            effect = effect,
            maxHpDamageCapPercent = listOf(25, 35, 45, 55)[prepare],
            oncePerBattle = once,
            earliestTurn = earliest,
            classOverrides = overrides,
            classDamagePercentRanks = damageOverrides,
        )

        val a19Overrides = listOf(
            ArenaAttackClassOverride(HeroClass.WARRIOR, effect(
                ArenaAttackEffectKind.COUNTER_BARRIER,
                "상대 보호막 피해의 {shatter}%만큼 보호막 추가 피해\n보호막이 없으면 상대의 피해 감소 효과 {ignore}% 무시",
                "Deal {shatter}% of shield damage again as bonus shield damage\nIf the target has no shield, ignore {ignore}% of its damage reduction",
                "相手のシールドダメージの{shatter}%分を追加\nシールドがなければ相手のダメージ軽減効果を{ignore}%無視",
                listOf(parameter("shatter", 100.0, 200.0), parameter("ignore", 20.0, 40.0)),
            )),
            ArenaAttackClassOverride(HeroClass.ROGUE, effect(
                ArenaAttackEffectKind.COUNTER_SUPPORT,
                "최근 2턴 안에 보조 스킬을 쓴 상대에게 피해 +{bonus}%\n분신 효과로 막을 수 없음",
                "Damage +{bonus}% against a target that used a support skill within 2 turns\nCannot be blocked by images",
                "2ターン以内に補助スキルを使った相手へのダメージ+{bonus}%\n分身効果では防げない",
                listOf(parameter("bonus", 15.0, 45.0), parameter("window", 2.0)), duration = 2,
            )),
            ArenaAttackClassOverride(HeroClass.RANGER, effect(
                ArenaAttackEffectKind.COUNTER_ILLUSION_HEAL,
                "적중 시 은신·분신 효과 1개 제거\n제거할 효과가 없으면 최근 2턴 안에 회복한 상대에게 피해 +{bonus}%",
                "On hit, remove one Stealth or image effect\nIf none is removed, damage +{bonus}% against a target that healed within 2 turns",
                "命中時、隠密・分身効果を1つ解除\n解除できなければ2ターン以内に回復した相手へのダメージ+{bonus}%",
                listOf(parameter("remove", 1.0), parameter("bonus", 10.0, 35.0), parameter("window", 2.0)),
                duration = 2,
            )),
            ArenaAttackClassOverride(HeroClass.MAGE, effect(
                ArenaAttackEffectKind.COUNTER_RUSH,
                "직전 턴에 즉시 공격 피해를 받았다면 준비 1턴 단축\n상대 HP에 피해 시 2턴 동안 매 턴 공격력의 {burn}% 화상 피해",
                "If damaged by an instant attack last turn, preparation -1 turn\nOn HP damage, Burn deals {burn}% attack damage each turn for 2 turns",
                "直前のターンに即時攻撃のダメージを受けていれば準備-1ターン\n相手HPにダメージを与えると2ターン、毎ターン攻撃力の{burn}%火傷ダメージ",
                listOf(parameter("prepareReduction", 1.0), parameter("burn", 20.0, 55.0)),
                duration = 2, charges = 1,
            )),
            ArenaAttackClassOverride(HeroClass.CLERIC, effect(
                ArenaAttackEffectKind.COUNTER_STATUS,
                "공격 준비 시작 시 해로운 상태 1개 제거\n제거하면 피해 +{bonus}%",
                "When preparation starts, remove one harmful status\nIf removed, damage +{bonus}%",
                "攻撃準備の開始時に有害状態を1つ解除\n解除するとダメージ+{bonus}%",
                listOf(parameter("cleanse", 1.0), parameter("bonus", 10.0, 30.0)),
            )),
            ArenaAttackClassOverride(HeroClass.PALADIN, effect(
                ArenaAttackEffectKind.COUNTER_PIERCE,
                "최근 2턴 안에 보호막 관통 피해를 받고 생존했다면 피해 +{bonus}%\n2턴 동안 상대 관통 효과 -{pierceReduction}%",
                "After surviving shield-piercing damage within 2 turns, damage +{bonus}%\nEnemy piercing -{pierceReduction}% for 2 turns",
                "2ターン以内にシールド貫通ダメージを受けて生存した場合、ダメージ+{bonus}%\n2ターン、相手の貫通効果-{pierceReduction}%",
                listOf(parameter("bonus", 15.0, 45.0), parameter("pierceReduction", 10.0, 30.0)),
                duration = 2,
            )),
        ).associateBy(ArenaAttackClassOverride::heroClass)

        return listOf(
            profile(1, 0, 4, 1, 105, effect(ArenaAttackEffectKind.ACCURATE,
                "명중률 +{accuracy}%", "Accuracy +{accuracy}%", "命中率+{accuracy}%",
                listOf(parameter("accuracy", 8.0, 20.0))),
                damageRanks = listOf(105, 112, 120, 127, 147, 157, 168, 180, 192, 206),
                damageOverrides = mapOf(
                    HeroClass.WARRIOR to listOf(106, 113, 121, 128, 148, 158, 169, 181, 193, 212),
                    HeroClass.CLERIC to listOf(105, 112, 120, 127, 147, 157, 168, 180, 192, 204),
                    HeroClass.PALADIN to listOf(105, 112, 120, 127, 147, 157, 168, 180, 192, 212),
                )),
            profile(2, 0, 6, 2, 115, effect(ArenaAttackEffectKind.PREPARATION_PUNISH,
                "준비 중인 상대에게 피해 +{bonus}%", "Damage +{bonus}% against a preparing target", "準備中の相手へのダメージ+{bonus}%",
                listOf(
                    parameter("bonus", 15.0, 45.0),
                    parameter("masterBleedTick", 0.0, values = rankTen(18.0)),
                    parameter("masterBleedTurns", 0.0, values = rankTen(2.0)),
                )),
                damageRanks = listOf(115, 125, 136, 148, 176, 191, 206, 223, 241, 261),
                damageOverrides = mapOf(
                    HeroClass.WARRIOR to listOf(116, 126, 137, 149, 177, 192, 207, 224, 242, 282),
                    HeroClass.RANGER to listOf(115, 125, 136, 148, 176, 191, 206, 223, 241, 249),
                    HeroClass.MAGE to listOf(115, 125, 136, 148, 176, 191, 206, 223, 241, 268),
                    HeroClass.PALADIN to listOf(115, 125, 136, 148, 176, 191, 206, 223, 241, 269),
                )),
            profile(3, 1, 9, 2, 210, effect(ArenaAttackEffectKind.SHIELD_SHATTER,
                "상대 보호막 피해의 {shatter}%만큼 보호막 추가 피해", "Deal {shatter}% of shield damage again as bonus shield damage", "相手のシールドダメージの{shatter}%分を追加",
                listOf(
                    parameter("shatter", 50.0, 150.0),
                    parameter("masterRupture", 0.0, values = rankTen(15.0)),
                    parameter("masterRuptureTurns", 0.0, values = rankTen(4.0)),
                )),
                damageRanks = listOf(210, 227, 247, 267, 316, 343, 371, 400, 431, 466)),
            profile(4, 0, 7, 2, 85, effect(ArenaAttackEffectKind.MISS_RECOVERY,
                "직전 공격이 빗나갔다면 피해 +{bonus}%", "Damage +{bonus}% if the previous attack missed", "直前の攻撃が外れていればダメージ+{bonus}%",
                listOf(parameter("bonus", 15.0, 50.0)))),
            profile(5, 1, 11, 3, 200, effect(ArenaAttackEffectKind.ATTACK_SUPPRESSION,
                "상대 HP에 피해 시\n상대의 다음 공격 피해 -{reduction}%\n4턴 안에 최대 {uses}회", "On HP damage\nTarget's next attack damage -{reduction}%\nUp to {uses:time|times} within 4 turns", "相手HPにダメージを与えると\n相手の次の攻撃ダメージ-{reduction}%\n4ターン以内に最大{uses}回",
                listOf(
                    parameter("reduction", 8.0, 25.0),
                    parameter("masterSuppressionCharges", 1.0, values = List(9) { 1.0 } + 2.0),
                    parameter("uses", 1.0, values = List(9) { 1.0 } + 2.0),
                ), charges = 1)),
            profile(6, 1, 12, 3, 225, effect(ArenaAttackEffectKind.MANA_PRESSURE,
                "적중 시 상대 MP -{drain}", "On hit, target MP -{drain}", "命中時、相手MP-{drain}",
                listOf(parameter("drain", 4.0, 12.0)))),
            profile(7, 0, 8, 3, 105, effect(ArenaAttackEffectKind.CAST_INTERCEPT,
                "준비 중인 상대에게 피해 +{bonus}%\n명중률 +{accuracy}%",
                "Against a preparing target, damage +{bonus}%\nAccuracy +{accuracy}%",
                "準備中の相手へのダメージ+{bonus}%\n命中率+{accuracy}%",
                listOf(parameter("bonus", 20.0, 60.0), parameter("accuracy", 10.0, 30.0)))),
            profile(8, 2, 17, 4, 340, effect(ArenaAttackEffectKind.TOTAL_MITIGATION_PIERCE,
                "상대의 피해 감소 효과 {ignore}% 무시", "Ignore {ignore}% of the target's damage reduction", "相手のダメージ軽減効果を{ignore}%無視",
                listOf(parameter("ignore", 15.0, 55.0)))),
            profile(9, 1, 13, 3, 240, effect(ArenaAttackEffectKind.FOLLOW_UP_MARK,
                "4턴 안의 다음 공격 {uses}회 강화\n명중률 +{accuracy}%\n피해 +{bonus}%", "Empower the next {uses:attack|attacks} within 4 turns\nAccuracy +{accuracy}%\nDamage +{bonus}%", "4ターン以内の次の攻撃{uses}回を強化\n命中率+{accuracy}%\nダメージ+{bonus}%",
                listOf(
                    parameter("accuracy", 5.0, 18.0),
                    parameter("bonus", 8.0, 25.0),
                    parameter("masterFollowUpCharges", 1.0, values = List(9) { 1.0 } + 2.0),
                    parameter("uses", 1.0, values = List(9) { 1.0 } + 2.0),
                ), charges = 1)),
            profile(10, 2, 19, 5, 390, effect(ArenaAttackEffectKind.SHIELD_BYPASS,
                "피해 {bypass}%가 상대 보호막 무시", "{bypass}% of damage ignores the target's shield", "ダメージの{bypass}%が相手のシールドを無視",
                listOf(parameter("bypass", 15.0, 55.0)))),
            profile(11, 0, 9, 3, 120, effect(ArenaAttackEffectKind.HEALING_REDUCTION,
                "상대 HP에 피해 시\n2턴 동안 회복량 -{reduction}%", "On HP damage\nTarget healing -{reduction}% for 2 turns", "相手HPにダメージを与えると\n2ターン、回復量-{reduction}%",
                listOf(
                    parameter("reduction", 15.0, 45.0),
                    parameter("masterBleedTick", 0.0, values = rankTen(12.0)),
                    parameter("masterBleedTurns", 0.0, values = rankTen(3.0)),
                ), duration = 2)),
            profile(12, 1, 14, 3, 280, effect(ArenaAttackEffectKind.SPELL_STABILITY,
                "명중률 +{accuracy}%", "Accuracy +{accuracy}%", "命中率+{accuracy}%",
                listOf(
                    parameter("accuracy", 8.0, 25.0),
                    parameter("masterAccuracyFloor", 0.0, values = rankTen(100.0)),
                ))),
            profile(13, 1, 15, 4, 310, effect(ArenaAttackEffectKind.STATUS_EXPLOIT,
                "해로운 상태의 상대에게 피해 +{bonus}%\n상태가 없으면 상대가 4턴 안에 받는 다음 피해 +{vulnerability}%", "Damage +{bonus}% against a target with a harmful status\nOtherwise, its next damage taken within 4 turns +{vulnerability}%", "有害状態の相手へのダメージ+{bonus}%\n状態がなければ4ターン以内に受ける次のダメージ+{vulnerability}%",
                listOf(parameter("bonus", 15.0, 60.0), parameter("vulnerability", 5.0, 20.0)), charges = 1)),
            profile(14, 2, 21, 5, 440, effect(ArenaAttackEffectKind.CAST_DELAY,
                "제어 성공 시 상대 공격 준비 +{delay}턴\n제어 명중률 +{controlAccuracy}%", "On control success, target attack preparation +{delay:turn|turns}\nControl accuracy +{controlAccuracy}%", "制御成功時、相手の攻撃準備+{delay}ターン\n制御命中率+{controlAccuracy}%",
                listOf(parameter("delay", 1.0, values = List(9) { 1.0 } + 2.0), parameter("controlAccuracy", 0.0, 18.0,
                    listOf(0.0, 2.0, 4.0, 6.0, 8.0, 10.0, 12.0, 14.0, 16.0, 18.0))), charges = 1)),
            profile(15, 0, 12, 4, 155, effect(ArenaAttackEffectKind.LOW_HP_DRAIN,
                "HP {threshold}% 이하에서 적용\n상대 HP에 준 피해의 {drain}% 회복\n최대 HP {cap}%까지", "Applies at {threshold}% HP or less\nHeal {drain}% of damage dealt to target HP\nUp to {cap}% max HP", "HP{threshold}%以下で適用\n相手HPに与えたダメージの{drain}%回復\n最大HPの{cap}%まで",
                listOf(
                    parameter("threshold", 40.0, values = List(9) { 40.0 } + 50.0),
                    parameter("drain", 5.0, values = listOf(5.0, 5.8, 6.6, 7.4, 8.2, 9.0, 9.8, 10.6, 12.0, 15.0)),
                    parameter("cap", 4.0, values = List(9) { 4.0 } + 6.0),
                ))),
            profile(16, 3, 26, 6, 520, effect(ArenaAttackEffectKind.HEAVY_CHANNEL,
                "3턴 준비 후 강력한 공격", "A powerful attack after 3 turns of preparation", "3ターン準備後の強力な攻撃",
                listOf(
                    parameter("masterBleedTick", 0.0, values = rankTen(20.0)),
                    parameter("masterBleedTurns", 0.0, values = rankTen(3.0)),
                ))),
            profile(17, 1, 20, 5, 380, effect(ArenaAttackEffectKind.EXECUTE,
                "상대 HP {threshold}% 이하에서 피해 +{bonus}%", "Damage +{bonus}% when target HP is {threshold}% or less", "相手HP{threshold}%以下でダメージ+{bonus}%",
                listOf(
                    parameter("threshold", 30.0, values = List(9) { 30.0 } + 40.0),
                    parameter("bonus", 20.0, 60.0),
                ))),
            profile(18, 0, 13, 4, 170, effect(ArenaAttackEffectKind.DISPEL_OR_DRAIN,
                "상대 HP에 피해 시 강화 효과 최대 {dispel}개 제거\n제거할 효과가 없으면 상대 MP {drain} 흡수", "On HP damage, remove up to {dispel} target buffs\nIf none is removed, steal {drain} target MP", "相手HPにダメージを与えると強化効果を最大{dispel}個解除\n解除できなければ相手MPを{drain}吸収",
                listOf(
                    parameter("dispel", 1.0, values = List(9) { 1.0 } + 2.0),
                    parameter("drain", 5.0, 15.0),
                ))),
            profile(19, 2, 27, 6, 560, effect(ArenaAttackEffectKind.CLASS_COUNTER,
                "직업별 상성 대응 효과", "Class-specific counter effect", "職業別の相性対策効果"), overrides = a19Overrides),
            profile(20, 3, 32, 0, 720, effect(ArenaAttackEffectKind.ULTIMATE,
                "상대의 피해 감소 효과 {ignore}% 무시\n공격 전 상대 HP가 {cap}% 초과면 HP 1 유지", "Ignore {ignore}% of the target's damage reduction\nIf target HP is above {cap}% before the attack, leave 1 HP", "相手のダメージ軽減効果を{ignore}%無視\n攻撃前の相手HPが{cap}%超ならHP1を残す",
                listOf(
                    parameter("ignore", 25.0, values = List(9) { 25.0 } + 40.0),
                    parameter("cap", 55.0),
                    parameter("minimumHp", 1.0),
                )),
                once = true, earliest = 6),
        )
    }

    private fun capSupportMagnitude(kind: ArenaSupportKind, value: Double): Double = when (kind) {
        ArenaSupportKind.IRON,
        ArenaSupportKind.RESTRAINT,
        ArenaSupportKind.WRIST,
        ArenaSupportKind.BASIC_GUARD,
        ArenaSupportKind.SKILL_GUARD,
        ArenaSupportKind.SHOUT,
        ArenaSupportKind.RAPID,
        ArenaSupportKind.AIM,
        ArenaSupportKind.BLESS,
        ArenaSupportKind.RESOLVE,
        -> value.coerceAtMost(35.0)
        ArenaSupportKind.STEALTH,
        ArenaSupportKind.COUNTER,
        ArenaSupportKind.PURSUIT,
        ArenaSupportKind.OPPORTUNITY,
        ArenaSupportKind.CONDENSE,
        ArenaSupportKind.RETRIBUTION,
        ArenaSupportKind.EXECUTE,
        -> value.coerceAtMost(75.0)
        ArenaSupportKind.EVASION -> value.coerceAtMost(45.0)
        ArenaSupportKind.HEAL,
        ArenaSupportKind.BANDAGE,
        ArenaSupportKind.LAY_HANDS,
        -> value.coerceAtMost(20.0)
        ArenaSupportKind.SHIELD,
        ArenaSupportKind.LOW_SHIELD,
        -> value.coerceAtMost(25.0)
        ArenaSupportKind.HEAL_BLOCK_PREP -> value.coerceAtMost(50.0)
        ArenaSupportKind.BASIC_PIERCE,
        ArenaSupportKind.PIERCE,
        ArenaSupportKind.OBSERVE,
        -> value.coerceAtMost(60.0)
        ArenaSupportKind.SMOKE,
        ArenaSupportKind.FOCUS,
        -> value.coerceAtMost(20.0)
        else -> value
    }

    private fun capSupportSecondary(kind: ArenaSupportKind, value: Double): Double = when (kind) {
        ArenaSupportKind.AIM,
        ArenaSupportKind.BLESS,
        ArenaSupportKind.PURSUIT,
        -> value.coerceAtMost(20.0)
        else -> value
    }

    private data class LayoutSlot(
        val key: String,
        val row: Int,
        val column: Int,
        val parents: Set<String> = emptySet(),
    )

    /** A function avoids object property initialization-order coupling with [values]. */
    private fun treeLayout() = listOf(
        LayoutSlot("A01", 0, 0), LayoutSlot("A02", 0, 1), LayoutSlot("A03", 0, 2),
        LayoutSlot("A04", 1, 0, setOf("A01")), LayoutSlot("S01", 1, 1, setOf("A01", "A02")), LayoutSlot("A05", 1, 2, setOf("A03")),
        LayoutSlot("S02", 2, 0, setOf("A04")), LayoutSlot("A06", 2, 1, setOf("S01")), LayoutSlot("A07", 2, 2, setOf("A05")),
        LayoutSlot("A08", 3, 0, setOf("S02")), LayoutSlot("A09", 3, 1, setOf("A06", "A07")), LayoutSlot("S03", 3, 2, setOf("A07")),
        LayoutSlot("A10", 4, 0, setOf("A08")), LayoutSlot("S04", 4, 1, setOf("A09")), LayoutSlot("A11", 4, 2, setOf("S03")),
        LayoutSlot("S05", 5, 0, setOf("A10", "S04")), LayoutSlot("A12", 5, 1, setOf("S04")), LayoutSlot("A13", 5, 2, setOf("A11", "S04")),
        LayoutSlot("A14", 6, 0, setOf("S05")), LayoutSlot("A15", 6, 1, setOf("A12")), LayoutSlot("S06", 6, 2, setOf("A13")),
        LayoutSlot("S07", 7, 0, setOf("A14", "A15")), LayoutSlot("A16", 7, 1, setOf("A15")), LayoutSlot("A17", 7, 2, setOf("S06")),
        LayoutSlot("A18", 8, 0, setOf("S07")), LayoutSlot("S08", 8, 1, setOf("A16", "A17")), LayoutSlot("S09", 8, 2, setOf("A17")),
        LayoutSlot("A19", 9, 0, setOf("A18", "S08")), LayoutSlot("S10", 9, 1, setOf("S08", "S09")), LayoutSlot("A20", 9, 2, setOf("S09", "S10")),
    )

    private val SUPPORT_RANK_MULTIPLIERS =
        listOf(0.55, 0.65, 0.75, 0.85, 1.00, 1.10, 1.20, 1.30, 1.40, 1.50)
    private val CONTROL_ACCURACY_BONUS = listOf(0.0, 2.0, 4.0, 6.0, 8.0, 10.0, 12.0, 14.0, 16.0, 18.0)
    private val MIRROR_IMAGES = listOf(1, 1, 2, 2, 3, 3, 3, 3, 3, 4)
    private val DAMAGE_CAP_PERCENT = listOf(28.0, 26.0, 24.0, 22.0, 20.0, 19.0, 18.0, 17.0, 16.0, 15.0)
    private val POISON_ACCELERATION_BONUS = listOf(0.0, 5.0, 10.0, 15.0, 20.0, 25.0, 30.0, 35.0, 40.0, 45.0)
    private val STABILIZE_ACCURACY = listOf(90.0, 92.0, 94.0, 96.0, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0)
    private val RESTRAINT_DAMAGE_PENALTY = listOf(12.0, 11.5, 11.0, 10.5, 10.0, 9.0, 8.0, 7.0, 6.0, 5.0)
    private val COUNT_SUPPORT_KINDS = setOf(
        ArenaSupportKind.CLEANSE_ACCURACY,
        ArenaSupportKind.CLEANSE,
        ArenaSupportKind.REVEAL,
        ArenaSupportKind.DISPEL,
        ArenaSupportKind.STATUS_GUARD,
        ArenaSupportKind.JUDGMENT,
        ArenaSupportKind.SEAL,
        ArenaSupportKind.SANCTUARY,
    )

    private fun attackKey(index: Int) = "A${index.toString().padStart(2, '0')}"
    private fun supportKey(index: Int) = "S${index.toString().padStart(2, '0')}"
}

fun effectiveSupport(definition: ArenaSupportDefinition, rank: Int): ArenaSupportDefinition =
    ArenaSkillTreeCatalog.effectiveSupport(definition, rank)

private fun formatNumber(value: Double): String =
    String.format(Locale.ROOT, "%.1f", value).trimEnd('0').trimEnd('.')

private fun formatEffectNumber(key: String, value: Double): String =
    if (key.contains("accuracy", ignoreCase = true)) value.roundToInt().toString() else formatNumber(value)

internal fun arenaRankedMpCost(baseCost: Int, rank: Int): Int {
    require(baseCost > 0)
    require(rank in 1..ARENA_SKILL_TREE_MAX_RANK)
    val investedRanks = rank - 1
    return baseCost + 4 + investedRanks + baseCost * investedRanks / 25
}

private fun roundOneDecimal(value: Double): Double = (value * 10.0).roundToInt() / 10.0
