package com.nullplaying.engine

import com.nullplaying.model.BATTLE_PLACEMENT_BATTLES
import com.nullplaying.model.BATTLE_RATING_K
import com.nullplaying.model.BATTLE_RULES_VERSION
import com.nullplaying.model.BATTLE_TICKET_CAPACITY
import com.nullplaying.model.BATTLE_UNLIMITED_ROUNDS
import com.nullplaying.model.BattleActionKind
import com.nullplaying.model.BattleActionResolution
import com.nullplaying.model.BattleBuildStats
import com.nullplaying.model.BattleCondition
import com.nullplaying.model.BattleGuidance
import com.nullplaying.model.BattleHeroClass
import com.nullplaying.model.BattleOutcome
import com.nullplaying.model.BattleProjectionSnapshot
import com.nullplaying.model.BattleRewardPolicy
import com.nullplaying.model.BattleRules
import com.nullplaying.model.BattleSeasonStanding
import com.nullplaying.model.BattleSide
import com.nullplaying.model.BattleSkillKind
import com.nullplaying.model.BattleSkillSnapshot
import com.nullplaying.model.BattleTicketState
import com.nullplaying.model.ProjectionBattleResult
import com.nullplaying.model.UserInitiatedBattleRequest
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionBattleEngineTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `same server seed and snapshots reproduce an identical terminating battle`() {
        val request = request(seed = 91_827_364L)

        val first = ProjectionBattleEngine.simulate(request)
        val second = ProjectionBattleEngine.simulate(request)

        assertEquals(first, second)
        assertEquals(BATTLE_RULES_VERSION, first.rulesVersion)
        assertEquals(0, BATTLE_UNLIMITED_ROUNDS)
        assertEquals(30, ProjectionBattleEngine.FATIGUE_START_ROUND)
        assertEquals(json.encodeToString(first), json.encodeToString(second))
        assertTrue(first.rounds.isNotEmpty())
        assertTrue(first.rounds.last().userHpAfter == 0 || first.rounds.last().opponentHpAfter == 0)
        assertEquals(first, json.decodeFromString<ProjectionBattleResult>(json.encodeToString(first)))

        val transcripts = (1L..24L)
            .map { ProjectionBattleEngine.simulate(request.copy(serverSeed = it)).rounds }
            .toSet()
        assertTrue("server seeds must produce more than one transcript", transcripts.size > 1)
    }

    @Test
    fun `both actions use the same pre-round health and are applied simultaneously`() {
        val mirrored = request(
            user = projection("user", guidance = BattleGuidance.ASSAULT, skills = emptyList()),
            opponent = projection("opponent", guidance = BattleGuidance.ASSAULT, skills = emptyList()),
        )
        val result = (1L..1_000L)
            .asSequence()
            .map { ProjectionBattleEngine.simulate(mirrored.copy(serverSeed = it)) }
            .first {
                val round = it.rounds.first()
                round.userAction.kind == BattleActionKind.BASIC_ATTACK &&
                    round.opponentAction.kind == BattleActionKind.BASIC_ATTACK
            }
        val first = result.rounds.first()

        assertEquals(result.user.maxHp, first.userHpBefore)
        assertEquals(result.opponent.maxHp, first.opponentHpBefore)
        assertTrue(first.userHpAfter < first.userHpBefore)
        assertTrue(first.opponentHpAfter < first.opponentHpBefore)
    }

    @Test
    fun `morale starts from composure and charisma then moves inside safe bounds`() {
        val inspired = projection(
            id = "inspired",
            condition = BattleCondition.BEST,
            build = BattleBuildStats(10, 10, 10, 5, 15, 50),
        ).copy(activeTraitIds = listOf("TRAIT_041", "TRAIT_048"))
        val shaken = projection(
            id = "shaken",
            condition = BattleCondition.WORST,
            build = BattleBuildStats(25, 25, 25, 15, 5, 5),
        ).copy(activeTraitIds = listOf("TRAIT_031", "TRAIT_032"))

        val result = ProjectionBattleEngine.simulate(request(seed = 9_311L, user = inspired, opponent = shaken))
        val first = result.rounds.first()

        assertTrue(first.userMoraleBefore > first.opponentMoraleBefore)
        assertTrue(
            result.rounds.all { round ->
                listOf(
                    round.userMoraleBefore,
                    round.opponentMoraleBefore,
                    round.userMoraleAfter,
                    round.opponentMoraleAfter,
                ).all { it in 0..100 }
            },
        )
        assertTrue(
            result.rounds.any { round ->
                round.userMoraleBefore != round.userMoraleAfter ||
                    round.opponentMoraleBefore != round.opponentMoraleAfter
            },
        )
    }

    @Test
    fun `growth resonance is monotone bounded and level is never counted twice`() {
        val powers = listOf(0L, 1L, 1_000L, 10_000L, 100_000L, 1_000_000L, Long.MAX_VALUE)
        val budgets = powers.map {
            ProjectionBattleEngine.growthResonanceBudget(it, growthReferencePower = 10_000L)
        }

        assertEquals(budgets.sorted(), budgets)
        assertTrue(budgets.all { it in 100..108 })
        assertEquals(100, budgets.first())
        assertEquals(108, budgets.last())

        val lowLevel = projection("same", level = 20L, power = 45_000L)
        val highLevel = lowLevel.copy(level = 9_999L)
        val lowNormalized = ProjectionBattleEngine.normalizeProjection(lowLevel)
        val highNormalized = ProjectionBattleEngine.normalizeProjection(highLevel)
        assertEquals(lowNormalized.growthResonanceBudget, highNormalized.growthResonanceBudget)
        assertEquals(lowNormalized.stats, highNormalized.stats)
        assertNotEquals(lowNormalized.level, highNormalized.level)
    }

    @Test
    fun `normalization retains build proportions and safely handles extreme or empty stats`() {
        val proportioned = projection(
            id = "ratio",
            power = 0L,
            build = BattleBuildStats(60L, 30L, 10L, 0L, 0L, 0L),
        )
        val normalized = ProjectionBattleEngine.normalizeProjection(proportioned)

        assertEquals(10_000, normalized.stats.total())
        assertEquals(6_000, normalized.stats.strength)
        assertEquals(3_000, normalized.stats.constitution)
        assertEquals(1_000, normalized.stats.dexterity)

        val extreme = ProjectionBattleEngine.normalizeProjection(
            proportioned.copy(
                verifiedPower = Long.MAX_VALUE,
                build = BattleBuildStats(
                    Long.MAX_VALUE,
                    Long.MAX_VALUE,
                    Long.MIN_VALUE,
                    0L,
                    1L,
                    1L,
                ),
            ),
        )
        assertEquals(10_000, extreme.stats.total())
        assertTrue(extreme.stats.values().all { it >= 0 })

        val empty = ProjectionBattleEngine.normalizeProjection(
            proportioned.copy(build = BattleBuildStats(0L, 0L, 0L, 0L, 0L, 0L)),
        )
        assertEquals(10_000, empty.stats.total())
        assertTrue(empty.stats.values().max() - empty.stats.values().min() <= 1)
    }

    @Test
    fun `equal-rating result uses K24 and only changes the initiating user`() {
        val win = ProjectionBattleEngine.settleUserStanding(
            standing = BattleSeasonStanding(score = 1_000),
            opponentReferenceScore = 1_000,
            outcome = BattleOutcome.USER_WIN,
        )

        assertEquals(BATTLE_RATING_K, win.kFactor)
        assertEquals(12, win.scoreDelta)
        assertEquals(1_012, win.after.score)
        assertEquals(1, win.after.wins)
        assertTrue(win.wasPlacementBattle)
        assertEquals(9, win.placementBattlesRemaining)
        assertFalse(win.opponentScoreChanged)
        assertEquals(1_000, win.opponentReferenceScore)
    }

    @Test
    fun `losses stop exactly at zero and report only the applied score delta`() {
        val atZero = ProjectionBattleEngine.settleUserStanding(
            standing = BattleSeasonStanding(score = 0),
            opponentReferenceScore = 0,
            outcome = BattleOutcome.USER_LOSS,
        )
        assertEquals(0, atZero.before.score)
        assertEquals(0, atZero.after.score)
        assertEquals(0, atZero.scoreDelta)
        assertEquals(1, atZero.after.losses)

        val nearZero = ProjectionBattleEngine.settleUserStanding(
            standing = BattleSeasonStanding(score = 1),
            opponentReferenceScore = 0,
            outcome = BattleOutcome.USER_LOSS,
        )
        assertEquals(1, nearZero.before.score)
        assertEquals(0, nearZero.after.score)
        assertEquals(-1, nearZero.scoreDelta)
        assertEquals(1, nearZero.after.losses)

        val malformedLegacy = ProjectionBattleEngine.settleUserStanding(
            standing = BattleSeasonStanding(score = -50),
            opponentReferenceScore = -10,
            outcome = BattleOutcome.USER_LOSS,
        )
        assertEquals(0, malformedLegacy.before.score)
        assertEquals(0, malformedLegacy.after.score)
        assertEquals(0, malformedLegacy.scoreDelta)
    }

    @Test
    fun `first ten settled battles are placement and the eleventh is ordinary`() {
        var standing = BattleSeasonStanding()
        repeat(BATTLE_PLACEMENT_BATTLES) { index ->
            val update = ProjectionBattleEngine.settleUserStanding(
                standing,
                opponentReferenceScore = standing.score,
                outcome = BattleOutcome.DRAW,
            )
            assertTrue(update.wasPlacementBattle)
            assertEquals(BATTLE_PLACEMENT_BATTLES - index - 1, update.placementBattlesRemaining)
            standing = update.after
        }

        val eleventh = ProjectionBattleEngine.settleUserStanding(
            standing,
            opponentReferenceScore = standing.score,
            outcome = BattleOutcome.DRAW,
        )
        assertFalse(eleventh.wasPlacementBattle)
        assertEquals(0, eleventh.placementBattlesRemaining)
    }

    @Test
    fun `ten ticket capacity consumes and refunds without mutating its input`() {
        val original = BattleTicketState()
        var current = original
        repeat(BATTLE_TICKET_CAPACITY) {
            val transition = ProjectionBattleEngine.consumeTicket(current, gameEpochDay = 20_001L)
            assertTrue(transition.accepted)
            current = transition.state
        }
        val rejected = ProjectionBattleEngine.consumeTicket(current, gameEpochDay = 20_001L)

        assertFalse(rejected.accepted)
        assertEquals(0, rejected.state.remaining)
        assertEquals(BATTLE_TICKET_CAPACITY, original.remaining)
        assertEquals(1, ProjectionBattleEngine.refundTicket(current, 20_001L).remaining)
        assertEquals(0, ProjectionBattleEngine.refreshTickets(current, 20_002L).remaining)
    }

    @Test
    fun `ticket capacity does not refill at Seoul midnight`() {
        val seoul = ZoneId.of("Asia/Seoul")
        val justBeforeMidnight = Instant.parse("2026-09-04T14:59:59.999Z")
        val atMidnight = Instant.parse("2026-09-04T15:00:00Z")
        val beforeDay = justBeforeMidnight.atZone(seoul).toLocalDate().toEpochDay()
        val afterDay = atMidnight.atZone(seoul).toLocalDate().toEpochDay()
        assertEquals(beforeDay + 1L, afterDay)

        var depleted = BattleTicketState()
        repeat(BATTLE_TICKET_CAPACITY) {
            depleted = ProjectionBattleEngine.consumeTicket(depleted, beforeDay).state
        }
        assertFalse(ProjectionBattleEngine.consumeTicket(depleted, beforeDay).accepted)

        val rejectedAfterMidnight = ProjectionBattleEngine.consumeTicket(depleted, afterDay)
        assertFalse(rejectedAfterMidnight.accepted)
        assertEquals(afterDay, rejectedAfterMidnight.state.gameEpochDay)
        assertEquals(0, rejectedAfterMidnight.state.remaining)
    }

    @Test
    fun `official resolution spends one ticket settles user and has record-only rewards`() {
        val resolution = ProjectionBattleEngine.resolveOfficialBattle(
            request = request(),
            standing = BattleSeasonStanding(),
            tickets = BattleTicketState(),
            gameEpochDay = 20_001L,
        )

        assertTrue(resolution.accepted)
        assertEquals(BATTLE_TICKET_CAPACITY - 1, resolution.tickets.remaining)
        assertNotNull(resolution.battle)
        assertNotNull(resolution.standing)
        assertEquals(BattleRewardPolicy.RECORD_ONLY, resolution.battle?.rewardPolicy)
        assertFalse(resolution.standing?.opponentScoreChanged ?: true)
    }

    @Test
    fun `trait combat profiles deterministically alter combat style without changing inputs`() {
        val plain = request(
            user = projection("user").copy(activeTraitIds = emptyList()),
            opponent = projection("opponent").copy(activeTraitIds = emptyList()),
        )
        val decorated = plain.copy(
            user = plain.user.copy(activeTraitIds = listOf("TRAIT_011", "TRAIT_021")),
            opponent = plain.opponent.copy(activeTraitIds = listOf("TRAIT_061")),
        )

        val plainResult = ProjectionBattleEngine.simulate(plain)
        val decoratedResult = ProjectionBattleEngine.simulate(decorated)
        assertNotEquals(plainResult.user.combatProfile, decoratedResult.user.combatProfile)
        assertNotEquals(plainResult.rounds, decoratedResult.rounds)
        assertEquals(decoratedResult, ProjectionBattleEngine.simulate(decorated))
    }

    @Test
    fun `growth resonance 108 wins sixty to sixty-five percent after side-balanced seed sampling`() {
        val weak = projection(
            id = "growth-100",
            power = 0L,
            heroClass = BattleHeroClass.WARRIOR,
            guidance = BattleGuidance.BALANCED,
        )
        val strong = weak.copy(projectionId = "growth-108", verifiedPower = Long.MAX_VALUE)
        assertEquals(100, ProjectionBattleEngine.normalizeProjection(weak).growthResonanceBudget)
        assertEquals(108, ProjectionBattleEngine.normalizeProjection(strong).growthResonanceBudget)

        val seedCount = 10_000
        val tally = sideBalancedTally(strong, weak, seedCount)
        println("growth-resonance-qa seeds=$seedCount battles=${tally.total} result=${tally.firstWins}-${tally.secondWins}-${tally.draws} winRate=${tally.firstWinRate}")
        assertEquals(seedCount * 2, tally.total)
        assertTrue(
            "108 resonance actual win rate=${tally.firstWinRate} " +
                "(${tally.firstWins}-${tally.secondWins}-${tally.draws}) must be 0.60..0.65",
            tally.firstWinRate in 0.60..0.65,
        )
    }

    @Test
    fun `equal growth peers stay near fifty percent after side-balanced seed sampling`() {
        val peerA = projection(
            id = "equal-a",
            power = 30_000L,
            heroClass = BattleHeroClass.WARRIOR,
            guidance = BattleGuidance.BALANCED,
        )
        val peerB = peerA.copy(projectionId = "equal-b")
        val seedCount = 10_000
        val tally = sideBalancedTally(peerA, peerB, seedCount)
        println("equal-growth-qa seeds=$seedCount battles=${tally.total} result=${tally.firstWins}-${tally.secondWins}-${tally.draws} winRate=${tally.firstWinRate}")

        assertEquals(seedCount * 2, tally.total)
        assertTrue(
            "equal-growth actual win rate=${tally.firstWinRate} must stay near 0.50",
            tally.firstWinRate in 0.48..0.52,
        )
    }

    @Test
    fun `legacy condition values are neutral and cannot alter a battle`() {
        val normal = projection(
            id = "condition-normal",
            power = 30_000L,
            heroClass = BattleHeroClass.WARRIOR,
            guidance = BattleGuidance.BALANCED,
        )
        val opponent = normal.copy(projectionId = "condition-opponent")
        val baseline = request(seed = 9_311L, user = normal, opponent = opponent)
        val legacy = baseline.copy(
            user = baseline.user.copy(condition = BattleCondition.BEST),
            opponent = baseline.opponent.copy(condition = BattleCondition.WORST),
        )

        assertEquals(
            listOf("WORST", "BAD", "NORMAL", "GOOD", "BEST"),
            BattleCondition.entries.map { it.name },
        )
        val baselineResult = ProjectionBattleEngine.simulate(baseline)
        val legacyResult = ProjectionBattleEngine.simulate(legacy)
        assertEquals(baselineResult.outcome, legacyResult.outcome)
        assertEquals(baselineResult.rounds, legacyResult.rounds)
    }

    @Test
    fun `v2 combat produces varied lengths and uses only eligible owned skills as finishers`() {
        val finisherSkill = BattleSkillSnapshot(
            skillId = "owned-finisher",
            displayName = "불굴의 일격",
            kind = BattleSkillKind.STRIKE,
            powerBasisPoints = 13_500,
            cooldownRounds = 1,
            masteryLevel = 55,
            finisherEligible = true,
        )
        val user = projection("v2-user", skills = listOf(finisherSkill)).copy(
            activeTraitIds = listOf("TRAIT_011", "TRAIT_031", "TRAIT_091"),
        )
        val opponent = projection("v2-opponent", skills = listOf(finisherSkill.copy(skillId = "opponent-finisher"))).copy(
            activeTraitIds = listOf("TRAIT_021", "TRAIT_041", "TRAIT_061"),
        )
        val results = (1L..2_000L).map { seed ->
            ProjectionBattleEngine.simulate(request(seed = seed, user = user, opponent = opponent))
        }
        val lengths = results.map { it.rounds.size }.sorted()
        val finishers = results.flatMap { result ->
            result.rounds.flatMap { listOf(it.userAction, it.opponentAction) }.filter { it.finisher }
        }
        val actions = results.flatMap { result ->
            result.rounds.flatMap { listOf(it.userAction, it.opponentAction) }
        }
        val guardActions = actions.count { it.kind == BattleActionKind.GUARD }
        val guardedAttacks = actions.count { it.resolution == BattleActionResolution.BLOCKED }
        val moraleValues = results.flatMap { result ->
            result.rounds.flatMap { round ->
                listOf(round.userMoraleBefore, round.opponentMoraleBefore, round.userMoraleAfter, round.opponentMoraleAfter)
            }
        }

        println(
            "v2-length-qa samples=${lengths.size} min=${lengths.first()} " +
                "median=${lengths[lengths.size / 2]} p90=${lengths[(lengths.size * 9 / 10).coerceAtMost(lengths.lastIndex)]} " +
                "max=${lengths.last()} finisherAttempts=${finishers.size} " +
                "finisherSuccess=${finishers.count { it.finisherSucceeded }} " +
                "guards=$guardActions/${actions.size} blockedAttacks=$guardedAttacks " +
                "morale=${moraleValues.min()}..${moraleValues.max()}",
        )
        assertTrue(lengths.distinct().size >= 5)
        assertTrue(lengths.first() in 3..6)
        assertTrue(lengths.first() < lengths[lengths.size / 2])
        assertTrue(lengths[lengths.size / 2] in 9..12)
        assertTrue(lengths.last() > lengths[lengths.size / 2])
        assertTrue(lengths.count { it <= 7 } > 0)
        assertTrue(results.all { it.rounds.last().userHpAfter == 0 || it.rounds.last().opponentHpAfter == 0 })
        assertTrue(finishers.isNotEmpty())
        assertTrue(finishers.any { it.finisherSucceeded })
        assertTrue(finishers.any { !it.finisherSucceeded && it.selfDamage > 0 })
        assertTrue(moraleValues.min() < 50)
        assertTrue(moraleValues.max() > 50)
        assertTrue(finishers.all { it.skillId == "owned-finisher" || it.skillId == "opponent-finisher" })
        assertTrue(
            results.all { result ->
                result.rounds.count { it.userAction.finisher } <= 1 &&
                    result.rounds.count { it.opponentAction.finisher } <= 1
            },
        )
    }

    @Test
    fun `ordinary hits center near ten percent and skill hits center near thirty percent`() {
        data class DamageSample(val percent: Double, val actionKind: BattleActionKind)

        val results = (1L..5_000L).map { seed ->
            ProjectionBattleEngine.simulate(request(seed = seed))
        }
        val samples = results.flatMap { result ->
            result.rounds.flatMap { round ->
                listOf(
                    round.userAction to result.opponent.maxHp,
                    round.opponentAction to result.user.maxHp,
                )
            }
        }.filter { (action, _) ->
            action.resolution == BattleActionResolution.HIT &&
                action.damage > 0 &&
                !action.critical &&
                !action.powerAttack &&
                !action.finisher
        }.map { (action, targetMaxHp) ->
            DamageSample(action.damage * 100.0 / targetMaxHp.toDouble(), action.kind)
        }
        val basicPercents = samples.filter { it.actionKind == BattleActionKind.BASIC_ATTACK }
            .map(DamageSample::percent).sorted()
        val skillPercents = samples.filter { it.actionKind == BattleActionKind.SKILL }
            .map(DamageSample::percent).sorted()
        val basicMedian = basicPercents[basicPercents.size / 2]
        val skillMedian = skillPercents[skillPercents.size / 2]
        val resolutions = results.flatMap { result ->
            result.rounds.flatMap { listOf(it.userAction.resolution, it.opponentAction.resolution) }
        }.toSet()

        println(
            "damage-percent-qa samples=${samples.size} basic=${basicPercents.first()}..${basicPercents.last()} " +
                "median=$basicMedian skill=${skillPercents.first()}..${skillPercents.last()} " +
                "median=$skillMedian resolutions=${resolutions.sortedBy { it.ordinal }}",
        )
        assertTrue(basicPercents.size > 1_000)
        assertTrue(skillPercents.size > 500)
        assertTrue("basic median=$basicMedian", basicMedian in 8.0..12.0)
        assertTrue("skill median=$skillMedian", skillMedian in 25.0..35.0)
        assertTrue(
            resolutions.containsAll(
                listOf(
                    BattleActionResolution.HIT,
                    BattleActionResolution.BLOCKED,
                    BattleActionResolution.EVADED,
                    BattleActionResolution.MISSED,
                    BattleActionResolution.GUARDED,
                ),
            ),
        )
    }

    @Test
    fun `endurance profiles measurably create long fights without infinite recovery`() {
        val patient = projection("patient", skills = emptyList()).copy(
            activeTraitIds = listOf("TRAIT_041", "TRAIT_042", "TRAIT_048"),
            guidance = BattleGuidance.GUARD,
        )
        val results = (1L..1_000L).map { seed ->
            ProjectionBattleEngine.simulate(request(seed = seed, user = patient, opponent = patient.copy(projectionId = "patient-b")))
        }
        val lengths = results.map { it.rounds.size }.sorted()
        val median = lengths[lengths.size / 2]

        println("v2-endurance-length-qa samples=${lengths.size} min=${lengths.first()} median=$median max=${lengths.last()}")
        assertTrue(median >= 8)
        assertTrue(lengths.last() >= 12)
        assertTrue(lengths.last() > 20)
        assertTrue("the former round limit must be passable: max=${lengths.last()}", lengths.last() > 30)
        assertTrue(results.all { it.rounds.last().userHpAfter == 0 || it.rounds.last().opponentHpAfter == 0 })
    }

    @Test
    fun `legacy max rounds is ignored and every battle ends by actual energy loss`() {
        val patient = projection("unlimited", skills = emptyList()).copy(
            activeTraitIds = listOf("TRAIT_041", "TRAIT_042", "TRAIT_048"),
            guidance = BattleGuidance.GUARD,
        )
        val results = (1L..500L).map { seed ->
            ProjectionBattleEngine.simulate(
                request(
                    seed = seed,
                    user = patient,
                    opponent = patient.copy(projectionId = "unlimited-b"),
                ).copy(rules = BattleRules(maxRounds = 1, growthReferencePower = 10_000L)),
            )
        }

        assertTrue(results.any { it.rounds.size > 30 })
        assertTrue(results.all { it.rounds.size > 1 })
        results.forEach { result ->
            val last = result.rounds.last()
            val rawUserHp = last.userHpBefore - last.opponentAction.damage -
                last.userAction.selfDamage + last.userAction.healing
            val rawOpponentHp = last.opponentHpBefore - last.userAction.damage -
                last.opponentAction.selfDamage + last.opponentAction.healing
            assertTrue(last.userHpAfter == 0 || last.opponentHpAfter == 0)
            assertTrue(
                "Battle ended without an actual depletion: $last",
                (last.userHpAfter == 0 && rawUserHp <= 0) ||
                    (last.opponentHpAfter == 0 && rawOpponentHp <= 0),
            )
        }
    }

    @Test
    fun `attack power determines battle length instead of a fixed five turn script`() {
        val highAttack = projection(
            id = "high-attack",
            guidance = BattleGuidance.ASSAULT,
            build = BattleBuildStats(60, 15, 10, 5, 5, 5),
            skills = emptyList(),
        )
        val lowAttack = projection(
            id = "low-attack",
            guidance = BattleGuidance.ASSAULT,
            build = BattleBuildStats(10, 15, 10, 55, 5, 5),
            skills = emptyList(),
        )
        val highAttackLengths = (1L..1_000L).map { seed ->
            ProjectionBattleEngine.simulate(
                request(seed = seed, user = highAttack, opponent = highAttack.copy(projectionId = "high-attack-b")),
            ).rounds.size
        }.sorted()
        val lowAttackLengths = (1L..1_000L).map { seed ->
            ProjectionBattleEngine.simulate(
                request(seed = seed, user = lowAttack, opponent = lowAttack.copy(projectionId = "low-attack-b")),
            ).rounds.size
        }.sorted()
        val highMedian = highAttackLengths[highAttackLengths.size / 2]
        val lowMedian = lowAttackLengths[lowAttackLengths.size / 2]

        println(
            "attack-length-qa high=${highAttackLengths.first()}..${highAttackLengths.last()} median=$highMedian " +
                "low=${lowAttackLengths.first()}..${lowAttackLengths.last()} median=$lowMedian",
        )
        assertTrue(highMedian < lowMedian)
        assertTrue((highAttackLengths + lowAttackLengths).all { it >= 1 })
        assertTrue((highAttackLengths + lowAttackLengths).any { it != 5 })
    }

    @Test
    fun `constitution changes maximum health while dexterity changes deterministic pressure`() {
        val sturdy = projection(
            id = "sturdy",
            build = BattleBuildStats(10, 60, 10, 5, 10, 5),
            skills = emptyList(),
        )
        val swift = projection(
            id = "swift",
            build = BattleBuildStats(10, 10, 60, 5, 10, 5),
            skills = emptyList(),
        )
        val sturdyNormalized = ProjectionBattleEngine.normalizeProjection(sturdy)
        val swiftNormalized = ProjectionBattleEngine.normalizeProjection(swift)

        assertTrue(sturdyNormalized.maxHp > swiftNormalized.maxHp)
        val sturdyResult = ProjectionBattleEngine.simulate(request(seed = 444L, user = sturdy, opponent = swift))
        val swiftResult = ProjectionBattleEngine.simulate(request(seed = 444L, user = swift, opponent = sturdy))
        assertNotEquals(sturdyResult.rounds, swiftResult.rounds)
    }

    @Test
    fun `new battle payloads decode from empty JSON with safe defaults`() {
        val request = json.decodeFromString<UserInitiatedBattleRequest>("{}")
        val tickets = json.decodeFromString<BattleTicketState>("{}")

        assertEquals(0L, request.serverSeed)
        assertEquals(BATTLE_UNLIMITED_ROUNDS, request.rules.maxRounds)
        assertEquals(BATTLE_TICKET_CAPACITY, tickets.remaining)
        assertTrue(ProjectionBattleEngine.simulate(request).rounds.isNotEmpty())
    }

    private fun request(
        seed: Long = 7_711L,
        user: BattleProjectionSnapshot = projection("user"),
        opponent: BattleProjectionSnapshot = projection(
            id = "opponent",
            heroClass = BattleHeroClass.MAGE,
            build = BattleBuildStats(8L, 12L, 14L, 34L, 22L, 10L),
        ),
    ): UserInitiatedBattleRequest = UserInitiatedBattleRequest(
        battleId = "battle-test-001",
        serverSeed = seed,
        requestedAtMillis = 1_000L,
        user = user,
        opponent = opponent,
        opponentReferenceScore = 1_000,
        rules = BattleRules(growthReferencePower = 10_000L),
    )

    private fun projection(
        id: String,
        heroClass: BattleHeroClass = BattleHeroClass.WARRIOR,
        guidance: BattleGuidance = BattleGuidance.BALANCED,
        condition: BattleCondition = BattleCondition.NORMAL,
        level: Long = 40L,
        power: Long = 30_000L,
        build: BattleBuildStats = BattleBuildStats(32L, 24L, 18L, 8L, 12L, 6L),
        skills: List<BattleSkillSnapshot> = listOf(
            BattleSkillSnapshot("skill-$id-1", "결정의 일격", BattleSkillKind.STRIKE, 13_000, 2, 20),
            BattleSkillSnapshot("skill-$id-2", "꿰뚫는 걸음", BattleSkillKind.PIERCE, 11_500, 1, 40),
        ),
    ): BattleProjectionSnapshot = BattleProjectionSnapshot(
        projectionId = id,
        displayName = id,
        heroClass = heroClass,
        level = level,
        verifiedPower = power,
        condition = condition,
        build = build,
        guidance = guidance,
        skills = skills,
    )

    private data class SideBalancedTally(
        val firstWins: Int,
        val secondWins: Int,
        val draws: Int,
    ) {
        val total: Int get() = firstWins + secondWins + draws
        val firstWinRate: Double get() = firstWins.toDouble() / total.toDouble()
    }

    private fun sideBalancedTally(
        first: BattleProjectionSnapshot,
        second: BattleProjectionSnapshot,
        seedCount: Int,
    ): SideBalancedTally {
        var firstWins = 0
        var secondWins = 0
        var draws = 0
        for (seed in 1L..seedCount.toLong()) {
            when (
                ProjectionBattleEngine.simulate(
                    request(seed = seed, user = first, opponent = second),
                ).outcome
            ) {
                BattleOutcome.USER_WIN -> firstWins += 1
                BattleOutcome.USER_LOSS -> secondWins += 1
                BattleOutcome.DRAW -> draws += 1
            }
            when (
                ProjectionBattleEngine.simulate(
                    request(seed = seed, user = second, opponent = first),
                ).outcome
            ) {
                BattleOutcome.USER_WIN -> secondWins += 1
                BattleOutcome.USER_LOSS -> firstWins += 1
                BattleOutcome.DRAW -> draws += 1
            }
        }
        return SideBalancedTally(firstWins, secondWins, draws)
    }
}
