package com.nullplaying.ui

import com.nullplaying.engine.BattleQaMatchFactory
import com.nullplaying.engine.PublicPlayerBattleDerivation
import com.nullplaying.engine.ProjectionBattleEngine
import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.engine.SkillCatalog
import com.nullplaying.engine.arena.ArenaAttackInput
import com.nullplaying.engine.arena.ArenaOpponentSource
import com.nullplaying.engine.arena.ArenaProgressionCatalog
import com.nullplaying.engine.arena.ArenaCharacterPointRules
import com.nullplaying.engine.arena.ArenaMatchmakingProfile
import com.nullplaying.engine.arena.ArenaAdaptiveMatchmaking
import com.nullplaying.engine.arena.ArenaProgressionRules
import com.nullplaying.engine.arena.ArenaProgressionState
import com.nullplaying.engine.arena.ArenaSupportTraitRank
import com.nullplaying.engine.arena.ArenaSupportCatalog
import com.nullplaying.engine.arena.ArenaCoreStats
import com.nullplaying.engine.arena.ArenaFighterInput
import com.nullplaying.engine.arena.ArenaRunStatus
import com.nullplaying.engine.arena.ArenaSupportEventType
import com.nullplaying.engine.arena.ArenaSupportInput
import com.nullplaying.engine.arena.ArenaSupportResult
import com.nullplaying.engine.arena.ArenaSupportTurnEngine
import com.nullplaying.engine.arena.withIdentityRules
import com.nullplaying.engine.arena.withIdentityOpponentRules
import com.nullplaying.engine.arena.ArenaTurnInputAdapter
import com.nullplaying.engine.arena.ArenaSkillNodeKind
import com.nullplaying.engine.arena.ArenaSkillTreeCatalog
import com.nullplaying.engine.arena.ArenaSkillTreeRules
import com.nullplaying.engine.arena.ArenaSkillTreeState
import com.nullplaying.engine.arena.ArenaSyntheticProfileGrowth
import com.nullplaying.engine.arena.ArenaAutoBuildPreset
import com.nullplaying.engine.arena.ArenaAutoBuildProfiles
import com.nullplaying.engine.arena.arenaAutoBuildAllocationSeed
import com.nullplaying.engine.arena.arenaAutoBuildPresetIdentitySeed
import com.nullplaying.localization.AppLanguage
import com.nullplaying.model.BattleBuildStats
import com.nullplaying.model.BattleOutcome
import com.nullplaying.model.BattleProjectionSnapshot
import com.nullplaying.model.BattleRound
import com.nullplaying.model.BattleSeasonStanding
import com.nullplaying.model.BattleSkillSnapshot
import com.nullplaying.model.BattleTicketState
import com.nullplaying.model.HeroClass
import com.nullplaying.model.HeroPathBattleSnapshot
import com.nullplaying.model.LearnedSkill
import com.nullplaying.model.NormalizedBattleProjection
import com.nullplaying.model.ProjectionBattleResult
import com.nullplaying.model.SimpleGameState
import com.nullplaying.remote.BattleQaNarrative
import com.nullplaying.remote.BattleQaScene
import kotlin.math.ceil

/**
 * Builds an arena-only future-level projection for the Battle QA variant.
 *
 * The saved state is never mutated. Existing learned skills keep their mastery, while the
 * deterministic class skills that normal progression would have granted by [targetLevel] are
 * filled into the copied snapshot. A character already above the requested level is never
 * down-scaled because its earlier stat rolls cannot be reconstructed faithfully.
 */
