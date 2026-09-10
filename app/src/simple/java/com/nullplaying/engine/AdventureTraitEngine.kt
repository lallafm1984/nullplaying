package com.nullplaying.engine

import com.nullplaying.model.*

/** Main-adventure only. Stable source/domain rolls never advance combat, loot or arena RNG. */
object AdventureTraitEngine {
    const val EXTRA_ITEM_BASIS_POINTS = 10
    const val EVIDENCE_WINDOW = 12
    const val FORMATION_MIN_ACTIVE_MILLIS = 8L * 60L * 60L * 1_000L
    const val FORMATION_COOLDOWN_ACTIVE_MILLIS = 24L * 60L * 60L * 1_000L
    const val STABLE_MIN_ACTIVE_MILLIS = 24L * 60L * 60L * 1_000L
    const val OPPOSITION_MIN_ACTIVE_MILLIS = 8L * 60L * 60L * 1_000L
    const val SHAKY_RECOVERY_MIN_ACTIVE_MILLIS = 8L * 60L * 60L * 1_000L
    const val SHAKY_LOSS_MIN_ACTIVE_MILLIS = 24L * 60L * 60L * 1_000L
    const val WEAKENING_OPPOSITION_COUNT = 4
    const val LOSS_OPPOSITION_COUNT = 6
    private const val EVENT_BEHAVIOR_BASIS_POINTS = 100
    private const val EVENT_CONTEXT_PACE_BASIS_POINTS = 100
    private const val TRADEOFF_EFFECT_BASIS_POINTS = 100
    private const val SHOP_FOCUS_BASIS_POINTS = 300
    private const val RELATIONSHIP_TONE_BASIS_POINTS = 300
    private val speechSignals = setOf(AdventureBehaviorSignal.SPEAK_DIRECT, AdventureBehaviorSignal.SPEAK_GENTLE)
    private val signalTraits = mapOf(
        AdventureBehaviorSignal.TAKE_ALL to ("L01" to "L02"),
        AdventureBehaviorSignal.TAKE_ONLY_USEFUL to ("L02" to "L01"),
        AdventureBehaviorSignal.PREPARE_THOROUGHLY to ("L04" to "L03"),
        AdventureBehaviorSignal.DEPART_LIGHTLY to ("L03" to "L04"),
        AdventureBehaviorSignal.INSPECT_NEW_GEAR to ("L05" to "L06"),
        AdventureBehaviorSignal.KEEP_FAMILIAR_GEAR to ("L06" to "L05"),
        AdventureBehaviorSignal.WEAPON_FOCUS to ("S05" to "S06"),
        AdventureBehaviorSignal.ARMOR_FOCUS to ("S06" to "S05"),
        AdventureBehaviorSignal.PERSIST to ("E03" to "E04"),
        AdventureBehaviorSignal.MOVE_ON to ("E04" to "E03"),
        AdventureBehaviorSignal.SEEK_NOVELTY to ("G01" to "G02"),
        AdventureBehaviorSignal.REPEAT_PROVEN to ("G02" to "G01"),
        AdventureBehaviorSignal.TAKE_RISK to ("E01" to "E02"),
        AdventureBehaviorSignal.CHECK_SAFETY to ("E02" to "E01"),
        AdventureBehaviorSignal.HELP_OTHERS to ("R01" to "R02"),
        AdventureBehaviorSignal.SELF_PRIORITY to ("R02" to "R01"),
        AdventureBehaviorSignal.COOPERATE to ("R03" to "R04"),
        AdventureBehaviorSignal.ACT_ALONE to ("R04" to "R03"),
        AdventureBehaviorSignal.SPEAK_DIRECT to ("R05" to "R06"),
        AdventureBehaviorSignal.SPEAK_GENTLE to ("R06" to "R05"),
        AdventureBehaviorSignal.TOWN_COMFORT to ("T01" to "T02"),
        AdventureBehaviorSignal.WILDERNESS_COMFORT to ("T02" to "T01"),
        AdventureBehaviorSignal.HURRY_HOME to ("T03" to "T04"),
        AdventureBehaviorSignal.LINGER_RETURN to ("T04" to "T03"),
    )
    private val approachBiases = listOf(
        "E01" to AdventureBehaviorSignal.TAKE_RISK,
        "E02" to AdventureBehaviorSignal.CHECK_SAFETY,
        "R01" to AdventureBehaviorSignal.HELP_OTHERS,
        "R02" to AdventureBehaviorSignal.SELF_PRIORITY,
        "R03" to AdventureBehaviorSignal.COOPERATE,
        "R04" to AdventureBehaviorSignal.ACT_ALONE,
        "T03" to AdventureBehaviorSignal.HURRY_HOME,
        "T04" to AdventureBehaviorSignal.LINGER_RETURN,
    )
    private val recordedChanges = setOf(AdventureTraitChangeKind.ACQUIRED,
        AdventureTraitChangeKind.LOST, AdventureTraitChangeKind.REPLACED)

    fun initialize(state: SimpleGameState) {
        val traits = state.adventureTraits
        if (!traits.initialized) {
            traits.initialized = true
            traits.seed = mix(state.skillCatalogSeed xor state.rngState xor 0x273B_0196_56A4_87CDL)
        }
        val normalized = mutableListOf<AdventureOwnedTrait>()
        traits.owned.sortedWith(compareByDescending<AdventureOwnedTrait> { it.acquisitionSequence }
            .thenBy { it.traitId }).forEach { owned ->
            val definition = AdventureTraitCatalog.find(owned.traitId) ?: return@forEach
            if (normalized.none { it.traitId == owned.traitId || it.traitId == definition.oppositeId }) normalized += owned
        }
        traits.owned = normalized
        val ownedIds = normalized.mapTo(mutableSetOf()) { it.traitId }
        traits.formationStartedAtByTrait = traits.formationStartedAtByTrait.filterKeys { id ->
            AdventureTraitCatalog.find(id) != null && id !in ownedIds
        }
        traits.stableStartedAtByTrait = traits.stableStartedAtByTrait.filterKeys { it in ownedIds }
        traits.oppositionStartedAtByTrait = traits.oppositionStartedAtByTrait.filterKeys { it in ownedIds }
        val shakyIds = normalized.filter { it.shaky }.mapTo(mutableSetOf()) { it.traitId }
        traits.weakenedStartedAtByTrait = traits.weakenedStartedAtByTrait.filterKeys { it in shakyIds }
    }

    fun beginSource(state: SimpleGameState, kind: String, context: String, at: Long): AdventureTraitSource {
        initialize(state)
        val traits = state.adventureTraits
        finalizeEvidence(state, at)
        traits.sourceSequence++
        val source = AdventureTraitSource("$kind:${traits.sourceSequence}", kind, context, at,
            traits.owned.map { it.traitId })
        traits.source = source
        traits.visibleActivations = emptyList()
        return source
    }

