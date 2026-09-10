package com.nullplaying.ui

import com.nullplaying.engine.HeroPathCatalog
import com.nullplaying.engine.HeroPathEngine
import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.model.BattleHeroClass
import com.nullplaying.model.HeroClass
import com.nullplaying.model.HeroPathBranch
import com.nullplaying.model.HeroPathMutationStatus
import com.nullplaying.model.HeroPathNodeSlot
import com.nullplaying.model.HeroPathState
import com.nullplaying.model.SimpleGameState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeroPathPresentationContractTest {
    @Test
    fun `all eighteen specializations reach the ten point core through visible UI actions`() {
        HeroPathCatalog.branches.forEach { branch ->
            val state = gameState(branch.heroClass)
            allocationOrder(branch.branch).forEach { id -> commitVisible(state, id) }
            assertEquals(branch.branch.name, 10L, state.heroPath.spentPoints)
            assertEquals(7, state.heroPath.traits.size)
            assertEquals(5, state.heroPath.activeTraitIds.size) // Compatibility view does not block V2.
            assertEquals(node(branch.branch, HeroPathNodeSlot.CORE), state.heroPath.activeCoreTraitId)
            assertMatchesAllocator(state)
        }
    }

    @Test
    fun `non core tier includes the proposed point while core requires nine other points`() {
        val state = gameState(BattleHeroClass.WARRIOR)
        val branch = HeroPathBranch.WARRIOR_BERSERKER
        commitVisible(state, node(branch, HeroPathNodeSlot.FOUNDATION_A))
        commitVisible(state, node(branch, HeroPathNodeSlot.FOUNDATION_B))
        val choice = node(branch, HeroPathNodeSlot.CHOICE_A)
        assertTrue(model(state).nodes.single { it.id == choice }.canDraft)
        commitVisible(state, choice) // The third point satisfies the tier-two allocator contract.

        val core = node(branch, HeroPathNodeSlot.CORE)
        assertFalse(model(state).nodes.single { it.id == core }.canDraft)
        val missing = model(state).nodes.single { it.id == core }.actionBlockReason!!
        assertTrue(missing.ko.contains("다른 특성"))
        assertTrue(missing.en.contains("other talents"))
        assertTrue(missing.ja.contains("他の特性"))
    }

    @Test
    fun `twenty point allocation spans more than five nodes but cannot spend a twenty first point`() {
        BattleHeroClass.entries.forEach { heroClass ->
            val state = gameState(heroClass)
            val branches = HeroPathCatalog.branchesFor(heroClass).map { it.branch }
            allocationOrder(branches[0]).forEach { commitVisible(state, it) }
            allocationOrder(branches[1]).dropLast(1).forEach { commitVisible(state, it) }
            commitVisible(state, node(branches[2], HeroPathNodeSlot.FOUNDATION_A))
            assertEquals(20L, state.heroPath.spentPoints)
            assertEquals(10L, state.heroPath.unspentPoints)
            assertTrue(state.heroPath.traits.size > 5)
            val extra = node(branches[2], HeroPathNodeSlot.FOUNDATION_B)
            val extraUi = model(state).nodes.single { it.id == extra }
            assertFalse(extraUi.canDraft)
            assertTrue(extraUi.actionBlockReason!!.ko.contains("20"))
            assertEquals(HeroPathMutationStatus.POINT_CAP_EXCEEDED, HeroPathEngine.applyDraft(state.heroPath, listOf(extra)).status)
            assertMatchesAllocator(state)
        }
    }

    @Test
    fun `A and B exclude one another in both pending and committed allocations`() {
        HeroPathCatalog.branches.forEach { branch ->
            val state = gameState(branch.heroClass)
            allocationOrder(branch.branch).take(4).forEach { commitVisible(state, it) }
            val choiceA = node(branch.branch, HeroPathNodeSlot.CHOICE_A)
            val choiceB = node(branch.branch, HeroPathNodeSlot.CHOICE_B)
            val pending = model(state, setOf(choiceA))
            assertEquals(HeroPathNodeStatus.DRAFT, pending.nodes.single { it.id == choiceA }.status)
            assertTrue(pending.nodes.single { it.id == choiceA }.canDraft) // Undo remains available.
            assertFalse(pending.nodes.single { it.id == choiceB }.canDraft)
            assertMatchesAllocator(state, setOf(choiceA))
            commitVisible(state, choiceA)
            assertFalse(model(state).nodes.single { it.id == choiceB }.canDraft)
            assertEquals(HeroPathMutationStatus.CHOICE_CONFLICT, HeroPathEngine.applyDraft(state.heroPath, listOf(choiceB)).status)
        }
    }

    @Test
    fun `one global core is enforced across specialization tabs`() {
        BattleHeroClass.entries.forEach { heroClass ->
            val state = gameState(heroClass)
            val branches = HeroPathCatalog.branchesFor(heroClass).map { it.branch }
            allocationOrder(branches[0]).forEach { commitVisible(state, it) }
            allocationOrder(branches[1]).dropLast(1).forEach { commitVisible(state, it) }
            val secondCore = node(branches[1], HeroPathNodeSlot.CORE)
            assertFalse(model(state).nodes.single { it.id == secondCore }.canDraft)
            assertEquals(HeroPathMutationStatus.INVALID_ALLOCATION, HeroPathEngine.applyDraft(state.heroPath, listOf(secondCore)).status)
            assertMatchesAllocator(state)
        }
    }

    @Test
    fun `rank ups and mixed branch draft batches remain possible past the legacy five slot view`() {
        BattleHeroClass.entries.forEach { heroClass ->
            val state = gameState(heroClass)
            // The allocator's compatibility view is lexically ordered; the later branch is omitted.
            val branches = HeroPathCatalog.branchesFor(heroClass).map { it.branch }.sortedBy { it.name }
            allocationOrder(branches[0]).forEach { commitVisible(state, it) }
            val otherA = node(branches[1], HeroPathNodeSlot.FOUNDATION_A)
            val thirdA = node(branches[2], HeroPathNodeSlot.FOUNDATION_A)
            commitVisible(state, otherA)
            assertFalse(otherA in state.heroPath.activeTraitIds)
            val pending = setOf(otherA, thirdA)
            assertTrue(model(state).nodes.single { it.id == otherA }.canDraft)
            assertMatchesAllocator(state, pending)
            val result = HeroPathEngine.applyDraft(state.heroPath, pending.toList())
            assertEquals(HeroPathMutationStatus.APPLIED, result.status)
            state.heroPath = result.state
            assertEquals(2, state.heroPath.traits.single { it.traitId == otherA }.rank)
            assertEquals(1, state.heroPath.traits.single { it.traitId == thirdA }.rank)
            assertMatchesAllocator(state)
        }
    }

    @Test
    fun `cancelling a funding draft cannot enable an invalid dependent batch`() {
        val state = gameState(BattleHeroClass.WARRIOR)
        val branch = HeroPathBranch.WARRIOR_BERSERKER
        val foundationA = node(branch, HeroPathNodeSlot.FOUNDATION_A)
        val foundationB = node(branch, HeroPathNodeSlot.FOUNDATION_B)
        val choiceA = node(branch, HeroPathNodeSlot.CHOICE_A)
        val otherBranch = node(HeroPathBranch.WARRIOR_BULWARK, HeroPathNodeSlot.FOUNDATION_A)
        commitVisible(state, foundationA)
        assertMatchesAllocator(state, setOf(foundationB, choiceA))
        val invalidPending = setOf(choiceA) // Cancelling foundation B leaves only two points.
        assertFalse(model(state, invalidPending).nodes.single { it.id == otherBranch }.canDraft)
        assertMatchesAllocator(state, invalidPending)
    }

    private fun gameState(heroClass: BattleHeroClass): SimpleGameState {
        val engine = SimpleGameEngine()
        val simpleClass = HeroClass.valueOf(heroClass.name)
        val rolled = engine.rollStats(77L, simpleClass)
        return engine.newGame("QAPath", simpleClass, rolled.stats, rolled.nextSeed, 1_000L).also {
            it.hero.level = 150L
            it.heroPath = HeroPathEngine.reconcilePointGrants(HeroPathState(heroClass = heroClass), 150L)
        }
    }

    private fun node(branch: HeroPathBranch, slot: HeroPathNodeSlot): String =
        HeroPathCatalog.nodesFor(branch).single { it.slot == slot }.traitId

    private fun allocationOrder(branch: HeroPathBranch): List<String> = listOf(
        HeroPathNodeSlot.FOUNDATION_A, HeroPathNodeSlot.FOUNDATION_B,
        HeroPathNodeSlot.FOUNDATION_A, HeroPathNodeSlot.FOUNDATION_B,
        HeroPathNodeSlot.CHOICE_A, HeroPathNodeSlot.SPECIAL_A,
        HeroPathNodeSlot.SPECIAL_B, HeroPathNodeSlot.ADVANCED_TACTIC,
        HeroPathNodeSlot.ADVANCED_TACTIC, HeroPathNodeSlot.CORE,
    ).map { node(branch, it) }

    private fun model(state: SimpleGameState, drafts: Set<String> = emptySet()) =
        heroPathPanelModel(state, HeroPathFilter.ALL, drafts)

    private fun commitVisible(state: SimpleGameState, id: String) {
        val ui = model(state).nodes.single { it.id == id }
        assertTrue("$id at ${state.heroPath.spentPoints}: ${ui.actionBlockReason}", ui.canDraft)
        val draftUi = model(state, setOf(id)).nodes.single { it.id == id }
        assertEquals(HeroPathNodeStatus.DRAFT, draftUi.status)
        val result = HeroPathEngine.applyDraft(state.heroPath, listOf(id))
        assertEquals(id, HeroPathMutationStatus.APPLIED, result.status)
        state.heroPath = result.state
    }

    private fun assertMatchesAllocator(state: SimpleGameState, drafts: Set<String> = emptySet()) {
        model(state, drafts).nodes.forEach { ui ->
            val expected = ui.id in drafts || HeroPathEngine.applyDraft(
                state.heroPath, (drafts + ui.id).toList(),
            ).status == HeroPathMutationStatus.APPLIED
            assertEquals(ui.id, expected, ui.canDraft)
        }
    }
}
