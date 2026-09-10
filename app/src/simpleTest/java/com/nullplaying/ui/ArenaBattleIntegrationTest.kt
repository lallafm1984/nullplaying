package com.nullplaying.ui

import com.nullplaying.BuildConfig
import com.nullplaying.engine.BattleQaMatchFactory
import com.nullplaying.engine.PublicPlayerBattleDerivation
import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.engine.SkillCatalog
import com.nullplaying.engine.arena.ArenaProgressionRules
import com.nullplaying.engine.arena.ArenaProgressionState
import com.nullplaying.engine.arena.ArenaRunStatus
import com.nullplaying.engine.arena.ArenaSkillTreeRules
import com.nullplaying.engine.arena.ArenaSupportEventType
import com.nullplaying.engine.arena.ArenaSupportEventText
import com.nullplaying.engine.arena.ArenaSupportTurnEngine
import com.nullplaying.engine.arena.ArenaSyntheticProfileGrowth
import com.nullplaying.engine.arena.ArenaTurnInputAdapter
import com.nullplaying.localization.AppLanguage
import com.nullplaying.model.ActiveBattleTrait
import com.nullplaying.model.BattleGuidance
import com.nullplaying.model.BattleHeroClass
import com.nullplaying.model.BattleOutcome
import com.nullplaying.model.BattleSeasonStanding
import com.nullplaying.model.BattleTicketState
import com.nullplaying.model.BattleTraitState
import com.nullplaying.model.HeroClass
import com.nullplaying.model.HeroPathState
import com.nullplaying.model.HeroPathTraitProgress
import com.nullplaying.model.LearnedSkill
import com.nullplaying.model.SimpleGameState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ArenaBattleIntegrationTest {
    @Test
    fun `battle QA normal arena uses the saved hero level and persists eligible arena experience`() {
        assertFalse(BuildConfig.BATTLE_IGNORE_HERO_LEVEL_GATE)
        assertEquals(0, BuildConfig.BATTLE_QA_HERO_LEVEL_OVERRIDE)
        assertEquals(
            BuildConfig.BUILD_TYPE == "battleQa",
            BuildConfig.BATTLE_SKILL_TREE_REVIEW_ENABLED,
        )

        val source = actualState(HeroClass.WARRIOR, 16)
        val sourceBefore = Json.encodeToString(source)
        val arenaState = projectArenaStateForQa(source, BuildConfig.BATTLE_QA_HERO_LEVEL_OVERRIDE)
        val progression = ArenaProgressionRules.initialize(
            ArenaProgressionState(),
            source.hero.level,
            BuildConfig.BATTLE_IGNORE_HERO_LEVEL_GATE,
        )
        val ownedAttackIds = ArenaTurnInputAdapter.ownedAttackIds(arenaState)
        val rootId = SkillCatalog.forClass(source.hero.heroClass).first().catalogId
        val skillTree = ArenaSkillTreeRules.allocate(
            state = ArenaSkillTreeRules.initialize(null, source.hero.heroClass),
            heroClass = source.hero.heroClass,
            arenaLevel = 1,
            ownedAttackIds = ownedAttackIds,
            nodeId = rootId,
            targetRank = 1,
            editingEnabled = true,
        ).also { assertTrue(it.error.orEmpty(), it.accepted) }.state
        val arenaMatch = match(arenaState)
        val (result, narrative) = requireNotNull(prepareArenaBattle(
            state = arenaState,
            match = arenaMatch,
            standing = BattleSeasonStanding(),
            tickets = BattleTicketState(20_703L, 10),
            playerStance = BattleStance.BALANCED,
            language = AppLanguage.KOREAN,
            progression = progression,
            ignoreHeroLevelGate = BuildConfig.BATTLE_IGNORE_HERO_LEVEL_GATE,
            skillTree = skillTree,
        ))
        val live = requireNotNull(result.supportBattle)

        assertEquals(16L, result.userLevel)
        assertEquals(16L, live.user.fighter.level)
        val expectedUserStats = requireNotNull(PublicPlayerBattleDerivation.deriveStats(
            heroClass = arenaState.hero.heroClass,
            level = arenaState.hero.level,
            combatPower = arenaMatch.userEffectiveCombatPower,
            rawStats = arenaState.hero.stats,
        ))
        assertEquals(expectedUserStats.values().map { it.toDouble() }, live.user.fighter.stats.values())
        assertEquals(listOf(rootId), live.user.fighter.attacks.map { it.id })
        assertTrue(live.user.arenaClassBalanceEnabled)
        assertTrue(live.opponent.arenaClassBalanceEnabled)
        assertTrue(live.growthEligible)

        val reserved = ArenaProgressionRules.reserveBattle(
            progression,
            result.battle.battleId,
            live.issuedDay,
            allowGrowth = live.growthEligible,
            outcome = result.outcome,
        )
        val persistedPending = requireNotNull(decodeBattleLocalSnapshot(encodeBattleLocalSnapshot(
            BattleLocalSnapshot(
                gameEpochDay = live.issuedDay,
                history = listOf(result.toHistory(narrative, completedAtMillis = 2_000L)),
                arenaProgression = reserved,
                arenaSkillTree = skillTree,
            ),
        )))
        val recovered = recoverArenaProgression(persistedPending)
        val persistedCompleted = requireNotNull(decodeBattleLocalSnapshot(
            encodeBattleLocalSnapshot(recovered),
        ))
        val completed = persistedCompleted.arenaProgression
        val view = ArenaProgressionRules.view(
            completed,
            live.issuedDay,
        )
        val expectedXp = ArenaProgressionRules.xpForOutcome(result.outcome)
        assertEquals(expectedXp, completed.totalXp)
        assertEquals(
            expectedXp - ArenaProgressionRules.xpForLevel(view.level),
            view.xpIntoLevel,
        )
        assertEquals(160L, view.xpToNext)
        assertEquals(ArenaProgressionRules.DAILY_GROWTH_ENTRIES - 1, view.growthRemaining)
        assertNull(completed.pending)
        assertEquals(skillTree, persistedCompleted.arenaSkillTree)
        assertEquals(persistedCompleted, recoverArenaProgression(persistedCompleted))
        assertEquals(sourceBefore, Json.encodeToString(source))
    }

    @Test
    fun `QA level twenty projection uses real class growth and five attacks without mutating low saved heroes`() {
        HeroClass.entries.forEach { heroClass ->
            listOf(1, 6, 9).forEach { savedLevel ->
                val source = actualState(heroClass, savedLevel)
                source.skills[0] = source.skills[0].copy(usageCount = 600L)
                val sourceBefore = Json.encodeToString(source)
                val expectedStats = source.hero.stats.copy()
                val expectedRng = ArenaSyntheticProfileGrowth.grow(
                    engine = SimpleGameEngine(),
                    stats = expectedStats,
                    heroClass = heroClass,
                    fromLevel = savedLevel.toLong(),
                    targetLevel = 20L,
                    identitySeed = source.rngState,
                )

                val projected = projectArenaStateForQa(source, 20)
                val expectedAttacks = SkillCatalog.forClass(heroClass).filter { it.unlockLevel <= 20 }

                assertEquals("$heroClass from Lv.$savedLevel source mutation", sourceBefore, Json.encodeToString(source))
                assertEquals("$heroClass from Lv.$savedLevel projected level", 20L, projected.hero.level)
                assertEquals("$heroClass from Lv.$savedLevel projected stats", expectedStats, projected.hero.stats)
                assertEquals("$heroClass from Lv.$savedLevel projected RNG", expectedRng, projected.rngState)
                assertEquals(
                    "$heroClass from Lv.$savedLevel growth count",
                    source.classGuidedLevelGrowths + (20 - savedLevel),
                    projected.classGuidedLevelGrowths,
                )
                assertEquals(listOf(1, 5, 10, 15, 20), expectedAttacks.map { it.unlockLevel })
                assertEquals(expectedAttacks.map { it.catalogId }, projected.skills.map { it.catalogId })
                assertEquals(5, projected.skills.size)
                assertEquals(600L, projected.skills.first().usageCount)
                assertTrue(projected.skills.drop(1).all { it.usageCount == 0L })
            }
        }
    }

    @Test
    fun `projected level twenty enters the normal arena with five attacks and a terminal immutable result`() {
        HeroClass.entries.forEach { heroClass ->
            val source = actualState(heroClass, 6)
            val sourceBefore = Json.encodeToString(source)
            val projected = projectArenaStateForQa(source, 20)
            val projectedBefore = Json.encodeToString(projected)
            val expectedAttackIds = SkillCatalog.forClass(heroClass)
                .filter { it.unlockLevel <= 20 }
                .map { it.catalogId }
            val arenaMatch = match(projected, seed = 20_000L + heroClass.ordinal)
            val prepared = prepareArenaBattle(
                projected,
                arenaMatch,
                BattleSeasonStanding(),
                BattleTicketState(1, 3),
                BattleStance.BALANCED,
                AppLanguage.KOREAN,
            )

            assertNotNull("$heroClass Lv.20 normal preparation", prepared)
            val (preview, narrative) = requireNotNull(prepared)
            val live = requireNotNull(preview.supportBattle)
            val winner = requireNotNull(live.simulation.winnerId)
            val loser = if (winner == live.user.fighter.id) live.opponent.fighter.id else live.user.fighter.id
            assertEquals(20L, live.user.fighter.level)
            assertEquals(expectedAttackIds, live.user.fighter.attacks.map { it.id })
            assertEquals((1..5).toList(), live.user.fighter.attacks.map { it.tier })
            val expectedUserStats = requireNotNull(PublicPlayerBattleDerivation.deriveStats(
                heroClass = projected.hero.heroClass,
                level = projected.hero.level,
                combatPower = arenaMatch.userEffectiveCombatPower,
                rawStats = projected.hero.stats,
            ))
            assertEquals(expectedUserStats.values().map { it.toDouble() }, live.user.fighter.stats.values())
            assertEquals(ArenaRunStatus.COMPLETED, live.simulation.status)
            assertTrue(live.simulation.fighters.getValue(winner).hp > 0.0)
            assertEquals(0.0, live.simulation.fighters.getValue(loser).hp, 0.0)
            assertEquals(ArenaSupportEventType.END, live.simulation.events.last().type)
            assertEquals(winner, live.simulation.events.last().actorId)
            assertEquals(narrative.scenes.last().text, preview.decisiveMoment)
            assertEquals(sourceBefore, Json.encodeToString(source))
            assertEquals(projectedBefore, Json.encodeToString(projected))
            val captured = live.savedContract()
            val frozen = Json.encodeToString(captured)
            projected.skills.clear()
            SimpleGameEngine().applyClassGuidedGrowth(projected.hero.stats, heroClass, 911L)
            assertEquals(frozen, Json.encodeToString(captured))
            assertEquals(live.simulation, ArenaSupportTurnEngine.simulate(captured.user,
                captured.opponent, captured.seed, captured.rules, ignoreHeroLevelGate = captured.ignoreHeroLevelGate))
        }
    }

    @Test
    fun `normal entry retains every valid owned attack and never grants unowned attacks`() {
        val state = state(HeroClass.MAGE, 30)
        val catalog = SkillCatalog.forClass(HeroClass.MAGE)
        state.skills.clear()
        val owned = catalog.take(6)
        owned.forEachIndexed { index, skill ->
            state.skills += LearnedSkill(index + 1, skill.name, skill.unlockLevel.toLong(),
                skill.description, catalogId = skill.catalogId)
        }
        val foreign = SkillCatalog.forClass(HeroClass.WARRIOR).first()
        state.skills += LearnedSkill(90, foreign.name, 1, "", catalogId = foreign.catalogId)
        state.skills += LearnedSkill(91, catalog.last().name, 1, "", catalogId = catalog.last().catalogId)
        state.skills += LearnedSkill(92, catalog.first().name, 1, "", catalogId = "unknown-id")
        val skillsBefore = state.skills.toList()
        val prepared = prepare(state)
        val live = prepared.first.supportBattle!!

        assertEquals(owned.map { it.catalogId }, live.user.fighter.attacks.map { it.id })
        assertEquals(owned.map { it.catalogId }, prepared.first.battle.user.skills.map { it.skillId })
        assertEquals(skillsBefore, state.skills)
        assertTrue(live.user.traits.isEmpty())
        assertEquals(1, live.user.arenaLevel)
        assertEquals(com.nullplaying.engine.arena.ArenaSupportCatalog.unlockedIds(HeroClass.MAGE, state.hero.level), live.user.supportIds)
    }

    @Test
    fun `old traits equipment and hero path allocations are not combat causes`() {
        val state = state(HeroClass.ROGUE, 20)
        val baseline = prepare(state).first.supportBattle!!
        state.battleTraits = BattleTraitState(active = listOf(ActiveBattleTrait("TRAIT_021")))
        state.heroPath = HeroPathState(
            heroClass = BattleHeroClass.ROGUE,
            revision = 42,
            traits = listOf(HeroPathTraitProgress("TRAIT_006")),
            activeTraitIds = listOf("TRAIT_006"),
        )
        val prepared = prepare(state)

        assertEquals(baseline, prepared.first.supportBattle)
        listOf(prepared.first.battle.user, prepared.first.battle.opponent).forEach {
            assertTrue(it.activeTraitIds.isEmpty())
            assertTrue(it.heroPathTraitRanks.isEmpty())
            assertTrue(it.equipment.isEmpty())
            assertTrue(it.heroPathBattleSnapshot.nodes.isEmpty())
        }
        assertTrue(prepared.first.supportBattle!!.simulation.events.filter {
            it.type == ArenaSupportEventType.TRAIT_TRIGGERED
        }.all { it.actorId == baseline.opponent.fighter.id && it.traitId?.startsWith("AT9_") == true })
    }

    @Test
    fun `synthetic foe uses real class growth and catalog attacks with no arbitrary named skill`() {
        HeroClass.entries.forEach { heroClass ->
            val prepared = prepare(state(heroClass, 10))
            val opponent = prepared.first.supportBattle!!.opponent
            assertEquals(prepared.first.opponentLevel, opponent.fighter.level)
            assertTrue(opponent.fighter.level >= 10)
            opponent.fighter.attacks.forEach { attack ->
                val definition = SkillCatalog.find(attack.id)!!
                assertEquals(opponent.fighter.heroClass, definition.heroClass)
                assertTrue(definition.unlockLevel <= opponent.fighter.level)
                assertEquals(definition.name, attack.name)
                assertEquals(0, attack.masteryBonusPercent)
            }
            assertEquals(com.nullplaying.engine.arena.ArenaSupportCatalog.unlockedIds(opponent.fighter.heroClass, opponent.fighter.level), opponent.supportIds)
            val opponentTrait = opponent.traits.single()
            assertEquals(1, opponentTrait.rank)
            val traitDefinition = requireNotNull(
                com.nullplaying.engine.arena.ArenaProgressionCatalog.find(opponentTrait.id),
            )
            assertEquals(opponent.fighter.heroClass, traitDefinition.heroClass)
        }
    }

    @Test
    fun `every class and ten level band settle only the real terminal winner`() {
        HeroClass.entries.forEach { heroClass ->
            (10..100 step 10).forEach { level ->
                val (result, narrative) = prepare(state(heroClass, level), seed = level.toLong())
                val live = result.supportBattle!!
                val simulation = live.simulation
                val winnerId = simulation.winnerId!!
                val loserId = if (winnerId == live.user.fighter.id) live.opponent.fighter.id else live.user.fighter.id
                assertEquals(ArenaRunStatus.COMPLETED, simulation.status)
                assertTrue(simulation.fighters.getValue(winnerId).hp > 0)
                assertEquals(0.0, simulation.fighters.getValue(loserId).hp, 0.0)
                assertEquals(ArenaSupportEventType.END, simulation.events.last().type)
                assertEquals(winnerId, simulation.events.last().actorId)
                val userWin = winnerId == live.user.fighter.id
                assertEquals(if (userWin) BattleOutcome.USER_WIN else BattleOutcome.USER_LOSS, result.outcome)
                assertEquals(result.outcome, result.battle.outcome)
                assertEquals(2, result.entriesAfter)
                assertEquals(1, result.standingAfter.completedBattles)
                assertEquals(if (userWin) 1 else 0, result.standingAfter.wins)
                assertEquals(if (userWin) 0 else 1, result.standingAfter.losses)
                assertEquals(result.standingAfter.score - 1_000, result.pointDelta)
                assertEquals(narrative.scenes.last().text, result.decisiveMoment)
                assertFalse(narrative.productionDatabaseTouched)
                assertFalse(narrative.modelValid)
                assertTrue(narrative.scenes.none { "TRAIT_" in it.text || "AT9_" in it.text })
            }
        }
    }

    @Test
    fun `exactly identical input has identical outcome and no source mutations`() {
        val state = state(HeroClass.PALADIN, 60)
        val sourceStats = state.hero.stats.copy()
        val skills = state.skills.toList()
        val first = prepare(state)
        assertEquals(first, prepare(state))
        assertEquals(sourceStats, state.hero.stats)
        assertEquals(skills, state.skills)
    }

    @Test
    fun `exhausted tickets and below level ten cannot prepare a result`() {
        val belowGate = state(HeroClass.WARRIOR, 9)
        assertNull(prepareArenaBattle(belowGate, match(belowGate), BattleSeasonStanding(),
            BattleTicketState(1, 3), BattleStance.BALANCED, AppLanguage.KOREAN))
        val eligible = state(HeroClass.WARRIOR, 10)
        assertNull(prepareArenaBattle(eligible, match(eligible), BattleSeasonStanding(),
            BattleTicketState(1, 0), BattleStance.BALANCED, AppLanguage.KOREAN))
    }

    @Test
    fun `arena history keeps one localized event per line with factual shield and heal effects`() {
        val (result, narrative) = prepare(state(HeroClass.CLERIC, 20))
        val live = result.supportBattle!!
        val timeline = buildArenaLiveTimeline(live.simulation,
            linkedMapOf(live.user.fighter.id to result.userName, live.opponent.fighter.id to result.opponentName))
        assertEquals(timeline.logs.map { it.text }, narrative.scenes.map { it.text })
        assertEquals(narrative.scenes.map { it.text }, battlePlaybackLines(narrative))
        assertEquals("arena_local", narrative.source)
        assertTrue(narrative.scenes.any { "체력을" in it.text || "실드" in it.text })
        val basicResults = live.simulation.events.filter { event ->
            event.actionId == "BASIC_ATTACK" && event.type in setOf(
                ArenaSupportEventType.ATTACK_HIT,
                ArenaSupportEventType.ATTACK_MISS,
                ArenaSupportEventType.ATTACK_EVADED,
            )
        }
        assertTrue(basicResults.isNotEmpty())
        val scenesByPhase = narrative.scenes.associateBy { it.phaseId }
        val fighterNames = linkedMapOf(
            live.user.fighter.id to result.userName,
            live.opponent.fighter.id to result.opponentName,
        )
        basicResults.forEach { event ->
            val expected = checkNotNull(ArenaSupportEventText.text(event, fighterNames, "ko"))
            assertTrue(expected.contains("공격"))
            assertFalse(expected.contains("일반 공격"))
            assertTrue(Regex("^.*의 공격[이을] .+$").matches(expected))
            assertEquals(expected, scenesByPhase.getValue("arena-event-${event.sequence}").text)
        }
    }

    @Test
    fun `default arena route selects support engine and preserves runtime above loading boundary`() {
        val panel = sourceFile("BattlePanel.kt").readText()
        val app = sourceFile("AlarmQuestApp.kt").readText()
        val participantBlock = panel.substringAfter("val participate = participate@ {")
            .substringBefore("fun settleStartedBattle(")
        assertTrue(participantBlock.contains("prepareArenaBattle("))
        assertFalse(participantBlock.contains("resolveOfficialBattle("))
        assertTrue(panel.contains("if (state.result.supportBattle != null)"))
        assertTrue(panel.contains("ArenaLiveBattleContent("))
        val runtimePosition = app.indexOf("val arenaRuntime = remember(")
        val loadingPosition = app.indexOf("entryScene != EntryScene.TITLE && !entryReady -> LoadingScreen")
        assertTrue(runtimePosition >= 0 && loadingPosition > runtimePosition)
        assertTrue(app.contains("arenaRuntime = arenaRuntime"))
        assertTrue(panel.contains("var overlayState by runtime::overlayState"))
    }

    @Test
    fun `QA preview appends an active action to the log only after its hold completes`() {
        val activity = battleQaSourceFile("ArenaSupportQaActivity.kt").readText()
        val eventPlayback = activity.substringAfter("val line = ArenaSupportEventText.text(event, names, language)")
            .substringBefore("if (event.type in setOf(ArenaSupportEventType.KO")
        val hold = eventPlayback.indexOf("holdActive((requestedHold - impactActiveMillis)")
        val settledLogs = eventPlayback.indexOf("settledDetailLogs.forEach { logs.add(0, it) }")
        val settledEvent = eventPlayback.indexOf("logs.add(0, event.sequence to line)")

        assertTrue(hold >= 0)
        assertTrue(settledLogs > hold)
        assertTrue(settledEvent > settledLogs)
        assertFalse(eventPlayback.substring(0, hold).contains("logs.add(0"))
    }

    private fun sourceFile(name: String): File = listOf(
        File("src/simple/java/com/nullplaying/ui/$name"),
        File("app/src/simple/java/com/nullplaying/ui/$name"),
    ).firstOrNull(File::isFile) ?: error("Source contract file not found: $name")

    private fun battleQaSourceFile(name: String): File = listOf(
        File("src/battleQa/java/com/nullplaying/ui/$name"),
        File("app/src/battleQa/java/com/nullplaying/ui/$name"),
    ).firstOrNull(File::isFile) ?: error("Battle QA source contract file not found: $name")

    private fun prepare(state: SimpleGameState, seed: Long = 41L) =
        prepareArenaBattle(state, match(state, seed), BattleSeasonStanding(),
            BattleTicketState(1, 3), BattleStance.BALANCED, AppLanguage.KOREAN).also {
            assertNotNull(it)
        }!!

    private fun match(state: SimpleGameState, seed: Long = 41L) = BattleQaMatchFactory.createMatch(
        state, 1_000L, BattleGuidance.BALANCED, 1_000, 0,
        "123e4567-e89b-42d3-a456-426614174444", seed, 1_000L,
    )

    private fun state(heroClass: HeroClass, level: Int): SimpleGameState {
        val engine = SimpleGameEngine()
        val roll = engine.rollStats(71L, heroClass)
        return engine.newGame("보유기술검증", heroClass, roll.stats.copy(), roll.nextSeed, 1_000L).apply {
            rngState = ArenaSyntheticProfileGrowth.grow(
                engine, hero.stats, heroClass,
                targetLevel = level.toLong(), identitySeed = rngState,
            )
            hero.level = level.toLong()
        }
    }

    private fun actualState(heroClass: HeroClass, level: Int): SimpleGameState {
        val engine = SimpleGameEngine()
        val roll = engine.rollStats(701L + heroClass.ordinal, heroClass)
        val state = engine.newGame("실제캐릭터검증", heroClass, roll.stats.copy(), roll.nextSeed, 1_000L)
        state.rngState = ArenaSyntheticProfileGrowth.grow(
            engine, state.hero.stats, heroClass,
            targetLevel = level.toLong(), identitySeed = state.rngState,
        )
        state.hero.level = level.toLong()
        state.classGuidedLevelGrowths = (level - 1).toLong()
        // This stays before the opening action boundary and only repairs the actual saved catalog.
        engine.settle(state, 1_001L)
        return state
    }
}
