package com.nullplaying.engine
import com.nullplaying.model.HeroClass
import kotlin.math.abs

object SpecializationBonusChecks {
    @JvmStatic fun main(args:Array<String>) {
        val floorMillis=(args.firstOrNull()?.toDouble()?.times(1000))?.toLong() ?: 4000L
        BalanceSimulationHooks.configure("con_mental,180")
        val engine=SimpleGameEngine(OfflineAdventureConfig(480,12))
        var n=0
        fun verify(ok:Boolean){check(ok);n++}
        for(cls in HeroClass.entries){
            val state=engine.newGame("LOCAL_CHECK",cls,engine.rollStats(9123L,cls).stats,8213L,0)
            val pmax=if(cls==HeroClass.MAGE) .35 else .30
            for(level in listOf(0L,1L,50L,99L,100L,120L,Long.MAX_VALUE)){
                state.hero.level=level
                var h0=8.0;var p0=.2;var c0=0.0
                for(v in listOf(0L,1L,18L,49L,50L,149L,150L,151L,179L,180L,181L,Long.MAX_VALUE)){
                    with(state.hero.stats){constitution=v;intelligence=v;wisdom=v;charisma=v;dexterity=v}
                    val h=BalanceSimulationHooks.offlineHours(state)
                    val p=BalanceSimulationHooks.rawProbability(state,20)
                    val c=BalanceSimulationHooks.saleBonus(state)
                    verify(h in 8.0..10.0 && h>=h0)
                    verify(p in .2..pmax && p>=p0)
                    verify(c in 0.0..0.15 && c>=c0)
                    verify(BalanceSimulationHooks.encounterMillis(state,5000) in floorMillis..5000)
                    verify(BalanceSimulationHooks.sale(state,Long.MAX_VALUE)==Long.MAX_VALUE)
                    verify(BalanceSimulationHooks.sale(state,0)==0L)
                    if(level<100)verify(h<10 && c<.15)
                    h0=h;p0=p;c0=c
                }
            }
            state.hero.level=100
            state.hero.stats.constitution=150;state.hero.stats.charisma=150
            state.hero.stats.dexterity=150
            verify(abs(BalanceSimulationHooks.offlineHours(state)-10)<1e-12)
            verify(abs(BalanceSimulationHooks.saleBonus(state)-.15)<1e-12)
            verify(BalanceSimulationHooks.encounterMillis(state,5000)==floorMillis)
            state.hero.stats.constitution=50
            verify(abs(BalanceSimulationHooks.offlineHours(state)-8.535161041173488)<1e-12)
        }
        println("Strong specialization / CHA15 / DEX${floorMillis}ms boundary checks: $n PASS")
    }
}
