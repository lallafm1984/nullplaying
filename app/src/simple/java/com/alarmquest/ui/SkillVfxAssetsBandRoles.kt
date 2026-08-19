package com.alarmquest.ui

import com.alarmquest.R
import com.alarmquest.model.HeroClass

/**
 * Production insertion point for primary A/B and dedicated finisher assets.
 *
 * Cells without a completed art pack preserve the currently shipped body/depth resources. This
 * file can grow one class/candidate/growth cell at a time without changing the pure planner.
 */
internal val AuthoredClassBandRoleAssetResolver = ClassBandRoleAssetResolver { key, _, fallback ->
    authoredVfx5RoleAssets(key, fallback) ?: fallback
}

/**
 * Complete six-role insertion point for the vfx6 candidate packs. Until a real drawable exists,
 * keep the resolved production fallback; never reference a placeholder R id. This resolver is the
 * only mapping that needs to change when the 28 contact/debris/echo packs land.
 */
internal val AuthoredClassBandSixRoleAssetResolver = ClassBandSixRoleAssetResolver { key, _, fallback ->
    val pack = vfx6CandidateRolePack(key.heroClass, key.candidate) ?: return@ClassBandSixRoleAssetResolver fallback
    fallback.copy(
        anticipation = pack.anticipation ?: fallback.anticipation,
        contactPoint = pack.contact,
        contactFracture = pack.contact,
        contactRing = pack.contact,
        debrisPoint = pack.debris,
        debrisFracture = pack.debris,
        debrisRing = pack.debris,
        residual = pack.residual ?: fallback.residual,
        finisherEcho = pack.echo,
    )
}

private data class Vfx6CandidateRolePack(
    val contact: Int,
    val debris: Int,
    val echo: Int,
    val anticipation: Int? = null,
    val residual: Int? = null,
)

