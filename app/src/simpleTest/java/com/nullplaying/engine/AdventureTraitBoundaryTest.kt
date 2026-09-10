package com.nullplaying.engine

import com.nullplaying.model.AdventureOwnedTrait
import com.nullplaying.model.AdventureEventOutcome
import com.nullplaying.model.AdventureEventRewardKind
import com.nullplaying.model.AdventureEventItemReward
import com.nullplaying.model.AdventurePhase
import com.nullplaying.model.AdventureTraitShopVisit
import com.nullplaying.model.AdventureTraitState
import com.nullplaying.model.AdventureTraitChangeKind
import com.nullplaying.model.AdventureTraitEvidence
import com.nullplaying.model.AdventureTraitEffectKind
import com.nullplaying.model.AdventureTraitWeaponUse
import com.nullplaying.model.BattleGuidance
import com.nullplaying.model.EquipmentSlot
import com.nullplaying.model.HeroClass
import com.nullplaying.model.HeroStats
import com.nullplaying.model.MonsterGrade
import com.nullplaying.model.RecentAdventureEventType
import com.nullplaying.model.InventoryItem
import com.nullplaying.model.ShopEquipmentOffer
import com.nullplaying.model.SimpleGameState
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Independent integration boundaries: adventure effects never become arena inputs. */
class AdventureTraitBoundaryTest {
    private val engine = SimpleGameEngine()
    private val json = Json { encodeDefaults = true }

    @Test
    fun `owned adventure traits and temporary journey capacity do not change shared combat calculations`() {
        HeroClass.entries.forEach { heroClass ->
            val baseline = newGame(heroClass)
            val withTraits = copy(baseline).apply {
                adventureTraits = AdventureTraitState(
                    initialized = true,
                    seed = 847_119L,
                    owned = independentOwnedTraits(),
                    temporaryBagSlots = 3L,
                )
            }
            assertEquals(baseline.inventoryCapacity() + 3L, withTraits.inventoryCapacity())
            assertEquals(engine.displayCombatPower(baseline), engine.displayCombatPower(withTraits))
            assertEquals(engine.characterStatPower(baseline), engine.characterStatPower(withTraits))
            assertEquals(engine.expectedBasicAttackDamage(baseline), engine.expectedBasicAttackDamage(withTraits))
            assertEquals(engine.expectedAttackDamage(baseline), engine.expectedAttackDamage(withTraits))
            assertEquals(engine.monsterEnergyFor(baseline, 12), engine.monsterEnergyFor(withTraits, 12))
            assertEquals(engine.skillProcBasisPoints(baseline), engine.skillProcBasisPoints(withTraits))
            assertEquals(engine.combatDurationPercent(baseline), engine.combatDurationPercent(withTraits))
            baseline.skills.forEach { skill ->
                assertEquals(engine.skillPreviewDamage(baseline, skill.catalogId),
                    engine.skillPreviewDamage(withTraits, skill.catalogId))
            }
        }
    }

    @Test
    fun `arena request is byte equivalent when only adventure traits differ`() {
        HeroClass.entries.forEach { heroClass ->
            val baseline = newGame(heroClass)
            val withTraits = copy(baseline).apply {
                adventureTraits = AdventureTraitState(
                    initialized = true,
                    seed = 983_741L,
                    owned = independentOwnedTraits(),
                    temporaryBagSlots = 3L,
                    opportunityCounts = mapOf("L01" to 100_000L),
                    procCounts = mapOf("L01" to 99L),
                )
            }
            fun request(state: SimpleGameState) = BattleQaMatchFactory.createMatch(
                state = state,
                combatPower = engine.displayCombatPower(state),
                guidance = BattleGuidance.BALANCED,
                userScore = 1_000,
                matchSequence = 7,
                battleId = "local-adventure-isolation",
                serverSeed = 102_931L,
                requestedAtMillis = 1_000L,
            ).request
            assertEquals(json.encodeToString(request(baseline)), json.encodeToString(request(withTraits)))
            assertEquals(baseline.battleTraits, withTraits.battleTraits)
            assertEquals(baseline.heroPath, withTraits.heroPath)
        }
    }

