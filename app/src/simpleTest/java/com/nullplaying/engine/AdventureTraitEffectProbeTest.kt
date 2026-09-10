package com.nullplaying.engine

import com.nullplaying.model.AdventureEventItemReward
import com.nullplaying.model.AdventureEventOutcome
import com.nullplaying.model.AdventureEventRewardKind
import com.nullplaying.model.AdventureEventRun
import com.nullplaying.model.AdventureOwnedTrait
import com.nullplaying.model.AdventurePhase
import com.nullplaying.model.AdventureTraitEffectKind
import com.nullplaying.model.EquipmentSlot
import com.nullplaying.model.HeroClass
import com.nullplaying.model.InventoryItem
import com.nullplaying.model.SimpleGameState
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Forced ownership and selected passing seeds verify mechanics; never label these natural rates. */
class AdventureTraitEffectProbeTest {
    @Test(timeout = 120_000L)
    fun `extra item decision has one tenth percent frequency and source key idempotence`() {
        val game = held("L01")
        val stacked = held("L01", "L04", "L05", "S05", "E03", "G01", "R05", "C03", "S01")
        game.adventureTraits.seed = 0x47A1_5C83L
        stacked.adventureTraits.seed = game.adventureTraits.seed
        val draws = 200_000
        var passed = 0
        repeat(draws) { index ->
            val key = "original-reward:$index"
            val first = AdventureTraitEngine.roll(game, "L01", key, AdventureTraitEngine.EXTRA_ITEM_BASIS_POINTS)
            val repeated = AdventureTraitEngine.roll(game, "L01", key, AdventureTraitEngine.EXTRA_ITEM_BASIS_POINTS)
            val otherHeld = AdventureTraitEngine.roll(stacked, "L01", key, AdventureTraitEngine.EXTRA_ITEM_BASIS_POINTS)
            assertEquals(first, repeated)
            assertEquals("Other holdings cannot multiply the shared extra-item roll", first, otherHeld)
            if (first) passed++
        }
        assertEquals(10, AdventureTraitEngine.EXTRA_ITEM_BASIS_POINTS)
        assertEquals(draws.toLong(), game.adventureTraits.opportunityCounts["L01"])
        assertEquals(passed.toLong(), game.adventureTraits.procCounts["L01"])
        assertEquals(0L, game.adventureTraits.actualEffectCounts["L01"] ?: 0L)
        assertTrue("Fixed-source frequency: $passed / $draws", passed in 150..250)
        assertTrue("A passed roll alone is not a life event", game.adventureTraits.evidence.isEmpty())
        assertTrue(game.adventureTraits.recentChanges.isEmpty())
        AdventureQaFixtures.stagingQaDirectory().resolve("adventure-trait-extra-decision-frequency.csv").toFile().writeText(
            "sample,eligible_original_sources,passed_rolls,observed_percent,actual_awards\n" +
                "forced_held_rng_only,$draws,$passed,${String.format(Locale.ROOT, "%.6f", passed * 100.0 / draws)},0\n")
    }

    @Test
    fun `decisive and steady strikes change both ends preserve the midpoint and consume one basic hit`() {
        listOf("C03", "C04").forEach { id ->
            val percents = (40L..60L).map { original ->
                val game = held(id)
                game.adventureTraits.seed = passingSeed("$id:combat:1", 50)
                AdventureTraitEngine.beginCombat(game, AdventureQaFixtures.EPOCH)
                val changed = AdventureTraitEngine.basicDamagePercent(game, original, 1_000L, 10_000L, AdventureQaFixtures.EPOCH)
                assertEquals("The second basic hit is unchanged", 60L,
                    AdventureTraitEngine.basicDamagePercent(game, 60L, 1_000L, 10_000L, AdventureQaFixtures.EPOCH + 1L))
                assertEquals(1L, game.adventureTraits.opportunityCounts[id])
                assertTrue((game.adventureTraits.actualEffectCounts[id] ?: 0L) <= 1L)
                changed
            }
            assertEquals(50.0, percents.average(), 0.0)
            assertEquals(if (id == "C03") 30L else 45L, percents.first())
            assertEquals(if (id == "C03") 70L else 55L, percents.last())
        }
    }

