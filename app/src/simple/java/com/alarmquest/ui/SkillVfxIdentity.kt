package com.alarmquest.ui

import com.alarmquest.engine.SkillDefinition

/**
 * Roles are deliberately domain separated. Adding a new debris variant must not silently move
 * the primary or retime the contact plane for an existing catalog id.
 */
internal enum class SkillVfxRole {
    ANTICIPATION,
    PRIMARY,
    CONTACT,
    DEBRIS,
    RESIDUAL,
    FINISHER_RING,
    FINISHER_ECHO,
    WARRIOR_ACCENT,
}

internal data class SkillRoleVariant(
    val assetVariant: Int,
    val pathVariant: Int,
    val timingVariant: Int,
    val transformVariant: Int,
    val compositingVariant: Int,
)

internal data class SkillVfxIdentity(
    val schemaVersion: Int,
    val catalogId: String,
    val semanticKey: String,
    val stableId: String,
    val roleVariants: Map<SkillVfxRole, SkillRoleVariant>,
)

internal const val SKILL_VFX_IDENTITY_SCHEMA_VERSION = 2
private const val SKILL_VFX_IDENTITY_SALT = "alarmquest-vfx-identity-v2"
private const val FNV64_OFFSET_BASIS = -3750763034362895579L
private const val FNV64_PRIME = 1099511628211L

/** Platform-independent UTF-8 FNV-1a followed by a SplitMix64 avalanche. */
internal fun stableSkillVfxHash(value: String): Long {
    var hash = FNV64_OFFSET_BASIS
    value.encodeToByteArray().forEach { byte ->
        hash = (hash xor (byte.toLong() and 0xffL)) * FNV64_PRIME
    }
    var mixed = hash
    mixed = (mixed xor (mixed ushr 30)) * -4658895280553007687L
    mixed = (mixed xor (mixed ushr 27)) * -7723592293110705685L
    return mixed xor (mixed ushr 31)
}

private fun variant(seed: Long, range: Int): Int =
    java.lang.Long.remainderUnsigned(seed, range.toLong()).toInt()

internal fun skillVfxIdentity(definition: SkillDefinition): SkillVfxIdentity {
    val plan = semanticVfxPlan(definition)
    val semanticKey = listOf(
        definition.heroClass.name,
        definition.candidate,
        definition.unlockLevel / 5,
        definition.element.name,
        definition.motion.name,
        plan.action.name,
        plan.flow.name,
        plan.impactStyle.name,
        definition.timingProfile.name,
        definition.finisher.name,
        definition.hitCount,
        definition.effectVariant,
    ).joinToString(":")
    // Geometry is keyed by the immutable id. Element is intentionally retained only in
    // semanticKey/materialKey, so recolouring a skill never changes its motion grammar.
    val identitySeed = stableSkillVfxHash("$SKILL_VFX_IDENTITY_SALT|${definition.catalogId}")
    val variants = SkillVfxRole.entries.associateWith { role ->
        val roleSeed = stableSkillVfxHash(
            "$SKILL_VFX_IDENTITY_SALT|${definition.catalogId}|${role.name}|$identitySeed",
        )
        SkillRoleVariant(
            assetVariant = variant(roleSeed, 3),
            pathVariant = variant(roleSeed.rotateLeft(11), 8),
            timingVariant = variant(roleSeed.rotateLeft(23), 11),
            transformVariant = variant(roleSeed.rotateLeft(37), 16),
            compositingVariant = variant(roleSeed.rotateLeft(49), 8),
        )
    }
    return SkillVfxIdentity(
        schemaVersion = SKILL_VFX_IDENTITY_SCHEMA_VERSION,
        catalogId = definition.catalogId,
        semanticKey = semanticKey,
        stableId = "v$SKILL_VFX_IDENTITY_SCHEMA_VERSION:${java.lang.Long.toUnsignedString(identitySeed, 16)}",
        roleVariants = variants,
    )
}

private fun Long.rotateLeft(distance: Int): Long = java.lang.Long.rotateLeft(this, distance)