    @Test
    fun `extra weapon settlement rejects stale free negative or mismatched price after restore`() {
        val enabled = SimpleGameEngine(enableAdventureTraits = true)
        val expectedPrice = engine.equipmentPrice(20L, EquipmentSlot.WEAPON)
        listOf(-1L, 0L, 1L, expectedPrice - 10L, expectedPrice + 10L, Long.MAX_VALUE).forEach { price ->
            val game = pendingExtraWeapon(price)
            val restored = copy(game)
            val gold = restored.hero.gold
            val equipment = restored.equipment.map { it.copy() }
            enabled.settleOffline(restored, restored.actionEndsAt)
            assertEquals("price=$price", gold, restored.hero.gold)
            assertEquals("price=$price", 0L, restored.totalEquipmentPurchases)
            assertEquals("price=$price", equipment, restored.equipment)
            assertTrue(restored.adventureTraits.shopVisit?.extraUsed != false)
        }
    }

    @Test
    fun `extra weapon honors current funds power and acquisition rarity protection`() {
        val enabled = SimpleGameEngine(enableAdventureTraits = true)
        listOf("funds", "same-power", "changed-current", "protected-rarity", "offer-rarity").forEach { reason ->
            val game = pendingExtraWeapon(engine.equipmentPrice(20L, EquipmentSlot.WEAPON))
            val weapon = game.equipment.first { it.slot == EquipmentSlot.WEAPON }
            when (reason) {
                "funds" -> game.hero.gold = requireNotNull(game.pendingShopOffer).price - 1L
                "same-power" -> game.pendingShopOffer = requireNotNull(game.pendingShopOffer).copy(newPower = weapon.power)
                "changed-current" -> weapon.power += 1L
                "protected-rarity" -> { weapon.rarity = "전설"; weapon.acquiredAtLevel = game.hero.level }
                "offer-rarity" -> game.pendingShopOffer = requireNotNull(game.pendingShopOffer).copy(rarity = "신화")
            }
            val gold = game.hero.gold
            val equipment = game.equipment.map { it.copy() }
            enabled.settleOffline(game, game.actionEndsAt)
            assertEquals(reason, gold, game.hero.gold)
            assertEquals(reason, 0L, game.totalEquipmentPurchases)
            assertEquals(reason, equipment, game.equipment)
        }
    }

    @Test
    fun `valid paid extra weapon is purchased once across result restart`() {
        val enabled = SimpleGameEngine(enableAdventureTraits = true)
        val price = engine.equipmentPrice(20L, EquipmentSlot.WEAPON)
        val game = pendingExtraWeapon(price).apply { hero.gold = price }
        val offer = requireNotNull(game.pendingShopOffer)
        enabled.settleOffline(game, game.actionEndsAt)
        assertEquals(AdventurePhase.SHOPPING_RESULT, game.adventurePhase)
        assertEquals(0L, game.hero.gold)
        assertEquals(1L, game.totalEquipmentPurchases)
        assertEquals(offer.newPower, game.equipment.first { it.slot == EquipmentSlot.WEAPON }.power)
        val restored = copy(game)
        enabled.settleOffline(restored, restored.actionEndsAt)
        assertEquals(0L, restored.hero.gold)
        assertEquals(1L, restored.totalEquipmentPurchases)
        assertFalse(restored.adventureTraits.shopVisit?.extraUsed == false)
    }

    @Test
    fun `quick sale subtracts floor of the batch discount rather than flooring the remainder`() {
        listOf(listOf(10L, 19L, 10L), listOf(1L), listOf(11L, 12L, 17L), listOf(21L, 18L, 22L)).forEach { values ->
            val game = newGame().apply {
                adventureTraits = AdventureTraitState(initialized = true,
                    seed = seedPassing("S02:town:1:sale", 300),
                    owned = listOf(AdventureOwnedTrait("S02")))
            }
            AdventureTraitEngine.beginSource(game, "town", "town", 1L)
            val batch = AdventureTraitEngine.beginSale(game, values.mapIndexed { i, value -> i.toLong() to value }, 1L)
            val baseTotal = values.sum()
            assertEquals("values=$values", "S02", batch.traitId)
            assertEquals("values=$values", baseTotal - baseTotal / 20L, batch.paidValues.sum())
            assertTrue(batch.paidValues.all { it >= 0L })
            assertEquals(1L, game.adventureTraits.opportunityCounts["S02"])
        }
    }

