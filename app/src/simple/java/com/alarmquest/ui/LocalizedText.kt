package com.alarmquest.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import com.alarmquest.localization.AppLanguage
import com.alarmquest.localization.GameLocalization
import com.alarmquest.localization.GameNameLocalization

internal val LocalAppLanguage = staticCompositionLocalOf { AppLanguage.KOREAN }

internal data class LocalizedTextLayoutPolicy(
    val maxLines: Int,
    val typeScale: Float,
    val ellipsizeClippedText: Boolean,
)

internal fun localizedTextLayoutPolicy(
    sourceLength: Int,
    translatedLength: Int,
    language: AppLanguage,
    requestedMaxLines: Int,
): LocalizedTextLayoutPolicy {
    val compactLabel = sourceLength <= COMPACT_SOURCE_LENGTH
    val expandedTranslation = translatedLength > sourceLength + maxOf(2, sourceLength / 5)
    val effectiveMaxLines = when {
        requestedMaxLines != Int.MAX_VALUE -> requestedMaxLines
        compactLabel -> 2
        else -> requestedMaxLines
    }
    val typeScale = when {
        !compactLabel || !expandedTranslation -> 1f
        language == AppLanguage.ENGLISH -> 0.9f
        language == AppLanguage.JAPANESE -> 0.96f
        else -> 1f
    }
    return LocalizedTextLayoutPolicy(
        maxLines = effectiveMaxLines,
        typeScale = typeScale,
        ellipsizeClippedText = effectiveMaxLines <= 2,
    )
}

internal fun localized(text: String, language: AppLanguage): String =
    GameLocalization.translate(text, language)

internal fun localized(text: String): String = GameLocalization.translateCurrent(text)

internal fun localizedPreserving(text: String, vararg protectedValues: String): String =
    GameLocalization.translateCurrentPreserving(text, protectedValues.asList())

internal fun localizedPreserving(text: String, protectedValues: Map<String, String>): String =
    GameLocalization.translateCurrentPreserving(text, protectedValues)

internal fun heroNameLocalizationReplacements(heroName: String): Map<String, String> = buildMap {
    listOf("은", "는", "이", "가", "과", "와").forEach { particle ->
        put(heroName + particle, heroName)
    }
    put(heroName, heroName)
}

internal fun localizedStoryText(text: String, heroName: String): String =
    localizedPreserving(text, heroNameLocalizationReplacements(heroName))

internal fun localizedEquipmentName(text: String, language: AppLanguage): String =
    GameNameLocalization.equipmentName(text, language)

internal fun localizedItemName(text: String, language: AppLanguage): String =
    GameNameLocalization.itemName(text, language)

internal fun localizedMonsterName(text: String, baseName: String, language: AppLanguage): String =
    GameNameLocalization.monsterName(text, baseName, language)

internal fun nextAdaptiveTextScale(currentScale: Float, hasVisualOverflow: Boolean): Float =
    if (hasVisualOverflow && currentScale > MIN_ADAPTIVE_SCALE) {
        (currentScale - ADAPTIVE_SCALE_STEP).coerceAtLeast(MIN_ADAPTIVE_SCALE)
    } else {
        currentScale
    }

/**
 * Single localization boundary for every Material text node in the app.
 *
 * Expanded English/Japanese copy is allowed a small type-size reduction only for constrained
 * labels. This keeps compact headers, menu items, and buttons from clipping while preserving
 * explicit maxLines and overflow contracts. Narrative/body text remains at its authored size.
 */
@Composable
internal fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
) {
    val language = LocalAppLanguage.current
    val localizedText = localized(text, language)
    val layoutPolicy = localizedTextLayoutPolicy(
        sourceLength = text.length,
        translatedLength = localizedText.length,
        language = language,
        requestedMaxLines = maxLines,
    )
    var adaptiveScale by remember(localizedText, maxLines, fontSize, lineHeight, style) {
        mutableFloatStateOf(1f)
    }
    val effectiveMaxLines = layoutPolicy.maxLines
    val typeScale = layoutPolicy.typeScale * adaptiveScale
    val safeFontSize = when {
        typeScale == 1f || !fontSize.isSpecified || fontSize.value <= 11f -> fontSize
        else -> fontSize * typeScale
    }
    val safeStyle = when {
        typeScale == 1f || fontSize.isSpecified || !style.fontSize.isSpecified -> style
        style.fontSize.value <= 11f -> style
        else -> style.copy(
            fontSize = style.fontSize * typeScale,
            lineHeight = if (style.lineHeight.isSpecified) style.lineHeight * typeScale else style.lineHeight,
        )
    }
    val safeLineHeight = when {
        typeScale == 1f || !lineHeight.isSpecified || lineHeight.value <= 11f -> lineHeight
        else -> lineHeight * typeScale
    }
    val safeOverflow = when {
        overflow != TextOverflow.Clip -> overflow
        layoutPolicy.ellipsizeClippedText -> TextOverflow.Ellipsis
        else -> overflow
    }
    MaterialText(
        text = localizedText,
        modifier = modifier,
        color = color,
        fontSize = safeFontSize,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        letterSpacing = letterSpacing,
        textDecoration = textDecoration,
        textAlign = textAlign,
        lineHeight = safeLineHeight,
        overflow = safeOverflow,
        softWrap = softWrap,
        maxLines = effectiveMaxLines,
        minLines = minLines,
        onTextLayout = { result ->
            adaptiveScale = nextAdaptiveTextScale(
                currentScale = adaptiveScale,
                hasVisualOverflow = result.hasVisualOverflow,
            )
            onTextLayout?.invoke(result)
        },
        style = safeStyle,
    )
}

@Composable
internal fun UnlocalizedText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    MaterialText(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        lineHeight = lineHeight,
        fontWeight = fontWeight,
        maxLines = maxLines,
        overflow = overflow,
    )
}

private const val COMPACT_SOURCE_LENGTH = 18
private const val MIN_ADAPTIVE_SCALE = 0.78f
private const val ADAPTIVE_SCALE_STEP = 0.04f
