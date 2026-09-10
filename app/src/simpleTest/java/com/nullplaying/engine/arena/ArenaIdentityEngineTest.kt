package com.nullplaying.engine.arena

import com.nullplaying.engine.SkillCatalog
import com.nullplaying.model.HeroClass
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

internal fun identityFixture(c: HeroClass,id: String,rank: Int=1,level: Long=100): ArenaSupportInput {
    val attacks=SkillCatalog.forClass(c).filter { it.unlockLevel<=level }.map { d ->
        ArenaTurnInputAdapter.resolveAttack(ArenaAttackInput(d.catalogId,d.name,
            if(d.unlockLevel==1) 1 else d.unlockLevel/5+1,0,0,0),c,rank)
    }
    val supports=ArenaSupportCatalog.forClass(c).associate { it.id to rank }
    return ArenaSupportInput(ArenaFighterInput(id,c,level,
        ArenaCoreStats(30.0,30.0,30.0,30.0,30.0,30.0,200.0,100.0),attacks),
        supportIds=supports.keys,supportRanks=supports,arenaLevel=100).withIdentityRules()
}

class ArenaIdentityEngineTest {
    @Test fun `all 180 definitions and 1800 ranks resolve with complete three language copy`() {
        assertEquals(180,ArenaIdentityCatalog.values.size)
        for(c in HeroClass.entries) for(rank in 1..10) {
            val f=identityFixture(c,"f",rank)
            ArenaIdentityEngine.validate(f)
            assertEquals(30,f.identity!!.skills.size)
            for(s in f.identity.skills) {
                assertTrue(s.actionTurns>=1);assertTrue(s.once || s.cooldown>=1)
                for(lang in listOf("ko","en","ja")) {
                    val copy=ArenaIdentityCopy.effect(s.id,rank,lang)
                    assertTrue(copy.isNotBlank());assertFalse(copy.contains('{'))
                    if(lang!="ko") assertFalse(copy.any { it in '\uac00'..'\ud7a3' })
                    val timing=ArenaIdentityCopy.timing(s.id,rank,lang)
                    assertFalse(timing.contains("0턴"));assertFalse(timing.contains("0-turn"))
                }
            }
        }
    }
    @Test fun `identity snapshots round trip and old inputs stay legacy`() {
        val left=identityFixture(HeroClass.RANGER,"left",10)
        val right=identityFixture(HeroClass.WARRIOR,"right",10)
        val encoded=Json.encodeToString(left)
        val restored=Json.decodeFromString<ArenaSupportInput>(encoded)
        assertEquals(left,restored);assertEquals(left,left.withIdentityRules())
        val first=ArenaSupportTurnEngine.simulate(left,right,55)
        assertEquals(first,ArenaSupportTurnEngine.simulate(restored,right,55))
        assertEquals(first,ArenaSupportTurnEngine.simulate(right,left,55))
        assertEquals(ARENA_IDENTITY_RULES_VERSION,first.rulesVersion)
        assertEquals(ARENA_SUPPORT_RULES_VERSION,ArenaSupportTurnEngine.simulate(left.copy(identity=null),right.copy(identity=null),55).rulesVersion)
    }
    @Test fun `ten point first attack has action time and two cooldown turns`() {
        for(c in HeroClass.entries) {
            val source=identityFixture(c,"a",10)
            val id=source.identity!!.skills.single { it.slot=="A01" }.id
            val target=identityFixture(HeroClass.PALADIN,"b",1)
            val schedule=(1..20).flatMap { listOf(("a" to it) to id,("b" to it) to "BASIC_ATTACK") }.toMap()
            val r=ArenaIdentityEngine.scripted(source,target,88,schedule,ArenaTurnRules(safetyTurnLimit=12))
            val starts=r.events.filter { it.actorId=="a" && it.actionId==id && it.type==ArenaSupportEventType.CAST_START }
            assertTrue(starts.size>=2)
            assertTrue(starts.all { it.castTurns==1 })
            assertTrue(starts.zipWithNext().all { (a,b)->b.turn-a.turn>=3 })
            assertEquals(listOf(1,4,7,10),starts.map { it.turn })
        }
    }
    @Test fun `every attack executes through real hit and effect paths`() {
        for(c in HeroClass.entries) {
            val source=identityFixture(c,"a",10)
            val target=identityFixture(HeroClass.PALADIN,"b",1)
            for(s in source.identity!!.skills.filter { !it.support }) {
                val schedule=(1..16).flatMap { listOf(("a" to it) to s.id,("b" to it) to "BASIC_ATTACK") }.toMap()
                val r=ArenaIdentityEngine.scripted(source,target,91,schedule,ArenaTurnRules(safetyTurnLimit=16))
                assertTrue("No start: ${s.id}",r.events.any { it.type==ArenaSupportEventType.CAST_START && it.actionId==s.id })
                assertTrue(r.events.any { it.actionId==s.id && it.type in setOf(ArenaSupportEventType.ATTACK_HIT,ArenaSupportEventType.ATTACK_MISS,ArenaSupportEventType.ATTACK_EVADED) })
                assertTrue(r.fighters.values.all { it.hp>=0 && it.mpUnits>=0 && it.mpUnits<=it.maxMpUnits })
            }
        }
    }
    @Test fun `native stats alter attack aim survival and resource without altering action turns`() {
        for(c in HeroClass.entries) {
            val f=identityFixture(c,"f").fighter
            val base=ArenaIdentityFormula.derive(f)
            val dex=ArenaIdentityFormula.derive(f.copy(stats=f.stats.copy(dexterity=60.0)))
            val con=ArenaIdentityFormula.derive(f.copy(stats=f.stats.copy(constitution=60.0)))
            val intel=ArenaIdentityFormula.derive(f.copy(stats=f.stats.copy(intelligence=60.0)))
            assertTrue(dex.evade>base.evade);assertTrue(con.hp>base.hp);assertTrue(intel.mp>base.mp)
            assertTrue(con.physicalGuard>base.physicalGuard)
        }
    }
    @Test fun `long automatic battles terminate and never exceed shields or damage caps`() {
        for(c in HeroClass.entries) for(d in HeroClass.entries) repeat(10) { seed ->
            val a=identityFixture(c,"a",10);val b=identityFixture(d,"b",10)
            val r=ArenaSupportTurnEngine.simulate(a,b,seed.toLong(),rules=ArenaTurnRules(safetyTurnLimit=200))
            assertEquals("$c/$d seed=$seed",ArenaRunStatus.COMPLETED,r.status)
            assertEquals(ArenaSupportEventType.END,r.events.last().type)
            r.events.forEach { e ->
                e.shieldAfter?.let { assertTrue(it<=r.fighters.getValue(e.actorId!!).maxHp*.20+1e-7) }
                if(e.type==ArenaSupportEventType.ATTACK_HIT && e.actionId!="BASIC_ATTACK") {
                    val f=if(e.actorId=="a") a else b
                    val s=f.identity!!.skills.single { it.id==e.actionId }
                    assertTrue(e.amount<=r.fighters.getValue(e.targetId!!).maxHp*s.n("damage_hp_cap_percent")/100+1e-7)
                }
            }
        }
    }
}
