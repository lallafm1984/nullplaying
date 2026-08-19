package com.alarmquest.ui

import androidx.annotation.DrawableRes
import androidx.compose.ui.geometry.Offset
import com.alarmquest.engine.SkillDefinition
import com.alarmquest.model.HeroClass
import kotlin.math.roundToInt

/** The exact action/flow motion families reviewed by the motion system. */
internal enum class SkillVfxGrammarId {
    G01_ASCEND_BOTTOM_TO_TOP,
    G02_BEAM_LEFT_TO_RIGHT,
    G03_BURST_CENTER_OUT,
    G04_CHAIN_OUTSIDE_IN,
    G05_CHARGE_LEFT_TO_RIGHT,
    G06_COLLAPSE_OUTSIDE_IN,
    G07_CROSS_CUT_LEFT_TO_RIGHT,
    G08_CUT_LEFT_TO_RIGHT,
    G09_DESCEND_TOP_TO_BOTTOM,
    G10_DIVE_TOP_TO_BOTTOM,
    G11_EXECUTE_TOP_TO_BOTTOM,
    G12_FLURRY_LEFT_TO_RIGHT,
    G13_PIERCE_OUTSIDE_IN,
    G14_SHOT_OUTSIDE_IN,
    G15_SPIN_CLOCKWISE,
    G16_TRAP_OUTSIDE_IN,
    G17_VOLLEY_OUTSIDE_IN,
    G18_VOLLEY_TOP_TO_BOTTOM,
}

internal enum class SkillVfxGrowthStage(
    val occupancyTarget: Float,
    /** Maximum authored bbox overflow on each edge of the combat plane. */
    val overflowFraction: Float,
) {
    LOW(.58f, .10f),
    MID(.76f, .20f),
    HIGH(.92f, .32f),
}

/**
 * Warrior slash recipes deliberately use raw, oversized raster bounds.  The three authored
 * non-slash columns need the same visual mass, while the remaining classes keep the shared
 * authored envelope until their own class pass is reviewed.
 */
internal data class WarriorNonSlashFullBleedScale(
    val primary: Float,
    val anticipation: Float,
    val contact: Float,
    val debris: Float,
    val residual: Float,
    val finisher: Float,
    val accent: Float,
    val overflowFraction: Float,
)

internal fun warriorNonSlashFullBleedScale(
    definition: SkillDefinition,
): WarriorNonSlashFullBleedScale? {
    if (definition.heroClass != HeroClass.WARRIOR || definition.candidate !in 1..3) {
        return null
    }
    val stage = skillVfxGrammar(definition).growthStage
    val stageBoost = when (stage) {
        SkillVfxGrowthStage.LOW -> 1f
        SkillVfxGrowthStage.MID -> 1.10f
        SkillVfxGrowthStage.HIGH -> 1.20f
    }
    val familyBoost = when (definition.candidate) {
        1 -> 1f // heavy descent: broad hammer head and ground collision
        2 -> 1.02f // charge: long horizontal body and wake
        else -> 1.04f // rupture: the widest terrain mass
    }
    val body = stageBoost * familyBoost
    return WarriorNonSlashFullBleedScale(
        primary = 1.20f * body,
        anticipation = 1.16f * body,
        // Ground rupture needs a broad contact blast, but its square source cannot be scaled as
        // wide as the horizontal terrain body without pushing the authored convergence point
        // below the combat plane. 3.40 keeps the luminous width close to the body while the
        // optical anchor owns the exact vertical alignment.
        contact = (if (definition.candidate == 3) 3.40f else 1.46f) * body,
        debris = 1.28f * body,
        residual = 1.18f * body,
        finisher = 1.26f * body,
        accent = 1.20f * body,
        // VFX8 assets are tightly cropped and share a semantic strike anchor, so they need
        // controlled breathing room rather than the legacy two-to-three viewport overflow.
        overflowFraction = if (definition.candidate == 3) {
            // The enlarged ground contact is intentionally allowed to leave the combat plane,
            // matching the legacy slash family's full-bleed flavor.
            when (stage) {
                SkillVfxGrowthStage.LOW -> 1.60f
                SkillVfxGrowthStage.MID -> 2.10f
                SkillVfxGrowthStage.HIGH -> 2.50f
            }
        } else {
            when (stage) {
                SkillVfxGrowthStage.LOW -> 1.00f
                SkillVfxGrowthStage.MID -> 1.75f
                SkillVfxGrowthStage.HIGH -> 2.00f
            }
        },
    )
}

