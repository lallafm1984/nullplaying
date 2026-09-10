package com.nullplaying.engine.arena

import com.nullplaying.model.BattleOutcome
import com.nullplaying.model.HeroClass
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure ledger/effect contract tests, not class win-rate or long-term player behavior QA. */
class ArenaProgressionTest {
    private val rules = ArenaProgressionRules
    private val warrior = ArenaProgressionCatalog.initialTrait(HeroClass.WARRIOR)

    private fun fresh() = rules.initialize(ArenaProgressionState(), 10)
    private fun atLevel(level: Int) = fresh().copy(totalXp = rules.xpForLevel(level))
    private fun complete(
        state: ArenaProgressionState,
        id: String,
        day: Long,
        growth: Boolean = true,
        outcome: BattleOutcome = BattleOutcome.DRAW,
    ) = rules.completeBattle(rules.reserveBattle(state, id, day, growth, outcome), id)

    @Test
    fun `hero ten unlocks one arena point exactly once and preserves saved growth`() {
        val locked = ArenaProgressionState()
        assertEquals(locked, rules.initialize(locked, 9))
        assertEquals(0, rules.view(locked, 0).level)
        val unlocked = rules.initialize(locked, 10)
        assertTrue(unlocked.unlocked)
        assertEquals(0L, unlocked.totalXp)
        assertEquals(1, rules.view(unlocked, 0).baseAvailable)
        assertEquals(1, rules.view(unlocked, 0).level)
        assertEquals(160L, rules.view(unlocked, 0).xpToNext)
        assertEquals(unlocked, rules.initialize(unlocked, 10))
        assertEquals(unlocked, rules.initialize(unlocked, 100))
        val advanced = complete(unlocked, "new-only", 0)
        assertEquals(advanced, rules.initialize(advanced, 20))
    }

    @Test
    fun `all one hundred level thresholds carry XP and give one point per level`() {
        for (level in 1..100) {
            val threshold = rules.xpForLevel(level)
            assertEquals(level, rules.levelFromXp(threshold))
            val view = rules.view(atLevel(level), 0)
            assertEquals(level, view.baseEarned + view.enhancementEarned)
            assertEquals(minOf(level, 50), view.baseEarned)
            assertEquals(maxOf(level - 50, 0), view.enhancementEarned)
            assertEquals(0L, view.xpIntoLevel)
            if (level > 1) assertEquals(level - 1, rules.levelFromXp(threshold - 1))
            if (level < 100) assertEquals(
                rules.xpRequiredForNextLevel(level),
                rules.xpForLevel(level + 1) - threshold,
            )
        }
        assertEquals(100, rules.levelFromXp(Long.MAX_VALUE))
    }

    @Test
    fun `legacy twenty entry XP ledger retains its saved progression milestones`() {
        assertEquals(100L, rules.MATCH_XP)
        assertEquals(20, rules.DAILY_GROWTH_ENTRIES)
        assertEquals(listOf(1600L, 7200L, 30400L, 69600L, 196000L, 204000L, 444000L, 744000L),
            listOf(5, 10, 20, 30, 50, 51, 75, 100).map(rules::xpForLevel))
        val awardedBattles = (rules.MAX_TOTAL_XP + rules.MATCH_XP - 1L) / rules.MATCH_XP
        val minimumDays = (awardedBattles + rules.DAILY_GROWTH_ENTRIES - 1L) /
            rules.DAILY_GROWTH_ENTRIES
        assertEquals(7440L, awardedBattles)
        assertEquals(372L, minimumDays)
        assertEquals(160L, rules.xpRequiredForNextLevel(1))
        assertEquals(11_840L, rules.xpRequiredForNextLevel(74))
        assertEquals(12_000L, rules.xpRequiredForNextLevel(75))
        assertEquals(12_000L, rules.xpRequiredForNextLevel(99))
        assertEquals(1_600L, rules.xpForLevel(5))
    }

    @Test
    fun `win and loss rewards preserve a one hundred XP fifty percent average`() {
        assertEquals(120L, rules.xpForOutcome(BattleOutcome.USER_WIN))
        assertEquals(80L, rules.xpForOutcome(BattleOutcome.USER_LOSS))
        assertEquals(100L, rules.xpForOutcome(BattleOutcome.DRAW))

        val winPending = rules.reserveBattle(fresh(), "win", 0, outcome = BattleOutcome.USER_WIN)
        assertEquals(120L, checkNotNull(winPending.pending).xpAward)
        val won = rules.completeBattle(winPending, "win")
        assertEquals(120L, won.totalXp)

        val lossPending = rules.reserveBattle(won, "loss", 0, outcome = BattleOutcome.USER_LOSS)
        assertEquals(80L, checkNotNull(lossPending.pending).xpAward)
        val split = rules.completeBattle(lossPending, "loss")
        assertEquals(200L, split.totalXp)
        assertTrue(rules.isValid(split))
    }

