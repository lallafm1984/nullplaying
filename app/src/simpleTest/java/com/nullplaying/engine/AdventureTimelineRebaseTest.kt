package com.nullplaying.engine

import com.nullplaying.model.AdventureEncounterCandidate
import com.nullplaying.model.AdventureEncounterRoster
import com.nullplaying.model.AdventureEventBattleRewardKind
import com.nullplaying.model.AdventureEventBattleState
import com.nullplaying.model.AdventureEventContinuation
import com.nullplaying.model.AdventureEventItemReward
import com.nullplaying.model.AdventureEventOutcome
import com.nullplaying.model.AdventureEventResult
import com.nullplaying.model.AdventureEventRun
import com.nullplaying.model.AdventureEventStat
import com.nullplaying.model.AdventureOwnedTrait
import com.nullplaying.model.AdventureRelationshipContact
import com.nullplaying.model.AdventureRelationshipMemory
import com.nullplaying.model.AdventureRelationshipResult
import com.nullplaying.model.AdventureRelationshipRun
import com.nullplaying.model.AdventureTraitActivation
import com.nullplaying.model.AdventureTraitChange
import com.nullplaying.model.AdventureTraitChangeKind
import com.nullplaying.model.AdventureTraitEffectKind
import com.nullplaying.model.AdventureTraitEvidenceUpdate
import com.nullplaying.model.AdventureTraitSource
import com.nullplaying.model.HeroClass
import com.nullplaying.model.MonsterGrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdventureTimelineRebaseTest {
    private val engine = SimpleGameEngine(
        enableAdventureEvents = true,
        enableAdventureRelationships = true,
        enableAdventureTraits = true,
    )

    @Test
    fun `trusted rebase moves local adventure clocks and keeps server roster epoch`() {
        val oldCheckpoint = 1_000_000L
        val trustedNow = 100_000L
        val game = engine.newGame(
            name = "시간축 검사",
            heroClass = HeroClass.WARRIOR,
            rolledStats = engine.rollStats(77L).stats,
            seed = 88L,
            now = oldCheckpoint,
        )
        game.actionStartedAt = 990_000L
        game.actionEndsAt = 1_010_000L
        game.lastSettledAt = oldCheckpoint
        game.hero.level = 10L
        val candidate = AdventureEncounterCandidate(
            characterId = "peer-1",
            displayName = "Peer One",
            heroClass = HeroClass.ROGUE,
            level = 10L,
            combatPower = 42L,
        )
        val oldEvent = eventRun(startedAt = 980_000L)
        val pendingEvent = eventRun(startedAt = 995_000L, sequence = 2L)
        val eventResult = AdventureEventResult(
            run = oldEvent,
            occurredAt = 985_000L,
            experienceAwarded = 1L,
            goldAwarded = 0L,
        )
        val corruptFutureResult = eventResult.copy(
            run = oldEvent.copy(sequence = 3L, startedAt = 1_090_000L),
            occurredAt = 1_100_000L,
        )
        game.adventureJourney.apply {
            initialized = true
            nextEventAt = 1_020_000L
            pending = pendingEvent
            lastResult = eventResult
            recentResults = listOf(eventResult, corruptFutureResult)
            eventBattle = AdventureEventBattleState(
                eventId = oldEvent.eventId,
                grade = MonsterGrade.ELITE,
                rewardKind = AdventureEventBattleRewardKind.GOLD,
                rewardSeed = 7L,
                continuation = AdventureEventContinuation.NEXT_ADVENTURE_STEP,
                context = oldEvent.context,
                result = eventResult,
            )
        }
        val relationshipRun = relationshipRun(candidate, startedAt = 995_000L)
        val relationshipResult = AdventureRelationshipResult(
            run = relationshipRun.copy(sequence = 2L, startedAt = 980_000L),
            occurredAt = 985_000L,
            experienceAwarded = 1L,
            scoreAfter = 5,
            progressAdded = 0L,
        )
        game.adventureRelationships.apply {
            initialized = true
            initializedAt = 900_000L
            pausedMillis = 12_345L
            nextEncounterAt = 1_030_000L
            roster = AdventureEncounterRoster(
                snapshotId = "server-snapshot",
                receivedAt = 90_000L,
                validUntil = 200_000L,
                candidates = listOf(candidate),
            )
            contacts = listOf(
                AdventureRelationshipContact(
                    characterId = candidate.characterId,
                    score = 5,
                    meetings = 1L,
                    firstMetAt = 930_000L,
                    lastMetAt = 990_000L,
                    lastMetActiveMillis = 60_000L,
                    nextEligibleActiveMillis = 70_000L,
                    latestSnapshot = candidate,
                    memories = listOf(
                        AdventureRelationshipMemory(
                            sequence = 1L,
                            sceneId = "road-help",
                            approachId = "help",
                            outcome = AdventureEventOutcome.SUCCESS,
                            occurredAt = 990_000L,
                            scoreBefore = 0,
                            scoreDelta = 5,
                            scoreAfter = 5,
                        ),
                    ),
                ),
            )
            pending = relationshipRun
            lastResult = relationshipResult
            recentResults = listOf(relationshipResult)
        }
        val change = AdventureTraitChange(
            sequence = 1L,
            traitId = "G01",
            kind = AdventureTraitChangeKind.ACQUIRED,
            sourceKey = "event:1",
            occurredAt = 990_000L,
            reasonKey = "test",
        )
        val activation = AdventureTraitActivation(
            sequence = 1L,
            traitId = "G01",
            sourceKey = "event:1",
            sourceActionSequence = 1L,
            occurredAt = 995_000L,
            effectKind = AdventureTraitEffectKind.EXPERIENCE,
        )
        game.adventureTraits.apply {
            initialized = true
            owned = listOf(AdventureOwnedTrait("G01", 920_000L, 1L, lastChange = change))
            formationStartedAtByTrait = mapOf("G02" to 930_000L)
            lastFormationAt = 940_000L
            stableStartedAtByTrait = mapOf("G01" to 950_000L)
            oppositionStartedAtByTrait = mapOf("G01" to 960_000L)
            weakenedStartedAtByTrait = mapOf("G01" to 970_000L)
            pendingEvidence = listOf(
                AdventureTraitEvidenceUpdate(
                    sourceKey = "event:2",
                    contextKey = "road",
                    positive = setOf("G01"),
                    negative = emptySet(),
                    occurredAt = 980_000L,
                    reasonKey = "test",
                ),
            )
            recentChanges = listOf(change)
            visibleActivations = listOf(activation)
            recentActivations = listOf(activation)
            source = AdventureTraitSource(
                key = "event:2",
                kind = "event",
                contextKey = "road",
                startedAt = 995_000L,
                ownedIds = listOf("G01"),
                baseEvent = pendingEvent,
                retryRun = oldEvent.copy(sequence = 4L, startedAt = 997_000L),
                baseRelationship = relationshipRun,
            )
        }
        val heroExperience = game.hero.experience
        val heroGold = game.hero.gold
        val rngState = game.rngState

        engine.rebaseTimelineWithoutProgress(game, trustedNow)

        assertEquals(90_000L, game.actionStartedAt)
        assertEquals(110_000L, game.actionEndsAt)
        assertEquals(trustedNow, game.lastSettledAt)
        assertEquals(120_000L, game.adventureJourney.nextEventAt)
        assertEquals(95_000L, game.adventureJourney.pending!!.startedAt)
        assertEquals(85_000L, game.adventureJourney.lastResult!!.occurredAt)
        assertEquals(trustedNow, game.adventureJourney.recentResults.last().occurredAt)
        assertEquals(trustedNow, game.adventureJourney.recentResults.last().run.startedAt)
        assertEquals(85_000L, game.adventureJourney.eventBattle!!.result!!.occurredAt)

        val relationships = game.adventureRelationships
        assertEquals(0L, relationships.initializedAt)
        assertEquals(12_345L, relationships.pausedMillis)
        assertEquals(130_000L, relationships.nextEncounterAt)
        assertEquals(90_000L, relationships.roster!!.receivedAt)
        assertEquals(200_000L, relationships.roster!!.validUntil)
        val contact = relationships.contacts.single()
        assertEquals(30_000L, contact.firstMetAt)
        assertEquals(90_000L, contact.lastMetAt)
        assertEquals(60_000L, contact.lastMetActiveMillis)
        assertEquals(70_000L, contact.nextEligibleActiveMillis)
        assertEquals(90_000L, contact.memories.single().occurredAt)
        assertEquals(95_000L, relationships.pending!!.startedAt)
        assertEquals(85_000L, relationships.lastResult!!.occurredAt)
        assertTrue(AdventureRelationshipEngine.eligibleCandidates(game, trustedNow).isNotEmpty())

        val traits = game.adventureTraits
        assertEquals(20_000L, traits.owned.single().acquiredAt)
        assertEquals(90_000L, traits.owned.single().lastChange!!.occurredAt)
        assertEquals(30_000L, traits.formationStartedAtByTrait.getValue("G02"))
        assertEquals(40_000L, traits.lastFormationAt)
        assertEquals(50_000L, traits.stableStartedAtByTrait.getValue("G01"))
        assertEquals(60_000L, traits.oppositionStartedAtByTrait.getValue("G01"))
        assertEquals(70_000L, traits.weakenedStartedAtByTrait.getValue("G01"))
        assertEquals(80_000L, traits.pendingEvidence.single().occurredAt)
        assertEquals(90_000L, traits.recentChanges.single().occurredAt)
        assertEquals(95_000L, traits.visibleActivations.single().occurredAt)
        assertEquals(95_000L, traits.recentActivations.single().occurredAt)
        assertEquals(95_000L, traits.source!!.startedAt)
        assertEquals(95_000L, traits.source!!.baseEvent!!.startedAt)
        assertEquals(97_000L, traits.source!!.retryRun!!.startedAt)
        assertEquals(95_000L, traits.source!!.baseRelationship!!.startedAt)
        assertEquals(listOf("G01"), traits.owned.map { it.traitId })
        assertFalse(traits.owned.any { owned ->
            AdventureTraitCatalog.definition(owned.traitId).oppositeId in traits.owned.map { it.traitId }
        })
        assertEquals(heroExperience, game.hero.experience)
        assertEquals(heroGold, game.hero.gold)
        assertEquals(rngState, game.rngState)
    }

    @Test
    fun `forward trusted rebase applies the same signed delta without moving server roster time`() {
        val oldCheckpoint = 100_000L
        val trustedNow = 1_000_000L
        val game = engine.newGame(
            name = "forward-rebase",
            heroClass = HeroClass.WARRIOR,
            rolledStats = engine.rollStats(91L).stats,
            seed = 92L,
            now = oldCheckpoint,
        )
        game.hero.level = 10L
        game.adventureJourney.apply {
            initialized = true
            nextEventAt = 120_000L
            pending = eventRun(startedAt = 95_000L)
        }
        val candidate = AdventureEncounterCandidate(
            characterId = "peer-forward",
            displayName = "Forward Peer",
            heroClass = HeroClass.ROGUE,
            level = 10L,
            combatPower = 42L,
        )
        game.adventureRelationships.apply {
            initialized = true
            initializedAt = 10_000L
            nextEncounterAt = 130_000L
            roster = AdventureEncounterRoster(
                snapshotId = "server-forward",
                receivedAt = 900_000L,
                validUntil = 1_100_000L,
                candidates = listOf(candidate),
            )
            contacts = listOf(
                AdventureRelationshipContact(
                    characterId = candidate.characterId,
                    meetings = 1L,
                    firstMetAt = 30_000L,
                    lastMetAt = 90_000L,
                    latestSnapshot = candidate,
                ),
            )
        }
        game.adventureTraits.apply {
            initialized = true
            owned = listOf(AdventureOwnedTrait("G01", acquiredAt = 20_000L))
            lastFormationAt = 40_000L
            stableStartedAtByTrait = mapOf("G01" to 50_000L)
        }

        engine.rebaseTimelineWithoutProgress(game, trustedNow)

        assertEquals(1_020_000L, game.adventureJourney.nextEventAt)
        assertEquals(995_000L, game.adventureJourney.pending!!.startedAt)
        assertEquals(910_000L, game.adventureRelationships.initializedAt)
        assertEquals(1_030_000L, game.adventureRelationships.nextEncounterAt)
        assertEquals(930_000L, game.adventureRelationships.contacts.single().firstMetAt)
        assertEquals(990_000L, game.adventureRelationships.contacts.single().lastMetAt)
        assertEquals(900_000L, game.adventureRelationships.roster!!.receivedAt)
        assertEquals(1_100_000L, game.adventureRelationships.roster!!.validUntil)
        assertTrue(AdventureRelationshipEngine.eligibleCandidates(game, trustedNow).isNotEmpty())
        assertEquals(920_000L, game.adventureTraits.owned.single().acquiredAt)
        assertEquals(940_000L, game.adventureTraits.lastFormationAt)
        assertEquals(950_000L, game.adventureTraits.stableStartedAtByTrait.getValue("G01"))
    }

    private fun eventRun(startedAt: Long, sequence: Long = 1L) = AdventureEventRun(
        sequence = sequence,
        eventId = "fallen-bridge",
        approachId = "cross",
        startedAt = startedAt,
        durationMillis = 5_000L,
        heroLevel = 10L,
        primaryStat = AdventureEventStat.DEX,
        secondaryStat = AdventureEventStat.WIS,
        primaryValue = 12L,
        secondaryValue = 11L,
        successBasisPoints = 6_000,
        partialBasisPoints = 2_000,
        roll = 123,
        outcome = AdventureEventOutcome.SUCCESS,
        experienceReward = 1L,
        goldReward = 0L,
        itemReward = AdventureEventItemReward.NONE,
        routeDelayMillis = 0L,
        rewardSeed = 7L,
    )

    private fun relationshipRun(
        candidate: AdventureEncounterCandidate,
        startedAt: Long,
    ) = AdventureRelationshipRun(
        sequence = 1L,
        sceneId = "road-help",
        approachId = "help",
        candidate = candidate,
        snapshotId = "server-snapshot",
        startedAt = startedAt,
        startedActiveMillis = 50_000L,
        durationMillis = 5_000L,
        heroLevel = 10L,
        primaryStat = AdventureEventStat.CHA,
        secondaryStat = AdventureEventStat.WIS,
        primaryValue = 12L,
        secondaryValue = 11L,
        successBasisPoints = 6_000,
        partialBasisPoints = 2_000,
        roll = 123,
        outcome = AdventureEventOutcome.SUCCESS,
        scoreBefore = 0,
        scoreDelta = 5,
        experienceReward = 1L,
        rewardSeed = 8L,
        encounterLevel = 10L,
        labyrinthDepth = 0L,
        baseExperienceBudget = 10L,
        reunion = false,
    )
}
