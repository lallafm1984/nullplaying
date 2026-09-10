package com.nullplaying.engine.arena

import com.nullplaying.model.HeroClass
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt
import kotlin.math.sqrt

const val ARENA_IDENTITY_V1_RULES_VERSION = "arena-stat-identity-v1"
const val ARENA_IDENTITY_V2_RULES_VERSION = "arena-stat-identity-v2"
const val ARENA_IDENTITY_V3_RULES_VERSION = "arena-stat-identity-v3"
const val ARENA_IDENTITY_V4_RULES_VERSION = "arena-stat-identity-v4"
const val ARENA_IDENTITY_RULES_VERSION = "arena-stat-identity-v5"

/** Captured once at issuance. No catalog lookup or CP rescaling is allowed during replay. */
@Serializable
data class ArenaIdentitySkill(
    val id: String,
    val slot: String,
    val kind: String,
    val rank: Int,
    val support: Boolean,
    val actionTurns: Int,
    val cooldown: Int,
    val once: Boolean,
    val earliestTurn: Int,
    val duration: Int,
    val charges: Int,
    val mp: Int,
    val damagePercent: Int,
    val magnitude: Double,
    val statFactor: Double,
    val numbers: Map<String, Double> = emptyMap(),
    val options: Map<String, String> = emptyMap(),
    val capstone: String = "",
) {
    init {
        require(id.isNotBlank() && kind.isNotBlank() && rank in 1..10)
        require(actionTurns in 1..4 && (once && cooldown == 0 || cooldown in 1..100))
        require((mp in 1..100 || id=="BASIC_ATTACK" && mp==0) && damagePercent in 0..5000)
        require(earliestTurn >= 1 && duration in 0..100 && charges in 0..100)
        require(magnitude.isFinite() && magnitude >= 0 && statFactor in 0.8..1.2)
        require(numbers.values.all { it.isFinite() })
    }
    fun n(key: String, fallback: Double = 0.0) = numbers[key] ?: fallback
}

@Serializable
data class ArenaIdentityStats(
    val hp: Double, val mp: Int, val attack: Double, val weightedAttack: Double,
    val aim: Double, val evade: Double, val dexterity: Double,
    val physicalGuard: Double, val magicGuard: Double, val resistance: Double,
    val baseHit: Double, val baseEvade: Double,
) {
    init {
        require(listOf(hp, attack, weightedAttack, aim, evade, dexterity, physicalGuard,
            magicGuard, resistance, baseHit, baseEvade).all { it.isFinite() && it >= 0 })
        require(hp > 0 && attack > 0 && mp in 1..100000)
        require(baseHit in 0.7..0.99 && baseEvade in 0.02..0.12)
    }
}

@Serializable
data class ArenaIdentitySnapshot(
    val version: String = ARENA_IDENTITY_V1_RULES_VERSION,
    val stats: ArenaIdentityStats,
    val skills: List<ArenaIdentitySkill>,
) {
    init {
        require(version in setOf(ARENA_IDENTITY_V1_RULES_VERSION, ARENA_IDENTITY_V2_RULES_VERSION, ARENA_IDENTITY_V3_RULES_VERSION, ARENA_IDENTITY_V4_RULES_VERSION, ARENA_IDENTITY_RULES_VERSION))
        require(skills.map { it.id }.distinct().size == skills.size && skills.size <= 30)
    }
}