    @Test
    fun `twenty first-day losses still reach arena level five`() {
        var state = fresh()
        repeat(rules.DAILY_GROWTH_ENTRIES) { index ->
            state = complete(
                state = state,
                id = "first-day-loss-$index",
                day = 0,
                outcome = BattleOutcome.USER_LOSS,
            )
        }
        assertEquals(1_600L, state.totalXp)
        assertEquals(5, rules.view(state, 0).level)
        assertEquals(0L, rules.view(state, 0).xpIntoLevel)
        assertEquals(800L, rules.view(state, 0).xpToNext)
    }

    @Test
    fun `early completions carry remaining XP across level thresholds`() {
        val first = complete(fresh(), "one", 0, outcome = BattleOutcome.USER_WIN)
        assertEquals(120L, first.totalXp)
        assertEquals(1, rules.view(first, 0).level)
        assertEquals(120L, rules.view(first, 0).xpIntoLevel)
        val second = complete(first, "two", 0, outcome = BattleOutcome.USER_LOSS)
        assertEquals(2, rules.view(second, 0).level)
        assertEquals(40L, rules.view(second, 0).xpIntoLevel)
        val third = complete(second, "three", 0, outcome = BattleOutcome.USER_WIN)
        assertEquals(2, rules.view(third, 0).level)
        assertEquals(160L, rules.view(third, 0).xpIntoLevel)
    }

    @Test
    fun `stored XP supports multiple level boundaries without separate point duplication`() {
        val jumped = fresh().copy(totalXp = rules.xpForLevel(60) + 37)
        val view = rules.view(jumped, 0)
        assertEquals(60, view.level)
        assertEquals(50, view.baseEarned)
        assertEquals(10, view.enhancementEarned)
        assertEquals(37L, view.xpIntoLevel)
        assertEquals(jumped, rules.initialize(jumped, 100))
    }

    @Test
    fun `all allowed official matches grow each day and an over-cap replay stays practice`() {
        var state = fresh()
        repeat(rules.DAILY_GROWTH_ENTRIES) { state = complete(state, "normal-$it", 20) }
        assertEquals(2_000L, state.totalXp)
        assertEquals(0, rules.view(state, 20).growthRemaining)
        val pending = rules.reserveBattle(state, "practice", 20)
        assertFalse(checkNotNull(pending.pending).xpEligible)
        val after = rules.completeBattle(pending, "practice")
        assertEquals(state.totalXp, after.totalXp)
        assertEquals(state.settledBattleIds, after.settledBattleIds)
        assertNull(after.pending)
        assertEquals(rules.DAILY_GROWTH_ENTRIES, after.growthEntriesUsed)
        assertEquals(rules.DAILY_GROWTH_ENTRIES, rules.view(after, 21).growthRemaining)
        assertEquals(2_100L, complete(after, "tomorrow", 21).totalXp)
    }

    @Test
    fun `explicit replay practice never consumes a growth entry`() {
        val pending = rules.reserveBattle(fresh(), "replay", 4, allowGrowth = false)
        assertFalse(checkNotNull(pending.pending).xpEligible)
        assertEquals(0, pending.growthEntriesUsed)
        val after = rules.completeBattle(pending, "replay")
        assertEquals(0L, after.totalXp)
        assertTrue(after.settledBattleIds.isEmpty())
        assertEquals(rules.DAILY_GROWTH_ENTRIES, rules.view(after, 4).growthRemaining)
    }

    @Test
    fun `reservation consumes issue day and completion across midnight does not charge new day`() {
        val pending = rules.reserveBattle(fresh(), "late", 50)
        assertEquals(50L, checkNotNull(pending.pending).issuedDay)
        assertEquals(1, pending.growthEntriesUsed)
        assertEquals(rules.DAILY_GROWTH_ENTRIES, rules.view(pending, 51).growthRemaining)
        val finished = rules.completeBattle(pending, "late")
        assertEquals(50L, finished.growthDay)
        assertEquals(1, finished.growthEntriesUsed)
        val next = rules.reserveBattle(finished, "early", 51)
        assertEquals(1, next.growthEntriesUsed)
        assertEquals(51L, next.growthDay)
        assertTrue(checkNotNull(next.pending).xpEligible)
    }

