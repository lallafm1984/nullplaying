package com.nullplaying.engine

import com.nullplaying.model.AdventureEncounterCandidate
import com.nullplaying.model.AdventureEventItemReward
import com.nullplaying.model.AdventureEventOutcome
import com.nullplaying.model.AdventureEventRewardKind
import com.nullplaying.model.AdventureEventStat
import com.nullplaying.model.AdventureRelationshipBattleKind
import com.nullplaying.model.AdventureRelationshipBattleParticipantSnapshot
import com.nullplaying.model.AdventureRelationshipEquipmentSnapshot
import com.nullplaying.model.AdventureRelationshipContact
import com.nullplaying.model.AdventureRelationshipResult
import com.nullplaying.model.AdventureRelationshipRun
import com.nullplaying.model.AdventureRelationshipSkillSnapshot
import com.nullplaying.model.AdventureRelationshipState
import com.nullplaying.model.AdventureRelationshipTier
import com.nullplaying.model.BattleOutcome
import com.nullplaying.model.HeroStats
import com.nullplaying.model.MonsterGrade
import com.nullplaying.model.SimpleGameState
import com.nullplaying.model.TaleKind

data class AdventureRelationshipApproach(
    val id: String,
    val title: AdventureText,
    val primaryStat: AdventureEventStat,
    val secondaryStat: AdventureEventStat,
    val affinityBias: Int,
    val behaviorSignals: Set<AdventureBehaviorSignal> = emptySet(),
)

enum class AdventureRelationshipReunionRule { ANY, FIRST_MEETING_ONLY, REUNION_ONLY }

data class AdventureRelationshipRewardWeights(
    val experience: Int,
    val gold: Int,
    val item: Int,
) {
    init {
        require(listOf(experience, gold, item).all { it in 0..100 })
        require(experience + gold + item == 100)
    }
}

/** One local economic distribution shared by every relationship scene and relationship tier. */
internal val FIXED_RELATIONSHIP_ECONOMIC_REWARD_WEIGHTS =
    AdventureRelationshipRewardWeights(experience = 60, gold = 35, item = 5)

data class AdventureRelationshipBattleRule(
    val kind: AdventureRelationshipBattleKind,
    val triggerOutcomes: Set<AdventureEventOutcome>,
    val grade: MonsterGrade = MonsterGrade.NORMAL,
)

data class AdventureRelationshipDefinition(
    val id: String,
    val title: AdventureText,
    val scene: AdventureText,
    val approaches: List<AdventureRelationshipApproach>,
    val success: AdventureText,
    val partial: AdventureText,
    val failure: AdventureText,
    val rewardWeights: AdventureRelationshipRewardWeights,
    val reunionRule: AdventureRelationshipReunionRule = AdventureRelationshipReunionRule.ANY,
    val allowedTiers: Set<AdventureRelationshipTier> = AdventureRelationshipTier.entries.toSet(),
    val battleRule: AdventureRelationshipBattleRule? = null,
)

/** Local memories of an automatic adventure scene, never assertions about remote player activity. */
object AdventureRelationshipEngine {
    const val DISCOVERY_MILLIS = 5_000L
    const val DECISION_MILLIS = 5_000L
    const val ACTION_MILLIS = DISCOVERY_MILLIS + DECISION_MILLIS
    const val RESULT_MILLIS = 5_000L
    const val MIN_HERO_LEVEL = 10L
    const val MIN_INTERVAL_MILLIS = 4L * 60L * 60_000L
    const val MAX_INTERVAL_MILLIS = 8L * 60L * 60_000L
    const val REUNION_COOLDOWN_MILLIS = 12L * 60L * 60_000L
    const val MAX_CONTACTS = 100
    const val MAX_MEMORIES_PER_CONTACT = 16
    const val HISTORY_LIMIT = 32
    const val RECENT_SCENE_LIMIT = 8
    const val REUNION_PREFERENCE_PERCENT = 35
    internal const val TRAIT_INFLUENCE_BASIS_POINTS = 2_000
    private const val FIXED_EXPERIENCE_PERCENT = 65L
    private const val FIXED_GOLD_PER_LEVEL = 3L
    private const val SEED_DOMAIN = 0x237B_9A61_581D_43EFL
    private const val OUTCOME_DOMAIN = 0x357A_16D2_49EB_6021L
    private const val AFFINITY_DOMAIN = 0x461C_730E_295A_18B9L
    private const val REWARD_DOMAIN = 0x79A1_324D_615B_0CE7L
    private const val BATTLE_DOMAIN = 0x624E_1F93_7AC5_20D9L
    private const val GOLDEN_GAMMA = -7_046_029_254_386_353_131L

