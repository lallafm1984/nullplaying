package com.nullplaying.ui

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
import com.nullplaying.localization.AppLanguage
import com.nullplaying.model.HeroClass
import com.nullplaying.remote.RankingRefreshPolicy
import java.text.NumberFormat
import java.util.Locale
import com.nullplaying.remote.RemoteRankingSnapshot
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
    val systemEntryCode: String? = null,
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
    val systemEntryCode: String? = null,
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
    val settledAtEpochMillis: Long = 0L,
    val nextSettlementAtEpochMillis: Long = 0L,
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
internal const val RANKING_ENTRY_MENU_MIN_HEIGHT_DP = 64
internal const val RANKING_ENTRY_MENU_TITLE_FONT_SIZE_SP = 15
internal const val RANKING_ENTRY_MENU_DETAIL_FONT_SIZE_SP = 12
internal const val COMPACT_RANKING_ENTRY_MENU_HEIGHT_DP = 40
internal const val COMPACT_RANKING_ENTRY_MENU_TITLE_FONT_SIZE_SP = 12
internal const val COMPACT_RANKING_ENTRY_MENU_DETAIL_FONT_SIZE_SP = 9
internal const val RANKING_PAGE_TITLE_FONT_SIZE_SP = 20
internal const val RANKING_PAGE_SUBTITLE_FONT_SIZE_SP = 12
internal const val RANKING_PAGE_ENTER_DURATION_MILLIS = 220
internal const val RANKING_PAGE_EXIT_DURATION_MILLIS = 190

internal fun rankingPageEnterOffset(targetVisible: Boolean, width: Int): Int =
    if (targetVisible) width / 10 else -width / 10

internal fun rankingPageExitOffset(targetVisible: Boolean, width: Int): Int =
    if (targetVisible) -width / 14 else width / 14

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
    val systemEntries = entries
        .filter { it.systemEntryCode != null }
        .distinctBy { it.characterId }
    val mapped = entries.filter { it.systemEntryCode == null }.map { remote ->
        val removedRanksAhead = systemEntries.count { it.rank < remote.rank }
        val removedRowsAhead = systemEntries.count { it.listIndex < remote.listIndex }
        val adjustedRank = if (remote.rank > 0) {
            (remote.rank - removedRanksAhead).coerceAtLeast(1)
        } else {
            remote.rank
        }
        RankingEntry(
            rank = adjustedRank,
            listIndex = if (remote.listIndex >= 0) {
                (remote.listIndex - removedRowsAhead).coerceAtLeast(0)
            } else {
                remote.listIndex
            },
            characterId = remote.characterId,
            displayName = remote.displayName,
            heroClass = remote.heroClass,
            level = remote.level,
            combatPower = remote.combatPower,
            honorific = honorificForRank(adjustedRank),
            achievedAtEpochMillis = remote.achievedAtEpochMillis,
            verifiedAtEpochMillis = remote.updatedAtEpochMillis,
            isMe = remote.characterId == playerCharacterId,
            systemEntryCode = null,
        )
    }
    val mappedMine = mapped.firstOrNull { it.characterId == playerCharacterId }
    val myRemote = ownEntries.firstOrNull {
        it.characterId == playerCharacterId && it.systemEntryCode == null
    } ?: myEntry?.takeIf {
        it.characterId == playerCharacterId && it.systemEntryCode == null
    }
    // Old server snapshots may still contain system gatekeepers. Remove them from the visible
    // field and close their rank/list gaps for an authenticated own row outside the top field.
    val serverMine = myRemote?.let { remote ->
        val removedRanksAhead = systemEntries.count { it.rank < remote.rank }
        val removedRowsAhead = systemEntries.count { it.listIndex < remote.listIndex }
        val adjustedRank = if (remote.rank > 0) {
            (remote.rank - removedRanksAhead).coerceAtLeast(1)
        } else {
            remote.rank
        }
        RankingEntry(
            rank = adjustedRank,
            listIndex = if (remote.listIndex >= 0) {
                (remote.listIndex - removedRowsAhead).coerceAtLeast(0)
            } else {
                remote.listIndex
            },
            characterId = remote.characterId,
            displayName = remote.displayName,
            heroClass = remote.heroClass,
            level = remote.level,
            combatPower = remote.combatPower,
            honorific = honorificForRank(adjustedRank),
            achievedAtEpochMillis = remote.achievedAtEpochMillis,
            verifiedAtEpochMillis = remote.updatedAtEpochMillis,
            isMe = true,
            systemEntryCode = null,
        )
    }?.let { privateMine ->
        mappedMine?.let { publicMine ->
            privateMine.copy(rank = publicMine.rank, listIndex = publicMine.listIndex)
        } ?: privateMine
    } ?: mappedMine
    // The public list may contain an administrator-sanitized display name. The separately
    // authenticated own row is authoritative for the player's private "my rank" card.
    val mine = serverMine ?: RankingEntry(
        rank = 0,
        listIndex = -1,
        characterId = playerCharacterId,
        displayName = playerName,
        heroClass = playerClass,
        level = playerLevel,
        combatPower = playerCombatPower,
        honorific = if (playerLevel < 20L) "Lv.20부터 참가" else "다음 정산부터 참가",
        achievedAtEpochMillis = settledAtEpochMillis,
        verifiedAtEpochMillis = fetchedAtEpochMillis,
        isMe = true,
    )
    return RankingSnapshot(
        snapshotId = snapshotId,
        source = if (isFromCache) RankingSnapshotSource.CACHE else RankingSnapshotSource.REMOTE,
        fetchedAtEpochMillis = fetchedAtEpochMillis,
        formulaVersion = 1,
        totalParticipants = (totalParticipants - systemEntries.size).coerceAtLeast(mapped.size),
        entries = mapped,
        myEntry = mine,
        usesLocalPower = serverMine == null,
        settledAtEpochMillis = settledAtEpochMillis,
        nextSettlementAtEpochMillis = nextSettlementAtEpochMillis,
    )
}

