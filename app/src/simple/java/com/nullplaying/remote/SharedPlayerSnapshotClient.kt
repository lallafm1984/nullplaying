package com.nullplaying.remote

import com.nullplaying.data.GameSnapshot
import com.nullplaying.data.SimpleGameRepository
import com.nullplaying.model.PublicPlayerRoster
import com.nullplaying.model.SHARED_PLAYER_MIN_LEVEL
import com.nullplaying.model.SHARED_PLAYER_RULES_VERSION
import kotlinx.coroutines.CancellationException

/**
 * Authenticated transport boundary. Production wiring is deliberately absent from the isolated
 * staging build; a future implementation must reuse the validated Supabase Auth session and only
 * call the two RPCs defined by the reviewed migration.
 */
internal interface AuthenticatedSharedPlayerApi {
    suspend fun captureSessionIdentity(): String

    fun isSessionIdentityCurrent(identity: String): Boolean

    /** Local-only receipt check. Implementations must not authenticate or open a network call. */
    fun isPlayerNetworkProfileCurrent(
        snapshots: List<PublicPlayerSnapshotUpload>,
        nowEpochMillis: Long,
    ): Boolean = false

    /** Local-only decision. An initial empty profile must not create a session or request. */
    fun shouldPublishPlayerNetworkProfile(
        snapshots: List<PublicPlayerSnapshotUpload>,
        nowEpochMillis: Long,
    ): Boolean = snapshots.isNotEmpty() &&
        !isPlayerNetworkProfileCurrent(snapshots, nowEpochMillis)

    suspend fun syncPublicPlayerSnapshots(
        snapshots: List<PublicPlayerSnapshotUpload>,
    ): SharedPlayerPublicationMode

    suspend fun getDailyPublicPlayerRoster(
        characterId: String,
        rulesVersion: Int,
    ): DailyPublicPlayerRosterResponse
}

internal enum class SharedPlayerPublicationMode { UNIFIED, LEGACY }

internal object DisabledSharedPlayerApi : AuthenticatedSharedPlayerApi {
    override suspend fun captureSessionIdentity(): String =
        error("Shared-player remote transport is disabled")

    override fun isSessionIdentityCurrent(identity: String): Boolean = false

    override suspend fun syncPublicPlayerSnapshots(
        snapshots: List<PublicPlayerSnapshotUpload>,
    ): SharedPlayerPublicationMode {
        error("Shared-player remote transport is not connected in preintegration staging")
    }

    override suspend fun getDailyPublicPlayerRoster(
        characterId: String,
        rulesVersion: Int,
    ): DailyPublicPlayerRosterResponse =
        error("Shared-player remote transport is not connected in preintegration staging")
}

/**
 * Lifecycle-ready orchestration with a fakeable transport. Repeated calls reuse the persisted
 * server-day roster; the server contract independently prevents daily rerolls.
 */