    @Test
    fun `sales use one batch roll and adjust the existing charisma total once`() {
        listOf("S01", "S02").forEach { id ->
            val game = held(id)
            val engine = SimpleGameEngine()
            game.adventureTraits.seed = passingSeed("$id:town:1:sale", 300)
            AdventureTraitEngine.beginSource(game, "town", "sale", AdventureQaFixtures.EPOCH)
            val items = (1..31).map { index -> InventoryItem(index.toLong(), "거래 표본 $index",
                if (index % 3 == 0) "희귀" else "일반", "전리품", 20L + index % 3) }
            val prices = items.map { it.id to AdventureQaFixtures.baselineSaleValue(engine, game, it) }
            val base = prices.sumOf { it.second }
            val batch = AdventureTraitEngine.beginSale(game, prices, AdventureQaFixtures.EPOCH)
            val expected = if (id == "S01") base + base / 20L else base - base / 20L
            assertEquals(prices.map { it.second }, batch.baseValues)
            assertEquals(expected, batch.paidValues.sum())
            assertEquals(if (id == "S01") 110 else 85, batch.durationPercent)
            assertTrue(batch.paidValues.all { it >= 0L })
            assertEquals(1L, game.adventureTraits.opportunityCounts[id])
            assertEquals(1L, game.adventureTraits.procCounts[id])
        }
    }

    @Test
    fun `packing grants temporary capacity and longer preparation without deleting return overflow`() {
        val game = held("L04")
        val normal = game.inventoryCapacity()
        game.adventureTraits.seed = passingSeed("L04:departure:1", 100)
        val duration = AdventureTraitEngine.depart(game, AdventureQaFixtures.EPOCH, 4_000L)
        assertEquals(4_600L, duration)
        assertEquals(normal + 3L, game.inventoryCapacity())
        game.inventory = MutableList((normal + 3L).toInt()) { InventoryItem(it + 1L, "짐 $it", "일반", "전리품", 50L) }
        val items = game.inventory.toList()
        AdventureTraitEngine.beginReturn(game, AdventureQaFixtures.EPOCH + 60_000L)
        assertEquals(normal, game.inventoryCapacity())
        assertEquals(items, game.inventory)
    }

    @Test
    fun `travel light excludes valuable trophy rarity and removes one eligible item with its time cost`() {
        val game = held("L02")
        AdventureTraitEngine.beginSource(game, "combat", "family", AdventureQaFixtures.EPOCH)
        val key = "combat:1:reward:1"
        game.adventureTraits.seed = passingSeed("L02:$key", 10)
        assertFalse(AdventureTraitEngine.omitTrophy(game, key, "희귀", "귀한 표본", AdventureQaFixtures.EPOCH))
        assertEquals(0L, game.adventureTraits.opportunityCounts["L02"] ?: 0L)
        assertTrue(AdventureTraitEngine.omitTrophy(game, key, "일반", "작은 표본", AdventureQaFixtures.EPOCH))
        assertEquals(850L, AdventureTraitEngine.resultMillis(game, 1_000L))
        assertEquals(AdventureTraitEffectKind.OMITTED_ITEM, game.adventureTraits.recentActivations.single().effectKind)
    }

    @Test
    fun `new experience gains five percent once and requires more reflection time`() {
        val game = held("G01")
        game.adventureTraits.seed = passingSeed("G01:combat:1:xp", 100)
        AdventureTraitEngine.beginSource(game, "combat", "new-family", AdventureQaFixtures.EPOCH)
        assertEquals(229L, AdventureTraitEngine.experience(game, 219L, "new-family", AdventureQaFixtures.EPOCH))
        assertEquals(1_100L, AdventureTraitEngine.resultMillis(game, 1_000L))
        AdventureTraitEngine.beginSource(game, "combat", "new-family", AdventureQaFixtures.EPOCH + 1L)
        assertEquals(219L, AdventureTraitEngine.experience(game, 219L, "new-family", AdventureQaFixtures.EPOCH + 1L))
        assertEquals(1_000L, AdventureTraitEngine.resultMillis(game, 1_000L))
        assertEquals(1L, game.adventureTraits.opportunityCounts["G01"])
    }