    fun owns(state: SimpleGameState, id: String): Boolean =
        state.adventureTraits.source?.ownedIds?.contains(id) ?: state.adventureTraits.owned.any { it.traitId == id }

    fun ensureSource(state: SimpleGameState) {
        initialize(state)
        if (state.adventureTraits.source != null) return
        val kind = when (state.adventurePhase) {
            AdventurePhase.COMBAT, AdventurePhase.LOOTING -> "combat"
            AdventurePhase.EVENT, AdventurePhase.EVENT_RESULT -> "event"
            AdventurePhase.RELATIONSHIP, AdventurePhase.RELATIONSHIP_RESULT -> "relationship"
            else -> "town"
        }
        val source = beginSource(state, kind, if (kind == "combat") state.monster.catalogId else kind, state.actionStartedAt)
        source.baseEvent = state.adventureJourney.pending
        source.baseRelationship = state.adventureRelationships.pending
    }

    fun random(seed: Long, key: String, bound: Int = 10_000): Int {
        require(bound > 0)
        var hash = seed xor -3_750_763_034_362_895_579L
        key.forEach { hash = (hash xor it.code.toLong()) * 1_099_511_628_211L }
        return ((mix(hash) ushr 1) % bound.toLong()).toInt()
    }

    fun seedFor(state: SimpleGameState, key: String): Long =
        mix(state.adventureTraits.seed xor random(state.adventureTraits.seed, key, Int.MAX_VALUE).toLong())

    fun roll(state: SimpleGameState, id: String, sourceKey: String, basisPoints: Int): Boolean {
        val traits = state.adventureTraits
        val key = "$id:$sourceKey"
        traits.decisions.lastOrNull { it.key == key }?.let { return it.passed }
        val value = random(traits.seed, key)
        val passed = value < basisPoints
        traits.decisions = (traits.decisions + AdventureTraitDecision(key, value, basisPoints, passed)).takeLast(512)
        traits.opportunityCounts = increment(traits.opportunityCounts, id)
        if (passed) traits.procCounts = increment(traits.procCounts, id)
        return passed
    }

    fun activate(state: SimpleGameState, id: String, key: String, at: Long, kind: AdventureTraitEffectKind,
        before: Long = 0L, after: Long = 0L, time: Long = 0L, name: String = "") {
        val traits = state.adventureTraits
        if (traits.recentActivations.any { it.traitId == id && it.sourceKey == key && it.effectKind == kind }) return
        traits.activationSequence++
        val activation = AdventureTraitActivation(traits.activationSequence, id, key, state.actionSequence,
            at, kind, before, after, time, name)
        traits.visibleActivations = (traits.visibleActivations + activation).takeLast(4)
        traits.recentActivations = (traits.recentActivations + activation).takeLast(32)
        traits.actualEffectCounts = increment(traits.actualEffectCounts, id)
    }

    fun drainRecentEvents(state: SimpleGameState): List<RecentAdventureEvent> {
        val traits = state.adventureTraits
        // Wavering/recovery remains visible in the profile without filling the shared event history.
        val events = traits.recentChanges.filter { it.sequence > traits.reportedChangeSequence && it.kind in recordedChanges }.map {
            RecentAdventureEvent(it.occurredAt, RecentAdventureEventType.ADVENTURE_TRAIT_CHANGED,
                subjectId = it.traitId, subjectName = AdventureTraitCatalog.definition(it.traitId).name.ko,
                contextName = it.kind.name, previousName = it.replacedTraitId, currentName = it.reasonKey)
        } + traits.recentActivations.filter { it.sequence > traits.reportedActivationSequence }.map {
            RecentAdventureEvent(it.occurredAt, RecentAdventureEventType.ADVENTURE_TRAIT_ACTIVATED,
                subjectId = it.traitId, subjectName = AdventureTraitCatalog.definition(it.traitId).name.ko,
                contextName = it.effectKind.name, previousValue = it.previousValue,
                currentValue = it.currentValue, currentName = it.subjectName)
        }
        traits.reportedChangeSequence = traits.changeSequence
        traits.reportedActivationSequence = traits.activationSequence
        return events.sortedBy { it.occurredAt }
    }

    fun observe(state: SimpleGameState, key: String, context: String, positive: Set<String>,
        negative: Set<String> = emptySet(), at: Long, reason: String = context) {
        val traits = state.adventureTraits
        if (traits.pendingEvidence.any { it.sourceKey == key }) return
        if ("S01" in positive && reason == "trade:purchase") traits.prerequisites += "considered"
        traits.pendingEvidence = traits.pendingEvidence + AdventureTraitEvidenceUpdate(key, context, positive, negative, at, reason)
    }

