package com.nullplaying.ui

import com.nullplaying.localization.AppLanguage
import com.nullplaying.model.EquipmentSlot
import com.nullplaying.model.RecentAdventureEvent
import com.nullplaying.model.RecentAdventureEventType
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentAdventureEventsTest {
    @Test
    fun `recent events title never distinguishes online from offline`() {
        AppLanguage.entries.forEach { language ->
            val title = recentEventsTitle(language)
            assertFalse(title.contains("온라인"))
            assertFalse(title.contains("오프라인"))
            assertFalse(title.contains("Online", ignoreCase = true))
            assertFalse(title.contains("Offline", ignoreCase = true))
        }
    }

    @Test
    fun `equipment event presents one unmerged replacement`() {
        val presentation = recentAdventureEventPresentation(
            event = RecentAdventureEvent(
                occurredAt = 1_000L,
                type = RecentAdventureEventType.EQUIPMENT_CHANGED,
                previousName = "낡은 검",
                currentName = "기사의 검",
                previousValue = 12L,
                currentValue = 18L,
                equipmentSlot = EquipmentSlot.WEAPON,
            ),
            language = AppLanguage.KOREAN,
        )

        assertEquals("무기 교체 · 기사의 검", presentation.title)
        assertEquals("낡은 검 (12) → 기사의 검 (18)", presentation.detail)
    }

    @Test
    fun `day labels group only by calendar date`() {
        val now = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 2, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val yesterday = (now.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, -1)
        }

        assertEquals("오늘", recentEventDayLabel(now.timeInMillis, AppLanguage.KOREAN, now.timeInMillis))
        assertEquals(
            "어제",
            recentEventDayLabel(yesterday.timeInMillis, AppLanguage.KOREAN, now.timeInMillis),
        )
        assertTrue(recentEventDayKey(now.timeInMillis) != recentEventDayKey(yesterday.timeInMillis))
    }

    @Test
    fun `event time always uses the twenty four hour clock`() {
        val evening = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 2, 21, 5, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        assertEquals(
            "21:05",
            recentEventTimeLabel(evening, AppLanguage.ENGLISH),
        )
        assertEquals("21:05", recentEventTimeLabel(evening, AppLanguage.JAPANESE))
        assertEquals("21:05", recentEventTimeLabel(evening, AppLanguage.KOREAN))
    }
}
