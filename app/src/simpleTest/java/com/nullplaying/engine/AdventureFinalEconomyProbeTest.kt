package com.nullplaying.engine

import com.nullplaying.model.AdventurePhase
import com.nullplaying.model.AdventureEventOutcome
import com.nullplaying.model.AdventureEventRewardKind
import com.nullplaying.model.AdventureTraitChangeKind
import com.nullplaying.model.InventoryItem
import com.nullplaying.model.RecentAdventureEvent
import com.nullplaying.model.RecentAdventureEventType
import com.nullplaying.model.SimpleGameState
import java.io.BufferedWriter
import java.util.Locale
import java.util.zip.GZIPOutputStream
import kotlin.math.max
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Final paired economics: natural traits only. Forced ownership belongs to separate effect QA. */
class AdventureFinalEconomyProbeTest {
    private data class Scenario(val id: String, val level: Long, val depth: Long = 0L)
    private val rows = mutableListOf<Map<String, Any>>()
    private val traitRows = mutableListOf<Map<String, Any>>()

    @Test(timeout = 300_000L)
    fun `six hour heldout cohort reconciles cash inventory rewards and natural trait exposure`() {
        val qa = AdventureQaFixtures.stagingQaDirectory()
        val ledgerPath = qa.resolve("adventure-final-economy-ledger.csv.gz")
        try {
            GZIPOutputStream(ledgerPath.toFile().outputStream()).bufferedWriter(Charsets.UTF_8).use { ledger ->
                ledger.appendLine("scenario,class,seed,mode,at,operation,source,item_id,item_level,rarity,slot,power,original_power,equipped,base_sale_value,actual_gold,name")
                SCENARIOS.forEach { scenario ->
                    com.nullplaying.model.HeroClass.entries.forEach { heroClass ->
                        AdventureQaFixtures.finalSeeds.forEach { seed ->
                            listOf(false, true).forEach { enabled ->
                                measure(scenario, heroClass, seed, enabled, ledger)
                            }
                        }
                    }
                }
            }
        } finally {
            writeTable(qa.resolve("adventure-final-economy.csv").toFile().bufferedWriter(), rows)
            writeTable(qa.resolve("adventure-final-natural-traits.csv").toFile().bufferedWriter(), traitRows)
            qa.resolve("adventure-final-economy-notes.txt").toFile().writeText(
                "Synthetic paired fixtures, never production saves or live server candidate counts.\n" +
                    "16 fresh fixed seed domains x six classes x Lv20/labyrinth100 x OFF/ON x six raw active hours = 384 rows.\n" +
                    "OFF disables incidents/relationships/traits. ON starts with no owned traits and uses a received 12-person same-level fixture roster.\n" +
                    "Source availability hours include only the active time with an unexpired level-plus-or-minus-one candidate.\n" +
                    "Held time, eligible source count, passed rolls and actual-effect counters are separate trait denominators.\n" +
                    "Cash balance reconciles exactly. Ending bag value is a baseline CHA-adjusted liquidation estimate, not earned cash or a predicted sale trait.\n" +
                    "Bag-in items may be replaced old equipment; ACQUIRE records use actual candidate receipts separately.\n" +
                    "Item/sale/buy ledger is gzip-compressed UTF-8 CSV to bound artifact size. Sales use actual removed items, never scheduled offers.\n" +
                    "Per-seed class rows share a seed domain. Ratios with small denominators are not population effects.\n" +
                    "Rows completed=${rows.size}/384; trait rows=${traitRows.size}/${384 * AdventureTraitCatalog.all.size}; seeds=${AdventureQaFixtures.finalSeeds.joinToString(";")}.\n")
        }
        assertEquals(384, rows.size)
        assertEquals(384 * AdventureTraitCatalog.all.size, traitRows.size)
        val enabledRows = rows.filter { it["mode"] == "ON" }
        val activeHours = enabledRows.sumOf { (it.getValue("active_hours") as Number).toDouble() }
        val completedEvents = enabledRows.sumOf { (it.getValue("events") as Number).toLong() }
        val eventEquipment = enabledRows.sumOf { (it.getValue("event_equipment") as Number).toLong() }
        val eventBattles = enabledRows.sumOf { (it.getValue("event_battles") as Number).toLong() }
        val eliteEventBattles = enabledRows.sumOf { (it.getValue("event_elites") as Number).toLong() }
        val bossEventBattles = enabledRows.sumOf { (it.getValue("event_bosses") as Number).toLong() }
        val battleGoldRewards = enabledRows.sumOf { (it.getValue("event_battle_gold") as Number).toLong() }
        val battleEquipmentRewards = enabledRows.sumOf { (it.getValue("event_battle_equipment") as Number).toLong() }
        val selectedRewards = enabledRows.sumOf { row ->
            listOf("event_reward_experience", "event_reward_gold", "event_reward_item", "event_reward_route")
                .sumOf { key -> (row.getValue(key) as Number).toLong() }
        }
        val unspecifiedRewards = enabledRows.sumOf {
            (it.getValue("event_reward_unspecified") as Number).toLong()
        }
        val eventsPerHour = completedEvents / activeHours
        val hoursPerEventEquipment = activeHours / eventEquipment
        assertEquals("Every generated event stores one selected reward kind", completedEvents, selectedRewards)
        assertEquals("No new event may keep the legacy reward marker", 0L, unspecifiedRewards)
        assertTrue("Events should remain occasional: $eventsPerHour/hour", eventsPerHour in 1.9..3.0)
        assertTrue(
            "Event equipment should stay near the twelve-hour target: $hoursPerEventEquipment hours/item",
            hoursPerEventEquipment in 10.5..13.5,
        )
        assertTrue("The held-out cohort must exercise elite event battles", eliteEventBattles > 0L)
        assertTrue("The held-out cohort must exercise boss event battles", bossEventBattles > 0L)
        assertEquals("Each settled event battle has exactly one gold or equipment reward", eventBattles,
            battleGoldRewards + battleEquipmentRewards)
    }

