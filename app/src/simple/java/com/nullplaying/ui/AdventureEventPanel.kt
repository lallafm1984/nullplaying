package com.nullplaying.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nullplaying.engine.AdventureEventEngine
import com.nullplaying.engine.AdventureText
import com.nullplaying.localization.AppLanguage
import com.nullplaying.model.*
import kotlinx.coroutines.delay

internal fun AdventureText.inLanguage(language: AppLanguage): String = when (language) {
    AppLanguage.KOREAN -> ko
    AppLanguage.ENGLISH -> en
    AppLanguage.JAPANESE -> ja
}

internal fun journeyText(language: AppLanguage, ko: String, en: String, ja: String) =
    AdventureText(ko, en, ja).inLanguage(language)

internal data class AdventureCenterTextMetrics(
    val fontSizeSp: Int,
    val lineHeightSp: Int,
    val maxLines: Int = 3,
)

/** Keeps the established card geometry while giving longer English and Japanese copy a third line. */
internal fun adventureCenterTextMetrics(text: String): AdventureCenterTextMetrics {
    val visualLength = text.sumOf { character ->
        when {
            character.isWhitespace() -> 0.35
            character.code <= 0x7f -> 0.55
            else -> 1.0
        }
    }
    return when {
        visualLength <= 32.0 -> AdventureCenterTextMetrics(fontSizeSp = 21, lineHeightSp = 26)
        visualLength <= 48.0 -> AdventureCenterTextMetrics(fontSizeSp = 19, lineHeightSp = 24)
        else -> AdventureCenterTextMetrics(fontSizeSp = 17, lineHeightSp = 22)
    }
}

internal fun eventOutcomeLabel(outcome: AdventureEventOutcome, language: AppLanguage): String = when (outcome) {
    AdventureEventOutcome.SUCCESS -> journeyText(language, "해결", "Resolved", "解決")
    AdventureEventOutcome.PARTIAL -> journeyText(language, "일부 해결", "Partial success", "一部解決")
    AdventureEventOutcome.FAILURE -> journeyText(language, "다른 길로", "Another way", "別の道へ")
}

internal enum class AdventureEventReceiptReward {
    EXPERIENCE,
    GOLD,
    ITEM,
    ROUTE,
    BATTLE,
    NONE,
}

internal enum class AdventureEventItemReceiptState {
    ACQUIRED,
    OMITTED,
    BAG_FULL,
    NONE,
}

internal data class AdventureEventReceiptPresentation(
    val eventTitle: String,
    val subjectName: String = "",
    val outcomeLabel: String,
    val narrative: String,
    val reward: AdventureEventReceiptReward,
    val rewardTypeLabel: String,
    val rewardSummary: String,
    val penaltySummary: String,
    val itemState: AdventureEventItemReceiptState,
)

internal fun AdventureEventReceiptPresentation.wasGranted(): Boolean =
    reward != AdventureEventReceiptReward.NONE && reward != AdventureEventReceiptReward.BATTLE &&
        (reward != AdventureEventReceiptReward.ITEM || itemState == AdventureEventItemReceiptState.ACQUIRED)

