package com.nullplaying.engine

import com.nullplaying.model.BattleGuidance
import com.nullplaying.model.BattleCondition
import com.nullplaying.model.ActiveBattleTrait
import com.nullplaying.model.BattleTraitCombatProfile
import com.nullplaying.model.BattleTraitState
import com.nullplaying.model.HeroClass
import com.nullplaying.model.HeroPathState
import com.nullplaying.model.HeroPathTraitProgress
import com.nullplaying.model.LearnedSkill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BattleQaMatchFactoryTest {
    private val engine = SimpleGameEngine()

    @Test
    fun `local player snapshot reflects the current test character and virtual opponent is nearby`() {
        val state = engine.newGame(
            name = "푸른별",
            heroClass = HeroClass.RANGER,
            rolledStats = engine.rollStats(77L, HeroClass.RANGER).stats,
            seed = 88L,
            now = 1_000L,
        ).apply {
            hero.level = 37L
            hero.stats.dexterity = 91L
            equipment.first().name = "테스트 유성궁"
            skills[0] = skills.first().copy(name = "테스트 유성 사격")
            battleTraits = BattleTraitState(
                active = listOf(
                    ActiveBattleTrait("TRAIT_021"),
                    ActiveBattleTrait("TRAIT_041"),
                ),
            )
        }

        val match = BattleQaMatchFactory.createMatch(
            state = state,
            combatPower = 12_345L,
            guidance = BattleGuidance.ASSAULT,
            userScore = 1_044,
            matchSequence = 0,
            battleId = "123e4567-e89b-42d3-a456-426614174111",
            serverSeed = 999L,
            requestedAtMillis = 2_000L,
        )

        assertEquals("푸른별", match.request.user.displayName)
        assertEquals(37L, match.request.user.level)
        assertEquals(12_345L, match.request.user.verifiedPower)
        assertEquals(BattleCondition.NORMAL, match.request.user.condition)
        assertEquals(12_345L, match.userEffectiveCombatPower)
        assertEquals(91L, match.request.user.build.dexterity)
        assertEquals("테스트 유성궁", match.request.user.equipment.first().displayName)
        assertEquals("테스트 유성 사격", match.request.user.skills.first().displayName)
        assertEquals(1, match.request.user.skills.size)
        assertEquals("테스트 유성 사격", match.request.user.skills.single().displayName)
        assertTrue(match.request.user.skills.none { it.skillId.startsWith("core-") })
        assertEquals(4, match.request.opponent.skills.size)
        assertEquals(4, match.request.opponent.skills.map { it.displayName }.distinct().size)
        assertEquals(listOf("TRAIT_021", "TRAIT_041"), match.request.user.activeTraitIds)
        val normalizedUser = ProjectionBattleEngine.simulate(match.request).user
        assertEquals(BattleTraitCombatProfile(), normalizedUser.combatProfile)
        assertTrue(normalizedUser.heroPathBattleSnapshot.treeVersion > 0)
        assertTrue(normalizedUser.heroPathBattleSnapshot.nodes.isEmpty())
        assertEquals(3, match.request.opponent.activeTraitIds.size)
        assertTrue(match.request.opponent.activeTraitIds.all { it.startsWith("TRAIT_") })
        assertEquals(BattleGuidance.ASSAULT, match.request.user.guidance)
        assertNotEquals("영웅", match.request.user.displayName)
        assertNotEquals("상대", match.request.opponent.displayName)
        assertTrue(match.request.opponent.level in 35L..39L)
        assertTrue(match.opponentCombatPower in 11_700L..13_050L)
        assertEquals(BattleCondition.NORMAL, match.request.opponent.condition)
        assertEquals(match.opponentCombatPower, match.opponentEffectiveCombatPower)
        assertTrue(match.request.opponentReferenceScore in 1_000..1_100)
    }

    @Test
    fun `up to four owned skills are selected randomly but reproducibly`() {
        val state = engine.newGame(
            name = "기술선별",
            heroClass = HeroClass.WARRIOR,
            rolledStats = engine.rollStats(19L, HeroClass.WARRIOR).stats,
            seed = 20L,
            now = 0L,
        ).apply {
            skills.clear()
            skills += LearnedSkill(1, "낮은 숙련", 1L, "테스트", usageCount = 0L)
            skills += LearnedSkill(2, "가장 높은 숙련", 1L, "테스트", usageCount = 9_000L)
            skills += LearnedSkill(3, "두 번째 숙련", 1L, "테스트", usageCount = 4_000L)
            skills += LearnedSkill(4, "네 번째 기술", 1L, "테스트", usageCount = 3_000L)
            skills += LearnedSkill(5, "다섯 번째 기술", 1L, "테스트", usageCount = 2_000L)
            skills += LearnedSkill(6, "여섯 번째 기술", 1L, "테스트", usageCount = 1_000L)
        }

        fun selected(seed: Long) = BattleQaMatchFactory.createMatch(
            state = state,
            combatPower = 900L,
            guidance = BattleGuidance.GUARD,
            userScore = 1_000,
            matchSequence = 0,
            battleId = "123e4567-e89b-42d3-a456-426614174333",
            serverSeed = seed,
            requestedAtMillis = 0L,
        ).request.user.skills

        val matchSkills = selected(333L)
        assertEquals(4, matchSkills.size)
        assertEquals(4, matchSkills.count { it.skillId.startsWith("local-skill-") })
        assertEquals(0, matchSkills.count { it.skillId.startsWith("core-") })
        assertTrue(matchSkills.all { selected -> state.skills.any { it.name == selected.displayName } })
        assertEquals(matchSkills.map { it.skillId }, selected(333L).map { it.skillId })

        val learnedSelections = (1L..24L).map { seed ->
            selected(seed)
                .map { it.displayName }
                .toSet()
        }.toSet()
        assertTrue(learnedSelections.size > 1)
        assertTrue(learnedSelections.any { "낮은 숙련" in it })
    }

    @Test
    fun `committed hero path replaces legacy traits and pins its revision`() {
        val state = engine.newGame(
            name = "길위의영웅",
            heroClass = HeroClass.PALADIN,
            rolledStats = engine.rollStats(71L, HeroClass.PALADIN).stats,
            seed = 72L,
            now = 0L,
        ).apply {
            heroPath = HeroPathState(
                heroClass = com.nullplaying.model.BattleHeroClass.PALADIN,
                revision = 7L,
                traits = listOf(HeroPathTraitProgress("TRAIT_006")),
                activeTraitIds = listOf("TRAIT_006"),
            )
        }
        val snapshot = BattleQaMatchFactory.createMatch(
            state = state,
            combatPower = 1_200L,
            guidance = BattleGuidance.BALANCED,
            userScore = 1_000,
            matchSequence = 0,
            battleId = "path-snapshot",
            serverSeed = 5L,
            requestedAtMillis = 0L,
        ).request.user

        assertEquals(listOf("TRAIT_006"), snapshot.activeTraitIds)
        assertEquals(7L, snapshot.heroPathRevision)
        assertEquals(state.heroPath.catalogVersion, snapshot.heroPathCatalogVersion)
        assertEquals(2, snapshot.snapshotVersion)
    }

    @Test
    fun `three sequential local matches rotate distinct named opponents deterministically`() {
        val state = engine.newGame(
            name = "QA20",
            heroClass = HeroClass.WARRIOR,
            rolledStats = engine.rollStats(7L, HeroClass.WARRIOR).stats,
            seed = 8L,
            now = 0L,
        )
        fun opponent(sequence: Int): String = BattleQaMatchFactory.createMatch(
            state = state,
            combatPower = 386L,
            guidance = BattleGuidance.BALANCED,
            userScore = 1_000,
            matchSequence = sequence,
            battleId = "123e4567-e89b-42d3-a456-42661417411$sequence",
            serverSeed = sequence.toLong(),
            requestedAtMillis = 0L,
        ).request.opponent.displayName

        val firstRun = (0..2).map(::opponent)
        val secondRun = (0..2).map(::opponent)
        assertEquals(firstRun, secondRun)
        assertEquals(3, firstRun.toSet().size)
    }
}
