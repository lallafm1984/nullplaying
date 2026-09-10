package com.nullplaying.engine.arena

import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.engine.SkillCatalog
import com.nullplaying.model.HeroClass
import com.nullplaying.model.SimpleGameState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bounded growth/selection probes, not a win-rate study or a real player progression sample. */
class ArenaTurnGrowthContractTest {
    private val game = SimpleGameEngine()

    @Test
    fun `raising one raw stat cannot reduce hp mana or basic damage or increase incoming damage`() {
        // Basics, certain hits, and a surviving target separate formulas from AI/initiative/RNG.
        // The large fixed health floor avoids overkill clipping in this measurement fixture.
        for (heroClass in HeroClass.entries) {
            for (index in 0..7) {
                var previous: Probe? = null
                var first: Probe? = null
                for (value in listOf(0.0, 1.0, 10.0, 100.0, 1_000_000.0)) {
                    val varied = baseStats().values().toMutableList().also { it[index] = value }
                    val current = probe(input("subject", heroClass, stats = statsFrom(varied)))
                    previous?.let { before ->
                        val label = "$heroClass rawStat=$index value=$value"
                        assertTrue("HP regressed: $label", current.maxHp + EPSILON >= before.maxHp)
                        assertTrue("MP regressed: $label", current.maxMp >= before.maxMp)
                        assertTrue("basic damage regressed: $label", current.outgoing + EPSILON >= before.outgoing)
                        assertTrue("incoming damage increased: $label", current.incoming <= before.incoming + EPSILON)
                    }
                    if (first == null) first = current
                    previous = current
                }
                if (index == 7) {
                    assertTrue("raw MP growth must eventually rise above the floor",
                        checkNotNull(previous).maxMp > checkNotNull(first).maxMp)
                }
            }
        }
    }

    @Test
    fun `real growth crosses early unlock and post one hundred boundaries without losing stats or skills`() {
        for (heroClass in HeroClass.entries) {
            for (seed in listOf(13L, 91L)) {
                val state = freshState(heroClass, seed)
                val initialTotal = state.hero.stats.values().take(6).sum()
                var previousInput: ArenaFighterInput? = null
                var previousProbe: Probe? = null

                BOUNDARIES.forEachIndexed { index, level ->
                    growTo(state, level)
                    // Less than eleven milliseconds total: repair the actual catalog without
                    // replaying attacks or inventing high-level mastery.
                    game.settle(state, FIXED_TIME + index + 1L)
                    val built = ArenaTurnInputAdapter.fromState(state, "subject")
                    val current = built.fighter
                    val definitions = SkillCatalog.forClass(heroClass).filter { it.unlockLevel.toLong() <= level }
                    val expectedCount = minOf(20L, 1L + level / 5L).toInt()

                    assertTrue("$heroClass level=$level rejects=${built.rejectedSkills}", built.rejectedSkills.isEmpty())
                    assertEquals(level, current.level)
                    assertEquals(initialTotal + 2L * (level - 1L), state.hero.stats.values().take(6).sum())
                    assertEquals(expectedCount, current.attacks.size)
                    assertEquals(definitions.map { it.catalogId }, current.attacks.map { it.id })
                    assertEquals((1..expectedCount).toList(), current.attacks.map { it.tier })
                    assertEquals(state.hero.stats.values().map { it.toDouble() }, current.stats.values())
                    assertTrue(state.skills.all { it.acquiredAtLevel <= level && it.usageCount == 0L })
                    assertTrue(current.attacks.all { it.masteryBonusPercent == 0 })
                    previousInput?.let { previous ->
                        assertTrue(current.stats.values().zip(previous.stats.values()).all { (now, old) -> now >= old })
                        assertTrue(current.attacks.map { it.id }.containsAll(previous.attacks.map { it.id }))
                    }

                    // Remove attacks only in the stat measurement copy, never the owned snapshot.
                    val measured = probe(current.copy(attacks = emptyList()))
                    previousProbe?.let { previous ->
                        assertTrue("growth HP at $heroClass/$level", measured.maxHp + EPSILON >= previous.maxHp)
                        assertTrue("growth MP at $heroClass/$level", measured.maxMp >= previous.maxMp)
                        assertTrue("growth attack at $heroClass/$level", measured.outgoing + EPSILON >= previous.outgoing)
                        assertTrue("growth defense at $heroClass/$level", measured.incoming <= previous.incoming + EPSILON)
                    }
                    previousInput = current
                    previousProbe = measured
                }
                assertEquals(200L, state.hero.level)
                assertEquals(20, state.skills.size)
            }
        }
    }

