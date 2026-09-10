package com.nullplaying.remote

import com.nullplaying.BuildConfig
import java.net.URI

internal const val ARENA_SERVER_QA_APPLICATION_ID = "com.nullplaying.arenaserverqa"
internal const val ARENA_LIVE_SERVER_QA_APPLICATION_ID = "com.nullplaying.arenaliveserverqa"
internal const val ARENA_LIVE_SERVER_PROJECT_REF = "rlrmaynzdwulbuvymxfa"

/**
 * The complete outbound surface reserved for the Arena real-server QA build. The additive unified
 * profile RPC replaces two duplicate publications when installed. Match start, action, result,
 * reward and settlement endpoints remain absent; combat consumes cached roster data and resolves
 * entirely in the local deterministic engine.
 */
internal enum class ArenaServerQaEndpoint(
    val path: String,
    val method: String,
) {
    ANONYMOUS_SIGN_UP("/auth/v1/signup", "POST"),
    ANONYMOUS_TOKEN_REFRESH("/auth/v1/token?grant_type=refresh_token", "POST"),
    QA_PASSWORD_SIGN_IN("/auth/v1/token?grant_type=password", "POST"),
    ANONYMOUS_USER("/auth/v1/user", "GET"),
    SYNC_PLAYER_NETWORK_PROFILE("/rest/v1/rpc/sync_player_network_profile", "POST"),
    SYNC_PUBLIC_PLAYER_SNAPSHOTS("/rest/v1/rpc/sync_public_player_snapshots", "POST"),
    GET_DAILY_PUBLIC_PLAYER_ROSTER("/rest/v1/rpc/get_daily_public_player_roster", "POST"),
}

internal fun isArenaServerQaEndpointAllowed(path: String, method: String): Boolean =
    ArenaServerQaEndpoint.entries.any { endpoint ->
        endpoint.path == path && endpoint.method == method
    }

internal data class ArenaServerQaRuntimeConfig(
    val applicationId: String,
    val debugBuild: Boolean,
    val remoteServicesEnabled: Boolean,
    val transportEnabled: Boolean,
    val adventureSystemEnabled: Boolean,
    val sharedPlayerSyncEnabled: Boolean,
    val arenaServerMatchingEnabled: Boolean,
    val standardSupabaseUrl: String,
    val standardSupabasePublishableKey: String,
    val qaSupabaseUrl: String,
    val qaSupabasePublishableKey: String,
    val liveServerAcknowledgement: String = "",
) {
    val enabled: Boolean
        get() = isArenaServerQaTransportEnabled(
            applicationId = applicationId,
            debugBuild = debugBuild,
            remoteServicesEnabled = remoteServicesEnabled,
            transportEnabled = transportEnabled,
            adventureSystemEnabled = adventureSystemEnabled,
            sharedPlayerSyncEnabled = sharedPlayerSyncEnabled,
            arenaServerMatchingEnabled = arenaServerMatchingEnabled,
            standardSupabaseUrl = standardSupabaseUrl,
            standardSupabasePublishableKey = standardSupabasePublishableKey,
            qaSupabaseUrl = qaSupabaseUrl,
            qaSupabasePublishableKey = qaSupabasePublishableKey,
            liveServerAcknowledgement = liveServerAcknowledgement,
        )

    companion object {
        fun fromBuildConfig(): ArenaServerQaRuntimeConfig = ArenaServerQaRuntimeConfig(
            applicationId = BuildConfig.APPLICATION_ID,
            debugBuild = BuildConfig.DEBUG,
            remoteServicesEnabled = BuildConfig.REMOTE_SERVICES_ENABLED,
            transportEnabled = BuildConfig.SHARED_PLAYER_QA_TRANSPORT_ENABLED,
            adventureSystemEnabled = BuildConfig.ADVENTURE_SYSTEM_ENABLED,
            sharedPlayerSyncEnabled = BuildConfig.SHARED_PLAYER_SYNC_ENABLED,
            arenaServerMatchingEnabled = BuildConfig.ARENA_SERVER_MATCHING_ENABLED,
            standardSupabaseUrl = BuildConfig.SUPABASE_URL,
            standardSupabasePublishableKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
            qaSupabaseUrl = BuildConfig.SUPABASE_QA_URL,
            qaSupabasePublishableKey = BuildConfig.SUPABASE_QA_PUBLISHABLE_KEY,
            liveServerAcknowledgement = BuildConfig.ARENA_LIVE_SERVER_QA_ACK,
        )
    }
}

internal data class ArenaServerQaDestination(
    val origin: String,
    val projectRef: String,
) {
    companion object {
        fun parse(url: String): ArenaServerQaDestination? {
            val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
            if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null ||
                uri.port != -1 || (!uri.path.isNullOrEmpty() && uri.path != "/") ||
                uri.query != null || uri.fragment != null
            ) return null
            val host = uri.host?.lowercase()?.takeIf { it.endsWith(".supabase.co") }
                ?: return null
            val projectRef = host.removeSuffix(".supabase.co")
            if (!projectRef.matches(Regex("^[a-z0-9]{20}$"))) return null
            return ArenaServerQaDestination(
                origin = "https://$host",
                projectRef = projectRef,
            )
        }
    }
}

/**
 * The broad production switch and normal Supabase fields must remain unavailable to this package.
 * The endpoint/key pair is accepted only from the QA-specific BuildConfig fields.
 */
internal fun isArenaServerQaTransportEnabled(
    applicationId: String,
    debugBuild: Boolean,
    remoteServicesEnabled: Boolean,
    transportEnabled: Boolean,
    adventureSystemEnabled: Boolean,
    sharedPlayerSyncEnabled: Boolean,
    arenaServerMatchingEnabled: Boolean,
    standardSupabaseUrl: String,
    standardSupabasePublishableKey: String,
    qaSupabaseUrl: String,
    qaSupabasePublishableKey: String,
    liveServerAcknowledgement: String = "",
): Boolean {
    val destination = ArenaServerQaDestination.parse(qaSupabaseUrl) ?: return false
    val destinationAllowed = when (applicationId) {
        ARENA_SERVER_QA_APPLICATION_ID ->
            liveServerAcknowledgement.isBlank() &&
                destination.projectRef != ARENA_LIVE_SERVER_PROJECT_REF
        ARENA_LIVE_SERVER_QA_APPLICATION_ID ->
            liveServerAcknowledgement == ARENA_LIVE_SERVER_PROJECT_REF &&
                destination.projectRef == ARENA_LIVE_SERVER_PROJECT_REF
        else -> false
    }
    return destinationAllowed &&
        debugBuild &&
        !remoteServicesEnabled &&
        transportEnabled &&
        adventureSystemEnabled &&
        sharedPlayerSyncEnabled &&
        arenaServerMatchingEnabled &&
        standardSupabaseUrl.isBlank() &&
        standardSupabasePublishableKey.isBlank() &&
        qaSupabasePublishableKey.isNotBlank() &&
        !qaSupabasePublishableKey.startsWith("sb_secret_", ignoreCase = true) &&
        !qaSupabasePublishableKey.contains("service_role", ignoreCase = true)
}
