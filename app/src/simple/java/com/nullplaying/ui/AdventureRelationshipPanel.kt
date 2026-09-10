package com.nullplaying.ui

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import com.nullplaying.engine.AdventureRelationshipBattleEngine
import com.nullplaying.engine.AdventureRelationshipEngine
import com.nullplaying.localization.AppLanguage
import com.nullplaying.model.*
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.util.Locale

internal const val RELATIONSHIP_DISCOVERY_MILLIS = 5_000L
internal const val RELATIONSHIP_ACTION_MILLIS = 5_000L

internal fun relationshipTierLabel(tier: AdventureRelationshipTier, language: AppLanguage): String = when (tier) {
    AdventureRelationshipTier.VERY_CLOSE -> journeyText(language, "매우 친함", "Very close", "とても親しい")
    AdventureRelationshipTier.CLOSE -> journeyText(language, "친함", "Friendly", "親しい")
    AdventureRelationshipTier.KNOWN -> journeyText(language, "알고 있음", "Acquainted", "顔見知り")
    AdventureRelationshipTier.BAD -> journeyText(language, "나쁨", "Strained", "不仲")
    AdventureRelationshipTier.VERY_BAD -> journeyText(language, "매우 나쁨", "Very strained", "非常に不仲")
    AdventureRelationshipTier.HOSTILE -> journeyText(language, "적대적", "Hostile", "敵対的")
}

internal fun relationshipColor(tier: AdventureRelationshipTier): Color = when (tier) {
    AdventureRelationshipTier.VERY_CLOSE, AdventureRelationshipTier.CLOSE -> Color(0xFF84D3B0)
    AdventureRelationshipTier.KNOWN -> AqGold
    AdventureRelationshipTier.BAD, AdventureRelationshipTier.VERY_BAD, AdventureRelationshipTier.HOSTILE -> Color(0xFFE994A1)
}

internal enum class RelationshipIncidentDisplayStage { DISCOVERY, ACTION, BATTLE }

internal data class RelationshipIncidentDisplayWindow(
    val stage: RelationshipIncidentDisplayStage,
    val startedAt: Long,
    val endsAt: Long,
)

private fun relationshipTimePlus(startedAt: Long, durationMillis: Long): Long {
    val duration = durationMillis.coerceAtLeast(0L)
    return if (startedAt > Long.MAX_VALUE - duration) Long.MAX_VALUE else startedAt + duration
}

/** Fixed visible contract: 5s discovery, 5s action, then only an authored battle duration. */
internal fun relationshipIncidentDisplayWindow(
    run: AdventureRelationshipRun,
    now: Long,
): RelationshipIncidentDisplayWindow {
    val discoveryEnd = relationshipTimePlus(run.startedAt, RELATIONSHIP_DISCOVERY_MILLIS)
    val actionEnd = relationshipTimePlus(discoveryEnd, RELATIONSHIP_ACTION_MILLIS)
    val battleEnd = relationshipTimePlus(actionEnd, run.battleDurationMillis)
    return when {
        now < discoveryEnd -> RelationshipIncidentDisplayWindow(RelationshipIncidentDisplayStage.DISCOVERY, run.startedAt, discoveryEnd)
        now < actionEnd || run.battleDurationMillis <= 0L ->
            RelationshipIncidentDisplayWindow(RelationshipIncidentDisplayStage.ACTION, discoveryEnd, actionEnd)
        else -> RelationshipIncidentDisplayWindow(RelationshipIncidentDisplayStage.BATTLE, actionEnd, battleEnd)
    }
}

@Composable
private fun rememberRelationshipIncidentDisplayWindow(
    run: AdventureRelationshipRun,
    initialNow: Long,
): RelationshipIncidentDisplayWindow {
    var now by remember(run.sequence, run.startedAt, run.battleDurationMillis) {
        mutableLongStateOf(initialNow)
    }
    val window = relationshipIncidentDisplayWindow(run, now)
    LaunchedEffect(run.sequence, window.stage, window.endsAt) {
        val remaining = (window.endsAt - now).coerceAtLeast(0L)
        if (remaining > 0L) delay(remaining)
        now = window.endsAt
    }
    return window
}

internal data class RelationshipBattleFrame(val beat: ArenaLiveBeat, val elapsedMillis: Long)