    val all: List<AdventureRelationshipDefinition> = AdventureRelationshipCatalog.all
    fun definition(id: String): AdventureRelationshipDefinition = AdventureRelationshipCatalog.definition(id)

    fun initialize(state: SimpleGameState, at: Long) {
        val relationships = state.adventureRelationships
        if (relationships.initialized) return
        val rng = RelationshipRng(mix(state.skillCatalogSeed xor state.rngState xor SEED_DOMAIN xor
            state.rankingCharacterId.hashCode().toLong()))
        relationships.initialized = true
        relationships.initializedAt = at
        relationships.nextEncounterAt = plus(at, interval(rng))
        relationships.rngState = rng.state
    }

    fun activeMillisAt(relationships: AdventureRelationshipState, at: Long): Long =
        (at - relationships.initializedAt).coerceAtLeast(0L)
            .let { (it - relationships.pausedMillis).coerceAtLeast(0L) }

    /** Wall-clock roster validity is intentionally not adjusted when the adventure pauses. */
    fun pause(relationships: AdventureRelationshipState, pausedMillis: Long) {
        if (!relationships.initialized || pausedMillis <= 0L) return
        relationships.pausedMillis = plus(relationships.pausedMillis, pausedMillis)
        relationships.nextEncounterAt = plus(relationships.nextEncounterAt, pausedMillis)
        relationships.pending = relationships.pending?.let { it.copy(startedAt = plus(it.startedAt, pausedMillis)) }
    }

    fun eligibleCandidates(state: SimpleGameState, eventAt: Long): List<AdventureEncounterCandidate> {
        val relationships = state.adventureRelationships
        if (state.hero.level < MIN_HERO_LEVEL) return emptyList()
        val roster = relationships.roster ?: return emptyList()
        if (roster.snapshotId.isBlank() || eventAt < roster.receivedAt || eventAt >= roster.validUntil) return emptyList()
        val activeAt = activeMillisAt(relationships, eventAt)
        val contacts = relationships.contacts.associateBy { it.characterId }
        val level = state.hero.level.coerceAtLeast(1L)
        return roster.candidates.asSequence()
            .filter { candidate ->
                val contact = contacts[candidate.characterId]
                candidate.characterId.isNotBlank() && candidate.characterId != state.rankingCharacterId &&
                    candidate.displayName.isNotBlank() && candidate.level >= 1L && candidate.combatPower > 0L &&
                    candidate.level in (level - 1L).coerceAtLeast(1L)..plus(level, 1L) &&
                    (contact != null || contacts.size < MAX_CONTACTS) &&
                    (contact == null || (eventAt >= contact.lastMetAt && activeAt >= contact.nextEligibleActiveMillis))
            }
            .sortedWith(compareBy<AdventureEncounterCandidate> { it.characterId }
                .thenBy { it.displayName }.thenBy { it.heroClass.name }.thenBy { it.level }.thenBy { it.combatPower })
            .distinctBy { it.characterId }.toList()
    }

