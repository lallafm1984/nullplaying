package com.nullplaying.engine

import com.nullplaying.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Read-only product diagnosis. No win-rate bands here are asserted as a release contract. */
class BasePvpClassDiagnosticTest {
    private val engine = SimpleGameEngine()
    private val classes = HeroClass.entries
    private val levels = listOf(1, 5, 20, 50, 100)
    private val samples = 2_000
    private val rules = BattleRules(growthReferencePower = 10_000)

    private enum class Layer { ACTUAL_PLAYER, GROWTH_STANDARD_STRIKE, EQUAL_STANDARD_STRIKE }

    @Test
    fun levelFiftyInputCloneDiagnostic() {
        runClones(1, listOf("UNCHANGED_CONTROL", "FINISHER_OFF", "ASSAULT", "GUARD"))
    }

    @Test
    fun highMasterySensitivityDiagnostic() {
        listOf(30, 100).forEach { mastery ->
            val fixtures = List(samples) { sample -> classes.map { actualPlayer(it, 100, sample, mastery) } }
            fixtures.flatten().forEach { owner ->
                val normalized = ProjectionBattleEngine.normalizeProjection(owner, rules)
                assertEquals(BattleTraitCombatProfile(), normalized.combatProfile)
                assertEquals(0, normalized.heroPathBattleSnapshot.treeVersion)
                assertTrue(normalized.heroPathBattleSnapshot.nodes.isEmpty())
                assertTrue(normalized.activeTraitIds.isEmpty() && normalized.heroPathTraitRanks.isEmpty())
                assertTrue(normalized.skills.all { it.masteryLevel == mastery })
            }
            runMatrix(Layer.ACTUAL_PLAYER, 100, fixtures, mastery)
            runClones(mastery, listOf("UNCHANGED_CONTROL", "FINISHER_OFF"))
        }
    }

    private fun runClones(mastery: Int, variants: List<String>) {
        classes.forEachIndexed { classIndex, heroClass ->
            val fixtures = List(samples) { actualPlayer(heroClass, 50, it, mastery) }
            variants.forEach { variant ->
                val result = Cell()
                val candidateTelemetry = Telemetry()
                val balancedTelemetry = Telemetry()
                repeat(samples) { sample ->
                    val original = fixtures[sample]
                    val candidate = when (variant) {
                        "FINISHER_OFF" -> original.copy(skills = original.skills.map { it.copy(finisherEligible = false) })
                        "ASSAULT" -> original.copy(guidance = BattleGuidance.ASSAULT)
                        "GUARD" -> original.copy(guidance = BattleGuidance.GUARD)
                        else -> original
                    }.copy(projectionId = "clone-candidate-$classIndex-$sample")
                    val balanced = original.copy(projectionId = "clone-balanced-$classIndex-$sample")
                    // Shared IDs, seed and original skill selection across all four variants.
                    val request = UserInitiatedBattleRequest(
                        battleId = "base-clone-L50-C$classIndex-S$sample", serverSeed = 414_000_000L + classIndex * 10_000L + sample,
                        requestedAtMillis = 1_000, user = candidate, opponent = balanced, rules = rules,
                    )
                    val forward = ProjectionBattleEngine.simulate(request)
                    val mirror = ProjectionBattleEngine.simulate(request.copy(user = balanced, opponent = candidate))
                    result.record(forward.outcome, mirror.outcome.opposite())
                    candidateTelemetry.record(forward, true)
                    candidateTelemetry.record(mirror, false)
                    balancedTelemetry.record(forward, false)
                    balancedTelemetry.record(mirror, true)
                }
                println("base-pvp-clone class=$heroClass variant=$variant level=50 mastery=$mastery pairedSeeds=$samples battles=${samples * 2} " +
                    "candidateWin=${result.rate()} forward=${result.forwardRate()} mirror=${result.mirrorRate()} sideDiff=${result.sideDifference()}")
                println("base-pvp-clone-actions class=$heroClass variant=$variant mastery=$mastery owner=CANDIDATE ${candidateTelemetry.describe()}")
                println("base-pvp-clone-actions class=$heroClass variant=$variant mastery=$mastery owner=BALANCED ${balancedTelemetry.describe()}")
            }
        }
    }

