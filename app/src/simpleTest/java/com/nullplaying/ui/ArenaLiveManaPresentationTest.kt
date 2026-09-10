package com.nullplaying.ui

import com.nullplaying.engine.arena.ArenaSupportEvent
import com.nullplaying.engine.arena.ArenaSupportEventType
import com.nullplaying.engine.arena.ArenaSupportQaFixtures
import com.nullplaying.engine.arena.ArenaSupportTurnEngine
import com.nullplaying.model.HeroClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArenaLiveManaPresentationTest {
    private val user = "user"
    private val opponent = "opponent"

    @Test
    fun `first turn instant skills spend visible mana only with their own actions`() {
        val events = starts() + listOf(
            paid(3, user, 3_000),
            paid(4, opponent, 4_000),
            action(5, ArenaSupportEventType.ATTACK_HIT, user, opponent),
            action(6, ArenaSupportEventType.ATTACK_HIT, opponent, user),
        )
        val frames = buildArenaLiveManaFrames(events)

        assertMana(frames, 3, 10_000, 10_000)
        assertMana(frames, 4, 10_000, 10_000)
        assertMana(frames, 5, 7_000, 10_000)
        assertMana(frames, 6, 7_000, 6_000)
        // A later frame must never mutate a previously captured playback snapshot.
        assertMana(frames, 3, 10_000, 10_000)
        assertEquals(7_000, events.first { it.sequence == 3 }.mpAfterUnits)
        assertEquals(6_000, events.first { it.sequence == 4 }.mpAfterUnits)
    }

    @Test
    fun `reversed action order follows the acting side and misses still pay`() {
        val frames = buildArenaLiveManaFrames(starts() + listOf(
            paid(3, user, 3_000),
            paid(4, opponent, 4_000),
            action(5, ArenaSupportEventType.ATTACK_MISS, opponent, user),
            action(6, ArenaSupportEventType.ATTACK_HIT, user, opponent),
        ))

        assertMana(frames, 4, 10_000, 10_000)
        assertMana(frames, 5, 10_000, 6_000)
        assertMana(frames, 6, 7_000, 6_000)
    }

    @Test
    fun `evaded attack reveals attacker payment even when both fighters share a cast id`() {
        val frames = buildArenaLiveManaFrames(starts() + listOf(
            paid(3, user, 3_000),
            paid(4, opponent, 4_000),
            // ATTACK_EVADED names the defender as actor and attacker as target.
            action(5, ArenaSupportEventType.ATTACK_EVADED, opponent, user),
            action(6, ArenaSupportEventType.ATTACK_HIT, opponent, user),
        ))

        assertMana(frames, 5, 7_000, 10_000)
        assertMana(frames, 6, 7_000, 6_000)
    }

    @Test
    fun `self targeted support reveals only its owners payment`() {
        val frames = buildArenaLiveManaFrames(starts() + listOf(
            paid(3, user, 3_000),
            paid(4, opponent, 4_000),
            action(5, ArenaSupportEventType.SUPPORT_APPLIED, user, user),
            action(6, ArenaSupportEventType.SUPPORT_APPLIED, opponent, opponent),
        ))

        assertMana(frames, 5, 7_000, 10_000)
        assertMana(frames, 6, 7_000, 6_000)
    }

    @Test
    fun `cleric healing payment accompanies the factual heal rather than its silent support event`() {
        val frames = buildArenaLiveManaFrames(starts() + listOf(
            paid(3, user, 3_000).copy(actionId = "ARENA_SUP_CLERIC_01"),
            action(4, ArenaSupportEventType.SUPPORT_APPLIED, user, user)
                .copy(actionId = "ARENA_SUP_CLERIC_01"),
            action(5, ArenaSupportEventType.HEAL_APPLIED, user, user)
                .copy(actionId = "ARENA_SUP_CLERIC_01", reason = "direct_heal", amount = 10.0),
        ))

        assertMana(frames, 3, 10_000, 10_000)
        assertMana(frames, 4, 10_000, 10_000)
        assertMana(frames, 5, 7_000, 10_000)
    }

    @Test
    fun `cleric support with no factual healing still reveals paid mana`() {
        val frames = buildArenaLiveManaFrames(starts() + listOf(
            paid(3, user, 3_000).copy(actionId = "ARENA_SUP_CLERIC_01"),
            action(4, ArenaSupportEventType.SUPPORT_APPLIED, user, user)
                .copy(actionId = "ARENA_SUP_CLERIC_01"),
        ))

        assertMana(frames, 4, 7_000, 10_000)
    }

    @Test
    fun `multi turn preparation pays immediately while instant opponent waits for its action`() {
        val frames = buildArenaLiveManaFrames(starts() + listOf(
            paid(3, user, 3_000, turns = 2),
            paid(4, opponent, 4_000),
            action(5, ArenaSupportEventType.CAST_PROGRESS, user, opponent),
            action(6, ArenaSupportEventType.ATTACK_HIT, opponent, user),
            action(7, ArenaSupportEventType.ATTACK_HIT, user, opponent).copy(turn = 2),
        ))

        assertMana(frames, 3, 7_000, 10_000)
        assertMana(frames, 5, 7_000, 10_000)
        assertMana(frames, 6, 7_000, 6_000)
        assertMana(frames, 7, 7_000, 6_000)
    }

    @Test
    fun `mana drain displays its own loss without revealing the victims pending skill cost`() {
        val frames = buildArenaLiveManaFrames(starts() + listOf(
            paid(3, user, 3_000),
            paid(4, opponent, 4_000),
            action(5, ArenaSupportEventType.ATTACK_HIT, user, opponent),
            ArenaSupportEvent(6, 1, ArenaSupportEventType.MP_DRAINED,
                actorId = opponent, targetId = user, mpBeforeUnits = 6_000, mpAfterUnits = 4_000,
                amount = 2.0, causeSequence = 5),
            action(7, ArenaSupportEventType.ATTACK_HIT, opponent, user),
        ))

        assertMana(frames, 5, 7_000, 10_000)
        assertMana(frames, 6, 7_000, 8_000)
        assertMana(frames, 7, 7_000, 4_000)
    }

    @Test
    fun `delay and sleep make pending payment visible when interrupted preparation is shown`() {
        for (type in listOf(ArenaSupportEventType.CAST_DELAYED, ArenaSupportEventType.CAST_PAUSED)) {
            val frames = buildArenaLiveManaFrames(starts() + listOf(
                paid(3, user, 3_000),
                action(4, type, user, opponent),
                action(5, ArenaSupportEventType.ATTACK_HIT, user, opponent).copy(turn = 2),
            ))

            assertMana(frames, 3, 10_000, 10_000)
            assertMana(frames, 4, 7_000, 10_000)
            assertMana(frames, 5, 7_000, 10_000)
        }
    }

    @Test
    fun `death cancellation retains paid mana even when skill never resolves`() {
        val frames = buildArenaLiveManaFrames(starts() + listOf(
            paid(3, user, 3_000),
            paid(4, opponent, 4_000),
            action(5, ArenaSupportEventType.ATTACK_HIT, user, opponent),
            ArenaSupportEvent(6, 1, ArenaSupportEventType.KO, actorId = opponent, targetId = user),
            action(7, ArenaSupportEventType.CAST_CANCELLED_KO, opponent, user)
                .copy(mpBeforeUnits = 6_000, mpAfterUnits = 6_000),
            ArenaSupportEvent(8, 1, ArenaSupportEventType.END, actorId = user),
        ))

        assertMana(frames, 5, 7_000, 10_000)
        assertMana(frames, 7, 7_000, 6_000)
        assertMana(frames, 8, 7_000, 6_000)
    }

    @Test
    fun `a free basic attack does not change either visible mana bar`() {
        val frames = buildArenaLiveManaFrames(starts() + listOf(
            paid(3, user, 0).copy(actionId = "BASIC_ATTACK"),
            action(4, ArenaSupportEventType.ATTACK_HIT, user, opponent)
                .copy(actionId = "BASIC_ATTACK"),
        ))

        assertMana(frames, 3, 10_000, 10_000)
        assertMana(frames, 4, 10_000, 10_000)
    }

    @Test
    fun `real class matchups keep final engine mana exact and frames reproducible`() {
        for (heroClass in HeroClass.entries) {
            val simulation = ArenaSupportTurnEngine.simulate(
                ArenaSupportQaFixtures.fighter(heroClass, 100, user, traitRank = 1),
                ArenaSupportQaFixtures.fighter(
                    HeroClass.entries[(heroClass.ordinal + 1) % HeroClass.entries.size],
                    100, opponent, traitRank = 1),
                41L,
            )
            val frames = buildArenaLiveManaFrames(simulation.events)
            val final = frames.getValue(simulation.events.last().sequence)

            assertEquals(frames, buildArenaLiveManaFrames(simulation.events))
            simulation.fighters.forEach { (id, fighter) ->
                assertEquals(fighter.mpUnits, final.getValue(id))
                assertTrue(frames.values.all { frame -> frame[id]?.let { it in 0..fighter.maxMpUnits } != false })
            }
        }
    }

    private fun starts() = listOf(
        ArenaSupportEvent(1, 0, ArenaSupportEventType.START, actorId = user,
            mpBeforeUnits = 10_000, mpAfterUnits = 10_000),
        ArenaSupportEvent(2, 0, ArenaSupportEventType.START, actorId = opponent,
            mpBeforeUnits = 10_000, mpAfterUnits = 10_000),
    )

    private fun paid(sequence: Int, actor: String, cost: Int, turns: Int = 1) =
        ArenaSupportEvent(sequence, 1, ArenaSupportEventType.CAST_START,
            actorId = actor, targetId = if (actor == user) opponent else user,
            actionId = "test_skill", castId = 1, castTurns = turns,
            mpBeforeUnits = 10_000, mpAfterUnits = 10_000 - cost, amount = cost / 1_000.0)

    private fun action(sequence: Int, type: ArenaSupportEventType, actor: String, target: String) =
        ArenaSupportEvent(sequence, 1, type, actorId = actor, targetId = target,
            actionId = "test_skill", castId = 1)

    private fun assertMana(frames: Map<Int, Map<String, Int>>, sequence: Int, userMp: Int, opponentMp: Int) {
        assertEquals("user MP at event $sequence", userMp, frames.getValue(sequence).getValue(user))
        assertEquals("opponent MP at event $sequence", opponentMp, frames.getValue(sequence).getValue(opponent))
    }
}