    fun finalizeEvidence(state: SimpleGameState, at: Long) {
        val traits = state.adventureTraits
        val updates = traits.pendingEvidence
        if (updates.isEmpty()) return
        traits.pendingEvidence = emptyList()
        val key = traits.source?.key ?: updates.first().sourceKey
        if (key in traits.observedSourceKeys) return
        traits.observedSourceKeys = (traits.observedSourceKeys + key).takeLast(512)
        val positive = updates.flatMap { it.positive }.toSet()
        val negative = updates.flatMap { it.negative }.toSet() - positive
        val freshPositive = mutableSetOf<String>()
        val ids = (positive + negative).filter { AdventureTraitCatalog.find(it) != null }
        ids.forEach { id ->
            if (traits.evidence[id].orEmpty().any { it.sourceKey == key }) return@forEach
            val update = updates.first { if (id in positive) id in it.positive else id in it.negative }
            val entry = AdventureTraitEvidence(key, update.contextKey, id in positive, update.reasonKey)
            traits.evidence = traits.evidence + (id to (traits.evidence[id].orEmpty() + entry).takeLast(EVIDENCE_WINDOW))
            if (id in positive) freshPositive += id
        }
        val beforeOwned = traits.owned
        beforeOwned.forEach { owned ->
            if (owned.traitId !in traits.stableStartedAtByTrait) {
                // A save created before active-time pacing starts a fresh protection window.
                traits.stableStartedAtByTrait = traits.stableStartedAtByTrait + (owned.traitId to at)
            }
            if (owned.shaky && owned.traitId !in traits.weakenedStartedAtByTrait) {
                traits.weakenedStartedAtByTrait = traits.weakenedStartedAtByTrait + (owned.traitId to at)
            }
        }
        freshPositive.filter { id -> beforeOwned.none { it.traitId == id } }.forEach { id ->
            if (id !in traits.formationStartedAtByTrait) {
                traits.formationStartedAtByTrait = traits.formationStartedAtByTrait + (id to at)
            }
        }
        ids.forEach { id ->
            val owned = beforeOwned.firstOrNull { it.traitId == id }
            val opposition = traits.evidence[id].orEmpty().count { !it.positive }
            if (owned == null && opposition > 3) {
                // Formation requires one sustained, currently coherent tendency.
                traits.formationStartedAtByTrait = traits.formationStartedAtByTrait - id
            } else if (owned != null && id in negative && id !in traits.oppositionStartedAtByTrait) {
                traits.oppositionStartedAtByTrait = traits.oppositionStartedAtByTrait + (id to at)
            } else if (owned != null && id !in negative && !owned.shaky && opposition <= 3) {
                traits.oppositionStartedAtByTrait = traits.oppositionStartedAtByTrait - id
            }
        }
        val eligible = freshPositive.filter { id ->
            val evidence = traits.evidence[id].orEmpty()
            val support = evidence.filter { it.positive }
            val opposite = AdventureTraitCatalog.definition(id).oppositeId
            val oppositeOwned = beforeOwned.firstOrNull { it.traitId == opposite }
            val replacementReady = oppositeOwned == null || (
                oppositeOwned.shaky && elapsedAtLeast(
                    at,
                    traits.weakenedStartedAtByTrait[opposite],
                    SHAKY_LOSS_MIN_ACTIVE_MILLIS,
                )
            )
            beforeOwned.none { it.traitId == id } && replacementReady &&
                elapsedAtLeast(at, traits.formationStartedAtByTrait[id], FORMATION_MIN_ACTIVE_MILLIS) && support.size >= 8 &&
                support.map { it.contextKey }.distinct().size >= 3 && evidence.count { !it.positive } <= 3 &&
                when (id) {
                    "L01" -> "exploration" in traits.prerequisites
                    "L04" -> "packing" in traits.prerequisites && "carried" in traits.prerequisites
                    "S01" -> "sale" in traits.prerequisites && "considered" in traits.prerequisites
                    "S02" -> "empty_visit" in traits.prerequisites
                    else -> true
                }
        }
        val formationCooldownReady = traits.lastFormationAt == null ||
            elapsedAtLeast(at, traits.lastFormationAt, FORMATION_COOLDOWN_ACTIVE_MILLIS)
        val acquired = if (eligible.isNotEmpty() && formationCooldownReady && roll(state, "FORMATION", key, 2_500))
            eligible.sortedWith(compareByDescending<String> { candidate ->
                traits.evidence[candidate].orEmpty().fold(0) { score, item -> score + if (item.positive) 1 else -1 }
            }.thenBy { random(traits.seed, "$key:choice:$it", Int.MAX_VALUE) }).first() else null
        val replacedId = acquired?.let { AdventureTraitCatalog.definition(it).oppositeId }.orEmpty()
        traits.owned = beforeOwned.mapNotNull { owned ->
            if (owned.traitId == replacedId) {
                clearOwnedLifecycleAnchors(traits, owned.traitId)
                return@mapNotNull null
            }
            if (owned.traitId !in ids) return@mapNotNull owned
            val opposition = traits.evidence[owned.traitId].orEmpty().filterNot { it.positive }
            val diversified = opposition.map { it.contextKey }.distinct().size >= 3
            val reason = updates.firstOrNull { owned.traitId in it.negative || owned.traitId in it.positive }?.reasonKey ?: "adventure:experience"
            val canLose = owned.shaky && opposition.size >= LOSS_OPPOSITION_COUNT && diversified && elapsedAtLeast(
                at,
                traits.weakenedStartedAtByTrait[owned.traitId],
                SHAKY_LOSS_MIN_ACTIVE_MILLIS,
            )
            if (canLose) {
                change(state, owned.traitId, AdventureTraitChangeKind.LOST, key, at, reason)
                clearOwnedLifecycleAnchors(traits, owned.traitId)
                null
            } else {
                val support = traits.evidence[owned.traitId].orEmpty().filter { it.positive }
                val canRecover = opposition.size <= 3 && owned.traitId in freshPositive &&
                    support.map { it.contextKey }.distinct().size >= 3 && elapsedAtLeast(
                        at,
                        traits.weakenedStartedAtByTrait[owned.traitId],
                        SHAKY_RECOVERY_MIN_ACTIVE_MILLIS,
                    )
                val canWeaken = !owned.shaky && opposition.size >= WEAKENING_OPPOSITION_COUNT && diversified &&
                    elapsedAtLeast(at, traits.stableStartedAtByTrait[owned.traitId], STABLE_MIN_ACTIVE_MILLIS) &&
                    elapsedAtLeast(at, traits.oppositionStartedAtByTrait[owned.traitId], OPPOSITION_MIN_ACTIVE_MILLIS)
                val shaky = if (owned.shaky) !canRecover else canWeaken
                if (shaky != owned.shaky) {
                    val record = change(state, owned.traitId,
                        if (shaky) AdventureTraitChangeKind.WEAKENED else AdventureTraitChangeKind.RECOVERED,
                        key, at, reason)
                    if (shaky) {
                        traits.weakenedStartedAtByTrait = traits.weakenedStartedAtByTrait + (owned.traitId to at)
                    } else {
                        traits.weakenedStartedAtByTrait = traits.weakenedStartedAtByTrait - owned.traitId
                        traits.oppositionStartedAtByTrait = traits.oppositionStartedAtByTrait - owned.traitId
                        traits.stableStartedAtByTrait = traits.stableStartedAtByTrait + (owned.traitId to at)
                    }
                    owned.copy(shaky = shaky, lastChange = record)
                } else owned
            }
        }
        if (acquired != null) {
            val id = acquired
            val reason = updates.first { id in it.positive }.reasonKey
            val opposite = AdventureTraitCatalog.definition(id).oppositeId
            val replaced = beforeOwned.firstOrNull { it.traitId == opposite }
            val record = change(state, id, if (replaced == null) AdventureTraitChangeKind.ACQUIRED else AdventureTraitChangeKind.REPLACED,
                key, at, reason, replaced?.traitId.orEmpty())
            traits.lastFormationAt = at
            traits.owned = traits.owned.filterNot { it.traitId == opposite || it.traitId == id } +
                AdventureOwnedTrait(id, at, record.sequence, false, record)
            traits.evidence = traits.evidence + (id to emptyList())
            traits.formationStartedAtByTrait = traits.formationStartedAtByTrait - id - opposite
            traits.stableStartedAtByTrait = traits.stableStartedAtByTrait + (id to at)
            traits.oppositionStartedAtByTrait = traits.oppositionStartedAtByTrait - id
            traits.weakenedStartedAtByTrait = traits.weakenedStartedAtByTrait - id
        }
        traits.owned = traits.owned.sortedWith(compareByDescending<AdventureOwnedTrait> { it.acquisitionSequence }.thenBy { it.traitId })
    }

    private fun change(state: SimpleGameState, id: String, kind: AdventureTraitChangeKind,
        key: String, at: Long, reason: String, replaced: String = ""): AdventureTraitChange {
        val traits = state.adventureTraits
        traits.changeSequence++
        val record = AdventureTraitChange(traits.changeSequence, id, kind, key, at, reason, replaced)
        traits.recentChanges = (traits.recentChanges + record).takeLast(32)
        return record
    }

