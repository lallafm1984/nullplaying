package com.nullplaying.engine

import com.nullplaying.model.AdventureEventItemReward
import com.nullplaying.model.AdventureEventOutcome
import com.nullplaying.model.AdventureEventRewardKind
import com.nullplaying.model.AdventureEventRun
import com.nullplaying.model.AdventureEventStat
import com.nullplaying.model.AdventureJourneyState
import com.nullplaying.model.AdventurePhase
import com.nullplaying.model.CombatPhase
import com.nullplaying.model.HeroClass
import com.nullplaying.model.HeroStats
import com.nullplaying.model.InventoryItem
import com.nullplaying.model.MonsterGrade
import com.nullplaying.model.RecentAdventureEventMetadata
import com.nullplaying.model.RecentAdventureEventType
import com.nullplaying.model.SimpleGameState
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdventureEventEngineTest {
    private val engine = SimpleGameEngine(enableAdventureEvents = true)

    @Test
    fun `feature is opt in and disabled engine keeps the existing timeline`() {
        val defaultEngine = SimpleGameEngine()
        val explicitDisabled = SimpleGameEngine(enableAdventureEvents = false)
        val original = newGame(defaultEngine)
        val disabled = newGame(explicitDisabled)
        defaultEngine.settleOffline(original, HOUR)
        explicitDisabled.settleOffline(disabled, HOUR)
        assertEquals(original, disabled)
        assertFalse(original.adventureJourney.initialized)
        assertEquals(0L, original.adventureJourney.completedEvents)
    }

    @Test
    fun `catalog has one hundred distinct playable multilingual situations and three approaches each`() {
        assertEquals(100, AdventureEventEngine.all.size)
        assertEquals(100, AdventureEventEngine.all.map { it.id }.distinct().size)
        assertEquals(100, AdventureEventEngine.all.map { it.title.ko }.distinct().size)
        AdventureEventEngine.all.forEach { event ->
            assertEquals(3, event.approaches.size)
            assertEquals(3, event.approaches.map { it.id }.distinct().size)
            (listOf(event.title, event.scene, event.success, event.partial, event.failure, event.itemName) +
                event.approaches.map { it.title }).forEach {
                assertTrue(it.ko.isNotBlank() && it.en.isNotBlank() && it.ja.isNotBlank())
            }
            assertTrue(event.storyFamily.isNotBlank())
            event.approaches.forEach { approach ->
                assertTrue(approach.behaviorSignals.isNotEmpty())
                AdventureBehaviorSignal.entries.chunked(2).forEach { pair ->
                    if (pair.size == 2) assertFalse(approach.behaviorSignals.containsAll(pair.toSet()))
                }
            }
        }
    }

    @Test
    fun `offline qa can request every authored situation without replacing its real resolution`() {
        val game = newGame()

        AdventureEventEngine.all.forEachIndexed { index, definition ->
            val run = AdventureEventEngine.beginForQa(
                state = game,
                eventAt = index * 30_000L,
                eventId = definition.id,
            )

            assertEquals(definition.id, run.eventId)
            assertTrue(run.approachId in definition.approaches.map { it.id })
            assertTrue(run.rewardKind != AdventureEventRewardKind.UNSPECIFIED)
            assertTrue(run.outcome in AdventureEventOutcome.entries)
            game.adventureJourney.pending = null
        }
    }

    @Test
    fun `offline qa queue preserves combat then follows the normal action result and return flow`() {
        val game = newGame()
        engine.settleOffline(game, game.actionEndsAt)
        assertEquals(AdventurePhase.COMBAT, game.adventurePhase)

        AdventureEventEngine.all.forEach { definition ->
            game.inventory.clear()
            val phaseBefore = game.adventurePhase
            val monsterBefore = game.monster
            val actionStartedBefore = game.actionStartedAt
            val actionEndsBefore = game.actionEndsAt
            assertTrue(engine.queueAdventureEventForQa(game, definition.id))
            assertEquals(definition.id, game.adventureJourney.qaQueuedEventId)
            assertEquals(definition.id, game.adventureJourney.qaSequenceCursorEventId)
            assertEquals(phaseBefore, game.adventurePhase)
            assertEquals(monsterBefore, game.monster)
            assertEquals(actionStartedBefore, game.actionStartedAt)
            assertEquals(actionEndsBefore, game.actionEndsAt)
            assertFalse(engine.queueAdventureEventForQa(game, definition.id))

            settleUntil(game) { it.adventurePhase == AdventurePhase.EVENT }
            assertEquals("", game.adventureJourney.qaQueuedEventId)
            assertEquals(definition.id, game.adventureJourney.pending?.eventId)
            assertEquals(AdventureEventEngine.ACTION_MILLIS, game.actionEndsAt - game.actionStartedAt)

            engine.settleOffline(game, game.actionEndsAt)
            assertEquals(AdventurePhase.EVENT_RESULT, game.adventurePhase)
            assertEquals(definition.id, game.adventureJourney.lastResult?.run?.eventId)
            assertEquals(AdventureEventEngine.RESULT_MILLIS, game.actionEndsAt - game.actionStartedAt)

            engine.settleOffline(game, game.actionEndsAt)
            assertEquals(AdventurePhase.COMBAT, game.adventurePhase)
            if (game.adventureJourney.eventBattle != null) {
                game.monster.currentEnergy = 0L
                game.combatPhase = CombatPhase.VICTORY
                engine.settleOffline(game, game.actionEndsAt)
                assertEquals(AdventurePhase.LOOTING, game.adventurePhase)
                engine.settleOffline(game, game.actionEndsAt)
                assertEquals(AdventurePhase.COMBAT, game.adventurePhase)
            }
        }
        assertEquals(AdventureEventEngine.all.size.toLong(), game.adventureJourney.completedEvents)

        val disabled = SimpleGameEngine()
        val disabledGame = newGame(disabled)
        disabled.settleOffline(disabledGame, disabledGame.actionEndsAt)
        assertFalse(disabled.queueAdventureEventForQa(disabledGame, "bridge"))
    }

    @Test
    fun `offline qa queue does not consume an existing route reward or failure delay`() {
        val game = newGame()
        engine.settleOffline(game, game.actionEndsAt)
        game.adventureJourney.nextEncounterDelayAdjustmentMillis = 2_000L
        game.adventureJourney.routeRewardDelayQueueMillis = listOf(-1_500L, -900L)

        assertTrue(engine.queueAdventureEventForQa(game, "bridge"))

        assertEquals(2_000L, game.adventureJourney.nextEncounterDelayAdjustmentMillis)
        assertEquals(listOf(-1_500L, -900L), game.adventureJourney.routeRewardDelayQueueMillis)
        assertEquals(AdventurePhase.COMBAT, game.adventurePhase)

        settleUntil(game) { it.adventurePhase == AdventurePhase.EVENT }

        assertEquals(2_000L, game.adventureJourney.nextEncounterDelayAdjustmentMillis)
        assertEquals(listOf(-1_500L, -900L), game.adventureJourney.routeRewardDelayQueueMillis)
    }

    @Test
    fun `offline qa can queue the next situation while the current result remains visible`() {
        val game = newGame()
        advanceToEvent(game)
        game.adventureJourney.pending = requireNotNull(game.adventureJourney.pending).copy(
            battleGrade = null,
            battleRewardKind = com.nullplaying.model.AdventureEventBattleRewardKind.NONE,
        )
        engine.settleOffline(game, game.actionEndsAt)
        assertEquals(AdventurePhase.EVENT_RESULT, game.adventurePhase)

        assertTrue(engine.queueAdventureEventForQa(game, "rescue"))
        assertEquals("rescue", game.adventureJourney.qaQueuedEventId)

        engine.settleOffline(game, game.actionEndsAt)
        settleUntil(game) { it.adventurePhase == AdventurePhase.EVENT }
        assertEquals("rescue", game.adventureJourney.pending?.eventId)
    }

    @Test
    fun `legacy journey payload defaults both offline qa fields to empty`() {
        val current = AdventureJourneyState(
            initialized = true,
            qaQueuedEventId = "bridge",
            qaSequenceCursorEventId = "bridge",
        )
        val encoded = Json.parseToJsonElement(Json.encodeToString(current)).jsonObject
        val legacyJson = JsonObject(
            encoded.filterKeys {
                it !in setOf(
                    "qaQueuedEventId",
                    "qaSequenceCursorEventId",
                    "recentStoryFamilies",
                    "nextEventContext",
                    "eventBattle",
                )
            },
        ).toString()

        val restored = Json.decodeFromString<AdventureJourneyState>(legacyJson)

        assertEquals("", restored.qaQueuedEventId)
        assertEquals("", restored.qaSequenceCursorEventId)
        assertTrue(restored.recentStoryFamilies.isEmpty())
        assertEquals(com.nullplaying.model.AdventureEventContext.FIELD_EXPLORATION, restored.nextEventContext)
        assertEquals(null, restored.eventBattle)
    }

    @Test
    fun `catalog reward weights match the authored event table and average twenty five percent items`() {
        assertEquals(100, AdventureEventEngine.all.size)
        AdventureEventEngine.all.forEach { definition ->
            assertEquals(100, definition.rewardWeights.run { experience + gold + item + route })
            assertTrue(definition.rewardWeights.run { listOf(experience, gold, item, route).all { it > 0 } })
            assertTrue(definition.successRouteDelayMillis < 0L)
            assertTrue(definition.successGoldPerLevel > 0L)
            assertEquals(AdventureEventItemReward.EQUIPMENT, definition.successItem)
            val exactCounts = (0 until 100).groupingBy {
                AdventureEventEngine.rewardKindForRoll(definition, it)
            }.eachCount()
            assertEquals(definition.rewardWeights.experience, exactCounts[AdventureEventRewardKind.EXPERIENCE])
            assertEquals(definition.rewardWeights.gold, exactCounts[AdventureEventRewardKind.GOLD])
            assertEquals(definition.rewardWeights.item, exactCounts[AdventureEventRewardKind.ITEM])
            assertEquals(definition.rewardWeights.route, exactCounts[AdventureEventRewardKind.ROUTE])
        }
        assertEquals(2_500, AdventureEventEngine.all.sumOf { it.rewardWeights.item })
    }

    @Test
    fun `reward weight boundaries use the fixed experience gold item route order`() {
        val bridge = AdventureEventEngine.definition("bridge")
        assertEquals(AdventureEventRewardKind.EXPERIENCE, AdventureEventEngine.rewardKindForRoll(bridge, 0))
        assertEquals(AdventureEventRewardKind.EXPERIENCE, AdventureEventEngine.rewardKindForRoll(bridge, 24))
        assertEquals(AdventureEventRewardKind.GOLD, AdventureEventEngine.rewardKindForRoll(bridge, 25))
        assertEquals(AdventureEventRewardKind.GOLD, AdventureEventEngine.rewardKindForRoll(bridge, 34))
        assertEquals(AdventureEventRewardKind.ITEM, AdventureEventEngine.rewardKindForRoll(bridge, 35))
        assertEquals(AdventureEventRewardKind.ITEM, AdventureEventEngine.rewardKindForRoll(bridge, 49))
        assertEquals(AdventureEventRewardKind.ROUTE, AdventureEventEngine.rewardKindForRoll(bridge, 50))
        assertEquals(AdventureEventRewardKind.ROUTE, AdventureEventEngine.rewardKindForRoll(bridge, 99))
    }

    @Test
    fun `success partial and failure preserve the selected single reward category`() {
        val game = newGame()
        val generated = AdventureEventEngine.begin(game, 1L)

        AdventureEventEngine.all.forEach { definition ->
            REWARD_KINDS.forEach { rewardKind ->
                val base = generated.copy(eventId = definition.id, rewardKind = rewardKind)
                val success = AdventureEventEngine.withOutcome(base, AdventureEventOutcome.SUCCESS, 91L)
                val partial = AdventureEventEngine.withOutcome(base, AdventureEventOutcome.PARTIAL, 91L)
                val failure = AdventureEventEngine.withOutcome(base, AdventureEventOutcome.FAILURE, 91L)
                assertEquals(rewardKind, success.rewardKind)
                assertEquals(rewardKind, partial.rewardKind)
                assertEquals(rewardKind, failure.rewardKind)
                listOf(success, partial, failure).forEach { resolved ->
                    if (resolved.battleGrade != null) {
                        assertTrue(positiveRewardKinds(resolved).isEmpty())
                        assertEquals(0L, resolved.routeDelayMillis)
                        assertTrue(resolved.battleRewardKind != com.nullplaying.model.AdventureEventBattleRewardKind.NONE)
                    } else if (resolved.outcome == AdventureEventOutcome.FAILURE) {
                        assertTrue(positiveRewardKinds(resolved).isEmpty())
                        assertEquals(2_000L, resolved.routeDelayMillis)
                        assertEquals(0, resolved.routeRewardUses)
                    } else {
                        assertEquals(setOf(rewardKind), positiveRewardKinds(resolved))
                    }
                }
                if (success.battleGrade == null && partial.battleGrade == null) {
                    when (rewardKind) {
                        AdventureEventRewardKind.ROUTE -> {
                            assertTrue(success.routeRewardUses in 2..10)
                            assertEquals(success.routeRewardUses, partial.routeRewardUses)
                            assertTrue(-partial.routeDelayMillis < -success.routeDelayMillis)
                        }
                        AdventureEventRewardKind.GOLD ->
                            assertTrue(partial.goldReward in 1L until success.goldReward)
                        AdventureEventRewardKind.ITEM ->
                            assertEquals(AdventureEventItemReward.TROPHY, partial.itemReward)
                        AdventureEventRewardKind.EXPERIENCE ->
                            assertTrue(partial.experienceReward in 1L until success.experienceReward)
                        AdventureEventRewardKind.UNSPECIFIED -> error("UNSPECIFIED is not selectable")
                    }
                }
            }
        }
    }

    @Test
    fun `route reward roll has the exact two to ten distribution for success and partial`() {
        assertEquals(
            mapOf(2 to 300, 3 to 240, 4 to 180, 5 to 120, 6 to 70, 7 to 40, 8 to 25, 9 to 15, 10 to 10),
            (0 until AdventureEventEngine.ROUTE_REWARD_ROLL_BOUND)
                .groupingBy(AdventureEventEngine::routeRewardUsesForRoll)
                .eachCount(),
        )
        val totalUses = (0 until AdventureEventEngine.ROUTE_REWARD_ROLL_BOUND)
            .sumOf(AdventureEventEngine::routeRewardUsesForRoll)
        assertEquals(3.775, totalUses.toDouble() / AdventureEventEngine.ROUTE_REWARD_ROLL_BOUND, 0.0)
        (0 until AdventureEventEngine.ROUTE_REWARD_ROLL_BOUND).forEach { roll ->
            val successUses = AdventureEventEngine.routeRewardUsesForRoll(roll)
            assertTrue(successUses in 2..10)
        }
    }

    @Test
    fun `relevant ability changes probability monotonically while unrelated ability does not`() {
        val stats = HeroStats(10L, 10L, 10L, 10L, 10L, 10L, 50L, 30L)
        val approach = AdventureEventEngine.definition("bridge").approaches.first()
        val baseline = AdventureEventEngine.successBasisPoints(stats, 1L, approach)
        assertTrue(AdventureEventEngine.successBasisPoints(stats.copy(strength = 20L), 1L, approach) > baseline)
        assertTrue(AdventureEventEngine.successBasisPoints(stats.copy(constitution = 20L), 1L, approach) > baseline)
        assertEquals(baseline, AdventureEventEngine.successBasisPoints(stats.copy(charisma = 100L), 1L, approach))
        assertTrue(AdventureEventEngine.successBasisPoints(stats.copy(strength = Long.MAX_VALUE), 1L, approach) in 1_500..8_500)
    }

    @Test
    fun `normal event shows five seconds of event and five seconds of action before its result`() {
        val game = newGame()
        advanceToEvent(game)
        val pending = requireNotNull(game.adventureJourney.pending)
        assertEquals(10_000L, game.actionEndsAt - game.actionStartedAt)
        val kills = game.totalKills
        val items = game.totalItemsFound
        val experience = game.hero.experience
        val gold = game.hero.gold
        val progress = game.adventureTale.activeAct().progress
        val started = game.actionStartedAt
        engine.settleOffline(game, started + AdventureEventEngine.EVENT_PRESENTATION_MILLIS)
        assertEquals(AdventurePhase.EVENT, game.adventurePhase)
        assertEquals(0L, game.adventureJourney.completedEvents)
        assertEquals(kills, game.totalKills)
        assertEquals(items, game.totalItemsFound)
        assertEquals(experience, game.hero.experience)
        assertEquals(gold, game.hero.gold)
        assertEquals(progress, game.adventureTale.activeAct().progress)
        engine.settleOffline(game, game.actionEndsAt - 1L)
        assertEquals(0L, game.adventureJourney.completedEvents)
        val delta = engine.settleOffline(game, game.actionEndsAt)
        assertEquals(AdventurePhase.EVENT_RESULT, game.adventurePhase)
        assertEquals(5_000L, game.actionEndsAt - game.actionStartedAt)
        assertEquals(5_000L, AdventureEventEngine.EVENT_PRESENTATION_MILLIS)
        assertEquals(5_000L, AdventureEventEngine.ACTION_PRESENTATION_MILLIS)
        assertEquals(10_000L, AdventureEventEngine.ACTION_MILLIS)
        assertEquals(15_000L, AdventureEventEngine.ACTION_MILLIS + AdventureEventEngine.RESULT_MILLIS)
        assertEquals(kills, game.totalKills)
        assertEquals(progress + 1L, game.adventureTale.activeAct().progress)
        assertEquals(1L, game.adventureJourney.completedEvents)
        assertEquals(1, delta.recentEvents.count { it.type == RecentAdventureEventType.ADVENTURE_EVENT })
        val result = requireNotNull(game.adventureJourney.lastResult)
        assertEquals(pending, result.run)
        assertEquals(started + AdventureEventEngine.ACTION_MILLIS, result.occurredAt)
        assertEquals(pending.experienceReward, result.experienceAwarded)
        assertEquals(pending.goldReward, result.goldAwarded)
        assertEquals(1L, result.progressAdded)
        assertEquals(null, game.adventureJourney.pending)
        val rewardSnapshot = Json.encodeToString(game)
        engine.settleOffline(game, game.lastSettledAt)
        assertEquals(rewardSnapshot, Json.encodeToString(game))
    }

    @Test
    fun `serialized restart during event and result cannot duplicate rewards`() {
        val continuous = newGame()
        advanceToEvent(continuous)
        engine.settleOffline(continuous, continuous.actionStartedAt + 9_000L)
        var restored = restore(continuous)
        val completion = continuous.actionEndsAt
        engine.settleOffline(continuous, completion)
        engine.settleOffline(restored, completion)
        assertEquals(continuous, restored)
        restored = restore(restored)
        engine.settleOffline(continuous, completion + AdventureEventEngine.RESULT_MILLIS)
        engine.settleOffline(restored, completion + AdventureEventEngine.RESULT_MILLIS)
        assertEquals(continuous, restored)
        assertEquals(1L, restored.adventureJourney.completedEvents)
    }

    @Test
    fun `reward kind route uses and distinct route queue survive serialized restart`() {
        val game = newGame()
        advanceToEvent(game)
        val routeRun = requireNotNull(game.adventureJourney.pending).copy(
            experienceReward = 0L,
            goldReward = 0L,
            itemReward = AdventureEventItemReward.NONE,
            routeDelayMillis = -1_500L,
            rewardKind = AdventureEventRewardKind.ROUTE,
            routeRewardUses = 9,
        )
        game.adventureJourney.pending = routeRun
        game.adventureJourney.routeRewardDelayQueueMillis = listOf(-1_500L, -900L)

        val restored = restore(game)

        assertEquals(routeRun, restored.adventureJourney.pending)
        assertEquals(listOf(-1_500L, -900L), restored.adventureJourney.routeRewardDelayQueueMillis)
    }

    @Test
    fun `legacy run defaults reward metadata and infers its existing positive category`() {
        val game = newGame()
        val source = AdventureEventEngine.begin(game, 1L).copy(
            outcome = AdventureEventOutcome.SUCCESS,
            experienceReward = 0L,
            goldReward = 123L,
            itemReward = AdventureEventItemReward.NONE,
            routeDelayMillis = 0L,
            rewardKind = AdventureEventRewardKind.GOLD,
            routeRewardUses = 0,
        )
        val encoded = Json.parseToJsonElement(Json.encodeToString(source)).jsonObject
        val legacyJson = JsonObject(encoded.filterKeys { it != "rewardKind" && it != "routeRewardUses" }).toString()

        val legacy = Json.decodeFromString<AdventureEventRun>(legacyJson)
        assertEquals(AdventureEventRewardKind.UNSPECIFIED, legacy.rewardKind)
        assertEquals(0, legacy.routeRewardUses)

        val rebuilt = AdventureEventEngine.withOutcome(legacy, AdventureEventOutcome.SUCCESS, 91L)
        assertEquals(AdventureEventRewardKind.GOLD, rebuilt.rewardKind)
        assertEquals(setOf(AdventureEventRewardKind.GOLD), positiveRewardKinds(rebuilt))
    }

    @Test
    fun `split offline settlement preserves every event reward seed and route`() {
        val continuous = newGame()
        val split = newGame()
        engine.settleOffline(continuous, 4L * HOUR)
        repeat(48) { engine.settleOffline(split, (it + 1L) * 5L * 60_000L) }
        assertEquals(continuous, split)
        assertTrue(
            "Four active hours should contain occasional events: ${continuous.adventureJourney.completedEvents}",
            continuous.adventureJourney.completedEvents in 7L..16L,
        )
        assertTrue(continuous.adventureJourney.recentResults.size <= AdventureEventEngine.HISTORY_LIMIT)
    }

    @Test
    fun `online and offline use identical incident decisions rewards and progression`() {
        val online = newGame()
        val offline = newGame()
        engine.settle(online, HOUR)
        engine.settleOffline(offline, HOUR)
        // Offline hides attack presentation; normalize only that existing presentation distinction.
        engine.settleOffline(online, HOUR + 1L)
        engine.settleOffline(offline, HOUR + 1L)
        assertEquals(online, offline)
    }

    @Test
    fun `legacy save without journey initializes deterministically without replaying prior time`() {
        val legacyEngine = SimpleGameEngine()
        val legacy = newGame(legacyEngine)
        legacyEngine.settleOffline(legacy, HOUR)
        val encoded = Json.parseToJsonElement(Json.encodeToString(legacy)).jsonObject
        val legacyJson = JsonObject(encoded.filterKeys { it != "adventureJourney" }).toString()
        val first = Json.decodeFromString<SimpleGameState>(legacyJson)
        val second = Json.decodeFromString<SimpleGameState>(legacyJson)
        engine.settleOffline(first, HOUR + 1L)
        engine.settleOffline(second, HOUR + 1L)
        assertEquals(first, second)
        assertTrue(first.adventureJourney.initialized)
        assertTrue(first.adventureJourney.nextEventAt >= HOUR + AdventureEventEngine.MIN_INTERVAL_MILLIS)
        assertEquals(0L, first.adventureJourney.completedEvents)
    }

    @Test
    fun `event schedule stays between fifteen and thirty active minutes`() {
        repeat(200) { index ->
            val game = newGame(seed = 10_000L + index)
            val delay = game.adventureJourney.nextEventAt - game.lastSettledAt
            assertTrue("delay=$delay", delay in AdventureEventEngine.MIN_INTERVAL_MILLIS..AdventureEventEngine.MAX_INTERVAL_MILLIS)
        }
        assertEquals(15L * 60_000L, AdventureEventEngine.MIN_INTERVAL_MILLIS)
        assertEquals(30L * 60_000L, AdventureEventEngine.MAX_INTERVAL_MILLIS)
    }

    @Test
    fun `settled event trophies never use common rarity`() {
        val game = newGame()
        advanceToEvent(game)
        game.inventory.clear()
        game.adventureJourney.pending = requireNotNull(game.adventureJourney.pending).copy(
            itemReward = AdventureEventItemReward.TROPHY,
            rewardKind = AdventureEventRewardKind.ITEM,
        )
        engine.settleOffline(game, game.actionEndsAt)
        val item = requireNotNull(game.inventory.firstOrNull())
        assertEquals("전리품", item.kind)
        assertTrue(item.rarity in setOf("고급", "희귀", "영웅", "전설", "신화"))
        assertFalse(item.rarity == "일반")
    }

    @Test
    fun `event schedules and evidence belong to the character rather than engine instance`() {
        val first = newGame(seed = 88L)
        val independent = newGame(seed = 98L)
        val untouched = restore(independent)
        engine.settleOffline(first, HOUR)
        assertEquals(untouched, independent)
        val independentEngine = SimpleGameEngine(enableAdventureEvents = true)
        engine.settleOffline(independent, HOUR)
        independentEngine.settleOffline(untouched, HOUR)
        assertEquals(untouched, independent)
    }

    @Test
    fun `offline charge depletion pauses event and schedule together without granting pending rewards`() {
        val game = newGame()
        advanceToEvent(game)
        val started = game.actionStartedAt
        val scheduled = game.adventureJourney.nextEventAt
        val availableAdventureMillis = AdventureEventEngine.ACTION_MILLIS / 2L
        val pausedMillis = 100_000L - availableAdventureMillis
        game.offlineAdventureMillis = availableAdventureMillis
        engine.settleOfflineWithOfflineAdventure(game, started + 100_000L)
        assertEquals(AdventurePhase.EVENT, game.adventurePhase)
        assertEquals(0L, game.adventureJourney.completedEvents)
        assertEquals(0L, game.offlineAdventureMillis)
        assertEquals(started + pausedMillis, game.actionStartedAt)
        assertEquals(game.actionStartedAt, game.adventureJourney.pending?.startedAt)
        assertEquals(scheduled + pausedMillis, game.adventureJourney.nextEventAt)
        assertEquals(started + pausedMillis + AdventureEventEngine.ACTION_MILLIS, game.actionEndsAt)
        game.offlineAdventureMillis = AdventureEventEngine.ACTION_MILLIS
        engine.settleOfflineWithOfflineAdventure(game, game.actionEndsAt)
        assertEquals(1L, game.adventureJourney.completedEvents)
    }

    @Test
    fun `event route reward appends every use and consumes the oldest shortcut per encounter`() {
        val game = newGame()
        advanceToEvent(game)
        game.inventory.clear()
        game.adventureJourney.routeRewardDelayQueueMillis = listOf(-900L)
        game.adventureJourney.pending = game.adventureJourney.pending?.copy(
            experienceReward = 0L,
            goldReward = 0L,
            itemReward = AdventureEventItemReward.NONE,
            routeDelayMillis = -1_500L,
            rewardKind = AdventureEventRewardKind.ROUTE,
            routeRewardUses = 3,
        )
        val delta = engine.settleOffline(game, game.actionEndsAt)
        assertEquals(0L, game.adventureJourney.nextEncounterDelayAdjustmentMillis)
        assertEquals(listOf(-900L, -1_500L, -1_500L, -1_500L), game.adventureJourney.routeRewardDelayQueueMillis)
        val recentEvent = delta.recentEvents.single { it.type == RecentAdventureEventType.ADVENTURE_EVENT }
        assertEquals(
            AdventureEventRewardKind.ROUTE,
            RecentAdventureEventMetadata.adventureRewardKind(recentEvent.contextName),
        )
        assertTrue(RecentAdventureEventMetadata.isRouteShortening(recentEvent.contextName))
        engine.settleOffline(game, game.actionEndsAt)
        assertEquals(AdventurePhase.COMBAT, game.adventurePhase)
        assertEquals(StatBonusRules.encounterRevealMillis(game) - 900L, game.actionEndsAt - game.actionStartedAt)
        assertEquals(0L, game.adventureJourney.nextEncounterDelayAdjustmentMillis)
        assertEquals(listOf(-1_500L, -1_500L, -1_500L), game.adventureJourney.routeRewardDelayQueueMillis)
    }

    @Test
    fun `legacy route reward without use metadata grants the new minimum of two encounters`() {
        val game = newGame()
        advanceToEvent(game)
        game.adventureJourney.pending = requireNotNull(game.adventureJourney.pending).copy(
            experienceReward = 0L,
            goldReward = 0L,
            itemReward = AdventureEventItemReward.NONE,
            routeDelayMillis = -1_500L,
            rewardKind = AdventureEventRewardKind.ROUTE,
            routeRewardUses = 0,
        )

        engine.settleOffline(game, game.actionEndsAt)

        assertEquals(listOf(-1_500L, -1_500L), game.adventureJourney.routeRewardDelayQueueMillis)
    }

    @Test
    fun `legacy settled one-use shortcut upgrades to two encounters`() {
        val game = newGame()
        advanceToEvent(game)
        game.adventureJourney.pending = requireNotNull(game.adventureJourney.pending).copy(
            experienceReward = 1L,
            goldReward = 0L,
            itemReward = AdventureEventItemReward.NONE,
            routeDelayMillis = 0L,
            rewardKind = AdventureEventRewardKind.EXPERIENCE,
            routeRewardUses = 0,
        )
        engine.settleOffline(game, game.actionEndsAt)
        game.adventureJourney.nextEncounterDelayAdjustmentMillis = -1_500L

        engine.settleOffline(game, game.actionEndsAt)

        assertEquals(AdventurePhase.COMBAT, game.adventurePhase)
        assertEquals(StatBonusRules.encounterRevealMillis(game) - 1_500L, game.actionEndsAt - game.actionStartedAt)
        assertEquals(0L, game.adventureJourney.nextEncounterDelayAdjustmentMillis)
        assertEquals(listOf(-1_500L), game.adventureJourney.routeRewardDelayQueueMillis)
    }

    @Test
    fun `legacy multi reward pending event settles and displays only one category`() {
        val game = newGame()
        advanceToEvent(game)
        game.inventory.clear()
        val experienceBefore = game.hero.experience
        val goldBefore = game.hero.gold
        val itemsBefore = game.totalItemsFound
        game.adventureJourney.pending = requireNotNull(game.adventureJourney.pending).copy(
            outcome = AdventureEventOutcome.SUCCESS,
            experienceReward = 123L,
            goldReward = 456L,
            itemReward = AdventureEventItemReward.TROPHY,
            routeDelayMillis = -1_500L,
            rewardKind = AdventureEventRewardKind.UNSPECIFIED,
            routeRewardUses = 0,
        )

        engine.settleOffline(game, game.actionEndsAt)

        val result = requireNotNull(game.adventureJourney.lastResult)
        assertEquals(AdventureEventRewardKind.ITEM, result.run.rewardKind)
        assertEquals(0L, result.experienceAwarded)
        assertEquals(0L, result.goldAwarded)
        assertEquals(experienceBefore, game.hero.experience)
        assertEquals(goldBefore, game.hero.gold)
        assertEquals(itemsBefore + 1L, game.totalItemsFound)
        assertEquals(1, result.actualItemCount)
        assertTrue(game.adventureJourney.routeRewardDelayQueueMillis.isEmpty())
    }

    @Test
    fun `failure delay is consumed before and does not delete queued route rewards`() {
        val game = newGame()
        advanceToEvent(game)
        game.adventureJourney.routeRewardDelayQueueMillis = listOf(-1_500L, -900L)
        game.adventureJourney.pending = requireNotNull(game.adventureJourney.pending).copy(
            outcome = AdventureEventOutcome.FAILURE,
            experienceReward = 0L,
            goldReward = 0L,
            itemReward = AdventureEventItemReward.NONE,
            routeDelayMillis = 2_000L,
            routeRewardUses = 0,
        )

        engine.settleOffline(game, game.actionEndsAt)
        assertEquals(2_000L, game.adventureJourney.nextEncounterDelayAdjustmentMillis)
        assertEquals(listOf(-1_500L, -900L), game.adventureJourney.routeRewardDelayQueueMillis)

        engine.settleOffline(game, game.actionEndsAt)
        assertEquals(AdventurePhase.COMBAT, game.adventurePhase)
        assertEquals(StatBonusRules.encounterRevealMillis(game) + 2_000L, game.actionEndsAt - game.actionStartedAt)
        assertEquals(0L, game.adventureJourney.nextEncounterDelayAdjustmentMillis)
        assertEquals(listOf(-1_500L, -900L), game.adventureJourney.routeRewardDelayQueueMillis)
    }

    @Test
    fun `failure preserves both upgraded uses of a legacy scalar shortcut`() {
        val game = newGame()
        advanceToEvent(game)
        game.adventureJourney.nextEncounterDelayAdjustmentMillis = -1_500L
        game.adventureJourney.pending = requireNotNull(game.adventureJourney.pending).copy(
            outcome = AdventureEventOutcome.FAILURE,
            experienceReward = 0L,
            goldReward = 0L,
            itemReward = AdventureEventItemReward.NONE,
            routeDelayMillis = 2_000L,
            routeRewardUses = 0,
        )

        engine.settleOffline(game, game.actionEndsAt)

        assertEquals(2_000L, game.adventureJourney.nextEncounterDelayAdjustmentMillis)
        assertEquals(listOf(-1_500L, -1_500L), game.adventureJourney.routeRewardDelayQueueMillis)
    }

    @Test
    fun `pending event completes learning but cannot cross the final boss boundary`() {
        val game = newGame()
        advanceToEvent(game)
        game.inventory.clear()
        val act = game.adventureTale.activeAct()
        act.progress = act.target - 1L
        val beforeActs = game.totalActs
        val beforeTales = game.totalTales
        val beforeKills = game.totalKills
        engine.settleOffline(game, game.actionEndsAt)
        assertEquals(act.target - 1L, act.progress)
        assertEquals(beforeActs, game.totalActs)
        assertEquals(beforeTales, game.totalTales)
        assertEquals(beforeKills, game.totalKills)
        assertEquals(0L, game.adventureJourney.lastResult?.progressAdded)
        engine.settleOffline(game, game.actionEndsAt)
        assertEquals(AdventurePhase.COMBAT, game.adventurePhase)
        assertEquals(MonsterGrade.BOSS, game.monster.grade)
    }

    @Test
    fun `full bag records no item rather than claiming an unreceived reward`() {
        val game = newGame()
        advanceToEvent(game)
        game.hero.experience = 0L
        game.adventureJourney.pending = game.adventureJourney.pending?.copy(
            itemReward = AdventureEventItemReward.TROPHY,
            rewardKind = AdventureEventRewardKind.ITEM,
        )
        game.inventory = MutableList(game.inventoryCapacity().toInt()) { index ->
            InventoryItem(index + 1L, "검증 전리품", "일반", "전리품", game.hero.level)
        }
        val itemsBefore = game.totalItemsFound
        engine.settleOffline(game, game.actionEndsAt)
        assertEquals(itemsBefore, game.totalItemsFound)
        assertEquals("", game.adventureJourney.lastResult?.itemName)
        assertEquals(0L, game.adventureJourney.lastResult?.itemFoundAtLevel)
        engine.settleOffline(game, game.actionEndsAt)
        assertEquals(AdventurePhase.RETURNING, game.adventurePhase)
    }

    @Test
    fun `all one hundred situations appear naturally and direct equipment remains rare`() {
        val game = newGame()
        val seen = mutableSetOf<Pair<String, AdventureEventOutcome>>()
        var equipmentRewards = 0
        repeat(30_000) { index ->
            val run = AdventureEventEngine.begin(
                game,
                index * 300_000L,
                context = com.nullplaying.model.AdventureEventContext.entries[
                    index % com.nullplaying.model.AdventureEventContext.entries.size
                ],
            )
            seen += run.eventId to run.outcome
            assertTrue(run.successBasisPoints in 1_500..8_500)
            assertTrue(run.successBasisPoints + run.partialBasisPoints <= 9_500)
            assertTrue(run.rewardKind != AdventureEventRewardKind.UNSPECIFIED)
            val actualKinds = positiveRewardKinds(run)
            if (run.battleGrade != null || run.outcome == AdventureEventOutcome.FAILURE) {
                assertTrue("${run.eventId} rewards=$actualKinds", actualKinds.isEmpty())
            } else {
                assertEquals(run.eventId, setOf(run.rewardKind), actualKinds)
            }
            if (run.itemReward == AdventureEventItemReward.EQUIPMENT) equipmentRewards++
            game.adventureJourney.pending = null
        }
        assertEquals(100, seen.map { it.first }.distinct().size)
        assertTrue(seen.size >= 295)
        assertTrue(equipmentRewards > 0)
        assertTrue("Equipment must remain rare: $equipmentRewards / 30000", equipmentRewards < 1_500)
    }

    @Test
    fun `labyrinth incident preserves ordinary depth experience and time budgets`() {
        val game = newGame()
        game.hero.level = 60L
        game.adventureTale = AdventureTaleCatalog.instantiate(
            definition = LabyrinthTaleCatalog.definitionForDepth(100L), sequence = 142L,
            heroName = game.hero.name, heroLevel = game.hero.level,
            variant = AdventureTaleCatalog.variantAt(0), labyrinthDepth = 100L)
        game.adventurePhase = AdventurePhase.LOOTING
        game.actionStartedAt = 0L
        game.actionEndsAt = 3_000L
        game.adventureJourney.nextEventAt = 0L
        game.adventureJourney.nextEventContext = com.nullplaying.model.AdventureEventContext.FIELD_EXPLORATION
        engine.settleOffline(game, 3_000L)
        assertEquals(AdventurePhase.EVENT, game.adventurePhase)
        val run = requireNotNull(game.adventureJourney.pending)
        val encounterLevel = game.hero.level + LabyrinthProgression.monsterLevelBonus(100L)
        assertEquals(encounterLevel, run.encounterLevel)
        assertEquals(100L, run.labyrinthDepth)
        assertEquals(LabyrinthProgression.scaleCombatExperience(16L + encounterLevel * 4L, 100L), run.baseExperienceBudget)
        val attacks = engine.attackCountForCombatPower(game,
            LabyrinthProgression.targetAttacks(100L, MonsterGrade.NORMAL.minAttacks, false))
        val expectedOrdinaryTotal = StatBonusRules.encounterRevealMillis(game) +
            (attacks - 1L) * SimpleGameEngine.ATTACK_PRESENTATION_MILLIS +
            SimpleGameEngine.VICTORY_PRESENTATION_MILLIS + SimpleGameEngine.LOOT_RESULT_MILLIS
        if (run.battleGrade == null) {
            assertEquals(expectedOrdinaryTotal, run.durationMillis + AdventureEventEngine.RESULT_MILLIS)
        } else {
            assertEquals(AdventureEventEngine.ACTION_MILLIS, run.durationMillis)
        }
        assertEquals(run.durationMillis, game.actionEndsAt - game.actionStartedAt)
    }

    private fun newGame(owner: SimpleGameEngine = engine, seed: Long = 88L): SimpleGameState = owner.newGame(
        name = "사건 검증", heroClass = HeroClass.WARRIOR,
        rolledStats = owner.rollStats(77L).stats, seed = seed, now = 0L)

    private fun advanceToEvent(game: SimpleGameState) {
        game.adventureJourney.nextEventContext =
            com.nullplaying.model.AdventureEventContext.FIELD_EXPLORATION
        repeat(2_000) {
            if (game.adventurePhase == AdventurePhase.EVENT) {
                game.adventureJourney.pending = requireNotNull(game.adventureJourney.pending).copy(
                    battleGrade = null,
                    battleRewardKind = com.nullplaying.model.AdventureEventBattleRewardKind.NONE,
                )
                return
            }
            engine.settleOffline(game, game.actionEndsAt)
        }
        error("No event was reached on the real automatic timeline")
    }

    private fun settleUntil(game: SimpleGameState, predicate: (SimpleGameState) -> Boolean) {
        repeat(2_000) {
            if (predicate(game)) return
            engine.settleOffline(game, game.actionEndsAt)
        }
        error("Requested adventure phase was not reached")
    }

    private fun restore(game: SimpleGameState): SimpleGameState = Json.decodeFromString(Json.encodeToString(game))

    private fun positiveRewardKinds(run: AdventureEventRun): Set<AdventureEventRewardKind> = buildSet {
        if (run.experienceReward > 0L) add(AdventureEventRewardKind.EXPERIENCE)
        if (run.goldReward > 0L) add(AdventureEventRewardKind.GOLD)
        if (run.itemReward != AdventureEventItemReward.NONE) add(AdventureEventRewardKind.ITEM)
        if (run.routeDelayMillis < 0L) add(AdventureEventRewardKind.ROUTE)
    }

    companion object {
        private const val HOUR = 3_600_000L
        private val REWARD_KINDS = listOf(
            AdventureEventRewardKind.EXPERIENCE,
            AdventureEventRewardKind.GOLD,
            AdventureEventRewardKind.ITEM,
            AdventureEventRewardKind.ROUTE,
        )
    }
}
