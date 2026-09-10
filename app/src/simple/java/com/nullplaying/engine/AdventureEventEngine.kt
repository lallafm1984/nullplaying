package com.nullplaying.engine

import com.nullplaying.model.AdventureEventItemReward
import com.nullplaying.model.AdventureEventBattleRewardKind
import com.nullplaying.model.AdventureEventContext
import com.nullplaying.model.AdventureEventContinuation
import com.nullplaying.model.AdventureEventOutcome
import com.nullplaying.model.AdventureEventProgressPolicy
import com.nullplaying.model.AdventureEventRewardKind
import com.nullplaying.model.AdventureEventRun
import com.nullplaying.model.AdventureEventStat
import com.nullplaying.model.AdventureJourneyState
import com.nullplaying.model.HeroStats
import com.nullplaying.model.SimpleGameState
import com.nullplaying.model.TaleKind
import com.nullplaying.model.MonsterGrade

data class AdventureText(val ko: String, val en: String, val ja: String)

data class AdventureEventApproach(
    val id: String,
    val title: AdventureText,
    val primaryStat: AdventureEventStat,
    val secondaryStat: AdventureEventStat,
    val behaviorSignals: Set<AdventureBehaviorSignal> = emptySet(),
)

enum class AdventureBehaviorSignal {
    TAKE_ALL,
    TAKE_ONLY_USEFUL,
    PREPARE_THOROUGHLY,
    DEPART_LIGHTLY,
    INSPECT_NEW_GEAR,
    KEEP_FAMILIAR_GEAR,
    WEAPON_FOCUS,
    ARMOR_FOCUS,
    PERSIST,
    MOVE_ON,
    SEEK_NOVELTY,
    REPEAT_PROVEN,
    TAKE_RISK,
    CHECK_SAFETY,
    HELP_OTHERS,
    SELF_PRIORITY,
    COOPERATE,
    ACT_ALONE,
    SPEAK_DIRECT,
    SPEAK_GENTLE,
    TOWN_COMFORT,
    WILDERNESS_COMFORT,
    HURRY_HOME,
    LINGER_RETURN,
}

data class AdventureEventBattleRule(
    val monsterName: AdventureText,
    val successGrade: MonsterGrade? = null,
    val partialGrade: MonsterGrade? = null,
    val failureGrade: MonsterGrade? = null,
) {
    fun gradeFor(outcome: AdventureEventOutcome): MonsterGrade? = when (outcome) {
        AdventureEventOutcome.SUCCESS -> successGrade
        AdventureEventOutcome.PARTIAL -> partialGrade
        AdventureEventOutcome.FAILURE -> failureGrade
    }
}

data class AdventureEventRewardWeights(
    val experience: Int,
    val gold: Int,
    val item: Int,
    val route: Int,
) {
    init {
        require(listOf(experience, gold, item, route).all { it in 0..100 })
        require(experience.toLong() + gold + item + route == 100L)
    }
}

data class AdventureEventDefinition(
    val id: String,
    val title: AdventureText,
    val scene: AdventureText,
    val approaches: List<AdventureEventApproach>,
    val success: AdventureText,
    val partial: AdventureText,
    val failure: AdventureText,
    val itemName: AdventureText,
    val successItem: AdventureEventItemReward,
    val successGoldPerLevel: Long,
    val successRouteDelayMillis: Long,
    val rewardWeights: AdventureEventRewardWeights,
    val context: AdventureEventContext = AdventureEventContext.FIELD_EXPLORATION,
    val storyFamily: String = id,
    val battleRule: AdventureEventBattleRule? = null,
)