    /** Returns null without spending decision RNG when locked, not due, or no valid server candidate exists. */
    fun tryBegin(
        state: SimpleGameState,
        eventAt: Long,
        localCombatPower: Long = localSnapshotPower(state),
    ): AdventureRelationshipRun? {
        initialize(state, eventAt)
        val relationships = state.adventureRelationships
        if (state.hero.level < MIN_HERO_LEVEL || relationships.pending != null ||
            eventAt < relationships.nextEncounterAt
        ) return null
        val candidates = eligibleCandidates(state, eventAt)
        if (candidates.isEmpty()) return null
        val rng = RelationshipRng(relationships.rngState)
        val knownIds = relationships.contacts.map { it.characterId }.toSet()
        val known = candidates.filter { it.characterId in knownIds }
        val pool = if (known.isNotEmpty() && rng.nextInt(100) < REUNION_PREFERENCE_PERCENT) known else candidates
        val candidate = pool[rng.nextInt(pool.size)]
        val previous = relationships.contacts.firstOrNull { it.characterId == candidate.characterId }
        val eligibleDefinitions = all.filter { relationshipDefinitionEligible(it, previous, state, candidate) }
        val freshDefinitions = eligibleDefinitions.filterNot { it.id in relationships.recentSceneIds }
        val definitionPool = freshDefinitions.ifEmpty { eligibleDefinitions }
        if (definitionPool.isEmpty()) return null
        val definition = definitionPool[rng.nextInt(definitionPool.size)]
        val sequence = plus(relationships.sequence, 1L)
        val decisionSeed = rng.nextLong()
        val influences = definition.approaches.associateWith { approach ->
            traitInfluence(state, approach, decisionSeed)
        }
        val expected = expectedStat(state.hero.level)
        val weights = definition.approaches.map { approach ->
            val influence = requireNotNull(influences[approach])
            val traitWeight = expected * influence.basisPointModifier / 2_500L
            plus(score(state.hero.stats, approach), traitWeight).coerceIn(1L, 1_000_000L).toInt()
        }
        val selection = rng.nextInt(weights.sum())
        var cumulative = 0
        val approach = definition.approaches[weights.indexOfFirst { weight ->
            cumulative += weight
            selection < cumulative
        }.coerceAtLeast(0)]
        val influence = requireNotNull(influences[approach])
        val outcomeRng = RelationshipRng(mix(decisionSeed xor OUTCOME_DOMAIN))
        val affinityRng = RelationshipRng(mix(decisionSeed xor AFFINITY_DOMAIN))
        val success = successBasisPoints(
            state.hero.stats,
            state.hero.level,
            approach,
            influence.basisPointModifier,
        )
        val partial = minOf(2_500, 9_500 - success)
        val roll = outcomeRng.nextInt(10_000)
        val outcome = when {
            roll < success -> AdventureEventOutcome.SUCCESS
            roll < success + partial -> AdventureEventOutcome.PARTIAL
            else -> AdventureEventOutcome.FAILURE
        }
        // Social results mostly follow what happened, while approach, awareness and chance preserve nuance.
        val socialAwareness = ((state.hero.stats.charisma.coerceIn(0L, 1_000_000L) +
            state.hero.stats.wisdom.coerceIn(0L, 1_000_000L)) / 2L)
        val socialAdjustment = ((socialAwareness - expected) / 10L).coerceIn(-1L, 1L).toInt()
        val outcomeAffinity = when (outcome) {
            AdventureEventOutcome.SUCCESS -> 3
            AdventureEventOutcome.PARTIAL -> 1
            AdventureEventOutcome.FAILURE -> -3
        }
        val traitAffinity = influence.basisPointModifier.compareTo(0)
        val delta = outcomeAffinity + approach.affinityBias.coerceIn(-2, 2) +
            affinityRng.nextInt(5) - 2 + socialAdjustment + traitAffinity
        val level = state.hero.level.coerceIn(1L, Long.MAX_VALUE / 1_000L)
        val depth = if (state.adventureTale.kind == TaleKind.LABYRINTH) state.adventureTale.labyrinthDepth else 0L
        val encounterLevel = plus(level, if (depth > 0L) LabyrinthProgression.monsterLevelBonus(depth) else 0L)
            .coerceAtMost(Long.MAX_VALUE / 1_000L)
        val rawExperience = plus(16L, encounterLevel * 4L)
        val baseExperience = if (depth > 0L) LabyrinthProgression.scaleCombatExperience(rawExperience, depth) else rawExperience
        val battleRule = definition.battleRule?.takeIf {
            outcome in it.triggerOutcomes && battleSnapshotComplete(state, candidate)
        }
        val rewardSeed = mix(decisionSeed xor REWARD_DOMAIN)
        // Freeze one reward from local progression inputs only. Scene, relationship tier,
        // incident outcome and projection battle outcome cannot select or scale economy.
        val reward = fixedEconomicReward(
            level,
            baseExperience,
            rewardSeed,
            state.inventory.size.toLong() < state.inventoryCapacity(),
        )
        val run = AdventureRelationshipRun(
            sequence = sequence,
            sceneId = definition.id,
            approachId = approach.id,
            candidate = candidate,
            snapshotId = requireNotNull(relationships.roster).snapshotId,
            startedAt = eventAt,
            startedActiveMillis = activeMillisAt(relationships, eventAt),
            durationMillis = plus(
                ACTION_MILLIS,
                if (battleRule == null) 0L else AdventureRelationshipBattleEngine.PLAYBACK_MILLIS,
            ),
            heroLevel = state.hero.level,
            primaryStat = approach.primaryStat,
            secondaryStat = approach.secondaryStat,
            primaryValue = AdventureEventEngine.stat(state.hero.stats, approach.primaryStat),
            secondaryValue = AdventureEventEngine.stat(state.hero.stats, approach.secondaryStat),
            successBasisPoints = success,
            partialBasisPoints = partial,
            roll = roll,
            outcome = outcome,
            scoreBefore = previous?.score ?: 0,
            scoreDelta = delta,
            experienceReward = reward.experience,
            rewardSeed = rewardSeed,
            encounterLevel = encounterLevel,
            labyrinthDepth = depth,
            baseExperienceBudget = baseExperience,
            reunion = previous != null,
            rewardKind = reward.kind,
            goldReward = reward.gold,
            itemReward = reward.item,
            battleKind = battleRule?.kind ?: AdventureRelationshipBattleKind.NONE,
            battleSeed = if (battleRule == null) 0L else mix(decisionSeed xor BATTLE_DOMAIN),
            battleDurationMillis = if (battleRule == null) 0L else AdventureRelationshipBattleEngine.PLAYBACK_MILLIS,
            localBattleSnapshot = battleRule?.let { localBattleSnapshot(state, localCombatPower) },
            opponentBattleSnapshot = battleRule?.let { opponentBattleSnapshot(state, candidate) },
            influentialTraitIds = influence.traitIds,
            traitBasisPointModifier = influence.basisPointModifier,
        )
        relationships.sequence = sequence
        relationships.rngState = rng.state
        relationships.pending = run
        relationships.recentSceneIds = (relationships.recentSceneIds + definition.id).takeLast(RECENT_SCENE_LIMIT)
        return run
    }

