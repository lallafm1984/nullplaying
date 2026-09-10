package com.nullplaying.engine

import com.nullplaying.model.BattleHeroClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Executable catalog contract for ARENA_TALENT_TREE_V2_20260905.md.
 *
 * Reflection is intentional while the V2 model is landing: this test compiles against V1, skips
 * only while the 144-node V2 catalog is absent, and starts enforcing the final public contract as
 * soon as that catalog is exposed.
 */
class ArenaTalentTreeV2CatalogContractTest {
    @Test
    fun `catalog contains six classes three specializations each and 144 stable nodes`() {
        requireV2Catalog()

        assertEquals(6, BattleHeroClass.entries.size)
        assertEquals(18, HeroPathCatalog.branches.size)
        assertEquals(144, HeroPathCatalog.nodes.size)
        BattleHeroClass.entries.forEach { heroClass ->
            assertEquals("$heroClass must expose exactly three specializations", 3, HeroPathCatalog.branchesFor(heroClass).size)
            assertEquals("$heroClass must expose 24 nodes", 24, HeroPathCatalog.nodesFor(heroClass).size)
        }

        val nodeIds = HeroPathCatalog.nodes.map { it.stringProperty("nodeId", "traitId") }
        assertEquals(144, nodeIds.distinct().size)
        assertTrue(nodeIds.all(String::isNotBlank))
        assertFalse("V1 numeric TRAIT_001..100 identifiers must be retired", nodeIds.any { it.matches(Regex("TRAIT_\\d{3}")) })
    }

    @Test
    fun `every specialization has the exact eight-node topology and ten legal ranks`() {
        requireV2Catalog()

        HeroPathCatalog.branches.forEach { specialization ->
            val nodes = HeroPathCatalog.nodesFor(specialization.branch)
            assertEquals("${specialization.branch} node count", 8, nodes.size)

            val slots = nodes.groupBy { it.slotFamily() }
            assertEquals("${specialization.branch} foundation count", 2, slots["FOUNDATION"].orEmpty().size)
            assertEquals("${specialization.branch} A-B choice count", 2, slots["CHOICE"].orEmpty().size)
            assertEquals("${specialization.branch} special count", 2, slots["SPECIAL"].orEmpty().size)
            assertEquals("${specialization.branch} advanced tactic count", 1, slots["ADVANCED_TACTIC"].orEmpty().size)
            assertEquals("${specialization.branch} core count", 1, slots["CORE"].orEmpty().size)

            slots["FOUNDATION"].orEmpty().forEach { assertEquals(2, it.intProperty("maxRank", "maximumRank")) }
            slots["CHOICE"].orEmpty().forEach { assertEquals(1, it.intProperty("maxRank", "maximumRank")) }
            slots["SPECIAL"].orEmpty().forEach { assertEquals(1, it.intProperty("maxRank", "maximumRank")) }
            slots["ADVANCED_TACTIC"].orEmpty().forEach { assertEquals(2, it.intProperty("maxRank", "maximumRank")) }
            slots["CORE"].orEmpty().forEach { assertEquals(1, it.intProperty("maxRank", "maximumRank")) }

            // Raw ranks total 11, but exactly one of the two rank-1 A/B choices is legal.
            val rawRanks = nodes.sumOf { it.intProperty("maxRank", "maximumRank") }
            val legalRanks = rawRanks - 1
            assertEquals("${specialization.branch} must complete at ten invested points", 10, legalRanks)
        }
    }

    @Test
    fun `choice A and B share one exclusive group and core remains globally singular`() {
        requireV2Catalog()

        HeroPathCatalog.branches.forEach { specialization ->
            val choices = HeroPathCatalog.nodesFor(specialization.branch).filter { it.slotFamily() == "CHOICE" }
            val groupIds = choices.map { it.stringProperty("choiceGroupId") }
            assertEquals(2, choices.size)
            assertTrue("${specialization.branch} choice group must be explicit", groupIds.all(String::isNotBlank))
            assertEquals("${specialization.branch} A/B nodes must share one choiceGroup", 1, groupIds.distinct().size)

            val slotNames = choices.map { it.enumName("nodeSlot", "slot", "nodeType", "kind") }.toSet()
            assertTrue("${specialization.branch} requires an A choice", slotNames.any { it.endsWith("_A") || it == "CHOICE_A" })
            assertTrue("${specialization.branch} requires a B choice", slotNames.any { it.endsWith("_B") || it == "CHOICE_B" })
        }

        assertEquals(18, HeroPathCatalog.nodes.count { it.slotFamily() == "CORE" })
    }

