package com.nullplaying.engine.arena

/** Public, present-state observations only. No RNG or predicted outcome is accepted. */
enum class ArenaGrowthEvent {
    NONE, OWN_HIT, TAKEN_HIT, OWN_MISS, ENEMY_MISS, EVADED, MIRROR_BLOCKED,
    SHIELD_ABSORBED, ENEMY_SHIELD_ABSORBED, SHIELD_BROKEN, DAMAGE_REDUCED,
    HEALED, ENEMY_HEALED, HOT_HEALED, SUPPORT_COMPLETED, SUPPORT_EXPIRED,
    ENEMY_SUPPORT_EXPIRED, CLEANSED, DISPELLED, STATUS_BLOCKED, STATUS_SHORTENED,
    CONTROL_RESISTED, MANA_DRAINED, ENEMY_PAID, TURN_END,
}
enum class ArenaGrowthPhase { COST, ACCURACY, DAMAGE, REDUCTION, SHIELD, HEAL, DOT, SUPPORT_POWER }
enum class ArenaGrowthTarget { ANY_ATTACK, BASIC, SKILL, ONE_TURN_ATTACK, ONE_TURN_SKILL, TWO_TURN_SKILL, LONG_SKILL, ANY_SUPPORT, LINKED_SUPPORT, INCOMING_BASIC, INCOMING_SKILL, DOT, HEAL, SHIELD, SUPPORT_POWER }

data class ArenaGrowthContext(
    val turn: Int,
    val event: ArenaGrowthEvent = ArenaGrowthEvent.NONE,
    val actionId: String = "",
    val sourceSupportId: String = "",
    val originalCastTurns: Int = 1,
    val isBasic: Boolean = false,
    val isSupport: Boolean = false,
    val selfHpRatio: Double = 1.0,
    val selfHpBeforeRatio: Double = selfHpRatio,
    val selfMpRatio: Double = 1.0,
    val targetHpRatio: Double = 1.0,
    val targetMpRatio: Double = 1.0,
    val selfShield: Double = 0.0,
    val targetShield: Double = 0.0,
    val selfCastOriginalTurns: Int = 0,
    val targetCastOriginalTurns: Int = 0,
    val targetCastRemaining: Int = 0,
    val targetCastingSupport: Boolean = false,
    val targetCastingRecoveryOrProtection: Boolean = false,
    val selfSupports: Set<String> = emptySet(),
    val targetSupports: Set<String> = emptySet(),
    val consumedSupports: Set<String> = emptySet(),
    val supportRemainingTurns: Map<String, Int> = emptyMap(),
    val hpDamage: Double = 0.0,
    val absorbed: Double = 0.0,
    val reduced: Double = 0.0,
    val healing: Double = 0.0,
    val healingMaxHpRatio: Double = 0.0,
    val paidMp: Double = 0.0,
    val manaLost: Double = 0.0,
    val selfCannotAffordOwnedSkill: Boolean = false,
    val targetCannotAffordOwnedSkill: Boolean = false,
    val targetHasDamageReduction: Boolean = false,
    val targetHasAttackBuff: Boolean = false,
    val targetHasOwnBurn: Boolean = false,
    val ownPoisonLayers: Int = 0,
    val ownPoisonFutureTicks: Int = 0,
    val targetMirrorOpportunities: Int = 0,
    val targetHasStealth: Boolean = false,
    val removedStatusKind: String = "",
    val appliedStatusKind: String = "",
    val supportHadUnusedCharges: Boolean = false,
    val supportWasAttackBuff: Boolean = false,
    val startSelfMpRatio: Double = selfMpRatio,
    val startTargetCastOriginalTurns: Int = targetCastOriginalTurns,
    val startTargetCastRemaining: Int = targetCastRemaining,
    val startTargetCastingSupport: Boolean = targetCastingSupport,
    val startTargetCastingRecoveryOrProtection: Boolean = targetCastingRecoveryOrProtection,
    val capturedHealingMaxHpRatio: Double = 0.0,
    val supportInstanceId: Int = 0,
    val eventSequence: Int = 0,
    val castId: Int? = null,
    val selfCastId: Int? = null,
    val selfCastActionId: String = "",
    val castStartedTurn: Int? = null,
) {
    fun active(id: String) = id in selfSupports
    fun consumed(id: String) = id in consumedSupports
    fun causedBy(id: String) = sourceSupportId == id
    val skill: Boolean get() = !isBasic && !isSupport
    val attack: Boolean get() = !isSupport
    val targetLongCast: Boolean get() = targetCastOriginalTurns in 2..3 && !targetCastingSupport
    val observedLongCast: Boolean get() = startTargetCastOriginalTurns in 2..3 && !startTargetCastingSupport
}