    /** Freezes a supplied projection result; battle simulation itself belongs to the battle adapter. */
    fun resolveBattle(run: AdventureRelationshipRun, outcome: BattleOutcome): AdventureRelationshipRun {
        require(run.battleKind != AdventureRelationshipBattleKind.NONE) { "Relationship run has no battle" }
        require(run.localBattleSnapshot != null && run.opponentBattleSnapshot != null) {
            "Relationship battle requires both frozen snapshots"
        }
        if (run.battleOutcome != null) return run
        val scoreDelta = when (run.battleKind) {
            AdventureRelationshipBattleKind.SPAR -> when (outcome) {
                BattleOutcome.USER_WIN -> 4
                BattleOutcome.USER_LOSS -> 2
                BattleOutcome.DRAW -> 3
            }
            AdventureRelationshipBattleKind.RIVALRY -> when (outcome) {
                BattleOutcome.USER_WIN -> 4
                BattleOutcome.USER_LOSS -> -2
                BattleOutcome.DRAW -> 1
            }
            AdventureRelationshipBattleKind.CONFLICT -> when (outcome) {
                BattleOutcome.USER_WIN -> -1
                BattleOutcome.USER_LOSS -> -4
                BattleOutcome.DRAW -> -2
            }
            AdventureRelationshipBattleKind.COOPERATIVE_HUNT -> when (outcome) {
                BattleOutcome.USER_WIN -> 5
                BattleOutcome.USER_LOSS -> -3
                BattleOutcome.DRAW -> 1
            }
            AdventureRelationshipBattleKind.NONE -> 0
        }
        // Older preview saves could reach battle playback before a reward was frozen. Give those
        // saves one bounded local XP reward; the battle outcome still cannot affect economy.
        val reward = if (run.rewardKind == AdventureEventRewardKind.UNSPECIFIED) {
            RelationshipReward(
                AdventureEventRewardKind.EXPERIENCE,
                percent(run.baseExperienceBudget, 50L).coerceAtLeast(1L),
                0L,
                AdventureEventItemReward.NONE,
            )
        } else {
            RelationshipReward(run.rewardKind, run.experienceReward, run.goldReward, run.itemReward)
        }
        return run.copy(
            battleOutcome = outcome,
            battleScoreDelta = scoreDelta,
            rewardKind = reward.kind,
            experienceReward = reward.experience,
            goldReward = reward.gold,
            itemReward = reward.item,
        )
    }

