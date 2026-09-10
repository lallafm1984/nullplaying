package com.nullplaying.ui

import com.nullplaying.engine.AdventureTraitCatalog
import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.localization.AppLanguage
import com.nullplaying.model.*
import org.junit.Assert.*
import org.junit.Test

class AdventureTraitPresentationTest {
    @Test fun `actual two item rewards and deliberately omitted loot are never misreported as a full bag`() {
        val original = eventResult()
        val result = original.copy(run = original.run.copy(
            itemReward = AdventureEventItemReward.TROPHY,
            rewardKind = AdventureEventRewardKind.ITEM,
        ), experienceAwarded = 0, goldAwarded = 0, progressAdded = 0,
            itemName = "흑철 조각", actualItemCount = 2, additionalItemNames = listOf("수정 조각"))
        val storedCopy = mapOf(
            AppLanguage.KOREAN to "가방에 보관했습니다",
            AppLanguage.ENGLISH to "Stored in the bag",
            AppLanguage.JAPANESE to "バッグに収納しました",
        )
        AppLanguage.entries.forEach { language ->
            assertEquals(storedCopy.getValue(language), eventRewardSummary(result, language))
            val omitted = result.copy(itemName = "", actualItemCount = 0, additionalItemNames = emptyList(), itemOmittedByTrait = true)
            assertTrue(eventRewardSummary(omitted, language).isNotBlank())
        }
        val event = RecentAdventureEvent(occurredAt = 1000, type = RecentAdventureEventType.ADVENTURE_EVENT,
            subjectId = "bridge", contextName = "repair:SUCCESS:2", currentName = "흑철 조각")
        assertEquals("흑철 조각 획득", recentAdventureEventPresentation(event, AppLanguage.KOREAN).detail)
        assertTrue(recentAdventureEventPresentation(event, AppLanguage.ENGLISH).detail.startsWith("Obtained "))
        assertFalse(recentAdventureEventPresentation(event, AppLanguage.ENGLISH).detail.contains("+2"))
        assertEquals("", recentAdventureEventPresentation(event.copy(contextName = "repair:SUCCESS:0", currentName = ""), AppLanguage.KOREAN).detail)
    }

    @Test fun `trait copy and actual effect notices do not expose chance decisions or technical identifiers`() {
        AdventureTraitCatalog.all.forEach { trait ->
            AppLanguage.entries.forEach { language ->
                val text = listOf(trait.name, trait.advantage, trait.disadvantage).joinToString(" ") { it.inLanguage(language) }
                assertFalse(text, text.contains("%"))
                assertFalse(text, text.contains(trait.id))
            }
        }
        AdventureTraitEffectKind.entries.forEach { kind ->
            val activation = AdventureTraitActivation(1, "L01", "hidden-source-roll-999", 8, 1000, kind,
                previousValue = 12, currentValue = 15, subjectName = "흑철 조각")
            AppLanguage.entries.forEach { language ->
                val text = adventureTraitActivationText(activation, language)
                assertFalse(text, text.contains("%"))
                assertFalse(text, text.contains("roll"))
                assertFalse(text, text.contains("hidden-source"))
                assertTrue(text.isNotBlank())
            }
        }
    }

    @Test fun `adventurer trait list contains only localized adventure trait names and descriptions`() {
        val engine = SimpleGameEngine()
        val roll = engine.rollStats(81L, HeroClass.RANGER)
        val state = engine.newGame("여행자", HeroClass.RANGER, roll.stats, roll.nextSeed, 0L)
        state.adventureTraits.owned = listOf(
            AdventureOwnedTrait("C03", acquisitionSequence = 1L, shaky = true),
            AdventureOwnedTrait("L01", acquisitionSequence = 2L),
            AdventureOwnedTrait("UNKNOWN", acquisitionSequence = 3L),
        )
        state.adventureTraits.recentChanges = listOf(
            AdventureTraitChange(1L, "C03", AdventureTraitChangeKind.WEAKENED, "qa", 1L, "combat"),
        )

        AppLanguage.entries.forEach { language ->
            val items = adventureTraitListItems(state, language)
            assertEquals(listOf("L01", "C03"), items.map { it.traitId })
            items.forEach { item ->
                val definition = AdventureTraitCatalog.definition(item.traitId)
                assertEquals(definition.name.inLanguage(language), item.name)
                assertEquals(
                    "${definition.advantage.inLanguage(language)}\n${definition.disadvantage.inLanguage(language)}",
                    item.description,
                )
                assertFalse(item.description.contains("WEAKENED"))
                assertFalse(item.description.contains("흔들림"))
            }
            assertFalse(items.first { it.traitId == "L01" }.shaky)
            assertEquals("", items.first { it.traitId == "L01" }.status)
            assertTrue(items.first { it.traitId == "C03" }.shaky)
            assertEquals(
                when (language) {
                    AppLanguage.KOREAN -> "흔들리는 중"
                    AppLanguage.ENGLISH -> "Wavering"
                    AppLanguage.JAPANESE -> "揺らぎ中"
                },
                items.first { it.traitId == "C03" }.status,
            )
        }
    }

