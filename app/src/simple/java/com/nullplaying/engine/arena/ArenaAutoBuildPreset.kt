package com.nullplaying.engine.arena

import com.nullplaying.model.HeroClass

/**
 * Ten authored opponent-build families. A family is selected from stable public identity data and
 * follows one ordered point route as Arena level grows; battle outcomes never select or reroll it.
 */
internal enum class ArenaAutoBuildPreset(val stableId: String) {
    BALANCED("balanced"),
    SWIFT_ASSAULT("swift_assault"),
    HEAVY_ASSAULT("heavy_assault"),
    CONTROL_PRESSURE("control_pressure"),
    DEFENSIVE_WARD("defensive_ward"),
    SUSTAIN_RECOVERY("sustain_recovery"),
    DEFENSE_BREAKER("defense_breaker"),
    STATUS_ATTRITION("status_attrition"),
    REACTIVE_COUNTER("reactive_counter"),
    WILD_TACTICS("wild_tactics"),
    ;

    companion object {
        fun fromSeed(seed: Long): ArenaAutoBuildPreset =
            entries[Math.floorMod(seed, entries.size.toLong()).toInt()]
    }
}

internal data class ArenaAutoBuildProfile(
    val preset: ArenaAutoBuildPreset,
    /** Legacy V5 trait-tree order. V6 uses [ArenaAutoBuildProfiles.pointOrder]. */
    val legacyTraitOrder: List<String>,
) {
    init {
        require(legacyTraitOrder.distinct().size == legacyTraitOrder.size)
    }
}

internal const val ARENA_AUTO_BUILD_SEASON_ID: String = "1"

/**
 * A build-family identity deliberately excludes requester, Arena level, roster id, fetch time,
 * match sequence, and wall time. The same public projection therefore keeps one style for the
 * whole season and reveals later points from the same route as its Arena budget grows.
 */
internal fun arenaAutoBuildPresetIdentitySeed(
    opponentProjectionId: String,
    seasonId: String = ARENA_AUTO_BUILD_SEASON_ID,
): Long = arenaStableHash64(
    "arena-build-preset-v2|$seasonId|$opponentProjectionId",
)

/**
 * Stable tie-break seed used only if partial attack ownership makes a recorded route unreachable.
 * It is separate from family selection so fallback decisions cannot reroll the family.
 */
internal fun arenaAutoBuildAllocationSeed(
    opponentProjectionId: String,
    seasonId: String = ARENA_AUTO_BUILD_SEASON_ID,
): Long = arenaStableHash64(
    "arena-build-allocation-v2|$seasonId|$opponentProjectionId",
)

/**
 * V6's authority is a class-by-family ordered point table. Each entry is one point, so a build at
 * Arena level N is the first N reachable entries. Lv.20 plans were selected from legal candidates;
 * Lv.25/Lv.30 add small authored theme steps, then a deterministic neutral continuation keeps the
 * opening identity without searching simulated outcomes.
 */
internal object ArenaAutoBuildProfiles {
    private val ROOT_SLOTS = setOf("A01", "A02", "A03")
    val values: List<ArenaAutoBuildProfile> = listOf(
        profile(ArenaAutoBuildPreset.BALANCED, balancedLegacy()),
        profile(ArenaAutoBuildPreset.SWIFT_ASSAULT, branchLegacy("A", listOf(1, 2, 4, 3, 6, 5, 7))),
        profile(ArenaAutoBuildPreset.HEAVY_ASSAULT, branchLegacy("C", listOf(7, 5, 3, 6, 4, 2, 1))),
        profile(ArenaAutoBuildPreset.CONTROL_PRESSURE, branchLegacy("B", listOf(2, 4, 1, 6, 3, 7, 5))),
        profile(ArenaAutoBuildPreset.DEFENSIVE_WARD, interleavedLegacy(listOf("C", "A", "B"), listOf(1, 3, 5, 2, 4, 6, 7))),
        profile(ArenaAutoBuildPreset.SUSTAIN_RECOVERY, interleavedLegacy(listOf("A", "C", "B"), listOf(2, 1, 4, 3, 6, 5, 7))),
        profile(ArenaAutoBuildPreset.DEFENSE_BREAKER, interleavedLegacy(listOf("B", "A", "C"), listOf(3, 1, 5, 2, 7, 4, 6))),
        profile(ArenaAutoBuildPreset.STATUS_ATTRITION, branchLegacy("C", listOf(1, 3, 2, 5, 4, 7, 6))),
        profile(ArenaAutoBuildPreset.REACTIVE_COUNTER, interleavedLegacy(listOf("B", "C", "A"), listOf(4, 2, 6, 1, 5, 3, 7))),
        profile(ArenaAutoBuildPreset.WILD_TACTICS, interleavedLegacy(listOf("C", "B", "A"), listOf(7, 1, 6, 2, 5, 3, 4))),
    )

    private val byPreset = values.associateBy(ArenaAutoBuildProfile::preset)

    /**
     * Every family opens with the same neutral competency attack. With only one point there are
     * three legal states, and assigning a family to the strongest or weakest root made its name a
     * hidden result selector. The first three points establish the shared competency floor; the
     * ten distinct Lv.10 checkpoints below are the first fully expressive family boundary.
     */
    private val lowLevelSignatures = ArenaAutoBuildPreset.entries.associateWith {
        slots("A01", "A02", "A03")
    }

    /**
     * Lv.10 is an authored checkpoint rather than the first ten points of a Lv.20 max-rank rush.
     * Every row keeps its three-point family signature and spends at least eight points on the
     * competency roots. A small number of families may open one reviewed row-one node early so
     * equally spread root triples do not collapse into duplicate or extreme-strength openings.
     * Every checkpoint remains a strict prefix of its class/family's reviewed Lv.20 opening.
     */
    private val level10Plans: Map<HeroClass, Map<ArenaAutoBuildPreset, RankPlan>> = mapOf(
        HeroClass.WARRIOR to plans(
            levelTenPlan(6, 2, 2),
            levelTenPlan(5, 2, 3),
            levelTenPlan(3, 2, 5),
            levelTenPlan(4, 3, 3),
            rankPlan(1, 4, 4, s01 = 1, minimumRootPoints = 8),
            levelTenPlan(3, 3, 4),
            levelTenPlan(5, 3, 2),
            levelTenPlan(2, 4, 4),
            levelTenPlan(4, 4, 2),
            levelTenPlan(3, 4, 3),
        ),
        HeroClass.ROGUE to plans(
            levelTenPlan(5, 2, 3),
            levelTenPlan(4, 4, 2),
            rankPlan(1, 4, 4, a05 = 1, minimumRootPoints = 8),
            levelTenPlan(3, 2, 5),
            levelTenPlan(3, 3, 4),
            rankPlan(2, 1, 5, a05 = 2, minimumRootPoints = 8),
            levelTenPlan(6, 2, 2),
            levelTenPlan(2, 4, 4),
            levelTenPlan(5, 3, 2),
            levelTenPlan(3, 4, 3),
        ),
        HeroClass.RANGER to plans(
            levelTenPlan(5, 2, 3),
            levelTenPlan(4, 3, 3),
            levelTenPlan(4, 4, 2),
            levelTenPlan(2, 4, 4),
            levelTenPlan(3, 3, 4),
            levelTenPlan(4, 2, 4),
            levelTenPlan(5, 3, 2),
            levelTenPlan(3, 4, 3),
            levelTenPlan(6, 2, 2),
            levelTenPlan(3, 2, 5),
        ),
        HeroClass.MAGE to plans(
            levelTenPlan(5, 3, 2),
            levelTenPlan(6, 2, 2),
            levelTenPlan(3, 3, 4),
            rankPlan(3, 5, 1, a04 = 1, minimumRootPoints = 8),
            levelTenPlan(3, 4, 3),
            rankPlan(2, 1, 5, a05 = 2, minimumRootPoints = 8),
            levelTenPlan(5, 2, 3),
            levelTenPlan(3, 2, 5),
            levelTenPlan(2, 4, 4),
            levelTenPlan(3, 5, 2),
        ),
        HeroClass.CLERIC to plans(
            levelTenPlan(2, 4, 4),
            levelTenPlan(6, 2, 2),
            levelTenPlan(3, 3, 4),
            levelTenPlan(4, 5, 1),
            rankPlan(1, 3, 5, s01 = 1, minimumRootPoints = 8),
            levelTenPlan(3, 5, 2),
            levelTenPlan(3, 2, 5),
            levelTenPlan(5, 2, 3),
            levelTenPlan(5, 3, 2),
            levelTenPlan(3, 4, 3),
        ),
        HeroClass.PALADIN to plans(
            levelTenPlan(6, 1, 3),
            levelTenPlan(6, 2, 2),
            rankPlan(3, 1, 5, a04 = 1, minimumRootPoints = 8),
            levelTenPlan(5, 2, 3),
            levelTenPlan(2, 4, 4),
            levelTenPlan(3, 2, 5),
            levelTenPlan(3, 3, 4),
            levelTenPlan(5, 3, 2),
            levelTenPlan(2, 5, 3),
            levelTenPlan(3, 4, 3),
        ),
    )

