package com.alarmquest.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SkillVfxAssetsPaladinTest {
    @Test
    fun `five candidates keep authored depth families`() {
        val candidates = (0..4).map(::paladinCandidateVfxAssets)

        assertEquals(PaladinVfxGrammar.entries.toSet(), candidates.map { it.grammar }.toSet())
        assertEquals(5, candidates.map { it.secondary }.distinct().size)
        assertEquals(5, candidates.map { it.residual }.distinct().size)
        candidates.forEachIndexed { index, candidate ->
            if (index == 4) {
                assertEquals(candidate.secondary, candidate.residual)
            } else {
                assertNotEquals(candidate.secondary, candidate.residual)
            }
        }
    }

    @Test
    fun `candidate grammar resolves matching impact and debris families`() {
        val sword = paladinCandidateVfxAssets(0)
        val shield = paladinCandidateVfxAssets(1)
        val hammer = paladinCandidateVfxAssets(2)
        val dawn = paladinCandidateVfxAssets(3)
        val order = paladinCandidateVfxAssets(4)

        assertEquals(PaladinImpactGrammar.EDGE, sword.impactGrammar)
        assertEquals(PaladinDebrisGrammar.STEEL, sword.debrisGrammar)
        assertEquals(PaladinImpactGrammar.WEIGHT, shield.impactGrammar)
        assertEquals(PaladinDebrisGrammar.STEEL, shield.debrisGrammar)
        assertEquals(PaladinImpactGrammar.WEIGHT, hammer.impactGrammar)
        assertEquals(PaladinDebrisGrammar.STONE, hammer.debrisGrammar)
        assertEquals(PaladinImpactGrammar.RADIANT, dawn.impactGrammar)
        assertEquals(PaladinDebrisGrammar.HERALDIC, dawn.debrisGrammar)
        assertEquals(PaladinImpactGrammar.RADIANT, order.impactGrammar)
        assertEquals(PaladinDebrisGrammar.HERALDIC, order.debrisGrammar)

        listOf(sword, shield, hammer, dawn, order).forEach { candidate ->
            assertEquals(
                paladinImpactResource(candidate.impactGrammar),
                when (candidate.impactGrammar) {
                    PaladinImpactGrammar.EDGE -> paladinImpactVfxAssets.point
                    PaladinImpactGrammar.WEIGHT -> paladinImpactVfxAssets.fracture
                    PaladinImpactGrammar.RADIANT -> paladinImpactVfxAssets.ring
                },
            )
            assertEquals(
                paladinDebrisResource(candidate.debrisGrammar),
                when (candidate.debrisGrammar) {
                    PaladinDebrisGrammar.STEEL -> paladinDebrisVfxAssets.point
                    PaladinDebrisGrammar.STONE -> paladinDebrisVfxAssets.fracture
                    PaladinDebrisGrammar.HERALDIC -> paladinDebrisVfxAssets.ring
                },
            )
        }
    }
}
