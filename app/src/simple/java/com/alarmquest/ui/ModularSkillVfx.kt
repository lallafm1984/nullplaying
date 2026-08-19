package com.alarmquest.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.annotation.DrawableRes
import com.alarmquest.R
import com.alarmquest.engine.SkillDefinition
import com.alarmquest.engine.SkillElement
import com.alarmquest.engine.SkillFinisher
import com.alarmquest.engine.SkillMotion
import com.alarmquest.model.HeroClass
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

internal enum class ModularVfxArchetype {
    WARRIOR,
    SLASH,
    PROJECTILE,
    COLUMN,
    VORTEX,
    IMPACT,
}

internal data class ModularRecipe(
    val archetype: ModularVfxArchetype,
    val index: Int,
)

internal enum class SemanticVfxAction {
    CUT,
    CROSS_CUT,
    FLURRY,
    CHARGE,
    EXECUTE,
    PIERCE,
    SHOT,
    VOLLEY,
    CHAIN,
    BEAM,
    DESCEND,
    ASCEND,
    DIVE,
    SPIN,
    BURST,
    COLLAPSE,
    TRAP,
}

internal enum class SemanticVfxFlow {
    LEFT_TO_RIGHT,
    TOP_TO_BOTTOM,
    BOTTOM_TO_TOP,
    CENTER_OUT,
    OUTSIDE_IN,
    CLOCKWISE,
}

internal enum class SemanticImpactStyle { EDGE, POINT, FRACTURE, RING, NONE }

internal data class SemanticVfxPlan(
    val action: SemanticVfxAction,
    val recipe: ModularRecipe,
    val flow: SemanticVfxFlow,
    val impactStyle: SemanticImpactStyle,
    val reason: String,
)

internal data class SkillVfxIntensityProfile(
    val tier: Int,
    val primaryScale: Float,
    val primaryAlpha: Float,
    val backdropScale: Float,
    val impactScale: Float,
    val finalLayerCount: Int,
)

internal enum class ClassVfxPath { FOCUS, CROSS, CONVERGE, RUPTURE }

internal enum class ClassImpactLayer { POINT, FRACTURE, RING }

/** The direction an authored primary image already points in before runtime rotation. */
internal data class ClassPrimaryAxis(
    val nativeDegrees: Float?,
    val label: String,
)

/** Semantic travel from the authored image's starting plane into the target contact point. */
internal data class ClassPrimaryMotion(
    val startXFraction: Float,
    val startYFraction: Float,
    val targetDegrees: Float?,
)

/**
 * Variation in the authored depth path must preserve the semantic attack axis. A downward
 * strike may lean a little left or right, but it must never become a horizontal projectile.
 */
internal fun classAuthoredPathStart(
    motion: ClassPrimaryMotion,
    signature: ClassVfxSignature,
): Offset {
    val routeSign = if (signature.tierVariant.mod(2) == 0) -1f else 1f
    val horizontal = motion.targetDegrees == 0f || motion.targetDegrees == 180f
    val vertical = motion.targetDegrees == 90f || motion.targetDegrees == -90f
    val crossOffset = when {
        horizontal -> Offset(0f, 0.07f * routeSign)
        vertical -> Offset(0.07f * routeSign, 0f)
        else -> Offset(0.06f * routeSign, -0.05f * routeSign)
    }
    val base = Offset(motion.startXFraction, motion.startYFraction)
    return when (signature.path) {
        ClassVfxPath.FOCUS -> base
        ClassVfxPath.CROSS -> base + crossOffset
        ClassVfxPath.CONVERGE -> Offset(base.x * 1.22f, base.y * 1.22f) - crossOffset * 0.7f
        ClassVfxPath.RUPTURE -> Offset(base.x * 0.64f, base.y * 0.64f) + crossOffset * 1.15f
    }
}

/**
 * Data-driven class pack used by the full VFX refresh. Each skill combines a class-owned
 * primary, a separate depth overlay, the existing contact core, and a tier/path finisher.
 * No image is stretched to represent progression by itself.
 */
internal data class ClassVfxSignature(
    val candidate: Int,
    val growthBand: Int,
    val tierVariant: Int,
    val path: ClassVfxPath,
)

internal data class ClassCandidateBandKey(
    val heroClass: HeroClass,
    /** Zero-based SkillDefinition candidate. */
    val candidate: Int,
    val growthBand: Int,
) {
    val stableId: String = "${heroClass.name.lowercase()}:c${candidate + 1}:b${growthBand + 1}"
}

/**
 * Asset packs can implement this without changing choreography or browser export code. The
 * fallback keeps the currently shipped candidate resource until a band-authored primary exists.
 */
internal fun interface ClassBandPrimaryResolver {
    @DrawableRes
    fun resolve(key: ClassCandidateBandKey, @DrawableRes fallback: Int): Int
}

internal val DefaultClassBandPrimaryResolver = ClassBandPrimaryResolver { _, fallback -> fallback }
internal val AuthoredClassBandPrimaryResolver = DefaultClassBandPrimaryResolver

internal fun classCandidateBandKey(definition: SkillDefinition): ClassCandidateBandKey {
    val signature = classVfxSignature(definition)
    return ClassCandidateBandKey(
        heroClass = definition.heroClass,
        candidate = definition.candidate,
        growthBand = signature.growthBand,
    )
}

internal data class ClassBandChoreography(
    val stableId: String,
    val targetOffset: Offset,
    val anticipationOffsetMillis: Int,
    val primaryScale: Float,
    val secondaryScale: Float,
    val rotationBiasDegrees: Float,
    val debrisSpread: Float,
    val residualLift: Float,
    val finisherOrbit: Float,
)

/** 150 stable class x candidate x growth-band compositions using the same authored asset pack. */
internal fun classBandChoreography(definition: SkillDefinition): ClassBandChoreography {
    val key = classCandidateBandKey(definition)
    val classX = floatArrayOf(-.018f, -.052f, .052f, .020f, -.024f, .032f)[key.heroClass.ordinal]
    val classY = floatArrayOf(.024f, -.018f, -.024f, -.036f, -.010f, .018f)[key.heroClass.ordinal]
    val candidateX = floatArrayOf(-.050f, .036f, -.020f, .052f, 0f)[key.candidate]
    val candidateY = floatArrayOf(-.026f, .018f, .040f, -.012f, .026f)[key.candidate]
    val bandProgress = key.growthBand / 4f
    val routeSign = if ((key.heroClass.ordinal + key.candidate + key.growthBand).mod(2) == 0) -1f else 1f
    return ClassBandChoreography(
        stableId = key.stableId,
        targetOffset = Offset(
            classX + candidateX * (.72f + bandProgress * .34f) + routeSign * key.growthBand * .004f,
            classY + candidateY * (.70f + bandProgress * .25f) - routeSign * key.growthBand * .003f,
        ),
        anticipationOffsetMillis = ((key.heroClass.ordinal * 7 + key.candidate * 3 + key.growthBand * 5).mod(5) - 2) * 12,
        primaryScale = .94f + key.growthBand * .035f + key.candidate * .008f,
        secondaryScale = .90f + key.growthBand * .042f + (4 - key.candidate) * .007f,
        rotationBiasDegrees = routeSign * (3f + key.candidate * 2.2f + key.growthBand * 1.4f),
        debrisSpread = .82f + key.growthBand * .13f + key.candidate * .025f,
        residualLift = -.012f - key.growthBand * .008f + key.heroClass.ordinal * .002f,
        finisherOrbit = routeSign * (.035f + key.growthBand * .009f + key.candidate * .004f),
    )
}

/**
 * A choreography profile, not an asset selector.  Each authored path owns a different
 * depth rhythm across all five planes so the same candidate art does not collapse into
 * one central flash.  Values are intentionally small enough to preserve the damage lane.
 */
internal data class ClassVfxPathProfile(
    val secondaryLeadMillis: Int,
    val secondarySettleMillis: Int,
    val primaryLeadMillis: Int,
    val primarySettleMillis: Int,
    val contactDelayMillis: Int,
    val debrisDelayMillis: Int,
    val residualDelayMillis: Int,
    val secondaryScale: Float,
    val primaryScale: Float,
    val contactScale: Float,
    val rotationOffsetDegrees: Float,
    val secondaryRotationDegrees: Float,
    val debrisVector: Offset,
    val residualVector: Offset,
    val mirror: Float,
)

internal fun classVfxPathProfile(path: ClassVfxPath): ClassVfxPathProfile = when (path) {
    ClassVfxPath.FOCUS -> ClassVfxPathProfile(
        secondaryLeadMillis = -190,
        secondarySettleMillis = 55,
        primaryLeadMillis = -150,
        primarySettleMillis = 35,
        contactDelayMillis = 0,
        debrisDelayMillis = 24,
        residualDelayMillis = 80,
        secondaryScale = 0.92f,
        primaryScale = 0.94f,
        contactScale = 0.90f,
        rotationOffsetDegrees = 0f,
        secondaryRotationDegrees = -7f,
        debrisVector = Offset(0.02f, -0.10f),
        residualVector = Offset(-0.04f, 0.02f),
        mirror = 1f,
    )
    ClassVfxPath.CROSS -> ClassVfxPathProfile(
        secondaryLeadMillis = -210,
        secondarySettleMillis = 15,
        primaryLeadMillis = -135,
        primarySettleMillis = 18,
        contactDelayMillis = 6,
        debrisDelayMillis = 18,
        residualDelayMillis = 52,
        secondaryScale = 1.04f,
        primaryScale = 1.00f,
        contactScale = 0.96f,
        rotationOffsetDegrees = 10f,
        secondaryRotationDegrees = -14f,
        debrisVector = Offset(-0.11f, -0.04f),
        residualVector = Offset(0.08f, -0.01f),
        mirror = -1f,
    )
    ClassVfxPath.CONVERGE -> ClassVfxPathProfile(
        secondaryLeadMillis = -240,
        secondarySettleMillis = -12,
        primaryLeadMillis = -185,
        primarySettleMillis = 6,
        contactDelayMillis = 12,
        debrisDelayMillis = 34,
        residualDelayMillis = 96,
        secondaryScale = 1.14f,
        primaryScale = 0.92f,
        contactScale = 1.08f,
        rotationOffsetDegrees = -8f,
        secondaryRotationDegrees = 18f,
        debrisVector = Offset(0.12f, -0.07f),
        residualVector = Offset(-0.08f, 0.04f),
        mirror = 1f,
    )
    ClassVfxPath.RUPTURE -> ClassVfxPathProfile(
        secondaryLeadMillis = -155,
        secondarySettleMillis = 78,
        primaryLeadMillis = -105,
        primarySettleMillis = 58,
        contactDelayMillis = 0,
        debrisDelayMillis = 8,
        residualDelayMillis = 38,
        secondaryScale = 0.98f,
        primaryScale = 1.10f,
        contactScale = 1.16f,
        rotationOffsetDegrees = 14f,
        secondaryRotationDegrees = 9f,
        debrisVector = Offset(-0.08f, -0.13f),
        residualVector = Offset(0.06f, 0.05f),
        mirror = -1f,
    )
}

internal data class AuthoredAssetPlacement(
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val rotatedWidth: Float,
    val rotatedHeight: Float,
)

/** Raw full-bleed placement used only by an explicitly reviewed class recipe. */
internal fun rawAuthoredAssetPlacement(
    centerX: Float,
    centerY: Float,
    width: Float,
    height: Float,
    rotationDegrees: Float,
): AuthoredAssetPlacement {
    val radians = Math.toRadians(rotationDegrees.toDouble())
    val cosValue = abs(cos(radians).toFloat())
    val sinValue = abs(sin(radians).toFloat())
    return AuthoredAssetPlacement(
        centerX = centerX,
        centerY = centerY,
        width = width,
        height = height,
        rotatedWidth = cosValue * width + sinValue * height,
        rotatedHeight = sinValue * width + cosValue * height,
    )
}

/** Stable role names shared by the Android renderer and the browser VFX preview export. */
internal enum class AuthoredLayerRole {
    SECONDARY,
    PRIMARY,
    CONTACT,
    DEBRIS,
    RESIDUAL,
    FINISHER_RING,
    FINISHER_ECHO,
    WARRIOR_ACCENT,
}

private data class CreationEarthquakeSpriteFrame(
    val startMillis: Int,
    val endMillis: Int,
    @DrawableRes val assetId: Int,
    val impact: Boolean = false,
    val final: Boolean = false,
)

internal val creationEarthquakeSpriteAssetIds = intArrayOf(
    R.drawable.vfx11_warrior_genesis_01,
    R.drawable.vfx11_warrior_genesis_02,
    R.drawable.vfx11_warrior_genesis_03,
    R.drawable.vfx11_warrior_genesis_04,
    R.drawable.vfx11_warrior_genesis_05,
    R.drawable.vfx11_warrior_genesis_06,
    R.drawable.vfx11_warrior_genesis_07,
    R.drawable.vfx11_warrior_genesis_08,
    R.drawable.vfx11_warrior_genesis_09,
    R.drawable.vfx11_warrior_genesis_10,
    R.drawable.vfx11_warrior_genesis_11,
)

private val creationEarthquakeSpriteFrames = listOf(
    CreationEarthquakeSpriteFrame(80, 125, creationEarthquakeSpriteAssetIds[0]),
    CreationEarthquakeSpriteFrame(125, 170, creationEarthquakeSpriteAssetIds[1]),
    CreationEarthquakeSpriteFrame(170, 215, creationEarthquakeSpriteAssetIds[2]),
    CreationEarthquakeSpriteFrame(215, 255, creationEarthquakeSpriteAssetIds[3]),
    CreationEarthquakeSpriteFrame(255, 295, creationEarthquakeSpriteAssetIds[4]),
    CreationEarthquakeSpriteFrame(295, 330, creationEarthquakeSpriteAssetIds[5]),
    CreationEarthquakeSpriteFrame(330, 360, creationEarthquakeSpriteAssetIds[6]),
    CreationEarthquakeSpriteFrame(360, 390, creationEarthquakeSpriteAssetIds[7]),
    CreationEarthquakeSpriteFrame(390, 420, creationEarthquakeSpriteAssetIds[8]),
    CreationEarthquakeSpriteFrame(420, 500, creationEarthquakeSpriteAssetIds[9], impact = true),
    CreationEarthquakeSpriteFrame(500, 980, creationEarthquakeSpriteAssetIds[10], final = true),
)

internal fun creationEarthquakeGlowAlpha(
    elapsedMillis: Int,
    lastHitMillis: Int,
    reducedMotion: Boolean,
): Float {
    if (reducedMotion) return .42f
    val progress = when {
        elapsedMillis < 0 -> 0f
        elapsedMillis < 110 -> elapsedMillis / 110f
        elapsedMillis < lastHitMillis + 100 -> 1f
        else -> 1f - modularFraction(elapsedMillis, lastHitMillis + 100, SKILL_VFX_END_MILLIS)
    }
    return progress.coerceIn(0f, 1f) * .58f
}

private fun creationEarthquakeRenderableFramePlan(
    definition: SkillDefinition,
    elapsedMillis: Int,
    reducedMotion: Boolean,
    viewportWidth: Float,
    viewportHeight: Float,
): List<AuthoredLayerFrame> {
    val contactFrames = authoredClassFramePlan(
        definition = definition,
        elapsedMillis = elapsedMillis,
        reducedMotion = reducedMotion,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
    ).filter { it.role == AuthoredLayerRole.CONTACT }
    val spriteSpec = if (reducedMotion) {
        if (elapsedMillis in 420 until 780) creationEarthquakeSpriteFrames.last() else null
    } else {
        creationEarthquakeSpriteFrames.firstOrNull {
            elapsedMillis >= it.startMillis && elapsedMillis < it.endMillis
        }
    } ?: return contactFrames
    val impactScale = if (spriteSpec.impact && !reducedMotion) {
        .94f + .08f * modularEaseOut(modularFraction(elapsedMillis, 420, 475))
    } else {
        1f
    }
    val alpha = if (reducedMotion) {
        .78f
    } else if (spriteSpec.final && elapsedMillis > 780) {
        1f - modularFraction(elapsedMillis, 780, 980)
    } else {
        1f
    }
    val spriteWidthFraction = .8864266f * impactScale
    val spriteFrame = AuthoredLayerFrame(
        role = AuthoredLayerRole.PRIMARY,
        assetId = spriteSpec.assetId,
        instance = 0,
        hitIndex = definition.hitTimingsMillis.lastIndex,
        xFraction = .50f,
        yFraction = .72f,
        widthFraction = spriteWidthFraction,
        heightFraction = viewportWidth * spriteWidthFraction / viewportHeight,
        rotationDegrees = 0f,
        alpha = alpha.coerceIn(0f, 1f),
        reveal = 1f,
        mirror = 1f,
        startMillis = if (reducedMotion) 420 else spriteSpec.startMillis,
        endMillis = if (reducedMotion) 780 else spriteSpec.endMillis,
    )
    return listOf(spriteFrame) + contactFrames
}

/** Exact authored frame list consumed by both Android runtime and the browser exporter. */
internal fun authoredRenderableFramePlan(
    definition: SkillDefinition,
    elapsedMillis: Int,
    reducedMotion: Boolean,
    viewportWidth: Float,
    viewportHeight: Float,
): List<AuthoredLayerFrame> {
    if (isCreationEarthquake(definition)) {
        return creationEarthquakeRenderableFramePlan(
            definition = definition,
            elapsedMillis = elapsedMillis,
            reducedMotion = reducedMotion,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
        )
    }
    return authoredClassFramePlan(definition, elapsedMillis, reducedMotion, viewportWidth, viewportHeight)
}

internal enum class AuthoredAssetDrawMode {
    AUTHORED_SCREEN,
    LEGACY_UNTINTED,
    LEGACY_TINTED,
    LEGACY_STEEL_REVEAL,
}

/**
 * One authoritative, viewport-normalized draw command.  Keeping the resource id and the complete
 * transform/timing state here lets the web preview consume the same PSV rows as Android instead
 * of rebuilding the choreography from names or tiers.
 */
internal data class AuthoredLayerFrame(
    val role: AuthoredLayerRole,
    @DrawableRes val assetId: Int,
    val instance: Int,
    val hitIndex: Int,
    val xFraction: Float,
    val yFraction: Float,
    val widthFraction: Float,
    val heightFraction: Float,
    val rotationDegrees: Float,
    val alpha: Float,
    val reveal: Float,
    val mirror: Float,
    val startMillis: Int,
    val endMillis: Int,
    /** Deprecated PSV compatibility column. Runtime and browser must leave this null and inert. */
    val safeAlphaCap: Float? = null,
    val drawMode: AuthoredAssetDrawMode = AuthoredAssetDrawMode.AUTHORED_SCREEN,
    /** Packed ARGB value used only by [AuthoredAssetDrawMode.LEGACY_TINTED]. */
    val tintArgb: Long? = null,
)

internal const val AUTHORED_FRAME_PSV_HEADER =
    "role|assetId|instance|hitIndex|x|y|width|height|rotation|alpha|reveal|mirror|start|end|safeAlphaCap|drawMode|tintArgb"

internal fun AuthoredLayerFrame.toPsv(): String = listOf(
    role.name,
    assetId,
    instance,
    hitIndex,
    xFraction,
    yFraction,
    widthFraction,
    heightFraction,
    rotationDegrees,
    alpha,
    reveal,
    mirror,
    startMillis,
    endMillis,
    safeAlphaCap ?: "",
    drawMode.name,
    tintArgb?.toString(16) ?: "",
).joinToString("|")

internal fun safeAuthoredAssetPlacement(
    canvasWidth: Float,
    canvasHeight: Float,
    centerX: Float,
    centerY: Float,
    width: Float,
    height: Float,
    rotationDegrees: Float,
    insetFraction: Float = 0.035f,
    overflowFraction: Float = 0f,
): AuthoredAssetPlacement {
    require(overflowFraction >= 0f)
    val insetX = canvasWidth * insetFraction
    val insetY = canvasHeight * insetFraction
    val leftBound = if (overflowFraction > 0f) -canvasWidth * overflowFraction else insetX
    val rightBound = if (overflowFraction > 0f) canvasWidth * (1f + overflowFraction) else canvasWidth - insetX
    val topBound = if (overflowFraction > 0f) -canvasHeight * overflowFraction else insetY
    val bottomBound = if (overflowFraction > 0f) canvasHeight * (1f + overflowFraction) else canvasHeight - insetY
    val radians = Math.toRadians(rotationDegrees.toDouble())
    val cosValue = abs(cos(radians).toFloat())
    val sinValue = abs(sin(radians).toFloat())
    val rawRotatedWidth = cosValue * width + sinValue * height
    val rawRotatedHeight = sinValue * width + cosValue * height
    val scale = minOf(
        1f,
        (rightBound - leftBound) / rawRotatedWidth.coerceAtLeast(1f),
        (bottomBound - topBound) / rawRotatedHeight.coerceAtLeast(1f),
    ).coerceAtLeast(0f)
    val safeWidth = width * scale
    val safeHeight = height * scale
    val rotatedWidth = (cosValue * safeWidth + sinValue * safeHeight).coerceAtMost(rightBound - leftBound)
    val rotatedHeight = (sinValue * safeWidth + cosValue * safeHeight).coerceAtMost(bottomBound - topBound)
    val minX = leftBound + rotatedWidth / 2f
    val maxX = rightBound - rotatedWidth / 2f
    val minY = topBound + rotatedHeight / 2f
    val maxY = bottomBound - rotatedHeight / 2f
    return AuthoredAssetPlacement(
        centerX = if (minX <= maxX) centerX.coerceIn(minX, maxX) else canvasWidth / 2f,
        centerY = if (minY <= maxY) centerY.coerceIn(minY, maxY) else canvasHeight / 2f,
        width = safeWidth,
        height = safeHeight,
        rotatedWidth = rotatedWidth,
        rotatedHeight = rotatedHeight,
    )
}

internal data class AuthoredPrimaryTrajectory(
    val start: AuthoredAssetPlacement,
    val contact: AuthoredAssetPlacement,
)

/**
 * Fits a primary once for its whole travel segment, reserving enough rotated-bounds room for
 * both the anticipation origin and the contact point. Clamping each frame independently made
 * tall columns and outside-in projectiles appear stationary when both endpoints hit the same
 * safe-placement edge.
 */
internal fun safeAuthoredPrimaryTrajectory(
    canvasWidth: Float,
    canvasHeight: Float,
    desiredStartX: Float,
    desiredStartY: Float,
    desiredContactX: Float,
    desiredContactY: Float,
    width: Float,
    height: Float,
    rotationDegrees: Float,
    insetFraction: Float = 0.035f,
    // Leave enough semantic travel that a 10 ms presentation sample still observes at least
    // four percent motion for the fastest CONVERGE profiles after easing has begun.
    minimumTravelFraction: Float = 0.18f,
    overflowFraction: Float = 0f,
): AuthoredPrimaryTrajectory {
    require(overflowFraction >= 0f)
    val insetX = canvasWidth * insetFraction
    val insetY = canvasHeight * insetFraction
    val leftBound = if (overflowFraction > 0f) -canvasWidth * overflowFraction else insetX
    val rightBound = if (overflowFraction > 0f) canvasWidth * (1f + overflowFraction) else canvasWidth - insetX
    val topBound = if (overflowFraction > 0f) -canvasHeight * overflowFraction else insetY
    val bottomBound = if (overflowFraction > 0f) canvasHeight * (1f + overflowFraction) else canvasHeight - insetY
    val innerWidth = (rightBound - leftBound).coerceAtLeast(1f)
    val innerHeight = (bottomBound - topBound).coerceAtLeast(1f)
    val desiredDx = desiredContactX - desiredStartX
    val desiredDy = desiredContactY - desiredStartY
    val reservedX = minOf(abs(desiredDx), canvasWidth * minimumTravelFraction)
    val reservedY = minOf(abs(desiredDy), canvasHeight * minimumTravelFraction)
    val radians = Math.toRadians(rotationDegrees.toDouble())
    val cosValue = abs(cos(radians).toFloat())
    val sinValue = abs(sin(radians).toFloat())
    val rotatedWidth = cosValue * width + sinValue * height
    val rotatedHeight = sinValue * width + cosValue * height
    val trajectoryScale = minOf(
        1f,
        (innerWidth - reservedX).coerceAtLeast(1f) / rotatedWidth.coerceAtLeast(1f),
        (innerHeight - reservedY).coerceAtLeast(1f) / rotatedHeight.coerceAtLeast(1f),
    ).coerceAtLeast(0f)
    val safeWidth = width * trajectoryScale
    val safeHeight = height * trajectoryScale
    val rawStart = safeAuthoredAssetPlacement(
        canvasWidth,
        canvasHeight,
        desiredStartX,
        desiredStartY,
        safeWidth,
        safeHeight,
        rotationDegrees,
        insetFraction,
        overflowFraction,
    )
    val rawContact = safeAuthoredAssetPlacement(
        canvasWidth,
        canvasHeight,
        desiredContactX,
        desiredContactY,
        safeWidth,
        safeHeight,
        rotationDegrees,
        insetFraction,
        overflowFraction,
    )
    fun fitAxis(
        desiredStart: Float,
        desiredContact: Float,
        clampedStart: Float,
        clampedContact: Float,
        minimum: Float,
        maximum: Float,
        reserve: Float,
    ): Pair<Float, Float> {
        if (reserve <= 0f || desiredStart == desiredContact) return clampedStart to clampedContact
        val direction = if (desiredContact > desiredStart) 1f else -1f
        if (abs(clampedContact - clampedStart) >= reserve - 0.001f) return clampedStart to clampedContact
        return if (direction > 0f) {
            var contact = clampedContact
            var start = (contact - reserve).coerceAtLeast(minimum)
            contact = (start + reserve).coerceAtMost(maximum)
            start = contact - reserve
            start to contact
        } else {
            var contact = clampedContact
            var start = (contact + reserve).coerceAtMost(maximum)
            contact = (start - reserve).coerceAtLeast(minimum)
            start = contact + reserve
            start to contact
        }
    }
    val minX = leftBound + rawStart.rotatedWidth / 2f
    val maxX = rightBound - rawStart.rotatedWidth / 2f
    val minY = topBound + rawStart.rotatedHeight / 2f
    val maxY = bottomBound - rawStart.rotatedHeight / 2f
    val (startX, contactX) = fitAxis(
        desiredStartX, desiredContactX, rawStart.centerX, rawContact.centerX, minX, maxX, reservedX,
    )
    val (startY, contactY) = fitAxis(
        desiredStartY, desiredContactY, rawStart.centerY, rawContact.centerY, minY, maxY, reservedY,
    )
    return AuthoredPrimaryTrajectory(
        start = rawStart.copy(centerX = startX, centerY = startY),
        contact = rawContact.copy(centerX = contactX, centerY = contactY),
    )
}

internal data class ClassLayeredAssetSpec(
    @DrawableRes val primary: Int,
    /** Full A/B body set retained for export and asset QA. */
    @DrawableRes val primaryA: Int,
    @DrawableRes val primaryB: Int,
    @DrawableRes val secondary: Int,
    @DrawableRes val impactPoint: Int,
    @DrawableRes val impactFracture: Int,
    @DrawableRes val impactRing: Int,
    @DrawableRes val debrisPoint: Int,
    @DrawableRes val debrisFracture: Int,
    @DrawableRes val debrisRing: Int,
    @DrawableRes val residual: Int,
    /** Dedicated hooks; the current pack falls back to depth assets, never contact assets. */
    @DrawableRes val finisherRing: Int,
    @DrawableRes val finisherEcho: Int,
)

/** Single source of truth shared by Compose preloading and runtime/export parity tests. */
internal fun ClassLayeredAssetSpec.authoredRuntimeAssetIds(): IntArray = intArrayOf(
    primaryA,
    primaryB,
    secondary,
    impactPoint,
    impactFracture,
    impactRing,
    debrisPoint,
    debrisFracture,
    debrisRing,
    residual,
    finisherRing,
    finisherEcho,
).distinct().toIntArray()

internal fun classVfxSignature(definition: SkillDefinition): ClassVfxSignature {
    val tierIndex = (definition.unlockLevel / 5 - 1).coerceIn(0, 19)
    return ClassVfxSignature(
        candidate = (definition.candidate + 1).coerceIn(1, 5),
        growthBand = (tierIndex / 4).coerceIn(0, 4),
        tierVariant = tierIndex.mod(4),
        path = ClassVfxPath.entries[(tierIndex + definition.candidate - 1).mod(4)],
    )
}

internal fun classImpactLayerFor(
    definition: SkillDefinition,
    hitIndex: Int,
): ClassImpactLayer {
    val isFinal = hitIndex == definition.hitTimingsMillis.lastIndex
    if (!isFinal) {
        return if ((definition.candidate + hitIndex).mod(2) == 0) {
            ClassImpactLayer.POINT
        } else {
            ClassImpactLayer.FRACTURE
        }
    }
    if (definition.heroClass == HeroClass.PALADIN) {
        return when (paladinCandidateVfxAssets(definition.candidate).impactGrammar) {
            PaladinImpactGrammar.EDGE -> ClassImpactLayer.POINT
            PaladinImpactGrammar.WEIGHT -> ClassImpactLayer.FRACTURE
            PaladinImpactGrammar.RADIANT -> ClassImpactLayer.RING
        }
    }
    return when (semanticVfxPlan(definition).impactStyle) {
        SemanticImpactStyle.POINT -> ClassImpactLayer.POINT
        SemanticImpactStyle.FRACTURE -> ClassImpactLayer.FRACTURE
        SemanticImpactStyle.RING -> ClassImpactLayer.RING
        SemanticImpactStyle.EDGE -> if (definition.candidate.mod(2) == 0) {
            ClassImpactLayer.FRACTURE
        } else {
            ClassImpactLayer.POINT
        }
        SemanticImpactStyle.NONE -> ClassImpactLayer.entries[
            (definition.candidate + definition.unlockLevel / 5).mod(ClassImpactLayer.entries.size)
        ]
    }
}

internal fun classPrimaryAxis(
    heroClass: HeroClass,
    candidate: Int,
): ClassPrimaryAxis {
    val index = candidate.coerceIn(0, 4)
    return when (heroClass) {
        HeroClass.WARRIOR -> arrayOf(
            ClassPrimaryAxis(-35f, "diagonal slash"),
            ClassPrimaryAxis(90f, "vertical descent"),
            ClassPrimaryAxis(0f, "horizontal charge"),
            ClassPrimaryAxis(null, "radial earth"),
            ClassPrimaryAxis(-35f, "diagonal flurry"),
        )[index]
        HeroClass.ROGUE -> arrayOf(
            ClassPrimaryAxis(-45f, "diagonal needles"),
            ClassPrimaryAxis(135f, "shadow convergence"),
            ClassPrimaryAxis(null, "radial poison"),
            ClassPrimaryAxis(null, "radial wire"),
            ClassPrimaryAxis(-35f, "diagonal execute"),
        )[index]
        HeroClass.RANGER -> arrayOf(
            ClassPrimaryAxis(0f, "horizontal precision"),
            ClassPrimaryAxis(null, "radial volley"),
            ClassPrimaryAxis(135f, "diagonal wind"),
            ClassPrimaryAxis(45f, "diagonal beast"),
            ClassPrimaryAxis(90f, "downward celestial"),
        )[index]
        HeroClass.MAGE -> arrayOf(
            ClassPrimaryAxis(-90f, "rising fire"),
            ClassPrimaryAxis(null, "radial ice"),
            ClassPrimaryAxis(null, "radial lightning"),
            ClassPrimaryAxis(null, "radial arcane"),
            ClassPrimaryAxis(null, "radial gravity"),
        )[index]
        HeroClass.CLERIC -> arrayOf(
            ClassPrimaryAxis(90f, "descending light"),
            ClassPrimaryAxis(90f, "vertical judgment"),
            ClassPrimaryAxis(null, "radial exorcism"),
            ClassPrimaryAxis(-90f, "rising sacred flame"),
            ClassPrimaryAxis(90f, "descending seraph"),
        )[index]
        HeroClass.PALADIN -> arrayOf(
            ClassPrimaryAxis(45f, "diagonal sword"),
            ClassPrimaryAxis(null, "radial shield charge"),
            ClassPrimaryAxis(90f, "vertical hammer"),
            ClassPrimaryAxis(0f, "horizontal holy wave"),
            ClassPrimaryAxis(90f, "vertical judgment"),
        )[index]
    }
}

internal fun classPrimaryMotion(
    definition: SkillDefinition,
    hitIndex: Int,
): ClassPrimaryMotion {
    val plan = semanticVfxPlan(definition)
    if (
        plan.flow == SemanticVfxFlow.LEFT_TO_RIGHT &&
        plan.action in setOf(SemanticVfxAction.FLURRY, SemanticVfxAction.CROSS_CUT) &&
        hitIndex.mod(2) == 1
    ) {
        return ClassPrimaryMotion(0.25f, 0f, 180f)
    }
    return when (plan.flow) {
        SemanticVfxFlow.LEFT_TO_RIGHT -> ClassPrimaryMotion(-0.27f, 0f, 0f)
        SemanticVfxFlow.TOP_TO_BOTTOM -> ClassPrimaryMotion(0f, -0.25f, 90f)
        SemanticVfxFlow.BOTTOM_TO_TOP -> ClassPrimaryMotion(0f, 0.22f, -90f)
        SemanticVfxFlow.CENTER_OUT -> ClassPrimaryMotion(0f, 0f, null)
        SemanticVfxFlow.CLOCKWISE -> ClassPrimaryMotion(0f, 0f, null)
        SemanticVfxFlow.OUTSIDE_IN -> when ((definition.candidate + hitIndex).mod(4)) {
            0 -> ClassPrimaryMotion(-0.28f, 0f, 0f)
            1 -> ClassPrimaryMotion(0f, -0.24f, 90f)
            2 -> ClassPrimaryMotion(0.28f, 0f, 180f)
            else -> ClassPrimaryMotion(0f, 0.24f, -90f)
        }
    }
}

internal fun classPrimaryRotationDegrees(
    definition: SkillDefinition,
    hitIndex: Int,
): Float {
    val native = classPrimaryAxis(definition.heroClass, definition.candidate).nativeDegrees
        ?: return 0f
    val target = classPrimaryMotion(definition, hitIndex).targetDegrees ?: return 0f
    var delta = (target - native).mod(360f)
    if (delta > 180f) delta -= 360f
    return delta
}

internal data class SteelSlashVfxFrame(
    val visible: Boolean,
    val reveal: Float,
    val alpha: Float,
)

internal data class FierceDownwardStrikeVfxFrame(
    val visible: Boolean,
    val reveal: Float,
    val alpha: Float,
    val centerYFraction: Float,
    val scale: Float,
)

internal fun fierceDownwardStrikeVfxFrame(
    elapsedMillis: Int,
    reducedMotion: Boolean,
): FierceDownwardStrikeVfxFrame {
    if (reducedMotion) {
        return if (elapsedMillis in 420 until 620) {
            FierceDownwardStrikeVfxFrame(true, 1f, 0.48f, 0.38f, 0.82f)
        } else {
            FierceDownwardStrikeVfxFrame(false, 0f, 0f, 0.38f, 0.82f)
        }
    }
    return when {
        elapsedMillis < 250 || elapsedMillis >= 720 ->
            FierceDownwardStrikeVfxFrame(false, 0f, 0f, 0.23f, 0.76f)
        elapsedMillis < 340 -> {
            val progress = modularEaseOut(modularFraction(elapsedMillis, 250, 340))
            FierceDownwardStrikeVfxFrame(
                visible = true,
                reveal = 0.12f + 0.16f * progress,
                alpha = 0.18f + 0.20f * progress,
                centerYFraction = 0.23f + 0.01f * progress,
                scale = 0.76f + 0.02f * progress,
            )
        }
        elapsedMillis < 420 -> {
            val progress = modularFraction(elapsedMillis, 340, 420)
            val accelerated = progress * progress * progress
            FierceDownwardStrikeVfxFrame(
                visible = true,
                reveal = 0.28f + 0.72f * accelerated,
                alpha = 0.38f + 0.58f * modularEaseOut(progress),
                centerYFraction = 0.24f + 0.14f * accelerated,
                scale = 0.78f + 0.04f * accelerated,
            )
        }
        elapsedMillis < 500 -> {
            val progress = modularFraction(elapsedMillis, 420, 500)
            FierceDownwardStrikeVfxFrame(
                visible = true,
                reveal = 1f,
                alpha = 0.54f - 0.24f * modularEaseOut(progress),
                centerYFraction = 0.38f,
                scale = 0.82f,
            )
        }
        else -> {
            val progress = modularFraction(elapsedMillis, 500, 720)
            FierceDownwardStrikeVfxFrame(
                visible = true,
                reveal = 1f,
                alpha = 0.30f * (1f - progress),
                centerYFraction = 0.38f,
                scale = 0.82f + 0.03f * modularEaseOut(progress),
            )
        }
    }
}

internal data class ChargingThrustVfxFrame(
    val visible: Boolean,
    val xFraction: Float,
    val yFraction: Float,
    val scale: Float,
    val alpha: Float,
)

internal enum class WarriorNonSlashFamily {
    DESCENT,
    CHARGE,
    ERUPTION,
    SHOCKWAVE,
    COLLAPSE,
}

/** Candidate-column identity used for the three warrior non-slash rhythm systems. */
internal enum class WarriorNonSlashColumn {
    HEAVY,
    CHARGE,
    EARTH,
}

internal fun warriorNonSlashColumn(definition: SkillDefinition): WarriorNonSlashColumn? {
    if (warriorNonSlashSignature(definition) == null) return null
    return when (definition.candidate) {
        1 -> WarriorNonSlashColumn.HEAVY
        2 -> WarriorNonSlashColumn.CHARGE
        3 -> WarriorNonSlashColumn.EARTH
        else -> null
    }
}

/**
 * A stable authored signature for warrior non-slash skills.  Up to four route variants inside
 * each of the five intensity bands produce twenty distinct silhouettes per action family,
 * instead of reusing one animation with a larger scale.
 */
internal data class WarriorNonSlashSignature(
    val family: WarriorNonSlashFamily,
    val intensityBand: Int,
    val routeVariant: Int,
    val residualEndMillis: Int,
)

internal fun warriorNonSlashSignature(definition: SkillDefinition): WarriorNonSlashSignature? {
    if (definition.heroClass != HeroClass.WARRIOR) return null
    val action = semanticVfxPlan(definition).action
    val family = when (action) {
        SemanticVfxAction.DESCEND -> WarriorNonSlashFamily.DESCENT
        SemanticVfxAction.CHARGE -> WarriorNonSlashFamily.CHARGE
        SemanticVfxAction.ASCEND -> WarriorNonSlashFamily.ERUPTION
        SemanticVfxAction.BURST -> WarriorNonSlashFamily.SHOCKWAVE
        SemanticVfxAction.COLLAPSE -> WarriorNonSlashFamily.COLLAPSE
        else -> return null
    }
    val levelIndex = (definition.unlockLevel / 5 - 1).coerceIn(0, 19)
    val intensityBand = (levelIndex / 4).coerceIn(0, 4)
    // Candidate is stable within each warrior action column, so level progression must be
    // the dominant phase and rotate the authored routes instead of repeating one silhouette.
    val routeVariant = (levelIndex + definition.candidate).mod(4)
    return WarriorNonSlashSignature(
        family = family,
        intensityBand = intensityBand,
        routeVariant = routeVariant,
        residualEndMillis = 500 + intensityBand * 58,
    )
}

internal fun chargingThrustVfxFrame(
    elapsedMillis: Int,
    reducedMotion: Boolean,
): ChargingThrustVfxFrame {
    if (reducedMotion) {
        return if (elapsedMillis in 420 until 620) {
            ChargingThrustVfxFrame(true, 0.50f, 0.60f, 0.72f, 0.48f)
        } else {
            ChargingThrustVfxFrame(false, 0.50f, 0.60f, 0.72f, 0f)
        }
    }
    return when {
        elapsedMillis < 250 || elapsedMillis >= 720 ->
            ChargingThrustVfxFrame(false, 0.12f, 0.62f, 0.82f, 0f)
        elapsedMillis < 340 -> {
            val progress = modularEaseOut(modularFraction(elapsedMillis, 250, 340))
            ChargingThrustVfxFrame(
                visible = true,
                xFraction = 0.12f + 0.04f * progress,
                yFraction = 0.62f,
                scale = 0.82f,
                alpha = 0.26f + 0.16f * progress,
            )
        }
        elapsedMillis < 420 -> {
            val progress = modularFraction(elapsedMillis, 340, 420)
            val accelerated = progress * progress
            ChargingThrustVfxFrame(
                visible = true,
                xFraction = 0.16f + 0.34f * accelerated,
                yFraction = 0.62f - 0.02f * accelerated,
                scale = 0.82f - 0.10f * accelerated,
                alpha = 0.42f + 0.50f * modularEaseOut(progress),
            )
        }
        elapsedMillis < 490 -> {
            val progress = modularFraction(elapsedMillis, 420, 490)
            ChargingThrustVfxFrame(
                visible = true,
                xFraction = 0.50f,
                yFraction = 0.60f,
                scale = 0.72f,
                alpha = 0.72f - 0.42f * modularEaseOut(progress),
            )
        }
        else -> {
            val progress = modularFraction(elapsedMillis, 490, 720)
            ChargingThrustVfxFrame(
                visible = true,
                xFraction = 0.50f,
                yFraction = 0.60f,
                scale = 0.72f + 0.08f * modularEaseOut(progress),
                alpha = 0.30f * (1f - progress),
            )
        }
    }
}

internal data class GroundImpactVfxFrame(
    val visible: Boolean,
    val scale: Float,
    val alpha: Float,
)

internal data class ContinuousSlashVfxFrame(
    val visible: Boolean,
    val alpha: Float,
    val scale: Float,
    val xFraction: Float,
    val yFraction: Float,
    val rotationDegrees: Float,
    val direction: Float,
)

internal data class StarterWarriorHitFrame(
    val visible: Boolean,
    val alpha: Float,
    val scale: Float,
    val xFraction: Float,
    val yFraction: Float,
    val rotationDegrees: Float,
    val direction: Float,
)

internal fun starterWarriorComboFrame(
    elapsedMillis: Int,
    hitIndex: Int,
    hitTimingsMillis: List<Int>,
    reducedMotion: Boolean,
    stronger: Boolean,
): StarterWarriorHitFrame {
    val safeIndex = hitIndex.coerceIn(0, hitTimingsMillis.lastIndex)
    val impactAt = hitTimingsMillis[safeIndex]
    val startAt = (impactAt - if (stronger) 115 else 85).coerceAtLeast(0)
    val isFinal = safeIndex == hitTimingsMillis.lastIndex
    val nextAt = hitTimingsMillis.getOrNull(safeIndex + 1)
    val endAt = if (isFinal) {
        minOf(impactAt + if (stronger) 300 else 260, SKILL_VFX_END_MILLIS)
    } else {
        minOf(impactAt + if (stronger) 125 else 105, (nextAt ?: SKILL_VFX_END_MILLIS) - 18)
    }
    val x = floatArrayOf(0.42f, 0.58f, 0.50f)[safeIndex.mod(3)]
    val y = floatArrayOf(0.53f, 0.65f, 0.58f)[safeIndex.mod(3)]
    val rotation = floatArrayOf(-24f, 26f, -5f)[safeIndex.mod(3)]
    val direction = if (safeIndex % 2 == 0) 1f else -1f
    val baseScale = floatArrayOf(0.76f, 0.78f, if (stronger) 1.00f else 0.92f)[safeIndex.mod(3)]
    if (reducedMotion) {
        val visible = elapsedMillis in impactAt until minOf(impactAt + if (isFinal) 180 else 90, endAt)
        return StarterWarriorHitFrame(visible, if (visible) 0.48f else 0f, baseScale, x, y, rotation, direction)
    }
    if (elapsedMillis !in startAt until endAt) {
        return StarterWarriorHitFrame(false, 0f, baseScale, x, y, rotation, direction)
    }
    val enter = modularEaseOut(modularFraction(elapsedMillis, startAt, impactAt))
    // Dense combo timings can end before the old fixed fade start (impact + 42 ms). In that
    // case modularFraction received a reversed interval and made the entire early strike alpha
    // zero. Reserve a real post-contact hold/fade inside every hit window so twelve-slash shows
    // all twelve authored cuts instead of only its roomy final image.
    val requestedFadeStart = impactAt + if (isFinal) 90 else 42
    val fadeStart = minOf(requestedFadeStart, endAt - if (isFinal) 48 else 20)
        .coerceAtLeast(impactAt)
    val fade = if (elapsedMillis < fadeStart) {
        1f
    } else {
        1f - modularFraction(elapsedMillis, fadeStart, endAt)
    }
    val alpha = minOf(enter, fade).coerceIn(0f, 1f) * if (isFinal) 0.94f else 0.80f
    val scale = baseScale * if (elapsedMillis < impactAt) 0.90f + 0.10f * enter else 1f + 0.04f * modularFraction(elapsedMillis, impactAt, endAt)
    return StarterWarriorHitFrame(true, alpha, scale, x, y, rotation, direction)
}

internal fun continuousSlashVfxFrame(
    elapsedMillis: Int,
    hitIndex: Int,
    reducedMotion: Boolean,
): ContinuousSlashVfxFrame {
    val impactAt = intArrayOf(140, 300, 520)[hitIndex.coerceIn(0, 2)]
    val startAt = intArrayOf(55, 225, 435)[hitIndex.coerceIn(0, 2)]
    val endAt = intArrayOf(245, 455, 780)[hitIndex.coerceIn(0, 2)]
    val x = floatArrayOf(0.44f, 0.56f, 0.50f)[hitIndex.coerceIn(0, 2)]
    val y = floatArrayOf(0.53f, 0.64f, 0.58f)[hitIndex.coerceIn(0, 2)]
    val rotation = floatArrayOf(-18f, 20f, -6f)[hitIndex.coerceIn(0, 2)]
    val direction = if (hitIndex % 2 == 0) 1f else -1f
    val baseScale = floatArrayOf(0.78f, 0.74f, 0.90f)[hitIndex.coerceIn(0, 2)]

    if (reducedMotion) {
        val reducedEnd = if (hitIndex == 2) impactAt + 180 else impactAt + 90
        return ContinuousSlashVfxFrame(
            visible = elapsedMillis in impactAt until reducedEnd,
            alpha = if (elapsedMillis in impactAt until reducedEnd) 0.48f else 0f,
            scale = baseScale,
            xFraction = x,
            yFraction = y,
            rotationDegrees = rotation,
            direction = direction,
        )
    }
    if (elapsedMillis !in startAt until endAt) {
        return ContinuousSlashVfxFrame(false, 0f, baseScale, x, y, rotation, direction)
    }
    val enter = modularEaseOut(modularFraction(elapsedMillis, startAt, impactAt))
    val fadeStart = if (hitIndex == 2) impactAt + 90 else impactAt + 45
    val fade = 1f - modularFraction(elapsedMillis, fadeStart, endAt)
    val alpha = minOf(enter, fade).coerceIn(0f, 1f) * if (hitIndex == 2) 0.92f else 0.78f
    val scale = if (elapsedMillis < impactAt) {
        baseScale * (0.90f + 0.10f * enter)
    } else {
        baseScale * (1f + 0.05f * modularFraction(elapsedMillis, impactAt, endAt))
    }
    return ContinuousSlashVfxFrame(true, alpha, scale, x, y, rotation, direction)
}

internal fun groundImpactVfxFrame(
    elapsedMillis: Int,
    reducedMotion: Boolean,
): GroundImpactVfxFrame {
    if (reducedMotion) {
        return if (elapsedMillis in 420 until 620) {
            GroundImpactVfxFrame(true, 0.82f, 0.48f)
        } else {
            GroundImpactVfxFrame(false, 0.82f, 0f)
        }
    }
    return when {
        elapsedMillis < 320 || elapsedMillis >= 720 -> GroundImpactVfxFrame(false, 0.40f, 0f)
        elapsedMillis < 400 -> {
            val progress = modularEaseOut(modularFraction(elapsedMillis, 320, 400))
            GroundImpactVfxFrame(true, 0.40f + 0.18f * progress, 0.12f + 0.18f * progress)
        }
        elapsedMillis < 420 -> {
            val progress = modularFraction(elapsedMillis, 400, 420)
            GroundImpactVfxFrame(true, 0.58f + 0.24f * progress, 0.30f + 0.62f * progress)
        }
        elapsedMillis < 500 -> {
            val progress = modularEaseOut(modularFraction(elapsedMillis, 420, 500))
            GroundImpactVfxFrame(true, 0.82f + 0.06f * progress, 0.72f - 0.40f * progress)
        }
        else -> {
            val progress = modularFraction(elapsedMillis, 500, 720)
            GroundImpactVfxFrame(true, 0.88f + 0.05f * progress, 0.32f * (1f - progress))
        }
    }
}

internal fun steelSlashVfxFrame(
    elapsedMillis: Int,
    reducedMotion: Boolean,
): SteelSlashVfxFrame {
    if (reducedMotion) {
        return if (elapsedMillis in 420 until 580) {
            SteelSlashVfxFrame(true, 1f, 0.48f)
        } else {
            SteelSlashVfxFrame(false, 0f, 0f)
        }
    }
    return when {
        elapsedMillis < 340 || elapsedMillis >= 720 -> SteelSlashVfxFrame(false, 0f, 0f)
        elapsedMillis < 420 -> {
            val progress = modularFraction(elapsedMillis, 340, 420)
            SteelSlashVfxFrame(true, progress * progress * progress, 0.80f * progress)
        }
        elapsedMillis < 456 -> SteelSlashVfxFrame(true, 1f, 0.80f)
        elapsedMillis < 600 -> {
            val progress = modularEaseOut(modularFraction(elapsedMillis, 456, 600))
            SteelSlashVfxFrame(true, 1f, 0.80f + (0.24f - 0.80f) * progress)
        }
        else -> SteelSlashVfxFrame(
            true,
            1f,
            0.24f * (1f - modularFraction(elapsedMillis, 600, 720)),
        )
    }
}

internal fun skillVfxIntensityProfile(definition: SkillDefinition): SkillVfxIntensityProfile {
    val tier = definition.intensityTier.coerceIn(1, 5)
    val levelProgress = ((definition.unlockLevel / 5) - 1).coerceIn(0, 19) / 19f
    return SkillVfxIntensityProfile(
        tier = tier,
        primaryScale = 0.70f + levelProgress * 0.40f,
        primaryAlpha = 0.70f + levelProgress * 0.27f,
        backdropScale = 0.28f + levelProgress * 0.74f,
        impactScale = 0.70f + levelProgress * 0.46f,
        finalLayerCount = intArrayOf(2, 3, 3, 4, 5)[tier - 1],
    )
}

/**
 * Warrior actions use stable catalog coordinates so copy changes cannot alter combat staging.
 * Other classes choose the visual from the verb first, then the elemental noun.
 */
private fun warriorSemanticVfxAction(definition: SkillDefinition): SemanticVfxAction {
    val tier = (definition.unlockLevel / 5).coerceIn(1, 20)
    return when (definition.candidate) {
        0 -> if (tier == 6 || tier == 9) SemanticVfxAction.SPIN else SemanticVfxAction.CUT
        1 -> if (tier == 17) SemanticVfxAction.COLLAPSE else SemanticVfxAction.DESCEND
        2 -> SemanticVfxAction.CHARGE
        3 -> when (tier) {
            4, 7, 10 -> SemanticVfxAction.ASCEND
            12, 15, 16, 18, 19 -> SemanticVfxAction.COLLAPSE
            else -> SemanticVfxAction.BURST
        }
        4 -> SemanticVfxAction.FLURRY
        else -> error("Unknown warrior candidate ${definition.candidate}")
    }
}

internal fun semanticVfxPlan(definition: SkillDefinition): SemanticVfxPlan {
    val name = definition.name
    val action = when {
        definition.heroClass == HeroClass.WARRIOR -> warriorSemanticVfxAction(definition)
        definition.heroClass == HeroClass.ROGUE && name == "빠른 찌르기" -> SemanticVfxAction.PIERCE
        definition.heroClass == HeroClass.ROGUE && name == "그림자 베기" -> SemanticVfxAction.CUT
        definition.heroClass == HeroClass.ROGUE && name == "독니 찌르기" -> SemanticVfxAction.PIERCE
        definition.heroClass == HeroClass.ROGUE && name == "발목 덫" -> SemanticVfxAction.TRAP
        definition.heroClass == HeroClass.ROGUE && name == "급소 베기" -> SemanticVfxAction.CUT
        definition.heroClass == HeroClass.ROGUE && name == "쌍아 연격" -> SemanticVfxAction.CROSS_CUT
        definition.heroClass == HeroClass.ROGUE && name == "암습" -> SemanticVfxAction.CHARGE
        definition.heroClass == HeroClass.ROGUE && name == "녹독 파열" -> SemanticVfxAction.BURST
        definition.heroClass == HeroClass.ROGUE && name == "철사 절단" -> SemanticVfxAction.CUT
        definition.heroClass == HeroClass.ROGUE && name == "숨통 끊기" -> SemanticVfxAction.EXECUTE
        definition.heroClass == HeroClass.ROGUE && name == "삼연 찌르기" -> SemanticVfxAction.PIERCE
        definition.heroClass == HeroClass.ROGUE && name == "잔영 습격" -> SemanticVfxAction.CHARGE
        definition.heroClass == HeroClass.ROGUE && name == "맹독 쌍침" -> SemanticVfxAction.PIERCE
        definition.heroClass == HeroClass.ROGUE && name == "올가미 강타" -> SemanticVfxAction.TRAP
        definition.heroClass == HeroClass.ROGUE && name == "심장 찌르기" -> SemanticVfxAction.EXECUTE
        definition.heroClass == HeroClass.ROGUE && name == "비수 난무" -> SemanticVfxAction.FLURRY
        definition.heroClass == HeroClass.ROGUE && name == "어둠 도약" -> SemanticVfxAction.DIVE
        definition.heroClass == HeroClass.ROGUE && name == "독안개 폭침" -> SemanticVfxAction.BURST
        definition.heroClass == HeroClass.ROGUE && name == "칼날 덫" -> SemanticVfxAction.TRAP
        definition.heroClass == HeroClass.ROGUE && name == "무음 처형" -> SemanticVfxAction.EXECUTE
        definition.heroClass == HeroClass.ROGUE && name == "초승달 단검" -> SemanticVfxAction.CUT
        definition.heroClass == HeroClass.ROGUE && name == "그림자 교차" -> SemanticVfxAction.CROSS_CUT
        definition.heroClass == HeroClass.ROGUE && name == "독액 분사" -> SemanticVfxAction.VOLLEY
        definition.heroClass == HeroClass.ROGUE && name == "은사 포박" -> SemanticVfxAction.TRAP
        definition.heroClass == HeroClass.ROGUE && name == "붉은 급소" -> SemanticVfxAction.EXECUTE
        definition.heroClass == HeroClass.ROGUE && name == "질풍 쌍검" -> SemanticVfxAction.CROSS_CUT
        definition.heroClass == HeroClass.ROGUE && name == "야행 습격" -> SemanticVfxAction.CHARGE
        definition.heroClass == HeroClass.ROGUE && name == "부식 파열" -> SemanticVfxAction.BURST
        definition.heroClass == HeroClass.ROGUE && name == "회전 올가미" -> SemanticVfxAction.SPIN
        definition.heroClass == HeroClass.ROGUE && name == "사각 일격" -> SemanticVfxAction.EXECUTE
        definition.heroClass == HeroClass.ROGUE && name == "네 갈래 비수" -> SemanticVfxAction.VOLLEY
        definition.heroClass == HeroClass.ROGUE && name == "흑영 베기" -> SemanticVfxAction.CUT
        definition.heroClass == HeroClass.ROGUE && name == "사독 연침" -> SemanticVfxAction.PIERCE
        definition.heroClass == HeroClass.ROGUE && name == "사슬 덫" -> SemanticVfxAction.TRAP
        definition.heroClass == HeroClass.ROGUE && name == "치명 절단" -> SemanticVfxAction.EXECUTE
        definition.heroClass == HeroClass.ROGUE && name == "유령 난도" -> SemanticVfxAction.FLURRY
        definition.heroClass == HeroClass.ROGUE && name == "잔상 도약" -> SemanticVfxAction.DIVE
        definition.heroClass == HeroClass.ROGUE && name == "독사 송곳니" -> SemanticVfxAction.PIERCE
        definition.heroClass == HeroClass.ROGUE && name == "강철 실선" -> SemanticVfxAction.CROSS_CUT
        definition.heroClass == HeroClass.ROGUE && name == "영혼 찌르기" -> SemanticVfxAction.EXECUTE
        definition.catalogId == "rogue_t09_c01" -> SemanticVfxAction.CROSS_CUT
        definition.catalogId == "rogue_t09_c02" -> SemanticVfxAction.CHARGE
        definition.catalogId == "rogue_t09_c03" -> SemanticVfxAction.BURST
        definition.catalogId == "rogue_t09_c04" -> SemanticVfxAction.TRAP
        definition.catalogId == "rogue_t09_c05" -> SemanticVfxAction.EXECUTE
        definition.catalogId == "rogue_t10_c01" -> SemanticVfxAction.VOLLEY
        definition.catalogId == "rogue_t10_c02" -> SemanticVfxAction.FLURRY
        definition.catalogId == "rogue_t10_c03" -> SemanticVfxAction.CHAIN
        definition.catalogId == "rogue_t10_c04" -> SemanticVfxAction.TRAP
        definition.catalogId == "rogue_t10_c05" -> SemanticVfxAction.EXECUTE
        definition.catalogId in setOf("rogue_t11_c01","rogue_t11_c04","rogue_t12_c01","rogue_t13_c01","rogue_t14_c01") -> SemanticVfxAction.FLURRY
        definition.catalogId in setOf("rogue_t11_c02","rogue_t12_c02","rogue_t14_c02") -> SemanticVfxAction.CHARGE
        definition.catalogId in setOf("rogue_t11_c03") -> SemanticVfxAction.BURST
        definition.catalogId in setOf("rogue_t12_c03","rogue_t13_c03") -> SemanticVfxAction.PIERCE
        definition.catalogId in setOf("rogue_t13_c02") -> SemanticVfxAction.CROSS_CUT
        definition.catalogId in setOf("rogue_t11_c05","rogue_t12_c05","rogue_t13_c05","rogue_t14_c05") -> SemanticVfxAction.EXECUTE
        definition.catalogId in setOf("rogue_t12_c04","rogue_t13_c04","rogue_t14_c04") -> SemanticVfxAction.TRAP
        definition.catalogId == "rogue_t14_c03" -> SemanticVfxAction.BURST
        definition.catalogId in setOf("rogue_t15_c01","rogue_t15_c02","rogue_t16_c01","rogue_t17_c01","rogue_t17_c02","rogue_t19_c01","rogue_t19_c02","rogue_t20_c01","rogue_t20_c02") -> SemanticVfxAction.FLURRY
        definition.catalogId in setOf("rogue_t16_c02","rogue_t18_c02") -> SemanticVfxAction.CHARGE
        definition.catalogId in setOf("rogue_t15_c03","rogue_t18_c03","rogue_t20_c03") -> SemanticVfxAction.BURST
        definition.catalogId in setOf("rogue_t16_c03") -> SemanticVfxAction.CHAIN
        definition.catalogId in setOf("rogue_t17_c03","rogue_t19_c03") -> SemanticVfxAction.PIERCE
        definition.catalogId in setOf("rogue_t15_c04","rogue_t16_c04","rogue_t17_c04","rogue_t18_c04","rogue_t19_c04","rogue_t20_c04") -> SemanticVfxAction.TRAP
        definition.catalogId in setOf("rogue_t15_c05","rogue_t16_c05","rogue_t17_c05","rogue_t18_c05","rogue_t19_c05","rogue_t20_c05") -> SemanticVfxAction.EXECUTE
        definition.catalogId == "rogue_t18_c01" -> SemanticVfxAction.FLURRY
        definition.heroClass == HeroClass.ROGUE && name == "심연 분신참" -> SemanticVfxAction.FLURRY
        definition.heroClass == HeroClass.RANGER && name == "태풍 종결" -> SemanticVfxAction.BURST
        definition.heroClass == HeroClass.PALADIN && name == "왕국 영겁참" -> SemanticVfxAction.FLURRY
        definition.heroClass == HeroClass.CLERIC && name == "정화의 일격" -> SemanticVfxAction.EXECUTE
        definition.heroClass == HeroClass.MAGE && name == "신격 뇌전 연격" -> SemanticVfxAction.VOLLEY
        definition.heroClass == HeroClass.ROGUE && name.containsAny("독안개 폭침", "심연독 폭침", "독신 폭살") -> SemanticVfxAction.BURST
        definition.heroClass == HeroClass.CLERIC && name.containsAny("정화 연격", "파마 대연격") -> SemanticVfxAction.BEAM
        definition.catalogId == "ranger_t01_c01" -> SemanticVfxAction.SHOT
        definition.catalogId == "ranger_t01_c02" -> SemanticVfxAction.VOLLEY
        definition.catalogId == "ranger_t01_c03" -> SemanticVfxAction.SHOT
        definition.catalogId == "ranger_t01_c04" -> SemanticVfxAction.TRAP
        definition.catalogId == "ranger_t01_c05" -> SemanticVfxAction.SHOT
        definition.catalogId == "ranger_t02_c01" -> SemanticVfxAction.PIERCE
        definition.catalogId == "ranger_t02_c02" -> SemanticVfxAction.VOLLEY
        definition.catalogId == "ranger_t02_c03" -> SemanticVfxAction.VOLLEY
        definition.catalogId == "ranger_t02_c04" -> SemanticVfxAction.DIVE
        definition.catalogId == "ranger_t02_c05" -> SemanticVfxAction.SHOT
        definition.catalogId == "ranger_t03_c01" -> SemanticVfxAction.PIERCE
        definition.catalogId == "ranger_t03_c02" -> SemanticVfxAction.VOLLEY
        definition.catalogId == "ranger_t03_c03" -> SemanticVfxAction.CUT
        definition.catalogId == "ranger_t03_c04" -> SemanticVfxAction.TRAP
        definition.catalogId == "ranger_t03_c05" -> SemanticVfxAction.PIERCE
        definition.catalogId == "ranger_t04_c01" -> SemanticVfxAction.PIERCE
        definition.catalogId == "ranger_t04_c02" -> SemanticVfxAction.VOLLEY
        definition.catalogId == "ranger_t04_c03" -> SemanticVfxAction.SPIN
        definition.catalogId == "ranger_t04_c04" -> SemanticVfxAction.TRAP
        definition.catalogId == "ranger_t04_c05" -> SemanticVfxAction.VOLLEY
        definition.catalogId == "ranger_t05_c01" -> SemanticVfxAction.SHOT
        definition.catalogId == "ranger_t05_c02" -> SemanticVfxAction.VOLLEY
        definition.catalogId == "ranger_t05_c03" -> SemanticVfxAction.PIERCE
        definition.catalogId == "ranger_t05_c04" -> SemanticVfxAction.TRAP
        definition.catalogId == "ranger_t05_c05" -> SemanticVfxAction.SHOT
        definition.catalogId == "ranger_t06_c01" -> SemanticVfxAction.SHOT
        definition.catalogId == "ranger_t06_c02" -> SemanticVfxAction.VOLLEY
        definition.catalogId == "ranger_t06_c03" -> SemanticVfxAction.VOLLEY
        definition.catalogId == "ranger_t06_c04" -> SemanticVfxAction.DESCEND
        definition.catalogId == "ranger_t06_c05" -> SemanticVfxAction.SHOT
        definition.catalogId == "ranger_t07_c01" -> SemanticVfxAction.PIERCE
        definition.catalogId == "ranger_t07_c02" -> SemanticVfxAction.VOLLEY
        definition.catalogId == "ranger_t07_c03" -> SemanticVfxAction.VOLLEY
        definition.catalogId == "ranger_t07_c04" -> SemanticVfxAction.TRAP
        definition.catalogId == "ranger_t07_c05" -> SemanticVfxAction.SHOT
        definition.catalogId == "ranger_t08_c01" -> SemanticVfxAction.SHOT
        definition.catalogId == "ranger_t08_c02" -> SemanticVfxAction.VOLLEY
        definition.catalogId == "ranger_t08_c03" -> SemanticVfxAction.SPIN
        definition.catalogId == "ranger_t08_c04" -> SemanticVfxAction.TRAP
        definition.catalogId == "ranger_t08_c05" -> SemanticVfxAction.DESCEND
        definition.catalogId == "ranger_t09_c01" -> SemanticVfxAction.SHOT
        definition.catalogId == "ranger_t09_c02" -> SemanticVfxAction.VOLLEY
        definition.catalogId == "ranger_t09_c03" -> SemanticVfxAction.VOLLEY
        definition.catalogId == "ranger_t09_c04" -> SemanticVfxAction.DESCEND
        definition.catalogId == "ranger_t09_c05" -> SemanticVfxAction.VOLLEY
        definition.catalogId == "ranger_t10_c01" -> SemanticVfxAction.SHOT
        definition.catalogId == "ranger_t10_c02" -> SemanticVfxAction.VOLLEY
        definition.catalogId == "ranger_t10_c03" -> SemanticVfxAction.VOLLEY
        definition.catalogId == "ranger_t10_c04" -> SemanticVfxAction.DIVE
        definition.catalogId == "ranger_t10_c05" -> SemanticVfxAction.PIERCE
        definition.catalogId == "ranger_t11_c01" -> SemanticVfxAction.PIERCE
        definition.catalogId == "ranger_t11_c02" -> SemanticVfxAction.VOLLEY
        definition.catalogId == "ranger_t11_c03" -> SemanticVfxAction.BURST
        definition.catalogId == "ranger_t11_c04" -> SemanticVfxAction.TRAP
        definition.catalogId == "ranger_t11_c05" -> SemanticVfxAction.SHOT
        definition.catalogId == "ranger_t12_c01" -> SemanticVfxAction.SHOT
        definition.catalogId == "ranger_t12_c02" -> SemanticVfxAction.VOLLEY
        definition.catalogId == "ranger_t12_c03" -> SemanticVfxAction.VOLLEY
        definition.catalogId == "ranger_t12_c04" -> SemanticVfxAction.DIVE
        definition.catalogId == "ranger_t12_c05" -> SemanticVfxAction.SHOT
        definition.catalogId == "ranger_t13_c01" -> SemanticVfxAction.SHOT
        definition.catalogId == "ranger_t13_c02" -> SemanticVfxAction.VOLLEY
        definition.catalogId == "ranger_t13_c03" -> SemanticVfxAction.SPIN
        definition.catalogId == "ranger_t13_c04" -> SemanticVfxAction.DIVE
        definition.catalogId == "ranger_t13_c05" -> SemanticVfxAction.PIERCE
        definition.catalogId == "ranger_t14_c01" -> SemanticVfxAction.SHOT
        definition.catalogId == "ranger_t14_c02" -> SemanticVfxAction.VOLLEY
        definition.catalogId == "ranger_t14_c03" -> SemanticVfxAction.CUT
        definition.catalogId == "ranger_t14_c04" -> SemanticVfxAction.TRAP
        definition.catalogId == "ranger_t14_c05" -> SemanticVfxAction.DESCEND
        definition.catalogId == "ranger_t15_c01" -> SemanticVfxAction.SHOT
        definition.catalogId == "ranger_t15_c02" -> SemanticVfxAction.VOLLEY
        definition.catalogId == "ranger_t15_c03" -> SemanticVfxAction.VOLLEY
        definition.catalogId == "ranger_t15_c04" -> SemanticVfxAction.DIVE
        definition.catalogId == "ranger_t15_c05" -> SemanticVfxAction.BURST
        definition.catalogId == "ranger_t16_c01" -> SemanticVfxAction.PIERCE
        definition.catalogId == "ranger_t16_c02" -> SemanticVfxAction.VOLLEY
        definition.catalogId == "ranger_t16_c03" -> SemanticVfxAction.CUT
        definition.catalogId == "ranger_t16_c04" -> SemanticVfxAction.DIVE
        definition.catalogId == "ranger_t16_c05" -> SemanticVfxAction.VOLLEY
        definition.catalogId == "ranger_t17_c01" -> SemanticVfxAction.SHOT
        definition.catalogId == "ranger_t17_c02" -> SemanticVfxAction.VOLLEY
        definition.catalogId == "ranger_t17_c03" -> SemanticVfxAction.COLLAPSE
        definition.catalogId == "ranger_t17_c04" -> SemanticVfxAction.DIVE
        definition.catalogId == "ranger_t17_c05" -> SemanticVfxAction.PIERCE
        definition.catalogId == "ranger_t18_c01" -> SemanticVfxAction.SHOT
        definition.catalogId == "ranger_t18_c02" -> SemanticVfxAction.VOLLEY
        definition.catalogId == "ranger_t18_c03" -> SemanticVfxAction.BURST
        definition.catalogId == "ranger_t18_c04" -> SemanticVfxAction.TRAP
        definition.catalogId == "ranger_t18_c05" -> SemanticVfxAction.DESCEND
        definition.catalogId == "ranger_t19_c01" -> SemanticVfxAction.SHOT
        definition.catalogId == "ranger_t19_c02" -> SemanticVfxAction.VOLLEY
        definition.catalogId == "ranger_t19_c03" -> SemanticVfxAction.PIERCE
        definition.catalogId == "ranger_t19_c04" -> SemanticVfxAction.DIVE
        definition.catalogId == "ranger_t19_c05" -> SemanticVfxAction.BEAM
        definition.catalogId == "ranger_t20_c01" -> SemanticVfxAction.SHOT
        definition.catalogId == "ranger_t20_c02" -> SemanticVfxAction.VOLLEY
        definition.catalogId == "ranger_t20_c03" -> SemanticVfxAction.BURST
        definition.catalogId == "ranger_t20_c04" -> SemanticVfxAction.DIVE
        definition.catalogId == "ranger_t20_c05" -> SemanticVfxAction.PIERCE
        definition.catalogId in setOf("mage_t01_c01","mage_t01_c02","mage_t01_c04","mage_t02_c01","mage_t02_c05","mage_t04_c03","mage_t07_c03") -> SemanticVfxAction.SHOT
        definition.catalogId in setOf("mage_t03_c03","mage_t16_c03","mage_t17_c03","mage_t18_c03","mage_t20_c03") -> SemanticVfxAction.VOLLEY
        definition.catalogId == "mage_t01_c03" -> SemanticVfxAction.DESCEND
        definition.catalogId in setOf("mage_t01_c05","mage_t02_c04","mage_t03_c02","mage_t03_c05","mage_t04_c01","mage_t04_c04") -> SemanticVfxAction.BURST
        definition.catalogId == "mage_t02_c02" -> SemanticVfxAction.PIERCE
        definition.catalogId == "mage_t02_c03" -> SemanticVfxAction.CHAIN
        definition.catalogId == "mage_t03_c01" -> SemanticVfxAction.SPIN
        definition.catalogId == "mage_t03_c04" -> SemanticVfxAction.CUT
        definition.catalogId == "mage_t04_c02" -> SemanticVfxAction.PIERCE
        definition.catalogId == "mage_t04_c05" -> SemanticVfxAction.VOLLEY
        definition.catalogId in setOf("mage_t05_c01","mage_t06_c01","mage_t10_c01","mage_t12_c01","mage_t14_c01","mage_t18_c01","mage_t19_c01","mage_t20_c01") -> SemanticVfxAction.ASCEND
        definition.catalogId in setOf("mage_t05_c02","mage_t09_c05","mage_t13_c05","mage_t14_c03","mage_t16_c03","mage_t17_c03","mage_t19_c03","mage_t20_c03") -> SemanticVfxAction.VOLLEY
        definition.catalogId in setOf("mage_t05_c03","mage_t11_c03","mage_t13_c03","mage_t15_c04") -> SemanticVfxAction.CHAIN
        definition.catalogId in setOf("mage_t05_c04","mage_t07_c02","mage_t08_c01","mage_t09_c02","mage_t11_c04","mage_t14_c02") -> SemanticVfxAction.SPIN
        definition.catalogId in setOf("mage_t05_c05","mage_t06_c03","mage_t07_c01","mage_t08_c02","mage_t08_c03","mage_t08_c05","mage_t09_c03","mage_t10_c03","mage_t10_c05","mage_t12_c02","mage_t12_c05","mage_t13_c01","mage_t13_c04","mage_t14_c05","mage_t15_c02","mage_t16_c01","mage_t17_c01","mage_t18_c02","mage_t18_c04","mage_t19_c05","mage_t20_c02") -> SemanticVfxAction.BURST
        definition.catalogId in setOf("mage_t06_c02","mage_t10_c02","mage_t13_c02","mage_t19_c02") -> SemanticVfxAction.PIERCE
        definition.catalogId in setOf("mage_t06_c04","mage_t08_c04","mage_t14_c04") -> SemanticVfxAction.CUT
        definition.catalogId in setOf("mage_t06_c05","mage_t09_c01","mage_t11_c02","mage_t12_c03","mage_t15_c03","mage_t16_c05","mage_t17_c02","mage_t18_c03") -> SemanticVfxAction.DESCEND
        definition.catalogId in setOf("mage_t07_c03","mage_t11_c01") -> SemanticVfxAction.SHOT
        definition.catalogId in setOf("mage_t07_c04","mage_t07_c05","mage_t13_c05") -> SemanticVfxAction.VOLLEY
        definition.catalogId in setOf("mage_t09_c04","mage_t10_c04","mage_t12_c04","mage_t15_c05","mage_t16_c02","mage_t16_c04","mage_t17_c04","mage_t17_c05","mage_t20_c04","mage_t20_c05") -> SemanticVfxAction.COLLAPSE
        definition.catalogId in setOf("mage_t11_c05","mage_t15_c01","mage_t18_c05") -> SemanticVfxAction.DIVE
        definition.catalogId == "mage_t19_c04" -> SemanticVfxAction.BEAM
        definition.catalogId in setOf("cleric_t01_c01","cleric_t02_c01","cleric_t06_c01","cleric_t10_c01","cleric_t11_c04") -> SemanticVfxAction.SHOT
        definition.catalogId in setOf("cleric_t01_c02","cleric_t02_c02","cleric_t03_c02","cleric_t04_c02","cleric_t05_c02","cleric_t06_c02","cleric_t07_c02","cleric_t08_c02","cleric_t09_c02","cleric_t10_c05","cleric_t11_c02","cleric_t12_c02","cleric_t13_c02","cleric_t14_c02","cleric_t15_c02","cleric_t16_c02","cleric_t17_c02","cleric_t18_c02","cleric_t19_c02","cleric_t20_c02") -> SemanticVfxAction.DESCEND
        definition.catalogId == "cleric_t01_c03" -> SemanticVfxAction.EXECUTE
        definition.catalogId in setOf("cleric_t01_c04","cleric_t03_c04","cleric_t03_c05","cleric_t04_c03","cleric_t04_c04","cleric_t07_c01","cleric_t07_c04","cleric_t08_c01","cleric_t09_c03","cleric_t09_c04","cleric_t12_c01","cleric_t13_c03","cleric_t13_c04","cleric_t14_c03","cleric_t15_c01","cleric_t15_c04","cleric_t16_c03","cleric_t17_c01","cleric_t17_c03","cleric_t17_c04","cleric_t18_c03","cleric_t19_c04") -> SemanticVfxAction.BURST
        definition.catalogId == "cleric_t15_c03" -> SemanticVfxAction.COLLAPSE
        definition.catalogId in setOf("cleric_t01_c05","cleric_t09_c05") -> SemanticVfxAction.CHARGE
        definition.catalogId in setOf("cleric_t02_c03","cleric_t05_c03","cleric_t08_c03") -> SemanticVfxAction.TRAP
        definition.catalogId in setOf("cleric_t02_c04","cleric_t08_c04","cleric_t10_c04","cleric_t12_c04","cleric_t14_c04","cleric_t16_c04","cleric_t18_c04","cleric_t20_c04") -> SemanticVfxAction.ASCEND
        definition.catalogId in setOf("cleric_t02_c05","cleric_t06_c05","cleric_t08_c05","cleric_t11_c05","cleric_t17_c05","cleric_t18_c05","cleric_t20_c05") -> SemanticVfxAction.VOLLEY
        definition.catalogId == "cleric_t14_c05" -> SemanticVfxAction.DESCEND
        definition.catalogId in setOf("cleric_t03_c01","cleric_t05_c01","cleric_t07_c03","cleric_t09_c01","cleric_t10_c02","cleric_t11_c01","cleric_t12_c03","cleric_t13_c01","cleric_t14_c01","cleric_t16_c01","cleric_t18_c01","cleric_t19_c01","cleric_t19_c03","cleric_t20_c01","cleric_t20_c03") -> SemanticVfxAction.BEAM
        definition.catalogId in setOf("cleric_t03_c03","cleric_t04_c05","cleric_t07_c05","cleric_t12_c05","cleric_t15_c05","cleric_t16_c05") -> SemanticVfxAction.FLURRY
        definition.catalogId in setOf("cleric_t04_c01","cleric_t06_c04") -> SemanticVfxAction.SPIN
        definition.catalogId in setOf("cleric_t05_c04","cleric_t11_c04") -> SemanticVfxAction.BURST
        definition.catalogId == "cleric_t05_c05" -> SemanticVfxAction.DESCEND
        definition.catalogId in setOf("cleric_t13_c05","cleric_t19_c05") -> SemanticVfxAction.DIVE
        definition.catalogId in setOf("cleric_t06_c03","cleric_t10_c03","cleric_t11_c03") -> SemanticVfxAction.CHAIN
        definition.heroClass == HeroClass.PALADIN && definition.catalogId in setOf(
            "paladin_t01_c01","paladin_t02_c01","paladin_t05_c01","paladin_t07_c01",
            "paladin_t11_c01","paladin_t13_c01","paladin_t15_c01","paladin_t17_c01",
            "paladin_t19_c01",
        ) -> SemanticVfxAction.CUT
        definition.heroClass == HeroClass.PALADIN && definition.catalogId in setOf(
            "paladin_t04_c01","paladin_t08_c01","paladin_t10_c01","paladin_t14_c01",
            "paladin_t16_c01","paladin_t18_c01",
        ) -> SemanticVfxAction.CROSS_CUT
        definition.heroClass == HeroClass.PALADIN && definition.catalogId in setOf(
            "paladin_t02_c04","paladin_t03_c01","paladin_t03_c04","paladin_t05_c04",
            "paladin_t05_c05","paladin_t07_c04","paladin_t09_c01","paladin_t11_c04",
            "paladin_t12_c01","paladin_t13_c04","paladin_t14_c05",
            "paladin_t15_c05","paladin_t20_c01","paladin_t20_c05",
        ) -> SemanticVfxAction.FLURRY
        definition.heroClass == HeroClass.PALADIN && definition.catalogId in setOf(
            "paladin_t01_c02","paladin_t02_c02","paladin_t03_c02","paladin_t04_c05",
            "paladin_t06_c02","paladin_t08_c02","paladin_t08_c05","paladin_t09_c02",
            "paladin_t09_c05","paladin_t10_c02","paladin_t11_c02","paladin_t13_c02",
            "paladin_t14_c02","paladin_t16_c02","paladin_t16_c05","paladin_t17_c02",
            "paladin_t18_c02","paladin_t19_c02","paladin_t19_c05","paladin_t05_c02",
            "paladin_t07_c02","paladin_t15_c02",
        ) -> SemanticVfxAction.CHARGE
        definition.heroClass == HeroClass.PALADIN && definition.catalogId in setOf(
            "paladin_t01_c03","paladin_t02_c03","paladin_t03_c03","paladin_t04_c03",
            "paladin_t05_c03","paladin_t06_c03","paladin_t07_c03",
            "paladin_t08_c03","paladin_t09_c03","paladin_t10_c03","paladin_t11_c03",
            "paladin_t12_c03","paladin_t13_c03","paladin_t14_c03","paladin_t15_c03",
            "paladin_t16_c03","paladin_t17_c03","paladin_t18_c03","paladin_t19_c03",
            "paladin_t20_c03",
        ) -> SemanticVfxAction.DESCEND
        definition.heroClass == HeroClass.PALADIN && definition.catalogId in setOf(
            "paladin_t01_c04","paladin_t04_c02","paladin_t04_c04",
            "paladin_t06_c04","paladin_t09_c04","paladin_t10_c04","paladin_t12_c02",
            "paladin_t16_c04","paladin_t20_c02",
        ) -> SemanticVfxAction.BURST
        definition.heroClass == HeroClass.PALADIN && definition.catalogId in setOf(
            "paladin_t08_c04","paladin_t12_c04",
        ) -> SemanticVfxAction.SPIN
        definition.heroClass == HeroClass.PALADIN && definition.catalogId in setOf(
            "paladin_t06_c01",
        ) -> SemanticVfxAction.SPIN
        definition.heroClass == HeroClass.PALADIN && definition.catalogId in setOf(
            "paladin_t15_c04","paladin_t17_c04","paladin_t19_c04",
        ) -> SemanticVfxAction.BEAM
        definition.heroClass == HeroClass.PALADIN && definition.catalogId in setOf(
            "paladin_t02_c05","paladin_t06_c05","paladin_t12_c05","paladin_t17_c05",
        ) -> SemanticVfxAction.CUT
        definition.heroClass == HeroClass.PALADIN && definition.catalogId in setOf(
            "paladin_t01_c05","paladin_t03_c05","paladin_t07_c05","paladin_t10_c05",
            "paladin_t13_c05",
        ) -> SemanticVfxAction.EXECUTE
        definition.heroClass == HeroClass.PALADIN && definition.catalogId in setOf(
            "paladin_t11_c05","paladin_t18_c05",
        ) -> SemanticVfxAction.DESCEND
        definition.heroClass == HeroClass.PALADIN && definition.catalogId in setOf(
            "paladin_t14_c04",
        ) -> SemanticVfxAction.ASCEND
        definition.heroClass == HeroClass.PALADIN && definition.catalogId in setOf(
            "paladin_t18_c04","paladin_t20_c04",
        ) -> SemanticVfxAction.COLLAPSE
        definition.motion == SkillMotion.DASH_IMPACT -> SemanticVfxAction.CHARGE
        definition.heroClass == HeroClass.RANGER &&
            name.containsAny("세 갈래", "연속 사격", "부채꼴", "일곱 화살", "군집 화살", "빛살 난무") -> SemanticVfxAction.VOLLEY
        definition.heroClass == HeroClass.RANGER &&
            name.containsAny("조준", "저격", "필중", "일점", "한 발") -> SemanticVfxAction.SHOT
        definition.heroClass in setOf(HeroClass.RANGER, HeroClass.MAGE, HeroClass.CLERIC) &&
            name.containsAny("연사", "난사", "연탄", "포화") -> SemanticVfxAction.VOLLEY
        definition.heroClass == HeroClass.MAGE && name.contains("천둥 종말") -> SemanticVfxAction.VOLLEY
        definition.heroClass == HeroClass.MAGE && name.contains("초신성") -> SemanticVfxAction.BURST
        definition.heroClass == HeroClass.CLERIC && name == "만악 종결광" -> SemanticVfxAction.BEAM
        definition.heroClass == HeroClass.CLERIC && name == "영혼왕의 일격" -> SemanticVfxAction.DIVE
        definition.heroClass == HeroClass.PALADIN && name == "왕권 일격" -> SemanticVfxAction.EXECUTE
        definition.heroClass == HeroClass.PALADIN && name.contains("서약") && name.containsAny("검", "참") -> SemanticVfxAction.CUT
        name.containsAny("돌진", "돌파", "쇄도", "질주", "방패격", "밀치기") -> SemanticVfxAction.CHARGE
        name.containsAny("덫", "함정", "포획", "올가미", "포박", "봉쇄", "감옥", "봉인") -> SemanticVfxAction.TRAP
        name.containsAny("연쇄", "사슬", "광쇄") -> SemanticVfxAction.CHAIN
        name.containsAny("화살비", "유성우", "성우", "폭우", "만뢰", "송곳비", "폭격", "포화", "백만 화살", "무한 화살", "삼중 낙뢰", "사방 낙뢰") -> SemanticVfxAction.VOLLEY
        name.containsAny("광선", "광주") -> SemanticVfxAction.BEAM
        name.containsAny("내려치기", "강하", "낙하", "천벌", "벼락", "낙뢰", "전격", "심판", "대성추", "성추", "망치", "분쇄추", "강타", "분쇄") -> SemanticVfxAction.DESCEND
        name.containsAny("솟구침", "분출", "해일", "불바다", "용의 화염", "태양 화염", "창세의 화염", "태양을 삼킨 불꽃") -> SemanticVfxAction.ASCEND
        name.containsAny("급강하", "급습", "강습", "격돌", "충돌", "돌격") -> SemanticVfxAction.DIVE
        name.containsAny("붕괴", "소멸", "특이점", "왜곡") -> SemanticVfxAction.COLLAPSE
        name.containsAny("소용돌이", "회오리", "회전", "고리", "윤무", "눈보라", "서리 폭풍", "빙하 폭풍", "비전 폭풍") -> SemanticVfxAction.SPIN
        name.containsAny("폭발", "파열", "파동", "충격", "균열", "격노", "폭뢰", "초신성", "빙쇄", "연소") -> SemanticVfxAction.BURST
        name.containsAny("난무", "연격", "연참", "참무", "연속", "오연", "삼연", "칠연", "십이연", "검무", "단검무", "군무", "연무", "광란", "난도", "연탄", "난사", "연사", "연타", "쌍아", "삼중", "일곱", "백련", "천살") -> SemanticVfxAction.FLURRY
        name.containsAny("교차", "쌍검", "쌍아", "십자") -> SemanticVfxAction.CROSS_CUT
        name.containsAny("처형", "종결", "단두", "급소", "숨통", "일점", "한 점", "필중", "정조준", "조준", "저격") -> SemanticVfxAction.EXECUTE
        name.containsAny("관통", "찌르기", "창격", "창벽", "빙창", "얼음 창", "의 창", "연창", "쌍침", "연침", "독침", "송곳니") -> SemanticVfxAction.PIERCE
        name.containsAny("화살", "사격", "광탄", "성광탄", "화염구", "구체", "마력탄", "시위", "파편", "조각", "탄환", "광탄") -> SemanticVfxAction.SHOT
        name.containsAny("베기", "참격", "절단", "가르기", "양단", "칼날", "검격", "성검", "단검", "비수") -> SemanticVfxAction.CUT
        else -> when (definition.motion) {
            SkillMotion.CROSS_SLASH -> SemanticVfxAction.CROSS_CUT
            SkillMotion.RAPID_THREE, SkillMotion.FRENZY_FIVE -> SemanticVfxAction.FLURRY
            SkillMotion.EXECUTE_PAUSE -> SemanticVfxAction.EXECUTE
            SkillMotion.PIERCE_LINE -> SemanticVfxAction.PIERCE
            SkillMotion.DASH_IMPACT -> SemanticVfxAction.CHARGE
            SkillMotion.PROJECTILE_SINGLE -> SemanticVfxAction.SHOT
            SkillMotion.PROJECTILE_VOLLEY, SkillMotion.RAIN_VERTICAL -> SemanticVfxAction.VOLLEY
            SkillMotion.CHAIN_ARC -> SemanticVfxAction.CHAIN
            SkillMotion.BEAM_CHANNEL -> SemanticVfxAction.BEAM
            SkillMotion.HEAVY_FALL, SkillMotion.PILLAR_DROP -> SemanticVfxAction.DESCEND
            SkillMotion.ERUPTION_UP -> SemanticVfxAction.ASCEND
            SkillMotion.SUMMON_DIVE -> SemanticVfxAction.DIVE
            SkillMotion.SPIN_CUT -> SemanticVfxAction.SPIN
            SkillMotion.NOVA_RADIAL -> SemanticVfxAction.BURST
            SkillMotion.GRAVITY_COLLAPSE -> SemanticVfxAction.COLLAPSE
            SkillMotion.TRAP_SNAP -> SemanticVfxAction.TRAP
            SkillMotion.CLEAVE_HORIZONTAL -> SemanticVfxAction.CUT
        }
    }
    val variant = definition.effectVariant
    val warrior = definition.heroClass == HeroClass.WARRIOR
    val plan = when (action) {
        SemanticVfxAction.CUT -> SemanticVfxPlan(action, ModularRecipe(ModularVfxArchetype.SLASH, when {
            name.containsAny("반월", "초승달") -> 4
            name.containsAny("회오리", "회전") -> 7
            name.containsAny("공간", "차원", "절단") -> 8
            else -> intArrayOf(0, 1, 2, 4)[variant]
        }), SemanticVfxFlow.LEFT_TO_RIGHT, SemanticImpactStyle.EDGE, "cut verb")
        SemanticVfxAction.CROSS_CUT -> SemanticVfxPlan(action, ModularRecipe(ModularVfxArchetype.SLASH, 5), SemanticVfxFlow.LEFT_TO_RIGHT, SemanticImpactStyle.EDGE, "cross or twin weapon")
        SemanticVfxAction.FLURRY -> SemanticVfxPlan(action, ModularRecipe(ModularVfxArchetype.SLASH, 6), SemanticVfxFlow.LEFT_TO_RIGHT, SemanticImpactStyle.EDGE, "multi-hit verb")
        SemanticVfxAction.CHARGE -> SemanticVfxPlan(action, ModularRecipe(ModularVfxArchetype.WARRIOR, 6), SemanticVfxFlow.LEFT_TO_RIGHT, SemanticImpactStyle.POINT, "warrior forward charge")
        SemanticVfxAction.EXECUTE -> SemanticVfxPlan(action, ModularRecipe(ModularVfxArchetype.SLASH, if (name.containsAny("저격", "조준", "일점", "한 점", "필중")) 9 else 3), SemanticVfxFlow.TOP_TO_BOTTOM, SemanticImpactStyle.POINT, "precision or execute verb")
        SemanticVfxAction.PIERCE -> SemanticVfxPlan(action, ModularRecipe(ModularVfxArchetype.PROJECTILE, when (definition.element) {
            SkillElement.ICE -> 2
            SkillElement.FIRE -> 6
            SkillElement.LIGHTNING -> 4
            else -> if (definition.hitCount >= 3) 7 else 0
        }), SemanticVfxFlow.OUTSIDE_IN, SemanticImpactStyle.POINT, "pierce verb")
        SemanticVfxAction.SHOT -> SemanticVfxPlan(action, ModularRecipe(ModularVfxArchetype.PROJECTILE, when (definition.element) {
            SkillElement.FIRE -> 6
            SkillElement.ICE -> 2
            SkillElement.LIGHTNING -> 4
            SkillElement.WIND -> 5
            SkillElement.ARCANE, SkillElement.COSMIC -> 8
            else -> intArrayOf(0, 1, 5, 8)[variant]
        }), SemanticVfxFlow.OUTSIDE_IN, SemanticImpactStyle.POINT, "projectile noun")
        SemanticVfxAction.VOLLEY -> SemanticVfxPlan(action, ModularRecipe(ModularVfxArchetype.COLUMN, when (definition.element) {
            SkillElement.LIGHTNING -> 2
            SkillElement.ICE -> 3
            SkillElement.FIRE -> 5
            SkillElement.COSMIC -> 7
            else -> 6
        }), SemanticVfxFlow.TOP_TO_BOTTOM, SemanticImpactStyle.POINT, "rain or barrage verb")
        SemanticVfxAction.CHAIN -> SemanticVfxPlan(action, ModularRecipe(ModularVfxArchetype.PROJECTILE, if (definition.element == SkillElement.LIGHTNING) 4 else 9), SemanticVfxFlow.OUTSIDE_IN, SemanticImpactStyle.POINT, "chain verb")
        SemanticVfxAction.BEAM -> SemanticVfxPlan(action, ModularRecipe(ModularVfxArchetype.PROJECTILE, 3), SemanticVfxFlow.LEFT_TO_RIGHT, SemanticImpactStyle.POINT, "beam verb")
        SemanticVfxAction.DESCEND -> SemanticVfxPlan(action, ModularRecipe(ModularVfxArchetype.COLUMN, when (definition.element) {
            SkillElement.LIGHTNING -> 2
            SkillElement.ICE -> 3
            SkillElement.FIRE -> 5
            SkillElement.COSMIC -> 7
            SkillElement.ARCANE, SkillElement.HOLY -> 8
            else -> if (name.containsAny("망치", "분쇄", "강타")) 1 else 0
        }), SemanticVfxFlow.TOP_TO_BOTTOM, SemanticImpactStyle.FRACTURE, "descending impact verb")
        SemanticVfxAction.ASCEND -> SemanticVfxPlan(action, ModularRecipe(ModularVfxArchetype.COLUMN, if (definition.element == SkillElement.FIRE) 9 else 4), SemanticVfxFlow.BOTTOM_TO_TOP, SemanticImpactStyle.FRACTURE, "eruption verb")
        SemanticVfxAction.DIVE -> SemanticVfxPlan(action, ModularRecipe(ModularVfxArchetype.COLUMN, if (definition.element == SkillElement.COSMIC) 7 else 0), SemanticVfxFlow.TOP_TO_BOTTOM, SemanticImpactStyle.POINT, "dive verb")
        SemanticVfxAction.SPIN -> SemanticVfxPlan(action, ModularRecipe(ModularVfxArchetype.VORTEX, when (definition.element) {
            SkillElement.ICE -> intArrayOf(0, 2, 4, 7)[variant]
            SkillElement.WIND -> intArrayOf(1, 2, 3, 8)[variant]
            SkillElement.ARCANE, SkillElement.COSMIC -> intArrayOf(4, 5, 6, 8)[variant]
            else -> intArrayOf(1, 2, 3, 5)[variant]
        }), SemanticVfxFlow.CLOCKWISE, SemanticImpactStyle.RING, "rotation verb")
        SemanticVfxAction.BURST -> SemanticVfxPlan(action, ModularRecipe(ModularVfxArchetype.VORTEX, when {
            name.containsAny("파동", "충격") -> 7
            name.containsAny("균열", "파쇄") -> 9
            else -> when (definition.element) {
                SkillElement.FIRE -> intArrayOf(3, 8, 9, 1)[variant]
                SkillElement.ICE -> intArrayOf(0, 4, 7, 9)[variant]
                SkillElement.LIGHTNING -> intArrayOf(2, 3, 8, 9)[variant]
                SkillElement.ARCANE -> intArrayOf(4, 6, 7, 8)[variant]
                SkillElement.COSMIC -> intArrayOf(5, 8, 9, 4)[variant]
                else -> intArrayOf(0, 3, 7, 9)[variant]
            }
        }), SemanticVfxFlow.CENTER_OUT, SemanticImpactStyle.RING, "burst verb")
        SemanticVfxAction.COLLAPSE -> SemanticVfxPlan(action, ModularRecipe(ModularVfxArchetype.VORTEX, when (definition.element) {
            SkillElement.COSMIC -> intArrayOf(5, 8, 1, 4)[variant]
            SkillElement.ARCANE, SkillElement.DARK -> intArrayOf(1, 6, 7, 8)[variant]
            else -> intArrayOf(1, 7, 8, 6)[variant]
        }), SemanticVfxFlow.OUTSIDE_IN, SemanticImpactStyle.RING, "collapse verb")
        SemanticVfxAction.TRAP -> SemanticVfxPlan(action, ModularRecipe(ModularVfxArchetype.VORTEX, if (name.containsAny("감옥", "봉쇄", "포박")) 6 else 7), SemanticVfxFlow.OUTSIDE_IN, SemanticImpactStyle.RING, "trap verb")
    }
    return when {
        warrior -> warriorPlan(definition, plan)
        definition.heroClass == HeroClass.RANGER -> rangerPlan(definition, plan)
        definition.heroClass == HeroClass.PALADIN -> paladinPlan(definition, plan)
        else -> plan
    }
}

private fun paladinPlan(definition: SkillDefinition, fallback: SemanticVfxPlan): SemanticVfxPlan {
    val tier = definition.intensityTier
    val recipe = when (fallback.action) {
        SemanticVfxAction.CUT -> ModularRecipe(ModularVfxArchetype.SLASH, intArrayOf(0, 2, 4, 7, 9)[tier - 1])
        SemanticVfxAction.CROSS_CUT -> ModularRecipe(ModularVfxArchetype.SLASH, intArrayOf(5, 8, 5, 8, 10)[tier - 1])
        SemanticVfxAction.FLURRY -> ModularRecipe(ModularVfxArchetype.SLASH, (definition.unlockLevel / 5 + definition.candidate * 3).mod(11))
        SemanticVfxAction.DESCEND -> ModularRecipe(ModularVfxArchetype.COLUMN, intArrayOf(1, 8, 9, 6, 7)[tier - 1])
        SemanticVfxAction.BURST -> ModularRecipe(ModularVfxArchetype.VORTEX, (definition.unlockLevel / 10 + definition.candidate * 2).mod(10))
        SemanticVfxAction.SPIN -> ModularRecipe(ModularVfxArchetype.VORTEX, intArrayOf(1, 3, 5, 8, 9)[tier - 1])
        SemanticVfxAction.BEAM -> ModularRecipe(ModularVfxArchetype.PROJECTILE, intArrayOf(3, 3, 8, 8, 9)[tier - 1])
        else -> fallback.recipe
    }
    return fallback.copy(recipe = recipe, reason = "paladin tiered holy choreography")
}

private fun rangerPlan(definition: SkillDefinition, fallback: SemanticVfxPlan): SemanticVfxPlan {
    if (fallback.action != SemanticVfxAction.VOLLEY) return fallback
    val verticalRain = definition.name.containsAny("화살비", "폭우", "유성우", "성우", "폭격")
    if (verticalRain) return fallback.copy(
        recipe = ModularRecipe(ModularVfxArchetype.COLUMN, when (definition.element) {
            SkillElement.COSMIC -> 7
            SkillElement.WIND -> 6
            else -> definition.effectVariant.mod(10)
        }),
        flow = SemanticVfxFlow.TOP_TO_BOTTOM,
        reason = "ranger vertical arrow rain",
    )
    return fallback.copy(
        recipe = ModularRecipe(ModularVfxArchetype.PROJECTILE, when (definition.element) {
            SkillElement.COSMIC -> 8
            SkillElement.WIND -> 5
            else -> (definition.effectVariant * 2 + definition.intensityTier).mod(10)
        }),
        flow = SemanticVfxFlow.OUTSIDE_IN,
        reason = "ranger converging projectile volley",
    )
}

private fun warriorPlan(definition: SkillDefinition, fallback: SemanticVfxPlan): SemanticVfxPlan {
    val tier = (definition.unlockLevel / 5).coerceIn(1, 20)
    val recipeIndex = when (fallback.action) {
        SemanticVfxAction.FLURRY -> intArrayOf(
            0, 0, 0, 0, 4, 5, 1, 1, 4, 4,
            5, 2, 4, 8, 6, 3, 4, 4, 9, 8,
        )[tier - 1]
        SemanticVfxAction.CUT -> intArrayOf(
            0, 2, 0, 0, 2, 0, 3, 2, 0, 4,
            7, 4, 7, 7, 3, 7, 10, 10, 10, 10,
        )[tier - 1]
        SemanticVfxAction.CROSS_CUT -> 8
        SemanticVfxAction.EXECUTE -> if (definition.intensityTier >= 5) 10 else 1
        SemanticVfxAction.DESCEND -> 1
        SemanticVfxAction.CHARGE -> 6
        else -> return fallback
    }
    return fallback.copy(
        recipe = ModularRecipe(ModularVfxArchetype.WARRIOR, recipeIndex),
        flow = when (fallback.action) {
            SemanticVfxAction.DESCEND, SemanticVfxAction.EXECUTE -> SemanticVfxFlow.TOP_TO_BOTTOM
            else -> SemanticVfxFlow.LEFT_TO_RIGHT
        },
        reason = "warrior-specific ${fallback.action.name.lowercase()} choreography",
    )
}

internal fun modularRecipe(definition: SkillDefinition): ModularRecipe = semanticVfxPlan(definition).recipe

private fun String.containsAny(vararg keywords: String): Boolean = keywords.any(::contains)

@DrawableRes
private fun classFallbackResource(heroClass: HeroClass): Int = when (heroClass) {
    HeroClass.WARRIOR -> R.drawable.vfx8_warrior_slash_primary
    HeroClass.ROGUE -> R.drawable.vfx3_rogue_secondary_pierce
    HeroClass.RANGER -> R.drawable.vfx9_ranger_precision_anticipation
    HeroClass.MAGE -> R.drawable.vfx2_mage_c01_secondary
    HeroClass.CLERIC -> R.drawable.vfx10_cleric_light_tail
    HeroClass.PALADIN -> R.drawable.vfx2_paladin_c01_secondary
}

internal fun classPrimaryResources(heroClass: HeroClass): IntArray =
    IntArray(5) { classFallbackResource(heroClass) }

/**
 * SkillDefinition.candidate is zero based. Keep the mapping in one place so the renderer,
 * direction audit and tests cannot accidentally treat c02 as the first visual resource.
 */
@DrawableRes
internal fun classPrimaryResource(definition: SkillDefinition): Int {
    val resources = classPrimaryResources(definition.heroClass)
    return resources[definition.candidate.mod(resources.size)]
}

@DrawableRes
internal fun classBandPrimaryResource(
    definition: SkillDefinition,
    resolver: ClassBandPrimaryResolver = AuthoredClassBandPrimaryResolver,
): Int = resolver.resolve(classCandidateBandKey(definition), classPrimaryResource(definition))

/**
 * Resolve only the resources used by this attack. The secondary and residual roles are
 * intentionally separate from the body and contact resources, so an attack is never a
 * single bitmap enlarged several times.
 */
internal fun classLayeredAssetSpec(
    definition: SkillDefinition,
    primaryResolver: ClassBandPrimaryResolver = AuthoredClassBandPrimaryResolver,
    depthResolver: ClassBandDepthAssetResolver = AuthoredClassBandDepthAssetResolver,
    roleResolver: ClassBandRoleAssetResolver = AuthoredClassBandRoleAssetResolver,
    sixRoleResolver: ClassBandSixRoleAssetResolver = AuthoredClassBandSixRoleAssetResolver,
): ClassLayeredAssetSpec {
    val impacts = classImpactResources(definition.heroClass)
    val primary = classBandPrimaryResource(definition, primaryResolver)
    val band = classVfxSignature(definition).growthBand
    val rogueRangerCandidate = rogueRangerCandidateVfxAssets(definition.heroClass, definition.candidate)
    val rogueRangerImpacts = rogueRangerImpactVfxAssets(definition.heroClass)
    val rogueRangerDebris = rogueRangerDebrisVfxAssets(definition.heroClass)
    val mageClericCandidate = mageClericCandidateVfxAssets(definition.heroClass, definition.candidate)
    val mageClericImpacts = mageClericImpactVfxAssets(definition.heroClass)
    val mageClericDebris = mageClericDebrisVfxAssets(definition.heroClass)
    val paladinCandidate = definition.takeIf { it.heroClass == HeroClass.PALADIN }
        ?.let { paladinCandidateVfxAssets(it.candidate) }
    val (fallbackSecondary, fallbackResidual) = when {
        definition.heroClass == HeroClass.WARRIOR -> when (definition.candidate) {
            1 -> R.drawable.vfx8_warrior_heavy_anticipation to classOverlayResource(definition.heroClass)
            2 -> R.drawable.vfx7_warrior_charge_anticipation to classOverlayResource(definition.heroClass)
            3 -> R.drawable.vfx2_warrior_earth_residual to classOverlayResource(definition.heroClass)
            else -> classOverlayResource(definition.heroClass) to classOverlayResource(definition.heroClass)
        }
        rogueRangerCandidate != null -> rogueRangerCandidate.secondary to rogueRangerCandidate.residual
        mageClericCandidate != null -> mageClericCandidate.secondary to mageClericCandidate.residual
        paladinCandidate != null -> paladinCandidate.secondary to paladinCandidate.residual
        else -> classOverlayResource(definition.heroClass) to classOverlayResource(definition.heroClass)
    }
    val depthAssets = depthResolver.resolve(
        classCandidateBandKey(definition),
        skillVfxIdentity(definition),
        ClassBandDepthAssets(fallbackSecondary, fallbackResidual),
    )
    val secondary = depthAssets.secondary
    val residual = depthAssets.residual
    val warriorBandImpact = when (band) {
        0 -> R.drawable.vfx6_warrior_heavy_contact
        1 -> R.drawable.vfx6_warrior_heavy_contact
        2 -> R.drawable.vfx6_warrior_heavy_contact
        3 -> R.drawable.vfx6_warrior_heavy_contact
        else -> R.drawable.vfx6_warrior_heavy_contact
    }
    val warriorBandDebris = when (band) {
        0 -> R.drawable.vfx6_warrior_heavy_debris
        1 -> R.drawable.vfx6_warrior_heavy_debris
        2 -> R.drawable.vfx6_warrior_heavy_debris
        3 -> R.drawable.vfx6_warrior_heavy_debris
        else -> R.drawable.vfx6_warrior_heavy_debris
    }
    val warriorBandAfterglow = when (band) {
        0 -> R.drawable.vfx6_warrior_heavy_echo
        1 -> R.drawable.vfx6_warrior_heavy_echo
        2 -> R.drawable.vfx6_warrior_heavy_echo
        3 -> R.drawable.vfx6_warrior_heavy_echo
        else -> R.drawable.vfx6_warrior_heavy_echo
    }
    val roleAssets = roleResolver.resolve(
        classCandidateBandKey(definition),
        skillVfxIdentity(definition),
        ClassBandRoleAssets(
            primaryA = primary,
            primaryB = primary,
            finisherRing = if (definition.heroClass == HeroClass.WARRIOR) warriorBandAfterglow else residual,
            finisherEcho = secondary,
        ),
    )
    val selectedPrimary = authoredPrimaryAssetForHit(
        definition = definition,
        primaryA = roleAssets.primaryA,
        primaryB = roleAssets.primaryB,
        hitIndex = 0,
    )
    val fallbackSpec = ClassLayeredAssetSpec(
        primary = selectedPrimary,
        primaryA = roleAssets.primaryA,
        primaryB = roleAssets.primaryB,
        secondary = secondary,
        impactPoint = when {
            definition.heroClass == HeroClass.WARRIOR -> warriorBandImpact
            rogueRangerImpacts != null -> rogueRangerImpacts.point
            mageClericImpacts != null -> mageClericImpacts.point
            definition.heroClass == HeroClass.PALADIN -> paladinImpactVfxAssets.point
            else -> impacts[ClassImpactLayer.POINT.ordinal]
        },
        impactFracture = when {
            definition.heroClass == HeroClass.WARRIOR -> warriorBandImpact
            rogueRangerImpacts != null -> rogueRangerImpacts.fracture
            mageClericImpacts != null -> mageClericImpacts.fracture
            definition.heroClass == HeroClass.PALADIN -> paladinImpactVfxAssets.fracture
            else -> impacts[ClassImpactLayer.FRACTURE.ordinal]
        },
        impactRing = when {
            rogueRangerImpacts != null -> rogueRangerImpacts.ring
            mageClericImpacts != null -> mageClericImpacts.ring
            definition.heroClass == HeroClass.PALADIN -> paladinImpactVfxAssets.ring
            else -> impacts[ClassImpactLayer.RING.ordinal]
        },
        debrisPoint = when {
            definition.heroClass == HeroClass.WARRIOR -> warriorBandDebris
            rogueRangerDebris != null -> rogueRangerDebris.point
            mageClericDebris != null -> mageClericDebris.point
            definition.heroClass == HeroClass.PALADIN -> paladinDebrisVfxAssets.point
            else -> classDebrisResource(definition.heroClass)
        },
        debrisFracture = when {
            definition.heroClass == HeroClass.WARRIOR -> warriorBandDebris
            rogueRangerDebris != null -> rogueRangerDebris.fracture
            mageClericDebris != null -> mageClericDebris.fracture
            definition.heroClass == HeroClass.PALADIN -> paladinDebrisVfxAssets.fracture
            else -> classDebrisResource(definition.heroClass)
        },
        debrisRing = when {
            definition.heroClass == HeroClass.WARRIOR -> warriorBandDebris
            rogueRangerDebris != null -> rogueRangerDebris.ring
            mageClericDebris != null -> mageClericDebris.ring
            definition.heroClass == HeroClass.PALADIN -> paladinDebrisVfxAssets.ring
            else -> classDebrisResource(definition.heroClass)
        },
        residual = if (definition.heroClass == HeroClass.WARRIOR) {
            warriorBandAfterglow
        } else {
            residual
        },
        finisherRing = roleAssets.finisherRing,
        finisherEcho = roleAssets.finisherEcho,
    )
    val resolved = sixRoleResolver.resolve(
        key = classCandidateBandKey(definition),
        identity = skillVfxIdentity(definition),
        fallback = ClassBandSixRoleAssets(
            anticipation = fallbackSpec.secondary,
            primaryA = fallbackSpec.primaryA,
            primaryB = fallbackSpec.primaryB,
            contactPoint = fallbackSpec.impactPoint,
            contactFracture = fallbackSpec.impactFracture,
            contactRing = fallbackSpec.impactRing,
            debrisPoint = fallbackSpec.debrisPoint,
            debrisFracture = fallbackSpec.debrisFracture,
            debrisRing = fallbackSpec.debrisRing,
            residual = fallbackSpec.residual,
            finisherRing = fallbackSpec.finisherRing,
            // Never reuse anticipation as the echo plane. This existing class silhouette remains
            // a valid production fallback until the dedicated candidate vfx6 echo is connected.
            finisherEcho = classOverlayResource(definition.heroClass),
        ),
    )
    return fallbackSpec.copy(
        primary = authoredPrimaryAssetForHit(definition, resolved.primaryA, resolved.primaryB, 0),
        primaryA = resolved.primaryA,
        primaryB = resolved.primaryB,
        secondary = resolved.anticipation,
        impactPoint = resolved.contactPoint,
        impactFracture = resolved.contactFracture,
        impactRing = resolved.contactRing,
        debrisPoint = resolved.debrisPoint,
        debrisFracture = resolved.debrisFracture,
        debrisRing = resolved.debrisRing,
        residual = resolved.residual,
        finisherRing = resolved.finisherRing,
        finisherEcho = resolved.finisherEcho,
    )
}

/** Stable A/B allocation: every four-skill high cell uses both assets and multi-hits alternate. */
@DrawableRes
internal fun authoredPrimaryAssetForHit(
    definition: SkillDefinition,
    @DrawableRes primaryA: Int,
    @DrawableRes primaryB: Int,
    hitIndex: Int,
): Int {
    val catalogTier = (definition.unlockLevel / 5).coerceAtLeast(1)
    val baseVariant = (catalogTier + definition.candidate).mod(2)
    return if ((baseVariant + hitIndex).mod(2) == 0) primaryA else primaryB
}

@DrawableRes
internal fun classOverlayResource(heroClass: HeroClass): Int = classFallbackResource(heroClass)

@DrawableRes
internal fun classImpactResources(heroClass: HeroClass): IntArray =
    IntArray(ClassImpactLayer.entries.size) { classFallbackResource(heroClass) }

@DrawableRes
internal fun classDebrisResource(heroClass: HeroClass): Int = classFallbackResource(heroClass)

internal data class SemanticHitChoreography(
    val recipe: ModularRecipe,
    val xOffsetFraction: Float,
    val yOffsetFraction: Float,
    val widthScale: Float,
    /** The direction the player should perceive, independent from the source bitmap's authored slant. */
    val visualDirection: Float,
    /** Horizontal transform needed to make the authored bitmap match [visualDirection]. */
    val mirrorDirection: Float,
    val rotationDegrees: Float,
)

internal fun semanticHitChoreography(
    definition: SkillDefinition,
    hitIndex: Int,
): SemanticHitChoreography {
    val plan = semanticVfxPlan(definition)
    val isFinal = hitIndex == definition.hitCount - 1
    val baseIndex = hitIndex.mod(12)
    val isMultiBlade = plan.action in setOf(
        SemanticVfxAction.FLURRY,
        SemanticVfxAction.CROSS_CUT,
    ) || definition.hitCount >= 3 && plan.recipe.archetype == ModularVfxArchetype.SLASH
    val xOffsets = floatArrayOf(-0.12f, 0.10f, -0.05f, 0.13f, -0.09f, 0.07f, -0.14f, 0.12f, -0.04f, 0.09f, -0.11f, 0f)
    val yOffsets = floatArrayOf(-0.07f, 0.06f, 0.09f, -0.04f, -0.11f, 0.03f, 0.05f, -0.09f, 0.12f, 0.08f, -0.03f, 0f)
    val widthScales = floatArrayOf(0.82f, 0.86f, 0.88f, 0.91f, 0.93f, 0.95f, 0.92f, 0.96f, 0.98f, 1.00f, 1.02f, 1.10f)
    val rotations = floatArrayOf(-28f, 24f, -12f, 34f, -38f, 15f, -21f, 42f, -7f, 29f, -33f, 0f)
    val recipe = when (plan.action) {
        SemanticVfxAction.FLURRY -> ModularRecipe(
            if (definition.heroClass == HeroClass.WARRIOR) ModularVfxArchetype.WARRIOR else ModularVfxArchetype.SLASH,
            if (definition.heroClass == HeroClass.WARRIOR) {
                val stagePatterns = arrayOf(
                    intArrayOf(0, 2, 0),
                    intArrayOf(0, 2, 3, 0, 4),
                    intArrayOf(0, 2, 3, 4, 5, 7, 4),
                    intArrayOf(0, 3, 2, 4, 5, 3, 7, 6, 8),
                    intArrayOf(0, 3, 2, 4, 5, 6, 7, 3, 4, 9, 8, 10),
                )
                val pattern = stagePatterns[definition.intensityTier - 1]
                if (isFinal) semanticVfxPlan(definition).recipe.index else pattern[hitIndex.mod(pattern.size)]
            } else {
                intArrayOf(1, 2, 4, 8, 9)[hitIndex.mod(5)]
            },
        )
        SemanticVfxAction.CROSS_CUT -> ModularRecipe(
            if (definition.heroClass == HeroClass.WARRIOR) ModularVfxArchetype.WARRIOR else ModularVfxArchetype.SLASH,
            if (definition.heroClass == HeroClass.WARRIOR) {
                if (isFinal) 8 else if (baseIndex % 2 == 0) 0 else 2
            } else {
                if (baseIndex % 2 == 0) 1 else 2
            },
        )
        else -> if (isMultiBlade) {
            ModularRecipe(ModularVfxArchetype.SLASH, intArrayOf(1, 2, 4, 8, 9)[hitIndex.mod(5)])
        } else {
            plan.recipe
        }
    }
    val visualDirection = if (definition.hitCount > 1 && hitIndex % 2 == 1) -1f else 1f
    val authoredDirection = when (recipe.archetype) {
        // slash_03 was authored with the opposite diagonal to the other selected slash parts.
        ModularVfxArchetype.SLASH -> if (recipe.index == 2) -1f else 1f
        ModularVfxArchetype.WARRIOR -> 1f
        else -> 1f
    }
    return SemanticHitChoreography(
        recipe = recipe,
        xOffsetFraction = if (definition.hitCount == 1 || isFinal) 0f else xOffsets[baseIndex],
        yOffsetFraction = if (definition.hitCount == 1 || isFinal) 0f else yOffsets[baseIndex],
        widthScale = if (definition.hitCount == 1) 1f else if (isFinal) 1.08f else widthScales[baseIndex],
        visualDirection = visualDirection,
        mirrorDirection = visualDirection * authoredDirection,
        rotationDegrees = if (definition.hitCount == 1) 0f else rotations[baseIndex] * visualDirection,
    )
}

internal fun modularVfxArchetype(motion: SkillMotion): ModularVfxArchetype = when (motion) {
    SkillMotion.CLEAVE_HORIZONTAL,
    SkillMotion.CROSS_SLASH,
    SkillMotion.RAPID_THREE,
    SkillMotion.FRENZY_FIVE,
    SkillMotion.TRAP_SNAP,
    SkillMotion.EXECUTE_PAUSE,
    -> ModularVfxArchetype.SLASH

    SkillMotion.PIERCE_LINE,
    SkillMotion.DASH_IMPACT,
    SkillMotion.PROJECTILE_SINGLE,
    SkillMotion.PROJECTILE_VOLLEY,
    SkillMotion.BEAM_CHANNEL,
    SkillMotion.CHAIN_ARC,
    -> ModularVfxArchetype.PROJECTILE

    SkillMotion.HEAVY_FALL,
    SkillMotion.RAIN_VERTICAL,
    SkillMotion.PILLAR_DROP,
    SkillMotion.ERUPTION_UP,
    SkillMotion.SUMMON_DIVE,
    -> ModularVfxArchetype.COLUMN

    SkillMotion.SPIN_CUT,
    SkillMotion.NOVA_RADIAL,
    SkillMotion.GRAVITY_COLLAPSE,
    -> ModularVfxArchetype.VORTEX
}

internal fun modularHitPoint(definition: SkillDefinition, hitIndex: Int): LightningImpactPoint {
    if (hitIndex == definition.hitTimingsMillis.lastIndex) return LightningImpactPoint(0.50f, 0.60f)
    val base = when (semanticVfxPlan(definition).recipe.archetype) {
        ModularVfxArchetype.WARRIOR,
        ModularVfxArchetype.SLASH -> listOf(
            LightningImpactPoint(0.40f, 0.55f),
            LightningImpactPoint(0.58f, 0.63f),
            LightningImpactPoint(0.46f, 0.66f),
            LightningImpactPoint(0.63f, 0.53f),
        )
        ModularVfxArchetype.PROJECTILE -> listOf(
            LightningImpactPoint(0.38f, 0.57f),
            LightningImpactPoint(0.55f, 0.62f),
            LightningImpactPoint(0.67f, 0.55f),
            LightningImpactPoint(0.46f, 0.65f),
        )
        ModularVfxArchetype.COLUMN -> listOf(
            LightningImpactPoint(0.36f, 0.61f),
            LightningImpactPoint(0.64f, 0.61f),
            LightningImpactPoint(0.47f, 0.65f),
            LightningImpactPoint(0.58f, 0.56f),
        )
        ModularVfxArchetype.VORTEX,
        ModularVfxArchetype.IMPACT,
        -> listOf(
            LightningImpactPoint(0.44f, 0.58f),
            LightningImpactPoint(0.58f, 0.62f),
            LightningImpactPoint(0.48f, 0.66f),
            LightningImpactPoint(0.62f, 0.55f),
        )
    }
    return base[(hitIndex + definition.effectVariant) % base.size]
}

internal data class CenterConvergenceOrigin(val xFraction: Float, val yFraction: Float)

internal data class CenterConvergenceFrame(
    val xFraction: Float,
    val yFraction: Float,
    val scale: Float,
    val rotationDegrees: Float,
)

internal data class LightningImpactPoint(
    val xFraction: Float,
    val yFraction: Float,
)

private val CENTER_CONVERGENCE_ORIGINS = listOf(
    CenterConvergenceOrigin(0.05f, 0.48f),
    CenterConvergenceOrigin(0.95f, 0.52f),
    CenterConvergenceOrigin(0.15f, 0.20f),
    CenterConvergenceOrigin(0.85f, 0.20f),
    CenterConvergenceOrigin(0.12f, 0.88f),
    CenterConvergenceOrigin(0.88f, 0.88f),
    CenterConvergenceOrigin(0.50f, 0.10f),
    CenterConvergenceOrigin(0.50f, 0.94f),
)

internal fun usesCenterConvergence(definition: SkillDefinition, hitIndex: Int): Boolean {
    val action = semanticVfxPlan(definition).action
    return action in setOf(
        SemanticVfxAction.SHOT,
        SemanticVfxAction.PIERCE,
        SemanticVfxAction.CHAIN,
        SemanticVfxAction.CHARGE,
    ) || action == SemanticVfxAction.FLURRY &&
        definition.heroClass != HeroClass.WARRIOR &&
        hitIndex == definition.hitTimingsMillis.lastIndex
}

internal fun centerConvergenceOrigin(definition: SkillDefinition, hitIndex: Int): CenterConvergenceOrigin {
    val index = (definition.effectVariant * 2 + hitIndex * 3).mod(CENTER_CONVERGENCE_ORIGINS.size)
    return CENTER_CONVERGENCE_ORIGINS[index]
}

internal fun centerConvergenceFrame(
    progress: Float,
    origin: CenterConvergenceOrigin,
    reducedMotion: Boolean,
): CenterConvergenceFrame {
    val raw = progress.coerceIn(0f, 1f)
    val eased = raw * raw
    val targetX = 0.50f
    val targetY = 0.60f
    val travelScale = if (reducedMotion) 0f else 1f
    val startX = targetX + (origin.xFraction - targetX) * travelScale
    val startY = targetY + (origin.yFraction - targetY) * travelScale
    val x = startX + (targetX - startX) * eased
    val y = startY + (targetY - startY) * eased
    val startScale = 1f
    val scale = if (reducedMotion) 1f else startScale + (0.36f - startScale) * eased
    val rotation = if (reducedMotion) {
        0f
    } else {
        Math.toDegrees(
            atan2((targetY - startY).toDouble(), (targetX - startX).toDouble()),
        ).toFloat()
    }
    return CenterConvergenceFrame(x, y, scale, rotation)
}

@DrawableRes
internal fun warriorNonSlashAccentResource(definition: SkillDefinition): Int = when (warriorNonSlashColumn(definition)) {
    WarriorNonSlashColumn.HEAVY -> R.drawable.vfx8_warrior_heavy_anticipation
    WarriorNonSlashColumn.CHARGE -> R.drawable.vfx7_warrior_charge_anticipation
    WarriorNonSlashColumn.EARTH -> R.drawable.vfx7_warrior_earth_anticipation
    null -> classOverlayResource(definition.heroClass)
}

internal enum class LegacyWarriorBranch {
    STEEL_SLASH,
    CONTINUOUS_SLASH,
    SINGLE_SLASH,
    COMBO_SLASH,
}

/**
 * Original warrior multi-slash artwork restored for candidate five only.
 * Single-slash skills intentionally remain on the reviewed VFX8 family.
 */
private val LEGACY_WARRIOR_FLURRY_RESOURCES = intArrayOf(
    R.drawable.vfx_warrior_01,
    R.drawable.vfx_warrior_02,
    R.drawable.vfx_warrior_03,
    R.drawable.vfx_warrior_04,
    R.drawable.vfx_warrior_05,
    R.drawable.vfx_warrior_06,
    R.drawable.vfx_warrior_07,
    R.drawable.vfx_warrior_08,
    R.drawable.vfx_warrior_09,
    R.drawable.vfx_warrior_10,
)

@DrawableRes
internal fun legacyWarriorFlurryResource(index: Int): Int =
    LEGACY_WARRIOR_FLURRY_RESOURCES[index.mod(LEGACY_WARRIOR_FLURRY_RESOURCES.size)]

internal data class LegacyWarriorDescriptor(
    val branch: LegacyWarriorBranch,
    /** Stable runtime branch identifier consumed by the browser export. */
    val branchKey: String,
    @DrawableRes val primaryAssetId: Int,
    val recipe: ModularRecipe,
)

internal fun legacyWarriorDescriptor(definition: SkillDefinition): LegacyWarriorDescriptor? {
    if (!shouldKeepLegacyPrimary(definition)) return null
    val branch = when {
        isSteelSlash(definition) -> LegacyWarriorBranch.STEEL_SLASH
        isContinuousSlash(definition) -> LegacyWarriorBranch.CONTINUOUS_SLASH
        definition.candidate == 0 -> LegacyWarriorBranch.SINGLE_SLASH
        else -> LegacyWarriorBranch.COMBO_SLASH
    }
    val recipe = semanticHitChoreography(definition, definition.hitTimingsMillis.lastIndex).recipe
    val primary = when (branch) {
        LegacyWarriorBranch.STEEL_SLASH,
        LegacyWarriorBranch.SINGLE_SLASH,
        -> R.drawable.vfx8_warrior_slash_primary
        LegacyWarriorBranch.CONTINUOUS_SLASH,
        LegacyWarriorBranch.COMBO_SLASH,
        -> legacyWarriorFlurryResource(recipe.index)
    }
    return LegacyWarriorDescriptor(
        branch = branch,
        branchKey = "${branch.name}:${definition.catalogId}",
        primaryAssetId = primary,
        recipe = recipe,
    )
}

private data class LegacySingleSlashSpec(
    val start: Int,
    val fadeStart: Int,
    val end: Int,
    val reducedEnd: Int,
    val maxAlpha: Float,
    val baseWidth: Float,
    val widthGrowth: Float,
    val reducedWidth: Float,
    val y: Float,
    val rotationStart: Float,
    val rotationTravel: Float = 0f,
    val zeroRotationWhenReduced: Boolean = false,
    val mirror: Float = 1f,
    val tintArgb: Long? = null,
)

private fun legacySingleSlashSpec(tier: Int): LegacySingleSlashSpec = when (tier) {
    2 -> LegacySingleSlashSpec(250, 560, 760, 620, .86f, .72f, .06f, .78f, .59f, -2f)
    3 -> LegacySingleSlashSpec(270, 590, 780, 620, .90f, .68f, .16f, .80f, .59f, -9f)
    4 -> LegacySingleSlashSpec(255, 600, 800, 620, .92f, .70f, .17f, .83f, .59f, -12f, mirror = -1f)
    5 -> LegacySingleSlashSpec(230, 620, 830, 630, .95f, .70f, .22f, .86f, .58f, -7f)
    6 -> LegacySingleSlashSpec(240, 640, 850, 640, .91f, .58f, .24f, .74f, .60f, -24f, 46f, true)
    7 -> LegacySingleSlashSpec(220, 640, 860, 650, .96f, .70f, .24f, .88f, .58f, -5f)
    8 -> LegacySingleSlashSpec(210, 650, 880, 660, .97f, .72f, .25f, .90f, .58f, -4f)
    9 -> LegacySingleSlashSpec(190, 670, 900, 680, .98f, .58f, .28f, .78f, .60f, -34f, 68f, true)
    10 -> LegacySingleSlashSpec(180, 680, 920, 690, .98f, .72f, .28f, .92f, .58f, -3f)
    11 -> LegacySingleSlashSpec(165, 690, 940, 700, .99f, .74f, .29f, .94f, .59f, -2f)
    12 -> LegacySingleSlashSpec(155, 700, 955, 710, .99f, .76f, .30f, .96f, .58f, -1f)
    13 -> LegacySingleSlashSpec(145, 710, 970, 720, .99f, .78f, .31f, .98f, .58f, 0f)
    14 -> LegacySingleSlashSpec(135, 720, 985, 730, 1f, .80f, .32f, 1f, .57f, 1f)
    15 -> LegacySingleSlashSpec(125, 730, 1000, 740, 1f, .82f, .33f, 1.02f, .57f, -1f, tintArgb = 0xFFFFE5A0L)
    16 -> LegacySingleSlashSpec(115, 740, 1015, 750, 1f, .84f, .34f, 1.04f, .57f, -2f, tintArgb = 0xFFA7E9DFL)
    17 -> LegacySingleSlashSpec(105, 750, 1030, 760, 1f, .86f, .35f, 1.06f, .56f, -1f)
    18 -> LegacySingleSlashSpec(95, 760, 1045, 770, 1f, .88f, .36f, 1.08f, .56f, 0f)
    19 -> LegacySingleSlashSpec(85, 770, 1060, 780, 1f, .90f, .38f, 1.10f, .56f, -2f)
    20 -> LegacySingleSlashSpec(70, 790, 1080, 800, 1f, .92f, .40f, 1.12f, .56f, 0f)
    else -> error("No legacy single-slash spec for tier $tier")
}

private data class LegacyComboSpec(
    val rotations: FloatArray,
    val xs: FloatArray,
    val ys: FloatArray,
    val normalScale: Float,
    val finalScale: Float,
    val tintArgb: Long? = null,
)

private fun legacyComboSpec(tier: Int): LegacyComboSpec = when (tier) {
    2 -> LegacyComboSpec(floatArrayOf(-24f, 26f, -5f), floatArrayOf(.42f, .58f, .50f), floatArrayOf(.53f, .65f, .58f), 1f, 1f)
    3 -> LegacyComboSpec(floatArrayOf(-31f, 27f, -15f, 35f, -4f), floatArrayOf(.39f, .61f, .45f, .57f, .50f), floatArrayOf(.51f, .65f, .67f, .50f, .59f), .86f, 1.06f)
    4 -> LegacyComboSpec(floatArrayOf(-34f, 31f, -18f, 23f, -2f), floatArrayOf(.38f, .62f, .43f, .58f, .50f), floatArrayOf(.51f, .66f, .67f, .52f, .59f), .88f, 1.10f)
    5 -> LegacyComboSpec(floatArrayOf(-38f, 33f, -20f, 28f, -1f), floatArrayOf(.37f, .63f, .42f, .60f, .50f), floatArrayOf(.50f, .67f, .68f, .50f, .58f), .92f, 1.16f)
    6 -> LegacyComboSpec(floatArrayOf(-41f, 36f, -23f, 29f, -11f, 3f), floatArrayOf(.36f, .64f, .41f, .60f, .45f, .50f), floatArrayOf(.49f, .68f, .69f, .50f, .62f, .58f), .94f, 1.20f)
    7 -> LegacyComboSpec(floatArrayOf(-43f, 38f, -25f, 3f), floatArrayOf(.36f, .64f, .43f, .50f), floatArrayOf(.49f, .68f, .67f, .58f), .96f, 1.24f)
    8 -> LegacyComboSpec(floatArrayOf(-45f, 40f, -28f, 32f, -16f, 21f, 1f), floatArrayOf(.34f, .66f, .40f, .62f, .43f, .58f, .50f), floatArrayOf(.48f, .69f, .70f, .49f, .65f, .52f, .58f), .96f, 1.28f)
    9 -> LegacyComboSpec(floatArrayOf(-47f, 42f, -30f, 35f, -18f, 2f), floatArrayOf(.33f, .67f, .39f, .63f, .43f, .50f), floatArrayOf(.47f, .70f, .71f, .48f, .66f, .58f), .99f, 1.32f)
    10 -> LegacyComboSpec(floatArrayOf(-49f, 44f, -32f, 37f, -21f, 25f, 2f), floatArrayOf(.32f, .68f, .38f, .64f, .42f, .59f, .50f), floatArrayOf(.46f, .71f, .72f, .47f, .67f, .51f, .58f), 1.01f, 1.36f)
    11 -> LegacyComboSpec(floatArrayOf(-51f, 46f, -34f, 39f, -23f, 27f, 3f), floatArrayOf(.31f, .69f, .37f, .65f, .41f, .60f, .50f), floatArrayOf(.45f, .72f, .73f, .46f, .68f, .50f, .58f), 1.03f, 1.40f, 0xFFBDEFE5L)
    12 -> LegacyComboSpec(floatArrayOf(-53f, 48f, -36f, 41f, -25f, 29f, 4f), floatArrayOf(.30f, .70f, .36f, .66f, .40f, .61f, .50f), floatArrayOf(.44f, .73f, .74f, .45f, .69f, .49f, .58f), 1.05f, 1.44f)
    13 -> LegacyComboSpec(floatArrayOf(-55f, 50f, -38f, 43f, -27f, 5f), floatArrayOf(.29f, .71f, .35f, .67f, .39f, .50f), floatArrayOf(.43f, .74f, .75f, .44f, .70f, .58f), 1.07f, 1.48f)
    14 -> LegacyComboSpec(floatArrayOf(-56f, 52f, -41f, 45f, -31f, 34f, -20f, 23f, -9f, 6f), floatArrayOf(.28f, .72f, .34f, .68f, .38f, .63f, .42f, .58f, .46f, .50f), floatArrayOf(.42f, .75f, .76f, .43f, .71f, .47f, .67f, .51f, .62f, .58f), 1.08f, 1.54f)
    15 -> LegacyComboSpec(floatArrayOf(-58f, 54f, -43f, 47f, -33f, 36f, -18f, 7f), floatArrayOf(.27f, .73f, .33f, .69f, .37f, .64f, .43f, .50f), floatArrayOf(.41f, .76f, .77f, .42f, .72f, .46f, .66f, .58f), 1.10f, 1.60f, 0xFFD9D8FFL)
    16 -> LegacyComboSpec(floatArrayOf(-60f, 56f, -46f, 49f, -36f, 39f, -27f, 30f, -18f, 21f, -8f, 6f), floatArrayOf(.26f, .74f, .32f, .70f, .36f, .65f, .40f, .61f, .44f, .57f, .47f, .50f), floatArrayOf(.40f, .77f, .78f, .41f, .73f, .45f, .69f, .49f, .65f, .53f, .61f, .58f), 1.12f, 1.66f)
    17 -> LegacyComboSpec(floatArrayOf(-62f, 58f, -48f, 51f, -38f, 41f, -28f, 31f, 7f), floatArrayOf(.25f, .75f, .31f, .71f, .35f, .66f, .39f, .60f, .50f), floatArrayOf(.39f, .78f, .79f, .40f, .74f, .44f, .68f, .50f, .58f), 1.14f, 1.72f)
    18 -> LegacyComboSpec(floatArrayOf(-64f, 60f, -50f, 53f, -40f, 43f, -29f, 8f), floatArrayOf(.24f, .76f, .30f, .72f, .34f, .67f, .40f, .50f), floatArrayOf(.38f, .79f, .80f, .39f, .75f, .43f, .67f, .58f), 1.16f, 1.78f, 0xFFA7E9DFL)
    19 -> LegacyComboSpec(floatArrayOf(-66f, 62f, -53f, 56f, -43f, 46f, -33f, 35f, -20f, 7f), floatArrayOf(.23f, .77f, .29f, .73f, .33f, .68f, .37f, .63f, .42f, .50f), floatArrayOf(.37f, .80f, .81f, .38f, .76f, .42f, .71f, .47f, .65f, .58f), 1.18f, 1.84f)
    20 -> LegacyComboSpec(floatArrayOf(-68f, 64f, -56f, 59f, -47f, 50f, -38f, 41f, -29f, 32f, -18f, 8f), floatArrayOf(.22f, .78f, .28f, .74f, .32f, .69f, .36f, .64f, .40f, .59f, .44f, .50f), floatArrayOf(.36f, .81f, .82f, .37f, .77f, .41f, .72f, .46f, .67f, .51f, .62f, .58f), 1.20f, 1.92f)
    else -> error("No legacy combo spec for tier $tier")
}

private fun legacyWarriorAssetHeightToWidth(assetId: Int): Float = when (assetId) {
    R.drawable.vfx_warrior_01 -> 448f / 1024f
    R.drawable.vfx_warrior_02 -> 768f / 512f
    R.drawable.vfx_warrior_03 -> 512f / 768f
    R.drawable.vfx8_warrior_slash_primary -> 930f / 1692f
    R.drawable.vfx8_warrior_slash_contact -> 1f
    R.drawable.vfx8_warrior_slash_echo -> 1024f / 1536f
    R.drawable.vfx8_warrior_heavy_primary -> 1536f / 1024f
    R.drawable.vfx8_warrior_heavy_anticipation -> 1024f / 1536f
    R.drawable.vfx8_warrior_charge_primary -> 1009f / 1558f
    R.drawable.vfx8_warrior_earth_primary -> 1024f / 1536f
    else -> 1f
}

internal fun warriorVfx8AspectRatio(assetId: Int): Float? = when (assetId) {
    R.drawable.vfx8_warrior_slash_primary,
    R.drawable.vfx8_warrior_slash_contact,
    R.drawable.vfx8_warrior_slash_echo,
    R.drawable.vfx8_warrior_heavy_primary,
    R.drawable.vfx8_warrior_heavy_anticipation,
    R.drawable.vfx8_warrior_charge_primary,
    R.drawable.vfx8_warrior_earth_primary,
    -> legacyWarriorAssetHeightToWidth(assetId)
    else -> null
}

/**
 * Runtime preload contract for the forty slash/flurry skills that still enter through the
 * legacy warrior branch. The frame planner now emits VFX8 art, so those resources must be
 * loaded alongside the historical warrior array before Canvas resolves frame asset ids.
 */
internal fun legacyWarriorRuntimeAssetIds(): IntArray = (
    LEGACY_WARRIOR_FLURRY_RESOURCES.toList() + listOf(
        R.drawable.vfx8_warrior_slash_primary,
        R.drawable.vfx8_warrior_slash_contact,
        R.drawable.vfx8_warrior_slash_echo,
    )
).distinct().toIntArray()

/** Reviewed warrior support art is square; preserve that source geometry instead of role stretch. */
internal fun warriorReviewedAssetAspectRatio(assetId: Int): Float? = warriorVfx8AspectRatio(assetId) ?: when (assetId) {
    R.drawable.vfx6_warrior_heavy_contact,
    R.drawable.vfx6_warrior_heavy_debris,
    R.drawable.vfx6_warrior_heavy_echo,
    R.drawable.vfx7_warrior_charge_anticipation,
    R.drawable.vfx7_warrior_charge_contact,
    R.drawable.vfx7_warrior_charge_debris,
    R.drawable.vfx7_warrior_charge_echo,
    R.drawable.vfx7_warrior_earth_anticipation,
    R.drawable.vfx6_warrior_earth_contact,
    R.drawable.vfx6_warrior_earth_debris,
    R.drawable.vfx2_warrior_earth_residual,
    R.drawable.vfx2_warrior_earth_residual,
    -> 1f
    else -> null
}

internal data class WarriorStrikeAnchor(val x: Float, val y: Float)

/**
 * Reviewed optical anchor inside each cohesive VFX8 asset.
 *
 * Slash and charge are long directional bodies, so pinning their far tip to the target makes the
 * whole image look displaced to the opposite side. Those two use the measured alpha-mass center.
 * Heavy and earth still use their authored ground-contact coordinate because their lower edge is
 * the semantic hit point.
 */
internal fun warriorStrikeAnchor(assetId: Int): WarriorStrikeAnchor = when (assetId) {
    R.drawable.vfx8_warrior_slash_primary -> WarriorStrikeAnchor(.494f, .571f)
    R.drawable.vfx8_warrior_heavy_primary -> WarriorStrikeAnchor(.50f, .88f)
    R.drawable.vfx8_warrior_heavy_anticipation -> WarriorStrikeAnchor(.50f, .69f)
    R.drawable.vfx8_warrior_charge_primary -> WarriorStrikeAnchor(.465f, .527f)
    R.drawable.vfx8_warrior_earth_primary -> WarriorStrikeAnchor(.50f, .70f)
    // Measured luminous convergence point of the U-shaped ground blast. Pinning its bitmap
    // center to the hit point left the actual collision below the viewport.
    R.drawable.vfx6_warrior_earth_contact -> WarriorStrikeAnchor(.512f, .713f)
    else -> WarriorStrikeAnchor(.50f, .50f)
}

/** Measured visible collision point used by regression tests after authored placement. */
private fun warriorVisualImpactAnchor(assetId: Int): WarriorStrikeAnchor = when (assetId) {
    R.drawable.vfx8_warrior_earth_primary -> WarriorStrikeAnchor(.493f, .795f)
    else -> warriorStrikeAnchor(assetId)
}

/** Maps a placed semantic strike target to the asset's measured visible collision point. */
private fun warriorVisibleImpactFromStrikeTarget(
    assetId: Int,
    targetXFraction: Float,
    targetYFraction: Float,
    widthFraction: Float,
    heightFraction: Float,
    rotationDegrees: Float,
    mirror: Float,
    viewportWidth: Float,
    viewportHeight: Float,
): Offset {
    val strike = warriorStrikeAnchor(assetId)
    val visible = warriorVisualImpactAnchor(assetId)
    fun mirroredX(anchor: WarriorStrikeAnchor) = if (mirror < 0f) 1f - anchor.x else anchor.x
    val dx = (mirroredX(visible) - mirroredX(strike)) * widthFraction * viewportWidth
    val dy = (visible.y - strike.y) * heightFraction * viewportHeight
    val radians = Math.toRadians(rotationDegrees.toDouble())
    val rotatedDx = dx * cos(radians).toFloat() - dy * sin(radians).toFloat()
    val rotatedDy = dx * sin(radians).toFloat() + dy * cos(radians).toFloat()
    return Offset(
        x = targetXFraction + rotatedDx / viewportWidth,
        y = targetYFraction + rotatedDy / viewportHeight,
    )
}

/** Places an asset so its visible strike coordinate, rather than its bitmap center, hits target. */
internal fun warriorAnchoredCenter(
    assetId: Int,
    targetXFraction: Float,
    targetYFraction: Float,
    widthFraction: Float,
    heightFraction: Float,
    rotationDegrees: Float,
    mirror: Float,
    viewportWidth: Float,
    viewportHeight: Float,
): Offset {
    val anchor = warriorStrikeAnchor(assetId)
    val mirroredX = if (mirror < 0f) 1f - anchor.x else anchor.x
    val dx = (mirroredX - .5f) * widthFraction * viewportWidth
    val dy = (anchor.y - .5f) * heightFraction * viewportHeight
    val radians = Math.toRadians(rotationDegrees.toDouble())
    val rotatedDx = dx * cos(radians).toFloat() - dy * sin(radians).toFloat()
    val rotatedDy = dx * sin(radians).toFloat() + dy * cos(radians).toFloat()
    return Offset(
        x = targetXFraction - rotatedDx / viewportWidth,
        y = targetYFraction - rotatedDy / viewportHeight,
    )
}

/** Resolves the visible hit point back from a rendered frame for parity and regression gates. */
internal fun warriorResolvedStrikePoint(
    frame: AuthoredLayerFrame,
    viewportWidth: Float,
    viewportHeight: Float,
): Offset {
    val anchor = warriorVisualImpactAnchor(frame.assetId)
    val mirroredX = if (frame.mirror < 0f) 1f - anchor.x else anchor.x
    val dx = (mirroredX - .5f) * frame.widthFraction * viewportWidth
    val dy = (anchor.y - .5f) * frame.heightFraction * viewportHeight
    val radians = Math.toRadians(frame.rotationDegrees.toDouble())
    val rotatedDx = dx * cos(radians).toFloat() - dy * sin(radians).toFloat()
    val rotatedDy = dx * sin(radians).toFloat() + dy * cos(radians).toFloat()
    return Offset(
        x = frame.xFraction + rotatedDx / viewportWidth,
        y = frame.yFraction + rotatedDy / viewportHeight,
    )
}

private fun legacyHeightFraction(
    assetId: Int,
    widthFraction: Float,
    viewportWidth: Float,
    viewportHeight: Float,
): Float = widthFraction * legacyWarriorAssetHeightToWidth(assetId) * viewportWidth / viewportHeight

/** Restores the approved legacy slash recipe verbatim: raw size, center and rotation. */
private fun fitLegacyWarriorFrame(
    frame: AuthoredLayerFrame,
    viewportWidth: Float,
    viewportHeight: Float,
): AuthoredLayerFrame {
    require(viewportWidth > 0f && viewportHeight > 0f)
    return frame
}

/**
 * Cohesive planner for the forty slash skills. Every visible layer shares an explicit strike
 * coordinate, while stage growth changes scale, tail length and support count instead of art era.
 */
internal fun legacyWarriorFramePlan(
    definition: SkillDefinition,
    elapsedMillis: Int,
    reducedMotion: Boolean,
    viewportWidth: Float = 360f,
    viewportHeight: Float = 180f,
): List<AuthoredLayerFrame> {
    require(viewportWidth > 0f && viewportHeight > 0f)
    val descriptor = legacyWarriorDescriptor(definition) ?: return emptyList()
    if (elapsedMillis !in 0 until SKILL_VFX_END_MILLIS) return emptyList()
    val tier = (definition.unlockLevel / 5).coerceIn(1, 20)
    val growthStage = skillVfxGrammar(definition).growthStage
    val stageIndex = growthStage.ordinal
    val frames = mutableListOf<AuthoredLayerFrame>()

    fun append(
        role: AuthoredLayerRole,
        assetId: Int,
        hitIndex: Int,
        targetX: Float,
        targetY: Float,
        width: Float,
        rotation: Float,
        mirror: Float,
        start: Int,
        peak: Int,
        hold: Int,
        end: Int,
        peakAlpha: Float,
    ) {
        if (elapsedMillis !in start until minOf(end, SKILL_VFX_END_MILLIS)) return
        val alpha = if (reducedMotion) {
            peakAlpha * .68f
        } else {
            authoredEnvelope(elapsedMillis, start, peak, hold, end) * peakAlpha
        }
        if (alpha <= 0f) return
        val height = legacyHeightFraction(assetId, width, viewportWidth, viewportHeight)
        val center = warriorAnchoredCenter(
            assetId = assetId,
            targetXFraction = targetX,
            targetYFraction = targetY,
            widthFraction = width,
            heightFraction = height,
            rotationDegrees = rotation,
            mirror = mirror,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
        )
        frames += AuthoredLayerFrame(
            role = role,
            assetId = assetId,
            instance = 0,
            hitIndex = hitIndex,
            xFraction = center.x,
            yFraction = center.y,
            widthFraction = width,
            heightFraction = height,
            rotationDegrees = rotation,
            alpha = alpha.coerceIn(0f, 1f),
            reveal = 1f,
            mirror = mirror,
            startMillis = start.coerceAtLeast(0),
            endMillis = minOf(end, SKILL_VFX_END_MILLIS),
        )
    }

    if (descriptor.branch == LegacyWarriorBranch.STEEL_SLASH ||
        descriptor.branch == LegacyWarriorBranch.SINGLE_SLASH
    ) {
        val hit = definition.hitTimingsMillis.single()
        val spec = if (tier == 1) null else legacySingleSlashSpec(tier)
        val targetX = .50f
        val targetY = spec?.y ?: .59f
        // The source blade rises from lower-left to upper-right. Mirroring it produces the
        // requested upper-left to lower-right cut, consistently across all twenty single-slash
        // tiers (including Reduced Motion's static pose).
        val mirror = -1f
        val primaryStart = if (reducedMotion) hit - 20 else hit - intArrayOf(120, 150, 180)[stageIndex]
        val primaryEnd = hit + intArrayOf(190, 240, 300)[stageIndex]
        val authoredRotation = (spec?.rotationStart ?: -4f) +
            (spec?.rotationTravel ?: 0f) * .55f
        // Keep tier flavor small enough that no single slash flips back into a horizontal/rising
        // line. The mirrored source art owns the strong descending diagonal.
        val rotation = if (reducedMotion) {
            0f
        } else {
            (authoredRotation * .20f).coerceIn(-8f, 8f)
        }
        // Travel from the upper-left lane into the target so the animation direction agrees with
        // the descending bitmap axis. Reduced Motion keeps the same final diagonal but no travel.
        val sweep = if (reducedMotion) {
            1f
        } else {
            modularEaseOut(modularFraction(elapsedMillis, primaryStart, hit))
        }
        val primaryTargetX = targetX - .18f * (1f - sweep)
        val primaryTargetY = targetY - .20f * (1f - sweep)
        append(
            AuthoredLayerRole.PRIMARY,
            R.drawable.vfx8_warrior_slash_primary,
            0,
            primaryTargetX,
            primaryTargetY,
            if (reducedMotion) .62f else floatArrayOf(.72f, .82f, .92f)[stageIndex],
            rotation,
            mirror,
            primaryStart,
            hit,
            hit + 42,
            primaryEnd,
            floatArrayOf(.88f, .94f, 1f)[stageIndex],
        )
        val contactEnd = hit + intArrayOf(82, 96, 112)[stageIndex]
        append(
            AuthoredLayerRole.CONTACT,
            R.drawable.vfx8_warrior_slash_contact,
            0,
            targetX,
            targetY,
            floatArrayOf(.22f, .27f, .32f)[stageIndex],
            0f,
            1f,
            hit - 10,
            hit,
            hit + 24,
            contactEnd,
            floatArrayOf(.88f, .94f, 1f)[stageIndex],
        )
        if (!reducedMotion && stageIndex >= 1) {
            append(
                AuthoredLayerRole.FINISHER_ECHO,
                R.drawable.vfx8_warrior_slash_echo,
                0,
                targetX,
                targetY,
                floatArrayOf(.50f, .58f, .68f)[stageIndex],
                rotation,
                mirror,
                hit + 48,
                hit + 92,
                hit + 145,
                hit + intArrayOf(210, 260, 320)[stageIndex],
                floatArrayOf(.22f, .30f, .38f)[stageIndex],
            )
        }
        return frames
    }

    return definition.hitTimingsMillis.indices.mapNotNull { hitIndex ->
        val isContinuous = descriptor.branch == LegacyWarriorBranch.CONTINUOUS_SLASH
        val state = if (isContinuous) {
            continuousSlashVfxFrame(elapsedMillis, hitIndex, reducedMotion)
        } else {
            val starter = starterWarriorComboFrame(
                elapsedMillis,
                hitIndex,
                definition.hitTimingsMillis,
                reducedMotion,
                stronger = true,
            )
            ContinuousSlashVfxFrame(
                starter.visible,
                starter.alpha,
                starter.scale,
                starter.xFraction,
                starter.yFraction,
                starter.rotationDegrees,
                starter.direction,
            )
        }
        if (!state.visible) return@mapNotNull null
        val choreography = semanticHitChoreography(definition, hitIndex)
        // The starter's middle recipe selected vfx_warrior_03: a broad crescent whose stroke was
        // several times thicker than the first and third cuts. Keep the approved long streak for
        // all three starter hits; rotation, mirror, position, and scale still create the rhythm.
        val asset = if (isContinuous) {
            R.drawable.vfx_warrior_01
        } else {
            legacyWarriorFlurryResource(choreography.recipe.index)
        }
        val timing = definition.hitTimingsMillis[hitIndex]
        val isFinal = hitIndex == definition.hitTimingsMillis.lastIndex
        val combo = if (isContinuous) null else legacyComboSpec(tier)
        val stage = hitIndex.mod(combo?.rotations?.size ?: 1)
        val width = if (isContinuous) {
            state.scale
        } else {
            state.scale * if (isFinal) combo!!.finalScale else combo!!.normalScale
        }
        val x = if (isContinuous) state.xFraction else combo!!.xs[stage]
        val y = if (isContinuous) state.yFraction else combo!!.ys[stage]
        val rotation = if (isContinuous) state.rotationDegrees else combo!!.rotations[stage]
        val start = if (isContinuous) {
            if (reducedMotion) timing else intArrayOf(55, 225, 435)[hitIndex.coerceIn(0, 2)]
        } else if (reducedMotion) {
            timing
        } else {
            (timing - 115).coerceAtLeast(0)
        }
        val end = if (isContinuous) {
            if (reducedMotion) {
                timing + if (isFinal) 180 else 90
            } else {
                intArrayOf(245, 455, 780)[hitIndex.coerceIn(0, 2)]
            }
        } else if (reducedMotion) {
            minOf(timing + if (isFinal) 180 else 90, timing + if (isFinal) 300 else 125, SKILL_VFX_END_MILLIS)
        } else {
            if (isFinal) {
                minOf(timing + 300, SKILL_VFX_END_MILLIS)
            } else {
                minOf(timing + 125, definition.hitTimingsMillis[hitIndex + 1] - 18)
            }
        }
        val requestedTint = when {
            isCrimsonCombo(definition) -> 0xFFFF342EL
            isBloodFrenzy(definition) -> 0xFFD5102FL
            else -> combo?.tintArgb
        }
        val frame = AuthoredLayerFrame(
            role = AuthoredLayerRole.PRIMARY,
            assetId = asset,
            instance = 0,
            hitIndex = hitIndex,
            xFraction = x,
            yFraction = y,
            widthFraction = width,
            heightFraction = legacyHeightFraction(asset, width, viewportWidth, viewportHeight),
            rotationDegrees = rotation,
            alpha = state.alpha,
            reveal = 1f,
            mirror = state.direction,
            startMillis = start,
            endMillis = end,
            drawMode = if (requestedTint == null) {
                AuthoredAssetDrawMode.LEGACY_UNTINTED
            } else {
                AuthoredAssetDrawMode.LEGACY_TINTED
            },
            tintArgb = requestedTint,
        )
        if (isContinuous) frame else fitLegacyWarriorFrame(frame, viewportWidth, viewportHeight)
    }.let { restored -> if (reducedMotion) restored.takeLast(2) else restored }
}

internal fun authoredCompositionRoles(
    definition: SkillDefinition,
    isFinal: Boolean,
): List<AuthoredLayerRole> {
    if (!isFinal) return listOf(AuthoredLayerRole.PRIMARY, AuthoredLayerRole.CONTACT)
    val band = classVfxSignature(definition).growthBand
    if (warriorNonSlashColumn(definition) != null) {
        return buildList {
            add(AuthoredLayerRole.SECONDARY)
            add(AuthoredLayerRole.PRIMARY)
            add(AuthoredLayerRole.CONTACT)
            add(AuthoredLayerRole.DEBRIS)
            if (band >= 1) add(AuthoredLayerRole.RESIDUAL)
            // One family residual owns the tail; duplicating it as a ring made a pasted-on halo.
        }
    }
    return buildList {
        add(AuthoredLayerRole.SECONDARY)
        add(AuthoredLayerRole.PRIMARY)
        add(AuthoredLayerRole.CONTACT)
        add(AuthoredLayerRole.DEBRIS)
        if (band >= 1) add(AuthoredLayerRole.RESIDUAL)
        // A candidate's body and one directional tail own the finish. The former generic ring
        // duplicated the contact silhouette and made otherwise distinct classes converge on the
        // same circular afterimage.
        if (band >= 4) add(AuthoredLayerRole.FINISHER_ECHO)
    }
}

internal data class PhaseCompositionBudget(
    val concurrentLimit: Int,
    val minimumPeak: Int,
    val mandatoryRoles: List<AuthoredLayerRole>,
)

/** Low/mid/high composition contract shared by runtime, export, and the parity gates. */
internal fun phaseCompositionBudget(definition: SkillDefinition): PhaseCompositionBudget {
    val stage = skillVfxGrammar(definition).growthStage
    if (warriorNonSlashColumn(definition) != null) {
        return when (stage) {
            SkillVfxGrowthStage.LOW -> PhaseCompositionBudget(
                concurrentLimit = 4,
                minimumPeak = 3,
                mandatoryRoles = listOf(
                    AuthoredLayerRole.SECONDARY,
                    AuthoredLayerRole.PRIMARY,
                    AuthoredLayerRole.CONTACT,
                    AuthoredLayerRole.DEBRIS,
                ),
            )
            SkillVfxGrowthStage.MID -> PhaseCompositionBudget(
                concurrentLimit = 4,
                minimumPeak = 4,
                mandatoryRoles = listOf(
                    AuthoredLayerRole.SECONDARY,
                    AuthoredLayerRole.PRIMARY,
                    AuthoredLayerRole.CONTACT,
                    AuthoredLayerRole.DEBRIS,
                    AuthoredLayerRole.RESIDUAL,
                ),
            )
            SkillVfxGrowthStage.HIGH -> PhaseCompositionBudget(
                concurrentLimit = 5,
                minimumPeak = 4,
                mandatoryRoles = listOf(
                    AuthoredLayerRole.SECONDARY,
                    AuthoredLayerRole.PRIMARY,
                    AuthoredLayerRole.CONTACT,
                    AuthoredLayerRole.DEBRIS,
                    AuthoredLayerRole.RESIDUAL,
                ),
            )
        }
    }
    return when (stage) {
        SkillVfxGrowthStage.LOW -> PhaseCompositionBudget(
            concurrentLimit = 4,
            minimumPeak = 3,
            mandatoryRoles = listOf(
                AuthoredLayerRole.SECONDARY,
                AuthoredLayerRole.PRIMARY,
                AuthoredLayerRole.CONTACT,
                AuthoredLayerRole.DEBRIS,
            ),
        )
        SkillVfxGrowthStage.MID -> PhaseCompositionBudget(
            concurrentLimit = 4,
            minimumPeak = 3,
            mandatoryRoles = listOf(
                AuthoredLayerRole.SECONDARY,
                AuthoredLayerRole.PRIMARY,
                AuthoredLayerRole.CONTACT,
                AuthoredLayerRole.DEBRIS,
                AuthoredLayerRole.RESIDUAL,
            ),
        )
        SkillVfxGrowthStage.HIGH -> PhaseCompositionBudget(
            concurrentLimit = 4,
            minimumPeak = 3,
            mandatoryRoles = listOf(
                AuthoredLayerRole.SECONDARY,
                AuthoredLayerRole.PRIMARY,
                AuthoredLayerRole.CONTACT,
                AuthoredLayerRole.DEBRIS,
                AuthoredLayerRole.RESIDUAL,
                AuthoredLayerRole.FINISHER_ECHO,
            ),
        )
    }
}

private fun authoredEnvelope(
    local: Int,
    start: Int,
    enterEnd: Int,
    fadeStart: Int,
    end: Int,
): Float {
    if (local !in start until end) return 0f
    val enter = modularEaseOut(modularFraction(local, start, enterEnd.coerceAtLeast(start + 1)))
    val fade = 1f - modularFraction(local, fadeStart.coerceAtMost(end - 1), end)
    return minOf(enter, fade).coerceIn(0f, 1f)
}

/**
 * Optical timing for the warrior's three authored non-slash columns.
 *
 * Slash recipes establish their body much earlier and keep independent slash tails alive for
 * roughly one second. The shared authored envelope used to make every non-slash plane enter late
 * and fade together. These role-specific windows keep the large full-bleed placement while
 * separating anticipation, body, contact, debris, and finish in time.
 */
internal data class WarriorNonSlashRoleTimeline(
    val startMillis: Int,
    val peakMillis: Int,
    val holdEndMillis: Int,
    val endMillis: Int,
    val peakAlpha: Float,
)

private data class NonWarriorCandidateRhythm(
    val anticipationMillis: Int,
    val contactHoldMillis: Int,
    val tailMillis: Int,
)

/**
 * Candidate-owned motion signatures for the other five classes.
 *
 * The old path/tier rotation gave every candidate the same four timing shapes. These authored
 * landmarks keep damage timing untouched while separating anticipation weight, hit-stop, and
 * release. Growth scales density and tail length without changing the candidate's identity.
 */
private fun nonWarriorCandidateRhythm(definition: SkillDefinition): NonWarriorCandidateRhythm? {
    val candidate = definition.candidate.coerceIn(0, 4)
    fun rhythm(anticipation: IntArray, hold: IntArray, tail: IntArray) =
        NonWarriorCandidateRhythm(anticipation[candidate], hold[candidate], tail[candidate])
    return when (definition.heroClass) {
        HeroClass.WARRIOR -> null
        HeroClass.ROGUE -> rhythm(
            intArrayOf(130, 300, 210, 320, 420),
            intArrayOf(25, 50, 100, 160, 70),
            intArrayOf(250, 420, 520, 560, 340),
        )
        HeroClass.RANGER -> rhythm(
            intArrayOf(360, 120, 220, 300, 380),
            intArrayOf(35, 25, 55, 90, 140),
            intArrayOf(280, 260, 380, 400, 600),
        )
        HeroClass.MAGE -> rhythm(
            intArrayOf(210, 310, 140, 390, 440),
            intArrayOf(100, 160, 20, 170, 200),
            intArrayOf(540, 580, 220, 620, 630),
        )
        HeroClass.CLERIC -> rhythm(
            intArrayOf(280, 360, 330, 220, 410),
            intArrayOf(160, 120, 160, 110, 170),
            intArrayOf(500, 500, 600, 580, 650),
        )
        HeroClass.PALADIN -> rhythm(
            intArrayOf(200, 250, 370, 270, 440),
            intArrayOf(60, 90, 130, 130, 170),
            intArrayOf(320, 380, 520, 580, 630),
        )
    }
}

internal fun nonWarriorCandidateRoleTimeline(
    definition: SkillDefinition,
    role: AuthoredLayerRole,
): WarriorNonSlashRoleTimeline? {
    val base = nonWarriorCandidateRhythm(definition) ?: return null
    val stageIndex = skillVfxGrammar(definition).growthStage.ordinal
    val anticipation = (base.anticipationMillis * floatArrayOf(.82f, .92f, 1f)[stageIndex]).roundToInt()
    val hold = (base.contactHoldMillis * floatArrayOf(.86f, .94f, 1f)[stageIndex]).roundToInt()
    val tail = (base.tailMillis * floatArrayOf(.74f, .87f, 1f)[stageIndex]).roundToInt()
    // Preserve candidate-owned hit-stop on the CONTACT plane itself. The old 42 ms cap and
    // stage-only end time collapsed every Cleric/Paladin candidate into the same impact beat,
    // even though their body and tail windows differed. Compress the authored hold into a
    // practical 24..64 ms optical pause, then add a tiny candidate offset so equal source holds
    // (Cleric 0/2, Paladin 2/3) still read as different attacks.
    val contactHold = (
        22 + (hold * .16f).roundToInt() + definition.candidate.coerceIn(0, 4) * 2
    ).coerceIn(24, 64)
    val contactEnd = maxOf(
        contactHold + 30,
        intArrayOf(76, 86, 96)[stageIndex] + definition.candidate.coerceIn(0, 4) * 3,
    )
    val primaryEnd = minOf(tail, hold + intArrayOf(120, 145, 170)[stageIndex])
    val debrisEnd = minOf(tail, hold + intArrayOf(155, 190, 225)[stageIndex])
    val residualStart = maxOf(80, hold + 42)
    val echoStart = maxOf(105, hold + 68)
    return when (role) {
        AuthoredLayerRole.SECONDARY -> WarriorNonSlashRoleTimeline(
            -anticipation,
            -maxOf(44, anticipation / 2),
            -maxOf(24, anticipation / 5),
            -8,
            floatArrayOf(.28f, .34f, .40f)[stageIndex],
        )
        AuthoredLayerRole.PRIMARY -> WarriorNonSlashRoleTimeline(
            -minOf(anticipation, 320),
            -12,
            hold,
            primaryEnd,
            floatArrayOf(.82f, .90f, .98f)[stageIndex],
        )
        AuthoredLayerRole.CONTACT -> WarriorNonSlashRoleTimeline(
            -10,
            0,
            contactHold,
            contactEnd,
            floatArrayOf(.88f, .94f, 1f)[stageIndex],
        )
        AuthoredLayerRole.DEBRIS -> WarriorNonSlashRoleTimeline(
            12,
            54,
            minOf(debrisEnd - 1, hold + 90),
            debrisEnd,
            floatArrayOf(.34f, .42f, .50f)[stageIndex],
        )
        AuthoredLayerRole.RESIDUAL -> if (stageIndex == 0 || residualStart >= tail) null else {
            WarriorNonSlashRoleTimeline(
                residualStart,
                minOf(residualStart + 52, tail - 1),
                maxOf(residualStart + 53, tail - 78),
                tail,
                floatArrayOf(.22f, .29f, .36f)[stageIndex],
            )
        }
        AuthoredLayerRole.FINISHER_RING -> null
        AuthoredLayerRole.FINISHER_ECHO -> if (stageIndex < 2 || echoStart >= tail) null else {
            WarriorNonSlashRoleTimeline(
                echoStart,
                minOf(echoStart + 48, tail - 1),
                maxOf(echoStart + 49, tail - 64),
                tail,
                .30f,
            )
        }
        AuthoredLayerRole.WARRIOR_ACCENT -> null
    }
}

private fun reviewedRoleTimeline(
    definition: SkillDefinition,
    role: AuthoredLayerRole,
): WarriorNonSlashRoleTimeline? =
    warriorNonSlashRoleTimeline(definition, role) ?: nonWarriorCandidateRoleTimeline(definition, role)

internal fun warriorNonSlashRoleTimeline(
    definition: SkillDefinition,
    role: AuthoredLayerRole,
): WarriorNonSlashRoleTimeline? {
    val column = warriorNonSlashColumn(definition) ?: return null
    val stage = skillVfxGrammar(definition).growthStage
    val stageIndex = stage.ordinal
    val alpha = floatArrayOf(.90f, .95f, 1f)[stageIndex]
    return when (column) {
        WarriorNonSlashColumn.HEAVY -> when (role) {
            AuthoredLayerRole.SECONDARY -> WarriorNonSlashRoleTimeline(
                intArrayOf(-300, -350, -400)[stageIndex],
                intArrayOf(-210, -235, -260)[stageIndex],
                intArrayOf(-150, -165, -180)[stageIndex],
                intArrayOf(-70, -78, -86)[stageIndex],
                floatArrayOf(.40f, .46f, .52f)[stageIndex],
            )
            AuthoredLayerRole.PRIMARY -> WarriorNonSlashRoleTimeline(
                intArrayOf(-180, -210, -240)[stageIndex], 0,
                intArrayOf(220, 300, 380)[stageIndex],
                intArrayOf(650, 740, 820)[stageIndex],
                floatArrayOf(.88f, .94f, 1f)[stageIndex],
            )
            AuthoredLayerRole.CONTACT -> WarriorNonSlashRoleTimeline(
                -18, 0, 32, intArrayOf(130, 150, 170)[stageIndex], alpha,
            )
            AuthoredLayerRole.DEBRIS -> WarriorNonSlashRoleTimeline(
                12, 70, intArrayOf(190, 260, 340)[stageIndex],
                intArrayOf(360, 420, 500)[stageIndex],
                floatArrayOf(.50f, .58f, .66f)[stageIndex],
            )
            AuthoredLayerRole.RESIDUAL -> WarriorNonSlashRoleTimeline(
                110, 180, intArrayOf(300, 390, 520)[stageIndex],
                intArrayOf(450, 560, 690)[stageIndex],
                floatArrayOf(.30f, .38f, .46f)[stageIndex],
            )
            AuthoredLayerRole.FINISHER_RING -> WarriorNonSlashRoleTimeline(
                120, 210, intArrayOf(260, 340, 450)[stageIndex],
                intArrayOf(380, 520, 690)[stageIndex],
                floatArrayOf(.44f, .52f, .64f)[stageIndex],
            )
            AuthoredLayerRole.FINISHER_ECHO -> WarriorNonSlashRoleTimeline(240, 320, 430, 560, .44f)
            AuthoredLayerRole.WARRIOR_ACCENT -> null
        }
        WarriorNonSlashColumn.CHARGE -> when (role) {
            AuthoredLayerRole.SECONDARY -> WarriorNonSlashRoleTimeline(
                intArrayOf(-220, -250, -280)[stageIndex],
                intArrayOf(-140, -160, -180)[stageIndex],
                intArrayOf(-60, -70, -80)[stageIndex],
                intArrayOf(15, 20, 25)[stageIndex],
                floatArrayOf(.34f, .40f, .46f)[stageIndex],
            )
            AuthoredLayerRole.PRIMARY -> WarriorNonSlashRoleTimeline(
                intArrayOf(-200, -230, -260)[stageIndex], 0,
                intArrayOf(35, 45, 55)[stageIndex],
                intArrayOf(190, 230, 270)[stageIndex],
                floatArrayOf(.88f, .94f, 1f)[stageIndex],
            )
            AuthoredLayerRole.CONTACT -> WarriorNonSlashRoleTimeline(
                -10, 0, 20, intArrayOf(90, 110, 130)[stageIndex], alpha,
            )
            AuthoredLayerRole.DEBRIS -> WarriorNonSlashRoleTimeline(
                -20, 45, intArrayOf(150, 190, 240)[stageIndex],
                intArrayOf(360, 460, 560)[stageIndex],
                floatArrayOf(.48f, .56f, .64f)[stageIndex],
            )
            AuthoredLayerRole.RESIDUAL -> WarriorNonSlashRoleTimeline(
                40, 100, intArrayOf(200, 280, 360)[stageIndex],
                intArrayOf(380, 500, 620)[stageIndex],
                floatArrayOf(.32f, .40f, .48f)[stageIndex],
            )
            AuthoredLayerRole.FINISHER_RING -> WarriorNonSlashRoleTimeline(
                90, 150, intArrayOf(250, 330, 420)[stageIndex],
                intArrayOf(360, 500, 620)[stageIndex],
                floatArrayOf(.42f, .52f, .62f)[stageIndex],
            )
            AuthoredLayerRole.FINISHER_ECHO -> WarriorNonSlashRoleTimeline(180, 250, 360, 500, .42f)
            AuthoredLayerRole.WARRIOR_ACCENT -> null
        }
        WarriorNonSlashColumn.EARTH -> when (role) {
            AuthoredLayerRole.SECONDARY -> WarriorNonSlashRoleTimeline(
                intArrayOf(-180, -210, -240)[stageIndex],
                intArrayOf(-70, -80, -90)[stageIndex],
                intArrayOf(-20, -25, -30)[stageIndex],
                intArrayOf(60, 65, 70)[stageIndex],
                floatArrayOf(.36f, .42f, .48f)[stageIndex],
            )
            AuthoredLayerRole.PRIMARY -> WarriorNonSlashRoleTimeline(
                intArrayOf(-200, -230, -260)[stageIndex], 0,
                intArrayOf(420, 500, 600)[stageIndex],
                intArrayOf(720, 780, 820)[stageIndex],
                floatArrayOf(.88f, .94f, 1f)[stageIndex],
            )
            AuthoredLayerRole.CONTACT -> WarriorNonSlashRoleTimeline(
                -16, 0, 36, intArrayOf(110, 130, 150)[stageIndex], alpha,
            )
            AuthoredLayerRole.DEBRIS -> WarriorNonSlashRoleTimeline(
                10, 70, intArrayOf(140, 200, 280)[stageIndex],
                intArrayOf(260, 340, 420)[stageIndex],
                floatArrayOf(.46f, .55f, .64f)[stageIndex],
            )
            AuthoredLayerRole.RESIDUAL -> WarriorNonSlashRoleTimeline(
                intArrayOf(70, 70, 60)[stageIndex],
                intArrayOf(150, 170, 190)[stageIndex],
                intArrayOf(220, 280, 360)[stageIndex],
                intArrayOf(360, 460, 560)[stageIndex],
                floatArrayOf(.34f, .44f, .54f)[stageIndex],
            )
            AuthoredLayerRole.FINISHER_RING -> WarriorNonSlashRoleTimeline(
                intArrayOf(70, 70, 60)[stageIndex],
                intArrayOf(150, 170, 190)[stageIndex],
                intArrayOf(210, 270, 350)[stageIndex],
                intArrayOf(330, 430, 530)[stageIndex],
                floatArrayOf(.38f, .50f, .62f)[stageIndex],
            )
            AuthoredLayerRole.FINISHER_ECHO -> WarriorNonSlashRoleTimeline(180, 250, 330, 500, .42f)
            AuthoredLayerRole.WARRIOR_ACCENT -> null
        }
    }
}

private fun warriorNonSlashEnvelope(
    definition: SkillDefinition,
    role: AuthoredLayerRole,
    local: Int,
): Float? = warriorNonSlashRoleTimeline(definition, role)?.let { timeline ->
    authoredEnvelope(
        local = local,
        start = timeline.startMillis,
        enterEnd = timeline.peakMillis,
        fadeStart = timeline.holdEndMillis,
        end = timeline.endMillis,
    ) * timeline.peakAlpha
}

private fun reviewedRoleEnvelope(
    definition: SkillDefinition,
    role: AuthoredLayerRole,
    local: Int,
    effectiveEndMillis: Int,
): Float? = reviewedRoleTimeline(definition, role)?.let { timeline ->
    // A late final hit can push an authored tail into the global clean-frame ceiling. The
    // placement window is clipped to that ceiling, so the optical envelope must use the same
    // endpoint. Otherwise it remains near peak alpha at 1,239 ms and disappears on the next
    // frame. Reserve a short perceptual fade only when the global/next-hit ceiling shortened the
    // authored timeline; untouched timelines retain their authored hold and release exactly.
    val clipped = effectiveEndMillis < timeline.endMillis
    val fadeStart = if (clipped) {
        minOf(timeline.holdEndMillis, effectiveEndMillis - 72)
    } else {
        timeline.holdEndMillis
    }
    authoredEnvelope(
        local = local,
        start = timeline.startMillis,
        enterEnd = timeline.peakMillis,
        fadeStart = fadeStart,
        end = effectiveEndMillis,
    ) * timeline.peakAlpha
}

private fun authoredRoleAlpha(
    definition: SkillDefinition,
    roleGrammar: SkillVfxRoleDescriptor,
    alpha: Float,
): Float = if (warriorNonSlashSignature(definition) != null) {
    // The role timeline already owns the reviewed optical peak. Per-skill identity may vary the
    // asset and path, but must not make one level unexpectedly translucent.
    alpha
} else {
    alpha * roleGrammar.alphaMultiplier
}

private fun reducedAuthoredWindow(role: AuthoredLayerRole, isFinal: Boolean): IntRange? = when (role) {
    AuthoredLayerRole.SECONDARY,
    AuthoredLayerRole.WARRIOR_ACCENT,
    -> if (isFinal) -160 until -80 else null
    AuthoredLayerRole.PRIMARY -> if (isFinal) -80 until 0 else -65 until 0
    AuthoredLayerRole.CONTACT -> 0 until if (isFinal) 80 else 58
    AuthoredLayerRole.DEBRIS -> if (isFinal) 80 until 150 else null
    AuthoredLayerRole.RESIDUAL -> if (isFinal) 150 until 235 else null
    AuthoredLayerRole.FINISHER_RING -> if (isFinal) 235 until 325 else null
    AuthoredLayerRole.FINISHER_ECHO -> if (isFinal) 325 until 405 else null
}

private fun minimumPeakRoleOrder(stage: SkillVfxGrowthStage): List<AuthoredLayerRole> = when (stage) {
    SkillVfxGrowthStage.LOW -> listOf(
        AuthoredLayerRole.PRIMARY,
        AuthoredLayerRole.SECONDARY,
        AuthoredLayerRole.DEBRIS,
    )
    SkillVfxGrowthStage.MID -> listOf(
        AuthoredLayerRole.PRIMARY,
        AuthoredLayerRole.SECONDARY,
        AuthoredLayerRole.DEBRIS,
        AuthoredLayerRole.RESIDUAL,
    )
    SkillVfxGrowthStage.HIGH -> listOf(
        AuthoredLayerRole.PRIMARY,
        AuthoredLayerRole.SECONDARY,
        AuthoredLayerRole.DEBRIS,
        AuthoredLayerRole.RESIDUAL,
        AuthoredLayerRole.FINISHER_ECHO,
    )
}

/**
 * Pure authoritative planner for every non-legacy class attack.
 *
 * Coordinates and sizes are normalized to the supplied viewport. Android draws these commands
 * directly; browser tooling can export [AUTHORED_FRAME_PSV_HEADER] plus [toPsv] rows. Reduced
 * motion is globally capped after all hit plans are merged, so adjacent multi-hit phases cannot
 * accidentally pile more than two sprites on one frame.
 */
internal fun authoredClassFramePlan(
    definition: SkillDefinition,
    elapsedMillis: Int,
    reducedMotion: Boolean,
    viewportWidth: Float,
    viewportHeight: Float,
): List<AuthoredLayerFrame> {
    require(viewportWidth > 0f && viewportHeight > 0f)
    if (shouldKeepLegacyPrimary(definition)) return emptyList()
    val signature = classVfxSignature(definition)
    val profile = classVfxPathProfile(signature.path)
    val assets = classLayeredAssetSpec(definition)
    val band = signature.growthBand
    val composition = classBandChoreography(definition)
    val grammar = skillVfxGrammar(definition)
    // Reduced Motion keeps the existing contained static pose; only normal motion adopts the
    // intentionally oversized raw recipe requested for the warrior class pass.
    val warriorFullBleed = warriorNonSlashFullBleedScale(definition).takeUnless { reducedMotion }
    val authoredOverflowFraction = warriorFullBleed?.overflowFraction
        ?: grammar.growthStage.overflowFraction
    val warriorAccent = warriorNonSlashSignature(definition)
    val frames = mutableListOf<AuthoredLayerFrame>()

    fun appendFrame(
        role: AuthoredLayerRole,
        assetId: Int,
        instance: Int,
        hitIndex: Int,
        hitMillis: Int,
        local: Int,
        start: Int,
        end: Int,
        xFraction: Float,
        yFraction: Float,
        widthFraction: Float,
        heightToWidth: Float,
        rotationDegrees: Float,
        alpha: Float,
        reveal: Float,
        mirror: Float,
    ) {
        if (local !in start until end || alpha <= 0f) return
        val rawWidth = viewportWidth * widthFraction
        val resolvedHeightToWidth = if (warriorFullBleed != null) {
            warriorReviewedAssetAspectRatio(assetId) ?: heightToWidth
        } else heightToWidth
        val rawHeight = viewportWidth * widthFraction * resolvedHeightToWidth
        val semanticCenter = if (warriorFullBleed != null && warriorReviewedAssetAspectRatio(assetId) != null) {
            warriorAnchoredCenter(
                assetId = assetId,
                targetXFraction = xFraction,
                targetYFraction = yFraction,
                widthFraction = widthFraction,
                heightFraction = rawHeight / viewportHeight,
                rotationDegrees = rotationDegrees,
                mirror = mirror,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
            )
        } else Offset(xFraction, yFraction)
        val placement = if (warriorFullBleed != null) {
            rawAuthoredAssetPlacement(
                centerX = viewportWidth * semanticCenter.x,
                centerY = viewportHeight * semanticCenter.y,
                width = rawWidth,
                height = rawHeight,
                rotationDegrees = rotationDegrees,
            )
        } else {
            safeAuthoredAssetPlacement(
                canvasWidth = viewportWidth,
                canvasHeight = viewportHeight,
                centerX = viewportWidth * xFraction,
                centerY = viewportHeight * yFraction,
                width = rawWidth,
                height = rawHeight,
                rotationDegrees = rotationDegrees,
                overflowFraction = authoredOverflowFraction,
            )
        }
        frames += AuthoredLayerFrame(
            role = role,
            assetId = assetId,
            instance = instance,
            hitIndex = hitIndex,
            xFraction = placement.centerX / viewportWidth,
            yFraction = placement.centerY / viewportHeight,
            widthFraction = placement.width / viewportWidth,
            heightFraction = placement.height / viewportHeight,
            rotationDegrees = rotationDegrees,
            alpha = alpha.coerceIn(0f, 1f),
            reveal = reveal.coerceIn(0f, 1f),
            mirror = mirror,
            startMillis = (hitMillis + start).coerceAtLeast(0),
            endMillis = minOf(hitMillis + end, SKILL_VFX_END_MILLIS),
        )
    }

    definition.hitTimingsMillis.forEachIndexed { hitIndex, timing ->
        val isFinal = hitIndex == definition.hitTimingsMillis.lastIndex
        val local = elapsedMillis - timing
        val budget = phaseCompositionBudget(definition)
        val peakRoleOrder = if (warriorFullBleed != null) {
            when (grammar.growthStage) {
                SkillVfxGrowthStage.LOW -> listOf(
                    AuthoredLayerRole.PRIMARY,
                    AuthoredLayerRole.SECONDARY,
                    AuthoredLayerRole.DEBRIS,
                )
                SkillVfxGrowthStage.MID,
                SkillVfxGrowthStage.HIGH,
                -> listOf(
                    AuthoredLayerRole.PRIMARY,
                    AuthoredLayerRole.SECONDARY,
                    AuthoredLayerRole.DEBRIS,
                    AuthoredLayerRole.RESIDUAL,
                )
            }
        } else minimumPeakRoleOrder(grammar.growthStage)
        val roles = authoredCompositionRoles(definition, isFinal).toMutableSet().apply {
            if (isFinal && !reducedMotion && warriorFullBleed != null) addAll(peakRoleOrder)
        }
        fun descriptor(role: AuthoredLayerRole): SkillVfxRoleDescriptor = grammar.role(role)
        val point = modularHitPoint(definition, hitIndex)
        val targetOffsetScale = if (isFinal) 1f else .72f
        val primaryGrammar = descriptor(AuthoredLayerRole.PRIMARY)
        val plannedCenterX = (if (usesCenterConvergence(definition, hitIndex)) 0.50f else point.xFraction) +
            composition.targetOffset.x * targetOffsetScale + primaryGrammar.offset.x * targetOffsetScale
        val plannedCenterY = (if (usesCenterConvergence(definition, hitIndex)) 0.60f else point.yFraction) +
            composition.targetOffset.y * targetOffsetScale + primaryGrammar.offset.y * targetOffsetScale
        // The warrior's three authored columns use one semantic contact gate per family. Identity
        // variants still change assets and choreography, but may not pull supporting planes away
        // from the body axis.
        val centerX = if (warriorFullBleed != null) {
            .50f
        } else plannedCenterX
        val centerY = if (warriorFullBleed != null) {
            when (definition.candidate) {
                1 -> .63f // descending impact lands on the lower combat lane
                2 -> .60f // forward thrust remains nearly horizontal
                else -> .65f // ground rupture blooms from the floor
            }
        } else plannedCenterY
        val motion = classPrimaryMotion(definition, hitIndex)
        val pathStart = if (warriorFullBleed != null) {
            when (definition.candidate) {
                // A heavy descent must stay on the exact contact column. Route variation used to
                // introduce a lateral offset, making the falling body miss the correct impact.
                1 -> Offset(0f, -.48f)
                // The earth mass keeps one x anchor but erupts from below into its planted strike
                // position, producing the requested fixed-site "burst upward" motion.
                3 -> Offset(0f, .24f)
                else -> classAuthoredPathStart(motion, signature)
            }
        } else {
            classAuthoredPathStart(motion, signature)
        }
        val direction = if (reducedMotion) 1f else profile.mirror
        val baseRotation = if (reducedMotion) 0f else {
            classPrimaryRotationDegrees(definition, hitIndex) + profile.rotationOffsetDegrees
        }
        val reviewedPrimaryRotation = if (reducedMotion) {
            0f
        } else if (definition.heroClass == HeroClass.RANGER && definition.candidate == 0) {
            // Precision art is authored as one horizontal arrow. Semantic path variants may move
            // it from different sides, but must never rotate that body into a vertical spear.
            0f
        } else if (warriorFullBleed != null) {
            when (definition.candidate) {
                1 -> floatArrayOf(-2f, 1f, 0f, 2f)[warriorAccent?.routeVariant ?: 0]
                2 -> 0f
                else -> floatArrayOf(-1f, 0f, 1f, 0f)[warriorAccent?.routeVariant ?: 0]
            }
        } else {
            baseRotation + composition.rotationBiasDegrees + primaryGrammar.rotationBiasDegrees
        }
        val impactLayer = classImpactLayerFor(definition, hitIndex)
        val impactAsset = listOf(assets.impactPoint, assets.impactFracture, assets.impactRing)[impactLayer.ordinal]
        val debrisAsset = listOf(assets.debrisPoint, assets.debrisFracture, assets.debrisRing)[impactLayer.ordinal]
        val nonFinalEnd = minOf(
            260,
            (definition.hitTimingsMillis.getOrNull(hitIndex + 1)?.minus(timing) ?: 200) + 60,
        ).coerceAtLeast(105)
        val finalEnd = if (warriorFullBleed != null) {
            minOf(820, SKILL_VFX_LAST_VISIBLE_MILLIS - timing)
        } else {
            val authoredTail = AuthoredLayerRole.entries.maxOfOrNull { role ->
                nonWarriorCandidateRoleTimeline(definition, role)?.endMillis ?: 0
            } ?: 0
            minOf(maxOf(390 + band * 25, authoredTail), SKILL_VFX_LAST_VISIBLE_MILLIS - timing)
        }
        // Final phases intentionally share one short outer-plane burst. Its alpha remains low;
        // the compositor still admits at most two planes through the central damage-number lane.
        val finalBurstStart = 16
        val finalBurstEnd = minOf(126 + band * 12, finalEnd)

        fun window(role: AuthoredLayerRole, normalStart: Int, normalEnd: Int): Pair<Int, Int>? {
            if (!reducedMotion) {
                val reviewedTimeline = reviewedRoleTimeline(definition, role)
                if (reviewedTimeline != null) {
                    val ceiling = if (isFinal) finalEnd else nonFinalEnd
                    val end = minOf(reviewedTimeline.endMillis, ceiling)
                    return if (reviewedTimeline.startMillis < end) reviewedTimeline.startMillis to end else null
                }
                return descriptor(role).window(normalStart, normalEnd, finalEnd)
            }
            val reduced = reducedAuthoredWindow(role, isFinal) ?: return null
            return reduced.first to (reduced.last + 1)
        }

        if (AuthoredLayerRole.SECONDARY in roles) {
            val roleGrammar = descriptor(AuthoredLayerRole.SECONDARY)
            val anticipationStart = profile.secondaryLeadMillis + composition.anticipationOffsetMillis
            val normalWindow = window(
                AuthoredLayerRole.SECONDARY,
                anticipationStart,
                if (isFinal) maxOf(minOf(230 + band * 12, finalEnd), finalBurstEnd) else minOf(230 + band * 12, finalEnd),
            )
            val resolvedWindow = if (isFinal && !reducedMotion && warriorFullBleed != null && AuthoredLayerRole.SECONDARY in peakRoleOrder) {
                normalWindow?.let { minOf(it.first, finalBurstStart) to maxOf(it.second, finalBurstEnd) }
            } else normalWindow
            resolvedWindow
                ?.let { (start, end) ->
                    val settle = if (reducedMotion) 1f else modularEaseOut(
                        modularFraction(local, start, profile.secondarySettleMillis + composition.anticipationOffsetMillis / 2),
                    )
                    val alpha = if (reducedMotion) {
                        if (local in start until end) 0.34f else 0f
                    } else {
                        reviewedRoleEnvelope(definition, AuthoredLayerRole.SECONDARY, local, end)
                            ?: (authoredEnvelope(local, start, profile.secondarySettleMillis, 35, end) *
                                (0.24f + band * 0.035f))
                    }
                    appendFrame(
                        role = AuthoredLayerRole.SECONDARY,
                        assetId = assets.secondary,
                        instance = 0,
                        hitIndex = hitIndex,
                        hitMillis = timing,
                        local = local,
                        start = start,
                        end = end,
                        xFraction = centerX + pathStart.x * 0.43f * (1f - settle) +
                            if (warriorFullBleed != null) 0f else -profile.residualVector.x * 0.25f + roleGrammar.offset.x,
                        yFraction = centerY + pathStart.y * 0.43f * (1f - settle) +
                            if (warriorFullBleed != null) 0f else -0.055f + roleGrammar.offset.y,
                        widthFraction = (0.45f + band * 0.025f) * profile.secondaryScale * composition.secondaryScale *
                            roleGrammar.scaleX * (warriorFullBleed?.anticipation ?: 1f),
                        heightToWidth = roleGrammar.scaleY / roleGrammar.scaleX,
                        rotationDegrees = if (reducedMotion || warriorFullBleed != null) 0f else baseRotation + profile.secondaryRotationDegrees - composition.rotationBiasDegrees * .55f + roleGrammar.rotationBiasDegrees,
                        alpha = authoredRoleAlpha(definition, roleGrammar, alpha),
                        reveal = settle,
                        mirror = if (reducedMotion || warriorFullBleed != null) 1f else -direction * roleGrammar.mirror,
                    )
                }
        }

        var reviewedPrimaryVisibleImpact: Offset? = null
        var reviewedPrimaryWidthFraction: Float? = null
        if (AuthoredLayerRole.PRIMARY in roles) {
            val roleGrammar = descriptor(AuthoredLayerRole.PRIMARY)
            val primaryAsset = authoredPrimaryAssetForHit(
                definition = definition,
                primaryA = assets.primaryA,
                primaryB = assets.primaryB,
                hitIndex = hitIndex,
            )
            val normalEnd = if (isFinal) minOf(270, finalEnd) else minOf(225, nonFinalEnd)
            val primaryStart = profile.primaryLeadMillis + composition.anticipationOffsetMillis / 2
            window(AuthoredLayerRole.PRIMARY, primaryStart, normalEnd + band * 8)?.let { (start, end) ->
                val primaryRotation = if (
                    warriorFullBleed != null ||
                    (definition.heroClass == HeroClass.RANGER && definition.candidate == 0)
                ) {
                    reviewedPrimaryRotation
                } else if (reducedMotion) {
                    0f
                } else {
                    baseRotation + composition.rotationBiasDegrees + roleGrammar.rotationBiasDegrees
                }
                val desiredWidthFraction = if (warriorFullBleed != null) {
                    when (definition.candidate) {
                        1 -> floatArrayOf(.38f, .42f, .47f)[grammar.growthStage.ordinal]
                        2 -> floatArrayOf(.92f, 1.02f, 1.12f)[grammar.growthStage.ordinal]
                        else -> floatArrayOf(.92f, 1.02f, 1.12f)[grammar.growthStage.ordinal]
                    }
                } else {
                    (0.36f + band * 0.025f) * profile.primaryScale * composition.primaryScale *
                        roleGrammar.scaleX
                }
                val desiredHeightToWidth = warriorVfx8AspectRatio(primaryAsset)
                    ?: if (definition.heroClass == HeroClass.RANGER && definition.candidate == 0) {
                        .34f
                    } else {
                        roleGrammar.scaleY / roleGrammar.scaleX
                    }
                val desiredWidth = viewportWidth * desiredWidthFraction
                val desiredHeight = desiredWidth * desiredHeightToWidth
                val desiredStartX = viewportWidth * (centerX + if (reducedMotion) 0f else pathStart.x)
                val desiredStartY = viewportHeight * (centerY + 0.015f + if (reducedMotion) 0f else pathStart.y)
                val desiredContactX = viewportWidth * centerX
                val desiredContactY = viewportHeight * (centerY + 0.015f)
                val trajectory = if (warriorFullBleed != null) {
                    AuthoredPrimaryTrajectory(
                        start = rawAuthoredAssetPlacement(
                            desiredStartX, desiredStartY, desiredWidth, desiredHeight, primaryRotation,
                        ),
                        contact = rawAuthoredAssetPlacement(
                            desiredContactX, desiredContactY, desiredWidth, desiredHeight, primaryRotation,
                        ),
                    )
                } else {
                    safeAuthoredPrimaryTrajectory(
                        canvasWidth = viewportWidth,
                        canvasHeight = viewportHeight,
                        desiredStartX = desiredStartX,
                        desiredStartY = desiredStartY,
                        desiredContactX = desiredContactX,
                        desiredContactY = desiredContactY,
                        width = desiredWidth,
                        // Fit the exact non-uniform grammar bounds that appendFrame will draw.
                        // Fitting a square and stretching it afterward made the second placement
                        // clamp both endpoints to the same edge, visually freezing tall projectiles.
                        height = desiredHeight,
                        rotationDegrees = primaryRotation,
                        overflowFraction = authoredOverflowFraction,
                    )
                }
                val settle = if (reducedMotion) 1f else modularEaseOut(
                    // The first hit may begin before the 0 ms scene boundary. Keep a minimum
                    // trajectory span so its first on-screen sample still carries visible signed
                    // travel instead of arriving already settled at the safe-placement edge.
                    modularFraction(
                        local,
                        start,
                        if (warriorFullBleed != null) {
                            // Earth keeps rising through the contact beat; the other two reviewed
                            // families still arrive exactly at contact.
                            if (definition.candidate == 3) 65 else 0
                        } else {
                            start + (profile.primarySettleMillis - profile.primaryLeadMillis).coerceAtLeast(340)
                        },
                    ),
                )
                    val alpha = if (reducedMotion) {
                        if (local in start until end) 0.56f else 0f
                    } else {
                    reviewedRoleEnvelope(definition, AuthoredLayerRole.PRIMARY, local, end)
                        ?: (authoredEnvelope(local, start, profile.primarySettleMillis, if (isFinal) 90 else 55, end) *
                            (0.64f + band * 0.045f))
                }
                val renderedXFraction = (trajectory.start.centerX +
                    (trajectory.contact.centerX - trajectory.start.centerX) * settle) / viewportWidth
                val renderedYFraction = (trajectory.start.centerY +
                    (trajectory.contact.centerY - trajectory.start.centerY) * settle) / viewportHeight
                if (warriorFullBleed != null && definition.candidate == 3) {
                    reviewedPrimaryWidthFraction = trajectory.contact.width / viewportWidth
                    reviewedPrimaryVisibleImpact = warriorVisibleImpactFromStrikeTarget(
                        assetId = primaryAsset,
                        targetXFraction = renderedXFraction,
                        targetYFraction = renderedYFraction,
                        widthFraction = trajectory.contact.width / viewportWidth,
                        heightFraction = trajectory.contact.height / viewportHeight,
                        rotationDegrees = primaryRotation,
                        mirror = 1f,
                        viewportWidth = viewportWidth,
                        viewportHeight = viewportHeight,
                    )
                }
                appendFrame(
                    role = AuthoredLayerRole.PRIMARY,
                    assetId = primaryAsset,
                    instance = 0,
                    hitIndex = hitIndex,
                    hitMillis = timing,
                    local = local,
                    start = start,
                    end = end,
                    xFraction = renderedXFraction,
                    yFraction = renderedYFraction,
                    widthFraction = trajectory.contact.width / viewportWidth,
                    heightToWidth = trajectory.contact.height / trajectory.contact.width.coerceAtLeast(1f),
                    rotationDegrees = primaryRotation,
                    alpha = authoredRoleAlpha(definition, roleGrammar, alpha),
                    reveal = settle,
                    mirror = if (reducedMotion || warriorFullBleed != null) 1f else direction * roleGrammar.mirror,
                )
            }
        }

        if (AuthoredLayerRole.CONTACT in roles) {
            val roleGrammar = descriptor(AuthoredLayerRole.CONTACT)
            val normalEnd = if (isFinal) minOf(295, finalEnd) else minOf(215, nonFinalEnd)
            val contactWindow = window(AuthoredLayerRole.CONTACT, profile.contactDelayMillis, normalEnd)
            contactWindow?.let { (start, end) ->
                val alpha = if (reducedMotion) {
                    if (local in start until end) 0.82f else 0f
                } else {
                    reviewedRoleEnvelope(definition, AuthoredLayerRole.CONTACT, local, end)
                        ?: (authoredEnvelope(local, start, start + 26, start + 48, end) *
                            (0.24f + band * 0.018f))
                }
                val pulse = if (reducedMotion) 1f else 0.78f + 0.22f * modularEaseOut(
                    modularFraction(local, start, start + 78),
                )
                val contactBase = when (impactLayer) {
                    ClassImpactLayer.POINT -> 0.15f + band * 0.010f
                    ClassImpactLayer.FRACTURE -> 0.22f + band * 0.012f
                    ClassImpactLayer.RING -> 0.28f + band * 0.014f
                }
                val authoredContactWidth = contactBase * profile.contactScale * pulse * roleGrammar.scaleX *
                    (warriorFullBleed?.contact ?: 1f)
                val contactWidth = if (warriorFullBleed != null && definition.candidate == 3) {
                    // The U-shaped square blast is intentionally tall. Cap its width against the
                    // horizontal terrain body so LOW/MID/HIGH keep the same optical relationship
                    // instead of the high-tier contact swallowing the primary image.
                    minOf(authoredContactWidth, (reviewedPrimaryWidthFraction ?: authoredContactWidth) * .92f)
                } else {
                    authoredContactWidth
                }
                appendFrame(
                    role = AuthoredLayerRole.CONTACT,
                    assetId = impactAsset,
                    instance = 0,
                    hitIndex = hitIndex,
                    hitMillis = timing,
                    local = local,
                    start = start,
                    end = end,
                    xFraction = if (warriorFullBleed != null && definition.candidate == 3) {
                        reviewedPrimaryVisibleImpact?.x ?: centerX
                    } else {
                        centerX + if (warriorFullBleed != null) 0f else roleGrammar.offset.x
                    },
                    yFraction = if (warriorFullBleed != null && definition.candidate == 3) {
                        // Follow the measured visible spark in the rising terrain body. The old
                        // center-based placement put the contact convergence below the viewport,
                        // so only two detached dirt columns remained visible.
                        reviewedPrimaryVisibleImpact?.y ?: .81f
                    } else {
                        centerY + 0.015f + if (warriorFullBleed != null) 0f else roleGrammar.offset.y
                    },
                    widthFraction = contactWidth,
                    heightToWidth = roleGrammar.scaleY / roleGrammar.scaleX,
                    rotationDegrees = if (reducedMotion) {
                        0f
                    } else if (warriorFullBleed != null) {
                        // Contact follows the body axis; only a small family-specific surface
                        // angle remains so the impact reads as attached instead of pasted on.
                        reviewedPrimaryRotation + when (definition.candidate) {
                            1 -> 6f
                            2 -> 0f
                            3 -> 0f
                            else -> -6f
                        }
                    } else {
                        floatArrayOf(-8f, 7f, 13f, -5f)[signature.tierVariant] -
                            composition.rotationBiasDegrees * .35f + roleGrammar.rotationBiasDegrees
                    },
                    alpha = authoredRoleAlpha(definition, roleGrammar, alpha),
                    reveal = pulse,
                    mirror = if (reducedMotion || warriorFullBleed != null) 1f else direction * roleGrammar.mirror,
                )
            }
        }

        if (AuthoredLayerRole.DEBRIS in roles) {
            val roleGrammar = descriptor(AuthoredLayerRole.DEBRIS)
            val normalWindow = window(
                AuthoredLayerRole.DEBRIS,
                if (isFinal) minOf(profile.debrisDelayMillis, finalBurstStart) else profile.debrisDelayMillis,
                finalEnd,
            )
            val resolvedWindow = if (isFinal && !reducedMotion && warriorFullBleed != null) {
                normalWindow?.let { minOf(it.first, finalBurstStart) to maxOf(it.second, finalBurstEnd) }
            } else normalWindow
            resolvedWindow?.let { (start, end) ->
                val travel = if (reducedMotion) 1f else modularEaseOut(modularFraction(local, start + 18, end))
                val alpha = if (reducedMotion) {
                    if (local in start until end) 0.28f else 0f
                } else {
                    reviewedRoleEnvelope(definition, AuthoredLayerRole.DEBRIS, local, end)
                        ?: (authoredEnvelope(local, start, start + 42, 160, end) *
                            (0.18f + band * 0.026f))
                }
                appendFrame(
                    role = AuthoredLayerRole.DEBRIS,
                    assetId = debrisAsset,
                    instance = 0,
                    hitIndex = hitIndex,
                    hitMillis = timing,
                    local = local,
                    start = start,
                    end = end,
                    xFraction = if (warriorFullBleed != null) {
                        centerX + when (definition.candidate) {
                            1 -> .025f * travel
                            2 -> -.10f * travel
                            else -> 0f
                        }
                    } else centerX + profile.debrisVector.x * travel * composition.debrisSpread + roleGrammar.offset.x,
                    yFraction = if (warriorFullBleed != null) {
                        centerY + when (definition.candidate) {
                            1 -> -.07f * travel
                            2 -> 0f
                            else -> -.045f * travel
                        }
                    } else centerY + profile.debrisVector.y * travel * composition.debrisSpread + roleGrammar.offset.y,
                    widthFraction = if (warriorFullBleed != null) {
                        when (definition.candidate) {
                            1 -> floatArrayOf(.34f, .38f, .42f)[grammar.growthStage.ordinal]
                            2 -> floatArrayOf(.44f, .50f, .56f)[grammar.growthStage.ordinal]
                            else -> floatArrayOf(.46f, .52f, .58f)[grammar.growthStage.ordinal]
                        }
                    } else (0.26f + band * 0.025f) * roleGrammar.scaleX,
                    heightToWidth = roleGrammar.scaleY / roleGrammar.scaleX,
                    rotationDegrees = if (reducedMotion) {
                        0f
                    } else if (warriorFullBleed != null) {
                        when (definition.candidate) {
                            1 -> reviewedPrimaryRotation + 9f
                            2 -> reviewedPrimaryRotation - 8f
                            else -> (-12f + 24f * travel) // ground fragments spread radially
                        }
                    } else {
                        (floatArrayOf(18f, -22f, 31f, -14f)[signature.tierVariant] +
                            composition.rotationBiasDegrees) * travel + roleGrammar.rotationBiasDegrees
                    },
                    alpha = authoredRoleAlpha(definition, roleGrammar, alpha),
                    reveal = travel,
                    mirror = if (reducedMotion || warriorFullBleed != null) 1f else direction * roleGrammar.mirror,
                )
            }
        }

        if (AuthoredLayerRole.RESIDUAL in roles) {
            val roleGrammar = descriptor(AuthoredLayerRole.RESIDUAL)
            val normalWindow = window(
                AuthoredLayerRole.RESIDUAL,
                if (isFinal) minOf(profile.residualDelayMillis, finalBurstStart + 8) else profile.residualDelayMillis,
                finalEnd,
            )
            val resolvedWindow = if (isFinal && !reducedMotion && warriorFullBleed != null && AuthoredLayerRole.RESIDUAL in peakRoleOrder) {
                normalWindow?.let { minOf(it.first, finalBurstStart + 4) to maxOf(it.second, finalBurstEnd) }
            } else normalWindow
            resolvedWindow?.let { (start, end) ->
                val enter = if (reducedMotion) 1f else modularEaseOut(modularFraction(local, start, start + 80))
                val alpha = if (reducedMotion) {
                    if (local in start until end) 0.26f else 0f
                } else {
                    reviewedRoleEnvelope(definition, AuthoredLayerRole.RESIDUAL, local, end)
                        ?: (authoredEnvelope(local, start, start + 80, 205, end) *
                            (0.16f + band * 0.035f))
                }
                appendFrame(
                    role = AuthoredLayerRole.RESIDUAL,
                    assetId = assets.residual,
                    instance = 0,
                    hitIndex = hitIndex,
                    hitMillis = timing,
                    local = local,
                    start = start,
                    end = end,
                    xFraction = if (warriorFullBleed != null) centerX else centerX + profile.residualVector.x + roleGrammar.offset.x,
                    yFraction = if (warriorFullBleed != null) centerY + .015f else centerY + profile.residualVector.y + 0.035f + composition.residualLift + roleGrammar.offset.y,
                    widthFraction = if (warriorFullBleed != null) {
                        when (definition.candidate) {
                            1 -> floatArrayOf(.28f, .32f, .36f)[grammar.growthStage.ordinal]
                            2 -> floatArrayOf(.38f, .44f, .50f)[grammar.growthStage.ordinal]
                            else -> floatArrayOf(.34f, .40f, .46f)[grammar.growthStage.ordinal]
                        }
                    } else (0.38f + band * 0.040f) * roleGrammar.scaleX,
                    heightToWidth = roleGrammar.scaleY / roleGrammar.scaleX,
                    rotationDegrees = if (reducedMotion) 0f else floatArrayOf(7f, -9f, 13f, -5f)[signature.tierVariant] + composition.rotationBiasDegrees * .45f + roleGrammar.rotationBiasDegrees,
                    alpha = authoredRoleAlpha(definition, roleGrammar, alpha),
                    reveal = enter,
                    mirror = if (reducedMotion || warriorFullBleed != null) 1f else direction * roleGrammar.mirror,
                )
            }
        }

        if (AuthoredLayerRole.FINISHER_RING in roles) {
            val roleGrammar = descriptor(AuthoredLayerRole.FINISHER_RING)
            val normalWindow = window(AuthoredLayerRole.FINISHER_RING, finalBurstStart + 12, finalEnd)
            val resolvedWindow = if (isFinal && !reducedMotion && warriorFullBleed != null && AuthoredLayerRole.FINISHER_RING in peakRoleOrder) {
                normalWindow?.let { minOf(it.first, finalBurstStart + 8) to maxOf(it.second, finalBurstEnd) }
            } else normalWindow
            resolvedWindow?.let { (start, end) ->
                val progress = if (reducedMotion) 1f else modularEaseOut(modularFraction(local, start, 205))
                val alpha = if (reducedMotion) {
                    if (local in start until end) 0.24f else 0f
                } else {
                    reviewedRoleEnvelope(definition, AuthoredLayerRole.FINISHER_RING, local, end)
                        ?: (authoredEnvelope(local, start, 135, 230, end) * 0.17f)
                }
                appendFrame(
                    role = AuthoredLayerRole.FINISHER_RING,
                    // Keep the sixth plane independent from the contact plane even when the
                    // contact grammar already selected the ring asset.
                    assetId = assets.finisherRing,
                    instance = 0,
                    hitIndex = hitIndex,
                    hitMillis = timing,
                    local = local,
                    start = start,
                    end = end,
                    xFraction = centerX - profile.residualVector.x * 0.40f + composition.finisherOrbit + roleGrammar.offset.x,
                    yFraction = centerY - profile.residualVector.y * 0.40f - kotlin.math.abs(composition.finisherOrbit) * .35f + roleGrammar.offset.y,
                    widthFraction = (0.34f + band * 0.025f) * (0.84f + progress * 0.16f) * roleGrammar.scaleX *
                        (warriorFullBleed?.finisher ?: 1f),
                    heightToWidth = roleGrammar.scaleY / roleGrammar.scaleX,
                    rotationDegrees = if (reducedMotion) {
                        0f
                    } else if (warriorFullBleed != null) {
                        when (definition.candidate) {
                            1 -> reviewedPrimaryRotation + 8f
                            2 -> reviewedPrimaryRotation - 8f
                            else -> 0f
                        }
                    } else {
                        -9f + progress * 15f + composition.rotationBiasDegrees + roleGrammar.rotationBiasDegrees
                    },
                    alpha = authoredRoleAlpha(definition, roleGrammar, alpha),
                    reveal = progress,
                    mirror = if (reducedMotion) 1f else direction * roleGrammar.mirror,
                )
            }
        }

        if (AuthoredLayerRole.FINISHER_ECHO in roles) {
            val roleGrammar = descriptor(AuthoredLayerRole.FINISHER_ECHO)
            window(AuthoredLayerRole.FINISHER_ECHO, 150, finalEnd)?.let { (start, end) ->
                val progress = if (reducedMotion) 1f else modularEaseOut(modularFraction(local, start, minOf(start + 145, end)))
                val alpha = if (reducedMotion) {
                    if (local in start until end) .16f else 0f
                } else {
                    reviewedRoleEnvelope(definition, AuthoredLayerRole.FINISHER_ECHO, local, end)
                        ?: (authoredEnvelope(
                            local,
                            start,
                            minOf(start + 72, end - 1),
                            minOf(start + 190, end - 1),
                            end,
                        ) * .18f)
                }
                appendFrame(
                    role = AuthoredLayerRole.FINISHER_ECHO,
                    assetId = assets.finisherEcho,
                    instance = 0,
                    hitIndex = hitIndex,
                    hitMillis = timing,
                    local = local,
                    start = start,
                    end = end,
                    xFraction = centerX - composition.finisherOrbit * 1.35f + roleGrammar.offset.x,
                    yFraction = centerY + kotlin.math.abs(composition.finisherOrbit) * .48f + .025f + roleGrammar.offset.y,
                    widthFraction = (.27f + band * .026f) * (.86f + progress * .22f) * roleGrammar.scaleX *
                        (warriorFullBleed?.finisher ?: 1f),
                    heightToWidth = roleGrammar.scaleY / roleGrammar.scaleX,
                    rotationDegrees = if (reducedMotion) {
                        0f
                    } else if (warriorFullBleed != null) {
                        when (definition.candidate) {
                            1 -> reviewedPrimaryRotation - 8f
                            2 -> reviewedPrimaryRotation + 8f
                            else -> 0f
                        }
                    } else {
                        14f - progress * 22f - composition.rotationBiasDegrees + roleGrammar.rotationBiasDegrees
                    },
                    alpha = authoredRoleAlpha(definition, roleGrammar, alpha),
                    reveal = progress,
                    mirror = if (reducedMotion) 1f else -direction * roleGrammar.mirror,
                )
            }
        }

        if (AuthoredLayerRole.WARRIOR_ACCENT in roles && warriorAccent != null) {
            val roleGrammar = descriptor(AuthoredLayerRole.WARRIOR_ACCENT)
            val normalEnd = if (warriorFullBleed != null) {
                warriorNonSlashRoleTimeline(definition, AuthoredLayerRole.WARRIOR_ACCENT)?.endMillis
                    ?.let { minOf(it, finalEnd) } ?: minOf(warriorAccent.residualEndMillis, finalEnd)
            } else {
                minOf(warriorAccent.residualEndMillis, finalEnd)
            }
            window(AuthoredLayerRole.WARRIOR_ACCENT, -125, normalEnd)?.let { (start, end) ->
                val settle = if (reducedMotion) 1f else modularEaseOut(modularFraction(local, 12, 170))
                val alpha = if (reducedMotion) {
                    if (local in start until end) 0.30f else 0f
                } else {
                    warriorNonSlashEnvelope(definition, AuthoredLayerRole.WARRIOR_ACCENT, local)
                        ?: (authoredEnvelope(local, start, 12, end - 170, end) *
                            (0.16f + band * 0.040f))
                }
                val variant = warriorAccent.routeVariant
                val accentDirection = if (variant.mod(2) == 0) 1f else -1f
                val base = 0.18f + band * 0.025f
                val accentAsset = warriorNonSlashAccentResource(definition)
                val instanceCount = if (reducedMotion) 1 else when (warriorAccent.family) {
                    WarriorNonSlashFamily.CHARGE -> (1 + band / 2).coerceAtMost(3)
                    WarriorNonSlashFamily.ERUPTION -> if (band >= 3) 3 else 2
                    WarriorNonSlashFamily.SHOCKWAVE -> if (band >= 2) 2 else 1
                    WarriorNonSlashFamily.COLLAPSE -> if (band >= 4) 4 else 3
                    WarriorNonSlashFamily.DESCENT -> 1
                }
                repeat(instanceCount) { instance ->
                    val angle = (variant * 31f + instance * (360f / instanceCount)) * (Math.PI / 180.0).toFloat()
                    val (accentX, accentY) = when (warriorAccent.family) {
                        WarriorNonSlashFamily.DESCENT ->
                            centerX + floatArrayOf(-0.16f, 0.14f, -0.09f, 0.10f)[variant] * (1f - settle) to
                                centerY - (0.32f - 0.28f * settle)
                        WarriorNonSlashFamily.CHARGE ->
                            centerX + (settle - 0.5f) * 0.20f * accentDirection - 0.055f * instance * accentDirection to
                                centerY + (instance - 1) * 0.018f
                        WarriorNonSlashFamily.ERUPTION ->
                            centerX + (instance - (instanceCount - 1) / 2f) * (0.13f + variant * 0.008f) to
                                centerY + (0.20f - 0.20f * settle) + instance * 0.012f
                        WarriorNonSlashFamily.SHOCKWAVE ->
                            (centerX + if (instance == 0) 0f else -0.08f * accentDirection) to
                                (centerY + 0.11f)
                        WarriorNonSlashFamily.COLLAPSE -> {
                            val distance = (0.18f + band * 0.012f) * (1f - settle)
                            centerX + cos(angle) * distance to centerY + sin(angle) * distance * 0.72f
                        }
                    }
                    val width = when (warriorAccent.family) {
                        WarriorNonSlashFamily.CHARGE -> base * 1.10f * (1f - instance * 0.10f)
                        WarriorNonSlashFamily.SHOCKWAVE -> base * (0.68f + settle * (0.42f + band * 0.05f)) *
                            if (instance == 0) 1f else 0.72f
                        WarriorNonSlashFamily.COLLAPSE -> base * (0.54f + settle * 0.18f)
                        else -> base * (0.76f + 0.24f * settle)
                    } * (warriorFullBleed?.accent ?: 1f)
                    appendFrame(
                        role = AuthoredLayerRole.WARRIOR_ACCENT,
                        assetId = accentAsset,
                        instance = instance,
                        hitIndex = hitIndex,
                        hitMillis = timing,
                        local = local,
                        start = start,
                        end = end,
                        xFraction = accentX,
                        yFraction = accentY,
                        widthFraction = width,
                        heightToWidth = when (warriorAccent.family) {
                            WarriorNonSlashFamily.DESCENT, WarriorNonSlashFamily.ERUPTION -> 1.35f
                            WarriorNonSlashFamily.CHARGE -> 0.42f
                            WarriorNonSlashFamily.SHOCKWAVE -> floatArrayOf(0.34f, 0.48f, 0.28f, 0.40f)[variant]
                            WarriorNonSlashFamily.COLLAPSE -> 1f
                        },
                        rotationDegrees = if (reducedMotion) 0f else when (warriorAccent.family) {
                            WarriorNonSlashFamily.DESCENT -> floatArrayOf(-12f, 9f, -4f, 14f)[variant]
                            WarriorNonSlashFamily.CHARGE -> 0f
                            WarriorNonSlashFamily.ERUPTION -> floatArrayOf(-13f, 10f, -5f, 15f)[(variant + instance).mod(4)]
                            WarriorNonSlashFamily.SHOCKWAVE -> floatArrayOf(-7f, 4f, 10f, -3f)[variant] * if (instance == 0) 1f else -1f
                            WarriorNonSlashFamily.COLLAPSE -> instance * 37f + settle * 24f
                        },
                        alpha = authoredRoleAlpha(definition, roleGrammar, alpha) *
                            (1f - instance * 0.12f),
                        reveal = settle,
                        mirror = if (reducedMotion) 1f else accentDirection * if (instance == 0) 1f else -1f,
                    )
                }
            }
        }
    }

    val drawOrder = mapOf(
        AuthoredLayerRole.SECONDARY to 0,
        AuthoredLayerRole.RESIDUAL to 1,
        AuthoredLayerRole.DEBRIS to 2,
        AuthoredLayerRole.PRIMARY to 3,
        AuthoredLayerRole.WARRIOR_ACCENT to 4,
        AuthoredLayerRole.FINISHER_RING to 5,
        AuthoredLayerRole.FINISHER_ECHO to 6,
        // The impact flash is the topmost VFX plane. Damage text remains above all VFX at z=100.
        AuthoredLayerRole.CONTACT to 7,
    )
    val priority = mapOf(
        AuthoredLayerRole.CONTACT to 100,
        AuthoredLayerRole.FINISHER_RING to 95,
        AuthoredLayerRole.FINISHER_ECHO to 92,
        AuthoredLayerRole.PRIMARY to 90,
        AuthoredLayerRole.WARRIOR_ACCENT to 82,
        AuthoredLayerRole.SECONDARY to 80,
        AuthoredLayerRole.DEBRIS to 70,
        AuthoredLayerRole.RESIDUAL to 60,
    )
    if (!reducedMotion) {
        val budget = phaseCompositionBudget(definition)
        val latestHit = frames.maxOfOrNull { it.hitIndex } ?: return emptyList()
        val activeRoles = frames.filter { it.hitIndex == latestHit }.map { it.role }.toSet()
        val mandatory = budget.mandatoryRoles.filter(activeRoles::contains)
        // Select one plane from every mandatory phase first. This avoids the former global
        // priority starvation where contact/finish existed in metadata but never reached draw.
        val selected = buildList {
            mandatory.forEach { role ->
                frames.filter { it.role == role }
                    .maxWithOrNull(
                        compareBy<AuthoredLayerFrame> { it.hitIndex }
                            .thenBy { it.alpha }
                            .thenByDescending { it.instance },
                    )
                    ?.let(::add)
            }
            frames.asSequence()
                .filterNot { candidate -> any { it === candidate } }
                .sortedWith(
                    compareByDescending<AuthoredLayerFrame> { it.hitIndex == latestHit }
                        .thenByDescending { priority.getValue(it.role) }
                        .thenByDescending { it.alpha }
                        .thenBy { it.instance },
                )
                .take((budget.concurrentLimit - size).coerceAtLeast(0))
                .forEach(::add)
        }.take(budget.concurrentLimit)
        return selected.sortedBy { drawOrder.getValue(it.role) }
    }
    val selected = frames
        .sortedWith(
            compareByDescending<AuthoredLayerFrame> { priority.getValue(it.role) }
                .thenByDescending { it.hitIndex }
                .thenBy { it.instance },
        )
        .take(2)
    return selected.sortedBy { drawOrder.getValue(it.role) }
}

internal fun modularLayerCount(definition: SkillDefinition, isFinal: Boolean): Int {
    if (!shouldKeepLegacyPrimary(definition)) {
        return authoredCompositionRoles(definition, isFinal).size
    }
    return when {
        semanticVfxPlan(definition).impactStyle == SemanticImpactStyle.NONE -> 1
        isFinal -> skillVfxIntensityProfile(definition).finalLayerCount
        skillVfxIntensityProfile(definition).tier >= 4 -> 3
        else -> 2
    }
}

@Composable
internal fun ModularSkillEffectLayer(
    definition: SkillDefinition,
    elapsedMillis: Int,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = skillPalette(definition.element)
    val intensity = skillVfxIntensityProfile(definition)
    val detailedSpriteSheetAssetId = warriorDetailedSpriteSheetAssetId(definition.catalogId)
    val detailedSpriteSheet = detailedSpriteSheetAssetId?.let { ImageBitmap.imageResource(it) }
    val useLegacyPrimary = shouldKeepLegacyPrimary(definition)
    val runtimeResourceIds = if (detailedSpriteSheetAssetId != null) {
        intArrayOf()
    } else if (useLegacyPrimary) {
        legacyWarriorRuntimeAssetIds()
    } else if (isCreationEarthquake(definition)) {
        (classLayeredAssetSpec(definition).authoredRuntimeAssetIds() + creationEarthquakeSpriteAssetIds)
            .distinct()
            .toIntArray()
    } else {
        classLayeredAssetSpec(definition).authoredRuntimeAssetIds()
    }
    val authoredAssetsById = runtimeResourceIds.associateWith { ImageBitmap.imageResource(it) }

    Canvas(modifier) {
        if (detailedSpriteSheet != null) {
            drawWarriorDetailedSpriteSheet(
                sheet = detailedSpriteSheet,
                elapsedMillis = elapsedMillis,
                reducedMotion = reducedMotion,
            )
            return@Canvas
        }
        val lastHit = definition.hitTimingsMillis.last()
        val backdropProgress = when {
            elapsedMillis < 0 -> 0f
            elapsedMillis < 110 -> elapsedMillis / 110f
            elapsedMillis < lastHit + 100 -> 1f
            else -> 1f - modularFraction(elapsedMillis, lastHit + 100, SKILL_VFX_END_MILLIS)
        }.coerceIn(0f, 1f)
        if (isCreationEarthquake(definition)) {
            fun farthestCornerRadius(center: Offset): Float = hypot(
                maxOf(center.x, size.width - center.x),
                maxOf(center.y, size.height - center.y),
            )
            val shadeCenter = Offset(size.width * .50f, size.height * .74f)
            drawRect(
                brush = Brush.radialGradient(
                    0f to Color(0xB897140A),
                    .52f to Color(0xE648040D),
                    .92f to Color(0xFF1D050A),
                    1f to Color(0xFF1D050A),
                    center = shadeCenter,
                    radius = farthestCornerRadius(shadeCenter),
                ),
            )
            val glowCenter = Offset(size.width * .50f, size.height * .76f)
            drawRect(
                brush = Brush.radialGradient(
                    0f to Color(0xEBFF3A12),
                    .42f to Color(0x8C780812),
                    .72f to Color.Transparent,
                    center = glowCenter,
                    radius = farthestCornerRadius(glowCenter),
                ),
                alpha = creationEarthquakeGlowAlpha(elapsedMillis, lastHit, reducedMotion),
                blendMode = BlendMode.Screen,
            )
        } else {
            val backdropAlpha = if (reducedMotion) 0f else backdropProgress *
                palette.maxBackdropAlpha.coerceAtMost(0.055f) *
                intensity.backdropScale
            drawRect(palette.backdrop.copy(alpha = backdropAlpha))
        }

        val authoritativeFrames = if (useLegacyPrimary) {
            legacyWarriorFramePlan(
                definition = definition,
                elapsedMillis = elapsedMillis,
                reducedMotion = reducedMotion,
                viewportWidth = size.width,
                viewportHeight = size.height,
            )
        } else {
            authoredRenderableFramePlan(
                definition = definition,
                elapsedMillis = elapsedMillis,
                reducedMotion = reducedMotion,
                viewportWidth = size.width,
                viewportHeight = size.height,
            )
        }
        authoritativeFrames.forEach { frame ->
            val asset = checkNotNull(authoredAssetsById[frame.assetId]) {
                "${definition.catalogId} did not preload authored asset ${frame.assetId}"
            }
            drawAuthoritativeAssetFrame(asset = asset, frame = frame)
        }
    }
}

private fun DrawScope.drawWarriorDetailedSpriteSheet(
    sheet: ImageBitmap,
    elapsedMillis: Int,
    reducedMotion: Boolean,
) {
    val frameIndex = warriorDetailedSpriteFrameIndex(elapsedMillis, reducedMotion) ?: return
    val frameWidth = sheet.width / WARRIOR_DETAILED_SPRITE_COLUMNS
    val frameHeight = sheet.height / WARRIOR_DETAILED_SPRITE_ROWS
    drawImage(
        image = sheet,
        srcOffset = IntOffset(
            x = (frameIndex % WARRIOR_DETAILED_SPRITE_COLUMNS) * frameWidth,
            y = (frameIndex / WARRIOR_DETAILED_SPRITE_COLUMNS) * frameHeight,
        ),
        srcSize = IntSize(frameWidth, frameHeight),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(
            width = size.width.roundToInt().coerceAtLeast(1),
            height = size.height.roundToInt().coerceAtLeast(1),
        ),
        alpha = 1f,
        blendMode = BlendMode.Screen,
    )
}

internal fun shouldKeepLegacyPrimary(definition: SkillDefinition): Boolean =
    definition.heroClass == HeroClass.WARRIOR && semanticVfxPlan(definition).action in setOf(
        SemanticVfxAction.CUT,
        SemanticVfxAction.CROSS_CUT,
        SemanticVfxAction.FLURRY,
        SemanticVfxAction.SPIN,
    )

internal fun warriorFinisherResourceIndex(definition: SkillDefinition): Int = when {
    definition.unlockLevel >= 100 -> 10
    definition.catalogId in setOf(
        "warrior_t12_c02", "warrior_t15_c02", "warrior_t15_c05", "warrior_t19_c02",
    ) -> 6
    definition.catalogId in setOf("warrior_t02_c01", "warrior_t11_c01") -> 7
    definition.intensityTier >= 5 -> 8
    definition.catalogId in setOf("warrior_t06_c05", "warrior_t11_c05", "warrior_t16_c01") -> 5
    definition.catalogId in setOf("warrior_t07_c01", "warrior_t15_c01") -> 3
    else -> 4
}

private fun DrawScope.drawWarriorNonSlashAccent(
    asset: ImageBitmap,
    definition: SkillDefinition,
    signature: WarriorNonSlashSignature,
    center: Offset,
    local: Int,
    isFinal: Boolean,
    tint: Color,
    reducedMotion: Boolean,
) {
    if (!isFinal) return
    val band = signature.intensityBand
    val variant = signature.routeVariant
    val end = signature.residualEndMillis
    if (local !in -125 until end) return

    val entry = if (reducedMotion) 1f else modularEaseOut(modularFraction(local, -125, 12))
    val settle = if (reducedMotion) 1f else modularEaseOut(modularFraction(local, 12, 170))
    val fade = if (reducedMotion) {
        if (local in 0 until 190) 1f else 0f
    } else {
        1f - modularFraction(local, end - 170, end)
    }
    val alpha = minOf(entry, fade).coerceIn(0f, 1f) * (0.18f + band * 0.045f)
    if (alpha <= 0f) return

    val direction = if (variant % 2 == 0) 1f else -1f
    val base = 0.24f + band * 0.035f
    when (signature.family) {
        WarriorNonSlashFamily.DESCENT -> {
            // A second offset strike makes hammer/axe skills read as a falling weapon,
            // while alternating the landing side prevents the same central pillar silhouette.
            val x = center.x + size.width * (floatArrayOf(-0.16f, 0.14f, -0.09f, 0.10f)[variant]) * (1f - settle)
            val y = center.y - size.height * (0.32f - 0.28f * settle)
            val width = size.width * base * (0.76f + 0.24f * settle)
            drawTintedAsset(asset, Offset(x, y), width, width * 1.35f, alpha, tint, floatArrayOf(-12f, 9f, -4f, 14f)[variant])
        }
        WarriorNonSlashFamily.CHARGE -> {
            // Three compressed afterimages rush through the target instead of one enlarged sprite.
            val travel = if (reducedMotion) 0f else (settle - 0.5f) * size.width * 0.20f * direction
            val width = size.width * base * 1.10f
            repeat((1 + band / 2).coerceAtMost(3)) { echo ->
                val lag = size.width * 0.055f * echo * -direction
                drawTintedAsset(asset, center + Offset(travel + lag, size.height * (echo - 1) * 0.018f), width * (1f - echo * 0.10f), width * 0.42f, alpha * (0.82f - echo * 0.20f), tint, direction = direction)
            }
        }
        WarriorNonSlashFamily.ERUPTION -> {
            // Split earth in offset lanes; high tiers gain a third delayed spur rather than scale only.
            val count = if (band >= 3) 3 else 2
            repeat(count) { lane ->
                val laneX = (lane - (count - 1) / 2f) * size.width * (0.13f + variant * 0.008f)
                val rise = if (reducedMotion) 1f else settle
                val y = center.y + size.height * (0.20f - 0.20f * rise) + lane * size.height * 0.012f
                val width = size.width * base * (0.72f + lane * 0.08f)
                drawTintedAsset(asset, Offset(center.x + laneX, y), width, width * 1.20f, alpha * (0.84f - lane * 0.10f), tint, floatArrayOf(-13f, 10f, -5f, 15f)[(variant + lane).mod(4)])
            }
        }
        WarriorNonSlashFamily.SHOCKWAVE -> {
            // One directional ground wave plus one late counter-wave: no vortex-style zoom loop.
            val radius = size.width * base * (0.68f + settle * (0.42f + band * 0.05f))
            val squash = floatArrayOf(0.34f, 0.48f, 0.28f, 0.40f)[variant]
            drawTintedAsset(asset, center + Offset(0f, size.height * 0.11f), radius, radius * squash, alpha, tint, floatArrayOf(-7f, 4f, 10f, -3f)[variant], direction)
            if (band >= 2 && local >= 70) {
                drawTintedAsset(asset, center + Offset(size.width * 0.08f * -direction, size.height * 0.10f), radius * 0.72f, radius * squash * 0.72f, alpha * 0.42f, tint, -floatArrayOf(-7f, 4f, 10f, -3f)[variant], -direction)
            }
        }
        WarriorNonSlashFamily.COLLAPSE -> {
            // Four fragments converge, hold, then leave a contained residual core.
            val fragmentCount = if (band >= 4) 4 else 3
            repeat(fragmentCount) { fragment ->
                val angle = (variant * 31f + fragment * (360f / fragmentCount)) * (Math.PI / 180.0).toFloat()
                val distance = if (reducedMotion) 0f else size.width * (0.18f + band * 0.012f) * (1f - settle)
                val fragmentCenter = center + Offset(cos(angle) * distance, sin(angle) * distance * 0.72f)
                val width = size.width * base * (0.54f + settle * 0.18f)
                drawTintedAsset(asset, fragmentCenter, width, width, alpha * (0.74f - fragment * 0.08f), tint, if (reducedMotion) 0f else fragment * 37f + settle * 24f)
            }
        }
    }
}

private fun DrawScope.drawWarriorFinisher(
    asset: ImageBitmap,
    definition: SkillDefinition,
    center: Offset,
    local: Int,
    tint: Color,
    reducedMotion: Boolean,
) {
    val start = 105
    val end = if (definition.intensityTier >= 5) 390 else 315
    val progress = modularFraction(local, start, end)
    if (local !in start..end) return
    val enter = modularEaseOut(modularFraction(local, start, start + 75))
    val fade = 1f - modularFraction(local, end - 120, end)
    val alpha = minOf(enter, fade).coerceIn(0f, 1f) *
        floatArrayOf(0f, 0.20f, 0.28f, 0.36f, 0.46f)[definition.intensityTier - 1]
    val width = size.width * (0.30f + definition.intensityTier * 0.035f) *
        if (reducedMotion) 0.84f else 1f
    val rotation = if (reducedMotion) 0f else -8f + progress * 13f
    drawTintedAsset(
        asset = asset,
        center = center,
        width = width,
        height = width,
        alpha = alpha,
        tint = tint,
        rotation = rotation,
    )
}

private fun DrawScope.drawClassAuthoredLayers(
    primary: ImageBitmap,
    secondary: ImageBitmap,
    residual: ImageBitmap,
    impactAssets: List<ImageBitmap>,
    debrisAssets: List<ImageBitmap>,
    definition: SkillDefinition,
    signature: ClassVfxSignature,
    hitIndex: Int,
    nextHitGapMillis: Int?,
    center: Offset,
    local: Int,
    isFinal: Boolean,
    reducedMotion: Boolean,
) {
    val band = signature.growthBand
    val route = signature.tierVariant
    val profile = classVfxPathProfile(signature.path)
    // The latest catalogue hit lands at 900 ms. Ending every authored layer by local
    // 390 ms guarantees a fully clean 1,290 ms frame before the 1,300 ms VFX gate.
    val nonFinalEnd = minOf(260, (nextHitGapMillis ?: 200) + 60).coerceAtLeast(105)
    val end = if (isFinal) 390 else nonFinalEnd
    if (local !in -150 until end) return
    val motion = classPrimaryMotion(definition, hitIndex)
    val direction = if (reducedMotion) 1f else profile.mirror
    val pathStart = classAuthoredPathStart(motion, signature)
    val settle = if (reducedMotion) 1f else modularEaseOut(
        modularFraction(local, profile.primaryLeadMillis, profile.primarySettleMillis),
    )
    val offset = Offset(
        size.width * pathStart.x * (1f - settle),
        size.height * pathStart.y * (1f - settle),
    )
    val rotation = classPrimaryRotationDegrees(definition, hitIndex) +
        if (reducedMotion) 0f else profile.rotationOffsetDegrees

    // Rear plane: every non-slash skill owns a distinct anticipation/motion layer.
    // Higher bands extend its travel, not just its scale.
    if (isFinal && local in profile.secondaryLeadMillis until 230) {
        val overlayEnter = if (reducedMotion) 1f else modularEaseOut(
            modularFraction(local, profile.secondaryLeadMillis, profile.secondarySettleMillis),
        )
        val overlayFade = if (reducedMotion) {
            if (local in 0 until 175) 1f else 0f
        } else {
            1f - modularFraction(local, 35, 185)
        }
        val overlayAlpha = minOf(overlayEnter, overlayFade).coerceIn(0f, 1f) * (0.30f + band * 0.035f)
        val overlayWidth = size.width * (0.64f + band * 0.035f) * profile.secondaryScale
        val overlayAspect = secondary.height.toFloat() / secondary.width.toFloat()
        val depthTravel = if (reducedMotion) 1f else modularEaseOut(
            modularFraction(local, profile.secondaryLeadMillis, profile.secondarySettleMillis + 72),
        )
        val depthShift = Offset(
            size.width * pathStart.x * 0.48f * (1f - depthTravel),
            size.height * pathStart.y * 0.48f * (1f - depthTravel),
        )
        drawAuthoredAsset(
            asset = secondary,
            center = center + depthShift,
            width = overlayWidth,
            height = minOf(overlayWidth * overlayAspect, size.height * 0.72f),
            alpha = overlayAlpha,
            rotation = if (reducedMotion) 0f else rotation + profile.secondaryRotationDegrees,
            direction = -direction,
        )
    }

    // Mid plane: the actual attack body. One authored image is drawn exactly once.
    val primaryEnd = if (isFinal) 270 else minOf(225, nonFinalEnd)
    if (local in -150 until primaryEnd) {
        val enter = if (reducedMotion) 1f else modularEaseOut(modularFraction(local, -150, -5))
        val fade = if (reducedMotion) {
            if (local in 0 until 190) 1f else 0f
        } else {
            1f - modularFraction(local, if (isFinal) 90 else 55, primaryEnd)
        }
        val primaryAlpha = minOf(enter, fade).coerceIn(0f, 1f) * (0.72f + band * 0.045f)
        val primaryWidth = size.width * (0.48f + band * 0.025f) * profile.primaryScale
        val aspect = primary.height.toFloat() / primary.width.toFloat()
        val primaryHeight = minOf(primaryWidth * aspect, size.height * 0.54f)
        drawAuthoredAsset(
            asset = primary,
            center = center + offset,
            width = primaryWidth,
            height = primaryHeight,
            alpha = primaryAlpha,
            rotation = rotation,
            direction = direction,
        )
    }

    // Front contact plane: every class owns point, fracture and ring resources.
    // Consecutive hits alternate contact silhouettes; the final hit follows the semantic impact type.
    val impactLayer = classImpactLayerFor(definition, hitIndex)
    val contactEnd = if (isFinal) 295 else minOf(215, nonFinalEnd)
    if (local in profile.contactDelayMillis until contactEnd) {
        val contactFade = if (reducedMotion) {
            if (local < 190) 1f else 0f
        } else {
            1f - modularFraction(local, profile.contactDelayMillis + 48, contactEnd)
        }
        val contactAlpha = contactFade.coerceIn(0f, 1f) * (0.25f + band * 0.018f)
        val contactWidth = size.width * when (impactLayer) {
            ClassImpactLayer.POINT -> 0.20f + band * 0.012f
            ClassImpactLayer.FRACTURE -> 0.30f + band * 0.014f
            ClassImpactLayer.RING -> 0.36f + band * 0.018f
        } * profile.contactScale
        val pulse = if (reducedMotion) 1f else 0.78f + 0.22f * modularEaseOut(
            modularFraction(local, profile.contactDelayMillis, profile.contactDelayMillis + 78),
        )
        drawAuthoredAsset(
            asset = impactAssets[impactLayer.ordinal],
            center = center,
            width = contactWidth * pulse,
            height = contactWidth * pulse,
            alpha = contactAlpha,
            rotation = if (reducedMotion) 0f else floatArrayOf(-8f, 7f, 13f, -5f)[route],
            direction = direction,
        )
    }

    if (!isFinal) return

    // Foreground fragments begin at growth band 2 and travel independently from the contact plane.
    if (local in profile.debrisDelayMillis until 390) {
        val travel = if (reducedMotion) 0f else modularEaseOut(
            modularFraction(local, profile.debrisDelayMillis + 18, 390),
        )
        val debrisFade = if (reducedMotion) {
            if (local < 205) 1f else 0f
        } else {
            1f - modularFraction(local, 160, 390)
        }
        val debrisWidth = size.width * (0.38f + band * 0.035f)
        drawAuthoredAsset(
            asset = debrisAssets[impactLayer.ordinal],
            center = center + Offset(
                size.width * profile.debrisVector.x * travel,
                size.height * profile.debrisVector.y * travel,
            ),
            width = debrisWidth,
            height = debrisWidth,
            alpha = debrisFade.coerceIn(0f, 1f) * (0.20f + band * 0.026f),
            rotation = if (reducedMotion) 0f else floatArrayOf(18f, -22f, 31f, -14f)[route] * travel,
            direction = direction,
        )
    }

    // The final two bands add a class-owned residual plane, not another copy of the attack body.
    if (band >= 1 && local in profile.residualDelayMillis until 390) {
        val residualEnter = if (reducedMotion) 1f else modularEaseOut(
            modularFraction(local, profile.residualDelayMillis, profile.residualDelayMillis + 80),
        )
        val residualFade = if (reducedMotion) {
            if (local < 210) 1f else 0f
        } else {
            1f - modularFraction(local, 205, 390)
        }
        val residualWidth = size.width * (0.52f + band * 0.065f)
        val residualAspect = residual.height.toFloat() / residual.width.toFloat()
        drawAuthoredAsset(
            asset = residual,
            center = center + Offset(
                size.width * profile.residualVector.x,
                size.height * profile.residualVector.y,
            ),
            width = residualWidth,
            height = minOf(residualWidth * residualAspect, size.height * 0.72f),
            alpha = minOf(residualEnter, residualFade).coerceIn(0f, 1f) * (0.18f + band * 0.035f),
            rotation = if (reducedMotion) 0f else floatArrayOf(7f, -9f, 13f, -5f)[route],
            direction = direction,
        )
    }

    // Legendary skills close with a separate broken ring and still clear before the VFX gate.
    if (band >= 4 && local in 78 until 390 && impactLayer != ClassImpactLayer.RING) {
        val ringProgress = if (reducedMotion) 1f else modularEaseOut(modularFraction(local, 78, 205))
        val ringFade = if (reducedMotion) {
            if (local < 210) 1f else 0f
        } else {
            1f - modularFraction(local, 230, 390)
        }
        val ringWidth = size.width * 0.46f
        drawAuthoredAsset(
            asset = impactAssets[ClassImpactLayer.RING.ordinal],
            center = center,
            width = ringWidth * if (reducedMotion) 1f else 0.84f + ringProgress * 0.16f,
            height = ringWidth * if (reducedMotion) 1f else 0.84f + ringProgress * 0.16f,
            alpha = ringFade.coerceIn(0f, 1f) * 0.17f,
            rotation = if (reducedMotion) 0f else -9f + ringProgress * 15f,
            direction = direction,
        )
    }
}

private fun DrawScope.drawModularPrimary(
    asset: ImageBitmap,
    alternateCrossAsset: ImageBitmap,
    definition: SkillDefinition,
    plan: SemanticVfxPlan,
    choreography: SemanticHitChoreography,
    hitIndex: Int,
    center: Offset,
    local: Int,
    isFinal: Boolean,
    tint: Color,
    reducedMotion: Boolean,
) {
    if (
        drawRangerStarterPrimary(
            asset = asset,
            definition = definition,
            hitIndex = hitIndex,
            local = local,
            reducedMotion = reducedMotion,
        )
    ) return
    if (isSteelSlash(definition)) {
        val frame = steelSlashVfxFrame(local + definition.hitTimingsMillis.single(), reducedMotion)
        if (frame.visible) {
            drawSteelSlashAsset(
                asset = asset,
                center = Offset(size.width * 0.50f, size.height * 0.52f),
                width = size.width * 0.82f,
                height = size.height * 0.66f,
                alpha = frame.alpha,
                reveal = frame.reveal,
            )
        }
        return
    }
    if (isFierceDownwardStrike(definition)) {
        val frame = fierceDownwardStrikeVfxFrame(
            local + definition.hitTimingsMillis.single(),
            reducedMotion,
        )
        if (frame.visible) {
            drawFierceDownwardStrikeAsset(
                asset = asset,
                center = Offset(size.width * 0.50f, size.height * frame.centerYFraction),
                width = size.width * 0.58f * frame.scale,
                height = size.height * 0.72f * frame.scale,
                alpha = frame.alpha,
                reveal = frame.reveal,
            )
        }
        return
    }
    if (isChargingThrust(definition)) {
        val frame = chargingThrustVfxFrame(
            local + definition.hitTimingsMillis.single(),
            reducedMotion,
        )
        if (frame.visible) {
            val center = Offset(
                size.width * frame.xFraction,
                size.height * frame.yFraction,
            )
            drawTintedAsset(
                asset = asset,
                center = center,
                width = size.width * 0.78f * frame.scale,
                height = size.width * 0.42f * frame.scale,
                alpha = frame.alpha,
                tint = Color.White,
            )
        }
        return
    }
    if (isGroundImpact(definition)) {
        val frame = groundImpactVfxFrame(
            local + definition.hitTimingsMillis.single(),
            reducedMotion,
        )
        if (frame.visible) {
            drawTintedAsset(
                asset = asset,
                center = Offset(size.width * 0.50f, size.height * 0.72f),
                width = size.width * 0.70f * frame.scale,
                height = size.width * 0.42f * frame.scale,
                alpha = frame.alpha,
                tint = Color.White,
            )
        }
        return
    }
    if (isContinuousSlash(definition)) {
        val frame = continuousSlashVfxFrame(
            elapsedMillis = local + definition.hitTimingsMillis[hitIndex],
            hitIndex = hitIndex,
            reducedMotion = reducedMotion,
        )
        if (frame.visible) {
            val width = size.width * frame.scale
            drawUntintedAsset(
                asset = asset,
                center = Offset(size.width * frame.xFraction, size.height * frame.yFraction),
                width = width,
                height = width * asset.height.toFloat() / asset.width.toFloat(),
                alpha = frame.alpha,
                rotation = frame.rotationDegrees,
                direction = frame.direction,
            )
        }
        return
    }
    if (isHalfMoonSlash(definition)) {
        val elapsed = local + definition.hitTimingsMillis.single()
        val visible = if (reducedMotion) elapsed in 420 until 620 else elapsed in 250 until 760
        if (visible) {
            val enter = if (reducedMotion) 1f else modularEaseOut(modularFraction(elapsed, 250, 420))
            val fade = if (reducedMotion) 1f else 1f - modularFraction(elapsed, 560, 760)
            val alpha = minOf(enter, fade).coerceIn(0f, 1f) * if (reducedMotion) 0.48f else 0.86f
            val width = size.width * if (reducedMotion) 0.78f else 0.72f + 0.06f * enter
            drawUntintedAsset(
                asset = asset,
                center = Offset(size.width * 0.50f, size.height * 0.59f),
                width = width,
                height = width * asset.height.toFloat() / asset.width.toFloat(),
                alpha = alpha,
                rotation = -2f,
            )
        }
        return
    }
    if (isArmorShatter(definition)) {
        val elapsed = local + definition.hitTimingsMillis.single()
        val visible = if (reducedMotion) elapsed in 420 until 620 else elapsed in 300 until 760
        if (visible) {
            val enter = if (reducedMotion) 1f else modularEaseOut(modularFraction(elapsed, 300, 420))
            val fade = if (reducedMotion) 1f else 1f - modularFraction(elapsed, 560, 760)
            val alpha = minOf(enter, fade).coerceIn(0f, 1f) * if (reducedMotion) 0.48f else 0.90f
            val scale = if (reducedMotion) 0.60f else 0.50f + 0.14f * enter
            drawUntintedAsset(
                asset = asset,
                center = Offset(size.width * 0.50f, size.height * 0.65f),
                width = size.width * scale,
                height = size.width * scale * asset.height.toFloat() / asset.width.toFloat(),
                alpha = alpha,
                rotation = 0f,
            )
        }
        return
    }
    if (isFrontlineBreakthrough(definition)) {
        val elapsed = local + definition.hitTimingsMillis.single()
        val frame = chargingThrustVfxFrame(elapsed, reducedMotion)
        if (frame.visible) {
            val widenedScale = frame.scale * 1.08f
            drawTintedAsset(
                asset = asset,
                center = Offset(size.width * frame.xFraction, size.height * frame.yFraction),
                width = size.width * 0.84f * widenedScale,
                height = size.width * 0.46f * widenedScale,
                alpha = frame.alpha * 0.92f,
                tint = Color.White,
            )
        }
        return
    }
    if (isStoneDustBurst(definition)) {
        val elapsed = local + definition.hitTimingsMillis.single()
        val frame = groundImpactVfxFrame(elapsed, reducedMotion)
        if (frame.visible) {
            val alpha = frame.alpha * if (reducedMotion) 0.76f else 0.88f
            drawTintedAsset(
                asset = asset,
                center = Offset(size.width * 0.50f, size.height * 0.68f),
                width = size.width * 0.78f * frame.scale,
                height = size.width * 0.62f * frame.scale,
                alpha = alpha,
                tint = Color(0xFFD3AE78),
                rotation = if (reducedMotion) 0f else -6f + modularFraction(elapsed, 320, 720) * 12f,
            )
            drawTintedAsset(
                asset = asset,
                center = Offset(size.width * 0.50f, size.height * 0.70f),
                width = size.width * 0.54f * frame.scale,
                height = size.width * 0.34f * frame.scale,
                alpha = alpha * 0.62f,
                tint = Color(0xFF8E714D),
                rotation = if (reducedMotion) 0f else 12f,
                direction = -1f,
            )
        }
        return
    }
    if (isTripleSlash(definition)) {
        val elapsed = local + definition.hitTimingsMillis[hitIndex]
        val frame = starterWarriorComboFrame(
            elapsedMillis = elapsed,
            hitIndex = hitIndex,
            hitTimingsMillis = definition.hitTimingsMillis,
            reducedMotion = reducedMotion,
            stronger = true,
        )
        if (frame.visible) {
            val width = size.width * frame.scale
            drawUntintedAsset(
                asset = asset,
                center = Offset(size.width * frame.xFraction, size.height * frame.yFraction),
                width = width,
                height = width * asset.height.toFloat() / asset.width.toFloat(),
                alpha = frame.alpha,
                rotation = frame.rotationDegrees,
                direction = frame.direction,
            )
        }
        return
    }
    if (isBattlefieldCleave(definition)) {
        val elapsed = local + definition.hitTimingsMillis.single()
        val visible = if (reducedMotion) elapsed in 420 until 620 else elapsed in 270 until 780
        if (visible) {
            val enter = if (reducedMotion) 1f else modularEaseOut(modularFraction(elapsed, 270, 420))
            val fade = if (reducedMotion) 1f else 1f - modularFraction(elapsed, 590, 780)
            val alpha = minOf(enter, fade).coerceIn(0f, 1f) * if (reducedMotion) 0.48f else 0.90f
            val width = size.width * (if (reducedMotion) 0.80f else 0.68f + 0.16f * enter)
            drawUntintedAsset(
                asset = asset,
                center = Offset(size.width * 0.50f, size.height * 0.59f),
                width = width,
                height = width * asset.height.toFloat() / asset.width.toFloat(),
                alpha = alpha,
                rotation = -9f,
            )
        }
        return
    }
    if (isIronWallSmash(definition)) {
        val elapsed = local + definition.hitTimingsMillis.single()
        val frame = fierceDownwardStrikeVfxFrame(elapsed, reducedMotion)
        if (frame.visible) {
            drawFierceDownwardStrikeAsset(
                asset = asset,
                center = Offset(size.width * 0.50f, size.height * (frame.centerYFraction + 0.04f)),
                width = size.width * 0.52f * frame.scale,
                height = size.height * 0.66f * frame.scale,
                alpha = frame.alpha * 0.82f,
                reveal = frame.reveal,
            )
        }
        return
    }
    if (isFuriousAdvance(definition)) {
        val elapsed = local + definition.hitTimingsMillis.single()
        val frame = chargingThrustVfxFrame(elapsed, reducedMotion)
        if (frame.visible) {
            val scale = frame.scale * 1.14f
            drawTintedAsset(
                asset = asset,
                center = Offset(size.width * frame.xFraction, size.height * frame.yFraction),
                width = size.width * 0.88f * scale,
                height = size.width * 0.48f * scale,
                alpha = frame.alpha,
                tint = Color.White,
            )
        }
        return
    }
    if (isEarthFissure(definition)) {
        val elapsed = local + definition.hitTimingsMillis.single()
        val frame = groundImpactVfxFrame(elapsed, reducedMotion)
        if (frame.visible) {
            drawTintedAsset(
                asset = asset,
                center = Offset(size.width * 0.50f, size.height * 0.73f),
                width = size.width * 0.78f * frame.scale,
                height = size.width * 0.47f * frame.scale,
                alpha = frame.alpha * 0.84f,
                tint = Color.White,
            )
        }
        return
    }
    if (isBeastFrenzy(definition)) {
        val elapsed = local + definition.hitTimingsMillis[hitIndex]
        val frame = starterWarriorComboFrame(
            elapsedMillis = elapsed,
            hitIndex = hitIndex,
            hitTimingsMillis = definition.hitTimingsMillis,
            reducedMotion = reducedMotion,
            stronger = true,
        )
        if (frame.visible) {
            val stage = hitIndex.mod(5)
            val rotations = floatArrayOf(-31f, 27f, -15f, 35f, -4f)
            val xs = floatArrayOf(0.39f, 0.61f, 0.45f, 0.57f, 0.50f)
            val ys = floatArrayOf(0.51f, 0.65f, 0.67f, 0.50f, 0.59f)
            val width = size.width * frame.scale * if (hitIndex == definition.hitTimingsMillis.lastIndex) 1.06f else 0.86f
            drawUntintedAsset(
                asset = asset,
                center = Offset(size.width * xs[stage], size.height * ys[stage]),
                width = width,
                height = width * asset.height.toFloat() / asset.width.toFloat(),
                alpha = frame.alpha,
                rotation = rotations[stage],
                direction = frame.direction,
            )
        }
        return
    }
    if (isBloodWindSlash(definition)) {
        val elapsed = local + definition.hitTimingsMillis.single()
        val visible = if (reducedMotion) elapsed in 420 until 620 else elapsed in 255 until 800
        if (visible) {
            val enter = if (reducedMotion) 1f else modularEaseOut(modularFraction(elapsed, 255, 420))
            val fade = if (reducedMotion) 1f else 1f - modularFraction(elapsed, 600, 800)
            val alpha = minOf(enter, fade).coerceIn(0f, 1f) * if (reducedMotion) 0.48f else 0.92f
            val width = size.width * (if (reducedMotion) 0.83f else 0.70f + 0.17f * enter)
            drawUntintedAsset(
                asset = asset,
                center = Offset(size.width * 0.50f, size.height * 0.59f),
                width = width,
                height = width * asset.height.toFloat() / asset.width.toFloat(),
                alpha = alpha,
                rotation = -12f,
                direction = -1f,
            )
        }
        return
    }
    if (isHelmetCrusher(definition)) {
        val elapsed = local + definition.hitTimingsMillis.single()
        val frame = fierceDownwardStrikeVfxFrame(elapsed, reducedMotion)
        if (frame.visible) {
            drawFierceDownwardStrikeAsset(
                asset = asset,
                center = Offset(size.width * 0.50f, size.height * (frame.centerYFraction + 0.06f)),
                width = size.width * 0.55f * frame.scale,
                height = size.height * 0.69f * frame.scale,
                alpha = frame.alpha * 0.84f,
                reveal = frame.reveal,
            )
        }
        return
    }
    if (isCastleBreakerCharge(definition)) {
        val elapsed = local + definition.hitTimingsMillis.single()
        val frame = chargingThrustVfxFrame(elapsed, reducedMotion)
        if (frame.visible) {
            val scale = frame.scale * 1.20f
            drawTintedAsset(
                asset = asset,
                center = Offset(size.width * frame.xFraction, size.height * frame.yFraction),
                width = size.width * 0.92f * scale,
                height = size.width * 0.50f * scale,
                alpha = frame.alpha,
                tint = Color.White,
            )
        }
        return
    }
    if (isRockEruption(definition)) {
        val elapsed = local + definition.hitTimingsMillis.single()
        val frame = groundImpactVfxFrame(elapsed, reducedMotion)
        if (frame.visible) {
            val rise = if (reducedMotion) 1f else modularEaseOut(modularFraction(elapsed, 320, 470))
            drawTintedAsset(
                asset = asset,
                center = Offset(size.width * 0.50f, size.height * (0.82f - 0.17f * rise)),
                width = size.width * 0.72f * frame.scale,
                height = size.width * 0.48f * frame.scale,
                alpha = frame.alpha * 0.72f,
                tint = Color.White,
            )
        }
        return
    }
    if (isFiveStrike(definition)) {
        val elapsed = local + definition.hitTimingsMillis[hitIndex]
        val frame = starterWarriorComboFrame(elapsed, hitIndex, definition.hitTimingsMillis, reducedMotion, stronger = true)
        if (frame.visible) {
            val stage = hitIndex.mod(5)
            val rotations = floatArrayOf(-34f, 31f, -18f, 23f, -2f)
            val xs = floatArrayOf(0.38f, 0.62f, 0.43f, 0.58f, 0.50f)
            val ys = floatArrayOf(0.51f, 0.66f, 0.67f, 0.52f, 0.59f)
            val width = size.width * frame.scale * if (hitIndex == definition.hitTimingsMillis.lastIndex) 1.10f else 0.88f
            drawUntintedAsset(
                asset = asset,
                center = Offset(size.width * xs[stage], size.height * ys[stage]),
                width = width,
                height = width * asset.height.toFloat() / asset.width.toFloat(),
                alpha = frame.alpha,
                rotation = rotations[stage],
                direction = frame.direction,
            )
        }
        return
    }
    if (isSwordLightSever(definition)) {
        val elapsed = local + definition.hitTimingsMillis.single()
        val visible = if (reducedMotion) elapsed in 420 until 630 else elapsed in 230 until 830
        if (visible) {
            val enter = if (reducedMotion) 1f else modularEaseOut(modularFraction(elapsed, 230, 420))
            val fade = if (reducedMotion) 1f else 1f - modularFraction(elapsed, 620, 830)
            val alpha = minOf(enter, fade).coerceIn(0f, 1f) * if (reducedMotion) 0.48f else 0.95f
            val width = size.width * (if (reducedMotion) 0.86f else 0.70f + 0.22f * enter)
            drawUntintedAsset(asset, Offset(size.width * 0.50f, size.height * 0.58f), width, width * asset.height.toFloat() / asset.width, alpha, -7f)
        }
        return
    }
    if (isBattleAxeDescent(definition)) {
        val elapsed = local + definition.hitTimingsMillis.single()
        val frame = fierceDownwardStrikeVfxFrame(elapsed, reducedMotion)
        if (frame.visible) {
            drawFierceDownwardStrikeAsset(asset, Offset(size.width * 0.50f, size.height * (frame.centerYFraction + 0.07f)), size.width * 0.59f * frame.scale, size.height * 0.73f * frame.scale, frame.alpha * 0.88f, frame.reveal)
        }
        return
    }
    if (isWedgeBreakthrough(definition)) {
        val elapsed = local + definition.hitTimingsMillis.single()
        val frame = chargingThrustVfxFrame(elapsed, reducedMotion)
        if (frame.visible) {
            val scale = frame.scale * 1.25f
            drawTintedAsset(asset, Offset(size.width * frame.xFraction, size.height * frame.yFraction), size.width * 0.96f * scale, size.width * 0.52f * scale, frame.alpha, Color.White)
        }
        return
    }
    if (isSeismicWave(definition)) {
        val elapsed = local + definition.hitTimingsMillis.single()
        val frame = groundImpactVfxFrame(elapsed, reducedMotion)
        if (frame.visible) {
            val wave = if (reducedMotion) 1f else modularEaseOut(modularFraction(elapsed, 340, 620))
            drawTintedAsset(
                asset = asset,
                center = Offset(size.width * 0.50f, size.height * 0.73f),
                width = size.width * (0.48f + 0.45f * wave),
                height = size.width * (0.18f + 0.14f * wave),
                alpha = frame.alpha * (1f - 0.28f * wave),
                tint = Color.White,
            )
        }
        return
    }
    if (isBerserkerChainSlash(definition)) {
        val elapsed = local + definition.hitTimingsMillis[hitIndex]
        val frame = starterWarriorComboFrame(elapsed, hitIndex, definition.hitTimingsMillis, reducedMotion, stronger = true)
        if (frame.visible) {
            val stage = hitIndex.mod(5)
            val rotations = floatArrayOf(-38f, 33f, -20f, 28f, -1f)
            val xs = floatArrayOf(0.37f, 0.63f, 0.42f, 0.60f, 0.50f)
            val ys = floatArrayOf(0.50f, 0.67f, 0.68f, 0.50f, 0.58f)
            val width = size.width * frame.scale * if (hitIndex == definition.hitTimingsMillis.lastIndex) 1.16f else 0.92f
            drawUntintedAsset(asset, Offset(size.width * xs[stage], size.height * ys[stage]), width, width * asset.height.toFloat() / asset.width, frame.alpha, rotations[stage], frame.direction)
        }
        return
    }
    if (isRotatingSlash(definition)) {
        val elapsed = local + definition.hitTimingsMillis.single()
        val visible = if (reducedMotion) elapsed in 420 until 640 else elapsed in 240 until 850
        if (visible) {
            val enter = if (reducedMotion) 1f else modularEaseOut(modularFraction(elapsed, 240, 420))
            val fade = if (reducedMotion) 1f else 1f - modularFraction(elapsed, 640, 850)
            val alpha = minOf(enter, fade).coerceIn(0f, 1f) * if (reducedMotion) 0.48f else 0.91f
            val width = size.width * (if (reducedMotion) 0.74f else 0.58f + 0.24f * enter)
            drawUntintedAsset(asset, Offset(size.width * 0.50f, size.height * 0.60f), width, width * asset.height.toFloat() / asset.width, alpha, if (reducedMotion) 0f else -24f + 46f * enter)
        }
        return
    }
    if (isGiantHammer(definition)) {
        val elapsed = local + definition.hitTimingsMillis.single()
        val frame = fierceDownwardStrikeVfxFrame(elapsed, reducedMotion)
        if (frame.visible) {
            drawFierceDownwardStrikeAsset(asset, Offset(size.width * 0.50f, size.height * (frame.centerYFraction + 0.08f)), size.width * 0.64f * frame.scale, size.height * 0.78f * frame.scale, frame.alpha * 0.90f, frame.reveal)
        }
        return
    }
    if (isIroncladCharge(definition)) {
        val elapsed = local + definition.hitTimingsMillis.single()
        val frame = chargingThrustVfxFrame(elapsed, reducedMotion)
        if (frame.visible) {
            val scale = frame.scale * 1.30f
            drawTintedAsset(asset, Offset(size.width * frame.xFraction, size.height * frame.yFraction), size.width * 1.00f * scale, size.width * 0.54f * scale, frame.alpha, Color.White)
        }
        return
    }
    if (isFaultShatter(definition)) {
        val elapsed = local + definition.hitTimingsMillis.single()
        val frame = groundImpactVfxFrame(elapsed, reducedMotion)
        if (frame.visible) {
            drawTintedAsset(asset, Offset(size.width * 0.50f, size.height * 0.72f), size.width * 0.90f * frame.scale, size.width * 0.53f * frame.scale, frame.alpha * 0.88f, Color.White)
        }
        return
    }
    if (isStormFrenzy(definition)) {
        val elapsed = local + definition.hitTimingsMillis[hitIndex]
        val frame = starterWarriorComboFrame(elapsed, hitIndex, definition.hitTimingsMillis, reducedMotion, stronger = true)
        if (frame.visible) {
            val stage = hitIndex.mod(6)
            val rotations = floatArrayOf(-41f, 36f, -23f, 29f, -11f, 3f)
            val xs = floatArrayOf(0.36f, 0.64f, 0.41f, 0.60f, 0.45f, 0.50f)
            val ys = floatArrayOf(0.49f, 0.68f, 0.69f, 0.50f, 0.62f, 0.58f)
            val width = size.width * frame.scale * if (hitIndex == definition.hitTimingsMillis.lastIndex) 1.20f else 0.94f
            drawUntintedAsset(asset, Offset(size.width * xs[stage], size.height * ys[stage]), width, width * asset.height.toFloat() / asset.width, frame.alpha, rotations[stage], frame.direction)
        }
        return
    }
    if (isLionSlash(definition)) {
        val elapsed = local + definition.hitTimingsMillis.single(); val visible = if (reducedMotion) elapsed in 420 until 650 else elapsed in 220 until 860
        if (visible) { val enter = if (reducedMotion) 1f else modularEaseOut(modularFraction(elapsed,220,420)); val fade = if(reducedMotion) 1f else 1f-modularFraction(elapsed,640,860); val alpha=minOf(enter,fade).coerceIn(0f,1f)*(if(reducedMotion) .48f else .96f); val width=size.width*(if(reducedMotion).88f else .70f+.24f*enter); drawUntintedAsset(asset,Offset(size.width*.50f,size.height*.58f),width,width*asset.height.toFloat()/asset.width,alpha,-5f) }
        return
    }
    if (isBoneCrushingBlow(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single(); val frame=fierceDownwardStrikeVfxFrame(elapsed,reducedMotion)
        if(frame.visible) drawFierceDownwardStrikeAsset(asset,Offset(size.width*.50f,size.height*(frame.centerYFraction+.09f)),size.width*.67f*frame.scale,size.height*.81f*frame.scale,frame.alpha*.92f,frame.reveal)
        return
    }
    if (isLightningBreakthrough(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single(); val frame=chargingThrustVfxFrame(elapsed,reducedMotion)
        if(frame.visible){val scale=frame.scale*1.34f;drawTintedAsset(asset,Offset(size.width*frame.xFraction,size.height*frame.yFraction),size.width*1.02f*scale,size.width*.56f*scale,frame.alpha,Color(0xFFDDF7FF))}
        return
    }
    if (isMountainFist(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val frame=groundImpactVfxFrame(elapsed,reducedMotion)
        if(frame.visible){val rise=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,300,480));drawTintedAsset(asset,Offset(size.width*.50f,size.height*(.84f-.20f*rise)),size.width*.78f*frame.scale,size.width*.52f*frame.scale,frame.alpha*.76f,Color.White)}
        return
    }
    if (isCrimsonCombo(definition)) {
        val elapsed=local+definition.hitTimingsMillis[hitIndex];val frame=starterWarriorComboFrame(elapsed,hitIndex,definition.hitTimingsMillis,reducedMotion,true)
        if(frame.visible){val s=hitIndex.mod(4);val ro=floatArrayOf(-43f,38f,-25f,3f);val xs=floatArrayOf(.36f,.64f,.43f,.50f);val ys=floatArrayOf(.49f,.68f,.67f,.58f);val width=size.width*frame.scale*(if(hitIndex==definition.hitTimingsMillis.lastIndex)1.24f else .96f);drawUntintedAsset(asset,Offset(size.width*xs[s],size.height*ys[s]),width,width*asset.height.toFloat()/asset.width,frame.alpha,ro[s],frame.direction)}
        return
    }
    if (isValiantCleave(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val visible=if(reducedMotion)elapsed in 420 until 660 else elapsed in 210 until 880
        if(visible){val enter=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,210,420));val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,650,880);val alpha=minOf(enter,fade).coerceIn(0f,1f)*(if(reducedMotion).48f else .97f);val width=size.width*(if(reducedMotion).90f else .72f+.25f*enter);drawUntintedAsset(asset,Offset(size.width*.50f,size.height*.58f),width,width*asset.height.toFloat()/asset.width,alpha,-4f)}
        return
    }
    if (isGateDestroyer(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val frame=fierceDownwardStrikeVfxFrame(elapsed,reducedMotion)
        if(frame.visible)drawFierceDownwardStrikeAsset(asset,Offset(size.width*.50f,size.height*(frame.centerYFraction+.10f)),size.width*.70f*frame.scale,size.height*.84f*frame.scale,frame.alpha*.94f,frame.reveal)
        return
    }
    if (isChariotCharge(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val frame=chargingThrustVfxFrame(elapsed,reducedMotion)
        if(frame.visible){val scale=frame.scale*1.38f;drawTintedAsset(asset,Offset(size.width*frame.xFraction,size.height*frame.yFraction),size.width*1.06f*scale,size.width*.58f*scale,frame.alpha,Color.White)}
        return
    }
    if (isEarthRoar(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val frame=groundImpactVfxFrame(elapsed,reducedMotion)
        if(frame.visible){val wave=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,330,650));drawTintedAsset(asset,Offset(size.width*.50f,size.height*.72f),size.width*(.54f+.46f*wave),size.width*(.22f+.15f*wave),frame.alpha*(1f-.24f*wave),Color.White)}
        return
    }
    if (isSevenSlash(definition)) {
        val elapsed=local+definition.hitTimingsMillis[hitIndex];val frame=starterWarriorComboFrame(elapsed,hitIndex,definition.hitTimingsMillis,reducedMotion,true)
        if(frame.visible){val s=hitIndex.mod(7);val ro=floatArrayOf(-45f,40f,-28f,32f,-16f,21f,1f);val xs=floatArrayOf(.34f,.66f,.40f,.62f,.43f,.58f,.50f);val ys=floatArrayOf(.48f,.69f,.70f,.49f,.65f,.52f,.58f);val width=size.width*frame.scale*(if(hitIndex==definition.hitTimingsMillis.lastIndex)1.28f else .96f);drawUntintedAsset(asset,Offset(size.width*xs[s],size.height*ys[s]),width,width*asset.height.toFloat()/asset.width,frame.alpha,ro[s],frame.direction)}
        return
    }
    if (isSteelWhirlwind(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val visible=if(reducedMotion)elapsed in 420 until 680 else elapsed in 190 until 900
        if(visible){val enter=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,190,420));val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,670,900);val alpha=minOf(enter,fade).coerceIn(0f,1f)*(if(reducedMotion).48f else .98f);val width=size.width*(if(reducedMotion).78f else .58f+.28f*enter);drawUntintedAsset(asset,Offset(size.width*.50f,size.height*.60f),width,width*asset.height.toFloat()/asset.width,alpha,if(reducedMotion)0f else -34f+68f*enter)}
        return
    }
    if (isSmashToPieces(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val visible=if(reducedMotion)elapsed in 420 until 650 else elapsed in 270 until 820
        if(visible){val enter=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,270,420));val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,610,820);val alpha=minOf(enter,fade).coerceIn(0f,1f)*(if(reducedMotion).48f else .94f);val scale=if(reducedMotion).64f else .52f+.18f*enter;drawUntintedAsset(asset,Offset(size.width*.50f,size.height*.65f),size.width*scale,size.width*scale*asset.height.toFloat()/asset.width,alpha)}
        return
    }
    if (isIndomitableAdvance(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val frame=chargingThrustVfxFrame(elapsed,reducedMotion)
        if(frame.visible){val scale=frame.scale*1.42f;drawTintedAsset(asset,Offset(size.width*frame.xFraction,size.height*frame.yFraction),size.width*1.10f*scale,size.width*.60f*scale,frame.alpha,Color.White)}
        return
    }
    if (isRiftExplosion(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val frame=groundImpactVfxFrame(elapsed,reducedMotion)
        if(frame.visible)drawTintedAsset(asset,Offset(size.width*.50f,size.height*.72f),size.width*.98f*frame.scale,size.width*.58f*frame.scale,frame.alpha*.91f,Color.White)
        return
    }
    if (isFrenzyBlades(definition)) {
        val elapsed=local+definition.hitTimingsMillis[hitIndex];val frame=starterWarriorComboFrame(elapsed,hitIndex,definition.hitTimingsMillis,reducedMotion,true)
        if(frame.visible){val s=hitIndex.mod(6);val ro=floatArrayOf(-47f,42f,-30f,35f,-18f,2f);val xs=floatArrayOf(.33f,.67f,.39f,.63f,.43f,.50f);val ys=floatArrayOf(.47f,.70f,.71f,.48f,.66f,.58f);val width=size.width*frame.scale*(if(hitIndex==definition.hitTimingsMillis.lastIndex)1.32f else .99f);drawUntintedAsset(asset,Offset(size.width*xs[s],size.height*ys[s]),width,width*asset.height.toFloat()/asset.width,frame.alpha,ro[s],frame.direction)}
        return
    }
    if (isKingdomSever(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val visible=if(reducedMotion)elapsed in 420 until 690 else elapsed in 180 until 920
        if(visible){val enter=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,180,420));val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,680,920);val alpha=minOf(enter,fade).coerceIn(0f,1f)*(if(reducedMotion).48f else .98f);val width=size.width*(if(reducedMotion).92f else .72f+.28f*enter);drawUntintedAsset(asset,Offset(size.width*.50f,size.height*.58f),width,width*asset.height.toFloat()/asset.width,alpha,-3f)}
        return
    }
    if (isThunderDownstrike(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val frame=fierceDownwardStrikeVfxFrame(elapsed,reducedMotion)
        if(frame.visible)drawFierceDownwardStrikeAsset(asset,Offset(size.width*.50f,size.height*(frame.centerYFraction+.10f)),size.width*.73f*frame.scale,size.height*.87f*frame.scale,frame.alpha*.96f,frame.reveal)
        return
    }
    if (isVanguardBreakthrough(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val frame=chargingThrustVfxFrame(elapsed,reducedMotion)
        if(frame.visible){val scale=frame.scale*1.46f;drawTintedAsset(asset,Offset(size.width*frame.xFraction,size.height*frame.yFraction),size.width*1.12f*scale,size.width*.61f*scale,frame.alpha,Color.White)}
        return
    }
    if (isLeylineEruption(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val frame=groundImpactVfxFrame(elapsed,reducedMotion)
        if(frame.visible){val rise=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,280,490));drawTintedAsset(asset,Offset(size.width*.50f,size.height*(.86f-.22f*rise)),size.width*.84f*frame.scale,size.width*.56f*frame.scale,frame.alpha*.80f,Color.White)}
        return
    }
    if (isBloodFrenzy(definition)) {
        val elapsed=local+definition.hitTimingsMillis[hitIndex];val frame=starterWarriorComboFrame(elapsed,hitIndex,definition.hitTimingsMillis,reducedMotion,true)
        if(frame.visible){val s=hitIndex.mod(7);val ro=floatArrayOf(-49f,44f,-32f,37f,-21f,25f,2f);val xs=floatArrayOf(.32f,.68f,.38f,.64f,.42f,.59f,.50f);val ys=floatArrayOf(.46f,.71f,.72f,.47f,.67f,.51f,.58f);val width=size.width*frame.scale*(if(hitIndex==definition.hitTimingsMillis.lastIndex)1.36f else 1.01f);drawUntintedAsset(asset,Offset(size.width*xs[s],size.height*ys[s]),width,width*asset.height.toFloat()/asset.width,frame.alpha,ro[s],frame.direction)}
        return
    }
    if (isSwordEmperorHalfMoon(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val visible=if(reducedMotion)elapsed in 420 until 700 else elapsed in 165 until 940
        if(visible){val enter=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,165,420));val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,690,940);val alpha=minOf(enter,fade).coerceIn(0f,1f)*(if(reducedMotion).48f else .99f);val width=size.width*(if(reducedMotion).94f else .74f+.29f*enter);drawUntintedAsset(asset,Offset(size.width*.50f,size.height*.59f),width,width*asset.height.toFloat()/asset.width,alpha,-2f)}
        return
    }
    if (isCastleCrushingGreatSmash(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val frame=fierceDownwardStrikeVfxFrame(elapsed,reducedMotion)
        if(frame.visible)drawFierceDownwardStrikeAsset(asset,Offset(size.width*.50f,size.height*(frame.centerYFraction+.11f)),size.width*.77f*frame.scale,size.height*.91f*frame.scale,frame.alpha*.98f,frame.reveal)
        return
    }
    if (isUnbeatenCharge(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val frame=chargingThrustVfxFrame(elapsed,reducedMotion)
        if(frame.visible){val scale=frame.scale*1.50f;drawTintedAsset(asset,Offset(size.width*frame.xFraction,size.height*frame.yFraction),size.width*1.16f*scale,size.width*.63f*scale,frame.alpha,Color.White)}
        return
    }
    if (isContinentalFissure(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val frame=groundImpactVfxFrame(elapsed,reducedMotion)
        if(frame.visible){val spread=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,300,650));drawTintedAsset(asset,Offset(size.width*.50f,size.height*.73f),size.width*(.76f+.30f*spread)*frame.scale,size.width*(.42f+.18f*spread)*frame.scale,frame.alpha*.93f,Color.White)}
        return
    }
    if (isHotWindChainSlash(definition)) {
        val elapsed=local+definition.hitTimingsMillis[hitIndex];val frame=starterWarriorComboFrame(elapsed,hitIndex,definition.hitTimingsMillis,reducedMotion,true)
        if(frame.visible){val s=hitIndex.mod(7);val ro=floatArrayOf(-51f,46f,-34f,39f,-23f,27f,3f);val xs=floatArrayOf(.31f,.69f,.37f,.65f,.41f,.60f,.50f);val ys=floatArrayOf(.45f,.72f,.73f,.46f,.68f,.50f,.58f);val width=size.width*frame.scale*(if(hitIndex==definition.hitTimingsMillis.lastIndex)1.40f else 1.03f);drawTintedAsset(asset,Offset(size.width*xs[s],size.height*ys[s]),width,width*asset.height.toFloat()/asset.width,frame.alpha,Color(0xFFBDEFE5),ro[s],frame.direction)}
        return
    }
    if (isTitanCleave(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val visible=if(reducedMotion)elapsed in 420 until 710 else elapsed in 155 until 955
        if(visible){val enter=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,155,420));val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,700,955);val alpha=minOf(enter,fade).coerceIn(0f,1f)*(if(reducedMotion).48f else .99f);val width=size.width*(if(reducedMotion).96f else .76f+.30f*enter);drawUntintedAsset(asset,Offset(size.width*.50f,size.height*.58f),width,width*asset.height.toFloat()/asset.width,alpha,-1f)}
        return
    }
    if (isCometSmash(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val visible=if(reducedMotion)elapsed in 420 until 690 else elapsed in 190 until 930
        if(visible){val enter=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,190,420));val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,680,930);val alpha=minOf(enter,fade).coerceIn(0f,1f)*(if(reducedMotion).48f else .96f);val h=size.height*(if(reducedMotion).66f else .50f+.28f*enter);drawTintedAsset(asset,Offset(size.width*.50f,size.height*(.34f+.27f*enter)),size.width*.62f,h,alpha,Color(0xFFD9D8FF))}
        return
    }
    if (isKingsAdvance(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val frame=chargingThrustVfxFrame(elapsed,reducedMotion)
        if(frame.visible){val scale=frame.scale*1.54f;drawTintedAsset(asset,Offset(size.width*frame.xFraction,size.height*frame.yFraction),size.width*1.18f*scale,size.width*.64f*scale,frame.alpha,Color.White)}
        return
    }
    if (isMountainCollapse(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val frame=groundImpactVfxFrame(elapsed,reducedMotion)
        if(frame.visible){val collapse=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,275,640));drawTintedAsset(asset,Offset(size.width*.50f,size.height*.73f),size.width*(.80f+.32f*collapse)*frame.scale,size.width*(.44f+.20f*collapse)*frame.scale,frame.alpha*.95f,Color.White)}
        return
    }
    if (isHundredBattleFrenzy(definition)) {
        val elapsed=local+definition.hitTimingsMillis[hitIndex];val frame=starterWarriorComboFrame(elapsed,hitIndex,definition.hitTimingsMillis,reducedMotion,true)
        if(frame.visible){val s=hitIndex.mod(7);val ro=floatArrayOf(-53f,48f,-36f,41f,-25f,29f,4f);val xs=floatArrayOf(.30f,.70f,.36f,.66f,.40f,.61f,.50f);val ys=floatArrayOf(.44f,.73f,.74f,.45f,.69f,.49f,.58f);val width=size.width*frame.scale*(if(hitIndex==definition.hitTimingsMillis.lastIndex)1.44f else 1.05f);drawUntintedAsset(asset,Offset(size.width*xs[s],size.height*ys[s]),width,width*asset.height.toFloat()/asset.width,frame.alpha,ro[s],frame.direction)}
        return
    }
    if (isWarGodBlade(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val visible=if(reducedMotion)elapsed in 420 until 720 else elapsed in 145 until 970
        if(visible){val enter=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,145,420));val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,710,970);val alpha=minOf(enter,fade).coerceIn(0f,1f)*(if(reducedMotion).48f else .99f);val width=size.width*(if(reducedMotion).98f else .78f+.31f*enter);drawUntintedAsset(asset,Offset(size.width*.50f,size.height*.58f),width,width*asset.height.toFloat()/asset.width,alpha,0f)}
        return
    }
    if (isDragonBoneShatter(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val visible=if(reducedMotion)elapsed in 420 until 690 else elapsed in 245 until 910
        if(visible){val enter=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,245,420));val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,680,910);val alpha=minOf(enter,fade).coerceIn(0f,1f)*(if(reducedMotion).48f else .98f);val scale=if(reducedMotion).72f else .55f+.22f*enter;drawUntintedAsset(asset,Offset(size.width*.50f,size.height*.64f),size.width*scale,size.width*scale*asset.height.toFloat()/asset.width,alpha)}
        return
    }
    if (isIronBloodBreakthrough(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val frame=chargingThrustVfxFrame(elapsed,reducedMotion)
        if(frame.visible){val scale=frame.scale*1.58f;drawTintedAsset(asset,Offset(size.width*frame.xFraction,size.height*frame.yFraction),size.width*1.20f*scale,size.width*.65f*scale,frame.alpha,Color.White)}
        return
    }
    if (isEarthRage(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val frame=groundImpactVfxFrame(elapsed,reducedMotion)
        if(frame.visible){val rage=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,260,655));drawTintedAsset(asset,Offset(size.width*.50f,size.height*.72f),size.width*(.82f+.34f*rage)*frame.scale,size.width*(.46f+.21f*rage)*frame.scale,frame.alpha*.96f,Color.White)}
        return
    }
    if (isTyrantCombo(definition)) {
        val elapsed=local+definition.hitTimingsMillis[hitIndex];val frame=starterWarriorComboFrame(elapsed,hitIndex,definition.hitTimingsMillis,reducedMotion,true)
        if(frame.visible){val s=hitIndex.mod(6);val ro=floatArrayOf(-55f,50f,-38f,43f,-27f,5f);val xs=floatArrayOf(.29f,.71f,.35f,.67f,.39f,.50f);val ys=floatArrayOf(.43f,.74f,.75f,.44f,.70f,.58f);val width=size.width*frame.scale*(if(hitIndex==definition.hitTimingsMillis.lastIndex)1.48f else 1.07f);drawUntintedAsset(asset,Offset(size.width*xs[s],size.height*ys[s]),width,width*asset.height.toFloat()/asset.width,frame.alpha,ro[s],frame.direction)}
        return
    }
    if (isSkySever(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val visible=if(reducedMotion)elapsed in 420 until 730 else elapsed in 135 until 985
        if(visible){val enter=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,135,420));val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,720,985);val alpha=minOf(enter,fade).coerceIn(0f,1f)*(if(reducedMotion).48f else 1f);val width=size.width*(if(reducedMotion)1f else .80f+.32f*enter);drawUntintedAsset(asset,Offset(size.width*.50f,size.height*.57f),width,width*asset.height.toFloat()/asset.width,alpha,1f)}
        return
    }
    if (isCliffDescent(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val frame=fierceDownwardStrikeVfxFrame(elapsed,reducedMotion)
        if(frame.visible)drawFierceDownwardStrikeAsset(asset,Offset(size.width*.50f,size.height*(frame.centerYFraction+.12f)),size.width*.81f*frame.scale,size.height*.95f*frame.scale,frame.alpha,frame.reveal)
        return
    }
    if (isLegionCharge(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val frame=chargingThrustVfxFrame(elapsed,reducedMotion)
        if(frame.visible){val scale=frame.scale*1.62f;drawTintedAsset(asset,Offset(size.width*frame.xFraction,size.height*frame.yFraction),size.width*1.22f*scale,size.width*.66f*scale,frame.alpha,Color.White)}
        return
    }
    if (isCrustExplosion(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val frame=groundImpactVfxFrame(elapsed,reducedMotion)
        if(frame.visible){val burst=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,250,665));drawTintedAsset(asset,Offset(size.width*.50f,size.height*.72f),size.width*(.84f+.36f*burst)*frame.scale,size.width*(.48f+.22f*burst)*frame.scale,frame.alpha*.97f,Color.White)}
        return
    }
    if (isInfiniteSlash(definition)) {
        val elapsed=local+definition.hitTimingsMillis[hitIndex];val frame=starterWarriorComboFrame(elapsed,hitIndex,definition.hitTimingsMillis,reducedMotion,true)
        if(frame.visible){val s=hitIndex.mod(10);val ro=floatArrayOf(-56f,52f,-41f,45f,-31f,34f,-20f,23f,-9f,6f);val xs=floatArrayOf(.28f,.72f,.34f,.68f,.38f,.63f,.42f,.58f,.46f,.50f);val ys=floatArrayOf(.42f,.75f,.76f,.43f,.71f,.47f,.67f,.51f,.62f,.58f);val width=size.width*frame.scale*(if(hitIndex==definition.hitTimingsMillis.lastIndex)1.54f else 1.08f);drawUntintedAsset(asset,Offset(size.width*xs[s],size.height*ys[s]),width,width*asset.height.toFloat()/asset.width,frame.alpha,ro[s],frame.direction)}
        return
    }
    if (isGoldenLionSlash(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val visible=if(reducedMotion)elapsed in 420 until 740 else elapsed in 125 until 1000
        if(visible){val enter=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,125,420));val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,730,1000);val alpha=minOf(enter,fade).coerceIn(0f,1f)*(if(reducedMotion).48f else 1f);val width=size.width*(if(reducedMotion)1.02f else .82f+.33f*enter);drawTintedAsset(asset,Offset(size.width*.50f,size.height*.57f),width,width*asset.height.toFloat()/asset.width,alpha,Color(0xFFFFE5A0),-1f)}
        return
    }
    if (isStarIronShatter(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val visible=if(reducedMotion)elapsed in 420 until 700 else elapsed in 230 until 940
        if(visible){val enter=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,230,420));val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,690,940);val alpha=minOf(enter,fade).coerceIn(0f,1f)*(if(reducedMotion).48f else .99f);val scale=if(reducedMotion).74f else .56f+.24f*enter;drawTintedAsset(asset,Offset(size.width*.50f,size.height*.63f),size.width*scale,size.width*scale*asset.height.toFloat()/asset.width,alpha,Color(0xFFD9D8FF))}
        return
    }
    if (isEmperorCharge(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val frame=chargingThrustVfxFrame(elapsed,reducedMotion)
        if(frame.visible){val scale=frame.scale*1.66f;drawTintedAsset(asset,Offset(size.width*frame.xFraction,size.height*frame.yFraction),size.width*1.24f*scale,size.width*.67f*scale,frame.alpha,Color(0xFFFFEDB5))}
        return
    }
    if (isWorldFissure(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val frame=groundImpactVfxFrame(elapsed,reducedMotion)
        if(frame.visible){val collapse=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,240,680));drawTintedAsset(asset,Offset(size.width*.50f,size.height*.71f),size.width*(.86f+.38f*collapse)*frame.scale,size.width*(.50f+.23f*collapse)*frame.scale,frame.alpha*.98f,Color(0xFFD9D8FF))}
        return
    }
    if (isMeteorFrenzy(definition)) {
        val elapsed=local+definition.hitTimingsMillis[hitIndex];val frame=starterWarriorComboFrame(elapsed,hitIndex,definition.hitTimingsMillis,reducedMotion,true)
        if(frame.visible){val s=hitIndex.mod(8);val ro=floatArrayOf(-58f,54f,-43f,47f,-33f,36f,-18f,7f);val xs=floatArrayOf(.27f,.73f,.33f,.69f,.37f,.64f,.43f,.50f);val ys=floatArrayOf(.41f,.76f,.77f,.42f,.72f,.46f,.66f,.58f);val width=size.width*frame.scale*(if(hitIndex==definition.hitTimingsMillis.lastIndex)1.60f else 1.10f);drawTintedAsset(asset,Offset(size.width*xs[s],size.height*ys[s]),width,width*asset.height.toFloat()/asset.width,frame.alpha,Color(0xFFD9D8FF),ro[s],frame.direction)}
        return
    }
    if (isStormKingSlash(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val visible=if(reducedMotion)elapsed in 420 until 750 else elapsed in 115 until 1015
        if(visible){val enter=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,115,420));val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,740,1015);val alpha=minOf(enter,fade).coerceIn(0f,1f)*(if(reducedMotion).48f else 1f);val width=size.width*(if(reducedMotion)1.04f else .84f+.34f*enter);drawTintedAsset(asset,Offset(size.width*.50f,size.height*.57f),width,width*asset.height.toFloat()/asset.width,alpha,Color(0xFFA7E9DF),-2f)}
        return
    }
    if (isJudgmentSmash(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val frame=fierceDownwardStrikeVfxFrame(elapsed,reducedMotion)
        if(frame.visible)drawFierceDownwardStrikeAsset(asset,Offset(size.width*.50f,size.height*(frame.centerYFraction+.13f)),size.width*.84f*frame.scale,size.height*.98f*frame.scale,frame.alpha,frame.reveal)
        return
    }
    if (isCitadelPierce(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val frame=chargingThrustVfxFrame(elapsed,reducedMotion)
        if(frame.visible){val scale=frame.scale*1.70f;drawTintedAsset(asset,Offset(size.width*frame.xFraction,size.height*frame.yFraction),size.width*1.26f*scale,size.width*.68f*scale,frame.alpha,Color.White)}
        return
    }
    if (isEarthDoom(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val frame=groundImpactVfxFrame(elapsed,reducedMotion)
        if(frame.visible){val collapse=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,225,695));drawTintedAsset(asset,Offset(size.width*.50f,size.height*.71f),size.width*(.88f+.40f*collapse)*frame.scale,size.width*(.52f+.24f*collapse)*frame.scale,frame.alpha*.99f,Color.White)}
        return
    }
    if (isTwelveSlash(definition)) {
        val elapsed=local+definition.hitTimingsMillis[hitIndex];val frame=starterWarriorComboFrame(elapsed,hitIndex,definition.hitTimingsMillis,reducedMotion,true)
        if(frame.visible){val s=hitIndex.mod(12);val ro=floatArrayOf(-60f,56f,-46f,49f,-36f,39f,-27f,30f,-18f,21f,-8f,6f);val xs=floatArrayOf(.26f,.74f,.32f,.70f,.36f,.65f,.40f,.61f,.44f,.57f,.47f,.50f);val ys=floatArrayOf(.40f,.77f,.78f,.41f,.73f,.45f,.69f,.49f,.65f,.53f,.61f,.58f);val width=size.width*frame.scale*(if(hitIndex==definition.hitTimingsMillis.lastIndex)1.66f else 1.12f);drawUntintedAsset(asset,Offset(size.width*xs[s],size.height*ys[s]),width,width*asset.height.toFloat()/asset.width,frame.alpha,ro[s],frame.direction)}
        return
    }
    if (isDragonSlayerCleave(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val visible=if(reducedMotion)elapsed in 420 until 760 else elapsed in 105 until 1030
        if(visible){val enter=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,105,420));val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,750,1030);val alpha=minOf(enter,fade).coerceIn(0f,1f)*(if(reducedMotion).48f else 1f);val width=size.width*(if(reducedMotion)1.06f else .86f+.35f*enter);drawUntintedAsset(asset,Offset(size.width*.50f,size.height*.56f),width,width*asset.height.toFloat()/asset.width,alpha,-1f)}
        return
    }
    if (isMountTaiCollapse(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val frame=groundImpactVfxFrame(elapsed,reducedMotion)
        if(frame.visible){val collapse=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,210,710));drawTintedAsset(asset,Offset(size.width*.50f,size.height*.70f),size.width*(.90f+.42f*collapse)*frame.scale,size.width*(.54f+.25f*collapse)*frame.scale,frame.alpha,Color.White)}
        return
    }
    if (isMythBreakthrough(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val frame=chargingThrustVfxFrame(elapsed,reducedMotion)
        if(frame.visible){val scale=frame.scale*1.74f;drawTintedAsset(asset,Offset(size.width*frame.xFraction,size.height*frame.yFraction),size.width*1.28f*scale,size.width*.69f*scale,frame.alpha,Color(0xFFD9D8FF))}
        return
    }
    if (isHeavenEarthShatter(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val visible=if(reducedMotion)elapsed in 420 until 710 else elapsed in 210 until 960
        if(visible){val enter=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,210,420));val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,700,960);val alpha=minOf(enter,fade).coerceIn(0f,1f)*(if(reducedMotion).48f else 1f);val scale=if(reducedMotion).78f else .58f+.25f*enter;drawUntintedAsset(asset,Offset(size.width*.50f,size.height*.62f),size.width*scale,size.width*scale*asset.height.toFloat()/asset.width,alpha)}
        return
    }
    if (isBerserkerGodFrenzy(definition)) {
        val elapsed=local+definition.hitTimingsMillis[hitIndex];val frame=starterWarriorComboFrame(elapsed,hitIndex,definition.hitTimingsMillis,reducedMotion,true)
        if(frame.visible){val s=hitIndex.mod(9);val ro=floatArrayOf(-62f,58f,-48f,51f,-38f,41f,-28f,31f,7f);val xs=floatArrayOf(.25f,.75f,.31f,.71f,.35f,.66f,.39f,.60f,.50f);val ys=floatArrayOf(.39f,.78f,.79f,.40f,.74f,.44f,.68f,.50f,.58f);val width=size.width*frame.scale*(if(hitIndex==definition.hitTimingsMillis.lastIndex)1.72f else 1.14f);drawUntintedAsset(asset,Offset(size.width*xs[s],size.height*ys[s]),width,width*asset.height.toFloat()/asset.width,frame.alpha,ro[s],frame.direction)}
        return
    }
    if (isOverlordSever(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val visible=if(reducedMotion)elapsed in 420 until 770 else elapsed in 95 until 1045
        if(visible){val enter=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,95,420));val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,760,1045);val alpha=minOf(enter,fade).coerceIn(0f,1f)*(if(reducedMotion).48f else 1f);val width=size.width*(if(reducedMotion)1.08f else .88f+.36f*enter);drawUntintedAsset(asset,Offset(size.width*.50f,size.height*.56f),width,width*asset.height.toFloat()/asset.width,alpha,0f)}
        return
    }
    if (isDoomHammer(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val frame=fierceDownwardStrikeVfxFrame(elapsed,reducedMotion)
        if(frame.visible)drawFierceDownwardStrikeAsset(asset,Offset(size.width*.50f,size.height*(frame.centerYFraction+.14f)),size.width*.87f*frame.scale,size.height*1.01f*frame.scale,frame.alpha,frame.reveal)
        return
    }
    if (isWarKingAdvance(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val frame=chargingThrustVfxFrame(elapsed,reducedMotion)
        if(frame.visible){val scale=frame.scale*1.78f;drawTintedAsset(asset,Offset(size.width*frame.xFraction,size.height*frame.yFraction),size.width*1.30f*scale,size.width*.70f*scale,frame.alpha,Color.White)}
        return
    }
    if (isContinentCollapse(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val frame=groundImpactVfxFrame(elapsed,reducedMotion)
        if(frame.visible){val collapse=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,195,725));drawTintedAsset(asset,Offset(size.width*.50f,size.height*.70f),size.width*(.92f+.44f*collapse)*frame.scale,size.width*(.56f+.26f*collapse)*frame.scale,frame.alpha,Color.White)}
        return
    }
    if (isBloodWindCombo(definition)) {
        val elapsed=local+definition.hitTimingsMillis[hitIndex];val frame=starterWarriorComboFrame(elapsed,hitIndex,definition.hitTimingsMillis,reducedMotion,true)
        if(frame.visible){val s=hitIndex.mod(8);val ro=floatArrayOf(-64f,60f,-50f,53f,-40f,43f,-29f,8f);val xs=floatArrayOf(.24f,.76f,.30f,.72f,.34f,.67f,.40f,.50f);val ys=floatArrayOf(.38f,.79f,.80f,.39f,.75f,.43f,.67f,.58f);val width=size.width*frame.scale*(if(hitIndex==definition.hitTimingsMillis.lastIndex)1.78f else 1.16f);drawTintedAsset(asset,Offset(size.width*xs[s],size.height*ys[s]),width,width*asset.height.toFloat()/asset.width,frame.alpha,Color(0xFFA7E9DF),ro[s],frame.direction)}
        return
    }
    if (isWorldSplit(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val visible=if(reducedMotion)elapsed in 420 until 780 else elapsed in 85 until 1060
        if(visible){val enter=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,85,420));val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,770,1060);val alpha=minOf(enter,fade).coerceIn(0f,1f)*(if(reducedMotion).48f else 1f);val width=size.width*(if(reducedMotion)1.10f else .90f+.38f*enter);drawUntintedAsset(asset,Offset(size.width*.50f,size.height*.56f),width,width*asset.height.toFloat()/asset.width,alpha,-2f)}
        return
    }
    if (isStarBreakingStrike(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val visible=if(reducedMotion)elapsed in 420 until 730 else elapsed in 185 until 990
        if(visible){val enter=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,185,420));val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,720,990);val alpha=minOf(enter,fade).coerceIn(0f,1f)*(if(reducedMotion).48f else 1f);val scale=if(reducedMotion).80f else .58f+.28f*enter;drawTintedAsset(asset,Offset(size.width*.50f,size.height*.62f),size.width*scale,size.width*scale*asset.height.toFloat()/asset.width,alpha,Color(0xFFE9E3FF))}
        return
    }
    if (isInvincibleGrandCharge(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val frame=chargingThrustVfxFrame(elapsed,reducedMotion)
        if(frame.visible){val scale=frame.scale*1.82f;drawTintedAsset(asset,Offset(size.width*frame.xFraction,size.height*frame.yFraction),size.width*1.32f*scale,size.width*.71f*scale,frame.alpha,Color.White)}
        return
    }
    if (isWorldAxisCollapse(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val frame=groundImpactVfxFrame(elapsed,reducedMotion)
        if(frame.visible){val collapse=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,180,740));drawTintedAsset(asset,Offset(size.width*.50f,size.height*.70f),size.width*(.94f+.46f*collapse)*frame.scale,size.width*(.58f+.27f*collapse)*frame.scale,frame.alpha,Color(0xFFE9E3FF))}
        return
    }
    if (isHundredLotusSwordDance(definition)) {
        val elapsed=local+definition.hitTimingsMillis[hitIndex];val frame=starterWarriorComboFrame(elapsed,hitIndex,definition.hitTimingsMillis,reducedMotion,true)
        if(frame.visible){val s=hitIndex.mod(10);val ro=floatArrayOf(-66f,62f,-53f,56f,-43f,46f,-33f,35f,-20f,7f);val xs=floatArrayOf(.23f,.77f,.29f,.73f,.33f,.68f,.37f,.63f,.42f,.50f);val ys=floatArrayOf(.37f,.80f,.81f,.38f,.76f,.42f,.71f,.47f,.65f,.58f);val width=size.width*frame.scale*(if(hitIndex==definition.hitTimingsMillis.lastIndex)1.84f else 1.18f);drawUntintedAsset(asset,Offset(size.width*xs[s],size.height*ys[s]),width,width*asset.height.toFloat()/asset.width,frame.alpha,ro[s],frame.direction)}
        return
    }
    if (isEndSword(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val visible=if(reducedMotion)elapsed in 420 until 800 else elapsed in 70 until 1080
        if(visible){val enter=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,70,420));val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,790,1080);val alpha=minOf(enter,fade).coerceIn(0f,1f)*(if(reducedMotion).48f else 1f);val width=size.width*(if(reducedMotion)1.12f else .92f+.40f*enter);drawUntintedAsset(asset,Offset(size.width*.50f,size.height*.56f),width,width*asset.height.toFloat()/asset.width,alpha,0f)}
        return
    }
    if (isDivineShatter(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val visible=if(reducedMotion)elapsed in 420 until 750 else elapsed in 170 until 1010
        if(visible){val enter=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,170,420));val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,740,1010);val alpha=minOf(enter,fade).coerceIn(0f,1f)*(if(reducedMotion).48f else 1f);val scale=if(reducedMotion).82f else .60f+.29f*enter;drawUntintedAsset(asset,Offset(size.width*.50f,size.height*.62f),size.width*scale,size.width*scale*asset.height.toFloat()/asset.width,alpha)}
        return
    }
    if (isLastVanguard(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val frame=chargingThrustVfxFrame(elapsed,reducedMotion)
        if(frame.visible){val scale=frame.scale*1.86f;drawUntintedAsset(asset,Offset(size.width*frame.xFraction,size.height*frame.yFraction),size.width*1.34f*scale,size.width*.72f*scale,frame.alpha)}
        return
    }
    if (isCreationEarthquake(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val frame=groundImpactVfxFrame(elapsed,reducedMotion)
        if(frame.visible){val burst=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,165,755));drawUntintedAsset(asset,Offset(size.width*.50f,size.height*.70f),size.width*(.96f+.48f*burst)*frame.scale,size.width*(.60f+.28f*burst)*frame.scale,frame.alpha)}
        return
    }
    if (isInfiniteFrenzy(definition)) {
        val elapsed=local+definition.hitTimingsMillis[hitIndex];val frame=starterWarriorComboFrame(elapsed,hitIndex,definition.hitTimingsMillis,reducedMotion,true)
        if(frame.visible){val s=hitIndex.mod(12);val ro=floatArrayOf(-68f,64f,-56f,59f,-47f,50f,-38f,41f,-29f,32f,-18f,8f);val xs=floatArrayOf(.22f,.78f,.28f,.74f,.32f,.69f,.36f,.64f,.40f,.59f,.44f,.50f);val ys=floatArrayOf(.36f,.81f,.82f,.37f,.77f,.41f,.72f,.46f,.67f,.51f,.62f,.58f);val width=size.width*frame.scale*(if(hitIndex==definition.hitTimingsMillis.lastIndex)1.92f else 1.20f);drawUntintedAsset(asset,Offset(size.width*xs[s],size.height*ys[s]),width,width*asset.height.toFloat()/asset.width,frame.alpha,ro[s],frame.direction)}
        return
    }
    if (isQuickStab(definition)) {
        val elapsed=local+definition.hitTimingsMillis[hitIndex];val frame=centerConvergenceFrame(modularFraction(elapsed,250,420),centerConvergenceOrigin(definition,hitIndex),reducedMotion)
        val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,500,690);val alpha=(if(reducedMotion).48f else minOf(modularFraction(elapsed,230,340),fade).coerceIn(0f,1f)*.72f);if(alpha>0f){val width=size.width*.30f*frame.scale;drawTintedAsset(asset,Offset(size.width*frame.xFraction,size.height*frame.yFraction),width,width*.40f,alpha,Color(0xFFD7D2C5),frame.rotationDegrees)}
        return
    }
    if (isShadowSlash(definition)) {
        val elapsed=local+definition.hitTimingsMillis[hitIndex];val frame=starterWarriorComboFrame(elapsed,hitIndex,definition.hitTimingsMillis,reducedMotion,true)
        if(frame.visible){val s=hitIndex.mod(3);val ro=floatArrayOf(-38f,35f,4f);val xs=floatArrayOf(.34f,.66f,.50f);val ys=floatArrayOf(.46f,.70f,.58f);val width=size.width*frame.scale*(if(hitIndex==definition.hitTimingsMillis.lastIndex).84f else .58f);drawTintedAsset(asset,Offset(size.width*xs[s],size.height*ys[s]),width,width*asset.height.toFloat()/asset.width,frame.alpha*.78f,Color(0xFF75658C),ro[s],frame.direction)}
        return
    }
    if (isVenomFangStab(definition)) {
        val elapsed=local+definition.hitTimingsMillis[hitIndex];val frame=centerConvergenceFrame(modularFraction(elapsed,245,420),centerConvergenceOrigin(definition,hitIndex),reducedMotion)
        val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,490,690);val alpha=(if(reducedMotion).48f else minOf(modularFraction(elapsed,225,335),fade).coerceIn(0f,1f)*.74f);if(alpha>0f){val width=size.width*.31f*frame.scale;drawTintedAsset(asset,Offset(size.width*frame.xFraction,size.height*frame.yFraction),width,width*.40f,alpha,Color(0xFF8FD176),frame.rotationDegrees)}
        return
    }
    if (isAnkleTrap(definition)) {
        val elapsed=local+definition.hitTimingsMillis[hitIndex];val visible=if(reducedMotion)elapsed in 420 until 650 else elapsed in 275 until 710
        if(visible){val snap=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,275,420));val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,570,710);val alpha=minOf(snap,fade).coerceIn(0f,1f)*(if(reducedMotion).48f else .66f);val width=size.width*(.22f+.18f*snap);drawTintedAsset(asset,Offset(size.width*.50f,size.height*.66f),width,width*asset.height.toFloat()/asset.width,alpha,Color(0xFFC3BACB),if(reducedMotion)0f else 18f*(1f-snap))}
        return
    }
    if (isVitalSlash(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val visible=if(reducedMotion)elapsed in 420 until 660 else elapsed in 210 until 760
        if(visible){val enter=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,210,420));val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,560,760);val alpha=minOf(enter,fade).coerceIn(0f,1f)*(if(reducedMotion).48f else .76f);val width=size.width*(if(reducedMotion).66f else .48f+.20f*enter);drawTintedAsset(asset,Offset(size.width*.50f,size.height*.58f),width,width*asset.height.toFloat()/asset.width,alpha,Color(0xFFE8D6D6),-14f)}
        return
    }
    if (isTwinFangCombo(definition)) {
        val elapsed=local+definition.hitTimingsMillis[hitIndex];val frame=starterWarriorComboFrame(elapsed,hitIndex,definition.hitTimingsMillis,reducedMotion,true)
        if(frame.visible){val ro=if(hitIndex.mod(2)==0)-34f else 34f;val x=if(hitIndex.mod(2)==0).38f else .62f;val y=if(hitIndex.mod(2)==0).46f else .70f;val width=size.width*frame.scale*.64f;drawTintedAsset(asset,Offset(size.width*x,size.height*y),width,width*asset.height.toFloat()/asset.width,frame.alpha*.80f,Color(0xFFE1DCE5),ro,frame.direction)}
        return
    }
    if (isAmbush(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val frame=chargingThrustVfxFrame(elapsed,reducedMotion)
        if(frame.visible){val width=size.width*.60f*frame.scale;drawTintedAsset(asset,Offset(size.width*frame.xFraction,size.height*frame.yFraction),width,width*.46f,frame.alpha*.72f,Color(0xFF6E5A84))}
        return
    }
    if (isGreenVenomBurst(definition)) {
        val elapsed=local+definition.hitTimingsMillis[hitIndex];val visible=if(reducedMotion)elapsed in 420 until 670 else elapsed in 245 until 770
        if(visible){val burst=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,245,420));val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,560,770);val alpha=minOf(burst,fade).coerceIn(0f,1f)*(if(reducedMotion).48f else .72f);val width=size.width*(.24f+.24f*burst);drawTintedAsset(asset,Offset(size.width*.50f,size.height*.60f),width,width*asset.height.toFloat()/asset.width,alpha,Color(0xFF85CE70),if(reducedMotion)0f else hitIndex*17f)}
        return
    }
    if (isWireSever(definition)) {
        val elapsed=local+definition.hitTimingsMillis[hitIndex];val frame=starterWarriorComboFrame(elapsed,hitIndex,definition.hitTimingsMillis,reducedMotion,true)
        if(frame.visible){val width=size.width*frame.scale*.72f;drawTintedAsset(asset,Offset(size.width*.50f,size.height*.58f),width,width*asset.height.toFloat()/asset.width,frame.alpha*.78f,Color(0xFFBFC4CA),if(hitIndex.mod(2)==0)-18f else 21f,frame.direction)}
        return
    }
    if (isThroatEnd(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val visible=if(reducedMotion)elapsed in 420 until 690 else elapsed in 260 until 800
        if(visible){val enter=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,260,420));val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,600,800);val alpha=minOf(enter,fade).coerceIn(0f,1f)*(if(reducedMotion).48f else .82f);val width=size.width*(.46f+.26f*enter);drawTintedAsset(asset,Offset(size.width*.50f,size.height*.58f),width,width*asset.height.toFloat()/asset.width,alpha,Color(0xFFE9E2E6),-5f)}
        return
    }
    if (isTripleStab(definition) || isVenomTwinNeedles(definition)) {
        val elapsed=local+definition.hitTimingsMillis[hitIndex];val frame=centerConvergenceFrame(modularFraction(elapsed,225,420),centerConvergenceOrigin(definition,hitIndex),reducedMotion)
        val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,475,670);val alpha=(if(reducedMotion).48f else minOf(modularFraction(elapsed,205,320),fade).coerceIn(0f,1f)*.78f);if(alpha>0f){val width=size.width*(if(isTripleStab(definition)).32f else .29f)*frame.scale;drawTintedAsset(asset,Offset(size.width*frame.xFraction,size.height*frame.yFraction),width,width*.38f,alpha,if(isVenomTwinNeedles(definition))Color(0xFF82C96E) else Color(0xFFD8D3DB),frame.rotationDegrees)}
        return
    }
    if (isAfterimageAssault(definition)) {
        val elapsed=local+definition.hitTimingsMillis[hitIndex];val frame=chargingThrustVfxFrame(elapsed,reducedMotion)
        if(frame.visible){val echo=if(reducedMotion)0f else .035f;val width=size.width*.64f*frame.scale;drawTintedAsset(asset,Offset(size.width*(frame.xFraction-echo),size.height*frame.yFraction),width,width*.45f,frame.alpha*.28f,Color(0xFF5E4A78));drawTintedAsset(asset,Offset(size.width*frame.xFraction,size.height*frame.yFraction),width,width*.45f,frame.alpha*.72f,Color(0xFF8A759D))}
        return
    }
    if (isLassoStrike(definition)) {
        val elapsed=local+definition.hitTimingsMillis[hitIndex];val visible=if(reducedMotion)elapsed in 420 until 670 else elapsed in 230 until 760
        if(visible){val snap=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,230,420));val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,580,760);val alpha=minOf(snap,fade).coerceIn(0f,1f)*(if(reducedMotion).48f else .72f);val width=size.width*(.25f+.22f*snap);drawTintedAsset(asset,Offset(size.width*.50f,size.height*.61f),width,width*asset.height.toFloat()/asset.width,alpha,Color(0xFFCBC2CE),if(reducedMotion)0f else 24f*(1f-snap))}
        return
    }
    if (isHeartStab(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val frame=centerConvergenceFrame(modularFraction(elapsed,270,420),CenterConvergenceOrigin(.13f,.58f),reducedMotion)
        val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,590,820);val alpha=(if(reducedMotion).48f else minOf(modularFraction(elapsed,250,350),fade).coerceIn(0f,1f)*.86f);if(alpha>0f){val width=size.width*.40f*frame.scale;drawTintedAsset(asset,Offset(size.width*frame.xFraction,size.height*frame.yFraction),width,width*.40f,alpha,Color(0xFFE8DADD),frame.rotationDegrees)}
        return
    }
    if (isDaggerFrenzy(definition)) {
        val elapsed=local+definition.hitTimingsMillis[hitIndex];val frame=centerConvergenceFrame(modularFraction(elapsed,190,420),centerConvergenceOrigin(definition,hitIndex),reducedMotion)
        val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,455,640);val alpha=(if(reducedMotion).48f else minOf(modularFraction(elapsed,175,285),fade).coerceIn(0f,1f)*.80f);if(alpha>0f){val width=size.width*.30f*frame.scale;drawTintedAsset(asset,Offset(size.width*frame.xFraction,size.height*frame.yFraction),width,width*.40f,alpha,Color(0xFFD9D5DE),frame.rotationDegrees)}
        return
    }
    if (isDarkLeap(definition)) {
        val elapsed=local+definition.hitTimingsMillis[hitIndex];val visible=if(reducedMotion)elapsed in 420 until 670 else elapsed in 210 until 760
        if(visible){val dive=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,210,420));val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,575,760);val alpha=minOf(dive,fade).coerceIn(0f,1f)*(if(reducedMotion).48f else .76f);val x=.50f;val y=if(reducedMotion).60f else .22f+.38f*dive;val width=size.width*(.32f+.18f*dive);drawTintedAsset(asset,Offset(size.width*x,size.height*y),width,width*asset.height.toFloat()/asset.width,alpha,Color(0xFF67517C),-8f)}
        return
    }
    if (isPoisonMistBlast(definition)) {
        val elapsed=local+definition.hitTimingsMillis[hitIndex];val visible=if(reducedMotion)elapsed in 420 until 690 else elapsed in 215 until 790
        if(visible){val bloom=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,215,420));val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,600,790);val alpha=minOf(bloom,fade).coerceIn(0f,1f)*(if(reducedMotion).44f else .68f);val width=size.width*(.25f+.30f*bloom);drawTintedAsset(asset,Offset(size.width*.50f,size.height*.60f),width,width*asset.height.toFloat()/asset.width,alpha,Color(0xFF75B965),if(reducedMotion)0f else 22f*hitIndex)}
        return
    }
    if (isBladeTrap(definition)) {
        val elapsed=local+definition.hitTimingsMillis[hitIndex];val visible=if(reducedMotion)elapsed in 420 until 690 else elapsed in 210 until 780
        if(visible){val snap=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,210,420));val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,590,780);val alpha=minOf(snap,fade).coerceIn(0f,1f)*(if(reducedMotion).48f else .75f);val width=size.width*(.22f+.25f*snap);drawTintedAsset(asset,Offset(size.width*.50f,size.height*.64f),width,width*asset.height.toFloat()/asset.width,alpha,Color(0xFFC9C1CC),if(reducedMotion)0f else -28f+56f*snap)}
        return
    }
    if (isSilentExecution(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val visible=if(reducedMotion)elapsed in 420 until 710 else elapsed in 285 until 830
        if(visible){val enter=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,285,420));val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,620,830);val alpha=minOf(enter,fade).coerceIn(0f,1f)*(if(reducedMotion).48f else .86f);val width=size.width*(.44f+.28f*enter);drawTintedAsset(asset,Offset(size.width*.50f,size.height*.58f),width,width*asset.height.toFloat()/asset.width,alpha,Color(0xFFE8E2EA),3f)}
        return
    }
    if (isCrescentDagger(definition) || isShadowCross(definition)) {
        val elapsed=local+definition.hitTimingsMillis[hitIndex];val frame=starterWarriorComboFrame(elapsed,hitIndex,definition.hitTimingsMillis,reducedMotion,true)
        if(frame.visible){val cross=isShadowCross(definition);val ro=if(cross){if(hitIndex.mod(2)==0)-43f else 43f}else{-24f+hitIndex*18f};val x=if(hitIndex.mod(2)==0).38f else .62f;val y=if(hitIndex.mod(2)==0).46f else .70f;val width=size.width*frame.scale*(if(cross).72f else .68f);drawTintedAsset(asset,Offset(size.width*x,size.height*y),width,width*asset.height.toFloat()/asset.width,frame.alpha*.82f,if(cross)Color(0xFF77618C) else Color(0xFFDCD8E0),ro,frame.direction)}
        return
    }
    if (isPoisonSpray(definition)) {
        val elapsed=local+definition.hitTimingsMillis[hitIndex];val frame=centerConvergenceFrame(modularFraction(elapsed,215,420),centerConvergenceOrigin(definition,hitIndex),reducedMotion)
        val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,500,710);val alpha=(if(reducedMotion).46f else minOf(modularFraction(elapsed,195,310),fade).coerceIn(0f,1f)*.76f);if(alpha>0f){val width=size.width*.34f*frame.scale;drawTintedAsset(asset,Offset(size.width*frame.xFraction,size.height*frame.yFraction),width,width*.42f,alpha,Color(0xFF76BC67),frame.rotationDegrees)}
        return
    }
    if (isSilverWireBinding(definition)) {
        val elapsed=local+definition.hitTimingsMillis[hitIndex];val visible=if(reducedMotion)elapsed in 420 until 690 else elapsed in 195 until 790
        if(visible){val bind=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,195,420));val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,600,790);val alpha=minOf(bind,fade).coerceIn(0f,1f)*(if(reducedMotion).48f else .78f);val width=size.width*(.30f+.20f*bind);drawTintedAsset(asset,Offset(size.width*.50f,size.height*.60f),width,width*asset.height.toFloat()/asset.width,alpha,Color(0xFFD7D8DC),if(reducedMotion)0f else -35f+70f*bind)}
        return
    }
    if (isCrimsonVital(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val visible=if(reducedMotion)elapsed in 420 until 730 else elapsed in 270 until 850
        if(visible){val enter=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,270,420));val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,640,850);val alpha=minOf(enter,fade).coerceIn(0f,1f)*(if(reducedMotion).48f else .88f);val width=size.width*(.48f+.30f*enter);drawTintedAsset(asset,Offset(size.width*.50f,size.height*.58f),width,width*asset.height.toFloat()/asset.width,alpha,Color(0xFFE3A9AF),-7f)}
        return
    }
    if (isGaleTwinBlades(definition)) {
        val elapsed=local+definition.hitTimingsMillis[hitIndex];val frame=starterWarriorComboFrame(elapsed,hitIndex,definition.hitTimingsMillis,reducedMotion,true)
        if(frame.visible){val s=hitIndex.mod(3);val ro=floatArrayOf(-47f,44f,6f);val xs=floatArrayOf(.34f,.66f,.50f);val ys=floatArrayOf(.43f,.72f,.58f);val width=size.width*frame.scale*.78f;drawTintedAsset(asset,Offset(size.width*xs[s],size.height*ys[s]),width,width*asset.height.toFloat()/asset.width,frame.alpha*.84f,Color(0xFFA8DED4),ro[s],frame.direction)}
        return
    }
    if (isNightRaid(definition)) {
        val elapsed=local+definition.hitTimingsMillis[hitIndex];val frame=chargingThrustVfxFrame(elapsed,reducedMotion)
        if(frame.visible){val width=size.width*.70f*frame.scale;drawTintedAsset(asset,Offset(size.width*frame.xFraction,size.height*frame.yFraction),width,width*.46f,frame.alpha*.76f,Color(0xFF604879))}
        return
    }
    if (isCorrosionBurst(definition)) {
        val elapsed=local+definition.hitTimingsMillis[hitIndex];val visible=if(reducedMotion)elapsed in 420 until 700 else elapsed in 190 until 810
        if(visible){val burst=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,190,420));val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,610,810);val alpha=minOf(burst,fade).coerceIn(0f,1f)*(if(reducedMotion).44f else .72f);val width=size.width*(.26f+.32f*burst);drawTintedAsset(asset,Offset(size.width*.50f,size.height*.60f),width,width*asset.height.toFloat()/asset.width,alpha,Color(0xFF7EBE62),if(reducedMotion)0f else hitIndex*19f)}
        return
    }
    if (isSpinningLasso(definition)) {
        val elapsed=local+definition.hitTimingsMillis[hitIndex];val visible=if(reducedMotion)elapsed in 420 until 700 else elapsed in 190 until 810
        if(visible){val spin=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,190,420));val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,610,810);val alpha=minOf(spin,fade).coerceIn(0f,1f)*(if(reducedMotion).48f else .76f);val width=size.width*(.28f+.27f*spin);drawTintedAsset(asset,Offset(size.width*.50f,size.height*.61f),width,width*asset.height.toFloat()/asset.width,alpha,Color(0xFFC9C2CD),if(reducedMotion)0f else -95f+180f*spin)}
        return
    }
    if (isBlindSpotStrike(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val visible=if(reducedMotion)elapsed in 420 until 740 else elapsed in 265 until 860
        if(visible){val enter=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,265,420));val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,650,860);val alpha=minOf(enter,fade).coerceIn(0f,1f)*(if(reducedMotion).48f else .88f);val width=size.width*(.50f+.31f*enter);drawTintedAsset(asset,Offset(size.width*.50f,size.height*.58f),width,width*asset.height.toFloat()/asset.width,alpha,Color(0xFFE6E0E9),12f)}
        return
    }
    if (isFourWayDaggers(definition) || isDeadlyPoisonNeedles(definition)) {
        val elapsed=local+definition.hitTimingsMillis[hitIndex];val frame=centerConvergenceFrame(modularFraction(elapsed,185,420),centerConvergenceOrigin(definition,hitIndex),reducedMotion)
        val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,460,665);val alpha=(if(reducedMotion).48f else minOf(modularFraction(elapsed,165,280),fade).coerceIn(0f,1f)*.82f);if(alpha>0f){val width=size.width*(if(isFourWayDaggers(definition)).34f else .31f)*frame.scale;drawTintedAsset(asset,Offset(size.width*frame.xFraction,size.height*frame.yFraction),width,width*.40f,alpha,if(isDeadlyPoisonNeedles(definition))Color(0xFF75B964) else Color(0xFFD7D4DB),frame.rotationDegrees)}
        return
    }
    if (isBlackShadowSlash(definition)) {
        val elapsed=local+definition.hitTimingsMillis[hitIndex];val frame=starterWarriorComboFrame(elapsed,hitIndex,definition.hitTimingsMillis,reducedMotion,true)
        if(frame.visible){val width=size.width*frame.scale*.78f;drawTintedAsset(asset,Offset(size.width*.50f,size.height*.58f),width,width*asset.height.toFloat()/asset.width,frame.alpha*.82f,Color(0xFF59416F),if(hitIndex.mod(2)==0)-32f else 35f,frame.direction)}
        return
    }
    if (isChainTrap(definition)) {
        val elapsed=local+definition.hitTimingsMillis[hitIndex];val visible=if(reducedMotion)elapsed in 420 until 710 else elapsed in 175 until 820
        if(visible){val bind=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,175,420));val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,620,820);val alpha=minOf(bind,fade).coerceIn(0f,1f)*(if(reducedMotion).48f else .80f);val width=size.width*(.31f+.24f*bind);drawTintedAsset(asset,Offset(size.width*.50f,size.height*.61f),width,width*asset.height.toFloat()/asset.width,alpha,Color(0xFFB9B1BE),if(reducedMotion)0f else -45f+90f*bind)}
        return
    }
    if (isFatalSever(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val visible=if(reducedMotion)elapsed in 420 until 750 else elapsed in 250 until 880
        if(visible){val enter=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,250,420));val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,660,880);val alpha=minOf(enter,fade).coerceIn(0f,1f)*(if(reducedMotion).48f else .90f);val width=size.width*(.52f+.33f*enter);drawTintedAsset(asset,Offset(size.width*.50f,size.height*.58f),width,width*asset.height.toFloat()/asset.width,alpha,Color(0xFFE8DDE3),-10f)}
        return
    }
    if (isGhostCarve(definition) || isSteelThreadLine(definition)) {
        val elapsed=local+definition.hitTimingsMillis[hitIndex];val frame=starterWarriorComboFrame(elapsed,hitIndex,definition.hitTimingsMillis,reducedMotion,true)
        if(frame.visible){val steel=isSteelThreadLine(definition);val ro=if(steel){floatArrayOf(-49f,47f,-25f,24f)[hitIndex.mod(4)]}else{if(hitIndex.mod(2)==0)-38f else 40f};val xs=if(steel)floatArrayOf(.30f,.70f,.38f,.62f)else floatArrayOf(.38f,.62f);val ys=if(steel)floatArrayOf(.42f,.75f,.72f,.45f)else floatArrayOf(.46f,.70f);val s=hitIndex.mod(xs.size);val width=size.width*frame.scale*(if(steel).78f else .82f);drawTintedAsset(asset,Offset(size.width*xs[s],size.height*ys[s]),width,width*asset.height.toFloat()/asset.width,frame.alpha*.84f,if(steel)Color(0xFFC5CAD0) else Color(0xFF75668A),ro,frame.direction)}
        return
    }
    if (isAfterimageLeap(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val visible=if(reducedMotion)elapsed in 420 until 720 else elapsed in 170 until 830
        if(visible){val dive=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,170,420));val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,630,830);val alpha=minOf(dive,fade).coerceIn(0f,1f)*(if(reducedMotion).48f else .82f);val y=if(reducedMotion).60f else .18f+.42f*dive;val width=size.width*(.36f+.22f*dive);drawTintedAsset(asset,Offset(size.width*.50f,size.height*y),width,width*asset.height.toFloat()/asset.width,alpha*.30f,Color(0xFF4B385E),-7f);drawTintedAsset(asset,Offset(size.width*.50f,size.height*(y+.025f)),width,width*asset.height.toFloat()/asset.width,alpha,Color(0xFF79658D),-7f)}
        return
    }
    if (isSerpentFang(definition)) {
        val elapsed=local+definition.hitTimingsMillis[hitIndex];val frame=centerConvergenceFrame(modularFraction(elapsed,170,420),centerConvergenceOrigin(definition,hitIndex),reducedMotion)
        val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,455,680);val alpha=(if(reducedMotion).48f else minOf(modularFraction(elapsed,150,270),fade).coerceIn(0f,1f)*.84f);if(alpha>0f){val width=size.width*.33f*frame.scale;drawTintedAsset(asset,Offset(size.width*frame.xFraction,size.height*frame.yFraction),width,width*.39f,alpha,Color(0xFF6FB55F),frame.rotationDegrees)}
        return
    }
    if (isSoulStab(definition)) {
        val elapsed=local+definition.hitTimingsMillis.single();val frame=centerConvergenceFrame(modularFraction(elapsed,245,420),CenterConvergenceOrigin(.88f,.42f),reducedMotion)
        val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,620,850);val alpha=(if(reducedMotion).48f else minOf(modularFraction(elapsed,225,330),fade).coerceIn(0f,1f)*.90f);if(alpha>0f){val width=size.width*.44f*frame.scale;drawTintedAsset(asset,Offset(size.width*frame.xFraction,size.height*frame.yFraction),width,width*.40f,alpha,Color(0xFFC9B9DC),frame.rotationDegrees)}
        return
    }
    val intensity = skillVfxIntensityProfile(definition)
    val enter = modularEaseOut(modularFraction(local, -150, 20))
    val fade = 1f - modularFraction(
        local,
        if (isFinal) 150 else if (definition.hitCount >= 8) 52 else 90,
        if (isFinal) 340 else if (definition.hitCount >= 8) 126 else 220,
    )
    val alpha = minOf(enter, fade).coerceIn(0f, 1f) *
        (if (isFinal) 0.94f else 0.68f) * intensity.primaryAlpha
    if (alpha <= 0f) return
    when (plan.recipe.archetype) {
        ModularVfxArchetype.WARRIOR,
        ModularVfxArchetype.SLASH -> {
            if (usesCenterConvergence(definition, hitIndex)) {
                val origin = centerConvergenceOrigin(definition, hitIndex)
                val frame = centerConvergenceFrame(
                    progress = modularFraction(local, -150, 24),
                    origin = origin,
                    reducedMotion = reducedMotion,
                )
                val width = size.width * 0.48f * frame.scale * intensity.primaryScale
                drawTintedAsset(
                    asset = asset,
                    center = Offset(size.width * frame.xFraction, size.height * frame.yFraction),
                    width = width,
                    height = width,
                    alpha = alpha * 0.90f,
                    tint = tint,
                    rotation = frame.rotationDegrees,
                )
            } else {
                val travel = if (reducedMotion) 0f else size.width * 0.035f * (enter - 0.5f) * choreography.visualDirection
                val choreographedCenter = center + Offset(
                    x = size.width * choreography.xOffsetFraction,
                    y = size.height * choreography.yOffsetFraction,
                )
                val isVerticalWarrior = plan.recipe.archetype == ModularVfxArchetype.WARRIOR &&
                    plan.flow == SemanticVfxFlow.TOP_TO_BOTTOM
                val width = size.width * when {
                    isVerticalWarrior -> if (isFinal) 0.31f else 0.24f
                    isFinal -> 0.42f
                    else -> 0.28f
                } *
                    choreography.widthScale * intensity.primaryScale
                val height = if (isVerticalWarrior) width * 1.55f else width
                drawTintedAsset(
                    asset = asset,
                    center = choreographedCenter + Offset(travel, 0f),
                    width = width,
                    height = height,
                    alpha = alpha * 0.86f,
                    tint = tint,
                    rotation = choreography.rotationDegrees,
                    direction = choreography.mirrorDirection,
                )
                if (plan.recipe.archetype == ModularVfxArchetype.WARRIOR && intensity.tier >= 2) {
                    val echoCount = when {
                        isFinal && intensity.tier >= 5 -> 2
                        isFinal || intensity.tier >= 4 -> 1
                        else -> 0
                    }
                    repeat(echoCount) { echo ->
                        val echoScale = 1.10f + echo * 0.09f
                        drawTintedAsset(
                            asset = asset,
                            center = choreographedCenter,
                            width = width * echoScale,
                            height = height * echoScale,
                            alpha = alpha * (0.22f - echo * 0.06f),
                            tint = tint,
                            rotation = choreography.rotationDegrees + if (echo == 0) 5f else -6f,
                            direction = choreography.mirrorDirection,
                        )
                    }
                }
            }
            if (plan.action == SemanticVfxAction.CROSS_CUT && isFinal) {
                val width = size.width * 0.42f * choreography.widthScale * intensity.primaryScale
                drawTintedAsset(alternateCrossAsset, center, width * 0.92f, width * 0.92f, alpha * 0.68f, tint, direction = -choreography.mirrorDirection)
            }
        }
        ModularVfxArchetype.PROJECTILE -> {
            if (usesCenterConvergence(definition, hitIndex)) {
                val origin = centerConvergenceOrigin(definition, hitIndex)
                val frame = centerConvergenceFrame(
                    progress = modularFraction(local, -150, 24),
                    origin = origin,
                    reducedMotion = reducedMotion,
                )
                val baseWidth = size.width * if (isFinal) 0.48f else 0.38f
                val width = baseWidth * frame.scale * intensity.primaryScale
                drawTintedAsset(
                    asset = asset,
                    center = Offset(size.width * frame.xFraction, size.height * frame.yFraction),
                    width = width,
                    height = width,
                    alpha = alpha,
                    tint = tint,
                    rotation = frame.rotationDegrees,
                )
            } else {
                val width = size.width * 0.58f * intensity.primaryScale
                drawTintedAssetRevealedHorizontal(
                    asset,
                    center,
                    width,
                    width,
                    alpha,
                    tint,
                    if (reducedMotion) 1f else enter,
                    choreography.mirrorDirection,
                )
            }
        }
        ModularVfxArchetype.COLUMN -> {
            val width = size.width * (if (isFinal) 0.36f else 0.25f) * intensity.primaryScale
            val height = width
            val columnCenter = Offset(center.x, center.y - height * 0.42f)
            val reveal = if (reducedMotion) 1f else enter
            val fromBottom = plan.flow == SemanticVfxFlow.BOTTOM_TO_TOP
            drawTintedAssetRevealedVertical(
                asset,
                columnCenter,
                width,
                height,
                alpha,
                tint,
                reveal,
                fromBottom,
                choreography.mirrorDirection,
            )
            if (plan.action == SemanticVfxAction.VOLLEY && isFinal) {
                drawTintedAssetRevealedVertical(asset, columnCenter - Offset(size.width * 0.19f, 0f), width * 0.72f, height * 0.80f, alpha * 0.55f, tint, reveal, false)
                drawTintedAssetRevealedVertical(asset, columnCenter + Offset(size.width * 0.19f, 0f), width * 0.72f, height * 0.80f, alpha * 0.55f, tint, reveal, false)
            }
        }
        ModularVfxArchetype.VORTEX -> {
            val collapse = plan.flow == SemanticVfxFlow.OUTSIDE_IN
            val scale = if (reducedMotion) 1f else if (collapse) 1.18f - enter * 0.28f else 0.72f + enter * 0.30f
            val width = size.width * (if (isFinal) 0.52f else 0.34f) * scale * intensity.primaryScale
            val rotation = if (reducedMotion) {
                0f
            } else {
                local.coerceAtLeast(0) * 0.035f * choreography.visualDirection
            }
            drawTintedAsset(asset, center, width, width, alpha * 0.82f, tint, rotation)
        }
        ModularVfxArchetype.IMPACT -> Unit
    }
}

private fun DrawScope.drawRangerStarterPrimary(
    asset: ImageBitmap,
    definition: SkillDefinition,
    hitIndex: Int,
    local: Int,
    reducedMotion: Boolean,
): Boolean {
    if (definition.heroClass != HeroClass.RANGER || definition.unlockLevel > 10) return false
    val elapsed = local + definition.hitTimingsMillis[hitIndex]
    when {
        isAimedShot(definition) -> {
            val frame = centerConvergenceFrame(
                modularFraction(elapsed, 270, 420),
                CenterConvergenceOrigin(0.10f, 0.60f),
                reducedMotion,
            )
            val fade = if (reducedMotion) 1f else 1f - modularFraction(elapsed, 510, 700)
            val alpha = if (reducedMotion) 0.48f else minOf(modularFraction(elapsed, 245, 335), fade).coerceIn(0f, 1f) * 0.74f
            if (alpha > 0f) {
                val width = size.width * 0.34f * frame.scale
                drawTintedAsset(asset, Offset(size.width * frame.xFraction, size.height * frame.yFraction), width, width * 0.34f, alpha, Color(0xFFD8D5C9), frame.rotationDegrees)
            }
        }
        isTripleArrow(definition) -> {
            val frame = centerConvergenceFrame(
                modularFraction(elapsed, 205, 420),
                centerConvergenceOrigin(definition, hitIndex),
                reducedMotion,
            )
            val fade = if (reducedMotion) 1f else 1f - modularFraction(elapsed, 465, 650)
            val alpha = if (reducedMotion) 0.48f else minOf(modularFraction(elapsed, 180, 285), fade).coerceIn(0f, 1f) * 0.78f
            if (alpha > 0f) {
                val width = size.width * 0.31f * frame.scale
                drawTintedAsset(asset, Offset(size.width * frame.xFraction, size.height * frame.yFraction), width, width * 0.31f, alpha, Color(0xFFBBD9D0), frame.rotationDegrees)
            }
        }
        isBreezeArrow(definition) -> {
            val frame = centerConvergenceFrame(
                modularFraction(elapsed, 220, 420),
                centerConvergenceOrigin(definition, hitIndex),
                reducedMotion,
            )
            val fade = if (reducedMotion) 1f else 1f - modularFraction(elapsed, 480, 680)
            val alpha = if (reducedMotion) 0.46f else minOf(modularFraction(elapsed, 195, 300), fade).coerceIn(0f, 1f) * 0.70f
            if (alpha > 0f) {
                val width = size.width * 0.29f * frame.scale
                drawTintedAsset(asset, Offset(size.width * frame.xFraction, size.height * frame.yFraction), width, width * 0.30f, alpha, Color(0xFF8FD2C2), frame.rotationDegrees)
            }
        }
        isThornTrap(definition) -> {
            val visible = if (reducedMotion) elapsed in 420 until 650 else elapsed in 225 until 730
            if (visible) {
                val snap = if (reducedMotion) 1f else modularEaseOut(modularFraction(elapsed, 225, 420))
                val fade = if (reducedMotion) 1f else 1f - modularFraction(elapsed, 555, 730)
                val alpha = minOf(snap, fade).coerceIn(0f, 1f) * if (reducedMotion) 0.46f else 0.70f
                val width = size.width * (0.23f + 0.20f * snap)
                drawTintedAsset(asset, Offset(size.width * 0.50f, size.height * 0.67f), width, width, alpha, Color(0xFFA9B889), if (reducedMotion) 0f else -26f + 52f * snap)
            }
        }
        isMoonlightArrow(definition) -> {
            val frame = centerConvergenceFrame(
                modularFraction(elapsed, 190, 420),
                centerConvergenceOrigin(definition, hitIndex),
                reducedMotion,
            )
            val fade = if (reducedMotion) 1f else 1f - modularFraction(elapsed, 480, 690)
            val alpha = if (reducedMotion) 0.48f else minOf(modularFraction(elapsed, 165, 280), fade).coerceIn(0f, 1f) * 0.76f
            if (alpha > 0f) {
                val width = size.width * 0.32f * frame.scale
                drawTintedAsset(asset, Offset(size.width * frame.xFraction, size.height * frame.yFraction), width, width * 0.34f, alpha, Color(0xFFC8BDE8), frame.rotationDegrees)
            }
        }
        isPiercingShot(definition) -> {
            val frame = centerConvergenceFrame(modularFraction(elapsed, 255, 420), CenterConvergenceOrigin(0.08f, 0.60f), reducedMotion)
            val fade = if (reducedMotion) 1f else 1f - modularFraction(elapsed, 535, 735)
            val alpha = if (reducedMotion) 0.48f else minOf(modularFraction(elapsed, 225, 325), fade).coerceIn(0f, 1f) * 0.82f
            if (alpha > 0f) { val width=size.width*.38f*frame.scale;drawTintedAsset(asset,Offset(size.width*frame.xFraction,size.height*frame.yFraction),width,width*.30f,alpha,Color(0xFFDAD9D2),frame.rotationDegrees) }
        }
        isRapidShot(definition) || isGustBowstring(definition) -> {
            val frame = centerConvergenceFrame(modularFraction(elapsed, 190, 420), centerConvergenceOrigin(definition, hitIndex), reducedMotion)
            val fade = if (reducedMotion) 1f else 1f - modularFraction(elapsed, 465, 655)
            val alpha = if (reducedMotion) 0.48f else minOf(modularFraction(elapsed, 165, 275), fade).coerceIn(0f, 1f) * 0.82f
            if (alpha > 0f) { val width=size.width*.32f*frame.scale;drawTintedAsset(asset,Offset(size.width*frame.xFraction,size.height*frame.yFraction),width,width*.31f,alpha,if(isGustBowstring(definition))Color(0xFF8FD4C4) else Color(0xFFD4D6D1),frame.rotationDegrees) }
        }
        isWolfFang(definition) -> {
            val visible=if(reducedMotion)elapsed in 420 until 690 else elapsed in 190 until 790
            if(visible){val dive=if(reducedMotion)1f else modularEaseOut(modularFraction(elapsed,190,420));val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,590,790);val alpha=minOf(dive,fade).coerceIn(0f,1f)*(if(reducedMotion).48f else .78f);val lane=(hitIndex-1)*.055f;val x=.50f+lane;val y=if(reducedMotion).60f else .20f+.40f*dive;val width=size.width*(.28f+.19f*dive);drawTintedAsset(asset,Offset(size.width*x,size.height*y),width,width*asset.height.toFloat()/asset.width,alpha,Color(0xFFB8B39B),if(reducedMotion)0f else -10f+hitIndex*10f)}
        }
        isStardustShot(definition) -> {
            val frame=centerConvergenceFrame(modularFraction(elapsed,245,420),CenterConvergenceOrigin(.90f,.42f),reducedMotion)
            val fade=if(reducedMotion)1f else 1f-modularFraction(elapsed,560,760);val alpha=if(reducedMotion).48f else minOf(modularFraction(elapsed,215,320),fade).coerceIn(0f,1f)*.84f
            if(alpha>0f){val width=size.width*.39f*frame.scale;drawTintedAsset(asset,Offset(size.width*frame.xFraction,size.height*frame.yFraction),width,width*.35f,alpha,Color(0xFFD0C4EC),frame.rotationDegrees)}
        }
    }
    return true
}

private fun DrawScope.drawModularImpactLayers(
    core: ImageBitmap,
    spread: ImageBitmap,
    spikes: ImageBitmap,
    debris: ImageBitmap,
    definition: SkillDefinition,
    plan: SemanticVfxPlan,
    center: Offset,
    local: Int,
    isFinal: Boolean,
    palette: SkillPalette,
    reducedMotion: Boolean,
) {
    val intensity = skillVfxIntensityProfile(definition)
    val layerCount = modularLayerCount(definition, isFinal)
    if (plan.impactStyle == SemanticImpactStyle.NONE) return
    val coreAlpha = (
        modularPulse(local, -8, 24, if (isFinal) 260 else 170) *
            if (isFinal) 0.86f else 0.55f
        )
    if (coreAlpha > 0f) {
        val width = size.width * when (plan.impactStyle) {
            SemanticImpactStyle.POINT -> if (isFinal) 0.19f else 0.12f
            SemanticImpactStyle.EDGE -> if (isFinal) 0.15f else 0.10f
            else -> if (isFinal) 0.24f else 0.14f
        }
        drawTintedAsset(core, center, width * intensity.impactScale, width * intensity.impactScale, coreAlpha * intensity.primaryAlpha, palette.secondary)
    }
    if (layerCount < 3) return

    val spreadAlpha = modularPulse(local, 10, 62, if (isFinal) 370 else 220) * if (isFinal) 0.58f else 0.22f
    if (spreadAlpha > 0f) {
        val width = size.width * if (isFinal) 0.36f else 0.20f
        val layer = if (plan.impactStyle == SemanticImpactStyle.FRACTURE) spikes else spread
        drawTintedAsset(layer, center, width * intensity.impactScale, width * intensity.impactScale, spreadAlpha, palette.primary, if (reducedMotion) 0f else local * 0.010f)
    }
    if (layerCount < 4) return

    val spikesAlpha = modularPulse(local, 4, 34, if (isFinal) 300 else 185) * if (isFinal) 0.66f else 0.28f
    if (spikesAlpha > 0f) {
        val width = size.width * if (isFinal) 0.38f else 0.21f
        drawTintedAsset(spikes, center, width * intensity.impactScale, width * intensity.impactScale, spikesAlpha, palette.primary, if (reducedMotion) 0f else -4f + local * 0.025f)
    }
    if (layerCount < 5) return

    val debrisAlpha = modularPulse(local, 18, 76, 420) * 0.62f
    if (debrisAlpha > 0f) {
        val travel = if (reducedMotion) 0f else modularFraction(local, 18, 420)
        val drift = Offset(size.width * 0.03f * travel, -size.height * 0.04f * travel)
        val width = size.width * 0.45f * intensity.impactScale
        drawTintedAsset(debris, center + drift, width, width, debrisAlpha, palette.secondary, if (reducedMotion) 0f else travel * 8f)
    }
}

private fun DrawScope.drawAuthoritativeAssetFrame(
    asset: ImageBitmap,
    frame: AuthoredLayerFrame,
) {
    val center = Offset(size.width * frame.xFraction, size.height * frame.yFraction)
    val width = size.width * frame.widthFraction
    val height = size.height * frame.heightFraction
    fun draw(alpha: Float) = when (frame.drawMode) {
        AuthoredAssetDrawMode.AUTHORED_SCREEN -> drawAuthoredAsset(
            asset = asset,
            center = center,
            width = width,
            height = height,
            alpha = alpha,
            rotation = frame.rotationDegrees,
            direction = frame.mirror,
            // The authoritative planner already applied the growth-stage overflow policy.
            // Re-fitting during draw would shrink the exact same frame a second time.
            fitToCanvas = false,
        )
        AuthoredAssetDrawMode.LEGACY_UNTINTED -> drawUntintedAsset(
            asset = asset,
            center = center,
            width = width,
            height = height,
            alpha = alpha,
            rotation = frame.rotationDegrees,
            direction = frame.mirror,
        )
        AuthoredAssetDrawMode.LEGACY_TINTED -> drawTintedAsset(
            asset = asset,
            center = center,
            width = width,
            height = height,
            alpha = alpha,
            tint = Color(checkNotNull(frame.tintArgb) { "Tinted planner frame requires tintArgb" }),
            rotation = frame.rotationDegrees,
            direction = frame.mirror,
        )
        AuthoredAssetDrawMode.LEGACY_STEEL_REVEAL -> drawSteelSlashAsset(
            asset = asset,
            center = center,
            width = width,
            height = height,
            alpha = alpha,
            reveal = frame.reveal,
        )
    }
    draw(frame.alpha)
}

private fun DrawScope.drawTintedAsset(
    asset: ImageBitmap,
    center: Offset,
    width: Float,
    height: Float,
    alpha: Float,
    tint: Color,
    rotation: Float = 0f,
    direction: Float = 1f,
) {
    if (alpha <= 0f || width <= 1f || height <= 1f) return
    withTransform({
        rotate(rotation, center)
        scale(direction, 1f, center)
    }) {
        drawImage(
            image = asset,
            dstOffset = IntOffset((center.x - width / 2f).toInt(), (center.y - height / 2f).toInt()),
            dstSize = IntSize(width.toInt().coerceAtLeast(1), height.toInt().coerceAtLeast(1)),
            alpha = alpha.coerceIn(0f, 1f),
            colorFilter = ColorFilter.tint(tint, BlendMode.Modulate),
            blendMode = BlendMode.Screen,
        )
    }
}

private fun DrawScope.drawUntintedAsset(
    asset: ImageBitmap,
    center: Offset,
    width: Float,
    height: Float,
    alpha: Float,
    rotation: Float = 0f,
    direction: Float = 1f,
) {
    if (alpha <= 0f || width <= 1f || height <= 1f) return
    withTransform({
        rotate(rotation, center)
        scale(direction, 1f, center)
    }) {
        drawImage(
            image = asset,
            dstOffset = IntOffset((center.x - width / 2f).toInt(), (center.y - height / 2f).toInt()),
            dstSize = IntSize(width.toInt().coerceAtLeast(1), height.toInt().coerceAtLeast(1)),
            alpha = alpha.coerceIn(0f, 1f),
            blendMode = BlendMode.SrcOver,
        )
    }
}

/** Preserves the generated resource palette while treating its authored black as transparent light. */
private fun DrawScope.drawAuthoredAsset(
    asset: ImageBitmap,
    center: Offset,
    width: Float,
    height: Float,
    alpha: Float,
    rotation: Float = 0f,
    direction: Float = 1f,
    fitToCanvas: Boolean = true,
) {
    if (alpha <= 0f || width <= 1f || height <= 1f) return
    val placement = if (fitToCanvas) {
        safeAuthoredAssetPlacement(
            canvasWidth = size.width,
            canvasHeight = size.height,
            centerX = center.x,
            centerY = center.y,
            width = width,
            height = height,
            rotationDegrees = rotation,
        )
    } else {
        val radians = Math.toRadians(rotation.toDouble())
        val cosValue = abs(cos(radians).toFloat())
        val sinValue = abs(sin(radians).toFloat())
        AuthoredAssetPlacement(
            centerX = center.x,
            centerY = center.y,
            width = width,
            height = height,
            rotatedWidth = cosValue * width + sinValue * height,
            rotatedHeight = sinValue * width + cosValue * height,
        )
    }
    val safeCenter = Offset(placement.centerX, placement.centerY)
    withTransform({
        rotate(rotation, safeCenter)
        scale(direction, 1f, safeCenter)
    }) {
        drawImage(
            image = asset,
            dstOffset = IntOffset((safeCenter.x - placement.width / 2f).toInt(), (safeCenter.y - placement.height / 2f).toInt()),
            dstSize = IntSize(placement.width.toInt().coerceAtLeast(1), placement.height.toInt().coerceAtLeast(1)),
            alpha = alpha.coerceIn(0f, 1f),
            blendMode = BlendMode.Screen,
        )
    }
}

private fun DrawScope.drawSteelSlashAsset(
    asset: ImageBitmap,
    center: Offset,
    width: Float,
    height: Float,
    alpha: Float,
    reveal: Float,
) {
    if (alpha <= 0f || reveal <= 0f) return
    val left = center.x - width / 2f
    val top = center.y - height / 2f
    clipRect(left, top, left + width * reveal.coerceIn(0f, 1f), top + height) {
        drawImage(
            image = asset,
            dstOffset = IntOffset(left.toInt(), top.toInt()),
            dstSize = IntSize(width.toInt().coerceAtLeast(1), height.toInt().coerceAtLeast(1)),
            alpha = alpha.coerceIn(0f, 1f),
            blendMode = BlendMode.SrcOver,
        )
    }
}

private fun DrawScope.drawFierceDownwardStrikeAsset(
    asset: ImageBitmap,
    center: Offset,
    width: Float,
    height: Float,
    alpha: Float,
    reveal: Float,
) {
    if (alpha <= 0f || reveal <= 0f) return
    val left = center.x - width / 2f
    val top = center.y - height / 2f
    val visibleHeight = height * reveal.coerceIn(0f, 1f)
    clipRect(left, top, left + width, top + visibleHeight) {
        drawImage(
            image = asset,
            dstOffset = IntOffset(left.toInt(), top.toInt()),
            dstSize = IntSize(width.toInt().coerceAtLeast(1), height.toInt().coerceAtLeast(1)),
            alpha = alpha.coerceIn(0f, 1f),
            blendMode = BlendMode.SrcOver,
        )
    }
}

private fun DrawScope.drawTintedAssetRevealedVertical(
    asset: ImageBitmap,
    center: Offset,
    width: Float,
    height: Float,
    alpha: Float,
    tint: Color,
    reveal: Float,
    fromBottom: Boolean,
    direction: Float = 1f,
) {
    val left = center.x - width / 2f
    val top = center.y - height / 2f
    val visibleHeight = height * reveal.coerceIn(0f, 1f)
    val clipTop = if (fromBottom) top + height - visibleHeight else top
    val clipBottom = if (fromBottom) top + height else top + visibleHeight
    clipRect(left, clipTop, left + width, clipBottom) {
        drawTintedAsset(asset, center, width, height, alpha, tint, direction = direction)
    }
}

private fun DrawScope.drawTintedAssetRevealedHorizontal(
    asset: ImageBitmap,
    center: Offset,
    width: Float,
    height: Float,
    alpha: Float,
    tint: Color,
    reveal: Float,
    direction: Float,
) {
    val left = center.x - width / 2f
    val top = center.y - height / 2f
    val visibleWidth = width * reveal.coerceIn(0f, 1f)
    val clipLeft = if (direction < 0f) left + width - visibleWidth else left
    val clipRight = if (direction < 0f) left + width else left + visibleWidth
    clipRect(clipLeft, top, clipRight, top + height) {
        drawTintedAsset(asset, center, width, height, alpha, tint, direction = direction)
    }
}

private fun modularPulse(local: Int, start: Int, peak: Int, end: Int): Float = when {
    local < start || local >= end -> 0f
    local < peak -> modularEaseOut(modularFraction(local, start, peak))
    else -> 1f - modularFraction(local, peak, end)
}

private fun modularFraction(value: Int, start: Int, end: Int): Float =
    if (end <= start) 1f else ((value - start).toFloat() / (end - start).toFloat()).coerceIn(0f, 1f)

private fun modularEaseOut(value: Float): Float = 1f - (1f - value) * (1f - value)
