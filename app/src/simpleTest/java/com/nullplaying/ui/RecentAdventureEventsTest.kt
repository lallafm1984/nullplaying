package com.nullplaying.ui

import android.app.Application
import com.nullplaying.data.toEntity
import com.nullplaying.data.toRecordOrNull
import com.nullplaying.localization.AppLanguage
import com.nullplaying.localization.GameLocalization
import com.nullplaying.model.AdventureRelationshipTier
import com.nullplaying.model.EquipmentSlot
import com.nullplaying.model.RecentAdventureEvent
import com.nullplaying.model.RecentAdventureEventMetadata
import com.nullplaying.model.RecentAdventureEventType
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RecentAdventureEventsTest {
    @Before
    fun setUp() {
        GameLocalization.initialize(RuntimeEnvironment.getApplication())
    }

    @Test
    fun `unknown legacy event title never leaks stored Korean after language switch`() {
        val event = RecentAdventureEvent(
            occurredAt = 1_000L,
            type = RecentAdventureEventType.ADVENTURE_EVENT,
            subjectId = "retired_event_id",
            subjectName = "사라진 옛 사건",
            contextName = "legacy:SUCCESS:0",
        )

        assertEquals("사라진 옛 사건 · 해결", recentAdventureEventPresentation(event, AppLanguage.KOREAN).title)
        assertEquals("Adventure event · Resolved", recentAdventureEventPresentation(event, AppLanguage.ENGLISH).title)
        assertEquals("冒険の出来事 · 解決", recentAdventureEventPresentation(event, AppLanguage.JAPANESE).title)
    }

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
    fun `current route reward is labeled while ambiguous legacy rows stay unchanged`() {
        val current = recentAdventureEventPresentation(
            RecentAdventureEvent(
                occurredAt = 1_000L,
                type = RecentAdventureEventType.ADVENTURE_EVENT,
                subjectId = "bridge",
                subjectName = "끊어진 다리",
                contextName = "cross:SUCCESS:0:REWARD=ROUTE:ROUTE_USES=4:ROUTE_DELAY=-1500",
            ),
            AppLanguage.KOREAN,
        )
        val legacy = recentAdventureEventPresentation(
            RecentAdventureEvent(
                occurredAt = 1_000L,
                type = RecentAdventureEventType.ADVENTURE_EVENT,
                subjectId = "bridge",
                subjectName = "끊어진 다리",
                contextName = "cross:SUCCESS:0",
            ),
            AppLanguage.KOREAN,
        )

        assertEquals("시간 단축", current.detail)
        assertEquals(
            "Travel time reduced",
            recentAdventureEventPresentation(
                RecentAdventureEvent(
                    occurredAt = 1_000L,
                    type = RecentAdventureEventType.ADVENTURE_EVENT,
                    subjectId = "bridge",
                    subjectName = "끊어진 다리",
                    contextName = "cross:SUCCESS:0:REWARD=ROUTE:ROUTE_USES=4:ROUTE_DELAY=-1500",
                ),
                AppLanguage.ENGLISH,
            ).detail,
        )
        assertEquals(
            "移動時間短縮",
            recentAdventureEventPresentation(
                RecentAdventureEvent(
                    occurredAt = 1_000L,
                    type = RecentAdventureEventType.ADVENTURE_EVENT,
                    subjectId = "bridge",
                    subjectName = "끊어진 다리",
                    contextName = "cross:SUCCESS:0:REWARD=ROUTE:ROUTE_USES=4:ROUTE_DELAY=-1500",
                ),
                AppLanguage.JAPANESE,
            ).detail,
        )
        assertEquals("", legacy.detail)
    }

    @Test
    fun `failed route roll reports delay instead of shortening`() {
        val presentation = recentAdventureEventPresentation(
            RecentAdventureEvent(
                occurredAt = 1_000L,
                type = RecentAdventureEventType.ADVENTURE_EVENT,
                subjectId = "bridge",
                subjectName = "끊어진 다리",
                contextName = "cross:FAILURE:0:REWARD=ROUTE:ROUTE_USES=2:ROUTE_DELAY=2000",
            ),
            AppLanguage.KOREAN,
        )

        assertEquals("이동 지연", presentation.detail)
    }

    @Test
    fun `level up presents actual stat increases without repeating the level number`() {
        val context = RecentAdventureEventMetadata.encodeStatGrowth(
            before = com.nullplaying.model.HeroStats(10, 11, 12, 13, 14, 15, 20, 9),
            after = com.nullplaying.model.HeroStats(11, 11, 12, 14, 14, 15, 25, 13),
        )
        val current = recentAdventureEventPresentation(
            RecentAdventureEvent(
                occurredAt = 1_000L,
                type = RecentAdventureEventType.LEVEL_UP,
                contextName = context,
                previousValue = 4L,
                currentValue = 5L,
            ),
            AppLanguage.KOREAN,
        )
        val legacy = recentAdventureEventPresentation(
            RecentAdventureEvent(
                occurredAt = 1_000L,
                type = RecentAdventureEventType.LEVEL_UP,
                previousValue = 4L,
                currentValue = 5L,
            ),
            AppLanguage.KOREAN,
        )

        assertEquals("레벨 상승", current.title)
        assertEquals("STR +1 · INT +1 · HP MAX +5 · MP MAX +4", current.detail)
        assertFalse(current.detail.contains("→"))
        assertEquals("레벨 상승", legacy.title)
        assertEquals("", legacy.detail)
    }

    @Test
    fun `quest completion presents its actual experience and gold increases without duplicates`() {
        val presentation = recentAdventureEventPresentation(
            RecentAdventureEvent(
                occurredAt = 1_000L,
                type = RecentAdventureEventType.QUEST_COMPLETED,
                subjectName = "빈 막사",
                contextName = "돌아오지 않은 순찰대",
                previousValue = 46L,
                currentValue = 12L,
            ),
            AppLanguage.KOREAN,
        )

        assertEquals("퀘스트 완료 · 빈 막사", presentation.title)
        assertEquals("돌아오지 않은 순찰대 · EXP +46 · +12 G", presentation.detail)
    }

    @Test
    fun `every recent event category renders complete English and Japanese without authored Korean`() {
        val statGrowth = RecentAdventureEventMetadata.encodeStatGrowth(
            before = com.nullplaying.model.HeroStats(10, 11, 12, 13, 14, 15, 20, 9),
            after = com.nullplaying.model.HeroStats(11, 11, 12, 14, 14, 15, 25, 13),
        )
        val events = listOf(
            RecentAdventureEvent(
                occurredAt = 1_000L,
                type = RecentAdventureEventType.ADVENTURE_TRAIT_CHANGED,
                subjectId = "E04",
                contextName = "ACQUIRED",
            ),
            RecentAdventureEvent(
                occurredAt = 1_000L,
                type = RecentAdventureEventType.ADVENTURE_TRAIT_ACTIVATED,
                subjectId = "E04",
                contextName = "RETRY",
            ),
            RecentAdventureEvent(
                occurredAt = 1_000L,
                type = RecentAdventureEventType.ADVENTURE_EVENT,
                subjectId = "bridge",
                contextName = "repair:SUCCESS:0:REWARD=GOLD",
                currentValue = 12L,
            ),
            RecentAdventureEvent(
                occurredAt = 1_000L,
                type = RecentAdventureEventType.RELATIONSHIP_ENCOUNTER,
                subjectName = "Ari",
                contextName = "broken_bridge:secure_rope:SUCCESS",
                previousName = AdventureRelationshipTier.KNOWN.name,
                currentName = AdventureRelationshipTier.CLOSE.name,
                previousValue = 0L,
                currentValue = 30L,
            ),
            RecentAdventureEvent(
                occurredAt = 1_000L,
                type = RecentAdventureEventType.LEVEL_UP,
                contextName = statGrowth,
                previousValue = 4L,
                currentValue = 5L,
            ),
            RecentAdventureEvent(
                occurredAt = 1_000L,
                type = RecentAdventureEventType.SKILL_MASTERY,
                subjectName = "칼날 베기",
                previousValue = 2L,
                currentValue = 3L,
            ),
            RecentAdventureEvent(
                occurredAt = 1_000L,
                type = RecentAdventureEventType.SKILL_LEARNED,
                subjectName = "칼날 베기",
                currentValue = 5L,
            ),
            RecentAdventureEvent(
                occurredAt = 1_000L,
                type = RecentAdventureEventType.EQUIPMENT_CHANGED,
                previousName = "낡은 정련 철제 장검",
                currentName = "잘 벼린 정련 철제 장검 +3",
                previousValue = 12L,
                currentValue = 18L,
                equipmentSlot = EquipmentSlot.WEAPON,
            ),
            RecentAdventureEvent(
                occurredAt = 1_000L,
                type = RecentAdventureEventType.QUEST_COMPLETED,
                subjectName = "빈 막사",
                contextName = "돌아오지 않은 순찰대",
                previousValue = 46L,
                currentValue = 12L,
            ),
            RecentAdventureEvent(
                occurredAt = 1_000L,
                type = RecentAdventureEventType.TALE_COMPLETED,
                subjectName = "돌아오지 않은 순찰대",
                contextName = "잿빛 국경",
            ),
            RecentAdventureEvent(
                occurredAt = 1_000L,
                type = RecentAdventureEventType.TITLE_UNLOCKED,
                subjectName = "제1관문 정복자",
            ),
        )

        assertEquals(RecentAdventureEventType.entries.toSet(), events.map { it.type }.toSet())
        listOf(AppLanguage.ENGLISH, AppLanguage.JAPANESE).forEach { language ->
            events.forEach { event ->
                val presentation = recentAdventureEventPresentation(event, language)
                val rendered = "${presentation.title}\n${presentation.detail}"
                assertTrue("${event.type} $language title is blank", presentation.title.isNotBlank())
                assertFalse(
                    "${event.type} $language leaked authored Korean: $rendered",
                    KOREAN.containsMatchIn(rendered),
                )
            }
        }
    }

    @Test
    fun `versioned recent event metadata survives the existing entity round trip`() {
        val current = RecentAdventureEvent(
            occurredAt = 1_000L,
            type = RecentAdventureEventType.LEVEL_UP,
            contextName = "STAT_GROWTH_V1|STR=1|HP_MAX=5",
            previousValue = 4L,
            currentValue = 5L,
        )
        val legacy = RecentAdventureEvent(
            occurredAt = 900L,
            type = RecentAdventureEventType.LEVEL_UP,
            previousValue = 3L,
            currentValue = 4L,
        )

        assertEquals(current, current.toEntity(characterSlotId = 1).toRecordOrNull()!!.event)
        assertEquals(legacy, legacy.toEntity(characterSlotId = 1).toRecordOrNull()!!.event)
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

    private companion object {
        val KOREAN = Regex("[가-힣]")
    }
}
