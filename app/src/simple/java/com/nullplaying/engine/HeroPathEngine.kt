package com.nullplaying.engine

import com.nullplaying.model.BattleHeroClass
import com.nullplaying.model.HERO_PATH_CATALOG_VERSION
import com.nullplaying.model.HERO_PATH_COUNTER_RULES_VERSION
import com.nullplaying.model.HERO_PATH_MAX_ACTIVE_TRAITS
import com.nullplaying.model.HERO_PATH_MAX_CLASS_CHARGE
import com.nullplaying.model.HERO_PATH_MAX_COMBAT_POINTS
import com.nullplaying.model.HERO_PATH_MILESTONE_INTERVAL
import com.nullplaying.model.HERO_PATH_OFFER_COUNT
import com.nullplaying.model.HERO_PATH_SCHEMA_VERSION
import com.nullplaying.model.HERO_PATH_TREE_VERSION
import com.nullplaying.model.HeroPathAllocationTarget
import com.nullplaying.model.HeroPathBattleNodeSnapshot
import com.nullplaying.model.HeroPathBattleSnapshot
import com.nullplaying.model.HeroPathBranch
import com.nullplaying.model.HeroPathChargeTransition
import com.nullplaying.model.HeroPathChoiceStance
import com.nullplaying.model.HeroPathChoiceType
import com.nullplaying.model.HeroPathClassCharge
import com.nullplaying.model.HeroPathMilestoneToken
import com.nullplaying.model.HeroPathMutation
import com.nullplaying.model.HeroPathMutationStatus
import com.nullplaying.model.HeroPathNodeType
import com.nullplaying.model.HeroPathOfferChoice
import com.nullplaying.model.HeroPathState
import com.nullplaying.model.HeroPathTraitProgress
import java.security.MessageDigest

/** Pure Arena Talent Tree V2 transitions. It never reads gameplay RNG, time, or mutable battle state. */
object HeroPathEngine {
    fun reconcilePointGrants(state: HeroPathState, heroLevel: Long): HeroPathState {
        val current = normalize(state)
        val milestone = (heroLevel.coerceAtLeast(0L) / HERO_PATH_MILESTONE_INTERVAL) * HERO_PATH_MILESTONE_INTERVAL
        val last = maxOf(current.lastGrantedMilestone, milestone)
        return normalize(current.copy(
            earnedPoints = maxOf(current.earnedPoints, current.spentPoints, last / HERO_PATH_MILESTONE_INTERVAL),
            lastGrantedMilestone = last,
        ))
    }

    /** V1 numeric traits have no semantic V2 mapping: refund their spent points exactly once. */
    fun migrateV1Allocation(state: HeroPathState): HeroPathState = normalize(state)