    /**
     * Explicit per-class release openings. These are not a global plan plus exceptions: every class
     * owns all ten rows, which makes tuning and review possible without hidden inheritance.
     */
    private val level20Plans: Map<HeroClass, Map<ArenaAutoBuildPreset, RankPlan>> = mapOf(
        HeroClass.WARRIOR to plans(
            rankPlan(8, 7, 5),
            rankPlan(10, 7, 3),
            rankPlan(3, 3, 10, a04 = 3, s01 = 1),
            rankPlan(4, 6, 6, a04 = 4),
            rankPlan(2, 10, 4, s01 = 4),
            rankPlan(3, 6, 5, a05 = 5, s01 = 1),
            rankPlan(8, 3, 9),
            rankPlan(3, 5, 7, a05 = 5),
            rankPlan(8, 4, 3, a05 = 5),
            rankPlan(3, 4, 6, a04 = 1, a05 = 6),
        ),
        HeroClass.ROGUE to plans(
            rankPlan(8, 5, 7),
            rankPlan(10, 6, 4),
            rankPlan(3, 4, 8, a05 = 5),
            rankPlan(3, 6, 6, a04 = 4, s01 = 1),
            rankPlan(4, 5, 9, a04 = 1, s01 = 1),
            rankPlan(3, 3, 10, a05 = 3, s01 = 1),
            rankPlan(9, 5, 6),
            rankPlan(4, 6, 5, a05 = 5),
            rankPlan(5, 5, 8, s01 = 2),
            rankPlan(3, 5, 6, a04 = 1, a05 = 5),
        ),
        HeroClass.RANGER to plans(
            rankPlan(8, 8, 4),
            rankPlan(10, 5, 5),
            rankPlan(4, 5, 8, a05 = 3),
            rankPlan(3, 5, 6, a04 = 6),
            rankPlan(3, 8, 4, a04 = 3, s01 = 2),
            rankPlan(4, 4, 4, a05 = 7, s01 = 1),
            rankPlan(10, 6, 4),
            rankPlan(3, 4, 8, a05 = 5),
            rankPlan(6, 3, 9, a05 = 1, s01 = 1),
            rankPlan(3, 4, 5, a04 = 2, a05 = 6),
        ),
        HeroClass.MAGE to plans(
            rankPlan(7, 4, 9),
            rankPlan(8, 2, 10),
            rankPlan(3, 4, 10, a04 = 3),
            rankPlan(3, 8, 3, a04 = 6),
            rankPlan(3, 10, 3, a04 = 3, s01 = 1),
            rankPlan(4, 3, 5, a05 = 6, s01 = 2),
            rankPlan(8, 7, 5),
            rankPlan(3, 5, 7, a05 = 5),
            rankPlan(4, 7, 5, a04 = 4),
            rankPlan(3, 7, 3, a04 = 2, a05 = 5),
        ),
        HeroClass.CLERIC to plans(
            rankPlan(7, 8, 5),
            rankPlan(6, 2, 4, a05 = 7, s01 = 1),
            rankPlan(3, 3, 10, a05 = 4),
            rankPlan(4, 10, 3, a04 = 3),
            rankPlan(3, 8, 5, a04 = 3, s01 = 1),
            rankPlan(3, 6, 7, a05 = 3, s01 = 1),
            rankPlan(7, 7, 6),
            rankPlan(5, 5, 4, a05 = 6),
            rankPlan(5, 7, 5, a04 = 3),
            rankPlan(3, 5, 4, a04 = 2, a05 = 6),
        ),
        HeroClass.PALADIN to plans(
            rankPlan(7, 9, 4),
            rankPlan(8, 7, 5),
            rankPlan(4, 3, 10, a04 = 3),
            rankPlan(5, 6, 5, a04 = 4),
            rankPlan(4, 7, 5, a04 = 3, s01 = 1),
            rankPlan(3, 3, 10, a05 = 3, s01 = 1),
            rankPlan(10, 5, 5),
            rankPlan(5, 8, 3, a05 = 4),
            rankPlan(3, 8, 3, a04 = 6),
            rankPlan(3, 7, 5, a04 = 1, a05 = 4),
        ),
    )

    /** Partial Lv.10 public heroes own the three competency attacks and the first support route. */
    private val competencyLevel20: Map<ArenaAutoBuildPreset, RankPlan> = plans(
        rankPlan(7, 10, 3),
        rankPlan(8, 10, 2),
        rankPlan(6, 10, 4),
        rankPlan(7, 9, 1, s01 = 3),
        rankPlan(6, 10, 3, s01 = 1),
        rankPlan(6, 10, 1, s01 = 3),
        rankPlan(5, 10, 5),
        rankPlan(4, 10, 6),
        rankPlan(6, 10, 2, s01 = 2),
        rankPlan(5, 10, 3, s01 = 2),
    )

    /** Lv.10 fallback for projections that only own the initial competency attacks. */
    private val competencyLevel10: Map<ArenaAutoBuildPreset, RankPlan> = plans(
        levelTenPlan(4, 3, 3),
        levelTenPlan(5, 3, 2),
        levelTenPlan(3, 3, 4),
        levelTenPlan(4, 5, 1),
        levelTenPlan(4, 4, 2),
        levelTenPlan(3, 6, 1),
        levelTenPlan(5, 2, 3),
        levelTenPlan(3, 2, 5),
        levelTenPlan(3, 5, 2),
        levelTenPlan(3, 4, 3),
    )