/** Pure, local event rules. The main timeline owns all reward mutations and phase transitions. */
object AdventureEventEngine {
    const val EVENT_PRESENTATION_MILLIS = 5_000L
    const val ACTION_PRESENTATION_MILLIS = 5_000L
    const val ACTION_MILLIS = EVENT_PRESENTATION_MILLIS + ACTION_PRESENTATION_MILLIS
    const val RESULT_MILLIS = 5_000L
    const val MIN_INTERVAL_MILLIS = 15L * 60_000L
    const val MAX_INTERVAL_MILLIS = 30L * 60_000L
    // A successful resolution has one catalog-wide equipment chance, independent of each event's
    // authored mix of XP, gold, trophy and route rewards. Non-winning equipment chances are lower,
    // so deliberately reaching an elite/boss through a worse outcome is never the better strategy.
    internal const val EQUIPMENT_REWARD_ROLL_BOUND = 10_000
    internal const val EQUIPMENT_REWARD_ACCEPTED_ROLLS = 900
    internal const val TROPHY_RARITY_ROLL_BOUND = 1_000
    internal const val ROUTE_REWARD_ROLL_BOUND = 1_000
    internal const val EVENT_BATTLE_REWARD_ROLL_BOUND = 10_000
    internal const val PARTIAL_BATTLE_EQUIPMENT_REWARD_BASIS_POINTS = 500
    internal const val FAILURE_BATTLE_EQUIPMENT_REWARD_BASIS_POINTS = 200
    const val HISTORY_LIMIT = 32
    private const val SEED_SALT = 0x17D3_29A4_57C6_18B5L
    private const val REWARD_SEED_SALT = 0x4A81_36C7_2F95_0DBEL
    private const val TROPHY_RARITY_SEED_SALT = 0x2B65_74A1_39CF_08D3L

    val all: List<AdventureEventDefinition> = AdventureEventCatalog.all
    private val byId = all.associateBy { it.id }
    fun definition(id: String): AdventureEventDefinition = requireNotNull(byId[id]) { "Unknown adventure event $id" }

    fun initialize(state: SimpleGameState, at: Long) {
        val journey = state.adventureJourney
        if (journey.initialized) return
        val rng = EventRng(state.skillCatalogSeed xor state.rngState xor SEED_SALT)
        journey.initialized = true
        journey.nextEventAt = plus(at, interval(rng))
        journey.nextEventContext = scheduledContext(rng)
        journey.rngState = rng.state
    }

    fun isDue(state: SimpleGameState, eventAt: Long, context: AdventureEventContext): Boolean {
        initialize(state, eventAt)
        return eventAt >= state.adventureJourney.nextEventAt &&
            state.adventureJourney.nextEventContext == context &&
            state.adventureJourney.pending == null &&
            state.adventureJourney.eventBattle == null
    }

    fun begin(
        state: SimpleGameState,
        eventAt: Long,
        actionMillis: Long = ACTION_MILLIS,
        context: AdventureEventContext = state.adventureJourney.nextEventContext,
    ): AdventureEventRun = beginInternal(
        state = state,
        eventAt = eventAt,
        actionMillis = actionMillis,
        forcedEventId = null,
        context = context,
        continuationOverride = null,
        progressPolicyOverride = null,
    )

    /** Offline-QA only entry point. The selected situation still uses the real decision and reward pipeline. */
    internal fun beginForQa(
        state: SimpleGameState,
        eventAt: Long,
        eventId: String,
        actionMillis: Long = ACTION_MILLIS,
        continuationOverride: AdventureEventContinuation = AdventureEventContinuation.NEXT_ADVENTURE_STEP,
        progressPolicyOverride: AdventureEventProgressPolicy = AdventureEventProgressPolicy.REPLACE_ORDINARY_SLOT,
    ): AdventureEventRun = beginInternal(
        state = state,
        eventAt = eventAt,
        actionMillis = actionMillis,
        forcedEventId = eventId,
        context = definition(eventId).context,
        continuationOverride = continuationOverride,
        progressPolicyOverride = progressPolicyOverride,
    )