    fun applyAllocation(state: HeroPathState, target: HeroPathAllocationTarget): HeroPathMutation {
        val current = normalize(state)
        if (target.expectedRevision != current.revision) {
            return HeroPathMutation(HeroPathMutationStatus.STALE_REVISION, current)
        }
        val ranks = target.nodeRanks.filterValues { it > 0 }
        if (ranks.size != target.nodeRanks.size || ranks.keys.any { it !in HeroPathCatalog.byNodeId }) {
            return HeroPathMutation(HeroPathMutationStatus.INVALID_ALLOCATION, current)
        }
        val definitions = ranks.keys.map { HeroPathCatalog.byNodeId.getValue(it) }
        if (definitions.any { it.heroClassAffinity != current.heroClass }) {
            return HeroPathMutation(HeroPathMutationStatus.INVALID_ALLOCATION, current)
        }
        if (ranks.any { (id, rank) -> rank > HeroPathCatalog.byNodeId.getValue(id).maxRank }) {
            return HeroPathMutation(HeroPathMutationStatus.INVALID_ALLOCATION, current)
        }
        val spent = ranks.values.sum()
        if (spent > HERO_PATH_MAX_COMBAT_POINTS) {
            return HeroPathMutation(HeroPathMutationStatus.POINT_CAP_EXCEEDED, current)
        }
        if (spent.toLong() > current.earnedPoints) {
            return HeroPathMutation(HeroPathMutationStatus.INSUFFICIENT_POINTS, current)
        }
        if (definitions.any { it.minimumLevel > current.lastGrantedMilestone }) {
            return HeroPathMutation(HeroPathMutationStatus.INVALID_ALLOCATION, current)
        }
        val groupConflict = definitions.filter { it.choiceGroupId.isNotBlank() }
            .groupBy { it.choiceGroupId }.any { (_, members) -> members.size > 1 }
        if (groupConflict) return HeroPathMutation(HeroPathMutationStatus.CHOICE_CONFLICT, current)

        val branchPoints = ranks.entries.groupBy(
            { HeroPathCatalog.byNodeId.getValue(it.key).branch }, { it.value },
        ).mapValues { (_, values) -> values.sum() }
        val invalidTier = ranks.any { (id, rank) ->
            val node = HeroPathCatalog.byNodeId.getValue(id)
            val branchTotal = branchPoints.getOrDefault(node.branch, 0)
            val gatePoints = if (node.nodeType == HeroPathNodeType.CORE) branchTotal - rank else branchTotal
            gatePoints < node.requiredBranchInvestments ||
                node.prerequisiteTraitIds.any { ranks.getOrDefault(it, 0) <= 0 }
        }
        if (invalidTier) return HeroPathMutation(HeroPathMutationStatus.INVALID_ALLOCATION, current)

        val cores = definitions.filter { it.nodeType == HeroPathNodeType.CORE }.map { it.nodeId }
        if (cores.size > 1) return HeroPathMutation(HeroPathMutationStatus.INVALID_ALLOCATION, current)
        val selectedCore = target.activeCoreNodeId
        if ((cores.singleOrNull().orEmpty() != selectedCore) ||
            (selectedCore.isNotBlank() && ranks.getOrDefault(selectedCore, 0) != 1)) {
            return HeroPathMutation(HeroPathMutationStatus.INVALID_ALLOCATION, current)
        }
        // V2 represents A/B as two physical nodes in one exclusive group; no hidden choice payload exists.
        if (target.nodeChoices.isNotEmpty()) {
            return HeroPathMutation(HeroPathMutationStatus.INVALID_ALLOCATION, current)
        }

        val previous = current.traits.associateBy { it.traitId }
        val progress = ranks.entries.sortedBy { it.key }.map { (id, rank) ->
            val node = HeroPathCatalog.byNodeId.getValue(id)
            HeroPathTraitProgress(
                traitId = id,
                rank = rank,
                unlockedAtLevel = previous[id]?.unlockedAtLevel ?: node.minimumLevel,
                selectedChoiceId = target.nodeChoices[id].orEmpty(),
            )
        }
        val activeCompat = buildList {
            if (selectedCore.isNotBlank()) add(selectedCore)
            progress.map { it.traitId }.filter { it != selectedCore }.take(HERO_PATH_MAX_ACTIVE_TRAITS - size).forEach(::add)
        }
        val updated = current.copy(
            revision = incrementRevision(current.revision), traits = progress,
            activeTraitIds = activeCompat, activeCoreTraitId = selectedCore,
        )
        return HeroPathMutation(HeroPathMutationStatus.APPLIED, normalize(updated))
    }

    /** Named V2 entry point retained separately from the V1-compatible [applyDraft]. */
    fun applyAllocationTarget(state: HeroPathState, target: HeroPathAllocationTarget): HeroPathMutation =
        applyAllocation(state, target)

    /** Compatibility helper: each id means +1 rank, committed through the atomic allocator. */
    fun applyDraft(state: HeroPathState, traitIds: List<String>): HeroPathMutation {
        val current = normalize(state)
        if (traitIds.isEmpty() || traitIds.distinct().size != traitIds.size) {
            return HeroPathMutation(HeroPathMutationStatus.INVALID_ALLOCATION, current)
        }
        val ranks = current.traits.associate { it.traitId to it.rank }.toMutableMap()
        traitIds.forEach { ranks[it] = (ranks[it] ?: 0) + 1 }
        val core = ranks.keys.singleOrNull { HeroPathCatalog.byNodeId[it]?.nodeType == HeroPathNodeType.CORE }
            ?: current.activeCoreTraitId
        return applyAllocation(current, HeroPathAllocationTarget(current.revision, ranks, activeCoreNodeId = core))
    }

