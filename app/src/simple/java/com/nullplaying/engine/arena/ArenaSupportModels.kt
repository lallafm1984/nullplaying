package com.nullplaying.engine.arena

import com.nullplaying.model.HeroClass
import kotlinx.serialization.Serializable

const val ARENA_SUPPORT_RULES_VERSION = "arena-support-full-v6"

/** Immutable exact ownership and allocation snapshot; supports are never granted by simulation. */
@Serializable
data class ArenaSupportInput(
    val fighter: ArenaFighterInput,
    val supportIds: Set<String> = emptySet(),
    val traits: List<ArenaSupportTraitRank> = emptyList(),
    /** Serialized compatibility name: current battles store the hero-level point budget here. */
    val arenaLevel: Int = 1,
    /** New Arena battles opt in; false preserves non-Arena callers and historical saved replays. */
    val arenaClassBalanceEnabled: Boolean = false,
    /** Empty only for retained V2 contracts. Ranked contracts own a support at rank 1..10. */
    val supportRanks: Map<String, Int> = emptyMap(),
    /**
     * Exact execution values captured when a battle is saved. An empty map keeps compatibility
     * inputs readable; authoritative ranked saves freeze this map before persistence.
     */
    val resolvedSupports: Map<String, ArenaResolvedSupport> = emptyMap(),
    /** Present only for newly issued stat-identity battles. Null preserves historical V6. */
    val identity: ArenaIdentitySnapshot? = null,
)

/**
 * Serializable support execution payload. Copy and presentation strings intentionally stay in
 * the catalog; every value that can change simulation output is frozen here.
 */
@Serializable
data class ArenaResolvedSupport(
    val schemaVersion: Int = 1,
    val id: String,
    val heroClass: HeroClass,
    val rank: Int = 0,
    val kind: ArenaSupportKind,
    val mp: Int,
    val castTurns: Int,
    val durationTurns: Int,
    val cooldownTurns: Int,
    val charges: Int,
    val magnitude: Double,
    val secondary: Double,
    val threshold: Double,
    val oncePerBattle: Boolean,
    val conditionKey: String,
) {
    init {
        require(schemaVersion == 1)
        require(id.isNotBlank() && rank in 0..10)
        require(mp in 0..100 && castTurns in 1..100)
        require(durationTurns in 0..100 && cooldownTurns in 0..100 && charges in 0..100)
        require(listOf(magnitude, secondary, threshold).all { it.isFinite() && it >= 0.0 })
        require(conditionKey.isNotBlank())
    }

    internal val cast get() = castTurns
    internal val duration get() = durationTurns
    internal val cooldown get() = cooldownTurns
}

internal fun ArenaSupportDefinition.resolved(rank: Int = 0): ArenaResolvedSupport =
    ArenaResolvedSupport(
        id = id,
        heroClass = heroClass,
        rank = rank,
        kind = kind,
        mp = mp,
        castTurns = castTurns,
        durationTurns = durationTurns,
        cooldownTurns = cooldownTurns,
        charges = charges,
        magnitude = magnitude,
        secondary = secondary,
        threshold = threshold,
        oncePerBattle = oncePerBattle,
        conditionKey = conditionKey,
    )

/** Freeze current values once; an already-frozen historical payload is never re-resolved. */
internal fun ArenaSupportInput.freezeResolvedSupports(): ArenaSupportInput {
    if (resolvedSupports.isNotEmpty() || supportIds.isEmpty()) return this
    val values = supportIds.associateWith { id ->
        val base = requireNotNull(ArenaSupportCatalog.find(id)) { "Unknown support: $id" }
        val rank = supportRanks[id] ?: 0
        val effective = if (rank == 0) {
            // Legacy transform() only replaced payment/cast timing. Core magnitudes, charges and
            // durations are applied later by their executable branches, so freezing the whole
            // preview definition here would apply those replacements twice on replay.
            val transformed = ArenaSupportCatalog.effectiveDefinition(id, traits) ?: base
            base.copy(mp = transformed.mp, castTurns = transformed.castTurns)
        } else {
            ArenaSkillTreeCatalog.effectiveSupport(base, rank)
        }
        effective.resolved(rank)
    }
    return copy(resolvedSupports = values)
}

@Serializable
data class ArenaSupportTraitRank(
    val id: String,
    val rank: Int = 1,
    val enhancement: Int = 0,
)

data class ArenaSupportFighterResult(
    val hp: Double,
    val maxHp: Double,
    /** Exact ledger: 1 MP = 1,000 units. UI decimal formatting must not alter settlement. */
    val mpUnits: Int,
    val maxMpUnits: Int,
    val shield: Double,
)

enum class ArenaSupportEventType {
    START, CAST_START, CAST_PROGRESS, ATTACK_MISS, ATTACK_EVADED, ATTACK_HIT,
    SUPPORT_APPLIED, SUPPORT_TRIGGERED, HEAL_APPLIED, SHIELD_ABSORBED, DAMAGE_REDUCED,
    DOT_DAMAGE, MP_DRAINED, STATUS_APPLIED, STATUS_REMOVED, STATUS_BLOCKED,
    CONTROL_APPLIED, CONTROL_RESISTED, CAST_DELAYED, CAST_PAUSED, AWAKENED,
    TRAIT_TRIGGERED, EFFECT_EXPIRED, KO, CAST_CANCELLED_KO, END, SAFETY_ABORT,
}

data class ArenaSupportEvent(
    val sequence: Int,
    val turn: Int,
    val type: ArenaSupportEventType,
    val actorId: String? = null,
    val targetId: String? = null,
    val actionId: String? = null,
    val traitId: String? = null,
    val hpBefore: Double? = null,
    val hpAfter: Double? = null,
    val mpBeforeUnits: Int? = null,
    val mpAfterUnits: Int? = null,
    /** HP/absorption amount, except CAST_START where this is the paid MP display value. */
    val amount: Double = 0.0,
    val shieldAfter: Double? = null,
    val castTurns: Int = 0,
    val remainingTurns: Int = 0,
    val castId: Int? = null,
    val causeSequence: Int? = null,
    val shieldBefore: Double? = null,
    val effectExpiresAtTurn: Int? = null,
    /** Effective percentage or percentage-point value, never another damage application. */
    val traitValue: Double? = null,
    val reason: String? = null,
)

data class ArenaSupportResult(
    val status: ArenaRunStatus,
    val winnerId: String?,
    val turns: Int,
    val fighters: Map<String, ArenaSupportFighterResult>,
    val events: List<ArenaSupportEvent>,
    val rulesVersion: String = ARENA_SUPPORT_RULES_VERSION,
    val seed: Long = 0L,
    val appliedRules: ArenaTurnRules = ArenaTurnRules(),
)