internal fun adventureEventReceiptPresentation(
    result: AdventureEventResult,
    language: AppLanguage,
): AdventureEventReceiptPresentation {
    val definition = AdventureEventEngine.definition(result.run.eventId)
    val battlePending = result.run.battleGrade != null && !result.battleResolved
    val battleCompleted = result.run.battleGrade != null && result.battleResolved
    val selectedRewardKind = if (result.run.rewardKind != AdventureEventRewardKind.UNSPECIFIED) {
        result.run.rewardKind
    } else when {
        result.run.itemReward != AdventureEventItemReward.NONE -> AdventureEventRewardKind.ITEM
        result.run.goldReward > 0L -> AdventureEventRewardKind.GOLD
        result.run.routeDelayMillis < 0L -> AdventureEventRewardKind.ROUTE
        result.run.experienceReward > 0L -> AdventureEventRewardKind.EXPERIENCE
        else -> AdventureEventRewardKind.UNSPECIFIED
    }
    val reward = if (battlePending) AdventureEventReceiptReward.BATTLE else when (selectedRewardKind) {
        AdventureEventRewardKind.EXPERIENCE ->
            if (result.run.experienceReward > 0L) AdventureEventReceiptReward.EXPERIENCE else AdventureEventReceiptReward.NONE
        AdventureEventRewardKind.GOLD ->
            if (result.run.goldReward > 0L) AdventureEventReceiptReward.GOLD else AdventureEventReceiptReward.NONE
        AdventureEventRewardKind.ITEM ->
            if (result.run.itemReward != AdventureEventItemReward.NONE) AdventureEventReceiptReward.ITEM else AdventureEventReceiptReward.NONE
        AdventureEventRewardKind.ROUTE ->
            if (result.run.routeDelayMillis < 0L) AdventureEventReceiptReward.ROUTE else AdventureEventReceiptReward.NONE
        AdventureEventRewardKind.UNSPECIFIED -> AdventureEventReceiptReward.NONE
    }
    val itemState = when {
        reward != AdventureEventReceiptReward.ITEM -> AdventureEventItemReceiptState.NONE
        result.actualItemCount > 0 -> AdventureEventItemReceiptState.ACQUIRED
        result.itemOmittedByTrait -> AdventureEventItemReceiptState.OMITTED
        else -> AdventureEventItemReceiptState.BAG_FULL
    }
    val rewardTypeLabel = when (reward) {
        AdventureEventReceiptReward.EXPERIENCE -> journeyText(language, "경험치", "Experience", "経験値")
        AdventureEventReceiptReward.GOLD -> journeyText(language, "골드", "Gold", "ゴールド")
        AdventureEventReceiptReward.ITEM -> journeyText(language, "아이템", "Item", "アイテム")
        AdventureEventReceiptReward.ROUTE -> journeyText(language, "이동 단축", "Travel shortcut", "移動短縮")
        AdventureEventReceiptReward.BATTLE -> if (result.run.battleGrade == MonsterGrade.BOSS) {
            journeyText(language, "사건 보스", "Event boss", "出来事のボス")
        } else {
            journeyText(language, "정예 조우", "Elite encounter", "精鋭との遭遇")
        }
        AdventureEventReceiptReward.NONE -> journeyText(language, "보상 없음", "No reward", "報酬なし")
    }
    val rewardSummary = when (reward) {
        AdventureEventReceiptReward.EXPERIENCE -> "EXP +${result.experienceAwarded}"
        AdventureEventReceiptReward.GOLD -> "+${result.goldAwarded} G"
        AdventureEventReceiptReward.ITEM -> when (itemState) {
            AdventureEventItemReceiptState.ACQUIRED -> journeyText(
                language,
                if (result.itemEquipped) "새 장비로 바로 장착했습니다" else "가방에 보관했습니다",
                if (result.itemEquipped) "Equipped immediately" else "Stored in the bag",
                if (result.itemEquipped) "すぐに新しい装備を装着した" else "バッグに収納しました",
            )
            AdventureEventItemReceiptState.OMITTED -> journeyText(
                language,
                "아이템을 남기고 이동했습니다",
                "The item was left behind",
                "アイテムを置いて出発した",
            )
            AdventureEventItemReceiptState.BAG_FULL -> journeyText(
                language,
                "가방이 가득 차 아이템을 담지 못했습니다",
                "The bag was full, so the item was left behind",
                "バッグがいっぱいで持ち帰れませんでした",
            )
            AdventureEventItemReceiptState.NONE -> error("Item reward requires an item receipt state")
        }
        AdventureEventReceiptReward.ROUTE -> journeyText(
            language,
            "몬스터 조우 ${result.run.routeRewardUses.coerceIn(2, 10)}회 · 각 ${eventDurationLabel(-result.run.routeDelayMillis)}초 단축",
            "${result.run.routeRewardUses.coerceIn(2, 10)} encounters · ${eventDurationLabel(-result.run.routeDelayMillis)}s faster each",
            "遭遇${result.run.routeRewardUses.coerceIn(2, 10)}回 · 各${eventDurationLabel(-result.run.routeDelayMillis)}秒短縮",
        )
        AdventureEventReceiptReward.BATTLE -> if (result.run.battleGrade == MonsterGrade.BOSS) {
            journeyText(language, "보스 몬스터가 모습을 드러냈습니다", "A boss monster has appeared", "ボスモンスターが姿を現した")
        } else {
            journeyText(language, "정예 몬스터가 길을 막았습니다", "An elite monster blocks the way", "精鋭モンスターが道を塞いだ")
        }
        AdventureEventReceiptReward.NONE -> journeyText(language, "보상 없음", "No reward", "報酬なし")
    }
    val penaltySummary = if (result.run.routeDelayMillis > 0L) journeyText(
        language,
        "다음 몬스터 조우 ${eventDurationLabel(result.run.routeDelayMillis)}초 지연",
        "Next monster encounter delayed ${eventDurationLabel(result.run.routeDelayMillis)}s",
        "次のモンスター遭遇が${eventDurationLabel(result.run.routeDelayMillis)}秒遅延",
    ) else ""
    val narrative = if (battleCompleted) {
        val monsterName = requireNotNull(definition.battleRule).monsterName.inLanguage(language)
        when (language) {
            AppLanguage.KOREAN -> "$monsterName 처치 완료"
            AppLanguage.ENGLISH -> "Defeated $monsterName"
            AppLanguage.JAPANESE -> "${monsterName}を討伐"
        }
    } else {
        when (result.run.outcome) {
            AdventureEventOutcome.SUCCESS -> definition.success
            AdventureEventOutcome.PARTIAL -> definition.partial
            AdventureEventOutcome.FAILURE -> definition.failure
        }.inLanguage(language)
    }
    return AdventureEventReceiptPresentation(
        eventTitle = definition.title.inLanguage(language),
        outcomeLabel = if (battleCompleted) {
            journeyText(language, "전투 승리", "Victory", "戦闘勝利")
        } else {
            eventOutcomeLabel(result.run.outcome, language)
        },
        narrative = narrative,
        reward = reward,
        rewardTypeLabel = rewardTypeLabel,
        rewardSummary = rewardSummary,
        penaltySummary = penaltySummary,
        itemState = itemState,
    )
}

