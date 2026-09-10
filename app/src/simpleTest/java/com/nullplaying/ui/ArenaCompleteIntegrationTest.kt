package com.nullplaying.ui

import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.engine.SkillCatalog
import com.nullplaying.engine.arena.*
import com.nullplaying.localization.AppLanguage
import com.nullplaying.model.HeroClass
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ArenaCompleteIntegrationTest {
    @Test fun `simultaneous lethal dots render a draw only after both zero HP events`() {
        val events = listOf(
            ArenaSupportEvent(0,0,ArenaSupportEventType.START,"a",hpBefore=10.0,hpAfter=10.0),
            ArenaSupportEvent(1,0,ArenaSupportEventType.START,"b",hpBefore=10.0,hpAfter=10.0),
            ArenaSupportEvent(2,1,ArenaSupportEventType.DOT_DAMAGE,"a","b",hpBefore=10.0,hpAfter=0.0,amount=10.0,reason="poison"),
            ArenaSupportEvent(3,1,ArenaSupportEventType.DOT_DAMAGE,"b","a",hpBefore=10.0,hpAfter=0.0,amount=10.0,reason="burn"),
            ArenaSupportEvent(4,1,ArenaSupportEventType.KO,"a",hpBefore=0.0,hpAfter=0.0),
            ArenaSupportEvent(5,1,ArenaSupportEventType.KO,"b",hpBefore=0.0,hpAfter=0.0),
            ArenaSupportEvent(6,1,ArenaSupportEventType.END,reason="simultaneous_dot_draw"),
        )
        val result = ArenaSupportResult(ArenaRunStatus.COMPLETED,null,1,
            mapOf("a" to ArenaSupportFighterResult(0.0,10.0,0,0,0.0),
                "b" to ArenaSupportFighterResult(0.0,10.0,0,0,0.0)),events)
        val timeline = buildArenaLiveTimeline(result,mapOf("a" to "Noah","b" to "Eve"))
        assertTrue(timeline.beats.last().terminal)
        assertTrue(timeline.beats.last().after.values.all { it.hp == 0.0 })
        assertTrue(runCatching { buildArenaLiveTimeline(result.copy(events=events.filterNot { it.sequence==3 }),
            mapOf("a" to "Noah","b" to "Eve")) }.isFailure)
    }

    @Test fun `sixty automatic support grants persist through every unlock boundary without modifying growth`() {
        HeroClass.entries.forEach { heroClass ->
            var snapshot = BattleLocalSnapshot(score = 947, wins = 9,
                arenaProgression = ArenaProgressionState(unlocked = true, totalXp = 600))
            for (level in 1L..110L) {
                val ownership = reconcileArenaSupportOwnership(snapshot.arenaSupportOwnership, heroClass, level)
                val updated = snapshot.copy(arenaSupportOwnership = ownership)
                snapshot = requireNotNull(decodeBattleLocalSnapshot(encodeBattleLocalSnapshot(updated)))
                assertEquals(updated, snapshot)
                assertEquals(ownership, reconcileArenaSupportOwnership(ownership, heroClass, level))
                assertEquals(ArenaSupportCatalog.forClass(heroClass).filter { it.unlockLevel <= level }.map { it.id }.toSet(), ownership.ids)
                assertEquals(600L, snapshot.arenaProgression.totalXp)
                assertEquals(947, snapshot.score)
            }
            assertEquals(10, snapshot.arenaSupportOwnership!!.ids.size)
        }
        assertEquals(60, ArenaSupportCatalog.values.size)
        assertEquals(ArenaSupportCatalog.values.map { it.id }.toSet(), ArenaSupportTurnEngine.supportedSupportIds)
    }

    @Test fun `legacy saves extend once and failed storage preserves grants and growth`() {
        val old = requireNotNull(decodeBattleLocalSnapshot("""{"wins":4,"score":981}"""))
        assertNull(old.arenaSupportOwnership)
        assertEquals(0L, old.scoreAchievedAtMillis)
        val extended = old.copy(arenaSupportOwnership = reconcileArenaSupportOwnership(null, HeroClass.MAGE, 30))
        assertEquals(4, extended.arenaSupportOwnership!!.ids.size)
        assertNull(persistArenaProgression(extended, extended.arenaProgression) { false })
        assertNull(old.arenaSupportOwnership)
        assertEquals(4, old.wins)
        assertEquals(981, old.score)
        assertEquals(extended, decodeBattleLocalSnapshot(encodeBattleLocalSnapshot(extended)))
    }

    @Test fun `foreign unknown and prematurely granted supports are rejected`() {
        val mage = reconcileArenaSupportOwnership(null, HeroClass.MAGE, 100)
        assertTrue(runCatching { reconcileArenaSupportOwnership(mage, HeroClass.ROGUE, 100) }.isFailure)
        assertTrue(runCatching { reconcileArenaSupportOwnership(mage, HeroClass.MAGE, 10) }.isFailure)
        assertTrue(runCatching { reconcileArenaSupportOwnership(mage.copy(ids = setOf("unknown")), HeroClass.MAGE, 100) }.isFailure)
    }

    @Test fun `all 144 purchased traits survive storage and every UI list is the same class catalog`() {
        assertEquals(144, ArenaProgressionCatalog.values.size)
        assertEquals(ArenaProgressionCatalog.values.map { it.id }.toSet(), ArenaSupportTurnEngine.supportedTraitIds)
        HeroClass.entries.forEach { heroClass ->
            val definitions = arenaProgressionUiDefinitions(heroClass)
            assertEquals(24, definitions.size)
            assertEquals(ArenaProgressionCatalog.forClass(heroClass), definitions)
            definitions.forEach { definition ->
                val initial = ArenaProgressionState(unlocked = true, totalXp = ArenaProgressionRules.MAX_TOTAL_XP)
                val acquired = ArenaProgressionRules.allocate(initial, heroClass, 100,
                    ArenaSupportCatalog.unlockedIds(heroClass, 100), definition.id, definition.maxRank,
                    if (definition.isCore) 0 else 3)
                assertTrue("${definition.id}: ${acquired.error}", acquired.accepted)
                val saved = BattleLocalSnapshot(arenaProgression = acquired.state,
                    arenaSupportOwnership = reconcileArenaSupportOwnership(null, heroClass, 100))
                assertEquals(saved, decodeBattleLocalSnapshot(encodeBattleLocalSnapshot(saved)))
                AppLanguage.entries.forEach { language ->
                    assertTrue(arenaProgressionTraitName(definition, language).isNotBlank())
                    assertTrue(arenaProgressionTraitEffect(definition, definition.maxRank,
                        if (definition.isCore) 0 else 3, language).isNotBlank())
                }
                val reset = ArenaProgressionRules.reset(acquired.state)
                assertTrue(reset.accepted)
                assertEquals(50, ArenaProgressionRules.view(reset.state, 0).baseAvailable)
                assertEquals(50, ArenaProgressionRules.view(reset.state, 0).enhancementAvailable)
            }
        }
    }

    @Test fun `full saved participant contracts replay identically after serialization`() {
        HeroClass.entries.forEachIndexed { index, heroClass ->
            val user = input(heroClass, "user", 60, 50)
            val opponent = input(HeroClass.entries[(index + 1) % 6], "opponent", 60, 50)
            val rules = ArenaTurnRules()
            val first = ArenaSupportTurnEngine.simulate(user, opponent, 902L, rules)
            assertEquals(ArenaRunStatus.COMPLETED, first.status)
            val contract = ArenaSavedBattleContract(seed = 902L, user = user, opponent = opponent,
                rules = rules, progressionRevision = 7, issuedDay = 20700)
            val restored = Json.decodeFromString<ArenaSavedBattleContract>(Json.encodeToString(contract))
            assertEquals(contract, restored)
            assertEquals(first, ArenaSupportTurnEngine.simulate(restored.user, restored.opponent, restored.seed, restored.rules))
            val names = mapOf("user" to "Noah", "opponent" to "Eve")
            listOf("ko", "en", "ja").forEach { language ->
                val timeline = buildArenaLiveTimeline(first, names, language)
                val last = timeline.beats.last()
                assertTrue(last.terminal)
                first.fighters.forEach { (id, f) ->
                    assertEquals(f.hp, last.after.getValue(id).hp, 1e-6)
                    assertEquals(f.mpUnits, last.after.getValue(id).mpUnits)
                    assertEquals(f.shield, last.after.getValue(id).shield, 1e-6)
                }
                assertTrue(timeline.logs.none { "AT9_" in it.text || "ARENA_SUP_" in it.text })
            }
        }
    }

    companion object {
        fun input(heroClass: HeroClass, id: String, heroLevel: Int, arenaLevel: Int): ArenaSupportInput {
            val engine = SimpleGameEngine()
            val roll = engine.rollStats(901L + heroClass.ordinal, heroClass)
            val state = engine.newGame(id, heroClass, roll.stats.copy(), roll.nextSeed, 1000L)
            state.rngState = ArenaSyntheticProfileGrowth.grow(
                engine, state.hero.stats, heroClass,
                targetLevel = heroLevel.toLong(), identitySeed = state.rngState,
            )
            state.hero.level = heroLevel.toLong()
            val base = ArenaTurnInputAdapter.fromState(state, id).fighter
            return ArenaSupportInput(base.copy(attacks = SkillCatalog.forClass(heroClass).filter { it.unlockLevel <= heroLevel }.map {
                ArenaAttackInput(it.catalogId, it.name, if (it.unlockLevel == 1) 1 else it.unlockLevel / 5 + 1,
                    0, it.damagePercentMin, it.damagePercentMax)
            }), ArenaSupportCatalog.unlockedIds(heroClass, heroLevel.toLong()),
                arenaNpcTraitAllocation(heroClass, arenaLevel, heroLevel.toLong()), arenaLevel)
        }
    }
}