    fun beginCombat(state: SimpleGameState, at: Long) {
        val source = beginSource(state, "combat", state.monster.catalogId.ifBlank { state.monster.baseName }, at)
        val id = listOf("C03", "C04").firstOrNull { it in source.ownedIds }
        if (id != null && roll(state, id, source.key, 50)) source.combatTraitId = id
    }

    fun basicDamagePercent(state: SimpleGameState, raw: Long, baseDamage: Long, energyBefore: Long, at: Long): Long {
        val source = state.adventureTraits.source ?: return raw
        // Both styles use the same fixed prefix; long fights cannot keep widening only one sample.
        if (source.rawBasicRolls.size < 3) source.rawBasicRolls = source.rawBasicRolls + raw.toInt()
        if (scale(baseDamage, raw) >= energyBefore) source.finishingRawPercent = raw.toInt()
        if (!source.firstBasicPending) return raw
        source.firstBasicPending = false
        val changed = when (source.combatTraitId) {
            "C03" -> 30L + (raw - 40L) * 2L
            "C04" -> { val half = (raw + 50L) / 2L; if ((raw + 50L) % 2L != 0L && half % 2L != 0L) half + 1L else half }
            else -> raw
        }
        val before = scale(baseDamage, raw).coerceAtLeast(1L)
        val after = scale(baseDamage, changed).coerceAtLeast(1L)
        if (before != after) {
            source.combatAltered = true
            activate(state, source.combatTraitId, source.key, at, AdventureTraitEffectKind.DAMAGE, before, after)
        }
        return changed
    }

    fun observeCombat(state: SimpleGameState, at: Long) {
        val traits = state.adventureTraits
        val source = traits.source ?: return
        if (!source.combatAltered) {
            val sample = source.rawBasicRolls.take(3)
            if (sample.size == 3) {
                val spread = sample.maxOrNull()!! - sample.minOrNull()!!
                val id = when {
                    spread >= 15 -> "C03"
                    spread <= 6 -> "C04"
                    else -> null
                }
                if (id != null) observe(state, source.key + ":style", source.contextKey,
                    setOf(id), setOf(if (id == "C03") "C04" else "C03"), at, "combat:style")
            }
        }
        val mightyFoe = state.monster.grade == MonsterGrade.ELITE || state.monster.grade == MonsterGrade.BOSS
        observe(
            state,
            source.key + ":grade",
            "combat-grade:${state.monster.grade.name}:${source.contextKey}",
            setOf(if (mightyFoe) "C01" else "C02"),
            setOf(if (mightyFoe) "C02" else "C01"),
            at,
            "combat:grade",
        )
        when {
            source.finishingRawPercent >= 55 -> observe(
                state, source.key + ":finisher", "combat-finisher:strong:${source.contextKey}",
                setOf("C05"), setOf("C06"), at, "combat:finisher",
            )
            source.finishingRawPercent in 1..45 -> observe(
                state, source.key + ":finisher", "combat-finisher:measured:${source.contextKey}",
                setOf("C06"), setOf("C05"), at, "combat:finisher",
            )
        }
        traits.stableEquipmentCombats++
        if (traits.stableEquipmentCombats >= 100) {
            traits.stableEquipmentCombats = 0
            observe(state, source.key + ":familiar", source.contextKey, setOf("L06"), setOf("L05"), at, "equipment:familiar")
        }
        val completed = traits.weaponUses.filter { use ->
            val current = state.equipment.firstOrNull { it.slot == use.slot }
            if (state.monster.grade == MonsterGrade.NORMAL && current?.power == use.power &&
                traits.equipmentOrigins[use.slot]?.startsWith("TRAIT_") != true) {
                if (use.firstContext.isBlank()) use.firstContext = source.contextKey
                use.remainingCombats--
            }
            use.remainingCombats <= 0
        }
        completed.forEach { use -> observe(state, use.sourceKey + ":used", use.firstContext,
            if (use.slot == EquipmentSlot.WEAPON) setOf("S05") else setOf("S06"),
            if (use.slot == EquipmentSlot.WEAPON) setOf("S06") else setOf("S05"), at, "equipment:used") }
        traits.weaponUses = traits.weaponUses.filterNot { it in completed }.takeLast(24)
    }

    fun experience(state: SimpleGameState, amount: Long, family: String, at: Long, applyEffect: Boolean = true): Long {
        val traits = state.adventureTraits
        val source = traits.source ?: return amount
        val novel = family !in traits.recentExperienceFamilies.takeLast(3)
        traits.recentExperienceFamilies = (traits.recentExperienceFamilies + family).takeLast(3)
        val learningTrait = if (novel) "G01" else "G02"
        var changed = if (applyEffect && source.finalRewardOrigin == "PRIMARY" && owns(state, learningTrait) &&
            roll(state, learningTrait, source.key + ":xp", 100)) plus(amount, amount / 20L) else amount
        if (changed > amount) {
            source.resultTimePercent += 10
            activate(state, learningTrait, source.key + ":xp", at, AdventureTraitEffectKind.EXPERIENCE, amount, changed)
        }
        fun applyTradeoff(id: String, key: String, favorable: Boolean) {
            if (!applyEffect || source.finalRewardOrigin != "PRIMARY" || !owns(state, id) ||
                !roll(state, id, source.key + key, TRADEOFF_EFFECT_BASIS_POINTS) || changed <= 0L
            ) return
            val before = changed
            val adjustment = maxOf(1L, before / 20L)
            changed = if (favorable) plus(before, adjustment) else (before - adjustment).coerceAtLeast(1L)
            if (changed != before) activate(
                state, id, source.key + key, at, AdventureTraitEffectKind.EXPERIENCE, before, changed,
            )
        }
        if (source.kind == "combat") {
            val mightyFoe = state.monster.grade == MonsterGrade.ELITE || state.monster.grade == MonsterGrade.BOSS
            listOf("C01", "C02").firstOrNull { owns(state, it) }?.let { id ->
                applyTradeoff(id, ":gradeXp", (id == "C01") == mightyFoe)
            }
            val strongFinish = source.finishingRawPercent >= 55
            val measuredFinish = source.finishingRawPercent in 1..45
            if (strongFinish || measuredFinish) listOf("C05", "C06").firstOrNull { owns(state, it) }?.let { id ->
                applyTradeoff(id, ":finishXp", (id == "C05") == strongFinish)
            }
        }
        val largeThreshold = plus(16L, times(state.hero.level.coerceAtLeast(1L), 4L))
        val largeReward = amount >= largeThreshold
        listOf("G03", "G04").firstOrNull { owns(state, it) }?.let { id ->
            applyTradeoff(id, ":rewardSizeXp", (id == "G03") == largeReward)
        }
        val positive = mutableSetOf(learningTrait, if (largeReward) "G03" else "G04")
        val negative = mutableSetOf(if (novel) "G02" else "G01", if (largeReward) "G04" else "G03")
        observe(state, source.key + ":experience", family, positive, negative, at, "adventure:variety")
        return changed
    }

