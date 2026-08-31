package com.nullplaying.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.nullplaying.engine.SkillCatalog
import com.nullplaying.engine.SkillDefinition
import com.nullplaying.engine.SkillElement
import com.nullplaying.engine.SkillMotion
import kotlin.math.PI
import kotlin.math.sin

internal const val SKILL_PRESENTATION_DURATION_MILLIS = 1_400
internal const val SKILL_VFX_END_MILLIS = 1_350
internal const val DAMAGE_DISPLAY_EXTENSION_MILLIS = 500
internal const val MIN_DAMAGE_READ_MILLIS = 72
private const val FINAL_DAMAGE_BASE_DURATION_MILLIS = 420
private const val BLADE_DAMAGE_BASE_DURATION_MILLIS = 400

internal data class SkillDamageFrame(
    val visible: Boolean,
    val damage: Long,
    val alpha: Float,
    val scale: Float,
    val translationY: Float,
    val isFinal: Boolean,
)

internal data class SkillCameraFrame(
    val translationX: Float = 0f,
    val translationY: Float = 0f,
    val scale: Float = 1f,
)

internal data class SkillPresentationHit(
    val timingMillis: Int,
    val sourceHitIndices: List<Int>,
    val weight: Int,
)

internal data class SkillPalette(
    val backdrop: Color,
    val primary: Color,
    val secondary: Color,
    val damage: Color,
    val maxBackdropAlpha: Float,
)

internal fun skillDefinition(catalogId: String): SkillDefinition? = SkillCatalog.find(catalogId)

internal fun splitSkillDamage(totalDamage: Long, definition: SkillDefinition): List<Long> =
    SkillCatalog.splitDamage(totalDamage, definition.hitWeights)

internal fun skillPresentationHits(definition: SkillDefinition): List<SkillPresentationHit> =
    definition.hitTimingsMillis.mapIndexed { index, timing ->
        SkillPresentationHit(
            timingMillis = timing,
            sourceHitIndices = listOf(index),
            weight = definition.hitWeights[index],
        )
    }

internal fun cumulativeSkillDamage(totalDamage: Long, definition: SkillDefinition): List<Long> {
    val rawDamages = splitSkillDamage(totalDamage, definition)
    var accumulated = 0L
    return rawDamages.map { damage ->
        accumulated += damage
        accumulated
    }
}

private fun isBladeSlash(definition: SkillDefinition): Boolean = definition.catalogId == "warrior_t01_c01"

internal fun skillVfxEndMillis(definition: SkillDefinition): Int =
    detailedSpriteSheetSpec(definition.catalogId)?.let(::detailedSpriteDurationMillis)
        ?: SKILL_VFX_END_MILLIS

internal fun skillLabelAlpha(elapsedMillis: Int, definition: SkillDefinition): Float {
    val vfxEnd = skillVfxEndMillis(definition)
    if (elapsedMillis !in 0 until vfxEnd) return 0f
    val firstHit = definition.hitTimingsMillis.first()
    val enterStart = (firstHit - 140).coerceAtLeast(0)
    val enterEnd = (enterStart + 80).coerceAtMost(firstHit)
    val exitStart = (vfxEnd - 90).coerceAtLeast(enterEnd)
    return when {
        elapsedMillis < enterStart -> 0f
        elapsedMillis < enterEnd -> fraction(elapsedMillis, enterStart, enterEnd)
        elapsedMillis < exitStart -> 1f
        elapsedMillis < vfxEnd -> 1f - fraction(elapsedMillis, exitStart, vfxEnd)
        else -> 0f
    }
}

internal fun skillFinalDamageEndMillis(definition: SkillDefinition): Int {
    val baseDuration = if (isBladeSlash(definition)) {
        BLADE_DAMAGE_BASE_DURATION_MILLIS
    } else {
        FINAL_DAMAGE_BASE_DURATION_MILLIS
    }
    return minOf(
        definition.hitTimingsMillis.last() + baseDuration + DAMAGE_DISPLAY_EXTENSION_MILLIS,
        SKILL_PRESENTATION_DURATION_MILLIS,
    )
}

