package com.alarmquest.ui

import androidx.annotation.DrawableRes
import com.alarmquest.R
import com.alarmquest.model.HeroClass

/**
 * Candidate-owned layered VFX resources for the mage and cleric refresh.
 *
 * The two classes intentionally use different visual grammars: mage resources are built from
 * elemental orbits and space distortion, while cleric resources use descending light, judgment
 * panels, wards, sacred flame, and seraphic feathers. Every bitmap is 768 px with transparent
 * 64 px bleed and leaves the centered damage-number plane unobstructed.
 */
internal data class MageClericCandidateVfxAssets(
    @DrawableRes val secondary: Int,
    @DrawableRes val residual: Int,
)

internal data class MageClericImpactVfxAssets(
    @DrawableRes val point: Int,
    @DrawableRes val fracture: Int,
    @DrawableRes val ring: Int,
)

internal data class MageClericDebrisVfxAssets(
    @DrawableRes val point: Int,
    @DrawableRes val fracture: Int,
    @DrawableRes val ring: Int,
)

internal fun mageClericCandidateVfxAssets(
    heroClass: HeroClass,
    candidate: Int,
): MageClericCandidateVfxAssets? {
    val zeroBasedCandidate = candidate.coerceIn(0, 4)
    return when (heroClass) {
        HeroClass.MAGE -> mageCandidateAssets[zeroBasedCandidate]
        HeroClass.CLERIC -> clericCandidateAssets[zeroBasedCandidate]
        else -> null
    }
}

internal fun mageClericImpactVfxAssets(heroClass: HeroClass): MageClericImpactVfxAssets? = when (heroClass) {
    HeroClass.MAGE -> MageClericImpactVfxAssets(
        R.drawable.vfx6_mage_fire_contact,
        R.drawable.vfx6_mage_fire_contact,
        R.drawable.vfx6_mage_fire_contact,
    )
    HeroClass.CLERIC -> MageClericImpactVfxAssets(
        R.drawable.vfx6_cleric_light_contact,
        R.drawable.vfx6_cleric_light_contact,
        R.drawable.vfx6_cleric_light_contact,
    )
    else -> null
}

internal fun mageClericDebrisVfxAssets(heroClass: HeroClass): MageClericDebrisVfxAssets? = when (heroClass) {
    HeroClass.MAGE -> MageClericDebrisVfxAssets(
        R.drawable.vfx6_mage_fire_debris,
        R.drawable.vfx6_mage_fire_debris,
        R.drawable.vfx6_mage_fire_debris,
    )
    HeroClass.CLERIC -> MageClericDebrisVfxAssets(
        R.drawable.vfx6_cleric_light_debris,
        R.drawable.vfx6_cleric_light_debris,
        R.drawable.vfx6_cleric_light_debris,
    )
    else -> null
}

private val mageCandidateAssets = arrayOf(
    MageClericCandidateVfxAssets(
        secondary = R.drawable.vfx2_mage_c01_secondary,
        residual = R.drawable.vfx2_mage_c01_residual,
    ),
    MageClericCandidateVfxAssets(
        secondary = R.drawable.vfx2_mage_c02_secondary,
        residual = R.drawable.vfx2_mage_c02_residual,
    ),
    MageClericCandidateVfxAssets(
        secondary = R.drawable.vfx2_mage_c03_secondary,
        residual = R.drawable.vfx2_mage_c03_residual,
    ),
    MageClericCandidateVfxAssets(
        secondary = R.drawable.vfx2_mage_c04_secondary,
        residual = R.drawable.vfx2_mage_c04_residual,
    ),
    MageClericCandidateVfxAssets(
        secondary = R.drawable.vfx2_mage_c05_secondary,
        residual = R.drawable.vfx2_mage_c05_residual,
    ),
)

private val clericCandidateAssets = arrayOf(
    MageClericCandidateVfxAssets(
        secondary = R.drawable.vfx10_cleric_light_tail,
        residual = R.drawable.vfx10_cleric_light_tail,
    ),
    MageClericCandidateVfxAssets(
        secondary = R.drawable.vfx2_cleric_c02_secondary,
        residual = R.drawable.vfx2_cleric_c02_residual,
    ),
    MageClericCandidateVfxAssets(
        secondary = R.drawable.vfx10_cleric_exorcism_tail,
        residual = R.drawable.vfx10_cleric_exorcism_tail,
    ),
    MageClericCandidateVfxAssets(
        secondary = R.drawable.vfx2_cleric_c04_secondary,
        residual = R.drawable.vfx2_cleric_c04_residual,
    ),
    MageClericCandidateVfxAssets(
        secondary = R.drawable.vfx2_cleric_c05_secondary,
        residual = R.drawable.vfx2_cleric_c05_residual,
    ),
)
