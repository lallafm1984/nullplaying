package com.nullplaying.engine.arena

import com.nullplaying.model.HeroClass
import kotlinx.serialization.Serializable

/** New arena core inputs. No legacy projection normalization, UI state, or service references. */
@Serializable
data class ArenaCoreStats(
    val strength: Double,
    val constitution: Double,
    val dexterity: Double,
    val intelligence: Double,
    val wisdom: Double,
    val charisma: Double,
    val rawMaxHealth: Double,
    val rawMaxMana: Double,
) {
    init { require(values().all { it.isFinite() && it >= 0.0 }) }
    fun values(): List<Double> = listOf(strength, constitution, dexterity, intelligence,
        wisdom, charisma, rawMaxHealth, rawMaxMana)
}

@Serializable
data class ArenaResolvedAttack(
    /** Arena-only rank captured when the match is issued. Adventure mastery is never consulted. */
    val rank: Int,
    /** Zero completes on the selected turn; one to three are full preparation turns. */
    val prepareTurns: Int,
    val mpCost: Int,
    val cooldownTurns: Int,
    /** Total authored damage coefficient for the whole action, before arena stat formulas. */
    val damagePercent: Int,
    /** Stable executable key. Numeric parameters below are frozen with the battle snapshot. */
    val effectKey: String = "NONE",
    val effectValues: Map<String, Double> = emptyMap(),
    /** Compatibility scalars for old provisional snapshots; new snapshots use [effectValues]. */
    val primaryValue: Double = 0.0,
    val secondaryValue: Double = 0.0,
    val thresholdPercent: Double = 0.0,
    val durationTurns: Int = 0,
    val maxApplications: Int = 0,
    val oncePerBattle: Boolean = false,
    val earliestTurn: Int = 1,
    /** Zero means the normal preparation-band safety cap is used. */
    val hpDamageCapPercent: Double = 0.0,
    /** Used only by the deliberate non-lethal capstone contract. */
    val targetHpFloor: Int = 0,
    val tags: Set<String> = emptySet(),
) {
    init {
        require(rank in 1..10)
        require(prepareTurns in 0..3)
        require(mpCost in 0..100 && cooldownTurns in 0..100)
        require(damagePercent in 0..5_000)
        require(effectKey.isNotBlank())
        require(effectValues.keys.all(String::isNotBlank) && effectValues.values.all { it.isFinite() })
        require(listOf(primaryValue, secondaryValue, thresholdPercent, hpDamageCapPercent)
            .all { it.isFinite() && it >= 0.0 })
        require(durationTurns in 0..100 && maxApplications in 0..100)
        require(earliestTurn in 1..10_000 && targetHpFloor in 0..1)
    }
}

@Serializable
data class ArenaAttackInput(
    val id: String,
    val name: String,
    val tier: Int,
    val masteryBonusPercent: Int,
    val sourceDamagePercentMin: Int,
    val sourceDamagePercentMax: Int,
    /** Null only for the retained V2 replay/test contract. New arena matches always capture it. */
    val arena: ArenaResolvedAttack? = null,
) {
    init {
        require(id.isNotBlank() && id != "BASIC_ATTACK")
        require(tier in 1..20 && masteryBonusPercent in 0..50)
        require(sourceDamagePercentMin >= 0 && sourceDamagePercentMax >= sourceDamagePercentMin)
    }
}

@Serializable
data class ArenaFighterInput(
    val id: String,
    val heroClass: HeroClass,
    val level: Long,
    val stats: ArenaCoreStats,
    val attacks: List<ArenaAttackInput>,
) {
    init {
        require(id.isNotBlank() && level >= 1)
        require(attacks.map { it.id }.distinct().size == attacks.size)
        require(attacks.all { (if (it.tier == 1) 1 else (it.tier - 1) * 5) <= level })
    }
}

data class ArenaInputBuildResult(val fighter: ArenaFighterInput, val rejectedSkills: List<String>)

