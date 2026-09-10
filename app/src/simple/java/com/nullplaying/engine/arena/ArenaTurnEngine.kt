package com.nullplaying.engine.arena

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Deterministic, isolated direct-attack baseline. The app does not route battles here yet.
 * Each result is a complete event plan: presentation never calculates damage or picks a winner.
 */
object ArenaTurnEngine {
    private data class Attack(val id: String, val castTurns: Int, val mp: Int, val units: Double)
    private data class Casting(val id: Int, val attack: Attack, var remaining: Int)

    private class Fighter(val input: ArenaFighterInput, val rules: ArenaTurnRules) {
        private val stats = input.stats
        private val values = stats.values().take(6)
        private val formula = rules.formula
        private val primary = values[input.heroClass.primaryStatIndex]
        private val secondary = values[input.heroClass.secondaryStatIndex]
        private val classOffense = primary * .7 + secondary * .3
        // Symmetric pools avoid favoring one class's secondary stat before support mechanics exist.
        private val offensivePool = mean3(stats.strength, stats.dexterity, stats.intelligence)
        private val survivalPool = mean3(stats.constitution, stats.wisdom, stats.charisma)
        private val healthAttribute = values.sumOf { it / 6.0 } * (1 - formula.healthSurvivalShare) +
            survivalPool * formula.healthSurvivalShare
        private val attackAttribute = classOffense * (1 - formula.attackOffenseShare) +
            offensivePool * formula.attackOffenseShare
        val maxHp = formula.healthBase + formula.healthScale * sqrt(healthAttribute)
        val attackPower = formula.attackBase + formula.attackScale * sqrt(attackAttribute)
        val defense = 1 + formula.defenseScale * sqrt(survivalPool)
        init {
            require(maxHp.isFinite() && attackPower.isFinite() && defense.isFinite()) {
                "Stat/formula combination exceeds the finite combat range"
            }
        }
        val randomKey = hash(input.id)
        val maxMp = when (rules.mpMode) {
            ArenaMpMode.FIXED_100 -> 100
            ArenaMpMode.GROWTH_LOG -> max(100, (70 + 15 * ln(1 + stats.rawMaxMana / 20) / ln(2.0)).roundToInt())
        }
        val attacks = listOf(Attack("BASIC_ATTACK", 1, 0, 1.0)) +
            input.attacks.sortedBy { it.id }.map { skill ->
                val cast = 1 + (skill.tier - 1) % 3
                val base = when (rules.budget) {
                    ArenaAttackBudget.C0 -> cast.toDouble()
                    ArenaAttackBudget.C1 -> when (cast) { 1 -> 1.25; 2 -> 2.60; else -> 4.05 }
                }
                val mp = when (rules.budget) {
                    ArenaAttackBudget.C0 -> cast * 8
                    ArenaAttackBudget.C1 -> when (cast) { 1 -> 6; 2 -> 8; else -> 12 }
                }
                val growth = 1 + (if (rules.tierScaling) rules.tierGain * (skill.tier - 1) else 0.0) +
                    (if (rules.masteryScaling) rules.masteryGain * skill.masteryBonusPercent / 100 else 0.0)
                Attack(skill.id, cast, mp, base * growth)
            }
        var hp = maxHp
        var mp = maxMp
        var casting: Casting? = null
        var nextCastId = 1
        val readyAt = HashMap<String, Int>()
        var basics = 0
        var skills = 0
        var misses = 0
        var mpSpent = 0
        var damageDealt = 0.0
        var cancelled = 0
        val starts = IntArray(3)
        val completions = IntArray(3)
        var firstMpDepletedTurn: Int? = null

        fun snapshot() = ArenaFighterResult(hp, mp, maxHp, maxMp, ArenaFighterTelemetry(
            basics, skills, misses, mpSpent, damageDealt, cancelled,
            starts.toList(), completions.toList(), firstMpDepletedTurn,
        ))
    }

