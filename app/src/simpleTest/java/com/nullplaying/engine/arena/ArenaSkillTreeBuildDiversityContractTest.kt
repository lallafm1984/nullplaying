package com.nullplaying.engine.arena

import com.nullplaying.engine.SkillCatalog
import com.nullplaying.model.HeroClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural build-diversity contract for the V6 arena tree.
 *
 * This suite deliberately avoids combat output and skill-copy assertions. It proves that point
 * gates leave several legal routes through the graph; combat balance is evaluated separately.
 */
class ArenaSkillTreeBuildDiversityContractTest {
    private val rootSlots = setOf("A01", "A02", "A03")
    private val sampleBudgets = listOf(1, 3, 4, 8, 10, 13, 20, 25, 30, 50, 64, 75, 100)

    @Test
    fun `every class exposes branching rejoining and forty deep route topologies`() {
        HeroClass.entries.forEach { heroClass ->
            val nodes = ArenaSkillTreeCatalog.forClass(heroClass)
            val byId = nodes.associateBy(ArenaSkillTreeNodeDefinition::id)
            val childCounts = nodes.flatMap(ArenaSkillTreeNodeDefinition::parentAnyOf)
                .groupingBy { it }
                .eachCount()
            val deepPaths = nodes.filter { it.row == 9 }
                .flatMap { pathsTo(it, byId) }
            val routeInclusion = deepPaths.flatten()
                .groupingBy(ArenaSkillTreeNodeDefinition::slotKey)
                .eachCount()

            assertEquals("$heroClass merge nodes", 9, nodes.count { it.parentAnyOf.size > 1 })
            assertEquals("$heroClass split nodes", 7, childCounts.count { it.value > 1 })
            assertEquals("$heroClass deep routes", 40, deepPaths.size)
            assertEquals("$heroClass unique deep routes", 40,
                deepPaths.map(::slotSignature).distinct().size)
            assertEquals(
                "$heroClass routes by terminal",
                mapOf("A19" to 14, "S10" to 11, "A20" to 15),
                deepPaths.groupingBy { it.last().slotKey }.eachCount(),
            )
            assertEquals(
                "$heroClass routes by starting axis",
                mapOf("A01" to 12, "A02" to 11, "A03" to 17),
                deepPaths.groupingBy { it.first().slotKey }.eachCount(),
            )
            assertEquals("$heroClass direct-row deep routes", 29,
                deepPaths.count { minimumBudgetToAcquirePath(heroClass, it) == 64 })
            assertEquals("$heroClass chained-row deep routes", 11,
                deepPaths.count { minimumBudgetToAcquirePath(heroClass, it) == 67 })
            assertTrue("$heroClass no node may be mandatory in every deep route",
                routeInclusion.none { it.value == deepPaths.size })
            println(
                "arena-v6-routes class=$heroClass total=${deepPaths.size} " +
                    "roots=${deepPaths.groupingBy { it.first().slotKey }.eachCount()} " +
                    "mostIncluded=${routeInclusion.maxBy { it.value }}",
            )
        }
    }

    @Test
    fun `all thirty nodes can first unlock at their published point gate`() {
        HeroClass.entries.forEach { heroClass ->
            ArenaSkillTreeCatalog.forClass(heroClass).forEach { target ->
                val state = stateImmediatelyBeforeTarget(heroClass, target)
                val gate = target.minimumSpentPoints
                assertEquals("$heroClass ${target.slotKey} pre-gate spend", gate,
                    ArenaSkillTreeRules.spentPoints(state))
                assertTrue("$heroClass ${target.slotKey} state at gate must be valid",
                    ArenaSkillTreeRules.validate(state, heroClass, gate.coerceAtLeast(1), owned(heroClass)))

                val view = ArenaSkillTreeRules.view(state, gate + 1, owned(heroClass))
                assertTrue(
                    "$heroClass ${target.slotKey} must be a legal next point at ${gate + 1}",
                    view.nodes.single { it.definition.id == target.id }.canAllocate,
                )
            }
        }
    }

