package com.nullplaying.engine

import com.nullplaying.model.AdventureEventOutcome
import com.nullplaying.model.AdventureOwnedTrait
import com.nullplaying.model.EquipmentSlot
import com.nullplaying.model.HeroClass
import com.nullplaying.model.MonsterGrade
import com.nullplaying.model.SimpleGameState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdventureTraitExpansionTest {
    private val engine = SimpleGameEngine(enableAdventureEvents = true, enableAdventureTraits = true)

    @Test
    fun `six added pairs have real symmetric catalog contracts`() {
        val expectedPairs = setOf(
            setOf("C01", "C02"),
            setOf("C05", "C06"),
            setOf("E05", "E06"),
            setOf("S03", "S04"),
            setOf("T05", "T06"),
            setOf("G03", "G04"),
        )
        val actualPairs = AdventureTraitCatalog.all.map { setOf(it.id, it.oppositeId) }.toSet()
        assertTrue(actualPairs.containsAll(expectedPairs))
        assertEquals(40, AdventureTraitCatalog.all.size)
        assertEquals(20, actualPairs.size)
    }

    @Test
    fun `combat grade and finishing style traits grant both advantage and disadvantage`() {
        assertEquals(105L, combatExperience("C01", MonsterGrade.BOSS, finishingPercent = 50))
        assertEquals(95L, combatExperience("C01", MonsterGrade.NORMAL, finishingPercent = 50))
        assertEquals(105L, combatExperience("C02", MonsterGrade.NORMAL, finishingPercent = 50))
        assertEquals(95L, combatExperience("C02", MonsterGrade.ELITE, finishingPercent = 50))
        assertEquals(105L, combatExperience("C05", MonsterGrade.NORMAL, finishingPercent = 60))
        assertEquals(95L, combatExperience("C05", MonsterGrade.NORMAL, finishingPercent = 40))
        assertEquals(105L, combatExperience("C06", MonsterGrade.NORMAL, finishingPercent = 40))
        assertEquals(95L, combatExperience("C06", MonsterGrade.NORMAL, finishingPercent = 60))
    }

    @Test
    fun `large and small reward learners have opposite five percent tradeoffs`() {
        assertEquals(105L, rewardExperience("G03", 100L))
        assertEquals(9L, rewardExperience("G03", 10L))
        assertEquals(11L, rewardExperience("G04", 10L))
        assertEquals(95L, rewardExperience("G04", 100L))
    }

    @Test
    fun `event outcome pace traits save time only in their preferred result`() {
        assertEquals(18_000L, plannedEventMillis("E05", AdventureEventOutcome.SUCCESS))
        assertEquals(22_000L, plannedEventMillis("E05", AdventureEventOutcome.FAILURE))
        assertEquals(18_000L, plannedEventMillis("E06", AdventureEventOutcome.FAILURE))
        assertEquals(22_000L, plannedEventMillis("E06", AdventureEventOutcome.SUCCESS))
    }

    @Test
    fun `shop focus traits inspect weakest or strongest actual equipped slot`() {
        val weak = focusedShop("S03")
        val strong = focusedShop("S04")
        assertEquals(EquipmentSlot.WEAPON, weak)
        assertEquals(EquipmentSlot.ACCESSORY, strong)
    }

    @Test
    fun `return style traits trade sale time against departure time`() {
        assertEquals(90, saleDurationPercent("T05"))
        assertEquals(110, saleDurationPercent("T06"))
        assertEquals(4_400L, departureMillis("T05"))
        assertEquals(3_600L, departureMillis("T06"))
    }

    @Test
    fun `empty shop evidence uses distinct return contexts so quick trader can form`() {
        val game = newGame()
        repeat(3) { index ->
            game.totalReturns = index + 1L
            AdventureTraitEngine.beginReturn(game, index * 10_000L)
            AdventureTraitEngine.beginShop(game)
            AdventureTraitEngine.depart(game, index * 10_000L + 1L, 4_000L)
        }
        val evidence = game.adventureTraits.evidence.getValue("S02")
        assertEquals(3, evidence.size)
        assertEquals(3, evidence.map { it.contextKey }.distinct().size)
        assertTrue(evidence.all { it.positive && it.contextKey.startsWith("trade:empty:") })
    }

    private fun combatExperience(id: String, grade: MonsterGrade, finishingPercent: Int): Long {
        val game = owned(id)
        game.monster.grade = grade
        game.adventureTraits.seed = seedWhere { seed ->
            AdventureTraitEngine.random(seed, "$id:combat:1:${if (id in setOf("C01", "C02")) "gradeXp" else "finishXp"}") < 100
        }
        AdventureTraitEngine.beginCombat(game, 0L)
        game.adventureTraits.source!!.finishingRawPercent = finishingPercent
        return AdventureTraitEngine.experience(game, 100L, "combat:test", 1L)
    }

    private fun rewardExperience(id: String, amount: Long): Long {
        val game = owned(id)
        AdventureTraitEngine.beginSource(game, "event", "reward-size", 0L)
        game.adventureTraits.seed = seedWhere { seed ->
            AdventureTraitEngine.random(seed, "$id:event:1:rewardSizeXp") < 100
        }
        return AdventureTraitEngine.experience(game, amount, "event:reward-size", 1L)
    }

    private fun plannedEventMillis(id: String, outcome: AdventureEventOutcome): Long {
        val game = owned(id)
        val base = AdventureEventEngine.beginForQa(game, 0L, "bridge", actionMillis = 20_000L).copy(
            outcome = outcome,
            durationMillis = 20_000L,
        )
        game.adventureTraits.seed = seedWhere { seed ->
            AdventureTraitEngine.random(seed, "$id:event:1:outcomePace") < 100
        }
        return AdventureTraitEngine.planEvent(game, base).durationMillis
    }

    private fun focusedShop(id: String): EquipmentSlot? {
        val game = owned(id)
        game.equipment.sortedBy { it.slot.ordinal }.forEachIndexed { index, item -> item.power = index + 1L }
        val source = AdventureTraitEngine.beginSource(game, "town", "shop", 0L)
        game.adventureTraits.seed = seedWhere { seed ->
            AdventureTraitEngine.random(seed, "$id:${source.key}:shop") < 300
        }
        return AdventureTraitEngine.beginShop(game).extraSlot
    }

    private fun saleDurationPercent(id: String): Int {
        val game = owned(id)
        val source = AdventureTraitEngine.beginSource(game, "town", "sale", 0L)
        game.adventureTraits.seed = seedWhere { seed ->
            AdventureTraitEngine.random(seed, "$id:${source.key}:sale:returnPace") < 100
        }
        return AdventureTraitEngine.beginSale(game, listOf(1L to 100L), 1L).durationPercent
    }

    private fun departureMillis(id: String): Long {
        val game = owned(id)
        game.adventureTraits.seed = seedWhere { seed ->
            AdventureTraitEngine.random(seed, "$id:departure:1:returnPace") < 100
        }
        return AdventureTraitEngine.depart(game, 0L, 4_000L)
    }

    private fun owned(vararg ids: String): SimpleGameState = newGame().also { game ->
        game.adventureTraits.owned = ids.map { AdventureOwnedTrait(it) }
    }

    private fun newGame(): SimpleGameState = engine.newGame(
        "특성 확장 검증",
        HeroClass.WARRIOR,
        engine.rollStats(77L).stats,
        88L,
        0L,
    )

    private fun seedWhere(predicate: (Long) -> Boolean): Long = (1L..2_000_000L).first(predicate)
}
