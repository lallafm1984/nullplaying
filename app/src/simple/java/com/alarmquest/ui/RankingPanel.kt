package com.alarmquest.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.alarmquest.remote.RemoteRankingSnapshot
import kotlinx.coroutines.launch

internal enum class RankingSnapshotSource {
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
    val listIndex: Int = -1,
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
    val usesLocalPower: Boolean = false,
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

internal const val MAX_DISPLAYED_RANK = 1_000
internal const val OUTSIDE_DISPLAYED_RANK = MAX_DISPLAYED_RANK + 1

internal fun shouldEnableMyRankingMove(
    myRankingListIndex: Int?,
    visibleIndices: Set<Int>,
): Boolean = myRankingListIndex != null && myRankingListIndex !in visibleIndices

internal fun RemoteRankingSnapshot.toUiSnapshot(
    playerCharacterId: String,
    playerName: String,
    playerClass: HeroClass,
    playerLevel: Long,
    playerCombatPower: Long,
): RankingSnapshot {
    val mapped = entries.map { remote ->
        RankingEntry(
            rank = remote.rank,
            listIndex = remote.listIndex,
            characterId = remote.characterId,
            displayName = remote.displayName,
            heroClass = remote.heroClass,
            level = remote.level,
            combatPower = remote.combatPower,
            honorific = honorificForRank(remote.rank),
            achievedAtEpochMillis = remote.achievedAtEpochMillis,
            verifiedAtEpochMillis = remote.updatedAtEpochMillis,
            isMe = remote.characterId == playerCharacterId,
        )
    }
    val myRemote = myEntry?.takeIf { it.characterId == playerCharacterId }
    val serverMine = mapped.firstOrNull { it.characterId == playerCharacterId }
        ?: myRemote?.let { remote ->
            RankingEntry(
                rank = remote.rank,
                listIndex = remote.listIndex,
                characterId = remote.characterId,
                displayName = remote.displayName,
                heroClass = remote.heroClass,
                level = remote.level,
                combatPower = remote.combatPower,
                honorific = honorificForRank(remote.rank),
                achievedAtEpochMillis = remote.achievedAtEpochMillis,
                verifiedAtEpochMillis = remote.updatedAtEpochMillis,
                isMe = true,
            )
        }
    val mine = serverMine ?: RankingEntry(
        rank = 0,
        listIndex = -1,
        characterId = playerCharacterId,
        displayName = playerName,
        heroClass = playerClass,
        level = playerLevel,
        combatPower = playerCombatPower,
        honorific = if (playerLevel < 20L) "Lv.20부터 참가" else "순위 집계 중",
        achievedAtEpochMillis = fetchedAtEpochMillis,
        verifiedAtEpochMillis = fetchedAtEpochMillis,
        isMe = true,
    )
    return RankingSnapshot(
        snapshotId = "remote-$fetchedAtEpochMillis",
        source = if (isFromCache) RankingSnapshotSource.CACHE else RankingSnapshotSource.REMOTE,
        fetchedAtEpochMillis = fetchedAtEpochMillis,
        formulaVersion = 1,
        totalParticipants = totalParticipants,
        entries = mapped,
        myEntry = mine,
        usesLocalPower = serverMine == null ||
            serverMine.combatPower != playerCombatPower ||
            serverMine.level != playerLevel,
    )
}

internal fun RankingSnapshot.withLocalPlayerPower(
    characterId: String,
    displayName: String,
    heroClass: HeroClass,
    level: Long,
    combatPower: Long,
    now: Long,
): RankingSnapshot {
    if (level < 20L) {
        return copy(
            entries = entries.filterNot { it.characterId == characterId },
            myEntry = myEntry.copy(
                rank = 0,
                listIndex = -1,
                displayName = displayName,
                heroClass = heroClass,
                level = level,
                combatPower = combatPower,
                honorific = "Lv.20부터 참가",
                verifiedAtEpochMillis = myEntry.verifiedAtEpochMillis,
            ),
            usesLocalPower = true,
        )
    }

    val candidates = entries.filterNot { it.characterId == characterId }.map { entry ->
        RankingCandidate(
            characterId = entry.characterId,
            displayName = entry.displayName,
            heroClass = entry.heroClass,
            level = entry.level,
            score = entry.combatPower,
            achievedAtEpochMillis = entry.achievedAtEpochMillis,
            verifiedAtEpochMillis = entry.verifiedAtEpochMillis,
            isMe = false,
        )
    }.toMutableList()
    val powerIncreased = myEntry.rank <= 0 || combatPower > myEntry.combatPower
    candidates += RankingCandidate(
        characterId = characterId,
        displayName = displayName,
        heroClass = heroClass,
        level = level,
        score = combatPower,
        achievedAtEpochMillis = if (powerIncreased) now else myEntry.achievedAtEpochMillis,
        verifiedAtEpochMillis = myEntry.verifiedAtEpochMillis,
        isMe = true,
    )

    val ranked = rankCandidates(candidates)
    val calculatedMine = checkNotNull(ranked.firstOrNull { it.characterId == characterId })
    val localMine = if (calculatedMine.rank > MAX_DISPLAYED_RANK) {
        calculatedMine.copy(
            rank = OUTSIDE_DISPLAYED_RANK,
            listIndex = -1,
            honorific = honorificForRank(OUTSIDE_DISPLAYED_RANK),
        )
    } else {
        calculatedMine
    }
    return copy(
        entries = ranked.filter { it.rank <= MAX_DISPLAYED_RANK },
        myEntry = localMine,
        usesLocalPower = usesLocalPower ||
            combatPower != myEntry.combatPower ||
            level != myEntry.level,
    )
}

internal fun honorificForRank(rank: Int?): String = when {
    rank == null || rank <= 0 -> "순위 집계 중"
    rank == 1 -> "유일한 왕좌"
    rank == 2 -> "왕좌에 닿은 자"
    rank == 3 -> "천상의 수호자"
    rank == 4 -> "전설의 선봉"
    rank == 5 -> "별을 베는 자"
    rank == 6 -> "불굴의 정복자"
    rank == 7 -> "황금의 개척자"
    rank == 8 -> "새벽의 추적자"
    rank == 9 -> "은빛의 영웅"
    rank == 10 -> "별빛의 계승자"
    rank <= 25 -> "황금의 선구자"
    rank <= 50 -> "은빛 추적자"
    rank <= 100 -> "청동 길잡이"
    rank <= 300 -> "별빛의 도전자"
    rank <= 1_000 -> "새벽의 모험가"
    else -> "여정을 걷는 자"
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
            listIndex = index,
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

internal fun rankingHeaderPresentation(uiState: RankingUiState): RankingHeaderPresentation {
    val entry = when (uiState) {
        is RankingUiState.Content -> uiState.snapshot.myEntry
        is RankingUiState.Error -> uiState.cachedSnapshot?.myEntry
        RankingUiState.Loading,
        is RankingUiState.Empty -> null
    }
    if (entry != null && entry.rank > 0) {
        val rank = rankingPositionLabel(entry.rank)
        return RankingHeaderPresentation(
            visualLabel = "전체 $rank",
            accessibilityLabel = "전체 순위 $rank",
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

@Composable
internal fun RankingEntryMenu(
    uiState: RankingUiState,
    onClick: () -> Unit,
) {
    val myEntry = (uiState as? RankingUiState.Content)?.snapshot?.myEntry
    val detail = rankingEntryMenuDetail(myEntry)
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = localized("모험가 랭킹, $detail, 보기 버튼")
            },
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = AqGold.copy(alpha = 0.12f),
            contentColor = AqText,
        ),
        border = BorderStroke(1.dp, AqGoldSoft),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Leaderboard,
                contentDescription = null,
                tint = AqGold,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "모험가 랭킹",
                    color = AqText,
                    fontSize = 15.sp,
                    lineHeight = 19.sp,
                    fontWeight = FontWeight.Black,
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
            Spacer(Modifier.width(8.dp))
            Text(
                text = "보기",
                color = AqGold,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
            )
            Spacer(Modifier.width(2.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = AqGold,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

internal fun rankingEntryMenuDetail(entry: RankingEntry?): String = when {
    entry == null -> "순위 미집계"
    entry.rank <= 0 -> entry.honorific
    else -> "${rankingPositionLabel(entry.rank)} · ${entry.honorific}"
}

internal fun rankingPositionLabel(rank: Int): String = when {
    rank > MAX_DISPLAYED_RANK -> "${rankingNumber(MAX_DISPLAYED_RANK.toLong())}위 밖"
    rank > 0 -> "${rankingNumber(rank.toLong())}위"
    else -> "순위 없음"
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
                        detail = "네트워크 연결을 확인한 뒤 다시 시도해 주세요.",
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
                contentDescription = localized("캐릭터 화면으로 돌아가기"),
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
                    text = "${rankingTimeLabel(fetchedAt, LocalAppLanguage.current)} 기준",
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
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val myRankingListIndex = remember(snapshot.entries, snapshot.myEntry.characterId) {
        snapshot.entries.indexOfFirst { it.characterId == snapshot.myEntry.characterId }
            .takeIf { it >= 0 }
            ?.plus(RANKING_LIST_ITEM_OFFSET)
    }
    val isAtTop by remember(listState) {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }
    val isMyRankingMoveEnabled by remember(listState, myRankingListIndex) {
        derivedStateOf {
            val visibleIndices = listState.layoutInfo.visibleItemsInfo.mapTo(mutableSetOf()) { it.index }
            shouldEnableMyRankingMove(myRankingListIndex, visibleIndices)
        }
    }
    val quickMoveColors = ButtonDefaults.buttonColors(
        containerColor = AqSurfaceHigh,
        contentColor = AqGold,
        disabledContainerColor = AqSurface,
        disabledContentColor = AqMuted.copy(alpha = 0.45f),
    )
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
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(
                onClick = {
                    scope.launch { listState.animateScrollToItem(RANKING_MY_CARD_INDEX) }
                },
                enabled = !isAtTop,
                colors = quickMoveColors,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text("최상단", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    scope.launch {
                        myRankingListIndex?.let { listState.scrollToItemCentered(it) }
                    }
                },
                enabled = isMyRankingMoveEnabled,
                colors = quickMoveColors,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text("내 순위", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(top = 6.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "my-ranking-card") {
                MyRankingCard(snapshot.myEntry)
            }
            item(key = "ranking-heading") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, end = 4.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "전체 랭킹 · ${rankingNumber(snapshot.totalParticipants.toLong())}명",
                        modifier = Modifier.semantics { heading() },
                        color = AqText,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            itemsIndexed(snapshot.entries, key = { _, entry -> entry.characterId }) { _, entry ->
                RankingListRow(entry)
            }
        }
    }
}

private suspend fun LazyListState.scrollToItemCentered(index: Int) {
    scrollToItem(index)
    val target = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
    val targetCenter = target.offset + target.size / 2
    scrollBy((targetCenter - viewportCenter).toFloat())
}

private const val RANKING_MY_CARD_INDEX = 0
private const val RANKING_LIST_ITEM_OFFSET = 2

@Composable
private fun MyRankingCard(entry: RankingEntry) {
    val isOutsideDisplayedRanking = entry.rank > MAX_DISPLAYED_RANK
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 104.dp)
            .semantics(mergeDescendants = true) {
                val rankingDescription = if (entry.level < 20L) {
                    "랭킹 참가까지 레벨 20, 현재 레벨 ${entry.level}, ${entry.displayName}"
                } else if (isOutsideDisplayedRanking) {
                    "내 순위 ${rankingPositionLabel(entry.rank)}, ${entry.displayName}, 호칭 ${entry.honorific}, 전투력 ${rankingNumber(entry.combatPower)}"
                } else if (entry.rank > 0) {
                    "내 순위 ${entry.rank}위, ${entry.displayName}, 호칭 ${entry.honorific}, 전투력 ${rankingNumber(entry.combatPower)}"
                } else {
                    "내 순위 없음, ${entry.displayName}, 전투력 ${rankingNumber(entry.combatPower)}"
                }
                contentDescription = localizedPreserving(
                    rankingDescription,
                    entry.displayName,
                )
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
                    text = when {
                        isOutsideDisplayedRanking -> rankingPositionLabel(entry.rank)
                        entry.rank > 0 -> "#${rankingNumber(entry.rank.toLong())}"
                        else -> "—"
                    },
                    color = AqText,
                    fontSize = if (isOutsideDisplayedRanking) 14.sp else 29.sp,
                    lineHeight = 32.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                )
            }
            Box(Modifier.width(1.dp).height(58.dp).background(AqGoldSoft.copy(alpha = 0.65f)))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                UnlocalizedText(
                    text = entry.displayName,
                    color = AqText,
                    fontSize = 16.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
                contentDescription = localizedPreserving(
                    buildString {
                        append("${entry.rank}위, ")
                        if (entry.isMe) append("나, ")
                        append("${entry.displayName}, ${entry.heroClass.labelKo}, 레벨 ${entry.level}, ")
                        append("호칭 ${entry.honorific}, 전투력 ${rankingNumber(entry.combatPower)}")
                    },
                    entry.displayName,
                )
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
                UnlocalizedText(
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

private fun rankingTimeLabel(
    epochMillis: Long,
    language: com.alarmquest.localization.AppLanguage,
): String = rankingTimeFormatter(language).format(Instant.ofEpochMilli(epochMillis))

private fun rankingTimeFormatter(
    language: com.alarmquest.localization.AppLanguage,
): DateTimeFormatter = when (language) {
    com.alarmquest.localization.AppLanguage.ENGLISH ->
        DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.ENGLISH)
    com.alarmquest.localization.AppLanguage.JAPANESE ->
        DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.JAPANESE)
    com.alarmquest.localization.AppLanguage.KOREAN ->
        DateTimeFormatter.ofPattern("M월 d일 HH:mm", Locale.KOREA)
}.withZone(ZoneId.of("Asia/Seoul"))
