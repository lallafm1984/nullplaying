package com.nullplaying.engine

import com.nullplaying.model.BATTLE_TICKET_CAPACITY
import com.nullplaying.model.BATTLE_MAX_ACTIVE_TRAITS
import com.nullplaying.model.BATTLE_PLACEMENT_BATTLES
import com.nullplaying.model.BATTLE_RATING_K
import com.nullplaying.model.BattleActionKind
import com.nullplaying.model.BattleActionResolution
import com.nullplaying.model.BattleBuildStats
import com.nullplaying.model.BattleGuidance
import com.nullplaying.model.BattleHeroClass
import com.nullplaying.model.BattleOutcome
import com.nullplaying.model.BattleProjectionSnapshot
import com.nullplaying.model.BattleRewardPolicy
import com.nullplaying.model.BattleRound
import com.nullplaying.model.BattleRoundAction
import com.nullplaying.model.BattleRules
import com.nullplaying.model.BattleSeasonStanding
import com.nullplaying.model.BattleSide
import com.nullplaying.model.BattleSkillKind
import com.nullplaying.model.BattleSkillSnapshot
import com.nullplaying.model.BattleStandingUpdate
import com.nullplaying.model.BattleTicketState
import com.nullplaying.model.BattleTicketTransition
import com.nullplaying.model.BattleTalentEffectTrace
import com.nullplaying.model.HERO_PATH_CATALOG_VERSION
import com.nullplaying.model.HERO_PATH_COUNTER_RULES_VERSION
import com.nullplaying.model.HERO_PATH_MAX_CLASS_CHARGE
import com.nullplaying.model.HeroPathBranch
import com.nullplaying.model.HeroPathChoiceStance
import com.nullplaying.model.HeroPathCounterArchetype
import com.nullplaying.model.HeroPathEffectFamily
import com.nullplaying.model.HeroPathEffectStage
import com.nullplaying.model.HeroPathMatchupRelation
import com.nullplaying.model.HeroPathNodeSlot
import com.nullplaying.model.HeroPathNodeType
import com.nullplaying.model.NormalizedBattleProjection
import com.nullplaying.model.NormalizedBattleStats
import com.nullplaying.model.OfficialBattleResolution
import com.nullplaying.model.ProjectionBattleResult
import com.nullplaying.model.UserInitiatedBattleRequest
import java.math.BigInteger
import kotlin.math.floor
import kotlin.math.ln1p
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Pure Battle V0.1 rules. There is no repository, clock, random source, network or economy access:
 * callers provide the UTC game day and server seed, and persist returned values transactionally.
 * The trusted-clock caller derives gameEpochDay from the UTC epoch boundary used by the server;
 * this engine deliberately performs no clock or timezone calculation.
 */
object ProjectionBattleEngine {
    const val PROJECTION_MAX_HP = 1_000
    const val NORMALIZED_STAT_PRECISION = 100
    const val BASIC_ATTACK_DAMAGE_BASIS_POINTS = 1_000
    const val SKILL_ATTACK_DAMAGE_BASIS_POINTS = 3_000
    const val FATIGUE_START_ROUND = 30

    /**
     * Converts server-verified growth into a small 100..108 budget. The log curve gives early
     * growth recognition while preventing raw level/power gaps from deciding the ladder.
     */
    fun growthResonanceBudget(
        verifiedPower: Long,
        growthReferencePower: Long,
    ): Int {
        val power = verifiedPower.coerceAtLeast(0L).toDouble()
        val reference = growthReferencePower.coerceAtLeast(1L).toDouble()
        val logBonus = floor(4.0 * ln1p(power / reference)).toInt()
        return 100 + logBonus.coerceIn(0, 8)
    }

    /** Normalizes totals while retaining the exact non-negative proportions of the build. */
    fun normalizeProjection(
        snapshot: BattleProjectionSnapshot,
        rules: BattleRules = BattleRules(),
    ): NormalizedBattleProjection {
        val budget = growthResonanceBudget(snapshot.verifiedPower, rules.growthReferencePower)
        // Build shape is always normalized to base 100. Growth resonance is applied exactly once
        // to outgoing damage, rather than amplifying both attack and defense through this vector.
        val stats = apportionStats(snapshot.build, BASE_BUILD_BUDGET * NORMALIZED_STAT_PRECISION)
        val activeTraitIds = snapshot.activeTraitIds
            .asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .take(BATTLE_MAX_ACTIVE_TRAITS)
            .toList()
        val heroPathTraitRanks = snapshot.heroPathTraitRanks
            .filterKeys { it in activeTraitIds }
            .mapValues { (_, rank) -> rank.coerceIn(1, 5) }
        val explicitPath = snapshot.heroPathBattleSnapshot.takeIf {
            HeroPathEngine.validateBattleSnapshot(it, snapshot.heroClass)
        }
        val fallbackPath = if (explicitPath == null && snapshot.heroPathCatalogVersion >= HERO_PATH_CATALOG_VERSION) {
            HeroPathEngine.battleSnapshotFromRanks(
                snapshot.heroClass, snapshot.heroPathTraitRanks, snapshot.heroPathRevision,
                snapshot.heroPathTraitRanks.keys.singleOrNull {
                    HeroPathCatalog.byNodeId[it]?.nodeType == HeroPathNodeType.CORE
                }.orEmpty(),
            ).takeIf { HeroPathEngine.validateBattleSnapshot(it, snapshot.heroClass) }
        } else null
        val heroPathSnapshot = explicitPath ?: fallbackPath ?: com.nullplaying.model.HeroPathBattleSnapshot()
        return NormalizedBattleProjection(
            projectionId = snapshot.projectionId,
            displayName = snapshot.displayName,
            heroClass = snapshot.heroClass,
            // Metadata only. No combat formula below reads this field.
            level = snapshot.level.coerceAtLeast(1L),
            condition = snapshot.condition,
            growthResonanceBudget = budget,
            stats = stats,
            guidance = snapshot.guidance,
            skills = snapshot.skills
                .asSequence()
                .filter { it.skillId.isNotBlank() }
                .distinctBy { it.skillId }
                .take(MAX_SKILLS)
                .map(::sanitizeSkill)
                .toList(),
            equipment = snapshot.equipment
                .asSequence()
                .distinctBy { it.slot }
                .take(MAX_EQUIPMENT_SLOTS)
                .toList(),
            activeTraitIds = activeTraitIds,
            heroPathTraitRanks = heroPathTraitRanks,
            // Legacy traits remain narrative-only when the V2 tree is present.
            combatProfile = if (heroPathSnapshot.treeVersion > 0) {
                com.nullplaying.model.BattleTraitCombatProfile()
            } else {
                BattleTraitCatalog.combatProfileFor(activeTraitIds, heroPathTraitRanks)
            },
            heroPathRevision = if (heroPathSnapshot.treeVersion > 0) {
                heroPathSnapshot.allocationRevision
            } else {
                snapshot.heroPathRevision.coerceAtLeast(0L)
            },
            heroPathCatalogVersion = if (heroPathSnapshot.treeVersion > 0) {
                HERO_PATH_CATALOG_VERSION
            } else {
                snapshot.heroPathCatalogVersion.coerceAtLeast(0)
            },
            heroPathBattleSnapshot = heroPathSnapshot,
            maxHp = maxHpFor(stats),
            snapshotVersion = snapshot.snapshotVersion.coerceAtLeast(1),
        )
    }

