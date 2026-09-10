package com.nullplaying.engine.arena

import com.nullplaying.engine.SkillCatalog
import com.nullplaying.model.HeroClass
import org.junit.Assert.*
import org.junit.Test

class ArenaSkillNodeResetTest {
    private fun owned(heroClass: HeroClass, level: Int = 100) = SkillCatalog.forClass(heroClass)
        .filter { it.unlockLevel <= level }.mapTo(linkedSetOf()) { it.catalogId }

    @Test fun `single skill refunds exactly its rank without changing any other skill and duplicate tap is harmless`() {
        for (heroClass in HeroClass.entries) {
            val roots = ArenaSkillTreeCatalog.forClass(heroClass).filter { it.isRoot }
            val state = ArenaSkillTreeState(heroClass, 7,
                listOf(ArenaSkillAllocation(roots[0].id, 4), ArenaSkillAllocation(roots[1].id, 2)))
            val reset = ArenaSkillTreeRules.resetNode(state, heroClass, 20, owned(heroClass), roots[0].id, true)
            assertTrue(reset.accepted)
            assertEquals(8L, reset.state.revision)
            assertEquals(listOf(state.allocations[1]), reset.state.allocations)
            assertEquals(4, ArenaSkillTreeRules.spentPoints(state) - ArenaSkillTreeRules.spentPoints(reset.state))
            assertEquals(reset.state, ArenaSkillTreeRules.resetNode(reset.state, heroClass, 20, owned(heroClass), roots[0].id, true).state)
        }
    }

    @Test fun `a prerequisite cannot be removed while a dependent skill remains`() {
        for (heroClass in HeroClass.entries) {
            val nodes = ArenaSkillTreeCatalog.forClass(heroClass)
            val child = nodes.first { it.row == 1 }
            val parent = child.parentAnyOf.first()
            val state = ArenaSkillTreeState(heroClass, allocations = listOf(
                ArenaSkillAllocation(parent, 3), ArenaSkillAllocation(child.id, 1)))
            assertTrue(ArenaSkillTreeRules.validate(state, heroClass, 20, owned(heroClass)))
            val blocked = ArenaSkillTreeRules.resetNode(state, heroClass, 20, owned(heroClass), parent, true)
            assertEquals("dependent_skills", blocked.error)
            assertEquals(state, blocked.state)
            val leafReset = ArenaSkillTreeRules.resetNode(state, heroClass, 20, owned(heroClass), child.id, true)
            assertTrue(leafReset.accepted)
            assertTrue(ArenaSkillTreeRules.resetNode(leafReset.state, heroClass, 20, owned(heroClass), parent, true).accepted)
        }
    }

    @Test fun `earlier row spending still protects unrelated downstream skills`() {
        for (heroClass in HeroClass.entries) {
            val nodes = ArenaSkillTreeCatalog.forClass(heroClass)
            val last = nodes.first { it.row == 2 }
            val middle = nodes.first { it.id == last.parentAnyOf.first() }
            val root = middle.parentAnyOf.first()
            val spare = nodes.first { it.isRoot && it.id != root }.id
            val state = ArenaSkillTreeState(heroClass, allocations = listOf(
                ArenaSkillAllocation(root, 3), ArenaSkillAllocation(spare, 1),
                ArenaSkillAllocation(middle.id, 3), ArenaSkillAllocation(last.id, 1)))
            assertTrue(ArenaSkillTreeRules.validate(state, heroClass, 20, owned(heroClass)))
            val result = ArenaSkillTreeRules.resetNode(state, heroClass, 20, owned(heroClass), spare, true)
            assertFalse(result.accepted)
            assertEquals("dependent_skills", result.error)
            assertEquals(state, result.state)
        }
    }

    @Test fun `all six classes ten presets and five budgets preserve valid allocations after every possible reset`() {
        var accepted = 0
        var blocked = 0
        for (level in listOf(10, 20, 25, 30, 100)) for (heroClass in HeroClass.entries) for (preset in ArenaAutoBuildPreset.entries) {
            val attacks = owned(heroClass, level)
            val state = ArenaSkillTreeRules.autoAllocateWithPreset(heroClass, level, attacks, 17, preset)
            val view = ArenaSkillTreeRules.view(state, level, attacks)
            for (allocation in state.allocations) {
                val mutation = ArenaSkillTreeRules.resetNode(state, heroClass, level, attacks, allocation.nodeId, true)
                assertEquals(mutation.accepted, view.nodes.first { it.definition.id == allocation.nodeId }.canReset)
                if (mutation.accepted) {
                    accepted++
                    assertEquals(state.allocations.filterNot { it.nodeId == allocation.nodeId }, mutation.state.allocations)
                    assertTrue(ArenaSkillTreeRules.validate(mutation.state, heroClass, level, attacks))
                    assertEquals(allocation.rank, ArenaSkillTreeRules.spentPoints(state) - ArenaSkillTreeRules.spentPoints(mutation.state))
                } else {
                    blocked++
                    assertEquals("dependent_skills", mutation.error)
                    assertEquals(state, mutation.state)
                }
            }
        }
        assertTrue(accepted > 0 && blocked > 0)
        println("single-skill-reset accepted=$accepted dependency-blocked=$blocked")
    }

    @Test fun `battle lock and revision overflow cannot refund points`() {
        val heroClass = HeroClass.RANGER
        val root = ArenaSkillTreeCatalog.forClass(heroClass).first { it.isRoot }
        val state = ArenaSkillTreeState(heroClass, allocations = listOf(ArenaSkillAllocation(root.id, 1)))
        val locked = ArenaSkillTreeRules.resetNode(state, heroClass, 20, owned(heroClass), root.id, false)
        assertEquals("editing_locked", locked.error)
        assertEquals(state, locked.state)
        assertEquals("revision_overflow", ArenaSkillTreeRules.resetNode(
            state.copy(revision = Long.MAX_VALUE), heroClass, 20, owned(heroClass), root.id, true).error)
    }
}
