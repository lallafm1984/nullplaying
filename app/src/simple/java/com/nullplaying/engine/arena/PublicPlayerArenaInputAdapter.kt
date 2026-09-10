package com.nullplaying.engine.arena

import com.nullplaying.engine.PublicPlayerBattleDerivation
import com.nullplaying.engine.SkillCatalog
import com.nullplaying.engine.SkillElement
import com.nullplaying.model.BattleBuildStats
import com.nullplaying.model.BattleCondition
import com.nullplaying.model.BattleGuidance
import com.nullplaying.model.BattleHeroClass
import com.nullplaying.model.BattleProjectionSnapshot
import com.nullplaying.model.BattleSkillKind
import com.nullplaying.model.BattleSkillSnapshot
import com.nullplaying.model.HeroClass
import com.nullplaying.model.HeroStats
import com.nullplaying.model.LearnedSkill
import com.nullplaying.model.PublicPlayerRoster
import com.nullplaying.model.PublicPlayerSnapshot
import com.nullplaying.model.SHARED_PLAYER_MAX_LEVEL
import com.nullplaying.model.SHARED_PLAYER_MIN_LEVEL
import com.nullplaying.model.SHARED_PLAYER_RULES_VERSION
import com.nullplaying.model.SHARED_PLAYER_SNAPSHOT_VERSION
import com.nullplaying.remote.isValidPublicLabel
import com.nullplaying.remote.maximumAcceptedRankingCombatPower
import java.util.UUID

/** Existing arena contracts built from one server-selected public projection. */
data class PublicPlayerArenaMatchInput(
    val combat: ArenaSupportInput,
    val projection: BattleProjectionSnapshot,
    /** QA/audit identity for the authored local build family; never accepted from the server. */
    val autoBuildPresetId: String = "",
)

/** Pure V6 adapter. It reads no Android state and performs no remote or persistence work. */
object PublicPlayerArenaInputAdapter {
    fun fromRoster(
        roster: PublicPlayerRoster,
        projectionId: String,
        nowEpochMillis: Long,
        arenaLevel: Int = 1,
        stableSeed: Long = arenaAutoBuildAllocationSeed(projectionId),
    ): PublicPlayerArenaMatchInput? {
        if (!roster.isReusableFor(
                roster.requesterCharacterId,
                roster.requesterLevel,
                nowEpochMillis,
            )
        ) return null
        return roster.snapshotFor(projectionId)?.let { snapshot ->
            fromSnapshot(snapshot, arenaLevel, stableSeed, roster.receivedAtEpochMillis)
        }
    }

    fun fromSnapshot(
        snapshot: PublicPlayerSnapshot,
        arenaLevel: Int = 1,
        stableSeed: Long = arenaAutoBuildAllocationSeed(snapshot.projectionId),
    ): PublicPlayerArenaMatchInput? =
        fromSnapshot(snapshot, arenaLevel, stableSeed, issuedAtMillis = 0L)

    internal fun fromRosterSnapshot(
        snapshot: PublicPlayerSnapshot,
        arenaLevel: Int,
        stableSeed: Long,
        receivedAtEpochMillis: Long,
    ): PublicPlayerArenaMatchInput? =
        fromSnapshot(snapshot, arenaLevel, stableSeed, receivedAtEpochMillis)

    private fun fromSnapshot(
        snapshot: PublicPlayerSnapshot,
        arenaLevel: Int,
        stableSeed: Long,
        issuedAtMillis: Long,
    ): PublicPlayerArenaMatchInput? {
        if (!snapshot.hasValidArenaIdentity() || arenaLevel !in 1..ArenaSkillTreeRules.maxArenaLevel) {
            return null
        }
        val derived = PublicPlayerBattleDerivation.derive(snapshot) ?: return null
        return ArenaV6OpponentInputFactory.create(
            projectionId = snapshot.projectionId,
            displayName = snapshot.displayName,
            heroClass = snapshot.heroClass,
            level = snapshot.level,
            combatPower = snapshot.combatPower,
            stats = derived.stats,
            learnedSkills = derived.learnedSkills,
            arenaLevel = arenaLevel,
            stableSeed = stableSeed,
            issuedAtMillis = issuedAtMillis,
        )
    }

    private fun PublicPlayerSnapshot.hasValidArenaIdentity(): Boolean {
        val canonicalId = runCatching { UUID.fromString(projectionId) }.getOrNull() ?: return false
        return canonicalId.toString().equals(projectionId, ignoreCase = true) &&
            displayName.isValidPublicLabel(24) &&
            level in SHARED_PLAYER_MIN_LEVEL..SHARED_PLAYER_MAX_LEVEL &&
            combatPower in 1L..maximumAcceptedRankingCombatPower(level) &&
            rulesVersion == SHARED_PLAYER_RULES_VERSION &&
            snapshotVersion == SHARED_PLAYER_SNAPSHOT_VERSION
    }

}

/**
 * Shared V6 construction after trust-boundary-specific validation and stat derivation.
 * Public snapshots and authored local reserves enter through separate adapters; neither path can
 * provide equipment, skill mastery, support ownership, or arena allocations.
 */