    @Test
    fun `tier gates form a reachable 0 3 6 9 investment ladder`() {
        requireV2Catalog()

        HeroPathCatalog.branches.forEach { specialization ->
            val nodes = HeroPathCatalog.nodesFor(specialization.branch)
            val byTier = nodes.groupBy { it.tierNumber() }
            assertEquals("${specialization.branch} tier-1 node count", 2, byTier[1].orEmpty().size)
            assertEquals("${specialization.branch} tier-2 node count", 3, byTier[2].orEmpty().size)
            assertEquals("${specialization.branch} tier-3 node count", 2, byTier[3].orEmpty().size)
            assertEquals("${specialization.branch} core node count", 1, byTier[4].orEmpty().size)

            byTier.getValue(1).forEach { it.assertGate(level = 5, investments = 0) }
            byTier.getValue(2).forEach { it.assertGate(level = 20, investments = 3) }
            byTier.getValue(3).forEach { it.assertGate(level = 35, investments = 6) }
            byTier.getValue(4).forEach { it.assertGate(level = 50, investments = 9) }
        }
    }

    @Test
    fun `Korean technique summaries avoid fixed particles after dynamic names`() {
        requireV2Catalog()

        HeroPathCatalog.nodes.forEach { node ->
            listOf("을 ", "를 ", "로 ", "으로 ").forEach { fixedParticle ->
                assertFalse(
                    "${node.nodeId} must not append fixed '$fixedParticle' directly to its dynamic Korean name",
                    node.summaryKo.contains(node.nameKo + fixedParticle),
                )
            }
        }
    }

    private fun requireV2Catalog() {
        assumeTrue("V2 catalog has not landed yet", HeroPathCatalog.nodes.size == 144)
    }
}

private fun Any.assertGate(level: Int, investments: Int) {
    assertEquals(level, intProperty("minimumLevel", "minLevel"))
    assertEquals(investments, intProperty("requiredBranchInvestments", "requiredSpecializationInvestments", "requiredSpecPoints"))
}

private fun Any.slotFamily(): String {
    val slot = enumName("nodeSlot", "slot", "nodeType", "kind")
    return when {
        "FOUNDATION" in slot -> "FOUNDATION"
        "CHOICE" in slot -> "CHOICE"
        "SPECIAL" in slot -> "SPECIAL"
        "ADVANCED" in slot || "TACTIC" in slot -> "ADVANCED_TACTIC"
        "CORE" in slot -> "CORE"
        else -> error("Unknown V2 node slot $slot on $this")
    }
}

private fun Any.tierNumber(): Int {
    val value = property("tier")
    if (value is Number) return value.toInt()
    val name = (value as? Enum<*>)?.name ?: value.toString()
    if (name.contains("CORE", ignoreCase = true)) return 4
    return Regex("[1-4]").find(name)?.value?.toInt()
        ?: error("Cannot interpret tier $value on $this")
}

private fun Any.enumName(vararg names: String): String {
    val value = property(*names)
    return ((value as? Enum<*>)?.name ?: value.toString()).uppercase()
}

private fun Any.stringProperty(vararg names: String): String = property(*names).toString()

private fun Any.intProperty(vararg names: String): Int {
    val value = property(*names)
    return (value as? Number)?.toInt() ?: value.toString().toInt()
}

private fun Any.property(vararg names: String): Any {
    names.forEach { name ->
        val getter = "get" + name.replaceFirstChar { it.uppercase() }
        javaClass.methods.firstOrNull { it.parameterCount == 0 && it.name == getter }?.let { method ->
            val value = method.invoke(this)
            assertNotNull("$javaClass.$getter returned null", value)
            return value!!
        }
        javaClass.declaredFields.firstOrNull { it.name == name }?.let { field ->
            field.isAccessible = true
            val value = field.get(this)
            assertNotNull("$javaClass.$name returned null", value)
            return value!!
        }
    }
    error("${javaClass.name} must expose one of ${names.joinToString()}")
}