    fun resetAllocation(state: HeroPathState): HeroPathMutation {
        val current = normalize(state)
        if (current.traits.isEmpty()) return HeroPathMutation(HeroPathMutationStatus.NOTHING_TO_RESET, current)
        return HeroPathMutation(HeroPathMutationStatus.APPLIED, normalize(current.copy(
            revision = incrementRevision(current.revision), traits = emptyList(),
            activeTraitIds = emptyList(), activeCoreTraitId = "",
        )))
    }

    fun issueMilestone(state: HeroPathState, heroId: String, milestoneLevel: Long, generationSeed: Long): HeroPathMutation {
        val current = reconcilePointGrants(state, milestoneLevel)
        if (!validMilestone(milestoneLevel)) return HeroPathMutation(HeroPathMutationStatus.INVALID_MILESTONE, current)
        current.milestoneTokens.firstOrNull { it.milestoneLevel == milestoneLevel }?.let {
            return HeroPathMutation(HeroPathMutationStatus.TOKEN_ALREADY_ISSUED, current, it)
        }
        val candidates = quickPickCandidates(current, milestoneLevel)
        val ordered = candidates.sortedWith(compareBy({ stableHash("$heroId:$milestoneLevel:$generationSeed:${it.nodeId}") }, { it.nodeId }))
        val branchLeaders = HeroPathCatalog.branchesFor(current.heroClass).mapNotNull { branch ->
            ordered.firstOrNull { it.branch == branch.branch }
        }
        val chosen = (branchLeaders + ordered).distinctBy { it.nodeId }.take(HERO_PATH_OFFER_COUNT)
        if (chosen.size != HERO_PATH_OFFER_COUNT) return HeroPathMutation(HeroPathMutationStatus.INVALID_MILESTONE, current)
        val token = HeroPathMilestoneToken(
            tokenId = "arena-path:$HERO_PATH_CATALOG_VERSION:$heroId:$milestoneLevel",
            milestoneLevel = milestoneLevel, generationSeed = generationSeed,
            offers = chosen.map { node ->
                val rank = current.traits.firstOrNull { it.traitId == node.nodeId }?.rank ?: 0
                HeroPathOfferChoice(
                    offerId = "offer:${stableHash("$heroId:$milestoneLevel:$generationSeed:${node.nodeId}").toULong().toString(16)}",
                    type = if (rank > 0) HeroPathChoiceType.RANK_UP else if (node.nodeType == HeroPathNodeType.CORE) HeroPathChoiceType.CHANGE_CORE else HeroPathChoiceType.UNLOCK,
                    traitId = node.nodeId,
                    replacementTraitId = if (node.nodeType == HeroPathNodeType.CORE) current.activeCoreTraitId else "",
                )
            },
        )
        return HeroPathMutation(HeroPathMutationStatus.APPLIED, current.copy(milestoneTokens = current.milestoneTokens + token), token)
    }

    fun resolveMilestone(state: HeroPathState, tokenId: String, offerId: String): HeroPathMutation {
        val current = normalize(state)
        val index = current.milestoneTokens.indexOfFirst { it.tokenId == tokenId }
        if (index < 0) return HeroPathMutation(HeroPathMutationStatus.UNKNOWN_TOKEN, current)
        val token = current.milestoneTokens[index]
        if (token.resolved) return HeroPathMutation(HeroPathMutationStatus.TOKEN_ALREADY_RESOLVED, current, token)
        val offer = token.offers.firstOrNull { it.offerId == offerId }
            ?: return HeroPathMutation(HeroPathMutationStatus.UNKNOWN_OFFER, current, token)
        val ranks = current.traits.associate { it.traitId to it.rank }.toMutableMap()
        ranks[offer.traitId] = (ranks[offer.traitId] ?: 0) + 1
        val node = HeroPathCatalog.byNodeId[offer.traitId]
            ?: return HeroPathMutation(HeroPathMutationStatus.INVALID_OFFER, current, token)
        val conflicting = if (node.choiceGroupId.isBlank()) emptyList() else ranks.keys.filter {
            it != node.nodeId && HeroPathCatalog.byNodeId[it]?.choiceGroupId == node.choiceGroupId
        }
        conflicting.forEach(ranks::remove)
        val activeCore = if (node.nodeType == HeroPathNodeType.CORE) node.nodeId else current.activeCoreTraitId
        val applied = applyAllocation(current, HeroPathAllocationTarget(current.revision, ranks, activeCoreNodeId = activeCore))
        if (applied.status != HeroPathMutationStatus.APPLIED) return applied.copy(token = token)
        val resolved = token.copy(selectedOfferId = offer.offerId)
        val tokens = applied.state.milestoneTokens.toMutableList().apply { set(index, resolved) }
        return applied.copy(state = applied.state.copy(milestoneTokens = tokens), token = resolved)
    }

