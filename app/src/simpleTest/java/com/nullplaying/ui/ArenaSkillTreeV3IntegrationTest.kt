package com.nullplaying.ui

import com.nullplaying.engine.BattleQaMatchFactory
import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.engine.SkillCatalog
import com.nullplaying.engine.arena.ArenaProgressionPending
import com.nullplaying.engine.arena.ArenaProgressionRules
import com.nullplaying.engine.arena.ArenaProgressionState
import com.nullplaying.engine.arena.ArenaSkillAllocation
import com.nullplaying.engine.arena.ArenaSkillTreeCatalog
import com.nullplaying.engine.arena.ArenaSkillTreeRules
import com.nullplaying.engine.arena.ArenaSkillTreeState
import com.nullplaying.engine.arena.ArenaSupportInput
import com.nullplaying.engine.arena.ArenaSupportTurnEngine
import com.nullplaying.engine.arena.ArenaSyntheticProfileGrowth
import com.nullplaying.engine.arena.ArenaTraitAllocation
import com.nullplaying.engine.arena.ArenaTurnInputAdapter
import com.nullplaying.engine.arena.freezeResolvedSupports
import com.nullplaying.localization.AppLanguage
import com.nullplaying.model.BattleGuidance
import com.nullplaying.model.BattleSeasonStanding
import com.nullplaying.model.BattleTicketState
import com.nullplaying.model.HeroClass
import com.nullplaying.model.LearnedSkill
import com.nullplaying.model.SimpleGameState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArenaSkillTreeV3IntegrationTest {
    @Test
    fun `legacy snapshot initializes an empty v3 tree without touching xp old allocations pending or history`() {
        val pendingId = "legacy-pending-battle"
        val progression = ArenaProgressionState(
            unlocked = true,
            totalXp = 2_300L,
            revision = 31L,
            allocations = listOf(ArenaTraitAllocation("AT9_WARRIOR_A01", rank = 2, enhancement = 1)),
            growthDay = DAY,
            growthEntriesUsed = 2,
            pending = ArenaProgressionPending(pendingId, DAY, xpEligible = true),
            settledBattleIds = listOf("legacy-settled-battle"),
        )
        val source = BattleLocalSnapshot(
            gameEpochDay = DAY,
            entriesRemaining = 1,
            placementCompleted = 17,
            score = 1_087,
            wins = 11,
            losses = 5,
            draws = 1,
            condition = "GOOD",
            conditionEvaluatedAtMillis = 7_777L,
            conditionTransitionSequence = 9L,
            conditionMomentum = 3,
            conditionWinStreak = 2,
            history = listOf(history(pendingId)),
            arenaProgression = progression,
            arenaSkillTree = null,
            arenaSupportOwnership = ArenaSupportOwnership(
                HeroClass.WARRIOR,
                setOf("ARENA_SUP_FIGHTER_01"),
            ),
        )
        val encodedWithDefaults = encodeBattleLocalSnapshot(source)
        val legacyJson = encodedWithDefaults
            .replace(",\"arenaSkillTree\":null", "")
            .replace("\"arenaSkillTree\":null,", "")
        assertFalse(legacyJson.contains("arenaSkillTree"))

        val decoded = requireNotNull(decodeBattleLocalSnapshot(legacyJson))
        assertNull(decoded.arenaSkillTree)
        assertEquals(source, decoded)
        val initialized = initializeArenaSkillTree(decoded, HeroClass.WARRIOR)

        assertEquals(ArenaSkillTreeState(HeroClass.WARRIOR), initialized.arenaSkillTree)
        assertEquals(progression, initialized.arenaProgression)
        assertEquals(progression.totalXp, initialized.arenaProgression.totalXp)
        assertEquals(progression.allocations, initialized.arenaProgression.allocations)
        assertEquals(progression.pending, initialized.arenaProgression.pending)
        assertEquals(source.history, initialized.history)
        assertEquals(source.copy(arenaSkillTree = ArenaSkillTreeState(HeroClass.WARRIOR)), initialized)
        assertEquals(initialized, requireNotNull(decodeBattleLocalSnapshot(
            encodeBattleLocalSnapshot(initialized),
        )))
        assertEquals(initialized, initializeArenaSkillTree(initialized, HeroClass.WARRIOR))
    }

    @Test
    fun `retired level twenty QA attack is refunded without changing arena progress`() {
        val attacks = SkillCatalog.forClass(HeroClass.WARRIOR)
        val projectedOwned = attacks.take(5).mapTo(linkedSetOf()) { it.catalogId }
        val actualOwned = attacks.take(4).mapTo(linkedSetOf()) { it.catalogId }
        val projectedTree = ArenaSkillTreeState(
            heroClass = HeroClass.WARRIOR,
            revision = 9L,
            allocations = listOf(
                ArenaSkillAllocation(attacks[2].catalogId, 3),
                ArenaSkillAllocation(attacks[4].catalogId, 1),
            ),
        )
        assertTrue(ArenaSkillTreeRules.validate(
            projectedTree,
            HeroClass.WARRIOR,
            4,
            projectedOwned,
        ))
        assertFalse(ArenaSkillTreeRules.validate(
            projectedTree,
            HeroClass.WARRIOR,
            4,
            actualOwned,
        ))
        val progression = ArenaProgressionState(
            unlocked = true,
            totalXp = ArenaProgressionRules.xpForLevel(4),
            revision = 17L,
            growthDay = DAY,
            growthEntriesUsed = 2,
        )
        val source = BattleLocalSnapshot(
            gameEpochDay = DAY,
            entriesRemaining = 7,
            arenaProgression = progression,
            arenaSkillTree = projectedTree,
        )

        val migrated = reconcileArenaSkillTreeForFighter(
            snapshot = source,
            heroClass = HeroClass.WARRIOR,
            ownedAttackIds = actualOwned,
        )

        assertEquals(progression, migrated.arenaProgression)
        assertEquals(source.copy(
            arenaSkillTree = projectedTree.copy(revision = 10L, allocations = emptyList()),
        ), migrated)
        assertTrue(ArenaSkillTreeRules.validate(
            requireNotNull(migrated.arenaSkillTree),
            HeroClass.WARRIOR,
            4,
            actualOwned,
        ))
        assertEquals(4, ArenaSkillTreeRules.view(
            requireNotNull(migrated.arenaSkillTree),
            4,
            actualOwned,
        ).availablePoints)
    }

    @Test
    fun `v6 entry freezes ranked user and opponent contracts plus tree metadata into live and history snapshot`() {
        val state = fullState(HeroClass.WARRIOR, 95)
        val progression = ArenaProgressionState(
            unlocked = true,
            totalXp = ArenaProgressionRules.MAX_TOTAL_XP,
            revision = 19L,
            growthDay = DAY,
        )
        val ownedAttacks = ArenaTurnInputAdapter.ownedAttackIds(state)
        val skillTree = ArenaSkillTreeRules.autoAllocate(
            heroClass = HeroClass.WARRIOR,
            arenaLevel = 100,
            ownedAttackIds = ownedAttacks,
            seed = 0x7300L,
        ).copy(revision = 73L)
        assertTrue(ArenaSkillTreeRules.validate(skillTree, HeroClass.WARRIOR, 100, ownedAttacks))

        val prepared = requireNotNull(prepareArenaBattle(
            state = state,
            match = match(state, seed = 9_173L),
            standing = BattleSeasonStanding(),
            tickets = BattleTicketState(DAY, 3),
            playerStance = BattleStance.BALANCED,
            language = AppLanguage.KOREAN,
            progression = progression,
            skillTree = skillTree,
        ))
        val live = requireNotNull(prepared.first.supportBattle)

        assertRankedParticipant(live.user)
        assertRankedParticipant(live.opponent)
        val userRanks = skillTree.allocations.associate { it.nodeId to it.rank }
        live.user.fighter.attacks.forEach { attack ->
            assertEquals(userRanks.getValue(attack.id), requireNotNull(attack.arena).rank)
        }
        live.user.supportRanks.forEach { (id, rank) -> assertEquals(userRanks.getValue(id), rank) }
        assertEquals(73L, live.skillTreeRevision)
        assertEquals(13, live.skillTreeCatalogVersion)
        assertEquals("arena-skill-tree-v13", live.skillTreeRulesVersion)
        assertEquals("arena-stat-identity-v5", live.simulation.rulesVersion)
        assertEquals(ArenaSkillTreeCatalog.catalogVersion, live.skillTreeCatalogVersion)
        assertEquals(ArenaSkillTreeRules.rulesVersion, live.skillTreeRulesVersion)
        assertEquals(progression.revision, live.progressionRevision)

        val saved = live.savedContract()
        assertEquals(live.user.freezeResolvedSupports(), saved.user)
        assertEquals(live.opponent.freezeResolvedSupports(), saved.opponent)
        assertEquals(saved.user.supportIds, saved.user.resolvedSupports.keys)
        assertEquals(saved.opponent.supportIds, saved.opponent.resolvedSupports.keys)
        assertEquals(live.skillTreeRevision, saved.skillTreeRevision)
        assertEquals(live.skillTreeCatalogVersion, saved.skillTreeCatalogVersion)
        assertEquals(live.skillTreeRulesVersion, saved.skillTreeRulesVersion)
        assertEquals(live.progressionRevision, saved.progressionRevision)
        assertEquals(live.simulation.rulesVersion, saved.rulesVersion)
        assertEquals(live.simulation, ArenaSupportTurnEngine.simulate(
            saved.user,
            saved.opponent,
            saved.seed,
            saved.rules,
            ignoreHeroLevelGate = saved.ignoreHeroLevelGate,
        ))
        val codec = Json { encodeDefaults = true }
        val restored = codec.decodeFromString<ArenaSavedBattleContract>(
            codec.encodeToString(saved),
        )
        assertEquals(saved, restored)

        val issuedSnapshot = BattleLocalSnapshot(
            gameEpochDay = DAY,
            arenaProgression = progression,
            arenaSkillTree = skillTree,
        )
        assertTrue(arenaBattleRevisionsMatch(issuedSnapshot, live))
        assertFalse(arenaBattleRevisionsMatch(issuedSnapshot.copy(
            arenaProgression = progression.copy(revision = progression.revision + 1),
        ), live))
        assertFalse(arenaBattleRevisionsMatch(issuedSnapshot.copy(
            arenaSkillTree = skillTree.copy(revision = skillTree.revision + 1),
        ), live))
        assertFalse(arenaBattleRevisionsMatch(issuedSnapshot.copy(arenaSkillTree = null), live))
    }

    @Test
    fun `entry rejects prerequisite bypass unowned attack and invalid revision before simulation`() {
        val full = fullState(HeroClass.WARRIOR, 95)
        val progression = ArenaProgressionState(
            unlocked = true,
            totalXp = ArenaProgressionRules.xpForLevel(20),
            revision = 7L,
        )
        val fourthAttack = SkillCatalog.forClass(HeroClass.WARRIOR)[3].catalogId
        val prerequisiteBypass = ArenaSkillTreeState(
            heroClass = HeroClass.WARRIOR,
            revision = 8L,
            allocations = listOf(ArenaSkillAllocation(fourthAttack, 1)),
        )
        assertFalse(ArenaSkillTreeRules.validate(
            prerequisiteBypass,
            HeroClass.WARRIOR,
            20,
            ArenaTurnInputAdapter.ownedAttackIds(full),
        ))
        assertNull(prepare(full, progression, prerequisiteBypass))

        val partial = full.copy(skills = mutableListOf(full.skills.first().copy()))
        val unownedRoot = SkillCatalog.forClass(HeroClass.WARRIOR)[1].catalogId
        val unowned = ArenaSkillTreeState(
            heroClass = HeroClass.WARRIOR,
            revision = 9L,
            allocations = listOf(ArenaSkillAllocation(unownedRoot, 1)),
        )
        assertFalse(ArenaSkillTreeRules.validate(
            unowned,
            HeroClass.WARRIOR,
            20,
            ArenaTurnInputAdapter.ownedAttackIds(partial),
        ))
        assertNull(prepare(partial, progression, unowned))

        val firstRoot = SkillCatalog.forClass(HeroClass.WARRIOR).first().catalogId
        val invalidRevision = ArenaSkillTreeState(
            heroClass = HeroClass.WARRIOR,
            revision = -1L,
            allocations = listOf(ArenaSkillAllocation(firstRoot, 1)),
        )
        assertFalse(ArenaSkillTreeRules.validate(
            invalidRevision,
            HeroClass.WARRIOR,
            20,
            ArenaTurnInputAdapter.ownedAttackIds(full),
        ))
        assertNull(prepare(full, progression, invalidRevision))

        val ownedAttacks = ArenaTurnInputAdapter.ownedAttackIds(full)
        val onePointUnspent = ArenaSkillTreeRules.autoAllocate(
            heroClass = HeroClass.WARRIOR,
            arenaLevel = 19,
            ownedAttackIds = ownedAttacks,
            seed = 19L,
        )
        assertTrue(ArenaSkillTreeRules.validate(
            onePointUnspent,
            HeroClass.WARRIOR,
            20,
            ownedAttacks,
        ))
        assertEquals(1, ArenaSkillTreeRules.view(
            onePointUnspent,
            arenaLevel = 20,
            ownedAttackIds = ownedAttacks,
        ).availablePoints)
        assertNull(prepare(full, progression, onePointUnspent))
    }

    private fun assertRankedParticipant(input: ArenaSupportInput) {
        assertTrue(input.traits.isEmpty())
        assertTrue(input.fighter.attacks.isNotEmpty())
        assertTrue(input.supportRanks.isNotEmpty())
        assertEquals(input.supportIds, input.supportRanks.keys)
        assertTrue(input.supportRanks.values.all { it in 1..10 })
        input.fighter.attacks.forEach { attack ->
            assertEquals(0, attack.masteryBonusPercent)
            assertEquals(0, attack.sourceDamagePercentMin)
            assertEquals(0, attack.sourceDamagePercentMax)
            val arena = requireNotNull(attack.arena)
            assertTrue(arena.rank in 1..10)
            val node = requireNotNull(ArenaSkillTreeCatalog.find(attack.id))
            val profile = requireNotNull(node.attackProfile)
            assertEquals(profile.prepareTurns, arena.prepareTurns)
            assertEquals(profile.effectiveMpCost(arena.rank), arena.mpCost)
            assertEquals(profile.effectiveCooldownTurns(arena.rank), arena.cooldownTurns)
            assertEquals(profile.damagePercent(input.fighter.heroClass, arena.rank), arena.damagePercent)
        }
        ArenaSupportTurnEngine.validate(input)
    }

    private fun prepare(
        state: SimpleGameState,
        progression: ArenaProgressionState,
        skillTree: ArenaSkillTreeState,
    ) = prepareArenaBattle(
        state = state,
        match = match(state, seed = 8_811L),
        standing = BattleSeasonStanding(),
        tickets = BattleTicketState(DAY, 3),
        playerStance = BattleStance.BALANCED,
        language = AppLanguage.KOREAN,
        progression = progression,
        skillTree = skillTree,
    )

    private fun match(state: SimpleGameState, seed: Long) = BattleQaMatchFactory.createMatch(
        state = state,
        combatPower = 1_000L,
        guidance = BattleGuidance.BALANCED,
        userScore = 1_000,
        matchSequence = 0,
        battleId = "123e4567-e89b-42d3-a456-426614173003",
        serverSeed = seed,
        requestedAtMillis = 1_000L,
    )

    private fun fullState(heroClass: HeroClass, level: Int): SimpleGameState {
        val engine = SimpleGameEngine()
        val roll = engine.rollStats(703L + heroClass.ordinal, heroClass)
        return engine.newGame(
            "V3통합검증",
            heroClass,
            roll.stats.copy(),
            roll.nextSeed,
            1_000L,
        ).apply {
            val growthSeed = ArenaSyntheticProfileGrowth.grow(
                engine, hero.stats, heroClass,
                targetLevel = level.toLong(), identitySeed = rngState,
            )
            hero.level = level.toLong()
            rngState = growthSeed
            classGuidedLevelGrowths = (level - 1).toLong()
            skills.clear()
            SkillCatalog.forClass(heroClass).filter { it.unlockLevel <= level }.forEachIndexed { index, skill ->
                skills += LearnedSkill(
                    id = index + 1,
                    name = skill.name,
                    acquiredAtLevel = skill.unlockLevel.toLong(),
                    description = skill.description,
                    usageCount = if (index % 2 == 0) Long.MAX_VALUE else 0L,
                    catalogId = skill.catalogId,
                )
            }
        }
    }

    private fun history(id: String) = BattlePreviewHistory(
        battleId = id,
        userName = "레거시 영웅",
        opponentName = "기록 상대",
        opponentClass = "메이지",
        opponentLevel = 95L,
        resultLabel = "승리",
        pointDelta = 12,
        summary = "기존 아레나 기록",
        narrativeLines = listOf("이 기록은 그대로 남아야 한다."),
        skillNames = listOf("기존 스킬"),
        equipment = emptyList(),
        traitNames = listOf("기존 특성"),
        completedAtMillis = 7_000L,
        narrativeSource = "arena_local",
    )

    private companion object {
        const val DAY = 20_702L
    }
}
