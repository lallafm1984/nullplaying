package com.nullplaying.engine.arena

import com.nullplaying.model.HeroClass
import kotlinx.serialization.Serializable

@Serializable
data class ArenaSkillAllocation(
    val nodeId: String,
    val rank: Int,
)

@Serializable
data class ArenaSkillTreeState(
    val heroClass: HeroClass,
    val revision: Long = 0,
    val allocations: List<ArenaSkillAllocation> = emptyList(),
)

data class ArenaSkillTreeMutation(
    val accepted: Boolean,
    val state: ArenaSkillTreeState,
    val error: String? = null,
)

data class ArenaSkillTreeNodeView(
    val definition: ArenaSkillTreeNodeDefinition,
    val rank: Int,
    val canAllocate: Boolean,
    val lockReason: String? = null,
    val canReset: Boolean = false,
    val resetFailure: String? = null,
)

data class ArenaSkillTreeView(
    val state: ArenaSkillTreeState,
    val arenaLevel: Int,
    val earnedPoints: Int,
    val spentPoints: Int,
    val availablePoints: Int,
    val valid: Boolean,
    val nodes: List<ArenaSkillTreeNodeView>,
)

object ArenaSkillTreeRules {
    const val rulesVersion: String = "arena-skill-tree-v13"
    const val maxArenaLevel: Int = 100

    fun initialize(current: ArenaSkillTreeState?, heroClass: HeroClass): ArenaSkillTreeState {
        if (current == null) return ArenaSkillTreeState(heroClass = heroClass)
        require(current.revision >= 0) { "Invalid arena skill-tree revision" }
        if (current.heroClass != heroClass) {
            require(current.revision < Long.MAX_VALUE) { "Arena skill-tree revision overflow" }
            return ArenaSkillTreeState(heroClass = heroClass, revision = safeIncrement(current.revision))
        }
        require(hasValidStoredShape(current)) { "Invalid arena skill-tree state" }
        return canonical(current)
    }

    fun view(
        state: ArenaSkillTreeState,
        arenaLevel: Int,
        ownedAttackIds: Set<String>,
    ): ArenaSkillTreeView {
        require(arenaLevel in 1..maxArenaLevel) { "Arena level must be in 1..100" }
        val spent = spentPoints(state)
        val valid = validate(state, state.heroClass, arenaLevel, ownedAttackIds)
        val ranks = rankMap(state)
        val nodes = ArenaSkillTreeCatalog.forClass(state.heroClass).map { definition ->
            val currentRank = ranks[definition.id] ?: 0
            val failure = allocationFailure(
                state = state,
                heroClass = state.heroClass,
                arenaLevel = arenaLevel,
                ownedAttackIds = ownedAttackIds,
                definition = definition,
                targetRank = currentRank + 1,
                requireValidState = true,
            )
            val reset = if (currentRank > 0) resetNode(
                state, state.heroClass, arenaLevel, ownedAttackIds, definition.id, editingEnabled = true,
            ) else null
            ArenaSkillTreeNodeView(
                definition = definition,
                rank = currentRank,
                canAllocate = valid && failure == null,
                lockReason = if (valid) failure else "invalid_state",
                canReset = reset?.accepted == true,
                resetFailure = reset?.error,
            )
        }
        return ArenaSkillTreeView(
            state = canonical(state),
            arenaLevel = arenaLevel,
            earnedPoints = arenaLevel,
            spentPoints = spent,
            availablePoints = (arenaLevel - spent).coerceAtLeast(0),
            valid = valid,
            nodes = nodes,
        )
    }