    @Test
    fun `backward local clock never resets or reopens growth quota`() {
        var state = fresh()
        repeat(rules.DAILY_GROWTH_ENTRIES) { state = complete(state, "normal-$it", 10) }
        val backwards = rules.reserveBattle(state, "backdated", 9)
        assertEquals(10L, backwards.growthDay)
        assertEquals(rules.DAILY_GROWTH_ENTRIES, backwards.growthEntriesUsed)
        assertFalse(checkNotNull(backwards.pending).xpEligible)
        assertEquals(0, rules.view(backwards, 9).growthRemaining)
        assertEquals(2_000L, rules.completeBattle(backwards, "backdated").totalXp)
        val unused = rules.reserveBattle(complete(fresh(), "today", 10), "old", 9)
        assertFalse(checkNotNull(unused.pending).xpEligible)
        assertEquals(1, unused.growthEntriesUsed)
    }

    @Test
    fun `pending duplicate and awarded duplicate return unchanged without another slot or XP`() {
        val pending = rules.reserveBattle(fresh(), "unique", 0)
        assertEquals(pending, rules.reserveBattle(pending, "unique", 0))
        assertEquals(pending, rules.reserveBattle(pending, "unique", 1))
        assertEquals(pending, rules.completeBattle(pending, "wrong"))
        val settled = rules.completeBattle(pending, "unique")
        assertEquals(100L, settled.totalXp)
        assertEquals(settled, rules.completeBattle(settled, "unique"))
        assertEquals(settled, rules.reserveBattle(settled, "unique", 999))
        assertEquals(listOf("unique"), settled.settledBattleIds)
    }

    @Test
    fun `different concurrent reservation is rejected and cannot overwrite pending battle`() {
        val pending = rules.reserveBattle(fresh(), "one", 1)
        expectIllegal { rules.reserveBattle(pending, "two", 1) }
        assertEquals("one", pending.pending?.battleId)
        assertEquals(1, pending.growthEntriesUsed)
    }

    @Test
    fun `final award is capped and later practice never grows an unbounded dedupe ledger`() {
        var state = fresh()
        val expectedAwardIds = ((rules.MAX_TOTAL_XP + rules.MATCH_XP - 1L) / rules.MATCH_XP).toInt()
        repeat(expectedAwardIds) { index ->
            state = complete(state, "award-$index", (index / rules.DAILY_GROWTH_ENTRIES).toLong())
            assertTrue(rules.isValid(state))
        }
        assertEquals(744000L, state.totalXp)
        assertEquals(7440, state.settledBattleIds.size)
        val cap = rules.view(state, 372)
        assertEquals(100, cap.level)
        assertEquals(0L, cap.xpToNext)
        assertEquals(0L, cap.xpIntoLevel)
        assertEquals(0, cap.growthRemaining)
        repeat(50) { state = complete(state, "post-cap-$it", 372 + it.toLong()) }
        assertEquals(744000L, state.totalXp)
        assertEquals(7440, state.settledBattleIds.size)
        assertEquals(state, rules.reserveBattle(state, "award-0", 999))
    }

    @Test
    fun `partial final award never carries excess XP beyond cap`() {
        val nearCap = fresh().copy(totalXp = rules.MAX_TOTAL_XP - 23)
        val final = complete(nearCap, "last", 0)
        assertEquals(rules.MAX_TOTAL_XP, final.totalXp)
        assertEquals(listOf("last"), final.settledBattleIds)
        assertEquals(50, rules.view(final, 0).enhancementAvailable)
    }

    @Test
    fun `rank one purchase spends first point but never auto purchases a trait`() {
        assertTrue(fresh().allocations.isEmpty())
        val purchase = rules.allocate(fresh(), HeroClass.WARRIOR, 10, emptySet(), warrior.id, 1, 0)
        assertTrue(purchase.accepted)
        assertNull(purchase.error)
        assertEquals(0, rules.view(purchase.state, 0).baseAvailable)
        assertEquals(listOf(ArenaSupportTraitRank(warrior.id, 1, 0)), rules.toSupportTraits(purchase.state))
        assertFalse(rules.allocate(purchase.state, HeroClass.WARRIOR, 10, emptySet(), warrior.id, 2, 0).accepted)
    }

