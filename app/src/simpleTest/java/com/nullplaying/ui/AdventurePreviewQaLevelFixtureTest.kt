package com.nullplaying.ui

import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.engine.SkillCatalog
import com.nullplaying.model.HeroClass
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards QA-only adventure fixtures without launching Android, storage, or remote services. */
class AdventurePreviewQaLevelFixtureTest {
    @Test
    fun `relationship and trait searches project complete heroes instead of assigning raw levels`() {
        val activity = projectFile(
            "app/src/offlineQa/java/com/nullplaying/ui/AdventurePreviewQaActivity.kt",
        ).readText()
        val relationship = activity.substringAfter("private fun relationshipScenario(")
            .substringBefore("private fun traitScenario(")
        val trait = activity.substringAfter("private fun traitScenario(")
            .substringBefore("private fun traitBoundaryMayDecide(")

        listOf("relationship" to relationship, "trait" to trait).forEach { (name, source) ->
            assertTrue("$name must use coherent QA level projection", source.contains("projectArenaStateForQa("))
            assertFalse(
                "$name must not leave level, growth, stats, and skills out of sync",
                Regex("""\bstate\.hero\.level\s*=""").containsMatchIn(source),
            )
        }
    }

    @Test
    fun `QA projection keeps level growth stats and learned skills coherent for every class`() {
        val engine = SimpleGameEngine()
        val targetLevel = 20

        HeroClass.entries.forEach { heroClass ->
            val roll = engine.rollStats(91_000L + heroClass.ordinal, heroClass)
            val source = engine.newGame(
                name = "QA fixture",
                heroClass = heroClass,
                rolledStats = roll.stats,
                seed = roll.nextSeed,
                now = 1_000L,
            )
            val baseStats = source.hero.stats.values()

            val projected = projectArenaStateForQa(source, targetLevel)
            val expectedSkills = SkillCatalog.forClass(heroClass)
                .filter { it.unlockLevel <= targetLevel }

            assertEquals("$heroClass source level", 1L, source.hero.level)
            assertEquals("$heroClass source growth", 0L, source.classGuidedLevelGrowths)
            assertEquals("$heroClass source skills", 1, source.skills.size)
            assertEquals("$heroClass projected level", targetLevel.toLong(), projected.hero.level)
            assertEquals(
                "$heroClass projected growth",
                (targetLevel - 1).toLong(),
                projected.classGuidedLevelGrowths,
            )
            assertTrue(
                "$heroClass projected stats must grow",
                projected.hero.stats.values().zip(baseStats).any { (after, before) -> after > before },
            )
            assertTrue(
                "$heroClass projected stats must not regress",
                projected.hero.stats.values().zip(baseStats).all { (after, before) -> after >= before },
            )
            assertEquals(
                "$heroClass projected skills",
                expectedSkills.map { it.catalogId },
                projected.skills.map { it.catalogId },
            )
            assertEquals(
                "$heroClass projected skill levels",
                expectedSkills.map { it.unlockLevel.toLong() },
                projected.skills.map { it.acquiredAtLevel },
            )
        }
    }

    private fun projectFile(path: String): File {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "app/src/simple/java").isDirectory }
        return File(root, path)
    }
}
