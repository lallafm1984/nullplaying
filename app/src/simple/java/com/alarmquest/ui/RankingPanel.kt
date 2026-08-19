package com.alarmquest.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alarmquest.model.HeroClass
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal enum class RankingSnapshotSource {
    MOCK,
    CACHE,
    REMOTE,
}

internal data class RankingCandidate(
    val characterId: String,
    val displayName: String,
    val heroClass: HeroClass,
    val level: Long,
    val score: Long,
    val achievedAtEpochMillis: Long,
    val verifiedAtEpochMillis: Long,
    val isMe: Boolean = false,
)

internal data class RankingEntry(
    val rank: Int,
    val characterId: String,
    val displayName: String,
    val heroClass: HeroClass,
    val level: Long,
    val combatPower: Long,
    val honorific: String,
    val achievedAtEpochMillis: Long,
    val verifiedAtEpochMillis: Long,
    val isMe: Boolean,
)

internal data class RankingSnapshot(
    val snapshotId: String,
    val source: RankingSnapshotSource,
    val fetchedAtEpochMillis: Long,
    val formulaVersion: Int,
    val totalParticipants: Int,
    val entries: List<RankingEntry>,
    val myEntry: RankingEntry,
)

internal sealed interface RankingUiState {
    data object Loading : RankingUiState

    data class Content(val snapshot: RankingSnapshot) : RankingUiState

    data class Empty(val message: String) : RankingUiState

    data class Error(
        val message: String,
        val cachedSnapshot: RankingSnapshot? = null,
    ) : RankingUiState
}

internal data class RankingHeaderPresentation(
    val visualLabel: String,
    val accessibilityLabel: String,
    val isRanked: Boolean,
)

internal fun honorificForRank(rank: Int?): String = when {
    rank == null || rank <= 0 -> "순위 집계 중"
    rank == 1 -> "왕좌의 모험가"
    rank <= 3 -> "전설의 선봉"
    rank <= 10 -> "황금 개척자"
    rank <= 50 -> "은빛 추적자"
    rank <= 100 -> "청동 길잡이"
    else -> "여정의 도전자"
}

internal fun rankCandidates(candidates: List<RankingCandidate>): List<RankingEntry> {
    val sorted = candidates.sortedWith(
        compareByDescending<RankingCandidate> { it.score }
            .thenBy { it.achievedAtEpochMillis }
            .thenBy { it.characterId },
    )
    var previousScore: Long? = null
    var previousRank = 0
    return sorted.mapIndexed { index, candidate ->
        val rank = if (candidate.score == previousScore) previousRank else index + 1
        previousScore = candidate.score
        previousRank = rank
        RankingEntry(
            rank = rank,
            characterId = candidate.characterId,
            displayName = candidate.displayName,
            heroClass = candidate.heroClass,
            level = candidate.level,
            combatPower = candidate.score,
            honorific = honorificForRank(rank),
            achievedAtEpochMillis = candidate.achievedAtEpochMillis,
            verifiedAtEpochMillis = candidate.verifiedAtEpochMillis,
            isMe = candidate.isMe,
        )
    }
}

internal fun initialRankingUiState(
    showPreviewData: Boolean,
    playerName: String,
    playerClass: HeroClass,
    playerLevel: Long,
    playerCombatPower: Long,
    fetchedAtEpochMillis: Long,
): RankingUiState = if (showPreviewData) {
    RankingUiState.Content(
        previewRankingSnapshot(
            playerName = playerName,
            playerClass = playerClass,
            playerLevel = playerLevel,
            playerCombatPower = playerCombatPower,
            fetchedAtEpochMillis = fetchedAtEpochMillis,
        ),
    )
} else {
    RankingUiState.Empty("아직 집계된 순위가 없습니다")
}

