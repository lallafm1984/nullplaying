package com.nullplaying.engine.arena

import com.nullplaying.model.BattleOutcome
import com.nullplaying.model.HeroClass

/**
 * Pure V9 growth ledger. The caller persists each returned state atomically and may call
 * completeBattle only after a normal final result (including a recovered final result).
 * This is not a server-authoritative ticket system or a trusted-clock implementation.
 */
object ArenaProgressionRules {
    const val RULES_VERSION = "arena-progression-linear-cap-v5"
    const val MIN_HERO_LEVEL = 10L
    const val ENHANCEMENT_START_LEVEL = 51
    const val MAX_ARENA_LEVEL = 100
    /** Draw is a defensive engine fallback; normal progression is win/loss only. */
    const val MATCH_XP = 100L
    const val WIN_MATCH_XP = 120L
    const val LOSS_MATCH_XP = 80L
    const val MIN_MATCH_XP = LOSS_MATCH_XP
    /** Retired XP ledger keeps its historical bound so old saves remain valid. Entry limits live in ArenaTicketPolicy. */
    const val DAILY_GROWTH_ENTRIES = 20
    /** Linear growth reaches the cap at Lv.75; later levels never exceed six average days. */
    const val XP_PER_ARENA_LEVEL = 160L
    const val MAX_XP_FOR_NEXT_LEVEL = 12_000L
    const val MAX_TOTAL_XP = 744_000L
    const val MAX_AWARD_IDS = 9_300

    fun xpForLevel(level: Int): Long {
        require(level in 1..MAX_ARENA_LEVEL)
        var total = 0L
        for (currentLevel in 1 until level) {
            total += xpRequiredForNextLevel(currentLevel)
        }
        return total
    }

