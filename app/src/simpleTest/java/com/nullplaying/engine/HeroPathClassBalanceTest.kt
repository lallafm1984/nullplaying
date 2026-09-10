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
import com.nullplaying.model.HERO_PATH_CATALOG_VERSION
import com.nullplaying.model.HeroPathBranch
import com.nullplaying.model.HeroPathEffectFamily
import com.nullplaying.model.HeroPathNodeSlot
import com.nullplaying.model.UserInitiatedBattleRequest
import org.junit.Assert.assertTrue
import org.junit.Test

class HeroPathClassBalanceTest {
    @Test
    fun `six classes remain inside the aggregate win-rate guardrail across path builds`() {
        val seedsPerPair = 1_000
        val wins = BattleHeroClass.entries.associateWith { 0 }.toMutableMap()
        val losses = BattleHeroClass.entries.associateWith { 0 }.toMutableMap()
        val draws = BattleHeroClass.entries.associateWith { 0 }.toMutableMap()
        val profileResults = listOf(0, 10, 20).flatMap { points ->
            BattleHeroClass.entries.map { heroClass -> (points to heroClass) to WinLoss() }
        }.toMap().toMutableMap()
        val specializationResults = HeroPathCatalog.branches.associate { it.branch to WinLoss() }.toMutableMap()
        val effectFamilyResults = HeroPathEffectFamily.entries.associateWith { WinLoss() }.toMutableMap()
        val userSideResults = BattleHeroClass.entries.associateWith { WinLoss() }.toMutableMap()
        val opponentSideResults = BattleHeroClass.entries.associateWith { WinLoss() }.toMutableMap()

        BattleGuidance.entries.forEach { guidance ->
            listOf(0, 10, 20).forEach { allocatedPoints ->
                BattleHeroClass.entries.forEachIndexed { firstIndex, firstClass ->
                    BattleHeroClass.entries.drop(firstIndex + 1).forEach { secondClass ->
                        val firstVariants = (0..2).map { specializationIndex ->
                            projection(firstClass, allocatedPoints, guidance, specializationIndex)
                        }
                        val secondVariants = (0..2).map { specializationIndex ->
                            projection(secondClass, allocatedPoints, guidance, specializationIndex)
                        }
                        for (seed in 1L..seedsPerPair.toLong()) {
                            val specializationIndex = ((seed - 1L) % 3L).toInt()
                            val first = firstVariants[specializationIndex]
                            val second = secondVariants[specializationIndex]
                            val firstOutcome = ProjectionBattleEngine.simulate(request(seed, first, second)).outcome
                            val secondOutcome = ProjectionBattleEngine.simulate(request(seed, second, first)).outcome
                            record(
                                firstClass,
                                secondClass,
                                firstOutcome,
                                wins,
                                losses,
                                draws,
                            )
                            profileResults.getValue(allocatedPoints to firstClass).record(firstOutcome)
                            profileResults.getValue(allocatedPoints to secondClass).record(secondOutcome)
                            if (allocatedPoints > 0) {
                                val firstSpec = primaryBranch(firstClass, specializationIndex)
                                val secondSpec = primaryBranch(secondClass, specializationIndex)
                                specializationResults.getValue(firstSpec).record(firstOutcome)
                                specializationResults.getValue(secondSpec).record(secondOutcome)
                                effectFamilyResults.getValue(HeroPathCatalog.byBranch.getValue(firstSpec).effectFamily).record(firstOutcome)
                                effectFamilyResults.getValue(HeroPathCatalog.byBranch.getValue(secondSpec).effectFamily).record(secondOutcome)
                            }
                            userSideResults.getValue(firstClass).record(firstOutcome)
                            userSideResults.getValue(secondClass).record(secondOutcome)
                            opponentSideResults.getValue(secondClass).record(firstOutcome.opposite())
                            opponentSideResults.getValue(firstClass).record(secondOutcome.opposite())
                            record(
                                secondClass,
                                firstClass,
                                secondOutcome,
                                wins,
                                losses,
                                draws,
                            )
                        }
                    }
                }
            }
        }

        val rates = BattleHeroClass.entries.associateWith { heroClass ->
            val decisive = wins.getValue(heroClass) + losses.getValue(heroClass)
            if (decisive == 0) 0.5 else wins.getValue(heroClass).toDouble() / decisive.toDouble()
        }
        println(
            "hero-path-class-balance seedsPerPair=$seedsPerPair allocationStrata=27 battles=270000 " +
                rates.entries.joinToString { (heroClass, rate) ->
                    "$heroClass=${"%.4f".format(rate)} " +
                        "(${wins.getValue(heroClass)}-${losses.getValue(heroClass)}-${draws.getValue(heroClass)})"
                },
        )
        listOf(0, 10, 20).forEach { points ->
            println(
                "hero-path-points-$points " + BattleHeroClass.entries.joinToString { heroClass ->
                    "$heroClass=${"%.4f".format(profileResults.getValue(points to heroClass).rate())}"
                },
            )
        }
        println(
            "hero-path-specializations " + specializationResults.entries.joinToString { (branch, result) ->
                "$branch=${"%.4f".format(result.rate())}"
            },
        )
        println(
            "hero-path-effect-families " + effectFamilyResults.entries.joinToString { (family, result) ->
                "$family=${"%.4f".format(result.rate())}"
            },
        )
        println(
            "hero-path-side-difference " + BattleHeroClass.entries.joinToString { heroClass ->
                val userRate = userSideResults.getValue(heroClass).rate()
                val opponentRate = opponentSideResults.getValue(heroClass).rate()
                "$heroClass=user:${"%.4f".format(userRate)}/opponent:${"%.4f".format(opponentRate)}/diff:${"%.4f".format(kotlin.math.abs(userRate - opponentRate))}"
            },
        )
        rates.forEach { (heroClass, rate) ->
            assertTrue("$heroClass aggregate win rate $rate must stay within 45..55%", rate in 0.45..0.55)
        }
        specializationResults.forEach { (specialization, result) ->
            assertTrue(
                "$specialization win rate ${result.rate()} must stay within 45..55%",
                result.rate() in 0.45..0.55,
            )
        }
        BattleHeroClass.entries.forEach { heroClass ->
            val userRate = userSideResults.getValue(heroClass).rate()
            val opponentRate = opponentSideResults.getValue(heroClass).rate()
            val difference = kotlin.math.abs(userRate - opponentRate)
            assertTrue("$heroClass side difference $difference must stay within 1.5%p", difference <= 0.015)
        }
        assertTrue(
            "best-worst class spread must stay within eight percentage points: $rates",
            rates.values.max() - rates.values.min() <= 0.08,
        )
    }