    /**
     * Resolves both decisions from the same start-of-round state and applies their effects
     * simultaneously. A repeated request with the same snapshots, IDs, rules and server seed is
     * byte-for-byte reproducible after serialization.
     */
    fun simulate(request: UserInitiatedBattleRequest): ProjectionBattleResult {
        val user = normalizeProjection(request.user, request.rules)
        val opponent = normalizeProjection(request.opponent, request.rules)
        val baseSeed = request.serverSeed xor stableHash(request.battleId) xor
            stableHash(user.projectionId).rotateLeft(17) xor
            stableHash(opponent.projectionId).rotateLeft(41) xor
            request.rules.rulesVersion.toLong()
        val userCooldowns = mutableMapOf<String, Int>()
        val opponentCooldowns = mutableMapOf<String, Int>()
        val rounds = mutableListOf<BattleRound>()
        var userHp = user.maxHp
        var opponentHp = opponent.maxHp
        var userMorale = initialMorale(user)
        var opponentMorale = initialMorale(opponent)
        var userFinisherUsed = false
        var opponentFinisherUsed = false
        val userTalent = TalentRuntimeState(user.heroPathBattleSnapshot.initialClassCharge)
        val opponentTalent = TalentRuntimeState(opponent.heroPathBattleSnapshot.initialClassCharge)
        val userMatchup = talentMatchupContext(user, opponent)
        val opponentMatchup = talentMatchupContext(opponent, user)

        var roundNumber = 1
        while (userHp > 0 && opponentHp > 0 && roundNumber <= MAX_BATTLE_ROUNDS) {
            val userChargeBefore = userTalent.charge
            val opponentChargeBefore = opponentTalent.charge
            val userPlan = chooseAction(
                projection = user,
                hp = userHp,
                opponentHp = opponentHp,
                round = roundNumber,
                side = BattleSide.USER,
                seed = baseSeed,
                lastSkillRounds = userCooldowns,
                finisherUsed = userFinisherUsed,
                morale = userMorale,
                talentCharge = userTalent.charge,
                coreUsed = userTalent.coreUsed,
            )
            val opponentPlan = chooseAction(
                projection = opponent,
                hp = opponentHp,
                opponentHp = userHp,
                round = roundNumber,
                side = BattleSide.OPPONENT,
                seed = baseSeed,
                lastSkillRounds = opponentCooldowns,
                finisherUsed = opponentFinisherUsed,
                morale = opponentMorale,
                talentCharge = opponentTalent.charge,
                coreUsed = opponentTalent.coreUsed,
            )

            userPlan.skill?.let { userCooldowns[it.skillId] = roundNumber }
            opponentPlan.skill?.let { opponentCooldowns[it.skillId] = roundNumber }

            var userAction = resolveAction(
                actorSide = BattleSide.USER,
                actor = user,
                defender = opponent,
                plan = userPlan,
                defenderPlan = opponentPlan,
                actorHp = userHp,
                defenderHp = opponentHp,
                round = roundNumber,
                seed = baseSeed,
                morale = userMorale,
            )
            var opponentAction = resolveAction(
                actorSide = BattleSide.OPPONENT,
                actor = opponent,
                defender = user,
                plan = opponentPlan,
                defenderPlan = userPlan,
                actorHp = opponentHp,
                defenderHp = userHp,
                round = roundNumber,
                seed = baseSeed,
                morale = opponentMorale,
            )

            userAction = applyPlannedTalent(userAction, userPlan, user, opponent, userTalent, userMatchup)
            opponentAction = applyPlannedTalent(opponentAction, opponentPlan, opponent, user, opponentTalent, opponentMatchup)

            userFinisherUsed = userFinisherUsed || userAction.finisher
            opponentFinisherUsed = opponentFinisherUsed || opponentAction.finisher
            val rawNextUserHp = userHp - opponentAction.damage - userAction.selfDamage + userAction.healing
            val rawNextOpponentHp = opponentHp - userAction.damage - opponentAction.selfDamage + opponentAction.healing
            var nextUserHp = rawNextUserHp.coerceIn(0, user.maxHp)
            var nextOpponentHp = rawNextOpponentHp.coerceIn(0, opponent.maxHp)
            val roundTalentEffects = mutableListOf<BattleTalentEffectTrace>()
            roundTalentEffects += userAction.talentEffects
            roundTalentEffects += opponentAction.talentEffects
            if (nextUserHp == 0) {
                lethalSurvival(user, userTalent, userMatchup)?.let { trace ->
                    nextUserHp = 1
                    roundTalentEffects += trace
                }
            }
            if (nextOpponentHp == 0) {
                lethalSurvival(opponent, opponentTalent, opponentMatchup)?.let { trace ->
                    nextOpponentHp = 1
                    roundTalentEffects += trace
                }
            }
            // Actions resolve simultaneously, but the duel still needs one survivor. When both
            // attacks cross zero in the same exchange, preserve the fighter with the better
            // post-impact survival margin at 1 HP instead of turning every mutual KO into a draw.
            if (nextUserHp == 0 && nextOpponentHp == 0 && rawNextUserHp != rawNextOpponentHp) {
                if (rawNextUserHp > rawNextOpponentHp) {
                    nextUserHp = 1
                } else {
                    nextOpponentHp = 1
                }
            }
            val nextUserMorale = resolveMorale(
                before = userMorale,
                actor = user,
                opponent = opponent,
                actorAction = userAction,
                opponentAction = opponentAction,
                actorHpAfter = nextUserHp,
            )
            val nextOpponentMorale = resolveMorale(
                before = opponentMorale,
                actor = opponent,
                opponent = user,
                actorAction = opponentAction,
                opponentAction = userAction,
                actorHpAfter = nextOpponentHp,
            )
            updateClassCharge(
                runtime = userTalent, actor = user, actorAction = userAction,
                opponentAction = opponentAction, damageTaken = (userHp - nextUserHp).coerceAtLeast(0),
                round = roundNumber, seed = baseSeed, side = BattleSide.USER, matchup = userMatchup,
            )
            updateClassCharge(
                runtime = opponentTalent, actor = opponent, actorAction = opponentAction,
                opponentAction = userAction, damageTaken = (opponentHp - nextOpponentHp).coerceAtLeast(0),
                round = roundNumber, seed = baseSeed, side = BattleSide.OPPONENT, matchup = opponentMatchup,
            )
            val removedFromOpponent = userAction.talentEffects.sumOf { it.resourceRemoved }.coerceAtMost(1)
            val removedFromUser = opponentAction.talentEffects.sumOf { it.resourceRemoved }.coerceAtMost(1)
            opponentTalent.charge = (opponentTalent.charge - removedFromOpponent).coerceAtLeast(0)
            userTalent.charge = (userTalent.charge - removedFromUser).coerceAtLeast(0)
            rounds += BattleRound(
                number = roundNumber,
                userHpBefore = userHp,
                opponentHpBefore = opponentHp,
                userMoraleBefore = userMorale,
                opponentMoraleBefore = opponentMorale,
                userAction = userAction,
                opponentAction = opponentAction,
                userHpAfter = nextUserHp,
                opponentHpAfter = nextOpponentHp,
                userMoraleAfter = nextUserMorale,
                opponentMoraleAfter = nextOpponentMorale,
                userClassChargeBefore = userChargeBefore,
                opponentClassChargeBefore = opponentChargeBefore,
                userClassChargeAfter = userTalent.charge,
                opponentClassChargeAfter = opponentTalent.charge,
                talentEffects = roundTalentEffects,
            )
            userHp = nextUserHp
            opponentHp = nextOpponentHp
            userMorale = nextUserMorale
            opponentMorale = nextOpponentMorale
            if (userHp == 0 || opponentHp == 0) break
            roundNumber += 1
        }

        val userDamage = rounds.sumOf { it.userAction.damage.toLong() }
        val opponentDamage = rounds.sumOf { it.opponentAction.damage.toLong() }
        val outcome = when {
            userHp == 0 && opponentHp == 0 -> BattleOutcome.DRAW
            userHp > opponentHp -> BattleOutcome.USER_WIN
            userHp < opponentHp -> BattleOutcome.USER_LOSS
            userDamage > opponentDamage -> BattleOutcome.USER_WIN
            userDamage < opponentDamage -> BattleOutcome.USER_LOSS
            else -> BattleOutcome.DRAW
        }
        return ProjectionBattleResult(
            battleId = request.battleId,
            serverSeed = request.serverSeed,
            rulesVersion = request.rules.rulesVersion,
            user = user,
            opponent = opponent,
            rounds = rounds,
            outcome = outcome,
            rewardPolicy = BattleRewardPolicy.RECORD_ONLY,
        )
    }

