package com.nullplaying.engine

import com.nullplaying.model.HeroClass
import kotlin.math.abs

/** Boundary audit for the already-built standalone candidate jar. */
object ProgressionBonusChecks {
    @JvmStatic fun main(args: Array<String>) {
        val engine=SimpleGameEngine(OfflineAdventureConfig(480,12))
        var checks=0
        fun verify(ok:Boolean) { check(ok); checks++ }
        for (mode in listOf("baseline","stat_power","level_ramp","level_envelope")) {
            BalanceSimulationHooks.configure("$mode,8000")
            for (cls in HeroClass.entries) {
                val state=engine.newGame("LOCAL_BOUNDARY",cls,engine.rollStats(12341L,cls).stats,9182L,0L)
                val maxProc=if(cls==HeroClass.MAGE) .35 else .30
                for (level in listOf(0L,1L,10L,60L,99L,100L,110L,Long.MAX_VALUE)) {
                    state.hero.level=level
                    var priorH=8.0;var priorP=.2;var priorS=5000L;var priorC=0.0
                    for (value in listOf(0L,1L,18L,149L,150L,151L,5999L,6000L,6001L,7999L,8000L,8001L,Long.MAX_VALUE)) {
                        with(state.hero.stats) { maxHealth=value;maxMana=value;dexterity=value;charisma=value }
                        val h=BalanceSimulationHooks.offlineHours(state)
                        val p=BalanceSimulationHooks.rawProbability(state,20)
                        val s=BalanceSimulationHooks.encounterMillis(state,5000L)
                        val c=BalanceSimulationHooks.saleBonus(state)
                        verify(h in 8.0..10.0 && h>=priorH)
                        verify(p>=.2-1e-12 && p<=maxProc+1e-12 && p>=priorP-1e-12)
                        verify(s in 4000L..5000L && s<=priorS)
                        verify(c in 0.0..0.2 && c>=priorC)
                        verify(BalanceSimulationHooks.sale(state,0)==0L)
                        verify(BalanceSimulationHooks.sale(state,1_000_000) in 1_000_000L..1_200_000L)
                        verify(BalanceSimulationHooks.sale(state,Long.MAX_VALUE)==Long.MAX_VALUE)
                        if(mode=="level_envelope" && level<100) verify(h<10 && s>4000 && c<.2)
                        priorH=h;priorP=p;priorS=s;priorC=c
                    }
                }
                state.hero.level=100
                state.hero.stats.maxHealth=6000
                state.hero.stats.maxMana=if(cls==HeroClass.MAGE && mode!="baseline") 8000 else 6000
                state.hero.stats.dexterity=150;state.hero.stats.charisma=150
                verify(abs(BalanceSimulationHooks.offlineHours(state)-10)<1e-12)
                verify(abs(BalanceSimulationHooks.rawProbability(state,20)-maxProc)<1e-12)
                verify(BalanceSimulationHooks.encounterMillis(state,5000)==4000L)
                verify(abs(BalanceSimulationHooks.saleBonus(state)-.2)<1e-12)
            }
        }
        println("Progression bonus boundary checks: $checks PASS")
    }
}