internal fun skillDamageFrame(
    elapsedMillis: Int,
    definition: SkillDefinition,
    totalDamage: Long,
): SkillDamageFrame {
    val presentationHits = skillPresentationHits(definition)
    val timings = presentationHits.map(SkillPresentationHit::timingMillis)
    val damages = cumulativeSkillDamage(totalDamage, definition)
    val hitIndex = timings.indices.lastOrNull { elapsedMillis >= timings[it] }
        ?: return SkillDamageFrame(false, 0L, 0f, 1f, 0f, false)
    val isFinal = hitIndex == timings.lastIndex
    val start = timings[hitIndex]
    val end = timings.getOrNull(hitIndex + 1) ?: skillFinalDamageEndMillis(definition)
    if (end <= start || elapsedMillis >= end) {
        return SkillDamageFrame(false, 0L, 0f, 1f, 0f, isFinal)
    }
    val pulseEnd = minOf(start + 72, end)
    val pulseProgress = fraction(elapsedMillis, start, pulseEnd)
    val fadeStart = if (isFinal) (end - 160).coerceAtLeast(pulseEnd) else end
    val alpha = when {
        elapsedMillis < pulseEnd -> 0.72f + 0.28f * easeOut(pulseProgress)
        !isFinal || elapsedMillis < fadeStart -> 1f
        else -> 1f - fraction(elapsedMillis, fadeStart, end)
    }.coerceIn(0f, 1f)
    val peakScale = if (isFinal) 1.15f else 1.08f
    val scale = if (elapsedMillis < pulseEnd) {
        peakScale - (peakScale - 1f) * easeOut(pulseProgress)
    } else if (isFinal && elapsedMillis >= fadeStart) {
        1f - 0.04f * fraction(elapsedMillis, fadeStart, end)
    } else {
        1f
    }
    val translationY = if (isFinal && elapsedMillis >= fadeStart) {
        -16f * fraction(elapsedMillis, fadeStart, end)
    } else {
        0f
    }
    return SkillDamageFrame(true, damages[hitIndex], alpha, scale, translationY, isFinal)
}

internal fun skillEnergyFraction(
    elapsedMillis: Int,
    startFraction: Float,
    endFraction: Float,
    definition: SkillDefinition,
    reducedMotion: Boolean = false,
): Float {
    val start = startFraction.coerceIn(0f, 1f)
    val end = endFraction.coerceIn(0f, 1f)
    val presentationHits = skillPresentationHits(definition)
    if (reducedMotion) {
        var cumulativeWeight = 0
        presentationHits.forEachIndexed { index, hit ->
            if (elapsedMillis < hit.timingMillis) {
                return if (index == 0) start else start + (end - start) * cumulativeWeight / 100f
            }
            cumulativeWeight += hit.weight
        }
        return end
    }
    var previous = start
    var cumulativeWeight = 0
    presentationHits.forEachIndexed { index, hit ->
        cumulativeWeight += hit.weight
        val target = if (index == presentationHits.lastIndex) end
        else start + (end - start) * cumulativeWeight / 100f
        val requestedDuration = if (index == presentationHits.lastIndex) 96 else MIN_DAMAGE_READ_MILLIS
        val duration = minOf(
            requestedDuration,
            presentationHits.getOrNull(index + 1)?.timingMillis?.minus(hit.timingMillis)
                ?: requestedDuration,
        ).coerceAtLeast(1)
        if (elapsedMillis < hit.timingMillis) return previous.coerceIn(0f, 1f)
        if (elapsedMillis < hit.timingMillis + duration) {
            val step = fraction(elapsedMillis, hit.timingMillis, hit.timingMillis + duration)
            return (previous + (target - previous) * easeOut(step)).coerceIn(0f, 1f)
        }
        previous = target
    }
    return end
}

internal fun skillCameraFrame(elapsedMillis: Int, definition: SkillDefinition): SkillCameraFrame {
    if (isBladeSlash(definition)) {
        return when (elapsedMillis) {
            in 360 until 404 -> SkillCameraFrame(scale = 1f + 0.004f * fraction(elapsedMillis, 360, 404))
            in 404 until 420 -> {
                val progress = fraction(elapsedMillis, 404, 420)
                SkillCameraFrame(-3.6f * progress, scale = 1.004f + 0.012f * progress)
            }
            in 420 until 452 -> {
                val progress = easeOut(fraction(elapsedMillis, 420, 452))
                SkillCameraFrame(-3.6f + 4.8f * progress, scale = 1.016f - 0.006f * progress)
            }
            in 452 until 520 -> {
                val progress = easeOut(fraction(elapsedMillis, 452, 520))
                SkillCameraFrame(1.2f * (1f - progress), scale = 1.010f - 0.010f * progress)
            }
            else -> SkillCameraFrame()
        }
    }
    val amplitude = when (definition.motion) {
        SkillMotion.HEAVY_FALL, SkillMotion.PILLAR_DROP, SkillMotion.ERUPTION_UP,
        SkillMotion.GRAVITY_COLLAPSE -> 4.2f
        SkillMotion.DASH_IMPACT, SkillMotion.SUMMON_DIVE, SkillMotion.EXECUTE_PAUSE -> 3.6f
        SkillMotion.CLEAVE_HORIZONTAL, SkillMotion.CROSS_SLASH, SkillMotion.PIERCE_LINE,
        SkillMotion.BEAM_CHANNEL, SkillMotion.PROJECTILE_SINGLE, SkillMotion.CHAIN_ARC -> 2.8f
        else -> 2.1f
    } * floatArrayOf(0.50f, 0.64f, 0.77f, 0.90f, 1.03f)[definition.intensityTier.coerceIn(1, 5) - 1]
    val verticalMotion = definition.motion in setOf(
        SkillMotion.HEAVY_FALL, SkillMotion.PILLAR_DROP, SkillMotion.ERUPTION_UP,
        SkillMotion.RAIN_VERTICAL, SkillMotion.SUMMON_DIVE,
    )
    val pulseOnly = definition.motion in setOf(
        SkillMotion.NOVA_RADIAL, SkillMotion.SPIN_CUT, SkillMotion.TRAP_SNAP,
        SkillMotion.GRAVITY_COLLAPSE,
    )
    val presentationHits = skillPresentationHits(definition)
    presentationHits.indices.reversed().forEach { index ->
        val timing = presentationHits[index].timingMillis
        val zoomStart = timing - 30
        val shakeEnd = timing + if (index == presentationHits.lastIndex) 120 else 70
        val zoomEnd = timing + 120
        if (elapsedMillis in zoomStart..zoomEnd) {
            val shakeProgress = fraction(elapsedMillis, timing, shakeEnd)
            val finalBoost = if (index == presentationHits.lastIndex) 1.15f else when (presentationHits.size) {
                1 -> 1f
                2 -> 0.82f
                3 -> 0.70f
                4 -> 0.60f
                else -> 0.52f
            }
            val wave = if (elapsedMillis < timing || elapsedMillis > shakeEnd) 0f else {
                val oscillation = sin((shakeProgress * PI * 2.5).toFloat())
                val impulse = if (shakeProgress < 0.32f) 0.18f * (1f - shakeProgress / 0.32f) else 0f
                (oscillation + impulse) * (1f - shakeProgress) * (1f - shakeProgress)
            }
            val strength = (amplitude * finalBoost).coerceAtMost(5.2f)
            val zoomProgress = if (elapsedMillis < timing) fraction(elapsedMillis, zoomStart, timing)
            else 1f - fraction(elapsedMillis, timing, zoomEnd)
            return SkillCameraFrame(
                translationX = if (verticalMotion || pulseOnly) 0f else wave * strength,
                translationY = if (verticalMotion && !pulseOnly) wave * strength else 0f,
                scale = 1f + zoomProgress.coerceIn(0f, 1f) * 0.018f * finalBoost.coerceAtMost(1f) *
                    floatArrayOf(0.42f, 0.58f, 0.73f, 0.88f, 1f)[definition.intensityTier.coerceIn(1, 5) - 1],
            )
        }
    }
    return SkillCameraFrame()
}

