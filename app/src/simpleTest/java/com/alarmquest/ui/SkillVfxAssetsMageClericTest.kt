package com.alarmquest.ui

import com.alarmquest.model.HeroClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkillVfxAssetsMageClericTest {
    @Test
    fun `mage and cleric candidates keep five authored depth families`() {
        listOf(HeroClass.MAGE, HeroClass.CLERIC).forEach { heroClass ->
            val candidates = (0..4).map { candidate ->
                mageClericCandidateVfxAssets(heroClass, candidate)!!
            }

            assertEquals(5, candidates.map { it.secondary }.distinct().size)
            assertEquals(5, candidates.map { it.residual }.distinct().size)
            candidates.forEachIndexed { candidate, assets ->
                val intentionallySharedTail = heroClass == HeroClass.CLERIC && candidate in setOf(0, 2)
                if (intentionallySharedTail) {
                    assertEquals(assets.secondary, assets.residual)
                } else {
                    assertNotEquals(assets.secondary, assets.residual)
                }
            }
        }
    }

    @Test
    fun `mage and cleric fallback impact and debris stay class owned`() {
        val mageImpacts = mageClericImpactVfxAssets(HeroClass.MAGE)!!
        val clericImpacts = mageClericImpactVfxAssets(HeroClass.CLERIC)!!
        val mageDebris = mageClericDebrisVfxAssets(HeroClass.MAGE)!!
        val clericDebris = mageClericDebrisVfxAssets(HeroClass.CLERIC)!!

        assertEquals(1, setOf(mageImpacts.point, mageImpacts.fracture, mageImpacts.ring).size)
        assertEquals(1, setOf(clericImpacts.point, clericImpacts.fracture, clericImpacts.ring).size)
        assertEquals(1, setOf(mageDebris.point, mageDebris.fracture, mageDebris.ring).size)
        assertEquals(1, setOf(clericDebris.point, clericDebris.fracture, clericDebris.ring).size)
        assertNotEquals(mageImpacts.point, clericImpacts.point)
        assertNotEquals(mageDebris.point, clericDebris.point)
    }

    @Test
    fun `candidate bounds are stable and unrelated classes do not opt in`() {
        assertEquals(
            mageClericCandidateVfxAssets(HeroClass.MAGE, 0),
            mageClericCandidateVfxAssets(HeroClass.MAGE, -1),
        )
        assertEquals(
            mageClericCandidateVfxAssets(HeroClass.CLERIC, 4),
            mageClericCandidateVfxAssets(HeroClass.CLERIC, 9),
        )
        assertNull(mageClericCandidateVfxAssets(HeroClass.WARRIOR, 0))
        assertNull(mageClericImpactVfxAssets(HeroClass.PALADIN))
        assertNull(mageClericDebrisVfxAssets(HeroClass.RANGER))
    }
}
