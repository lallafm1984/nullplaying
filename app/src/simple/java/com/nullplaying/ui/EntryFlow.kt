package com.nullplaying.ui

import android.animation.ValueAnimator
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.nullplaying.R
import com.nullplaying.data.MAX_CHARACTER_SLOTS
import com.nullplaying.data.nextCharacterSlotUnlockLevel
import com.nullplaying.data.StartupPhase
import com.nullplaying.model.SimpleGameState
import java.text.NumberFormat
import java.util.Locale
import java.util.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

internal enum class EntryScene {
    TITLE,
    ROSTER,
    CREATION,
    GAME,
}

internal const val PLANNED_CHARACTER_SLOT_COUNT = 3
internal const val ROSTER_CARD_HEIGHT_DP = 182
internal const val ROSTER_CARD_GAP_DP = 8
internal const val ROSTER_THREE_CARD_LIST_BUDGET_DP = 600

internal data class CharacterRosterEntry(
    val slotId: Int,
    val state: SimpleGameState,
    val combatPower: Long,
    val offlineAdventureProgress: Float,
)

internal fun rosterCardsHeightDp(count: Int): Int {
    val cardCount = count.coerceAtLeast(0)
    return cardCount * ROSTER_CARD_HEIGHT_DP +
        (cardCount - 1).coerceAtLeast(0) * ROSTER_CARD_GAP_DP
}

internal fun canCreateCharacter(characters: List<CharacterRosterEntry>, unlockedSlotCount: Int): Boolean =
    characters.size < unlockedSlotCount.coerceIn(0, MAX_CHARACTER_SLOTS)

internal fun characterRosterContentDescription(
    name: String,
    classLabel: String,
    level: Long,
    combatPower: Long,
    adventureTitle: String,
    offlinePercent: Int,
): String {
    val powerText = NumberFormat.getIntegerInstance(Locale.KOREA).format(combatPower)
    return "$name, $classLabel, 레벨 $level, 전투력 $powerText, 현재 모험 $adventureTitle, 오프라인 모험 잔여 ${offlinePercent}퍼센트, 이어하기"
}

private data class PendingCharacterDeletion(
    val slotId: Int,
    val characterName: String,
)

internal enum class TitleLayer {
    FAR,
    GLOW,
    MIST,
    FOREGROUND,
    DUST,
}

internal data class TitleLayerFrame(
    val xDp: Float,
    val yDp: Float,
    val scale: Float,
    val alpha: Float,
)

internal const val TITLE_MOTE_COUNT = 48
internal const val TITLE_DUST_LOOP_MILLIS = 24_000
private const val TITLE_MOTE_SEED = 20260816L

internal const val TITLE_INTRO_CLOSED_HOLD_MILLIS = 200
internal const val TITLE_INTRO_OPEN_MILLIS = 1_500
internal const val TITLE_INTRO_TOTAL_MILLIS =
    TITLE_INTRO_CLOSED_HOLD_MILLIS + TITLE_INTRO_OPEN_MILLIS
internal const val TITLE_INTRO_CURTAIN_FADE_MILLIS = 900
internal const val TITLE_INTRO_SKIP_MILLIS = 140
private const val TITLE_INTRO_EXIT_DISTANCE_FRACTION = 0.82f
// Keep motion legible for the full 1.5 seconds: a brief tension build, a broad
// middle travel, and a soft settle without the previous front-loaded snap.
private val TitleIntroOpenEasing = CubicBezierEasing(0.42f, 0f, 0.25f, 1f)

internal data class TitleIntroPresentation(
    val leftTranslationXFraction: Float,
    val rightTranslationXFraction: Float,
    val leftRotationDegrees: Float,
    val rightRotationDegrees: Float,
    val scale: Float,
    val curtainAlpha: Float,
    val shutterAlpha: Float,
    val overlayAlpha: Float,
)

