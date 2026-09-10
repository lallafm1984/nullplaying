package com.nullplaying.engine.arena

import com.nullplaying.model.HeroClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArenaClassBalanceTest {
    @Test
    fun `normalization is deterministic bounded and interpolated`() {
        HeroClass.entries.forEach { heroClass ->
            (1..ArenaSkillTreeRules.maxArenaLevel).forEach { heroLevel ->
                (1..ArenaSkillTreeRules.maxArenaLevel).forEach { arenaLevel ->
                    val first = ArenaClassBalance.offenseMultiplier(
                        heroClass, heroLevel.toLong(), arenaLevel,
                    )
                    assertEquals(
                        first,
                        ArenaClassBalance.offenseMultiplier(
                            heroClass, heroLevel.toLong(), arenaLevel,
                        ),
                        0.0,
                    )
                    assertTrue(
                        "$heroClass hero=$heroLevel arena=$arenaLevel multiplier=$first",
                        first in ArenaClassBalance.MIN_OFFENSE_MULTIPLIER..
                            ArenaClassBalance.MAX_OFFENSE_MULTIPLIER,
                    )
                }
            }
        }
        assertEquals(1.05, ArenaClassBalance.offenseMultiplier(HeroClass.WARRIOR, 75L, 75), 1e-9)
        assertEquals(1.009, ArenaClassBalance.offenseMultiplier(HeroClass.MAGE, 25L, 25), 1e-9)
        assertEquals(1.030, ArenaClassBalance.offenseMultiplier(HeroClass.MAGE, 50L, 50), 1e-9)
        assertEquals(1.002, ArenaClassBalance.offenseMultiplier(HeroClass.CLERIC, 25L, 25), 1e-9)
        assertEquals(1.0, ArenaClassBalance.offenseMultiplier(HeroClass.CLERIC, 100L, 100), 0.0)
        // Keep the measured release extrema explicit instead of silently widening the guard.
        assertEquals(0.895, ArenaClassBalance.offenseMultiplier(HeroClass.RANGER, 100L, 100), 0.0)
        assertEquals(0.993, ArenaClassBalance.offenseMultiplier(HeroClass.ROGUE, 20L, 20), 0.0)
        assertEquals(0.984, ArenaClassBalance.offenseMultiplier(HeroClass.ROGUE, 25L, 25), 0.0)
        assertEquals(0.960, ArenaClassBalance.offenseMultiplier(HeroClass.ROGUE, 30L, 30), 0.0)
        assertEquals(1.043, ArenaClassBalance.offenseMultiplier(HeroClass.PALADIN, 30L, 30), 0.0)
    }

    @Test
    fun `late hero correction uses continuous arena and hero gap interpolation`() {
        assertEquals(1.0, ArenaClassBalance.offenseMultiplier(HeroClass.WARRIOR, 100L, 1), 0.0)
        val warriorArenaTenBase = ArenaClassBalance.baseOffenseMultiplier(HeroClass.WARRIOR, 10L)
        assertEquals(
            warriorArenaTenBase + 0.004 * 90.0 / 95.0,
            ArenaClassBalance.offenseMultiplier(HeroClass.WARRIOR, 100L, 10),
            1e-9,
        )
        assertEquals(
            1.0025 - 0.004 * 80.0 / 85.0,
            ArenaClassBalance.offenseMultiplier(HeroClass.WARRIOR, 100L, 20),
            1e-9,
        )
        assertEquals(1.0, ArenaClassBalance.offenseMultiplier(HeroClass.WARRIOR, 100L, 50), 1e-9)
        assertEquals(1.1, ArenaClassBalance.offenseMultiplier(HeroClass.WARRIOR, 100L, 100), 1e-9)

        // Arena progression beyond the hero's real growth cannot unlock a later correction band.
        assertEquals(1.0025, ArenaClassBalance.offenseMultiplier(HeroClass.WARRIOR, 20L, 100), 1e-9)
        assertEquals(0.985, ArenaClassBalance.offenseMultiplier(HeroClass.RANGER, 25L, 25), 0.0)
        assertEquals(
            0.985 - 0.020 * 75.0 / 80.0,
            ArenaClassBalance.offenseMultiplier(HeroClass.RANGER, 100L, 25),
            1e-9,
        )
        assertEquals(
            1.0 + 0.001 * 90.0 / 95.0,
            ArenaClassBalance.offenseMultiplier(HeroClass.RANGER, 100L, 10),
            1e-9,
        )
        assertEquals(
            1.0 - 0.004 * 90.0 / 95.0,
            ArenaClassBalance.offenseMultiplier(HeroClass.MAGE, 100L, 10),
            1e-9,
        )
        assertEquals(
            1.0 + 0.002 * 90.0 / 95.0,
            ArenaClassBalance.offenseMultiplier(HeroClass.CLERIC, 100L, 10),
            1e-9,
        )
        assertEquals(
            1.0 - 0.010 * 10.0 / 15.0 - 0.007,
            ArenaClassBalance.offenseMultiplier(HeroClass.ROGUE, 20L, 10),
            1e-9,
        )
        assertEquals(
            1.0 + 0.007 * 99.0 / 104.0,
            ArenaClassBalance.offenseMultiplier(HeroClass.CLERIC, 100L, 1),
            1e-9,
        )
    }

    @Test
    fun `profile adjustments are zero on equal axis and after the short level thirty bridge`() {
        HeroClass.entries.forEach { heroClass ->
            (1..ArenaSkillTreeRules.maxArenaLevel).forEach { heroLevel ->
                (1..ArenaSkillTreeRules.maxArenaLevel).forEach { arenaLevel ->
                    val effectiveLevel = minOf(heroLevel, arenaLevel).toLong()
                    val base = ArenaClassBalance.baseOffenseMultiplier(heroClass, effectiveLevel)
                    val actual = ArenaClassBalance.offenseMultiplier(
                        heroClass, heroLevel.toLong(), arenaLevel,
                    )
                    assertTrue(
                        "$heroClass hero=$heroLevel arena=$arenaLevel delta=${actual - base}",
                        kotlin.math.abs(actual - base) <=
                            ArenaClassBalance.MAX_PROFILE_ADJUSTMENT + 1e-12,
                    )
                    if (heroLevel == arenaLevel || arenaLevel >= 35) {
                        assertEquals(
                            "$heroClass hero=$heroLevel arena=$arenaLevel must use base curve",
                            base,
                            actual,
                            0.0,
                        )
                    }
                }
            }
        }
        assertTrue(
            ArenaClassBalance.offenseMultiplier(HeroClass.CLERIC, 100L, 25) >
                ArenaClassBalance.baseOffenseMultiplier(HeroClass.CLERIC, 25L),
        )
        assertTrue(
            ArenaClassBalance.offenseMultiplier(HeroClass.RANGER, 100L, 25) <
                ArenaClassBalance.baseOffenseMultiplier(HeroClass.RANGER, 25L),
        )
        assertEquals(
            ArenaClassBalance.baseOffenseMultiplier(HeroClass.WARRIOR, 30L),
            ArenaClassBalance.offenseMultiplier(HeroClass.WARRIOR, 30L, 30),
            0.0,
        )
        assertEquals(
            ArenaClassBalance.baseOffenseMultiplier(HeroClass.WARRIOR, 30L) -
                0.00425 * 70.0 / 75.0,
            ArenaClassBalance.offenseMultiplier(HeroClass.WARRIOR, 100L, 30),
            1e-9,
        )
        assertEquals(
            ArenaClassBalance.baseOffenseMultiplier(HeroClass.WARRIOR, 35L),
            ArenaClassBalance.offenseMultiplier(HeroClass.WARRIOR, 100L, 35),
            0.0,
        )
        assertEquals(
            ArenaClassBalance.baseOffenseMultiplier(HeroClass.ROGUE, 19L) - 0.010 / 6.0,
            ArenaClassBalance.offenseMultiplier(HeroClass.ROGUE, 19L, 20),
            1e-9,
        )
        assertEquals(
            ArenaClassBalance.baseOffenseMultiplier(HeroClass.WARRIOR, 1L) - 0.003,
            ArenaClassBalance.offenseMultiplier(HeroClass.WARRIOR, 10L, 1),
            1e-9,
        )
        assertEquals(
            ArenaClassBalance.baseOffenseMultiplier(HeroClass.ROGUE, 1L) -
                0.004 * 9.0 / 14.0 - 0.004,
            ArenaClassBalance.offenseMultiplier(HeroClass.ROGUE, 10L, 1),
            1e-9,
        )
        assertEquals(
            ArenaClassBalance.baseOffenseMultiplier(HeroClass.ROGUE, 1L) -
                0.004 * 99.0 / 104.0 + 0.004,
            ArenaClassBalance.offenseMultiplier(HeroClass.ROGUE, 100L, 1),
            1e-9,
        )
    }
}