/** No dependency on Android, matchmaking, equipment, clocks, or a previous result. */
object ArenaIdentityFormula {
    private val offense = arrayOf(
        doubleArrayOf(.55,.25,.20,0.0,0.0,0.0), doubleArrayOf(.30,0.0,.55,0.0,.15,0.0),
        doubleArrayOf(.10,0.0,.55,0.0,.35,0.0), doubleArrayOf(0.0,0.0,.10,.60,.30,0.0),
        doubleArrayOf(0.0,0.0,0.0,.10,.55,.35), doubleArrayOf(.50,.15,0.0,0.0,0.0,.35))
    private val accuracy = arrayOf(
        doubleArrayOf(0.0,0.0,.60,0.0,.40,0.0), doubleArrayOf(0.0,0.0,.70,0.0,.30,0.0),
        doubleArrayOf(0.0,0.0,.40,0.0,.60,0.0), doubleArrayOf(0.0,0.0,0.0,.40,.60,0.0),
        doubleArrayOf(0.0,0.0,0.0,0.0,.65,.35), doubleArrayOf(0.0,0.0,.35,0.0,.35,.30))
    private fun index(c: HeroClass) = when(c) {
        HeroClass.WARRIOR->0; HeroClass.ROGUE->1; HeroClass.RANGER->2
        HeroClass.MAGE->3; HeroClass.CLERIC->4; HeroClass.PALADIN->5
    }
    fun weighted(s: ArenaCoreStats, weights: List<Double>) =
        s.values().take(6).zip(weights).sumOf { (v,w)->v*w }
    fun mean(s: ArenaCoreStats) = s.values().take(6).average().coerceAtLeast(1.0)
    fun edge(a: Double,b: Double): Double {
        val x=sqrt(a.coerceAtLeast(1.0));val y=sqrt(b.coerceAtLeast(1.0))
        return (x-y)/(x+y)
    }
    fun derive(f: ArenaFighterInput): ArenaIdentityStats {
        val s=f.stats;val m=mean(s);val i=index(f.heroClass)
        val w=weighted(s,offense[i].toList()).coerceAtLeast(1.0)
        return ArenaIdentityStats(
            hp=(180+70*sqrt(.85*m+.15*s.constitution)).roundToInt().toDouble(),
            mp=(45+12*sqrt(.5*s.intelligence+.3*s.wisdom+.2*m)).roundToInt(),
            attack=(8+5*sqrt(.7*w+.3*m))*doubleArrayOf(1.044,1.0,.960,1.035,1.030,1.059)[i],
            weightedAttack=w, aim=weighted(s,accuracy[i].toList()),
            evade=.65*s.dexterity+.35*s.wisdom, dexterity=s.dexterity,
            physicalGuard=.65*s.constitution+.2*s.strength+.15*s.wisdom,
            magicGuard=.55*s.wisdom+.25*s.intelligence+.2*s.constitution,
            resistance=.6*s.wisdom+.25*s.constitution+.15*s.charisma,
            baseHit=doubleArrayOf(.94,.92,.96,.93,.95,.94)[i],
            baseEvade=doubleArrayOf(.03,.08,.06,.03,.03,.02)[i])
    }
}

data class ArenaIdentityDefinition(
    val id: String, val heroClass: HeroClass, val slot: String, val kind: String,
    val turns: Int, val cooldown: Int, val earliest: Int, val duration: Int,
    val mp: List<Int>, val damage: List<Int>, val magnitude: List<Double>,
    val charges: List<Int>, val weights: List<Double>,
    val numbers: Map<String,List<Double>>, val options: Map<String,String>, val capstone: String,
) {
    val support get()=slot.startsWith("S")
    fun resolve(rank: Int, fighter: ArenaFighterInput): ArenaIdentitySkill {
        require(rank in 1..10 && heroClass == fighter.heroClass)
        val derived=ArenaIdentityFormula.derive(fighter)
        val stat= ArenaIdentityFormula.weighted(fighter.stats,weights).coerceAtLeast(1.0)
        val factor=if(support) {
            if(options["stat_scaled"]=="true") sqrt(stat/ArenaIdentityFormula.mean(fighter.stats)).coerceIn(.85,1.15) else 1.0
        } else sqrt(stat/derived.weightedAttack).coerceIn(.9,1.1)
        val base = ArenaIdentitySkill(id,slot,kind,rank,support,turns,cooldown.coerceAtLeast(0),
            cooldown<0,earliest,duration,charges[rank-1],mp[rank-1],damage[rank-1],
            magnitude[rank-1],factor,numbers.mapValues { it.value[rank-1] },options,
            capstone.takeIf { rank==10 }.orEmpty())
        // Early Paladin pressure is lower; its later unlocked attacks retain competitive output.
        // This is fixed skill tuning, applied symmetrically and frozen before the battle starts.
        val tuned=if(heroClass==HeroClass.PALADIN && !support && slot.drop(1).toInt()>=5)
            base.copy(damagePercent=(base.damagePercent*1.04).roundToInt()) else base
        return ArenaIdentityMilestones.apply(tuned).copy(mp=ArenaIdentityMana.cost(this,rank))
    }
}

/** Explicit issuance boundary; historical inputs with no snapshot remain legacy inputs. */
fun ArenaSupportInput.withIdentityRules(): ArenaSupportInput {
    if(identity != null) return this
    require(traits.isEmpty()) { "Legacy growth cannot be combined with identity rules" }
    val attackRanks=fighter.attacks.associate { it.id to requireNotNull(it.arena).rank }
    require(supportRanks.keys == supportIds)
    val ranks=attackRanks+supportRanks
    val skills=ranks.toSortedMap().map { (id,rank)->
        requireNotNull(ArenaIdentityCatalog.find(id)).resolve(rank,fighter)
    }
    return copy(identity=ArenaIdentitySnapshot(version=ARENA_IDENTITY_RULES_VERSION,stats=ArenaIdentityFormula.derive(fighter),skills=skills))
}
