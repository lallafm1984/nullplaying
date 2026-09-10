package com.nullplaying.engine.arena

import com.nullplaying.BuildConfig
import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.engine.SkillCatalog
import com.nullplaying.model.BattleTraitState
import com.nullplaying.model.HeroClass
import com.nullplaying.model.HeroPathState
import com.nullplaying.model.HeroStats
import com.nullplaying.model.LearnedSkill
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Diagnostic laboratory for the NEW, isolated direct-attack + MP arena core.
 * No old ProjectionBattleEngine/factory, support/DoT/CC, live services, or balance acceptance claim.
 *
 * Environment (also accepted as identically named JVM properties):
 * AQ_ARENA_LAB_SAMPLES=2000; AQ_ARENA_LAB_VARIANTS=C0_FIXED,C1_FIXED,C1_LOG;
 * AQ_ARENA_LAB_LEVELS defaults to 10,20,...,200; explicit values may be any integers in 1..200.
 * AQ_ARENA_LAB_MASTERY=1; AQ_ARENA_LAB_SEED_OFFSET=0 (Long, applied to both seed bases).
 * Formula overrides: HEALTH_BASE, HEALTH_SCALE, HEALTH_SHARE, ATTACK_BASE, ATTACK_SCALE,
 * ATTACK_OFFENSE_SHARE, DEFENSE_SCALE, each prefixed with AQ_ARENA_LAB_.
 * Optional TIER_SCALING, MASTERY_SCALING, SAFETY_TURN_LIMIT, EMIT_PAIRS use the same prefix.
 *
 * A seed is one matched initial-roll quality, replayed through each class's isolated level growth.
 * Levels share those trajectories; variants share fighters/seeds; a mirror only swaps positions.
 * Same-class cells use an identical-stat clone with a different semantic ID, not two live players.
 * Raw paired outcomes can be emitted for downstream paired analysis; disabled by default to keep
 * multi-million-execution JUnit XML manageable. Reported intervals cluster by fixture seed and are
 * normal approximations, not simultaneous confidence bands or asserted release thresholds.
 */
class ArenaTurnBalanceLabTest {
    private val game = SimpleGameEngine()
    private val classes = HeroClass.entries

    private enum class Variant(val budget: ArenaAttackBudget, val mpMode: ArenaMpMode) {
        C0_FIXED(ArenaAttackBudget.C0, ArenaMpMode.FIXED_100),
        C1_FIXED(ArenaAttackBudget.C1, ArenaMpMode.FIXED_100),
        C1_LOG(ArenaAttackBudget.C1, ArenaMpMode.GROWTH_LOG),
    }

    private data class Configuration(
        val samples: Int,
        val seedOffset: Long,
        val levels: List<Int>,
        val variants: List<Variant>,
        val mastery: Int,
        val rules: ArenaTurnRules,
        val emitPairs: Boolean,
    )

    private data class GrowthCursor(
        val heroClass: HeroClass,
        val stats: HeroStats,
        val initialStatTotal: Long,
        val identitySeed: Long,
        var continuationSeed: Long,
        var level: Int = 1,
    )

