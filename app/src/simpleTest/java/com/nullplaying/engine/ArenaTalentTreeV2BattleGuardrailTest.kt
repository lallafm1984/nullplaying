package com.nullplaying.engine

import com.nullplaying.model.BattleBuildStats
import com.nullplaying.model.BattleCondition
import com.nullplaying.model.BattleGuidance
import com.nullplaying.model.BattleHeroClass
import com.nullplaying.model.BattleOutcome
import com.nullplaying.model.BattleProjectionSnapshot
import com.nullplaying.model.BattleRules
import com.nullplaying.model.BattleSkillKind
import com.nullplaying.model.BattleSkillSnapshot
import com.nullplaying.model.BattleTalentEffectTrace
import com.nullplaying.model.HERO_PATH_CATALOG_VERSION
import com.nullplaying.model.HERO_PATH_COUNTER_RULES_VERSION
import com.nullplaying.model.HeroPathAllocationTarget
import com.nullplaying.model.HeroPathBattleSnapshot
import com.nullplaying.model.HeroPathBranch
import com.nullplaying.model.HeroPathChoiceStance
import com.nullplaying.model.HeroPathCounterArchetype
import com.nullplaying.model.HeroPathEffectFamily
import com.nullplaying.model.HeroPathMatchupRelation
import com.nullplaying.model.HeroPathMutationStatus
import com.nullplaying.model.HeroPathNodeSlot
import com.nullplaying.model.HeroPathState
import com.nullplaying.model.ProjectionBattleResult
import com.nullplaying.model.UserInitiatedBattleRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ArenaTalentTreeV2BattleGuardrailTest {
    @Test
    fun `eighteen families form an antisymmetric six archetype cycle with exactly three counters`() {
        requireV2Catalog()
        val expectedFamilies = mapOf(
            HeroPathCounterArchetype.EXECUTION to setOf(
                HeroPathEffectFamily.RAGE_BURST,
                HeroPathEffectFamily.OPENING_EXECUTION,
                HeroPathEffectFamily.ELEMENTAL_BURST,
            ),
            HeroPathCounterArchetype.SUSTAIN to setOf(
                HeroPathEffectFamily.GRACEFUL_RECOVERY,
                HeroPathEffectFamily.PROVIDENT_REVERSAL,
                HeroPathEffectFamily.DAWN_CYCLE,
            ),
            HeroPathCounterArchetype.CONTROL to setOf(
                HeroPathEffectFamily.DECEPTIVE_CONTROL,
                HeroPathEffectFamily.CONTROLLED_HUNT,
                HeroPathEffectFamily.FORBIDDEN_GAMBIT,
            ),
            HeroPathCounterArchetype.PRECISION to setOf(
                HeroPathEffectFamily.FOCUSED_SHOT,
                HeroPathEffectFamily.MORALE_COMMAND,
                HeroPathEffectFamily.GRACE_JUDGMENT,
            ),
            HeroPathCounterArchetype.FORTRESS to setOf(
                HeroPathEffectFamily.IMPACT_GUARD,
                HeroPathEffectFamily.OATHED_GUARD,
                HeroPathEffectFamily.RETRIBUTIVE_COUNTER,
            ),
            HeroPathCounterArchetype.TEMPO to setOf(
                HeroPathEffectFamily.EVASIVE_CHAIN,
                HeroPathEffectFamily.MOBILE_VOLLEY,
                HeroPathEffectFamily.ARCANE_CYCLE,
            ),
        )
        val actualFamilies = HeroPathCatalog.branches.groupBy { it.counterArchetype }
            .mapValues { (_, branches) -> branches.map { it.effectFamily }.toSet() }
        assertEquals(expectedFamilies, actualFamilies)

        val favorableCycle = mapOf(
            HeroPathCounterArchetype.EXECUTION to HeroPathCounterArchetype.SUSTAIN,
            HeroPathCounterArchetype.SUSTAIN to HeroPathCounterArchetype.CONTROL,
            HeroPathCounterArchetype.CONTROL to HeroPathCounterArchetype.PRECISION,
            HeroPathCounterArchetype.PRECISION to HeroPathCounterArchetype.FORTRESS,
            HeroPathCounterArchetype.FORTRESS to HeroPathCounterArchetype.TEMPO,
            HeroPathCounterArchetype.TEMPO to HeroPathCounterArchetype.EXECUTION,
        )
        HeroPathCatalog.branches.forEach { attacker ->
            val relations = HeroPathCatalog.branches.groupBy { defender ->
                HeroPathCatalog.matchupRelation(attacker.branch, defender.branch)
            }
            assertEquals("${attacker.branch} favorable count", 3, relations[HeroPathMatchupRelation.FAVORABLE]?.size)
            assertEquals("${attacker.branch} unfavorable count", 3, relations[HeroPathMatchupRelation.UNFAVORABLE]?.size)
            assertEquals("${attacker.branch} neutral count", 12, relations[HeroPathMatchupRelation.NEUTRAL]?.size)
            assertTrue(relations.getValue(HeroPathMatchupRelation.FAVORABLE).all {
                it.counterArchetype == favorableCycle.getValue(attacker.counterArchetype)
            })
            HeroPathCatalog.branches.forEach { defender ->
                val forward = HeroPathCatalog.matchupRelation(attacker.branch, defender.branch)
                val reverse = HeroPathCatalog.matchupRelation(defender.branch, attacker.branch)
                assertEquals("$attacker vs $defender must be antisymmetric", forward.opposite(), reverse)
            }
        }
    }

    @Test
    fun `snapshot pins counter version dominant branch core priority tie break and choice stance`() {
        val branches = HeroPathCatalog.branchesFor(BattleHeroClass.WARRIOR).map { it.branch }
        val coreRanks = mergeRanks(branchRanks(branches[0], 10), branchRanks(branches[1], 9), branchRanks(branches[2], 1))
        val coreId = coreRanks.keys.single { HeroPathCatalog.byNodeId.getValue(it).slot == HeroPathNodeSlot.CORE }
        val coreSnapshot = HeroPathEngine.battleSnapshotFromRanks(
            BattleHeroClass.WARRIOR,
            coreRanks,
            activeCoreNodeId = coreId,
        )
        assertEquals(HERO_PATH_COUNTER_RULES_VERSION, coreSnapshot.counterRulesVersion)
        assertEquals(branches[0], coreSnapshot.dominantBranch)
        assertEquals(HeroPathChoiceStance.A, coreSnapshot.choiceStance)

        val investmentRanks = mergeRanks(branchRanks(branches[0], 7), branchRanks(branches[1], 6), branchRanks(branches[2], 5))
        val investmentSnapshot = HeroPathEngine.battleSnapshotFromRanks(BattleHeroClass.WARRIOR, investmentRanks)
        assertEquals(branches[0], investmentSnapshot.dominantBranch)
        assertEquals(HeroPathChoiceStance.A, investmentSnapshot.choiceStance)

        val tiedBranches = branches.take(2)
        val tiedRanks = mergeRanks(branchRanks(tiedBranches[0], 7, choiceB = true), branchRanks(tiedBranches[1], 7))
        val tiedSnapshot = HeroPathEngine.battleSnapshotFromRanks(BattleHeroClass.WARRIOR, tiedRanks)
        assertEquals(tiedBranches.minBy { it.name }, tiedSnapshot.dominantBranch)
        val expectedStance = if (tiedSnapshot.dominantBranch == tiedBranches[0]) HeroPathChoiceStance.B else HeroPathChoiceStance.A
        assertEquals(expectedStance, tiedSnapshot.choiceStance)
    }

    @Test
    fun `six representative builds per specialization are legal distinct and preserve point invariants`() {
        requireV2Catalog()

        HeroPathCatalog.branches.forEach { specialization ->
            val classBranches = HeroPathCatalog.branchesFor(specialization.heroClass).map { it.branch }
            val primary = specialization.branch
            val support = classBranches.first { it != primary }
            val third = classBranches.first { it != primary && it != support }
            val builds = listOf(
                branchRanks(primary, 10, choiceB = false),
                branchRanks(primary, 10, choiceB = true),
                mergeRanks(branchRanks(primary, 10), branchRanks(support, 9), branchRanks(third, 1)),
                mergeRanks(branchRanks(primary, 10), branchRanks(support, 7), branchRanks(third, 3)),
                mergeRanks(branchRanks(primary, 7), branchRanks(support, 7), branchRanks(third, 6)),
                mergeRanks(branchRanks(primary, 9), branchRanks(support, 6), branchRanks(third, 5)),
            )
            assertEquals("${specialization.branch} requires six distinct representative builds", 6, builds.distinct().size)

            builds.forEachIndexed { index, ranks ->
                val base = HeroPathEngine.reconcilePointGrants(
                    HeroPathState(heroClass = specialization.heroClass, revision = index.toLong()),
                    heroLevel = 100,
                )
                val core = ranks.keys.singleOrNull { HeroPathCatalog.byNodeId.getValue(it).slot == HeroPathNodeSlot.CORE }.orEmpty()
                val result = HeroPathEngine.applyAllocation(
                    base,
                    HeroPathAllocationTarget(base.revision, ranks, activeCoreNodeId = core),
                )

                assertEquals("${specialization.branch} representative build $index", HeroPathMutationStatus.APPLIED, result.status)
                assertEquals(ranks.values.sum().toLong(), result.state.spentPoints)
                assertEquals(result.state.earnedPoints, result.state.spentPoints + result.state.unspentPoints)
                assertTrue(result.state.spentPoints <= 20L)
            }
        }
    }

    @Test
    fun `special effects are deterministic non-recursive and remain inside all hard caps`() {
        requireV2Catalog()
        var observedEffects = 0
        val specializations = HeroPathCatalog.branches

        specializations.forEachIndexed { index, userSpec ->
            val opponentSpec = specializations[(index + 7) % specializations.size]
            repeat(24) { offset ->
                val request = request(
                    seed = 10_000L + index * 100L + offset,
                    user = projection(userSpec.branch, "user-$index", choiceB = offset % 2 == 1),
                    opponent = projection(opponentSpec.branch, "opponent-$index", choiceB = offset % 3 == 1),
                )
                val first = ProjectionBattleEngine.simulate(request)
                val replay = ProjectionBattleEngine.simulate(request)
                assertEquals("same snapshot and seed must replay exactly", first, replay)
                assertTrue("40-round safeguard exceeded", first.rounds.size <= 40)

                val legalNodeIds = (first.user.heroPathBattleSnapshot.nodes + first.opponent.heroPathBattleSnapshot.nodes)
                    .map { it.nodeId }
                    .toSet()
                val userEffects = first.rounds.flatMap { it.userAction.talentEffects }
                val opponentEffects = first.rounds.flatMap { it.opponentAction.talentEffects }
                val roundEffects = first.rounds.flatMap { it.talentEffects }
                observedEffects += (userEffects + opponentEffects + roundEffects).size

                (userEffects + opponentEffects + roundEffects).forEach { effect ->
                    assertEquals("effect recursion is forbidden", 0, effect.triggerDepth)
                    assertTrue("trace must name an allocated node: $effect", effect.sourceNodeId in legalNodeIds)
                    val node = HeroPathCatalog.byNodeId.getValue(effect.sourceNodeId)
                    assertEquals(node.effectFamily, effect.effectFamily)
                    assertEquals(node.effectStage, effect.effectStage)
                    assertTrue(effect.resourceRemoved in 0..1)
                    assertTrue(effect.chargeBefore in 0..3)
                    assertTrue(effect.chargeAfter in 0..3)
                    assertTrue(effect.extraActionOrdinal in 0..3)
                }
                userEffects.forEach { effect ->
                    assertCounterTrace(effect, first.user.heroPathBattleSnapshot, first.opponent.heroPathBattleSnapshot)
                }
                opponentEffects.forEach { effect ->
                    assertCounterTrace(effect, first.opponent.heroPathBattleSnapshot, first.user.heroPathBattleSnapshot)
                }
                roundEffects.forEach { effect ->
                    val userOwnsNode = first.user.heroPathBattleSnapshot.nodes.any { it.nodeId == effect.sourceNodeId }
                    if (userOwnsNode) {
                        assertCounterTrace(effect, first.user.heroPathBattleSnapshot, first.opponent.heroPathBattleSnapshot)
                    } else {
                        assertCounterTrace(effect, first.opponent.heroPathBattleSnapshot, first.user.heroPathBattleSnapshot)
                    }
                }

                assertSideCaps(first.user.maxHp, first.opponent.maxHp, first.rounds.map { it.userAction.talentEffects })
                assertSideCaps(first.opponent.maxHp, first.user.maxHp, first.rounds.map { it.opponentAction.talentEffects })
            }
        }

        assertTrue("V2 allocations must produce test-visible talent effect evidence", observedEffects > 0)
    }

    @Test
    fun `foundation ranks change class charge behavior for all six classes`() {
        requireV2Catalog()
        BattleHeroClass.entries.forEach { heroClass ->
            val branch = HeroPathCatalog.branchesFor(heroClass).first().branch
            val nodes = HeroPathCatalog.nodesFor(branch).associateBy { it.slot }
            val foundationA = nodes.getValue(HeroPathNodeSlot.FOUNDATION_A).nodeId
            val foundationB = nodes.getValue(HeroPathNodeSlot.FOUNDATION_B).nodeId
            val specialA = nodes.getValue(HeroPathNodeSlot.SPECIAL_A).nodeId
            val opponent = projectionWithRanks(branch, "foundation-opponent-$heroClass", emptyMap())

            val foundationASignatures = (0..2).map { rank ->
                chargeSignature(
                    actor = projectionWithRanks(
                        branch,
                        "foundation-a-$heroClass",
                        if (rank == 0) emptyMap() else mapOf(foundationA to rank),
                    ),
                    opponent = opponent,
                )
            }
            assertTrue(
                "$heroClass foundation A rank 0/1/2 must produce distinct charge behavior",
                foundationASignatures.distinct().size == 3,
            )

            val foundationBSignatures = (0..2).map { rank ->
                val ranks = buildMap {
                    put(foundationA, 2)
                    put(specialA, 1)
                    if (rank > 0) put(foundationB, rank)
                }
                chargeSignature(
                    actor = projectionWithRanks(branch, "foundation-b-$heroClass", ranks),
                    opponent = opponent,
                )
            }
            assertTrue(
                "$heroClass foundation B rank 0/1/2 must produce distinct charge behavior",
                foundationBSignatures.distinct().size == 3,
            )
        }
    }

    @Test
    fun `forbidden gambit advanced ranks increase landed damage while preserving resource removal`() {
        requireV2Catalog()
        val branch = HeroPathBranch.MAGE_FORBIDDEN
        val nodes = HeroPathCatalog.nodesFor(branch).associateBy { it.slot }
        val baseRanks = mapOf(
            nodes.getValue(HeroPathNodeSlot.FOUNDATION_A).nodeId to 2,
            nodes.getValue(HeroPathNodeSlot.FOUNDATION_B).nodeId to 2,
            nodes.getValue(HeroPathNodeSlot.SPECIAL_A).nodeId to 1,
        )
        val advancedNode = nodes.getValue(HeroPathNodeSlot.ADVANCED_TACTIC).nodeId
        val opponent = projectionWithRanks(branch, "forbidden-opponent", emptyMap())
        val damageByRank = (0..2).map { rank ->
            val ranks = if (rank == 0) baseRanks else baseRanks + (advancedNode to rank)
            val actor = projectionWithRanks(branch, "forbidden-actor", ranks)
            val landedEffects = (1L..256L).mapNotNull { seed ->
                ProjectionBattleEngine.simulate(request(seed, actor, opponent)).rounds
                    .asSequence()
                    .flatMap { it.userAction.talentEffects.asSequence() }
                    .firstOrNull { it.effectFamily == HeroPathEffectFamily.FORBIDDEN_GAMBIT && it.extraDamage > 0 }
            }
            assertTrue("Forbidden rank $rank must produce landed test evidence", landedEffects.size >= 100)
            assertTrue("Forbidden rank $rank must remove exactly one resource on landed effects", landedEffects.all { it.resourceRemoved == 1 })
            landedEffects.map { it.extraDamage }.average()
        }
        println("arena-v2-forbidden-advanced averageDamage=$damageByRank")
        assertTrue("Forbidden ADV 0/1/2 damage must increase: $damageByRank", damageByRank.zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun `ordered specialization smoke matrix blocks first-turn kills and pathological fight length`() {
        requireV2Catalog()
        val lengths = mutableListOf<Int>()
        var firstTurnKnockouts = 0
        val specs = HeroPathCatalog.branches
        val specializationResults = specs.associate { it.branch to WinLoss() }.toMutableMap()
        val classResults = BattleHeroClass.entries.associateWith { WinLoss() }.toMutableMap()
        val matchupResults = mutableMapOf<Pair<HeroPathBranch, HeroPathBranch>, WinLoss>()
        val activeCounterMatchupResults = mutableMapOf<Pair<HeroPathBranch, HeroPathBranch>, WinLoss>()
        val inactiveCounterMatchupResults = mutableMapOf<Pair<HeroPathBranch, HeroPathBranch>, WinLoss>()
        val sideSwapResults = mutableMapOf<Pair<HeroPathBranch, HeroPathBranch>, SideSwapResult>()
        val telemetry = mutableMapOf<ChargeTelemetryKey, ChargeTelemetry>()
        val seedsPerCell = matrixSeedsPerCell()

        specs.forEachIndexed { userIndex, userSpec ->
            specs.forEachIndexed { opponentIndex, opponentSpec ->
                repeat(seedsPerCell) { seedIndex ->
                    val cell = userIndex * specs.size + opponentIndex
                    val (battle, swappedBattle) = simulateMatrixPair(
                        userBranch = userSpec.branch,
                        opponentBranch = opponentSpec.branch,
                        cell = cell,
                        seedIndex = seedIndex,
                    )
                    sideSwapResults.getOrPut(
                        userSpec.branch to opponentSpec.branch,
                        ::SideSwapResult,
                    ).record(
                        originalAsUser = battle.outcome,
                        originalAsOpponent = swappedBattle.outcome.opposite(),
                    )
                    lengths += listOf(battle.rounds.size, swappedBattle.rounds.size)
                    specializationResults.getValue(userSpec.branch).record(battle.outcome)
                    specializationResults.getValue(userSpec.branch).record(swappedBattle.outcome.opposite())
                    specializationResults.getValue(opponentSpec.branch).record(battle.outcome.opposite())
                    specializationResults.getValue(opponentSpec.branch).record(swappedBattle.outcome)
                    classResults.getValue(userSpec.heroClass).record(battle.outcome)
                    classResults.getValue(userSpec.heroClass).record(swappedBattle.outcome.opposite())
                    classResults.getValue(opponentSpec.heroClass).record(battle.outcome.opposite())
                    classResults.getValue(opponentSpec.heroClass).record(swappedBattle.outcome)
                    matchupResults.getOrPut(userSpec.branch to opponentSpec.branch, ::WinLoss).apply {
                        record(battle.outcome)
                        record(swappedBattle.outcome.opposite())
                    }
                    val userPoints = matrixStratum(seedIndex, cell, opponentSide = false).points
                    val opponentPoints = matrixStratum(seedIndex, cell, opponentSide = true).points
                    val userRelation = HeroPathCatalog.matchupRelation(userSpec.branch, opponentSpec.branch)
                    val userTelemetry = telemetry.getOrPut(ChargeTelemetryKey(userSpec.heroClass, userPoints, userRelation), ::ChargeTelemetry)
                    val opponentTelemetry = telemetry.getOrPut(ChargeTelemetryKey(opponentSpec.heroClass, opponentPoints, userRelation.opposite()), ::ChargeTelemetry)
                    userTelemetry.record(battle, userSide = true)
                    userTelemetry.record(swappedBattle, userSide = false)
                    opponentTelemetry.record(battle, userSide = false)
                    opponentTelemetry.record(swappedBattle, userSide = true)
                    val layerResults = if (userPoints in activeCounterPointBands) {
                        activeCounterMatchupResults
                    } else {
                        inactiveCounterMatchupResults
                    }
                    layerResults.getOrPut(userSpec.branch to opponentSpec.branch, ::WinLoss).apply {
                        record(battle.outcome)
                        record(swappedBattle.outcome.opposite())
                    }
                    firstTurnKnockouts += listOf(battle, swappedBattle).count(::isFirstTurnKnockout)
                }
            }
        }

        assertEquals(324, matchupResults.size)
        assertEquals(324, sideSwapResults.size)
        sideSwapResults.forEach { (pair, result) ->
            assertEquals("side swap $pair forward base sample count", seedsPerCell, result.originalAsUser.total())
            assertEquals("side swap $pair mirrored base sample count", seedsPerCell, result.originalAsOpponent.total())
        }

        val baseSideDifferences = sideSwapResults.mapValues { (_, result) -> result.difference() }
        val baseMatchupRates = matchupResults.mapValues { (_, result) -> result.rate() }
        val baseActiveCounterRates = activeCounterMatchupResults.mapValues { (_, result) -> result.rate() }
        val canonicalPairs = HeroPathCatalog.branches.flatMapIndexed { index, attacker ->
            HeroPathCatalog.branches.drop(index + 1).map { defender -> attacker.branch to defender.branch }
        }
        val baseReciprocalDifferences = canonicalPairs.associateWith { pair ->
            kotlin.math.abs(baseMatchupRates.getValue(pair) + baseMatchupRates.getValue(pair.second to pair.first) - 1.0)
        }
        val confirmationCells = mutableSetOf<Pair<HeroPathBranch, HeroPathBranch>>()
        if (seedsPerCell in MATRIX_BASE_SEEDS_PER_CELL until MATRIX_CONFIRMATION_SEEDS_PER_CELL) {
            confirmationCells += baseSideDifferences.filterValues { it >= SIDE_CONFIRMATION_TRIGGER }.keys
            baseReciprocalDifferences.filterValues { it >= RECIPROCAL_CONFIRMATION_TRIGGER }.keys.forEach { pair ->
                confirmationCells += pair
                confirmationCells += pair.second to pair.first
            }
            baseActiveCounterRates.forEach { (pair, rate) ->
                val relation = HeroPathCatalog.matchupRelation(pair.first, pair.second)
                val relationBoundary = when (relation) {
                    HeroPathMatchupRelation.FAVORABLE -> FAVORABLE_WIN_RATE
                    HeroPathMatchupRelation.UNFAVORABLE -> UNFAVORABLE_WIN_RATE
                    HeroPathMatchupRelation.NEUTRAL -> null
                }
                if ((relationBoundary != null && nearBoundary(rate, relationBoundary, MATCHUP_CONFIRMATION_MARGIN)) ||
                    nearBoundary(rate, 0.35, CELL_CONFIRMATION_MARGIN) ||
                    nearBoundary(rate, 0.65, CELL_CONFIRMATION_MARGIN)) {
                    confirmationCells += pair
                }
            }
            specs.forEach { specialization ->
                val rates = specs.filter { it.branch != specialization.branch }.mapNotNull { opponent ->
                    baseActiveCounterRates[specialization.branch to opponent.branch]
                }
                if (rates.isEmpty()) return@forEach
                val mad = rates.map { kotlin.math.abs(it - 0.5) }.average()
                val deviation = standardDeviation(rates)
                if (nearBoundary(mad, MIN_SPEC_MAD, FLATNESS_MAD_CONFIRMATION_MARGIN) ||
                    nearBoundary(mad, MAX_SPEC_MAD, FLATNESS_MAD_CONFIRMATION_MARGIN) ||
                    nearBoundary(deviation, MIN_SPEC_STANDARD_DEVIATION, FLATNESS_SD_CONFIRMATION_MARGIN) ||
                    nearBoundary(deviation, MAX_SPEC_STANDARD_DEVIATION, FLATNESS_SD_CONFIRMATION_MARGIN)) {
                    specs.filter { it.branch != specialization.branch }.forEach { opponent ->
                        confirmationCells += specialization.branch to opponent.branch
                    }
                }
            }
            confirmationCells.toList().forEach { pair ->
                confirmationCells += pair.second to pair.first
            }
        }
        val specIndexes = specs.mapIndexed { index, spec -> spec.branch to index }.toMap()
        confirmationCells.forEach { pair ->
            val userIndex = specIndexes.getValue(pair.first)
            val opponentIndex = specIndexes.getValue(pair.second)
            val cell = userIndex * specs.size + opponentIndex
            val result = sideSwapResults.getValue(pair)
            (seedsPerCell until MATRIX_CONFIRMATION_SEEDS_PER_CELL).forEach { seedIndex ->
                val (battle, swappedBattle) = simulateMatrixPair(
                    userBranch = pair.first,
                    opponentBranch = pair.second,
                    cell = cell,
                    seedIndex = seedIndex,
                )
                result.record(
                    originalAsUser = battle.outcome,
                    originalAsOpponent = swappedBattle.outcome.opposite(),
                )
                matchupResults.getValue(pair).apply {
                    record(battle.outcome)
                    record(swappedBattle.outcome.opposite())
                }
                val userPoints = matrixStratum(seedIndex, cell, opponentSide = false).points
                val layerResults = if (userPoints in activeCounterPointBands) {
                    activeCounterMatchupResults
                } else {
                    inactiveCounterMatchupResults
                }
                layerResults.getOrPut(pair, ::WinLoss).apply {
                    record(battle.outcome)
                    record(swappedBattle.outcome.opposite())
                }
            }
            assertEquals(MATRIX_CONFIRMATION_SEEDS_PER_CELL, result.originalAsUser.total())
            assertEquals(MATRIX_CONFIRMATION_SEEDS_PER_CELL, result.originalAsOpponent.total())
            assertEquals(MATRIX_CONFIRMATION_SEEDS_PER_CELL * 2, matchupResults.getValue(pair).total())
        }

        val sorted = lengths.sorted()
        val median = percentile(sorted, 0.50)
        val p90 = percentile(sorted, 0.90)
        val p99 = percentile(sorted, 0.99)
        val firstTurnRate = firstTurnKnockouts.toDouble() / lengths.size.toDouble()
        val safeguardRate = lengths.count { it >= 40 }.toDouble() / lengths.size.toDouble()
        val maxSideDifference = sideSwapResults.maxOf { (_, result) -> result.difference() }
        val baseMaxSideDifference = baseSideDifferences.maxOf { (_, difference) -> difference }
        val reciprocalDifferences = canonicalPairs.associateWith { pair ->
            kotlin.math.abs(matchupResults.getValue(pair).rate() + matchupResults.getValue(pair.second to pair.first).rate() - 1.0)
        }
        val maxReciprocalDifference = reciprocalDifferences.maxOf { (_, difference) -> difference }
        val baseMaxReciprocalDifference = baseReciprocalDifferences.maxOf { (_, difference) -> difference }
        val matchupLow = matchupResults.minBy { (_, result) -> result.rate() }
        val matchupHigh = matchupResults.maxBy { (_, result) -> result.rate() }
        println("arena-v2-matrix cells=324 seedsPerCell=$seedsPerCell battles=${lengths.size} pairedSideBattles=${sideSwapResults.values.sumOf { it.total() * 2 }} confirmedCells=${confirmationCells.size} turn1Ko=$firstTurnRate median=$median p90=$p90 p99=$p99 round40=$safeguardRate matchupLow=${matchupLow.key}:${matchupLow.value.rate()} matchupHigh=${matchupHigh.key}:${matchupHigh.value.rate()} baseMaxSideDiff=$baseMaxSideDifference maxSideDiff=$maxSideDifference baseMaxReciprocalDiff=$baseMaxReciprocalDifference maxReciprocalDiff=$maxReciprocalDifference")
        val orderedMatchups = matchupResults.entries.sortedBy { (_, result) -> result.rate() }
        val orderedSideSwaps = sideSwapResults.entries.sortedByDescending { (_, result) -> result.difference() }
        println("arena-v2-matchup-low " + orderedMatchups.take(5).joinToString { (pair, result) -> "$pair=${result.rate()}" })
        println("arena-v2-matchup-high " + orderedMatchups.takeLast(5).joinToString { (pair, result) -> "$pair=${result.rate()}" })
        println("arena-v2-side-high " + orderedSideSwaps.take(5).joinToString { (pair, result) -> "$pair=${result.difference()}" })
        println("arena-v2-class " + classResults.entries.joinToString { (heroClass, result) -> "$heroClass=${result.rate()}" })
        println("arena-v2-spec " + specializationResults.entries.joinToString { (specialization, result) -> "$specialization=${result.rate()}" })
        // Diagnostic-only base samples. Adaptive boundary confirmations never reweight telemetry.
        telemetry.forEach { (key, value) -> println("arena-v3-charge $key ${value.describe()}") }
        if (activeCounterMatchupResults.size == 324 && inactiveCounterMatchupResults.size == 324) specs.forEach { specialization ->
            val rates = specs.asSequence()
                .filter { it.branch != specialization.branch }
                .associate { opponent ->
                    opponent.branch to activeCounterMatchupResults.getValue(specialization.branch to opponent.branch).rate()
                }
            val favorableRates = rates.filterKeys { opponent ->
                HeroPathCatalog.matchupRelation(specialization.branch, opponent) == HeroPathMatchupRelation.FAVORABLE
            }.values
            val unfavorableRates = rates.filterKeys { opponent ->
                HeroPathCatalog.matchupRelation(specialization.branch, opponent) == HeroPathMatchupRelation.UNFAVORABLE
            }.values
            val mad = rates.values.map { kotlin.math.abs(it - 0.5) }.average()
            val deviation = standardDeviation(rates.values)
            println(
                "arena-v2-texture-active-diagnostic ${specialization.branch} " +
                    "favorableMean=${favorableRates.average()} favorableMin=${favorableRates.min()} favorablePass=${favorableRates.count { it >= FAVORABLE_WIN_RATE }}/3 " +
                    "unfavorableMean=${unfavorableRates.average()} unfavorableMax=${unfavorableRates.max()} unfavorablePass=${unfavorableRates.count { it <= UNFAVORABLE_WIN_RATE }}/3 " +
                    "mad=$mad sd=$deviation",
            )
        }
        if (activeCounterMatchupResults.size == 324 && inactiveCounterMatchupResults.size == 324) {
            val inactiveFavorable = inactiveCounterMatchupResults.filterKeys { pair ->
                HeroPathCatalog.matchupRelation(pair.first, pair.second) == HeroPathMatchupRelation.FAVORABLE
            }.values.map(WinLoss::rate)
            val inactiveUnfavorable = inactiveCounterMatchupResults.filterKeys { pair ->
                HeroPathCatalog.matchupRelation(pair.first, pair.second) == HeroPathMatchupRelation.UNFAVORABLE
            }.values.map(WinLoss::rate)
            println(
                "arena-v2-texture-inactive-neutral favorableMean=${inactiveFavorable.average()} " +
                    "unfavorableMean=${inactiveUnfavorable.average()} samplesPerDirection=${inactiveFavorable.size}",
            )
        } else {
            println("arena-v2-texture-layer-diagnostic skipped: use at least six seeds per cell for full point-band coverage")
        }
        if (confirmationCells.isNotEmpty()) {
            println("arena-v2-confirmed " + confirmationCells.joinToString { pair ->
                "$pair=side:${baseSideDifferences.getValue(pair)}->${sideSwapResults.getValue(pair).difference()},matchup:${baseMatchupRates.getValue(pair)}->${matchupResults.getValue(pair).rate()}"
            })
        }

        assertTrue("first-turn KO rate $firstTurnRate exceeds 0.1%", firstTurnRate <= 0.001)
        assertTrue("median $median must remain in 7..12", median in 7..12)
        assertTrue("p90 $p90 exceeds 20", p90 <= 20)
        assertTrue("p99 $p99 exceeds 30", p99 <= 30)
        assertTrue("40-round safeguard rate $safeguardRate exceeds 0.01%", safeguardRate <= 0.0001)

        if (seedsPerCell >= 10_000) {
            assertEquals("active counter layer must cover every ordered cell", 324, activeCounterMatchupResults.size)
            assertEquals("inactive counter layer must cover every ordered cell", 324, inactiveCounterMatchupResults.size)
            classResults.forEach { (heroClass, result) ->
                assertTrue("$heroClass win rate ${result.rate()} must be inside 47..53%", result.rate() in 0.47..0.53)
            }
            specializationResults.forEach { (specialization, result) ->
                assertTrue("$specialization win rate ${result.rate()} must be inside 45..55%", result.rate() in 0.45..0.55)
            }
            matchupResults.forEach { (pair, result) ->
                assertTrue("matchup $pair win rate ${result.rate()} must be inside 35..65%", result.rate() in 0.35..0.65)
            }
            specs.forEach { specialization ->
                val rates = specs.asSequence()
                    .filter { it.branch != specialization.branch }
                    .associate { opponent ->
                        opponent.branch to activeCounterMatchupResults.getValue(specialization.branch to opponent.branch).rate()
                    }
                val favorable = rates.filterKeys { opponent ->
                    HeroPathCatalog.matchupRelation(specialization.branch, opponent) == HeroPathMatchupRelation.FAVORABLE
                }
                val unfavorable = rates.filterKeys { opponent ->
                    HeroPathCatalog.matchupRelation(specialization.branch, opponent) == HeroPathMatchupRelation.UNFAVORABLE
                }
                val deviations = rates.values.map { it - 0.5 }
                val mad = deviations.map { kotlin.math.abs(it) }.average()
                val standardDeviation = standardDeviation(rates.values)
                println("arena-v2-texture-active ${specialization.branch} favorable=${favorable.count { it.value >= FAVORABLE_WIN_RATE }} unfavorable=${unfavorable.count { it.value <= UNFAVORABLE_WIN_RATE }} mad=$mad sd=$standardDeviation")
                assertTrue(
                    "${specialization.branch} needs at least three favorable matchups: $favorable",
                    favorable.count { it.value >= FAVORABLE_WIN_RATE } >= 3,
                )
                assertTrue(
                    "${specialization.branch} needs at least three unfavorable matchups: $unfavorable",
                    unfavorable.count { it.value <= UNFAVORABLE_WIN_RATE } >= 3,
                )
                assertTrue("${specialization.branch} MAD $mad is too flat or too polarized", mad in MIN_SPEC_MAD..MAX_SPEC_MAD)
                assertTrue(
                    "${specialization.branch} SD $standardDeviation is too flat or too polarized",
                    standardDeviation in MIN_SPEC_STANDARD_DEVIATION..MAX_SPEC_STANDARD_DEVIATION,
                )
                val selfRate = matchupResults.getValue(specialization.branch to specialization.branch).rate()
                assertTrue("${specialization.branch} self matchup $selfRate must be neutral", selfRate in 0.485..0.515)
            }
            val canonicalRates = canonicalPairs.map { pair ->
                (activeCounterMatchupResults.getValue(pair).rate() +
                    (1.0 - activeCounterMatchupResults.getValue(pair.second to pair.first).rate())) / 2.0
            }
            val globalMad = canonicalRates.map { kotlin.math.abs(it - 0.5) }.average()
            val globalStandardDeviation = standardDeviation(canonicalRates)
            assertTrue("global matchup MAD $globalMad is too flat or too polarized", globalMad in MIN_GLOBAL_MAD..MAX_GLOBAL_MAD)
            assertTrue(
                "global matchup SD $globalStandardDeviation is too flat or too polarized",
                globalStandardDeviation in MIN_GLOBAL_STANDARD_DEVIATION..MAX_GLOBAL_STANDARD_DEVIATION,
            )
            val inactiveFavorableMean = inactiveCounterMatchupResults.filterKeys { pair ->
                HeroPathCatalog.matchupRelation(pair.first, pair.second) == HeroPathMatchupRelation.FAVORABLE
            }.values.map(WinLoss::rate).average()
            val inactiveUnfavorableMean = inactiveCounterMatchupResults.filterKeys { pair ->
                HeroPathCatalog.matchupRelation(pair.first, pair.second) == HeroPathMatchupRelation.UNFAVORABLE
            }.values.map(WinLoss::rate).average()
            assertTrue(
                "inactive 0/1/5-point favorable layer $inactiveFavorableMean must remain neutral",
                kotlin.math.abs(inactiveFavorableMean - 0.5) <= MAX_INACTIVE_COUNTER_BIAS,
            )
            assertTrue(
                "inactive 0/1/5-point unfavorable layer $inactiveUnfavorableMean must remain neutral",
                kotlin.math.abs(inactiveUnfavorableMean - 0.5) <= MAX_INACTIVE_COUNTER_BIAS,
            )
            reciprocalDifferences.forEach { (pair, difference) ->
                assertTrue("reciprocal matchup $pair differs by ${difference * 100}%p", difference <= MAX_RECIPROCAL_DIFFERENCE)
            }
            sideSwapResults.forEach { (pair, result) ->
                val sideDifference = result.difference()
                assertTrue(
                    "paired side swap $pair differs by ${sideDifference * 100}%p " +
                        "(${result.originalAsUser.rate()} as user vs ${result.originalAsOpponent.rate()} as opponent)",
                    sideDifference <= 0.015,
                )
            }
        }
    }

    @Test
    fun `choice A and B create bounded counterplay without a dominant stance`() {
        requireV2Catalog()
        val seedsPerCell = matrixSeedsPerCell()
        val specs = HeroPathCatalog.branches
        val results = mutableMapOf<Pair<HeroPathBranch, HeroPathBranch>, ChoiceComparison>()

        specs.forEachIndexed { userIndex, userSpec ->
            specs.forEachIndexed { opponentIndex, opponentSpec ->
                if (userIndex != opponentIndex) {
                    val pair = userSpec.branch to opponentSpec.branch
                    val cell = userIndex * specs.size + opponentIndex
                    repeat(seedsPerCell) { seedIndex ->
                        results.getOrPut(pair, ::ChoiceComparison).record(
                            simulateChoicePair(userSpec.branch, opponentSpec.branch, cell, seedIndex),
                        )
                    }
                }
            }
        }

        assertEquals(18 * 17, results.size)
        results.forEach { (pair, result) ->
            assertEquals("choice A base samples $pair", seedsPerCell * 2, result.choiceA.total())
            assertEquals("choice B base samples $pair", seedsPerCell * 2, result.choiceB.total())
        }
        val baseMetrics = specs.associate { spec -> spec.branch to choiceMetrics(spec.branch, specs, results) }
        val confirmationSpecs = if (seedsPerCell in MATRIX_BASE_SEEDS_PER_CELL until MATRIX_CONFIRMATION_SEEDS_PER_CELL) {
            baseMetrics.filterValues(::needsChoiceConfirmation).keys
        } else {
            emptySet()
        }
        confirmationSpecs.forEach { userBranch ->
            val userIndex = specs.indexOfFirst { it.branch == userBranch }
            specs.forEachIndexed { opponentIndex, opponentSpec ->
                if (userIndex != opponentIndex) {
                    val pair = userBranch to opponentSpec.branch
                    val cell = userIndex * specs.size + opponentIndex
                    val result = results.getValue(pair)
                    (seedsPerCell until MATRIX_CONFIRMATION_SEEDS_PER_CELL).forEach { seedIndex ->
                        result.record(simulateChoicePair(userBranch, opponentSpec.branch, cell, seedIndex))
                    }
                    assertEquals(MATRIX_CONFIRMATION_SEEDS_PER_CELL * 2, result.choiceA.total())
                    assertEquals(MATRIX_CONFIRMATION_SEEDS_PER_CELL * 2, result.choiceB.total())
                }
            }
        }

        val finalMetrics = specs.associate { spec -> spec.branch to choiceMetrics(spec.branch, specs, results) }
        finalMetrics.forEach { (specialization, metrics) ->
            println("arena-v2-choice $specialization favorableA=${metrics.favorableChoiceADelta} unfavorableB=${metrics.unfavorableChoiceBDelta} neutralAbs=${metrics.neutralMeanAbsoluteDelta} signed=${metrics.signedMeanDelta} aCounters=${metrics.choiceAFavorableCount} bCounters=${metrics.choiceBUnfavorableCount} confirmed=${specialization in confirmationSpecs}")
            if (seedsPerCell >= MATRIX_BASE_SEEDS_PER_CELL) {
                assertTrue(
                    "$specialization choice A favorable delta ${metrics.favorableChoiceADelta} must be 4..8%p",
                    metrics.favorableChoiceADelta in MIN_CHOICE_DELTA..MAX_CHOICE_DELTA,
                )
                assertTrue(
                    "$specialization choice B unfavorable delta ${metrics.unfavorableChoiceBDelta} must be 4..8%p",
                    metrics.unfavorableChoiceBDelta in MIN_CHOICE_DELTA..MAX_CHOICE_DELTA,
                )
                assertTrue(
                    "$specialization neutral choice delta ${metrics.neutralMeanAbsoluteDelta} exceeds 1.5%p",
                    metrics.neutralMeanAbsoluteDelta <= MAX_NEUTRAL_CHOICE_DELTA,
                )
                assertTrue(
                    "$specialization signed choice delta ${metrics.signedMeanDelta} reveals a dominant stance",
                    kotlin.math.abs(metrics.signedMeanDelta) <= MAX_SIGNED_CHOICE_DELTA,
                )
                assertTrue("$specialization choice A needs all three favorable counters", metrics.choiceAFavorableCount >= 3)
                assertTrue("$specialization choice B needs all three unfavorable counters", metrics.choiceBUnfavorableCount >= 3)
            }
        }
    }

    @Test
    fun `side swap preserves battle identity and reverses only the two projections`() {
        val original = request(
            seed = 91_827_364L,
            user = projection(HeroPathBranch.WARRIOR_BERSERKER, "mirror-a"),
            opponent = projection(HeroPathBranch.MAGE_ELEMENTALIST, "mirror-b"),
        )

        val swapped = sideSwapped(original)

        assertEquals(original.battleId, swapped.battleId)
        assertEquals(original.serverSeed, swapped.serverSeed)
        assertEquals(original.requestedAtMillis, swapped.requestedAtMillis)
        assertEquals(original.rules, swapped.rules)
        assertEquals(original.user, swapped.opponent)
        assertEquals(original.opponent, swapped.user)
    }

    private fun assertSideCaps(
        actorMaxHp: Int,
        targetMaxHp: Int,
        effectsByRound: List<List<BattleTalentEffectTrace>>,
    ) {
        val all = effectsByRound.flatten()
        assertTrue("extra action must be at most one per round", effectsByRound.all { round -> round.count { it.extraActionOrdinal > 0 } <= 1 })
        assertTrue("extra action must be at most three per battle", all.count { it.extraActionOrdinal > 0 } <= 3)
        assertTrue("lethal survival must be at most once per battle", all.count(BattleTalentEffectTrace::lethalSurvival) <= 1)
        assertTrue("counter total exceeds 25% max HP", all.sumOf { it.counterDamage } <= targetMaxHp * 25 / 100)
        assertTrue("single talent heal exceeds 15% max HP", all.all { it.bonusHealing <= actorMaxHp * 15 / 100 })
        assertTrue("talent heal total exceeds 30% max HP", all.sumOf { it.bonusHealing } <= actorMaxHp * 30 / 100)
        all.filter { HeroPathCatalog.byNodeId[it.sourceNodeId]?.slot == HeroPathNodeSlot.CORE }.forEach { core ->
            assertTrue("a core's added damage alone cannot one-shot a healthy target", core.extraDamage < targetMaxHp)
        }
    }

    private fun projection(
        branch: HeroPathBranch,
        id: String,
        choiceB: Boolean = false,
        condition: BattleCondition = BattleCondition.NORMAL,
        guidance: BattleGuidance = BattleGuidance.BALANCED,
        power: Long = 10_000L,
        points: Int = 10,
    ): BattleProjectionSnapshot {
        val metadata = HeroPathCatalog.byBranch.getValue(branch)
        val ranks = allocationRanks(branch, points, choiceB)
        val core = ranks.keys.singleOrNull { HeroPathCatalog.byNodeId.getValue(it).slot == HeroPathNodeSlot.CORE }.orEmpty()
        val heroPathSnapshot = HeroPathEngine.battleSnapshotFromRanks(
            heroClass = metadata.heroClass,
            nodeRanks = ranks,
            allocationRevision = 101L,
            activeCoreNodeId = core,
        )
        return BattleProjectionSnapshot(
            projectionId = id,
            displayName = id,
            heroClass = metadata.heroClass,
            level = 100L,
            verifiedPower = power,
            condition = condition,
            build = BattleBuildStats(20, 20, 20, 20, 20, 20),
            guidance = guidance,
            skills = commonSkills,
            heroPathRevision = heroPathSnapshot.allocationRevision,
            heroPathCatalogVersion = HERO_PATH_CATALOG_VERSION,
            heroPathBattleSnapshot = heroPathSnapshot,
            snapshotVersion = 3,
        )
    }

    private fun projectionWithRanks(
        branch: HeroPathBranch,
        id: String,
        ranks: Map<String, Int>,
    ): BattleProjectionSnapshot {
        val metadata = HeroPathCatalog.byBranch.getValue(branch)
        val snapshot = HeroPathEngine.battleSnapshotFromRanks(
            heroClass = metadata.heroClass,
            nodeRanks = ranks,
            allocationRevision = 202L,
        )
        return projection(branch, id, points = 0).copy(
            heroPathRevision = snapshot.allocationRevision,
            heroPathBattleSnapshot = snapshot,
        )
    }

    private fun chargeSignature(
        actor: BattleProjectionSnapshot,
        opponent: BattleProjectionSnapshot,
    ): List<Int> = (1L..256L).flatMap { seed ->
        ProjectionBattleEngine.simulate(request(seed, actor, opponent)).rounds.flatMap { round ->
            listOf(round.userClassChargeBefore, round.userClassChargeAfter)
        }
    }

    private fun request(
        seed: Long,
        user: BattleProjectionSnapshot,
        opponent: BattleProjectionSnapshot,
    ) = UserInitiatedBattleRequest(
        battleId = "arena-v2-${user.projectionId}-${opponent.projectionId}-$seed",
        serverSeed = seed,
        requestedAtMillis = 1_000L,
        user = user,
        opponent = opponent,
        rules = BattleRules(growthReferencePower = 10_000L),
    )

    private fun sideSwapped(request: UserInitiatedBattleRequest): UserInitiatedBattleRequest = request.copy(
        user = request.opponent,
        opponent = request.user,
    )

    private fun assertCounterTrace(
        effect: BattleTalentEffectTrace,
        actor: HeroPathBattleSnapshot,
        opponent: HeroPathBattleSnapshot,
    ) {
        val actorBranch = requireNotNull(actor.dominantBranch)
        val opponentBranch = requireNotNull(opponent.dominantBranch)
        val relation = HeroPathCatalog.matchupRelation(actorBranch, opponentBranch)
        val choiceAcceleration = if (
            HeroPathCatalog.byBranch.getValue(actorBranch).effectFamily == HeroPathEffectFamily.FOCUSED_SHOT
        ) 5_000 else 3_000
        val actorChoiceBonus = if (actor.choiceStance == HeroPathChoiceStance.A) 3_500 else 0
        val defenderChoiceReduction = if (opponent.choiceStance == HeroPathChoiceStance.B) 3_500 else 0
        val actorAccelerationBonus = if (actor.choiceStance == HeroPathChoiceStance.A) choiceAcceleration else 0
        val defenderAccelerationReduction = if (opponent.choiceStance == HeroPathChoiceStance.B) choiceAcceleration else 0
        val expectedPotency = (if (relation == HeroPathMatchupRelation.FAVORABLE) {
            11_500 + actorChoiceBonus - defenderChoiceReduction
        } else {
            10_000
        }).coerceIn(8_000, 15_000)
        val expectedAcceleration = (if (relation == HeroPathMatchupRelation.FAVORABLE) {
            1_500 + actorAccelerationBonus - defenderAccelerationReduction
        } else {
            0
        }).coerceIn(0, 6_500)
        assertEquals(HERO_PATH_COUNTER_RULES_VERSION, effect.counterRulesVersion)
        assertEquals(actor.counterRulesVersion, effect.counterRulesVersion)
        assertEquals(actorBranch, effect.dominantBranch)
        assertEquals(relation, effect.matchupRelation)
        assertEquals(actor.choiceStance, effect.choiceStance)
        assertEquals(opponent.choiceStance, effect.opponentChoiceStance)
        assertEquals(expectedPotency, effect.matchupPotencyBasisPoints)
        assertEquals(expectedAcceleration, effect.chargeAccelerationBasisPoints)
        assertTrue(effect.matchupPotencyBasisPoints in 8_000..15_000)
        assertTrue(effect.chargeAccelerationBasisPoints in 0..6_500)
    }

    private fun simulateChoicePair(
        userBranch: HeroPathBranch,
        opponentBranch: HeroPathBranch,
        cell: Int,
        seedIndex: Int,
    ): ChoiceSample {
        val sample = cell * MATRIX_CONFIRMATION_SEEDS_PER_CELL + seedIndex
        val userStratum = matrixStratum(seedIndex, cell, opponentSide = false)
        val opponentStratum = matrixStratum(seedIndex, cell, opponentSide = true)
        val userId = "choice-user-$sample"
        val opponentId = "choice-opponent-$sample"
        val opponent = projection(
            opponentBranch,
            opponentId,
            choiceB = (seedIndex + cell) % 2 == 1,
            condition = opponentStratum.condition,
            guidance = opponentStratum.guidance,
            power = opponentStratum.power,
            points = 10,
        )
        fun requestFor(choiceB: Boolean): UserInitiatedBattleRequest = request(
            seed = CHOICE_SEED_BASE + sample,
            user = projection(
                userBranch,
                userId,
                choiceB = choiceB,
                condition = userStratum.condition,
                guidance = userStratum.guidance,
                power = userStratum.power,
                points = 10,
            ),
            opponent = opponent,
        )
        val choiceARequest = requestFor(choiceB = false)
        val choiceBRequest = requestFor(choiceB = true)
        return ChoiceSample(
            choiceAForward = ProjectionBattleEngine.simulate(choiceARequest),
            choiceAMirror = ProjectionBattleEngine.simulate(sideSwapped(choiceARequest)),
            choiceBForward = ProjectionBattleEngine.simulate(choiceBRequest),
            choiceBMirror = ProjectionBattleEngine.simulate(sideSwapped(choiceBRequest)),
        )
    }

    private fun choiceMetrics(
        specialization: HeroPathBranch,
        specs: List<com.nullplaying.model.HeroPathBranchDefinition>,
        results: Map<Pair<HeroPathBranch, HeroPathBranch>, ChoiceComparison>,
    ): ChoiceMetrics {
        val deltas = specs.asSequence()
            .map { it.branch }
            .filter { it != specialization }
            .associateWith { opponent ->
                val result = results.getValue(specialization to opponent)
                result.choiceA.rate() - result.choiceB.rate()
            }
        val favorable = deltas.filterKeys {
            HeroPathCatalog.matchupRelation(specialization, it) == HeroPathMatchupRelation.FAVORABLE
        }.values
        val unfavorable = deltas.filterKeys {
            HeroPathCatalog.matchupRelation(specialization, it) == HeroPathMatchupRelation.UNFAVORABLE
        }.values
        val neutral = deltas.filterKeys {
            HeroPathCatalog.matchupRelation(specialization, it) == HeroPathMatchupRelation.NEUTRAL
        }.values
        return ChoiceMetrics(
            deltas = deltas,
            favorableChoiceADelta = favorable.average(),
            unfavorableChoiceBDelta = unfavorable.map { -it }.average(),
            neutralMeanAbsoluteDelta = neutral.map { kotlin.math.abs(it) }.average(),
            signedMeanDelta = deltas.values.average(),
            choiceAFavorableCount = favorable.count { it >= MIN_DIRECTIONAL_CHOICE_DELTA },
            choiceBUnfavorableCount = unfavorable.count { -it >= MIN_DIRECTIONAL_CHOICE_DELTA },
        )
    }

    private fun needsChoiceConfirmation(metrics: ChoiceMetrics): Boolean =
        nearBoundary(metrics.favorableChoiceADelta, MIN_CHOICE_DELTA, CHOICE_CONFIRMATION_MARGIN) ||
            nearBoundary(metrics.favorableChoiceADelta, MAX_CHOICE_DELTA, CHOICE_CONFIRMATION_MARGIN) ||
            nearBoundary(metrics.unfavorableChoiceBDelta, MIN_CHOICE_DELTA, CHOICE_CONFIRMATION_MARGIN) ||
            nearBoundary(metrics.unfavorableChoiceBDelta, MAX_CHOICE_DELTA, CHOICE_CONFIRMATION_MARGIN) ||
            kotlin.math.abs(metrics.signedMeanDelta) >= MAX_SIGNED_CHOICE_DELTA - CHOICE_CONFIRMATION_MARGIN ||
            metrics.deltas.values.any {
                nearBoundary(kotlin.math.abs(it), MIN_DIRECTIONAL_CHOICE_DELTA, CHOICE_CONFIRMATION_MARGIN)
            }

    private fun nearBoundary(value: Double, boundary: Double, margin: Double): Boolean =
        kotlin.math.abs(value - boundary) <= margin

    private fun matrixSeedsPerCell(): Int = (System.getenv("ARENA_TALENT_SEEDS_PER_CELL")
        ?: System.getProperty("arenaTalent.seedsPerCell"))
        ?.toIntOrNull()
        ?.coerceIn(1, MATRIX_CONFIRMATION_SEEDS_PER_CELL)
        ?: 1

    private fun standardDeviation(values: Collection<Double>): Double {
        val mean = values.average()
        return kotlin.math.sqrt(values.sumOf { value -> (value - mean) * (value - mean) } / values.size)
    }

    private fun isFirstTurnKnockout(result: ProjectionBattleResult): Boolean =
        result.rounds.firstOrNull()?.let { it.userHpAfter == 0 || it.opponentHpAfter == 0 } == true

    private fun simulateMatrixPair(
        userBranch: HeroPathBranch,
        opponentBranch: HeroPathBranch,
        cell: Int,
        seedIndex: Int,
    ): Pair<ProjectionBattleResult, ProjectionBattleResult> {
        val sample = cell * MATRIX_CONFIRMATION_SEEDS_PER_CELL + seedIndex
        val userStratum = matrixStratum(seedIndex, cell, opponentSide = false)
        val opponentStratum = matrixStratum(seedIndex, cell, opponentSide = true)
        val forwardRequest = request(
            seed = 80_000L + sample,
            user = projection(
                userBranch,
                "matrix-user-$sample",
                condition = userStratum.condition,
                guidance = userStratum.guidance,
                power = userStratum.power,
                points = userStratum.points,
            ),
            opponent = projection(
                opponentBranch,
                "matrix-opponent-$sample",
                condition = opponentStratum.condition,
                guidance = opponentStratum.guidance,
                power = opponentStratum.power,
                points = opponentStratum.points,
            ),
        )
        return ProjectionBattleEngine.simulate(forwardRequest) to
            ProjectionBattleEngine.simulate(sideSwapped(forwardRequest))
    }

    /**
     * Both sides receive the same per-cell marginal distribution. Point offsets stay within the
     * same inactive (0/1/5) or active (10/15/20) counter layer so texture is not diluted by an
     * unlocked projection fighting a locked one. Other side offsets avoid identical strata, and
     * the power permutation breaks the otherwise perfect guidance/power correlation at modulus 3.
     */
    private fun matrixStratum(seedIndex: Int, cell: Int, opponentSide: Boolean): MatrixStratum {
        val side = if (opponentSide) 1 else 0
        val basePointIndex = Math.floorMod(seedIndex + cell * 5, matrixPointBands.size)
        val pointLayerStart = if (basePointIndex < inactiveCounterPointBands.size) 0 else inactiveCounterPointBands.size
        val pointIndexWithinLayer = basePointIndex - pointLayerStart
        val pointIndex = pointLayerStart + Math.floorMod(pointIndexWithinLayer + side, inactiveCounterPointBands.size)
        return MatrixStratum(
            condition = BattleCondition.entries[Math.floorMod(seedIndex + cell + side * 2, BattleCondition.entries.size)],
            guidance = BattleGuidance.entries[Math.floorMod(seedIndex + cell * 2 + side, BattleGuidance.entries.size)],
            power = matrixPowerScales[Math.floorMod(seedIndex + seedIndex / matrixPowerScales.size + cell + side, matrixPowerScales.size)],
            points = matrixPointBands[pointIndex],
        )
    }

    private fun branchRanks(branch: HeroPathBranch, points: Int, choiceB: Boolean = false): Map<String, Int> {
        require(points in 0..10)
        val nodes = HeroPathCatalog.nodesFor(branch).associateBy { it.slot }
        val orderedPointSlots = listOf(
            HeroPathNodeSlot.FOUNDATION_A,
            HeroPathNodeSlot.FOUNDATION_B,
            HeroPathNodeSlot.FOUNDATION_A,
            if (choiceB) HeroPathNodeSlot.CHOICE_B else HeroPathNodeSlot.CHOICE_A,
            HeroPathNodeSlot.FOUNDATION_B,
            HeroPathNodeSlot.SPECIAL_A,
            HeroPathNodeSlot.SPECIAL_B,
            HeroPathNodeSlot.ADVANCED_TACTIC,
            HeroPathNodeSlot.ADVANCED_TACTIC,
            HeroPathNodeSlot.CORE,
        )
        return orderedPointSlots.take(points)
            .map { nodes.getValue(it).nodeId }
            .groupingBy { it }
            .eachCount()
    }

    private fun allocationRanks(primary: HeroPathBranch, points: Int, choiceB: Boolean): Map<String, Int> {
        require(points in 0..20)
        val heroClass = HeroPathCatalog.byBranch.getValue(primary).heroClass
        val branches = HeroPathCatalog.branchesFor(heroClass).map { it.branch }
        val support = branches.first { it != primary }
        val third = branches.first { it != primary && it != support }
        val primaryPoints = points.coerceAtMost(10)
        val supportPoints = (points - primaryPoints).coerceIn(0, 9)
        val thirdPoints = (points - primaryPoints - supportPoints).coerceIn(0, 10)
        return mergeRanks(
            branchRanks(primary, primaryPoints, choiceB),
            branchRanks(support, supportPoints, choiceB = false),
            branchRanks(third, thirdPoints, choiceB = false),
        )
    }

    private fun mergeRanks(vararg maps: Map<String, Int>): Map<String, Int> = maps
        .flatMap { it.entries }
        .associate { it.key to it.value }

    private fun percentile(sorted: List<Int>, fraction: Double): Int {
        val index = ((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun requireV2Catalog() {
        assumeTrue("V2 catalog has not landed yet", HeroPathCatalog.nodes.size == 144)
    }

    private val commonSkills = listOf(
        BattleSkillSnapshot("strike", "Strike", BattleSkillKind.STRIKE, 12_000, 2, 30),
        BattleSkillSnapshot("pierce", "Pierce", BattleSkillKind.PIERCE, 12_000, 2, 30),
        BattleSkillSnapshot("arcane", "Arcane", BattleSkillKind.ARCANE, 12_000, 2, 30),
        BattleSkillSnapshot("control", "Control", BattleSkillKind.CONTROL, 12_000, 2, 30),
        BattleSkillSnapshot("recover", "Recover", BattleSkillKind.RECOVER, 10_000, 3, 30),
    )

    private val matrixPowerScales = listOf(9_000L, 10_000L, 11_000L)
    private val matrixPointBands = listOf(0, 1, 5, 10, 15, 20)
    private val inactiveCounterPointBands = setOf(0, 1, 5)
    private val activeCounterPointBands = setOf(10, 15, 20)

    private companion object {
        const val MATRIX_BASE_SEEDS_PER_CELL = 10_000
        const val MATRIX_CONFIRMATION_SEEDS_PER_CELL = 100_000
        const val SIDE_CONFIRMATION_TRIGGER = 0.012
        const val RECIPROCAL_CONFIRMATION_TRIGGER = 0.012
        const val MATCHUP_CONFIRMATION_MARGIN = 0.0075
        const val CELL_CONFIRMATION_MARGIN = 0.01
        const val FLATNESS_MAD_CONFIRMATION_MARGIN = 0.005
        const val FLATNESS_SD_CONFIRMATION_MARGIN = 0.0075
        const val MAX_RECIPROCAL_DIFFERENCE = 0.015
        const val MAX_INACTIVE_COUNTER_BIAS = 0.015
        const val FAVORABLE_WIN_RATE = 0.53
        const val UNFAVORABLE_WIN_RATE = 0.47
        const val MIN_SPEC_MAD = 0.025
        const val MAX_SPEC_MAD = 0.075
        const val MIN_SPEC_STANDARD_DEVIATION = 0.035
        const val MAX_SPEC_STANDARD_DEVIATION = 0.09
        const val MIN_GLOBAL_MAD = 0.03
        const val MAX_GLOBAL_MAD = 0.07
        const val MIN_GLOBAL_STANDARD_DEVIATION = 0.04
        const val MAX_GLOBAL_STANDARD_DEVIATION = 0.085
        const val MIN_CHOICE_DELTA = 0.04
        const val MAX_CHOICE_DELTA = 0.08
        const val MIN_DIRECTIONAL_CHOICE_DELTA = 0.03
        const val MAX_NEUTRAL_CHOICE_DELTA = 0.015
        const val MAX_SIGNED_CHOICE_DELTA = 0.02
        const val CHOICE_CONFIRMATION_MARGIN = 0.0075
        const val CHOICE_SEED_BASE = 40_000_000L
    }

    private data class MatrixStratum(
        val condition: BattleCondition,
        val guidance: BattleGuidance,
        val power: Long,
        val points: Int,
    )

    private data class ChoiceSample(
        val choiceAForward: ProjectionBattleResult,
        val choiceAMirror: ProjectionBattleResult,
        val choiceBForward: ProjectionBattleResult,
        val choiceBMirror: ProjectionBattleResult,
    )

    private data class ChoiceComparison(
        val choiceA: WinLoss = WinLoss(),
        val choiceB: WinLoss = WinLoss(),
    ) {
        fun record(sample: ChoiceSample) {
            choiceA.record(sample.choiceAForward.outcome)
            choiceA.record(sample.choiceAMirror.outcome.reversed())
            choiceB.record(sample.choiceBForward.outcome)
            choiceB.record(sample.choiceBMirror.outcome.reversed())
        }

        private fun BattleOutcome.reversed(): BattleOutcome = when (this) {
            BattleOutcome.USER_WIN -> BattleOutcome.USER_LOSS
            BattleOutcome.USER_LOSS -> BattleOutcome.USER_WIN
            BattleOutcome.DRAW -> BattleOutcome.DRAW
        }
    }

    private data class ChoiceMetrics(
        val deltas: Map<HeroPathBranch, Double>,
        val favorableChoiceADelta: Double,
        val unfavorableChoiceBDelta: Double,
        val neutralMeanAbsoluteDelta: Double,
        val signedMeanDelta: Double,
        val choiceAFavorableCount: Int,
        val choiceBUnfavorableCount: Int,
    )

    private data class ChargeTelemetryKey(
        val heroClass: BattleHeroClass,
        val points: Int,
        val relation: HeroPathMatchupRelation,
    )

    /** Observed action/round evidence, not a decomposition of hidden RNG or gross damage. */
    private class ChargeTelemetry {
        private val outcomes = WinLoss()
        private var rounds = 0L
        private var procs = 0L
        private var firstProcRoundSum = 0L
        private var battlesWithProc = 0L
        private var consecutiveProcs = 0L
        private var fullChargeAfterProc = 0L
        private var strongTalentActions = 0L
        private var actionDamage = 0L
        private var ordinaryActionDamage = 0L
        private var talentActionDamage = 0L
        private var actionHealing = 0L
        private var selfDamage = 0L
        private var traceExtra = 0L
        private var traceCounter = 0L
        private var traceHealing = 0L
        private var resourceRemoved = 0L

        fun record(battle: ProjectionBattleResult, userSide: Boolean) {
            outcomes.record(if (userSide) battle.outcome else battle.outcome.let {
                when (it) {
                    BattleOutcome.USER_WIN -> BattleOutcome.USER_LOSS
                    BattleOutcome.USER_LOSS -> BattleOutcome.USER_WIN
                    BattleOutcome.DRAW -> BattleOutcome.DRAW
                }
            })
            var seenProc = false
            var previousWasProc = false
            battle.rounds.forEach { round ->
                val action = if (userSide) round.userAction else round.opponentAction
                val hasProc = action.talentEffects.isNotEmpty()
                rounds += 1
                actionDamage += action.damage
                actionHealing += action.healing
                selfDamage += action.selfDamage
                if (hasProc) {
                    procs += 1
                    talentActionDamage += action.damage
                    if (action.powerAttack) strongTalentActions += 1
                    if (!seenProc) {
                        firstProcRoundSum += round.number
                        battlesWithProc += 1
                        seenProc = true
                    }
                    if (previousWasProc) consecutiveProcs += 1
                    val chargeAfter = if (userSide) round.userClassChargeAfter else round.opponentClassChargeAfter
                    if (chargeAfter == 3) fullChargeAfterProc += 1
                    action.talentEffects.forEach { trace ->
                        traceExtra += trace.extraDamage
                        traceCounter += trace.counterDamage
                        traceHealing += trace.bonusHealing
                        resourceRemoved += trace.resourceRemoved
                    }
                } else {
                    ordinaryActionDamage += action.damage
                }
                previousWasProc = hasProc
            }
        }

        private fun ratio(numerator: Long, denominator: Long): Double =
            if (denominator == 0L) 0.0 else numerator.toDouble() / denominator

        fun describe(): String =
            "battles=${outcomes.total()} win=${outcomes.rate()} rounds=$rounds procs=$procs " +
                "procBattles=$battlesWithProc firstProcRoundMean=${ratio(firstProcRoundSum, battlesWithProc)} " +
                "procsPer10Rounds=${ratio(procs * 10, rounds)} consecutiveProcShare=${ratio(consecutiveProcs, procs)} " +
                "endRoundFullChargeAfterProcShare=${ratio(fullChargeAfterProc, procs)} " +
                "strongTalentShare=${ratio(strongTalentActions, procs)} actualActionDamage=$actionDamage " +
                "ordinaryActionDamage=$ordinaryActionDamage talentActionDamage=$talentActionDamage " +
                "actualActionHealing=$actionHealing selfDamage=$selfDamage " +
                "traceExtraBeforeFinalCap=$traceExtra traceCounterBeforeFinalCap=$traceCounter " +
                "traceHealingBeforeFinalCap=$traceHealing resourceRemoved=$resourceRemoved"
    }

    private data class WinLoss(var wins: Int = 0, var losses: Int = 0, var draws: Int = 0) {
        fun record(outcome: BattleOutcome) {
            when (outcome) {
                BattleOutcome.USER_WIN -> wins += 1
                BattleOutcome.USER_LOSS -> losses += 1
                BattleOutcome.DRAW -> draws += 1
            }
        }

        fun rate(): Double = if (total() == 0) 0.5 else (wins.toDouble() + draws.toDouble() * 0.5) / total().toDouble()

        fun total(): Int = wins + losses + draws
    }

    private data class SideSwapResult(
        val originalAsUser: WinLoss = WinLoss(),
        val originalAsOpponent: WinLoss = WinLoss(),
    ) {
        fun record(originalAsUser: BattleOutcome, originalAsOpponent: BattleOutcome) {
            this.originalAsUser.record(originalAsUser)
            this.originalAsOpponent.record(originalAsOpponent)
        }

        fun difference(): Double = kotlin.math.abs(originalAsUser.rate() - originalAsOpponent.rate())

        fun total(): Int = originalAsUser.total()
    }

    private fun BattleOutcome.opposite(): BattleOutcome = when (this) {
        BattleOutcome.USER_WIN -> BattleOutcome.USER_LOSS
        BattleOutcome.USER_LOSS -> BattleOutcome.USER_WIN
        BattleOutcome.DRAW -> BattleOutcome.DRAW
    }

    private fun HeroPathMatchupRelation.opposite(): HeroPathMatchupRelation = when (this) {
        HeroPathMatchupRelation.FAVORABLE -> HeroPathMatchupRelation.UNFAVORABLE
        HeroPathMatchupRelation.UNFAVORABLE -> HeroPathMatchupRelation.FAVORABLE
        HeroPathMatchupRelation.NEUTRAL -> HeroPathMatchupRelation.NEUTRAL
    }
}