    @Test fun `a completed sale does not keep claiming its effect during shopping or departure`() {
        assertTrue(adventureTraitEffectVisibleInPhase(AdventureTraitEffectKind.SALE, AdventurePhase.SELLING))
        assertFalse(adventureTraitEffectVisibleInPhase(AdventureTraitEffectKind.SALE, AdventurePhase.SHOPPING))
        assertFalse(adventureTraitEffectVisibleInPhase(AdventureTraitEffectKind.SALE, AdventurePhase.DEPARTING))
        assertTrue(adventureTraitEffectVisibleInPhase(AdventureTraitEffectKind.BAG_CAPACITY, AdventurePhase.DEPARTING))
        assertFalse(adventureTraitEffectVisibleInPhase(AdventureTraitEffectKind.BAG_CAPACITY, AdventurePhase.COMBAT))
        assertTrue(adventureTraitEffectVisibleInPhase(AdventureTraitEffectKind.EXTRA_ITEM, AdventurePhase.EVENT_RESULT))
        assertFalse(adventureTraitEffectVisibleInPhase(AdventureTraitEffectKind.EXTRA_ITEM, AdventurePhase.EVENT))
        assertTrue(adventureTraitEffectVisibleInPhase(AdventureTraitEffectKind.SHOP_REVIEW, AdventurePhase.SHOPPING_EMPTY))
    }

    @Test fun `retry action follows the original action and does not reveal the winning approach early`() {
        val base = eventResult().run
        val alternate = com.nullplaying.engine.AdventureEventEngine.definition(base.eventId).approaches.first { it.id != base.approachId }
        val retry = base.copy(approachId = alternate.id, startedAt = base.startedAt + base.durationMillis, durationMillis = base.durationMillis / 5)
        val state = SimpleGameEngine().let { engine ->
            val roll = engine.rollStats(173, HeroClass.WARRIOR)
            engine.newGame("여행자", HeroClass.WARRIOR, roll.stats, roll.nextSeed, 0)
        }
        state.adventurePhase = AdventurePhase.EVENT
        state.adventureJourney.pending = retry.copy(startedAt = base.startedAt, durationMillis = base.durationMillis + retry.durationMillis)
        state.adventureTraits.source = AdventureTraitSource("event:1", "event", base.eventId, base.startedAt,
            baseEvent = base, retryRun = retry)
        assertEquals(base.approachId, adventureEventActionRun(state, false)?.approachId)
        assertEquals(alternate.id, adventureEventActionRun(state, true)?.approachId)
        assertEquals(base.startedAt + base.durationMillis, adventureEventActionRun(state, true)?.startedAt)
    }

    @Test fun `event presentation changes from event to action at the exact five second boundary`() {
        val run = eventResult().run.copy(startedAt = 1_000L, durationMillis = 10_000L)

        assertEquals(
            AdventureEventDisplayWindow(AdventureEventDisplayStage.EVENT, 1_000L, 6_000L),
            adventureEventDisplayWindow(run, retry = false, now = 1_000L),
        )
        assertEquals(
            AdventureEventDisplayWindow(AdventureEventDisplayStage.EVENT, 1_000L, 6_000L),
            adventureEventDisplayWindow(run, retry = false, now = 5_999L),
        )
        assertEquals(
            AdventureEventDisplayWindow(AdventureEventDisplayStage.ACTION, 6_000L, 11_000L),
            adventureEventDisplayWindow(run, retry = false, now = 6_000L),
        )
        val retry = run.copy(startedAt = 11_000L, durationMillis = 2_000L)
        assertEquals(
            AdventureEventDisplayWindow(AdventureEventDisplayStage.ACTION, 11_000L, 13_000L),
            adventureEventDisplayWindow(retry, retry = true, now = 11_000L),
        )
        val shortLegacyRun = run.copy(startedAt = 20_000L, durationMillis = 4_000L)
        assertEquals(
            AdventureEventDisplayWindow(AdventureEventDisplayStage.EVENT, 20_000L, 22_000L),
            adventureEventDisplayWindow(shortLegacyRun, retry = false, now = 21_999L),
        )
        assertEquals(
            AdventureEventDisplayWindow(AdventureEventDisplayStage.ACTION, 22_000L, 24_000L),
            adventureEventDisplayWindow(shortLegacyRun, retry = false, now = 22_000L),
        )
    }

    @Test fun `event discovery uses the same small event name and large content hierarchy as action`() {
        val event = adventureEventCenterText(
            stage = AdventureEventDisplayStage.EVENT,
            eventName = "길을 메운 뿔짐승",
            eventScene = "이동하는 뿔짐승 떼가 좁은 협곡을 가득 메웠다.",
            actionTitle = "옆 비탈에 통로를 낸다",
        )
        val action = adventureEventCenterText(
            stage = AdventureEventDisplayStage.ACTION,
            eventName = "길을 메운 뿔짐승",
            eventScene = "이동하는 뿔짐승 떼가 좁은 협곡을 가득 메웠다.",
            actionTitle = "옆 비탈에 통로를 낸다",
        )

        assertEquals("길을 메운 뿔짐승", event.eventName)
        assertEquals("이동하는 뿔짐승 떼가 좁은 협곡을 가득 메웠다.", event.content)
        assertEquals("길을 메운 뿔짐승", action.eventName)
        assertEquals("옆 비탈에 통로를 낸다", action.content)
    }

    private fun eventResult(): AdventureEventResult {
        val engine = SimpleGameEngine(enableAdventureEvents = true)
        val roll = engine.rollStats(173, HeroClass.WARRIOR)
        val state = engine.newGame("여행자", HeroClass.WARRIOR, roll.stats, roll.nextSeed, 0)
        var attempts = 0
        while (state.adventureJourney.lastResult == null && attempts++ < 5000) engine.settle(state, state.actionEndsAt)
        return requireNotNull(state.adventureJourney.lastResult)
    }
}
