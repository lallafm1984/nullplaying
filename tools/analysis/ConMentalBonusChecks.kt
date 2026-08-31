package com.nullplaying.engine

import com.nullplaying.model.HeroClass
import kotlin.math.abs

object ConMentalBonusChecks {
    @JvmStatic fun main(args: Array<String>) {
        val engine=SimpleGameEngine(OfflineAdventureConfig(480,12))
        BalanceSimulationHooks.configure("con_mental,180")
        var checks=0
        fun verify(ok:Boolean) { check(ok);checks++ }
        for(cls in HeroClass.entries) {
            val state=engine.newGame("LOCAL_BOUNDARY",cls,engine.rollStats(12341L,cls).stats,9182L,0L)
            val maxProc=if(cls==HeroClass.MAGE) .35 else .30
            for(level in listOf(0L,1L,10L,60L,99L,100L,120L,Long.MAX_VALUE)) {
                state.hero.level=level
                var priorH=8.0;var priorP=.2;var priorSearch=5000L;var priorSale=0.0
                for(value in listOf(0L,1L,18L,128L,129L,149L,150L,151L,179L,180L,181L,Long.MAX_VALUE)) {
                    with(state.hero.stats) { constitution=value;intelligence=value;wisdom=value;dexterity=value;charisma=value }
                    val h=BalanceSimulationHooks.offlineHours(state)
                    val p=BalanceSimulationHooks.rawProbability(state,20)
                    val search=BalanceSimulationHooks.encounterMillis(state,5000)
                    val sale=BalanceSimulationHooks.saleBonus(state)
                    verify(h in 8.0..10.0 && h>=priorH)
                    verify(p>=priorP-1e-12 && p<=maxProc+1e-12)
                    verify(search in 4000..5000 && search<=priorSearch)
                    verify(sale in 0.0..0.2 && sale>=priorSale)
                    verify(BalanceSimulationHooks.sale(state,0)==0L)
                    verify(BalanceSimulationHooks.sale(state,Long.MAX_VALUE)==Long.MAX_VALUE)
                    if(level<100) verify(h<10 && search>4000 && sale<.2)
                    priorH=h;priorP=p;priorSearch=search;priorSale=sale
                }
            }
            state.hero.level=100
            state.hero.stats.constitution=150
            state.hero.stats.intelligence=180;state.hero.stats.wisdom=180
            verify(abs(BalanceSimulationHooks.offlineHours(state)-10)<1e-12)
            verify(abs(BalanceSimulationHooks.rawProbability(state,20)-maxProc)<1e-12)
            // Equal mental scores give identical added bonuses at the same P0.
            state.hero.stats.intelligence=200;state.hero.stats.wisdom=40
            val p=BalanceSimulationHooks.rawProbability(state,20)
            state.hero.stats.intelligence=40;state.hero.stats.wisdom=200
            verify(abs(BalanceSimulationHooks.rawProbability(state,20)-p)<1e-12)
            state.hero.stats.intelligence=120;state.hero.stats.wisdom=120
            verify(abs(BalanceSimulationHooks.rawProbability(state,20)-p)<1e-12)
        }
        println("CON / INT-WIS 50:50 boundary checks: $checks PASS")
    }
}
