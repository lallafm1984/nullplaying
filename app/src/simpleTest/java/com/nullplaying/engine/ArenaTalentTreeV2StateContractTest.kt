package com.nullplaying.engine

import com.nullplaying.model.BattleHeroClass
import com.nullplaying.model.HeroPathMutation
import com.nullplaying.model.HeroPathMutationStatus
import com.nullplaying.model.HeroPathState
import com.nullplaying.model.HeroPathTraitProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ArenaTalentTreeV2StateContractTest {
    @Test
    fun `offline level jumps grant every fifth-level point and level loss never revokes it`() {
        val initial = HeroPathState(heroClass = BattleHeroClass.WARRIOR)
        val level4 = HeroPathEngine.reconcilePointGrants(initial, heroLevel = 4)
        val level5 = HeroPathEngine.reconcilePointGrants(level4, heroLevel = 5)
        val offlineLevel100 = HeroPathEngine.reconcilePointGrants(level5, heroLevel = 100)
        val levelDropped25 = HeroPathEngine.reconcilePointGrants(offlineLevel100, heroLevel = 25)

        assertEquals(0L, level4.earnedPoints)
        assertEquals(1L, level5.earnedPoints)
        assertEquals(20L, offlineLevel100.earnedPoints)
        assertEquals(100L, offlineLevel100.lastGrantedMilestone)
        assertEquals(offlineLevel100.earnedPoints, levelDropped25.earnedPoints)
        assertEquals(offlineLevel100.lastGrantedMilestone, levelDropped25.lastGrantedMilestone)
        assertLedgerInvariant(levelDropped25)
    }

    @Test
    fun `invalid batch is atomic and cannot partially spend points or bump revision`() {
        val base = HeroPathEngine.reconcilePointGrants(
            HeroPathState(heroClass = BattleHeroClass.WARRIOR, revision = 19L),
            heroLevel = 100,
        )
        val warrior = HeroPathCatalog.nodesFor(BattleHeroClass.WARRIOR).first().traitId
        val rogue = HeroPathCatalog.nodesFor(BattleHeroClass.ROGUE).first().traitId

        val result = HeroPathEngine.applyDraft(base, listOf(warrior, rogue))

        assertEquals(HeroPathMutationStatus.INVALID_ALLOCATION, result.status)
        assertEquals(base, result.state)
        assertLedgerInvariant(result.state)
    }

    @Test
    fun `valid batch commits all nodes with exactly one revision increment`() {
        val base = HeroPathEngine.reconcilePointGrants(
            HeroPathState(heroClass = BattleHeroClass.WARRIOR, revision = 41L),
            heroLevel = 100,
        )
        val roots = HeroPathCatalog.branchesFor(BattleHeroClass.WARRIOR).map { branch ->
            HeroPathCatalog.nodesFor(branch.branch)
                .first { it.minimumLevel <= 5L && it.requiredBranchInvestments == 0 }
                .traitId
        }

        val result = HeroPathEngine.applyDraft(base, roots)

        assertEquals(HeroPathMutationStatus.APPLIED, result.status)
        assertEquals(base.revision + 1L, result.state.revision)
        assertEquals(base.spentPoints + roots.size, result.state.spentPoints)
        roots.forEach { assertTrue(it in result.state.traits.map(HeroPathTraitProgress::traitId)) }
        assertLedgerInvariant(result.state)
    }

    @Test
    fun `allocation never exceeds twenty applied points`() {
        requireV2Catalog()
        val base = HeroPathEngine.reconcilePointGrants(
            HeroPathState(heroClass = BattleHeroClass.WARRIOR, revision = 3L),
            heroLevel = 500,
        )
        val twentyOneDistinctNodes = HeroPathCatalog.nodesFor(BattleHeroClass.WARRIOR).take(21).map { it.traitId }

        val result = HeroPathEngine.applyDraft(base, twentyOneDistinctNodes)

        assertEquals(HeroPathMutationStatus.POINT_CAP_EXCEEDED, result.status)
        assertEquals(base, result.state)
    }

    @Test
    fun `stale batch revision rejects the entire target without mutation`() {
        requireV2Catalog()
        val base = HeroPathEngine.reconcilePointGrants(
            HeroPathState(heroClass = BattleHeroClass.WARRIOR, revision = 22L),
            heroLevel = 100,
        )
        val root = HeroPathCatalog.nodesFor(BattleHeroClass.WARRIOR).first { it.requiredBranchInvestments == 0 }
        val result = HeroPathEngine.applyAllocation(
            base,
            com.nullplaying.model.HeroPathAllocationTarget(
                expectedRevision = base.revision - 1L,
                nodeRanks = mapOf(root.nodeId to 1),
            ),
        )

        assertEquals(HeroPathMutationStatus.STALE_REVISION, result.status)
        assertEquals(base, result.state)
    }

    @Test
    fun `choice-group A and B cannot be committed together`() {
        requireV2Catalog()
        val base = HeroPathEngine.reconcilePointGrants(
            HeroPathState(heroClass = BattleHeroClass.WARRIOR, revision = 7L),
            heroLevel = 100,
        )
        val branch = HeroPathCatalog.branchesFor(BattleHeroClass.WARRIOR).first().branch
        val choices = HeroPathCatalog.nodesFor(branch).filter { it.testSlotFamily() == "CHOICE" }
        assertEquals(2, choices.size)

        val result = HeroPathEngine.applyDraft(base, choices.map { it.traitId })

        assertEquals(HeroPathMutationStatus.CHOICE_CONFLICT, result.status)
        assertEquals(base, result.state)
    }

    @Test
    fun `rank tier and global-core violations reject the whole allocation`() {
        requireV2Catalog()
        val base = HeroPathEngine.reconcilePointGrants(
            HeroPathState(heroClass = BattleHeroClass.WARRIOR, revision = 31L),
            heroLevel = 100,
        )
        val branches = HeroPathCatalog.branchesFor(BattleHeroClass.WARRIOR).map { it.branch }
        val firstNodes = HeroPathCatalog.nodesFor(branches[0])
        val secondNodes = HeroPathCatalog.nodesFor(branches[1])
        val foundation = firstNodes.first { it.slot == com.nullplaying.model.HeroPathNodeSlot.FOUNDATION_A }
        val core = firstNodes.first { it.slot == com.nullplaying.model.HeroPathNodeSlot.CORE }

        val overRank = HeroPathEngine.applyAllocation(
            base,
            com.nullplaying.model.HeroPathAllocationTarget(base.revision, mapOf(foundation.nodeId to foundation.maxRank + 1)),
        )
        val tierBypass = HeroPathEngine.applyAllocation(
            base,
            com.nullplaying.model.HeroPathAllocationTarget(base.revision, mapOf(core.nodeId to 1), activeCoreNodeId = core.nodeId),
        )
        val twoCoreRanks = (firstNodes + secondNodes)
            .filter { it.slot != com.nullplaying.model.HeroPathNodeSlot.CHOICE_B }
            .associate { it.nodeId to it.maxRank }
        val twoCores = HeroPathEngine.applyAllocation(
            base,
            com.nullplaying.model.HeroPathAllocationTarget(
                base.revision,
                twoCoreRanks,
                activeCoreNodeId = core.nodeId,
            ),
        )

        listOf(overRank, tierBypass, twoCores).forEach { result ->
            assertEquals(HeroPathMutationStatus.INVALID_ALLOCATION, result.status)
            assertEquals(base, result.state)
        }
    }

    @Test
    fun `reset refunds allocation without erasing earned-point history`() {
        val base = HeroPathEngine.reconcilePointGrants(
            HeroPathState(heroClass = BattleHeroClass.WARRIOR, revision = 12L),
            heroLevel = 100,
        )
        val root = HeroPathCatalog.nodesFor(BattleHeroClass.WARRIOR)
            .first { it.minimumLevel <= 5L && it.requiredBranchInvestments == 0 }
            .traitId
        val allocated = HeroPathEngine.applyDraft(base, listOf(root)).state

        val reset = HeroPathEngine.resetAllocation(allocated)

        assertEquals(HeroPathMutationStatus.APPLIED, reset.status)
        assertEquals(allocated.revision + 1L, reset.state.revision)
        assertEquals(allocated.earnedPoints, reset.state.earnedPoints)
        assertEquals(allocated.lastGrantedMilestone, reset.state.lastGrantedMilestone)
        assertEquals(0L, reset.state.spentPoints)
        assertEquals(reset.state.earnedPoints, reset.state.unspentPoints)
        assertTrue(reset.state.traits.isEmpty())
        assertTrue(reset.state.activeTraitIds.isEmpty())
        assertTrue(reset.state.activeCoreTraitId.isEmpty())
        assertLedgerInvariant(reset.state)
    }

    @Test
    fun `V1 migration refunds legacy allocation exactly once`() {
        requireV2Catalog()
        val legacy = HeroPathState(
            catalogVersion = 1,
            revision = 8L,
            heroClass = BattleHeroClass.WARRIOR,
            earnedPoints = 20L,
            lastGrantedMilestone = 100L,
            traits = listOf(
                HeroPathTraitProgress("TRAIT_001", rank = 3),
                HeroPathTraitProgress("TRAIT_007", rank = 2),
            ),
            activeTraitIds = listOf("TRAIT_001", "TRAIT_007"),
        )
        val migrationMethod = HeroPathEngine.javaClass.methods.firstOrNull {
            it.name == "migrateV1Allocation" && it.parameterCount == 1
        } ?: error("HeroPathEngine.migrateV1Allocation(state) is required by the V2 migration contract")

        val migrated = requireNotNull(migrationMethod.invoke(HeroPathEngine, legacy)).migrationState()
        val repeated = requireNotNull(migrationMethod.invoke(HeroPathEngine, migrated)).migrationState()

        assertEquals(20L, migrated.earnedPoints)
        assertEquals(100L, migrated.lastGrantedMilestone)
        assertEquals(0L, migrated.spentPoints)
        assertEquals(20L, migrated.unspentPoints)
        assertTrue(migrated.traits.isEmpty())
        assertTrue(migrated.activeTraitIds.isEmpty())
        assertTrue(migrated.activeCoreTraitId.isEmpty())
        assertNotEquals(1, migrated.catalogVersion)
        assertEquals("migration must be idempotent", migrated, repeated)
        assertLedgerInvariant(migrated)
    }

    private fun requireV2Catalog() {
        assumeTrue("V2 catalog has not landed yet", HeroPathCatalog.nodes.size == 144)
    }

    private fun assertLedgerInvariant(state: HeroPathState) {
        assertEquals(state.earnedPoints, state.spentPoints + state.unspentPoints)
        assertTrue(state.earnedPoints >= 0L)
        assertTrue(state.spentPoints >= 0L)
        assertTrue(state.unspentPoints >= 0L)
    }
}

