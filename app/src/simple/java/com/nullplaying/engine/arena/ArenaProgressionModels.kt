package com.nullplaying.engine.arena

import kotlinx.serialization.Serializable

/** Settlement ledger. Legacy XP remains readable; current skill points derive from hero level. */
@Serializable
data class ArenaProgressionState(
    val unlocked: Boolean = false,
    val totalXp: Long = 0,
    val revision: Long = 0,
    val allocations: List<ArenaTraitAllocation> = emptyList(),
    /** Trusted UTC epoch day supplied by the caller; -1 means no entry has been issued yet. */
    val growthDay: Long = -1,
    val growthEntriesUsed: Int = 0,
    val pending: ArenaProgressionPending? = null,
    /** Legacy XP receipts plus recent current-policy completions, bounded by MAX_AWARD_IDS. */
    val settledBattleIds: List<String> = emptyList(),
    /** False only for legacy saves/replays. New battles use hero level and never award arena XP. */
    val characterLevelPoints: Boolean = false,
)

@Serializable
data class ArenaTraitAllocation(val id: String, val rank: Int, val enhancement: Int = 0)

@Serializable
data class ArenaProgressionPending(
    val battleId: String,
    val issuedDay: Long,
    val xpEligible: Boolean,
    /** Frozen at reservation so crash recovery cannot change the result reward. */
    val xpAward: Long = 100L,
)

data class ArenaProgressionView(
    val level: Int,
    val xpIntoLevel: Long,
    /** Zero at the level cap or before unlock. */
    val xpToNext: Long,
    val baseEarned: Int,
    val baseSpent: Int,
    val baseAvailable: Int,
    val enhancementEarned: Int,
    val enhancementSpent: Int,
    val enhancementAvailable: Int,
    val growthRemaining: Int,
)

data class ArenaProgressionMutation(
    val accepted: Boolean,
    val state: ArenaProgressionState,
    /** Stable diagnostic key. UI localizes this rather than displaying technical text. */
    val error: String? = null,
)
