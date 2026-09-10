package com.nullplaying.ui

import com.nullplaying.localization.AppLanguage
import com.nullplaying.model.AdventureRelationshipTier
import com.nullplaying.model.RecentAdventureEvent
import com.nullplaying.model.RecentAdventureEventType
import org.junit.Assert.*
import org.junit.Test

class AdventurePresentationTest {
    @Test fun `relationship memory shows a name and relationship without exposing scores as rewards`() {
        val event = RecentAdventureEvent(occurredAt = 1000L,
            type = RecentAdventureEventType.RELATIONSHIP_ENCOUNTER,
            subjectId = "qa-peer", subjectName = "긴 이름의 여행자",
            contextName = "directions:read_signs:SUCCESS", previousValue = -17L, currentValue = -23L,
            previousName = "KNOWN", currentName = "BAD")
        AppLanguage.entries.forEach { language ->
            val actual = recentAdventureEventPresentation(event, language)
            assertTrue(actual.title.contains(event.subjectName))
            assertTrue(actual.title.contains(relationshipTierLabel(AdventureRelationshipTier.BAD, language)))
            val shown = actual.title + actual.detail
            listOf("-17", "-23", "%", "EXP", "roll", "확률").forEach { assertFalse(shown, shown.contains(it)) }
            assertTrue(actual.detail.isNotBlank())
            assertTrue(actual.detail.contains(com.nullplaying.engine.AdventureRelationshipEngine
                .definition("directions").title.inLanguage(language)))
        }
    }

    @Test fun `event record only shows positive actual rewards in every supported language`() {
        val event = RecentAdventureEvent(occurredAt = 1000L,
            type = RecentAdventureEventType.ADVENTURE_EVENT, subjectId = "bridge",
            contextName = "repair:PARTIAL", previousValue = 24L, currentValue = 0L)
        AppLanguage.entries.forEach { language ->
            val actual = recentAdventureEventPresentation(event, language)
            assertEquals("EXP +24", actual.detail)
            assertFalse(actual.title.contains("%"))
            assertFalse(actual.title.contains("PARTIAL"))
        }
    }

    @Test fun `tagged trophy uses the generic receipt and equipment retains its name`() {
        val base=RecentAdventureEvent(occurredAt=1000L,type=RecentAdventureEventType.ADVENTURE_EVENT,
            subjectId="retired_event",currentName="은빛 장식",contextName="look:SUCCESS:1:REWARD=ITEM:ITEM_KIND=TROPHY")
        assertEquals("전리품 획득",recentAdventureEventPresentation(base,AppLanguage.KOREAN).detail)
        val equipment=base.copy(currentName="기사의 검",contextName="look:SUCCESS:1:REWARD=ITEM:ITEM_KIND=EQUIPMENT")
        assertEquals("기사의 검 획득",recentAdventureEventPresentation(equipment,AppLanguage.KOREAN).detail)
        assertEquals("보상 없음",recentAdventureEventPresentation(base.copy(contextName="look:SUCCESS:0:REWARD=ITEM:ITEM_KIND=TROPHY"),AppLanguage.KOREAN).detail)
    }

    @Test fun `event trophy record uses a generic localized receipt instead of its name`() {
        val event = RecentAdventureEvent(
            occurredAt = 1_000L,
            type = RecentAdventureEventType.ADVENTURE_EVENT,
            subjectId = "ruins",
            contextName = "decode:SUCCESS:1",
            currentName = "잠든 유적의 문양 장식",
        )
        val expected = mapOf(
            AppLanguage.KOREAN to "전리품 획득",
            AppLanguage.ENGLISH to "Loot acquired",
            AppLanguage.JAPANESE to "戦利品を獲得",
        )

        AppLanguage.entries.forEach { language ->
            val detail = recentAdventureEventPresentation(event, language).detail
            assertEquals(expected.getValue(language), detail)
            assertFalse(detail.contains("+1"))
        }
    }
}
