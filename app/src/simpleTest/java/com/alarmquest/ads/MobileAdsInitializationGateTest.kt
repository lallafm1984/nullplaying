package com.alarmquest.ads

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileAdsInitializationGateTest {
    @Test
    fun `initialization waits for consent and runs only once`() {
        val gate = MobileAdsInitializationGate()

        assertFalse(gate.tryStart(canRequestAds = false))
        assertTrue(gate.tryStart(canRequestAds = true))
        assertFalse(gate.tryStart(canRequestAds = true))
    }

    @Test
    fun `failed initialization can be retried after consent remains valid`() {
        val gate = MobileAdsInitializationGate()

        assertTrue(gate.tryStart(canRequestAds = true))
        gate.markFailed()
        assertTrue(gate.tryStart(canRequestAds = true))
    }
}
