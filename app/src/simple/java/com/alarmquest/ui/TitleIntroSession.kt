package com.alarmquest.ui

import java.util.concurrent.atomic.AtomicBoolean

internal class TitleIntroGate {
    private val claimed = AtomicBoolean(false)

    fun claim(): Boolean = claimed.compareAndSet(false, true)
}

// Process-owned so Activity recreation, background return, and roster back navigation do not
// replay the cinematic. A fresh app process receives a fresh gate and plays it once again.
internal object ProcessTitleIntroGate {
    private val gate = TitleIntroGate()

    fun claim(): Boolean = gate.claim()
}
