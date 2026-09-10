package com.nullplaying.ui

import com.nullplaying.BuildConfig
import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.engine.arena.*
import com.nullplaying.model.HeroClass
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ArenaLabProfilesTest {
    @Test fun `all classes receive real growth and level gated ownership`() {
        for (c in HeroClass.entries) {
            val first = ArenaLabProfiles.generate(c, 10)
            for (level in listOf(10, 15, 20, 25, 30, 50, 100)) {
                val p = ArenaLabProfiles.generate(c, level)
                assertEquals(2L * (level - 10), p.stats.values().take(6).sum() - first.stats.values().take(6).sum())
                assertTrue(p.stats.maxHealth >= first.stats.maxHealth)
                assertTrue(p.stats.maxMana >= first.stats.maxMana)
                assertEquals(p, ArenaLabProfiles.generate(c, level))
                val state = ArenaLabProfiles.state(p)
                assertEquals(level.toLong(), state.hero.level)
                assertEquals((level / 5 + 1).coerceAtMost(SimpleGameEngine.MAX_SKILLS), state.skills.size)
                assertTrue(state.skills.all { it.acquiredAtLevel <= level })
                assertEquals(state.skills.size, ArenaTurnInputAdapter.ownedAttackIds(state).size)
                assertEquals(level, ArenaCharacterPointRules.combatBudget(level.toLong()))
                assertEquals(p.stats, state.hero.stats)
                state.hero.stats.strength++
                assertNotEquals(p.stats, state.hero.stats)
            }
        }
    }
    @Test fun `edited stats persist independently by class and level`() {
        val a = ArenaLabProfiles.generate(HeroClass.RANGER, 15).let {
            it.copy(stats = it.stats.copy(strength = 11, constitution = 22, dexterity = 22,
                intelligence = 16, wisdom = 30, charisma = 17, maxHealth = 186, maxMana = 158), combatPowerOverride = 157)
        }
        val b = ArenaLabProfiles.generate(HeroClass.MAGE, 15)
        val d = ArenaLabProfiles.generate(HeroClass.RANGER, 30)
        val book = mapOf(a.key to a, b.key to b, d.key to d)
        val restored = Json.decodeFromString<Map<String, ArenaLabProfile>>(Json.encodeToString(book))
        assertEquals(book, restored)
        assertEquals(3, restored.values.map { it.identity }.distinct().size)
        assertEquals(a.stats, ArenaLabProfiles.state(restored.getValue(a.key)).hero.stats)
        assertEquals(157L, ArenaLabProfiles.combatPower(a))
        assertEquals(b.stats, ArenaLabProfiles.state(b).hero.stats)
    }
    @Test fun `invalid and partial numeric edits are rejected`() {
        val valid = listOf("11", "22", "22", "16", "30", "17", "186", "158")
        assertNotNull(ArenaLabProfiles.parseStats(valid))
        for (bad in listOf("", "-1", "0", "10001", "99999999999999999999", "word")) {
            assertNull(ArenaLabProfiles.parseStats(valid.toMutableList().also { it[0] = bad }))
        }
        assertNull(ArenaLabProfiles.parseStats(valid.dropLast(1)))
        assertFalse(ArenaLabProfiles.validate(ArenaLabProfiles.generate(HeroClass.RANGER, 15).copy(level = 9)))
    }
    @Test fun `unlimited arena accepts an exhausted daily ledger`() {
        assertEquals("com.nullplaying.arenalab", BuildConfig.APPLICATION_ID)
        assertTrue(BuildConfig.BATTLE_UNLIMITED_ENTRIES)
        assertFalse(BuildConfig.REMOTE_SERVICES_ENABLED)
        assertFalse(BuildConfig.SHARED_PLAYER_SYNC_ENABLED)
        assertFalse(BuildConfig.ARENA_SERVER_MATCHING_ENABLED)
        assertEquals("", BuildConfig.SUPABASE_URL)
        assertEquals("", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
        assertEquals("상대 찾기", battleEntryButtonLabel(0, null, unlimitedEntries = true, dailyLimitReached = true))
        assertNotEquals(BattleEntryPrimaryAction.NONE, battleEntryPrimaryAction(0, null,
            unlimitedEntries = true, entryAllowed = true, hasAttackSkill = true, dailyLimitReached = true))
    }
    @Test fun `all classes can enter the current release combat engine with edited profiles`() {
        val results = HeroClass.entries.map { c ->
            val profile = ArenaLabProfiles.generate(c, 15)
            val state = ArenaLabProfiles.state(profile)
            val id = state.skills.first().catalogId
            val f = ArenaTurnInputAdapter.fromStateForCombatPower(state, c.name,
                ArenaLabProfiles.combatPower(profile), mapOf(id to 10))!!.fighter
            ArenaSupportInput(fighter = f, arenaLevel = 15).withIdentityRules()
        }
        for (a in results) for (b in results) {
            val opponent = b.copy(fighter = b.fighter.copy(id = "opponent"))
            val result = ArenaSupportTurnEngine.simulate(a, opponent, 91283L, recordEvents = false)
            assertEquals(ArenaRunStatus.COMPLETED, result.status)
            assertEquals(ARENA_IDENTITY_RULES_VERSION, a.identity!!.version)
        }
    }
}