    /**
     * Lv.21-30 is a shared competency phase. The family identity already lives in the authored
     * Lv.20 opening, including its row-one attack/support choices. Spending the next ten points on
     * those thematic children made one family several times stronger than another, so this phase
     * deliberately strengthens the three common attacks instead. Different permutations retain
     * distinct checkpoints without turning a support or charged attack into a trap/optimal build.
     */
    private val releasePhasePriority = mapOf(
        ArenaAutoBuildPreset.BALANCED to slots("A02", "A01", "A03"),
        ArenaAutoBuildPreset.SWIFT_ASSAULT to slots("A01", "A02", "A03"),
        ArenaAutoBuildPreset.HEAVY_ASSAULT to slots("A03", "A02", "A01"),
        ArenaAutoBuildPreset.CONTROL_PRESSURE to slots("A02", "A03", "A01"),
        ArenaAutoBuildPreset.DEFENSIVE_WARD to slots("A01", "A03", "A02"),
        ArenaAutoBuildPreset.SUSTAIN_RECOVERY to slots("A03", "A01", "A02"),
        ArenaAutoBuildPreset.DEFENSE_BREAKER to slots("A03", "A02", "A01"),
        ArenaAutoBuildPreset.STATUS_ATTRITION to slots("A02", "A03", "A01"),
        ArenaAutoBuildPreset.REACTIVE_COUNTER to slots("A01", "A02", "A03"),
        ArenaAutoBuildPreset.WILD_TACTICS to slots("A03", "A01", "A02"),
    )

