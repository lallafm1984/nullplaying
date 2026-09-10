package com.nullplaying.engine.arena

import com.nullplaying.model.HeroClass
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.ceil

data class ArenaSupportAuditReport(
    val json: String,
    val passed: Boolean,
    val executions: Int,
    val failures: List<String>,
)

/**
 * Device-callable, Android-free audit of the complete support engine.
 *
 * One source-growth trajectory per class is reused at each requested hero level.
 * Samples vary combat seeds, NOT the character-generation distribution. Position
 * mirrors and recordEvents=false replays are not independent outcome samples.
 * A mechanical pass is not a six-class balance or full 144-trait certification.
 */
object ArenaSupportAudit {
    private const val BASE_SEED = 9_050_900_000L
    private const val MAX_FAILURE_DETAILS = 160
    private const val EPSILON = 0.000001
    private val classes = listOf(HeroClass.WARRIOR, HeroClass.ROGUE, HeroClass.RANGER,
        HeroClass.MAGE, HeroClass.CLERIC, HeroClass.PALADIN)
    private val expectedSupports = ArenaSupportCatalog.values.map { it.id }.toSet()
    private data class SupportContract(val baseMp: Int, val cooldown: Int)
    // Catalog owns prices; the audit independently reconstructs payment and completion timing.
    private val supportContracts = ArenaSupportCatalog.values.associate {
        it.id to SupportContract(it.mp, it.cooldownTurns)
    }

    private enum class Cohort(
        val traitIndex: Int?,
        val rank: Int,
        val enhancement: Int,
        val supports: Boolean,
    ) {
        ATTACK_ONLY(null, 0, 0, false),
        SUPPORT_ONLY(null, 0, 0, true),
        A01_TRAIT_R1(0, 1, 0, true),
        A01_TRAIT_R5_E3(0, 5, 3, true),
        A02_TRAIT_R1(1, 1, 0, true),
        A02_TRAIT_R5_E3(1, 5, 3, true),
        CONCENTRATED(null, 0, 0, true),
        MIXED(null, 0, 0, true),
        COUNTER(null, 0, 0, true),
    }

    private class Problems {
        var count = 0
        val details = mutableListOf<String>()
        fun check(ok: Boolean, label: String, reason: String) {
            if (!ok) add("$label: $reason")
        }
        fun add(message: String) {
            count++
            if (details.size < MAX_FAILURE_DETAILS) details += message
        }
    }

    private class PairTally(val cohort: String, val level: Int, val row: String, val col: String) {
        var executions = 0
        var completed = 0
        var rowWins = 0
        var draws = 0
        var aborted = 0
        val turns = mutableListOf<Int>()
        fun record(result: ArenaSupportResult, rowId: String) {
            executions++
            if (result.status != ArenaRunStatus.COMPLETED) { aborted++; return }
            completed++
            if (result.winnerId == rowId) rowWins++
            if (result.winnerId == null) draws++
            turns += result.turns
        }
        fun score(): Double? = if (completed == 0) null else (rowWins + draws * 0.5) / completed
        fun json(): JsonObject = buildJsonObject {
            put("cohort", cohort); put("heroLevel", level); put("row", row); put("col", col)
            put("executions", executions); put("completed", completed); put("aborted", aborted)
            put("rowWins", rowWins); put("draws", draws); put("rowLosses", completed - rowWins - draws)
            put("rowScoreRate", score()?.let(::JsonPrimitive) ?: JsonNull)
            put("p50Turns", quantile(turns, 0.50)); put("p90Turns", quantile(turns, 0.90))
            put("p99Turns", quantile(turns, 0.99))
        }
    }

