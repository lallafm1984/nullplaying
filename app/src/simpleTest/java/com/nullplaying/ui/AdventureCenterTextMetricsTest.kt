package com.nullplaying.ui

import com.nullplaying.engine.AdventureEventCatalog
import com.nullplaying.engine.AdventureRelationshipCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdventureCenterTextMetricsTest {
    @Test
    fun `incident body copy uses three lines and scales down only as text grows`() {
        val short = adventureCenterTextMetrics("옆 비틈에 통로를 낸다")
        val medium = adventureCenterTextMetrics("Working together, they made a safe crossing over the broken bridge.")
        val long = adventureCenterTextMetrics(
            "The two adventurers compare every remaining clue before choosing the only passage that will stay open.",
        )

        assertEquals(3, short.maxLines)
        assertEquals(3, medium.maxLines)
        assertEquals(3, long.maxLines)
        assertEquals(21, short.fontSizeSp)
        assertEquals(19, medium.fontSizeSp)
        assertEquals(17, long.fontSizeSp)
        assertTrue(short.fontSizeSp > medium.fontSizeSp)
        assertTrue(medium.fontSizeSp > long.fontSizeSp)
    }

    @Test
    fun `every event and relationship body receives the three line contract`() {
        val texts = buildList {
            AdventureEventCatalog.all.forEach { event ->
                addAll(listOf(event.scene.ko, event.scene.en, event.scene.ja))
                event.approaches.forEach { addAll(listOf(it.title.ko, it.title.en, it.title.ja)) }
            }
            AdventureRelationshipCatalog.all.forEach { scene ->
                addAll(listOf(scene.scene.ko, scene.scene.en, scene.scene.ja))
                scene.approaches.forEach { addAll(listOf(it.title.ko, it.title.en, it.title.ja)) }
            }
        }

        assertTrue(texts.isNotEmpty())
        texts.forEach { text ->
            val metrics = adventureCenterTextMetrics(text)
            assertEquals("$text should receive three lines", 3, metrics.maxLines)
            assertTrue("$text uses an unsupported font size", metrics.fontSizeSp in setOf(17, 19, 21))
        }
    }
}
