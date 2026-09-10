package com.nullplaying.engine.arena

import com.nullplaying.engine.PublicPlayerBattleDerivation
import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.engine.SkillCatalog
import com.nullplaying.model.HeroClass
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArenaV6CompetencyBalanceGuardrailTest {
    private val engine = SimpleGameEngine()
    private val classes = HeroClass.entries
    private val levels = listOf(10, 20, 25, 30, 40, 50, 100)

    @Test
    fun `same level projection factory uses a deterministic legal competent allocation`() {
        classes.forEach { heroClass ->
            levels.forEach { arenaLevel ->
                val owned = PublicPlayerBattleDerivation
                    .learnedSkillsForClassAndLevel(heroClass, arenaLevel.toLong())
                    .mapTo(linkedSetOf()) { it.catalogId }
                val first = ArenaSkillTreeRules.autoAllocate(heroClass, arenaLevel, owned, seed = 71L)
                val replay = ArenaSkillTreeRules.autoAllocate(heroClass, arenaLevel, owned, seed = 71L)
                assertEquals(first, replay)
                assertEquals(arenaLevel, ArenaSkillTreeRules.spentPoints(first))
                assertTrue(ArenaSkillTreeRules.validate(first, heroClass, arenaLevel, owned))
                val ranks = first.allocations.associate { it.nodeId to it.rank }
                val rootRanks = ArenaSkillTreeCatalog.forClass(heroClass)
                    .filter(ArenaSkillTreeNodeDefinition::isRoot)
                    .map { ranks[it.id] ?: 0 }
                when {
                    arenaLevel in 20..30 -> {
                        // Release checkpoints cover ten authored families. They share all three
                        // competency roots and at least twelve root points without forcing every
                        // family to converge on the former max-rank opening.
                        assertTrue(
                            "$heroClass Arena Lv.$arenaLevel needs all competency roots: $rootRanks",
                            rootRanks.all { it > 0 },
                        )
                        assertTrue(
                            "$heroClass Arena Lv.$arenaLevel needs broad root investment: $rootRanks",
                            rootRanks.sum() >= 12,
                        )
                    }
                    arenaLevel >= 40 -> assertTrue(
                        "$heroClass Arena Lv.$arenaLevel needs two max-rank roots: $rootRanks",
                        rootRanks.count { it == 10 } >= 2,
                    )
                }
                if (arenaLevel == 100) {
                    val supportRanks = first.allocations.filter {
                        ArenaSkillTreeCatalog.find(it.nodeId)?.kind == ArenaSkillNodeKind.SUPPORT
                    }.map { it.rank }
                    assertTrue("path-gate supports must not be maxed before their child attacks", supportRanks.all { it <= 3 })
                }
            }
        }
    }

    @Test
    fun `broader hero attack ownership remains deterministic legal and separate from same level projection`() {
        classes.forEach { heroClass ->
            val fullyOwned = SkillCatalog.forClass(heroClass).mapTo(linkedSetOf()) { it.catalogId }
            levels.forEach { arenaLevel ->
                val first = ArenaSkillTreeRules.autoAllocate(heroClass, arenaLevel, fullyOwned, seed = 83L)
                val replay = ArenaSkillTreeRules.autoAllocate(heroClass, arenaLevel, fullyOwned, seed = 83L)
                assertEquals(first, replay)
                assertEquals(arenaLevel, ArenaSkillTreeRules.spentPoints(first))
                assertTrue(ArenaSkillTreeRules.validate(first, heroClass, arenaLevel, fullyOwned))
            }
        }
    }

    @Test
    fun `partial attack ownership never allocates an unavailable competency root`() {
        classes.forEach { heroClass ->
            val nodes = ArenaSkillTreeCatalog.forClass(heroClass)
            val roots = nodes.filter(ArenaSkillTreeNodeDefinition::isRoot).map { it.id }
            val nonRootAttack = nodes.first {
                it.kind == ArenaSkillNodeKind.ATTACK && !it.isRoot
            }.id
            val ownershipCases = listOf(
                emptySet(),
                setOf(roots.first()),
                roots.take(2).toSet(),
                setOf(nonRootAttack),
            )
            ownershipCases.forEachIndexed { caseIndex, owned ->
                listOf(20, 25, 30, 100).forEach { arenaLevel ->
                    val state = ArenaSkillTreeRules.autoAllocate(
                        heroClass, arenaLevel, owned, seed = 101L + caseIndex,
                    )
                    assertTrue(ArenaSkillTreeRules.validate(state, heroClass, arenaLevel, owned))
                    assertTrue(state.allocations.none { allocation ->
                        val node = ArenaSkillTreeCatalog.find(allocation.nodeId)
                        node?.kind == ArenaSkillNodeKind.ATTACK && allocation.nodeId !in owned
                    })
                    if (owned.isEmpty() || owned == setOf(nonRootAttack)) {
                        assertTrue(state.allocations.isEmpty())
                    }
                }
            }
        }
    }

    @Test
    fun `actual V6 factory allocator and support engine stay inside class and matchup bands`() {
        val growthSeeds = 8
        // Two complete passes across the ten authored presets. The default factory maps these
        // consecutive stable seeds to every family twice while still using each seed as its
        // deterministic within-family tie break.
        val buildSeeds = 20
        val battleSeeds = 3
        val failures = mutableListOf<String>()
        levels.forEach { level ->
            val classTallies = classes.associateWith { Tally() }.toMutableMap()
            val cells = mutableMapOf<Pair<HeroClass, HeroClass>, Tally>()
            classes.forEachIndexed { leftIndex, leftClass ->
                classes.drop(leftIndex).forEach { rightClass ->
                    val cell = Tally()
                    cells[leftClass to rightClass] = cell
                    repeat(growthSeeds) { growthSeed ->
                        repeat(buildSeeds) { buildSeed ->
                            val leftBase = fighter(level, leftClass, growthSeed, buildSeed)
                            val rightBase = fighter(level, rightClass, growthSeed, buildSeed)
                            repeat(battleSeeds) { battleSeed ->
                                repeat(2) { identityAssignment ->
                                    val identity0 = "guard-$level-$leftIndex-${rightClass.ordinal}-$growthSeed-$buildSeed-$battleSeed-0"
                                    val identity1 = "guard-$level-$leftIndex-${rightClass.ordinal}-$growthSeed-$buildSeed-$battleSeed-1"
                                    val leftId = if (identityAssignment == 0) identity0 else identity1
                                    val rightId = if (identityAssignment == 0) identity1 else identity0
                                    val left = leftBase.copy(fighter = leftBase.fighter.copy(id = leftId))
                                    val right = rightBase.copy(fighter = rightBase.fighter.copy(id = rightId))
                                    val seed = 99_000_000_000L + level * 10_000_000L +
                                        leftIndex * 1_000_000L + rightClass.ordinal * 100_000L +
                                        growthSeed * 3_000L + buildSeed * 10L + battleSeed
                                    val forward = ArenaSupportTurnEngine.simulate(left, right, seed, recordEvents = false)
                                    val mirror = ArenaSupportTurnEngine.simulate(right, left, seed, recordEvents = false)
                                    assertEquals(ArenaRunStatus.COMPLETED, forward.status)
                                    assertEquals(forward, mirror)
                                    cell.record(forward, leftId)
                                    if (leftClass != rightClass) {
                                        classTallies.getValue(leftClass).record(forward, leftId)
                                        classTallies.getValue(rightClass).record(forward, rightId)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            val matchupRates = cells.filterKeys { it.first != it.second }
                .mapValues { it.value.rate() }
            val classRates = classTallies.mapValues { it.value.rate() }
            println("arena-v6-competency level=$level class=${classRates.mapValues { fmt(it.value) }}")
            println("arena-v6-competency level=$level matchup=${matchupRates.mapValues { fmt(it.value) }}")
            matchupRates.forEach { (matchup, rate) ->
                if (rate !in 0.35..0.65) failures +=
                    "level=$level matchup=$matchup rate=${fmt(rate)} outside 0.35..0.65"
            }
            classRates.forEach { (heroClass, rate) ->
                if (rate !in 0.45..0.55) failures +=
                    "level=$level class=$heroClass rate=${fmt(rate)} outside 0.45..0.55"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun fighter(level: Int, heroClass: HeroClass, growthSeed: Int, buildSeed: Int): ArenaSupportInput {
        val roll = engine.rollStats(74_000_000L + growthSeed, heroClass)
        val state = engine.newGame("guard", heroClass, roll.stats.copy(), roll.nextSeed, 1_000L)
        state.rngState = ArenaSyntheticProfileGrowth.grow(
            engine = engine,
            stats = state.hero.stats,
            heroClass = heroClass,
            targetLevel = level.toLong(),
            identitySeed = state.rngState,
        )
        state.hero.level = level.toLong()
        val power = (1L + (level - 1L) * 5L) * 2L
        val stats = requireNotNull(PublicPlayerBattleDerivation.deriveStats(
            heroClass, level.toLong(), power, state.hero.stats,
        ))
        return requireNotNull(ArenaV6OpponentInputFactory.create(
            projectionId = "base-$heroClass-$level-$growthSeed-$buildSeed",
            displayName = "guard",
            heroClass = heroClass,
            level = level.toLong(),
            combatPower = power,
            stats = stats,
            learnedSkills = PublicPlayerBattleDerivation.learnedSkillsForClassAndLevel(heroClass, level.toLong()),
            arenaLevel = level,
            stableSeed = 84_000_000L + buildSeed,
        )).combat
    }

    private data class Tally(var wins: Int = 0, var losses: Int = 0, var draws: Int = 0) {
        private val count: Int get() = wins + losses + draws
        fun rate(): Double = (wins + draws * 0.5) / count
        fun record(result: ArenaSupportResult, fighterId: String) {
            when (result.winnerId) {
                fighterId -> wins++
                null -> draws++
                else -> losses++
            }
        }
    }

    private fun fmt(value: Double): String = String.format(Locale.ROOT, "%.4f", value)
}