    /** Corrupt or unsupported public combat input must not stall the automatic adventure clock. */
    fun withoutUnplayableBattle(run: AdventureRelationshipRun): AdventureRelationshipRun {
        if (run.battleKind == AdventureRelationshipBattleKind.NONE) return run
        val fallbackReward = if (run.rewardKind == AdventureEventRewardKind.UNSPECIFIED) {
            RelationshipReward(
                AdventureEventRewardKind.EXPERIENCE,
                percent(run.baseExperienceBudget, 50L).coerceAtLeast(1L),
                0L,
                AdventureEventItemReward.NONE,
            )
        } else {
            RelationshipReward(run.rewardKind, run.experienceReward, run.goldReward, run.itemReward)
        }
        return run.copy(
            durationMillis = ACTION_MILLIS,
            rewardKind = fallbackReward.kind,
            experienceReward = fallbackReward.experience,
            goldReward = fallbackReward.gold,
            itemReward = fallbackReward.item,
            battleKind = AdventureRelationshipBattleKind.NONE,
            battleSeed = 0L,
            battleDurationMillis = 0L,
            localBattleSnapshot = null,
            opponentBattleSnapshot = null,
            battleOutcome = null,
            battleScoreDelta = 0,
        )
    }

    fun effectiveScoreDelta(run: AdventureRelationshipRun): Int =
        (run.scoreDelta.toLong() + run.battleScoreDelta.toLong()).coerceIn(-200L, 200L).toInt()

    /** Called exactly once by the main timeline after the immutable reward is granted. */
    fun complete(relationships: AdventureRelationshipState, result: AdventureRelationshipResult) {
        val run = result.run
        require(run.battleKind == AdventureRelationshipBattleKind.NONE || run.battleOutcome != null) {
            "A relationship battle must be resolved before settlement"
        }
        val storedResult = result.copy(run = run.copy(
            localBattleSnapshot = null,
            opponentBattleSnapshot = null,
        ))
        val previous = relationships.contacts.firstOrNull { it.characterId == run.candidate.characterId }
        val activeAt = activeMillisAt(relationships, result.occurredAt)
        val contact = AdventureRelationshipContact(run.candidate.characterId, result.scoreAfter,
            plus(previous?.meetings ?: 0L, 1L), previous?.firstMetAt ?: result.occurredAt,
            result.occurredAt, activeAt, plus(activeAt, REUNION_COOLDOWN_MILLIS), run.candidate,
            (previous?.memories.orEmpty() + storedResult.toMemory()).takeLast(MAX_MEMORIES_PER_CONTACT))
        relationships.contacts = if (previous == null) relationships.contacts + contact
            else relationships.contacts.map { if (it.characterId == contact.characterId) contact else it }
        relationships.pending = null
        relationships.lastResult = storedResult
        relationships.recentResults = (relationships.recentResults + storedResult).takeLast(HISTORY_LIMIT)
        relationships.totalEncounters = plus(relationships.totalEncounters, 1L)
        relationships.totalExperience = plus(relationships.totalExperience, result.experienceAwarded)
        relationships.totalGold = plus(relationships.totalGold, result.goldAwarded)
        if (result.itemName.isNotBlank()) relationships.totalItems = plus(relationships.totalItems, 1L)
        scheduleNext(relationships, result.occurredAt)
    }

    fun scheduleNext(relationships: AdventureRelationshipState, eventAt: Long) {
        val rng = RelationshipRng(relationships.rngState)
        relationships.nextEncounterAt = plus(eventAt, interval(rng))
        relationships.rngState = rng.state
    }

    fun successBasisPoints(
        stats: HeroStats,
        level: Long,
        approach: AdventureRelationshipApproach,
        traitModifierBasisPoints: Int = 0,
    ): Int {
        val expected = expectedStat(level)
        return (4_500L + (score(stats, approach) - expected) * 2_500L / expected + traitModifierBasisPoints)
            .coerceIn(1_500L, 8_500L).toInt()
    }

    fun behaviorSignals(sceneId: String, approachId: String): Set<AdventureBehaviorSignal> =
        definition(sceneId).approaches.firstOrNull { it.id == approachId }?.behaviorSignals.orEmpty()

    private data class TraitInfluence(val traitIds: List<String>, val basisPointModifier: Int)

    internal data class RelationshipReward(
        val kind: AdventureEventRewardKind,
        val experience: Long,
        val gold: Long,
        val item: AdventureEventItemReward,
    )

