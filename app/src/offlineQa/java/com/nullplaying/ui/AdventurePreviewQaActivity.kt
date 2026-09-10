package com.nullplaying.ui

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import com.nullplaying.AlarmQuestApplication
import com.nullplaying.BuildConfig
import com.nullplaying.MainActivity
import com.nullplaying.data.SimpleStateEntity
import com.nullplaying.data.SimpleAccountProgressEntity
import com.nullplaying.data.StartupPhase
import com.nullplaying.data.toEntity
import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.engine.AdventureEventEngine
import com.nullplaying.engine.AdventureTraitCatalog
import com.nullplaying.engine.AdventureTraitEngine
import com.nullplaying.engine.AdventureRelationshipEngine
import com.nullplaying.localization.AppLanguage
import com.nullplaying.model.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.UUID

/** Seeds this isolated package with outcomes produced by the real engine, then opens the real app. */
class AdventurePreviewQaActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(BuildConfig.DEBUG && BuildConfig.ADVENTURE_PREVIEW_ENABLED && BuildConfig.ADVENTURE_SYSTEM_ENABLED)
        check(BuildConfig.APPLICATION_ID.endsWith(".adventurepreview") && !BuildConfig.REMOTE_SERVICES_ENABLED)
        check(BuildConfig.SUPABASE_URL.isBlank() && BuildConfig.SUPABASE_PUBLISHABLE_KEY.isBlank())
        val app = application as AlarmQuestApplication
        val language = when (intent.getStringExtra("qa_language")) {
            "en" -> AppLanguage.ENGLISH
            "ja" -> AppLanguage.JAPANESE
            else -> AppLanguage.KOREAN
        }
        app.gameLanguageStore.setLanguage(language)
        lifecycleScope.launch {
            // AlarmQuestApplication starts the repository as soon as the process is created. Wait
            // for that empty first-run load before replacing Room with the deterministic fixture;
            // otherwise the startup job can publish its stale empty roster after this activity has
            // already seeded slot 1.
            val startup = app.gameRepository.snapshots.first {
                it.ready || it.startupPhase == StartupPhase.FAILED
            }
            check(startup.ready) { "Offline QA repository initialization failed" }
            val kind = intent.getStringExtra("qa_kind")
            val sequence = kind == "sequence"
            val recentLogs = kind == "recent_logs"
            val relationship = kind == "relationship"
            val trait = kind == "trait" || kind == "traits"
            val relationshipSource = relationship || (trait && intent.getStringExtra("qa_trait_channel") == "relationship")
            val engine = SimpleGameEngine(enableAdventureEvents = true,
                enableAdventureRelationships = relationshipSource, enableAdventureTraits = trait)
            val seed = intent.getLongExtra("qa_seed", 741L)
            val now = System.currentTimeMillis()
            val resultPhase = intent.getBooleanExtra("qa_result", false)
            val (state, events) = withContext(Dispatchers.Default) {
                if (sequence) sequenceScenario(engine, seed, now)
                else if (recentLogs) recentLogsScenario(engine, seed, now)
                else if (trait) traitScenario(engine, seed, now, resultPhase, relationshipSource)
                else if (relationship) relationshipScenario(engine, seed, now, resultPhase)
                else eventScenario(engine, seed, now, resultPhase)
            }
            val displayNow = System.currentTimeMillis()
            if (!sequence && !recentLogs && !relationship && !trait && !resultPhase &&
                state.adventurePhase == AdventurePhase.EVENT
            ) {
                // The real-engine catalog search can take longer than the event itself on a cold
                // emulator. Start the already-selected real event at display time so the QA run
                // can observe its actual 5 s discovery + 5 s action stages instead of arriving
                // after settlement. This source set is absent from release builds.
                val pending = checkNotNull(state.adventureJourney.pending)
                val fixtureStartedAt = displayNow + 15_000L
                state.adventureJourney.pending = pending.copy(startedAt = fixtureStartedAt)
                state.actionStartedAt = fixtureStartedAt
                state.actionEndsAt = fixtureStartedAt + pending.durationMillis
                state.lastSettledAt = displayNow
            }
            // Pausing the simulated clock clears attack presentation. Keep the exact, already
            // computed attack fields for this screenshot fixture, never a fabricated attack.
            val attack = if (trait && state.adventurePhase == AdventurePhase.COMBAT) state.copy() else null
            state.offlineAdventureMillis = 0
            engine.settleOfflineWithOfflineAdventure(state, displayNow)
            if (attack != null) {
                state.lastAttackName = attack.lastAttackName
                state.lastAttackType = attack.lastAttackType
                state.lastAttackWasSkill = attack.lastAttackWasSkill
                state.lastSkillCatalogId = attack.lastSkillCatalogId
                state.lastDamage = attack.lastDamage
                state.lastMonsterEnergyBeforeAttack = attack.lastMonsterEnergyBeforeAttack
            }
            // Longer hold only for screenshots; mechanics/outcome/rewards above are unchanged.
            if (trait && intent.getStringExtra("qa_trait_phase") == "before_effect") {
                // QA-only camera lead-in. The actual attack and trait decision remain unsettled.
                state.actionEndsAt = displayNow + 12_000L
            } else if (intent.getBooleanExtra("qa_hold", false)) {
                // Keep the real two-stage event timing visible after the QA activity's search has
                // finished. Re-anchor only the isolated screenshot fixture; the production engine
                // still owns its normal 5 s discovery + 5 s action duration.
                if (state.adventurePhase == AdventurePhase.EVENT) {
                    // Room can persist a long real-engine history before MainActivity appears.
                    // Give that diagnostic write a bounded lead-in so it cannot consume the 5 s
                    // discovery frame that this explicit hold mode is meant to photograph.
                    val fixtureStartedAt = if (intent.getStringExtra("qa_event_stage") == "action") {
                        displayNow - AdventureEventEngine.EVENT_PRESENTATION_MILLIS - 500L
                    } else {
                        displayNow + 60_000L
                    }
                    state.adventureJourney.pending = state.adventureJourney.pending?.copy(
                        startedAt = fixtureStartedAt,
                        durationMillis = 300_000L,
                    )
                    state.actionStartedAt = fixtureStartedAt
                    state.actionEndsAt = fixtureStartedAt + 300_000L
                } else if (state.adventurePhase == AdventurePhase.RELATIONSHIP) {
                    val run = state.adventureRelationships.pending
                    if (run != null) {
                        val fixtureStartedAt = when (intent.getStringExtra("qa_relationship_stage")) {
                            // Leave almost the full 5 s action stage available after the cold launch.
                            "action" -> displayNow - RELATIONSHIP_DISCOVERY_MILLIS - 250L
                            "battle" -> displayNow - RELATIONSHIP_DISCOVERY_MILLIS -
                                RELATIONSHIP_ACTION_MILLIS - 1_000L
                            else -> displayNow + 60_000L
                        }
                        val anchored = run.copy(startedAt = fixtureStartedAt)
                        state.adventureRelationships.pending = anchored
                        state.actionStartedAt = fixtureStartedAt
                        state.actionEndsAt = fixtureStartedAt + anchored.durationMillis
                    }
                } else {
                    state.actionEndsAt = displayNow + 300_000L
                }
            }
            app.database.withTransaction {
                for (slot in 1..3) {
                    app.database.stateDao().delete(slot)
                    app.database.stateDao().deleteRecentAdventureEvents(slot)
                }
                app.database.stateDao().save(SimpleStateEntity(payload = fixtureJson.encodeToString(state), updatedAt = displayNow))
                app.database.accountProgressDao().save(SimpleAccountProgressEntity(
                    activeCharacterSlotId = 1,
                    unlockedCharacterSlots = if (relationshipSource) 2 else 1,
                ))
                app.database.recentAdventureEventDao().insertAll(events.takeLast(300).map { it.toEntity(1) })
            }
            // Refresh the in-memory repository after the direct QA-only Room seed. MainActivity
            // renders this snapshot, so a database row alone is not sufficient in the same process.
            check(
                app.gameRepository.initialize(
                    now = displayNow,
                    trustedTime = app.trustedGameClock.nowOrNull()?.isServerVerified == true,
                    deferUnverifiedSettlement = false,
                ),
            ) { "Offline QA fixture reload failed" }
            val eventRun = state.adventureJourney.pending ?: state.adventureJourney.lastResult?.run
            val eventResult = state.adventureJourney.lastResult
            Log.i("AdventurePreviewQA", "SEEDED phase=${state.adventurePhase} event=${eventRun?.eventId} realOutcome=${eventRun?.outcome} reward=${eventRun?.rewardKind} itemReward=${eventRun?.itemReward} itemEquipped=${eventResult?.itemEquipped} routeUses=${eventRun?.routeRewardUses} network=false")
            if (sequence) Log.i("AdventurePreviewQA", "SEQUENCE_READY phase=${state.adventurePhase} recent=${state.adventureJourney.recentEventIds} queued=${state.adventureJourney.qaQueuedEventId} network=false")
            if (recentLogs) {
                val route = events.lastOrNull {
                    it.type == RecentAdventureEventType.ADVENTURE_EVENT &&
                        RecentAdventureEventMetadata.isRouteShortening(it.contextName)
                }
                Log.i(
                    "AdventurePreviewQA",
                    "RECENT_LOGS_READY total=${events.size} route=${route?.subjectId} " +
                        "level=${events.lastOrNull { it.type == RecentAdventureEventType.LEVEL_UP }?.currentValue} " +
                        "quest=${events.lastOrNull { it.type == RecentAdventureEventType.QUEST_COMPLETED }?.subjectId} " +
                        "source=real_engine_settlement network=false",
                )
            }
            if (relationship) {
                val run = state.adventureRelationships.pending ?: state.adventureRelationships.lastResult!!.run
                Log.i(
                    "AdventurePreviewQA",
                    "RELATIONSHIP scene=${run.sceneId} outcome=${run.outcome} " +
                        "battle=${run.battleKind}:${run.battleOutcome} " +
                        "delta=${AdventureRelationshipEngine.effectiveScoreDelta(run)} " +
                        "reward=${run.rewardKind} reunion=${run.reunion} sequence=${run.sequence} " +
                        "source=local_public_snapshot network=false",
                )
            }
            if (trait) Log.i("AdventurePreviewQA", "TRAIT_READY id=${intent.getStringExtra("qa_trait")} mode=${intent.getStringExtra("qa_trait_state") ?: "effect"} phase=${state.adventurePhase} class=${state.hero.heroClass} source=${state.adventureTraits.source?.key} traitSeed=${state.adventureTraits.seed} actual=${state.adventureTraits.visibleActivations.map { it.traitId + ":" + it.effectKind }} latestChange=${state.adventureTraits.recentChanges.lastOrNull()} network=false")
            val mainIntent = Intent(this@AdventurePreviewQaActivity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            if (intent.getBooleanExtra(ARENA_SERVER_MATCHING_QA_FIXTURE_EXTRA, false)) {
                mainIntent.putExtra(ARENA_SERVER_MATCHING_QA_FIXTURE_EXTRA, true)
                mainIntent.putExtra(
                    ARENA_SERVER_CANDIDATE_COUNT_QA_EXTRA,
                    intent.getIntExtra(ARENA_SERVER_CANDIDATE_COUNT_QA_EXTRA, 3),
                )
            }
            startActivity(mainIntent)
            finish()
        }
    }

    private fun sequenceScenario(
        engine: SimpleGameEngine,
        seed: Long,
        now: Long,
    ): Pair<SimpleGameState, List<RecentAdventureEvent>> {
        val roll = engine.rollStats(seed, HeroClass.RANGER)
        val state = engine.newGame("사건 순회", HeroClass.RANGER, roll.stats, roll.nextSeed, now - 60_000L)
        val events = engine.settle(state, state.actionEndsAt).recentEvents
        check(state.adventurePhase == AdventurePhase.COMBAT)
        state.adventureJourney.recentEventIds = emptyList()
        state.adventureJourney.qaQueuedEventId = ""
        state.adventureJourney.qaSequenceCursorEventId = ""
        return state to events
    }

    private fun recentLogsScenario(
        engine: SimpleGameEngine,
        seed: Long,
        now: Long,
    ): Pair<SimpleGameState, List<RecentAdventureEvent>> {
        var totalSteps = 0
        repeat(16) { attempt ->
            val heroClass = HeroClass.entries[(seed + attempt).mod(HeroClass.entries.size)]
            val roll = engine.rollStats(seed + attempt, heroClass)
            val state = engine.newGame(
                "기록 검수",
                heroClass,
                roll.stats,
                roll.nextSeed,
                now - 7L * DAY,
            )
            val events = mutableListOf<RecentAdventureEvent>()
            repeat(40_000) {
                totalSteps++
                events += engine.settle(state, state.actionEndsAt).recentEvents
                if (events.size > 300) events.subList(0, events.size - 300).clear()
                val hasRoute = events.any { event ->
                    event.type == RecentAdventureEventType.ADVENTURE_EVENT &&
                        RecentAdventureEventMetadata.isRouteShortening(event.contextName)
                }
                val hasLevel = events.any { it.type == RecentAdventureEventType.LEVEL_UP }
                val hasQuest = events.any { it.type == RecentAdventureEventType.QUEST_COMPLETED }
                if (hasRoute && hasLevel && hasQuest) {
                    check(events.none { event ->
                        event.type == RecentAdventureEventType.LEVEL_UP &&
                            RecentAdventureEventMetadata.decodeStatGrowth(event.contextName).isEmpty()
                    })
                    return state to events
                }
            }
        }
        error("Real-engine recent log fixture was not found after $totalSteps steps")
    }

    private fun eventScenario(engine: SimpleGameEngine, seed: Long, now: Long, resultPhase: Boolean): Pair<SimpleGameState, List<RecentAdventureEvent>> {
        val desiredId = intent.getStringExtra("qa_event")
        val desiredOutcome = intent.getStringExtra("qa_outcome")
        val desiredRewardKind = intent.getStringExtra("qa_reward_kind")?.let { value ->
            AdventureEventRewardKind.entries.firstOrNull { it.name == value }
                ?: error("Unknown qa_reward_kind: $value")
        }
        val desiredItemReward = intent.getStringExtra("qa_item_reward")?.let { value ->
            AdventureEventItemReward.entries.firstOrNull { it.name == value }
                ?: error("Unknown qa_item_reward: $value")
        }
        val desiredItemEquipped = if (intent.hasExtra("qa_item_equipped")) {
            intent.getBooleanExtra("qa_item_equipped", false)
        } else null
        val desiredItemAcquired = if (intent.hasExtra("qa_item_acquired")) {
            intent.getBooleanExtra("qa_item_acquired", false)
        } else null
        require(resultPhase || (desiredItemReward == null && desiredItemEquipped == null && desiredItemAcquired == null)) {
            "Item settlement filters require qa_result=true"
        }
        val heroClass = requestedHeroClass()
        var steps = 0
        repeat(32) { attempt ->
            val roll = engine.rollStats(seed + attempt, heroClass)
            val state = engine.newGame("여행자 별", heroClass, roll.stats, roll.nextSeed, now - DAY)
            val events = mutableListOf<RecentAdventureEvent>()
            repeat(20_000) {
                steps++
                events += engine.settle(state, state.actionEndsAt).recentEvents
                val result = state.adventureJourney.lastResult.takeIf {
                    resultPhase && state.adventurePhase == AdventurePhase.EVENT_RESULT
                }
                val run = if (resultPhase) result?.run
                    else if (!resultPhase && state.adventurePhase == AdventurePhase.EVENT) state.adventureJourney.pending else null
                if (run != null && (desiredId == null || run.eventId == desiredId) &&
                    (desiredOutcome == null || run.outcome.name == desiredOutcome) &&
                    (desiredRewardKind == null || run.rewardKind == desiredRewardKind) &&
                    (desiredItemReward == null || run.itemReward == desiredItemReward) &&
                    (desiredItemEquipped == null || result?.itemEquipped == desiredItemEquipped) &&
                    (desiredItemAcquired == null || ((result?.actualItemCount ?: 0) > 0) == desiredItemAcquired)) return state to events
            }
        }
        error("Requested real-engine event was not found after $steps steps")
    }

    private fun relationshipScenario(engine: SimpleGameEngine, seed: Long, now: Long, resultPhase: Boolean): Pair<SimpleGameState, List<RecentAdventureEvent>> {
        val desiredScene = intent.getStringExtra("qa_scene")
        val desiredOutcome = intent.getStringExtra("qa_outcome")
        val desiredReunion = if (intent.hasExtra("qa_reunion")) intent.getBooleanExtra("qa_reunion", false) else null
        val desiredDelta = intent.getStringExtra("qa_delta")
        val desiredBattleKind = intent.getStringExtra("qa_battle_kind")?.let { value ->
            AdventureRelationshipBattleKind.entries.firstOrNull { it.name == value }
                ?: error("Unknown qa_battle_kind: $value")
        }
        val desiredRewardKind = intent.getStringExtra("qa_reward_kind")?.let { value ->
            AdventureEventRewardKind.entries.firstOrNull { it.name == value }
                ?: error("Unknown qa_reward_kind: $value")
        }
        val candidateName = intent.getStringExtra("qa_candidate_name") ?: "바람 따라 걷는 별"
        require(candidateName.isNotBlank() && candidateName.length <= 24 && candidateName.none(Char::isISOControl))
        var steps = 0
        val heroClass = requestedHeroClass()
        repeat(200) { attempt ->
            val roll = engine.rollStats(seed + attempt, heroClass)
            val state = projectArenaStateForQa(
                engine.newGame("여행자 별", heroClass, roll.stats, roll.nextSeed, now - 7L * DAY),
                targetLevel = 20,
            )
            // A fixture creates a new local hero; never reuse another class's arena identity.
            state.rankingCharacterId = qaUuid("local-${heroClass.name}-$now-$seed")
            val events = mutableListOf<RecentAdventureEvent>()
            installLocalDailyDto(state, state.lastSettledAt, candidateName)
            var attemptSteps = 0
            while (state.actionEndsAt < now - 60_000L && steps++ < 400_000 && attemptSteps++ < 20_000) {
                val expires = state.adventureRelationships.roster!!.validUntil
                if (state.actionEndsAt >= expires) {
                    // The old pool settles first. A new daily fixture never rewrites earlier time.
                    events += engine.settle(state, expires).recentEvents
                    installLocalDailyDto(state, expires, candidateName)
                } else {
                    events += engine.settle(state, state.actionEndsAt).recentEvents
                }
                if (events.size > 600) events.subList(0, events.size - 300).clear()
                val run = if (resultPhase && state.adventurePhase == AdventurePhase.RELATIONSHIP_RESULT) state.adventureRelationships.lastResult?.run
                    else if (!resultPhase && state.adventurePhase == AdventurePhase.RELATIONSHIP) state.adventureRelationships.pending else null
                if (run != null && (desiredScene == null || run.sceneId == desiredScene) &&
                    (desiredOutcome == null || run.outcome.name == desiredOutcome) &&
                    (desiredReunion == null || run.reunion == desiredReunion) &&
                    (desiredBattleKind == null || run.battleKind == desiredBattleKind) &&
                    (desiredRewardKind == null || run.rewardKind == desiredRewardKind) &&
                    deltaMatches(AdventureRelationshipEngine.effectiveScoreDelta(run), desiredDelta)
                ) return state to events
                // A first meeting cannot happen again with this single local candidate. Try a
                // fresh seed instead of editing the engine's natural scheduling or outcome.
                if (desiredReunion == false && state.adventureRelationships.totalEncounters > 0L) break
            }
        }
        error("Requested real-engine relationship was not found after $steps steps")
    }

    /** Searches real, not-yet-settled boundaries; only QA ownership and the trait seed are supplied. */
    private fun traitScenario(engine: SimpleGameEngine, seed: Long, now: Long, resultPhase: Boolean,
        relationshipSource: Boolean): Pair<SimpleGameState, List<RecentAdventureEvent>> {
        val id = intent.getStringExtra("qa_trait") ?: "L01"
        require(AdventureTraitCatalog.find(id) != null) { "Unknown qa_trait: $id" }
        val mode = intent.getStringExtra("qa_trait_state") ?: "effect"
        require(mode in setOf("effect", "owned", "acquired", "weakened", "lost", "replaced", "recovered"))
        val requestedKind = when (mode) {
            "acquired" -> AdventureTraitChangeKind.ACQUIRED
            "weakened" -> AdventureTraitChangeKind.WEAKENED
            "lost" -> AdventureTraitChangeKind.LOST
            "replaced" -> AdventureTraitChangeKind.REPLACED
            "recovered" -> AdventureTraitChangeKind.RECOVERED
            else -> null
        }
        val explicitIds = intent.getStringExtra("qa_traits")?.split(',')?.map(String::trim)?.filter(String::isNotBlank)
        val initialIds = explicitIds ?: when (mode) {
            "acquired" -> emptyList()
            "replaced" -> listOf(AdventureTraitCatalog.definition(id).oppositeId).filter(String::isNotBlank)
            else -> listOf(id)
        }
        require(initialIds.all { AdventureTraitCatalog.find(it) != null })
        require(initialIds.none { AdventureTraitCatalog.definition(it).oppositeId in initialIds }) { "Opposite traits cannot be seeded together" }
        require(mode != "effect" || id in initialIds) { "The requested effect must be owned in this fixture" }
        require(mode != "replaced" || AdventureTraitCatalog.definition(id).oppositeId.isNotBlank())
        intent.getStringExtra("qa_trait_effect")?.let { value -> require(AdventureTraitEffectKind.entries.any { it.name == value }) }
        val heroClass = requestedHeroClass()
        val level = intent.getLongExtra("qa_start_level", if (relationshipSource) 20L else 1L).coerceIn(1L, 100L)
        val candidateName = intent.getStringExtra("qa_candidate_name") ?: "바람 따라 걷는 별"
        require(candidateName.isNotBlank() && candidateName.length <= 24 && candidateName.none(Char::isISOControl))
        val searchStarted = SystemClock.elapsedRealtime()
        var steps = 0
        repeat(16) { attempt ->
            val roll = engine.rollStats(seed + attempt, heroClass)
            val state = projectArenaStateForQa(
                engine.newGame("여행자 별", heroClass, roll.stats, roll.nextSeed, now - 30L * DAY),
                targetLevel = level.toInt(),
            )
            if (intent.hasExtra("qa_start_gold")) {
                // Optional synthetic QA budget; real offers, guards and payment stay in the engine.
                state.hero.gold = intent.getLongExtra("qa_start_gold", state.hero.gold).coerceIn(0L, 1_000_000_000L)
            }
            // A fixture creates a new local hero; never reuse another class's arena identity.
            state.rankingCharacterId = qaUuid("local-${heroClass.name}-$now-$seed")
            state.adventureTraits.owned = initialIds.distinct().mapIndexed { index, traitId ->
                AdventureOwnedTrait(traitId, state.lastSettledAt, index.toLong() + 1L)
            }
            val events = mutableListOf<RecentAdventureEvent>()
            if (relationshipSource) installLocalDailyDto(state, state.lastSettledAt, candidateName)
            if (mode == "owned") {
                events += engine.settle(state, state.actionEndsAt).recentEvents
                return state to events
            }
            while (state.actionEndsAt < now - 60_000L && steps++ < 400_000 &&
                SystemClock.elapsedRealtime() - searchStarted < 180_000L) {
                if (relationshipSource && state.actionEndsAt >= state.adventureRelationships.roster!!.validUntil) {
                    val expires = state.adventureRelationships.roster!!.validUntil
                    events += engine.settle(state, expires).recentEvents
                    installLocalDailyDto(state, expires, candidateName)
                }
                if (mode == "effect" && traitBoundaryMayDecide(id, state)) {
                    findTraitEffect(engine, state, id, resultPhase)?.let { found ->
                        Log.i("AdventurePreviewQA", "TRAIT_SEARCH id=$id steps=$steps elapsedMs=${SystemClock.elapsedRealtime() - searchStarted} seed=${found.first.adventureTraits.seed} originalSource=${state.adventureTraits.source?.key} source=real_engine_boundary")
                        return found.first to (events + found.second).takeLast(300)
                    }
                }
                val changeBefore = state.adventureTraits.changeSequence
                events += engine.settle(state, state.actionEndsAt).recentEvents
                if (events.size > 600) events.subList(0, events.size - 300).clear()
                val change = state.adventureTraits.recentChanges.lastOrNull()
                if (requestedKind != null && change != null && change.sequence > changeBefore &&
                    change.traitId == id && change.kind == requestedKind) {
                    Log.i("AdventurePreviewQA", "TRAIT_LIFECYCLE id=$id kind=${change.kind} steps=$steps source=${change.sourceKey} source=real_base_experiences")
                    return state to events
                }
                if (steps % 5_000 == 0) Log.i("AdventurePreviewQA", "TRAIT_SEARCH_PROGRESS id=$id mode=$mode steps=$steps level=${state.hero.level}")
                // A fresh attempt keeps the requested fixture ownership honest; a lost trait is
                // never silently equipped again partway through its source or observation window.
                if (mode == "effect" && state.adventureTraits.owned.none { it.traitId == id }) break
            }
        }
        error("Requested real-engine trait $id/$mode was not found after $steps steps; existing save was not replaced")
    }

    private fun traitBoundaryMayDecide(id: String, state: SimpleGameState): Boolean = when (id) {
        "C03", "C04" -> state.adventurePhase in setOf(AdventurePhase.OPENING, AdventurePhase.LOOTING, AdventurePhase.DEPARTING)
        "L01", "L02", "L05", "G01" -> state.adventurePhase == AdventurePhase.EVENT ||
            (state.adventurePhase == AdventurePhase.COMBAT && state.combatPhase == CombatPhase.VICTORY)
        "S01", "S02" -> state.adventurePhase in setOf(AdventurePhase.RETURNING, AdventurePhase.EQUIPPING, AdventurePhase.SELLING)
        "S05" -> state.adventurePhase in setOf(AdventurePhase.RETURNING, AdventurePhase.EQUIPPING, AdventurePhase.SELLING, AdventurePhase.SHOPPING_RESULT)
        "L04" -> state.adventurePhase == AdventurePhase.SHOPPING_EMPTY
        "E03", "R05", "R06" -> state.adventurePhase in setOf(AdventurePhase.LOOTING, AdventurePhase.DEPARTING)
        else -> false
    }

    private fun findTraitEffect(engine: SimpleGameEngine, before: SimpleGameState, id: String,
        resultPhase: Boolean): Pair<SimpleGameState, List<RecentAdventureEvent>>? {
        val probe = cloneState(before)
        val oldKeys = before.adventureTraits.decisions.map { it.key }.toSet()
        engine.settle(probe, probe.actionEndsAt)
        val decision = probe.adventureTraits.decisions.lastOrNull { it.key.startsWith("$id:") && it.key !in oldKeys }
            ?: return null
        val originalKey = decision.key.removePrefix("$id:")
        val sourceKey = probe.adventureTraits.source?.key ?: return null
        val effectKind = intent.getStringExtra("qa_trait_effect")
            ?: if (intent.getStringExtra("qa_trait_channel") == "relationship") "RELATIONSHIP" else null
        val desiredDirection = intent.getStringExtra("qa_trait_delta")
        val wantedCase = intent.getStringExtra("qa_trait_case")
        val beforeSequence = before.adventureTraits.activationSequence
        var candidateSeed = before.adventureTraits.seed
        repeat(64) {
            var checked = 0
            do { candidateSeed++; checked++ } while (AdventureTraitEngine.random(candidateSeed, decision.key) >= decision.threshold && checked < 2_000_000)
            if (checked >= 2_000_000) return null
            val trial = cloneState(before)
            trial.adventureTraits.seed = candidateSeed
            val trialEvents = mutableListOf<RecentAdventureEvent>()
            for (step in 0 until 32) {
                trialEvents += engine.settle(trial, trial.actionEndsAt).recentEvents
                val activation = trial.adventureTraits.visibleActivations.lastOrNull {
                    it.traitId == id && it.sequence > beforeSequence && it.sourceKey == originalKey &&
                        (effectKind == null || it.effectKind.name == effectKind) &&
                        deltaMatches(it.currentValue.compareTo(it.previousValue), desiredDirection)
                }
                val paid = wantedCase != "paid" || (trial.adventurePhase == AdventurePhase.SHOPPING_RESULT &&
                    trial.adventureTraits.shopVisit?.pendingIsExtra == true && trial.lastShopPurchase != null)
                if (activation != null && paid && adventureTraitEffectVisibleInPhase(activation.effectKind, trial.adventurePhase)) {
                    if (intent.getStringExtra("qa_trait_phase") == "before_effect") {
                        require(id == "C03" || id == "C04") { "before_effect is a combat camera fixture" }
                        val pending = cloneState(before).apply { adventureTraits.seed = candidateSeed }
                        Log.i("AdventurePreviewQA", "TRAIT_BEFORE_EFFECT id=$id seed=$candidateSeed phase=${pending.adventurePhase} decision=${decision.key} unsettled=true cameraLeadInMs=12000")
                        return pending to emptyList()
                    }
                    if (resultPhase && trial.adventurePhase in setOf(AdventurePhase.EVENT, AdventurePhase.RELATIONSHIP))
                        trialEvents += engine.settle(trial, trial.actionEndsAt).recentEvents
                    if (id == "E03" && intent.getStringExtra("qa_trait_phase") == "retry" && trial.adventurePhase == AdventurePhase.EVENT) {
                        trial.adventureTraits.source?.retryRun?.let { retry ->
                            trialEvents += engine.settle(trial, retry.startedAt).recentEvents
                        }
                    }
                    return trial to trialEvents
                }
                if (trial.adventureTraits.source?.key != sourceKey) break
            }
        }
        return null
    }

    private fun requestedHeroClass(): HeroClass = intent.getStringExtra("qa_hero_class")?.let { name ->
        HeroClass.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: error("Unknown qa_hero_class: $name")
    } ?: HeroClass.RANGER

    private fun cloneState(state: SimpleGameState): SimpleGameState = fixtureJson.decodeFromString(fixtureJson.encodeToString(state))

    private fun installLocalDailyDto(state: SimpleGameState, receivedAt: Long, candidateName: String) {
        val opponentClass = HeroClass.entries[(state.hero.heroClass.ordinal + 1) % HeroClass.entries.size]
        val day = Math.floorDiv(receivedAt, DAY)
        val projectionId = qaUuid("remote-$candidateName-$day")
        val publicSnapshot = PublicPlayerSnapshot(
            projectionId = projectionId,
            displayName = candidateName,
            heroClass = opponentClass,
            level = state.hero.level,
            combatPower = (1L + (state.hero.level - 1L) * 5L) * 2L,
            rulesVersion = SHARED_PLAYER_RULES_VERSION,
            snapshotVersion = SHARED_PLAYER_SNAPSHOT_VERSION,
            stats = PublicPlayerStats(
                strength = state.hero.stats.strength,
                constitution = state.hero.stats.constitution,
                dexterity = state.hero.stats.dexterity,
                intelligence = state.hero.stats.intelligence,
                wisdom = state.hero.stats.wisdom,
                charisma = state.hero.stats.charisma,
                maxHealth = state.hero.stats.maxHealth.coerceAtLeast(1L),
                maxMana = state.hero.stats.maxMana.coerceAtLeast(0L),
            ),
            adventureTraitIds = listOf("R03", "E02"),
        )
        val roster = PublicPlayerRoster(
            requesterCharacterId = state.rankingCharacterId,
            requesterLevel = state.hero.level,
            rosterId = qaUuid("roster-$day"),
            rosterDateUtc = "2026-09-07",
            rulesVersion = SHARED_PLAYER_RULES_VERSION,
            receivedAtEpochMillis = receivedAt,
            validUntilEpochMillis = receivedAt + DAY,
            snapshots = listOf(publicSnapshot),
        )
        state.publicPlayerRoster = roster
        state.adventureRelationships.roster = roster.toAdventureEncounterRoster()
    }

    private fun qaUuid(source: String): String =
        UUID.nameUUIDFromBytes(source.toByteArray(Charsets.UTF_8)).toString()

    private fun deltaMatches(delta: Int, desired: String?): Boolean = when (desired) {
        null -> true
        "positive" -> delta > 0
        "negative" -> delta < 0
        "zero" -> delta == 0
        else -> delta == desired.toIntOrNull()
    }

    private companion object {
        const val DAY = 86_400_000L
        val fixtureJson = Json { encodeDefaults = true }
    }
}
