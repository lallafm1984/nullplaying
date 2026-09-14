package com.nullplaying.engine

import com.nullplaying.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class MythicDiscoveryTest {
    private fun game(): SimpleGameState {
        val engine = SimpleGameEngine()
        val rolled = engine.rollStats(71L, HeroClass.WARRIOR)
        return engine.newGame("별빛", HeroClass.WARRIOR, rolled.stats, rolled.nextSeed, 1_000L).also {
            it.rankingCharacterId = "10000000-0000-4000-8000-000000000001"
        }
    }
    @Test fun `real equipment drop records both equipped and bag mythics once`() {
        val engine = SimpleGameEngine()
        val rngType = Class.forName("com.nullplaying.engine.SimpleGameEngine\$StableRng")
        val ctor = rngType.getDeclaredConstructor(Long::class.javaPrimitiveType).apply { isAccessible = true }
        val drop = SimpleGameEngine::class.java.declaredMethods.single { it.name == "addEquipmentDrop" }.apply { isAccessible = true }
        val state = game()
        state.hero.level = 30
        // Exercise the real random acquisition path, not an inventory scan or a fabricated UI event.
        var mythicSeed: Long? = null
        for (seed in 1L..200_000L) {
            state.inventory.clear()
            state.equipment.forEach { it.power = 0 }
            drop.invoke(engine, state, ctor.newInstance(seed), 10_000L, mutableListOf<RecentAdventureEvent>(), "PRIMARY", 5)
            if (state.mythicDiscoveries.isNotEmpty()) { mythicSeed = seed; break }
        }
        assertNotNull("fixture must reach the real mythic roll", mythicSeed)
        assertTrue(state.equipment.any { it.rarity == "신화" })
        state.inventory.clear()
        state.equipment.forEach { it.power = Long.MAX_VALUE }
        drop.invoke(engine, state, ctor.newInstance(mythicSeed!!), 11_000L, mutableListOf<RecentAdventureEvent>(), "PRIMARY", 5)
        assertEquals(2, state.mythicDiscoveries.size)
        assertTrue(state.inventory.any { it.rarity == "신화" })
        state.inventory.clear()
        assertEquals(2, Json.decodeFromString<SimpleGameState>(Json.encodeToString(state)).mythicDiscoveries.size)
    }
    @Test fun `trusted clock correction preserves acquisition identity and age`() {
        val state = game()
        recordMythicDiscovery(state, 1, "검 +5", "신화", EquipmentSlot.WEAPON, 200, 9_000)
        val id = state.mythicDiscoveries.single().eventId
        AdventureTimelineRebase.rebase(state, 10_000, 5_000)
        assertEquals(id, state.mythicDiscoveries.single().eventId)
        assertEquals(4_000L, state.mythicDiscoveries.single().discoveredAt)
    }
    @Test fun `only mythic acquisitions persist and repeated acquisition does not duplicate`() {
        val state = game()
        recordMythicDiscovery(state, 1, "검 +4", "전설", EquipmentSlot.WEAPON, 100, 1_000)
        assertTrue(state.mythicDiscoveries.isEmpty())
        recordMythicDiscovery(state, 2, "검 +5", "신화", EquipmentSlot.WEAPON, 200, 2_000)
        state.inventory.clear()
        val restored = Json.decodeFromString<SimpleGameState>(Json.encodeToString(state))
        recordMythicDiscovery(restored, 2, "검 +5", "신화", EquipmentSlot.WEAPON, 200, 3_000)
        assertEquals(1, restored.mythicDiscoveries.size)
        assertEquals(2_000L, restored.mythicDiscoveries.single().discoveredAt)
    }
    @Test fun `local outbox retains most recent hundred and ids differ across characters`() {
        val a = game(); val b = game().also { it.rankingCharacterId = "10000000-0000-4000-8000-000000000002" }
        for (i in 1L..105L) recordMythicDiscovery(a, i, "검 +5", "신화", EquipmentSlot.WEAPON, 200, i)
        recordMythicDiscovery(b, 105, "검 +5", "신화", EquipmentSlot.WEAPON, 200, 105)
        assertEquals(100, a.mythicDiscoveries.size)
        assertEquals(6L, a.mythicDiscoveries.first().discoveredAt)
        assertNotEquals(a.mythicDiscoveries.last().eventId, b.mythicDiscoveries.last().eventId)
    }
}
