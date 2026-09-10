package com.nullplaying.ui

import android.animation.ValueAnimator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import com.nullplaying.localization.AppLanguage
import java.util.Locale

internal data class ArenaCombatFighterUi(
    val name: String,
    val classLabel: String,
    val level: Long,
    val hpFraction: Float,
    val mpFraction: Float,
    val shieldFraction: Float,
)

internal const val ARENA_COMBAT_STAGE_HEIGHT_DP = 218
internal const val ARENA_COMBAT_HEADER_HEIGHT_DP = 58
internal const val ARENA_COMBAT_VIEWPORT_HEIGHT_DP = 160
internal const val ARENA_COMBAT_HP_HEIGHT_DP = 4
internal const val ARENA_COMBAT_MP_HEIGHT_DP = 2
internal const val ARENA_COMBAT_GAUGE_GAP_DP = 2
internal const val ARENA_COMBAT_IDENTITY_GAUGE_GAP_DP = 4

internal val ArenaCombatShieldColor = Color(0xFF64E5DB)
internal val ArenaCombatMpColor = Color(0xFFB49ADC)
private val ArenaCombatBlue = Color(0xFF78A8D8)
private val ArenaCombatCrimson = Color(0xFFB94B58)
private val ArenaCombatGaugeTrack = Color(0xFF2A2431)

/** Physical left-to-right coordinates within one half of the shared bar. */
internal data class ArenaGaugeSegment(val start: Float, val end: Float) {
    val length: Float get() = end - start
}

internal data class ArenaCombatGaugeGeometry(
    val hp: ArenaGaugeSegment,
    val mp: ArenaGaugeSegment,
    val shield: ArenaGaugeSegment?,
)

/**
 * Every fraction uses the fighter's original maximum; shields never change the HP scale.
 * Shield first extends beyond the outer HP endpoint. Any overflow overlays the outer tip
 * of the same bar, leaving the underlying HP geometry unchanged (including at full HP).
 */
internal fun arenaCombatGaugeGeometry(
    hpFraction: Float,
    mpFraction: Float,
    shieldFraction: Float,
    rightSide: Boolean,
): ArenaCombatGaugeGeometry {
    val hp = arenaCombatSafeFraction(hpFraction)
    val mp = arenaCombatSafeFraction(mpFraction)
    val shield = arenaCombatSafeFraction(shieldFraction)
    fun physical(inner: Float, outer: Float): ArenaGaugeSegment = if (rightSide) {
        ArenaGaugeSegment(inner, outer)
    } else {
        ArenaGaugeSegment(1f - outer, 1f - inner)
    }
    val shieldSegment = if (shield > 0f) {
        val outer = (hp + shield).coerceAtMost(1f)
        physical((outer - shield).coerceAtLeast(0f), outer)
    } else null
    return ArenaCombatGaugeGeometry(hp = physical(0f, hp), mp = physical(0f, mp), shield = shieldSegment)
}

private fun arenaCombatSafeFraction(value: Float): Float = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f

