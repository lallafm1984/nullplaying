package com.nullplaying.ui

import com.nullplaying.engine.arena.ArenaSkillAllocation
import com.nullplaying.engine.arena.ArenaSkillTreeCatalog
import com.nullplaying.engine.arena.ArenaSkillTreeRules
import com.nullplaying.engine.arena.ArenaSkillTreeState
import com.nullplaying.model.HeroClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ArenaSkillTreePointAllocationUxTest {
    private val heroClass = HeroClass.WARRIOR
    private val root = ArenaSkillTreeCatalog.forClass(heroClass).single { it.slotKey == "A01" }
    private val ownedAttacks = ArenaSkillTreeCatalog.forClass(heroClass)
        .filter { it.attackProfile != null }
        .mapTo(linkedSetOf()) { it.id }

    @Test
    fun `an accepted point is published into the local snapshot`() {
        val state = ArenaSkillTreeState(heroClass)
        val snapshot = BattleLocalSnapshot(arenaSkillTree = state)
        val mutation = ArenaSkillTreeRules.allocate(
            state, heroClass, 1, ownedAttacks, root.id, 1, editingEnabled = true,
        )

        val updated = applyArenaSkillTreeMutation(snapshot, mutation)

        assertTrue(mutation.accepted)
        assertEquals(1, updated.arenaSkillTree?.allocations?.single()?.rank)
        assertEquals(1L, updated.arenaSkillTree?.revision)
    }

    @Test
    fun `rapid boundary taps become harmless no ops after the accepted tap`() {
        listOf(
            BoundaryScenario(startRank = 9, arenaLevel = 10, rejection = "rank_max"),
            BoundaryScenario(startRank = 8, arenaLevel = 9, rejection = "point_budget"),
            BoundaryScenario(startRank = 9, arenaLevel = 11, rejection = "rank_max"),
        ).forEach { scenario ->
            val initialState = ArenaSkillTreeState(
                heroClass = heroClass,
                allocations = listOf(ArenaSkillAllocation(root.id, scenario.startRank)),
            )
            val initial = BattleLocalSnapshot(arenaSkillTree = initialState)
            val first = ArenaSkillTreeRules.allocate(
                initialState,
                heroClass,
                scenario.arenaLevel,
                ownedAttacks,
                root.id,
                scenario.startRank + 1,
                editingEnabled = true,
            )
            assertTrue(first.error.orEmpty(), first.accepted)
            val afterFirst = applyArenaSkillTreeMutation(initial, first)
            val latestState = requireNotNull(afterFirst.arenaSkillTree)
            val latestRank = latestState.allocations.single().rank
            val second = ArenaSkillTreeRules.allocate(
                latestState,
                heroClass,
                scenario.arenaLevel,
                ownedAttacks,
                root.id,
                latestRank + 1,
                editingEnabled = true,
            )

            assertEquals(scenario.rejection, second.error)
            assertTrue(isArenaSkillTreeBoundaryNoOp(second.error))
            assertSame(afterFirst, applyArenaSkillTreeMutation(afterFirst, second))
        }
    }

    @Test
    fun `a real rule rejection is not misclassified as a rapid tap no op`() {
        val fresh = ArenaSkillTreeState(heroClass)
        val locked = ArenaSkillTreeCatalog.forClass(heroClass).single { it.slotKey == "A04" }
        val rejected = ArenaSkillTreeRules.allocate(
            fresh, heroClass, 100, ownedAttacks, locked.id, 1, editingEnabled = true,
        )
        assertEquals("prerequisite", rejected.error)
        assertTrue(!isArenaSkillTreeBoundaryNoOp(rejected.error))

        var failed = false
        try {
            applyArenaSkillTreeMutation(BattleLocalSnapshot(arenaSkillTree = fresh), rejected)
        } catch (_: IllegalStateException) {
            failed = true
        }
        assertTrue(failed)
    }

    private data class BoundaryScenario(
        val startRank: Int,
        val arenaLevel: Int,
        val rejection: String,
    )
}
