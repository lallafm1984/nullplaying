package com.nullplaying.engine

import com.nullplaying.model.HeroClass
import com.nullplaying.model.SimpleGameState
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Planning-only: CON specialization, DEX floor3.5s, CHA15, mental50:50. */
object BalanceSimulationHooks {
    var enabled=false
    var attacks=0L
    var casts=0L
    fun resetCounters(){attacks=0L;casts=0L}
    fun configure(csv:String){check(csv=="con_mental,180");enabled=true}
    private fun progress(state:SimpleGameState)=
        ((state.hero.level.coerceAtLeast(1)-1).toDouble()/99).coerceIn(0.0,1.0)
    private fun fraction(value:Long)=(value.toDouble()/150).coerceIn(0.0,1.0)
    fun offlineHours(state:SimpleGameState):Double=
        8+2*fraction(state.hero.stats.constitution).pow(1.2)*(.15+.85*progress(state).pow(1.2))
    fun rawProbability(state:SimpleGameState,basePercent:Int):Double {
        val mental=state.hero.stats.intelligence.toDouble()*.5+state.hero.stats.wisdom.toDouble()*.5
        val ratio=(mental/180).coerceIn(0.0,1.0).pow(1.2)
        val cap=if(state.hero.heroClass==HeroClass.MAGE) .35 else .30
        return (basePercent/100.0+.15*ratio).coerceIn(0.0,cap)
    }
    fun proc(state:SimpleGameState,roll:Int,basePercent:Int)=
        roll<(rawProbability(state,basePercent)*10000).roundToInt()
    private fun utilityRatio(state:SimpleGameState,value:Long)=
        minOf(fraction(value),.12+.88*progress(state).pow(1.4))
    fun encounterMillis(state:SimpleGameState,baseMillis:Long):Long=
        baseMillis-(1500*utilityRatio(state,state.hero.stats.dexterity)).roundToLong()
    fun saleBonus(state:SimpleGameState)=.15*utilityRatio(state,state.hero.stats.charisma)
    fun sale(state:SimpleGameState,value:Long)=(value*(1+saleBonus(state))).toLong()
}