/** Same fixed native battle-stage footprint; playback and combat rules belong to the caller. */
@Composable
internal fun ArenaCombatStage(
    left: ArenaCombatFighterUi,
    right: ArenaCombatFighterUi,
    message: String?,
    tint: Color,
    backgroundResourceId: Int,
    modifier: Modifier = Modifier,
    horizontalInset: Dp = 16.dp,
    skillCatalogId: String? = null,
    skillElapsedMillis: Int = SKILL_PRESENTATION_DURATION_MILLIS,
    skillMirrored: Boolean = false,
) {
    val skill = skillCatalogId?.let(::skillDefinition)
    Card(
        modifier = modifier.fillMaxWidth().height(ARENA_COMBAT_STAGE_HEIGHT_DP.dp).padding(horizontal = horizontalInset),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(22.dp),
    ) {
        Box(Modifier.fillMaxSize().border(1.dp, AqGoldSoft, RoundedCornerShape(22.dp))) {
            Image(painterResource(backgroundResourceId), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xB8120E19), Color(0xA8181220), Color(0xF015101B)))))
            Box(Modifier.fillMaxSize().background(tint))
            if (skill != null) {
                SkillEffectLayer(
                    definition = skill,
                    elapsedMillis = skillElapsedMillis.coerceIn(0, SKILL_PRESENTATION_DURATION_MILLIS),
                    reducedMotion = !ValueAnimator.areAnimatorsEnabled(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ARENA_COMBAT_VIEWPORT_HEIGHT_DP.dp)
                        .graphicsLayer { scaleX = if (skillMirrored) -1f else 1f }
                        .align(Alignment.BottomCenter),
                )
            }
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxWidth().height(ARENA_COMBAT_HEADER_HEIGHT_DP.dp)
                        .background(Color(0xB817111F)).border(1.dp, AqGoldSoft)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ArenaCombatIdentity(left, rightSide = false, modifier = Modifier.weight(1f))
                        ArenaCombatIdentity(right, rightSide = true, modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(ARENA_COMBAT_IDENTITY_GAUGE_GAP_DP.dp))
                    ArenaCombatEnergyBar(left, right)
                    Spacer(Modifier.height(ARENA_COMBAT_GAUGE_GAP_DP.dp))
                    ArenaCombatManaBar(left, right)
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(ARENA_COMBAT_VIEWPORT_HEIGHT_DP.dp)
                        .padding(start = 14.dp, end = 14.dp, top = 12.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    if (!message.isNullOrBlank()) {
                        Box(
                            Modifier.fillMaxWidth().background(Color(0xB317111F), RoundedCornerShape(10.dp))
                                .border(.8.dp, AqGoldSoft.copy(alpha = .45f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            MaterialText(message, color = AqText, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArenaCombatIdentity(fighter: ArenaCombatFighterUi, rightSide: Boolean, modifier: Modifier) {
    Column(modifier, horizontalAlignment = if (rightSide) Alignment.End else Alignment.Start) {
        MaterialText(fighter.name, color = AqText, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
        MaterialText("${fighter.classLabel} · Lv.${fighter.level}", color = AqMuted, fontSize = 9.sp, lineHeight = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ArenaCombatEnergyBar(left: ArenaCombatFighterUi, right: ArenaCombatFighterUi) {
    val language = LocalAppLanguage.current
    val leftGeometry = arenaCombatGaugeGeometry(left.hpFraction, left.mpFraction, left.shieldFraction, rightSide = false)
    val rightGeometry = arenaCombatGaugeGeometry(right.hpFraction, right.mpFraction, right.shieldFraction, rightSide = true)
    val shieldLabel = when (language) { AppLanguage.KOREAN -> "실드"; AppLanguage.JAPANESE -> "シールド"; AppLanguage.ENGLISH -> "shield" }
    Canvas(
        Modifier.fillMaxWidth().height(ARENA_COMBAT_HP_HEIGHT_DP.dp).clip(RoundedCornerShape(99.dp))
            .background(ArenaCombatGaugeTrack).border(.5.dp, AqGoldSoft.copy(alpha = .75f), RoundedCornerShape(99.dp))
            .semantics {
                contentDescription = "${left.name} / ${right.name} HP · $shieldLabel"
                stateDescription = "${left.name} HP ${arenaCombatPercent(left.hpFraction)}%, $shieldLabel ${arenaCombatPercent(left.shieldFraction)}%; " +
                    "${right.name} HP ${arenaCombatPercent(right.hpFraction)}%, $shieldLabel ${arenaCombatPercent(right.shieldFraction)}%"
            },
    ) {
        val divider = 3.dp.toPx().coerceAtMost(size.width)
        val half = (size.width - divider) / 2f
        drawArenaSegment(leftGeometry.hp, 0f, half, Brush.horizontalGradient(listOf(Color(0xFF2258A9), ArenaCombatBlue), startX = 0f, endX = half))
        drawArenaSegment(rightGeometry.hp, half + divider, half, Brush.horizontalGradient(listOf(ArenaCombatCrimson, Color(0xFF8C243A)), startX = half + divider, endX = size.width))
        leftGeometry.shield?.let { drawArenaSegment(it, 0f, half, ArenaCombatShieldColor) }
        rightGeometry.shield?.let { drawArenaSegment(it, half + divider, half, ArenaCombatShieldColor) }
        drawRect(AqText, Offset(half, 0f), Size(divider, size.height))
    }
}

@Composable
private fun ArenaCombatManaBar(left: ArenaCombatFighterUi, right: ArenaCombatFighterUi) {
    val leftGeometry = arenaCombatGaugeGeometry(left.hpFraction, left.mpFraction, left.shieldFraction, rightSide = false)
    val rightGeometry = arenaCombatGaugeGeometry(right.hpFraction, right.mpFraction, right.shieldFraction, rightSide = true)
    Canvas(
        Modifier.fillMaxWidth().height(ARENA_COMBAT_MP_HEIGHT_DP.dp).clip(RoundedCornerShape(99.dp)).background(ArenaCombatGaugeTrack)
            .semantics {
                contentDescription = "${left.name} / ${right.name} MP"
                stateDescription = "${left.name} MP ${arenaCombatPercent(left.mpFraction)}%; ${right.name} MP ${arenaCombatPercent(right.mpFraction)}%"
            },
    ) {
        val divider = 3.dp.toPx().coerceAtMost(size.width)
        val half = (size.width - divider) / 2f
        drawArenaSegment(leftGeometry.mp, 0f, half, ArenaCombatMpColor)
        drawArenaSegment(rightGeometry.mp, half + divider, half, ArenaCombatMpColor)
        drawRect(AqText.copy(alpha = .5f), Offset(half, 0f), Size(divider, size.height))
    }
}

private fun DrawScope.drawArenaSegment(segment: ArenaGaugeSegment, origin: Float, width: Float, color: Color) {
    if (segment.length > 0 && width > 0) drawRect(color, Offset(origin + segment.start * width, 0f), Size(segment.length * width, size.height))
}

private fun DrawScope.drawArenaSegment(segment: ArenaGaugeSegment, origin: Float, width: Float, brush: Brush) {
    if (segment.length > 0 && width > 0) drawRect(brush, Offset(origin + segment.start * width, 0f), Size(segment.length * width, size.height))
}

private fun arenaCombatPercent(fraction: Float): String = String.format(Locale.ROOT, "%.3f", arenaCombatSafeFraction(fraction) * 100f).trimEnd('0').trimEnd('.')
