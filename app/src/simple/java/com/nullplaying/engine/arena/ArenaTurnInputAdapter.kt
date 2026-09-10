package com.nullplaying.engine.arena

import com.nullplaying.engine.SkillCatalog
import com.nullplaying.engine.PublicPlayerBattleDerivation
import com.nullplaying.model.HeroClass
import com.nullplaying.model.LearnedSkill
import com.nullplaying.model.SimpleGameState

/**
 * Builds an arena snapshot without changing or supplementing the saved skill list.
 * Invalid fighter IDs, levels, or stats are rejected by the input model invariants.
 */
object ArenaTurnInputAdapter {
    /** Canonical owned IDs only. The returned set never exposes mastery or damage values. */
    fun ownedAttackIds(state: SimpleGameState): Set<String> =
        fromState(state, "arena-ownership").fighter.attacks.mapTo(linkedSetOf()) { it.id }

    /**
     * V6 battle snapshot. [attackRanks] is the already validated arena allocation and is the
     * only source of combat power; adventure usage/mastery and source damage are discarded.
     */
    fun fromState(
        state: SimpleGameState,
        id: String,
        attackRanks: Map<String, Int>,
    ): ArenaInputBuildResult {
        return resolveAttackRanks(fromState(state, id), state.hero.heroClass, attackRanks)
    }

    /**
     * Applies the same bounded aggregate-power correction used for a public projection while
     * retaining only the local character's real attack ownership. Equipment and mastery are not
     * copied into the Arena skill-tree contract.
     */
    fun fromStateForCombatPower(
        state: SimpleGameState,
        id: String,
        effectiveCombatPower: Long,
        attackRanks: Map<String, Int>? = null,
        allowLowLevelQa: Boolean = false,
    ): ArenaInputBuildResult? {
        val source = fromState(state, id)
        val derivedStats = PublicPlayerBattleDerivation.deriveStats(
            heroClass = state.hero.heroClass,
            level = state.hero.level,
            combatPower = effectiveCombatPower,
            rawStats = state.hero.stats,
            allowLowLevelQa = allowLowLevelQa,
        ) ?: return null
        val normalized = source.copy(
            fighter = source.fighter.copy(
                stats = derivedStats.toArenaCoreStats(),
                // Main-game usage mastery is deliberately outside the Arena wire contract.
                // Ranked skill-tree resolution below also clears the catalog source ranges.
                attacks = source.fighter.attacks.map { attack ->
                    attack.copy(masteryBonusPercent = 0)
                },
            ),
        )
        return if (attackRanks == null) normalized else {
            resolveAttackRanks(normalized, state.hero.heroClass, attackRanks)
        }
    }

    private fun resolveAttackRanks(
        source: ArenaInputBuildResult,
        heroClass: HeroClass,
        attackRanks: Map<String, Int>,
    ): ArenaInputBuildResult {
        val ownedById = source.fighter.attacks.associateBy(ArenaAttackInput::id)
        val rejected = source.rejectedSkills.toMutableList()
        val attacks = ArenaSkillTreeCatalog.forClass(heroClass)
            .filter { it.kind == ArenaSkillNodeKind.ATTACK }
            .mapNotNull { node ->
                val rank = attackRanks[node.id] ?: return@mapNotNull null
                if (rank !in 1..ARENA_SKILL_TREE_MAX_RANK) {
                    rejected += "arena_rank[catalogId=${node.id}]: rank $rank is outside 1..10"
                    return@mapNotNull null
                }
                val owned = ownedById[node.id]
                if (owned == null) {
                    rejected += "arena_unowned[catalogId=${node.id}]: attack is not owned in the main game"
                    return@mapNotNull null
                }
                resolveAttack(owned, heroClass, rank)
            }
        return ArenaInputBuildResult(
            fighter = source.fighter.copy(attacks = attacks),
            rejectedSkills = rejected,
        )
    }

    private fun com.nullplaying.model.HeroStats.toArenaCoreStats() = ArenaCoreStats(
        strength = strength.toDouble(),
        constitution = constitution.toDouble(),
        dexterity = dexterity.toDouble(),
        intelligence = intelligence.toDouble(),
        wisdom = wisdom.toDouble(),
        charisma = charisma.toDouble(),
        rawMaxHealth = maxHealth.toDouble(),
        rawMaxMana = maxMana.toDouble(),
    )