private fun vfx6CandidateRolePack(heroClass: HeroClass, candidate: Int): Vfx6CandidateRolePack? = when (heroClass) {
    HeroClass.WARRIOR -> when (candidate) {
        1 -> Vfx6CandidateRolePack(
            anticipation = R.drawable.vfx8_warrior_heavy_anticipation,
            contact = R.drawable.vfx6_warrior_heavy_contact,
            debris = R.drawable.vfx6_warrior_heavy_debris,
            residual = R.drawable.vfx6_warrior_heavy_echo,
            echo = R.drawable.vfx6_warrior_heavy_echo,
        )
        2 -> Vfx6CandidateRolePack(
            anticipation = R.drawable.vfx7_warrior_charge_anticipation,
            contact = R.drawable.vfx7_warrior_charge_contact,
            debris = R.drawable.vfx7_warrior_charge_debris,
            residual = R.drawable.vfx7_warrior_charge_echo,
            echo = R.drawable.vfx7_warrior_charge_echo,
        )
        3 -> Vfx6CandidateRolePack(
            anticipation = R.drawable.vfx7_warrior_earth_anticipation,
            contact = R.drawable.vfx6_warrior_earth_contact,
            debris = R.drawable.vfx6_warrior_earth_debris,
            residual = R.drawable.vfx2_warrior_earth_residual,
            echo = R.drawable.vfx2_warrior_earth_residual,
        )
        else -> null
    }
    HeroClass.ROGUE -> when (candidate) {
        0 -> Vfx6CandidateRolePack(R.drawable.vfx9_rogue_blade_contact, R.drawable.vfx6_rogue_blade_debris, R.drawable.vfx6_rogue_blade_echo)
        1 -> Vfx6CandidateRolePack(R.drawable.vfx6_rogue_shadow_contact, R.drawable.vfx6_rogue_shadow_debris, R.drawable.vfx6_rogue_shadow_echo)
        2 -> Vfx6CandidateRolePack(R.drawable.vfx6_rogue_poison_contact, R.drawable.vfx6_rogue_poison_debris, R.drawable.vfx6_rogue_poison_echo)
        3 -> Vfx6CandidateRolePack(R.drawable.vfx9_rogue_wire_contact, R.drawable.vfx6_rogue_wire_debris, R.drawable.vfx6_rogue_wire_echo)
        4 -> Vfx6CandidateRolePack(R.drawable.vfx6_rogue_execute_contact, R.drawable.vfx6_rogue_execute_debris, R.drawable.vfx6_rogue_execute_echo)
        else -> null
    }
    HeroClass.RANGER -> when (candidate) {
        0 -> Vfx6CandidateRolePack(
            contact = R.drawable.vfx9_ranger_precision_contact,
            debris = R.drawable.vfx9_ranger_precision_tail,
            echo = R.drawable.vfx6_ranger_precision_echo,
            anticipation = R.drawable.vfx9_ranger_precision_anticipation,
            residual = R.drawable.vfx9_ranger_precision_anticipation,
        )
        1 -> Vfx6CandidateRolePack(
            contact = R.drawable.vfx10_ranger_volley_contact,
            debris = R.drawable.vfx10_ranger_volley_tail,
            echo = R.drawable.vfx6_ranger_volley_echo,
            anticipation = R.drawable.vfx10_ranger_volley_anticipation,
            residual = R.drawable.vfx10_ranger_volley_anticipation,
        )
        2 -> Vfx6CandidateRolePack(R.drawable.vfx6_ranger_wind_contact, R.drawable.vfx6_ranger_wind_debris, R.drawable.vfx6_ranger_wind_echo)
        3 -> Vfx6CandidateRolePack(R.drawable.vfx6_ranger_beast_contact, R.drawable.vfx6_ranger_beast_debris, R.drawable.vfx6_ranger_beast_echo)
        4 -> Vfx6CandidateRolePack(R.drawable.vfx9_ranger_celestial_contact, R.drawable.vfx6_ranger_celestial_debris, R.drawable.vfx6_ranger_celestial_echo)
        else -> null
    }
    HeroClass.MAGE -> when (candidate) {
        0 -> Vfx6CandidateRolePack(R.drawable.vfx6_mage_fire_contact, R.drawable.vfx6_mage_fire_debris, R.drawable.vfx6_mage_fire_echo)
        1 -> Vfx6CandidateRolePack(R.drawable.vfx6_mage_ice_contact, R.drawable.vfx6_mage_ice_debris, R.drawable.vfx6_mage_ice_echo)
        2 -> Vfx6CandidateRolePack(R.drawable.vfx6_mage_lightning_contact, R.drawable.vfx6_mage_lightning_debris, R.drawable.vfx6_mage_lightning_echo)
        3 -> Vfx6CandidateRolePack(R.drawable.vfx6_mage_arcane_contact, R.drawable.vfx6_mage_arcane_debris, R.drawable.vfx6_mage_arcane_echo)
        4 -> Vfx6CandidateRolePack(R.drawable.vfx6_mage_cosmic_contact, R.drawable.vfx6_mage_cosmic_debris, R.drawable.vfx6_mage_cosmic_echo)
        else -> null
    }
    HeroClass.CLERIC -> when (candidate) {
        0 -> Vfx6CandidateRolePack(
            contact = R.drawable.vfx6_cleric_light_contact,
            debris = R.drawable.vfx6_cleric_light_debris,
            echo = R.drawable.vfx6_cleric_light_echo,
            anticipation = R.drawable.vfx10_cleric_light_tail,
            residual = R.drawable.vfx10_cleric_light_tail,
        )
        1 -> Vfx6CandidateRolePack(R.drawable.vfx6_cleric_judgment_contact, R.drawable.vfx6_cleric_judgment_debris, R.drawable.vfx6_cleric_judgment_echo)
        2 -> Vfx6CandidateRolePack(
            contact = R.drawable.vfx6_cleric_exorcism_contact,
            debris = R.drawable.vfx6_cleric_exorcism_debris,
            echo = R.drawable.vfx6_cleric_exorcism_echo,
            anticipation = R.drawable.vfx10_cleric_exorcism_tail,
            residual = R.drawable.vfx10_cleric_exorcism_tail,
        )
        3 -> Vfx6CandidateRolePack(R.drawable.vfx6_cleric_flame_contact, R.drawable.vfx6_cleric_flame_debris, R.drawable.vfx6_cleric_flame_echo)
        4 -> Vfx6CandidateRolePack(R.drawable.vfx6_cleric_seraph_contact, R.drawable.vfx6_cleric_seraph_debris, R.drawable.vfx6_cleric_seraph_echo)
        else -> null
    }
    HeroClass.PALADIN -> when (candidate) {
        0 -> Vfx6CandidateRolePack(R.drawable.vfx6_paladin_sword_contact, R.drawable.vfx6_paladin_sword_debris, R.drawable.vfx6_paladin_sword_echo)
        1 -> Vfx6CandidateRolePack(R.drawable.vfx6_paladin_shield_contact, R.drawable.vfx6_paladin_shield_debris, R.drawable.vfx6_paladin_shield_echo)
        2 -> Vfx6CandidateRolePack(R.drawable.vfx6_paladin_hammer_contact, R.drawable.vfx6_paladin_hammer_debris, R.drawable.vfx6_paladin_hammer_echo)
        3 -> Vfx6CandidateRolePack(R.drawable.vfx6_paladin_wave_contact, R.drawable.vfx6_paladin_wave_debris, R.drawable.vfx6_paladin_wave_echo)
        4 -> Vfx6CandidateRolePack(
            contact = R.drawable.vfx9_paladin_judgment_contact,
            debris = R.drawable.vfx10_paladin_judgment_tail,
            echo = R.drawable.vfx10_paladin_judgment_echo,
            anticipation = R.drawable.vfx10_paladin_judgment_anticipation,
            residual = R.drawable.vfx10_paladin_judgment_anticipation,
        )
        else -> null
    }
}

