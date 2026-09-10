package com.nullplaying.engine

import com.nullplaying.engine.arena.ArenaAttackInput
import com.nullplaying.engine.arena.ArenaCoreStats
import com.nullplaying.engine.arena.ArenaFighterInput
import com.nullplaying.engine.arena.ArenaRunStatus
import com.nullplaying.engine.arena.ArenaSupportInput
import com.nullplaying.engine.arena.ArenaSupportResult
import com.nullplaying.engine.arena.ArenaSupportTurnEngine
import com.nullplaying.model.AdventureEncounterCandidate
import com.nullplaying.model.AdventureRelationshipBattleKind
import com.nullplaying.model.AdventureRelationshipBattleParticipantSnapshot
import com.nullplaying.model.AdventureRelationshipEquipmentSnapshot
import com.nullplaying.model.AdventureRelationshipRun
import com.nullplaying.model.AdventureRelationshipSkillSnapshot
import com.nullplaying.model.BattleOutcome
import com.nullplaying.model.LearnedSkill
import com.nullplaying.model.SimpleGameState
import com.nullplaying.model.SHARED_PLAYER_MAX_ACTIVE_TRAITS
import com.nullplaying.model.toAdventureRelationshipBattleParticipantSnapshot

/**
 * Deterministic adapter for a relationship duel. The arena turn engine and its 218dp combat card
 * are reused, but tickets, rating, arena level growth, support skills, traits and skill-tree ranks
 * never enter this boundary.
 */
object AdventureRelationshipBattleEngine {
    const val PLAYBACK_MILLIS = 15_000L
    const val RULES_VERSION = "relationship-level-skills-power-v2"

    data class Resolution(
        val user: ArenaSupportInput,
        val opponent: ArenaSupportInput,
        val simulation: ArenaSupportResult,
        val outcome: BattleOutcome,
    )

    fun snapshotFromState(
        state: SimpleGameState,
        combatPower: Long = 0L,
    ): AdventureRelationshipBattleParticipantSnapshot = AdventureRelationshipBattleParticipantSnapshot(
        characterId = state.rankingCharacterId,
        displayName = state.hero.name,
        heroClass = state.hero.heroClass,
        level = state.hero.level,
        combatPower = combatPower.takeIf { it > 0L } ?: levelAverageCombatPower(state.hero.level),
        stats = state.hero.stats.copy(),
        learnedSkills = state.skills.map { skill ->
            AdventureRelationshipSkillSnapshot(
                catalogId = skill.catalogId,
                displayName = skill.name,
                level = skill.level,
                usageCount = skill.boundedUsageCount,
            )
        },
        equipment = state.equipment.map { item ->
            AdventureRelationshipEquipmentSnapshot(
                slot = item.slot,
                displayName = item.name,
                power = item.power,
                rarity = item.rarity,
            )
        },
        adventureTraitIds = state.adventureTraits.owned.map { it.traitId }.distinct(),
    )

    fun snapshotFromCandidate(
        candidate: AdventureEncounterCandidate,
    ): AdventureRelationshipBattleParticipantSnapshot? {
        if (candidate.stats == null) return null
        val skills = PublicPlayerBattleDerivation
            .learnedSkillsForClassAndLevel(candidate.heroClass, candidate.level)
            .map { it.toRelationshipSnapshot() }
        return AdventureRelationshipBattleParticipantSnapshot(
            characterId = candidate.characterId,
            displayName = candidate.displayName,
            heroClass = candidate.heroClass,
            level = candidate.level,
            combatPower = candidate.combatPower,
            stats = candidate.stats.copy(),
            learnedSkills = skills,
            // Real equipment is not part of the public contract. Aggregate power is converted
            // into a bounded level-relative correction by [PublicPlayerBattleDerivation].
            equipment = emptyList(),
            adventureTraitIds = candidate.adventureTraitIds,
        ).takeIf(::hasCompletePublicBattleContract)
    }

    /** Resolves the compact encounter identity against the persisted daily public roster. */
    fun snapshotFromCandidate(
        state: SimpleGameState,
        candidate: AdventureEncounterCandidate,
    ): AdventureRelationshipBattleParticipantSnapshot? {
        val publicSnapshot = state.publicPlayerRoster?.snapshotFor(candidate.characterId)
        if (publicSnapshot != null) {
            val derived = PublicPlayerBattleDerivation.derive(publicSnapshot) ?: return null
            return publicSnapshot.toAdventureRelationshipBattleParticipantSnapshot()
                .copy(
                    learnedSkills = derived.learnedSkills.map { it.toRelationshipSnapshot() },
                    equipment = emptyList(),
                )
                .takeIf(::hasCompletePublicBattleContract)
        }
        return snapshotFromCandidate(candidate)
    }

