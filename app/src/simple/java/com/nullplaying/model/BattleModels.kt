package com.nullplaying.model

import kotlinx.serialization.Serializable

const val BATTLE_RULES_VERSION = 7
const val BATTLE_UNLIMITED_ROUNDS = 0
const val BATTLE_TICKET_CAPACITY = 5
const val BATTLE_DAILY_MATCH_LIMIT = 10
const val BATTLE_PLACEMENT_BATTLES = 10
const val BATTLE_RATING_K = 24
const val BATTLE_MAX_ACTIVE_TRAITS = 5
const val BATTLE_TRAIT_REMOVAL_MILLIS = 3_600_000L

/**
 * Battle uses its own stable class contract so a server snapshot can be validated without
 * depending on mutable local-game state. Mapping from [HeroClass] belongs at the boundary.
 */
@Serializable
enum class BattleHeroClass {
    WARRIOR,
    ROGUE,
    RANGER,
    MAGE,
    CLERIC,
    PALADIN,
}

@Serializable
enum class BattleGuidance {
    ASSAULT,
    BALANCED,
    GUARD,
}

/** Legacy wire values kept only so old battle records can still be decoded. */
@Serializable
enum class BattleCondition {
    WORST,
    BAD,
    NORMAL,
    GOOD,
    BEST,
}

@Serializable
enum class BattleSkillKind {
    STRIKE,
    ARCANE,
    PIERCE,
    CONTROL,
    RECOVER,
}

@Serializable
enum class BattleEquipmentSlot {
    WEAPON,
    HEAD,
    BODY,
    HANDS,
    FEET,
    ACCESSORY,
}

@Serializable
data class BattleBuildStats(
    val strength: Long = 1L,
    val constitution: Long = 1L,
    val dexterity: Long = 1L,
    val intelligence: Long = 1L,
    val wisdom: Long = 1L,
    val charisma: Long = 1L,
) {
    fun values(): List<Long> = listOf(
        strength,
        constitution,
        dexterity,
        intelligence,
        wisdom,
        charisma,
    )
}

@Serializable
data class NormalizedBattleStats(
    val strength: Int = 0,
    val constitution: Int = 0,
    val dexterity: Int = 0,
    val intelligence: Int = 0,
    val wisdom: Int = 0,
    val charisma: Int = 0,
) {
    fun values(): List<Int> = listOf(
        strength,
        constitution,
        dexterity,
        intelligence,
        wisdom,
        charisma,
    )

    fun total(): Int = values().sum()
}

@Serializable
data class BattleSkillSnapshot(
    val skillId: String = "",
    val displayName: String = "",
    val kind: BattleSkillKind = BattleSkillKind.STRIKE,
    /** 10,000 is a 1.0 multiplier. The engine clamps untrusted extremes. */
    val powerBasisPoints: Int = 12_000,
    val cooldownRounds: Int = 2,
    /** Server-verified mastery from 0 to 100; its battle bonus is capped at five percent. */
    val masteryLevel: Int = 0,
    /** Only a genuinely owned, non-recovery skill may be selected as the once-per-battle finisher. */
    val finisherEligible: Boolean = false,
)

/**
 * Six normalized style axes. They always total 300, so traits redistribute combat behavior
 * instead of becoming a hidden second combat-power total.
 */
@Serializable
data class BattleTraitCombatProfile(
    val aggression: Int = 50,
    val powerAttack: Int = 50,
    val gamble: Int = 50,
    val stability: Int = 50,
    val shortFight: Int = 50,
    val longFight: Int = 50,
) {
    fun values(): List<Int> = listOf(
        aggression,
        powerAttack,
        gamble,
        stability,
        shortFight,
        longFight,
    )
}