    /**
     * Explicit class-by-family release checkpoints. Each Lv.25 row is a legal five-point
     * extension of Lv.20 and each Lv.30 row extends that exact route; no simulated outcome is
     * consulted at runtime. The table is exhaustive so release builds cannot fall back to a
     * shared strongest-path heuristic when a new family is added.
     */
    private val releaseCheckpoints: Map<CheckpointKey, RankPlan> = mapOf(
        CheckpointKey(25, HeroClass.WARRIOR, ArenaAutoBuildPreset.BALANCED) to
            rankPlan(9, 8, 8),
        CheckpointKey(25, HeroClass.WARRIOR, ArenaAutoBuildPreset.SWIFT_ASSAULT) to
            rankPlan(10, 8, 6, a04 = 1),
        CheckpointKey(25, HeroClass.WARRIOR, ArenaAutoBuildPreset.HEAVY_ASSAULT) to
            rankPlan(3, 8, 10, a04 = 3, s01 = 1),
        CheckpointKey(25, HeroClass.WARRIOR, ArenaAutoBuildPreset.CONTROL_PRESSURE) to
            rankPlan(5, 8, 8, a04 = 4),
        CheckpointKey(25, HeroClass.WARRIOR, ArenaAutoBuildPreset.DEFENSIVE_WARD) to
            rankPlan(5, 10, 6, s01 = 4),
        CheckpointKey(25, HeroClass.WARRIOR, ArenaAutoBuildPreset.SUSTAIN_RECOVERY) to
            rankPlan(3, 8, 7, a05 = 5, s01 = 2),
        CheckpointKey(25, HeroClass.WARRIOR, ArenaAutoBuildPreset.DEFENSE_BREAKER) to
            rankPlan(10, 5, 10),
        CheckpointKey(25, HeroClass.WARRIOR, ArenaAutoBuildPreset.STATUS_ATTRITION) to
            rankPlan(4, 7, 9, a05 = 5),
        CheckpointKey(25, HeroClass.WARRIOR, ArenaAutoBuildPreset.REACTIVE_COUNTER) to
            rankPlan(8, 4, 3, a05 = 8, s01 = 2),
        CheckpointKey(25, HeroClass.WARRIOR, ArenaAutoBuildPreset.WILD_TACTICS) to
            rankPlan(3, 4, 6, a04 = 4, a05 = 8),
        CheckpointKey(25, HeroClass.ROGUE, ArenaAutoBuildPreset.BALANCED) to
            rankPlan(8, 9, 7, a04 = 1),
        CheckpointKey(25, HeroClass.ROGUE, ArenaAutoBuildPreset.SWIFT_ASSAULT) to
            rankPlan(10, 8, 6, a04 = 1),
        CheckpointKey(25, HeroClass.ROGUE, ArenaAutoBuildPreset.HEAVY_ASSAULT) to
            rankPlan(4, 6, 10, a05 = 5),
        CheckpointKey(25, HeroClass.ROGUE, ArenaAutoBuildPreset.CONTROL_PRESSURE) to
            rankPlan(3, 8, 8, a04 = 5, s01 = 1),
        CheckpointKey(25, HeroClass.ROGUE, ArenaAutoBuildPreset.DEFENSIVE_WARD) to
            rankPlan(5, 6, 10, a04 = 3, s01 = 1),
        CheckpointKey(25, HeroClass.ROGUE, ArenaAutoBuildPreset.SUSTAIN_RECOVERY) to
            rankPlan(3, 7, 10, a04 = 1, a05 = 3, s01 = 1),
        CheckpointKey(25, HeroClass.ROGUE, ArenaAutoBuildPreset.DEFENSE_BREAKER) to
            rankPlan(10, 5, 10),
        CheckpointKey(25, HeroClass.ROGUE, ArenaAutoBuildPreset.STATUS_ATTRITION) to
            rankPlan(5, 8, 7, a05 = 5),
        CheckpointKey(25, HeroClass.ROGUE, ArenaAutoBuildPreset.REACTIVE_COUNTER) to
            rankPlan(5, 8, 8, a05 = 2, s01 = 2),
        CheckpointKey(25, HeroClass.ROGUE, ArenaAutoBuildPreset.WILD_TACTICS) to
            rankPlan(4, 6, 7, a04 = 1, a05 = 7),
        CheckpointKey(25, HeroClass.RANGER, ArenaAutoBuildPreset.BALANCED) to
            rankPlan(8, 10, 6, a05 = 1),
        CheckpointKey(25, HeroClass.RANGER, ArenaAutoBuildPreset.SWIFT_ASSAULT) to
            rankPlan(10, 5, 10),
        CheckpointKey(25, HeroClass.RANGER, ArenaAutoBuildPreset.HEAVY_ASSAULT) to
            rankPlan(5, 7, 10, a05 = 3),
        CheckpointKey(25, HeroClass.RANGER, ArenaAutoBuildPreset.CONTROL_PRESSURE) to
            rankPlan(4, 7, 8, a04 = 6),
        CheckpointKey(25, HeroClass.RANGER, ArenaAutoBuildPreset.DEFENSIVE_WARD) to
            rankPlan(5, 9, 6, a04 = 3, s01 = 2),
        CheckpointKey(25, HeroClass.RANGER, ArenaAutoBuildPreset.SUSTAIN_RECOVERY) to
            rankPlan(4, 8, 4, a05 = 7, s01 = 2),
        CheckpointKey(25, HeroClass.RANGER, ArenaAutoBuildPreset.DEFENSE_BREAKER) to
            rankPlan(10, 8, 7),
        CheckpointKey(25, HeroClass.RANGER, ArenaAutoBuildPreset.STATUS_ATTRITION) to
            rankPlan(3, 5, 10, a05 = 5, a07 = 2),
        CheckpointKey(25, HeroClass.RANGER, ArenaAutoBuildPreset.REACTIVE_COUNTER) to
            rankPlan(6, 6, 10, a05 = 2, s01 = 1),
        CheckpointKey(25, HeroClass.RANGER, ArenaAutoBuildPreset.WILD_TACTICS) to
            rankPlan(3, 4, 6, a04 = 3, a05 = 8, s02 = 1),
        CheckpointKey(25, HeroClass.MAGE, ArenaAutoBuildPreset.BALANCED) to
            rankPlan(8, 6, 9, a04 = 2),
        CheckpointKey(25, HeroClass.MAGE, ArenaAutoBuildPreset.SWIFT_ASSAULT) to
            rankPlan(9, 5, 10, a04 = 1),
        CheckpointKey(25, HeroClass.MAGE, ArenaAutoBuildPreset.HEAVY_ASSAULT) to
            rankPlan(3, 6, 10, a04 = 3, a05 = 2, s01 = 1),
        CheckpointKey(25, HeroClass.MAGE, ArenaAutoBuildPreset.CONTROL_PRESSURE) to
            rankPlan(3, 10, 5, a04 = 7),
        CheckpointKey(25, HeroClass.MAGE, ArenaAutoBuildPreset.DEFENSIVE_WARD) to
            rankPlan(6, 10, 5, a04 = 3, s01 = 1),
        CheckpointKey(25, HeroClass.MAGE, ArenaAutoBuildPreset.SUSTAIN_RECOVERY) to
            rankPlan(4, 6, 6, a05 = 7, s01 = 2),
        CheckpointKey(25, HeroClass.MAGE, ArenaAutoBuildPreset.DEFENSE_BREAKER) to
            rankPlan(9, 9, 7),
        CheckpointKey(25, HeroClass.MAGE, ArenaAutoBuildPreset.STATUS_ATTRITION) to
            rankPlan(6, 7, 7, a05 = 5),
        CheckpointKey(25, HeroClass.MAGE, ArenaAutoBuildPreset.REACTIVE_COUNTER) to
            rankPlan(6, 9, 6, a04 = 4),
        CheckpointKey(25, HeroClass.MAGE, ArenaAutoBuildPreset.WILD_TACTICS) to
            rankPlan(3, 7, 6, a04 = 2, a05 = 7),
        CheckpointKey(25, HeroClass.CLERIC, ArenaAutoBuildPreset.BALANCED) to
            rankPlan(9, 9, 6, a05 = 1),
        CheckpointKey(25, HeroClass.CLERIC, ArenaAutoBuildPreset.SWIFT_ASSAULT) to
            rankPlan(7, 3, 6, a05 = 8, s01 = 1),
        CheckpointKey(25, HeroClass.CLERIC, ArenaAutoBuildPreset.HEAVY_ASSAULT) to
            rankPlan(3, 6, 10, a05 = 4, a07 = 2),
        CheckpointKey(25, HeroClass.CLERIC, ArenaAutoBuildPreset.CONTROL_PRESSURE) to
            rankPlan(4, 10, 5, a04 = 3, a05 = 3),
        CheckpointKey(25, HeroClass.CLERIC, ArenaAutoBuildPreset.DEFENSIVE_WARD) to
            rankPlan(5, 9, 7, a04 = 3, s01 = 1),
        CheckpointKey(25, HeroClass.CLERIC, ArenaAutoBuildPreset.SUSTAIN_RECOVERY) to
            rankPlan(3, 8, 8, a05 = 4, s01 = 2),
        CheckpointKey(25, HeroClass.CLERIC, ArenaAutoBuildPreset.DEFENSE_BREAKER) to
            rankPlan(9, 9, 7),
        CheckpointKey(25, HeroClass.CLERIC, ArenaAutoBuildPreset.STATUS_ATTRITION) to
            rankPlan(6, 7, 6, a05 = 6),
        CheckpointKey(25, HeroClass.CLERIC, ArenaAutoBuildPreset.REACTIVE_COUNTER) to
            rankPlan(7, 9, 6, a04 = 3),
        CheckpointKey(25, HeroClass.CLERIC, ArenaAutoBuildPreset.WILD_TACTICS) to
            rankPlan(3, 5, 6, a04 = 4, a05 = 7),
        CheckpointKey(25, HeroClass.PALADIN, ArenaAutoBuildPreset.BALANCED) to
            rankPlan(7, 9, 4, a05 = 5),
        CheckpointKey(25, HeroClass.PALADIN, ArenaAutoBuildPreset.SWIFT_ASSAULT) to
            rankPlan(9, 9, 6, s01 = 1),
        CheckpointKey(25, HeroClass.PALADIN, ArenaAutoBuildPreset.HEAVY_ASSAULT) to
            rankPlan(4, 6, 10, a04 = 5),
        CheckpointKey(25, HeroClass.PALADIN, ArenaAutoBuildPreset.CONTROL_PRESSURE) to
            rankPlan(6, 8, 7, a04 = 4),
        CheckpointKey(25, HeroClass.PALADIN, ArenaAutoBuildPreset.DEFENSIVE_WARD) to
            rankPlan(6, 8, 7, a04 = 3, s01 = 1),
        CheckpointKey(25, HeroClass.PALADIN, ArenaAutoBuildPreset.SUSTAIN_RECOVERY) to
            rankPlan(3, 6, 10, a05 = 5, s01 = 1),
        CheckpointKey(25, HeroClass.PALADIN, ArenaAutoBuildPreset.DEFENSE_BREAKER) to
            rankPlan(10, 8, 7),
        CheckpointKey(25, HeroClass.PALADIN, ArenaAutoBuildPreset.STATUS_ATTRITION) to
            rankPlan(6, 10, 5, a05 = 4),
        CheckpointKey(25, HeroClass.PALADIN, ArenaAutoBuildPreset.REACTIVE_COUNTER) to
            rankPlan(4, 10, 5, a04 = 6),
        CheckpointKey(25, HeroClass.PALADIN, ArenaAutoBuildPreset.WILD_TACTICS) to
            rankPlan(3, 7, 5, a04 = 3, a05 = 6, s02 = 1),
        CheckpointKey(30, HeroClass.WARRIOR, ArenaAutoBuildPreset.BALANCED) to
            rankPlan(9, 8, 10, a04 = 1, s01 = 2),
        CheckpointKey(30, HeroClass.WARRIOR, ArenaAutoBuildPreset.SWIFT_ASSAULT) to
            rankPlan(10, 9, 8, a04 = 1, a05 = 1, s01 = 1),
        CheckpointKey(30, HeroClass.WARRIOR, ArenaAutoBuildPreset.HEAVY_ASSAULT) to
            rankPlan(8, 8, 10, a04 = 3, s01 = 1),
        CheckpointKey(30, HeroClass.WARRIOR, ArenaAutoBuildPreset.CONTROL_PRESSURE) to
            rankPlan(6, 10, 10, a04 = 4),
        CheckpointKey(30, HeroClass.WARRIOR, ArenaAutoBuildPreset.DEFENSIVE_WARD) to
            rankPlan(7, 10, 9, s01 = 4),
        CheckpointKey(30, HeroClass.WARRIOR, ArenaAutoBuildPreset.SUSTAIN_RECOVERY) to
            rankPlan(3, 9, 9, a05 = 6, a07 = 1, s01 = 2),
        CheckpointKey(30, HeroClass.WARRIOR, ArenaAutoBuildPreset.DEFENSE_BREAKER) to
            rankPlan(10, 8, 10, a04 = 1, a05 = 1),
        CheckpointKey(30, HeroClass.WARRIOR, ArenaAutoBuildPreset.STATUS_ATTRITION) to
            rankPlan(6, 9, 10, a05 = 5),
        CheckpointKey(30, HeroClass.WARRIOR, ArenaAutoBuildPreset.REACTIVE_COUNTER) to
            rankPlan(8, 4, 5, a05 = 10, s01 = 3),
        CheckpointKey(30, HeroClass.WARRIOR, ArenaAutoBuildPreset.WILD_TACTICS) to
            rankPlan(4, 4, 7, a04 = 4, a05 = 10, a07 = 1),
        CheckpointKey(30, HeroClass.ROGUE, ArenaAutoBuildPreset.BALANCED) to
            rankPlan(8, 10, 7, a04 = 5),
        CheckpointKey(30, HeroClass.ROGUE, ArenaAutoBuildPreset.SWIFT_ASSAULT) to
            rankPlan(10, 8, 10, a04 = 2),
        CheckpointKey(30, HeroClass.ROGUE, ArenaAutoBuildPreset.HEAVY_ASSAULT) to
            rankPlan(4, 9, 10, a05 = 5, a07 = 1, s01 = 1),
        CheckpointKey(30, HeroClass.ROGUE, ArenaAutoBuildPreset.CONTROL_PRESSURE) to
            rankPlan(4, 10, 10, a04 = 5, s01 = 1),
        CheckpointKey(30, HeroClass.ROGUE, ArenaAutoBuildPreset.DEFENSIVE_WARD) to
            rankPlan(6, 10, 10, a04 = 3, s01 = 1),
        CheckpointKey(30, HeroClass.ROGUE, ArenaAutoBuildPreset.SUSTAIN_RECOVERY) to
            rankPlan(3, 7, 10, a04 = 1, a05 = 8, s01 = 1),
        CheckpointKey(30, HeroClass.ROGUE, ArenaAutoBuildPreset.DEFENSE_BREAKER) to
            rankPlan(10, 8, 10, a04 = 1, a05 = 1),
        CheckpointKey(30, HeroClass.ROGUE, ArenaAutoBuildPreset.STATUS_ATTRITION) to
            rankPlan(6, 10, 9, a05 = 5),
        CheckpointKey(30, HeroClass.ROGUE, ArenaAutoBuildPreset.REACTIVE_COUNTER) to
            rankPlan(5, 10, 8, a05 = 5, s01 = 2),
        CheckpointKey(30, HeroClass.ROGUE, ArenaAutoBuildPreset.WILD_TACTICS) to
            rankPlan(4, 7, 9, a04 = 1, a05 = 9),
        CheckpointKey(30, HeroClass.RANGER, ArenaAutoBuildPreset.BALANCED) to
            rankPlan(8, 10, 9, a04 = 1, a05 = 1, s01 = 1),
        CheckpointKey(30, HeroClass.RANGER, ArenaAutoBuildPreset.SWIFT_ASSAULT) to
            rankPlan(10, 8, 10, a04 = 2),
        CheckpointKey(30, HeroClass.RANGER, ArenaAutoBuildPreset.HEAVY_ASSAULT) to
            rankPlan(7, 10, 10, a05 = 3),
        CheckpointKey(30, HeroClass.RANGER, ArenaAutoBuildPreset.CONTROL_PRESSURE) to
            rankPlan(5, 9, 10, a04 = 6),
        CheckpointKey(30, HeroClass.RANGER, ArenaAutoBuildPreset.DEFENSIVE_WARD) to
            rankPlan(7, 10, 8, a04 = 3, s01 = 2),
        CheckpointKey(30, HeroClass.RANGER, ArenaAutoBuildPreset.SUSTAIN_RECOVERY) to
            rankPlan(4, 8, 6, a05 = 9, s01 = 3),
        CheckpointKey(30, HeroClass.RANGER, ArenaAutoBuildPreset.DEFENSE_BREAKER) to
            rankPlan(10, 8, 10, a04 = 1, a05 = 1),
        CheckpointKey(30, HeroClass.RANGER, ArenaAutoBuildPreset.STATUS_ATTRITION) to
            rankPlan(3, 7, 10, a05 = 8, a07 = 2),
        CheckpointKey(30, HeroClass.RANGER, ArenaAutoBuildPreset.REACTIVE_COUNTER) to
            rankPlan(9, 8, 10, a05 = 2, s01 = 1),
        CheckpointKey(30, HeroClass.RANGER, ArenaAutoBuildPreset.WILD_TACTICS) to
            rankPlan(3, 4, 6, a04 = 3, a05 = 10, s02 = 4),
        CheckpointKey(30, HeroClass.MAGE, ArenaAutoBuildPreset.BALANCED) to
            rankPlan(9, 8, 10, a04 = 2, a05 = 1),
        CheckpointKey(30, HeroClass.MAGE, ArenaAutoBuildPreset.SWIFT_ASSAULT) to
            rankPlan(10, 8, 10, a04 = 1, s01 = 1),
        CheckpointKey(30, HeroClass.MAGE, ArenaAutoBuildPreset.HEAVY_ASSAULT) to
            rankPlan(3, 10, 10, a04 = 3, a05 = 2, s01 = 1, s02 = 1),
        CheckpointKey(30, HeroClass.MAGE, ArenaAutoBuildPreset.CONTROL_PRESSURE) to
            rankPlan(4, 10, 9, a04 = 7),
        CheckpointKey(30, HeroClass.MAGE, ArenaAutoBuildPreset.DEFENSIVE_WARD) to
            rankPlan(7, 10, 9, a04 = 3, s01 = 1),
        CheckpointKey(30, HeroClass.MAGE, ArenaAutoBuildPreset.SUSTAIN_RECOVERY) to
            rankPlan(4, 8, 7, a05 = 9, s01 = 2),
        CheckpointKey(30, HeroClass.MAGE, ArenaAutoBuildPreset.DEFENSE_BREAKER) to
            rankPlan(10, 9, 10, a05 = 1),
        CheckpointKey(30, HeroClass.MAGE, ArenaAutoBuildPreset.STATUS_ATTRITION) to
            rankPlan(6, 9, 10, a05 = 5),
        CheckpointKey(30, HeroClass.MAGE, ArenaAutoBuildPreset.REACTIVE_COUNTER) to
            rankPlan(8, 10, 8, a04 = 4),
        CheckpointKey(30, HeroClass.MAGE, ArenaAutoBuildPreset.WILD_TACTICS) to
            rankPlan(4, 8, 8, a04 = 2, a05 = 7, s01 = 1),
        CheckpointKey(30, HeroClass.CLERIC, ArenaAutoBuildPreset.BALANCED) to
            rankPlan(9, 10, 9, a05 = 2),
        CheckpointKey(30, HeroClass.CLERIC, ArenaAutoBuildPreset.SWIFT_ASSAULT) to
            rankPlan(8, 4, 8, a05 = 9, s01 = 1),
        CheckpointKey(30, HeroClass.CLERIC, ArenaAutoBuildPreset.HEAVY_ASSAULT) to
            rankPlan(3, 7, 10, a05 = 4, a07 = 6),
        CheckpointKey(30, HeroClass.CLERIC, ArenaAutoBuildPreset.CONTROL_PRESSURE) to
            rankPlan(5, 10, 9, a04 = 3, a05 = 3),
        CheckpointKey(30, HeroClass.CLERIC, ArenaAutoBuildPreset.DEFENSIVE_WARD) to
            rankPlan(5, 10, 8, a04 = 3, s01 = 4),
        CheckpointKey(30, HeroClass.CLERIC, ArenaAutoBuildPreset.SUSTAIN_RECOVERY) to
            rankPlan(3, 10, 9, a05 = 4, a07 = 2, s01 = 2),
        CheckpointKey(30, HeroClass.CLERIC, ArenaAutoBuildPreset.DEFENSE_BREAKER) to
            rankPlan(9, 10, 7, a05 = 3, a07 = 1),
        CheckpointKey(30, HeroClass.CLERIC, ArenaAutoBuildPreset.STATUS_ATTRITION) to
            rankPlan(7, 9, 8, a05 = 6),
        CheckpointKey(30, HeroClass.CLERIC, ArenaAutoBuildPreset.REACTIVE_COUNTER) to
            rankPlan(9, 10, 8, a04 = 3),
        CheckpointKey(30, HeroClass.CLERIC, ArenaAutoBuildPreset.WILD_TACTICS) to
            rankPlan(3, 5, 8, a04 = 4, a05 = 10),
        CheckpointKey(30, HeroClass.PALADIN, ArenaAutoBuildPreset.BALANCED) to
            rankPlan(7, 9, 5, a05 = 9),
        CheckpointKey(30, HeroClass.PALADIN, ArenaAutoBuildPreset.SWIFT_ASSAULT) to
            rankPlan(9, 10, 6, a05 = 4, s01 = 1),
        CheckpointKey(30, HeroClass.PALADIN, ArenaAutoBuildPreset.HEAVY_ASSAULT) to
            rankPlan(7, 8, 10, a04 = 5),
        CheckpointKey(30, HeroClass.PALADIN, ArenaAutoBuildPreset.CONTROL_PRESSURE) to
            rankPlan(7, 8, 10, a04 = 4, a05 = 1),
        CheckpointKey(30, HeroClass.PALADIN, ArenaAutoBuildPreset.DEFENSIVE_WARD) to
            rankPlan(8, 9, 9, a04 = 3, s01 = 1),
        CheckpointKey(30, HeroClass.PALADIN, ArenaAutoBuildPreset.SUSTAIN_RECOVERY) to
            rankPlan(3, 9, 10, a04 = 2, a05 = 5, s01 = 1),
        CheckpointKey(30, HeroClass.PALADIN, ArenaAutoBuildPreset.DEFENSE_BREAKER) to
            rankPlan(10, 8, 7, a05 = 3, a07 = 1, s01 = 1),
        CheckpointKey(30, HeroClass.PALADIN, ArenaAutoBuildPreset.STATUS_ATTRITION) to
            rankPlan(7, 10, 9, a05 = 4),
        CheckpointKey(30, HeroClass.PALADIN, ArenaAutoBuildPreset.REACTIVE_COUNTER) to
            rankPlan(6, 10, 7, a04 = 6, s01 = 1),
        CheckpointKey(30, HeroClass.PALADIN, ArenaAutoBuildPreset.WILD_TACTICS) to
            rankPlan(3, 9, 5, a04 = 3, a05 = 6, s02 = 4),
    )
    private val competencyLevel25: Map<ArenaAutoBuildPreset, RankPlan> = plans(
        rankPlan(10, 10, 5),
        rankPlan(10, 10, 4, s01 = 1),
        rankPlan(9, 10, 6),
        rankPlan(10, 10, 2, s01 = 3),
        rankPlan(9, 10, 5, s01 = 1),
        rankPlan(9, 10, 3, s01 = 3),
        rankPlan(8, 10, 7),
        rankPlan(7, 10, 8),
        rankPlan(10, 10, 3, s01 = 2),
        rankPlan(8, 10, 5, s01 = 2),
    )