    @Test
    fun `natural loss and opposite formation share one atomic replacement record`() {
        val source = "local-opposite-transition"
        val game = newGame().apply {
            adventureTraits = AdventureTraitState(initialized = true,
                seed = seedPassing("FORMATION:$source", 2_500),
                owned = listOf(AdventureOwnedTrait("C03", 1L, 1L, shaky = true)),
                formationStartedAtByTrait = mapOf("C04" to 0L),
                stableStartedAtByTrait = mapOf("C03" to 0L),
                oppositionStartedAtByTrait = mapOf("C03" to 0L),
                weakenedStartedAtByTrait = mapOf("C03" to 0L),
                evidence = mapOf(
                    "C03" to (1..9).map { AdventureTraitEvidence("opposition:$it", "combat:${it % 3}", false, "combat:style") },
                    "C04" to (1..7).map { AdventureTraitEvidence("support:$it", "combat:${it % 3}", true, "combat:style") }))
        }
        val transitionAt = AdventureTraitEngine.SHAKY_LOSS_MIN_ACTIVE_MILLIS
        AdventureTraitEngine.observe(game, source, "combat:0", setOf("C04"), setOf("C03"), transitionAt, "combat:style")
        AdventureTraitEngine.finalizeEvidence(game, transitionAt)
        assertEquals(listOf("C04"), game.adventureTraits.owned.map { it.traitId })
        val record = game.adventureTraits.recentChanges.single()
        assertEquals(AdventureTraitChangeKind.REPLACED, record.kind)
        assertEquals("C03", record.replacedTraitId)
        assertEquals("C04", record.traitId)
        val restored = copy(game)
        assertEquals(game.adventureTraits, restored.adventureTraits)
    }

    @Test
    fun `unrelated combat evidence cannot evict or duplicate rare weapon evidence`() {
        val game = newGame().apply { adventureTraits = AdventureTraitState(initialized = true, seed = 901L) }
        AdventureTraitEngine.observe(game, "rare-weapon-source", "equipment:WEAPON", setOf("S05"), at = 1L)
        AdventureTraitEngine.finalizeEvidence(game, 1L)
        val before = game.adventureTraits.evidence["S05"]
        repeat(1_000) { i ->
            AdventureTraitEngine.observe(game, "unrelated:$i", "combat:${i % 3}", emptySet(), setOf("C03"), i.toLong() + 2L)
            AdventureTraitEngine.finalizeEvidence(game, i.toLong() + 2L)
        }
        assertEquals(before, game.adventureTraits.evidence["S05"])
        AdventureTraitEngine.observe(game, "rare-weapon-source", "equipment:WEAPON", setOf("S05"), at = 1_003L)
        AdventureTraitEngine.finalizeEvidence(game, 1_003L)
        assertEquals(before, game.adventureTraits.evidence["S05"])
    }

    @Test
    fun `several newly qualified traits in one combat share one formation roll`() {
        val game = newGame().apply {
            adventureTraits = AdventureTraitState(initialized = true,
                seed = seedPassing("FORMATION:combat:1", 2_500), evidence = listOf("C03", "L05").associateWith { id ->
                    (1..7).map { AdventureTraitEvidence("$id:old:$it", "context:${it % 3}", true, "combat:base") }
                }, formationStartedAtByTrait = mapOf("C03" to 0L, "L05" to 0L))
        }
        val formedAt = AdventureTraitEngine.FORMATION_MIN_ACTIVE_MILLIS
        val source = AdventureTraitEngine.beginSource(game, "combat", "monster:local", formedAt)
        AdventureTraitEngine.observe(game, source.key + ":style", "context:0", setOf("C03"), at = formedAt)
        AdventureTraitEngine.observe(game, source.key + ":equipment", "context:1", setOf("L05"), at = formedAt)
        AdventureTraitEngine.finalizeEvidence(game, formedAt)
        assertEquals(1L, game.adventureTraits.opportunityCounts["FORMATION"])
        assertEquals(1, game.adventureTraits.owned.size)
        val after = copy(game)
        AdventureTraitEngine.finalizeEvidence(game, formedAt)
        assertEquals(after, game)
        val restored = copy(game)
        AdventureTraitEngine.observe(restored, source.key + ":style", "context:0", setOf("C03"), at = formedAt)
        AdventureTraitEngine.finalizeEvidence(restored, formedAt)
        assertEquals(after, restored)
    }

