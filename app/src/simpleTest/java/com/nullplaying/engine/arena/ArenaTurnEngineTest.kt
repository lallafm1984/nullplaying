package com.nullplaying.engine.arena

import com.nullplaying.model.HeroClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Core contracts only: these fixtures do not certify real-growth or six-class balance. */
class ArenaTurnEngineTest {
    @Test
    fun `identical inputs and seed reproduce mechanics and the complete event stream`() {
        val left = fighter("hero-a", HeroClass.WARRIOR)
        val right = fighter("hero-b", HeroClass.MAGE)

        val first = ArenaTurnEngine.simulate(left, right, 19_827L, rules())
        val second = ArenaTurnEngine.simulate(left, right, 19_827L, rules())

        assertEquals(first, second)
        assertCompleted(first)
    }

    @Test
    fun `event recording cannot change the winner health mana or telemetry`() {
        for (budget in ArenaAttackBudget.entries) {
            for (seed in listOf(0L, 29L, 804L)) {
                val left = fighter("left-owner", HeroClass.ROGUE)
                val right = fighter("right-owner", HeroClass.PALADIN)
                val rules = rules().copy(budget = budget)
                val recorded = ArenaTurnEngine.simulate(left, right, seed, rules, recordEvents = true)
                val silent = ArenaTurnEngine.simulate(left, right, seed, rules, recordEvents = false)

                assertTrue(silent.events.isEmpty())
                assertEquals(recorded.copy(events = emptyList()), silent)
                assertCompleted(recorded)
            }
        }
    }

    @Test
    fun `swapping screen sides preserves semantic fighter outcomes for every class`() {
        val classes = HeroClass.entries
        for ((index, heroClass) in classes.withIndex()) {
            val left = fighter("owner-z", heroClass)
            val right = fighter("owner-a", classes[(index + 1) % classes.size]).copy(
                stats = stats().copy(strength = 18.0, dexterity = 43.0, wisdom = 31.0),
            )
            for (seed in listOf(1L, 42L, 987_654L)) {
                val forward = ArenaTurnEngine.simulate(left, right, seed, rules())
                val reverse = ArenaTurnEngine.simulate(right, left, seed, rules())

                assertEquals("status for $heroClass seed=$seed", forward.status, reverse.status)
                assertEquals("winner for $heroClass seed=$seed", forward.winnerId, reverse.winnerId)
                assertEquals("turns for $heroClass seed=$seed", forward.turns, reverse.turns)
                assertEquals("fighter state for $heroClass seed=$seed", forward.fighters, reverse.fighters)
                assertCompleted(forward)
                assertCompleted(reverse)
            }
        }
    }

    @Test
    fun `replaying events reconstructs final hp and mp without hidden settlement damage`() {
        for (budget in ArenaAttackBudget.entries) {
            for (mpMode in ArenaMpMode.entries) {
                for (seed in listOf(17L, 608L)) {
                    val result = ArenaTurnEngine.simulate(
                        fighter("fighter", HeroClass.WARRIOR),
                        fighter("cleric", HeroClass.CLERIC),
                        seed,
                        rules().copy(budget = budget, mpMode = mpMode),
                    )
                    assertCompleted(result)
                    assertReplay(result)
                }
            }
        }
    }

    @Test
    fun `cast duration is global turns and one turn attacks finish in their starting turn`() {
        for (castTurns in 1..3) {
            val owned = attack("owned-$castTurns", tier = castTurns)
            val result = ArenaTurnEngine.simulate(
                fighter("caster", HeroClass.MAGE, listOf(owned)),
                fighter("target", HeroClass.WARRIOR, emptyList()),
                91L,
                longFightRules().copy(safetyTurnLimit = 20),
            )
            val starts = result.events.filter { it.type == ArenaEventType.CAST_START }
                .associateBy { castKey(it) }
            val completions = result.events.filter { it.type in completedAttackTypes }
            val skillCompletions = completions.filter { it.attackId == owned.id }

            assertTrue("fixture must exercise $castTurns-turn owned skill", skillCompletions.isNotEmpty())
            for (completion in completions) {
                val start = checkNotNull(starts[castKey(completion)])
                val expectedDuration = if (start.attackId == BASIC_ATTACK) 1 else castTurns
                assertEquals(expectedDuration, start.castTurns)
                assertEquals(start.turn + expectedDuration - 1, completion.turn)
                val expectedDamage = if (start.attackId == BASIC_ATTACK) 1.0 else listOf(1.25, 2.60, 4.05)[castTurns - 1]
                assertEquals("C1 pre-growth damage budget", expectedDamage, completion.amount, EPSILON)
            }
        }
    }