data class ArenaGrowthModifier(val traitId: String, val value: Double, val unit: ArenaProgressionEffectUnit,
    val phase: ArenaGrowthPhase, val target: ArenaGrowthTarget, val causeSequence: Int = 0,
    val expiresAtTurn: Int? = null, val evaluatedTurn: Int = 0)
data class ArenaGrowthActivation(val traitId: String, val value: Double, val causeSequence: Int, val expiresAtTurn: Int)

internal data class ArenaGrowthRule(
    val phase: ArenaGrowthPhase,
    val target: ArenaGrowthTarget,
    val event: ArenaGrowthEvent = ArenaGrowthEvent.NONE,
    val supportTarget: String? = null,
    val conditionEn: String,
    val conditionJa: String,
    val predicate: (ArenaGrowthContext, ArenaGrowthHistory) -> Boolean,
)
internal data class ArenaGrowthHistory(
    val ownHits: List<ArenaGrowthContext> = emptyList(),
    val takenHits: List<ArenaGrowthContext> = emptyList(),
    val basicHits: List<ArenaGrowthContext> = emptyList(),
    val lastMissTurn: Int = -1,
    val hotTicks: Map<Int, Int> = emptyMap(),
)

/** Each fighter owns this deterministic token ledger. Peeking for automatic choice never consumes. */
class ArenaProgressionRuntime(allocations: List<ArenaSupportTraitRank>) {
    private val values = allocations.filter { it.id !in legacyTraitIds && ArenaProgressionCatalog.find(it.id)?.isCore == false }
        .associate { it.id to requireNotNull(ArenaProgressionCatalog.find(it.id)).value(it.rank, it.enhancement) }
    private data class Token(val activation: ArenaGrowthActivation, val availableFrom: Int, val castId: Int? = null)
    private val tokens = mutableMapOf<String, Token>()
    private val readyAt = mutableMapOf<String, Int>()
    private var history = ArenaGrowthHistory()

    fun observe(context: ArenaGrowthContext): List<ArenaGrowthActivation> {
        expire(context.turn)
        if (context.selfHpRatio <= 0.0) return emptyList()
        val activated = mutableListOf<ArenaGrowthActivation>()
        for ((id, value) in values) {
            val rule = ArenaGrowthRules.rules.getValue(id)
            if (rule.event == ArenaGrowthEvent.NONE || rule.event != context.event ||
                id in tokens || context.turn < (readyAt[id] ?: 1) || !rule.predicate(context, history)) continue
            val definition = requireNotNull(ArenaProgressionCatalog.find(id))
            val activation = ArenaGrowthActivation(id, value, context.eventSequence, context.turn + definition.windowTurns)
            tokens[id] = Token(activation, if (id == "AT9_ROGUE_B02") context.turn else context.turn + 1,
                if (id == "AT9_ROGUE_B02") context.selfCastId else null)
            readyAt[id] = context.turn + definition.cooldownTurns + 1
            activated += activation
            if (id in setOf("AT9_ROGUE_B01", "AT9_MAGE_B02")) history = history.copy(basicHits = emptyList())
            if (id == "AT9_WARRIOR_A02") history = history.copy(ownHits = emptyList())
            if (id == "AT9_WARRIOR_C04") history = history.copy(takenHits = emptyList())
        }
        history = when (context.event) {
            ArenaGrowthEvent.OWN_HIT -> history.copy(
                ownHits = (history.ownHits + context).takeLast(2),
                basicHits = if (activated.any { it.traitId in setOf("AT9_ROGUE_B01", "AT9_MAGE_B02") }) emptyList()
                else if (context.isBasic && context.hpDamage > 0) (history.basicHits + context).takeLast(2) else history.basicHits,
            )
            ArenaGrowthEvent.OWN_MISS -> history.copy(ownHits = emptyList(), lastMissTurn = context.turn)
            ArenaGrowthEvent.TAKEN_HIT -> history.copy(takenHits = (history.takenHits + context).takeLast(2))
            ArenaGrowthEvent.ENEMY_MISS -> history.copy(takenHits = emptyList())
            ArenaGrowthEvent.HOT_HEALED -> if (context.healing > 0) history.copy(hotTicks =
                (history.hotTicks + (context.supportInstanceId to ((history.hotTicks[context.supportInstanceId] ?: 0) + 1)))
                    .entries.toList().takeLast(16).associate { it.key to it.value }) else history
            else -> history
        }
        return activated
    }