    private class Coverage {
        val supports = expectedSupports.associateWith { 0 }.toMutableMap()
        val traits = classes.flatMap { heroClass ->
            ArenaProgressionCatalog.forClass(heroClass).map { it.id to 0 }
        }.toMap().toMutableMap()
        val traitReasons = mutableMapOf<String, Int>()
        val discountedCastStarts = traits.keys.associateWith { 0 }.toMutableMap()
        val eventCounts = ArenaSupportEventType.entries.associate { it.name to 0 }.toMutableMap()
        var hpDamage = 0.0
        var actualHealing = 0.0
        var shieldAbsorption = 0.0
        var paidMpUnits = 0L
        fun observe(event: ArenaSupportEvent) {
            eventCounts[event.type.name] = (eventCounts[event.type.name] ?: 0) + 1
            if (event.type == ArenaSupportEventType.SUPPORT_APPLIED && event.actionId != null) {
                supports[event.actionId] = (supports[event.actionId] ?: 0) + 1
            }
            if (event.type == ArenaSupportEventType.TRAIT_TRIGGERED && event.traitId != null) {
                traits[event.traitId] = (traits[event.traitId] ?: 0) + 1
                val key = "${event.traitId}:${event.reason ?: "unspecified"}"
                traitReasons[key] = (traitReasons[key] ?: 0) + 1
            }
            if (event.type == ArenaSupportEventType.CAST_START && event.traitId != null &&
                event.traitValue?.let { it > 0 && it.isFinite() } == true) {
                discountedCastStarts[event.traitId] = (discountedCastStarts[event.traitId] ?: 0) + 1
            }
            // Invalid amounts are reported by the ledger checks, never serialized as NaN/Infinity.
            if (!event.amount.isFinite() || event.amount < 0) return
            when (event.type) {
                ArenaSupportEventType.ATTACK_HIT, ArenaSupportEventType.DOT_DAMAGE -> hpDamage += event.amount
                ArenaSupportEventType.HEAL_APPLIED -> actualHealing += event.amount
                ArenaSupportEventType.SHIELD_ABSORBED -> shieldAbsorption += event.amount
                ArenaSupportEventType.CAST_START -> if (event.mpBeforeUnits != null && event.mpAfterUnits != null) {
                    paidMpUnits += (event.mpBeforeUnits - event.mpAfterUnits).toLong()
                }
                else -> Unit
            }
        }
    }

    /** Nine cohorts, 21 class pairs, with position mirrors and recording-parity replays. */
    fun run(
        samplesPerPair: Int = 20,
        levels: List<Int> = (10..100 step 10).toList(),
    ): ArenaSupportAuditReport {
        val started = System.nanoTime()
        val problems = Problems()
        if (samplesPerPair !in 1..200 || levels.isEmpty() || levels.any { it !in 10..100 } ||
            levels.distinct().size != levels.size) {
            problems.add("Invalid audit input: samples must be 1..200 and levels distinct within 10..100")
            return report(samplesPerPair, levels, 0, 0, 0, emptyList(), Coverage(), problems, started)
        }
        val rules = ArenaTurnRules()
        var executions = 0
        var recordedExecutions = 0
        var parityExecutions = 0
        val rows = mutableListOf<PairTally>()
        val coverage = Coverage()
        for (level in levels) {
            // Fresh immutable inputs are released at each level; ID does not influence fixture growth.
            val templates = try {
                classes.associateWith { heroClass -> ArenaSupportQaFixtures.fighter(
                    heroClass = heroClass, heroLevel = level, id = "audit_${heroClass.name}_A",
                    traitRank = 0, enhancement = 0, arenaLevel = 60,
                ).copy(supportIds = ArenaSupportCatalog.unlockedIds(heroClass, level.toLong())) }
            } catch (error: Exception) {
                problems.add("Fixture level $level: ${error.javaClass.simpleName}: ${error.message}")
                continue
            }
            problems.check(templates.values.all { it.supportIds == ArenaSupportCatalog.unlockedIds(it.fighter.heroClass, level.toLong()) },
                "Fixture level $level", "support ownership does not match current hero-level unlocks")
            for (cohort in Cohort.entries) {
                var pairIndex = 0
                for (i in classes.indices) for (j in i until classes.size) {
                    val rowClass = classes[i]
                    val colClass = classes[j]
                    val left = forCohort(templates.getValue(rowClass), cohort, "audit_${rowClass.name}_A")
                    val right = forCohort(templates.getValue(colClass), cohort, "audit_${colClass.name}_B")
                    val tally = PairTally(cohort.name, level, rowClass.name, colClass.name)
                    rows += tally
                    for (sample in 0 until samplesPerPair) {
                        val seed = BASE_SEED + level * 1_000_000L + pairIndex * 10_000L + sample
                        val label = "${cohort.name}/L$level/${rowClass.name}-${colClass.name}/seed=$seed"
                        try {
                            executions++; recordedExecutions++
                            val forward = ArenaSupportTurnEngine.simulate(left, right, seed, rules, recordEvents = true)
                            auditBattle(forward, left, right, "$label/forward", problems, coverage)
                            tally.record(forward, left.fighter.id)

                            executions++; recordedExecutions++
                            val mirror = ArenaSupportTurnEngine.simulate(right, left, seed, rules, recordEvents = true)
                            auditBattle(mirror, right, left, "$label/mirror", problems, coverage)
                            tally.record(mirror, left.fighter.id)
                            problems.check(sameOutcome(forward, mirror), label,
                                "position-only mirror changed terminal state, winner, or turn count")

                            executions++; parityExecutions++
                            val unrecorded = ArenaSupportTurnEngine.simulate(left, right, seed, rules, recordEvents = false)
                            problems.check(unrecorded.events.isEmpty(), label, "recordEvents=false emitted events")
                            problems.check(sameOutcome(forward, unrecorded), label,
                                "recordEvents flag changed the simulation result")
                        } catch (error: Exception) {
                            problems.add("$label: ${error.javaClass.simpleName}: ${error.message}")
                        }
                    }
                    pairIndex++
                }
            }
        }
        return report(samplesPerPair, levels, executions, recordedExecutions, parityExecutions,
            rows, coverage, problems, started)
    }

