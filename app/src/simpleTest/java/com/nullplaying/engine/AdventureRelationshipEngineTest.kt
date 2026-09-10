package com.nullplaying.engine

import com.nullplaying.model.AdventureEncounterCandidate
import com.nullplaying.model.AdventureEncounterRoster
import com.nullplaying.model.AdventureEventOutcome
import com.nullplaying.model.AdventureEventRewardKind
import com.nullplaying.model.AdventurePhase
import com.nullplaying.model.AdventureRelationshipBattleKind
import com.nullplaying.model.AdventureRelationshipContact
import com.nullplaying.model.AdventureRelationshipEquipmentSnapshot
import com.nullplaying.model.AdventureRelationshipResult
import com.nullplaying.model.AdventureRelationshipTier
import com.nullplaying.model.BattleOutcome
import com.nullplaying.model.EquipmentSlot
import com.nullplaying.model.HeroClass
import com.nullplaying.model.HeroStats
import com.nullplaying.model.InventoryItem
import com.nullplaying.model.MonsterGrade
import com.nullplaying.model.RecentAdventureEventType
import com.nullplaying.model.SimpleGameState
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdventureRelationshipEngineTest {
    private val engine = SimpleGameEngine(enableAdventureEvents = true, enableAdventureRelationships = true)

    @Test
    fun `relationship feature defaults off and preserves stage one timeline`() {
        val defaultEngine = SimpleGameEngine(enableAdventureEvents = true)
        val disabledEngine = SimpleGameEngine(enableAdventureEvents = true, enableAdventureRelationships = false)
        val baseline = newGame(defaultEngine)
        val disabled = newGame(disabledEngine)
        defaultEngine.settleOffline(baseline, HOUR)
        disabledEngine.settleOffline(disabled, HOUR)
        assertEquals(baseline, disabled)
        assertFalse(baseline.adventureRelationships.initialized)
    }

    @Test
    fun `fifty scenes include eight initial and two reunion battle situations with complete localizations`() {
        assertEquals(50, AdventureRelationshipEngine.all.size)
        assertEquals(50, AdventureRelationshipEngine.all.map { it.id }.distinct().size)
        val battleDefinitions = AdventureRelationshipEngine.all.filter { it.battleRule != null }
        assertEquals(10, battleDefinitions.size)
        assertEquals(8, battleDefinitions.count {
            it.reunionRule != AdventureRelationshipReunionRule.REUNION_ONLY
        })
        assertEquals(2, battleDefinitions.count {
            it.reunionRule == AdventureRelationshipReunionRule.REUNION_ONLY
        })
        AdventureRelationshipEngine.all.forEach { definition ->
            assertEquals(3, definition.approaches.map { it.id }.distinct().size)
            assertEquals(FIXED_RELATIONSHIP_ECONOMIC_REWARD_WEIGHTS, definition.rewardWeights)
            assertTrue(definition.allowedTiers.isNotEmpty())
            (listOf(definition.title, definition.scene, definition.success, definition.partial, definition.failure) +
                definition.approaches.map { it.title }).forEach {
                assertTrue(it.ko.isNotBlank() && it.en.isNotBlank() && it.ja.isNotBlank())
                assertFalse(it.ko.contains("%"))
            }
        }
    }

    @Test
    fun `same local reward seed stays economic-identical across every scene tier and incident outcome`() {
        val seed = (0L..10_000L).first { candidateSeed ->
            AdventureRelationshipEngine.fixedEconomicReward(10L, 56L, candidateSeed, true).kind ==
                AdventureEventRewardKind.ITEM
        }
        val expected = AdventureRelationshipEngine.fixedEconomicReward(10L, 56L, seed, true)

        AdventureRelationshipEngine.all.forEach { definition ->
            AdventureRelationshipTier.entries.forEach { tier ->
                AdventureEventOutcome.entries.forEach { outcome ->
                    assertEquals(
                        "${definition.id}/$tier/$outcome",
                        expected,
                        AdventureRelationshipEngine.fixedEconomicReward(10L, 56L, seed, true),
                    )
                }
            }
        }
        val fullBag = AdventureRelationshipEngine.fixedEconomicReward(10L, 56L, seed, false)
        assertEquals(AdventureEventRewardKind.EXPERIENCE, fullBag.kind)
        assertEquals(1, listOf(fullBag.experience > 0L, fullBag.gold > 0L,
            fullBag.item != com.nullplaying.model.AdventureEventItemReward.NONE).count { it })
    }

    @Test
    fun `own relevant stats affect success without inferring remote stats`() {
        val stats = HeroStats(10L, 10L, 10L, 10L, 10L, 10L, 50L, 30L)
        val approach = AdventureRelationshipEngine.definition("directions").approaches.first()
        val baseline = AdventureRelationshipEngine.successBasisPoints(stats, 10L, approach)
        assertTrue(AdventureRelationshipEngine.successBasisPoints(stats.copy(intelligence = 20L), 10L, approach) > baseline)
        assertTrue(AdventureRelationshipEngine.successBasisPoints(stats.copy(wisdom = 20L), 10L, approach) > baseline)
        assertEquals(baseline, AdventureRelationshipEngine.successBasisPoints(stats.copy(strength = 100L), 10L, approach))
    }

    @Test
    fun `candidate admission enforces receipt expiry own id exact level and deterministic ordering`() {
        val game = newGame()
        game.hero.level = 10L
        game.rankingCharacterId = "self"
        game.adventureRelationships.roster = AdventureEncounterRoster("snapshot", 100L, 200L,
            listOf(candidate("z", 11L), candidate("a", 9L), candidate("self", 10L),
                candidate("too-high", 12L), candidate("too-low", 8L), candidate("a", 9L)))
        assertTrue(AdventureRelationshipEngine.eligibleCandidates(game, 99L).isEmpty())
        assertEquals(listOf("a", "z"), AdventureRelationshipEngine.eligibleCandidates(game, 100L).map { it.characterId })
        assertEquals(listOf("a", "z"), AdventureRelationshipEngine.eligibleCandidates(game, 199L).map { it.characterId })
        assertTrue(AdventureRelationshipEngine.eligibleCandidates(game, 200L).isEmpty())
        val seed = game.adventureRelationships.rngState
        game.adventureRelationships.nextEncounterAt = 0L
        assertNull(AdventureRelationshipEngine.tryBegin(game, 200L))
        assertEquals(seed, game.adventureRelationships.rngState)
        game.hero.level = 13L
        assertEquals(listOf("too-high"), AdventureRelationshipEngine.eligibleCandidates(game, 150L).map { it.characterId })
    }

    @Test
    fun `meeting and five second result grant exactly one reward progress and one memory without kills`() {
        val game = meetingReady()
        engine.settleOffline(game, 3_000L)
        val run = requireNotNull(game.adventureRelationships.pending)
        assertEquals(AdventurePhase.RELATIONSHIP, game.adventurePhase)
        assertEquals(run.durationMillis, game.actionEndsAt - game.actionStartedAt)
        assertEquals(1, selectedRewardKinds(run))
        val beforeKills = game.totalKills
        val beforeItems = game.totalItemsFound
        val beforeGold = game.hero.gold
        val beforeProgress = game.adventureTale.activeAct().progress
        val completion = game.actionEndsAt
        engine.settleOffline(game, completion - 1L)
        assertEquals(0L, game.adventureRelationships.totalEncounters)
        val delta = engine.settleOffline(game, completion)
        assertEquals(AdventurePhase.RELATIONSHIP_RESULT, game.adventurePhase)
        assertEquals(5_000L, game.actionEndsAt - game.actionStartedAt)
        assertEquals(beforeKills, game.totalKills)
        assertEquals(beforeProgress + 1L, game.adventureTale.activeAct().progress)
        val result = requireNotNull(game.adventureRelationships.lastResult)
        assertEquals(run, result.run)
        assertEquals(completion, result.occurredAt)
        assertEquals(run.experienceReward, result.experienceAwarded)
        assertEquals(run.goldReward, result.goldAwarded)
        assertEquals(run.rewardKind, result.rewardKind)
        assertEquals(1, actualRewardKinds(result))
        assertEquals(beforeGold + result.goldAwarded, game.hero.gold)
        assertEquals(beforeItems + if (result.itemName.isBlank()) 0L else 1L, game.totalItemsFound)
        assertEquals(listOf(result.toMemory()), game.adventureRelationships.contacts.single().memories)
        assertEquals(1L, game.adventureRelationships.contacts.single().meetings)
        assertEquals(result.scoreAfter, game.adventureRelationships.contacts.single().score)
        assertNull(game.adventureRelationships.pending)
        val record = delta.recentEvents.single { it.type == RecentAdventureEventType.RELATIONSHIP_ENCOUNTER }
        assertEquals(run.candidate.characterId, record.subjectId)
        assertEquals(run.candidate.displayName, record.subjectName)
        assertEquals(run.scoreBefore.toLong(), record.previousValue)
        assertEquals(result.scoreAfter.toLong(), record.currentValue)
        val serialized = Json.encodeToString(game)
        engine.settleOffline(game, completion)
        engine.settleOffline(game, 1L)
        assertEquals(serialized, Json.encodeToString(game))
    }

    @Test
    fun `saved action and result replay once even when roster changes after selection`() {
        val continuous = meetingReady()
        engine.settleOffline(continuous, 3_000L)
        val run = requireNotNull(continuous.adventureRelationships.pending)
        val midAction = run.startedAt + (run.durationMillis / 2L).coerceAtLeast(1L)
        engine.settleOffline(continuous, midAction)
        continuous.adventureRelationships.roster = AdventureEncounterRoster("later", midAction, 24L * HOUR,
            listOf(candidate("new-opponent")))
        var restored = restore(continuous)
        val completion = run.startedAt + run.durationMillis
        engine.settleOffline(continuous, completion)
        engine.settleOffline(restored, completion)
        assertEquals(continuous, restored)
        assertEquals("opponent", restored.adventureRelationships.lastResult?.run?.candidate?.characterId)
        restored = restore(restored)
        val resultEnd = completion + AdventureRelationshipEngine.RESULT_MILLIS
        engine.settleOffline(continuous, resultEnd)
        engine.settleOffline(restored, resultEnd)
        assertEquals(continuous, restored)
        assertEquals(1L, restored.adventureRelationships.totalEncounters)
    }

    @Test
    fun `split timeline and serialized chunks preserve relationship event and combat decisions`() {
        val continuous = newGame()
        continuous.adventureRelationships.roster = AdventureEncounterRoster("stable", 0L, 24L * HOUR,
            (1L..30L).map { candidate("level-$it", it) })
        var split = restore(continuous)
        engine.settleOffline(continuous, 9L * HOUR)
        repeat(108) {
            engine.settleOffline(split, (it + 1L) * 5L * 60_000L)
            if (it % 7 == 0) split = restore(split)
        }
        assertEquals(continuous, split)
        assertTrue(continuous.adventureRelationships.totalEncounters >= 1L)
    }

    @Test
    fun `uncharged pause freezes active cooldown but does not extend roster ttl`() {
        val game = meetingReady()
        engine.settleOffline(game, 3_000L)
        engine.settleOffline(game, game.actionEndsAt)
        val contact = game.adventureRelationships.contacts.single()
        val beforeActive = AdventureRelationshipEngine.activeMillisAt(game.adventureRelationships, game.lastSettledAt)
        val beforeNext = game.adventureRelationships.nextEncounterAt
        val roster = game.adventureRelationships.roster
        game.offlineAdventureMillis = 0L
        engine.settleOfflineWithOfflineAdventure(game, game.lastSettledAt + 72L * HOUR)
        assertEquals(beforeActive, AdventureRelationshipEngine.activeMillisAt(game.adventureRelationships, game.lastSettledAt))
        assertEquals(beforeNext + 72L * HOUR, game.adventureRelationships.nextEncounterAt)
        assertEquals(roster, game.adventureRelationships.roster)
        assertEquals(contact, game.adventureRelationships.contacts.single())
        game.adventureRelationships.roster = AdventureEncounterRoster("fresh", game.lastSettledAt, game.lastSettledAt + 24L * HOUR,
            listOf(candidate("opponent")))
        val remaining = contact.nextEligibleActiveMillis - beforeActive
        assertEquals(AdventureRelationshipEngine.REUNION_COOLDOWN_MILLIS, remaining)
        assertTrue(AdventureRelationshipEngine.eligibleCandidates(game, game.lastSettledAt).isEmpty())
        assertTrue(AdventureRelationshipEngine.eligibleCandidates(game, game.lastSettledAt + remaining - 1L).isEmpty())
        assertEquals(listOf("opponent"), AdventureRelationshipEngine.eligibleCandidates(game, game.lastSettledAt + remaining).map { it.characterId })
        assertTrue(AdventureRelationshipEngine.eligibleCandidates(game, contact.lastMetAt - 1L).isEmpty())
    }

    @Test
    fun `pause during meeting shifts completion without changing selected outcome`() {
        val game = meetingReady()
        engine.settleOffline(game, 3_000L)
        val run = requireNotNull(game.adventureRelationships.pending)
        val midAction = run.startedAt + (run.durationMillis / 2L).coerceAtLeast(1L)
        engine.settleOffline(game, midAction)
        val expectedCompletion = game.actionEndsAt + 72L * HOUR
        game.offlineAdventureMillis = 0L
        engine.settleOfflineWithOfflineAdventure(game, midAction + 72L * HOUR)
        assertEquals(run.copy(startedAt = run.startedAt + 72L * HOUR), game.adventureRelationships.pending)
        engine.settleOffline(game, expectedCompletion)
        assertEquals(1L, game.adventureRelationships.totalEncounters)
        assertEquals(expectedCompletion, game.adventureRelationships.lastResult?.occurredAt)
        assertEquals(
            AdventureRelationshipEngine.activeMillisAt(game.adventureRelationships, expectedCompletion),
            game.adventureRelationships.contacts.single().lastMetActiveMillis,
        )
    }

    @Test
    fun `one hundred contacts retain their memories and prevent new stranger admission`() {
        val game = newGame()
        game.adventureRelationships.contacts = (1..100).map {
            AdventureRelationshipContact("known-$it", latestSnapshot = candidate("known-$it"))
        }
        val contacts = game.adventureRelationships.contacts
        game.adventureRelationships.roster = AdventureEncounterRoster("full", 0L, 24L * HOUR,
            listOf(candidate("stranger"), candidate("known-1")))
        assertEquals(listOf("known-1"), AdventureRelationshipEngine.eligibleCandidates(game, HOUR).map { it.characterId })
        assertEquals(contacts, game.adventureRelationships.contacts)
    }

    @Test
    fun `bounded raw memories preserve first meeting lifetime count and current relationship`() {
        val game = meetingReady()
        engine.settleOffline(game, 3_000L)
        engine.settleOffline(game, game.actionEndsAt)
        val first = requireNotNull(game.adventureRelationships.lastResult)
        repeat(32) { index ->
            val sequence = index + 2L
            val result = first.copy(run = first.run.copy(sequence = sequence),
                occurredAt = first.occurredAt + sequence * 6L * HOUR, scoreAfter = 42)
            AdventureRelationshipEngine.complete(game.adventureRelationships, result)
        }
        val contact = game.adventureRelationships.contacts.single()
        assertEquals(16, contact.memories.size)
        assertEquals(32, game.adventureRelationships.recentResults.size)
        assertEquals(18L, contact.memories.first().sequence)
        assertEquals(33L, contact.memories.last().sequence)
        assertEquals(33L, contact.meetings)
        assertEquals(33L, game.adventureRelationships.totalEncounters)
        assertEquals(first.occurredAt, contact.firstMetAt)
        assertEquals(42, contact.score)
        assertEquals(first.run.candidate, contact.latestSnapshot)
    }

    @Test
    fun `preview full memories migrate to compact records and never write full results again`() {
        val game = meetingReady()
        engine.settleOffline(game, 3_000L)
        engine.settleOffline(game, game.actionEndsAt)
        val result = requireNotNull(game.adventureRelationships.lastResult)
        val stateJson = Json.parseToJsonElement(Json.encodeToString(game)).jsonObject
        val relationshipsJson = stateJson.getValue("adventureRelationships").jsonObject
        val contactJson = relationshipsJson.getValue("contacts").jsonArray.single().jsonObject
        val oldContact = JsonObject(contactJson + ("memories" to JsonArray(listOf(
            Json.parseToJsonElement(Json.encodeToString(result))))))
        val oldRelationships = JsonObject(relationshipsJson + ("contacts" to JsonArray(listOf(oldContact))))
        val oldState = JsonObject(stateJson + ("adventureRelationships" to oldRelationships))
        val restored = Json.decodeFromString<SimpleGameState>(oldState.toString())
        assertEquals(game, restored)
        assertEquals(result.toMemory(), restored.adventureRelationships.contacts.single().memories.single())
        val rewritten = Json.parseToJsonElement(Json.encodeToString(restored)).jsonObject
            .getValue("adventureRelationships").jsonObject
        val memory = rewritten.getValue("contacts").jsonArray.single().jsonObject
            .getValue("memories").jsonArray.single().jsonObject
        assertFalse(memory.containsKey("run"))
        assertFalse(memory.containsKey("candidate"))
        assertFalse(memory.containsKey("roll"))
        assertEquals(8, memory.size)
        assertTrue(rewritten.getValue("lastResult").jsonObject.containsKey("run"))
        val resultEnd = result.occurredAt + AdventureRelationshipEngine.RESULT_MILLIS
        engine.settleOffline(restored, resultEnd)
        engine.settleOffline(game, resultEnd)
        assertEquals(game, restored)
    }

    @Test
    fun `all six tier boundaries are inclusive and total`() {
        listOf(100 to AdventureRelationshipTier.VERY_CLOSE, 70 to AdventureRelationshipTier.VERY_CLOSE,
            69 to AdventureRelationshipTier.CLOSE, 30 to AdventureRelationshipTier.CLOSE,
            29 to AdventureRelationshipTier.KNOWN, -19 to AdventureRelationshipTier.KNOWN,
            -20 to AdventureRelationshipTier.BAD, -49 to AdventureRelationshipTier.BAD,
            -50 to AdventureRelationshipTier.VERY_BAD, -79 to AdventureRelationshipTier.VERY_BAD,
            -80 to AdventureRelationshipTier.HOSTILE, -100 to AdventureRelationshipTier.HOSTILE).forEach { (score, tier) ->
            assertEquals(tier, AdventureRelationshipTier.fromScore(score))
        }
    }

    @Test
    fun `outcome affinity and single rewards do not consume combat rng`() {
        val game = newGame()
        game.adventureRelationships.roster = AdventureEncounterRoster("broad", 0L, Long.MAX_VALUE,
            listOf(candidate("opponent")))
        val combatRng = game.rngState
        val eventRng = game.adventureJourney.rngState
        val seen = mutableSetOf<Pair<String, AdventureEventOutcome>>()
        var successfulDislike = 0
        var failedHelp = 0
        repeat(3_000) {
            game.adventureRelationships.nextEncounterAt = 0L
            val run = requireNotNull(AdventureRelationshipEngine.tryBegin(game, it * 300_000L))
            seen += run.sceneId to run.outcome
            if (run.outcome == AdventureEventOutcome.SUCCESS && run.scoreDelta < 0) successfulDislike++
            if (run.outcome == AdventureEventOutcome.FAILURE && run.scoreDelta > 0) failedHelp++
            assertEquals(1, selectedRewardKinds(run))
            if (run.rewardKind == AdventureEventRewardKind.EXPERIENCE) {
                assertTrue(run.experienceReward in 1L..run.baseExperienceBudget)
            }
            game.adventureRelationships.pending = null
        }
        val eligibleNonBattleScenes = AdventureRelationshipEngine.all.count { it.battleRule == null }
        assertEquals(eligibleNonBattleScenes * AdventureEventOutcome.entries.size, seen.size)
        assertTrue(successfulDislike > 0)
        assertTrue(failedHelp > 0)
        assertEquals(combatRng, game.rngState)
        assertEquals(eventRng, game.adventureJourney.rngState)
    }

    @Test
    fun `ordinary meeting cannot bypass final boss or elite and cannot overflow a full bag`() {
        listOf(MonsterGrade.BOSS, MonsterGrade.ELITE).forEach { grade ->
            val game = meetingReady()
            val act = game.adventureTale.activeAct()
            act.progress = (0L until act.target).first { QuestMonsterCatalog.encounterGrade(it, act.target) == grade }
            engine.settleOffline(game, 3_000L)
            assertEquals(AdventurePhase.COMBAT, game.adventurePhase)
            assertEquals(grade, game.monster.grade)
            assertNull(game.adventureRelationships.pending)
        }
        val full = meetingReady()
        full.inventory = MutableList(full.inventoryCapacity().toInt()) { InventoryItem(it + 1L, "짐", "일반", "전리품", full.hero.level) }
        engine.settleOffline(full, 3_000L)
        assertEquals(AdventurePhase.RETURNING, full.adventurePhase)
        assertNull(full.adventureRelationships.pending)
        val game = meetingReady()
        engine.settleOffline(game, 3_000L)
        val completion = game.actionEndsAt
        val act = game.adventureTale.activeAct()
        act.progress = act.target - 1L
        val acts = game.totalActs
        engine.settleOffline(game, completion)
        assertEquals(0L, game.adventureRelationships.lastResult?.progressAdded)
        assertEquals(acts, game.totalActs)
        engine.settleOffline(game, completion + AdventureRelationshipEngine.RESULT_MILLIS)
        assertEquals(MonsterGrade.BOSS, game.monster.grade)
    }

    @Test
    fun `labyrinth xp uses own ordinary encounter budget and never remote player level`() {
        val game = meetingReady()
        game.hero.level = 60L
        game.adventureTale = AdventureTaleCatalog.instantiate(
            definition = LabyrinthTaleCatalog.definitionForDepth(100L), sequence = 142L,
            heroName = game.hero.name, heroLevel = game.hero.level,
            variant = AdventureTaleCatalog.variantAt(0), labyrinthDepth = 100L)
        game.adventureRelationships.roster = AdventureEncounterRoster("deep", 0L, HOUR,
            listOf(candidate("opponent", 59L)))
        engine.settleOffline(game, 3_000L)
        val run = requireNotNull(game.adventureRelationships.pending)
        val encounterLevel = 60L + LabyrinthProgression.monsterLevelBonus(100L)
        assertEquals(59L, run.candidate.level)
        assertEquals(encounterLevel, run.encounterLevel)
        assertEquals(LabyrinthProgression.scaleCombatExperience(16L + encounterLevel * 4L, 100L), run.baseExperienceBudget)
        assertEquals(1, selectedRewardKinds(run))
        assertTrue(run.experienceReward <= run.baseExperienceBudget)
        assertEquals(AdventureRelationshipEngine.ACTION_MILLIS, run.durationMillis)
    }

    @Test
    fun `battle selection freezes both projections and compact completion drops duplicate fighter snapshots`() {
        val game = newGame()
        game.rankingCharacterId = "local-projection"
        game.adventureRelationships.roster = AdventureEncounterRoster(
            "battle-roster",
            0L,
            Long.MAX_VALUE,
            listOf(battleCandidate("remote-projection", game.hero.level)),
        )
        val selected = generateSequence(0L) { it + 1L }.take(200).mapNotNull { attempt ->
            game.adventureRelationships.nextEncounterAt = 0L
            AdventureRelationshipEngine.tryBegin(game, attempt * 1_000L).also {
                if (it?.battleKind == AdventureRelationshipBattleKind.NONE) game.adventureRelationships.pending = null
            }
        }.first { it.battleKind != AdventureRelationshipBattleKind.NONE }

        assertNotNull(selected.localBattleSnapshot)
        assertNotNull(selected.opponentBattleSnapshot)
        val first = requireNotNull(AdventureRelationshipBattleEngine.simulate(selected))
        game.hero.stats.strength += 10_000L
        val replay = requireNotNull(AdventureRelationshipBattleEngine.simulate(selected))
        assertEquals(first.simulation, replay.simulation)

        val resolved = AdventureRelationshipEngine.resolveBattle(selected, first.outcome)
        assertTrue(resolved.battleOutcome in BattleOutcome.entries)
        assertEquals(1, selectedRewardKinds(resolved))
        BattleOutcome.entries.forEach { outcome ->
            val outcomeVariant = AdventureRelationshipEngine.resolveBattle(selected, outcome)
            assertEquals(selected.rewardKind, outcomeVariant.rewardKind)
            assertEquals(selected.experienceReward, outcomeVariant.experienceReward)
            assertEquals(selected.goldReward, outcomeVariant.goldReward)
            assertEquals(selected.itemReward, outcomeVariant.itemReward)
        }
        val actualItem = if (resolved.rewardKind == AdventureEventRewardKind.ITEM) "고정 전투 장비" else ""
        AdventureRelationshipEngine.complete(
            game.adventureRelationships,
            AdventureRelationshipResult(
                run = resolved,
                occurredAt = selected.startedAt + selected.durationMillis,
                experienceAwarded = resolved.experienceReward,
                goldAwarded = resolved.goldReward,
                itemName = actualItem,
                itemRarity = if (actualItem.isBlank()) "" else "고급",
                itemEquipped = false,
                rewardKind = resolved.rewardKind,
                scoreAfter = (resolved.scoreBefore + AdventureRelationshipEngine.effectiveScoreDelta(resolved)).coerceIn(-100, 100),
                progressAdded = 1L,
            ),
        )
        val stored = requireNotNull(game.adventureRelationships.lastResult)
        assertEquals(first.outcome, stored.run.battleOutcome)
        assertNull(stored.run.localBattleSnapshot)
        assertNull(stored.run.opponentBattleSnapshot)
        assertTrue(Json.encodeToString(stored.run).length < Json.encodeToString(resolved).length)
    }

    @Test
    fun `legacy saves default relationship state and character slots remain independent`() {
        val game = newGame()
        val withoutRelationships = JsonObject(Json.parseToJsonElement(Json.encodeToString(game)).jsonObject
            .filterKeys { it != "adventureRelationships" })
        val legacy = Json.decodeFromString<SimpleGameState>(withoutRelationships.toString())
        assertFalse(legacy.adventureRelationships.initialized)
        engine.settleOffline(legacy, 1L)
        assertTrue(legacy.adventureRelationships.initialized)
        assertTrue(legacy.adventureRelationships.nextEncounterAt in AdventureRelationshipEngine.MIN_INTERVAL_MILLIS..
            AdventureRelationshipEngine.MAX_INTERVAL_MILLIS)
        assertTrue(legacy.adventureRelationships.contacts.isEmpty())
        val other = newGame()
        val active = meetingReady()
        engine.settleOffline(active, 3_000L)
        engine.settleOffline(active, active.actionEndsAt)
        assertTrue(other.adventureRelationships.contacts.isEmpty())
        assertEquals(0L, other.adventureRelationships.totalEncounters)
        assertNotNull(active.adventureRelationships.lastResult)
    }

    private fun newGame(owner: SimpleGameEngine = engine): SimpleGameState {
        val game = owner.newGame(
            name = "인연 검증",
            heroClass = HeroClass.WARRIOR,
            rolledStats = owner.rollStats(77L).stats,
            seed = 88L,
            now = 0L,
        )
        var growthSeed = 77L
        repeat(9) { growthSeed = owner.applyClassGuidedGrowth(game.hero.stats, game.hero.heroClass, growthSeed) }
        game.hero.level = AdventureRelationshipEngine.MIN_HERO_LEVEL
        game.classGuidedLevelGrowths = 9L
        return game
    }

    private fun meetingReady(): SimpleGameState = newGame().also {
        it.adventurePhase = AdventurePhase.LOOTING
        it.actionStartedAt = 0L
        it.actionEndsAt = 3_000L
        it.adventureRelationships.nextEncounterAt = 0L
        it.adventureRelationships.roster = AdventureEncounterRoster("initial", 0L, 24L * HOUR,
            listOf(candidate("opponent")))
    }

    private fun candidate(id: String, level: Long = AdventureRelationshipEngine.MIN_HERO_LEVEL) =
        AdventureEncounterCandidate(id, "모험가 $id", HeroClass.MAGE, level, 100L)

    private fun battleCandidate(id: String, level: Long) = AdventureEncounterCandidate(
        characterId = id,
        displayName = "전투 모험가 $id",
        heroClass = HeroClass.MAGE,
        level = level,
        combatPower = 500L,
        stats = HeroStats(14L, 14L, 14L, 18L, 17L, 13L, 120L, 80L),
        equipment = EquipmentSlot.entries.map { slot ->
            AdventureRelationshipEquipmentSnapshot(slot, "${slot.labelKo} 장비", 20L, "일반")
        },
    )

    private fun selectedRewardKinds(run: com.nullplaying.model.AdventureRelationshipRun): Int = listOf(
        run.rewardKind == AdventureEventRewardKind.EXPERIENCE && run.experienceReward > 0L,
        run.rewardKind == AdventureEventRewardKind.GOLD && run.goldReward > 0L,
        run.rewardKind == AdventureEventRewardKind.ITEM && run.itemReward != com.nullplaying.model.AdventureEventItemReward.NONE,
    ).count { it }

    private fun actualRewardKinds(result: AdventureRelationshipResult): Int = listOf(
        result.rewardKind == AdventureEventRewardKind.EXPERIENCE && result.experienceAwarded > 0L,
        result.rewardKind == AdventureEventRewardKind.GOLD && result.goldAwarded > 0L,
        result.rewardKind == AdventureEventRewardKind.ITEM && result.itemName.isNotBlank(),
    ).count { it }
    private fun restore(game: SimpleGameState): SimpleGameState = Json.decodeFromString(Json.encodeToString(game))
    companion object { private const val HOUR = 3_600_000L }
}