    private fun beginInternal(
        state: SimpleGameState,
        eventAt: Long,
        actionMillis: Long,
        forcedEventId: String?,
        context: AdventureEventContext,
        continuationOverride: AdventureEventContinuation?,
        progressPolicyOverride: AdventureEventProgressPolicy?,
    ): AdventureEventRun {
        initialize(state, eventAt)
        val journey = state.adventureJourney
        check(journey.pending == null)
        val rng = EventRng(journey.rngState)
        val definition = if (forcedEventId == null) {
            val contextual = all.filter { it.context == context }.ifEmpty { all }
            val recentIds = journey.recentEventIds.takeLast(6).toSet()
            val recentFamilies = journey.recentStoryFamilies.takeLast(3).toSet()
            val candidates = contextual.filter {
                it.id !in recentIds && it.storyFamily !in recentFamilies
            }.ifEmpty {
                contextual.filter { it.id !in recentIds }
            }.ifEmpty { contextual }
            candidates[rng.nextInt(candidates.size)]
        } else {
            definition(forcedEventId)
        }
        val rolledRewardKind = rewardKindForRoll(definition, rng.nextInt(100))
        val weights = definition.approaches.map { approach ->
            score(state.hero.stats, approach).coerceIn(1L, 1_000_000L).toInt()
        }
        val selection = rng.nextInt(weights.sum())
        var cumulative = 0
        val approach = definition.approaches[weights.indexOfFirst { weight ->
            cumulative += weight
            selection < cumulative
        }.coerceAtLeast(0)]
        val success = successBasisPoints(state.hero.stats, state.hero.level, approach)
        val partial = minOf(2_500, 9_500 - success)
        val roll = rng.nextInt(10_000)
        val outcome = when {
            roll < success -> AdventureEventOutcome.SUCCESS
            roll < success + partial -> AdventureEventOutcome.PARTIAL
            else -> AdventureEventOutcome.FAILURE
        }
        val level = state.hero.level.coerceIn(1L, Long.MAX_VALUE / 1_000L)
        val depth = if (state.adventureTale.kind == TaleKind.LABYRINTH) state.adventureTale.labyrinthDepth else 0L
        val encounterLevel = plus(level, if (depth > 0L) LabyrinthProgression.monsterLevelBonus(depth) else 0L)
            .coerceAtMost(Long.MAX_VALUE / 1_000L)
        val rawExperience = plus(16L, encounterLevel * 4L)
        val baseExperience = if (depth > 0L) LabyrinthProgression.scaleCombatExperience(rawExperience, depth) else rawExperience
        val battleGrade = definition.battleRule?.gradeFor(outcome)
        val equipmentRewardRoll = rng.nextInt(EQUIPMENT_REWARD_ROLL_BOUND)
        val hasBagSpace = state.inventory.size.toLong() < state.inventoryCapacity()
        val outcomeRewardKind = rewardKindForOutcome(
            selected = rolledRewardKind,
            outcome = outcome,
            equipmentRoll = equipmentRewardRoll,
        )
        val rewardKind = if (!hasBagSpace && outcomeRewardKind == AdventureEventRewardKind.ITEM) {
            AdventureEventRewardKind.GOLD
        } else {
            outcomeRewardKind
        }
        val battleRewardKind = if (battleGrade == null) {
            AdventureEventBattleRewardKind.NONE
        } else if (hasBagSpace && battleEquipmentRewardForRoll(outcome, battleGrade, equipmentRewardRoll)) {
            AdventureEventBattleRewardKind.EQUIPMENT
        } else {
            AdventureEventBattleRewardKind.GOLD
        }
        val reward = if (battleGrade == null) {
            rewardFor(definition, rewardKind, outcome, level, baseExperience, equipmentRewardRoll, rng)
        } else {
            EventReward()
        }
        val sequence = plus(journey.sequence, 1L)
        rng.nextInt(Int.MAX_VALUE)
        val run = AdventureEventRun(sequence, definition.id, approach.id, eventAt, actionMillis.coerceAtLeast(1_000L),
            state.hero.level, approach.primaryStat, approach.secondaryStat,
            stat(state.hero.stats, approach.primaryStat), stat(state.hero.stats, approach.secondaryStat),
            success, partial, roll, outcome, reward.experience,
            reward.gold, reward.item, reward.routeDelayMillis, forkRewardSeed(rng.state), encounterLevel, depth,
            baseExperience, rewardKind, reward.routeRewardUses,
            context = context,
            continuation = continuationOverride ?: continuationFor(context),
            progressPolicy = progressPolicyOverride ?: progressPolicyFor(context),
            battleGrade = battleGrade,
            battleRewardKind = battleRewardKind,
        )
        journey.sequence = sequence
        journey.rngState = rng.state
        journey.pending = run
        journey.recentEventIds = (journey.recentEventIds + definition.id).takeLast(6)
        journey.recentStoryFamilies = (journey.recentStoryFamilies + definition.storyFamily).takeLast(3)
        return run
    }

