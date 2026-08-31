package com.nullplaying.engine
object BalanceSimulationHooks {
    var attacks=0L; var casts=0L
    fun resetCounters(){ attacks=0L; casts=0L }
    fun configure(csv:String){ check(csv=="") }
}