    @Test
    fun `three disjoint starting axes each sustain a full rank deep build`() {
        val spineSlots = listOf(
            listOf("A01", "A04", "S02", "A08", "A10", "S05", "A14", "S07", "A18", "A19"),
            listOf("A02", "S01", "A06", "A09", "S04", "A12", "A15", "A16", "S08", "S10"),
            listOf("A03", "A05", "A07", "S03", "A11", "A13", "S06", "A17", "S09", "A20"),
        )

        HeroClass.entries.forEach { heroClass ->
            val bySlot = ArenaSkillTreeCatalog.forClass(heroClass)
                .associateBy(ArenaSkillTreeNodeDefinition::slotKey)
            val builds = spineSlots.map { slots ->
                val path = slots.map(bySlot::getValue)
                buildPathAtBudget(heroClass, path, 100).also { state ->
                    assertEquals("$heroClass ${slots.first()} budget", 100,
                        ArenaSkillTreeRules.spentPoints(state))
                    assertTrue("$heroClass ${slots.first()} validity",
                        ArenaSkillTreeRules.validate(state, heroClass, 100, owned(heroClass)))
                    assertEquals(slots.toSet(), state.allocations.map { allocation ->
                        requireNotNull(bySlot.values.firstOrNull { it.id == allocation.nodeId }).slotKey
                    }.toSet())
                    assertTrue(state.allocations.all { it.rank == 10 })
                    assertEquals(setOf(slots.first()), slots.toSet().intersect(rootSlots))
                    assertEquals((0..9).toSet(), path.map(ArenaSkillTreeNodeDefinition::row).toSet())
                }
            }
            assertTrue("$heroClass spines must share no forced node",
                builds.map { it.allocations.map(ArenaSkillAllocation::nodeId).toSet() }
                    .reduce(Set<String>::intersect)
                    .isEmpty())
        }
    }

    @Test
    fun `all forty deep route topologies support legal budget one hundred builds`() {
        HeroClass.entries.forEach { heroClass ->
            val nodes = ArenaSkillTreeCatalog.forClass(heroClass)
            val byId = nodes.associateBy(ArenaSkillTreeNodeDefinition::id)
            val builds = nodes.filter { it.row == 9 }
                .flatMap { pathsTo(it, byId) }
                .map { path -> buildPathAtBudget(heroClass, path, 100) }

            assertEquals(40, builds.size)
            assertEquals(40, builds.map { structuralDigest(heroClass, it) }.distinct().size)
            assertTrue(builds.all {
                ArenaSkillTreeRules.spentPoints(it) == 100 &&
                    ArenaSkillTreeRules.validate(it, heroClass, 100, owned(heroClass))
            })
        }
    }

    @Test
    fun `seeded allocator keeps multiple choices at every budget checkpoint`() {
        val sampledStatesByClass = HeroClass.entries.associateWith { heroClass ->
            sampleBudgets.associateWith { budget ->
                (0L until SAMPLE_SEEDS).map { seed ->
                    ArenaSkillTreeRules.autoAllocate(heroClass, budget, owned(heroClass), seed)
                }
            }
        }
        val uniqueCountsByClass = sampledStatesByClass.mapValues { (heroClass, byBudget) ->
            byBudget.mapValues { (_, states) ->
                states.map { structuralDigest(heroClass, it) }.toSet().size
            }
        }

        uniqueCountsByClass.forEach { (heroClass, counts) ->
            println("arena-v6-diversity-counts class=$heroClass counts=$counts")
            // Every authored family shares a neutral three-point competency floor. The family
            // first branches at point four, becomes broadly distinct by point eight, and all ten
            // explicit families are visible at point ten. While moving toward the reviewed Lv.20
            // balance checkpoint, at most two family pairs may briefly share the same rank state.
            assertEquals("$heroClass one-point shared floor", 1, counts.getValue(1))
            assertEquals("$heroClass three-point shared floor", 1, counts.getValue(3))
            assertEquals("$heroClass first branch axes", 3, counts.getValue(4))
            assertTrue("$heroClass budget 8 family diversity", counts.getValue(8) >= 8)
            // Lv.20/25/30 used to sample at least 64 anonymous seed heuristics. The allocator now
            // has ten explicit, balanced families, so the release-level contract is exhaustive
            // family reachability rather than dozens of hidden opening rerolls.
            assertTrue(
                "$heroClass budget 13 must keep broad transition diversity",
                counts.getValue(13) >= 8,
            )
            listOf(10, 20, 25, 30).forEach { budget ->
                assertTrue("$heroClass budget $budget must retain all ten authored families",
                    counts.getValue(budget) >= ArenaAutoBuildPreset.entries.size)
            }
            // Deeper budgets deliberately resume deterministic within-family path variation,
            // while every family must remain represented.
            listOf(50, 64, 75, 100).forEach { budget ->
                assertTrue("$heroClass budget $budget must retain all authored families",
                    counts.getValue(budget) >= ArenaAutoBuildPreset.entries.size)
            }
            val slotsById = ArenaSkillTreeCatalog.forClass(heroClass).associate { it.id to it.slotKey }
            val finalStates = sampledStatesByClass.getValue(heroClass).getValue(100)
            val finalInclusion = finalStates.flatMap { state ->
                state.allocations.map { slotsById.getValue(it.nodeId) }
            }.groupingBy { it }.eachCount()
            val mostIncluded = finalInclusion.maxBy { it.value }
            // autoAllocate is a deterministic QA fallback, not a uniform sampler of every legal
            // build. Keep its inclusion rate diagnostic-only; the disjoint-spine contract above
            // is the exact proof that the rules do not logically force a node.
            val highInclusion = finalInclusion.filterValues {
                it * 5 >= SAMPLE_SEEDS * 4
            }.toSortedMap()
            println(
                "arena-v6-diversity class=$heroClass sampledUnique=$counts seeds=$SAMPLE_SEEDS " +
                    "mostIncludedAt100=$mostIncluded highInclusionAt100=$highInclusion",
            )
        }
        // Class-specific attacks can make equivalent family preferences resolve through different
        // legal paths. Per-class lower bounds above are the compatibility contract; exact count
        // equality would incorrectly forbid the class-aware balance correction.
    }