    fun scheduleNext(journey: AdventureJourneyState, eventAt: Long) {
        val rng = EventRng(journey.rngState)
        journey.nextEventAt = plus(eventAt, interval(rng))
        journey.nextEventContext = scheduledContext(rng)
        journey.rngState = rng.state
    }

    /** Rebuild exactly one authored reward from a trait domain without consuming the base event stream. */
    fun withOutcome(run: AdventureEventRun, outcome: AdventureEventOutcome, seed: Long): AdventureEventRun {
        val definition = definition(run.eventId)
        val rng = EventRng(seed)
        val level = run.heroLevel.coerceIn(1L, Long.MAX_VALUE / 1_000L)
        val selectedRewardKind = resolvedRewardKind(run, definition)
        val equipmentRewardRoll = rng.nextInt(EQUIPMENT_REWARD_ROLL_BOUND)
        val rewardKind = rewardKindForOutcome(selectedRewardKind, outcome, equipmentRewardRoll)
        val battleGrade = definition.battleRule?.gradeFor(outcome)
        val battleRewardKind = if (battleGrade == null) AdventureEventBattleRewardKind.NONE
            else battleRewardKindForRoll(outcome, battleGrade, equipmentRewardRoll)
        val reward = if (battleGrade == null) {
            rewardFor(
                definition,
                rewardKind,
                outcome,
                level,
                run.baseExperienceBudget,
                equipmentRewardRoll,
                rng,
            )
        } else {
            EventReward()
        }
        return run.copy(outcome = outcome, experienceReward = reward.experience, goldReward = reward.gold,
            itemReward = reward.item, routeDelayMillis = reward.routeDelayMillis,
            rewardSeed = forkRewardSeed(rng.state), rewardKind = rewardKind,
            routeRewardUses = reward.routeRewardUses,
            battleGrade = battleGrade,
            battleRewardKind = battleRewardKind)
    }

    /**
     * Old pending saves may contain several positive fields from the pre-selection reward model.
     * Keep the already-authored value of one deterministic category so settlement and its receipt agree.
     */
    internal fun normalizedForSettlement(run: AdventureEventRun): AdventureEventRun {
        if (run.battleGrade != null) {
            return run.copy(
                experienceReward = 0L,
                goldReward = 0L,
                itemReward = AdventureEventItemReward.NONE,
                routeDelayMillis = 0L,
                routeRewardUses = 0,
                battleRewardKind = run.battleRewardKind.takeUnless {
                    it == AdventureEventBattleRewardKind.NONE
                } ?: battleRewardKindForSeed(run.rewardSeed, run.battleGrade, run.outcome),
            )
        }
        val definition = definition(run.eventId)
        val rewardKind = resolvedRewardKind(run, definition)
        if (run.outcome == AdventureEventOutcome.FAILURE) {
            return run.copy(
                experienceReward = 0L,
                goldReward = 0L,
                itemReward = AdventureEventItemReward.NONE,
                routeDelayMillis = run.routeDelayMillis.takeIf { it > 0L } ?: 2_000L,
                rewardKind = rewardKind,
                routeRewardUses = 0,
            )
        }
        return when (rewardKind) {
            AdventureEventRewardKind.EXPERIENCE -> run.copy(
                goldReward = 0L,
                itemReward = AdventureEventItemReward.NONE,
                routeDelayMillis = 0L,
                rewardKind = rewardKind,
                routeRewardUses = 0,
            )
            AdventureEventRewardKind.GOLD -> run.copy(
                experienceReward = 0L,
                itemReward = AdventureEventItemReward.NONE,
                routeDelayMillis = 0L,
                rewardKind = rewardKind,
                routeRewardUses = 0,
            )
            AdventureEventRewardKind.ITEM -> run.copy(
                experienceReward = 0L,
                goldReward = 0L,
                routeDelayMillis = 0L,
                rewardKind = rewardKind,
                routeRewardUses = 0,
            )
            AdventureEventRewardKind.ROUTE -> run.copy(
                experienceReward = 0L,
                goldReward = 0L,
                itemReward = AdventureEventItemReward.NONE,
                rewardKind = rewardKind,
                routeRewardUses = if (run.routeDelayMillis < 0L) run.routeRewardUses.coerceIn(2, 10) else 0,
            )
            AdventureEventRewardKind.UNSPECIFIED -> error("Resolved event reward kind cannot be unspecified")
        }
    }

