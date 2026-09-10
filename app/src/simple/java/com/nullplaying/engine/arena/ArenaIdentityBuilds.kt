package com.nullplaying.engine.arena

import com.nullplaying.engine.SkillCatalog
import com.nullplaying.model.HeroClass
import kotlin.math.max

/** Ten stable role priorities. Only newly issued NPC builds use this; player allocations are untouched. */
internal object ArenaIdentityBuilds {
    private val themes=listOf(
        emptySet(),
        setOf("accuracy","followup_damage","RAPID","followup_accuracy","opening_bonus"),
        setOf("damage_bonus","execute_bonus","CONDENSE","SHOUT"),
        setOf("delay","mp_drain","TAUNT","TRAP","SLEEP","MP_DRAIN"),
        setOf("weaken_damage","IRON","SKILL_GUARD","EVASION","SHIELD"),
        setOf("heal_on_hit_hp","shield_on_hit_hp","HEAL","REGEN","LOW_SHIELD"),
        setOf("shield_extra","mitigation_ignore","shield_bypass","PIERCE","evasion_reduction"),
        setOf("bleed_tick","burn_tick","poison_tick","status_bonus","POISON_COAT","poison_bonus","burn_release","bleed_bonus","burn_bonus"),
        setOf("after_miss_bonus","preparing_bonus","COUNTER","PURSUIT","OPPORTUNITY","guarded_bonus","recent_support_bonus"),
        setOf("followup_damage","status_bonus","recent_support_bonus","recent_fast_bonus","buffed_target_bonus","consecutive_bonus"),
    )
    // Different opening signatures, with enough breadth for cooldowns and later parent gates.
    private val roots=listOf(listOf(5,2,2),listOf(6,3,1),listOf(3,3,4),listOf(3,3,3),listOf(4,3,2),
        listOf(3,3,3),listOf(4,1,4),listOf(3,2,4),listOf(5,3,1),listOf(2,3,4))
    private val openingSupports=listOf(1,0,0,1,1,1,1,1,1,1)
    private val level25Paths=setOf(ArenaAutoBuildPreset.BALANCED,ArenaAutoBuildPreset.DEFENSIVE_WARD,
        ArenaAutoBuildPreset.SUSTAIN_RECOVERY,ArenaAutoBuildPreset.DEFENSE_BREAKER,ArenaAutoBuildPreset.WILD_TACTICS)
    private val roots14=listOf(listOf(5,5,4),listOf(7,4,3),listOf(4,4,6),listOf(4,7,3),listOf(6,3,5),
        listOf(4,4,6),listOf(5,3,6),listOf(3,6,5),listOf(7,3,4),listOf(3,7,4))
    private data class Key(val heroClass: HeroClass,val budget: Int,val owned: Set<String>,val preset: ArenaAutoBuildPreset)
    private val cache=object : LinkedHashMap<Key,ArenaSkillTreeState>(64,.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key,ArenaSkillTreeState>?): Boolean = size>256
    }
    fun allocate(c: HeroClass,budget: Int,owned: Set<String>,preset: ArenaAutoBuildPreset): ArenaSkillTreeState {
        val key=Key(c,budget,owned.toSet(),preset)
        val result=synchronized(cache) { cache[key] } ?: allocateFresh(c,budget,key.owned,preset).also {
            synchronized(cache) { cache[key]=it }
        }
        // Callers receive their own list; cached authored ranks contain no player stats or identity.
        return result.copy(allocations=result.allocations.toList())
    }
    private fun allocateFresh(c: HeroClass,budget: Int,owned: Set<String>,preset: ArenaAutoBuildPreset): ArenaSkillTreeState {
        var state=ArenaSkillTreeRules.initialize(null,c)
        val nodes=ArenaSkillTreeCatalog.forClass(c)
        fun put(id: String) {
            val rank=state.allocations.firstOrNull { it.nodeId==id }?.rank ?: 0
            val update=ArenaSkillTreeRules.allocate(state,c,budget,owned,id,rank+1,true)
            check(update.accepted);state=update.state
        }
        // Round-robin start, then the authored ten-point signature.
        for(rank in 1..6) for(i in 0..2) {
            if(ArenaSkillTreeRules.spentPoints(state)>=budget) return state
            val node=nodes.single { it.slotKey=="A0${i+1}" }
            if(rank<=roots[preset.ordinal][i] && node.id in owned) put(node.id)
        }
        val openingSupport=nodes.single { it.slotKey=="S01" }
        repeat(openingSupports[preset.ordinal]) {
            if(ArenaSkillTreeRules.spentPoints(state)>=budget) return state
            put(openingSupport.id)
        }
        growOpening@ for(i in 0..2) {
            val node=nodes.single { it.slotKey=="A0${i+1}" }
            if(node.id !in owned) continue
            while((state.allocations.firstOrNull { it.nodeId==node.id }?.rank ?: 0)<roots14[preset.ordinal][i]) {
                if(ArenaSkillTreeRules.spentPoints(state)>=minOf(budget,14)) break@growOpening
                put(node.id)
            }
        }
        val pendingRanks=linkedMapOf<String,Int>()
        // A new level milestone opens a real alternative; only half the presets specialize in
        // each route, preserving focused and defensive choices without changing player points.
        fun toward(slot: String, rank: Int) {
            val node=nodes.single { it.slotKey==slot }
            pendingRanks[node.id]=maxOf(pendingRanks[node.id] ?: 0,rank)
            while((state.allocations.firstOrNull { it.nodeId==node.id }?.rank ?: 0)<rank &&
                ArenaSkillTreeRules.spentPoints(state)<budget) {
                if(!ArenaSkillTreeRules.view(state,budget,owned).nodes.single { it.definition.id==node.id }.canAllocate) break
                put(node.id)
            }
        }
        if(budget>=15) { toward("A01",3);toward("A04",1) }
        if(budget>=20) { toward("A03",3);toward("A05",1) }
        if(budget>=25 && preset in level25Paths) { toward("S01",3);toward("A06",5) }
        if(budget>=30 && preset !in level25Paths) { toward("A05",3);toward("A07",5) }
        // The counter route needs an owned heavy follow-up after its early interrupt.
        if(budget>=35 && c==HeroClass.WARRIOR && preset==ArenaAutoBuildPreset.REACTIVE_COUNTER) {
            toward("A04",3);toward("S02",3);toward("A08",3)
        }
        // Fast openers need a later finisher/guard follow-through instead of only root attacks.
        if(budget>=55 && preset==ArenaAutoBuildPreset.SWIFT_ASSAULT && c in setOf(HeroClass.ROGUE,HeroClass.PALADIN)) {
            toward("A09",3);toward("S04",3);toward("A12",5)
        }
        if(budget>=85 && c==HeroClass.PALADIN && preset==ArenaAutoBuildPreset.STATUS_ATTRITION) {
            toward("A09",3);toward("S04",3);toward("A12",5);toward("A13",5)
        }
        // Keep the fast Ranger's earlier piercing lane when a new row opens at level sixty.
        if(budget>=35 && c==HeroClass.RANGER && preset==ArenaAutoBuildPreset.SWIFT_ASSAULT) {
            toward("A04",3);toward("S02",3);toward("A08",3)
        }
        if(budget>=85 && c==HeroClass.PALADIN && preset==ArenaAutoBuildPreset.WILD_TACTICS) {
            toward("A09",3);toward("S04",3);toward("A13",5);toward("S06",3);toward("A17",5)
        }
        if(budget>=90 && c==HeroClass.PALADIN && preset==ArenaAutoBuildPreset.WILD_TACTICS) {
            toward("S08",3);toward("A19",5)
        }
        while(ArenaSkillTreeRules.spentPoints(state)<budget) {
            val spent=ArenaSkillTreeRules.spentPoints(state)
            val allLegal=ArenaSkillTreeRules.view(state,budget,owned).nodes.filter { it.canAllocate }
            val legal=allLegal.filterNot { spent<60 && it.definition.isRoot && it.rank>=6 }.ifEmpty { allLegal }
            if(legal.isEmpty()) break
            val supportSpent=state.allocations.filter { ArenaIdentityCatalog.find(it.nodeId)!!.support }.sumOf { it.rank }
            // Revisit an authored target after its spent-point or parent gate becomes legal.
            val pending=pendingRanks.entries.firstNotNullOfOrNull { (id,rank) ->
                legal.firstOrNull { it.definition.id==id && it.rank<rank }
            }
            val selected=pending ?: legal.maxWith(compareBy<ArenaSkillTreeNodeView> { node ->
                val s=requireNotNull(ArenaIdentityCatalog.find(node.definition.id))
                val themed=if(s.kind in themes[preset.ordinal]) 1.10 else 1.0
                val heavy=if(preset==ArenaAutoBuildPreset.HEAVY_ASSAULT && s.turns>=2) 1.03 else 1.0
                val swift=if(preset==ArenaAutoBuildPreset.SWIFT_ASSAULT && s.turns==1 && !s.support) 1.06 else 1.0
                val opensBranch=node.rank==2 && nodes.any { child ->
                    child.sourceAttackId in owned && child.minimumSpentPoints<budget &&
                        node.definition.id in child.parentAnyOf && child.parentAnyOf.all { parent ->
                            (state.allocations.firstOrNull { it.nodeId==parent }?.rank ?: 0)<child.minParentRank
                        }
                }
                val supportLimit=max(1,(spent+1)/5)
                val support=if(s.support) {
                    if(spent<15 || supportSpent>=supportLimit && !(opensBranch && supportSpent==supportLimit)) .05 else .85
                } else 1.0
                // Diminishing priority produces several useful skills instead of one rank-ten spam build.
                val signature=if(s.slot in setOf("A01","A02","A03")) .85+roots[preset.ordinal][s.slot.last().digitToInt()-1]*.05 else 1.0
                val tie=1.0+Math.floorMod(s.slot.hashCode()*31+preset.ordinal*17,11)*.006
                // One more point can open an owned attack or activate an authored rank-five bonus.
                val step=if(opensBranch) 1.30 else if(node.rank==4) 1.08 else 1.0
                themed*heavy*swift*support*signature*tie*step/(1+node.rank*.18)
            }.thenBy { it.definition.slotKey })
            put(selected.definition.id)
        }
        return state
    }
}

