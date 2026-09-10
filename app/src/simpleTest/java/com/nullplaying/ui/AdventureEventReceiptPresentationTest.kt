package com.nullplaying.ui

import com.nullplaying.engine.AdventureEventEngine
import com.nullplaying.localization.AppLanguage
import com.nullplaying.model.AdventureEventItemReward
import com.nullplaying.model.AdventureEventOutcome
import com.nullplaying.model.AdventureEventResult
import com.nullplaying.model.AdventureEventBattleRewardKind
import com.nullplaying.model.AdventureEventRewardKind
import com.nullplaying.model.AdventureEventRun
import com.nullplaying.model.AdventureEventStat
import com.nullplaying.model.MonsterGrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdventureEventReceiptPresentationTest {
    @Test
    fun `event battle announcement names the grade without exposing a probability`() {
        listOf(
            MonsterGrade.ELITE to "정예 몬스터가 길을 막았습니다",
            MonsterGrade.BOSS to "보스 몬스터가 모습을 드러냈습니다",
        ).forEach { (grade, expected) ->
            val base = result(AdventureEventRewardKind.GOLD)
            val pending = base.copy(
                run = base.run.copy(
                    battleGrade = grade,
                    battleRewardKind = AdventureEventBattleRewardKind.GOLD,
                ),
            )
            val presentation = adventureEventReceiptPresentation(pending, AppLanguage.KOREAN)
            assertEquals(AdventureEventReceiptReward.BATTLE, presentation.reward)
            assertEquals(expected, presentation.rewardSummary)
            assertFalse(presentation.rewardSummary.contains("%"))
            assertFalse(presentation.wasGranted())
        }
    }

    @Test
    fun `resolved zero gold battle receipt is completed instead of returning to pending battle`() {
        val base = result(AdventureEventRewardKind.GOLD)
        val resolved = base.copy(
            run = base.run.copy(
                eventId = "abandoned_giant_hive",
                battleGrade = MonsterGrade.BOSS,
                battleRewardKind = AdventureEventBattleRewardKind.GOLD,
            ),
            battleResolved = true,
        )

        val expectedOutcome = mapOf(
            AppLanguage.KOREAN to "전투 승리",
            AppLanguage.ENGLISH to "Victory",
            AppLanguage.JAPANESE to "戦闘勝利",
        )
        AppLanguage.entries.forEach { language ->
            val presentation = adventureEventReceiptPresentation(resolved, language)
            assertEquals(AdventureEventReceiptReward.NONE, presentation.reward)
            assertFalse(presentation.wasGranted())
            assertEquals(expectedOutcome.getValue(language), presentation.outcomeLabel)
            assertFalse(presentation.narrative.contains("다른 길"))
            assertFalse(presentation.narrative.contains("Another way"))
            assertFalse(presentation.narrative.contains("別の道"))
        }
    }

    @Test
    fun `event monsters use their authored English and Japanese names`() {
        val definition = AdventureEventEngine.all.first { it.battleRule != null }
        val monsterName = requireNotNull(definition.battleRule).monsterName

        assertEquals(
            monsterName.en,
            localizedMonsterName(
                text = monsterName.ko,
                baseName = monsterName.ko,
                language = AppLanguage.ENGLISH,
                catalogId = "event:${definition.id}",
            ),
        )
        assertEquals(
            monsterName.ja,
            localizedMonsterName(
                text = monsterName.ko,
                baseName = monsterName.ko,
                language = AppLanguage.JAPANESE,
                catalogId = "event:${definition.id}",
            ),
        )
    }

    @Test
    fun `event result exposes one selected reward and never structural progress`() {
        val cases = listOf(
            result(AdventureEventRewardKind.EXPERIENCE, experience = 41L) to "EXP +41",
            result(AdventureEventRewardKind.GOLD, gold = 27L) to "+27 G",
            result(
                AdventureEventRewardKind.ITEM,
                itemReward = AdventureEventItemReward.TROPHY,
                itemName = "유적의 작은 장식",
                itemCount = 2,
            ) to "가방에 보관했습니다",
            result(AdventureEventRewardKind.ROUTE, routeDelayMillis = -1_500L, routeRewardUses = 4) to "몬스터 조우 4회 · 각 1.5초 단축",
        )

        cases.forEach { (eventResult, expected) ->
            val presentation = adventureEventReceiptPresentation(eventResult, AppLanguage.KOREAN)
            assertEquals(expected, presentation.rewardSummary)
            assertFalse(presentation.rewardSummary.contains("진행"))
            assertFalse(presentation.rewardSummary.contains("%"))
            assertFalse(presentation.rewardSummary.contains("roll", ignoreCase = true))
        }
    }

    @Test
    fun `failed event shows no reward and keeps its delay as a penalty`() {
        val eventResult = result(
            rewardKind = AdventureEventRewardKind.GOLD,
            outcome = AdventureEventOutcome.FAILURE,
            routeDelayMillis = 2_000L,
        )

        AppLanguage.entries.forEach { language ->
            val presentation = adventureEventReceiptPresentation(eventResult, language)
            assertEquals(AdventureEventReceiptReward.NONE, presentation.reward)
            assertTrue(presentation.rewardSummary.isNotBlank())
            assertTrue(presentation.penaltySummary.isNotBlank())
            assertFalse(presentation.penaltySummary.contains("%"))
        }
    }

    @Test
    fun `legacy route reward without use metadata is presented as at least two encounters`() {
        val eventResult = result(
            rewardKind = AdventureEventRewardKind.ROUTE,
            routeDelayMillis = -1_500L,
            routeRewardUses = 0,
        )

        assertEquals(
            "몬스터 조우 2회 · 각 1.5초 단축",
            adventureEventReceiptPresentation(eventResult, AppLanguage.KOREAN).rewardSummary,
        )
    }

    @Test
    fun `item receipt distinguishes acquired omitted and full bag states`() {
        val base = result(
            rewardKind = AdventureEventRewardKind.ITEM,
            itemReward = AdventureEventItemReward.TROPHY,
            itemName = "유적의 작은 장식",
            itemCount = 1,
        )

        assertEquals(
            AdventureEventItemReceiptState.ACQUIRED,
            adventureEventReceiptPresentation(base, AppLanguage.KOREAN).itemState,
        )
        assertEquals(
            AdventureEventItemReceiptState.OMITTED,
            adventureEventReceiptPresentation(
                base.copy(itemName = "", actualItemCount = 0, itemOmittedByTrait = true),
                AppLanguage.KOREAN,
            ).itemState,
        )
        assertEquals(
            AdventureEventItemReceiptState.BAG_FULL,
            adventureEventReceiptPresentation(
                base.copy(itemName = "", actualItemCount = 0, itemOmittedByTrait = false),
                AppLanguage.KOREAN,
            ).itemState,
        )
        assertTrue(adventureEventReceiptPresentation(base, AppLanguage.KOREAN).wasGranted())
        assertFalse(
            adventureEventReceiptPresentation(
                base.copy(itemName = "", actualItemCount = 0, itemOmittedByTrait = true),
                AppLanguage.KOREAN,
            ).wasGranted(),
        )
        assertFalse(
            adventureEventReceiptPresentation(
                base.copy(itemName = "", actualItemCount = 0, itemOmittedByTrait = false),
                AppLanguage.KOREAN,
            ).wasGranted(),
        )
    }

    @Test
    fun `event trophies and unequipped equipment use the ordinary bag status`() {
        val trophy = result(
            rewardKind = AdventureEventRewardKind.ITEM,
            itemReward = AdventureEventItemReward.TROPHY,
            itemName = "유적의 작은 장식",
            itemCount = 1,
        )
        val unequippedEquipment = trophy.copy(
            run = trophy.run.copy(itemReward = AdventureEventItemReward.EQUIPMENT),
            itemName = "낡은 훈련식 정찰 후드",
        )
        val equippedEquipment = unequippedEquipment.copy(itemEquipped = true)

        assertEquals(
            "가방에 보관했습니다",
            adventureEventReceiptPresentation(trophy, AppLanguage.KOREAN).rewardSummary,
        )
        assertEquals(
            "가방에 보관했습니다",
            adventureEventReceiptPresentation(unequippedEquipment, AppLanguage.KOREAN).rewardSummary,
        )
        assertEquals(
            "새 장비로 바로 장착했습니다",
            adventureEventReceiptPresentation(equippedEquipment, AppLanguage.KOREAN).rewardSummary,
        )
    }

    @Test
    fun `event trophy name uses the authored translation`() {
        val authoredName = AdventureEventEngine.definition("ruins").itemName
        val itemResult = result(
            rewardKind = AdventureEventRewardKind.ITEM,
            itemReward = AdventureEventItemReward.TROPHY,
            itemName = authoredName.ko,
            itemCount = 1,
        )

        assertEquals(authoredName.en, eventItemDisplayName(itemResult, itemResult.itemName, AppLanguage.ENGLISH))
        assertEquals(authoredName.ja, eventItemDisplayName(itemResult, itemResult.itemName, AppLanguage.JAPANESE))
    }

    @Test
    fun `legacy multi reward run is reduced to one displayed category`() {
        val legacy = result(
            rewardKind = AdventureEventRewardKind.UNSPECIFIED,
            experience = 40L,
            gold = 20L,
            itemReward = AdventureEventItemReward.TROPHY,
            itemName = "유적의 작은 장식",
            itemCount = 1,
            routeDelayMillis = -2_000L,
        )

        val presentation = adventureEventReceiptPresentation(legacy, AppLanguage.KOREAN)
        assertEquals(AdventureEventReceiptReward.ITEM, presentation.reward)
        assertEquals("가방에 보관했습니다", presentation.rewardSummary)
    }

    private fun result(
        rewardKind: AdventureEventRewardKind,
        outcome: AdventureEventOutcome = AdventureEventOutcome.SUCCESS,
        experience: Long = 0L,
        gold: Long = 0L,
        itemReward: AdventureEventItemReward = AdventureEventItemReward.NONE,
        itemName: String = "",
        itemCount: Int = 0,
        itemEquipped: Boolean = false,
        routeDelayMillis: Long = 0L,
        routeRewardUses: Int = 0,
    ): AdventureEventResult {
        val run = AdventureEventRun(
            sequence = 1L,
            eventId = "ruins",
            approachId = "decode",
            startedAt = 1_000L,
            durationMillis = 20_000L,
            heroLevel = 3L,
            primaryStat = AdventureEventStat.INT,
            secondaryStat = AdventureEventStat.WIS,
            primaryValue = 12L,
            secondaryValue = 11L,
            successBasisPoints = 5_000,
            partialBasisPoints = 2_500,
            roll = 1,
            outcome = outcome,
            experienceReward = experience,
            goldReward = gold,
            itemReward = itemReward,
            routeDelayMillis = routeDelayMillis,
            rewardSeed = 7L,
            rewardKind = rewardKind,
            routeRewardUses = routeRewardUses,
        )
        return AdventureEventResult(
            run = run,
            occurredAt = 21_000L,
            experienceAwarded = experience,
            goldAwarded = gold,
            itemName = itemName,
            itemRarity = if (itemName.isBlank()) "" else "고급",
            itemEquipped = itemEquipped,
            progressAdded = 1L,
            actualItemCount = itemCount,
        )
    }
}