internal fun rankingHeaderPresentation(uiState: RankingUiState): RankingHeaderPresentation {
    val entry = when (uiState) {
        is RankingUiState.Content -> uiState.snapshot.myEntry
        is RankingUiState.Error -> uiState.cachedSnapshot?.myEntry
        RankingUiState.Loading,
        is RankingUiState.Empty -> null
    }
    if (entry != null) {
        val rank = rankingNumber(entry.rank.toLong())
        return RankingHeaderPresentation(
            visualLabel = "전체 ${rank}위",
            accessibilityLabel = "전체 순위 ${rank}위",
            isRanked = true,
        )
    }

    val isLoading = uiState == RankingUiState.Loading
    return RankingHeaderPresentation(
        visualLabel = if (isLoading) "순위 집계 중" else "순위 미집계",
        accessibilityLabel = if (isLoading) "전체 순위 집계 중" else "전체 순위 미집계",
        isRanked = false,
    )
}

internal fun previewRankingSnapshot(
    playerName: String,
    playerClass: HeroClass,
    playerLevel: Long,
    playerCombatPower: Long,
    fetchedAtEpochMillis: Long,
): RankingSnapshot {
    val power = playerCombatPower.coerceAtLeast(0L)
    val verifiedAt = fetchedAtEpochMillis
    val candidates = listOf(
        previewCandidate("mock-01", "잿빛왕관", HeroClass.PALADIN, playerLevel + 19L, powerAbove(power, 40), fetchedAtEpochMillis - 720_000L, verifiedAt),
        previewCandidate("mock-02", "새벽의방패", HeroClass.WARRIOR, playerLevel + 15L, powerAbove(power, 31), fetchedAtEpochMillis - 650_000L, verifiedAt),
        previewCandidate("mock-03", "북풍의서약", HeroClass.RANGER, playerLevel + 14L, powerAbove(power, 31), fetchedAtEpochMillis - 620_000L, verifiedAt),
        previewCandidate("mock-04", "별을읽는자", HeroClass.MAGE, playerLevel + 12L, powerAbove(power, 23), fetchedAtEpochMillis - 560_000L, verifiedAt),
        previewCandidate("mock-05", "유리숲을걷는긴이름의모험가", HeroClass.CLERIC, playerLevel + 9L, powerAbove(power, 17), fetchedAtEpochMillis - 500_000L, verifiedAt),
        previewCandidate("mock-06", "검은실", HeroClass.ROGUE, playerLevel + 7L, powerAbove(power, 11), fetchedAtEpochMillis - 440_000L, verifiedAt),
        previewCandidate("mock-07", "황혼나침반", HeroClass.RANGER, playerLevel + 4L, powerAbove(power, 5), fetchedAtEpochMillis - 380_000L, verifiedAt),
        RankingCandidate(
            characterId = "preview-me",
            displayName = playerName.ifBlank { "나의 모험가" },
            heroClass = playerClass,
            level = playerLevel,
            score = power,
            achievedAtEpochMillis = fetchedAtEpochMillis - 320_000L,
            verifiedAtEpochMillis = verifiedAt,
            isMe = true,
        ),
        previewCandidate("mock-09", "고요한불씨", HeroClass.MAGE, (playerLevel - 2L).coerceAtLeast(1L), powerBelow(power, 4), fetchedAtEpochMillis - 260_000L, verifiedAt),
        previewCandidate("mock-10", "철빛여우", HeroClass.ROGUE, (playerLevel - 4L).coerceAtLeast(1L), powerBelow(power, 9), fetchedAtEpochMillis - 200_000L, verifiedAt),
        previewCandidate("mock-11", "작은성화", HeroClass.CLERIC, (playerLevel - 6L).coerceAtLeast(1L), powerBelow(power, 14), fetchedAtEpochMillis - 140_000L, verifiedAt),
    )
    val ranked = rankCandidates(candidates)
    val myEntry = checkNotNull(ranked.singleOrNull { it.isMe })
    return RankingSnapshot(
        snapshotId = "preview-$fetchedAtEpochMillis",
        source = RankingSnapshotSource.MOCK,
        fetchedAtEpochMillis = fetchedAtEpochMillis,
        formulaVersion = 0,
        totalParticipants = 1_284,
        entries = ranked.take(10),
        myEntry = myEntry,
    )
}