internal fun ArenaSupportInput.withIdentityOpponentRules(
    preset: ArenaAutoBuildPreset=ArenaAutoBuildPreset.fromSeed(arenaAutoBuildPresetIdentitySeed(fighter.id)),
): ArenaSupportInput {
    if(identity!=null) return this
    val owned=SkillCatalog.forClass(fighter.heroClass).filter { it.unlockLevel.toLong()<=fighter.level }
    val tree=ArenaIdentityBuilds.allocate(fighter.heroClass,arenaLevel,owned.map { it.catalogId }.toSet(),preset)
    val ranks=tree.allocations.associate { it.nodeId to it.rank }
    val attacks=owned.mapNotNull { d -> ranks[d.catalogId]?.let { rank ->
        ArenaTurnInputAdapter.resolveAttack(ArenaAttackInput(d.catalogId,d.name,
            if(d.unlockLevel==1) 1 else d.unlockLevel/5+1,0,0,0),fighter.heroClass,rank)
    } }
    val support=ranks.filterKeys { ArenaIdentityCatalog.find(it)!!.support }
    return copy(fighter=fighter.copy(attacks=attacks),supportIds=support.keys,supportRanks=support,
        resolvedSupports=emptyMap()).freezeResolvedSupports().withIdentityRules()
}