    @Test
    fun `level metadata alone adds no hidden band or level one hundred combat multiplier`() {
        val rules = measurementRules().copy(safetyTurnLimit = 4)
        for (heroClass in HeroClass.entries) {
            val original = input("subject", heroClass, level = 1L, attacks = listOf(attack(1)))
            val target = input("target", HeroClass.WARRIOR)
            val reference = ArenaTurnEngine.simulate(original, target, 371L, rules)
            for (level in BOUNDARIES) {
                val result = ArenaTurnEngine.simulate(original.copy(level = level), target, 371L, rules)
                assertEquals("unchanged stats and ownership at $heroClass/$level", reference, result)
            }
        }
    }

    @Test
    fun `each of the 120 catalog attacks can be selected from its complete twenty skill owned pool`() {
        val rules = constantCombatRules().copy(masteryScaling = true, masteryGain = 1.0)
        for (heroClass in HeroClass.entries) {
            val state = freshState(heroClass, 41L)
            growTo(state, 95L)
            game.settle(state, FIXED_TIME + 1L)
            val built = ArenaTurnInputAdapter.fromState(state, "subject")
            assertTrue(built.rejectedSkills.isEmpty())
            assertEquals(20, built.fighter.attacks.size)

            for (preferred in built.fighter.attacks) {
                // A deliberately controlled mastery advantage makes every tier the best action.
                // Put it last in the supplied list to detect first-N/fixed selection truncation.
                // This is an optimizer contract fixture, not a claimed player mastery profile.
                val owned = built.fighter.attacks.filter { it.id != preferred.id }
                    .map { it.copy(masteryBonusPercent = 0) } + preferred.copy(masteryBonusPercent = 50)
                val subject = built.fighter.copy(attacks = owned)
                val result = ArenaTurnEngine.simulate(subject, input("target", HeroClass.WARRIOR), 63L, rules)
                val first = result.events.first {
                    it.type == ArenaEventType.CAST_START && it.actorId == subject.id
                }

                assertEquals("unscanned catalog candidate $heroClass/${preferred.id}", preferred.id, first.attackId)
                assertEquals(20, subject.attacks.size)
                assertTrue(result.events.filter { it.actorId == subject.id && it.attackId != null }.all {
                    it.attackId == BASIC_ATTACK || it.attackId in owned.map { skill -> skill.id }
                })
            }
        }
    }

    @Test
    fun `all twenty tiers retain their cast cycle and C0 C1 costs rather than only the first three`() {
        for (budget in ArenaAttackBudget.entries) {
            for (tier in 1..20) {
                val owned = attack(tier).copy(masteryBonusPercent = 50)
                val subject = input("subject", HeroClass.MAGE, level = 95L, attacks = listOf(owned))
                val rules = constantCombatRules().copy(budget = budget, masteryScaling = true, masteryGain = 1.0)
                val result = ArenaTurnEngine.simulate(subject, input("target", HeroClass.WARRIOR), 72L, rules)
                val start = result.events.first {
                    it.type == ArenaEventType.CAST_START && it.actorId == subject.id && it.attackId == owned.id
                }
                val hit = result.events.first {
                    it.type == ArenaEventType.ATTACK_HIT && it.actorId == subject.id && it.castId == start.castId
                }
                val cast = 1 + (tier - 1) % 3
                val expectedCost = if (budget == ArenaAttackBudget.C0) cast * 8 else listOf(6, 8, 12)[cast - 1]
                val unscaledUnits = if (budget == ArenaAttackBudget.C0) cast.toDouble() else listOf(1.25, 2.60, 4.05)[cast - 1]

                assertEquals("$budget T$tier cast", cast, start.castTurns)
                assertEquals(start.turn + cast - 1, hit.turn)
                assertEquals("$budget T$tier MP", expectedCost, checkNotNull(start.mpBefore) - checkNotNull(start.mpAfter))
                assertEquals("$budget T$tier damage", unscaledUnits * 1.5, hit.amount, EPSILON)
            }
        }
    }