    private fun measure(
        scenario: Scenario, heroClass: com.nullplaying.model.HeroClass, seed: Long,
        enabled: Boolean, ledger: BufferedWriter,
    ) {
        val engine = SimpleGameEngine(enableAdventureEvents = enabled,
            enableAdventureRelationships = enabled, enableAdventureTraits = enabled)
        val game = AdventureQaFixtures.game(engine, heroClass, seed, scenario.level, scenario.depth)
        if (enabled) game.adventureRelationships.roster = AdventureQaFixtures.roster(game)
        val mode = if (enabled) "ON" else "OFF"
        val key = listOf<Any>(scenario.id, heroClass.name, seed, mode)
        val end = AdventureQaFixtures.EPOCH + 6L * AdventureQaFixtures.HOUR
        val startPower = engine.displayCombatPower(game)
        val startGold = game.hero.gold
        val startEquipmentValue = equipmentValue(engine, game)
        var xp = 0L
        var saleGold = 0L
        var saleBaseGold = 0L
        var eventGold = 0L
        var relationshipGold = 0L
        var otherGold = 0L
        var purchaseGold = 0L
        var bagInputValue = 0L
        var bagInputItems = 0L
        var acquiredEquipment = 0L
        var eventEquipment = 0L
        var acquiredValue = 0L
        var progress = 0L
        var saleBatches = 0L
        var sourceValidMillis = 0L
        var steps = 0L
        var extraShopPurchases = 0L
        var allRecentEventCount = 0L
        var allTraitChangeCount = 0L
        val recentUiEvents = java.util.ArrayDeque<RecentAdventureEvent>()
        val heldMillis = mutableMapOf<String, Long>()
        val firstAcquired = mutableMapOf<String, Long>()
        val acquiredCounts = mutableMapOf<String, Long>()
        val lostCounts = mutableMapOf<String, Long>()
        val weakenedCounts = mutableMapOf<String, Long>()
        val recoveredCounts = mutableMapOf<String, Long>()
        var lastChange = 0L
        var lastActivation = 0L
        var lastRewardTrace = 0L
        val rewardSources = mutableMapOf<String, Long>()
        val effectRecords = mutableMapOf<String, Long>()

        fun recordHeldThrough(at: Long) {
            val elapsed = (at - game.lastSettledAt).coerceAtLeast(0L)
            game.adventureTraits.owned.forEach { heldMillis[it.traitId] = (heldMillis[it.traitId] ?: 0L) + elapsed }
            sourceValidMillis += AdventureQaFixtures.sourceValidTimeThrough(game, at)
        }
        while (game.actionEndsAt <= end) {
            val at = game.actionEndsAt
            recordHeldThrough(at)
            val beforeGold = game.hero.gold
            val beforeLevel = game.hero.level
            val beforeXp = game.hero.experience
            val beforeSaleGold = game.totalSaleGold
            val beforeSold = game.totalItemsSold
            val beforePurchases = game.totalEquipmentPurchases
            val beforeReturns = game.totalReturns
            val beforeShopExtra = game.adventureTraits.shopVisit?.pendingIsExtra == true
            val beforeItems = game.totalItemsFound
            val beforeEventGold = game.adventureJourney.totalGold
            val beforeRelationshipGold = game.adventureRelationships.totalGold
            val beforeInventory = game.inventory.toList()
            val beforePhase = game.adventurePhase
            val beforeSource = game.adventureTraits.source?.key ?: "${beforePhase.name}:${game.actionSequence}"
            val beforeAct = game.adventureTale.activeAct()
            val beforeActsCompleted = game.totalActs
            val beforeActGold = beforeAct.rewardGold
            val beforeActKey = "${game.adventureTale.sequence}:${game.adventureTale.definitionId}:${game.adventureTale.currentActIndex}:${beforeAct.id}"
            val beforeProgress = beforeAct.progress
            val beforeTarget = beforeAct.target
            val settled = engine.settleOffline(game, at)
            settled.recentEvents.forEach { event ->
                allRecentEventCount++
                if (event.type == RecentAdventureEventType.ADVENTURE_TRAIT_CHANGED) allTraitChangeCount++
                recentUiEvents.addLast(event)
                if (recentUiEvents.size > 300) recentUiEvents.removeFirst()
            }
            assertTrue("$key: action clock advances", game.actionEndsAt > at)
            assertTrue("$key: bounded action loop", ++steps < 500_000L)
            assertEquals(at, game.lastSettledAt)
            assertTrue(game.hero.gold >= 0L)

            xp += game.hero.experience - beforeXp
            for (level in beforeLevel until game.hero.level) xp += engine.experienceRequired(level)
            val soldCount = game.totalItemsSold - beforeSold
            val bought = game.totalEquipmentPurchases - beforePurchases
            assertTrue(soldCount in 0L..1L)
            assertTrue(bought in 0L..1L)
            val paid = if (bought == 1L) requireNotNull(game.lastShopPurchase).price else 0L
            val actualSale = game.totalSaleGold - beforeSaleGold
            val directEvent = game.adventureJourney.totalGold - beforeEventGold
            val directRelationship = game.adventureRelationships.totalGold - beforeRelationshipGold
            val completedActs = game.totalActs - beforeActsCompleted
            assertTrue(completedActs in 0L..1L)
            val directOther = if (completedActs == 1L) beforeActGold else 0L
            saleGold += actualSale
            eventGold += directEvent
            relationshipGold += directRelationship
            otherGold += directOther
            purchaseGold += paid
            assertEquals(
                "$key: cash conservation at $at",
                beforeGold + actualSale + directEvent + directRelationship + directOther - paid,
                game.hero.gold,
            )

            val afterIds = game.inventory.map { it.id }.toSet()
            val soldItems = beforeInventory.filter { it.id !in afterIds }
            assertEquals("$key: sold records equal physical bag removals", soldCount, soldItems.size.toLong())
            soldItems.forEach { item ->
                val base = AdventureQaFixtures.baselineSaleValue(engine, game, item)
                saleBaseGold += base
                ledgerRow(ledger, key, at, "SALE", beforeSource, item, base, actualSale)
            }
            if (soldCount > 0L && beforePhase != AdventurePhase.SELLING) saleBatches += 1L
            if (bought == 1L) {
                if (beforeShopExtra) extraShopPurchases++
                val offer = requireNotNull(game.lastShopPurchase)
                val item = InventoryItem(0L, offer.name, offer.rarity, "장비", game.hero.level, offer.slot, offer.newPower)
                ledgerRow(ledger, key, at, "BUY", "$beforeSource|${if (beforeShopExtra) "TRAIT_SHOP_EXTRA" else "PRIMARY"}",
                    item, AdventureQaFixtures.baselineSaleValue(engine, game, item), -paid)
                assertTrue("Actual purchase upgrades the reviewed item", offer.newPower > offer.previousPower)
            }
            if (directEvent != 0L) ledgerRow(ledger, key, at, "DIRECT_EVENT_GOLD", beforeSource, null, 0L, directEvent)
            if (directRelationship != 0L) ledgerRow(
                ledger, key, at, "DIRECT_RELATIONSHIP_GOLD", beforeSource, null, 0L, directRelationship,
            )
            if (directOther != 0L) ledgerRow(ledger, key, at, "OTHER_GOLD", beforeSource, null, 0L, directOther)
            val returned = game.totalReturns - beforeReturns
            assertTrue(returned in 0L..1L)
            if (returned == 1L) ledgerRow(ledger, key, at, "RETURN", beforeSource, null,
                game.inventory.sumOf { AdventureQaFixtures.baselineSaleValue(engine, game, it) }, 0L,
                "items=${game.inventory.size};capacity=${game.inventoryCapacity()};duration=${game.actionEndsAt - at}")

            val bagInputs = game.inventory.filter { it.id > beforeItems }
            assertEquals("$key: every successful item adds one physical bag item or displaced equipment",
                game.totalItemsFound - beforeItems, bagInputs.size.toLong())
            bagInputs.forEach { item ->
                val base = AdventureQaFixtures.baselineSaleValue(engine, game, item)
                bagInputItems += 1L
                bagInputValue += base
                ledgerRow(ledger, key, at, "BAG_IN", beforeSource, item, base, 0L)
            }
            // OFF has exactly one original loot per acquisition boundary. ON uses per-item traces.
            if (!enabled && game.totalItemsFound > beforeItems) {
                assertEquals(1L, game.totalItemsFound - beforeItems)
                val item = InventoryItem(game.totalItemsFound, game.lastLootName, game.lastLootRarity, game.lastLootKind,
                    game.hero.level, game.lastLootEquipmentSlot, game.lastLootEquipmentPower)
                val value = AdventureQaFixtures.baselineSaleValue(engine, game, item)
                acquiredValue += value
                if (item.kind == "장비") acquiredEquipment += 1L
                rewardSources["COMBAT_PRIMARY"] = (rewardSources["COMBAT_PRIMARY"] ?: 0L) + 1L
                ledgerRow(ledger, key, at, "ACQUIRE", "COMBAT_PRIMARY", item, value, 0L,
                    originalPower = item.equipmentPower, equipped = game.lastLootEquipped)
            }
            if (enabled) {
                val traces = game.adventureTraits.recentRewardTraces.filter { it.sequence > lastRewardTrace }
                traces.forEach { trace ->
                    lastRewardTrace = max(lastRewardTrace, trace.sequence)
                    val item = InventoryItem(trace.itemId, trace.name, trace.rarity,
                        if (trace.slot != null) "장비" else "전리품", trace.foundAtLevel, trace.slot, trace.finalPower)
                    val value = AdventureQaFixtures.baselineSaleValue(engine, game, item)
                    val category = "${trace.sourceKey.substringBefore(':').uppercase()}_${trace.origin}"
                    if (trace.actualGranted) {
                        acquiredValue += value
                        if (trace.slot != null) {
                            acquiredEquipment += 1L
                            if (trace.sourceKey.startsWith("event:") || trace.origin == "EVENT_BATTLE") {
                                eventEquipment += 1L
                            }
                        }
                        rewardSources[category] = (rewardSources[category] ?: 0L) + 1L
                    }
                    ledgerRow(ledger, key, at, if (trace.actualGranted) "ACQUIRE" else "REWARD_NOT_GRANTED",
                        "${trace.sourceKey}|${trace.origin}", item, value, 0L,
                        originalPower = trace.originalPower, equipped = trace.equipped)
                }
                assertEquals("$key: receipts account for each real item", game.totalItemsFound - beforeItems,
                    traces.count { it.actualGranted }.toLong())
            }

            val afterAct = game.adventureTale.activeAct()
            val afterActKey = "${game.adventureTale.sequence}:${game.adventureTale.definitionId}:${game.adventureTale.currentActIndex}:${afterAct.id}"
            progress += if (beforeActKey == afterActKey) max(0L, afterAct.progress - beforeProgress)
                else max(0L, beforeTarget - beforeProgress) + afterAct.progress
            game.adventureTraits.recentChanges.filter { it.sequence > lastChange }.forEach { change ->
                lastChange = max(lastChange, change.sequence)
                fun add(map: MutableMap<String, Long>, id: String) { map[id] = (map[id] ?: 0L) + 1L }
                when (change.kind) {
                    AdventureTraitChangeKind.ACQUIRED -> { add(acquiredCounts, change.traitId); firstAcquired.putIfAbsent(change.traitId, change.occurredAt) }
                    AdventureTraitChangeKind.REPLACED -> {
                        add(acquiredCounts, change.traitId); firstAcquired.putIfAbsent(change.traitId, change.occurredAt)
                        add(lostCounts, change.replacedTraitId)
                    }
                    AdventureTraitChangeKind.LOST -> add(lostCounts, change.traitId)
                    AdventureTraitChangeKind.WEAKENED -> add(weakenedCounts, change.traitId)
                    AdventureTraitChangeKind.RECOVERED -> add(recoveredCounts, change.traitId)
                }
                ledgerRow(ledger, key, at, "TRAIT_${change.kind}", change.sourceKey, null, 0L, 0L, change.traitId)
            }
            game.adventureTraits.recentActivations.filter { it.sequence > lastActivation }.forEach { activation ->
                lastActivation = max(lastActivation, activation.sequence)
                effectRecords[activation.traitId] = (effectRecords[activation.traitId] ?: 0L) + 1L
                ledgerRow(ledger, key, at, "TRAIT_EFFECT", activation.sourceKey, null,
                    activation.previousValue, activation.currentValue, "${activation.traitId}:${activation.effectKind}:${activation.timeAdjustmentMillis}")
            }
            assertNoOpposites(game)
        }
        recordHeldThrough(end)
        engine.settleOffline(game, end)
        assertEquals(end, game.lastSettledAt)
        assertEquals(startGold + saleGold + eventGold + relationshipGold + otherGold - purchaseGold, game.hero.gold)
        val bagValue = game.inventory.sumOf { AdventureQaFixtures.baselineSaleValue(engine, game, it) }
        val eventResults = game.adventureJourney.recentResults
        assertEquals(game.adventureJourney.completedEvents.toInt(), eventResults.size)
        val selectedRewardCounts = AdventureEventRewardKind.entries.associateWith { kind ->
            eventResults.count { it.run.rewardKind == kind }
        }
        val awardedRouteResults = eventResults.filter { it.run.routeDelayMillis < 0L }
        val remainingRouteShorteningMillis = -game.adventureJourney.routeRewardDelayQueueMillis
            .filter { it < 0L }
            .sum()
        val info = linkedMapOf<String, Any>(
            "scenario" to scenario.id, "class" to heroClass.name, "seed" to seed, "mode" to mode,
            "active_hours" to 6, "candidate_valid_hours" to sourceValidMillis.toDouble() / AdventureQaFixtures.HOUR,
            "start_level" to scenario.level, "end_level" to game.hero.level, "start_depth" to scenario.depth,
            "end_depth" to game.adventureTale.labyrinthDepth, "xp" to xp, "xp_per_hour" to xp / 6.0,
            "items" to game.totalItemsFound, "items_per_hour" to game.totalItemsFound / 6.0,
            "equipment" to acquiredEquipment, "equipment_per_hour" to acquiredEquipment / 6.0,
            "event_equipment" to eventEquipment,
            "acquired_candidate_base_value" to acquiredValue, "bag_input_items" to bagInputItems,
            "bag_input_base_value" to bagInputValue, "sale_items" to game.totalItemsSold,
            "sale_batches" to saleBatches, "sale_base_gold" to saleBaseGold, "sale_actual_gold" to saleGold,
            "sale_trait_gold_delta" to saleGold - saleBaseGold, "direct_event_gold" to eventGold,
            "direct_relationship_gold" to relationshipGold,
            "other_gold" to otherGold, "cash_earned" to saleGold + eventGold + relationshipGold + otherGold,
            "cash_earned_per_hour" to (saleGold + eventGold + relationshipGold + otherGold) / 6.0,
            "purchase_gold" to purchaseGold,
            "ending_gold" to game.hero.gold, "ending_bag_items" to game.inventory.size,
            "ending_bag_base_value" to bagValue,
            "earned_plus_ending_bag_value" to saleGold + eventGold + relationshipGold + otherGold + bagValue,
            "start_cp" to startPower, "end_cp" to engine.displayCombatPower(game),
            "equipment_value_change" to equipmentValue(engine, game) - startEquipmentValue,
            "loot_upgrades" to game.totalLootEquipmentEquips, "purchases" to game.totalEquipmentPurchases,
            "extra_shop_purchases" to extraShopPurchases,
            "returns" to game.totalReturns, "kills" to game.totalKills, "progress" to progress,
            "events" to game.adventureJourney.completedEvents, "relationships" to game.adventureRelationships.totalEncounters,
            "event_reward_experience" to selectedRewardCounts.getValue(AdventureEventRewardKind.EXPERIENCE),
            "event_reward_gold" to selectedRewardCounts.getValue(AdventureEventRewardKind.GOLD),
            "event_reward_item" to selectedRewardCounts.getValue(AdventureEventRewardKind.ITEM),
            "event_reward_route" to selectedRewardCounts.getValue(AdventureEventRewardKind.ROUTE),
            "event_reward_unspecified" to selectedRewardCounts.getValue(AdventureEventRewardKind.UNSPECIFIED),
            "event_battles" to eventResults.count { it.run.battleGrade != null && it.battleResolved },
            "event_elites" to eventResults.count {
                it.run.battleGrade == com.nullplaying.model.MonsterGrade.ELITE && it.battleResolved
            },
            "event_bosses" to eventResults.count {
                it.run.battleGrade == com.nullplaying.model.MonsterGrade.BOSS && it.battleResolved
            },
            "event_battle_gold" to eventResults.count {
                it.run.battleGrade != null && it.battleResolved &&
                    it.run.battleRewardKind == com.nullplaying.model.AdventureEventBattleRewardKind.GOLD
            },
            "event_battle_equipment" to eventResults.count {
                it.run.battleGrade != null && it.battleResolved &&
                    it.run.battleRewardKind == com.nullplaying.model.AdventureEventBattleRewardKind.EQUIPMENT
            },
            "event_failures" to eventResults.count { it.run.outcome == AdventureEventOutcome.FAILURE },
            "route_reward_results_awarded" to awardedRouteResults.size,
            "route_reward_uses_awarded" to awardedRouteResults.sumOf { it.run.routeRewardUses.toLong() },
            "route_shortening_millis_awarded" to awardedRouteResults.sumOf {
                -it.run.routeDelayMillis * it.run.routeRewardUses.toLong()
            },
            "route_reward_uses_remaining" to game.adventureJourney.routeRewardDelayQueueMillis.size,
            "route_shortening_millis_remaining" to remainingRouteShorteningMillis,
            "route_reward_uses_2" to awardedRouteResults.count { it.run.routeRewardUses == 2 },
            "route_reward_uses_3" to awardedRouteResults.count { it.run.routeRewardUses == 3 },
            "route_reward_uses_4" to awardedRouteResults.count { it.run.routeRewardUses == 4 },
            "route_reward_uses_5" to awardedRouteResults.count { it.run.routeRewardUses == 5 },
            "route_reward_uses_6" to awardedRouteResults.count { it.run.routeRewardUses == 6 },
            "route_reward_uses_7" to awardedRouteResults.count { it.run.routeRewardUses == 7 },
            "route_reward_uses_8" to awardedRouteResults.count { it.run.routeRewardUses == 8 },
            "route_reward_uses_9" to awardedRouteResults.count { it.run.routeRewardUses == 9 },
            "route_reward_uses_10" to awardedRouteResults.count { it.run.routeRewardUses == 10 },
            "trait_acquisitions" to acquiredCounts.values.sum(), "trait_losses" to lostCounts.values.sum(),
            "formation_opportunities" to (game.adventureTraits.opportunityCounts["FORMATION"] ?: 0L),
            "formation_passed_rolls" to (game.adventureTraits.procCounts["FORMATION"] ?: 0L),
            "owned_traits_at_end" to game.adventureTraits.owned.size, "action_steps" to steps,
            "all_recent_events" to allRecentEventCount, "all_trait_changes" to allTraitChangeCount,
            "recent_300_count" to recentUiEvents.size,
            "recent_300_trait_changes" to recentUiEvents.count { it.type == RecentAdventureEventType.ADVENTURE_TRAIT_CHANGED },
            "recent_300_trait_activations" to recentUiEvents.count { it.type == RecentAdventureEventType.ADVENTURE_TRAIT_ACTIVATED },
            "reward_sources" to rewardSources.toSortedMap().entries.joinToString(";") { "${it.key}:${it.value}" },
        )
        rows += info
        AdventureTraitCatalog.all.forEach { trait ->
            val id = trait.id
            val held = game.adventureTraits.owned.any { it.traitId == id }
            val acquired = acquiredCounts[id] ?: 0L
            val lost = lostCounts[id] ?: 0L
            assertEquals("$key/$id: lifecycle conservation", if (held) 1L else 0L, acquired - lost)
            traitRows += linkedMapOf("scenario" to scenario.id, "class" to heroClass.name, "seed" to seed, "mode" to mode,
                "trait" to id, "held_hours" to (heldMillis[id] ?: 0L).toDouble() / AdventureQaFixtures.HOUR,
                "opportunities" to (game.adventureTraits.opportunityCounts[id] ?: 0L),
                "passed_rolls" to (game.adventureTraits.procCounts[id] ?: 0L),
                "actual_effects" to (game.adventureTraits.actualEffectCounts[id] ?: 0L),
                "effect_records" to (effectRecords[id] ?: 0L), "acquired" to acquired, "lost_or_replaced" to lost,
                "weakened" to (weakenedCounts[id] ?: 0L), "recovered" to (recoveredCounts[id] ?: 0L),
                "first_acquired_hour" to (firstAcquired[id]?.let { (it - AdventureQaFixtures.EPOCH).toDouble() / AdventureQaFixtures.HOUR } ?: ""),
                "owned_at_end" to held, "remaining_evidence" to game.adventureTraits.evidence[id].orEmpty().size)
        }
    }

