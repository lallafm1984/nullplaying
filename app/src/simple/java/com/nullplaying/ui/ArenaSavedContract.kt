package com.nullplaying.ui

import com.nullplaying.engine.arena.ARENA_SUPPORT_RULES_VERSION
import com.nullplaying.engine.arena.ArenaSupportCatalog
import com.nullplaying.engine.arena.ArenaSupportInput
import com.nullplaying.engine.arena.ArenaMatchmakingProfile
import com.nullplaying.engine.arena.ArenaTurnRules
import com.nullplaying.engine.arena.freezeResolvedSupports
import com.nullplaying.model.HeroClass
import kotlinx.serialization.Serializable

/** The automatic level grants are arena-only and never alter adventure skill ownership. */
@Serializable
internal data class ArenaSupportOwnership(
    val heroClass: HeroClass,
    val ids: Set<String>,
)

/** Validate before extending a legacy save; foreign/unknown grants never become usable. */
internal fun reconcileArenaSupportOwnership(
    previous: ArenaSupportOwnership?, heroClass: HeroClass, heroLevel: Long,
): ArenaSupportOwnership {
    require(heroLevel >= 1)
    require(previous == null || previous.heroClass == heroClass) { "Foreign support ownership" }
    previous?.ids?.forEach { id ->
        val definition = requireNotNull(ArenaSupportCatalog.find(id)) { "Unknown owned support" }
        require(definition.heroClass == heroClass && definition.unlockLevel <= heroLevel) { "Invalid support grant" }
    }
    return ArenaSupportOwnership(heroClass,
        previous?.ids.orEmpty() + ArenaSupportCatalog.unlockedIds(heroClass, heroLevel))
}

/** Exact participants and rule configuration for audit/reproduction, never an XP claim. */
@Serializable
internal data class ArenaSavedBattleContract(
    val rulesVersion: String = ARENA_SUPPORT_RULES_VERSION,
    val seed: Long,
    val user: ArenaSupportInput,
    val opponent: ArenaSupportInput,
    val rules: ArenaTurnRules,
    val progressionRevision: Long,
    val issuedDay: Long,
    val ignoreHeroLevelGate: Boolean = false,
    /** Null/zero/blank only for retained V2 history. */
    val skillTreeRevision: Long? = null,
    val skillTreeCatalogVersion: Int = 0,
    val skillTreeRulesVersion: String = "",
    val matchmakingProfile: ArenaMatchmakingProfile? = null,
)

internal fun ArenaLiveBattle.savedContract() = ArenaSavedBattleContract(
    rulesVersion = simulation.rulesVersion, seed = simulation.seed,
    user = user.freezeResolvedSupports(),
    opponent = opponent.freezeResolvedSupports(),
    rules = simulation.appliedRules,
    progressionRevision = progressionRevision, issuedDay = issuedDay,
    ignoreHeroLevelGate = ignoreHeroLevelGate,
    skillTreeRevision = skillTreeRevision,
    skillTreeCatalogVersion = skillTreeCatalogVersion,
    skillTreeRulesVersion = skillTreeRulesVersion,
    matchmakingProfile = matchmakingProfile,
)