    @Test
    fun `level fifty fifty one and fifty two keep base and enhancement budgets separate`() {
        for (level in listOf(50, 51)) {
            val rejected = rules.allocate(atLevel(level), HeroClass.WARRIOR, 10, emptySet(), warrior.id, 1, 1)
            assertFalse(rejected.accepted)
            assertEquals("enhancement_budget", rejected.error)
        }
        val accepted = rules.allocate(atLevel(52), HeroClass.WARRIOR, 10, emptySet(), warrior.id, 1, 1)
        assertTrue(accepted.accepted)
        assertEquals(49, rules.view(accepted.state, 0).baseAvailable)
        assertEquals(0, rules.view(accepted.state, 0).enhancementAvailable)
        assertEquals(listOf(0, 2, 5, 10), (0..3).map(rules::enhancementCost))
    }

    @Test
    fun `base rank refund retains appropriate enhancement and removal refunds both budgets`() {
        val full = rules.allocate(atLevel(100), HeroClass.WARRIOR, 10, emptySet(), warrior.id, 5, 3).state
        val lower = rules.allocate(full, HeroClass.WARRIOR, 10, emptySet(), warrior.id, 1, 3)
        assertTrue(lower.accepted)
        assertEquals(49, rules.view(lower.state, 0).baseAvailable)
        assertEquals(40, rules.view(lower.state, 0).enhancementAvailable)
        assertEquals(2.6, warrior.value(1, 3), 1e-9)
        assertEquals(13.0, warrior.value(5, 3), 1e-9)
        val removed = rules.allocate(lower.state, HeroClass.WARRIOR, 10, emptySet(), warrior.id, 0, 3)
        assertTrue(removed.accepted)
        assertTrue(removed.state.allocations.isEmpty())
        assertEquals(50, rules.view(removed.state, 0).baseAvailable)
        assertEquals(50, rules.view(removed.state, 0).enhancementAvailable)
        assertEquals(full.totalXp, removed.state.totalXp)
    }

    @Test
    fun `free reset keeps XP quota and exact award IDs intact`() {
        val grown = complete(atLevel(60), "real", 7)
        val purchased = rules.allocate(grown, HeroClass.WARRIOR, 10, emptySet(), warrior.id, 5, 3).state
        val reset = rules.reset(purchased)
        assertTrue(reset.accepted)
        assertTrue(reset.state.allocations.isEmpty())
        assertEquals(purchased.totalXp, reset.state.totalXp)
        assertEquals(purchased.growthDay, reset.state.growthDay)
        assertEquals(purchased.growthEntriesUsed, reset.state.growthEntriesUsed)
        assertEquals(purchased.settledBattleIds, reset.state.settledBattleIds)
        assertEquals(reset.state, rules.reset(reset.state).state)
    }

    @Test
    fun `all editing is frozen from reservation until completion`() {
        val allocated = rules.allocate(atLevel(60), HeroClass.WARRIOR, 10, emptySet(), warrior.id, 1, 1).state
        val pending = rules.reserveBattle(allocated, "locked-build", 0)
        for ((rank, enhancement) in listOf(2 to 1, 0 to 0, 1 to 2)) {
            val result = rules.allocate(pending, HeroClass.WARRIOR, 10, emptySet(), warrior.id, rank, enhancement)
            assertFalse(result.accepted)
            assertEquals("battle_pending", result.error)
            assertEquals(pending, result.state)
        }
        assertFalse(rules.reset(pending).accepted)
        assertTrue(rules.reset(rules.completeBattle(pending, "locked-build")).accepted)
    }

