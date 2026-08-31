package com.nullplaying.remote

import org.junit.Assert.*
import org.junit.Test

class SessionLogLifecycleTest {
    @Test
    fun `remote switch parses strict booleans and cannot bypass QA isolation`() {
        assertEquals(true, SessionLogRemotePolicy.parse(" true "))
        assertEquals(false, SessionLogRemotePolicy.parse("FALSE"))
        for (value in listOf("", "1", "0", "yes", "null", "invalid")) assertNull(SessionLogRemotePolicy.parse(value))
        assertFalse(SessionLogRemotePolicy.allowed(false, true))
        assertFalse(SessionLogRemotePolicy.allowed(false, false))
        assertFalse(SessionLogRemotePolicy.allowed(true, false))
        assertTrue(SessionLogRemotePolicy.allowed(true, true))
    }

    @Test
    fun `off generates no backlog and enabling while visible resumes the next transition`() {
        val lifecycle = SessionLogLifecycle()
        lifecycle.foregrounded()
        assertFalse(lifecycle.backgrounded(enabled = false) { error("OFF must not persist") })
        assertFalse(lifecycle.backgrounded { error("An old OFF transition must not be replayed") })
        lifecycle.foregrounded()
        assertTrue(lifecycle.backgrounded(enabled = true) { true })
        assertFalse(lifecycle.backgrounded { error("No duplicate") })
    }

    @Test
    fun `only duplicate event ids acknowledge a conflict and other failures retain the log`() {
        val duplicate = """{"code":"23505","message":"duplicate key violates unique constraint \"app_session_logs_pkey\""}"""
        assertTrue(isDuplicateSessionLogConflict(409, duplicate))
        assertFalse(isDuplicateSessionLogConflict(500, duplicate))
        assertFalse(isDuplicateSessionLogConflict(409, """{"code":"23503","message":"foreign key violation"}"""))
        assertFalse(isDuplicateSessionLogConflict(409, """{"code":"23505","message":"another unique constraint"}"""))
        assertFalse(isDuplicateSessionLogConflict(409, "invalid JSON"))
    }

    @Test
    fun `home and normal exit use supported reasons while rotation is ignored`() {
        assertEquals("background", sessionLogReasonForStop(false, false))
        assertEquals("exit", sessionLogReasonForStop(true, false))
        assertNull(sessionLogReasonForStop(false, true))
        assertNull(sessionLogReasonForStop(true, true))
    }

    @Test
    fun `short home visit is persisted immediately and stop plus destroy cannot double log`() {
        val lifecycle = SessionLogLifecycle()
        val persisted = mutableListOf<String>()
        lifecycle.foregrounded()
        assertTrue(lifecycle.backgrounded { persisted.add("background") })
        assertEquals(listOf("background"), persisted)
        assertFalse(lifecycle.backgrounded { persisted.add("exit") })
        lifecycle.foregrounded() // No five-minute delay needed.
        assertTrue(lifecycle.backgrounded { persisted.add("exit") })
        assertEquals(listOf("background", "exit"), persisted)
    }

    @Test
    fun `failed local persistence can retry instead of silently consuming the transition`() {
        val lifecycle = SessionLogLifecycle()
        lifecycle.foregrounded()
        assertFalse(lifecycle.backgrounded { false })
        assertTrue(lifecycle.backgrounded { true })
        assertFalse(lifecycle.backgrounded { error("duplicate must not write") })
    }

    @Test
    fun `legacy pending recovery has a stable event id across process restarts`() {
        val encoded = "{\"backgroundedAt\":1234,\"reason\":\"background\"}"
        assertEquals(legacyBackgroundSessionEventId(encoded), legacyBackgroundSessionEventId(encoded))
        assertNotEquals(legacyBackgroundSessionEventId(encoded), legacyBackgroundSessionEventId(encoded + " "))
        assertEquals(36, legacyBackgroundSessionEventId(encoded).length)
    }

    @Test
    fun `full screen ad is not an app exit but home while ad is showing is logged`() {
        val visibility = SessionActivityVisibility()
        val main = Any(); val ad = Any()
        assertTrue(visibility.started(main))
        assertFalse(visibility.started(ad))
        assertNull(visibility.stopped(main, false, false))
        assertEquals("background", visibility.stopped(ad, false, false))
        assertNull(visibility.stopped(ad, true, false))
    }

    @Test
    fun `returning from an ad and rotation do not manufacture background events`() {
        val visibility = SessionActivityVisibility()
        val main = Any(); val ad = Any(); val rotated = Any()
        visibility.started(main); visibility.started(ad)
        visibility.stopped(main, false, false)
        assertFalse(visibility.started(main))
        assertNull(visibility.stopped(ad, true, false))
        assertNull(visibility.stopped(main, false, true))
        assertTrue(visibility.started(rotated))
        assertEquals("exit", visibility.stopped(rotated, true, false))
        assertNull(visibility.stopped(rotated, true, false))
    }
}
