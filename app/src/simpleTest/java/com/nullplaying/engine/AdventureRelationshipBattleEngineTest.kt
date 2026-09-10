package com.nullplaying.engine

import com.nullplaying.model.AdventureEncounterCandidate
import com.nullplaying.model.AdventureEventOutcome
import com.nullplaying.model.AdventureEventStat
import com.nullplaying.model.AdventureRelationshipBattleKind
import com.nullplaying.model.AdventureRelationshipEquipmentSnapshot
import com.nullplaying.model.AdventureRelationshipRun
import com.nullplaying.model.AdventureRelationshipSkillSnapshot
import com.nullplaying.model.HeroClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdventureRelationshipBattleEngineTest {
    private val gameEngine = SimpleGameEngine(
        enableAdventureEvents = true,
        enableAdventureRelationships = true,
        enableAdventureTraits = true,
    )

    @Test
    fun `relationship battle keeps local attacks and derives remote attacks from class and level`() {
        val local = AdventureQaFixtures.game(gameEngine, HeroClass.WARRIOR, 301L, 10L)
        val remote = AdventureQaFixtures.game(gameEngine, HeroClass.RANGER, 302L, 11L)
        local.rankingCharacterId = "local-projection"
        remote.rankingCharacterId = "remote-projection"
        local.skills[0] = local.skills[0].copy(usageCount = 237L)
        val run = battleRun(candidate(remote))

        val frozen = AdventureRelationshipBattleEngine.freezeParticipants(local, run)
        assertNotNull(frozen)
        val localInput = AdventureRelationshipBattleEngine.toArenaInput(frozen!!.localBattleSnapshot!!)!!
        val remoteInput = AdventureRelationshipBattleEngine.toArenaInput(frozen.opponentBattleSnapshot!!)!!

        assertEquals(local.skills.map { it.catalogId }, localInput.fighter.attacks.map { it.id })
        val expectedRemoteSkills = PublicPlayerBattleDerivation
            .learnedSkillsForClassAndLevel(remote.hero.heroClass, remote.hero.level)
        assertEquals(expectedRemoteSkills.map { it.catalogId }, remoteInput.fighter.attacks.map { it.id })
        assertEquals(local.skills[0].damageBonusPercent.toInt(), localInput.fighter.attacks[0].masteryBonusPercent)
        assertEquals(
            expectedRemoteSkills.map { it.damageBonusPercent.toInt() },
            remoteInput.fighter.attacks.map { it.masteryBonusPercent },
        )
        listOf(localInput, remoteInput).forEach { input ->
            assertFalse("relationship combat must retain its pre-Arena balance formula",
                input.arenaClassBalanceEnabled)
            assertTrue(input.supportIds.isEmpty())
            assertTrue(input.supportRanks.isEmpty())
            assertTrue(input.resolvedSupports.isEmpty())
            assertTrue(input.traits.isEmpty())
            assertEquals(1, input.arenaLevel)
        }
    }

    @Test
    fun `same frozen participants and seed produce the same completed battle`() {
        val local = AdventureQaFixtures.game(gameEngine, HeroClass.CLERIC, 311L, 10L)
        val remote = AdventureQaFixtures.game(gameEngine, HeroClass.ROGUE, 312L, 10L)
        local.rankingCharacterId = "local-projection"
        remote.rankingCharacterId = "remote-projection"
        val frozen = AdventureRelationshipBattleEngine.freezeParticipants(local, battleRun(candidate(remote)))!!

        val first = AdventureRelationshipBattleEngine.simulate(frozen)
        val second = AdventureRelationshipBattleEngine.simulate(frozen)

        assertNotNull(first)
        assertEquals(first!!.outcome, second!!.outcome)
        assertEquals(first.simulation, second.simulation)
    }

    @Test
    fun `remote payload without learned skills derives unlocked average mastery attacks`() {
        val local = AdventureQaFixtures.game(gameEngine, HeroClass.PALADIN, 321L, 10L)
        val remote = AdventureQaFixtures.game(gameEngine, HeroClass.MAGE, 322L, 10L)
        local.rankingCharacterId = "local-projection"
        remote.rankingCharacterId = "remote-projection"
        val basicOnly = candidate(remote).copy(learnedSkills = emptyList())
        val frozen = AdventureRelationshipBattleEngine.freezeParticipants(local, battleRun(basicOnly))!!

        val attacks = AdventureRelationshipBattleEngine
            .toArenaInput(frozen.opponentBattleSnapshot!!)!!.fighter.attacks
        val expectedSkills = PublicPlayerBattleDerivation
            .learnedSkillsForClassAndLevel(remote.hero.heroClass, remote.hero.level)
        assertEquals(expectedSkills.map { it.catalogId }, attacks.map { it.id })
        assertEquals(expectedSkills.map { it.damageBonusPercent.toInt() }, attacks.map { it.masteryBonusPercent })
        assertNotNull(AdventureRelationshipBattleEngine.simulate(frozen))
    }

    @Test
    fun `remote equipment and skill fields are ignored while invalid raw stats fail closed`() {
        val remote = AdventureQaFixtures.game(gameEngine, HeroClass.RANGER, 332L, 10L)
        remote.rankingCharacterId = "remote-projection"
        val valid = candidate(remote)
        val withoutEquipment = AdventureRelationshipBattleEngine.snapshotFromCandidate(
            valid.copy(equipment = valid.equipment.dropLast(1)),
        )
        assertNotNull(withoutEquipment)
        assertTrue(withoutEquipment!!.equipment.isEmpty())
        val foreign = SkillCatalog.forClass(HeroClass.MAGE).first()
        val ignoredForeignSkill = AdventureRelationshipBattleEngine.snapshotFromCandidate(
            valid.copy(
                learnedSkills = listOf(
                    AdventureRelationshipSkillSnapshot(
                        catalogId = foreign.catalogId,
                        displayName = foreign.name,
                        level = 1L,
                        usageCount = 0L,
                    ),
                ),
            ),
        )
        assertNotNull(ignoredForeignSkill)
        assertTrue(ignoredForeignSkill!!.learnedSkills.none { it.catalogId == foreign.catalogId })
        assertNull(AdventureRelationshipBattleEngine.snapshotFromCandidate(
            valid.copy(stats = valid.stats!!.copy(maxHealth = 0L)),
        ))
    }

    private fun candidate(state: com.nullplaying.model.SimpleGameState) = AdventureEncounterCandidate(
        characterId = state.rankingCharacterId,
        displayName = state.hero.name,
        heroClass = state.hero.heroClass,
        level = state.hero.level,
        combatPower = gameEngine.displayCombatPower(state),
        stats = state.hero.stats.copy(),
        learnedSkills = state.skills.map { skill ->
            AdventureRelationshipSkillSnapshot(
                catalogId = skill.catalogId,
                displayName = skill.name,
                level = skill.level,
                usageCount = skill.boundedUsageCount,
            )
        },
        equipment = state.equipment.map { item ->
            AdventureRelationshipEquipmentSnapshot(item.slot, item.name, item.power, item.rarity)
        },
        adventureTraitIds = state.adventureTraits.owned.map { it.traitId },
    )

    private fun battleRun(candidate: AdventureEncounterCandidate) = AdventureRelationshipRun(
        sequence = 7L,
        sceneId = "training_spar",
        approachId = "salute",
        candidate = candidate,
        snapshotId = "daily-roster",
        startedAt = AdventureQaFixtures.EPOCH,
        startedActiveMillis = 0L,
        durationMillis = 10_000L,
        heroLevel = 10L,
        primaryStat = AdventureEventStat.CHA,
        secondaryStat = AdventureEventStat.STR,
        primaryValue = 10L,
        secondaryValue = 10L,
        successBasisPoints = 5_000,
        partialBasisPoints = 2_500,
        roll = 1,
        outcome = AdventureEventOutcome.SUCCESS,
        scoreBefore = 0,
        scoreDelta = 3,
        experienceReward = 0L,
        rewardSeed = 91L,
        encounterLevel = 10L,
        labyrinthDepth = 0L,
        baseExperienceBudget = 56L,
        reunion = false,
        battleKind = AdventureRelationshipBattleKind.SPAR,
        battleSeed = 19L,
    )
}
