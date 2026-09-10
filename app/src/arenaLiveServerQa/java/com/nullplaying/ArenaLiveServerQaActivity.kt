package com.nullplaying

import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.system.Os
import android.system.OsConstants
import android.util.Log
import android.view.Gravity
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import com.nullplaying.data.SimpleAccountProgressEntity
import com.nullplaying.data.SimpleDatabase
import com.nullplaying.data.SimpleGameRepository
import com.nullplaying.data.SimpleStateBackupStore
import com.nullplaying.data.SimpleStateEntity
import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.localization.GameLanguageStore
import com.nullplaying.notifications.GameNotificationPreferencesStore
import com.nullplaying.remote.ARENA_LIVE_SERVER_QA_APPLICATION_ID
import com.nullplaying.remote.ArenaLiveServerQaCredentials
import com.nullplaying.remote.ArenaLiveServerQaHandoff
import com.nullplaying.remote.ArenaLiveServerQaValidatedHandoff
import com.nullplaying.remote.ArenaServerQaRateLimitException
import com.nullplaying.remote.ArenaServerQaRuntimeConfig
import com.nullplaying.remote.ArenaServerQaSharedPlayerTransport
import com.nullplaying.remote.SharedPlayerSnapshotClient
import com.nullplaying.remote.SupabaseAuthenticatedSharedPlayerApi
import com.nullplaying.remote.SupabaseGameService
import com.nullplaying.remote.buildPublicPlayerSnapshotUploads
import com.nullplaying.remote.createArenaLiveServerQaHero
import com.nullplaying.remote.isExactArenaLiveServerQaHero
import com.nullplaying.remote.isExactArenaLiveServerQaUpload
import com.nullplaying.ui.AlarmQuestApp
import com.nullplaying.ui.AlarmQuestTheme
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * One-shot live shared-player QA. Input comes from a 0600 app-private handoff installed with
 * `adb run-as`; the Activity deletes it before parsing or making any network request.
 */
