package com.alarmquest.engine

import com.alarmquest.model.AdventurePhase
import com.alarmquest.model.CombatPhase
import com.alarmquest.model.HeroClass
import com.alarmquest.model.MonsterGrade
import com.alarmquest.model.MonsterState
import com.alarmquest.model.SIMPLE_GAME_SCHEMA_VERSION
import com.alarmquest.model.TaleKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthProgressionTest {
    private val engine = SimpleGameEngine()

    @Test
    fun `depth changes act targets danger and rewards while every tenth depth is a gate`() {
        assertEquals(
            listOf(800L, 950L, 1_100L, 900L, 1_250L),
            (0..4).map { LabyrinthProgression.actTarget(depth = 1L, actIndex = it) },
        )
        assertEquals(
            listOf(802L, 952L, 1_102L, 902L, 1_252L),
            (0..4).map { LabyrinthProgression.actTarget(depth = 2L, actIndex = it) },
        )
        assertEquals(
            listOf(818L, 968L, 1_118L, 918L, 1_568L),
            (0..4).map { LabyrinthProgression.actTarget(depth = 10L, actIndex = it) },
        )
        assertEquals(0L, LabyrinthProgression.rewardBonusPercent(1L))
        assertEquals(15L, LabyrinthProgression.rewardBonusPercent(10L))
        assertEquals(1L, LabyrinthProgression.rewardBonusPercent(11L))
        assertEquals(117L, LabyrinthProgression.scaleReward(101L, depth = 10L))
        assertEquals(109L, LabyrinthProgression.scaleCombatExperience(101L, depth = 10L))
        assertEquals(1, LabyrinthProgression.monsterAttackBonus(1L))
        assertEquals(4, LabyrinthProgression.monsterAttackBonus(100L))
        assertEquals(9L, LabyrinthProgression.monsterLevelBonus(100L))
        assertEquals(44, LabyrinthProgression.targetAttacks(100L, 30, isGateBoss = true))
        assertTrue(LabyrinthProgression.isGateDepth(10L))
        assertTrue(!LabyrinthProgression.isGateDepth(11L))
    }

    @Test
    fun `gate instantiation announces its boss reward record and unlocked title`() {
        val regular = taleAtDepth(9L)
        val gate = taleAtDepth(10L)

        assertTrue(regular.title.startsWith("제9구역 · "))
        assertTrue(!regular.subtitle.contains("관문"))
        assertTrue(gate.title.startsWith("제10구역 · 제1관문 · "))
        assertTrue(gate.subtitle.startsWith("표층 관문 · "))
        assertTrue(gate.opening.contains("제1관문의 문지기"))
        assertTrue(gate.ending.contains("제1관문 돌파 기록"))
        assertTrue(gate.ending.contains("‘제1관문 정복자’ 칭호"))
        assertTrue(gate.acts.last().title.startsWith("관문 · "))
        assertTrue(gate.acts.last().target > regular.acts.last().target)
        assertTrue(gate.acts.all { it.rewardExperience > regular.acts.first().rewardExperience })
        assertTrue(gate.acts.all { it.rewardGold > regular.acts.first().rewardGold })
    }

    @Test
    fun `final encounter on a tenth depth becomes a stronger automatic gate boss`() {
        val game = newGame()
        game.adventureTale = taleAtDepth(10L).also { tale ->
            tale.currentActIndex = tale.acts.lastIndex
            tale.acts.take(tale.acts.lastIndex).forEach { act ->
                act.progress = act.target
                act.completed = true
            }
            tale.activeAct().progress = tale.activeAct().target - 2L
        }
        game.adventurePhase = AdventurePhase.COMBAT
        game.combatPhase = CombatPhase.ATTACKING
        game.monster = MonsterState(
            id = 1L,
            name = "관문 앞 정찰수",
            level = game.hero.level,
            maxEnergy = 1L,
            grade = MonsterGrade.NORMAL,
            currentEnergy = 1L,
        )
        game.actionStartedAt = 0L
        game.actionEndsAt = 1L
        game.lastSettledAt = 0L

        engine.settle(game, now = 1L)
        engine.settle(game, now = game.actionEndsAt)
        assertEquals(game.adventureTale.activeAct().target - 1L, game.adventureTale.activeAct().progress)
        engine.settle(game, now = game.actionEndsAt)

        assertEquals(AdventurePhase.COMBAT, game.adventurePhase)
        assertEquals(CombatPhase.REVEAL, game.combatPhase)
        assertEquals(MonsterGrade.BOSS, game.monster.grade)
        assertTrue(game.monster.isFinalBoss)
        assertTrue(game.monster.isLabyrinthGateBoss)
        assertTrue(game.monster.name.startsWith("제10구역 관문지기 · "))
        val plannedAttacks = LabyrinthProgression.targetAttacks(
            depth = 10L,
            baseAttacks = MonsterGrade.BOSS.minAttacks,
            isGateBoss = true,
        )
        assertEquals(engine.attackCountForCombatPower(game, plannedAttacks), game.monster.expectedAttacks)

        game.monster.maxEnergy = 1L
        game.monster.currentEnergy = 1L
        game.combatPhase = CombatPhase.ATTACKING
        game.actionStartedAt = game.lastSettledAt
        game.actionEndsAt = game.lastSettledAt + 1L
        engine.settle(game, now = game.actionEndsAt)
        engine.settle(game, now = game.actionEndsAt)

        assertEquals(10L, game.labyrinthDepthCompleted)
        assertEquals(11L, game.adventureTale.labyrinthDepth)
        assertTrue(game.completedTaleHistory.any { it.labyrinthDepth == 10L })
        assertTrue(game.lastLootSummary.contains("제1관문 정복자 해금"))
    }

    @Test
    fun `schema thirty seven keeps active labyrinth completion ratio under new depth rules`() {
        val game = newGame()
        val previous = taleAtDepth(37L)
        previous.currentActIndex = 2
        previous.acts.indices.forEach { index ->
            val old = previous.acts[index]
            previous.acts[index] = old.copy(
                progress = when {
                    index < 2 -> 1_000L
                    index == 2 -> 500L
                    else -> 0L
                },
                target = 1_000L,
                rewardExperience = 1L,
                rewardGold = 1L,
                completed = index < 2,
            )
        }
        game.adventureTale = previous
        game.adventurePhase = AdventurePhase.LOOTING
        game.schemaVersion = 37

        engine.settleOfflineWithOfflineAdventure(game, now = game.lastSettledAt)

        val migrated = game.adventureTale
        val expectedTargets = migrated.acts.indices.map {
            LabyrinthProgression.actTarget(depth = 37L, actIndex = it)
        }
        assertEquals(SIMPLE_GAME_SCHEMA_VERSION, game.schemaVersion)
        assertEquals(expectedTargets, migrated.acts.map { it.target })
        assertTrue(migrated.acts.take(2).all { it.completed && it.progress == it.target })
        assertEquals(expectedTargets[2] / 2L, migrated.acts[2].progress)
        assertTrue(migrated.acts.all { it.rewardExperience > 1L && it.rewardGold > 1L })
    }

    @Test
    fun `highest depth derives the next gate and an endlessly advancing title`() {
        assertEquals("아직 없음", LabyrinthProgression.titleForCompletedDepth(0L))
        assertEquals("미궁 탐사자", LabyrinthProgression.titleForCompletedDepth(9L))
        assertEquals("제1관문 정복자", LabyrinthProgression.titleForCompletedDepth(10L))
        assertEquals("제10관문 정복자", LabyrinthProgression.titleForCompletedDepth(109L))
        assertEquals(10L, LabyrinthProgression.nextGateDepth(0L))
        assertEquals(10L, LabyrinthProgression.nextGateDepth(9L))
        assertEquals(20L, LabyrinthProgression.nextGateDepth(10L))
    }

    private fun taleAtDepth(depth: Long) = AdventureTaleCatalog.instantiate(
        definition = LabyrinthTaleCatalog.definitionForDepth(depth),
        sequence = 42L + depth,
        heroName = "기준 전사",
        heroLevel = 60L,
        variant = AdventureTaleCatalog.variantAt(depth.toInt()),
        labyrinthDepth = depth,
    )

    private fun newGame() = engine.newGame(
        name = "기준 전사",
        heroClass = HeroClass.WARRIOR,
        rolledStats = engine.rollStats(77L).stats,
        seed = 88L,
        now = 0L,
    )
}