    fun createBattleSnapshot(state: HeroPathState): HeroPathBattleSnapshot =
        createBattleSnapshot(state, emptyList())

    fun createBattleSnapshot(state: HeroPathState, ownedSkillIds: List<String>): HeroPathBattleSnapshot {
        val current = normalize(state)
        return snapshotOf(current.heroClass, current.revision, current.traits, current.activeCoreTraitId, ownedSkillIds)
    }

    fun battleSnapshotFromRanks(
        heroClass: BattleHeroClass,
        nodeRanks: Map<String, Int>,
        allocationRevision: Long = 0L,
        activeCoreNodeId: String = "",
        ownedSkillIds: List<String> = emptyList(),
    ): HeroPathBattleSnapshot = snapshotOf(
        heroClass, allocationRevision,
        nodeRanks.map { (id, rank) -> HeroPathTraitProgress(id, rank) }, activeCoreNodeId, ownedSkillIds,
    )

    fun validateBattleSnapshot(snapshot: HeroPathBattleSnapshot, expectedClass: BattleHeroClass): Boolean {
        if (snapshot.treeVersion != HERO_PATH_TREE_VERSION || snapshot.heroClass != expectedClass) return false
        if (snapshot.counterRulesVersion != HERO_PATH_COUNTER_RULES_VERSION) return false
        if (snapshot.initialClassCharge !in 0..HERO_PATH_MAX_CLASS_CHARGE) return false
        if (snapshot.ownedSkillIds.any { it.isBlank() } || snapshot.ownedSkillIds.distinct().size != snapshot.ownedSkillIds.size) return false
        if (snapshot.nodes.any { node ->
                val definition = HeroPathCatalog.byNodeId[node.nodeId]
                definition == null || definition.heroClassAffinity != expectedClass ||
                    node.rank !in 1..definition.maxRank || node.branch != definition.branch ||
                    node.effectFamily != definition.effectFamily || node.slot != definition.slot ||
                    node.effectStage != definition.effectStage
            }) return false
        if (snapshot.nodes.sumOf { it.rank } > HERO_PATH_MAX_COMBAT_POINTS) return false
        if (snapshot.nodes.map { it.nodeId }.distinct().size != snapshot.nodes.size) return false
        val expectedSpecs = snapshot.nodes.map { it.branch }.distinct().sortedBy { it.name }
        if (snapshot.specializationIds != expectedSpecs) return false
        val definitions = snapshot.nodes.map { HeroPathCatalog.byNodeId.getValue(it.nodeId) }
        if (definitions.filter { it.choiceGroupId.isNotBlank() }.groupBy { it.choiceGroupId }.any { it.value.size > 1 }) return false
        val branchPoints = snapshot.nodes.groupBy { it.branch }.mapValues { (_, nodes) -> nodes.sumOf { it.rank } }
        if (snapshot.nodes.any { snap ->
                val node = HeroPathCatalog.byNodeId.getValue(snap.nodeId)
                val gate = if (node.nodeType == HeroPathNodeType.CORE) {
                    branchPoints.getOrDefault(node.branch, 0) - snap.rank
                } else {
                    branchPoints.getOrDefault(node.branch, 0)
                }
                gate < node.requiredBranchInvestments ||
                    node.prerequisiteTraitIds.any { required -> snapshot.nodes.none { it.nodeId == required } }
            }) return false
        val cores = snapshot.nodes.filter { HeroPathCatalog.byNodeId[it.nodeId]?.nodeType == HeroPathNodeType.CORE }
        if (cores.size > 1 || cores.singleOrNull()?.nodeId.orEmpty() != snapshot.activeCoreNodeId) return false
        val (expectedDominant, expectedStance) = dominantSelection(snapshot.nodes, snapshot.activeCoreNodeId)
        if (snapshot.dominantBranch != expectedDominant || snapshot.choiceStance != expectedStance) return false
        return snapshot.derivedRulesDigest == digest(snapshot.copy(derivedRulesDigest = ""))
    }