class ArenaLiveServerQaActivity : ComponentActivity() {
    private var qaDatabase: SimpleDatabase? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val runtimeConfig = ArenaServerQaRuntimeConfig.fromBuildConfig()
        if (BuildConfig.APPLICATION_ID != ARENA_LIVE_SERVER_QA_APPLICATION_ID ||
            !runtimeConfig.enabled
        ) {
            showTerminalStatus("Live Arena QA authorization is missing")
            return
        }
        showTerminalStatus("Preparing isolated live matchmaking test…")
        lifecycleScope.launch {
            runCatching {
                val handoff = readAndConsumeHandoff()
                runLiveQa(runtimeConfig, handoff)
            }.onSuccess(::showNormalArena)
                .onFailure { error ->
                    Log.e(TAG, "Live Arena QA failed", error)
                    showTerminalStatus("Live Arena QA failed: ${error.javaClass.simpleName}")
                }
        }
    }

    private suspend fun runLiveQa(
        runtimeConfig: ArenaServerQaRuntimeConfig,
        handoff: ArenaLiveServerQaValidatedHandoff,
    ): SimpleGameRepository {
        val now = System.currentTimeMillis()
        val engine = SimpleGameEngine(
            enableAdventureEvents = true,
            enableAdventureRelationships = true,
            enableAdventureTraits = true,
        )
        val database = Room.inMemoryDatabaseBuilder(
            applicationContext,
            SimpleDatabase::class.java,
        ).build().also { qaDatabase = it }
        val state = createArenaLiveServerQaHero(engine, handoff.runTag, now)
        check(isExactArenaLiveServerQaHero(state))
        database.stateDao().save(SimpleStateEntity(
            id = 1,
            payload = Json { encodeDefaults = true }.encodeToString(state),
            updatedAt = now,
        ))
        database.accountProgressDao().save(SimpleAccountProgressEntity(
            activeCharacterSlotId = 1,
        ))
        val repository = SimpleGameRepository(
            database = database,
            engine = engine,
            backupStore = MemoryOnlySimpleStateBackupStore,
            publicPlayerRosterRetentionEnabled = true,
        )
        repository.initialize(now)
        repository.onAppForegrounded(now, SystemClock.elapsedRealtime())

        // Check the complete outgoing object before authentication. This repository contains one
        // in-memory synthetic hero, so no installed save can enter the transport.
        val preAuthSnapshot = repository.snapshots.value
        val preAuthState = requireNotNull(preAuthSnapshot.state)
        check(isExactArenaLiveServerQaHero(preAuthState))
        val preAuthUploads = requireNotNull(
            buildPublicPlayerSnapshotUploads(preAuthSnapshot, repository::displayCombatPower),
        )
        check(preAuthUploads.size == 1)
        check(isExactArenaLiveServerQaUpload(preAuthUploads.single(), handoff.runTag))

        var credentials: ArenaLiveServerQaCredentials? = handoff.credentials
        val transport = ArenaServerQaSharedPlayerTransport.create(
            context = applicationContext,
            runtimeConfig = runtimeConfig,
            repository = repository,
            liveCredentials = { credentials.also { credentials = null } },
        )
        val client = SharedPlayerSnapshotClient(
            repository = repository,
            api = SupabaseAuthenticatedSharedPlayerApi(transport),
            bootCount = {
                Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
            },
        )
        val roster = try {
            synchronize(client, preAuthSnapshot)
        } catch (rateLimit: ArenaServerQaRateLimitException) {
            delay(rateLimit.retryAfterSeconds * 1_000L)
            synchronize(client, repository.snapshots.value)
        } finally {
            credentials = null
        }
        check(roster != null)
        Log.i(TAG, "Live roster installed: serverCandidates=${roster.snapshots.size}")
        return repository
    }

    private suspend fun synchronize(
        client: SharedPlayerSnapshotClient,
        snapshot: com.nullplaying.data.GameSnapshot,
    ) = client.synchronize(
        snapshot = snapshot,
        forceRosterRefresh = true,
        nowEpochMillis = System.currentTimeMillis(),
    ).getOrThrow()

    private fun readAndConsumeHandoff(): ArenaLiveServerQaValidatedHandoff {
        val handoffFile = File(filesDir, HANDOFF_FILE_NAME)
        check(handoffFile.canonicalFile.parentFile == filesDir.canonicalFile)
        val descriptor = Os.open(
            handoffFile.absolutePath,
            OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW,
            0,
        )
        val bytes = try {
            val stat = Os.fstat(descriptor)
            check(stat.st_uid == Process.myUid())
            check(stat.st_mode and UNIX_PERMISSION_MASK == OWNER_READ_WRITE)
            check(stat.st_mode and OsConstants.S_IFMT == OsConstants.S_IFREG)
            check(stat.st_size in 1L..MAX_HANDOFF_BYTES.toLong())
            FileInputStream(descriptor).use { input ->
                val output = ByteArrayOutputStream(stat.st_size.toInt())
                val buffer = ByteArray(4 * 1_024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    check(output.size() + read <= MAX_HANDOFF_BYTES)
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        } finally {
            runCatching { Os.close(descriptor) }
            check(handoffFile.delete()) { "Could not consume the live QA handoff" }
        }
        return try {
            Json {
                ignoreUnknownKeys = false
                isLenient = false
            }.decodeFromString<ArenaLiveServerQaHandoff>(bytes.toString(Charsets.UTF_8))
                .validated()
        } finally {
            bytes.fill(0)
        }
    }

    private fun showNormalArena(repository: SimpleGameRepository) {
        val disabledBroadService = SupabaseGameService(applicationContext, repository)
        setContent {
            AlarmQuestTheme {
                AlarmQuestApp(
                    repository = repository,
                    notificationPreferencesStore = GameNotificationPreferencesStore(applicationContext),
                    gameLanguageStore = GameLanguageStore(applicationContext),
                    supabaseGameService = disabledBroadService,
                    gameNow = System::currentTimeMillis,
                    onRetryGameInitialization = {},
                    onBeginRewardedAdSession = { _, _ -> false },
                    onFinishRewardedAdSession = { _, _ -> false },
                    onEarnedOfflineAdventureReward = { _, _ -> },
                )
            }
        }
    }

    private fun showTerminalStatus(message: String) {
        setContentView(TextView(this).apply {
            text = message
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.rgb(18, 13, 25))
        })
    }

    override fun onDestroy() {
        qaDatabase?.close()
        qaDatabase = null
        super.onDestroy()
    }

    private companion object {
        const val TAG = "ArenaLiveServerQa"
        const val HANDOFF_FILE_NAME = "arena_live_qa_handoff.json"
        const val MAX_HANDOFF_BYTES = 32 * 1_024
        const val UNIX_PERMISSION_MASK = 0x1ff
        const val OWNER_READ_WRITE = 0x180
    }
}

private object MemoryOnlySimpleStateBackupStore : SimpleStateBackupStore {
    override suspend fun load(slotId: Int): SimpleStateEntity? = null
    override suspend fun save(entity: SimpleStateEntity) = Unit
    override suspend fun clear(slotId: Int) = Unit
    override suspend fun loadAccountProgress(): SimpleAccountProgressEntity? = null
    override suspend fun saveAccountProgress(entity: SimpleAccountProgressEntity) = Unit
}