    @Test
    fun `base six class diagnosis excludes every talent and preserves real player skill conversion`() {
        // Precompute matched-quality characters once, without battle RNG or opponent data.
        val fixtures = levels.associateWith { level ->
            List(samples) { sample -> classes.map { heroClass -> actualPlayer(heroClass, level, sample) } }
        }
        fixtures.forEach { (level, samplesAtLevel) ->
            classes.forEachIndexed { index, heroClass ->
                val snapshots = samplesAtLevel.map { it[index] }
                val normalized = snapshots.map { ProjectionBattleEngine.normalizeProjection(it, rules) }
                normalized.forEach { owner ->
                    assertEquals(BattleTraitCombatProfile(), owner.combatProfile)
                    assertTrue(owner.activeTraitIds.isEmpty())
                    assertTrue(owner.heroPathTraitRanks.isEmpty())
                    assertEquals(0, owner.heroPathBattleSnapshot.treeVersion)
                    assertTrue(owner.heroPathBattleSnapshot.nodes.isEmpty())
                    assertTrue(owner.equipment.isEmpty())
                    assertTrue(owner.skills.all { it.masteryLevel == 1 && it.cooldownRounds == 2 })
                    assertTrue(owner.skills.all { it.kind in setOf(BattleSkillKind.STRIKE, BattleSkillKind.ARCANE) })
                }
                println("base-pvp-fixture level=$level class=$heroClass skills=${normalized.first().skills.size} " +
                    "meanBuild=${(0..5).joinToString(",") { stat -> snapshots.map { it.build.values()[stat] }.average().toString() }} " +
                    "meanNormalized=${(0..5).joinToString(",") { stat -> normalized.map { it.stats.values()[stat] }.average().toString() }} " +
                    "kindCounts=${BattleSkillKind.entries.joinToString(",") { kind -> "$kind:${normalized.sumOf { owner -> owner.skills.count { it.kind == kind } }}" }} " +
                    "meanSkillPower=${normalized.flatMap { it.skills }.map { it.powerBasisPoints }.average()} " +
                    "minSkillPower=${normalized.flatMap { it.skills }.minOf { it.powerBasisPoints }} maxSkillPower=${normalized.flatMap { it.skills }.maxOf { it.powerBasisPoints }}")
            }
        }

        // Actual-player evidence first; synthetic controls are labeled and never mixed into it.
        Layer.entries.forEach { layer ->
            levels.forEach { level ->
                runMatrix(layer, level, fixtures.getValue(level))
            }
        }
    }

