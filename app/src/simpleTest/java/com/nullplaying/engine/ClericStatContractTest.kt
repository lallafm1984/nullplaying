package com.nullplaying.engine

import com.nullplaying.model.HeroClass
import com.nullplaying.model.HeroStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClericStatContractTest {
    private val engine = SimpleGameEngine()

    @Test
    fun `cleric uses wisdom first and charisma second without changing other classes`() {
        val expected = mapOf(
            HeroClass.WARRIOR to (0 to 1), HeroClass.ROGUE to (2 to 0),
            HeroClass.RANGER to (2 to 4), HeroClass.MAGE to (3 to 4),
            HeroClass.CLERIC to (4 to 5), HeroClass.PALADIN to (0 to 5),
        )
        expected.forEach { (heroClass, pair) ->
            assertEquals(pair.first, heroClass.primaryStatIndex)
            assertEquals(pair.second, heroClass.secondaryStatIndex)
        }
    }

    @Test
    fun `cleric offense and base proc use charisma rather than intelligence`() {
        val stats = HeroStats(10, 10, 10, 40, 35, 25, 100, 100)
        assertEquals(32L, engine.classOffenseAttribute(stats, HeroClass.CLERIC))
        assertEquals(32L, engine.classOffenseAttribute(stats.copy(intelligence = 1000), HeroClass.CLERIC))
        assertEquals(62L, engine.classOffenseAttribute(stats.copy(charisma = 125), HeroClass.CLERIC))
        val game = engine.newGame("로컬검증", HeroClass.CLERIC, stats, 321L, 0L)
        assertEquals(13, engine.baseSkillProcPercent(game))
        val actualBefore = engine.skillProcBasisPoints(game)
        game.hero.stats.intelligence = 1000
        assertEquals(13, engine.baseSkillProcPercent(game))
        assertTrue(engine.skillProcBasisPoints(game) > actualBefore)
        game.hero.stats.charisma = 125
        assertEquals(18, engine.baseSkillProcPercent(game))
    }

    @Test
    fun `cleric guided growth focuses wisdom or charisma while intelligence remains a random stat`() {
        val total = LongArray(6)
        repeat(1000) { seed ->
            val stats = HeroStats(12, 12, 12, 12, 12, 12, 20, 10)
            engine.applyClassGuidedGrowth(stats, HeroClass.CLERIC, seed.toLong() + 1L)
            val gains = stats.values().take(6).map { it - 12 }
            assertEquals(2L, gains.sum())
            assertTrue(gains[4] + gains[5] >= 1L)
            gains.forEachIndexed { index, gain -> total[index] += gain }
        }
        assertTrue(total[4] > total[3])
        assertTrue(total[5] > total[3])
        // CHA is not substituted into the shared INT/WIS resource formula.
        assertEquals(15L, engine.manaBaseAttribute(12L, 18L))
    }
}
