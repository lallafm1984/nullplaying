package com.nullplaying.time

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SharedPreferencesTrustedTimeAnchorStoreTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Before
    fun clearPreferences() {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `all versioned anchor fields survive store recreation`() {
        val anchor = TrustedTimeAnchor(
            version = TrustedGameClock.CURRENT_ANCHOR_VERSION,
            serverEpochMs = 1_725_000_000_000L,
            observedElapsedMs = 123_456L,
            bootCount = 9L,
            confidence = TrustedTimeConfidence.VERIFIED_SERVER,
        )

        assertEquals(true, store().write(anchor))

        assertEquals(anchor, store().read())
    }

    @Test
    fun `unsupported stored version is unavailable`() {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt("anchor_version", TrustedGameClock.CURRENT_ANCHOR_VERSION + 1)
            .putLong("server_epoch_ms", 1_000L)
            .putLong("observed_elapsed_ms", 100L)
            .putLong("boot_count", 1L)
            .putString("source", TrustedTimeConfidence.VERIFIED_SERVER.name)
            .commit()

        assertNull(store().read())
    }

    private fun store() = SharedPreferencesTrustedTimeAnchorStore(
        context = context,
        preferencesName = PREFERENCES_NAME,
    )

    private companion object {
        const val PREFERENCES_NAME = "trusted_game_clock_test"
    }
}