internal class SharedPlayerSnapshotClient(
    private val repository: SimpleGameRepository,
    private val api: AuthenticatedSharedPlayerApi = DisabledSharedPlayerApi,
    private val monotonicNowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val bootCount: () -> Int = { 0 },
) {
    suspend fun synchronize(
        snapshot: GameSnapshot,
        forceRosterRefresh: Boolean = false,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): Result<PublicPlayerRoster?> = runCatching {
        val receivedAtMonotonicMillis = monotonicNowMillis()
        val receivedAtBootCount = bootCount()
        // Expired raw opponent input must not survive a failed auth or network refresh.
        repository.clearExpiredPublicPlayerRosters(nowEpochMillis)
        val currentSnapshot = repository.snapshots.value.takeIf { it.ready } ?: snapshot
        val active = currentSnapshot.state
        val localUploads = buildPublicPlayerSnapshotUploads(
            currentSnapshot,
            repository::displayCombatPower,
        ) ?: error("Local public-player snapshot failed validation")
        val activeCanFetchRoster = active != null &&
            active.hero.level >= SHARED_PLAYER_MIN_LEVEL &&
            active.rankingCharacterId.isNotBlank()
        val needsPublication = api.shouldPublishPlayerNetworkProfile(
            localUploads,
            nowEpochMillis,
        )
        val cached = active?.publicPlayerRoster
        val reusableCached = cached?.takeIf {
            activeCanFetchRoster && !forceRosterRefresh && it.isTrustedReusableFor(
                active.rankingCharacterId,
                active.hero.level,
                nowEpochMillis,
                receivedAtMonotonicMillis,
                receivedAtBootCount,
            )
        }
        // A server-day roster is immutable and contains public opponent data only. Foreground
        // session validation owns account-replacement cleanup before this client is called, so a
        // valid cache plus an unchanged unified payload needs no Auth, publication, roster, or
        // battle request. A changed local payload is published before this cache is reused.
        if (reusableCached != null && !needsPublication) return@runCatching reusableCached
        // Publication is account-wide, while roster retrieval belongs only to the active eligible
        // character. With no publishable change and no eligible active character, stay fully local.
        if (!needsPublication && !activeCanFetchRoster) return@runCatching null

        // Capturing an identity may replace a deleted/revoked session and purge its old roster.
        // Re-read the repository after that barrier so a stale pre-auth object cannot be uploaded.
        val sessionIdentity = api.captureSessionIdentity()
        val authenticatedSnapshot = repository.snapshots.value.takeIf { it.ready } ?: currentSnapshot
        val authenticatedActive = authenticatedSnapshot.state
        val uploads = buildPublicPlayerSnapshotUploads(authenticatedSnapshot, repository::displayCombatPower)
            ?: error("Local public-player snapshot failed validation")
        if (api.shouldPublishPlayerNetworkProfile(uploads, nowEpochMillis)) {
            try {
                api.syncPublicPlayerSnapshots(uploads)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Publication and roster retrieval are independent. A cached/fetched server roster
                // is still safe opponent input while the local profile waits for its bounded retry.
            }
        }
        check(api.isSessionIdentityCurrent(sessionIdentity)) {
            "Shared-player account changed during publication"
        }

        val authenticatedCanFetchRoster = authenticatedActive != null &&
            authenticatedActive.hero.level >= SHARED_PLAYER_MIN_LEVEL &&
            authenticatedActive.rankingCharacterId.isNotBlank()
        if (!authenticatedCanFetchRoster) return@runCatching null

        // Opponent input is still immutable for the server day. Publishing a changed local
        // profile must not reroll it, so reuse the post-auth cache after the publication attempt.
        val authenticatedCached = authenticatedActive.publicPlayerRoster?.takeIf {
            !forceRosterRefresh && it.isTrustedReusableFor(
                authenticatedActive.rankingCharacterId,
                authenticatedActive.hero.level,
                nowEpochMillis,
                receivedAtMonotonicMillis,
                receivedAtBootCount,
            )
        }
        if (authenticatedCached != null) return@runCatching authenticatedCached

        // Publication and roster retrieval are deliberately independent. A client/server throttle
        // or transient write failure must not hide an already-published daily roster.
        val response = api.getDailyPublicPlayerRoster(
            characterId = authenticatedActive.rankingCharacterId,
            rulesVersion = SHARED_PLAYER_RULES_VERSION,
        )
        val roster = response.toPublicPlayerRoster(
            requesterCharacterId = authenticatedActive.rankingCharacterId,
            requesterLevel = authenticatedActive.hero.level,
            receivedAtEpochMillis = nowEpochMillis,
            receivedAtMonotonicMillis = receivedAtMonotonicMillis,
            receivedAtBootCount = receivedAtBootCount,
        ) ?: error("Server public-player roster failed validation")
        check(api.isSessionIdentityCurrent(sessionIdentity)) {
            "Shared-player account changed while receiving the daily roster"
        }
        check(repository.installPublicPlayerRoster(
            roster = roster,
            now = nowEpochMillis,
            isSourceCurrent = { api.isSessionIdentityCurrent(sessionIdentity) },
        )) {
            "Public-player roster no longer matches the current character"
        }
        roster
    }
}