    @Test
    fun `speech traits strengthen or soften both positive and negative local memories`() {
        listOf("R05", "R06").forEach { id ->
            listOf(4, -4).forEach { originalDelta ->
                val game = held(id)
                game.adventureRelationships.roster = AdventureQaFixtures.roster(game)
                AdventureRelationshipEngine.initialize(game, AdventureQaFixtures.EPOCH)
                game.adventureRelationships.nextEncounterAt = AdventureQaFixtures.EPOCH
                val run = requireNotNull(AdventureRelationshipEngine.tryBegin(game, AdventureQaFixtures.EPOCH))
                    .copy(scoreBefore = 0, scoreDelta = originalDelta)
                game.adventureTraits.seed = passingSeed("$id:relationship:1:tone", 300)
                AdventureTraitEngine.planRelationship(game, run)
                val direction = if (originalDelta > 0) 1 else -1
                val expected = if (id == "R05") originalDelta + direction else originalDelta - direction
                assertEquals(expected, game.adventureTraits.source?.relationshipDelta)
                assertEquals(run, game.adventureTraits.source?.baseRelationship)
                assertEquals(1L, game.adventureTraits.actualEffectCounts[id])
            }
        }
    }

    @Test
    fun `speech dialogue has both favorable and unfavorable resolved event results`() {
        listOf(0, 1).forEach { branch ->
            val game = held("R05")
            val base = baseEvent(game, "mediation", "evidence", AdventureEventOutcome.PARTIAL)
            game.adventureTraits.seed = passingSeed("R05:event:1:dialogue", 300) {
                AdventureTraitEngine.random(it, "event:1:decisive", 2) == branch
            }
            val changed = AdventureTraitEngine.planEvent(game, base)
            assertEquals(if (branch == 0) AdventureEventOutcome.SUCCESS else AdventureEventOutcome.FAILURE, changed.outcome)
            assertEquals(changed, game.adventureTraits.source?.baseEvent)
        }
        listOf(AdventureEventOutcome.SUCCESS, AdventureEventOutcome.FAILURE).forEach { outcome ->
            val game = held("R06")
            val base = baseEvent(game, "mediation", "listen", outcome)
            game.adventureTraits.seed = passingSeed("R06:event:1:dialogue", 300)
            val changed = AdventureTraitEngine.planEvent(game, base)
            assertEquals(AdventureEventOutcome.PARTIAL, changed.outcome)
            assertEquals(changed, game.adventureTraits.source?.baseEvent)
        }
    }

    @Test
    fun `investigation uses one different authored approach and retains the better reward bundle`() {
        val game = held("E03")
        val base = baseEvent(game, "ruins", "decode", AdventureEventOutcome.PARTIAL)
        game.adventureTraits.seed = passingSeed("E03:event:1:retry", 100)
        val changed = AdventureTraitEngine.planEvent(game, base)
        val retry = requireNotNull(game.adventureTraits.source?.retryRun)
        assertTrue(retry.approachId != base.approachId)
        assertTrue(AdventureEventEngine.definition(base.eventId).approaches.any { it.id == retry.approachId })
        assertEquals(minOf(base.outcome.ordinal, retry.outcome.ordinal), changed.outcome.ordinal)
        assertEquals(base.durationMillis + base.durationMillis / 5L, changed.durationMillis)
        val chosen = if (retry.outcome.ordinal < base.outcome.ordinal) retry else base
        assertEquals(chosen.experienceReward, changed.experienceReward)
        assertEquals(chosen.goldReward, changed.goldReward)
        assertEquals(chosen.itemReward, changed.itemReward)
        assertEquals(1L, game.adventureTraits.opportunityCounts["E03"])
        assertEquals(changed, game.adventureTraits.source?.baseEvent)
    }

