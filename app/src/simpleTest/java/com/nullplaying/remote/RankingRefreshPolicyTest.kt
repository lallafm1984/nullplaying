package com.nullplaying.remote

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RankingRefreshPolicyTest {
    @Test
    fun `remote ranking refresh defaults to one hour and rejects unsafe values`() {
        assertEquals(1L, RankingRefreshPolicy().intervalHours)
        assertEquals(3_600_000L, RankingRefreshPolicy().intervalMillis)
        assertEquals(RankingRefreshPolicy(1L), RankingRefreshPolicy.parse(" 1 "))
        assertEquals(RankingRefreshPolicy(8L), RankingRefreshPolicy.parse(" 8 "))
        assertEquals(RankingRefreshPolicy(24L), RankingRefreshPolicy.parse("24"))

        listOf("", "0", "25", "1.5", "NaN", "999999999999999999999").forEach {
            assertNull(it, RankingRefreshPolicy.parse(it))
        }
    }

    @Test
    fun `client policy can only delay the authoritative server boundary`() {
        val eightHours = RankingRefreshPolicy(8L)
        val settledAt = 1_000L

        assertEquals(
            settledAt + eightHours.intervalMillis,
            earliestRankingRefreshAt(
                settledAtEpochMillis = settledAt,
                serverNextSettlementAtEpochMillis = settledAt + 60_000L,
                policy = eightHours,
            ),
        )
        assertEquals(
            settledAt + 12L * 60L * 60L * 1_000L,
            earliestRankingRefreshAt(
                settledAtEpochMillis = settledAt,
                serverNextSettlementAtEpochMillis = settledAt + 12L * 60L * 60L * 1_000L,
                policy = eightHours,
            ),
        )
    }

    @Test
    fun `refresh boundary addition saturates on corrupt persisted time`() {
        assertEquals(
            Long.MAX_VALUE,
            earliestRankingRefreshAt(
                settledAtEpochMillis = Long.MAX_VALUE - 1L,
                serverNextSettlementAtEpochMillis = 1L,
                policy = RankingRefreshPolicy(),
            ),
        )
    }

    @Test
    fun `shared trusted clock wins over edited wall time after process restart`() {
        val trustedNow = 8_000L

        assertEquals(
            trustedNow,
            resolveRankingNowEpochMillis(
                processClock = null,
                elapsedRealtimeMillis = 10L,
                trustedGameEpochMillis = trustedNow,
                wallClockEpochMillis = Long.MAX_VALUE,
                savedServerOffsetMillis = Long.MAX_VALUE,
            ),
        )
        assertEquals(
            trustedNow,
            resolveRankingNowEpochMillis(
                processClock = null,
                elapsedRealtimeMillis = 10L,
                trustedGameEpochMillis = trustedNow,
                wallClockEpochMillis = 0L,
                savedServerOffsetMillis = Long.MIN_VALUE,
            ),
        )
    }

    @Test
    fun `ranking clock and wall fallback saturate instead of wrapping`() {
        assertEquals(
            Long.MAX_VALUE,
            DailyRankingClock(
                serverNowEpochMillis = Long.MAX_VALUE - 1L,
                observedElapsedRealtimeMillis = 1L,
            ).now(10L),
        )
        assertEquals(
            Long.MAX_VALUE,
            resolveRankingNowEpochMillis(
                processClock = null,
                elapsedRealtimeMillis = 0L,
                trustedGameEpochMillis = null,
                wallClockEpochMillis = Long.MAX_VALUE - 1L,
                savedServerOffsetMillis = 10L,
            ),
        )
        assertEquals(Long.MIN_VALUE, saturatingSubtractRankingTime(Long.MIN_VALUE, 1L))
        assertEquals(Long.MAX_VALUE, saturatingSubtractRankingTime(0L, Long.MIN_VALUE))
    }

    @Test
    fun `production keeps arena wait subscribed to the same live policy used by both fetch gates`() {
        val application = projectFile(
            "app/src/simple/java/com/nullplaying/AlarmQuestApplication.kt",
        ).readText()
        val remoteConfig = projectFile(
            "app/src/simple/java/com/nullplaying/remote/OfflineAdventureRemoteConfig.kt",
        ).readText()
        val service = projectFile(
            "app/src/simple/java/com/nullplaying/remote/SupabaseGameService.kt",
        ).readText()
        val screen = projectFile(
            "app/src/simple/java/com/nullplaying/ui/AlarmQuestApp.kt",
        ).readText()
        val rankingPanel = projectFile(
            "app/src/simple/java/com/nullplaying/ui/RankingPanel.kt",
        ).readText()
        val battlePanel = projectFile(
            "app/src/simple/java/com/nullplaying/ui/BattlePanel.kt",
        ).readText()

        assertTrue(application.contains(
            "rankingRefreshPolicy = offlineAdventureRemoteConfig.rankingRefreshPolicy",
        ))
        assertTrue(remoteConfig.contains(
            "RankingRefreshPolicy.INTERVAL_HOURS_KEY to RankingRefreshPolicy.DEFAULT_INTERVAL_HOURS",
        ))
        assertTrue(service.contains(
            "isDailyRankingCacheReusable(cachedSnapshot, serverNow, forceRefresh, rankingRefreshPolicy.value)",
        ))
        assertTrue(service.contains(
            "isDailyArenaRankingCacheReusable(cachedSnapshot, serverNow, rankingRefreshPolicy.value)",
        ))
        assertTrue(screen.contains(
            "val rankingRefreshPolicy by supabaseGameService.rankingRefreshPolicy.collectAsState()",
        ))
        assertEquals(
            2,
            Regex("refreshPolicy = rankingRefreshPolicy").findAll(screen).count(),
        )
        assertTrue(rankingPanel.contains(
            "subtitle = rankingRefreshPeriodLabel(refreshPolicy, language)",
        ))
        assertTrue(battlePanel.contains(
            "subtitle = rankingRefreshPeriodLabel(refreshPolicy, language)",
        ))
        val arenaWaitEffect = screen.substringAfter(
            "remoteArenaRanking?.nextCheckAtEpochMillis,",
        ).substringBefore(") {")
        assertTrue(arenaWaitEffect.contains("rankingRefreshPolicy"))
    }

    private fun projectFile(relativePath: String): File {
        val workingDirectory = requireNotNull(System.getProperty("user.dir"))
        val roots = generateSequence(File(workingDirectory).absoluteFile) { it.parentFile }
        val root = roots.firstOrNull { File(it, "app/build.gradle.kts").isFile }
            ?: error("Could not locate project root")
        return File(root, relativePath)
    }
}