    private val signalTraits = mapOf(
        AdventureBehaviorSignal.TAKE_ALL to ("L01" to "L02"),
        AdventureBehaviorSignal.TAKE_ONLY_USEFUL to ("L02" to "L01"),
        AdventureBehaviorSignal.PREPARE_THOROUGHLY to ("L04" to "L03"),
        AdventureBehaviorSignal.DEPART_LIGHTLY to ("L03" to "L04"),
        AdventureBehaviorSignal.INSPECT_NEW_GEAR to ("L05" to "L06"),
        AdventureBehaviorSignal.KEEP_FAMILIAR_GEAR to ("L06" to "L05"),
        AdventureBehaviorSignal.WEAPON_FOCUS to ("S05" to "S06"),
        AdventureBehaviorSignal.ARMOR_FOCUS to ("S06" to "S05"),
        AdventureBehaviorSignal.PERSIST to ("E03" to "E04"),
        AdventureBehaviorSignal.MOVE_ON to ("E04" to "E03"),
        AdventureBehaviorSignal.SEEK_NOVELTY to ("G01" to "G02"),
        AdventureBehaviorSignal.REPEAT_PROVEN to ("G02" to "G01"),
        AdventureBehaviorSignal.TAKE_RISK to ("E01" to "E02"),
        AdventureBehaviorSignal.CHECK_SAFETY to ("E02" to "E01"),
        AdventureBehaviorSignal.HELP_OTHERS to ("R01" to "R02"),
        AdventureBehaviorSignal.SELF_PRIORITY to ("R02" to "R01"),
        AdventureBehaviorSignal.COOPERATE to ("R03" to "R04"),
        AdventureBehaviorSignal.ACT_ALONE to ("R04" to "R03"),
        AdventureBehaviorSignal.SPEAK_DIRECT to ("R05" to "R06"),
        AdventureBehaviorSignal.SPEAK_GENTLE to ("R06" to "R05"),
        AdventureBehaviorSignal.TOWN_COMFORT to ("T01" to "T02"),
        AdventureBehaviorSignal.WILDERNESS_COMFORT to ("T02" to "T01"),
        AdventureBehaviorSignal.HURRY_HOME to ("T03" to "T04"),
        AdventureBehaviorSignal.LINGER_RETURN to ("T04" to "T03"),
    )

    private fun traitInfluence(
        state: SimpleGameState,
        approach: AdventureRelationshipApproach,
        decisionSeed: Long,
    ): TraitInfluence {
        val owned = state.adventureTraits.owned.map { it.traitId }.toSet()
        val contributions = mutableListOf<Pair<String, Int>>()
        approach.behaviorSignals.forEach { signal ->
            val pair = signalTraits[signal] ?: return@forEach
            if (pair.first in owned) contributions += pair.first to 750
            if (pair.second in owned) contributions += pair.second to -750
        }
        val activated = contributions.distinctBy { it.first }.filter { (traitId, _) ->
            val rollSeed = mix(decisionSeed xor traitId.hashCode().toLong().rotateLeft(19) xor
                approach.id.hashCode().toLong().rotateLeft(41))
            RelationshipRng(rollSeed).nextInt(10_000) < TRAIT_INFLUENCE_BASIS_POINTS
        }
        return TraitInfluence(
            traitIds = activated.map { it.first }.sorted(),
            basisPointModifier = activated.sumOf { it.second }.coerceIn(-1_500, 1_500),
        )
    }

    private fun relationshipDefinitionEligible(
        definition: AdventureRelationshipDefinition,
        previous: AdventureRelationshipContact?,
        state: SimpleGameState,
        candidate: AdventureEncounterCandidate,
    ): Boolean {
        val reunionEligible = when (definition.reunionRule) {
            AdventureRelationshipReunionRule.ANY -> true
            AdventureRelationshipReunionRule.FIRST_MEETING_ONLY -> previous == null
            AdventureRelationshipReunionRule.REUNION_ONLY -> previous != null
        }
        val tier = previous?.tier ?: AdventureRelationshipTier.KNOWN
        return reunionEligible && tier in definition.allowedTiers &&
            (definition.battleRule == null || battleSnapshotComplete(state, candidate))
    }