    private fun stateImmediatelyBeforeTarget(
        heroClass: HeroClass,
        target: ArenaSkillTreeNodeDefinition,
    ): ArenaSkillTreeState {
        if (target.isRoot) return ArenaSkillTreeRules.initialize(null, heroClass)
        val nodes = ArenaSkillTreeCatalog.forClass(heroClass)
        val byId = nodes.associateBy(ArenaSkillTreeNodeDefinition::id)
        val directParent = target.parentAnyOf.map(byId::getValue)
            .filter { it.row < target.row }
            .minByOrNull(ArenaSkillTreeNodeDefinition::slotKey)
            ?: target.parentAnyOf.map(byId::getValue)
                .minBy(ArenaSkillTreeNodeDefinition::slotKey)
        val path = pathsTo(directParent, byId)
            .minWith(compareBy<List<ArenaSkillTreeNodeDefinition>>({ it.size }, ::slotSignature))
        var state = ArenaSkillTreeRules.initialize(null, heroClass)
        path.forEach { node ->
            state = fillEarlierPathRanks(
                state = state,
                heroClass = heroClass,
                path = path,
                beforeRow = node.row,
                targetSpend = node.minimumSpentPoints,
                arenaLevel = target.minimumSpentPoints.coerceAtLeast(1),
            )
            state = allocateToRank(state, heroClass, node.id, 3,
                target.minimumSpentPoints.coerceAtLeast(3))
        }
        return fillEarlierPathRanks(
            state = state,
            heroClass = heroClass,
            path = path,
            beforeRow = target.row,
            targetSpend = target.minimumSpentPoints,
            arenaLevel = target.minimumSpentPoints.coerceAtLeast(1),
        )
    }

    private fun minimumBudgetToAcquirePath(
        heroClass: HeroClass,
        path: List<ArenaSkillTreeNodeDefinition>,
    ): Int = acquirePath(heroClass, path, 100).let(ArenaSkillTreeRules::spentPoints)

    private fun buildPathAtBudget(
        heroClass: HeroClass,
        path: List<ArenaSkillTreeNodeDefinition>,
        budget: Int,
    ): ArenaSkillTreeState {
        var state = acquirePath(heroClass, path, budget)
        while (ArenaSkillTreeRules.spentPoints(state) < budget) {
            val ranks = state.allocations.associate { it.nodeId to it.rank }
            val candidate = path.firstOrNull { (ranks[it.id] ?: 0) < it.maxRank }
                ?: error("Path capacity exhausted below budget $budget")
            state = allocateToRank(
                state,
                heroClass,
                candidate.id,
                (ranks[candidate.id] ?: 0) + 1,
                budget,
            )
        }
        return state
    }

