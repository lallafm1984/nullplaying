package com.nullplaying.engine.arena

import com.nullplaying.engine.PublicPlayerBattleDerivation
import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.model.HeroClass
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

/** Actual engine, legal authored point paths, rolled/grown stats; no average-stat stand-ins. */
class ArenaIdentityBalanceTest {
    private val engine=SimpleGameEngine()
    internal fun profile(c: HeroClass,level: Int,seed: Int,preset: ArenaAutoBuildPreset,combatPower: Long?=null): ArenaSupportInput {
        val roll=engine.rollStats(73190000L+seed+(System.getenv("IDENTITY_BALANCE_OFFSET")?.toLong() ?: 0L),c)
        val state=engine.newGame("balance",c,roll.stats.copy(),roll.nextSeed,1000L)
        ArenaSyntheticProfileGrowth.grow(engine,state.hero.stats,c,targetLevel=level.toLong(),identitySeed=roll.nextSeed)
        val power=combatPower ?: (1L+(level-1)*5L)*2
        val stats=requireNotNull(PublicPlayerBattleDerivation.deriveStats(c,level.toLong(),power,state.hero.stats))
        val envelope=ArenaV6OpponentInputFactory.create(
            projectionId="profile-${c.name}-$level-$seed-${preset.stableId}",displayName="balance",
            heroClass=c,level=level.toLong(),combatPower=power,stats=stats,
            learnedSkills=PublicPlayerBattleDerivation.learnedSkillsForClassAndLevel(c,level.toLong()),
            arenaLevel=level.coerceAtMost(100),stableSeed=17800000L+seed*100+preset.ordinal,buildPreset=preset,
        )
        if(envelope==null) {
            val owned=com.nullplaying.engine.SkillCatalog.forClass(c).filter { it.unlockLevel<=level }.map { it.catalogId }.toSet()
            val legacy=runCatching { ArenaSkillTreeRules.autoAllocateWithPreset(c,level.coerceAtMost(100),owned,17800000L+seed*100+preset.ordinal,preset) }
            error("Envelope rejected $c L$level seed=$seed preset=$preset; allocation=${legacy.exceptionOrNull()} spent=${legacy.getOrNull()?.let(ArenaSkillTreeRules::spentPoints)}")
        }
        return envelope.combat.withIdentityOpponentRules(preset)
    }
    @Test fun `new stat and skill rules are balanced across legal level and preset matrix`() {
        val levels=System.getenv("IDENTITY_BALANCE_LEVELS")?.split(',')?.map(String::toInt) ?: listOf(10,14,20,25,30,50,100)
        val samples=System.getenv("IDENTITY_BALANCE_SEEDS")?.toInt() ?: 128
        println("IDENTITY_COHORT,seeds=$samples,offset=${System.getenv("IDENTITY_BALANCE_OFFSET") ?: "0"}")
        val failures=mutableListOf<String>()
        for(level in levels) {
            // The large probe must not retain every fighter's frozen skill maps at once.
            // This bounds test-only memory while preserving the same profiles, seeds and battles.
            val cache=object : LinkedHashMap<Triple<HeroClass,Int,ArenaAutoBuildPreset>,ArenaSupportInput>(256,.75f,true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<HeroClass,Int,ArenaAutoBuildPreset>,ArenaSupportInput>?): Boolean = size>4096
            }
            fun get(c: HeroClass,g: Int,p: ArenaAutoBuildPreset)=cache.getOrPut(Triple(c,g,p)) { profile(c,level,g,p) }
            val counts=HeroClass.entries.associateWith { DoubleArray(2) }
            val mana=HeroClass.entries.associateWith { IntArray(101) }
            val winnerMana=HeroClass.entries.associateWith { IntArray(101) }
            val presets=ArenaAutoBuildPreset.entries.associateWith { DoubleArray(2) }
            val builds=mutableMapOf<Pair<HeroClass,ArenaAutoBuildPreset>,DoubleArray>()
            var turns=0L;var battles=0;var aborted=0
            for((i,c) in HeroClass.entries.withIndex()) for(d in HeroClass.entries.drop(i+1)) {
                val pair=DoubleArray(2)
                for(p in ArenaAutoBuildPreset.entries) for(q in ArenaAutoBuildPreset.entries) repeat(samples) { seed ->
                    val a=get(c,seed,p).let { it.copy(fighter=it.fighter.copy(id="A:$c:$d:${p.stableId}:${q.stableId}:$seed")) }
                    val b=get(d,seed xor 1,q).let { it.copy(fighter=it.fighter.copy(id="B:$c:$d:${p.stableId}:${q.stableId}:$seed")) }
                    val result=ArenaSupportTurnEngine.simulate(a,b,970000L+seed*10000+level*100+p.ordinal*10+q.ordinal,
                        ArenaTurnRules(safetyTurnLimit=200),recordEvents=false)
                    if(result.status!=ArenaRunStatus.COMPLETED) aborted++
                    val win=if(result.winnerId==a.fighter.id) 1.0 else if(result.winnerId==null) .5 else 0.0
                    for((key,value) in listOf(c to win,d to (1-win))) {
                        counts.getValue(key)[0]+=value;counts.getValue(key)[1]++
                        val id=if(key==c) a.fighter.id else b.fighter.id
                        val f=result.fighters.getValue(id)
                        val percent=(100L*f.mpUnits/f.maxMpUnits).toInt().coerceIn(0,100)
                        mana.getValue(key)[percent]++
                        if(result.winnerId==id) winnerMana.getValue(key)[percent]++
                    }
                    for((key,value) in listOf(p to win,q to (1-win))) { presets.getValue(key)[0]+=value;presets.getValue(key)[1]++ }
                    for((key,value) in listOf((c to p) to win,(d to q) to (1-win))) {
                        val tally=builds.getOrPut(key) { DoubleArray(2) };tally[0]+=value;tally[1]++
                    }
                    pair[0]+=win;pair[1]++;battles++;turns+=result.turns
                }
                val rate=pair[0]/pair[1]
                if(rate !in .35.. .65) failures+="L$level $c/$d ${fmt(rate)}"
                println("IDENTITY_PAIR,$level,$c,$d,${fmt(rate)}")
            }
            fun median(hist: IntArray): Int {
                val half=(hist.sum()+1)/2
                var total=0
                return hist.indices.first { total+=hist[it];total>=half }
            }
            for((c,t) in counts) {
                val hist=mana.getValue(c)
                val winHist=winnerMana.getValue(c)
                println("IDENTITY_MP,$level,$c,n=${hist.sum()},median=${median(hist)},winnerMedian=${median(winHist)},mean=${hist.indices.sumOf { it.toDouble()*hist[it] }/hist.sum()}")
                val rate=t[0]/t[1];println("IDENTITY_CLASS,$level,$c,${fmt(rate)}")
                if(rate !in .45.. .55) failures+="L$level $c ${fmt(rate)}"
            }
            for((p,t) in presets) {
                val rate=t[0]/t[1];println("IDENTITY_PRESET,$level,$p,${fmt(rate)}")
                if(rate !in .40.. .60) failures+="L$level $p ${fmt(rate)}"
            }
            for((p,t) in builds) {
                val rate=t[0]/t[1]
                println("IDENTITY_BUILD,$level,${p.first},${p.second},${fmt(rate)}")
                if(rate !in .35.. .65) failures+="L$level $p ${fmt(rate)}"
            }
            println("IDENTITY_TOTAL,$level,$battles,${fmt(turns.toDouble()/battles)},$aborted")
            if(aborted>0) failures+="L$level aborted=$aborted"
        }
        assertTrue(failures.joinToString("\n"),failures.isEmpty())
    }
    private fun fmt(v: Double)=String.format(Locale.ROOT,"%.4f",v)
}
