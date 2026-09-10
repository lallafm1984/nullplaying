package com.nullplaying.engine

import com.nullplaying.model.BattleActionResolution
import com.nullplaying.model.BattleBuildStats
import com.nullplaying.model.BattleProjectionSnapshot
import com.nullplaying.model.BattleRoundAction
import com.nullplaying.model.BattleRules
import com.nullplaying.model.BattleSkillKind
import com.nullplaying.model.BattleSkillSnapshot
import com.nullplaying.model.HERO_PATH_CATALOG_VERSION
import com.nullplaying.model.HeroPathBranch
import com.nullplaying.model.HeroPathMatchupRelation
import com.nullplaying.model.HeroPathNodeSlot
import com.nullplaying.model.UserInitiatedBattleRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The counter floor is not a second attack: failed attacks cannot acquire outgoing damage. */
class ArenaTalentMissPressureRegressionTest {
    @Test
    fun `all favorable talent families preserve zero outgoing damage on missed and evaded attacks`() {
        HeroPathCatalog.branches.forEach { spec ->
            val failures = talentActions(spec.branch).filter {
                it.resolution == BattleActionResolution.MISSED || it.resolution == BattleActionResolution.EVADED
            }.toList()
            assertTrue("${spec.branch} requires a missed talent sample", failures.any { it.resolution == BattleActionResolution.MISSED })
            assertTrue("${spec.branch} requires an evaded talent sample", failures.any { it.resolution == BattleActionResolution.EVADED })
            failures.forEach { action ->
                assertEquals("${spec.branch} ${action.resolution} outgoing damage", 0, action.damage)
                action.talentEffects.forEach { trace ->
                    assertEquals("failed attack extra damage", 0, trace.extraDamage)
                    assertEquals("failed attack fortress damage", 0, trace.counterDamage)
                    assertEquals("failed attack resource removal", 0, trace.resourceRemoved)
                }
                // Do not equate healing or explicitly modeled self-damage with outgoing damage.
                // These fixtures have no finisher-eligible skill, so backlash is absent here.
                assertEquals(0, action.selfDamage)
            }
        }
    }

    @Test
    fun `normal landed favorable talents retain their numeric matchup and real effects`() {
        HeroPathCatalog.branches.forEach { spec ->
            val landed = talentActions(spec.branch).firstOrNull { action ->
                (action.resolution == BattleActionResolution.HIT || action.resolution == BattleActionResolution.BLOCKED) &&
                    action.talentEffects.any { it.extraDamage > 0 || it.counterDamage > 0 || it.bonusHealing > 0 }
            }
            assertTrue("${spec.branch} needs positive landed evidence", landed != null)
            landed!!.talentEffects.forEach { trace ->
                assertEquals(HeroPathMatchupRelation.FAVORABLE, trace.matchupRelation)
                assertEquals(15_000, trace.matchupPotencyBasisPoints)
                assertTrue("landed action must retain positive damage", landed.damage > 0)
            }
        }
    }

    @Test
    fun `independent recovery talent still heals when its accompanying attack misses`() {
        val healingFailure = talentActions(HeroPathBranch.CLERIC_SANCTUARY).firstOrNull { action ->
            (action.resolution == BattleActionResolution.MISSED || action.resolution == BattleActionResolution.EVADED) &&
                action.talentEffects.any { it.bonusHealing > 0 }
        }
        assertTrue("a recovery-family failed-attack sample must exist", healingFailure != null)
        assertEquals(0, healingFailure!!.damage)
        assertTrue(healingFailure.healing > 0)
        assertTrue(healingFailure.talentEffects.sumOf { it.bonusHealing } > 0)
    }

    @Test
    fun `an offensive talent attached to an owned recovery skill does not invent outgoing damage`() {
        val recovered = talentActions(
            HeroPathBranch.WARRIOR_BERSERKER,
            skills = listOf(BattleSkillSnapshot("recover-only", "Recover", BattleSkillKind.RECOVER, 10_000, 0)),
        ).filter { it.resolution == BattleActionResolution.RECOVERED }.take(16).toList()
        assertEquals("owned recovery skill must actually trigger the talent", 16, recovered.size)
        recovered.forEach { action ->
            assertEquals(0, action.damage)
            assertTrue(action.healing > 0)
            action.talentEffects.forEach { trace ->
                assertEquals(0, trace.extraDamage)
                assertEquals(0, trace.counterDamage)
            }
        }
    }

    private fun talentActions(
        branch: HeroPathBranch,
        skills: List<BattleSkillSnapshot> = attackSkills,
    ): Sequence<BattleRoundAction> {
        val opponentBranch = HeroPathCatalog.branches.first {
            HeroPathCatalog.matchupRelation(branch, it.branch) == HeroPathMatchupRelation.FAVORABLE
        }.branch
        val actor = projection(branch, "pressure-actor-$branch", talentsEnabled = true, skills = skills)
        val opponent = projection(opponentBranch, "pressure-target-$branch", talentsEnabled = false)
        return (1L..768L).asSequence().flatMap { seed ->
            ProjectionBattleEngine.simulate(
                UserInitiatedBattleRequest(
                    battleId = "miss-pressure-$branch-$seed",
                    serverSeed = seed,
                    requestedAtMillis = 1_000L,
                    user = actor,
                    opponent = opponent,
                    rules = BattleRules(growthReferencePower = 10_000L),
                ),
            ).rounds.asSequence().map { it.userAction }
        }.filter { action ->
            action.talentEffects.any { it.matchupRelation == HeroPathMatchupRelation.FAVORABLE }
        }
    }

    private fun projection(
        branch: HeroPathBranch,
        id: String,
        talentsEnabled: Boolean,
        skills: List<BattleSkillSnapshot> = attackSkills,
    ): BattleProjectionSnapshot {
        val spec = HeroPathCatalog.byBranch.getValue(branch)
        val nodes = HeroPathCatalog.nodesFor(branch).associateBy { it.slot }
        val ranks = if (talentsEnabled) {
            mapOf(
                nodes.getValue(HeroPathNodeSlot.FOUNDATION_A).nodeId to 2,
                nodes.getValue(HeroPathNodeSlot.FOUNDATION_B).nodeId to 2,
                nodes.getValue(HeroPathNodeSlot.CHOICE_A).nodeId to 1,
                nodes.getValue(HeroPathNodeSlot.SPECIAL_A).nodeId to 1,
            )
        } else {
            mapOf(nodes.getValue(HeroPathNodeSlot.FOUNDATION_A).nodeId to 1)
        }
        val path = HeroPathEngine.battleSnapshotFromRanks(
            spec.heroClass, ranks, allocationRevision = 1L, ownedSkillIds = skills.map { it.skillId },
        )
        assertTrue(HeroPathEngine.validateBattleSnapshot(path, spec.heroClass))
        return BattleProjectionSnapshot(
            projectionId = id,
            displayName = id,
            heroClass = spec.heroClass,
            level = 100L,
            verifiedPower = 10_000L,
            build = BattleBuildStats(20, 20, 20, 20, 20, 20),
            skills = skills,
            heroPathRevision = path.allocationRevision,
            heroPathCatalogVersion = HERO_PATH_CATALOG_VERSION,
            heroPathBattleSnapshot = path,
            snapshotVersion = 3,
        )
    }

    private val attackSkills = listOf(
        BattleSkillSnapshot("pressure-strike", "Strike", BattleSkillKind.STRIKE, 10_000, 0),
    )
}