    @Test
    fun `one hundred actual proc records without base experiences never form or remove traits`() {
        val game = newGame().apply {
            adventureTraits = AdventureTraitState(initialized = true, seed = 901L, owned = independentOwnedTraits())
        }
        val beforeOwned = game.adventureTraits.owned
        repeat(100) { index ->
            AdventureTraitEngine.activate(game, "G01", "local-derived:$index", index.toLong(),
                AdventureTraitEffectKind.EXPERIENCE, 100L, 105L)
        }
        AdventureTraitEngine.finalizeEvidence(game, 101L)
        assertEquals(beforeOwned, game.adventureTraits.owned)
        assertTrue(game.adventureTraits.evidence.isEmpty())
        assertTrue(game.adventureTraits.recentChanges.isEmpty())
        assertFalse(game.adventureTraits.opportunityCounts.containsKey("FORMATION"))
    }

    @Test
    fun `restoration with no elapsed or charged time normalizes opposites before use`() {
        val enabled = SimpleGameEngine(enableAdventureTraits = true)
        listOf(0L, -1L).forEach { now ->
            val game = newGame().apply {
                offlineAdventureMillis = 0L
                adventureTraits = AdventureTraitState(initialized = true, seed = 991L,
                    owned = independentOwnedTraits() + listOf(
                        AdventureOwnedTrait("C04", 2L, 20L),
                        AdventureOwnedTrait("L02", 2L, 21L),
                        AdventureOwnedTrait("S02", 2L, 22L),
                        AdventureOwnedTrait("R06", 2L, 23L),
                        AdventureOwnedTrait("unknown-local-id", 2L, 99L)))
            }
            val restored = copy(game)
            enabled.settleOfflineWithOfflineAdventure(restored, now)
            val ids = restored.adventureTraits.owned.map { it.traitId }
            assertEquals(9, ids.size)
            assertTrue(ids.containsAll(listOf("C04", "L02", "S02", "R06", "L04", "L05", "S05", "E03", "G01")))
            assertFalse(ids.any { it in setOf("C03", "L01", "S01", "R05", "unknown-local-id") })
        }
    }

    @Test
    fun `disabled adventure traits cannot retain temporary capacity from a preview save`() {
        val baseline = newGame()
        val saved = copy(baseline).apply {
            offlineAdventureMillis = 0L
            adventureTraits = AdventureTraitState(initialized = true, seed = 11L,
                owned = independentOwnedTraits(), temporaryBagSlots = 3L)
        }
        val restored = copy(saved)
        engine.settleOfflineWithOfflineAdventure(restored, 0L)
        assertEquals(baseline.inventoryCapacity(), restored.inventoryCapacity())
        assertEquals(saved.adventureTraits.owned, restored.adventureTraits.owned)
    }

    @Test
    fun `successful speech does not create a needless retry opportunity or investigation delay`() {
        val game = dialogueGame("R05").apply {
            adventureTraits.seed = (1L..100_000L).first { seed ->
                AdventureTraitEngine.random(seed, "R05:event:1:dialogue") < 300 &&
                    AdventureTraitEngine.random(seed, "event:1:decisive", 2) == 0
            }
        }
        val base = dialogueBase(game, AdventureEventOutcome.PARTIAL)
        val decisionRng = game.adventureJourney.rngState
        val mainRng = game.rngState
        val result = AdventureTraitEngine.planEvent(game, base)
        assertEquals(AdventureEventOutcome.SUCCESS, result.outcome)
        assertEquals(base.durationMillis, result.durationMillis)
        assertEquals(base.baseExperienceBudget * 120L / 100L, result.experienceReward)
        assertEquals(0L, result.goldReward)
        assertEquals(AdventureEventItemReward.NONE, result.itemReward)
        assertEquals(0L, result.routeDelayMillis)
        assertFalse(game.adventureTraits.opportunityCounts.containsKey("E03"))
        assertEquals(null, game.adventureTraits.source?.retryRun)
        assertEquals(decisionRng, game.adventureJourney.rngState)
        assertEquals(mainRng, game.rngState)
    }

    @Test
    fun `gentle speech downgrading base success cannot generate a derived retry opportunity`() {
        val game = dialogueGame("R06").apply { adventureTraits.seed = seedPassing("R06:event:1:dialogue", 300) }
        val base = dialogueBase(game, AdventureEventOutcome.SUCCESS)
        val result = AdventureTraitEngine.planEvent(game, base)
        assertEquals(AdventureEventOutcome.PARTIAL, result.outcome)
        assertEquals(base.baseExperienceBudget, result.experienceReward)
        assertEquals(0L, result.goldReward)
        assertEquals(AdventureEventItemReward.NONE, result.itemReward)
        assertEquals(0L, result.routeDelayMillis)
        assertEquals(base.durationMillis, result.durationMillis)
        assertFalse(game.adventureTraits.opportunityCounts.containsKey("E03"))
        assertEquals(null, game.adventureTraits.source?.retryRun)
    }

