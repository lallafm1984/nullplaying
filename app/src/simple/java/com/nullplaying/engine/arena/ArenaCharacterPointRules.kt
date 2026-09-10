package com.nullplaying.engine.arena

/** Arena points are a derived entitlement, never a second XP balance or a level-up award. */
object ArenaCharacterPointRules {
    const val RULES_VERSION = "arena-character-points-v1"
    const val MIN_HERO_LEVEL = 10L
    const val MAX_POINTS = 100

    fun budget(heroLevel: Long): Int =
        if (heroLevel < MIN_HERO_LEVEL) 0 else heroLevel.coerceAtMost(MAX_POINTS.toLong()).toInt()

    /** Low-level fixtures may enter with their own level budget; production still unlocks at 10. */
    fun combatBudget(heroLevel: Long, allowLowLevelQa: Boolean = false): Int =
        if (allowLowLevelQa && heroLevel in 1 until MIN_HERO_LEVEL) heroLevel.toInt() else budget(heroLevel)

    /** Finish any captured legacy result before switching policy. Retain its audit/clock fields. */
    fun migrate(state: ArenaProgressionState, heroLevel: Long, allowLowLevelQa: Boolean = false): ArenaProgressionState {
        require(ArenaProgressionRules.isValid(state))
        if (state.pending != null) return state
        val initialized = ArenaProgressionRules.initialize(state, heroLevel, allowLowLevelQa)
        return if (initialized.characterLevelPoints) initialized else initialized.copy(
            characterLevelPoints = true,
            revision = initialized.revision + 1,
        )
    }
}
