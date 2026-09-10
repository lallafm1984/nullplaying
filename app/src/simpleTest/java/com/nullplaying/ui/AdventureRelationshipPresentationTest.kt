package com.nullplaying.ui

import com.nullplaying.localization.AppLanguage
import com.nullplaying.model.AdventureEncounterCandidate
import com.nullplaying.model.AdventureEventItemReward
import com.nullplaying.model.AdventureEventOutcome
import com.nullplaying.model.AdventureEventRewardKind
import com.nullplaying.model.AdventureEventStat
import com.nullplaying.model.AdventureRelationshipBattleKind
import com.nullplaying.model.AdventureRelationshipResult
import com.nullplaying.model.AdventureRelationshipRun
import com.nullplaying.model.HeroClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AdventureRelationshipPresentationTest {
    @Test
    fun `relationship incident uses five second discovery and action before battle`() {
        val run = run(battleDurationMillis = 15_000L)

        assertEquals(RelationshipIncidentDisplayStage.DISCOVERY, relationshipIncidentDisplayWindow(run, 4_999L).stage)
        assertEquals(RelationshipIncidentDisplayStage.ACTION, relationshipIncidentDisplayWindow(run, 5_000L).stage)
        assertEquals(RelationshipIncidentDisplayStage.ACTION, relationshipIncidentDisplayWindow(run, 9_999L).stage)
        assertEquals(RelationshipIncidentDisplayStage.BATTLE, relationshipIncidentDisplayWindow(run, 10_000L).stage)
        assertEquals(25_000L, relationshipIncidentDisplayWindow(run, 20_000L).endsAt)
    }

    @Test
    fun `relationship equipment receipt uses the standard bag language and hides score`() {
        val result = AdventureRelationshipResult(
            run = run(
                rewardKind = AdventureEventRewardKind.ITEM,
                itemReward = AdventureEventItemReward.EQUIPMENT,
                scoreBefore = 28,
                scoreDelta = 4,
            ),
            occurredAt = 30_000L,
            experienceAwarded = 0L,
            scoreAfter = 32,
            progressAdded = 1L,
            itemName = "은빛 나침반",
            itemRarity = "고급",
            itemEquipped = false,
            rewardKind = AdventureEventRewardKind.ITEM,
        )

        AppLanguage.entries.forEach { language ->
            val receipt = relationshipReceiptPresentation(result, language)
            assertEquals(AdventureEventReceiptReward.ITEM, receipt.reward)
            assertEquals(AdventureEventItemReceiptState.ACQUIRED, receipt.itemState)
            assertEquals(result.run.candidate.displayName, receipt.subjectName)
            assertEquals(relationshipTierLabel(result.tier, language), receipt.outcomeLabel)
            val text = listOf(receipt.narrative, receipt.rewardSummary, receipt.penaltySummary).joinToString(" ")
            assertFalse(text.contains("28"))
            assertFalse(text.contains("32"))
            assertFalse(text.contains("%"))
        }
        assertEquals("가방에 보관했습니다", relationshipReceiptPresentation(result, AppLanguage.KOREAN).rewardSummary)
    }

    @Test
    fun `relationship result presents exactly the selected xp or gold reward without probability`() {
        val xp = AdventureRelationshipResult(
            run = run(rewardKind = AdventureEventRewardKind.EXPERIENCE),
            occurredAt = 10_000L,
            experienceAwarded = 42L,
            scoreAfter = 2,
            progressAdded = 1L,
            rewardKind = AdventureEventRewardKind.EXPERIENCE,
        )
        val gold = AdventureRelationshipResult(
            run = run(rewardKind = AdventureEventRewardKind.GOLD),
            occurredAt = 10_000L,
            experienceAwarded = 0L,
            goldAwarded = 70L,
            scoreAfter = 2,
            progressAdded = 1L,
            rewardKind = AdventureEventRewardKind.GOLD,
        )

        AppLanguage.entries.forEach { language ->
            val xpReceipt = relationshipReceiptPresentation(xp, language)
            val goldReceipt = relationshipReceiptPresentation(gold, language)
            assertEquals(AdventureEventReceiptReward.EXPERIENCE, xpReceipt.reward)
            assertEquals("EXP +42", xpReceipt.rewardSummary)
            assertEquals(AdventureEventReceiptReward.GOLD, goldReceipt.reward)
            assertEquals("+70 G", goldReceipt.rewardSummary)
            listOf(xpReceipt, goldReceipt).forEach { receipt ->
                val text = listOf(receipt.narrative, receipt.rewardSummary, receipt.penaltySummary).joinToString(" ")
                assertFalse(text.contains("%"))
                assertFalse(text.contains("확률"))
                assertTrue(receipt.reward != AdventureEventReceiptReward.NONE)
            }
        }
    }

    @Test
    fun `fixed battle window traverses the full arena ledger`() {
        val first = beat(durationMillis = 1_500L)
        val second = beat(durationMillis = 1_400L)
        val terminal = beat(durationMillis = 0L, terminal = true)
        val timeline = ArenaLiveTimeline(listOf(first, second, terminal))

        assertSame(first, relationshipBattleFrame(timeline, 0f)?.beat)
        assertSame(second, relationshipBattleFrame(timeline, .75f)?.beat)
        assertSame(terminal, relationshipBattleFrame(timeline, 1f)?.beat)
    }

    private fun beat(durationMillis: Long, terminal: Boolean = false) = ArenaLiveBeat(
        turn = 1,
        type = null,
        sequences = emptyList(),
        before = emptyMap(),
        after = emptyMap(),
        message = null,
        logs = emptyList(),
        durationMillis = durationMillis,
        terminal = terminal,
    )

    private fun run(
        battleDurationMillis: Long = 0L,
        rewardKind: AdventureEventRewardKind = AdventureEventRewardKind.EXPERIENCE,
        itemReward: AdventureEventItemReward = AdventureEventItemReward.NONE,
        scoreBefore: Int = 0,
        scoreDelta: Int = 2,
    ) = AdventureRelationshipRun(
        sequence = 1L,
        sceneId = "directions",
        approachId = "read_signs",
        candidate = AdventureEncounterCandidate("peer-1", "바람별", HeroClass.RANGER, 10L, 120L),
        snapshotId = "daily-1",
        startedAt = 0L,
        startedActiveMillis = 0L,
        durationMillis = 10_000L + battleDurationMillis,
        heroLevel = 10L,
        primaryStat = AdventureEventStat.INT,
        secondaryStat = AdventureEventStat.WIS,
        primaryValue = 14L,
        secondaryValue = 13L,
        successBasisPoints = 5_000,
        partialBasisPoints = 2_000,
        roll = 1_000,
        outcome = AdventureEventOutcome.SUCCESS,
        scoreBefore = scoreBefore,
        scoreDelta = scoreDelta,
        experienceReward = 0L,
        rewardSeed = 3L,
        encounterLevel = 10L,
        labyrinthDepth = 0L,
        baseExperienceBudget = 56L,
        reunion = false,
        rewardKind = rewardKind,
        itemReward = itemReward,
        battleKind = if (battleDurationMillis > 0L) AdventureRelationshipBattleKind.SPAR else AdventureRelationshipBattleKind.NONE,
        battleDurationMillis = battleDurationMillis,
    )
}
