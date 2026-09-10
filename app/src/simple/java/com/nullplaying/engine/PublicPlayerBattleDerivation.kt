package com.nullplaying.engine

import com.nullplaying.model.HeroClass
import com.nullplaying.model.HeroStats
import com.nullplaying.model.LearnedSkill
import com.nullplaying.model.PublicPlayerSnapshot
import com.nullplaying.model.PublicPlayerStats
import com.nullplaying.model.SHARED_PLAYER_MAX_LEVEL
import com.nullplaying.model.SHARED_PLAYER_MIN_LEVEL
import kotlin.math.roundToLong

/** Safe local battle material derived from the deliberately small public wire contract. */
internal data class DerivedPublicBattleProfile(
    val stats: HeroStats,
    val learnedSkills: List<LearnedSkill>,
    /** Applied local correction; 1,000 means the level-average power baseline. */
    val powerScalePermille: Int,
)

/**
 * Shared pure boundary for arena and relationship battles. It never reads Android state and it
 * never reconstructs a player's actual skill mastery or equipment. The disclosed class and level
 * select catalog skills with the documented level-age average mastery; aggregate power supplies
 * only a bounded numeric correction. The same factor is applied to every raw stat, preserving
 * each character's rolled and grown strengths and weaknesses instead of replacing them with a
 * level-average stat block.
 */
internal object PublicPlayerBattleDerivation {
    fun derive(snapshot: PublicPlayerSnapshot): DerivedPublicBattleProfile? {
        val stats = deriveStats(
            heroClass = snapshot.heroClass,
            level = snapshot.level,
            combatPower = snapshot.combatPower,
            rawStats = snapshot.stats.toHeroStats(),
        ) ?: return null
        return DerivedPublicBattleProfile(
            stats = stats,
            learnedSkills = learnedSkillsForClassAndLevel(snapshot.heroClass, snapshot.level),
            powerScalePermille = powerScalePermille(snapshot.level, snapshot.combatPower),
        )
    }

    /** Same bounded formula for a local frozen participant and a public remote participant. */
    fun deriveStats(
        @Suppress("UNUSED_PARAMETER") heroClass: HeroClass,
        level: Long,
        combatPower: Long,
        rawStats: HeroStats,
        allowLowLevelQa: Boolean = false,
    ): HeroStats? = deriveStatsWithScale(
        level, combatPower, rawStats, allowLowLevelQa,
        powerScalePermille(level, combatPower).toDouble() / PER_MILLE,
    )

    /** Local reserves share the player's bounded scale, then retain their actual +/-10% ratio.
     * Public players and relationship battles keep the existing independent derivation. */
    fun deriveLocalArenaStats(
        @Suppress("UNUSED_PARAMETER") heroClass: HeroClass,
        level: Long,
        combatPower: Long,
        requesterCombatPower: Long,
        rawStats: HeroStats,
    ): HeroStats? {
        if (requesterCombatPower <= 0L) return null
        val ratio = combatPower.toDouble() / requesterCombatPower.toDouble()
        if (ratio !in 0.9..1.1) return null
        val correction = powerScalePermille(level, requesterCombatPower).toDouble() / PER_MILLE * ratio
        return deriveStatsWithScale(level, combatPower, rawStats, false, correction)
    }

