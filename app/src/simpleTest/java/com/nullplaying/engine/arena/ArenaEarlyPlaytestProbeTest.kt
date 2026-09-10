package com.nullplaying.engine.arena

import com.nullplaying.model.HeroClass
import org.junit.Assert.*
import org.junit.Test

/** Observe normal AI decisions with legal point paths; never force a skill to pad diversity. */
class ArenaEarlyPlaytestProbeTest {
    @Test fun `observe early class builds through normal automatic combat`() {
        val factory=ArenaIdentityBalanceTest()
        val cache=mutableMapOf<Triple<HeroClass,Int,ArenaAutoBuildPreset>,ArenaSupportInput>()
        for(level in listOf(10,15,20,25,30)) {
            cache.clear()
            fun profile(c: HeroClass,seed: Int,p: ArenaAutoBuildPreset)=cache.getOrPut(Triple(c,seed,p)) { factory.profile(c,level,seed,p) }
            for(c in HeroClass.entries) {
                var starts=0;var supports=0;var distinct=0;var a06=0;var a07=0;var owns06=0;var owns07=0
                repeat(16) { seed -> for(p in ArenaAutoBuildPreset.entries) {
                    val a=profile(c,seed,p)
                    val enemy=HeroClass.entries.filter { it!=c }[seed%5]
                    val b=profile(enemy,seed xor 1,ArenaAutoBuildPreset.entries[(p.ordinal+seed)%10])
                    val result=ArenaSupportTurnEngine.simulate(a,b,824400L+seed*100+p.ordinal)
                    assertEquals(ArenaRunStatus.COMPLETED,result.status)
                    val skills=a.identity!!.skills.associateBy { it.id }
                    if(skills.values.any { it.slot=="A06" }) owns06++
                    if(skills.values.any { it.slot=="A07" }) owns07++
                    val casts=result.events.filter { it.actorId==a.fighter.id && it.type==ArenaSupportEventType.CAST_START }
                    starts+=casts.size
                    distinct+=casts.mapNotNull { skills[it.actionId]?.id }.distinct().size
                    supports+=casts.count { skills[it.actionId]?.support==true }
                    a06+=casts.count { skills[it.actionId]?.slot=="A06" }
                    a07+=casts.count { skills[it.actionId]?.slot=="A07" }
                } }
                println("EARLY_PLAY,$level,$c,battles=160,meanSkills=${distinct/160.0},supportCasts=$supports,totalActions=$starts,ownsA06=$owns06,castsA06=$a06,ownsA07=$owns07,castsA07=$a07")
            }
        }
    }
}
