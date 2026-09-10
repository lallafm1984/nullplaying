package com.nullplaying.engine.arena

import com.nullplaying.model.BattleOutcome
import org.junit.Assert.*
import org.junit.Test

class ArenaCharacterPointRulesTest {
    @Test fun `level budget unlocks at ten and cannot duplicate on reload or multi-level growth`() {
        val expected = mapOf(1L to 0, 9L to 0, 10L to 10, 20L to 20, 25L to 25,
            30L to 30, 99L to 99, 100L to 100, 101L to 100, Long.MAX_VALUE to 100)
        expected.forEach { (level, points) ->
            assertEquals(points, ArenaCharacterPointRules.budget(level))
            val state = ArenaCharacterPointRules.migrate(ArenaProgressionState(), level)
            assertEquals(level >= 10, state.unlocked)
            assertEquals(state, ArenaCharacterPointRules.migrate(state, level))
            assertEquals(0L, state.totalXp)
            assertTrue(ArenaProgressionRules.isValid(state))
        }
        assertEquals(25, ArenaCharacterPointRules.budget(25L))
        assertEquals(15, ArenaCharacterPointRules.budget(25L) - ArenaCharacterPointRules.budget(9L + 1))
    }

    @Test fun `new duels award no XP and retain zero-XP completion dedupe across days`() {
        var state = ArenaCharacterPointRules.migrate(ArenaProgressionState(), 20)
        for ((index, outcome) in BattleOutcome.entries.withIndex()) {
            val id = "new-$index"
            val reserved = ArenaProgressionRules.reserveBattle(state, id, index.toLong(), outcome = outcome)
            assertFalse(requireNotNull(reserved.pending).xpEligible)
            assertEquals(0L, reserved.pending!!.xpAward)
            val completed = ArenaProgressionRules.completeBattle(reserved, id)
            assertEquals(0L, completed.totalXp)
            assertNull(completed.pending)
            assertTrue(id in completed.settledBattleIds)
            assertTrue(ArenaProgressionRules.isValid(completed))
            assertEquals(completed, ArenaProgressionRules.completeBattle(completed, id))
            assertEquals(completed, ArenaProgressionRules.reserveBattle(completed, id, 99))
            state = completed
        }
        assertEquals(2L, state.growthDay)
        assertEquals(0, state.growthEntriesUsed)
    }

    @Test fun `legacy pending must finish before migration and old XP cannot buy new points`() {
        val legacy = ArenaProgressionRules.initialize(ArenaProgressionState(), 20)
        val reserved = ArenaProgressionRules.reserveBattle(legacy, "legacy", 77, outcome = BattleOutcome.USER_WIN)
        assertEquals(reserved, ArenaCharacterPointRules.migrate(reserved, 20))
        val recovered = ArenaProgressionRules.completeBattle(reserved, "legacy")
        val migrated = ArenaCharacterPointRules.migrate(recovered, 20)
        assertEquals(120L, migrated.totalXp)
        assertEquals(recovered.copy(characterLevelPoints = true, revision = recovered.revision + 1), migrated)
        assertEquals(20, ArenaCharacterPointRules.budget(20))
        assertEquals(migrated, ArenaProgressionRules.completeBattle(migrated, "legacy"))
        val next = ArenaProgressionRules.completeBattle(ArenaProgressionRules.reserveBattle(migrated, "new", 77), "new")
        assertEquals(120L, next.totalXp)
        assertTrue(ArenaProgressionRules.isValid(next))
    }

    @Test fun `migrated ledger rejects forged XP-bearing reservation`() {
        val new = ArenaProgressionRules.reserveBattle(ArenaCharacterPointRules.migrate(ArenaProgressionState(), 20), "a", 3)
        assertFalse(ArenaProgressionRules.isValid(new.copy(pending = new.pending!!.copy(xpAward = 120))))
        assertFalse(ArenaProgressionRules.isValid(new.copy(pending = new.pending!!.copy(xpEligible = true))))
    }
}