    @Test
    fun `completed skills wait two complete global turns before starting again`() {
        val owned = attack("one-turn-owned", tier = 1)
        val result = ArenaTurnEngine.simulate(
            fighter("caster", HeroClass.MAGE, listOf(owned)),
            fighter("target", HeroClass.WARRIOR, emptyList()),
            102L,
            longFightRules().copy(safetyTurnLimit = 24, cooldownTurns = 2),
        )
        val starts = result.events.filter {
            it.type == ArenaEventType.CAST_START && it.actorId == "caster" && it.attackId == owned.id
        }
        val completions = result.events.filter {
            it.type in completedAttackTypes && it.actorId == "caster" && it.attackId == owned.id
        }.associateBy { castKey(it) }

        assertTrue("fixture must reuse the owned skill", starts.size >= 2)
        starts.zipWithNext().forEach { (previous, next) ->
            val completed = checkNotNull(completions[castKey(previous)])
            assertTrue("a completion at E may next start at E+3", next.turn >= completed.turn + 3)
        }
    }

    @Test
    fun `casts spend their budget exactly once even when every attack misses`() {
        for (budget in ArenaAttackBudget.entries) {
            for (castTurns in 1..3) {
                // At exactly 0% accuracy all actions have zero value, so the selector correctly
                // prefers free basics. A bounded recorded-miss fixture still exercises paid casts.
                // Modest real mastery makes even the equal-efficiency C0 skill worth selecting.
                val owned = attack("skill-$castTurns", castTurns).copy(masteryBonusPercent = 50)
                val missRules = longFightRules().copy(
                    budget = budget, masteryScaling = true, hitChance = 0.01, safetyTurnLimit = 20,
                )
                val result = checkNotNull((1L..64L).asSequence().map { seed ->
                    ArenaTurnEngine.simulate(
                        fighter("caster", HeroClass.MAGE, listOf(owned)),
                        fighter("target", HeroClass.WARRIOR, emptyList()),
                        seed,
                        missRules,
                    )
                }.firstOrNull { candidate ->
                    candidate.events.none { it.type == ArenaEventType.ATTACK_HIT } &&
                        candidate.events.any { it.type == ArenaEventType.CAST_START && it.attackId == owned.id }
                }) { "could not find a paid all-miss fixture for $budget / $castTurns turns" }
                val starts = result.events.filter { it.type == ArenaEventType.CAST_START }
                assertTrue(starts.any { it.attackId == owned.id })
                for (start in starts) {
                    val expectedCost = when {
                        start.attackId == BASIC_ATTACK -> 0
                        budget == ArenaAttackBudget.C0 -> 8 * castTurns
                        else -> listOf(6, 8, 12)[castTurns - 1]
                    }
                    assertEquals(expectedCost, checkNotNull(start.mpBefore) - checkNotNull(start.mpAfter))
                }
                assertFalse(result.events.any { it.type == ArenaEventType.ATTACK_HIT })
                assertTrue(result.events.any { it.type == ArenaEventType.ATTACK_MISS })
                assertReplay(result)
            }
        }
    }

