package com.nullplaying.engine.arena

import com.nullplaying.engine.SkillCatalog
import com.nullplaying.model.HeroClass
import java.security.MessageDigest
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Release-contract coverage for the V4 arena tree.
 *
 * The matrix below is deliberately a deterministic crash/safety smoke test. It does not turn a
 * tiny fixed-seed sample into a class win-rate acceptance claim; statistical balance still needs a
 * separately sized laboratory run and explicit thresholds.
 */
class ArenaSkillTreeV4MatrixSmokeTest {
    private val milestoneRanks = listOf(1, 5, 10)

    @Test
    fun `six class catalogs resolve legal rank one five and ten snapshots without rank reversal`() {
        var resolvedAttacks = 0
        var resolvedSupports = 0

        HeroClass.entries.forEach { heroClass ->
            val nodes = ArenaSkillTreeCatalog.forClass(heroClass)
            val prefixes = legalThreeRankPrefixes(heroClass)
            val template = ArenaSupportQaFixtures.fullFighter(
                heroClass = heroClass,
                heroLevel = 95,
                id = "v4-resolution-${heroClass.name.lowercase()}",
            )

            nodes.forEach { node ->
                val legalStates = milestoneRanks.associateWith { rank ->
                    val state = allocateToRank(
                        initial = prefixes.getValue(node.row),
                        heroClass = heroClass,
                        nodeId = node.id,
                        targetRank = rank,
                    )
                    assertTrue(
                        "$heroClass ${node.slotKey} R$rank must satisfy the real parent, spend and budget rules",
                        ArenaSkillTreeRules.validate(state, heroClass, 100, ownedAttackIds(heroClass)),
                    )
                    assertTrue(ArenaSkillTreeRules.spentPoints(state) <= 100)
                    assertEquals(rank, state.allocations.single { it.nodeId == node.id }.rank)

                    val input = arenaInput(state, template)
                    ArenaSupportTurnEngine.validate(input)
                    state to input
                }

                when (node.kind) {
                    ArenaSkillNodeKind.ATTACK -> {
                        val snapshots = milestoneRanks.map { rank ->
                            val input = legalStates.getValue(rank).second
                            requireNotNull(input.fighter.attacks.single { it.id == node.id }.arena)
                        }
                        assertEquals(milestoneRanks, snapshots.map { it.rank })
                        assertStrictlyIncreasing("$heroClass ${node.slotKey} damage",
                            snapshots.map { it.damagePercent.toDouble() })
                        assertStrictlyIncreasing("$heroClass ${node.slotKey} MP",
                            snapshots.map { it.mpCost.toDouble() })
                        assertNonIncreasing("$heroClass ${node.slotKey} cooldown",
                            snapshots.map { it.cooldownTurns.toDouble() })
                        assertConstant("$heroClass ${node.slotKey} preparation",
                            snapshots.map { it.prepareTurns.toDouble() })
                        assertConstant("$heroClass ${node.slotKey} duration",
                            snapshots.map { it.durationTurns.toDouble() })
                        assertNonDecreasing("$heroClass ${node.slotKey} applications",
                            snapshots.map { it.maxApplications.toDouble() })
                        assertConstant("$heroClass ${node.slotKey} earliest turn",
                            snapshots.map { it.earliestTurn.toDouble() })
                        assertNonDecreasing("$heroClass ${node.slotKey} HP cap",
                            snapshots.map { it.hpDamageCapPercent })
                        assertEquals(1, snapshots.map { it.oncePerBattle }.distinct().size)
                        assertEquals(1, snapshots.map { it.effectKey }.distinct().size)
                        assertEquals(1, snapshots.map { it.effectValues.keys }.distinct().size)
                        snapshots.first().effectValues.keys.forEach { key ->
                            assertNoDirectionalReversal(
                                "$heroClass ${node.slotKey} effect $key",
                                snapshots.map { it.effectValues.getValue(key) },
                            )
                        }
                        snapshots.forEach { snapshot ->
                            assertTrue(snapshot.effectValues.values.all(Double::isFinite))
                            assertTrue(snapshot.tags.contains(node.slotKey))
                            assertTrue(snapshot.tags.contains(snapshot.effectKey))
                        }
                        resolvedAttacks += snapshots.size
                    }

                    ArenaSkillNodeKind.SUPPORT -> {
                        val base = requireNotNull(ArenaSupportCatalog.find(node.id))
                        val snapshots = milestoneRanks.map { rank ->
                            val input = legalStates.getValue(rank).second
                            assertEquals(rank, input.supportRanks.getValue(node.id))
                            ArenaSkillTreeCatalog.effectiveSupport(base, rank)
                        }
                        assertEquals(1, snapshots.map { it.id }.distinct().size)
                        assertEquals(1, snapshots.map { it.heroClass }.distinct().size)
                        assertEquals(1, snapshots.map { it.kind }.distinct().size)
                        assertStrictlyIncreasing("$heroClass ${node.slotKey} MP",
                            snapshots.map { it.mp.toDouble() })
                        assertNonIncreasing("$heroClass ${node.slotKey} cooldown",
                            snapshots.map { it.cooldownTurns.toDouble() })
                        assertNonIncreasing("$heroClass ${node.slotKey} cast",
                            snapshots.map { it.castTurns.toDouble() })
                        assertConstant("$heroClass ${node.slotKey} duration",
                            snapshots.map { it.durationTurns.toDouble() })
                        assertNonDecreasing("$heroClass ${node.slotKey} charges",
                            snapshots.map { it.charges.toDouble() })
                        assertNoDirectionalReversal("$heroClass ${node.slotKey} magnitude",
                            snapshots.map { it.magnitude })
                        assertNoDirectionalReversal("$heroClass ${node.slotKey} secondary",
                            snapshots.map { it.secondary })
                        assertConstant("$heroClass ${node.slotKey} threshold",
                            snapshots.map { it.threshold })
                        assertEquals(1, snapshots.map { it.oncePerBattle }.distinct().size)
                        snapshots.forEach { snapshot ->
                            assertTrue(snapshot.mp >= 1)
                            assertTrue(snapshot.cooldownTurns >= 0)
                            assertTrue(snapshot.charges in 0..5)
                            assertTrue(snapshot.magnitude.isFinite())
                            assertTrue(snapshot.secondary.isFinite())
                            assertFalse(snapshot.summaryText("ko").contains('{'))
                        }
                        resolvedSupports += snapshots.size
                    }
                }
            }
        }

        assertEquals(6 * 20 * milestoneRanks.size, resolvedAttacks)
        assertEquals(6 * 10 * milestoneRanks.size, resolvedSupports)
    }