    /** Elo K=24 settlement for the initiating user only. */
    fun settleUserStanding(
        standing: BattleSeasonStanding,
        opponentReferenceScore: Int,
        outcome: BattleOutcome,
    ): BattleStandingUpdate {
        val safeBefore = standing.copy(
            score = standing.score.coerceAtLeast(0),
            completedBattles = standing.completedBattles.coerceAtLeast(0),
            wins = standing.wins.coerceAtLeast(0),
            losses = standing.losses.coerceAtLeast(0),
            draws = standing.draws.coerceAtLeast(0),
        )
        val opponentScore = opponentReferenceScore.coerceAtLeast(0)
        val exponent = ((opponentScore - safeBefore.score).toDouble() / ELO_SCALE)
            .coerceIn(-MAX_ELO_EXPONENT, MAX_ELO_EXPONENT)
        val expected = 1.0 / (1.0 + 10.0.pow(exponent))
        val actual = when (outcome) {
            BattleOutcome.USER_WIN -> 1.0
            BattleOutcome.USER_LOSS -> 0.0
            BattleOutcome.DRAW -> 0.5
        }
        val requestedDelta = (BATTLE_RATING_K * (actual - expected)).roundToInt()
        val afterScore = (safeBefore.score.toLong() + requestedDelta.toLong())
            .coerceIn(0L, Int.MAX_VALUE.toLong())
            .toInt()
        val actualDelta = afterScore - safeBefore.score
        val after = safeBefore.copy(
            score = afterScore,
            completedBattles = safeIncrement(safeBefore.completedBattles),
            wins = if (outcome == BattleOutcome.USER_WIN) safeIncrement(safeBefore.wins) else safeBefore.wins,
            losses = if (outcome == BattleOutcome.USER_LOSS) safeIncrement(safeBefore.losses) else safeBefore.losses,
            draws = if (outcome == BattleOutcome.DRAW) safeIncrement(safeBefore.draws) else safeBefore.draws,
        )
        return BattleStandingUpdate(
            before = safeBefore,
            after = after,
            scoreDelta = actualDelta,
            opponentReferenceScore = opponentScore,
            kFactor = BATTLE_RATING_K,
            wasPlacementBattle = safeBefore.completedBattles < BATTLE_PLACEMENT_BATTLES,
            placementBattlesRemaining =
                (BATTLE_PLACEMENT_BATTLES - after.completedBattles).coerceAtLeast(0),
            opponentScoreChanged = false,
        )
    }

    fun refreshTickets(
        state: BattleTicketState,
        gameEpochDay: Long,
    ): BattleTicketState = if (state.gameEpochDay == gameEpochDay) {
        state.copy(remaining = state.remaining.coerceIn(0, BATTLE_TICKET_CAPACITY))
    } else {
        state.copy(
            gameEpochDay = gameEpochDay,
            remaining = state.remaining.coerceIn(0, BATTLE_TICKET_CAPACITY),
        )
    }

    fun consumeTicket(
        state: BattleTicketState,
        gameEpochDay: Long,
    ): BattleTicketTransition {
        val current = refreshTickets(state, gameEpochDay)
        if (current.remaining <= 0) return BattleTicketTransition(false, current)
        return BattleTicketTransition(true, current.copy(remaining = current.remaining - 1))
    }

    fun refundTicket(
        state: BattleTicketState,
        gameEpochDay: Long,
    ): BattleTicketState {
        val current = refreshTickets(state, gameEpochDay)
        return current.copy(remaining = (current.remaining + 1).coerceAtMost(BATTLE_TICKET_CAPACITY))
    }

    /** Convenience transaction model: only this explicitly user-initiated request can spend. */
    fun resolveOfficialBattle(
        request: UserInitiatedBattleRequest,
        standing: BattleSeasonStanding,
        tickets: BattleTicketState,
        gameEpochDay: Long,
    ): OfficialBattleResolution {
        val transition = consumeTicket(tickets, gameEpochDay)
        if (!transition.accepted) {
            return OfficialBattleResolution(accepted = false, tickets = transition.state)
        }
        val result = simulate(request)
        val standingUpdate = settleUserStanding(
            standing = standing,
            opponentReferenceScore = request.opponentReferenceScore,
            outcome = result.outcome,
        )
        return OfficialBattleResolution(
            accepted = true,
            tickets = transition.state,
            battle = result,
            standing = standingUpdate,
        )
    }

    private fun apportionStats(build: BattleBuildStats, totalUnits: Int): NormalizedBattleStats {
        val safeValues = build.values().map { BigInteger.valueOf(it.coerceAtLeast(0L)) }
        val sum = safeValues.fold(BigInteger.ZERO, BigInteger::add)
        val allocated = IntArray(safeValues.size)
        if (sum == BigInteger.ZERO) {
            val base = totalUnits / allocated.size
            val remainder = totalUnits % allocated.size
            for (index in allocated.indices) allocated[index] = base + if (index < remainder) 1 else 0
        } else {
            val total = BigInteger.valueOf(totalUnits.toLong())
            val remainders = Array(safeValues.size) { BigInteger.ZERO }
            var used = 0
            safeValues.forEachIndexed { index, value ->
                val division = value.multiply(total).divideAndRemainder(sum)
                allocated[index] = division[0].toInt()
                remainders[index] = division[1]
                used += allocated[index]
            }
            val priority = allocated.indices.sortedWith(
                compareByDescending<Int> { remainders[it] }.thenBy { it },
            )
            repeat(totalUnits - used) { offset -> allocated[priority[offset]] += 1 }
        }
        return NormalizedBattleStats(
            strength = allocated[0],
            constitution = allocated[1],
            dexterity = allocated[2],
            intelligence = allocated[3],
            wisdom = allocated[4],
            charisma = allocated[5],
        )
    }

    private fun maxHpFor(stats: NormalizedBattleStats): Int = (
        800 + stats.constitution / 10 + stats.wisdom / 50
        ).coerceIn(MIN_PROJECTION_HP, MAX_PROJECTION_HP)

    private fun initialMorale(projection: NormalizedBattleProjection): Int {
        val charisma = ((projection.stats.charisma - 1_500) / 350).coerceIn(-5, 8)
        val wisdom = ((projection.stats.wisdom - 1_500) / 600).coerceIn(-3, 5)
        val composure = (projection.combatProfile.stability - 50) / 10
        return (50 + charisma + wisdom + composure).coerceIn(MIN_MORALE, MAX_MORALE)
    }

    private fun resolveMorale(
        before: Int,
        actor: NormalizedBattleProjection,
        opponent: NormalizedBattleProjection,
        actorAction: BattleRoundAction,
        opponentAction: BattleRoundAction,
        actorHpAfter: Int,
    ): Int {
        val dealtPercent = (actorAction.damage + opponentAction.selfDamage) * 100 / opponent.maxHp.coerceAtLeast(1)
        val takenPercent = (opponentAction.damage + actorAction.selfDamage) * 100 / actor.maxHp.coerceAtLeast(1)
        var change = ((dealtPercent - takenPercent) / 3).coerceIn(-8, 8)
        if (actorAction.critical) change += 3
        if (opponentAction.critical) change -= 3
        if (actorAction.powerAttack && actorAction.damage > 0) change += 2
        if (actorAction.finisher) change += if (actorAction.finisherSucceeded) 12 else -14
        if (opponentAction.finisherSucceeded) change -= 8

        if (actorHpAfter * 100 <= actor.maxHp * 35 && before < 55) {
            val rally = (
                actor.stats.wisdom / 900 + actor.stats.charisma / 1_200 +
                    actor.combatProfile.stability / 30 + actor.combatProfile.longFight / 30
                ).coerceIn(1, 6)
            change += rally
        }
        val recovery = (
            1 + actor.stats.wisdom / 2_500 + actor.combatProfile.stability / 50
            ).coerceIn(1, 4)
        change += when {
            before < 50 -> recovery
            before > 50 -> -recovery
            else -> 0
        }
        return (before + change).coerceIn(MIN_MORALE, MAX_MORALE)
    }

    private fun sanitizeSkill(skill: BattleSkillSnapshot): BattleSkillSnapshot = skill.copy(
        powerBasisPoints = skill.powerBasisPoints.coerceIn(MIN_SKILL_POWER_BP, MAX_SKILL_POWER_BP),
        cooldownRounds = skill.cooldownRounds.coerceIn(0, MAX_SKILL_COOLDOWN_ROUNDS),
        masteryLevel = skill.masteryLevel.coerceIn(0, MAX_MASTERY_LEVEL),
    )

    private data class PlannedAction(
        val kind: BattleActionKind,
        val skill: BattleSkillSnapshot? = null,
        val powerAttack: Boolean = false,
        val finisher: Boolean = false,
        val talentNodeId: String = "",
    )

    private data class TalentRuntimeState(
        var charge: Int,
        var coreUsed: Boolean = false,
        var extraActionsUsed: Int = 0,
        var counterDamageDealt: Int = 0,
        var bonusHealingDone: Int = 0,
    )

