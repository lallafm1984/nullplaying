package com.alarmquest.ui

import com.alarmquest.R
import com.alarmquest.engine.SkillCatalog
import com.alarmquest.model.HeroClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SkillVfxAssetsBandRolesTest {
    private val fallback = ClassBandRoleAssets(-1, -1, -2, -3)

    @Test
    fun `ranger p0 cells use distinct low mid high vfx5 primary pairs`() {
        listOf(0, 1, 2, 3, 4).forEach { candidate ->
            val packs = listOf(0, 2, 4).map { band ->
                AuthoredClassBandRoleAssetResolver.resolve(
                    ClassCandidateBandKey(HeroClass.RANGER, candidate, band),
                    testIdentity(HeroClass.RANGER, candidate, band),
                    fallback,
                )
            }
            assertEquals(3, packs.map { it.primaryA }.distinct().size)
            assertEquals(3, packs.map { it.primaryB }.distinct().size)
            packs.forEach { pack ->
                assertNotEquals(pack.primaryA, pack.primaryB)
                assertEquals(pack.primaryA, pack.finisherRing)
                assertEquals(fallback.finisherEcho, pack.finisherEcho)
            }
        }
    }

    @Test
    fun `cleric p0 cells use distinct low mid high vfx5 role packs`() {
        listOf(0, 1, 2, 3, 4).forEach { candidate ->
            val packs = listOf(0, 2, 4).map { band ->
                AuthoredClassBandRoleAssetResolver.resolve(
                    ClassCandidateBandKey(HeroClass.CLERIC, candidate, band),
                    testIdentity(HeroClass.CLERIC, candidate, band),
                    fallback,
                )
            }
            assertEquals(3, packs.map { it.primaryA }.distinct().size)
            assertEquals(3, packs.map { it.primaryB }.distinct().size)
            packs.forEach { pack ->
                assertNotEquals(pack.primaryA, pack.primaryB)
                assertEquals(pack.primaryA, pack.finisherRing)
            }
        }
    }

    @Test
    fun `second vfx5 wave uses distinct primary pairs`() {
        listOf(
            HeroClass.MAGE to 0,
            HeroClass.MAGE to 1,
            HeroClass.MAGE to 2,
            HeroClass.MAGE to 3,
            HeroClass.MAGE to 4,
            HeroClass.ROGUE to 0,
            HeroClass.ROGUE to 1,
            HeroClass.ROGUE to 2,
            HeroClass.ROGUE to 3,
            HeroClass.ROGUE to 4,
            HeroClass.PALADIN to 0,
            HeroClass.PALADIN to 1,
            HeroClass.PALADIN to 2,
            HeroClass.PALADIN to 3,
            HeroClass.PALADIN to 4,
        ).forEach { (heroClass, candidate) ->
            val packs = listOf(0, 2, 4).map { band ->
                AuthoredClassBandRoleAssetResolver.resolve(
                    ClassCandidateBandKey(heroClass, candidate, band),
                    testIdentity(heroClass, candidate, band),
                    fallback,
                )
            }
            val expectedStageVariants = if (heroClass == HeroClass.ROGUE && candidate == 0) 1 else 3
            assertEquals(expectedStageVariants, packs.map { it.primaryA }.distinct().size)
            assertEquals(expectedStageVariants, packs.map { it.primaryB }.distinct().size)
            packs.forEach { pack ->
                assertNotEquals(pack.primaryA, pack.primaryB)
                assertEquals(pack.primaryA, pack.finisherRing)
            }
        }
    }

    @Test
    fun `warrior non slash packs keep one family asset while choreography owns growth`() {
        listOf(1, 2, 3).forEach { candidate ->
            val packs = listOf(0, 2, 4).map { band ->
                AuthoredClassBandRoleAssetResolver.resolve(
                    ClassCandidateBandKey(HeroClass.WARRIOR, candidate = candidate, growthBand = band),
                    testIdentity(HeroClass.WARRIOR, candidate = candidate, band = band),
                    fallback,
                )
            }
            assertEquals(1, packs.map { it.primaryA }.distinct().size)
            assertEquals(1, packs.map { it.primaryB }.distinct().size)
            assertEquals(1, packs.map { it.finisherRing }.distinct().size)
            packs.forEach { pack ->
                assertEquals(pack.primaryA, pack.primaryB)
                assertEquals(pack.primaryA, pack.finisherRing)
                assertEquals(fallback.finisherEcho, pack.finisherEcho)
            }
        }
    }

    @Test
    fun `legacy warrior slash cells retain all fallback roles`() {
        val resolved = AuthoredClassBandRoleAssetResolver.resolve(
            ClassCandidateBandKey(HeroClass.WARRIOR, candidate = 0, growthBand = 4),
            testIdentity(HeroClass.WARRIOR, candidate = 0, band = 4),
            fallback,
        )
        assertEquals(fallback, resolved)
    }

    private fun testIdentity(heroClass: HeroClass, candidate: Int, band: Int): SkillVfxIdentity {
        val definition = SkillCatalog.all.first {
            it.heroClass == heroClass && it.candidate == candidate && classCandidateBandKey(it).growthBand == band
        }
        return skillVfxIdentity(definition)
    }
}