internal fun titleIntroPresentation(
    progress: Float,
    playIntro: Boolean,
    reducedMotion: Boolean,
): TitleIntroPresentation {
    val resolvedProgress = if (!playIntro || reducedMotion) {
        1f
    } else {
        progress.coerceIn(0f, 1f)
    }
    val easedProgress = TitleIntroOpenEasing.transform(resolvedProgress)
    val exitDistance = TITLE_INTRO_EXIT_DISTANCE_FRACTION * easedProgress
    val curtainFadeProgress =
        TITLE_INTRO_CURTAIN_FADE_MILLIS.toFloat() / TITLE_INTRO_OPEN_MILLIS
    val curtainAlpha = (1f - resolvedProgress / curtainFadeProgress).coerceIn(0f, 1f)
    val shutterAlpha = ((1f - resolvedProgress) / 0.14f).coerceIn(0f, 1f)
    return TitleIntroPresentation(
        leftTranslationXFraction = -exitDistance,
        rightTranslationXFraction = exitDistance,
        leftRotationDegrees = -4f * easedProgress,
        rightRotationDegrees = 4f * easedProgress,
        // Keep the authored master at 1:1 scale. Scaling each clipped half around a
        // different outer pivot creates a visible vertical join while the shutter is closed.
        scale = 1f,
        curtainAlpha = curtainAlpha,
        shutterAlpha = shutterAlpha,
        overlayAlpha = maxOf(curtainAlpha, shutterAlpha),
    )
}

internal data class TitleIntroCallbackState(
    val introFinished: Boolean = false,
    val enterRequested: Boolean = false,
)

internal data class TitleIntroCallbackTransition(
    val state: TitleIntroCallbackState,
    val notifyIntroFinished: Boolean,
    val notifyEnterRequested: Boolean,
)

internal fun titleIntroCallbackTransition(
    state: TitleIntroCallbackState,
    finishIntro: Boolean,
    requestEnter: Boolean,
): TitleIntroCallbackTransition {
    val notifyIntroFinished = finishIntro && !state.introFinished
    val notifyEnterRequested = requestEnter && !state.enterRequested
    return TitleIntroCallbackTransition(
        state = TitleIntroCallbackState(
            introFinished = state.introFinished || finishIntro,
            enterRequested = state.enterRequested || requestEnter,
        ),
        notifyIntroFinished = notifyIntroFinished,
        notifyEnterRequested = notifyEnterRequested,
    )
}

internal data class TitleMote(
    val x: Float,
    val y: Float,
    val radiusDp: Float,
    val alpha: Float,
    val horizontalRange: Float,
    val verticalRange: Float,
    val loopCount: Int,
    val phase: Float,
    val tone: Int,
)

internal data class TitleMoteFrame(
    val x: Float,
    val y: Float,
    val pulse: Float,
)

internal fun titleMotes(
    count: Int = TITLE_MOTE_COUNT,
    seed: Long = TITLE_MOTE_SEED,
): List<TitleMote> {
    require(count >= 0)
    val random = Random(seed)
    return List(count) {
        val sizeRoll = random.nextFloat()
        val radiusDp = when {
            sizeRoll < 0.65f -> 0.45f + random.nextFloat() * 0.75f
            sizeRoll < 0.9f -> 1.2f + random.nextFloat() * 1.1f
            else -> 2.3f + random.nextFloat() * 1.4f
        }
        TitleMote(
            x = 0.04f + random.nextFloat() * 0.92f,
            y = 0.36f + random.nextFloat() * 0.58f,
            radiusDp = radiusDp,
            alpha = 0.32f + random.nextFloat() * 0.5f,
            horizontalRange = 0.005f + random.nextFloat() * 0.021f,
            verticalRange = 0.015f + random.nextFloat() * 0.05f,
            // Whole laps make each particle meet the loop seam at the same position and velocity.
            loopCount = 1 + random.nextInt(4),
            phase = random.nextFloat(),
            tone = random.nextInt(10),
        )
    }
}

internal fun titleMoteFrame(
    mote: TitleMote,
    phase: Float,
    reducedMotion: Boolean,
): TitleMoteFrame {
    val masterPhase = if (reducedMotion) 0f else phase.coerceIn(0f, 1f)
    val angle = (masterPhase * mote.loopCount + mote.phase) * 2f * PI.toFloat()
    val pulseAngle = angle * 2f + mote.phase * 2f * PI.toFloat()
    return TitleMoteFrame(
        x = (mote.x + sin(angle) * mote.horizontalRange).coerceIn(0.02f, 0.98f),
        y = (mote.y + cos(angle) * mote.verticalRange).coerceIn(0.34f, 0.97f),
        pulse = if (reducedMotion) {
            1f
        } else {
            0.72f + 0.28f * ((sin(pulseAngle) + 1f) * 0.5f)
        },
    )
}

