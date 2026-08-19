package com.alarmquest.ui

import com.alarmquest.model.HeroClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RankingPresentationTest {
    @Test
    fun `honorifics change only at the approved rank boundaries`() {
        assertEquals("순위 집계 중", honorificForRank(null))
        assertEquals("순위 집계 중", honorificForRank(0))
        assertEquals("왕좌의 모험가", honorificForRank(1))
        assertEquals("전설의 선봉", honorificForRank(2))
        assertEquals("전설의 선봉", honorificForRank(3))
        assertEquals("황금 개척자", honorificForRank(4))
        assertEquals("황금 개척자", honorificForRank(10))
        assertEquals("은빛 추적자", honorificForRank(11))
        assertEquals("은빛 추적자", honorificForRank(50))
        assertEquals("청동 길잡이", honorificForRank(51))
        assertEquals("청동 길잡이", honorificForRank(100))
        assertEquals("여정의 도전자", honorificForRank(101))
    }

    @Test
    fun `ties use competition ranks and stable achieved time ordering`() {
        val candidates = listOf(
            candidate("d", score = 90L, achievedAt = 10L),
            candidate("c", score = 120L, achievedAt = 30L),
            candidate("b", score = 120L, achievedAt = 20L),
            candidate("a", score = 150L, achievedAt = 40L),
        )

        val ranked = rankCandidates(candidates)

        assertEquals(listOf("a", "b", "c", "d"), ranked.map { it.characterId })
        assertEquals(listOf(1, 2, 2, 4), ranked.map { it.rank })
        assertEquals(listOf("왕좌의 모험가", "전설의 선봉", "전설의 선봉", "황금 개척자"), ranked.map { it.honorific })
    }

    @Test
    fun `preview snapshot keeps the real player combat power and marks sample data`() {
        val state = initialRankingUiState(
            showPreviewData = true,
            playerName = "QA",
            playerClass = HeroClass.WARRIOR,
            playerLevel = 85L,
            playerCombatPower = 1_012L,
            fetchedAtEpochMillis = 1_787_000_000_000L,
        )

        assertTrue(state is RankingUiState.Content)
        val snapshot = (state as RankingUiState.Content).snapshot
        assertEquals(RankingSnapshotSource.MOCK, snapshot.source)
        assertEquals(0, snapshot.formulaVersion)
        assertEquals(1_012L, snapshot.myEntry.combatPower)
        assertEquals("QA", snapshot.myEntry.displayName)
        assertEquals(1, snapshot.entries.count { it.isMe })
        assertEquals(snapshot.myEntry.honorific, honorificForRank(snapshot.myEntry.rank))
        assertEquals(snapshot.entries.size, snapshot.entries.map { it.characterId }.distinct().size)
    }

    @Test
    fun `release state never presents preview adventurers as real users`() {
        val state = initialRankingUiState(
            showPreviewData = false,
            playerName = "QA",
            playerClass = HeroClass.WARRIOR,
            playerLevel = 85L,
            playerCombatPower = 1_012L,
            fetchedAtEpochMillis = 1_787_000_000_000L,
        )

        assertEquals(RankingUiState.Empty("아직 집계된 순위가 없습니다"), state)
    }

    @Test
    fun `hero header presents the preview player's actual overall rank`() {
        val state = initialRankingUiState(
            showPreviewData = true,
            playerName = "QA",
            playerClass = HeroClass.WARRIOR,
            playerLevel = 85L,
            playerCombatPower = 1_130L,
            fetchedAtEpochMillis = 1_787_000_000_000L,
        )

        val presentation = rankingHeaderPresentation(state)

        assertEquals("전체 8위", presentation.visualLabel)
        assertEquals("전체 순위 8위", presentation.accessibilityLabel)
        assertTrue(presentation.isRanked)
    }

    @Test
    fun `hero header distinguishes loading from an unranked state`() {
        val loading = rankingHeaderPresentation(RankingUiState.Loading)
        val empty = rankingHeaderPresentation(RankingUiState.Empty("아직 집계된 순위가 없습니다"))
        val error = rankingHeaderPresentation(RankingUiState.Error("네트워크 오류"))

        assertEquals("순위 집계 중", loading.visualLabel)
        assertEquals("순위 미집계", empty.visualLabel)
        assertEquals("순위 미집계", error.visualLabel)
        assertFalse(loading.isRanked)
        assertFalse(empty.isRanked)
        assertFalse(error.isRanked)
    }

    @Test
    fun `hero header keeps and formats the cached player rank`() {
        val content = initialRankingUiState(
            showPreviewData = true,
            playerName = "QA",
            playerClass = HeroClass.WARRIOR,
            playerLevel = 85L,
            playerCombatPower = 1_130L,
            fetchedAtEpochMillis = 1_787_000_000_000L,
        ) as RankingUiState.Content
        val cached = content.snapshot.copy(
            myEntry = content.snapshot.myEntry.copy(rank = 1_284),
        )

        val presentation = rankingHeaderPresentation(
            RankingUiState.Error("새 순위를 불러오지 못함", cachedSnapshot = cached),
        )

        assertEquals("전체 1,284위", presentation.visualLabel)
        assertEquals("전체 순위 1,284위", presentation.accessibilityLabel)
        assertTrue(presentation.isRanked)
    }

    private fun candidate(
        characterId: String,
        score: Long,
        achievedAt: Long,
    ): RankingCandidate = RankingCandidate(
        characterId = characterId,
        displayName = characterId,
        heroClass = HeroClass.WARRIOR,
        level = 1L,
        score = score,
        achievedAtEpochMillis = achievedAt,
        verifiedAtEpochMillis = 100L,
    )
}
