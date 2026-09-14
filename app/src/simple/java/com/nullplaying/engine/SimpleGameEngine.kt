package com.nullplaying.engine

import com.nullplaying.model.AdventurePhase
import com.nullplaying.model.AdventureEventItemReward
import com.nullplaying.model.AdventureEventRewardKind
import com.nullplaying.model.AdventureEventBattleRewardKind
import com.nullplaying.model.AdventureEventBattleState
import com.nullplaying.model.AdventureEventContext
import com.nullplaying.model.AdventureEventContinuation
import com.nullplaying.model.AdventureEventProgressPolicy
import com.nullplaying.model.AdventureEventResult
import com.nullplaying.model.AdventureRelationshipResult
import com.nullplaying.model.AdventureRelationshipBattleKind
import com.nullplaying.model.AdventureRelationshipTier
import com.nullplaying.model.AdventureTraitEffectKind
import com.nullplaying.model.AdventureTaleState
import com.nullplaying.model.CombatPhase
import com.nullplaying.model.CompletedTaleRecord
import com.nullplaying.model.EquipmentSlot
import com.nullplaying.model.EquippedItem
import com.nullplaying.model.HeroClass
import com.nullplaying.model.BattleHeroClass
import com.nullplaying.model.HeroPathState
import com.nullplaying.model.HeroState
import com.nullplaying.model.HeroStats
import com.nullplaying.model.InventoryItem
import com.nullplaying.model.LearnedSkill
import com.nullplaying.model.MonsterState
import com.nullplaying.model.MonsterGrade
import com.nullplaying.model.RecentAdventureEvent
import com.nullplaying.model.RecentAdventureEventMetadata
import com.nullplaying.model.RecentAdventureEventType
import com.nullplaying.model.SettlementDelta
import com.nullplaying.model.SIMPLE_GAME_SCHEMA_VERSION
import com.nullplaying.model.ShopEquipmentOffer
import com.nullplaying.model.SimpleGameState
import com.nullplaying.model.StatRoll
import com.nullplaying.model.TaleKind
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal data class LegacyAutoHuntSnapshot(
    val schemaVersion: Int,
    val chargeMillis: Long,
    val activeUntil: Long,
)

private data class CombatLoot(
    val name: String,
    val rarity: String,
    val kind: String,
    val equipped: Boolean = false,
    val equipmentSlot: EquipmentSlot? = null,
    val equipmentPower: Long? = null,
    val previousPower: Long? = null,
)