/** Fits a complete deterministic ledger inside the relationship battle's fixed playback window. */
internal fun relationshipBattleFrame(timeline: ArenaLiveTimeline, progress: Float): RelationshipBattleFrame? {
    if (timeline.beats.isEmpty()) return null
    val safeProgress = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    if (safeProgress >= 1f) return RelationshipBattleFrame(timeline.beats.last(), timeline.beats.last().durationMillis)
    val total = timeline.beats.sumOf { it.durationMillis.coerceAtLeast(0L) }
    if (total <= 0L) return RelationshipBattleFrame(timeline.beats.first(), 0L)
    val target = (safeProgress.toDouble() * total.toDouble()).toLong().coerceIn(0L, total - 1L)
    var cursor = 0L
    timeline.beats.forEach { beat ->
        val duration = beat.durationMillis.coerceAtLeast(0L)
        if (duration > 0L && target < cursor + duration) return RelationshipBattleFrame(beat, target - cursor)
        cursor += duration
    }
    return RelationshipBattleFrame(timeline.beats.last(), timeline.beats.last().durationMillis)
}

internal fun relationshipReceiptPresentation(
    result: AdventureRelationshipResult,
    language: AppLanguage,
): AdventureEventReceiptPresentation {
    val definition = AdventureRelationshipEngine.definition(result.run.sceneId)
    val selectedReward = if (result.run.rewardKind != AdventureEventRewardKind.UNSPECIFIED) result.run.rewardKind else when {
        result.itemName.isNotBlank() || result.run.itemReward != AdventureEventItemReward.NONE -> AdventureEventRewardKind.ITEM
        result.goldAwarded > 0L -> AdventureEventRewardKind.GOLD
        result.experienceAwarded > 0L -> AdventureEventRewardKind.EXPERIENCE
        else -> AdventureEventRewardKind.UNSPECIFIED
    }
    val reward = when (selectedReward) {
        AdventureEventRewardKind.EXPERIENCE -> if (result.experienceAwarded > 0L) AdventureEventReceiptReward.EXPERIENCE else AdventureEventReceiptReward.NONE
        AdventureEventRewardKind.GOLD -> if (result.goldAwarded > 0L) AdventureEventReceiptReward.GOLD else AdventureEventReceiptReward.NONE
        AdventureEventRewardKind.ITEM -> AdventureEventReceiptReward.ITEM
        AdventureEventRewardKind.ROUTE, AdventureEventRewardKind.UNSPECIFIED -> AdventureEventReceiptReward.NONE
    }
    val itemState = when {
        reward != AdventureEventReceiptReward.ITEM -> AdventureEventItemReceiptState.NONE
        result.itemName.isNotBlank() -> AdventureEventItemReceiptState.ACQUIRED
        else -> AdventureEventItemReceiptState.BAG_FULL
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
            AdventureEventItemReceiptState.BAG_FULL -> journeyText(
                language,
                "가방이 가득 차 아이템을 담지 못했습니다",
                "The bag was full, so the item was left behind",
                "バッグがいっぱいで持ち帰れませんでした",
            )
            AdventureEventItemReceiptState.OMITTED, AdventureEventItemReceiptState.NONE ->
                journeyText(language, "보상 없음", "No reward", "報酬なし")
        }
        AdventureEventReceiptReward.ROUTE, AdventureEventReceiptReward.BATTLE, AdventureEventReceiptReward.NONE ->
            journeyText(language, "보상 없음", "No reward", "報酬なし")
    }
    val relationChange = result.scoreAfter - result.run.scoreBefore
    val relationshipSummary = when {
        relationChange > 0 -> journeyText(language, "사이가 조금 가까워졌습니다", "You grew a little closer", "少し親しくなりました")
        relationChange < 0 -> journeyText(language, "서로에게 거리감이 남았습니다", "Some distance remains", "互いに距離が残りました")
        else -> journeyText(language, "서로를 조금 더 알게 되었습니다", "You learned a little more about each other", "互いを少し知りました")
    }
    val narrative = when (result.run.outcome) {
        AdventureEventOutcome.SUCCESS -> definition.success
        AdventureEventOutcome.PARTIAL -> definition.partial
        AdventureEventOutcome.FAILURE -> definition.failure
    }.inLanguage(language)
    return AdventureEventReceiptPresentation(
        eventTitle = definition.title.inLanguage(language),
        subjectName = result.run.candidate.displayName,
        outcomeLabel = relationshipTierLabel(result.tier, language),
        narrative = narrative,
        reward = reward,
        rewardTypeLabel = when (reward) {
            AdventureEventReceiptReward.EXPERIENCE -> journeyText(language, "경험치", "Experience", "経験値")
            AdventureEventReceiptReward.GOLD -> journeyText(language, "골드", "Gold", "ゴールド")
            AdventureEventReceiptReward.ITEM -> journeyText(language, "장비", "Equipment", "装備")
            else -> journeyText(language, "보상 없음", "No reward", "報酬なし")
        },
        rewardSummary = rewardSummary,
        penaltySummary = relationshipSummary,
        itemState = itemState,
    )
}

