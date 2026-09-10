package com.nullplaying.ui

import com.nullplaying.engine.BattleQaMatchFactory
import com.nullplaying.engine.PublicPlayerBattleDerivation
import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.engine.SkillCatalog
import com.nullplaying.engine.arena.ArenaRecentMatch
import com.nullplaying.engine.arena.ArenaAutoBuildPreset
import com.nullplaying.engine.arena.ArenaLocalReserveMatchmaking
import com.nullplaying.engine.arena.ArenaCharacterPointRules
import com.nullplaying.engine.arena.ArenaProgressionRules
import com.nullplaying.engine.arena.ArenaProgressionState
import com.nullplaying.engine.arena.ArenaOpponentSource
import com.nullplaying.engine.arena.ArenaRunStatus
import com.nullplaying.engine.arena.ArenaSkillNodeKind
import com.nullplaying.engine.arena.ArenaSkillTreeCatalog
import com.nullplaying.engine.arena.ArenaServerMatchSelectionResult
import com.nullplaying.engine.arena.ArenaSkillTreeRules
import com.nullplaying.engine.arena.ArenaSupportInput
import com.nullplaying.engine.arena.ArenaSupportTurnEngine
import com.nullplaying.engine.arena.ArenaSyntheticProfileGrowth
import com.nullplaying.engine.arena.ArenaTurnInputAdapter
import com.nullplaying.engine.arena.arenaStableHash64
import com.nullplaying.engine.arena.arenaServerMatchingEnabled
import com.nullplaying.engine.arena.selectArenaOpponent
import com.nullplaying.engine.arena.selectArenaServerOpponent
import com.nullplaying.engine.arena.withIdentityOpponentRules
import com.nullplaying.engine.arena.withArenaServerOpponent
import com.nullplaying.localization.AppLanguage
import com.nullplaying.model.BattleGuidance
import com.nullplaying.model.BattleOutcome
import com.nullplaying.model.BattleSeasonStanding
import com.nullplaying.model.BattleTicketState
import com.nullplaying.model.HeroClass
import com.nullplaying.model.PublicPlayerRoster
import com.nullplaying.model.PublicPlayerSnapshot
import com.nullplaying.model.PublicPlayerStats
import com.nullplaying.model.SHARED_PLAYER_RULES_VERSION
import com.nullplaying.model.SHARED_PLAYER_SNAPSHOT_VERSION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArenaServerBattleIntegrationTest {
    @Test
    fun `fully grown level twenty one ranger reports outcomes across local reserve pool`() {
        val engine = SimpleGameEngine()
        val state = fullyGrownLevelTwentyOneRangerState(engine)
        val combatPower = engine.displayCombatPower(state)
        val arenaLevel = 21
        val progression = ArenaProgressionState(
            unlocked = true,
            totalXp = ArenaProgressionRules.xpForLevel(5),
            characterLevelPoints = true,
        )
        val ownedAttackIds = ArenaTurnInputAdapter.ownedAttackIds(state)
        val skillTree = ArenaSkillTreeRules.autoAllocate(
            heroClass = HeroClass.RANGER,
            arenaLevel = arenaLevel,
            ownedAttackIds = ownedAttackIds,
            seed = RANGER_SKILL_TREE_SEED,
        )
        val treeView = ArenaSkillTreeRules.view(skillTree, arenaLevel, ownedAttackIds)
        assertTrue(treeView.valid)
        assertEquals(arenaLevel, treeView.spentPoints)
        assertEquals(0, treeView.availablePoints)
        assertEquals(21L, state.hero.level)
        assertEquals(20L, state.classGuidedLevelGrowths)
        assertTrue(PublicPlayerBattleDerivation.powerScalePermille(21L, combatPower) in 900..1_100)
        assertEquals(
            SkillCatalog.forClass(HeroClass.RANGER)
                .filter { it.unlockLevel <= 21 }
                .map { it.catalogId },
            state.skills.map { it.catalogId },
        )

        val outcomes = mutableListOf<BattleOutcome>()
        val history = mutableListOf<ArenaRecentMatch>()
        val opponentCounts = linkedMapOf<String, Int>()
        repeat(LOCAL_POOL_CYCLES) { cycle ->
            repeat(LOCAL_POOL_SIZE) { slot ->
                val sequence = cycle * LOCAL_POOL_SIZE + slot
                val selected = selectArenaOpponent(
                    serverRosterEnabled = false,
                    roster = null,
                    requesterCharacterId = REQUESTER,
                    requesterLevel = state.hero.level,
                    nowEpochMillis = NOW,
                    completedMatchSequence = sequence.toLong(),
                    // Match the live caller: omit these only when testing the legacy rotation.
                    requesterCombatPower = combatPower,
                    requesterStats = state.hero.stats.copy(),
                    recentMatches = history,
                ) as ArenaServerMatchSelectionResult.Ready
                assertEquals(ArenaOpponentSource.LOCAL_RESERVE, selected.source)
                assertEquals(LOCAL_POOL_SIZE, selected.poolSize)
                assertEquals(0, selected.serverPoolSize)
                assertEquals(LOCAL_POOL_SIZE, selected.localPoolSize)
                val opponentId = selected.opponent.projection.projectionId
                opponentCounts[opponentId] = opponentCounts.getOrDefault(opponentId, 0) + 1

                val base = BattleQaMatchFactory.createMatch(
                    state = state,
                    combatPower = combatPower,
                    guidance = BattleGuidance.BALANCED,
                    userScore = 1_000,
                    matchSequence = sequence,
                    battleId = "00000000-0000-4000-8000-${(sequence + 1).toString().padStart(12, '0')}",
                    serverSeed = RANGER_BATTLE_SEED + sequence,
                    requestedAtMillis = NOW,
                )
                val match = base.withArenaServerOpponent(
                    selected = selected,
                    opponentReferenceScore = 1_000,
                )
                val result = requireNotNull(prepareArenaBattle(
                    state = state,
                    match = match,
                    standing = BattleSeasonStanding(),
                    tickets = BattleTicketState(gameEpochDay = 20_703L, remaining = 10),
                    playerStance = BattleStance.BALANCED,
                    language = AppLanguage.KOREAN,
                    progression = progression,
                    skillTree = skillTree,
                    preparedOpponent = selected.opponent.combat,
                    preparedOpponentSource = selected.source,
                    matchmakingProfile = selected.matchmakingProfile,
                )).first
                assertEquals(ArenaRunStatus.COMPLETED, result.supportBattle?.simulation?.status)
                outcomes += result.outcome
                history.add(0, ArenaRecentMatch(
                    "integration-$sequence", opponentId, state.hero.level, result.outcome,
                    combatPower, requireNotNull(selected.matchmakingProfile).adjustmentPermille,
                ))
            }
        }

        val wins = outcomes.count { it == BattleOutcome.USER_WIN }
        val losses = outcomes.count { it == BattleOutcome.USER_LOSS }
        val draws = outcomes.count { it == BattleOutcome.DRAW }
        val stats = state.hero.stats
        println(
            "Lv.21 Ranger representative-growth stats=" +
                "${stats.strength}/${stats.constitution}/${stats.dexterity}/" +
                "${stats.intelligence}/${stats.wisdom}/${stats.charisma} " +
                "HP=${stats.maxHealth} MP=${stats.maxMana} power=$combatPower; " +
                "Skill points $arenaLevel local-reserve outcomes=" +
                "$wins wins/$losses losses/$draws draws (${outcomes.size} battles)",
        )

        assertEquals(LOCAL_POOL_SIZE, opponentCounts.size)
        assertTrue(opponentCounts.values.all { it == LOCAL_POOL_CYCLES })
        assertEquals(LOCAL_POOL_CYCLES * LOCAL_POOL_SIZE, outcomes.size)
        val effectiveWinRate = effectiveWinRatePercent(intArrayOf(wins, losses, draws))
        assertTrue(
            "Representative real-path win rate $effectiveWinRate% must stay in 35..65%",
            effectiveWinRate in 35.0..65.0,
        )
    }

    @Test
    fun `representative ranger identities and every auto build preset report local reserve balance`() {
        val engine = SimpleGameEngine()
        val arenaLevel = 21
        val opponents = requireNotNull(ArenaLocalReserveMatchmaking.build(
            requesterCharacterId = REQUESTER,
            requesterLevel = 21L,
            count = LOCAL_POOL_SIZE,
        ))
        val presets = ArenaAutoBuildPreset.entries
        assertEquals(LOCAL_POOL_SIZE, opponents.size)
        assertEquals(10, presets.size)

        val aggregate = IntArray(3)
        val presetTallies = presets.associateWith { IntArray(3) }
        RANGER_PROFILE_SEEDS.forEachIndexed { profileIndex, identitySeed ->
            val state = fullyGrownLevelTwentyOneRangerState(engine, identitySeed)
            val combatPower = engine.displayCombatPower(state)
            val ownedAttackIds = ArenaTurnInputAdapter.ownedAttackIds(state)
            val profileTally = IntArray(3)

            presets.forEachIndexed { presetIndex, preset ->
                val allocationSeed = arenaStableHash64(
                    "ranger-player-build|$profileIndex|${preset.stableId}",
                )
                val tree = ArenaSkillTreeRules.autoAllocateWithPreset(
                    heroClass = HeroClass.RANGER,
                    arenaLevel = arenaLevel,
                    ownedAttackIds = ownedAttackIds,
                    seed = allocationSeed,
                    preset = preset,
                )
                val treeView = ArenaSkillTreeRules.view(tree, arenaLevel, ownedAttackIds)
                assertTrue("profile=$profileIndex preset=${preset.stableId}", treeView.valid)
                assertEquals(arenaLevel, treeView.spentPoints)
                assertEquals(0, treeView.availablePoints)
                val user = arenaInputForTree(
                    state = state,
                    effectiveCombatPower = combatPower,
                    arenaLevel = arenaLevel,
                    tree = tree,
                )

                opponents.forEachIndexed { opponentIndex, opponent ->
                    val simulation = ArenaSupportTurnEngine.simulate(
                        left = user,
                        right = opponent.combat,
                        seed = arenaStableHash64(
                            "ranger-balance|$profileIndex|$presetIndex|$opponentIndex",
                        ),
                        recordEvents = false,
                    )
                    assertEquals(ArenaRunStatus.COMPLETED, simulation.status)
                    val outcome = when (simulation.winnerId) {
                        user.fighter.id -> BattleOutcome.USER_WIN
                        null -> BattleOutcome.DRAW
                        else -> BattleOutcome.USER_LOSS
                    }
                    recordOutcome(profileTally, outcome)
                    recordOutcome(requireNotNull(presetTallies[preset]), outcome)
                    recordOutcome(aggregate, outcome)
                }
            }

            val stats = state.hero.stats
            println(
                "Ranger profile ${profileIndex + 1}: seed=$identitySeed " +
                    "stats=${stats.strength}/${stats.constitution}/${stats.dexterity}/" +
                    "${stats.intelligence}/${stats.wisdom}/${stats.charisma} " +
                    "HP=${stats.maxHealth} MP=${stats.maxMana} power=$combatPower; " +
                    outcomeText(profileTally),
            )
            assertEquals(presets.size * opponents.size, profileTally.sum())
        }

        presets.forEach { preset ->
            val tally = requireNotNull(presetTallies[preset])
            println("Ranger preset ${preset.stableId}: ${outcomeText(tally)}")
            val presetWinRate = effectiveWinRatePercent(tally)
            assertTrue(
                "Ranger preset ${preset.stableId} win rate $presetWinRate% must stay in 40..70%",
                presetWinRate in 40.0..70.0,
            )
        }
        println("Ranger representative aggregate: ${outcomeText(aggregate)}")
        assertEquals(RANGER_PROFILE_SEEDS.size * presets.size * opponents.size, aggregate.sum())
        val aggregateWinRate = effectiveWinRatePercent(aggregate)
        assertTrue(
            "Representative aggregate win rate $aggregateWinRate% must stay in 45..65%",
            aggregateWinRate in 45.0..65.0,
        )
    }

    @Test
    fun `nearby public opponent uses its own level budget and preserves chosen local tree`() {
        val state = levelTenState()
        val progression = ArenaCharacterPointRules.migrate(ArenaProgressionState(), 10L)
        val arenaLevel = ArenaCharacterPointRules.budget(state.hero.level)
        val ownedAttackIds = ArenaTurnInputAdapter.ownedAttackIds(state)
        val localTree = ArenaSkillTreeRules.autoAllocate(
            heroClass = state.hero.heroClass,
            arenaLevel = arenaLevel,
            ownedAttackIds = ownedAttackIds,
            seed = 41L,
        ).copy(revision = 19L)
        val roster = publicRoster().let { value ->
            value.copy(snapshots = value.snapshots.map { it.copy(level = 11L) })
        }
        val selected = selectArenaServerOpponent(
            enabled = true,
            roster = roster,
            requesterCharacterId = REQUESTER,
            requesterLevel = 10L,
            nowEpochMillis = NOW,
            completedMatchSequence = 0L,
        ) as ArenaServerMatchSelectionResult.Ready
        assertEquals(ArenaOpponentSource.PUBLIC_ROSTER, selected.source)
        val base = BattleQaMatchFactory.createMatch(
            state = state,
            combatPower = 92L,
            guidance = BattleGuidance.BALANCED,
            userScore = 1_000,
            matchSequence = 0,
            battleId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            serverSeed = 999L,
            requestedAtMillis = NOW,
        )
        val match = base.withArenaServerOpponent(selected, opponentReferenceScore = 1_000)

        val prepared = requireNotNull(prepareArenaBattle(
            state = state,
            match = match,
            standing = BattleSeasonStanding(),
            tickets = BattleTicketState(20_703L, 3),
            playerStance = BattleStance.BALANCED,
            language = AppLanguage.KOREAN,
            progression = progression,
            skillTree = localTree,
            preparedOpponent = selected.opponent.combat,
            preparedOpponentSource = selected.source,
        ))
        val (result, narrative) = prepared
        val live = requireNotNull(result.supportBattle)
        assertEquals(10, live.user.arenaLevel)
        assertEquals(11, live.opponent.arenaLevel)
        assertFalse(live.growthEligible)
        assertEquals(10, localTree.allocations.sumOf { it.rank })
        assertEquals(11, live.opponent.supportRanks.values.sum() + live.opponent.fighter.attacks.sumOf { it.arena!!.rank })

        assertEquals(selected.opponent.combat.withIdentityOpponentRules(), live.opponent)
        assertEquals(selected.opponent.combat.fighter.stats, live.opponent.fighter.stats)
        assertEquals(PUBLIC_ID, live.opponent.fighter.id)
        assertEquals("실제상대", result.opponentName)
        assertEquals(92L, result.opponentPower)
        assertEquals(selected.battleSeed, live.simulation.seed)
        assertEquals("arena_public_roster", narrative.source)
        assertFalse(narrative.syntheticOnly)
        assertEquals(localTree.revision, live.skillTreeRevision)
        localTree.allocations.associate { it.nodeId to it.rank }.let { ranks ->
            live.user.fighter.attacks.forEach { attack ->
                assertEquals(ranks.getValue(attack.id), requireNotNull(attack.arena).rank)
            }
        }
        ArenaSupportTurnEngine.validate(live.opponent)
        assertTrue(live.opponent.fighter.attacks.all {
            it.masteryBonusPercent == 0 && it.sourceDamagePercentMin == 0 &&
                it.sourceDamagePercentMax == 0 && it.arena != null
        })
        assertTrue(result.match.request.opponent.equipment.isEmpty())
        assertTrue(result.match.request.opponent.skills.all { it.masteryLevel == 0 })
        val history = result.toHistory(narrative, NOW + 1L)
        assertEquals(PUBLIC_ID, history.opponentProjectionId)
        assertEquals("PUBLIC_ROSTER", history.opponentSource)
    }

    @Test
    fun `local reserve freezes new rules from prepared stats and records its source`() {
        val state = levelTenState()
        val progression = ArenaCharacterPointRules.migrate(ArenaProgressionState(), 10L)
        val arenaLevel = ArenaCharacterPointRules.budget(state.hero.level)
        val localTree = ArenaSkillTreeRules.autoAllocate(
            heroClass = state.hero.heroClass,
            arenaLevel = arenaLevel,
            ownedAttackIds = ArenaTurnInputAdapter.ownedAttackIds(state),
            seed = 91L,
        )
        val selected = selectArenaOpponent(
            serverRosterEnabled = false,
            roster = null,
            requesterCharacterId = REQUESTER,
            requesterLevel = 10L,
            nowEpochMillis = NOW,
            completedMatchSequence = 3L,
        ) as ArenaServerMatchSelectionResult.Ready
        assertEquals(ArenaOpponentSource.LOCAL_RESERVE, selected.source)
        assertEquals(20, selected.poolSize)
        val base = BattleQaMatchFactory.createMatch(
            state = state,
            combatPower = 92L,
            guidance = BattleGuidance.BALANCED,
            userScore = 1_000,
            matchSequence = 3,
            battleId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            serverSeed = 555L,
            requestedAtMillis = NOW,
        )
        val match = base.withArenaServerOpponent(selected, opponentReferenceScore = 1_000)

        val (result, narrative) = requireNotNull(prepareArenaBattle(
            state = state,
            match = match,
            standing = BattleSeasonStanding(),
            tickets = BattleTicketState(20_703L, 3),
            playerStance = BattleStance.BALANCED,
            language = AppLanguage.ENGLISH,
            progression = progression,
            skillTree = localTree,
            preparedOpponent = selected.opponent.combat,
            preparedOpponentSource = selected.source,
        ))

        assertEquals(selected.opponent.combat.withIdentityOpponentRules(), result.supportBattle?.opponent)
        assertEquals(selected.opponent.combat.fighter.stats, result.supportBattle?.opponent?.fighter?.stats)
        assertEquals(selected.battleSeed, result.supportBattle?.simulation?.seed)
        assertEquals("arena_local_reserve", narrative.source)
        assertTrue(narrative.syntheticOnly)
        assertEquals("LOCAL_RESERVE", result.toHistory(narrative, NOW + 1L).opponentSource)
        assertEquals(2, result.entriesAfter)
    }

    @Test
    fun `explicit offline fixture produces one public plus nineteen local candidates without remote gate`() {
        val state = levelTenState()
        val fixture = requireNotNull(arenaServerMatchingQaFixture(
            requested = true,
            debugBuild = true,
            remoteServicesEnabled = false,
            state = state,
            nowEpochMillis = NOW,
            candidateCount = 1,
        ))
        val enabled = arenaServerMatchingEnabled(
            arenaFlagEnabled = false,
            sharedTransportEnabled = false,
            remoteServicesEnabled = false,
            qaFixtureEnabled = true,
        )
        val cycle = (0L until 20L).map { sequence ->
            selectArenaOpponent(
                serverRosterEnabled = enabled,
                roster = fixture,
                requesterCharacterId = fixture.requesterCharacterId,
                requesterLevel = 10L,
                nowEpochMillis = NOW,
                completedMatchSequence = sequence,
            ) as ArenaServerMatchSelectionResult.Ready
        }

        assertEquals(1, fixture.snapshots.size)
        assertEquals(1, cycle.count { it.source == ArenaOpponentSource.PUBLIC_ROSTER })
        assertEquals(19, cycle.count { it.source == ArenaOpponentSource.LOCAL_RESERVE })
        assertTrue(cycle.all { it.serverPoolSize == 1 && it.localPoolSize == 19 })
        assertEquals(20, cycle.map { it.opponent.projection.projectionId }.distinct().size)
        assertTrue(arenaServerMatchingQaFixture(
            requested = true,
            debugBuild = true,
            remoteServicesEnabled = true,
            state = state,
            nowEpochMillis = NOW,
            candidateCount = 1,
        ) == null)
    }

    @Test
    fun `total opponent pool failure occurs before preparation and ticket settlement`() {
        val panel = java.io.File(
            "app/src/simple/java/com/nullplaying/ui/BattlePanel.kt",
        ).takeIf { it.isFile } ?: java.io.File("src/simple/java/com/nullplaying/ui/BattlePanel.kt")
        val participant = panel.readText()
            .substringAfter("val participate = participate@ {")
            .substringBefore("fun settleStartedBattle(")
        val reject = participant.indexOf("return@launch", participant.indexOf("selectArenaOpponent("))
        val prepare = participant.indexOf("prepareArenaBattle(")

        assertTrue(reject >= 0)
        assertTrue(prepare > reject)
        assertTrue(participant.indexOf("selectArenaOpponent(") in 0 until reject)
        assertFalse(participant.substring(0, prepare).contains("spendBattleEntrySnapshot("))
        assertTrue(participant.contains(
            "조건에 맞는 상대가 없습니다. 출전권은 사용되지 않았습니다.",
        ).not())
        assertTrue(panel.readText().contains(
            "조건에 맞는 상대가 없습니다. 출전권은 사용되지 않았습니다.",
        ))
    }

    @Test
    fun `adaptive match captures rating difficulty and survives persistence with legacy histories`() {
        val engine=SimpleGameEngine()
        val state=fullyGrownLevelTwentyOneRangerState(engine)
        val power=engine.displayCombatPower(state)
        val recent=(0..9).map { com.nullplaying.engine.arena.ArenaRecentMatch("old-$it","foe-$it",21,
            BattleOutcome.USER_LOSS,power,-100) }
        val selected=selectArenaOpponent(false,null,REQUESTER,21,NOW,10,power,recent)
            as ArenaServerMatchSelectionResult.Ready
        assertEquals(21L,selected.opponent.projection.level)
        assertEquals(1,selected.matchmakingProfile!!.levelSearchRadius)
        assertEquals(2,selected.matchmakingProfile!!.rulesVersion)
        val progression=ArenaProgressionState(unlocked=true,characterLevelPoints=true)
        val tree=ArenaSkillTreeRules.autoAllocate(HeroClass.RANGER,21,ArenaTurnInputAdapter.ownedAttackIds(state),77L)
        val base=BattleQaMatchFactory.createMatch(state,power,BattleGuidance.BALANCED,1000,10,
            "11111111-1111-4111-8111-111111111111",77L,NOW)
        val match=base.withArenaServerOpponent(selected,1000)
        val prepared=requireNotNull(prepareArenaBattle(state,match,BattleSeasonStanding(),
            BattleTicketState(gameEpochDay=20_703L,remaining=10),BattleStance.BALANCED,AppLanguage.KOREAN,
            progression=progression,skillTree=tree,preparedOpponent=selected.opponent.combat,
            preparedOpponentSource=selected.source,matchmakingProfile=selected.matchmakingProfile))
        val history=prepared.first.toHistory(prepared.second,NOW)
        val snapshot=BattleLocalSnapshot(history=listOf(history),arenaProgression=progression,arenaSkillTree=tree)
        val restored=requireNotNull(decodeBattleLocalSnapshot(encodeBattleLocalSnapshot(snapshot)))
        assertEquals(snapshot,restored)
        assertEquals(selected.matchmakingProfile,restored.history.single().arenaSnapshot!!.matchmakingProfile)
        val observation=arenaRecentMatchHistory(restored.history).single()
        assertEquals(prepared.first.outcome,observation.outcome)
        assertEquals(power,observation.heroPower)
        assertEquals(-100,observation.adjustmentPermille)
        assertEquals(selected.opponent.combat.withIdentityOpponentRules().fighter,prepared.first.supportBattle!!.opponent.fighter)
        assertEquals(selected.opponent.combat.fighter.stats,prepared.first.supportBattle!!.opponent.fighter.stats)
        // A zero-point loss is still a loss, and older saved contracts need no migration.
        val legacy=history.copy(resultLabel="패배",pointDelta=0,
            arenaSnapshot=history.arenaSnapshot!!.copy(matchmakingProfile=null))
        val legacyObservation=arenaRecentMatchHistory(listOf(legacy)).single()
        assertEquals(BattleOutcome.USER_LOSS,legacyObservation.outcome)
        assertEquals(0L,legacyObservation.heroPower)
        assertEquals(0,legacyObservation.adjustmentPermille)
        val versionOne = history.copy(arenaSnapshot=history.arenaSnapshot!!.copy(
            matchmakingProfile=selected.matchmakingProfile!!.copy(rulesVersion=1,levelSearchRadius=2)))
        val oldSnapshot = snapshot.copy(history=listOf(versionOne))
        assertEquals(oldSnapshot,decodeBattleLocalSnapshot(encodeBattleLocalSnapshot(oldSnapshot)))
        assertEquals(power,arenaRecentMatchHistory(listOf(versionOne)).single().heroPower)
        org.junit.Assert.assertNull(prepareArenaBattle(state,match,BattleSeasonStanding(),
            BattleTicketState(gameEpochDay=20_703L,remaining=10),BattleStance.BALANCED,AppLanguage.KOREAN,
            progression=progression,skillTree=tree,preparedOpponent=selected.opponent.combat,
            preparedOpponentSource=selected.source,matchmakingProfile=selected.matchmakingProfile!!.copy(levelSearchRadius=2)))
        org.junit.Assert.assertNull(prepareArenaBattle(state,match,BattleSeasonStanding(),
            BattleTicketState(gameEpochDay=20_703L,remaining=10),BattleStance.BALANCED,AppLanguage.KOREAN,
            progression=progression,skillTree=tree,preparedOpponent=selected.opponent.combat,
            preparedOpponentSource=selected.source,matchmakingProfile=selected.matchmakingProfile!!.copy(heroPower=power+1)))
    }

    private fun levelTenState() = SimpleGameEngine().let { engine ->
        val roll = engine.rollStats(777L, HeroClass.WARRIOR)
        engine.newGame("로컬영웅", HeroClass.WARRIOR, roll.stats.copy(), roll.nextSeed, NOW).apply {
            rankingCharacterId = REQUESTER
            rngState = ArenaSyntheticProfileGrowth.grow(
                engine, hero.stats, hero.heroClass, targetLevel = 10L, identitySeed = rngState,
            )
            hero.level = 10L
            classGuidedLevelGrowths = 9L
            engine.settle(this, NOW + 1L)
        }
    }

    private fun fullyGrownLevelTwentyOneRangerState(
        engine: SimpleGameEngine,
        identitySeed: Long = RANGER_ROLL_SEED,
    ) = engine.rollStats(identitySeed, HeroClass.RANGER).let { roll ->
            engine.newGame(
                name = "LevelTwentyOneRanger",
                heroClass = HeroClass.RANGER,
                rolledStats = roll.stats.copy(),
                seed = roll.nextSeed,
                now = NOW,
            ).apply {
                rankingCharacterId = REQUESTER
                rngState = ArenaSyntheticProfileGrowth.grow(
                    engine = engine,
                    stats = hero.stats,
                    heroClass = hero.heroClass,
                    targetLevel = 21L,
                    identitySeed = rngState,
                )
                hero.level = 21L
                classGuidedLevelGrowths = 20L
                val expectedEquipmentPower = engine.expectedEquipmentCombatPower(hero.level)
                equipment.forEach { item -> item.power = expectedEquipmentPower }
                engine.settle(this, NOW + 1L)
            }
        }

    private fun arenaInputForTree(
        state: com.nullplaying.model.SimpleGameState,
        effectiveCombatPower: Long,
        arenaLevel: Int,
        tree: com.nullplaying.engine.arena.ArenaSkillTreeState,
    ): ArenaSupportInput {
        val ranks = tree.allocations.associate { it.nodeId to it.rank }
        val attackRanks = ranks.filterKeys {
            ArenaSkillTreeCatalog.find(it)?.kind == ArenaSkillNodeKind.ATTACK
        }
        val supportRanks = ranks.filterKeys {
            ArenaSkillTreeCatalog.find(it)?.kind == ArenaSkillNodeKind.SUPPORT
        }
        val built = requireNotNull(ArenaTurnInputAdapter.fromStateForCombatPower(
            state = state,
            id = REQUESTER,
            effectiveCombatPower = effectiveCombatPower,
            attackRanks = attackRanks,
        ))
        assertTrue(built.rejectedSkills.isEmpty())
        return ArenaSupportInput(
            fighter = built.fighter,
            supportIds = supportRanks.keys,
            arenaLevel = arenaLevel,
            arenaClassBalanceEnabled = true,
            traits = emptyList(),
            supportRanks = supportRanks,
        )
    }

    private fun recordOutcome(tally: IntArray, outcome: BattleOutcome) {
        tally[when (outcome) {
            BattleOutcome.USER_WIN -> 0
            BattleOutcome.USER_LOSS -> 1
            BattleOutcome.DRAW -> 2
        }]++
    }

    private fun outcomeText(tally: IntArray): String {
        val total = tally.sum()
        val effectiveWinRate = effectiveWinRatePercent(tally)
        return "${tally[0]} wins/${tally[1]} losses/${tally[2]} draws " +
            "($total battles, ${"%.1f".format(java.util.Locale.ROOT, effectiveWinRate)}%)"
    }

    private fun effectiveWinRatePercent(tally: IntArray): Double =
        if (tally.sum() == 0) 0.0 else {
            (tally[0] + tally[2] * 0.5) * 100.0 / tally.sum()
        }

    private fun publicRoster() = PublicPlayerRoster(
        requesterCharacterId = REQUESTER,
        requesterLevel = 10L,
        rosterId = "88888888-8888-4888-8888-888888888888",
        rosterDateUtc = "2026-09-07",
        rulesVersion = SHARED_PLAYER_RULES_VERSION,
        receivedAtEpochMillis = NOW - 1_000L,
        validUntilEpochMillis = NOW + 10_000L,
        snapshots = listOf(PublicPlayerSnapshot(
            projectionId = PUBLIC_ID,
            displayName = "실제상대",
            heroClass = HeroClass.RANGER,
            level = 10L,
            combatPower = 92L,
            rulesVersion = SHARED_PLAYER_RULES_VERSION,
            snapshotVersion = SHARED_PLAYER_SNAPSHOT_VERSION,
            stats = PublicPlayerStats(10, 11, 12, 13, 14, 15, 180, 90),
            adventureTraitIds = emptyList(),
        )),
    )

    private companion object {
        const val REQUESTER = "99999999-9999-4999-8999-999999999999"
        const val PUBLIC_ID = "11111111-1111-4111-8111-111111111111"
        const val NOW = 1_790_000_000_000L
        const val LOCAL_POOL_SIZE = 20
        const val LOCAL_POOL_CYCLES = 10
        const val RANGER_ROLL_SEED = 0x21_52_41_4EL
        const val RANGER_SKILL_TREE_SEED = 0x52_41_4E_47_45_52L
        const val RANGER_BATTLE_SEED = 0x21_41_52_45_4E_41L
        val RANGER_PROFILE_SEEDS = List(10) { index ->
            RANGER_ROLL_SEED + index.toLong() * 0x9E37L
        }
    }
}