    fun planEvent(state: SimpleGameState, base: AdventureEventRun): AdventureEventRun {
        val source = beginSource(state, "event", base.eventId, base.startedAt)
        source.baseEvent = base
        var result = applyApproachBias(state, source, base)
        val speech = listOf("R05", "R06").firstOrNull { owns(state, it) }
        val speechApproach = AdventureEventEngine.definition(result.eventId).approaches
            .firstOrNull { it.id == result.approachId }
        if (speech != null && speechApproach?.behaviorSignals.orEmpty().any { it in speechSignals } &&
            roll(state, speech, source.key + ":dialogue", 300)) {
            val beforeOutcome = result.outcome
            val outcome = when (speech) {
                "R05" -> if (beforeOutcome == AdventureEventOutcome.PARTIAL)
                    if (random(state.adventureTraits.seed, source.key + ":decisive", 2) == 0) AdventureEventOutcome.SUCCESS else AdventureEventOutcome.FAILURE
                    else beforeOutcome
                else -> AdventureEventOutcome.PARTIAL
            }
            if (outcome != beforeOutcome) {
                source.dialogueOutcome = outcome
                result = AdventureEventEngine.withOutcome(result, outcome, seedFor(state, source.key + ":speechReward"))
                source.finalRewardOrigin = "TRAIT_DIALOGUE"
                activate(state, speech, source.key + ":dialogue", base.startedAt, AdventureTraitEffectKind.DIALOGUE,
                    beforeOutcome.ordinal.toLong(), outcome.ordinal.toLong())
            }
        }
        if (base.outcome != AdventureEventOutcome.SUCCESS && result.outcome != AdventureEventOutcome.SUCCESS && owns(state, "E03") && roll(state, "E03", source.key + ":retry", 100)) {
            val alternatives = AdventureEventEngine.definition(base.eventId).approaches.filter { it.id != base.approachId }
            val alternate = alternatives[random(state.adventureTraits.seed, source.key + ":approach", alternatives.size)]
            val success = AdventureEventEngine.successBasisPoints(state.hero.stats, base.heroLevel, alternate)
            val roll = random(state.adventureTraits.seed, source.key + ":retryOutcome")
            val outcome = when { roll < success -> AdventureEventOutcome.SUCCESS; roll < success + minOf(2_500, 9_500 - success) -> AdventureEventOutcome.PARTIAL; else -> AdventureEventOutcome.FAILURE }
            source.retryRun = AdventureEventEngine.withOutcome(base, outcome, seedFor(state, source.key + ":retryReward")).copy(
                startedAt = plus(base.startedAt, base.durationMillis), durationMillis = (base.durationMillis / 5L).coerceAtLeast(1L),
                approachId = alternate.id, primaryStat = alternate.primaryStat, secondaryStat = alternate.secondaryStat,
                primaryValue = AdventureEventEngine.stat(state.hero.stats, alternate.primaryStat),
                secondaryValue = AdventureEventEngine.stat(state.hero.stats, alternate.secondaryStat),
                successBasisPoints = success, partialBasisPoints = minOf(2_500, 9_500 - success), roll = roll)
            if (outcome.ordinal < result.outcome.ordinal) {
                result = requireNotNull(source.retryRun).copy(startedAt = base.startedAt)
                source.finalRewardOrigin = "TRAIT_RETRY"
            }
            result = result.copy(durationMillis = plus(base.durationMillis, base.durationMillis / 5L))
            activate(state, "E03", source.key + ":retry", base.startedAt, AdventureTraitEffectKind.RETRY,
                time = base.durationMillis / 5L, name = "${base.eventId}:${alternate.id}")
        }
        if (base.outcome != AdventureEventOutcome.SUCCESS && result.outcome != AdventureEventOutcome.SUCCESS &&
            owns(state, "E04") && roll(state, "E04", source.key + ":moveOn", EVENT_BEHAVIOR_BASIS_POINTS)) {
            val before = result.durationMillis
            val after = scale(before, 85L).coerceAtLeast(1_000L)
            if (after != before) {
                result = result.copy(durationMillis = after)
                activate(state, "E04", source.key + ":moveOn", base.startedAt, AdventureTraitEffectKind.RETRY,
                    before, after, after - before, "${base.eventId}:${base.approachId}")
            }
        }
        val resolved = applyOutcomePace(state, source, applyContextPace(state, source, base, result))
        // Evidence follows the action the hero actually performed and the outcome shown to the
        // player. A trait-driven change may therefore reinforce or oppose later trait formation.
        source.baseEvent = resolved
        return resolved
    }

    private fun applyApproachBias(
        state: SimpleGameState,
        source: AdventureTraitSource,
        base: AdventureEventRun,
    ): AdventureEventRun {
        val definition = AdventureEventEngine.definition(base.eventId)
        val baseline = definition.approaches.firstOrNull { it.id == base.approachId } ?: return base
        val passedBiases = approachBiases.filter { (traitId, signal) ->
            owns(state, traitId) && signal !in baseline.behaviorSignals &&
                definition.approaches.any { signal in it.behaviorSignals } &&
                roll(state, traitId, source.key + ":approach", EVENT_BEHAVIOR_BASIS_POINTS)
        }
        if (passedBiases.isEmpty()) return base
        val scores = definition.approaches.associateWith { approach ->
            passedBiases.count { (_, signal) -> signal in approach.behaviorSignals }
        }
        val bestScore = scores.values.maxOrNull() ?: return base
        if (bestScore <= 0) return base
        val candidates = scores.filterValues { it == bestScore }.keys.sortedBy { it.id }
        val selected = candidates[random(state.adventureTraits.seed, source.key + ":biasedApproach", candidates.size)]
        if (selected.id == baseline.id) return base
        val changed = withApproach(state, base, selected, source.key + ":biasedOutcome:${selected.id}")
        source.finalRewardOrigin = "TRAIT_APPROACH"
        passedBiases.filter { (_, signal) -> signal in selected.behaviorSignals }.forEach { (traitId, _) ->
            activate(state, traitId, source.key + ":approach", base.startedAt, AdventureTraitEffectKind.DIALOGUE,
                definition.approaches.indexOf(baseline).toLong(), definition.approaches.indexOf(selected).toLong(),
                name = "${base.eventId}:${selected.id}")
        }
        return changed
    }

