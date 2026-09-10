package com.nullplaying.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nullplaying.data.RecentAdventureEventRecord
import com.nullplaying.localization.AppLanguage
import com.nullplaying.model.AdventureEventRewardKind
import com.nullplaying.model.EquipmentSlot
import com.nullplaying.model.RecentAdventureEvent
import com.nullplaying.model.RecentAdventureEventMetadata
import com.nullplaying.model.RecentAdventureEventType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
internal fun RecentAdventureEventsButton(
    unreadCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val language = LocalAppLanguage.current
    CompactInlineActionButton(
        icon = Icons.Filled.History,
        label = recentEventsTitle(language),
        onClick = onClick,
        modifier = modifier,
        badgeText = unreadCount.takeIf { it > 0 }?.let { if (it > 9) "9+" else it.toString() },
    )
}

@Composable
internal fun RecentAdventureEventsDialog(
    records: List<RecentAdventureEventRecord>,
    onDismiss: () -> Unit,
) {
    val language = LocalAppLanguage.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.84f),
            shape = RoundedCornerShape(22.dp),
            color = AqSurfaceHigh,
            tonalElevation = 8.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.History,
                        contentDescription = null,
                        tint = AqGold,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(9.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        MaterialText(
                            text = recentEventsTitle(language),
                            modifier = Modifier.semantics { heading() },
                            color = AqText,
                            fontSize = 19.sp,
                            lineHeight = 24.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        MaterialText(
                            text = recentEventsSubtitle(language),
                            color = AqMuted,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.semantics {
                            contentDescription = closeLabel(language)
                        },
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = null, tint = AqMuted)
                    }
                }
                HorizontalDivider(color = AqMuted.copy(alpha = 0.18f))

                if (records.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(28.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Filled.History,
                                contentDescription = null,
                                tint = AqMuted.copy(alpha = 0.5f),
                                modifier = Modifier.size(34.dp),
                            )
                            Spacer(Modifier.height(10.dp))
                            MaterialText(
                                text = recentEventsEmptyText(language),
                                color = AqMuted,
                                fontSize = 13.sp,
                                lineHeight = 19.sp,
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 8.dp,
                            bottom = 18.dp,
                        ),
                    ) {
                        itemsIndexed(records, key = { _, record -> record.id }) { index, record ->
                            val dayKey = recentEventDayKey(record.event.occurredAt)
                            val previousDayKey = records.getOrNull(index - 1)
                                ?.event
                                ?.occurredAt
                                ?.let(::recentEventDayKey)
                            if (index == 0 || dayKey != previousDayKey) {
                                MaterialText(
                                    text = recentEventDayLabel(record.event.occurredAt, language),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = if (index == 0) 6.dp else 15.dp, bottom = 6.dp)
                                        .semantics { heading() },
                                    color = AqGold,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            RecentAdventureEventRow(record.event, language)
                            if (index != records.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 44.dp),
                                    color = AqMuted.copy(alpha = 0.12f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentAdventureEventRow(
    event: RecentAdventureEvent,
    language: AppLanguage,
) {
    val presentation = recentAdventureEventPresentation(event, language)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(presentation.color.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = presentation.icon,
                contentDescription = null,
                tint = presentation.color,
                modifier = Modifier.size(17.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            MaterialText(
                text = presentation.title,
                color = AqText,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (presentation.detail.isNotBlank()) {
                MaterialText(
                    text = presentation.detail,
                    color = AqMuted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        MaterialText(
            text = recentEventTimeLabel(event.occurredAt, language),
            color = AqMuted.copy(alpha = 0.82f),
            fontSize = 10.sp,
            lineHeight = 14.sp,
        )
    }
}

internal data class RecentAdventureEventPresentation(
    val icon: ImageVector,
    val color: Color,
    val title: String,
    val detail: String,
)

/** Unknown pre-catalog event rows must not expose their stored Korean title after a language switch. */
internal fun legacyAdventureEventTitle(
    subjectName: String,
    language: AppLanguage,
): String {
    if (language == AppLanguage.KOREAN && subjectName.isNotBlank()) return subjectName
    val translated = localized(subjectName, language)
    if (translated.isNotBlank() && !Regex("[가-힣]").containsMatchIn(translated)) return translated
    return journeyText(language, "길 위의 사건", "Adventure event", "冒険の出来事")
}

internal fun recentAdventureEventPresentation(
    event: RecentAdventureEvent,
    language: AppLanguage,
): RecentAdventureEventPresentation {
    val subject = localized(event.subjectName, language)
    val context = localized(event.contextName, language)
    val previousEquipment = localizedEquipmentName(event.previousName, language)
    val currentEquipment = localizedEquipmentName(event.currentName, language)
    val previous = event.previousValue ?: 0L
    val current = event.currentValue ?: 0L
    return when (event.type) {
        RecentAdventureEventType.ADVENTURE_TRAIT_CHANGED, RecentAdventureEventType.ADVENTURE_TRAIT_ACTIVATED -> {
            val definition = com.nullplaying.engine.AdventureTraitCatalog.find(event.subjectId)
            val name = definition?.name?.inLanguage(language) ?: journeyText(language, "모험 특성", "Adventure trait", "冒険の特性")
            val changed = event.type == RecentAdventureEventType.ADVENTURE_TRAIT_CHANGED
            val kind = runCatching { com.nullplaying.model.AdventureTraitChangeKind.valueOf(event.contextName) }.getOrNull()
            val displayEvent = if (!changed && event.contextName in setOf("EXTRA_ITEM", "OMITTED_ITEM"))
                event.copy(currentName = localizedItemName(event.currentName, language)) else event
            RecentAdventureEventPresentation(
                Icons.Filled.AutoAwesome, AqGold,
                if (changed && kind != null) "${adventureTraitChangeLabel(kind, language)} · $name" else name,
                com.nullplaying.engine.AdventureTraitCatalog.recentEventText(displayEvent).inLanguage(language),
            )
        }
        RecentAdventureEventType.RELATIONSHIP_ENCOUNTER -> {
            val tier = runCatching { com.nullplaying.model.AdventureRelationshipTier.valueOf(event.currentName) }
                .getOrElse { com.nullplaying.model.AdventureRelationshipTier.fromScore(current.toInt()) }
            val scene = runCatching { com.nullplaying.engine.AdventureRelationshipEngine.definition(event.contextName.substringBefore(":")) }.getOrNull()
            val change = when {
                current > previous -> journeyText(language, "조금 가까워졌다", "A little closer", "少し親しくなった")
                current < previous -> journeyText(language, "거리감이 남았다", "Some distance remains", "距離を感じた")
                else -> journeyText(language, "기억에 남은 만남", "An encounter remembered", "心に残る出会い")
            }
            val reward = when (RecentAdventureEventMetadata.adventureRewardKind(event.contextName)) {
                AdventureEventRewardKind.EXPERIENCE -> RecentAdventureEventMetadata
                    .relationshipExperience(event.contextName)
                    .takeIf { it > 0L }
                    ?.let { journeyText(language, "경험치 +$it", "EXP +$it", "経験値 +$it") }
                AdventureEventRewardKind.GOLD -> RecentAdventureEventMetadata
                    .relationshipGold(event.contextName)
                    .takeIf { it > 0L }
                    ?.let { "+$it G" }
                AdventureEventRewardKind.ITEM -> {
                    val itemName = RecentAdventureEventMetadata.relationshipItemName(event.contextName)
                    if (itemName.isBlank()) {
                        journeyText(language, "가방이 가득 차 장비를 담지 못했다", "The bag was full, so the equipment was left behind", "バッグがいっぱいで装備を持ち帰れなかった")
                    } else {
                        val localizedName = localizedEquipmentName(itemName, language)
                        val stored = if (RecentAdventureEventMetadata.relationshipItemEquipped(event.contextName)) {
                            journeyText(language, "새 장비로 장착", "Equipped", "新しい装備として装着")
                        } else {
                            journeyText(language, "가방에 보관", "Stored in the bag", "バッグに収納")
                        }
                        journeyText(language, "$localizedName 획득 · $stored", "$localizedName acquired · $stored", "$localizedName 獲得・$stored")
                    }
                }
                AdventureEventRewardKind.ROUTE, AdventureEventRewardKind.UNSPECIFIED, null -> null
            }
            RecentAdventureEventPresentation(
                Icons.AutoMirrored.Filled.MenuBook, relationshipColor(tier),
                "${event.subjectName} · ${relationshipTierLabel(tier, language)}",
                listOfNotNull(scene?.title?.inLanguage(language), change, reward).joinToString(" · "),
            )
        }
        RecentAdventureEventType.ADVENTURE_EVENT -> {
            val definition = runCatching { com.nullplaying.engine.AdventureEventEngine.definition(event.subjectId) }.getOrNull()
            val contextParts = event.contextName.split(":")
            val outcome = runCatching { com.nullplaying.model.AdventureEventOutcome.valueOf(contextParts.getOrNull(1).orEmpty()) }.getOrDefault(com.nullplaying.model.AdventureEventOutcome.PARTIAL)
            val actualItemCount = contextParts.getOrNull(2)?.toIntOrNull()?.coerceAtLeast(0)
                ?: if (event.currentName.isNotBlank()) 1 else 0
            val itemName = when {
                actualItemCount <= 0 || event.currentName.isBlank() -> ""
                definition != null && event.currentName == definition.itemName.ko -> definition.itemName.inLanguage(language)
                else -> localizedItemName(event.currentName, language)
            }
            val rewardKind = RecentAdventureEventMetadata.adventureRewardKind(event.contextName)
            val routeShortened = RecentAdventureEventMetadata.isRouteShortening(event.contextName)
            val itemKind = RecentAdventureEventMetadata.adventureItemReward(event.contextName)
            val isTrophy = itemKind == com.nullplaying.model.AdventureEventItemReward.TROPHY ||
                (itemKind == null && definition != null && event.currentName == definition.itemName.ko)
            val detail = buildList {
                if (previous > 0) add("EXP +$previous")
                if (current > 0) add("+$current G")
                if (itemName.isNotBlank()) add(
                    if (isTrophy) journeyText(language, "전리품 획득", "Loot acquired", "戦利品を獲得")
                    else journeyText(language, "$itemName 획득", "Obtained $itemName", "${itemName}を獲得")
                )
                if (routeShortened) {
                    add(journeyText(language, "시간 단축", "Travel time reduced", "移動時間短縮"))
                }
                if (isEmpty() && rewardKind != null) {
                    add(
                        if (outcome == com.nullplaying.model.AdventureEventOutcome.FAILURE) {
                            journeyText(language, "이동 지연", "Travel delayed", "移動遅延")
                        } else {
                            journeyText(language, "보상 없음", "No reward", "報酬なし")
                        },
                    )
                }
            }.distinct().joinToString(" · ")
            RecentAdventureEventPresentation(
                Icons.AutoMirrored.Filled.MenuBook, Color(0xFFD9B76F),
                "${definition?.title?.inLanguage(language) ?: legacyAdventureEventTitle(event.subjectName, language)} · ${eventOutcomeLabel(outcome, language)}",
                detail,
            )
        }
        RecentAdventureEventType.LEVEL_UP -> {
            val statGrowth = RecentAdventureEventMetadata.decodeStatGrowth(event.contextName)
                .joinToString(" · ") { (key, amount) -> "${statGrowthLabel(key)} +$amount" }
            eventPresentation(
                Icons.AutoMirrored.Filled.TrendingUp,
                Color(0xFF76D6A1),
                language,
                ko = "레벨 상승",
                en = "Level up",
                ja = "レベルアップ",
                detailKo = statGrowth,
                detailEn = statGrowth,
                detailJa = statGrowth,
            )
        }
        RecentAdventureEventType.SKILL_MASTERY -> eventPresentation(
            Icons.Filled.AutoAwesome,
            Color(0xFF82C7FF),
            language,
            ko = "$subject 숙련도 상승",
            en = "$subject mastery increased",
            ja = "${subject}の熟練度上昇",
            detailKo = "LV.$previous → LV.$current",
            detailEn = "LV.$previous → LV.$current",
            detailJa = "LV.$previous → LV.$current",
        )
        RecentAdventureEventType.SKILL_LEARNED -> eventPresentation(
            Icons.Filled.AutoAwesome,
            Color(0xFFB8A4FF),
            language,
            ko = "새 스킬 습득 · $subject",
            en = "New skill learned · $subject",
            ja = "新スキル習得 · $subject",
            detailKo = "Lv.${current}에 습득",
            detailEn = "Learned at Lv.$current",
            detailJa = "Lv.${current}で習得",
        )
        RecentAdventureEventType.EQUIPMENT_CHANGED -> eventPresentation(
            Icons.Filled.Shield,
            Color(0xFFF0BE61),
            language,
            ko = "${equipmentSlotLabel(event.equipmentSlot, language)} 교체 · $currentEquipment",
            en = "${equipmentSlotLabel(event.equipmentSlot, language)} changed · $currentEquipment",
            ja = "${equipmentSlotLabel(event.equipmentSlot, language)}変更 · $currentEquipment",
            detailKo = "$previousEquipment ($previous) → $currentEquipment ($current)",
            detailEn = "$previousEquipment ($previous) → $currentEquipment ($current)",
            detailJa = "$previousEquipment ($previous) → $currentEquipment ($current)",
        )
        RecentAdventureEventType.QUEST_COMPLETED -> {
            val completionDetail = buildList {
                if (context.isNotBlank()) add(context)
                if (previous > 0L) add("EXP +$previous")
                if (current > 0L) add("+$current G")
            }.distinct().joinToString(" · ")
            eventPresentation(
                Icons.Filled.CheckCircle,
                Color(0xFF75D7D0),
                language,
                ko = "퀘스트 완료 · $subject",
                en = "Quest completed · $subject",
                ja = "クエスト完了 · $subject",
                detailKo = completionDetail,
                detailEn = completionDetail,
                detailJa = completionDetail,
            )
        }
        RecentAdventureEventType.TALE_COMPLETED -> eventPresentation(
            Icons.AutoMirrored.Filled.MenuBook,
            Color(0xFFE894C5),
            language,
            ko = "모험담 완료 · $subject",
            en = "Adventure tale completed · $subject",
            ja = "冒険譚完了 · $subject",
            detailKo = context,
            detailEn = context,
            detailJa = context,
        )
        RecentAdventureEventType.TITLE_UNLOCKED -> eventPresentation(
            Icons.Filled.WorkspacePremium,
            AqGold,
            language,
            ko = "호칭 해금 · $subject",
            en = "Title unlocked · $subject",
            ja = "称号解放 · $subject",
            detailKo = context,
            detailEn = context,
            detailJa = context,
        )
    }
}

private fun eventPresentation(
    icon: ImageVector,
    color: Color,
    language: AppLanguage,
    ko: String,
    en: String,
    ja: String,
    detailKo: String,
    detailEn: String,
    detailJa: String,
): RecentAdventureEventPresentation = RecentAdventureEventPresentation(
    icon = icon,
    color = color,
    title = selectLanguage(language, ko, en, ja),
    detail = selectLanguage(language, detailKo, detailEn, detailJa),
)

internal fun recentEventsTitle(language: AppLanguage): String =
    selectLanguage(language, "최근 사건", "Recent Events", "最近の出来事")

private fun recentEventsSubtitle(language: AppLanguage): String = selectLanguage(
    language,
    "중요한 성장과 모험 기록",
    "Notable growth and adventure records",
    "成長と冒険の主な記録",
)

private fun recentEventsEmptyText(language: AppLanguage): String = selectLanguage(
    language,
    "아직 기록할 만한 사건이 없습니다.",
    "No notable events yet.",
    "まだ記録する出来事はありません。",
)

private fun closeLabel(language: AppLanguage): String =
    selectLanguage(language, "닫기", "Close", "閉じる")

private fun equipmentSlotLabel(slot: EquipmentSlot?, language: AppLanguage): String {
    val labels = when (slot) {
        EquipmentSlot.WEAPON -> Triple("무기", "Weapon", "武器")
        EquipmentSlot.HEAD -> Triple("머리", "Head", "頭")
        EquipmentSlot.BODY -> Triple("몸", "Body", "胴")
        EquipmentSlot.HANDS -> Triple("손", "Hands", "手")
        EquipmentSlot.FEET -> Triple("발", "Feet", "足")
        EquipmentSlot.ACCESSORY -> Triple("장신구", "Accessory", "装飾品")
        null -> Triple("장비", "Equipment", "装備")
    }
    return selectLanguage(language, labels.first, labels.second, labels.third)
}

private fun statGrowthLabel(key: String): String = when (key) {
    "HP_MAX" -> "HP MAX"
    "MP_MAX" -> "MP MAX"
    else -> key
}

internal fun recentEventTimeLabel(
    timestamp: Long,
    language: AppLanguage,
): String = SimpleDateFormat("HH:mm", localeFor(language)).format(Date(timestamp))

internal fun recentEventDayKey(timestamp: Long): String =
    SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date(timestamp))

internal fun recentEventDayLabel(
    timestamp: Long,
    language: AppLanguage,
    now: Long = System.currentTimeMillis(),
): String {
    val target = Calendar.getInstance().apply { timeInMillis = timestamp }
    val today = Calendar.getInstance().apply { timeInMillis = now }
    if (sameDay(target, today)) return selectLanguage(language, "오늘", "Today", "今日")
    today.add(Calendar.DAY_OF_YEAR, -1)
    if (sameDay(target, today)) return selectLanguage(language, "어제", "Yesterday", "昨日")
    return SimpleDateFormat(
        when (language) {
            AppLanguage.ENGLISH -> "MMM d, yyyy"
            AppLanguage.JAPANESE -> "yyyy年M月d日"
            AppLanguage.KOREAN -> "yyyy년 M월 d일"
        },
        localeFor(language),
    ).format(Date(timestamp))
}

private fun sameDay(left: Calendar, right: Calendar): Boolean =
    left.get(Calendar.ERA) == right.get(Calendar.ERA) &&
        left.get(Calendar.YEAR) == right.get(Calendar.YEAR) &&
        left.get(Calendar.DAY_OF_YEAR) == right.get(Calendar.DAY_OF_YEAR)

private fun localeFor(language: AppLanguage): Locale = Locale.forLanguageTag(language.languageTag)

private fun <T> selectLanguage(language: AppLanguage, ko: T, en: T, ja: T): T = when (language) {
    AppLanguage.KOREAN -> ko
    AppLanguage.ENGLISH -> en
    AppLanguage.JAPANESE -> ja
}