@Serializable
data class BattleEquipmentSnapshot(
    val itemId: String = "",
    val displayName: String = "",
    val slot: BattleEquipmentSlot = BattleEquipmentSlot.WEAPON,
    val rarity: String = "",
    /** Narrative-only stable tag. Raw PvE item numbers are represented once in verified power. */
    val narrativeTag: String = "",
)

/**
 * Immutable input issued by a trusted server. Level is display/unlock metadata only: it is never
 * added to combat after [verifiedPower], preventing level from being counted twice.
 */
@Serializable
data class BattleProjectionSnapshot(
    val projectionId: String = "",
    val displayName: String = "",
    val heroClass: BattleHeroClass = BattleHeroClass.WARRIOR,
    val level: Long = 20L,
    val verifiedPower: Long = 0L,
    /** Retained for old snapshot decoding; every value is combat-neutral. */
    val condition: BattleCondition = BattleCondition.NORMAL,
    val build: BattleBuildStats = BattleBuildStats(),
    val guidance: BattleGuidance = BattleGuidance.BALANCED,
    val skills: List<BattleSkillSnapshot> = emptyList(),
    val equipment: List<BattleEquipmentSnapshot> = emptyList(),
    /** Server-issued IDs select normalized V2 combat style axes and matching narrative cues. */
    val activeTraitIds: List<String> = emptyList(),
    /** Effective Hero Path ranks, capped and trusted with the immutable snapshot. */
    val heroPathTraitRanks: Map<String, Int> = emptyMap(),
    /** Pins the committed Hero Path build used when this immutable battle was calculated. */
    val heroPathRevision: Long = 0L,
    val heroPathCatalogVersion: Int = 0,
    /** Immutable V2 allocation. Legacy rank maps remain only for old wire compatibility. */
    val heroPathBattleSnapshot: HeroPathBattleSnapshot = HeroPathBattleSnapshot(),
    val snapshotVersion: Int = 1,
    val issuedAtMillis: Long = 0L,
)

@Serializable
data class NormalizedBattleProjection(
    val projectionId: String = "",
    val displayName: String = "",
    val heroClass: BattleHeroClass = BattleHeroClass.WARRIOR,
    val level: Long = 20L,
    /** Retained for old snapshot decoding; every value is combat-neutral. */
    val condition: BattleCondition = BattleCondition.NORMAL,
    /**
     * Displayed 100..108 growth resonance. Build proportions in [stats] always use a base-100
     * budget; combat applies this value once to final outgoing damage.
     */
    val growthResonanceBudget: Int = 100,
    val stats: NormalizedBattleStats = NormalizedBattleStats(),
    val guidance: BattleGuidance = BattleGuidance.BALANCED,
    val skills: List<BattleSkillSnapshot> = emptyList(),
    val equipment: List<BattleEquipmentSnapshot> = emptyList(),
    val activeTraitIds: List<String> = emptyList(),
    val heroPathTraitRanks: Map<String, Int> = emptyMap(),
    val combatProfile: BattleTraitCombatProfile = BattleTraitCombatProfile(),
    val heroPathRevision: Long = 0L,
    val heroPathCatalogVersion: Int = 0,
    val heroPathBattleSnapshot: HeroPathBattleSnapshot = HeroPathBattleSnapshot(),
    val maxHp: Int = 1_000,
    val snapshotVersion: Int = 1,
)

@Serializable
data class BattleRules(
    val rulesVersion: Int = BATTLE_RULES_VERSION,
    /** Server-set seasonal scale for logarithmic growth resonance. */
    val growthReferencePower: Long = 10_000L,
    /** Legacy wire field. Zero means unlimited and the V6 engine ignores positive old limits. */
    val maxRounds: Int = BATTLE_UNLIMITED_ROUNDS,
)

@Serializable
data class UserInitiatedBattleRequest(
    val battleId: String = "",
    val serverSeed: Long = 0L,
    val requestedAtMillis: Long = 0L,
    val user: BattleProjectionSnapshot = BattleProjectionSnapshot(),
    val opponent: BattleProjectionSnapshot = BattleProjectionSnapshot(),
    /** Read-only rating used as an Elo benchmark; the opponent is never settled by this request. */
    val opponentReferenceScore: Int = 1_000,
    val rules: BattleRules = BattleRules(),
)