internal enum class SkillVfxHitBand { SINGLE, DOUBLE, COMBO, BARRAGE }

internal enum class SkillVfxAnchor { TARGET, LEADING_EDGE, TRAILING_EDGE, ORBIT, GROUND }

internal data class SkillVfxRoleDescriptor(
    val role: SkillVfxRole,
    val assetVariant: Int,
    val pathVariant: Int,
    val timingVariant: Int,
    val transformVariant: Int,
    val compositingVariant: Int,
    val startOffsetMillis: Int,
    val durationScale: Float,
    val offset: Offset,
    val scaleX: Float,
    val scaleY: Float,
    val rotationBiasDegrees: Float,
    val mirror: Float,
    val alphaMultiplier: Float,
    /** Deprecated export-compatibility field. Runtime and browser leave it null and inert. */
    val safeAlphaCap: Float? = null,
    val instancePattern: Int,
    val zOrder: Int,
    val anchor: SkillVfxAnchor,
)

internal data class SkillVfxGrammarDescriptor(
    val schemaVersion: Int,
    val identityId: String,
    val grammarId: SkillVfxGrammarId,
    val growthStage: SkillVfxGrowthStage,
    val hitBand: SkillVfxHitBand,
    /** Element remains a material/palette key and never selects geometry. */
    val materialKey: String,
    val roles: Map<SkillVfxRole, SkillVfxRoleDescriptor>,
) {
    fun role(layerRole: AuthoredLayerRole): SkillVfxRoleDescriptor = roles.getValue(layerRole.skillVfxRole)

    /** Quantized on purpose: microscopic float changes cannot fake a unique visual identity. */
    fun perceptualSignature(): String = buildString {
        append(grammarId.name).append(':').append(growthStage.name).append(':').append(hitBand.name)
        SkillVfxRole.entries.forEach { role ->
            val value = roles.getValue(role)
            append('|').append(role.name)
            append(':').append(value.assetVariant)
            append(':').append(value.pathVariant)
            append(':').append(value.startOffsetMillis / 6)
            append(':').append((value.offset.x / .02f).roundToInt())
            append(':').append((value.offset.y / .02f).roundToInt())
            append(':').append((value.scaleX / .03f).roundToInt())
            append(':').append((value.scaleY / .03f).roundToInt())
            append(':').append((value.rotationBiasDegrees / 5f).roundToInt())
            append(':').append(value.mirror.toInt())
            append(':').append(value.instancePattern)
        }
    }

    fun exportValue(): String = SkillVfxRole.entries.joinToString(";") { role ->
        val value = roles.getValue(role)
        listOf(
            role.name,
            value.assetVariant,
            value.pathVariant,
            value.startOffsetMillis,
            value.durationScale,
            value.offset.x,
            value.offset.y,
            value.scaleX,
            value.scaleY,
            value.rotationBiasDegrees,
            value.mirror,
            value.alphaMultiplier,
            value.safeAlphaCap ?: "",
            value.instancePattern,
            value.zOrder,
            value.anchor.name,
        ).joinToString(",")
    }
}

internal data class ClassBandDepthAssets(
    @DrawableRes val secondary: Int,
    @DrawableRes val residual: Int,
)

/**
 * Authored body and finish resources for one class/candidate/growth cell.  A and B are both
 * retained in the descriptor so the immutable catalog identity, rather than a mutable RNG, can
 * select the silhouette. Finisher resources are deliberately independent from contact art.
 */
