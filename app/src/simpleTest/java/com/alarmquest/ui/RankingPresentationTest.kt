package com.alarmquest.ui

import com.alarmquest.model.HeroClass
import com.alarmquest.remote.RemoteRankingEntry
import com.alarmquest.remote.RemoteRankingSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RankingPresentationTest {
    @Test
    fun `honorifics change only at the approved rank boundaries`() {
        assertEquals("순위 집계 중", honorificForRank(null))
        assertEquals("순위 집계 중", honorificForRank(0))
        assertEquals("유일한 왕좌", honorificForRank(1))
        assertEquals("왕좌에 닿은 자", honorificForRank(2))
        assertEquals("천상의 수호자", honorificForRank(3))
        assertEquals("전설의 선봉", honorificForRank(4))
        assertEquals("별을 베는 자", honorificForRank(5))
        assertEquals("불굴의 정복자", honorificForRank(6))
        assertEquals("황금의 개척자", honorificForRank(7))
        assertEquals("새벽의 추적자", honorificForRank(8))
        assertEquals("은빛의 영웅", honorificForRank(9))
        assertEquals("별빛의 계승자", honorificForRank(10))
        assertEquals("황금의 선구자", honorificForRank(11))
        assertEquals("황금의 선구자", honorificForRank(25))
        assertEquals("은빛 추적자", honorificForRank(50))
        assertEquals("청동 길잡이", honorificForRank(51))
        assertEquals("청동 길잡이", honorificForRank(100))
        assertEquals("별빛의 도전자", honorificForRank(101))
        assertEquals("별빛의 도전자", honorificForRank(300))
        assertEquals("새벽의 모험가", honorificForRank(1_000))
        assertEquals("여정을 걷는 자", honorificForRank(1_001))
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
        assertEquals(listOf("유일한 왕좌", "왕좌에 닿은 자", "왕좌에 닿은 자", "전설의 선봉"), ranked.map { it.honorific })
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
    fun `hero header labels a cached rank beyond one thousand as outside`() {
        val cached = RankingSnapshot(
            snapshotId = "cached",
            source = RankingSnapshotSource.CACHE,
            fetchedAtEpochMillis = 1_787_000_000_000L,
            formulaVersion = 1,
            totalParticipants = 1_284,
            entries = emptyList(),
            myEntry = candidate("me", 1_130L, 85L)
                .copy(isMe = true)
                .let { rankCandidates(listOf(it)).single() }
                .copy(
                    rank = 1_284,
                    honorific = honorificForRank(1_284),
                ),
        )

        val presentation = rankingHeaderPresentation(
            RankingUiState.Error("새 순위를 불러오지 못함", cachedSnapshot = cached),
        )

        assertEquals("전체 1,000위 밖", presentation.visualLabel)
        assertEquals("전체 순위 1,000위 밖", presentation.accessibilityLabel)
        assertTrue(presentation.isRanked)
    }

    @Test
    fun `every owned character keeps its global placement while only the active one is mine`() {
        val remoteEntries = listOf(
            remoteEntry("character-a", rank = 1, listIndex = 0, combatPower = 300L, isMe = true),
            remoteEntry("character-b", rank = 2, listIndex = 1, combatPower = 200L, isMe = false),
            remoteEntry("character-c", rank = 3, listIndex = 2, combatPower = 100L, isMe = false),
        )
        val remoteSnapshot = RemoteRankingSnapshot(
            requestedCharacterId = "character-a",
            fetchedAtEpochMillis = 100L,
            totalParticipants = 3,
            entries = remoteEntries,
            myEntry = remoteEntries.first(),
        )

        listOf(
            Triple("character-a", "첫째", HeroClass.WARRIOR),
            Triple("character-b", "둘째", HeroClass.ROGUE),
            Triple("character-c", "셋째", HeroClass.MAGE),
        ).forEachIndexed { index, (characterId, name, heroClass) ->
            val snapshot = remoteSnapshot.toUiSnapshot(
                playerCharacterId = characterId,
                playerName = name,
                playerClass = heroClass,
                playerLevel = 20L,
                playerCombatPower = remoteEntries[index].combatPower,
            )

            assertEquals(3, snapshot.entries.size)
            assertEquals(characterId, snapshot.myEntry.characterId)
            assertEquals(1, snapshot.entries.count { it.isMe })
            assertTrue(snapshot.entries.single { it.characterId == characterId }.isMe)
            assertTrue(snapshot.entries.filterNot { it.characterId == characterId }.none { it.isMe })
        }
    }

    @Test
    fun `another character's out-of-list my entry never leaks into the active character`() {
        val previousCharacter = remoteEntry(
            "character-a",
            rank = 5_000,
            listIndex = 4_999,
            combatPower = 100L,
            isMe = true,
        )

        val snapshot = RemoteRankingSnapshot(
            requestedCharacterId = "character-a",
            fetchedAtEpochMillis = 100L,
            totalParticipants = 5_000,
            entries = listOf(
                remoteEntry("first", rank = 1, listIndex = 0, combatPower = 300L, isMe = false),
            ),
            myEntry = previousCharacter,
        ).toUiSnapshot(
            playerCharacterId = "character-b",
            playerName = "둘째",
            playerClass = HeroClass.MAGE,
            playerLevel = 20L,
            playerCombatPower = 200L,
        )

        assertEquals("character-b", snapshot.myEntry.characterId)
        assertEquals("둘째", snapshot.myEntry.displayName)
        assertEquals(HeroClass.MAGE, snapshot.myEntry.heroClass)
        assertFalse(snapshot.entries.any { it.isMe })
    }

    @Test
    fun `local combat power reranks the downloaded server snapshot`() {
        val remoteEntries = listOf(
            remoteEntry("first", rank = 1, listIndex = 0, combatPower = 300L, isMe = false),
            remoteEntry("me", rank = 2, listIndex = 1, combatPower = 100L, isMe = true),
            remoteEntry("third", rank = 3, listIndex = 2, combatPower = 90L, isMe = false),
        )
        val snapshot = RemoteRankingSnapshot(
            requestedCharacterId = "me",
            fetchedAtEpochMillis = 100L,
            totalParticipants = 3,
            entries = remoteEntries,
            myEntry = remoteEntries.single { it.isMe },
        ).toUiSnapshot(
            playerCharacterId = "me",
            playerName = "local-me",
            playerClass = HeroClass.WARRIOR,
            playerLevel = 20L,
            playerCombatPower = 400L,
        ).withLocalPlayerPower(
            characterId = "me",
            displayName = "local-me",
            heroClass = HeroClass.WARRIOR,
            level = 20L,
            combatPower = 400L,
            now = 200L,
        )

        assertEquals(1, snapshot.myEntry.rank)
        assertEquals(400L, snapshot.myEntry.combatPower)
        assertEquals(listOf("me", "first", "third"), snapshot.entries.map { it.characterId })
        assertTrue(snapshot.usesLocalPower)
    }

    @Test
    fun `a local character below level twenty is removed from the ranking`() {
        val snapshot = RemoteRankingSnapshot(
            requestedCharacterId = "me",
            fetchedAtEpochMillis = 100L,
            totalParticipants = 0,
            entries = emptyList(),
            myEntry = null,
        ).toUiSnapshot(
            playerCharacterId = "me",
            playerName = "me",
            playerClass = HeroClass.WARRIOR,
            playerLevel = 19L,
            playerCombatPower = 100L,
        ).withLocalPlayerPower(
            characterId = "me",
            displayName = "me",
            heroClass = HeroClass.WARRIOR,
            level = 19L,
            combatPower = 100L,
            now = 200L,
        )

        assertEquals(0, snapshot.myEntry.rank)
        assertEquals("Lv.20부터 참가", snapshot.myEntry.honorific)
        assertEquals("Lv.20부터 참가", rankingEntryMenuDetail(snapshot.myEntry))
        assertTrue(snapshot.entries.none { it.characterId == "me" })
        assertTrue(snapshot.usesLocalPower)
    }

    @Test
    fun `an eligible local character missing from the server receives a normal rank`() {
        val remoteEntries = listOf(
            remoteEntry("first", rank = 1, listIndex = 0, combatPower = 300L, isMe = false),
            remoteEntry("third", rank = 2, listIndex = 1, combatPower = 90L, isMe = false),
        )
        val snapshot = RemoteRankingSnapshot(
            requestedCharacterId = "me",
            fetchedAtEpochMillis = 100L,
            totalParticipants = 2,
            entries = remoteEntries,
            myEntry = null,
        ).toUiSnapshot(
            playerCharacterId = "me",
            playerName = "me",
            playerClass = HeroClass.WARRIOR,
            playerLevel = 20L,
            playerCombatPower = 100L,
        ).withLocalPlayerPower(
            characterId = "me",
            displayName = "me",
            heroClass = HeroClass.WARRIOR,
            level = 20L,
            combatPower = 100L,
            now = 200L,
        )

        assertEquals(2, snapshot.myEntry.rank)
        assertEquals(2, snapshot.totalParticipants)
        assertEquals(listOf("first", "me", "third"), snapshot.entries.map { it.characterId })
        assertTrue(snapshot.usesLocalPower)
    }

    @Test
    fun `a local character below the top one thousand is shown only as outside`() {
        val topEntries = (1..MAX_DISPLAYED_RANK).map { rank ->
            remoteEntry(
                characterId = "rank-$rank",
                rank = rank,
                listIndex = rank - 1,
                combatPower = 2_001L - rank,
                isMe = false,
            )
        }
        val serverMine = remoteEntry(
            characterId = "me",
            rank = 5_000,
            listIndex = 4_999,
            combatPower = 100L,
            isMe = true,
        )
        val snapshot = RemoteRankingSnapshot(
            requestedCharacterId = "me",
            fetchedAtEpochMillis = 100L,
            totalParticipants = 5_000,
            entries = topEntries,
            myEntry = serverMine,
        ).toUiSnapshot(
            playerCharacterId = "me",
            playerName = "me",
            playerClass = HeroClass.WARRIOR,
            playerLevel = 20L,
            playerCombatPower = 500L,
        ).withLocalPlayerPower(
            characterId = "me",
            displayName = "me",
            heroClass = HeroClass.WARRIOR,
            level = 20L,
            combatPower = 500L,
            now = 200L,
        )

        assertEquals(OUTSIDE_DISPLAYED_RANK, snapshot.myEntry.rank)
        assertEquals(-1, snapshot.myEntry.listIndex)
        assertEquals("1,000위 밖", rankingPositionLabel(snapshot.myEntry.rank))
        assertEquals("1,000위 밖 · 여정을 걷는 자", rankingEntryMenuDetail(snapshot.myEntry))
        assertTrue(snapshot.entries.none { it.characterId == "me" })
    }

    @Test
    fun `a local character entering the top one thousand receives a snapshot rank`() {
        val topEntries = (1..MAX_DISPLAYED_RANK).map { rank ->
            remoteEntry(
                characterId = "rank-$rank",
                rank = rank,
                listIndex = rank - 1,
                combatPower = 2_001L - rank,
                isMe = false,
            )
        }
        val snapshot = RemoteRankingSnapshot(
            requestedCharacterId = "me",
            fetchedAtEpochMillis = 100L,
            totalParticipants = 5_000,
            entries = topEntries,
            myEntry = remoteEntry("me", rank = 5_000, listIndex = 4_999, combatPower = 100L, isMe = true),
        ).toUiSnapshot(
            playerCharacterId = "me",
            playerName = "me",
            playerClass = HeroClass.WARRIOR,
            playerLevel = 20L,
            playerCombatPower = 1_500L,
        ).withLocalPlayerPower(
            characterId = "me",
            displayName = "me",
            heroClass = HeroClass.WARRIOR,
            level = 20L,
            combatPower = 1_500L,
            now = 200L,
        )

        assertEquals(501, snapshot.myEntry.rank)
        assertTrue(snapshot.entries.any { it.characterId == "me" })
        assertEquals("501위", rankingPositionLabel(snapshot.myEntry.rank))
    }

    @Test
    fun `ranking header labels an outside character without inventing an exact rank`() {
        val entry = candidate("me", 100L, 20L).copy(isMe = true)
            .let { rankCandidates(listOf(it)).single() }
            .copy(rank = OUTSIDE_DISPLAYED_RANK, honorific = honorificForRank(OUTSIDE_DISPLAYED_RANK))
        val state = RankingUiState.Content(
            RankingSnapshot(
                snapshotId = "outside",
                source = RankingSnapshotSource.CACHE,
                fetchedAtEpochMillis = 100L,
                formulaVersion = 1,
                totalParticipants = 5_000,
                entries = emptyList(),
                myEntry = entry,
                usesLocalPower = true,
            ),
        )

        val presentation = rankingHeaderPresentation(state)

        assertEquals("전체 1,000위 밖", presentation.visualLabel)
        assertEquals("전체 순위 1,000위 밖", presentation.accessibilityLabel)
        assertTrue(presentation.isRanked)
    }

    @Test
    fun `ranking menu omits zero rank but keeps positive ranks`() {
        val ranked = candidate("me", 100L, 20L).copy(isMe = true)
            .let { rankCandidates(listOf(it)).single() }

        assertEquals("순위 미집계", rankingEntryMenuDetail(null))
        assertEquals("1위 · ${ranked.honorific}", rankingEntryMenuDetail(ranked))
        assertEquals(
            "Lv.20부터 참가",
            rankingEntryMenuDetail(ranked.copy(rank = 0, honorific = "Lv.20부터 참가")),
        )
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

    private fun remoteEntry(
        characterId: String,
        rank: Int,
        listIndex: Int,
        combatPower: Long,
        isMe: Boolean,
    ) = RemoteRankingEntry(
        rank = rank,
        listIndex = listIndex,
        characterId = characterId,
        displayName = characterId,
        heroClass = HeroClass.WARRIOR,
        level = 20L,
        combatPower = combatPower,
        achievedAtEpochMillis = 10L + listIndex,
        updatedAtEpochMillis = 100L,
        isMe = isMe,
    )
}
