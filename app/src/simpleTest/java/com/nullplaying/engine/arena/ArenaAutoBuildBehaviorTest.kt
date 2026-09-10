package com.nullplaying.engine.arena

import com.nullplaying.engine.PublicPlayerBattleDerivation
import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.model.HeroClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Live-engine proof that the ten authored allocations do not collapse to one combat script. */
class ArenaAutoBuildBehaviorTest {
    private val engine = SimpleGameEngine()

    @Test
    fun `release presets produce multiple deterministic action sequences`() {
        val failures = mutableListOf<String>()
        listOf(20, 25, 30).forEach { level ->
            HeroClass.entries.forEach { heroClass ->
                val signatures = ArenaAutoBuildPreset.entries.associateWith { preset ->
                    val actor = fighter(
                        level = level,
                        heroClass = heroClass,
                        preset = preset,
                        // Keep combat identity constant across presets so the signature can only
                        // differ because of its allocated build, not an id-derived tie break.
                        id = "behavior-actor-$level-$heroClass",
                    )
                    val skillIds = actor.fighter.attacks.mapTo(linkedSetOf()) { it.id }
                    val directIds = skillIds + BASIC_ATTACK_ID
                    val scenarios = (0 until SCENARIOS).map { scenario ->
                        val opponent = fighter(
                            level = level,
                            // Exercise all classes: defensive support should not be judged only
                            // against one attack profile that may never call for it.
                            heroClass = HeroClass.entries[(heroClass.ordinal + scenario + 1) % HeroClass.entries.size],
                            preset = ArenaAutoBuildPreset.BALANCED,
                            id = "behavior-target-$level-$heroClass-$scenario",
                            growthSeed = scenario + 1,
                        )
                        val seed = 943_000_000_000L + level * 10_000L +
                            heroClass.ordinal * 100L + scenario
                        val result = ArenaSupportTurnEngine.simulate(
                            actor,
                            opponent,
                            seed,
                            recordEvents = true,
                        )
                        assertEquals(ArenaRunStatus.COMPLETED, result.status)
                        assertEquals(
                            result,
                            ArenaSupportTurnEngine.simulate(
                                actor,
                                opponent,
                                seed,
                                recordEvents = true,
                            ),
                        )
                        result.events.asSequence()
                            .filter {
                                it.type == ArenaSupportEventType.CAST_START &&
                                    it.actorId == actor.fighter.id
                            }
                            .mapNotNull(ArenaSupportEvent::actionId)
                            .take(ACTION_WINDOW)
                            .toList()
                    }
                    val actions = scenarios.flatten()
                    BehaviorSignature(
                        actions = actions,
                        firstDirectAction = scenarios.first().firstOrNull(directIds::contains),
                        usedActions = actions.toSet(),
                        usedDirectSkills = actions.filterTo(linkedSetOf(), skillIds::contains),
                    )
                }
                val distinct = signatures.values.map(BehaviorSignature::actions).toSet().size
                val firstDirect = signatures.mapValues { (_, signature) ->
                    requireNotNull(signature.firstDirectAction)
                }
                val distinctFirstDirect = firstDirect.values.toSet().size
                val firstDirectCounts = firstDirect.values.groupingBy { it }.eachCount()
                val maximumFirstDirectCount = firstDirectCounts.values.maxOrNull() ?: 0
                val usedActions = signatures.values.flatMapTo(linkedSetOf()) { it.usedActions }
                val usedDirectSkills = signatures.values.flatMapTo(linkedSetOf()) {
                    it.usedDirectSkills
                }
                val perPresetActionCoverage = signatures.mapValues { (_, signature) ->
                    signature.usedActions.size
                }
                println(
                    "arena-auto-actions level=$level class=$heroClass distinct=$distinct " +
                        "firstDirectDistinct=$distinctFirstDirect firstDirect=$firstDirect " +
                        "firstDirectCounts=$firstDirectCounts usedActions=$usedActions " +
                        "usedDirectSkills=$usedDirectSkills " +
                        "perPresetActionCoverage=$perPresetActionCoverage",
                )
                if (distinct < MIN_DISTINCT_ACTION_SEQUENCES) {
                    failures += "Lv.$level $heroClass presets produced only $distinct action sequences: " +
                        signatures
                }
                if (distinctFirstDirect < MIN_DISTINCT_FIRST_DIRECT_ACTIONS) {
                    failures += "Lv.$level $heroClass presets produced only $distinctFirstDirect " +
                        "first direct attacks: $firstDirect"
                }
                if (maximumFirstDirectCount > MAX_PRESETS_PER_FIRST_DIRECT_ACTION) {
                    failures += "Lv.$level $heroClass lets one first direct attack dominate " +
                        "$maximumFirstDirectCount/${ArenaAutoBuildPreset.entries.size} presets: " +
                        firstDirectCounts
                }
                if (usedActions.size < MIN_DISTINCT_USED_ACTIONS) {
                    failures += "Lv.$level $heroClass presets used only ${usedActions.size} " +
                        "distinct actions in live combat: $usedActions"
                }
                if (usedDirectSkills.size < MIN_DISTINCT_USED_DIRECT_SKILLS) {
                    failures += "Lv.$level $heroClass presets used only ${usedDirectSkills.size} " +
                        "distinct direct skills in live combat: $usedDirectSkills"
                }
                perPresetActionCoverage.filterValues {
                    it < MIN_DISTINCT_ACTIONS_PER_PRESET
                }.takeIf(Map<ArenaAutoBuildPreset, Int>::isNotEmpty)?.let { sparsePresets ->
                    failures += "Lv.$level $heroClass presets have too little live action coverage " +
                        "(<$MIN_DISTINCT_ACTIONS_PER_PRESET): $sparsePresets"
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun fighter(
        level: Int,
        heroClass: HeroClass,
        preset: ArenaAutoBuildPreset,
        id: String,
        growthSeed: Int = 0,
    ): ArenaSupportInput {
        val roll = engine.rollStats(96_000_000L + growthSeed, heroClass)
        val state = engine.newGame("behavior", heroClass, roll.stats.copy(), roll.nextSeed, 1_000L)
        state.rngState = ArenaSyntheticProfileGrowth.grow(
            engine = engine,
            stats = state.hero.stats,
            heroClass = heroClass,
            targetLevel = level.toLong(),
            identitySeed = state.rngState,
        )
        state.hero.level = level.toLong()
        val power = (1L + (level - 1L) * 5L) * 2L
        val stats = requireNotNull(
            PublicPlayerBattleDerivation.deriveStats(
                heroClass,
                level.toLong(),
                power,
                state.hero.stats,
            ),
        )
        return requireNotNull(
            ArenaV6OpponentInputFactory.create(
                projectionId = id,
                displayName = "behavior",
                heroClass = heroClass,
                level = level.toLong(),
                combatPower = power,
                stats = stats,
                learnedSkills = PublicPlayerBattleDerivation.learnedSkillsForClassAndLevel(
                    heroClass,
                    level.toLong(),
                ),
                arenaLevel = level,
                stableSeed = 96_500_000L + preset.ordinal,
                buildPreset = preset,
            ),
        ).combat
    }

    private data class BehaviorSignature(
        val actions: List<String>,
        val firstDirectAction: String?,
        val usedActions: Set<String>,
        val usedDirectSkills: Set<String>,
    )

    private companion object {
        const val BASIC_ATTACK_ID = "BASIC_ATTACK"
        const val ACTION_WINDOW = 12
        const val SCENARIOS = 6
        const val MIN_DISTINCT_ACTION_SEQUENCES = 6
        const val MIN_DISTINCT_FIRST_DIRECT_ACTIONS = 3
        const val MAX_PRESETS_PER_FIRST_DIRECT_ACTION = 7
        const val MIN_DISTINCT_USED_ACTIONS = 6
        const val MIN_DISTINCT_USED_DIRECT_SKILLS = 4
        const val MIN_DISTINCT_ACTIONS_PER_PRESET = 3
    }
}