internal data class ClassBandRoleAssets(
    @DrawableRes val primaryA: Int,
    @DrawableRes val primaryB: Int,
    @DrawableRes val finisherRing: Int,
    @DrawableRes val finisherEcho: Int,
)

/**
 * Complete authored raster contract for one class/candidate/growth cell.
 *
 * Keeping all planes in one value prevents a body/finish resolver from silently leaving contact,
 * debris, or depth on a class-wide fallback. The current production resolver may return the
 * supplied fallback while a candidate pack is being authored, but the planner/exporter always see
 * the same complete contract.
 */
internal data class ClassBandSixRoleAssets(
    @DrawableRes val anticipation: Int,
    @DrawableRes val primaryA: Int,
    @DrawableRes val primaryB: Int,
    @DrawableRes val contactPoint: Int,
    @DrawableRes val contactFracture: Int,
    @DrawableRes val contactRing: Int,
    @DrawableRes val debrisPoint: Int,
    @DrawableRes val debrisFracture: Int,
    @DrawableRes val debrisRing: Int,
    @DrawableRes val residual: Int,
    @DrawableRes val finisherRing: Int,
    @DrawableRes val finisherEcho: Int,
)

internal fun interface ClassBandSixRoleAssetResolver {
    fun resolve(
        key: ClassCandidateBandKey,
        identity: SkillVfxIdentity,
        fallback: ClassBandSixRoleAssets,
    ): ClassBandSixRoleAssets
}

internal fun interface ClassBandRoleAssetResolver {
    fun resolve(
        key: ClassCandidateBandKey,
        identity: SkillVfxIdentity,
        fallback: ClassBandRoleAssets,
    ): ClassBandRoleAssets
}

internal fun interface ClassBandDepthAssetResolver {
    fun resolve(
        key: ClassCandidateBandKey,
        identity: SkillVfxIdentity,
        fallback: ClassBandDepthAssets,
    ): ClassBandDepthAssets
}

internal val AuthoredClassBandDepthAssetResolver = ClassBandDepthAssetResolver { _, _, fallback -> fallback }

internal val AuthoredLayerRole.skillVfxRole: SkillVfxRole
    get() = when (this) {
        AuthoredLayerRole.SECONDARY -> SkillVfxRole.ANTICIPATION
        AuthoredLayerRole.PRIMARY -> SkillVfxRole.PRIMARY
        AuthoredLayerRole.CONTACT -> SkillVfxRole.CONTACT
        AuthoredLayerRole.DEBRIS -> SkillVfxRole.DEBRIS
        AuthoredLayerRole.RESIDUAL -> SkillVfxRole.RESIDUAL
        AuthoredLayerRole.FINISHER_RING -> SkillVfxRole.FINISHER_RING
        AuthoredLayerRole.FINISHER_ECHO -> SkillVfxRole.FINISHER_ECHO
        AuthoredLayerRole.WARRIOR_ACCENT -> SkillVfxRole.WARRIOR_ACCENT
    }

private data class GrammarRhythm(
    val anticipation: Int,
    val primary: Int,
    val contact: Int,
    val debris: Int,
    val residual: Int,
    val finisher: Int,
    val bias: Offset,
    val rotation: Float,
)