    private fun projection(
        heroClass: BattleHeroClass,
        allocatedPoints: Int,
        guidance: BattleGuidance,
        primarySpecializationIndex: Int,
    ): BattleProjectionSnapshot {
        val branches = HeroPathCatalog.branchesFor(heroClass).map { it.branch }
        val primaryIndex = primarySpecializationIndex % branches.size
        val primary = branches[primaryIndex]
        val support = branches[(primaryIndex + 1) % branches.size]
        val third = branches[(primaryIndex + 2) % branches.size]
        val ranks = when (allocatedPoints) {
            0 -> emptyMap()
            10 -> branchRanks(primary, 10)
            20 -> branchRanks(primary, 10) + branchRanks(support, 9) + branchRanks(third, 1)
            else -> error("unsupported V2 balance allocation: $allocatedPoints")
        }
        val activeCore = ranks.keys.singleOrNull {
            HeroPathCatalog.byNodeId.getValue(it).slot == HeroPathNodeSlot.CORE
        }.orEmpty()
        val heroPathSnapshot = HeroPathEngine.battleSnapshotFromRanks(
            heroClass = heroClass,
            nodeRanks = ranks,
            allocationRevision = allocatedPoints.toLong(),
            activeCoreNodeId = activeCore,
        )
        check(HeroPathEngine.validateBattleSnapshot(heroPathSnapshot, heroClass))
        return BattleProjectionSnapshot(
            projectionId = "balance-${heroClass.name}-$allocatedPoints-${guidance.name}-$primaryIndex",
            displayName = heroClass.name,
            heroClass = heroClass,
            level = 100L,
            verifiedPower = 30_000L,
            condition = BattleCondition.NORMAL,
            build = BattleBuildStats(20, 20, 20, 20, 20, 20),
            guidance = guidance,
            skills = listOf(
                BattleSkillSnapshot("strike", "Strike", BattleSkillKind.STRIKE, 12_000, 2, 30),
                BattleSkillSnapshot("pierce", "Pierce", BattleSkillKind.PIERCE, 12_000, 2, 30),
                BattleSkillSnapshot("arcane", "Arcane", BattleSkillKind.ARCANE, 12_000, 2, 30),
                BattleSkillSnapshot("control", "Control", BattleSkillKind.CONTROL, 12_000, 2, 30),
            ),
            heroPathRevision = heroPathSnapshot.allocationRevision,
            heroPathCatalogVersion = HERO_PATH_CATALOG_VERSION,
            heroPathBattleSnapshot = heroPathSnapshot,
            snapshotVersion = 3,
        )
    }

