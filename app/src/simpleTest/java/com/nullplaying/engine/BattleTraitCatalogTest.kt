package com.nullplaying.engine

import com.nullplaying.model.BATTLE_MAX_ACTIVE_TRAITS
import com.nullplaying.model.BATTLE_TRAIT_REMOVAL_MILLIS
import com.nullplaying.model.BattleBuildStats
import com.nullplaying.model.BattleProjectionSnapshot
import com.nullplaying.model.BattleSide
import com.nullplaying.model.BattleTraitCategory
import com.nullplaying.model.BattleTraitMutationStatus
import com.nullplaying.model.BattleTraitState
import com.nullplaying.model.UserInitiatedBattleRequest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BattleTraitCatalogTest {
    @Test
    fun `catalog contains one hundred distinct useful Korean narrative traits`() {
        val traits = BattleTraitCatalog.all

        assertEquals(100, traits.size)
        assertEquals((1..100).map { "TRAIT_%03d".format(it) }, traits.map { it.id })
        assertEquals(100, traits.map { it.id }.distinct().size)
        assertEquals(100, traits.map { it.nameKo }.distinct().size)
        assertEquals(100, traits.map { it.descriptionKo }.distinct().size)
        assertTrue(traits.all { it.id.isNotBlank() && it.nameKo.isNotBlank() && it.descriptionKo.isNotBlank() })
        assertTrue(traits.all { it.combatProfile.values().sum() == 300 })
        assertTrue(traits.flatMap { it.combatProfile.values() }.all { it in 0..100 })
        assertTrue(traits.map { it.combatProfile }.distinct().size >= 70)
        BattleTraitCategory.entries.forEach { category ->
            assertEquals(10, traits.count { it.category == category })
        }
    }

    @Test
    fun `active traits blend into one normalized six axis combat profile`() {
        val profile = BattleTraitCatalog.combatProfileFor(
            listOf("TRAIT_011", "TRAIT_041", "TRAIT_091", "UNKNOWN"),
        )

        assertEquals(6, profile.values().size)
        assertEquals(300, profile.values().sum())
        assertTrue(profile.values().all { it in 0..100 })
        assertEquals(com.nullplaying.model.BattleTraitCombatProfile(), BattleTraitCatalog.combatProfileFor(emptyList()))
    }

    @Test
    fun `hero path rank strengthens the same style without breaking fixed budget`() {
        val traitId = "TRAIT_011"
        val rankOne = BattleTraitCatalog.combatProfileFor(listOf(traitId), mapOf(traitId to 1))
        val rankFive = BattleTraitCatalog.combatProfileFor(listOf(traitId), mapOf(traitId to 5))
        val rankOverflow = BattleTraitCatalog.combatProfileFor(listOf(traitId), mapOf(traitId to 500))

        assertEquals(300, rankFive.values().sum())
        assertTrue(rankFive.aggression > rankOne.aggression)
        assertEquals(rankFive, rankOverflow)
    }

    @Test
    fun `active traits cap at five while repeated evidence does not consume another slot`() {
        var state = BattleTraitState()
        val firstFive = BattleTraitCatalog.all.take(BATTLE_MAX_ACTIVE_TRAITS)
        firstFive.forEachIndexed { index, trait ->
            val mutation = BattleTraitCatalog.addTrait(state, trait.id, nowMillis = index.toLong())
            assertEquals(BattleTraitMutationStatus.ADDED, mutation.status)
            state = mutation.state
        }

        val rejected = BattleTraitCatalog.addTrait(
            state,
            BattleTraitCatalog.all[BATTLE_MAX_ACTIVE_TRAITS].id,
            nowMillis = 100L,
        )
        assertEquals(BattleTraitMutationStatus.ACTIVE_LIMIT_REACHED, rejected.status)
        assertEquals(BATTLE_MAX_ACTIVE_TRAITS, rejected.state.active.size)

        val repeated = BattleTraitCatalog.addTrait(state, firstFive.first().id, nowMillis = 200L)
        assertEquals(BattleTraitMutationStatus.EVIDENCE_ADDED, repeated.status)
        assertEquals(BATTLE_MAX_ACTIVE_TRAITS, repeated.state.active.size)
        assertEquals(2, repeated.state.active.first().evidenceCount)
    }

    @Test
    fun `one hour removal remains active can be cancelled and completes only at deadline`() {
        val traitId = BattleTraitCatalog.all.first().id
        val active = BattleTraitCatalog.addTrait(BattleTraitState(), traitId, nowMillis = 10L).state
        val scheduled = BattleTraitCatalog.requestRemoval(active, traitId, nowMillis = 1_000L)

        assertEquals(BattleTraitMutationStatus.REMOVAL_SCHEDULED, scheduled.status)
        assertEquals(1, scheduled.state.active.size)
        assertEquals(1_000L + BATTLE_TRAIT_REMOVAL_MILLIS, scheduled.state.removal?.readyAtMillis)

        val early = BattleTraitCatalog.completeRemoval(
            scheduled.state,
            nowMillis = 1_000L + BATTLE_TRAIT_REMOVAL_MILLIS - 1L,
        )
        assertEquals(BattleTraitMutationStatus.NOT_READY, early.status)
        assertEquals(1, early.state.active.size)

        val cancelled = BattleTraitCatalog.cancelRemoval(scheduled.state, nowMillis = 2_000L)
        assertEquals(BattleTraitMutationStatus.REMOVAL_CANCELLED, cancelled.status)
        assertNull(cancelled.state.removal)
        assertEquals(1, cancelled.state.active.size)

        val rescheduled = BattleTraitCatalog.requestRemoval(cancelled.state, traitId, nowMillis = 5_000L)
        val atDeadline = BattleTraitCatalog.completeRemoval(
            rescheduled.state,
            nowMillis = 5_000L + BATTLE_TRAIT_REMOVAL_MILLIS,
        )
        assertEquals(BattleTraitMutationStatus.REMOVED, atDeadline.status)
        assertTrue(atDeadline.state.active.isEmpty())
        assertNull(atDeadline.state.removal)
    }

    @Test
    fun `expired removal cannot be cancelled instead of completed`() {
        val traitId = BattleTraitCatalog.all.first().id
        val active = BattleTraitCatalog.addTrait(BattleTraitState(), traitId, 0L).state
        val scheduled = BattleTraitCatalog.requestRemoval(active, traitId, 0L).state
        val cancellation = BattleTraitCatalog.cancelRemoval(
            scheduled,
            BATTLE_TRAIT_REMOVAL_MILLIS,
        )

        assertEquals(BattleTraitMutationStatus.CANCELLATION_WINDOW_CLOSED, cancellation.status)
        assertNotNull(cancellation.state.removal)
        assertFalse(cancellation.state.active.isEmpty())
    }

    @Test
    fun `battle evidence suggests a deterministic catalog trait without changing its result`() {
        val request = UserInitiatedBattleRequest(
            battleId = "trait-evidence",
            serverSeed = 88L,
            user = BattleProjectionSnapshot(
                projectionId = "user",
                verifiedPower = 20_000L,
                build = BattleBuildStats(30L, 25L, 20L, 10L, 10L, 5L),
            ),
            opponent = BattleProjectionSnapshot(
                projectionId = "opponent",
                verifiedPower = 20_000L,
                build = BattleBuildStats(10L, 10L, 20L, 30L, 25L, 5L),
            ),
        )
        val result = ProjectionBattleEngine.simulate(request)
        val first = BattleTraitCatalog.suggestedTrait(result, BattleSide.USER)
        val second = BattleTraitCatalog.suggestedTrait(result, BattleSide.USER)

        assertEquals(first, second)
        assertEquals(first, BattleTraitCatalog.byId[first.id])
        assertEquals(result, ProjectionBattleEngine.simulate(request))
    }

    @Test
    fun `trait state is serializable and empty JSON uses safe defaults`() {
        val json = Json { encodeDefaults = true }
        val state = BattleTraitCatalog.addTrait(
            BattleTraitState(),
            BattleTraitCatalog.all.last().id,
            nowMillis = 123L,
        ).state

        assertEquals(state, json.decodeFromString<BattleTraitState>(json.encodeToString(state)))
        assertEquals(BattleTraitState(), json.decodeFromString<BattleTraitState>("{}"))
    }
}