internal fun titleLayerFrame(
    layer: TitleLayer,
    phase: Float,
    reducedMotion: Boolean,
): TitleLayerFrame {
    if (reducedMotion) {
        return when (layer) {
            TitleLayer.FAR -> TitleLayerFrame(0f, 0f, 1.055f, 1f)
            TitleLayer.GLOW -> TitleLayerFrame(0f, 0f, 1.055f, 0.34f)
            TitleLayer.MIST -> TitleLayerFrame(0f, 0f, 1.08f, 0.13f)
            TitleLayer.FOREGROUND -> TitleLayerFrame(0f, 0f, 1.095f, 0.84f)
            TitleLayer.DUST -> TitleLayerFrame(0f, 0f, 1f, 0.62f)
        }
    }
    val angle = phase.coerceIn(0f, 1f) * (2f * PI.toFloat())
    val wave = sin(angle).toFloat()
    val crossWave = cos(angle).toFloat()
    return when (layer) {
        TitleLayer.FAR -> TitleLayerFrame(
            xDp = wave * 4f,
            yDp = crossWave * 2f,
            scale = 1.055f + crossWave * 0.005f,
            alpha = 1f,
        )
        TitleLayer.GLOW -> TitleLayerFrame(
            xDp = wave * 2f,
            yDp = crossWave * 1.5f,
            scale = 1.055f + crossWave * 0.004f,
            alpha = 0.32f + crossWave * 0.09f,
        )
        TitleLayer.MIST -> TitleLayerFrame(
            xDp = wave * 10f,
            yDp = crossWave * 4f,
            scale = 1.08f + crossWave * 0.01f,
            alpha = 0.11f + crossWave * 0.06f,
        )
        TitleLayer.FOREGROUND -> TitleLayerFrame(
            xDp = -wave * 12f,
            yDp = -crossWave * 3f,
            scale = 1.095f + crossWave * 0.006f,
            alpha = 0.84f,
        )
        TitleLayer.DUST -> TitleLayerFrame(
            xDp = -wave * 7f,
            yDp = -crossWave * 5f,
            scale = 1f,
            alpha = 0.56f + crossWave * 0.18f,
        )
    }
}

internal fun titleStatusText(
    ready: Boolean,
    pendingEnter: Boolean,
    phase: StartupPhase,
): String = when {
    ready -> "화면을 터치해 시작"
    pendingEnter -> "모험 기록을 여는 중"
    phase == StartupPhase.SETTLING_OFFLINE -> "지난 모험을 정리하는 중"
    phase == StartupPhase.SAVING_RESULT -> "모험 기록을 정리하는 중"
    else -> "모험 기록을 불러오는 중"
}

private data class TitleMotion(
    val farPhase: Float,
    val glowPhase: Float,
    val mistPhase: Float,
    val foregroundPhase: Float,
    val dustPhase: Float,
    val promptAlpha: Float,
)

@Composable
private fun rememberTitleMotion(enabled: Boolean): TitleMotion {
    if (!enabled) {
        return TitleMotion(
            farPhase = 0f,
            glowPhase = 0f,
            mistPhase = 0f,
            foregroundPhase = 0f,
            dustPhase = 0f,
            promptAlpha = 0.9f,
        )
    }
    val transition = rememberInfiniteTransition(label = "title-parallax")
    val farPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(22_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "title-far-phase",
    )
    val glowPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(15_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "title-glow-phase",
    )
    val mistPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(13_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "title-mist-phase",
    )
    val foregroundPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(9_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "title-foreground-phase",
    )
    val dustPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(TITLE_DUST_LOOP_MILLIS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "title-dust-phase",
    )
    val promptAlpha by transition.animateFloat(
        initialValue = 0.58f,
        targetValue = 0.92f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "title-prompt-alpha",
    )
    return TitleMotion(farPhase, glowPhase, mistPhase, foregroundPhase, dustPhase, promptAlpha)
}

