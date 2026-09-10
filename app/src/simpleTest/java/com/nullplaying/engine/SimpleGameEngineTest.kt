package com.nullplaying.engine

import com.nullplaying.model.CombatPhase
import com.nullplaying.model.AdventurePhase
import com.nullplaying.model.CompletedTaleRecord
import com.nullplaying.model.EquipmentSlot
import com.nullplaying.model.HeroClass
import com.nullplaying.model.HeroStats
import com.nullplaying.model.InventoryItem
import com.nullplaying.model.LearnedSkill
import com.nullplaying.model.MonsterGrade
import com.nullplaying.model.RecentAdventureEventType
import com.nullplaying.model.RecentAdventureEventMetadata
import com.nullplaying.model.SettlementDelta
import com.nullplaying.model.ShopEquipmentOffer
import com.nullplaying.model.SIMPLE_GAME_SCHEMA_VERSION
import com.nullplaying.model.SimpleGameState
import com.nullplaying.model.TaleKind
import com.nullplaying.model.TRUSTED_TIMELINE_LEGACY
import com.nullplaying.model.TRUSTED_TIMELINE_VERIFIED
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleGameEngineTest {
    private val engine = SimpleGameEngine()

    @Test
    fun `future timeline rebase preserves action progress without granting rewards`() {
        val game = engine.newGame(
            name = "시간 검사",
            heroClass = HeroClass.WARRIOR,
            rolledStats = engine.rollStats(77L).stats,
            seed = 88L,
            now = 1_000_000L,
        )
        game.actionStartedAt = 1_000_000L
        game.lastSettledAt = 1_002_000L
        game.actionEndsAt = 1_006_000L
        game.hero.experience = 123L
        game.hero.gold = 456L
        game.totalKills = 7L
        game.totalActs = 8L
        game.offlineAdventureMillis = 9_000L
        val rngBefore = game.rngState

        engine.rebaseTimelineWithoutProgress(game, now = 500_000L)

        assertEquals(498_000L, game.actionStartedAt)
        assertEquals(504_000L, game.actionEndsAt)
        assertEquals(500_000L, game.lastSettledAt)
        assertEquals(123L, game.hero.experience)
        assertEquals(456L, game.hero.gold)
        assertEquals(7L, game.totalKills)
        assertEquals(8L, game.totalActs)
        assertEquals(9_000L, game.offlineAdventureMillis)
        assertEquals(rngBefore, game.rngState)
    }

    @Test
    fun `future timeline rebase is safe for corrupt extreme timestamps`() {
        val game = engine.newGame(
            name = "극값 검사",
            heroClass = HeroClass.WARRIOR,
            rolledStats = engine.rollStats(77L).stats,
            seed = 88L,
            now = 0L,
        )
        game.actionStartedAt = Long.MIN_VALUE
        game.lastSettledAt = Long.MAX_VALUE
        game.actionEndsAt = Long.MAX_VALUE

        engine.rebaseTimelineWithoutProgress(game, now = 10L)

        assertEquals(Long.MIN_VALUE + 11L, game.actionStartedAt)
        assertEquals(10L, game.actionEndsAt)
        assertEquals(10L, game.lastSettledAt)
    }

    @Test
    fun `new hero sees an opening before the first encounter search`() {
        val game = engine.newGame(
            name = "새별",
            heroClass = HeroClass.RANGER,
            rolledStats = engine.rollStats(77L).stats,
            seed = 88L,
            now = 1_000L,
        )

        assertEquals(AdventurePhase.OPENING, game.adventurePhase)
        assertEquals(
            SimpleGameEngine.OPENING_PRESENTATION_MILLIS,
            game.actionEndsAt - game.actionStartedAt,
        )
        assertEquals(2_000L, SimpleGameEngine.OPENING_SLIDE_MILLIS)
        assertEquals(3, SimpleGameEngine.OPENING_SLIDE_COUNT)
        assertEquals(6_000L, SimpleGameEngine.OPENING_PRESENTATION_MILLIS)
        assertEquals(3, game.adventureTale.openingSlides.size)
        assertTrue(game.adventureTale.openingSlides.first().contains("새별"))
        assertTrue(game.lastResult.contains("새별"))
        assertTrue(game.monster.name.isBlank())

        engine.settle(game, game.actionEndsAt)

        assertEquals(AdventurePhase.COMBAT, game.adventurePhase)
        assertEquals(CombatPhase.REVEAL, game.combatPhase)
        assertTrue(game.monster.name.isNotBlank())
        assertEquals(
            StatBonusRules.encounterRevealMillis(game),
            game.actionEndsAt - game.actionStartedAt,
        )
        assertEquals(0L, game.actionSequence)
    }

    @Test
    fun `character roll derives class resources with dnd style modifiers`() {
        repeat(500) { index ->
            HeroClass.entries.forEach { heroClass ->
                val stats = engine.rollStats(index.toLong() + 1L, heroClass).stats
                stats.values().take(6).forEach { value ->
                    assertTrue(value in 3L..18L)
                }
                assertEquals(
                    engine.initialMaxHealth(heroClass, stats.constitution),
                    stats.maxHealth,
                )
                assertEquals(
                    engine.initialMaxMana(heroClass, stats.intelligence, stats.wisdom),
                    stats.maxMana,
                )
            }
        }
    }

    @Test
    fun `dnd ability modifier rounds negative odd scores down`() {
        val expected = mapOf(
            3L to -4L,
            8L to -1L,
            9L to -1L,
            10L to 0L,
            11L to 0L,
            12L to 1L,
            18L to 4L,
        )

        expected.forEach { (score, modifier) ->
            assertEquals(modifier, engine.dndAbilityModifier(score))
        }
    }

    @Test
    fun `class resource bases distinguish martial hybrid and caster roles`() {
        val stats = HeroStats(
            strength = 12L,
            constitution = 13L,
            dexterity = 12L,
            intelligence = 10L,
            wisdom = 8L,
            charisma = 12L,
            maxHealth = 0L,
            maxMana = 0L,
        )
        val expected = mapOf(
            HeroClass.WARRIOR to (11L to 3L),
            HeroClass.ROGUE to (9L to 5L),
            HeroClass.RANGER to (11L to 7L),
            HeroClass.MAGE to (7L to 9L),
            HeroClass.CLERIC to (9L to 9L),
            HeroClass.PALADIN to (11L to 7L),
        )

        expected.forEach { (heroClass, resources) ->
            val derived = engine.initialStatsForClass(stats, heroClass)
            assertEquals(resources.first, derived.maxHealth)
            assertEquals(resources.second, derived.maxMana)
            derived.values().take(6).forEachIndexed { index, value ->
                assertEquals(stats.values()[index], value)
            }
        }

        val minimum = stats.copy(constitution = 3L, intelligence = 3L, wisdom = 3L)
        HeroClass.entries.forEach { heroClass ->
            val derived = engine.initialStatsForClass(minimum, heroClass)
            assertTrue(derived.maxHealth >= 1L)
            assertTrue(derived.maxMana >= 1L)
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
            HeroClass.CLERIC to setOf(4, 5),
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
        assertEquals(1L, game.adventureTale.sequence)
        assertEquals(5, game.adventureTale.acts.size)
        assertEquals(1, game.completedTaleHistory.size)
        assertEquals(5, game.completedTaleHistory.single().actMemories.size)
        assertEquals("부러진 성문의 파수꾼", game.completedTaleHistory.single().title)
        assertEquals(TaleKind.PROLOGUE, game.completedTaleHistory.single().kind)
        assertEquals("돌아오지 않은 순찰대", game.adventureTale.title)
        assertEquals(
            1,
            delta.recentEvents.count { it.type == RecentAdventureEventType.TALE_COMPLETED },
        )
        assertFalse(delta.recentEvents.any { it.type == RecentAdventureEventType.QUEST_COMPLETED })
    }

    @Test
    fun `heroes start with one skill then learn every five levels and stop at twenty`() {
        val game = newGame(now = 0L)
        for (level in 1L..104L) {
            game.hero.level = level
            game.hero.experience = engine.experienceRequired(level) - 1L
            settleUntilNextKill(game)
        }

        assertEquals(20, game.skills.size)
        assertEquals(listOf(1L) + (5L..95L step 5L).toList(), game.skills.map { it.acquiredAtLevel })
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
    fun `stat bonuses bring the reference warrior to level one hundred near day one hundred sixty two`() {
        val game = newGame(now = 0L)
        val dayMillis = 24L * 60L * 60L * 1_000L

        engine.settleOffline(game, 161L * dayMillis)
        assertTrue("day 161 level=${game.hero.level}", game.hero.level < 100L)

        engine.settleOffline(game, 162L * dayMillis)
        assertEquals("day 162 level", 100L, game.hero.level)
        assertTrue(game.skills.all { it.level <= LearnedSkill.MAX_LEVEL })
        assertEquals(LearnedSkill.MAX_LEVEL, game.skills.first().level)
        val firstTierAverage = SkillCatalog.damagePercentRange(1).average() +
            game.skills.first().damageBonusPercent
        val lastTierAverage = SkillCatalog.damagePercentRange(20).average() +
            game.skills.last().damageBonusPercent
        assertTrue("tier 1=$firstTierAverage tier 20=$lastTierAverage", lastTierAverage > firstTierAverage)
    }

    @Test
    fun `schema nineteen preserves active act progress ratio under the longer targets`() {
        val game = newGame(now = 0L)
        game.useFirstMainTale()
        game.schemaVersion = 19
        game.adventureTale.acts.indices.forEach { index ->
            val old = game.adventureTale.acts[index]
            game.adventureTale.acts[index] = old.copy(
                progress = if (index == 0) 10L else 0L,
                target = 20L + index * 5L,
            )
        }

        engine.settleOfflineWithOfflineAdventure(game, now = 0L)

        assertEquals(SIMPLE_GAME_SCHEMA_VERSION, game.schemaVersion)
        assertEquals(listOf(150L, 250L, 300L, 200L, 350L), game.adventureTale.acts.map { it.target })
        assertEquals(75L, game.adventureTale.acts.first().progress)
        assertEquals(0L, game.adventureTale.acts.drop(1).sumOf { it.progress })
    }

    @Test
    fun `schema twenty preserves level experience and act progress ratios`() {
        val game = newGame(now = 0L)
        game.useFirstMainTale()
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

        assertEquals(SIMPLE_GAME_SCHEMA_VERSION, game.schemaVersion)
        assertEquals(812_592L, game.hero.experience)
        assertEquals(75L, game.adventureTale.acts.first().progress)
        assertEquals(150L, game.adventureTale.acts.first().target)
        assertEquals(46L, game.adventureTale.acts.first().rewardExperience)
    }

    @Test
    fun `schema twenty one migrates oversized act targets without scaling rewards twice`() {
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

        assertEquals(SIMPLE_GAME_SCHEMA_VERSION, game.schemaVersion)
        assertEquals(354L, game.adventureTale.acts.first().progress)
        assertEquals(708L, game.adventureTale.acts.first().target)
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

        assertEquals(SIMPLE_GAME_SCHEMA_VERSION, game.schemaVersion)
        assertEquals("ash_border.c13", game.adventureTale.definitionId)
        assertEquals(13L, game.adventureTale.sequence)
        assertEquals(TaleKind.MAIN, game.adventureTale.kind)
        assertEquals(2, game.adventureTale.volumeNumber)
        assertEquals("유리 숲의 백야", game.adventureTale.volumeTitle)
        assertEquals(17L, game.totalTales)
        assertEquals(
            listOf(720L, 1_200L, 1_440L, 960L, 1_680L),
            game.adventureTale.acts.map { it.target },
        )
        assertTrue(game.monster.catalogId.startsWith("ash_border.c13."))
    }

    @Test
    fun `schema thirty six preserves active main act progress under the chapter rhythm`() {
        val game = newGame(now = 0L)
        game.schemaVersion = 36
        game.adventureTale = AdventureTaleCatalog.instantiate(
            definition = AdventureTaleCatalog.mainTales[11],
            sequence = 12L,
            heroName = game.hero.name,
            heroLevel = game.hero.level,
            variant = game.adventureTale.variant,
        )
        game.adventureTale.currentActIndex = 2
        game.adventureTale.acts.indices.forEach { index ->
            val act = game.adventureTale.acts[index]
            game.adventureTale.acts[index] = act.copy(
                progress = when {
                    index < 2 -> 1_000L
                    index == 2 -> 500L
                    else -> 0L
                },
                target = 1_000L,
                completed = index < 2,
            )
        }

        engine.settleOfflineWithOfflineAdventure(game, now = 0L)

        assertEquals(SIMPLE_GAME_SCHEMA_VERSION, game.schemaVersion)
        assertEquals(
            listOf(708L, 1_180L, 1_416L, 944L, 1_652L),
            game.adventureTale.acts.map { it.target },
        )
        assertEquals(
            listOf(708L, 1_180L, 708L, 0L, 0L),
            game.adventureTale.acts.map { it.progress },
        )
        assertEquals(listOf(true, true, false, false, false), game.adventureTale.acts.map { it.completed })
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
        assertTrue(presentationSeed != game.presentationRngState)
        assertTrue(game.skills.sumOf { it.usageCount } > 0L)
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
        assertEquals(online.presentationRngState, offline.presentationRngState)
        assertEquals(online.taleRngState, offline.taleRngState)
        assertEquals(online.recentMonsterNames, offline.recentMonsterNames)
        assertEquals(online.recentItemNames, offline.recentItemNames)
        assertEquals(online.consecutiveBasicAttacks, offline.consecutiveBasicAttacks)
        assertEquals(0L, offline.lastDamage)
        assertEquals("", offline.lastAttackName)
    }

    @Test
    fun `chunked offline settlement matches one continuous offline replay`() {
        val continuous = newGame(now = 0L)
        val chunked = newGame(now = 0L)
        val chunkMillis = 6L * 60L * 60L * 1_000L

        engine.settleOffline(continuous, 24L * 60L * 60L * 1_000L)
        repeat(4) { index ->
            engine.settleOffline(chunked, (index + 1L) * chunkMillis)
        }

        assertEquals(continuous, chunked)
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

        repeat(AdventureTaleCatalog.MAIN_TALE_COUNT + 1) {
            val currentTitle = game.adventureTale.title
            repeat(SimpleGameEngine.ACTS_PER_TALE) {
                val act = game.adventureTale.activeAct()
                act.progress = act.target - 1L
                game.monster.grade = MonsterGrade.BOSS
                settleUntilNextKill(game)
            }
            completedTitles += currentTitle
        }

        assertEquals(
            listOf(StarterPrologueCatalog.forClass(HeroClass.WARRIOR).title) +
                AdventureTaleCatalog.mainTales.map { it.title },
            completedTitles,
        )
        assertEquals(25L, game.totalTales)
        assertEquals(125L, game.totalActs)
        assertEquals(25, game.completedTaleHistory.size)
        assertEquals(TaleKind.PROLOGUE, game.completedTaleHistory.first().kind)
        assertTrue(game.completedTaleHistory.drop(1).all { it.kind == TaleKind.MAIN })
        assertEquals(TaleKind.EPILOGUE, game.adventureTale.kind)
        assertEquals(25L, game.adventureTale.sequence)
    }

    @Test
    fun `main tale rhythm reaches the epilogue in the intended effective progression window`() {
        val game = newGame(now = 0L)
        val stepMillis = 6L * 60L * 60L * 1_000L
        val maximumMillis = 40L * 24L * 60L * 60L * 1_000L
        var now = 0L

        while (game.adventureTale.kind != TaleKind.EPILOGUE && now < maximumMillis) {
            now += stepMillis
            engine.settleOffline(game, now)
        }

        assertEquals(TaleKind.EPILOGUE, game.adventureTale.kind)
        val elapsedHours = now / (60L * 60L * 1_000L)
        assertTrue("elapsedHours=$elapsedHours", elapsedHours in (32L * 24L)..(35L * 24L))
        assertTrue("level=${game.hero.level}", game.hero.level in 42L..44L)
        println(
            "mainTaleRhythm elapsedHours=$elapsedHours " +
                "level=${game.hero.level} kills=${game.totalKills}",
        )
    }

    @Test
    fun `one foreground minute banks one twentieth of character capacity and twenty minutes fills it`() {
        val game = newGame(now = 0L)
        game.offlineAdventureMillis = 0L

        engine.advanceOfflineAdventureForeground(
            game,
            60L * 1_000L,
        )
        assertEquals(
            engine.offlineAdventureCapacityMillis(game) / 20L,
            game.offlineAdventureMillis,
        )
        assertEquals(1f / 20f, engine.offlineAdventureFraction(game), 0.000001f)

        engine.advanceOfflineAdventureForeground(
            game,
            SimpleGameEngine.OFFLINE_ADVENTURE_CHARGE_MILLIS - 60L * 1_000L,
        )
        assertEquals(
            engine.offlineAdventureCapacityMillis(game),
            game.offlineAdventureMillis,
        )
        assertTrue(engine.isOfflineAdventureFull(game))

        engine.advanceOfflineAdventureForeground(game, 60_000L)
        assertEquals(engine.offlineAdventureCapacityMillis(game), game.offlineAdventureMillis)
    }

    @Test
    fun `existing earned balances survive a lower character capacity`() {
        val game = newGame(now = 0L)
        game.offlineAdventureMillis = 24L * 60L * 60L * 1_000L

        engine.settleOfflineWithOfflineAdventure(game, now = 0L)

        assertEquals(24L * 60L * 60L * 1_000L, game.offlineAdventureMillis)
        assertTrue(engine.isOfflineAdventureFull(game))
    }

    @Test
    fun `earned reward fills the offline bank once and never stacks`() {
        val game = newGame(now = 0L)
        game.offlineAdventureMillis = engine.offlineAdventureCapacityMillis(game) / 2L

        assertTrue(engine.grantRewardedOfflineAdventure(game, "reward-1"))
        assertEquals(engine.offlineAdventureCapacityMillis(game), game.offlineAdventureMillis)
        assertTrue(!engine.grantRewardedOfflineAdventure(game, "reward-1"))
        assertTrue(!engine.grantRewardedOfflineAdventure(game, "reward-2"))
        assertEquals(engine.offlineAdventureCapacityMillis(game), game.offlineAdventureMillis)
    }

    @Test
    fun `delayed earned callback stays idempotent after a newer reward was applied`() {
        val game = newGame(now = 0L)
        val halfBank = engine.offlineAdventureCapacityMillis(game) / 2L
        game.offlineAdventureMillis = halfBank
        assertTrue(engine.grantRewardedOfflineAdventure(game, "reward-1"))
        game.offlineAdventureMillis = halfBank
        assertTrue(engine.grantRewardedOfflineAdventure(game, "reward-2"))
        game.offlineAdventureMillis = halfBank

        assertFalse(engine.grantRewardedOfflineAdventure(game, "reward-1"))
        assertEquals(halfBank, game.offlineAdventureMillis)
        assertEquals(listOf("reward-1", "reward-2"), game.rewardedOfflineRequestIds)
    }

    @Test
    fun `older schema forty six payload without trusted timeline and reward ledger stays safe`() {
        val codec = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
        val original = newGame(now = 0L).apply {
            schemaVersion = SIMPLE_GAME_SCHEMA_VERSION
            trustedTimelineVersion = TRUSTED_TIMELINE_VERIFIED
            hero.gold = 12_345L
            hero.experience = 6_789L
            totalKills = 23L
            totalActs = 17L
            totalItemsFound = 11L
            offlineAdventureMillis = engine.offlineAdventureCapacityMillis(this) / 2L
            lastRewardRequestId = "legacy-reward-request"
            rewardedOfflineRequestIds = listOf("new-ledger-only-request")
        }
        val currentPayload = codec.parseToJsonElement(codec.encodeToString(original)).jsonObject
        val olderSchemaFortySixPayload = JsonObject(
            currentPayload.filterKeys { field ->
                field != "trustedTimelineVersion" && field != "rewardedOfflineRequestIds"
            },
        ).toString()

        assertFalse(olderSchemaFortySixPayload.contains("\"trustedTimelineVersion\""))
        assertFalse(olderSchemaFortySixPayload.contains("\"rewardedOfflineRequestIds\""))
        val restored = codec.decodeFromString<SimpleGameState>(olderSchemaFortySixPayload)

        assertEquals(SIMPLE_GAME_SCHEMA_VERSION, restored.schemaVersion)
        assertEquals(TRUSTED_TIMELINE_LEGACY, restored.trustedTimelineVersion)
        assertTrue(restored.rewardedOfflineRequestIds.isEmpty())
        assertEquals("legacy-reward-request", restored.lastRewardRequestId)
        assertEquals(12_345L, restored.hero.gold)
        assertEquals(6_789L, restored.hero.experience)
        assertEquals(23L, restored.totalKills)
        assertEquals(17L, restored.totalActs)
        assertEquals(11L, restored.totalItemsFound)
        assertEquals(original.offlineAdventureMillis, restored.offlineAdventureMillis)
        assertEquals(original.lastSettledAt, restored.lastSettledAt)

        val bankBeforeDuplicate = restored.offlineAdventureMillis
        assertFalse(
            engine.grantRewardedOfflineAdventure(restored, "legacy-reward-request"),
        )
        assertEquals(bankBeforeDuplicate, restored.offlineAdventureMillis)
        assertTrue(engine.grantRewardedOfflineAdventure(restored, "fresh-reward-request"))
        assertEquals(
            engine.offlineAdventureCapacityMillis(restored),
            restored.offlineAdventureMillis,
        )
        assertEquals(listOf("fresh-reward-request"), restored.rewardedOfflineRequestIds)
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
        assertEquals(SIMPLE_GAME_SCHEMA_VERSION, migrated.schemaVersion)
        assertEquals(engine.offlineAdventureCapacityMillis(migrated), migrated.offlineAdventureMillis)
    }

    @Test
    fun `pre-bank legacy migration caps an implausibly large epoch gap`() {
        val migrated = newGame(now = 0L)
        val reference = newGame(now = 0L)
        migrated.schemaVersion = 7
        reference.schemaVersion = 7
        migrated.offlineAdventureMillis = 0L
        val capacity = engine.offlineAdventureCapacityMillis(migrated)
        val implausibleNow = 365L * 24L * 60L * 60L * 1_000L

        val expected = engine.settleOffline(reference, capacity)
        val actual = engine.settleOfflineWithOfflineAdventure(
            migrated,
            implausibleNow,
            LegacyAutoHuntSnapshot(schemaVersion = 7, chargeMillis = 0L, activeUntil = 0L),
        )

        assertEquals(expected.defeatedMonsters, actual.defeatedMonsters)
        assertEquals(expected.elapsedMillis, actual.elapsedMillis)
        assertEquals(implausibleNow, migrated.lastSettledAt)
        assertEquals(engine.offlineAdventureCapacityMillis(migrated), migrated.offlineAdventureMillis)
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
        assertEquals(SIMPLE_GAME_SCHEMA_VERSION, migrated.schemaVersion)
        assertEquals(activeUntil - now, migrated.offlineAdventureMillis)
    }

    @Test
    fun `schema eight partial charge converts proportionally into banked time`() {
        val migrated = newGame(now = 0L)
        migrated.schemaVersion = 8
        migrated.offlineAdventureMillis = 0L
        val halfCharge = 6L * 60L * 1_000L // Original schema-eight 12-minute full charge.

        engine.settleOfflineWithOfflineAdventure(
            migrated,
            now = 0L,
            legacy = LegacyAutoHuntSnapshot(8, chargeMillis = halfCharge, activeUntil = 0L),
        )

        assertEquals(SIMPLE_GAME_SCHEMA_VERSION, migrated.schemaVersion)
        assertEquals(
            6L * 60L * 60L * 1_000L, // Preserve the original schema-eight 12h / 12min rate.
            migrated.offlineAdventureMillis,
        )
    }

    @Test
    fun `recent monsters and items do not repeat`() {
        val game = newGame(now = 0L)
        repeat(240) {
            settleUntilNextKill(game)
            assertTrue(game.monster.maxEnergy > 0L)
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
        val maximumEnergy = game.monster.maxEnergy

        assertEquals(1f, engine.monsterEnergyFraction(game))
        val firstBoundary = game.actionEndsAt
        engine.settle(game, firstBoundary)

        assertEquals(1L, game.actionSequence)
        assertEquals(1, game.monster.attacksCompleted)
        assertTrue(game.monster.currentEnergy < maximumEnergy)
        assertTrue(game.lastDamage > 0L)
        assertEquals(maximumEnergy, game.lastMonsterEnergyBeforeAttack)
        assertEquals(
            (maximumEnergy - game.lastDamage).coerceAtLeast(0L),
            game.monster.currentEnergy,
        )
        val afterFirstAttack = game.monster.currentEnergy

        engine.settle(game, firstBoundary + 500L)
        assertEquals(afterFirstAttack, game.monster.currentEnergy)

        while (game.combatPhase != CombatPhase.VICTORY) {
            engine.settle(game, game.actionEndsAt)
        }
        assertTrue(
            "target=$expectedAttacks actual=${game.monster.attacksCompleted}",
            game.monster.attacksCompleted in
                (expectedAttacks - 2).coerceAtLeast(1)..(expectedAttacks + 2),
        )
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
        assertTrue(game.monster.currentEnergy > 0L)
        assertEquals(game.monster.maxEnergy, game.monster.currentEnergy)
    }

    @Test
    fun `skill damage is subtracted from monster energy exactly once`() {
        val game = newGame(now = 0L)
        game.consecutiveBasicAttacks = SimpleGameEngine.MAX_CONSECUTIVE_BASIC_ATTACKS
        val energyBefore = game.monster.currentEnergy
        val usageBefore = game.skills.single().usageCount

        engine.settle(game, game.actionEndsAt)

        assertTrue(game.lastAttackWasSkill)
        assertEquals(usageBefore + 1L, game.skills.single().usageCount)
        assertEquals(energyBefore, game.lastMonsterEnergyBeforeAttack)
        assertEquals(
            (energyBefore - game.lastDamage).coerceAtLeast(0L),
            game.monster.currentEnergy,
        )
    }

    @Test
    fun `basic attacks have no technique name and skills keep their attack name`() {
        val game = newGame(now = 0L)
        val definition = SkillCatalog.select(game.skillCatalogSeed, game.hero.heroClass, 1)
        assertEquals(listOf(definition.catalogId), game.skills.map { it.catalogId })
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
    fun `basic and skill damage coefficients use the requested ranges`() {
        assertEquals(40, SimpleGameEngine.BASIC_ATTACK_MIN_PERCENT)
        assertEquals(60, SimpleGameEngine.BASIC_ATTACK_MAX_PERCENT)
        assertEquals(90..100, SkillCatalog.damagePercentRange(1))
        assertEquals(270..280, SkillCatalog.damagePercentRange(10))
        assertEquals(470..480, SkillCatalog.damagePercentRange(20))
        assertEquals(
            (1..20).map { 90 + (it - 1) * 20 },
            (1..20).map { SkillCatalog.damagePercentRange(it).first },
        )
    }

    @Test
    fun `skill mastery distributes fifty damage percent across one hundred levels`() {
        val game = newGame(now = 0L)
        game.skills[0] = game.skills.single().copy(usageCount = 99L)

        settleUntilSkillAttack(game)

        val levelTwo = game.skills.first()
        assertEquals(100L, levelTwo.usageCount)
        assertEquals(2L, levelTwo.level)
        assertEquals(0L, levelTwo.damageBonusPercent)
        assertEquals("${levelTwo.name} LV.2", levelTwo.displayName)

        game.skills[0] = levelTwo.copy(usageCount = 199L)
        settleUntilSkillAttack(game)

        val levelThree = game.skills.first()
        assertEquals(200L, levelThree.usageCount)
        assertEquals(3L, levelThree.level)
        assertEquals(1L, levelThree.damageBonusPercent)
        assertEquals("${levelThree.name} LV.3", levelThree.displayName)
    }

    @Test
    fun `online and offline settlement emit the same individual mastery event`() {
        val online = newGame(now = 0L).apply {
            skills[0] = skills.single().copy(usageCount = 99L)
            consecutiveBasicAttacks = Int.MAX_VALUE
        }
        val offline = Json.decodeFromString<SimpleGameState>(Json.encodeToString(online))

        val onlineDelta = engine.settle(online, online.actionEndsAt)
        val offlineDelta = engine.settleOffline(offline, offline.actionEndsAt)

        assertEquals(onlineDelta.recentEvents, offlineDelta.recentEvents)
        val event = onlineDelta.recentEvents.single()
        assertEquals(RecentAdventureEventType.SKILL_MASTERY, event.type)
        assertEquals(1L, event.previousValue)
        assertEquals(2L, event.currentValue)
        assertEquals(online.skills.single().name, event.subjectName)
    }

    @Test
    fun `skill mastery stops at level one hundred with a full experience bar`() {
        val game = newGame(now = 0L)
        game.skills[0] = game.skills.single().copy(
            usageCount = LearnedSkill.MAX_USAGE_COUNT - 1L,
        )

        settleUntilSkillAttack(game)

        val maximum = game.skills.single()
        assertEquals(LearnedSkill.MAX_USAGE_COUNT, maximum.usageCount)
        assertEquals(LearnedSkill.MAX_LEVEL, maximum.level)
        assertEquals(LearnedSkill.MAX_DAMAGE_BONUS_PERCENT, maximum.damageBonusPercent)
        assertEquals(LearnedSkill.USES_PER_LEVEL, maximum.masteryExperience)
        assertEquals(1f, maximum.masteryProgress)
        assertTrue(maximum.isMaxLevel)

        settleUntilSkillAttack(game)

        assertEquals(LearnedSkill.MAX_USAGE_COUNT, game.skills.single().usageCount)
        assertEquals(LearnedSkill.MAX_LEVEL, game.skills.single().level)
    }

    @Test
    fun `oversized saved mastery is bounded to level one hundred`() {
        val game = newGame(now = 0L)
        game.skills[0] = game.skills.single().copy(usageCount = Long.MAX_VALUE)
        val learned = game.skills.single()

        assertEquals(LearnedSkill.MAX_USAGE_COUNT, learned.boundedUsageCount)
        assertEquals(LearnedSkill.MAX_LEVEL, learned.level)
        assertEquals(LearnedSkill.MAX_DAMAGE_BONUS_PERCENT, learned.damageBonusPercent)
        assertEquals(LearnedSkill.MAX_USAGE_COUNT, learned.nextUsageCount)
        assertEquals(1f, learned.masteryProgress)

        engine.settle(game, now = 1L)

        assertEquals(LearnedSkill.MAX_USAGE_COUNT, game.skills.single().usageCount)
    }

    @Test
    fun `legacy learned skill json starts mastery at level one`() {
        val learned = Json.decodeFromString<LearnedSkill>(
            """{"id":1,"name":"칼날 베기","acquiredAtLevel":1,"description":"설명","catalogId":"warrior_t01_c01"}""",
        )

        assertEquals(0L, learned.usageCount)
        assertEquals(1L, learned.level)
        assertEquals("칼날 베기 LV.1", learned.displayName)
    }

    @Test
    fun `legacy learned skills migrate to the direct attack catalog without progress loss`() {
        val game = newGame(now = 0L)
        game.schemaVersion = 6
        game.hero.level = 95L
        game.skills.clear()
        game.skills += LearnedSkill(2, "전투 감각", 10L, "예전 설명")
        game.skills += LearnedSkill(19, "세계수의 가호", 95L, "예전 설명")
        val actionEndsAt = game.actionEndsAt

        engine.settle(game, 1L)

        assertEquals(SIMPLE_GAME_SCHEMA_VERSION, game.schemaVersion)
        assertEquals((1..20).toList(), game.skills.map { it.id })
        assertEquals(listOf(1L) + (5L..95L step 5L).toList(), game.skills.map { it.acquiredAtLevel })
        assertTrue(game.skills.all { it.catalogId.isNotBlank() })
        assertTrue(game.skills.all { SkillCatalog.find(it.catalogId)?.name == it.name })
        assertTrue(game.skills.all { "적" in it.description })
        assertEquals(actionEndsAt, game.actionEndsAt)
    }

    @Test
    fun `schema twenty six maps a removed candidate to the fixed signature skill at the same tier`() {
        val game = newGame(now = 0L)
        val definition = SkillCatalog.find("warrior_t20_c01")!!
        val catalogSeed = game.skillCatalogSeed
        game.schemaVersion = 26
        game.hero.level = 95L
        game.skills.clear()
        game.skills += LearnedSkill(
            id = 20,
            name = "창세 지진",
            acquiredAtLevel = 100L,
            description = "예전 설명",
            catalogId = "warrior_t20_c04",
            usageCount = 299L,
        )
        game.lastAttackWasSkill = true
        game.lastSkillCatalogId = "warrior_t20_c04"
        game.lastAttackName = "창세 지진"
        game.lastResult = "창세 지진 · 777 피해"

        engine.settle(game, 1L)

        assertEquals(SIMPLE_GAME_SCHEMA_VERSION, game.schemaVersion)
        assertEquals(catalogSeed, game.skillCatalogSeed)
        assertEquals(20, game.skills.size)
        assertEquals(definition.catalogId, game.skills.last().catalogId)
        assertEquals("최후의 일격", game.skills.last().name)
        assertEquals(definition.description, game.skills.last().description)
        assertEquals(299L, game.skills.last().usageCount)
        assertEquals(3L, game.skills.last().level)
        assertEquals("", game.lastSkillCatalogId)
        assertEquals("", game.lastAttackName)
        assertEquals(0L, game.lastMonsterEnergyBeforeAttack)
        assertEquals("최후의 일격 · 777 피해", game.lastResult)
    }

    @Test
    fun `the class catalogs contain one hundred twenty unique signature attacks`() {
        assertEquals(120, SkillCatalog.all.size)
        assertEquals(120, SkillCatalog.all.map { it.catalogId }.distinct().size)
        HeroClass.entries.forEach { heroClass ->
            val skills = SkillCatalog.forClass(heroClass)
            assertEquals(20, skills.size)
            assertEquals(20, skills.map { it.name }.distinct().size)
            assertEquals(listOf(1) + (5..95 step 5).toList(), skills.map { it.unlockLevel })
            assertTrue(skills.all { "적" in it.description })
        }
    }

    @Test
    fun `every class starts at level one with its first signature skill`() {
        HeroClass.entries.forEachIndexed { index, heroClass ->
            val game = engine.newGame(
                name = "${heroClass.name} 테스터",
                heroClass = heroClass,
                rolledStats = engine.rollStats(700L + index).stats,
                seed = 800L + index,
                now = 0L,
            )
            val expected = SkillCatalog.forClass(heroClass).first()

            assertEquals(1L, game.hero.level)
            assertEquals(1, game.skills.size)
            assertEquals(expected.catalogId, game.skills.single().catalogId)
            assertEquals(expected.name, game.skills.single().name)
            assertEquals(1L, game.skills.single().acquiredAtLevel)
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

        assertEquals(20, warrior.size)
        assertEquals(20, warrior.map { it.name }.distinct().size)
        assertTrue(warrior.none { it.name in otherClassNames })
        assertTrue(warrior.none { definition -> spellWords.any(definition.name::contains) })
        assertEquals(
            listOf(
                "칼날 베기", "강철 베기", "파쇄격", "대지 가르기", "십자 참격",
                "폭풍 베기", "철갑 돌진", "전장의 돌격", "회오리 참격", "대지 분쇄",
                "폭풍검", "섬광 일섬", "무영 연참", "용살검", "멸천 일섬", "무극일섬",
                "파멸의 검", "천지 가르기", "천하대양단", "최후의 일격",
            ),
            warrior.map { it.name },
        )
        assertEquals(
            (1..20).map { SkillCatalog.damagePercentRange(it).first },
            warrior.map { it.damagePercentMin },
        )
        assertEquals(
            (1..20).map { SkillCatalog.damagePercentRange(it).last },
            warrior.map { it.damagePercentMax },
        )
        warrior.forEach { definition ->
            assertEquals(
                listOf(SkillElement.PHYSICAL, SkillElement.EARTH, SkillElement.PHYSICAL, SkillElement.EARTH, SkillElement.PHYSICAL)[definition.candidate],
                definition.element,
            )
        }
    }

    @Test
    fun `every class always learns its fixed twenty signature skills`() {
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
        assertEquals(listOf(10, 20, 30), MonsterGrade.entries.map { it.minAttacks })
        assertEquals(listOf(10, 20, 30), MonsterGrade.entries.map { it.maxAttacks })
        val normal = engine.expectedCombatDurationRangeMillis(MonsterGrade.NORMAL)
        val elite = engine.expectedCombatDurationRangeMillis(MonsterGrade.ELITE)
        val boss = engine.expectedCombatDurationRangeMillis(MonsterGrade.BOSS)

        assertTrue(normal.last < elite.first)
        assertTrue(elite.last < boss.first)
        assertEquals(21_200L, normal.first)
        assertEquals(21_200L, normal.last)
        assertEquals(35_200L, elite.first)
        assertEquals(35_200L, elite.last)
        assertEquals(49_200L, boss.first)
        assertEquals(49_200L, boss.last)
        assertEquals(
            14_200L,
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
        assertEmptyShopThenDeparting(game)
        assertEquals(SimpleGameEngine.DEPART_TO_FIELDS_MILLIS, game.actionEndsAt - game.actionStartedAt)

        engine.settle(game, game.actionEndsAt)
        assertEquals(AdventurePhase.COMBAT, game.adventurePhase)
        assertEquals(CombatPhase.REVEAL, game.combatPhase)
        assertTrue(game.monster.currentEnergy > 0L)
        assertEquals(game.monster.maxEnergy, game.monster.currentEnergy)
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
        assertEquals(StatBonusRules.encounterRevealMillis(game), game.actionEndsAt - game.actionStartedAt)
        assertTrue(game.monster.currentEnergy > 0L)
        assertEquals(game.monster.maxEnergy, game.monster.currentEnergy)
    }

    @Test
    fun `each victory grants exactly one item even when a tale act completes`() {
        val game = newGame(now = 0L)
        val act = game.adventureTale.activeAct()
        val expectedQuestExperience = act.rewardExperience
        val expectedQuestGold = act.rewardGold
        act.progress = act.target - 1L
        game.monster.grade = MonsterGrade.BOSS
        game.monster.isFinalBoss = true
        val itemsBefore = game.totalItemsFound
        val bagBefore = game.inventory.size
        forceVictory(game)

        val delta = engine.settle(game, game.actionEndsAt)

        assertEquals(itemsBefore + 1L, game.totalItemsFound)
        assertEquals(bagBefore + 1, game.inventory.size)
        assertEquals(AdventurePhase.LOOTING, game.adventurePhase)
        assertTrue(game.lastLootName.isNotBlank())
        assertTrue(game.lastLootRarity.isNotBlank())
        assertEquals("장비", game.lastLootKind)
        assertTrue(game.lastLootEquipmentSlot != null)
        assertTrue(game.lastLootEquipmentPower != null)
        assertEquals(1L, game.totalActs)
        val questEvent = delta.recentEvents.single { it.type == RecentAdventureEventType.QUEST_COMPLETED }
        assertEquals(expectedQuestExperience, questEvent.previousValue)
        assertEquals(expectedQuestGold, questEvent.currentValue)
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
        assertEmptyShopThenDeparting(game)
    }

    @Test
    fun `selling shows each sold item and positive amount before the next town action`() {
        val game = newGame(now = 0L)
        game.inventory += InventoryItem(
            id = 1L,
            name = "황혼의 결정 표본",
            rarity = "희귀",
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
        assertEquals("희귀", game.lastTownItemRarity)
        assertTrue(game.lastTownGold > 0L)
        assertEquals(goldBefore + game.lastTownGold, game.hero.gold)
        assertTrue(game.lastResult.contains("+${game.lastTownGold}G"))
        assertEquals(SimpleGameEngine.SELL_ITEM_MILLIS, game.actionEndsAt - game.actionStartedAt)

        engine.settle(game, game.actionEndsAt)
        assertTrue(game.adventurePhase != AdventurePhase.SELLING)
    }

    @Test
    fun `sale value rises at every acquisition level in ten gold units`() {
        fun item(rarity: String, foundAtLevel: Long) = InventoryItem(
            id = foundAtLevel,
            name = "검증 전리품",
            rarity = rarity,
            kind = "장비",
            foundAtLevel = foundAtLevel,
        )

        assertEquals(10L, engine.saleValueForTest(item("일반", 1L)))
        assertEquals(20L, engine.saleValueForTest(item("일반", 2L)))
        assertEquals(100L, engine.saleValueForTest(item("일반", 10L)))
        assertEquals(300L, engine.saleValueForTest(item("희귀", 10L)))
        assertEquals(6_000L, engine.saleValueForTest(item("신화", 100L)))
        assertEquals(Long.MAX_VALUE, engine.saleValueForTest(item("신화", Long.MAX_VALUE)))
        (1L..100L).forEach { level ->
            assertEquals(level * 10L, engine.saleValueForTest(item("일반", level)))
        }
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

        val delta = engine.settle(game, game.actionEndsAt)

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
        assertTrue(delta.recentEvents.any { it.type == RecentAdventureEventType.EQUIPMENT_CHANGED })

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

        assertEquals(SIMPLE_GAME_SCHEMA_VERSION, game.schemaVersion)
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
    fun `new games use the current schema with the class prologue tale`() {
        val game = newGame(now = 0L)

        assertEquals(SIMPLE_GAME_SCHEMA_VERSION, game.schemaVersion)
        assertTrue(" · " !in game.monster.name)
        assertEquals("첫 발걸음", game.adventureTale.volumeTitle)
        assertEquals(0, game.adventureTale.chapterNumber)
        assertEquals(TaleKind.PROLOGUE, game.adventureTale.kind)
        assertEquals("부러진 성문의 파수꾼", game.adventureTale.title)
        assertEquals(listOf(7L, 8L, 9L, 10L, 12L), game.adventureTale.acts.map { it.target })
        assertEquals(SimpleGameEngine.ACTS_PER_TALE, game.adventureTale.acts.size)
        assertTrue(game.adventureTale.acts.all { it.progress == 0L && !it.completed })
    }

    @Test
    fun `inventory capacity keeps strength meaningful below a two to one same level spread`() {
        val game = newGame(now = 0L)

        game.hero.level = 1L
        game.hero.stats.strength = 3L
        assertEquals(16L, game.inventoryCapacity())
        game.hero.stats.strength = 18L
        assertEquals(24L, game.inventoryCapacity())
        game.hero.stats.strength = Long.MAX_VALUE
        assertEquals(29L, game.inventoryCapacity())

        game.hero.level = 100L
        game.hero.stats.strength = 0L
        assertEquals(74L, game.inventoryCapacity())
        game.hero.stats.strength = 50L
        assertEquals(99L, game.inventoryCapacity())
        game.hero.stats.strength = 172L
        assertEquals(147L, game.inventoryCapacity())
        game.hero.stats.strength = Long.MAX_VALUE
        assertEquals(147L, game.inventoryCapacity())

        listOf(1L, 10L, 50L, 100L, 1_000L).forEach { level ->
            game.hero.level = level
            game.hero.stats.strength = 0L
            val minimum = game.inventoryCapacity()
            game.hero.stats.strength = Long.MAX_VALUE
            val maximum = game.inventoryCapacity()
            assertTrue("level=$level min=$minimum max=$maximum", maximum < minimum * 2L)
        }

        game.hero.level = Long.MAX_VALUE
        assertEquals(Long.MAX_VALUE, game.inventoryCapacity())
    }

    @Test
    fun `equipment price follows the quadratic level formula in ten gold units`() {
        assertEquals(500L, engine.equipmentPrice(1L))
        assertEquals(12_500L, engine.equipmentPrice(5L))
        assertEquals(50_000L, engine.equipmentPrice(10L))
        assertEquals(5_000_000L, engine.equipmentPrice(100L))
    }

    @Test
    fun `shop price uses the approved percentage for each equipment slot`() {
        assertEquals(150L, engine.equipmentPricePercent(EquipmentSlot.WEAPON))
        assertEquals(120L, engine.equipmentPricePercent(EquipmentSlot.BODY))
        assertEquals(100L, engine.equipmentPricePercent(EquipmentSlot.HEAD))
        assertEquals(90L, engine.equipmentPricePercent(EquipmentSlot.HANDS))
        assertEquals(90L, engine.equipmentPricePercent(EquipmentSlot.FEET))
        assertEquals(80L, engine.equipmentPricePercent(EquipmentSlot.ACCESSORY))

        assertEquals(75_000L, engine.equipmentPrice(10L, EquipmentSlot.WEAPON))
        assertEquals(60_000L, engine.equipmentPrice(10L, EquipmentSlot.BODY))
        assertEquals(50_000L, engine.equipmentPrice(10L, EquipmentSlot.HEAD))
        assertEquals(45_000L, engine.equipmentPrice(10L, EquipmentSlot.HANDS))
        assertEquals(45_000L, engine.equipmentPrice(10L, EquipmentSlot.FEET))
        assertEquals(40_000L, engine.equipmentPrice(10L, EquipmentSlot.ACCESSORY))
        assertEquals(750L, engine.equipmentPrice(1L, EquipmentSlot.WEAPON))
        assertEquals(Long.MAX_VALUE, engine.equipmentPrice(Long.MAX_VALUE, EquipmentSlot.WEAPON))
        (1L..100L).forEach { level ->
            EquipmentSlot.entries.forEach { slot ->
                assertEquals(0L, engine.equipmentPrice(level, slot) % 10L)
            }
        }
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

        assertEmptyShopThenDeparting(game)
    }

    @Test
    fun `pending shop offer survives a serialized restart and resolves identically`() {
        val uninterrupted = newGame(now = 0L)
        uninterrupted.hero.level = 10L
        uninterrupted.equipment.forEach { it.power = 0L }
        uninterrupted.hero.gold = engine.equipmentPrice(10L, EquipmentSlot.WEAPON)
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

        assertEquals(50, equipmentCounts.getValue("신화"))
        assertEquals(500, equipmentCounts.getValue("전설"))
        assertEquals(50_000, equipmentCounts.getValue("영웅"))
        assertEquals(140_000, equipmentCounts.getValue("희귀"))
        assertEquals(300_000, equipmentCounts.getValue("고급"))
        assertEquals(509_450, equipmentCounts.getValue("일반"))

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

        assertEmptyShopThenDeparting(game)
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
    fun `loot and shop share the five power per level base while rare loot keeps its premium`() {
        assertEquals(14L, engine.maximumShopEquipmentPower(1L))
        assertEquals(59L, engine.maximumShopEquipmentPower(10L))
        assertEquals(244L, engine.maximumShopEquipmentPower(47L))
        assertEquals(509L, engine.maximumShopEquipmentPower(100L))

        assertEquals(1L, engine.lootEquipmentPowerForRoll(1L, "일반", 0))
        assertEquals(36L, engine.lootEquipmentPowerForRoll(10L, "일반", 0))
        assertEquals(41L, engine.lootEquipmentPowerForRoll(11L, "일반", 0))
        (3L..100L).forEach { level ->
            assertEquals(
                engine.shopEquipmentPowerForRoll(level, "영웅", 0),
                engine.lootEquipmentPowerForRoll(level, "영웅", 0),
            )
            assertEquals(
                engine.shopEquipmentPowerForRoll(level, "영웅", 11),
                engine.lootEquipmentPowerForRoll(level, "영웅", 11),
            )
        }

        assertEquals(20L, engine.lootEquipmentPowerForRoll(1L, "전설", 0))
        assertEquals(31L, engine.lootEquipmentPowerForRoll(1L, "전설", 11))
        assertEquals(30L, engine.lootEquipmentPowerForRoll(1L, "신화", 0))
        assertEquals(41L, engine.lootEquipmentPowerForRoll(1L, "신화", 11))
        assertEquals(61L, engine.lootEquipmentPowerForRoll(10L, "전설", 0))
        assertEquals(66L, engine.lootEquipmentPowerForRoll(10L, "신화", 0))
        assertEquals(249L, engine.lootEquipmentPowerForRoll(47L, "전설", 0))
        assertEquals(257L, engine.lootEquipmentPowerForRoll(47L, "신화", 0))
        assertEquals(520L, engine.lootEquipmentPowerForRoll(100L, "전설", 0))
        assertEquals(535L, engine.lootEquipmentPowerForRoll(100L, "신화", 0))
        assertEquals(Long.MAX_VALUE, engine.maximumShopEquipmentPower(Long.MAX_VALUE))
        assertEquals(
            Long.MAX_VALUE,
            engine.lootEquipmentPowerForRoll(Long.MAX_VALUE, "전설", 11),
        )
        assertEquals(
            Long.MAX_VALUE,
            engine.lootEquipmentPowerForRoll(Long.MAX_VALUE, "신화", 11),
        )
    }

    @Test
    fun `plus four and plus five loot always beat the strongest same level shop item by contract`() {
        (1L..10_000L).forEach { level ->
            val shopMaximum = engine.maximumShopEquipmentPower(level)
            val legendaryMinimum = engine.lootEquipmentPowerForRoll(level, "전설", 0)
            val mythicMinimum = engine.lootEquipmentPowerForRoll(level, "신화", 0)

            assertTrue(
                "level=$level legendary=$legendaryMinimum shop=$shopMaximum",
                legendaryMinimum * 100L >= shopMaximum * 102L,
            )
            assertTrue(
                "level=$level mythic=$mythicMinimum shop=$shopMaximum",
                mythicMinimum * 100L >= shopMaximum * 105L,
            )
        }
    }

    @Test
    fun `shop keeps checking remaining slots after an unaffordable upgrade`() {
        val affordableGold = engine.equipmentPrice(10L, EquipmentSlot.HEAD)
        var missedPurchases = 0

        (1L..600L).forEach { seed ->
            val game = newGameWithSeed(seed = seed, now = 0L)
            game.hero.level = 10L
            game.hero.gold = affordableGold
            game.equipment.forEach { item ->
                item.rarity = "일반"
                item.power = 0L
            }
            game.adventurePhase = AdventurePhase.SELLING
            game.actionStartedAt = 0L
            game.actionEndsAt = 1L
            game.lastSettledAt = 0L

            engine.settle(game, game.actionEndsAt)

            val offer = game.pendingShopOffer
            if (offer == null) {
                missedPurchases += 1
            } else {
                assertTrue("seed=$seed price=${offer.price}", offer.price <= game.hero.gold)
                assertTrue(offer.slot in game.shopAttemptedSlots)
            }
        }

        assertEquals("affordable upgrade missed across 600 seeds", 0, missedPurchases)
    }

    @Test
    fun `persisted offer that becomes unaffordable falls through to another affordable slot`() {
        val game = newGame(now = 0L)
        game.hero.level = 10L
        game.hero.gold = engine.equipmentPrice(10L, EquipmentSlot.HEAD)
        game.equipment.forEach { item ->
            item.rarity = "일반"
            item.power = 0L
        }
        game.adventurePhase = AdventurePhase.SHOPPING
        game.pendingShopOffer = com.nullplaying.model.ShopEquipmentOffer(
            slot = EquipmentSlot.WEAPON,
            name = "비싼 무기",
            rarity = "일반",
            previousPower = 0L,
            newPower = 100L,
            price = engine.equipmentPrice(10L, EquipmentSlot.WEAPON),
        )
        game.shopAttemptedSlots.clear()
        game.shopAttemptedSlots += EquipmentSlot.WEAPON
        game.actionStartedAt = 0L
        game.actionEndsAt = 1L
        game.lastSettledAt = 0L

        engine.settle(game, game.actionEndsAt)

        val fallback = requireNotNull(game.pendingShopOffer)
        assertEquals(AdventurePhase.SHOPPING, game.adventurePhase)
        assertTrue(fallback.slot != EquipmentSlot.WEAPON)
        assertTrue(fallback.price <= game.hero.gold)
        assertEquals(0L, game.totalEquipmentPurchases)
    }

    @Test
    fun `shop exhausts every slot when no real upgrade is affordable`() {
        val game = newGame(now = 0L)
        game.hero.level = 10L
        game.hero.gold = 0L
        game.equipment.forEach { item -> item.power = 0L }
        game.adventurePhase = AdventurePhase.SELLING
        game.actionStartedAt = 0L
        game.actionEndsAt = 1L
        game.lastSettledAt = 0L

        engine.settle(game, game.actionEndsAt)

        assertEmptyShopThenDeparting(game)
        assertEquals(null, game.pendingShopOffer)
        assertEquals(EquipmentSlot.entries.toSet(), game.shopAttemptedSlots.toSet())
        assertEquals(0L, game.totalEquipmentPurchases)
    }

    @Test
    fun `schema thirty three raises only underpowered plus four and plus five equipment`() {
        val game = newGame(now = 0L)
        game.schemaVersion = 33
        game.hero.level = 100L
        val legendary = game.equipment.first { it.slot == EquipmentSlot.WEAPON }
        legendary.rarity = "전설"
        legendary.power = 1L
        legendary.acquiredAtLevel = 47L
        val strongMythic = game.equipment.first { it.slot == EquipmentSlot.BODY }
        strongMythic.rarity = "신화"
        strongMythic.power = 999L
        strongMythic.acquiredAtLevel = 47L
        game.inventory += InventoryItem(
            id = 1L,
            name = "오래된 전설 장비 +4",
            rarity = "전설",
            kind = "장비",
            foundAtLevel = 100L,
            equipmentSlot = EquipmentSlot.HEAD,
            equipmentPower = 100L,
        )
        game.inventory += InventoryItem(
            id = 2L,
            name = "오래된 신화 장비 +5",
            rarity = "신화",
            kind = "장비",
            foundAtLevel = 47L,
            equipmentSlot = EquipmentSlot.HANDS,
            equipmentPower = 220L,
        )
        game.lastLootRarity = "신화"
        game.lastLootEquipmentPower = 1L
        game.adventurePhase = AdventurePhase.SHOPPING
        game.pendingShopOffer = com.nullplaying.model.ShopEquipmentOffer(
            slot = EquipmentSlot.WEAPON,
            name = "구형 상점 장비",
            rarity = "영웅",
            previousPower = 1L,
            newPower = 2L,
            price = 1L,
        )
        game.lastShopPurchase = game.pendingShopOffer
        game.shopAttemptedSlots += EquipmentSlot.WEAPON

        engine.settleOfflineWithOfflineAdventure(game, now = game.lastSettledAt)

        assertEquals(SIMPLE_GAME_SCHEMA_VERSION, game.schemaVersion)
        assertEquals(249L, legendary.power)
        assertEquals(999L, strongMythic.power)
        assertEquals(520L, game.inventory.first { it.id == 1L }.equipmentPower)
        assertEquals(257L, game.inventory.first { it.id == 2L }.equipmentPower)
        assertEquals(535L, game.lastLootEquipmentPower)
        assertEquals(null, game.pendingShopOffer)
        assertEquals(null, game.lastShopPurchase)
        assertTrue(game.shopAttemptedSlots.isEmpty())
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

        assertEmptyShopThenDeparting(game)
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
        game.pendingShopOffer = com.nullplaying.model.ShopEquipmentOffer(
            slot = EquipmentSlot.WEAPON,
            name = "구형 상점 무기 +3",
            rarity = "영웅",
            previousPower = game.equipment.first().power,
            newPower = game.equipment.first().power + 1L,
            price = 1L,
        )
        game.shopAttemptedSlots += EquipmentSlot.WEAPON

        engine.settleOfflineWithOfflineAdventure(game, now = 0L)

        assertEquals(SIMPLE_GAME_SCHEMA_VERSION, game.schemaVersion)
        assertTrue(game.equipment.all { it.acquiredAtLevel == 42L })
        assertTrue(game.shopAttemptedSlots.isEmpty())
        assertEquals(null, game.pendingShopOffer)
    }

    @Test
    fun `schema twenty five clears a pending offer priced on the former curve`() {
        val game = newGame(now = 0L)
        game.schemaVersion = 25
        game.hero.level = 10L
        game.hero.gold = 1_000L
        game.adventurePhase = AdventurePhase.SHOPPING
        game.pendingShopOffer = com.nullplaying.model.ShopEquipmentOffer(
            slot = EquipmentSlot.BODY,
            name = "구형 상점 몸 장비",
            rarity = "일반",
            previousPower = game.equipment.first { it.slot == EquipmentSlot.BODY }.power,
            newPower = game.equipment.first { it.slot == EquipmentSlot.BODY }.power + 1L,
            price = 750L,
        )
        game.shopAttemptedSlots += EquipmentSlot.BODY

        engine.settleOfflineWithOfflineAdventure(game, now = 0L)

        assertEquals(SIMPLE_GAME_SCHEMA_VERSION, game.schemaVersion)
        assertEquals(null, game.pendingShopOffer)
        assertTrue(game.shopAttemptedSlots.isEmpty())
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

        assertEquals(SIMPLE_GAME_SCHEMA_VERSION, game.schemaVersion)
        assertEquals(0L, game.classGuidedLevelGrowths)
        assertEquals(496L, engine.characterStatPower(game))
        assertEquals(992L, engine.displayCombatPower(game))
    }

    @Test
    fun `real level ups count toward the new two thirds benchmark`() {
        val game = newGame(now = 0L)
        val statsBefore = game.hero.stats.copy()
        game.hero.experience = engine.experienceRequired(game.hero.level) - 1L

        val delta = settleUntilNextKill(game)

        assertEquals(2L, game.hero.level)
        assertEquals(1L, game.classGuidedLevelGrowths)
        assertEquals(335L, engine.expectedWeightedStatThirtieths(2L, 1L))
        val levelEvent = delta.recentEvents.single { it.type == RecentAdventureEventType.LEVEL_UP }
        val recordedGrowth = RecentAdventureEventMetadata.decodeStatGrowth(levelEvent.contextName).toMap()
        val actualGrowth = com.nullplaying.model.HeroStats.labels
            .map { it.replace(" ", "_") }
            .zip(statsBefore.values().zip(game.hero.stats.values()))
            .mapNotNull { (key, values) ->
                val deltaValue = values.second - values.first
                if (deltaValue > 0L) key to deltaValue else null
            }
            .toMap()
        assertEquals(actualGrowth, recordedGrowth)
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
            HeroClass.CLERIC to 39L,
            HeroClass.PALADIN to 46L,
        )

        expectedStatPower.forEach { (heroClass, statPower) ->
            game.hero.heroClass = heroClass
            assertEquals(statPower, engine.characterStatPower(game))
            assertEquals(statPower + 20L, engine.displayCombatPower(game))
        }
    }

    @Test
    fun `class offense uses primary seventy secondary thirty with half up rounding`() {
        val stats = HeroStats(
            strength = 60L,
            constitution = 30L,
            dexterity = 50L,
            intelligence = 40L,
            wisdom = 35L,
            charisma = 25L,
            maxHealth = 100L,
            maxMana = 100L,
        )
        val expectedOffense = mapOf(
            HeroClass.WARRIOR to 51L,
            HeroClass.ROGUE to 53L,
            HeroClass.RANGER to 46L,
            HeroClass.MAGE to 39L,
            HeroClass.CLERIC to 32L,
            HeroClass.PALADIN to 50L,
        )

        expectedOffense.forEach { (heroClass, expected) ->
            assertEquals(expected, engine.classOffenseAttribute(stats, heroClass))
        }

        val maximumStats = HeroStats(
            strength = Long.MAX_VALUE,
            constitution = Long.MAX_VALUE,
            dexterity = Long.MAX_VALUE,
            intelligence = Long.MAX_VALUE,
            wisdom = Long.MAX_VALUE,
            charisma = Long.MAX_VALUE,
            maxHealth = Long.MAX_VALUE,
            maxMana = Long.MAX_VALUE,
        )
        assertEquals(
            Long.MAX_VALUE,
            engine.classOffenseAttribute(maximumStats, HeroClass.WARRIOR),
        )
    }

    @Test
    fun `paladin basic skill and monster power use strength charisma and ignore wisdom`() {
        val game = newGame(now = 0L)
        game.hero.heroClass = HeroClass.PALADIN
        game.hero.level = 20L
        game.hero.stats = HeroStats(
            strength = 40L,
            constitution = 10L,
            dexterity = 10L,
            intelligence = 10L,
            wisdom = 10L,
            charisma = 20L,
            maxHealth = 100L,
            maxMana = 100L,
        )
        game.equipment.clear()
        val skill = SkillCatalog.forClass(HeroClass.PALADIN).first()
        val baselineBasic = engine.expectedBasicAttackDamage(game)
        val baselineSkill = engine.skillPreviewDamage(game, skill.catalogId)
        val baselineMonster = engine.monsterEnergyFor(game, targetAttacks = 20)

        game.hero.stats.wisdom = 10_000L

        assertEquals(baselineBasic, engine.expectedBasicAttackDamage(game))
        assertEquals(baselineSkill, engine.skillPreviewDamage(game, skill.catalogId))
        assertEquals(baselineMonster, engine.monsterEnergyFor(game, targetAttacks = 20))

        game.hero.stats.charisma = 60L
        val charismaBasic = engine.expectedBasicAttackDamage(game)
        val charismaSkill = engine.skillPreviewDamage(game, skill.catalogId)
        val charismaMonster = engine.monsterEnergyFor(game, targetAttacks = 20)

        assertTrue(charismaBasic > baselineBasic)
        assertTrue(charismaSkill > baselineSkill)
        assertTrue(charismaMonster > baselineMonster)

        game.hero.stats.strength = 80L
        assertTrue(engine.expectedBasicAttackDamage(game) > charismaBasic)
        assertTrue(engine.skillPreviewDamage(game, skill.catalogId) > charismaSkill)
        assertTrue(engine.monsterEnergyFor(game, targetAttacks = 20) > charismaMonster)
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

        assertEquals(SIMPLE_GAME_SCHEMA_VERSION, game.schemaVersion)
        assertEquals(
            engine.attackCountForCombatPower(game, MonsterGrade.NORMAL.minAttacks),
            game.monster.expectedAttacks,
        )
        assertEquals(0, game.monster.attacksCompleted)
        assertTrue(game.monster.currentEnergy > 0L)
        assertEquals(game.monster.maxEnergy, game.monster.currentEnergy)
        assertEquals(CombatPhase.REVEAL, game.combatPhase)
        assertEquals(0L, game.actionStartedAt)
        assertEquals(StatBonusRules.encounterRevealMillis(game), game.actionEndsAt)
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

        assertEquals(SIMPLE_GAME_SCHEMA_VERSION, game.schemaVersion)
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

    @Test
    fun `skill proc rate uses each class primary secondary stats at seven to three`() {
        val game = newGame(now = 0L)
        game.hero.stats = HeroStats(
            strength = 60L,
            constitution = 30L,
            dexterity = 50L,
            intelligence = 40L,
            wisdom = 35L,
            charisma = 25L,
            maxHealth = 100L,
            maxMana = 100L,
        )
        val expected = mapOf(
            HeroClass.WARRIOR to 16,
            HeroClass.ROGUE to 16,
            HeroClass.RANGER to 15,
            HeroClass.MAGE to 14,
            HeroClass.CLERIC to 13,
            HeroClass.PALADIN to 16,
        )

        expected.forEach { (heroClass, percent) ->
            game.hero.heroClass = heroClass
            assertEquals(heroClass.name, percent, engine.baseSkillProcPercent(game))
        }

        game.hero.stats = HeroStats(0L, 0L, 0L, 0L, 0L, 0L, 100L, 100L)
        assertEquals(8, engine.baseSkillProcPercent(game))
        game.hero.stats = HeroStats(100L, 100L, 100L, 100L, 100L, 100L, 100L, 100L)
        assertEquals(20, engine.baseSkillProcPercent(game))
    }

    @Test
    fun `expected skill rate includes the fifteen basic attack guarantee`() {
        val game = newGame(now = 0L)

        game.hero.stats = HeroStats(0L, 0L, 0L, 0L, 0L, 0L, 100L, 100L)
        assertEquals(1_086, engine.effectiveSkillProcBasisPoints(game))

        game.hero.stats = HeroStats(100L, 100L, 100L, 100L, 100L, 100L, 100L, 100L)
        val p = engine.skillProcBasisPoints(game) / 10_000.0
        val cycle = (1.0 - Math.pow(1.0 - p, 16.0)) / p
        assertEquals(Math.round(10_000.0 / cycle).toInt(), engine.effectiveSkillProcBasisPoints(game))
    }

    @Test
    fun `monster energy budget excludes skill tier and mastery damage`() {
        val game = newGame(now = 0L)
        game.hero.level = 20L
        // Use level-appropriate equipment so integer damage rounding cannot hide mastery.
        game.equipment.forEach { it.power = 100L }
        val targetAttacks = game.monster.expectedAttacks
        val noviceBasicDamage = engine.expectedBasicAttackDamage(game)
        val noviceDamage = engine.expectedAttackDamage(game)
        val noviceEnergy = engine.monsterEnergyFor(game, targetAttacks)

        game.skills[0] = game.skills.single().copy(usageCount = 1_000L)

        assertEquals(noviceBasicDamage, engine.expectedBasicAttackDamage(game))
        assertTrue(engine.expectedAttackDamage(game) > noviceDamage)
        assertEquals(noviceEnergy, engine.monsterEnergyFor(game, targetAttacks))

        val masteredDamage = engine.expectedAttackDamage(game)
        val finalTier = SkillCatalog.forClass(HeroClass.WARRIOR).last()
        game.skills[0] = game.skills.single().copy(
            id = 20,
            name = finalTier.name,
            catalogId = finalTier.catalogId,
            usageCount = LearnedSkill.MAX_USAGE_COUNT,
        )

        assertTrue(engine.expectedAttackDamage(game) > masteredDamage)
        assertEquals(noviceEnergy, engine.monsterEnergyFor(game, targetAttacks))
    }

    @Test
    fun `fifteen consecutive basic attacks force the next skill and reset the streak`() {
        val game = newGame(now = 0L)
        game.consecutiveBasicAttacks = SimpleGameEngine.MAX_CONSECUTIVE_BASIC_ATTACKS
        game.combatPhase = CombatPhase.ATTACKING
        game.actionStartedAt = 0L
        game.actionEndsAt = 1L

        assertTrue(engine.shouldUseSkill(game, procRoll = 9_999))
        engine.settle(game, now = 1L)

        assertTrue(game.lastAttackWasSkill)
        assertEquals(0, game.consecutiveBasicAttacks)
    }

    @Test
    fun `new skill selection weight fades from eight to one through two hundred uses`() {
        val game = newGame(now = 0L)
        val definitions = SkillCatalog.forClass(HeroClass.WARRIOR)
        game.skills = listOf(0L, 100L, 150L, 200L).mapIndexed { index, usageCount ->
            val definition = definitions[index]
            game.skills.single().copy(
                id = index + 1,
                name = definition.name,
                catalogId = definition.catalogId,
                usageCount = usageCount,
            )
        }.toMutableList()

        assertEquals(listOf(8, 5, 3, 1), engine.skillSelectionWeights(game))
    }

    @Test
    fun `one owned skill remains selectable while two owned skills alternate`() {
        val singleSkillGame = newGame(now = 0L)
        val onlySkill = singleSkillGame.skills.single()
        singleSkillGame.lastActivatedSkillCatalogId = onlySkill.catalogId

        assertEquals(listOf(8), engine.skillSelectionWeights(singleSkillGame))
        assertEquals(0, engine.selectWeightedSkillIndex(singleSkillGame, selectionRoll = 7))

        val twoSkillGame = newGame(now = 0L)
        twoSkillGame.hero.level = 5L
        val secondDefinition = SkillCatalog.forClass(HeroClass.WARRIOR)[1]
        twoSkillGame.skills += LearnedSkill(
            id = 2,
            name = secondDefinition.name,
            acquiredAtLevel = 5L,
            description = secondDefinition.description,
            catalogId = secondDefinition.catalogId,
        )
        twoSkillGame.lastActivatedSkillCatalogId = twoSkillGame.skills.first().catalogId

        assertEquals(listOf(0, 8), engine.skillSelectionWeights(twoSkillGame))
        assertEquals(1, engine.selectWeightedSkillIndex(twoSkillGame, selectionRoll = 0))

        twoSkillGame.consecutiveBasicAttacks = SimpleGameEngine.MAX_CONSECUTIVE_BASIC_ATTACKS
        twoSkillGame.combatPhase = CombatPhase.ATTACKING
        twoSkillGame.actionStartedAt = 0L
        twoSkillGame.actionEndsAt = 1L
        engine.settle(twoSkillGame, now = 1L)

        assertTrue(twoSkillGame.lastAttackWasSkill)
        assertEquals(secondDefinition.catalogId, twoSkillGame.lastActivatedSkillCatalogId)
    }

    @Test
    fun `basic attacks and presentation clearing keep the previous skill exclusion`() {
        val game = newGame(now = 0L)
        val onlySkillCatalogId = game.skills.single().catalogId
        game.lastActivatedSkillCatalogId = onlySkillCatalogId
        var guard = 0
        while (game.lastAttackType != "기본 공격" && guard < 100) {
            engine.settle(game, game.actionEndsAt)
            guard += 1
        }

        assertEquals("기본 공격", game.lastAttackType)
        assertEquals(onlySkillCatalogId, game.lastActivatedSkillCatalogId)

        game.offlineAdventureMillis = 0L
        engine.settleOfflineWithOfflineAdventure(game, now = game.lastSettledAt + 1L)

        assertEquals("", game.lastSkillCatalogId)
        assertEquals(onlySkillCatalogId, game.lastActivatedSkillCatalogId)
    }

    @Test
    fun `schema forty one starts weighted selection without a previous skill`() {
        val game = newGame(now = 0L)
        game.schemaVersion = 41
        game.lastActivatedSkillCatalogId = game.skills.single().catalogId

        engine.settleOfflineWithOfflineAdventure(game, now = 0L)

        assertEquals(SIMPLE_GAME_SCHEMA_VERSION, game.schemaVersion)
        assertEquals("", game.lastActivatedSkillCatalogId)
    }

    @Test
    fun `schema twenty eight resets the newly persisted basic attack pity counter`() {
        val game = newGame(now = 0L)
        game.schemaVersion = 28
        game.consecutiveBasicAttacks = 15

        engine.settleOfflineWithOfflineAdventure(game, now = 0L)

        assertEquals(SIMPLE_GAME_SCHEMA_VERSION, game.schemaVersion)
        assertEquals(0, game.consecutiveBasicAttacks)
    }

    @Test
    fun `schema twenty nine active combat is rebuilt with real damage energy`() {
        val game = newGame(now = 0L)
        game.schemaVersion = 29
        game.consecutiveBasicAttacks = 7
        game.monster.maxEnergy = SimpleGameEngine.MONSTER_ENERGY_SCALE
        game.monster.currentEnergy = 40L
        game.monster.attacksCompleted = 3
        game.combatPhase = CombatPhase.ATTACKING
        game.lastDamage = 60L
        game.lastMonsterEnergyBeforeAttack = 100L
        game.lastAttackName = "이전 스킬"

        engine.settleOfflineWithOfflineAdventure(game, now = 0L)

        assertEquals(SIMPLE_GAME_SCHEMA_VERSION, game.schemaVersion)
        assertEquals(7, game.consecutiveBasicAttacks)
        assertEquals(0, game.monster.attacksCompleted)
        assertTrue(game.monster.maxEnergy > 0L)
        assertEquals(game.monster.maxEnergy, game.monster.currentEnergy)
        assertEquals(CombatPhase.REVEAL, game.combatPhase)
        assertEquals(0L, game.lastDamage)
        assertEquals(0L, game.lastMonsterEnergyBeforeAttack)
        assertEquals("", game.lastAttackName)
    }

    @Test
    fun `schema thirty five active combat is rebuilt for unified class offense`() {
        val game = newGame(now = 0L)
        game.schemaVersion = 35
        game.hero.heroClass = HeroClass.RANGER
        game.hero.stats.dexterity = 40L
        game.hero.stats.wisdom = 10L
        game.adventurePhase = AdventurePhase.COMBAT
        game.combatPhase = CombatPhase.ATTACKING
        game.monster.maxEnergy = 99_999L
        game.monster.currentEnergy = 12_345L
        game.monster.attacksCompleted = 3
        game.lastDamage = 60L
        game.lastMonsterEnergyBeforeAttack = 100L
        game.lastAttackName = "이전 스킬"

        engine.settleOfflineWithOfflineAdventure(game, now = 0L)

        assertEquals(SIMPLE_GAME_SCHEMA_VERSION, game.schemaVersion)
        assertEquals(0, game.monster.attacksCompleted)
        assertTrue(game.monster.maxEnergy > 0L)
        assertTrue(game.monster.maxEnergy != 99_999L)
        assertEquals(game.monster.maxEnergy, game.monster.currentEnergy)
        assertEquals(CombatPhase.REVEAL, game.combatPhase)
        assertEquals(0L, game.lastDamage)
        assertEquals(0L, game.lastMonsterEnergyBeforeAttack)
        assertEquals("", game.lastAttackName)
    }

    @Test
    fun `schema thirty nine active combat is rebuilt for the current monster energy formula`() {
        val game = newGame(now = 0L)
        game.schemaVersion = 39
        game.adventurePhase = AdventurePhase.COMBAT
        game.combatPhase = CombatPhase.ATTACKING
        game.monster.maxEnergy = 99_999L
        game.monster.currentEnergy = 12_345L
        game.monster.attacksCompleted = 3
        game.lastDamage = 60L
        game.lastMonsterEnergyBeforeAttack = 100L
        game.lastAttackName = "이전 공격"

        engine.settleOfflineWithOfflineAdventure(game, now = 0L)

        assertEquals(SIMPLE_GAME_SCHEMA_VERSION, game.schemaVersion)
        assertEquals(0, game.monster.attacksCompleted)
        assertTrue(game.monster.maxEnergy > 0L)
        assertTrue(game.monster.maxEnergy != 99_999L)
        assertEquals(game.monster.maxEnergy, game.monster.currentEnergy)
        assertEquals(CombatPhase.REVEAL, game.combatPhase)
        assertEquals(0L, game.lastDamage)
        assertEquals(0L, game.lastMonsterEnergyBeforeAttack)
        assertEquals("", game.lastAttackName)
    }

    @Test
    fun `schema forty migrates saved class equipment to the approved terminology`() {
        val game = newGame(now = 0L)
        game.schemaVersion = 40
        game.equipment.first().name = "훈련식 전사 투구"
        game.inventory += InventoryItem(
            id = 41L,
            name = "견습식 도적 가죽옷 +1",
            rarity = "고급",
            kind = "장비",
            foundAtLevel = 4L,
            equipmentSlot = EquipmentSlot.BODY,
            equipmentPower = 12L,
        )
        game.pendingShopOffer = ShopEquipmentOffer(
            slot = EquipmentSlot.WEAPON,
            name = "모험식 순찰자 창",
            rarity = "일반",
            previousPower = 10L,
            newPower = 11L,
            price = 100L,
        )
        game.lastShopPurchase = ShopEquipmentOffer(
            slot = EquipmentSlot.BODY,
            name = "철제 마도사 로브",
            rarity = "일반",
            previousPower = 11L,
            newPower = 12L,
            price = 120L,
        )
        game.lastTownItemName = "강철 성직 장화"
        game.lastLootName = "왕실제 성기사 갑옷"
        game.lastLootSummary = "왕실제 성기사 갑옷 획득"
        game.lastResult = "왕실제 성기사 갑옷 자동 장착"
        game.recentItemNames = mutableListOf("강철 성직 장화", "훈련식 전사 투구")

        engine.settleOfflineWithOfflineAdventure(game, now = 0L)

        assertEquals(SIMPLE_GAME_SCHEMA_VERSION, game.schemaVersion)
        assertEquals("훈련식 파이터 투구", game.equipment.first().name)
        assertEquals("견습식 시프 가죽옷 +1", game.inventory.last().name)
        assertEquals("모험식 레인저 창", game.pendingShopOffer?.name)
        assertEquals("철제 메이지 로브", game.lastShopPurchase?.name)
        assertEquals("강철 클래릭 장화", game.lastTownItemName)
        assertEquals("왕실제 팔라딘 갑옷", game.lastLootName)
        assertEquals("왕실제 팔라딘 갑옷 획득", game.lastLootSummary)
        assertEquals("왕실제 팔라딘 갑옷 자동 장착", game.lastResult)
        assertEquals(
            listOf("강철 클래릭 장화", "훈련식 파이터 투구"),
            game.recentItemNames,
        )
    }

    @Test
    fun `schema thirty refreshes the active tale and completed history with dawn bells`() {
        val game = newGame(now = 0L)
        val definition = AdventureTaleCatalog.mainTales[3]
        val legacyTale = AdventureTaleCatalog.instantiate(
            definition = definition,
            sequence = 4L,
            heroName = game.hero.name,
            heroLevel = game.hero.level,
            variant = game.adventureTale.variant,
        )
        legacyTale.currentActIndex = 1
        legacyTale.acts[1] = legacyTale.acts[1].copy(
            title = "종혀가 여는 길",
            body = "첫 번째 종혀를 홈에 대자 비밀 계단이 나타났다.",
            completionBody = "첫 종혀로 비밀 계단을 열어 오르반을 찾았다.",
            progress = 37L,
        )
        val previousTarget = legacyTale.acts[1].target
        val previousRewardExperience = legacyTale.acts[1].rewardExperience
        game.adventureTale = legacyTale
        game.completedTaleHistory += CompletedTaleRecord(
            taleSequence = 4L,
            taleId = definition.id,
            kind = TaleKind.MAIN,
            volumeNumber = 1,
            chapterNumber = 4,
            title = definition.title,
            summary = "첫 종혀로 비밀 계단을 열어 오르반을 찾았다.",
            nextHook = "도둑맞은 세 종혀를 되찾아야 한다.",
            actMemories = listOf("첫 번째 종혀를 홈에 대자 비밀 계단이 나타났다."),
            completedAtLevel = game.hero.level,
        )
        game.monster.catalogId = "ash_border.c04.boss.02"
        game.monster.baseName = "종혀로 열린 길의 파수꾼"
        game.monster.name = "종혀 홈에 웅크린 종혀로 열린 길의 파수꾼"
        game.recentMonsterNames = mutableListOf(game.monster.name)
        game.lastResult = "종혀로 열린 길의 파수꾼 처치"
        game.schemaVersion = 30

        engine.settleOfflineWithOfflineAdventure(game, now = 0L)

        val migratedAct = game.adventureTale.acts[1]
        assertEquals(SIMPLE_GAME_SCHEMA_VERSION, game.schemaVersion)
        assertEquals("clapper_door", migratedAct.id)
        assertEquals("종소리가 여는 길", migratedAct.title)
        assertTrue(migratedAct.body.contains("첫 번째 새벽종을 울리자"))
        assertEquals(37L, migratedAct.progress)
        assertEquals(previousTarget, migratedAct.target)
        assertEquals(previousRewardExperience, migratedAct.rewardExperience)
        val migratedHistory = game.completedTaleHistory.single()
        assertTrue(migratedHistory.summary.contains("첫 번째 새벽종을 울려"))
        assertTrue(migratedHistory.nextHook.contains("도둑맞은 두 새벽종"))
        assertFalse(migratedHistory.actMemories.any { "종혀" in it || "홈에 대자" in it })
        assertEquals("종소리로 열린 길의 파수꾼", game.monster.baseName)
        assertFalse(game.monster.name.contains("종혀"))
        assertFalse(game.recentMonsterNames.any { "종혀" in it })
        assertFalse(game.lastResult.contains("종혀"))
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

    private fun settleUntilSkillAttack(game: SimpleGameState) {
        var guard = 0
        while (guard < 1_000) {
            val beforeSequence = game.actionSequence
            engine.settle(game, game.actionEndsAt)
            if (game.actionSequence > beforeSequence && game.lastAttackWasSkill) return
            guard += 1
        }
        assertTrue("skill attack was not selected", false)
    }

    private fun newGame(now: Long) = engine.newGame(
        name = "테스터",
        heroClass = HeroClass.WARRIOR,
        rolledStats = engine.rollStats(77L).stats,
        seed = 88L,
        now = now,
    ).also(::skipOpeningForLegacyTestBaseline)

    private fun newGameWithSeed(seed: Long, now: Long) = engine.newGame(
        name = "테스터",
        heroClass = HeroClass.WARRIOR,
        rolledStats = engine.rollStats(77L).stats,
        seed = seed,
        now = now,
    ).also(::skipOpeningForLegacyTestBaseline)

    private fun skipOpeningForLegacyTestBaseline(game: SimpleGameState) {
        engine.settle(game, game.actionEndsAt)
        game.actionStartedAt -= SimpleGameEngine.OPENING_PRESENTATION_MILLIS
        game.actionEndsAt -= SimpleGameEngine.OPENING_PRESENTATION_MILLIS
        game.lastSettledAt -= SimpleGameEngine.OPENING_PRESENTATION_MILLIS
        game.lastResult = "모험을 준비하는 중"
    }

    private fun assertEmptyShopThenDeparting(game: SimpleGameState) {
        assertEquals(AdventurePhase.SHOPPING_EMPTY, game.adventurePhase)
        assertEquals(null, game.pendingShopOffer)
        assertEquals(
            "지금 살 수 있는 더 좋은 장비를 찾지 못했습니다",
            game.lastResult,
        )
        assertEquals(
            SimpleGameEngine.SHOP_EMPTY_RESULT_MILLIS,
            game.actionEndsAt - game.actionStartedAt,
        )

        engine.settle(game, game.actionEndsAt)

        assertEquals(AdventurePhase.DEPARTING, game.adventurePhase)
    }

    private fun SimpleGameState.useFirstMainTale() {
        adventureTale = AdventureTaleCatalog.instantiate(
            definition = AdventureTaleCatalog.firstMain,
            sequence = 1L,
            heroName = hero.name,
            heroLevel = hero.level,
            variant = AdventureTaleCatalog.variantAt(0),
        )
    }

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
