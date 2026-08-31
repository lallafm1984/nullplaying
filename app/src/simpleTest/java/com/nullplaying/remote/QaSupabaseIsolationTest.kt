package com.nullplaying.remote

import androidx.room.Room
import com.nullplaying.BuildConfig
import com.nullplaying.data.FileSimpleStateBackupStore
import com.nullplaying.data.SimpleDatabase
import com.nullplaying.data.SimpleGameRepository
import com.nullplaying.engine.SimpleGameEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class QaSupabaseIsolationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `build type controls production endpoint and QA never creates a remote session`() = runBlocking {
        // A unit test must not initialize the production Application or authenticate a release build.
        assertEquals(android.app.Application::class.java, RuntimeEnvironment.getApplication().javaClass)
        if (!BuildConfig.DEBUG) {
            assertTrue(BuildConfig.SUPABASE_URL.startsWith("https://"))
            assertFalse(BuildConfig.SUPABASE_PUBLISHABLE_KEY.isBlank())
            assertFalse(BuildConfig.SUPABASE_PUBLISHABLE_KEY.startsWith("sb_secret_"))
            return@runBlocking
        }
        assertEquals("", BuildConfig.SUPABASE_URL)
        assertEquals("", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
        val context = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(context, SimpleDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val repository = SimpleGameRepository(
                database, SimpleGameEngine(), FileSimpleStateBackupStore(temporaryFolder.newFolder()),
            )
            val service = SupabaseGameService(context, repository)
            service.initialize()
            service.validateAnonymousSessionForForeground()
            service.recordAppForegrounded()
            service.recordAppBackgrounded(repository.snapshots.value)
            service.flushPendingSessionLogs()
            assertTrue(service.connectionState.value is SupabaseConnectionState.Disabled)
            assertNull(service.checkForAppUpdate().getOrThrow())
            val preferences = context.getSharedPreferences("supabase_game", 0)
            assertFalse(preferences.contains("auth_session"))
            assertFalse(preferences.contains("pending_background_session"))
            assertFalse(preferences.contains("pending_session_logs"))
        } finally {
            database.close()
        }
    }
}