    @Test
    fun levelBandClassMatrixReportsNewCoreOutcomesWithoutAssertingWinRateBalance() {
        assumeTrue("Run this lab only in an isolated QA variant",
            BuildConfig.BUILD_TYPE in setOf("battleQa", "offlineQa"))
        assertFalse(BuildConfig.REMOTE_SERVICES_ENABLED)
        assertEquals("", BuildConfig.SUPABASE_URL)
        assertEquals("", BuildConfig.SUPABASE_PUBLISHABLE_KEY)

        val config = configuration()
        val pairs = classes.indices.flatMap { first ->
            (first until classes.size).map { second -> first to second }
        }
        val identities = config.levels.size.toLong() * pairs.size * config.samples
        val creationSeedBase = Math.addExact(CREATION_SEED, config.seedOffset)
        val battleSeedBase = Math.addExact(BATTLE_SEED, config.seedOffset)
        println("arena-lab-manifest stage=direct_attack_mp_only samples=${config.samples} " +
            "levels=${config.levels.joinToString(",")} variants=${config.variants.joinToString(",")} " +
            "seedOffset=${config.seedOffset} creationSeed=$creationSeedBase battleSeed=$battleSeedBase " +
            "formulaRevision=symmetric_pools_v2 " +
            "mastery=${config.mastery} pairsPerLevel=${pairs.size} crossPairs=15 selfPairs=6 " +
            "initialRollSeeds=${config.samples} classGrowthTrajectories=${classes.size * config.samples} " +
            "pairIdentities=$identities plannedExecutions=${identities * 2 * config.variants.size} " +
            "sameClass=identical_stats_distinct_ids mirror=position_swap_only " +
            "fixtureKind=isolated_level_growth_all_unlocked_attacks noEquipment=true " +
            "supports=false dots=false cc=false recordEvents=false pairRows=${config.emitPairs} " +
            "scoreMeaning=win_plus_half_draw abortsAreDraws=false")

        val cursors = List(config.samples) { sample -> classes.map { heroClass ->
            val roll = game.rollStats(Math.addExact(creationSeedBase, sample.toLong()), heroClass)
            val state = game.newGame("Arena input", heroClass, roll.stats, roll.nextSeed, FIXED_TIME)
            val stats = state.hero.stats.copy()
            GrowthCursor(
                heroClass, stats, stats.values().take(6).sum(),
                identitySeed = state.rngState,
                continuationSeed = state.rngState,
            )
        } }

        // One level's immutable inputs are retained at a time, across all paired variants.
        config.levels.forEach { level ->
            val fixtures = cursors.mapIndexed { sample, owners -> owners.map { cursor ->
                fixture(cursor, level, sample, config.mastery)
            } }
            printInputs(level, fixtures, config)
            config.variants.forEach { variant -> runMatrix(level, variant, fixtures, pairs, config) }
        }
    }

    private fun fixture(cursor: GrowthCursor, level: Int, sample: Int, mastery: Int): ArenaFighterInput {
        cursor.continuationSeed = ArenaSyntheticProfileGrowth.grow(
            engine = game,
            stats = cursor.stats,
            heroClass = cursor.heroClass,
            fromLevel = cursor.level.toLong(),
            targetLevel = level.toLong(),
            identitySeed = cursor.identitySeed,
        )
        cursor.level = level
        assertEquals(cursor.initialStatTotal + 2L * (level - 1), cursor.stats.values().take(6).sum())
        val definitions = SkillCatalog.forClass(cursor.heroClass).filter { it.unlockLevel <= level }
        val state = game.newGame("Arena ${cursor.heroClass.name}", cursor.heroClass,
            cursor.stats.copy(), cursor.continuationSeed, FIXED_TIME).apply {
            hero.level = level.toLong()
            // newGame derives initial HP/MP; restore this trajectory's grown, raw values.
            hero.stats = cursor.stats.copy()
            equipment.clear()
            battleTraits = BattleTraitState(active = emptyList())
            heroPath = HeroPathState()
            skills.clear()
            skills += definitions.mapIndexed { index, definition ->
                LearnedSkill(index + 1, definition.name, definition.unlockLevel.toLong(),
                    definition.description, catalogId = definition.catalogId,
                    usageCount = (mastery - 1L) * LearnedSkill.USES_PER_LEVEL)
            }
        }
        val built = ArenaTurnInputAdapter.fromState(state, "arena-${cursor.heroClass.name}-$sample")
        assertTrue("Valid owned skills were rejected: ${built.rejectedSkills}", built.rejectedSkills.isEmpty())
        assertEquals(definitions.map { it.catalogId }, built.fighter.attacks.map { it.id })
        assertEquals(minOf(20, 1 + level / 5), built.fighter.attacks.size)
        assertEquals(cursor.stats.values().map { it.toDouble() }, built.fighter.stats.values())
        return built.fighter
    }

    private fun printInputs(level: Int, fixtures: List<List<ArenaFighterInput>>, config: Configuration) {
        classes.forEachIndexed { index, heroClass ->
            val owners = fixtures.map { it[index] }
            val means = (0..5).joinToString(",") { stat ->
                number(owners.map { it.stats.values()[stat] }.average())
            }
            println("arena-lab-input level=$level class=$heroClass samples=${config.samples} " +
                "ownedAttacks=${owners.first().attacks.size} mastery=${config.mastery} meanRawStats=$means " +
                "meanRawHp=${number(owners.map { it.stats.rawMaxHealth }.average())} " +
                "meanRawMp=${number(owners.map { it.stats.rawMaxMana }.average())} " +
                "masteryBonusPercent=${owners.first().attacks.first().masteryBonusPercent}")
        }
    }

