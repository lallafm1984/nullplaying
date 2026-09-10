package com.nullplaying.engine.arena

import com.nullplaying.model.HeroClass
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ArenaManaBudgetProbeTest {
    private val codec=Json { ignoreUnknownKeys=true }
    @Test fun `rank costs never decrease and translated previews match the frozen costs`() {
        for(d in ArenaIdentityCatalog.values) {
            val source=identityFixture(d.heroClass,"preview-cost").fighter
            val costs=(1..10).map { rank ->
                val s=d.resolve(rank,source)
                assertEquals(s.mp,ArenaIdentityCopy.preview(d.id,rank).mp)
                assertTrue(s.mp in 1..100)
                for(language in listOf("ko","en","ja")) {
                    assertTrue(ArenaIdentityCopy.timing(d.id,rank,language).contains("MP ${s.mp}"))
                    if(rank<10) {
                        val next=d.resolve(rank+1,source).mp
                        val expected=if(next==s.mp) "MP ${s.mp}" else "MP ${s.mp}→$next"
                        assertTrue(ArenaIdentityCopy.timing(d.id,rank,language,rank+1).contains(expected))
                    }
                }
                s.mp
            }
            assertTrue("${d.id}: $costs",costs.zipWithNext().all { (a,b)->b>=a })
        }
    }

    @Test fun `reported builds use vigilance early and every cast pays its displayed cost`() {
        val dir=File("src/simpleTest/resources/arena-mana-device")
        repeat(3) { index ->
            val pair=codec.decodeFromString<List<ArenaSupportInput>>(File(dir,"$index.json").readText())
                .map { it.copy(identity=null).withIdentityRules() }
            var used=0;var early=0;var wins=0
            val mana=mutableListOf<Double>()
            repeat(256) { seed ->
                val r=ArenaSupportTurnEngine.simulate(pair[0],pair[1],1291030L+seed)
                assertEquals(ArenaRunStatus.COMPLETED,r.status)
                val mine=r.events.filter { it.type==ArenaSupportEventType.CAST_START && it.actorId==pair[0].fighter.id }
                val guards=mine.filter { it.actionId=="ARENA_SUP_RANGER_06" }
                if(guards.isNotEmpty()) used++
                if(guards.any { it.turn<=4 }) early++
                if(r.winnerId==pair[0].fighter.id) wins++
                for(e in r.events.filter { it.type==ArenaSupportEventType.CAST_START }) {
                    val input=pair.single { it.fighter.id==e.actorId }
                    val cost=if(e.actionId=="BASIC_ATTACK") 0 else input.identity!!.skills.single { it.id==e.actionId }.mp
                    assertEquals(cost.toDouble(),e.amount,0.0)
                    assertEquals(cost*1000,e.mpBeforeUnits!!-e.mpAfterUnits!!)
                    assertTrue(e.mpAfterUnits!!>=0)
                }
                val f=r.fighters.getValue(pair[0].fighter.id)
                mana+=f.mpUnits.toDouble()/f.maxMpUnits
            }
            println("MANA_REBUILT,$index,battles=256,used=$used,early=$early,wins=$wins,median=${mana.sorted()[127]}")
            assertTrue("Reported build $index never uses its defensive skill in time",early>=128)
        }
    }
    @Test fun `measure actual device MP and preserve frozen battles`() {
        val dir=File("src/simpleTest/resources/arena-mana-device")
        repeat(3) { index ->
            val pair=codec.decodeFromString<List<ArenaSupportInput>>(File(dir,"$index.json").readText())
            val seed=File(dir,"$index.seed").readText().toLong()
            val r=ArenaSupportTurnEngine.simulate(pair[0],pair[1],seed)
            val digest=MessageDigest.getInstance("SHA-256").digest(r.toString().toByteArray()).joinToString("") { "%02x".format(it) }
            val golden=File(dir,"$index.sha256")
            assertTrue(golden.exists())
            assertEquals(golden.readText(),digest)
            val f=r.fighters.getValue(pair[0].fighter.id)
            val casts=r.events.filter { it.actorId==pair[0].fighter.id && it.type==ArenaSupportEventType.CAST_START }
            println("MANA_DEVICE,$index,hash=$digest,remaining=${f.mpUnits/1000.0},max=${f.maxMpUnits/1000.0},turns=${r.turns},vigilanceTurns=${casts.filter { it.actionId=="ARENA_SUP_RANGER_06" }.map { it.turn }},spent=${casts.sumOf { it.amount }}")
        }
    }

    @Test fun `measure MP by class level and legal build`() {
        val factory=ArenaIdentityBalanceTest()
        for(level in listOf(10,15,20,25,30,50,75,100)) {
            val cache=mutableMapOf<Triple<HeroClass,Int,ArenaAutoBuildPreset>,ArenaSupportInput>()
            fun input(c: HeroClass,seed: Int,p: ArenaAutoBuildPreset)=cache.getOrPut(Triple(c,seed,p)) { factory.profile(c,level,seed,p) }
            for(c in HeroClass.entries) {
                val remaining=mutableListOf<Double>();val winning=mutableListOf<Double>()
                val usage=mutableMapOf<String,Int>()
                var basics=0;var actions=0;var starvedEarly=0;var usedGuard=0;var hasGuard=0;var turns=0
                for(p in ArenaAutoBuildPreset.entries) for(d in HeroClass.entries.filter { it!=c }) repeat(4) { seed ->
                    val a=input(c,seed,p);val b=input(d,seed xor 1,ArenaAutoBuildPreset.entries[(p.ordinal+seed*3)%10])
                    val r=ArenaSupportTurnEngine.simulate(a,b,10920000L+level*10000+c.ordinal*1000+p.ordinal*10+seed)
                    assertEquals(ArenaRunStatus.COMPLETED,r.status)
                    val f=r.fighters.getValue(a.fighter.id);val fraction=f.mpUnits.toDouble()/f.maxMpUnits
                    remaining+=fraction;if(r.winnerId==a.fighter.id) winning+=fraction
                    val casts=r.events.filter { it.actorId==a.fighter.id && it.type==ArenaSupportEventType.CAST_START }
                    val cheapest=a.identity!!.skills.filter { !it.support }.minOfOrNull { it.mp } ?: 0
                    casts.forEach { usage[requireNotNull(it.actionId)]=(usage[it.actionId] ?: 0)+1 }
                    basics+=casts.count { it.actionId=="BASIC_ATTACK" };actions+=casts.size
                    if(casts.any { it.turn<=5 && it.actionId=="BASIC_ATTACK" && (it.mpBeforeUnits ?: 0)<cheapest*1000 }) starvedEarly++
                    if(a.supportIds.contains("ARENA_SUP_RANGER_06")) {hasGuard++;if(casts.any { it.actionId=="ARENA_SUP_RANGER_06" }) usedGuard++}
                    turns+=r.turns
                }
                if(level==100) println("MANA_USAGE,$c,"+usage.entries.sortedByDescending { it.value }.take(8).map { "${it.key}:${it.value}" })
                fun percentile(values: List<Double>,p: Double)=values.sorted()[(values.lastIndex*p).toInt()]
                println("MANA_MATRIX,$level,$c,n=${remaining.size},mean=${remaining.average()},p25=${percentile(remaining,.25)},median=${percentile(remaining,.5)},p75=${percentile(remaining,.75)},winnerMedian=${percentile(winning,.5)},basicShare=${basics.toDouble()/actions},earlyStarved=$starvedEarly,guard=$usedGuard/$hasGuard,turns=${turns.toDouble()/remaining.size}")
            }
        }
    }
}