    private fun withApproach(
        state: SimpleGameState,
        base: AdventureEventRun,
        approach: AdventureEventApproach,
        key: String,
    ): AdventureEventRun {
        val success = AdventureEventEngine.successBasisPoints(state.hero.stats, base.heroLevel, approach)
        val partial = minOf(2_500, 9_500 - success)
        val outcomeRoll = random(state.adventureTraits.seed, key)
        val outcome = when {
            outcomeRoll < success -> AdventureEventOutcome.SUCCESS
            outcomeRoll < success + partial -> AdventureEventOutcome.PARTIAL
            else -> AdventureEventOutcome.FAILURE
        }
        val rewarded = if (outcome == base.outcome) base else AdventureEventEngine.withOutcome(
            base, outcome, seedFor(state, key + ":reward"),
        )
        return rewarded.copy(
            approachId = approach.id,
            primaryStat = approach.primaryStat,
            secondaryStat = approach.secondaryStat,
            primaryValue = AdventureEventEngine.stat(state.hero.stats, approach.primaryStat),
            secondaryValue = AdventureEventEngine.stat(state.hero.stats, approach.secondaryStat),
            successBasisPoints = success,
            partialBasisPoints = partial,
            roll = outcomeRoll,
        )
    }

    private fun applyContextPace(
        state: SimpleGameState,
        source: AdventureTraitSource,
        base: AdventureEventRun,
        result: AdventureEventRun,
    ): AdventureEventRun {
        val traitId = listOf("T01", "T02").firstOrNull { owns(state, it) } ?: return result
        if (!roll(state, traitId, source.key + ":contextPace", EVENT_CONTEXT_PACE_BASIS_POINTS)) return result
        val town = base.context == AdventureEventContext.TOWN_RETURN
        val comfortable = (traitId == "T01" && town) || (traitId == "T02" && !town)
        val before = result.durationMillis
        val after = scale(before, if (comfortable) 90L else 110L).coerceAtLeast(1_000L)
        if (after == before) return result
        activate(state, traitId, source.key + ":contextPace", base.startedAt, AdventureTraitEffectKind.DIALOGUE,
            before, after, after - before, base.context.name)
        return result.copy(durationMillis = after)
    }

    private fun applyOutcomePace(
        state: SimpleGameState,
        source: AdventureTraitSource,
        result: AdventureEventRun,
    ): AdventureEventRun {
        val traitId = listOf("E05", "E06").firstOrNull { owns(state, it) } ?: return result
        if (!roll(state, traitId, source.key + ":outcomePace", TRADEOFF_EFFECT_BASIS_POINTS)) return result
        val success = result.outcome == AdventureEventOutcome.SUCCESS
        val favorable = (traitId == "E05") == success
        val before = result.durationMillis
        val after = scale(before, if (favorable) 90L else 110L).coerceAtLeast(1_000L)
        if (after == before) return result
        activate(
            state, traitId, source.key + ":outcomePace", result.startedAt,
            AdventureTraitEffectKind.DIALOGUE, before, after, after - before, result.outcome.name,
        )
        return result.copy(durationMillis = after)
    }

    fun observeEvent(state: SimpleGameState, at: Long) {
        val source = state.adventureTraits.source ?: return
        val run = source.baseEvent ?: return
        val definition = AdventureEventEngine.definition(run.eventId)
        val approach = definition.approaches.firstOrNull { it.id == run.approachId } ?: return
        val signals = approach.behaviorSignals
        val positive = mutableSetOf<String>()
        val negative = mutableSetOf<String>()
        signals.forEach { signal ->
            signalTraits[signal]?.let { (supportingTrait, opposingTrait) ->
                positive += supportingTrait
                negative += opposingTrait
            }
        }
        val outcomeSuccess = run.outcome == AdventureEventOutcome.SUCCESS
        positive += if (outcomeSuccess) "E05" else "E06"
        negative += if (outcomeSuccess) "E06" else "E05"
        if (AdventureBehaviorSignal.TAKE_ALL in signals) state.adventureTraits.prerequisites += "exploration"
        if (AdventureBehaviorSignal.PREPARE_THOROUGHLY in signals) state.adventureTraits.prerequisites += "packing"
        observe(
            state = state,
            key = source.key + ":base",
            context = "${run.context.name}:${definition.storyFamily}",
            positive = positive,
            negative = negative,
            at = at,
            reason = "adventure:signal",
        )
    }

    fun planRelationship(state: SimpleGameState, run: AdventureRelationshipRun) {
        val source = beginSource(state, "relationship", run.sceneId, run.startedAt)
        source.baseRelationship = run
        source.relationshipDelta = AdventureRelationshipEngine.effectiveScoreDelta(run)
        listOf("R05", "R06").firstOrNull { owns(state, it) }?.let { traitId ->
            val before = source.relationshipDelta ?: 0
            if (before != 0 && roll(
                    state,
                    traitId,
                    source.key + ":tone",
                    RELATIONSHIP_TONE_BASIS_POINTS,
                )
            ) {
                val direction = before.compareTo(0)
                val after = when (traitId) {
                    "R05" -> before + direction
                    else -> before - direction
                }.coerceIn(-200, 200)
                if (after != before) {
                    source.relationshipDelta = after
                    activate(
                        state,
                        traitId,
                        source.key + ":tone",
                        run.startedAt,
                        AdventureTraitEffectKind.RELATIONSHIP,
                        before.toLong(),
                        after.toLong(),
                    )
                }
            }
        }
        val withoutTrait = (run.successBasisPoints - run.traitBasisPointModifier).coerceIn(1_500, 8_500)
        run.influentialTraitIds.forEach { id ->
            activate(
                state,
                id,
                source.key + ":influence:$id",
                run.startedAt,
                AdventureTraitEffectKind.RELATIONSHIP,
                withoutTrait.toLong(),
                run.successBasisPoints.toLong(),
            )
        }
    }

    fun observeRelationship(state: SimpleGameState, at: Long) {
        val source = state.adventureTraits.source ?: return
        val run = source.baseRelationship ?: return
        val positive = mutableSetOf<String>(); val negative = mutableSetOf<String>()
        AdventureRelationshipEngine.behaviorSignals(run.sceneId, run.approachId).forEach { signal ->
            signalTraits[signal]?.let { (supportingTrait, opposingTrait) ->
                positive += supportingTrait
                negative += opposingTrait
            }
        }
        observe(state, source.key + ":base", "relationship:${run.sceneId}", positive, negative, at, "relationship:${run.sceneId}")
    }

    fun nextRewardKey(state: SimpleGameState, origin: String = "PRIMARY"): String {
        val source = requireNotNull(state.adventureTraits.source)
        if (origin != "PRIMARY") {
            source.derivedRewardSequence++
            return "${source.key}:derived:${source.derivedRewardSequence}"
        }
        source.rewardSequence++
        return "${source.key}:reward:${source.rewardSequence}"
    }

