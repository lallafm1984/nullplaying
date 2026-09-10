package com.nullplaying.engine

import com.nullplaying.model.BattleHeroClass
import com.nullplaying.model.HeroPathMutationStatus
import com.nullplaying.model.HeroPathState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ArenaTalentTreeV2SnapshotContractTest {
    @Test
    fun `battle snapshot pins allocation revision nodes digest and initial class resource`() {
        requireV2Catalog()
        val create = HeroPathEngine.javaClass.methods.firstOrNull {
            it.name == "createBattleSnapshot" && it.parameterCount == 1
        } ?: error("HeroPathEngine.createBattleSnapshot(state) is required")
        val base = HeroPathEngine.reconcilePointGrants(
            HeroPathState(heroClass = BattleHeroClass.WARRIOR, revision = 71L),
            heroLevel = 100,
        )
        val root = HeroPathCatalog.nodesFor(BattleHeroClass.WARRIOR)
            .first { it.minimumLevel <= 5L && it.requiredBranchInvestments == 0 }
            .traitId
        val mutation = HeroPathEngine.applyDraft(base, listOf(root))
        assertEquals(HeroPathMutationStatus.APPLIED, mutation.status)

        val snapshot = requireNotNull(create.invoke(HeroPathEngine, mutation.state))
        val nodes = snapshot.listProperty("nodes", "allocations")
        val specializationIds = snapshot.listProperty("specializationIds", "branchIds")

        assertTrue(snapshot.intProperty("treeVersion", "catalogVersion") >= 2)
        assertEquals(mutation.state.revision, snapshot.longProperty("allocationRevision", "revision"))
        assertTrue(nodes.isNotEmpty())
        assertTrue(specializationIds.isNotEmpty())
        assertTrue(snapshot.stringProperty("derivedRulesDigest", "rulesDigest").isNotBlank())
        assertTrue(snapshot.hasProperty("activeCoreNodeId"))
        assertTrue(snapshot.hasProperty("initialClassCharge", "initialClassResource"))
    }

    @Test
    fun `committing or resetting later cannot mutate an already-created battle snapshot`() {
        requireV2Catalog()
        val create = HeroPathEngine.javaClass.methods.firstOrNull {
            it.name == "createBattleSnapshot" && it.parameterCount == 1
        } ?: error("HeroPathEngine.createBattleSnapshot(state) is required")
        val base = HeroPathEngine.reconcilePointGrants(
            HeroPathState(heroClass = BattleHeroClass.MAGE, revision = 5L),
            heroLevel = 100,
        )
        val firstSnapshot = requireNotNull(create.invoke(HeroPathEngine, base))
        val firstSnapshotValue = firstSnapshot.toString()
        val firstDigest = firstSnapshot.stringProperty("derivedRulesDigest", "rulesDigest")
        val firstRevision = firstSnapshot.longProperty("allocationRevision", "revision")

        val root = HeroPathCatalog.nodesFor(BattleHeroClass.MAGE)
            .first { it.minimumLevel <= 5L && it.requiredBranchInvestments == 0 }
            .traitId
        val allocated = HeroPathEngine.applyDraft(base, listOf(root))
        assertEquals(HeroPathMutationStatus.APPLIED, allocated.status)
        val secondSnapshot = requireNotNull(create.invoke(HeroPathEngine, allocated.state))
        HeroPathEngine.resetAllocation(allocated.state)

        assertEquals(firstSnapshotValue, firstSnapshot.toString())
        assertEquals(firstRevision, firstSnapshot.longProperty("allocationRevision", "revision"))
        assertEquals(firstDigest, firstSnapshot.stringProperty("derivedRulesDigest", "rulesDigest"))
        assertNotEquals(firstSnapshot.longProperty("allocationRevision", "revision"), secondSnapshot.longProperty("allocationRevision", "revision"))
        assertNotEquals(firstSnapshot.stringProperty("derivedRulesDigest", "rulesDigest"), secondSnapshot.stringProperty("derivedRulesDigest", "rulesDigest"))
    }

    @Test
    fun `same committed state creates equal deterministic snapshots and class validation rejects mismatch`() {
        requireV2Catalog()
        val create = HeroPathEngine.javaClass.methods.firstOrNull {
            it.name == "createBattleSnapshot" && it.parameterCount == 1
        } ?: error("HeroPathEngine.createBattleSnapshot(state) is required")
        val validate = HeroPathEngine.javaClass.methods.firstOrNull {
            it.name == "validateBattleSnapshot" && it.parameterCount == 2
        } ?: error("HeroPathEngine.validateBattleSnapshot(snapshot, expectedClass) is required")
        val state = HeroPathEngine.reconcilePointGrants(
            HeroPathState(heroClass = BattleHeroClass.CLERIC, revision = 11L),
            heroLevel = 80,
        )

        val first = requireNotNull(create.invoke(HeroPathEngine, state))
        val second = requireNotNull(create.invoke(HeroPathEngine, state))
        val accepted = requireNotNull(validate.invoke(HeroPathEngine, first, BattleHeroClass.CLERIC))
        val rejected = requireNotNull(validate.invoke(HeroPathEngine, first, BattleHeroClass.ROGUE))

        assertEquals(first, second)
        assertTrue("matching class snapshot must validate", accepted.validationAccepted())
        assertFalse("cross-class snapshot must be rejected", rejected.validationAccepted())
    }

    private fun requireV2Catalog() {
        assumeTrue("V2 catalog has not landed yet", HeroPathCatalog.nodes.size == 144)
    }
}

private fun Any.validationAccepted(): Boolean = when (this) {
    is Boolean -> this
    is Enum<*> -> name in setOf("VALID", "ACCEPTED", "OK")
    else -> {
        val getter = javaClass.methods.firstOrNull {
            it.parameterCount == 0 && it.name in setOf("getAccepted", "isAccepted", "getValid", "isValid")
        } ?: error("${javaClass.name} validation result must expose accepted/isValid")
        getter.invoke(this) as? Boolean
            ?: error("${javaClass.name} validation accepted value must be Boolean")
    }
}

private fun Any.hasProperty(vararg names: String): Boolean = names.any { name ->
    val getter = "get" + name.replaceFirstChar { it.uppercase() }
    javaClass.methods.any { it.parameterCount == 0 && it.name == getter } ||
        javaClass.declaredFields.any { it.name == name }
}

private fun Any.listProperty(vararg names: String): List<*> = snapshotProperty(*names) as? List<*>
    ?: error("${javaClass.name}.${names.joinToString()} must be a List")

private fun Any.stringProperty(vararg names: String): String = snapshotProperty(*names)?.toString().orEmpty()

private fun Any.intProperty(vararg names: String): Int = (snapshotProperty(*names) as? Number)?.toInt()
    ?: error("${javaClass.name}.${names.joinToString()} must be numeric")

private fun Any.longProperty(vararg names: String): Long = (snapshotProperty(*names) as? Number)?.toLong()
    ?: error("${javaClass.name}.${names.joinToString()} must be numeric")

private fun Any.snapshotProperty(vararg names: String): Any? {
    names.forEach { name ->
        val getter = "get" + name.replaceFirstChar { it.uppercase() }
        javaClass.methods.firstOrNull { it.parameterCount == 0 && it.name == getter }?.let { return it.invoke(this) }
        javaClass.declaredFields.firstOrNull { it.name == name }?.let { field ->
            field.isAccessible = true
            return field.get(this)
        }
    }
    error("${javaClass.name} must expose one of ${names.joinToString()}")
}