    @Test
    fun `zero mana switches to free basic attacks rather than causing defeat`() {
        // The published growth-log candidate maps raw MP 68 to 102: seventeen 6-MP casts.
        val caster = fighter("caster", HeroClass.MAGE, listOf(attack("six-mp", 1)))
            .copy(stats = stats().copy(rawMaxMana = 68.0))
        val result = ArenaTurnEngine.simulate(
            caster,
            fighter("target", HeroClass.WARRIOR, emptyList()),
            53L,
            longFightRules().copy(mpMode = ArenaMpMode.GROWTH_LOG),
        )
        val depletion = result.events.firstOrNull {
            it.type == ArenaEventType.CAST_START && it.actorId == caster.id &&
                it.mpBefore != null && it.mpBefore > 0 && it.mpAfter == 0
        }
        assertNotNull("fixture must reach actual zero MP, not merely unaffordable MP", depletion)
        val zeroAt = checkNotNull(depletion)
        assertTrue(result.events.any {
            it.type == ArenaEventType.CAST_START && it.actorId == caster.id &&
                it.sequence > zeroAt.sequence && it.attackId == BASIC_ATTACK &&
                it.mpBefore == 0 && it.mpAfter == 0
        })
        assertTrue(result.events.any {
            it.type in completedAttackTypes && it.actorId == caster.id && it.sequence > zeroAt.sequence
        })
        assertEquals(0, result.fighters.getValue(caster.id).mp)
        assertEquals(zeroAt.turn, result.fighters.getValue(caster.id).telemetry.firstMpDepletedTurn)
        assertCompleted(result)
        assertReplay(result)
    }

    @Test
    fun `having no owned skills still allows a complete battle using only free basics`() {
        val result = ArenaTurnEngine.simulate(
            fighter("a", HeroClass.CLERIC, emptyList()),
            fighter("b", HeroClass.RANGER, emptyList()),
            3L,
            rules(),
        )

        assertCompleted(result)
        assertTrue(result.events.filter { it.type == ArenaEventType.CAST_START }.all {
            it.attackId == BASIC_ATTACK && it.castTurns == 1 && it.mpBefore == it.mpAfter
        })
        result.fighters.values.forEach { state ->
            assertEquals(state.maxMp, state.mp)
            assertEquals(0, state.telemetry.mpSpent)
            assertEquals(0, state.telemetry.skillCompletions)
        }
        assertReplay(result)
    }

    @Test
    fun `lethal damage cancels the victims pending cast with no late attack or refund`() {
        val killer = fighter("killer", HeroClass.WARRIOR, emptyList()).copy(
            stats = ArenaCoreStats(10_000.0, 10_000.0, 10_000.0, 10_000.0,
                10_000.0, 10_000.0, 1.0, 68.0),
        )
        val victim = fighter("victim", HeroClass.MAGE, listOf(attack("long-spell", 3))).copy(
            stats = ArenaCoreStats(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 68.0),
        )
        val result = ArenaTurnEngine.simulate(
            killer,
            victim,
            31L,
            rules().copy(
                hitChance = 1.0,
                damageVariance = 0.0,
                formula = ArenaStatFormula(healthBase = 100.0, healthScale = 0.0,
                    attackBase = 1.0, attackScale = 10.0, defenseScale = 0.0),
            ),
        )

        assertCompleted(result)
        assertEquals(killer.id, result.winnerId)
        val start = checkNotNull(result.events.firstOrNull {
            it.type == ArenaEventType.CAST_START && it.actorId == victim.id && it.attackId == "long-spell"
        })
        val cancelled = result.events.filter {
            it.type == ArenaEventType.CAST_CANCELLED_KO && castKey(it) == castKey(start)
        }
        assertEquals(1, cancelled.size)
        assertFalse(result.events.any { it.type in completedAttackTypes && castKey(it) == castKey(start) })
        assertEquals(checkNotNull(start.mpAfter), result.fighters.getValue(victim.id).mp)
        assertEquals(1, result.fighters.getValue(victim.id).telemetry.koCancelledCasts)
        assertReplay(result)
    }

