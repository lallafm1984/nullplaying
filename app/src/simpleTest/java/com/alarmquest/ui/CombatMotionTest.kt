package com.alarmquest.ui

import com.alarmquest.engine.SkillCatalog
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CombatMotionTest {
    @Test
    fun `all one hundred twenty signature skills route to reviewed sprite sheets`() {
        assertEquals(120, SkillCatalog.all.size)
        val specs = SkillCatalog.all.map { definition ->
            assertNotNull(definition.catalogId, detailedSpriteSheetSpec(definition.catalogId))
            checkNotNull(detailedSpriteSheetSpec(definition.catalogId))
        }

        assertEquals(120, specs.map { it.assetId }.distinct().size)
        assertTrue(specs.all { it.columns == 4 })
        assertEquals(119, specs.count { it.rows == 4 && it.frameCount == 16 })
        assertEquals(1, specs.count { it.rows == 3 && it.frameCount == 12 })
    }

    @Test
    fun `sixteen frame sheets follow the web frame clock and fade ending`() {
        val spec = checkNotNull(detailedSpriteSheetSpec("rogue_t01_c01"))

        assertEquals(1_350, detailedSpriteDurationMillis(spec))
        assertEquals(0, detailedSpriteFrame(0, spec, reducedMotion = false)?.index)
        assertEquals(6, detailedSpriteFrame(420, spec, reducedMotion = false)?.index)
        assertEquals(7, detailedSpriteFrame(490, spec, reducedMotion = false)?.index)
        assertEquals(11, detailedSpriteFrame(720, spec, reducedMotion = false)?.index)
        assertEquals(14, detailedSpriteFrame(999, spec, reducedMotion = false)?.index)
        assertEquals(14, detailedSpriteFrame(1_100, spec, reducedMotion = false)?.index)
        assertEquals(.2631579f, detailedSpriteFrame(1_225, spec, reducedMotion = false)?.alpha ?: 0f, .0001f)
        assertNull(detailedSpriteFrame(1_350, spec, reducedMotion = false))
    }

    @Test
    fun `the approved twelve frame shatter sheet keeps its native three row layout`() {
        val spec = checkNotNull(detailedSpriteSheetSpec("warrior_t03_c02"))

        assertEquals(3, spec.rows)
        assertEquals(12, spec.frameCount)
        assertEquals(1_100, detailedSpriteDurationMillis(spec))
        assertEquals(6, detailedSpriteFrame(420, spec, reducedMotion = false)?.index)
        assertEquals(11, detailedSpriteFrame(749, spec, reducedMotion = false)?.index)
        assertEquals(11, detailedSpriteFrame(850, spec, reducedMotion = false)?.index)
        assertEquals(.30339807f, detailedSpriteFrame(975, spec, reducedMotion = false)?.alpha ?: 0f, .0001f)
        assertNull(detailedSpriteFrame(1_100, spec, reducedMotion = false))
    }

    @Test
    fun `blade slash ends at one second while reduced motion shows the impact frame`() {
        val spec = checkNotNull(detailedSpriteSheetSpec("warrior_t01_c01"))

        assertEquals(1_000, detailedSpriteDurationMillis(spec))
        assertEquals(14, detailedSpriteFrame(999, spec, reducedMotion = false)?.index)
        assertNull(detailedSpriteFrame(1_000, spec, reducedMotion = false))
        val reduced = checkNotNull(detailedSpriteFrame(420, spec, reducedMotion = true))
        assertEquals(8, reduced.index)
        assertTrue(abs(reduced.alpha - 1f) < .0001f)
        assertNull(detailedSpriteFrame(780, spec, reducedMotion = true))
    }

    @Test
    fun `late impact warrior sheets keep the web F09 eight hundred millisecond clock`() {
        val catalogIds = listOf(
            "warrior_t14_c03",
            "warrior_t17_c01",
            "warrior_t18_c02",
            "warrior_t19_c02",
            "warrior_t20_c01",
        )

        catalogIds.forEach { catalogId ->
            val spec = checkNotNull(detailedSpriteSheetSpec(catalogId))

            assertEquals(catalogId, 1_400, detailedSpriteDurationMillis(spec))
            assertEquals(catalogId, 7, detailedSpriteFrame(799, spec, reducedMotion = false)?.index)
            assertEquals(catalogId, 8, detailedSpriteFrame(800, spec, reducedMotion = false)?.index)
            assertEquals(catalogId, 14, detailedSpriteFrame(1_220, spec, reducedMotion = false)?.index)
            assertEquals(
                catalogId,
                .5f,
                detailedSpriteFrame(1_310, spec, reducedMotion = false)?.alpha ?: 0f,
                .0001f,
            )
            assertNull(catalogId, detailedSpriteFrame(1_400, spec, reducedMotion = false))
        }
    }
}
