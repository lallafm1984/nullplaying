package com.nullplaying.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedGameClockTest {
    private val elapsed = MutableElapsedRealtime(100L)
    private val boot = MutableBootCount(7L)
    private val store = InMemoryAnchorStore()

    @Test
    fun `clock is unavailable before an anchor is installed`() {
        assertNull(clock().nowOrNull())
    }

    @Test
    fun `same boot uses elapsed realtime and ignores wall clock edits`() {
        val clock = clock()
        var deviceWallClock = 5_000L
        assertEquals(
            1_000L,
            clock.installVerifiedServerObservation(1_000L)?.epochMillis,
        )

        deviceWallClock += 24L * 60L * 60L * 1_000L
        assertEquals(1_000L, clock.nowOrNull()?.epochMillis)

        elapsed.value += 250L
        deviceWallClock -= 48L * 60L * 60L * 1_000L
        assertEquals(1_250L, clock.nowOrNull()?.epochMillis)
        assertTrue(deviceWallClock < 0L)
    }

    @Test
    fun `persisted anchor survives process recreation in the same boot`() {
        val firstProcess = clock()
        firstProcess.installVerifiedServerObservation(10_000L)
        elapsed.value = 600L

        val recreatedProcess = clock()

        assertEquals(10_500L, recreatedProcess.nowOrNull()?.epochMillis)
        assertEquals(
            TrustedTimeConfidence.VERIFIED_SERVER,
            recreatedProcess.nowOrNull()?.confidence,
        )
    }

    @Test
    fun `boot mismatch makes a persisted anchor unavailable`() {
        clock().installVerifiedServerObservation(10_000L)

        boot.value = requireNotNull(boot.value) + 1L

        assertNull(clock().nowOrNull())
    }

    @Test
    fun `elapsed realtime regression makes an anchor unavailable`() {
        elapsed.value = 1_000L
        clock().installVerifiedServerObservation(10_000L)

        elapsed.value = 999L

        assertNull(clock().nowOrNull())
    }

    @Test
    fun `server preview does not mutate until adopted`() {
        val clock = clock()

        val preview = requireNotNull(clock.previewVerifiedServerObservation(5_000L))

        assertEquals(5_000L, preview.proposedEpochMillis)
        assertNull(clock.nowOrNull())
        assertNull(store.read())

        elapsed.value = 125L
        val adopted = clock.adoptVerifiedServerObservation(preview)

        assertEquals(5_025L, adopted?.epochMillis)
        assertTrue(adopted?.isServerVerified == true)
    }

    @Test
    fun `verified refresh cannot move trusted time backwards`() {
        val clock = clock()
        clock.installVerifiedServerObservation(1_000L)
        elapsed.value = 200L
        assertEquals(1_100L, clock.nowOrNull()?.epochMillis)

        val preview = requireNotNull(clock.previewVerifiedServerObservation(1_050L))
        assertEquals(1_100L, preview.proposedEpochMillis)

        elapsed.value = 220L
        assertEquals(1_120L, clock.adoptVerifiedServerObservation(preview)?.epochMillis)
    }

    @Test
    fun `verified refresh rejects an implausible forward server date`() {
        val clock = clock()
        clock.installVerifiedServerObservation(1_000_000L)
        elapsed.value = 200L
        val before = requireNotNull(clock.nowOrNull())

        val implausible = before.epochMillis +
            TrustedGameClock.MAX_VERIFIED_REFRESH_FORWARD_CORRECTION_MILLIS + 1L

        assertNull(clock.previewVerifiedServerObservation(implausible))
        assertEquals(before, clock.nowOrNull())
    }

    @Test
    fun `verified server observation can correct a provisional anchor backwards`() {
        val clock = clock()
        clock.installProvisionalAnchor(10_000L)
        elapsed.value = 200L

        val preview = requireNotNull(clock.previewVerifiedServerObservation(1_000L))

        assertEquals(10_100L, preview.previousReading?.epochMillis)
        assertEquals(1_000L, preview.proposedEpochMillis)
        assertTrue(preview.correctsProvisionalBackwards)

        val adopted = requireNotNull(clock.adoptVerifiedServerObservation(preview))
        assertEquals(1_000L, adopted.epochMillis)
        assertEquals(TrustedTimeConfidence.VERIFIED_SERVER, adopted.confidence)
    }

    @Test
    fun `provisional install never replaces a usable anchor`() {
        val clock = clock()
        clock.installVerifiedServerObservation(1_000L)
        elapsed.value = 150L

        val result = clock.installProvisionalAnchor(99_000L)

        assertEquals(1_050L, result?.epochMillis)
        assertEquals(TrustedTimeConfidence.VERIFIED_SERVER, result?.confidence)
        assertEquals(1_000L, store.read()?.serverEpochMs)
    }

    @Test
    fun `provisional install can recover after reboot when caller supplies a bound`() {
        val clock = clock()
        clock.installVerifiedServerObservation(1_000L)
        boot.value = 8L
        elapsed.value = 20L
        assertNull(clock.nowOrNull())

        val result = clock.installProvisionalAnchor(1_500L)

        assertEquals(1_500L, result?.epochMillis)
        assertEquals(TrustedTimeConfidence.PROVISIONAL, result?.confidence)
        assertEquals(8L, store.read()?.bootCount)
    }

    @Test
    fun `checkpoint persists the latest projection and retains confidence`() {
        val clock = clock()
        clock.installVerifiedServerObservation(1_000L)
        elapsed.value = 500L

        val checkpoint = requireNotNull(clock.checkpoint())
        val persisted = requireNotNull(store.read())

        assertEquals(1_400L, checkpoint.epochMillis)
        assertEquals(1_400L, persisted.serverEpochMs)
        assertEquals(500L, persisted.observedElapsedMs)
        assertEquals(TrustedTimeConfidence.VERIFIED_SERVER, persisted.confidence)

        boot.value = requireNotNull(boot.value) + 1L
        elapsed.value = 1L
        assertNull(clock.nowOrNull())
        assertEquals(1_400L, clock.persistedAnchorOrNull()?.serverEpochMs)
    }

    @Test
    fun `stale server preview is rejected after another anchor mutation`() {
        val clock = clock()
        clock.installVerifiedServerObservation(1_000L)
        val stalePreview = requireNotNull(clock.previewVerifiedServerObservation(2_000L))
        elapsed.value = 150L
        clock.checkpoint()

        assertNull(clock.adoptVerifiedServerObservation(stalePreview))
        assertEquals(1_050L, clock.nowOrNull()?.epochMillis)
    }

    @Test
    fun `server preview is rejected if reboot happens before adoption`() {
        val clock = clock()
        val preview = requireNotNull(clock.previewVerifiedServerObservation(2_000L))
        boot.value = requireNotNull(boot.value) + 1L
        elapsed.value = 0L

        assertNull(clock.adoptVerifiedServerObservation(preview))
        assertNull(clock.nowOrNull())
    }

    @Test
    fun `epoch projection saturates instead of overflowing`() {
        val clock = clock()
        clock.installVerifiedServerObservation(Long.MAX_VALUE - 10L)
        elapsed.value = 200L

        assertEquals(Long.MAX_VALUE, clock.nowOrNull()?.epochMillis)
    }

    @Test
    fun `invalid persisted version is ignored`() {
        store.anchor = TrustedTimeAnchor(
            version = TrustedGameClock.CURRENT_ANCHOR_VERSION + 1,
            serverEpochMs = 1_000L,
            observedElapsedMs = 100L,
            bootCount = 7L,
            confidence = TrustedTimeConfidence.VERIFIED_SERVER,
        )

        assertNull(clock().nowOrNull())
    }

    @Test
    fun `failed durable write does not install an in-memory anchor`() {
        store.acceptWrites = false
        val clock = clock()

        assertNull(clock.installVerifiedServerObservation(1_000L))
        assertNull(clock.nowOrNull())
        assertNull(store.read())
    }

    @Test
    fun `missing boot count keeps the clock unavailable`() {
        boot.value = null
        val clock = clock()

        assertNull(clock.installVerifiedServerObservation(1_000L))
        assertNull(clock.installProvisionalAnchor(1_000L))
        assertNull(clock.nowOrNull())
    }

    @Test
    fun `negative epoch observations are rejected`() {
        val clock = clock()

        assertNull(clock.previewVerifiedServerObservation(-1L))
        assertNull(clock.installProvisionalAnchor(-1L))
        assertFalse(clock.nowOrNull()?.isServerVerified == true)
    }

    private fun clock() = TrustedGameClock(
        store = store,
        elapsedRealtimeSource = ElapsedRealtimeSource { elapsed.value },
        bootCountSource = BootCountSource { boot.value },
    )

    private class MutableElapsedRealtime(var value: Long)

    private class MutableBootCount(var value: Long?)

    private class InMemoryAnchorStore(
        var anchor: TrustedTimeAnchor? = null,
        var acceptWrites: Boolean = true,
    ) : TrustedTimeAnchorStore {
        override fun read(): TrustedTimeAnchor? = anchor

        override fun write(anchor: TrustedTimeAnchor): Boolean {
            if (!acceptWrites) return false
            this.anchor = anchor
            return true
        }
    }
}
