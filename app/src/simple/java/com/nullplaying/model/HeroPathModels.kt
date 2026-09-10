package com.nullplaying.model

import kotlinx.serialization.Serializable

const val HERO_PATH_SCHEMA_VERSION = 3
const val HERO_PATH_CATALOG_VERSION = 2
const val HERO_PATH_TREE_VERSION = 2
const val HERO_PATH_MILESTONE_INTERVAL = 5L
const val HERO_PATH_OFFER_COUNT = 3
const val HERO_PATH_MAX_ACTIVE_TRAITS = 5
const val HERO_PATH_MAX_ACTIVE_CORES = 1
const val HERO_PATH_MAX_CLASS_CHARGE = 3
const val HERO_PATH_MAX_EFFECTIVE_RANK = 5
const val HERO_PATH_MAX_COMBAT_POINTS = 20
const val HERO_PATH_COUNTER_RULES_VERSION = 1

@Serializable
enum class HeroPathCounterArchetype {
    EXECUTION,
    SUSTAIN,
    CONTROL,
    PRECISION,
    FORTRESS,
    TEMPO,
}

@Serializable
enum class HeroPathMatchupRelation { FAVORABLE, UNFAVORABLE, NEUTRAL }

@Serializable
enum class HeroPathChoiceStance { NONE, A, B }

/** The three permanent paths available to each battle class. */
@Serializable
enum class HeroPathBranch {
    WARRIOR_BERSERKER,
    WARRIOR_BULWARK,
    WARRIOR_WARLORD,
    ROGUE_ASSASSIN,
    ROGUE_SHADOW_DANCER,
    ROGUE_TRICKSTER,
    RANGER_MARKSMAN,
    RANGER_WINDWALKER,
    RANGER_TRAPPER,
    MAGE_ELEMENTALIST,
    MAGE_ARCANIST,
    MAGE_FORBIDDEN,
    CLERIC_SANCTUARY,
    CLERIC_JUDGMENT,
    CLERIC_PROVIDENCE,
    PALADIN_GUARDIAN,
    PALADIN_AVENGER,
    PALADIN_DAWN,
}

/** One bounded rules family backs each specialization; nodes select a stage inside the family. */
@Serializable
enum class HeroPathEffectFamily {
    RAGE_BURST,
    IMPACT_GUARD,
    MORALE_COMMAND,
    OPENING_EXECUTION,
    EVASIVE_CHAIN,
    DECEPTIVE_CONTROL,
    FOCUSED_SHOT,
    MOBILE_VOLLEY,
    CONTROLLED_HUNT,
    ELEMENTAL_BURST,
    ARCANE_CYCLE,
    FORBIDDEN_GAMBIT,
    GRACEFUL_RECOVERY,
    GRACE_JUDGMENT,
    PROVIDENT_REVERSAL,
    OATHED_GUARD,
    RETRIBUTIVE_COUNTER,
    DAWN_CYCLE,
}

@Serializable
enum class HeroPathNodeType {
    FOUNDATION,
    CHOICE,
    TACTICAL,
    SPECIAL,
    CORE,
    /** V1 compatibility only. Normalized V2 catalogs never emit this value. */
    NORMAL,
}

@Serializable
enum class HeroPathNodeSlot {
    FOUNDATION_A,
    FOUNDATION_B,
    CHOICE_A,
    CHOICE_B,
    SPECIAL_A,
    SPECIAL_B,
    ADVANCED_TACTIC,
    CORE,
}

@Serializable
enum class HeroPathTier(val minimumLevel: Long, val requiredSpecializationPoints: Int) {
    TIER_1(5L, 0),
    TIER_2(20L, 3),
    TIER_3(35L, 6),
    CORE(50L, 9),
}

@Serializable
enum class HeroPathEffectStage {
    PRE_BATTLE,
    ROUND_START,
    PLAN_ACTION,
    RESOLVE_ACTION,
    AFTER_ACTION,
    ROUND_END,
}

/** Typed UI mutations prevent an unlock card from silently replacing or changing a core. */
@Serializable
enum class HeroPathChoiceType {
    UNLOCK,
    RANK_UP,
    REPLACE,
    CHANGE_CORE,
}

@Serializable
data class HeroPathBranchDefinition(
    val branch: HeroPathBranch,
    val heroClass: BattleHeroClass,
    val effectFamily: HeroPathEffectFamily,
    val counterArchetype: HeroPathCounterArchetype = HeroPathCounterArchetype.EXECUTION,
    val nameKo: String,
    val nameEn: String,
    val nameJa: String,
    val descriptionKo: String = "",
    val descriptionEn: String = "",
    val descriptionJa: String = "",
)

@Serializable
data class HeroPathNodeDefinition(
    val traitId: String,
    val heroClassAffinity: BattleHeroClass,
    val branch: HeroPathBranch,
    val effectFamily: HeroPathEffectFamily,
    val nodeType: HeroPathNodeType = HeroPathNodeType.FOUNDATION,
    val slot: HeroPathNodeSlot = HeroPathNodeSlot.FOUNDATION_A,
    val tier: HeroPathTier = HeroPathTier.TIER_1,
    val maxRank: Int = 1,
    val minimumLevel: Long = HERO_PATH_MILESTONE_INTERVAL,
    val requiredBranchInvestments: Int = 0,
    val prerequisiteTraitIds: List<String> = emptyList(),
    val choiceGroupId: String = "",
    val choiceOptions: List<String> = emptyList(),
    val effectStage: HeroPathEffectStage = HeroPathEffectStage.PLAN_ACTION,
    val nameKo: String = traitId,
    val nameEn: String = traitId,
    val nameJa: String = traitId,
    val summaryKo: String = "",
    val summaryEn: String = "",
    val summaryJa: String = "",
) {
    val nodeId: String
        get() = traitId

    val kind: HeroPathNodeType
        get() = nodeType
}