private fun authoredVfx5RoleAssets(
    key: ClassCandidateBandKey,
    fallback: ClassBandRoleAssets,
): ClassBandRoleAssets? {
    val stage = when (key.growthBand) {
        0, 1 -> Vfx5Stage.LOW
        2, 3 -> Vfx5Stage.MID
        else -> Vfx5Stage.HIGH
    }
    val pack = when (key.heroClass) {
        HeroClass.RANGER -> when (key.candidate) {
            0 -> rangerPrecisionPack(stage)
            1 -> rangerVolleyPack(stage)
            2 -> rangerWindPack(stage)
            3 -> rangerBeastPack(stage)
            4 -> rangerCelestialPack(stage)
            else -> null
        }
        HeroClass.CLERIC -> when (key.candidate) {
            0 -> clericLightPack(stage)
            1 -> clericJudgmentPack(stage)
            2 -> clericExorcismPack(stage)
            3 -> clericFlamePack(stage)
            4 -> clericSeraphPack(stage)
            else -> null
        }
        HeroClass.MAGE -> when (key.candidate) {
            0 -> mageFirePack(stage)
            1 -> mageIcePack(stage)
            2 -> mageLightningPack(stage)
            3 -> mageArcanePack(stage)
            4 -> mageCosmicPack(stage)
            else -> null
        }
        HeroClass.ROGUE -> when (key.candidate) {
            0 -> rogueBladePack(stage)
            1 -> rogueShadowPack(stage)
            2 -> roguePoisonPack(stage)
            3 -> rogueWirePack(stage)
            4 -> rogueExecutePack(stage)
            else -> null
        }
        HeroClass.PALADIN -> when (key.candidate) {
            0 -> paladinSwordPack(stage)
            1 -> paladinShieldPack(stage)
            2 -> paladinHammerPack(stage)
            3 -> paladinWavePack(stage)
            4 -> paladinJudgmentPack(stage)
            else -> null
        }
        HeroClass.WARRIOR -> when (key.candidate) {
            1 -> warriorHeavyPack(stage)
            2 -> warriorChargePack(stage)
            3 -> warriorEarthPack(stage)
            else -> null
        }
    } ?: return null
    return ClassBandRoleAssets(
        primaryA = pack.primaryA,
        primaryB = pack.primaryB,
        // FINISHER_RING is not part of the reviewed planner. Reusing the authored body keeps the
        // descriptor complete without retaining an unreachable third bitmap for every stage.
        finisherRing = pack.primaryA,
        finisherEcho = fallback.finisherEcho,
    )
}

private enum class Vfx5Stage { LOW, MID, HIGH }

private data class Vfx5RolePack(
    val primaryA: Int,
    val primaryB: Int,
)

private fun rangerPrecisionPack(stage: Vfx5Stage) = when (stage) {
    Vfx5Stage.LOW -> Vfx5RolePack(R.drawable.vfx5_ranger_precision_low_primary_a, R.drawable.vfx5_ranger_precision_low_primary_b)
    Vfx5Stage.MID -> Vfx5RolePack(R.drawable.vfx5_ranger_precision_mid_primary_a, R.drawable.vfx5_ranger_precision_mid_primary_b)
    Vfx5Stage.HIGH -> Vfx5RolePack(R.drawable.vfx5_ranger_precision_high_primary_a, R.drawable.vfx5_ranger_precision_high_primary_b)
}

