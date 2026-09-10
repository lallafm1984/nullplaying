package com.nullplaying.engine.arena

import com.nullplaying.model.HeroClass
import org.junit.Assert.*
import org.junit.Test

class ArenaIdentityPlayerBuildTest {
    @Test fun `high level factory accepts every sampled legal profile`() {
        val factory=ArenaIdentityBalanceTest()
        for(level in listOf(95,100)) for(preset in ArenaAutoBuildPreset.entries) repeat(256) { seed ->
            factory.profile(HeroClass.PALADIN,level,seed,preset)
        }
    }

    @Test fun `late authored finishers are reached after their point gates open`() {
        for(level in listOf(90,95,100)) {
            val f=ArenaIdentityBalanceTest().profile(HeroClass.PALADIN,level,0,ArenaAutoBuildPreset.WILD_TACTICS)
            val ranks=f.identity!!.skills.associate { it.slot to it.rank }
            assertTrue("L$level missing late parent",(ranks["S08"] ?: 0)>=3)
            assertTrue("L$level skipped authored finisher",(ranks["A19"] ?: 0)>=5)
            assertEquals(level,f.identity.skills.sumOf { it.rank })
        }
    }

    @Test fun `rank ten aimed shot cannot dominate the legal level fourteen roster`() {
        var wins=0;var count=0
        for(seed in 0..31) {
            val a=ArenaIdentityBalanceTest().profile(HeroClass.RANGER,14,seed,ArenaAutoBuildPreset.BALANCED,145L)
            val desired=mapOf("A01" to 10,"A02" to 3,"A03" to 1)
            val f=a.fighter.copy(attacks=a.fighter.attacks.mapNotNull { attack ->
                val slot=ArenaIdentityCatalog.find(attack.id)!!.slot
                desired[slot]?.let { ArenaTurnInputAdapter.resolveAttack(attack,HeroClass.RANGER,it) }
            })
            val player=a.copy(fighter=f,identity=null,supportIds=emptySet(),supportRanks=emptyMap(),resolvedSupports=emptyMap()).withIdentityRules()
            for(c in HeroClass.entries) for(p in ArenaAutoBuildPreset.entries) {
                val b=ArenaIdentityBalanceTest().profile(c,14,seed xor 1,p,145L).let { it.copy(fighter=it.fighter.copy(id="opponent")) }
                val r=ArenaSupportTurnEngine.simulate(player,b,seed*3011L+p.ordinal,recordEvents=false)
                assertEquals(ArenaRunStatus.COMPLETED,r.status)
                if(r.winnerId==player.fighter.id) wins++
                count++
            }
        }
        val rate=wins.toDouble()/count
        println("IDENTITY_AIMED_SHOT,$count,$rate")
        assertTrue("Maxed aimed shot roster win rate: $rate",rate in .25.. .65)
    }
    @Test fun `one and two hero levels remain contestable with real legal skills`() {
        for(level in listOf(14,20,30)) for(delta in 1..2) {
            var score=0.0;var count=0
            for(c in HeroClass.entries) for(d in HeroClass.entries) repeat(8) { seed ->
                val p=ArenaAutoBuildPreset.entries[seed%ArenaAutoBuildPreset.entries.size]
                val a=ArenaIdentityBalanceTest().profile(c,level,64+seed,p).let { it.copy(fighter=it.fighter.copy(id="low")) }
                val b=ArenaIdentityBalanceTest().profile(d,level+delta,64+(seed xor 1),p).let { it.copy(fighter=it.fighter.copy(id="high")) }
                val r=ArenaSupportTurnEngine.simulate(a,b,129000L+seed+level*19+delta*41,recordEvents=false)
                assertEquals(ArenaRunStatus.COMPLETED,r.status)
                score+=if(r.winnerId==a.fighter.id) 1.0 else if(r.winnerId==null) .5 else 0.0
                count++
            }
            val rate=score/count;println("IDENTITY_ADJACENT,$level,$delta,$count,$rate")
            assertTrue("L$level vs +$delta: $rate",rate in .25.. .55)
        }
    }
}
