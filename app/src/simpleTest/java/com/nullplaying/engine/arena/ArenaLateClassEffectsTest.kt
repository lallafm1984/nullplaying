package com.nullplaying.engine.arena

import com.nullplaying.model.HeroClass
import org.junit.Assert.*
import org.junit.Test

class ArenaLateClassEffectsTest {
    private fun skill(f: ArenaSupportInput, slot: String)=f.identity!!.skills.single { it.slot==slot }
    private fun withoutMagnitude(f: ArenaSupportInput,id: String)=f.copy(identity=f.identity!!.copy(
        skills=f.identity.skills.map { if(it.id==id) it.copy(magnitude=0.0) else it }))

    @Test fun `later conditional attacks reward their own class setup in actual damage`() {
        for((c,setup) in listOf(HeroClass.WARRIOR to "A06",HeroClass.ROGUE to "A03",
            HeroClass.MAGE to "A01",HeroClass.RANGER to "A01",HeroClass.PALADIN to "S01")) {
            val a=identityFixture(c,"a",1)
            val b=identityFixture(HeroClass.WARRIOR,"b",1)
            val opening=skill(a,setup);val followup=skill(a,"A13")
            val end=opening.actionTurns+followup.actionTurns
            val plan=buildMap {
                for(t in 1..end) {
                    put("a" to t,when(t) {1->opening.id;opening.actionTurns+1->followup.id;else->"BASIC_ATTACK"})
                    put("b" to t,"BASIC_ATTACK")
                }
            }
            var gains=0
            for(seed in 0L..127L) {
                val with=ArenaIdentityEngine.scripted(a,b,seed,plan,ArenaTurnRules(safetyTurnLimit=end))
                val without=ArenaIdentityEngine.scripted(withoutMagnitude(a,followup.id),b,seed,plan,ArenaTurnRules(safetyTurnLimit=end))
                fun damage(r: ArenaSupportResult)=r.events.filter {
                    it.type==ArenaSupportEventType.ATTACK_HIT && it.actionId==followup.id
                }.sumOf { it.amount }
                if(damage(with)>damage(without)+1e-6) gains++
            }
            assertTrue("$c setup did not improve ${followup.kind}",gains>0)
        }
    }

    @Test fun `mage late mana return really restores spent mana without healing`() {
        val a=identityFixture(HeroClass.MAGE,"a",1)
        val b=identityFixture(HeroClass.WARRIOR,"b",1)
        val s=skill(a,"A15")
        var observed=0
        for(seed in 0L..63L) {
            val plan=mapOf(("a" to 1) to s.id,("b" to 1) to "BASIC_ATTACK")
            val with=ArenaIdentityEngine.scripted(a,b,seed,plan,ArenaTurnRules(safetyTurnLimit=1))
            val without=ArenaIdentityEngine.scripted(withoutMagnitude(a,s.id),b,seed,plan,ArenaTurnRules(safetyTurnLimit=1))
            if(with.events.any { it.type==ArenaSupportEventType.ATTACK_HIT && it.actionId==s.id }) {
                observed++
                assertEquals(2000,with.fighters.getValue("a").mpUnits-without.fighters.getValue("a").mpUnits)
                assertEquals(without.fighters.getValue("a").hp,with.fighters.getValue("a").hp,1e-9)
            }
        }
        assertTrue(observed>0)
    }

    @Test fun `rogue late evasion prevents later hits rather than only adding status text`() {
        val a=identityFixture(HeroClass.ROGUE,"a",1)
        val b=identityFixture(HeroClass.WARRIOR,"b",1)
        val s=skill(a,"A15")
        var withHits=0;var withoutHits=0
        val plan=buildMap { for(t in 1..3) {
            put("a" to t,if(t==1) s.id else "BASIC_ATTACK");put("b" to t,"BASIC_ATTACK")
        } }
        for(seed in 0L..511L) {
            val with=ArenaIdentityEngine.scripted(a,b,seed,plan,ArenaTurnRules(safetyTurnLimit=3))
            val without=ArenaIdentityEngine.scripted(withoutMagnitude(a,s.id),b,seed,plan,ArenaTurnRules(safetyTurnLimit=3))
            fun hits(r: ArenaSupportResult)=r.events.count { it.actorId=="b" && it.turn>1 && it.type==ArenaSupportEventType.ATTACK_HIT }
            withHits+=hits(with);withoutHits+=hits(without)
        }
        assertTrue("evasion hit count $withoutHits -> $withHits",withHits<withoutHits)
    }
}
