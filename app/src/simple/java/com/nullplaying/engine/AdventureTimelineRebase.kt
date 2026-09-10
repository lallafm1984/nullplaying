package com.nullplaying.engine

import com.nullplaying.model.AdventureEventResult
import com.nullplaying.model.AdventureEventRun
import com.nullplaying.model.AdventureJourneyState
import com.nullplaying.model.AdventureOwnedTrait
import com.nullplaying.model.AdventureRelationshipContact
import com.nullplaying.model.AdventureRelationshipMemory
import com.nullplaying.model.AdventureRelationshipResult
import com.nullplaying.model.AdventureRelationshipRun
import com.nullplaying.model.AdventureRelationshipState
import com.nullplaying.model.AdventureTraitActivation
import com.nullplaying.model.AdventureTraitChange
import com.nullplaying.model.AdventureTraitEvidenceUpdate
import com.nullplaying.model.AdventureTraitSource
import com.nullplaying.model.AdventureTraitState
import com.nullplaying.model.SimpleGameState

/**
 * Rebases character-owned epoch timestamps after provisional or legacy time is replaced by the
 * trusted game clock. Server-owned roster validity remains on its original server epoch.
 */
internal object AdventureTimelineRebase {
    fun rebase(state: SimpleGameState, oldCheckpoint: Long, newCheckpoint: Long) {
        if (oldCheckpoint == newCheckpoint) return
        val shift = TimestampShift(oldCheckpoint, newCheckpoint)
        rebaseJourney(state.adventureJourney, shift)
        rebaseRelationships(state.adventureRelationships, shift)
        rebaseTraits(state.adventureTraits, shift)
    }

    private fun rebaseJourney(journey: AdventureJourneyState, shift: TimestampShift) {
        if (journey.initialized && journey.nextEventAt != 0L) {
            journey.nextEventAt = shift.epoch(journey.nextEventAt)
        }
        journey.pending = journey.pending?.let(shift::eventRun)
        journey.lastResult = journey.lastResult?.let(shift::eventResult)
        journey.recentResults = journey.recentResults.map(shift::eventResult)
        journey.eventBattle = journey.eventBattle?.copy(
            result = journey.eventBattle?.result?.let(shift::eventResult),
        )
    }

    private fun rebaseRelationships(
        relationships: AdventureRelationshipState,
        shift: TimestampShift,
    ) {
        if (relationships.initializedAt != 0L) {
            relationships.initializedAt = shift.settled(relationships.initializedAt)
        }
        if (relationships.nextEncounterAt != 0L) {
            relationships.nextEncounterAt = shift.epoch(relationships.nextEncounterAt)
        }
        relationships.contacts = relationships.contacts.map(shift::contact)
        relationships.pending = relationships.pending?.let(shift::relationshipRun)
        relationships.lastResult = relationships.lastResult?.let(shift::relationshipResult)
        relationships.recentResults = relationships.recentResults.map(shift::relationshipResult)
        // roster.receivedAt/validUntil deliberately stay on the signed server epoch.
    }

    private fun rebaseTraits(traits: AdventureTraitState, shift: TimestampShift) {
        traits.owned = traits.owned.map(shift::ownedTrait)
        traits.formationStartedAtByTrait = traits.formationStartedAtByTrait.mapValues {
            shift.settled(it.value)
        }
        traits.lastFormationAt = traits.lastFormationAt?.let(shift::settled)
        traits.stableStartedAtByTrait = traits.stableStartedAtByTrait.mapValues {
            shift.settled(it.value)
        }
        traits.oppositionStartedAtByTrait = traits.oppositionStartedAtByTrait.mapValues {
            shift.settled(it.value)
        }
        traits.weakenedStartedAtByTrait = traits.weakenedStartedAtByTrait.mapValues {
            shift.settled(it.value)
        }
        traits.pendingEvidence = traits.pendingEvidence.map(shift::evidenceUpdate)
        traits.recentChanges = traits.recentChanges.map(shift::traitChange)
        traits.visibleActivations = traits.visibleActivations.map(shift::activation)
        traits.recentActivations = traits.recentActivations.map(shift::activation)
        traits.source = traits.source?.let(shift::traitSource)
    }

    private class TimestampShift(
        private val oldCheckpoint: Long,
        private val newCheckpoint: Long,
    ) {
        fun epoch(value: Long): Long = if (newCheckpoint >= oldCheckpoint) {
            safeAdd(value, nonNegativeDifference(newCheckpoint, oldCheckpoint))
        } else {
            safeSubtract(value, nonNegativeDifference(oldCheckpoint, newCheckpoint))
        }

        fun settled(value: Long): Long = epoch(value).coerceAtMost(newCheckpoint)

        fun eventRun(run: AdventureEventRun): AdventureEventRun =
            run.copy(startedAt = settled(run.startedAt))

        fun eventResult(result: AdventureEventResult): AdventureEventResult = result.copy(
            run = eventRun(result.run),
            occurredAt = settled(result.occurredAt),
        )

        fun relationshipRun(run: AdventureRelationshipRun): AdventureRelationshipRun =
            run.copy(startedAt = settled(run.startedAt))

        fun relationshipResult(result: AdventureRelationshipResult): AdventureRelationshipResult =
            result.copy(
                run = relationshipRun(result.run),
                occurredAt = settled(result.occurredAt),
            )

        fun relationshipMemory(memory: AdventureRelationshipMemory): AdventureRelationshipMemory =
            memory.copy(occurredAt = settled(memory.occurredAt))

        fun contact(contact: AdventureRelationshipContact): AdventureRelationshipContact = contact.copy(
            firstMetAt = if (contact.firstMetAt == 0L) 0L else settled(contact.firstMetAt),
            lastMetAt = if (contact.lastMetAt == 0L) 0L else settled(contact.lastMetAt),
            memories = contact.memories.map(::relationshipMemory),
        )

        fun traitChange(change: AdventureTraitChange): AdventureTraitChange =
            change.copy(occurredAt = settled(change.occurredAt))

        fun ownedTrait(owned: AdventureOwnedTrait): AdventureOwnedTrait = owned.copy(
            acquiredAt = if (owned.acquiredAt == 0L) 0L else settled(owned.acquiredAt),
            lastChange = owned.lastChange?.let(::traitChange),
        )

        fun activation(activation: AdventureTraitActivation): AdventureTraitActivation =
            activation.copy(occurredAt = settled(activation.occurredAt))

        fun evidenceUpdate(update: AdventureTraitEvidenceUpdate): AdventureTraitEvidenceUpdate =
            update.copy(occurredAt = settled(update.occurredAt))

        fun traitSource(source: AdventureTraitSource): AdventureTraitSource = source.copy(
            startedAt = settled(source.startedAt),
            baseEvent = source.baseEvent?.let(::eventRun),
            retryRun = source.retryRun?.let(::eventRun),
            baseRelationship = source.baseRelationship?.let(::relationshipRun),
        )

        private fun safeAdd(left: Long, right: Long): Long = when {
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
    }
}
