package com.nullplaying.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MainPanelPresentationTest {
    @Test
    fun `hero header level text scales down after the compact level column`() {
        assertEquals(24, heroHeaderLevelFontSizeSp("9"))
        assertEquals(24, heroHeaderLevelFontSizeSp("99"))
        assertEquals(21, heroHeaderLevelFontSizeSp("999"))
        assertEquals(15, heroHeaderLevelFontSizeSp("9,999"))
    }

    @Test
    fun `main status percent truncates visible progress and clamps the bounds`() {
        assertEquals(0, mainStatusPercent(-0.2f))
        assertEquals(0, mainStatusPercent(0f))
        assertEquals(39, mainStatusPercent(2_073_845f / 5_279_391f))
        assertEquals(19, mainStatusPercent(9f / 47f))
        assertEquals(39, mainStatusPercent(0.399f))
        assertEquals(100, mainStatusPercent(1f))
        assertEquals(100, mainStatusPercent(1.2f))
    }

    @Test
    fun `tale progress includes the current act and reaches one hundred only when the last act ends`() {
        assertEquals(0f, mainTaleProgress(0, 0, 0L, 100L), 0f)
        assertEquals(0f, mainTaleProgress(0, 5, 0L, 100L), 0f)
        assertEquals(0.1f, mainTaleProgress(0, 5, 50L, 100L), 0f)
        assertEquals(0.2f, mainTaleProgress(1, 5, 0L, 100L), 0f)
        assertEquals(0.5f, mainTaleProgress(2, 5, 50L, 100L), 0f)

        val lastActStarted = mainTaleProgress(4, 5, 0L, 1_000L)
        val lastActHalfway = mainTaleProgress(4, 5, 500L, 1_000L)
        val lastActAlmostDone = mainTaleProgress(4, 5, 999L, 1_000L)
        val lastActCompleted = mainTaleProgress(4, 5, 1_000L, 1_000L)

        assertEquals(0.8f, lastActStarted, 0f)
        assertEquals(0.9f, lastActHalfway, 0f)
        assertEquals(99, mainStatusPercent(lastActAlmostDone))
        assertEquals(1f, lastActCompleted, 0f)
        assertEquals(100, mainStatusPercent(lastActCompleted))
        assertEquals(1f, mainTaleProgress(8, 5, 0L, 100L), 0f)
    }

    @Test
    fun `tale act label counts completed acts instead of treating the current act as complete`() {
        assertEquals("0/0 완료", mainTaleActCompletionLabel(0, 0, false))
        assertEquals("0/5 완료", mainTaleActCompletionLabel(0, 5, false))
        assertEquals("2/5 완료", mainTaleActCompletionLabel(2, 5, false))
        assertEquals("4/5 완료", mainTaleActCompletionLabel(4, 5, false))
        assertEquals("5/5 완료", mainTaleActCompletionLabel(4, 5, true))
        assertEquals("5/5 완료", mainTaleActCompletionLabel(8, 5, false))
    }
}