    private fun runMatrix(layer: Layer, level: Int, fixtures: List<List<BattleProjectionSnapshot>>, mastery: Int = 1) {
                val cells = Array(6) { Array(6) { Cell() } }
                val telemetry = Array(6) { Telemetry() }
                val lengths = ArrayList<Int>(36 * samples * 2)
                var firstRoundKo = 0
                var endedWithBothAlive = 0
                var simultaneousRawKnockouts = 0
                var rawKnockoutFinalWins = 0
                classes.forEachIndexed { row, _ ->
                    classes.forEachIndexed { col, _ ->
                        repeat(samples) { sample ->
                            val source = fixtures[sample]
                            val user = applyLayer(source[row], layer).copy(projectionId = "base-owner-$row-$sample")
                            val opponent = applyLayer(source[col], layer).copy(projectionId = "base-rival-$col-$sample")
                            // Identical IDs/seeds between A/B/C make controls paired. Every ordered
                            // cell and sample has a unique battle ID; mirror swaps only projections.
                            val request = UserInitiatedBattleRequest(
                                battleId = "base-pvp-L$level-cell${row * 6 + col}-seed$sample",
                                serverSeed = 91_000_000L + level * 1_000_000L + (row * 6 + col) * 10_000L + sample,
                                requestedAtMillis = 1_000,
                                user = user, opponent = opponent, rules = rules,
                            )
                            val forward = ProjectionBattleEngine.simulate(request)
                            val mirror = ProjectionBattleEngine.simulate(request.copy(user = opponent, opponent = user))
                            cells[row][col].record(forward.outcome, mirror.outcome.opposite())
                            listOf(forward, mirror).forEach { result ->
                                lengths += result.rounds.size
                                val last = result.rounds.last()
                                if (last.userHpAfter > 0 && last.opponentHpAfter > 0) endedWithBothAlive += 1
                                if (result.rounds.first().let { it.userHpAfter == 0 || it.opponentHpAfter == 0 }) firstRoundKo += 1
                                result.rounds.forEach { round ->
                                    val rawUser = round.userHpBefore - round.opponentAction.damage - round.userAction.selfDamage + round.userAction.healing
                                    val rawOpponent = round.opponentHpBefore - round.userAction.damage - round.opponentAction.selfDamage + round.opponentAction.healing
                                    if (rawUser <= 0 && rawOpponent <= 0) simultaneousRawKnockouts += 1
                                    if (round === last && ((result.outcome == BattleOutcome.USER_WIN && rawUser <= 0) ||
                                            (result.outcome == BattleOutcome.USER_LOSS && rawOpponent <= 0))) rawKnockoutFinalWins += 1
                                }
                                assertTrue("all talent traces must remain absent", result.rounds.all {
                                    it.talentEffects.isEmpty() && it.userAction.talentEffects.isEmpty() && it.opponentAction.talentEffects.isEmpty()
                                })
                            }
                            if (row != col) {
                                telemetry[row].record(forward, userSide = true)
                                telemetry[row].record(mirror, userSide = false)
                                telemetry[col].record(forward, userSide = false)
                                telemetry[col].record(mirror, userSide = true)
                            }
                        }
                    }
                }
                val sorted = lengths.sorted()
                val prefix = "layer=$layer level=$level mastery=$mastery"
                println("base-pvp-summary $prefix seedsPerCell=$samples matrixBattles=${lengths.size} uniqueBattleIdentities=${36 * samples} " +
                    "median=${sorted[sorted.size / 2]} p90=${sorted[(sorted.size * .9).toInt()]} p99=${sorted[(sorted.size * .99).toInt()]} " +
                    "firstRoundKo=$firstRoundKo endedWithBothAlive=$endedWithBothAlive " +
                    "simultaneousRawKnockouts=$simultaneousRawKnockouts rawKnockoutFinalWins=$rawKnockoutFinalWins " +
                    "maxSideDifference=${cells.flatMap { it.toList() }.maxOf { it.sideDifference() }}")
                classes.forEachIndexed { row, heroClass ->
                    val opponentRates = classes.indices.filter { it != row }.map { col ->
                        // Equal-weight both ordered directions, not only the class as first owner.
                        (cells[row][col].rate() + 1.0 - cells[col][row].rate()) / 2.0
                    }
                    println("base-pvp-matrix $prefix class=$heroClass rates=${cells[row].joinToString(",") { it.rate().toString() }}")
                    println("base-pvp-class $prefix class=$heroClass win=${opponentRates.average()} " +
                        "self=${cells[row][row].rate()} meanSideDiff=${cells[row].map { it.sideDifference() }.average()} " +
                        "${telemetry[row].describe()}")
                }
                cells.forEachIndexed { row, columns -> columns.forEachIndexed { col, cell ->
                    println("base-pvp-cell $prefix row=${classes[row]} col=${classes[col]} " +
                        "win=${cell.rate()} sideDiff=${cell.sideDifference()} forward=${cell.forwardRate()} mirrored=${cell.mirrorRate()}")
                } }
    }