    @Test
    fun `a safety stop is explicitly aborted while both fighters remain alive`() {
        val result = ArenaTurnEngine.simulate(
            fighter("a", HeroClass.MAGE),
            fighter("b", HeroClass.PALADIN),
            43L,
            rules().copy(hitChance = 0.0, safetyTurnLimit = 3),
        )

        assertEquals(ArenaRunStatus.ABORTED_SAFETY_LIMIT, result.status)
        assertEquals(null, result.winnerId)
        assertEquals(3, result.turns)
        assertEquals(1, result.events.count { it.type == ArenaEventType.SAFETY_ABORT })
        assertEquals(ArenaEventType.SAFETY_ABORT, result.events.last().type)
        assertFalse(result.events.any { it.type == ArenaEventType.KO || it.type == ArenaEventType.BATTLE_END })
        result.fighters.values.forEach { assertEquals(it.maxHp, it.hp, EPSILON) }
        assertReplay(result)
    }

    @Test
    fun `a lethal hit on the last allowed turn still completes instead of safety aborting`() {
        val result = ArenaTurnEngine.simulate(
            fighter("a", HeroClass.WARRIOR, emptyList()),
            fighter("b", HeroClass.MAGE, emptyList()),
            49L,
            longFightRules().copy(safetyTurnLimit = 1,
                formula = ArenaStatFormula(healthBase = 1.0, healthScale = 0.0,
                    attackBase = 10.0, attackScale = 0.0, defenseScale = 0.0)),
        )

        assertEquals(1, result.turns)
        assertCompleted(result)
        assertReplay(result)
    }

    @Test
    fun `only actually owned attack ids or the free basic appear in a fighters events`() {
        val left = fighter("a", HeroClass.MAGE, listOf(attack("a-only", 2)))
        val right = fighter("b", HeroClass.CLERIC, listOf(attack("b-only", 3)))
        val beforeLeft = left.copy(attacks = left.attacks.toList())
        val beforeRight = right.copy(attacks = right.attacks.toList())
        val result = ArenaTurnEngine.simulate(left, right, 803L, rules())

        for (input in listOf(left, right)) {
            val allowed = input.attacks.map { it.id }.toSet() + BASIC_ATTACK
            val used = result.events.filter { it.actorId == input.id && it.attackId != null }
            assertTrue(used.isNotEmpty())
            assertTrue(used.all { it.attackId in allowed })
        }
        assertEquals(beforeLeft, left)
        assertEquals(beforeRight, right)
    }

    @Test
    fun `every raw stat rejects nonfinite or negative values while zero remains valid`() {
        val original = stats().values()
        for (index in original.indices) {
            for (invalid in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
                assertRejected("raw stat index=$index must reject $invalid") {
                    statsFrom(original.toMutableList().also { it[index] = invalid })
                }
            }
        }
        assertEquals(List(8) { 0.0 }, statsFrom(List(8) { 0.0 }).values())
    }

    @Test
    fun `owned inputs reject fake basic ids duplicate skills and future level skills`() {
        assertRejected("basic is a built-in action, not an owned skill") { attack(BASIC_ATTACK, 1) }
        val one = attack("one", 1)
        assertRejected("duplicate owned skill IDs") { fighter("a", attacks = listOf(one, one)) }
        assertRejected("tier 3 does not belong to a level 5 fighter") {
            fighter("a", attacks = listOf(attack("future", 3))).copy(level = 5L)
        }
        assertRejected("semantic fighter IDs must remain distinct when sides are swapped") {
            ArenaTurnEngine.simulate(fighter("same"), fighter("same", HeroClass.MAGE), 1L, rules())
        }
    }

