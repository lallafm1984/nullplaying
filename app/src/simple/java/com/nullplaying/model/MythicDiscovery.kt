package com.nullplaying.model

import kotlinx.serialization.Serializable
import java.util.UUID

/** Acquisition history survives equipping, selling and offline catch-up; old saves start empty. */
@Serializable
data class MythicDiscovery(
    val eventId: String,
    val characterId: String,
    val displayName: String,
    val heroClass: HeroClass,
    val level: Long,
    val itemName: String,
    val slot: EquipmentSlot,
    val power: Long,
    val discoveredAt: Long,
)

internal fun recordMythicDiscovery(
    state: SimpleGameState, itemId: Long, name: String, rarity: String,
    slot: EquipmentSlot, power: Long, at: Long,
) {
    if (rarity != "신화" || state.rankingCharacterId.isBlank()) return
    val eventId = UUID.nameUUIDFromBytes("mythic:${state.rankingCharacterId}:$itemId".toByteArray(Charsets.UTF_8)).toString()
    if (state.mythicDiscoveries.any { it.eventId == eventId }) return
    state.mythicDiscoveries = (state.mythicDiscoveries + MythicDiscovery(
        eventId, state.rankingCharacterId, state.hero.name, state.hero.heroClass,
        state.hero.level, name, slot, power, at,
    )).takeLast(100)
}