internal fun projectArenaStateForQa(state: SimpleGameState, targetLevel: Int): SimpleGameState {
    require(targetLevel in 0..100) { "QA arena level override must be 0..100" }
    if (targetLevel == 0 || state.hero.level >= targetLevel) {
        return state.copy(
            hero = state.hero.copy(stats = state.hero.stats.copy()),
            skills = state.skills.map { it.copy() }.toMutableList(),
        )
    }
    val currentLevel = state.hero.level.coerceIn(1L, 100L)
    val effectiveLevel = targetLevel.toLong()
    val projectedStats = state.hero.stats.copy()
    val levelDelta = (effectiveLevel - currentLevel).toInt()
    val engine = SimpleGameEngine()
    val projectedRngState = ArenaSyntheticProfileGrowth.grow(
        engine = engine,
        stats = projectedStats,
        heroClass = state.hero.heroClass,
        fromLevel = currentLevel,
        targetLevel = effectiveLevel,
        identitySeed = state.rngState,
    )

    val classSkills = SkillCatalog.forClass(state.hero.heroClass)
    val existingByTier = state.skills.mapNotNull { learned ->
        val definition = learned.catalogId.takeIf { it.isNotBlank() }?.let(SkillCatalog::find)
            ?: classSkills.singleOrNull { it.name == learned.name }
        definition?.takeIf {
            it.heroClass == state.hero.heroClass && it.unlockLevel.toLong() <= effectiveLevel
        }?.let { definition ->
            val tier = if (definition.unlockLevel == 1) 1 else definition.unlockLevel / 5 + 1
            tier to learned.copy(
                name = definition.name,
                description = definition.description,
                catalogId = definition.catalogId,
            )
        }
    }.distinctBy { it.first }.toMap()
    val highestTier = if (effectiveLevel <= 1L) 1 else (effectiveLevel / 5L + 1L).toInt().coerceAtMost(20)
    val projectedSkills = (1..highestTier).map { tier ->
        existingByTier[tier] ?: SkillCatalog.select(state.skillCatalogSeed, state.hero.heroClass, tier).let { definition ->
            LearnedSkill(
                id = tier,
                name = definition.name,
                acquiredAtLevel = if (tier == 1) 1L else (tier - 1L) * 5L,
                description = definition.description,
                catalogId = definition.catalogId,
            )
        }
    }.toMutableList()

    return state.copy(
        hero = state.hero.copy(level = effectiveLevel, stats = projectedStats),
        skills = projectedSkills,
        classGuidedLevelGrowths = if (state.classGuidedLevelGrowths > Long.MAX_VALUE - levelDelta) {
            Long.MAX_VALUE
        } else {
            state.classGuidedLevelGrowths + levelDelta
        },
        rngState = projectedRngState,
    )
}

/** The complete, immutable source of the normal arena's playback and result. */
internal data class ArenaLiveBattle(
    val user: ArenaSupportInput,
    val opponent: ArenaSupportInput,
    val simulation: ArenaSupportResult,
    val progressionRevision: Long = 0L,
    val issuedDay: Long = 0L,
    val growthEligible: Boolean = false,
    val ignoreHeroLevelGate: Boolean = false,
    /** Null only for retained V2 replay/test callers. */
    val skillTreeRevision: Long? = null,
    val skillTreeCatalogVersion: Int = 0,
    val skillTreeRulesVersion: String = "",
    val matchmakingProfile: ArenaMatchmakingProfile? = null,
)

/**
 * Local arena entry boundary. No old projection combat, talents, or maxed QA fixture is used.
 *
 * All automatically learned class supports are frozen at entry. User traits
 * come only from the saved allocation; NPC budgets use earned level, never spent points.
 * Existing score/ticket rules are retained; these pure transitions do not save or spend anything.
 */
