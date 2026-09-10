package com.nullplaying.engine

import com.nullplaying.BuildConfig
import com.nullplaying.model.HeroClass
import com.nullplaying.model.HeroStats
import com.nullplaying.model.LearnedSkill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Inputs for the new PvP design, not a test of the old ProjectionBattleEngine's win rates.
 * Uses the current level-growth function in isolation: no adventure rewards, equipment,
 * production service, sampled player history, or legacy four-skill projection conversion.
 */
class PvpLevelBandInputAuditTest {
    private val engine = SimpleGameEngine()
    private val levelBands = (10..200 step 10).toList()
    private val samples = 128

    private data class Input(val stats: HeroStats, val skillIds: List<String>)

    private fun requireIsolatedVariant() {
        assumeTrue("Input audit is explicitly scoped to isolated QA variants",
            BuildConfig.BUILD_TYPE in setOf("battleQa", "offlineQa"))
        assertFalse("Run with testBattleQaUnitTest or testOfflineQaUnitTest", BuildConfig.REMOTE_SERVICES_ENABLED)
        assertEquals("", BuildConfig.SUPABASE_URL)
        assertEquals("", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
    }

    @Test
    fun catalogAndUnlockBoundariesPreserveEveryOwnedAttack() {
        requireIsolatedVariant()
        assertEquals(120, SkillCatalog.all.size)
        assertEquals(120, SkillCatalog.all.map { it.catalogId }.distinct().size)
        HeroClass.entries.forEach { heroClass ->
            val definitions = SkillCatalog.forClass(heroClass)
            assertEquals(listOf(1) + (5..95 step 5).toList(), definitions.map { it.unlockLevel })
            (1..200).forEach { level ->
                val owned = definitions.filter { it.unlockLevel <= level }
                assertEquals(minOf(20, 1 + level / 5), owned.size)
                assertTrue(owned.all { it.heroClass == heroClass && it.unlockLevel <= level })
                assertEquals(owned.size, owned.map { it.catalogId }.distinct().size)
            }
            definitions.forEachIndexed { index, skill ->
                // Current acquisition is deterministic, not a random candidate selection.
                assertEquals(skill.catalogId, SkillCatalog.select(1L, heroClass, index + 1).catalogId)
                assertEquals(skill.catalogId, SkillCatalog.select(987_654L, heroClass, index + 1).catalogId)
                val newlyLearned = LearnedSkill(
                    id = index + 1, name = skill.name, acquiredAtLevel = skill.unlockLevel.toLong(),
                    description = skill.description, catalogId = skill.catalogId, usageCount = 0,
                )
                assertEquals(1L, newlyLearned.level)
                println("pvp-input-attack class=${heroClass.name} id=${skill.catalogId} " +
                    "unlock=${skill.unlockLevel} tier=${index + 1} name=${skill.name} " +
                    "pveMin=${skill.damagePercentMin} pveMax=${skill.damagePercentMax} " +
                    "hits=${skill.hitCount} motion=${skill.motion} element=${skill.element}")
            }
        }
        println("pvp-input-contract levels=1..200 classes=6 catalog=120 " +
            "ownershipChecks=1200 source=deterministic-current-catalog battleSimulations=0")
    }

    @Test
    fun everyTenLevelsUsesIndependentGrowthAndCompleteUncappedSkillInputs() {
        requireIsolatedVariant()
        val fixtures = HeroClass.entries.associateWith {
            levelBands.associateWith { ArrayList<Input>(samples) }
        }
        HeroClass.entries.forEach { heroClass ->
            repeat(samples) { sample ->
                val roll = engine.rollStats(9_050_000L + sample, heroClass)
                val state = engine.newGame("Input audit", heroClass, roll.stats, roll.nextSeed, 1_000L)
                val stats = state.hero.stats.copy()
                val initialTotal = stats.values().take(6).sum()
                var growthSeed = state.rngState
                var previousHp = stats.maxHealth
                var previousMp = stats.maxMana
                (1..levelBands.last()).forEach { level ->
                    if (level > 1) growthSeed = engine.applyClassGuidedGrowth(stats, heroClass, growthSeed)
                    assertEquals(initialTotal + 2L * (level - 1), stats.values().take(6).sum())
                    assertTrue(stats.values().all { it > 0L })
                    assertTrue(stats.maxHealth >= previousHp && stats.maxMana >= previousMp)
                    previousHp = stats.maxHealth
                    previousMp = stats.maxMana
                    if (level in levelBands) {
                        val ids = SkillCatalog.forClass(heroClass)
                            .filter { it.unlockLevel <= level }.map { it.catalogId }
                        fixtures.getValue(heroClass).getValue(level) += Input(stats.copy(), ids)
                    }
                }
            }
        }

        levelBands.forEach { level ->
            HeroClass.entries.forEach { heroClass ->
                val inputs = fixtures.getValue(heroClass).getValue(level)
                assertEquals(samples, inputs.size)
                assertTrue(inputs.all { it.skillIds.size == minOf(20, 1 + level / 5) })
                val statMeans = (0..5).joinToString(",") { index ->
                    "%.3f".format(java.util.Locale.ROOT, inputs.map { it.stats.values()[index] }.average())
                }
                val mp = inputs.map { it.stats.maxMana }.sorted()
                val hp = inputs.map { it.stats.maxHealth }.sorted()
                val control = inputs.map {
                    val values = it.stats.values()
                    values[heroClass.primaryStatIndex] * .7 + values[heroClass.secondaryStatIndex] * .3
                }
                val resist = inputs.map { it.stats.constitution * .5 + it.stats.wisdom * .3 + it.stats.charisma * .2 }
                println("pvp-input-band level=$level class=${heroClass.name} samples=$samples " +
                    "owned=${inputs.first().skillIds.size} meanStats=$statMeans " +
                    "rawHpMedian=${hp[hp.size / 2]} rawMpP10=${mp[(mp.size * .1).toInt()]} " +
                    "rawMpMedian=${mp[mp.size / 2]} rawMpP90=${mp[(mp.size * .9).toInt()]} " +
                    "meanControl=${control.average()} meanResist=${resist.average()}")
            }
        }
        // A skill catalog ending at Lv.95 is not a hero growth cap at Lv.100.
        HeroClass.entries.forEach { heroClass ->
            val at100 = fixtures.getValue(heroClass).getValue(100)
            val at200 = fixtures.getValue(heroClass).getValue(200)
            at100.zip(at200).forEach { (before, after) ->
                assertTrue(after.stats.values().take(6).sum() > before.stats.values().take(6).sum())
                assertEquals(before.skillIds, after.skillIds)
            }
        }
        println("pvp-input-summary bands=${levelBands.size} levels=${levelBands.joinToString(",")} " +
            "classes=6 seedsPerClass=$samples snapshots=${6 * samples * levelBands.size} " +
            "fixtureKind=isolated-level-growth masteryScenario=newly-learned-1 battleSimulations=0")
    }
}
