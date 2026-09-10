package com.nullplaying.ui

import com.nullplaying.engine.BattleQaMatchFactory
import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.engine.SkillCatalog
import com.nullplaying.engine.arena.ArenaAttackInput
import com.nullplaying.engine.arena.ArenaProgressionCatalog
import com.nullplaying.engine.arena.ArenaProgressionRules
import com.nullplaying.engine.arena.ArenaProgressionState
import com.nullplaying.engine.arena.ArenaRunStatus
import com.nullplaying.engine.arena.ArenaSupportEventType
import com.nullplaying.engine.arena.ArenaSupportInput
import com.nullplaying.engine.arena.ArenaSupportTraitRank
import com.nullplaying.engine.arena.ArenaSupportTurnEngine
import com.nullplaying.engine.arena.ArenaSyntheticProfileGrowth
import com.nullplaying.engine.arena.ArenaTurnInputAdapter
import com.nullplaying.localization.AppLanguage
import com.nullplaying.model.BattleGuidance
import com.nullplaying.model.BattleSeasonStanding
import com.nullplaying.model.BattleTicketState
import com.nullplaying.model.HeroClass
import com.nullplaying.model.LearnedSkill
import com.nullplaying.model.SimpleGameState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** QA may lower arena-only hero gates, never a saved hero level or an attack's unlock level. */
class ArenaQaLevelGateTest {
    @Test
    fun `QA preparation can initialize an omitted growth ledger without pretending the hero is level ten`() {
        val state = character(HeroClass.MAGE, 1)
        val stats = state.hero.stats.copy()
        val prepared = prepareArenaBattle(state, match(state), BattleSeasonStanding(),
            BattleTicketState(DAY, 3), BattleStance.BALANCED, AppLanguage.KOREAN,
            ignoreHeroLevelGate = true)
        val live = requireNotNull(prepared?.first?.supportBattle)
        assertEquals(1L, live.user.fighter.level)
        assertEquals(1, live.user.arenaLevel)
        assertTrue(live.user.traits.isEmpty())
        assertEquals(stats, state.hero.stats)
        assertEquals(1L, state.hero.level)
    }

    @Test
    fun `every class at real hero levels one six and nine can finish a QA arena duel`() {
        HeroClass.entries.forEach { heroClass ->
            listOf(1, 6, 9).forEach { level ->
                val state = character(heroClass, level)
                val levelBefore = state.hero.level
                val statsBefore = state.hero.stats.copy()
                val skillsBefore = state.skills.map { it.copy() }
                val growth = purchasedFirstTrait(state)
                val match = match(state)
                val prepared = prepareArenaBattle(state, match, BattleSeasonStanding(),
                    BattleTicketState(DAY, 3), BattleStance.BALANCED, AppLanguage.KOREAN,
                    progression = growth, ignoreHeroLevelGate = true)

                assertNotNull("$heroClass Lv.$level QA preparation failed", prepared)
                val result = requireNotNull(prepared)
                val live = requireNotNull(result.first.supportBattle)
                val simulation = live.simulation
                val codec = kotlinx.serialization.json.Json { encodeDefaults = true }
                val saved = codec.decodeFromString<ArenaSavedBattleContract>(
                    codec.encodeToString(ArenaSavedBattleContract.serializer(), live.savedContract()))
                assertTrue(saved.ignoreHeroLevelGate)
                assertEquals(simulation, ArenaSupportTurnEngine.simulate(saved.user, saved.opponent,
                    saved.seed, saved.rules, ignoreHeroLevelGate = saved.ignoreHeroLevelGate))
                assertEquals(level.toLong(), live.user.fighter.level)
                assertEquals(level.toLong(), result.first.userLevel)
                assertEquals(match.request.opponent.level.coerceIn(1L, 100L), live.opponent.fighter.level)
                assertTrue(live.opponent.fighter.level < 10L || level == 9)
                assertEquals(ArenaSupportTurnEngine.initialSupportIds(heroClass), live.user.supportIds)
                assertEquals(1, live.user.arenaLevel)
                assertEquals(1, live.user.traits.single().rank)
                assertEquals(ArenaRunStatus.COMPLETED, simulation.status)
                assertEquals(ArenaSupportEventType.END, simulation.events.last().type)
                val winner = requireNotNull(simulation.winnerId)
                assertTrue(simulation.fighters.getValue(winner).hp > 0.0)
                assertEquals(1, simulation.fighters.values.count { it.hp == 0.0 })
                simulation.fighters.values.forEach { fighter ->
                    assertTrue(fighter.mpUnits in 0..fighter.maxMpUnits)
                    assertTrue(fighter.hp in 0.0..fighter.maxHp)
                }
                assertEquals(levelBefore, state.hero.level)
                assertEquals(statsBefore, state.hero.stats)
                assertEquals(skillsBefore, state.skills)
                assertEquals(0L, growth.totalXp)
                assertNull(growth.pending)
            }
        }
    }