    @Test(timeout = 180_000L)
    fun `passing extra rewards create independent items once and preserve all equipment slots`() {
        val slotCounts = EquipmentSlot.entries.associateWith { 0 }.toMutableMap()
        var accepted = 0
        var trial = 1L
        while (accepted < 3_000 && trial <= 6_000_000L) {
            val traitSeed = trial++
            if (AdventureTraitEngine.random(traitSeed, "L01:event:1:reward:1") >= 10) continue
            val game = settledReward(listOf("L01"), traitSeed, 97L, AdventureEventItemReward.TROPHY)
            val traces = game.adventureTraits.recentRewardTraces
            assertEquals(2L, game.totalItemsFound)
            assertEquals(1, traces.count { it.origin == "PRIMARY" && it.actualGranted })
            assertEquals(1, traces.count { it.origin == "TRAIT_EXTRA" && it.actualGranted })
            assertEquals(1L, game.adventureTraits.opportunityCounts["L01"])
            assertEquals(1L, game.adventureTraits.procCounts["L01"])
            assertEquals(1L, game.adventureTraits.actualEffectCounts["L01"])
            assertEquals(5_750L, game.actionEndsAt - game.actionStartedAt)
            val extra = traces.single { it.origin == "TRAIT_EXTRA" }
            extra.slot?.let { slotCounts[it] = slotCounts.getValue(it) + 1 }
            if (accepted < 32) {
                val stacked = settledReward(listOf("L01", "L04", "L05", "S05", "E03", "G01", "R05", "C03", "S01"),
                    traitSeed, 97L, AdventureEventItemReward.TROPHY)
                assertEquals("Other held traits neither copy the original reward nor reroll the extra", traces,
                    stacked.adventureTraits.recentRewardTraces)
                assertEquals("Derived gear never gets another appraisal chance", 0L, stacked.adventureTraits.opportunityCounts["L05"] ?: 0L)
            }
            accepted++
        }
        assertEquals(3_000, accepted)
        assertTrue("Independently generated extra gear occurs", slotCounts.values.sum() in 30..90)
        assertTrue("Every original equipment slot remains reachable: $slotCounts", slotCounts.values.all { it > 0 })
        AdventureQaFixtures.stagingQaDirectory().resolve("adventure-trait-extra-actual-rewards.csv").toFile().writeText(
            "sample,accepted_extra_rolls,equipment,${EquipmentSlot.entries.joinToString(",") { it.name.lowercase() }}\n" +
                "forced_held_passing_seed,3000,${slotCounts.values.sum()},${EquipmentSlot.entries.joinToString(",") { slotCounts.getValue(it).toString() }}\n")

        val passing = passingSeed("L01:event:1:reward:1", 10)
        val lastSlot = settledReward(listOf("L01"), passing, 97L, AdventureEventItemReward.TROPHY, freeSlots = 1)
        assertEquals(1L, lastSlot.adventureTraits.procCounts["L01"])
        assertEquals(0L, lastSlot.adventureTraits.actualEffectCounts["L01"] ?: 0L)
        assertEquals(1, lastSlot.adventureTraits.recentRewardTraces.count { it.actualGranted })
        assertEquals(5_000L, lastSlot.actionEndsAt - lastSlot.actionStartedAt)
        val full = settledReward(listOf("L01"), passing, 97L, AdventureEventItemReward.TROPHY, freeSlots = 0)
        assertEquals(0L, full.adventureTraits.opportunityCounts["L01"] ?: 0L)
        assertTrue(full.adventureTraits.recentRewardTraces.none { it.actualGranted })
        assertFalse("A rejected original reward is not a successful exploration pickup",
            "exploration" in full.adventureTraits.prerequisites)
    }

