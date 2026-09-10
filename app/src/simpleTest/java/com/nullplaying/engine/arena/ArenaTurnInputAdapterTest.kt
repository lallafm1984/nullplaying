package com.nullplaying.engine.arena

import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.engine.SkillCatalog
import com.nullplaying.engine.PublicPlayerBattleDerivation
import com.nullplaying.model.HeroClass
import com.nullplaying.model.LearnedSkill
import com.nullplaying.model.PublicPlayerSnapshot
import com.nullplaying.model.PublicPlayerStats
import com.nullplaying.model.SHARED_PLAYER_RULES_VERSION
import com.nullplaying.model.SHARED_PLAYER_SNAPSHOT_VERSION
import com.nullplaying.model.SimpleGameState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ArenaTurnInputAdapterTest {
    private val engine = SimpleGameEngine()

    @Test
    fun levelTenRetainsAllThreeCatalogSkillsForEveryClass() {
        HeroClass.entries.forEach { heroClass ->
            val state = stateAtLevel(heroClass, 10L)
            val result = ArenaTurnInputAdapter.fromState(state, "hero")

            assertEquals("$heroClass source fixture", 3, state.skills.size)
            assertEquals(listOf(1, 2, 3), result.fighter.attacks.map { it.tier })
            assertEquals(
                SkillCatalog.forClass(heroClass).take(3).map { it.catalogId },
                result.fighter.attacks.map { it.id },
            )
            assertTrue("$heroClass rejected skills", result.rejectedSkills.isEmpty())
        }
    }

    @Test
    fun levelNinetyFiveRetainsAllTwentySkillsWithoutTruncation() {
        HeroClass.entries.forEach { heroClass ->
            val state = stateAtLevel(heroClass, 95L)
            val result = ArenaTurnInputAdapter.fromState(state, "hero")
            val definitions = SkillCatalog.forClass(heroClass)

            assertEquals(20, state.skills.size)
            assertEquals(20, result.fighter.attacks.size)
            assertEquals(definitions.map { it.catalogId }, result.fighter.attacks.map { it.id })
            assertEquals((1..20).toList(), result.fighter.attacks.map { it.tier })
            assertEquals(
                definitions.map { it.damagePercentMin to it.damagePercentMax },
                result.fighter.attacks.map { it.sourceDamagePercentMin to it.sourceDamagePercentMax },
            )
            assertTrue(result.rejectedSkills.isEmpty())
        }
    }

    @Test
    fun everyTenLevelCheckpointUsesOnlyItsActuallyLearnedTiers() {
        val checkpoints = listOf(
            10L to 3, 20L to 5, 30L to 7, 40L to 9, 50L to 11,
            60L to 13, 70L to 15, 80L to 17, 90L to 19, 100L to 20,
        )
        HeroClass.entries.forEach { heroClass ->
            checkpoints.forEach { (level, expectedCount) ->
                val state = stateAtLevel(heroClass, level)
                val result = ArenaTurnInputAdapter.fromState(state, "hero")

                assertEquals("$heroClass at level $level", expectedCount, state.skills.size)
                assertEquals(expectedCount, result.fighter.attacks.size)
                assertEquals(level, result.fighter.level)
                assertTrue(result.rejectedSkills.isEmpty())
            }
        }
    }

    @Test
    fun heroLevelsAboveOneHundredAreNotClamped() {
        HeroClass.entries.forEach { heroClass ->
            listOf(100L, 110L, 200L).forEach { level ->
                val state = stateAtLevel(heroClass, level)
                val result = ArenaTurnInputAdapter.fromState(state, "hero")

                assertEquals(level, result.fighter.level)
                assertEquals(20, result.fighter.attacks.size)
                assertTrue(result.rejectedSkills.isEmpty())
            }
        }
    }

    @Test
    fun partialOwnershipIsPreservedIncludingSourceOrder() {
        val state = stateAtLevel(HeroClass.RANGER, 95L)
        val selected = listOf(19, 2, 12, 6, 9).map { state.skills[it] }
        state.skills = selected.toMutableList()

        val result = ArenaTurnInputAdapter.fromState(state, "hero")

        assertEquals(5, result.fighter.attacks.size)
        assertEquals(selected.map { it.catalogId }, result.fighter.attacks.map { it.id })
        assertEquals(listOf(20, 3, 13, 7, 10), result.fighter.attacks.map { it.tier })
        assertTrue(result.rejectedSkills.isEmpty())
    }

    @Test
    fun emptyOwnershipDoesNotInventCatalogSkillsOrABasicAttack() {
        val state = stateAtLevel(HeroClass.MAGE, 95L)
        state.skills.clear()

        val result = ArenaTurnInputAdapter.fromState(state, "hero")

        assertTrue(result.fighter.attacks.isEmpty())
        assertTrue(result.rejectedSkills.isEmpty())
        assertTrue(state.skills.isEmpty())
    }

    @Test
    fun legacyNameResolvesOnlyTheExactSameClassDefinition() {
        val state = stateAtLevel(HeroClass.CLERIC, 10L)
        val learned = state.skills[2]
        state.skills = mutableListOf(
            learned.copy(id = 999, catalogId = "", acquiredAtLevel = 999L),
        )

        val result = ArenaTurnInputAdapter.fromState(state, "hero")
        val attack = result.fighter.attacks.single()

        assertEquals(learned.catalogId, attack.id)
        assertEquals(learned.name, attack.name)
        assertEquals(3, attack.tier)
        assertTrue(result.rejectedSkills.isEmpty())
        assertEquals("", state.skills.single().catalogId)
        assertEquals(999, state.skills.single().id)
    }

    @Test
    fun legacyNamesAreNotTrimmedOrStrippedOfDisplaySuffixes() {
        val state = stateAtLevel(HeroClass.WARRIOR, 10L)
        val learned = state.skills.first()
        val foreignName = SkillCatalog.forClass(HeroClass.MAGE).first().name
        state.skills = mutableListOf(
            learned.copy(catalogId = "", name = " " + learned.name),
            learned.copy(catalogId = "", name = learned.name + " LV.1"),
            learned.copy(catalogId = "", name = foreignName),
        )

        val result = ArenaTurnInputAdapter.fromState(state, "hero")

        assertTrue(result.fighter.attacks.isEmpty())
        assertEquals(3, result.rejectedSkills.size)
        assertTrue(result.rejectedSkills.all { it.startsWith("unknown[") })
    }

    @Test
    fun invalidPresentCatalogIdNeverFallsBackToAValidName() {
        val state = stateAtLevel(HeroClass.WARRIOR, 1L)
        state.skills[0] = state.skills[0].copy(catalogId = "missing_catalog_id")

        val result = ArenaTurnInputAdapter.fromState(state, "hero")

        assertTrue(result.fighter.attacks.isEmpty())
        assertTrue(result.rejectedSkills.single().startsWith("unknown["))
    }

    @Test
    fun foreignLockedDuplicateAndUnknownSkillsAreReportedSeparately() {
        val state = stateAtLevel(HeroClass.WARRIOR, 10L)
        val original = state.skills.toList()
        val foreign = stateAtLevel(HeroClass.MAGE, 1L).skills.single()
        val locked = stateAtLevel(HeroClass.WARRIOR, 15L).skills.last()
        state.skills += listOf(
            foreign,
            locked,
            original.first().copy(catalogId = ""),
            original.first().copy(catalogId = "unknown_catalog_id"),
        )

        val result = ArenaTurnInputAdapter.fromState(state, "hero")

        assertEquals(original.map { it.catalogId }, result.fighter.attacks.map { it.id })
        assertEquals(
            listOf("foreign", "locked", "duplicate", "unknown"),
            result.rejectedSkills.map { it.substringBefore('[') },
        )
    }

    @Test
    fun legacyAndCanonicalDuplicatesKeepTheFirstValidMastery() {
        val state = stateAtLevel(HeroClass.RANGER, 10L)
        val learned = state.skills[1]
        state.skills = mutableListOf(
            learned.copy(catalogId = "", usageCount = 200L),
            learned.copy(usageCount = LearnedSkill.MAX_USAGE_COUNT),
        )

        val result = ArenaTurnInputAdapter.fromState(state, "hero")

        assertEquals(learned.catalogId, result.fighter.attacks.single().id)
        assertEquals(1, result.fighter.attacks.single().masteryBonusPercent)
        assertTrue(result.rejectedSkills.single().startsWith("duplicate["))
    }

    @Test
    fun tiersNamesAndDamageComeFromDefinitionsNotSavedDisplayFields() {
        val state = stateAtLevel(HeroClass.PALADIN, 10L)
        val definitions = SkillCatalog.forClass(HeroClass.PALADIN).take(3)
        state.skills = state.skills.mapIndexed { index, learned ->
            learned.copy(id = 777, acquiredAtLevel = 999L, name = "old display $index")
        }.toMutableList()

        val result = ArenaTurnInputAdapter.fromState(state, "hero")

        assertEquals(listOf(1, 2, 3), result.fighter.attacks.map { it.tier })
        assertEquals(definitions.map { it.name }, result.fighter.attacks.map { it.name })
        assertEquals(
            definitions.map { it.damagePercentMin to it.damagePercentMax },
            result.fighter.attacks.map { it.sourceDamagePercentMin to it.sourceDamagePercentMax },
        )
        assertTrue(result.rejectedSkills.isEmpty())
    }

    @Test
    fun masteryMappingUsesBoundedUsageAndPercentagePoints() {
        val state = stateAtLevel(HeroClass.ROGUE, 1L)
        val original = state.skills.single()
        val cases = listOf(
            -1L to 0, 0L to 0, 99L to 0, 100L to 0, 199L to 0,
            200L to 1, 4_900L to 24, 9_900L to 50, Long.MAX_VALUE to 50,
        )
        cases.forEach { (usageCount, expectedBonus) ->
            state.skills = mutableListOf(original.copy(usageCount = usageCount))

            val result = ArenaTurnInputAdapter.fromState(state, "hero")
            val attack = result.fighter.attacks.single()

            assertEquals("usage $usageCount", expectedBonus, attack.masteryBonusPercent)
            assertEquals(90, attack.sourceDamagePercentMin)
            assertEquals(100, attack.sourceDamagePercentMax)
            assertEquals(usageCount, state.skills.single().usageCount)
            assertTrue(result.rejectedSkills.isEmpty())
        }
    }

    @Test
    fun fighterIdentityAndAllEightStatsAreCopiedWithoutNormalization() {
        val state = stateAtLevel(HeroClass.PALADIN, 20L)

        val result = ArenaTurnInputAdapter.fromState(state, "stable-player-id")

        assertEquals("stable-player-id", result.fighter.id)
        assertEquals(HeroClass.PALADIN, result.fighter.heroClass)
        assertEquals(20L, result.fighter.level)
        assertEquals(state.hero.stats.values().map { it.toDouble() }, result.fighter.stats.values())
    }

    @Test
    fun `arena combat power uses the same bounded stat derivation as a public projection`() {
        val state = stateAtLevel(HeroClass.PALADIN, 20L)
        state.skills[0] = state.skills[0].copy(usageCount = 9_900L)
        val averagePower = (1L + (state.hero.level - 1L) * 5L) * 2L
        val powers = listOf(
            averagePower * 8L / 10L,
            averagePower,
            averagePower * 12L / 10L,
        )

        val derived = powers.map { power ->
            val local = requireNotNull(ArenaTurnInputAdapter.fromStateForCombatPower(
                state = state,
                id = "local-$power",
                effectiveCombatPower = power,
            ))
            val publicFormula = requireNotNull(PublicPlayerBattleDerivation.deriveStats(
                heroClass = state.hero.heroClass,
                level = state.hero.level,
                combatPower = power,
                rawStats = state.hero.stats,
            ))
            assertEquals(
                publicFormula.values().map(Long::toDouble),
                local.fighter.stats.values(),
            )
            val remote = requireNotNull(PublicPlayerArenaInputAdapter.fromSnapshot(
                snapshot = PublicPlayerSnapshot(
                    projectionId = "123e4567-e89b-42d3-a456-426614174000",
                    displayName = "Parity",
                    heroClass = state.hero.heroClass,
                    level = state.hero.level,
                    combatPower = power,
                    rulesVersion = SHARED_PLAYER_RULES_VERSION,
                    snapshotVersion = SHARED_PLAYER_SNAPSHOT_VERSION,
                    stats = PublicPlayerStats(
                        strength = state.hero.stats.strength,
                        constitution = state.hero.stats.constitution,
                        dexterity = state.hero.stats.dexterity,
                        intelligence = state.hero.stats.intelligence,
                        wisdom = state.hero.stats.wisdom,
                        charisma = state.hero.stats.charisma,
                        maxHealth = state.hero.stats.maxHealth,
                        maxMana = state.hero.stats.maxMana,
                    ),
                    adventureTraitIds = emptyList(),
                ),
                arenaLevel = 1,
                stableSeed = 101L,
            )).combat
            assertEquals(local.fighter.stats, remote.fighter.stats)
            assertEquals(state.skills.map { it.catalogId }, local.fighter.attacks.map { it.id })
            assertTrue(local.fighter.attacks.all { it.masteryBonusPercent == 0 })
            local.fighter.stats.values()
        }

        derived[0].zip(derived[1]).forEach { (low, average) -> assertTrue(low <= average) }
        derived[1].zip(derived[2]).forEach { (average, high) -> assertTrue(average <= high) }
        assertTrue(derived[0] != derived[1])
        assertTrue(derived[1] != derived[2])
    }

    @Test
    fun buildingInputDoesNotMutateAnySerializedStateOrReplaceItsSkillList() {
        val state = stateAtLevel(HeroClass.WARRIOR, 10L)
        state.skills += state.skills.first().copy(
            catalogId = "", id = 888, usageCount = Long.MAX_VALUE,
        )
        state.skills += state.skills.first().copy(catalogId = "unknown_catalog_id")
        val sourceList = state.skills
        val before = Json.encodeToString(state)

        ArenaTurnInputAdapter.fromState(state, "hero")

        assertSame(sourceList, state.skills)
        assertEquals(before, Json.encodeToString(state))
    }

    @Test
    fun returnedSnapshotDoesNotAliasMutableSourceStatsOrSkills() {
        val state = stateAtLevel(HeroClass.MAGE, 10L)
        val result = ArenaTurnInputAdapter.fromState(state, "hero")
        val expectedIds = result.fighter.attacks.map { it.id }
        val expectedStats = result.fighter.stats.values()

        state.skills.clear()
        state.hero.stats.intelligence += 100L
        state.hero.level = 200L

        assertEquals(expectedIds, result.fighter.attacks.map { it.id })
        assertEquals(expectedStats, result.fighter.stats.values())
        assertEquals(10L, result.fighter.level)
    }

    /**
     * The original engine creates the fixture and repairs its level-appropriate catalog.
     * Only one millisecond is settled, before the opening ends. This is an adapter fixture,
     * not a replay of level growth or evidence of a realistic high-level stat distribution.
     */
    private fun stateAtLevel(heroClass: HeroClass, level: Long): SimpleGameState {
        val roll = engine.rollStats(73L, heroClass)
        val now = 1_000L
        val state = engine.newGame(
            name = "AdapterFixture",
            heroClass = heroClass,
            rolledStats = roll.stats,
            seed = roll.nextSeed,
            now = now,
        )
        state.hero.level = level
        engine.settle(state, now + 1L)
        return state
    }
}
