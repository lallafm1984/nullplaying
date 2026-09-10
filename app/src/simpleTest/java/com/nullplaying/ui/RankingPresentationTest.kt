package com.nullplaying.ui

import com.nullplaying.localization.AppLanguage
import com.nullplaying.model.HeroClass
import com.nullplaying.remote.RankingRefreshPolicy
import com.nullplaying.remote.RemoteRankingEntry
import com.nullplaying.remote.RemoteRankingSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RankingPresentationTest {
    @Test
    fun `legacy cached gatekeepers are removed and real player ranks close continuously`() {
        val gatekeeper = remoteEntry(
            "gate-1", rank = 1, listIndex = 0, combatPower = 500L, isMe = false,
        ).copy(systemEntryCode = "RANK_GATE_01")
        val firstPlayer = remoteEntry(
            "player-a", rank = 2, listIndex = 1, combatPower = 400L, isMe = false,
        )
        val secondPlayer = remoteEntry(
            "player-b", rank = 3, listIndex = 2, combatPower = 300L, isMe = true,
        )

        val snapshot = RemoteRankingSnapshot(
            requestedCharacterId = "player-b",
            fetchedAtEpochMillis = 100L,
            totalParticipants = 3,
            entries = listOf(gatekeeper, firstPlayer, secondPlayer),
            myEntry = secondPlayer,
        ).toUiSnapshot(
            playerCharacterId = "player-b",
            playerName = "player-b",
            playerClass = HeroClass.WARRIOR,
            playerLevel = 20L,
            playerCombatPower = 300L,
        )

        assertEquals(listOf("player-a", "player-b"), snapshot.entries.map { it.characterId })
        assertEquals(listOf(1, 2), snapshot.entries.map { it.rank })
        assertEquals(listOf(0, 1), snapshot.entries.map { it.listIndex })
        assertEquals(2, snapshot.totalParticipants)
        assertEquals(2, snapshot.myEntry.rank)
        assertEquals(1, snapshot.myEntry.listIndex)
        assertTrue(snapshot.entries.none { it.systemEntryCode != null })
    }

    @Test
    fun `local reranking defensively ignores a legacy gatekeeper`() {
        val gatekeeper = RankingEntry(
            rank = 1,
            listIndex = 0,
            characterId = "gate-1",
            displayName = "Gatekeeper I",
            heroClass = HeroClass.WARRIOR,
            level = 20L,
            combatPower = 500L,
            honorific = honorificForRank(1),
            achievedAtEpochMillis = 10L,
            verifiedAtEpochMillis = 100L,
            isMe = false,
            systemEntryCode = "RANK_GATE_01",
        )
        val player = candidate("player-a", score = 400L, achievedAt = 20L)
            .let { rankCandidates(listOf(it)).single() }
        val local = candidate("me", score = 300L, achievedAt = 30L)
            .copy(isMe = true)
            .let { rankCandidates(listOf(it)).single() }
        val snapshot = RankingSnapshot(
            snapshotId = "legacy",
            source = RankingSnapshotSource.CACHE,
            fetchedAtEpochMillis = 100L,
            formulaVersion = 1,
            totalParticipants = 3,
            entries = listOf(gatekeeper, player),
            myEntry = local.copy(rank = 3, listIndex = 2),
        ).withLocalPlayerPower(
            characterId = "me",
            displayName = "me",
            heroClass = HeroClass.WARRIOR,
            level = 20L,
            combatPower = 450L,
            now = 200L,
        )

        assertEquals(listOf("me", "player-a"), snapshot.entries.map { it.characterId })
        assertEquals(listOf(1, 2), snapshot.entries.map { it.rank })
        assertEquals(2, snapshot.totalParticipants)
        assertTrue(snapshot.entries.none { it.systemEntryCode != null })
    }

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
    fun `ranking row accessibility uses the selected language throughout`() {
        val entry = RankingEntry(
            rank = 1,
            characterId = "gate-20",
            displayName = "Gatekeeper XX",
            heroClass = HeroClass.ROGUE,
            level = 40L,
            combatPower = 154L,
            honorific = "유일한 왕좌",
            achievedAtEpochMillis = 0L,
            verifiedAtEpochMillis = 0L,
            isMe = false,
        )

        assertEquals(
            "Rank 1, Gatekeeper XX, Thief, level 40, title Sole Sovereign, power 154",
            rankingListAccessibilityDescription(
                entry,
                displayName = "Gatekeeper XX",
                localizedClass = "Thief",
                localizedHonorific = "Sole Sovereign",
                language = AppLanguage.ENGLISH,
            ),
        )
        assertEquals(
            "1位、Gatekeeper XX、盗賊、レベル40、称号唯一の覇者、戦闘力154",
            rankingListAccessibilityDescription(
                entry,
                displayName = "Gatekeeper XX",
                localizedClass = "盗賊",
                localizedHonorific = "唯一の覇者",
                language = AppLanguage.JAPANESE,
            ),
        )
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
    fun `authenticated own row keeps the original name when the public row is moderated`() {
        val publicRow = remoteEntry(
            "character-a",
            rank = 5,
            listIndex = 4,
            combatPower = 200L,
            isMe = true,
        ).copy(displayName = "Clean Adventurer")
        val privateOwnRow = publicRow.copy(displayName = "My Original Name")

        val snapshot = RemoteRankingSnapshot(
            requestedCharacterId = "character-a",
            fetchedAtEpochMillis = 100L,
            totalParticipants = 10,
            entries = listOf(publicRow),
            myEntry = publicRow,
            ownEntries = listOf(privateOwnRow),
        ).toUiSnapshot(
            playerCharacterId = "character-a",
            playerName = "My Original Name",
            playerClass = HeroClass.WARRIOR,
            playerLevel = 20L,
            playerCombatPower = 200L,
        )

        assertEquals("Clean Adventurer", snapshot.entries.single().displayName)
        assertEquals("My Original Name", snapshot.myEntry.displayName)
        assertEquals(5, snapshot.myEntry.rank)
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
    fun `daily field keeps other players fixed while latest local power gets a provisional rank`() {
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
            snapshotId = "daily-1",
            settledAtEpochMillis = 1_000L,
            nextSettlementAtEpochMillis = 87_400_000L,
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
            now = 2_000L,
        )

        assertEquals(1, snapshot.myEntry.rank)
        assertEquals(400L, snapshot.myEntry.combatPower)
        assertEquals(listOf("me", "first", "third"), snapshot.entries.map { it.characterId })
        assertEquals(listOf(2, 3), snapshot.entries.filterNot { it.isMe }.map { it.rank })
        assertTrue(snapshot.usesLocalPower)
        assertEquals("daily-1", snapshot.snapshotId)
        assertEquals(1_000L, snapshot.settledAtEpochMillis)
        assertEquals(87_400_000L, snapshot.nextSettlementAtEpochMillis)
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
        )

        assertEquals(0, snapshot.myEntry.rank)
        assertEquals("Lv.20부터 참가", snapshot.myEntry.honorific)
        assertEquals("Lv.20부터 참가", rankingEntryMenuDetail(snapshot.myEntry))
        assertTrue(snapshot.entries.none { it.characterId == "me" })
        assertTrue(snapshot.usesLocalPower)
    }

    @Test
    fun `a newly eligible character waits until the next settlement`() {
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
        )

        assertEquals(0, snapshot.myEntry.rank)
        assertEquals("다음 정산부터 참가", snapshot.myEntry.honorific)
        assertEquals(2, snapshot.totalParticipants)
        assertEquals(listOf("first", "third"), snapshot.entries.map { it.characterId })
        assertTrue(snapshot.usesLocalPower)
    }

    @Test
    fun `a newly eligible local character gets a provisional place in the fixed daily field`() {
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
            snapshotId = "daily-1",
            settledAtEpochMillis = 1_000L,
            nextSettlementAtEpochMillis = 87_400_000L,
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
            now = 2_000L,
        )

        assertEquals(2, snapshot.myEntry.rank)
        assertEquals(listOf("first", "me", "third"), snapshot.entries.map { it.characterId })
        assertEquals(3, snapshot.totalParticipants)
        assertEquals("daily-1", snapshot.snapshotId)
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
        )

        assertEquals(5_000, snapshot.myEntry.rank)
        assertEquals(4_999, snapshot.myEntry.listIndex)
        assertEquals("1,000위 밖", rankingPositionLabel(snapshot.myEntry.rank))
        assertEquals("1,000위 밖 · 여정을 걷는 자", rankingEntryMenuDetail(snapshot.myEntry))
        assertTrue(snapshot.entries.none { it.characterId == "me" })
    }

    @Test
    fun `provisional local rerank below a complete top field keeps total and uses outside sentinel`() {
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
            now = 2_000L,
        )

        assertEquals(OUTSIDE_DISPLAYED_RANK, snapshot.myEntry.rank)
        assertEquals(5_000, snapshot.totalParticipants)
        assertEquals(MAX_DISPLAYED_RANK, snapshot.entries.size)
        assertFalse(snapshot.entries.any { it.characterId == "me" })
    }

    @Test
    fun `growth into the top one thousand waits for the next server settlement`() {
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
        )

        assertEquals(5_000, snapshot.myEntry.rank)
        assertFalse(snapshot.entries.any { it.characterId == "me" })
        assertEquals(100L, snapshot.myEntry.combatPower)
        assertEquals("1,000위 밖", rankingPositionLabel(snapshot.myEntry.rank))
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

    @Test
    fun `compact adventurer ranking copy stays short and returns to main in every language`() {
        assertEquals("모험가 랭킹", compactAdventurerRankingTitle(AppLanguage.KOREAN))
        assertEquals("Rankings", compactAdventurerRankingTitle(AppLanguage.ENGLISH))
        assertEquals("ランキング", compactAdventurerRankingTitle(AppLanguage.JAPANESE))

        assertEquals("메인 화면으로 돌아가기", adventurerRankingBackContentDescription(AppLanguage.KOREAN))
        assertEquals("Back to Main", adventurerRankingBackContentDescription(AppLanguage.ENGLISH))
        assertEquals("メイン画面に戻る", adventurerRankingBackContentDescription(AppLanguage.JAPANESE))

        assertEquals(
            "Adventurer rankings, Unranked, view button",
            compactAdventurerRankingAccessibilityLabel("Unranked", AppLanguage.ENGLISH),
        )
    }

    @Test
    fun `both rankings describe the active refresh period in one localized format`() {
        assertEquals(
            "갱신 주기 · 1시간",
            rankingRefreshPeriodLabel(RankingRefreshPolicy(1L), AppLanguage.KOREAN),
        )
        assertEquals(
            "Refresh interval · 1 hour",
            rankingRefreshPeriodLabel(RankingRefreshPolicy(1L), AppLanguage.ENGLISH),
        )
        assertEquals(
            "Refresh interval · 8 hours",
            rankingRefreshPeriodLabel(RankingRefreshPolicy(8L), AppLanguage.ENGLISH),
        )
        assertEquals(
            "更新間隔・24時間",
            rankingRefreshPeriodLabel(RankingRefreshPolicy(24L), AppLanguage.JAPANESE),
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
