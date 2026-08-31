package com.nullplaying.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.nullplaying.engine.SkillDefinition
import kotlin.math.roundToInt

/** Draws the reviewed web sprite sheet that is mapped to every production skill. */
@Composable
internal fun ModularSkillEffectLayer(
    definition: SkillDefinition,
    elapsedMillis: Int,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val spec = detailedSpriteSheetSpec(definition.catalogId) ?: return
    val sheet = ImageBitmap.imageResource(spec.assetId)
    Canvas(modifier) {
        drawDetailedSpriteSheet(sheet, spec, elapsedMillis, reducedMotion)
    }
}

private fun DrawScope.drawDetailedSpriteSheet(
    sheet: ImageBitmap,
    spec: DetailedSpriteSheetSpec,
    elapsedMillis: Int,
    reducedMotion: Boolean,
) {
    val frame = detailedSpriteFrame(elapsedMillis, spec, reducedMotion) ?: return
    val frameWidth = sheet.width / spec.columns
    val frameHeight = sheet.height / spec.rows
    drawImage(
        image = sheet,
        srcOffset = IntOffset(
            x = (frame.index % spec.columns) * frameWidth,
            y = (frame.index / spec.columns) * frameHeight,
        ),
        srcSize = IntSize(frameWidth, frameHeight),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(
            width = size.width.roundToInt().coerceAtLeast(1),
            height = size.height.roundToInt().coerceAtLeast(1),
        ),
        alpha = frame.alpha,
        blendMode = BlendMode.SrcOver,
    )
}
