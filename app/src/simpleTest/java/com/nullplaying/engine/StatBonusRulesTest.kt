package com.nullplaying.engine

import com.nullplaying.model.HeroClass
import com.nullplaying.model.HeroStats
import com.nullplaying.model.SimpleGameState
import java.math.BigInteger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class StatBonusRulesTest {
    @Test
    fun `remote base and charge pairs drive capacity refill fraction reward and consumption consistently`() {
        for (baseMinutes in listOf(1L, 480L, 720L, 1_440L, 4_320L)) {
            for (chargeMinutes in listOf(1L, 6L, 12L, 20L, 24L, 1_440L)) {
                val configured = SimpleGameEngine(OfflineAdventureConfig(baseMinutes, chargeMinutes))
                for (con in listOf(0L, 50L, 150L)) {
                    val state = game(value = con)
                    val capacity = configured.offlineAdventureCapacityMillis(state)
                    val expectedBonus = StatBonusRules.adventureBonusMillis(state, baseMinutes*60_000L)
                    assertEquals(baseMinutes*60_000L + expectedBonus, capacity)
                    if (con == 0L) assertEquals(baseMinutes*60_000L, capacity)
                    if (con == 150L) assertEquals(baseMinutes*75_000L, capacity)
                    state.offlineAdventureMillis = 0L
                    val halfCharge = chargeMinutes*30_000L
                    configured.advanceOfflineAdventureForeground(state, halfCharge)
                    assertEquals(capacity/2L, state.offlineAdventureMillis)
                    assertEquals(0.5f, configured.offlineAdventureFraction(state), 0.00002f)
                    configured.advanceOfflineAdventureForeground(state, halfCharge)
                    assertEquals(capacity, state.offlineAdventureMillis)
                    assertTrue(configured.isOfflineAdventureFull(state))
                    state.offlineAdventureMillis = 0L
                    assertTrue(configured.grantRewardedOfflineAdventure(state, "remote-$baseMinutes-$chargeMinutes-$con"))
                    assertEquals(capacity, state.offlineAdventureMillis)
                }
            }
        }
    }

    @Test
    fun `twelve hour remote base yields fifteen hour con cap and remote six minute charging is honored`() {
        val configured = SimpleGameEngine(OfflineAdventureConfig(720, 6))
        val state = game().apply { offlineAdventureMillis = 0L }
        configured.advanceOfflineAdventureForeground(state, 60_000L)
        assertEquals(9_000_000L, state.offlineAdventureMillis) // 2h30m earned per minute.
        configured.advanceOfflineAdventureForeground(state, 300_000L)
        assertEquals(54_000_000L, state.offlineAdventureMillis)
        configured.settleOfflineWithOfflineAdventure(state, 3_600_000L)
        assertEquals(50_400_000L, state.offlineAdventureMillis)
        configured.updateOfflineAdventureConfig(OfflineAdventureConfig(480, 12))
        configured.clampOfflineAdventureBalance(state)
        assertEquals(50_400_000L, state.offlineAdventureMillis) // Previously earned time survives.
        configured.advanceOfflineAdventureForeground(state, 720_000L)
        assertEquals(50_400_000L, state.offlineAdventureMillis)
    }

    private val engine = SimpleGameEngine(OfflineAdventureConfig(480, 12))

    private fun game(heroClass: HeroClass = HeroClass.WARRIOR, value: Long = 150L, level: Long = 100L): SimpleGameState =
        engine.newGame("LOCAL_ONLY", heroClass, HeroStats(value, value, value, value, value, value, 100, 100), 981723L, 0L)
            .apply { hero.level = level }

    private fun clone(state: SimpleGameState): SimpleGameState = Json.decodeFromString(Json.encodeToString(state))

    @Test
    fun `remote capacity is the base and an eight hour base retains the approved two hour bonus`() {
        assertEquals(480L, OfflineAdventureConfig.DEFAULT_CAPACITY_MINUTES)
        assertEquals(20L, OfflineAdventureConfig.DEFAULT_CHARGE_MINUTES)
        assertEquals("offline_adventure_capacity_minutes", OfflineAdventureConfig.CAPACITY_KEY)
        assertEquals(28_800_000L, engine.offlineAdventureCapacityMillis(game(value = 0)))
        assertEquals(36_000_000L, engine.offlineAdventureCapacityMillis(game()))
        assertEquals(28_800_000L + 1_080_000L, engine.offlineAdventureCapacityMillis(game(level = 1)))
        assertTrue(engine.offlineAdventureCapacityMillis(game(level = 99)) < 36_000_000L)
    }

    @Test
    fun `bounded monotone curves and extreme values never overflow`() {
        for (heroClass in HeroClass.entries) {
            val state = game(heroClass)
            for (stat in listOf(Long.MIN_VALUE, 0L, 3L, 50L, 149L, 150L, 180L, Long.MAX_VALUE)) {
                state.hero.stats = HeroStats(stat, stat, stat, stat, stat, stat, 100, 100)
                var previousTime = 0L
                var previousSale = 0.0
                var previousSearch = 5_001L
                for (level in listOf(Long.MIN_VALUE, 1L, 2L, 20L, 50L, 80L, 99L, 100L, 101L, 120L, Long.MAX_VALUE)) {
                    state.hero.level = level
                    val time = StatBonusRules.adventureBonusMillis(state, 28_800_000L)
                    val sale = StatBonusRules.saleBonusPercent(state)
                    val search = StatBonusRules.encounterSearchMillis(state)
                    assertTrue(time in previousTime..7_200_000L)
                    assertTrue(sale in previousSale..15.0)
                    assertTrue(search in 3_500L..previousSearch)
                    assertTrue(engine.skillProcBasisPoints(state) in 800..if (heroClass == HeroClass.MAGE) 3_500 else 3_000)
                    previousTime = time; previousSale = sale; previousSearch = search
                }
            }
        }
    }

    @Test
    fun `mental bonus gives intelligence and wisdom equal weight independent of base aptitude`() {
        for (heroClass in HeroClass.entries) {
            val state = game(heroClass, value = 0L)
            state.hero.stats.intelligence = 160L; state.hero.stats.wisdom = 50L
            val a = StatBonusRules.skillProcBasisPoints(state, 20)
            state.hero.stats.intelligence = 50L; state.hero.stats.wisdom = 160L
            assertEquals(a, StatBonusRules.skillProcBasisPoints(state, 20))
            state.hero.stats.intelligence = 180L; state.hero.stats.wisdom = 180L
            assertEquals(if (heroClass == HeroClass.MAGE) 3_500 else 3_000,
                StatBonusRules.skillProcBasisPoints(state, 20))
        }
    }

    @Test
    fun `mage needs mental 180 to reach exact raw cap and pity is still separate`() {
        val state = game(HeroClass.MAGE, value = 179L)
        assertTrue(engine.skillProcBasisPoints(state) < 3_500)
        state.hero.stats.intelligence = 180L; state.hero.stats.wisdom = 180L
        assertEquals(3_500, engine.skillProcBasisPoints(state))
        assertTrue(engine.shouldUseSkill(state, 3_499))
        assertFalse(engine.shouldUseSkill(state, 3_500))
        state.consecutiveBasicAttacks = 15
        assertTrue(engine.shouldUseSkill(state, 9_999))
        state.skills.clear()
        assertFalse(engine.shouldUseSkill(state, 0))
        assertEquals(0, engine.effectiveSkillProcBasisPoints(state))
    }

    @Test
    fun `dex reduces search only and discovery remains two seconds`() {
        val state = game()
        assertEquals(3_500L, StatBonusRules.encounterSearchMillis(state))
        assertEquals(5_500L, StatBonusRules.encounterRevealMillis(state))
        state.hero.stats.dexterity = 0L
        assertEquals(5_000L, StatBonusRules.encounterSearchMillis(state))
        assertEquals(7_000L, StatBonusRules.encounterRevealMillis(state))
        state.hero.stats.dexterity = 150L; state.hero.level = 99L
        assertTrue(StatBonusRules.encounterSearchMillis(state) > 3_500L)
    }

    @Test
    fun `sale integer rounding is exact at every stat limited rate and saturates safely`() {
        val state = game()
        for (cha in 0L..150L) {
            state.hero.stats.charisma = cha
            for (base in listOf(0L, 1L, 10L, 100L, 1_000L, 6_000L, 999_999_999L, Long.MAX_VALUE)) {
                val expected = BigInteger.valueOf(base).multiply(BigInteger.valueOf(1000L+cha))
                    .divide(BigInteger.valueOf(1000L)).min(BigInteger.valueOf(Long.MAX_VALUE)).toLong()
                assertEquals("cha=$cha base=$base", expected, StatBonusRules.saleValue(state, base))
            }
        }
        assertEquals(115L, StatBonusRules.saleValue(state, 100L))
        assertEquals(0L, StatBonusRules.saleValue(state, Long.MIN_VALUE))
        state.hero.level = 1L
        assertEquals(1_018L, StatBonusRules.saleValue(state, 1_000L))
    }

    @Test
    fun `dynamic charge is independent of tick size including JSON restoration`() {
        val whole = game(value = 53L, level = 77L).apply { offlineAdventureMillis = 0L }
        var split = clone(whole)
        engine.advanceOfflineAdventureForeground(whole, 12_347L)
        repeat(12_347) { tick ->
            engine.advanceOfflineAdventureForeground(split, 1L)
            if (tick == 6123) split = clone(split)
        }
        assertEquals(whole.offlineAdventureMillis, split.offlineAdventureMillis)
        assertEquals(whole.offlineAdventureChargeRemainder, split.offlineAdventureChargeRemainder)
        engine.advanceOfflineAdventureForeground(split, Long.MAX_VALUE)
        assertEquals(engine.offlineAdventureCapacityMillis(split), split.offlineAdventureMillis)
        assertEquals(0L, split.offlineAdventureChargeRemainder)
    }

    @Test
    fun `old fractional denominator converts once without reinterpreting repeated saves`() {
        val state = game().apply {
            offlineAdventureMillis = 0L
            offlineAdventureChargeRemainder = 7L
            offlineAdventureChargeRemainderVersion = 0
        }
        val current = clone(state).apply {
            offlineAdventureChargeRemainder = 7L*60_000L
            offlineAdventureChargeRemainderVersion = 1
        }
        repeat(10) {
            engine.advanceOfflineAdventureForeground(state, 1L)
            engine.advanceOfflineAdventureForeground(current, 1L)
            assertEquals(current.offlineAdventureMillis, state.offlineAdventureMillis)
            assertEquals(current.offlineAdventureChargeRemainder, state.offlineAdventureChargeRemainder)
            assertEquals(1, clone(state).offlineAdventureChargeRemainderVersion)
        }
        val oldJson = Json.encodeToString(state).replace(Regex(",?\"offlineAdventureChargeRemainderVersion\":1"), "")
        assertEquals(0, Json.decodeFromString<SimpleGameState>(oldJson).offlineAdventureChargeRemainderVersion)
    }

    @Test
    fun `legacy twelve hour balance is consumed not deleted and cannot be topped up above new cap`() {
        val state = game(value = 50L).apply { offlineAdventureMillis = 43_200_000L }
        engine.clampOfflineAdventureBalance(state)
        engine.advanceOfflineAdventureForeground(state, 720_000L)
        assertEquals(43_200_000L, state.offlineAdventureMillis)
        assertFalse(engine.grantRewardedOfflineAdventure(state, "legacy-full"))
        engine.settleOfflineWithOfflineAdventure(state, 3_600_000L)
        assertEquals(39_600_000L, state.offlineAdventureMillis)
        engine.settleOfflineWithOfflineAdventure(state, 13L*3_600_000L)
        assertEquals(0L, state.offlineAdventureMillis)
        val kills = state.totalKills
        engine.settleOfflineWithOfflineAdventure(state, 24L*3_600_000L)
        assertEquals(kills, state.totalKills)
    }

    @Test
    fun `level up raises capacity without adding time and reward respects that new cap`() {
        val state = game(level = 1L)
        val before = state.offlineAdventureMillis
        state.hero.level = 100L
        assertEquals(before, state.offlineAdventureMillis)
        assertFalse(engine.isOfflineAdventureFull(state))
        assertTrue(engine.grantRewardedOfflineAdventure(state, "level-cap"))
        assertEquals(36_000_000L, state.offlineAdventureMillis)
        assertFalse(engine.grantRewardedOfflineAdventure(state, "level-cap"))
    }

    @Test
    fun `maximum remote values and elapsed time cannot overflow fractional charging`() {
        val maxEngine = SimpleGameEngine(OfflineAdventureConfig(4_320, 1_440))
        val state = game(value = Long.MAX_VALUE).apply { offlineAdventureMillis = 0L }
        maxEngine.advanceOfflineAdventureForeground(state, Long.MAX_VALUE)
        assertEquals(324_000_000L, state.offlineAdventureMillis)
        assertTrue(maxEngine.isOfflineAdventureFull(state))
    }

    @Test
    fun `all classes have matching foreground and offline outcomes with stat bonuses`() {
        for (heroClass in HeroClass.entries) {
            val foreground = game(heroClass, value = 160L)
            val offline = clone(foreground)
            repeat(60) { engine.settle(foreground, (it+1)*60_000L) }
            engine.settleOffline(offline, 3_600_000L)
            assertEquals(foreground.hero, offline.hero)
            assertEquals(foreground.equipment, offline.equipment)
            assertEquals(foreground.skills, offline.skills)
            assertEquals(foreground.totalSaleGold, offline.totalSaleGold)
            assertEquals(foreground.totalKills, offline.totalKills)
            assertEquals(foreground.rngState, offline.rngState)
        }
    }
}
