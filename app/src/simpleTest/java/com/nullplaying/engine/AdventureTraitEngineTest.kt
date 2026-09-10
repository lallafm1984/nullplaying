package com.nullplaying.engine

import com.nullplaying.model.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class AdventureTraitEngineTest {
    private val engine = SimpleGameEngine(enableAdventureEvents = true, enableAdventureTraits = true)

    @Test fun `catalog has forty complete localized two sided traits and twenty opposite pairs`() {
        assertEquals(40, AdventureTraitCatalog.all.size)
        assertEquals(40, AdventureTraitCatalog.all.map { it.id }.distinct().size)
        assertTrue(AdventureTraitCatalog.all.all { it.oppositeId.isNotBlank() })
        AdventureTraitCatalog.all.forEach { definition ->
            listOf(definition.name, definition.advantage, definition.disadvantage).forEach {
                assertTrue(it.ko.isNotBlank() && it.en.isNotBlank() && it.ja.isNotBlank())
                assertFalse(it.ko.contains('%') || it.en.contains('%') || it.ja.contains('%'))
            }
            assertEquals(definition.id, AdventureTraitCatalog.definition(definition.oppositeId).oppositeId)
        }
        assertEquals(20, AdventureTraitCatalog.all.map { setOf(it.id, it.oppositeId) }.toSet().size)
    }

    @Test fun `disabled constructor preserves previous events and default save state`() {
        val old = SimpleGameEngine(enableAdventureEvents = true)
        val explicit = SimpleGameEngine(enableAdventureEvents = true, enableAdventureTraits = false)
        val a = newGame(old); val b = newGame(explicit)
        old.settleOffline(a, HOUR); explicit.settleOffline(b, HOUR)
        assertEquals(a, b)
        assertFalse(a.adventureTraits.initialized)
    }

    @Test fun `extra item roll is one per original source and independent of other owned traits`() {
        val single = owned("L01")
        val many = owned("L01", "L04", "L05", "S01", "S05", "E03", "G01", "R05", "C03")
        single.adventureTraits.seed = 774L; many.adventureTraits.seed = 774L
        var passes = 0
        repeat(50_000) { index ->
            val key = "reward:$index"
            val expected = AdventureTraitEngine.random(774L, "L01:$key") < 10
            val a = AdventureTraitEngine.roll(single, "L01", key, 10)
            val b = AdventureTraitEngine.roll(many, "L01", key, 10)
            assertEquals(expected, a); assertEquals(a, b)
            if (a) passes++
        }
        assertTrue("Unexpected common distribution: $passes", passes in 25..80)
        assertEquals(50_000L, single.adventureTraits.opportunityCounts["L01"])
        assertEquals(single.adventureTraits.procCounts, many.adventureTraits.procCounts)
    }

    @Test fun `collector grants two real items once and derived item does not consume primary reward index`() {
        val game = eventReady(listOf("L01"), "bridge", AdventureEventOutcome.SUCCESS,
            AdventureEventItemReward.TROPHY) { seed -> pass(seed, "L01:event:1:reward:1", 10) }
        val before = game.totalItemsFound
        engine.settleOffline(game, game.actionEndsAt)
        val result = requireNotNull(game.adventureJourney.lastResult)
        assertEquals(2L, game.totalItemsFound - before)
        assertEquals(2, result.actualItemCount)
        assertEquals(1, result.additionalItemNames.size)
        assertEquals(1L, game.adventureTraits.source?.rewardSequence)
        assertEquals(1L, game.adventureTraits.opportunityCounts["L01"])
        assertEquals(listOf("PRIMARY", "TRAIT_EXTRA"), game.adventureTraits.recentRewardTraces.map { it.origin })
        assertTrue(game.actionEndsAt - game.actionStartedAt > AdventureEventEngine.RESULT_MILLIS)
        val saved = Json.encodeToString(game)
        engine.settleOffline(game, game.lastSettledAt)
        assertEquals(saved, Json.encodeToString(game))
    }

    @Test fun `valuable event trophy is not treated as a minor item to leave behind`() {
        val game = eventReady(listOf("L02"), "bridge", AdventureEventOutcome.SUCCESS,
            AdventureEventItemReward.TROPHY) { seed -> pass(seed, "L02:event:1:reward:1", 10) }
        engine.settleOffline(game, game.actionEndsAt)
        val result = requireNotNull(game.adventureJourney.lastResult)
        assertFalse(result.itemOmittedByTrait)
        assertEquals(1, result.actualItemCount)
        assertEquals(1, game.inventory.size)
        assertTrue(game.inventory.single().rarity in setOf("고급", "희귀", "영웅", "전설", "신화"))
        assertEquals(AdventureEventEngine.RESULT_MILLIS, game.actionEndsAt - game.actionStartedAt)
        assertTrue(game.adventureTraits.recentRewardTraces.single().actualGranted)
        assertEquals(0L, game.adventureTraits.opportunityCounts["L02"] ?: 0L)
        assertTrue(game.adventureTraits.visibleActivations.isEmpty())
    }

    @Test fun `appraisal preserves all six slots rarity and original power while independently improving actual gear`() {
        val slots = mutableSetOf<EquipmentSlot>()
        var improvements = 0
        for (rewardSeed in 1L..120L) {
            val game = eventReady(listOf("L05"), "ruins", AdventureEventOutcome.SUCCESS,
                AdventureEventItemReward.EQUIPMENT, rewardSeed = rewardSeed) { seed ->
                pass(seed, "L05:event:1:reward:1", 100) && AdventureTraitEngine.random(seed, "event:1:reward:1:appraisePower", 12) == 11
            }
            game.equipment.forEach { it.power = 0L }
            engine.settleOffline(game, game.actionEndsAt)
            val trace = game.adventureTraits.recentRewardTraces.single()
            slots += requireNotNull(trace.slot)
            val finalPower = requireNotNull(trace.finalPower)
            val originalPower = requireNotNull(trace.originalPower)
            assertTrue(finalPower >= originalPower)
            assertEquals(trace.finalPower, game.equipment.first { it.slot == trace.slot }.power)
            assertEquals(trace.rarity, game.equipment.first { it.slot == trace.slot }.rarity)
            if (finalPower > originalPower) improvements++
            if (slots.size == 6 && improvements >= 6) break
        }
        assertEquals(6, slots.size)
        assertTrue(improvements >= 6)
    }

    @Test fun `first basic attack transforms are symmetric and real attack damage follows the stored source`() {
        listOf("C03", "C04").forEach { id ->
            val percentages = (40L..60L).map { raw ->
                val game = owned(id)
                game.adventureTraits.seed = seedWhere { pass(it, "$id:combat:1", 50) }
                AdventureTraitEngine.beginCombat(game, 0L)
                AdventureTraitEngine.basicDamagePercent(game, raw, 100L, 10_000L, 1L)
            }
            assertEquals(1_050L, percentages.sum())
            assertEquals(if (id == "C03") 30L else 45L, percentages.minOrNull())
            assertEquals(if (id == "C03") 70L else 55L, percentages.maxOrNull())
            val game = owned(id)
            game.skills.clear()
            game.adventurePhase = AdventurePhase.LOOTING; game.actionEndsAt = 1_000L
            AdventureTraitEngine.ensureSource(game)
            game.adventureTraits.seed = seedWhere { pass(it, "$id:combat:2", 50) }
            engine.settleOffline(game, 1_000L)
            // Foreground presentation retains lastDamage; offline settlement intentionally clears it.
            engine.settle(game, game.actionEndsAt)
            val activation = game.adventureTraits.recentActivations.lastOrNull { it.effectKind == AdventureTraitEffectKind.DAMAGE }
            if (activation != null) assertEquals(activation.currentValue, game.lastDamage)
            assertFalse(game.adventureTraits.source!!.firstBasicPending)
            val raw = game.adventureTraits.source!!.rawBasicRolls.single().toLong()
            assertEquals(raw, AdventureTraitEngine.basicDamagePercent(game, raw, 100L, 10_000L, game.lastSettledAt))
        }
    }

    @Test fun `sale traits pay exact batch totals and persist the remaining allocation through restart`() {
        listOf("S01", "S02").forEach { id ->
            val game = owned(id)
            game.adventurePhase = AdventurePhase.RETURNING
            game.actionEndsAt = 1_000L
            game.inventory = mutableListOf(InventoryItem(1L, "첫 전리품", "일반", "전리품", 1L),
                InventoryItem(2L, "둘째 전리품", "희귀", "전리품", 2L), InventoryItem(3L, "셋째 전리품", "고급", "전리품", 3L))
            AdventureTraitEngine.ensureSource(game)
            val source = game.adventureTraits.source!!.key
            game.adventureTraits.seed = seedWhere { pass(it, "$id:$source:sale", 300) }
            engine.settleOffline(game, 1_000L)
            val batch = requireNotNull(game.adventureTraits.saleBatch)
            val base = batch.baseValues.sum()
            val expected = if (id == "S01") base + base / 20L else base - base / 20L
            assertEquals(expected, batch.paidValues.sum())
            assertEquals(if (id == "S01") 1_100L else 850L, game.actionEndsAt - game.actionStartedAt)
            val restored = restore(game)
            repeat(3) { engine.settleOffline(game, game.actionEndsAt); engine.settleOffline(restored, restored.actionEndsAt) }
            assertEquals(game, restored)
            assertEquals(expected, game.totalSaleGold)
            assertEquals(1L, game.adventureTraits.opportunityCounts[id])
            assertEquals(1L, game.adventureTraits.actualEffectCounts[id])
        }
    }

    @Test fun `temporary packing adds exactly three slots and expiry keeps already carried items`() {
        val game = owned("L04")
        game.adventurePhase = AdventurePhase.SHOPPING_EMPTY; game.actionEndsAt = 1_000L
        AdventureTraitEngine.ensureSource(game)
        game.adventureTraits.seed = seedWhere { pass(it, "L04:departure:2", 100) }
        val baseCapacity = game.inventoryCapacity()
        engine.settleOffline(game, 1_000L)
        assertEquals(AdventurePhase.DEPARTING, game.adventurePhase)
        assertEquals(baseCapacity + 3L, game.inventoryCapacity())
        assertEquals(4_600L, game.actionEndsAt - game.actionStartedAt)
        game.inventory = MutableList(game.inventoryCapacity().toInt()) { InventoryItem(it + 1L, "운반물", "일반", "전리품", 1L) }
        val count = game.inventory.size
        engine.settleOffline(game, game.actionEndsAt)
        assertEquals(AdventurePhase.RETURNING, game.adventurePhase)
        assertEquals(baseCapacity, game.inventoryCapacity())
        assertEquals(count, game.inventory.size)
    }

    @Test fun `extra weapon review happens once and costs time even when the actual offer is unsuitable`() {
        val game = owned("S05")
        game.adventurePhase = AdventurePhase.SELLING; game.actionEndsAt = 1_000L
        game.equipment.forEach { it.power = Long.MAX_VALUE / 10L }
        AdventureTraitEngine.ensureSource(game)
        val source = game.adventureTraits.source!!.key
        game.adventureTraits.seed = seedWhere { pass(it, "S05:$source:shop", 300) }
        engine.settleOffline(game, 1_000L)
        assertEquals(AdventurePhase.SHOPPING_EMPTY, game.adventurePhase)
        assertEquals(6, game.shopAttemptedSlots.size)
        assertTrue(game.adventureTraits.shopVisit!!.extraUsed)
        assertEquals(SimpleGameEngine.SHOP_EMPTY_RESULT_MILLIS + SimpleGameEngine.SHOP_OFFER_MILLIS,
            game.actionEndsAt - game.actionStartedAt)
        assertEquals(0L, game.totalEquipmentPurchases)
        assertEquals(1L, game.adventureTraits.actualEffectCounts["S05"])
        val restored = restore(game)
        engine.settleOffline(game, game.actionEndsAt); engine.settleOffline(restored, restored.actionEndsAt)
        assertEquals(game, restored)
        assertEquals(1L, game.adventureTraits.actualEffectCounts["S05"])
    }

    @Test fun `retry stores the alternate ability decision and gives only one derived reward bundle`() {
        val game = eventReady(listOf("E03", "L01", "L05", "G01"), "ruins", AdventureEventOutcome.PARTIAL,
            AdventureEventItemReward.NONE, rewardKind = AdventureEventRewardKind.ITEM) { seed -> pass(seed, "E03:event:1:retry", 100) &&
                AdventureTraitEngine.random(seed, "event:1:retryOutcome") < 1_500 }
        val source = game.adventureTraits.source!!
        assertNotNull(source.retryRun)
        assertEquals(source.retryRun!!.approachId, source.baseEvent!!.approachId)
        assertEquals(game.adventureJourney.pending, source.baseEvent)
        assertEquals(AdventureEventOutcome.SUCCESS, source.retryRun!!.outcome)
        assertEquals("TRAIT_RETRY", source.finalRewardOrigin)
        assertEquals(24_000L, game.actionEndsAt)
        val restored = restore(game)
        engine.settleOffline(game, game.actionEndsAt); engine.settleOffline(restored, restored.actionEndsAt)
        assertEquals(game, restored)
        assertEquals(1, game.adventureJourney.lastResult!!.actualItemCount)
        assertEquals("TRAIT_RETRY", game.adventureTraits.recentRewardTraces.single().origin)
        assertNull(game.adventureTraits.opportunityCounts["L01"])
        assertNull(game.adventureTraits.opportunityCounts["L05"])
        assertNull(game.adventureTraits.opportunityCounts["G01"])
    }

    @Test fun `new experience changes only its original xp and result time`() {
        val game = eventReady(listOf("G01"), "bridge", AdventureEventOutcome.SUCCESS,
            AdventureEventItemReward.NONE, experience = 100L) { seed -> pass(seed, "G01:event:1:xp", 100) }
        engine.settleOffline(game, game.actionEndsAt)
        assertEquals(105L, game.adventureJourney.lastResult!!.experienceAwarded)
        assertEquals(5_500L, game.actionEndsAt - game.actionStartedAt)
        assertEquals(1L, game.adventureTraits.actualEffectCounts["G01"])
    }

    @Test fun `both speech traits alter authored dialogue outcomes using one source roll`() {
        listOf("R05", "R06").forEach { id ->
            val original = if (id == "R05") AdventureEventOutcome.PARTIAL else AdventureEventOutcome.SUCCESS
            val game = eventReady(listOf(id), "mediation", original, AdventureEventItemReward.TROPHY,
                approachId = "listen") { seed -> pass(seed, "$id:event:1:dialogue", 300) }
            assertNotEquals(original, game.adventureJourney.pending!!.outcome)
            assertEquals(game.adventureJourney.pending, game.adventureTraits.source!!.baseEvent)
            assertEquals(1L, game.adventureTraits.opportunityCounts[id])
            engine.settleOffline(game, game.actionEndsAt)
            assertEquals(1L, game.adventureTraits.actualEffectCounts[id])
            assertEquals(1L, game.adventureJourney.completedEvents)
            assertEquals(0L, game.totalKills)
        }
    }

    @Test fun `six hour bulk and chunked replay preserve every activation event beyond compact history limit`() {
        val bulk = owned("C03", "L01", "L04", "L05", "S01", "S05", "E03", "G01", "R05")
        var split = restore(bulk)
        val bulkEvents = engine.settleOffline(bulk, 6L * HOUR).recentEvents
        val splitEvents = mutableListOf<RecentAdventureEvent>()
        repeat(72) {
            splitEvents += engine.settleOffline(split, (it + 1L) * 5L * 60_000L).recentEvents
            if (it % 5 == 0) split = restore(split)
        }
        assertEquals(bulk, split)
        assertEquals(bulkEvents, splitEvents)
        assertTrue(bulk.adventureTraits.recentActivations.size <= 32)
        assertTrue(bulk.adventureTraits.visibleActivations.size <= 4)
    }

    @Test fun `old saves initialize empty traits while pause preserves pending trait plans and effects`() {
        val game = eventReady(listOf("E03"), "ruins", AdventureEventOutcome.PARTIAL,
            AdventureEventItemReward.NONE, rewardKind = AdventureEventRewardKind.ITEM) { seed -> pass(seed, "E03:event:1:retry", 100) }
        engine.settleOffline(game, 10_000L)
        val before = restore(game)
        game.offlineAdventureMillis = 0L
        engine.settleOfflineWithOfflineAdventure(game, 10_000L + 24L * HOUR)
        assertEquals(before.actionEndsAt + 24L * HOUR, game.actionEndsAt)
        assertEquals(before.adventureTraits.source!!.retryRun!!.roll, game.adventureTraits.source!!.retryRun!!.roll)
        assertEquals(before.adventureTraits.source!!.retryRun!!.startedAt + 24L * HOUR,
            game.adventureTraits.source!!.retryRun!!.startedAt)
        val oldJson = JsonObject(Json.parseToJsonElement(Json.encodeToString(newGame())).jsonObject.filterKeys { it != "adventureTraits" })
        val old = Json.decodeFromString<SimpleGameState>(oldJson.toString())
        assertFalse(old.adventureTraits.initialized)
        engine.settleOffline(old, 1L)
        assertTrue(old.adventureTraits.initialized)
        assertTrue(old.adventureTraits.owned.isEmpty())
    }

    @Test fun `combat styles use the same first three basics with neutral gaps and no finishing bias`() {
        val examples = listOf(
            listOf(40L, 47L, 55L) to "C03",
            listOf(52L, 55L, 58L) to "C04",
            listOf(40L, 47L, 54L) to null,
            listOf(40L, 44L, 47L) to null,
            listOf(40L, 60L) to null,
        )
        examples.forEach { (rolls, expected) ->
            listOf(0, 60).forEach { finishingRaw ->
                val game = owned()
                AdventureTraitEngine.beginCombat(game, 0L)
                rolls.forEachIndexed { index, raw ->
                    AdventureTraitEngine.basicDamagePercent(game, raw, 100L, 100_000L, index + 1L)
                }
                game.adventureTraits.source!!.finishingRawPercent = finishingRaw
                AdventureTraitEngine.observeCombat(game, 100L)
                AdventureTraitEngine.finalizeEvidence(game, 100L)
                val support = listOf("C03", "C04").filter { id ->
                    game.adventureTraits.evidence[id].orEmpty().any { it.positive }
                }
                assertEquals(expected?.let { listOf(it) }.orEmpty(), support)
                if (expected != null) assertFalse(game.adventureTraits.evidence.getValue(
                    if (expected == "C03") "C04" else "C03").single().positive)
            }
        }
    }

    @Test fun `long combats preserve the original three basics through restart and altered combat gives no style evidence`() {
        val game = owned()
        AdventureTraitEngine.beginCombat(game, 0L)
        listOf(49L, 50L, 51L).forEachIndexed { index, raw ->
            AdventureTraitEngine.basicDamagePercent(game, raw, 100L, 100_000L, index + 1L)
        }
        val restarted = restore(game)
        repeat(150) { index ->
            val raw = if (index % 2 == 0) 40L else 60L
            AdventureTraitEngine.basicDamagePercent(game, raw, 100L, 100_000L, index + 4L)
            AdventureTraitEngine.basicDamagePercent(restarted, raw, 100L, 100_000L, index + 4L)
        }
        assertEquals(listOf(49, 50, 51), game.adventureTraits.source!!.rawBasicRolls)
        AdventureTraitEngine.observeCombat(game, 200L)
        AdventureTraitEngine.finalizeEvidence(game, 200L)
        AdventureTraitEngine.observeCombat(restarted, 200L)
        AdventureTraitEngine.finalizeEvidence(restarted, 200L)
        assertEquals(game, restarted)
        assertTrue(game.adventureTraits.evidence.getValue("C04").single().positive)
        val altered = owned("C03")
        altered.adventureTraits.seed = seedWhere { pass(it, "C03:combat:1", 50) }
        AdventureTraitEngine.beginCombat(altered, 0L)
        listOf(40L, 60L, 50L).forEachIndexed { index, raw ->
            AdventureTraitEngine.basicDamagePercent(altered, raw, 100L, 100_000L, index + 1L)
        }
        assertTrue(altered.adventureTraits.source!!.combatAltered)
        AdventureTraitEngine.observeCombat(altered, 100L)
        AdventureTraitEngine.finalizeEvidence(altered, 100L)
        assertTrue(altered.adventureTraits.evidence["C03"].orEmpty().isEmpty())
        assertTrue(altered.adventureTraits.evidence["C04"].orEmpty().isEmpty())
    }

    @Test fun `shaky traits ignore five six oscillation and recover only after diverse new support across restart`() {
        val game = owned("G01")
        val initialSigns = listOf(false, false, true, false, true, false, true, false, true, false, true, true)
        game.adventureTraits.evidence = mapOf("G01" to initialSigns.mapIndexed { index, positive ->
            AdventureTraitEvidence("initial:$index", "family:${index % 3}", positive, "adventure:variety")
        })
        game.adventureTraits.stableStartedAtByTrait = mapOf("G01" to 0L)
        game.adventureTraits.oppositionStartedAtByTrait = mapOf("G01" to 0L)
        val weakenedAt = AdventureTraitEngine.STABLE_MIN_ACTIVE_MILLIS
        applyTraitEvidence(game, weakenedAt, false, "family:0")
        assertTrue(game.adventureTraits.owned.single().shaky)
        assertEquals(listOf(AdventureTraitChangeKind.WEAKENED), game.adventureTraits.recentChanges.map { it.kind })
        var restarted = restore(game)
        listOf(true, false, true, false).forEachIndexed { index, positive ->
            val at = weakenedAt + (index + 1L) * HOUR
            applyTraitEvidence(game, at, positive, "family:${index % 3}")
            applyTraitEvidence(restarted, at, positive, "family:${index % 3}")
            restarted = restore(restarted)
            assertEquals(game, restarted)
            assertTrue(game.adventureTraits.owned.single().shaky)
            assertEquals(1, game.adventureTraits.recentChanges.size)
        }
        repeat(12) { index ->
            val at = weakenedAt + AdventureTraitEngine.SHAKY_RECOVERY_MIN_ACTIVE_MILLIS + index * HOUR
            applyTraitEvidence(game, at, true, "family:${index % 3}")
            applyTraitEvidence(restarted, at, true, "family:${index % 3}")
            restarted = restore(restarted)
            assertEquals(game, restarted)
        }
        assertFalse(game.adventureTraits.owned.single().shaky)
        assertEquals(listOf(AdventureTraitChangeKind.WEAKENED, AdventureTraitChangeKind.RECOVERED),
            game.adventureTraits.recentChanges.map { it.kind })
        assertEquals(AdventureTraitEngine.drainRecentEvents(game), AdventureTraitEngine.drainRecentEvents(restarted))
    }

    @Test fun `shaky recovery needs fresh support and three support contexts without changing loss threshold`() {
        val game = owned("G01")
        game.adventureTraits.owned = listOf(AdventureOwnedTrait("G01", shaky = true))
        game.adventureTraits.weakenedStartedAtByTrait = mapOf("G01" to 0L)
        game.adventureTraits.evidence = mapOf("G01" to List(9) { index ->
            AdventureTraitEvidence("initial:$index", "family:0", true, "adventure:variety")
        })
        applyTraitEvidence(game, HOUR, null, "unrelated")
        assertTrue(game.adventureTraits.owned.single().shaky)
        applyTraitEvidence(game, 2L * HOUR, true, "family:0")
        assertTrue(game.adventureTraits.owned.single().shaky)
        applyTraitEvidence(game, 3L * HOUR, true, "family:1")
        assertTrue(game.adventureTraits.owned.single().shaky)
        applyTraitEvidence(game, AdventureTraitEngine.SHAKY_RECOVERY_MIN_ACTIVE_MILLIS, true, "family:2")
        assertFalse(game.adventureTraits.owned.single().shaky)
        assertEquals(AdventureTraitChangeKind.RECOVERED, game.adventureTraits.recentChanges.single().kind)
        val opposingHours = listOf(9L, 12L, 16L, 20L, 24L, 32L, 33L, 34L, 35L)
        opposingHours.forEachIndexed { index, hour ->
            applyTraitEvidence(game, hour * HOUR, false, "opposite:${index % 3}")
        }
        assertTrue(game.adventureTraits.owned.single().shaky)
        applyTraitEvidence(game, 56L * HOUR, false, "opposite:0")
        assertTrue(game.adventureTraits.owned.isEmpty())
        assertEquals(AdventureTraitChangeKind.LOST, game.adventureTraits.recentChanges.last().kind)
    }

    private fun applyTraitEvidence(game: SimpleGameState, sequence: Long, positive: Boolean?, context: String) {
        val source = AdventureTraitEngine.beginSource(game, "evidence", context, sequence)
        AdventureTraitEngine.observe(game, source.key + ":fact", context,
            if (positive == true) setOf("G01") else emptySet(),
            if (positive == false) setOf("G01") else emptySet(), sequence, "adventure:variety")
        AdventureTraitEngine.finalizeEvidence(game, sequence)
    }

    private fun eventReady(ids: List<String>, eventId: String, outcome: AdventureEventOutcome,
        item: AdventureEventItemReward, rewardSeed: Long = 91L, experience: Long = 20L,
        approachId: String? = null,
        rewardKind: AdventureEventRewardKind = if (item != AdventureEventItemReward.NONE) {
            AdventureEventRewardKind.ITEM
        } else {
            AdventureEventRewardKind.EXPERIENCE
        },
        predicate: (Long) -> Boolean,
    ): SimpleGameState {
        val game = owned(*ids.toTypedArray())
        val definition = AdventureEventEngine.definition(eventId)
        val base = AdventureEventEngine.begin(game, 0L).copy(eventId = eventId,
            approachId = approachId ?: definition.approaches.first().id,
            outcome = outcome, itemReward = item, rewardSeed = rewardSeed, experienceReward = experience,
            goldReward = 0L, durationMillis = 20_000L, rewardKind = rewardKind)
        game.adventureTraits.seed = seedWhere(predicate)
        val run = AdventureTraitEngine.planEvent(game, base)
        game.adventureJourney.pending = run
        game.adventurePhase = AdventurePhase.EVENT
        game.actionStartedAt = 0L; game.actionEndsAt = run.durationMillis
        return game
    }

    private fun owned(vararg ids: String): SimpleGameState = newGame().also {
        it.adventureTraits.owned = ids.map { id -> AdventureOwnedTrait(id) }
    }
    private fun newGame(owner: SimpleGameEngine = engine): SimpleGameState = owner.newGame("특성 검증", HeroClass.WARRIOR,
        owner.rollStats(77L).stats, 88L, 0L)
    private fun pass(seed: Long, key: String, basisPoints: Int) = AdventureTraitEngine.random(seed, key) < basisPoints
    private fun seedWhere(predicate: (Long) -> Boolean): Long = (1L..2_000_000L).first(predicate)
    private fun restore(game: SimpleGameState): SimpleGameState = Json.decodeFromString(Json.encodeToString(game))
    companion object { private const val HOUR = 3_600_000L }
}