private fun DrawScope.drawTitleRasterLayer(
    image: ImageBitmap,
    frame: TitleLayerFrame,
    blendMode: BlendMode = BlendMode.SrcOver,
) {
    val fillScale = maxOf(
        size.width / image.width.toFloat(),
        size.height / image.height.toFloat(),
    )
    val drawWidth = (image.width * fillScale).roundToInt().coerceAtLeast(1)
    val drawHeight = (image.height * fillScale).roundToInt().coerceAtLeast(1)
    val destination = IntOffset(
        x = ((size.width - drawWidth) / 2f).roundToInt(),
        y = ((size.height - drawHeight) / 2f).roundToInt(),
    )
    val translationX = frame.xDp.dp.toPx()
    val translationY = frame.yDp.dp.toPx()
    withTransform({
        translate(translationX, translationY)
        scale(frame.scale, frame.scale, pivot = center)
    }) {
        drawImage(
            image = image,
            dstOffset = destination,
            dstSize = IntSize(drawWidth, drawHeight),
            alpha = frame.alpha.coerceIn(0f, 1f),
            blendMode = blendMode,
        )
    }
}

private fun DrawScope.drawTitleIntroSide(
    image: ImageBitmap,
    destinationOffset: IntOffset,
    destinationSize: IntSize,
    translationXFraction: Float,
    rotationDegrees: Float,
    scaleMultiplier: Float,
    alpha: Float,
    pivot: Offset,
) {
    withTransform({
        scale(scaleMultiplier, scaleMultiplier, pivot)
        rotate(rotationDegrees, pivot)
        translate(size.width * translationXFraction, 0f)
    }) {
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = destinationOffset,
            dstSize = destinationSize,
            alpha = alpha,
        )
    }
}

@Composable
private fun TitleIntroShutter(presentation: TitleIntroPresentation) {
    if (presentation.overlayAlpha <= 0f) return

    val leftShutter = ImageBitmap.imageResource(R.drawable.title_intro_thorn_shutter_left_v6)
    val rightShutter = ImageBitmap.imageResource(R.drawable.title_intro_thorn_shutter_right_v6)
    Canvas(Modifier.fillMaxSize()) {
        if (presentation.curtainAlpha > 0f) {
            drawRect(
                color = Color(0xFF08060D),
                alpha = presentation.curtainAlpha,
            )
        }
        if (presentation.shutterAlpha <= 0f) return@Canvas

        val fillScale = maxOf(
            size.width / leftShutter.width.toFloat(),
            size.height / leftShutter.height.toFloat(),
        )
        val drawWidth = (leftShutter.width * fillScale).roundToInt().coerceAtLeast(2)
        val drawHeight = (leftShutter.height * fillScale).roundToInt().coerceAtLeast(1)
        val destinationX = ((size.width - drawWidth) / 2f).roundToInt()
        val destinationY = ((size.height - drawHeight) / 2f).roundToInt()

        // Each side is a complete edge-rooted tree component. Moving the full
        // transparent layer preserves every tapered tip and never exposes a
        // synthetic vertical cut through the bark.
        drawTitleIntroSide(
            image = leftShutter,
            destinationOffset = IntOffset(destinationX, destinationY),
            destinationSize = IntSize(drawWidth, drawHeight),
            translationXFraction = presentation.leftTranslationXFraction,
            rotationDegrees = presentation.leftRotationDegrees,
            scaleMultiplier = presentation.scale,
            alpha = presentation.shutterAlpha,
            pivot = Offset(0f, size.height / 2f),
        )
        drawTitleIntroSide(
            image = rightShutter,
            destinationOffset = IntOffset(destinationX, destinationY),
            destinationSize = IntSize(drawWidth, drawHeight),
            translationXFraction = presentation.rightTranslationXFraction,
            rotationDegrees = presentation.rightRotationDegrees,
            scaleMultiplier = presentation.scale,
            alpha = presentation.shutterAlpha,
            pivot = Offset(size.width, size.height / 2f),
        )
    }
}

