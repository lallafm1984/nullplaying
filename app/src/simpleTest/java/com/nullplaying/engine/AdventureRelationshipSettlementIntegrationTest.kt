package com.nullplaying.engine

import com.nullplaying.model.AdventureEncounterCandidate
import com.nullplaying.model.AdventureEventItemReward
import com.nullplaying.model.AdventureEventOutcome
import com.nullplaying.model.AdventureEventRewardKind
import com.nullplaying.model.AdventureEventStat
import com.nullplaying.model.AdventurePhase
import com.nullplaying.model.AdventureRelationshipBattleKind
import com.nullplaying.model.AdventureRelationshipEquipmentSnapshot
import com.nullplaying.model.AdventureRelationshipRun
import com.nullplaying.model.AdventureRelationshipSkillSnapshot
import com.nullplaying.model.HeroClass
import com.nullplaying.model.RecentAdventureEventMetadata
import com.nullplaying.model.RecentAdventureEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdventureRelationshipSettlementIntegrationTest {
    private val engine = SimpleGameEngine(enableAdventureRelationships = true)

    @Test
    fun `one relationship settlement grants exactly its selected gold reward`() {
        val game = stagedGame()
        val run = relationshipRun(
            rewardKind = AdventureEventRewardKind.GOLD,
            gold = 140L,
        )
        stage(game, run)
        val beforeExperience = game.hero.experience
        val beforeItems = game.totalItemsFound

        val delta = engine.settle(game, game.actionEndsAt)

        assertEquals(140L, game.hero.gold)
        assertEquals(beforeExperience, game.hero.experience)
        assertEquals(beforeItems, game.totalItemsFound)
        val result = game.adventureRelationships.lastResult!!
        assertEquals(AdventureEventRewardKind.GOLD, result.rewardKind)
        assertEquals(140L, result.goldAwarded)
        assertEquals(0L, result.experienceAwarded)
        assertTrue(result.itemName.isBlank())
        val recent = delta.recentEvents.single { it.type == RecentAdventureEventType.RELATIONSHIP_ENCOUNTER }
        assertEquals(AdventureEventRewardKind.GOLD, RecentAdventureEventMetadata.adventureRewardKind(recent.contextName))
        assertEquals(140L, RecentAdventureEventMetadata.relationshipGold(recent.contextName))
    }

    @Test
    fun `relationship equipment follows normal inventory and receipt path with no second reward`() {
        val game = stagedGame()
        val run = relationshipRun(
            rewardKind = AdventureEventRewardKind.ITEM,
            itemReward = AdventureEventItemReward.EQUIPMENT,
        )
        stage(game, run)
        val beforeExperience = game.hero.experience

        engine.settle(game, game.actionEndsAt)

        val result = game.adventureRelationships.lastResult!!
        assertEquals(AdventureEventRewardKind.ITEM, result.rewardKind)
        assertTrue(result.itemName.isNotBlank())
        assertEquals(1L, game.totalItemsFound)
        assertEquals(0L, game.hero.gold)
        assertEquals(beforeExperience, game.hero.experience)
        assertTrue(game.lastLootName.isNotBlank())
        assertTrue(game.lastLootSummary.contains("가방에 보관") || game.lastLootSummary.contains("새 장비로 장착"))
    }

    @Test
    fun `resolved battle is frozen before playback and strips both snapshots from saved history`() {
        val game = stagedGame()
        val remote = AdventureQaFixtures.game(engine, HeroClass.MAGE, 992L, 10L)
        remote.rankingCharacterId = "remote-projection"
        val selected = relationshipRun(
            rewardKind = AdventureEventRewardKind.UNSPECIFIED,
            battleKind = AdventureRelationshipBattleKind.SPAR,
            candidate = candidate(remote),
        )
        val frozen = AdventureRelationshipBattleEngine.freezeParticipants(game, selected)!!
        val simulation = AdventureRelationshipBattleEngine.simulate(frozen)
        assertNotNull(simulation)
        val resolved = AdventureRelationshipEngine.resolveBattle(frozen, simulation!!.outcome)
        assertEquals(AdventureRelationshipBattleEngine.PLAYBACK_MILLIS, resolved.battleDurationMillis)
        assertEquals(AdventureRelationshipEngine.ACTION_MILLIS + resolved.battleDurationMillis, resolved.durationMillis)
        stage(game, resolved)

        engine.settle(game, game.actionEndsAt)

        val saved = game.adventureRelationships.lastResult!!.run
        assertNotNull(saved.battleOutcome)
        assertEquals(resolved.battleKind, saved.battleKind)
        assertEquals(resolved.battleSeed, saved.battleSeed)
        assertTrue(saved.localBattleSnapshot == null)
        assertTrue(saved.opponentBattleSnapshot == null)
        assertFalse(saved.rewardKind == AdventureEventRewardKind.UNSPECIFIED)
    }

    private fun stagedGame() = AdventureQaFixtures.game(engine, HeroClass.WARRIOR, 991L, 10L).also { game ->
        game.rankingCharacterId = "local-projection"
        game.adventureRelationships.initialized = true
        game.adventureRelationships.initializedAt = AdventureQaFixtures.EPOCH
        game.adventureRelationships.nextEncounterAt = Long.MAX_VALUE
    }

    private fun stage(game: com.nullplaying.model.SimpleGameState, run: AdventureRelationshipRun) {
        game.adventureRelationships.pending = run
        game.adventurePhase = AdventurePhase.RELATIONSHIP
        game.actionStartedAt = run.startedAt
        game.actionEndsAt = run.startedAt + run.durationMillis
        game.lastSettledAt = run.startedAt
    }

    private fun relationshipRun(
        rewardKind: AdventureEventRewardKind,
        gold: Long = 0L,
        itemReward: AdventureEventItemReward = AdventureEventItemReward.NONE,
        battleKind: AdventureRelationshipBattleKind = AdventureRelationshipBattleKind.NONE,
        candidate: AdventureEncounterCandidate = AdventureEncounterCandidate(
            "remote-projection", "먼 길의 별", HeroClass.RANGER, 10L, 40L,
        ),
    ) = AdventureRelationshipRun(
        sequence = 1L,
        sceneId = "training_spar",
        approachId = "salute",
        candidate = candidate,
        snapshotId = "roster",
        startedAt = AdventureQaFixtures.EPOCH,
        startedActiveMillis = 0L,
        durationMillis = AdventureRelationshipEngine.ACTION_MILLIS +
            if (battleKind == AdventureRelationshipBattleKind.NONE) 0L else AdventureRelationshipBattleEngine.PLAYBACK_MILLIS,
        heroLevel = 10L,
        primaryStat = AdventureEventStat.CHA,
        secondaryStat = AdventureEventStat.STR,
        primaryValue = 12L,
        secondaryValue = 12L,
        successBasisPoints = 5_000,
        partialBasisPoints = 2_500,
        roll = 1,
        outcome = AdventureEventOutcome.SUCCESS,
        scoreBefore = 0,
        scoreDelta = 3,
        experienceReward = 0L,
        rewardSeed = 515L,
        encounterLevel = 10L,
        labyrinthDepth = 0L,
        baseExperienceBudget = 56L,
        reunion = false,
        rewardKind = rewardKind,
        goldReward = gold,
        itemReward = itemReward,
        battleKind = battleKind,
        battleSeed = 818L,
        battleDurationMillis = if (battleKind == AdventureRelationshipBattleKind.NONE) 0L else AdventureRelationshipBattleEngine.PLAYBACK_MILLIS,
    )

    private fun candidate(state: com.nullplaying.model.SimpleGameState) = AdventureEncounterCandidate(
        characterId = state.rankingCharacterId,
        displayName = state.hero.name,
        heroClass = state.hero.heroClass,
        level = state.hero.level,
        combatPower = engine.displayCombatPower(state),
        stats = state.hero.stats.copy(),
        learnedSkills = state.skills.map { skill ->
            AdventureRelationshipSkillSnapshot(skill.catalogId, skill.name, skill.level, skill.boundedUsageCount)
        },
        equipment = state.equipment.map { item ->
            AdventureRelationshipEquipmentSnapshot(item.slot, item.name, item.power, item.rarity)
        },
    )
}