internal fun prepareArenaBattle(
    state: SimpleGameState,
    match: BattleQaMatchFactory.Match,
    standing: BattleSeasonStanding,
    tickets: BattleTicketState,
    playerStance: BattleStance,
    language: AppLanguage,
    progression: ArenaProgressionState? = null,
    ignoreHeroLevelGate: Boolean = false,
    ownedSupportIds: Set<String> = ArenaSupportCatalog.unlockedIds(state.hero.heroClass,
        maxOf(state.hero.level, if (ignoreHeroLevelGate) 10L else 1L)),
    skillTree: ArenaSkillTreeState? = null,
    preparedOpponent: ArenaSupportInput? = null,
    preparedOpponentSource: ArenaOpponentSource? = null,
    matchmakingProfile: ArenaMatchmakingProfile? = null,
): Pair<BattlePreviewResult, BattleQaNarrative>? {
    val activeProgression = progression ?: ArenaProgressionRules.initialize(
        ArenaProgressionState(), state.hero.level, ignoreHeroLevelGate)
    if (state.hero.level < (if (ignoreHeroLevelGate) 1L else 10L) || !activeProgression.unlocked ||
        !(if (activeProgression.characterLevelPoints) ArenaProgressionRules.isValid(activeProgression)
            else ArenaProgressionRules.isValidForFighter(activeProgression, state.hero.heroClass,
                state.hero.level, ownedSupportIds, ignoreHeroLevelGate)) || activeProgression.pending != null) return null
    if (ownedSupportIds.any { id -> ArenaSupportCatalog.find(id)?.let {
            it.heroClass != state.hero.heroClass ||
                (it.unlockLevel > state.hero.level && !(ignoreHeroLevelGate && it.unlockLevel <= 10))
        } != false }) return null
    if ((preparedOpponent == null) != (preparedOpponentSource == null)) return null
    if (matchmakingProfile != null && (
            preparedOpponent == null || matchmakingProfile.rulesVersion !in 1..ArenaAdaptiveMatchmaking.RULES_VERSION ||
                matchmakingProfile.heroLevel != state.hero.level ||
                matchmakingProfile.heroPower != match.userEffectiveCombatPower ||
                matchmakingProfile.referencePower <= 0L || matchmakingProfile.targetPower <= 0L ||
                matchmakingProfile.sampleCount !in 0..ArenaAdaptiveMatchmaking.HISTORY_LIMIT ||
                matchmakingProfile.adjustmentPermille !in -100..100 ||
                matchmakingProfile.recentStreak !in -10..10 ||
                matchmakingProfile.levelSearchRadius !in 1..2 ||
                (matchmakingProfile.rulesVersion == ArenaAdaptiveMatchmaking.RULES_VERSION &&
                    (matchmakingProfile.heroPower !in 1L..ArenaAdaptiveMatchmaking.MAX_LOCAL_REFERENCE_POWER ||
                        matchmakingProfile.levelSearchRadius != 1 ||
                        (preparedOpponentSource == ArenaOpponentSource.LOCAL_RESERVE &&
                            (match.request.opponent.level != state.hero.level ||
                                match.opponentEffectiveCombatPower !in ArenaAdaptiveMatchmaking.localPowerBounds(matchmakingProfile))))) ||
                match.request.opponent.level !in maxOf(10L, state.hero.level - matchmakingProfile.levelSearchRadius)..
                    (state.hero.level + matchmakingProfile.levelSearchRadius) ||
                (matchmakingProfile.levelSearchRadius == 2 &&
                    (preparedOpponentSource != ArenaOpponentSource.LOCAL_RESERVE || matchmakingProfile.sampleCount < 5 ||
                        kotlin.math.abs(matchmakingProfile.recentStreak) < 3 ||
                        matchmakingProfile.recentStreak * matchmakingProfile.adjustmentPermille <= 0)) ||
                match.request.opponentReferenceScore != ArenaAdaptiveMatchmaking.referenceScore(
                    matchmakingProfile, standing.score, match.request.opponent.level, match.opponentEffectiveCombatPower,
                )
            )) return null

    val userId = match.request.user.projectionId.ifBlank { "arena-user" }
    val opponentId = match.request.opponent.projectionId
        .takeIf { it.isNotBlank() && it != userId } ?: "arena-opponent:$userId"
    if (activeProgression.characterLevelPoints && skillTree == null) return null
    val arenaLevel = if (activeProgression.characterLevelPoints)
        ArenaCharacterPointRules.combatBudget(state.hero.level, ignoreHeroLevelGate)
        else ArenaProgressionRules.levelFromXp(activeProgression.totalXp)
    val user = if (skillTree == null) {
        val built = ArenaTurnInputAdapter.fromStateForCombatPower(
            state = state,
            id = userId,
            effectiveCombatPower = match.userEffectiveCombatPower,
            allowLowLevelQa = ignoreHeroLevelGate,
        ) ?: return null
        ArenaSupportInput(
            fighter = built.fighter,
            supportIds = ownedSupportIds.toSet(),
            arenaLevel = arenaLevel,
            arenaClassBalanceEnabled = true,
            traits = ArenaProgressionRules.toSupportTraits(activeProgression),
        )
    } else {
        val ownedAttackIds = ArenaTurnInputAdapter.ownedAttackIds(state)
        val treeView = runCatching {
            ArenaSkillTreeRules.view(skillTree, arenaLevel, ownedAttackIds)
        }.getOrNull() ?: return null
        if (!treeView.valid || treeView.availablePoints != 0) return null
        val ranks = skillTree.allocations.associate { it.nodeId to it.rank }
        val attackRanks = ranks.filterKeys { ArenaSkillTreeCatalog.find(it)?.kind == ArenaSkillNodeKind.ATTACK }
        val supportRanks = ranks.filterKeys { ArenaSkillTreeCatalog.find(it)?.kind == ArenaSkillNodeKind.SUPPORT }
        val built = ArenaTurnInputAdapter.fromStateForCombatPower(
            state = state,
            id = userId,
            effectiveCombatPower = match.userEffectiveCombatPower,
            attackRanks = attackRanks,
            allowLowLevelQa = ignoreHeroLevelGate,
        ) ?: return null
        if (built.rejectedSkills.isNotEmpty()) return null
        ArenaSupportInput(
            fighter = built.fighter,
            supportIds = supportRanks.keys,
            arenaLevel = arenaLevel,
            arenaClassBalanceEnabled = true,
            traits = emptyList(),
            supportRanks = supportRanks,
        )
    }
    val identityUser = if (skillTree != null) user.withIdentityRules() else user
    val opponent = if (preparedOpponent == null) {
        arenaLocalOpponent(match, opponentId,
            if (activeProgression.characterLevelPoints) ArenaCharacterPointRules.combatBudget(
                match.request.opponent.level, ignoreHeroLevelGate) else user.arenaLevel, ignoreHeroLevelGate,
            skillTreeMode = skillTree != null)
    } else {
        preparedOpponent.takeIf {
            it.fighter.id == opponentId &&
                it.fighter.heroClass.name == match.request.opponent.heroClass.name &&
                it.fighter.level == match.request.opponent.level &&
                it.arenaLevel == if (activeProgression.characterLevelPoints)
                    ArenaCharacterPointRules.combatBudget(it.fighter.level, ignoreHeroLevelGate) else user.arenaLevel
        } ?: return null
    }
    val identityOpponent = if (skillTree != null) opponent.withIdentityOpponentRules() else opponent
    val simulation = ArenaSupportTurnEngine.simulate(identityUser, identityOpponent, match.request.serverSeed,
        ignoreHeroLevelGate = ignoreHeroLevelGate)
    if (simulation.status != ArenaRunStatus.COMPLETED) return null

    val userResult = simulation.fighters.getValue(userId)
    val opponentResult = simulation.fighters.getValue(opponentId)
    val outcome = when {
        simulation.winnerId == null && userResult.hp == 0.0 && opponentResult.hp == 0.0 ->
            BattleOutcome.DRAW
        simulation.winnerId == userId && userResult.hp > 0.0 && opponentResult.hp == 0.0 ->
            BattleOutcome.USER_WIN
        simulation.winnerId == opponentId && opponentResult.hp > 0.0 && userResult.hp == 0.0 ->
            BattleOutcome.USER_LOSS
        else -> return null // A safety limit or nonterminal HP state is never a result.
    }
    if (simulation.events.lastOrNull()?.let {
            it.type == ArenaSupportEventType.END && it.actorId == simulation.winnerId
        } != true
    ) return null
    val ticketTransition = ProjectionBattleEngine.consumeTicket(tickets, tickets.gameEpochDay)
    if (!ticketTransition.accepted) return null

    val live = ArenaLiveBattle(identityUser, identityOpponent, simulation,
        progressionRevision = activeProgression.revision,
        issuedDay = tickets.gameEpochDay,
        ignoreHeroLevelGate = ignoreHeroLevelGate,
        growthEligible = !activeProgression.characterLevelPoints &&
            ArenaProgressionRules.view(activeProgression, tickets.gameEpochDay).growthRemaining > 0,
        skillTreeRevision = skillTree?.revision,
        skillTreeCatalogVersion = if (skillTree == null) 0 else ArenaSkillTreeCatalog.catalogVersion,
        skillTreeRulesVersion = if (skillTree == null) "" else ArenaSkillTreeRules.rulesVersion,
        matchmakingProfile = matchmakingProfile,
    )
    val actualMatch = match.copy(request = match.request.copy(
        user = match.request.user.arenaMetadata(user.fighter),
        opponent = match.request.opponent.arenaMetadata(opponent.fighter),
    ))
    // Compatibility envelope for the established result/history UI, never a playback source.
    // The immutable support contract is retained separately in history for reproducibility.
    val battle = ProjectionBattleResult(
        battleId = actualMatch.request.battleId,
        serverSeed = simulation.seed,
        rulesVersion = 10_002,
        user = actualMatch.request.user.arenaResultMetadata(userResult.maxHp),
        opponent = actualMatch.request.opponent.arenaResultMetadata(opponentResult.maxHp),
        rounds = listOf(BattleRound(
            number = simulation.turns,
            userHpBefore = userResult.maxHp.displayHp(),
            opponentHpBefore = opponentResult.maxHp.displayHp(),
            userHpAfter = userResult.hp.displayHp(),
            opponentHpAfter = opponentResult.hp.displayHp(),
        )),
        outcome = outcome,
    )
    val names = mapOf(userId to actualMatch.request.user.displayName,
        opponentId to actualMatch.request.opponent.displayName)
    // Validate the entire replay before the caller may settle a started match. History is the
    // exact live log, including merged shield details, not a second narration interpretation.
    val timeline = runCatching { buildArenaLiveTimeline(simulation, names, language.languageTag) }
        .getOrNull() ?: return null
    val scenes = timeline.logs.map { line ->
        BattleQaScene(phaseId = "arena-event-${line.sequence}", text = line.text)
    }
    val narrativeSource = when (preparedOpponentSource) {
        ArenaOpponentSource.PUBLIC_ROSTER -> "arena_public_roster"
        ArenaOpponentSource.LOCAL_RESERVE -> "arena_local_reserve"
        null -> "arena_local"
    }
    val narrative = BattleQaNarrative(
        schemaVersion = 1,
        requestId = actualMatch.request.battleId,
        battleId = actualMatch.request.battleId,
        userName = actualMatch.request.user.displayName,
        opponentName = actualMatch.request.opponent.displayName,
        languageTag = language.languageTag,
        phaseCount = scenes.size,
        source = narrativeSource,
        modelValid = false,
        syntheticOnly = preparedOpponentSource != ArenaOpponentSource.PUBLIC_ROSTER,
        productionDatabaseTouched = false,
        scenes = scenes,
    )
    val preview = battlePreviewResult(
        match = actualMatch,
        battle = battle,
        standing = ProjectionBattleEngine.settleUserStanding(standing,
            actualMatch.request.opponentReferenceScore, outcome),
        ticketsAfter = ticketTransition.state,
        playerStance = playerStance,
    ).copy(
        supportBattle = live,
        decisiveMoment = scenes.last().text,
    )
    return preview to narrative
}

