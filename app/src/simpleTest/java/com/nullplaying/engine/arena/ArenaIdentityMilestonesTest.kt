package com.nullplaying.engine.arena

import com.nullplaying.model.HeroClass
import org.junit.Assert.*
import org.junit.Test

class ArenaIdentityMilestonesTest {
    @Test fun `all 180 skills expose two localized operative milestones without zero turns`() {
        for(c in HeroClass.entries) for(rank in listOf(5,10)) {
            val f=identityFixture(c,"a",rank)
            for(s in f.identity!!.skills) {
                val bonus=ArenaIdentityMilestones.at(s,rank)
                for(lang in listOf("ko","en","ja")) {
                    val text=ArenaIdentityCopy.milestone(s.id,rank,lang)
                    assertTrue("$c ${s.slot} rank $rank",text.isNotBlank())
                    assertFalse(text.contains("8% 감소"))
                    assertFalse(text.contains('{'))
                    if(lang!="ko") assertFalse(text.any { it in '\uac00'..'\ud7a3' })
                }
                assertTrue(s.actionTurns>0 && (s.once || s.cooldown>0))
                assertNotEquals("mp_efficiency",s.capstone)
                assertTrue(bonus.key=="original_capstone" || bonus.amount!=0.0)
            }
        }
    }

    /** Remove just one milestone from a frozen skill; rank, stats, MP, damage curve and RNG stay equal. */
    private fun without(s: ArenaIdentitySkill,rank: Int): ArenaIdentitySkill {
        val b=ArenaIdentityMilestones.at(s,rank)
        return when(b.key) {
            "magnitude" -> s.copy(magnitude=s.magnitude-b.amount)
            "duration" -> s.copy(duration=s.duration-b.amount.toInt())
            "charges" -> s.copy(charges=s.charges-b.amount.toInt())
            "original_capstone" -> s.copy(capstone="")
            else -> s.copy(numbers=s.numbers+(b.key to (s.n(b.key)-b.amount)))
        }
    }

    private fun ArenaSupportInput.withScenarioMana(): ArenaSupportInput =
        copy(identity=identity!!.let { it.copy(stats=it.stats.copy(mp=500)) })

    @Test fun `every milestone changes the actual ledger when its combat situation occurs`() {
        val missing=mutableListOf<String>()
        for(c in HeroClass.entries) for(rank in listOf(5,10)) {
            // Isolate each effect from MP starvation in these 60-turn, all-skills fixtures.
            // Legal finite-MP builds and affordability are covered by the mana/balance probes.
            val original=identityFixture(c,"a",rank).withScenarioMana()
            for(s in original.identity!!.skills) {
                val before=original.copy(identity=original.identity.copy(skills=original.identity.skills.map {
                    if(it.id==s.id) without(it,rank) else it
                }))
                var changed=false
                for(case in 0..23) {
                    val scenario=case/3;val phase=case%3
                    val enemy=listOf(HeroClass.ROGUE,HeroClass.MAGE,HeroClass.CLERIC,HeroClass.PALADIN,HeroClass.MAGE,HeroClass.RANGER,HeroClass.MAGE,HeroClass.CLERIC)[scenario]
                    val sourceEnemy=identityFixture(enemy,"b",10).withScenarioMana()
                    val b=if(scenario>=6) sourceEnemy.copy(identity=sourceEnemy.identity!!.copy(stats=sourceEnemy.identity.stats.copy(hp=sourceEnemy.identity.stats.hp*3))) else sourceEnemy
                    fun enemyKind(vararg kinds: String)=kinds.firstNotNullOfOrNull { k -> b.identity!!.skills.firstOrNull { it.kind==k }?.id } ?: "BASIC_ATTACK"
                    fun ownKind(vararg kinds: String)=kinds.firstNotNullOfOrNull { k -> original.identity.skills.firstOrNull { it.kind==k }?.id } ?: "BASIC_ATTACK"
                    val negative=enemyKind("slow_accuracy","poison_tick","weaken_damage")
                    val protection=enemyKind("SHIELD","STEALTH","REGEN")
                    val heavy=b.identity!!.skills.filter { !it.support }.maxBy { it.damagePercent }.id
                    val ownFast=original.identity.skills.first { it.slot=="A01" }.id
                    val schedule=buildMap {
                        for(t in 1..60) {
                            val own=when {
                                s.n("recent_heal_damage")>0 && t%4==phase -> ownKind("HEAL","LAY_HANDS","BANDAGE")
                                (s.n("guarded_damage")>0 || s.kind=="guarded_bonus") && t%4==phase -> ownKind("SKILL_GUARD","IRON","SHIELD","LOW_SHIELD")
                                s.kind=="bleed_bonus" && t%4==phase -> ownKind("bleed_tick")
                                s.kind=="poison_bonus" && t%4==phase -> ownKind("poison_tick")
                                s.kind in setOf("burn_release","burn_bonus") && t%3==phase -> ownKind("burn_tick")
                                s.kind=="POISON_ACCELERATE" -> if(t%3!=0) ownKind("poison_tick") else s.id
                                s.kind=="status_bonus" && t%6==1 -> ownKind("slow_accuracy","weaken_damage","poison_tick")
                                s.kind=="SANCTUARY" -> if(t%3!=phase) ownFast else s.id
                                s.support && t%3!=phase -> if(scenario==4) ownKind("shield_bypass","damage_bonus") else ownFast
                                else -> s.id
                            }
                            put("a" to t,own)
                            put("b" to t,when(scenario) {
                                0 -> if(t%4==1) protection else negative
                                1 -> if(t%5==1) enemyKind("MIRROR") else if(t%3==2) negative else "BASIC_ATTACK"
                                2 -> if(t%3==1) enemyKind("HEAL","LAY_HANDS") else if(t%3==2) negative else "BASIC_ATTACK"
                                3 -> if(t%4==1) heavy else enemyKind("shield_extra","shield_bypass")
                                4 -> if(t%5==1) protection else heavy
                                5 -> if(t%3==1) enemyKind("TAUNT","TRAP","SLEEP") else negative
                                6 -> enemyKind("SHIELD")
                                else -> enemyKind("HEAL","LAY_HANDS")
                            })
                        }
                    }
                    for(seed in 0L..if(s.kind=="SANCTUARY") 255L else 31L) {
                        val afterResult=ArenaIdentityEngine.scripted(original,b,seed,schedule,ArenaTurnRules(safetyTurnLimit=60))
                        val beforeResult=ArenaIdentityEngine.scripted(before,b,seed,schedule,ArenaTurnRules(safetyTurnLimit=60))
                        fun ledger(r: ArenaSupportResult)=r.events.filter { e ->
                            e.type in setOf(ArenaSupportEventType.ATTACK_HIT,ArenaSupportEventType.DOT_DAMAGE,
                                ArenaSupportEventType.HEAL_APPLIED,ArenaSupportEventType.MP_DRAINED,
                                ArenaSupportEventType.SHIELD_ABSORBED)
                        }.map { listOf(it.turn,it.type,it.actorId,it.targetId,it.hpBefore,it.hpAfter,it.mpBeforeUnits,it.mpAfterUnits,it.shieldBefore,it.shieldAfter,it.amount) }
                        if(afterResult.fighters!=beforeResult.fighters || ledger(afterResult)!=ledger(beforeResult)) { changed=true;break }
                    }
                    if(changed) break
                }
                if(!changed) missing+="$c ${s.slot} rank$rank ${s.kind} ${ArenaIdentityMilestones.at(s,rank).key}"
            }
        }
        assertTrue("No observed milestone effect:\n"+missing.joinToString("\n"),missing.isEmpty())
    }
}
