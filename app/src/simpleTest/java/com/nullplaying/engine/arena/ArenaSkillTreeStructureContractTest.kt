package com.nullplaying.engine.arena

import com.nullplaying.engine.SkillCatalog
import com.nullplaying.model.HeroClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural release contract for the single, long arena skill tree.
 *
 * This suite deliberately avoids skill copy and combat tuning. It protects only the topology and
 * the real allocation gates that must stay identical across all six class-specific catalogs.
 */
class ArenaSkillTreeStructureContractTest {
    @Test
    fun `six classes keep one connected ten row graph with no premature dead ends`() {
        assertEquals(6, HeroClass.entries.size)
        assertEquals(6 * 30, ArenaSkillTreeCatalog.values.size)
        assertEquals(ArenaSkillTreeCatalog.values.size,
            ArenaSkillTreeCatalog.values.map { it.id }.distinct().size)

        HeroClass.entries.forEach { heroClass ->
            val nodes = ArenaSkillTreeCatalog.forClass(heroClass)
            val byId = nodes.associateBy { it.id }
            val roots = nodes.filter(ArenaSkillTreeNodeDefinition::isRoot)
            val children = nodes.associate { it.id to linkedSetOf<String>() }

            assertEquals("$heroClass node count", 30, nodes.size)
            assertEquals("$heroClass attack count", 20,
                nodes.count { it.kind == ArenaSkillNodeKind.ATTACK })
            assertEquals("$heroClass support count", 10,
                nodes.count { it.kind == ArenaSkillNodeKind.SUPPORT })
            assertEquals("$heroClass ten rows", (0..9).toSet(), nodes.map { it.row }.toSet())
            assertEquals("$heroClass three nodes per row", (0..9).associateWith { 3 },
                nodes.groupingBy { it.row }.eachCount())
            assertEquals("$heroClass starting attacks", setOf("A01", "A02", "A03"),
                roots.map { it.slotKey }.toSet())
            assertTrue("$heroClass roots must all be attacks",
                roots.all { it.kind == ArenaSkillNodeKind.ATTACK })

            nodes.forEach { node ->
                assertEquals("$heroClass ${node.slotKey} row spend gate",
                    ArenaSkillTreeCatalog.rowThreshold(node.row), node.minimumSpentPoints)
                if (node.isRoot) {
                    assertEquals("$heroClass ${node.slotKey} root parent rank", 0,
                        node.minParentRank)
                } else {
                    assertTrue("$heroClass ${node.slotKey} must have one or two parents",
                        node.parentAnyOf.size in 1..2)
                    assertEquals("$heroClass ${node.slotKey} parent rank", 3,
                        node.minParentRank)
                }
                node.parentAnyOf.forEach { parentId ->
                    val parent = byId[parentId]
                    assertTrue("$heroClass ${node.slotKey} has a missing or foreign parent",
                        parent?.heroClass == heroClass)
                    requireNotNull(parent)
                    assertTrue("$heroClass ${node.slotKey} parent must precede child",
                        parent.row < node.row ||
                            parent.row == node.row && parent.column < node.column)
                    children.getValue(parentId) += node.id
                }
            }

            val reached = roots.mapTo(linkedSetOf()) { it.id }
            repeat(nodes.size) {
                nodes.filter { it.id !in reached && it.parentAnyOf.any(reached::contains) }
                    .forEach { reached += it.id }
            }
            assertEquals("$heroClass every node is root-reachable", byId.keys, reached)

            val undirected = nodes.associate { it.id to linkedSetOf<String>() }
            nodes.forEach { node ->
                node.parentAnyOf.forEach { parentId ->
                    undirected.getValue(node.id) += parentId
                    undirected.getValue(parentId) += node.id
                }
            }
            val connected = linkedSetOf(nodes.first().id)
            val pending = mutableListOf(nodes.first().id)
            while (pending.isNotEmpty()) {
                val current = pending.removeAt(pending.lastIndex)
                undirected.getValue(current).filter(connected::add).forEach(pending::add)
            }
            assertEquals("$heroClass graph must be one component", byId.keys, connected)
            assertEquals("$heroClass only final attacks may terminate", setOf("A19", "A20"),
                nodes.filter { children.getValue(it.id).isEmpty() }.map { it.slotKey }.toSet())

            val depth = linkedMapOf<String, Int>()
            nodes.forEach { node ->
                depth[node.id] = if (node.isRoot) 1 else
                    node.parentAnyOf.maxOf { depth.getValue(it) } + 1
            }
            assertEquals("$heroClass longest directed chain", 11, depth.values.max())
        }
    }