    fun simulate(
        left: ArenaFighterInput,
        right: ArenaFighterInput,
        seed: Long,
        rules: ArenaTurnRules = ArenaTurnRules(),
        recordEvents: Boolean = true,
    ): ArenaTurnResult {
        require(left.id != right.id) { "Distinct, stable fighter IDs are required" }
        // Canonical semantic IDs, never LEFT/RIGHT, determine tied ordering and random streams.
        val fighters = listOf(Fighter(left, rules), Fighter(right, rules)).sortedBy { it.input.id }
        val initiativeKey = hash(fighters.joinToString("") { "${it.input.id.length}:${it.input.id}" })
        val events = if (recordEvents) ArrayList<ArenaTurnEvent>() else null
        var sequence = 0
        fun emit(
            type: ArenaEventType, turn: Int, actor: Fighter? = null, target: Fighter? = null,
            cast: Casting? = null, hpBefore: Double? = null, hpAfter: Double? = null,
            mpBefore: Int? = null, mpAfter: Int? = null, amount: Double = 0.0,
        ) {
            if (events != null) events += ArenaTurnEvent(
                type, turn, sequence, actor?.input?.id, target?.input?.id,
                cast?.attack?.id, cast?.id, hpBefore, hpAfter, mpBefore, mpAfter, amount,
                cast?.attack?.castTurns ?: 0, cast?.remaining ?: 0,
            )
            sequence++
        }
        fun result(status: ArenaRunStatus, winner: Fighter?, turn: Int) = ArenaTurnResult(
            status, winner?.input?.id, turn,
            fighters.associate { it.input.id to it.snapshot() }, events?.toList() ?: emptyList(),
            seed = seed, appliedRules = rules,
        )

        fighters.forEach { emit(ArenaEventType.BATTLE_START, 0, it,
            hpBefore = it.hp, hpAfter = it.hp, mpBefore = it.mp, mpAfter = it.mp) }

        for (turn in 1..rules.safetyTurnLimit) {
            // Capture BOTH choices before either pays/starts. No decision can see a simultaneous choice.
            val choices = fighters.mapIndexed { index, fighter ->
                if (fighter.casting == null) choose(fighter, fighters[1 - index], turn, rules) else null
            }
            fighters.forEachIndexed { index, fighter ->
                val attack = choices[index] ?: return@forEachIndexed
                val before = fighter.mp
                check(attack.mp <= before)
                fighter.mp -= attack.mp
                fighter.mpSpent += attack.mp
                if (fighter.mp == 0 && fighter.firstMpDepletedTurn == null) fighter.firstMpDepletedTurn = turn
                val casting = Casting(fighter.nextCastId++, attack, attack.castTurns)
                fighter.casting = casting
                fighter.starts[attack.castTurns - 1]++
                emit(ArenaEventType.CAST_START, turn, fighter, fighters[1 - index], casting,
                    mpBefore = before, mpAfter = fighter.mp, amount = attack.mp.toDouble())
            }
            fighters.forEach { fighter ->
                val casting = checkNotNull(fighter.casting)
                casting.remaining--
                emit(ArenaEventType.CAST_PROGRESS, turn, fighter, cast = casting)
            }

            val ready = fighters.filter { it.casting?.remaining == 0 }
            val ordered = if (ready.size == 2) {
                val a = fighters[0]
                val b = fighters[1]
                val scale = maxOf(1.0, a.input.stats.dexterity, b.input.stats.dexterity)
                val aDex = a.input.stats.dexterity / scale
                val bDex = b.input.stats.dexterity / scale
                val denominator = max(1.0 / scale, aDex + bDex)
                val edge = (rules.initiativeSensitivity * (aDex - bDex) /
                    denominator).coerceIn(-rules.initiativeMaxEdge, rules.initiativeMaxEdge)
                if (random(seed, initiativeKey, turn, 0, 1) < .5 + edge) ready else ready.reversed()
            } else ready

            for (fighter in ordered) {
                check(fighter.hp > 0.0)
                val target = fighters.first { it !== fighter }
                val casting = checkNotNull(fighter.casting)
                val attack = casting.attack
                fighter.casting = null
                fighter.completions[attack.castTurns - 1]++
                if (attack.id == "BASIC_ATTACK") fighter.basics++ else {
                    fighter.skills++
                    fighter.readyAt[attack.id] = turn + rules.cooldownTurns + 1
                }
                val hit = random(seed, fighter.randomKey, turn, casting.id, 2) < rules.hitChance
                if (!hit) {
                    fighter.misses++
                    emit(ArenaEventType.ATTACK_MISS, turn, fighter, target, casting,
                        hpBefore = target.hp, hpAfter = target.hp, mpBefore = fighter.mp, mpAfter = fighter.mp)
                    continue
                }
                val variance = 1 + (random(seed, fighter.randomKey, turn, casting.id, 3) * 2 - 1) * rules.damageVariance
                val rawDamage = fighter.attackPower * attack.units * variance / target.defense
                val before = target.hp
                val requestedDamage = min(before, rawDamage)
                // This subtracts the applied damage. No HP=1 rescue, future finisher, or recoil.
                target.hp = max(0.0, before - requestedDamage)
                // At extreme valid stats, a sub-ULP hit can round away. Report actual HP loss only.
                val damage = before - target.hp
                fighter.damageDealt += damage
                emit(ArenaEventType.ATTACK_HIT, turn, fighter, target, casting,
                    hpBefore = before, hpAfter = target.hp, mpBefore = fighter.mp, mpAfter = fighter.mp, amount = damage)
                if (target.hp <= 0.0) {
                    emit(ArenaEventType.KO, turn, target, fighter, hpBefore = 0.0, hpAfter = 0.0)
                    target.casting?.let { pending ->
                        target.cancelled++
                        emit(ArenaEventType.CAST_CANCELLED_KO, turn, target, fighter, pending,
                            hpBefore = 0.0, hpAfter = 0.0, mpBefore = target.mp, mpAfter = target.mp)
                        target.casting = null
                    }
                    emit(ArenaEventType.BATTLE_END, turn, fighter, target,
                        hpBefore = target.hp, hpAfter = target.hp)
                    return result(ArenaRunStatus.COMPLETED, fighter, turn)
                }
            }
        }
        // An operational safeguard is NOT a maximum round rule or an in-game draw/loss.
        emit(ArenaEventType.SAFETY_ABORT, rules.safetyTurnLimit)
        return result(ArenaRunStatus.ABORTED_SAFETY_LIMIT, null, rules.safetyTurnLimit)
    }

