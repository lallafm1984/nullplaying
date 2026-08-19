package com.alarmquest.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MainPanelPresentationTest {
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
    fun `tale progress uses the displayed one-based act position`() {
        assertEquals(0f, mainTaleProgress(currentActIndex = 0, actCount = 0), 0f)
        assertEquals(0.2f, mainTaleProgress(currentActIndex = 0, actCount = 5), 0f)
        assertEquals(1f, mainTaleProgress(currentActIndex = 4, actCount = 5), 0f)
        assertEquals(1f, mainTaleProgress(currentActIndex = 8, actCount = 5), 0f)
    }
}