    @Test(timeout = 120_000L)
    fun `appraisal keeps six original slots and rarity and adds cost only when power improves`() {
        val appraiseSeed = passingSeed("L05:event:1:reward:1", 100) {
            AdventureTraitEngine.random(it, "event:1:reward:1:appraisePower", 12) in 4..8
        }
        val slots = EquipmentSlot.entries.associateWith { 0 }.toMutableMap()
        var improved = 0
        var unchanged = 0
        repeat(600) { index ->
            val rewardSeed = AdventureEventEngine.forkRewardSeed(index + 1L)
            val baseline = settledReward(emptyList(), appraiseSeed, rewardSeed, AdventureEventItemReward.EQUIPMENT)
            val appraised = settledReward(listOf("L05"), appraiseSeed, rewardSeed, AdventureEventItemReward.EQUIPMENT)
            val original = baseline.adventureTraits.recentRewardTraces.single()
            val result = appraised.adventureTraits.recentRewardTraces.single()
            val slot = requireNotNull(result.slot)
            slots[slot] = slots.getValue(slot) + 1
            assertEquals(original.slot, result.slot)
            assertEquals(original.rarity, result.rarity)
            assertEquals(original.foundAtLevel, result.foundAtLevel)
            assertEquals(original.finalPower, result.originalPower)
            val actualPower = requireNotNull(result.finalPower)
            val originalPower = requireNotNull(original.finalPower)
            assertTrue(actualPower >= originalPower)
            assertTrue(actualPower <= SimpleGameEngine().lootEquipmentPowerForRoll(result.foundAtLevel, result.rarity, 11))
            assertEquals(1L, appraised.adventureTraits.opportunityCounts["L05"])
            assertEquals(1L, appraised.adventureTraits.procCounts["L05"])
            if (actualPower > originalPower) {
                improved++
                assertEquals(1L, appraised.adventureTraits.actualEffectCounts["L05"])
                assertEquals(5_750L, appraised.actionEndsAt - appraised.actionStartedAt)
            } else {
                unchanged++
                assertEquals(0L, appraised.adventureTraits.actualEffectCounts["L05"] ?: 0L)
                assertEquals(5_000L, appraised.actionEndsAt - appraised.actionStartedAt)
            }
        }
        assertTrue(improved > 0 && unchanged > 0)
        assertTrue("Six-slot input distribution remains intact: $slots", slots.values.all { it in 54..150 })
        AdventureQaFixtures.stagingQaDirectory().resolve("adventure-trait-appraisal-preservation.csv").toFile().writeText(
            "sample,gear,improved,unchanged,${EquipmentSlot.entries.joinToString(",") { it.name.lowercase() }}\n" +
                "forced_held_passing_seed,600,$improved,$unchanged,${EquipmentSlot.entries.joinToString(",") { slots.getValue(it).toString() }}\n")
        val protected = settledReward(listOf("L02"), passingSeed("L02:event:1:reward:1", 10), 97L, AdventureEventItemReward.EQUIPMENT)
        assertEquals(1L, protected.totalItemsFound)
        assertEquals(0L, protected.adventureTraits.opportunityCounts["L02"] ?: 0L)
    }

    @Test
    fun `extra shop review costs real time and only an affordable permitted upgrade is bought`() {
        val engine = SimpleGameEngine(enableAdventureTraits = true)
        listOf("buy", "unaffordable", "protected_rarity", "not_upgrade").forEach { mode ->
            val game = held("S05")
            game.adventureTraits.seed = passingSeed("S05:town:1:shop", 300)
            AdventureTraitEngine.beginSource(game, "town", "shop", AdventureQaFixtures.EPOCH)
            game.shopAttemptedSlots = EquipmentSlot.entries.toMutableList()
            val weapon = game.equipment.single { it.slot == EquipmentSlot.WEAPON }
            weapon.power = if (mode == "not_upgrade") 1_000_000L else 1L
            weapon.rarity = if (mode == "protected_rarity") "신화" else "일반"
            weapon.acquiredAtLevel = game.hero.level
            game.hero.gold = if (mode == "unaffordable") 0L else 1_000_000_000L
            val beforeGold = game.hero.gold
            game.adventurePhase = AdventurePhase.EQUIPPING
            game.actionStartedAt = AdventureQaFixtures.EPOCH
            game.actionEndsAt = AdventureQaFixtures.EPOCH + 1L
            engine.settleOffline(game, game.actionEndsAt)
            assertEquals(1L, game.adventureTraits.opportunityCounts["S05"])
            assertEquals(1L, game.adventureTraits.actualEffectCounts["S05"])
            assertEquals(6, game.shopAttemptedSlots.size)
            assertTrue(requireNotNull(game.adventureTraits.shopVisit).extraUsed)
            assertEquals(0L, game.totalEquipmentPurchases)
            if (mode == "buy") {
                val offer = requireNotNull(game.pendingShopOffer)
                assertEquals(EquipmentSlot.WEAPON, offer.slot)
                assertEquals(engine.equipmentPrice(game.hero.level, EquipmentSlot.WEAPON), offer.price)
                assertEquals(SimpleGameEngine.SHOP_OFFER_MILLIS, game.actionEndsAt - game.actionStartedAt)
                engine.settleOffline(game, game.actionEndsAt)
                assertEquals(1L, game.totalEquipmentPurchases)
                assertEquals(beforeGold - offer.price, game.hero.gold)
                assertEquals(offer.newPower, weapon.power)
                engine.settleOffline(game, game.actionEndsAt)
                assertEquals(AdventurePhase.SHOPPING_EMPTY, game.adventurePhase)
                assertEquals(1L, game.totalEquipmentPurchases)
            } else {
                assertEquals(AdventurePhase.SHOPPING_EMPTY, game.adventurePhase)
                assertEquals(SimpleGameEngine.SHOP_EMPTY_RESULT_MILLIS + SimpleGameEngine.SHOP_OFFER_MILLIS,
                    game.actionEndsAt - game.actionStartedAt)
                assertEquals(beforeGold, game.hero.gold)
                assertEquals(null, game.pendingShopOffer)
            }
            assertEquals(1L, game.adventureTraits.opportunityCounts["S05"])
            assertEquals(1L, game.adventureTraits.actualEffectCounts["S05"])
            assertEquals("An extra purchase never changes the original six-slot review facts", 0L,
                requireNotNull(game.adventureTraits.shopVisit).basePurchases)
            engine.settleOffline(game, game.actionEndsAt)
            assertEquals(AdventurePhase.DEPARTING, game.adventurePhase)
            assertTrue("The original no-purchase visit still supplies its own evidence",
                "empty_visit" in game.adventureTraits.prerequisites)
        }
    }

