package com.nullplaying.ui

import com.nullplaying.BuildConfig
import com.nullplaying.R
import com.nullplaying.engine.arena.ArenaProgressionRules
import com.nullplaying.engine.arena.ArenaSkillTreeRules
import com.nullplaying.localization.AppLanguage
import com.nullplaying.model.BATTLE_TICKET_CAPACITY
import com.nullplaying.model.BATTLE_PLACEMENT_BATTLES
import com.nullplaying.model.BATTLE_RATING_K
import com.nullplaying.model.BATTLE_TRAIT_REMOVAL_MILLIS
import com.nullplaying.model.BattleActionKind
import com.nullplaying.model.BattleActionResolution
import com.nullplaying.model.BattleGuidance
import com.nullplaying.model.BattleOutcome
import com.nullplaying.model.BattleEquipmentSlot
import com.nullplaying.model.BattleEquipmentSnapshot
import com.nullplaying.model.BattleRoundAction
import com.nullplaying.model.BattleRound
import com.nullplaying.model.BattleSide
import com.nullplaying.model.BattleSkillKind
import com.nullplaying.model.BattleSkillSnapshot
import com.nullplaying.model.BattleTraitCategory
import com.nullplaying.model.BattleTraitDefinition
import com.nullplaying.model.ProjectionBattleResult
import com.nullplaying.model.NormalizedBattleProjection
import com.nullplaying.remote.BattleQaNarrative
import com.nullplaying.remote.BattleQaScene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BattlePresentationTest {
    @Test
    fun `reward button shows exactly the remaining daily allowance in every language`() {
        assertEquals("광고로 3회 충전", battleEntryButtonLabel(
            0, null, rewardedRefillAvailable = true, rewardedRefillCount = 3,
        ))
        assertEquals("Watch ad for 3 entries", battleEntryButtonLabel(
            0, null, rewardedRefillAvailable = true, rewardedRefillCount = 3, language = AppLanguage.ENGLISH,
        ))
        assertEquals("広告で3回分回復", battleEntryButtonLabel(
            0, null, rewardedRefillAvailable = true, rewardedRefillCount = 3, language = AppLanguage.JAPANESE,
        ))
        AppLanguage.entries.forEach { language ->
            assertEquals("5", arenaTicketValueLabel(5, false, language))
            assertEquals("0", arenaTicketValueLabel(0, false, language))
        }
    }

    @Test
    fun `arena ranking failure detail never exposes raw server exception text`() {
        assertEquals("네트워크 연결을 확인한 뒤 다시 시도해 주세요.", arenaRankingErrorDetailLabel(AppLanguage.KOREAN))
        assertEquals("Check your connection and try again.", arenaRankingErrorDetailLabel(AppLanguage.ENGLISH))
        assertEquals("通信状態を確認して、もう一度お試しください。", arenaRankingErrorDetailLabel(AppLanguage.JAPANESE))
        assertFalse(arenaRankingErrorDetailLabel(AppLanguage.ENGLISH).contains("Throwable"))
    }

    @Test
    fun `commercial arena runtime copy is explicit in Korean English and Japanese`() {
        val korean = listOf(
            arenaBattleResultLabel(BattleOutcome.USER_WIN, AppLanguage.KOREAN),
            arenaBattlePointTitle(AppLanguage.KOREAN),
            arenaBattleSeasonScoreTitle(AppLanguage.KOREAN),
            arenaBattleSeasonRecordTitle(AppLanguage.KOREAN),
            arenaSeasonPlacementTitle(AppLanguage.KOREAN),
            arenaRecentHistoryTitle(AppLanguage.KOREAN),
            arenaRecentHistoryCountLabel(7, AppLanguage.KOREAN),
            arenaRecentHistoryEmptyLabel(AppLanguage.KOREAN),
            arenaFindingOpponentLabel(AppLanguage.KOREAN),
            arenaCloseLabel(AppLanguage.KOREAN),
            arenaConfirmLabel(AppLanguage.KOREAN),
            arenaNarrativePreparingLabel(AppLanguage.KOREAN),
            arenaLiveBattleLogTitle(AppLanguage.KOREAN),
            battleRankingEntryDetail(1_480, 10, AppLanguage.KOREAN),
            battleScoreDisplay(1_480, 9, AppLanguage.KOREAN),
        )
        val english = listOf(
            arenaBattleResultLabel(BattleOutcome.USER_WIN, AppLanguage.ENGLISH),
            arenaBattlePointTitle(AppLanguage.ENGLISH),
            arenaBattleSeasonScoreTitle(AppLanguage.ENGLISH),
            arenaBattleSeasonRecordTitle(AppLanguage.ENGLISH),
            arenaSeasonPlacementTitle(AppLanguage.ENGLISH),
            arenaRecentHistoryTitle(AppLanguage.ENGLISH),
            arenaRecentHistoryCountLabel(7, AppLanguage.ENGLISH),
            arenaRecentHistoryEmptyLabel(AppLanguage.ENGLISH),
            arenaFindingOpponentLabel(AppLanguage.ENGLISH),
            arenaCloseLabel(AppLanguage.ENGLISH),
            arenaConfirmLabel(AppLanguage.ENGLISH),
            arenaNarrativePreparingLabel(AppLanguage.ENGLISH),
            arenaLiveBattleLogTitle(AppLanguage.ENGLISH),
            battleRankingEntryDetail(1_480, 10, AppLanguage.ENGLISH),
            battleScoreDisplay(1_480, 9, AppLanguage.ENGLISH),
        )
        val japanese = listOf(
            arenaBattleResultLabel(BattleOutcome.USER_WIN, AppLanguage.JAPANESE),
            arenaBattlePointTitle(AppLanguage.JAPANESE),
            arenaBattleSeasonScoreTitle(AppLanguage.JAPANESE),
            arenaBattleSeasonRecordTitle(AppLanguage.JAPANESE),
            arenaSeasonPlacementTitle(AppLanguage.JAPANESE),
            arenaRecentHistoryTitle(AppLanguage.JAPANESE),
            arenaRecentHistoryCountLabel(7, AppLanguage.JAPANESE),
            arenaRecentHistoryEmptyLabel(AppLanguage.JAPANESE),
            arenaFindingOpponentLabel(AppLanguage.JAPANESE),
            arenaCloseLabel(AppLanguage.JAPANESE),
            arenaConfirmLabel(AppLanguage.JAPANESE),
            arenaNarrativePreparingLabel(AppLanguage.JAPANESE),
            arenaLiveBattleLogTitle(AppLanguage.JAPANESE),
            battleRankingEntryDetail(1_480, 10, AppLanguage.JAPANESE),
            battleScoreDisplay(1_480, 9, AppLanguage.JAPANESE),
        )

        assertEquals(
            listOf("승리", "결투장 포인트", "시즌 점수", "시즌 전적", "시즌 배치", "최근 전적", "7회", "아직 전적이 없습니다", "상대 찾는 중…", "닫기", "확인", "첫 문장을 준비하고 있습니다.", "결투장 기록", "1480점", "배치 중"),
            korean,
        )
        assertEquals(
            listOf("Victory", "Arena points", "Season score", "Season record", "Season placement", "Recent results", "7 matches", "No battle record yet", "Finding opponent…", "Close", "OK", "Preparing the first battle line.", "Arena log", "1480 pts", "Placement"),
            english,
        )
        assertEquals(
            listOf("勝利", "闘技場ポイント", "シーズンスコア", "シーズン戦績", "シーズン順位決定戦", "最近の戦績", "7戦", "まだ対戦記録がありません", "対戦相手を検索中…", "閉じる", "確認", "最初の戦闘メッセージを準備中です。", "闘技場の記録", "1480点", "順位決定中"),
            japanese,
        )
        assertFalse(english.any { Regex("[가-힣]").containsMatchIn(it) })
        assertFalse(japanese.any { Regex("[가-힣]").containsMatchIn(it) })
    }

    @Test
    fun `arena dynamic copy preserves names numbers and class identity in every language`() {
        assertEquals("Spend 1 point", battleEntryButtonLabel(3, null, availablePoints = 1, language = AppLanguage.ENGLISH))
        assertEquals("Spend 4 points", battleEntryButtonLabel(3, null, availablePoints = 4, language = AppLanguage.ENGLISH))
        assertEquals("4ポイント使用", battleEntryButtonLabel(3, null, availablePoints = 4, language = AppLanguage.JAPANESE))
        assertEquals(
            "Arena rankings, Placement 4/10, view button",
            arenaRankingEntryAccessibilityLabel("Placement 4/10", AppLanguage.ENGLISH),
        )
        assertEquals("Auto-cancels in 2 mins", battleMatchReadyCountdownLabel(120_000L, 0L, AppLanguage.ENGLISH))
        assertEquals("1秒後に自動キャンセル", battleMatchReadyCountdownLabel(1_000L, 0L, AppLanguage.JAPANESE))
        assertEquals(
            "Duel ready, Auto-cancels in 2 mins",
            battleMatchReadyAccessibilityLabel("Auto-cancels in 2 mins", AppLanguage.ENGLISH),
        )
        assertEquals(
            "Thief · Lv.25",
            battleHeroClassLevelLabel(com.nullplaying.model.BattleHeroClass.ROGUE, 25, AppLanguage.ENGLISH),
        )
        assertEquals(
            "シーフ · Lv.25 · プレイヤー",
            arenaHistoryOpponentMetaLabel("시프", 25, "PUBLIC_ROSTER", AppLanguage.JAPANESE),
        )
        assertEquals("Rhea · Victory", arenaHistoryDetailTitle("Rhea", "승리", AppLanguage.ENGLISH))
        assertEquals(
            "View the previous Arena record against Rhea",
            arenaHistoryAccessibilityLabel("Rhea", AppLanguage.ENGLISH),
        )
        assertEquals("Rhea attacks", arenaBattleMomentumLabel(BattleSide.USER, "Rhea", "Nox", AppLanguage.ENGLISH))
        assertEquals("Noxの攻勢", arenaBattleMomentumLabel(BattleSide.OPPONENT, "Rhea", "Nox", AppLanguage.JAPANESE))
        assertEquals("Battle line 3 / 8", battlePlaybackProgressLabel(2, 8, AppLanguage.ENGLISH))
    }

    @Test
    fun `every arena runtime helper blocks Korean leakage outside Korean locale`() {
        listOf(AppLanguage.ENGLISH, AppLanguage.JAPANESE).forEach { language ->
            val samples = buildList {
                BattleOutcome.entries.forEach { add(arenaBattleResultLabel(it, language)) }
                com.nullplaying.model.BattleHeroClass.entries.forEach {
                    add(battleHeroClassLevelLabel(it, 30, language))
                }
                add(arenaBattlePointTitle(language))
                add(arenaBattleSeasonScoreTitle(language))
                add(arenaBattleSeasonRecordTitle(language))
                add(arenaSeasonPlacementTitle(language))
                add(arenaRecentHistoryTitle(language))
                add(arenaRecentHistoryCountLabel(10, language))
                add(arenaRecentHistoryEmptyLabel(language))
                add(arenaFindingOpponentLabel(language))
                add(arenaCloseLabel(language))
                add(arenaConfirmLabel(language))
                add(arenaNarrativePreparingLabel(language))
                add(arenaLiveBattleLogTitle(language))
                add(battleNarrativeSourceLabel("arena_public_roster", false, language))
                add(battleNarrativeSourceLabel("qwen", true, language))
                add(battleNarrativeSourceLabel("local_template", false, language))
                add(arenaHistoryResultLabel("패배", language))
                add(arenaHistoryOpponentMetaLabel("클래릭", 30, "LOCAL_RESERVE", language))
                add(arenaHistoryDetailTitle("Rhea", "무승부", language))
                add(arenaHistoryAccessibilityLabel("Rhea", language))
                add(battleRankingEntryDetail(1_480, 4, language))
                add(battleRankingEntryDetail(1_480, 10, language))
                add(arenaRankingEntryAccessibilityLabel(battleRankingEntryDetail(1_480, 4, language), language))
                add(battleScoreDisplay(1_480, 4, language))
                add(battleMatchReadyTitle(language))
                add(battleMatchReadyCountdownLabel(61_000L, 0L, language))
                add(arenaBattleProgressDescription(language))
                add(arenaBattleProgressStateLabel(false, false, false, 2, 8, language))
                add(arenaBattleMomentumLabel(BattleSide.USER, "Rhea", "Nox", language))
                add(arenaBattleEnergyAccessibilityLabel("Rhea", "Nox", language))
                add(arenaRewardedRefillTitle(language))
                add(arenaRewardedRefillSupportingMessage(language))
                add(arenaMatchFoundMessage(language))
                add(arenaMatchFoundActionLabel(language))
                add(arenaMatchFoundAccessibilityLabel(language))
                add(battleGuidanceLabel("Rhea", BattleStance.ASSAULT, "Nox", BattleStance.GUARD, language))
                add(battleOfficialResultTitle(BattleOutcome.USER_WIN, language))
                add(battleResultOpponentMetaLabel("Nox", 1_440, true, language))
                add(battleResultOpponentMetaLabel("Nox", 1_440, false, language))
                add(
                    battleResultFighterMetaLabel(
                        name = "Rhea",
                        heroClass = com.nullplaying.model.BattleHeroClass.RANGER,
                        level = 30L,
                        power = 284L,
                        opponent = false,
                        language = language,
                    ),
                )
                add(
                    battleDecisiveMomentLabel(
                        outcome = BattleOutcome.USER_WIN,
                        rounds = 6,
                        userName = "Rhea",
                        userHp = 42,
                        opponentName = "Nox",
                        opponentHp = 0,
                        language = language,
                    ),
                )
            }

            assertTrue("$language returned a blank runtime label", samples.all(String::isNotBlank))
            samples.forEach { value ->
                assertFalse("$language leaked Korean: $value", Regex("[가-힣]").containsMatchIn(value))
            }
        }
    }

    @Test
    fun `manual arena result copy preserves Korean player names in foreign locales`() {
        assertEquals(
            "전적: Assault · 랭킹: Guard",
            battleGuidanceLabel(
                userName = "전적",
                userStance = BattleStance.ASSAULT,
                opponentName = "랭킹",
                opponentStance = BattleStance.GUARD,
                language = AppLanguage.ENGLISH,
            ),
        )
        assertEquals(
            "전적：猛攻・랭킹：守勢",
            battleGuidanceLabel(
                userName = "전적",
                userStance = BattleStance.ASSAULT,
                opponentName = "랭킹",
                opponentStance = BattleStance.GUARD,
                language = AppLanguage.JAPANESE,
            ),
        )
        assertEquals(
            "전적 · 1440 pts · Season participant",
            battleResultOpponentMetaLabel("전적", 1_440, true, AppLanguage.ENGLISH),
        )
        assertEquals(
            "전적・順位決定戦参加者",
            battleResultOpponentMetaLabel("전적", 1_440, false, AppLanguage.JAPANESE),
        )
    }

    @Test
    fun `arena rewarded and match found surfaces have reviewed copy in all languages`() {
        assertEquals("Refill Arena entries", arenaRewardedRefillTitle(AppLanguage.ENGLISH))
        assertEquals("出場券回復", arenaRewardedRefillTitle(AppLanguage.JAPANESE))
        assertEquals(
            "10-minute auto-refill stays active\nOnce daily per character · Available at 0 entries",
            arenaRewardedRefillSupportingMessage(AppLanguage.ENGLISH),
        )
        assertEquals("Arena opponent found", arenaMatchFoundMessage(AppLanguage.ENGLISH))
        assertEquals("Go", arenaMatchFoundActionLabel(AppLanguage.ENGLISH))
        assertEquals(
            "闘技場の対戦相手が見つかりました。闘技場へ移動します。",
            arenaMatchFoundAccessibilityLabel(AppLanguage.JAPANESE),
        )
    }

    @Test
    fun `arena ranking status copy is complete in every language`() {
        assertEquals("결투장 랭킹을 불러오는 중입니다.", arenaRankingLoadingLabel(AppLanguage.KOREAN))
        assertEquals("Loading Arena rankings.", arenaRankingLoadingLabel(AppLanguage.ENGLISH))
        assertEquals("闘技場ランキングを読み込み中です。", arenaRankingLoadingLabel(AppLanguage.JAPANESE))
        assertEquals("결투장 랭킹을 불러오지 못했습니다.", arenaRankingLoadFailureLabel(AppLanguage.KOREAN))
        assertEquals("Couldn't load Arena rankings.", arenaRankingLoadFailureLabel(AppLanguage.ENGLISH))
        assertEquals("闘技場ランキングを読み込めませんでした。", arenaRankingLoadFailureLabel(AppLanguage.JAPANESE))
        assertEquals("다시 시도", arenaRankingRetryLabel(AppLanguage.KOREAN))
        assertEquals("Retry", arenaRankingRetryLabel(AppLanguage.ENGLISH))
        assertEquals("再試行", arenaRankingRetryLabel(AppLanguage.JAPANESE))
        assertEquals("랭킹 집계 중", arenaRankingEmptyTitle(AppLanguage.KOREAN))
        assertEquals("Compiling rankings", arenaRankingEmptyTitle(AppLanguage.ENGLISH))
        assertEquals("ランキング集計中", arenaRankingEmptyTitle(AppLanguage.JAPANESE))
        assertEquals("배치 진행 중", arenaRankingPlacementTitle(AppLanguage.KOREAN))
        assertEquals("Placement in progress", arenaRankingPlacementTitle(AppLanguage.ENGLISH))
        assertEquals("順位決定戦進行中", arenaRankingPlacementTitle(AppLanguage.JAPANESE))
    }

    @Test
    fun `battle log displays newest completed sentence first without changing its ordinal`() {
        val entries = battleLogEntries(listOf("첫 문장", "둘째 문장", "마지막 문장"))

        assertEquals(listOf(3, 2, 1), entries.map { it.ordinal })
        assertEquals(listOf("마지막 문장", "둘째 문장", "첫 문장"), entries.map { it.text })
    }

    @Test
    fun `battle prose identifies both sides skill and rarity item as distinct semantic spans`() {
        val skill = BattleSkillSnapshot(
            skillId = "meteor-shot",
            displayName = "유성 사격",
            kind = BattleSkillKind.PIERCE,
        )
        val item = BattleEquipmentSnapshot(
            itemId = "star-bow",
            displayName = "별매듭 장궁",
            slot = BattleEquipmentSlot.WEAPON,
            rarity = "전설",
        )
        val trait = BattleTraitDefinition(
            id = "TRAIT_012",
            nameKo = "빈틈 추적자",
            descriptionKo = "작은 틈도 놓치지 않는다.",
            category = BattleTraitCategory.OFFENSE,
        )
        val text = "빈틈 추적자인 푸른별은 별매듭 장궁으로 유성 사격을 펼쳤고 붉은달은 물러섰다."

        val spans = battleNarrativeSpans(
            text = text,
            userName = "푸른별",
            opponentName = "붉은달",
            skills = listOf(skill),
            equipment = listOf(item),
            traits = listOf(trait),
        )
        val rolesByText = spans.associate { span ->
            text.substring(span.start, span.endExclusive) to span.role
        }

        assertEquals(BattleNarrativeRole.USER, rolesByText["푸른별"])
        assertEquals(BattleNarrativeRole.OPPONENT, rolesByText["붉은달"])
        assertEquals(BattleNarrativeRole.SKILL, rolesByText["유성 사격"])
        assertEquals(BattleNarrativeRole.ITEM, rolesByText["별매듭 장궁"])
        assertEquals(BattleNarrativeRole.TRAIT, rolesByText["빈틈 추적자"])
        assertEquals("전설", spans.single { it.role == BattleNarrativeRole.ITEM }.rarity)
    }

    @Test
    fun `skills and traits each use their shared accent color`() {
        val skill = BattleSkillSnapshot(
            skillId = "virtual-shield-bash",
            displayName = "방패 밀치기",
            kind = BattleSkillKind.CONTROL,
        )

        assertEquals(battleSkillAccent(skill), battleSkillAccent(skill.copy(displayName = "renamed")))
        assertEquals(
            battleSkillAccent(skill),
            battleSkillAccent(
                BattleSkillSnapshot(
                    skillId = "virtual-meteor-shot",
                    displayName = "유성 사격",
                    kind = BattleSkillKind.PIERCE,
                ),
            ),
        )

        val trait = BattleTraitDefinition(
            id = "TRAIT_012",
            nameKo = "빈틈 추적자",
            category = BattleTraitCategory.OFFENSE,
        )
        assertEquals(battleTraitAccent(trait), battleTraitAccent(trait.copy(nameKo = "이름 변경")))
        assertEquals(
            battleTraitAccent(trait),
            battleTraitAccent(
                BattleTraitDefinition(
                    id = "TRAIT_050",
                    nameKo = "마지막까지 선 자",
                    category = BattleTraitCategory.ENDURANCE,
                ),
            ),
        )
    }

    @Test
    fun `result content stays hidden until dedicated page playback finishes`() {
        assertEquals(false, battleOutcomeVisible(BattleFlowStep.MATCHING))
        assertEquals(false, battleOutcomeVisible(BattleFlowStep.MATCH_READY))
        assertEquals(false, battleOutcomeVisible(BattleFlowStep.PLAYING))
        assertTrue(battleOutcomeVisible(BattleFlowStep.RESULT))
        assertTrue(battleSettlementAllowed(BattleFlowStep.PLAYING))
        assertTrue(battleSettlementAllowed(BattleFlowStep.RESULT))
    }

    @Test
    fun `battle participation locks menu input and exposes current entry states`() {
        assertEquals(18, BATTLE_ENTRY_BUTTON_FONT_SIZE_SP)
        assertEquals(22, BATTLE_ENTRY_BUTTON_LINE_HEIGHT_SP)
        assertEquals("상대 찾기", battleEntryButtonLabel(5, null))
        assertEquals("상대 찾는 중…", battleEntryButtonLabel(5, BattleFlowStep.MATCHING))
        assertEquals("출전권 회복 중", battleEntryButtonLabel(0, null))
        assertEquals("광고로 5회 충전", battleEntryButtonLabel(
            entriesRemaining = 0,
            step = null,
            rewardedRefillAvailable = true,
        ))
        assertEquals("오늘 광고 충전 1/1", battleEntryButtonLabel(
            entriesRemaining = 0,
            step = null,
            rewardedRefillLimitReached = true,
        ))
        assertEquals("오늘 출전 완료 10/10", battleEntryButtonLabel(
            entriesRemaining = 7,
            step = null,
            dailyLimitReached = true,
        ))
        assertEquals(BattleEntryPrimaryAction.NONE, battleEntryPrimaryAction(
            entriesRemaining = 7,
            step = null,
            unlimitedEntries = false,
            entryAllowed = true,
            hasAttackSkill = true,
            dailyLimitReached = true,
        ))
        assertFalse(BuildConfig.BATTLE_UNLIMITED_ENTRIES)
        assertEquals(BuildConfig.BUILD_TYPE == "battleQa", BuildConfig.BATTLE_SKILL_TREE_REVIEW_ENABLED)
        assertFalse(BuildConfig.BATTLE_IGNORE_HERO_LEVEL_GATE)
        assertEquals(0, BuildConfig.BATTLE_QA_HERO_LEVEL_OVERRIDE)
        assertEquals(BATTLE_DAILY_ENTRIES, battleEntryCountForSession(0, unlimitedEntries = true))
        assertEquals(0, battleEntryCountForSession(0, unlimitedEntries = false))
        assertEquals("상대 찾기", battleEntryButtonLabel(0, null, unlimitedEntries = true))
    }

    @Test
    fun `normal arena requires both a persisted unlock and the real hero level`() {
        assertFalse(arenaEntryUnlocked(
            progressionUnlocked = true,
            heroLevel = ArenaProgressionRules.MIN_HERO_LEVEL - 1,
            ignoreHeroLevelGate = false,
        ))
        assertTrue(arenaEntryUnlocked(
            progressionUnlocked = true,
            heroLevel = ArenaProgressionRules.MIN_HERO_LEVEL,
            ignoreHeroLevelGate = false,
        ))
        assertFalse(arenaEntryUnlocked(
            progressionUnlocked = false,
            heroLevel = 100,
            ignoreHeroLevelGate = false,
        ))
    }

    @Test
    fun `battle preparation reports the actual matching phase`() {
        assertEquals(BattleSessionPhase.IDLE, battleSessionPhase(null, preparing = false))
        assertEquals(BattleSessionPhase.MATCHING, battleSessionPhase(null, preparing = true))
        assertEquals(
            BattleSessionPhase.MATCHING,
            battleSessionPhase(BattleFlowStep.MATCHING, preparing = false),
        )
        assertEquals(
            BattleSessionPhase.MATCH_READY,
            battleSessionPhase(BattleFlowStep.MATCH_READY, preparing = false),
        )
        assertEquals(
            BattleSessionPhase.IN_BATTLE,
            battleSessionPhase(BattleFlowStep.PLAYING, preparing = false),
        )
    }

    @Test
    fun `arena entry spends points selects an attack and handles refill before matchmaking`() {
        assertEquals(
            BattleEntryPrimaryAction.OPEN_SKILL_TREE,
            battleEntryPrimaryAction(
                entriesRemaining = 5,
                step = null,
                unlimitedEntries = false,
                entryAllowed = true,
                hasAttackSkill = true,
                availablePoints = 3,
            ),
        )
        assertEquals(
            "3포인트 사용",
            battleEntryButtonLabel(
                entriesRemaining = 5,
                step = null,
                hasAttackSkill = true,
                availablePoints = 3,
            ),
        )
        assertEquals(
            BattleEntryPrimaryAction.OPEN_SKILL_TREE,
            battleEntryPrimaryAction(
                entriesRemaining = 5,
                step = null,
                unlimitedEntries = false,
                entryAllowed = true,
                hasAttackSkill = false,
            ),
        )
        assertEquals(
            "공격 스킬 선택",
            battleEntryButtonLabel(
                entriesRemaining = 5,
                step = null,
                hasAttackSkill = false,
            ),
        )
        assertEquals(
            BattleEntryPrimaryAction.FIND_OPPONENT,
            battleEntryPrimaryAction(5, null, false, true, hasAttackSkill = true),
        )
        assertEquals(
            BattleEntryPrimaryAction.START_BATTLE,
            battleEntryPrimaryAction(5, BattleFlowStep.MATCH_READY, false, true, hasAttackSkill = true),
        )
        assertEquals(
            BattleEntryPrimaryAction.REFILL_TICKETS,
            battleEntryPrimaryAction(
                entriesRemaining = 0,
                step = null,
                unlimitedEntries = false,
                entryAllowed = true,
                hasAttackSkill = true,
                rewardedRefillAvailable = true,
            ),
        )
        assertEquals(
            BattleEntryPrimaryAction.NONE,
            battleEntryPrimaryAction(0, null, false, true, hasAttackSkill = true),
        )
        assertEquals(
            "출전권 회복 중",
            battleEntryButtonLabel(0, null),
        )
    }

    @Test
    fun `arena ticket card copy stays concise across every recovery state`() {
        assertEquals("무제한", arenaTicketValueLabel(10, true, AppLanguage.KOREAN))
        assertEquals("QA 전용", arenaTicketStatusLabel(10, true, null, AppLanguage.KOREAN))
        assertEquals("5", arenaTicketValueLabel(10, false, AppLanguage.KOREAN))
        assertEquals("충전 완료", arenaTicketStatusLabel(10, false, null, AppLanguage.KOREAN))
        assertEquals("3", arenaTicketValueLabel(3, false, AppLanguage.KOREAN))
        assertEquals("1장 충전중 : 3:21", arenaTicketStatusLabel(3, false, "3:21", AppLanguage.KOREAN))
        assertEquals("Next entry: 3:21", arenaTicketStatusLabel(3, false, "3:21", AppLanguage.ENGLISH))
        assertEquals("1枚回復中：3:21", arenaTicketStatusLabel(3, false, "3:21", AppLanguage.JAPANESE))
        assertEquals("4", arenaTicketValueLabel(4, false, AppLanguage.KOREAN))
        assertEquals("1장 충전중 : 10:00", arenaTicketStatusLabel(4, false, "10:00", AppLanguage.KOREAN))
        assertEquals("충전 완료", arenaTicketStatusLabel(10, false, "3:21", AppLanguage.KOREAN))
        assertEquals("QA 전용", arenaTicketStatusLabel(10, true, "3:21", AppLanguage.KOREAN))
        assertEquals("0", arenaTicketValueLabel(0, false, AppLanguage.KOREAN))
        assertEquals("충전 중", arenaTicketStatusLabel(0, false, null, AppLanguage.KOREAN))
    }

    @Test
    fun `daily arena limit replaces ticket recharge copy with localized trusted reset countdown`() {
        assertEquals(
            "초기화까지 7:08:09",
            arenaTicketStatusLabel(
                entriesRemaining = 3,
                unlimitedEntries = false,
                entryRecoveryCountdown = "5:22",
                language = AppLanguage.KOREAN,
                dailyLimitReached = true,
                dailyResetCountdown = "7:08:09",
            ),
        )
        assertEquals(
            "Reset in 7:08:09",
            arenaTicketStatusLabel(
                entriesRemaining = BATTLE_ENTRY_CAPACITY,
                unlimitedEntries = false,
                entryRecoveryCountdown = null,
                language = AppLanguage.ENGLISH,
                dailyLimitReached = true,
                dailyResetCountdown = "7:08:09",
            ),
        )
        assertEquals(
            "リセットまで 7:08:09",
            arenaTicketStatusLabel(
                entriesRemaining = 0,
                unlimitedEntries = false,
                entryRecoveryCountdown = "5:22",
                language = AppLanguage.JAPANESE,
                dailyLimitReached = true,
                dailyResetCountdown = "7:08:09",
            ),
        )
        assertEquals(
            "서버 시간 확인 중…",
            arenaTicketStatusLabel(
                entriesRemaining = 3,
                unlimitedEntries = false,
                entryRecoveryCountdown = "5:22",
                language = AppLanguage.KOREAN,
                dailyLimitReached = true,
                dailyResetCountdown = null,
            ),
        )
        assertEquals(
            "Checking server time…",
            arenaTicketStatusLabel(
                entriesRemaining = 3,
                unlimitedEntries = false,
                entryRecoveryCountdown = null,
                language = AppLanguage.ENGLISH,
                dailyLimitReached = true,
                dailyResetCountdown = null,
            ),
        )
        assertEquals(
            "サーバー時刻を確認中…",
            arenaTicketStatusLabel(
                entriesRemaining = 3,
                unlimitedEntries = false,
                entryRecoveryCountdown = null,
                language = AppLanguage.JAPANESE,
                dailyLimitReached = true,
                dailyResetCountdown = null,
            ),
        )
        assertEquals("7:08:10", arenaDailyResetCountdownLabel(25_689_001L))
    }

    @Test
    fun `arena ticket progress shows progress toward the next entry`() {
        assertEquals(1f, arenaTicketRecoveryProgress(10, false, null), 0f)
        assertEquals(1f, arenaTicketRecoveryProgress(0, true, null), 0f)
        assertEquals(0f, arenaTicketRecoveryProgress(0, false, BATTLE_ENTRY_RECOVERY_INTERVAL_MILLIS), 0f)
        assertEquals(0.5f, arenaTicketRecoveryProgress(0, false, BATTLE_ENTRY_RECOVERY_INTERVAL_MILLIS / 2L), 0.000_001f)
        assertEquals(0.75f, arenaTicketRecoveryProgress(3, false, BATTLE_ENTRY_RECOVERY_INTERVAL_MILLIS / 4L), 0.000_001f)
        assertEquals(0f, arenaTicketRecoveryProgress(4, false, BATTLE_ENTRY_RECOVERY_INTERVAL_MILLIS + 1L), 0f)
        assertEquals(1f, arenaTicketRecoveryProgress(4, false, -1L), 0f)
        assertEquals(1f, arenaTicketRecoveryProgress(4, false, 0L), 0f)
    }

    @Test
    fun `battle QA today entry opens isolated six class review with maximum point budget`() {
        assertNull(battleSkillTreeReviewRequest(enabled = false, language = AppLanguage.KOREAN))

        val request = checkNotNull(
            battleSkillTreeReviewRequest(enabled = true, language = AppLanguage.KOREAN),
        )

        assertEquals("com.nullplaying.ui.ArenaProgressionQaActivity", request.activityClassName)
        assertEquals(ArenaSkillTreeRules.maxArenaLevel, request.arenaLevel)
        assertEquals(100, request.arenaLevel)
        assertEquals(0, request.allocatedPoints)
        assertEquals("ko", request.languageTag)
    }

    @Test
    fun `unstarted ready battle expires after ten minutes and countdown rounds up by minute`() {
        val readyAt = 100_000L
        val expiresAt = battleMatchReadyExpiresAt(readyAt)

        assertEquals(600_000L, BATTLE_MATCH_READY_TIMEOUT_MILLIS)
        assertEquals(700_000L, expiresAt)
        assertEquals("10분 후 자동 취소", battleMatchReadyCountdownLabel(expiresAt, readyAt))
        assertEquals("9분 후 자동 취소", battleMatchReadyCountdownLabel(expiresAt, 170_000L))
        assertEquals("59초 후 자동 취소", battleMatchReadyCountdownLabel(expiresAt, 641_000L))
        assertEquals("0초 후 자동 취소", battleMatchReadyCountdownLabel(expiresAt, expiresAt))
        assertEquals(false, battleMatchReadyExpired(expiresAt, 699_999L))
        assertTrue(battleMatchReadyExpired(expiresAt, 700_000L))
        assertEquals(Long.MAX_VALUE, battleMatchReadyExpiresAt(Long.MAX_VALUE - 10_000L))
    }

    @Test
    fun `turn playback waits for motion and never starts the next attack before one point five seconds`() {
        assertEquals(1_500, BATTLE_INTRO_DISPLAY_MILLIS)
        assertEquals(1_200, BATTLE_TRAIT_DISPLAY_MILLIS)
        assertEquals(2, BATTLE_MAX_MID_TRAIT_ACTIVATIONS)
        assertEquals(1_500, BATTLE_MIN_ATTACK_INTERVAL_MILLIS)
        assertEquals(180, BATTLE_ENERGY_IMPACT_MILLIS)
        assertEquals(350, BATTLE_ARENA_TINT_HOLD_MILLIS)
        assertEquals(250, BATTLE_ARENA_TINT_FADE_MILLIS)
        assertEquals(600, battleArenaTintDurationMillis())
        assertEquals(1_500, BATTLE_BASIC_ATTACK_MOTION_MILLIS)
        assertEquals(1_000, BATTLE_GUARD_MOTION_MILLIS)
        assertEquals(1_700, BATTLE_SKILL_MOTION_MILLIS)
        assertEquals(1_850, BATTLE_POWER_ATTACK_MOTION_MILLIS)
        assertEquals(2_100, BATTLE_FINISHER_MOTION_MILLIS)
        assertEquals(650, BATTLE_FINAL_ENDPOINT_CONFIRM_MILLIS)
        assertEquals(450, BATTLE_RESULT_ENTER_MILLIS)
        assertEquals(250, BATTLE_RESULT_EXIT_MILLIS)
        assertEquals(
            1_500,
            battleActionMotionDurationMillis(BattleRoundAction(kind = BattleActionKind.BASIC_ATTACK)),
        )
        assertEquals(
            1_000,
            battleActionMotionDurationMillis(BattleRoundAction(kind = BattleActionKind.GUARD)),
        )
        assertEquals(
            1_700,
            battleActionMotionDurationMillis(BattleRoundAction(kind = BattleActionKind.SKILL)),
        )
        assertEquals(
            1_850,
            battleActionMotionDurationMillis(BattleRoundAction(powerAttack = true)),
        )
        assertEquals(
            2_100,
            battleActionMotionDurationMillis(BattleRoundAction(finisher = true)),
        )
        assertEquals(500, battleNextAttackDelayMillis(BATTLE_GUARD_MOTION_MILLIS))
        assertEquals(0, battleNextAttackDelayMillis(BATTLE_BASIC_ATTACK_MOTION_MILLIS))
        assertEquals(0, battleNextAttackDelayMillis(BATTLE_SKILL_MOTION_MILLIS))
        assertEquals(180, battleEnergyImpactDurationMillis(BATTLE_GUARD_MOTION_MILLIS))
        assertEquals(180, battleEnergyImpactDurationMillis(BATTLE_BASIC_ATTACK_MOTION_MILLIS))
        assertEquals(900, battlePostVisualEffectsDelayMillis(BATTLE_GUARD_MOTION_MILLIS))
        assertEquals(900, battlePostVisualEffectsDelayMillis(BATTLE_BASIC_ATTACK_MOTION_MILLIS))
        assertEquals(1_100, battlePostVisualEffectsDelayMillis(BATTLE_SKILL_MOTION_MILLIS))
        assertEquals("아린과 미라의 전투가 시작됐다.", battleOpeningAnnouncement("아린", "미라", "ko"))
        assertEquals("The battle between Arin and Mira began.", battleOpeningAnnouncement("Arin", "Mira", "en-US"))
        assertEquals("アリンとミラの戦いが始まった。", battleOpeningAnnouncement("アリン", "ミラ", "ja"))
        val trait = BattleTraitDefinition(
            id = "TRAIT_012",
            nameKo = "빈틈 추적자",
            category = BattleTraitCategory.OFFENSE,
        )
        assertEquals(
            "아린의 특성 빈틈 추적자가 드러났다.",
            battleOpeningTraitAnnouncement("아린", trait, "ko"),
        )
        assertEquals(
            "Arin entered with offensive instinct.",
            battleOpeningTraitAnnouncement("Arin", trait, "en"),
        )
        assertEquals(
            "アリンは攻勢本能を携えて臨んだ。",
            battleOpeningTraitAnnouncement("アリン", trait, "ja"),
        )
        assertEquals(
            "아린의 특성 빈틈 추적자가 발동했다.",
            battleTraitActivationAnnouncement("아린", trait, "ko"),
        )
        assertEquals("Arin's offensive instinct activated.", battleTraitActivationAnnouncement("Arin", trait, "en"))
        assertEquals("アリンの攻勢本能が発動した。", battleTraitActivationAnnouncement("アリン", trait, "ja"))
        assertEquals("결투 개시", battleOpeningLabel("ko"))
        assertEquals("Duel begins", battleOpeningLabel("en"))
        assertEquals("決闘開始", battleOpeningLabel("ja"))
        assertEquals("특성 발동", battleTraitActivationLabel("ko"))
        assertEquals("Trait activated", battleTraitActivationLabel("en"))
        assertEquals("特性発動", battleTraitActivationLabel("ja"))
        assertEquals(3, BATTLE_NARRATIVE_MIN_ANCHORS)
        assertEquals(5, BATTLE_NARRATIVE_MAX_ANCHORS)
        assertEquals(3, battleNarrativeAnchorCount(2))
        assertEquals(4, battleNarrativeAnchorCount(4))
        assertEquals(5, battleNarrativeAnchorCount(8))
        assertEquals("전투 문장 1 / 8", battlePlaybackProgressLabel(lineIndex = 0, lineCount = 8))
        assertEquals("전투 문장 6 / 8", battlePlaybackProgressLabel(lineIndex = 5, lineCount = 8))
        assertEquals("전투 문장 1 / 1", battlePlaybackProgressLabel(lineIndex = -1, lineCount = 0))
    }

    @Test
    fun `each duel paragraph carries exchange state power and exact energy movement`() {
        val battle = ProjectionBattleResult(
            rounds = listOf(
                BattleRound(
                    number = 1,
                    userHpBefore = 1_000,
                    opponentHpBefore = 1_000,
                    userAction = BattleRoundAction(
                        actor = BattleSide.USER,
                        kind = BattleActionKind.BASIC_ATTACK,
                        damage = 100,
                    ),
                    opponentAction = BattleRoundAction(
                        actor = BattleSide.OPPONENT,
                        kind = BattleActionKind.BASIC_ATTACK,
                        damage = 60,
                    ),
                    userHpAfter = 940,
                    opponentHpAfter = 900,
                ),
                BattleRound(
                    number = 2,
                    userHpBefore = 940,
                    opponentHpBefore = 900,
                    userAction = BattleRoundAction(actor = BattleSide.USER, damage = 70),
                    opponentAction = BattleRoundAction(actor = BattleSide.OPPONENT, damage = 70),
                    userHpAfter = 870,
                    opponentHpAfter = 830,
                ),
            ),
        )
        val narrative = BattleQaNarrative(
            scenes = listOf(
                BattleQaScene(text = "첫 합이 시작됐다. 푸른별이 앞섰다.", effectKey = "SLASH"),
                BattleQaScene(text = "두 무기가 맞섰다. 마지막 거리가 좁아졌다.", effectKey = "CLASH"),
            ),
        )

        val beats = battlePlaybackBeats(battle, narrative)

        assertEquals(4, beats.size)
        assertEquals(BattleParagraphState.ATTACK, beats[0].userState)
        assertEquals(BattleParagraphState.HIT, beats[0].opponentState)
        assertEquals(BattleSide.USER, beats[0].arenaTintSide)
        assertEquals(100, beats[0].userPower)
        assertEquals(0, beats[0].opponentPower)
        assertEquals(1_000, beats[0].userEnergyBefore)
        assertEquals(1_000, beats[0].userEnergyAfter)
        assertEquals(1_000, beats[0].opponentEnergyBefore)
        assertEquals(900, beats[0].opponentEnergyAfter)
        assertEquals(BattleParagraphState.HIT, beats[1].userState)
        assertEquals(BattleParagraphState.ATTACK, beats[1].opponentState)
        assertEquals(BattleSide.OPPONENT, beats[1].arenaTintSide)
        assertEquals(0, beats[1].userPower)
        assertEquals(60, beats[1].opponentPower)
        assertEquals(940, beats[1].userEnergyAfter)
        assertEquals(900, beats[1].opponentEnergyAfter)
        assertEquals(BattleParagraphState.HIT, beats[2].userState)
        assertEquals(BattleParagraphState.ATTACK, beats[2].opponentState)
        assertEquals(BattleParagraphState.ATTACK, beats[3].userState)
        assertEquals(BattleParagraphState.HIT, beats[3].opponentState)
        assertEquals(870, beats.last().userEnergyAfter)
        assertEquals(830, beats.last().opponentEnergyAfter)
        assertEquals(listOf(1_500, 1_500, 1_500, 1_500), beats.map(BattlePlaybackBeat::motionDurationMillis))
        beats.zipWithNext().forEach { (before, after) ->
            assertEquals(before.userEnergyAfter, after.userEnergyBefore)
            assertEquals(before.opponentEnergyAfter, after.opponentEnergyBefore)
        }
        beats.filter { it.text.isNotBlank() }.forEach { beat ->
            when {
                beat.userState == BattleParagraphState.ATTACK ->
                    assertTrue(beat.opponentEnergyAfter < beat.opponentEnergyBefore)
                beat.opponentState == BattleParagraphState.ATTACK ->
                    assertTrue(beat.userEnergyAfter < beat.userEnergyBefore)
            }
        }
        assertEquals(beats, battlePlaybackPlan(battle, narrative).turns)
        val traitBattle = battle.copy(
            battleId = "trait-sequence-test",
            user = battle.user.copy(
                displayName = "푸른별",
                activeTraitIds = listOf("TRAIT_012", "TRAIT_050", "TRAIT_071"),
            ),
            opponent = battle.opponent.copy(displayName = "붉은달"),
        )
        val traitPlan = battlePlaybackPlan(traitBattle, narrative.copy(languageTag = "ko"))
        assertEquals(BattlePlaybackBeatKind.OPENING, traitPlan.sequence[0].kind)
        assertEquals("푸른별과 붉은달의 전투가 시작됐다.", traitPlan.sequence[0].text)
        assertTrue("특성" !in traitPlan.sequence[0].text)
        assertEquals(
            listOf("TRAIT_012", "TRAIT_050", "TRAIT_071"),
            traitPlan.sequence
                .filter { it.kind == BattlePlaybackBeatKind.OPENING_TRAIT }
                .map(BattlePlaybackBeat::traitId),
        )
        val firstActionIndex = traitPlan.sequence.indexOfFirst { it.kind == BattlePlaybackBeatKind.ACTION }
        assertEquals(4, firstActionIndex)
        val midTraitIndex = traitPlan.sequence.indexOfFirst { it.kind == BattlePlaybackBeatKind.MID_BATTLE_TRAIT }
        assertTrue(midTraitIndex > firstActionIndex)
        assertEquals(BattlePlaybackBeatKind.ACTION, traitPlan.sequence[midTraitIndex + 1].kind)
        assertEquals(traitPlan.sequence[midTraitIndex].userEnergyBefore, traitPlan.sequence[midTraitIndex].userEnergyAfter)
        assertEquals(
            traitPlan.sequence[midTraitIndex].opponentEnergyBefore,
            traitPlan.sequence[midTraitIndex].opponentEnergyAfter,
        )
        assertEquals("공격", battleParagraphStateLabel(BattleParagraphState.ATTACK))
        assertEquals("무승부", battleParagraphStateLabel(BattleParagraphState.DRAW))
        assertEquals("피격", battleParagraphStateLabel(BattleParagraphState.HIT))
        assertEquals(0.38f, battleArenaTintAlpha(BattleParagraphState.ATTACK, 0f), 0.001f)
        assertEquals(0.19f, battleArenaTintAlpha(BattleParagraphState.HIT, 0.5f), 0.001f)
        assertEquals(0f, battleArenaTintAlpha(BattleParagraphState.HIT, 1f), 0.001f)
        assertEquals(0f, battleArenaTintAlpha(BattleParagraphState.DRAW, 0f), 0.001f)
        assertEquals(AqRed, battleArenaTintColor(BattleSide.USER))
        assertEquals(
            androidx.compose.ui.graphics.Color(0xFF78A8D8),
            battleArenaTintColor(BattleSide.OPPONENT),
        )
        assertEquals(1f, battleDuelGaugeEnergyFraction(1_000f), 0.001f)
        assertEquals(0.5f, battleDuelGaugeEnergyFraction(500f), 0.001f)
        assertEquals(0f, battleDuelGaugeEnergyFraction(0f), 0.001f)
        assertEquals(0.5f, battleDuelGaugeEnergyFraction(700f, 1_400f), 0.001f)
        assertEquals(1f, battleDuelGaugeEnergyFraction(1_500f), 0.001f)
        assertEquals(0f, battleDuelGaugeEnergyFraction(-100f), 0.001f)

        val finalFractions = listOf(0f, 0.25f, 0.5f, 0.75f, 1f).map { progress ->
            battleDuelGaugeEnergyFractionAtProgress(
                energyBefore = 400,
                energyAfter = 0,
                maxEnergy = 1_000,
                progress = progress,
            )
        }
        listOf(0.4f, 0.3f, 0.2f, 0.1f, 0f).zip(finalFractions).forEach { (expected, actual) ->
            assertEquals(expected, actual, 0.0001f)
        }
        assertTrue(finalFractions.zipWithNext().all { (before, after) -> after < before })
    }

    @Test
    fun `blocked ordinary attack still shows attacker background without moving energy`() {
        val battle = ProjectionBattleResult(
            rounds = listOf(
                BattleRound(
                    number = 1,
                    userAction = BattleRoundAction(
                        actor = BattleSide.USER,
                        kind = BattleActionKind.BASIC_ATTACK,
                        damage = 0,
                        resolution = com.nullplaying.model.BattleActionResolution.MISSED,
                    ),
                    opponentAction = BattleRoundAction(
                        actor = BattleSide.OPPONENT,
                        kind = BattleActionKind.GUARD,
                    ),
                ),
            ),
        )
        val beats = battlePlaybackBeats(
            battle = battle,
            narrative = BattleQaNarrative(
                scenes = listOf(BattleQaScene(text = "공격이 방어에 막혔다.")),
            ),
        )

        assertEquals(1, beats.size)
        val attackBeat = beats.single()
        assertEquals(BattleParagraphState.DRAW, attackBeat.userState)
        assertEquals(attackBeat.userEnergyBefore, attackBeat.userEnergyAfter)
        assertEquals(attackBeat.opponentEnergyBefore, attackBeat.opponentEnergyAfter)
        assertEquals(0.38f, battleArenaTintAlpha(attackBeat.arenaTintSide, 0f), 0.001f)
        assertTrue(attackBeat.text.isNotBlank())
    }

    @Test
    fun `every non anchor zero damage attack receives a short playback sentence`() {
        val rounds = (1..6).map { number ->
            BattleRound(
                number = number,
                userAction = BattleRoundAction(
                    actor = BattleSide.USER,
                    kind = BattleActionKind.BASIC_ATTACK,
                    damage = 0,
                    resolution = com.nullplaying.model.BattleActionResolution.MISSED,
                ),
                opponentAction = BattleRoundAction(
                    actor = BattleSide.OPPONENT,
                    kind = BattleActionKind.GUARD,
                ),
            )
        }
        val beats = battlePlaybackBeats(
            battle = ProjectionBattleResult(rounds = rounds),
            narrative = BattleQaNarrative(
                languageTag = "en",
                scenes = listOf(
                    BattleQaScene(text = "The opening attack was blocked."),
                    BattleQaScene(text = "The final attack missed."),
                ),
            ),
        )

        assertEquals(6, beats.size)
        assertTrue(beats.all { it.text.isNotBlank() })
        assertEquals(6, beats.count { it.arenaTintSide == BattleSide.USER })
        assertEquals(0, beats.count { it.arenaTintSide == null })
        assertTrue(beats.all { it.userEnergyBefore == it.userEnergyAfter })
        assertTrue(beats.all { it.opponentEnergyBefore == it.opponentEnergyAfter })
    }

    @Test
    fun `decisive final paragraph drives the defeated side gauge to its endpoint`() {
        val battle = ProjectionBattleResult(
            outcome = com.nullplaying.model.BattleOutcome.USER_WIN,
            rounds = listOf(
                BattleRound(
                    number = 1,
                    userAction = BattleRoundAction(actor = BattleSide.USER, damage = 180),
                    opponentAction = BattleRoundAction(actor = BattleSide.OPPONENT, damage = 100),
                    userHpAfter = 900,
                    opponentHpAfter = 820,
                ),
            ),
        )
        val narrative = BattleQaNarrative(
            scenes = listOf(
                BattleQaScene(text = "공방이 기울었다. 결승의 일격이 이어졌다."),
            ),
        )

        val beats = battlePlaybackBeats(battle, narrative)

        assertEquals(900, beats.last().userEnergyAfter)
        assertEquals(0, beats.last().opponentEnergyAfter)
        assertEquals(0.9f, battleDuelGaugeEnergyFraction(900f), 0.001f)
        assertEquals(0f, battleDuelGaugeEnergyFraction(0f), 0.001f)
    }

    @Test
    fun `two thousand final exchanges drain the defeated energy evenly to zero`() {
        repeat(2_000) { sequence ->
            val userMax = 850 + sequence % 551
            val opponentMax = 850 + sequence * 7 % 551
            val userBefore = 1 + sequence * 11 % userMax
            val opponentBefore = 1 + sequence * 13 % opponentMax
            val userWins = sequence % 2 == 0
            val defeatedBefore = if (userWins) opponentBefore else userBefore
            val defeatedMax = if (userWins) opponentMax else userMax
            val fractions = (0..20).map { frame ->
                battleDuelGaugeEnergyFractionAtProgress(
                    energyBefore = defeatedBefore,
                    energyAfter = 0,
                    maxEnergy = defeatedMax,
                    progress = frame / 20f,
                )
            }

            assertEquals(
                defeatedBefore.toFloat() / defeatedMax,
                fractions.first(),
                0.0001f,
            )
            assertEquals(0f, fractions.last(), 0.0001f)
            assertTrue(fractions.zipWithNext().all { (before, after) -> after <= before })
            val step = fractions[1] - fractions[0]
            fractions.zipWithNext().forEach { (before, after) ->
                assertEquals(step, after - before, 0.0001f)
            }
        }
    }

    @Test
    fun `ordinary successful basic attacks only show authored narrative anchors`() {
        val rounds = (1..6).map { number ->
            BattleRound(
                number = number,
                userHpBefore = 1_010 - number * 10,
                opponentHpBefore = 1_010 - number * 10,
                userAction = BattleRoundAction(actor = BattleSide.USER, damage = 10),
                opponentAction = BattleRoundAction(actor = BattleSide.OPPONENT, damage = 10),
                userHpAfter = 1_000 - number * 10,
                opponentHpAfter = 1_000 - number * 10,
            )
        }
        val beats = battlePlaybackBeats(
            battle = ProjectionBattleResult(rounds = rounds),
            narrative = BattleQaNarrative(
                scenes = listOf(
                    BattleQaScene(text = "개막이 열렸다."),
                    BattleQaScene(text = "결승의 순간이 다가왔다."),
                ),
            ),
        )

        assertEquals(rounds.size * 2, beats.size)
        assertEquals(
            listOf("개막이 열렸다.", "결승의 순간이 다가왔다."),
            beats.map { it.text }.filter(String::isNotBlank),
        )
        assertEquals(2, beats.count { it.text.isNotBlank() })
        assertEquals(10, beats.count { it.text.isBlank() })
    }

    @Test
    fun `playback never lets a zero energy character attack`() {
        val survivorBattle = ProjectionBattleResult(
            outcome = BattleOutcome.USER_WIN,
            rounds = listOf(
                BattleRound(
                    number = 1,
                    userHpBefore = 100,
                    opponentHpBefore = 100,
                    userAction = BattleRoundAction(actor = BattleSide.USER, damage = 150),
                    opponentAction = BattleRoundAction(actor = BattleSide.OPPONENT, damage = 120),
                    userHpAfter = 1,
                    opponentHpAfter = 0,
                ),
            ),
        )
        val survivorBeats = battlePlaybackBeats(survivorBattle, BattleQaNarrative())

        assertEquals(2, survivorBeats.size)
        assertEquals(1, survivorBeats.first().userEnergyAfter)
        assertEquals(1, survivorBeats.last().userEnergyBefore)
        assertEquals(0, survivorBeats.last().opponentEnergyAfter)
        survivorBeats.forEach { beat ->
            val actorEnergy = if (beat.actorSide == BattleSide.USER) {
                beat.userEnergyBefore
            } else {
                beat.opponentEnergyBefore
            }
            assertTrue("A zero-energy actor reached playback: $beat", actorEnergy > 0)
        }

        val mutualKnockout = survivorBattle.copy(
            outcome = BattleOutcome.DRAW,
            rounds = listOf(
                survivorBattle.rounds.single().copy(
                    userAction = BattleRoundAction(actor = BattleSide.USER, damage = 100),
                    opponentAction = BattleRoundAction(actor = BattleSide.OPPONENT, damage = 100),
                    userHpAfter = 0,
                    opponentHpAfter = 0,
                ),
            ),
        )
        val drawBeats = battlePlaybackBeats(mutualKnockout, BattleQaNarrative())

        assertEquals(1, drawBeats.size)
        assertTrue(drawBeats.single().userEnergyBefore > 0)
        assertTrue(drawBeats.single().opponentEnergyBefore > 0)
        assertEquals(0, drawBeats.single().userEnergyAfter)
        assertEquals(0, drawBeats.single().opponentEnergyAfter)
    }

    @Test
    fun `lethal guarded hit names the defender and explains exhausted energy`() {
        val skill = BattleSkillSnapshot(
            skillId = "iron-slash",
            displayName = "강철 베기",
        )
        val battle = ProjectionBattleResult(
            user = NormalizedBattleProjection(
                displayName = "QA테스트",
                skills = listOf(skill),
                maxHp = 100,
            ),
            opponent = NormalizedBattleProjection(
                displayName = "라온",
                maxHp = 100,
            ),
            outcome = BattleOutcome.USER_WIN,
            rounds = listOf(
                BattleRound(
                    number = 1,
                    userHpBefore = 100,
                    opponentHpBefore = 30,
                    userAction = BattleRoundAction(
                        actor = BattleSide.USER,
                        kind = BattleActionKind.SKILL,
                        skillId = skill.skillId,
                        damage = 30,
                        resolution = BattleActionResolution.BLOCKED,
                    ),
                    opponentAction = BattleRoundAction(
                        actor = BattleSide.OPPONENT,
                        kind = BattleActionKind.GUARD,
                        resolution = BattleActionResolution.GUARDED,
                    ),
                    userHpAfter = 100,
                    opponentHpAfter = 0,
                ),
            ),
        )

        val beats = battlePlaybackBeats(
            battle = battle,
            narrative = BattleQaNarrative(languageTag = "ko"),
        )

        assertEquals(1, beats.size)
        assertEquals(30, beats.single().opponentEnergyBefore)
        assertEquals(0, beats.single().opponentEnergyAfter)
        assertTrue(beats.single().text.contains("라온이 QA테스트의 강철 베기를 막았지만 남은 힘이 다했다"))
        assertTrue(beats.none { it.text.contains("상대") })
    }

    @Test
    fun `failed finisher backlash is the final loss beat and explains defeat`() {
        val skill = BattleSkillSnapshot(
            skillId = "iron-slash",
            displayName = "강철 베기",
            finisherEligible = true,
        )
        val battle = ProjectionBattleResult(
            user = NormalizedBattleProjection(
                displayName = "QA테스트",
                skills = listOf(skill),
                maxHp = 100,
            ),
            opponent = NormalizedBattleProjection(
                displayName = "노아",
                maxHp = 100,
            ),
            outcome = BattleOutcome.USER_LOSS,
            rounds = listOf(
                BattleRound(
                    number = 7,
                    userHpBefore = 40,
                    opponentHpBefore = 80,
                    userAction = BattleRoundAction(
                        actor = BattleSide.USER,
                        kind = BattleActionKind.SKILL,
                        skillId = skill.skillId,
                        selfDamage = 25,
                        finisher = true,
                        finisherSucceeded = false,
                        resolution = BattleActionResolution.MISSED,
                    ),
                    opponentAction = BattleRoundAction(
                        actor = BattleSide.OPPONENT,
                        kind = BattleActionKind.SKILL,
                        damage = 20,
                    ),
                    userHpAfter = 0,
                    opponentHpAfter = 80,
                ),
            ),
        )

        val beats = battlePlaybackBeats(
            battle = battle,
            narrative = BattleQaNarrative(languageTag = "ko"),
        )

        assertEquals(2, beats.size)
        assertEquals(BattleSide.OPPONENT, beats.first().actorSide)
        assertEquals(20, beats.first().userEnergyAfter)
        assertEquals(BattleSide.USER, beats.last().actorSide)
        assertEquals(20, beats.last().userEnergyBefore)
        assertEquals(0, beats.last().userEnergyAfter)
        assertEquals(BattleSide.OPPONENT, beats.last().arenaTintSide)
        assertTrue(beats.last().text.contains("강철 베기가 빗나가자, QA테스트는 반동을 견디지 못했다"))
        beats.forEach { beat ->
            val actorEnergy = if (beat.actorSide == BattleSide.USER) {
                beat.userEnergyBefore
            } else {
                beat.opponentEnergyBefore
            }
            assertTrue("A zero-energy actor reached playback: $beat", actorEnergy > 0)
        }
    }

    @Test
    fun `playback text policy shows skills guards blocks evades and misses but hides plain hits`() {
        val plainHit = BattleRoundAction(
            actor = BattleSide.USER,
            kind = BattleActionKind.BASIC_ATTACK,
            damage = 100,
            resolution = com.nullplaying.model.BattleActionResolution.HIT,
        )

        assertEquals(false, battleActionTextVisible(plainHit))
        assertTrue(battleActionTextVisible(plainHit.copy(kind = BattleActionKind.SKILL)))
        assertEquals(
            false,
            battleActionTextVisible(plainHit.copy(kind = BattleActionKind.GUARD, damage = 0)),
        )
        listOf(
            com.nullplaying.model.BattleActionResolution.BLOCKED,
            com.nullplaying.model.BattleActionResolution.EVADED,
            com.nullplaying.model.BattleActionResolution.MISSED,
        ).forEach { resolution ->
            assertTrue(battleActionTextVisible(plainHit.copy(damage = 0, resolution = resolution)))
        }
    }

    @Test
    fun `long battle prose becomes a dynamic quote free sentence sequence including the result`() {
        val narrative = BattleQaNarrative(
            scenes = listOf(
                BattleQaScene(
                    text = "QA20이 \"여기까지다.\" 검을 들어 공격선을 막았다. " +
                        "세라는 한 발 비켜 다음 공격의 거리를 재었다.",
                ),
                BattleQaScene(
                    text = "세라가 빠르게 거리를 접자 QA20이 반격의 호흡을 잡았다. " +
                        "공격과 방어의 박자가 거칠게 엇갈렸다. " +
                        "앞서던 흐름은 짧은 반격으로 다시 팽팽해졌다.",
                ),
                BattleQaScene(
                    text = "두 사람의 마지막 공방이 맞부딪쳤다. " +
                        "QA20의 결정타가 방어를 무너뜨리며 QA20이 승리하고 세라가 패배했다.",
                ),
            ),
        )

        val lines = battlePlaybackLines(narrative)

        assertEquals(7, lines.size)
        assertEquals("QA20이 검을 들어 공격선을 막았다.", lines.first())
        assertTrue(lines.last().contains("QA20이 승리"))
        assertTrue(lines.last().contains("세라가 패배"))
        assertTrue(lines.none { it.contains('"') || it.contains('“') || it.contains('”') })
        assertTrue(lines.none { it.contains("여기까지다") })
    }

    @Test
    fun `empty battle narration falls back in the requested language`() {
        assertEquals(
            listOf("The exchange continued."),
            battlePlaybackLines(BattleQaNarrative(languageTag = "en", scenes = emptyList())),
        )
        assertEquals(
            listOf("攻防が続いた。"),
            battlePlaybackLines(BattleQaNarrative(languageTag = "ja", scenes = emptyList())),
        )
        assertEquals(
            listOf("공방이 이어졌다."),
            battlePlaybackLines(BattleQaNarrative(languageTag = "ko", scenes = emptyList())),
        )
    }

    @Test
    fun `a long narration sentence remains one playback unit`() {
        val sentence = "공기가 팽팽하게 조여드는 가운데 세라가 손끝에 집중한 마력을 내보내며 전장을 어둡게 물들인다."

        assertEquals(listOf(sentence), battleNarrationSentences(sentence))
    }

    @Test
    fun `local safety narration names the real skill and rotates weapon and armor details`() {
        val skill = BattleSkillSnapshot(
            skillId = "meteor-shot",
            displayName = "유성 사격",
            kind = BattleSkillKind.PIERCE,
        )
        val equipment = listOf(
            BattleEquipmentSnapshot(
                itemId = "star-bow",
                displayName = "별매듭 장궁",
                slot = BattleEquipmentSlot.WEAPON,
            ),
            BattleEquipmentSnapshot(
                itemId = "wind-coat",
                displayName = "순풍 외투",
                slot = BattleEquipmentSlot.BODY,
            ),
        )
        val action = BattleRoundAction(
            actor = BattleSide.USER,
            kind = BattleActionKind.SKILL,
            skillId = skill.skillId,
            damage = 120,
        )

        val variants = (0 until 6).map { variation ->
            localBattleActionText(
                name = "푸른별",
                action = action,
                skills = listOf(skill),
                equipment = equipment,
                variation = variation,
            )
        }

        assertTrue(variants.all { it.contains("푸른별") && it.contains("유성 사격") })
        assertTrue(variants.any { it.contains("별매듭 장궁") })
        assertTrue(variants.any { it.contains("순풍 외투") })
        assertEquals(6, variants.toSet().size)
    }

    @Test
    fun `v0 point contract stays visible and user-scoped`() {
        assertEquals(1_000, BATTLE_START_SCORE)
        assertEquals(24, BATTLE_K_FACTOR)
        assertEquals(BATTLE_RATING_K, BATTLE_K_FACTOR)
        assertEquals("시작 1000점 · K 24", battleScoreRuleLabel())
        assertTrue(battleUserOnlyScoreNotice().contains("내가 출전한 공식전"))
        assertTrue(battleUserOnlyScoreNotice().contains("매칭된 참가자의 점수는 변하지 않습니다"))
        assertEquals(
            "QA20 지침 균형 · 아린 지침 수호",
            battleGuidanceLabel("QA20", BattleStance.BALANCED, "아린", BattleStance.GUARD),
        )
    }

    @Test
    fun `entry capacity and placement contracts use the approved counts`() {
        assertEquals(5, BATTLE_DAILY_ENTRIES)
        assertEquals(10, BATTLE_ENTRY_DAILY_LIMIT)
        assertEquals(10, BATTLE_PLACEMENT_REQUIRED)
        assertEquals(BATTLE_TICKET_CAPACITY, BATTLE_DAILY_ENTRIES)
        assertEquals(BATTLE_PLACEMENT_BATTLES, BATTLE_PLACEMENT_REQUIRED)
        assertEquals("0/10", battlePlacementLabel(-1))
        assertEquals("3/10", battlePlacementLabel(3))
        assertEquals("10/10", battlePlacementLabel(12))
    }

    @Test
    fun `settled battle state and replay log survive local serialization`() {
        val history = BattlePreviewHistory(
            battleId = "battle-1",
            userName = "QA20",
            opponentName = "미라",
            opponentClass = "레인저",
            opponentLevel = 5,
            resultLabel = "승리",
            pointDelta = 12,
            summary = "마지막 공방",
            narrativeLines = listOf("첫 문장.", "둘째 문장."),
            skillNames = listOf("은총 메이스"),
            equipment = listOf(BattlePreviewHistoryEquipment("견습식 판금 흉갑", "고급")),
            traitNames = listOf("빈틈 추적자"),
            completedAtMillis = 1234L,
            narrativeSource = "qwen",
            narrativeModelValid = true,
        )
        val snapshot = BattleLocalSnapshot(
            gameEpochDay = 20_000L,
            entriesRemaining = 2,
            placementCompleted = 1,
            score = 1_012,
            wins = 1,
            history = listOf(history),
        )

        assertEquals(snapshot, decodeBattleLocalSnapshot(encodeBattleLocalSnapshot(snapshot)))
        assertEquals("이전 AI 서사", battleNarrativeSourceLabel("qwen", true))
        assertEquals("로컬 템플릿 서사", battleNarrativeSourceLabel("local_template", false))
    }

    @Test
    fun `growth resonance is an index with one final damage channel`() {
        assertEquals(100, battleResonanceIndex(99))
        assertEquals(104, battleResonanceIndex(104))
        assertEquals(108, battleResonanceIndex(109))
        assertEquals(0, battleResonanceDamageBonusTenths(100))
        assertEquals(36, battleResonanceDamageBonusTenths(104))
        assertEquals(72, battleResonanceDamageBonusTenths(108))
        assertEquals("+3.6%", battleResonanceDamageBonusLabel(104))
        assertEquals("+7.2%", battleResonanceDamageBonusLabel(108))
        assertEquals(
            "지수 1점당 최종 피해 +0.9% · 최대 +7.2%",
            battleResonanceRuleLabel(),
        )
    }

    @Test
    fun `battle menu mirrors the main battle card height and keeps ten inline records`() {
        assertEquals(218, BATTLE_MENU_STAGE_HEIGHT_DP)
        assertEquals(64, RANKING_ENTRY_MENU_MIN_HEIGHT_DP)
        assertEquals(15, RANKING_ENTRY_MENU_TITLE_FONT_SIZE_SP)
        assertEquals(12, RANKING_ENTRY_MENU_DETAIL_FONT_SIZE_SP)
        assertEquals(40, COMPACT_RANKING_ENTRY_MENU_HEIGHT_DP)
        assertEquals(12, COMPACT_RANKING_ENTRY_MENU_TITLE_FONT_SIZE_SP)
        assertEquals(9, COMPACT_RANKING_ENTRY_MENU_DETAIL_FONT_SIZE_SP)
        assertEquals(20, RANKING_PAGE_TITLE_FONT_SIZE_SP)
        assertEquals(12, RANKING_PAGE_SUBTITLE_FONT_SIZE_SP)
        assertEquals(10, BATTLE_RECENT_HISTORY_LIMIT)
        val history = (1..12).map { index ->
            BattlePreviewHistory(
                battleId = "battle-$index",
                userName = "QA20",
                opponentName = "상대 $index",
                opponentClass = "파이터",
                opponentLevel = index.toLong(),
                resultLabel = "승리",
                pointDelta = 10,
                summary = "요약",
                narrativeLines = listOf("기록"),
                skillNames = emptyList(),
                equipment = emptyList(),
                traitNames = emptyList(),
                completedAtMillis = index.toLong(),
            )
        }

        assertEquals((1..10).map { "battle-$it" }, recentBattleHistory(history).map { it.battleId })
        assertEquals("배치 3/10", battleRankingEntryDetail(score = 1_024, placementCompleted = 3))
        assertEquals("1024점", battleRankingEntryDetail(score = 1_024, placementCompleted = 10))
        assertEquals(220, RANKING_PAGE_ENTER_DURATION_MILLIS)
        assertEquals(190, RANKING_PAGE_EXIT_DURATION_MILLIS)
        assertEquals(108, rankingPageEnterOffset(targetVisible = true, width = 1_080))
        assertEquals(-108, rankingPageEnterOffset(targetVisible = false, width = 1_080))
        assertEquals(-77, rankingPageExitOffset(targetVisible = true, width = 1_080))
        assertEquals(77, rankingPageExitOffset(targetVisible = false, width = 1_080))
    }

    @Test
    fun `trait removal communicates the full one hour delay`() {
        assertEquals(60, BATTLE_TRAIT_REMOVAL_MINUTES)
        assertEquals(
            BATTLE_TRAIT_REMOVAL_MILLIS,
            BATTLE_TRAIT_REMOVAL_MINUTES * 60_000L,
        )
        assertEquals("제거까지 1시간", battleTraitRemovalLabel(BATTLE_TRAIT_REMOVAL_MINUTES))
        assertEquals("제거까지 59분", battleTraitRemovalLabel(59))
        assertEquals("제거 완료", battleTraitRemovalLabel(0))
    }

    @Test
    fun `all three approved stances remain available`() {
        assertEquals(
            listOf("맹공", "균형", "수호"),
            BattleStance.entries.map { it.label },
        )
        assertEquals(
            listOf("ASSAULT", "BALANCED", "GUARD"),
            BattleStance.entries.map { it.name },
        )
        assertEquals(
            BattleGuidance.entries.map { it.name },
            BattleStance.entries.map { it.name },
        )
    }

    @Test
    fun `bottom navigation shares one label baseline and icon layout`() {
        assertEquals(74, BOTTOM_MENU_HEIGHT_DP)
        assertEquals(8, BOTTOM_MENU_LABEL_BOTTOM_PADDING_DP)
        assertEquals(9, BOTTOM_MENU_ICON_TOP_OFFSET_DP)
        assertEquals(R.drawable.ic_swords, MenuTab.BATTLE.iconResourceId)
        assertEquals(null, MenuTab.BATTLE.icon)
    }

    @Test
    fun `competitive data remains private until all ten placement battles finish`() {
        assertEquals(false, battleCompetitiveDataVisible(9))
        assertEquals(true, battleCompetitiveDataVisible(10))
        assertEquals(true, battlePlacementProgressVisible(9))
        assertEquals(false, battlePlacementProgressVisible(10))
        assertEquals(false, battlePlacementProgressVisible(11))
        assertEquals("배치 중", battleScoreDisplay(score = 1_048, placementCompleted = 9))
        assertEquals("1048", battleScoreDisplay(score = 1_048, placementCompleted = 10))
        assertEquals("0", battleScoreDisplay(score = -1, placementCompleted = 10))
        assertEquals(
            "비공개",
            battlePointDeltaDisplay(13, placementCompleted = 9, language = AppLanguage.KOREAN),
        )
        assertEquals(
            "Hidden",
            battlePointDeltaDisplay(13, placementCompleted = 9, language = AppLanguage.ENGLISH),
        )
        assertEquals(
            "非公開",
            battlePointDeltaDisplay(13, placementCompleted = 9, language = AppLanguage.JAPANESE),
        )
        assertEquals("+13", battleSettledPointDeltaDisplay(13))
        assertEquals("-11", battleSettledPointDeltaDisplay(-11))
        assertEquals("0", battleSettledPointDeltaDisplay(0))
        assertEquals("+13", battlePointDeltaDisplay(pointDelta = 13, placementCompleted = 10))
        assertEquals("-11", battlePointDeltaDisplay(pointDelta = -11, placementCompleted = 10))
    }

    @Test
    fun `ranking uses the server contract tie break order`() {
        assertEquals(
            "순위는 시즌 점수, 전적, 해당 점수 도달 시각 순으로 정렬됩니다.",
            BATTLE_RANKING_SORT_NOTICE,
        )
    }

    @Test
    fun `arena ranking values are localized without translating player names`() {
        assertEquals("12위", battleArenaRankLabel(12, AppLanguage.KOREAN))
        assertEquals("#12", battleArenaRankLabel(12, AppLanguage.ENGLISH))
        assertEquals("12位", battleArenaRankLabel(12, AppLanguage.JAPANESE))
        assertEquals("1000위 밖", battleArenaRankLabel(1_001, AppLanguage.KOREAN))
        assertEquals("Outside 1000", battleArenaRankLabel(1_001, AppLanguage.ENGLISH))
        assertEquals("1000位圏外", battleArenaRankLabel(1_001, AppLanguage.JAPANESE))
        assertEquals("8승 2패 1무", battleArenaRecordLabel(8, 2, 1, AppLanguage.KOREAN))
        assertEquals("8 W 2 L 1 D", battleArenaRecordLabel(8, 2, 1, AppLanguage.ENGLISH))
        assertEquals("8勝 2敗 1分", battleArenaRecordLabel(8, 2, 1, AppLanguage.JAPANESE))
        assertEquals("1480점", battleArenaScoreLabel(1_480, AppLanguage.KOREAN))
        assertEquals("1480 pts", battleArenaScoreLabel(1_480, AppLanguage.ENGLISH))
        assertEquals("1480点", battleArenaScoreLabel(1_480, AppLanguage.JAPANESE))
        assertEquals("0점", battleArenaScoreLabel(-1, AppLanguage.KOREAN))
        assertEquals("0 pts", battleArenaScoreLabel(-1, AppLanguage.ENGLISH))
        assertEquals("0点", battleArenaScoreLabel(-1, AppLanguage.JAPANESE))
        assertEquals(BattleOutcome.USER_LOSS, arenaHistoryOutcome("패배"))
        assertEquals(BattleOutcome.USER_LOSS, arenaHistoryOutcome("Defeat"))
        assertEquals(BattleOutcome.USER_LOSS, arenaHistoryOutcome("敗北"))
        assertEquals(BattleOutcome.USER_LOSS, arenaHistoryOutcomeForPresentation("패배", 0))
        assertEquals(BattleOutcome.DRAW, arenaHistoryOutcomeForPresentation("legacy-unknown", 0))
    }

    @Test
    fun `enabled release exposes arena redirects saved item tabs and uses full battle body`() {
        assertTrue(battlePreviewMenuEnabled(isDebugBuild = false, serverMatchingEnabled = true))
        assertEquals(
            listOf("MAIN", "CHARACTER", "BATTLE", "ITEMS", "QUEST"),
            visibleMenuTabsForBuild(isDebugBuild = false, serverMatchingEnabled = true).map { it.name },
        )
        assertEquals(
            MenuTab.ITEMS,
            menuTabAfterBuildRedirect(MenuTab.EQUIPMENT, isDebugBuild = false, serverMatchingEnabled = true),
        )
        assertEquals(
            MenuTab.ITEMS,
            menuTabAfterBuildRedirect(MenuTab.BAG, isDebugBuild = false, serverMatchingEnabled = true),
        )
        assertFalse(
            adventurePanelVisibleForBuild(MenuTab.BATTLE, isDebugBuild = false, serverMatchingEnabled = true),
        )
        assertTrue(
            adventurePanelVisibleForBuild(MenuTab.MAIN, isDebugBuild = false, serverMatchingEnabled = true),
        )
    }

    @Test
    fun `battle preview menu and item consolidation are debug only`() {
        assertTrue(battlePreviewMenuEnabled(isDebugBuild = true))
        assertEquals(false, battlePreviewMenuEnabled(isDebugBuild = false))
        assertEquals(
            listOf("MAIN", "CHARACTER", "BATTLE", "ITEMS", "QUEST"),
            visibleMenuTabsForBuild(isDebugBuild = true).map { it.name },
        )
        assertEquals(
            listOf("MAIN", "CHARACTER", "EQUIPMENT", "BAG", "QUEST"),
            visibleMenuTabsForBuild(isDebugBuild = false).map { it.name },
        )
        assertEquals(
            MenuTab.ITEMS,
            menuTabAfterBuildRedirect(MenuTab.EQUIPMENT, isDebugBuild = true),
        )
        assertEquals(
            MenuTab.EQUIPMENT,
            menuTabAfterBuildRedirect(MenuTab.EQUIPMENT, isDebugBuild = false),
        )
        assertEquals(
            MenuTab.BAG,
            menuTabAfterBuildRedirect(MenuTab.BAG, isDebugBuild = false),
        )
        assertEquals(visibleMenuTabsForBuild(BuildConfig.DEBUG), visibleMenuTabs)
    }

    @Test
    fun `battle preview alone receives a dedicated body without the adventure panel`() {
        assertEquals(
            false,
            adventurePanelVisibleForBuild(MenuTab.BATTLE, isDebugBuild = true),
        )
        assertTrue(adventurePanelVisibleForBuild(MenuTab.MAIN, isDebugBuild = true))
        assertTrue(adventurePanelVisibleForBuild(MenuTab.ITEMS, isDebugBuild = true))
        assertTrue(adventurePanelVisibleForBuild(MenuTab.BATTLE, isDebugBuild = false))
        assertTrue(
            visibleMenuTabsForBuild(isDebugBuild = false).all { tab ->
                adventurePanelVisibleForBuild(tab, isDebugBuild = false)
            },
        )
    }

    @Test
    fun `acknowledging a result permits the next daily entry`() {
        val initial = BattlePreviewEntryState(entriesRemaining = 3, resultVisible = false)
        assertTrue(canStartPreviewBattle(initial))

        val afterFirstBattle = startPreviewBattle(initial, selectedStance = BattleStance.BALANCED)
        assertEquals(3, afterFirstBattle.entriesRemaining)
        assertTrue(afterFirstBattle.resultVisible)
        assertEquals(false, canStartPreviewBattle(afterFirstBattle))

        val afterAcknowledgement = acknowledgePreviewBattleResult(afterFirstBattle)
        assertEquals(2, afterAcknowledgement.entriesRemaining)
        assertEquals(false, afterAcknowledgement.resultVisible)
        assertTrue(canStartPreviewBattle(afterAcknowledgement))

        val afterSecondBattle = startPreviewBattle(
            afterAcknowledgement,
            selectedStance = BattleStance.GUARD,
        )
        assertEquals(2, afterSecondBattle.entriesRemaining)
        assertTrue(afterSecondBattle.resultVisible)

        val afterSecondAcknowledgement = acknowledgePreviewBattleResult(afterSecondBattle)
        assertEquals(1, afterSecondAcknowledgement.entriesRemaining)
        assertTrue(canStartPreviewBattle(afterSecondAcknowledgement))
        val afterThirdBattle = startPreviewBattle(
            afterSecondAcknowledgement,
            selectedStance = BattleStance.ASSAULT,
        )
        assertEquals(1, afterThirdBattle.entriesRemaining)
        assertTrue(afterThirdBattle.resultVisible)

        val afterThirdAcknowledgement = acknowledgePreviewBattleResult(afterThirdBattle)
        assertEquals(0, afterThirdAcknowledgement.entriesRemaining)
        assertEquals(false, canStartPreviewBattle(afterThirdAcknowledgement))
        assertEquals(
            afterThirdAcknowledgement,
            startPreviewBattle(afterThirdAcknowledgement, selectedStance = BattleStance.BALANCED),
        )
    }

    @Test
    fun `battle captures assault guidance across acknowledgement`() {
        val initial = BattlePreviewEntryState(
            entriesRemaining = 3,
            resultVisible = false,
            lastBattleStance = BattleStance.BALANCED,
        )

        val afterBattle = startPreviewBattle(initial, selectedStance = BattleStance.ASSAULT)
        assertEquals(BattleStance.ASSAULT, afterBattle.lastBattleStance)
        assertEquals("파이터", battleHeroClassLabel(com.nullplaying.model.BattleHeroClass.WARRIOR))
        assertEquals("시프", battleHeroClassLabel(com.nullplaying.model.BattleHeroClass.ROGUE))

        val afterResultAcknowledgement = acknowledgePreviewBattleResult(afterBattle)
        assertEquals(false, afterResultAcknowledgement.resultVisible)
        assertEquals(BattleStance.ASSAULT, afterResultAcknowledgement.lastBattleStance)

        val afterHistoryTabMove = acknowledgePreviewBattleResult(afterResultAcknowledgement)
        assertEquals(BattleStance.ASSAULT, afterHistoryTabMove.lastBattleStance)
    }
}