@Composable
internal fun AdventureRelationshipPanel(state: SimpleGameState, now: Long) {
    val result = state.adventureRelationships.lastResult.takeIf { state.adventurePhase == AdventurePhase.RELATIONSHIP_RESULT }
    if (result != null) {
        val progress = remember(result.run.sequence, state.adventurePhase) { Animatable(0f) }
        LaunchedEffect(result.run.sequence, state.adventurePhase, state.actionStartedAt, state.actionEndsAt) {
            val duration = (state.actionEndsAt - state.actionStartedAt).coerceAtLeast(1L)
            progress.snapTo(((now - state.actionStartedAt).toFloat() / duration).coerceIn(0f, 1f))
            val remaining = (state.actionEndsAt - now).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            if (remaining > 0) progress.animateTo(1f, tween(remaining, easing = LinearEasing))
        }
        LootResultPanel(state = state, progress = progress.value, relationshipResult = result)
        return
    }

    val run = state.adventureRelationships.pending ?: return
    val window = rememberRelationshipIncidentDisplayWindow(run, now)
    val progress = remember(run.sequence, window.stage) { Animatable(0f) }
    LaunchedEffect(run.sequence, window.stage, window.startedAt, window.endsAt) {
        val duration = (window.endsAt - window.startedAt).coerceAtLeast(1L)
        progress.snapTo(((now - window.startedAt).toFloat() / duration).coerceIn(0f, 1f))
        val remaining = (window.endsAt - now).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        if (remaining > 0) progress.animateTo(1f, tween(remaining, easing = LinearEasing))
    }
    if (window.stage == RelationshipIncidentDisplayStage.BATTLE) {
        RelationshipBattleStage(state = state, run = run, progress = progress.value)
    } else {
        RelationshipIncidentStage(state = state, run = run, stage = window.stage, progress = progress.value)
    }
}

