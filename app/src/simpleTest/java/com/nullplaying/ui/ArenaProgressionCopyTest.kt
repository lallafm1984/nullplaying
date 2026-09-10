package com.nullplaying.ui

import com.nullplaying.engine.arena.ArenaProgressionCatalog
import com.nullplaying.engine.arena.ArenaProgressionRules
import com.nullplaying.engine.arena.ArenaSupportCatalog
import com.nullplaying.engine.arena.ArenaSupportTraitRank
import com.nullplaying.localization.AppLanguage
import com.nullplaying.model.HeroClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArenaProgressionCopyTest {
    @Test
    fun `UI exposes all 144 growth traits and 60 supports exactly once in their own class`() {
        assertEquals(144, ArenaProgressionCatalog.values.size)
        assertEquals(60, ArenaSupportCatalog.values.size)
        val displayedTraits = HeroClass.entries.flatMap { heroClass ->
            arenaProgressionUiDefinitions(heroClass).also { definitions ->
                assertEquals(24, definitions.size)
                assertTrue(definitions.all { it.heroClass == heroClass })
                assertEquals(setOf("A", "B", "C"), definitions.map { it.branch }.toSet())
            }
        }
        val displayedSupports = HeroClass.entries.flatMap { heroClass ->
            ArenaSupportCatalog.forClass(heroClass).also { definitions ->
                assertEquals(10, definitions.size)
                assertTrue(definitions.all { it.heroClass == heroClass })
            }
        }
        assertEquals(ArenaProgressionCatalog.values.map { it.id }.toSet(), displayedTraits.map { it.id }.toSet())
        assertEquals(144, displayedTraits.map { it.id }.distinct().size)
        assertEquals(ArenaSupportCatalog.values.map { it.id }.toSet(), displayedSupports.map { it.id }.toSet())
        assertEquals(60, displayedSupports.map { it.id }.distinct().size)
    }

    @Test
    fun `all trait names triggers effects and limitations are readable in every UI language`() {
        ArenaProgressionCatalog.values.forEach { definition ->
            AppLanguage.entries.forEach { language ->
                val expectedName = when (language) {
                    AppLanguage.KOREAN -> definition.nameKo
                    AppLanguage.ENGLISH -> definition.nameEn
                    AppLanguage.JAPANESE -> definition.nameJa
                }
                assertEquals(expectedName, arenaProgressionTraitName(definition, language))
                assertReadableCopy(expectedName, language)
                assertReadableCopy(definition.conditionText(language.arenaCode), language)
                assertReadableCopy(definition.limitationText(language.arenaCode), language)
                for (rank in 1..definition.maxRank) {
                    for (enhancement in if (definition.isCore) 0..0 else 0..3) {
                        val actual = arenaProgressionTraitEffect(definition, rank, enhancement, language)
                        assertEquals("${definition.id} / $rank / $enhancement / $language",
                            definition.effectText(language.arenaCode, rank, enhancement), actual)
                        assertReadableCopy(actual, language)
                        val summary = arenaProgressionTraitSummary(definition, rank, enhancement, language)
                        assertEquals(definition.summaryText(language.arenaCode, rank, enhancement), summary)
                        assertReadableCopy(summary, language)
                        if (!definition.isCore) {
                            val displayedNumbers = Regex("\\d+(?:\\.\\d+)?").findAll(summary).map { it.value.toDouble() }.toList()
                            assertTrue("${definition.id}: $summary", displayedNumbers.any { kotlin.math.abs(it - definition.value(rank, enhancement)) < 0.00001 })
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `rank zero is unlearned for every trait including replacement cores`() {
        ArenaProgressionCatalog.values.forEach { definition ->
            AppLanguage.entries.forEach { language ->
                val expected = when (language) {
                    AppLanguage.KOREAN -> "미습득"
                    AppLanguage.ENGLISH -> "Not learned"
                    AppLanguage.JAPANESE -> "未習得"
                }
                assertEquals(expected, arenaProgressionTraitEffect(definition, 0, 0, language))
            }
        }
    }

    @Test
    fun `all support details and hero-level unlock labels come from the executable catalogue`() {
        ArenaSupportCatalog.values.forEach { definition ->
            AppLanguage.entries.forEach { language ->
                assertReadableCopy(definition.name(language.arenaCode), language)
                assertReadableCopy(definition.effectText(language.arenaCode), language)
                assertReadableCopy(definition.summaryText(language.arenaCode), language)
                assertReadableCopy(definition.conditionText(language.arenaCode), language)
                assertReadableCopy(definition.limitationText(language.arenaCode), language)
                val timings = arenaSupportTimingLines(definition, language)
                timings.forEach { assertReadableCopy(it, language) }
                assertTrue(timings.first().contains("MP ${definition.mp}"))
                assertTrue(timings.first().contains(definition.castTurns.toString()))
                if (definition.durationTurns > 0) assertTrue(timings[1].contains(definition.durationTurns.toString()))
                if (!definition.oncePerBattle) assertTrue(timings[2].contains(definition.cooldownTurns.toString()))
                if (definition.charges > 0) assertTrue(timings.last().contains(definition.charges.toString()))
                val locked = model(definition.heroClass).copy(savedHeroLevel = definition.unlockLevel.toLong() - 1, ownedSupportIds = emptySet())
                val unlocked = locked.copy(savedHeroLevel = definition.unlockLevel.toLong(), ownedSupportIds = setOf(definition.id))
                val lockedText = supportStatus(locked, definition, language)
                assertTrue(lockedText.contains(definition.unlockLevel.toString()))
                assertReadableCopy(lockedText, language)
                assertReadableCopy(supportStatus(unlocked, definition, language), language)
                assertFalse(lockedText == supportStatus(unlocked, definition, language))
            }
        }
    }

    @Test
    fun `explicit ownership is honored and never replaced by hero-level defaults`() {
        HeroClass.entries.forEach { heroClass ->
            val explicitEmpty = model(heroClass).copy(ownedSupportIds = emptySet())
            assertTrue(explicitEmpty.supportIds().isEmpty())
            arenaProgressionUiDefinitions(heroClass).filter { it.requiredSupportIds.isNotEmpty() }.forEach { definition ->
                assertFalse(explicitEmpty.traitState(definition).available)
                assertFalse(arenaTraitPurchaseState(explicitEmpty, definition).canRankUp)
            }
            val automatic = model(heroClass)
            assertEquals(ArenaSupportCatalog.unlockedIds(heroClass, automatic.effectiveHeroLevel()), automatic.supportIds())
            assertTrue(automatic.supportIds().all { ArenaSupportCatalog.find(it)?.heroClass == heroClass })
        }
    }

    @Test
    fun `support detail reflects the same core replacements as the combat catalogue`() {
        ArenaProgressionCatalog.values.filter { it.isCore }.forEach { core ->
            val state = model(core.heroClass).copy(traits = listOf(ArenaProgressionTraitUiModel(core.id, 1, 0)))
            ArenaSupportCatalog.forClass(core.heroClass).forEach { support ->
                val expected = ArenaSupportCatalog.effectiveDefinition(support.id, listOf(ArenaSupportTraitRank(core.id, 1, 0)))
                assertEquals("${core.id} / ${support.id}", expected, arenaSupportEffectiveDefinition(state, support))
                AppLanguage.entries.forEach { language ->
                    assertReadableCopy(arenaSupportEffectiveDefinition(state, support).summaryText(language.arenaCode), language)
                    arenaSupportTimingLines(arenaSupportEffectiveDefinition(state, support), language).forEach {
                        assertReadableCopy(it, language)
                    }
                }
            }
        }
    }

    @Test
    fun `all required supports must be owned before presenting an available trait`() {
        ArenaProgressionCatalog.values.filter { it.requiredSupportIds.isNotEmpty() }.forEach { definition ->
            val all = model(definition.heroClass).copy(ownedSupportIds = definition.requiredSupportIds.toSet())
            assertTrue(definition.id, all.traitState(definition).available)
            definition.requiredSupportIds.forEach { removed ->
                assertFalse("${definition.id} missing $removed",
                    all.copy(ownedSupportIds = all.supportIds() - removed).traitState(definition).available)
            }
        }
    }

    @Test
    fun `rank actions derive correct ordinary and core costs and reject short budgets`() {
        ArenaProgressionCatalog.values.forEach { definition ->
            for (rank in 0 until definition.maxRank) {
                val before = model(definition.heroClass).copy(traits = listOf(ArenaProgressionTraitUiModel(definition.id, rank, 0)))
                val action = arenaTraitPurchaseState(before, definition)
                val expectedCost = definition.basePointCost(rank + 1) - definition.basePointCost(rank)
                assertEquals(expectedCost, action.rankCost)
                assertTrue(action.canRankUp)
                assertFalse(arenaTraitPurchaseState(before.copy(baseAvailable = expectedCost - 1), definition).canRankUp)
                assertTrue(arenaTraitPurchaseState(before.copy(baseAvailable = expectedCost), definition).canRankUp)
            }
            val maxed = model(definition.heroClass).copy(traits = listOf(ArenaProgressionTraitUiModel(definition.id, definition.maxRank, 0)))
            assertFalse(arenaTraitPurchaseState(maxed, definition).canRankUp)
        }
    }

    @Test
    fun `only one core can be selected and cores never offer enhancement`() {
        HeroClass.entries.forEach { heroClass ->
            val cores = arenaProgressionUiDefinitions(heroClass).filter { it.isCore }
            assertEquals(3, cores.size)
            cores.forEach { selected ->
                val state = model(heroClass).copy(traits = listOf(ArenaProgressionTraitUiModel(selected.id, 1, 0)))
                cores.forEach { candidate ->
                    val action = arenaTraitPurchaseState(state, candidate)
                    assertFalse(action.canRankUp)
                    assertFalse(action.canEnhance)
                    assertEquals(0, action.enhancementCost)
                    assertEquals(candidate.id != selected.id, action.anotherCoreLearned)
                }
            }
        }
    }

    @Test
    fun `enhance actions preserve the cumulative two three five cost contract and level gate`() {
        ArenaProgressionCatalog.values.filterNot { it.isCore }.forEach { definition ->
            for (stage in 0..2) {
                val before = model(definition.heroClass).copy(traits = listOf(ArenaProgressionTraitUiModel(definition.id, 1, stage)))
                val expectedCost = ArenaProgressionRules.enhancementCost(stage + 1) - ArenaProgressionRules.enhancementCost(stage)
                val action = arenaTraitPurchaseState(before, definition)
                assertEquals(expectedCost, action.enhancementCost)
                assertTrue(action.canEnhance)
                assertFalse(arenaTraitPurchaseState(before.copy(arenaLevel = 50), definition).canEnhance)
                assertFalse(arenaTraitPurchaseState(before.copy(enhancementAvailable = expectedCost - 1), definition).canEnhance)
                assertTrue(arenaTraitPurchaseState(before.copy(arenaLevel = 51, enhancementAvailable = expectedCost), definition).canEnhance)
            }
            val maxed = model(definition.heroClass).copy(traits = listOf(ArenaProgressionTraitUiModel(definition.id, 5, 3)))
            assertFalse(arenaTraitPurchaseState(maxed, definition).canEnhance)
        }
    }

    @Test
    fun `preparation and active battle lock both purchase actions without hiding catalogue`() {
        ArenaProgressionCatalog.values.forEach { definition ->
            val state = model(definition.heroClass).copy(editingEnabled = false)
            val action = arenaTraitPurchaseState(state, definition)
            assertFalse(action.canRankUp)
            assertFalse(action.canEnhance)
            assertEquals(24, arenaProgressionUiDefinitions(state.heroClass).size)
        }
    }

    private fun model(heroClass: HeroClass) = ArenaProgressionUiModel(
        heroClass = heroClass, unlocked = true, arenaLevel = 100,
        xpIntoLevel = 0, xpToNext = 0, baseAvailable = 50, baseSpent = 0,
        enhancementAvailable = 50, enhancementSpent = 0, rank = 0, enhancement = 0,
        growthRemaining = 0, editingEnabled = true, savedHeroLevel = 100,
    )

    private fun assertReadableCopy(text: String, language: AppLanguage) {
        val message = "$language: $text"
        assertTrue(message, text.isNotBlank())
        assertFalse(message, text.contains("AT9_"))
        assertFalse(message, text.contains("ARENA_SUP_"))
        assertFalse(message, text.contains(Regex("\\{[^}]+}")))
        assertFalse(message, text.contains(Regex("\\bnull\\b")))
        if (language != AppLanguage.KOREAN) assertFalse(message, text.contains(Regex("[가-힣]")))
    }
}
