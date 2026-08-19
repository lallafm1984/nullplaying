package com.alarmquest.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineAdventurePresentationTest {
    @Test
    fun `percentage uses the same clamped value shown to the player`() {
        assertEquals(0, offlineAdventurePercent(-1f))
        assertEquals(24, offlineAdventurePercent(0.249f))
        assertEquals(25, offlineAdventurePercent(0.25f))
        assertEquals(100, offlineAdventurePercent(2f))
    }

    @Test
    fun `gauge colors change at the four approved boundaries`() {
        assertEquals(Color(0xFFFF5C6C), offlineAdventureColor(0))
        assertEquals(Color(0xFFFF5C6C), offlineAdventureColor(24))
        assertEquals(Color(0xFFF29A49), offlineAdventureColor(25))
        assertEquals(Color(0xFFF29A49), offlineAdventureColor(49))
        assertEquals(Color(0xFFE7C55A), offlineAdventureColor(50))
        assertEquals(Color(0xFFE7C55A), offlineAdventureColor(74))
        assertEquals(Color(0xFF8BCB84), offlineAdventureColor(75))
        assertEquals(Color(0xFF8BCB84), offlineAdventureColor(100))
    }
}
