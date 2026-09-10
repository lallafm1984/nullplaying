package com.nullplaying.ui

import com.nullplaying.localization.AppLanguage
import com.nullplaying.model.AdventureEventRewardKind
import com.nullplaying.model.AdventureRelationshipTier
import com.nullplaying.model.RecentAdventureEvent
import com.nullplaying.model.RecentAdventureEventMetadata
import com.nullplaying.model.RecentAdventureEventType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelationshipRecentRewardPresentationTest {
    @Test
    fun `relationship equipment log names the item and uses the normal equipped wording`() {
        val event = relationshipEvent(
            rewardKind = AdventureEventRewardKind.ITEM,
            itemName = "새벽의 검 +3",
            itemEquipped = true,
        )

        val detail = recentAdventureEventPresentation(event, AppLanguage.KOREAN).detail

        assertTrue(detail.contains("새벽의 검 +3"))
        assertTrue(detail.contains("획득"))
        assertTrue(detail.contains("새 장비로 장착"))
        assertFalse(detail.contains("아이템 +1"))
    }

    @Test
    fun `relationship equipment kept in inventory says stored in bag`() {
        val event = relationshipEvent(
            rewardKind = AdventureEventRewardKind.ITEM,
            itemName = "오래된 투구 +2",
            itemEquipped = false,
        )

        val detail = recentAdventureEventPresentation(event, AppLanguage.KOREAN).detail

        assertTrue(detail.contains("오래된 투구 +2 획득"))
        assertTrue(detail.contains("가방에 보관"))
    }

    @Test
    fun `relationship gold and experience logs preserve exact settled amounts`() {
        val gold = recentAdventureEventPresentation(
            relationshipEvent(AdventureEventRewardKind.GOLD, gold = 145L),
            AppLanguage.KOREAN,
        ).detail
        val experience = recentAdventureEventPresentation(
            relationshipEvent(AdventureEventRewardKind.EXPERIENCE, experience = 88L),
            AppLanguage.KOREAN,
        ).detail

        assertTrue(gold.contains("+145 G"))
        assertTrue(experience.contains("경험치 +88"))
    }

    private fun relationshipEvent(
        rewardKind: AdventureEventRewardKind,
        experience: Long = 0L,
        gold: Long = 0L,
        itemName: String = "",
        itemEquipped: Boolean = false,
    ) = RecentAdventureEvent(
        occurredAt = 1_800_000_000_000L,
        type = RecentAdventureEventType.RELATIONSHIP_ENCOUNTER,
        subjectId = "projection-id",
        subjectName = "바람 따라 걷는 별",
        contextName = RecentAdventureEventMetadata.appendRelationshipReward(
            baseContext = "directions:read_signs:SUCCESS",
            rewardKind = rewardKind,
            experienceAwarded = experience,
            goldAwarded = gold,
            itemName = itemName,
            itemEquipped = itemEquipped,
        ),
        previousName = AdventureRelationshipTier.KNOWN.name,
        currentName = AdventureRelationshipTier.CLOSE.name,
        previousValue = 0L,
        currentValue = 30L,
        rarity = "영웅",
    )
}
