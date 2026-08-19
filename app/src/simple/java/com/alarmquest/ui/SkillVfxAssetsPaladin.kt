package com.alarmquest.ui

import androidx.annotation.DrawableRes
import com.alarmquest.R

/**
 * Candidate-owned layers for the paladin's five visual schools.
 *
 * The semantic families are kept beside the resources so choreography can select a matching
 * contact/debris language without inferring it from a drawable name. Candidate indices are the
 * zero-based values carried by [SkillDefinition].
 */
internal enum class PaladinVfxGrammar {
    SACRED_SWORD,
    FORTRESS_SHIELD,
    JUDGMENT_HAMMER,
    DAWN,
    KNIGHT_ORDER,
}

internal enum class PaladinImpactGrammar {
    EDGE,
    WEIGHT,
    RADIANT,
}

internal enum class PaladinDebrisGrammar {
    STEEL,
    STONE,
    HERALDIC,
}

internal data class PaladinCandidateVfxAssets(
    val grammar: PaladinVfxGrammar,
    val impactGrammar: PaladinImpactGrammar,
    val debrisGrammar: PaladinDebrisGrammar,
    @DrawableRes val secondary: Int,
    @DrawableRes val residual: Int,
)

internal data class PaladinImpactVfxAssets(
    @DrawableRes val point: Int,
    @DrawableRes val fracture: Int,
    @DrawableRes val ring: Int,
)

internal data class PaladinDebrisVfxAssets(
    @DrawableRes val point: Int,
    @DrawableRes val fracture: Int,
    @DrawableRes val ring: Int,
)

internal fun paladinCandidateVfxAssets(candidate: Int): PaladinCandidateVfxAssets =
    paladinCandidateAssets[candidate.coerceIn(0, 4)]

internal val paladinImpactVfxAssets = PaladinImpactVfxAssets(
    R.drawable.vfx6_paladin_sword_contact,
    R.drawable.vfx6_paladin_sword_contact,
    R.drawable.vfx6_paladin_sword_contact,
)

internal val paladinDebrisVfxAssets = PaladinDebrisVfxAssets(
    R.drawable.vfx6_paladin_sword_debris,
    R.drawable.vfx6_paladin_sword_debris,
    R.drawable.vfx6_paladin_sword_debris,
)

@DrawableRes
internal fun paladinImpactResource(grammar: PaladinImpactGrammar): Int = when (grammar) {
    PaladinImpactGrammar.EDGE -> paladinImpactVfxAssets.point
    PaladinImpactGrammar.WEIGHT -> paladinImpactVfxAssets.fracture
    PaladinImpactGrammar.RADIANT -> paladinImpactVfxAssets.ring
}

@DrawableRes
internal fun paladinDebrisResource(grammar: PaladinDebrisGrammar): Int = when (grammar) {
    PaladinDebrisGrammar.STEEL -> paladinDebrisVfxAssets.point
    PaladinDebrisGrammar.STONE -> paladinDebrisVfxAssets.fracture
    PaladinDebrisGrammar.HERALDIC -> paladinDebrisVfxAssets.ring
}

private val paladinCandidateAssets = arrayOf(
    PaladinCandidateVfxAssets(
        grammar = PaladinVfxGrammar.SACRED_SWORD,
        impactGrammar = PaladinImpactGrammar.EDGE,
        debrisGrammar = PaladinDebrisGrammar.STEEL,
        secondary = R.drawable.vfx2_paladin_c01_secondary,
        residual = R.drawable.vfx2_paladin_c01_residual,
    ),
    PaladinCandidateVfxAssets(
        grammar = PaladinVfxGrammar.FORTRESS_SHIELD,
        impactGrammar = PaladinImpactGrammar.WEIGHT,
        debrisGrammar = PaladinDebrisGrammar.STEEL,
        secondary = R.drawable.vfx2_paladin_c02_secondary,
        residual = R.drawable.vfx2_paladin_c02_residual,
    ),
    PaladinCandidateVfxAssets(
        grammar = PaladinVfxGrammar.JUDGMENT_HAMMER,
        impactGrammar = PaladinImpactGrammar.WEIGHT,
        debrisGrammar = PaladinDebrisGrammar.STONE,
        secondary = R.drawable.vfx2_paladin_c03_secondary,
        residual = R.drawable.vfx2_paladin_c03_residual,
    ),
    PaladinCandidateVfxAssets(
        grammar = PaladinVfxGrammar.DAWN,
        impactGrammar = PaladinImpactGrammar.RADIANT,
        debrisGrammar = PaladinDebrisGrammar.HERALDIC,
        secondary = R.drawable.vfx2_paladin_c04_secondary,
        residual = R.drawable.vfx2_paladin_c04_residual,
    ),
    PaladinCandidateVfxAssets(
        grammar = PaladinVfxGrammar.KNIGHT_ORDER,
        impactGrammar = PaladinImpactGrammar.RADIANT,
        debrisGrammar = PaladinDebrisGrammar.HERALDIC,
        secondary = R.drawable.vfx10_paladin_judgment_anticipation,
        residual = R.drawable.vfx10_paladin_judgment_anticipation,
    ),
)
