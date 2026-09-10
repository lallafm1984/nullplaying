package com.nullplaying.engine.arena

import com.nullplaying.engine.SkillCatalog
import com.nullplaying.engine.arena.ArenaSupportKind as Kind

import com.nullplaying.model.HeroClass
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

private typealias Support = ArenaResolvedSupport

/**
 * Local deterministic arena with sixty support definitions and allocated V9 growth effects.
 * Input ownership is explicit; catalog entries are never silently added to a fighter.
 */
object ArenaSupportTurnEngine {
    private const val BASIC = "BASIC_ATTACK"
    private const val MP_SCALE = 1000

    internal data class CleanseRemovalCandidate(
        val id: Int,
        val kind: String,
        val impact: Double,
    )

    /**
     * Returns deterministic removal batches. Poison layers are one status for the
     * count contract and leave together; every other candidate consumes one count.
     */
    internal fun cleanseRemovalPlan(
        candidates: List<CleanseRemovalCandidate>,
        accuracyOnly: Boolean,
        maximum: Int,
    ): List<List<Int>> {
        val remaining = candidates.filter { !accuracyOnly || it.kind == "accuracy" }.toMutableList()
        val plan = mutableListOf<List<Int>>()
        repeat(maximum.coerceAtLeast(0)) {
            val options = buildList<List<CleanseRemovalCandidate>> {
                var poisonAdded = false
                remaining.forEach { candidate ->
                    if (candidate.kind != "poison") add(listOf(candidate))
                    else if (!poisonAdded) {
                        add(remaining.filter { it.kind == "poison" })
                        poisonAdded = true
                    }
                }
            }
            val selected = options.maxByOrNull { option -> option.sumOf { it.impact } } ?: return plan
            val ids = selected.map { it.id }
            plan += ids
            remaining.removeAll { it.id in ids }
        }
        return plan
    }

    private val catalog = ArenaSupportCatalog.values.associateBy { it.id }

    val supportedSupportIds: Set<String> get() = catalog.keys.toSet()
    val supportedTraitIds: Set<String> get() = ArenaProgressionCatalog.values.map { it.id }.toSet()

    /** Catalog lookup for UI/fixtures only; simulate never grants these IDs automatically. */
    fun initialSupportIds(heroClass: HeroClass): Set<String> =
        catalog.values.filter { it.heroClass == heroClass && it.unlockLevel <= 10 }.map { it.id }.toSet()

    fun validate(input: ArenaSupportInput, ignoreHeroLevelGate: Boolean = false) {
        require(input.fighter.level >= if (ignoreHeroLevelGate) 1 else 10) { "Arena hero level gate" }
        require(input.arenaLevel in 1..100) { "Arena level must be in 1..100" }
        val treeContract = input.supportRanks.isNotEmpty() || input.fighter.attacks.any { it.arena != null }
        if (treeContract) {
            require(input.fighter.attacks.all { it.arena != null }) { "Mixed arena attack contracts" }
            require(input.supportRanks.keys == input.supportIds) { "Support ranks must match ownership" }
            require(input.supportRanks.values.all { it in 1..10 }) { "Invalid support rank" }
            require(input.traits.isEmpty()) { "Legacy growth traits cannot modify a V3 skill tree" }
        } else {
            require(input.supportRanks.isEmpty()) { "Legacy supports cannot carry ranks" }
        }
        if (input.resolvedSupports.isNotEmpty()) {
            require(input.resolvedSupports.keys == input.supportIds) {
                "Resolved supports must match ownership"
            }
            input.resolvedSupports.forEach { (id, support) ->
                require(support.id == id) { "Resolved support ID mismatch" }
                require(support.heroClass == input.fighter.heroClass) { "Foreign resolved support: $id" }
                require(support.rank == (input.supportRanks[id] ?: 0)) { "Resolved support rank mismatch: $id" }
            }
        }
        for (attack in input.fighter.attacks) {
            val definition = requireNotNull(SkillCatalog.find(attack.id)) { "Unknown attack: ${attack.id}" }
            require(definition.heroClass == input.fighter.heroClass) { "Foreign attack: ${attack.id}" }
            require(definition.unlockLevel.toLong() <= input.fighter.level) { "Locked attack: ${attack.id}" }
            val tier = if (definition.unlockLevel == 1) 1 else definition.unlockLevel / 5 + 1
            require(attack.tier == tier) { "Attack tier does not match its catalog ID: ${attack.id}" }
        }
        for (id in input.supportIds) {
            val support = input.resolvedSupports[id]
            val catalogSupport = if (support == null) {
                requireNotNull(catalog[id]) { "Unknown support: $id" }
            } else {
                null
            }
            require((support?.heroClass ?: catalogSupport?.heroClass) == input.fighter.heroClass) {
                "Foreign support: $id"
            }
            // QA opens only the arena baseline; actual owned attack unlocks remain enforced.
            require(support != null || id in input.supportRanks ||
                input.fighter.level >= checkNotNull(catalogSupport).unlock ||
                (ignoreHeroLevelGate && catalogSupport.unlock <= 10)) { "Locked support: $id" }
        }
        require(input.traits.map { it.id }.distinct().size == input.traits.size) { "Duplicate trait" }
        require(input.traits.count { ArenaProgressionCatalog.find(it.id)?.isCore == true } <= 1) { "Only one core trait may be allocated" }
        var basePoints = 0
        var enhancementPoints = 0
        for (trait in input.traits) {
            val definition = requireNotNull(ArenaProgressionCatalog.find(trait.id)) {
                "Unknown trait: ${trait.id}"
            }
            require(definition.heroClass == input.fighter.heroClass) { "Foreign trait: ${trait.id}" }
            require(trait.rank in 1..definition.maxRank && trait.enhancement in 0..definition.maxEnhancement) { "Invalid trait rank" }
            require(input.fighter.level >= definition.minHeroLevel || ignoreHeroLevelGate) { "Locked trait: ${trait.id}" }
            definition.requiredSupportIds.forEach {
                require(it in input.supportIds) { "Trait requires an actually owned support: $it" }
            }
            basePoints += definition.basePointCost(trait.rank)
            enhancementPoints += listOf(0, 2, 5, 10)[trait.enhancement]
        }
        require(basePoints <= min(50, input.arenaLevel)) { "Base point budget exceeded" }
        require(enhancementPoints <= max(0, input.arenaLevel - 50)) { "Enhancement budget exceeded" }
    }

    fun simulate(
        left: ArenaSupportInput,
        right: ArenaSupportInput,
        seed: Long,
        rules: ArenaTurnRules = ArenaTurnRules(),
        recordEvents: Boolean = true,
        ignoreHeroLevelGate: Boolean = false,
    ): ArenaSupportResult {
        validate(left, ignoreHeroLevelGate)
        validate(right, ignoreHeroLevelGate)
        require(left.fighter.id != right.fighter.id) { "Distinct stable fighter IDs are required" }
        if (left.identity != null || right.identity != null) {
            require(left.identity != null && right.identity != null) { "Mixed identity/legacy rules" }
            require(left.identity.version == right.identity.version) { "Mixed identity versions" }
            return if(left.identity.version == ARENA_IDENTITY_V1_RULES_VERSION)
                ArenaIdentityEngineV1.simulate(left, right, seed, rules, recordEvents)
            else if(left.identity.version == ARENA_IDENTITY_V2_RULES_VERSION)
                ArenaIdentityEngineV2.simulate(left, right, seed, rules, recordEvents)
            else if(left.identity.version == ARENA_IDENTITY_V3_RULES_VERSION)
                ArenaIdentityEngineV3.simulate(left,right,seed,rules,recordEvents)
            else if(left.identity.version == ARENA_IDENTITY_V4_RULES_VERSION)
                ArenaIdentityEngineV4.simulate(left,right,seed,rules,recordEvents)
            else ArenaIdentityEngine.simulate(left, right, seed, rules, recordEvents)
        }
        return Run(left, right, seed, rules, recordEvents).simulate()
    }

    /** Test-only deterministic action schedule. Ownership, MP, cooldown, trigger and turn gates still apply. */
    internal fun simulateScripted(left: ArenaSupportInput, right: ArenaSupportInput, seed: Long,
        schedule: Map<Pair<String, Int>, String>, rules: ArenaTurnRules = ArenaTurnRules()): ArenaSupportResult {
        validate(left); validate(right)
        require(left.fighter.id != right.fighter.id)
        return Run(left, right, seed, rules, true, schedule).simulate()
    }

    private data class Action(
        val id: String, val cast: Int, val mp: Int, val units: Double = 0.0,
        val support: Support? = null, val originalCast: Int = cast,
        val coreIds: Set<String> = emptySet(), val extraMp: Int = 0,
        val cooldown: Int = 0, val arena: ArenaResolvedAttack? = null,
    ) {
        val isAttack get() = support == null
        val isSkill get() = isAttack && id != BASIC
        val effectKey get() = arena?.effectKey.orEmpty()
        fun value(key: String): Double = arena?.effectValues?.get(key) ?: 0.0
    }

    private data class TraitToken(
        val traitId: String,
        val expires: Int,
        val availableFrom: Int,
        val cause: Int,
    )
    private data class Casting(
        val id: Int, val action: Action, var remaining: Int, val startTurn: Int,
        val startSequence: Int, val reservedTraits: Map<String, TraitToken> = emptyMap(),
        val growthModifiers: List<ArenaGrowthModifier> = emptyList(),
        val startContext: ArenaGrowthContext? = null, val capturedAmount: Double = 0.0,
    )
    private data class Active(val support: Support, val expires: Int, var charges: Int = support.charges,
        val appliedTurn: Int = 0, val instanceId: Int = 0, val capturedAmount: Double = 0.0, val causeSequence: Int = 0,
        val reductionBonus: Double = 0.0)
    private data class Harmful(val kind: String, val sourceId: String, val supportId: String,
        val appliedTurn: Int, val expires: Int, val magnitude: Double, val cause: Int)
    private data class Recent(val turn: Int, val sequence: Int, val amount: Double = 0.0)
    private data class TimedValue(val value: Double, val expires: Int, var charges: Int = 1)
    private data class FollowUpMark(
        val accuracy: Double,
        val damage: Double,
        val expires: Int,
        val charges: Int = 1,
    )
    private data class Choice(
        val action: Action,
        val costUnits: Int,
        val discounts: Map<String, Double> = emptyMap(),
        val growthDiscounts: List<ArenaGrowthModifier> = emptyList(),
        val observedContext: ArenaGrowthContext? = null,
    ) {
        val discountPercent: Double get() = discounts.values.sum().coerceAtMost(30.0)
    }

    private data class DirectAttackCompletion(val actionId: String, val damagedHp: Boolean)

    private class Fighter(original: ArenaSupportInput, val rules: ArenaTurnRules) {
        val input = original.copy(
            fighter = original.fighter.copy(attacks = original.fighter.attacks.toList()),
            supportIds = original.supportIds.toSet(), supportRanks = original.supportRanks.toMap(),
            traits = original.traits.toList(),
        )
        val growth = ArenaProgressionRuntime(input.traits)
        val id = input.fighter.id
        val heroClass = input.fighter.heroClass
        val randomKey = hash(id)
        private val traitsById = input.traits.associateBy { it.id }
        private val traitValues = traitsById.mapValues { (id, trait) ->
            requireNotNull(ArenaProgressionCatalog.find(id)).value(trait.rank, trait.enhancement)
        }
        private val stats = input.fighter.stats
        private val values = stats.values().take(6)
        private val f = rules.formula
        private val classOffense = values[heroClass.primaryStatIndex] * .7 +
            values[heroClass.secondaryStatIndex] * .3
        private val offensePool = mean3(stats.strength, stats.dexterity, stats.intelligence)
        private val survivalPool = mean3(stats.constitution, stats.wisdom, stats.charisma)
        private val healthAttribute = values.sumOf { it / 6.0 } * (1 - f.healthSurvivalShare) +
            survivalPool * f.healthSurvivalShare
        private val attackAttribute = classOffense * (1 - f.attackOffenseShare) +
            offensePool * f.attackOffenseShare
        val maxHp = f.healthBase + f.healthScale * sqrt(healthAttribute)
        val attackPower = (f.attackBase + f.attackScale * sqrt(attackAttribute)) * if (
            input.arenaClassBalanceEnabled
        ) ArenaClassBalance.offenseMultiplier(
            heroClass = heroClass,
            heroLevel = input.fighter.level,
            arenaLevel = input.arenaLevel,
        ) else 1.0
        val defense = 1 + f.defenseScale * sqrt(survivalPool)
        private val maxMp = when (rules.mpMode) {
            ArenaMpMode.FIXED_100 -> 100
            ArenaMpMode.GROWTH_LOG -> max(100,
                (70 + 15 * ln(1 + stats.rawMaxMana / 20) / ln(2.0)).roundToInt())
        }
        val maxMpUnits = maxMp * MP_SCALE
        val attacks = listOf(Action(BASIC, 1, 0, 1.0)) + input.fighter.attacks.sortedBy { it.id }.map {
            val arena = it.arena
            if (arena != null) {
                Action(
                    id = it.id,
                    cast = arena.prepareTurns + 1,
                    mp = arena.mpCost,
                    units = arena.damagePercent / 100.0,
                    cooldown = arena.cooldownTurns,
                    arena = arena,
                )
            } else {
                val cast = 1 + (it.tier - 1) % 3
                val base = when (rules.budget) {
                    ArenaAttackBudget.C0 -> cast.toDouble()
                    ArenaAttackBudget.C1 -> when (cast) { 1 -> 1.25; 2 -> 2.60; else -> 4.05 }
                }
                val mp = when (rules.budget) {
                    ArenaAttackBudget.C0 -> cast * 8
                    ArenaAttackBudget.C1 -> when (cast) { 1 -> 6; 2 -> 8; else -> 12 }
                }
                val growth = 1 + (if (rules.tierScaling) rules.tierGain * (it.tier - 1) else 0.0) +
                    (if (rules.masteryScaling) rules.masteryGain * it.masteryBonusPercent / 100 else 0.0)
                Action(it.id, cast, mp, base * growth, cooldown = rules.cooldownTurns)
            }
        }
        val supports = input.supportIds.sorted().map { id ->
            val support = input.resolvedSupports[id] ?: catalog.getValue(id).let { base ->
                input.supportRanks[id]?.let { ArenaSkillTreeCatalog.effectiveSupport(base, it) } ?: base
            }.resolved(input.supportRanks[id] ?: 0)
            Action(id, support.cast, support.mp, support = support)
        }
        init {
            require(maxMp > 0 && maxMp <= Int.MAX_VALUE / MP_SCALE)
            require(maxHp.isFinite() && (maxHp * 1.2).isFinite() && attackPower.isFinite() && defense.isFinite())
            require((attackPower * attacks.maxOf { it.units } * 4.0).isFinite()) {
                "Attack/formula combination exceeds the finite support range"
            }
        }
        var hp = maxHp
        var mpUnits = maxMpUnits
        var shield = 0.0
        var casting: Casting? = null
        var nextCastId = 1
        val readyAt = HashMap<String, Int>()
        val effects = HashMap<Kind, Active>()
        val traitTokens = HashMap<String, TraitToken>()
        val traitReadyAt = HashMap<String, Int>()
        var lastDirectAttack: DirectAttackCompletion? = null
        var healReadyAt = 1
        var mustCompleteAttack = false
        var consecutiveSupports = 0
        val harmful = mutableListOf<Harmful>()
        val supportUseCounts = mutableMapOf<String, Int>()
        val usedRecent = mutableSetOf<Int>()
        var recentDamage: Recent? = null
        var recentMiss: Recent? = null
        val recentHealing = mutableListOf<Recent>()
        var lastAttackFailedTurn = Int.MIN_VALUE
        var lastInstantHitTakenTurn = Int.MIN_VALUE
        var lastSupportCompletedTurn = Int.MIN_VALUE
        var lastPiercingHitTakenTurn = Int.MIN_VALUE
        var nextDirectDamagePenalty: TimedValue? = null
        var followUpMark: FollowUpMark? = null
        var vulnerability: TimedValue? = null
        var arenaHealingReduction: TimedValue? = null
        var pierceSuppression: TimedValue? = null
        var currentAttackBonusPercent = 0.0
        val usedArenaOnce = mutableSetOf<String>()
        val arenaEffectUseCounts = mutableMapOf<String, Int>()
        var sleepAt = -1
        var immuneUntil = -1
        var needsNormalCompletion = false
        var tauntUntil = -1
        var sameStatusGuard: Pair<String, Int>? = null
        val coreTokens = mutableMapOf<String, Pair<Int, Double>>()
        var shoutBasicHits = 0
        var healCoreHotUntil = -1
        var healCoreHotCause = -1
        fun core(branch: String) = hasTrait("AT9_${heroClass.name}_${branch}_CORE")
        fun coreValue(branch: String, key: String, fallback: Double): Double =
            if (core(branch)) ArenaProgressionCatalog.find("AT9_${heroClass.name}_${branch}_CORE")?.coreParameters?.get(key) ?: fallback else fallback
        fun harmful(kind: String, turn: Int) = harmful.filter { it.kind == kind && it.expires >= turn }
        fun immune(turn: Int) = turn <= immuneUntil || needsNormalCompletion


        fun has(kind: Kind, atTurn: Int) = effects[kind]?.expires?.let { it >= atTurn } == true
        fun hasTrait(traitId: String) = traitId in traitsById
        fun traitValue(traitId: String) = traitValues[traitId] ?: 0.0
        fun tokenAvailable(traitId: String, turn: Int) = traitTokens[traitId]
            ?.takeIf { turn >= it.availableFrom && turn <= it.expires }
        fun snapshot() = ArenaSupportFighterResult(hp, maxHp, mpUnits, maxMpUnits, shield)
    }