private fun rhythm(id: SkillVfxGrammarId): GrammarRhythm = when (id) {
    SkillVfxGrammarId.G01_ASCEND_BOTTOM_TO_TOP -> GrammarRhythm(-18, -10, 5, 12, 22, 18, Offset(0f, .022f), -7f)
    SkillVfxGrammarId.G02_BEAM_LEFT_TO_RIGHT -> GrammarRhythm(-28, -16, 0, 8, 16, 12, Offset(-.020f, 0f), 0f)
    SkillVfxGrammarId.G03_BURST_CENTER_OUT -> GrammarRhythm(-10, -4, 0, 2, 12, 8, Offset(0f, .012f), 9f)
    SkillVfxGrammarId.G04_CHAIN_OUTSIDE_IN -> GrammarRhythm(-30, -20, 4, 12, 24, 20, Offset(.020f, -.014f), -11f)
    SkillVfxGrammarId.G05_CHARGE_LEFT_TO_RIGHT -> GrammarRhythm(-24, -18, 0, 6, 15, 12, Offset(-.026f, .008f), 1f)
    SkillVfxGrammarId.G06_COLLAPSE_OUTSIDE_IN -> GrammarRhythm(-34, -20, 7, 10, 22, 25, Offset(0f, -.020f), 14f)
    SkillVfxGrammarId.G07_CROSS_CUT_LEFT_TO_RIGHT -> GrammarRhythm(-16, -12, 0, 5, 13, 10, Offset(.012f, -.012f), 13f)
    SkillVfxGrammarId.G08_CUT_LEFT_TO_RIGHT -> GrammarRhythm(-14, -10, 0, 7, 16, 12, Offset(-.014f, .006f), -5f)
    SkillVfxGrammarId.G09_DESCEND_TOP_TO_BOTTOM -> GrammarRhythm(-26, -18, 3, 8, 20, 18, Offset(.008f, -.024f), 6f)
    SkillVfxGrammarId.G10_DIVE_TOP_TO_BOTTOM -> GrammarRhythm(-32, -22, 4, 5, 18, 16, Offset(-.016f, -.026f), -9f)
    SkillVfxGrammarId.G11_EXECUTE_TOP_TO_BOTTOM -> GrammarRhythm(-38, -24, 8, 14, 26, 22, Offset(.014f, -.018f), 3f)
    SkillVfxGrammarId.G12_FLURRY_LEFT_TO_RIGHT -> GrammarRhythm(-12, -8, 0, 4, 10, 8, Offset(.018f, .010f), 12f)
    SkillVfxGrammarId.G13_PIERCE_OUTSIDE_IN -> GrammarRhythm(-25, -18, 1, 7, 16, 14, Offset(-.022f, -.008f), -3f)
    SkillVfxGrammarId.G14_SHOT_OUTSIDE_IN -> GrammarRhythm(-22, -16, 0, 9, 18, 14, Offset(.024f, -.006f), 4f)
    SkillVfxGrammarId.G15_SPIN_CLOCKWISE -> GrammarRhythm(-16, -12, 2, 6, 18, 12, Offset(0f, .014f), 18f)
    SkillVfxGrammarId.G16_TRAP_OUTSIDE_IN -> GrammarRhythm(-36, -20, 8, 13, 28, 24, Offset(0f, .026f), -14f)
    SkillVfxGrammarId.G17_VOLLEY_OUTSIDE_IN -> GrammarRhythm(-18, -12, 0, 5, 12, 9, Offset(.022f, -.012f), 7f)
    SkillVfxGrammarId.G18_VOLLEY_TOP_TO_BOTTOM -> GrammarRhythm(-20, -14, 1, 5, 14, 10, Offset(-.014f, -.022f), -7f)
}

