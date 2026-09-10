package com.nullplaying.engine.arena

import com.nullplaying.engine.SkillCatalog
import com.nullplaying.model.HeroClass
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArenaSkillTreeRulesTest {
    private val heroClass = HeroClass.WARRIOR
    private val nodes get() = ArenaSkillTreeCatalog.forClass(heroClass)
    private val bySlot get() = nodes.associateBy { it.slotKey }
    private val owned get() = SkillCatalog.forClass(heroClass).map { it.catalogId }.toSet()

    @Test
    fun `state is serializable and initialize is stable and class safe`() {
        val fresh = ArenaSkillTreeRules.initialize(null, heroClass)
        assertEquals(heroClass, fresh.heroClass)
        assertEquals(0L, fresh.revision)
        assertTrue(fresh.allocations.isEmpty())
        assertEquals(fresh, Json.decodeFromString<ArenaSkillTreeState>(Json.encodeToString(fresh)))
        assertEquals(fresh, ArenaSkillTreeRules.initialize(fresh, heroClass))

        val switched = ArenaSkillTreeRules.initialize(fresh.copy(revision = 8), HeroClass.ROGUE)
        assertEquals(HeroClass.ROGUE, switched.heroClass)
        assertEquals(9L, switched.revision)
        assertTrue(switched.allocations.isEmpty())
    }

    @Test
    fun `one point per arena level and rank plus one are enforced`() {
        val fresh = ArenaSkillTreeRules.initialize(null, heroClass)
        val a01 = bySlot.getValue("A01").id
        val a02 = bySlot.getValue("A02").id
        val first = ArenaSkillTreeRules.allocate(fresh, heroClass, 1, owned, a01, 1, true)
        assertTrue(first.accepted)
        assertEquals(1L, first.state.revision)
        assertEquals(1, ArenaSkillTreeRules.view(first.state, 1, owned).spentPoints)
        assertEquals(0, ArenaSkillTreeRules.view(first.state, 1, owned).availablePoints)

        val overBudget = ArenaSkillTreeRules.allocate(first.state, heroClass, 1, owned, a02, 1, true)
        assertFalse(overBudget.accepted)
        assertEquals("point_budget", overBudget.error)
        assertEquals(first.state, overBudget.state)
        val skipped = ArenaSkillTreeRules.allocate(fresh, heroClass, 10, owned, a01, 2, true)
        assertFalse(skipped.accepted)
        assertEquals("rank_step", skipped.error)
        assertFalse(ArenaSkillTreeRules.allocate(fresh, heroClass, 101, owned, a01, 1, true).accepted)
        val overflow = fresh.copy(revision = Long.MAX_VALUE)
        assertEquals("revision_overflow",
            ArenaSkillTreeRules.allocate(overflow, heroClass, 10, owned, a01, 1, true).error)
    }

    @Test
    fun `parent rank three and previous row spend gate cannot be bypassed`() {
        val a01 = bySlot.getValue("A01").id
        val a02 = bySlot.getValue("A02").id
        val a04 = bySlot.getValue("A04").id
        var state = ArenaSkillTreeRules.initialize(null, heroClass)
        repeat(2) { index ->
            state = accepted(state, 100, a01, index + 1)
        }
        val weakParent = ArenaSkillTreeRules.allocate(state, heroClass, 100, owned, a04, 1, true)
        assertFalse(weakParent.accepted)
        assertEquals("prerequisite", weakParent.error)
        state = accepted(state, 100, a01, 3)
        state = accepted(state, 100, a04, 1)
        assertEquals(4, ArenaSkillTreeRules.spentPoints(state))

        state = accepted(state, 100, a04, 2)
        state = accepted(state, 100, a04, 3)
        val s02 = bySlot.getValue("S02").id
        val belowSeven = ArenaSkillTreeRules.allocate(state, heroClass, 100, owned, s02, 1, true)
        assertFalse(belowSeven.accepted)
        assertEquals("row_spend", belowSeven.error)
        state = accepted(state, 100, a02, 1)
        val atSeven = ArenaSkillTreeRules.allocate(state, heroClass, 100, owned, s02, 1, true)
        assertTrue(atSeven.accepted)
    }

    @Test
    fun `any one reported cross parent at rank three unlocks child`() {
        val a01 = bySlot.getValue("A01").id
        val a03 = bySlot.getValue("A03").id
        val a05 = bySlot.getValue("A05").id
        val a07 = bySlot.getValue("A07").id
        val a09 = bySlot.getValue("A09").id
        var state = ArenaSkillTreeRules.initialize(null, heroClass)
        repeat(3) { index -> state = accepted(state, 100, a01, index + 1) }
        repeat(3) { index -> state = accepted(state, 100, a03, index + 1) }
        repeat(3) { index -> state = accepted(state, 100, a05, index + 1) }
        repeat(3) { index -> state = accepted(state, 100, a07, index + 1) }
        assertEquals(12, ArenaSkillTreeRules.spentPoints(state))
        val result = ArenaSkillTreeRules.allocate(state, heroClass, 100, owned, a09, 1, true)
        assertTrue(result.accepted)
    }

    @Test
    fun `prerequisite and row spend gates are acquisition checks not rank-up costs`() {
        val a01 = bySlot.getValue("A01").id
        val a04 = bySlot.getValue("A04").id
        var state = ArenaSkillTreeRules.initialize(null, heroClass)
        repeat(3) { index -> state = accepted(state, 100, a01, index + 1) }
        state = accepted(state, 100, a04, 1)
        val before = ArenaSkillTreeRules.view(state, 5, owned)
        val nodeBefore = before.nodes.single { it.definition.id == a04 }
        assertTrue(nodeBefore.canAllocate)

        val upgraded = ArenaSkillTreeRules.allocate(
            state = state,
            heroClass = heroClass,
            arenaLevel = 5,
            ownedAttackIds = owned,
            nodeId = a04,
            targetRank = 2,
            editingEnabled = true,
        )

        assertTrue(upgraded.error.orEmpty(), upgraded.accepted)
        assertEquals(2, upgraded.state.allocations.single { it.nodeId == a04 }.rank)
        assertTrue(ArenaSkillTreeRules.validate(upgraded.state, heroClass, 5, owned))
    }

    @Test
    fun `attack ownership class identity and editing lock are mandatory`() {
        val fresh = ArenaSkillTreeRules.initialize(null, heroClass)
        val a01 = bySlot.getValue("A01").id
        val notOwned = ArenaSkillTreeRules.allocate(fresh, heroClass, 10, emptySet(), a01, 1, true)
        assertEquals("attack_not_owned", notOwned.error)
        val locked = ArenaSkillTreeRules.allocate(fresh, heroClass, 10, owned, a01, 1, false)
        assertEquals("editing_locked", locked.error)
        val wrongClass = ArenaSkillTreeRules.allocate(fresh, HeroClass.ROGUE, 10, owned, a01, 1, true)
        assertEquals("hero_class", wrongClass.error)
        val foreign = ArenaSkillTreeCatalog.forClass(HeroClass.ROGUE).first().id
        val foreignResult = ArenaSkillTreeRules.allocate(fresh, heroClass, 10, owned, foreign, 1, true)
        assertEquals("foreign_node", foreignResult.error)
        val unknown = ArenaSkillTreeRules.allocate(fresh, heroClass, 10, owned, "missing", 1, true)
        assertEquals("unknown_node", unknown.error)
    }

    @Test
    fun `validate rejects duplicate unknown foreign orphan rank and budget states`() {
        val a01 = bySlot.getValue("A01").id
        val a04 = bySlot.getValue("A04").id
        val valid = ArenaSkillTreeState(heroClass, allocations = listOf(ArenaSkillAllocation(a01, 1)))
        assertTrue(ArenaSkillTreeRules.validate(valid, heroClass, 1, owned))
        assertFalse(ArenaSkillTreeRules.validate(valid, heroClass, 1, emptySet()))
        assertFalse(ArenaSkillTreeRules.validate(valid.copy(revision = -1), heroClass, 1, owned))
        assertFalse(ArenaSkillTreeRules.validate(valid.copy(allocations = listOf(
            ArenaSkillAllocation(a01, 1), ArenaSkillAllocation(a01, 1))), heroClass, 2, owned))
        assertFalse(ArenaSkillTreeRules.validate(valid.copy(allocations = listOf(
            ArenaSkillAllocation("missing", 1))), heroClass, 2, owned))
        assertFalse(ArenaSkillTreeRules.validate(valid.copy(allocations = listOf(
            ArenaSkillAllocation(ArenaSkillTreeCatalog.forClass(HeroClass.ROGUE).first().id, 1))),
            heroClass, 2, owned))
        assertFalse(ArenaSkillTreeRules.validate(valid.copy(allocations = listOf(
            ArenaSkillAllocation(a01, 11))), heroClass, 100, owned))
        assertFalse(ArenaSkillTreeRules.validate(valid.copy(allocations = listOf(
            ArenaSkillAllocation(a04, 1))), heroClass, 100, owned))
        assertFalse(ArenaSkillTreeRules.validate(valid.copy(allocations = listOf(
            ArenaSkillAllocation(a01, 2))), heroClass, 1, owned))
        assertFalse(ArenaSkillTreeRules.validate(valid, heroClass, 0, owned))
        assertFalse(ArenaSkillTreeRules.validate(valid, heroClass, 101, owned))
    }

    @Test
    fun `reset refunds every rank and increments revision only for a change`() {
        val a01 = bySlot.getValue("A01").id
        var state = ArenaSkillTreeRules.initialize(null, heroClass)
        state = accepted(state, 10, a01, 1)
        state = accepted(state, 10, a01, 2)
        val locked = ArenaSkillTreeRules.reset(state, heroClass, editingEnabled = false)
        assertFalse(locked.accepted)
        assertEquals(state, locked.state)
        val reset = ArenaSkillTreeRules.reset(state, heroClass)
        assertTrue(reset.accepted)
        assertTrue(reset.state.allocations.isEmpty())
        assertEquals(state.revision + 1, reset.state.revision)
        assertEquals(reset.state, ArenaSkillTreeRules.reset(reset.state, heroClass).state)
    }

    @Test
    fun `auto allocation is valid deterministic canonical and fills available capacity`() {
        val first = ArenaSkillTreeRules.autoAllocate(heroClass, 100, owned, 44L)
        val replay = ArenaSkillTreeRules.autoAllocate(heroClass, 100, owned, 44L)
        val alternate = ArenaSkillTreeRules.autoAllocate(heroClass, 100, owned, 45L)
        assertEquals(first, replay)
        assertNotEquals(first.allocations, alternate.allocations)
        assertEquals(100, ArenaSkillTreeRules.spentPoints(first))
        assertTrue(ArenaSkillTreeRules.validate(first, heroClass, 100, owned))
        val order = nodes.mapIndexed { index, definition -> definition.id to index }.toMap()
        assertEquals(first.allocations.sortedBy { order.getValue(it.nodeId) }, first.allocations)

        val noAttacks = ArenaSkillTreeRules.autoAllocate(heroClass, 100, emptySet(), 44L)
        assertTrue(noAttacks.allocations.isEmpty())
        assertTrue(ArenaSkillTreeRules.validate(noAttacks, heroClass, 100, emptySet()))
    }

    @Test
    fun `auto allocation remains valid for empty and partial attack ownership`() {
        HeroClass.entries.forEach { candidateClass ->
            val candidateNodes = ArenaSkillTreeCatalog.forClass(candidateClass)
            val rootAttacks = candidateNodes.filter {
                it.kind == ArenaSkillNodeKind.ATTACK && it.isRoot
            }.sortedBy { it.slotKey }
            val nonRootAttack = candidateNodes.first {
                it.kind == ArenaSkillNodeKind.ATTACK && !it.isRoot
            }
            val ownershipCases = linkedMapOf(
                "empty" to emptySet(),
                "one_root" to setOf(rootAttacks[0].id),
                "two_roots" to rootAttacks.take(2).mapTo(linkedSetOf()) { it.id },
                "non_root_only" to setOf(nonRootAttack.id),
            )

            ownershipCases.forEach { (caseName, partialOwned) ->
                listOf(20, 25, 30, 100).forEach { arenaLevel ->
                    val state = ArenaSkillTreeRules.autoAllocate(
                        heroClass = candidateClass,
                        arenaLevel = arenaLevel,
                        ownedAttackIds = partialOwned,
                        seed = 4400L + candidateClass.ordinal * 1_000L + arenaLevel,
                    )
                    assertTrue(
                        "$candidateClass $caseName Lv.$arenaLevel must remain valid",
                        ArenaSkillTreeRules.validate(state, candidateClass, arenaLevel, partialOwned),
                    )
                    assertTrue(
                        "$candidateClass $caseName Lv.$arenaLevel must not allocate an unowned attack",
                        state.allocations.all { allocation ->
                            val definition = requireNotNull(ArenaSkillTreeCatalog.find(allocation.nodeId))
                            definition.kind != ArenaSkillNodeKind.ATTACK || definition.id in partialOwned
                        },
                    )
                }
            }

            assertTrue(ArenaSkillTreeRules.autoAllocate(
                candidateClass, 100, emptySet(), 44L).allocations.isEmpty())
            assertTrue(ArenaSkillTreeRules.autoAllocate(
                candidateClass, 100, setOf(nonRootAttack.id), 44L).allocations.isEmpty())
        }
    }

    @Test
    fun `every class and arena level can auto allocate every point when attacks are owned`() {
        HeroClass.entries.forEach { candidateClass ->
            val candidateOwned = SkillCatalog.forClass(candidateClass).mapTo(linkedSetOf()) { it.catalogId }
            for (arenaLevel in 1..ArenaSkillTreeRules.maxArenaLevel) {
                val state = ArenaSkillTreeRules.autoAllocate(
                    heroClass = candidateClass,
                    arenaLevel = arenaLevel,
                    ownedAttackIds = candidateOwned,
                    seed = 44L + candidateClass.ordinal * 1_000L + arenaLevel,
                )

                assertEquals(
                    "$candidateClass Lv.$arenaLevel must spend every point",
                    arenaLevel,
                    ArenaSkillTreeRules.spentPoints(state),
                )
                assertTrue(
                    "$candidateClass Lv.$arenaLevel must remain valid",
                    ArenaSkillTreeRules.validate(state, candidateClass, arenaLevel, candidateOwned),
                )
            }
        }
    }

    private fun accepted(
        state: ArenaSkillTreeState,
        arenaLevel: Int,
        nodeId: String,
        rank: Int,
    ): ArenaSkillTreeState {
        val mutation = ArenaSkillTreeRules.allocate(
            state, heroClass, arenaLevel, owned, nodeId, rank, editingEnabled = true)
        assertTrue("Expected $nodeId rank $rank, got ${mutation.error}", mutation.accepted)
        return mutation.state
    }
}
