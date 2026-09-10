package com.nullplaying.ui

import com.nullplaying.engine.SkillCatalog
import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.engine.arena.ArenaRunStatus
import com.nullplaying.engine.arena.ArenaSupportEventType
import com.nullplaying.engine.arena.ArenaSupportEventText
import com.nullplaying.engine.arena.ArenaSupportQaFixtures
import com.nullplaying.engine.arena.ArenaSupportResult
import com.nullplaying.engine.arena.ArenaSupportTurnEngine
import com.nullplaying.model.HeroClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ArenaLiveBattleTimelineTest {
    private val names = linkedMapOf("user" to "내영웅", "opponent" to "상대닉네임")

    @Test
    fun `every class reconstructs exact final HP MP shield and result follows visible zero`() {
        for (heroClass in HeroClass.entries) {
            for (level in listOf(10, 30, 100)) {
                for (seed in listOf(1L, 41L, 91L)) {
                    val simulation = battle(heroClass, HeroClass.entries[(heroClass.ordinal + 1) % HeroClass.entries.size], seed, level)
                    val timeline = buildArenaLiveTimeline(simulation, names)
                    val first = timeline.beats.first()
                    val introduction = checkNotNull(first.message)
                    assertEquals(1_500L, first.durationMillis)
                    assertTrue(introduction.contains(names.getValue("user")))
                    assertTrue(introduction.contains(names.getValue("opponent")))
                    assertTrue(first.before.values.all { it.hpFraction == 1f && it.mpFraction == 1f })
                    val terminal = timeline.beats.last()
                    assertTrue(terminal.terminal)
                    assertEquals(ArenaSupportEventType.END, terminal.type)
                    for ((id, actual) in simulation.fighters) {
                        val shown = terminal.after.getValue(id)
                        assertEquals(actual.hp, shown.hp, 0.000001)
                        assertEquals(actual.mpUnits, shown.mpUnits)
                        assertEquals(actual.shield, shown.shield, 0.000001)
                    }
                    assertTrue(terminal.before.filterKeys { it != simulation.winnerId }.values.all { it.hp == 0.0 })
                    val lethal = timeline.beats.first { beat ->
                        beat.type == ArenaSupportEventType.ATTACK_HIT && beat.after.values.any { it.hp == 0.0 }
                    }
                    assertEquals(ARENA_LIVE_TURN_MILLIS, lethal.durationMillis)
                    assertNotNull(lethal.message)
                    assertFalse(timeline.beats.drop(timeline.beats.indexOf(lethal) + 1).any {
                        it.type in setOf(ArenaSupportEventType.ATTACK_HIT, ArenaSupportEventType.ATTACK_MISS,
                            ArenaSupportEventType.CAST_START, ArenaSupportEventType.ATTACK_EVADED)
                    })
                }
            }
        }
    }

    @Test
    fun `shield absorption and residual HP damage are one impact not two animations`() {
        val simulation = (1L..30L).asSequence().map { battle(HeroClass.MAGE, HeroClass.WARRIOR, it) }
            .first { it.events.any { event -> event.type == ArenaSupportEventType.SHIELD_ABSORBED } }
        val timeline = buildArenaLiveTimeline(simulation, names)
        val absorptions = simulation.events.filter { it.type == ArenaSupportEventType.SHIELD_ABSORBED }
        for (absorption in absorptions) {
            val beat = timeline.beats.single { absorption.sequence in it.sequences }
            assertEquals(ArenaSupportEventType.ATTACK_HIT, beat.type)
            assertEquals(ARENA_LIVE_TURN_MILLIS, beat.durationMillis)
            val owner = checkNotNull(absorption.actorId)
            assertEquals(absorption.shieldBefore!!, beat.before.getValue(owner).shield, 0.000001)
            assertEquals(absorption.shieldAfter!!, beat.after.getValue(owner).shield, 0.000001)
            assertTrue(beat.logs.any { it.sequence == absorption.sequence })
        }
        assertFalse(timeline.beats.any { it.type == ArenaSupportEventType.SHIELD_ABSORBED })
    }

    @Test
    fun `cast payment is applied exactly once and miss never changes HP`() {
        val simulation = battle(HeroClass.ROGUE, HeroClass.CLERIC, 41L, 30)
        val timeline = buildArenaLiveTimeline(simulation, names)
        var payments = 0
        timeline.beats.forEach { beat ->
            for (id in names.keys) {
                val spent = beat.before.getValue(id).mpUnits - beat.after.getValue(id).mpUnits
                if (spent != 0) {
                    assertTrue(beat.type in setOf(ArenaSupportEventType.CAST_START,
                        ArenaSupportEventType.ATTACK_HIT, ArenaSupportEventType.ATTACK_MISS,
                        ArenaSupportEventType.ATTACK_EVADED, ArenaSupportEventType.SUPPORT_APPLIED,
                        ArenaSupportEventType.HEAL_APPLIED, ArenaSupportEventType.CAST_DELAYED,
                        ArenaSupportEventType.CAST_PAUSED, ArenaSupportEventType.CAST_CANCELLED_KO))
                    assertTrue(spent > 0)
                    assertTrue(beat.durationMillis >= ARENA_LIVE_GAUGE_MOTION_MILLIS)
                    payments += spent
                }
                if (beat.type == ArenaSupportEventType.ATTACK_MISS || beat.type == ArenaSupportEventType.ATTACK_EVADED) {
                    assertEquals(beat.before.getValue(id).hp, beat.after.getValue(id).hp, 0.0)
                }
            }
        }
        val ledgerSpent = simulation.events.filter { it.type == ArenaSupportEventType.CAST_START }
            .sumOf { (it.mpBeforeUnits ?: 0) - (it.mpAfterUnits ?: 0) }
        assertEquals(ledgerSpent, payments)
        assertTrue(payments > 0)
    }

    @Test
    fun `arena action turn uses the exact main battle attack time`() {
        assertEquals(SimpleGameEngine.ATTACK_PRESENTATION_MILLIS, ARENA_LIVE_TURN_MILLIS)
        val timeline = buildArenaLiveTimeline(battle(HeroClass.WARRIOR, HeroClass.MAGE, 41L, 20), names)
        val fullTurnTypes = setOf(
            ArenaSupportEventType.ATTACK_HIT,
            ArenaSupportEventType.ATTACK_MISS,
            ArenaSupportEventType.ATTACK_EVADED,
            ArenaSupportEventType.HEAL_APPLIED,
        )
        val actionBeats = timeline.beats.filter { it.type in fullTurnTypes }
        assertTrue(actionBeats.isNotEmpty())
        actionBeats.forEach { beat -> assertEquals(ARENA_LIVE_TURN_MILLIS, beat.durationMillis) }
    }

    @Test
    fun `HP and MP share the same clamped gauge motion`() {
        assertEquals(.8f, arenaLiveGaugeFraction(.8f, .2f, 0L), .00001f)
        assertEquals(.5f, arenaLiveGaugeFraction(.8f, .2f, 60L), .00001f)
        assertEquals(.2f, arenaLiveGaugeFraction(.8f, .2f, 120L), .00001f)
        assertEquals(.2f, arenaLiveGaugeFraction(.8f, .2f, 1_500L), .00001f)
        assertEquals(.5f, arenaLiveGaugeFraction(Float.NaN, 1f, 60L), .00001f)
    }

    @Test
    fun `skill target HP shield and tint wait for the authored contact`() {
        val skill = checkNotNull(SkillCatalog.find("warrior_t01_c01"))
        val firstHit = skill.hitTimingsMillis.first().toLong()
        val finish = arenaLiveGaugeMotionMillis(skill)
        assertEquals(.8f, arenaLiveDisplayedGaugeFraction(.8f, .2f, firstHit - 1, skill), .00001f)
        assertEquals(.8f, arenaLiveDisplayedGaugeFraction(.8f, .2f, firstHit, skill), .00001f)
        val duringContact = arenaLiveDisplayedGaugeFraction(.8f, .2f, firstHit + 48, skill)
        assertTrue(duringContact < .8f && duringContact > .2f)
        assertEquals(.2f, arenaLiveDisplayedGaugeFraction(.8f, .2f, finish, skill), .00001f)
        assertEquals(0f, arenaLiveImpactTintAlpha(firstHit - 1, skill), .00001f)
        assertEquals(.25f, arenaLiveImpactTintAlpha(firstHit, skill), .00001f)
        assertEquals(.125f, arenaLiveImpactTintAlpha(firstHit + 120, skill), .00001f)
        assertEquals(0f, arenaLiveImpactTintAlpha(firstHit + 240, skill), .00001f)
    }

    @Test
    fun `shared playback clock freezes for background dialog and inspection pause`() {
        assertEquals(32L, arenaLivePlaybackClockDelta(true, false, false, 50L))
        assertEquals(16L, arenaLivePlaybackClockDelta(true, false, false, 16L))
        assertEquals(0L, arenaLivePlaybackClockDelta(false, false, false, 16L))
        assertEquals(0L, arenaLivePlaybackClockDelta(true, true, false, 16L))
        assertEquals(0L, arenaLivePlaybackClockDelta(true, false, true, 16L))
    }

    @Test
    fun `instant MP payment waits for its own action while free basic cast does not pause`() {
        val simulation = battle(HeroClass.WARRIOR, HeroClass.MAGE, 41L, 20)
        val timeline = buildArenaLiveTimeline(simulation, names)
        val paid = simulation.events.first { event ->
            event.type == ArenaSupportEventType.CAST_START && event.castTurns <= 1 &&
                event.mpBeforeUnits != event.mpAfterUnits && ArenaSupportEventText.text(event, names) == null
        }
        val cast = timeline.beats.single { paid.sequence in it.sequences }
        assertEquals(0L, cast.durationMillis)
        assertEquals(cast.before, cast.after)
        val owner = checkNotNull(paid.actorId)
        val action = timeline.beats.first { it.sequences.firstOrNull()?.let { sequence -> sequence > paid.sequence } == true &&
            it.before.getValue(owner).mpUnits != it.after.getValue(owner).mpUnits }
        assertTrue(action.durationMillis >= ARENA_LIVE_GAUGE_MOTION_MILLIS)
        assertEquals(paid.mpBeforeUnits, action.before.getValue(owner).mpUnits)
        assertEquals(paid.mpAfterUnits, action.after.getValue(owner).mpUnits)
        simulation.events.filter {
            it.type == ArenaSupportEventType.CAST_START && it.actionId == "BASIC_ATTACK" &&
                it.mpBeforeUnits == it.mpAfterUnits
        }.forEach { event ->
            assertEquals(0L, timeline.beats.single { event.sequence in it.sequences }.durationMillis)
        }
    }

    @Test
    fun `every resolved basic attack has a localized stage message and one log entry`() {
        val simulation = battle(HeroClass.ROGUE, HeroClass.MAGE, 41L)
        val basicResults = simulation.events.filter {
            it.actionId == "BASIC_ATTACK" && it.type in setOf(
                ArenaSupportEventType.ATTACK_HIT,
                ArenaSupportEventType.ATTACK_MISS,
                ArenaSupportEventType.ATTACK_EVADED,
            )
        }
        assertTrue(basicResults.isNotEmpty())
        assertTrue(basicResults.any { event ->
            event.type == ArenaSupportEventType.ATTACK_HIT && event.amount > 0.0 && event.hpAfter != 0.0
        })
        for ((language, label) in listOf("ko" to "공격", "en" to "basic attack", "ja" to "通常攻撃")) {
            val timeline = buildArenaLiveTimeline(simulation, names, language)
            basicResults.forEach { event ->
                val text = checkNotNull(ArenaSupportEventText.text(event, names, language))
                val beat = timeline.beats.single { event.sequence in it.sequences }
                assertTrue(text.contains(label))
                if (language == "ko") {
                    assertFalse(text.contains("일반 공격"))
                    assertTrue(
                        Regex("^(내영웅|상대닉네임)의 공격이 (빗나갔다\\.|(내영웅|상대닉네임)에게 (적중했다\\.|체력 피해를 주지 못했다\\.))$")
                            .matches(text) ||
                            Regex("^(내영웅|상대닉네임)[이가] (내영웅|상대닉네임)의 공격을 피했다\\.$")
                                .matches(text),
                    )
                }
                assertTrue(beat.message.orEmpty().contains(text))
                assertEquals(text, beat.logs.single { it.sequence == event.sequence }.text)
                assertNull(beat.skill)
            }
        }

        val timeline = buildArenaLiveTimeline(simulation, names)
        simulation.events.filter { it.type == ArenaSupportEventType.ATTACK_HIT && it.actionId != "BASIC_ATTACK" }
            .forEach { event ->
                val beat = timeline.beats.single { event.sequence in it.sequences }
                val skill = checkNotNull(event.actionId?.let(SkillCatalog::find))
                assertTrue(beat.message.orEmpty().contains(skill.name))
                assertTrue(beat.message.orEmpty().contains(names.getValue(checkNotNull(event.actorId))))
                assertEquals(
                    ArenaLiveSkillPresentation(event.sequence, skill.catalogId,
                        checkNotNull(event.actorId), checkNotNull(event.targetId)),
                    beat.skill,
                )
            }
        simulation.events.filter { it.type in setOf(ArenaSupportEventType.ATTACK_MISS, ArenaSupportEventType.ATTACK_EVADED) }
            .forEach { event ->
                assertNull(timeline.beats.single { event.sequence in it.sequences }.skill)
            }
    }

    @Test
    fun `snapshot chains remain exact across an arbitrary playback restart`() {
        val timeline = buildArenaLiveTimeline(battle(HeroClass.MAGE, HeroClass.PALADIN, 91L, 30), names)
        timeline.beats.zipWithNext().forEach { (prior, next) -> assertEquals(prior.after, next.before) }
        for (restart in timeline.beats.indices) {
            assertEquals(timeline.beats.last().after, timeline.beats.drop(restart).last().after)
        }
    }

    @Test
    fun `malformed terminal result and post KO attacks are rejected rather than forced to zero`() {
        val simulation = battle(HeroClass.MAGE, HeroClass.ROGUE, 41L)
        assertRejected(simulation.copy(status = ArenaRunStatus.ABORTED_SAFETY_LIMIT, winnerId = null))
        assertRejected(simulation.copy(events = simulation.events.filter { it.type != ArenaSupportEventType.KO }))
        assertRejected(simulation.copy(events = simulation.events.filter { it.type != ArenaSupportEventType.END }))
        val loser = simulation.fighters.keys.single { it != simulation.winnerId }
        assertRejected(simulation.copy(fighters = simulation.fighters + (loser to simulation.fighters.getValue(loser).copy(hp = 2.0))))
        val end = simulation.events.last()
        val forged = end.copy(type = ArenaSupportEventType.CAST_START, actorId = loser, targetId = simulation.winnerId)
        assertRejected(simulation.copy(events = simulation.events.dropLast(1) + forged + end.copy(sequence = end.sequence + 1)))
    }

    @Test
    fun `opening and terminal messages support all three languages without generic opponent`() {
        val simulation = battle(HeroClass.CLERIC, HeroClass.ROGUE, 91L)
        for (language in listOf("ko", "en", "ja")) {
            val timeline = buildArenaLiveTimeline(simulation, names, language)
            val introduction = timeline.beats.first().message.orEmpty()
            assertTrue(introduction.contains(names.getValue("user")))
            assertTrue(introduction.contains(names.getValue("opponent")))
            assertTrue(timeline.beats.last().message.orEmpty().contains(names.getValue(checkNotNull(simulation.winnerId))))
        }
    }

    @Test
    fun `live log excludes the active beat until that beat is complete`() {
        val timeline = buildArenaLiveTimeline(battle(HeroClass.WARRIOR, HeroClass.MAGE, 41L, 20), names)
        val activeIndex = timeline.beats.indexOfFirst { it.logs.isNotEmpty() }
        assertTrue(activeIndex >= 0)

        val visible = arenaLiveVisibleLogEntries(timeline, activeIndex, finished = false)
        val completedSequences = timeline.beats.take(activeIndex).flatMap { it.logs }.map { it.sequence }.asReversed()
        val activeSequences = timeline.beats[activeIndex].logs.map { it.sequence }

        assertEquals(completedSequences, visible.map { it.sequence })
        assertTrue(visible.none { it.sequence in activeSequences })
    }

    @Test
    fun `resolved basic attack moves from stage message into the completed log exactly once`() {
        val simulation = battle(HeroClass.ROGUE, HeroClass.MAGE, 41L)
        val basicSequence = simulation.events.first { event ->
            event.actionId == "BASIC_ATTACK" && event.type in setOf(
                ArenaSupportEventType.ATTACK_HIT,
                ArenaSupportEventType.ATTACK_MISS,
                ArenaSupportEventType.ATTACK_EVADED,
            )
        }.sequence
        val timeline = buildArenaLiveTimeline(simulation, names)
        val basicIndex = timeline.beats.indexOfFirst { beat -> basicSequence in beat.sequences }
        assertTrue(basicIndex >= 0)
        val basicBeat = timeline.beats[basicIndex]
        val basicLog = basicBeat.logs.single { it.sequence == basicSequence }
        assertTrue(basicBeat.message.orEmpty().contains("공격"))
        assertTrue(basicLog.text.contains("공격"))
        assertFalse(basicBeat.message.orEmpty().contains("일반 공격"))
        assertFalse(basicLog.text.contains("일반 공격"))

        assertTrue(arenaLiveVisibleLogEntries(timeline, basicIndex, finished = false)
            .none { it.sequence == basicLog.sequence })
        val settled = arenaLiveVisibleLogEntries(timeline, basicIndex + 1, finished = false)
            .filter { it.sequence == basicLog.sequence }
        assertEquals(1, settled.size)
        assertEquals(basicLog.text, settled.single().text)
    }

    @Test
    fun `finished live log contains the complete timeline newest first`() {
        val timeline = buildArenaLiveTimeline(battle(HeroClass.ROGUE, HeroClass.CLERIC, 91L, 30), names)

        val visible = arenaLiveVisibleLogEntries(timeline, playbackIndex = 0, finished = true)

        assertEquals(timeline.logs.map { it.sequence }.asReversed(), visible.map { it.sequence })
        assertEquals(timeline.logs.map { it.text }.asReversed(), visible.map { it.text })
        assertEquals(timeline.logs.last().text, visible.first().text)
    }

    @Test
    fun `visible log ordinals stay continuous when raw ledger sequences skip`() {
        val timeline = ArenaLiveTimeline(
            beats = listOf(
                ArenaLiveBeat(1, null, listOf(4), emptyMap(), emptyMap(), null,
                    listOf(ArenaLiveLogLine(4, "first")), 0L),
                ArenaLiveBeat(2, null, listOf(19), emptyMap(), emptyMap(), null,
                    listOf(ArenaLiveLogLine(19, "second")), 0L),
                ArenaLiveBeat(3, null, listOf(42), emptyMap(), emptyMap(), null,
                    listOf(ArenaLiveLogLine(42, "third")), 0L),
            ),
        )

        val visible = arenaLiveVisibleLogEntries(timeline, playbackIndex = 3, finished = false)

        assertEquals(listOf(42, 19, 4), visible.map { it.sequence })
        assertEquals(listOf(3, 2, 1), visible.map { it.ordinal })
        assertEquals(listOf("third", "second", "first"), visible.map { it.text })
    }

    private fun battle(left: HeroClass, right: HeroClass, seed: Long, level: Int = 10): ArenaSupportResult =
        ArenaSupportTurnEngine.simulate(
            ArenaSupportQaFixtures.fighter(left, level, "user", traitRank = 1),
            ArenaSupportQaFixtures.fighter(right, level, "opponent", traitRank = 1), seed,
        )

    private fun assertRejected(simulation: ArenaSupportResult) {
        try {
            buildArenaLiveTimeline(simulation, names)
            fail("Corrupt arena ledger must not produce a presentation result")
        } catch (_: IllegalArgumentException) {
            // Expected validation boundary.
        }
    }
}