    @Test
    fun `extreme valid stats produce finite bounded state or an explicit bounded abort`() {
        val magnitudes = listOf(0.0, Double.MIN_VALUE, 1.0, 1e12, Long.MAX_VALUE.toDouble(), 1e100, Double.MAX_VALUE)
        for (heroClass in HeroClass.entries) {
            for (magnitude in magnitudes) {
                val extremes = statsFrom(List(8) { magnitude })
                val rules = ArenaTurnRules(safetyTurnLimit = 3, hitChance = 1.0, damageVariance = 0.0)
                val result = ArenaTurnEngine.simulate(
                    input("subject", heroClass, stats = extremes),
                    input("target", HeroClass.WARRIOR, stats = extremes),
                    821L,
                    rules,
                )
                assertTrue(result.turns in 1..3)
                for (owner in result.fighters.values) {
                    assertTrue("$heroClass/$magnitude maxHP", owner.maxHp.isFinite() && owner.maxHp > 0.0)
                    assertTrue("$heroClass/$magnitude HP", owner.hp.isFinite() && owner.hp in 0.0..owner.maxHp)
                    assertTrue(owner.mp in 0..owner.maxMp)
                    assertTrue(owner.telemetry.damageDealt.isFinite() && owner.telemetry.damageDealt >= 0.0)
                    assertEquals(owner.maxMp - owner.mp, owner.telemetry.mpSpent)
                }
                for (event in result.events) {
                    assertTrue(event.amount.isFinite() && event.amount >= 0.0)
                    assertTrue(listOfNotNull(event.hpBefore, event.hpAfter).all { it.isFinite() && it >= 0.0 })
                }
                if (result.status == ArenaRunStatus.ABORTED_SAFETY_LIMIT) {
                    assertEquals(null, result.winnerId)
                    assertTrue(result.fighters.values.all { it.hp > 0.0 })
                    assertEquals(ArenaEventType.SAFETY_ABORT, result.events.last().type)
                } else {
                    assertTrue(result.winnerId in result.fighters.keys)
                    assertEquals(1, result.fighters.values.count { it.hp == 0.0 })
                    assertTrue(result.fighters.getValue(checkNotNull(result.winnerId)).hp > 0.0)
                }
            }
        }
    }

    @Test
    fun `sub ULP hits never report hp damage that floating point health did not lose`() {
        val huge = Long.MAX_VALUE.toDouble()
        val subject = input("subject", HeroClass.MAGE,
            stats = ArenaCoreStats(0.0, huge, 0.0, 0.0, 0.0, 0.0, huge, 0.0))
        val target = input("target", HeroClass.MAGE,
            stats = ArenaCoreStats(0.0, 0.0, 0.0, 0.0, huge, 0.0, huge, 0.0))
        val rules = ArenaTurnRules(
            safetyTurnLimit = 1, hitChance = 1.0, damageVariance = 0.0,
            formula = ArenaStatFormula(healthBase = 180.0, healthScale = 70.0,
                healthSurvivalShare = 0.5, attackBase = 8.0, attackScale = 5.0,
                attackOffenseShare = 0.5, defenseScale = 0.015),
        )
        val result = ArenaTurnEngine.simulate(subject, target, 302L, rules)
        val hits = result.events.filter { it.type == ArenaEventType.ATTACK_HIT }
        assertEquals(2, hits.size)
        assertTrue("fixture must exercise a rounded-away, sub-ULP hit", hits.any {
            it.actorId == subject.id && it.hpBefore == it.hpAfter
        })
        for (hit in hits) {
            assertEquals(checkNotNull(hit.hpBefore) - checkNotNull(hit.hpAfter), hit.amount, 0.0)
        }
        for ((id, owner) in result.fighters) {
            assertEquals(hits.filter { it.actorId == id }.sumOf { it.amount }, owner.telemetry.damageDealt, 0.0)
        }
    }