    @Test
    fun `merge nodes prevent a forced single route into every final choice`() {
        val expectedMerges = setOf(
            "S01", "A09", "S05", "A13", "S07", "S08", "A19", "S10", "A20",
        )
        val expectedFinalPathCounts = mapOf("A19" to 14, "S10" to 11, "A20" to 15)

        HeroClass.entries.forEach { heroClass ->
            val nodes = ArenaSkillTreeCatalog.forClass(heroClass)
            val byId = nodes.associateBy { it.id }
            val bySlot = nodes.associateBy { it.slotKey }
            assertEquals("$heroClass merge-node contract", expectedMerges,
                nodes.filter { it.parentAnyOf.size == 2 }.map { it.slotKey }.toSet())

            val memo = mutableMapOf<String, List<List<String>>>()
            fun pathsTo(nodeId: String): List<List<String>> = memo.getOrPut(nodeId) {
                val node = byId.getValue(nodeId)
                if (node.isRoot) {
                    listOf(listOf(node.id))
                } else {
                    node.parentAnyOf.flatMap { parentId ->
                        pathsTo(parentId).map { path -> path + node.id }
                    }
                }
            }

            expectedFinalPathCounts.forEach { (slotKey, expectedCount) ->
                val target = bySlot.getValue(slotKey)
                val paths = pathsTo(target.id)
                assertEquals("$heroClass $slotKey path count", expectedCount, paths.size)
                assertEquals("$heroClass $slotKey can be reached from every starting attack",
                    setOf("A01", "A02", "A03"),
                    paths.map { path -> byId.getValue(path.first()).slotKey }.toSet())
                val mandatory = paths.drop(1).fold(paths.first().toSet()) { common, path ->
                    common intersect path.toSet()
                }
                assertEquals("$heroClass $slotKey must have no forced intermediate node",
                    setOf(target.id), mandatory)
            }
        }
    }

    @Test
    fun `every node can pass the real parent and previous-row allocation gates`() {
        HeroClass.entries.forEach { heroClass ->
            val nodes = ArenaSkillTreeCatalog.forClass(heroClass)
            val ownedAttacks = SkillCatalog.forClass(heroClass).map { it.catalogId }.toSet()
            var prefix = ArenaSkillTreeRules.initialize(null, heroClass)

            nodes.groupBy { it.row }.toSortedMap().forEach { (_, rowNodes) ->
                rowNodes.forEach { node ->
                    val lockedAttempt = if (node.isRoot) null else ArenaSkillTreeRules.allocate(
                        state = ArenaSkillTreeRules.initialize(null, heroClass),
                        heroClass = heroClass,
                        arenaLevel = 100,
                        ownedAttackIds = ownedAttacks,
                        nodeId = node.id,
                        targetRank = 1,
                        editingEnabled = true,
                    )
                    if (lockedAttempt != null) {
                        assertEquals("$heroClass ${node.slotKey} must enforce its parent first",
                            "prerequisite", lockedAttempt.error)
                    }

                    val firstRank = ArenaSkillTreeRules.allocate(
                        state = prefix,
                        heroClass = heroClass,
                        arenaLevel = 100,
                        ownedAttackIds = ownedAttacks,
                        nodeId = node.id,
                        targetRank = 1,
                        editingEnabled = true,
                    )
                    assertTrue("$heroClass ${node.slotKey} rejected after legal prefix: " +
                        firstRank.error, firstRank.accepted)
                }

                rowNodes.forEach { node ->
                    repeat(3) { index ->
                        val rank = index + 1
                        val mutation = ArenaSkillTreeRules.allocate(
                            state = prefix,
                            heroClass = heroClass,
                            arenaLevel = 100,
                            ownedAttackIds = ownedAttacks,
                            nodeId = node.id,
                            targetRank = rank,
                            editingEnabled = true,
                        )
                        assertTrue("$heroClass ${node.slotKey} R$rank rejected: ${mutation.error}",
                            mutation.accepted)
                        prefix = mutation.state
                    }
                }
            }

            assertEquals("$heroClass three ranks across all nodes", 90,
                ArenaSkillTreeRules.spentPoints(prefix))
            assertTrue("$heroClass complete structural prefix remains valid",
                ArenaSkillTreeRules.validate(prefix, heroClass, 100, ownedAttacks))
        }
    }
}