    private data class TalentMatchupContext(
        val counterRulesVersion: Int,
        val dominantBranch: HeroPathBranch?,
        val counterArchetype: HeroPathCounterArchetype?,
        val relation: HeroPathMatchupRelation,
        val choiceStance: HeroPathChoiceStance,
        val opponentChoiceStance: HeroPathChoiceStance,
        val potencyBasisPoints: Int,
        val chargeAccelerationBasisPoints: Int,
    )

    private fun talentMatchupContext(
        actor: NormalizedBattleProjection,
        opponent: NormalizedBattleProjection,
    ): TalentMatchupContext {
        val actorSnapshot = actor.heroPathBattleSnapshot
        val opponentSnapshot = opponent.heroPathBattleSnapshot
        val dominantDefinition = actorSnapshot.dominantBranch?.let { HeroPathCatalog.byBranch[it] }
        val counterArchetype = dominantDefinition?.counterArchetype
        val choiceAcceleration = if (dominantDefinition?.effectFamily == HeroPathEffectFamily.FOCUSED_SHOT) 5_000 else 3_000
        val relation = if (
            actorSnapshot.counterRulesVersion == HERO_PATH_COUNTER_RULES_VERSION &&
            opponentSnapshot.counterRulesVersion == HERO_PATH_COUNTER_RULES_VERSION &&
            actorSnapshot.dominantBranch != null && opponentSnapshot.dominantBranch != null
        ) {
            HeroPathCatalog.matchupRelation(actorSnapshot.dominantBranch, opponentSnapshot.dominantBranch)
        } else {
            HeroPathMatchupRelation.NEUTRAL
        }
        var potency = 10_000
        var acceleration = 0
        if (relation == HeroPathMatchupRelation.FAVORABLE) {
            potency = 11_500
            acceleration = 1_500
            if (actorSnapshot.choiceStance == HeroPathChoiceStance.A) {
                potency += 3_500
                acceleration += choiceAcceleration
            }
            if (opponentSnapshot.choiceStance == HeroPathChoiceStance.B) {
                potency -= 3_500
                acceleration -= choiceAcceleration
            }
        }
        return TalentMatchupContext(
            counterRulesVersion = HERO_PATH_COUNTER_RULES_VERSION,
            dominantBranch = actorSnapshot.dominantBranch,
            counterArchetype = counterArchetype,
            relation = relation,
            choiceStance = actorSnapshot.choiceStance,
            opponentChoiceStance = opponentSnapshot.choiceStance,
            potencyBasisPoints = potency.coerceIn(8_000, 15_000),
            chargeAccelerationBasisPoints = acceleration.coerceIn(0, 6_500),
        )
    }

    private fun chooseAction(
        projection: NormalizedBattleProjection,
        hp: Int,
        opponentHp: Int,
        round: Int,
        side: BattleSide,
        seed: Long,
        lastSkillRounds: Map<String, Int>,
        finisherUsed: Boolean,
        morale: Int,
        talentCharge: Int,
        coreUsed: Boolean,
    ): PlannedAction {
        val availableSkills = projection.skills.filter { skill ->
            val lastRound = lastSkillRounds[skill.skillId] ?: Int.MIN_VALUE
            val pinnedSkills = projection.heroPathBattleSnapshot.ownedSkillIds
            (pinnedSkills.isEmpty() || skill.skillId in pinnedSkills) &&
                round.toLong() - lastRound.toLong() > skill.cooldownRounds.toLong()
        }
        val profile = projection.combatProfile
        val readyTalent = talentReadyNode(projection, hp, round, talentCharge, coreUsed, seed, side)
        if (readyTalent != null && availableSkills.isNotEmpty()) {
            val actualSkill = availableSkills.maxWithOrNull(
                compareBy<BattleSkillSnapshot> { it.powerBasisPoints + it.masteryLevel * 10 }
                    .thenBy { it.skillId },
            )!!
            return PlannedAction(
                kind = BattleActionKind.SKILL,
                skill = actualSkill,
                // Talent readiness unlocks its effect, not a guaranteed second damage multiplier.
                powerAttack = actualSkill.kind != BattleSkillKind.RECOVER &&
                    rollPowerAttack(projection, round, side, seed, morale),
                talentNodeId = readyTalent.nodeId,
            )
        }
        val finisherSkill = availableSkills
            .asSequence()
            .filter { it.finisherEligible && it.kind != BattleSkillKind.RECOVER }
            .maxWithOrNull(
                compareBy<BattleSkillSnapshot> { it.powerBasisPoints + it.masteryLevel * 10 }
                    .thenBy { it.skillId },
            )
        val finisherWindow = round >= 6 && (
            hp * 100 <= projection.maxHp * 45 ||
                opponentHp * 100 <= projection.maxHp * 35 ||
                round >= 8
            )
        if (!finisherUsed && finisherSkill != null && finisherWindow) {
            val lowHealthBonus = if (hp * 100 <= projection.maxHp * 45) 700 else 0
            val lateBonus = ((round - 7).coerceAtLeast(0) * 250).coerceAtMost(1_250)
            val finisherChance = (
                300 + profile.gamble * 25 + projection.stats.charisma / 10 +
                    lowHealthBonus + lateBonus - profile.stability * 6 +
                    (morale - 50) * 15
                ).coerceIn(500, 4_200)
            if (deterministicRoll(seed, round, side, lane = 7, bound = 10_000) < finisherChance) {
                return PlannedAction(
                    kind = BattleActionKind.SKILL,
                    skill = finisherSkill,
                    finisher = true,
                )
            }
        }
        if (round > FATIGUE_START_ROUND) {
            val offensiveSkills = availableSkills.filter { it.kind != BattleSkillKind.RECOVER }
            val fatigueSkill = if (offensiveSkills.isEmpty()) {
                null
            } else {
                offensiveSkills[
                    deterministicRoll(seed, round, side, lane = 12, bound = offensiveSkills.size)
                ]
            }
            return PlannedAction(
                kind = if (fatigueSkill == null) BattleActionKind.BASIC_ATTACK else BattleActionKind.SKILL,
                skill = fatigueSkill,
                powerAttack = true,
            )
        }
        var (attackWeight, skillWeight, guardWeight) = when (projection.guidance) {
            BattleGuidance.ASSAULT -> Triple(4_000, 5_000, 1_000)
            BattleGuidance.BALANCED -> Triple(4_500, 2_000, 3_500)
            BattleGuidance.GUARD -> Triple(3_000, 2_500, 4_500)
        }
        attackWeight += (profile.aggression - 50) * 30
        attackWeight += (morale - 50) * 18
        skillWeight += (projection.stats.intelligence / 100 - 50) * 10
        guardWeight += (profile.stability - 50) * 20 +
            (profile.longFight - 50) * 10 +
            (projection.stats.wisdom / 100 - 50) * 8 -
            (morale - 50) * 10
        if (round <= 4) attackWeight += (profile.shortFight - 50) * 18
        if (hp <= projection.maxHp / 3) {
            val shift = minOf(1_000, attackWeight)
            attackWeight -= shift
            guardWeight += shift
        }
        attackWeight = attackWeight.coerceAtLeast(250)
        skillWeight = skillWeight.coerceAtLeast(250)
        guardWeight = guardWeight.coerceAtLeast(250)
        if (availableSkills.isEmpty()) {
            attackWeight += skillWeight
            skillWeight = 0
        }
        val totalWeight = attackWeight + skillWeight + guardWeight
        val roll = deterministicRoll(seed, round, side, lane = 0, bound = totalWeight)
        val selected = when {
            roll < attackWeight -> PlannedAction(BattleActionKind.BASIC_ATTACK)
            roll < attackWeight + skillWeight -> {
                // Prefer skills that have not appeared yet (then the least recently used ones)
                // so a multi-turn battle showcases the actual loadout instead of repeating one
                // lucky deterministic pick.
                val oldestUse = availableSkills.minOf { skill ->
                    lastSkillRounds[skill.skillId] ?: Int.MIN_VALUE
                }
                val rotationSkills = availableSkills.filter { skill ->
                    (lastSkillRounds[skill.skillId] ?: Int.MIN_VALUE) == oldestUse
                }
                val skillIndex = deterministicRoll(
                    seed,
                    round,
                    side,
                    lane = 1,
                    bound = rotationSkills.size,
                )
                PlannedAction(BattleActionKind.SKILL, rotationSkills[skillIndex])
            }
            else -> PlannedAction(BattleActionKind.GUARD)
        }
        if (selected.kind == BattleActionKind.GUARD) return selected
        return selected.copy(powerAttack = rollPowerAttack(projection, round, side, seed, morale))
    }

