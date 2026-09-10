package com.nullplaying.engine.arena

import com.nullplaying.model.HeroClass
import org.junit.Assert.assertEquals
import org.junit.Test

class ArenaLatePlaytestProbeTest {
    @Test fun `observe actual later skills by authored preset`() {
        val factory=ArenaIdentityBalanceTest()
        for(level in listOf(45,60,75,90,100)) for(c in HeroClass.entries) for(p in ArenaAutoBuildPreset.entries) {
            val counts=mutableMapOf<String,Int>()
            var wins=0
            repeat(16) { seed ->
                val a=factory.profile(c,level,seed,p)
                val enemy=HeroClass.entries.filter { it!=c }[seed%5]
                val b=factory.profile(enemy,level,seed xor 1,ArenaAutoBuildPreset.entries[(p.ordinal+seed)%10])
                val r=ArenaSupportTurnEngine.simulate(a,b,824400L+seed*100+p.ordinal)
                assertEquals(ArenaRunStatus.COMPLETED,r.status)
                if(r.winnerId==a.fighter.id) wins++
                val skills=a.identity!!.skills.associateBy { it.id }
                r.events.filter { it.actorId==a.fighter.id && it.type==ArenaSupportEventType.CAST_START }.forEach {
                    val slot=skills[it.actionId]?.slot ?: "BASIC"
                    counts[slot]=(counts[slot] ?: 0)+1
                }
            }
            println("LATE_PLAY,$level,$c,$p,wins=$wins/16,casts="+counts.entries.sortedByDescending { it.value }.joinToString(" ") { "${it.key}:${it.value}" })
        }
    }
}