enum class ArenaAttackBudget { C0, C1 }
enum class ArenaMpMode { FIXED_100, GROWTH_LOG }

/** Calibration coefficients, not a final six-class balance certification. All classes share them. */
@Serializable
data class ArenaStatFormula(
    val healthBase: Double = 180.0,
    val healthScale: Double = 70.0,
    val healthSurvivalShare: Double = 0.45,
    val attackBase: Double = 8.0,
    val attackScale: Double = 5.0,
    val attackOffenseShare: Double = 0.5,
    // Baseline survival is already in HP. Extra stat mitigation is an explicit lab control only.
    val defenseScale: Double = 0.0,
) {
    init {
        require(listOf(healthBase, healthScale, attackBase, attackScale, defenseScale)
            .all { it.isFinite() && it >= 0.0 })
        require(healthBase > 0 && attackBase > 0)
        require(healthSurvivalShare in 0.0..1.0 && attackOffenseShare in 0.0..1.0)
    }
}

@Serializable
data class ArenaTurnRules(
    val budget: ArenaAttackBudget = ArenaAttackBudget.C1,
    val mpMode: ArenaMpMode = ArenaMpMode.GROWTH_LOG,
    val tierScaling: Boolean = true,
    val masteryScaling: Boolean = true,
    val safetyTurnLimit: Int = 10_000,
    val formula: ArenaStatFormula = ArenaStatFormula(),
    val hitChance: Double = 0.95,
    val damageVariance: Double = 0.08,
    val tierGain: Double = 0.01,
    val masteryGain: Double = 0.20,
    val cooldownTurns: Int = 2,
    val initiativeSensitivity: Double = 0.20,
    val initiativeMaxEdge: Double = 0.10,
) {
    init {
        require(safetyTurnLimit in 1..100_000 && cooldownTurns in 0..1000)
        require(hitChance in 0.0..1.0 && damageVariance in 0.0..0.5)
        require(tierGain in 0.0..1.0 && masteryGain in 0.0..1.0)
        require(initiativeSensitivity in 0.0..1.0 && initiativeMaxEdge in 0.0..0.25)
    }
}

enum class ArenaRunStatus { COMPLETED, ABORTED_SAFETY_LIMIT }
enum class ArenaEventType {
    BATTLE_START, CAST_START, CAST_PROGRESS, ATTACK_HIT, ATTACK_MISS,
    CAST_CANCELLED_KO, KO, BATTLE_END, SAFETY_ABORT,
}

data class ArenaTurnEvent(
    val type: ArenaEventType,
    val turn: Int,
    val sequence: Int,
    val actorId: String? = null,
    val targetId: String? = null,
    val attackId: String? = null,
    val castId: Int? = null,
    val hpBefore: Double? = null,
    val hpAfter: Double? = null,
    val mpBefore: Int? = null,
    val mpAfter: Int? = null,
    val amount: Double = 0.0,
    val castTurns: Int = 0,
    val remainingTurns: Int = 0,
)

data class ArenaFighterTelemetry(
    val basicCompletions: Int,
    val skillCompletions: Int,
    val misses: Int,
    val mpSpent: Int,
    val damageDealt: Double,
    val koCancelledCasts: Int,
    val startsByCast: List<Int>,
    val completionsByCast: List<Int>,
    val firstMpDepletedTurn: Int?,
)

data class ArenaFighterResult(
    val hp: Double,
    val mp: Int,
    val maxHp: Double,
    val maxMp: Int,
    val telemetry: ArenaFighterTelemetry,
)

data class ArenaTurnResult(
    val status: ArenaRunStatus,
    val winnerId: String?,
    val turns: Int,
    val fighters: Map<String, ArenaFighterResult>,
    val events: List<ArenaTurnEvent>,
    val rulesVersion: String = "arena-turn-core-v1",
    val seed: Long = 0L,
    val appliedRules: ArenaTurnRules = ArenaTurnRules(),
)