    private fun forCohort(template: ArenaSupportInput, cohort: Cohort, id: String): ArenaSupportInput {
        val traits = if (cohort in setOf(Cohort.CONCENTRATED, Cohort.MIXED, Cohort.COUNTER)) {
            var growth = ArenaProgressionState(unlocked = true, totalXp = ArenaProgressionRules.xpForLevel(60))
            val preferred = if (cohort == Cohort.COUNTER) "B" else "A"
            val eligible = ArenaProgressionCatalog.forClass(template.fighter.heroClass).filter {
                it.minHeroLevel <= template.fighter.level && it.requiredSupportIds.all(template.supportIds::contains)
            }
            val ordered = eligible.filterNot { it.isCore }.sortedWith(
                if (cohort == Cohort.MIXED) compareBy({ it.id.takeLast(2) }, { it.branch })
                else compareBy({ if (it.branch == preferred) 0 else 1 }, { it.id }))
            val core = eligible.firstOrNull { it.isCore && it.branch == preferred }
            if (core != null && cohort != Cohort.MIXED) growth = ArenaProgressionRules.allocate(growth,
                template.fighter.heroClass, template.fighter.level, template.supportIds, core.id, 1, 0).state
            for (node in ordered) {
                val available = ArenaProgressionRules.view(growth, 0).baseAvailable
                if (available == 0) break
                val rank = minOf(5, available)
                growth = ArenaProgressionRules.allocate(growth, template.fighter.heroClass,
                    template.fighter.level, template.supportIds, node.id, rank, 0).state
            }
            val enhanced = growth.allocations.firstOrNull { ArenaProgressionCatalog.find(it.id)?.isCore == false }
            if (enhanced != null) growth = ArenaProgressionRules.allocate(growth, template.fighter.heroClass,
                template.fighter.level, template.supportIds, enhanced.id, enhanced.rank, 3).state
            ArenaProgressionRules.toSupportTraits(growth)
        } else cohort.traitIndex?.let { index -> listOf(ArenaSupportTraitRank(
            ArenaProgressionCatalog.forClass(template.fighter.heroClass)[index].id, cohort.rank, cohort.enhancement)) }.orEmpty()
        return template.copy(fighter = template.fighter.copy(id = id),
            supportIds = if (cohort.supports) template.supportIds else emptySet(), traits = traits, arenaLevel = 60)
    }

    private fun sameOutcome(a: ArenaSupportResult, b: ArenaSupportResult): Boolean =
        a.copy(events = emptyList()) == b.copy(events = emptyList())

    private data class Ledger(var hp: Double, var mp: Int, var shield: Double = 0.0,
        var lostHp: Double = 0.0, var healedHp: Double = 0.0, var paidMp: Long = 0, var drainedMp: Long = 0)
    private data class Cast(val start: Int, val duration: Int, val actionId: String?,
        var completed: Boolean = false, var cancelled: Boolean = false, var delayedTurns: Int = 0)

