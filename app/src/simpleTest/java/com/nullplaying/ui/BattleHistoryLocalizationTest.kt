package com.nullplaying.ui

import android.app.Application
import com.nullplaying.engine.arena.ArenaProgressionCatalog
import com.nullplaying.engine.arena.ArenaSupportTurnEngine
import com.nullplaying.engine.arena.ArenaTurnRules
import com.nullplaying.localization.AppLanguage
import com.nullplaying.localization.GameLocalization
import com.nullplaying.model.BattleOutcome
import com.nullplaying.model.HeroClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.ceil

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BattleHistoryLocalizationTest {
    @Before
    fun setUp() {
        GameLocalization.initialize(RuntimeEnvironment.getApplication())
    }

    @Test
    fun `saved arena history replays in selected language and preserves player names`() {
        val user = ArenaCompleteIntegrationTest.input(HeroClass.WARRIOR, "user", 60, 50)
        val opponent = ArenaCompleteIntegrationTest.input(HeroClass.MAGE, "opponent", 60, 50)
        val rules = ArenaTurnRules()
        val seed = 4_209L
        val simulation = ArenaSupportTurnEngine.simulate(user, opponent, seed, rules)
        val names = mapOf("user" to "별", "opponent" to "달")
        val koreanLines = buildArenaLiveTimeline(simulation, names, "ko").logs.map { it.text }
        val outcome = when (simulation.winnerId) {
            "user" -> BattleOutcome.USER_WIN
            "opponent" -> BattleOutcome.USER_LOSS
            else -> BattleOutcome.DRAW
        }
        val history = BattlePreviewHistory(
            battleId = "history-localization",
            userName = "별",
            opponentName = "달",
            opponentClass = "메이지",
            opponentLevel = 60,
            resultLabel = when (outcome) {
                BattleOutcome.USER_WIN -> "승리"
                BattleOutcome.USER_LOSS -> "패배"
                BattleOutcome.DRAW -> "무승부"
            },
            pointDelta = 3,
            // Older support-battle history incorrectly saved the one-row compatibility envelope
            // as one round. Snapshot replay must repair that display from the exact simulation.
            summary = "1합 끝에 저장된 요약",
            narrativeLines = koreanLines,
            skillNames = (user.fighter.attacks + opponent.fighter.attacks).map { it.name },
            // A legacy English-language record verifies cross-language recovery without migration.
            equipment = listOf(BattlePreviewHistoryEquipment("Mercenary's Iron Mace +3", "고급")),
            traitNames = (user.traits + opponent.traits)
                .mapNotNull { ArenaProgressionCatalog.find(it.id)?.nameKo }
                .distinct(),
            completedAtMillis = 1_000L,
            arenaSnapshot = ArenaSavedBattleContract(
                seed = seed,
                user = user,
                opponent = opponent,
                rules = rules,
                progressionRevision = 7,
                issuedDay = 20_700,
            ),
        )
        val restored = requireNotNull(
            decodeBattleLocalSnapshot(
                encodeBattleLocalSnapshot(BattleLocalSnapshot(history = listOf(history))),
            ),
        ).history.single()

        val english = battleHistoryLocalizedContent(restored, AppLanguage.ENGLISH)
        val japanese = battleHistoryLocalizedContent(restored, AppLanguage.JAPANESE)
        val userHp = displayHp(simulation.fighters.getValue("user").hp)
        val opponentHp = displayHp(simulation.fighters.getValue("opponent").hp)

        assertEquals("傭兵の鉄製メイス +3", japanese.equipment.single().name)
        assertTrue(simulation.turns > 1)
        assertEquals(
            battleDecisiveMomentLabel(
                outcome,
                simulation.turns,
                "별",
                userHp,
                "달",
                opponentHp,
                AppLanguage.ENGLISH,
            ),
            english.summary,
        )
        assertEquals(
            battleDecisiveMomentLabel(
                outcome,
                simulation.turns,
                "별",
                userHp,
                "달",
                opponentHp,
                AppLanguage.JAPANESE,
            ),
            japanese.summary,
        )
        assertTrue(english.summary.contains("별") && english.summary.contains("달"))
        assertTrue(japanese.summary.contains("별") && japanese.summary.contains("달"))
        assertTrue(english.narrativeLines.any { "별" in it || "달" in it })
        assertNoSystemKorean(english)
        assertNoSystemKorean(japanese)
        assertEquals(history, restored)
    }

    @Test
    fun `incompatible saved rules retain the legacy record instead of inventing a replay`() {
        val user = ArenaCompleteIntegrationTest.input(HeroClass.WARRIOR, "user", 60, 50)
        val opponent = ArenaCompleteIntegrationTest.input(HeroClass.MAGE, "opponent", 60, 50)
        val history = BattlePreviewHistory(
            battleId = "old-rules",
            userName = "Player One",
            opponentName = "Player Two",
            opponentClass = "메이지",
            opponentLevel = 60,
            resultLabel = "승리",
            pointDelta = 3,
            summary = "Legacy summary",
            narrativeLines = listOf("Legacy line"),
            skillNames = emptyList(),
            equipment = emptyList(),
            traitNames = emptyList(),
            completedAtMillis = 1_000L,
            arenaSnapshot = ArenaSavedBattleContract(
                rulesVersion = "retired-rules",
                seed = 99L,
                user = user,
                opponent = opponent,
                rules = ArenaTurnRules(),
                progressionRevision = 1L,
                issuedDay = 20_700L,
            ),
        )

        val content = battleHistoryLocalizedContent(history, AppLanguage.JAPANESE)

        assertEquals("Legacy summary", content.summary)
        assertEquals(listOf("Legacy line"), content.narrativeLines)
    }

    private fun displayHp(value: Double): Int =
        if (value <= 0.0) 0 else ceil(value).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()

    private fun assertNoSystemKorean(content: BattleHistoryLocalizedContent) {
        val displayed = listOf(content.summary) + content.narrativeLines + content.skillNames +
            content.equipment.map { it.name } + content.traitNames
        displayed.forEach { value ->
            val withoutPlayerNames = value.replace("별", "").replace("달", "")
            assertFalse("Korean system text leaked: $value", KOREAN.containsMatchIn(withoutPlayerNames))
        }
    }

    companion object {
        private val KOREAN = Regex("[가-힣]")
    }
}
