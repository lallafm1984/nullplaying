package com.nullplaying.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Compact encounter identity copied from the daily public roster. */
@Serializable
data class AdventureEncounterCandidate(
    val characterId: String,
    val displayName: String,
    val heroClass: HeroClass,
    val level: Long,
    val combatPower: Long,
    /** Legacy/local QA fallback only. Production battle data resolves from PublicPlayerRoster. */
    val stats: HeroStats? = null,
    val learnedSkills: List<AdventureRelationshipSkillSnapshot> = emptyList(),
    val equipment: List<AdventureRelationshipEquipmentSnapshot> = emptyList(),
    val adventureTraitIds: List<String> = emptyList(),
)

@Serializable
data class AdventureRelationshipSkillSnapshot(
    val catalogId: String = "",
    val displayName: String = "",
    val level: Long = 1L,
    val usageCount: Long = 0L,
)

@Serializable
data class AdventureRelationshipEquipmentSnapshot(
    val slot: EquipmentSlot = EquipmentSlot.WEAPON,
    val displayName: String = "",
    val power: Long = 0L,
    val rarity: String = "",
)

/**
 * Frozen local playback input. Remote skills are derived from class/level and equipment stays
 * empty; none of these compatibility fields expands the server wire contract.
 */
@Serializable
data class AdventureRelationshipBattleParticipantSnapshot(
    val characterId: String = "",
    val displayName: String = "",
    val heroClass: HeroClass = HeroClass.WARRIOR,
    val level: Long = 1L,
    val combatPower: Long = 0L,
    val stats: HeroStats? = null,
    val learnedSkills: List<AdventureRelationshipSkillSnapshot> = emptyList(),
    val equipment: List<AdventureRelationshipEquipmentSnapshot> = emptyList(),
    val adventureTraitIds: List<String> = emptyList(),
)

@Serializable
enum class AdventureRelationshipBattleKind {
    NONE,
    SPAR,
    RIVALRY,
    CONFLICT,
    COOPERATIVE_HUNT,
}

@Serializable
data class AdventureEncounterRoster(
    val snapshotId: String,
    val receivedAt: Long,
    val validUntil: Long,
    val candidates: List<AdventureEncounterCandidate> = emptyList(),
)

@Serializable
enum class AdventureRelationshipTier {
    VERY_CLOSE, CLOSE, KNOWN, BAD, VERY_BAD, HOSTILE;

    companion object {
        fun fromScore(score: Int): AdventureRelationshipTier = when {
            score >= 70 -> VERY_CLOSE
            score >= 30 -> CLOSE
            score >= -19 -> KNOWN
            score >= -49 -> BAD
            score >= -79 -> VERY_BAD
            else -> HOSTILE
        }
    }
}

/** The selected opponent, action, outcome and rewards are immutable before presentation starts. */
@Serializable
data class AdventureRelationshipRun(
    val sequence: Long,
    val sceneId: String,
    val approachId: String,
    val candidate: AdventureEncounterCandidate,
    val snapshotId: String,
    val startedAt: Long,
    val startedActiveMillis: Long,
    val durationMillis: Long,
    val heroLevel: Long,
    val primaryStat: AdventureEventStat,
    val secondaryStat: AdventureEventStat,
    val primaryValue: Long,
    val secondaryValue: Long,
    val successBasisPoints: Int,
    val partialBasisPoints: Int,
    val roll: Int,
    val outcome: AdventureEventOutcome,
    val scoreBefore: Int,
    val scoreDelta: Int,
    val experienceReward: Long,
    val rewardSeed: Long,
    val encounterLevel: Long,
    val labyrinthDepth: Long,
    val baseExperienceBudget: Long,
    val reunion: Boolean,
    /** Exactly one reward kind is settled for a non-battle meeting or a resolved relationship battle. */
    val rewardKind: AdventureEventRewardKind = AdventureEventRewardKind.EXPERIENCE,
    val goldReward: Long = 0L,
    val itemReward: AdventureEventItemReward = AdventureEventItemReward.NONE,
    val battleKind: AdventureRelationshipBattleKind = AdventureRelationshipBattleKind.NONE,
    val battleSeed: Long = 0L,
    /** Filled by the battle adapter after simulation; zero keeps legacy/non-battle timing unchanged. */
    val battleDurationMillis: Long = 0L,
    val localBattleSnapshot: AdventureRelationshipBattleParticipantSnapshot? = null,
    val opponentBattleSnapshot: AdventureRelationshipBattleParticipantSnapshot? = null,
    val battleOutcome: BattleOutcome? = null,
    /** Added to [scoreDelta] only after the immutable battle result is supplied. */
    val battleScoreDelta: Int = 0,
    val influentialTraitIds: List<String> = emptyList(),
    val traitBasisPointModifier: Int = 0,
)