    @Test
    fun `four level one hundred archetypes are legal and materially distinct for every class`() {
        HeroClass.entries.forEach { heroClass ->
            val builds = BuildArchetype.entries.associateWith { archetype ->
                build(heroClass, archetype).also { state ->
                    assertEquals("$heroClass $archetype budget", 100,
                        ArenaSkillTreeRules.spentPoints(state))
                    assertTrue("$heroClass $archetype tree validity",
                        ArenaSkillTreeRules.validate(state, heroClass, 100, ownedAttackIds(heroClass)))
                    ArenaSupportTurnEngine.validate(arenaInput(
                        state,
                        ArenaSupportQaFixtures.fullFighter(heroClass, 95,
                            "v4-build-${heroClass.name.lowercase()}-${archetype.name.lowercase()}"),
                    ))
                }
            }

            val digests = builds.mapValues { digest(it.value) }
            assertEquals("$heroClass archetype digests", BuildArchetype.entries.size,
                digests.values.toSet().size)
            builds.values.toList().indices.forEach { first ->
                for (second in first + 1 until builds.size) {
                    assertNotEquals(builds.values.elementAt(first).allocations,
                        builds.values.elementAt(second).allocations)
                }
            }

            val threeRoot = rankBySlot(heroClass, builds.getValue(BuildArchetype.THREE_ROOT_FOCUS))
            assertEquals(listOf(10, 10, 10), listOf("A01", "A02", "A03").map(threeRoot::getValue))
            assertEquals(10, threeRoot.size)
            assertTrue(threeRoot.values.all { it == 10 })

            val twoRoot = rankBySlot(heroClass, builds.getValue(BuildArchetype.TWO_ROOT_MIX))
            assertEquals(10, twoRoot.getValue("A01"))
            assertEquals(0, twoRoot["A02"] ?: 0)
            assertEquals(10, twoRoot.getValue("A03"))
            assertTrue(twoRoot.keys.any { it in setOf("A04", "S02", "A08", "A10") })
            assertTrue(twoRoot.keys.any { it in setOf("A05", "A07", "S03", "A11") })

            val broad = rankBySlot(heroClass, builds.getValue(BuildArchetype.BROAD_COUNTERPLAY))
            assertEquals(30, broad.size)
            assertTrue(ArenaSkillTreeCatalog.forClass(heroClass)
                .filter { it.kind == ArenaSkillNodeKind.ATTACK }.all { broad.getValue(it.slotKey) == 3 })
            assertTrue(ArenaSkillTreeCatalog.forClass(heroClass)
                .filter { it.kind == ArenaSkillNodeKind.SUPPORT }.all { broad.getValue(it.slotKey) == 4 })
            assertEquals(3, broad.getValue("A19"))

            val deep = rankBySlot(heroClass, builds.getValue(BuildArchetype.DEEP_TEN_ROW))
            assertEquals(10, deep.size)
            assertTrue(deep.values.all { it == 10 })
            assertEquals(10, deep.getValue("A20"))
            assertEquals((0..9).toSet(), ArenaSkillTreeCatalog.forClass(heroClass)
                .filter { it.slotKey in deep }.map { it.row }.toSet())

            println("arena-v4-builds class=$heroClass balanceAcceptance=NOT_EVALUATED " +
                digests.entries.joinToString(" ") { "${it.key}=${it.value}" })
        }
    }

