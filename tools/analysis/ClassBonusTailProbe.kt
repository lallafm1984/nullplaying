package com.nullplaying.engine

import com.nullplaying.model.*
import java.io.File
import java.util.Random

/** IID event-seed sensitivity, NOT full combat trajectories or user data. */
object ClassBonusTailProbe {
    @JvmStatic fun main(args: Array<String>) {
        val engine = SimpleGameEngine(OfflineAdventureConfig(480, 12))
        val samples = 10_000
        val growthCount = 99 + 129 // Lv1→100 plus observed completed tales.
        File(args[0]).bufferedWriter().use { w ->
            w.appendLine("class,seed_index,hp,mp,str,con,dex,int,wis,cha")
            for (cls in HeroClass.entries) for (i in 0 until samples) {
                val seed = Random(350831_100_000L + i * 1009L).nextLong()
                val roll = engine.rollStats(seed, cls)
                val stats = roll.stats
                // A contiguous 4-draw growth-only loop aliases StableRng's low
                // bits and is NOT the game's interleaved combat RNG cadence.
                // Independent event seeds preserve within-event engine logic
                // while explicitly assuming independence between growth events.
                val events = Random(seed xor 721739L)
                repeat(growthCount) { engine.applyClassGuidedGrowth(stats, cls, events.nextLong()) }
                w.appendLine(listOf(cls.name,i,stats.maxHealth,stats.maxMana,stats.strength,
                    stats.constitution,stats.dexterity,stats.intelligence,stats.wisdom,stats.charisma).joinToString(","))
            }
        }
        // Boundary checks execute the exact bonus hooks used in combat replays.
        var checks = 0
        for (cls in HeroClass.entries) {
            val extra = if (cls == HeroClass.MAGE) 15.0 else 10.0
            BalanceSimulationHooks.configure("2,6000,$extra,6000,0.2,150,0.2,150,1")
            val game = engine.newGame("LOCAL_BOUNDARY",cls,engine.rollStats(350831L,cls).stats,7812L,0L)
            var previousProbability = 0.0
            var previousEncounter = 5_000L
            for (value in listOf(0L,1L,149L,150L,5_999L,6_000L,6_001L,Long.MAX_VALUE)) {
                game.hero.stats.maxMana=value; game.hero.stats.dexterity=value; game.hero.stats.charisma=value
                val probability=BalanceSimulationHooks.rawProbability(game,20)
                val encounter=BalanceSimulationHooks.encounterMillis(game,5_000L)
                check(probability in .20..((20+extra)/100))
                checks++
                check(probability >= previousProbability)
                checks++
                check(encounter in 4_000L..5_000L && encounter <= previousEncounter)
                checks++
                for (price in listOf(0L,1L,100L,1_000_000L,Long.MAX_VALUE)) {
                    val sale=BalanceSimulationHooks.sale(game,price)
                    check(sale >= price)
                    checks++
                    if (price <= 1_000_000L) { check(sale <= price*1.2); checks++ }
                }
                if (value >= 6_000L) { check(kotlin.math.abs(probability-(20+extra)/100)<1e-12); checks++ }
                if (value >= 150L) { check(encounter==4_000L); checks++ }
                previousProbability=probability; previousEncounter=encounter
            }
        }
        println("IID growth-event seed samples: ${samples*6}; growth events per sample: $growthCount; boundary checks: $checks PASS")
    }
}