    private val competencyLevel30: Map<ArenaAutoBuildPreset, RankPlan> = plans(
        rankPlan(10, 10, 10),
        rankPlan(10, 10, 9, s01 = 1),
        rankPlan(9, 10, 10, s01 = 1),
        rankPlan(10, 10, 7, s01 = 3),
        rankPlan(9, 10, 9, s01 = 2),
        rankPlan(9, 10, 8, s01 = 3),
        rankPlan(8, 10, 10, s01 = 2),
        rankPlan(7, 10, 10, s01 = 3),
        rankPlan(10, 10, 8, s01 = 2),
        rankPlan(8, 10, 9, s01 = 3),
    )

    private val fullOwnershipPointOrders: Map<HeroClass, Map<ArenaAutoBuildPreset, List<String>>> =
        HeroClass.entries.associateWith { heroClass ->
            level20Plans.getValue(heroClass).mapValues { (preset, plan) ->
                buildFullRoute(heroClass, preset, plan)
            }
        }

    private val competencyPointOrders: Map<ArenaAutoBuildPreset, List<String>> =
        ArenaAutoBuildPreset.entries.associateWith { preset ->
            buildCompetencyRoute(preset)
        }

    init {
        check(values.size == ArenaAutoBuildPreset.entries.size)
        check(byPreset.size == ArenaAutoBuildPreset.entries.size)
        check(ArenaAutoBuildPreset.entries.map(ArenaAutoBuildPreset::stableId).distinct().size == values.size)
        check(lowLevelSignatures.keys == ArenaAutoBuildPreset.entries.toSet())
        check(lowLevelSignatures.values.all { it.size == 3 && it.first() == "A01" })
        check(level10Plans.keys == HeroClass.entries.toSet())
        check(level10Plans.values.all { it.keys == ArenaAutoBuildPreset.entries.toSet() })
        check(level10Plans.values.all { plans -> plans.values.all { it.points == 10 } })
        check(level10Plans.values.all { plans -> plans.values.toSet().size == ArenaAutoBuildPreset.entries.size })
        check(level20Plans.keys == HeroClass.entries.toSet())
        check(level20Plans.values.all { it.keys == ArenaAutoBuildPreset.entries.toSet() })
        check(level20Plans.values.all { plans -> plans.values.all { it.points == 20 } })
        check(level20Plans.values.all { plans -> plans.values.toSet().size == ArenaAutoBuildPreset.entries.size })
        HeroClass.entries.forEach { heroClass ->
            ArenaAutoBuildPreset.entries.forEach { preset ->
                val signature = lowLevelSignatures.getValue(preset).groupingBy { it }.eachCount()
                val level10 = level10Plans.getValue(heroClass).getValue(preset)
                val level20 = level20Plans.getValue(heroClass).getValue(preset)
                check(signature.all { (slot, rank) -> level10.rank(slot) >= rank })
                check(level10.ranks.all { (slot, rank) -> level20.rank(slot) >= rank })
            }
        }
        check(competencyLevel10.values.all { it.points == 10 })
        check(competencyLevel10.values.toSet().size == ArenaAutoBuildPreset.entries.size)
        ArenaAutoBuildPreset.entries.forEach { preset ->
            val signature = lowLevelSignatures.getValue(preset).groupingBy { it }.eachCount()
            val level10 = competencyLevel10.getValue(preset)
            val level20 = competencyLevel20.getValue(preset)
            check(signature.all { (slot, rank) -> level10.rank(slot) >= rank })
            check(level10.ranks.all { (slot, rank) -> level20.rank(slot) >= rank })
        }
        check(releaseCheckpoints.all { (key, plan) ->
            key.level in setOf(25, 30) && plan.points == key.level
        })
        check(releaseCheckpoints.keys == buildSet {
            listOf(25, 30).forEach { level ->
                HeroClass.entries.forEach { heroClass ->
                    ArenaAutoBuildPreset.entries.forEach { preset ->
                        add(CheckpointKey(level, heroClass, preset))
                    }
                }
            }
        })
        check(fullOwnershipPointOrders.values.all { routes ->
            routes.values.all { it.size == ArenaSkillTreeRules.maxArenaLevel }
        })
        check(competencyPointOrders.values.all { it.size >= 30 })
    }