    fun omitTrophy(state: SimpleGameState, key: String, rarity: String, name: String, at: Long): Boolean {
        if (rarity != "일반" || !owns(state, "L02") || !roll(state, "L02", key, 10)) return false
        state.adventureTraits.source?.let { it.itemOmitted = true; it.resultTimePercent -= 15 }
        activate(state, "L02", key, at, AdventureTraitEffectKind.OMITTED_ITEM, 1, 0, name = name)
        return true
    }

    fun observeEquipment(state: SimpleGameState, key: String, slot: EquipmentSlot, selectedPower: Long, upgraded: Boolean, at: Long) {
        observe(state, key, "equipment:${slot.name}", setOf("L05"), setOf("L06"), at, "equipment:compare")
        val ordered = state.equipment.sortedWith(compareBy<EquippedItem> { it.power }.thenBy { it.slot.ordinal })
        if (ordered.size >= 2 && ordered.first().power != ordered.last().power) {
            when (slot) {
                ordered.first().slot -> observe(state, key + ":focus", "equipment-weak:${slot.name}",
                    setOf("S03"), setOf("S04"), at, "equipment:weakest")
                ordered.last().slot -> observe(state, key + ":focus", "equipment-strong:${slot.name}",
                    setOf("S04"), setOf("S03"), at, "equipment:strongest")
                else -> Unit
            }
        }
        if (upgraded) state.adventureTraits.stableEquipmentCombats = 0
        if ((slot == EquipmentSlot.WEAPON || upgraded) && state.adventureTraits.equipmentOrigins[slot]?.startsWith("TRAIT_") != true) state.adventureTraits.weaponUses =
            (state.adventureTraits.weaponUses + AdventureTraitWeaponUse(key, slot, selectedPower)).takeLast(24)
    }

    /** L06 only declines a marginal numerical upgrade; larger gains always remain eligible. */
    fun keepFamiliarGear(
        state: SimpleGameState,
        key: String,
        slot: EquipmentSlot,
        currentPower: Long,
        candidatePower: Long,
        at: Long,
    ): Boolean {
        if (!owns(state, "L06") || currentPower <= 0L || candidatePower <= currentPower) return false
        val largestMarginalPower = plus(currentPower, maxOf(1L, currentPower / 20L))
        if (candidatePower > largestMarginalPower || !roll(state, "L06", "$key:familiar", 100)) return false
        activate(
            state = state,
            id = "L06",
            key = "$key:familiar",
            at = at,
            kind = AdventureTraitEffectKind.APPRAISAL,
            before = currentPower,
            after = candidatePower,
            name = slot.name,
        )
        return true
    }

    fun rewardTrace(state: SimpleGameState, key: String, origin: String, itemId: Long, name: String,
        slot: EquipmentSlot?, rarity: String, originalPower: Long?, finalPower: Long?, granted: Boolean, equipped: Boolean) {
        val traits = state.adventureTraits
        traits.rewardTraceSequence++
        traits.recentRewardTraces = (traits.recentRewardTraces + AdventureTraitRewardTrace(traits.rewardTraceSequence,
            key, origin, itemId, name, slot, rarity, originalPower, finalPower, granted, equipped, state.hero.level)).takeLast(128)
        if (granted) traits.primaryRewardOrigins = (traits.primaryRewardOrigins + (itemId to origin)).filterKeys { id ->
            state.inventory.any { it.id == id }
        }
        if (granted && origin == "PRIMARY" && traits.source?.kind == "combat")
            traits.primaryRewardFamilies = (traits.primaryRewardFamilies + (itemId to traits.source!!.contextKey))
                .filterKeys { id -> state.inventory.any { it.id == id } }
    }

    fun beginReturn(state: SimpleGameState, at: Long) {
        val traits = state.adventureTraits
        traits.temporaryBagSlots = 0L
        val carriedIds = state.inventory.mapTo(mutableSetOf()) { it.id }
        traits.familiarHeldItemIds = traits.familiarHeldItemIds.filterTo(mutableSetOf()) { it in carriedIds }
        beginSource(state, "town", "town:${state.totalReturns}", at)
        traits.saleBatch = null; traits.shopVisit = null
        val original = state.inventory.filter { traits.primaryRewardOrigins[it.id] != "TRAIT_EXTRA" }
        traits.carriedEvidenceContexts = original.map { if (it.kind == "장비") "equipment" else "trophy" }.distinct()
        traits.carriedOriginalFamilies = original.filter { it.kind != "장비" }.mapNotNull { traits.primaryRewardFamilies[it.id] }.distinct()
    }

    fun beginSale(state: SimpleGameState, values: List<Pair<Long, Long>>, at: Long): AdventureTraitSaleBatch {
        val key = requireNotNull(state.adventureTraits.source).key + ":sale"
        val id = listOf("S01", "S02").firstOrNull { owns(state, it) }
        val fired = id != null && roll(state, id, key, 300)
        fun target(value: Long): Long = if (!fired) value else if (id == "S01") plus(value, value / 20L) else value - value / 20L
        var cumulative = 0L
        val paid = values.map { (_, value) ->
            val before = target(cumulative)
            cumulative = plus(cumulative, value)
            target(cumulative) - before
        }
        var duration = if (!fired) 100 else if (id == "S01") 110 else 85
        val returnTrait = listOf("T05", "T06").firstOrNull { owns(state, it) }
        if (returnTrait != null && roll(state, returnTrait, key + ":returnPace", TRADEOFF_EFFECT_BASIS_POINTS)) {
            val before = duration
            duration = scale(duration.toLong(), if (returnTrait == "T05") 90L else 110L).toInt().coerceAtLeast(1)
            activate(state, returnTrait, key + ":returnPace", at, AdventureTraitEffectKind.DIALOGUE,
                before.toLong(), duration.toLong(), duration.toLong() - before)
        }
        val batch = AdventureTraitSaleBatch(key, values.map { it.first }, values.map { it.second }, paid, duration,
            if (fired) id.orEmpty() else "")
        state.adventureTraits.saleBatch = batch
        if (fired) activate(state, id.orEmpty(), key, at, AdventureTraitEffectKind.SALE, cumulative, paid.sum())
        return batch
    }

    fun finishSale(state: SimpleGameState, at: Long) {
        val traits = state.adventureTraits
        val batch = traits.saleBatch ?: return
        if (batch.nextIndex < batch.itemIds.size) return
        traits.prerequisites += "sale"
        val positive = mutableSetOf("S01")
        val negative = mutableSetOf<String>()
        if (traits.carriedEvidenceContexts.size >= 2) { positive += "L04"; traits.prerequisites += "carried" }
        else negative += "L04"
        observe(state, batch.sourceKey + ":complete", "trade:sale", positive, negative, at, "trade:sale")
        val family = traits.carriedOriginalFamilies.firstOrNull { it !in traits.soldOriginalFamilies }
        if (family != null) {
            observe(state, batch.sourceKey + ":carried", "loot:$family", setOf("L01"), at = at, reason = "adventure:carried")
            traits.soldOriginalFamilies = (traits.soldOriginalFamilies + traits.carriedOriginalFamilies).distinct().takeLast(32)
        }
        traits.familiarHeldItemIds = emptySet()
        traits.saleBatch = null
    }