@Composable
private fun TitleArtLayers(
    farFrame: TitleLayerFrame,
    glowFrame: TitleLayerFrame,
    mistFrame: TitleLayerFrame,
    foregroundFrame: TitleLayerFrame,
    dustFrame: TitleLayerFrame,
    dustPhase: Float,
    animationsEnabled: Boolean,
) {
    val world = ImageBitmap.imageResource(R.drawable.title_layer_world_v2)
    val gateLight = ImageBitmap.imageResource(R.drawable.title_layer_gate_light_v2)
    val atmosphere = ImageBitmap.imageResource(R.drawable.title_layer_atmosphere_v2)
    val foreground = ImageBitmap.imageResource(R.drawable.title_layer_foreground_v2)

    Canvas(Modifier.fillMaxSize()) {
        drawTitleRasterLayer(world, farFrame)
        // The generated emission pass is authored on pure black, so Screen treats black as clear.
        drawTitleRasterLayer(gateLight, glowFrame, blendMode = BlendMode.Screen)
        drawTitleRasterLayer(atmosphere, mistFrame)
        drawTitleRasterLayer(foreground, foregroundFrame)

        val dustTranslationX = dustFrame.xDp.dp.toPx()
        val dustTranslationY = dustFrame.yDp.dp.toPx()
        withTransform({
            translate(dustTranslationX, dustTranslationY)
        }) {
            TITLE_MOTES.forEach { mote ->
                val moteFrame = titleMoteFrame(
                    mote = mote,
                    phase = dustPhase,
                    reducedMotion = !animationsEnabled,
                )
                val moteColor = when (mote.tone) {
                    in 0..4 -> Color(0xFF72C9D2)
                    in 5..7 -> AqGold
                    else -> Color(0xFFB78AD8)
                }
                val moteAlpha = dustFrame.alpha.coerceIn(0f, 1f) * mote.alpha * moteFrame.pulse
                if (mote.radiusDp >= 1.8f) {
                    drawCircle(
                        color = moteColor,
                        radius = (mote.radiusDp * 2.15f).dp.toPx(),
                        center = Offset(size.width * moteFrame.x, size.height * moteFrame.y),
                        alpha = moteAlpha * 0.16f,
                    )
                }
                drawCircle(
                    color = moteColor,
                    radius = mote.radiusDp.dp.toPx(),
                    center = Offset(size.width * moteFrame.x, size.height * moteFrame.y),
                    alpha = moteAlpha,
                )
            }
        }
    }
}