    private fun deriveStatsWithScale(
        level: Long,
        combatPower: Long,
        rawStats: HeroStats,
        allowLowLevelQa: Boolean,
        correction: Double,
    ): HeroStats? {
        val minimumLevel = if (allowLowLevelQa) 1L else SHARED_PLAYER_MIN_LEVEL
        if (level !in minimumLevel..SHARED_PLAYER_MAX_LEVEL || combatPower <= 0L) return null
        val rawBase = rawStats.values().take(BASE_STAT_COUNT)
        if (rawBase.any { it < 0L } || rawStats.maxHealth < 1L || rawStats.maxMana < 0L) return null

        val maxIndividual = level * 2L + 16L
        val maxTotal = level * 2L + 106L
        val individualScale = rawBase.maxOrNull()?.takeIf { it > maxIndividual }
            ?.let { maxIndividual.toDouble() / it.toDouble() } ?: 1.0
        val total = rawBase.sumOf(Long::toDouble).coerceAtLeast(1.0)
        val totalScale = if (total > maxTotal.toDouble()) maxTotal.toDouble() / total else 1.0
        val vitalityTotal = rawStats.maxHealth.toDouble() + rawStats.maxMana.toDouble()
        val maxVitality = minOf(
            4L * level * level + 512L,
            combatPower.coerceAtMost(MAX_DERIVATION_POWER) * (level + 64L),
        )
        val vitalityScale = if (vitalityTotal > maxVitality.toDouble()) {
            maxVitality.toDouble() / vitalityTotal
        } else 1.0
        val baseScale = minOf(individualScale, totalScale) * correction
        val hpScale = vitalityScale * correction
        fun scaled(value: Long, scale: Double, minimum: Long = 0L): Long =
            (value.toDouble() * scale).roundToLong().coerceAtLeast(minimum)

        return HeroStats(
            strength = scaled(rawStats.strength, baseScale),
            constitution = scaled(rawStats.constitution, baseScale),
            dexterity = scaled(rawStats.dexterity, baseScale),
            intelligence = scaled(rawStats.intelligence, baseScale),
            wisdom = scaled(rawStats.wisdom, baseScale),
            charisma = scaled(rawStats.charisma, baseScale),
            maxHealth = scaled(rawStats.maxHealth, hpScale, 1L),
            maxMana = scaled(rawStats.maxMana, hpScale),
        )
    }

    /**
     * Deterministic class/level ownership and average mastery. A newly unlocked skill starts at
     * mastery 1; every hero level since unlock adds one mastery, capped at 100. General attacks
     * remain engine-owned and are not inserted into this list.
     */
    fun learnedSkillsForClassAndLevel(heroClass: HeroClass, level: Long): List<LearnedSkill> =
        SkillCatalog.forClass(heroClass)
            .asSequence()
            .filter { it.unlockLevel.toLong() <= level }
            .sortedWith(compareBy(SkillDefinition::unlockLevel, SkillDefinition::catalogId))
            .mapIndexed { index, definition ->
                LearnedSkill(
                    id = index + 1,
                    name = definition.name,
                    acquiredAtLevel = definition.unlockLevel.toLong(),
                    description = definition.description,
                    catalogId = definition.catalogId,
                    usageCount = ((level - definition.unlockLevel.toLong()).coerceIn(0L, 99L) *
                        LearnedSkill.USES_PER_LEVEL),
                )
            }
            .toList()

    fun powerScalePermille(level: Long, combatPower: Long): Int {
        val safeLevel = level.coerceIn(1L, SHARED_PLAYER_MAX_LEVEL)
        val benchmark = 1L + (safeLevel - 1L) * 5L
        val expected = (benchmark * 2L).coerceAtLeast(1L)
        val ratio = combatPower.coerceAtLeast(1L).toDouble() / expected.toDouble()
        return (ratio * PER_MILLE).roundToLong()
            .coerceIn(MIN_POWER_SCALE_PERMILLE.toLong(), MAX_POWER_SCALE_PERMILLE.toLong())
            .toInt()
    }

    private fun PublicPlayerStats.toHeroStats() = HeroStats(
        strength = strength,
        constitution = constitution,
        dexterity = dexterity,
        intelligence = intelligence,
        wisdom = wisdom,
        charisma = charisma,
        maxHealth = maxHealth,
        maxMana = maxMana,
    )

    private const val BASE_STAT_COUNT = 6
    private const val PER_MILLE = 1_000.0
    private const val MIN_POWER_SCALE_PERMILLE = 800
    private const val MAX_POWER_SCALE_PERMILLE = 1_200
    private const val MAX_DERIVATION_POWER = 1_000_000_000L
}