    @Test
    fun `the normal path still refuses low heroes even with a previously QA unlocked ledger`() {
        HeroClass.entries.forEach { heroClass ->
            listOf(1, 6, 9).forEach { level ->
                val state = character(heroClass, level)
                val strict = ArenaProgressionRules.initialize(ArenaProgressionState(), state.hero.level)
                assertFalse(strict.unlocked)
                val qaGrowth = purchasedFirstTrait(state)
                assertNull(prepareArenaBattle(state, match(state), BattleSeasonStanding(),
                    BattleTicketState(DAY, 3), BattleStance.BALANCED, AppLanguage.KOREAN,
                    progression = qaGrowth))
                val allocation = ArenaProgressionRules.allocate(qaGrowth, heroClass, state.hero.level,
                    ArenaSupportTurnEngine.initialSupportIds(heroClass),
                    ArenaProgressionCatalog.initialTrait(heroClass).id, 1, 0)
                assertFalse(allocation.accepted)
                assertEquals(qaGrowth, allocation.state)
                assertTrue(runCatching { ArenaSupportTurnEngine.validate(input(state, qaGrowth)) }.isFailure)
            }
        }
    }

    @Test
    fun `QA first point unlock is idempotent and cannot bypass rank enhancement or support ownership budgets`() {
        HeroClass.entries.forEach { heroClass ->
            val state = character(heroClass, 1)
            val growth = ArenaProgressionRules.initialize(ArenaProgressionState(), 1L, ignoreHeroLevelGate = true)
            assertTrue(growth.unlocked)
            assertEquals(growth, ArenaProgressionRules.initialize(growth, 1L, ignoreHeroLevelGate = true))
            val view = ArenaProgressionRules.view(growth, DAY)
            assertEquals(1, view.level)
            assertEquals(1, view.baseAvailable)
            assertEquals(0, view.enhancementAvailable)
            assertEquals(ArenaProgressionRules.DAILY_GROWTH_ENTRIES, view.growthRemaining)
            val trait = ArenaProgressionCatalog.initialTrait(heroClass)
            val supports = ArenaSupportTurnEngine.initialSupportIds(heroClass)
            fun allocate(rank: Int, enhancement: Int, owned: Set<String> = supports) =
                ArenaProgressionRules.allocate(growth, heroClass, state.hero.level, owned, trait.id,
                    rank, enhancement, ignoreHeroLevelGate = true)
            assertTrue(allocate(1, 0).accepted)
            assertFalse(allocate(2, 0).accepted)
            assertFalse(allocate(1, 1).accepted)
            if (trait.requiredSupportId != null) assertFalse(allocate(1, 0, emptySet()).accepted)
            val foreign = ArenaProgressionCatalog.values.first { it.heroClass != heroClass }
            assertFalse(ArenaProgressionRules.allocate(growth, heroClass, state.hero.level, supports,
                foreign.id, 1, 0, ignoreHeroLevelGate = true).accepted)
        }
    }

    @Test
    fun `QA gate bypass keeps unowned foreign and hero level locked attacks out of the actual input`() {
        val state = character(HeroClass.WARRIOR, 1)
        val owned = state.skills.single().catalogId
        val foreign = SkillCatalog.forClass(HeroClass.MAGE).first()
        val locked = SkillCatalog.forClass(HeroClass.WARRIOR).first { it.unlockLevel > 1 }
        state.skills += LearnedSkill(81, foreign.name, 1, foreign.description, catalogId = foreign.catalogId)
        state.skills += LearnedSkill(82, locked.name, 1, locked.description, catalogId = locked.catalogId)
        val before = state.skills.map { it.copy() }
        val prepared = requireNotNull(prepareArenaBattle(state, match(state), BattleSeasonStanding(),
            BattleTicketState(DAY, 3), BattleStance.BALANCED, AppLanguage.KOREAN,
            progression = purchasedFirstTrait(state), ignoreHeroLevelGate = true))

        val live = requireNotNull(prepared.first.supportBattle)
        assertEquals(listOf(owned), live.user.fighter.attacks.map { it.id })
        assertEquals(before, state.skills)
        assertEquals(1L, state.hero.level)
        val foreignAttack = ArenaAttackInput(foreign.catalogId, foreign.name, 1, 0,
            foreign.damagePercentMin, foreign.damagePercentMax)
        assertTrue(runCatching {
            ArenaSupportTurnEngine.validate(live.user.copy(fighter = live.user.fighter.copy(
                attacks = listOf(foreignAttack))), ignoreHeroLevelGate = true)
        }.isFailure)
        assertTrue(runCatching {
            // The fighter invariant rejects an unearned attack tier before any arena gate runs.
            live.user.fighter.copy(attacks = listOf(ArenaAttackInput(locked.catalogId, locked.name,
                locked.unlockLevel / 5 + 1, 0, locked.damagePercentMin, locked.damagePercentMax)))
        }.isFailure)
    }