    @Test
    fun `catalog exposes both executable A traits for every class with current V9 values`() {
        assertEquals(144, ArenaProgressionCatalog.values.size)
        assertEquals(ArenaSupportTurnEngine.supportedTraitIds, ArenaProgressionCatalog.values.map { it.id }.toSet())
        for (heroClass in HeroClass.entries) {
            val definitions = ArenaProgressionCatalog.forClass(heroClass).filter { it.id.endsWith("_A01") || it.id.endsWith("_A02") }
            assertEquals(listOf("AT9_${heroClass.name}_A01", "AT9_${heroClass.name}_A02"),
                definitions.map { it.id })
            assertEquals(definitions.first(), ArenaProgressionCatalog.initialTrait(heroClass))
            definitions.forEach { definition ->
                assertEquals(heroClass, definition.heroClass)
                assertTrue(definition.nameKo.isNotBlank() && definition.nameEn.isNotBlank() && definition.nameJa.isNotBlank())
                assertNotNull(ArenaProgressionCatalog.find(definition.id))
                val support = definition.requiredSupportId?.let { setOf(it) } ?: emptySet()
                assertTrue(rules.allocate(fresh(), heroClass, 20, support, definition.id, 1, 0).accepted)
            }
            val a01PerRank = when (heroClass) {
                HeroClass.WARRIOR, HeroClass.RANGER, HeroClass.MAGE -> 2.0
                HeroClass.ROGUE -> .5
                HeroClass.CLERIC -> .2
                HeroClass.PALADIN -> 1.0
            }
            val a02Ranks = when (heroClass) {
                HeroClass.WARRIOR, HeroClass.ROGUE -> listOf(1.0, 2.0, 3.0, 4.0, 5.0)
                HeroClass.RANGER -> listOf(2.0, 3.0, 4.0, 5.0, 6.0)
                HeroClass.MAGE, HeroClass.CLERIC, HeroClass.PALADIN -> listOf(2.0, 4.0, 6.0, 8.0, 10.0)
            }
            for (rank in 1..5) for (enhancement in 0..3) {
                val multiplier = 1 + enhancement * .1
                assertEquals(a01PerRank * rank * multiplier, definitions[0].value(rank, enhancement), 1e-9)
                assertEquals(a02Ranks[rank - 1] * multiplier, definitions[1].value(rank, enhancement), 1e-9)
            }
        }
        assertEquals("간절한 기도", ArenaProgressionCatalog.initialTrait(HeroClass.CLERIC).nameKo)
        assertEquals(ArenaProgressionEffectUnit.MAX_HP_HEAL_PERCENT,
            ArenaProgressionCatalog.initialTrait(HeroClass.CLERIC).effectUnit)
    }

    @Test
    fun `A01 and A02 allocations share base enhancement and support ownership budgets`() {
        for (heroClass in HeroClass.entries) {
            val (a01, a02) = ArenaProgressionCatalog.forClass(heroClass)
            val supports = ArenaSupportTurnEngine.initialSupportIds(heroClass)

            val first = rules.allocate(atLevel(2), heroClass, 20, supports, a01.id, 1, 0)
            assertTrue("$heroClass A01", first.accepted)
            val both = rules.allocate(first.state, heroClass, 20, supports, a02.id, 1, 0)
            assertTrue("$heroClass A02", both.accepted)
            assertEquals(listOf(a01.id, a02.id), both.state.allocations.map { it.id })
            assertEquals(2, rules.view(both.state, 0).baseSpent)
            assertEquals(0, rules.view(both.state, 0).baseAvailable)
            val traits = rules.toSupportTraits(both.state)
            assertEquals(listOf(a01.id, a02.id), traits.map { it.id })
            val input = ArenaSupportQaFixtures.fighter(heroClass, 20, "both-$heroClass", arenaLevel = 2)
                .copy(traits = traits)
            ArenaSupportTurnEngine.validate(input)
            assertTrue(runCatching { ArenaSupportTurnEngine.validate(input.copy(arenaLevel = 1)) }.isFailure)

            val onePoint = rules.allocate(fresh(), heroClass, 20, supports, a01.id, 1, 0)
            val baseOverflow = rules.allocate(onePoint.state, heroClass, 20, supports, a02.id, 1, 0)
            assertFalse(baseOverflow.accepted)
            assertEquals("base_budget", baseOverflow.error)
            assertEquals(onePoint.state, baseOverflow.state)

            val enhanced = rules.allocate(atLevel(52), heroClass, 20, supports, a01.id, 1, 1)
            assertTrue(enhanced.accepted)
            val enhancementOverflow = rules.allocate(enhanced.state, heroClass, 20, supports, a02.id, 1, 1)
            assertFalse(enhancementOverflow.accepted)
            assertEquals("enhancement_budget", enhancementOverflow.error)
            assertEquals(enhanced.state, enhancementOverflow.state)

            a02.requiredSupportId?.let { required ->
                assertTrue(required in supports)
                val missing = rules.allocate(fresh(), heroClass, 20, supports - required, a02.id, 1, 0)
                assertFalse(missing.accepted)
                assertEquals("support_required", missing.error)
            }
        }
    }