internal fun eventRewardSummary(result: AdventureEventResult, language: AppLanguage): String =
    adventureEventReceiptPresentation(result, language).rewardSummary

internal fun adventureEventContextLabel(
    context: AdventureEventContext,
    language: AppLanguage,
): String = when (context) {
    AdventureEventContext.OUTBOUND_ROUTE -> journeyText(language, "사냥터로 가는 길", "Road to the hunting ground", "狩り場への道")
    AdventureEventContext.FIELD_EXPLORATION -> journeyText(language, "탐색 중의 사건", "Exploration event", "探索中の出来事")
    AdventureEventContext.PRE_COMBAT -> journeyText(language, "전투 직전의 사건", "Before battle", "戦闘直前の出来事")
    AdventureEventContext.POST_COMBAT -> journeyText(language, "전투 뒤의 사건", "After battle", "戦闘後の出来事")
    AdventureEventContext.RETURN_ROUTE -> journeyText(language, "마을로 돌아가는 길", "Road back to town", "町へ戻る道")
    AdventureEventContext.TOWN_RETURN -> journeyText(language, "마을에서 생긴 사건", "Town event", "町での出来事")
}

internal fun eventItemDisplayName(
    result: AdventureEventResult,
    sourceItemName: String,
    language: AppLanguage,
): String {
    val definition = AdventureEventEngine.definition(result.run.eventId)
    return if (result.run.itemReward == AdventureEventItemReward.TROPHY && sourceItemName == definition.itemName.ko) {
        definition.itemName.inLanguage(language)
    } else {
        sourceItemName
    }
}

private fun eventDurationLabel(durationMillis: Long): String {
    val tenths = (durationMillis.coerceAtLeast(0L) + 50L) / 100L
    return if (tenths % 10L == 0L) (tenths / 10L).toString()
    else "${tenths / 10L}.${tenths % 10L}"
}