@Serializable
enum class BattleSide {
    USER,
    OPPONENT,
}

@Serializable
enum class BattleActionKind {
    BASIC_ATTACK,
    SKILL,
    GUARD,
}

@Serializable
enum class BattleActionResolution {
    HIT,
    BLOCKED,
    EVADED,
    MISSED,
    GUARDED,
    RECOVERED,
}

/** Test-visible evidence for bounded, non-recursive V2 rules. */
@Serializable
data class BattleTalentEffectTrace(
    val sourceNodeId: String = "",
    val effectFamily: HeroPathEffectFamily = HeroPathEffectFamily.RAGE_BURST,
    val effectStage: HeroPathEffectStage = HeroPathEffectStage.RESOLVE_ACTION,
    val triggerDepth: Int = 0,
    val extraActionOrdinal: Int = 0,
    val extraDamage: Int = 0,
    val counterDamage: Int = 0,
    val bonusHealing: Int = 0,
    val resourceRemoved: Int = 0,
    val lethalSurvival: Boolean = false,
    val chargeBefore: Int = 0,
    val chargeAfter: Int = 0,
    val counterRulesVersion: Int = 0,
    val dominantBranch: HeroPathBranch? = null,
    val matchupRelation: HeroPathMatchupRelation = HeroPathMatchupRelation.NEUTRAL,
    val choiceStance: HeroPathChoiceStance = HeroPathChoiceStance.NONE,
    val opponentChoiceStance: HeroPathChoiceStance = HeroPathChoiceStance.NONE,
    val matchupPotencyBasisPoints: Int = 10_000,
    val chargeAccelerationBasisPoints: Int = 0,
)

@Serializable
data class BattleRoundAction(
    val actor: BattleSide = BattleSide.USER,
    val kind: BattleActionKind = BattleActionKind.BASIC_ATTACK,
    val skillId: String = "",
    val damage: Int = 0,
    val healing: Int = 0,
    val critical: Boolean = false,
    val powerAttack: Boolean = false,
    val finisher: Boolean = false,
    val finisherSucceeded: Boolean = false,
    val selfDamage: Int = 0,
    val resolution: BattleActionResolution = BattleActionResolution.HIT,
    val talentEffects: List<BattleTalentEffectTrace> = emptyList(),
)

@Serializable
data class BattleRound(
    val number: Int = 0,
    val userHpBefore: Int = 1_000,
    val opponentHpBefore: Int = 1_000,
    /** Internal momentum from 0 to 100. It is intentionally not rendered as another UI bar. */
    val userMoraleBefore: Int = 50,
    val opponentMoraleBefore: Int = 50,
    val userAction: BattleRoundAction = BattleRoundAction(),
    val opponentAction: BattleRoundAction = BattleRoundAction(actor = BattleSide.OPPONENT),
    val userHpAfter: Int = 1_000,
    val opponentHpAfter: Int = 1_000,
    val userMoraleAfter: Int = 50,
    val opponentMoraleAfter: Int = 50,
    val userClassChargeBefore: Int = 0,
    val opponentClassChargeBefore: Int = 0,
    val userClassChargeAfter: Int = 0,
    val opponentClassChargeAfter: Int = 0,
    val talentEffects: List<BattleTalentEffectTrace> = emptyList(),
)

@Serializable
enum class BattleOutcome {
    USER_WIN,
    USER_LOSS,
    DRAW,
}

@Serializable
enum class BattleRewardPolicy {
    /** Battle V0.1 writes rank, record and traits only; it has no economic reward fields. */
    RECORD_ONLY,
}

