package com.nullplaying.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nullplaying.localization.AppLanguage

internal data class ArenaGuideContent(
    val title: String,
    val lines: List<String>,
    val close: String,
)

internal fun arenaGuideContent(language: AppLanguage): ArenaGuideContent = when (language) {
    AppLanguage.KOREAN -> ArenaGuideContent(
        title = "결투장 안내",
        lines = listOf(
            "결투장은 다른 플레이어의 캐릭터와 자동으로 결투하는 곳입니다.",
            "캐릭터의 레벨과 능력치를 사용합니다. 로컬 도전자는 같은 레벨이며 최근 전적에 따라 강도가 조절됩니다.",
            "캐릭터 10레벨에 스킬 포인트 10개를 받고, 이후 레벨마다 1개씩 늘어납니다. 최대 100개입니다.",
            "결투장 스킬 설정에서 포인트를 배분해 스킬 성능을 강화합니다.",
            "전투에는 출전권 1장을 사용하며, 결과는 시즌 점수와 랭킹에 반영됩니다.",
        ),
        close = "닫기",
    )
    AppLanguage.ENGLISH -> ArenaGuideContent(
        title = "Arena Guide",
        lines = listOf(
            "The Arena is where your hero automatically duels other players’ characters.",
            "Your hero’s level and stats are used. Local challengers match your level, with strength adjusted to recent results.",
            "At hero level 10, you receive 10 skill points, then 1 per level, up to 100 in total.",
            "Spend points in Skill Setup to strengthen skills for Arena battles.",
            "Each duel uses one entry, and the result affects your season score and ranking.",
        ),
        close = "Close",
    )
    AppLanguage.JAPANESE -> ArenaGuideContent(
        title = "闘技場案内",
        lines = listOf(
            "闘技場では、ほかのプレイヤーのキャラクターと自動で対戦します。",
            "キャラクターのレベルと能力値を使います。ローカルの挑戦者は同じレベルで、最近の戦績に応じて強さが調整されます。",
            "キャラクターがレベル10になるとスキルポイントを10獲得し、以降は1レベルごとに1増えます。上限は100です。",
            "「スキル設定」でポイントを割り振ると、闘技場でのスキル性能を強化できます。",
            "対戦には出場券を1枚使い、結果はシーズンスコアとランキングに反映されます。",
        ),
        close = "閉じる",
    )
}

@Composable
internal fun ArenaGuideButton(modifier: Modifier = Modifier) {
    val content = arenaGuideContent(LocalAppLanguage.current)
    var open by rememberSaveable { mutableStateOf(false) }

    CompactInlineActionButton(
        icon = Icons.Outlined.Info,
        label = content.title,
        onClick = { open = true },
        modifier = modifier.testTag("arena-guide-button"),
    )

    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            modifier = Modifier.testTag("arena-guide-dialog"),
            containerColor = AqSurfaceHigh,
            title = {
                Text(
                    content.title,
                    color = AqText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    lineHeight = 24.sp,
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    content.lines.forEach { line ->
                        Text(
                            text = line,
                            color = AqMuted,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            maxLines = Int.MAX_VALUE - 1,
                            overflow = TextOverflow.Visible,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { open = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(
                        content.close,
                        color = AqGold,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
        )
    }
}