    fun normalizeClassCharge(charge: HeroPathClassCharge) = charge.copy(value = charge.value.coerceIn(0, HERO_PATH_MAX_CLASS_CHARGE))
    fun gainClassCharge(charge: HeroPathClassCharge, amount: Int = 1): HeroPathChargeTransition {
        val before = normalizeClassCharge(charge)
        val after = before.copy(value = (before.value + amount.coerceAtLeast(0)).coerceAtMost(HERO_PATH_MAX_CLASS_CHARGE))
        return HeroPathChargeTransition(after != before, before, after)
    }
    fun spendClassCharge(charge: HeroPathClassCharge, cost: Int = HERO_PATH_MAX_CLASS_CHARGE): HeroPathChargeTransition {
        val before = normalizeClassCharge(charge)
        val safe = cost.coerceAtLeast(0)
        if (before.value < safe) return HeroPathChargeTransition(false, before, before)
        val after = before.copy(value = before.value - safe)
        return HeroPathChargeTransition(true, before, after)
    }

    fun branchInvestments(state: HeroPathState, branch: HeroPathBranch): Int = normalize(state).traits.sumOf {
        if (HeroPathCatalog.byNodeId[it.traitId]?.branch == branch) it.rank else 0
    }

    fun normalize(state: HeroPathState): HeroPathState {
        val legacySpent = state.traits.filter { it.traitId.startsWith("TRAIT_") }.sumOf { it.rank.coerceAtLeast(1).toLong() }
        val needsRefund = state.catalogVersion < HERO_PATH_CATALOG_VERSION || legacySpent > 0L
        val source = if (needsRefund) emptyList() else state.traits
        val progress = source.asSequence().mapNotNull { p ->
            HeroPathCatalog.byNodeId[p.traitId]?.takeIf { it.heroClassAffinity == state.heroClass }?.let { node ->
                p.copy(rank = p.rank.coerceIn(1, node.maxRank))
            }
        }.distinctBy { it.traitId }.sortedBy { it.traitId }.toList()
        val last = state.lastGrantedMilestone.coerceAtLeast(0L)
        val refundedEarned = maxOf(state.earnedPoints, legacySpent, last / HERO_PATH_MILESTONE_INTERVAL)
        val owned = progress.map { it.traitId }.toSet()
        val core = state.activeCoreTraitId.takeIf { it in owned && HeroPathCatalog.byNodeId[it]?.nodeType == HeroPathNodeType.CORE }.orEmpty()
        val tokens = if (needsRefund) {
            state.milestoneTokens.mapIndexed { index, token ->
                if (token.resolved) token else token.copy(selectedOfferId = "v1-refund:$index")
            }
        } else {
            state.milestoneTokens
        }
        return state.copy(
            schemaVersion = HERO_PATH_SCHEMA_VERSION, catalogVersion = HERO_PATH_CATALOG_VERSION,
            treeVersion = HERO_PATH_TREE_VERSION,
            revision = if (needsRefund && !state.v1AllocationRefunded) incrementRevision(state.revision) else state.revision,
            earnedPoints = maxOf(refundedEarned, progress.sumOf { it.rank }.toLong()),
            traits = progress,
            activeTraitIds = state.activeTraitIds.filter { it in owned }.distinct().take(HERO_PATH_MAX_ACTIVE_TRAITS),
            activeCoreTraitId = core,
            milestoneTokens = tokens,
            v1AllocationRefunded = state.v1AllocationRefunded || needsRefund,
        )
    }

    private fun quickPickCandidates(state: HeroPathState, level: Long) = HeroPathCatalog.nodesFor(state.heroClass).filter { node ->
        val ranks = state.traits.associate { it.traitId to it.rank }.toMutableMap()
        val rank = ranks[node.nodeId] ?: 0
        if (rank >= node.maxRank || node.minimumLevel > level) return@filter false
        if (node.choiceGroupId.isNotBlank() && ranks.keys.any {
                it != node.nodeId && HeroPathCatalog.byNodeId[it]?.choiceGroupId == node.choiceGroupId
            }) return@filter false
        ranks[node.nodeId] = rank + 1
        val core = if (node.nodeType == HeroPathNodeType.CORE) node.nodeId else state.activeCoreTraitId
        applyAllocation(state, HeroPathAllocationTarget(state.revision, ranks, activeCoreNodeId = core)).status ==
            HeroPathMutationStatus.APPLIED
    }

