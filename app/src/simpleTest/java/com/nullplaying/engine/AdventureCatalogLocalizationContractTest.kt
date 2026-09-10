package com.nullplaying.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdventureCatalogLocalizationContractTest {
    @Test
    fun `all one hundred events have complete authored copy in every language`() {
        assertEquals(100, AdventureEventCatalog.all.size)
        assertEquals(100, AdventureEventCatalog.all.map { it.id }.distinct().size)
        assertEquals(100, AdventureEventCatalog.all.map { it.title.ko }.distinct().size)
        assertEquals(100, AdventureEventCatalog.all.map { it.title.en }.distinct().size)
        assertEquals(100, AdventureEventCatalog.all.map { it.title.ja }.distinct().size)

        AdventureEventCatalog.all.forEach { event ->
            listOf(
                "title" to event.title,
                "scene" to event.scene,
                "success" to event.success,
                "partial" to event.partial,
                "failure" to event.failure,
                "item" to event.itemName,
            ).forEach { (field, copy) -> assertComplete("${event.id} $field", copy) }
            assertEquals("${event.id} approach count", 3, event.approaches.size)
            event.approaches.forEach { approach ->
                assertComplete("${event.id}/${approach.id} approach", approach.title)
            }
            event.battleRule?.let { battle ->
                assertComplete("${event.id} battle monster", battle.monsterName)
            }
        }
    }

    @Test
    fun `reviewed event copy preserves concrete objects actors and outcomes`() {
        fun event(id: String) = AdventureEventCatalog.all.single { it.id == id }

        assertEquals(
            "A one-person-wide path is secured, allowing the hero to cross.",
            event("bridge").partial.en,
        )
        assertEquals(
            "Open-Air Archive Chronicle Fragment",
            event("buried_library").itemName.en,
        )
        assertEquals(
            "Ancient Seed-Vault Brass Hygrometer",
            event("seed_vault").itemName.en,
        )
        assertEquals("Curse-Cleared Sword Hilt", event("cursed_blade_marker").itemName.en)
        assertEquals("呪いを払った剣の柄", event("cursed_blade_marker").itemName.ja)
        assertEquals(
            "The cliff collapses, forcing a retreat to less favorable ground beyond the debris.",
            event("unstable_cliff_roar").failure.en,
        )
        assertEquals("水たまりの中の二体目の敵", event("reflected_enemy").title.ja)
        assertEquals(
            "Colored Glass from the Child's Lantern",
            event("lost_childs_lantern").itemName.en,
        )
        assertEquals("迷子の子の灯籠の色ガラス", event("lost_childs_lantern").itemName.ja)
        assertEquals(
            "A pouch of spoils grows hotter near the town wall while one coin inside remains cold.",
            event("heating_coin_pouch").scene.en,
        )
        assertEquals("Ownerless Homecoming Medal Ribbon", event("ownerless_medal").itemName.en)
        assertEquals("持ち主のない帰還勲章のリボン", event("ownerless_medal").itemName.ja)
    }

    @Test
    fun `all fifty relationship scenes keep unique faithful success copy`() {
        val scenes = AdventureRelationshipCatalog.all
        assertEquals(50, scenes.size)
        assertEquals(50, scenes.map { it.id }.distinct().size)
        assertEquals(50, scenes.map { it.title.en }.distinct().size)
        assertEquals(50, scenes.map { it.title.ja }.distinct().size)
        assertEquals(50, scenes.map { it.success.ko }.distinct().size)
        assertEquals(50, scenes.map { it.success.en }.distinct().size)
        assertEquals(50, scenes.map { it.success.ja }.distinct().size)

        scenes.forEach { scene ->
            listOf(
                "title" to scene.title,
                "scene" to scene.scene,
                "success" to scene.success,
                "partial" to scene.partial,
                "failure" to scene.failure,
            ).forEach { (field, copy) -> assertComplete("${scene.id} $field", copy) }
            assertEquals("${scene.id} approach count", 3, scene.approaches.size)
            scene.approaches.forEach { approach ->
                assertComplete("${scene.id}/${approach.id} approach", approach.title)
            }
        }

        val allEnglish = scenes.joinToString("\n") { it.success.en }
        val allJapanese = scenes.joinToString("\n") { it.success.ja }
        assertFalse(allEnglish.contains("ends with trust earned", ignoreCase = true))
        assertFalse(allJapanese.contains("を通じて二人の間に信頼が残った"))
        assertEquals(
            "Working the rope together, they made a safe crossing.",
            AdventureRelationshipCatalog.definition("broken_bridge").success.en,
        )
        assertEquals(
            "二人でロープを引き、安全な渡り道を作った。",
            AdventureRelationshipCatalog.definition("broken_bridge").success.ja,
        )
        assertEquals(
            "Another adventurer rings a small bell in the thick fog.",
            AdventureRelationshipCatalog.definition("fog_bell").scene.en,
        )
        assertEquals(
            "Another adventurer is pushing a merchant's cart out of the mud.",
            AdventureRelationshipCatalog.definition("trapped_cart").scene.en,
        )
        assertEquals(
            "別の冒険者が泥にはまった商人の荷車を押している。",
            AdventureRelationshipCatalog.definition("trapped_cart").scene.ja,
        )
    }

    @Test
    fun `all forty adventure traits have complete names advantages and disadvantages`() {
        assertEquals(40, AdventureTraitCatalog.all.size)
        assertEquals(40, AdventureTraitCatalog.all.map { it.id }.distinct().size)
        assertEquals(40, AdventureTraitCatalog.all.map { it.name.ko }.distinct().size)
        assertEquals(40, AdventureTraitCatalog.all.map { it.name.en }.distinct().size)
        assertEquals(40, AdventureTraitCatalog.all.map { it.name.ja }.distinct().size)
        AdventureTraitCatalog.all.forEach { trait ->
            assertComplete("${trait.id} name", trait.name)
            assertComplete("${trait.id} advantage", trait.advantage)
            assertComplete("${trait.id} disadvantage", trait.disadvantage)
            assertTrue("${trait.id} has no opposite", trait.oppositeId.isNotBlank())
        }
        assertEquals(
            "Warmth and clear outcomes may also be reduced",
            AdventureTraitCatalog.definition("R06").disadvantage.en,
        )
        assertEquals(
            "드물게 풀리지 않은 사건을 더 살피지 않고 끝냅니다",
            AdventureTraitCatalog.definition("E04").advantage.ko,
        )
        assertFalse(
            AdventureTraitCatalog.definition("E04").advantage.ko ==
                AdventureTraitCatalog.definition("E06").advantage.ko,
        )
    }

    private fun assertComplete(label: String, copy: AdventureText) {
        assertTrue("$label Korean is blank", copy.ko.isNotBlank())
        assertTrue("$label English is blank", copy.en.isNotBlank())
        assertTrue("$label Japanese is blank", copy.ja.isNotBlank())
        assertFalse("$label English leaked Korean: ${copy.en}", KOREAN.containsMatchIn(copy.en))
        assertFalse("$label Japanese leaked Korean: ${copy.ja}", KOREAN.containsMatchIn(copy.ja))
    }

    private companion object {
        val KOREAN = Regex("[가-힣]")
    }
}
