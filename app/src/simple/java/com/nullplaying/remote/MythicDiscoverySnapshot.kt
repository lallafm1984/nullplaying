package com.nullplaying.remote

import com.nullplaying.model.MythicDiscovery
import kotlinx.serialization.Serializable

@Serializable
internal data class MythicDiscoverySnapshot(
    val entries: List<MythicDiscovery> = emptyList(),
    val settledAt: Long = 0,
    val nextSettlementAt: Long = 0,
    val serverNow: Long = 0,
)