    @Test
    fun `weapon comparison requires three normal battles and never counts derived equipment use`() {
        val game = newGame().apply { adventureTraits = AdventureTraitState(initialized = true, seed = 81L) }
        val weapon = game.equipment.first { it.slot == EquipmentSlot.WEAPON }
        AdventureTraitEngine.observeEquipment(game, "original:weapon", EquipmentSlot.WEAPON, weapon.power, false, 1L)
        AdventureTraitEngine.finalizeEvidence(game, 1L)
        listOf(MonsterGrade.ELITE, MonsterGrade.BOSS, MonsterGrade.ELITE).forEachIndexed { index, grade ->
            game.monster.grade = grade
            AdventureTraitEngine.beginCombat(game, index.toLong() + 2L)
            AdventureTraitEngine.observeCombat(game, index.toLong() + 2L)
            AdventureTraitEngine.finalizeEvidence(game, index.toLong() + 2L)
        }
        assertEquals(3, game.adventureTraits.weaponUses.single().remainingCombats)
        assertTrue(game.adventureTraits.evidence["S05"].isNullOrEmpty())
        game.monster.grade = MonsterGrade.NORMAL
        repeat(3) { index ->
            AdventureTraitEngine.beginCombat(game, index.toLong() + 10L)
            AdventureTraitEngine.observeCombat(game, index.toLong() + 10L)
            AdventureTraitEngine.finalizeEvidence(game, index.toLong() + 10L)
        }
        assertEquals(1, game.adventureTraits.evidence["S05"].orEmpty().size)
        val evidence = game.adventureTraits.evidence["S05"]
        game.adventureTraits.weaponUses = listOf(AdventureTraitWeaponUse("comparison-before-extra", EquipmentSlot.WEAPON, weapon.power))
        game.adventureTraits.equipmentOrigins = mapOf(EquipmentSlot.WEAPON to "TRAIT_EXTRA")
        repeat(3) { index ->
            AdventureTraitEngine.beginCombat(game, index.toLong() + 20L)
            AdventureTraitEngine.observeCombat(game, index.toLong() + 20L)
            AdventureTraitEngine.finalizeEvidence(game, index.toLong() + 20L)
        }
        assertEquals(evidence, game.adventureTraits.evidence["S05"])
    }

    @Test
    fun `selected retry reward grants one bundle without extra loot appraisal or equipment evidence`() {
        val game = retryGame(successfulRetry = true)
        val base = eventBase(game, "ruins", "decode", AdventureEventOutcome.FAILURE)
        val result = AdventureTraitEngine.planEvent(game, base)
        assertEquals(AdventureEventOutcome.SUCCESS, result.outcome)
        prepareEventResult(game, result)
        val enabled = SimpleGameEngine(enableAdventureEvents = true, enableAdventureTraits = true)
        val delta = enabled.settleOffline(game, game.actionEndsAt)
        assertEquals(1, delta.recentEvents.count { it.type == RecentAdventureEventType.ADVENTURE_EVENT })
        assertEquals(1L, game.adventureJourney.completedEvents)
        assertEquals(1L, game.totalItemsFound)
        assertEquals(result.experienceReward, game.adventureJourney.lastResult?.experienceAwarded)
        assertFalse(game.adventureTraits.opportunityCounts.containsKey("L01"))
        assertFalse(game.adventureTraits.opportunityCounts.containsKey("L05"))
        assertTrue(game.adventureTraits.evidence["L01"].isNullOrEmpty())
        assertTrue(game.adventureTraits.evidence["L05"].isNullOrEmpty())
        assertEquals("TRAIT_RETRY", game.adventureTraits.recentRewardTraces.single { it.actualGranted }.origin)
        val saved = copy(game)
        enabled.settleOffline(saved, saved.lastSettledAt)
        assertEquals(game, saved)
    }