    private class Run(
        left: ArenaSupportInput, right: ArenaSupportInput, val seed: Long,
        val rules: ArenaTurnRules, recordEvents: Boolean,
        val scripted: Map<Pair<String, Int>, String> = emptyMap(),
    ) {
        private val fighters = listOf(Fighter(left, rules), Fighter(right, rules)).sortedBy { it.id }
        private val initiativeKey = hash(fighters.joinToString("") { "${it.id.length}:${it.id}" })
        private val events = if (recordEvents) ArrayList<ArenaSupportEvent>() else null
        private var sequence = 0

        private fun emit(
            type: ArenaSupportEventType, turn: Int, actor: Fighter? = null, target: Fighter? = null,
            cast: Casting? = null, actionId: String? = cast?.action?.id, traitId: String? = null,
            hpBefore: Double? = null, hpAfter: Double? = null,
            mpBefore: Int? = null, mpAfter: Int? = null, amount: Double = 0.0,
            shieldBefore: Double? = null, shieldAfter: Double? = null, cause: Int? = null,
            expires: Int? = null, traitValue: Double? = null, reason: String? = null,
        ): Int {
            val index = sequence++
            events?.add(ArenaSupportEvent(
                sequence = index, turn = turn, type = type, actorId = actor?.id, targetId = target?.id,
                actionId = actionId, traitId = traitId, hpBefore = hpBefore, hpAfter = hpAfter,
                mpBeforeUnits = mpBefore, mpAfterUnits = mpAfter, amount = amount,
                shieldAfter = shieldAfter, castTurns = cast?.action?.cast ?: 0,
                remainingTurns = cast?.remaining ?: 0, castId = cast?.id,
                causeSequence = cause, shieldBefore = shieldBefore, effectExpiresAtTurn = expires,
                traitValue = traitValue, reason = reason,
            ))
            return index
        }

        private fun result(status: ArenaRunStatus, winner: Fighter?, turn: Int) = ArenaSupportResult(
            status, winner?.id, turn, fighters.associate { it.id to it.snapshot() },
            events?.toList() ?: emptyList(), ARENA_SUPPORT_RULES_VERSION, seed, rules,
        )

        fun simulate(): ArenaSupportResult {
            fighters.forEach { f ->
                emit(ArenaSupportEventType.START, 0, f, hpBefore = f.hp, hpAfter = f.hp,
                    mpBefore = f.mpUnits, mpAfter = f.mpUnits, shieldBefore = 0.0, shieldAfter = 0.0)
            }
            for (turn in 1..rules.safetyTurnLimit) {
                if (periodic(turn)) return result(ArenaRunStatus.COMPLETED, fighters.singleOrNull { it.hp > 0 }, turn)
                fighters.forEach { f ->
                    if (f.tauntUntil >= 0 && f.tauntUntil < turn) { f.tauntUntil = -1; f.needsNormalCompletion = false }
                }
                // Freeze both decisions AND their final prices before either pays or reveals a cast.
                val choices = fighters.mapIndexed { i, f ->
                    if (f.casting == null && f.sleepAt != turn) scriptedChoice(f, fighters[1 - i], turn) ?: choose(f, fighters[1 - i], turn) else null
                }
                fighters.forEachIndexed { i, f -> choices[i]?.let { start(f, fighters[1 - i], it, turn) } }
                fighters.forEachIndexed { i, f ->
                    if (choices[i]?.action?.isAttack == true) {
                        val trap = fighters[1 - i].effects[Kind.TRAP]?.takeIf { it.expires >= turn && it.appliedTurn < turn }
                        if (trap != null) {
                            consumeEffect(fighters[1 - i], Kind.TRAP, turn, f.casting?.startSequence)
                            control(fighters[1 - i], f, trap.support, turn, f.casting?.id ?: 0, f.casting?.startSequence ?: sequence)
                        }
                    }
                }
                fighters.forEach { f ->
                    if (f.sleepAt == turn) {
                        emit(ArenaSupportEventType.CAST_PAUSED, turn, f, opponent(f), f.casting, reason = "sleep")
                        f.sleepAt = -1
                        return@forEach
                    }
                    val cast = f.casting ?: return@forEach
                    cast.remaining--
                    emit(ArenaSupportEventType.CAST_PROGRESS, turn, f, cast = cast)
                }
                val ready = fighters.filter { it.casting?.remaining == 0 }
                val ordered = if (ready.size == 2 && !firstActsFirst(turn)) ready.reversed() else ready
                for (f in ordered) {
                    check(f.hp > 0)
                    val target = fighters.first { it !== f }
                    val cast = checkNotNull(f.casting)
                    f.casting = null
                    if (cast.action.isAttack) {
                        f.consecutiveSupports = 0
                        f.needsNormalCompletion = false
                        f.mustCompleteAttack = false
                        if (cast.action.isSkill) f.readyAt[cast.action.id] = turn + cast.action.cooldown + 1
                        if (attack(f, target, cast, turn)) return result(ArenaRunStatus.COMPLETED, f, turn)
                    } else {
                        applySupport(f, cast, turn)
                        if (f.sleepAt < turn && f.tauntUntil < turn) f.needsNormalCompletion = false
                    }
                }
                fighters.forEach { expire(it, turn) }
            }
            emit(ArenaSupportEventType.SAFETY_ABORT, rules.safetyTurnLimit, reason = "operational_limit")
            return result(ArenaRunStatus.ABORTED_SAFETY_LIMIT, null, rules.safetyTurnLimit)
        }

        private fun opponent(f: Fighter) = fighters.first { it !== f }
        private fun effectValue(f: Fighter, kind: Kind, turn: Int): Double =
            f.effects[kind]?.takeIf { it.expires >= turn }?.support?.magnitude ?: 0.0
        private fun hasDebuff(f: Fighter, turn: Int) = f.harmful.any { it.expires >= turn }
        private fun attackBuff(kind: Kind) = kind in setOf(Kind.SHOUT, Kind.AIM, Kind.BLESS, Kind.CONDENSE,
            Kind.COUNTER, Kind.PURSUIT, Kind.OPPORTUNITY, Kind.RAPID, Kind.RETRIBUTION, Kind.RESOLVE, Kind.EXECUTE)
        private fun removableBuff(kind: Kind) = attackBuff(kind) || kind in setOf(Kind.IRON, Kind.RESTRAINT,
            Kind.BASIC_GUARD, Kind.SKILL_GUARD, Kind.STABILIZE, Kind.FOCUS, Kind.OBSERVE, Kind.PIERCE)
        private fun threat(f: Fighter, turn: Int) = f.effects.keys.any {
            it in setOf(Kind.POISON_COAT, Kind.BURN_PREP, Kind.HEAL_BLOCK_PREP, Kind.EMBER, Kind.TRAP)
        } || f.casting?.action?.support?.kind in setOf(Kind.SMOKE, Kind.SLEEP, Kind.TAUNT, Kind.WRIST)

        private fun cannotAffordOwnedSkill(f: Fighter, target: Fighter, turn: Int): Boolean {
            val skills = f.attacks.filter { it.isSkill }
            if (skills.isEmpty()) return false
            // Build a minimal context to avoid recursive affordability evaluation. COST
            // predicates never depend on cannot-afford flags, only public scalar/support state.
            return skills.none { original ->
                val action = transform(f, target, original, turn)
                val c = ArenaGrowthContext(turn = turn, actionId = action.id, originalCastTurns = action.originalCast,
                    selfHpRatio = f.hp / f.maxHp, selfMpRatio = f.mpUnits.toDouble() / f.maxMpUnits,
                    targetHpRatio = target.hp / target.maxHp, targetMpRatio = target.mpUnits.toDouble() / target.maxMpUnits,
                    targetCastOriginalTurns = target.casting?.action?.originalCast ?: 0,
                    targetCastRemaining = target.casting?.remaining ?: 0,
                    targetCastingSupport = target.casting?.action?.isAttack == false,
                    selfSupports = f.effects.values.filter { it.expires >= turn }.map { it.support.id }.toSet())
                val legacy = listOf(ArenaProgressionCatalog.WARRIOR_A01, ArenaProgressionCatalog.MAGE_A01,
                    ArenaProgressionCatalog.CLERIC_A02).filter { f.tokenAvailable(it, turn) != null }.sumOf { f.traitValue(it) } +
                    if (action.originalCast in 2..3 && f.has(Kind.FOCUS, turn)) f.traitValue(ArenaProgressionCatalog.PALADIN_A02) else 0.0
                val percent = (legacy + f.growth.modifiers(c, ArenaGrowthPhase.COST).sumOf { it.value }).coerceAtMost(30.0)
                val price = max(MP_SCALE, ceil(action.mp * MP_SCALE * (1 - percent / 100) - 1e-9).toInt()) + action.extraMp * MP_SCALE
                price <= f.mpUnits
            }
        }

        private fun context(
            f: Fighter, target: Fighter, action: Action, turn: Int,
            event: ArenaGrowthEvent = ArenaGrowthEvent.NONE, cast: Casting? = null,
            sourceSupportId: String = action.support?.id ?: "", cause: Int = sequence,
        ): ArenaGrowthContext {
            val poison = target.harmful("poison", turn).filter { it.sourceId == f.id }
            return ArenaGrowthContext(
                turn, event, action.id, sourceSupportId, action.originalCast,
                action.id == BASIC, !action.isAttack, f.hp / f.maxHp, f.hp / f.maxHp,
                f.mpUnits.toDouble() / f.maxMpUnits, target.hp / target.maxHp,
                target.mpUnits.toDouble() / target.maxMpUnits, f.shield, target.shield,
                f.casting?.action?.originalCast ?: 0, target.casting?.action?.originalCast ?: 0,
                target.casting?.remaining ?: 0, target.casting?.action?.isAttack == false,
                target.casting?.action?.support?.kind in setOf(Kind.HEAL, Kind.REGEN, Kind.BANDAGE,
                    Kind.LAY_HANDS, Kind.IRON, Kind.SHIELD, Kind.LOW_SHIELD, Kind.SKILL_GUARD),
                f.effects.values.filter { it.expires >= turn }.map { it.support.id }.toSet(),
                (target.effects.values.filter { it.expires >= turn }.map { it.support.id } +
                    target.harmful.filter { it.expires >= turn && it.sourceId == f.id }.map { it.supportId }).toSet(),
                supportRemainingTurns = f.effects.values.associate { it.support.id to max(0, it.expires - turn) },
                selfCannotAffordOwnedSkill = f.hasTrait("AT9_PALADIN_C04") && cannotAffordOwnedSkill(f, target, turn),
                targetCannotAffordOwnedSkill = f.hasTrait("AT9_ROGUE_C07") && cannotAffordOwnedSkill(target, f, turn),
                targetHasDamageReduction = protection(target, action, turn).second > 0,
                targetHasAttackBuff = target.effects.keys.any(::attackBuff),
                targetHasOwnBurn = target.harmful("burn", turn).any { it.sourceId == f.id },
                ownPoisonLayers = poison.size, ownPoisonFutureTicks = poison.maxOfOrNull { max(0, it.expires - turn) } ?: 0,
                targetMirrorOpportunities = target.effects[Kind.MIRROR]?.charges ?: 0,
                targetHasStealth = target.has(Kind.STEALTH, turn),
                startSelfMpRatio = cast?.startContext?.selfMpRatio ?: f.mpUnits.toDouble() / f.maxMpUnits,
                startTargetCastOriginalTurns = cast?.startContext?.targetCastOriginalTurns ?: target.casting?.action?.originalCast ?: 0,
                startTargetCastRemaining = cast?.startContext?.targetCastRemaining ?: target.casting?.remaining ?: 0,
                startTargetCastingSupport = cast?.startContext?.targetCastingSupport ?: (target.casting?.action?.isAttack == false),
                startTargetCastingRecoveryOrProtection = cast?.startContext?.targetCastingRecoveryOrProtection ?: false,
                capturedHealingMaxHpRatio = (cast?.capturedAmount ?: if (action.support?.kind == Kind.HEAL_TRACK)
                    target.recentHealing.filter { it.turn >= turn - 1 && it.sequence !in f.usedRecent }
                        .let { if (f.core("C")) it.sumOf { e -> e.amount } else it.lastOrNull()?.amount ?: 0.0 }
                    else 0.0) / target.maxHp,
                supportInstanceId = cast?.id ?: 0, eventSequence = cause,
                castId = cast?.id, castStartedTurn = cast?.startTurn, selfCastId = f.casting?.id, selfCastActionId = f.casting?.action?.id ?: "",
            )
        }

        private fun observe(f: Fighter, c: ArenaGrowthContext) {
            f.growth.observe(c).forEach {
                emit(ArenaSupportEventType.TRAIT_TRIGGERED, c.turn, f, traitId = it.traitId,
                    cause = it.causeSequence, expires = it.expiresAtTurn, traitValue = it.value, reason = "prepared")
            }
        }
        private fun growthModifiers(f: Fighter, c: ArenaGrowthContext, phase: ArenaGrowthPhase, cast: Casting? = null): List<ArenaGrowthModifier> {
            val reserved = cast?.growthModifiers.orEmpty().filter { it.phase == phase &&
                (it.expiresAtTurn == null || it.expiresAtTurn >= c.turn) }
            return (reserved + f.growth.modifiers(c, phase)).distinctBy { it.traitId }
        }
        private fun appliedGrowth(f: Fighter, target: Fighter, modifiers: List<ArenaGrowthModifier>, turn: Int, cause: Int, reason: String) {
            modifiers.forEach { emit(ArenaSupportEventType.TRAIT_TRIGGERED, turn, f, target,
                traitId = it.traitId, cause = it.causeSequence.takeIf { n -> n > 0 } ?: cause,
                traitValue = it.value, reason = reason) }
            f.growth.consume(modifiers)
        }
        private fun supportTriggered(f: Fighter, target: Fighter, id: String, turn: Int, cause: Int, value: Double, reason: String) {
            if (value <= 0 || cause >= sequence) return
            emit(ArenaSupportEventType.SUPPORT_TRIGGERED, turn, f, target, actionId = id,
                cause = cause, traitValue = value, reason = reason)
        }
        private fun offensiveContributors(f: Fighter, target: Fighter, action: Action, turn: Int): Map<String, Double> {
            val bonuses = linkedMapOf<String, Double>()
            fun add(kind: Kind, eligible: Boolean = true, replacement: Double? = null) {
                val active = f.effects[kind]?.takeIf { it.expires >= turn } ?: return
                if (eligible) bonuses[active.support.id] = replacement ?: active.support.magnitude
            }
            add(Kind.SHOUT, replacement = if (f.core("A")) f.coreValue("A", "replacementShoutDamageBonusPercent", 10.0) else null)
            add(Kind.AIM, !(f.heroClass == HeroClass.RANGER && f.core("A")))
            add(Kind.BLESS)
            add(Kind.STEALTH, !(f.heroClass == HeroClass.ROGUE && f.core("A")),
                replacement = if (target.has(Kind.TRUTH, turn)) 50.0 else null)
            add(Kind.CONDENSE, action.isSkill && !(f.heroClass == HeroClass.MAGE && f.core("A")))
            add(Kind.RAPID, action.id == BASIC)
            add(Kind.COUNTER, action.id == BASIC && !f.core("C"))
            add(Kind.OPPORTUNITY, action.id == BASIC)
            add(Kind.PURSUIT, action.id == BASIC)
            add(Kind.RETRIBUTION, action.isSkill)
            add(Kind.EXECUTE, action.isSkill)
            add(Kind.RESOLVE, f.hp <= f.maxHp * .35)
            if ("rogue_a" in action.coreIds) bonuses["ARENA_SUP_ROGUE_01"] = 50.0
            if ("mage_a" in action.coreIds) bonuses["ARENA_SUP_MAGE_07"] = 60.0
            if ("ranger_a" in action.coreIds) bonuses["ARENA_SUP_RANGER_01"] = 35.0
            val best = bonuses.maxWithOrNull(compareBy<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            return buildMap {
                best?.let { put(it.key, it.value) }
                if (action.id == BASIC && f.has(Kind.COUNTER, turn) && f.core("C")) put("ARENA_SUP_FIGHTER_04", 50.0)
                if (f.has(Kind.HEAL_TRACK, turn)) put("ARENA_SUP_RANGER_10", if (f.core("C")) 30.0 else 40.0)
                if (action.isSkill && f.has(Kind.BURN_PREP, turn) && f.core("B")) put("ARENA_SUP_MAGE_11", 20.0)
                if (f.has(Kind.MP_DRAIN, turn) && f.core("C")) put("ARENA_SUP_ROGUE_03", 25.0)
            }
        }

        private fun coreEvent(f: Fighter, branch: String, turn: Int, cause: Int, value: Double = 1.0) {
            emit(ArenaSupportEventType.TRAIT_TRIGGERED, turn, f,
                traitId = "AT9_${f.heroClass.name}_${branch}_CORE", cause = cause,
                traitValue = value, reason = "core_transform_applied")
        }
        private fun coreReady(f: Fighter, branch: String, turn: Int) = f.coreTokens[branch]?.first?.let { it >= turn } == true

        private fun statusBlocked(f: Fighter, source: Fighter, kind: String, supportId: String, turn: Int, cause: Int): Boolean {
            val same = f.sameStatusGuard
            val guard = f.effects[Kind.STATUS_GUARD]?.takeIf { it.expires >= turn }
            if (same?.first == kind && same.second >= turn) {
                f.sameStatusGuard = null
                coreEvent(f, "C", turn, cause)
            } else if (guard != null) consumeEffect(f, Kind.STATUS_GUARD, turn, cause)
            else return false
            val event = emit(ArenaSupportEventType.STATUS_BLOCKED, turn, f, source,
                actionId = guard?.support?.id ?: "ARENA_SUP_CLERIC_03", cause = cause, reason = kind)
            observe(f, context(f, source, Action(supportId, 1, 0), turn, ArenaGrowthEvent.STATUS_BLOCKED,
                sourceSupportId = guard?.support?.id ?: "ARENA_SUP_CLERIC_03", cause = event).copy(appliedStatusKind = kind))
            return true
        }
        private fun addStatus(source: Fighter, target: Fighter, kind: String, support: Support, turn: Int,
            duration: Int, magnitude: Double, cause: Int, explicitExpiry: Int? = null): Boolean {
            if (target.hp <= 0 || duration <= 0 || (kind == "poison" && target.harmful("poison", turn).size >= 3)) return false
            if (statusBlocked(target, source, kind, support.id, turn, cause)) return false
            val shorten = target.effects[Kind.SANCTUARY]
                ?.takeIf { it.expires >= turn }
                ?.support?.magnitude?.roundToInt()
                ?.coerceAtLeast(0)
                ?: 0
            val expires = explicitExpiry ?: turn + max(1, duration - shorten)
            if (kind != "poison") target.harmful.removeAll { it.kind == kind }
            target.harmful += Harmful(kind, source.id, support.id, turn, expires, magnitude, cause)
            val event = emit(ArenaSupportEventType.STATUS_APPLIED, turn, source, target,
                actionId = support.id, amount = magnitude, expires = expires, cause = cause, reason = kind)
            if (shorten > 0 && duration > 1) supportTriggered(target, source, "ARENA_SUP_CLERIC_08", turn, event, shorten.toDouble(), "harmful_duration_shortened")
            if (shorten > 0 && duration > 1) observe(target,
                context(target, source, Action(support.id, 1, 0), turn, ArenaGrowthEvent.STATUS_SHORTENED,
                    sourceSupportId = "ARENA_SUP_CLERIC_08", cause = event).copy(appliedStatusKind = kind))
            return true
        }
        private fun cleanse(
            f: Fighter,
            source: Fighter,
            action: Action,
            turn: Int,
            cause: Int,
            accuracyOnly: Boolean,
            maximum: Int = 1,
        ): Int {
            val indexed = f.harmful.withIndex().filter { it.value.expires >= turn }
            val byId = indexed.associate { it.index to it.value }
            val plan = cleanseRemovalPlan(
                indexed.map { (index, harmful) ->
                    CleanseRemovalCandidate(
                        id = index,
                        kind = harmful.kind,
                        impact = harmful.magnitude * (harmful.expires - turn + 1),
                    )
                },
                accuracyOnly,
                maximum,
            )
            plan.forEach { ids ->
                val selected = ids.mapNotNull(byId::get)
                val kind = selected.first().kind
                f.harmful.removeAll { harmful -> selected.any { it === harmful } }
                val event = emit(ArenaSupportEventType.STATUS_REMOVED, turn, f, f, actionId = action.id,
                    cause = cause, reason = kind)
                observe(f, context(f, source, action, turn, ArenaGrowthEvent.CLEANSED, cause = event).copy(removedStatusKind = kind))
                if (f.heroClass == HeroClass.CLERIC && f.core("C") && action.id == "ARENA_SUP_CLERIC_03") {
                    f.sameStatusGuard = kind to turn + 3
                    coreEvent(f, "C", turn, event)
                }
            }
            return plan.size
        }
        private fun dispel(f: Fighter, target: Fighter, action: Action, turn: Int, cause: Int, mode: String): Boolean {
            val active = target.effects.values.filter { it.expires >= turn && when (mode) {
                "illusion" -> it.support.kind in setOf(Kind.STEALTH, Kind.MIRROR)
                "restricted" -> removableBuff(it.support.kind)
                else -> true
            } }.sortedWith(compareBy<Active> { if (it.support.kind == Kind.STEALTH) 0 else 1 }
                .thenByDescending { it.expires - turn }.thenBy { it.support.id }).firstOrNull() ?: return false
            val before = target.shield
            val consumedIllusionCharge = mode == "illusion" && active.charges > 1
            if (consumedIllusionCharge) active.charges-- else target.effects.remove(active.support.kind)
            val shield = active.support.kind in setOf(Kind.SHIELD, Kind.LOW_SHIELD, Kind.LAY_HANDS)
            if (shield) target.shield = 0.0
            val event = emit(
                if (consumedIllusionCharge) ArenaSupportEventType.STATUS_REMOVED else ArenaSupportEventType.EFFECT_EXPIRED,
                turn,
                target,
                f,
                actionId = active.support.id,
                shieldBefore = before.takeIf { shield },
                shieldAfter = 0.0.takeIf { shield },
                cause = cause,
                reason = if (consumedIllusionCharge) "illusion_charge_removed" else "dispelled",
            )
            supportTriggered(f, target, action.id, turn, event, 1.0, "beneficial_status_removed")
            observe(f, context(f, target, action, turn, ArenaGrowthEvent.DISPELLED,
                sourceSupportId = action.id, cause = event).copy(removedStatusKind = active.support.kind.name.lowercase(),
                supportWasAttackBuff = attackBuff(active.support.kind)))
            if (!consumedIllusionCharge) observe(target, context(target, f, action, turn, ArenaGrowthEvent.ENEMY_SUPPORT_EXPIRED,
                sourceSupportId = active.support.id, cause = event))
            if (f.heroClass == HeroClass.RANGER && f.core("B") && action.id == "ARENA_SUP_RANGER_02") {
                f.coreTokens["B"] = turn + 3 to 0.0; coreEvent(f, "B", turn, event)
            }
            if (f.heroClass == HeroClass.PALADIN && f.core("B") && action.id == "ARENA_SUP_PALADIN_03") {
                f.coreTokens["B"] = turn + 4 to 0.0; coreEvent(f, "B", turn, event)
            }
            return true
        }
        private fun dispelUpTo(
            f: Fighter,
            target: Fighter,
            action: Action,
            turn: Int,
            cause: Int,
            mode: String,
            maximum: Int,
        ): Int {
            var removed = 0
            repeat(maximum.coerceAtLeast(0)) {
                if (!dispel(f, target, action, turn, cause, mode)) return removed
                removed++
            }
            return removed
        }
        private fun control(source: Fighter, target: Fighter, support: Support, turn: Int, castId: Int, cause: Int): Boolean {
            var reason = "control_immunity"
            if (!target.immune(turn)) {
                val stats = source.input.fighter.stats.values()
                val strength = stats[source.heroClass.primaryStatIndex] * .7 + stats[source.heroClass.secondaryStatIndex] * .3
                val ts = target.input.fighter.stats
                val resistance = ts.constitution * .5 + ts.wisdom * .3 + ts.charisma * .2
                val rankAccuracy = if (support.kind in setOf(Kind.SLEEP, Kind.TAUNT, Kind.TRAP))
                    support.secondary / 100 else 0.0
                val chance = (.65 + .30 * (strength - resistance) / max(strength + resistance, 1.0) + rankAccuracy)
                    .coerceIn(.35, .95)
                if (random(seed, source.randomKey, turn, castId, 7) < chance) {
                    if (statusBlocked(target, source, support.kind.name.lowercase(), support.id, turn, cause)) return false
                    target.immuneUntil = turn + 2
                    target.needsNormalCompletion = true
                    when (support.kind) {
                        Kind.SLEEP -> target.sleepAt = turn + 1
                        Kind.TAUNT -> target.tauntUntil = turn + support.duration
                        Kind.TRAP -> target.casting?.let { it.remaining++ }
                        else -> error("Unknown control")
                    }
                    emit(ArenaSupportEventType.CONTROL_APPLIED, turn, source, target, actionId = support.id,
                        amount = 1.0, cause = cause, expires = if (support.kind == Kind.TAUNT) target.tauntUntil else turn + 1,
                        reason = support.kind.name.lowercase())
                    if (support.kind == Kind.TRAP) emit(ArenaSupportEventType.CAST_DELAYED, turn, target, source,
                        cast = target.casting, cause = cause, reason = "trap", amount = 1.0)
                    return true
                }
                reason = "resisted"
            }
            val event = emit(ArenaSupportEventType.CONTROL_RESISTED, turn, target, source, actionId = support.id,
                cause = cause, reason = reason)
            observe(source, context(source, target, Action(support.id, 1, 0), turn,
                ArenaGrowthEvent.CONTROL_RESISTED, sourceSupportId = support.id, cause = event))
            return false
        }
        private fun wake(f: Fighter, source: Fighter, turn: Int, cause: Int) {
            if (f.hp <= 0 || f.sleepAt < turn) return
            f.sleepAt = -1
            f.needsNormalCompletion = false
            emit(ArenaSupportEventType.AWAKENED, turn, f, source, cause = cause, reason = "hp_damage")
        }
        private fun heal(f: Fighter, target: Fighter, action: Action, turn: Int, requested: Double,
            cause: Int, cast: Casting? = null, hot: Boolean = false, supportInstanceId: Int = cast?.id ?: 0): Double {
            if (f.hp <= 0 || requested <= 0) return 0.0
            val before = f.hp
            val c = context(f, target, action, turn, if (hot) ArenaGrowthEvent.HOT_HEALED else ArenaGrowthEvent.HEALED,
                cast, cause = cause).copy(supportInstanceId = supportInstanceId)
            val modifiers = growthModifiers(f, c, ArenaGrowthPhase.HEAL)
            var amount = requested
            modifiers.forEach { m -> amount += when(m.unit) {
                ArenaProgressionEffectUnit.MAX_HP_HEAL_PERCENT -> f.maxHp * m.value / 100
                else -> requested * m.value / 100
            } }
            val penalty = maxOf(
                f.harmful("heal_reduction", turn).maxOfOrNull { it.magnitude } ?: 0.0,
                f.arenaHealingReduction?.takeIf { it.expires >= turn }?.value ?: 0.0,
            )
            amount *= 1 - penalty.coerceIn(0.0, 100.0) / 100
            f.hp = min(f.maxHp, before + amount)
            val actual = f.hp - before
            if (actual <= 0) return 0.0
            val event = emit(ArenaSupportEventType.HEAL_APPLIED, turn, f, f, cast, actionId = action.id,
                hpBefore = before, hpAfter = f.hp, amount = actual, cause = cause,
                reason = if (hot) "hot" else "direct_heal")
            f.recentHealing += Recent(turn, event, actual)
            f.recentHealing.removeAll { it.turn < turn - 1 }
            appliedGrowth(f, target, modifiers, turn, event, "healing_bonus_applied")
            observe(f, c.copy(healing = actual, healingMaxHpRatio = actual / f.maxHp,
                selfHpRatio = f.hp / f.maxHp, selfHpBeforeRatio = before / f.maxHp, eventSequence = event))
            observe(target, context(target, f, action, turn, ArenaGrowthEvent.ENEMY_HEALED,
                sourceSupportId = action.id, cause = event).copy(healing = actual, healingMaxHpRatio = actual / f.maxHp))
            return actual
        }
        private fun finishDeaths(turn: Int, cause: Int): Boolean {
            val dead = fighters.filter { it.hp <= 0 }
            if (dead.isEmpty()) return false
            dead.forEach { f ->
                emit(ArenaSupportEventType.KO, turn, f, opponent(f), hpBefore = 0.0, hpAfter = 0.0, cause = cause)
                f.casting?.let { emit(ArenaSupportEventType.CAST_CANCELLED_KO, turn, f, opponent(f), it,
                    hpBefore = 0.0, hpAfter = 0.0, mpBefore = f.mpUnits, mpAfter = f.mpUnits, cause = cause) }
                f.casting = null
            }
            emit(ArenaSupportEventType.END, turn, fighters.singleOrNull { it.hp > 0 }, cause = cause,
                reason = if (dead.size == 2) "simultaneous_dot_draw" else null)
            return true
        }
        private fun periodic(turn: Int): Boolean {
            // Freeze all pending damage before settling either side. Both lethal DOTs form a draw.
            data class Tick(val target: Fighter, val source: Fighter, val dot: Harmful,
                val requested: Double, val modifiers: List<ArenaGrowthModifier>)
            val ticks = fighters.flatMap { target -> target.harmful.filter {
                it.kind in setOf("poison", "burn", "bleed") && it.appliedTurn < turn && it.expires >= turn
            }.map { dot ->
                val source = fighters.first { it.id == dot.sourceId }
                val reduction = listOf(effectValue(target, Kind.IRON, turn),
                    effectValue(target, Kind.RESTRAINT, turn) + (target.effects[Kind.RESTRAINT]?.reductionBonus ?: 0.0) * 100).max() / 100
                val modifiers = growthModifiers(source, context(source, target, Action(dot.supportId, 1, 0), turn,
                    sourceSupportId = dot.supportId, cause = dot.cause), ArenaGrowthPhase.DOT)
                    .filter { it.traitId == "AT9_MAGE_B06" }
                Tick(target, source, dot, dot.magnitude * (1 + modifiers.sumOf { it.value } / 100) /
                    target.defense * (1 - reduction), modifiers)
            } }
            for ((target, source, dot, requested, tickMods) in ticks) {
                val before = target.hp
                if (before <= 0.0) continue
                val beforeShield = target.shield
                val absorbed = min(target.shield, requested)
                target.shield -= absorbed
                if (absorbed > 0) {
                    val shield = target.effects.values.firstOrNull { it.support.kind in setOf(Kind.SHIELD, Kind.LOW_SHIELD, Kind.LAY_HANDS) }
                    emit(ArenaSupportEventType.SHIELD_ABSORBED, turn, target, source, actionId = shield?.support?.id,
                        shieldBefore = beforeShield, shieldAfter = target.shield, amount = absorbed, cause = dot.cause)
                    if (target.shield == 0.0 && shield != null) removeEffect(target, shield.support.kind, turn, dot.cause)
                }
                target.hp = max(0.0, before - max(0.0, requested - absorbed))
                val event = emit(ArenaSupportEventType.DOT_DAMAGE, turn, source, target, actionId = dot.supportId,
                    hpBefore = before, hpAfter = target.hp, amount = before - target.hp, cause = dot.cause, reason = dot.kind)
                if (before > target.hp || absorbed > 0) appliedGrowth(source, target, tickMods, turn, event, "dot_bonus_applied")
                // No reactions, lifesteal, attack rights, or healing are triggered by a tick.
                if (before > target.hp && target.hp > 0) wake(target, source, turn, event)
            }
            if (finishDeaths(turn, sequence - 1)) return true
            fighters.forEach { f ->
                f.effects[Kind.REGEN]?.takeIf { it.appliedTurn < turn && it.expires >= turn }?.let {
                    heal(f, opponent(f), Action(it.support.id, it.support.cast, 0, support = it.support), turn,
                        f.maxHp * it.support.magnitude / 100, it.causeSequence, hot = true, supportInstanceId = it.instanceId)
                }
                if (f.healCoreHotUntil >= turn) {
                    val s = catalog.getValue("ARENA_SUP_CLERIC_01").resolved()
                    heal(f, opponent(f), Action(s.id, 2, 0, support = s), turn,
                        f.maxHp * f.coreValue("A", "hotHealMaxHpPercentPerTick", 3.0) / 100,
                        f.healCoreHotCause, hot = true)
                }
            }
            return false
        }

        private fun firstActsFirst(turn: Int): Boolean {
            val a = fighters[0].input.fighter.stats.dexterity
            val b = fighters[1].input.fighter.stats.dexterity
            val scale = maxOf(1.0, a, b)
            val denominator = max(1.0 / scale, a / scale + b / scale)
            val edge = (rules.initiativeSensitivity * (a / scale - b / scale) / denominator)
                .coerceIn(-rules.initiativeMaxEdge, rules.initiativeMaxEdge)
            return random(seed, initiativeKey, turn, 0, 1) < .5 + edge
        }

        private fun start(f: Fighter, target: Fighter, choice: Choice, turn: Int) {
            val action = choice.action
            action.arena?.let { arena ->
                check(turn >= arena.earliestTurn)
                check(!arena.oncePerBattle || action.id !in f.usedArenaOnce)
                if (arena.oncePerBattle) f.usedArenaOnce += action.id
                if ("arena_counter_rush" in action.coreIds) {
                    f.arenaEffectUseCounts[action.id] =
                        (f.arenaEffectUseCounts[action.id] ?: 0) + 1
                }
            }
            val reservedTraits = linkedMapOf<String, TraitToken>()
            fun reserve(traitId: String, eligible: Boolean) {
                if (!eligible) return
                val token = f.tokenAvailable(traitId, turn) ?: return
                f.traitTokens.remove(traitId)
                reservedTraits[traitId] = token
            }
            reserve(ArenaProgressionCatalog.ROGUE_A01, action.isAttack)
            reserve(ArenaProgressionCatalog.PALADIN_A01, action.isAttack)
            reserve(ArenaProgressionCatalog.WARRIOR_A02, action.id == BASIC)
            choice.discounts.keys.forEach { traitId ->
                if (traitId == ArenaProgressionCatalog.WARRIOR_A01 ||
                    traitId == ArenaProgressionCatalog.MAGE_A01 ||
                    traitId == ArenaProgressionCatalog.CLERIC_A02) {
                    f.traitTokens.remove(traitId)
                }
            }
            val startContext = choice.observedContext ?: context(f, target, action, turn)
            val growthReserved = if (action.isAttack) listOf(ArenaGrowthPhase.ACCURACY, ArenaGrowthPhase.DAMAGE, ArenaGrowthPhase.SHIELD)
                .flatMap { f.growth.modifiers(startContext, it) }.filter { it.expiresAtTurn != null } else emptyList()
            f.growth.consume(growthReserved + choice.growthDiscounts)
            var captured = when (action.support?.kind) {
                Kind.COUNTER, Kind.RETRIBUTION -> f.recentDamage?.takeIf { it.turn >= turn - 1 }?.let {
                    f.usedRecent += it.sequence; it.amount
                } ?: 0.0
                Kind.HEAL_TRACK -> target.recentHealing.filter { it.turn >= turn - 1 && it.sequence !in f.usedRecent }
                    .let { if (f.core("C")) it else it.takeLast(1) }.sumOf { f.usedRecent += it.sequence; it.amount }
                else -> 0.0
            }
            if (action.effectKey == "COUNTER_STATUS") {
                val maximum = action.value("cleanse").roundToInt().coerceAtLeast(0)
                val indexed = f.harmful.withIndex().filter { it.value.expires >= turn }
                val byId = indexed.associate { it.index to it.value }
                val plan = cleanseRemovalPlan(
                    indexed.map { (index, harmful) ->
                        CleanseRemovalCandidate(
                            id = index,
                            kind = harmful.kind,
                            impact = harmful.magnitude * (harmful.expires - turn + 1),
                        )
                    },
                    accuracyOnly = false,
                    maximum = maximum,
                )
                plan.forEach { ids ->
                    val removed = ids.mapNotNull(byId::get)
                    f.harmful.removeAll { harmful -> removed.any { it === harmful } }
                    emit(ArenaSupportEventType.STATUS_REMOVED, turn, f, f, actionId = action.id,
                        cause = removed.minOf { it.cause }, reason = removed.first().kind)
                }
                captured += plan.size
                f.currentAttackBonusPercent = if (captured > 0.0) action.value("bonus") else 0.0
            }
            if (f.tauntUntil >= turn && action.isAttack) f.tauntUntil = -1
            if ("warrior_a" in action.coreIds) f.coreTokens.remove("A")
            if ("ranger_b" in action.coreIds) f.coreTokens.remove("B")
            if ("rogue_a" in action.coreIds) consumeEffect(f, Kind.STEALTH, turn, sequence)
            if ("mage_a" in action.coreIds) consumeEffect(f, Kind.CONDENSE, turn, sequence)
            if (action.support?.kind == Kind.EXECUTE && f.core("B") && coreReady(f, "B", turn)) f.coreTokens.remove("B")
            val before = f.mpUnits
            check(choice.costUnits in 0..before)
            f.mpUnits -= choice.costUnits
            val cast = Casting(f.nextCastId++, action, action.cast, turn, sequence,
                reservedTraits.toMap(), growthReserved, startContext, captured)
            f.casting = cast
            val source = emit(ArenaSupportEventType.CAST_START, turn, f,
                if (action.isAttack) target else f, cast,
                traitId = choice.discounts.keys.singleOrNull(),
                mpBefore = before, mpAfter = f.mpUnits, amount = choice.costUnits / 1000.0,
                traitValue = choice.discountPercent.takeIf { it > 0.0 })
            appliedGrowth(f, target, choice.growthDiscounts, turn, source, "discount_paid")
            action.coreIds.filterNot { it.startsWith("arena_") }.forEach { core ->
                coreEvent(f, core.substringAfter('_').uppercase(), turn, source)
            }
            if ("arena_counter_rush" in action.coreIds) {
                emit(ArenaSupportEventType.STATUS_APPLIED, turn, f, target, cast,
                    cause = source, amount = 1.0, reason = "counter_rush_prepare_reduced")
            }
            observe(target, context(target, f, action, turn, ArenaGrowthEvent.ENEMY_PAID, cause = source)
                .copy(paidMp = choice.costUnits / 1000.0))
            choice.discounts.forEach { (traitId, value) ->
                if (traitId == ArenaProgressionCatalog.RANGER_A01 ||
                    traitId == ArenaProgressionCatalog.MAGE_A02 ||
                    traitId == ArenaProgressionCatalog.PALADIN_A02) {
                    emit(ArenaSupportEventType.TRAIT_TRIGGERED, turn, f, f, cast,
                        traitId = traitId, cause = source, traitValue = value,
                        reason = "discount_paid")
                }
            }
        }

        private fun applySupport(f: Fighter, cast: Casting, turn: Int) {
            val support = checkNotNull(cast.action.support)
            val target = opponent(f)
            f.lastSupportCompletedTurn = turn
            f.readyAt[support.id] = turn + support.cooldown + 1
            if (support.oncePerBattle) {
                f.supportUseCounts[support.id] = (f.supportUseCounts[support.id] ?: 0) + 1
            }
            f.mustCompleteAttack = true
            f.consecutiveSupports++
            var charges = support.charges
            if (support.kind == Kind.BASIC_SHATTER && f.core("B")) charges = f.coreValue("B", "replacementApplications", 2.0).toInt()
            if (support.kind == Kind.MIRROR && f.core("C")) charges = f.coreValue("C", "illusionCount", 2.0).toInt()
            if (support.kind == Kind.LIFESTEAL && f.core("B")) charges = 1
            val shieldAction = support.kind in setOf(Kind.SHIELD, Kind.LOW_SHIELD) ||
                (support.kind == Kind.LAY_HANDS && f.core("C"))
            val beforeShield = f.shield
            val c = context(f, target, cast.action, turn, ArenaGrowthEvent.SUPPORT_COMPLETED, cast)
            val shieldMods = if (shieldAction) growthModifiers(f, c, ArenaGrowthPhase.SHIELD) else emptyList()
            if (shieldAction) {
                val base = when (support.kind) {
                    Kind.SHIELD -> f.maxHp * support.magnitude / 100
                    else -> min(f.maxHp * support.magnitude / 100, sqrt(f.input.fighter.stats.charisma) * support.secondary)
                }
                val extra = shieldMods.sumOf { if (it.unit == ArenaProgressionEffectUnit.MAX_HP_SHIELD_PERCENT)
                    f.maxHp * it.value / 100 else base * it.value / 100 }
                if (base + extra > f.shield) {
                    f.effects.keys.filter { it in setOf(Kind.SHIELD, Kind.LOW_SHIELD, Kind.LAY_HANDS) }.toList().forEach {
                        removeEffect(f, it, turn, cast.startSequence)
                    }
                    f.shield = base + extra
                }
            }
            val timed = support.duration > 0 && support.kind !in setOf(Kind.SMOKE, Kind.WRIST, Kind.TAUNT, Kind.SLEEP)
            val duration = if (shieldAction && support.kind == Kind.LAY_HANDS) f.coreValue("C", "shieldDurationTurns", 4.0).toInt() else support.duration
            if (timed || (shieldAction && duration > 0)) {
                check(!f.has(support.kind, turn)) { "Active support must not be wastefully refreshed" }
                val reductionMods = if (support.kind == Kind.RESTRAINT) growthModifiers(f, c, ArenaGrowthPhase.REDUCTION) else emptyList()
                f.effects[support.kind] = Active(support, turn + duration, charges, turn, cast.id, cast.capturedAmount,
                    cast.startSequence, reductionMods.sumOf { it.value } / 100)
                if (reductionMods.isNotEmpty()) appliedGrowth(f, target, reductionMods, turn, cast.startSequence, "support_reduction_bonus_applied")
            }
            val applied = emit(ArenaSupportEventType.SUPPORT_APPLIED, turn, f, f, cast,
                hpBefore = f.hp, hpAfter = f.hp, shieldBefore = beforeShield.takeIf { shieldAction },
                shieldAfter = f.shield.takeIf { shieldAction }, amount = (f.shield - beforeShield).takeIf { shieldAction } ?: 0.0,
                expires = (turn + duration).takeIf { duration > 0 })
            if (shieldAction) appliedGrowth(f, target, shieldMods, turn, applied, "shield_bonus_applied")
            observe(f, c.copy(eventSequence = applied))
            when (support.kind) {
                Kind.HEAL -> {
                    val before = f.hp
                    val clericA01 = f.traitValue(ArenaProgressionCatalog.CLERIC_A01)
                    val bonus = if (clericA01 > 0 && before <= f.maxHp * .35) clericA01 / 100 else 0.0
                    val fraction = if (f.core("A")) f.coreValue("A", "instantHealMaxHpPercent", 8.0) / 100 else support.magnitude / 100
                    val healed = heal(f, target, cast.action, turn, f.maxHp * (fraction + bonus), applied, cast)
                    f.healReadyAt = turn + 3
                    if (f.core("A")) {
                        f.healCoreHotUntil = turn + 4; f.healCoreHotCause = applied
                        coreEvent(f, "A", turn, applied)
                    }
                    if (bonus > 0 && healed > min(f.maxHp - before, f.maxHp * fraction)) {
                        emit(ArenaSupportEventType.TRAIT_TRIGGERED, turn, f, f, cast,
                            traitId = ArenaProgressionCatalog.CLERIC_A01, cause = applied,
                            traitValue = clericA01, reason = "healing_bonus_applied")
                    }
                    if (healed >= f.maxHp * .05) prepareTrait(f, ArenaProgressionCatalog.CLERIC_A02, turn, f.recentHealing.last().sequence)
                }
                Kind.BANDAGE, Kind.LAY_HANDS -> {
                    val healed = if (shieldAction) { coreEvent(f, "C", turn, applied); 0.0 }
                    else heal(f, target, cast.action, turn, min(f.maxHp * support.magnitude / 100,
                        sqrt(if (support.kind == Kind.BANDAGE) f.input.fighter.stats.constitution else f.input.fighter.stats.charisma) * support.secondary), applied, cast)
                    if (support.kind == Kind.LAY_HANDS) observe(f, c.copy(healing = healed, selfShield = f.shield, eventSequence = applied))
                    f.healReadyAt = turn + 3
                }
                Kind.CLEANSE, Kind.CLEANSE_ACCURACY -> cleanse(
                    f,
                    target,
                    cast.action,
                    turn,
                    applied,
                    support.kind == Kind.CLEANSE_ACCURACY,
                    support.magnitude.roundToInt(),
                )
                Kind.SMOKE, Kind.WRIST -> addStatus(f, target, if (support.kind == Kind.SMOKE) "accuracy" else "basic_weakness",
                    support, turn, support.duration, support.magnitude, applied)
                Kind.SLEEP, Kind.TAUNT -> control(f, target, support, turn, cast.id, applied)
                Kind.REVEAL, Kind.DISPEL -> dispelUpTo(
                    f,
                    target,
                    cast.action,
                    turn,
                    applied,
                    if (support.kind == Kind.REVEAL) "illusion" else "restricted",
                    support.magnitude.roundToInt(),
                )
                Kind.POISON_ACCELERATE -> {
                    val poison = target.harmful("poison", turn)
                    if (poison.size in 1..2) {
                        val last = poison.maxBy { it.expires }
                        addStatus(f, target, "poison", support, turn, max(1, last.expires - turn),
                            last.magnitude * (1 + support.secondary / 100), applied,
                            explicitExpiry = last.expires)
                    }
                }
                Kind.SHOUT -> { f.shoutBasicHits = 0; if (f.core("A")) coreEvent(f, "A", turn, applied, 10.0) }
                Kind.BASIC_SHATTER -> if (f.core("B")) coreEvent(f, "B", turn, applied, 60.0)
                Kind.COUNTER -> if (f.core("C")) coreEvent(f, "C", turn, applied, 50.0)
                Kind.MIRROR -> if (f.core("C")) coreEvent(f, "C", turn, applied, 2.0)
                Kind.MP_DRAIN -> if (f.core("C")) coreEvent(f, "C", turn, applied, 30.0)
                Kind.HEAL_TRACK -> if (f.core("C") && cast.capturedAmount > 0) coreEvent(f, "C", turn, applied, 30.0)
                Kind.LIFESTEAL -> if (f.core("B")) coreEvent(f, "B", turn, applied, 30.0)
                else -> Unit
            }
        }

        private fun attack(f: Fighter, target: Fighter, cast: Casting, turn: Int): Boolean {
            val action = cast.action
            // Capture one-shot state before this attack can replace it with a new effect.
            val consumedFollowUp = f.followUpMark?.takeIf { it.expires >= turn }
            val consumedPenalty = f.nextDirectDamagePenalty?.takeIf { it.expires >= turn }
            val consumedVulnerability = target.vulnerability?.takeIf { it.expires >= turn }
            val targetHadStatus = target.harmful.any { it.expires >= turn } ||
                target.arenaHealingReduction?.expires?.let { it >= turn } == true
            val targetWasPreparingAttack = targetPreparingAttack(target)
            val counterPierceTriggered = action.effectKey == "COUNTER_PIERCE" &&
                f.lastPiercingHitTakenTurn >= turn - 2
            val c = context(f, target, action, turn, ArenaGrowthEvent.OWN_HIT, cast)
            val offensiveSupports = offensiveContributors(f, target, action, turn)
            val accuracyMods = growthModifiers(f, c, ArenaGrowthPhase.ACCURACY, cast)
            val damageMods = growthModifiers(f, c, ArenaGrowthPhase.DAMAGE, cast)
            f.growth.consume(damageMods.filter { it.expiresAtTurn != null })
            val hitChance = (accuracy(f, target, action, turn, cast.reservedTraits) +
                accuracyMods.sumOf { it.value } / 100)
                .coerceIn(0.0, 1.0)
            val damageTraits = damageTraitBonuses(f, target, action, turn, cast.reservedTraits)
            val direct = rawDamage(f, target, action, turn, cast.reservedTraits) +
                f.attackPower * action.units / target.defense * damageMods
                    .filter { it.unit == ArenaProgressionEffectUnit.DAMAGE_PERCENT }.sumOf { it.value } / 100
            val accuracyToken = cast.reservedTraits[ArenaProgressionCatalog.ROGUE_A01]?.takeIf { it.expires >= turn }
            if (accuracyToken != null) {
                val points = (hitChance - accuracy(f, target, action, turn, emptyMap())) * 100
                if (points > 0) emit(ArenaSupportEventType.TRAIT_TRIGGERED, turn, f, target, cast,
                    traitId = ArenaProgressionCatalog.ROGUE_A01, cause = accuracyToken.cause,
                    traitValue = points, reason = "accuracy_applied")
            }
            if (accuracyMods.isNotEmpty()) appliedGrowth(f, target, accuracyMods, turn, cast.startSequence, "accuracy_applied")
            for (kind in listOf(Kind.AIM, Kind.BLESS, Kind.FOCUS, Kind.PURSUIT, Kind.STABILIZE)) {
                val active = f.effects[kind]?.takeIf { it.expires >= turn } ?: continue
                val eligible = when(kind) { Kind.FOCUS, Kind.STABILIZE -> action.isSkill; Kind.PURSUIT -> action.id == BASIC; else -> true }
                val penalty = f.harmful("accuracy", turn).maxOfOrNull { it.magnitude } ?: 0.0
                if (eligible && (rules.hitChance < 1 || penalty > 0)) supportTriggered(f, target, active.support.id,
                    turn, cast.startSequence, min(active.support.magnitude, (1 - rules.hitChance + penalty / 100) * 100), "accuracy_modifier_applied")
            }
            if (!(action.isSkill && f.has(Kind.STABILIZE, turn))) f.harmful("accuracy", turn).maxByOrNull { it.magnitude }?.let {
                supportTriggered(target, f, it.supportId, turn, cast.startSequence, it.magnitude, "accuracy_penalty_applied")
            }
            if (action.isSkill && (!f.core("A") || f.heroClass != HeroClass.MAGE)) consumeEffect(f, Kind.CONDENSE, turn, cast.startSequence)
            if (action.isSkill) consumeEffect(f, Kind.FOCUS, turn, cast.startSequence)
            // Completion-consuming rights expire on misses too; successful-hit rights remain below.
            if (action.id == BASIC) {
                consumeEffect(f, Kind.OPPORTUNITY, turn, cast.startSequence)
                consumeEffect(f, Kind.PURSUIT, turn, cast.startSequence)
                consumeEffect(f, Kind.COUNTER, turn, cast.startSequence)
            }
            if (action.isSkill) {
                consumeEffect(f, Kind.RETRIBUTION, turn, cast.startSequence)
                consumeEffect(f, Kind.EXECUTE, turn, cast.startSequence)
            }
            if (random(seed, f.randomKey, turn, cast.id, 2) >= hitChance) {
                val event = emit(ArenaSupportEventType.ATTACK_MISS, turn, f, target, cast,
                    hpBefore = target.hp, hpAfter = target.hp, cause = cast.startSequence)
                f.recentMiss = Recent(turn, event)
                observe(f, c.copy(event = ArenaGrowthEvent.OWN_MISS, eventSequence = event))
                observe(target, context(target, f, action, turn, ArenaGrowthEvent.ENEMY_MISS, cause = event))
                recordWarriorAttack(f, action, false, turn, event)
                f.lastAttackFailedTurn = turn
                f.currentAttackBonusPercent = 0.0
                return false
            }
            if (target.has(Kind.EVASION, turn)) {
                val evade = target.effects.getValue(Kind.EVASION).support.magnitude / 100
                consumeEffect(target, Kind.EVASION, turn, cast.startSequence)
                if (random(seed, target.randomKey, turn, cast.id, 4) < evade) {
                    val event = emit(ArenaSupportEventType.ATTACK_EVADED, turn, target, f, cast,
                        hpBefore = target.hp, hpAfter = target.hp, cause = cast.startSequence, reason = "body_movement")
                    prepareTrait(target, ArenaProgressionCatalog.ROGUE_A01, turn, event)
                    observe(target, context(target, f, action, turn, ArenaGrowthEvent.EVADED,
                        sourceSupportId = "ARENA_SUP_ROGUE_04", cause = event))
                    recordWarriorAttack(f, action, false, turn, event)
                    f.lastAttackFailedTurn = turn
                    f.currentAttackBonusPercent = 0.0
                    return false
                }
            }
            // The ranger counter removes an illusion before the mirror roll. Its heal bonus was
            // already resolved from the pre-dispel public state in rawDamage.
            if (action.effectKey == "COUNTER_ILLUSION_HEAL") {
                dispelUpTo(
                    f,
                    target,
                    action,
                    turn,
                    cast.startSequence,
                    "illusion",
                    action.value("remove").roundToInt().coerceAtLeast(0),
                )
            }
            if (action.effectKey != "COUNTER_SUPPORT") {
                target.effects[Kind.MIRROR]?.takeIf { it.expires >= turn }?.let { firstMirror ->
                    val requestedChecks = if (action.effectKey == "MIRROR_PRESSURE")
                        action.value("mirrorChecks").roundToInt().coerceAtLeast(1) else 1
                    var everyCheckedStrikeBlocked = true
                    var checked = 0
                    val truth = f.has(Kind.TRUTH, turn)
                    while (checked < requestedChecks) {
                        val mirror = target.effects[Kind.MIRROR]?.takeIf { it.expires >= turn } ?: break
                        var chance = 1.0 / (mirror.charges + 1)
                        if (truth) chance *= .5
                        useCharge(target, Kind.MIRROR, turn, cast.startSequence)
                        val blocked = random(seed, target.randomKey, turn, cast.id, 5 + checked) < chance
                        checked++
                        if (!blocked) {
                            everyCheckedStrikeBlocked = false
                            break
                        }
                    }
                    if (truth && checked > 0) {
                        supportTriggered(f, target, "ARENA_SUP_CLERIC_07", turn, cast.startSequence,
                            50.0, "mirror_probability_weakened")
                        useCharge(f, Kind.TRUTH, turn, cast.startSequence)
                    }
                    if (checked > 0 && everyCheckedStrikeBlocked) {
                        val event = emit(ArenaSupportEventType.ATTACK_EVADED, turn, target, f, cast,
                            hpBefore = target.hp, hpAfter = target.hp, cause = cast.startSequence,
                            reason = if (action.effectKey == "MIRROR_PRESSURE") "arena_mirror_pressure" else "mirror")
                        observe(target, context(target, f, action, turn, ArenaGrowthEvent.MIRROR_BLOCKED,
                            sourceSupportId = firstMirror.support.id, cause = event))
                        recordWarriorAttack(f, action, false, turn, event)
                        f.lastAttackFailedTurn = turn
                        f.currentAttackBonusPercent = 0.0
                        return false
                    }
                }
            }
            val stealth = f.has(Kind.STEALTH, turn) && !(f.heroClass == HeroClass.ROGUE && f.core("A"))
            if (stealth) consumeEffect(f, Kind.STEALTH, turn, cast.startSequence)
            val variance = 1 + (random(seed, f.randomKey, turn, cast.id, 3) * 2 - 1) * rules.damageVariance
            val before = target.hp
            val beforeShield = target.shield
            val incoming = direct * variance
            val protection = protection(target, action, turn)
            val reductionMods = growthModifiers(target, context(target, f, action, turn, ArenaGrowthEvent.DAMAGE_REDUCED), ArenaGrowthPhase.REDUCTION)
            val piercesTotalReduction = action.effectKey == "TOTAL_MITIGATION_PIERCE"
            val arenaIgnore = when (action.effectKey) {
                "MITIGATION_PIERCE" -> action.value("ignore")
                "TOTAL_MITIGATION_PIERCE" -> action.value("ignore")
                "ULTIMATE" -> action.value("ignore")
                "COUNTER_BARRIER" -> if (target.shield <= 0.0) action.value("ignore") else 0.0
                else -> 0.0
            }
            val mitigationIgnore = (listOf(
                effectValue(f, Kind.OBSERVE, turn) + damageMods.filter { it.traitId == "AT9_RANGER_B07" }.sumOf { it.value }, effectValue(f, Kind.PIERCE, turn),
                if (action.id == BASIC) effectValue(f, Kind.BASIC_PIERCE, turn) else 0.0,
                damageMods.filter { it.unit == ArenaProgressionEffectUnit.DAMAGE_REDUCTION_IGNORE_PERCENT && it.traitId != "AT9_RANGER_B07" }.sumOf { it.value },
                arenaIgnore,
            ).max() - (target.pierceSuppression?.takeIf { it.expires >= turn }?.value ?: 0.0))
                .coerceIn(0.0, 80.0) / 100
            val growthReductionRate = reductionMods.sumOf { it.value } / 100
            val reductionWithoutPierce = (protection.second + growthReductionRate).coerceIn(0.0, .60)
            val reductionRate = if (piercesTotalReduction) {
                (protection.second + growthReductionRate) * (1 - mitigationIgnore)
            } else {
                protection.second * (1 - mitigationIgnore) + growthReductionRate
            }.coerceIn(0.0, .60)
            val reduced = incoming * (1 - reductionRate)
            val reducedEvent = if (reductionRate > 0) emit(ArenaSupportEventType.DAMAGE_REDUCED, turn, target, f, cast,
                actionId = protection.first?.support?.id ?: reductionMods.firstOrNull()?.traitId,
                hpBefore = before, hpAfter = before, amount = incoming - reduced, cause = cast.startSequence) else cast.startSequence
            if (reductionMods.isNotEmpty() && incoming > reduced) appliedGrowth(target, f, reductionMods, turn, reducedEvent, "damage_reduction_applied")
            val guardUsed = action.isSkill && target.has(Kind.SKILL_GUARD, turn)
            val guardId = target.effects[Kind.SKILL_GUARD]?.support?.id
            if (guardUsed) consumeEffect(target, Kind.SKILL_GUARD, turn, cast.startSequence)
            val bypassPercent = maxOf(
                if (f.has(Kind.PHASE, turn)) effectValue(f, Kind.PHASE, turn) else 0.0,
                if (action.effectKey == "SHIELD_BYPASS") action.value("bypass") else 0.0,
            ).coerceIn(0.0, 60.0)
            val bypass = reduced * bypassPercent / 100
            val absorbedWithoutBypass = min(target.shield, reduced)
            val absorbRequest = min(target.shield, reduced - bypass)
            target.shield = max(0.0, target.shield - absorbRequest)
            val absorbed = beforeShield - target.shield
            var absorbedEvent: Int? = null
            val shieldSourceId = target.effects.values.firstOrNull { it.support.kind in setOf(Kind.SHIELD, Kind.LOW_SHIELD, Kind.LAY_HANDS) }?.support?.id ?: "ARENA_SUP_MAGE_01"
            if (absorbed > 0) {
                val shieldSource = target.effects.values.firstOrNull { it.support.kind in setOf(Kind.SHIELD, Kind.LOW_SHIELD, Kind.LAY_HANDS) }
                absorbedEvent = emit(ArenaSupportEventType.SHIELD_ABSORBED, turn, target, f, cast,
                    actionId = shieldSource?.support?.id ?: "ARENA_SUP_MAGE_01", hpBefore = before, hpAfter = before,
                    shieldBefore = beforeShield, shieldAfter = target.shield, amount = absorbed, cause = cast.startSequence)
                val shatterKind = if (action.id == BASIC) Kind.BASIC_SHATTER else Kind.SKILL_SHATTER
                if (f.has(shatterKind, turn)) {
                    val active = f.effects.getValue(shatterKind)
                    val shatterMods = growthModifiers(f, c.copy(sourceSupportId = active.support.id,
                        consumedSupports = setOf(active.support.id)), ArenaGrowthPhase.SUPPORT_POWER)
                    val coreScale = if (shatterKind == Kind.BASIC_SHATTER && f.core("B")) f.coreValue("B", "perApplicationShatterMultiplier", .6) else 1.0
                    val extra = min(target.shield, absorbed * (active.support.magnitude / 100 * coreScale * (1 + shatterMods.sumOf { it.value } / 100)))
                    val shieldBefore = target.shield
                    target.shield -= extra
                    if (extra > 0) {
                        val e = emit(ArenaSupportEventType.SHIELD_ABSORBED, turn, target, f, cast,
                            actionId = active.support.id, shieldBefore = shieldBefore, shieldAfter = target.shield,
                            amount = extra, cause = cast.startSequence, reason = "extra_shatter_no_hp")
                        appliedGrowth(f, target, shatterMods, turn, e, "shield_damage_bonus_applied")
                    }
                    useCharge(f, shatterKind, turn, cast.startSequence)
                }
                val attackShieldMods = growthModifiers(f, c, ArenaGrowthPhase.SHIELD, cast)
                    .filter { it.unit == ArenaProgressionEffectUnit.SHIELD_DAMAGE_PERCENT }
                val extraTraitShield = min(target.shield, absorbed * attackShieldMods.sumOf { it.value } / 100)
                if (extraTraitShield > 0) {
                    val previousShield = target.shield
                    target.shield -= extraTraitShield
                    val e = emit(ArenaSupportEventType.SHIELD_ABSORBED, turn, target, f, cast,
                        actionId = action.id, shieldBefore = previousShield, shieldAfter = target.shield,
                        amount = extraTraitShield, cause = cast.startSequence, reason = "trait_extra_shatter_no_hp")
                    appliedGrowth(f, target, attackShieldMods, turn, e, "shield_damage_bonus_applied")
                }
                val arenaShatterPercent = when (action.effectKey) {
                    "SHIELD_SHATTER" -> action.value("shatter")
                    "COUNTER_BARRIER" -> action.value("shatter")
                    else -> 0.0
                }
                val arenaShatter = min(target.shield, absorbed * arenaShatterPercent / 100)
                if (arenaShatter > 0) {
                    val previousShield = target.shield
                    target.shield -= arenaShatter
                    emit(ArenaSupportEventType.SHIELD_ABSORBED, turn, target, f, cast,
                        actionId = action.id, shieldBefore = previousShield, shieldAfter = target.shield,
                        amount = arenaShatter, cause = cast.startSequence, reason = "arena_shield_shatter")
                }
                if (target.shield == 0.0) {
                    shieldSource?.let { removeEffect(target, it.support.kind, turn, absorbedEvent) }
                    observe(f, c.copy(event = ArenaGrowthEvent.SHIELD_BROKEN, absorbed = absorbed, eventSequence = absorbedEvent))
                }
            }
            var hpDamage = max(0.0, reduced - absorbed)
            action.arena?.hpDamageCapPercent?.takeIf { it > 0.0 }?.let { capPercent ->
                val capped = target.maxHp * capPercent / 100
                val saved = max(0.0, hpDamage - capped)
                if (saved > 0.0) {
                    hpDamage = capped
                    emit(ArenaSupportEventType.DAMAGE_REDUCED, turn, target, f, cast,
                        actionId = action.id, amount = saved, cause = cast.startSequence,
                        reason = "arena_hp_damage_cap")
                }
            }
            if (target.has(Kind.DAMAGE_CAP, turn)) {
                val support = target.effects.getValue(Kind.DAMAGE_CAP).support
                val saved = max(0.0, hpDamage - target.maxHp * support.magnitude / 100)
                hpDamage -= saved
                consumeEffect(target, Kind.DAMAGE_CAP, turn, cast.startSequence)
                if (saved > 0) emit(ArenaSupportEventType.DAMAGE_REDUCED, turn, target, f, cast,
                    actionId = support.id, amount = saved, cause = cast.startSequence)
            }
            var graceSaved = 0.0
            if (target.has(Kind.GRACE, turn)) {
                val support = target.effects.getValue(Kind.GRACE).support
                if (hpDamage >= target.maxHp * support.threshold / 100) {
                    val saved = min(hpDamage * support.magnitude / 100, target.maxHp * support.secondary / 100)
                    hpDamage -= saved
                    graceSaved = saved
                    consumeEffect(target, Kind.GRACE, turn, cast.startSequence)
                    emit(ArenaSupportEventType.DAMAGE_REDUCED, turn, target, f, cast,
                        actionId = support.id, amount = saved, cause = cast.startSequence)
                }
            }
            val preserveFloor = action.arena?.targetHpFloor?.takeIf { it > 0 &&
                before > target.maxHp * (action.arena.hpDamageCapPercent / 100) } ?: 0
            target.hp = max(preserveFloor.toDouble(), before - min(before, hpDamage))
            val dealt = before - target.hp
            val source = emit(ArenaSupportEventType.ATTACK_HIT, turn, f, target, cast,
                hpBefore = before, hpAfter = target.hp, amount = dealt, cause = cast.startSequence)
            // A direct hit still landed when its HP portion was fully absorbed by a shield.
            // Keep that semantic separate from effects explicitly authored around HP damage.
            val directHit = dealt + absorbed > 0
            val mitigationPierceApplied = reductionRate + 1e-12 < reductionWithoutPierce
            val shieldBypassApplied = absorbRequest + 1e-12 < absorbedWithoutBypass
            if (directHit) {
                offensiveSupports.forEach { (id, value) -> supportTriggered(f, target, id, turn, source, value, "direct_damage_modifier_applied") }
                if (f.has(Kind.PHASE, turn) && beforeShield > 0 && bypass > 0)
                    supportTriggered(f, target, "ARENA_SUP_MAGE_04", turn, source, effectValue(f, Kind.PHASE, turn), "shield_bypass_applied")
                if (mitigationIgnore > 0 && protection.second > 0) {
                    listOf(Kind.OBSERVE, Kind.PIERCE, Kind.BASIC_PIERCE).filter { f.has(it, turn) && (it != Kind.BASIC_PIERCE || action.id == BASIC) }.maxByOrNull { effectValue(f, it, turn) }?.let {
                        supportTriggered(f, target, f.effects.getValue(it).support.id, turn, source, mitigationIgnore * 100, "support_mitigation_ignored")
                    }
                }
                f.harmful("basic_weakness", turn).takeIf { action.id == BASIC }?.maxByOrNull { it.magnitude }?.let {
                    supportTriggered(target, f, it.supportId, turn, source, it.magnitude, "basic_damage_reduced")
                }
                if (target.has(Kind.TRUTH, turn) && c.selfSupports.contains("ARENA_SUP_ROGUE_01")) {
                    supportTriggered(target, f, "ARENA_SUP_CLERIC_07", turn, source, 50.0, "stealth_bonus_weakened")
                    useCharge(target, Kind.TRUTH, turn, source)
                }
                damageTraits.forEach { (id, value) -> emit(ArenaSupportEventType.TRAIT_TRIGGERED, turn, f, target, cast,
                    traitId = id, cause = cast.reservedTraits[id]?.cause ?: source, traitValue = value,
                    reason = when(id) { ArenaProgressionCatalog.ROGUE_A02 -> "stealth_damage_bonus_applied"
                        ArenaProgressionCatalog.RANGER_A02 -> "casting_damage_bonus_applied"; else -> "damage_bonus_applied" }) }
                appliedGrowth(f, target, damageMods, turn, source, "damage_bonus_applied")
                val supportPowerMods = c.selfSupports.filter { it in setOf("ARENA_SUP_RANGER_03", "ARENA_SUP_MAGE_07", "ARENA_SUP_RANGER_10") }
                    .flatMap { id -> growthModifiers(f, c.copy(sourceSupportId = id, consumedSupports = setOf(id)), ArenaGrowthPhase.SUPPORT_POWER) }
                appliedGrowth(f, target, supportPowerMods, turn, source, "support_power_bonus_applied")
                if (target.hp > 0 && (mitigationPierceApplied || shieldBypassApplied)) {
                    target.lastPiercingHitTakenTurn = turn
                }
                if (action.arena?.prepareTurns == 0) target.lastInstantHitTakenTurn = turn
                if (consumedFollowUp != null) {
                    f.followUpMark = consumedFollowUp.copy(charges = consumedFollowUp.charges - 1)
                        .takeIf { it.charges > 0 }
                }
                if (consumedPenalty != null) {
                    consumedPenalty.charges--
                    if (consumedPenalty.charges <= 0) f.nextDirectDamagePenalty = null
                }
                if (consumedVulnerability != null) target.vulnerability = null
                applyArenaHitEffects(
                    f = f,
                    target = target,
                    action = action,
                    cast = cast,
                    turn = turn,
                    dealt = dealt,
                    cause = source,
                    targetHadStatus = targetHadStatus,
                    counterPierceTriggered = counterPierceTriggered,
                    targetWasPreparingAttack = targetWasPreparingAttack,
                    shieldBroken = beforeShield > 0.0 && target.shield <= 0.0,
                )
                f.lastAttackFailedTurn = Int.MIN_VALUE
            }
            f.currentAttackBonusPercent = 0.0
            if (finishDeaths(turn, source)) return true
            if (dealt > 0) {
                target.recentDamage = Recent(turn, source, dealt)
                wake(target, f, turn, source)
            }
            val consumed = buildSet {
                if (stealth) add("ARENA_SUP_ROGUE_01")
                if (action.isSkill && ("mage_a" in action.coreIds || c.selfSupports.contains("ARENA_SUP_MAGE_07"))) add("ARENA_SUP_MAGE_07")
                if (action.isSkill && c.selfSupports.contains("ARENA_SUP_PALADIN_09")) add("ARENA_SUP_PALADIN_09")
                if (action.id == BASIC && c.selfSupports.contains("ARENA_SUP_FIGHTER_04")) add("ARENA_SUP_FIGHTER_04")
                if (action.id == BASIC && c.selfSupports.contains("ARENA_SUP_ROGUE_05")) add("ARENA_SUP_ROGUE_05")
                if (action.isSkill && c.selfSupports.contains("ARENA_SUP_PALADIN_02")) add("ARENA_SUP_PALADIN_02")
                if (c.selfSupports.contains("ARENA_SUP_RANGER_10")) add("ARENA_SUP_RANGER_10")
            }
            observe(f, c.copy(hpDamage = dealt, absorbed = absorbed, consumedSupports = consumed, eventSequence = source))
            observe(target, context(target, f, action, turn, ArenaGrowthEvent.TAKEN_HIT, cause = source)
                .copy(hpDamage = dealt, selfHpBeforeRatio = before / target.maxHp))
            val actualSavedHp = min(before, max(0.0, incoming - beforeShield)) - min(before, max(0.0, reduced - beforeShield))
            if (actualSavedHp > 0) observe(target, context(target, f, action, turn, ArenaGrowthEvent.DAMAGE_REDUCED,
                sourceSupportId = protection.first?.support?.id ?: "", cause = reducedEvent)
                .copy(reduced = actualSavedHp, consumedSupports = setOfNotNull(guardId.takeIf { guardUsed })))
            if (graceSaved > 0) observe(target, context(target, f, action, turn, ArenaGrowthEvent.DAMAGE_REDUCED,
                sourceSupportId = "ARENA_SUP_CLERIC_05", cause = source).copy(reduced = graceSaved))
            if (absorbed > 0 && absorbedEvent != null) {
                observe(target, context(target, f, action, turn, ArenaGrowthEvent.SHIELD_ABSORBED,
                    sourceSupportId = shieldSourceId, cause = absorbedEvent).copy(absorbed = absorbed))
                observe(f, c.copy(event = ArenaGrowthEvent.ENEMY_SHIELD_ABSORBED, absorbed = absorbed, eventSequence = absorbedEvent))
            }
            if (action.id == BASIC && dealt > 0) {
                prepareTrait(f, ArenaProgressionCatalog.WARRIOR_A01, turn, source)
                if (f.heroClass == HeroClass.WARRIOR && f.core("A") && f.has(Kind.SHOUT, turn)) {
                    f.shoutBasicHits++
                    if (f.shoutBasicHits >= 2 && !coreReady(f, "A", turn)) {
                        f.coreTokens["A"] = f.effects.getValue(Kind.SHOUT).expires to 0.0
                        f.shoutBasicHits = 0; coreEvent(f, "A", turn, source)
                    }
                }
            }
            recordWarriorAttack(f, action, dealt > 0, turn, source)
            if (absorbed >= 1 && absorbedEvent != null) prepareTrait(target, ArenaProgressionCatalog.MAGE_A01, turn, absorbedEvent)
            if (guardUsed && actualSavedHp > 0) {
                prepareTrait(target, ArenaProgressionCatalog.PALADIN_A01, turn, source)
                if (target.core("A")) {
                    target.coreTokens["A"] = turn + 3 to min(actualSavedHp * target.coreValue("A", "preventedDamageToExtraPercent", 50.0) / 100, target.maxHp * target.coreValue("A", "maximumExtraPercentOwnMaxHp", 4.0) / 100)
                    coreEvent(target, "A", turn, source)
                }
            }
            if (action.isSkill && f.heroClass == HeroClass.PALADIN && coreReady(f, "A", turn)) f.coreTokens.remove("A")
            if (action.id == BASIC) useCharge(f, Kind.BASIC_PIERCE, turn, source)
            if (f.has(Kind.HEAL_TRACK, turn) && dealt + absorbed > 0) consumeEffect(f, Kind.HEAL_TRACK, turn, source)
            if (f.has(Kind.MP_DRAIN, turn) && dealt + absorbed > 0) {
                val active = f.effects.getValue(Kind.MP_DRAIN)
                val loss = min(target.mpUnits, ((if (f.core("C")) f.coreValue("C", "replacementMaximumManaLoss", 30.0) else active.support.magnitude) * MP_SCALE).roundToInt())
                val beforeMp = target.mpUnits; target.mpUnits -= loss
                consumeEffect(f, Kind.MP_DRAIN, turn, source)
                if (loss > 0) {
                    val event = emit(ArenaSupportEventType.MP_DRAINED, turn, target, f, actionId = active.support.id,
                        mpBefore = beforeMp, mpAfter = target.mpUnits, amount = loss / 1000.0, cause = source)
                    observe(f, c.copy(event = ArenaGrowthEvent.MANA_DRAINED, sourceSupportId = active.support.id,
                        manaLost = loss / 1000.0, eventSequence = event))
                }
            }
            if (f.has(Kind.HEAL_BLOCK_PREP, turn) && dealt + absorbed > 0) {
                val support = f.effects.getValue(Kind.HEAL_BLOCK_PREP).support
                addStatus(f, target, "heal_reduction", support, turn, support.secondary.toInt(), support.magnitude, source)
                consumeEffect(f, Kind.HEAL_BLOCK_PREP, turn, source)
            }
            if (f.has(Kind.JUDGMENT, turn) && dealt + absorbed > 0) {
                val support = f.effects.getValue(Kind.JUDGMENT).support
                dispelUpTo(
                    f,
                    target,
                    Action(support.id, 1, 0, support = support),
                    turn,
                    source,
                    "all",
                    support.magnitude.roundToInt(),
                )
                // Judgment is one trigger whose payload grows at the milestone; it is not
                // two independent triggering hits when the resolved definition has charges=2.
                removeEffect(f, Kind.JUDGMENT, turn, source)
            }
            if (dealt > 0) {
                applyAttackStatuses(f, target, action, cast, turn, dealt, source)
                if (target.has(Kind.EMBER, turn)) {
                    val support = target.effects.getValue(Kind.EMBER).support
                    addStatus(target, f, "burn", support, turn, support.secondary.toInt(), target.attackPower * support.magnitude / 100, source)
                    consumeEffect(target, Kind.EMBER, turn, source)
                }
            }
            return false
        }

        private fun applyArenaHitEffects(
            f: Fighter,
            target: Fighter,
            action: Action,
            cast: Casting,
            turn: Int,
            dealt: Double,
            cause: Int,
            targetHadStatus: Boolean,
            counterPierceTriggered: Boolean,
            targetWasPreparingAttack: Boolean,
            shieldBroken: Boolean,
        ) {
            fun applied(reason: String, amount: Double, expires: Int? = null, receiver: Fighter = target): Int {
                return emit(ArenaSupportEventType.STATUS_APPLIED, turn, f, receiver, cast,
                    actionId = action.id, amount = amount, expires = expires, cause = cause, reason = reason)
            }
            fun blocked(kind: String): Boolean = statusBlocked(target, f, kind, action.id, turn, cause)
            fun applyBleed(eligible: Boolean) {
                val tickPercent = action.value("masterBleedTick")
                val turns = action.value("masterBleedTurns").roundToInt()
                if (!eligible || dealt <= 0.0 || tickPercent <= 0.0 || turns <= 0) return
                val tick = f.attackPower * tickPercent / 100
                val shorten = target.effects[Kind.SANCTUARY]
                    ?.takeIf { it.expires >= turn }
                    ?.support?.magnitude?.roundToInt()
                    ?.coerceAtLeast(0)
                    ?: 0
                val effectiveTurns = max(1, turns - shorten)
                val expires = turn + effectiveTurns
                val current = target.harmful.filter { it.kind == "bleed" && it.expires >= turn }
                    .maxOfOrNull { it.magnitude * (it.expires - turn) } ?: 0.0
                if (current > tick * effectiveTurns) return
                if (blocked("bleed")) return
                target.harmful.removeAll { it.kind == "bleed" }
                target.harmful += Harmful("bleed", f.id, action.id, turn, expires, tick, cause)
                val event = applied("arena_mastery_bleed", tick, expires)
                if (shorten > 0 && turns > 1) {
                    supportTriggered(
                        target,
                        f,
                        "ARENA_SUP_CLERIC_08",
                        turn,
                        event,
                        shorten.toDouble(),
                        "harmful_duration_shortened",
                    )
                    observe(
                        target,
                        context(
                            target,
                            f,
                            Action(action.id, 1, 0),
                            turn,
                            ArenaGrowthEvent.STATUS_SHORTENED,
                            sourceSupportId = "ARENA_SUP_CLERIC_08",
                            cause = event,
                        ).copy(appliedStatusKind = "bleed"),
                    )
                }
            }
            fun applyVulnerability(valueKey: String, turnsKey: String, eligible: Boolean) {
                val value = action.value(valueKey)
                val turns = action.value(turnsKey).roundToInt()
                if (!eligible || value <= 0.0 || turns <= 0) return
                val expires = turn + turns
                val current = target.vulnerability?.takeIf { it.expires >= turn }
                if (current == null || value >= current.value) {
                    if (blocked("arena_vulnerability")) return
                    target.vulnerability = TimedValue(value, expires)
                    applied("arena_mastery_vulnerability", value, expires)
                }
            }

            when (action.effectKey) {
                "PREPARATION_PUNISH" -> applyBleed(targetWasPreparingAttack)
                "SHIELD_SHATTER" -> applyVulnerability(
                    "masterRupture",
                    "masterRuptureTurns",
                    shieldBroken,
                )
                "ATTACK_SUPPRESSION" -> if (dealt > 0 && !blocked("arena_attack_suppression")) {
                    val reduction = action.value("reduction")
                    val charges = action.value("masterSuppressionCharges").roundToInt().coerceAtLeast(1)
                    target.nextDirectDamagePenalty = TimedValue(reduction, turn + 4, charges)
                    applied("arena_attack_suppression", reduction, turn + 4)
                }
                "MANA_PRESSURE" -> {
                    val loss = min(target.mpUnits, (action.value("drain") * MP_SCALE).roundToInt())
                    if (loss > 0) {
                        val beforeTarget = target.mpUnits
                        target.mpUnits -= loss
                        emit(
                            ArenaSupportEventType.MP_DRAINED,
                            turn,
                            actor = target,
                            target = f,
                            actionId = action.id,
                            mpBefore = beforeTarget,
                            mpAfter = target.mpUnits,
                            amount = loss / MP_SCALE.toDouble(),
                            cause = cause,
                            reason = "arena_mana_pressure",
                        )
                    }
                }
                "FOLLOW_UP_MARK" -> {
                    f.followUpMark = FollowUpMark(
                        accuracy = action.value("accuracy"),
                        damage = action.value("bonus"),
                        expires = turn + 4,
                        charges = action.value("masterFollowUpCharges").roundToInt().coerceAtLeast(1),
                    )
                    applied("arena_follow_up_mark", action.value("bonus"), turn + 4, f)
                }
                "HEALING_REDUCTION" -> {
                    if (dealt > 0 && !blocked("arena_healing_reduction")) {
                        val reduction = action.value("reduction")
                        target.arenaHealingReduction = TimedValue(reduction, turn + max(1, action.arena?.durationTurns ?: 2))
                        applied("arena_healing_reduction", reduction, target.arenaHealingReduction?.expires)
                    }
                    applyBleed(true)
                }
                "STATUS_EXPLOIT" -> if (!targetHadStatus && !blocked("arena_vulnerability")) {
                    val vulnerability = action.value("vulnerability")
                    target.vulnerability = TimedValue(vulnerability, turn + 4)
                    applied("arena_vulnerability", vulnerability, turn + 4)
                }
                "CAST_DELAY" -> {
                    val pending = target.casting
                    if (pending != null) {
                        val chance = (.55 + action.value("controlAccuracy") / 100).coerceIn(.35, .95)
                        if (!target.immune(turn) && !statusBlocked(target, f, "arena_cast_delay", action.id, turn, cause) &&
                            random(seed, f.randomKey, turn, cast.id, 21) < chance) {
                            pending.remaining += action.value("delay").roundToInt().coerceAtLeast(1)
                            target.immuneUntil = turn + 2
                            target.needsNormalCompletion = true
                            emit(ArenaSupportEventType.CAST_DELAYED, turn, target, f, pending,
                                actionId = action.id, amount = action.value("delay"), cause = cause,
                                reason = "arena_cast_delay")
                        } else {
                            emit(ArenaSupportEventType.CONTROL_RESISTED, turn, target, f,
                                actionId = action.id, cause = cause, reason = "arena_cast_delay")
                        }
                    }
                }
                "LOW_HP_DRAIN" -> if (dealt > 0 &&
                    f.hp <= f.maxHp * action.value("threshold") / 100) {
                    val requested = min(dealt * action.value("drain") / 100,
                        f.maxHp * action.value("cap") / 100)
                    val restored = heal(f, target, action, turn, requested, cause, cast)
                    if (restored > 0) applied("arena_low_hp_drain", restored, receiver = f)
                }
                "HEAVY_CHANNEL" -> applyBleed(true)
                "DISPEL_OR_DRAIN" -> if (dealt > 0) {
                    val maximum = action.value("dispel").roundToInt().coerceAtLeast(1)
                    if (dispelUpTo(f, target, action, turn, cause, "all", maximum) == 0) {
                        val loss = min(target.mpUnits, (action.value("drain") * MP_SCALE).roundToInt())
                        if (loss > 0) {
                            val beforeTarget = target.mpUnits
                            target.mpUnits -= loss
                            f.mpUnits = min(f.maxMpUnits, f.mpUnits + loss)
                            emit(ArenaSupportEventType.MP_DRAINED, turn, target, f, actionId = action.id,
                                mpBefore = beforeTarget, mpAfter = target.mpUnits, amount = loss / 1000.0,
                                cause = cause, reason = "arena_dispel_or_drain")
                        }
                    }
                }
                "COUNTER_RUSH" -> if (action.value("burn") > 0.0 &&
                    !statusBlocked(target, f, "burn", action.id, turn, cause)) {
                    val duration = max(1, action.arena?.durationTurns ?: 2)
                    val expires = turn + duration
                    val tick = f.attackPower * action.value("burn") / 100
                    target.harmful.removeAll { it.kind == "burn" }
                    target.harmful += Harmful("burn", f.id, action.id, turn, expires, tick, cause)
                    applied("arena_counter_rush_burn", tick, expires)
                }
                "COUNTER_PIERCE" -> if (counterPierceTriggered &&
                    !blocked("arena_pierce_suppression")) {
                    val reduction = action.value("pierceReduction")
                    target.pierceSuppression = TimedValue(reduction, turn + 2)
                    applied("arena_counter_pierce", reduction, turn + 2)
                }
            }
        }

        private fun applyAttackStatuses(f: Fighter, target: Fighter, action: Action, cast: Casting, turn: Int, dealt: Double, cause: Int) {
            if (f.has(Kind.POISON_COAT, turn)) {
                val support = f.effects.getValue(Kind.POISON_COAT).support
                val c = context(f, target, action, turn, cast = cast, sourceSupportId = support.id, cause = cause)
                val mods = growthModifiers(f, c, ArenaGrowthPhase.DOT)
                val scale = if (f.core("B")) f.coreValue("B", "tickDamageMultiplier", .8) else 1.0
                val duration = if (f.core("B")) f.coreValue("B", "replacementLayerDurationTurns", 6.0).toInt() else support.secondary.toInt()
                val tick = f.attackPower * support.magnitude / 100 * scale * (1 + mods.sumOf { it.value } / 100)
                if (addStatus(f, target, "poison", support, turn, duration, tick, cause)) {
                    appliedGrowth(f, target, mods, turn, cause, "dot_bonus_applied")
                    if (f.core("B")) coreEvent(f, "B", turn, cause)
                }
                useCharge(f, Kind.POISON_COAT, turn, cause)
            }
            if (action.isSkill && f.has(Kind.BURN_PREP, turn)) {
                val support = f.effects.getValue(Kind.BURN_PREP).support
                val mods = growthModifiers(f, context(f, target, action, turn, cast = cast, sourceSupportId = support.id), ArenaGrowthPhase.DOT)
                    .filter { it.traitId != "AT9_MAGE_B06" }
                val scale = if (f.core("B")) f.coreValue("B", "burnTotalMultiplier", 1.4) else 1.0
                val tick = f.attackPower * support.magnitude / 100 * scale * (1 + mods.sumOf { it.value } / 100)
                if (addStatus(f, target, "burn", support, turn, support.secondary.toInt(), tick, cause)) {
                    appliedGrowth(f, target, mods, turn, cause, "dot_bonus_applied")
                    if (f.core("B")) coreEvent(f, "B", turn, cause)
                }
                consumeEffect(f, Kind.BURN_PREP, turn, cause)
            }
            if (f.has(Kind.LIFESTEAL, turn) && turn >= f.healReadyAt && (!f.core("B") || action.isSkill)) {
                val support = f.effects.getValue(Kind.LIFESTEAL).support
                val percent = if (f.core("B")) f.coreValue("B", "actualHpDamageHealPercent", 30.0) else support.magnitude
                val cap = if (f.core("B")) f.coreValue("B", "ownMaxHpHealCapPercent", 8.0) else support.secondary
                val linkContext = context(f, target, action, turn, sourceSupportId = support.id).copy(consumedSupports = setOf(support.id))
                val linkMods = growthModifiers(f, linkContext, ArenaGrowthPhase.SUPPORT_POWER)
                val linkHeal = heal(f, target, Action(support.id, 1, 0, support = support), turn,
                    min(dealt * percent / 100 * (1 + linkMods.sumOf { it.value } / 100), f.maxHp * cap / 100), cause)
                if (linkHeal > 0) appliedGrowth(f, target, linkMods, turn, cause, "lifesteal_bonus_applied")
                f.healReadyAt = turn + 3
                if (f.core("B")) consumeEffect(f, Kind.LIFESTEAL, turn, cause)
            }
            if (f.has(Kind.SEAL, turn)) {
                val support = f.effects.getValue(Kind.SEAL).support
                dispelUpTo(
                    f,
                    target,
                    Action(support.id, 1, 0, support = support),
                    turn,
                    cause,
                    "restricted",
                    support.magnitude.roundToInt(),
                )
                // Seal follows the same single-hit count contract as Judgment.
                removeEffect(f, Kind.SEAL, turn, cause)
            }
        }

        private fun useCharge(f: Fighter, kind: Kind, turn: Int, cause: Int) =
            consumeEffect(f, kind, turn, cause)

        private fun recordWarriorAttack(
            f: Fighter,
            action: Action,
            damagedHp: Boolean,
            turn: Int,
            cause: Int,
        ) {
            if (!f.hasTrait(ArenaProgressionCatalog.WARRIOR_A02)) return
            val previous = f.lastDirectAttack
            val triggered = damagedHp && previous?.damagedHp == true && previous.actionId != action.id &&
                prepareTrait(f, ArenaProgressionCatalog.WARRIOR_A02, turn, cause)
            // A successfully used trigger pair cannot be reused as either half of another pair.
            f.lastDirectAttack = if (triggered) null else DirectAttackCompletion(action.id, damagedHp)
        }

        private fun prepareTrait(f: Fighter, traitId: String, turn: Int, cause: Int): Boolean {
            val definition = ArenaProgressionCatalog.find(traitId) ?: return false
            if (!f.hasTrait(traitId) || definition.windowTurns <= 0 || definition.charges != 1 ||
                traitId in f.traitTokens || turn < (f.traitReadyAt[traitId] ?: 1) || f.hp <= 0) return false
            val expires = turn + definition.windowTurns
            f.traitTokens[traitId] = TraitToken(traitId, expires, turn + 1, cause)
            f.traitReadyAt[traitId] = turn + definition.cooldownTurns + 1
            emit(ArenaSupportEventType.TRAIT_TRIGGERED, turn, f,
                traitId = traitId, cause = cause, expires = expires,
                traitValue = f.traitValue(traitId), reason = "prepared")
            return true
        }

        private fun consumeEffect(f: Fighter, kind: Kind, turn: Int, cause: Int?) {
            val active = f.effects[kind] ?: return
            if (active.charges > 1) {
                active.charges--
                return
            }
            removeEffect(f, kind, turn, cause)
        }

        private fun removeEffect(f: Fighter, kind: Kind, turn: Int, cause: Int?) {
            val active = f.effects.remove(kind) ?: return
            emit(ArenaSupportEventType.EFFECT_EXPIRED, turn, f, actionId = active.support.id,
                cause = cause?.takeIf { it < sequence } ?: active.causeSequence.takeIf { it < sequence }, reason = "consumed")
        }

        private fun expire(f: Fighter, turn: Int) {
            for (kind in f.effects.keys.sortedBy { it.ordinal }.toList()) {
                val active = f.effects.getValue(kind)
                if (active.expires > turn) continue
                f.effects.remove(kind)
                val beforeShield = f.shield
                val shieldKind = kind in setOf(Kind.SHIELD, Kind.LOW_SHIELD, Kind.LAY_HANDS)
                if (shieldKind) f.shield = 0.0
                val expired = emit(ArenaSupportEventType.EFFECT_EXPIRED, turn, f, actionId = active.support.id,
                    shieldBefore = if (shieldKind) beforeShield else null,
                    shieldAfter = if (shieldKind) 0.0 else null, reason = "elapsed")
                observe(f, context(f, opponent(f), Action(active.support.id, active.support.cast, 0, support = active.support), turn, ArenaGrowthEvent.SUPPORT_EXPIRED, sourceSupportId = active.support.id, cause = expired).copy(supportHadUnusedCharges = active.charges > 0))
                observe(opponent(f), context(opponent(f), f, Action(active.support.id, active.support.cast, 0, support = active.support), turn, ArenaGrowthEvent.ENEMY_SUPPORT_EXPIRED, sourceSupportId = active.support.id, cause = expired).copy(supportWasAttackBuff = attackBuff(active.support.kind)))
            }
            f.harmful.filter { it.expires <= turn }.forEach {
                emit(ArenaSupportEventType.STATUS_REMOVED, turn, f, actionId = it.supportId, reason = it.kind, cause = it.cause)
            }
            f.harmful.removeAll { it.expires <= turn }
            if (f.nextDirectDamagePenalty?.expires?.let { it <= turn } == true) f.nextDirectDamagePenalty = null
            if (f.followUpMark?.expires?.let { it <= turn } == true) f.followUpMark = null
            if (f.vulnerability?.expires?.let { it <= turn } == true) f.vulnerability = null
            if (f.arenaHealingReduction?.expires?.let { it <= turn } == true) f.arenaHealingReduction = null
            if (f.pierceSuppression?.expires?.let { it <= turn } == true) f.pierceSuppression = null
            f.growth.expire(turn + 1).forEach { emit(ArenaSupportEventType.EFFECT_EXPIRED, turn, f, traitId = it, reason = "trait_elapsed") }
            observe(f, context(f, opponent(f), Action(BASIC, 1, 0), turn, ArenaGrowthEvent.TURN_END, cause = sequence - 1))
            for (traitId in f.traitTokens.keys.sorted().toList()) {
                val token = f.traitTokens.getValue(traitId)
                if (token.expires > turn) continue
                f.traitTokens.remove(traitId)
                emit(ArenaSupportEventType.EFFECT_EXPIRED, turn, f, traitId = traitId,
                    cause = token.cause, reason = "trait_elapsed")
            }
        }

        // Pure current-state evaluation; the helper never pays, consumes rights, or samples RNG.
        private fun transform(f: Fighter, target: Fighter, original: Action, turn: Int): Action {
            var action = original
            val cores = mutableSetOf<String>()
            if (original.support != null) {
                val effective = if (original.id in f.input.supportRanks ||
                    original.id in f.input.resolvedSupports) {
                    original.support
                } else {
                    ArenaSupportCatalog.effectiveDefinition(original.id, f.input.traits)
                        ?.resolved() ?: original.support
                }
                action = original.copy(cast = effective.castTurns, mp = effective.mp)
                if (original.id == "ARENA_SUP_PALADIN_10" && f.core("B") && coreReady(f, "B", turn))
                    action = action.copy(mp = f.coreValue("B", "replacementExecutionMpCost", 15.0).toInt())
                return action
            }
            if (!action.isSkill) return action
            var extraMp = 0
            val arena = action.arena
            val counterRushUses = f.arenaEffectUseCounts[action.id] ?: 0
            val counterRushReduction = action.value("prepareReduction").roundToInt().coerceAtLeast(0)
            val counterRushCast = max(1, action.cast - counterRushReduction)
            if (action.effectKey == "COUNTER_RUSH" && counterRushReduction > 0 &&
                counterRushCast < action.cast &&
                f.lastInstantHitTakenTurn >= turn - 1 &&
                counterRushUses < (arena?.maxApplications ?: 0)) {
                action = action.copy(cast = counterRushCast)
                cores += "arena_counter_rush"
            }
            if (f.heroClass == HeroClass.WARRIOR && f.core("A") && coreReady(f, "A", turn) && original.cast == 2) {
                action = action.copy(cast = 1, units = action.units * f.coreValue("A", "attackPowerMultiplier", .8)); cores += "warrior_a"
            }
            if (f.heroClass == HeroClass.ROGUE && f.core("A") && f.has(Kind.STEALTH, turn) && original.cast in 2..3) {
                action = action.copy(cast = max(1, action.cast - 1)); cores += "rogue_a"
            }
            if (f.heroClass == HeroClass.RANGER && f.core("B") && coreReady(f, "B", turn) && original.cast in 2..3) {
                action = action.copy(cast = max(1, action.cast - 1)); cores += "ranger_b"
            }
            if (f.heroClass == HeroClass.MAGE && f.core("A") && f.has(Kind.CONDENSE, turn) && original.cast == 3) {
                extraMp += f.coreValue("A", "additionalAttackMp", 12.0).toInt(); cores += "mage_a"
            }
            if (f.heroClass == HeroClass.RANGER && f.core("A") && f.has(Kind.AIM, turn) && original.cast == 1 &&
                target.casting?.action?.let { it.isSkill && it.originalCast in 2..3 } == true && target.casting?.remaining == 1) {
                extraMp += f.coreValue("A", "additionalAttackMp", 4.0).toInt(); cores += "ranger_a"
            }
            return action.copy(coreIds = action.coreIds + cores, extraMp = extraMp)
        }

        private fun price(f: Fighter, target: Fighter, original: Action, turn: Int): Choice {
            val action = transform(f, target, original, turn)
            val discounts = linkedMapOf<String, Double>()
            fun discount(id: String, eligible: Boolean) { if (eligible && f.hasTrait(id)) discounts[id] = f.traitValue(id) }
            discount(ArenaProgressionCatalog.WARRIOR_A01, action.isSkill && f.tokenAvailable(ArenaProgressionCatalog.WARRIOR_A01, turn) != null)
            discount(ArenaProgressionCatalog.MAGE_A01, action.isSkill && f.tokenAvailable(ArenaProgressionCatalog.MAGE_A01, turn) != null)
            discount(ArenaProgressionCatalog.CLERIC_A02, action.isSkill && f.tokenAvailable(ArenaProgressionCatalog.CLERIC_A02, turn) != null)
            val incoming = target.casting
            discount(ArenaProgressionCatalog.RANGER_A01, action.support?.kind == Kind.AIM && incoming != null &&
                incoming.action.isSkill && incoming.action.originalCast in 2..3 && incoming.remaining >= 2)
            discount(ArenaProgressionCatalog.MAGE_A02, action.support?.kind == Kind.CONDENSE && f.mpUnits * 2 >= f.maxMpUnits)
            discount(ArenaProgressionCatalog.PALADIN_A02, action.isSkill && action.originalCast in 2..3 && f.has(Kind.FOCUS, turn))
            val observed = context(f, target, action, turn)
            val mods = f.growth.modifiers(observed, ArenaGrowthPhase.COST)
            mods.forEach { discounts[it.traitId] = it.value }
            val percent = discounts.values.sum().coerceAtMost(30.0)
            val cost = (if (action.mp == 0) 0 else max(MP_SCALE,
                ceil(action.mp * MP_SCALE * (1 - percent / 100) - 1e-9).toInt())) + action.extraMp * MP_SCALE
            return Choice(action, cost, discounts, mods, observed)
        }

        private fun attackTokens(f: Fighter, action: Action, turn: Int): Map<String, TraitToken> {
            val tokens = linkedMapOf<String, TraitToken>()
            fun include(traitId: String, eligible: Boolean) {
                if (eligible) f.tokenAvailable(traitId, turn)?.let { tokens[traitId] = it }
            }
            include(ArenaProgressionCatalog.ROGUE_A01, action.isAttack)
            include(ArenaProgressionCatalog.PALADIN_A01, action.isAttack)
            include(ArenaProgressionCatalog.WARRIOR_A02, action.id == BASIC)
            return tokens
        }

        private fun accuracy(
            f: Fighter,
            target: Fighter,
            action: Action,
            turn: Int,
            reservedTraits: Map<String, TraitToken>,
        ): Double {
            var chance = rules.hitChance
            chance -= (f.harmful("accuracy", turn).maxOfOrNull { it.magnitude } ?: 0.0) / 100
            if (f.has(Kind.AIM, turn)) chance += f.effects.getValue(Kind.AIM).support.secondary / 100
            if (f.has(Kind.BLESS, turn)) chance += f.effects.getValue(Kind.BLESS).support.secondary / 100
            if (action.id == BASIC && f.has(Kind.PURSUIT, turn)) chance += f.effects.getValue(Kind.PURSUIT).support.secondary / 100
            if (action.isSkill && f.has(Kind.FOCUS, turn)) chance += effectValue(f, Kind.FOCUS, turn) / 100
            if (action.effectKey in setOf("ACCURATE", "SPELL_STABILITY")) {
                chance += action.value("accuracy") / 100
                action.value("masterAccuracyFloor").takeIf { it > 0.0 }?.let { floor ->
                    chance = max(floor / 100, chance)
                }
            }
            if (action.effectKey == "CAST_INTERCEPT" && targetPreparingAttack(target)) {
                chance += action.value("accuracy") / 100
            }
            if (action.isSkill && f.has(Kind.STABILIZE, turn)) {
                chance = max(chance, effectValue(f, Kind.STABILIZE, turn) / 100)
            }
            f.followUpMark?.takeIf { it.expires >= turn }?.let { chance += it.accuracy / 100 }
            val rogueA01 = reservedTraits[ArenaProgressionCatalog.ROGUE_A01]
            if (rogueA01 != null && rogueA01.expires >= turn) {
                chance += f.traitValue(ArenaProgressionCatalog.ROGUE_A01) / 100.0
            }
            return chance.coerceIn(0.0, 1.0)
        }

        private fun targetPreparingAttack(target: Fighter): Boolean =
            target.casting?.action?.isAttack == true

        private fun damageTraitBonuses(
            f: Fighter,
            target: Fighter,
            action: Action,
            turn: Int,
            reservedTraits: Map<String, TraitToken>,
        ): Map<String, Double> {
            val bonuses = linkedMapOf<String, Double>()
            fun add(traitId: String, eligible: Boolean) {
                if (eligible && f.hasTrait(traitId)) bonuses[traitId] = f.traitValue(traitId)
            }
            add(ArenaProgressionCatalog.WARRIOR_A02,
                action.id == BASIC && reservedTraits[ArenaProgressionCatalog.WARRIOR_A02]
                    ?.let { it.expires >= turn } == true)
            add(ArenaProgressionCatalog.ROGUE_A02,
                action.isSkill && action.originalCast == 1 && f.has(Kind.STEALTH, turn))
            add(ArenaProgressionCatalog.RANGER_A02,
                action.id == BASIC && target.casting?.action?.let {
                    it.isSkill && it.originalCast in 2..3
                } == true)
            add(ArenaProgressionCatalog.PALADIN_A01,
                reservedTraits[ArenaProgressionCatalog.PALADIN_A01]
                    ?.let { it.expires >= turn } == true)
            return bonuses
        }

        private fun rawDamage(f: Fighter, target: Fighter, action: Action, turn: Int,
            reservedTraits: Map<String, TraitToken>): Double {
            val boosts = mutableListOf<Double>()
            fun add(kind: Kind, eligible: Boolean = true) { if (eligible && f.has(kind, turn)) boosts += effectValue(f, kind, turn) / 100 }
            if (f.has(Kind.SHOUT, turn)) boosts += (if (f.core("A")) f.coreValue("A", "replacementShoutDamageBonusPercent", 10.0) else effectValue(f, Kind.SHOUT, turn)) / 100
            if (f.has(Kind.AIM, turn) && !(f.heroClass == HeroClass.RANGER && f.core("A"))) boosts += effectValue(f, Kind.AIM, turn) / 100
            add(Kind.BLESS)
            if (f.has(Kind.STEALTH, turn) && !(f.heroClass == HeroClass.ROGUE && f.core("A")))
                boosts += effectValue(f, Kind.STEALTH, turn) / 100 * if (target.has(Kind.TRUTH, turn)) .5 else 1.0
            if ("rogue_a" in action.coreIds) boosts += f.coreValue("A", "replacementDamageMultiplier", 1.5) - 1
            if ("mage_a" in action.coreIds) boosts += f.coreValue("A", "damageBonusPercent", 60.0) / 100
            else add(Kind.CONDENSE, action.isSkill && !(f.heroClass == HeroClass.MAGE && f.core("A")))
            if ("ranger_a" in action.coreIds) boosts += f.coreValue("A", "conditionalDamageBonusPercent", 35.0) / 100
            add(Kind.RAPID, action.id == BASIC)
            add(Kind.COUNTER, action.id == BASIC && !f.core("C"))
            add(Kind.PURSUIT, action.id == BASIC)
            add(Kind.OPPORTUNITY, action.id == BASIC)
            add(Kind.RETRIBUTION, action.isSkill)
            add(Kind.EXECUTE, action.isSkill)
            add(Kind.RESOLVE, f.hp <= f.maxHp * .35)
            var bonus = boosts.maxOrNull() ?: 0.0
            if (f.has(Kind.RESTRAINT, turn)) bonus -= f.effects.getValue(Kind.RESTRAINT).support.secondary / 100
            if (action.id == BASIC) bonus -= (f.harmful("basic_weakness", turn).maxOfOrNull { it.magnitude } ?: 0.0) / 100
            bonus += damageTraitBonuses(f, target, action, turn, reservedTraits).values.sum() / 100
            val supportPower = f.effects.values.filter { it.expires >= turn && it.support.kind in setOf(Kind.RAPID, Kind.CONDENSE) }
                .flatMap { active -> growthModifiers(f, context(f, target, action, turn, sourceSupportId = active.support.id)
                    .copy(consumedSupports = setOf(active.support.id)), ArenaGrowthPhase.SUPPORT_POWER) }
            bonus += supportPower.sumOf { m -> if (m.unit == ArenaProgressionEffectUnit.SUPPORT_POWER_PERCENTAGE_POINTS) m.value / 100
                else (boosts.maxOrNull() ?: 0.0) * m.value / 100 }
            var damage = f.attackPower * action.units * max(.1, 1 + bonus)
            if (action.id == BASIC && f.has(Kind.COUNTER, turn) && f.core("C")) {
                damage += min(f.effects.getValue(Kind.COUNTER).capturedAmount * f.coreValue("C", "replacementRecentHpDamagePercent", 50.0) / 100, f.maxHp * f.coreValue("C", "maximumExtraPercentOwnMaxHp", 8.0) / 100)
            }
            if (f.has(Kind.HEAL_TRACK, turn)) {
                val active = f.effects.getValue(Kind.HEAL_TRACK)
                val trackMods = growthModifiers(f, context(f, target, action, turn, sourceSupportId = active.support.id)
                    .copy(consumedSupports = setOf(active.support.id)), ArenaGrowthPhase.SUPPORT_POWER)
                val extra = trackMods.sumOf { it.value } / 100
                damage += if (f.core("C")) min(active.capturedAmount * f.coreValue("C", "actualHealingConversionPercent", 30.0) / 100 * (1 + extra), target.maxHp * f.coreValue("C", "targetMaxHpCapPercent", 8.0) / 100)
                    else min(active.capturedAmount * active.support.magnitude / 100 * (1 + extra), f.attackPower * action.units * active.support.secondary / 100)
            }
            if (action.isSkill && f.heroClass == HeroClass.PALADIN && coreReady(f, "A", turn)) damage += f.coreTokens.getValue("A").second
            if (f.has(Kind.MP_DRAIN, turn) && f.core("C")) damage *= f.coreValue("C", "deliveryDirectDamageMultiplier", .75)
            if (action.isSkill && f.has(Kind.BURN_PREP, turn) && f.core("B")) damage *= f.coreValue("B", "directDamageMultiplier", .8)
            var arenaBonus = f.currentAttackBonusPercent
            when (action.effectKey) {
                "PREPARATION_PUNISH", "CAST_INTERCEPT" -> if (targetPreparingAttack(target)) {
                    arenaBonus += action.value("bonus")
                }
                "MISS_RECOVERY" -> if (f.lastAttackFailedTurn >= turn - 2) arenaBonus += action.value("bonus")
                "STATUS_EXPLOIT" -> if (target.harmful.any { it.expires >= turn } ||
                    target.arenaHealingReduction?.expires?.let { it >= turn } == true) {
                    arenaBonus += action.value("bonus")
                }
                "EXECUTE" -> if (target.hp <= target.maxHp * action.value("threshold") / 100) {
                    arenaBonus += action.value("bonus")
                }
                "COUNTER_SUPPORT" -> if (target.lastSupportCompletedTurn >= turn - action.value("window").toInt()) {
                    arenaBonus += action.value("bonus")
                }
                "COUNTER_ILLUSION_HEAL" -> if (!target.has(Kind.STEALTH, turn) && !target.has(Kind.MIRROR, turn) &&
                    target.recentHealing.any { it.turn >= turn - action.value("window").toInt() }) {
                    arenaBonus += action.value("bonus")
                }
                "COUNTER_PIERCE" -> if (f.lastPiercingHitTakenTurn >= turn - 2) {
                    arenaBonus += action.value("bonus")
                }
            }
            f.followUpMark?.takeIf { it.expires >= turn }?.let { arenaBonus += it.damage }
            target.vulnerability?.takeIf { it.expires >= turn }?.let { arenaBonus += it.value }
            f.nextDirectDamagePenalty?.takeIf { it.expires >= turn }?.let { arenaBonus -= it.value }
            damage *= max(0.1, 1 + arenaBonus / 100)
            return damage / target.defense
        }

        private fun protection(f: Fighter, action: Action, turn: Int): Pair<Active?, Double> {
            val options = listOfNotNull(
                f.effects[Kind.IRON]?.takeIf { it.expires >= turn }?.let { it to it.support.magnitude / 100 },
                f.effects[Kind.RESTRAINT]?.takeIf { it.expires >= turn }?.let { it to it.support.magnitude / 100 + it.reductionBonus },
                if (action.id == BASIC) f.effects[Kind.BASIC_GUARD]?.takeIf { it.expires >= turn }?.let { it to it.support.magnitude / 100 } else null,
                if (action.isSkill) f.effects[Kind.SKILL_GUARD]?.takeIf { it.expires >= turn }?.let { it to (if (f.core("A")) f.coreValue("A", "replacementSkillDamageReductionPercent", 25.0) else it.support.magnitude) / 100 } else null,
            )
            return options.maxByOrNull { it.second } ?: (null to 0.0)
        }

        private fun expected(
            f: Fighter, target: Fighter, action: Action, completion: Int,
            reservedTraits: Map<String, TraitToken>, paidMpUnits: Int = action.mp * MP_SCALE,
            startTurn: Int = completion - action.cast + 1,
        ): Double {
            // Public-state projection only: account for known expiry and the quoted payment,
            // while HP, opponent decisions and RNG remain unpredicted. Peeking never consumes.
            val start = context(f, target, action, startTurn)
            val c = context(f, target, action, completion).copy(
                selfMpRatio = max(0, f.mpUnits - paidMpUnits).toDouble() / f.maxMpUnits,
                startSelfMpRatio = start.selfMpRatio, castStartedTurn = startTurn,
                startTargetCastOriginalTurns = start.targetCastOriginalTurns,
                startTargetCastRemaining = start.targetCastRemaining,
                startTargetCastingSupport = start.targetCastingSupport,
                startTargetCastingRecoveryOrProtection = start.targetCastingRecoveryOrProtection,
            )
            fun mods(phase: ArenaGrowthPhase): List<ArenaGrowthModifier> =
                (f.growth.modifiers(start, phase).filter { it.expiresAtTurn != null && it.expiresAtTurn >= completion } +
                    f.growth.modifiers(c, phase).filter { it.expiresAtTurn == null }).distinctBy { it.traitId }
            val damageMods = mods(ArenaGrowthPhase.DAMAGE)
            val accuracyMods = mods(ArenaGrowthPhase.ACCURACY)
            val damage = rawDamage(f, target, action, completion, reservedTraits) + f.attackPower * action.units /
                target.defense * damageMods.filter { it.unit == ArenaProgressionEffectUnit.DAMAGE_PERCENT }.sumOf { it.value } / 100
            val reductionMods = target.growth.modifiers(context(target, f, action, completion), ArenaGrowthPhase.REDUCTION)
            val piercesTotalReduction = action.effectKey == "TOTAL_MITIGATION_PIERCE"
            val arenaIgnore = when (action.effectKey) {
                "MITIGATION_PIERCE" -> action.value("ignore")
                "TOTAL_MITIGATION_PIERCE" -> action.value("ignore")
                "ULTIMATE" -> action.value("ignore")
                "COUNTER_BARRIER" -> if (target.shield <= 0.0) action.value("ignore") else 0.0
                else -> 0.0
            }
            val ignore = (listOf(effectValue(f, Kind.OBSERVE, completion) +
                damageMods.filter { it.traitId == "AT9_RANGER_B07" }.sumOf { it.value },
                effectValue(f, Kind.PIERCE, completion),
                if (action.id == BASIC) effectValue(f, Kind.BASIC_PIERCE, completion) else 0.0,
                damageMods.filter { it.unit == ArenaProgressionEffectUnit.DAMAGE_REDUCTION_IGNORE_PERCENT &&
                    it.traitId != "AT9_RANGER_B07" }.sumOf { it.value }, arenaIgnore).max() -
                (target.pierceSuppression?.takeIf { it.expires >= completion }?.value ?: 0.0))
                .coerceIn(0.0, 80.0) / 100
            val supportReduction = protection(target, action, completion).second
            val growthReduction = reductionMods.sumOf { it.value } / 100
            val rate = if (piercesTotalReduction) {
                (supportReduction + growthReduction) * (1 - ignore)
            } else {
                supportReduction * (1 - ignore) + growthReduction
            }.coerceIn(0.0, .60)
            var effective = damage * (1 - rate)
            if (target.has(Kind.DAMAGE_CAP, completion)) {
                val cap = target.effects.getValue(Kind.DAMAGE_CAP).support.magnitude / 100
                effective = min(effective, target.shield + target.maxHp * cap)
            }
            if (target.has(Kind.GRACE, completion) && effective - target.shield >= target.maxHp * .15)
                effective -= min(max(0.0, effective - target.shield) * .35, target.maxHp * .12)
            val evade = if (target.has(Kind.EVASION, completion)) 1 - effectValue(target, Kind.EVASION, completion) / 100 else 1.0
            val mirror = when (action.effectKey) {
                "COUNTER_SUPPORT" -> 1.0
                "COUNTER_ILLUSION_HEAL" -> target.effects[Kind.MIRROR]
                    ?.takeIf { it.expires >= completion }
                    ?.let { active ->
                        var removals = action.value("remove").roundToInt().coerceAtLeast(0)
                        if (removals > 0 && target.has(Kind.STEALTH, completion)) removals--
                        val remaining = (active.charges - removals).coerceAtLeast(0)
                        if (remaining == 0) 1.0 else {
                            val blockChance = 1.0 / (remaining + 1) *
                                if (f.has(Kind.TRUTH, completion)) .5 else 1.0
                            1 - blockChance
                        }
                    } ?: 1.0
                else -> target.effects[Kind.MIRROR]?.takeIf { it.expires >= completion }?.let { active ->
                    val checks = if (action.effectKey == "MIRROR_PRESSURE")
                        min(active.charges, action.value("mirrorChecks").roundToInt().coerceAtLeast(1)) else 1
                    var allBlocked = 1.0
                    repeat(checks) { index ->
                        val remaining = (active.charges - index).coerceAtLeast(1)
                        allBlocked *= 1.0 / (remaining + 1) *
                            if (f.has(Kind.TRUTH, completion)) .5 else 1.0
                    }
                    1 - allBlocked
                } ?: 1.0
            }
            val accuracy = (accuracy(f, target, action, completion, reservedTraits) +
                accuracyMods.sumOf { it.value } / 100)
                .coerceIn(0.0, 1.0)
            val bypassPercent = maxOf(
                if (f.has(Kind.PHASE, completion)) effectValue(f, Kind.PHASE, completion) else 0.0,
                if (action.effectKey == "SHIELD_BYPASS") action.value("bypass") else 0.0,
            ).coerceIn(0.0, 60.0)
            val bypass = effective * bypassPercent / 100
            val ordinaryAbsorbed = min(target.shield, max(0.0, effective - bypass))
            var remainingShield = max(0.0, target.shield - ordinaryAbsorbed)
            val shatterKind = if (action.id == BASIC) Kind.BASIC_SHATTER else Kind.SKILL_SHATTER
            val shatter = f.effects[shatterKind]?.takeIf { it.expires >= completion }
            val supportShatter = if (shatter != null) {
                val bonus = f.growth.modifiers(c.copy(sourceSupportId = shatter.support.id,
                    consumedSupports = setOf(shatter.support.id)), ArenaGrowthPhase.SUPPORT_POWER).sumOf { it.value } / 100
                val coreScale = if (shatterKind == Kind.BASIC_SHATTER && f.core("B"))
                    f.coreValue("B", "perApplicationShatterMultiplier", .6) else 1.0
                min(remainingShield, ordinaryAbsorbed * shatter.support.magnitude / 100 * coreScale * (1 + bonus))
            } else 0.0
            remainingShield -= supportShatter
            val traitShatter = min(remainingShield, ordinaryAbsorbed *
                mods(ArenaGrowthPhase.SHIELD).filter { it.unit == ArenaProgressionEffectUnit.SHIELD_DAMAGE_PERCENT }.sumOf { it.value } / 100)
            remainingShield -= traitShatter
            val arenaShatter = if (action.effectKey in setOf("SHIELD_SHATTER", "COUNTER_BARRIER")) {
                min(remainingShield, ordinaryAbsorbed * action.value("shatter") / 100)
            } else 0.0
            var directValue = min(effective, target.hp + target.shield)
            action.arena?.hpDamageCapPercent?.takeIf { it > 0.0 }?.let { capPercent ->
                directValue = ordinaryAbsorbed + min(
                    max(0.0, directValue - ordinaryAbsorbed),
                    target.maxHp * capPercent / 100,
                )
            }
            val preMirrorChance = accuracy * evade
            val landChance = preMirrorChance * mirror
            val directExpected =
                (directValue + supportShatter + traitShatter + arenaShatter) * landChance
            val predictedHpDamage = max(0.0, directValue - ordinaryAbsorbed)
            val shieldBroken = target.shield > 0.0 && remainingShield - arenaShatter <= 1e-9
            return directExpected + expectedArenaEffectUtility(
                f = f,
                target = target,
                action = action,
                turn = completion,
                directExpected = directExpected,
                predictedHpDamage = predictedHpDamage,
                preMirrorChance = preMirrorChance,
                landChance = landChance,
                shieldBroken = shieldBroken,
            )
        }

        /**
         * Conservative public-state value for effects that resolve after the direct-hit number.
         * It never samples RNG, predicts a future opponent choice, or mutates combat state. The
         * global ceiling keeps a conditional status from outweighing the attack that delivers it.
         */
        private fun expectedArenaEffectUtility(
            f: Fighter,
            target: Fighter,
            action: Action,
            turn: Int,
            directExpected: Double,
            predictedHpDamage: Double,
            preMirrorChance: Double,
            landChance: Double,
            shieldBroken: Boolean,
        ): Double {
            if (!action.isSkill || action.arena == null) return 0.0

            val ownBasic = f.attackPower / target.defense * rules.hitChance *
                (1 - protection(target, f.attacks[0], turn).second)
            val enemyBasic = target.attackPower / f.defense * rules.hitChance *
                (1 - protection(f, target.attacks[0], turn).second)
            fun blocked(kind: String): Boolean =
                target.sameStatusGuard?.let { it.first == kind && it.second >= turn } == true ||
                    target.has(Kind.STATUS_GUARD, turn)
            fun dotValue(kind: String, tickKey: String, turns: Int, eligible: Boolean): Double {
                val tickPercent = action.value(tickKey)
                if (!eligible || tickPercent <= 0.0 || turns <= 0 || blocked(kind)) return 0.0
                val reduction = max(
                    effectValue(target, Kind.IRON, turn),
                    effectValue(target, Kind.RESTRAINT, turn) +
                        (target.effects[Kind.RESTRAINT]?.reductionBonus ?: 0.0) * 100,
                ).coerceIn(0.0, 95.0) / 100
                val requested = f.attackPower * tickPercent / 100 / target.defense *
                    (1 - reduction) * turns
                val retained = target.harmful.filter { it.kind == kind && it.expires >= turn }
                    .maxOfOrNull {
                        it.magnitude / target.defense * (1 - reduction) *
                            max(0, it.expires - turn)
                    } ?: 0.0
                return max(0.0, requested - retained)
                    .coerceAtMost(target.hp + target.shield) * landChance
            }
            fun vulnerabilityValue(value: Double, eligible: Boolean): Double {
                if (!eligible || value <= 0.0 || blocked("arena_vulnerability")) return 0.0
                val retained = target.vulnerability?.takeIf { it.expires >= turn }?.value ?: 0.0
                return ownBasic * max(0.0, value - retained) / 100 * .55 * landChance
            }
            fun harmfulValue(harmful: Harmful): Double {
                val remaining = max(0, harmful.expires - turn)
                return when (harmful.kind) {
                    "poison", "burn", "bleed" ->
                        harmful.magnitude / f.defense * remaining
                    "accuracy" -> ownBasic * harmful.magnitude / 100 * remaining
                    "heal_reduction" -> (f.maxHp - f.hp) * harmful.magnitude / 100 * .5
                    else -> ownBasic * .1 * remaining
                }
            }

            val utility = when (action.effectKey) {
                "PREPARATION_PUNISH" -> dotValue(
                    "bleed", "masterBleedTick", action.value("masterBleedTurns").roundToInt(),
                    target.casting?.let { it.action.isAttack && it.remaining > action.cast } == true &&
                        predictedHpDamage > 0.0,
                )
                "SHIELD_SHATTER" -> vulnerabilityValue(
                    action.value("masterRupture"),
                    shieldBroken && action.value("masterRuptureTurns") > 0.0,
                )
                "ATTACK_SUPPRESSION" -> {
                    if (predictedHpDamage <= 0.0 || blocked("arena_attack_suppression")) 0.0 else {
                        val charges = action.value("masterSuppressionCharges")
                            .roundToInt().coerceAtLeast(1)
                        enemyBasic * action.value("reduction") / 100 * charges * .55 * landChance
                    }
                }
                "FOLLOW_UP_MARK" -> {
                    val charges = action.value("masterFollowUpCharges").roundToInt().coerceAtLeast(1)
                    val retained = f.followUpMark?.takeIf { it.expires >= turn }
                    val damage = max(0.0, action.value("bonus") - (retained?.damage ?: 0.0))
                    val accuracy = max(0.0, action.value("accuracy") - (retained?.accuracy ?: 0.0))
                    ownBasic * (damage + accuracy * .5) / 100 * charges * .55 * landChance
                }
                "HEALING_REDUCTION" -> {
                    val healKinds = setOf(Kind.HEAL, Kind.REGEN, Kind.LIFESTEAL, Kind.BANDAGE, Kind.LAY_HANDS)
                    val healValue = if (predictedHpDamage > 0.0 &&
                        target.supports.any { it.support?.kind in healKinds } &&
                        !blocked("arena_healing_reduction")) {
                        min(target.maxHp - target.hp, target.maxHp * .2) *
                            action.value("reduction") / 100 * landChance
                    } else 0.0
                    healValue + dotValue(
                        "bleed", "masterBleedTick", action.value("masterBleedTurns").roundToInt(),
                        predictedHpDamage > 0.0,
                    )
                }
                "STATUS_EXPLOIT" -> vulnerabilityValue(
                    action.value("vulnerability"),
                    !hasDebuff(target, turn) &&
                        target.arenaHealingReduction?.expires?.let { it >= turn } != true,
                )
                "CAST_DELAY" -> {
                    val pending = target.casting
                    if (pending == null || pending.remaining <= action.cast || target.immune(turn) ||
                        blocked("arena_cast_delay")) 0.0 else {
                        val delayedRate = if (pending.action.isAttack) {
                            target.attackPower * pending.action.units / f.defense /
                                max(1, pending.action.cast)
                        } else {
                            enemyBasic * .2
                        }
                        val chance = (.55 + action.value("controlAccuracy") / 100).coerceIn(.35, .95)
                        delayedRate * action.value("delay") * chance * .5 * landChance
                    }
                }
                "LOW_HP_DRAIN" -> {
                    if (predictedHpDamage <= 0.0 ||
                        f.hp > f.maxHp * action.value("threshold") / 100) 0.0 else {
                        val requested = min(
                            predictedHpDamage * action.value("drain") / 100,
                            f.maxHp * action.value("cap") / 100,
                        )
                        val reduction = f.arenaHealingReduction?.takeIf { it.expires >= turn }
                            ?.value?.coerceIn(0.0, 100.0) ?: 0.0
                        min(f.maxHp - f.hp, requested) * (1 - reduction / 100) * landChance
                    }
                }
                "HEAVY_CHANNEL" -> dotValue(
                    "bleed", "masterBleedTick", action.value("masterBleedTurns").roundToInt(),
                    predictedHpDamage > 0.0,
                )
                "MANA_PRESSURE" -> {
                    val loss = min(target.mpUnits / MP_SCALE.toDouble(), action.value("drain"))
                    enemyBasic * loss * .02 * landChance
                }
                "DISPEL_OR_DRAIN" -> {
                    if (predictedHpDamage <= 0.0) 0.0 else {
                        val maximum = action.value("dispel").roundToInt().coerceAtLeast(0)
                        val buffs = target.effects.values.filter { it.expires >= turn }
                            .sortedWith(compareByDescending<Active> { it.expires - turn }
                                .thenBy { it.support.id })
                        if (buffs.isNotEmpty() && maximum > 0) {
                            enemyBasic * min(maximum, buffs.size) * .15 * landChance
                        } else {
                            val loss = min(target.mpUnits / MP_SCALE.toDouble(), action.value("drain"))
                            val restored = min(
                                (f.maxMpUnits - f.mpUnits) / MP_SCALE.toDouble(),
                                loss,
                            )
                            (enemyBasic * loss * .02 + ownBasic * restored * .01) * landChance
                        }
                    }
                }
                "COUNTER_ILLUSION_HEAL" -> {
                    val maximum = action.value("remove").roundToInt().coerceAtLeast(0)
                    val opportunities =
                        (if (target.has(Kind.STEALTH, turn)) 1 else 0) +
                            (target.effects[Kind.MIRROR]?.takeIf { it.expires >= turn }?.charges ?: 0)
                    ownBasic * min(maximum, opportunities) * .03 * preMirrorChance
                }
                "COUNTER_RUSH" -> dotValue(
                    "burn", "burn", action.arena.durationTurns,
                    action.value("burn") > 0.0,
                )
                "COUNTER_STATUS" -> {
                    val maximum = action.value("cleanse").roundToInt().coerceAtLeast(0)
                    val active = f.harmful.filter { it.expires >= turn }
                    val byId = active.withIndex().associate { it.index to it.value }
                    val plan = cleanseRemovalPlan(
                        active.mapIndexed { index, harmful ->
                            CleanseRemovalCandidate(
                                id = index,
                                kind = harmful.kind,
                                impact = harmful.magnitude * (harmful.expires - turn + 1),
                            )
                        },
                        accuracyOnly = false,
                        maximum = maximum,
                    )
                    val removed = plan.flatten().mapNotNull(byId::get)
                    removed.sumOf(::harmfulValue) +
                        directExpected * action.value("bonus") / 100 *
                        if (plan.isEmpty()) 0.0 else 1.0
                }
                "COUNTER_PIERCE" -> {
                    if (f.lastPiercingHitTakenTurn < turn - 2 || blocked("arena_pierce_suppression")) {
                        0.0
                    } else {
                        ownBasic * action.value("pierceReduction") / 100 * .35 * landChance
                    }
                }
                else -> 0.0
            }
            val ceiling = max(ownBasic, directExpected) * .75
            return utility.coerceIn(0.0, ceiling)
        }

        private fun scriptedChoice(f: Fighter, target: Fighter, turn: Int): Choice? {
            val id = scripted[f.id to turn] ?: return null
            val action = (f.attacks + f.supports).firstOrNull { it.id == id } ?: return null
            if (action.arena?.let { turn < it.earliestTurn || (it.oncePerBattle && id in f.usedArenaOnce) } == true) return null
            val choice = price(f, target, action, turn)
            if (choice.costUnits > f.mpUnits || turn < (f.readyAt[id] ?: 0)) return null
            val support = action.support
            if (support != null && (f.mustCompleteAttack || f.tauntUntil >= turn || f.has(support.kind, turn) ||
                supportUsesExhausted(f, support) || !supportEligible(f, target, support, turn))) return null
            return choice
        }

        private fun choose(f: Fighter, target: Fighter, turn: Int): Choice {
            var best = price(f, target, f.attacks[0], turn)
            var bestScore = Double.NEGATIVE_INFINITY
            val basicExpected = f.attackPower / target.defense * rules.hitChance
            for (original in f.attacks) {
                val choice = price(f, target, original, turn)
                val action = choice.action
                if (action.arena?.let {
                        turn < it.earliestTurn || (it.oncePerBattle && action.id in f.usedArenaOnce)
                    } == true) continue
                if (choice.costUnits > f.mpUnits || turn < (f.readyAt[action.id] ?: 0)) continue
                val finish = turn + action.cast - 1
                val tokens = attackTokens(f, action, turn)
                var score = expected(f, target, action, finish, tokens, choice.costUnits, turn) /
                    (action.cast * (1 + .015 * (action.cast - 1)))
                score -= .06 * basicExpected * choice.costUnits / max(1, f.mpUnits)
                target.casting?.let { incoming ->
                    if (incoming.action.isAttack && action.cast > 1 && incoming.remaining < action.cast &&
                        rawDamage(target, f, incoming.action, turn + incoming.remaining - 1,
                            incoming.reservedTraits) *
                        (1 - protection(f, incoming.action, turn + incoming.remaining - 1).second) >= f.hp + f.shield) score *= .5
                }
                if (better(score, choice, bestScore, best)) { best = choice; bestScore = score }
            }
            // Current support policy: after one support, finish one direct attack before another support.
            // This is a visible AI policy, not a reduction of the actually owned candidate list.
            if (f.tauntUntil < turn) for (original in f.supports) {
                val choice = price(f, target, original, turn)
                val action = choice.action
                val support = checkNotNull(action.support).copy(mp = action.mp, castTurns = action.cast)
                if (f.mustCompleteAttack) continue
                if (f.consecutiveSupports >= 2) continue
                if (supportUsesExhausted(f, support) || !supportEligible(f, target, support, turn)) continue
                if (f.has(support.kind, turn) || turn < (f.readyAt[action.id] ?: 0)) continue
                if (support.kind == Kind.HEAL && turn < f.healReadyAt) continue
                if (choice.costUnits > f.mpUnits) continue
                val score = supportValue(f, target, support, turn, basicExpected) -
                    .06 * basicExpected * choice.costUnits / max(1, f.mpUnits)
                if (better(score, choice, bestScore, best)) { best = choice; bestScore = score }
            }
            return best
        }

        // A rank-ten tree support can upgrade a base once-per-duel right to two completions.
        private fun supportUsesExhausted(f: Fighter, support: Support): Boolean =
            support.oncePerBattle && (f.supportUseCounts[support.id] ?: 0) >= support.charges.coerceAtLeast(1)

        private fun payableFollowups(f: Fighter, target: Fighter, support: Support, turn: Int): List<Action> {
            val supportAction = f.supports.first { it.id == support.id }
            val preparation = price(f, target, supportAction, turn)
            val remainingMp = f.mpUnits - preparation.costUnits
            if (remainingMp < 0) return emptyList()
            val nextTurn = turn + preparation.action.cast
            return f.attacks.filter { it.isSkill }.mapNotNull { original ->
                var action = transform(f, target, original, nextTurn)
                if (nextTurn < (f.readyAt[original.id] ?: 0)) return@mapNotNull null
                if (support.kind == Kind.STEALTH && f.core("A") && original.originalCast in 2..3)
                    action = action.copy(cast = max(1, original.originalCast - 1), coreIds = action.coreIds + "rogue_a")
                if (support.kind == Kind.CONDENSE && f.core("A")) {
                    if (original.originalCast != 3) return@mapNotNull null
                    action = action.copy(extraMp = f.coreValue("A", "additionalAttackMp", 12.0).toInt(), coreIds = action.coreIds + "mage_a")
                }
                if (support.kind == Kind.REVEAL && f.core("B") && original.originalCast in 2..3 &&
                    (target.has(Kind.STEALTH, turn) || target.has(Kind.MIRROR, turn)))
                    action = action.copy(cast = max(1, original.originalCast - 1), coreIds = action.coreIds + "ranger_b")
                if (support.duration > 0 && action.cast > support.duration) return@mapNotNull null
                val context = context(f, target, action, nextTurn).copy(
                    selfMpRatio = remainingMp.toDouble() / f.maxMpUnits,
                    startSelfMpRatio = remainingMp.toDouble() / f.maxMpUnits,
                    selfSupports = f.effects.values.filter { it.expires >= nextTurn }.map { it.support.id }.toSet() + support.id,
                )
                val legacy = listOf(ArenaProgressionCatalog.WARRIOR_A01, ArenaProgressionCatalog.MAGE_A01,
                    ArenaProgressionCatalog.CLERIC_A02).filter { f.tokenAvailable(it, nextTurn) != null }.sumOf { f.traitValue(it) } +
                    if (action.originalCast in 2..3 && (support.kind == Kind.FOCUS || f.has(Kind.FOCUS, nextTurn)))
                        f.traitValue(ArenaProgressionCatalog.PALADIN_A02) else 0.0
                val discount = (legacy + f.growth.modifiers(context, ArenaGrowthPhase.COST).sumOf { it.value }).coerceAtMost(30.0)
                val cost = max(MP_SCALE, ceil(action.mp * MP_SCALE * (1 - discount / 100) - 1e-9).toInt()) + action.extraMp * MP_SCALE
                action.takeIf { cost <= remainingMp }
            }
        }

        private fun supportEligible(f: Fighter, target: Fighter, support: Support, turn: Int): Boolean {
            val ownRecent = f.recentDamage?.let { it.turn >= turn - 1 && it.sequence !in f.usedRecent } == true
            val enemyBuff = target.effects.values.any { it.expires >= turn && removableBuff(it.support.kind) }
            val availableSkills = payableFollowups(f, target, support, turn)
            return when (support.conditionKey) {
                "RECENT_DAMAGE" -> ownRecent && (support.kind != Kind.RETRIBUTION || availableSkills.isNotEmpty())
                "OWN_MISS" -> f.recentMiss?.turn?.let { it >= turn - 1 } == true
                "ENEMY_MISS" -> target.recentMiss?.turn?.let { it >= turn - 1 } == true
                "RECENT_HEAL" -> target.recentHealing.any { it.turn >= turn - 1 && it.sequence !in f.usedRecent }
                "ENEMY_BASIC" -> target.casting?.let {
                    it.action.id == BASIC && it.remaining >= support.cast
                } == true || target.attacks.none { it.isSkill && it.mp * MP_SCALE <= target.mpUnits }
                "ENEMY_SKILL" -> target.casting?.let {
                    it.action.isSkill && it.remaining >= support.cast
                } == true
                "BIG_HIT" -> target.casting?.let {
                    it.action.isAttack && it.remaining >= support.cast &&
                        target.attackPower * it.action.units / f.defense >= f.maxHp * support.threshold / 100
                } == true
                "LOW_HP" -> f.hp <= f.maxHp * support.threshold / 100 &&
                    (support.kind != Kind.LOW_SHIELD || f.shield <= 0) &&
                    (support.kind != Kind.LAY_HANDS || !f.core("C") ||
                        min(f.maxHp * support.magnitude / 100, sqrt(f.input.fighter.stats.charisma) * support.secondary) > f.shield)
                "ENEMY_LOW_HP" -> (target.hp <= target.maxHp * support.threshold / 100 ||
                    (f.heroClass == HeroClass.PALADIN && f.core("B") && coreReady(f, "B", turn))) && availableSkills.isNotEmpty()
                "SHIELD" -> target.shield > 0 && (support.kind != Kind.SKILL_SHATTER || availableSkills.isNotEmpty())
                "DEFENSE", "ENEMY_MITIGATION" -> target.effects.values.any { it.expires >= turn && it.support.kind in
                    setOf(Kind.IRON, Kind.RESTRAINT, Kind.BASIC_GUARD, Kind.SKILL_GUARD) }
                "ACCURACY_DEBUFF" -> f.harmful("accuracy", turn).isNotEmpty()
                "DEBUFF" -> hasDebuff(f, turn)
                "POISON_ROOM" -> target.harmful("poison", turn).size < 3
                "POISON_EXISTING" -> target.harmful("poison", turn).size in 1..2
                "ENEMY_ILLUSION" -> target.has(Kind.STEALTH, turn) || target.has(Kind.MIRROR, turn)
                "ENEMY_BUFF" -> enemyBuff
                "ENEMY_ANY_BUFF" -> target.effects.any { it.value.expires >= turn }
                "MISSING_HP" -> f.hp <= f.maxHp * .95 && (support.kind !in setOf(Kind.HEAL, Kind.REGEN) || turn >= f.healReadyAt)
                "CONTROL" -> !target.immune(turn) && (support.kind != Kind.SLEEP || target.harmful.none {
                    it.kind in setOf("poison", "burn", "bleed") && it.expires > turn }) &&
                    (support.kind != Kind.TAUNT || target.supports.any { it.mp * MP_SCALE <= target.mpUnits }) &&
                    (support.kind != Kind.TRAP || target.casting?.remaining?.let { it >= support.cast } == true)
                "STATUS_THREAT" -> threat(target, turn)
                "ENEMY_HEAL" -> target.hp < target.maxHp * .75 && target.supports.any {
                    it.support?.kind in setOf(Kind.HEAL, Kind.REGEN, Kind.LIFESTEAL, Kind.BANDAGE, Kind.LAY_HANDS) && it.mp * MP_SCALE <= target.mpUnits }
                "ENEMY_MP" -> target.mpUnits > 0
                "SKILL_FOLLOWUP", "FOLLOWUP" -> availableSkills.any {
                    when {
                        support.kind == Kind.CONDENSE && f.core("A") -> it.originalCast == 3
                        support.kind == Kind.STEALTH && f.core("A") -> it.originalCast in 2..3
                        else -> true
                    }
                }
                else -> true
            }
        }

        private fun supportValue(f: Fighter, target: Fighter, support: Support, turn: Int, basicRate: Double): Double {
            val ownRate = max(1e-9, f.attacks.filter { it.mp * MP_SCALE <= f.mpUnits }
                .maxOf { f.attackPower * it.units * rules.hitChance / target.defense / it.cast })
            val enemyOptions = target.attacks.filter { it.mp * MP_SCALE <= target.mpUnits }
            val enemyRate = max(1e-9, enemyOptions.maxOf { target.attackPower * it.units * rules.hitChance / f.defense / it.cast })
            val horizon = min((f.hp + f.shield) / enemyRate, (target.hp + target.shield) / ownRate).coerceIn(1.0, 8.0)
            val futureTurns = min(support.duration.toDouble(), max(0.0, horizon - support.cast))
            val followupStart = turn + support.cast
            // A preparation must not be valued using an attack still on cooldown when its
            // mandatory follow-up can start. Public readyAt values are not future RNG.
            val payableSkill = payableFollowups(f, target, support, turn)
            val nextSkill = payableSkill.maxByOrNull { f.attackPower * it.units * rules.hitChance / it.cast }
            val nextDamage = nextSkill?.let { f.attackPower * it.units / target.defense } ?: 0.0
            val incoming = target.casting?.takeIf { it.action.isAttack }?.action ?: enemyOptions.maxByOrNull {
                target.attackPower * it.units * rules.hitChance / it.cast
            }!!
            val incomingDamage = target.attackPower * incoming.units / f.defense
            var value = when (support.kind) {
                Kind.IRON -> enemyRate * futureTurns * support.magnitude / 100
                Kind.SHOUT -> ownRate * futureTurns * (if (f.core("A")) f.coreValue("A", "replacementShoutDamageBonusPercent", support.magnitude) else support.magnitude) / 100
                Kind.EVASION -> min(f.hp, incomingDamage) * rules.hitChance * support.magnitude / 100
                Kind.STEALTH -> if (nextSkill != null && nextSkill.cast + 1 <= horizon + 1) {
                    val legacyA02Combo = f.hasTrait(ArenaProgressionCatalog.ROGUE_A02) &&
                        !f.core("A") && nextSkill.originalCast == 1
                    val linkedBonus = if (legacyA02Combo)
                        f.traitValue(ArenaProgressionCatalog.ROGUE_A02) else 0.0
                    val multiplier = if (legacyA02Combo)
                        1 + (support.magnitude + linkedBonus) / 100
                    else support.magnitude / 100
                    min(nextDamage * multiplier, target.hp + target.shield) * rules.hitChance *
                        support.cast / (support.cast + nextSkill.cast)
                } else 0.0
                Kind.AIM -> ownRate * futureTurns * (if (f.core("A")) 0.0 else support.magnitude) / 100 +
                    ownRate * futureTurns * (min(1.0, rules.hitChance + support.secondary / 100) - rules.hitChance)
                Kind.BASIC_GUARD -> {
                    val basicShare = if (enemyOptions.none { it.isSkill }) 1.0 else if (incoming.id == BASIC) .65 else .15
                    target.attackPower / f.defense * rules.hitChance * futureTurns * support.magnitude / 100 * basicShare
                }
                Kind.SHIELD -> min(f.maxHp * support.magnitude / 100, enemyRate * futureTurns)
                Kind.CONDENSE -> if (nextSkill != null && nextSkill.cast + 1 <= horizon + 1) {
                    val multiplier = if (f.hasTrait(ArenaProgressionCatalog.MAGE_A02) && !f.core("A"))
                        1 + support.magnitude / 100 else support.magnitude / 100
                    min(nextDamage * multiplier, target.hp + target.shield) * rules.hitChance *
                        support.cast / (support.cast + nextSkill.cast)
                } else 0.0
                Kind.HEAL -> {
                    val missing = f.maxHp - f.hp
                    if (missing < f.maxHp * .05) return Double.NEGATIVE_INFINITY
                    val clericA01 = f.traitValue(ArenaProgressionCatalog.CLERIC_A01)
                    val heal = f.maxHp * (support.magnitude / 100 + if (clericA01 > 0.0 && f.hp <= f.maxHp * .35)
                        clericA01 / 100.0 else 0.0)
                    min(missing + enemyRate, heal) * if (f.hp <= f.maxHp * .35) 1.65 else 1.15
                }
                Kind.RESTRAINT -> enemyRate * futureTurns * support.magnitude / 100 - ownRate * futureTurns * support.secondary / 100
                Kind.SKILL_GUARD -> if (incoming.isSkill) min(f.hp, incomingDamage * support.magnitude / 100) * rules.hitChance else 0.0
                Kind.FOCUS -> if (nextSkill != null) min(nextDamage, target.hp + target.shield) *
                    (min(1.0, rules.hitChance + support.magnitude / 100) - rules.hitChance) *
                    support.cast / (support.cast + nextSkill.cast) else 0.0
                Kind.BASIC_SHATTER -> min(
                    target.shield,
                    basicRate * support.magnitude / 100 * support.charges.coerceAtLeast(1) *
                        (if (f.core("B")) .6 else 1.0),
                ) * 1.3
                Kind.SKILL_SHATTER -> min(target.shield, nextDamage) * 1.3
                Kind.COUNTER -> basicRate * (if (f.core("C")) min((f.recentDamage?.amount ?: 0.0) * .5, f.maxHp * .08) / max(1.0, basicRate) else support.magnitude / 100)
                Kind.PURSUIT -> basicRate * (.35 + .15 / max(.1, rules.hitChance))
                Kind.OPPORTUNITY -> basicRate * support.magnitude / 100
                Kind.BASIC_PIERCE -> basicRate * support.charges.coerceAtLeast(1) *
                    protection(target, f.attacks[0], turn).second * support.magnitude / 100
                Kind.PIERCE, Kind.OBSERVE -> ownRate * futureTurns *
                    protection(target, nextSkill ?: f.attacks[0], turn).second * support.magnitude / 100
                Kind.CLEANSE_ACCURACY, Kind.CLEANSE -> f.harmful.filter { it.expires > turn &&
                    (support.kind == Kind.CLEANSE || it.kind == "accuracy") }.groupBy { it.kind }.maxOfOrNull { (kind, layers) ->
                    when(kind) {
                        "poison", "burn", "bleed" -> layers.sumOf { it.magnitude * max(0, it.expires - turn) } / f.defense
                        "accuracy" -> ownRate * layers.maxOf { it.expires - turn } * layers.maxOf { it.magnitude } / 100
                        "heal_reduction" -> (f.maxHp - f.hp) * layers.maxOf { it.magnitude } / 100
                        else -> basicRate * layers.maxOf { it.expires - turn } * layers.maxOf { it.magnitude } / 100
                    }
                } ?: 0.0
                Kind.SMOKE -> enemyRate * futureTurns * support.magnitude / 100
                Kind.WRIST -> target.attackPower / f.defense * futureTurns * support.magnitude / 100 *
                    if (enemyOptions.none { it.isSkill }) 1.0 else .35
                Kind.MP_DRAIN -> min(target.mpUnits / 1000.0, if (f.core("C")) 30.0 else support.magnitude) * basicRate * .085
                Kind.POISON_COAT -> f.attackPower * support.magnitude / 100 * support.secondary *
                    min(2, 3 - target.harmful("poison", turn).size) * min(1.0, futureTurns / 3) *
                    if (f.core("B")) 1.2 else 1.0
                Kind.POISON_ACCELERATE -> target.harmful("poison", turn).maxByOrNull { it.expires }?.let {
                    it.magnitude * max(0, it.expires - turn) / target.defense
                } ?: 0.0
                Kind.BURN_PREP, Kind.EMBER -> f.attackPower * support.magnitude / 100 * support.secondary *
                    min(1.0, futureTurns / 2) * if (target.shield > 0) .3 else 1.0
                Kind.HEAL_BLOCK_PREP -> (target.maxHp - target.hp) * support.magnitude / 100 * .75
                Kind.REVEAL, Kind.TRUTH -> when {
                    target.has(Kind.STEALTH, turn) -> incomingDamage * if (support.kind == Kind.TRUTH) .5 else 1.0
                    target.has(Kind.MIRROR, turn) -> ownRate * min(4.0, horizon) * if (support.kind == Kind.TRUTH) .18 else .35
                    else -> 0.0
                }
                Kind.DISPEL, Kind.SEAL, Kind.JUDGMENT -> target.effects.values.filter { it.expires >= turn &&
                    (support.kind == Kind.JUDGMENT || removableBuff(it.support.kind)) }.maxOfOrNull {
                    val rate = if (attackBuff(it.support.kind)) enemyRate else ownRate
                    rate * min(futureTurns.takeIf { v -> v > 0 } ?: 4.0, (it.expires - turn).toDouble()) * max(.15, it.support.magnitude / 100)
                } ?: 0.0
                Kind.RAPID -> basicRate * futureTurns * support.magnitude / 100 * if (enemyOptions.none { it.isSkill }) 1.0 else .8
                Kind.STABILIZE -> ownRate * futureTurns *
                    (1 - accuracy(f, target, nextSkill ?: f.attacks[0], turn, emptyMap()))
                Kind.PHASE -> min(target.shield, ownRate * futureTurns * support.magnitude / 100)
                Kind.BANDAGE, Kind.LAY_HANDS -> min(f.maxHp - f.hp, min(f.maxHp * support.magnitude / 100,
                    sqrt(if (support.kind == Kind.BANDAGE) f.input.fighter.stats.constitution else f.input.fighter.stats.charisma) * support.secondary)) * 1.65
                Kind.LOW_SHIELD -> min(f.maxHp * support.magnitude / 100, sqrt(f.input.fighter.stats.charisma) * support.secondary) * 1.35
                Kind.REGEN -> min(f.maxHp - f.hp + enemyRate, f.maxHp * support.magnitude / 100 * min(support.duration.toDouble(), horizon)) * 1.25
                Kind.BLESS -> ownRate * futureTurns * (support.magnitude + support.secondary) / 100
                Kind.RESOLVE -> ownRate * futureTurns * support.magnitude / 100
                Kind.RETRIBUTION, Kind.EXECUTE -> nextDamage * support.magnitude / 100 *
                    support.cast / (support.cast + (nextSkill?.cast ?: 1))
                Kind.LIFESTEAL -> min(f.maxHp - f.hp + enemyRate, min(ownRate * futureTurns * support.magnitude / 100,
                    f.maxHp * support.secondary / 100 * max(1.0, futureTurns / 3)))
                Kind.DAMAGE_CAP -> max(0.0, incomingDamage - f.maxHp * support.magnitude / 100)
                Kind.GRACE -> if (incomingDamage >= f.maxHp * support.threshold / 100)
                    min(incomingDamage * support.magnitude / 100, f.maxHp * support.secondary / 100) else 0.0
                Kind.STATUS_GUARD -> if (threat(target, turn)) max(ownRate, enemyRate) * 1.5 else 0.0
                Kind.SANCTUARY -> if (threat(target, turn)) max(ownRate, enemyRate) * 1.2 else 0.0
                Kind.HEAL_TRACK -> target.recentHealing.filter { it.turn >= turn - 1 && it.sequence !in f.usedRecent }
                    .let { if (f.core("C")) min(it.sumOf { e -> e.amount } * .3, target.maxHp * .08)
                    else min((it.lastOrNull()?.amount ?: 0.0) * .4, nextDamage * .4) }
                Kind.TAUNT -> if (target.hp < target.maxHp * .6) enemyRate * .8 else 0.0
                Kind.SLEEP -> enemyRate * .35
                Kind.TRAP -> ownRate * .30
                Kind.MIRROR -> min(f.hp, enemyRate * min(4.0, futureTurns)) * .32
            }
            if (support.kind != Kind.HEAL) value *= 1.15
            target.casting?.let {
                if (it.action.isAttack && it.remaining < support.cast && incomingDamage >= f.hp + f.shield) value *= .25
            }
            if (support.cast > 1 && enemyRate * support.cast >= f.hp + f.shield) value *= .25
            // Avoid support-only time extensions; a zero-value state can never beat a useful attack.
            return if (value > 0.0) value / support.cast else -basicRate
        }

        private fun better(score: Double, candidate: Choice, bestScore: Double, best: Choice): Boolean =
            score > bestScore + 1e-10 || (abs(score - bestScore) <= 1e-10 &&
                (candidate.costUnits < best.costUnits ||
                    (candidate.costUnits == best.costUnits && candidate.action.id < best.action.id)))
    }

    private fun mean3(a: Double, b: Double, c: Double) =
        (a / 3.0 + b / 3.0 + c / 3.0).coerceAtMost(maxOf(a, b, c))

    private fun hash(key: String): Long {
        var value = -3750763034362895579L
        key.forEach { value = (value xor it.code.toLong()) * 1099511628211L }
        return value
    }

    private fun random(seed: Long, key: Long, turn: Int, cast: Int, kind: Int): Double {
        var x = seed xor key xor (turn.toLong() * -7046029254386353131L) xor
            (cast.toLong() * -4658895280553007687L) xor (kind.toLong() * -7723592293110705685L)
        x = (x xor (x ushr 30)) * -4658895280553007687L
        x = (x xor (x ushr 27)) * -7723592293110705685L
        x = x xor (x ushr 31)
        return (x ushr 11).toDouble() / 9007199254740992.0
    }
}