private fun previewCandidate(
    characterId: String,
    displayName: String,
    heroClass: HeroClass,
    level: Long,
    score: Long,
    achievedAtEpochMillis: Long,
    verifiedAtEpochMillis: Long,
): RankingCandidate = RankingCandidate(
    characterId = characterId,
    displayName = displayName,
    heroClass = heroClass,
    level = level.coerceAtLeast(1L),
    score = score,
    achievedAtEpochMillis = achievedAtEpochMillis,
    verifiedAtEpochMillis = verifiedAtEpochMillis,
)

private fun powerAbove(base: Long, percent: Int): Long {
    val delta = percentageDelta(base, percent).coerceAtLeast(1L)
    return if (base > Long.MAX_VALUE - delta) Long.MAX_VALUE else base + delta
}

private fun powerBelow(base: Long, percent: Int): Long =
    (base - percentageDelta(base, percent).coerceAtLeast(1L)).coerceAtLeast(0L)

private fun percentageDelta(base: Long, percent: Int): Long {
    val safeBase = base.coerceAtLeast(0L)
    return (safeBase / 100L) * percent + ((safeBase % 100L) * percent) / 100L
}

@Composable
internal fun RankingEntryMenu(
    uiState: RankingUiState,
    onClick: () -> Unit,
) {
    val myEntry = (uiState as? RankingUiState.Content)?.snapshot?.myEntry
    val detail = if (myEntry == null) {
        "순위 미집계"
    } else {
        "${myEntry.rank}위 · ${myEntry.honorific}"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(AqSurfaceHigh)
            .border(1.dp, Color(0xFF46394F), RoundedCornerShape(14.dp))
            .clickable(
                role = Role.Button,
                onClickLabel = "모험가 랭킹 열기",
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = "모험가 랭킹, $detail"
            }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Leaderboard,
            contentDescription = null,
            tint = AqGold,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "모험가 랭킹",
                color = AqText,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = detail,
                color = if (myEntry == null) AqMuted else AqGold,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = AqMuted,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
internal fun RankingScreen(
    uiState: RankingUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().background(AqBackground)) {
        RankingTopBar(uiState = uiState, onBack = onBack)
        when (uiState) {
            RankingUiState.Loading -> RankingLoadingState()
            is RankingUiState.Content -> RankingContent(snapshot = uiState.snapshot)
            is RankingUiState.Empty -> RankingMessageState(
                title = uiState.message,
            )
            is RankingUiState.Error -> {
                val cached = uiState.cachedSnapshot
                if (cached == null) {
                    RankingMessageState(
                        title = "랭킹을 불러오지 못했습니다",
                        detail = uiState.message,
                        actionLabel = "다시 시도",
                        onAction = onRetry,
                    )
                } else {
                    RankingContent(
                        snapshot = cached,
                        warning = "새 순위를 불러오지 못해 이전 기록을 표시합니다",
                    )
                }
            }
        }
    }
}

@Composable
private fun RankingTopBar(uiState: RankingUiState, onBack: () -> Unit) {
    val fetchedAt = when (uiState) {
        is RankingUiState.Content -> uiState.snapshot.fetchedAtEpochMillis
        is RankingUiState.Error -> uiState.cachedSnapshot?.fetchedAtEpochMillis
        else -> null
    }
    Row(
        modifier = Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "캐릭터 화면으로 돌아가기",
                tint = AqText,
                modifier = Modifier.size(26.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = "모험가 랭킹",
                modifier = Modifier.semantics { heading() },
                color = AqText,
                fontSize = 20.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Black,
            )
            if (fetchedAt != null) {
                Text(
                    text = "${rankingTimeLabel(fetchedAt)} 기준",
                    color = AqMuted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
            }
        }
    }
}

@Composable
private fun RankingContent(
    snapshot: RankingSnapshot,
    warning: String? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
    ) {
        if (warning != null) {
            Text(
                text = warning,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AqRed.copy(alpha = 0.12f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                color = AqRed,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
        MyRankingCard(snapshot.myEntry)
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 14.dp, end = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "전체 랭킹",
                modifier = Modifier.semantics { heading() },
                color = AqText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "전투력 순",
                color = AqGold,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(top = 6.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(snapshot.entries, key = RankingEntry::characterId) { entry ->
                RankingListRow(entry)
            }
        }
    }
}

@Composable
private fun MyRankingCard(entry: RankingEntry) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 104.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "내 순위 ${entry.rank}위, ${entry.displayName}, 호칭 ${entry.honorific}, 전투력 ${rankingNumber(entry.combatPower)}"
            },
        colors = CardDefaults.cardColors(containerColor = AqGold.copy(alpha = 0.10f)),
        border = BorderStroke(1.dp, AqGoldSoft),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.width(78.dp)) {
                Text("내 순위", color = AqGold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = "#${rankingNumber(entry.rank.toLong())}",
                    color = AqText,
                    fontSize = 29.sp,
                    lineHeight = 32.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                )
            }
            Box(Modifier.width(1.dp).height(58.dp).background(AqGoldSoft.copy(alpha = 0.65f)))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.displayName,
                        modifier = Modifier.weight(1f),
                        color = AqText,
                        fontSize = 16.sp,
                        lineHeight = 21.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = entry.honorific,
                    color = AqGold,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "${entry.heroClass.labelKo} · Lv.${rankingNumber(entry.level)} · 전투력 ${rankingNumber(entry.combatPower)}",
                    color = AqMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun RankingListRow(entry: RankingEntry) {
    val rankColor = when (entry.rank) {
        1 -> AqGold
        2 -> Color(0xFFD8D3DF)
        3 -> Color(0xFFC78C66)
        else -> AqMuted
    }
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 62.dp)
            .clip(shape)
            .background(if (entry.isMe) AqGold.copy(alpha = 0.09f) else AqSurface)
            .then(if (entry.isMe) Modifier.border(1.dp, AqGoldSoft, shape) else Modifier)
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append("${entry.rank}위, ")
                    if (entry.isMe) append("나, ")
                    append("${entry.displayName}, ${entry.heroClass.labelKo}, 레벨 ${entry.level}, ")
                    append("호칭 ${entry.honorific}, 전투력 ${rankingNumber(entry.combatPower)}")
                }
            }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${entry.rank}",
            modifier = Modifier.width(38.dp),
            color = rankColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.displayName,
                    modifier = Modifier.weight(1f),
                    color = AqText,
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "${entry.heroClass.labelKo} · Lv.${rankingNumber(entry.level)} · ${entry.honorific}",
                color = AqMuted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = rankingNumber(entry.combatPower),
                color = if (entry.isMe) AqGold else AqText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun RankingLoadingState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("랭킹을 불러오는 중", color = AqText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(104.dp).clip(RoundedCornerShape(20.dp)).background(AqSurfaceHigh))
        Spacer(Modifier.height(14.dp))
        repeat(5) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(62.dp)
                    .padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(AqSurface),
            )
        }
    }
}

@Composable
private fun RankingMessageState(
    title: String,
    detail: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Leaderboard,
            contentDescription = null,
            tint = AqGold,
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text(title, color = AqText, fontSize = 19.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        if (!detail.isNullOrBlank()) {
            Spacer(Modifier.height(7.dp))
            Text(detail, color = AqMuted, fontSize = 13.sp, lineHeight = 19.sp, textAlign = TextAlign.Center)
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = AqGold),
            ) {
                Text(actionLabel, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun rankingNumber(value: Long): String =
    NumberFormat.getNumberInstance(Locale.KOREA).format(value)

private fun rankingTimeLabel(epochMillis: Long): String =
    RANKING_TIME_FORMATTER.format(Instant.ofEpochMilli(epochMillis))

private val RANKING_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("M월 d일 HH:mm", Locale.KOREA)
        .withZone(ZoneId.of("Asia/Seoul"))