    @Test
    fun `unsuccessful retry retains the original reward and its one eligible extra loot roll`() {
        val game = retryGame(successfulRetry = false)
        val candidateBase = eventBase(game, "ruins", "decode", AdventureEventOutcome.PARTIAL)
        val base = (1L..20L).map { AdventureEventEngine.withOutcome(candidateBase, AdventureEventOutcome.PARTIAL, it) }
            .first { it.itemReward == AdventureEventItemReward.TROPHY }
        val result = AdventureTraitEngine.planEvent(game, base)
        assertEquals(AdventureEventOutcome.PARTIAL, result.outcome)
        assertEquals(AdventureEventOutcome.FAILURE, game.adventureTraits.source?.retryRun?.outcome)
        prepareEventResult(game, result)
        val enabled = SimpleGameEngine(enableAdventureEvents = true, enableAdventureTraits = true)
        enabled.settleOffline(game, game.actionEndsAt)
        assertEquals(1L, game.adventureJourney.completedEvents)
        assertEquals(1L, game.adventureTraits.opportunityCounts["L01"])
        assertEquals(1, game.adventureTraits.recentRewardTraces.count { it.actualGranted && it.origin == "PRIMARY" })
        assertEquals(0, game.adventureTraits.recentRewardTraces.count { it.origin == "TRAIT_RETRY" })
        assertEquals(base.experienceReward, game.adventureJourney.lastResult?.experienceAwarded)
    }

    @Test
    fun `forty eight active hours preserve every trait record across split settlements and restart`() {
        val enabled = SimpleGameEngine(enableAdventureEvents = true, enableAdventureTraits = true)
        val whole = newGame().apply {
            adventureTraits = AdventureTraitState(initialized = true, seed = 83_419L, owned = independentOwnedTraits())
        }
        var split = copy(whole)
        val hour = 3_600_000L
        val wholeEvents = enabled.settleOffline(whole, 48L * hour).recentEvents
        val splitEvents = mutableListOf<com.nullplaying.model.RecentAdventureEvent>()
        for (hours in 1L..48L) {
            splitEvents += enabled.settleOffline(split, hours * hour).recentEvents
            if (hours % 4L == 0L) split = copy(split)
        }
        val traitRecords = wholeEvents.filter { it.type == RecentAdventureEventType.ADVENTURE_TRAIT_ACTIVATED }
        assertTrue("natural activation records=${traitRecords.size}", traitRecords.size > 32)
        assertEquals(whole.adventureTraits.actualEffectCounts.values.sum(), traitRecords.size.toLong())
        assertEquals(wholeEvents, splitEvents)
        assertEquals(whole, split)
    }

    @Test
    fun `successful extra roll at the last bag slot keeps the original and adds no phantom item or delay`() {
        val game = newGame().apply {
            adventureTraits = AdventureTraitState(initialized = true,
                seed = seedPassing("L01:event:1:reward:1", 10), owned = listOf(AdventureOwnedTrait("L01")))
            inventory = (1 until inventoryCapacity().toInt()).map { index ->
                InventoryItem(index.toLong(), "Prior item $index", "일반", "전리품", hero.level)
            }.toMutableList()
            totalItemsFound = inventory.size.toLong()
        }
        val priorItems = game.inventory.toList()
        val beforeItems = game.totalItemsFound
        val base = eventBase(game, "ruins", "decode", AdventureEventOutcome.SUCCESS)
        val result = AdventureTraitEngine.planEvent(game, base)
        prepareEventResult(game, result)
        val enabled = SimpleGameEngine(enableAdventureEvents = true, enableAdventureTraits = true)
        enabled.settleOffline(game, game.actionEndsAt)
        assertEquals(1L, game.adventureTraits.opportunityCounts["L01"])
        assertEquals(1L, game.adventureTraits.procCounts["L01"])
        assertEquals(0L, game.adventureTraits.actualEffectCounts["L01"] ?: 0L)
        assertEquals(beforeItems + 1L, game.totalItemsFound)
        assertEquals(game.inventoryCapacity(), game.inventory.size.toLong())
        priorItems.forEach { item -> assertEquals(item, game.inventory.first { it.id == item.id }) }
        assertEquals(1, game.adventureJourney.lastResult?.actualItemCount)
        assertTrue(game.adventureJourney.lastResult?.additionalItemNames.orEmpty().isEmpty())
        assertEquals(AdventureEventEngine.RESULT_MILLIS, game.actionEndsAt - game.actionStartedAt)
        val restored = copy(game)
        enabled.settleOffline(restored, restored.actionEndsAt)
        assertEquals(AdventurePhase.RETURNING, restored.adventurePhase)
        assertEquals(beforeItems + 1L, restored.totalItemsFound)
        assertEquals(game.inventory, restored.inventory)
        assertEquals(1L, restored.adventureTraits.procCounts["L01"])
    }