    fun allocate(
        state: ArenaSkillTreeState,
        heroClass: HeroClass,
        arenaLevel: Int,
        ownedAttackIds: Set<String>,
        nodeId: String,
        targetRank: Int,
        editingEnabled: Boolean,
    ): ArenaSkillTreeMutation {
        if (!editingEnabled) return rejected(state, "editing_locked")
        if (state.heroClass != heroClass) return rejected(state, "hero_class")
        if (arenaLevel !in 1..maxArenaLevel) return rejected(state, "arena_level")
        if (!validate(state, heroClass, arenaLevel, ownedAttackIds)) return rejected(state, "invalid_state")
        if (state.revision == Long.MAX_VALUE) return rejected(state, "revision_overflow")
        val definition = ArenaSkillTreeCatalog.find(nodeId)
            ?: return rejected(state, "unknown_node")
        if (definition.heroClass != heroClass) return rejected(state, "foreign_node")
        val failure = allocationFailure(
            state = state,
            heroClass = heroClass,
            arenaLevel = arenaLevel,
            ownedAttackIds = ownedAttackIds,
            definition = definition,
            targetRank = targetRank,
            requireValidState = false,
        )
        if (failure != null) return rejected(state, failure)

        val allocations = state.allocations.associateByTo(linkedMapOf(), ArenaSkillAllocation::nodeId)
        allocations[nodeId] = ArenaSkillAllocation(nodeId, targetRank)
        val candidate = canonical(state.copy(
            revision = safeIncrement(state.revision),
            allocations = allocations.values.toList(),
        ))
        check(validate(candidate, heroClass, arenaLevel, ownedAttackIds)) {
            "Accepted arena skill allocation must remain valid"
        }
        return ArenaSkillTreeMutation(accepted = true, state = candidate)
    }

    /** Refund only this node. Never silently cascade a reset to another purchased skill. */
    fun resetNode(
        state: ArenaSkillTreeState,
        heroClass: HeroClass,
        arenaLevel: Int,
        ownedAttackIds: Set<String>,
        nodeId: String,
        editingEnabled: Boolean,
    ): ArenaSkillTreeMutation {
        if (!editingEnabled) return rejected(state, "editing_locked")
        if (state.heroClass != heroClass) return rejected(state, "hero_class")
        if (!validate(state, heroClass, arenaLevel, ownedAttackIds)) return rejected(state, "invalid_state")
        val definition = ArenaSkillTreeCatalog.find(nodeId) ?: return rejected(state, "unknown_node")
        if (definition.heroClass != heroClass) return rejected(state, "foreign_node")
        if (state.allocations.none { it.nodeId == nodeId }) return ArenaSkillTreeMutation(true, state)
        if (state.revision == Long.MAX_VALUE) return rejected(state, "revision_overflow")
        val candidate = state.copy(
            revision = safeIncrement(state.revision),
            allocations = state.allocations.filterNot { it.nodeId == nodeId },
        )
        // Both parent-rank requirements and earlier-row investment requirements must survive.
        if (!validate(candidate, heroClass, arenaLevel, ownedAttackIds)) return rejected(state, "dependent_skills")
        return ArenaSkillTreeMutation(true, candidate)
    }

    fun reset(
        state: ArenaSkillTreeState,
        heroClass: HeroClass,
        editingEnabled: Boolean = true,
    ): ArenaSkillTreeMutation {
        if (!editingEnabled) return rejected(state, "editing_locked")
        if (state.heroClass != heroClass) return rejected(state, "hero_class")
        if (state.revision < 0) return rejected(state, "invalid_state")
        if (state.allocations.isEmpty()) return ArenaSkillTreeMutation(accepted = true, state = state)
        if (state.revision == Long.MAX_VALUE) return rejected(state, "revision_overflow")
        return ArenaSkillTreeMutation(
            accepted = true,
            state = state.copy(revision = safeIncrement(state.revision), allocations = emptyList()),
        )
    }

    fun validate(
        state: ArenaSkillTreeState,
        heroClass: HeroClass,
        arenaLevel: Int,
        ownedAttackIds: Set<String>,
    ): Boolean {
        if (state.heroClass != heroClass || state.revision < 0 || arenaLevel !in 1..maxArenaLevel) return false
        if (!hasValidStoredShape(state)) return false
        if (spentPoints(state) > arenaLevel) return false
        val ranks = rankMap(state)
        return state.allocations.all { allocation ->
            val definition = ArenaSkillTreeCatalog.find(allocation.nodeId) ?: return@all false
            if (definition.heroClass != heroClass) return@all false
            if (definition.kind == ArenaSkillNodeKind.ATTACK && definition.id !in ownedAttackIds) return@all false
            if (!definition.isRoot && definition.parentAnyOf.none { (ranks[it] ?: 0) >= definition.minParentRank }) {
                return@all false
            }
            spentBeforeRow(state, definition.row) >= definition.minimumSpentPoints
        }
    }

