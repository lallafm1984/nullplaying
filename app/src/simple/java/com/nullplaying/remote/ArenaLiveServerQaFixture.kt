package com.nullplaying.remote

import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.model.HeroClass
import com.nullplaying.model.PublicPlayerStats
import com.nullplaying.model.SimpleGameState
import com.nullplaying.model.SHARED_PLAYER_RULES_VERSION
import com.nullplaying.model.SHARED_PLAYER_SNAPSHOT_VERSION
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.jsonPrimitive

internal const val ARENA_LIVE_QA_HERO_ID = "a1100000-0000-4000-8000-000000000001"
internal const val ARENA_LIVE_QA_HERO_LEVEL = 10_000L
internal const val ARENA_LIVE_QA_COMBAT_POWER = 49_996L

@Serializable
internal data class ArenaLiveServerQaHandoff(
    val version: Int,
    @SerialName("project_ref") val projectRef: String,
    @SerialName("run_tag") val runTag: String,
    val requester: ArenaLiveServerQaRequester,
) {
    fun validated(): ArenaLiveServerQaValidatedHandoff {
        val tag = validateArenaLiveServerQaRunTag(runTag)
        require(version == 1)
        require(projectRef == ARENA_LIVE_SERVER_PROJECT_REF)
        require(requester.characterId == ARENA_LIVE_QA_HERO_ID)
        require(requester.slotId == 1)
        require(requester.displayName == "AQ-A-$tag")
        require(requester.heroClass == HeroClass.WARRIOR)
        require(requester.level == ARENA_LIVE_QA_HERO_LEVEL)
        require(requester.combatPower == ARENA_LIVE_QA_COMBAT_POWER)
        require(requester.rulesVersion == SHARED_PLAYER_RULES_VERSION)
        require(requester.snapshotVersion == SHARED_PLAYER_SNAPSHOT_VERSION)
        require(requester.stats == arenaLiveServerQaStats())
        require(requester.adventureTraitIds.isEmpty())
        require(requester.email == "aq-live-${tag.lowercase()}-a@example.invalid")
        val credentials = ArenaLiveServerQaCredentials(requester.email, requester.password)
        require(credentials.valid)
        return ArenaLiveServerQaValidatedHandoff(tag, credentials)
    }
}

@Serializable
internal data class ArenaLiveServerQaRequester(
    val email: String,
    val password: String,
    @SerialName("character_id") val characterId: String,
    @SerialName("slot_id")
    @Serializable(with = ArenaLiveServerQaSlotIdSerializer::class)
    val slotId: Int,
    @SerialName("display_name") val displayName: String,
    @SerialName("hero_class") val heroClass: HeroClass,
    val level: Long,
    @SerialName("combat_power") val combatPower: Long,
    @SerialName("rules_version") val rulesVersion: Int,
    @SerialName("snapshot_version") val snapshotVersion: Int,
    val stats: PublicPlayerStats,
    @SerialName("adventure_trait_ids") val adventureTraitIds: List<String>,
)

/** Kotlin serialization normally accepts a quoted JSON integer for an Int property. */
private object ArenaLiveServerQaSlotIdSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ArenaLiveServerQaSlotId", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int {
        val jsonDecoder = decoder as? JsonDecoder
            ?: error("Arena live QA handoff must use JSON")
        val primitive = jsonDecoder.decodeJsonElement().jsonPrimitive
        require(!primitive.isString) { "slot_id must be a JSON integer" }
        return requireNotNull(primitive.content.toIntOrNull()) {
            "slot_id must be a JSON integer"
        }
    }

    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
}

internal data class ArenaLiveServerQaValidatedHandoff(
    val runTag: String,
    val credentials: ArenaLiveServerQaCredentials,
)

/**
 * One reproducible, non-economic test projection. It is created before authentication in an
 * in-memory repository owned by the side-by-side QA package.
 */
