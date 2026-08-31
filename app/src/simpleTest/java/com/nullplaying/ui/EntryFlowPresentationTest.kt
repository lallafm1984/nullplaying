package com.nullplaying.ui

import com.nullplaying.data.StartupPhase
import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.model.HeroClass
import com.nullplaying.model.TaleKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryFlowPresentationTest {
    @Test
    fun `one shared banner covers every post-title entry scene`() {
        assertFalse(entrySceneUsesSharedBanner(EntryScene.TITLE))
        assertTrue(entrySceneUsesSharedBanner(EntryScene.ROSTER))
        assertTrue(entrySceneUsesSharedBanner(EntryScene.CREATION))
        assertTrue(entrySceneUsesSharedBanner(EntryScene.GAME))
    }

    @Test
    fun `changing character uses directional transitions around the roster`() {
        assertEquals(
            EntrySceneTransitionDirection.BACKWARD,
            entrySceneTransitionDirection(EntryScene.GAME, EntryScene.ROSTER),
        )
        assertEquals(
            EntrySceneTransitionDirection.FORWARD,
            entrySceneTransitionDirection(EntryScene.ROSTER, EntryScene.GAME),
        )
        assertEquals(
            EntrySceneTransitionDirection.FADE,
            entrySceneTransitionDirection(EntryScene.TITLE, EntryScene.ROSTER),
        )
    }

    @Test
    fun `new character enters the game with a playable prologue quest`() {
        val engine = SimpleGameEngine()
        val state = engine.newGame(
            name = "루나",
            heroClass = HeroClass.MAGE,
            rolledStats = engine.rollStats(77L).stats,
            seed = 88L,
            now = 1_000L,
        )

        assertEquals(EntryScene.GAME, entrySceneFor(state))
        assertEquals(TaleKind.PROLOGUE, state.adventureTale.kind)
        assertEquals(5, state.adventureTale.acts.size)
        assertTrue(state.adventureTale.acts.all { it.target > 1L })
    }

    @Test
    fun `character slots begin at one and expand through level unlocks`() {
        assertEquals(3, PLANNED_CHARACTER_SLOT_COUNT)
        assertFalse(canCreateCharacter(emptyList(), 0))
        assertTrue(canCreateCharacter(emptyList(), 1))
    }

    @Test
    fun `three compact roster cards fit the one-screen list budget`() {
        assertEquals(562, rosterCardsHeightDp(3))
        assertTrue(rosterCardsHeightDp(3) <= ROSTER_THREE_CARD_LIST_BUDGET_DP)
    }

    @Test
    fun `roster accessibility summary includes offline adventure balance`() {
        val description = characterRosterContentDescription(
            name = "루나",
            classLabel = "메이지",
            level = 42L,
            combatPower = 1_234L,
            adventureTitle = "유리 숲의 백야",
            offlinePercent = 37,
        )

        assertTrue(description.contains("루나"))
        assertTrue(description.contains("현재 모험 유리 숲의 백야"))
        assertTrue(description.contains("오프라인 모험 잔여 37퍼센트"))
        assertFalse(description.contains("슬롯"))
    }

    @Test
    fun `character delete warning names the hero scope and irreversible result`() {
        val warning = characterDeleteWarningText("루나")

        assertTrue(warning.contains("루나"))
        assertTrue(warning.contains("장비"))
        assertTrue(warning.contains("가방"))
        assertTrue(warning.contains("퀘스트"))
        assertTrue(warning.contains("영구 삭제"))
        assertTrue(warning.contains("복구할 수 없습니다"))
    }

    @Test
    fun `reduced motion freezes every title layer`() {
        TitleLayer.entries.forEach { layer ->
            val first = titleLayerFrame(layer, phase = 0.11f, reducedMotion = true)
            val later = titleLayerFrame(layer, phase = 0.83f, reducedMotion = true)

            assertEquals(first, later)
            assertEquals(0f, first.xDp, 0.0001f)
            assertEquals(0f, first.yDp, 0.0001f)
        }
    }

    @Test
    fun `foreground atmosphere travels opposite the distant scene`() {
        val far = titleLayerFrame(TitleLayer.FAR, phase = 0.25f, reducedMotion = false)
        val mist = titleLayerFrame(TitleLayer.MIST, phase = 0.25f, reducedMotion = false)
        val foreground = titleLayerFrame(
            TitleLayer.FOREGROUND,
            phase = 0.25f,
            reducedMotion = false,
        )
        val dust = titleLayerFrame(TitleLayer.DUST, phase = 0.25f, reducedMotion = false)

        assertEquals(4f, far.xDp, 0.001f)
        assertEquals(10f, mist.xDp, 0.001f)
        assertEquals(-12f, foreground.xDp, 0.001f)
        assertEquals(-7f, dust.xDp, 0.001f)
        assertTrue(far.scale in 1.05f..1.06f)
        assertTrue(mist.alpha in 0.05f..0.17f)
        assertEquals(0.84f, foreground.alpha, 0.001f)
    }

    @Test
    fun `title motes are plentiful deterministic and varied in size`() {
        val motes = titleMotes()

        assertEquals(TITLE_MOTE_COUNT, motes.size)
        assertEquals(48, motes.size)
        assertEquals(motes, titleMotes())
        assertTrue(motes.map { it.radiusDp }.distinct().size >= 40)
        assertTrue(motes.minOf { it.radiusDp } < 0.8f)
        assertTrue(motes.maxOf { it.radiusDp } > 3f)
        assertTrue(motes.all { it.x in 0.04f..0.96f })
        assertTrue(motes.all { it.y in 0.36f..0.94f })
        assertTrue(motes.map { it.loopCount }.distinct().size >= 3)
        assertTrue(motes != titleMotes(seed = 7L))
    }

    @Test
    fun `every title layer and mote joins the loop without a visible seam`() {
        TitleLayer.entries.forEach { layer ->
            val start = titleLayerFrame(layer, phase = 0f, reducedMotion = false)
            val end = titleLayerFrame(layer, phase = 1f, reducedMotion = false)

            assertEquals(start.xDp, end.xDp, 0.0001f)
            assertEquals(start.yDp, end.yDp, 0.0001f)
            assertEquals(start.scale, end.scale, 0.0001f)
            assertEquals(start.alpha, end.alpha, 0.0001f)
        }

        titleMotes().forEach { mote ->
            val start = titleMoteFrame(mote, phase = 0f, reducedMotion = false)
            val end = titleMoteFrame(mote, phase = 1f, reducedMotion = false)
            val justBeforeEnd = titleMoteFrame(mote, phase = 0.9999f, reducedMotion = false)
            val justAfterStart = titleMoteFrame(mote, phase = 0.0001f, reducedMotion = false)

            assertEquals(start.x, end.x, 0.0001f)
            assertEquals(start.y, end.y, 0.0001f)
            assertEquals(start.pulse, end.pulse, 0.0001f)
            assertTrue(kotlin.math.abs(justBeforeEnd.x - justAfterStart.x) < 0.001f)
            assertTrue(kotlin.math.abs(justBeforeEnd.y - justAfterStart.y) < 0.001f)
            assertTrue(kotlin.math.abs(justBeforeEnd.pulse - justAfterStart.pulse) < 0.01f)
        }
    }

    @Test
    fun `title status keeps loading and ready messages distinct`() {
        assertEquals(
            "모험 기록을 불러오는 중",
            titleStatusText(
                ready = false,
                pendingEnter = false,
                phase = StartupPhase.LOADING_RECORD,
            ),
        )
        assertEquals(
            "모험 기록을 여는 중",
            titleStatusText(
                ready = false,
                pendingEnter = true,
                phase = StartupPhase.SETTLING_OFFLINE,
            ),
        )
        assertEquals(
            "화면을 터치해 시작",
            titleStatusText(
                ready = true,
                pendingEnter = false,
                phase = StartupPhase.READY,
            ),
        )
    }

    @Test
    fun `title intro starts closed and finishes with no overlay`() {
        val start = titleIntroPresentation(
            progress = 0f,
            playIntro = true,
            reducedMotion = false,
        )
        val end = titleIntroPresentation(
            progress = 1f,
            playIntro = true,
            reducedMotion = false,
        )

        assertEquals(1f, start.scale, 0.0001f)
        assertEquals(0f, start.leftTranslationXFraction, 0.0001f)
        assertEquals(0f, start.rightTranslationXFraction, 0.0001f)
        assertEquals(0f, start.leftRotationDegrees, 0.0001f)
        assertEquals(0f, start.rightRotationDegrees, 0.0001f)
        assertEquals(1f, start.curtainAlpha, 0.0001f)
        assertEquals(1f, start.shutterAlpha, 0.0001f)
        assertEquals(1f, start.overlayAlpha, 0.0001f)
        assertEquals(1f, end.scale, 0.0001f)
        assertEquals(0f, end.curtainAlpha, 0.0001f)
        assertEquals(0f, end.shutterAlpha, 0.0001f)
        assertEquals(0f, end.overlayAlpha, 0.0001f)
        assertEquals(1_700, TITLE_INTRO_TOTAL_MILLIS)
        assertEquals(200, TITLE_INTRO_CLOSED_HOLD_MILLIS)
        assertEquals(1_500, TITLE_INTRO_OPEN_MILLIS)
        assertEquals(900, TITLE_INTRO_CURTAIN_FADE_MILLIS)
        assertEquals(
            TITLE_INTRO_TOTAL_MILLIS,
            TITLE_INTRO_CLOSED_HOLD_MILLIS + TITLE_INTRO_OPEN_MILLIS,
        )
        assertTrue(TITLE_INTRO_SKIP_MILLIS in 120..160)
    }

    @Test
    fun `title intro halves travel outward without overshoot`() {
        val quarter = titleIntroPresentation(
            progress = 0.25f,
            playIntro = true,
            reducedMotion = false,
        )
        val middle = titleIntroPresentation(
            progress = 0.5f,
            playIntro = true,
            reducedMotion = false,
        )
        val end = titleIntroPresentation(
            progress = 1f,
            playIntro = true,
            reducedMotion = false,
        )

        assertTrue(quarter.rightTranslationXFraction in 0f..0.24f)
        assertTrue(middle.leftTranslationXFraction < 0f)
        assertTrue(middle.rightTranslationXFraction > 0f)
        assertTrue(middle.leftRotationDegrees in -4f..0f)
        assertTrue(middle.rightRotationDegrees in 0f..4f)
        assertEquals(1f, middle.scale, 0.0001f)
        assertEquals(-4f, end.leftRotationDegrees, 0.0001f)
        assertEquals(4f, end.rightRotationDegrees, 0.0001f)
        assertTrue(end.leftTranslationXFraction < middle.leftTranslationXFraction)
        assertTrue(end.rightTranslationXFraction > middle.rightTranslationXFraction)
    }

    @Test
    fun `black curtain remains visible through the first half of opening`() {
        val middle = titleIntroPresentation(
            progress = 0.5f,
            playIntro = true,
            reducedMotion = false,
        )

        assertTrue(middle.curtainAlpha in 0.15f..0.18f)
    }

    @Test
    fun `reduced motion and disabled intro resolve immediately open`() {
        val reduced = titleIntroPresentation(
            progress = 0f,
            playIntro = true,
            reducedMotion = true,
        )
        val disabled = titleIntroPresentation(
            progress = 0f,
            playIntro = false,
            reducedMotion = false,
        )

        assertEquals(reduced, disabled)
        assertEquals(0f, reduced.overlayAlpha, 0.0001f)
        assertEquals(1f, reduced.scale, 0.0001f)
    }

    @Test
    fun `title intro callbacks are emitted once across repeated completion`() {
        val first = titleIntroCallbackTransition(
            state = TitleIntroCallbackState(),
            finishIntro = true,
            requestEnter = true,
        )
        val repeated = titleIntroCallbackTransition(
            state = first.state,
            finishIntro = true,
            requestEnter = true,
        )

        assertTrue(first.notifyIntroFinished)
        assertTrue(first.notifyEnterRequested)
        assertTrue(first.state.introFinished)
        assertTrue(first.state.enterRequested)
        assertFalse(repeated.notifyIntroFinished)
        assertFalse(repeated.notifyEnterRequested)
        assertEquals(first.state, repeated.state)
    }
}