@Composable
internal fun rememberAdventureRetryStarted(state: SimpleGameState, now: Long): Boolean {
    val retryAt = state.adventureTraits.source?.retryRun?.startedAt
        .takeIf { state.adventurePhase == AdventurePhase.EVENT }
    var started by remember(retryAt) { mutableStateOf(retryAt != null && now >= retryAt) }
    LaunchedEffect(retryAt) {
        if (retryAt != null) {
            delay((retryAt - now).coerceAtLeast(0L))
            started = true
        }
    }
    return started
}

internal fun adventureEventActionRun(state: SimpleGameState, retryStarted: Boolean): AdventureEventRun? {
    val source = state.adventureTraits.source
    return if (source?.retryRun != null) {
        if (retryStarted) source.retryRun else source.baseEvent
    } else state.adventureJourney.pending
}

internal enum class AdventureEventDisplayStage {
    EVENT,
    ACTION,
}

internal data class AdventureEventCenterText(
    val eventName: String,
    val content: String,
)

internal fun adventureEventCenterText(
    stage: AdventureEventDisplayStage,
    eventName: String,
    eventScene: String,
    actionTitle: String,
): AdventureEventCenterText = AdventureEventCenterText(
    eventName = eventName,
    content = when (stage) {
        AdventureEventDisplayStage.EVENT -> eventScene
        AdventureEventDisplayStage.ACTION -> actionTitle
    },
)

internal data class AdventureEventDisplayWindow(
    val stage: AdventureEventDisplayStage,
    val startedAt: Long,
    val endsAt: Long,
)

private fun eventTimePlus(startedAt: Long, durationMillis: Long): Long {
    val duration = durationMillis.coerceAtLeast(0L)
    return if (startedAt > Long.MAX_VALUE - duration) Long.MAX_VALUE else startedAt + duration
}

internal fun adventureEventDisplayWindow(
    run: AdventureEventRun,
    retry: Boolean,
    now: Long,
): AdventureEventDisplayWindow {
    val runEndsAt = eventTimePlus(run.startedAt, run.durationMillis)
    val eventDuration = AdventureEventEngine.EVENT_PRESENTATION_MILLIS.coerceAtMost(
        (run.durationMillis.coerceAtLeast(1L) / 2L).coerceAtLeast(1L),
    )
    val eventEndsAt = eventTimePlus(
        run.startedAt,
        eventDuration,
    )
    val stage = if (!retry && now < eventEndsAt) AdventureEventDisplayStage.EVENT else AdventureEventDisplayStage.ACTION
    return AdventureEventDisplayWindow(
        stage = stage,
        startedAt = if (stage == AdventureEventDisplayStage.EVENT || retry) run.startedAt else eventEndsAt,
        endsAt = if (stage == AdventureEventDisplayStage.EVENT) eventEndsAt else runEndsAt,
    )
}

@Composable
private fun rememberAdventureEventDisplayWindow(
    run: AdventureEventRun,
    retry: Boolean,
    initialNow: Long,
): AdventureEventDisplayWindow {
    var now by remember(run.sequence, run.startedAt, retry) { mutableLongStateOf(initialNow) }
    val window = adventureEventDisplayWindow(run, retry, now)
    LaunchedEffect(run.sequence, run.startedAt, retry, window.stage) {
        if (!retry && window.stage == AdventureEventDisplayStage.EVENT) {
            delay((window.endsAt - now).coerceAtLeast(0L))
            now = window.endsAt
        }
    }
    return window
}