    private fun rollPowerAttack(
        projection: NormalizedBattleProjection,
        round: Int,
        side: BattleSide,
        seed: Long,
        morale: Int,
    ): Boolean {
        val profile = projection.combatProfile
        val earlyBonus = if (round <= 4) (profile.shortFight - 50) * 12 else 0
        val powerAttackChance = (
            500 + profile.powerAttack * 30 + profile.aggression * 10 + earlyBonus +
                (morale - 50) * 18
            ).coerceIn(500, 5_000)
        return deterministicRoll(seed, round, side, lane = 6, bound = 10_000) < powerAttackChance
    }

    private fun resolveAction(
        actorSide: BattleSide,
        actor: NormalizedBattleProjection,
        defender: NormalizedBattleProjection,
        plan: PlannedAction,
        defenderPlan: PlannedAction,
        actorHp: Int,
        defenderHp: Int,
        round: Int,
        seed: Long,
        morale: Int,
    ): BattleRoundAction {
        if (plan.kind == BattleActionKind.GUARD) {
            return BattleRoundAction(
                actor = actorSide,
                kind = BattleActionKind.GUARD,
                resolution = BattleActionResolution.GUARDED,
            )
        }
        val skill = plan.skill
        if (plan.finisher && skill != null) {
            val successChance = (
                4_500 + skill.masteryLevel.coerceIn(0, MAX_MASTERY_LEVEL) * 15 +
                    primaryStat(actor) / 10 + actor.stats.wisdom / 17 + actor.stats.charisma / 25 -
                    defender.stats.dexterity / 20 - defender.stats.wisdom / 34 -
                    defender.combatProfile.stability * 8 + (morale - 50) * 10
                ).coerceIn(3_500, 8_500)
            val succeeded = deterministicRoll(seed, round, actorSide, lane = 8, bound = 10_000) < successChance
            if (!succeeded) {
                val backlashPercent = (12 + actor.combatProfile.gamble / 8).coerceIn(12, 24)
                return BattleRoundAction(
                    actor = actorSide,
                    kind = BattleActionKind.SKILL,
                    skillId = skill.skillId,
                    finisher = true,
                    finisherSucceeded = false,
                    selfDamage = (actor.maxHp * backlashPercent / 100)
                        .coerceIn(1, actorHp.coerceAtLeast(1)),
                    resolution = BattleActionResolution.MISSED,
                )
            }
            val finisherPercent = (
                25 + actor.combatProfile.powerAttack / 8 + skill.masteryLevel / 10
                ).coerceIn(25, 48)
            var finisherDamage = defender.maxHp * finisherPercent / 100
            if (defenderHp * 100 > defender.maxHp * 55) {
                finisherDamage = minOf(finisherDamage, (defenderHp - 1).coerceAtLeast(1))
            }
            if (defenderPlan.kind == BattleActionKind.GUARD) {
                finisherDamage = finisherDamage * 8_000 / 10_000
            }
            return BattleRoundAction(
                actor = actorSide,
                kind = BattleActionKind.SKILL,
                skillId = skill.skillId,
                damage = finisherDamage.coerceIn(1, defender.maxHp * 55 / 100),
                critical = true,
                finisher = true,
                finisherSucceeded = true,
                resolution = if (defenderPlan.kind == BattleActionKind.GUARD) {
                    BattleActionResolution.BLOCKED
                } else {
                    BattleActionResolution.HIT
                },
            )
        }
        if (skill?.kind == BattleSkillKind.RECOVER) {
            val focus = actor.stats.wisdom + actor.stats.charisma / 2
            val masteryBonus = skill.masteryLevel.coerceIn(0, MAX_MASTERY_LEVEL) * 5
            val scaledHealing = (60L + focus / 35L) *
                (skill.powerBasisPoints + masteryBonus).toLong() / 10_000L
            val lateDecay = (10_000 - (round - 8).coerceAtLeast(0) * 800).coerceAtLeast(4_000)
            val longFightBonus = (9_500 + actor.combatProfile.longFight * 10).coerceIn(9_500, 10_500)
            return BattleRoundAction(
                actor = actorSide,
                kind = BattleActionKind.SKILL,
                skillId = skill.skillId,
                healing = (scaledHealing * lateDecay * longFightBonus / 100_000_000L)
                    .toInt()
                    .coerceIn(1, MAX_HEALING),
                resolution = BattleActionResolution.RECOVERED,
            )
        }

        val skillKind = skill?.kind ?: BattleSkillKind.STRIKE
        val offense = offenseScore(actor, skillKind)
        var defense = defenseScore(defender, skillKind)
        if (skillKind == BattleSkillKind.PIERCE) defense /= 2
        val missChanceBp = (
            700 - actor.stats.dexterity / 10 + (50 - morale) * 10 -
                (actor.combatProfile.stability - 50) * 6
            ).coerceIn(150, 1_200)
        if (
            round <= FATIGUE_START_ROUND &&
            deterministicRoll(seed, round, actorSide, lane = 4, bound = 10_000) < missChanceBp
        ) {
            return BattleRoundAction(
                actor = actorSide,
                kind = plan.kind,
                skillId = skill?.skillId.orEmpty(),
                powerAttack = plan.powerAttack,
                resolution = BattleActionResolution.MISSED,
            )
        }
        if (round <= FATIGUE_START_ROUND && defenderPlan.kind != BattleActionKind.GUARD) {
            val evadeChanceBp = (
                500 + (defender.stats.dexterity - actor.stats.dexterity) / 8 +
                    (defender.combatProfile.stability - 50) * 5
                ).coerceIn(200, 1_500)
            if (deterministicRoll(seed, round, actorSide, lane = 5, bound = 10_000) < evadeChanceBp) {
                return BattleRoundAction(
                    actor = actorSide,
                    kind = plan.kind,
                    skillId = skill?.skillId.orEmpty(),
                    powerAttack = plan.powerAttack,
                    resolution = BattleActionResolution.EVADED,
                )
            }
        }

        val baseDamageBasisPoints = if (skill == null) {
            BASIC_ATTACK_DAMAGE_BASIS_POINTS
        } else {
            SKILL_ATTACK_DAMAGE_BASIS_POINTS
        }
        var damage = defender.maxHp.toLong() * baseDamageBasisPoints / 10_000L
        if (skill != null) {
            val skillPower = skill.powerBasisPoints +
                skill.masteryLevel.coerceIn(0, MAX_MASTERY_LEVEL) * 5
            damage = damage * skillPower.toLong() / NOMINAL_SKILL_POWER_BASIS_POINTS
        }
        val matchupBasisPoints = (
            10_000L + (offense - defense) / 6L
            ).coerceIn(8_500L, 11_500L)
        damage = damage * matchupBasisPoints / 10_000L
        if (skillKind == BattleSkillKind.CONTROL) damage = damage * 8_500L / 10_000L

        val latePressureBp = (
            10_000 + (round - 10).coerceAtLeast(0) * 150
            ).coerceAtMost(12_000)
        damage = damage * latePressureBp / 10_000L
        if (round > FATIGUE_START_ROUND) {
            val fatiguePressureBp = (
                10_000 + (round - FATIGUE_START_ROUND) * 1_000
                ).coerceAtMost(20_000)
            damage = damage * fatiguePressureBp / 10_000L
        }
        if (round <= 4) {
            val shortFightBp = (10_000 + (actor.combatProfile.shortFight - 50) * 20)
                .coerceIn(9_000, 11_000)
            damage = damage * shortFightBp / 10_000L
        }
        if (round >= 6) {
            val longFightDefenseBp = (10_000 - (defender.combatProfile.longFight - 50) * 12)
                .coerceIn(9_000, 11_000)
            damage = damage * longFightDefenseBp / 10_000L
        }
        val tempoBp = (9_000 + actor.stats.dexterity / 5).coerceIn(9_000, 10_500)
        damage = damage * tempoBp / 10_000L
        if (plan.powerAttack) {
            val powerAttackBp = (14_500 + actor.combatProfile.powerAttack * 40)
                .coerceIn(14_500, 18_500)
            damage = damage * powerAttackBp / 10_000L
        }

        val varianceBp = (1_800 - actor.combatProfile.stability * 12).coerceIn(600, 1_800)
        val varianceRoll = deterministicRoll(seed, round, actorSide, lane = 3, bound = varianceBp * 2 + 1)
        damage = damage * (10_000 - varianceBp + varianceRoll) / 10_000L

        val criticalChance = (500 + actor.stats.dexterity / 4).coerceIn(500, MAX_CRITICAL_BP)
        val critical = deterministicRoll(seed, round, actorSide, lane = 2, bound = 10_000) < criticalChance
        if (critical) damage = damage * CRITICAL_MULTIPLIER_BP / 10_000L
        if (defenderPlan.kind == BattleActionKind.GUARD) {
            damage = damage * GUARDED_DAMAGE_BP / 10_000L
        }
        // The only numeric growth channel in V0.1. HP, defense, healing, skill mastery and the
        // normalized build vector do not receive a second resonance multiplier.
        val resonanceDamageBasisPoints = 10_000L +
            (actor.growthResonanceBudget - BASE_BUILD_BUDGET).toLong() *
            GROWTH_DAMAGE_BASIS_POINTS_PER_RESONANCE
        damage = damage * resonanceDamageBasisPoints / 10_000L
        val moraleDamageBasisPoints = (10_000L + (morale - 50).toLong() * 12L)
            .coerceIn(9_400L, 10_600L)
        damage = damage * moraleDamageBasisPoints / 10_000L
        val damageCapPercent = when {
            plan.powerAttack && skill != null -> 55
            plan.powerAttack -> 30
            skill != null -> 45
            else -> 20
        }
        val damageCap = defender.maxHp * damageCapPercent / 100
        return BattleRoundAction(
            actor = actorSide,
            kind = plan.kind,
            skillId = skill?.skillId.orEmpty(),
            damage = damage.toInt().coerceIn(MIN_DAMAGE, minOf(MAX_DAMAGE, damageCap.coerceAtLeast(MIN_DAMAGE))),
            critical = critical,
            powerAttack = plan.powerAttack,
            resolution = if (defenderPlan.kind == BattleActionKind.GUARD) {
                BattleActionResolution.BLOCKED
            } else {
                BattleActionResolution.HIT
            },
        )
    }

