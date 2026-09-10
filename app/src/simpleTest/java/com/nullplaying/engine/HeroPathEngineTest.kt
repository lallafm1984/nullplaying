package com.nullplaying.engine

import com.nullplaying.model.BattleHeroClass
import com.nullplaying.model.HERO_PATH_MAX_CLASS_CHARGE
import com.nullplaying.model.HERO_PATH_MAX_COMBAT_POINTS
import com.nullplaying.model.HERO_PATH_OFFER_COUNT
import com.nullplaying.model.HeroPathAllocationTarget
import com.nullplaying.model.HeroPathBranch
import com.nullplaying.model.HeroPathChoiceType
import com.nullplaying.model.HeroPathClassCharge
import com.nullplaying.model.HeroPathMutationStatus
import com.nullplaying.model.HeroPathNodeSlot
import com.nullplaying.model.HeroPathNodeType
import com.nullplaying.model.HeroPathState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeroPathEngineTest {
    @Test
    fun `catalog maps one hundred forty four V2 nodes into six classes and eighteen specializations`() {
        assertEquals(18, HeroPathCatalog.branches.size)
        assertEquals(18, HeroPathCatalog.branches.map { it.branch }.distinct().size)
        assertEquals(18, HeroPathCatalog.branches.map { it.effectFamily }.distinct().size)
        assertEquals(144, HeroPathCatalog.nodes.size)
        assertEquals(144, HeroPathCatalog.byTraitId.size)
        assertEquals(144, HeroPathCatalog.nodes.map { it.nodeId }.distinct().size)
        assertTrue(HeroPathCatalog.nodes.none { it.nodeId.matches(Regex("TRAIT_\\d{3}")) })
        BattleHeroClass.entries.forEach { heroClass ->
            assertEquals(3, HeroPathCatalog.branchesFor(heroClass).size)
            assertEquals(24, HeroPathCatalog.nodesFor(heroClass).size)
        }
        HeroPathCatalog.branches.forEach { branch ->
            val nodes = HeroPathCatalog.nodesFor(branch.branch)
            assertEquals(8, nodes.size)
            assertEquals(HeroPathNodeSlot.entries.toSet(), nodes.map { it.slot }.toSet())
            assertEquals(11, nodes.sumOf { it.maxRank })
            assertEquals(10, nodes.sumOf { it.maxRank } - 1) // A/B choice is exclusive.
            assertEquals(1, nodes.count { it.nodeType == HeroPathNodeType.CORE })
            assertTrue(nodes.all { it.heroClassAffinity == branch.heroClass })
            assertTrue(nodes.all { it.effectFamily == branch.effectFamily })
        }
    }

    @Test
    fun `first milestone stores exactly three reproducible class branch unlocks`() {
        BattleHeroClass.entries.forEach { heroClass ->
            val initial = HeroPathState(heroClass = heroClass)
            val first = HeroPathEngine.issueMilestone(initial, "hero-$heroClass", 5L, 91L)
            val repeated = HeroPathEngine.issueMilestone(initial, "hero-$heroClass", 5L, 91L)

            assertEquals(HeroPathMutationStatus.APPLIED, first.status)
            assertEquals(first, repeated)
            assertEquals(HERO_PATH_OFFER_COUNT, first.token?.offers?.size)
            assertEquals(3, first.token?.offers?.map { offer ->
                HeroPathCatalog.byTraitId.getValue(offer.traitId).branch
            }?.distinct()?.size)
            assertTrue(first.token?.offers?.all { it.type == HeroPathChoiceType.UNLOCK } == true)
            assertEquals(first.token, first.state.milestoneTokens.single())

            val stored = HeroPathEngine.issueMilestone(
                first.state,
                "hero-$heroClass",
                5L,
                generationSeed = Long.MAX_VALUE,
            )
            assertEquals(HeroPathMutationStatus.TOKEN_ALREADY_ISSUED, stored.status)
            assertEquals(first.token, stored.token)
        }
    }

    @Test
    fun `unlock resolves once and never touches the learned skill model`() {
        val issued = HeroPathEngine.issueMilestone(
            HeroPathState(heroClass = BattleHeroClass.ROGUE),
            heroId = "rogue-one",
            milestoneLevel = 5L,
            generationSeed = 17L,
        )
        val offer = issued.token!!.offers.first()
        val resolved = HeroPathEngine.resolveMilestone(issued.state, issued.token.tokenId, offer.offerId)

        assertEquals(HeroPathMutationStatus.APPLIED, resolved.status)
        assertEquals(listOf(offer.traitId), resolved.state.activeTraitIds)
        assertEquals(1, resolved.state.traits.single().rank)
        assertTrue(resolved.token?.resolved == true)
        assertEquals(1L, resolved.state.revision)
        assertEquals(
            HeroPathMutationStatus.TOKEN_ALREADY_RESOLVED,
            HeroPathEngine.resolveMilestone(resolved.state, issued.token.tokenId, offer.offerId).status,
        )
        // The standalone contract has no LearnedSkill field or mutation path.
        assertFalse(HeroPathState::class.java.declaredFields.any { it.name.contains("skill", ignoreCase = true) })
    }

    @Test
    fun `offline catch-up issues only oldest choice until it is committed`() {
        val gameEngine = SimpleGameEngine()
        val rolled = gameEngine.rollStats(77L, com.nullplaying.model.HeroClass.MAGE)
        val state = gameEngine.newGame(
            name = "path-mage",
            heroClass = com.nullplaying.model.HeroClass.MAGE,
            rolledStats = rolled.stats,
            seed = rolled.nextSeed,
            now = 1_000L,
        )
        state.hero.level = 26L
        state.rankingCharacterId = "path-mage-id"

        assertTrue(gameEngine.reconcileHeroPath(state))
        assertEquals(BattleHeroClass.MAGE, state.heroPath.heroClass)
        assertEquals(listOf(5L), state.heroPath.milestoneTokens.map { it.milestoneLevel })
        val first = state.heroPath.milestoneTokens.single()
        val resolved = HeroPathEngine.resolveMilestone(
            state.heroPath,
            first.tokenId,
            first.offers.first().offerId,
        )
        state.heroPath = resolved.state

        assertTrue(gameEngine.reconcileHeroPath(state))
        assertEquals(listOf(5L, 10L), state.heroPath.milestoneTokens.map { it.milestoneLevel })
        assertTrue(state.heroPath.milestoneTokens.last().offers.size == HERO_PATH_OFFER_COUNT)
    }

    @Test
    fun `twenty point target keeps every V2 node beyond the legacy five slot view`() {
        val branches = HeroPathCatalog.branchesFor(BattleHeroClass.WARRIOR).map { it.branch }
        val ranks = buildMap {
            putAll(branchRanks(branches[0], points = 10))
            putAll(branchRanks(branches[1], points = 9))
            putAll(branchRanks(branches[2], points = 1))
        }
        val core = ranks.keys.single { HeroPathCatalog.byNodeId.getValue(it).nodeType == HeroPathNodeType.CORE }
        val granted = HeroPathEngine.reconcilePointGrants(
            HeroPathState(heroClass = BattleHeroClass.WARRIOR),
            heroLevel = 100L,
        )
        val result = HeroPathEngine.applyAllocationTarget(
            granted,
            HeroPathAllocationTarget(
                expectedRevision = granted.revision,
                nodeRanks = ranks,
                activeCoreNodeId = core,
            ),
        )

        assertEquals(HeroPathMutationStatus.APPLIED, result.status)
        assertEquals(HERO_PATH_MAX_COMBAT_POINTS.toLong(), result.state.spentPoints)
        assertEquals(0L, result.state.unspentPoints)
        assertTrue(result.state.traits.size > 5)
        assertEquals(ranks, result.state.traits.associate { it.traitId to it.rank })
        assertEquals(core, result.state.activeCoreTraitId)

        val snapshot = HeroPathEngine.createBattleSnapshot(result.state)
        assertTrue(HeroPathEngine.validateBattleSnapshot(snapshot, BattleHeroClass.WARRIOR))
        assertEquals(ranks, snapshot.nodes.associate { it.nodeId to it.rank })
        assertEquals(1, snapshot.nodes.count { it.nodeId == snapshot.activeCoreNodeId })
    }

    @Test
    fun `node max rank cannot consume another point`() {
        val node = HeroPathCatalog.nodesFor(BattleHeroClass.MAGE)
            .first { it.slot == HeroPathNodeSlot.FOUNDATION_A }
        val granted = HeroPathEngine.reconcilePointGrants(
            HeroPathState(heroClass = BattleHeroClass.MAGE),
            heroLevel = 100L,
        )

        val legal = HeroPathEngine.applyAllocationTarget(
            granted,
            HeroPathAllocationTarget(granted.revision, mapOf(node.nodeId to node.maxRank)),
        )
        assertEquals(HeroPathMutationStatus.APPLIED, legal.status)

        val overRank = HeroPathEngine.applyAllocationTarget(
            legal.state,
            HeroPathAllocationTarget(legal.state.revision, mapOf(node.nodeId to node.maxRank + 1)),
        )
        assertEquals(HeroPathMutationStatus.INVALID_ALLOCATION, overRank.status)
        assertEquals(legal.state, overRank.state)
        assertEquals(node.maxRank, overRank.state.traits.single().rank)
    }

    @Test
    fun `changing specialization core atomically leaves one explicit active core`() {
        val branches = HeroPathCatalog.branchesFor(BattleHeroClass.PALADIN)
        val oldRanks = branchRanks(branches[0].branch, points = 10)
        val newRanks = branchRanks(branches[1].branch, points = 10)
        val oldCore = coreId(oldRanks)
        val newCore = coreId(newRanks)
        val granted = HeroPathEngine.reconcilePointGrants(
            HeroPathState(heroClass = BattleHeroClass.PALADIN),
            heroLevel = 100L,
        )
        val initial = HeroPathEngine.applyAllocationTarget(
            granted,
            HeroPathAllocationTarget(granted.revision, oldRanks, activeCoreNodeId = oldCore),
        )
        assertEquals(HeroPathMutationStatus.APPLIED, initial.status)

        val result = HeroPathEngine.applyAllocationTarget(
            initial.state,
            HeroPathAllocationTarget(initial.state.revision, newRanks, activeCoreNodeId = newCore),
        )
        assertEquals(HeroPathMutationStatus.APPLIED, result.status)
        assertEquals(newCore, result.state.activeCoreTraitId)
        assertTrue(newCore in result.state.traits.map { it.traitId })
        assertFalse(oldCore in result.state.traits.map { it.traitId })
        assertEquals(1, result.state.traits.count { it.traitId == result.state.activeCoreTraitId })
        assertTrue(HeroPathEngine.validateBattleSnapshot(
            HeroPathEngine.createBattleSnapshot(result.state),
            BattleHeroClass.PALADIN,
        ))
    }

    @Test
    fun `class charge saturates at three and failed spending is immutable`() {
        val empty = HeroPathClassCharge(BattleHeroClass.CLERIC, value = -9)
        val first = HeroPathEngine.gainClassCharge(empty, amount = 99)
        assertEquals(0, first.before.value)
        assertEquals(HERO_PATH_MAX_CLASS_CHARGE, first.after.value)

        val spent = HeroPathEngine.spendClassCharge(first.after)
        assertTrue(spent.accepted)
        assertEquals(0, spent.after.value)

        val rejected = HeroPathEngine.spendClassCharge(spent.after)
        assertFalse(rejected.accepted)
        assertEquals(rejected.before, rejected.after)
    }

    @Test
    fun `invalid levels and seeds are handled without shared random state`() {
        val state = HeroPathState(heroClass = BattleHeroClass.RANGER)
        assertEquals(
            HeroPathMutationStatus.INVALID_MILESTONE,
            HeroPathEngine.issueMilestone(state, "ranger", 6L, 1L).status,
        )
        val first = HeroPathEngine.issueMilestone(state, "ranger", 5L, 1L).token
        val second = HeroPathEngine.issueMilestone(state, "ranger", 5L, 2L).token
        assertNotEquals(first?.generationSeed, second?.generationSeed)
    }

    @Test
    fun `every class receives twenty deterministic points through level one hundred`() {
        BattleHeroClass.entries.forEach { heroClass ->
            var state = HeroPathState(heroClass = heroClass)
            (5L..100L step 5L).forEachIndexed { index, level ->
                state = HeroPathEngine.reconcilePointGrants(state, level)
                assertEquals("$heroClass level $level point ledger", index + 1L, state.earnedPoints)
                assertEquals(level, state.lastGrantedMilestone)
                assertEquals(state.earnedPoints, state.spentPoints + state.unspentPoints)
            }
            assertEquals(HERO_PATH_MAX_COMBAT_POINTS.toLong(), state.earnedPoints)
            assertEquals(HERO_PATH_MAX_COMBAT_POINTS.toLong(), state.unspentPoints)
        }
    }

    @Test
    fun `five-level points accumulate and never disappear after a level drop`() {
        val initial = HeroPathState(heroClass = BattleHeroClass.RANGER)
        val levelFour = HeroPathEngine.reconcilePointGrants(initial, 4L)
        val levelSixteen = HeroPathEngine.reconcilePointGrants(levelFour, 16L)
        val dropped = HeroPathEngine.reconcilePointGrants(levelSixteen, 4L)
        val returned = HeroPathEngine.reconcilePointGrants(dropped, 16L)
        val levelTwenty = HeroPathEngine.reconcilePointGrants(returned, 20L)

        assertEquals(0L, levelFour.earnedPoints)
        assertEquals(3L, levelSixteen.earnedPoints)
        assertEquals(15L, levelSixteen.lastGrantedMilestone)
        assertEquals(3L, dropped.earnedPoints)
        assertEquals(3L, returned.earnedPoints)
        assertEquals(4L, levelTwenty.earnedPoints)
        assertEquals(levelTwenty.earnedPoints, levelTwenty.spentPoints + levelTwenty.unspentPoints)
    }

    @Test
    fun `batch allocation is atomic order independent and reset refunds every point`() {
        val branch = HeroPathCatalog.branchesFor(BattleHeroClass.CLERIC).first().branch
        val root = HeroPathCatalog.nodesFor(branch).single { it.slot == HeroPathNodeSlot.FOUNDATION_A }
        val second = HeroPathCatalog.nodesFor(branch).single { it.slot == HeroPathNodeSlot.FOUNDATION_B }
        val granted = HeroPathEngine.reconcilePointGrants(
            HeroPathState(heroClass = BattleHeroClass.CLERIC),
            heroLevel = 10L,
        )
        val applied = HeroPathEngine.applyDraft(granted, listOf(second.traitId, root.traitId))
        val reversed = HeroPathEngine.applyDraft(granted, listOf(root.traitId, second.traitId))

        assertEquals(HeroPathMutationStatus.APPLIED, applied.status)
        assertEquals(applied, reversed)
        assertEquals(2L, applied.state.earnedPoints)
        assertEquals(2L, applied.state.spentPoints)
        assertEquals(0L, applied.state.unspentPoints)
        assertEquals(1L, applied.state.revision)

        val reset = HeroPathEngine.resetAllocation(applied.state)
        assertEquals(HeroPathMutationStatus.APPLIED, reset.status)
        assertEquals(2L, reset.state.earnedPoints)
        assertEquals(0L, reset.state.spentPoints)
        assertEquals(2L, reset.state.unspentPoints)
        assertEquals(2L, reset.state.revision)
        assertTrue(reset.state.traits.isEmpty())
    }

    @Test
    fun `invalid batch never consumes points or partially applies`() {
        val granted = HeroPathEngine.reconcilePointGrants(
            HeroPathState(heroClass = BattleHeroClass.MAGE),
            heroLevel = 5L,
        )
        val mageRoot = HeroPathCatalog.nodesFor(BattleHeroClass.MAGE)
            .first { it.slot == HeroPathNodeSlot.FOUNDATION_A }
        val mageSecondRoot = HeroPathCatalog.nodesFor(BattleHeroClass.MAGE)
            .first { it.slot == HeroPathNodeSlot.FOUNDATION_B }
        val foreignRoot = HeroPathCatalog.nodesFor(BattleHeroClass.PALADIN)
            .first { it.slot == HeroPathNodeSlot.FOUNDATION_A }

        val overBudget = HeroPathEngine.applyDraft(granted, listOf(mageRoot.traitId, mageSecondRoot.traitId))
        assertEquals(HeroPathMutationStatus.INSUFFICIENT_POINTS, overBudget.status)
        assertEquals(granted, overBudget.state)

        val foreign = HeroPathEngine.applyDraft(granted, listOf(foreignRoot.traitId))
        assertEquals(HeroPathMutationStatus.INVALID_ALLOCATION, foreign.status)
        assertEquals(granted, foreign.state)
        assertEquals(granted.earnedPoints, granted.spentPoints + granted.unspentPoints)
    }

    private fun branchRanks(branch: HeroPathBranch, points: Int): Map<String, Int> {
        require(points in 0..10)
        val nodes = HeroPathCatalog.nodesFor(branch).associateBy { it.slot }
        val order = listOf(
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
        )
        return buildMap {
            order.take(points).forEach { slot ->
                val nodeId = nodes.getValue(slot).nodeId
                put(nodeId, getOrDefault(nodeId, 0) + 1)
            }
        }
    }

    private fun coreId(ranks: Map<String, Int>): String = ranks.keys.single {
        HeroPathCatalog.byNodeId.getValue(it).nodeType == HeroPathNodeType.CORE
    }
}