/**
 * The server supplies one immutable ranking field. Only this device's active character is replaced
 * with its current local power so older clients retain their familiar provisional rank between
 * settlements. Snapshot identity and settlement metadata remain unchanged.
 */
internal fun RankingSnapshot.withLocalPlayerPower(
    characterId: String,
    displayName: String,
    heroClass: HeroClass,
    level: Long,
    combatPower: Long,
    now: Long,
): RankingSnapshot {
    val visibleEntries = entries.filter { it.systemEntryCode == null }
    val visibleParticipantCount = (totalParticipants - (entries.size - visibleEntries.size))
        .coerceAtLeast(visibleEntries.size)
    if (level < 20L) {
        return copy(
            totalParticipants = visibleParticipantCount,
            entries = visibleEntries.filterNot { it.characterId == characterId },
            myEntry = myEntry.copy(
                rank = 0,
                listIndex = -1,
                displayName = displayName,
                heroClass = heroClass,
                level = level,
                combatPower = combatPower,
                honorific = "Lv.20부터 참가",
            ),
            usesLocalPower = true,
        )
    }
    val candidates = visibleEntries.filterNot { it.characterId == characterId }.map { entry ->
        RankingCandidate(
            characterId = entry.characterId,
            displayName = entry.displayName,
            heroClass = entry.heroClass,
            level = entry.level,
            score = entry.combatPower,
            achievedAtEpochMillis = entry.achievedAtEpochMillis,
            verifiedAtEpochMillis = entry.verifiedAtEpochMillis,
            isMe = false,
            systemEntryCode = null,
        )
    }.toMutableList()
    val localPowerChanged = myEntry.rank <= 0 || combatPower != myEntry.combatPower
    candidates += RankingCandidate(
        characterId = characterId,
        displayName = displayName,
        heroClass = heroClass,
        level = level,
        score = combatPower,
        achievedAtEpochMillis = if (localPowerChanged) now else myEntry.achievedAtEpochMillis,
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
    } else calculatedMine
    return copy(
        totalParticipants = maxOf(
            visibleParticipantCount + if (myEntry.rank <= 0) 1 else 0,
            ranked.size,
        ),
        entries = ranked.filter { it.rank <= MAX_DISPLAYED_RANK },
        myEntry = localMine,
        usesLocalPower = usesLocalPower || combatPower != myEntry.combatPower || level != myEntry.level,
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
            systemEntryCode = candidate.systemEntryCode,
        )
    }
}