    private fun talentReadyNode(
        projection: NormalizedBattleProjection,
        hp: Int,
        round: Int,
        charge: Int,
        coreUsed: Boolean,
        seed: Long,
        side: BattleSide,
    ) = if (charge < HERO_PATH_MAX_CLASS_CHARGE) {
        null
    } else {
        val candidates = projection.heroPathBattleSnapshot.nodes.asSequence()
            .filter { node ->
                when (node.slot) {
                    HeroPathNodeSlot.SPECIAL_A -> true
                    HeroPathNodeSlot.SPECIAL_B -> hp * 100 <= projection.maxHp * 60 || round >= 6
                    HeroPathNodeSlot.CORE -> !coreUsed && !isLethalSurvivalFamily(node.effectFamily) &&
                        (hp * 100 <= projection.maxHp * 45 || round >= 8)
                    else -> false
                }
            }
            .sortedBy { it.nodeId }
            .toList()
        candidates.firstOrNull { it.slot == HeroPathNodeSlot.CORE } ?: candidates.takeIf { it.isNotEmpty() }?.let {
            it[deterministicRoll(seed, round, side, lane = 14, bound = it.size)]
        }
    }

    private fun applyPlannedTalent(
        action: BattleRoundAction,
        plan: PlannedAction,
        actor: NormalizedBattleProjection,
        defender: NormalizedBattleProjection,
        runtime: TalentRuntimeState,
        matchup: TalentMatchupContext,
    ): BattleRoundAction {
        val node = actor.heroPathBattleSnapshot.nodes.firstOrNull { it.nodeId == plan.talentNodeId }
            ?: return action
        if (runtime.charge < HERO_PATH_MAX_CLASS_CHARGE) return action
        val before = runtime.charge
        runtime.charge = 0
        if (node.slot == HeroPathNodeSlot.CORE) runtime.coreUsed = true
        val landed = action.resolution == BattleActionResolution.HIT || action.resolution == BattleActionResolution.BLOCKED
        var extraDamage = 0
        var counterDamage = 0
        var bonusHealing = 0
        var resourceRemoved = 0
        var extraOrdinal = 0
        val advancedRank = rankForSlot(actor, HeroPathNodeSlot.ADVANCED_TACTIC)
        when (node.effectFamily) {
            HeroPathEffectFamily.IMPACT_GUARD,
            HeroPathEffectFamily.RETRIBUTIVE_COUNTER -> if (landed) {
                val counterPercent = 20 + advancedRank * 3
                counterDamage = minOf((action.damage * counterPercent / 100).coerceAtLeast(1), defender.maxHp * 8 / 100)
            }
            HeroPathEffectFamily.GRACEFUL_RECOVERY,
            HeroPathEffectFamily.OATHED_GUARD,
            HeroPathEffectFamily.DAWN_CYCLE,
            HeroPathEffectFamily.EVASIVE_CHAIN,
            HeroPathEffectFamily.PROVIDENT_REVERSAL -> {
                val flatHealPercent = when (node.effectFamily) {
                    HeroPathEffectFamily.PROVIDENT_REVERSAL -> 1
                    HeroPathEffectFamily.DAWN_CYCLE -> 2
                    HeroPathEffectFamily.EVASIVE_CHAIN,
                    HeroPathEffectFamily.GRACEFUL_RECOVERY -> 15
                    else -> 5
                }
                bonusHealing = minOf(
                    if (action.healing > 0) (action.healing * (25 + advancedRank * 5) / 100).coerceAtLeast(1) else actor.maxHp * flatHealPercent / 100,
                    actor.maxHp * 15 / 100,
                )
            }
            HeroPathEffectFamily.DECEPTIVE_CONTROL,
            HeroPathEffectFamily.CONTROLLED_HUNT,
            HeroPathEffectFamily.FORBIDDEN_GAMBIT,
            HeroPathEffectFamily.ARCANE_CYCLE -> {
                resourceRemoved = if (landed) 1 else 0
                val controlBasePercent = when (node.effectFamily) {
                    HeroPathEffectFamily.DECEPTIVE_CONTROL -> 35
                    HeroPathEffectFamily.CONTROLLED_HUNT -> 120
                    HeroPathEffectFamily.FORBIDDEN_GAMBIT -> 10
                    else -> 18
                }
                val controlCapPercent = when (node.effectFamily) {
                    HeroPathEffectFamily.DECEPTIVE_CONTROL -> 10
                    HeroPathEffectFamily.CONTROLLED_HUNT -> 25
                    HeroPathEffectFamily.FORBIDDEN_GAMBIT -> 4 + advancedRank * 2
                    else -> 8
                }
                val controlScaling = if (node.effectFamily == HeroPathEffectFamily.FORBIDDEN_GAMBIT) advancedRank * 10 else advancedRank * 3
                if (landed && controlCapPercent > 0) extraDamage = minOf(
                    (action.damage * (controlBasePercent + controlScaling) / 100).coerceAtLeast(1),
                    defender.maxHp * controlCapPercent / 100,
                )
            }
            else -> if (landed && runtime.extraActionsUsed < MAX_TALENT_EXTRA_ACTIONS) {
                runtime.extraActionsUsed += 1
                extraOrdinal = runtime.extraActionsUsed
                val burstBasePercent = when (node.effectFamily) {
                    HeroPathEffectFamily.RAGE_BURST -> 20
                    HeroPathEffectFamily.MORALE_COMMAND -> 120
                    HeroPathEffectFamily.FOCUSED_SHOT -> 400
                    HeroPathEffectFamily.MOBILE_VOLLEY -> 350
                    else -> 30
                }
                val burstCapPercent = when (node.effectFamily) {
                    HeroPathEffectFamily.RAGE_BURST -> 10
                    HeroPathEffectFamily.MORALE_COMMAND -> 35
                    HeroPathEffectFamily.FOCUSED_SHOT -> 45
                    HeroPathEffectFamily.MOBILE_VOLLEY -> 40
                    else -> 12
                }
                extraDamage = minOf(
                    (action.damage * (burstBasePercent + advancedRank * 5) / 100).coerceAtLeast(1),
                    defender.maxHp * burstCapPercent / 100,
                )
            }
        }
        extraDamage = scaleByBasisPoints(extraDamage, matchup.potencyBasisPoints)
        counterDamage = scaleByBasisPoints(counterDamage, matchup.potencyBasisPoints)
        bonusHealing = scaleByBasisPoints(bonusHealing, matchup.potencyBasisPoints)
        if (matchup.relation == HeroPathMatchupRelation.FAVORABLE) {
            val pressureBasisPoints = (matchup.potencyBasisPoints - 10_000).coerceAtLeast(0)
            // Recovery-family effects are independent of the attack landing. Offensive pressure,
            // including the fortress bonus, must not turn a miss, evade or recovery into a hit.
            when {
                bonusHealing > 0 || matchup.counterArchetype == HeroPathCounterArchetype.SUSTAIN -> {
                    val pressure = actor.maxHp * pressureBasisPoints / 200_000
                    bonusHealing = maxOf(bonusHealing, pressure)
                }
                landed && (counterDamage > 0 || matchup.counterArchetype == HeroPathCounterArchetype.FORTRESS) -> {
                    val pressure = defender.maxHp * pressureBasisPoints / 200_000
                    counterDamage = maxOf(counterDamage, pressure)
                }
                landed -> {
                    val pressure = defender.maxHp * pressureBasisPoints / 200_000
                    extraDamage = maxOf(extraDamage, pressure)
                }
            }
        }
        extraDamage = extraDamage.coerceAtMost(defender.maxHp * 45 / 100)
        counterDamage = counterDamage.let { scaled ->
            val remaining = (defender.maxHp * 25 / 100 - runtime.counterDamageDealt).coerceAtLeast(0)
            minOf(scaled, defender.maxHp * 10 / 100, remaining)
        }
        bonusHealing = bonusHealing.let { scaled ->
            val remaining = (actor.maxHp * 30 / 100 - runtime.bonusHealingDone).coerceAtLeast(0)
            minOf(scaled, actor.maxHp * 15 / 100, remaining)
        }
        runtime.counterDamageDealt += counterDamage
        runtime.bonusHealingDone += bonusHealing
        val trace = BattleTalentEffectTrace(
            sourceNodeId = node.nodeId, effectFamily = node.effectFamily,
            effectStage = node.effectStage, triggerDepth = 0, extraActionOrdinal = extraOrdinal,
            extraDamage = extraDamage, counterDamage = counterDamage, bonusHealing = bonusHealing,
            resourceRemoved = resourceRemoved, chargeBefore = before, chargeAfter = runtime.charge,
            counterRulesVersion = matchup.counterRulesVersion,
            dominantBranch = matchup.dominantBranch,
            matchupRelation = matchup.relation,
            choiceStance = matchup.choiceStance,
            opponentChoiceStance = matchup.opponentChoiceStance,
            matchupPotencyBasisPoints = matchup.potencyBasisPoints,
            chargeAccelerationBasisPoints = matchup.chargeAccelerationBasisPoints,
        )
        return action.copy(
            damage = (action.damage + extraDamage + counterDamage).coerceAtMost(defender.maxHp * 60 / 100),
            healing = (action.healing + bonusHealing).coerceAtMost(MAX_HEALING + actor.maxHp * 15 / 100),
            talentEffects = action.talentEffects + trace,
        )
    }

