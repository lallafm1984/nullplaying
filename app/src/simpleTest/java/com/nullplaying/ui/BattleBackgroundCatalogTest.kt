package com.nullplaying.ui

import com.nullplaying.R
import org.junit.Assert.assertEquals
import org.junit.Test

class BattleBackgroundCatalogTest {
    @Test
    fun `all class prologues use a unique authored background`() {
        val backgrounds = listOf(
            "prologue.warrior" to R.drawable.battle_prologue_warrior,
            "prologue.rogue" to R.drawable.battle_prologue_rogue,
            "prologue.ranger" to R.drawable.battle_prologue_ranger,
            "prologue.mage" to R.drawable.battle_prologue_mage,
            "prologue.cleric" to R.drawable.battle_prologue_cleric,
            "prologue.paladin" to R.drawable.battle_prologue_paladin,
        ).map { (definitionId, expected) ->
            battleBackgroundResource(definitionId, chapterNumber = 0).also { actual ->
                assertEquals(expected, actual)
            }
        }

        assertEquals(6, backgrounds.distinct().size)
    }

    @Test
    fun `all main chapters use a unique background`() {
        val backgrounds = (1..24).map { chapter ->
            battleBackgroundResource(
                definitionId = "ash_border.c${chapter.toString().padStart(2, '0')}",
                chapterNumber = chapter,
            )
        }

        assertEquals(24, backgrounds.distinct().size)
        assertEquals(R.drawable.battle_chapter_01, backgrounds.first())
        assertEquals(R.drawable.battle_chapter_24, backgrounds.last())
    }

    @Test
    fun `repeat expeditions reuse the closest authored location`() {
        assertEquals(
            R.drawable.battle_chapter_24,
            battleBackgroundResource("ash_border.epilogue_rebuild", chapterNumber = 1),
        )
        assertEquals(
            R.drawable.battle_chapter_19,
            battleBackgroundResource("ash_border.epilogue_waterway", chapterNumber = 4),
        )
        assertEquals(
            R.drawable.battle_chapter_21,
            battleBackgroundResource("ash_border.epilogue_three_lights", chapterNumber = 6),
        )
    }

    @Test
    fun `guardian epilogues reuse the authored location that matches their story`() {
        val expected = listOf(
            "ash_border.epilogue_missing_cart" to R.drawable.battle_chapter_05,
            "ash_border.epilogue_buried_bell" to R.drawable.battle_chapter_04,
            "ash_border.epilogue_root_stair" to R.drawable.battle_chapter_14,
            "ash_border.epilogue_reversed_channel" to R.drawable.battle_chapter_19,
            "ash_border.epilogue_thirteenth_marker" to R.drawable.battle_chapter_03,
            "ash_border.epilogue_fourth_light" to R.drawable.battle_chapter_21,
            "ash_border.epilogue_sealed_arch" to R.drawable.battle_chapter_10,
            "ash_border.epilogue_returning_tracks" to R.drawable.battle_chapter_20,
            "ash_border.epilogue_empty_ledger" to R.drawable.battle_chapter_17,
            "ash_border.epilogue_ash_glass_spiral" to R.drawable.battle_chapter_23,
            "ash_border.epilogue_last_surface_camp" to R.drawable.battle_chapter_13,
            "ash_border.epilogue_star_below" to R.drawable.battle_chapter_24,
        )

        expected.forEach { (definitionId, drawable) ->
            assertEquals(drawable, battleBackgroundResource(definitionId, chapterNumber = 999))
        }
    }

    @Test
    fun `labyrinth environments reuse stable authored locations`() {
        val expected = listOf(
            "labyrinth.root_gate" to R.drawable.battle_chapter_22,
            "labyrinth.drowned_archive" to R.drawable.battle_chapter_17,
            "labyrinth.glass_cavern" to R.drawable.battle_chapter_23,
            "labyrinth.bell_forge" to R.drawable.battle_chapter_04,
            "labyrinth.ash_garden" to R.drawable.battle_chapter_18,
            "labyrinth.starless_stair" to R.drawable.battle_chapter_21,
        )

        expected.forEach { (definitionId, drawable) ->
            assertEquals(drawable, battleBackgroundResource(definitionId, chapterNumber = 999))
        }
    }

    @Test
    fun `invalid chapter falls back to the legacy battle background`() {
        assertEquals(
            R.drawable.battle_background,
            battleBackgroundResource("unknown", chapterNumber = 0),
        )
    }
}
