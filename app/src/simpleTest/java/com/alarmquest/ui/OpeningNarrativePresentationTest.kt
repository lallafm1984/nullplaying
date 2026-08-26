package com.alarmquest.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpeningNarrativePresentationTest {
    private val slides = listOf("첫 번째 장면", "두 번째 장면", "세 번째 장면")

    @Test
    fun `each third of total progress owns one opening slide`() {
        val first = openingNarrativePresentation(slides, totalProgress = 0.25f)
        val second = openingNarrativePresentation(slides, totalProgress = 0.5f)
        val third = openingNarrativePresentation(slides, totalProgress = 5f / 6f)

        assertEquals(1, first.slideNumber)
        assertEquals("첫 번째 장면", first.text)
        assertEquals(0.75f, first.slideProgress, 0.0001f)
        assertEquals(2, second.slideNumber)
        assertEquals("두 번째 장면", second.text)
        assertEquals(0.5f, second.slideProgress, 0.0001f)
        assertEquals(3, third.slideNumber)
        assertEquals("세 번째 장면", third.text)
        assertEquals(0.5f, third.slideProgress, 0.0001f)
    }

    @Test
    fun `slide bar resets after each two second boundary and completes on the third`() {
        val justBeforeSecond = openingNarrativePresentation(slides, totalProgress = 0.3333f)
        val secondStart = openingNarrativePresentation(slides, totalProgress = 1f / 3f)
        val thirdStart = openingNarrativePresentation(slides, totalProgress = 2f / 3f)
        val complete = openingNarrativePresentation(slides, totalProgress = 1f)

        assertEquals(1, justBeforeSecond.slideNumber)
        assertTrue(justBeforeSecond.slideProgress > 0.99f)
        assertEquals(2, secondStart.slideNumber)
        assertEquals(0f, secondStart.slideProgress, 0.0001f)
        assertEquals(3, thirdStart.slideNumber)
        assertEquals(0f, thirdStart.slideProgress, 0.0001f)
        assertEquals(3, complete.slideNumber)
        assertEquals(1f, complete.slideProgress, 0.0001f)
    }
}