    private fun choose(fighter: Fighter, target: Fighter, turn: Int, rules: ArenaTurnRules): Attack {
        val basicExpected = fighter.attackPower / target.defense * rules.hitChance
        var best = fighter.attacks[0]
        var bestScore = Double.NEGATIVE_INFINITY
        for (attack in fighter.attacks) {
            if (attack.mp > fighter.mp || turn < (fighter.readyAt[attack.id] ?: 0)) continue
            val damage = fighter.attackPower * attack.units / target.defense
            val effective = min(damage, target.hp) * rules.hitChance
            var score = effective / (attack.castTurns * (1 + .015 * (attack.castTurns - 1)))
            // Resource scarcity is observable; never inspect a future hit/initiative random roll.
            score -= .06 * basicExpected * attack.mp / max(1, fighter.mp)
            target.casting?.let { incoming ->
                val incomingDamage = target.attackPower * incoming.attack.units / fighter.defense
                if (attack.castTurns > 1 && incoming.remaining < attack.castTurns && incomingDamage >= fighter.hp) {
                    score *= .5
                }
            }
            if (score > bestScore + 1e-10 ||
                (kotlin.math.abs(score - bestScore) <= 1e-10 &&
                    (attack.mp < best.mp || (attack.mp == best.mp && attack.id < best.id)))) {
                best = attack
                bestScore = score
            }
        }
        return best
    }

    // Rounding three MAX_VALUE / 3 terms can overflow; an average cannot exceed its largest input.
    private fun mean3(a: Double, b: Double, c: Double) =
        (a / 3.0 + b / 3.0 + c / 3.0).coerceAtMost(maxOf(a, b, c))

    /** Small counter-based random stream. UTF-16 IDs are stable and never depend on names or UI. */
    private fun hash(key: String): Long {
        var hash = -3750763034362895579L
        key.forEach { hash = (hash xor it.code.toLong()) * 1099511628211L }
        return hash
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