@Composable
internal fun AdventureEventPanel(state: SimpleGameState, now: Long) {
    val language = LocalAppLanguage.current
    val result = state.adventureJourney.lastResult.takeIf { state.adventurePhase == AdventurePhase.EVENT_RESULT }
    if (result != null) {
        val progress = remember(result.run.sequence, state.adventurePhase) { Animatable(0f) }
        LaunchedEffect(result.run.sequence, state.adventurePhase, state.actionStartedAt, state.actionEndsAt) {
            val duration = (state.actionEndsAt - state.actionStartedAt).coerceAtLeast(1L)
            progress.snapTo(((now - state.actionStartedAt).toFloat() / duration).coerceIn(0f, 1f))
            val remaining = (state.actionEndsAt - now).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            if (remaining > 0) progress.animateTo(1f, tween(remaining, easing = LinearEasing))
        }
        LootResultPanel(state = state, progress = progress.value, eventResult = result)
        return
    }
    val retryStarted = rememberAdventureRetryStarted(state, now)
    val run = adventureEventActionRun(state, retryStarted) ?: return
    val retry = retryStarted && state.adventureTraits.source?.retryRun != null
    val displayWindow = rememberAdventureEventDisplayWindow(run, retry, now)
    val definition = AdventureEventEngine.definition(run.eventId)
    val progress = remember(run.sequence, run.startedAt, displayWindow.stage) { Animatable(0f) }
    LaunchedEffect(run.sequence, run.startedAt, displayWindow.stage, displayWindow.startedAt, displayWindow.endsAt) {
        val duration = (displayWindow.endsAt - displayWindow.startedAt).coerceAtLeast(1L)
        progress.snapTo(((now - displayWindow.startedAt).toFloat() / duration).coerceIn(0f, 1f))
        val remaining = (displayWindow.endsAt - now).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        if (remaining > 0) progress.animateTo(1f, tween(remaining, easing = LinearEasing))
    }
    val approach = definition.approaches.first { it.id == run.approachId }
    val centerText = adventureEventCenterText(
        stage = displayWindow.stage,
        eventName = definition.title.inLanguage(language),
        eventScene = definition.scene.inLanguage(language),
        actionTitle = approach.title.inLanguage(language),
    )
    Card(
        modifier = Modifier.fillMaxWidth().height(218.dp).padding(horizontal = 16.dp),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Box(Modifier.fillMaxSize().border(1.dp, AqGoldSoft, RoundedCornerShape(22.dp))) {
            Image(
                painter = painterResource(
                    battleBackgroundResource(
                        definitionId = state.adventureTale.definitionId,
                        chapterNumber = state.adventureTale.chapterNumber,
                    ),
                ),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(Color(0xD5161020), Color(0xE01A1222), Color(0xF015101B)),
                    ),
                ),
            )
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(58.dp)
                        .background(Color(0xEE17111F))
                        .border(1.dp, AqGoldSoft)
                        .padding(horizontal = 15.dp, vertical = 8.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        MaterialText(
                            text = adventureEventContextLabel(run.context, language),
                            color = AqText,
                            fontSize = 13.sp,
                            lineHeight = 17.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        MaterialText(
                            text = when (displayWindow.stage) {
                                AdventureEventDisplayStage.EVENT -> journeyText(language, "사건 발견", "Discovering", "出来事を発見")
                                AdventureEventDisplayStage.ACTION -> if (retry) {
                                    journeyText(language, "다시 조사", "Trying again", "再調査")
                                } else {
                                    journeyText(language, "행동 수행", "Taking action", "行動")
                                }
                            },
                            color = Color(0xFF84D3B0),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { progress.value },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .semantics {
                                contentDescription = when (displayWindow.stage) {
                                    AdventureEventDisplayStage.EVENT -> definition.title.inLanguage(language)
                                    AdventureEventDisplayStage.ACTION -> approach.title.inLanguage(language)
                                }
                                stateDescription = when (displayWindow.stage) {
                                    AdventureEventDisplayStage.EVENT -> journeyText(language, "1/2 · 사건 발견 중", "1 of 2 · Discovering an event", "1/2 · 出来事を発見中")
                                    AdventureEventDisplayStage.ACTION -> if (retry) {
                                        journeyText(language, "2/2 · 다시 조사 중", "2 of 2 · Trying again", "2/2 · 再調査中")
                                    } else {
                                        journeyText(language, "2/2 · 행동 진행 중", "2 of 2 · Action in progress", "2/2 · 行動の進行中")
                                    }
                                }
                            },
                        color = AqGold,
                        trackColor = Color(0xFF4A3B4F),
                    )
                }
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val contentMetrics = adventureCenterTextMetrics(centerText.content)
                    Column(
                        modifier = Modifier.widthIn(max = 330.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        MaterialText(
                            text = centerText.eventName,
                            color = AqMuted,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(7.dp))
                        MaterialText(
                            text = centerText.content,
                            color = AqText,
                            fontSize = contentMetrics.fontSizeSp.sp,
                            lineHeight = contentMetrics.lineHeightSp.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = contentMetrics.maxLines,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}