    @Test
    fun `foreign unknown locked and unowned support purchases are rejected without spending`() {
        val mage = ArenaProgressionCatalog.initialTrait(HeroClass.MAGE)
        val cases = listOf(
            rules.allocate(fresh(), HeroClass.WARRIOR, 10, emptySet(), mage.id, 1, 0),
            rules.allocate(fresh(), HeroClass.WARRIOR, 10, emptySet(), "AT9_WARRIOR_A99", 1, 0),
            rules.allocate(fresh(), HeroClass.WARRIOR, 9, emptySet(), warrior.id, 1, 0),
            rules.allocate(fresh(), HeroClass.MAGE, 10, emptySet(), mage.id, 1, 0),
            rules.allocate(ArenaProgressionState(), HeroClass.WARRIOR, 10, emptySet(), warrior.id, 1, 0),
        )
        cases.forEach { assertFalse(it.accepted); assertTrue(it.state.allocations.isEmpty()) }
        assertEquals("foreign_trait", cases[0].error)
        assertEquals("unknown_trait", cases[1].error)
        assertEquals("support_required", cases[3].error)
    }

    @Test
    fun `corrupt rank budget identities XP and mixed class saves fail closed`() {
        val invalid = listOf(
            fresh().copy(totalXp = -1),
            fresh().copy(totalXp = rules.MAX_TOTAL_XP + 1L),
            fresh().copy(allocations = listOf(ArenaTraitAllocation(warrior.id, 5))),
            fresh().copy(allocations = listOf(ArenaTraitAllocation(warrior.id, 1, 1))),
            fresh().copy(allocations = listOf(ArenaTraitAllocation(warrior.id, 0))),
            fresh().copy(allocations = listOf(ArenaTraitAllocation("unknown", 1))),
            atLevel(100).copy(allocations = listOf(ArenaTraitAllocation(warrior.id, 1), ArenaTraitAllocation(warrior.id, 1))),
            atLevel(100).copy(allocations = listOf(ArenaTraitAllocation(warrior.id, 1), ArenaTraitAllocation("AT9_MAGE_A01", 1))),
            fresh().copy(growthEntriesUsed = rules.DAILY_GROWTH_ENTRIES + 1),
            fresh().copy(settledBattleIds = listOf("phantom")),
            atLevel(100).copy(settledBattleIds = listOf("same", "same")),
            fresh().copy(pending = ArenaProgressionPending("", 0, true)),
            ArenaProgressionState(totalXp = 100),
        )
        invalid.forEach { state ->
            assertFalse("accepted corrupt state: $state", rules.isValid(state))
            assertFalse(rules.allocate(state, HeroClass.WARRIOR, 10, emptySet(), warrior.id, 1, 0).accepted)
            assertFalse(rules.reset(state).accepted)
            expectIllegal { rules.toSupportTraits(state) }
        }
    }

    @Test
    fun `invalid new reservation parameters never produce a new state`() {
        expectIllegal { rules.reserveBattle(fresh(), "", 0) }
        expectIllegal { rules.reserveBattle(fresh(), "   ", 0) }
        expectIllegal { rules.reserveBattle(fresh(), "id", -1) }
        expectIllegal { rules.reserveBattle(ArenaProgressionState(), "id", 0) }
        expectIllegal { rules.xpForLevel(0) }
        expectIllegal { rules.xpForLevel(101) }
        expectIllegal { rules.levelFromXp(-1) }
        expectIllegal { rules.enhancementCost(4) }
    }

    @Test
    fun `serialization round trip preserves reservation allocation and dedupe`() {
        val earned = complete(atLevel(60), "settled", 10)
        val allocated = rules.allocate(earned, HeroClass.WARRIOR, 10, emptySet(), warrior.id, 2, 3).state
        val pending = rules.reserveBattle(allocated, "ongoing", 11)
        val restored = Json.decodeFromString<ArenaProgressionState>(Json.encodeToString(pending))
        assertEquals(pending, restored)
        assertTrue(rules.isValid(restored))
        assertEquals(rules.completeBattle(pending, "ongoing"), rules.completeBattle(restored, "ongoing"))
    }

    @Test
    fun `banked full level one hundred budget is preserved without phantom catalog purchases`() {
        val view = rules.view(atLevel(100), 0)
        assertEquals(50, view.baseAvailable)
        assertEquals(50, view.enhancementAvailable)
        assertTrue(rules.toSupportTraits(atLevel(100)).isEmpty())
        val one = rules.allocate(atLevel(100), HeroClass.WARRIOR, 100, emptySet(), warrior.id, 5, 3).state
        assertEquals(45, rules.view(one, 0).baseAvailable)
        assertEquals(40, rules.view(one, 0).enhancementAvailable)
    }

    private fun expectIllegal(block: () -> Unit) {
        var thrown = false
        try { block() } catch (_: IllegalArgumentException) { thrown = true }
        assertTrue("Expected invalid input to be rejected", thrown)
    }
}
