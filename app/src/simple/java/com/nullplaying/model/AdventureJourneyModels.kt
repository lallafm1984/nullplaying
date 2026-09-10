package com.nullplaying.model

import kotlinx.serialization.Serializable

@Serializable
enum class AdventureEventStat { STR, CON, DEX, INT, WIS, CHA }

@Serializable
enum class AdventureEventOutcome { SUCCESS, PARTIAL, FAILURE }

@Serializable
enum class AdventureEventItemReward { NONE, TROPHY, EQUIPMENT }

@Serializable
enum class AdventureEventRewardKind { UNSPECIFIED, EXPERIENCE, GOLD, ITEM, ROUTE }

@Serializable
enum class AdventureEventContext {
    OUTBOUND_ROUTE,
    FIELD_EXPLORATION,
    PRE_COMBAT,
    POST_COMBAT,
    RETURN_ROUTE,
    TOWN_RETURN,
}

@Serializable
enum class AdventureEventContinuation {
    NEXT_ADVENTURE_STEP,
    BEGIN_COMBAT,
    BEGIN_RETURNING,
    BEGIN_TOWN_SHOPPING,
}

@Serializable
enum class AdventureEventProgressPolicy { REPLACE_ORDINARY_SLOT, NO_PROGRESS }

@Serializable
enum class AdventureEventBattleRewardKind { NONE, GOLD, EQUIPMENT }

/** Immutable evidence chosen before the visible action starts. Rewards settle only on completion. */
@Serializable
data class AdventureEventRun(
    val sequence: Long,
    val eventId: String,
    val approachId: String,
    val startedAt: Long,
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
    val experienceReward: Long,
    val goldReward: Long,
    val itemReward: AdventureEventItemReward,
    val routeDelayMillis: Long,
    val rewardSeed: Long,
    val encounterLevel: Long = heroLevel,
    val labyrinthDepth: Long = 0L,
    val baseExperienceBudget: Long = 0L,
    val rewardKind: AdventureEventRewardKind = AdventureEventRewardKind.UNSPECIFIED,
    val routeRewardUses: Int = 0,
    val context: AdventureEventContext = AdventureEventContext.FIELD_EXPLORATION,
    val continuation: AdventureEventContinuation = AdventureEventContinuation.NEXT_ADVENTURE_STEP,
    val progressPolicy: AdventureEventProgressPolicy = AdventureEventProgressPolicy.REPLACE_ORDINARY_SLOT,
    val battleGrade: MonsterGrade? = null,
    val battleRewardKind: AdventureEventBattleRewardKind = AdventureEventBattleRewardKind.NONE,
)

@Serializable
data class AdventureEventResult(
    val run: AdventureEventRun,
    val occurredAt: Long,
    val experienceAwarded: Long,
    val goldAwarded: Long,
    val itemName: String = "",
    val itemRarity: String = "",
    val itemEquipped: Boolean = false,
    val progressAdded: Long = 0L,
    val itemFoundAtLevel: Long = 0L,
    val additionalItemNames: List<String> = emptyList(),
    val itemOmittedByTrait: Boolean = false,
    val actualItemCount: Int = if (itemName.isBlank()) 0 else 1,
    /** Explicitly distinguishes a settled event battle from a zero-valued pending receipt. */
    val battleResolved: Boolean = false,
)

@Serializable
data class AdventureEventBattleState(
    val eventId: String,
    val grade: MonsterGrade,
    val rewardKind: AdventureEventBattleRewardKind,
    val rewardSeed: Long,
    val continuation: AdventureEventContinuation,
    val context: AdventureEventContext,
    var result: AdventureEventResult? = null,
)

/** Per character; no wall-clock RNG, network dependency, arena state or trait state. */
@Serializable
data class AdventureJourneyState(
    var initialized: Boolean = false,
    var rngState: Long = 0L,
    var nextEventAt: Long = 0L,
    var sequence: Long = 0L,
    var completedEvents: Long = 0L,
    var totalExperience: Long = 0L,
    var totalGold: Long = 0L,
    var totalItems: Long = 0L,
    var pending: AdventureEventRun? = null,
    var lastResult: AdventureEventResult? = null,
    var recentResults: List<AdventureEventResult> = emptyList(),
    var nextEncounterDelayAdjustmentMillis: Long = 0L,
    var routeRewardDelayQueueMillis: List<Long> = emptyList(),
    var recentEventIds: List<String> = emptyList(),
    var recentStoryFamilies: List<String> = emptyList(),
    var nextEventContext: AdventureEventContext = AdventureEventContext.FIELD_EXPLORATION,
    var eventBattle: AdventureEventBattleState? = null,
    /** Offline-QA only: consumed at the next ordinary encounter boundary. */
    var qaQueuedEventId: String = "",
    /** Offline-QA only: independent from naturally selected recent events. */
    var qaSequenceCursorEventId: String = "",
)
