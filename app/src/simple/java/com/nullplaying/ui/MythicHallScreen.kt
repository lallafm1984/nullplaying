package com.nullplaying.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nullplaying.localization.AppLanguage
import com.nullplaying.remote.MythicDiscoverySnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun mythicCopy(language: AppLanguage, ko: String, en: String, ja: String): String = when(language) {
    AppLanguage.KOREAN -> ko
    AppLanguage.ENGLISH -> en
    AppLanguage.JAPANESE -> ja
}
internal fun mythicHallTitle(language: AppLanguage) = mythicCopy(language, "신화의 전당", "Hall of Myths", "神話の殿堂")

@Composable
internal fun MythicHallEntryButton(onClick: () -> Unit) {
    val language = LocalAppLanguage.current
    val title = mythicHallTitle(language)
    CompactRecordEntryMenu(title = title,
        detail = null,
        detailHighlighted = true, accessibilityLabel = title, onClick = onClick)
}

@Composable
internal fun MythicHallScreen(
    snapshot: MythicDiscoverySnapshot?, error: Boolean = false,
    onBack: () -> Unit, onRetry: () -> Unit = {}, modifier: Modifier = Modifier,
) {
    val language = LocalAppLanguage.current
    fun copy(ko: String, en: String, ja: String) = mythicCopy(language, ko, en, ja)
    Column(modifier.fillMaxSize().background(AqBackground)) {
        RankingPageTopBar(title = mythicHallTitle(language),
            subtitle = copy("1시간마다 갱신 · 최신 100개", "Updated hourly · Latest 100", "1時間ごとに更新・最新100件"),
            backContentDescription = copy("아이템으로 돌아가기", "Back to Items", "アイテムに戻る"), onBack = onBack)
        UnlocalizedText(copy("수많은 여정 속에서 빛난, 신화의 기록.",
            "Mythic treasures. Extraordinary journeys.", "幾多の旅路に輝く、神話の記録。"),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            color = AqGold, fontSize = 12.sp, lineHeight = 18.sp)
        if (error) {
            UnlocalizedText(if (snapshot != null) copy("연결하지 못해 이전 기록을 표시합니다.", "Connection unavailable. Showing saved records.", "接続できないため、保存済みの記録を表示します。")
                else copy("기록을 불러오지 못했습니다.", "Could not load discoveries.", "記録を読み込めませんでした。"),
                modifier = Modifier.padding(horizontal = 20.dp), color = AqMuted, fontSize = 12.sp)
            TextButton(onClick = onRetry, modifier = Modifier.align(Alignment.End)) {
                UnlocalizedText(copy("다시 시도", "Retry", "再試行"), color = AqGold)
            }
        }
        if (snapshot == null || snapshot.entries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                UnlocalizedText(if (snapshot == null && !error) copy("기록을 불러오는 중…", "Loading records…", "記録を読み込み中…")
                    else copy("다음 신화의 발견을 기다리고 있습니다.", "Awaiting the next mythic discovery.", "新たな神話の発見を待っています。"),
                    color = AqMuted, fontSize = 14.sp)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                items(snapshot.entries.take(100), key = { it.eventId }) { entry ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(AqSurface)
                        .padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            UnlocalizedText(localizedEquipmentName(entry.itemName, language), color = rarityColor("신화"),
                                fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                            UnlocalizedText("${entry.displayName} · ${localized(entry.heroClass.labelKo, language)} · Lv.${entry.level}",
                                color = AqText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(2.dp))
                            UnlocalizedText(SimpleDateFormat("MM.dd HH:mm", Locale.getDefault()).format(Date(entry.discoveredAt)),
                                color = AqMuted, fontSize = 10.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            UnlocalizedText(localized(entry.slot.labelKo, language), color = AqMuted, fontSize = 10.sp)
                            UnlocalizedText("${entry.power}", color = AqGold, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