    private data class Probe(val maxHp: Double, val maxMp: Int, val outgoing: Double, val incoming: Double)

    private fun probe(subject: ArenaFighterInput): Probe {
        val result = ArenaTurnEngine.simulate(subject, input("target", HeroClass.WARRIOR), 8_031L, measurementRules())
        val owner = result.fighters.getValue(subject.id)
        assertTrue(result.fighters.values.all { it.hp > 0.0 })
        return Probe(owner.maxHp, owner.maxMp,
            result.events.single { it.type == ArenaEventType.ATTACK_HIT && it.actorId == subject.id }.amount,
            result.events.single { it.type == ArenaEventType.ATTACK_HIT && it.targetId == subject.id }.amount)
    }

    private fun measurementRules() = ArenaTurnRules(
        safetyTurnLimit = 1, hitChance = 1.0, damageVariance = 0.0,
        mpMode = ArenaMpMode.GROWTH_LOG,
        formula = ArenaStatFormula().copy(healthBase = 1_000_000.0),
    )

    private fun constantCombatRules() = ArenaTurnRules(
        safetyTurnLimit = 3, hitChance = 1.0, damageVariance = 0.0,
        mpMode = ArenaMpMode.FIXED_100, tierScaling = false, masteryScaling = false,
        formula = ArenaStatFormula(healthBase = 1_000.0, healthScale = 0.0,
            attackBase = 1.0, attackScale = 0.0, defenseScale = 0.0),
    )

    private fun freshState(heroClass: HeroClass, seed: Long): SimpleGameState {
        val roll = game.rollStats(seed, heroClass)
        return game.newGame("Growth contract", heroClass, roll.stats, roll.nextSeed, FIXED_TIME)
    }

    private fun growTo(state: SimpleGameState, level: Long) {
        while (state.hero.level < level) {
            state.rngState = game.applyClassGuidedGrowth(state.hero.stats, state.hero.heroClass, state.rngState)
            state.hero.level++
        }
    }

    private fun input(
        id: String,
        heroClass: HeroClass,
        level: Long = 1L,
        stats: ArenaCoreStats = baseStats(),
        attacks: List<ArenaAttackInput> = emptyList(),
    ) = ArenaFighterInput(id, heroClass, level, stats, attacks)

    private fun attack(tier: Int) = ArenaAttackInput("owned-tier-$tier", "Owned tier $tier", tier,
        masteryBonusPercent = 0, sourceDamagePercentMin = 90 + (tier - 1) * 20,
        sourceDamagePercentMax = 100 + (tier - 1) * 20)

    private fun baseStats() = ArenaCoreStats(25.0, 30.0, 27.0, 24.0, 28.0, 23.0, 400.0, 160.0)

    private fun statsFrom(values: List<Double>) = ArenaCoreStats(
        values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7],
    )

    companion object {
        private const val BASIC_ATTACK = "BASIC_ATTACK"
        private const val FIXED_TIME = 1_000L
        private const val EPSILON = 1e-6
        private val BOUNDARIES = listOf(1L, 4L, 5L, 9L, 10L, 94L, 95L, 100L, 101L, 200L)
    }
}