    /**
     * Fork only the reward stream. The accepted equipment roll conditions the award state's low bits;
     * continuing the same LCG from those bits would exclude half the equipment slots.
     * SplitMix64's avalanche breaks that correlation without advancing or replacing the
     * event decision/schedule stream or the existing combat RNG.
     */
    internal fun forkRewardSeed(eventState: Long): Long {
        var mixed = eventState + REWARD_SEED_SALT
        mixed = (mixed xor (mixed ushr 30)) * -4_658_895_280_553_007_687L
        mixed = (mixed xor (mixed ushr 27)) * -7_723_592_293_110_705_685L
        mixed = mixed xor (mixed ushr 31)
        return mixed.takeUnless { it == 0L } ?: REWARD_SEED_SALT
    }

    /** Event trophies are sale-only finds and intentionally never use the common rarity. */
    internal fun eventTrophyRarity(rewardSeed: Long): String {
        val rng = EventRng(rewardSeed xor TROPHY_RARITY_SEED_SALT)
        return eventTrophyRarityForRoll(rng.nextInt(TROPHY_RARITY_ROLL_BOUND))
    }

    internal fun eventTrophyRarityForRoll(roll: Int): String =
        when (roll.coerceIn(0, TROPHY_RARITY_ROLL_BOUND - 1)) {
            0 -> "신화"
            in 1..9 -> "전설"
            in 10..49 -> "영웅"
            in 50..249 -> "희귀"
            else -> "고급"
        }

    internal fun successfulItemReward(
        configured: AdventureEventItemReward,
        equipmentRoll: Int,
    ): AdventureEventItemReward =
        if (configured == AdventureEventItemReward.EQUIPMENT &&
            equipmentRoll.coerceIn(0, EQUIPMENT_REWARD_ROLL_BOUND - 1) < EQUIPMENT_REWARD_ACCEPTED_ROLLS
        ) AdventureEventItemReward.EQUIPMENT else AdventureEventItemReward.TROPHY

    internal fun rewardKindForOutcome(
        selected: AdventureEventRewardKind,
        outcome: AdventureEventOutcome,
        equipmentRoll: Int,
    ): AdventureEventRewardKind = if (
        outcome == AdventureEventOutcome.SUCCESS &&
        successfulItemReward(AdventureEventItemReward.EQUIPMENT, equipmentRoll) == AdventureEventItemReward.EQUIPMENT
    ) {
        AdventureEventRewardKind.ITEM
    } else {
        selected
    }

    internal fun rewardKindForRoll(
        definition: AdventureEventDefinition,
        roll: Int,
    ): AdventureEventRewardKind {
        val bounded = roll.coerceIn(0, 99)
        val weights = definition.rewardWeights
        return when {
            bounded < weights.experience -> AdventureEventRewardKind.EXPERIENCE
            bounded < weights.experience + weights.gold -> AdventureEventRewardKind.GOLD
            bounded < weights.experience + weights.gold + weights.item -> AdventureEventRewardKind.ITEM
            else -> AdventureEventRewardKind.ROUTE
        }
    }