internal object ArenaV6OpponentInputFactory {
    fun create(
        projectionId: String,
        displayName: String,
        heroClass: HeroClass,
        level: Long,
        combatPower: Long,
        stats: HeroStats,
        learnedSkills: List<LearnedSkill>,
        arenaLevel: Int,
        stableSeed: Long,
        buildPreset: ArenaAutoBuildPreset = ArenaAutoBuildPreset.fromSeed(
            arenaAutoBuildPresetIdentitySeed(projectionId),
        ),
        issuedAtMillis: Long = 0L,
    ): PublicPlayerArenaMatchInput? {
        if (level < 1L || arenaLevel !in 1..ArenaSkillTreeRules.maxArenaLevel ||
            learnedSkills.isEmpty()
        ) {
            return null
        }
        // Ownership comes only from the validated profile's class and level. Adventure mastery is
        // discarded; the V6 arena tree below is the sole source of attack and support ranks.
        val ownedAttacks = learnedSkills.mapNotNull { learned ->
            val definition = SkillCatalog.find(learned.catalogId) ?: return@mapNotNull null
            if (definition.heroClass != heroClass ||
                definition.unlockLevel.toLong() > level
            ) return@mapNotNull null
            ArenaAttackInput(
                id = definition.catalogId,
                name = definition.name,
                tier = if (definition.unlockLevel == 1) 1 else definition.unlockLevel / 5 + 1,
                masteryBonusPercent = 0,
                sourceDamagePercentMin = 0,
                sourceDamagePercentMax = 0,
            )
        }
        if (ownedAttacks.size != learnedSkills.size || ownedAttacks.isEmpty()) return null
        val ownedById = ownedAttacks.associateBy(ArenaAttackInput::id)
        val tree = runCatching {
            ArenaSkillTreeRules.autoAllocateWithPreset(
                heroClass = heroClass,
                arenaLevel = arenaLevel,
                ownedAttackIds = ownedById.keys,
                seed = stableSeed,
                preset = buildPreset,
            )
        }.getOrNull() ?: return null
        val spentPoints = ArenaSkillTreeRules.spentPoints(tree)
        if (spentPoints != arenaLevel) {
            // Arena progression and hero skill ownership advance independently. A lower-level
            // projection may exhaust every owned route before spending a high Arena budget. Keep
            // that legal maximal build instead of making the whole opponent pool unavailable;
            // never fill the gap by fabricating an unowned attack.
            val exhausted = ArenaSkillTreeRules.view(
                tree, arenaLevel, ownedById.keys,
            ).nodes.none(ArenaSkillTreeNodeView::canAllocate)
            if (!exhausted) return null
        }
        val ranks = tree.allocations.associate { it.nodeId to it.rank }
        val resolvedAttacks = ArenaSkillTreeCatalog.forClass(heroClass)
            .asSequence()
            .filter { it.kind == ArenaSkillNodeKind.ATTACK }
            .mapNotNull { node ->
                val rank = ranks[node.id] ?: return@mapNotNull null
                val owned = ownedById[node.id] ?: return@mapNotNull null
                runCatching {
                    ArenaTurnInputAdapter.resolveAttack(owned, heroClass, rank)
                }.getOrNull()
            }
            .toList()
        if (resolvedAttacks.isEmpty()) return null
        val supportRanks = ranks.filterKeys { nodeId ->
            ArenaSkillTreeCatalog.find(nodeId)?.kind == ArenaSkillNodeKind.SUPPORT
        }
        val fighter = ArenaFighterInput(
            id = projectionId,
            heroClass = heroClass,
            level = level,
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
            attacks = resolvedAttacks,
        )
        val combat = ArenaSupportInput(
            fighter = fighter,
            supportIds = supportRanks.keys,
            traits = emptyList(),
            arenaLevel = arenaLevel,
            arenaClassBalanceEnabled = true,
            supportRanks = supportRanks,
        ).freezeResolvedSupports()
        return PublicPlayerArenaMatchInput(
            combat = combat,
            projection = BattleProjectionSnapshot(
                projectionId = projectionId,
                displayName = displayName,
                heroClass = BattleHeroClass.valueOf(heroClass.name),
                level = level,
                verifiedPower = combatPower,
                condition = BattleCondition.NORMAL,
                build = BattleBuildStats(
                    strength = stats.strength,
                    constitution = stats.constitution,
                    dexterity = stats.dexterity,
                    intelligence = stats.intelligence,
                    wisdom = stats.wisdom,
                    charisma = stats.charisma,
                ),
                guidance = BattleGuidance.BALANCED,
                skills = resolvedAttacks.map { attack -> attack.toBattleSkill(heroClass) },
                // Equipment, skill mastery, support ownership and arena allocations are never
                // accepted from the server. The latter three are reconstructed above.
                equipment = emptyList(),
                activeTraitIds = emptyList(),
                heroPathTraitRanks = emptyMap(),
                snapshotVersion = ARENA_V6_PROJECTION_VERSION,
                issuedAtMillis = issuedAtMillis.coerceAtLeast(0L),
            ),
            autoBuildPresetId = buildPreset.stableId,
        )
    }

    private fun ArenaAttackInput.toBattleSkill(heroClass: HeroClass): BattleSkillSnapshot {
        val definition = requireNotNull(SkillCatalog.find(id))
        require(definition.heroClass == heroClass)
        val kind = if (definition.element == SkillElement.PHYSICAL) {
            BattleSkillKind.STRIKE
        } else {
            BattleSkillKind.ARCANE
        }
        val resolved = requireNotNull(arena)
        return BattleSkillSnapshot(
            skillId = id,
            displayName = name,
            kind = kind,
            powerBasisPoints = (resolved.damagePercent.toLong() * 100L)
                .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
            cooldownRounds = resolved.cooldownTurns,
            masteryLevel = 0,
            finisherEligible = true,
        )
    }

    private const val ARENA_V6_PROJECTION_VERSION = 6
}