    private fun assertCompleted(result: ArenaTurnResult) {
        assertEquals(ArenaRunStatus.COMPLETED, result.status)
        assertNotNull(result.winnerId)
        assertEquals(1, result.fighters.values.count { it.hp == 0.0 })
        assertTrue(result.fighters.getValue(checkNotNull(result.winnerId)).hp > 0.0)
        assertEquals(result.fighters.size, result.events.count { it.type == ArenaEventType.BATTLE_START })
        assertEquals(result.fighters.keys, result.events.filter { it.type == ArenaEventType.BATTLE_START }
            .map { checkNotNull(it.actorId) }.toSet())
        assertEquals(1, result.events.count { it.type == ArenaEventType.KO })
        assertEquals(1, result.events.count { it.type == ArenaEventType.BATTLE_END })
        assertEquals(ArenaEventType.BATTLE_START, result.events.first().type)
        assertEquals(ArenaEventType.BATTLE_END, result.events.last().type)
        assertFalse(result.events.any { it.type == ArenaEventType.SAFETY_ABORT })
        val koIndex = result.events.indexOfFirst { it.type == ArenaEventType.KO }
        assertFalse("no actor attacks, starts, or progresses after KO", result.events.drop(koIndex + 1).any {
            it.type in completedAttackTypes || it.type == ArenaEventType.CAST_START ||
                it.type == ArenaEventType.CAST_PROGRESS
        })
        val starts = result.events.filter { it.type == ArenaEventType.CAST_START }
        val terminals = result.events.filter {
            it.type in completedAttackTypes || it.type == ArenaEventType.CAST_CANCELLED_KO
        }.groupBy { castKey(it) }
        assertEquals("every cast must have one start", starts.size, starts.map { castKey(it) }.distinct().size)
        starts.forEach { start ->
            assertEquals("exactly one terminal result for ${castKey(start)}", 1, terminals[castKey(start)]?.size ?: 0)
        }
        assertEquals(starts.map { castKey(it) }.toSet(), terminals.keys)
    }

    private fun assertReplay(result: ArenaTurnResult) {
        val hp = result.fighters.mapValues { it.value.maxHp }.toMutableMap()
        val mp = result.fighters.mapValues { it.value.maxMp }.toMutableMap()
        val spent = result.fighters.mapValues { 0 }.toMutableMap()
        val dealt = result.fighters.mapValues { 0.0 }.toMutableMap()
        val completed = mutableSetOf<Pair<String, Int>>()
        val started = mutableSetOf<Pair<String, Int>>()
        result.events.zipWithNext().forEach { (previous, next) ->
            assertTrue("event turns never reverse", next.turn >= previous.turn)
            assertTrue("event sequence is unique and increasing", next.sequence > previous.sequence)
        }
        for (event in result.events) {
            if (event.type != ArenaEventType.CAST_START && event.mpBefore != null && event.mpAfter != null) {
                val actor = checkNotNull(event.actorId)
                assertEquals("MP cannot change outside cast start", mp.getValue(actor), event.mpBefore)
                assertEquals("MP is not refunded by misses or death", mp.getValue(actor), event.mpAfter)
            }
            when (event.type) {
                ArenaEventType.CAST_START -> {
                    val actor = checkNotNull(event.actorId)
                    assertTrue("dead actors cannot begin casts", hp.getValue(actor) > 0.0)
                    assertTrue(started.add(castKey(event)))
                    assertEquals(mp.getValue(actor), checkNotNull(event.mpBefore))
                    val after = checkNotNull(event.mpAfter)
                    assertTrue(after in 0..mp.getValue(actor))
                    spent[actor] = spent.getValue(actor) + mp.getValue(actor) - after
                    mp[actor] = after
                }
                ArenaEventType.ATTACK_HIT, ArenaEventType.ATTACK_MISS -> {
                    val actor = checkNotNull(event.actorId)
                    val target = checkNotNull(event.targetId)
                    assertTrue(hp.getValue(actor) > 0.0)
                    assertTrue(hp.getValue(target) > 0.0)
                    assertTrue(castKey(event) in started)
                    assertTrue("a cast cannot attack twice", completed.add(castKey(event)))
                    if (event.type == ArenaEventType.ATTACK_HIT) {
                        val before = checkNotNull(event.hpBefore)
                        val after = checkNotNull(event.hpAfter)
                        assertEquals(hp.getValue(target), before, EPSILON)
                        assertTrue(after.isFinite() && after >= 0.0 && after < before)
                        assertEquals(before - after, event.amount, EPSILON)
                        hp[target] = after
                        dealt[actor] = dealt.getValue(actor) + before - after
                    } else {
                        event.hpBefore?.let { assertEquals(hp.getValue(target), it, EPSILON) }
                        event.hpAfter?.let { assertEquals(hp.getValue(target), it, EPSILON) }
                        assertEquals(0.0, event.amount, EPSILON)
                    }
                }
                ArenaEventType.CAST_PROGRESS -> {
                    assertTrue(castKey(event) in started)
                    assertTrue(castKey(event) !in completed)
                    assertTrue(hp.getValue(checkNotNull(event.actorId)) > 0.0)
                    assertTrue(event.remainingTurns >= 0)
                }
                ArenaEventType.CAST_CANCELLED_KO -> {
                    assertTrue(castKey(event) in started)
                    assertTrue(completed.add(castKey(event)))
                    assertEquals(0.0, hp.getValue(checkNotNull(event.actorId)), EPSILON)
                }
                ArenaEventType.KO -> {
                    assertEquals(0.0, hp.getValue(checkNotNull(event.actorId)), EPSILON)
                    assertTrue(hp.getValue(checkNotNull(event.targetId)) > 0.0)
                }
                else -> Unit
            }
        }
        for ((id, state) in result.fighters) {
            assertEquals("visible HP must equal engine HP for $id", state.hp, hp.getValue(id), EPSILON)
            assertEquals("visible MP must equal engine MP for $id", state.mp, mp.getValue(id))
            assertEquals(state.telemetry.mpSpent, spent.getValue(id))
            assertEquals(state.telemetry.damageDealt, dealt.getValue(id), EPSILON)
            assertTrue(state.hp in 0.0..state.maxHp)
            assertTrue(state.mp in 0..state.maxMp)
        }
    }