    private fun battleSnapshotComplete(
        state: SimpleGameState,
        candidate: AdventureEncounterCandidate,
    ): Boolean = AdventureRelationshipBattleEngine.snapshotFromCandidate(state, candidate) != null

    private fun localBattleSnapshot(
        state: SimpleGameState,
        localCombatPower: Long,
    ): AdventureRelationshipBattleParticipantSnapshot =
        AdventureRelationshipBattleEngine.snapshotFromState(state, localCombatPower)

    private fun opponentBattleSnapshot(
        state: SimpleGameState,
        candidate: AdventureEncounterCandidate,
    ): AdventureRelationshipBattleParticipantSnapshot =
        requireNotNull(AdventureRelationshipBattleEngine.snapshotFromCandidate(state, candidate))

    private fun localSnapshotPower(state: SimpleGameState): Long {
        val statPower = state.hero.stats.values().fold(0L, ::plus)
        val equipmentPower = state.equipment.fold(0L) { total, item -> plus(total, item.power.coerceAtLeast(0L)) }
        return plus(statPower, equipmentPower)
    }

    internal fun fixedEconomicReward(
        level: Long,
        baseExperience: Long,
        rewardSeed: Long,
        bagHasSpace: Boolean,
    ): RelationshipReward {
        val rng = RelationshipRng(rewardSeed)
        val roll = rng.nextInt(100)
        return when {
            roll < FIXED_RELATIONSHIP_ECONOMIC_REWARD_WEIGHTS.experience -> RelationshipReward(
                AdventureEventRewardKind.EXPERIENCE,
                percent(baseExperience, FIXED_EXPERIENCE_PERCENT).coerceAtLeast(1L),
                0L,
                AdventureEventItemReward.NONE,
            )
            roll < FIXED_RELATIONSHIP_ECONOMIC_REWARD_WEIGHTS.experience +
                FIXED_RELATIONSHIP_ECONOMIC_REWARD_WEIGHTS.gold -> RelationshipReward(
                AdventureEventRewardKind.GOLD,
                0L,
                times(level.coerceAtLeast(1L), FIXED_GOLD_PER_LEVEL).coerceAtLeast(1L),
                AdventureEventItemReward.NONE,
            )
            bagHasSpace -> RelationshipReward(
                AdventureEventRewardKind.ITEM,
                0L,
                0L,
                AdventureEventItemReward.EQUIPMENT,
            )
            else -> RelationshipReward(
                AdventureEventRewardKind.EXPERIENCE,
                percent(baseExperience, FIXED_EXPERIENCE_PERCENT).coerceAtLeast(1L),
                0L,
                AdventureEventItemReward.NONE,
            )
        }
    }

    private fun expectedStat(level: Long): Long =
        10L + (level.coerceIn(1L, 1_000_000L) - 1L) / 3L

    private fun percent(value: Long, percent: Long): Long =
        times(value / 100L, percent) + (value % 100L) * percent / 100L

    private fun score(stats: HeroStats, approach: AdventureRelationshipApproach): Long =
        (AdventureEventEngine.stat(stats, approach.primaryStat).coerceIn(0L, 1_000_000L) * 7L +
            AdventureEventEngine.stat(stats, approach.secondaryStat).coerceIn(0L, 1_000_000L) * 3L) / 10L

    private fun interval(rng: RelationshipRng) = MIN_INTERVAL_MILLIS +
        rng.nextInt((MAX_INTERVAL_MILLIS - MIN_INTERVAL_MILLIS + 1L).toInt())
    private fun plus(left: Long, right: Long): Long = if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
    private fun times(left: Long, right: Long): Long = if (left != 0L && right > Long.MAX_VALUE / left) Long.MAX_VALUE else left * right
    private fun mix(value: Long): Long {
        var result = value
        result = (result xor (result ushr 30)) * -4_658_895_280_553_007_687L
        result = (result xor (result ushr 27)) * -7_723_592_293_110_705_685L
        return result xor (result ushr 31)
    }
    private class RelationshipRng(seed: Long) {
        var state = seed
        fun nextLong(): Long {
            state += GOLDEN_GAMMA
            return mix(state)
        }
        fun nextInt(bound: Int): Int {
            require(bound > 0)
            var bits: Long
            var value: Long
            do {
                bits = nextLong() ushr 1
                value = bits % bound.toLong()
            } while (bits - value + bound.toLong() - 1L < 0L)
            return value.toInt()
        }
    }
}