private fun warriorHeavyPack(stage: Vfx5Stage): Vfx5RolePack {
    // Growth comes from choreography and scale, not from mixing three generations of art.
    @Suppress("UNUSED_VARIABLE") val growthStage = stage
    return Vfx5RolePack(
        R.drawable.vfx8_warrior_heavy_primary,
        R.drawable.vfx8_warrior_heavy_primary,
    )
}

private fun warriorChargePack(stage: Vfx5Stage): Vfx5RolePack {
    @Suppress("UNUSED_VARIABLE") val growthStage = stage
    return Vfx5RolePack(
        R.drawable.vfx8_warrior_charge_primary,
        R.drawable.vfx8_warrior_charge_primary,
    )
}

private fun rangerCelestialPack(stage: Vfx5Stage) = when (stage) {
    Vfx5Stage.LOW -> Vfx5RolePack(R.drawable.vfx5_ranger_celestial_low_primary_a, R.drawable.vfx5_ranger_celestial_low_primary_b)
    Vfx5Stage.MID -> Vfx5RolePack(R.drawable.vfx5_ranger_celestial_mid_primary_a, R.drawable.vfx5_ranger_celestial_mid_primary_b)
    Vfx5Stage.HIGH -> Vfx5RolePack(R.drawable.vfx5_ranger_celestial_high_primary_a, R.drawable.vfx5_ranger_celestial_high_primary_b)
}

private fun rangerVolleyPack(stage: Vfx5Stage) = when (stage) {
    Vfx5Stage.LOW -> Vfx5RolePack(R.drawable.vfx5_ranger_volley_low_primary_a, R.drawable.vfx5_ranger_volley_low_primary_b)
    Vfx5Stage.MID -> Vfx5RolePack(R.drawable.vfx5_ranger_volley_mid_primary_a, R.drawable.vfx5_ranger_volley_mid_primary_b)
    Vfx5Stage.HIGH -> Vfx5RolePack(R.drawable.vfx5_ranger_volley_high_primary_a, R.drawable.vfx5_ranger_volley_high_primary_b)
}

private fun rangerWindPack(stage: Vfx5Stage) = when (stage) {
    Vfx5Stage.LOW -> Vfx5RolePack(R.drawable.vfx5_ranger_wind_low_primary_a, R.drawable.vfx5_ranger_wind_low_primary_b)
    Vfx5Stage.MID -> Vfx5RolePack(R.drawable.vfx5_ranger_wind_mid_primary_a, R.drawable.vfx5_ranger_wind_mid_primary_b)
    Vfx5Stage.HIGH -> Vfx5RolePack(R.drawable.vfx5_ranger_wind_high_primary_a, R.drawable.vfx5_ranger_wind_high_primary_b)
}

private fun rangerBeastPack(stage: Vfx5Stage) = when (stage) {
    Vfx5Stage.LOW -> Vfx5RolePack(R.drawable.vfx5_ranger_beast_low_primary_a, R.drawable.vfx5_ranger_beast_low_primary_b)
    Vfx5Stage.MID -> Vfx5RolePack(R.drawable.vfx5_ranger_beast_mid_primary_a, R.drawable.vfx5_ranger_beast_mid_primary_b)
    Vfx5Stage.HIGH -> Vfx5RolePack(R.drawable.vfx5_ranger_beast_high_primary_a, R.drawable.vfx5_ranger_beast_high_primary_b)
}

private fun clericJudgmentPack(stage: Vfx5Stage) = when (stage) {
    Vfx5Stage.LOW -> Vfx5RolePack(R.drawable.vfx5_cleric_judgment_low_primary_a, R.drawable.vfx5_cleric_judgment_low_primary_b)
    Vfx5Stage.MID -> Vfx5RolePack(R.drawable.vfx5_cleric_judgment_mid_primary_a, R.drawable.vfx5_cleric_judgment_mid_primary_b)
    Vfx5Stage.HIGH -> Vfx5RolePack(R.drawable.vfx5_cleric_judgment_high_primary_a, R.drawable.vfx5_cleric_judgment_high_primary_b)
}

