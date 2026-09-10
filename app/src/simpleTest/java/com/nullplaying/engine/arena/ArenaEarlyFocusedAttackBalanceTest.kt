package com.nullplaying.engine.arena

import com.nullplaying.engine.PublicPlayerBattleDerivation
import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.engine.SkillCatalog
import com.nullplaying.model.HeroClass
import org.junit.Assert.*
import org.junit.Test

/** User report: level 14, power 145, first attack rank 10. Independent legal stat rolls. */
class ArenaEarlyFocusedAttackBalanceTest {
    private val engine = SimpleGameEngine()
    private val cache = mutableMapOf<String, ArenaSupportInput>()

    @Test fun `level fourteen focused opening attacks are compared against all local presets`() {
        val cases = HeroClass.entries.map { it to "focus" } + listOf(
            HeroClass.RANGER to "split", HeroClass.RANGER to "rank9", HeroClass.RANGER to "preset",
            HeroClass.RANGER to "cooldown1",
        )
        for ((heroClass, build) in cases) {
            val total = IntArray(3)
            val byClass = HeroClass.entries.associateWith { IntArray(3) }
            val byPreset = mutableMapOf<String, IntArray>()
            repeat(4) { sample ->
                val player = authored(base(heroClass, sample, ArenaAutoBuildPreset.entries.first(), 145), build)
                    .let { it.copy(fighter = it.fighter.copy(id = "player-$heroClass-$sample")) }
                for (enemyClass in HeroClass.entries) for (preset in ArenaAutoBuildPreset.entries) {
                    val power = listOf(131L, 140L, 150L, 159L)[sample]
                    val opponent = base(enemyClass, sample + 23, preset, power)
                    repeat(6) { seedIndex ->
                        val result = ArenaSupportTurnEngine.simulate(player, opponent,
                            91_014_500L + sample * 100_000L + enemyClass.ordinal * 1000 + preset.ordinal * 10 + seedIndex,
                            recordEvents = false)
                        assertEquals(ArenaRunStatus.COMPLETED, result.status)
                        val outcome = when(result.winnerId) { player.fighter.id -> 0; null -> 2; else -> 1 }
                        total[outcome]++; byClass.getValue(enemyClass)[outcome]++
                        byPreset.getOrPut(preset.name) { IntArray(3) }[outcome]++
                    }
                }
            }
            if (build == "focus") {
                val winRate = total[0].toDouble() / total.sum()
                assertTrue("Focused $heroClass at level 14 / power 145: $winRate", winRate in 0.35..0.65)
                assertEquals(0, total[2])
            }
            println("early-focus class=$heroClass build=$build total=${total.joinToString("/")} rate=${total[0].toDouble()/total.sum()}")
            println("early-focus-opponents class=$heroClass build=$build ${byClass.mapValues { it.value.joinToString("/") }}")
            println("early-focus-presets class=$heroClass build=$build ${byPreset.mapValues { it.value.joinToString("/") }}")
        }
    }

    private fun base(heroClass: HeroClass, sample: Int, preset: ArenaAutoBuildPreset, power: Long): ArenaSupportInput {
        val key = "$heroClass-$sample-$preset-$power"
        return cache.getOrPut(key) {
            val roll = engine.rollStats(881_014L + sample * 203L, heroClass)
            val stats = roll.stats.copy()
            ArenaSyntheticProfileGrowth.grow(engine, stats, heroClass, targetLevel = 14L, identitySeed = roll.nextSeed)
            val derived = requireNotNull(PublicPlayerBattleDerivation.deriveStats(heroClass, 14L, power, stats))
            requireNotNull(ArenaV6OpponentInputFactory.create(key, "Probe", heroClass, 14L, power, derived,
                PublicPlayerBattleDerivation.learnedSkillsForClassAndLevel(heroClass, 14L),
                ArenaCharacterPointRules.budget(14L), sample.toLong(), preset)).combat
        }
    }

    private fun authored(base: ArenaSupportInput, build: String): ArenaSupportInput {
        if (build == "preset") return base
        val ranks = when (build) { "split" -> listOf(7, 7); "rank9" -> listOf(9, 5); else -> listOf(10, 4) }
        val attacks = SkillCatalog.forClass(base.fighter.heroClass).take(2).mapIndexed { index, definition ->
            val attack = ArenaTurnInputAdapter.resolveAttack(ArenaAttackInput(definition.catalogId, definition.name,
                if (definition.unlockLevel == 1) 1 else definition.unlockLevel / 5 + 1, 0, 0, 0),
                base.fighter.heroClass, ranks[index])
            if (build == "cooldown1" && index == 0) attack.copy(arena = requireNotNull(attack.arena).copy(cooldownTurns = 1))
            else attack
        }
        return base.copy(fighter = base.fighter.copy(attacks = attacks), supportIds = emptySet(),
            supportRanks = emptyMap(), resolvedSupports = emptyMap())
    }
}