    @Test
    fun `six by six legal V4 build matrix is deterministic side neutral and safety bounded`() {
        val classes = HeroClass.entries
        val representativeStrategies = mapOf(
            HeroClass.WARRIOR to BuildArchetype.THREE_ROOT_FOCUS,
            HeroClass.ROGUE to BuildArchetype.TWO_ROOT_MIX,
            HeroClass.RANGER to BuildArchetype.BROAD_COUNTERPLAY,
            HeroClass.MAGE to BuildArchetype.DEEP_TEN_ROW,
            HeroClass.CLERIC to BuildArchetype.BROAD_COUNTERPLAY,
            HeroClass.PALADIN to BuildArchetype.DEEP_TEN_ROW,
        )
        val inputs = classes.associateWith { heroClass ->
            val strategy = representativeStrategies.getValue(heroClass)
            val state = build(heroClass, strategy)
            arenaInput(
                state,
                ArenaSupportQaFixtures.fullFighter(
                    heroClass,
                    95,
                    "v4-matrix-${heroClass.name.lowercase()}",
                ),
            ).also(ArenaSupportTurnEngine::validate)
        }
        val rules = ArenaTurnRules(
            safetyTurnLimit = 160,
            hitChance = 0.90,
            damageVariance = 0.04,
            tierScaling = false,
            masteryScaling = false,
            mpMode = ArenaMpMode.FIXED_100,
            formula = ArenaStatFormula(
                healthBase = 550.0,
                healthScale = 0.0,
                attackBase = 70.0,
                attackScale = 0.0,
            ),
        )
        val matrix = Array(classes.size) { arrayOfNulls<ArenaSupportResult>(classes.size) }

        classes.forEachIndexed { row, leftClass ->
            classes.forEachIndexed { column, rightClass ->
                val left = inputs.getValue(leftClass)
                val right = if (leftClass == rightClass) {
                    val original = inputs.getValue(rightClass)
                    original.copy(fighter = original.fighter.copy(id = "${original.fighter.id}-clone"))
                } else {
                    inputs.getValue(rightClass)
                }
                val low = minOf(row, column).toLong()
                val high = maxOf(row, column).toLong()
                val seed = MATRIX_SEED xor (low shl 32) xor high
                val result = ArenaSupportTurnEngine.simulate(left, right, seed, rules)
                val replay = ArenaSupportTurnEngine.simulate(left, right, seed, rules)
                val mirrored = ArenaSupportTurnEngine.simulate(right, left, seed, rules)

                assertEquals("$leftClass vs $rightClass replay", result, replay)
                assertEquals("$leftClass vs $rightClass argument-side swap", result, mirrored)
                assertSafeCompletedResult(result, left.fighter.id, right.fighter.id, rules)
                matrix[row][column] = result
            }
        }

        classes.indices.forEach { row ->
            classes.indices.forEach { column ->
                assertEquals("ordered 6x6 cell must be semantic-ID stable: $row,$column",
                    matrix[row][column], matrix[column][row])
            }
        }
        println("arena-v4-matrix executions=108 cells=36 fixedSeeds=21 " +
            "scope=crash_safety_determinism_side_handling balanceAcceptance=NOT_EVALUATED")
    }