    private fun contractBaseMp(input: ArenaSupportInput?, actionId: String?, rules: ArenaTurnRules): Int? {
        if (actionId == "BASIC_ATTACK") return 0
        supportContracts[actionId]?.let { contract ->
            val core = input?.traits?.firstOrNull { ArenaProgressionCatalog.find(it.id)?.isCore == true }?.id
            return when {
                core == "AT9_RANGER_B_CORE" && actionId == "ARENA_SUP_RANGER_02" -> 35
                core == "AT9_RANGER_C_CORE" && actionId == "ARENA_SUP_RANGER_10" -> 25
                core == "AT9_CLERIC_C_CORE" && actionId == "ARENA_SUP_CLERIC_03" -> 25
                else -> contract.baseMp
            }
        }
        val attack = input?.fighter?.attacks?.firstOrNull { it.id == actionId } ?: return null
        val originalCastTurns = 1 + (attack.tier - 1) % 3
        return when (rules.budget) {
            ArenaAttackBudget.C0 -> originalCastTurns * 8
            ArenaAttackBudget.C1 -> when (originalCastTurns) { 1 -> 6; 2 -> 8; else -> 12 }
        }
    }

    private fun auditBattle(
        result: ArenaSupportResult,
        left: ArenaSupportInput,
        right: ArenaSupportInput,
        label: String,
        problems: Problems,
        coverage: Coverage,
    ) {
        val inputs = listOf(left, right).associateBy { it.fighter.id }
        problems.check(result.fighters.keys == inputs.keys, label, "result fighter IDs differ from inputs")
        if (result.fighters.keys != inputs.keys) return
        val ledgers = result.fighters.mapValues { (_, fighter) -> Ledger(fighter.maxHp, fighter.maxMpUnits) }
        val starts = mutableSetOf<String>()
        val dead = mutableSetOf<String>()
        val casts = mutableMapOf<Pair<String, Int>, Cast>()
        val activeCasts = mutableMapOf<String, Int>()
        val actionReadyAt = mutableMapOf<Pair<String, String>, Int>()
        val healCasts = mutableSetOf<Pair<String, Int>>()
        val seenSequences = mutableSetOf<Int>()
        var lastSequence = -1
        var lastTurn = -1
        var ended = false
        var endCount = 0
        var koCount = 0
        for ((id, fighter) in result.fighters) {
            problems.check(fighter.maxHp.isFinite() && fighter.maxHp > 0 && fighter.hp.isFinite() &&
                fighter.hp >= 0 && fighter.hp <= fighter.maxHp + EPSILON, label, "invalid final HP for $id")
            problems.check(fighter.maxMpUnits > 0 && fighter.mpUnits in 0..fighter.maxMpUnits,
                label, "invalid final MP for $id")
            problems.check(fighter.shield.isFinite() && fighter.shield >= 0, label, "invalid final shield for $id")
        }
        problems.check(result.events.isNotEmpty(), label, "recorded battle has no events")
        for (event in result.events) {
            val at = "$label/event=${event.sequence}:${event.type}"
            coverage.observe(event)
            problems.check(!ended, at, "event emitted after terminal END/SAFETY_ABORT")
            problems.check(event.sequence > lastSequence && seenSequences.add(event.sequence), at, "event order/duplicate sequence")
            problems.check(event.turn >= lastTurn && event.turn >= 0 && event.turn <= result.turns, at, "invalid global turn")
            lastSequence = event.sequence; lastTurn = event.turn
            problems.check(event.amount.isFinite() && event.amount >= 0, at, "invalid event amount")
            event.actorId?.let { problems.check(it in inputs, at, "unknown actor $it") }
            event.targetId?.let { problems.check(it in inputs, at, "unknown target $it") }
            event.causeSequence?.let { problems.check(it < event.sequence && it in seenSequences, at, "missing/future cause event") }

            val ownerId = when (event.type) {
                ArenaSupportEventType.ATTACK_HIT, ArenaSupportEventType.ATTACK_MISS, ArenaSupportEventType.DOT_DAMAGE -> event.targetId
                else -> event.actorId
            }
            val ledger = ledgers[ownerId]
            val ownerResult = result.fighters[ownerId]
            if (event.hpBefore != null || event.hpAfter != null) {
                problems.check(ledger != null && ownerResult != null && event.hpBefore != null && event.hpAfter != null,
                    at, "HP fields lack owner/before/after")
                if (ledger != null && ownerResult != null && event.hpBefore != null && event.hpAfter != null) {
                    val before = event.hpBefore
                    val after = event.hpAfter
                    problems.check(before.isFinite() && after.isFinite() && after >= 0 && after <= ownerResult.maxHp + EPSILON,
                        at, "invalid event HP bounds")
                    problems.check(close(ledger.hp, before), at, "HP chain: ledger=${ledger.hp}, before=$before")
                    when (event.type) {
                        ArenaSupportEventType.ATTACK_HIT, ArenaSupportEventType.DOT_DAMAGE -> {
                            problems.check(after <= before + EPSILON && close(event.amount, before - after), at,
                                "attack amount is not the actual HP decrement")
                            ledger.lostHp += before - after
                        }
                        ArenaSupportEventType.HEAL_APPLIED -> {
                            problems.check(before > 0 && after >= before && close(event.amount, after - before), at,
                                "healing revived KO or differs from actual capped HP restoration")
                            ledger.healedHp += after - before
                        }
                        else -> problems.check(close(before, after), at, "non-HP event changed HP")
                    }
                    ledger.hp = after
                }
            } else if (event.type in setOf(ArenaSupportEventType.ATTACK_HIT, ArenaSupportEventType.ATTACK_MISS,
                    ArenaSupportEventType.ATTACK_EVADED, ArenaSupportEventType.HEAL_APPLIED)) {
                problems.add("$at: missing required HP trace")
            }
            if (event.mpBeforeUnits != null || event.mpAfterUnits != null) {
                val caster = ledgers[event.actorId]
                val finalCaster = result.fighters[event.actorId]
                problems.check(caster != null && finalCaster != null && event.mpBeforeUnits != null && event.mpAfterUnits != null,
                    at, "MP fields lack owner/before/after")
                if (caster != null && finalCaster != null && event.mpBeforeUnits != null && event.mpAfterUnits != null) {
                    val before = event.mpBeforeUnits
                    val after = event.mpAfterUnits
                    problems.check(caster.mp == before && after in 0..finalCaster.maxMpUnits, at, "MP chain/bounds mismatch")
                    if (event.type == ArenaSupportEventType.CAST_START) {
                        val paid = before - after
                        problems.check(paid >= 0 && close(paid.toDouble(), event.amount * 1000), at, "MP payment mismatch")
                        problems.check(if (event.actionId == "BASIC_ATTACK") paid == 0 else paid >= 1000,
                            at, "basic is not free or a paid action costs less than one MP")
                        val baseMp = contractBaseMp(inputs[event.actorId], event.actionId, result.appliedRules)
                        val discount = event.traitValue ?: 0.0
                        problems.check(baseMp != null && discount.isFinite() && discount >= 0.0,
                            at, "MP contract lacks an action base price or valid discount")
                        if (baseMp != null && discount.isFinite() && discount >= 0.0) {
                            val expectedPaid = if (baseMp == 0) 0 else maxOf(1000,
                                ceil(baseMp * 1000.0 * (1.0 - minOf(discount, 30.0) / 100.0) - 1e-9).toInt())
                            val coreIds = inputs[event.actorId]?.traits.orEmpty().map { it.id }.toSet()
                            val alternatives = mutableSetOf(expectedPaid)
                            if (event.actionId !in supportContracts) {
                                if ("AT9_MAGE_A_CORE" in coreIds) alternatives += expectedPaid + 12_000
                                if ("AT9_RANGER_A_CORE" in coreIds) alternatives += expectedPaid + 4_000
                            }
                            if (event.actionId == "ARENA_SUP_PALADIN_10" && "AT9_PALADIN_B_CORE" in coreIds)
                                alternatives += maxOf(1000, ceil(15_000 * (1 - minOf(discount, 30.0) / 100) - 1e-9).toInt())
                            problems.check(paid in alternatives, at,
                                "MP contract: paid=$paid authorized=$alternatives baseMp=$baseMp discount=$discount")
                        }
                        caster.paidMp += paid
                    } else if (event.type == ArenaSupportEventType.MP_DRAINED) {
                        problems.check(after <= before && close(event.amount * 1000, (before - after).toDouble()), at,
                            "MP drain differs from actual balance loss")
                        caster.drainedMp += before - after
                    } else problems.check(before == after, at, "MP changed outside cast start or drain")
                    caster.mp = after
                }
            } else if (event.type == ArenaSupportEventType.CAST_START) problems.add("$at: cast has no MP payment trace")
            if (event.shieldBefore != null || event.shieldAfter != null) {
                problems.check(ledger != null && event.shieldBefore != null && event.shieldAfter != null,
                    at, "shield fields lack owner/before/after")
                if (ledger != null && event.shieldBefore != null && event.shieldAfter != null) {
                    val before = event.shieldBefore
                    val after = event.shieldAfter
                    problems.check(before.isFinite() && after.isFinite() && before >= 0 && after >= 0 && close(ledger.shield, before),
                        at, "shield ledger chain/bounds mismatch")
                    when (event.type) {
                        ArenaSupportEventType.SHIELD_ABSORBED -> problems.check(after <= before && close(before - after, event.amount),
                            at, "absorption amount differs from shield loss")
                        ArenaSupportEventType.SUPPORT_APPLIED, ArenaSupportEventType.EFFECT_EXPIRED -> Unit
                        else -> problems.check(close(before, after), at, "shield changed outside application/absorption/expiry")
                    }
                    ledger.shield = after
                }
            }

            when (event.type) {
                ArenaSupportEventType.START -> {
                    val id = event.actorId
                    problems.check(id != null && starts.add(id), at, "duplicate/unknown START")
                    problems.check(event.hpBefore != null && event.mpBeforeUnits != null && event.shieldBefore == 0.0,
                        at, "START lacks initial HP/MP/shield ledger")
                }
                ArenaSupportEventType.CAST_START -> {
                    val id = event.actorId
                    val castId = event.castId
                    problems.check(id != null && ledgers[id]?.hp?.let { it > 0 } == true && id !in dead,
                        at, "KO fighter starts an action")
                    problems.check(event.castTurns in 1..3 && castId != null && castId >= 0,
                        at, "cast duration or cast ID missing/invalid")
                    if (id != null && castId != null) {
                        problems.check(id !in activeCasts, at, "actor started overlapping casts")
                        problems.check(casts.put(id to castId, Cast(event.turn, event.castTurns, event.actionId)) == null,
                            at, "duplicate cast ID for actor")
                        activeCasts[id] = castId
                        val owned = inputs.getValue(id)
                        problems.check(event.actionId == "BASIC_ATTACK" || event.actionId in owned.supportIds ||
                            owned.fighter.attacks.any { it.id == event.actionId }, at, "unowned action started")
                        event.actionId?.let { actionId ->
                            val readyAt = actionReadyAt[id to actionId] ?: 0
                            problems.check(event.turn >= readyAt, at,
                                "action restarted before completion-based cooldown: turn=${event.turn} readyAt=$readyAt action=$actionId")
                        }
                    }
                }
                ArenaSupportEventType.CAST_PROGRESS -> {
                    val cast = event.actorId?.let { actor -> event.castId?.let { casts[actor to it] } }
                    problems.check(cast != null && !cast.completed && !cast.cancelled, at, "progress lacks active cast")
                    if (cast != null) problems.check(event.remainingTurns in 0..(cast.duration + cast.delayedTurns) &&
                        event.turn in cast.start..(cast.start + cast.duration + cast.delayedTurns - 1), at, "invalid shared-turn cast progress")
                }
                ArenaSupportEventType.ATTACK_HIT, ArenaSupportEventType.ATTACK_MISS,
                ArenaSupportEventType.ATTACK_EVADED, ArenaSupportEventType.SUPPORT_APPLIED -> {
                    val actor = if (event.type == ArenaSupportEventType.ATTACK_EVADED) event.targetId else event.actorId
                    val castId = event.castId
                    val cast = if (actor != null && castId != null) casts[actor to castId] else null
                    problems.check(actor != null && ledgers[actor]?.hp?.let { it > 0 } == true && actor !in dead,
                        at, "KO fighter completes an action")
                    problems.check(cast != null && !cast.completed && !cast.cancelled, at, "missing/duplicate/cancelled action completion")
                    if (cast != null) {
                        problems.check(event.turn == cast.start + cast.duration + cast.delayedTurns - 1 && event.actionId == cast.actionId,
                            at, "action completion violates S+C-1 or action identity")
                        cast.completed = true
                        activeCasts.remove(actor)
                        val actionId = cast.actionId
                        if (actor != null && actionId != null && actionId != "BASIC_ATTACK") {
                            val cooldown = if (event.type == ArenaSupportEventType.SUPPORT_APPLIED)
                                supportContracts[actionId]?.cooldown else result.appliedRules.cooldownTurns
                            problems.check(cooldown != null, at, "completed support lacks an independent cooldown contract")
                            if (cooldown != null) actionReadyAt[actor to actionId] = event.turn + cooldown + 1
                        }
                    }
                    if (event.type == ArenaSupportEventType.SUPPORT_APPLIED) {
                        problems.check(inputs[event.actorId]?.supportIds?.contains(event.actionId) == true, at, "unowned support applied")
                    } else {
                        problems.check(event.actorId != null && event.targetId != null && event.actorId != event.targetId,
                            at, "direct attack identifies no opponent or targets its own actor")
                    }
                }
                ArenaSupportEventType.HEAL_APPLIED -> {
                    val actor = event.actorId
                    problems.check(actor != null && actor !in dead && event.hpBefore?.let { it > 0 } == true,
                        at, "healing after KO")
                    // HoT and life-link heals share their original cast; each actual heal has a unique sequence.
                    problems.check(event.causeSequence != null, at, "heal has no originating action or status")
                }
                ArenaSupportEventType.CAST_DELAYED, ArenaSupportEventType.CAST_PAUSED -> {
                    val cast = event.actorId?.let { actor -> event.castId?.let { casts[actor to it] } }
                    if (cast != null) {
                        problems.check(!cast.completed && !cast.cancelled, at, "delay or pause after completion")
                        cast.delayedTurns += if (event.type == ArenaSupportEventType.CAST_PAUSED) 1 else event.amount.toInt()
                    }
                }
                ArenaSupportEventType.TRAIT_TRIGGERED -> {
                    val owned = inputs[event.actorId]?.traits.orEmpty()
                    problems.check(event.traitId != null && owned.any { it.id == event.traitId }, at, "unallocated trait triggered")
                    problems.check(event.traitValue != null && event.traitValue.isFinite() && event.traitValue > 0,
                        at, "trigger lacks positive effective trait value")
                }
                ArenaSupportEventType.KO -> {
                    koCount++
                    val id = event.actorId
                    problems.check(id != null && dead.add(id) && ledgers[id]?.hp == 0.0, at, "KO without zero HP or duplicate KO")
                }
                ArenaSupportEventType.CAST_CANCELLED_KO -> {
                    val actor = event.actorId
                    val castId = event.castId
                    problems.check(actor != null && actor in dead && ledgers[actor]?.hp == 0.0,
                        at, "KO cancellation without a dead actor")
                    val cast = if (actor != null && castId != null) casts[actor to castId] else null
                    problems.check(cast != null && !cast.completed && !cast.cancelled, at, "cancellation lacks outstanding cast")
                    if (cast != null) cast.cancelled = true
                    activeCasts.remove(actor)
                }
                ArenaSupportEventType.END -> { endCount++; ended = true }
                ArenaSupportEventType.SAFETY_ABORT -> ended = true
                else -> Unit
            }
        }
        problems.check(starts == inputs.keys, label, "missing one or both initial START events")
        problems.check(result.status == ArenaRunStatus.COMPLETED, label, "safety limit is a calculation failure, not a draw")
        if (result.status == ArenaRunStatus.COMPLETED) {
            val draw = result.winnerId == null
            problems.check(endCount == 1 && koCount == if (draw) 2 else 1, label,
                "completed duel lacks expected KO and END events")
            problems.check(draw || result.winnerId in inputs.keys, label, "invalid winner identity")
            for ((id, fighter) in result.fighters) {
                problems.check(if (!draw && id == result.winnerId) fighter.hp > 0 else fighter.hp == 0.0,
                    label, "winner/loser HP contradicts outcome")
            }
            if (draw) problems.check(result.events.last().reason == "simultaneous_dot_draw", label,
                "draw must come from simultaneous lethal periodic damage")
        }
        for ((id, final) in result.fighters) {
            val ledger = ledgers.getValue(id)
            problems.check(close(ledger.hp, final.hp) && ledger.mp == final.mpUnits && close(ledger.shield, final.shield),
                label, "reconstructed terminal ledger differs for $id")
            problems.check(close(final.maxHp + ledger.healedHp - ledger.lostHp, final.hp),
                label, "HP conservation mismatch for $id")
            problems.check(final.maxMpUnits.toLong() - ledger.paidMp - ledger.drainedMp == final.mpUnits.toLong(),
                label, "MP conservation mismatch for $id")
        }
    }

