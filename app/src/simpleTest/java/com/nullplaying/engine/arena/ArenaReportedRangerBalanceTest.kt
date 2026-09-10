package com.nullplaying.engine.arena

import com.nullplaying.engine.PublicPlayerBattleDerivation
import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.model.BattleOutcome
import com.nullplaying.model.HeroClass
import com.nullplaying.model.HeroStats
import org.junit.Assert.*
import org.junit.Test

/** The exact photographed 15-point player build, against the app's actual twenty local identities. */
class ArenaReportedRangerBalanceTest {
    @Test fun `reported level fifteen ranger is evaluated against actual local reserve paths`() {
        val engine=SimpleGameEngine()
        val c=HeroClass.RANGER
        val desired=mapOf("A01" to 10,"A02" to 2,"A03" to 1,"A04" to 1,"S01" to 1)
        val histories=listOf(emptyList(),List(5) { ArenaRecentMatch("win-$it","old-$it",15,
            BattleOutcome.USER_WIN,157,0) },List(10) { ArenaRecentMatch("win-$it","old-$it",15,
            BattleOutcome.USER_WIN,157,100) })
        val pools=histories.map { history ->
            val profile=requireNotNull(ArenaAdaptiveMatchmaking.profile(15,157,history))
            requireNotNull(ArenaLocalReserveMatchmaking.build(requesterLevel=15,count=20,
                matchmakingProfile=profile,requesterStats=HeroStats(11,22,22,16,30,17,186,158))).map { it.combat.withIdentityOpponentRules() }
        }
        val rates=mutableListOf<Double>()
        for((condition,pool) in pools.withIndex()) {
            var wins=0;var draws=0;var battles=0;var fiveWins=0
            val classes=HeroClass.entries.associateWith { IntArray(2) }
            repeat(32) { sample ->
                val roll=engine.rollStats(812000L+sample,c)
                val state=engine.newGame("reported-ranger",c,roll.stats.copy(),roll.nextSeed,1000L)
                state.hero.stats=HeroStats(11,22,22,16,30,17,186,158)
                assertEquals(listOf(11L,22L,22L,16L,30L,17L,186L,158L),state.hero.stats.values())
                state.hero.level=15
                state.classGuidedLevelGrowths=14
                state.skills.clear()
                state.skills.addAll(PublicPlayerBattleDerivation.learnedSkillsForClassAndLevel(c,15))
                val ranks=desired.mapKeys { (slot,_) -> ArenaSkillTreeCatalog.forClass(c).single { it.slotKey==slot }.id }
                var tree=ArenaSkillTreeRules.initialize(null,c)
                for((id,rank) in ranks) for(step in 1..rank) {
                    val result=ArenaSkillTreeRules.allocate(tree,c,15,ArenaTurnInputAdapter.ownedAttackIds(state),id,step,true)
                    assertTrue("Invalid photographed allocation: $id",result.accepted);tree=result.state
                }
                assertEquals(15,ArenaSkillTreeRules.spentPoints(tree))
                val support=ranks.filterKeys { ArenaIdentityCatalog.find(it)!!.support }
                val built=requireNotNull(ArenaTurnInputAdapter.fromStateForCombatPower(state,"reported-ranger",157,
                    ranks.filterKeys { it !in support }))
                assertTrue(built.rejectedSkills.isEmpty())
                val player=ArenaSupportInput(built.fighter,support.keys,arenaLevel=15,
                    supportRanks=support).withIdentityRules()
                if(sample==0) {
                    println("REPORTED_PLAYER,$condition,${player.fighter.stats},${player.identity!!.stats}")
                    pool.forEach { println("REPORTED_OPPONENT,$condition,${it.fighter.heroClass},${it.fighter.stats},${it.identity!!.skills.map { skill -> skill.slot to skill.rank }}") }
                }
                var streak=0
                repeat(8) { cycle -> for((slot,opponent) in pool.withIndex()) {
                    val result=ArenaSupportTurnEngine.simulate(player,opponent,
                        913700L+sample*10000+cycle*100+slot,recordEvents=false)
                    assertEquals(ArenaRunStatus.COMPLETED,result.status)
                    val won=result.winnerId==player.fighter.id
                    if(won) wins++ else if(result.winnerId==null) draws++
                    streak=if(won) streak+1 else 0
                    if(streak==5) fiveWins++
                    classes.getValue(opponent.fighter.heroClass)[0]+=if(won) 1 else 0
                    classes.getValue(opponent.fighter.heroClass)[1]++
                    battles++
                } }
            }
            val rate=(wins+.5*draws)/battles
            println("REPORTED_RANGER,condition=${listOf("neutral","five_wins","max_winning")[condition]},battles=$battles,wins=$wins,draws=$draws,rate=$rate,five_win_runs=$fiveWins")
            classes.forEach { (clazz,t) -> println("REPORTED_RANGER_CLASS,$condition,$clazz,${t[0]},${t[1]}") }
            rates+=rate
        }
        assertTrue("Photographed build dominates real local roster: $rates",rates[0] in .35.. .65 && rates[1] in .30.. .60 && rates[2] in .25.. .55)
    }
}