    fun get(preset: ArenaAutoBuildPreset): ArenaAutoBuildProfile = byPreset.getValue(preset)

    fun pointOrder(
        heroClass: HeroClass,
        preset: ArenaAutoBuildPreset,
        competencyOnly: Boolean,
    ): List<String> = if (competencyOnly) {
        competencyPointOrders.getValue(preset)
    } else {
        fullOwnershipPointOrders.getValue(heroClass).getValue(preset)
    }

    internal fun checkpointPlan(
        heroClass: HeroClass,
        preset: ArenaAutoBuildPreset,
        arenaLevel: Int,
        competencyOnly: Boolean = false,
    ): List<Pair<String, Int>> = pointOrder(heroClass, preset, competencyOnly)
        .take(arenaLevel)
        .groupingBy { it }
        .eachCount()
        .toList()
        .sortedBy { it.first }

    private fun buildFullRoute(
        heroClass: HeroClass,
        preset: ArenaAutoBuildPreset,
        level20: RankPlan,
    ): List<String> {
        val route = mutableListOf<String>()
        val ranks = mutableMapOf<String, Int>()
        appendTargetPlan(
            route,
            ranks,
            lowLevelSignatures.getValue(preset),
            level10Plans.getValue(heroClass).getValue(preset),
        )
        check(route.size == 10)
        appendTargetPlan(route, ranks, emptyList(), level20)
        check(route.size == 20)

        appendReleaseCheckpoint(heroClass, preset, 25, route, ranks)
        appendReleaseCheckpoint(heroClass, preset, 30, route, ranks)
        check(route.size == 30)

        // By Lv.40 every family has two complete competency roots. This shared competence floor
        // prevents thematic row-one choices from turning into deliberately weak opponents.
        val rootsByRank = ROOT_SLOTS.sortedWith(
            compareByDescending<String> { ranks[it] ?: 0 }.thenBy { rootTieOrder(preset).indexOf(it) },
        )
        rootsByRank.take(2).forEach { slot ->
            while ((ranks[slot] ?: 0) < ARENA_SKILL_TREE_MAX_RANK && route.size < 40) {
                appendKnownLegal(route, ranks, slot)
            }
        }
        while (route.size < 40) {
            appendPreferredPoint(
                heroClass = heroClass,
                route = route,
                ranks = ranks,
                // Once the shared competency floor is met, keep spending on this family's
                // authored branch. Falling back to root priority here made different families
                // converge to the same Lv.50 build.
                priority = continuationPriority(preset),
                supportCap = 3,
                rotation = route.size - 30,
            )
        }

        val continuation = continuationPriority(preset)
        while (route.size < ArenaSkillTreeRules.maxArenaLevel) {
            appendPreferredPoint(
                heroClass, route, ranks, continuation, supportCap = 3,
                rotation = route.size - 40,
            )
        }
        return route
    }