    private fun runMatrix(
        level: Int,
        variant: Variant,
        fixtures: List<List<ArenaFighterInput>>,
        pairs: List<Pair<Int, Int>>,
        config: Configuration,
    ) {
        val rules = config.rules.copy(budget = variant.budget, mpMode = variant.mpMode)
        val prefix = "variant=$variant level=$level mastery=${config.mastery}"
        printRules(prefix, rules)
        val classTotals = Array(classes.size) { FighterTotals() }
        val asLeft = Array(classes.size) { Outcomes() }
        val asRight = Array(classes.size) { Outcomes() }
        val classSeedScores = Array(classes.size) { DoubleArray(config.samples) }
        val matrix = Array(classes.size) { DoubleArray(classes.size) { Double.NaN } }
        val selfTotals = Array(classes.size) { Outcomes() }
        val allLengths = TurnHistogram(rules.safetyTurnLimit)
        val guards = Guards()
        var totalAborts = 0L
        var mirrorMismatches = 0L
        var rulesVersion: String? = null

        pairs.forEachIndexed { pairIndex, (row, col) ->
            val firstTotals = Outcomes()
            val secondTotals = Outcomes()
            val forwardTotals = Outcomes()
            val mirrorTotals = Outcomes()
            val lengths = TurnHistogram(rules.safetyTurnLimit)
            val pairScores = DoubleArray(config.samples)
            repeat(config.samples) { sample ->
                val first = fixtures[sample][row]
                val second = if (row == col) first.copy(id = "${first.id}-clone") else fixtures[sample][col]
                val seed = Math.addExact(BATTLE_SEED, config.seedOffset) xor (level.toLong() shl 40) xor
                    (pairIndex.toLong() shl 32) xor sample.toLong()
                val forward = ArenaTurnEngine.simulate(first, second, seed, rules, recordEvents = false)
                val mirror = ArenaTurnEngine.simulate(second, first, seed, rules, recordEvents = false)
                rulesVersion = forward.rulesVersion
                guards.inspect(forward, first.id, second.id, rules.safetyTurnLimit, "$prefix pair=$pairIndex sample=$sample")
                guards.inspect(mirror, first.id, second.id, rules.safetyTurnLimit, "$prefix pair=$pairIndex sample=$sample mirror")
                if (forward != mirror) mirrorMismatches++

                forwardTotals.record(forward, first.id)
                mirrorTotals.record(mirror, first.id)
                val score = (score(forward, first.id) + score(mirror, first.id)) / 2.0
                pairScores[sample] = score
                listOf(forward, mirror).forEach { result ->
                    firstTotals.record(result, first.id)
                    secondTotals.record(result, second.id)
                    if (result.status == ArenaRunStatus.ABORTED_SAFETY_LIMIT) totalAborts++ else {
                        lengths.record(result.turns)
                        allLengths.record(result.turns)
                    }
                }
                if (row == col) {
                    selfTotals[row].record(forward, first.id)
                    selfTotals[row].record(mirror, first.id)
                } else {
                    classTotals[row].record(forward, first.id)
                    classTotals[row].record(mirror, first.id)
                    classTotals[col].record(forward, second.id)
                    classTotals[col].record(mirror, second.id)
                    asLeft[row].record(forward, first.id)
                    asRight[row].record(mirror, first.id)
                    asRight[col].record(forward, second.id)
                    asLeft[col].record(mirror, second.id)
                    classSeedScores[row][sample] += score / (classes.size - 1)
                    classSeedScores[col][sample] += (1.0 - score) / (classes.size - 1)
                }
                if (config.emitPairs) println("arena-lab-pair $prefix pair=$pairIndex " +
                    "row=${classes[row]} col=${classes[col]} sample=$sample seed=$seed " +
                    "forward=${outcome(forward, first.id)} mirror=${outcome(mirror, first.id)} " +
                    "turnsForward=${forward.turns} turnsMirror=${mirror.turns}")
            }
            matrix[row][col] = firstTotals.rate()
            if (row != col) matrix[col][row] = secondTotals.rate()
            println("arena-lab-cell $prefix row=${classes[row]} col=${classes[col]} " +
                "pairSeeds=${config.samples} executions=${config.samples * 2L} ${firstTotals.describe()} " +
                "forwardScore=${number(forwardTotals.rate())} mirrorScore=${number(mirrorTotals.rate())} " +
                "signedSidePp=${number(100 * (forwardTotals.rate() - mirrorTotals.rate()))} " +
                "${interval(pairScores)} ${lengths.describe()}")
        }

        println("arena-lab-summary $prefix rulesVersion=$rulesVersion stage=direct_attack_mp_only " +
            "pairSeeds=${pairs.size.toLong() * config.samples} executions=${pairs.size * config.samples * 2L} " +
            "aborted=$totalAborts invalidResults=${guards.invalid} mirrorMismatches=$mirrorMismatches " +
            "${allLengths.describe()} lengthsExcludeAborts=true interval=fixture_seed_normal95")
        classes.forEachIndexed { row, heroClass ->
            val cross = classes.indices.filter { it != row }.map { matrix[row][it] }
            println("arena-lab-matrix $prefix class=$heroClass " +
                "columns=${classes.joinToString(",")} scores=${matrix[row].joinToString(",") { number(it) }}")
            println("arena-lab-class $prefix class=$heroClass opponents=5 " +
                "equalOpponentScore=${number(cross.average())} worstOpponentScore=${number(cross.minOrNull() ?: Double.NaN)} " +
                "asLeftScore=${number(asLeft[row].rate())} asRightScore=${number(asRight[row].rate())} " +
                "signedSidePp=${number(100 * (asLeft[row].rate() - asRight[row].rate()))} " +
                "${interval(classSeedScores[row])} ${classTotals[row].describe()}")
            println("arena-lab-self $prefix class=$heroClass ${selfTotals[row].describe()} " +
                "cohort=identical_stats_distinct_ids excludedFromClassAverage=true")
        }
        guards.examples.forEach { println("arena-lab-invalid $it") }
        // Diagnostic win-rate variation is deliberately NOT an assertion failure.
        assertEquals("Invalid results; see arena-lab-invalid telemetry", 0L, guards.invalid)
        assertEquals("Safety aborts are unfinished simulations, not draws", 0L, totalAborts)
        assertEquals("Position swaps must preserve semantic-ID-based simulation", 0L, mirrorMismatches)
    }

