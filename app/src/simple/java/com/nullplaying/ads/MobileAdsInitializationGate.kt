package com.nullplaying.ads

import java.util.concurrent.atomic.AtomicBoolean

/** Prevents consent callbacks from initializing the ads SDK more than once. */
internal class MobileAdsInitializationGate {
    private val initializationStarted = AtomicBoolean(false)

    fun tryStart(canRequestAds: Boolean): Boolean =
        canRequestAds && initializationStarted.compareAndSet(false, true)

    fun markFailed() {
        initializationStarted.set(false)
    }
}