private fun clericLightPack(stage: Vfx5Stage) = when (stage) {
    Vfx5Stage.LOW -> Vfx5RolePack(R.drawable.vfx5_cleric_light_low_primary_a, R.drawable.vfx5_cleric_light_low_primary_b)
    Vfx5Stage.MID -> Vfx5RolePack(R.drawable.vfx5_cleric_light_mid_primary_a, R.drawable.vfx5_cleric_light_mid_primary_b)
    Vfx5Stage.HIGH -> Vfx5RolePack(R.drawable.vfx5_cleric_light_high_primary_a, R.drawable.vfx5_cleric_light_high_primary_b)
}

private fun clericExorcismPack(stage: Vfx5Stage) = when (stage) {
    Vfx5Stage.LOW -> Vfx5RolePack(R.drawable.vfx5_cleric_exorcism_low_primary_a, R.drawable.vfx5_cleric_exorcism_low_primary_b)
    Vfx5Stage.MID -> Vfx5RolePack(R.drawable.vfx5_cleric_exorcism_mid_primary_a, R.drawable.vfx5_cleric_exorcism_mid_primary_b)
    Vfx5Stage.HIGH -> Vfx5RolePack(R.drawable.vfx5_cleric_exorcism_high_primary_a, R.drawable.vfx5_cleric_exorcism_high_primary_b)
}

private fun clericFlamePack(stage: Vfx5Stage) = when (stage) {
    Vfx5Stage.LOW -> Vfx5RolePack(R.drawable.vfx5_cleric_flame_low_primary_a, R.drawable.vfx5_cleric_flame_low_primary_b)
    Vfx5Stage.MID -> Vfx5RolePack(R.drawable.vfx5_cleric_flame_mid_primary_a, R.drawable.vfx5_cleric_flame_mid_primary_b)
    Vfx5Stage.HIGH -> Vfx5RolePack(R.drawable.vfx5_cleric_flame_high_primary_a, R.drawable.vfx5_cleric_flame_high_primary_b)
}

private fun clericSeraphPack(stage: Vfx5Stage) = when (stage) {
    Vfx5Stage.LOW -> Vfx5RolePack(R.drawable.vfx5_cleric_seraph_low_primary_a, R.drawable.vfx5_cleric_seraph_low_primary_b)
    Vfx5Stage.MID -> Vfx5RolePack(R.drawable.vfx5_cleric_seraph_mid_primary_a, R.drawable.vfx5_cleric_seraph_mid_primary_b)
    Vfx5Stage.HIGH -> Vfx5RolePack(R.drawable.vfx5_cleric_seraph_high_primary_a, R.drawable.vfx5_cleric_seraph_high_primary_b)
}

private fun mageArcanePack(stage: Vfx5Stage) = when (stage) {
    Vfx5Stage.LOW -> Vfx5RolePack(R.drawable.vfx5_mage_arcane_low_primary_a, R.drawable.vfx5_mage_arcane_low_primary_b)
    Vfx5Stage.MID -> Vfx5RolePack(R.drawable.vfx5_mage_arcane_mid_primary_a, R.drawable.vfx5_mage_arcane_mid_primary_b)
    Vfx5Stage.HIGH -> Vfx5RolePack(R.drawable.vfx5_mage_arcane_high_primary_a, R.drawable.vfx5_mage_arcane_high_primary_b)
}

private fun mageFirePack(stage: Vfx5Stage) = when (stage) {
    Vfx5Stage.LOW -> Vfx5RolePack(R.drawable.vfx5_mage_fire_low_primary_a, R.drawable.vfx5_mage_fire_low_primary_b)
    Vfx5Stage.MID -> Vfx5RolePack(R.drawable.vfx5_mage_fire_mid_primary_a, R.drawable.vfx5_mage_fire_mid_primary_b)
    Vfx5Stage.HIGH -> Vfx5RolePack(R.drawable.vfx5_mage_fire_high_primary_a, R.drawable.vfx5_mage_fire_high_primary_b)
}

