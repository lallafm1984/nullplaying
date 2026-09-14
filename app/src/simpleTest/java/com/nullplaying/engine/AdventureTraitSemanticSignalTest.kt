package com.nullplaying.engine

import com.nullplaying.model.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AdventureTraitSemanticSignalTest {
    private val engine = SimpleGameEngine(enableAdventureEvents = true, enableAdventureTraits = true)

    @Test fun `all event behavior signals add symmetric evidence with context and story family diversity`() {
        axes.forEach { axis ->
            listOf(
                Triple(axis.firstSignal, axis.firstTrait, axis.secondTrait),
                Triple(axis.secondSignal, axis.secondTrait, axis.firstTrait),
            ).forEach { (signal, supportingTrait, opposingTrait) ->
                val reference = signalReferences(signal).firstOrNull { axis.opposite(signal) !in it.approach.behaviorSignals }
                    ?: throw AssertionError("No unambiguous authored approach for $signal")
                val game = game()
                val base = eventRun(game, reference, 1L)
                AdventureTraitEngine.planEvent(game, base)
                AdventureTraitEngine.observeEvent(game, 1L)
                AdventureTraitEngine.finalizeEvidence(game, 1L)

                val expectedContext = "${base.context.name}:${reference.definition.storyFamily}"
                assertEquals(expectedContext, game.adventureTraits.evidence.getValue(supportingTrait).single().contextKey)
                assertTrue(game.adventureTraits.evidence.getValue(supportingTrait).single().positive)
                assertEquals(expectedContext, game.adventureTraits.evidence.getValue(opposingTrait).single().contextKey)
                assertFalse(game.adventureTraits.evidence.getValue(opposingTrait).single().positive)
            }
        }
    }

    @Test fun `semantic risk and safety evidence forms weakens and removes a trait without opposite coexistence`() {
        val riskSamples = diverseSamples(AdventureBehaviorSignal.TAKE_RISK, AdventureBehaviorSignal.CHECK_SAFETY)
        val safetySamples = diverseSamples(AdventureBehaviorSignal.CHECK_SAFETY, AdventureBehaviorSignal.TAKE_RISK)
        val seed = seedWhere { candidate ->
            AdventureTraitEngine.random(candidate, "FORMATION:event:8") < 2_500 &&
                // E01 and E05 both have eight supported signs across three contexts.
                AdventureTraitEngine.random(candidate, "event:8:formation-choice", 240) < 120 &&
                (9..18).all { sequence ->
                    AdventureTraitEngine.random(candidate, "FORMATION:event:$sequence") >= 2_500 &&
                        AdventureTraitEngine.random(candidate, "E01:event:$sequence:approach") >= 100
                }
        }
        val game = game().also { it.adventureTraits.seed = seed }

        repeat(8) { index ->
            recordEventEvidence(
                game,
                riskSamples[index % riskSamples.size],
                index + 1L,
                index * 2L * HOUR,
            )
            assertFalse(game.ownsBoth("E01", "E02"))
        }
        assertTrue(game.adventureTraits.owned.any { it.traitId == "E01" })
        assertTrue(game.adventureTraits.recentChanges.any {
            it.traitId == "E01" && it.kind == AdventureTraitChangeKind.ACQUIRED
        })

        val safetyHours = listOf(16L, 20L, 24L, 28L, 32L, 40L, 41L, 42L, 43L, 88L)
        repeat(10) { index ->
            recordEventEvidence(
                game,
                safetySamples[index % safetySamples.size],
                index + 9L,
                safetyHours[index] * HOUR,
            )
            assertFalse(game.ownsBoth("E01", "E02"))
        }
        assertFalse(game.adventureTraits.owned.any { it.traitId == "E01" })
        assertFalse(game.adventureTraits.owned.any { it.traitId == "E02" })
        assertEquals(
            AdventureTraitChangeKind.LOST,
            game.adventureTraits.recentChanges.last { it.traitId == "E01" }.kind,
        )
    }

    @Test fun `trait selected and successful retry approaches become resolved event evidence`() {
        val (definition, baseline, riskApproach) = AdventureEventEngine.all.asSequence().mapNotNull { event ->
            val risk = event.approaches.firstOrNull { AdventureBehaviorSignal.TAKE_RISK in it.behaviorSignals }
            val original = event.approaches.firstOrNull { AdventureBehaviorSignal.TAKE_RISK !in it.behaviorSignals }
            if (risk == null || original == null) null else Triple(event, original, risk)
        }.firstOrNull() ?: throw AssertionError("No event offers both a risk and non-risk approach")
        val baselineReference = SignalReference(definition, baseline)

        val biased = game("E01")
        biased.adventureTraits.seed = seedWhere {
            AdventureTraitEngine.random(it, "E01:event:1:approach") < 100
        }
        val base = eventRun(biased, baselineReference, 1L)
        val planned = AdventureTraitEngine.planEvent(biased, base)
        assertEquals(planned, biased.adventureTraits.source!!.baseEvent)
        assertNotEquals(base.approachId, planned.approachId)
        assertTrue(AdventureBehaviorSignal.TAKE_RISK in riskApproach.behaviorSignals)
        assertTrue(
            AdventureBehaviorSignal.TAKE_RISK in definition.approaches
                .first { it.id == planned.approachId }.behaviorSignals,
        )
        assertEquals("TRAIT_APPROACH", biased.adventureTraits.source!!.finalRewardOrigin)
        AdventureTraitEngine.observeEvent(biased, 1L)
        AdventureTraitEngine.finalizeEvidence(biased, 1L)
        assertTrue(biased.adventureTraits.evidence.getValue("E01").single().positive)

        val retried = game("E03")
        val retryBase = eventRun(retried, baselineReference, 1L, AdventureEventOutcome.FAILURE)
        val alternatives = definition.approaches.filter { it.id != baseline.id }
        val riskIndex = alternatives.indexOfFirst { it.id == riskApproach.id }
        val retrySuccess = AdventureEventEngine.successBasisPoints(retried.hero.stats, retried.hero.level, riskApproach)
        val retryPartial = minOf(2_500, 9_500 - retrySuccess)
        retried.adventureTraits.seed = seedWhere {
            AdventureTraitEngine.random(it, "E03:event:1:retry") < 100 &&
                AdventureTraitEngine.random(it, "event:1:approach", alternatives.size) == riskIndex &&
                AdventureTraitEngine.random(it, "event:1:retryOutcome") < retrySuccess + retryPartial
        }
        val retryPlanned = AdventureTraitEngine.planEvent(retried, retryBase)
        assertNotNull(retried.adventureTraits.source!!.retryRun)
        assertEquals(riskApproach.id, retryPlanned.approachId)
        assertEquals(retryPlanned, retried.adventureTraits.source!!.baseEvent)
        AdventureTraitEngine.observeEvent(retried, 2L)
        AdventureTraitEngine.finalizeEvidence(retried, 2L)
        assertTrue(retried.adventureTraits.evidence.getValue("E01").single().positive)
    }

    @Test fun `new low frequency event and repetition effects change only their documented values`() {
        val reference = signalReferences(AdventureBehaviorSignal.MOVE_ON).first()
        val moveOn = game("E04")
        moveOn.adventureTraits.seed = seedWhere {
            AdventureTraitEngine.random(it, "E04:event:1:moveOn") < 100
        }
        val unresolved = eventRun(moveOn, reference, 1L, AdventureEventOutcome.FAILURE, 20_000L)
        val shortened = AdventureTraitEngine.planEvent(moveOn, unresolved)
        assertEquals(17_000L, shortened.durationMillis)
        assertEquals(shortened, moveOn.adventureTraits.source!!.baseEvent)
        assertEquals(AdventureEventOutcome.FAILURE, shortened.outcome)
        assertEquals(1L, moveOn.adventureTraits.actualEffectCounts["E04"])

        val repeated = game("G02")
        repeated.adventureTraits.recentExperienceFamilies = listOf("known-family")
        AdventureTraitEngine.beginSource(repeated, "combat", "known-family", 1L)
        repeated.adventureTraits.seed = seedWhere {
            AdventureTraitEngine.random(it, "G02:combat:1:xp") < 100
        }
        assertEquals(105L, AdventureTraitEngine.experience(repeated, 100L, "known-family", 1L))
        assertEquals(1_100L, AdventureTraitEngine.resultMillis(repeated, 1_000L))
        AdventureTraitEngine.finalizeEvidence(repeated, 1L)
        assertTrue(repeated.adventureTraits.evidence["G02"].orEmpty().isEmpty())
        assertTrue(repeated.adventureTraits.evidence["G01"].orEmpty().isEmpty())
    }

    @Test fun `town and wilderness comfort traits adjust event time in both directions`() {
        val reference = signalReferences(AdventureBehaviorSignal.TOWN_COMFORT).first()
        data class Case(val traitId: String, val context: AdventureEventContext, val expectedMillis: Long)
        listOf(
            Case("T01", AdventureEventContext.TOWN_RETURN, 18_000L),
            Case("T01", AdventureEventContext.FIELD_EXPLORATION, 22_000L),
            Case("T02", AdventureEventContext.TOWN_RETURN, 22_000L),
            Case("T02", AdventureEventContext.FIELD_EXPLORATION, 18_000L),
        ).forEach { case ->
            val game = game(case.traitId)
            game.adventureTraits.seed = seedWhere {
                AdventureTraitEngine.random(it, "${case.traitId}:event:1:contextPace") < 100
            }
            val base = eventRun(game, reference, 1L, durationMillis = 20_000L).copy(context = case.context)
            val result = AdventureTraitEngine.planEvent(game, base)
            assertEquals(case.expectedMillis, result.durationMillis)
            assertEquals(result, game.adventureTraits.source!!.baseEvent)
            assertEquals(1L, game.adventureTraits.actualEffectCounts[case.traitId])
        }
    }

    @Test fun `light packing shortens departure reduces one bag slot and resets on return`() {
        val game = game("L03")
        val baseCapacity = game.inventoryCapacity()
        game.adventureTraits.seed = seedWhere {
            AdventureTraitEngine.random(it, "L03:departure:1") < 100
        }

        assertEquals(2_550L, AdventureTraitEngine.depart(game, 1L, 3_000L))
        assertEquals(-1L, game.adventureTraits.temporaryBagSlots)
        assertEquals(baseCapacity - 1L, game.inventoryCapacity())
        assertEquals(1L, game.adventureTraits.actualEffectCounts["L03"])
        assertEquals(-450L, game.adventureTraits.recentActivations.single { it.traitId == "L03" }.timeAdjustmentMillis)

        AdventureTraitEngine.beginReturn(game, 3_000L)
        assertEquals(0L, game.adventureTraits.temporaryBagSlots)
        assertEquals(baseCapacity, game.inventoryCapacity())
    }

    @Test fun `armor enthusiast stores a deterministic non weapon extra review across restart`() {
        val game = game("S06")
        game.adventurePhase = AdventurePhase.SELLING
        game.actionEndsAt = 1_000L
        game.equipment.forEach { it.power = Long.MAX_VALUE / 10L }
        AdventureTraitEngine.ensureSource(game)
        val sourceKey = game.adventureTraits.source!!.key
        game.adventureTraits.seed = seedWhere {
            AdventureTraitEngine.random(it, "S06:$sourceKey:shop") < 300
        }

        engine.settleOffline(game, 1_000L)
        val visit = requireNotNull(game.adventureTraits.shopVisit)
        assertTrue(visit.extraAllowed)
        assertTrue(visit.extraUsed)
        assertEquals("S06", visit.extraTraitId)
        assertTrue(visit.extraSlot in setOf(EquipmentSlot.BODY, EquipmentSlot.HEAD, EquipmentSlot.HANDS, EquipmentSlot.FEET))
        assertNotEquals(EquipmentSlot.WEAPON, visit.extraSlot)
        assertEquals(6, game.shopAttemptedSlots.size)
        assertEquals(AdventurePhase.SHOPPING_EMPTY, game.adventurePhase)
        assertEquals(1L, game.adventureTraits.actualEffectCounts["S06"])
        assertEquals(AdventureTraitEffectKind.SHOP_REVIEW,
            game.adventureTraits.recentActivations.single { it.traitId == "S06" }.effectKind)

        val restored: SimpleGameState = Json.decodeFromString(Json.encodeToString(game))
        assertEquals(game, restored)
        engine.settleOffline(game, game.actionEndsAt)
        engine.settleOffline(restored, restored.actionEndsAt)
        assertEquals(game, restored)
    }

    @Test fun `familiar gear defers only marginal upgrades and clears held item guards after sale`() {
        val game = game("L06")
        AdventureTraitEngine.beginSource(game, "combat", "qa", 1L)
        game.adventureTraits.seed = seedWhere {
            AdventureTraitEngine.random(it, "L06:loot:1:familiar") < 100
        }

        assertTrue(AdventureTraitEngine.keepFamiliarGear(
            game, "loot:1", EquipmentSlot.BODY, currentPower = 100L, candidatePower = 105L, at = 2L,
        ))
        assertFalse(AdventureTraitEngine.keepFamiliarGear(
            game, "loot:2", EquipmentSlot.BODY, currentPower = 100L, candidatePower = 106L, at = 2L,
        ))
        assertFalse(AdventureTraitEngine.keepFamiliarGear(
            game, "loot:3", EquipmentSlot.BODY, currentPower = 100L, candidatePower = 100L, at = 2L,
        ))
        assertEquals(1L, game.adventureTraits.actualEffectCounts["L06"])

        game.inventory = mutableListOf(
            InventoryItem(41L, "보류한 갑옷", "고급", "장비", 1L, EquipmentSlot.BODY, 105L),
        )
        game.adventureTraits.familiarHeldItemIds = setOf(41L, 999L)
        AdventureTraitEngine.beginReturn(game, 3L)
        assertEquals(setOf(41L), game.adventureTraits.familiarHeldItemIds)
        game.adventureTraits.saleBatch = AdventureTraitSaleBatch(
            sourceKey = "town:2:sale",
            itemIds = emptyList(),
            baseValues = emptyList(),
            paidValues = emptyList(),
            durationPercent = 100,
            traitId = "",
        )
        AdventureTraitEngine.finishSale(game, 4L)
        assertTrue(game.adventureTraits.familiarHeldItemIds.isEmpty())
    }

    @Test fun `restoring every reciprocal pair keeps only one side of all twenty axes`() {
        val game = this.game(*AdventureTraitCatalog.all.map { it.id }.toTypedArray())
        assertEquals(20, game.adventureTraits.owned.size)
        AdventureTraitCatalog.all.map { setOf(it.id, it.oppositeId) }.toSet().forEach { pair ->
            assertEquals(1, game.adventureTraits.owned.count { it.traitId in pair })
        }
    }

    private fun recordEventEvidence(
        game: SimpleGameState,
        reference: SignalReference,
        sequence: Long,
        at: Long,
    ) {
        val base = eventRun(game, reference, sequence).copy(startedAt = at)
        AdventureTraitEngine.planEvent(game, base)
        AdventureTraitEngine.observeEvent(game, at)
        AdventureTraitEngine.finalizeEvidence(game, at)
    }

    private fun diverseSamples(
        signal: AdventureBehaviorSignal,
        opposite: AdventureBehaviorSignal,
    ): List<SignalReference> {
        val candidates = signalReferences(signal).filter { opposite !in it.approach.behaviorSignals }
        for (first in candidates.indices) {
            for (second in first + 1 until candidates.size) {
                for (third in second + 1 until candidates.size) {
                    val samples = listOf(candidates[first], candidates[second], candidates[third])
                    if (samples.map { it.diversityKey }.toSet().size != 3) continue
                    val common = samples.map { it.approach.behaviorSignals }
                        .reduce { left, right -> left intersect right }
                    if (common == setOf(signal)) return samples
                }
            }
        }
        throw AssertionError("$signal needs three diverse authored situations without one repeated companion signal")
    }

    private fun signalReferences(signal: AdventureBehaviorSignal): List<SignalReference> =
        AdventureEventEngine.all.flatMap { definition ->
            definition.approaches.filter { signal in it.behaviorSignals }
                .map { SignalReference(definition, it) }
        }

    private fun eventRun(
        state: SimpleGameState,
        reference: SignalReference,
        sequence: Long,
        outcome: AdventureEventOutcome = AdventureEventOutcome.SUCCESS,
        durationMillis: Long = 10_000L,
    ): AdventureEventRun {
        val approach = reference.approach
        val success = AdventureEventEngine.successBasisPoints(state.hero.stats, state.hero.level, approach)
        return AdventureEventRun(
            sequence = sequence,
            eventId = reference.definition.id,
            approachId = approach.id,
            startedAt = sequence * 100_000L,
            durationMillis = durationMillis,
            heroLevel = state.hero.level,
            primaryStat = approach.primaryStat,
            secondaryStat = approach.secondaryStat,
            primaryValue = AdventureEventEngine.stat(state.hero.stats, approach.primaryStat),
            secondaryValue = AdventureEventEngine.stat(state.hero.stats, approach.secondaryStat),
            successBasisPoints = success,
            partialBasisPoints = minOf(2_500, 9_500 - success),
            roll = 0,
            outcome = outcome,
            experienceReward = 100L,
            goldReward = 0L,
            itemReward = AdventureEventItemReward.NONE,
            routeDelayMillis = 0L,
            rewardSeed = sequence * 37L,
            baseExperienceBudget = 100L,
            rewardKind = AdventureEventRewardKind.EXPERIENCE,
            context = reference.definition.context,
        )
    }

    private fun game(vararg traitIds: String): SimpleGameState {
        val rolled = engine.rollStats(77L, HeroClass.WARRIOR)
        return engine.newGame("의미 신호 검증", HeroClass.WARRIOR, rolled.stats, rolled.nextSeed, 0L).also { game ->
            game.adventureTraits.owned = traitIds.mapIndexed { index, id ->
                AdventureOwnedTrait(id, acquisitionSequence = index + 1L)
            }
            AdventureTraitEngine.initialize(game)
        }
    }

    private fun SimpleGameState.ownsBoth(first: String, second: String): Boolean {
        val ids = adventureTraits.owned.map { it.traitId }.toSet()
        return first in ids && second in ids
    }

    private fun seedWhere(predicate: (Long) -> Boolean): Long = (1L..2_000_000L).first(predicate)

    private data class SignalReference(
        val definition: AdventureEventDefinition,
        val approach: AdventureEventApproach,
    ) {
        val diversityKey: String = "${definition.context.name}:${definition.storyFamily}"
    }

    private data class SignalAxis(
        val firstSignal: AdventureBehaviorSignal,
        val firstTrait: String,
        val secondSignal: AdventureBehaviorSignal,
        val secondTrait: String,
    ) {
        fun opposite(signal: AdventureBehaviorSignal): AdventureBehaviorSignal =
            if (signal == firstSignal) secondSignal else firstSignal
    }

    private val axes = listOf(
        SignalAxis(AdventureBehaviorSignal.TAKE_ALL, "L01", AdventureBehaviorSignal.TAKE_ONLY_USEFUL, "L02"),
        SignalAxis(AdventureBehaviorSignal.PREPARE_THOROUGHLY, "L04", AdventureBehaviorSignal.DEPART_LIGHTLY, "L03"),
        SignalAxis(AdventureBehaviorSignal.INSPECT_NEW_GEAR, "L05", AdventureBehaviorSignal.KEEP_FAMILIAR_GEAR, "L06"),
        SignalAxis(AdventureBehaviorSignal.WEAPON_FOCUS, "S05", AdventureBehaviorSignal.ARMOR_FOCUS, "S06"),
        SignalAxis(AdventureBehaviorSignal.PERSIST, "E03", AdventureBehaviorSignal.MOVE_ON, "E04"),
        SignalAxis(AdventureBehaviorSignal.SEEK_NOVELTY, "G01", AdventureBehaviorSignal.REPEAT_PROVEN, "G02"),
        SignalAxis(AdventureBehaviorSignal.TAKE_RISK, "E01", AdventureBehaviorSignal.CHECK_SAFETY, "E02"),
        SignalAxis(AdventureBehaviorSignal.HELP_OTHERS, "R01", AdventureBehaviorSignal.SELF_PRIORITY, "R02"),
        SignalAxis(AdventureBehaviorSignal.COOPERATE, "R03", AdventureBehaviorSignal.ACT_ALONE, "R04"),
        SignalAxis(AdventureBehaviorSignal.SPEAK_DIRECT, "R05", AdventureBehaviorSignal.SPEAK_GENTLE, "R06"),
        SignalAxis(AdventureBehaviorSignal.TOWN_COMFORT, "T01", AdventureBehaviorSignal.WILDERNESS_COMFORT, "T02"),
        SignalAxis(AdventureBehaviorSignal.HURRY_HOME, "T03", AdventureBehaviorSignal.LINGER_RETURN, "T04"),
    )

    companion object { private const val HOUR = 3_600_000L }
}