private fun Any.migrationState(): HeroPathState = when (this) {
    is HeroPathState -> this
    is HeroPathMutation -> state
    else -> {
        val getter = javaClass.methods.firstOrNull { it.name == "getState" && it.parameterCount == 0 }
            ?: error("Migration result ${javaClass.name} must expose state")
        getter.invoke(this) as? HeroPathState
            ?: error("Migration result ${javaClass.name}.state must be HeroPathState")
    }
}

private fun Any.testSlotFamily(): String {
    val value = listOf("nodeSlot", "slot", "nodeType", "kind")
        .asSequence()
        .map { name -> "get" + name.replaceFirstChar { it.uppercase() } }
        .mapNotNull { getter -> javaClass.methods.firstOrNull { it.name == getter && it.parameterCount == 0 } }
        .firstOrNull()
        ?.invoke(this)
        ?: error("${javaClass.name} must expose nodeSlot")
    val name = ((value as? Enum<*>)?.name ?: value.toString()).uppercase()
    return when {
        "FOUNDATION" in name -> "FOUNDATION"
        "CHOICE" in name -> "CHOICE"
        "SPECIAL" in name -> "SPECIAL"
        "ADVANCED" in name || "TACTIC" in name -> "ADVANCED_TACTIC"
        "CORE" in name -> "CORE"
        else -> name
    }
}