@Composable
internal fun TitleScene(
    ready: Boolean,
    pendingEnter: Boolean,
    startupPhase: StartupPhase,
    playIntro: Boolean,
    onIntroFinished: () -> Unit,
    onEnterRequested: () -> Unit,
) {
    // The title is the app's root destination, so back must not finish the Activity.
    BackHandler { }
    val animationsEnabled = ValueAnimator.areAnimatorsEnabled()
    val motion = rememberTitleMotion(animationsEnabled)
    val introProgress = remember {
        Animatable(if (playIntro && animationsEnabled) 0f else 1f)
    }
    var skipIntroRequested by remember { mutableStateOf(false) }
    var callbackState by remember { mutableStateOf(TitleIntroCallbackState()) }
    val currentOnIntroFinished by rememberUpdatedState(onIntroFinished)
    val currentOnEnterRequested by rememberUpdatedState(onEnterRequested)

    fun dispatchIntroCallbacks(finishIntro: Boolean, requestEnter: Boolean) {
        val transition = titleIntroCallbackTransition(
            state = callbackState,
            finishIntro = finishIntro,
            requestEnter = requestEnter,
        )
        callbackState = transition.state
        if (transition.notifyIntroFinished) currentOnIntroFinished()
        if (transition.notifyEnterRequested) currentOnEnterRequested()
    }

    LaunchedEffect(playIntro, animationsEnabled, skipIntroRequested) {
        when {
            !playIntro -> introProgress.snapTo(1f)
            !animationsEnabled -> {
                introProgress.snapTo(1f)
                dispatchIntroCallbacks(finishIntro = true, requestEnter = false)
            }
            skipIntroRequested -> {
                introProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = TITLE_INTRO_SKIP_MILLIS,
                        easing = LinearEasing,
                    ),
                )
                dispatchIntroCallbacks(finishIntro = true, requestEnter = true)
            }
            else -> {
                introProgress.snapTo(0f)
                delay(TITLE_INTRO_CLOSED_HOLD_MILLIS.toLong())
                introProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = TITLE_INTRO_OPEN_MILLIS,
                        easing = LinearEasing,
                    ),
                )
                dispatchIntroCallbacks(finishIntro = true, requestEnter = false)
            }
        }
    }

    val introPresentation = titleIntroPresentation(
        progress = introProgress.value,
        playIntro = playIntro,
        reducedMotion = !animationsEnabled,
    )
    val farFrame = titleLayerFrame(TitleLayer.FAR, motion.farPhase, !animationsEnabled)
    val glowFrame = titleLayerFrame(TitleLayer.GLOW, motion.glowPhase, !animationsEnabled)
    val mistFrame = titleLayerFrame(TitleLayer.MIST, motion.mistPhase, !animationsEnabled)
    val foregroundFrame = titleLayerFrame(
        TitleLayer.FOREGROUND,
        motion.foregroundPhase,
        !animationsEnabled,
    )
    val dustFrame = titleLayerFrame(TitleLayer.DUST, motion.dustPhase, !animationsEnabled)
    val status = titleStatusText(ready, pendingEnter, startupPhase)
    val brandName = localized("널 플레이잉")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AqBackground)
            .semantics(mergeDescendants = true) {
                contentDescription = localized("$brandName. $status. 모험가 선택 화면으로 이동")
            }
            .clickable(
                role = Role.Button,
                onClickLabel = localized("모험가 선택 화면으로 이동"),
                onClick = {
                    val introIsBlocking = playIntro &&
                        animationsEnabled &&
                        introProgress.value < 1f
                    if (introIsBlocking) {
                        if (!skipIntroRequested && !callbackState.enterRequested) {
                            skipIntroRequested = true
                        }
                    } else {
                        dispatchIntroCallbacks(
                            finishIntro = playIntro && !callbackState.introFinished,
                            requestEnter = true,
                        )
                    }
                },
            ),
    ) {
        TitleArtLayers(
            farFrame = farFrame,
            glowFrame = glowFrame,
            mistFrame = mistFrame,
            foregroundFrame = foregroundFrame,
            dustFrame = dustFrame,
            dustPhase = motion.dustPhase,
            animationsEnabled = animationsEnabled,
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color(0x55100C16),
                            0.24f to Color(0x22100C16),
                            0.62f to Color.Transparent,
                            1f to Color(0xE6100C16),
                        ),
                    ),
                )
                .background(
                    Brush.horizontalGradient(
                        listOf(Color(0x66100C16), Color.Transparent, Color(0x66100C16)),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = 84.dp, start = 24.dp, end = 24.dp)
                .clearAndSetSemantics { },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NullPlayingWordmark(
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .fillMaxWidth()
                    .height(62.dp),
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.brand_status_the_adventure),
                    color = AqMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.6.sp,
                    lineHeight = 16.sp,
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = stringResource(R.string.brand_status_continues),
                    color = AqGold,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.6.sp,
                    lineHeight = 16.sp,
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(start = 24.dp, end = 24.dp, bottom = 42.dp)
                .graphicsLayer {
                    alpha = if (ready) motion.promptAlpha else 0.84f
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .width(42.dp)
                    .height(1.dp)
                    .background(AqGold.copy(alpha = 0.74f)),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = status,
                color = if (ready) AqText else AqMuted,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }

        // Canvas has no content description or semantics modifier: the shutter is decorative
        // while the containing full-screen button remains the only accessibility node.
        TitleIntroShutter(introPresentation)
    }
}