    private class Outcomes {
        var wins = 0L
        var draws = 0L
        var losses = 0L
        var aborted = 0L
        val completed get() = wins + draws + losses
        fun record(result: ArenaTurnResult, id: String) {
            when (outcome(result, id)) {
                "ABORT" -> aborted++
                "DRAW" -> draws++
                "WIN" -> wins++
                else -> losses++
            }
        }
        fun rate() = if (completed == 0L) Double.NaN else (wins + .5 * draws) / completed
        fun describe() = "completed=$completed wins=$wins draws=$draws losses=$losses aborted=$aborted " +
            "scoreRate=${number(rate())} drawRate=${number(ratio(draws, completed))}"
    }

    private class FighterTotals {
        private val outcomes = Outcomes()
        private var exposures = 0L
        private var basics = 0L
        private var skills = 0L
        private var misses = 0L
        private var mpSpent = 0L
        private var damage = 0.0
        private var cancelled = 0L
        private var maxHp = 0.0
        private var maxMp = 0L
        private var endMp = 0L
        private var depleted = 0L
        private var depletionTurnSum = 0L
        private val starts = LongArray(3)
        private val completions = LongArray(3)
        fun record(result: ArenaTurnResult, id: String) {
            outcomes.record(result, id)
            val owner = result.fighters[id] ?: return
            exposures++
            val t = owner.telemetry
            basics += t.basicCompletions
            skills += t.skillCompletions
            misses += t.misses
            mpSpent += t.mpSpent
            damage += t.damageDealt
            cancelled += t.koCancelledCasts
            maxHp += owner.maxHp
            maxMp += owner.maxMp
            endMp += owner.mp
            t.firstMpDepletedTurn?.let { depleted++; depletionTurnSum += it }
            (0..2).forEach { index ->
                starts[index] += t.startsByCast.getOrElse(index) { 0 }
                completions[index] += t.completionsByCast.getOrElse(index) { 0 }
            }
        }
        fun describe(): String {
            val totalCompletions = basics + skills
            return "${outcomes.describe()} exposures=$exposures " +
                "basicCompletions=$basics skillCompletions=$skills misses=$misses " +
                "meanCompletions=${number(ratio(totalCompletions, exposures))} " +
                "skillShare=${number(ratio(skills, totalCompletions))} missShare=${number(ratio(misses, totalCompletions))} " +
                "mpSpent=$mpSpent meanMpSpent=${number(ratio(mpSpent, exposures))} " +
                "damageDealt=${number(damage)} meanDamage=${number(damage / exposures)} " +
                "meanMaxHp=${number(maxHp / exposures)} meanMaxMp=${number(ratio(maxMp, exposures))} " +
                "meanEndMp=${number(ratio(endMp, exposures))} koCancelledCasts=$cancelled " +
                "startsByCast=${starts.joinToString(",")} completionsByCast=${completions.joinToString(",")} " +
                "completionRateByCast=${(0..2).joinToString(",") { number(ratio(completions[it], starts[it])) }} " +
                "exactMpZeroExposures=$depleted exactMpZeroShare=${number(ratio(depleted, exposures))} " +
                "meanFirstMpZeroTurnAmongDepleted=${number(ratio(depletionTurnSum, depleted))}"
        }
    }

