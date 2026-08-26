package com.alarmquest.ui

import androidx.annotation.DrawableRes
import com.alarmquest.R

@DrawableRes
internal fun battleBackgroundResource(
    definitionId: String,
    chapterNumber: Int,
): Int {
    val prologueBackground = when (definitionId) {
        "prologue.warrior" -> R.drawable.battle_prologue_warrior
        "prologue.rogue" -> R.drawable.battle_prologue_rogue
        "prologue.ranger" -> R.drawable.battle_prologue_ranger
        "prologue.mage" -> R.drawable.battle_prologue_mage
        "prologue.cleric" -> R.drawable.battle_prologue_cleric
        "prologue.paladin" -> R.drawable.battle_prologue_paladin
        else -> null
    }
    if (prologueBackground != null) return prologueBackground

    val epilogueBackground = when (definitionId) {
        "ash_border.epilogue_rebuild" -> R.drawable.battle_chapter_24
        "ash_border.epilogue_old_bell" -> R.drawable.battle_chapter_12
        "ash_border.epilogue_glass_leaf" -> R.drawable.battle_chapter_14
        "ash_border.epilogue_waterway" -> R.drawable.battle_chapter_19
        "ash_border.epilogue_memorial" -> R.drawable.battle_chapter_03
        "ash_border.epilogue_three_lights" -> R.drawable.battle_chapter_21
        "ash_border.epilogue_missing_cart" -> R.drawable.battle_chapter_05
        "ash_border.epilogue_buried_bell" -> R.drawable.battle_chapter_04
        "ash_border.epilogue_root_stair" -> R.drawable.battle_chapter_14
        "ash_border.epilogue_reversed_channel" -> R.drawable.battle_chapter_19
        "ash_border.epilogue_thirteenth_marker" -> R.drawable.battle_chapter_03
        "ash_border.epilogue_fourth_light" -> R.drawable.battle_chapter_21
        "ash_border.epilogue_sealed_arch" -> R.drawable.battle_chapter_10
        "ash_border.epilogue_returning_tracks" -> R.drawable.battle_chapter_20
        "ash_border.epilogue_empty_ledger" -> R.drawable.battle_chapter_17
        "ash_border.epilogue_ash_glass_spiral" -> R.drawable.battle_chapter_23
        "ash_border.epilogue_last_surface_camp" -> R.drawable.battle_chapter_13
        "ash_border.epilogue_star_below" -> R.drawable.battle_chapter_24
        "labyrinth.root_gate" -> R.drawable.battle_chapter_22
        "labyrinth.drowned_archive" -> R.drawable.battle_chapter_17
        "labyrinth.glass_cavern" -> R.drawable.battle_chapter_23
        "labyrinth.bell_forge" -> R.drawable.battle_chapter_04
        "labyrinth.ash_garden" -> R.drawable.battle_chapter_18
        "labyrinth.starless_stair" -> R.drawable.battle_chapter_21
        else -> null
    }
    if (epilogueBackground != null) return epilogueBackground

    return when (chapterNumber) {
        1 -> R.drawable.battle_chapter_01
        2 -> R.drawable.battle_chapter_02
        3 -> R.drawable.battle_chapter_03
        4 -> R.drawable.battle_chapter_04
        5 -> R.drawable.battle_chapter_05
        6 -> R.drawable.battle_chapter_06
        7 -> R.drawable.battle_chapter_07
        8 -> R.drawable.battle_chapter_08
        9 -> R.drawable.battle_chapter_09
        10 -> R.drawable.battle_chapter_10
        11 -> R.drawable.battle_chapter_11
        12 -> R.drawable.battle_chapter_12
        13 -> R.drawable.battle_chapter_13
        14 -> R.drawable.battle_chapter_14
        15 -> R.drawable.battle_chapter_15
        16 -> R.drawable.battle_chapter_16
        17 -> R.drawable.battle_chapter_17
        18 -> R.drawable.battle_chapter_18
        19 -> R.drawable.battle_chapter_19
        20 -> R.drawable.battle_chapter_20
        21 -> R.drawable.battle_chapter_21
        22 -> R.drawable.battle_chapter_22
        23 -> R.drawable.battle_chapter_23
        24 -> R.drawable.battle_chapter_24
        else -> R.drawable.battle_background
    }
}