    /** Freezes both sides before any visible incident or battle playback begins. */
    fun freezeParticipants(
        state: SimpleGameState,
        run: AdventureRelationshipRun,
        combatPower: Long = 0L,
    ): AdventureRelationshipRun? {
        if (run.battleKind == AdventureRelationshipBattleKind.NONE) return run
        val local = snapshotFromState(state, combatPower)
        val opponent = snapshotFromCandidate(state, run.candidate) ?: return null
        if (!hasCompletePublicBattleContract(local) || local.characterId == opponent.characterId) return null
        return run.copy(
            localBattleSnapshot = local,
            opponentBattleSnapshot = opponent,
            battleSeed = run.battleSeed.takeIf { it != 0L } ?: stableBattleSeed(run),
        )
    }

    fun simulate(run: AdventureRelationshipRun): Resolution? {
        if (run.battleKind == AdventureRelationshipBattleKind.NONE) return null
        val leftSnapshot = run.localBattleSnapshot ?: return null
        val rightSnapshot = run.opponentBattleSnapshot ?: return null
        val left = toArenaInput(leftSnapshot) ?: return null
        val right = toArenaInput(rightSnapshot) ?: return null
        if (left.fighter.id == right.fighter.id) return null
        val simulation = runCatching {
            ArenaSupportTurnEngine.simulate(
                left = left,
                right = right,
                seed = run.battleSeed,
                recordEvents = true,
            )
        }.getOrNull() ?: return null
        if (simulation.status != ArenaRunStatus.COMPLETED) return null
        val leftResult = simulation.fighters[left.fighter.id] ?: return null
        val rightResult = simulation.fighters[right.fighter.id] ?: return null
        val outcome = when {
            simulation.winnerId == left.fighter.id && leftResult.hp > 0.0 && rightResult.hp == 0.0 ->
                BattleOutcome.USER_WIN
            simulation.winnerId == right.fighter.id && rightResult.hp > 0.0 && leftResult.hp == 0.0 ->
                BattleOutcome.USER_LOSS
            simulation.winnerId == null && leftResult.hp == 0.0 && rightResult.hp == 0.0 ->
                BattleOutcome.DRAW
            else -> return null
        }
        return Resolution(left, right, simulation, outcome)
    }

    internal fun hasCompletePublicBattleContract(
        snapshot: AdventureRelationshipBattleParticipantSnapshot,
    ): Boolean {
        val stats = snapshot.stats ?: return false
        if (snapshot.characterId.isBlank() || snapshot.displayName.isBlank() || snapshot.level < 10L ||
            snapshot.combatPower <= 0L
        ) return false
        if (stats.values().any { it < 0L } || stats.maxHealth <= 0L || stats.maxMana < 0L) return false
        if (snapshot.learnedSkills.size > 20 || snapshot.learnedSkills.map { it.catalogId }.distinct().size != snapshot.learnedSkills.size) return false
        if (!hasValidAdventureTraits(snapshot.adventureTraitIds)) return false
        if (PublicPlayerBattleDerivation.deriveStats(
                snapshot.heroClass,
                snapshot.level,
                snapshot.combatPower,
                stats,
            ) == null
        ) return false
        return validatedAttacks(snapshot) != null
    }