    fun autoAllocate(
        heroClass: HeroClass,
        arenaLevel: Int,
        ownedAttackIds: Set<String>,
        seed: Long,
    ): ArenaSkillTreeState = autoAllocateWithPreset(
        heroClass,
        arenaLevel,
        ownedAttackIds,
        seed,
        ArenaAutoBuildPreset.fromSeed(seed),
    )

    internal fun autoAllocateWithPreset(
        heroClass: HeroClass,
        arenaLevel: Int,
        ownedAttackIds: Set<String>,
        seed: Long,
        preset: ArenaAutoBuildPreset,
    ): ArenaSkillTreeState {
        require(arenaLevel in 1..maxArenaLevel) { "Arena level must be in 1..100" }
        var state = initialize(null, heroClass)
        if (ownedAttackIds.isEmpty()) return state
        val nodes = ArenaSkillTreeCatalog.forClass(heroClass)
        val bySlot = nodes.associateBy(ArenaSkillTreeNodeDefinition::slotKey)
        val competencyRootIds = nodes.filter {
            it.isRoot && it.kind == ArenaSkillNodeKind.ATTACK
        }.mapTo(linkedSetOf(), ArenaSkillTreeNodeDefinition::id)
        val ownsOnlyCompetencyAttacks = competencyRootIds.all(ownedAttackIds::contains) &&
            nodes.none {
                it.kind == ArenaSkillNodeKind.ATTACK && !it.isRoot && it.id in ownedAttackIds
        }
        val pointOrder = ArenaAutoBuildProfiles.pointOrder(
            heroClass = heroClass,
            preset = preset,
            competencyOnly = ownsOnlyCompetencyAttacks,
        )

        // Each route entry is exactly one authored point. A full-ownership projection consumes a
        // longer prefix of the same list as Arena level rises. Partial ownership may only skip an
        // unavailable or unreachable entry; it can never synthesize an attack the hero does not
        // own. The deterministic legal fallback below spends any remaining reachable budget.
        for (slotKey in pointOrder) {
            if (spentPoints(state) >= arenaLevel) break
            val definition = bySlot.getValue(slotKey)
            if (definition.kind == ArenaSkillNodeKind.ATTACK && definition.id !in ownedAttackIds) {
                continue
            }
            val rank = rankMap(state)[definition.id] ?: 0
            if (rank >= definition.maxRank) continue
            val mutation = allocate(
                state, heroClass, arenaLevel, ownedAttackIds,
                definition.id, rank + 1, editingEnabled = true,
            )
            if (mutation.accepted) state = mutation.state
        }
        while (spentPoints(state) < arenaLevel) {
            val ranks = rankMap(state)
            val legal = nodes.filter { definition ->
                allocationFailure(
                    state = state,
                    heroClass = heroClass,
                    arenaLevel = arenaLevel,
                    ownedAttackIds = ownedAttackIds,
                    definition = definition,
                    targetRank = (ranks[definition.id] ?: 0) + 1,
                    requireValidState = false,
                ) == null
            }
            if (legal.isEmpty()) break
            val step = spentPoints(state).toLong()
            val supportBounded = legal.filterNot { definition ->
                definition.kind == ArenaSkillNodeKind.SUPPORT &&
                    (ranks[definition.id] ?: 0) >= definition.minParentRank
            }.ifEmpty { legal }
            val selected = supportBounded.minWith(
                compareBy<ArenaSkillTreeNodeDefinition> { definition ->
                    val slotHash = definition.slotKey.fold(0L) { hash, char ->
                        hash * 131L + char.code
                    }
                    arenaAutoBuildMix64(
                        seed xor (step * AUTO_ALLOCATE_JITTER_GAMMA) xor slotHash,
                    )
                }.thenBy(ArenaSkillTreeNodeDefinition::slotKey),
            )
            val mutation = allocate(
                state = state,
                heroClass = heroClass,
                arenaLevel = arenaLevel,
                ownedAttackIds = ownedAttackIds,
                nodeId = selected.id,
                targetRank = (ranks[selected.id] ?: 0) + 1,
                editingEnabled = true,
            )
            check(mutation.accepted) { "Auto allocation selected an invalid candidate: ${mutation.error}" }
            state = mutation.state
        }
        check(validate(state, heroClass, arenaLevel, ownedAttackIds))
        return state
    }