    fun modifiers(context: ArenaGrowthContext, phase: ArenaGrowthPhase): List<ArenaGrowthModifier> {
        if (context.selfHpRatio <= 0.0) return emptyList()
        return values.mapNotNull { (id, value) ->
            val rule = ArenaGrowthRules.rules.getValue(id)
            if (rule.phase != phase || !matches(rule, context) ||
                (rule.event == ArenaGrowthEvent.NONE && context.turn < (readyAt[id] ?: 1))) return@mapNotNull null
            val token = tokens[id]
            val eligible = if (rule.event == ArenaGrowthEvent.NONE) rule.predicate(context, history)
                else token != null && context.turn >= token.availableFrom && context.turn <= token.activation.expiresAtTurn &&
                    (token.castId == null || token.castId == context.castId) &&
                    (id == "AT9_ROGUE_B02" || context.castStartedTurn == null || context.castStartedTurn >= token.availableFrom)
            if (!eligible) return@mapNotNull null
            ArenaGrowthModifier(id, value, requireNotNull(ArenaProgressionCatalog.find(id)).effectUnit,
                phase, rule.target, token?.activation?.causeSequence ?: 0, token?.activation?.expiresAtTurn, context.turn)
        }
    }

    fun consume(modifiers: List<ArenaGrowthModifier>) {
        modifiers.forEach {
            tokens.remove(it.traitId)
            val rule = ArenaGrowthRules.rules.getValue(it.traitId)
            val definition = requireNotNull(ArenaProgressionCatalog.find(it.traitId))
            if (rule.event == ArenaGrowthEvent.NONE && definition.cooldownTurns > 0)
                readyAt[it.traitId] = it.evaluatedTurn + definition.cooldownTurns + 1
        }
    }

    fun expire(turn: Int): List<String> {
        val expired = tokens.filterValues { it.activation.expiresAtTurn < turn }.keys.toList()
        expired.forEach(tokens::remove)
        return expired
    }

    private fun matches(rule: ArenaGrowthRule, c: ArenaGrowthContext): Boolean = when (rule.target) {
        ArenaGrowthTarget.ANY_ATTACK -> c.attack
        ArenaGrowthTarget.BASIC, ArenaGrowthTarget.INCOMING_BASIC -> c.isBasic
        ArenaGrowthTarget.SKILL, ArenaGrowthTarget.INCOMING_SKILL -> c.skill
        ArenaGrowthTarget.ONE_TURN_ATTACK -> c.attack && c.originalCastTurns == 1
        ArenaGrowthTarget.ONE_TURN_SKILL -> c.skill && c.originalCastTurns == 1
        ArenaGrowthTarget.TWO_TURN_SKILL -> c.skill && c.originalCastTurns == 2
        ArenaGrowthTarget.LONG_SKILL -> c.skill && c.originalCastTurns in 2..3
        ArenaGrowthTarget.ANY_SUPPORT -> c.isSupport
        ArenaGrowthTarget.LINKED_SUPPORT -> c.isSupport && c.actionId == rule.supportTarget
        ArenaGrowthTarget.DOT -> c.sourceSupportId == rule.supportTarget
        ArenaGrowthTarget.HEAL, ArenaGrowthTarget.SHIELD, ArenaGrowthTarget.SUPPORT_POWER ->
            c.actionId == rule.supportTarget || c.sourceSupportId == rule.supportTarget || c.consumed(rule.supportTarget ?: "")
    }

    companion object {
        val legacyTraitIds = setOf("WARRIOR", "ROGUE", "RANGER", "MAGE", "CLERIC", "PALADIN")
            .flatMap { listOf("AT9_${it}_A01", "AT9_${it}_A02") }.toSet()
        val implementedTraitIds: Set<String> get() = ArenaGrowthRules.rules.keys
    }
}
