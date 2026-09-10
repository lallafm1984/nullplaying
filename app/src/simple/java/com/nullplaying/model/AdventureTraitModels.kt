package com.nullplaying.model

import kotlinx.serialization.Serializable

@Serializable enum class AdventureTraitChangeKind { ACQUIRED, WEAKENED, RECOVERED, LOST, REPLACED }
@Serializable enum class AdventureTraitEffectKind { DAMAGE, EXTRA_ITEM, OMITTED_ITEM, BAG_CAPACITY, APPRAISAL, SALE, SHOP_REVIEW, RETRY, EXPERIENCE, RELATIONSHIP, DIALOGUE }

@Serializable data class AdventureTraitChange(
    val sequence: Long, val traitId: String, val kind: AdventureTraitChangeKind,
    val sourceKey: String, val occurredAt: Long, val reasonKey: String,
    val replacedTraitId: String = "",
)

@Serializable data class AdventureOwnedTrait(
    val traitId: String, val acquiredAt: Long = 0L, val acquisitionSequence: Long = 0L,
    val shaky: Boolean = false, val lastChange: AdventureTraitChange? = null,
)

@Serializable data class AdventureTraitActivation(
    val sequence: Long, val traitId: String, val sourceKey: String,
    val sourceActionSequence: Long, val occurredAt: Long, val effectKind: AdventureTraitEffectKind,
    val previousValue: Long = 0L, val currentValue: Long = 0L,
    val timeAdjustmentMillis: Long = 0L, val subjectName: String = "",
)

@Serializable data class AdventureTraitEvidence(
    val sourceKey: String, val contextKey: String, val positive: Boolean, val reasonKey: String,
)

@Serializable data class AdventureTraitDecision(val key: String, val roll: Int, val threshold: Int, val passed: Boolean)

@Serializable data class AdventureTraitEvidenceUpdate(
    val sourceKey: String, val contextKey: String, val positive: Set<String>,
    val negative: Set<String>, val occurredAt: Long, val reasonKey: String,
)

@Serializable data class AdventureTraitSource(
    val key: String, val kind: String, val contextKey: String, val startedAt: Long,
    val ownedIds: List<String> = emptyList(),
    var rewardSequence: Long = 0L,
    var derivedRewardSequence: Long = 0L,
    var finalRewardOrigin: String = "PRIMARY",
    var combatTraitId: String = "", var firstBasicPending: Boolean = true,
    var rawBasicRolls: List<Int> = emptyList(), var finishingRawPercent: Int = 0,
    var combatAltered: Boolean = false,
    var resultTimePercent: Int = 100,
    var baseEvent: AdventureEventRun? = null,
    var retryRun: AdventureEventRun? = null,
    var dialogueOutcome: AdventureEventOutcome? = null,
    var baseRelationship: AdventureRelationshipRun? = null,
    var relationshipDelta: Int? = null,
    var additionalItemNames: List<String> = emptyList(), var itemOmitted: Boolean = false,
)

@Serializable data class AdventureTraitSaleBatch(
    val sourceKey: String, val itemIds: List<Long>, val baseValues: List<Long>,
    val paidValues: List<Long>, val durationPercent: Int, val traitId: String,
    var nextIndex: Int = 0,
)

@Serializable data class AdventureTraitShopVisit(
    val sourceKey: String, val extraAllowed: Boolean, val purchasesBefore: Long,
    var extraUsed: Boolean = false, var pendingIsExtra: Boolean = false,
    var justReviewedExtra: Boolean = false,
    var basePurchases: Long = 0L,
    val extraTraitId: String = "",
    val extraSlot: EquipmentSlot? = null,
)

@Serializable data class AdventureTraitWeaponUse(
    val sourceKey: String, val slot: EquipmentSlot, val power: Long,
    var remainingCombats: Int = 3, var firstContext: String = "",
)

@Serializable data class AdventureTraitRewardTrace(
    val sequence: Long, val sourceKey: String, val origin: String, val itemId: Long,
    val name: String, val slot: EquipmentSlot?, val rarity: String,
    val originalPower: Long?, val finalPower: Long?, val actualGranted: Boolean,
    val equipped: Boolean, val foundAtLevel: Long = 1L,
)

/** Separate from arena BattleTraitState. Decisions and evidence belong to original source records. */
@Serializable data class AdventureTraitState(
    var initialized: Boolean = false, var seed: Long = 0L, var sourceSequence: Long = 0L,
    var changeSequence: Long = 0L, var activationSequence: Long = 0L,
    var owned: List<AdventureOwnedTrait> = emptyList(),
    var evidence: Map<String, List<AdventureTraitEvidence>> = emptyMap(),
    /**
     * Active-adventure clock anchors. They move forward with an uncovered offline pause, so
     * calendar time without adventure charge cannot form, weaken, recover, or remove a trait.
     * Empty defaults keep saves made before the lifecycle pacing rules readable.
     */
    var formationStartedAtByTrait: Map<String, Long> = emptyMap(),
    /** Last successful formation on the active-adventure clock; null keeps legacy saves compatible. */
    var lastFormationAt: Long? = null,
    var stableStartedAtByTrait: Map<String, Long> = emptyMap(),
    var oppositionStartedAtByTrait: Map<String, Long> = emptyMap(),
    var weakenedStartedAtByTrait: Map<String, Long> = emptyMap(),
    var observedSourceKeys: List<String> = emptyList(),
    var pendingEvidence: List<AdventureTraitEvidenceUpdate> = emptyList(),
    var decisions: List<AdventureTraitDecision> = emptyList(),
    var opportunityCounts: Map<String, Long> = emptyMap(),
    var procCounts: Map<String, Long> = emptyMap(),
    var actualEffectCounts: Map<String, Long> = emptyMap(),
    var recentChanges: List<AdventureTraitChange> = emptyList(),
    var visibleActivations: List<AdventureTraitActivation> = emptyList(),
    var recentActivations: List<AdventureTraitActivation> = emptyList(),
    var source: AdventureTraitSource? = null,
    var saleBatch: AdventureTraitSaleBatch? = null,
    var shopVisit: AdventureTraitShopVisit? = null,
    var temporaryBagSlots: Long = 0L,
    var recentExperienceFamilies: List<String> = emptyList(),
    var weaponUses: List<AdventureTraitWeaponUse> = emptyList(),
    var stableEquipmentCombats: Int = 0,
    var lastBaseEquipmentPowers: List<Long> = emptyList(),
    var carriedEvidenceContexts: List<String> = emptyList(),
    var prerequisites: Set<String> = emptySet(),
    var primaryRewardOrigins: Map<Long, String> = emptyMap(),
    var equipmentOrigins: Map<EquipmentSlot, String> = emptyMap(),
    var primaryRewardFamilies: Map<Long, String> = emptyMap(),
    var carriedOriginalFamilies: List<String> = emptyList(),
    var soldOriginalFamilies: List<String> = emptyList(),
    var recentRewardTraces: List<AdventureTraitRewardTrace> = emptyList(),
    var rewardTraceSequence: Long = 0L,
    var reportedChangeSequence: Long = 0L, var reportedActivationSequence: Long = 0L,
    /** Small upgrades deliberately kept in the bag by L06 must remain there until sale. */
    var familiarHeldItemIds: Set<Long> = emptySet(),
)