internal fun rankingDisplayName(entry: RankingEntry): String = entry.displayName

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
    val myEntry = when (uiState) {
        is RankingUiState.Content -> uiState.snapshot.myEntry
        is RankingUiState.Error -> uiState.cachedSnapshot?.myEntry
        else -> null
    }
    val detail = rankingEntryMenuDetail(myEntry)
    RankingEntryMenuButton(
        title = "모험가 랭킹",
        detail = detail,
        detailHighlighted = myEntry != null,
        accessibilityLabel = "모험가 랭킹, $detail, 보기 버튼",
        onClick = onClick,
    )
}

/**
 * Main-screen version of [RankingEntryMenuButton]. It keeps the same gold surface, outline,
 * icon, title, and current-rank detail while fitting the 40dp adventure-status toolbar.
 */
@Composable
internal fun CompactRankingEntryMenu(
    uiState: RankingUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val language = LocalAppLanguage.current
    val myEntry = when (uiState) {
        is RankingUiState.Content -> uiState.snapshot.myEntry
        is RankingUiState.Error -> uiState.cachedSnapshot?.myEntry
        else -> null
    }
    val detail = rankingEntryMenuDetail(myEntry)
    val localizedDetail = localized(detail, language)
    Button(
        onClick = onClick,
        modifier = modifier
            .height(COMPACT_RANKING_ENTRY_MENU_HEIGHT_DP.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = compactAdventurerRankingAccessibilityLabel(localizedDetail, language)
            },
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = AqGold.copy(alpha = 0.12f),
            contentColor = AqText,
        ),
        border = BorderStroke(1.dp, AqGoldSoft),
        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Leaderboard,
            contentDescription = null,
            tint = AqGold,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(5.dp))
        Column {
            UnlocalizedText(
                text = compactAdventurerRankingTitle(language),
                color = AqText,
                fontSize = COMPACT_RANKING_ENTRY_MENU_TITLE_FONT_SIZE_SP.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
            )
            UnlocalizedText(
                text = localizedDetail,
                color = if (myEntry != null) AqGold else AqMuted,
                fontSize = COMPACT_RANKING_ENTRY_MENU_DETAIL_FONT_SIZE_SP.sp,
                lineHeight = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal fun compactAdventurerRankingTitle(language: AppLanguage): String = when (language) {
    AppLanguage.KOREAN -> "모험가 랭킹"
    AppLanguage.ENGLISH -> "Rankings"
    AppLanguage.JAPANESE -> "ランキング"
}

internal fun compactAdventurerRankingAccessibilityLabel(
    localizedDetail: String,
    language: AppLanguage,
): String = when (language) {
    AppLanguage.KOREAN -> "모험가 랭킹, $localizedDetail, 보기 버튼"
    AppLanguage.ENGLISH -> "Adventurer rankings, $localizedDetail, view button"
    AppLanguage.JAPANESE -> "冒険者ランキング、$localizedDetail、表示ボタン"
}

@Composable
internal fun RankingEntryMenuButton(
    title: String,
    detail: String,
    detailHighlighted: Boolean,
    accessibilityLabel: String,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RANKING_ENTRY_MENU_MIN_HEIGHT_DP.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = localized(accessibilityLabel)
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
                UnlocalizedText(
                    text = localized(title),
                    color = AqText,
                    fontSize = RANKING_ENTRY_MENU_TITLE_FONT_SIZE_SP.sp,
                    lineHeight = 19.sp,
                    fontWeight = FontWeight.Black,
                )
                UnlocalizedText(
                    text = localized(detail),
                    color = if (detailHighlighted) AqGold else AqMuted,
                    fontSize = RANKING_ENTRY_MENU_DETAIL_FONT_SIZE_SP.sp,
                    lineHeight = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            UnlocalizedText(
                text = localized("보기"),
                color = AqGold,
                fontSize = RANKING_ENTRY_MENU_DETAIL_FONT_SIZE_SP.sp,
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
    refreshPolicy: RankingRefreshPolicy,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().background(AqBackground)) {
        RankingTopBar(refreshPolicy = refreshPolicy, onBack = onBack)
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
private fun RankingTopBar(refreshPolicy: RankingRefreshPolicy, onBack: () -> Unit) {
    val language = LocalAppLanguage.current
    RankingPageTopBar(
        title = "모험가 랭킹",
        subtitle = rankingRefreshPeriodLabel(refreshPolicy, language),
        backContentDescription = adventurerRankingBackContentDescription(language),
        onBack = onBack,
    )
}

internal fun adventurerRankingBackContentDescription(language: AppLanguage): String = when (language) {
    AppLanguage.KOREAN -> "메인 화면으로 돌아가기"
    AppLanguage.ENGLISH -> "Back to Main"
    AppLanguage.JAPANESE -> "メイン画面に戻る"
}

@Composable
internal fun RankingPageTopBar(
    title: String,
    subtitle: String?,
    backContentDescription: String,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = localized(backContentDescription),
                tint = AqText,
                modifier = Modifier.size(26.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            UnlocalizedText(
                text = localized(title),
                modifier = Modifier.semantics { heading() },
                color = AqText,
                fontSize = RANKING_PAGE_TITLE_FONT_SIZE_SP.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Black,
            )
            if (subtitle != null) {
                UnlocalizedText(
                    text = localized(subtitle),
                    color = AqMuted,
                    fontSize = RANKING_PAGE_SUBTITLE_FONT_SIZE_SP.sp,
                    lineHeight = 16.sp,
                )
            }
        }
    }
}

@Composable
private fun RankingContent(snapshot: RankingSnapshot, warning: String? = null) {
    RankingListContent(
        entryKeys = snapshot.entries.map { it.characterId },
        currentPlayerKey = snapshot.myEntry.characterId,
        totalParticipants = snapshot.totalParticipants,
        warning = warning,
        personalCard = { MyRankingCard(snapshot.myEntry) },
    ) { index -> RankingListRow(snapshot.entries[index]) }
}

/** Shared ordering, quick navigation, spacing and heading for both public rankings. */
@Composable
internal fun RankingListContent(
    entryKeys: List<String>,
    currentPlayerKey: String?,
    totalParticipants: Int,
    warning: String? = null,
    personalCard: (@Composable () -> Unit)? = null,
    rowContent: @Composable (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val myRankingListIndex = remember(entryKeys, currentPlayerKey, personalCard != null) {
        entryKeys.indexOfFirst { it == currentPlayerKey }
            .takeIf { it >= 0 }
            ?.plus(if (personalCard != null) RANKING_LIST_ITEM_OFFSET else 1)
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
            if (personalCard != null) {
                item(key = "my-ranking-card") { personalCard() }
            }
            item(key = "ranking-heading") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, end = 4.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "전체 랭킹 · ${rankingNumber(totalParticipants.toLong())}명",
                        modifier = Modifier.semantics { heading() },
                        color = AqText,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            itemsIndexed(entryKeys, key = { _, key -> key }) { index, _ ->
                rowContent(index)
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
    val displayName = rankingDisplayName(entry)
    val rankingDescription = if (entry.level < 20L) {
        "랭킹 참가까지 레벨 20, 현재 레벨 ${entry.level}, $displayName"
    } else if (isOutsideDisplayedRanking) {
        "내 순위 ${rankingPositionLabel(entry.rank)}, $displayName, 호칭 ${entry.honorific}, 전투력 ${rankingNumber(entry.combatPower)}"
    } else if (entry.rank > 0) {
        "내 순위 ${entry.rank}위, $displayName, 호칭 ${entry.honorific}, 전투력 ${rankingNumber(entry.combatPower)}"
    } else {
        "내 순위 없음, $displayName, 전투력 ${rankingNumber(entry.combatPower)}"
    }
    val accessibility = localizedPreserving(
        rankingDescription,
        displayName,
    )
    RankingPersonalCard(
        displayName = displayName,
        rankLabel = when {
            isOutsideDisplayedRanking -> rankingPositionLabel(entry.rank)
            entry.rank > 0 -> "#${rankingNumber(entry.rank.toLong())}"
            else -> "—"
        },
        compactRank = isOutsideDisplayedRanking,
        accent = entry.honorific,
        detail = "${entry.heroClass.labelKo} · Lv.${rankingNumber(entry.level)} · 전투력 ${rankingNumber(entry.combatPower)}",
        accessibility = accessibility,
    )
}

@Composable
internal fun RankingPersonalCard(
    displayName: String,
    rankLabel: String,
    compactRank: Boolean,
    accent: String,
    detail: String,
    accessibility: String,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 104.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = accessibility
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
                    text = rankLabel,
                    color = AqText,
                    fontSize = if (compactRank) 14.sp else 29.sp,
                    lineHeight = 32.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                )
            }
            Box(Modifier.width(1.dp).height(58.dp).background(AqGoldSoft.copy(alpha = 0.65f)))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                UnlocalizedText(
                    text = displayName,
                    color = AqText,
                    fontSize = 16.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = accent,
                    color = AqGold,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = detail,
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
    val language = LocalAppLanguage.current
    val displayName = rankingDisplayName(entry)
    RankingPlayerRow(
        rank = entry.rank,
        displayName = displayName,
        detail = "${entry.heroClass.labelKo} · Lv.${rankingNumber(entry.level)} · ${entry.honorific}",
        metricLabel = "전투력",
        metricValue = rankingNumber(entry.combatPower),
        isMe = entry.isMe,
        accessibility = rankingListAccessibilityDescription(
            entry, displayName, localized(entry.heroClass.labelKo, language),
            localized(entry.honorific, language), language,
        ),
    )
}

@Composable
internal fun RankingPlayerRow(
    rank: Int,
    displayName: String,
    detail: String,
    metricLabel: String,
    metricValue: String,
    isMe: Boolean,
    accessibility: String,
) {
    val rankColor = when (rank) {
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
            .background(if (isMe) AqGold.copy(alpha = 0.09f) else AqSurface)
            .then(if (isMe) Modifier.border(1.dp, AqGoldSoft, shape) else Modifier)
            .semantics(mergeDescendants = true) {
                contentDescription = accessibility
            }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${rank}",
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
                    text = displayName,
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
                text = detail,
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
                text = metricLabel,
                color = AqMuted,
                fontSize = 9.sp,
                lineHeight = 11.sp,
                maxLines = 1,
            )
            Text(
                text = metricValue,
                color = if (isMe) AqGold else AqText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

internal fun rankingListAccessibilityDescription(
    entry: RankingEntry,
    displayName: String,
    localizedClass: String,
    localizedHonorific: String,
    language: AppLanguage,
): String = when (language) {
    AppLanguage.KOREAN -> buildString {
        append("${entry.rank}위, ")
        if (entry.isMe) append("나, ")
        append("$displayName, $localizedClass, 레벨 ${entry.level}, ")
        append("호칭 $localizedHonorific, 전투력 ${rankingNumber(entry.combatPower)}")
    }
    AppLanguage.ENGLISH -> buildString {
        append("Rank ${entry.rank}, ")
        if (entry.isMe) append("you, ")
        append("$displayName, $localizedClass, level ${entry.level}, ")
        append("title $localizedHonorific, power ${rankingNumber(entry.combatPower)}")
    }
    AppLanguage.JAPANESE -> buildString {
        append("${entry.rank}位、")
        if (entry.isMe) append("自分、")
        append("$displayName、$localizedClass、レベル${entry.level}、")
        append("称号$localizedHonorific、戦闘力${rankingNumber(entry.combatPower)}")
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

internal fun rankingNumber(value: Long): String =
    NumberFormat.getNumberInstance(Locale.KOREA).format(value)

internal fun rankingRefreshPeriodLabel(
    policy: RankingRefreshPolicy,
    language: AppLanguage,
): String {
    val hours = policy.intervalHours
    return when (language) {
        AppLanguage.KOREAN -> "갱신 주기 · ${hours}시간"
        AppLanguage.ENGLISH ->
            "Refresh interval · $hours ${if (hours == 1L) "hour" else "hours"}"
        AppLanguage.JAPANESE -> "更新間隔・${hours}時間"
    }
}
