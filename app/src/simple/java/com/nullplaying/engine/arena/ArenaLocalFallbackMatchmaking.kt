package com.nullplaying.engine.arena

import com.nullplaying.engine.PublicPlayerBattleDerivation
import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.model.HeroClass
import com.nullplaying.model.HeroStats
import com.nullplaying.model.SHARED_PLAYER_MAX_LEVEL
import com.nullplaying.model.SHARED_PLAYER_MIN_LEVEL
import com.nullplaying.remote.maximumAcceptedRankingCombatPower
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.roundToLong

internal data class ArenaLocalReserveDefinition(
    val slot: Int,
    val name: String,
    val heroClass: HeroClass,
    val powerPermille: Int,
    val growthSeed: Long,
)

/** Twenty authored identities are materialized only for the requested level and never persisted. */
internal object ArenaLocalReserveMatchmaking {
    const val reserveSize = 20

    private data class RawProfileKey(val level: Long, val slot: Int)

    private val rawProfileCache = object : LinkedHashMap<RawProfileKey, HeroStats>(
        RAW_PROFILE_CACHE_SIZE + 1,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<RawProfileKey, HeroStats>?,
        ): Boolean = size > RAW_PROFILE_CACHE_SIZE
    }

    // Every prefix stays close to average power while the first ten entries cover ten different
    // presets. The second half repeats those families with each entry's exact power complement.
    private val fillOrder = listOf(
        10, 9, 18, 7, 16, 5, 14, 3, 12, 1,
        20, 19, 8, 17, 6, 15, 4, 13, 2, 11,
    )

    val definitions: List<ArenaLocalReserveDefinition> = listOf(
        ArenaLocalReserveDefinition(1, "Alden", HeroClass.WARRIOR, 900, 0x5101L),
        ArenaLocalReserveDefinition(2, "Briar", HeroClass.ROGUE, 920, 0x5102L),
        ArenaLocalReserveDefinition(3, "Celeste", HeroClass.RANGER, 935, 0x5103L),
        ArenaLocalReserveDefinition(4, "Dorian", HeroClass.MAGE, 950, 0x5104L),
        ArenaLocalReserveDefinition(5, "Elara", HeroClass.CLERIC, 965, 0x5105L),
        ArenaLocalReserveDefinition(6, "Felix", HeroClass.PALADIN, 975, 0x5106L),
        ArenaLocalReserveDefinition(7, "Gwyn", HeroClass.WARRIOR, 985, 0x5107L),
        ArenaLocalReserveDefinition(8, "Hazel", HeroClass.ROGUE, 990, 0x5108L),
        ArenaLocalReserveDefinition(9, "Ilyan", HeroClass.RANGER, 995, 0x5109L),
        ArenaLocalReserveDefinition(10, "Juniper", HeroClass.MAGE, 1_000, 0x5110L),
        ArenaLocalReserveDefinition(11, "Kael", HeroClass.CLERIC, 1_100, 0x5111L),
        ArenaLocalReserveDefinition(12, "Lyra", HeroClass.PALADIN, 1_080, 0x5112L),
        ArenaLocalReserveDefinition(13, "Mira", HeroClass.WARRIOR, 1_065, 0x5113L),
        ArenaLocalReserveDefinition(14, "Nolan", HeroClass.ROGUE, 1_050, 0x5114L),
        ArenaLocalReserveDefinition(15, "Orin", HeroClass.RANGER, 1_035, 0x5115L),
        ArenaLocalReserveDefinition(16, "Petra", HeroClass.MAGE, 1_025, 0x5116L),
        ArenaLocalReserveDefinition(17, "Quinn", HeroClass.CLERIC, 1_015, 0x5117L),
        ArenaLocalReserveDefinition(18, "Rowan", HeroClass.PALADIN, 1_010, 0x5118L),
        ArenaLocalReserveDefinition(19, "Sylvie", HeroClass.WARRIOR, 1_005, 0x5119L),
        ArenaLocalReserveDefinition(20, "Tristan", HeroClass.ROGUE, 1_000, 0x5120L),
    )

    init {
        check(definitions.size == reserveSize)
        check(definitions.map { it.slot }.toSet().size == reserveSize)
        check(definitions.map { it.name }.toSet().size == reserveSize)
        check(definitions.all { it.name.matches(Regex("^[A-Za-z]+$")) })
        check(HeroClass.entries.all { heroClass ->
            definitions.count { it.heroClass == heroClass } in 3..4
        })
        check(definitions.all { it.powerPermille in 900..1_100 })
        check(definitions.sumOf { it.powerPermille } == reserveSize * 1_000)
        check(fillOrder.toSet() == (1..reserveSize).toSet())
        check(fillOrder.indices.all { lastIndex ->
            val prefix = fillOrder.take(lastIndex + 1).map { slot ->
                definitions.single { it.slot == slot }.powerPermille
            }
            kotlin.math.abs(prefix.average() - 1_000.0) <= 6.0
        })
    }

    fun build(
        requesterCharacterId: String = "arena-local-requester",
        requesterLevel: Long,
        count: Int,
        forbiddenProjectionIds: Set<String> = emptySet(),
        matchmakingProfile: ArenaMatchmakingProfile? = null,
        requesterStats: HeroStats? = null,
    ): List<PublicPlayerArenaMatchInput>? {
        if (requesterLevel !in SHARED_PLAYER_MIN_LEVEL..SHARED_PLAYER_MAX_LEVEL ||
            count !in 0..reserveSize || (matchmakingProfile != null &&
                (matchmakingProfile.rulesVersion != ArenaAdaptiveMatchmaking.RULES_VERSION ||
                    matchmakingProfile.heroLevel != requesterLevel ||
                    matchmakingProfile.heroPower !in 1L..ArenaAdaptiveMatchmaking.MAX_LOCAL_REFERENCE_POWER))
        ) return null
        if (requesterStats != null && requesterStats.values().any { it < 0L }) return null
        if (count == 0) return emptyList()
        val usedIds = forbiddenProjectionIds.toMutableSet()
        val engine = SimpleGameEngine()
        val bySlot = definitions.associateBy(ArenaLocalReserveDefinition::slot)
        return fillOrder.take(count).map { slot ->
            val definition = bySlot.getValue(slot)
            val projectionId = uniqueProjectionId(requesterLevel, definition.slot, usedIds)
            usedIds += projectionId
            val generatedStats = representativeRawStats(engine, definition, requesterLevel)
            val rawStats = if(requesterStats != null) matchGrowthBudget(generatedStats, requesterStats, requesterLevel) else generatedStats
            val averagePower = ((1L + (requesterLevel - 1L) * 5L) * 2L).coerceAtLeast(1L)
            val generatedPower = ((averagePower * definition.powerPermille.toLong()) + 500L)
                .div(1_000L)
                .coerceIn(1L, maximumAcceptedRankingCombatPower(requesterLevel))
            val combatPower = matchmakingProfile?.let {
                ArenaAdaptiveMatchmaking.localPower(it, definition.powerPermille)
            } ?: generatedPower
            val derivedStats = (if (matchmakingProfile != null) PublicPlayerBattleDerivation.deriveLocalArenaStats(
                heroClass = definition.heroClass,
                level = requesterLevel,
                combatPower = combatPower,
                requesterCombatPower = matchmakingProfile.heroPower,
                rawStats = rawStats,
            ) else PublicPlayerBattleDerivation.deriveStats(
                heroClass = definition.heroClass,
                level = requesterLevel,
                combatPower = combatPower,
                rawStats = rawStats,
            )) ?: return null
            ArenaV6OpponentInputFactory.create(
                projectionId = projectionId,
                displayName = definition.name,
                heroClass = definition.heroClass,
                level = requesterLevel,
                combatPower = combatPower,
                stats = derivedStats,
                learnedSkills = PublicPlayerBattleDerivation.learnedSkillsForClassAndLevel(
                    definition.heroClass,
                    requesterLevel,
                ),
                arenaLevel = ArenaCharacterPointRules.budget(requesterLevel),
                stableSeed = arenaAutoBuildAllocationSeed(projectionId),
                // Slots 1..10 cover every family; slots 11..20 repeat the same order. Each pair
                // has complementary combat-power multipliers, so no style inherits a power bias.
                buildPreset = ArenaAutoBuildPreset.entries[
                    (definition.slot - 1) % ArenaAutoBuildPreset.entries.size
                ],
            ) ?: return null
        }
    }

    /** CP alone omits quest and event stat growth. Match the six-stat budget once, retaining
     * each local identity's class-specific proportions. Public opponents are never adjusted. */
    internal fun matchGrowthBudget(generated: HeroStats, requester: HeroStats, level: Long): HeroStats {
        val values=requester.values().take(6)
        require(values.all { it >= 0 })
        val maximumTotal=(level*2+106).toDouble()
        val maximumIndividual=(level*2+16).toDouble()
        val target=values.sumOf(Long::toDouble).coerceIn(6.0,maximumTotal)
        val raw=generated.values().take(6)
        val scale=minOf(target/raw.sumOf(Long::toDouble).coerceAtLeast(1.0),
            maximumIndividual/raw.maxOf(Long::toDouble).coerceAtLeast(1.0))
        val n=raw.map { (it*scale).roundToLong().coerceAtLeast(1) }
        return generated.copy(strength=n[0],constitution=n[1],dexterity=n[2],
            intelligence=n[3],wisdom=n[4],charisma=n[5])
    }

    internal fun projectionIdForLevel(level: Long, slot: Int, collisionSalt: Int = 0): String =
        UUID.nameUUIDFromBytes(
            "arena-local-reserve-v2|$level|$slot|$collisionSalt"
                .toByteArray(StandardCharsets.UTF_8),
        ).toString()

    /**
     * Selects a real, normally generated trajectory rather than averaging every stat. Each local
     * identity keeps a different roll and growth path; selection only rejects trajectories whose
     * class primary or total base roll sits outside the ordinary middle band. This prevents a
     * fallback NPC from looking stronger than a normal hero before its explicit 90-110% power
     * modifier is applied.
     */
    internal fun representativeRawStats(
        engine: SimpleGameEngine,
        definition: ArenaLocalReserveDefinition,
        level: Long,
    ): HeroStats {
        val key = RawProfileKey(level, definition.slot)
        synchronized(rawProfileCache) {
            rawProfileCache[key]?.let { return it.copy() }
        }

        val growthSteps = level - 1L
        val expectedPrimary = INITIAL_STAT_MEAN + growthSteps * PRIMARY_GROWTH_MEAN
        val primaryDeviation = sqrt(INITIAL_STAT_VARIANCE + growthSteps * PRIMARY_GROWTH_VARIANCE)
        val primaryLower = expectedPrimary - PRIMARY_MIDDLE_Z * primaryDeviation
        val primaryUpper = expectedPrimary + PRIMARY_MIDDLE_Z * primaryDeviation
        val classDefinitions = definitions.filter { it.heroClass == definition.heroClass }
            .sortedBy(ArenaLocalReserveDefinition::slot)
        val classIndex = classDefinitions.indexOfFirst { it.slot == definition.slot }
        check(classIndex >= 0)
        val classTargetZ = when (classDefinitions.size) {
            3 -> PRIMARY_TARGET_Z_THREE[classIndex]
            4 -> PRIMARY_TARGET_Z_FOUR[classIndex]
            else -> error("Local reserve classes must have three or four profiles")
        }
        val targetPrimary = expectedPrimary + classTargetZ * primaryDeviation
        val expectedTotal = INITIAL_TOTAL_MEAN + growthSteps * BASE_GROWTH_PER_LEVEL
        val totalDeviation = sqrt(INITIAL_TOTAL_VARIANCE)
        val totalLower = expectedTotal - TOTAL_MIDDLE_Z * totalDeviation
        val totalUpper = expectedTotal + TOTAL_MIDDLE_Z * totalDeviation

        fun createCandidate(candidateIndex: Int): HeroStats {
            val rollSeed = arenaStableHash64(
                "arena-local-profile-v3|${definition.growthSeed}|${definition.slot}|" +
                    "$candidateIndex|roll",
            )
            val roll = engine.rollStats(rollSeed, definition.heroClass)
            val stats = roll.stats.copy()
            ArenaSyntheticProfileGrowth.grow(
                engine = engine,
                stats = stats,
                heroClass = definition.heroClass,
                targetLevel = level,
                identitySeed = arenaStableHash64(
                    "arena-local-profile-v3|${definition.growthSeed}|${definition.slot}|" +
                        "$candidateIndex|growth",
                ),
            )
            return stats
        }

        fun selectCandidate(candidates: List<HeroStats>): Pair<HeroStats, Boolean> {
            val healthValues = candidates.map { it.maxHealth.toDouble() }.sorted()
            val manaValues = candidates.map { it.maxMana.toDouble() }.sorted()
            fun primaryDistance(stats: HeroStats): Double {
                val primary = stats.values()[definition.heroClass.primaryStatIndex].toDouble()
                return distanceOutside(primary, primaryLower, primaryUpper)
            }
            fun totalDistance(stats: HeroStats): Double {
                val total = stats.values().take(BASE_STAT_COUNT).sum().toDouble()
                return distanceOutside(total, totalLower, totalUpper)
            }
            fun healthRankDistance(stats: HeroStats): Double =
                centeredRankDistance(stats.maxHealth.toDouble(), healthValues)
            fun manaRankDistance(stats: HeroStats): Double =
                centeredRankDistance(stats.maxMana.toDouble(), manaValues)
            fun violationCount(stats: HeroStats): Int = listOf(
                primaryDistance(stats),
                totalDistance(stats),
                distanceOutside(healthRankDistance(stats), 0.0, VITALITY_CENTERED_RANK_LIMIT),
                distanceOutside(manaRankDistance(stats), 0.0, VITALITY_CENTERED_RANK_LIMIT),
            ).count { it > 0.0 }

            // Prefer the true intersection of all four ordinary bands. Only if the deterministic
            // cohort has no such profile do we minimize the number and size of violations.
            val ordinary = candidates.filter { violationCount(it) == 0 }
            val selectable = ordinary.ifEmpty { candidates }
            val selected = selectable.minWithOrNull(
                compareBy<HeroStats> { stats ->
                    if (ordinary.isEmpty()) violationCount(stats) else 0
                }.thenBy { stats ->
                    if (ordinary.isNotEmpty()) 0.0 else maxOf(
                        primaryDistance(stats) / primaryDeviation.coerceAtLeast(1.0),
                        totalDistance(stats) / totalDeviation.coerceAtLeast(1.0),
                        distanceOutside(
                            healthRankDistance(stats), 0.0, VITALITY_CENTERED_RANK_LIMIT,
                        ),
                        distanceOutside(
                            manaRankDistance(stats), 0.0, VITALITY_CENTERED_RANK_LIMIT,
                        ),
                    )
                }.thenBy { stats ->
                    maxOf(healthRankDistance(stats), manaRankDistance(stats))
                }.thenBy { stats ->
                    healthRankDistance(stats) + manaRankDistance(stats)
                }.thenBy { stats ->
                    val primary = stats.values()[definition.heroClass.primaryStatIndex].toDouble()
                    abs(primary - targetPrimary)
                }.thenBy { stats ->
                    abs(stats.values().take(BASE_STAT_COUNT).sum().toDouble() - expectedTotal)
                },
            ) ?: error("Local reserve profile candidate set must not be empty")
            return selected to ordinary.isNotEmpty()
        }

        val candidates = (0 until RAW_PROFILE_INITIAL_CANDIDATES)
            .mapTo(mutableListOf(), ::createCandidate)
        var selection = selectCandidate(candidates)
        while (!selection.second && candidates.size < RAW_PROFILE_MAX_CANDIDATES) {
            val previousSize = candidates.size
            val expandedSize = (previousSize * 2).coerceAtMost(RAW_PROFILE_MAX_CANDIDATES)
            (previousSize until expandedSize)
                .mapTo(candidates, ::createCandidate)
            selection = selectCandidate(candidates)
        }
        val selected = selection.first

        synchronized(rawProfileCache) {
            rawProfileCache[key] = selected.copy()
        }
        return selected.copy()
    }

    private fun uniqueProjectionId(level: Long, slot: Int, forbidden: Set<String>): String {
        var salt = 0
        while (true) {
            val candidate = projectionIdForLevel(level, slot, salt)
            if (candidate !in forbidden) return candidate
            salt++
        }
    }

    private fun distanceOutside(value: Double, lower: Double, upper: Double): Double = when {
        value < lower -> lower - value
        value > upper -> value - upper
        else -> 0.0
    }

    private fun centeredRankDistance(value: Double, sorted: List<Double>): Double {
        val first = sorted.indexOfFirst { it == value }
        val last = sorted.indexOfLast { it == value }
        check(first >= 0 && last >= first)
        val centeredRank = (first + last).toDouble() / 2.0 / (sorted.size - 1).toDouble()
        return abs(centeredRank - 0.5)
    }

    private const val BASE_STAT_COUNT = 6
    private const val RAW_PROFILE_INITIAL_CANDIDATES = 32
    private const val RAW_PROFILE_MAX_CANDIDATES = 128
    private const val RAW_PROFILE_CACHE_SIZE = reserveSize * 4
    private const val INITIAL_STAT_MEAN = 10.5
    private const val INITIAL_STAT_VARIANCE = 8.75
    private const val INITIAL_TOTAL_MEAN = INITIAL_STAT_MEAN * BASE_STAT_COUNT
    private const val INITIAL_TOTAL_VARIANCE = INITIAL_STAT_VARIANCE * BASE_STAT_COUNT
    private const val PRIMARY_GROWTH_MEAN = 2.0 / 3.0
    private const val PRIMARY_GROWTH_VARIANCE = 7.0 / 18.0
    private const val BASE_GROWTH_PER_LEVEL = 2.0
    // Slightly narrower than the mathematical P30/P70 cut keeps finite deterministic cohorts
    // away from the upper boundary; local reserves should never look like exceptional rolls.
    private const val PRIMARY_MIDDLE_Z = 0.40
    private const val TOTAL_MIDDLE_Z = 1.15
    private const val VITALITY_CENTERED_RANK_LIMIT = 0.25
    private val PRIMARY_TARGET_Z_THREE = doubleArrayOf(-0.25, 0.0, 0.25)
    private val PRIMARY_TARGET_Z_FOUR = doubleArrayOf(-0.30, -0.10, 0.10, 0.30)
}
