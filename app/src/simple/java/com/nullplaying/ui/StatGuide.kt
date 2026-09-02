package com.nullplaying.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The same heading/button layout is used in both stat sections, with wrapping for large text. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StatSectionHeading(
    title: String,
    modifier: Modifier = Modifier,
    titleFontSize: TextUnit = 18.sp,
    trailingText: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                title,
                modifier = Modifier.align(Alignment.CenterVertically).semantics { heading() },
                color = AqText,
                fontWeight = FontWeight.Bold,
                fontSize = titleFontSize,
            )
            StatGuideButton(
                modifier = Modifier.align(Alignment.CenterVertically),
                enabled = enabled,
            )
        }
        if (trailingText != null) {
            Text(
                trailingText,
                modifier = Modifier.padding(start = 8.dp, end = 4.dp),
                color = AqGold,
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun StatGuideButton(modifier: Modifier = Modifier, enabled: Boolean = true) {
    var guideOpen by rememberSaveable { mutableStateOf(false) }
    CompactInlineActionButton(
        icon = Icons.Outlined.Info,
        label = StatGuideContent.title,
        onClick = {
            guideOpen = true
        },
        enabled = enabled,
        modifier = modifier,
    )

    if (guideOpen) {
        StatGuideDialog(onDismiss = { guideOpen = false })
    }
}

/** Compact gold-outline action used beside section headings. */
@Composable
internal fun CompactInlineActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    badgeText: String? = null,
) {
    val focusManager = LocalFocusManager.current
    val buttonShape = RoundedCornerShape(percent = 50)

    // Clickable Surface keeps Material's 48dp touch target outside the compact badge outline.
    Surface(
        onClick = {
            focusManager.clearFocus()
            onClick()
        },
        enabled = enabled,
        modifier = modifier.semantics { role = Role.Button },
        shape = buttonShape,
        border = BorderStroke(1.dp, if (enabled) AqGold.copy(alpha = 0.65f) else AqMuted.copy(alpha = 0.3f)),
        color = AqGold.copy(alpha = if (enabled) 0.1f else 0.04f),
        contentColor = if (enabled) AqGold else AqMuted,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 28.dp)
                .padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
            if (badgeText != null) {
                Spacer(Modifier.width(5.dp))
                Surface(
                    shape = RoundedCornerShape(percent = 50),
                    color = AqGold,
                    contentColor = AqSurface,
                ) {
                    Text(
                        badgeText,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatGuideDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AqSurfaceHigh,
        title = {
            Text(
                StatGuideContent.title,
                color = AqText,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                lineHeight = 24.sp,
            )
        },
        text = {
            // Use the available dialog height so all eight entries fit at normal text size.
            // Only constrained screens / enlarged text need scrolling; Close stays reachable.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GuideBodyText(StatGuideContent.introduction)
                StatGuideContent.entries.forEach { entry ->
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            entry.title,
                            modifier = Modifier.semantics { heading() },
                            color = AqGold,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        GuideBodyText(entry.benefit)
                    }
                }
                HorizontalDivider(color = AqMuted.copy(alpha = 0.2f))
                GuideBodyText(StatGuideContent.classBenefit)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("닫기", color = AqGold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        },
    )
}

@Composable
private fun GuideBodyText(text: String) {
    Text(
        text,
        color = AqMuted,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        // Explicitly bypass compact-label truncation for short Japanese/English sentences.
        maxLines = Int.MAX_VALUE - 1,
        overflow = TextOverflow.Visible,
    )
}
