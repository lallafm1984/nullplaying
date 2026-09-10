package com.nullplaying.ui

import com.nullplaying.engine.arena.ArenaSkillNodeKind
import com.nullplaying.engine.arena.ArenaSkillTreeCatalog
import com.nullplaying.engine.arena.ArenaSkillTreeRules
import com.nullplaying.model.HeroClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArenaProgressionQaInitialStateTest {
    @Test
    fun `maximum review budget starts every class with one hundred unspent points`() {
        assertEquals(6, HeroClass.entries.size)
        HeroClass.entries.forEach { heroClass ->
            val ownedAttackIds = ArenaSkillTreeCatalog.forClass(heroClass)
                .filter { it.kind == ArenaSkillNodeKind.ATTACK }
                .mapTo(linkedSetOf()) { it.id }
            val state = arenaProgressionQaInitialState(
                heroClass = heroClass,
                arenaLevel = ArenaSkillTreeRules.maxArenaLevel,
                allocatedPoints = 0,
                seed = 1L,
                focusedRootSlot = null,
                focusedRootRank = 0,
                ownedAttackIds = ownedAttackIds,
            )
            val view = ArenaSkillTreeRules.view(
                state = state,
                arenaLevel = ArenaSkillTreeRules.maxArenaLevel,
                ownedAttackIds = ownedAttackIds,
            )

            assertTrue(view.valid)
            assertEquals(0, view.spentPoints)
            assertEquals(100, view.availablePoints)
        }
    }
}