@Serializable
data class ProjectionBattleResult(
    val battleId: String = "",
    val serverSeed: Long = 0L,
    val rulesVersion: Int = BATTLE_RULES_VERSION,
    val user: NormalizedBattleProjection = NormalizedBattleProjection(),
    val opponent: NormalizedBattleProjection = NormalizedBattleProjection(),
    val rounds: List<BattleRound> = emptyList(),
    val outcome: BattleOutcome = BattleOutcome.DRAW,
    val rewardPolicy: BattleRewardPolicy = BattleRewardPolicy.RECORD_ONLY,
)

@Serializable
data class BattleSeasonStanding(
    val score: Int = 1_000,
    val completedBattles: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val draws: Int = 0,
)

@Serializable
data class BattleStandingUpdate(
    val before: BattleSeasonStanding = BattleSeasonStanding(),
    val after: BattleSeasonStanding = BattleSeasonStanding(),
    val scoreDelta: Int = 0,
    val opponentReferenceScore: Int = 1_000,
    val kFactor: Int = BATTLE_RATING_K,
    val wasPlacementBattle: Boolean = true,
    val placementBattlesRemaining: Int = BATTLE_PLACEMENT_BATTLES,
    /** Explicit invariant for the one-way, user-initiated V0.1 ladder. */
    val opponentScoreChanged: Boolean = false,
)

@Serializable
data class BattleTicketState(
    /** Caller-supplied trusted UTC epoch day; the engine does no clock or timezone math. */
    val gameEpochDay: Long = Long.MIN_VALUE,
    val remaining: Int = BATTLE_TICKET_CAPACITY,
)

@Serializable
data class BattleTicketTransition(
    val accepted: Boolean = false,
    val state: BattleTicketState = BattleTicketState(),
)

@Serializable
data class OfficialBattleResolution(
    val accepted: Boolean = false,
    val tickets: BattleTicketState = BattleTicketState(),
    val battle: ProjectionBattleResult? = null,
    val standing: BattleStandingUpdate? = null,
)

@Serializable
enum class BattleTraitCategory {
    OPENING,
    OFFENSE,
    DEFENSE,
    REVERSAL,
    ENDURANCE,
    PRECISION,
    TACTICS,
    TEMPERAMENT,
    MOMENTUM,
    FINISH,
}

@Serializable
data class BattleTraitDefinition(
    val id: String = "",
    val nameKo: String = "",
    val descriptionKo: String = "",
    val category: BattleTraitCategory = BattleTraitCategory.TEMPERAMENT,
    val combatProfile: BattleTraitCombatProfile = BattleTraitCombatProfile(),
)

@Serializable
data class ActiveBattleTrait(
    val traitId: String = "",
    val acquiredAtMillis: Long = 0L,
    val evidenceCount: Int = 1,
)

@Serializable
data class BattleTraitRemoval(
    val traitId: String = "",
    val requestedAtMillis: Long = 0L,
    val readyAtMillis: Long = BATTLE_TRAIT_REMOVAL_MILLIS,
)

@Serializable
data class BattleTraitState(
    val active: List<ActiveBattleTrait> = emptyList(),
    val removal: BattleTraitRemoval? = null,
)

@Serializable
enum class BattleTraitMutationStatus {
    ADDED,
    EVIDENCE_ADDED,
    REMOVAL_SCHEDULED,
    REMOVAL_CANCELLED,
    REMOVED,
    UNKNOWN_TRAIT,
    ACTIVE_LIMIT_REACHED,
    TRAIT_NOT_ACTIVE,
    REMOVAL_ALREADY_PENDING,
    NO_REMOVAL_PENDING,
    NOT_READY,
    CANCELLATION_WINDOW_CLOSED,
}

@Serializable
data class BattleTraitMutation(
    val status: BattleTraitMutationStatus = BattleTraitMutationStatus.UNKNOWN_TRAIT,
    val state: BattleTraitState = BattleTraitState(),
)