    fun beginShop(state: SimpleGameState): AdventureTraitShopVisit {
        state.adventureTraits.shopVisit?.let { return it }
        val key = requireNotNull(state.adventureTraits.source).key + ":shop"
        // Every owned trait receives its own recorded opportunity even if an earlier trait fires.
        // Only one extra review is selected so the existing one-extra-offer economy remains intact.
        val traitId = listOf("S03", "S04", "S05", "S06")
            .filter { owns(state, it) }
            .filter { roll(state, it, key, if (it == "S03" || it == "S04") SHOP_FOCUS_BASIS_POINTS else 300) }
            .firstOrNull()
        val extra = traitId != null
        val armorSlots = listOf(EquipmentSlot.BODY, EquipmentSlot.HEAD, EquipmentSlot.HANDS, EquipmentSlot.FEET)
        val extraSlot = when {
            !extra -> null
            traitId == "S06" -> armorSlots[random(state.adventureTraits.seed, "$key:armorSlot", armorSlots.size)]
            traitId == "S03" -> state.equipment.minWithOrNull(compareBy<EquippedItem> { it.power }.thenBy { it.slot.ordinal })?.slot
            traitId == "S04" -> state.equipment.maxWithOrNull(compareBy<EquippedItem> { it.power }.thenByDescending { it.slot.ordinal })?.slot
            else -> EquipmentSlot.WEAPON
        }
        return AdventureTraitShopVisit(
            sourceKey = key,
            extraAllowed = extra,
            purchasesBefore = state.totalEquipmentPurchases,
            extraTraitId = traitId.takeIf { extra }.orEmpty(),
            extraSlot = extraSlot,
        ).also { state.adventureTraits.shopVisit = it }
    }

    fun depart(state: SimpleGameState, at: Long, baseMillis: Long): Long {
        val traits = state.adventureTraits
        traits.shopVisit?.let { visit ->
            if (visit.basePurchases == 0L) {
                traits.prerequisites += "empty_visit"
                observe(state, visit.sourceKey + ":complete", "trade:empty:${state.totalReturns}", setOf("S02"), setOf("S01"), at, "trade:empty")
            }
            val methodical = visit.basePurchases > 0L
            observe(
                state,
                visit.sourceKey + ":returnStyle",
                "return-style:${state.totalReturns}",
                setOf(if (methodical) "T05" else "T06"),
                setOf(if (methodical) "T06" else "T05"),
                at,
                "trade:return-style",
            )
        }
        val source = beginSource(state, "departure", "departure", at)
        traits.temporaryBagSlots = 0L
        var result = baseMillis
        val packingTrait = listOf("L03", "L04").firstOrNull { owns(state, it) }
        if (packingTrait != null && roll(state, packingTrait, source.key, 100)) {
            result = if (packingTrait == "L03") {
                traits.temporaryBagSlots = -1L
                val shortened = scale(result, 85L).coerceAtLeast(1L)
                activate(state, packingTrait, source.key, at, AdventureTraitEffectKind.BAG_CAPACITY,
                    0L, -1L, shortened - result)
                shortened
            } else {
                traits.temporaryBagSlots = 3L
                val extra = result * 15L / 100L
                activate(state, packingTrait, source.key, at, AdventureTraitEffectKind.BAG_CAPACITY, 0, 3, extra)
                plus(result, extra)
            }
        }
        val returnTrait = listOf("T05", "T06").firstOrNull { owns(state, it) }
        if (returnTrait != null && roll(state, returnTrait, source.key + ":returnPace", TRADEOFF_EFFECT_BASIS_POINTS)) {
            val before = result
            result = scale(result, if (returnTrait == "T06") 90L else 110L).coerceAtLeast(1L)
            activate(state, returnTrait, source.key + ":returnPace", at, AdventureTraitEffectKind.DIALOGUE,
                before, result, result - before)
        }
        return result
    }

    fun resultMillis(state: SimpleGameState, base: Long): Long =
        scale(base, state.adventureTraits.source?.resultTimePercent?.coerceAtLeast(1)?.toLong() ?: 100L).coerceAtLeast(1L)

    fun pause(state: SimpleGameState, millis: Long) {
        val traits = state.adventureTraits
        traits.source = traits.source?.copy(startedAt = plus(traits.source!!.startedAt, millis),
            baseEvent = traits.source!!.baseEvent?.let { it.copy(startedAt = plus(it.startedAt, millis)) },
            retryRun = traits.source!!.retryRun?.let { it.copy(startedAt = plus(it.startedAt, millis)) },
            baseRelationship = traits.source!!.baseRelationship?.let { it.copy(startedAt = plus(it.startedAt, millis)) })
        traits.formationStartedAtByTrait = shiftAnchors(traits.formationStartedAtByTrait, millis)
        traits.lastFormationAt = traits.lastFormationAt?.let { plus(it, millis) }
        traits.stableStartedAtByTrait = shiftAnchors(traits.stableStartedAtByTrait, millis)
        traits.oppositionStartedAtByTrait = shiftAnchors(traits.oppositionStartedAtByTrait, millis)
        traits.weakenedStartedAtByTrait = shiftAnchors(traits.weakenedStartedAtByTrait, millis)
    }

    private fun clearOwnedLifecycleAnchors(traits: AdventureTraitState, id: String) {
        traits.stableStartedAtByTrait = traits.stableStartedAtByTrait - id
        traits.oppositionStartedAtByTrait = traits.oppositionStartedAtByTrait - id
        traits.weakenedStartedAtByTrait = traits.weakenedStartedAtByTrait - id
    }

    private fun shiftAnchors(values: Map<String, Long>, millis: Long): Map<String, Long> =
        if (millis <= 0L) values else values.mapValues { (_, startedAt) -> plus(startedAt, millis) }

    private fun elapsedAtLeast(at: Long, startedAt: Long?, required: Long): Boolean =
        startedAt != null && at >= startedAt && at - startedAt >= required

    private fun increment(values: Map<String, Long>, id: String) = values + (id to plus(values[id] ?: 0L, 1L))
    fun scale(value: Long, percent: Long): Long = plus(times(value / 100L, percent), value % 100L * percent / 100L)
    private fun times(left: Long, right: Long) = if (left != 0L && right > Long.MAX_VALUE / left) Long.MAX_VALUE else left * right
    private fun plus(left: Long, right: Long) = if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
    private fun mix(value: Long): Long {
        var mixed = value
        mixed = (mixed xor (mixed ushr 30)) * -4_658_895_280_553_007_687L
        mixed = (mixed xor (mixed ushr 27)) * -7_723_592_293_110_705_685L
        return mixed xor (mixed ushr 31)
    }
}