internal fun createArenaLiveServerQaHero(
    engine: SimpleGameEngine,
    runTag: String,
    nowEpochMillis: Long,
): SimpleGameState {
    val normalizedRunTag = validateArenaLiveServerQaRunTag(runTag)
    val heroClass = HeroClass.WARRIOR
    val roll = engine.rollStats(ARENA_LIVE_QA_SEED, heroClass)
    val state = engine.newGame(
        name = "AQ-A-$normalizedRunTag",
        heroClass = heroClass,
        rolledStats = roll.stats,
        seed = roll.nextSeed,
        now = nowEpochMillis,
    )
    state.rankingCharacterId = ARENA_LIVE_QA_HERO_ID
    state.hero.name = "AQ-A-$normalizedRunTag"
    state.hero.level = ARENA_LIVE_QA_HERO_LEVEL
    state.hero.experience = 0L
    state.hero.gold = 0L
    state.classGuidedLevelGrowths = ARENA_LIVE_QA_HERO_LEVEL - 1L
    val baseStat = (ARENA_LIVE_QA_HERO_LEVEL * 2L + 100L) / 6L
    state.hero.stats.strength = baseStat
    state.hero.stats.constitution = baseStat
    state.hero.stats.dexterity = baseStat
    state.hero.stats.intelligence = baseStat
    state.hero.stats.wisdom = baseStat
    state.hero.stats.charisma = baseStat
    state.hero.stats.maxHealth = ARENA_LIVE_QA_HERO_LEVEL * 20L
    state.hero.stats.maxMana = ARENA_LIVE_QA_HERO_LEVEL * 10L
    val equipmentContribution = ARENA_LIVE_QA_COMBAT_POWER - engine.characterStatPower(state)
    check(equipmentContribution > 0L)
    state.equipment.forEach { it.power = equipmentContribution }
    check(engine.displayCombatPower(state) == ARENA_LIVE_QA_COMBAT_POWER)
    state.inventory.clear()
    state.adventureTraits.owned = emptyList()
    state.offlineAdventureMillis = 0L
    state.actionStartedAt = nowEpochMillis
    state.actionEndsAt = nowEpochMillis + 60_000L
    state.lastSettledAt = nowEpochMillis
    return state
}

internal fun isExactArenaLiveServerQaHero(state: SimpleGameState): Boolean =
    state.rankingCharacterId == ARENA_LIVE_QA_HERO_ID &&
        state.hero.name.matches(Regex("^AQ-A-[A-Z0-9]{8,14}$")) &&
        state.hero.heroClass == HeroClass.WARRIOR &&
        state.hero.level == ARENA_LIVE_QA_HERO_LEVEL &&
        state.hero.gold == 0L &&
        state.inventory.isEmpty() &&
        state.adventureTraits.owned.isEmpty()

internal fun isExactArenaLiveServerQaUpload(
    upload: PublicPlayerSnapshotUpload,
    runTag: String,
): Boolean {
    val tag = runCatching { validateArenaLiveServerQaRunTag(runTag) }.getOrNull()
        ?: return false
    return upload.characterId == ARENA_LIVE_QA_HERO_ID &&
        upload.displayName == "AQ-A-$tag" &&
        upload.heroClass == HeroClass.WARRIOR &&
        upload.level == ARENA_LIVE_QA_HERO_LEVEL &&
        upload.combatPower == ARENA_LIVE_QA_COMBAT_POWER &&
        upload.rulesVersion == SHARED_PLAYER_RULES_VERSION &&
        upload.snapshotVersion == SHARED_PLAYER_SNAPSHOT_VERSION &&
        upload.stats == arenaLiveServerQaStats() &&
        upload.adventureTraitIds.isEmpty()
}

internal fun arenaLiveServerQaStats(): PublicPlayerStats {
    val baseStat = (ARENA_LIVE_QA_HERO_LEVEL * 2L + 100L) / 6L
    return PublicPlayerStats(
        strength = baseStat,
        constitution = baseStat,
        dexterity = baseStat,
        intelligence = baseStat,
        wisdom = baseStat,
        charisma = baseStat,
        maxHealth = ARENA_LIVE_QA_HERO_LEVEL * 20L,
        maxMana = ARENA_LIVE_QA_HERO_LEVEL * 10L,
    )
}

internal fun validateArenaLiveServerQaRunTag(value: String): String =
    value.trim().uppercase().also {
        require(it.matches(Regex("^[A-Z0-9]{8,14}$")))
    }

private const val ARENA_LIVE_QA_SEED = 0x41535452415141L
