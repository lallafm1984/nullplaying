package com.nullplaying.ui

import androidx.compose.ui.text.style.TextOverflow
import com.nullplaying.localization.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalizedTextLayoutTest {
    @Test
    fun `compact English labels shrink and never exceed two implicit lines`() {
        val policy = localizedTextLayoutPolicy(
            sourceLength = 12,
            translatedLength = 28,
            language = AppLanguage.ENGLISH,
            requestedMaxLines = Int.MAX_VALUE,
        )

        assertEquals(2, policy.maxLines)
        assertEquals(0.9f, policy.typeScale)
        assertTrue(policy.ellipsizeClippedText)
    }

    @Test
    fun `long narrative text keeps its authored multi-line layout`() {
        val policy = localizedTextLayoutPolicy(
            sourceLength = 120,
            translatedLength = 180,
            language = AppLanguage.ENGLISH,
            requestedMaxLines = Int.MAX_VALUE,
        )

        assertEquals(Int.MAX_VALUE, policy.maxLines)
        assertEquals(1f, policy.typeScale)
    }

    @Test
    fun `explicit one-line contracts remain one line in Japanese`() {
        val policy = localizedTextLayoutPolicy(
            sourceLength = 8,
            translatedLength = 18,
            language = AppLanguage.JAPANESE,
            requestedMaxLines = 1,
        )

        assertEquals(1, policy.maxLines)
        assertEquals(0.96f, policy.typeScale)
        assertTrue(policy.ellipsizeClippedText)
    }

    @Test
    fun `language-neutral counters keep their authored size`() {
        val policy = localizedTextLayoutPolicy(
            sourceLength = 4,
            translatedLength = 4,
            language = AppLanguage.ENGLISH,
            requestedMaxLines = 1,
        )

        assertEquals(1f, policy.typeScale)
    }

    @Test
    fun `measured overflow shrinks in bounded steps without becoming unreadable`() {
        assertEquals(0.96f, nextAdaptiveTextScale(1f, hasVisualOverflow = true), 0.0001f)
        assertEquals(0.78f, nextAdaptiveTextScale(0.8f, hasVisualOverflow = true), 0.0001f)
        assertEquals(0.78f, nextAdaptiveTextScale(0.78f, hasVisualOverflow = true), 0.0001f)
        assertEquals(1f, nextAdaptiveTextScale(1f, hasVisualOverflow = false), 0.0001f)
    }

    @Test
    fun `market and loot result item names wrap without ellipsis`() {
        assertEquals(2, MARKET_ACTION_ITEM_MAX_LINES)
        assertEquals(TextOverflow.Visible, MARKET_ACTION_ITEM_OVERFLOW)
        assertEquals(11f, marketActionItemFontSizeSp(48), 0.0001f)

        assertEquals(3, LOOT_RESULT_ITEM_MAX_LINES)
        assertEquals(TextOverflow.Visible, LOOT_RESULT_ITEM_OVERFLOW)
        assertEquals(218, LOOT_RESULT_PANEL_HEIGHT_DP)
        assertEquals(13f, lootResultItemFontSizeSp(48), 0.0001f)
        assertEquals(3f, lootResultContentVerticalPaddingDp(48), 0.0001f)
        assertEquals(4f, lootResultStatusVerticalPaddingDp(48), 0.0001f)
        assertEquals(16f, lootResultItemLineHeightSp(48), 0.0001f)
    }

    @Test
    fun `long localized monster names receive a two-line combat header`() {
        assertEquals(1, monsterHeaderMaxLines(42))
        assertEquals(58, monsterHeaderHeightDp(42))
        assertEquals(13f, monsterHeaderFontSizeSp(42), 0.0001f)

        assertEquals(2, monsterHeaderMaxLines(69))
        assertEquals(72, monsterHeaderHeightDp(69))
        assertEquals(11f, monsterHeaderFontSizeSp(69), 0.0001f)
        assertEquals(14f, monsterHeaderLineHeightSp(69), 0.0001f)
        assertEquals(4, monsterHeaderProgressSpacingDp(69))
        assertEquals(TextOverflow.Visible, MONSTER_HEADER_OVERFLOW)
    }
}
