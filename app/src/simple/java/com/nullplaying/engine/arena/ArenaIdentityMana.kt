package com.nullplaying.engine.arena

import com.nullplaying.model.HeroClass
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Arena-only, actor-independent costs: the preview and frozen combat input use the same curve. */
object ArenaIdentityMana {
    fun cost(definition: ArenaIdentityDefinition, rank: Int): Int {
        require(rank in 1..10)
        val row=requireNotNull(ArenaSkillTreeCatalog.find(definition.id)).row
        // Preserve each skill's initial cost, including support/long-cast premiums.
        val stageWeight=if(!definition.support && definition.turns==1) .4 else .9
        val rankWeight=if(!definition.support && definition.turns==1) .06 else .12
        val warrior=definition.heroClass==HeroClass.WARRIOR
        val longCastPremium=row*(definition.turns-2).coerceAtLeast(0)*.25
        val base=definition.mp.first()+ceil((row*stageWeight+longCastPremium)*(if(warrior) .40 else 1.0)).toInt()
        val growth=ceil((rank-1)*(1.0+(row*rankWeight+(definition.turns-1)*.10)*(if(warrior) .35 else 1.0))).toInt()
        val spellPremium=when(definition.heroClass) {
            HeroClass.MAGE -> 1.15+row*.01
            HeroClass.CLERIC -> 1.0+row*.015
            else -> 1.0
        }
        // Published v23 baseline: fixed per-skill relief before the new proportional reduction.
        val discount=floor(base*spellPremium*.10).toInt()
        val previousCost = ceil((base+growth)*spellPremium).toInt()-discount
        // Apply the same 40% relief to attacks and support, including stage/rank premiums.
        // Whole-MP rounding can keep adjacent ranks equal; learned skills never become free.
        return (previousCost * .60).roundToInt().coerceAtLeast(1).also { require(it in 1..100) }
    }
}
