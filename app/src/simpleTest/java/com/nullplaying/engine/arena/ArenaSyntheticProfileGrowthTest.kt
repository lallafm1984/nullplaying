package com.nullplaying.engine.arena

import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.model.HeroClass
import com.nullplaying.model.HeroStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArenaSyntheticProfileGrowthTest {
    private val engine = SimpleGameEngine()

    @Test
    fun `same synthetic identity reproduces stats and continuation token`() {
        val first = baseStats()
        val second = baseStats()

        val firstContinuation = ArenaSyntheticProfileGrowth.grow(
            engine, first, HeroClass.RANGER, targetLevel = 100L, identitySeed = 91_337L,
        )
        val secondContinuation = ArenaSyntheticProfileGrowth.grow(
            engine, second, HeroClass.RANGER, targetLevel = 100L, identitySeed = 91_337L,
        )

        assertEquals(first, second)
        assertEquals(firstContinuation, secondContinuation)
        assertEquals(60L + 2L * 99L, first.values().take(BASE_STAT_COUNT).sum())
    }

    @Test
    fun `independent level seeds cannot lock long growth to one class focus`() {
        HeroClass.entries.forEach { heroClass ->
            repeat(8) { identity ->
                val stats = baseStats()
                ArenaSyntheticProfileGrowth.grow(
                    engine = engine,
                    stats = stats,
                    heroClass = heroClass,
                    targetLevel = 100L,
                    identitySeed = 0x51A7_0000L + heroClass.ordinal * 100L + identity,
                )
                val primary = stats.values()[heroClass.primaryStatIndex] - INITIAL_STAT
                val secondary = stats.values()[heroClass.secondaryStatIndex] - INITIAL_STAT
                val primaryShare = primary.toDouble() / (primary + secondary).toDouble()
                assertTrue(
                    "$heroClass identity=$identity focus share=$primaryShare stats=${stats.values()}",
                    primaryShare in 0.35..0.65,
                )
                assertEquals(
                    60L + 2L * 99L,
                    stats.values().take(BASE_STAT_COUNT).sum(),
                )
            }
        }
    }

    private fun baseStats() = HeroStats(
        strength = INITIAL_STAT,
        constitution = INITIAL_STAT,
        dexterity = INITIAL_STAT,
        intelligence = INITIAL_STAT,
        wisdom = INITIAL_STAT,
        charisma = INITIAL_STAT,
        maxHealth = 10L,
        maxMana = 10L,
    )

    private companion object {
        const val INITIAL_STAT = 10L
        const val BASE_STAT_COUNT = 6
    }
}
