package com.nullplaying.engine

import com.nullplaying.model.HeroClass
import com.nullplaying.model.PublicPlayerSnapshot
import com.nullplaying.model.PublicPlayerStats
import com.nullplaying.model.SHARED_PLAYER_RULES_VERSION
import com.nullplaying.model.SHARED_PLAYER_SNAPSHOT_VERSION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicPlayerBattleDerivationTest {
    @Test
    fun `level average is neutral and low high power corrections are bounded`() {
        assertEquals(1_000, PublicPlayerBattleDerivation.powerScalePermille(10L, 92L))
        assertEquals(800, PublicPlayerBattleDerivation.powerScalePermille(10L, 1L))
        assertEquals(1_200, PublicPlayerBattleDerivation.powerScalePermille(10L, 10_000L))
    }

    @Test
    fun `same public input derives deterministic level based average mastery`() {
        val snapshot = PublicPlayerSnapshot(
            projectionId = "11111111-1111-4111-8111-111111111111",
            displayName = "재현 영웅",
            heroClass = HeroClass.MAGE,
            level = 20L,
            combatPower = 192L,
            rulesVersion = SHARED_PLAYER_RULES_VERSION,
            snapshotVersion = SHARED_PLAYER_SNAPSHOT_VERSION,
            stats = PublicPlayerStats(10L, 11L, 12L, 23L, 24L, 15L, 300L, 210L),
            adventureTraitIds = emptyList(),
        )

        val first = PublicPlayerBattleDerivation.derive(snapshot)!!
        val second = PublicPlayerBattleDerivation.derive(snapshot)!!

        assertEquals(first, second)
        assertEquals(
            listOf(10L, 11L, 12L, 23L, 24L, 15L),
            first.stats.values().take(6),
        )
        assertTrue(first.stats.values().take(6).distinct().size > 1)
        val definitions = SkillCatalog.forClass(HeroClass.MAGE).filter { it.unlockLevel <= 20 }
        assertEquals(definitions.map { it.catalogId }, first.learnedSkills.map { it.catalogId })
        assertEquals(
            definitions.map { (20L - it.unlockLevel.toLong()).coerceIn(0L, 99L) * 100L },
            first.learnedSkills.map { it.usageCount },
        )
        assertEquals(listOf(20L, 16L, 11L, 6L, 1L), first.learnedSkills.map { it.level })
    }

    @Test
    fun `combat power correction preserves a character's stat profile instead of averaging it`() {
        val raw = PublicPlayerStats(8L, 11L, 16L, 25L, 20L, 13L, 300L, 210L)
        val lower = requireNotNull(PublicPlayerBattleDerivation.deriveStats(
            HeroClass.MAGE, 20L, 154L, raw.toHeroStatsForTest(),
        ))
        val higher = requireNotNull(PublicPlayerBattleDerivation.deriveStats(
            HeroClass.MAGE, 20L, 230L, raw.toHeroStatsForTest(),
        ))

        assertTrue(lower.intelligence > lower.wisdom)
        assertTrue(lower.wisdom > lower.dexterity)
        assertTrue(higher.intelligence > higher.wisdom)
        assertTrue(higher.wisdom > higher.dexterity)
        assertTrue(lower.values().take(6).distinct().size > 1)
        assertTrue(higher.values().take(6).distinct().size > 1)
    }

    @Test
    fun `local arena power differences survive both public power caps without changing public derivation`() {
        val raw = PublicPlayerStats(8L,11L,16L,25L,20L,13L,300L,210L).toHeroStatsForTest()
        for(power in listOf(100L,205L,400L,800L)) {
            val self=requireNotNull(PublicPlayerBattleDerivation.deriveStats(HeroClass.MAGE,21,power,raw))
            fun local(value:Long)=requireNotNull(PublicPlayerBattleDerivation.deriveLocalArenaStats(
                HeroClass.MAGE,21,value,power,raw))
            val low=local((power*9+9)/10); val equal=local(power); val high=local(power*11/10)
            assertEquals(self,equal)
            assertTrue(low.maxHealth<equal.maxHealth && high.maxHealth>equal.maxHealth)
            assertTrue(low.intelligence<equal.intelligence && high.intelligence>equal.intelligence)
            assertEquals(null,PublicPlayerBattleDerivation.deriveLocalArenaStats(HeroClass.MAGE,21,power*2,power,raw))
        }
        assertEquals(800,PublicPlayerBattleDerivation.powerScalePermille(21,100))
        assertEquals(1200,PublicPlayerBattleDerivation.powerScalePermille(21,800))
    }

    @Test
    fun `low level derivation is available only to the explicit offline QA path`() {
        val raw = PublicPlayerStats(10L, 11L, 12L, 13L, 14L, 15L, 30L, 20L)
            .toHeroStatsForTest()

        assertEquals(
            null,
            PublicPlayerBattleDerivation.deriveStats(HeroClass.WARRIOR, 9L, 82L, raw),
        )
        assertTrue(
            PublicPlayerBattleDerivation.deriveStats(
                HeroClass.WARRIOR,
                9L,
                82L,
                raw,
                allowLowLevelQa = true,
            ) != null,
        )
    }

    private fun PublicPlayerStats.toHeroStatsForTest() = com.nullplaying.model.HeroStats(
        strength, constitution, dexterity, intelligence, wisdom, charisma, maxHealth, maxMana,
    )
}