    private fun assertNoOpposites(game: SimpleGameState) {
        val owned = game.adventureTraits.owned.map { it.traitId }.toSet()
        assertEquals(owned.size, game.adventureTraits.owned.size)
        AdventureTraitCatalog.all.forEach { trait ->
            if (trait.id in owned && trait.oppositeId.isNotBlank()) assertTrue(trait.oppositeId !in owned)
        }
    }

    private fun equipmentValue(engine: SimpleGameEngine, game: SimpleGameState): Long = game.equipment.sumOf {
        AdventureQaFixtures.baselineSaleValue(engine, game,
            InventoryItem(0L, it.name, it.rarity, "장비", it.acquiredAtLevel, it.slot, it.power))
    }

    private fun ledgerRow(
        writer: BufferedWriter, key: List<Any>, at: Long, operation: String, source: String,
        item: InventoryItem?, base: Long, actual: Long, label: String = item?.name.orEmpty(),
        originalPower: Long? = null, equipped: Boolean? = null,
    ) {
        writer.appendLine((key + listOf(at, operation, source, item?.id ?: "", item?.foundAtLevel ?: "",
            item?.rarity ?: "", item?.equipmentSlot?.name ?: "", item?.equipmentPower ?: "",
            originalPower ?: "", equipped ?: "", base, actual, label))
            .joinToString(",", transform = ::cell))
    }

    private fun writeTable(writer: BufferedWriter, records: List<Map<String, Any>>) = writer.use { output ->
        if (records.isNotEmpty()) {
            val columns = records.first().keys.toList()
            output.appendLine(columns.joinToString(","))
            records.forEach { row -> output.appendLine(columns.joinToString(",") { cell(row.getValue(it)) }) }
        }
    }

    private fun cell(value: Any): String {
        val text = if (value is Double) String.format(Locale.ROOT, "%.8f", value) else value.toString()
        return if (text.any { it == ',' || it == '"' || it == '\n' }) "\"${text.replace("\"", "\"\"")}\"" else text
    }

    companion object {
        private val SCENARIOS = listOf(Scenario("level_20", 20L), Scenario("labyrinth_100", 100L, 100L))
    }
}