    private fun actualPlayer(heroClass: HeroClass, level: Int, sample: Int, mastery: Int = 1): BattleProjectionSnapshot {
        val roll = engine.rollStats(770_000L + sample, heroClass)
        val stats = engine.initialStatsForClass(roll.stats, heroClass)
        var growthSeed = roll.nextSeed
        repeat(level - 1) { growthSeed = engine.applyClassGuidedGrowth(stats, heroClass, growthSeed) }
        val state = engine.newGame("Base $heroClass", heroClass, stats, growthSeed, 1_000).apply {
            hero.level = level.toLong()
            hero.stats = stats
            rankingCharacterId = "base-fixture-${heroClass.name}-$sample"
            equipment.clear()
            battleTraits = BattleTraitState(active = emptyList())
            heroPath = HeroPathState()
            skills.clear()
            skills += SkillCatalog.forClass(heroClass).filter { it.unlockLevel <= level }.mapIndexed { index, definition ->
                LearnedSkill(index + 1, definition.name, definition.unlockLevel.toLong(), definition.description,
                    catalogId = definition.catalogId, usageCount = (mastery - 1L) * LearnedSkill.USES_PER_LEVEL)
            }
        }
        // Reuse the actual player's production projection/mapping/4-slot random selection.
        // The generated synthetic opponent is intentionally never simulated.
        return BattleQaMatchFactory.createMatch(
            state = state, combatPower = 10_000, guidance = BattleGuidance.BALANCED,
            userScore = 1_000, matchSequence = sample,
            battleId = "base-fixture-L$level-S$sample", serverSeed = 991_000L + sample,
            requestedAtMillis = 1_000,
        ).request.user.copy(
            equipment = emptyList(), activeTraitIds = emptyList(), heroPathTraitRanks = emptyMap(),
            heroPathBattleSnapshot = HeroPathBattleSnapshot(), heroPathCatalogVersion = 0, heroPathRevision = 0,
        )
    }

    private fun applyLayer(actual: BattleProjectionSnapshot, layer: Layer): BattleProjectionSnapshot {
        if (layer == Layer.ACTUAL_PLAYER) return actual
        val standardSkills = List(actual.skills.size) { index -> BattleSkillSnapshot(
            skillId = "standard-strike-$index", displayName = "Standard Strike $index",
            kind = BattleSkillKind.STRIKE, powerBasisPoints = 10_000, cooldownRounds = 2,
            masteryLevel = 1, finisherEligible = true,
        ) }
        return actual.copy(
            build = if (layer == Layer.EQUAL_STANDARD_STRIKE) BattleBuildStats(20, 20, 20, 20, 20, 20) else actual.build,
            skills = standardSkills,
        )
    }

    private class Cell {
        private var forward = 0.0
        private var mirror = 0.0
        private var samples = 0
        fun record(first: BattleOutcome, second: BattleOutcome) {
            forward += first.score()
            mirror += second.score()
            samples += 1
        }
        fun rate() = (forward + mirror) / (samples * 2)
        fun forwardRate() = forward / samples
        fun mirrorRate() = mirror / samples
        fun sideDifference() = kotlin.math.abs(forwardRate() - mirrorRate())
    }

    private class Telemetry {
        private var battles = 0L
        private var rounds = 0L
        private var skills = 0L
        private var basics = 0L
        private var guards = 0L
        private var healing = 0L
        private var effectiveHealing = 0L
        private var outgoingDamage = 0L
        private var selfCost = 0L
        private var critical = 0L
        private var powerAttacks = 0L
        private var finishers = 0L
        private var evades = 0L
        private var blocks = 0L
        private var misses = 0L
        private var zeroDamageActions = 0L
        private var losses = 0L
        private var noIncomingFinalLoss = 0L
        private var finalSelfCostLoss = 0L
        private var ownMissFinalLoss = 0L
        private var noIncomingAndNoSelfCostLoss = 0L