    private fun lethalSurvival(
        projection: NormalizedBattleProjection,
        runtime: TalentRuntimeState,
        matchup: TalentMatchupContext,
    ): BattleTalentEffectTrace? {
        if (runtime.coreUsed || runtime.charge < HERO_PATH_MAX_CLASS_CHARGE) return null
        val core = projection.heroPathBattleSnapshot.nodes.firstOrNull { node ->
            node.nodeId == projection.heroPathBattleSnapshot.activeCoreNodeId &&
                isLethalSurvivalFamily(node.effectFamily)
        } ?: return null
        val before = runtime.charge
        runtime.charge = 0
        runtime.coreUsed = true
        return BattleTalentEffectTrace(
            sourceNodeId = core.nodeId, effectFamily = core.effectFamily,
            effectStage = HeroPathEffectStage.ROUND_END, triggerDepth = 0,
            lethalSurvival = true, chargeBefore = before, chargeAfter = 0,
            counterRulesVersion = matchup.counterRulesVersion,
            dominantBranch = matchup.dominantBranch,
            matchupRelation = matchup.relation,
            choiceStance = matchup.choiceStance,
            opponentChoiceStance = matchup.opponentChoiceStance,
            matchupPotencyBasisPoints = matchup.potencyBasisPoints,
            chargeAccelerationBasisPoints = matchup.chargeAccelerationBasisPoints,
        )
    }

    private fun isLethalSurvivalFamily(family: HeroPathEffectFamily) = family in setOf(
        HeroPathEffectFamily.IMPACT_GUARD,
        HeroPathEffectFamily.GRACEFUL_RECOVERY,
        HeroPathEffectFamily.PROVIDENT_REVERSAL,
        HeroPathEffectFamily.OATHED_GUARD,
    )

    private fun updateClassCharge(
        runtime: TalentRuntimeState,
        actor: NormalizedBattleProjection,
        actorAction: BattleRoundAction,
        opponentAction: BattleRoundAction,
        damageTaken: Int,
        round: Int,
        seed: Long,
        side: BattleSide,
        matchup: TalentMatchupContext,
    ) {
        val foundationA = rankForSlot(actor, HeroPathNodeSlot.FOUNDATION_A)
        val foundationB = rankForSlot(actor, HeroPathNodeSlot.FOUNDATION_B)
        val gain = when (actor.heroClass) {
            BattleHeroClass.WARRIOR -> damageTaken * 100 >= actor.maxHp * (11 - foundationA) || opponentAction.resolution == BattleActionResolution.BLOCKED
            BattleHeroClass.ROGUE -> actorAction.damage > 0 || opponentAction.resolution == BattleActionResolution.EVADED ||
                (foundationA >= 1 && actorAction.kind == BattleActionKind.SKILL) ||
                (foundationA >= 2 && actorAction.kind == BattleActionKind.BASIC_ATTACK)
            BattleHeroClass.RANGER -> actorAction.damage > 0 ||
                (foundationA >= 1 && actorAction.kind == BattleActionKind.SKILL) ||
                (foundationA >= 2 && actorAction.kind == BattleActionKind.BASIC_ATTACK)
            BattleHeroClass.MAGE -> actorAction.kind == BattleActionKind.GUARD ||
                (actorAction.kind == BattleActionKind.SKILL &&
                    actorAction.resolution !in setOf(BattleActionResolution.MISSED, BattleActionResolution.EVADED)) ||
                (actorAction.kind == BattleActionKind.BASIC_ATTACK && actorAction.damage > 0 &&
                    (foundationA >= 2 || foundationA >= 1 && (actorAction.critical || actorAction.powerAttack)))
            BattleHeroClass.CLERIC -> actorAction.kind == BattleActionKind.SKILL || actorAction.kind == BattleActionKind.GUARD ||
                (foundationA >= 1 && damageTaken * 100 >= actor.maxHp * (20 - foundationA * 5))
            BattleHeroClass.PALADIN -> opponentAction.resolution == BattleActionResolution.BLOCKED ||
                actorAction.kind == BattleActionKind.GUARD || actorAction.kind == BattleActionKind.SKILL ||
                (actorAction.kind == BattleActionKind.BASIC_ATTACK && actorAction.damage > 0 &&
                    (foundationA >= 2 || foundationA >= 1 && actorAction.powerAttack))
        }
        val rogueLossRoll = deterministicRoll(seed, round, side, lane = 15, bound = 10_000)
        val rogueStrongImpact = damageTaken * 100 >= actor.maxHp * 25
        val rogueLostRhythm = actor.heroClass == BattleHeroClass.ROGUE && damageTaken > 0 && when (foundationB) {
            0 -> true
            1 -> rogueStrongImpact || rogueLossRoll < 5_000
            else -> false
        }
        if (rogueLostRhythm) {
            runtime.charge = (runtime.charge - 1).coerceAtLeast(0)
        } else if (actor.heroClass == BattleHeroClass.RANGER && damageTaken * 100 >= actor.maxHp * (25 + foundationB * 2)) {
            runtime.charge = 0
        } else if (gain && deterministicRoll(seed, round, side, lane = 20, bound = 10_000) < when (actor.heroClass) {
                BattleHeroClass.MAGE -> 7_100
                BattleHeroClass.CLERIC -> 8_100
                BattleHeroClass.PALADIN -> 7_300
                else -> 10_000
            }) {
            val rangerAccelerationBasisPoints = when (
                actor.heroPathBattleSnapshot.dominantBranch?.let { HeroPathCatalog.byBranch[it]?.effectFamily }
            ) {
                HeroPathEffectFamily.MOBILE_VOLLEY,
                HeroPathEffectFamily.CONTROLLED_HUNT -> 3_000
                else -> if (actor.heroPathBattleSnapshot.nodes.any {
                        it.effectFamily == HeroPathEffectFamily.FOCUSED_SHOT
                    }) 1_500 else 0
            }
            val focusedAcceleration = actor.heroClass == BattleHeroClass.RANGER &&
                deterministicRoll(seed, round, side, lane = 16, bound = 10_000) < rangerAccelerationBasisPoints
            val matchupAcceleration = matchup.chargeAccelerationBasisPoints > 0 &&
                deterministicRoll(seed, round, side, lane = 17, bound = 10_000) < matchup.chargeAccelerationBasisPoints
            val gainAmount = if (focusedAcceleration || matchupAcceleration) 2 else 1
            runtime.charge = (runtime.charge + gainAmount).coerceAtMost(HERO_PATH_MAX_CLASS_CHARGE)
        }
        val spentSpecialCharge = actorAction.talentEffects.any {
            it.chargeBefore == HERO_PATH_MAX_CLASS_CHARGE && it.chargeAfter == 0 && !it.lethalSurvival
        }
        val recoversAfterSpecial = actor.heroClass !in setOf(BattleHeroClass.ROGUE, BattleHeroClass.RANGER) &&
            foundationB > 0 && spentSpecialCharge &&
            deterministicRoll(seed, round, side, lane = 19, bound = 10_000) < foundationB * 2_500
        if (recoversAfterSpecial) {
            runtime.charge = (runtime.charge + 1).coerceAtMost(HERO_PATH_MAX_CLASS_CHARGE)
        }
    }

