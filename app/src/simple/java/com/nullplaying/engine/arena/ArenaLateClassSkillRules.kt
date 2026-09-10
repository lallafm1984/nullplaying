package com.nullplaying.engine.arena

import com.nullplaying.model.HeroClass
import kotlin.math.roundToInt

/** Later skills deepen the same class choices introduced before level 30. */
internal object ArenaLateClassSkillRules {
    private fun ArenaIdentityDefinition.effect(kind: String, from: Double, to: Double=from,
        scale: Double=1.0, lasting: Int=duration, uses: Int?=null) = copy(
        kind=kind, magnitude=List(10) { from+(to-from)*it/9 }, duration=lasting,
        damage=damage.map { (it*scale).roundToInt() },
        charges=uses?.let { n -> List(10) { n } } ?: charges,
        options=options+("effect_kind" to kind),
        numbers=numbers+mapOf("effect_r1" to List(10) { from },"effect_r10" to List(10) { to },
            "effect_duration_turns" to List(10) { lasting.toDouble() }),
    )

    fun apply(d: ArenaIdentityDefinition): ArenaIdentityDefinition = when(d.heroClass) {
        HeroClass.WARRIOR -> when(d.slot) {
            "A10" -> d.copy(damage=d.damage.map { (it*.97).roundToInt() })
            "A12" -> d.copy(damage=d.damage.map { (it*.98).roundToInt() })
            "A08", "A09" -> d.copy(damage=d.damage.map { (it*.96).roundToInt() })
            "A13" -> d.effect("bleed_bonus",8.0,16.0,scale=.94)
            "A18" -> d.effect("self_cleanse",1.0)
            else -> d
        }
        HeroClass.ROGUE -> when(d.slot) {
            "A17" -> d.copy(damage=d.damage.map { (it*.98).roundToInt() })
            "A13" -> d.effect("poison_bonus",12.0,24.0,scale=1.11)
            "A15" -> d.effect("evasion_on_hit",4.0,6.0,lasting=2)
            else -> d
        }
        HeroClass.RANGER -> when(d.slot) {
            "A19" -> d.copy(damage=d.damage.map { (it*.97).roundToInt() })
            "A09" -> d.copy(damage=d.damage.map { (it*.97).roundToInt() })
            "A08" -> d.copy(damage=d.damage.map { (it*.94).roundToInt() })
            "A10" -> d.effect("evasion_reduction",4.0,8.0,scale=.97,lasting=2)
            "A12" -> d.copy(damage=d.damage.map { (it*.93).roundToInt() })
            "A13" -> d.effect("consecutive_bonus",12.0,24.0)
            "A15" -> d.effect("followup_accuracy",4.0,8.0,scale=1.08,lasting=5,uses=1)
            "A16" -> d.copy(damage=d.damage.mapIndexed { i,value -> (value*if(i<5) .98 else .96).roundToInt() })
            "A17" -> d
            "A18" -> d.effect("slow_accuracy",6.0,10.0,scale=1.04,lasting=2)
            else -> d
        }
        HeroClass.MAGE -> when(d.slot) {
            "A19" -> d.copy(damage=d.damage.map { (it*.97).roundToInt() })
            "A09" -> d.effect("burn_bonus",10.0,18.0)
            "A11" -> d.effect("slow_accuracy",4.0,8.0,scale=1.0,lasting=2)
            "A13" -> d.effect("burn_bonus",12.0,24.0)
            "A15" -> d.effect("mana_return",2.0,4.0)
            else -> d
        }
        HeroClass.CLERIC -> when(d.slot) {
            "A09" -> d.effect("shield_on_hit_hp",1.0,2.0,scale=.90,lasting=2)
            "A10" -> d.effect("self_cleanse",1.0,scale=.96)
            "A11" -> d.effect("burn_tick",3.0,5.0,scale=.96,lasting=2)
            "A13" -> d.effect("buffed_target_bonus",10.0,20.0,scale=1.04)
            "A18" -> d.effect("dispel",1.0)
            "A12" -> d.copy(damage=d.damage.map { (it*1.06).roundToInt() })
            "A15" -> d.copy(damage=d.damage.map { (it*.97).roundToInt() })
            "A20" -> d.effect("heal_on_hit_hp",2.0,3.0,scale=1.03)
            else -> d
        }
        HeroClass.PALADIN -> when(d.slot) {
            "A09" -> d.effect("guarded_bonus",10.0,18.0,scale=1.08)
            "A10" -> d.effect("weaken_damage",6.0,10.0,scale=.90,lasting=2)
            "A12" -> d.effect("shield_on_hit_hp",1.0,2.0,scale=.94,lasting=2)
            "A13" -> d.effect("guarded_bonus",10.0,20.0,scale=.94)
            "A17" -> d.effect("recent_support_bonus",10.0,20.0,scale=.98)
            "A18" -> d.effect("self_cleanse",1.0)
            "A19" -> d.copy(damage=d.damage.mapIndexed { i,value -> (value*if(i<5) .92 else .90).roundToInt() })
            else -> d
        }
    }
}