    internal fun toArenaInput(
        snapshot: AdventureRelationshipBattleParticipantSnapshot,
    ): ArenaSupportInput? {
        if (!hasCompletePublicBattleContractWithoutAttackRecursion(snapshot)) return null
        val rawStats = snapshot.stats ?: return null
        val stats = PublicPlayerBattleDerivation.deriveStats(
            snapshot.heroClass,
            snapshot.level,
            snapshot.combatPower,
            rawStats,
        ) ?: return null
        val attacks = validatedAttacks(snapshot) ?: return null
        return ArenaSupportInput(
            fighter = ArenaFighterInput(
                id = snapshot.characterId,
                heroClass = snapshot.heroClass,
                level = snapshot.level,
                stats = ArenaCoreStats(
                    strength = stats.strength.toDouble(),
                    constitution = stats.constitution.toDouble(),
                    dexterity = stats.dexterity.toDouble(),
                    intelligence = stats.intelligence.toDouble(),
                    wisdom = stats.wisdom.toDouble(),
                    charisma = stats.charisma.toDouble(),
                    rawMaxHealth = stats.maxHealth.toDouble(),
                    rawMaxMana = stats.maxMana.toDouble(),
                ),
                attacks = attacks,
            ),
            supportIds = emptySet(),
            traits = emptyList(),
            arenaLevel = 1,
            supportRanks = emptyMap(),
            resolvedSupports = emptyMap(),
        )
    }

    private fun hasCompletePublicBattleContractWithoutAttackRecursion(
        snapshot: AdventureRelationshipBattleParticipantSnapshot,
    ): Boolean {
        val stats = snapshot.stats ?: return false
        return snapshot.characterId.isNotBlank() && snapshot.displayName.isNotBlank() && snapshot.level >= 10L &&
            snapshot.combatPower > 0L &&
            stats.values().all { it >= 0L } && stats.maxHealth > 0L && stats.maxMana >= 0L &&
            snapshot.learnedSkills.size <= 20 &&
            snapshot.learnedSkills.map { it.catalogId }.distinct().size == snapshot.learnedSkills.size &&
            PublicPlayerBattleDerivation.deriveStats(
                snapshot.heroClass,
                snapshot.level,
                snapshot.combatPower,
                stats,
            ) != null &&
            hasValidAdventureTraits(snapshot.adventureTraitIds)
    }

    private fun hasValidAdventureTraits(ids: List<String>): Boolean {
        if (ids.size > SHARED_PLAYER_MAX_ACTIVE_TRAITS || ids.distinct().size != ids.size) return false
        val definitions = ids.map { AdventureTraitCatalog.find(it) ?: return false }
        val owned = ids.toSet()
        return definitions.none { it.oppositeId.isNotBlank() && it.oppositeId in owned }
    }

    private fun validatedAttacks(
        snapshot: AdventureRelationshipBattleParticipantSnapshot,
    ): List<ArenaAttackInput>? {
        val attacks = mutableListOf<ArenaAttackInput>()
        snapshot.learnedSkills.forEach { skill ->
            if (skill.catalogId.isBlank() || skill.level !in 1L..LearnedSkill.MAX_LEVEL ||
                skill.usageCount !in 0L..LearnedSkill.MAX_USAGE_COUNT
            ) return null
            val definition = SkillCatalog.find(skill.catalogId) ?: return null
            if (definition.heroClass != snapshot.heroClass || definition.unlockLevel.toLong() > snapshot.level) return null
            val learned = LearnedSkill(
                id = attacks.size + 1,
                name = definition.name,
                acquiredAtLevel = definition.unlockLevel.toLong(),
                description = definition.description,
                catalogId = definition.catalogId,
                usageCount = skill.usageCount,
            )
            if (learned.level != skill.level) return null
            attacks += ArenaAttackInput(
                id = definition.catalogId,
                name = definition.name,
                tier = if (definition.unlockLevel == 1) 1 else definition.unlockLevel / 5 + 1,
                masteryBonusPercent = learned.damageBonusPercent.toInt(),
                sourceDamagePercentMin = definition.damagePercentMin,
                sourceDamagePercentMax = definition.damagePercentMax,
            )
        }
        return attacks
    }

    private fun stableBattleSeed(run: AdventureRelationshipRun): Long {
        var value = run.rewardSeed xor run.sequence.rotateLeft(17) xor run.candidate.characterId.hashCode().toLong()
        value = (value xor (value ushr 30)) * -4_658_895_280_553_007_687L
        value = (value xor (value ushr 27)) * -7_723_592_293_110_705_685L
        return (value xor (value ushr 31)).let { if (it == 0L) 1L else it }
    }

    private fun LearnedSkill.toRelationshipSnapshot() = AdventureRelationshipSkillSnapshot(
        catalogId = catalogId,
        displayName = name,
        level = level,
        usageCount = boundedUsageCount,
    )

    private fun levelAverageCombatPower(level: Long): Long =
        (1L + (level.coerceAtLeast(1L) - 1L) * 5L) * 2L
}