    private class TurnHistogram(limit: Int) {
        private val buckets = LongArray(limit + 1)
        private var count = 0L
        private var total = 0L
        fun record(turns: Int) {
            if (turns !in buckets.indices) return
            buckets[turns]++
            count++
            total += turns
        }
        private fun percentile(fraction: Double): String {
            if (count == 0L) return "NA"
            val rank = ceil(count * fraction).toLong().coerceAtLeast(1)
            var cumulative = 0L
            buckets.forEachIndexed { turn, n ->
                cumulative += n
                if (cumulative >= rank) return turn.toString()
            }
            return "NA"
        }
        fun describe() = "lengthSamples=$count meanTurns=${number(ratio(total, count))} " +
            "p50=${percentile(.5)} p90=${percentile(.9)} p99=${percentile(.99)}"
    }

    private class Guards {
        var invalid = 0L
        val examples = ArrayList<String>()
        fun inspect(result: ArenaTurnResult, firstId: String, secondId: String, limit: Int, label: String) {
            val reasons = ArrayList<String>()
            if (result.turns !in 1..limit) reasons += "invalid_turn"
            if (result.fighters.keys != setOf(firstId, secondId)) reasons += "invalid_fighter_ids"
            result.fighters.forEach { (id, fighter) ->
                if (!fighter.maxHp.isFinite() || fighter.maxHp <= 0 || !fighter.hp.isFinite() ||
                    fighter.hp < 0 || fighter.hp > fighter.maxHp + EPSILON) reasons += "invalid_hp:$id"
                if (fighter.maxMp < 0 || fighter.mp !in 0..fighter.maxMp) reasons += "invalid_mp:$id"
                val t = fighter.telemetry
                if (t.mpSpent < 0 || t.mpSpent != fighter.maxMp - fighter.mp) reasons += "mp_ledger:$id"
                if (!t.damageDealt.isFinite() || t.damageDealt < 0 ||
                    t.startsByCast.size != 3 || t.completionsByCast.size != 3) reasons += "invalid_telemetry:$id"
            }
            if (result.status == ArenaRunStatus.COMPLETED) {
                val winner = result.winnerId
                if (winner == null) {
                    if (result.fighters.values.any { it.hp > 0 }) reasons += "nonlethal_draw"
                } else if (winner !in setOf(firstId, secondId) ||
                    (result.fighters[winner]?.hp ?: 0.0) <= 0 ||
                    result.fighters.any { (id, fighter) -> id != winner && fighter.hp > 0 }) {
                    reasons += "invalid_ko_winner"
                }
            } else if (result.winnerId != null) reasons += "abort_has_winner"
            if (reasons.isNotEmpty()) {
                invalid++
                if (examples.size < 8) examples += "$label reasons=${reasons.joinToString(",")}"
            }
        }
    }

    private fun configuration(): Configuration {
        val defaultRules = ArenaTurnRules()
        val defaultFormula = defaultRules.formula
        val levels = setting("LEVELS")?.split(',')?.map { it.trim().toInt() }
            ?: (10..200 step 10).toList()
        require(levels.isNotEmpty() && levels.distinct().size == levels.size &&
            levels.all { it in 1..200 }) { "LEVELS must be unique integers in 1..200" }
        val variants = setting("VARIANTS")?.split(',')?.map { Variant.valueOf(it.trim().uppercase(Locale.ROOT)) }
            ?: Variant.entries.toList()
        require(variants.isNotEmpty() && variants.distinct().size == variants.size)
        val samples = integer("SAMPLES", 2_000)
        val seedOffset = setting("SEED_OFFSET")?.toLong() ?: 0L
        val mastery = integer("MASTERY", 1)
        require(samples in 1..100_000 && mastery in 1..100)
        val formula = defaultFormula.copy(
            healthBase = decimal("HEALTH_BASE", defaultFormula.healthBase),
            healthScale = decimal("HEALTH_SCALE", defaultFormula.healthScale),
            healthSurvivalShare = decimal("HEALTH_SHARE", defaultFormula.healthSurvivalShare),
            attackBase = decimal("ATTACK_BASE", defaultFormula.attackBase),
            attackScale = decimal("ATTACK_SCALE", defaultFormula.attackScale),
            attackOffenseShare = decimal("ATTACK_OFFENSE_SHARE", defaultFormula.attackOffenseShare),
            defenseScale = decimal("DEFENSE_SCALE", defaultFormula.defenseScale),
        )
        return Configuration(samples, seedOffset, levels.sorted(), variants, mastery, defaultRules.copy(
            formula = formula,
            tierScaling = boolean("TIER_SCALING", defaultRules.tierScaling),
            masteryScaling = boolean("MASTERY_SCALING", defaultRules.masteryScaling),
            safetyTurnLimit = integer("SAFETY_TURN_LIMIT", defaultRules.safetyTurnLimit),
        ), boolean("EMIT_PAIRS", false))
    }