    private fun report(
        samples: Int, levels: List<Int>, executions: Int, recorded: Int, parity: Int,
        rows: List<PairTally>, coverage: Coverage, problems: Problems, started: Long,
    ): ArenaSupportAuditReport {
        val warnings = mutableListOf(
            "Exploratory fixed-growth combat-seed sample; not a population win-rate or full balance certification.",
            "All 60 supports and 144 traits are tracked; each fixture owns only hero-level-unlocked supports. Unobserved effects remain explicit coverage gaps.",
            "Trait coverage includes prepared rights; inspect trigger reasons and discounted casts separately from applied effects.",
        )
        coverage.supports.filterValues { it == 0 }.keys.forEach { warnings += "Unobserved support: $it" }
        coverage.traits.filterValues { it == 0 }.keys.forEach { warnings += "Unobserved trait: $it" }
        val classRows = rows.groupBy { it.cohort to it.level }.flatMap { (group, pairs) ->
            classes.map { cls ->
                val cross = pairs.filter { it.row != it.col && (it.row == cls.name || it.col == cls.name) }
                val scores = cross.mapNotNull { pair -> pair.score()?.let { if (pair.row == cls.name) it else 1.0 - it } }
                val wins = cross.sumOf { if (it.row == cls.name) it.rowWins else it.completed - it.rowWins - it.draws }
                val completed = cross.sumOf { it.completed }
                val draws = cross.sumOf { it.draws }
                buildJsonObject {
                    put("cohort", group.first); put("heroLevel", group.second); put("heroClass", cls.name)
                    put("opponents", cross.size); put("completed", completed); put("wins", wins)
                    put("draws", draws); put("losses", completed - wins - draws)
                    put("equalOpponentScore", if (scores.size == 5) JsonPrimitive(scores.average()) else JsonNull)
                    put("aborted", cross.sumOf { it.aborted })
                }
            }
        }
        val json = buildJsonObject {
            put("auditVersion", "arena-support-audit-v3"); put("passed", problems.count == 0)
            put("scope", "complete_catalog_level_gated_same_budget_exploratory")
            put("executionLocation", "calling_process_cpu_not_assumed_device")
            put("samplesPerPair", samples); put("heroLevels", JsonArray(levels.map(::JsonPrimitive)))
            put("arenaLevel", 60); put("growthTrajectories", 6)
            put("growthFixture", "one_fixed_actual_source_growth_per_class_reused_across_levels")
            put("plannedOwnedSupports", "all_current_hero_level_unlocked_supports")
            put("cohorts", JsonArray(Cohort.entries.map { JsonPrimitive(it.name) }))
            put("baseSeed", BASE_SEED); put("seedsSharedAcrossCohorts", true)
            put("plannedUniqueLevelPairCombatSeeds", levels.size * 21 * samples)
            put("mirrorIndependentSamples", false); put("recordingReplaysIndependentSamples", false)
            put("executions", executions); put("recordedExecutions", recorded); put("recordingParityExecutions", parity)
            put("elapsedMillis", (System.nanoTime() - started) / 1_000_000)
            put("failureCount", problems.count); put("failureDetailsTruncated", problems.count > problems.details.size)
            put("failures", JsonArray(problems.details.map(::JsonPrimitive)))
            put("warnings", JsonArray(warnings.map(::JsonPrimitive)))
            put("scoreDefinition", "win_plus_half_draw_equal_weight_other_five_classes_self_pairs_excluded")
            put("classRates", JsonArray(classRows)); put("pairRows", JsonArray(rows.map { it.json() }))
            put("supportCoverage", countsJson(coverage.supports)); put("traitCoverage", countsJson(coverage.traits))
            put("traitTriggerReasons", countsJson(coverage.traitReasons))
            put("discountedCastStartsByTrait", countsJson(coverage.discountedCastStarts))
            put("eventCounts", countsJson(coverage.eventCounts))
            put("recordedHpDamage", finiteJson(coverage.hpDamage)); put("recordedActualHealing", finiteJson(coverage.actualHealing))
            put("recordedShieldAbsorption", finiteJson(coverage.shieldAbsorption)); put("recordedPaidMpUnits", coverage.paidMpUnits)
            put("coverageCountsIncludePositionMirrors", true)
            put("coverageAmountsExcludeInvalidEvents", true)
        }.toString()
        return ArenaSupportAuditReport(json, problems.count == 0, executions, problems.details.toList())
    }

    private fun countsJson(counts: Map<String, Int>): JsonObject = buildJsonObject {
        counts.toSortedMap().forEach { (key, value) -> put(key, value) }
    }
    private fun close(a: Double, b: Double): Boolean = a.isFinite() && b.isFinite() && abs(a - b) <= EPSILON
    private fun finiteJson(value: Double) = if (value.isFinite()) JsonPrimitive(value) else JsonNull
    private fun quantile(values: List<Int>, probability: Double): Int = if (values.isEmpty()) 0 else
        values.sorted()[(ceil(probability * values.size).toInt() - 1).coerceIn(0, values.lastIndex)]
}