internal fun skillVfxGrammarId(plan: SemanticVfxPlan): SkillVfxGrammarId = when (plan.action to plan.flow) {
    SemanticVfxAction.ASCEND to SemanticVfxFlow.BOTTOM_TO_TOP -> SkillVfxGrammarId.G01_ASCEND_BOTTOM_TO_TOP
    SemanticVfxAction.BEAM to SemanticVfxFlow.LEFT_TO_RIGHT -> SkillVfxGrammarId.G02_BEAM_LEFT_TO_RIGHT
    SemanticVfxAction.BURST to SemanticVfxFlow.CENTER_OUT -> SkillVfxGrammarId.G03_BURST_CENTER_OUT
    SemanticVfxAction.CHAIN to SemanticVfxFlow.OUTSIDE_IN -> SkillVfxGrammarId.G04_CHAIN_OUTSIDE_IN
    SemanticVfxAction.CHARGE to SemanticVfxFlow.LEFT_TO_RIGHT -> SkillVfxGrammarId.G05_CHARGE_LEFT_TO_RIGHT
    SemanticVfxAction.COLLAPSE to SemanticVfxFlow.OUTSIDE_IN -> SkillVfxGrammarId.G06_COLLAPSE_OUTSIDE_IN
    SemanticVfxAction.CROSS_CUT to SemanticVfxFlow.LEFT_TO_RIGHT -> SkillVfxGrammarId.G07_CROSS_CUT_LEFT_TO_RIGHT
    SemanticVfxAction.CUT to SemanticVfxFlow.LEFT_TO_RIGHT -> SkillVfxGrammarId.G08_CUT_LEFT_TO_RIGHT
    SemanticVfxAction.DESCEND to SemanticVfxFlow.TOP_TO_BOTTOM -> SkillVfxGrammarId.G09_DESCEND_TOP_TO_BOTTOM
    SemanticVfxAction.DIVE to SemanticVfxFlow.TOP_TO_BOTTOM -> SkillVfxGrammarId.G10_DIVE_TOP_TO_BOTTOM
    SemanticVfxAction.EXECUTE to SemanticVfxFlow.TOP_TO_BOTTOM -> SkillVfxGrammarId.G11_EXECUTE_TOP_TO_BOTTOM
    SemanticVfxAction.FLURRY to SemanticVfxFlow.LEFT_TO_RIGHT -> SkillVfxGrammarId.G12_FLURRY_LEFT_TO_RIGHT
    SemanticVfxAction.PIERCE to SemanticVfxFlow.OUTSIDE_IN -> SkillVfxGrammarId.G13_PIERCE_OUTSIDE_IN
    SemanticVfxAction.SHOT to SemanticVfxFlow.OUTSIDE_IN -> SkillVfxGrammarId.G14_SHOT_OUTSIDE_IN
    SemanticVfxAction.SPIN to SemanticVfxFlow.CLOCKWISE -> SkillVfxGrammarId.G15_SPIN_CLOCKWISE
    SemanticVfxAction.TRAP to SemanticVfxFlow.OUTSIDE_IN -> SkillVfxGrammarId.G16_TRAP_OUTSIDE_IN
    SemanticVfxAction.VOLLEY to SemanticVfxFlow.OUTSIDE_IN -> SkillVfxGrammarId.G17_VOLLEY_OUTSIDE_IN
    SemanticVfxAction.VOLLEY to SemanticVfxFlow.TOP_TO_BOTTOM -> SkillVfxGrammarId.G18_VOLLEY_TOP_TO_BOTTOM
    else -> error("No exact VFX grammar for ${plan.action}/${plan.flow}")
}