@Composable
internal fun CharacterRosterScreen(
    characters: List<CharacterRosterEntry>,
    unlockedCharacterSlotCount: Int,
    onContinue: (slotId: Int) -> Unit,
    onCreate: () -> Unit,
    onDelete: suspend (slotId: Int) -> Boolean,
    onBack: () -> Unit,
) {
    var characterPendingDeletion by remember { mutableStateOf<PendingCharacterDeletion?>(null) }
    var deletionInProgress by remember { mutableStateOf(false) }
    var deletionError by remember { mutableStateOf<String?>(null) }
    val creationAvailable = canCreateCharacter(characters, unlockedCharacterSlotCount)
    val nextUnlockLevel = if (creationAvailable) {
        null
    } else {
        nextCharacterSlotUnlockLevel(unlockedCharacterSlotCount)
    }
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF24192E), AqBackground, Color(0xFF100C16)),
                ),
            )
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = localized("타이틀로 돌아가기"),
                        tint = AqText,
                    )
                }
                Text(
                    text = "모험가 선택",
                    color = AqText,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(ROSTER_CARD_GAP_DP.dp),
            ) {
                if (characters.isEmpty()) {
                    item {
                        EmptyRosterCard()
                    }
                } else {
                    items(characters, key = CharacterRosterEntry::slotId) { character ->
                        CharacterContinueCard(
                            slotId = character.slotId,
                            state = character.state,
                            combatPower = character.combatPower,
                            offlineAdventureProgress = character.offlineAdventureProgress,
                            onContinue = { onContinue(character.slotId) },
                            onDelete = {
                                deletionError = null
                                characterPendingDeletion = PendingCharacterDeletion(
                                    slotId = character.slotId,
                                    characterName = character.state.hero.name,
                                )
                            },
                        )
                    }
                }
                nextUnlockLevel?.let { level ->
                    item(key = "locked-character-slot-$level") {
                        LockedCharacterSlotHint(level)
                    }
                }
            }
            if (creationAvailable) {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onCreate,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AqGold),
                ) {
                    Text(
                        text = if (characters.isEmpty()) {
                            "첫 모험가 만들기"
                        } else {
                            "새 모험가 만들기"
                        },
                        fontWeight = FontWeight.Black,
                        color = Color(0xFF211808),
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }

    }

    characterPendingDeletion?.let { pendingDeletion ->
        CharacterDeleteConfirmationDialog(
            characterName = pendingDeletion.characterName,
            deletionInProgress = deletionInProgress,
            errorMessage = deletionError,
            onDismiss = {
                if (!deletionInProgress) {
                    characterPendingDeletion = null
                    deletionError = null
                }
            },
            onConfirm = {
                if (!deletionInProgress) {
                    deletionInProgress = true
                    deletionError = null
                    scope.launch {
                        try {
                            if (onDelete(pendingDeletion.slotId)) {
                                characterPendingDeletion = null
                            } else {
                                deletionError =
                                    "삭제하지 못했습니다. 저장 상태를 확인한 뒤 다시 시도해 주세요."
                            }
                        } finally {
                            deletionInProgress = false
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun LockedCharacterSlotHint(requiredLevel: Long) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AqSurface.copy(alpha = 0.82f)),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, AqGoldSoft, RoundedCornerShape(18.dp)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = AqGold,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "다음 모험가 슬롯 · Lv.${requiredLevel}에 영구 해금",
                color = AqMuted,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun EmptyRosterCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = AqSurface),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 34.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .border(1.dp, AqGoldSoft, CircleShape),
            ) {
                Image(
                    painter = painterResource(R.drawable.title_layer_world_v2),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(Modifier.fillMaxSize().background(Color(0x55100C16)))
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = null,
                    tint = AqGold,
                    modifier = Modifier.align(Alignment.Center).size(36.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = "아직 모험가가 없습니다",
                color = AqText,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "첫 모험가를 만들어 멈춰 있던 세계를 깨워 보세요.",
                color = AqMuted,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun CharacterContinueCard(
    slotId: Int,
    state: SimpleGameState,
    combatPower: Long,
    offlineAdventureProgress: Float,
    onContinue: () -> Unit,
    onDelete: () -> Unit,
) {
    val hero = state.hero
    val offlinePercent = offlineAdventurePercent(offlineAdventureProgress)
    val offlineColor = offlineAdventureColor(offlinePercent)
    val description = characterRosterContentDescription(
        name = hero.name,
        classLabel = hero.heroClass.labelKo,
        level = hero.level,
        combatPower = combatPower,
        adventureTitle = state.adventureTale.title,
        offlinePercent = offlinePercent,
    )
    Card(
        colors = CardDefaults.cardColors(containerColor = AqSurface),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(ROSTER_CARD_HEIGHT_DP.dp)
            .border(1.dp, AqGoldSoft, RoundedCornerShape(20.dp)),
    ) {
        Column {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {
                        contentDescription = localizedPreserving(description, hero.name)
                    }
                    .clickable(
                        role = Role.Button,
                        onClickLabel = localizedPreserving("${hero.name}으로 이어하기", hero.name),
                        onClick = onContinue,
                    )
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(AqGold.copy(alpha = 0.12f))
                            .border(1.dp, AqGoldSoft, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "LV",
                                color = AqMuted,
                                fontSize = 9.sp,
                                lineHeight = 10.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = hero.level.toString(),
                                color = AqGold,
                                fontSize = 18.sp,
                                lineHeight = 20.sp,
                                fontWeight = FontWeight.Black,
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        UnlocalizedText(
                            text = hero.name,
                            color = AqText,
                            fontSize = 20.sp,
                            lineHeight = 23.sp,
                            fontWeight = FontWeight.Black,
                        )
                        Spacer(Modifier.height(1.dp))
                        Text(
                            text = hero.heroClass.labelKo,
                            color = AqMuted,
                            fontSize = 12.sp,
                            lineHeight = 15.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Text(
                        text = "이어하기 ›",
                        color = AqGold,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(9.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(AqSurfaceHigh),
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.width(56.dp)) {
                        Text("전투력", color = AqMuted, fontSize = 10.sp, lineHeight = 12.sp)
                        Text(
                            text = NumberFormat.getIntegerInstance(Locale.KOREA).format(combatPower),
                            color = AqGold,
                            fontSize = 15.sp,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.Black,
                            maxLines = 1,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("현재 모험", color = AqMuted, fontSize = 10.sp, lineHeight = 12.sp)
                        Text(
                            text = state.adventureTale.title,
                            color = AqText,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Column(
                        modifier = Modifier.width(96.dp),
                        horizontalAlignment = Alignment.End,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "오프라인 잔여",
                                color = AqMuted,
                                fontSize = 9.sp,
                                lineHeight = 12.sp,
                                maxLines = 1,
                                softWrap = false,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "$offlinePercent%",
                                color = offlineColor,
                                fontSize = 11.sp,
                                lineHeight = 12.sp,
                                fontWeight = FontWeight.Black,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                        Spacer(Modifier.height(5.dp))
                        LinearProgressIndicator(
                            progress = { offlineAdventureProgress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(99.dp))
                                .clearAndSetSemantics { },
                            color = offlineColor,
                            trackColor = Color(0xFF4A3B4F),
                            drawStopIndicator = { },
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(AqSurfaceHigh))
            TextButton(
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = AqRed),
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("모험가 삭제", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

internal fun characterDeleteWarningText(characterName: String): String =
    "‘$characterName’의 레벨, 장비, 가방, 스킬, 퀘스트를 포함한 모든 모험 기록이 영구 삭제됩니다.\n\n삭제한 기록은 복구할 수 없습니다."

@Composable
private fun CharacterDeleteConfirmationDialog(
    characterName: String,
    deletionInProgress: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AqSurfaceHigh,
        icon = {
            Icon(Icons.Filled.Delete, contentDescription = null, tint = AqRed)
        },
        title = {
            Text("모험가를 삭제할까요?", color = AqText, fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                UnlocalizedText(
                    text = localizedPreserving(
                        characterDeleteWarningText(characterName),
                        characterName,
                    ),
                    color = AqMuted,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                )
                errorMessage?.let { message ->
                    Spacer(Modifier.height(12.dp))
                    Text(message, color = AqRed, fontSize = 13.sp, lineHeight = 19.sp)
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !deletionInProgress,
                modifier = Modifier.height(48.dp),
            ) {
                Text("취소", color = AqMuted)
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !deletionInProgress,
                modifier = Modifier.height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AqRed,
                    contentColor = Color.White,
                    disabledContainerColor = AqRed.copy(alpha = 0.5f),
                    disabledContentColor = Color.White.copy(alpha = 0.7f),
                ),
            ) {
                Text(
                    text = if (deletionInProgress) "삭제 중…" else "영구 삭제",
                    fontWeight = FontWeight.Bold,
                )
            }
        },
    )
}

private val TITLE_MOTES = titleMotes()