private fun mageIcePack(stage: Vfx5Stage) = when (stage) {
    Vfx5Stage.LOW -> Vfx5RolePack(R.drawable.vfx5_mage_ice_low_primary_a, R.drawable.vfx5_mage_ice_low_primary_b)
    Vfx5Stage.MID -> Vfx5RolePack(R.drawable.vfx5_mage_ice_mid_primary_a, R.drawable.vfx5_mage_ice_mid_primary_b)
    Vfx5Stage.HIGH -> Vfx5RolePack(R.drawable.vfx5_mage_ice_high_primary_a, R.drawable.vfx5_mage_ice_high_primary_b)
}

private fun mageLightningPack(stage: Vfx5Stage) = when (stage) {
    Vfx5Stage.LOW -> Vfx5RolePack(R.drawable.vfx5_mage_lightning_low_primary_a, R.drawable.vfx5_mage_lightning_low_primary_b)
    Vfx5Stage.MID -> Vfx5RolePack(R.drawable.vfx5_mage_lightning_mid_primary_a, R.drawable.vfx5_mage_lightning_mid_primary_b)
    Vfx5Stage.HIGH -> Vfx5RolePack(R.drawable.vfx5_mage_lightning_high_primary_a, R.drawable.vfx5_mage_lightning_high_primary_b)
}

private fun mageCosmicPack(stage: Vfx5Stage) = when (stage) {
    Vfx5Stage.LOW -> Vfx5RolePack(R.drawable.vfx5_mage_cosmic_low_primary_a, R.drawable.vfx5_mage_cosmic_low_primary_b)
    Vfx5Stage.MID -> Vfx5RolePack(R.drawable.vfx5_mage_cosmic_mid_primary_a, R.drawable.vfx5_mage_cosmic_mid_primary_b)
    Vfx5Stage.HIGH -> Vfx5RolePack(R.drawable.vfx5_mage_cosmic_high_primary_a, R.drawable.vfx5_mage_cosmic_high_primary_b)
}

private fun rogueExecutePack(stage: Vfx5Stage) = when (stage) {
    Vfx5Stage.LOW -> Vfx5RolePack(R.drawable.vfx5_rogue_execute_low_primary_a, R.drawable.vfx5_rogue_execute_low_primary_b)
    Vfx5Stage.MID -> Vfx5RolePack(R.drawable.vfx5_rogue_execute_mid_primary_a, R.drawable.vfx5_rogue_execute_mid_primary_b)
    Vfx5Stage.HIGH -> Vfx5RolePack(R.drawable.vfx5_rogue_execute_high_primary_a, R.drawable.vfx5_rogue_execute_high_primary_b)
}

private fun rogueBladePack(stage: Vfx5Stage): Vfx5RolePack {
    // Candidate c01 previously mixed a cyan/red dragon medallion with violet spikes. Keep one
    // black-steel blade family across growth; choreography, scale, and tail density show level.
    @Suppress("UNUSED_VARIABLE") val growthStage = stage
    return Vfx5RolePack(
        R.drawable.vfx9_rogue_blade_primary_a,
        R.drawable.vfx9_rogue_blade_primary_b,
    )
}

private fun rogueShadowPack(stage: Vfx5Stage) = when (stage) {
    Vfx5Stage.LOW -> Vfx5RolePack(R.drawable.vfx5_rogue_shadow_low_primary_a, R.drawable.vfx5_rogue_shadow_low_primary_b)
    Vfx5Stage.MID -> Vfx5RolePack(R.drawable.vfx5_rogue_shadow_mid_primary_a, R.drawable.vfx5_rogue_shadow_mid_primary_b)
    Vfx5Stage.HIGH -> Vfx5RolePack(R.drawable.vfx5_rogue_shadow_high_primary_a, R.drawable.vfx5_rogue_shadow_high_primary_b)
}

private fun roguePoisonPack(stage: Vfx5Stage) = when (stage) {
    Vfx5Stage.LOW -> Vfx5RolePack(R.drawable.vfx5_rogue_poison_low_primary_a, R.drawable.vfx5_rogue_poison_low_primary_b)
    Vfx5Stage.MID -> Vfx5RolePack(R.drawable.vfx5_rogue_poison_mid_primary_a, R.drawable.vfx5_rogue_poison_mid_primary_b)
    Vfx5Stage.HIGH -> Vfx5RolePack(R.drawable.vfx5_rogue_poison_high_primary_a, R.drawable.vfx5_rogue_poison_high_primary_b)
}

