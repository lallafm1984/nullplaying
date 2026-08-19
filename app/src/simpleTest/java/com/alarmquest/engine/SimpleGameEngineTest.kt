package com.alarmquest.engine

import com.alarmquest.model.CombatPhase
import com.alarmquest.model.AdventurePhase
import com.alarmquest.model.EquipmentSlot
import com.alarmquest.model.HeroClass
import com.alarmquest.model.HeroStats
import com.alarmquest.model.InventoryItem
import com.alarmquest.model.LearnedSkill
import com.alarmquest.model.MonsterGrade
import com.alarmquest.model.SettlementDelta
import com.alarmquest.model.SimpleGameState
import com.alarmquest.model.TaleKind
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleGameEngineTest {
    private val engine = SimpleGameEngine()

    @Test
    fun `character roll uses 3d6 stats and blended intelligence wisdom mana`() {
        repeat(500) { index ->
            val stats = engine.rollStats(index.toLong() + 1L).stats
            stats.values().take(6).forEach { value ->
                assertTrue(value in 3L..18L)
            }
            assertTrue(stats.maxHealth in (stats.constitution / 6L)..(stats.constitution / 6L + 7L))
            val manaBase = engine.manaBaseAttribute(stats.intelligence, stats.wisdom)
            assertTrue(stats.maxMana in (manaBase / 6L)..(manaBase / 6L + 7L))
        }
    }

    @Test
    fun `mana base attribute is an overflow safe fifty fifty int wisdom blend`() {
        assertEquals(15L, engine.manaBaseAttribute(12L, 18L))
        assertEquals(15L, engine.manaBaseAttribute(13L, 18L))
        assertEquals(16L, engine.manaBaseAttribute(13L, 19L))
        assertEquals(Long.MAX_VALUE, engine.manaBaseAttribute(Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(10L, engine.manaBaseAttribute(-100L, 20L))
    }

    @Test
    fun `level growth gives one class stat and one random primary stat`() {
        val seed = 20260811L
        val actual = HeroStats(17, 14, 11, 9, 7, 5, 4, 3)
        val expected = actual.copy()
        val reference = ReferenceRng(seed)

        expected.maxHealth += expected.constitution / 3L + 1L + reference.nextInt(4)
        expected.maxMana +=
            engine.manaBaseAttribute(expected.intelligence, expected.wisdom) / 3L +
            1L + reference.nextInt(4)
        expected.increment(if (reference.nextInt(2) == 0) 3 else 4)
        expected.increment(reference.nextInt(6))

        val nextSeed = engine.applyClassGuidedGrowth(actual, HeroClass.MAGE, seed)

        assertEquals(expected, actual)
        assertEquals(reference.state, nextSeed)
    }

    @Test
    fun `all growth points exclude hp and mp for every class`() {
        val classStats = mapOf(
            HeroClass.WARRIOR to setOf(0, 1),
            HeroClass.ROGUE to setOf(2, 0),
            HeroClass.RANGER to setOf(2, 4),
            HeroClass.MAGE to setOf(3, 4),
            HeroClass.CLERIC to setOf(4, 3),
            HeroClass.PALADIN to setOf(0, 5),
        )

        classStats.forEach { (heroClass, focusedIndices) ->
            repeat(200) { seedOffset ->
                val stats = HeroStats(12, 12, 12, 12, 12, 12, 20, 10)
                val before = stats.copy()
                val seed = heroClass.ordinal * 1_000L + seedOffset + 1L

                engine.applyClassGuidedGrowth(stats, heroClass, seed)

                val primaryDeltas = stats.values().take(6).zip(before.values().take(6))
                    .map { (after, previous) -> after - previous }
                assertEquals(2L, primaryDeltas.sum())
                assertTrue(focusedIndices.any { primaryDeltas[it] > 0L })
                assertTrue(primaryDeltas.all { it in 0L..2L })

                val reference = ReferenceRng(seed)
                val expectedHealthGain = before.constitution / 3L + 1L + reference.nextInt(4)
                val expectedManaGain =
                    engine.manaBaseAttribute(before.intelligence, before.wisdom) / 3L +
                    1L + reference.nextInt(4)
                assertEquals(expectedHealthGain, stats.maxHealth - before.maxHealth)
                assertEquals(expectedManaGain, stats.maxMana - before.maxMana)
            }
        }
    }

    @Test
    fun `a completed Adventure Tale applies the same stat growth without granting a level`() {
        val game = newGame(now = 10_000L)
        game.hero.level = 1_000L
        game.hero.experience = 0L
        repeat(SimpleGameEngine.ACTS_PER_TALE - 1) {
            val act = game.adventureTale.activeAct()
            act.progress = act.target - 1L
            game.monster.grade = MonsterGrade.BOSS
            settleUntilNextKill(game)
        }
        val before = game.hero.stats.values().sum()
        val beforeLevel = game.hero.level
        val finalAct = game.adventureTale.activeAct()
        finalAct.progress = finalAct.target - 1L
        game.monster.grade = MonsterGrade.BOSS

        val delta = settleUntilNextKill(game)

        assertEquals(1L, delta.talesCompleted)
        assertEquals(beforeLevel, game.hero.level)
        assertTrue(game.hero.stats.values().sum() > before)
        assertEquals(2L, game.adventureTale.sequence)
        assertEquals(5, game.adventureTale.acts.size)
        assertEquals(1, game.completedTaleHistory.size)
        assertEquals(5, game.completedTaleHistory.single().actMemories.size)
        assertEquals("돌아오지 않은 순찰대", game.completedTaleHistory.single().title)
    }

    @Test
    fun `skills arrive every five levels and stop at twenty`() {
        val game = newGame(now = 0L)
        for (level in 1L..104L) {
            game.hero.level = level
            game.hero.experience = engine.experienceRequired(level) - 1L
            settleUntilNextKill(game)
        }

        assertEquals(20, game.skills.size)
        assertEquals((1..20).map { it * 5L }, game.skills.map { it.acquiredAtLevel })
        assertEquals(20, game.skills.map { it.name }.distinct().size)
    }

    @Test
    fun `six month progression curve requires the planned cumulative experience to level one hundred`() {
        assertEquals(1_520L, engine.experienceRequired(1L))
        assertEquals(80_864L, engine.experienceRequired(10L))
        assertEquals(1_625_184L, engine.experienceRequired(50L))
        assertEquals(6_168_464L, engine.experienceRequired(99L))
        assertEquals(
            210_050_016L,
            (1L until 100L).sumOf(engine::experienceRequired),
        )
        assertEquals(Long.MAX_VALUE, engine.experienceRequired(Long.MAX_VALUE))
    }

    @Test
    fun `reference hero reaches level one hundred on day one hundred eighty`() {
        val game = newGame(now = 0L)
        val dayMillis = 24L * 60L * 60L * 1_000L

        engine.settleOffline(game, 179L * dayMillis)
        assertTrue("day 179 level=${game.hero.level}", game.hero.level < 100L)

        engine.settleOffline(game, 180L * dayMillis)
        assertEquals("day 180 level", 100L, game.hero.level)
    }

    @Test
    fun `schema nineteen preserves active act progress ratio under the longer targets`() {
        val game = newGame(now = 0L)
        game.schemaVersion = 19
        game.adventureTale.acts.indices.forEach { index ->
            val old = game.adventureTale.acts[index]
            game.adventureTale.acts[index] = old.copy(
                progress = if (index == 0) 10L else 0L,
                target = 20L + index * 5L,
            )
        }

        engine.settleOfflineWithOfflineAdventure(game, now = 0L)

        assertEquals(24, game.schemaVersion)
        assertEquals(listOf(145L, 178L, 220L, 271L, 331L), game.adventureTale.acts.map { it.target })
        assertEquals(72L, game.adventureTale.acts.first().progress)
        assertEquals(0L, game.adventureTale.acts.drop(1).sumOf { it.progress })
    }

    @Test
    fun `schema twenty preserves level experience and act progress ratios`() {
        val game = newGame(now = 0L)
        game.schemaVersion = 20
        game.hero.level = 50L
        game.hero.experience = 561_330L
        val first = game.adventureTale.acts.first()
        game.adventureTale.acts[0] = first.copy(
            progress = 50L,
            target = 100L,
            rewardExperience = 32L,
        )

        engine.settleOfflineWithOfflineAdventure(game, now = 0L)

        assertEquals(24, game.schemaVersion)
        assertEquals(812_592L, game.hero.experience)
        assertEquals(72L, game.adventureTale.acts.first().progress)
        assertEquals(145L, game.adventureTale.acts.first().target)
        assertEquals(46L, game.adventureTale.acts.first().rewardExperience)
    }

    @Test
    fun `schema twenty one caps oversized act targets without scaling rewards twice`() {
        val game = newGame(now = 0L)
        game.schemaVersion = 21
        game.hero.level = 50L
        game.hero.experience = 922_185L
        game.adventureTale = AdventureTaleCatalog.instantiate(
            definition = AdventureTaleCatalog.mainTales[11],
            sequence = 12L,
            heroName = game.hero.name,
            heroLevel = game.hero.level,
            variant = game.adventureTale.variant,
        )
        val first = game.adventureTale.acts.first()
        game.adventureTale.acts[0] = first.copy(
            progress = 6_005L,
            target = 12_010L,
            rewardExperience = 46L,
        )

        engine.settleOfflineWithOfflineAdventure(game, now = 0L)

        assertEquals(24, game.schemaVersion)
        assertEquals(500L, game.adventureTale.acts.first().progress)
        assertEquals(1_000L, game.adventureTale.acts.first().target)
        assertEquals(46L, game.adventureTale.acts.first().rewardExperience)
        assertEquals(812_592L, game.hero.experience)
    }

    @Test
    fun `schema twenty one postgame save enters the new second volume before repeating`() {
        val game = newGame(now = 0L)
        game.schemaVersion = 21
        game.totalTales = 17L
        game.adventureTale = AdventureTaleCatalog.instantiate(
            definition = AdventureTaleCatalog.epilogues.first(),
            sequence = 18L,
            heroName = game.hero.name,
            heroLevel = game.hero.level,
            variant = game.adventureTale.variant,
        )

        engine.settleOfflineWithOfflineAdventure(game, now = 0L)

        assertEquals(24, game.schemaVersion)
        assertEquals("ash_border.c13", game.adventureTale.definitionId)
        assertEquals(13L, game.adventureTale.sequence)
        assertEquals(TaleKind.MAIN, game.adventureTale.kind)
        assertEquals(2, game.adventureTale.volumeNumber)
        assertEquals("유리 숲의 백야", game.adventureTale.volumeTitle)
        assertEquals(17L, game.totalTales)
        assertTrue(game.adventureTale.acts.all { it.target == 1_000L })
        assertTrue(game.monster.catalogId.startsWith("ash_border.c13."))
    }

    @Test
    fun `an act cannot finish until its last encounter is a boss`() {
        val game = newGame(now = 0L)
        val act = game.adventureTale.activeAct()
        act.progress = act.target - 1L
        game.monster.grade = MonsterGrade.NORMAL
        forceVictory(game)

        engine.settle(game, game.actionEndsAt)

        assertEquals(act.target - 1L, act.progress)
        assertEquals(0L, game.totalActs)

        engine.settle(game, game.actionEndsAt)
        assertEquals(MonsterGrade.BOSS, game.monster.grade)
        assertTrue(game.monster.catalogId.endsWith(".boss.01"))
        assertTrue(game.monster.baseName.isNotBlank())

        forceVictory(game)
        engine.settle(game, game.actionEndsAt)
        assertEquals(1L, game.totalActs)
        assertEquals(1, game.adventureTale.currentActIndex)
    }

    @Test
    fun `skill effect preview damage is deterministic and does not advance game state`() {
        val game = newGame(now = 0L)
        game.hero.level = 55L
        game.hero.heroClass = HeroClass.MAGE
        val definition = SkillCatalog.forClass(HeroClass.MAGE).last()
        val rngBefore = game.rngState
        val presentationRngBefore = game.presentationRngState
        val actionBefore = game.actionSequence
        val killsBefore = game.totalKills
        val equipmentBefore = game.equipment.map { it.copy() }

        val first = engine.skillPreviewDamage(game, definition.catalogId)
        val second = engine.skillPreviewDamage(game, definition.catalogId)

        assertTrue(first > 0L)
        assertEquals(first, second)
        assertEquals(rngBefore, game.rngState)
        assertEquals(presentationRngBefore, game.presentationRngState)
        assertEquals(actionBefore, game.actionSequence)
        assertEquals(killsBefore, game.totalKills)
        assertEquals(equipmentBefore, game.equipment)
    }

    @Test
    fun `offline settlement uses all elapsed time beyond the old three day window`() {
        val game = newGame(now = 0L)
        val elapsed = 4L * 24L * 60L * 60L * 1_000L
        val presentationSeed = game.presentationRngState

        val delta = engine.settleOffline(game, elapsed)

        assertEquals(elapsed, delta.elapsedMillis)
        assertEquals(elapsed, game.lastSettledAt)
        assertTrue(delta.defeatedMonsters > 0L)
        assertTrue(game.actionSequence > 0L)
        assertEquals(presentationSeed, game.presentationRngState)
        assertEquals(0L, game.lastDamage)
        assertEquals("", game.lastAttackName)
    }

    @Test
    fun `offline combat boundary settlement matches online gameplay results`() {
        val online = newGame(now = 0L)
        val offline = newGame(now = 0L)
        val elapsed = 6L * 60L * 60L * 1_000L + 12_345L

        engine.settle(online, elapsed)
        engine.settleOffline(offline, elapsed)

        assertEquals(online.hero, offline.hero)
        assertEquals(online.equipment, offline.equipment)
        assertEquals(online.skills, offline.skills)
        assertEquals(online.inventory, offline.inventory)
        assertEquals(online.adventureTale, offline.adventureTale)
        assertEquals(online.completedTaleHistory, offline.completedTaleHistory)
        assertEquals(online.monster, offline.monster)
        assertEquals(online.totalKills, offline.totalKills)
        assertEquals(online.totalActs, offline.totalActs)
        assertEquals(online.totalTales, offline.totalTales)
        assertEquals(online.totalItemsFound, offline.totalItemsFound)
        assertEquals(online.actionSequence, offline.actionSequence)
        assertEquals(online.actionStartedAt, offline.actionStartedAt)
        assertEquals(online.actionEndsAt, offline.actionEndsAt)
        assertEquals(online.adventurePhase, offline.adventurePhase)
        assertEquals(online.combatPhase, offline.combatPhase)
        assertEquals(online.totalReturns, offline.totalReturns)
        assertEquals(online.totalItemsSold, offline.totalItemsSold)
        assertEquals(online.totalEquipmentPurchases, offline.totalEquipmentPurchases)
        assertEquals(online.totalLootEquipmentEquips, offline.totalLootEquipmentEquips)
        assertEquals(online.totalSaleGold, offline.totalSaleGold)
        assertEquals(online.rngState, offline.rngState)
        assertEquals(online.taleRngState, offline.taleRngState)
        assertEquals(online.recentMonsterNames, offline.recentMonsterNames)
        assertEquals(online.recentItemNames, offline.recentItemNames)
        assertEquals(0L, offline.lastDamage)
        assertEquals("", offline.lastAttackName)
    }

    @Test
    fun `same seed snapshots the same tale variant without consuming gameplay rng`() {
        val first = newGameWithSeed(seed = 888L, now = 0L)
        val second = newGameWithSeed(seed = 888L, now = 0L)

        assertEquals(first.adventureTale, second.adventureTale)
        assertEquals(first.taleRngState, second.taleRngState)
        assertEquals(first.rngState, second.rngState)

        val gameplayBefore = first.rngState
        val taleBefore = first.taleRngState
        repeat(3) {
            settleUntilNextKill(first)
        }

        assertEquals(taleBefore, first.taleRngState)
        assertTrue(gameplayBefore != first.rngState)
        assertEquals(second.adventureTale.variant, first.adventureTale.variant)
    }

    @Test
    fun `serialized restart preserves tale prose progress history and deterministic continuation`() {
        val uninterrupted = newGame(now = 0L)
        repeat(7) { settleUntilNextKill(uninterrupted) }
        val json = Json { encodeDefaults = true }
        val restored = json.decodeFromString<SimpleGameState>(json.encodeToString(uninterrupted))

        repeat(15) {
            settleUntilNextKill(uninterrupted)
            settleUntilNextKill(restored)
        }

        assertEquals(uninterrupted.hero, restored.hero)
        assertEquals(uninterrupted.equipment, restored.equipment)
        assertEquals(uninterrupted.inventory, restored.inventory)
        assertEquals(uninterrupted.monster, restored.monster)
        assertEquals(uninterrupted.adventureTale, restored.adventureTale)
        assertEquals(uninterrupted.completedTaleHistory, restored.completedTaleHistory)
        assertEquals(uninterrupted.rngState, restored.rngState)
        assertEquals(uninterrupted.taleRngState, restored.taleRngState)
    }

    @Test
    fun `twenty four authored chapters complete in order then enter epilogues`() {
        val game = newGame(now = 0L)
        val completedTitles = mutableListOf<String>()

        repeat(AdventureTaleCatalog.MAIN_TALE_COUNT) {
            val currentTitle = game.adventureTale.title
            repeat(SimpleGameEngine.ACTS_PER_TALE) {
                val act = game.adventureTale.activeAct()
                act.progress = act.target - 1L
                game.monster.grade = MonsterGrade.BOSS
                settleUntilNextKill(game)
            }
            completedTitles += currentTitle
        }

        assertEquals(AdventureTaleCatalog.mainTales.map { it.title }, completedTitles)
        assertEquals(24L, game.totalTales)
        assertEquals(120L, game.totalActs)
        assertEquals(24, game.completedTaleHistory.size)
        assertTrue(game.completedTaleHistory.all { it.kind == TaleKind.MAIN })
        assertEquals(TaleKind.EPILOGUE, game.adventureTale.kind)
        assertEquals(25L, game.adventureTale.sequence)
    }

    @Test
    fun `ten foreground minutes bank one full offline day proportionally`() {
        val game = newGame(now = 0L)
        game.offlineAdventureMillis = 0L

        engine.advanceOfflineAdventureForeground(
            game,
            SimpleGameEngine.OFFLINE_ADVENTURE_CHARGE_MILLIS / 2L,
        )
        assertEquals(
            SimpleGameEngine.OFFLINE_ADVENTURE_CAPACITY_MILLIS / 2L,
            game.offlineAdventureMillis,
        )
        assertEquals(0.5f, engine.offlineAdventureFraction(game))

        engine.advanceOfflineAdventureForeground(
            game,
            SimpleGameEngine.OFFLINE_ADVENTURE_CHARGE_MILLIS / 2L,
        )
        assertEquals(
            SimpleGameEngine.OFFLINE_ADVENTURE_CAPACITY_MILLIS,
            game.offlineAdventureMillis,
        )
        assertTrue(engine.isOfflineAdventureFull(game))

        engine.advanceOfflineAdventureForeground(game, 60_000L)
        assertEquals(SimpleGameEngine.OFFLINE_ADVENTURE_CAPACITY_MILLIS, game.offlineAdventureMillis)
    }

    @Test
    fun `earned reward fills the offline bank once and never stacks`() {
        val game = newGame(now = 0L)
        game.offlineAdventureMillis = SimpleGameEngine.OFFLINE_ADVENTURE_CAPACITY_MILLIS / 2L

        assertTrue(engine.grantRewardedOfflineAdventure(game, "reward-1"))
        assertEquals(SimpleGameEngine.OFFLINE_ADVENTURE_CAPACITY_MILLIS, game.offlineAdventureMillis)
        assertTrue(!engine.grantRewardedOfflineAdventure(game, "reward-1"))
        assertTrue(!engine.grantRewardedOfflineAdventure(game, "reward-2"))
        assertEquals(SimpleGameEngine.OFFLINE_ADVENTURE_CAPACITY_MILLIS, game.offlineAdventureMillis)
    }

    @Test
    fun `empty offline bank pauses the timeline without granting backlog growth`() {
        val game = newGame(now = 0L)
        game.offlineAdventureMillis = 0L
        val actionStartedAt = game.actionStartedAt
        val actionEndsAt = game.actionEndsAt
        val now = 60L * 60L * 1_000L

        val delta = engine.settleOfflineWithOfflineAdventure(game, now)

        assertEquals(0L, delta.elapsedMillis)
        assertEquals(0L, delta.defeatedMonsters)
        assertEquals(0L, game.totalKills)
        assertEquals(now, game.lastSettledAt)
        assertEquals(actionStartedAt + now, game.actionStartedAt)
        assertEquals(actionEndsAt + now, game.actionEndsAt)

        engine.settle(game, game.actionEndsAt)
        assertEquals(1L, game.actionSequence)
    }

    @Test
    fun `offline growth consumes only the banked time and pauses the uncovered tail`() {
        val limited = newGame(now = 0L)
        val reference = newGame(now = 0L)
        val bankedMillis = 60L * 60L * 1_000L
        val reconnectAt = 2L * bankedMillis
        limited.offlineAdventureMillis = bankedMillis

        val expected = engine.settleOffline(reference, bankedMillis)
        val expectedActionStartedAt = reference.actionStartedAt
        val expectedActionEndsAt = reference.actionEndsAt
        val actual = engine.settleOfflineWithOfflineAdventure(limited, reconnectAt)

        assertEquals(expected.defeatedMonsters, actual.defeatedMonsters)
        assertEquals(expected.levelsGained, actual.levelsGained)
        assertEquals(reference.hero, limited.hero)
        assertEquals(reference.equipment, limited.equipment)
        assertEquals(reference.adventureTale, limited.adventureTale)
        assertEquals(bankedMillis, actual.elapsedMillis)
        assertEquals(reconnectAt, limited.lastSettledAt)
        assertEquals(expectedActionStartedAt + bankedMillis, limited.actionStartedAt)
        assertEquals(expectedActionEndsAt + bankedMillis, limited.actionEndsAt)
        assertEquals(0L, limited.offlineAdventureMillis)
    }

    @Test
    fun `short absence leaves unused offline time in the bank`() {
        val game = newGame(now = 0L)
        val absence = 30L * 60L * 1_000L
        game.offlineAdventureMillis = 2L * 60L * 60L * 1_000L

        val delta = engine.settleOfflineWithOfflineAdventure(game, absence)

        assertEquals(absence, delta.elapsedMillis)
        assertEquals(90L * 60L * 1_000L, game.offlineAdventureMillis)
    }

    @Test
    fun `legacy save keeps its old catch up once then receives a free day`() {
        val migrated = newGame(now = 0L)
        val reference = newGame(now = 0L)
        migrated.schemaVersion = 7
        reference.schemaVersion = 7
        migrated.offlineAdventureMillis = 0L
        val now = 2L * 60L * 60L * 1_000L

        val expected = engine.settleOffline(reference, now)
        val actual = engine.settleOfflineWithOfflineAdventure(
            migrated,
            now,
            LegacyAutoHuntSnapshot(schemaVersion = 7, chargeMillis = 0L, activeUntil = 0L),
        )

        assertEquals(expected.defeatedMonsters, actual.defeatedMonsters)
        assertEquals(reference.hero, migrated.hero)
        assertEquals(reference.equipment, migrated.equipment)
        assertEquals(24, migrated.schemaVersion)
        assertEquals(SimpleGameEngine.OFFLINE_ADVENTURE_CAPACITY_MILLIS, migrated.offlineAdventureMillis)
    }

    @Test
    fun `schema eight active window migrates to its remaining offline balance`() {
        val migrated = newGame(now = 0L)
        val now = 30L * 60L * 1_000L
        val activeUntil = 60L * 60L * 1_000L
        migrated.schemaVersion = 8
        migrated.offlineAdventureMillis = 0L

        val delta = engine.settleOfflineWithOfflineAdventure(
            migrated,
            now,
            LegacyAutoHuntSnapshot(8, chargeMillis = 0L, activeUntil = activeUntil),
        )

        assertEquals(now, delta.elapsedMillis)
        assertEquals(24, migrated.schemaVersion)
        assertEquals(activeUntil - now, migrated.offlineAdventureMillis)
    }

    @Test
    fun `schema eight partial charge converts proportionally into banked time`() {
        val migrated = newGame(now = 0L)
        migrated.schemaVersion = 8
        migrated.offlineAdventureMillis = 0L
        val halfCharge = SimpleGameEngine.OFFLINE_ADVENTURE_CHARGE_MILLIS / 2L

        engine.settleOfflineWithOfflineAdventure(
            migrated,
            now = 0L,
            legacy = LegacyAutoHuntSnapshot(8, chargeMillis = halfCharge, activeUntil = 0L),
        )

        assertEquals(24, migrated.schemaVersion)
        assertEquals(
            SimpleGameEngine.OFFLINE_ADVENTURE_CAPACITY_MILLIS / 2L,
            migrated.offlineAdventureMillis,
        )
    }

    @Test
    fun `recent monsters and items do not repeat`() {
        val game = newGame(now = 0L)
        repeat(240) {
            settleUntilNextKill(game)
            assertEquals(SimpleGameEngine.MONSTER_ENERGY_SCALE, game.monster.maxEnergy)
        }

        assertEquals(game.recentMonsterNames.size, game.recentMonsterNames.distinct().size)
        assertEquals(game.recentItemNames.size, game.recentItemNames.distinct().size)
        assertTrue(game.recentMonsterNames.size >= 20)
        assertTrue(game.recentItemNames.size >= 40)
        assertTrue(game.recentMonsterNames.none { " · " in it })
    }

    @Test
    fun `monster energy only drops on attack events and reaches zero before victory settlement`() {
        val game = newGame(now = 5_000L)
        val expectedAttacks = game.monster.expectedAttacks

        assertEquals(1f, engine.monsterEnergyFraction(game))
        val firstBoundary = game.actionEndsAt
        engine.settle(game, firstBoundary)

        assertEquals(1L, game.actionSequence)
        assertEquals(1, game.monster.attacksCompleted)
        assertTrue(game.monster.currentEnergy < SimpleGameEngine.MONSTER_ENERGY_SCALE)
        assertTrue(game.lastDamage > 0L)
        val afterFirstAttack = game.monster.currentEnergy

        engine.settle(game, firstBoundary + 500L)
        assertEquals(afterFirstAttack, game.monster.currentEnergy)

        while (game.combatPhase != CombatPhase.VICTORY) {
            engine.settle(game, game.actionEndsAt)
        }
        assertEquals(expectedAttacks, game.monster.attacksCompleted)
        assertEquals(0L, game.monster.currentEnergy)
        assertEquals(0f, engine.monsterEnergyFraction(game))
        assertEquals(0L, game.totalKills)

        engine.settle(game, game.actionEndsAt)
        assertEquals(1L, game.totalKills)
        assertEquals(AdventurePhase.LOOTING, game.adventurePhase)
        assertEquals(0L, game.monster.currentEnergy)
        assertTrue(game.lastLootSummary.isNotBlank())

        engine.settle(game, game.actionEndsAt)
        assertEquals(AdventurePhase.COMBAT, game.adventurePhase)
        assertEquals(CombatPhase.REVEAL, game.combatPhase)
        assertEquals(SimpleGameEngine.MONSTER_ENERGY_SCALE, game.monster.currentEnergy)
    }

    @Test
    fun `basic attacks have no technique name and skills keep their attack name`() {
        val game = newGame(now = 0L)
        val definition = SkillCatalog.select(game.skillCatalogSeed, game.hero.heroClass, 1)
        game.skills += LearnedSkill(
            1,
            definition.name,
            5L,
            definition.description,
            definition.catalogId,
        )
        val attackTypes = mutableSetOf<String>()

        repeat(120) {
            val beforeSequence = game.actionSequence
            while (game.actionSequence == beforeSequence || game.lastDamage <= 0L) {
                engine.settle(game, game.actionEndsAt)
            }
            attackTypes += game.lastAttackType
            assertTrue(game.lastDamage > 0L)
            if (game.lastAttackWasSkill) {
                assertEquals(definition.name, game.lastAttackName)
                assertEquals(definition.catalogId, game.lastSkillCatalogId)
                assertEquals("보유 스킬", game.lastAttackType)
            } else {
                assertEquals("", game.lastAttackName)
                assertEquals("기본 공격", game.lastAttackType)
                assertTrue(game.lastResult.startsWith("기본 공격 · "))
            }
        }

        assertEquals(setOf("기본 공격", "보유 스킬"), attackTypes)
    }

    @Test
    fun `legacy learned skills migrate to the direct attack catalog without progress loss`() {
        val game = newGame(now = 0L)
        game.schemaVersion = 6
        game.skills += LearnedSkill(2, "전투 감각", 10L, "예전 설명")
        game.skills += LearnedSkill(19, "세계수의 가호", 95L, "예전 설명")
        val actionEndsAt = game.actionEndsAt

        engine.settle(game, 1L)

        assertEquals(24, game.schemaVersion)
        assertEquals(listOf(2, 19), game.skills.map { it.id })
        assertEquals(listOf(10L, 95L), game.skills.map { it.acquiredAtLevel })
        assertTrue(game.skills.all { it.catalogId.isNotBlank() })
        assertTrue(game.skills.all { SkillCatalog.find(it.catalogId)?.name == it.name })
        assertTrue(game.skills.all { "적" in it.description })
        assertEquals(actionEndsAt, game.actionEndsAt)
    }

    @Test
    fun `schema twenty two warrior display names refresh without rerolling the learned skill`() {
        val game = newGame(now = 0L)
        val definition = SkillCatalog.find("warrior_t20_c04")!!
        val catalogSeed = game.skillCatalogSeed
        game.schemaVersion = 22
        game.skills += LearnedSkill(
            id = 20,
            name = "창세 지진",
            acquiredAtLevel = 100L,
            description = "예전 설명",
            catalogId = definition.catalogId,
        )
        game.lastAttackWasSkill = true
        game.lastSkillCatalogId = definition.catalogId
        game.lastAttackName = "창세 지진"
        game.lastResult = "창세 지진 · 777 피해"

        engine.settle(game, 1L)

        assertEquals(24, game.schemaVersion)
        assertEquals(catalogSeed, game.skillCatalogSeed)
        assertEquals(definition.catalogId, game.skills.single().catalogId)
        assertEquals("천하붕쇄", game.skills.single().name)
        assertEquals(definition.description, game.skills.single().description)
        assertEquals("천하붕쇄", game.lastAttackName)
        assertEquals("천하붕쇄 · 777 피해", game.lastResult)
    }

    @Test
    fun `the class catalogs contain six hundred unique direct attacks`() {
        assertEquals(600, SkillCatalog.all.size)
        assertEquals(600, SkillCatalog.all.map { it.catalogId }.distinct().size)
        HeroClass.entries.forEach { heroClass ->
            val skills = SkillCatalog.forClass(heroClass)
            assertEquals(100, skills.size)
            assertEquals(100, skills.map { it.name }.distinct().size)
            assertTrue(skills.all { "적" in it.description })
        }
    }

    @Test
    fun `warrior catalog stays martial and grows from basic drills to army breaking finishers`() {
        val warrior = SkillCatalog.forClass(HeroClass.WARRIOR)
        val otherClassNames = SkillCatalog.all
            .filter { it.heroClass != HeroClass.WARRIOR }
            .map { it.name }
            .toSet()
        val spellWords = listOf(
            "신성", "심판", "천사", "마력", "비전", "차원", "별", "유성", "혜성",
            "번개", "천둥", "창세", "신격", "종언", "우주", "성광",
        )

        assertEquals(100, warrior.size)
        assertEquals(100, warrior.map { it.name }.distinct().size)
        assertTrue(warrior.none { it.name in otherClassNames })
        assertTrue(warrior.none { definition -> spellWords.any(definition.name::contains) })
        assertEquals(
            listOf("칼날 베기", "완력 내려찍기", "전열 돌진", "지면 발구르기", "거친 연속참"),
            warrior.filter { it.unlockLevel == 5 }.map { it.name },
        )
        assertEquals(
            listOf("만군 대양단", "대륙 파쇄타", "불퇴 대돌파", "대륙 대붕쇄", "만군 대참무"),
            warrior.filter { it.unlockLevel == 95 }.map { it.name },
        )
        assertEquals(
            listOf("천하대양단", "무쌍 대분쇄", "천하무패 돌격", "천하붕쇄", "멸군광란"),
            warrior.filter { it.unlockLevel == 100 }.map { it.name },
        )
        assertEquals((0 until 20).map { 120 + it * 3 }, warrior.chunked(5).map { it.first().damagePercent })
        warrior.forEach { definition ->
            assertEquals(
                listOf(SkillElement.PHYSICAL, SkillElement.EARTH, SkillElement.PHYSICAL, SkillElement.EARTH, SkillElement.PHYSICAL)[definition.candidate],
                definition.element,
            )
        }
    }

    @Test
    fun `every class deterministically learns one different skill in each five level tier`() {
        HeroClass.entries.forEach { heroClass ->
            val seed = SkillCatalog.deriveSeed(123_456_789L, heroClass)
            val firstPass = (1..20).map { tier ->
                SkillCatalog.select(seed, heroClass, tier).catalogId
            }
            val secondPass = (1..20).map { tier ->
                SkillCatalog.select(seed, heroClass, tier).catalogId
            }

            assertEquals(firstPass, secondPass)
            assertEquals(20, firstPass.distinct().size)
        }
    }

    @Test
    fun `all multi hit definitions split total damage exactly`() {
        SkillCatalog.all.forEach { definition ->
            val damages = SkillCatalog.splitDamage(12_345L, definition.hitWeights)
            assertEquals(definition.hitCount, damages.size)
            assertEquals(12_345L, damages.sum())
            assertTrue(damages.all { it >= 0L })
        }
        assertEquals(listOf(0L, 1L), SkillCatalog.splitDamage(1L, listOf(48, 52)))
    }

    @Test
    fun `monster grade controls total battle time without changing the energy scale`() {
        val normal = engine.expectedCombatDurationRangeMillis(MonsterGrade.NORMAL)
        val elite = engine.expectedCombatDurationRangeMillis(MonsterGrade.ELITE)
        val boss = engine.expectedCombatDurationRangeMillis(MonsterGrade.BOSS)

        assertTrue(normal.last < elite.first)
        assertTrue(elite.last < boss.first)
        assertEquals(17_000L, normal.first)
        assertEquals(17_000L, normal.last)
        assertEquals(26_800L, elite.first)
        assertEquals(26_800L, elite.last)
        assertEquals(38_000L, boss.first)
        assertEquals(38_000L, boss.last)
        assertEquals(
            10_000L,
            normal.first - SimpleGameEngine.ENCOUNTER_REVEAL_MILLIS,
        )
    }

    @Test
    fun `loot search and discovery keep their exact presentation times`() {
        assertEquals(3_000L, SimpleGameEngine.LOOT_RESULT_MILLIS)
        assertEquals(5_000L, SimpleGameEngine.ENCOUNTER_SEARCH_MILLIS)
        assertEquals(2_000L, SimpleGameEngine.ENCOUNTER_DISCOVERY_MILLIS)
        assertEquals(
            SimpleGameEngine.ENCOUNTER_SEARCH_MILLIS + SimpleGameEngine.ENCOUNTER_DISCOVERY_MILLIS,
            SimpleGameEngine.ENCOUNTER_REVEAL_MILLIS,
        )
    }

    @Test
    fun `full bag returns sells everything buys affordable gear and departs`() {
        val game = newGame(now = 0L)
        val inventoryCapacity = game.inventoryCapacity().toInt()
        repeat(inventoryCapacity) { index ->
            game.inventory += InventoryItem(
                id = index + 1L,
                name = "전리품 $index",
                rarity = "일반",
                kind = "전리품",
                foundAtLevel = 1L,
            )
        }
        val equipmentBefore = game.equipment.map { it.copy() }
        val price = engine.equipmentPrice(game.hero.level)
        game.hero.gold = price * 3L
        forceVictory(game)

        engine.settle(game, game.actionEndsAt)

        assertEquals(AdventurePhase.LOOTING, game.adventurePhase)
        assertEquals(SimpleGameEngine.LOOT_RESULT_MILLIS, game.actionEndsAt - game.actionStartedAt)
        assertTrue(game.lastLootSummary.isNotBlank())
        assertEquals(inventoryCapacity, game.inventory.size)

        engine.settle(game, game.actionEndsAt)
        assertEquals(AdventurePhase.RETURNING, game.adventurePhase)
        assertEquals(1L, game.totalReturns)
        assertEquals(SimpleGameEngine.RETURN_TO_TOWN_MILLIS, game.actionEndsAt - game.actionStartedAt)
        assertEquals(inventoryCapacity, game.inventory.size)

        engine.settle(game, game.actionEndsAt)
        assertEquals(AdventurePhase.SELLING, game.adventurePhase)
        repeat(inventoryCapacity) {
            engine.settle(game, game.actionEndsAt)
        }

        assertTrue(game.inventory.isEmpty())
        assertEquals(inventoryCapacity.toLong(), game.totalItemsSold)
        assertTrue(game.totalSaleGold > 0L)
        assertEquals(AdventurePhase.SHOPPING, game.adventurePhase)

        var guard = 0
        while (
            game.adventurePhase in setOf(
                AdventurePhase.SHOPPING,
                AdventurePhase.SHOPPING_RESULT,
            ) && guard < 100
        ) {
            engine.settle(game, game.actionEndsAt)
            guard += 1
        }
        assertTrue(game.totalEquipmentPurchases > 0L)
        assertTrue(game.equipment != equipmentBefore)
        assertTrue(game.totalEquipmentPurchases <= EquipmentSlot.entries.size.toLong())
        assertEquals(EquipmentSlot.entries.toSet(), game.shopAttemptedSlots.toSet())
        assertEquals(AdventurePhase.DEPARTING, game.adventurePhase)
        assertEquals(SimpleGameEngine.DEPART_TO_FIELDS_MILLIS, game.actionEndsAt - game.actionStartedAt)

        engine.settle(game, game.actionEndsAt)
        assertEquals(AdventurePhase.COMBAT, game.adventurePhase)
        assertEquals(CombatPhase.REVEAL, game.combatPhase)
        assertEquals(SimpleGameEngine.MONSTER_ENERGY_SCALE, game.monster.currentEnergy)
    }

    @Test
    fun `victory shows loot before exploration and the next encounter`() {
        val game = newGame(now = 0L)
        val defeatedMonsterId = game.monster.id
        forceVictory(game)

        engine.settle(game, game.actionEndsAt)

        assertEquals(AdventurePhase.LOOTING, game.adventurePhase)
        assertEquals(defeatedMonsterId, game.monster.id)
        assertEquals(SimpleGameEngine.LOOT_RESULT_MILLIS, game.actionEndsAt - game.actionStartedAt)
        assertTrue(game.lastLootSummary.isNotBlank())

        engine.settle(game, game.actionEndsAt)

        assertEquals(AdventurePhase.COMBAT, game.adventurePhase)
        assertEquals(CombatPhase.REVEAL, game.combatPhase)
        assertTrue(game.monster.id != defeatedMonsterId)
        assertEquals(SimpleGameEngine.ENCOUNTER_REVEAL_MILLIS, game.actionEndsAt - game.actionStartedAt)
        assertEquals(SimpleGameEngine.MONSTER_ENERGY_SCALE, game.monster.currentEnergy)
    }

    @Test
    fun `each victory grants exactly one item even when a tale act completes`() {
        val game = newGame(now = 0L)
        val act = game.adventureTale.activeAct()
        act.progress = act.target - 1L
        game.monster.grade = MonsterGrade.BOSS
        game.monster.isFinalBoss = true
        val itemsBefore = game.totalItemsFound
        val bagBefore = game.inventory.size
        forceVictory(game)

        engine.settle(game, game.actionEndsAt)

        assertEquals(itemsBefore + 1L, game.totalItemsFound)
        assertEquals(bagBefore + 1, game.inventory.size)
        assertEquals(AdventurePhase.LOOTING, game.adventurePhase)
        assertTrue(game.lastLootName.isNotBlank())
        assertTrue(game.lastLootRarity.isNotBlank())
        assertEquals("장비", game.lastLootKind)
        assertTrue(game.lastLootEquipmentSlot != null)
        assertTrue(game.lastLootEquipmentPower != null)
        assertEquals(1L, game.totalActs)
    }

    @Test
    fun `shopping persists each offer and tries every slot at most once per return`() {
        val game = newGame(now = 0L)
        game.hero.level = 10L
        game.hero.gold = Long.MAX_VALUE
        game.equipment.forEach { item ->
            item.power = 0L
            item.rarity = "일반"
        }
        game.adventurePhase = AdventurePhase.SELLING
        game.actionStartedAt = 0L
        game.actionEndsAt = 1L
        game.lastSettledAt = 0L
        engine.settle(game, game.actionEndsAt)

        var resultCount = 0L
        var guard = 0
        while (
            game.adventurePhase in setOf(
                AdventurePhase.SHOPPING,
                AdventurePhase.SHOPPING_RESULT,
            ) && guard < 100
        ) {
            when (game.adventurePhase) {
                AdventurePhase.SHOPPING -> {
                    val offer = requireNotNull(game.pendingShopOffer)
                    assertEquals(
                        SimpleGameEngine.SHOP_OFFER_MILLIS,
                        game.actionEndsAt - game.actionStartedAt,
                    )
                    assertEquals(
                        engine.equipmentPrice(game.hero.level, offer.slot),
                        offer.price,
                    )
                    assertTrue(offer.newPower > offer.previousPower)
                    assertTrue(offer.rarity in setOf("일반", "고급", "희귀", "영웅"))
                }

                AdventurePhase.SHOPPING_RESULT -> {
                    val purchase = requireNotNull(game.lastShopPurchase)
                    assertEquals(
                        SimpleGameEngine.SHOP_RESULT_MILLIS,
                        game.actionEndsAt - game.actionStartedAt,
                    )
                    val equipped = game.equipment.first { it.slot == purchase.slot }
                    assertEquals(purchase.name, equipped.name)
                    assertEquals(purchase.newPower, equipped.power)
                    assertEquals(purchase.rarity, equipped.rarity)
                    resultCount += 1L
                }

                else -> Unit
            }
            engine.settle(game, game.actionEndsAt)
            guard += 1
        }

        assertEquals(resultCount, game.totalEquipmentPurchases)
        assertEquals(EquipmentSlot.entries.size.toLong(), game.totalEquipmentPurchases)
        assertEquals(EquipmentSlot.entries.toSet(), game.shopAttemptedSlots.toSet())
        assertEquals(AdventurePhase.DEPARTING, game.adventurePhase)
    }

    @Test
    fun `selling shows each sold item and positive amount before the next town action`() {
        val game = newGame(now = 0L)
        game.inventory += InventoryItem(
            id = 1L,
            name = "황혼의 결정 표본",
            rarity = "일반",
            kind = "전리품",
            foundAtLevel = 1L,
        )
        game.adventurePhase = AdventurePhase.RETURNING
        game.actionStartedAt = 0L
        game.actionEndsAt = SimpleGameEngine.RETURN_TO_TOWN_MILLIS
        game.lastSettledAt = 0L
        val goldBefore = game.hero.gold

        engine.settle(game, game.actionEndsAt)

        assertEquals(AdventurePhase.SELLING, game.adventurePhase)
        assertTrue(game.inventory.isEmpty())
        assertEquals("황혼의 결정 표본", game.lastTownItemName)
        assertTrue(game.lastTownGold > 0L)
        assertEquals(goldBefore + game.lastTownGold, game.hero.gold)
        assertTrue(game.lastResult.contains("+${game.lastTownGold}G"))
        assertEquals(SimpleGameEngine.SELL_ITEM_MILLIS, game.actionEndsAt - game.actionStartedAt)

        engine.settle(game, game.actionEndsAt)
        assertTrue(game.adventurePhase != AdventurePhase.SELLING)
    }

    @Test
    fun `sale value is deterministic modest and capped by acquisition level`() {
        fun item(rarity: String, foundAtLevel: Long) = InventoryItem(
            id = foundAtLevel,
            name = "검증 전리품",
            rarity = rarity,
            kind = "장비",
            foundAtLevel = foundAtLevel,
        )

        assertEquals(1L, engine.saleValueForTest(item("일반", 1L)))
        assertEquals(4L, engine.saleValueForTest(item("영웅", 12L)))
        assertEquals(6L, engine.saleValueForTest(item("희귀", 13L)))
        assertEquals(24L, engine.saleValueForTest(item("신화", 100L)))
        assertEquals(24L, engine.saleValueForTest(item("신화", Long.MAX_VALUE)))
    }

    @Test
    fun `better combat loot equips immediately and keeps the replaced item in the bag`() {
        val game = newGame(now = 0L)
        val equipmentBefore = game.equipment.map { it.copy() }
        val inventoryCapacity = game.inventoryCapacity().toInt()
        repeat(inventoryCapacity - 1) { index ->
            game.inventory += InventoryItem(
                id = index + 1L,
                name = "기존 전리품 $index",
                rarity = "일반",
                kind = "전리품",
                foundAtLevel = 1L,
            )
        }
        val oldNames = game.inventory.map { it.name }.toSet()
        game.monster.grade = MonsterGrade.BOSS
        game.monster.isFinalBoss = true
        forceVictory(game)

        engine.settle(game, game.actionEndsAt)

        assertEquals(inventoryCapacity, game.inventory.size)
        assertTrue(game.inventory.map { it.name }.containsAll(oldNames))
        assertTrue(game.inventory.any { it.kind == "장비" && it.equipmentPower == 1L })
        assertTrue(game.equipment != equipmentBefore)
        assertTrue(game.equipment.sumOf { it.power } > equipmentBefore.sumOf { it.power })
        assertTrue(game.totalLootEquipmentEquips > 0L)
        assertTrue(game.lastLootEquipped)
        assertTrue(game.lastLootSummary.contains("새 장비로 장착"))
        assertTrue(game.lastLootPreviousPower != null)
        assertEquals(AdventurePhase.LOOTING, game.adventurePhase)

        engine.settle(game, game.actionEndsAt)
        assertEquals(AdventurePhase.RETURNING, game.adventurePhase)

        engine.settle(game, game.actionEndsAt)

        assertEquals(AdventurePhase.SELLING, game.adventurePhase)
    }

    @Test
    fun `equipment names omit normal enhancement and use numbers for higher rarity`() {
        assertEquals("별빛 장검", engine.equipmentNameForRarity("별빛 장검", "일반"))
        assertEquals("별빛 장검 +1", engine.equipmentNameForRarity("별빛 장검", "고급"))
        assertEquals("별빛 장검 +2", engine.equipmentNameForRarity("별빛 장검", "희귀"))
        assertEquals("별빛 장검 +3", engine.equipmentNameForRarity("별빛 장검", "영웅"))
        assertEquals("별빛 장검 +4", engine.equipmentNameForRarity("별빛 장검", "전설"))
        assertEquals("별빛 장검 +5", engine.equipmentNameForRarity("별빛 장검", "신화"))
    }

    @Test
    fun `schema fifteen display names migrate without changing equipment power or rarity`() {
        val game = newGame(now = 0L)
        game.schemaVersion = 15
        val equipped = game.equipment.first()
        equipped.name = "별빛 장검 · 집중"
        equipped.rarity = "일반"
        equipped.power = 777L
        val rareEquipped = game.equipment[1]
        rareEquipped.name = "황혼의 투구 · 강인함"
        rareEquipped.rarity = "희귀"
        rareEquipped.power = 778L
        game.inventory += InventoryItem(
            id = 1L,
            name = "미스릴 장화 · 정밀",
            rarity = "일반",
            kind = "장비",
            foundAtLevel = 10L,
            equipmentSlot = EquipmentSlot.FEET,
            equipmentPower = 888L,
        )
        game.inventory += InventoryItem(
            id = 2L,
            name = "왕가의 반지 · 균형",
            rarity = "전설",
            kind = "장비",
            foundAtLevel = 10L,
            equipmentSlot = EquipmentSlot.ACCESSORY,
            equipmentPower = 889L,
        )
        game.monster.name = "굶주린 뿔늑대 · 검은 바람"
        game.lastResult = "굶주린 뿔늑대 · 검은 바람 처치 · 경험치 +25"
        game.recentMonsterNames = mutableListOf(
            "굶주린 뿔늑대 · 검은 바람",
            "잿빛 숲 고블린 · 폐허의 파수꾼",
        )

        engine.settle(game, 1L)

        assertEquals(24, game.schemaVersion)
        assertEquals("별빛 장검", equipped.name)
        assertEquals(777L, equipped.power)
        assertEquals("일반", equipped.rarity)
        assertEquals("황혼의 투구 +2", rareEquipped.name)
        assertEquals(778L, rareEquipped.power)
        assertEquals(listOf("미스릴 장화", "왕가의 반지 +4"), game.inventory.map { it.name })
        assertEquals(listOf(888L, 889L), game.inventory.map { it.equipmentPower })
        assertEquals("굶주린 뿔늑대", game.monster.name)
        assertEquals("굶주린 뿔늑대 처치 · 경험치 +25", game.lastResult)
        assertEquals(listOf("굶주린 뿔늑대", "잿빛 숲 고블린"), game.recentMonsterNames)
    }

    @Test
    fun `new games use the current schema with the first authored tale`() {
        val game = newGame(now = 0L)

        assertEquals(24, game.schemaVersion)
        assertTrue(" · " !in game.monster.name)
        assertEquals("잿빛 국경", game.adventureTale.volumeTitle)
        assertEquals(1, game.adventureTale.chapterNumber)
        assertEquals("돌아오지 않은 순찰대", game.adventureTale.title)
        assertEquals(SimpleGameEngine.ACTS_PER_TALE, game.adventureTale.acts.size)
        assertTrue(game.adventureTale.acts.all { it.progress == 0L && !it.completed })
    }

    @Test
    fun `inventory capacity matches the Progress Quest ten plus strength rule`() {
        val game = newGame(now = 0L)

        game.hero.stats.strength = 3L
        assertEquals(13L, game.inventoryCapacity())
        game.hero.stats.strength = 18L
        assertEquals(28L, game.inventoryCapacity())
        game.hero.stats.strength = 1_234L
        assertEquals(1_244L, game.inventoryCapacity())
        game.hero.stats.strength = Long.MAX_VALUE
        assertEquals(Long.MAX_VALUE, game.inventoryCapacity())
    }

    @Test
    fun `equipment price follows the Progress Quest level formula`() {
        assertEquals(35L, engine.equipmentPrice(1L))
        assertEquals(195L, engine.equipmentPrice(5L))
        assertEquals(620L, engine.equipmentPrice(10L))
    }

    @Test
    fun `shop price uses the approved percentage for each equipment slot`() {
        assertEquals(150L, engine.equipmentPricePercent(EquipmentSlot.WEAPON))
        assertEquals(120L, engine.equipmentPricePercent(EquipmentSlot.BODY))
        assertEquals(100L, engine.equipmentPricePercent(EquipmentSlot.HEAD))
        assertEquals(90L, engine.equipmentPricePercent(EquipmentSlot.HANDS))
        assertEquals(90L, engine.equipmentPricePercent(EquipmentSlot.FEET))
        assertEquals(80L, engine.equipmentPricePercent(EquipmentSlot.ACCESSORY))

        assertEquals(930L, engine.equipmentPrice(10L, EquipmentSlot.WEAPON))
        assertEquals(744L, engine.equipmentPrice(10L, EquipmentSlot.BODY))
        assertEquals(620L, engine.equipmentPrice(10L, EquipmentSlot.HEAD))
        assertEquals(558L, engine.equipmentPrice(10L, EquipmentSlot.HANDS))
        assertEquals(558L, engine.equipmentPrice(10L, EquipmentSlot.FEET))
        assertEquals(496L, engine.equipmentPrice(10L, EquipmentSlot.ACCESSORY))
        assertEquals(53L, engine.equipmentPrice(1L, EquipmentSlot.WEAPON))
        assertEquals(Long.MAX_VALUE, engine.equipmentPrice(Long.MAX_VALUE, EquipmentSlot.WEAPON))
    }

    @Test
    fun `exact weapon price buys the persisted offer and shows its replacement values`() {
        val game = newGame(now = 0L)
        game.hero.level = 10L
        game.equipment.forEach { item ->
            item.rarity = "일반"
            item.power = Long.MAX_VALUE
        }
        val weapon = game.equipment.first { it.slot == EquipmentSlot.WEAPON }
        weapon.rarity = "일반"
        weapon.power = 0L
        val price = engine.equipmentPrice(game.hero.level, EquipmentSlot.WEAPON)
        game.hero.gold = price
        game.adventurePhase = AdventurePhase.SELLING
        game.actionStartedAt = 0L
        game.actionEndsAt = 1L
        game.lastSettledAt = 0L

        engine.settle(game, game.actionEndsAt)

        val offer = requireNotNull(game.pendingShopOffer)
        assertEquals(EquipmentSlot.WEAPON, offer.slot)
        assertEquals(price, offer.price)
        assertEquals(weapon.power, offer.previousPower)
        assertTrue(
            SimpleContent.equipmentPrefixes(game.hero.level).any { prefix ->
                offer.name.startsWith("$prefix ")
            },
        )
        assertTrue(
            SimpleContent.equipmentBases(
                EquipmentSlot.WEAPON,
                game.hero.level,
                game.hero.heroClass,
            ).any { base ->
                base in offer.name
            },
        )

        engine.settle(game, game.actionEndsAt)

        assertEquals(AdventurePhase.SHOPPING_RESULT, game.adventurePhase)
        assertEquals(0L, game.hero.gold)
        assertEquals(1L, game.totalEquipmentPurchases)
        assertEquals(offer, game.lastShopPurchase)
        assertEquals(offer.newPower, weapon.power)

        engine.settle(game, game.actionEndsAt)

        assertEquals(AdventurePhase.DEPARTING, game.adventurePhase)
    }

    @Test
    fun `pending shop offer survives a serialized restart and resolves identically`() {
        val uninterrupted = newGame(now = 0L)
        uninterrupted.hero.level = 10L
        uninterrupted.equipment.forEach { it.power = 0L }
        uninterrupted.hero.gold = 10_000L
        uninterrupted.adventurePhase = AdventurePhase.SELLING
        uninterrupted.actionStartedAt = 0L
        uninterrupted.actionEndsAt = 1L
        uninterrupted.lastSettledAt = 0L
        engine.settle(uninterrupted, uninterrupted.actionEndsAt)
        val json = Json { encodeDefaults = true }
        val restored = json.decodeFromString<SimpleGameState>(json.encodeToString(uninterrupted))

        engine.settle(uninterrupted, uninterrupted.actionEndsAt)
        engine.settle(restored, restored.actionEndsAt)

        assertEquals(uninterrupted.hero, restored.hero)
        assertEquals(uninterrupted.equipment, restored.equipment)
        assertEquals(uninterrupted.lastShopPurchase, restored.lastShopPurchase)
        assertEquals(uninterrupted.shopAttemptedSlots, restored.shopAttemptedSlots)
        assertEquals(uninterrupted.rngState, restored.rngState)
        assertEquals(AdventurePhase.SHOPPING_RESULT, restored.adventurePhase)
    }

    @Test
    fun `equipment loot uses approved rare odds while trophy and shop stop at plus three`() {
        val equipmentCounts = (0 until 1_000_000)
            .groupingBy(engine::equipmentLootRarityForRoll)
            .eachCount()

        assertEquals(100, equipmentCounts.getValue("신화"))
        assertEquals(1_000, equipmentCounts.getValue("전설"))
        assertEquals(50_000, equipmentCounts.getValue("영웅"))
        assertEquals(140_000, equipmentCounts.getValue("희귀"))
        assertEquals(300_000, equipmentCounts.getValue("고급"))
        assertEquals(508_900, equipmentCounts.getValue("일반"))

        val trophyCounts = (0 until 100)
            .groupingBy(engine::trophyRarityForRoll)
            .eachCount()
        assertEquals(7, trophyCounts.getValue("영웅"))
        assertEquals(14, trophyCounts.getValue("희귀"))
        assertEquals(30, trophyCounts.getValue("고급"))
        assertEquals(49, trophyCounts.getValue("일반"))
        assertEquals(null, trophyCounts["전설"])
        assertEquals(null, trophyCounts["신화"])

        val shopCounts = (0 until 1_000)
            .groupingBy(engine::shopRarityForRoll)
            .eachCount()
        assertEquals(1, shopCounts.getValue("영웅"))
        assertEquals(20, shopCounts.getValue("희귀"))
        assertEquals(229, shopCounts.getValue("고급"))
        assertEquals(750, shopCounts.getValue("일반"))
        assertEquals(null, shopCounts["전설"])
        assertEquals(null, shopCounts["신화"])
    }

    @Test
    fun `shop protects plus four and plus five for their acquisition level only`() {
        val game = newGame(now = 0L)
        game.hero.level = 10L
        game.equipment.forEach { item ->
            item.rarity = "일반"
            item.power = Long.MAX_VALUE
        }
        val protected = game.equipment.first { it.slot == EquipmentSlot.WEAPON }
        protected.rarity = "전설"
        protected.power = 0L
        protected.acquiredAtLevel = 10L
        game.hero.gold = Long.MAX_VALUE
        game.adventurePhase = AdventurePhase.SELLING
        game.actionStartedAt = 0L
        game.actionEndsAt = 1L
        game.lastSettledAt = 0L

        engine.settle(game, game.actionEndsAt)

        assertEquals(AdventurePhase.DEPARTING, game.adventurePhase)
        assertEquals(null, game.pendingShopOffer)
        assertEquals(0L, game.totalEquipmentPurchases)

        game.hero.level = 11L
        game.shopAttemptedSlots.clear()
        game.adventurePhase = AdventurePhase.SELLING
        game.actionStartedAt = game.actionEndsAt
        game.actionEndsAt += 1L
        engine.settle(game, game.actionEndsAt)

        val offer = requireNotNull(game.pendingShopOffer)
        assertEquals(EquipmentSlot.WEAPON, offer.slot)
        assertTrue(offer.rarity in setOf("일반", "고급", "희귀", "영웅"))
        assertTrue(offer.newPower > protected.power)
    }

    @Test
    fun `shop candidate power uses only the small zero through three grade bonus`() {
        assertEquals(45L, engine.shopEquipmentPowerForRoll(10L, "일반", 0))
        assertEquals(46L, engine.shopEquipmentPowerForRoll(10L, "고급", 0))
        assertEquals(47L, engine.shopEquipmentPowerForRoll(10L, "희귀", 0))
        assertEquals(48L, engine.shopEquipmentPowerForRoll(10L, "영웅", 0))
        assertEquals(59L, engine.shopEquipmentPowerForRoll(10L, "영웅", 11))
        assertEquals(1L, engine.shopEquipmentPowerForRoll(1L, "일반", 0))
    }

    @Test
    fun `random selected slot ends the shop visit when its real upgrade is unaffordable`() {
        val game = newGame(now = 0L)
        game.hero.level = 10L
        game.hero.gold = 0L
        game.equipment.forEach { item -> item.power = 0L }
        game.adventurePhase = AdventurePhase.SELLING
        game.actionStartedAt = 0L
        game.actionEndsAt = 1L
        game.lastSettledAt = 0L

        engine.settle(game, game.actionEndsAt)

        assertEquals(AdventurePhase.DEPARTING, game.adventurePhase)
        assertEquals(null, game.pendingShopOffer)
        assertEquals(EquipmentSlot.entries.toSet(), game.shopAttemptedSlots.toSet())
        assertEquals(0L, game.totalEquipmentPurchases)
    }

    @Test
    fun `shop never forces a micro upgrade when every actual candidate is weaker`() {
        val game = newGame(now = 0L)
        game.hero.level = 10L
        game.hero.gold = Long.MAX_VALUE
        game.equipment.forEach { item -> item.power = Long.MAX_VALUE }
        game.adventurePhase = AdventurePhase.SELLING
        game.actionStartedAt = 0L
        game.actionEndsAt = 1L
        game.lastSettledAt = 0L

        engine.settle(game, game.actionEndsAt)

        assertEquals(AdventurePhase.DEPARTING, game.adventurePhase)
        assertEquals(null, game.pendingShopOffer)
        assertEquals(EquipmentSlot.entries.toSet(), game.shopAttemptedSlots.toSet())
        assertEquals(0L, game.totalEquipmentPurchases)
    }

    @Test
    fun `schema twenty three protects migrated high rarity gear and invalidates its old offer`() {
        val game = newGame(now = 0L)
        game.schemaVersion = 23
        game.hero.level = 42L
        game.equipment.first().rarity = "신화"
        game.adventurePhase = AdventurePhase.SHOPPING
        game.pendingShopOffer = com.alarmquest.model.ShopEquipmentOffer(
            slot = EquipmentSlot.WEAPON,
            name = "구형 상점 무기 +3",
            rarity = "영웅",
            previousPower = game.equipment.first().power,
            newPower = game.equipment.first().power + 1L,
            price = 1L,
        )
        game.shopAttemptedSlots += EquipmentSlot.WEAPON

        engine.settleOfflineWithOfflineAdventure(game, now = 0L)

        assertEquals(24, game.schemaVersion)
        assertTrue(game.equipment.all { it.acquiredAtLevel == 42L })
        assertTrue(game.shopAttemptedSlots.isEmpty())
        assertEquals(null, game.pendingShopOffer)
    }

    @Test
    fun `equipment prefixes still advance from beginner gear to endgame gear`() {
        assertTrue("수습생의" in SimpleContent.equipmentPrefixes(1L))
        assertTrue("미스릴" in SimpleContent.equipmentPrefixes(60L))
        assertTrue("성좌의" in SimpleContent.equipmentPrefixes(100L))
    }

    @Test
    fun `equipment combat benchmark follows measured long term progression`() {
        assertEquals(1L, engine.expectedEquipmentCombatPower(1L))
        assertEquals(21L, engine.expectedEquipmentCombatPower(5L))
        assertEquals(46L, engine.expectedEquipmentCombatPower(10L))
        assertEquals(246L, engine.expectedEquipmentCombatPower(50L))
        assertEquals(496L, engine.expectedEquipmentCombatPower(100L))
    }

    @Test
    fun `expected weighted stat growth uses the exact two thirds level rate`() {
        assertEquals(315L, engine.expectedWeightedStatThirtieths(1L))
        assertEquals(2_295L, engine.expectedWeightedStatThirtieths(100L))
        assertEquals(4_295L, engine.expectedWeightedStatThirtieths(200L))
        assertEquals(10_295L, engine.expectedWeightedStatThirtieths(500L))
        assertEquals(20_295L, engine.expectedWeightedStatThirtieths(1_000L))
    }

    @Test
    fun `legacy levels retain their former combat power benchmark`() {
        val game = newGame(now = 0L)
        game.schemaVersion = 18
        game.hero.level = 100L
        game.classGuidedLevelGrowths = 0L
        game.hero.heroClass = HeroClass.WARRIOR
        game.hero.stats.strength = 42L
        game.hero.stats.constitution = 36L
        setAllEquipmentPower(game, 496L)

        assertEquals(1_206L, engine.expectedWeightedStatThirtieths(100L, 0L))
        assertEquals(496L, engine.characterStatPower(game))

        engine.settleOfflineWithOfflineAdventure(game, now = 0L)

        assertEquals(24, game.schemaVersion)
        assertEquals(0L, game.classGuidedLevelGrowths)
        assertEquals(496L, engine.characterStatPower(game))
        assertEquals(992L, engine.displayCombatPower(game))
    }

    @Test
    fun `real level ups count toward the new two thirds benchmark`() {
        val game = newGame(now = 0L)
        game.hero.experience = engine.experienceRequired(game.hero.level) - 1L

        settleUntilNextKill(game)

        assertEquals(2L, game.hero.level)
        assertEquals(1L, game.classGuidedLevelGrowths)
        assertEquals(335L, engine.expectedWeightedStatThirtieths(2L, 1L))
    }

    @Test
    fun `expected stat growth and equipment produce an even fifty fifty combat power`() {
        val game = newGame(now = 0L)
        game.hero.level = 10L
        game.classGuidedLevelGrowths = 9L
        game.hero.heroClass = HeroClass.WARRIOR
        game.hero.stats.strength = 18L
        game.hero.stats.constitution = 13L
        setAllEquipmentPower(game, 46L)

        assertEquals(46L, engine.expectedEquipmentCombatPower(10L))
        assertEquals(92L, engine.expectedCombatPower(game))
        assertEquals(46L, engine.characterStatPower(game))
        assertEquals(46L, engine.averageEquipmentPower(game))
        assertEquals(92L, engine.displayCombatPower(game))
        assertEquals(100, engine.combatDurationPercent(game))
        assertEquals(20, engine.attackCountForCombatPower(game, 20))

        game.totalTales = 1L
        game.hero.stats.intelligence += 1L
        game.hero.stats.maxHealth += 5L
        game.hero.stats.maxMana += 4L
        assertEquals(46L, engine.characterStatPower(game))
        assertEquals(92L, engine.displayCombatPower(game))

        game.hero.stats.strength = 18L
        game.hero.stats.constitution = 23L
        assertEquals(52L, engine.characterStatPower(game))
        assertEquals(98L, engine.displayCombatPower(game))
    }

    @Test
    fun `equal stat and equipment contributions influence combat time equally`() {
        val game = newGame(now = 0L)
        game.hero.level = 10L
        game.classGuidedLevelGrowths = 9L
        game.hero.heroClass = HeroClass.WARRIOR
        game.hero.stats.strength = 30L
        game.hero.stats.constitution = 10L
        setAllEquipmentPower(game, 46L)

        assertEquals(55L, engine.characterStatPower(game))
        assertEquals(46L, engine.averageEquipmentPower(game))
        assertEquals(101L, engine.displayCombatPower(game))
        assertEquals(95, engine.combatDurationPercent(game))
        assertEquals(19, engine.attackCountForCombatPower(game, 20))

        game.hero.stats.strength = 18L
        game.hero.stats.constitution = 13L
        setAllEquipmentPower(game, 55L)

        assertEquals(46L, engine.characterStatPower(game))
        assertEquals(55L, engine.averageEquipmentPower(game))
        assertEquals(101L, engine.displayCombatPower(game))
        assertEquals(95, engine.combatDurationPercent(game))
        assertEquals(19, engine.attackCountForCombatPower(game, 20))
    }

    @Test
    fun `total combat power keeps the existing battle duration safety bounds`() {
        val game = newGame(now = 0L)
        game.hero.level = 10L
        game.classGuidedLevelGrowths = 9L
        game.hero.heroClass = HeroClass.WARRIOR
        game.hero.stats.strength = 0L
        game.hero.stats.constitution = 0L
        setAllEquipmentPower(game, 46L)

        assertEquals(0L, engine.characterStatPower(game))
        assertEquals(46L, engine.displayCombatPower(game))
        assertEquals(125, engine.combatDurationPercent(game))
        assertEquals(25, engine.attackCountForCombatPower(game, 20))

        setAllEquipmentPower(game, 0L)
        assertEquals(140, engine.combatDurationPercent(game))
        assertEquals(28, engine.attackCountForCombatPower(game, 20))

        game.hero.stats.strength = 18L
        game.hero.stats.constitution = 13L
        setAllEquipmentPower(game, 1_000L)
        assertEquals(60, engine.combatDurationPercent(game))
        assertEquals(12, engine.attackCountForCombatPower(game, 20))
    }

    @Test
    fun `display combat power uses class weighted stats plus average equipment`() {
        val game = newGame(now = 0L)
        game.hero.level = 10L
        game.classGuidedLevelGrowths = 9L
        game.hero.stats = HeroStats(
            strength = 20L,
            constitution = 10L,
            dexterity = 18L,
            intelligence = 16L,
            wisdom = 14L,
            charisma = 8L,
            maxHealth = 1_000L,
            maxMana = 1_000L,
        )
        setAllEquipmentPower(game, 20L)

        val expectedStatPower = mapOf(
            HeroClass.WARRIOR to 47L,
            HeroClass.ROGUE to 50L,
            HeroClass.RANGER to 47L,
            HeroClass.MAGE to 43L,
            HeroClass.CLERIC to 42L,
            HeroClass.PALADIN to 46L,
        )

        expectedStatPower.forEach { (heroClass, statPower) ->
            game.hero.heroClass = heroClass
            assertEquals(statPower, engine.characterStatPower(game))
            assertEquals(statPower + 20L, engine.displayCombatPower(game))
        }
    }

    @Test
    fun `primary stats affect combat power more than secondary stats`() {
        val game = newGame(now = 0L)
        game.hero.level = 10L
        game.classGuidedLevelGrowths = 9L
        game.hero.heroClass = HeroClass.WARRIOR
        game.equipment.clear()
        game.hero.stats.strength = 18L
        game.hero.stats.constitution = 13L
        val baseline = engine.characterStatPower(game)

        game.hero.stats.strength += 1L
        val primaryIncrease = engine.characterStatPower(game) - baseline
        game.hero.stats.strength -= 1L
        game.hero.stats.constitution += 1L
        val secondaryIncrease = engine.characterStatPower(game) - baseline

        assertTrue(primaryIncrease > secondaryIncrease)
        assertTrue(secondaryIncrease > 0L)
    }

    @Test
    fun `soft normalization keeps ordinary stat and equipment shares comparable`() {
        val game = newGame(now = 0L)
        game.hero.level = 10L
        game.classGuidedLevelGrowths = 9L
        game.hero.heroClass = HeroClass.WARRIOR
        setAllEquipmentPower(game, 46L)

        game.hero.stats.strength = 1L
        game.hero.stats.constitution = 1L
        assertEquals(34L, engine.characterStatPower(game))

        game.hero.stats.strength = Long.MAX_VALUE
        game.hero.stats.constitution = Long.MAX_VALUE
        assertEquals(62L, engine.characterStatPower(game))
    }

    @Test
    fun `damaged equipment values cannot make equipment or total combat power negative`() {
        val game = newGame(now = 0L)
        game.equipment.forEach { it.power = -100L }

        assertEquals(0L, engine.averageEquipmentPower(game))
        assertEquals(engine.characterStatPower(game), engine.displayCombatPower(game))

        game.equipment.forEach { it.power = Long.MAX_VALUE }
        assertEquals(Long.MAX_VALUE, engine.averageEquipmentPower(game))
        assertEquals(Long.MAX_VALUE, engine.displayCombatPower(game))
    }

    @Test
    fun `schema twenty active combat is rebaselined to the ten second hunt`() {
        val game = newGame(now = 0L)
        game.schemaVersion = 20
        game.monster.expectedAttacks = 99
        game.monster.attacksCompleted = 50
        game.monster.currentEnergy = 500_000L
        game.actionStartedAt = 123L
        game.actionEndsAt = 456L

        engine.settleOfflineWithOfflineAdventure(game, now = 0L)

        assertEquals(24, game.schemaVersion)
        assertEquals(
            engine.attackCountForCombatPower(game, MonsterGrade.NORMAL.minAttacks),
            game.monster.expectedAttacks,
        )
        assertEquals(0, game.monster.attacksCompleted)
        assertEquals(SimpleGameEngine.MONSTER_ENERGY_SCALE, game.monster.currentEnergy)
        assertEquals(CombatPhase.REVEAL, game.combatPhase)
        assertEquals(0L, game.actionStartedAt)
        assertEquals(SimpleGameEngine.ENCOUNTER_REVEAL_MILLIS, game.actionEndsAt)
    }

    @Test
    fun `schema twenty town action keeps its timeline during hunt migration`() {
        val game = newGame(now = 0L)
        game.schemaVersion = 20
        game.adventurePhase = AdventurePhase.LOOTING
        game.actionStartedAt = 1_000L
        game.actionEndsAt = 4_000L
        val expectedAttacks = game.monster.expectedAttacks

        engine.settleOfflineWithOfflineAdventure(game, now = 0L)

        assertEquals(24, game.schemaVersion)
        assertEquals(AdventurePhase.LOOTING, game.adventurePhase)
        assertEquals(1_000L, game.actionStartedAt)
        assertEquals(4_000L, game.actionEndsAt)
        assertEquals(expectedAttacks, game.monster.expectedAttacks)
    }

    @Test
    fun `display combat power saturates instead of wrapping at unlimited stat values`() {
        val game = newGame(now = 0L)
        game.hero.level = Long.MAX_VALUE
        game.totalTales = Long.MAX_VALUE
        game.hero.heroClass = HeroClass.WARRIOR
        game.hero.stats.strength = Long.MAX_VALUE
        game.hero.stats.constitution = Long.MAX_VALUE
        game.equipment.clear()

        assertEquals(Long.MAX_VALUE, engine.characterStatPower(game))
        assertEquals(Long.MAX_VALUE, engine.displayCombatPower(game))
    }

    private fun settleUntilNextKill(game: SimpleGameState): SettlementDelta {
        val beforeKills = game.totalKills
        var delta = SettlementDelta(0L, 0L, 0L, 0L, 0L, 0L)
        var guard = 0
        while (game.totalKills == beforeKills && guard < 1_000) {
            delta = engine.settle(game, game.actionEndsAt)
            guard += 1
        }
        assertEquals(beforeKills + 1L, game.totalKills)
        return delta
    }

    private fun newGame(now: Long) = engine.newGame(
        name = "테스터",
        heroClass = HeroClass.WARRIOR,
        rolledStats = engine.rollStats(77L).stats,
        seed = 88L,
        now = now,
    )

    private fun newGameWithSeed(seed: Long, now: Long) = engine.newGame(
        name = "테스터",
        heroClass = HeroClass.WARRIOR,
        rolledStats = engine.rollStats(77L).stats,
        seed = seed,
        now = now,
    )

    private fun forceVictory(game: SimpleGameState) {
        game.adventurePhase = AdventurePhase.COMBAT
        game.monster.attacksCompleted = game.monster.expectedAttacks
        game.monster.currentEnergy = 0L
        game.combatPhase = CombatPhase.VICTORY
    }

    private fun setAllEquipmentPower(game: SimpleGameState, power: Long) {
        EquipmentSlot.entries.forEach { slot ->
            game.equipment.first { it.slot == slot }.power = power
        }
    }

    private class ReferenceRng(seed: Long) {
        var state = seed
            private set

        fun nextInt(bound: Int): Int {
            state = state * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
            return ((state ushr 1) % bound.toLong()).toInt()
        }

    }
}