    fun spentPoints(state: ArenaSkillTreeState): Int = state.allocations.sumOf(ArenaSkillAllocation::rank)

    private fun allocationFailure(
        state: ArenaSkillTreeState,
        heroClass: HeroClass,
        arenaLevel: Int,
        ownedAttackIds: Set<String>,
        definition: ArenaSkillTreeNodeDefinition,
        targetRank: Int,
        requireValidState: Boolean,
    ): String? {
        if (requireValidState && !validate(state, heroClass, arenaLevel, ownedAttackIds)) return "invalid_state"
        if (definition.heroClass != heroClass) return "foreign_node"
        val currentRank = rankMap(state)[definition.id] ?: 0
        if (currentRank >= definition.maxRank) return "rank_max"
        if (targetRank != currentRank + 1) return "rank_step"
        if (targetRank !in 1..definition.maxRank) return "rank_max"
        if (definition.kind == ArenaSkillNodeKind.ATTACK && definition.id !in ownedAttackIds) {
            return "attack_not_owned"
        }
        // A permanent ownership requirement is more actionable than a temporary zero-point state.
        // The global point counter still exposes that there are no points left.
        if (spentPoints(state) + 1 > arenaLevel) return "point_budget"
        // Parent and row-spend gates unlock the node once. Later ranks only spend another earned
        // point on an already-valid allocation; validate() above remains the full-state guard.
        if (currentRank == 0) {
            if (!definition.isRoot) {
                val ranks = rankMap(state)
                if (definition.parentAnyOf.none { (ranks[it] ?: 0) >= definition.minParentRank }) {
                    return "prerequisite"
                }
            }
            if (spentBeforeRow(state, definition.row) < definition.minimumSpentPoints) return "row_spend"
        }
        return null
    }

    private fun hasValidStoredShape(state: ArenaSkillTreeState): Boolean {
        if (state.revision < 0) return false
        if (state.allocations.map(ArenaSkillAllocation::nodeId).distinct().size != state.allocations.size) return false
        return state.allocations.all { allocation ->
            val definition = ArenaSkillTreeCatalog.find(allocation.nodeId) ?: return@all false
            definition.heroClass == state.heroClass && allocation.rank in 1..definition.maxRank
        }
    }

    private fun spentBeforeRow(state: ArenaSkillTreeState, row: Int): Int = state.allocations.sumOf { allocation ->
        val definition = ArenaSkillTreeCatalog.find(allocation.nodeId)
        if (definition != null && definition.row < row) allocation.rank else 0
    }

    private fun rankMap(state: ArenaSkillTreeState): Map<String, Int> =
        state.allocations.associate { it.nodeId to it.rank }

    private fun canonical(state: ArenaSkillTreeState): ArenaSkillTreeState {
        val order = ArenaSkillTreeCatalog.forClass(state.heroClass)
            .mapIndexed { index, definition -> definition.id to index }
            .toMap()
        return state.copy(allocations = state.allocations.sortedWith(
            compareBy<ArenaSkillAllocation> { order[it.nodeId] ?: Int.MAX_VALUE }.thenBy { it.nodeId },
        ))
    }

    private fun rejected(state: ArenaSkillTreeState, error: String) =
        ArenaSkillTreeMutation(accepted = false, state = state, error = error)

    private fun safeIncrement(value: Long): Long = if (value == Long.MAX_VALUE) value else value + 1

    private const val AUTO_ALLOCATE_JITTER_GAMMA = -7046029254386353131L
}
