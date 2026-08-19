package com.alarmquest.ui

import androidx.annotation.DrawableRes
import com.alarmquest.R
import com.alarmquest.model.HeroClass

/**
 * Candidate-owned layered VFX resources for the rogue/ranger refresh.
 *
 * This file is deliberately separate from [ModularSkillVfx] so the renderer can opt in
 * without rewriting the existing class resource tables. All assets are 768 px, carry a
 * transparent 64 px bleed. Secondary and residual sprites preserve the damage-number
 * plane; compact impact sprites intentionally occupy that plane at the contact frame.
 */
internal data class RogueRangerCandidateVfxAssets(
    @DrawableRes val secondary: Int,
    @DrawableRes val residual: Int,
)

internal data class RogueRangerImpactVfxAssets(
    @DrawableRes val point: Int,
    @DrawableRes val fracture: Int,
    @DrawableRes val ring: Int,
)

internal data class RogueRangerDebrisVfxAssets(
    @DrawableRes val point: Int,
    @DrawableRes val fracture: Int,
    @DrawableRes val ring: Int,
)

internal fun rogueRangerCandidateVfxAssets(
    heroClass: HeroClass,
    candidate: Int,
): RogueRangerCandidateVfxAssets? {
    val zeroBasedCandidate = candidate.coerceIn(0, 4)
    return when (heroClass) {
        HeroClass.ROGUE -> rogueCandidateAssets[zeroBasedCandidate]
        HeroClass.RANGER -> rangerCandidateAssets[zeroBasedCandidate]
        else -> null
    }
}

internal fun rogueRangerImpactVfxAssets(heroClass: HeroClass): RogueRangerImpactVfxAssets? = when (heroClass) {
    HeroClass.ROGUE -> RogueRangerImpactVfxAssets(
        R.drawable.vfx9_rogue_blade_contact,
        R.drawable.vfx9_rogue_blade_contact,
        R.drawable.vfx9_rogue_blade_contact,
    )
    HeroClass.RANGER -> RogueRangerImpactVfxAssets(
        R.drawable.vfx9_ranger_precision_contact,
        R.drawable.vfx9_ranger_precision_contact,
        R.drawable.vfx9_ranger_precision_contact,
    )
    else -> null
}

internal fun rogueRangerDebrisVfxAssets(heroClass: HeroClass): RogueRangerDebrisVfxAssets? = when (heroClass) {
    HeroClass.ROGUE -> RogueRangerDebrisVfxAssets(
        R.drawable.vfx6_rogue_blade_debris,
        R.drawable.vfx6_rogue_blade_debris,
        R.drawable.vfx6_rogue_blade_debris,
    )
    HeroClass.RANGER -> RogueRangerDebrisVfxAssets(
        R.drawable.vfx9_ranger_precision_tail,
        R.drawable.vfx9_ranger_precision_tail,
        R.drawable.vfx9_ranger_precision_tail,
    )
    else -> null
}

private val rogueCandidateAssets = arrayOf(
    RogueRangerCandidateVfxAssets(
        secondary = R.drawable.vfx3_rogue_secondary_pierce,
        residual = R.drawable.vfx3_rogue_residual_pierce,
    ),
    RogueRangerCandidateVfxAssets(
        secondary = R.drawable.vfx3_rogue_secondary_shadow,
        residual = R.drawable.vfx3_rogue_residual_shadow,
    ),
    RogueRangerCandidateVfxAssets(
        secondary = R.drawable.vfx3_rogue_secondary_poison,
        residual = R.drawable.vfx3_rogue_residual_poison,
    ),
    RogueRangerCandidateVfxAssets(
        secondary = R.drawable.vfx3_rogue_secondary_wire,
        residual = R.drawable.vfx3_rogue_residual_wire,
    ),
    RogueRangerCandidateVfxAssets(
        secondary = R.drawable.vfx3_rogue_secondary_execute,
        residual = R.drawable.vfx3_rogue_residual_execute,
    ),
)

private val rangerCandidateAssets = arrayOf(
    RogueRangerCandidateVfxAssets(
        secondary = R.drawable.vfx9_ranger_precision_anticipation,
        residual = R.drawable.vfx9_ranger_precision_anticipation,
    ),
    RogueRangerCandidateVfxAssets(
        secondary = R.drawable.vfx10_ranger_volley_anticipation,
        residual = R.drawable.vfx10_ranger_volley_anticipation,
    ),
    RogueRangerCandidateVfxAssets(
        secondary = R.drawable.vfx3_ranger_secondary_wind,
        residual = R.drawable.vfx3_ranger_residual_wind,
    ),
    RogueRangerCandidateVfxAssets(
        secondary = R.drawable.vfx3_ranger_secondary_beast,
        residual = R.drawable.vfx3_ranger_residual_beast,
    ),
    RogueRangerCandidateVfxAssets(
        secondary = R.drawable.vfx3_ranger_secondary_celestial,
        residual = R.drawable.vfx3_ranger_residual_celestial,
    ),
)