internal fun skillVfxGrammar(definition: SkillDefinition): SkillVfxGrammarDescriptor {
    val identity = skillVfxIdentity(definition)
    val plan = semanticVfxPlan(definition)
    val grammarId = skillVfxGrammarId(plan)
    val rhythm = rhythm(grammarId)
    val band = classVfxSignature(definition).growthBand
    val stage = when (band) {
        0, 1 -> SkillVfxGrowthStage.LOW
        2, 3 -> SkillVfxGrowthStage.MID
        else -> SkillVfxGrowthStage.HIGH
    }
    val hitBand = when (definition.hitCount) {
        1 -> SkillVfxHitBand.SINGLE
        2 -> SkillVfxHitBand.DOUBLE
        in 3..5 -> SkillVfxHitBand.COMBO
        else -> SkillVfxHitBand.BARRAGE
    }
    val phaseShift = mapOf(
        SkillVfxRole.ANTICIPATION to rhythm.anticipation,
        SkillVfxRole.PRIMARY to rhythm.primary,
        SkillVfxRole.CONTACT to rhythm.contact,
        SkillVfxRole.DEBRIS to rhythm.debris,
        SkillVfxRole.RESIDUAL to rhythm.residual,
        SkillVfxRole.FINISHER_RING to rhythm.finisher,
        SkillVfxRole.FINISHER_ECHO to rhythm.finisher + 12,
        SkillVfxRole.WARRIOR_ACCENT to rhythm.primary - 4,
    )
    val zOrders = mapOf(
        SkillVfxRole.ANTICIPATION to 0,
        SkillVfxRole.RESIDUAL to 1,
        SkillVfxRole.PRIMARY to 2,
        SkillVfxRole.WARRIOR_ACCENT to 3,
        SkillVfxRole.CONTACT to 4,
        SkillVfxRole.DEBRIS to 5,
        SkillVfxRole.FINISHER_RING to 6,
        SkillVfxRole.FINISHER_ECHO to 7,
    )
    val occupancyScale = when (stage) {
        SkillVfxGrowthStage.LOW -> .94f
        SkillVfxGrowthStage.MID -> 1f
        SkillVfxGrowthStage.HIGH -> 1.06f
    }
    val roles = SkillVfxRole.entries.associateWith { role ->
        val variant = identity.roleVariants.getValue(role)
        val signedX = (variant.transformVariant.mod(5) - 2) * .010f
        val signedY = (variant.pathVariant.mod(5) - 2) * .009f
        val roleWeight = when (role) {
            SkillVfxRole.PRIMARY, SkillVfxRole.ANTICIPATION -> 1f
            SkillVfxRole.CONTACT, SkillVfxRole.FINISHER_RING, SkillVfxRole.FINISHER_ECHO -> .68f
            else -> .82f
        }
        SkillVfxRoleDescriptor(
            role = role,
            assetVariant = variant.assetVariant,
            pathVariant = variant.pathVariant,
            timingVariant = variant.timingVariant,
            transformVariant = variant.transformVariant,
            compositingVariant = variant.compositingVariant,
            startOffsetMillis = phaseShift.getValue(role) + (variant.timingVariant - 5) * 4,
            durationScale = .92f + variant.compositingVariant.mod(5) * .04f,
            offset = rhythm.bias * roleWeight + Offset(signedX, signedY),
            scaleX = occupancyScale * (.95f + variant.transformVariant.mod(5) * .025f),
            scaleY = occupancyScale * (.95f + variant.pathVariant.mod(5) * .025f),
            rotationBiasDegrees = rhythm.rotation * roleWeight + (variant.transformVariant.mod(7) - 3) * 3f,
            mirror = if (variant.pathVariant.mod(2) == 0) 1f else -1f,
            alphaMultiplier = .92f + variant.compositingVariant.mod(4) * .04f,
            safeAlphaCap = null,
            instancePattern = when (hitBand) {
                SkillVfxHitBand.SINGLE -> 1
                SkillVfxHitBand.DOUBLE -> 2
                SkillVfxHitBand.COMBO -> 2 + variant.pathVariant.mod(2)
                SkillVfxHitBand.BARRAGE -> 3 + variant.pathVariant.mod(2)
            },
            zOrder = zOrders.getValue(role),
            anchor = when (role) {
                SkillVfxRole.ANTICIPATION -> SkillVfxAnchor.LEADING_EDGE
                SkillVfxRole.DEBRIS -> SkillVfxAnchor.TRAILING_EDGE
                SkillVfxRole.FINISHER_RING, SkillVfxRole.FINISHER_ECHO -> SkillVfxAnchor.ORBIT
                SkillVfxRole.RESIDUAL -> SkillVfxAnchor.GROUND
                else -> SkillVfxAnchor.TARGET
            },
        )
    }
    return SkillVfxGrammarDescriptor(
        schemaVersion = SKILL_VFX_IDENTITY_SCHEMA_VERSION,
        identityId = identity.stableId,
        grammarId = grammarId,
        growthStage = stage,
        hitBand = hitBand,
        materialKey = definition.element.name,
        roles = roles,
    )
}

internal fun SkillVfxRoleDescriptor.window(
    start: Int,
    end: Int,
    ceiling: Int,
): Pair<Int, Int>? {
    val shiftedStart = start + startOffsetMillis
    val duration = ((end - start).coerceAtLeast(1) * durationScale).roundToInt().coerceAtLeast(1)
    val shiftedEnd = minOf(shiftedStart + duration, ceiling)
    return if (shiftedStart < shiftedEnd) shiftedStart to shiftedEnd else null
}
