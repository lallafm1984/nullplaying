package com.nullplaying.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.nullplaying.BuildConfig
import com.nullplaying.localization.AppLanguage
import com.nullplaying.localization.GameLocalization
import com.nullplaying.model.HeroClass
import com.nullplaying.remote.ArenaRankingLocalStanding
import com.nullplaying.remote.RankingRefreshPolicy
import com.nullplaying.remote.RemoteArenaRankingEntry
import com.nullplaying.remote.RemoteArenaRankingSnapshot
import com.nullplaying.remote.RemoteRankingEntry
import com.nullplaying.remote.RemoteRankingSnapshot
import java.time.Instant
import java.util.TimeZone

/** Local fixtures only. This activity and its exported entry point exist only in offlineQa. */
class DailyRankingQaActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(BuildConfig.DEBUG && !BuildConfig.REMOTE_SERVICES_ENABLED)
        check(BuildConfig.SUPABASE_URL.isBlank() && BuildConfig.SUPABASE_PUBLISHABLE_KEY.isBlank())
        val language = when (intent.getStringExtra("qa_language")) {
            "en" -> AppLanguage.ENGLISH
            "ja" -> AppLanguage.JAPANESE
            else -> AppLanguage.KOREAN
        }
        TimeZone.setDefault(TimeZone.getTimeZone(intent.getStringExtra("qa_zone") ?: "Asia/Seoul"))
        GameLocalization.setActiveLanguage(language)
        val cutoff = Instant.parse("2026-09-05T00:00:00Z").toEpochMilli()
        val arenaContent = intent.getStringExtra("qa_kind") == "arena_content"
        if (intent.getStringExtra("qa_kind") == "arena_empty" || arenaContent) {
            val characterId = "10000000-0000-4000-8000-000000000001"
            val arenaCutoff = Instant.parse("2026-09-08T16:00:00Z").toEpochMilli()
            val nextSettlement = Instant.parse("2026-09-08T17:00:00Z").toEpochMilli()
            val count = intent.getIntExtra("qa_count", 5).coerceIn(1, 50)
            val myIndex = minOf(if (count > 10) 26 else 1, count - 1)
            val name = when (language) {
                AppLanguage.KOREAN -> "여행자의긴이름별"
                AppLanguage.JAPANESE -> "星降る旅の冒険者"
                AppLanguage.ENGLISH -> "Starlight"
            }
            val rankingEntries = if (arenaContent) (0 until count).map { index ->
                RemoteArenaRankingEntry(
                    rank = index + 1, listIndex = index,
                    accountId = "20000000-0000-4000-8000-%012d".format(index),
                    characterId = if (index == myIndex) characterId else "30000000-0000-4000-8000-%012d".format(index),
                    displayName = if (index == myIndex) name else "Aster ${index + 1}",
                    heroClass = HeroClass.WARRIOR, level = 21L, score = 2000 - index * 20,
                    completedBattles = 10, wins = 8, losses = 2, draws = 0,
                    achievedAtEpochMillis = arenaCutoff - 3_600_000L, isMe = index == myIndex,
                )
            } else emptyList()
            val standing = ArenaRankingLocalStanding(
                characterId = characterId,
                displayName = name,
                heroClass = HeroClass.WARRIOR,
                level = 30L,
                score = if (arenaContent) 2000 - myIndex * 20 else 1_480,
                completedBattles = 10,
                wins = 8,
                losses = 2,
                draws = 0,
                observedAtEpochMillis = arenaCutoff - 3_600_000L,
            )
            val snapshot = RemoteArenaRankingSnapshot(
                requestedCharacterId = characterId,
                fetchedAtEpochMillis = arenaCutoff + 60_000L,
                totalParticipants = rankingEntries.size,
                entries = rankingEntries,
                ownEntries = rankingEntries.filter { it.isMe },
                snapshotId = "arena-bootstrap-qa",
                seasonId = "1",
                settledAtEpochMillis = arenaCutoff,
                nextSettlementAtEpochMillis = nextSettlement,
                generatedAtEpochMillis = arenaCutoff + 1_000L,
                rulesVersion = 1,
                serverTimeOffsetMillis = 0L,
                isBootstrap = !arenaContent,
            )
            setContent {
                CompositionLocalProvider(LocalAppLanguage provides language) {
                    AlarmQuestTheme {
                        BattleRankingScreen(
                            localStanding = standing,
                            remoteSnapshot = snapshot,
                            errorMessage = null,
                            refreshPolicy = RankingRefreshPolicy(),
                            modifier = Modifier.fillMaxSize().systemBarsPadding(),
                            onBack = { finish() },
                            onRetry = {},
                        )
                    }
                }
            }
            return
        }
        val pending = intent.getBooleanExtra("qa_pending", false)
        val entries = listOf("Aster", "Mira", "Luna", "Rowan", "Sol").mapIndexed { index, name ->
            RemoteRankingEntry(
                rank = index + 1, listIndex = index, characterId = "local-qa-$index",
                displayName = name, heroClass = HeroClass.WARRIOR, level = 30L,
                combatPower = 300L - index * 20L, achievedAtEpochMillis = cutoff - 3_600_000L,
                updatedAtEpochMillis = cutoff - 3_600_000L, isMe = !pending && index == 1,
            )
        }
        val myId = if (pending) "local-qa-new" else "local-qa-1"
        val remote = RemoteRankingSnapshot(
            requestedCharacterId = myId, fetchedAtEpochMillis = cutoff + 7_200_000L,
            totalParticipants = entries.size, entries = entries,
            myEntry = entries.firstOrNull { it.isMe }, ownEntries = entries.filter { it.isMe },
            snapshotId = "2026-09-05", settledAtEpochMillis = cutoff,
            nextSettlementAtEpochMillis = cutoff + 86_400_000L,
            generatedAtEpochMillis = cutoff + 1_000L,
        )
        // A much stronger local hero must still show the settled #2 / 280 score.
        val snapshot = remote.toUiSnapshot(myId, "Mira", HeroClass.WARRIOR, 40L, 480L)
        val state = if (intent.getBooleanExtra("qa_cached_error", false)) {
            RankingUiState.Error("Offline QA", snapshot)
        } else RankingUiState.Content(snapshot)
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides language) {
                AlarmQuestTheme {
                    RankingScreen(
                        uiState = state,
                        refreshPolicy = RankingRefreshPolicy(),
                        onBack = { finish() },
                        onRetry = {},
                        modifier = Modifier.fillMaxSize().systemBarsPadding(),
                    )
                }
            }
        }
    }
}