    private fun acquirePath(
        heroClass: HeroClass,
        path: List<ArenaSkillTreeNodeDefinition>,
        arenaLevel: Int,
    ): ArenaSkillTreeState {
        var state = ArenaSkillTreeRules.initialize(null, heroClass)
        path.forEachIndexed { index, node ->
            state = fillEarlierPathRanks(
                state = state,
                heroClass = heroClass,
                path = path,
                beforeRow = node.row,
                targetSpend = node.minimumSpentPoints,
                arenaLevel = arenaLevel,
            )
            state = allocateToRank(
                state,
                heroClass,
                node.id,
                if (index == path.lastIndex) 1 else node.minParentRank.coerceAtLeast(1),
                arenaLevel,
            )
        }
        return state
    }

    private fun fillEarlierPathRanks(
        state: ArenaSkillTreeState,
        heroClass: HeroClass,
        path: List<ArenaSkillTreeNodeDefinition>,
        beforeRow: Int,
        targetSpend: Int,
        arenaLevel: Int,
    ): ArenaSkillTreeState {
        var next = state
        while (spentBeforeRow(heroClass, next, beforeRow) < targetSpend) {
            val ranks = next.allocations.associate { it.nodeId to it.rank }
            val candidate = path.firstOrNull {
                it.row < beforeRow && (ranks[it.id] ?: 0) in 1 until it.maxRank
            } ?: error("No path capacity to reach row $beforeRow gate $targetSpend")
            next = allocateToRank(
                next,
                heroClass,
                candidate.id,
                ranks.getValue(candidate.id) + 1,
                arenaLevel,
            )
        }
        return next
    }

    private fun allocateToRank(
        initial: ArenaSkillTreeState,
        heroClass: HeroClass,
        nodeId: String,
        targetRank: Int,
        arenaLevel: Int,
    ): ArenaSkillTreeState {
        var state = initial
        val startingRank = state.allocations.firstOrNull { it.nodeId == nodeId }?.rank ?: 0
        for (rank in startingRank + 1..targetRank) {
            val mutation = ArenaSkillTreeRules.allocate(
                state = state,
                heroClass = heroClass,
                arenaLevel = arenaLevel,
                ownedAttackIds = owned(heroClass),
                nodeId = nodeId,
                targetRank = rank,
                editingEnabled = true,
            )
            assertTrue("$heroClass $nodeId R$rank rejected: ${mutation.error}", mutation.accepted)
            state = mutation.state
        }
        return state
    }

    private fun spentBeforeRow(heroClass: HeroClass, state: ArenaSkillTreeState, row: Int): Int {
        val rows = ArenaSkillTreeCatalog.forClass(heroClass).associate { it.id to it.row }
        return state.allocations.sumOf { allocation ->
            if (rows.getValue(allocation.nodeId) < row) allocation.rank else 0
        }
    }

    private fun pathsTo(
        target: ArenaSkillTreeNodeDefinition,
        byId: Map<String, ArenaSkillTreeNodeDefinition>,
    ): List<List<ArenaSkillTreeNodeDefinition>> = if (target.isRoot) {
        listOf(listOf(target))
    } else {
        target.parentAnyOf.map(byId::getValue)
            .sortedBy(ArenaSkillTreeNodeDefinition::slotKey)
            .flatMap { parent -> pathsTo(parent, byId).map { it + target } }
    }

    private fun structuralDigest(heroClass: HeroClass, state: ArenaSkillTreeState): String {
        val slots = ArenaSkillTreeCatalog.forClass(heroClass).associate { it.id to it.slotKey }
        return state.allocations.joinToString("|") { "${slots.getValue(it.nodeId)}:${it.rank}" }
    }

    private fun slotSignature(path: List<ArenaSkillTreeNodeDefinition>): String =
        path.joinToString(">", transform = ArenaSkillTreeNodeDefinition::slotKey)

    private fun owned(heroClass: HeroClass): Set<String> =
        SkillCatalog.forClass(heroClass).mapTo(linkedSetOf()) { it.catalogId }

    private companion object {
        const val SAMPLE_SEEDS = 256L
    }
}
