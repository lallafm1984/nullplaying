package com.nullplaying.engine.arena

import com.nullplaying.model.HeroClass
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ArenaCompleteProgressionTest {
    private val rules = ArenaProgressionRules
    private fun state(level: Int = 100) = ArenaProgressionState(unlocked = true, totalXp = rules.xpForLevel(level))

    @Test fun `144 unique definitions include every exact class branch rank and owned support contract`() {
        val catalog = ArenaProgressionCatalog.values
        assertEquals(144, catalog.size)
        assertEquals(144, catalog.map { it.id }.toSet().size)
        for (heroClass in HeroClass.entries) for (branch in listOf("A", "B", "C")) {
            val nodes = catalog.filter { it.heroClass == heroClass && it.branch == branch }
            assertEquals((1..7).map { "AT9_${heroClass}_${branch}0$it" }.toSet() + "AT9_${heroClass}_${branch}_CORE", nodes.map { it.id }.toSet())
            assertEquals(7, nodes.count { !it.isCore })
            assertEquals(1, nodes.count { it.isCore })
            nodes.forEach { node ->
                node.requiredSupportIds.forEach { supportId ->
                    val support = requireNotNull(ArenaSupportCatalog.find(supportId))
                    assertEquals(node.heroClass, support.heroClass)
                    assertTrue("${node.id}: ${support.id}", node.minHeroLevel >= support.unlockLevel)
                }
                assertEquals(node.requiredSupportIds.firstOrNull(), node.requiredSupportId)
            }
        }
        assertEquals(126, ArenaProgressionRuntime.implementedTraitIds.size)
        assertEquals(catalog.filterNot { it.isCore }.map { it.id }.toSet(), ArenaProgressionRuntime.implementedTraitIds)
    }

    @Test fun `all 2520 scalar rank enhancement cells and 18 cores provide Korean English Japanese copy`() {
        var cells = 0
        for (d in ArenaProgressionCatalog.values) {
            for (language in listOf("ko", "en", "ja")) {
                assertTrue("${d.id}/$language condition", d.conditionText(language).isNotBlank())
                assertTrue("${d.id}/$language limit", d.limitationText(language).isNotBlank())
            }
            for (rank in 1..d.maxRank) for (stage in 0..d.maxEnhancement) {
                val value = d.value(rank, stage)
                assertTrue("${d.id}/$rank/$stage", value > 0)
                if (!d.isCore) {
                    cells++
                    assertEquals(d.baseValues[rank - 1] * (1 + .1 * stage), value, 1e-9)
                }
                for (language in listOf("ko", "en", "ja")) {
                    val text = d.effectText(language, rank, stage)
                    assertTrue(text.isNotBlank())
                    assertFalse(text.contains("{value}"))
                    assertFalse(text.contains("TODO"))
                    assertFalse(Regex("\\bnull\\b").containsMatchIn(text))
                }
            }
        }
        assertEquals(2520, cells)
    }

    @Test fun `cores cost five allow no enhancement and are mutually exclusive across all branches`() {
        for (heroClass in HeroClass.entries) {
            val supports = ArenaSupportCatalog.unlockedIds(heroClass, 100)
            val cores = ArenaProgressionCatalog.forClass(heroClass).filter { it.isCore }
            for (core in cores) {
                assertEquals(5, core.basePointCost(1))
                assertEquals(1, core.maxRank)
                assertEquals(0, core.maxEnhancement)
                assertFalse(rules.allocate(state(4), heroClass, 100, supports, core.id, 1, 0).accepted)
                val purchased = rules.allocate(state(5), heroClass, 100, supports, core.id, 1, 0)
                assertTrue("${core.id}: ${purchased.error}", purchased.accepted)
                assertEquals(5, rules.view(purchased.state, 0).baseSpent)
                assertFalse(rules.allocate(state(), heroClass, 100, supports, core.id, 2, 0).accepted)
                assertFalse(rules.allocate(state(), heroClass, 100, supports, core.id, 1, 1).accepted)
                val high = purchased.state.copy(totalXp = rules.MAX_TOTAL_XP)
                for (other in cores.filterNot { it.id == core.id }) {
                    val rejected = rules.allocate(high, heroClass, 100, supports, other.id, 1, 0)
                    assertEquals("core_limit", rejected.error)
                    assertEquals(high, rejected.state)
                }
                assertEquals(state(5).totalXp, rules.reset(purchased.state).state.totalXp)
                assertEquals(5, rules.view(rules.reset(purchased.state).state, 0).baseAvailable)
            }
        }
    }

    @Test fun `every required support is independently required and foreign builds fail atomically`() {
        for (d in ArenaProgressionCatalog.values) {
            val supports = d.requiredSupportIds.toSet()
            val accepted = rules.allocate(state(), d.heroClass, 100, supports, d.id, 1, 0)
            assertTrue("${d.id}: ${accepted.error}", accepted.accepted)
            for (missing in supports) {
                val rejected = rules.allocate(state(), d.heroClass, 100, supports - missing, d.id, 1, 0)
                assertEquals("support_required", rejected.error)
                assertEquals(state(), rejected.state)
                assertFalse(rules.isValidForFighter(accepted.state, d.heroClass, 100, supports - missing))
            }
            assertFalse(rules.isValidForFighter(accepted.state, HeroClass.entries.first { it != d.heroClass }, 100, supports))
            assertEquals(accepted.state, Json.decodeFromString<ArenaProgressionState>(Json.encodeToString(accepted.state)))
        }
        assertEquals(ArenaProgressionState(), Json.decodeFromString<ArenaProgressionState>("{}"))
    }

    @Test fun `all early classes have enough legal low hero level nodes to spend fifty plus fifty`() {
        for (heroClass in HeroClass.entries) for (firstBranch in listOf("A", "B", "C")) {
            val supports = ArenaSupportCatalog.unlockedIds(heroClass, 10)
            val eligible = ArenaProgressionCatalog.forClass(heroClass).filter { it.minHeroLevel <= 10 && it.requiredSupportIds.all(supports::contains) }
                .sortedWith(compareBy({ if (it.branch == firstBranch) 0 else 1 }, { it.isCore }, { it.id }))
            var current = state()
            for (d in eligible.filterNot { it.isCore }) {
                val rank = minOf(5, rules.view(current, 0).baseAvailable)
                if (rank == 0) break
                val mutation = rules.allocate(current, heroClass, 10, supports, d.id, rank, 0)
                assertTrue(mutation.accepted)
                current = mutation.state
            }
            assertEquals("$heroClass/$firstBranch base", 50, rules.view(current, 0).baseSpent)
            for (allocation in current.allocations.take(5)) {
                val mutation = rules.allocate(current, heroClass, 10, supports, allocation.id, allocation.rank, 3)
                assertTrue(mutation.accepted)
                current = mutation.state
            }
            assertEquals(50, rules.view(current, 0).enhancementSpent)
            assertTrue(rules.isValidForFighter(current, heroClass, 10, supports))
        }
    }

    @Test fun `conditional token lifecycle does not refresh peek or survive expiry and KO`() {
        val runtime = ArenaProgressionRuntime(listOf(ArenaSupportTraitRank("AT9_WARRIOR_C01", 5, 3)))
        val hit = ArenaGrowthContext(1, ArenaGrowthEvent.TAKEN_HIT, hpDamage = 10.0, selfHpRatio = .9, eventSequence = 10)
        val activation = runtime.observe(hit).single()
        assertEquals(4, activation.expiresAtTurn)
        assertTrue(runtime.observe(hit.copy(turn = 2, eventSequence = 11)).isEmpty())
        val attack = ArenaGrowthContext(2, isBasic = true)
        val first = runtime.modifiers(attack, ArenaGrowthPhase.DAMAGE)
        assertEquals(first, runtime.modifiers(attack, ArenaGrowthPhase.DAMAGE))
        assertEquals(6.5, first.single().value, 1e-9)
        assertTrue(runtime.modifiers(attack.copy(selfHpRatio = 0.0), ArenaGrowthPhase.DAMAGE).isEmpty())
        runtime.consume(first)
        assertTrue(runtime.modifiers(attack, ArenaGrowthPhase.DAMAGE).isEmpty())
        assertTrue(runtime.observe(hit.copy(turn = 3)).isEmpty())
        assertEquals(1, runtime.observe(hit.copy(turn = 4)).size)
        assertTrue(runtime.modifiers(attack.copy(turn = 8), ArenaGrowthPhase.DAMAGE).isEmpty())
    }
}