    private fun printRules(prefix: String, rules: ArenaTurnRules) {
        val f = rules.formula
        println("arena-lab-rules $prefix budget=${rules.budget} mpMode=${rules.mpMode} " +
            "formulaRevision=symmetric_pools_v2 " +
            "healthBase=${f.healthBase} healthScale=${f.healthScale} healthShare=${f.healthSurvivalShare} " +
            "attackBase=${f.attackBase} attackScale=${f.attackScale} attackOffenseShare=${f.attackOffenseShare} " +
            "defenseScale=${f.defenseScale} tierScaling=${rules.tierScaling} masteryScaling=${rules.masteryScaling} " +
            "tierGain=${rules.tierGain} masteryGain=${rules.masteryGain} hitChance=${rules.hitChance} " +
            "damageVariance=${rules.damageVariance} cooldownTurns=${rules.cooldownTurns} " +
            "initiativeSensitivity=${rules.initiativeSensitivity} initiativeMaxEdge=${rules.initiativeMaxEdge} " +
            "safetyTurnLimit=${rules.safetyTurnLimit} castSchedule=tier_modulo_3 " +
            "damageKind=actual_hp_loss_clipped_overkill castCountsIncludeBasic=true " +
            "mpDepletion=exact_zero_not_insufficient_skill_mp")
    }

    private companion object {
        const val CREATION_SEED = 9_060_000L
        const val BATTLE_SEED = 7_510_000_000L
        const val FIXED_TIME = 1_000L
        const val EPSILON = 1e-7
        fun setting(name: String): String? = System.getenv("AQ_ARENA_LAB_$name")
            ?.takeIf { it.isNotBlank() } ?: System.getProperty("AQ_ARENA_LAB_$name")?.takeIf { it.isNotBlank() }
        fun integer(name: String, fallback: Int) = setting(name)?.toInt() ?: fallback
        fun decimal(name: String, fallback: Double) = setting(name)?.toDouble() ?: fallback
        fun boolean(name: String, fallback: Boolean): Boolean = setting(name)?.let { value ->
            when (value.lowercase(Locale.ROOT)) {
                "true" -> true
                "false" -> false
                else -> error("AQ_ARENA_LAB_$name must be true or false")
            }
        } ?: fallback
        fun number(value: Double) = if (value.isFinite()) "%.6f".format(Locale.ROOT, value) else "NA"
        fun ratio(numerator: Long, denominator: Long) =
            if (denominator == 0L) Double.NaN else numerator.toDouble() / denominator
        fun outcome(result: ArenaTurnResult, id: String) = when {
            result.status == ArenaRunStatus.ABORTED_SAFETY_LIMIT -> "ABORT"
            result.winnerId == null -> "DRAW"
            result.winnerId == id -> "WIN"
            else -> "LOSS"
        }
        fun score(result: ArenaTurnResult, id: String): Double = when (outcome(result, id)) {
            "ABORT" -> Double.NaN
            "DRAW" -> .5
            "WIN" -> 1.0
            else -> 0.0
        }
        fun interval(seedScores: DoubleArray): String {
            if (seedScores.size < 2 || seedScores.any { !it.isFinite() }) return "seedCiLow=NA seedCiHigh=NA"
            val mean = seedScores.average()
            val variance = seedScores.sumOf { (it - mean) * (it - mean) } / (seedScores.size - 1)
            val margin = 1.96 * sqrt(variance / seedScores.size)
            return "seedCiLow=${number((mean - margin).coerceAtLeast(0.0))} " +
                "seedCiHigh=${number((mean + margin).coerceAtMost(1.0))}"
        }
    }
}
