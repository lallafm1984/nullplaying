package com.nullplaying.ui

import com.nullplaying.engine.arena.ArenaSkillAllocation
import com.nullplaying.engine.arena.ArenaSkillTreeCatalog
import com.nullplaying.engine.arena.ArenaSkillTreeRules
import com.nullplaying.engine.arena.ArenaSkillTreeState
import com.nullplaying.model.HeroClass

internal fun arenaProgressionQaInitialState(
    heroClass: HeroClass,
    arenaLevel: Int,
    allocatedPoints: Int,
    seed: Long,
    focusedRootSlot: String?,
    focusedRootRank: Int,
    ownedAttackIds: Set<String>,
): ArenaSkillTreeState {
    val focusedRoot = focusedRootSlot?.let { slot ->
        ArenaSkillTreeCatalog.forClass(heroClass).firstOrNull { it.slotKey == slot && it.isRoot }
    }
    return when {
        focusedRoot != null && focusedRootRank in 1..focusedRoot.maxRank && focusedRootRank <= arenaLevel -> {
            ArenaSkillTreeState(
                heroClass = heroClass,
                allocations = listOf(ArenaSkillAllocation(focusedRoot.id, focusedRootRank)),
            )
        }
        allocatedPoints == 0 -> ArenaSkillTreeRules.initialize(null, heroClass)
        else -> ArenaSkillTreeRules.autoAllocate(
            heroClass = heroClass,
            arenaLevel = allocatedPoints,
            ownedAttackIds = ownedAttackIds,
            seed = seed,
        )
    }
}