private fun rogueWirePack(stage: Vfx5Stage) = when (stage) {
    Vfx5Stage.LOW -> Vfx5RolePack(R.drawable.vfx5_rogue_wire_low_primary_a, R.drawable.vfx5_rogue_wire_low_primary_b)
    Vfx5Stage.MID -> Vfx5RolePack(R.drawable.vfx5_rogue_wire_mid_primary_a, R.drawable.vfx5_rogue_wire_mid_primary_b)
    Vfx5Stage.HIGH -> Vfx5RolePack(R.drawable.vfx5_rogue_wire_high_primary_a, R.drawable.vfx5_rogue_wire_high_primary_b)
}

private fun warriorEarthPack(stage: Vfx5Stage): Vfx5RolePack {
    @Suppress("UNUSED_VARIABLE") val growthStage = stage
    return Vfx5RolePack(
        R.drawable.vfx8_warrior_earth_primary,
        R.drawable.vfx8_warrior_earth_primary,
    )
}

private fun paladinWavePack(stage: Vfx5Stage) = when (stage) {
    Vfx5Stage.LOW -> Vfx5RolePack(R.drawable.vfx5_paladin_wave_low_primary_a, R.drawable.vfx5_paladin_wave_low_primary_b)
    Vfx5Stage.MID -> Vfx5RolePack(R.drawable.vfx5_paladin_wave_mid_primary_a, R.drawable.vfx5_paladin_wave_mid_primary_b)
    Vfx5Stage.HIGH -> Vfx5RolePack(R.drawable.vfx5_paladin_wave_high_primary_a, R.drawable.vfx5_paladin_wave_high_primary_b)
}

private fun paladinSwordPack(stage: Vfx5Stage) = when (stage) {
    Vfx5Stage.LOW -> Vfx5RolePack(R.drawable.vfx5_paladin_sword_low_primary_a, R.drawable.vfx5_paladin_sword_low_primary_b)
    Vfx5Stage.MID -> Vfx5RolePack(R.drawable.vfx5_paladin_sword_mid_primary_a, R.drawable.vfx5_paladin_sword_mid_primary_b)
    Vfx5Stage.HIGH -> Vfx5RolePack(R.drawable.vfx5_paladin_sword_high_primary_a, R.drawable.vfx5_paladin_sword_high_primary_b)
}

private fun paladinShieldPack(stage: Vfx5Stage) = when (stage) {
    Vfx5Stage.LOW -> Vfx5RolePack(R.drawable.vfx5_paladin_shield_low_primary_a, R.drawable.vfx5_paladin_shield_low_primary_b)
    Vfx5Stage.MID -> Vfx5RolePack(R.drawable.vfx5_paladin_shield_mid_primary_a, R.drawable.vfx5_paladin_shield_mid_primary_b)
    Vfx5Stage.HIGH -> Vfx5RolePack(R.drawable.vfx5_paladin_shield_high_primary_a, R.drawable.vfx5_paladin_shield_high_primary_b)
}

private fun paladinHammerPack(stage: Vfx5Stage) = when (stage) {
    Vfx5Stage.LOW -> Vfx5RolePack(R.drawable.vfx5_paladin_hammer_low_primary_a, R.drawable.vfx5_paladin_hammer_low_primary_b)
    Vfx5Stage.MID -> Vfx5RolePack(R.drawable.vfx5_paladin_hammer_mid_primary_a, R.drawable.vfx5_paladin_hammer_mid_primary_b)
    Vfx5Stage.HIGH -> Vfx5RolePack(R.drawable.vfx5_paladin_hammer_high_primary_a, R.drawable.vfx5_paladin_hammer_high_primary_b)
}

private fun paladinJudgmentPack(stage: Vfx5Stage) = when (stage) {
    Vfx5Stage.LOW -> Vfx5RolePack(R.drawable.vfx5_paladin_judgment_low_primary_a, R.drawable.vfx5_paladin_judgment_low_primary_b)
    Vfx5Stage.MID -> Vfx5RolePack(R.drawable.vfx5_paladin_judgment_mid_primary_a, R.drawable.vfx5_paladin_judgment_mid_primary_b)
    Vfx5Stage.HIGH -> Vfx5RolePack(R.drawable.vfx5_paladin_judgment_high_primary_a, R.drawable.vfx5_paladin_judgment_high_primary_b)
}