    /**
     * Canonical V6 resolver shared by player and NPC issuance. The returned fields are the
     * complete frozen combat contract; replay never derives timing or cost from [rank].
     */
    fun resolveAttack(
        owned: ArenaAttackInput,
        heroClass: HeroClass,
        rank: Int,
    ): ArenaAttackInput {
        require(rank in 1..ARENA_SKILL_TREE_MAX_RANK) { "Arena attack rank must be in 1..10" }
        val node = requireNotNull(ArenaSkillTreeCatalog.find(owned.id)) {
            "Arena attack is absent from the skill-tree catalog: ${owned.id}"
        }
        require(node.kind == ArenaSkillNodeKind.ATTACK && node.heroClass == heroClass &&
            node.sourceAttackId == owned.id) {
            "Arena attack does not belong to $heroClass: ${owned.id}"
        }
        val profile = requireNotNull(node.attackProfile)
        val effect = profile.effectFor(heroClass)
        val values = effect.parameters.associate { it.key to it.value(rank) }.toSortedMap()
        return owned.copy(
            masteryBonusPercent = 0,
            sourceDamagePercentMin = 0,
            sourceDamagePercentMax = 0,
            arena = ArenaResolvedAttack(
                rank = rank,
                prepareTurns = profile.prepareTurns,
                mpCost = profile.effectiveMpCost(rank),
                cooldownTurns = profile.effectiveCooldownTurns(rank),
                damagePercent = profile.damagePercent(heroClass, rank),
                effectKey = effect.kind.name,
                effectValues = values,
                durationTurns = effect.durationTurns,
                maxApplications = effect.charges,
                oncePerBattle = profile.oncePerBattle,
                earliestTurn = profile.earliestTurn,
                hpDamageCapPercent = profile.effectiveMaxHpDamageCapPercent(rank).toDouble(),
                targetHpFloor = values["minimumHp"]?.toInt() ?: 0,
                tags = setOf(profile.profileId, effect.kind.name),
            ),
        )
    }

    fun fromState(state: SimpleGameState, id: String): ArenaInputBuildResult {
        val hero = state.hero
        val sourceStats = hero.stats
        val classDefinitions = SkillCatalog.forClass(hero.heroClass)
        val attacks = mutableListOf<ArenaAttackInput>()
        val rejected = mutableListOf<String>()
        val acceptedIds = mutableSetOf<String>()

        state.skills.forEachIndexed { index, learned ->
            val definition = if (learned.catalogId.isBlank()) {
                // A legacy name must be an exact, unique match in this class.
                // Do not trim names, strip suffixes, or infer a tier from learned.id.
                val matches = classDefinitions.filter { it.name == learned.name }
                if (matches.size != 1) {
                    rejected += rejection(
                        reason = "unknown",
                        index = index,
                        learned = learned,
                        detail = if (matches.isEmpty()) {
                            "legacy name has no exact match in ${hero.heroClass.name}"
                        } else {
                            "legacy name is ambiguous in ${hero.heroClass.name}"
                        },
                    )
                    return@forEachIndexed
                }
                matches.single()
            } else {
                // A present but invalid ID must not silently fall back to its name.
                val resolved = SkillCatalog.find(learned.catalogId)
                if (resolved == null) {
                    rejected += rejection("unknown", index, learned, "catalog ID not found")
                    return@forEachIndexed
                }
                resolved
            }

            if (definition.heroClass != hero.heroClass) {
                rejected += rejection(
                    "foreign",
                    index,
                    learned,
                    "belongs to ${definition.heroClass.name}, not ${hero.heroClass.name}",
                )
                return@forEachIndexed
            }
            if (definition.unlockLevel.toLong() > hero.level) {
                rejected += rejection(
                    "locked",
                    index,
                    learned,
                    "requires level ${definition.unlockLevel}; hero level is ${hero.level}",
                )
                return@forEachIndexed
            }
            if (!acceptedIds.add(definition.catalogId)) {
                rejected += rejection(
                    "duplicate",
                    index,
                    learned,
                    "resolved catalog ID ${definition.catalogId} was already accepted",
                )
                return@forEachIndexed
            }

            attacks += ArenaAttackInput(
                id = definition.catalogId,
                name = definition.name,
                tier = if (definition.unlockLevel == 1) 1 else definition.unlockLevel / 5 + 1,
                masteryBonusPercent = learned.damageBonusPercent.coerceIn(0L, 50L).toInt(),
                sourceDamagePercentMin = definition.damagePercentMin,
                sourceDamagePercentMax = definition.damagePercentMax,
            )
        }

        return ArenaInputBuildResult(
            fighter = ArenaFighterInput(
                id = id,
                heroClass = hero.heroClass,
                level = hero.level,
                stats = ArenaCoreStats(
                    strength = sourceStats.strength.toDouble(),
                    constitution = sourceStats.constitution.toDouble(),
                    dexterity = sourceStats.dexterity.toDouble(),
                    intelligence = sourceStats.intelligence.toDouble(),
                    wisdom = sourceStats.wisdom.toDouble(),
                    charisma = sourceStats.charisma.toDouble(),
                    rawMaxHealth = sourceStats.maxHealth.toDouble(),
                    rawMaxMana = sourceStats.maxMana.toDouble(),
                ),
                attacks = attacks.toList(),
            ),
            rejectedSkills = rejected.toList(),
        )
    }

    private fun rejection(
        reason: String,
        index: Int,
        learned: LearnedSkill,
        detail: String,
    ): String = "$reason[index=$index, catalogId=${learned.catalogId.ifBlank { "<legacy>" }}, " +
        "name=${learned.name}]: $detail"
}