    private fun rules() = ArenaTurnRules(mpMode = ArenaMpMode.FIXED_100)

    private fun longFightRules() = rules().copy(
        hitChance = 1.0,
        damageVariance = 0.0,
        tierScaling = false,
        masteryScaling = false,
        formula = ArenaStatFormula(healthBase = 200.0, healthScale = 0.0,
            attackBase = 1.0, attackScale = 0.0, defenseScale = 0.0),
    )

    private fun fighter(
        id: String,
        heroClass: HeroClass = HeroClass.WARRIOR,
        attacks: List<ArenaAttackInput> = listOf(attack("$id-short", 1), attack("$id-medium", 2), attack("$id-long", 3)),
    ) = ArenaFighterInput(id = id, heroClass = heroClass, level = 100L, stats = stats(), attacks = attacks)

    private fun attack(id: String, tier: Int) = ArenaAttackInput(
        id = id,
        name = "Owned $id",
        tier = tier,
        masteryBonusPercent = 0,
        sourceDamagePercentMin = 90 + (tier - 1) * 20,
        sourceDamagePercentMax = 100 + (tier - 1) * 20,
    )

    private fun stats() = ArenaCoreStats(25.0, 30.0, 27.0, 24.0, 28.0, 23.0, 400.0, 160.0)

    private fun statsFrom(values: List<Double>) = ArenaCoreStats(
        values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7],
    )

    private fun castKey(event: ArenaTurnEvent): Pair<String, Int> =
        checkNotNull(event.actorId) to checkNotNull(event.castId)

    private inline fun assertRejected(message: String, block: () -> Unit) {
        try {
            block()
            fail(message)
        } catch (_: IllegalArgumentException) {
            // A malformed input must fail before a battle is simulated.
        }
    }

    companion object {
        private const val BASIC_ATTACK = "BASIC_ATTACK"
        private const val EPSILON = 1e-8
        private val completedAttackTypes = setOf(ArenaEventType.ATTACK_HIT, ArenaEventType.ATTACK_MISS)
    }
}
