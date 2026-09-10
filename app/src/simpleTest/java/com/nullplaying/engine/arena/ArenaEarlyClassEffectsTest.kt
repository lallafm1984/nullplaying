package com.nullplaying.engine.arena

import com.nullplaying.model.HeroClass
import org.junit.Assert.*
import org.junit.Test

class ArenaEarlyClassEffectsTest {
    private fun skill(f: ArenaSupportInput, slot: String)=f.identity!!.skills.single { it.slot==slot }
    private fun schedule(a: ArenaSupportInput, first: String, second: String, secondTurn: Int, turns: Int)=buildMap {
        for(t in 1..turns) {
            put("a" to t,when(t) { 1->skill(a,first).id;secondTurn->skill(a,second).id;else->"BASIC_ATTACK" })
            put("b" to t,"BASIC_ATTACK")
        }
    }
    @Test fun `level 25 and 30 attacks have six different class roles`() {
        for(slot in listOf("A06","A07")) {
            val skills=HeroClass.entries.map { skill(identityFixture(it,"a"),slot) }
            assertEquals(6,skills.map { it.kind }.toSet().size)
            assertTrue(skills.all { it.actionTurns>=1 && it.cooldown>=1 })
        }
    }
    @Test fun `poison extension adds one future tick without infinite renewal`() {
        val a=identityFixture(HeroClass.ROGUE,"a",10)
        val b=identityFixture(HeroClass.WARRIOR,"b",1)
        val poison=skill(a,"A03");val strike=skill(a,"A06")
        val without=a.copy(identity=a.identity!!.copy(skills=a.identity.skills.map {
            if(it.id==strike.id) it.copy(numbers=it.numbers+("poison_extend" to 0.0)) else it
        }))
        var observed=0
        for(seed in 0L..127L) {
            val plan=schedule(a,"A03","A06",3,7)
            val r=ArenaIdentityEngine.scripted(a,b,seed,plan,ArenaTurnRules(safetyTurnLimit=7))
            if(r.events.none { it.actionId==strike.id && it.type==ArenaSupportEventType.STATUS_APPLIED && it.reason=="poison" }) continue
            observed++
            val control=ArenaIdentityEngine.scripted(without,b,seed,plan,ArenaTurnRules(safetyTurnLimit=7))
            fun ticks(v: ArenaSupportResult)=v.events.filter { it.actionId==poison.id && it.type==ArenaSupportEventType.DOT_DAMAGE }.map { it.turn }
            assertEquals(listOf(3,4,5,6),ticks(r))
            assertEquals(listOf(3,4,5),ticks(control))
        }
        assertTrue(observed>0)
    }
    @Test fun `burn detonation consumes only future ticks on hit and shares periodic cap`() {
        val a=identityFixture(HeroClass.MAGE,"a",1)
        val b=identityFixture(HeroClass.WARRIOR,"b",1)
        val burn=skill(a,"A01");val release=skill(a,"A07")
        var hit=0;var miss=0
        for(seed in 0L..127L) {
            val r=ArenaIdentityEngine.scripted(a,b,seed,schedule(a,"A01","A07",2,4),ArenaTurnRules(safetyTurnLimit=4))
            if(r.events.none { it.actionId==burn.id && it.type==ArenaSupportEventType.STATUS_APPLIED }) continue
            val exploded=r.events.any { it.actionId==release.id && it.type==ArenaSupportEventType.DOT_DAMAGE }
            if(exploded) {
                hit++
                assertFalse(r.events.any { it.actionId==burn.id && it.type==ArenaSupportEventType.DOT_DAMAGE && it.turn>=3 })
            } else if(r.events.any { it.actionId==release.id && it.type in setOf(ArenaSupportEventType.ATTACK_MISS,ArenaSupportEventType.ATTACK_EVADED) }) {
                miss++
                assertTrue(r.events.any { it.actionId==burn.id && it.type==ArenaSupportEventType.DOT_DAMAGE && it.turn==3 })
            }
            r.events.filter { it.type==ArenaSupportEventType.DOT_DAMAGE }.groupBy { it.turn to it.targetId }.forEach { (key, events) ->
                assertTrue(events.sumOf { it.amount }<=r.fighters.getValue(key.second!!).maxHp*.08+1e-6)
            }
        }
        assertTrue(hit>0 && miss>0)
    }
    @Test fun `ranger evasion reduction improves the next hit in matching random trials`() {
        val a=identityFixture(HeroClass.RANGER,"a",1)
        val b=identityFixture(HeroClass.ROGUE,"b",1)
        val setup=skill(a,"A06");val followup=skill(a,"A01")
        val without=a.copy(identity=a.identity!!.copy(skills=a.identity.skills.map { if(it.id==setup.id) it.copy(magnitude=0.0) else it }))
        var after=0;var before=0
        for(seed in 0L..511L) {
            val plan=schedule(a,"A06","A01",3,3)
            val r=ArenaIdentityEngine.scripted(a,b,seed,plan,ArenaTurnRules(safetyTurnLimit=3))
            val control=ArenaIdentityEngine.scripted(without,b,seed,plan,ArenaTurnRules(safetyTurnLimit=3))
            fun landed(v: ArenaSupportResult)=v.events.any { it.actionId==followup.id && it.turn==3 && it.type==ArenaSupportEventType.ATTACK_HIT }
            if(landed(r)) after++
            if(landed(control)) before++
        }
        assertTrue("evasion reduction $before -> $after",after>before)
    }
}
