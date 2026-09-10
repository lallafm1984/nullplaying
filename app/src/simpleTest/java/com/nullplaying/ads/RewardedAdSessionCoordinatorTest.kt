package com.nullplaying.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RewardedAdSessionCoordinatorTest {
    private val coordinator = RewardedAdSessionCoordinator(
        resumeRecoveryGraceMillis = 3_000L,
        foregroundWatchdogMillis = 10_000L,
    )

    @Test
    fun `matching terminal callback releases exactly its own show session`() {
        assertTrue(coordinator.begin("show-1", 1_000L))
        assertFalse(coordinator.terminal("stale"))
        assertEquals("show-1", coordinator.activeToken())
        assertTrue(coordinator.terminal("show-1"))
        assertNull(coordinator.activeToken())
        assertFalse(coordinator.terminal("show-1"))
    }

    @Test
    fun `host pause never releases a real full screen ad before resume grace`() {
        assertTrue(coordinator.begin("show-1", 1_000L))
        coordinator.hostPaused(1_100L)

        assertFalse(coordinator.recoverIfOrphaned("show-1", 20_000L, hostResumed = false))
        assertFalse(coordinator.recoverIfOrphaned("show-1", 4_099L, hostResumed = true))
        assertEquals("show-1", coordinator.activeToken())
        assertTrue(coordinator.recoverIfOrphaned("show-1", 4_100L, hostResumed = true))
        assertNull(coordinator.activeToken())
    }

    @Test
    fun `missing lifecycle and SDK callbacks recover only at long foreground watchdog`() {
        assertTrue(coordinator.begin("show-1", 1_000L))

        assertFalse(coordinator.recoverIfOrphaned("show-1", 10_999L, hostResumed = true))
        assertTrue(coordinator.recoverIfOrphaned("show-1", 11_000L, hostResumed = true))
        assertNull(coordinator.activeToken())
    }

    @Test
    fun `late callback from recovered show cannot clear a newer session`() {
        assertTrue(coordinator.begin("show-1", 1_000L))
        coordinator.hostPaused(1_100L)
        assertTrue(coordinator.recoverIfOrphaned("show-1", 4_100L, hostResumed = true))
        assertTrue(coordinator.begin("show-2", 4_101L))

        assertFalse(coordinator.terminal("show-1"))
        assertEquals("show-2", coordinator.activeToken())
    }
}