    private enum class BuildArchetype {
        THREE_ROOT_FOCUS,
        TWO_ROOT_MIX,
        BROAD_COUNTERPLAY,
        DEEP_TEN_ROW,
    }

    private fun build(heroClass: HeroClass, archetype: BuildArchetype): ArenaSkillTreeState {
        var state = ArenaSkillTreeRules.initialize(null, heroClass)
        val slots = when (archetype) {
            BuildArchetype.THREE_ROOT_FOCUS -> listOf(
                "A01", "A02", "A03", "A04", "S01", "A05", "S02", "A06", "A07", "A09",
            )
            BuildArchetype.TWO_ROOT_MIX -> listOf(
                "A01", "A03", "A04", "A05", "S02", "A07", "A08", "S03", "A10", "A11",
            )
            BuildArchetype.DEEP_TEN_ROW -> listOf(
                "A03", "A05", "A07", "S03", "A11", "A13", "S06", "A17", "S09", "A20",
            )
            BuildArchetype.BROAD_COUNTERPLAY -> emptyList()
        }
        if (archetype == BuildArchetype.BROAD_COUNTERPLAY) {
            ArenaSkillTreeCatalog.forClass(heroClass).groupBy { it.row }.toSortedMap().values.forEach { row ->
                row.forEach { node -> state = allocateToRank(state, heroClass, node.id, 3) }
            }
            ArenaSkillTreeCatalog.forClass(heroClass)
                .filter { it.kind == ArenaSkillNodeKind.SUPPORT }
                .forEach { node -> state = allocateToRank(state, heroClass, node.id, 4) }
        } else {
            slots.forEach { slot ->
                state = allocateToRank(state, heroClass, nodeBySlot(heroClass, slot).id, 10)
            }
        }
        return state
    }

    /** State immediately before each row, with every earlier row at rank three. */
    private fun legalThreeRankPrefixes(heroClass: HeroClass): Map<Int, ArenaSkillTreeState> {
        var state = ArenaSkillTreeRules.initialize(null, heroClass)
        val prefixes = linkedMapOf<Int, ArenaSkillTreeState>()
        ArenaSkillTreeCatalog.forClass(heroClass).groupBy { it.row }.toSortedMap().forEach { (row, nodes) ->
            prefixes[row] = state
            nodes.forEach { node -> state = allocateToRank(state, heroClass, node.id, 3) }
        }
        return prefixes
    }

    private fun allocateToRank(
        initial: ArenaSkillTreeState,
        heroClass: HeroClass,
        nodeId: String,
        targetRank: Int,
    ): ArenaSkillTreeState {
        var state = initial
        val startingRank = state.allocations.firstOrNull { it.nodeId == nodeId }?.rank ?: 0
        for (rank in startingRank + 1..targetRank) {
            val mutation = ArenaSkillTreeRules.allocate(
                state = state,
                heroClass = heroClass,
                arenaLevel = 100,
                ownedAttackIds = ownedAttackIds(heroClass),
                nodeId = nodeId,
                targetRank = rank,
                editingEnabled = true,
            )
            assertTrue("$heroClass $nodeId R$rank rejected: ${mutation.error}", mutation.accepted)
            state = mutation.state
        }
        return state
    }

    private fun arenaInput(state: ArenaSkillTreeState, template: ArenaSupportInput): ArenaSupportInput {
        val ranks = state.allocations.associate { it.nodeId to it.rank }
        val sourceAttacks = template.fighter.attacks.associateBy { it.id }
        val resolvedAttacks = ArenaSkillTreeCatalog.forClass(state.heroClass)
            .filter { it.kind == ArenaSkillNodeKind.ATTACK }
            .mapNotNull { node ->
                val rank = ranks[node.id] ?: return@mapNotNull null
                ArenaTurnInputAdapter.resolveAttack(sourceAttacks.getValue(node.id), state.heroClass, rank)
            }
        val supportRanks = ArenaSkillTreeCatalog.forClass(state.heroClass)
            .filter { it.kind == ArenaSkillNodeKind.SUPPORT && it.id in ranks }
            .associate { it.id to ranks.getValue(it.id) }
        return template.copy(
            fighter = template.fighter.copy(attacks = resolvedAttacks),
            supportIds = supportRanks.keys,
            supportRanks = supportRanks,
            traits = emptyList(),
            arenaLevel = 100,
        )
    }

