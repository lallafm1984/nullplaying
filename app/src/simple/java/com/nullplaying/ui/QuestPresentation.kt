package com.nullplaying.ui

import com.nullplaying.engine.AdventureTaleCatalog
import com.nullplaying.engine.LabyrinthProgression
import com.nullplaying.model.CompletedTaleRecord
import com.nullplaying.model.TaleKind

private val LABYRINTH_DEPTH_PREFIX = Regex("^제[0-9]+구역\\s*·\\s*")
private val LABYRINTH_TIER_PREFIX = Regex("^(표층|심층) (원정|관문)\\s*·\\s*")

internal fun completedTalesInReadingOrder(
    history: List<CompletedTaleRecord>,
): List<CompletedTaleRecord> = history.sortedBy(CompletedTaleRecord::taleSequence)

internal fun taleVolumeLabel(
    kind: TaleKind,
    volumeNumber: Int,
    volumeTitle: String,
    heroLevel: Long = 0L,
): String = when (kind) {
    TaleKind.PROLOGUE -> "프롤로그 · $volumeTitle"
    TaleKind.MAIN, TaleKind.EPILOGUE -> "제${volumeNumber}권 · $volumeTitle"
    TaleKind.LABYRINTH -> labyrinthLayerLabel(heroLevel)
}

internal fun taleChapterLabel(
    kind: TaleKind,
    chapterNumber: Int,
    chapterTitle: String,
    labyrinthDepth: Long = 0L,
): String = when (kind) {
    TaleKind.PROLOGUE -> "직업 이야기  $chapterTitle"
    TaleKind.MAIN, TaleKind.EPILOGUE -> "제${chapterNumber}장  $chapterTitle"
    TaleKind.LABYRINTH -> buildString {
        append("제")
        append(resolvedLabyrinthDepth(labyrinthDepth, chapterNumber))
        append("구역 · ")
        append(labyrinthTitleWithoutDepth(chapterTitle))
    }
}

internal fun taleSubtitleLabel(
    kind: TaleKind,
    subtitle: String,
    heroLevel: Long,
): String = if (kind == TaleKind.LABYRINTH) {
    val expeditionKind = LABYRINTH_TIER_PREFIX.find(subtitle)?.groupValues?.get(2) ?: "원정"
    "${if (heroLevel >= AdventureTaleCatalog.LABYRINTH_DEEP_LEVEL) "심층" else "표층"} " +
        "$expeditionKind · " +
        subtitle.replaceFirst(LABYRINTH_TIER_PREFIX, "")
} else {
    subtitle
}

internal fun completedTaleLocationLabel(record: CompletedTaleRecord): String = when (record.kind) {
    TaleKind.PROLOGUE -> "직업 프롤로그"
    TaleKind.MAIN, TaleKind.EPILOGUE -> "제${record.volumeNumber}권 제${record.chapterNumber}장"
    TaleKind.LABYRINTH -> buildString {
        append(labyrinthLayerLabel(record.completedAtLevel))
        append(" · 제")
        val depth = resolvedLabyrinthDepth(record.labyrinthDepth, record.chapterNumber)
        append(depth)
        append("구역")
        if (LabyrinthProgression.isGateDepth(depth)) {
            append(" · 제")
            append(LabyrinthProgression.gateNumberForDepth(depth))
            append("관문 기록")
        }
    }
}

internal fun completedTaleStatusLabel(record: CompletedTaleRecord): String {
    val depth = resolvedLabyrinthDepth(record.labyrinthDepth, record.chapterNumber)
    return if (record.kind == TaleKind.LABYRINTH && LabyrinthProgression.isGateDepth(depth)) {
        "관문 돌파"
    } else {
        "완결"
    }
}

internal fun completedTaleTitleLabel(record: CompletedTaleRecord): String =
    if (record.kind == TaleKind.LABYRINTH) labyrinthTitleWithoutDepth(record.title) else record.title

internal fun labyrinthLayerLabel(heroLevel: Long): String =
    if (heroLevel >= AdventureTaleCatalog.LABYRINTH_DEEP_LEVEL) "심층 미궁" else "표층 미궁"

internal fun resolvedLabyrinthDepth(labyrinthDepth: Long, chapterNumber: Int): Long =
    labyrinthDepth.takeIf { it > 0L } ?: chapterNumber.toLong().coerceAtLeast(1L)

private fun labyrinthTitleWithoutDepth(title: String): String =
    title.replaceFirst(LABYRINTH_DEPTH_PREFIX, "")

internal fun labyrinthHighestDepthValueLabel(completedDepth: Long): String =
    if (completedDepth > 0L) "제${completedDepth}구역" else "기록 없음"

internal fun labyrinthTitleLabel(completedDepth: Long): String =
    "칭호 · ${LabyrinthProgression.titleForCompletedDepth(completedDepth)}"

internal fun labyrinthNextGateLabel(completedDepth: Long): String =
    "다음 관문 · 제${LabyrinthProgression.nextGateDepth(completedDepth)}구역"

internal fun labyrinthDepthRuleLabel(currentDepth: Long): String {
    val depth = currentDepth.coerceAtLeast(1L)
    val rewardBonus = LabyrinthProgression.rewardBonusPercent(depth)
    val rewardLabel = if (rewardBonus > 0L) "보상 +$rewardBonus%" else "기본 보상"
    return if (LabyrinthProgression.isGateDepth(depth)) {
        "제${LabyrinthProgression.gateNumberForDepth(depth)}관문 보스 자동 출현 · $rewardLabel"
    } else {
        "위협 ${LabyrinthProgression.dangerTier(depth)}단계 · $rewardLabel"
    }
}

internal fun labyrinthDepthContentDescription(
    heroLevel: Long,
    currentDepth: Long,
    highestCompletedDepth: Long,
): String = buildString {
    append(labyrinthLayerLabel(heroLevel))
    append(", 현재 깊이 제")
    append(currentDepth.coerceAtLeast(1L))
    append("구역, 최고 완주 깊이 ")
    append(labyrinthHighestDepthValueLabel(highestCompletedDepth))
    append(", ")
    append(labyrinthTitleLabel(highestCompletedDepth))
    append(", ")
    append(labyrinthNextGateLabel(highestCompletedDepth))
    append(", ")
    append(labyrinthDepthRuleLabel(currentDepth))
}