@Serializable
data class HeroPathTraitProgress(
    val traitId: String,
    /** Rank is an unbounded history counter; combat reads [effectiveRank] only. */
    val rank: Int = 1,
    val unlockedAtLevel: Long = HERO_PATH_MILESTONE_INTERVAL,
    /** A or B for CHOICE nodes; blank for every other node. */
    val selectedChoiceId: String = "",
) {
    val effectiveRank: Int
        get() = rank.coerceIn(1, HERO_PATH_MAX_EFFECTIVE_RANK)
}

@Serializable
data class HeroPathOfferChoice(
    val offerId: String,
    val type: HeroPathChoiceType,
    val traitId: String,
    /** Required by REPLACE, and names the prior core for CHANGE_CORE when one exists. */
    val replacementTraitId: String = "",
)

@Serializable
data class HeroPathMilestoneToken(
    val tokenId: String,
    val milestoneLevel: Long,
    val generationSeed: Long,
    /** Issued tokens always contain exactly [HERO_PATH_OFFER_COUNT] choices. */
    val offers: List<HeroPathOfferChoice>,
    val selectedOfferId: String = "",
) {
    val resolved: Boolean
        get() = selectedOfferId.isNotBlank()
}

@Serializable
data class HeroPathState(
    val schemaVersion: Int = HERO_PATH_SCHEMA_VERSION,
    val catalogVersion: Int = HERO_PATH_CATALOG_VERSION,
    val treeVersion: Int = HERO_PATH_TREE_VERSION,
    /** Increments only after a committed choice so precomputed battles can pin this build. */
    val revision: Long = 0L,
    val heroClass: BattleHeroClass = BattleHeroClass.WARRIOR,
    /** Monotonic point ledger. Level loss never removes an already granted point. */
    val earnedPoints: Long = 0L,
    val lastGrantedMilestone: Long = 0L,
    val traits: List<HeroPathTraitProgress> = emptyList(),
    val activeTraitIds: List<String> = emptyList(),
    val activeCoreTraitId: String = "",
    val milestoneTokens: List<HeroPathMilestoneToken> = emptyList(),
    /** Set once after the numeric V1 allocation has been refunded instead of remapped. */
    val v1AllocationRefunded: Boolean = false,
) {
    val spentPoints: Long
        get() = traits.sumOf { it.rank.coerceAtLeast(1).toLong() }

    val unspentPoints: Long
        get() = (earnedPoints - spentPoints).coerceAtLeast(0L)
}

/** Entire desired allocation. The engine validates and commits this atomically. */
@Serializable
data class HeroPathAllocationTarget(
    val expectedRevision: Long,
    val nodeRanks: Map<String, Int>,
    val nodeChoices: Map<String, String> = emptyMap(),
    val activeCoreNodeId: String = "",
)

@Serializable
data class HeroPathBattleNodeSnapshot(
    val nodeId: String,
    val rank: Int,
    val selectedChoiceId: String = "",
    val branch: HeroPathBranch,
    val effectFamily: HeroPathEffectFamily,
    val slot: HeroPathNodeSlot,
    val effectStage: HeroPathEffectStage,
)

/** Immutable battle-owned copy. Resetting the live tree cannot alter this value. */
@Serializable
data class HeroPathBattleSnapshot(
    val treeVersion: Int = 0,
    val allocationRevision: Long = 0L,
    val heroClass: BattleHeroClass = BattleHeroClass.WARRIOR,
    val specializationIds: List<HeroPathBranch> = emptyList(),
    val nodes: List<HeroPathBattleNodeSnapshot> = emptyList(),
    val activeCoreNodeId: String = "",
    val derivedRulesDigest: String = "",
    val ownedSkillIds: List<String> = emptyList(),
    val initialClassCharge: Int = 0,
    val counterRulesVersion: Int = HERO_PATH_COUNTER_RULES_VERSION,
    val dominantBranch: HeroPathBranch? = null,
    val choiceStance: HeroPathChoiceStance = HeroPathChoiceStance.NONE,
)

@Serializable
data class HeroPathClassCharge(
    val heroClass: BattleHeroClass,
    val value: Int = 0,
)

@Serializable
data class HeroPathChargeTransition(
    val accepted: Boolean,
    val before: HeroPathClassCharge,
    val after: HeroPathClassCharge,
)

@Serializable
enum class HeroPathMutationStatus {
    APPLIED,
    INVALID_MILESTONE,
    TOKEN_ALREADY_ISSUED,
    UNKNOWN_TOKEN,
    TOKEN_ALREADY_RESOLVED,
    UNKNOWN_OFFER,
    INVALID_OFFER,
    INSUFFICIENT_POINTS,
    INVALID_ALLOCATION,
    STALE_REVISION,
    NOTHING_TO_RESET,
    CHOICE_CONFLICT,
    POINT_CAP_EXCEEDED,
    INVALID_SNAPSHOT,
}

@Serializable
data class HeroPathMutation(
    val status: HeroPathMutationStatus,
    val state: HeroPathState,
    val token: HeroPathMilestoneToken? = null,
)