    private fun primaryBranch(heroClass: BattleHeroClass, specializationIndex: Int): HeroPathBranch {
        val branches = HeroPathCatalog.branchesFor(heroClass).map { it.branch }
        return branches[specializationIndex % branches.size]
    }

    /** Reachable 0 -> 3 -> 6 -> 9 ladder; CHOICE_A is used for the stable balance fixture. */
    private fun branchRanks(branch: HeroPathBranch, points: Int): Map<String, Int> {
        require(points in 0..10)
        val nodes = HeroPathCatalog.nodesFor(branch).associateBy { it.slot }
        return listOf(
            HeroPathNodeSlot.FOUNDATION_A,
            HeroPathNodeSlot.FOUNDATION_B,
            HeroPathNodeSlot.FOUNDATION_A,
            HeroPathNodeSlot.CHOICE_A,
            HeroPathNodeSlot.FOUNDATION_B,
            HeroPathNodeSlot.SPECIAL_A,
            HeroPathNodeSlot.SPECIAL_B,
            HeroPathNodeSlot.ADVANCED_TACTIC,
            HeroPathNodeSlot.ADVANCED_TACTIC,
            HeroPathNodeSlot.CORE,
        ).take(points)
            .map { nodes.getValue(it).nodeId }
            .groupingBy { it }
            .eachCount()
    }

    private fun request(
        seed: Long,
        user: BattleProjectionSnapshot,
        opponent: BattleProjectionSnapshot,
    ) = UserInitiatedBattleRequest(
        battleId = "balance-${user.heroClass}-${opponent.heroClass}-$seed",
        serverSeed = seed,
        requestedAtMillis = 1_000L,
        user = user,
        opponent = opponent,
        rules = BattleRules(growthReferencePower = 10_000L),
    )

    private fun record(
        userClass: BattleHeroClass,
        opponentClass: BattleHeroClass,
        outcome: BattleOutcome,
        wins: MutableMap<BattleHeroClass, Int>,
        losses: MutableMap<BattleHeroClass, Int>,
        draws: MutableMap<BattleHeroClass, Int>,
    ) {
        when (outcome) {
            BattleOutcome.USER_WIN -> {
                wins[userClass] = wins.getValue(userClass) + 1
                losses[opponentClass] = losses.getValue(opponentClass) + 1
            }
            BattleOutcome.USER_LOSS -> {
                losses[userClass] = losses.getValue(userClass) + 1
                wins[opponentClass] = wins.getValue(opponentClass) + 1
            }
            BattleOutcome.DRAW -> {
                draws[userClass] = draws.getValue(userClass) + 1
                draws[opponentClass] = draws.getValue(opponentClass) + 1
            }
        }
    }

    private data class WinLoss(var wins: Int = 0, var losses: Int = 0, var draws: Int = 0) {
        fun record(outcome: BattleOutcome) {
            when (outcome) {
                BattleOutcome.USER_WIN -> wins += 1
                BattleOutcome.USER_LOSS -> losses += 1
                BattleOutcome.DRAW -> draws += 1
            }
        }

        fun rate(): Double = if (wins + losses == 0) 0.5 else wins.toDouble() / (wins + losses).toDouble()
    }

    private fun BattleOutcome.opposite(): BattleOutcome = when (this) {
        BattleOutcome.USER_WIN -> BattleOutcome.USER_LOSS
        BattleOutcome.USER_LOSS -> BattleOutcome.USER_WIN
        BattleOutcome.DRAW -> BattleOutcome.DRAW
    }
}