    private fun appendReleaseCheckpoint(
        heroClass: HeroClass,
        preset: ArenaAutoBuildPreset,
        level: Int,
        route: MutableList<String>,
        ranks: MutableMap<String, Int>,
    ) {
        check(level in setOf(25, 30) && route.size == level - 5)
        val target = releaseCheckpoints.getValue(CheckpointKey(level, heroClass, preset))
        appendCheckpointTarget(heroClass, preset, route, ranks, target)
        check(route.size == level)
    }

    private fun appendCheckpointTarget(
        heroClass: HeroClass,
        preset: ArenaAutoBuildPreset,
        route: MutableList<String>,
        ranks: MutableMap<String, Int>,
        target: RankPlan,
    ) {
        check(ranks.all { (slot, rank) -> rank <= target.rank(slot) }) {
            "$heroClass/$preset target removes an existing point: current=$ranks target=$target"
        }
        val nodes = ArenaSkillTreeCatalog.forClass(heroClass)
        val bySlot = nodes.associateBy(ArenaSkillTreeNodeDefinition::slotKey)
        val byId = nodes.associateBy(ArenaSkillTreeNodeDefinition::id)
        val priority = (releasePriority(heroClass, preset) + target.ranks.keys).distinct()
        while (route.size < target.points) {
            val offset = Math.floorMod(route.size, priority.size)
            val ordered = priority.drop(offset) + priority.take(offset)
            val selected = ordered.asSequence()
                .mapNotNull(bySlot::get)
                .firstOrNull { definition ->
                    val rank = ranks[definition.slotKey] ?: 0
                    rank < target.rank(definition.slotKey) &&
                        (rank > 0 || definition.isRoot || definition.parentAnyOf.any { parentId ->
                            (ranks[byId.getValue(parentId).slotKey] ?: 0) >= definition.minParentRank
                        }) &&
                        (rank > 0 || spentBeforeRow(nodes, ranks, definition.row) >=
                            definition.minimumSpentPoints)
                } ?: error(
                    "No legal checkpoint point for $heroClass/$preset at ${route.size + 1}: " +
                        "current=$ranks target=$target",
                )
            appendKnownLegal(route, ranks, selected.slotKey)
        }
        check(ranks == target.ranks) {
            "$heroClass/$preset checkpoint mismatch: current=$ranks target=$target"
        }
    }

    private fun buildCompetencyRoute(preset: ArenaAutoBuildPreset): List<String> {
        val route = mutableListOf<String>()
        val ranks = mutableMapOf<String, Int>()
        appendTargetPlan(
            route, ranks, lowLevelSignatures.getValue(preset), competencyLevel10.getValue(preset),
        )
        appendTargetPlan(route, ranks, emptyList(), competencyLevel20.getValue(preset))
        appendTargetPlan(route, ranks, emptyList(), competencyLevel25.getValue(preset))
        appendTargetPlan(route, ranks, emptyList(), competencyLevel30.getValue(preset))
        check(route.size == 30)
        // Only these four slots remain reachable when a Lv.10 projection owns no later attacks.
        val priority = rootTieOrder(preset) + "S01"
        while (route.size < 40) {
            val slot = priority.firstOrNull { (ranks[it] ?: 0) < ARENA_SKILL_TREE_MAX_RANK }
                ?: break
            appendKnownLegal(route, ranks, slot)
        }
        return route
    }

    private fun appendTargetPlan(
        route: MutableList<String>,
        ranks: MutableMap<String, Int>,
        prefix: List<String>,
        target: RankPlan,
    ) {
        prefix.forEach { slot ->
            check((ranks[slot] ?: 0) < target.rank(slot)) {
                "Signature $slot exceeds target $target"
            }
            appendKnownLegal(route, ranks, slot)
        }
        val rootOrder = ROOT_SLOTS.sortedByDescending(target::rank)
        (rootOrder + listOf("A04", "A05", "S01")).distinct().forEach { slot ->
            while ((ranks[slot] ?: 0) < target.rank(slot)) appendKnownLegal(route, ranks, slot)
        }
        check(target.ranks.all { (slot, rank) -> (ranks[slot] ?: 0) == rank })
    }