    private fun assertSafeCompletedResult(
        result: ArenaSupportResult,
        leftId: String,
        rightId: String,
        rules: ArenaTurnRules,
    ) {
        assertEquals(ARENA_SUPPORT_RULES_VERSION, result.rulesVersion)
        assertEquals(ArenaRunStatus.COMPLETED, result.status)
        assertTrue(result.turns in 1..rules.safetyTurnLimit)
        assertTrue(result.winnerId in setOf(leftId, rightId))
        assertEquals(setOf(leftId, rightId), result.fighters.keys)
        assertEquals(result.events.indices.toList(), result.events.map { it.sequence })
        assertTrue(result.events.none { it.type == ArenaSupportEventType.SAFETY_ABORT })
        assertEquals(ArenaSupportEventType.END, result.events.last().type)
        result.fighters.values.forEach { fighter ->
            assertTrue(fighter.hp.isFinite() && fighter.hp in 0.0..fighter.maxHp)
            assertTrue(fighter.shield.isFinite() && fighter.shield >= 0.0)
            assertTrue(fighter.mpUnits in 0..fighter.maxMpUnits)
        }
        result.events.forEach { event ->
            assertTrue(event.amount.isFinite() && event.amount >= 0.0)
            event.hpBefore?.let { assertTrue(it.isFinite() && it >= 0.0) }
            event.hpAfter?.let { assertTrue(it.isFinite() && it >= 0.0) }
            event.shieldBefore?.let { assertTrue(it.isFinite() && it >= 0.0) }
            event.shieldAfter?.let { assertTrue(it.isFinite() && it >= 0.0) }
            if (event.causeSequence != null) assertTrue(event.causeSequence < event.sequence)
        }
    }

    private fun rankBySlot(heroClass: HeroClass, state: ArenaSkillTreeState): Map<String, Int> {
        val idToSlot = ArenaSkillTreeCatalog.forClass(heroClass).associate { it.id to it.slotKey }
        return state.allocations.associate { idToSlot.getValue(it.nodeId) to it.rank }
    }

    private fun nodeBySlot(heroClass: HeroClass, slot: String): ArenaSkillTreeNodeDefinition =
        ArenaSkillTreeCatalog.forClass(heroClass).single { it.slotKey == slot }

    private fun ownedAttackIds(heroClass: HeroClass): Set<String> =
        SkillCatalog.forClass(heroClass).mapTo(linkedSetOf()) { it.catalogId }

    private fun digest(state: ArenaSkillTreeState): String {
        val canonical = state.allocations.joinToString("|") { "${it.nodeId}:${it.rank}" }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
            .take(6).joinToString("") { "%02x".format(it) }
    }

    private fun assertStrictlyIncreasing(label: String, values: List<Double>) {
        assertTrue("$label: $values", values.zipWithNext().all { (before, after) -> after > before })
    }

    private fun assertNonIncreasing(label: String, values: List<Double>) {
        assertTrue("$label: $values", values.zipWithNext().all { (before, after) -> after <= before })
    }

    private fun assertNonDecreasing(label: String, values: List<Double>) {
        assertTrue("$label: $values", values.zipWithNext().all { (before, after) -> after >= before })
    }

    private fun assertConstant(label: String, values: List<Double>) {
        assertTrue("$label: $values", values.all { abs(it - values.first()) <= EPSILON })
    }

    /** Direction comes from the authored endpoints; middle milestones may plateau but not reverse. */
    private fun assertNoDirectionalReversal(label: String, values: List<Double>) {
        when {
            values.last() > values.first() + EPSILON -> assertNonDecreasing(label, values)
            values.last() < values.first() - EPSILON -> assertNonIncreasing(label, values)
            else -> assertConstant(label, values)
        }
    }

    private companion object {
        const val EPSILON = 1e-9
        const val MATRIX_SEED = 0x5A17_4EE1L
    }
}