internal fun arenaBattleIntro(userName: String, opponentName: String, language: AppLanguage): String =
    when (language) {
        AppLanguage.KOREAN -> "$userName · $opponentName, 결투가 시작됐다."
        AppLanguage.ENGLISH -> "$userName and $opponentName begin their duel."
        AppLanguage.JAPANESE -> "${userName}と${opponentName}の決闘が始まった。"
    }

private fun arenaLocalOpponent(
    match: BattleQaMatchFactory.Match,
    id: String,
    arenaLevel: Int,
    ignoreHeroLevelGate: Boolean,
    skillTreeMode: Boolean = false,
): ArenaSupportInput {
    val projection = match.request.opponent
    val heroClass = HeroClass.valueOf(projection.heroClass.name)
    // Keep nearby low-level QA foes at their real level instead of promoting them to Lv.10.
    val level = projection.level.coerceIn(if (ignoreHeroLevelGate) 1L else 10L, 100L)
    val engine = SimpleGameEngine()
    val roll = engine.rollStats(0x2050_905AL xor match.templateId.hashCode().toLong(), heroClass)
    val generated = engine.newGame(projection.displayName, heroClass, roll.stats.copy(),
        roll.nextSeed, match.request.requestedAtMillis)
    val stats = generated.hero.stats.copy()
    ArenaSyntheticProfileGrowth.grow(
        engine = engine,
        stats = stats,
        heroClass = heroClass,
        targetLevel = level,
        identitySeed = generated.rngState,
    )
    val normalizedStats = requireNotNull(PublicPlayerBattleDerivation.deriveStats(
        heroClass = heroClass,
        level = level,
        combatPower = match.opponentEffectiveCombatPower,
        rawStats = stats,
        allowLowLevelQa = ignoreHeroLevelGate,
    ))
    val legacyFighter = ArenaFighterInput(
            id = id,
            heroClass = heroClass,
            level = level,
            stats = ArenaCoreStats(
                normalizedStats.strength.toDouble(), normalizedStats.constitution.toDouble(),
                normalizedStats.dexterity.toDouble(), normalizedStats.intelligence.toDouble(),
                normalizedStats.wisdom.toDouble(), normalizedStats.charisma.toDouble(),
                normalizedStats.maxHealth.toDouble(), normalizedStats.maxMana.toDouble(),
            ),
            attacks = SkillCatalog.forClass(heroClass).filter { it.unlockLevel <= level }.map {
                ArenaAttackInput(it.catalogId, it.name,
                    if (it.unlockLevel == 1) 1 else it.unlockLevel / 5 + 1,
                    0, it.damagePercentMin, it.damagePercentMax)
            },
        )
    val buildPreset = ArenaAutoBuildPreset.fromSeed(
        arenaAutoBuildPresetIdentitySeed(projection.projectionId),
    )
    val buildSeed = arenaAutoBuildAllocationSeed(projection.projectionId)
    if (!skillTreeMode) return ArenaSupportInput(
        fighter = legacyFighter,
        supportIds = ArenaSupportCatalog.unlockedIds(heroClass, maxOf(level, if (ignoreHeroLevelGate) 10L else 1L)),
        arenaLevel = arenaLevel,
        arenaClassBalanceEnabled = true,
        traits = arenaNpcTraitAllocation(
            heroClass, arenaLevel, level, ignoreHeroLevelGate, buildSeed, buildPreset,
        ),
    )

    val ownedAttackIds = legacyFighter.attacks.mapTo(linkedSetOf()) { it.id }
    val tree = ArenaSkillTreeRules.autoAllocateWithPreset(
        heroClass = heroClass,
        arenaLevel = arenaLevel,
        ownedAttackIds = ownedAttackIds,
        seed = buildSeed,
        preset = buildPreset,
    )
    val ranks = tree.allocations.associate { it.nodeId to it.rank }
    val resolvedAttacks = legacyFighter.attacks.mapNotNull { owned ->
        val rank = ranks[owned.id] ?: return@mapNotNull null
        ArenaTurnInputAdapter.resolveAttack(owned, heroClass, rank)
    }
    val supportRanks = ranks.filterKeys {
        ArenaSkillTreeCatalog.find(it)?.kind == ArenaSkillNodeKind.SUPPORT
    }
    return ArenaSupportInput(
        fighter = legacyFighter.copy(attacks = resolvedAttacks),
        supportIds = supportRanks.keys,
        arenaLevel = arenaLevel,
        arenaClassBalanceEnabled = true,
        traits = emptyList(),
        supportRanks = supportRanks,
    )
}

