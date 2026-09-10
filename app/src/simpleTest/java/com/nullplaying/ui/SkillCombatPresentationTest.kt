package com.nullplaying.ui

import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.engine.SkillCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillCombatPresentationTest {
    @Test
    fun `all one hundred twenty skills follow the reviewed sprite hit clock`() {
        assertEquals(120, SkillCatalog.all.size)

        SkillCatalog.all.forEach { definition ->
            val expected = when (definition.catalogId) {
                "warrior_t01_c01" -> listOf(490)
                "warrior_t03_c02" -> listOf(250)
                "warrior_t06_c05" -> listOf(63, 125, 188, 250, 313, 500)
                "warrior_t14_c03",
                "warrior_t17_c01",
                "warrior_t18_c02",
                "warrior_t19_c02",
                "warrior_t20_c01",
                -> listOf(800)
                "warrior_t16_c05" ->
                    listOf(63, 100, 138, 175, 213, 250, 288, 325, 363, 400, 438, 500)
                else -> when (definition.hitCount) {
                    1 -> listOf(500)
                    2 -> listOf(313, 500)
                    3 -> listOf(188, 313, 500)
                    4 -> listOf(125, 250, 375, 500)
                    5 -> listOf(125, 219, 313, 406, 500)
                    else -> error("Unreviewed hit count ${definition.hitCount}")
                }
            }

            assertEquals(definition.catalogId, expected, definition.hitTimingsMillis)
            assertEquals(definition.catalogId, definition.hitCount, skillPresentationHits(definition).size)
            val spec = checkNotNull(detailedSpriteSheetSpec(definition.catalogId))
            definition.hitTimingsMillis.forEach { timing ->
                assertTrue(
                    "${definition.catalogId} has no VFX frame at ${timing}ms",
                    detailedSpriteFrame(timing, spec, reducedMotion = false) != null,
                )
            }
        }
    }

    @Test
    fun `multi hit damage stays visible and updates to the cumulative total on every hit`() {
        val totalDamage = 10_007L

        SkillCatalog.all.forEach { definition ->
            val rawDamages = splitSkillDamage(totalDamage, definition)
            var cumulative = 0L
            definition.hitTimingsMillis.forEachIndexed { index, timing ->
                cumulative += rawDamages[index]
                val atHit = skillDamageFrame(timing, definition, totalDamage)
                assertTrue("${definition.catalogId} hit ${index + 1} is hidden", atHit.visible)
                assertEquals(definition.catalogId, cumulative, atHit.damage)
                assertEquals(index == definition.hitTimingsMillis.lastIndex, atHit.isFinal)

                definition.hitTimingsMillis.getOrNull(index + 1)?.let { nextTiming ->
                    val beforeNextHit = skillDamageFrame(nextTiming - 1, definition, totalDamage)
                    assertTrue(definition.catalogId, beforeNextHit.visible)
                    assertEquals(definition.catalogId, cumulative, beforeNextHit.damage)
                }
            }
            assertEquals(definition.catalogId, totalDamage, cumulative)
        }
    }

    @Test
    fun `shadowless flurry presents five cuts without changing its total damage`() {
        val definition = checkNotNull(SkillCatalog.find("warrior_t13_c03"))
        val totalDamage = 12_345L

        assertEquals(5, definition.hitCount)
        assertEquals(listOf(15, 17, 18, 20, 30), definition.hitWeights)
        assertEquals(listOf(125, 219, 313, 406, 500), definition.hitTimingsMillis)
        assertEquals(330, definition.damagePercentMin)
        assertEquals(340, definition.damagePercentMax)
        assertEquals(totalDamage, splitSkillDamage(totalDamage, definition).sum())
        assertEquals(totalDamage, cumulativeSkillDamage(totalDamage, definition).last())
    }

    @Test
    fun `final damage gets the requested half second extension without overlapping the next attack`() {
        SkillCatalog.all.forEach { definition ->
            val finalHit = definition.hitTimingsMillis.last()
            val end = skillFinalDamageEndMillis(definition)
            val requestedLifetime = if (definition.catalogId == "warrior_t01_c01") 900 else 920
            val expectedLifetime = minOf(
                requestedLifetime,
                SKILL_PRESENTATION_DURATION_MILLIS - finalHit,
            )

            assertEquals(definition.catalogId, expectedLifetime, end - finalHit)
            assertTrue(definition.catalogId, skillDamageFrame(end - 1, definition, 1_000L).visible)
            assertFalse(definition.catalogId, skillDamageFrame(end, definition, 1_000L).visible)
        }
        assertEquals(500, DAMAGE_DISPLAY_EXTENSION_MILLIS)
        assertEquals(SKILL_PRESENTATION_DURATION_MILLIS.toLong(), SimpleGameEngine.ATTACK_PRESENTATION_MILLIS)
    }

    @Test
    fun `skill name remains visible through each authored effect ending`() {
        SkillCatalog.all.forEach { definition ->
            val vfxEnd = skillVfxEndMillis(definition)

            assertTrue(definition.catalogId, skillLabelAlpha(definition.hitTimingsMillis.first(), definition) > 0f)
            assertTrue(definition.catalogId, skillLabelAlpha(vfxEnd - 1, definition) > 0f)
            assertEquals(definition.catalogId, 0f, skillLabelAlpha(vfxEnd, definition), 0f)
        }
    }
}