    internal fun routeRewardUsesForRoll(roll: Int): Int = when (roll.coerceIn(0, ROUTE_REWARD_ROLL_BOUND - 1)) {
        in 0..299 -> 2
        in 300..539 -> 3
        in 540..719 -> 4
        in 720..839 -> 5
        in 840..909 -> 6
        in 910..949 -> 7
        in 950..974 -> 8
        in 975..989 -> 9
        else -> 10
    }

    private data class EventReward(
        val experience: Long = 0L,
        val gold: Long = 0L,
        val item: AdventureEventItemReward = AdventureEventItemReward.NONE,
        val routeDelayMillis: Long = 0L,
        val routeRewardUses: Int = 0,
    )

    /** The single source of truth used by both newly generated and trait-rewritten outcomes. */
    private fun rewardFor(
        definition: AdventureEventDefinition,
        rewardKind: AdventureEventRewardKind,
        outcome: AdventureEventOutcome,
        level: Long,
        baseExperience: Long,
        equipmentRewardRoll: Int,
        rng: EventRng,
    ): EventReward {
        if (outcome == AdventureEventOutcome.FAILURE) {
            return EventReward(routeDelayMillis = 2_000L)
        }

        val partial = outcome == AdventureEventOutcome.PARTIAL
        return when (rewardKind) {
            AdventureEventRewardKind.ROUTE -> {
                val successUses = routeRewardUsesForRoll(rng.nextInt(ROUTE_REWARD_ROLL_BOUND))
                EventReward(
                    routeDelayMillis = if (partial) definition.successRouteDelayMillis / 2L
                    else definition.successRouteDelayMillis,
                    routeRewardUses = successUses,
                )
            }
            AdventureEventRewardKind.GOLD -> EventReward(
                gold = if (partial) times(level, definition.successGoldPerLevel) / 4L
                else times(level, definition.successGoldPerLevel),
            )
            AdventureEventRewardKind.ITEM -> EventReward(
                item = if (partial) {
                    AdventureEventItemReward.TROPHY
                } else if (definition.successItem == AdventureEventItemReward.EQUIPMENT) {
                    successfulItemReward(
                        definition.successItem,
                        equipmentRewardRoll,
                    )
                } else {
                    definition.successItem
                },
            )
            AdventureEventRewardKind.EXPERIENCE -> EventReward(
                experience = scaledExperience(baseExperience, if (partial) 100L else 120L),
            )
            AdventureEventRewardKind.UNSPECIFIED -> error("Adventure event reward kind must be selected")
        }
    }

    private fun resolvedRewardKind(
        run: AdventureEventRun,
        definition: AdventureEventDefinition,
    ): AdventureEventRewardKind {
        if (run.rewardKind != AdventureEventRewardKind.UNSPECIFIED) return run.rewardKind
        return when {
            run.itemReward != AdventureEventItemReward.NONE -> AdventureEventRewardKind.ITEM
            run.goldReward > 0L -> AdventureEventRewardKind.GOLD
            run.routeDelayMillis < 0L -> AdventureEventRewardKind.ROUTE
            run.experienceReward > 0L -> AdventureEventRewardKind.EXPERIENCE
            else -> rewardKindForRoll(definition, ((run.rewardSeed ushr 1) % 100L).toInt())
        }
    }

    private fun scaledExperience(base: Long, percent: Long): Long =
        plus(times(base / 100L, percent), base % 100L * percent / 100L)

    fun successBasisPoints(stats: HeroStats, level: Long, approach: AdventureEventApproach): Int {
        val expected = (10L + (level.coerceIn(1L, 1_000_000L) - 1L) / 3L).coerceAtLeast(1L)
        return (4_500L + (score(stats, approach) - expected) * 2_500L / expected)
            .coerceIn(1_500L, 8_500L).toInt()
    }

    private fun score(stats: HeroStats, approach: AdventureEventApproach): Long =
        (stat(stats, approach.primaryStat).coerceIn(0L, 1_000_000L) * 7L +
            stat(stats, approach.secondaryStat).coerceIn(0L, 1_000_000L) * 3L) / 10L