    fun levelFromXp(totalXp: Long): Int {
        require(totalXp >= 0)
        var low = 1
        var high = MAX_ARENA_LEVEL
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (xpForLevel(mid) <= totalXp) low = mid else high = mid - 1
        }
        return low
    }

    fun xpRequiredForNextLevel(level: Int): Long {
        require(level in 1..MAX_ARENA_LEVEL)
        if (level == MAX_ARENA_LEVEL) return 0L
        return minOf(MAX_XP_FOR_NEXT_LEVEL, XP_PER_ARENA_LEVEL * level)
    }

    fun xpForOutcome(outcome: BattleOutcome): Long = when (outcome) {
        BattleOutcome.USER_WIN -> WIN_MATCH_XP
        BattleOutcome.USER_LOSS -> LOSS_MATCH_XP
        BattleOutcome.DRAW -> MATCH_XP
    }

    fun enhancementCost(stage: Int): Int {
        require(stage in 0..3)
        return when (stage) { 0 -> 0; 1 -> 2; 2 -> 5; else -> 10 }
    }

    fun baseCost(allocation: ArenaTraitAllocation): Int =
        requireNotNull(ArenaProgressionCatalog.find(allocation.id)).basePointCost(allocation.rank)

    /** Recheck the entire captured build against actual current ownership. */
    fun isValidForFighter(
        state: ArenaProgressionState,
        heroClass: HeroClass,
        heroLevel: Long,
        ownedSupports: Set<String>,
        ignoreHeroLevelGate: Boolean = false,
    ): Boolean = isValid(state) && state.allocations.all { allocation ->
        val definition = ArenaProgressionCatalog.find(allocation.id) ?: return@all false
        definition.heroClass == heroClass &&
            (ignoreHeroLevelGate || heroLevel >= definition.minHeroLevel) &&
            definition.requiredSupportIds.all { it in ownedSupports }
    }

    fun initialize(
        state: ArenaProgressionState,
        heroLevel: Long,
        ignoreHeroLevelGate: Boolean = false,
    ): ArenaProgressionState {
        require(isValid(state)) { "Invalid arena growth state" }
        if (state.unlocked || heroLevel < if (ignoreHeroLevelGate) 1 else MIN_HERO_LEVEL) return state
        return state.copy(unlocked = true, revision = state.revision + 1)
    }

    fun view(state: ArenaProgressionState, epochDay: Long): ArenaProgressionView {
        require(isValid(state)) { "Invalid arena growth state" }
        require(epochDay >= 0) { "Invalid epoch day" }
        if (!state.unlocked) return ArenaProgressionView(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val level = levelFromXp(state.totalXp)
        val base = minOf(level, ENHANCEMENT_START_LEVEL - 1)
        val enhancement = maxOf(0, level - ENHANCEMENT_START_LEVEL + 1)
        val baseSpent = state.allocations.sumOf { baseCost(it) }
        val enhancementSpent = state.allocations.sumOf { enhancementCost(it.enhancement) }
        val growthRemaining = when {
            level == MAX_ARENA_LEVEL || epochDay < state.growthDay -> 0
            epochDay > state.growthDay -> DAILY_GROWTH_ENTRIES
            else -> DAILY_GROWTH_ENTRIES - state.growthEntriesUsed
        }
        return ArenaProgressionView(
            level = level,
            xpIntoLevel = if (level == MAX_ARENA_LEVEL) 0 else state.totalXp - xpForLevel(level),
            xpToNext = xpRequiredForNextLevel(level),
            baseEarned = base,
            baseSpent = baseSpent,
            baseAvailable = base - baseSpent,
            enhancementEarned = enhancement,
            enhancementSpent = enhancementSpent,
            enhancementAvailable = enhancement - enhancementSpent,
            growthRemaining = growthRemaining,
        )
    }

    /** Reservation consumes the issue day's growth slot, never the completion day's slot. */
    fun reserveBattle(
        state: ArenaProgressionState,
        battleId: String,
        epochDay: Long,
        allowGrowth: Boolean = true,
        outcome: BattleOutcome = BattleOutcome.DRAW,
    ): ArenaProgressionState {
        require(isValid(state)) { "Invalid arena growth state" }
        require(state.unlocked) { "Arena growth locked" }
        require(battleId.isNotBlank() && epochDay >= 0) { "Invalid battle identity/day" }
        if (battleId in state.settledBattleIds || state.pending?.battleId == battleId) return state
        require(state.pending == null) { "A battle is already reserved" }
        val day = maxOf(epochDay, state.growthDay)
        val used = if (epochDay > state.growthDay) 0 else state.growthEntriesUsed
        val eligible = !state.characterLevelPoints && allowGrowth && epochDay >= state.growthDay &&
            state.totalXp < MAX_TOTAL_XP && used < DAILY_GROWTH_ENTRIES
        return state.copy(
            revision = state.revision + 1,
            growthDay = day,
            growthEntriesUsed = used + if (eligible) 1 else 0,
            pending = ArenaProgressionPending(
                battleId = battleId,
                issuedDay = epochDay,
                xpEligible = eligible,
                xpAward = if (state.characterLevelPoints) 0L else xpForOutcome(outcome),
            ),
        )
    }

    /** Invalid/missing/repeated completion IDs award nothing; practice clears with zero XP. */
    fun completeBattle(state: ArenaProgressionState, battleId: String): ArenaProgressionState {
        require(isValid(state)) { "Invalid arena growth state" }
        val pending = state.pending ?: return state
        if (battleId != pending.battleId) return state
        val grant = if (!state.characterLevelPoints && pending.xpEligible && battleId !in state.settledBattleIds)
            minOf(pending.xpAward, MAX_TOTAL_XP - state.totalXp) else 0L
        return state.copy(
            totalXp = state.totalXp + grant,
            revision = state.revision + 1,
            pending = null,
            settledBattleIds = if (grant > 0 || state.characterLevelPoints)
                (state.settledBattleIds + battleId).takeLast(MAX_AWARD_IDS) else state.settledBattleIds,
        )
    }

    fun allocate(
        state: ArenaProgressionState,
        heroClass: HeroClass,
        heroLevel: Long,
        ownedSupports: Set<String>,
        traitId: String,
        rank: Int,
        enhancement: Int,
        ignoreHeroLevelGate: Boolean = false,
    ): ArenaProgressionMutation {
        fun rejected(error: String) = ArenaProgressionMutation(false, state, error)
        if (state.pending != null) return rejected("battle_pending")
        if (!isValid(state)) return rejected("invalid_state")
        if (!state.unlocked || heroLevel < if (ignoreHeroLevelGate) 1 else MIN_HERO_LEVEL) return rejected("arena_locked")
        val definition = ArenaProgressionCatalog.find(traitId) ?: return rejected("unknown_trait")
        if (definition.heroClass != heroClass || state.allocations.any {
                ArenaProgressionCatalog.find(it.id)?.heroClass != heroClass
            }) return rejected("foreign_trait")
        if (rank !in 0..definition.maxRank || enhancement !in 0..definition.maxEnhancement) return rejected("invalid_rank")
        if (!ignoreHeroLevelGate && heroLevel < definition.minHeroLevel) return rejected("hero_level_required")
        if (rank > 0 && definition.requiredSupportIds.any { it !in ownedSupports })
            return rejected("support_required")
        // Removing the base automatically refunds linked enhancement, even if the UI
        // submits its previous enhancement value with rank zero.
        val nextAllocations = state.allocations.filterNot { it.id == traitId } +
            if (rank > 0) listOf(ArenaTraitAllocation(traitId, rank, enhancement)) else emptyList()
        val level = levelFromXp(state.totalXp)
        if (nextAllocations.count { ArenaProgressionCatalog.find(it.id)?.isCore == true } > 1) return rejected("core_limit")
        if (nextAllocations.sumOf { baseCost(it) } > minOf(level, ENHANCEMENT_START_LEVEL - 1)) return rejected("base_budget")
        if (nextAllocations.sumOf { enhancementCost(it.enhancement) } > maxOf(0, level - ENHANCEMENT_START_LEVEL + 1))
            return rejected("enhancement_budget")
        if (nextAllocations == state.allocations) return ArenaProgressionMutation(true, state)
        return ArenaProgressionMutation(true,
            state.copy(allocations = nextAllocations, revision = state.revision + 1))
    }

    fun reset(state: ArenaProgressionState): ArenaProgressionMutation {
        if (state.pending != null) return ArenaProgressionMutation(false, state, "battle_pending")
        if (!isValid(state)) return ArenaProgressionMutation(false, state, "invalid_state")
        if (state.allocations.isEmpty()) return ArenaProgressionMutation(true, state)
        return ArenaProgressionMutation(true, state.copy(allocations = emptyList(), revision = state.revision + 1))
    }

    /** Caller still validates current class/support ownership before battle capture. */
    fun toSupportTraits(state: ArenaProgressionState): List<ArenaSupportTraitRank> {
        require(isValid(state)) { "Invalid arena growth state" }
        return state.allocations.map { ArenaSupportTraitRank(it.id, it.rank, it.enhancement) }
    }

    /** Reject corruption instead of silently trimming purchases or granting replacement XP. */
    fun isValid(state: ArenaProgressionState): Boolean {
        if (state.totalXp !in 0..MAX_TOTAL_XP || state.revision !in 0 until Long.MAX_VALUE ||
            state.growthDay < -1 || state.growthEntriesUsed !in 0..DAILY_GROWTH_ENTRIES) return false
        if (!state.unlocked && (state.totalXp != 0L || state.allocations.isNotEmpty() ||
                state.pending != null || state.settledBattleIds.isNotEmpty() ||
                state.growthDay != -1L || state.growthEntriesUsed != 0)) return false
        if (state.growthDay == -1L && (state.growthEntriesUsed != 0 || state.pending != null)) return false
        if (state.settledBattleIds.size > MAX_AWARD_IDS ||
            state.settledBattleIds.any { it.isBlank() } ||
            state.settledBattleIds.distinct().size != state.settledBattleIds.size) return false
        if (!state.characterLevelPoints && state.settledBattleIds.size.toLong() > state.totalXp / MIN_MATCH_XP) return false
        state.pending?.let {
            if (it.battleId.isBlank() || it.issuedDay < 0 || it.issuedDay > state.growthDay ||
                it.battleId in state.settledBattleIds ||
                (if (state.characterLevelPoints) it.xpEligible || it.xpAward != 0L
                    else it.xpAward !in setOf(LOSS_MATCH_XP, MATCH_XP, WIN_MATCH_XP)) ||
                (it.xpEligible && (state.growthEntriesUsed == 0 || it.issuedDay != state.growthDay))) return false
        }
        if (state.allocations.map { it.id }.distinct().size != state.allocations.size) return false
        val classes = mutableSetOf<HeroClass>()
        for (allocation in state.allocations) {
            val definition = ArenaProgressionCatalog.find(allocation.id) ?: return false
            classes += definition.heroClass
            if (allocation.rank !in 1..definition.maxRank || allocation.enhancement !in 0..definition.maxEnhancement) return false
        }
        if (classes.size > 1 || state.allocations.count { ArenaProgressionCatalog.find(it.id)?.isCore == true } > 1) return false
        val level = if (state.unlocked) levelFromXp(state.totalXp) else 0
        return state.allocations.sumOf { baseCost(it) } <= minOf(level, ENHANCEMENT_START_LEVEL - 1) &&
            state.allocations.sumOf { enhancementCost(it.enhancement) } <= maxOf(0, level - ENHANCEMENT_START_LEVEL + 1)
    }
}