class SimpleGameEngine(
    initialOfflineAdventureConfig: OfflineAdventureConfig = OfflineAdventureConfig(),
    private val enableAdventureEvents: Boolean = false,
    private val enableAdventureRelationships: Boolean = false,
    private val enableAdventureTraits: Boolean = false,
) {
    @Volatile
    var offlineAdventureConfig: OfflineAdventureConfig = initialOfflineAdventureConfig
        private set

    // Called only inside the repository mutex, after settling time under the previous rules.
    internal fun updateOfflineAdventureConfig(config: OfflineAdventureConfig) {
        offlineAdventureConfig = config
    }

    internal fun clampOfflineAdventureBalance(state: SimpleGameState) {
        // A lower new cap must not delete already earned time. Keep legacy/remote overflow
        // until consumed; only new charging/rewards are limited to the current character cap.
        state.offlineAdventureMillis = state.offlineAdventureMillis
            .coerceIn(0L, MAX_STORED_OFFLINE_ADVENTURE_MILLIS)
        state.offlineAdventureChargeRemainder = 0L
        state.offlineAdventureChargeRemainderVersion = 1
    }

    /** Queues one real authored event for the next ordinary boundary without dropping combat progress. */
    internal fun queueAdventureEventForQa(state: SimpleGameState, eventId: String): Boolean {
        if (!enableAdventureEvents ||
            AdventureEventEngine.all.none { it.id == eventId } ||
            state.adventureJourney.pending != null ||
            state.adventureJourney.qaQueuedEventId.isNotBlank()
        ) {
            return false
        }
        state.adventureJourney.qaQueuedEventId = eventId
        state.adventureJourney.qaSequenceCursorEventId = eventId
        return true
    }

    fun offlineAdventureCapacityMillis(state: SimpleGameState): Long {
        val base = offlineAdventureConfig.capacityMillis
        return base + StatBonusRules.adventureBonusMillis(state, base)
    }

    fun rollStats(
        seed: Long,
        heroClass: HeroClass = HeroClass.WARRIOR,
    ): StatRoll {
        val rng = StableRng(seed)
        fun threeDice(): Long = 3L + rng.nextInt(6) + rng.nextInt(6) + rng.nextInt(6)

        val strength = threeDice()
        val constitution = threeDice()
        val dexterity = threeDice()
        val intelligence = threeDice()
        val wisdom = threeDice()
        val charisma = threeDice()
        // Keep the established reroll/creation seed cadence while HP and MP become derived values.
        rng.nextInt(8)
        rng.nextInt(8)
        return StatRoll(
            stats = HeroStats(
                strength = strength,
                constitution = constitution,
                dexterity = dexterity,
                intelligence = intelligence,
                wisdom = wisdom,
                charisma = charisma,
                maxHealth = initialMaxHealth(heroClass, constitution),
                maxMana = initialMaxMana(heroClass, intelligence, wisdom),
            ),
            nextSeed = rng.state,
        )
    }

    fun newGame(
        name: String,
        heroClass: HeroClass,
        rolledStats: HeroStats,
        seed: Long,
        now: Long,
    ): SimpleGameState {
        val rng = StableRng(seed)
        val taleRng = StableRng(seed xor TALE_SEED_SALT)
        val hero = HeroState(
            name = name.trim().ifBlank { "이름 없는 모험가" }.take(16),
            heroClass = heroClass,
            stats = initialStatsForClass(rolledStats, heroClass),
        )
        val skillCatalogSeed = SkillCatalog.deriveSeed(seed, heroClass)
        val startingSkill = SkillCatalog.select(skillCatalogSeed, heroClass, 1)
        val state = SimpleGameState(
            hero = hero,
            equipment = starterEquipment(heroClass),
            skills = mutableListOf(
                LearnedSkill(
                    id = 1,
                    name = startingSkill.name,
                    acquiredAtLevel = 1L,
                    description = startingSkill.description,
                    catalogId = startingSkill.catalogId,
                ),
            ),
            adventureTale = createAdventureTale(
                definition = StarterPrologueCatalog.forClass(heroClass),
                sequence = 0L,
                level = 1L,
                heroName = hero.name,
                rng = taleRng,
            ),
            monster = MonsterState(0L, "", 1L, 1L),
            adventurePhase = AdventurePhase.OPENING,
            actionStartedAt = now,
            actionEndsAt = safeAdd(now, OPENING_PRESENTATION_MILLIS),
            lastSettledAt = now,
            rngState = rng.state,
            presentationRngState = seed xor PRESENTATION_SEED_SALT,
            taleRngState = taleRng.state,
            skillCatalogSeed = skillCatalogSeed,
            lastResult = "${hero.name}의 발걸음이 새로운 모험의 첫 장을 엽니다",
        )
        state.offlineAdventureMillis = offlineAdventureCapacityMillis(state)
        state.offlineAdventureChargeRemainderVersion = 1
        if (enableAdventureEvents) AdventureEventEngine.initialize(state, now)
        if (enableAdventureRelationships) AdventureRelationshipEngine.initialize(state, now)
        if (enableAdventureTraits) AdventureTraitEngine.initialize(state)
        return state
    }

    private fun prepareAdventureTraits(state: SimpleGameState) {
        if (enableAdventureTraits) AdventureTraitEngine.ensureSource(state)
        else if (state.adventureTraits.initialized) {
            state.adventureTraits.temporaryBagSlots = 0L
            state.adventureTraits.source = null
            state.adventureTraits.saleBatch = null
            state.adventureTraits.shopVisit = null
            state.adventureTraits.visibleActivations = emptyList()
            state.adventureTraits.pendingEvidence = emptyList()
        }
    }

    fun settle(state: SimpleGameState, now: Long): SettlementDelta {
        prepareAdventureTraits(state)
        if (now <= state.lastSettledAt) return emptyDelta()
        val savedSchema = state.schemaVersion
        val startedAt = state.lastSettledAt
        val beforeKills = state.totalKills
        val beforeLevel = state.hero.level
        val beforeActs = state.totalActs
        val beforeTales = state.totalTales
        val beforeItems = state.totalItemsFound
        val recentEvents = mutableListOf<RecentAdventureEvent>()
        val gameplayRng = StableRng(state.rngState)
        val presentationRng = StableRng(state.presentationRngState)
        normalizeLegacyCombat(state, gameplayRng, savedSchema)
        if (enableAdventureEvents) AdventureEventEngine.initialize(state, state.lastSettledAt)
        if (enableAdventureRelationships) AdventureRelationshipEngine.initialize(state, state.lastSettledAt)

        replayTimeline(state, gameplayRng, presentationRng, now, recentEvents)
        if (enableAdventureTraits) recentEvents += AdventureTraitEngine.drainRecentEvents(state)
        reconcileHeroPath(state)
        state.lastSettledAt = now
        state.rngState = gameplayRng.state
        state.presentationRngState = presentationRng.state
        return settlementDelta(
            state = state,
            startedAt = startedAt,
            beforeKills = beforeKills,
            beforeLevel = beforeLevel,
            beforeActs = beforeActs,
            beforeTales = beforeTales,
            beforeItems = beforeItems,
            recentEvents = recentEvents,
        )
    }

    /**
     * Resolves elapsed background time at combat boundaries. Skill-use progression and its
     * deterministic rolls are replayed, while attack names, damage and animation stay hidden.
     */
    fun settleOffline(state: SimpleGameState, now: Long): SettlementDelta {
        prepareAdventureTraits(state)
        if (now <= state.lastSettledAt) return emptyDelta()
        val savedSchema = state.schemaVersion
        val startedAt = state.lastSettledAt
        val beforeKills = state.totalKills
        val beforeLevel = state.hero.level
        val beforeActs = state.totalActs
        val beforeTales = state.totalTales
        val beforeItems = state.totalItemsFound
        val recentEvents = mutableListOf<RecentAdventureEvent>()
        val gameplayRng = StableRng(state.rngState)
        val presentationRng = StableRng(state.presentationRngState)
        normalizeLegacyCombat(state, gameplayRng, savedSchema)
        if (enableAdventureEvents) AdventureEventEngine.initialize(state, state.lastSettledAt)
        if (enableAdventureRelationships) AdventureRelationshipEngine.initialize(state, state.lastSettledAt)

        replayTimeline(state, gameplayRng, presentationRng, now, recentEvents)
        if (enableAdventureTraits) recentEvents += AdventureTraitEngine.drainRecentEvents(state)
        reconcileHeroPath(state)
        clearAttackPresentation(state)
        state.lastSettledAt = now
        state.rngState = gameplayRng.state
        state.presentationRngState = presentationRng.state
        return settlementDelta(
            state = state,
            startedAt = startedAt,
            beforeKills = beforeKills,
            beforeLevel = beforeLevel,
            beforeActs = beforeActs,
            beforeTales = beforeTales,
            beforeItems = beforeItems,
            recentEvents = recentEvents,
        )
    }

    private fun replayTimeline(
        state: SimpleGameState,
        gameplayRng: StableRng,
        presentationRng: StableRng,
        now: Long,
        recentEvents: MutableList<RecentAdventureEvent>,
    ) {
        while (state.actionEndsAt <= now) {
            val eventAt = state.actionEndsAt
            when (state.adventurePhase) {
                AdventurePhase.EVENT -> finishAdventureEvent(state, eventAt, recentEvents)
                AdventurePhase.EVENT_RESULT -> finishAdventureEventResult(
                    state,
                    gameplayRng,
                    presentationRng,
                    eventAt,
                    recentEvents,
                )
                AdventurePhase.RELATIONSHIP -> finishAdventureRelationship(state, eventAt, recentEvents)
                AdventurePhase.RELATIONSHIP_RESULT -> finishAdventureEventResult(
                    state,
                    gameplayRng,
                    presentationRng,
                    eventAt,
                    recentEvents,
                )
                AdventurePhase.COMBAT -> when (state.combatPhase) {
                    CombatPhase.REVEAL,
                    CombatPhase.ATTACKING,
                    -> performAttack(state, presentationRng, eventAt, recentEvents)

                    CombatPhase.VICTORY -> finishVictory(state, gameplayRng, eventAt, recentEvents)
                }

                else -> finishTownAction(state, gameplayRng, eventAt, recentEvents)
            }
            if (enableAdventureTraits) recentEvents += AdventureTraitEngine.drainRecentEvents(state)
        }
    }

    /**
     * Consumes the banked offline-adventure time at a one-to-one rate. Any uncovered part of
     * the absence pauses the combat timeline so old attack events cannot run on the next tick.
     */
    internal fun settleOfflineWithOfflineAdventure(
        state: SimpleGameState,
        now: Long,
        legacy: LegacyAutoHuntSnapshot? = null,
    ): SettlementDelta {
        prepareAdventureTraits(state)
        val savedSchema = legacy?.schemaVersion ?: state.schemaVersion
        if (
            (
                savedSchema < MONSTER_ENERGY_REBASE_SCHEMA_VERSION &&
                    state.adventurePhase == AdventurePhase.COMBAT
            ) ||
                (
                    savedSchema < DAMAGE_ENERGY_SCHEMA_VERSION &&
                        state.adventurePhase == AdventurePhase.COMBAT
                ) ||
                (
                    savedSchema < POSTGAME_JOURNEY_SCHEMA_VERSION &&
                        state.adventurePhase == AdventurePhase.COMBAT
                ) ||
                (
                    savedSchema < LABYRINTH_PROGRESSION_SCHEMA_VERSION &&
                        state.adventureTale.kind == TaleKind.LABYRINTH &&
                        state.adventurePhase == AdventurePhase.COMBAT
                ) ||
                shouldRebaselineClassOffense(state, savedSchema)
        ) {
            val migrationRng = StableRng(state.rngState)
            normalizeLegacyCombat(state, migrationRng, savedSchema)
            state.rngState = migrationRng.state
        }
        synchronizeCatalogs(state, savedSchema)
        reconcileHeroPath(state)
        if (savedSchema < OFFLINE_ADVENTURE_SCHEMA_VERSION) {
            return migrateOfflineAdventure(state, now, savedSchema, legacy)
        }
        state.schemaVersion = CURRENT_SCHEMA_VERSION
        state.offlineAdventureMillis = state.offlineAdventureMillis
            .coerceIn(0L, MAX_STORED_OFFLINE_ADVENTURE_MILLIS)
        if (now <= state.lastSettledAt) return emptyDelta()

        val elapsed = now - state.lastSettledAt
        val coveredMillis = minOf(elapsed, state.offlineAdventureMillis)
        val coveredUntil = safeAdd(state.lastSettledAt, coveredMillis).coerceAtMost(now)
        val delta = if (coveredMillis > 0L) {
            settleOffline(state, coveredUntil)
        } else {
            emptyDelta()
        }
        state.offlineAdventureMillis = (state.offlineAdventureMillis - coveredMillis)
            .coerceAtLeast(0L)
        if (state.lastSettledAt < now) pauseTimeline(state, now)
        return delta
    }

    /**
     * Moves a saved action to a new epoch without replaying rewards. This is used when a legacy
     * save is ahead of verified server time, or when a character was created on an unverified
     * provisional clock. Relative action progress is retained; economy and RNG fields are not
     * changed.
     */
    internal fun rebaseTimelineWithoutProgress(state: SimpleGameState, now: Long) {
        val oldCheckpoint = state.lastSettledAt
        val elapsedInAction = nonNegativeDifference(oldCheckpoint, state.actionStartedAt)
        val remainingInAction = nonNegativeDifference(state.actionEndsAt, oldCheckpoint)
        state.actionStartedAt = safeSubtract(now, elapsedInAction)
        state.actionEndsAt = safeAdd(now, remainingInAction)
        AdventureTimelineRebase.rebase(state, oldCheckpoint, now)
        state.lastSettledAt = now
        clearAttackPresentation(state)
    }

    fun advanceOfflineAdventureForeground(
        state: SimpleGameState,
        foregroundElapsedMillis: Long,
    ) {
        if (foregroundElapsedMillis <= 0L) return
        val capacity = offlineAdventureCapacityMillis(state)
        val current = state.offlineAdventureMillis
            .coerceIn(0L, MAX_STORED_OFFLINE_ADVENTURE_MILLIS)
        if (current >= capacity) {
            state.offlineAdventureMillis = current
            state.offlineAdventureChargeRemainder = 0L
            state.offlineAdventureChargeRemainderVersion = 1
            return
        }
        val config = offlineAdventureConfig
        val chargeMillis = config.chargeMinutes * 60_000L
        if (state.offlineAdventureChargeRemainderVersion == 0) {
            state.offlineAdventureChargeRemainder =
                state.offlineAdventureChargeRemainder.coerceIn(0L, config.chargeMinutes - 1L) * 60_000L
            state.offlineAdventureChargeRemainderVersion = 1
        }
        // Bound before multiplication and retain fractional milliseconds across ticks/saves.
        val elapsed = foregroundElapsedMillis.coerceAtMost(chargeMillis)
        val numerator = elapsed * capacity +
            state.offlineAdventureChargeRemainder.coerceIn(0L, chargeMillis - 1L)
        val earned = numerator / chargeMillis
        state.offlineAdventureMillis = safeAdd(current, earned).coerceAtMost(capacity)
        state.offlineAdventureChargeRemainder = if (isOfflineAdventureFull(state)) 0L
            else numerator % chargeMillis
    }

    fun grantRewardedOfflineAdventure(
        state: SimpleGameState,
        rewardRequestId: String,
    ): Boolean {
        if (
            rewardRequestId.isBlank() ||
            rewardRequestId == state.lastRewardRequestId ||
            rewardRequestId in state.rewardedOfflineRequestIds
        ) return false
        if (isOfflineAdventureFull(state)) return false
        state.lastRewardRequestId = rewardRequestId
        state.rewardedOfflineRequestIds = (
            state.rewardedOfflineRequestIds + rewardRequestId
        ).distinct().takeLast(REWARDED_OFFLINE_REQUEST_LEDGER_LIMIT)
        state.offlineAdventureChargeRemainder = 0L
        state.offlineAdventureChargeRemainderVersion = 1
        state.offlineAdventureMillis = offlineAdventureCapacityMillis(state)
        return true
    }

    fun offlineAdventureFraction(state: SimpleGameState): Float =
        (state.offlineAdventureMillis.toDouble() / offlineAdventureCapacityMillis(state).toDouble())
            .coerceIn(0.0, 1.0)
            .toFloat()

    fun isOfflineAdventureFull(state: SimpleGameState): Boolean =
        state.offlineAdventureMillis >= offlineAdventureCapacityMillis(state)

    /** Grants one persistent point per five levels and keeps one current three-card recommendation. */
    internal fun reconcileHeroPath(state: SimpleGameState): Boolean {
        val battleClass = BattleHeroClass.valueOf(state.hero.heroClass.name)
        var current = HeroPathEngine.normalize(state.heroPath)
        if (current.heroClass != battleClass) {
            current = HeroPathState(
                heroClass = battleClass,
                revision = if (current.revision == Long.MAX_VALUE) Long.MAX_VALUE else current.revision + 1L,
            )
        }
        current = HeroPathEngine.reconcilePointGrants(current, state.hero.level)
        if (current.milestoneTokens.any { !it.resolved }) {
            val changed = current != state.heroPath
            state.heroPath = current
            return changed
        }
        if (current.unspentPoints <= 0L) {
            val changed = current != state.heroPath
            state.heroPath = current
            return changed
        }
        val issuedLevels = current.milestoneTokens.map { it.milestoneLevel }.toSet()
        val nextLevel = generateSequence(5L) { previous ->
            if (previous > Long.MAX_VALUE - 5L) null else previous + 5L
        }.takeWhile { it <= current.lastGrantedMilestone }
            .firstOrNull { it !in issuedLevels }
        if (nextLevel != null) {
            val heroId = state.rankingCharacterId.ifBlank {
                "${state.hero.heroClass.name}:${state.hero.name}:${state.skillCatalogSeed}"
            }
            val mutation = HeroPathEngine.issueMilestone(
                state = current,
                heroId = heroId,
                milestoneLevel = nextLevel,
                generationSeed = heroPathSeed(heroId, nextLevel),
            )
            if (mutation.status == com.nullplaying.model.HeroPathMutationStatus.APPLIED) {
                current = mutation.state
            }
        }
        val changed = current != state.heroPath
        state.heroPath = current
        return changed
    }

    private fun heroPathSeed(heroId: String, milestoneLevel: Long): Long {
        var hash = -3_750_763_034_362_895_579L xor milestoneLevel
        heroId.forEach { character ->
            hash = hash xor character.code.toLong()
            hash *= 1_099_511_628_211L
        }
        return hash
    }

    private fun migrateOfflineAdventure(
        state: SimpleGameState,
        now: Long,
        savedSchema: Int,
        legacy: LegacyAutoHuntSnapshot?,
    ): SettlementDelta {
        if (savedSchema < LEGACY_AUTO_HUNT_SCHEMA_VERSION) {
            // Pre-bank builds historically replayed the entire wall-clock gap. Bound that one-time
            // migration now that epoch comes from a remote header: even a bad first observation
            // must not turn into unbounded rewards.
            val elapsed = nonNegativeDifference(now, state.lastSettledAt)
            val coveredMillis = minOf(elapsed, offlineAdventureCapacityMillis(state))
            val coveredUntil = safeAdd(state.lastSettledAt, coveredMillis).coerceAtMost(now)
            val delta = if (coveredMillis > 0L) {
                settleOffline(state, coveredUntil)
            } else {
                emptyDelta()
            }
            if (state.lastSettledAt < now) pauseTimeline(state, now)
            state.schemaVersion = CURRENT_SCHEMA_VERSION
            state.offlineAdventureMillis = offlineAdventureCapacityMillis(state)
            return delta
        }

        if (now <= state.lastSettledAt) {
            state.schemaVersion = CURRENT_SCHEMA_VERSION
            state.offlineAdventureMillis = legacyOfflineAdventureBalance(state, now, legacy)
            return emptyDelta()
        }
        val activeUntil = legacy?.activeUntil ?: 0L
        val coveredUntil = minOf(now, activeUntil).coerceAtLeast(state.lastSettledAt)
        val delta = if (coveredUntil > state.lastSettledAt) {
            settleOffline(state, coveredUntil)
        } else {
            emptyDelta()
        }
        if (state.lastSettledAt < now) pauseTimeline(state, now)
        state.schemaVersion = CURRENT_SCHEMA_VERSION
        state.offlineAdventureMillis = legacyOfflineAdventureBalance(state, now, legacy)
        return delta
    }

    private fun legacyOfflineAdventureBalance(
        state: SimpleGameState,
        now: Long,
        legacy: LegacyAutoHuntSnapshot?,
    ): Long {
        val activeUntil = legacy?.activeUntil ?: 0L
        if (activeUntil > 0L) {
            return (activeUntil - now).coerceIn(0L, MAX_STORED_OFFLINE_ADVENTURE_MILLIS)
        }
        val oldCharge = legacy?.chargeMillis ?: state.offlineAdventureMillis
        return safeMul(oldCharge.coerceAtLeast(0L), LEGACY_OFFLINE_ADVENTURE_EARN_RATE)
            .coerceAtMost(MAX_STORED_OFFLINE_ADVENTURE_MILLIS)
    }

    private fun settlementDelta(
        state: SimpleGameState,
        startedAt: Long,
        beforeKills: Long,
        beforeLevel: Long,
        beforeActs: Long,
        beforeTales: Long,
        beforeItems: Long,
        recentEvents: List<RecentAdventureEvent>,
    ): SettlementDelta = SettlementDelta(
        elapsedMillis = state.lastSettledAt - startedAt,
        defeatedMonsters = state.totalKills - beforeKills,
        levelsGained = state.hero.level - beforeLevel,
        actsCompleted = state.totalActs - beforeActs,
        talesCompleted = state.totalTales - beforeTales,
        itemsFound = state.totalItemsFound - beforeItems,
        recentEvents = recentEvents,
    )

    private fun clearAttackPresentation(state: SimpleGameState) {
        state.lastAttackName = ""
        state.lastAttackType = ""
        state.lastAttackWasSkill = false
        state.lastSkillCatalogId = ""
        state.lastDamage = 0L
        state.lastMonsterEnergyBeforeAttack = 0L
    }

    private fun pauseTimeline(state: SimpleGameState, now: Long) {
        val pausedMillis = (now - state.lastSettledAt).coerceAtLeast(0L)
        state.actionStartedAt = safeAdd(state.actionStartedAt, pausedMillis)
        state.actionEndsAt = safeAdd(state.actionEndsAt, pausedMillis)
        AdventureRelationshipEngine.pause(state.adventureRelationships, pausedMillis)
        if (enableAdventureTraits) AdventureTraitEngine.pause(state, pausedMillis)
        if (state.adventureJourney.initialized) {
            state.adventureJourney.nextEventAt = safeAdd(state.adventureJourney.nextEventAt, pausedMillis)
            state.adventureJourney.pending = state.adventureJourney.pending?.let {
                it.copy(startedAt = safeAdd(it.startedAt, pausedMillis))
            }
        }
        state.lastSettledAt = now
        clearAttackPresentation(state)
    }

    fun experienceRequired(level: Long): Long {
        val safeLevel = level.coerceAtLeast(1L)
        return safeMul(
            304L,
            safeMul(
                safeAdd(safeLevel, 4L),
                safeAdd(safeMul(safeLevel, 2L), -1L),
            ),
        )
    }

    fun monsterEnergyFraction(state: SimpleGameState): Float =
        if (state.monster.maxEnergy <= 0L) {
            0f
        } else {
            (state.monster.currentEnergy.toDouble() / state.monster.maxEnergy.toDouble())
                .toFloat()
                .coerceIn(0f, 1f)
        }

    fun expectedCombatDurationRangeMillis(grade: MonsterGrade): LongRange =
        safeAdd(
            safeAdd(
                ENCOUNTER_REVEAL_MILLIS,
                safeMul((grade.minAttacks - 1).toLong(), ATTACK_PRESENTATION_MILLIS),
            ),
            VICTORY_PRESENTATION_MILLIS,
        )..
            safeAdd(
                safeAdd(
                    ENCOUNTER_REVEAL_MILLIS,
                    safeMul((grade.maxAttacks - 1).toLong(), ATTACK_PRESENTATION_MILLIS),
                ),
                VICTORY_PRESENTATION_MILLIS,
            )

    /** Class-guided level growth, reused by Adventure Tale completion. */
    fun applyClassGuidedGrowth(stats: HeroStats, heroClass: HeroClass, rngSeed: Long): Long {
        val rng = StableRng(rngSeed)
        applyClassGuidedGrowth(stats, heroClass, rng)
        return rng.state
    }

    private fun performAttack(
        state: SimpleGameState,
        rng: StableRng,
        eventAt: Long,
        recentEvents: MutableList<RecentAdventureEvent>,
    ) {
        if (state.monster.currentEnergy <= 0L) {
            state.combatPhase = CombatPhase.VICTORY
            state.actionStartedAt = eventAt
            state.actionEndsAt = safeAdd(eventAt, VICTORY_PRESENTATION_MILLIS)
            return
        }

        val attack = rollAttack(state, rng)
        val skill = attack.skill
        val skillDefinition = attack.definition
        if (
            skill != null &&
            attack.previousSkillLevel != null &&
            skill.level > attack.previousSkillLevel
        ) {
            recentEvents += RecentAdventureEvent(
                occurredAt = eventAt,
                type = RecentAdventureEventType.SKILL_MASTERY,
                subjectId = skill.catalogId,
                subjectName = skill.name,
                previousValue = attack.previousSkillLevel,
                currentValue = skill.level,
            )
        }
        val baseDamage = baseAttackDamage(state)
        val energyBeforeAttack = state.monster.currentEnergy.coerceAtLeast(0L)
        val damagePercent = if (enableAdventureTraits && skill == null)
            AdventureTraitEngine.basicDamagePercent(state, attack.damagePercent, baseDamage, energyBeforeAttack, eventAt)
            else attack.damagePercent
        val variedDamage = scalePercent(baseDamage, damagePercent).coerceAtLeast(1L)
        state.monster.currentEnergy = (energyBeforeAttack - variedDamage).coerceAtLeast(0L)
        state.monster.attacksCompleted = if (state.monster.attacksCompleted == Int.MAX_VALUE) {
            Int.MAX_VALUE
        } else {
            state.monster.attacksCompleted + 1
        }
        state.actionSequence = safeIncrement(state.actionSequence)
        state.actionStartedAt = eventAt
        state.lastDamage = variedDamage
        state.lastMonsterEnergyBeforeAttack = energyBeforeAttack
        state.lastAttackWasSkill = skill != null
        state.lastSkillCatalogId = skillDefinition?.catalogId.orEmpty()
        if (skill != null) {
            state.lastActivatedSkillCatalogId = skill.catalogId
        }
        state.lastAttackName = skill?.name.orEmpty()
        state.lastAttackType = if (skill == null) "기본 공격" else "보유 스킬"
        state.lastResult = if (skill == null) {
            "기본 공격 · ${variedDamage} 피해"
        } else {
            "${skill.name} · ${variedDamage} 피해"
        }

        if (state.monster.currentEnergy == 0L) {
            state.combatPhase = CombatPhase.VICTORY
            state.actionEndsAt = safeAdd(eventAt, VICTORY_PRESENTATION_MILLIS)
        } else {
            state.combatPhase = CombatPhase.ATTACKING
            state.actionEndsAt = safeAdd(eventAt, ATTACK_PRESENTATION_MILLIS)
        }
    }

    private data class RolledAttack(
        val skill: LearnedSkill?,
        val definition: SkillDefinition?,
        val damagePercent: Long,
        val previousSkillLevel: Long? = null,
    )

    fun baseSkillProcPercent(state: SimpleGameState): Int {
        val aptitude = weightedCombatStatTenths(
            stats = state.hero.stats,
            heroClass = state.hero.heroClass,
        ) / 10L
        return safeAdd(SKILL_PROC_BASE_PERCENT.toLong(), aptitude / SKILL_APTITUDE_PER_PERCENT)
            .coerceIn(SKILL_PROC_MIN_PERCENT.toLong(), SKILL_PROC_MAX_PERCENT.toLong())
            .toInt()
    }

    fun skillProcBasisPoints(state: SimpleGameState): Int =
        StatBonusRules.skillProcBasisPoints(state, baseSkillProcPercent(state))

    fun skillProcPercent(state: SimpleGameState): Double = skillProcBasisPoints(state) / 100.0

    internal fun effectiveSkillProcBasisPoints(state: SimpleGameState): Int {
        if (state.skills.isEmpty()) return 0
        val rawProbability = skillProcBasisPoints(state) / 10_000.0
        val missProbability = 1.0 - rawProbability
        val expectedCycleLength = (1.0 - Math.pow(missProbability, 16.0)) / rawProbability
        return (10_000.0 / expectedCycleLength)
            .roundToInt()
            .coerceIn(0, 10_000)
    }

    internal fun expectedBasicAttackDamage(state: SimpleGameState): Long {
        val baseDamage = baseAttackDamage(state)
        return averageNonNegative(
            (BASIC_ATTACK_MIN_PERCENT..BASIC_ATTACK_MAX_PERCENT).map { percent ->
                scalePercent(baseDamage, percent.toLong()).coerceAtLeast(1L)
            },
        )
    }

    internal fun expectedAttackDamage(state: SimpleGameState): Long {
        val baseDamage = baseAttackDamage(state)
        val basicDamage = expectedBasicAttackDamage(state)
        if (state.skills.isEmpty()) return basicDamage

        fun expectedSkillDamage(skill: LearnedSkill): Long {
            val definition = SkillCatalog.find(skill.catalogId)
            val range = definition?.let { it.damagePercentMin..it.damagePercentMax }
                ?: SkillCatalog.damagePercentRange(skill.id)
            val bonusPercent = skill.copy(usageCount = skill.nextUsageCount).damageBonusPercent
            return averageNonNegative(range.map { percent ->
                scalePercent(
                    baseDamage,
                    safeAdd(percent.toLong(), bonusPercent),
                ).coerceAtLeast(1L)
            })
        }

        val allSkillDamages = state.skills.map(::expectedSkillDamage)
        val selectedSkillDamage = weightedAverageNonNegative(
            values = allSkillDamages,
            weights = skillSelectionWeights(state),
        )
        val skillBasisPoints = effectiveSkillProcBasisPoints(state).toLong()
        return safeAdd(
            scaleRatio(basicDamage, 10_000L - skillBasisPoints, 10_000L),
            scaleRatio(selectedSkillDamage, skillBasisPoints, 10_000L),
        ).coerceAtLeast(1L)
    }

    internal fun monsterEnergyFor(state: SimpleGameState, targetAttacks: Int): Long {
        val safeTarget = targetAttacks.coerceAtLeast(1)
        // Skill-tier and mastery damage intentionally remain real growth instead of being
        // absorbed into newly generated monster HP.
        val expectedDamage = expectedBasicAttackDamage(state)
        val minimumBasicDamage = scalePercent(
            baseAttackDamage(state),
            BASIC_ATTACK_MIN_PERCENT.toLong(),
        ).coerceAtLeast(1L)
        val expectedEnergy = safeAdd(
            safeMul((safeTarget - 1).toLong(), expectedDamage),
            minimumBasicDamage,
        )
        return expectedEnergy.coerceAtLeast(1L)
    }

    internal fun shouldUseSkill(state: SimpleGameState, procRoll: Int): Boolean =
        state.skills.isNotEmpty() && (
            state.consecutiveBasicAttacks >= MAX_CONSECUTIVE_BASIC_ATTACKS ||
                procRoll.coerceIn(0, 9_999) < skillProcBasisPoints(state)
            )

    internal fun skillSelectionWeights(state: SimpleGameState): List<Int> {
        if (state.skills.isEmpty()) return emptyList()
        // A single owned skill remains eligible so both normal procs and the 15-basic guarantee
        // can resolve. With two or more, only the previously activated skill is excluded.
        val blockedIndex = if (
            state.skills.size > 1 &&
            state.lastActivatedSkillCatalogId.isNotBlank()
        ) {
            state.skills.indexOfFirst { it.catalogId == state.lastActivatedSkillCatalogId }
        } else {
            -1
        }
        return state.skills.mapIndexed { index, skill ->
            if (index == blockedIndex) {
                0
            } else {
                // Base weight 1 + a rounded bonus that fades from 7 to 0 over 200 uses.
                val remainingUses = (NEW_SKILL_SELECTION_FADE_USES - skill.boundedUsageCount)
                    .coerceAtLeast(0L)
                val extraWeight = (
                    NEW_SKILL_MAX_EXTRA_SELECTION_WEIGHT * remainingUses +
                        NEW_SKILL_SELECTION_FADE_USES / 2L
                    ) / NEW_SKILL_SELECTION_FADE_USES
                1 + extraWeight.toInt()
            }
        }
    }

    internal fun selectWeightedSkillIndex(state: SimpleGameState, selectionRoll: Int): Int {
        val weights = skillSelectionWeights(state)
        val totalWeight = weights.sum()
        if (totalWeight <= 0) return -1
        var remainingRoll = selectionRoll.mod(totalWeight)
        weights.forEachIndexed { index, weight ->
            if (remainingRoll < weight) return index
            remainingRoll -= weight
        }
        return -1
    }

    private fun rollAttack(state: SimpleGameState, rng: StableRng): RolledAttack {
        val procRoll = rng.nextInt(10_000)
        val skillIndex = if (shouldUseSkill(state, procRoll)) {
            val totalWeight = skillSelectionWeights(state).sum()
            selectWeightedSkillIndex(state, rng.nextInt(totalWeight))
        } else {
            -1
        }
        if (skillIndex < 0) {
            state.consecutiveBasicAttacks =
                (state.consecutiveBasicAttacks.coerceIn(0, MAX_CONSECUTIVE_BASIC_ATTACKS) + 1)
                    .coerceAtMost(MAX_CONSECUTIVE_BASIC_ATTACKS)
            return RolledAttack(
                skill = null,
                definition = null,
                damagePercent = (BASIC_ATTACK_MIN_PERCENT + rng.nextInt(
                    BASIC_ATTACK_MAX_PERCENT - BASIC_ATTACK_MIN_PERCENT + 1,
                )).toLong(),
            )
        }

        state.consecutiveBasicAttacks = 0
        val current = state.skills[skillIndex]
        val updated = current.copy(usageCount = current.nextUsageCount)
        state.skills[skillIndex] = updated
        val definition = SkillCatalog.find(updated.catalogId)
        val baseRange = definition?.let { it.damagePercentMin..it.damagePercentMax }
            ?: SkillCatalog.damagePercentRange(updated.id)
        val basePercent = baseRange.first + rng.nextInt(baseRange.last - baseRange.first + 1)
        return RolledAttack(
            skill = updated,
            definition = definition,
            damagePercent = safeAdd(basePercent.toLong(), updated.damageBonusPercent),
            previousSkillLevel = current.level,
        )
    }

    private fun finishVictory(
        state: SimpleGameState,
        rng: StableRng,
        eventAt: Long,
        recentEvents: MutableList<RecentAdventureEvent>,
    ) {
        if (state.adventureJourney.eventBattle != null) {
            completeAdventureEventBattle(state, eventAt, recentEvents)
        } else {
            completeCombat(state, rng, eventAt, recentEvents)
        }
        continueAfterVictory(state, eventAt)
    }

    private fun continueAfterVictory(state: SimpleGameState, eventAt: Long) {
        beginLooting(state, eventAt)
        clearAttackPresentation(state)
    }

    private fun beginLooting(state: SimpleGameState, eventAt: Long) {
        state.adventurePhase = AdventurePhase.LOOTING
        state.combatPhase = CombatPhase.VICTORY
        state.actionStartedAt = eventAt
        state.actionEndsAt = safeAdd(eventAt, LOOT_RESULT_MILLIS)
        if (enableAdventureTraits) state.actionEndsAt = safeAdd(eventAt, AdventureTraitEngine.resultMillis(state, LOOT_RESULT_MILLIS))
        state.lastResult = state.lastLootSummary
    }

    private fun finishLooting(state: SimpleGameState, rng: StableRng, eventAt: Long) {
        val eventBattle = state.adventureJourney.eventBattle
        if (eventBattle?.result != null) {
            state.adventureJourney.eventBattle = null
            resumeAdventureAfterEvent(state, rng, eventAt, eventBattle.continuation)
            return
        }
        if (state.inventory.size.toLong() >= state.inventoryCapacity()) {
            if (!tryBeginAdventureEvent(state, eventAt, AdventureEventContext.RETURN_ROUTE)) {
                beginReturning(state, eventAt)
            }
        } else {
            if (!tryBeginAdventureEvent(state, eventAt, AdventureEventContext.POST_COMBAT)) {
                beginNextAdventureStep(state, rng, eventAt)
            }
        }
    }

    /** Only ordinary encounter slots may become events. Elites and story/gate bosses remain combat. */
    private fun beginNextAdventureStep(state: SimpleGameState, rng: StableRng, eventAt: Long) {
        if ((enableAdventureEvents || enableAdventureRelationships) && state.inventory.size.toLong() >= state.inventoryCapacity()) {
            beginReturning(state, eventAt)
            return
        }
        val act = state.adventureTale.activeAct()
        val ordinarySlot = QuestMonsterCatalog.encounterGrade(act.progress, act.target) == MonsterGrade.NORMAL
        if (enableAdventureEvents && ordinarySlot) {
            val queuedEventId = state.adventureJourney.qaQueuedEventId
            if (queuedEventId.isNotBlank()) {
                state.adventureJourney.qaQueuedEventId = ""
                val definitionExists = AdventureEventEngine.all.any { it.id == queuedEventId }
                if (definitionExists) {
                    beginAdventureEvent(
                        state = state,
                        eventAt = eventAt,
                        baseRun = AdventureEventEngine.beginForQa(
                            state = state,
                            eventAt = eventAt,
                            eventId = queuedEventId,
                            actionMillis = adventureEventActionMillis(state),
                            // The visible QA sequencer audits the complete catalog without
                            // advancing into authored elite/story slots between forced cases.
                            progressPolicyOverride = AdventureEventProgressPolicy.NO_PROGRESS,
                        ),
                    )
                    return
                }
            }
        }
        if (enableAdventureRelationships && ordinarySlot) {
            val selectedRun = AdventureRelationshipEngine.tryBegin(
                state = state,
                eventAt = eventAt,
                localCombatPower = displayCombatPower(state),
            )
            if (selectedRun != null) {
                val run = if (selectedRun.battleKind == AdventureRelationshipBattleKind.NONE) {
                    selectedRun
                } else {
                    AdventureRelationshipBattleEngine.simulate(selectedRun)?.let { resolution ->
                        AdventureRelationshipEngine.resolveBattle(selectedRun, resolution.outcome)
                    } ?: AdventureRelationshipEngine.withoutUnplayableBattle(selectedRun)
                }
                state.adventureRelationships.pending = run
                if (enableAdventureTraits) AdventureTraitEngine.planRelationship(state, run)
                state.adventurePhase = AdventurePhase.RELATIONSHIP
                state.actionSequence = safeIncrement(state.actionSequence)
                state.actionStartedAt = eventAt
                state.actionEndsAt = safeAdd(eventAt, run.durationMillis)
                clearAttackPresentation(state)
                clearLootPresentation(state)
                state.lastResult = AdventureRelationshipEngine.definition(run.sceneId).scene.ko
                return
            }
        }
        if (enableAdventureEvents) {
            AdventureEventEngine.initialize(state, eventAt)
            if (ordinarySlot) {
                if (tryBeginAdventureEvent(state, eventAt, AdventureEventContext.PRE_COMBAT)) return
                if (tryBeginAdventureEvent(
                        state,
                        eventAt,
                        AdventureEventContext.FIELD_EXPLORATION,
                        adventureEventActionMillis(state),
                    )
                ) return
            }
        }
        beginCombat(state, rng, eventAt)
    }

    private fun tryBeginAdventureEvent(
        state: SimpleGameState,
        eventAt: Long,
        context: AdventureEventContext,
        actionMillis: Long = AdventureEventEngine.ACTION_MILLIS,
    ): Boolean {
        if (!enableAdventureEvents || !AdventureEventEngine.isDue(state, eventAt, context)) return false
        beginAdventureEvent(
            state = state,
            eventAt = eventAt,
            baseRun = AdventureEventEngine.begin(state, eventAt, actionMillis, context),
        )
        return true
    }

    private fun beginAdventureEvent(
        state: SimpleGameState,
        eventAt: Long,
        baseRun: com.nullplaying.model.AdventureEventRun,
    ) {
        val plannedRun = if (enableAdventureTraits) AdventureTraitEngine.planEvent(state, baseRun) else baseRun
        // A labyrinth event without combat uses the replaced ordinary encounter's time budget.
        // When the event itself opens a real elite/boss fight, that fight supplies the combat time;
        // keep only the authored 5s discovery + 5s action and any trait-added investigation time.
        val run = if (
            plannedRun.context == AdventureEventContext.FIELD_EXPLORATION &&
            plannedRun.battleGrade != null &&
            plannedRun.durationMillis > AdventureEventEngine.ACTION_MILLIS
        ) {
            plannedRun.copy(
                durationMillis = safeAdd(
                    AdventureEventEngine.ACTION_MILLIS,
                    (plannedRun.durationMillis - baseRun.durationMillis).coerceAtLeast(0L),
                ),
            )
        } else {
            plannedRun
        }
        state.adventureJourney.pending = run
        state.adventurePhase = AdventurePhase.EVENT
        state.actionSequence = safeIncrement(state.actionSequence)
        state.actionStartedAt = eventAt
        state.actionEndsAt = safeAdd(eventAt, run.durationMillis)
        clearAttackPresentation(state)
        clearLootPresentation(state)
        state.lastResult = AdventureEventEngine.definition(run.eventId).scene.ko
    }

    private fun finishAdventureRelationship(
        state: SimpleGameState,
        eventAt: Long,
        recentEvents: MutableList<RecentAdventureEvent>,
    ) {
        val relationships = state.adventureRelationships
        val run = relationships.pending
        if (run == null) {
            state.adventurePhase = AdventurePhase.RELATIONSHIP_RESULT
            state.actionStartedAt = eventAt
            state.actionEndsAt = safeAdd(eventAt, AdventureRelationshipEngine.RESULT_MILLIS)
            return
        }
        val rewardRng = StableRng(run.rewardSeed)
        val xp = if (run.rewardKind == AdventureEventRewardKind.EXPERIENCE && run.experienceReward > 0L) {
            if (enableAdventureTraits) {
                AdventureTraitEngine.experience(state, run.experienceReward, "relationship:${run.sceneId}", eventAt)
            } else {
                run.experienceReward
            }
        } else {
            0L
        }
        if (xp > 0L) grantExperience(state, xp, rewardRng, eventAt, recentEvents)
        val goldBefore = state.hero.gold
        if (run.rewardKind == AdventureEventRewardKind.GOLD && run.goldReward > 0L) {
            state.hero.gold = safeAdd(state.hero.gold, run.goldReward)
        }
        val goldAwarded = state.hero.gold - goldBefore
        val rewardOrigin = if (enableAdventureTraits) {
            state.adventureTraits.source?.finalRewardOrigin ?: "PRIMARY"
        } else {
            "PRIMARY"
        }
        val loot = if (
            run.rewardKind == AdventureEventRewardKind.ITEM &&
            run.itemReward == AdventureEventItemReward.EQUIPMENT
        ) {
            addEquipmentDrop(
                state = state,
                rng = rewardRng,
                eventAt = eventAt,
                recentEvents = recentEvents,
                origin = rewardOrigin,
                maximumRarityRank = MYTHIC_RARITY_RANK,
            )
        } else {
            null
        }
        val progressBefore = state.adventureTale.activeAct().progress
        advanceOrdinaryAdventureProgress(state)
        val traitSource = state.adventureTraits.source
        val relationshipDelta = if (
            enableAdventureTraits &&
            traitSource?.baseRelationship?.sequence == run.sequence &&
            traitSource.baseRelationship?.snapshotId == run.snapshotId
        ) {
            traitSource.relationshipDelta ?: AdventureRelationshipEngine.effectiveScoreDelta(run)
        } else {
            AdventureRelationshipEngine.effectiveScoreDelta(run)
        }
        val result = AdventureRelationshipResult(
            run = run,
            occurredAt = eventAt,
            experienceAwarded = xp,
            scoreAfter = (run.scoreBefore + relationshipDelta).coerceIn(-100, 100),
            progressAdded = state.adventureTale.activeAct().progress - progressBefore,
            goldAwarded = goldAwarded,
            itemName = loot?.name.orEmpty(),
            itemRarity = loot?.rarity.orEmpty(),
            itemEquipped = loot?.equipped ?: false,
            rewardKind = run.rewardKind,
        )
        AdventureRelationshipEngine.complete(relationships, result)
        if (enableAdventureTraits) {
            AdventureTraitEngine.observeRelationship(state, eventAt)
            AdventureTraitEngine.finalizeEvidence(state, eventAt)
        }
        recentEvents += RecentAdventureEvent(
            occurredAt = eventAt,
            type = RecentAdventureEventType.RELATIONSHIP_ENCOUNTER,
            subjectId = run.candidate.characterId,
            subjectName = run.candidate.displayName,
            contextName = RecentAdventureEventMetadata.appendRelationshipReward(
                baseContext = "${run.sceneId}:${run.approachId}:${run.outcome.name}",
                rewardKind = result.rewardKind,
                experienceAwarded = result.experienceAwarded,
                goldAwarded = result.goldAwarded,
                itemName = result.itemName,
                itemEquipped = result.itemEquipped,
            ),
            previousName = AdventureRelationshipTier.fromScore(run.scoreBefore).name,
            currentName = result.tier.name,
            previousValue = run.scoreBefore.toLong(),
            currentValue = result.scoreAfter.toLong(),
            rarity = result.itemRarity,
        )
        state.adventurePhase = AdventurePhase.RELATIONSHIP_RESULT
        state.actionSequence = safeIncrement(state.actionSequence)
        state.actionStartedAt = eventAt
        state.actionEndsAt = safeAdd(eventAt, AdventureRelationshipEngine.RESULT_MILLIS)
        if (enableAdventureTraits) state.actionEndsAt = safeAdd(eventAt, AdventureTraitEngine.resultMillis(state, AdventureRelationshipEngine.RESULT_MILLIS))
        val definition = AdventureRelationshipEngine.definition(run.sceneId)
        state.lastResult = when (run.outcome) {
            com.nullplaying.model.AdventureEventOutcome.SUCCESS -> definition.success.ko
            com.nullplaying.model.AdventureEventOutcome.PARTIAL -> definition.partial.ko
            com.nullplaying.model.AdventureEventOutcome.FAILURE -> definition.failure.ko
        }
        if (loot != null) recordLootPresentation(state, loot) else clearLootPresentation(state)
    }

    private fun finishAdventureEvent(
        state: SimpleGameState,
        eventAt: Long,
        recentEvents: MutableList<RecentAdventureEvent>,
    ) {
        val journey = state.adventureJourney
        val pendingRun = journey.pending
        if (pendingRun == null) {
            // A malformed/older prototype phase cannot replay a previous reward or stall the clock.
            state.adventurePhase = AdventurePhase.EVENT_RESULT
            state.actionStartedAt = eventAt
            state.actionEndsAt = safeAdd(eventAt, AdventureEventEngine.RESULT_MILLIS)
            return
        }
        val run = AdventureEventEngine.normalizedForSettlement(pendingRun)
        val definition = AdventureEventEngine.definition(run.eventId)
        val rewardRng = StableRng(run.rewardSeed)
        // The existing growth/skill unlock path runs once; event randomness never consumes combat RNG.
        val xp = if (enableAdventureTraits && run.experienceReward > 0L) {
            AdventureTraitEngine.experience(state, run.experienceReward, "event:${run.eventId}", eventAt)
        } else {
            run.experienceReward
        }
        grantExperience(state, xp, rewardRng, eventAt, recentEvents)
        val goldBefore = state.hero.gold
        state.hero.gold = safeAdd(state.hero.gold, run.goldReward)
        val goldAwarded = state.hero.gold - goldBefore
        val itemBefore = state.totalItemsFound
        val rewardOrigin = if (enableAdventureTraits) state.adventureTraits.source?.finalRewardOrigin ?: "PRIMARY" else "PRIMARY"
        val loot = when (run.itemReward) {
            AdventureEventItemReward.NONE -> null
            AdventureEventItemReward.EQUIPMENT -> addEquipmentDrop(state, rewardRng, eventAt, recentEvents, rewardOrigin)
            AdventureEventItemReward.TROPHY -> if (state.inventory.size.toLong() < state.inventoryCapacity()) {
                val item = InventoryItem(safeIncrement(state.totalItemsFound), definition.itemName.ko,
                    AdventureEventEngine.eventTrophyRarity(run.rewardSeed), "전리품", state.hero.level)
                val key = if (enableAdventureTraits) AdventureTraitEngine.nextRewardKey(state, rewardOrigin) else ""
                val canOmit = run.eventId in setOf("bridge", "wayfinding", "ruins", "camp")
                val omitted = enableAdventureTraits && rewardOrigin == "PRIMARY" && canOmit && AdventureTraitEngine.omitTrophy(state, key, item.rarity, item.name, eventAt)
                if (omitted) {
                    AdventureTraitEngine.rewardTrace(state, key, rewardOrigin, 0L, item.name, null, item.rarity, null, null, false, false)
                    null
                } else {
                    addInventory(state, item)
                    if (enableAdventureTraits) {
                        AdventureTraitEngine.rewardTrace(state, key, rewardOrigin, item.id, item.name, null, item.rarity, null, null, true, false)
                        if (rewardOrigin == "PRIMARY") maybeAddTraitExtra(state, key, eventAt, recentEvents)
                    }
                    CombatLoot(item.name, item.rarity, item.kind)
                }
            } else null
        }
        val progressBefore = state.adventureTale.activeAct().progress
        if (run.progressPolicy == AdventureEventProgressPolicy.REPLACE_ORDINARY_SLOT) {
            advanceOrdinaryAdventureProgress(state)
        }
        val progressAdded = state.adventureTale.activeAct().progress - progressBefore
        val result = AdventureEventResult(run, eventAt, xp, goldAwarded,
            loot?.name.orEmpty(), loot?.rarity.orEmpty(), loot?.equipped ?: false, progressAdded,
            if (loot == null) 0L else state.hero.level,
            additionalItemNames = if (enableAdventureTraits) state.adventureTraits.source?.additionalItemNames.orEmpty() else emptyList(),
            itemOmittedByTrait = enableAdventureTraits && state.adventureTraits.source?.itemOmitted == true,
            actualItemCount = (state.totalItemsFound - itemBefore).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        journey.pending = null
        journey.lastResult = result
        journey.completedEvents = safeIncrement(journey.completedEvents)
        journey.totalExperience = safeAdd(journey.totalExperience, result.experienceAwarded)
        journey.totalGold = safeAdd(journey.totalGold, goldAwarded)
        journey.totalItems = safeAdd(journey.totalItems, state.totalItemsFound - itemBefore)
        journey.recentResults = (journey.recentResults + result).takeLast(AdventureEventEngine.HISTORY_LIMIT)
        if (run.battleGrade != null) {
            journey.eventBattle = AdventureEventBattleState(
                eventId = run.eventId,
                grade = run.battleGrade,
                rewardKind = run.battleRewardKind.takeUnless {
                    it == AdventureEventBattleRewardKind.NONE
                } ?: AdventureEventEngine.battleRewardKindForSeed(run.rewardSeed, run.battleGrade),
                rewardSeed = run.rewardSeed,
                continuation = run.continuation,
                context = run.context,
            )
        }
        when {
            run.routeDelayMillis < 0L -> {
                val uses = run.routeRewardUses.coerceIn(2, 10)
                journey.routeRewardDelayQueueMillis = journey.routeRewardDelayQueueMillis +
                    List(uses) { run.routeDelayMillis }
            }
            run.routeDelayMillis > 0L -> {
                val pendingAdjustment = journey.nextEncounterDelayAdjustmentMillis
                if (pendingAdjustment < 0L) {
                    journey.routeRewardDelayQueueMillis =
                        listOf(pendingAdjustment, pendingAdjustment) + journey.routeRewardDelayQueueMillis
                    journey.nextEncounterDelayAdjustmentMillis = run.routeDelayMillis
                } else {
                    journey.nextEncounterDelayAdjustmentMillis = safeAdd(pendingAdjustment, run.routeDelayMillis)
                }
            }
        }
        AdventureEventEngine.scheduleNext(journey, eventAt)
        if (enableAdventureTraits) {
            AdventureTraitEngine.observeEvent(state, eventAt)
            AdventureTraitEngine.finalizeEvidence(state, eventAt)
        }
        // A combat incident is still unresolved here. Record it once, after the elite/boss reward
        // has settled, so recent history never shows a duplicate zero-reward entry.
        if (run.battleGrade == null) {
            val baseContext = if (enableAdventureTraits) {
                "${run.approachId}:${run.outcome.name}:${result.actualItemCount}"
            } else {
                "${run.approachId}:${run.outcome.name}"
            }
            recentEvents += RecentAdventureEvent(
                occurredAt = eventAt,
                type = RecentAdventureEventType.ADVENTURE_EVENT,
                subjectId = run.eventId,
                subjectName = definition.title.ko,
                contextName = RecentAdventureEventMetadata.appendAdventureReward(
                    baseContext = baseContext,
                    rewardKind = run.rewardKind,
                    routeRewardUses = run.routeRewardUses,
                    routeDelayMillis = run.routeDelayMillis,
                    itemReward = run.itemReward,
                ),
                previousValue = result.experienceAwarded,
                currentValue = result.goldAwarded,
                currentName = result.itemName,
                rarity = result.itemRarity,
            )
        }
        state.adventurePhase = AdventurePhase.EVENT_RESULT
        state.actionSequence = safeIncrement(state.actionSequence)
        state.actionStartedAt = eventAt
        state.actionEndsAt = safeAdd(eventAt, AdventureEventEngine.RESULT_MILLIS)
        if (enableAdventureTraits) state.actionEndsAt = safeAdd(eventAt, AdventureTraitEngine.resultMillis(state, AdventureEventEngine.RESULT_MILLIS))
        state.lastResult = when (run.outcome) {
            com.nullplaying.model.AdventureEventOutcome.SUCCESS -> definition.success.ko
            com.nullplaying.model.AdventureEventOutcome.PARTIAL -> definition.partial.ko
            com.nullplaying.model.AdventureEventOutcome.FAILURE -> definition.failure.ko
        }
        if (loot != null) recordLootPresentation(state, loot) else clearLootPresentation(state)
    }

    private fun finishAdventureEventResult(
        state: SimpleGameState,
        rng: StableRng,
        presentationRng: StableRng,
        eventAt: Long,
        recentEvents: MutableList<RecentAdventureEvent>,
    ) {
        if (state.adventurePhase == AdventurePhase.RELATIONSHIP_RESULT) {
            resumeAdventureAfterEvent(state, rng, eventAt, AdventureEventContinuation.NEXT_ADVENTURE_STEP)
            return
        }
        val battle = state.adventureJourney.eventBattle
        if (battle != null && battle.result == null) {
            beginAdventureEventBattle(state, presentationRng, eventAt, battle, recentEvents)
            return
        }
        val continuation = state.adventureJourney.lastResult?.run?.continuation
            ?: AdventureEventContinuation.NEXT_ADVENTURE_STEP
        resumeAdventureAfterEvent(state, rng, eventAt, continuation)
    }

    private fun resumeAdventureAfterEvent(
        state: SimpleGameState,
        rng: StableRng,
        eventAt: Long,
        continuation: AdventureEventContinuation,
    ) {
        when (continuation) {
            AdventureEventContinuation.NEXT_ADVENTURE_STEP -> {
                if (state.inventory.size.toLong() >= state.inventoryCapacity()) beginReturning(state, eventAt)
                else beginNextAdventureStep(state, rng, eventAt)
            }
            AdventureEventContinuation.BEGIN_COMBAT -> {
                if (state.inventory.size.toLong() >= state.inventoryCapacity()) beginReturning(state, eventAt)
                else beginCombat(state, rng, eventAt)
            }
            AdventureEventContinuation.BEGIN_RETURNING -> beginReturning(state, eventAt)
            AdventureEventContinuation.BEGIN_TOWN_SHOPPING -> beginShoppingOrDeparting(state, rng, eventAt)
        }
    }

    private fun advanceOrdinaryAdventureProgress(state: SimpleGameState) {
        // Successful learning, including a safe failed attempt, advances the ordinary journey only.
        // Completion, quest rewards, titles and the final boss are owned by the existing combat path.
        advanceActProgress(state, canCompleteAct = false)
    }

    private fun adventureEventActionMillis(state: SimpleGameState): Long {
        if (state.adventureTale.kind != TaleKind.LABYRINTH) return AdventureEventEngine.ACTION_MILLIS
        val baseAttacks = LabyrinthProgression.targetAttacks(state.adventureTale.labyrinthDepth,
            MonsterGrade.NORMAL.minAttacks, isGateBoss = false)
        val attacks = attackCountForCombatPower(state, baseAttacks)
        // Match the current ordinary encounter's depth/power budget instead of bypassing deep combat in 25s.
        val ordinaryCombatMillis = safeAdd(StatBonusRules.encounterRevealMillis(state),
            safeAdd(safeMul((attacks - 1).coerceAtLeast(0).toLong(), ATTACK_PRESENTATION_MILLIS),
                VICTORY_PRESENTATION_MILLIS))
        // Keep the ordinary combat + loot total when more time is reserved for reading the result.
        return (safeAdd(ordinaryCombatMillis, LOOT_RESULT_MILLIS) - AdventureEventEngine.RESULT_MILLIS)
            .coerceAtLeast(1_000L)
    }

    /** Both real victories and other journey actions share the same non-boss progress boundary. */
    private fun advanceActProgress(state: SimpleGameState, canCompleteAct: Boolean): Boolean {
        val act = state.adventureTale.activeAct()
        val upperBound = if (canCompleteAct) act.target else (act.target - 1L).coerceAtLeast(0L)
        act.progress = safeIncrement(act.progress).coerceAtMost(upperBound)
        return canCompleteAct && act.progress >= act.target
    }

    private fun beginAdventureEventBattle(
        state: SimpleGameState,
        presentationRng: StableRng,
        eventAt: Long,
        battle: AdventureEventBattleState,
        recentEvents: MutableList<RecentAdventureEvent>,
    ) {
        val definition = AdventureEventEngine.definition(battle.eventId)
        val battleRule = requireNotNull(definition.battleRule) {
            "Adventure event ${battle.eventId} has no battle rule"
        }
        val depth = state.adventureTale.labyrinthDepth.takeIf {
            state.adventureTale.kind == TaleKind.LABYRINTH
        } ?: 0L
        val level = safeAdd(
            state.hero.level.coerceAtLeast(1L),
            if (depth > 0L) LabyrinthProgression.monsterLevelBonus(depth) else 0L,
        )
        val baseAttacks = battle.grade.minAttacks
        val depthAdjustedAttacks = if (depth > 0L) {
            LabyrinthProgression.targetAttacks(depth, baseAttacks, isGateBoss = false)
        } else {
            baseAttacks
        }
        val targetAttacks = attackCountForCombatPower(state, depthAdjustedAttacks)
        val energy = monsterEnergyFor(state, targetAttacks)
        val baseName = battleRule.monsterName.ko
        state.monster = MonsterState(
            id = safeIncrement(state.totalKills),
            name = baseName,
            level = level,
            maxEnergy = energy,
            grade = battle.grade,
            currentEnergy = energy,
            expectedAttacks = targetAttacks,
            attacksCompleted = 0,
            catalogId = "event:${battle.eventId}",
            baseName = baseName,
            isFinalBoss = false,
            isLabyrinthGateBoss = false,
        )
        if (enableAdventureTraits) AdventureTraitEngine.beginCombat(state, eventAt)
        state.adventurePhase = AdventurePhase.COMBAT
        state.lastTownItemName = ""
        state.lastTownItemRarity = ""
        state.lastTownGold = 0L
        state.pendingShopOffer = null
        state.lastShopPurchase = null
        state.shopAttemptedSlots.clear()
        clearLootPresentation(state)
        // The event result already introduced this enemy. Start the first real attack at the
        // same boundary instead of replaying the ordinary search/discovery presentation.
        performAttack(state, presentationRng, eventAt, recentEvents)
    }

    private fun completeAdventureEventBattle(
        state: SimpleGameState,
        eventAt: Long,
        recentEvents: MutableList<RecentAdventureEvent>,
    ) {
        val journey = state.adventureJourney
        val battle = requireNotNull(journey.eventBattle)
        val sourceResult = requireNotNull(journey.lastResult) {
            "Event battle ${battle.eventId} has no source result"
        }
        state.totalKills = safeIncrement(state.totalKills)
        val rewardRng = StableRng(battle.rewardSeed)
        val itemsBefore = state.totalItemsFound
        val goldBefore = state.hero.gold
        var loot: CombatLoot? = null
        var actualRewardKind = battle.rewardKind
        if (battle.rewardKind == AdventureEventBattleRewardKind.EQUIPMENT) {
            val maximumRarityRank = if (sourceResult.run.outcome == com.nullplaying.model.AdventureEventOutcome.SUCCESS) {
                MYTHIC_RARITY_RANK
            } else {
                LEGENDARY_RARITY_RANK
            }
            loot = addEquipmentDrop(
                state,
                rewardRng,
                eventAt,
                recentEvents,
                origin = "EVENT_BATTLE",
                maximumRarityRank = maximumRarityRank,
            )
            if (loot == null) actualRewardKind = AdventureEventBattleRewardKind.GOLD
        }
        if (actualRewardKind == AdventureEventBattleRewardKind.GOLD) {
            val multiplier = eventBattleGoldPerLevel(sourceResult.run.outcome)
            val gold = safeMul(state.hero.level.coerceAtLeast(1L), multiplier).coerceAtLeast(multiplier)
            state.hero.gold = safeAdd(state.hero.gold, gold)
        }
        val goldAwarded = state.hero.gold - goldBefore
        val rewardRun = sourceResult.run.copy(
            experienceReward = 0L,
            goldReward = goldAwarded,
            itemReward = if (loot != null) AdventureEventItemReward.EQUIPMENT else AdventureEventItemReward.NONE,
            routeDelayMillis = 0L,
            rewardKind = if (loot != null) {
                com.nullplaying.model.AdventureEventRewardKind.ITEM
            } else {
                com.nullplaying.model.AdventureEventRewardKind.GOLD
            },
            routeRewardUses = 0,
            battleRewardKind = actualRewardKind,
        )
        val result = AdventureEventResult(
            run = rewardRun,
            occurredAt = eventAt,
            experienceAwarded = 0L,
            goldAwarded = goldAwarded,
            itemName = loot?.name.orEmpty(),
            itemRarity = loot?.rarity.orEmpty(),
            itemEquipped = loot?.equipped ?: false,
            progressAdded = 0L,
            itemFoundAtLevel = if (loot == null) 0L else state.hero.level,
            actualItemCount = (state.totalItemsFound - itemsBefore)
                .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
            battleResolved = true,
        )
        battle.result = result
        journey.lastResult = result
        journey.totalGold = safeAdd(journey.totalGold, goldAwarded)
        journey.totalItems = safeAdd(journey.totalItems, state.totalItemsFound - itemsBefore)
        journey.recentResults = if (
            journey.recentResults.lastOrNull()?.run?.sequence == sourceResult.run.sequence
        ) {
            (journey.recentResults.dropLast(1) + result).takeLast(AdventureEventEngine.HISTORY_LIMIT)
        } else {
            (journey.recentResults + result).takeLast(AdventureEventEngine.HISTORY_LIMIT)
        }
        if (loot != null) {
            recordLootPresentation(state, loot)
        } else {
            clearLootPresentation(state)
            state.lastLootSummary = "+${goldAwarded}G · 사건 전투 보상"
        }
        if (enableAdventureTraits) {
            AdventureTraitEngine.observeCombat(state, eventAt)
            AdventureTraitEngine.finalizeEvidence(state, eventAt)
        }
        val definition = AdventureEventEngine.definition(battle.eventId)
        recentEvents += RecentAdventureEvent(
            occurredAt = eventAt,
            type = RecentAdventureEventType.ADVENTURE_EVENT,
            subjectId = battle.eventId,
            subjectName = definition.title.ko,
            contextName = RecentAdventureEventMetadata.appendAdventureReward(
                baseContext = "${rewardRun.approachId}:${rewardRun.outcome.name}:${result.actualItemCount}",
                rewardKind = rewardRun.rewardKind,
                routeRewardUses = 0,
                routeDelayMillis = 0L,
                itemReward = rewardRun.itemReward,
            ),
            previousValue = 0L,
            currentValue = goldAwarded,
            currentName = result.itemName,
            rarity = result.itemRarity,
        )
        state.lastResult = "${state.monster.name} 처치 · 사건 보상 정산"
    }

    private fun completeCombat(
        state: SimpleGameState,
        rng: StableRng,
        eventAt: Long,
        recentEvents: MutableList<RecentAdventureEvent>,
    ) {
        val defeated = state.monster
        val defeatedTale = state.adventureTale
        val labyrinthDepth = defeatedTale.labyrinthDepth
        val isLabyrinthGateBoss = defeatedTale.kind == TaleKind.LABYRINTH &&
            defeated.isLabyrinthGateBoss
        state.totalKills = safeIncrement(state.totalKills)

        val gradeMultiplier = when (defeated.grade) {
            MonsterGrade.NORMAL -> 1L
            MonsterGrade.ELITE -> 3L
            MonsterGrade.BOSS -> if (isLabyrinthGateBoss) 12L else 8L
        }
        val baseXp = safeMul(safeAdd(16L, safeMul(defeated.level, 4L)), gradeMultiplier)
        val ordinaryXp = if (defeatedTale.kind == TaleKind.LABYRINTH) {
            LabyrinthProgression.scaleCombatExperience(baseXp, labyrinthDepth)
        } else {
            baseXp
        }
        val xp = if (enableAdventureTraits) AdventureTraitEngine.experience(state, ordinaryXp,
            "combat:${defeated.catalogId.ifBlank { defeated.baseName }}", eventAt) else ordinaryXp
        grantExperience(state, xp, rng, eventAt, recentEvents)
        val loot = if (rng.nextInt(100) < equipmentDropPercent(defeated)) {
            addEquipmentDrop(state, rng, eventAt, recentEvents)
        } else {
            addTrophy(state, rng, defeated, eventAt, recentEvents)
        }
        recordLootPresentation(state, loot)
        advanceTaleOnVictory(state, rng, defeated, eventAt, recentEvents)
        if (enableAdventureTraits) {
            AdventureTraitEngine.observeCombat(state, eventAt)
            AdventureTraitEngine.finalizeEvidence(state, eventAt)
        }
        state.lastResult = if (isLabyrinthGateBoss) {
            val unlockedTitle = LabyrinthProgression.titleForCompletedDepth(labyrinthDepth)
            recentEvents += RecentAdventureEvent(
                occurredAt = eventAt,
                type = RecentAdventureEventType.TITLE_UNLOCKED,
                subjectName = unlockedTitle,
            )
            state.lastLootSummary = "${state.lastLootSummary} · $unlockedTitle 해금"
            "${defeated.name} 처치 · 경험치 +$xp · " +
                "$unlockedTitle 해금"
        } else {
            "${defeated.name} 처치 · 경험치 +$xp"
        }
    }

    private fun beginReturning(state: SimpleGameState, eventAt: Long) {
        state.adventurePhase = AdventurePhase.RETURNING
        state.combatPhase = CombatPhase.VICTORY
        state.totalReturns = safeIncrement(state.totalReturns)
        if (enableAdventureTraits) AdventureTraitEngine.beginReturn(state, eventAt)
        state.actionSequence = safeIncrement(state.actionSequence)
        state.actionStartedAt = eventAt
        state.actionEndsAt = safeAdd(eventAt, RETURN_TO_TOWN_MILLIS)
        state.lastTownItemName = ""
        state.lastTownItemRarity = ""
        state.lastTownGold = 0L
        state.pendingShopOffer = null
        state.lastShopPurchase = null
        state.shopAttemptedSlots.clear()
        clearLootPresentation(state)
        state.lastResult = "가방이 가득 차 마을로 귀환 중"
    }

    private fun beginCombat(state: SimpleGameState, rng: StableRng, eventAt: Long) {
        state.monster = createMonster(state, rng)
        if (enableAdventureTraits) AdventureTraitEngine.beginCombat(state, eventAt)
        state.adventurePhase = AdventurePhase.COMBAT
        state.combatPhase = CombatPhase.REVEAL
        state.actionStartedAt = eventAt
        val journey = state.adventureJourney
        val immediateAdjustment = journey.nextEncounterDelayAdjustmentMillis
        val routeAdjustment = if (immediateAdjustment > 0L) {
            journey.nextEncounterDelayAdjustmentMillis = 0L
            immediateAdjustment
        } else if (immediateAdjustment < 0L) {
            // A pre-queue save stored one shortcut in the scalar field. Apply it now and
            // preserve one more use so the upgraded reward meets the current two-use minimum.
            journey.nextEncounterDelayAdjustmentMillis = 0L
            journey.routeRewardDelayQueueMillis =
                listOf(immediateAdjustment) + journey.routeRewardDelayQueueMillis
            immediateAdjustment
        } else {
            val queued = journey.routeRewardDelayQueueMillis.firstOrNull() ?: 0L
            if (journey.routeRewardDelayQueueMillis.isNotEmpty()) {
                journey.routeRewardDelayQueueMillis = journey.routeRewardDelayQueueMillis.drop(1)
            }
            queued
        }
        val revealMillis = (StatBonusRules.encounterRevealMillis(state) + routeAdjustment).coerceAtLeast(1_000L)
        state.actionEndsAt = safeAdd(eventAt, revealMillis)
        state.lastTownItemName = ""
        state.lastTownItemRarity = ""
        state.lastTownGold = 0L
        state.pendingShopOffer = null
        state.lastShopPurchase = null
        state.shopAttemptedSlots.clear()
        clearLootPresentation(state)
    }

    private fun finishTownAction(
        state: SimpleGameState,
        rng: StableRng,
        eventAt: Long,
        recentEvents: MutableList<RecentAdventureEvent>,
    ) {
        if (state.adventurePhase == AdventurePhase.OPENING) {
            beginCombat(state, rng, eventAt)
            return
        }
        state.actionSequence = safeIncrement(state.actionSequence)
        when (state.adventurePhase) {
            AdventurePhase.OPENING -> Unit
            AdventurePhase.LOOTING -> finishLooting(state, rng, eventAt)
            AdventurePhase.RETURNING -> beginEquipmentSortingOrSelling(state, rng, eventAt, recentEvents)
            AdventurePhase.EQUIPPING -> beginSellingOrShopping(state, rng, eventAt)
            AdventurePhase.SELLING -> beginSellingOrShopping(state, rng, eventAt)
            AdventurePhase.SHOPPING -> buyEquipment(state, rng, eventAt, recentEvents)
            AdventurePhase.SHOPPING_RESULT -> beginShoppingOrDeparting(state, rng, eventAt)
            AdventurePhase.SHOPPING_EMPTY -> beginDeparting(state, eventAt)
            AdventurePhase.DEPARTING -> {
                if (!tryBeginAdventureEvent(state, eventAt, AdventureEventContext.OUTBOUND_ROUTE)) {
                    beginNextAdventureStep(state, rng, eventAt)
                }
            }
            AdventurePhase.EVENT,
            AdventurePhase.EVENT_RESULT,
            AdventurePhase.RELATIONSHIP,
            AdventurePhase.RELATIONSHIP_RESULT,
            AdventurePhase.COMBAT -> Unit
        }
    }

    private fun beginEquipmentSortingOrSelling(
        state: SimpleGameState,
        rng: StableRng,
        eventAt: Long,
        recentEvents: MutableList<RecentAdventureEvent>,
    ) {
        val equipmentLoot = state.inventory.count {
            it.kind == "장비" && it.equipmentSlot != null && it.equipmentPower != null
        }
        if (equipmentLoot == 0) {
            beginSellingOrShopping(state, rng, eventAt)
            return
        }

        val equippedNames = equipBestLoot(state, eventAt, recentEvents)
        if (equippedNames.isEmpty()) {
            beginSellingOrShopping(state, rng, eventAt)
            return
        }
        state.adventurePhase = AdventurePhase.EQUIPPING
        state.actionStartedAt = eventAt
        state.actionEndsAt = safeAdd(eventAt, EQUIP_LOOT_MILLIS)
        state.lastTownItemName = equippedNames.joinToString(", ")
        state.lastTownItemRarity = ""
        state.lastTownGold = equippedNames.size.toLong()
        state.lastResult = if (equippedNames.isEmpty()) {
            "현재 장비보다 나은 전리품이 없음"
        } else {
            "드롭 장비 ${equippedNames.size}부위 교체"
        }
    }

    private fun equipBestLoot(
        state: SimpleGameState,
        eventAt: Long,
        recentEvents: MutableList<RecentAdventureEvent>,
    ): List<String> {
        val equippedNames = mutableListOf<String>()
        EquipmentSlot.entries.forEach { slot ->
            val best = state.inventory.withIndex()
                .filter { (_, item) ->
                        item.kind == "장비" &&
                        item.equipmentSlot == slot &&
                        item.equipmentPower != null &&
                        (!enableAdventureTraits || item.id !in state.adventureTraits.familiarHeldItemIds)
                }
                .maxWithOrNull(
                    compareBy<IndexedValue<InventoryItem>> { it.value.equipmentPower ?: Long.MIN_VALUE }
                        .thenBy { rarityRank(it.value.rarity) },
                ) ?: return@forEach
            val current = state.equipment.first { it.slot == slot }
            val candidate = best.value
            val candidatePower = candidate.equipmentPower ?: return@forEach
            val isUpgrade = candidatePower > current.power ||
                (candidatePower == current.power && rarityRank(candidate.rarity) > rarityRank(current.rarity))
            if (!isUpgrade) return@forEach

            val previousName = current.name
            val previousPower = current.power
            val candidateOrigin = state.adventureTraits.primaryRewardOrigins[candidate.id] ?: "PRIMARY"
            val equippedOrigin = state.adventureTraits.equipmentOrigins[slot] ?: "PRIMARY"
            val replaced = InventoryItem(
                id = candidate.id,
                name = current.name,
                rarity = current.rarity,
                kind = "장비",
                foundAtLevel = current.acquiredAtLevel,
                equipmentSlot = current.slot,
                equipmentPower = current.power,
            )
            current.name = candidate.name
            current.power = candidatePower
            current.rarity = candidate.rarity
            current.acquiredAtLevel = state.hero.level.coerceAtLeast(1L)
            state.inventory[best.index] = replaced
            if (enableAdventureTraits) {
                state.adventureTraits.equipmentOrigins = state.adventureTraits.equipmentOrigins + (slot to candidateOrigin)
                state.adventureTraits.primaryRewardOrigins = state.adventureTraits.primaryRewardOrigins + (candidate.id to equippedOrigin)
            }
            state.totalLootEquipmentEquips = safeIncrement(state.totalLootEquipmentEquips)
            recentEvents += RecentAdventureEvent(
                occurredAt = eventAt,
                type = RecentAdventureEventType.EQUIPMENT_CHANGED,
                previousName = previousName,
                currentName = current.name,
                previousValue = previousPower,
                currentValue = current.power,
                equipmentSlot = slot,
                rarity = current.rarity,
            )
            equippedNames += current.name
        }
        return equippedNames
    }

    private fun beginSellingOrShopping(
        state: SimpleGameState,
        rng: StableRng,
        eventAt: Long,
    ) {
        if (enableAdventureTraits && state.adventureTraits.saleBatch == null && state.inventory.isNotEmpty()) {
            AdventureTraitEngine.beginSale(state, state.inventory.asReversed().map {
                it.id to StatBonusRules.saleValue(state, saleValue(it))
            }, eventAt)
        }
        val sold = state.inventory.removeLastOrNull()
        if (sold == null) {
            if (enableAdventureTraits) AdventureTraitEngine.finishSale(state, eventAt)
            if (!tryBeginAdventureEvent(state, eventAt, AdventureEventContext.TOWN_RETURN)) {
                beginShoppingOrDeparting(state, rng, eventAt)
            }
            return
        }
        val batch = if (enableAdventureTraits) state.adventureTraits.saleBatch else null
        val value = if (batch != null && batch.nextIndex < batch.itemIds.size && batch.itemIds[batch.nextIndex] == sold.id)
            batch.paidValues[batch.nextIndex++] else StatBonusRules.saleValue(state, saleValue(sold))
        state.hero.gold = safeAdd(state.hero.gold, value)
        state.totalItemsSold = safeIncrement(state.totalItemsSold)
        state.totalSaleGold = safeAdd(state.totalSaleGold, value)
        state.adventurePhase = AdventurePhase.SELLING
        state.actionStartedAt = eventAt
        state.actionEndsAt = safeAdd(eventAt, SELL_ITEM_MILLIS)
        if (batch != null) state.actionEndsAt = safeAdd(eventAt, scalePercent(SELL_ITEM_MILLIS, batch.durationPercent.toLong()).coerceAtLeast(1L))
        state.lastTownItemName = sold.name
        state.lastTownItemRarity = sold.rarity
        state.lastTownGold = value
        state.pendingShopOffer = null
        state.lastShopPurchase = null
        state.lastResult = "${sold.name} 판매 · +${value}G"
    }

    private fun beginShoppingOrDeparting(
        state: SimpleGameState,
        rng: StableRng,
        eventAt: Long,
    ) {
        if (enableAdventureTraits) AdventureTraitEngine.beginShop(state)
        val offer = createShopOffer(state, rng)
        state.lastShopPurchase = null
        if (offer != null) {
            state.pendingShopOffer = offer
            state.adventurePhase = AdventurePhase.SHOPPING
            state.actionStartedAt = eventAt
            state.actionEndsAt = safeAdd(eventAt, SHOP_OFFER_MILLIS)
            state.lastTownItemName = offer.name
            state.lastTownItemRarity = offer.rarity
            state.lastTownGold = offer.price
            state.lastResult = "${offer.name} 자동 구매 예정 · ${offer.price}G"
        } else {
            beginEmptyShopResult(state, eventAt)
        }
    }

    private fun beginEmptyShopResult(state: SimpleGameState, eventAt: Long) {
        state.pendingShopOffer = null
        state.lastShopPurchase = null
        state.adventurePhase = AdventurePhase.SHOPPING_EMPTY
        state.actionStartedAt = eventAt
        state.actionEndsAt = safeAdd(eventAt, SHOP_EMPTY_RESULT_MILLIS)
        if (enableAdventureTraits && state.adventureTraits.shopVisit?.justReviewedExtra == true) {
            state.actionEndsAt = safeAdd(state.actionEndsAt, SHOP_OFFER_MILLIS)
            state.adventureTraits.shopVisit?.justReviewedExtra = false
        }
        state.lastTownItemName = ""
        state.lastTownItemRarity = ""
        state.lastTownGold = 0L
        state.lastResult = "지금 살 수 있는 더 좋은 장비를 찾지 못했습니다"
    }

    private fun beginDeparting(state: SimpleGameState, eventAt: Long) {
        state.pendingShopOffer = null
        state.lastShopPurchase = null
        state.adventurePhase = AdventurePhase.DEPARTING
        state.actionStartedAt = eventAt
        val departureMillis = if (enableAdventureTraits) AdventureTraitEngine.depart(state, eventAt, DEPART_TO_FIELDS_MILLIS) else DEPART_TO_FIELDS_MILLIS
        state.actionEndsAt = safeAdd(eventAt, departureMillis)
        state.lastTownItemName = ""
        state.lastTownItemRarity = ""
        state.lastTownGold = 0L
        state.lastResult = "사냥터로 다시 출정 중"
    }

    private fun buyEquipment(
        state: SimpleGameState,
        rng: StableRng,
        eventAt: Long,
        recentEvents: MutableList<RecentAdventureEvent>,
    ) {
        val offer = state.pendingShopOffer ?: createShopOffer(state, rng)
        val current = offer?.let { pending ->
            state.equipment.firstOrNull { it.slot == pending.slot }
        }
        if (offer != null && current != null && state.hero.gold < offer.price) {
            state.pendingShopOffer = null
            beginShoppingOrDeparting(state, rng, eventAt)
            return
        }
        if (
            offer == null ||
            current == null ||
            current.power != offer.previousPower ||
            offer.newPower <= current.power ||
            rarityRank(offer.rarity) > SHOP_MAX_RARITY_RANK ||
            (enableAdventureTraits && (offer.price <= 0L || offer.price != equipmentPrice(state.hero.level, offer.slot) ||
                (rarityRank(current.rarity) > SHOP_MAX_RARITY_RANK && state.hero.level <= current.acquiredAtLevel)))
        ) {
            state.pendingShopOffer = null
            beginShoppingOrDeparting(state, rng, eventAt)
            return
        }

        val previousName = current.name
        val previousPower = current.power
        state.hero.gold -= offer.price
        current.name = offer.name
        current.power = offer.newPower
        current.rarity = offer.rarity
        current.acquiredAtLevel = state.hero.level.coerceAtLeast(1L)
        state.totalEquipmentPurchases = safeIncrement(state.totalEquipmentPurchases)
        if (enableAdventureTraits) {
            val visit = state.adventureTraits.shopVisit
            val derived = visit?.pendingIsExtra == true
            state.adventureTraits.equipmentOrigins = state.adventureTraits.equipmentOrigins +
                (current.slot to if (derived) "TRAIT_SHOP_EXTRA" else "PRIMARY")
            if (!derived) {
                if (visit != null) visit.basePurchases++
                val key = (visit?.sourceKey ?: state.adventureTraits.source!!.key) + ":compare:${current.slot}"
                AdventureTraitEngine.observeEquipment(state, key, current.slot, current.power, true, eventAt)
                AdventureTraitEngine.observe(state, key + ":paid", "trade:purchase:${current.slot}", setOf("S01"), setOf("S02"), eventAt, "trade:purchase")
            }
        }
        recentEvents += RecentAdventureEvent(
            occurredAt = eventAt,
            type = RecentAdventureEventType.EQUIPMENT_CHANGED,
            previousName = previousName,
            currentName = current.name,
            previousValue = previousPower,
            currentValue = current.power,
            equipmentSlot = current.slot,
            rarity = current.rarity,
        )
        state.pendingShopOffer = null
        state.lastShopPurchase = offer
        state.lastTownItemName = offer.name
        state.lastTownItemRarity = offer.rarity
        state.lastTownGold = offer.price
        state.lastResult = "${offer.name} 구매 · -${offer.price}G"
        state.adventurePhase = AdventurePhase.SHOPPING_RESULT
        state.actionStartedAt = eventAt
        state.actionEndsAt = safeAdd(eventAt, SHOP_RESULT_MILLIS)
    }

    private fun createShopOffer(state: SimpleGameState, rng: StableRng): ShopEquipmentOffer? {
        while (true) {
            val remainingSlots = EquipmentSlot.entries.filterNot(state.shopAttemptedSlots::contains)
            val visit = if (enableAdventureTraits) AdventureTraitEngine.beginShop(state) else null
            val extra = remainingSlots.isEmpty() && visit?.extraAllowed == true && !visit.extraUsed
            if (remainingSlots.isEmpty() && !extra) return null
            val candidateRng = if (extra) StableRng(AdventureTraitEngine.seedFor(state, visit!!.sourceKey + ":extraCandidate")) else rng
            val slot = if (extra) {
                visit?.extraSlot ?: EquipmentSlot.WEAPON
            } else {
                remainingSlots.pick(rng)
            }
            if (extra) {
                visit!!.extraUsed = true
                visit.pendingIsExtra = true
                visit.justReviewedExtra = true
                val reviewTraitId = visit.extraTraitId.ifBlank { "S05" }
                AdventureTraitEngine.activate(state, reviewTraitId, visit.sourceKey, state.actionEndsAt,
                    AdventureTraitEffectKind.SHOP_REVIEW, 0, 1, SHOP_OFFER_MILLIS)
            } else {
                state.shopAttemptedSlots += slot
                visit?.pendingIsExtra = false
            }
            val current = state.equipment.firstOrNull { it.slot == slot } ?: continue
            val rolledRarity = shopRarity(candidateRng)
            val previousNames = if (extra) state.recentItemNames.toMutableList() else null
            val candidate = equipmentCandidate(state, candidateRng, slot, rolledRarity, forShop = true)
            if (previousNames != null) state.recentItemNames = previousNames
            val comparisonKey = visit?.sourceKey.orEmpty() + ":compare:${slot.name}"
            if (enableAdventureTraits && !extra) AdventureTraitEngine.observe(state, comparisonKey,
                "equipment:${slot.name}", setOf("L05"), at = state.actionEndsAt, reason = "equipment:compare")
            val protectsHighRarityAtAcquisitionLevel =
                rarityRank(current.rarity) > SHOP_MAX_RARITY_RANK &&
                    state.hero.level <= current.acquiredAtLevel
            if (protectsHighRarityAtAcquisitionLevel || candidate.power <= current.power) {
                if (enableAdventureTraits && !extra) AdventureTraitEngine.observeEquipment(state, comparisonKey, slot, current.power, false, state.actionEndsAt)
                if (extra) return null else continue
            }

            val price = equipmentPrice(state.hero.level, slot)
            if (state.hero.gold < price) {
                if (enableAdventureTraits && !extra) AdventureTraitEngine.observeEquipment(state, comparisonKey, slot, current.power, false, state.actionEndsAt)
                if (extra) return null else continue
            }
            if (extra) visit?.justReviewedExtra = false
            return ShopEquipmentOffer(
                slot = slot,
                name = candidate.name,
                rarity = candidate.rarity,
                previousPower = current.power,
                newPower = candidate.power,
                price = price,
            )
        }
    }

    private fun saleValue(item: InventoryItem): Long = safeMul(
        safeMul(item.foundAtLevel.coerceAtLeast(1L), GOLD_UNIT),
        rarityRank(item.rarity).toLong() + 1L,
    )

    internal fun saleValueForTest(item: InventoryItem): Long = saleValue(item)

    fun equipmentPrice(level: Long): Long {
        val safeLevel = level.coerceAtLeast(1L)
        // Sale value and bag capacity both increase with level, so prices follow the same
        // quadratic progression instead of letting long-running automatic hunts hoard gold.
        return safeMul(
            GOLD_UNIT,
            safeMul(SHOP_PRICE_PER_LEVEL_SQUARED, safeMul(safeLevel, safeLevel)),
        )
    }

    fun equipmentPrice(level: Long, slot: EquipmentSlot): Long =
        roundUpToGoldUnit(
            scalePercentRounded(equipmentPrice(level), equipmentPricePercent(slot)),
        )

    internal fun equipmentPricePercent(slot: EquipmentSlot): Long = when (slot) {
        EquipmentSlot.WEAPON -> 150L
        EquipmentSlot.BODY -> 120L
        EquipmentSlot.HEAD -> 100L
        EquipmentSlot.HANDS -> 90L
        EquipmentSlot.FEET -> 90L
        EquipmentSlot.ACCESSORY -> 80L
    }

    fun averageEquipmentPower(state: SimpleGameState): Long {
        if (state.equipment.isEmpty()) return 0L
        val count = state.equipment.size.toLong()
        var quotientSum = 0L
        var remainderSum = 0L
        state.equipment.forEach { item ->
            val power = item.power.coerceAtLeast(0L)
            quotientSum = safeAdd(quotientSum, power / count)
            remainderSum = safeAdd(remainderSum, power % count)
        }
        return safeAdd(
            quotientSum,
            safeAdd(remainderSum, count / 2L) / count,
        )
    }

    fun characterStatPower(state: SimpleGameState): Long {
        val weightedStatTenths = weightedCombatStatTenths(
            stats = state.hero.stats,
            heroClass = state.hero.heroClass,
        )
        if (weightedStatTenths == 0L) return 0L

        val expectedStatThirtieths = expectedWeightedStatThirtieths(
            level = state.hero.level,
            classGuidedLevelGrowths = state.classGuidedLevelGrowths,
        )
        return normalizedStatCombatPower(
            weightedStatTenths = weightedStatTenths,
            expectedStatThirtieths = expectedStatThirtieths,
            equipmentBenchmark = expectedEquipmentCombatPower(state.hero.level),
        )
    }

    internal fun expectedWeightedStatThirtieths(
        level: Long,
        classGuidedLevelGrowths: Long = levelProgress(level),
    ): Long {
        val totalLevelGrowths = levelProgress(level)
        val guidedGrowths = classGuidedLevelGrowths.coerceIn(0L, totalLevelGrowths)
        val legacyGrowths = totalLevelGrowths - guidedGrowths
        return safeAdd(
            BASE_EXPECTED_STAT_THIRTIETHS,
            safeAdd(
                safeMul(legacyGrowths, LEGACY_EXPECTED_STAT_GROWTH_THIRTIETHS),
                safeMul(guidedGrowths, EXPECTED_STAT_GROWTH_THIRTIETHS),
            ),
        )
    }

    fun displayCombatPower(state: SimpleGameState): Long =
        safeAdd(characterStatPower(state), averageEquipmentPower(state))

    /** Deterministic midpoint damage used only by the debug skill-effect viewer. */
    internal fun skillPreviewDamage(state: SimpleGameState, catalogId: String): Long {
        val definition = SkillCatalog.find(catalogId) ?: return baseAttackDamage(state)
        val learned = state.skills.firstOrNull { it.catalogId == catalogId }
        val midpointPercent = (definition.damagePercentMin + definition.damagePercentMax) / 2L
        return scalePercent(
            baseAttackDamage(state),
            safeAdd(midpointPercent, learned?.damageBonusPercent ?: 0L),
        )
            .coerceAtLeast(1L)
    }

    internal fun expectedEquipmentCombatPower(level: Long): Long = safeAdd(
        BASE_EXPECTED_EQUIPMENT_POWER,
        safeMul(
            levelProgress(level),
            EXPECTED_EQUIPMENT_POWER_PER_LEVEL,
        ),
    )

    private fun equipmentLevelPowerBase(level: Long): Long =
        (expectedEquipmentCombatPower(level) - BASE_EXPECTED_EQUIPMENT_POWER).coerceAtLeast(0L)

    internal fun expectedCombatPower(state: SimpleGameState): Long =
        safeMul(expectedEquipmentCombatPower(state.hero.level), 2L)

    fun combatDurationPercent(state: SimpleGameState): Int {
        val expectedPower = expectedCombatPower(state).coerceAtLeast(1L).toDouble()
        val actualPower = displayCombatPower(state).coerceAtLeast(0L).toDouble()
        val relativeDifference = (actualPower - expectedPower) / expectedPower
        return (100.0 - relativeDifference * 50.0)
            .roundToInt()
            .coerceIn(MIN_COMBAT_DURATION_PERCENT, MAX_COMBAT_DURATION_PERCENT)
    }

    internal fun attackCountForCombatPower(state: SimpleGameState, baseAttacks: Int): Int {
        val scaled = (baseAttacks.toLong() * combatDurationPercent(state).toLong() + 50L) / 100L
        return scaled.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    }

    /** Grade controls time and danger; the event outcome alone controls its recovery budget. */
    internal fun eventBattleGoldPerLevel(outcome: com.nullplaying.model.AdventureEventOutcome): Long = when (outcome) {
        com.nullplaying.model.AdventureEventOutcome.SUCCESS -> 20L
        com.nullplaying.model.AdventureEventOutcome.PARTIAL -> 4L
        com.nullplaying.model.AdventureEventOutcome.FAILURE -> 1L
    }

    private fun grantExperience(
        state: SimpleGameState,
        amount: Long,
        rng: StableRng,
        eventAt: Long,
        recentEvents: MutableList<RecentAdventureEvent>,
    ) {
        state.hero.experience = safeAdd(state.hero.experience, amount)
        while (state.hero.experience >= experienceRequired(state.hero.level)) {
            val required = experienceRequired(state.hero.level)
            state.hero.experience -= required
            val previousLevel = state.hero.level
            state.hero.level = safeIncrement(state.hero.level)
            val statsBefore = state.hero.stats.copy()
            applyClassGuidedGrowth(state.hero.stats, state.hero.heroClass, rng)
            state.classGuidedLevelGrowths = safeIncrement(state.classGuidedLevelGrowths)
            recentEvents += RecentAdventureEvent(
                occurredAt = eventAt,
                type = RecentAdventureEventType.LEVEL_UP,
                contextName = RecentAdventureEventMetadata.encodeStatGrowth(
                    before = statsBefore,
                    after = state.hero.stats,
                ),
                previousValue = previousLevel,
                currentValue = state.hero.level,
            )
            learnSkillIfNeeded(state)?.let { learned ->
                recentEvents += RecentAdventureEvent(
                    occurredAt = eventAt,
                    type = RecentAdventureEventType.SKILL_LEARNED,
                    subjectId = learned.catalogId,
                    subjectName = learned.name,
                    currentValue = state.hero.level,
                )
            }
        }
    }

    private fun applyClassGuidedGrowth(
        stats: HeroStats,
        heroClass: HeroClass,
        rng: StableRng,
    ) {
        stats.maxHealth = safeAdd(
            stats.maxHealth,
            safeAdd(stats.constitution / 3L + 1L, rng.nextInt(4).toLong()),
        )
        stats.maxMana = safeAdd(
            stats.maxMana,
            safeAdd(
                manaBaseAttribute(stats.intelligence, stats.wisdom) / 3L + 1L,
                rng.nextInt(4).toLong(),
            ),
        )
        val (primaryIndex, secondaryIndex) = combatStatIndices(heroClass)
        stats.increment(if (rng.nextInt(2) == 0) primaryIndex else secondaryIndex)
        stats.increment(rng.nextInt(PRIMARY_STAT_COUNT))
    }

    private fun combatStatIndices(heroClass: HeroClass): Pair<Int, Int> =
        heroClass.primaryStatIndex to heroClass.secondaryStatIndex

    private fun combatStatValues(
        stats: HeroStats,
        heroClass: HeroClass,
    ): Pair<Long, Long> {
        val values = stats.values().take(PRIMARY_STAT_COUNT)
        val (primaryIndex, secondaryIndex) = combatStatIndices(heroClass)
        return values[primaryIndex].coerceAtLeast(0L) to
            values[secondaryIndex].coerceAtLeast(0L)
    }

    private fun weightedCombatStatTenths(
        stats: HeroStats,
        heroClass: HeroClass,
    ): Long {
        val (primary, secondary) = combatStatValues(stats, heroClass)
        return safeAdd(
            safeMul(primary, PRIMARY_STAT_WEIGHT),
            safeMul(secondary, SECONDARY_STAT_WEIGHT),
        )
    }

    internal fun classOffenseAttribute(
        stats: HeroStats,
        heroClass: HeroClass,
    ): Long {
        val (primary, secondary) = combatStatValues(stats, heroClass)
        val whole = safeAdd(
            safeMul(primary / 10L, PRIMARY_STAT_WEIGHT),
            safeMul(secondary / 10L, SECONDARY_STAT_WEIGHT),
        )
        val remainderTenths = safeAdd(
            safeMul(primary % 10L, PRIMARY_STAT_WEIGHT),
            safeMul(secondary % 10L, SECONDARY_STAT_WEIGHT),
        )
        return safeAdd(whole, safeAdd(remainderTenths, 5L) / 10L)
    }

    private fun learnSkillIfNeeded(state: SimpleGameState): LearnedSkill? {
        if (state.hero.level % 5L != 0L || state.skills.size >= MAX_SKILLS) return null
        ensureSkillCatalogSeed(state)
        val tier = (state.hero.level / 5L + 1L).toInt()
        if (tier !in 2..MAX_SKILLS || state.skills.any { it.id == tier }) return null
        val definition = SkillCatalog.select(state.skillCatalogSeed, state.hero.heroClass, tier)
        val learned = LearnedSkill(
            id = tier,
            name = definition.name,
            acquiredAtLevel = state.hero.level,
            description = definition.description,
            catalogId = definition.catalogId,
        )
        state.skills += learned
        return learned
    }

    private fun advanceTaleOnVictory(
        state: SimpleGameState,
        gameplayRng: StableRng,
        defeated: MonsterState,
        eventAt: Long,
        recentEvents: MutableList<RecentAdventureEvent>,
    ) {
        val tale = state.adventureTale
        val act = tale.activeAct()
        if (!advanceActProgress(state, canCompleteAct = defeated.grade == MonsterGrade.BOSS)) return

        act.completed = true
        state.totalActs = safeIncrement(state.totalActs)
        val goldBefore = state.hero.gold
        state.hero.gold = safeAdd(state.hero.gold, act.rewardGold)
        val goldAwarded = state.hero.gold - goldBefore
        grantExperience(state, act.rewardExperience, gameplayRng, eventAt, recentEvents)

        if (tale.currentActIndex < tale.acts.lastIndex) {
            recentEvents += RecentAdventureEvent(
                occurredAt = eventAt,
                type = RecentAdventureEventType.QUEST_COMPLETED,
                subjectId = act.id,
                subjectName = act.title,
                contextName = tale.title,
                previousValue = act.rewardExperience,
                currentValue = goldAwarded,
            )
            tale.currentActIndex += 1
            return
        }

        state.totalTales = safeIncrement(state.totalTales)
        applyClassGuidedGrowth(state.hero.stats, state.hero.heroClass, gameplayRng)
        if (tale.kind == TaleKind.LABYRINTH) {
            state.labyrinthDepthCompleted = maxOf(
                state.labyrinthDepthCompleted,
                tale.labyrinthDepth.coerceAtLeast(1L),
            )
        }
        state.completedTaleHistory += CompletedTaleRecord(
            taleSequence = tale.sequence,
            taleId = tale.definitionId,
            kind = tale.kind,
            volumeNumber = tale.volumeNumber,
            chapterNumber = tale.chapterNumber,
            title = tale.title,
            summary = tale.ending,
            nextHook = tale.nextHook,
            actMemories = tale.acts.map { it.completionBody },
            completedAtLevel = state.hero.level,
            labyrinthDepth = tale.labyrinthDepth,
        )
        recentEvents += RecentAdventureEvent(
            occurredAt = eventAt,
            type = RecentAdventureEventType.TALE_COMPLETED,
            subjectId = tale.definitionId,
            subjectName = tale.title,
            contextName = tale.volumeTitle,
        )
        trimRepeatableTaleHistory(state)

        val taleRng = StableRng(state.taleRngState)
        val currentDefinition = AdventureTaleCatalog.requireDefinition(tale.definitionId)
        val nextDefinition = AdventureTaleCatalog.nextDefinition(currentDefinition, tale.sequence)
        state.adventureTale = createAdventureTale(
            definition = nextDefinition,
            sequence = safeIncrement(tale.sequence),
            level = state.hero.level,
            heroName = state.hero.name,
            rng = taleRng,
            labyrinthDepth = if (nextDefinition.kind == TaleKind.LABYRINTH) {
                safeIncrement(state.labyrinthDepthCompleted)
            } else {
                0L
            },
        )
        state.taleRngState = taleRng.state
    }

    private fun createAdventureTale(
        definition: AdventureTaleDefinition,
        sequence: Long,
        level: Long,
        heroName: String,
        rng: StableRng,
        labyrinthDepth: Long = 0L,
    ): AdventureTaleState {
        val variant = AdventureTaleCatalog.variantAt(rng.nextInt(AdventureTaleCatalog.variantCount()))
        return AdventureTaleCatalog.instantiate(
            definition = definition,
            sequence = sequence,
            heroName = heroName,
            heroLevel = level,
            variant = variant,
            labyrinthDepth = labyrinthDepth,
        )
    }

    private fun trimRepeatableTaleHistory(state: SimpleGameState) {
        while (
            state.completedTaleHistory.count {
                it.kind == TaleKind.LABYRINTH &&
                    !LabyrinthProgression.isGateDepth(it.labyrinthDepth)
            } > MAX_LABYRINTH_HISTORY
        ) {
            val oldestLabyrinth = state.completedTaleHistory.indexOfFirst {
                it.kind == TaleKind.LABYRINTH &&
                    !LabyrinthProgression.isGateDepth(it.labyrinthDepth)
            }
            if (oldestLabyrinth < 0) return
            state.completedTaleHistory.removeAt(oldestLabyrinth)
        }
        while (
            state.completedTaleHistory.count {
                it.kind == TaleKind.LABYRINTH &&
                    LabyrinthProgression.isGateDepth(it.labyrinthDepth)
            } > MAX_LABYRINTH_GATE_HISTORY
        ) {
            val oldestGate = state.completedTaleHistory.indexOfFirst {
                it.kind == TaleKind.LABYRINTH &&
                    LabyrinthProgression.isGateDepth(it.labyrinthDepth)
            }
            if (oldestGate < 0) return
            state.completedTaleHistory.removeAt(oldestGate)
        }
    }

    private fun createMonster(state: SimpleGameState, rng: StableRng): MonsterState {
        val levelDelta = rng.nextInt(5) - 2
        val tale = state.adventureTale
        val labyrinthDepth = tale.labyrinthDepth
        val level = safeAdd(
            (state.hero.level + levelDelta).coerceAtLeast(1L),
            if (tale.kind == TaleKind.LABYRINTH) {
                LabyrinthProgression.monsterLevelBonus(labyrinthDepth)
            } else {
                0L
            },
        )
        val actIndex = tale.currentActIndex.coerceIn(0, tale.acts.lastIndex)
        val act = tale.acts[actIndex]
        val grade = QuestMonsterCatalog.encounterGrade(act.progress, act.target)
        val isLabyrinthGateBoss = tale.kind == TaleKind.LABYRINTH &&
            LabyrinthProgression.isGateDepth(labyrinthDepth) &&
            grade == MonsterGrade.BOSS &&
            actIndex == tale.acts.lastIndex
        val baseAttacks = grade.minAttacks + rng.nextInt(grade.maxAttacks - grade.minAttacks + 1)
        val group = QuestMonsterCatalog.groupFor(tale.definitionId)
        var catalogId = ""
        var baseName = ""
        val rawEncounterName = when {
            group == null -> uniqueName(state.recentMonsterNames, RECENT_MONSTERS, rng) {
                val modifier = SimpleContent.monsterAdjectives.pick(rng)
                val kind = SimpleContent.monsterKinds.pick(rng)
                baseName = kind
                "$modifier $kind"
            }

            grade == MonsterGrade.NORMAL -> {
                val pool = group.normals.take(QuestMonsterCatalog.normalPoolSize(actIndex))
                uniqueName(state.recentMonsterNames, RECENT_MONSTERS, rng) {
                    val definition = pool.pick(rng)
                    catalogId = definition.id
                    baseName = definition.baseName
                    "${group.adjectives.pick(rng)} ${definition.baseName}"
                }
            }

            grade == MonsterGrade.ELITE -> {
                val definition = group.elites[QuestMonsterCatalog.eliteIndex(act.progress, act.target)]
                catalogId = definition.id
                baseName = definition.baseName
                definition.baseName
            }

            else -> {
                val definition = group.bosses[actIndex]
                catalogId = definition.id
                baseName = definition.baseName
                definition.baseName
            }
        }
        val modifierCandidates = when {
            group == null -> SimpleContent.monsterAdjectives
            grade == MonsterGrade.NORMAL -> group.adjectives
            else -> emptyList()
        }
        val encounterName = if (baseName.isNotBlank() && modifierCandidates.isNotEmpty()) {
            MonsterModifierCompatibility.repairedName(
                sourceName = rawEncounterName,
                baseName = baseName,
                candidates = modifierCandidates,
            )
        } else {
            rawEncounterName
        }
        val name = if (isLabyrinthGateBoss) {
            "제${labyrinthDepth}구역 관문지기 · $encounterName"
        } else {
            encounterName
        }
        val depthAdjustedAttacks = if (tale.kind == TaleKind.LABYRINTH) {
            LabyrinthProgression.targetAttacks(
                depth = labyrinthDepth,
                baseAttacks = baseAttacks,
                isGateBoss = isLabyrinthGateBoss,
            )
        } else {
            baseAttacks
        }
        val targetAttacks = attackCountForCombatPower(state, depthAdjustedAttacks)
        val monsterEnergy = monsterEnergyFor(state, targetAttacks)
        return MonsterState(
            id = safeIncrement(state.totalKills),
            name = name,
            level = level,
            maxEnergy = monsterEnergy,
            grade = grade,
            currentEnergy = monsterEnergy,
            expectedAttacks = targetAttacks,
            attacksCompleted = 0,
            catalogId = catalogId,
            baseName = baseName.ifBlank { name },
            isFinalBoss = grade == MonsterGrade.BOSS && actIndex == tale.acts.lastIndex,
            isLabyrinthGateBoss = isLabyrinthGateBoss,
        )
    }

    /**
     * Rebuilds an active legacy encounter with real damage-scaled energy. The original action
     * boundary is replaced with the first reveal boundary so no partially simulated legacy hit
     * can be counted twice.
     */
    private fun normalizeLegacyCombat(
        state: SimpleGameState,
        rng: StableRng,
        savedSchema: Int = state.schemaVersion,
    ) {
        val storyRebased = synchronizeCatalogs(state, savedSchema)
        synchronizePendingShopOffer(state, rng)
        if (storyRebased) state.monster = createMonster(state, rng)
        val needsCombatRebaseline =
            savedSchema < ACTIVE_COMBAT_REBASE_SCHEMA_VERSION &&
                state.adventurePhase == AdventurePhase.COMBAT
        val needsMonsterEnergyRebaseline =
            savedSchema < MONSTER_ENERGY_REBASE_SCHEMA_VERSION &&
                state.adventurePhase == AdventurePhase.COMBAT
        val needsDamageEnergyRebaseline =
            savedSchema < DAMAGE_ENERGY_SCHEMA_VERSION &&
                state.adventurePhase == AdventurePhase.COMBAT
        val needsClassOffenseRebaseline =
            shouldRebaselineClassOffense(state, savedSchema)
        val invalidTimeline = state.monster.maxEnergy <= 0L ||
            state.monster.currentEnergy !in 0L..state.monster.maxEnergy ||
            state.monster.expectedAttacks <= 0 ||
            state.monster.attacksCompleted < 0 ||
            needsCombatRebaseline ||
            needsMonsterEnergyRebaseline ||
            needsDamageEnergyRebaseline ||
            needsClassOffenseRebaseline ||
            storyRebased
        if (!invalidTimeline) return

        val grade = state.monster.grade
        val baseAttacks = grade.minAttacks + rng.nextInt(grade.maxAttacks - grade.minAttacks + 1)
        val depthAdjustedAttacks = if (state.adventureTale.kind == TaleKind.LABYRINTH) {
            LabyrinthProgression.targetAttacks(
                depth = state.adventureTale.labyrinthDepth,
                baseAttacks = baseAttacks,
                isGateBoss = state.monster.isLabyrinthGateBoss,
            )
        } else {
            baseAttacks
        }
        val targetAttacks = attackCountForCombatPower(
            state,
            depthAdjustedAttacks,
        )
        val monsterEnergy = monsterEnergyFor(state, targetAttacks)
        state.monster.maxEnergy = monsterEnergy
        state.monster.currentEnergy = monsterEnergy
        state.monster.expectedAttacks = targetAttacks
        state.monster.attacksCompleted = 0
        state.combatPhase = CombatPhase.REVEAL
        state.actionStartedAt = state.lastSettledAt
                state.actionEndsAt = safeAdd(state.lastSettledAt, StatBonusRules.encounterRevealMillis(state))
        clearAttackPresentation(state)
    }

    private fun shouldRebaselineClassOffense(
        state: SimpleGameState,
        savedSchema: Int,
    ): Boolean =
        savedSchema < CLASS_PRIMARY_SECONDARY_OFFENSE_SCHEMA_VERSION &&
            state.adventurePhase == AdventurePhase.COMBAT

    private fun synchronizePendingShopOffer(state: SimpleGameState, rng: StableRng) {
        if (state.adventurePhase != AdventurePhase.SHOPPING) return
        state.pendingShopOffer?.let { pending ->
            if (pending.slot !in state.shopAttemptedSlots) {
                state.shopAttemptedSlots += pending.slot
            }
            return
        }
        val offer = createShopOffer(state, rng)
        if (offer == null) {
            beginEmptyShopResult(state, state.lastSettledAt)
            return
        }
        state.pendingShopOffer = offer
        state.lastShopPurchase = null
        state.lastTownItemName = offer.name
        state.lastTownItemRarity = offer.rarity
        state.lastTownGold = offer.price
        state.lastResult = "${offer.name} 자동 구매 예정 · ${offer.price}G"
    }

    private fun synchronizeCatalogs(state: SimpleGameState, savedSchema: Int): Boolean {
        val stateSchema = state.schemaVersion
        var storyRebased = false
        if (savedSchema < STORY_EXPANSION_SCHEMA_VERSION && stateSchema < STORY_EXPANSION_SCHEMA_VERSION) {
            storyRebased = migrateExpandedMainStory(state)
            if (!storyRebased) {
                migrateProgressionTargets(
                    state = state,
                    scaleRewards = savedSchema < HUNTING_PACING_SCHEMA_VERSION,
                )
            }
        }
        if (savedSchema < POSTGAME_JOURNEY_SCHEMA_VERSION && stateSchema < POSTGAME_JOURNEY_SCHEMA_VERSION) {
            storyRebased = migratePostgameJourney(state) || storyRebased
        }
        if (savedSchema < EXPERIENCE_CURVE_SCHEMA_VERSION && stateSchema < EXPERIENCE_CURVE_SCHEMA_VERSION) {
            migrateExperienceProgress(state, savedSchema)
        }
        if (savedSchema < ATTACK_SKILL_CATALOG_SCHEMA_VERSION) {
            synchronizeAttackSkillCatalog(state)
        }
        if (
            savedSchema < SIGNATURE_SKILL_CATALOG_SCHEMA_VERSION ||
            state.skillCatalogSeed == 0L ||
            state.skills.any { it.catalogId.isBlank() || SkillCatalog.find(it.catalogId) == null } ||
            state.skills.size != expectedSkillCount(state.hero.level)
        ) {
            synchronizeClassSkillCatalog(state)
        }
        synchronizeSkillDisplayNames(state)
        if (savedSchema < DISPLAY_NAME_SCHEMA_VERSION) {
            synchronizeEquipmentNames(state)
            synchronizeMonsterNames(state)
        }
        if (savedSchema < CLASS_TERMINOLOGY_SCHEMA_VERSION) {
            synchronizeClassEquipmentTerminology(state)
        }
        if (savedSchema < SHOP_POWER_FIRST_SCHEMA_VERSION) {
            migrateShopPowerFirstState(state)
        }
        if (savedSchema < QUADRATIC_SHOP_PRICE_SCHEMA_VERSION) {
            migrateShopPriceCurveState(state)
        }
        if (
            savedSchema < EQUIPMENT_POWER_ALIGNMENT_SCHEMA_VERSION &&
                stateSchema < EQUIPMENT_POWER_ALIGNMENT_SCHEMA_VERSION
        ) {
            migrateEquipmentPowerAlignmentState(state)
        }
        if (savedSchema < DAWN_BELL_STORY_SCHEMA_VERSION) {
            synchronizeDawnBellStoryContent(state)
        }
        if (
            savedSchema < MAIN_ACT_RHYTHM_SCHEMA_VERSION &&
                stateSchema < MAIN_ACT_RHYTHM_SCHEMA_VERSION
        ) {
            migrateProgressionTargets(state = state, scaleRewards = false)
        }
        if (
            savedSchema < LABYRINTH_PROGRESSION_SCHEMA_VERSION &&
                stateSchema < LABYRINTH_PROGRESSION_SCHEMA_VERSION
        ) {
            storyRebased = migrateLabyrinthProgression(state) || storyRebased
        }
        state.consecutiveBasicAttacks = if (savedSchema < SKILL_PROC_SCHEMA_VERSION) {
            0
        } else {
            state.consecutiveBasicAttacks.coerceIn(0, MAX_CONSECUTIVE_BASIC_ATTACKS)
        }
        if (savedSchema < WEIGHTED_SKILL_SELECTION_SCHEMA_VERSION) {
            state.lastActivatedSkillCatalogId = ""
        }
        state.schemaVersion = CURRENT_SCHEMA_VERSION
        return storyRebased
    }

    /**
     * Schema 32 repeated six epilogues forever. Keep each authored completion once, then move an
     * active repeat to the first unseen epilogue while preserving its five-act time investment.
     */
    private fun migratePostgameJourney(state: SimpleGameState): Boolean {
        state.labyrinthDepthCompleted = 0L
        val epiloguesById = AdventureTaleCatalog.epilogues.associateBy { it.id }
        val retainedEpilogueIds = mutableSetOf<String>()
        state.completedTaleHistory = state.completedTaleHistory.mapNotNull { record ->
            val definition = epiloguesById[record.taleId]
            when {
                record.kind != TaleKind.EPILOGUE || definition == null -> record
                !retainedEpilogueIds.add(record.taleId) -> null
                else -> record.copy(
                    volumeNumber = definition.volumeNumber,
                    chapterNumber = definition.chapterNumber,
                    title = definition.title,
                    labyrinthDepth = 0L,
                )
            }
        }.toMutableList()

        val current = state.adventureTale
        if (current.kind != TaleKind.EPILOGUE) return false
        val completedIds = state.completedTaleHistory
            .asSequence()
            .filter { it.kind == TaleKind.EPILOGUE }
            .map { it.taleId }
            .toSet()
        val currentDefinition = epiloguesById[current.definitionId]
        val targetDefinition = if (currentDefinition != null && current.definitionId !in completedIds) {
            currentDefinition
        } else {
            AdventureTaleCatalog.epilogues.firstOrNull { it.id !in completedIds }
                ?: AdventureTaleCatalog.labyrinths.first()
        }
        val refreshed = AdventureTaleCatalog.instantiate(
            definition = targetDefinition,
            sequence = current.sequence,
            heroName = state.hero.name,
            heroLevel = state.hero.level,
            variant = current.variant,
            labyrinthDepth = if (targetDefinition.kind == TaleKind.LABYRINTH) 1L else 0L,
        )
        transferTaleProgress(current, refreshed)
        state.adventureTale = refreshed
        return state.adventurePhase == AdventurePhase.COMBAT
    }

    private fun transferTaleProgress(
        previous: AdventureTaleState,
        refreshed: AdventureTaleState,
        preserveRewards: Boolean = true,
    ) {
        refreshed.currentActIndex = previous.currentActIndex.coerceIn(0, refreshed.acts.lastIndex)
        refreshed.acts.indices.forEach { index ->
            val old = previous.acts.getOrNull(index) ?: return@forEach
            val next = refreshed.acts[index]
            val wasCompleted = old.completed || index < refreshed.currentActIndex
            val progress = if (wasCompleted) {
                next.target
            } else {
                val oldTarget = old.target.coerceAtLeast(1L)
                val ratioProgress = (
                    old.progress.coerceAtLeast(0L).toDouble() /
                        oldTarget.toDouble() * next.target.toDouble()
                    ).toLong()
                val upperBound = if (index == refreshed.currentActIndex) {
                    (next.target - 1L).coerceAtLeast(0L)
                } else {
                    next.target
                }
                ratioProgress.coerceIn(0L, upperBound)
            }
            refreshed.acts[index] = next.copy(
                progress = progress,
                rewardExperience = if (preserveRewards) {
                    old.rewardExperience
                } else {
                    next.rewardExperience
                },
                rewardGold = if (preserveRewards) old.rewardGold else next.rewardGold,
                completed = wasCompleted,
            )
        }
    }

    /**
     * Schema 37 stored every labyrinth act as 1,000 hunts. Refresh the active depth with
     * depth-aware targets and rewards while retaining the same within-act completion ratio.
     */
    private fun migrateLabyrinthProgression(state: SimpleGameState): Boolean {
        val previous = state.adventureTale
        if (previous.kind != TaleKind.LABYRINTH) return false
        val depth = previous.labyrinthDepth.takeIf { it > 0L }
            ?: safeIncrement(state.labyrinthDepthCompleted)
        val definition = AdventureTaleCatalog.requireDefinition(previous.definitionId)
        val refreshed = AdventureTaleCatalog.instantiate(
            definition = definition,
            sequence = previous.sequence,
            heroName = state.hero.name,
            heroLevel = state.hero.level,
            variant = previous.variant,
            labyrinthDepth = depth,
        )
        transferTaleProgress(previous, refreshed, preserveRewards = false)
        state.adventureTale = refreshed
        return state.adventurePhase == AdventurePhase.COMBAT
    }

    private fun synchronizeDawnBellStoryContent(state: SimpleGameState) {
        val currentTale = state.adventureTale
        if (currentTale.definitionId in DAWN_BELL_TALE_IDS) {
            val definition = AdventureTaleCatalog.find(currentTale.definitionId)
            if (definition != null) {
                val refreshed = AdventureTaleCatalog.instantiate(
                    definition = definition,
                    sequence = currentTale.sequence,
                    heroName = state.hero.name,
                    heroLevel = state.hero.level,
                    variant = currentTale.variant,
                )
                refreshed.currentActIndex = currentTale.currentActIndex
                    .coerceIn(0, refreshed.acts.lastIndex)
                refreshed.acts.indices.forEach { index ->
                    val newAct = refreshed.acts[index]
                    val previous = currentTale.acts.firstOrNull { it.id == newAct.id }
                        ?: return@forEach
                    refreshed.acts[index] = newAct.copy(
                        progress = previous.progress,
                        target = previous.target,
                        rewardExperience = previous.rewardExperience,
                        rewardGold = previous.rewardGold,
                        completed = previous.completed,
                    )
                }
                state.adventureTale = refreshed
            }
        }

        state.completedTaleHistory = state.completedTaleHistory.map { record ->
            if (record.taleId !in DAWN_BELL_TALE_IDS) {
                record
            } else {
                record.copy(
                    title = modernizeDawnBellStoryText(record.title),
                    summary = modernizeDawnBellStoryText(record.summary),
                    nextHook = modernizeDawnBellStoryText(record.nextHook),
                    actMemories = record.actMemories.map(::modernizeDawnBellStoryText),
                )
            }
        }.toMutableList()

        val previousBaseName = state.monster.baseName
        val catalogBaseName = QuestMonsterCatalog.definition(state.monster.catalogId)?.baseName
        if (!catalogBaseName.isNullOrBlank()) {
            state.monster.baseName = catalogBaseName
            if (previousBaseName.isNotBlank()) {
                state.monster.name = state.monster.name.replace(previousBaseName, catalogBaseName)
            }
        }
        state.monster.name = modernizeDawnBellStoryText(state.monster.name)
        state.monster.baseName = modernizeDawnBellStoryText(state.monster.baseName)
        state.recentMonsterNames = state.recentMonsterNames
            .map(::modernizeDawnBellStoryText)
            .toMutableList()
        state.lastResult = modernizeDawnBellStoryText(state.lastResult)
    }

    private fun modernizeDawnBellStoryText(value: String): String = value
        .replace("종혀 삼키는 자", "새벽종을 삼킨 자")
        .replace("첫 번째 종혀의 포식자", "첫 번째 새벽종을 삼킨 자")
        .replace("종혀 구멍", "벽 틈")
        .replace("종혀로 열린", "종소리로 열린")
        .replace("종혀 홈에", "벽화 아래")
        .replace("종혀 상자", "새벽종 상자")
        .replace("종혀 탈취자", "새벽종 탈취자")
        .replace("두 번째 종혀의 약탈자", "두 번째 새벽종을 훔친 자")
        .replace("마지막 종혀의 포식자", "마지막 새벽종을 지키는 자")
        .replace("종혀 피라미", "종소리 피라미")
        .replace("세 종혀를 흘어 놓는 자", "세 새벽종을 흩어 놓는 자")
        .replace("종혀가 여는 길", "종소리가 여는 길")
        .replace("첫 종혀로 비밀 계단을 열어", "첫 번째 새벽종을 울려 비밀 계단을 열고")
        .replace("첫 번째 종혀를 홈에 대자", "첫 번째 새벽종을 울리자")
        .replace("마지막 종혀를 끼우며", "마지막 새벽종을 울리며")
        .replace("마지막 종혀를 끼웠다", "마지막 새벽종을 울렸다")
        .replace("마지막 종혀를 끼워", "마지막 새벽종을 울려")
        .replace("세 개의 종혀", "되찾은 세 새벽종")
        .replace("종혀", "새벽종")
        .replace("새벽종를", "새벽종을")
        .replace("새벽종와", "새벽종과")
        .replace("새벽종는", "새벽종은")
        .replace("새벽종로", "새벽종으로")
        .replace("새벽종가", "새벽종이")
        .replace("도둑맞은 세 새벽종", "도둑맞은 두 새벽종")
        .replace("세 새벽종이 모두 도난당했다", "남은 두 새벽종이 모두 도난당했다")
        .replace("누군가 새벽종을 모두 훔쳤다고", "누군가 남은 두 새벽종을 모두 훔쳤다고")

    private fun migrateShopPowerFirstState(state: SimpleGameState) {
        val migrationLevel = state.hero.level.coerceAtLeast(1L)
        state.equipment.forEach { item -> item.acquiredAtLevel = migrationLevel }
        state.shopAttemptedSlots.clear()
        if (state.adventurePhase == AdventurePhase.SHOPPING) {
            state.pendingShopOffer = null
            state.lastShopPurchase = null
        }
    }

    private fun migrateShopPriceCurveState(state: SimpleGameState) {
        if (state.adventurePhase != AdventurePhase.SHOPPING) return
        state.pendingShopOffer = null
        state.lastShopPurchase = null
        state.shopAttemptedSlots.clear()
    }

    private fun migrateEquipmentPowerAlignmentState(state: SimpleGameState) {
        state.equipment.forEach { item ->
            if (rarityRank(item.rarity) >= LEGENDARY_RARITY_RANK) {
                item.power = maxOf(
                    item.power,
                    lootEquipmentPowerFloor(item.acquiredAtLevel, item.rarity),
                )
            }
        }
        state.inventory.indices.forEach { index ->
            val item = state.inventory[index]
            val currentPower = item.equipmentPower
            if (
                item.kind == "장비" &&
                    currentPower != null &&
                    rarityRank(item.rarity) >= LEGENDARY_RARITY_RANK
            ) {
                state.inventory[index] = item.copy(
                    equipmentPower = maxOf(
                        currentPower,
                        lootEquipmentPowerFloor(item.foundAtLevel, item.rarity),
                    ),
                )
            }
        }
        state.lastLootEquipmentPower?.let { currentPower ->
            if (rarityRank(state.lastLootRarity) >= LEGENDARY_RARITY_RANK) {
                state.lastLootEquipmentPower = maxOf(
                    currentPower,
                    lootEquipmentPowerFloor(state.hero.level, state.lastLootRarity),
                )
            }
        }
        if (state.adventurePhase == AdventurePhase.SHOPPING) {
            state.pendingShopOffer = null
            state.lastShopPurchase = null
            state.shopAttemptedSlots.clear()
        }
    }

    private fun migrateExpandedMainStory(state: SimpleGameState): Boolean {
        val oldTale = state.adventureTale
        if (oldTale.kind != TaleKind.EPILOGUE) return false
        val firstSecondVolume = AdventureTaleCatalog.mainTales[LEGACY_MAIN_TALE_COUNT]
        state.adventureTale = AdventureTaleCatalog.instantiate(
            definition = firstSecondVolume,
            sequence = LEGACY_MAIN_TALE_COUNT.toLong() + 1L,
            heroName = state.hero.name,
            heroLevel = state.hero.level,
            variant = oldTale.variant,
        )
        return true
    }

    private fun migrateProgressionTargets(
        state: SimpleGameState,
        scaleRewards: Boolean,
    ) {
        val tale = state.adventureTale
        tale.currentActIndex = tale.currentActIndex.coerceIn(0, tale.acts.lastIndex)
        tale.acts.indices.forEach { index ->
            val old = tale.acts[index]
            val newTarget = AdventureTaleCatalog.targetFor(tale.definitionId, index)
            val wasCompleted = old.completed || index < tale.currentActIndex
            val migratedProgress = if (wasCompleted) {
                newTarget
            } else {
                val oldTarget = old.target.coerceAtLeast(1L)
                val ratioProgress = (old.progress.coerceAtLeast(0L).toDouble() /
                    oldTarget.toDouble() * newTarget.toDouble()).toLong()
                val upperBound = if (index == tale.currentActIndex) {
                    (newTarget - 1L).coerceAtLeast(0L)
                } else {
                    newTarget
                }
                ratioProgress.coerceIn(0L, upperBound)
            }
            tale.acts[index] = old.copy(
                progress = migratedProgress,
                target = newTarget,
                rewardExperience = if (scaleRewards) {
                    scaleFormerPacingValue(old.rewardExperience)
                } else {
                    old.rewardExperience
                },
                completed = wasCompleted,
            )
        }
    }

    private fun migrateExperienceProgress(state: SimpleGameState, savedSchema: Int) {
        val oldRequired = when {
            savedSchema < 20 -> legacyExperienceRequired(state.hero.level)
            savedSchema < 21 -> formerExperienceRequired(state.hero.level)
            else -> previousExperienceRequired(state.hero.level)
        }.coerceAtLeast(1L)
        val newRequired = experienceRequired(state.hero.level).coerceAtLeast(1L)
        if (oldRequired == newRequired) return
        val migrated = state.hero.experience.coerceAtLeast(0L).toDouble() /
            oldRequired.toDouble() * newRequired.toDouble()
        state.hero.experience = when {
            !migrated.isFinite() || migrated >= newRequired.toDouble() -> newRequired - 1L
            else -> migrated.toLong().coerceIn(0L, newRequired - 1L)
        }
    }

    private fun formerExperienceRequired(level: Long): Long {
        val safeLevel = level.coerceAtLeast(1L)
        return safeMul(
            210L,
            safeMul(
                safeAdd(safeLevel, 4L),
                safeAdd(safeMul(safeLevel, 2L), -1L),
            ),
        )
    }

    private fun previousExperienceRequired(level: Long): Long {
        val safeLevel = level.coerceAtLeast(1L)
        return safeMul(
            345L,
            safeMul(
                safeAdd(safeLevel, 4L),
                safeAdd(safeMul(safeLevel, 2L), -1L),
            ),
        )
    }

    private fun legacyExperienceRequired(level: Long): Long {
        val step = max(0L, level - 1L)
        return safeAdd(
            100L,
            safeAdd(safeMul(step, 40L), safeMul(safeMul(step, step), 8L)),
        )
    }

    private fun scaleFormerPacingValue(value: Long): Long {
        if (value <= 0L) return 0L
        val whole = safeMul(value / 20L, 29L)
        val remainder = safeAdd(safeMul(value % 20L, 29L), 10L) / 20L
        return safeAdd(whole, remainder)
    }

    private fun ensureSkillCatalogSeed(state: SimpleGameState) {
        if (state.skillCatalogSeed != 0L) return
        val heroSalt = state.hero.name.fold(0L) { hash, character ->
            hash * 31L + character.code.toLong()
        }
        state.skillCatalogSeed = SkillCatalog.deriveSeed(
            state.rngState xor state.presentationRngState xor heroSalt,
            state.hero.heroClass,
        )
    }

    private fun synchronizeClassSkillCatalog(state: SimpleGameState) {
        ensureSkillCatalogSeed(state)
        val previousLastSkillTier = signatureTier(state.lastSkillCatalogId)
        val previousActivatedSkillTier = signatureTier(state.lastActivatedSkillCatalogId)
        val previousUsageByTier = state.skills.associate { learned ->
            learned.id to learned.boundedUsageCount
        }
        state.skills = (1..expectedSkillCount(state.hero.level)).map { tier ->
            val definition = SkillCatalog.select(state.skillCatalogSeed, state.hero.heroClass, tier)
            LearnedSkill(
                id = tier,
                name = definition.name,
                acquiredAtLevel = signatureAcquisitionLevel(tier),
                description = definition.description,
                catalogId = definition.catalogId,
                usageCount = previousUsageByTier[tier] ?: 0L,
            )
        }.toMutableList()
        if (previousLastSkillTier != null) {
            state.lastSkillCatalogId = SkillCatalog.select(
                state.skillCatalogSeed,
                state.hero.heroClass,
                previousLastSkillTier,
            ).catalogId
        }
        state.lastActivatedSkillCatalogId = previousActivatedSkillTier?.let { tier ->
            SkillCatalog.select(
                state.skillCatalogSeed,
                state.hero.heroClass,
                tier,
            ).catalogId
        }.orEmpty()
    }

    private fun expectedSkillCount(level: Long): Int =
        (level.coerceAtLeast(1L) / 5L + 1L).coerceAtMost(MAX_SKILLS.toLong()).toInt()

    private fun signatureAcquisitionLevel(tier: Int): Long =
        if (tier <= 1) 1L else (tier - 1L) * 5L

    private fun signatureTier(catalogId: String): Int? = catalogId
        .substringAfter("_t", missingDelimiterValue = "")
        .substringBefore("_c")
        .toIntOrNull()
        ?.takeIf { it in 1..MAX_SKILLS }

    private fun synchronizeSkillDisplayNames(state: SimpleGameState) {
        state.skills.indices.forEach { index ->
            val learned = state.skills[index]
            val definition = SkillCatalog.find(learned.catalogId) ?: return@forEach
            if (
                learned.name != definition.name ||
                learned.description != definition.description ||
                learned.usageCount != learned.boundedUsageCount
            ) {
                state.skills[index] = learned.copy(
                    name = definition.name,
                    description = definition.description,
                    usageCount = learned.boundedUsageCount,
                )
            }
        }

        if (state.skills.none { it.catalogId == state.lastActivatedSkillCatalogId }) {
            state.lastActivatedSkillCatalogId = ""
        }

        val activeDefinition = SkillCatalog.find(state.lastSkillCatalogId) ?: return
        val previousAttackName = state.lastAttackName
        state.lastAttackName = activeDefinition.name
        if (
            state.lastAttackWasSkill &&
            previousAttackName.isNotBlank() &&
            state.lastResult.startsWith("$previousAttackName · ")
        ) {
            state.lastResult = activeDefinition.name + state.lastResult.removePrefix(previousAttackName)
        }
    }

    private fun synchronizeAttackSkillCatalog(state: SimpleGameState) {
        state.skills.indices.forEach { index ->
            val learned = state.skills[index]
            val (name, description) = SimpleContent.skills.getOrNull(learned.id - 1) ?: return@forEach
            state.skills[index] = learned.copy(
                name = name,
                description = description,
            )
        }
    }

    private fun synchronizeEquipmentNames(state: SimpleGameState) {
        state.equipment.forEach { item ->
            item.name = normalizedEquipmentName(item.name, item.rarity)
        }
        state.inventory.indices.forEach { index ->
            val item = state.inventory[index]
            if (item.kind == "장비") {
                state.inventory[index] = item.copy(
                    name = normalizedEquipmentName(item.name, item.rarity),
                )
            }
        }
    }

    private fun synchronizeClassEquipmentTerminology(state: SimpleGameState) {
        state.equipment.forEach { item ->
            item.name = ClassEquipmentCatalog.modernizeName(item.name)
        }
        state.inventory.indices.forEach { index ->
            val item = state.inventory[index]
            if (item.kind == "장비") {
                state.inventory[index] = item.copy(
                    name = ClassEquipmentCatalog.modernizeName(item.name),
                )
            }
        }
        state.pendingShopOffer = state.pendingShopOffer?.let { offer ->
            offer.copy(name = ClassEquipmentCatalog.modernizeName(offer.name))
        }
        state.lastShopPurchase = state.lastShopPurchase?.let { purchase ->
            purchase.copy(name = ClassEquipmentCatalog.modernizeName(purchase.name))
        }
        state.lastTownItemName = ClassEquipmentCatalog.modernizeName(state.lastTownItemName)
        state.lastLootName = ClassEquipmentCatalog.modernizeName(state.lastLootName)
        state.lastLootSummary = ClassEquipmentCatalog.modernizeName(state.lastLootSummary)
        state.lastResult = ClassEquipmentCatalog.modernizeName(state.lastResult)
        state.recentItemNames = state.recentItemNames
            .map(ClassEquipmentCatalog::modernizeName)
            .toMutableList()
    }

    private fun synchronizeMonsterNames(state: SimpleGameState) {
        state.monster.name = normalizedMonsterName(state.monster.name)
        state.recentMonsterNames = state.recentMonsterNames
            .map(::normalizedMonsterName)
            .filter { it.isNotBlank() }
            .distinct()
            .take(RECENT_MONSTERS)
            .toMutableList()
        state.lastResult = state.lastResult.replace(LEGACY_MONSTER_EPITHET_IN_RESULT, "")
    }

    /** Base damage shared by real basic attacks, skills, and basic-attack monster HP budgets. */
    private fun baseAttackDamage(state: SimpleGameState): Long {
        val offense = classOffenseAttribute(state.hero.stats, state.hero.heroClass)
        val equipmentPower = state.equipment.fold(0L) { total, item -> safeAdd(total, item.power) }
        return safeAdd(
            8L,
            safeAdd(
                safeMul(state.hero.level, 2L),
                safeAdd(safeMul(offense, 3L), equipmentPower / 3L),
            ),
        )
    }

    private fun scalePercent(value: Long, percent: Long): Long = safeAdd(
        safeMul(value / 100L, percent),
        safeMul(value % 100L, percent) / 100L,
    )

    private fun scaleRatio(value: Long, numerator: Long, denominator: Long): Long {
        require(value >= 0L)
        require(numerator >= 0L)
        require(denominator > 0L)
        return safeAdd(
            safeMul(value / denominator, numerator),
            safeMul(value % denominator, numerator) / denominator,
        )
    }

    private fun averageNonNegative(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val count = values.size.toLong()
        var quotientSum = 0L
        var remainderSum = 0L
        values.forEach { value ->
            val safeValue = value.coerceAtLeast(0L)
            quotientSum = safeAdd(quotientSum, safeValue / count)
            remainderSum = safeAdd(remainderSum, safeValue % count)
        }
        return safeAdd(quotientSum, remainderSum / count)
    }

    private fun weightedAverageNonNegative(values: List<Long>, weights: List<Int>): Long {
        require(values.size == weights.size)
        val totalWeight = weights.sumOf { it.coerceAtLeast(0).toLong() }
        if (totalWeight == 0L) return 0L
        var quotientSum = 0L
        var remainderSum = 0L
        values.indices.forEach { index ->
            val value = values[index].coerceAtLeast(0L)
            val weight = weights[index].coerceAtLeast(0).toLong()
            quotientSum = safeAdd(quotientSum, safeMul(value / totalWeight, weight))
            remainderSum = safeAdd(remainderSum, safeMul(value % totalWeight, weight))
        }
        return safeAdd(quotientSum, remainderSum / totalWeight)
    }

    private fun addTrophy(
        state: SimpleGameState,
        rng: StableRng,
        defeated: MonsterState,
        eventAt: Long,
        recentEvents: MutableList<RecentAdventureEvent>,
        origin: String = "PRIMARY",
    ): CombatLoot? {
        if (state.inventory.size.toLong() >= state.inventoryCapacity()) return null
        val name = uniqueName(state.recentItemNames, RECENT_ITEMS, rng) {
            QuestMonsterCatalog.definition(defeated.catalogId)
                ?.trophyNames
                ?.pick(rng)
                ?: "${SimpleContent.lootMaterials.pick(rng)} ${SimpleContent.lootForms.pick(rng)}"
        }
        val rarity = trophyRarity(rng)
        val key = if (enableAdventureTraits) AdventureTraitEngine.nextRewardKey(state, origin) else ""
        if (enableAdventureTraits && origin == "PRIMARY" && defeated.grade == MonsterGrade.NORMAL &&
            AdventureTraitEngine.omitTrophy(state, key, rarity, name, eventAt)) {
            AdventureTraitEngine.rewardTrace(state, key, origin, 0L, name, null, rarity, null, null, false, false)
            return null
        }
        val itemId = safeIncrement(state.totalItemsFound)
        addInventory(
            state,
            InventoryItem(
                id = itemId,
                name = name,
                rarity = rarity,
                kind = "전리품",
                foundAtLevel = state.hero.level,
            ),
        )
        if (enableAdventureTraits) {
            AdventureTraitEngine.rewardTrace(state, key, origin, itemId, name, null, rarity, null, null, true, false)
            if (origin == "PRIMARY") maybeAddTraitExtra(state, key, eventAt, recentEvents)
        }
        return CombatLoot(name = name, rarity = rarity, kind = "전리품")
    }

    private fun addEquipmentDrop(
        state: SimpleGameState,
        rng: StableRng,
        eventAt: Long,
        recentEvents: MutableList<RecentAdventureEvent>,
        origin: String = "PRIMARY",
        maximumRarityRank: Int = MYTHIC_RARITY_RANK,
    ): CombatLoot? {
        if (state.inventory.size.toLong() >= state.inventoryCapacity()) return null
        val slot = EquipmentSlot.entries[rng.nextInt(EquipmentSlot.entries.size)]
        val originalCandidate = equipmentCandidate(
            state,
            rng,
            slot,
            equipmentLootRarity(rng, maximumRarityRank),
        )
        val key = if (enableAdventureTraits) AdventureTraitEngine.nextRewardKey(state, origin) else ""
        var candidate = originalCandidate
        if (enableAdventureTraits && origin != "TRAIT_EXTRA" && AdventureTraitEngine.owns(state, "L05") &&
            AdventureTraitEngine.roll(state, "L05", key, 100)) {
            val reroll = AdventureTraitEngine.random(state.adventureTraits.seed, key + ":appraisePower", EQUIPMENT_POWER_ROLL_MAX + 1)
            val power = maxOf(originalCandidate.power, lootEquipmentPowerForRoll(state.hero.level, originalCandidate.rarity, reroll))
            if (power > originalCandidate.power) {
                candidate = originalCandidate.copy(power = power)
                state.adventureTraits.source?.let { it.resultTimePercent += 15 }
                AdventureTraitEngine.activate(state, "L05", key, eventAt, AdventureTraitEffectKind.APPRAISAL,
                    originalCandidate.power, power, name = candidate.name)
            }
        }
        val current = state.equipment.first { it.slot == slot }
        val previousOrigin = state.adventureTraits.equipmentOrigins[slot] ?: "PRIMARY"
        val baseUpgrade = originalCandidate.power > current.power ||
            (originalCandidate.power == current.power && rarityRank(originalCandidate.rarity) > rarityRank(current.rarity))
        val baseSelectedPower = if (baseUpgrade) originalCandidate.power else current.power
        val droppedItemId = safeIncrement(state.totalItemsFound)
        com.nullplaying.model.recordMythicDiscovery(state, droppedItemId, candidate.name, candidate.rarity,
            slot, candidate.power, eventAt)
        val candidateIsUpgrade = candidate.power > current.power ||
            (candidate.power == current.power && rarityRank(candidate.rarity) > rarityRank(current.rarity))
        val keptFamiliar = enableAdventureTraits && origin != "TRAIT_EXTRA" && candidate.power > current.power &&
            AdventureTraitEngine.keepFamiliarGear(
                state = state,
                key = key,
                slot = slot,
                currentPower = current.power,
                candidatePower = candidate.power,
                at = eventAt,
            )
        val isUpgrade = candidateIsUpgrade && !keptFamiliar

        if (isUpgrade) {
            val previousName = current.name
            val previousPower = current.power
            val replaced = InventoryItem(
                id = droppedItemId,
                name = current.name,
                rarity = current.rarity,
                kind = "장비",
                foundAtLevel = current.acquiredAtLevel,
                equipmentSlot = current.slot,
                equipmentPower = current.power,
            )
            current.name = candidate.name
            current.power = candidate.power
            current.rarity = candidate.rarity
            current.acquiredAtLevel = state.hero.level.coerceAtLeast(1L)
            addInventory(state, replaced)
            if (enableAdventureTraits) {
                val equippedOrigin = if (candidate.power > originalCandidate.power) "TRAIT_APPRAISAL" else origin
                state.adventureTraits.equipmentOrigins = state.adventureTraits.equipmentOrigins + (slot to equippedOrigin)
                AdventureTraitEngine.rewardTrace(state, key, origin, droppedItemId, candidate.name, slot,
                    candidate.rarity, originalCandidate.power, candidate.power, true, true)
                state.adventureTraits.primaryRewardOrigins = state.adventureTraits.primaryRewardOrigins + (droppedItemId to previousOrigin)
                if (origin != "TRAIT_EXTRA") {
                    AdventureTraitEngine.observeEquipment(state, key, slot, baseSelectedPower, baseUpgrade, eventAt)
                }
            }
            state.totalLootEquipmentEquips = safeIncrement(state.totalLootEquipmentEquips)
            recentEvents += RecentAdventureEvent(
                occurredAt = eventAt,
                type = RecentAdventureEventType.EQUIPMENT_CHANGED,
                previousName = previousName,
                currentName = current.name,
                previousValue = previousPower,
                currentValue = current.power,
                equipmentSlot = slot,
                rarity = current.rarity,
            )
            if (enableAdventureTraits && origin == "PRIMARY") maybeAddTraitExtra(state, key, eventAt, recentEvents)
            return CombatLoot(
                name = candidate.name,
                rarity = candidate.rarity,
                kind = "장비",
                equipped = true,
                equipmentSlot = slot,
                equipmentPower = candidate.power,
                previousPower = replaced.equipmentPower,
            )
        }

        addInventory(
            state,
            InventoryItem(
                id = droppedItemId,
                name = candidate.name,
                rarity = candidate.rarity,
                kind = "장비",
                foundAtLevel = state.hero.level,
                equipmentSlot = slot,
                equipmentPower = candidate.power,
            ),
        )
        if (enableAdventureTraits) {
            if (keptFamiliar) {
                state.adventureTraits.familiarHeldItemIds =
                    state.adventureTraits.familiarHeldItemIds + droppedItemId
            }
            AdventureTraitEngine.rewardTrace(state, key, origin, droppedItemId, candidate.name, slot,
                candidate.rarity, originalCandidate.power, candidate.power, true, false)
            if (candidate.power > originalCandidate.power) state.adventureTraits.primaryRewardOrigins =
                state.adventureTraits.primaryRewardOrigins + (droppedItemId to "TRAIT_APPRAISAL")
            if (origin != "TRAIT_EXTRA") {
                if (keptFamiliar) {
                    AdventureTraitEngine.observe(
                        state = state,
                        key = "$key:familiarChoice",
                        context = "equipment:${slot.name}",
                        positive = setOf("L06"),
                        negative = setOf("L05"),
                        at = eventAt,
                        reason = "equipment:familiar",
                    )
                } else {
                    AdventureTraitEngine.observeEquipment(state, key, slot, baseSelectedPower, baseUpgrade, eventAt)
                }
            }
            if (origin == "PRIMARY") {
                maybeAddTraitExtra(state, key, eventAt, recentEvents)
            }
        }
        return CombatLoot(
            name = candidate.name,
            rarity = candidate.rarity,
            kind = "장비",
            equipmentSlot = slot,
            equipmentPower = candidate.power,
            previousPower = current.power,
        )
    }

    private fun recordLootPresentation(state: SimpleGameState, loot: CombatLoot?) {
        clearLootPresentation(state)
        if (loot == null) {
            state.lastLootSummary = if (enableAdventureTraits && state.adventureTraits.source?.itemOmitted == true)
                "전리품을 남기고 길을 서두릅니다" else "가방이 가득 차 전리품을 더 담지 못했습니다"
            return
        }
        state.lastLootName = loot.name
        state.lastLootRarity = loot.rarity
        state.lastLootKind = loot.kind
        state.lastLootEquipped = loot.equipped
        state.lastLootEquipmentSlot = loot.equipmentSlot
        state.lastLootEquipmentPower = loot.equipmentPower
        state.lastLootPreviousPower = loot.previousPower
        state.lastLootSummary = if (loot.equipped) {
            "${loot.name} · 새 장비로 장착"
        } else {
            "${loot.name} · 가방에 보관"
        }
    }

    private fun maybeAddTraitExtra(state: SimpleGameState, originalKey: String, at: Long,
        recentEvents: MutableList<RecentAdventureEvent>) {
        if (!AdventureTraitEngine.owns(state, "L01") ||
            !AdventureTraitEngine.roll(state, "L01", originalKey, AdventureTraitEngine.EXTRA_ITEM_BASIS_POINTS)) return
        if (state.inventory.size.toLong() >= state.inventoryCapacity()) return
        val rng = StableRng(AdventureTraitEngine.seedFor(state, originalKey + ":extraReward"))
        val recentNames = state.recentItemNames.toMutableList()
        val extra = if (rng.nextInt(100) < 2) addEquipmentDrop(state, rng, at, recentEvents, "TRAIT_EXTRA")
            else addTrophy(state, rng, MonsterState(0L, "", state.hero.level, 1L), at, recentEvents, "TRAIT_EXTRA")
        state.recentItemNames = recentNames
        if (extra != null) {
            state.adventureTraits.source?.let { it.additionalItemNames = it.additionalItemNames + extra.name; it.resultTimePercent += 15 }
            AdventureTraitEngine.activate(state, "L01", originalKey, at, AdventureTraitEffectKind.EXTRA_ITEM, 1L, 2L, name = extra.name)
        }
    }

    private fun clearLootPresentation(state: SimpleGameState) {
        state.lastLootSummary = ""
        state.lastLootName = ""
        state.lastLootRarity = ""
        state.lastLootKind = ""
        state.lastLootEquipped = false
        state.lastLootEquipmentSlot = null
        state.lastLootEquipmentPower = null
        state.lastLootPreviousPower = null
    }

    private fun equipmentCandidate(
        state: SimpleGameState,
        rng: StableRng,
        slot: EquipmentSlot,
        rarity: String,
        forShop: Boolean = false,
    ): EquipmentCandidate {
        val name = uniqueName(state.recentItemNames, RECENT_ITEMS, rng) {
            equipmentNameForRarity(
                coreName = "${SimpleContent.equipmentPrefixes(state.hero.level).pick(rng)} " +
                    SimpleContent.equipmentBase(
                        slot = slot,
                        level = state.hero.level,
                        heroClass = state.hero.heroClass,
                        variantIndex = rng.nextInt(ClassEquipmentCatalog.BASE_VARIANTS_PER_SLOT),
                    ),
                rarity = rarity,
            )
        }
        val powerRoll = rng.nextInt(EQUIPMENT_POWER_ROLL_MAX + 1)
        val power = if (forShop) {
            shopEquipmentPowerForRoll(state.hero.level, rarity, powerRoll)
        } else {
            lootEquipmentPowerForRoll(state.hero.level, rarity, powerRoll)
        }
        return EquipmentCandidate(
            name = name,
            rarity = rarity,
            power = power,
        )
    }

    internal fun shopEquipmentPowerForRoll(level: Long, rarity: String, roll: Int): Long =
        safeAdd(
            equipmentLevelPowerBase(level),
            rarityRank(rarity).coerceIn(0, SHOP_MAX_RARITY_RANK).toLong() +
                roll.coerceIn(0, EQUIPMENT_POWER_ROLL_MAX),
        ).coerceAtLeast(1L)

    internal fun maximumShopEquipmentPower(level: Long): Long =
        shopEquipmentPowerForRoll(
            level = level,
            rarity = "영웅",
            roll = EQUIPMENT_POWER_ROLL_MAX,
        )

    internal fun lootEquipmentPowerFloor(level: Long, rarity: String): Long {
        val sourceAndRarityPower = safeAdd(
            (equipmentLevelPowerBase(level) - LOOT_POWER_OFFSET).coerceAtLeast(0L),
            lootRarityPower(rarity),
        )
        val guaranteedPercent = when (rarity) {
            "전설" -> LEGENDARY_MIN_SHOP_POWER_PERCENT
            "신화" -> MYTHIC_MIN_SHOP_POWER_PERCENT
            else -> return sourceAndRarityPower.coerceAtLeast(1L)
        }
        return maxOf(
            sourceAndRarityPower,
            scalePercentCeiling(maximumShopEquipmentPower(level), guaranteedPercent),
        ).coerceAtLeast(1L)
    }

    internal fun lootEquipmentPowerForRoll(level: Long, rarity: String, roll: Int): Long =
        safeAdd(
            lootEquipmentPowerFloor(level, rarity),
            roll.coerceIn(0, EQUIPMENT_POWER_ROLL_MAX).toLong(),
        ).coerceAtLeast(1L)

    private fun lootRarityPower(rarity: String): Long = when (rarity) {
        "신화" -> 30L
        "전설" -> 20L
        "영웅" -> 12L
        "희귀" -> 7L
        "고급" -> 3L
        else -> 0L
    }

    internal fun equipmentNameForRarity(
        coreName: String,
        rarity: String,
    ): String {
        val enhancement = rarityRank(rarity)
        return if (enhancement == 0) {
            coreName
        } else {
            "$coreName +$enhancement"
        }
    }

    private fun normalizedEquipmentName(name: String, rarity: String): String {
        val enhancement = rarityRank(rarity)
        val coreName = name.substringBefore(" · ").replace(ENHANCEMENT_SUFFIX, "")
        return if (enhancement == 0) coreName else "$coreName +$enhancement"
    }

    private fun normalizedMonsterName(name: String): String = name.substringBefore(" · ").trim()

    private fun addInventory(state: SimpleGameState, item: InventoryItem) {
        if (state.inventory.size.toLong() >= state.inventoryCapacity()) return
        state.totalItemsFound = safeIncrement(state.totalItemsFound)
        state.inventory.add(0, item)
    }

    private fun equipmentLootRarity(
        rng: StableRng,
        maximumRarityRank: Int = MYTHIC_RARITY_RANK,
    ): String = cappedEquipmentLootRarityForRoll(
        roll = rng.nextInt(LOOT_RARITY_ROLL_BOUND),
        maximumRarityRank = maximumRarityRank,
    )

    private fun trophyRarity(rng: StableRng): String =
        trophyRarityForRoll(rng.nextInt(100))

    private fun shopRarity(rng: StableRng): String =
        shopRarityForRoll(rng.nextInt(SHOP_RARITY_ROLL_BOUND))

    internal fun equipmentLootRarityForRoll(roll: Int): String {
        val bounded = roll.coerceIn(0, LOOT_RARITY_ROLL_BOUND - 1)
        return when {
            bounded < 500 -> "신화"
            bounded < 1_500 -> "전설"
            bounded < 51_500 -> "영웅"
            bounded < 191_500 -> "희귀"
            bounded < 491_500 -> "고급"
            else -> "일반"
        }
    }

    internal fun cappedEquipmentLootRarityForRoll(roll: Int, maximumRarityRank: Int): String {
        val rarity = equipmentLootRarityForRoll(roll)
        val cap = maximumRarityRank.coerceIn(0, MYTHIC_RARITY_RANK)
        if (rarityRank(rarity) <= cap) return rarity
        return when (cap) {
            MYTHIC_RARITY_RANK -> "신화"
            LEGENDARY_RARITY_RANK -> "전설"
            3 -> "영웅"
            2 -> "희귀"
            1 -> "고급"
            else -> "일반"
        }
    }

    internal fun trophyRarityForRoll(roll: Int): String = when (roll.coerceIn(0, 99)) {
        in 0..6 -> "영웅"
        in 7..20 -> "희귀"
        in 21..50 -> "고급"
        else -> "일반"
    }

    internal fun shopRarityForRoll(roll: Int): String =
        when (roll.coerceIn(0, SHOP_RARITY_ROLL_BOUND - 1)) {
            0 -> "영웅"
            in 1..20 -> "희귀"
            in 21..249 -> "고급"
            else -> "일반"
        }

    internal fun equipmentDropPercent(monster: MonsterState): Int = when (monster.grade) {
        MonsterGrade.NORMAL -> 2
        MonsterGrade.ELITE -> 10
        MonsterGrade.BOSS -> if (monster.isFinalBoss) 100 else 50
    }

    private fun rarityRank(rarity: String): Int = when (rarity) {
        "신화" -> 5
        "전설" -> 4
        "영웅" -> 3
        "희귀" -> 2
        "고급" -> 1
        else -> 0
    }

    private fun uniqueName(
        recent: MutableList<String>,
        keep: Int,
        rng: StableRng,
        create: () -> String,
    ): String {
        var candidate = create()
        repeat(64) {
            if (candidate !in recent) {
                recent.add(0, candidate)
                while (recent.size > keep) recent.removeAt(recent.lastIndex)
                return candidate
            }
            candidate = create()
        }
        recent.add(0, candidate)
        while (recent.size > keep) recent.removeAt(recent.lastIndex)
        return candidate
    }

    private fun starterEquipment(heroClass: HeroClass): MutableList<EquippedItem> =
        EquipmentSlot.entries.map { slot ->
            EquippedItem(
                slot = slot,
                name = SimpleContent.equipmentBase(
                    slot = slot,
                    level = 1L,
                    heroClass = heroClass,
                    variantIndex = 0,
                ),
                power = 1L,
            )
        }.toMutableList()

    private data class EquipmentCandidate(
        val name: String,
        val rarity: String,
        val power: Long,
    )

    private fun <T> List<T>.pick(rng: StableRng): T = this[rng.nextInt(size)]

    private fun safeIncrement(value: Long): Long = if (value == Long.MAX_VALUE) value else value + 1L

    private fun levelProgress(level: Long): Long = level.coerceAtLeast(1L) - 1L

    internal fun manaBaseAttribute(intelligence: Long, wisdom: Long): Long {
        val safeIntelligence = intelligence.coerceAtLeast(0L)
        val safeWisdom = wisdom.coerceAtLeast(0L)
        return safeAdd(
            safeAdd(safeIntelligence / 2L, safeWisdom / 2L),
            (safeIntelligence % 2L + safeWisdom % 2L) / 2L,
        )
    }

    internal fun dndAbilityModifier(score: Long): Long =
        Math.floorDiv(score.coerceAtLeast(0L) - 10L, 2L)

    internal fun initialHealthBase(heroClass: HeroClass): Long = when (heroClass) {
        HeroClass.WARRIOR,
        HeroClass.RANGER,
        HeroClass.PALADIN,
        -> 10L

        HeroClass.ROGUE,
        HeroClass.CLERIC,
        -> 8L

        HeroClass.MAGE -> 6L
    }

    internal fun initialManaBase(heroClass: HeroClass): Long = when (heroClass) {
        HeroClass.WARRIOR -> 4L
        HeroClass.ROGUE -> 6L
        HeroClass.RANGER,
        HeroClass.PALADIN,
        -> 8L

        HeroClass.MAGE,
        HeroClass.CLERIC,
        -> 10L
    }

    internal fun initialMaxHealth(heroClass: HeroClass, constitution: Long): Long =
        safeAdd(initialHealthBase(heroClass), dndAbilityModifier(constitution)).coerceAtLeast(1L)

    internal fun initialMaxMana(
        heroClass: HeroClass,
        intelligence: Long,
        wisdom: Long,
    ): Long = safeAdd(
        initialManaBase(heroClass),
        dndAbilityModifier(manaBaseAttribute(intelligence, wisdom)),
    ).coerceAtLeast(1L)

    internal fun initialStatsForClass(stats: HeroStats, heroClass: HeroClass): HeroStats =
        stats.copy(
            maxHealth = initialMaxHealth(heroClass, stats.constitution),
            maxMana = initialMaxMana(heroClass, stats.intelligence, stats.wisdom),
        )

    private fun safeAdd(left: Long, right: Long): Long =
        when {
            right > 0L && left > Long.MAX_VALUE - right -> Long.MAX_VALUE
            right < 0L && left < Long.MIN_VALUE - right -> Long.MIN_VALUE
            else -> left + right
        }

    private fun safeSubtract(left: Long, nonNegativeRight: Long): Long =
        if (nonNegativeRight > 0L && left < Long.MIN_VALUE + nonNegativeRight) {
            Long.MIN_VALUE
        } else {
            left - nonNegativeRight
        }

    private fun nonNegativeDifference(later: Long, earlier: Long): Long = when {
        later <= earlier -> 0L
        earlier < 0L && later > Long.MAX_VALUE + earlier -> Long.MAX_VALUE
        else -> later - earlier
    }

    private fun safeMul(left: Long, right: Long): Long {
        if (left == 0L || right == 0L) return 0L
        if (left > Long.MAX_VALUE / right) return Long.MAX_VALUE
        return left * right
    }

    private fun scalePercentRounded(value: Long, percent: Long): Long {
        val whole = safeMul(value / 100L, percent)
        val remainder = safeMul(value % 100L, percent)
        return safeAdd(whole, safeAdd(remainder, 50L) / 100L)
    }

    private fun scalePercentCeiling(value: Long, percent: Long): Long {
        val whole = safeMul(value / 100L, percent)
        val remainder = safeMul(value % 100L, percent)
        val roundedRemainder = if (remainder == 0L) 0L else safeAdd(remainder, 99L) / 100L
        return safeAdd(whole, roundedRemainder)
    }

    private fun roundUpToGoldUnit(value: Long): Long {
        if (value <= 0L || value == Long.MAX_VALUE) return value
        val remainder = value % GOLD_UNIT
        return if (remainder == 0L) value else {
            safeMul(safeAdd(value / GOLD_UNIT, 1L), GOLD_UNIT)
        }
    }

    private fun normalizedStatCombatPower(
        weightedStatTenths: Long,
        expectedStatThirtieths: Long,
        equipmentBenchmark: Long,
    ): Long {
        if (
            weightedStatTenths <= 0L ||
            expectedStatThirtieths <= 0L ||
            equipmentBenchmark <= 0L
        ) {
            return 0L
        }
        // A 0.35 soft range keeps the two displayed contributions comparable during endless
        // epilogue growth without ever making a real primary/secondary stat gain reduce power.
        val statRatio = weightedStatTenths.toDouble() * 3.0 /
            expectedStatThirtieths.toDouble()
        val deviation = statRatio - 1.0
        val softenedMultiplier = 1.0 +
            deviation / (1.0 + kotlin.math.abs(deviation) / STAT_SOFT_CAP_RANGE)
        val scaled = equipmentBenchmark.toDouble() * softenedMultiplier
        return if (!scaled.isFinite() || scaled >= Long.MAX_VALUE.toDouble()) {
            Long.MAX_VALUE
        } else {
            scaled.roundToLong().coerceAtLeast(0L)
        }
    }

    private fun emptyDelta() = SettlementDelta(0L, 0L, 0L, 0L, 0L, 0L)

    private class StableRng(initialSeed: Long) {
        var state: Long = initialSeed.takeUnless { it == 0L } ?: DEFAULT_SEED
            private set

        fun nextInt(bound: Int): Int {
            require(bound > 0)
            state = state * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
            return ((state ushr 1) % bound.toLong()).toInt()
        }

        fun nextDouble(): Double {
            nextInt(Int.MAX_VALUE)
            return (state ushr 11).toDouble() / 9_007_199_254_740_992.0
        }
    }

    companion object {
        private const val PRIMARY_STAT_WEIGHT = 7L
        private const val SECONDARY_STAT_WEIGHT = 3L
        private const val PRIMARY_STAT_COUNT = 6
        private const val BASE_EXPECTED_STAT_THIRTIETHS = 315L
        private const val LEGACY_EXPECTED_STAT_GROWTH_THIRTIETHS = 9L
        private const val EXPECTED_STAT_GROWTH_THIRTIETHS = 20L
        private const val STAT_SOFT_CAP_RANGE = 0.35
        private const val BASE_EXPECTED_EQUIPMENT_POWER = 1L
        private const val EXPECTED_EQUIPMENT_POWER_PER_LEVEL = 5L
        const val ATTACK_PRESENTATION_MILLIS = 1_400L
        const val OPENING_SLIDE_MILLIS = 2_000L
        const val OPENING_SLIDE_COUNT = 3
        const val OPENING_PRESENTATION_MILLIS =
            OPENING_SLIDE_MILLIS * OPENING_SLIDE_COUNT
        const val ENCOUNTER_SEARCH_MILLIS = 5_000L
        const val ENCOUNTER_DISCOVERY_MILLIS = 2_000L
        const val ENCOUNTER_REVEAL_MILLIS =
            ENCOUNTER_SEARCH_MILLIS + ENCOUNTER_DISCOVERY_MILLIS
        const val VICTORY_PRESENTATION_MILLIS = 1_600L
        const val LOOT_RESULT_MILLIS = 3_000L
        const val RETURN_TO_TOWN_MILLIS = 4_000L
        const val EQUIP_LOOT_MILLIS = 2_000L
        const val SELL_ITEM_MILLIS = 1_000L
        const val BUY_EQUIPMENT_MILLIS = 5_000L
        const val SHOP_OFFER_MILLIS = 3_000L
        const val SHOP_RESULT_MILLIS = BUY_EQUIPMENT_MILLIS - SHOP_OFFER_MILLIS
        const val SHOP_EMPTY_RESULT_MILLIS = 2_000L
        const val DEPART_TO_FIELDS_MILLIS = 4_000L
        const val MONSTER_ENERGY_SCALE = 100L
        const val MAX_SKILLS = 20
        const val ACTS_PER_TALE = AdventureTaleCatalog.ACTS_PER_TALE
        const val OFFLINE_ADVENTURE_CHARGE_MILLIS = OfflineAdventureConfig.DEFAULT_CHARGE_MINUTES * 60_000L
        const val OFFLINE_ADVENTURE_CAPACITY_MILLIS = OfflineAdventureConfig.DEFAULT_CAPACITY_MINUTES * 60_000L
        private const val LEGACY_OFFLINE_ADVENTURE_EARN_RATE = 60L
        private const val MAX_STORED_OFFLINE_ADVENTURE_MILLIS =
            OfflineAdventureConfig.MAX_CAPACITY_MINUTES * 60_000L * 5L / 4L
        private const val REWARDED_OFFLINE_REQUEST_LEDGER_LIMIT = 32
        private val ENHANCEMENT_SUFFIX = Regex(" \\+[1-5]$")
        private val LEGACY_MONSTER_EPITHET_IN_RESULT = Regex(" · [^·]+(?= 처치 · 경험치 \\+)")
        private const val CURRENT_SCHEMA_VERSION = SIMPLE_GAME_SCHEMA_VERSION
        private const val OFFLINE_ADVENTURE_SCHEMA_VERSION = 9
        private const val LEGACY_AUTO_HUNT_SCHEMA_VERSION = 8
        private const val ATTACK_SKILL_CATALOG_SCHEMA_VERSION = 7
        private const val SIGNATURE_SKILL_CATALOG_SCHEMA_VERSION = 27
        private const val SKILL_PROC_SCHEMA_VERSION = 29
        private const val DAMAGE_ENERGY_SCHEMA_VERSION = 30
        private const val DAWN_BELL_STORY_SCHEMA_VERSION = 31
        private const val POSTGAME_JOURNEY_SCHEMA_VERSION = 33
        private const val EQUIPMENT_POWER_ALIGNMENT_SCHEMA_VERSION = 34
        private const val CLASS_PRIMARY_SECONDARY_OFFENSE_SCHEMA_VERSION = 36
        private const val MAIN_ACT_RHYTHM_SCHEMA_VERSION = 37
        private const val LABYRINTH_PROGRESSION_SCHEMA_VERSION = 38
        private const val MONSTER_ENERGY_REBASE_SCHEMA_VERSION = 40
        private const val CLASS_TERMINOLOGY_SCHEMA_VERSION = 41
        private const val WEIGHTED_SKILL_SELECTION_SCHEMA_VERSION = 42
        private const val DISPLAY_NAME_SCHEMA_VERSION = 16
        private const val HUNTING_PACING_SCHEMA_VERSION = 21
        private const val EXPERIENCE_CURVE_SCHEMA_VERSION = 22
        private const val STORY_EXPANSION_SCHEMA_VERSION = 22
        private const val ACTIVE_COMBAT_REBASE_SCHEMA_VERSION = 22
        private const val SHOP_POWER_FIRST_SCHEMA_VERSION = 24
        private const val LEGACY_MAIN_TALE_COUNT = 12
        private val DAWN_BELL_TALE_IDS = setOf(
            "ash_border.c02",
            "ash_border.c04",
            "ash_border.c09",
            "ash_border.c10",
            "ash_border.c12",
        )
        private const val MIN_COMBAT_DURATION_PERCENT = 60
        private const val MAX_COMBAT_DURATION_PERCENT = 140
        private const val SKILL_PROC_BASE_PERCENT = 8
        private const val SKILL_PROC_MIN_PERCENT = 8
        private const val SKILL_PROC_MAX_PERCENT = 20
        private const val SKILL_APTITUDE_PER_PERCENT = 6L
        internal const val MAX_CONSECUTIVE_BASIC_ATTACKS = 15
        internal const val NEW_SKILL_SELECTION_FADE_USES = 200L
        internal const val NEW_SKILL_MAX_EXTRA_SELECTION_WEIGHT = 7L
        internal const val BASIC_ATTACK_MIN_PERCENT = 40
        internal const val BASIC_ATTACK_MAX_PERCENT = 60
        private const val MYTHIC_RARITY_RANK = 5
        private const val LEGENDARY_RARITY_RANK = 4
        private const val SHOP_MAX_RARITY_RANK = 3
        private const val SHOP_RARITY_ROLL_BOUND = 1_000
        private const val EQUIPMENT_POWER_ROLL_MAX = 11
        // With the existing loot rarity bonuses, subtracting nine makes +3 loot use the exact
        // same range as +3 shop gear from level three onward while lower grades stay below it.
        private const val LOOT_POWER_OFFSET = 9L
        private const val LEGENDARY_MIN_SHOP_POWER_PERCENT = 102L
        private const val MYTHIC_MIN_SHOP_POWER_PERCENT = 105L
        private const val GOLD_UNIT = 10L
        private const val SHOP_PRICE_PER_LEVEL_SQUARED = 50L
        private const val QUADRATIC_SHOP_PRICE_SCHEMA_VERSION = 26
        private const val LOOT_RARITY_ROLL_BOUND = 1_000_000
        private const val RECENT_MONSTERS = 24
        private const val RECENT_ITEMS = 48
        internal const val MAX_LABYRINTH_HISTORY = 12
        internal const val MAX_LABYRINTH_GATE_HISTORY = 12
        private const val DEFAULT_SEED = 0x5A17_2026_0811L
        private const val PRESENTATION_SEED_SALT = 0x4D59_5DF4_D0F3_3173L
        private const val TALE_SEED_SALT = 0x29A7_63D1_4B5E_8F21L
    }
}