    private fun snapshotOf(
        heroClass: BattleHeroClass, revision: Long, traits: List<HeroPathTraitProgress>, core: String,
        ownedSkillIds: List<String>,
    ): HeroPathBattleSnapshot {
        val nodes = traits.mapNotNull { progress ->
            HeroPathCatalog.byNodeId[progress.traitId]?.takeIf { it.heroClassAffinity == heroClass }?.let { node ->
                HeroPathBattleNodeSnapshot(node.nodeId, progress.rank.coerceIn(1, node.maxRank), progress.selectedChoiceId,
                    node.branch, node.effectFamily, node.slot, node.effectStage)
            }
        }.sortedBy { it.nodeId }
        val actualCore = core.takeIf { id -> nodes.any { it.nodeId == id } && HeroPathCatalog.byNodeId[id]?.nodeType == HeroPathNodeType.CORE }.orEmpty()
        val (dominantBranch, choiceStance) = dominantSelection(nodes, actualCore)
        val raw = HeroPathBattleSnapshot(
            treeVersion = HERO_PATH_TREE_VERSION, allocationRevision = revision, heroClass = heroClass,
            specializationIds = nodes.map { it.branch }.distinct().sortedBy { it.name }, nodes = nodes,
            activeCoreNodeId = actualCore,
            ownedSkillIds = ownedSkillIds.filter { it.isNotBlank() }.distinct().sorted(),
            initialClassCharge = 0,
            counterRulesVersion = HERO_PATH_COUNTER_RULES_VERSION,
            dominantBranch = dominantBranch,
            choiceStance = choiceStance,
        )
        return raw.copy(derivedRulesDigest = digest(raw))
    }

    private fun digest(snapshot: HeroPathBattleSnapshot): String {
        val canonical = buildString {
            append(snapshot.treeVersion).append('|').append(snapshot.allocationRevision).append('|')
            append(snapshot.heroClass.name).append('|').append(snapshot.activeCoreNodeId).append('|').append(snapshot.initialClassCharge)
            append('|').append("counter:").append(snapshot.counterRulesVersion)
                .append(':').append(snapshot.dominantBranch?.name.orEmpty())
                .append(':').append(snapshot.choiceStance.name)
            snapshot.specializationIds.sortedBy { it.name }.forEach { append('|').append("spec:").append(it.name) }
            snapshot.ownedSkillIds.sorted().forEach { append('|').append("skill:").append(it) }
            snapshot.nodes.sortedBy { it.nodeId }.forEach {
                append('|').append(it.nodeId).append(':').append(it.rank).append(':').append(it.selectedChoiceId)
                    .append(':').append(it.branch.name).append(':').append(it.effectFamily.name)
                    .append(':').append(it.slot.name).append(':').append(it.effectStage.name)
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun dominantSelection(
        nodes: List<HeroPathBattleNodeSnapshot>,
        activeCoreNodeId: String,
    ): Pair<HeroPathBranch?, HeroPathChoiceStance> {
        val dominant = nodes.firstOrNull { it.nodeId == activeCoreNodeId }?.branch
            ?: nodes.groupBy { it.branch }
                .map { (branch, branchNodes) -> branch to branchNodes.sumOf { it.rank } }
                .sortedWith(compareByDescending<Pair<HeroPathBranch, Int>> { it.second }.thenBy { it.first.name })
                .firstOrNull()?.first
        val dominantNodes = nodes.filter { it.branch == dominant }
        val stance = when {
            dominantNodes.any { it.slot == com.nullplaying.model.HeroPathNodeSlot.CHOICE_A } -> HeroPathChoiceStance.A
            dominantNodes.any { it.slot == com.nullplaying.model.HeroPathNodeSlot.CHOICE_B } -> HeroPathChoiceStance.B
            else -> HeroPathChoiceStance.NONE
        }
        return dominant to stance
    }

    private fun validMilestone(level: Long) = level >= HERO_PATH_MILESTONE_INTERVAL && level % HERO_PATH_MILESTONE_INTERVAL == 0L
    private fun incrementRevision(value: Long) = if (value == Long.MAX_VALUE) Long.MAX_VALUE else value + 1L
    private fun stableHash(value: String): Long {
        var hash = -0x340d631b7bdddcdbL
        value.forEach { char -> hash = (hash xor char.code.toLong()) * 0x100000001b3L }
        return hash
    }
}
