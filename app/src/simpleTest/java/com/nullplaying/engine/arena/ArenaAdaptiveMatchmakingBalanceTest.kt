package com.nullplaying.engine.arena

import com.nullplaying.engine.PublicPlayerBattleDerivation
import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.model.BattleOutcome
import com.nullplaying.model.HeroClass
import org.junit.Assert.*
import org.junit.Test

class ArenaAdaptiveMatchmakingBalanceTest {
    @Test fun `10800 actual battles compare weak average and strong characters before and after adaptation`() {
        val engine=SimpleGameEngine()
        var total=0
        val totals=mutableMapOf<Pair<Int,Boolean>,IntArray>()
        for(level in listOf(20L,25L,30L)) for(powerPercent in listOf(60,100,140)) {
            val cell=mutableMapOf<Boolean,IntArray>()
            for(heroClass in HeroClass.entries) repeat(5) { sample ->
                val power=ArenaAdaptiveMatchmaking.averagePower(level)*powerPercent/100
                val roll=engine.rollStats(99_005_100L+sample*203,heroClass)
                val stats=roll.stats.copy()
                ArenaSyntheticProfileGrowth.grow(engine,stats,heroClass,targetLevel=level,identitySeed=roll.nextSeed)
                val derived=requireNotNull(PublicPlayerBattleDerivation.deriveStats(heroClass,level,power,stats))
                val fighter=requireNotNull(ArenaV6OpponentInputFactory.create(
                    projectionId="adaptive-player-$heroClass-$sample",displayName="Balance",heroClass=heroClass,
                    level=level,combatPower=power,stats=derived,
                    learnedSkills=PublicPlayerBattleDerivation.learnedSkillsForClassAndLevel(heroClass,level),
                    arenaLevel=ArenaCharacterPointRules.budget(level),stableSeed=sample.toLong(),
                    buildPreset=ArenaAutoBuildPreset.entries[(sample+heroClass.ordinal)%10],
                )).combat
                for(adaptive in listOf(false,true)) {
                    val history=mutableListOf<ArenaRecentMatch>()
                    var streak=0;var longest=0
                    repeat(20) { sequence ->
                        val selection=selectArenaOpponent(false,null,"adaptive-balance-$heroClass-$sample",level,900000,
                            sequence.toLong(),if(adaptive) power else null,history)
                        assertTrue("level=$level power=$power adaptive=$adaptive: $selection",selection is ArenaServerMatchSelectionResult.Ready)
                        val selected=selection as ArenaServerMatchSelectionResult.Ready
                        assertEquals(level,selected.opponent.projection.level)
                        if(adaptive) assertTrue(selected.opponent.projection.verifiedPower*10 in power*9..power*11)
                        val seed=90_000_000L+level*100_000+heroClass.ordinal*1000+sample*20+sequence
                        val result=ArenaSupportTurnEngine.simulate(fighter,selected.opponent.combat,seed,recordEvents=false)
                        assertEquals(ArenaRunStatus.COMPLETED,result.status)
                        val outcome=when(result.winnerId) { fighter.fighter.id -> BattleOutcome.USER_WIN; null -> BattleOutcome.DRAW; else -> BattleOutcome.USER_LOSS }
                        val index=when(outcome){BattleOutcome.USER_WIN->0;BattleOutcome.USER_LOSS->1;else->2}
                        val tally=cell.getOrPut(adaptive){IntArray(4)};tally[index]++
                        if(outcome==BattleOutcome.USER_LOSS){streak++;longest=maxOf(longest,streak)}else streak=0
                        history.add(0,ArenaRecentMatch("battle-$sequence",selected.opponent.projection.projectionId,level,
                            outcome,power,selected.matchmakingProfile?.adjustmentPermille?:0))
                        total++
                    }
                    cell.getValue(adaptive)[3]+=longest
                }
            }
            cell.forEach { (adaptive,v) ->
                println("adaptive-balance level=$level power=$powerPercent adaptive=$adaptive wins=${v[0]} losses=${v[1]} draws=${v[2]} longestLossStreakSum=${v[3]} profiles=30")
                val sum=totals.getOrPut(powerPercent to adaptive){IntArray(4)}
                v.indices.forEach { sum[it]+=v[it] }
                assertEquals(0,v[2])
            }
        }
        assertEquals(10800,total)
        totals.forEach { (key,v)->println("adaptive-total power=${key.first} adaptive=${key.second} tally=${v.joinToString("/")}") }
        val weakBefore=totals.getValue(60 to false);val weakAfter=totals.getValue(60 to true)
        assertTrue("weak characters should win more",weakAfter[0]>weakBefore[0])
        assertTrue("weak characters should have shorter losing streaks",weakAfter[3]<weakBefore[3])
        val ordinary=totals.getValue(100 to true)
        val rate=ordinary[0].toDouble()/(ordinary[0]+ordinary[1])
        assertTrue("ordinary win rate=$rate",rate in .35.. .65)
        // With player-relative local opponents, stronger equipment also raises opponent power.
        // Every power band should remain competitive, including the old 80/120% stat limits.
        for(power in listOf(60,100,140)) {
            val outcomes=totals.getValue(power to true)
            val winRate=outcomes[0].toDouble()/(outcomes[0]+outcomes[1])
            assertTrue("power=$power win rate=$winRate",winRate in .35.. .65)
        }
    }
}