    fun stat(stats: HeroStats, key: AdventureEventStat): Long = when (key) {
        AdventureEventStat.STR -> stats.strength
        AdventureEventStat.CON -> stats.constitution
        AdventureEventStat.DEX -> stats.dexterity
        AdventureEventStat.INT -> stats.intelligence
        AdventureEventStat.WIS -> stats.wisdom
        AdventureEventStat.CHA -> stats.charisma
    }

    private fun interval(rng: EventRng) = MIN_INTERVAL_MILLIS + rng.nextInt((MAX_INTERVAL_MILLIS - MIN_INTERVAL_MILLIS + 1L).toInt())
    private fun scheduledContext(rng: EventRng): AdventureEventContext =
        all[rng.nextInt(all.size)].context

    private fun continuationFor(context: AdventureEventContext): AdventureEventContinuation = when (context) {
        AdventureEventContext.PRE_COMBAT -> AdventureEventContinuation.BEGIN_COMBAT
        AdventureEventContext.RETURN_ROUTE -> AdventureEventContinuation.BEGIN_RETURNING
        AdventureEventContext.TOWN_RETURN -> AdventureEventContinuation.BEGIN_TOWN_SHOPPING
        AdventureEventContext.OUTBOUND_ROUTE,
        AdventureEventContext.FIELD_EXPLORATION,
        AdventureEventContext.POST_COMBAT,
        -> AdventureEventContinuation.NEXT_ADVENTURE_STEP
    }

    private fun progressPolicyFor(context: AdventureEventContext): AdventureEventProgressPolicy =
        if (context == AdventureEventContext.FIELD_EXPLORATION) {
            AdventureEventProgressPolicy.REPLACE_ORDINARY_SLOT
        } else {
            AdventureEventProgressPolicy.NO_PROGRESS
        }

    internal fun battleEquipmentRewardBasisPoints(outcome: AdventureEventOutcome): Int = when (outcome) {
        AdventureEventOutcome.SUCCESS -> EQUIPMENT_REWARD_ACCEPTED_ROLLS
        AdventureEventOutcome.PARTIAL -> PARTIAL_BATTLE_EQUIPMENT_REWARD_BASIS_POINTS
        AdventureEventOutcome.FAILURE -> FAILURE_BATTLE_EQUIPMENT_REWARD_BASIS_POINTS
    }

    internal fun battleEquipmentRewardForRoll(
        outcome: AdventureEventOutcome,
        grade: MonsterGrade,
        roll: Int,
    ): Boolean = grade != MonsterGrade.NORMAL &&
        roll.coerceIn(0, EVENT_BATTLE_REWARD_ROLL_BOUND - 1) < battleEquipmentRewardBasisPoints(outcome)

    internal fun battleRewardKindForRoll(
        outcome: AdventureEventOutcome,
        grade: MonsterGrade,
        roll: Int,
    ): AdventureEventBattleRewardKind = if (battleEquipmentRewardForRoll(outcome, grade, roll)) {
        AdventureEventBattleRewardKind.EQUIPMENT
    } else {
        AdventureEventBattleRewardKind.GOLD
    }

    internal fun battleRewardKindForSeed(
        seed: Long,
        grade: MonsterGrade,
        outcome: AdventureEventOutcome = AdventureEventOutcome.FAILURE,
    ): AdventureEventBattleRewardKind {
        val rng = EventRng(seed xor REWARD_SEED_SALT)
        return battleRewardKindForRoll(outcome, grade, rng.nextInt(EVENT_BATTLE_REWARD_ROLL_BOUND))
    }
    private fun plus(left: Long, right: Long): Long = if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
    private fun times(left: Long, right: Long): Long = if (left != 0L && right > Long.MAX_VALUE / left) Long.MAX_VALUE else left * right
    private class EventRng(seed: Long) {
        var state: Long = seed.takeUnless { it == 0L } ?: SEED_SALT
        fun nextInt(bound: Int): Int {
            state = state * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
            return ((state ushr 1) % bound.toLong()).toInt()
        }
    }
}
