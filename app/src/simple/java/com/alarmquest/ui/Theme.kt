package com.alarmquest.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val AqBackground = Color(0xFF15111D)
val AqSurface = Color(0xFF21182B)
val AqSurfaceHigh = Color(0xFF2A2035)
val AqGold = Color(0xFFE1B95F)
val AqGoldSoft = Color(0xFF8E7040)
val AqText = Color(0xFFF6F0E7)
val AqMuted = Color(0xFFB0A5B7)
val AqRed = Color(0xFFDF626B)

private val colors = darkColorScheme(
    primary = AqGold,
    onPrimary = Color(0xFF211808),
    background = AqBackground,
    onBackground = AqText,
    surface = AqSurface,
    onSurface = AqText,
    surfaceVariant = AqSurfaceHigh,
    onSurfaceVariant = AqMuted,
    error = AqRed,
)

@Composable
fun AlarmQuestTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