    private fun retryGame(successfulRetry: Boolean) = newGame().apply {
        val seed = (1L..100_000L).first { candidate ->
            AdventureTraitEngine.random(candidate, "E03:event:1:retry") < 100 &&
                AdventureTraitEngine.random(candidate, "event:1:retryOutcome").let {
                    if (successfulRetry) it < 1_500 else it >= 9_500
                }
        }
        adventureTraits = AdventureTraitState(initialized = true, seed = seed,
            owned = listOf("E03", "L01", "L05").map { AdventureOwnedTrait(it) })
    }

    private fun prepareEventResult(game: SimpleGameState, run: com.nullplaying.model.AdventureEventRun) {
        game.adventureJourney.pending = run
        game.adventurePhase = AdventurePhase.EVENT
        game.actionStartedAt = run.startedAt
        game.actionEndsAt = run.startedAt + run.durationMillis
    }

    private fun dialogueGame(id: String) = newGame().apply {
        adventureTraits = AdventureTraitState(initialized = true,
            owned = listOf(AdventureOwnedTrait(id), AdventureOwnedTrait("E03")))
    }

    private fun dialogueBase(game: SimpleGameState, outcome: AdventureEventOutcome): com.nullplaying.model.AdventureEventRun {
        return eventBase(game, "mediation", "evidence", outcome)
    }

    private fun eventBase(game: SimpleGameState, eventId: String, approachId: String,
        outcome: AdventureEventOutcome): com.nullplaying.model.AdventureEventRun {
        val generated = AdventureEventEngine.begin(game, 1L)
        val definition = AdventureEventEngine.definition(eventId)
        val approach = definition.approaches.first { it.id == approachId }
        val rewardKind = when (eventId) {
            "ruins" -> AdventureEventRewardKind.ITEM
            "mediation" -> AdventureEventRewardKind.EXPERIENCE
            else -> error("Fixture reward kind must be explicit for $eventId")
        }
        return AdventureEventEngine.withOutcome(generated.copy(eventId = definition.id, approachId = approach.id,
            primaryStat = approach.primaryStat, secondaryStat = approach.secondaryStat,
            primaryValue = AdventureEventEngine.stat(game.hero.stats, approach.primaryStat),
            secondaryValue = AdventureEventEngine.stat(game.hero.stats, approach.secondaryStat),
            rewardKind = rewardKind), outcome, 37_991L)
    }

    private fun seedPassing(key: String, threshold: Int): Long =
        (1L..100_000L).first { AdventureTraitEngine.random(it, key) < threshold }

    private fun pendingExtraWeapon(price: Long): SimpleGameState = newGame().apply {
        equipment.forEach { it.power = 100L; it.rarity = "일반" }
        hero.gold = 10_000_000L
        adventurePhase = AdventurePhase.SHOPPING
        actionStartedAt = 0L
        actionEndsAt = 1L
        shopAttemptedSlots = EquipmentSlot.entries.toMutableList()
        pendingShopOffer = ShopEquipmentOffer(EquipmentSlot.WEAPON, "Local paid candidate", "희귀",
            previousPower = 100L, newPower = 110L, price = price)
        adventureTraits = AdventureTraitState(initialized = true, seed = 71L,
            owned = listOf(AdventureOwnedTrait("S05", 0L, 1L)),
            shopVisit = AdventureTraitShopVisit("shop:1", true, 0L,
                extraUsed = true, pendingIsExtra = true))
    }

    private fun independentOwnedTraits() =
        listOf("C03", "L01", "L04", "L05", "S01", "S05", "E03", "G01", "R05")
            .mapIndexed { index, id -> AdventureOwnedTrait(id, 1L, index.toLong() + 1L) }

    private fun newGame(heroClass: HeroClass = HeroClass.RANGER): SimpleGameState = engine.newGame(
        "Local boundary QA", heroClass,
        HeroStats(12, 14, 16, 11, 15, 13, 80, 40), 73_419L, 0L,
    ).apply { hero.level = 20L; rankingCharacterId = "local-boundary-hero" }

    private fun copy(state: SimpleGameState): SimpleGameState = json.decodeFromString(json.encodeToString(state))
}
