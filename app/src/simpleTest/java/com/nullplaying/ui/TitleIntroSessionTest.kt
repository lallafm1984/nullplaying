package com.nullplaying.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleIntroSessionTest {
    @Test
    fun `a title intro gate can be claimed only once per process session`() {
        val gate = TitleIntroGate()

        assertTrue(gate.claim())
        assertFalse(gate.claim())
        assertFalse(gate.claim())
    }
}
