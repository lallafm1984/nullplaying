package com.nullplaying.engine.arena

import com.nullplaying.model.HeroClass
import org.junit.Assert.*
import org.junit.Test

class ArenaIdentityEffectsTest {
    private fun slot(f: ArenaSupportInput,slot: String)=f.identity!!.skills.single { it.slot==slot }.id
    private fun kind(f: ArenaSupportInput,kind: String)=f.identity!!.skills.first { it.kind==kind }.id
    @Test fun `all sixty support skills can resolve when their conditions occur`() {
        val missing=mutableListOf<String>()
        for(c in HeroClass.entries) {
            val a=identityFixture(c,"a",10)
            for(s in a.identity!!.skills.filter { it.support }) {
                var resolved=false
                // Different opponents create shields, status, misses, healing and charging windows.
                for(enemy in listOf(HeroClass.MAGE,HeroClass.ROGUE,HeroClass.CLERIC)) {
                    val b=identityFixture(enemy,"b",10)
                    for(case in 0L..17L) {
                        val seed=case/3;val phase=(case%3).toInt()
                        val schedule=buildMap {
                            for(t in 1..45) {
                                put("a" to t,if(s.kind=="POISON_ACCELERATE") { if(t%3==0) s.id else slot(a,"A03") } else if(t%3==phase || t==1) s.id else slot(a,if(t%2==0) "A03" else "A01"))
                                put("b" to t,when {
                                    t%5==1 -> kind(b,when(enemy){HeroClass.MAGE->"SHIELD";HeroClass.ROGUE->"STEALTH";else->"REGEN"})
                                    t%5==2 || s.kind=="CLEANSE_ACCURACY" -> kind(b,if(enemy==HeroClass.CLERIC) "accuracy" else "slow_accuracy")
                                    else -> slot(b,"A03")
                                })
                            }
                        }
                        val r=ArenaIdentityEngine.scripted(a,b,seed,schedule,ArenaTurnRules(safetyTurnLimit=45))
                        resolved=resolved || r.events.any { it.actionId==s.id && it.type==ArenaSupportEventType.SUPPORT_APPLIED }
                        if(resolved) break
                    }
                    if(resolved) break
                }
                if(!resolved) missing+=s.id+":"+s.kind
            }
        }
        assertTrue("Unreachable supports: $missing",missing.isEmpty())
    }
    @Test fun `control immunity permits no chained delays and periodic damage cannot chain triggers`() {
        val a=identityFixture(HeroClass.ROGUE,"a",10)
        val b=identityFixture(HeroClass.RANGER,"b",10)
        val schedule=buildMap {
            for(t in 1..40) {put("a" to t,slot(a,"A03"));put("b" to t,kind(b,"TRAP"))}
        }
        for(seed in 0L..50L) {
            val r=ArenaIdentityEngine.scripted(a,b,seed,schedule,ArenaTurnRules(safetyTurnLimit=40))
            val controls=r.events.filter { it.type==ArenaSupportEventType.CONTROL_APPLIED }
            controls.groupBy { it.targetId }.values.forEach { es -> es.zipWithNext().forEach { (p,n) -> assertTrue(n.turn-p.turn>=4) } }
            val dots=r.events.filter { it.type==ArenaSupportEventType.DOT_DAMAGE }
            dots.groupBy { it.turn to it.targetId }.values.forEach { es ->
                assertTrue(es.sumOf { it.amount }<=r.fighters.getValue(es.first().targetId!!).maxHp*.08+1e-6)
                assertTrue(es.all { it.castId==null })
            }
        }
    }
    @Test fun `sanctuary shortens existing poison instead of extending its last turn`() {
        val original=identityFixture(HeroClass.ROGUE,"a",10)
        val poison=original.identity!!.skills.first { it.kind=="poison_tick" }
        val a=original.copy(identity=original.identity!!.copy(skills=original.identity!!.skills.map {
            if(it.id==poison.id) it.copy(actionTurns=1) else it
        }))
        val b=identityFixture(HeroClass.CLERIC,"b",10)
        val sanctuary=kind(b,"SANCTUARY")
        val schedule=buildMap {
            for(t in 1..4) {
                put("a" to t,if(t==1) poison.id else "BASIC_ATTACK")
                put("b" to t,if(t==2) sanctuary else "BASIC_ATTACK")
            }
        }
        var observed=0
        for(seed in 0L..19L) {
            val r=ArenaIdentityEngine.scripted(a,b,seed,schedule,ArenaTurnRules(safetyTurnLimit=4))
            if(r.events.none { it.type==ArenaSupportEventType.STATUS_APPLIED && it.reason=="poison" }) continue
            observed++
            assertTrue(r.events.any { it.type==ArenaSupportEventType.SUPPORT_APPLIED && it.actionId==sanctuary })
            assertEquals(listOf(2,3),r.events.filter { it.type==ArenaSupportEventType.DOT_DAMAGE && it.reason=="poison" }.map { it.turn })
        }
        assertTrue("Expected actual poison applications",observed>0)
    }
    @Test fun `new opponent paths are legal deterministic and preserve user allocations`() {
        for(c in HeroClass.entries) for(level in listOf(10,14,20,25,30,50,100)) {
            val source=identityFixture(c,"a",level=level.toLong()).copy(arenaLevel=level,identity=null)
            val signatures=mutableSetOf<List<Pair<String,Int>>>()
            for(p in ArenaAutoBuildPreset.entries) {
                val rebuilt=source.withIdentityOpponentRules(p)
                assertEquals(rebuilt,source.withIdentityOpponentRules(p))
                val owned=com.nullplaying.engine.SkillCatalog.forClass(c).filter { it.unlockLevel<=level }.map { it.catalogId }.toSet()
                val tree=ArenaIdentityBuilds.allocate(c,level,owned,p)
                assertTrue(ArenaSkillTreeRules.validate(tree,c,level,owned))
                assertEquals(level,ArenaSkillTreeRules.spentPoints(tree))
                assertTrue(rebuilt.fighter.attacks.all { it.id in owned })
                signatures+=tree.allocations.map { it.nodeId to it.rank }
            }
            assertTrue("$c L$level lacks build diversity: ${signatures.size}",signatures.size>=8)
            assertEquals(source.fighter.attacks,source.withIdentityRules().fighter.attacks)
        }
    }
}