    private fun settledReward(
        ids: List<String>, traitSeed: Long, rewardSeed: Long, reward: AdventureEventItemReward,
        freeSlots: Int? = null,
    ): SimpleGameState {
        val game = held(*ids.toTypedArray())
        game.adventureTraits.seed = traitSeed
        val base = baseEvent(game, "ruins", "decode", AdventureEventOutcome.SUCCESS).copy(
            rewardSeed = rewardSeed, experienceReward = 0L, goldReward = 0L, itemReward = reward,
            rewardKind = AdventureEventRewardKind.ITEM)
        if (freeSlots != null) {
            game.inventory = MutableList((game.inventoryCapacity() - freeSlots).toInt()) {
                InventoryItem(it + 1L, "이전 물품 $it", "일반", "전리품", game.hero.level)
            }
            game.totalItemsFound = game.inventory.size.toLong()
        }
        val run = AdventureTraitEngine.planEvent(game, base)
        game.adventureJourney.pending = run
        game.adventurePhase = AdventurePhase.EVENT
        game.actionStartedAt = AdventureQaFixtures.EPOCH
        game.actionEndsAt = AdventureQaFixtures.EPOCH + run.durationMillis
        SimpleGameEngine(enableAdventureEvents = true, enableAdventureTraits = true).settleOffline(game, game.actionEndsAt)
        return game
    }

    private fun held(vararg ids: String): SimpleGameState {
        val game = AdventureQaFixtures.game(SimpleGameEngine(), HeroClass.CLERIC, 503L, 50L)
        AdventureTraitEngine.initialize(game)
        game.adventureTraits.owned = ids.mapIndexed { index, id -> AdventureOwnedTrait(id, AdventureQaFixtures.EPOCH, index + 1L) }
        return game
    }

    private fun baseEvent(game: SimpleGameState, eventId: String, approachId: String, outcome: AdventureEventOutcome): AdventureEventRun {
        val first = AdventureEventEngine.begin(game, AdventureQaFixtures.EPOCH)
        val approach = AdventureEventEngine.definition(eventId).approaches.single { it.id == approachId }
        val matching = first.copy(eventId = eventId, approachId = approachId, primaryStat = approach.primaryStat,
            secondaryStat = approach.secondaryStat, primaryValue = AdventureEventEngine.stat(game.hero.stats, approach.primaryStat),
            secondaryValue = AdventureEventEngine.stat(game.hero.stats, approach.secondaryStat), itemReward = AdventureEventItemReward.NONE)
        return AdventureEventEngine.withOutcome(matching, outcome, matching.rewardSeed)
    }

    private fun passingSeed(key: String, threshold: Int, condition: (Long) -> Boolean = { true }): Long =
        (1L..1_000_000L).firstOrNull { AdventureTraitEngine.random(it, key) < threshold && condition(it) }
            ?: error("No deterministic passing fixture seed for $key")
}
