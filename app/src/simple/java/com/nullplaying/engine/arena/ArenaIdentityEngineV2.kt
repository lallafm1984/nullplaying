package com.nullplaying.engine.arena

import com.nullplaying.model.HeroClass
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import com.nullplaying.engine.arena.ArenaSupportEventType as Event

/** V2 milestone rules. Published V1 and legacy V6 replays retain their original runtimes. */
internal object ArenaIdentityEngineV2 {
    private const val BASIC="BASIC_ATTACK"
    private val basic=ArenaIdentitySkill(BASIC,"A00","basic",1,false,1,1,false,1,0,0,0,100,0.0,1.0)
    private val damageBuffs=setOf("SHOUT","RESOLVE","COUNTER","PURSUIT","OPPORTUNITY","CONDENSE",
        "RETRIBUTION","EXECUTE","STEALTH","AIM","BLESS","FOCUS","RAPID","followup_damage","next_basic_damage")
    private val guards=setOf("IRON","WRIST","RESTRAINT","BASIC_GUARD","SKILL_GUARD","GRACE","milestone_guard")
    private val cleanses=setOf("CLEANSE_ACCURACY","CLEANSE","REVEAL","DISPEL","JUDGMENT","SEAL")
    private val controls=setOf("TAUNT","TRAP","SLEEP")
    private val pierces=setOf("BASIC_PIERCE","PIERCE","OBSERVE","PHASE")
    private val coatings=setOf("POISON_COAT","BURN_PREP","EMBER","HEAL_BLOCK_PREP","MP_DRAIN","HEAL_TRACK")
    val attackKinds=setOf("accuracy","followup_damage","followup_accuracy","shield_extra","weaken_damage",
        "bleed_tick","poison_tick","burn_tick","delay","mitigation_ignore","shield_bypass","heal_reduction",
        "status_bonus","heal_on_hit_hp","shield_on_hit_hp","damage_bonus","execute_bonus","dispel","self_cleanse",
        "preparing_bonus","slow_accuracy","mp_drain","after_miss_bonus","recent_support_bonus","recent_fast_bonus",
        "recent_pierce_bonus","buffed_target_bonus","remove_illusion")
    val supportKinds=damageBuffs+guards+cleanses+controls+pierces+coatings+setOf(
        "BASIC_SHATTER","SKILL_SHATTER","BANDAGE","LAY_HANDS","HEAL","REGEN","DAMAGE_CAP","SMOKE",
        "EVASION","POISON_ACCELERATE","SHIELD","LOW_SHIELD","MIRROR","STABILIZE","STATUS_GUARD","TRUTH","SANCTUARY","LIFESTEAL")

    fun validate(input: ArenaSupportInput) {
        val snap=requireNotNull(input.identity)
        require(snap.version==ARENA_IDENTITY_V2_RULES_VERSION && input.traits.isEmpty())
        val ranks=input.fighter.attacks.associate { it.id to requireNotNull(it.arena).rank }+input.supportRanks
        require(snap.skills.map { it.id }.toSet()==ranks.keys)
        snap.skills.forEach { s ->
            require(ranks[s.id]==s.rank && s.support==(s.id in input.supportIds))
            require(s.kind in if(s.support) supportKinds else attackKinds) { "Unimplemented identity effect: ${s.kind}" }
        }
    }

    fun simulate(left: ArenaSupportInput,right: ArenaSupportInput,seed: Long,
        rules: ArenaTurnRules=ArenaTurnRules(),recordEvents: Boolean=true): ArenaSupportResult {
        validate(left);validate(right);require(left.fighter.id!=right.fighter.id)
        return Run(left,right,seed,rules,recordEvents).run()
    }

    internal fun scripted(left: ArenaSupportInput,right: ArenaSupportInput,seed: Long,
        schedule: Map<Pair<String,Int>,String>,rules: ArenaTurnRules=ArenaTurnRules()): ArenaSupportResult {
        validate(left);validate(right);require(left.fighter.id!=right.fighter.id)
        return Run(left,right,seed,rules,true,schedule).run()
    }

    private data class Buff(val skill: ArenaIdentitySkill,val expires: Int,var charges: Int,
        val value: Double=skill.magnitude*skill.statFactor,val start: Int=0)
    private data class Debuff(val kind: String,val source: String,val skill: ArenaIdentitySkill,
        val expires: Int,val value: Double,val starts: Int,val firstTickMultiplier: Double=1.0)
    private data class Cast(val skill: ArenaIdentitySkill,var remaining: Int,val id: Int,val started: Int)
    private class Fighter(val input: ArenaSupportInput) {
        val id=input.fighter.id;val clazz=input.fighter.heroClass;val data=checkNotNull(input.identity)
        val stats=data.stats;val skills=data.skills.sortedBy { it.id }
        val pressure=stats.attack*(skills.filter { !it.support }.maxOfOrNull { it.damagePercent/100.0*it.statFactor/it.actionTurns } ?: 1.0)*.85
        var hp=stats.hp;var mp=stats.mp*1000
        val buffs=linkedMapOf<String,Buff>();val debuffs=mutableListOf<Debuff>()
        val shields=linkedMapOf<String,Pair<Double,Int>>()
        val ready=mutableMapOf<String,Int>();val used=mutableSetOf<String>();val internalReady=mutableMapOf<String,Int>()
        var casting: Cast?=null;var castId=0;var immunityUntil=0;var pendingDelay=false;var tauntUntil=0
        var lastMiss=-100;var enemyMiss=-100;var lastHit=-100;var lastSupport=-100;var lastFast=-100;var lastPierce=-100
        var lastHealTurn=-100;var lastHeal=0.0;var consumedHeal=-101;var supportsInRow=0
        val shield get()=shields.values.sumOf { it.first }
        val magic get()=clazz==HeroClass.MAGE || clazz==HeroClass.CLERIC
        fun snapshot()=ArenaSupportFighterResult(hp,stats.hp,mp,stats.mp*1000,shield)
    }