internal fun skillPalette(element: SkillElement): SkillPalette = when (element) {
    SkillElement.PHYSICAL -> SkillPalette(Color(0xFF707784), Color(0xFFF6F0E7), Color(0xFFA9B2C2), Color(0xFFF6F0E7), 0.09f)
    SkillElement.FIRE -> SkillPalette(Color(0xFF6A1715), Color(0xFFFFB45F), Color(0xFFE6612C), Color(0xFFFFB45F), 0.14f)
    SkillElement.ICE -> SkillPalette(Color(0xFF123652), Color(0xFFB9EDFF), Color(0xFF72D4FF), Color(0xFFB9EDFF), 0.13f)
    SkillElement.LIGHTNING -> SkillPalette(Color(0xFF182858), Color(0xFFDDF7FF), Color(0xFF8DBFFF), Color(0xFFDDF7FF), 0.12f)
    SkillElement.ARCANE -> SkillPalette(Color(0xFF243A78), Color(0xFFD3B6FF), Color(0xFF845CCB), Color(0xFFD3B6FF), 0.14f)
    SkillElement.DARK -> SkillPalette(Color(0xFF190C24), Color(0xFFE0A7FF), Color(0xFF52216B), Color(0xFFE0A7FF), 0.14f)
    SkillElement.POISON -> SkillPalette(Color(0xFF102B19), Color(0xFFB8E879), Color(0xFF70B84F), Color(0xFFB8E879), 0.14f)
    SkillElement.WIND -> SkillPalette(Color(0xFF123B3A), Color(0xFFA7E9DF), Color(0xFF69D3C9), Color(0xFFA7E9DF), 0.11f)
    SkillElement.EARTH -> SkillPalette(Color(0xFF2B1C12), Color(0xFFE2BE79), Color(0xFFA87B3E), Color(0xFFE2BE79), 0.14f)
    SkillElement.HOLY -> SkillPalette(Color(0xFF3B2C10), Color(0xFFFFF0AD), Color(0xFFF0C454), Color(0xFFFFF0AD), 0.12f)
    SkillElement.COSMIC -> SkillPalette(Color(0xFF17183D), Color(0xFFD9D8FF), Color(0xFF7774D8), Color(0xFFD9D8FF), 0.14f)
}

@Composable
internal fun SkillEffectLayer(
    definition: SkillDefinition,
    elapsedMillis: Int,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    ModularSkillEffectLayer(definition, elapsedMillis, reducedMotion, modifier)
}

private fun easeOut(value: Float): Float = 1f - (1f - value) * (1f - value)

private fun fraction(value: Int, start: Int, end: Int): Float =
    if (end <= start) 1f else ((value - start).toFloat() / (end - start)).coerceIn(0f, 1f)

private fun fraction(value: Float, start: Float, end: Float): Float =
    if (end <= start) 1f else ((value - start) / (end - start)).coerceIn(0f, 1f)