/**
 * Deterministic local-NPC spending through the same public purchase validator as the player.
 * Missing hero/support requirements and core exclusivity are checked before every purchase.
 */
internal fun arenaNpcTraitAllocation(
    heroClass: HeroClass,
    arenaLevel: Int,
    heroLevel: Long = 100,
    ignoreHeroLevelGate: Boolean = false,
    seed: Long = 0L,
    preset: ArenaAutoBuildPreset = ArenaAutoBuildPreset.fromSeed(seed),
): List<ArenaSupportTraitRank> {
    require(arenaLevel in 1..ArenaProgressionRules.MAX_ARENA_LEVEL)
    val profile = ArenaAutoBuildProfiles.get(preset)
    val legacyOrder = profile.legacyTraitOrder.withIndex().associate { it.value to it.index }
    val idPrefix = "AT9_${heroClass.name}_"
    val definitions = ArenaProgressionCatalog.forClass(heroClass).sortedWith(
        compareBy<com.nullplaying.engine.arena.ArenaProgressionTraitDefinition> { definition ->
            legacyOrder[definition.id.removePrefix(idPrefix)] ?: Int.MAX_VALUE
        }.thenBy { it.id },
    )
    val owned = ArenaSupportCatalog.unlockedIds(heroClass, maxOf(heroLevel, if (ignoreHeroLevelGate) 10L else 1L))
    var state = ArenaProgressionState(unlocked = true,
        totalXp = ArenaProgressionRules.xpForLevel(arenaLevel))
    definitions.forEach { definition ->
        for (rank in 1..definition.maxRank) {
            val next = ArenaProgressionRules.allocate(state, heroClass, heroLevel, owned,
                definition.id, rank, 0, ignoreHeroLevelGate)
            if (!next.accepted) break
            state = next.state
        }
    }
    definitions.filterNot { it.isCore }.forEach { definition ->
        val allocation = state.allocations.firstOrNull { it.id == definition.id } ?: return@forEach
        for (stage in 1..3) {
            val next = ArenaProgressionRules.allocate(state, heroClass, heroLevel, owned,
                definition.id, allocation.rank, stage, ignoreHeroLevelGate)
            if (!next.accepted) break
            state = next.state
        }
    }
    return ArenaProgressionRules.toSupportTraits(state)
}

