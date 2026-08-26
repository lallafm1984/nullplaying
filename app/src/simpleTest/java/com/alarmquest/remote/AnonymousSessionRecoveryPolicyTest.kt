package com.alarmquest.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnonymousSessionRecoveryPolicyTest {
    @Test
    fun `deleted user and revoked session responses replace the anonymous session`() {
        assertTrue(
            shouldReplaceAnonymousSession(
                statusCode = 403,
                responseBody = """{"error_code":"user_not_found","msg":"User from sub claim in JWT does not exist"}""",
            ),
        )
        assertTrue(
            shouldReplaceAnonymousSession(
                statusCode = 403,
                responseBody = """{"error_code":"session_not_found"}""",
            ),
        )
        assertTrue(
            shouldReplaceAnonymousSession(
                statusCode = 400,
                responseBody = """{"error_code":"refresh_token_not_found"}""",
            ),
        )
        assertTrue(shouldReplaceAnonymousSession(401, "invalid token"))
        assertTrue(shouldReplaceAnonymousSession(403, "{\"error_code\":\"bad_jwt\"}"))
    }

    @Test
    fun `permission and transient failures do not create a new anonymous identity`() {
        assertFalse(shouldReplaceAnonymousSession(403, "permission denied for table ranking_entries"))
        assertFalse(shouldReplaceAnonymousSession(422, "Anonymous sign-ins are disabled"))
        assertFalse(shouldReplaceAnonymousSession(429, "rate limit exceeded"))
        assertFalse(shouldReplaceAnonymousSession(500, "internal server error"))
    }
}