@Serializable
data class AdventureRelationshipResult(
    val run: AdventureRelationshipRun,
    val occurredAt: Long,
    val experienceAwarded: Long,
    val scoreAfter: Int,
    val progressAdded: Long,
    val goldAwarded: Long = 0L,
    val itemName: String = "",
    val itemRarity: String = "",
    val itemEquipped: Boolean = false,
    val rewardKind: AdventureEventRewardKind = run.rewardKind,
) {
    val tier: AdventureRelationshipTier get() = AdventureRelationshipTier.fromScore(scoreAfter)

    fun toMemory(): AdventureRelationshipMemory = AdventureRelationshipMemory(
        run.sequence, run.sceneId, run.approachId, run.outcome, occurredAt,
        run.scoreBefore, scoreAfter - run.scoreBefore, scoreAfter,
    )
}

/** Long-lived memory keeps the actual score change without duplicating snapshots and decision rolls. */
@Serializable
data class AdventureRelationshipMemory(
    val sequence: Long,
    val sceneId: String,
    val approachId: String,
    val outcome: AdventureEventOutcome,
    val occurredAt: Long,
    val scoreBefore: Int,
    val scoreDelta: Int,
    val scoreAfter: Int,
)

/** Read the earlier isolated-preview full results; every subsequent write uses compact records. */
object AdventureRelationshipMemoriesSerializer : JsonTransformingSerializer<List<AdventureRelationshipMemory>>(
    ListSerializer(AdventureRelationshipMemory.serializer()),
) {
    override fun transformDeserialize(element: JsonElement): JsonElement = JsonArray(element.jsonArray.map { entry ->
        val result = entry.jsonObject
        val run = result["run"]?.jsonObject ?: return@map entry
        buildJsonObject {
            listOf("sequence", "sceneId", "approachId", "outcome", "scoreBefore").forEach { key ->
                put(key, requireNotNull(run[key]))
            }
            put("occurredAt", requireNotNull(result["occurredAt"]))
            put("scoreAfter", requireNotNull(result["scoreAfter"]))
            put("scoreDelta", requireNotNull(result["scoreAfter"]).jsonPrimitive.int -
                requireNotNull(run["scoreBefore"]).jsonPrimitive.int)
        }
    })
}

@Serializable
data class AdventureRelationshipContact(
    val characterId: String,
    val score: Int = 0,
    val meetings: Long = 0L,
    val firstMetAt: Long = 0L,
    val lastMetAt: Long = 0L,
    val lastMetActiveMillis: Long = 0L,
    val nextEligibleActiveMillis: Long = 0L,
    val latestSnapshot: AdventureEncounterCandidate,
    @Serializable(with = AdventureRelationshipMemoriesSerializer::class)
    val memories: List<AdventureRelationshipMemory> = emptyList(),
) {
    val tier: AdventureRelationshipTier get() = AdventureRelationshipTier.fromScore(score)
}

@Serializable
data class AdventureRelationshipState(
    var initialized: Boolean = false,
    var rngState: Long = 0L,
    var initializedAt: Long = 0L,
    var pausedMillis: Long = 0L,
    var nextEncounterAt: Long = 0L,
    var sequence: Long = 0L,
    var roster: AdventureEncounterRoster? = null,
    var contacts: List<AdventureRelationshipContact> = emptyList(),
    var pending: AdventureRelationshipRun? = null,
    var lastResult: AdventureRelationshipResult? = null,
    var recentResults: List<AdventureRelationshipResult> = emptyList(),
    var totalEncounters: Long = 0L,
    var totalExperience: Long = 0L,
    var totalGold: Long = 0L,
    var totalItems: Long = 0L,
    /** Avoids showing the same authored situation again in a short run. */
    var recentSceneIds: List<String> = emptyList(),
)