private fun BattleProjectionSnapshot.arenaMetadata(fighter: ArenaFighterInput) = copy(
    projectionId = fighter.id,
    level = fighter.level,
    build = BattleBuildStats(
        fighter.stats.strength.toLong(), fighter.stats.constitution.toLong(),
        fighter.stats.dexterity.toLong(), fighter.stats.intelligence.toLong(),
        fighter.stats.wisdom.toLong(), fighter.stats.charisma.toLong(),
    ),
    skills = fighter.attacks.map { BattleSkillSnapshot(skillId = it.id, displayName = it.name) },
    // Equipment/old traits did not participate in this battle and must not appear as its causes.
    equipment = emptyList(),
    activeTraitIds = emptyList(),
    heroPathTraitRanks = emptyMap(),
    heroPathRevision = 0L,
    heroPathCatalogVersion = 0,
    heroPathBattleSnapshot = HeroPathBattleSnapshot(),
)

private fun BattleProjectionSnapshot.arenaResultMetadata(maxHp: Double) = NormalizedBattleProjection(
    projectionId = projectionId,
    displayName = displayName,
    heroClass = heroClass,
    level = level,
    condition = condition,
    guidance = guidance,
    skills = skills,
    maxHp = maxHp.displayHp(),
)

/** Positive fractional HP must not be displayed as an already defeated fighter. */
private fun Double.displayHp(): Int = if (this <= 0.0) 0 else ceil(this).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
