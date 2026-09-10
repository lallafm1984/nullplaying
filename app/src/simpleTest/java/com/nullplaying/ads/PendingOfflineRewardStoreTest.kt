package com.nullplaying.ads

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PendingOfflineRewardStoreTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Before
    fun clearPreferences() {
        context.getSharedPreferences("pending_offline_reward_grants_v1", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `earned request survives store recreation until durable game grant completes`() {
        assertTrue(PendingOfflineRewardStore(context).enqueue("character-1", "request-1"))

        assertEquals(
            listOf(PendingOfflineReward("character-1", "request-1")),
            PendingOfflineRewardStore(context).pending(),
        )

        assertTrue(PendingOfflineRewardStore(context).remove("request-1"))
        assertTrue(PendingOfflineRewardStore(context).pending().isEmpty())
    }

    @Test
    fun `same request cannot be redirected to another character`() {
        val store = PendingOfflineRewardStore(context)
        assertTrue(store.enqueue("character-1", "request-1"))
        assertTrue(store.enqueue("character-1", "request-1"))
        assertFalse(store.enqueue("character-2", "request-1"))
        assertEquals(listOf(PendingOfflineReward("character-1", "request-1")), store.pending())
    }
}