@Composable
private fun RelationshipIncidentStage(
    state: SimpleGameState,
    run: AdventureRelationshipRun,
    stage: RelationshipIncidentDisplayStage,
    progress: Float,
) {
    val language = LocalAppLanguage.current
    val definition = AdventureRelationshipEngine.definition(run.sceneId)
    val approach = definition.approaches.first { it.id == run.approachId }
    val content = when (stage) {
        RelationshipIncidentDisplayStage.DISCOVERY -> definition.scene.inLanguage(language)
        RelationshipIncidentDisplayStage.ACTION -> approach.title.inLanguage(language)
        RelationshipIncidentDisplayStage.BATTLE -> ""
    }
    Card(
        modifier = Modifier.fillMaxWidth().height(218.dp).padding(horizontal = 16.dp),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Box(Modifier.fillMaxSize().border(1.dp, AqGoldSoft, RoundedCornerShape(22.dp))) {
            Image(
                painter = painterResource(battleBackgroundResource(state.adventureTale.definitionId, state.adventureTale.chapterNumber)),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xD5161020), Color(0xE01A1222), Color(0xF015101B)))))
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxWidth().height(58.dp).background(Color(0xEE17111F))
                        .border(1.dp, AqGoldSoft).padding(horizontal = 15.dp, vertical = 8.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        MaterialText(
                            text = journeyText(language, "${run.candidate.displayName} · 만남", "Encounter with ${run.candidate.displayName}", "${run.candidate.displayName}との出会い"),
                            color = AqText,
                            fontSize = 13.sp,
                            lineHeight = 17.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        MaterialText(
                            text = when (stage) {
                                RelationshipIncidentDisplayStage.DISCOVERY -> journeyText(language, "사건 발견", "Discovering", "出来事を発見")
                                RelationshipIncidentDisplayStage.ACTION -> journeyText(language, "행동 수행", "Taking action", "行動")
                                RelationshipIncidentDisplayStage.BATTLE -> journeyText(language, "자동 전투", "Auto battle", "自動戦闘")
                            },
                            color = Color(0xFF84D3B0),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(99.dp)).semantics {
                            contentDescription = definition.title.inLanguage(language)
                            stateDescription = when (stage) {
                                RelationshipIncidentDisplayStage.DISCOVERY -> journeyText(language, "1/2 · 사건 발견 중", "1 of 2 · Discovering", "1/2 · 出来事を発見中")
                                RelationshipIncidentDisplayStage.ACTION -> journeyText(language, "2/2 · 행동 진행 중", "2 of 2 · Taking action", "2/2 · 行動中")
                                RelationshipIncidentDisplayStage.BATTLE -> journeyText(language, "자동 전투 중", "Auto battle in progress", "自動戦闘中")
                            }
                        },
                        color = AqGold,
                        trackColor = Color(0xFF4A3B4F),
                    )
                }
                Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                    val contentMetrics = adventureCenterTextMetrics(content)
                    Column(Modifier.widthIn(max = 330.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        MaterialText(
                            definition.title.inLanguage(language), color = AqMuted, fontSize = 12.sp, lineHeight = 17.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(7.dp))
                        MaterialText(
                            content, color = AqText,
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

@Composable
private fun RelationshipBattleStage(state: SimpleGameState, run: AdventureRelationshipRun, progress: Float) {
    val language = LocalAppLanguage.current
    val resolution = remember(run.battleSeed, run.localBattleSnapshot, run.opponentBattleSnapshot) {
        AdventureRelationshipBattleEngine.simulate(run)
    }
    val names = remember(resolution, run.candidate.displayName, state.hero.name) {
        resolution?.let {
            linkedMapOf(
                it.user.fighter.id to (run.localBattleSnapshot?.displayName ?: state.hero.name),
                it.opponent.fighter.id to (run.opponentBattleSnapshot?.displayName ?: run.candidate.displayName),
            )
        }
    }
    val timeline = remember(resolution, names, language) {
        if (resolution == null || names == null) null
        else runCatching { buildArenaLiveTimeline(resolution.simulation, names, language.languageTag) }.getOrNull()
    }
    val frame = timeline?.let { relationshipBattleFrame(it, progress) }
    val beat = frame?.beat
    val elapsed = frame?.elapsedMillis ?: 0L
    val reducedMotion = !ValueAnimator.areAnimatorsEnabled()
    val activeSkill = beat?.skill?.catalogId?.let(::skillDefinition)
    val local = run.localBattleSnapshot
    val opponent = run.opponentBattleSnapshot
    fun fighter(id: String?, snapshot: AdventureRelationshipBattleParticipantSnapshot?): ArenaCombatFighterUi {
        val after = id?.let { beat?.after?.get(it) }
        val before = id?.let { beat?.before?.get(it) } ?: after
        val targetSkill = activeSkill.takeIf { beat?.skill?.targetId == id }
        val hp = if (before != null && after != null) arenaLiveDisplayedGaugeFraction(before.hpFraction, after.hpFraction, elapsed, targetSkill, reducedMotion) else 1f
        val mp = if (before != null && after != null) arenaLiveGaugeFraction(before.mpFraction, after.mpFraction, elapsed) else 1f
        val shield = if (before != null && after != null) arenaLiveDisplayedGaugeFraction(before.shieldFraction, after.shieldFraction, elapsed, targetSkill, reducedMotion) else 0f
        return ArenaCombatFighterUi(
            name = snapshot?.displayName.orEmpty().ifBlank { journeyText(language, "모험가", "Adventurer", "冒険者") },
            classLabel = snapshot?.heroClass?.labelKo?.let { localized(it, language) }.orEmpty(),
            level = snapshot?.level ?: 1L,
            hpFraction = hp,
            mpFraction = mp,
            shieldFraction = shield,
        )
    }
    val userId = resolution?.user?.fighter?.id
    val opponentId = resolution?.opponent?.fighter?.id
    val tint = if (beat?.damageTargetId != null) {
        val color = if (beat.damageTargetId == userId) AqRed else Color(0xFF78A8D8)
        color.copy(alpha = arenaLiveImpactTintAlpha(elapsed, activeSkill))
    } else Color.Transparent
    val errorMessage = journeyText(language, "전투 기록을 확인할 수 없습니다.", "The battle record could not be verified.", "戦闘記録を確認できませんでした。")
    ArenaCombatStage(
        left = fighter(userId, local),
        right = fighter(opponentId, opponent),
        message = if (timeline == null) errorMessage else beat?.message,
        tint = tint,
        backgroundResourceId = battleBackgroundResource(state.adventureTale.definitionId, state.adventureTale.chapterNumber),
        skillCatalogId = beat?.skill?.catalogId,
        skillElapsedMillis = elapsed.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        skillMirrored = beat?.skill?.actorId == opponentId,
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = journeyText(
                language,
                "${local?.displayName ?: state.hero.name} · ${opponent?.displayName ?: run.candidate.displayName} 인연 전투 · 자동 진행",
                "Relationship battle between ${local?.displayName ?: state.hero.name} and ${opponent?.displayName ?: run.candidate.displayName} · automatic",
                "${local?.displayName ?: state.hero.name}と${opponent?.displayName ?: run.candidate.displayName}の縁の戦闘 · 自動進行",
            )
        },
    )
}

@Composable
internal fun AdventureRelationshipProfile(state: SimpleGameState) {
    val language = LocalAppLanguage.current
    val contacts = state.adventureRelationships.contacts.sortedWith(
        compareByDescending<AdventureRelationshipContact> { it.lastMetAt }.thenBy { it.latestSnapshot.displayName },
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MaterialText(journeyText(language, "인연", "Relationships", "縁"), color = AqText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        MaterialText(
            journeyText(language, "모험에서 실제로 마주친 모험가와의 관계입니다.", "Relationships with adventurers encountered during the journey.", "冒険で実際に出会った冒険者との関係です。"),
            color = AqMuted, fontSize = 12.sp, lineHeight = 18.sp,
        )
        if (contacts.isEmpty()) {
            MaterialText(
                if (state.hero.level < AdventureRelationshipEngine.MIN_HERO_LEVEL) journeyText(
                    language,
                    "레벨 10부터 비슷한 레벨의 모험가를 가끔 만날 수 있습니다.",
                    "From level 10, you may occasionally meet adventurers near your level.",
                    "レベル10から、近いレベルの冒険者と時おり出会います。",
                ) else journeyText(
                    language,
                    "아직 만난 모험가가 없습니다. 비슷한 레벨의 모험가와 가끔 마주칩니다.",
                    "No adventurers met yet. You may occasionally meet a nearby-level adventurer.",
                    "まだ出会った冒険者はいません。近いレベルの冒険者と時おり出会います。",
                ),
                color = AqMuted, fontSize = 13.sp, lineHeight = 19.sp,
            )
        }
        contacts.forEach { contact -> RelationshipContactCard(contact, language) }
    }
}

@Composable
private fun RelationshipContactCard(contact: AdventureRelationshipContact, language: AppLanguage) {
    val projection = contact.latestSnapshot
    val accent = relationshipColor(contact.tier)
    val initial = projection.displayName.trim().take(1).ifBlank { "·" }
    val power = NumberFormat.getNumberInstance(Locale.KOREA).format(projection.combatPower)
    Card(colors = CardDefaults.cardColors(containerColor = AqSurfaceHigh), shape = RoundedCornerShape(14.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(42.dp).clip(CircleShape).background(accent.copy(alpha = .15f)).border(1.dp, accent.copy(alpha = .65f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { MaterialText(initial, color = accent, fontSize = 16.sp, fontWeight = FontWeight.Black) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    MaterialText(
                        projection.displayName, modifier = Modifier.weight(1f), color = AqText, fontSize = 15.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(8.dp))
                    MaterialText(relationshipTierLabel(contact.tier, language), color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                MaterialText(
                    journeyText(
                        language,
                        "${localized(projection.heroClass.labelKo, language)} · Lv.${projection.level} · 전투력 $power",
                        "${localized(projection.heroClass.labelKo, language)} · Lv.${projection.level} · Power $power",
                        "${localized(projection.heroClass.labelKo, language)} · Lv.${projection.level} · 戦闘力 $power",
                    ),
                    color = AqMuted, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val memoryTitle = contact.memories.lastOrNull()?.let { memory ->
                    runCatching { AdventureRelationshipEngine.definition(memory.sceneId).title.inLanguage(language) }.getOrNull()
                }
                MaterialText(
                    buildList {
                        add(journeyText(language, "${contact.meetings}번 만남", "${contact.meetings} encounters", "${contact.meetings}回の出会い"))
                        if (!memoryTitle.isNullOrBlank()) add(memoryTitle)
                    }.joinToString(" · "),
                    color = AqMuted, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Source-compatible alias for old previews while the tab is now named Relationships. */
@Composable
internal fun AdventureJourneyProfile(state: SimpleGameState) = AdventureRelationshipProfile(state)