    private fun appendPreferredPoint(
        heroClass: HeroClass,
        route: MutableList<String>,
        ranks: MutableMap<String, Int>,
        priority: List<String>,
        supportCap: Int,
        rotation: Int = 0,
    ) {
        val nodes = ArenaSkillTreeCatalog.forClass(heroClass)
        val bySlot = nodes.associateBy(ArenaSkillTreeNodeDefinition::slotKey)
        val byId = nodes.associateBy(ArenaSkillTreeNodeDefinition::id)
        val offset = Math.floorMod(rotation, priority.size)
        val rotatedPriority = priority.drop(offset) + priority.take(offset)
        val candidates = (rotatedPriority + nodes.map(ArenaSkillTreeNodeDefinition::slotKey)).distinct()
            .mapNotNull(bySlot::get)
            .filter { definition ->
                val rank = ranks[definition.slotKey] ?: 0
                rank < definition.maxRank &&
                    (definition.kind != ArenaSkillNodeKind.SUPPORT || rank < supportCap) &&
                    (rank > 0 || definition.isRoot || definition.parentAnyOf.any { parentId ->
                        (ranks[byId.getValue(parentId).slotKey] ?: 0) >= definition.minParentRank
                    }) &&
                    (rank > 0 || spentBeforeRow(nodes, ranks, definition.row) >= definition.minimumSpentPoints)
            }
        val selected = candidates.firstOrNull()
            ?: error("No legal point remains for $heroClass at route point ${route.size + 1}")
        appendKnownLegal(route, ranks, selected.slotKey)
    }

    private fun spentBeforeRow(
        nodes: List<ArenaSkillTreeNodeDefinition>,
        ranks: Map<String, Int>,
        row: Int,
    ): Int = nodes.filter { it.row < row }.sumOf { ranks[it.slotKey] ?: 0 }

    private fun appendKnownLegal(
        route: MutableList<String>,
        ranks: MutableMap<String, Int>,
        slot: String,
    ) {
        check((ranks[slot] ?: 0) < ARENA_SKILL_TREE_MAX_RANK) { "$slot exceeds rank ten" }
        route += slot
        ranks[slot] = (ranks[slot] ?: 0) + 1
    }

    private fun continuationPriority(preset: ArenaAutoBuildPreset): List<String> {
        val theme = when (preset) {
            ArenaAutoBuildPreset.BALANCED -> slots("A04", "A05", "S01", "A06", "A07", "S02")
            ArenaAutoBuildPreset.SWIFT_ASSAULT -> slots("A04", "A06", "A08", "A10", "S01", "S02")
            ArenaAutoBuildPreset.HEAVY_ASSAULT -> slots("A05", "A07", "A09", "A11", "S03", "S01")
            ArenaAutoBuildPreset.CONTROL_PRESSURE -> slots("A05", "S01", "A07", "S03", "A09", "S04")
            ArenaAutoBuildPreset.DEFENSIVE_WARD -> slots("S01", "S02", "A04", "A06", "A08", "S04")
            ArenaAutoBuildPreset.SUSTAIN_RECOVERY -> slots("S01", "A06", "S04", "A12", "S05", "A05")
            ArenaAutoBuildPreset.DEFENSE_BREAKER -> slots("A05", "A07", "A09", "A13", "S04", "A03")
            ArenaAutoBuildPreset.STATUS_ATTRITION -> slots("A05", "A07", "S03", "A11", "A13", "S06")
            ArenaAutoBuildPreset.REACTIVE_COUNTER -> slots("A04", "S02", "A08", "A10", "S05", "A01")
            ArenaAutoBuildPreset.WILD_TACTICS ->
                slots("A04", "A05", "S01", "A07", "S03", "A09", "S02", "A08", "S04", "A11")
        }
        // appendPreferredPoint already appends the catalog as a deterministic legality fallback.
        // Keeping that fallback out of the rotating theme prevents rotation from promoting a
        // generic A01/A02 slot ahead of the family's authored branch.
        return theme.distinct()
    }

    private fun releasePriority(
        heroClass: HeroClass,
        preset: ArenaAutoBuildPreset,
    ): List<String> {
        // Keep the class in the signature: the point table is explicitly class-by-family even
        // where this neutral release phase currently shares one policy between classes.
        check(heroClass in HeroClass.entries)
        return releasePhasePriority.getValue(preset)
    }

    private fun rootTieOrder(preset: ArenaAutoBuildPreset): List<String> = when (preset) {
        ArenaAutoBuildPreset.SWIFT_ASSAULT, ArenaAutoBuildPreset.REACTIVE_COUNTER ->
            slots("A01", "A02", "A03")
        ArenaAutoBuildPreset.HEAVY_ASSAULT, ArenaAutoBuildPreset.DEFENSE_BREAKER,
        ArenaAutoBuildPreset.STATUS_ATTRITION -> slots("A03", "A02", "A01")
        else -> slots("A02", "A01", "A03")
    }

    private data class RankPlan(val ranks: Map<String, Int>) {
        val points: Int = ranks.values.sum()
        fun rank(slot: String): Int = ranks[slot] ?: 0
    }

    private fun rankPlan(
        a01: Int,
        a02: Int,
        a03: Int,
        a04: Int = 0,
        a05: Int = 0,
        a06: Int = 0,
        a07: Int = 0,
        s01: Int = 0,
        s02: Int = 0,
        s03: Int = 0,
        minimumRootPoints: Int = 12,
    ): RankPlan = RankPlan(buildMap {
        if (a01 > 0) put("A01", a01)
        if (a02 > 0) put("A02", a02)
        if (a03 > 0) put("A03", a03)
        if (a04 > 0) put("A04", a04)
        if (a05 > 0) put("A05", a05)
        if (a06 > 0) put("A06", a06)
        if (a07 > 0) put("A07", a07)
        if (s01 > 0) put("S01", s01)
        if (s02 > 0) put("S02", s02)
        if (s03 > 0) put("S03", s03)
    }.also { ranks ->
        require(ranks.values.all { it in 1..ARENA_SKILL_TREE_MAX_RANK })
        require(ranks.filterKeys(ROOT_SLOTS::contains).values.sum() >= minimumRootPoints)
    })

    private fun levelTenPlan(a01: Int, a02: Int, a03: Int): RankPlan =
        rankPlan(a01, a02, a03, minimumRootPoints = 10).also { plan ->
            require(plan.points == 10)
            require(plan.ranks.filterKeys(ROOT_SLOTS::contains).values.max() <= 6)
        }

    private data class CheckpointKey(
        val level: Int,
        val heroClass: HeroClass,
        val preset: ArenaAutoBuildPreset,
    )

    private fun plans(vararg values: RankPlan): Map<ArenaAutoBuildPreset, RankPlan> {
        require(values.size == ArenaAutoBuildPreset.entries.size)
        return ArenaAutoBuildPreset.entries.zip(values.asList()).toMap()
    }

    private fun profile(preset: ArenaAutoBuildPreset, legacy: List<String>) =
        ArenaAutoBuildProfile(preset, legacy.distinct())

    private fun branchLegacy(branch: String, indices: List<Int>): List<String> =
        indices.map { "$branch${it.toString().padStart(2, '0')}" } + "${branch}_CORE" +
            listOf("A", "B", "C").filterNot { it == branch }.flatMap { fallback ->
                indices.map { "$fallback${it.toString().padStart(2, '0')}" } + "${fallback}_CORE"
            }

    private fun interleavedLegacy(branches: List<String>, indices: List<Int>): List<String> =
        indices.flatMap { index -> branches.map { "$it${index.toString().padStart(2, '0')}" } } +
            branches.map { "${it}_CORE" }

    private fun balancedLegacy(): List<String> =
        interleavedLegacy(listOf("A", "B", "C"), listOf(1, 2, 3, 4, 5, 6, 7))

    private fun slots(vararg values: String): List<String> = values.asList()

}

internal fun arenaAutoBuildMix64(input: Long): Long {
    var value = input - 7046029254386353131L
    value = (value xor (value ushr 30)) * -4658895280553007687L
    value = (value xor (value ushr 27)) * -7723592293110705685L
    return value xor (value ushr 31)
}