        fun record(battle: ProjectionBattleResult, userSide: Boolean) {
            battles += 1
            val lost = if (userSide) battle.outcome == BattleOutcome.USER_LOSS else battle.outcome == BattleOutcome.USER_WIN
            if (lost) losses += 1
            battle.rounds.forEach { round ->
                val action = if (userSide) round.userAction else round.opponentAction
                val incoming = if (userSide) round.opponentAction else round.userAction
                val hpBefore = if (userSide) round.userHpBefore else round.opponentHpBefore
                val hpAfter = if (userSide) round.userHpAfter else round.opponentHpAfter
                rounds += 1
                when (action.kind) {
                    BattleActionKind.SKILL -> skills += 1
                    BattleActionKind.BASIC_ATTACK -> basics += 1
                    BattleActionKind.GUARD -> guards += 1
                }
                healing += action.healing
                // Actual restoration after max-HP cap, not a promise to survive a simultaneous hit.
                val maxHp = if (userSide) battle.user.maxHp else battle.opponent.maxHp
                effectiveHealing += action.healing.coerceAtMost((maxHp - hpBefore + incoming.damage + action.selfDamage).coerceAtLeast(0))
                outgoingDamage += action.damage
                selfCost += action.selfDamage
                if (action.critical) critical += 1
                if (action.powerAttack) powerAttacks += 1
                if (action.finisher) finishers += 1
                if (action.resolution == BattleActionResolution.EVADED) evades += 1
                if (action.resolution == BattleActionResolution.BLOCKED) blocks += 1
                if (action.resolution == BattleActionResolution.MISSED) misses += 1
                if (action.damage == 0) zeroDamageActions += 1
                if (lost && round === battle.rounds.last()) {
                    if (incoming.damage == 0) {
                        noIncomingFinalLoss += 1
                        if (action.selfDamage == 0) noIncomingAndNoSelfCostLoss += 1
                    }
                    if (hpAfter == 0 && hpBefore - incoming.damage + action.healing > 0 && action.selfDamage > 0) finalSelfCostLoss += 1
                    if (action.resolution in setOf(BattleActionResolution.MISSED, BattleActionResolution.EVADED)) ownMissFinalLoss += 1
                }
            }
        }

        private fun rate(value: Long) = if (rounds == 0L) 0.0 else value.toDouble() / rounds
        fun describe() = "exposures=$battles meanActions=${rounds.toDouble() / battles} skillShare=${rate(skills)} basicShare=${rate(basics)} guardShare=${rate(guards)} " +
            "meanDamage=${outgoingDamage.toDouble() / battles} meanHealing=${healing.toDouble() / battles} meanEffectiveHealing=${effectiveHealing.toDouble() / battles} " +
            "meanSelfCost=${selfCost.toDouble() / battles} criticalShare=${rate(critical)} strongShare=${rate(powerAttacks)} finisherShare=${rate(finishers)} " +
            "attackEvadedShare=${rate(evades)} attackBlockedShare=${rate(blocks)} attackMissedShare=${rate(misses)} zeroDamageActionShare=${rate(zeroDamageActions)} " +
            "losses=$losses noIncomingFinalLoss=$noIncomingFinalLoss finalSelfCostNecessaryLoss=$finalSelfCostLoss " +
            "ownMissFinalLoss=$ownMissFinalLoss noIncomingAndNoSelfCostLoss=$noIncomingAndNoSelfCostLoss"
    }

    private companion object {
        fun BattleOutcome.opposite(): BattleOutcome = when (this) {
            BattleOutcome.USER_WIN -> BattleOutcome.USER_LOSS
            BattleOutcome.USER_LOSS -> BattleOutcome.USER_WIN
            BattleOutcome.DRAW -> BattleOutcome.DRAW
        }
        fun BattleOutcome.score() = when (this) {
            BattleOutcome.USER_WIN -> 1.0
            BattleOutcome.USER_LOSS -> 0.0
            BattleOutcome.DRAW -> .5
        }
    }
}
