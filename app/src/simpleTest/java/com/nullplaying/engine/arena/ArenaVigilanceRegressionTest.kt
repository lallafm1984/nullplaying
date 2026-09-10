package com.nullplaying.engine.arena

import java.io.File
import java.security.MessageDigest
import com.nullplaying.model.HeroClass
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ArenaVigilanceRegressionTest {
    private val dir=File("src/simpleTest/resources/arena-vigilance-device")
    private val codec=Json { ignoreUnknownKeys=true }
    private val guard="ARENA_SUP_RANGER_06"

    @Test fun `measure the six reported device battles and frozen replays`() {
        repeat(6) { index ->
            val pair=codec.decodeFromString<List<ArenaSupportInput>>(File(dir,"$index.json").readText())
            val seed=File(dir,"$index.seed").readText().toLong()
            val result=ArenaSupportTurnEngine.simulate(pair[0],pair[1],seed)
            assertEquals(ArenaRunStatus.COMPLETED,result.status)
            val hash=MessageDigest.getInstance("SHA-256").digest(result.toString().toByteArray()).joinToString("") { "%02x".format(it) }
            val golden=File(dir,"$index.sha256")
            assertTrue(golden.exists())
            assertEquals(golden.readText(),hash)
            val actions=result.events.filter { it.type==ArenaSupportEventType.CAST_START }
            println("VIGILANCE_BASELINE,$index,$hash,guard=${actions.count { it.actorId==pair[0].fighter.id && it.actionId==guard }},enemyBasics=${actions.count { it.actorId==pair[1].fighter.id && it.actionId=="BASIC_ATTACK" }},turns=${result.turns}")
        }
    }

    @Test fun `normal AI uses invested vigilance without abandoning attacks`() {
        var allUsed=0
        repeat(6) { index ->
            val pair=codec.decodeFromString<List<ArenaSupportInput>>(File(dir,"$index.json").readText())
                .map { it.copy(identity=null).withIdentityRules() }
            val without=pair[0].copy(identity=pair[0].identity!!.copy(skills=pair[0].identity!!.skills.filter { it.id!=guard }),
                supportIds=pair[0].supportIds-guard,supportRanks=pair[0].supportRanks-guard,
                resolvedSupports=pair[0].resolvedSupports-guard)
            var used=0;var totalCasts=0;var wins=0;var winsWithout=0
            repeat(128) { seed ->
                val r=ArenaSupportTurnEngine.simulate(pair[0],pair[1],850010L+seed)
                assertEquals(ArenaRunStatus.COMPLETED,r.status)
                val casts=r.events.filter { it.actorId==pair[0].fighter.id && it.type==ArenaSupportEventType.CAST_START }
                val guards=casts.filter { it.actionId==guard }
                if(guards.isNotEmpty()) used++
                totalCasts+=guards.size
                if(r.winnerId==pair[0].fighter.id) wins++
                if(ArenaSupportTurnEngine.simulate(without,pair[1],850010L+seed,recordEvents=false).winnerId==without.fighter.id) winsWithout++
                assertTrue(casts.any { it.actionId!=guard })
                assertTrue(guards.zipWithNext().all { (a,b)->b.turn-a.turn>=7 })
            }
            println("VIGILANCE_AUTOMATIC,$index,battles=128,used=$used,casts=$totalCasts,wins=$wins,winsWithout=$winsWithout")
            allUsed+=used
        }
        assertTrue("Invested skill is still effectively unused: $allUsed/768",allUsed>=192)
        // These equal-power opponents can all warrant an opening defense. Against an
        // immediately defeatable opponent, attack takes priority; there is no forced cast.
        val pair=codec.decodeFromString<List<ArenaSupportInput>>(File(dir,"0.json").readText())
            .map { it.copy(identity=null).withIdentityRules() }
        val weak=pair[1].copy(identity=pair[1].identity!!.let { it.copy(stats=it.stats.copy(hp=1.0)) })
        val result=ArenaSupportTurnEngine.simulate(pair[0],weak,8815L)
        val first=result.events.first { it.type==ArenaSupportEventType.CAST_START && it.actorId==pair[0].fighter.id }
        assertNotEquals(guard,first.actionId)
    }

    @Test fun `vigilance evades direct hits instead of reducing their damage`() {
        for(rank in listOf(1,3,5,10)) for(enemySlot in listOf("BASIC_ATTACK","A01")) {
            val a=identityFixture(HeroClass.RANGER,"a",rank)
            val b=identityFixture(HeroClass.MAGE,"b",1)
            val skill=a.identity!!.skills.single { it.id==guard }
            val attack=if(enemySlot=="BASIC_ATTACK") enemySlot else b.identity!!.skills.single { it.slot==enemySlot }.id
            val without=a.copy(identity=a.identity.copy(skills=a.identity.skills.map { if(it.id==guard) it.copy(magnitude=0.0) else it }))
            var extraDodges=0
            for(seed in 0L..127L) {
                val plan=buildMap {
                    for(t in 1..8) { put("a" to t,if(t==1) guard else "BASIC_ATTACK");put("b" to t,attack) }
                }
                val r=ArenaIdentityEngine.scripted(a,b,seed,plan,ArenaTurnRules(safetyTurnLimit=8))
                val c=ArenaIdentityEngine.scripted(without,b,seed,plan,ArenaTurnRules(safetyTurnLimit=8))
                fun hits(v: ArenaSupportResult)=v.events.filter { it.actorId=="b" && it.type==ArenaSupportEventType.ATTACK_HIT }
                val old=hits(c).associateBy { it.turn }
                val dodges=r.events.filter { it.actorId=="a" && it.type==ArenaSupportEventType.ATTACK_EVADED && it.reason=="vigilance" }
                extraDodges+=dodges.size
                assertTrue(dodges.size<=skill.charges)
                assertTrue(dodges.all { it.turn<=1+skill.duration })
                for(e in hits(r)) assertEquals("Landed attacks must not be reduced",old.getValue(e.turn).amount,e.amount,0.0)
            }
            assertTrue("No extra dodges for $rank/$enemySlot",extraDodges>0)
        }
    }

    @Test fun `existing burn keeps ticking and own attacks preserve evasion checks`() {
        val a=identityFixture(HeroClass.RANGER,"a",3)
        val b=identityFixture(HeroClass.MAGE,"b",1)
        val burn=b.identity!!.skills.single { it.slot=="A01" }
        val without=a.copy(identity=a.identity!!.copy(skills=a.identity.skills.map { if(it.id==guard) it.copy(magnitude=0.0) else it }))
        var ticks=0;var lateDodges=0
        for(seed in 0L..127L) {
            val plan=buildMap {
                for(t in 1..4) {put("a" to t,if(t==2) guard else "BASIC_ATTACK");put("b" to t,if(t==1) burn.id else "BASIC_ATTACK")}
            }
            val r=ArenaIdentityEngine.scripted(a,b,seed,plan,ArenaTurnRules(safetyTurnLimit=4))
            val c=ArenaIdentityEngine.scripted(without,b,seed,plan,ArenaTurnRules(safetyTurnLimit=4))
            fun dots(v: ArenaSupportResult)=v.events.filter { it.type==ArenaSupportEventType.DOT_DAMAGE }.map { it.turn to it.amount }
            assertEquals(dots(c),dots(r));ticks+=dots(r).size
            val slow=b.copy(identity=b.identity!!.copy(skills=b.identity.skills.map { if(it.id==burn.id) it.copy(actionTurns=3) else it }))
            val slowPlan=buildMap {
                for(t in 1..6) {put("a" to t,if(t==1) guard else "BASIC_ATTACK");put("b" to t,burn.id)}
            }
            val long=ArenaIdentityEngine.scripted(a,slow,seed,slowPlan,ArenaTurnRules(safetyTurnLimit=6))
            lateDodges+=long.events.count { it.type==ArenaSupportEventType.ATTACK_EVADED && it.reason=="vigilance" && it.turn>=5 }
        }
        assertTrue(ticks>0);assertTrue("Own attacks consumed evasion checks",lateDodges>0)
    }

    @Test fun `vigilance copy and milestones describe evasion only`() {
        for(rank in listOf(1,3,5,10)) for(language in listOf("ko","en","ja")) {
            val text=ArenaIdentityCopy.effect(guard,rank,language)
            assertTrue(text.contains(when(language) { "ko"->"추가 회피";"en"->"Extra evasion";else->"追加回避" }))
            assertTrue(text.contains("35%"))
            assertFalse(text.contains(when(language) { "ko"->"피해 감소";"en"->"damage reduction";else->"ダメージ軽減" }))
        }
        val rank5=ArenaIdentityMilestones.at(identityFixture(HeroClass.RANGER,"a",5).identity!!.skills.single { it.id==guard },5)
        val rank10=ArenaIdentityMilestones.at(identityFixture(HeroClass.RANGER,"a",10).identity!!.skills.single { it.id==guard },10)
        assertEquals("magnitude",rank5.key);assertEquals(2.0,rank5.amount,0.0)
        assertEquals("charges",rank10.key);assertEquals(1.0,rank10.amount,0.0)
    }

}