    private fun scaleByBasisPoints(value: Int, basisPoints: Int): Int =
        (value.toLong() * basisPoints.toLong() / 10_000L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    private fun rankForSlot(projection: NormalizedBattleProjection, slot: HeroPathNodeSlot): Int {
        val cap = when (slot) {
            HeroPathNodeSlot.FOUNDATION_A,
            HeroPathNodeSlot.FOUNDATION_B,
            HeroPathNodeSlot.ADVANCED_TACTIC -> 2
            else -> 1
        }
        return projection.heroPathBattleSnapshot.nodes.filter { it.slot == slot }.sumOf { it.rank }.coerceAtMost(cap)
    }

    private fun offenseScore(
        projection: NormalizedBattleProjection,
        skillKind: BattleSkillKind,
    ): Long {
        val stats = projection.stats
        if (skillKind == BattleSkillKind.ARCANE || skillKind == BattleSkillKind.CONTROL) {
            return stats.intelligence * 2L + stats.wisdom + stats.charisma / 2L
        }
        if (skillKind == BattleSkillKind.PIERCE) {
            return stats.dexterity * 2L + primaryStat(projection) + stats.strength / 2L
        }
        return primaryStat(projection) * 2L + secondaryStat(projection) + stats.dexterity / 2L
    }

    private fun defenseScore(
        projection: NormalizedBattleProjection,
        incomingKind: BattleSkillKind,
    ): Long = if (incomingKind == BattleSkillKind.ARCANE || incomingKind == BattleSkillKind.CONTROL) {
        projection.stats.wisdom * 2L + projection.stats.intelligence + projection.stats.charisma / 2L
    } else {
        projection.stats.constitution * 2L + projection.stats.wisdom + projection.stats.strength / 2L
    }

    private fun primaryStat(projection: NormalizedBattleProjection): Int = when (projection.heroClass) {
        BattleHeroClass.WARRIOR, BattleHeroClass.PALADIN -> projection.stats.strength
        BattleHeroClass.ROGUE, BattleHeroClass.RANGER -> projection.stats.dexterity
        BattleHeroClass.MAGE -> projection.stats.intelligence
        BattleHeroClass.CLERIC -> projection.stats.wisdom
    }

    private fun secondaryStat(projection: NormalizedBattleProjection): Int = when (projection.heroClass) {
        BattleHeroClass.WARRIOR -> projection.stats.constitution
        BattleHeroClass.ROGUE -> projection.stats.strength
        BattleHeroClass.RANGER -> projection.stats.wisdom
        BattleHeroClass.MAGE -> projection.stats.wisdom
        BattleHeroClass.CLERIC -> projection.stats.charisma
        BattleHeroClass.PALADIN -> projection.stats.charisma
    }

    private fun deterministicRoll(
        seed: Long,
        round: Int,
        side: BattleSide,
        lane: Int,
        bound: Int,
    ): Int {
        if (bound <= 1) return 0
        val sideSalt = if (side == BattleSide.USER) USER_SIDE_SALT else OPPONENT_SIDE_SALT
        val mixed = mix64(
            seed + round.toLong() * ROUND_SALT + sideSalt + lane.toLong() * LANE_SALT,
        )
        return ((mixed ushr 1) % bound.toLong()).toInt()
    }

    private fun stableHash(value: String): Long {
        var hash = FNV_OFFSET_BASIS
        for (character in value) {
            hash = hash xor character.code.toLong()
            hash *= FNV_PRIME
        }
        return hash
    }

    private fun mix64(input: Long): Long {
        var value = input + MIX_GAMMA
        value = (value xor (value ushr 30)) * MIX_MULTIPLIER_1
        value = (value xor (value ushr 27)) * MIX_MULTIPLIER_2
        return value xor (value ushr 31)
    }

    private fun Long.rotateLeft(bits: Int): Long =
        (this shl bits) or (this ushr (Long.SIZE_BITS - bits))

    private fun safeIncrement(value: Int): Int = if (value == Int.MAX_VALUE) value else value + 1

    private const val MAX_SKILLS = 4
    private const val MAX_EQUIPMENT_SLOTS = 6
    private const val BASE_BUILD_BUDGET = 100
    /** One displayed resonance point adds 1.5% in the single outgoing-damage channel. */
    private const val GROWTH_DAMAGE_BASIS_POINTS_PER_RESONANCE = 200L
    private const val MIN_SKILL_POWER_BP = 8_000
    private const val MAX_SKILL_POWER_BP = 16_000
    private const val NOMINAL_SKILL_POWER_BASIS_POINTS = 12_000L
    private const val MAX_MASTERY_LEVEL = 100
    private const val MAX_SKILL_COOLDOWN_ROUNDS = 100
    private const val MIN_DAMAGE = 20
    private const val MAX_DAMAGE = 800
    private const val MAX_HEALING = 220
    private const val MAX_TALENT_EXTRA_ACTIONS = 3
    private const val MAX_BATTLE_ROUNDS = 40
    private const val MIN_PROJECTION_HP = 850
    private const val MAX_PROJECTION_HP = 1_400
    private const val MIN_MORALE = 0
    private const val MAX_MORALE = 100
    private const val MAX_CRITICAL_BP = 2_500
    private const val CRITICAL_MULTIPLIER_BP = 13_000L
    private const val GUARDED_DAMAGE_BP = 4_500L
    private const val ELO_SCALE = 400.0
    private const val MAX_ELO_EXPONENT = 4.0

    private const val FNV_OFFSET_BASIS = -3_750_763_034_362_895_579L
    private const val FNV_PRIME = 1_099_511_628_211L
    private const val MIX_GAMMA = -7_046_029_254_386_353_131L
    private const val MIX_MULTIPLIER_1 = -4_658_895_280_553_007_687L
    private const val MIX_MULTIPLIER_2 = -7_723_592_293_110_705_685L
    private const val ROUND_SALT = 6_364_136_223_846_793_005L
    private const val LANE_SALT = 1_442_695_040_888_963_407L
    private const val USER_SIDE_SALT = 2_862_933_555_777_941_757L
    private const val OPPONENT_SIDE_SALT = 3_037_000_493L
}