    @Test
    fun `QA support validation still rejects foreign supports and excessive trait budgets`() {
        val state = character(HeroClass.MAGE, 1)
        val valid = input(state, purchasedFirstTrait(state))
        ArenaSupportTurnEngine.validate(valid, ignoreHeroLevelGate = true)
        val foreignSupport = ArenaSupportTurnEngine.initialSupportIds(HeroClass.ROGUE).first()
        val foreignTrait = ArenaProgressionCatalog.initialTrait(HeroClass.ROGUE).id
        listOf(
            valid.copy(supportIds = valid.supportIds + foreignSupport),
            valid.copy(traits = listOf(ArenaSupportTraitRank(foreignTrait, 1))),
            valid.copy(traits = listOf(valid.traits.single().copy(rank = 2))),
            valid.copy(traits = listOf(valid.traits.single().copy(enhancement = 1))),
            valid.copy(arenaLevel = 101),
        ).forEach { invalid ->
            assertTrue(runCatching { ArenaSupportTurnEngine.validate(invalid, ignoreHeroLevelGate = true) }.isFailure)
        }
    }

    @Test
    fun `QA low level entry gives XP for every allowed match without extra starting XP`() {
        var growth = ArenaProgressionRules.initialize(ArenaProgressionState(), 1L, ignoreHeroLevelGate = true)
        repeat(ArenaProgressionRules.DAILY_GROWTH_ENTRIES + 1) { index ->
            val id = "qa-low-level-$index"
            growth = ArenaProgressionRules.reserveBattle(growth, id, DAY)
            assertEquals(index < ArenaProgressionRules.DAILY_GROWTH_ENTRIES, growth.pending?.xpEligible)
            growth = ArenaProgressionRules.completeBattle(growth, id)
        }
        val view = ArenaProgressionRules.view(growth, DAY)
        assertEquals(2_000L, growth.totalXp)
        assertEquals(ArenaProgressionRules.DAILY_GROWTH_ENTRIES, growth.settledBattleIds.size)
        assertEquals(ArenaProgressionRules.levelFromXp(growth.totalXp), view.level)
        assertEquals(5, view.level)
        assertEquals(5, view.baseAvailable)
        assertEquals(0, view.enhancementAvailable)
        assertEquals(0, view.growthRemaining)
    }

    @Test
    fun `QA ignore flag does not bypass exhausted battle tickets`() {
        val state = character(HeroClass.WARRIOR, 1)
        assertNull(prepareArenaBattle(state, match(state), BattleSeasonStanding(), BattleTicketState(DAY, 0),
            BattleStance.BALANCED, AppLanguage.KOREAN, progression = purchasedFirstTrait(state),
            ignoreHeroLevelGate = true))
    }

    @Test
    fun `QA unlock still requires a valid hero level of at least one`() {
        assertFalse(ArenaProgressionRules.initialize(ArenaProgressionState(), 0L,
            ignoreHeroLevelGate = true).unlocked)
        assertFalse(ArenaProgressionRules.initialize(ArenaProgressionState(), -1L,
            ignoreHeroLevelGate = true).unlocked)
    }

    private fun input(state: SimpleGameState, growth: ArenaProgressionState) = ArenaSupportInput(
        fighter = ArenaTurnInputAdapter.fromState(state, "low-level-user").fighter,
        supportIds = ArenaSupportTurnEngine.initialSupportIds(state.hero.heroClass),
        traits = ArenaProgressionRules.toSupportTraits(growth),
        arenaLevel = ArenaProgressionRules.levelFromXp(growth.totalXp),
    )

    private fun purchasedFirstTrait(state: SimpleGameState): ArenaProgressionState {
        val opened = ArenaProgressionRules.initialize(ArenaProgressionState(), state.hero.level,
            ignoreHeroLevelGate = true)
        val purchase = ArenaProgressionRules.allocate(opened, state.hero.heroClass, state.hero.level,
            ArenaSupportTurnEngine.initialSupportIds(state.hero.heroClass),
            ArenaProgressionCatalog.initialTrait(state.hero.heroClass).id, 1, 0,
            ignoreHeroLevelGate = true)
        assertTrue(purchase.accepted)
        return purchase.state
    }

    private fun match(state: SimpleGameState) = BattleQaMatchFactory.createMatch(
        state, 1_000L, BattleGuidance.BALANCED, 1_000, 0,
        "123e4567-e89b-42d3-a456-426614174488", 41L, 1_000L,
    )

    private fun character(heroClass: HeroClass, level: Int): SimpleGameState {
        val engine = SimpleGameEngine()
        val roll = engine.rollStats(71L, heroClass)
        return engine.newGame("저레벨검수", heroClass, roll.stats.copy(), roll.nextSeed, 1_000L).apply {
            rngState = ArenaSyntheticProfileGrowth.grow(
                engine, hero.stats, heroClass,
                targetLevel = level.toLong(), identitySeed = rngState,
            )
            hero.level = level.toLong()
            skills.clear()
            val skill = SkillCatalog.forClass(heroClass).first { it.unlockLevel == 1 }
            skills += LearnedSkill(1, skill.name, 1, skill.description, catalogId = skill.catalogId)
        }
    }

    private companion object { const val DAY = 20_702L }
}