    private class Run(left: ArenaSupportInput,right: ArenaSupportInput,val seed: Long,val rules: ArenaTurnRules,
        val recording: Boolean,val schedule: Map<Pair<String,Int>,String> = emptyMap()) {
        val fighters=listOf(Fighter(left),Fighter(right)).sortedBy { it.id }
        val events=mutableListOf<ArenaSupportEvent>();var sequence=0;var turn=0
        var resolving: Cast?=null
        fun other(f: Fighter)=fighters.first { it!==f }
        fun emit(type: Event,actor: Fighter?=null,target: Fighter?=null,skill: ArenaIdentitySkill?=null,
            hpBefore: Double?=null,hpAfter: Double?=null,mpBefore: Int?=null,mpAfter: Int?=null,
            shieldBefore: Double?=null,shieldAfter: Double?=null,amount: Double=0.0,reason: String?=null,
            cast: Cast?=null,expires: Int?=null) {
            if(recording) events+=ArenaSupportEvent(sequence,turn,type,actor?.id,target?.id,skill?.id,
                hpBefore=hpBefore,hpAfter=hpAfter,mpBeforeUnits=mpBefore,mpAfterUnits=mpAfter,
                shieldBefore=shieldBefore,shieldAfter=shieldAfter,amount=amount,reason=reason,
                castTurns=cast?.skill?.actionTurns ?: 0,remainingTurns=cast?.remaining ?: 0,
                castId=(cast ?: resolving)?.id,effectExpiresAtTurn=expires)
            sequence++
        }
        fun random(f: Fighter,key: String): Double {
            var h=seed xor -7046029254386353131L
            for(c in "${f.id}|$turn|${f.castId}|$key") h=(h xor c.code.toLong())*1099511628211L
            h=(h xor (h ushr 30))*-4658895280553007687L
            h=(h xor (h ushr 27))*-7723592293110705685L
            return ((h xor (h ushr 31)) ushr 11).toDouble()/9007199254740992.0
        }
        fun result(status: ArenaRunStatus,winner: Fighter?)=ArenaSupportResult(status,winner?.id,turn,
            fighters.associate { it.id to it.snapshot() },if(recording) events.toList() else emptyList(),
            ARENA_IDENTITY_V2_RULES_VERSION,seed,rules)
        fun finish(): ArenaSupportResult? {
            if(fighters.all { it.hp>0 }) return null
            fighters.filter { it.hp<=0 }.forEach { f ->
                emit(Event.KO,f,hpBefore=f.hp,hpAfter=f.hp)
                f.casting?.let { emit(Event.CAST_CANCELLED_KO,f,skill=it.skill,cast=it);f.casting=null }
            }
            val winner=fighters.singleOrNull { it.hp>0 }
            emit(Event.END,winner)
            return result(ArenaRunStatus.COMPLETED,winner)
        }
        fun run(): ArenaSupportResult {
            fighters.forEach { emit(Event.START,it,hpBefore=it.hp,hpAfter=it.hp,mpBefore=it.mp,
                mpAfter=it.mp,shieldBefore=0.0,shieldAfter=0.0) }
            for(t in 1..rules.safetyTurnLimit) {
                turn=t
                fighters.forEach(::expire)
                fighters.forEach { periodic(it) }
                finish()?.let { return it }
                // Neither choice sees the other fighter's current-turn selection or random roll.
                val choices=fighters.map { f -> if(f.casting==null) choose(f,other(f)) else null }
                fighters.forEachIndexed { i,f -> choices[i]?.let { start(f,it) } }
                fighters.forEach { f -> f.casting?.let { c -> c.remaining--;emit(Event.CAST_PROGRESS,f,skill=c.skill,cast=c) } }
                val order=if(random(fighters[0],"initiative") <
                    .5+.08*ArenaIdentityFormula.edge(fighters[0].stats.dexterity,fighters[1].stats.dexterity)) fighters else fighters.reversed()
                for(f in order) {
                    val c=f.casting ?: continue
                    if(c.remaining>0) continue // a prior resolution can delay or interrupt this cast
                    f.casting=null
                    if(c.skill.id!=BASIC) f.ready[c.skill.id]=turn+c.skill.cooldown+1
                    resolving=c
                    if(c.skill.support) { f.supportsInRow++;f.lastSupport=turn;support(f,other(f),c.skill) }
                    else { f.supportsInRow=0;attack(f,other(f),c.skill) }
                    resolving=null
                    finish()?.let { return it }
                }
            }
            emit(Event.SAFETY_ABORT,reason="operational_limit")
            return result(ArenaRunStatus.ABORTED_SAFETY_LIMIT,null)
        }
        fun expire(f: Fighter) {
            f.buffs.values.filter { it.expires<turn }.toList().forEach {
                f.buffs.remove(it.skill.kind);emit(Event.EFFECT_EXPIRED,f,skill=it.skill)
            }
            f.debuffs.removeAll { it.expires<turn }
            val old=f.shield
            f.shields.entries.removeAll { it.value.second<turn }
            if(f.shield!=old) emit(Event.EFFECT_EXPIRED,f,shieldBefore=old,shieldAfter=f.shield,reason="shield_expired")
        }
        fun changeMp(f: Fighter,delta: Int,s: ArenaIdentitySkill?=null,drain: Boolean=false) {
            val before=f.mp;f.mp=(before+delta).coerceIn(0,f.stats.mp*1000)
            if(before!=f.mp) emit(if(drain) Event.MP_DRAINED else Event.SUPPORT_TRIGGERED,f,skill=s,
                mpBefore=before,mpAfter=f.mp,amount=(before-f.mp).coerceAtLeast(0)/1000.0,
                reason=if(s==null) "identity_mp_regeneration" else "identity_mp_refund")
        }
        fun periodic(f: Fighter) {
            if(f.hp<=0) return
            changeMp(f,1000)
            f.buffs["REGEN"]?.takeIf { it.start<turn }?.let { heal(f,it.skill,f.stats.hp*it.value/100,periodic=true) }
            var remaining=f.stats.hp*.08
            for(d in f.debuffs.filter { it.kind in setOf("poison","bleed","burn") && it.starts<=turn }.toList()) {
                if(f.hp<=0 || remaining<=0) break
                val source=fighters.single { it.id==d.source }
                val raw=d.value*(if(turn==d.starts) d.firstTickMultiplier else 1.0)*(1-mitigation(source,f))
                val actual=damage(source,f,d.skill,raw,remaining,d.kind)
                remaining-=actual
            }
        }
        fun magnitude(s: ArenaIdentitySkill)=s.magnitude*s.statFactor
        fun attackPower(f: Fighter,s: ArenaIdentitySkill)=f.stats.attack*s.statFactor
        fun hasNegative(f: Fighter)=f.debuffs.isNotEmpty()
        fun eligible(f: Fighter,t: Fighter,s: ArenaIdentitySkill): Boolean {
            if(s.id==BASIC) return true
            if(turn<s.earliestTurn || f.mp<s.mp*1000 || (f.ready[s.id] ?: 0)>turn || s.once && s.id in f.used) return false
            if(!s.support) return true
            if(f.supportsInRow>=2) return false
            if(f.buffs[s.kind]?.let { it.expires>=turn && it.value>=magnitude(s) }==true) return false
            return when(s.kind) {
                "BANDAGE","HEAL","LAY_HANDS","LOW_SHIELD" -> f.hp/f.stats.hp<=s.n("cast_hp_threshold_percent",65.0)/100
                "RESOLVE" -> f.hp<=f.stats.hp*.5
                "COUNTER","RETRIBUTION" -> turn-f.lastHit<=2
                "PURSUIT" -> turn-f.lastMiss<=2
                "OPPORTUNITY" -> turn-f.enemyMiss<=2
                "EXECUTE" -> t.hp<=t.stats.hp*.4
                "CLEANSE_ACCURACY" -> f.debuffs.any { it.kind=="accuracy" }
                "CLEANSE" -> hasNegative(f)
                "SANCTUARY" -> hasNegative(f) || (t.skills.any { it.kind in controls || it.kind=="delay" } && f.immunityUntil<turn)
                "REVEAL","TRUTH" -> "STEALTH" in t.buffs || "MIRROR" in t.buffs
                "DISPEL","JUDGMENT","SEAL" -> t.buffs.isNotEmpty() || t.shield>0
                "POISON_ACCELERATE" -> t.debuffs.any { it.kind=="poison" && it.source==f.id && it.expires>turn }
                "HEAL_TRACK" -> t.lastHealTurn>f.consumedHeal && turn-t.lastHealTurn<=2+s.n("heal_track_window") && t.lastHeal>0
                "MP_DRAIN" -> t.mp>=5000
                "TAUNT","TRAP","SLEEP" -> t.immunityUntil<turn
                "BASIC_SHATTER","SKILL_SHATTER" -> t.shield>0
                "SHIELD" -> f.shield<f.stats.hp*magnitude(s)/100
                else -> true
            }
        }
        fun choose(f: Fighter,t: Fighter): ArenaIdentitySkill {
            if(f.tauntUntil>=turn) { f.tauntUntil=0;return basic }
            schedule[f.id to turn]?.let { id ->
                val forced=if(id==BASIC) basic else f.skills.firstOrNull { it.id==id }
                if(forced!=null && eligible(f,t,forced)) return forced
                return basic
            }
            val available=f.skills.filter { eligible(f,t,it) }+basic
            return available.maxWithOrNull(compareBy<ArenaIdentitySkill> { score(f,t,it) }.thenBy { it.id }) ?: basic
        }
        fun score(f: Fighter,t: Fighter,s: ArenaIdentitySkill): Double {
            val a=f.stats.attack
            if(!s.support) {
                val raw=attackPower(f,s)*s.damagePercent/100.0*(1+conditionalDamage(f,t,s)/100)
                val hit=hitChance(f,t,s)*(1-evasion(t,f,s))
                val cap=t.stats.hp*s.n("damage_hp_cap_percent",100.0)/100
                val hurt=min(raw,cap)*hit
                val lethal=if(hurt>=t.hp) a*.5/s.actionTurns else 0.0
                val status=when(s.kind) {
                    "burn_tick","poison_tick","bleed_tick" -> attackPower(f,s)*s.magnitude/100*s.duration*hit
                    "delay" -> if(t.casting!=null && t.immunityUntil<turn) a*.4 else 0.0
                    "heal_on_hit_hp" -> min(f.stats.hp*s.magnitude/100,f.stats.hp-f.hp)
                    "shield_on_hit_hp" -> f.stats.hp*s.magnitude/100*.5
                    else -> 0.0
                }
                val survivalTurns=(f.hp+f.shield)/t.pressure.coerceAtLeast(1.0)
                val completion=if(s.actionTurns>1 && s.actionTurns>survivalTurns)
                    (survivalTurns/s.actionTurns).coerceIn(.15,1.0) else 1.0
                return ((hurt+status)/s.actionTurns+lethal)*completion-(if(s.id==BASIC) 0.0 else s.mp*.10)
            }
            val v=magnitude(s)/100
            val basicDemand=if(f.skills.none { !it.support && eligible(f,t,it) }) 1.0 else .1
            val enemyCast=t.casting?.skill
            val availableEnemy=t.skills.filter { !it.support && eligible(t,f,it) }
            val incoming=enemyCast?.let { attackPower(t,it)*it.damagePercent/100.0/it.actionTurns }
                ?: availableEnemy.maxOfOrNull { attackPower(t,it)*it.damagePercent/100.0/it.actionTurns }
                ?: t.stats.attack
            val incomingHit=enemyCast?.let { attackPower(t,it)*it.damagePercent/100.0 }
                ?: availableEnemy.maxOfOrNull { attackPower(t,it)*it.damagePercent/100.0 } ?: t.stats.attack
            val basicExposure=if(enemyCast!=null) { if(enemyCast.id==BASIC) 1.0 else 0.0 }
                else if(availableEnemy.isEmpty()) 1.0 else .15
            val need=1.0 // Compare recovered/prevented HP with an attack; low HP alone is not extra value.
            return when(s.kind) {
                "HEAL","LAY_HANDS","BANDAGE" -> min(f.stats.hp-f.hp,f.stats.hp*v)*need
                "REGEN" -> min(f.stats.hp-f.hp,f.stats.hp*v*s.duration)*need
                "SHIELD","LOW_SHIELD" -> min(f.stats.hp*.20-f.shield,f.stats.hp*v)*.9*need
                // Guard value is expected prevented damage, allowing for misses and native mitigation.
                in guards -> (incoming+t.stats.attack*max(0,s.duration-1)*1.25)*v*.9*need * when(s.kind) {
                    "BASIC_GUARD" -> basicExposure
                    "SKILL_GUARD" -> 1-basicExposure
                    "GRACE" -> if(incomingHit>=f.stats.hp*.15) .8 else 0.0
                    else -> 1.0
                }
                "DAMAGE_CAP" -> max(0.0,incomingHit-f.stats.hp*v)*.85
                "MIRROR","EVASION" -> (incoming+t.stats.attack*2)*.27*need
                in damageBuffs -> a*v*(if(s.charges>0) s.charges*1.8 else s.duration*1.4)*.75
                in pierces -> a*s.n("next_attack_damage_bonus_percent",20.0)/100*s.charges*1.5*(if(s.kind=="BASIC_PIERCE") basicDemand else 1.0)
                "BASIC_SHATTER","SKILL_SHATTER" -> min(t.shield,a*v*s.charges)*(if(s.kind=="BASIC_SHATTER") basicDemand else 1.0)
                in controls -> if(t.casting!=null) incoming*.65 else a*.65
                "POISON_COAT","BURN_PREP","EMBER" -> a*v*2*s.charges*1.0
                "MP_DRAIN" -> if(t.mp<30000) a*1.2 else a*.65
                "SMOKE" -> (incoming+t.stats.attack*3)*v
                "HEAL_BLOCK_PREP" -> if(t.skills.any { it.kind in setOf("HEAL","REGEN","LAY_HANDS") }) a else 0.0
                "POISON_ACCELERATE" -> a*.9
                in cleanses -> if(s.kind in setOf("CLEANSE","CLEANSE_ACCURACY")) {
                    f.debuffs.filter { s.kind!="CLEANSE_ACCURACY" || it.kind=="accuracy" }.maxOfOrNull { d ->
                        val remaining=(d.expires-turn+1).coerceAtLeast(0)
                        when(d.kind) {
                            "poison","bleed","burn" -> d.value*remaining
                            "accuracy","damage" -> a*1.4*d.value/100*remaining
                            "healing" -> if(f.hp<f.stats.hp*.5) f.stats.hp*.08*d.value/100 else 0.0
                            else -> 0.0
                        }
                    }?.plus(a*.06) ?: 0.0
                } else {
                    val shieldValue=t.shield
                    val buffValue=t.buffs.values.maxOfOrNull { b ->
                        when(b.skill.kind) {
                            "MIRROR","EVASION" -> a*.25*b.charges
                            in guards -> a*1.4*b.value/100*min(3,b.expires-turn+1)
                            in damageBuffs -> incoming*b.value/100*if(b.charges>0) b.charges else min(3,b.expires-turn+1)
                            "REGEN" -> t.stats.hp*b.value/100*min(3,b.expires-turn+1)
                            else -> a*.1
                        }
                    } ?: 0.0
                    max(if(s.kind=="REVEAL") 0.0 else shieldValue,buffValue)+a*.06
                }
                "LIFESTEAL" -> min(f.stats.hp-f.hp,a*3*v)*need
                "HEAL_TRACK" -> min(t.lastHeal*v,a*(.25+s.n("heal_track_cap")/100))
                "STABILIZE" -> a*2*s.n("skill_damage_bonus_percent",10.0)/100+a*.2
                "TRUTH" -> a*1.1
                "STATUS_GUARD","SANCTUARY" -> if(hasNegative(f)) a*1.2 else a*.65
                else -> 0.0
            }-s.mp*.10
        }
        fun start(f: Fighter,s: ArenaIdentitySkill) {
            val before=f.mp
            if(s.id!=BASIC) { f.mp-=s.mp*1000;if(s.once) f.used+=s.id }
            val extra=if(f.pendingDelay) 1 else 0;f.pendingDelay=false
            val c=Cast(s,s.actionTurns+extra,++f.castId,turn);f.casting=c
            emit(Event.CAST_START,f,other(f),s,mpBefore=before,mpAfter=f.mp,amount=(before-f.mp)/1000.0,cast=c)
        }
        fun addBuff(f: Fighter,s: ArenaIdentitySkill,value: Double=magnitude(s),kind: String=s.kind,
            charges: Int=s.charges,duration: Int=s.duration) {
            val effective=s.copy(kind=kind)
            val prior=f.buffs[kind]
            if(prior!=null && prior.value>value && prior.expires>=turn) return
            f.buffs[kind]=Buff(effective,turn+duration,charges,value,turn)
        }
        fun consume(f: Fighter,kind: String) {
            val b=f.buffs[kind] ?: return
            if(b.charges>0 && --b.charges<=0) {
                f.buffs.remove(kind);emit(Event.EFFECT_EXPIRED,f,skill=b.skill,reason="consumed")
            }
        }
        fun addShield(f: Fighter,s: ArenaIdentitySkill,amount: Double) {
            val before=f.shield;val old=f.shields[s.id]?.first ?: 0.0
            val actual=min(amount.coerceAtLeast(0.0),max(0.0,f.stats.hp*.20-(before-old)))
            if(actual<=old) return
            f.shields[s.id]=actual to (turn+s.duration.coerceAtLeast(2))
            emit(Event.SUPPORT_TRIGGERED,f,skill=s,shieldBefore=before,shieldAfter=f.shield,reason="shield_created")
        }
        fun heal(f: Fighter,s: ArenaIdentitySkill,amount: Double,periodic: Boolean=false) {
            val reduction=(f.debuffs.filter { it.kind=="healing" }.maxOfOrNull { it.value } ?: 0.0).coerceAtMost(40.0)
            val before=f.hp;f.hp=min(f.stats.hp,f.hp+(amount*(1-reduction/100)).roundToInt().coerceAtLeast(0))
            if(f.hp>before) { f.lastHealTurn=turn;f.lastHeal=f.hp-before
                emit(Event.HEAL_APPLIED,f,skill=s,hpBefore=before,hpAfter=f.hp,amount=f.hp-before,reason=if(periodic) "hot" else null) }
        }
        fun debuff(f: Fighter,t: Fighter,s: ArenaIdentitySkill,kind: String,value: Double,duration: Int=2) {
            t.buffs["STATUS_GUARD"]?.let { consume(t,"STATUS_GUARD");emit(Event.STATUS_BLOCKED,t,skill=it.skill);return }
            val reduced=if("SANCTUARY" in t.buffs) (duration-1).coerceAtLeast(1) else duration
            if(kind !in setOf("poison","bleed","burn")) {
                val old=t.debuffs.filter { it.kind==kind }
                if(old.any { it.value>value }) return
                t.debuffs.removeAll { it.kind==kind }
            } else {
                val sameAction=t.debuffs.firstOrNull { it.kind==kind && it.source==f.id && it.starts==turn+1 }
                if(sameAction!=null) {
                    if(sameAction.value>=value) return
                    t.debuffs.remove(sameAction)
                }
                if(t.debuffs.count { it.kind==kind }>=3) return
            }
            t.debuffs+=Debuff(kind,f.id,s,turn+reduced,value,turn+1)
            emit(Event.STATUS_APPLIED,f,t,s,expires=turn+reduced,reason=kind)
        }
        fun cleanse(f: Fighter,s: ArenaIdentitySkill,accuracyOnly: Boolean=false): Boolean {
            val selected=f.debuffs.filter { !accuracyOnly || it.kind=="accuracy" }.maxByOrNull { it.value } ?: return false
            if(selected.kind=="poison") f.debuffs.removeAll { it.kind=="poison" } else f.debuffs.remove(selected)
            emit(Event.STATUS_REMOVED,f,skill=s,reason=selected.kind);return true
        }
        fun dispel(t: Fighter,s: ArenaIdentitySkill,illusionOnly: Boolean=false): Boolean {
            val target=t.buffs.values.filter { !illusionOnly || it.skill.kind in setOf("STEALTH","MIRROR") }
                .maxByOrNull { it.value }
            if(target!=null) { t.buffs.remove(target.skill.kind);emit(Event.EFFECT_EXPIRED,t,skill=target.skill,reason="dispelled");return true }
            if(!illusionOnly && t.shield>0) {
                val before=t.shield;t.shields.entries.maxByOrNull { it.value.first }?.let { t.shields.remove(it.key) }
                emit(Event.EFFECT_EXPIRED,t,skill=s,shieldBefore=before,shieldAfter=t.shield,reason="dispelled");return true
            }
            return false
        }
        fun control(f: Fighter,t: Fighter,s: ArenaIdentitySkill,base: Double,mode: String) {
            if(t.immunityUntil>=turn) { emit(Event.CONTROL_RESISTED,t,f,s,reason="control_immunity");return }
            t.buffs["STATUS_GUARD"]?.let { consume(t,"STATUS_GUARD");emit(Event.STATUS_BLOCKED,t,skill=it.skill);return }
            val resist=t.buffs["SANCTUARY"]?.skill?.n("resistance_bonus_pp") ?: 0.0
            val chance=((base+s.n("control_bonus_pp"))/100+.10*ArenaIdentityFormula.edge(f.stats.aim,t.stats.resistance)-resist/100).coerceIn(.30,.75)
            if(random(f,"control:${s.id}")>=chance) { emit(Event.CONTROL_RESISTED,t,f,s);return }
            if(s.n("control_accuracy")>0) addBuff(f,s,s.n("control_accuracy"),"followup_accuracy",1,2)
            t.immunityUntil=turn+3
            when(mode) {
                "TAUNT" -> t.tauntUntil=turn+3
                "SLEEP" -> if(t.casting!=null) {
                    val c=checkNotNull(t.casting);t.casting=null
                    if(c.skill.id!=BASIC) t.ready[c.skill.id]=turn+c.skill.cooldown+1
                    emit(Event.CAST_CANCELLED_KO,t,skill=c.skill,cast=c,reason="interrupted")
                } else t.pendingDelay=true
                else -> if(t.casting!=null) { t.casting!!.remaining++;emit(Event.CAST_DELAYED,t,skill=s,cast=t.casting) } else t.pendingDelay=true
            }
            emit(Event.CONTROL_APPLIED,f,t,s,reason=mode,expires=t.immunityUntil)
        }
        fun removalBonus(f: Fighter,s: ArenaIdentitySkill,success: Boolean) {
            if(success && s.n("removal_damage")>0) addBuff(f,s,s.n("removal_damage"),"followup_damage",1,2)
        }
        fun directHeal(f: Fighter,s: ArenaIdentitySkill,amount: Double) {
            val before=f.hp
            heal(f,s,amount)
            if(f.hp>before) {
                if(s.n("heal_shield")>0) addShield(f,s.copy(duration=2),f.stats.hp*s.n("heal_shield")/100)
                if(s.n("heal_cleanse")>0) cleanse(f,s)
            }
        }
        fun drain(f: Fighter,t: Fighter,s: ArenaIdentitySkill) {
            val before=t.mp
            changeMp(t,-s.magnitude.roundToInt()*1000,s,true)
            if(t.mp<before && s.n("drain_refund")>0) changeMp(f,1000,s)
        }
        fun support(f: Fighter,t: Fighter,s: ArenaIdentitySkill) {
            emit(Event.SUPPORT_APPLIED,f,skill=s)
            when(s.kind) {
                "HEAL","BANDAGE","LAY_HANDS" -> directHeal(f,s,f.stats.hp*magnitude(s)/100)
                "SHIELD","LOW_SHIELD" -> { addShield(f,s,f.stats.hp*magnitude(s)/100); if(s.n("shield_guard")>0) addBuff(f,s,s.n("shield_guard"),"milestone_guard",0,2) }
                "CLEANSE","CLEANSE_ACCURACY" -> if(cleanse(f,s,s.kind=="CLEANSE_ACCURACY")) { addBuff(f,s,6.0,"followup_accuracy",1,2);removalBonus(f,s,true) }
                "DISPEL","JUDGMENT","SEAL","REVEAL" -> if(dispel(t,s,s.kind=="REVEAL")) { addBuff(f,s,6.0,"followup_accuracy",1,2);removalBonus(f,s,true) }
                in controls -> control(f,t,s,s.n("base_success_percent",50.0),s.kind)
                "SMOKE" -> debuff(f,t,s,"accuracy",s.magnitude,s.duration)
                "WRIST" -> debuff(f,t,s,"damage",magnitude(s),s.duration)
                "POISON_ACCELERATE" -> t.debuffs.firstOrNull { it.kind=="poison" && it.source==f.id && it.expires>turn }?.let { old ->
                    if(t.debuffs.count { it.kind=="poison" }<3) t.debuffs+=old.copy(starts=turn+1,expires=old.expires+s.n("poison_duration").toInt(),firstTickMultiplier=1.2+s.n("poison_first_tick"))
                }
                "MIRROR","STATUS_GUARD" -> addBuff(f,s,charges=s.magnitude.toInt())
                "SANCTUARY" -> { f.debuffs.replaceAll { it.copy(expires=max(turn,it.expires-1)) };addBuff(f,s) }
                else -> { require(s.kind in supportKinds);addBuff(f,s) }
            }
        }
        fun conditionalDamage(f: Fighter,t: Fighter,s: ArenaIdentitySkill): Double = (when(s.kind) {
            "preparing_bonus" -> if(t.casting!=null) s.magnitude else 0.0
            "after_miss_bonus" -> if(turn-f.lastMiss<=2+s.n("miss_window")) s.magnitude else 0.0
            "execute_bonus" -> if(t.hp<=t.stats.hp*(.35+s.n("execute_threshold")/100)) s.magnitude else 0.0
            "status_bonus" -> if(t.debuffs.any { it.source==f.id }) s.magnitude else 0.0
            "recent_support_bonus" -> if(turn-t.lastSupport<=2) s.magnitude else 0.0
            "recent_fast_bonus" -> if(turn-t.lastFast<=2) s.magnitude else 0.0
            "recent_pierce_bonus" -> if(turn-t.lastPierce<=2) s.magnitude else 0.0
            "buffed_target_bonus" -> if(t.buffs.isNotEmpty() || t.shield>0) s.magnitude else 0.0
            "damage_bonus" -> s.magnitude
            else -> 0.0
        }) + (if(turn-f.lastMiss<=2) s.n("after_miss_damage") else 0.0) +
            (if(t.shield>0) s.n("shield_target_damage") else 0.0)
        fun matches(b: Buff,s: ArenaIdentitySkill)=when(b.skill.kind) {
            "RAPID" -> s.actionTurns==1
            "next_basic_damage","BASIC_PIERCE","BASIC_SHATTER" -> s.id==BASIC
            "SKILL_SHATTER","STABILIZE" -> s.id!=BASIC
            else -> true
        }
        fun hitChance(f: Fighter,t: Fighter,s: ArenaIdentitySkill): Double {
            val own=f.buffs.values.filter { matches(it,s) }.maxOfOrNull { b->when(b.skill.kind) {
                "followup_accuracy","STABILIZE" -> b.value
                "AIM","BLESS","FOCUS" -> b.skill.n("accuracy_bonus_pp")
                "PURSUIT" -> 6.0
                else -> 0.0
            } } ?: 0.0
            val debuff=f.debuffs.filter { it.kind=="accuracy" }.maxOfOrNull { it.value } ?: 0.0
            return (f.stats.baseHit+.05*ArenaIdentityFormula.edge(f.stats.aim,t.stats.evade)+
                (own-debuff+if(s.kind=="accuracy") s.magnitude else 0.0)/100).coerceIn(.70,.99)
        }
        fun evasion(t: Fighter,f: Fighter,s: ArenaIdentitySkill): Double {
            val native=(t.stats.baseEvade+.04*ArenaIdentityFormula.edge(t.stats.evade,f.stats.aim)).coerceIn(.02,.12)
            val ignored=if(s.capstone=="native_evasion_ignore") s.n("capstone_value")/100 else 0.0
            val active=t.buffs["EVASION"]?.let { (it.value/100+.05*ArenaIdentityFormula.edge(
                .7*t.input.fighter.stats.dexterity+.3*t.input.fighter.stats.wisdom,f.stats.aim)).coerceIn(.15,.30) } ?: 0.0
            return (1-(1-(native-ignored).coerceAtLeast(0.0))*(1-active)).coerceAtMost(.35)
        }
        fun mitigation(f: Fighter,t: Fighter)= (.04+.04*ArenaIdentityFormula.edge(
            if(f.magic) t.stats.magicGuard else t.stats.physicalGuard,f.stats.weightedAttack)).coerceIn(.01,.08)
        fun attack(f: Fighter,t: Fighter,s: ArenaIdentitySkill) {
            val buffs=f.buffs.values.filter { matches(it,s) }.toList()
            val hit=hitChance(f,t,s);val evade=evasion(t,f,s)
            val bonus=buffs.sumOf { b -> when(b.skill.kind) {
                in damageBuffs -> b.value
                in pierces -> b.skill.n("next_attack_damage_bonus_percent")
                "followup_accuracy" -> b.skill.n("followup_damage_bonus")
                "STABILIZE" -> b.skill.n("skill_damage_bonus_percent")
                else -> 0.0
            } }.coerceAtMost(50.0)
            val condition=conditionalDamage(f,t,s)
            if(s.kind=="after_miss_bonus" && condition>0) f.lastMiss=-100
            buffs.filter { it.charges>0 && it.skill.kind !in setOf("MIRROR","EVASION","STATUS_GUARD","BASIC_GUARD","SKILL_GUARD","GRACE","DAMAGE_CAP") }
                .forEach { consume(f,it.skill.kind) }
            if(s.actionTurns==1) f.lastFast=turn
            if(s.kind in setOf("mitigation_ignore","shield_bypass") || buffs.any { it.skill.kind in pierces }) f.lastPierce=turn
            if(random(f,"hit:${s.id}")>=hit) { f.lastMiss=turn;t.enemyMiss=turn;emit(Event.ATTACK_MISS,f,t,s);return }
            val dodge=random(f,"evade:${s.id}")<evade
            if("EVASION" in t.buffs) consume(t,"EVASION")
            if(dodge) { f.lastMiss=turn;t.enemyMiss=turn;emit(Event.ATTACK_EVADED,t,f,s);return }
            val mirror=t.buffs["MIRROR"]
            val truth=buffs.firstOrNull { it.skill.kind=="TRUTH" }?.value ?: 0.0
            if(mirror!=null) {
                consume(t,"MIRROR")
                if(random(f,"mirror:${s.id}")<(.25+mirror.skill.n("mirror_chance_pp")/100)*(1-truth/100)) { f.lastMiss=turn;t.enemyMiss=turn;emit(Event.ATTACK_EVADED,t,f,s,reason="mirror");return }
            }
            val suppressed=f.debuffs.filter { it.kind=="damage" }.maxOfOrNull { it.value } ?: 0.0
            val penalty=f.buffs["RESTRAINT"]?.skill?.n("self_damage_penalty_percent") ?: 0.0
            var raw=attackPower(f,s)*s.damagePercent/100.0*(1+(bonus+condition).coerceAtMost(50.0)/100)*
                (1-(suppressed+penalty).coerceAtMost(40.0)/100)*(.94+.12*random(f,"damage:${s.id}"))
            var reduction=mitigation(f,t)
            val ignore=max(if(s.kind=="mitigation_ignore") s.magnitude else 0.0,
                buffs.filter { it.skill.kind in pierces && it.skill.kind!="PHASE" }.maxOfOrNull { it.value } ?: 0.0)
                        for(b in t.buffs.values.filter { it.skill.kind in guards && when(it.skill.kind) {
                "BASIC_GUARD"->s.id==BASIC;"SKILL_GUARD"->s.id!=BASIC
                "GRACE"->raw>=t.stats.hp*.15;else->true } }.toList()) {
                val value=if(b.skill.kind=="GRACE") min(b.value/100,t.stats.hp*.08/raw.coerceAtLeast(1.0)) else b.value/100
                reduction=1-(1-reduction)*(1-value);consume(t,b.skill.kind)
            }
            reduction=reduction.coerceAtMost(.40)*(1-ignore/100)
            buffs.firstOrNull { it.skill.kind=="HEAL_TRACK" }?.let { b ->
                if(t.lastHealTurn>f.consumedHeal && turn-t.lastHealTurn<=2+b.skill.n("heal_track_window")) {
                    raw+=min(t.lastHeal*b.value/100,attackPower(f,s)*(.25+b.skill.n("heal_track_cap")/100))
                    f.consumedHeal=t.lastHealTurn
                }
            }
            val prevented=raw*reduction;raw-=prevented
            if(prevented>0) emit(Event.DAMAGE_REDUCED,t,f,amount=prevented,reason="identity_mitigation")
            val shieldBonus=max(if(s.kind=="shield_extra") s.magnitude else 0.0,
                buffs.filter { it.skill.kind in setOf("BASIC_SHATTER","SKILL_SHATTER") }.maxOfOrNull { it.value } ?: 0.0)
            val bypass=max(if(s.kind=="shield_bypass") s.magnitude else 0.0,
                buffs.filter { it.skill.kind=="PHASE" }.maxOfOrNull { it.value } ?: 0.0)/100
            var hpCap=t.stats.hp*s.n("damage_hp_cap_percent",100.0)/100
            t.buffs["DAMAGE_CAP"]?.takeIf { raw>t.stats.hp*it.value/100 }?.let {
                hpCap=min(hpCap,t.stats.hp*it.value/100);consume(t,"DAMAGE_CAP") }
            val dealt=damage(f,t,s,raw,hpCap,null,shieldBonus,bypass)
            if(t.hp<=0) return // no healing, control, or extra damage after a lethal result
            if(truth>0) t.buffs["STEALTH"]?.let { t.buffs["STEALTH"]=it.copy(value=it.value*(1-truth/100)) }
            when(s.kind) {
                "followup_damage","followup_accuracy" -> addBuff(f,s,s.magnitude,s.kind,s.charges,s.duration)
                "weaken_damage" -> debuff(f,t,s,"damage",s.magnitude,s.duration)
                "slow_accuracy" -> debuff(f,t,s,"accuracy",s.magnitude,s.duration)
                "heal_reduction" -> debuff(f,t,s,"healing",s.magnitude,s.duration)
                "poison_tick","bleed_tick","burn_tick" -> debuff(f,t,s,s.kind.substringBefore('_'),attackPower(f,s)*s.magnitude/100,s.duration)
                "delay" -> control(f,t,s,s.n("control_success_r1_percent")+(s.n("control_success_r10_percent")-s.n("control_success_r1_percent"))*(s.rank-1)/9,"delay")
                "heal_on_hit_hp" -> directHeal(f,s,f.stats.hp*s.magnitude/100)
                "shield_on_hit_hp" -> addShield(f,s,f.stats.hp*s.magnitude/100)
                "dispel" -> removalBonus(f,s,dispel(t,s))
                "self_cleanse" -> removalBonus(f,s,cleanse(f,s))
                "remove_illusion" -> t.buffs["MIRROR"]?.let { consume(t,"MIRROR");removalBonus(f,s,true) }
                "mp_drain" -> drain(f,t,s)
                else -> require(s.kind=="basic" || s.kind in attackKinds)
            }
            for(b in buffs) when(b.skill.kind) {
                "POISON_COAT","BURN_PREP","EMBER" -> debuff(f,t,b.skill,if(b.skill.kind=="POISON_COAT") "poison" else "burn",attackPower(f,s)*b.value/100)
                "HEAL_BLOCK_PREP" -> debuff(f,t,b.skill,"healing",b.value,3)
                "MP_DRAIN" -> changeMp(t,-b.value.roundToInt()*1000,b.skill,true)
                "LIFESTEAL" -> heal(f,b.skill,min(f.stats.hp*.05,dealt*b.value/100))
            }
            if(t.hp<=0) return
            if(s.n("on_hit_accuracy")>0) addBuff(f,s,s.n("on_hit_accuracy"),"followup_accuracy",1,2)
            if(s.n("on_hit_guard")>0) addBuff(f,s,s.n("on_hit_guard"),"milestone_guard",0,2)
            when(s.capstone) {
                "next_basic_damage" -> addBuff(f,s,s.n("capstone_value"),"next_basic_damage",1,2)
                "bleed" -> if(t.debuffs.none { it.kind=="bleed" && it.skill.id==s.id }) debuff(f,t,s,"bleed",f.stats.attack*.06)
                "mp_refund_on_hit" -> changeMp(f,1000,s)
                "heal_on_hit" -> if((f.internalReady[s.id] ?: 0)<=turn) { heal(f,s,f.stats.hp*.015);f.internalReady[s.id]=turn+4 }
                "shield_on_hit" -> addShield(f,s,f.stats.hp*.02)
            }
        }
        /** One ledger path for direct and periodic damage; absorption never becomes extra HP damage. */
        fun damage(f: Fighter,t: Fighter,s: ArenaIdentitySkill,amount: Double,cap: Double,
            dot: String?=null,shieldExtra: Double=0.0,bypass: Double=0.0): Double {
            val raw=amount.coerceAtLeast(0.0)
            val bypassed=raw*bypass.coerceIn(0.0,1.0)
            val shieldPart=raw-bypassed
            val beforeShield=t.shield
            val shieldDamage=min(beforeShield,shieldPart*(1+shieldExtra/100))
            var remaining=shieldDamage
            for(key in t.shields.keys.toList()) {
                val (value,expiry)=t.shields.getValue(key);val take=min(value,remaining);remaining-=take
                if(value<=take) t.shields.remove(key) else t.shields[key]=(value-take) to expiry
                if(remaining<=0) break
            }
            if(shieldDamage>0) emit(Event.SHIELD_ABSORBED,t,f,shieldBefore=beforeShield,shieldAfter=t.shield,amount=shieldDamage,reason="identity_shield")
            val absorbedBase=if(shieldExtra>0) shieldDamage/(1+shieldExtra/100) else shieldDamage
            val hpRaw=(bypassed+(shieldPart-absorbedBase).coerceAtLeast(0.0)).coerceAtMost(cap.coerceAtLeast(0.0))
            val committed=if(hpRaw>0 && cap>=1) hpRaw.roundToInt().coerceAtLeast(1).toDouble().coerceAtMost(cap) else 0.0
            val before=t.hp;t.hp=(before-committed).coerceAtLeast(0.0)
            if(t.hp<before) t.lastHit=turn
            emit(if(dot==null) Event.ATTACK_HIT else Event.DOT_DAMAGE,f,t,s,hpBefore=before,hpAfter=t.hp,
                amount=before-t.hp,reason=dot)
            return before-t.hp
        }
    }
}
