package com.nullplaying.engine

import com.nullplaying.model.*
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.*
import org.junit.Test

/** Read-only gameplay analysis: synthetic local saves; production rules are not changed. */
class AdventureTraitOwnershipAnalysisTest {
    private val hour = 3_600_000L
    private val epoch = AdventureQaFixtures.EPOCH
    private fun output() = Paths.get(System.getProperty("user.dir")).toAbsolutePath().let { cwd ->
        val root = generateSequence(cwd) { it.parent }.first { Files.exists(it.resolve("docs/SESSION_HANDOFF.md")) }
        root.resolve("output/trait-ownership-analysis/20260914/rules-${AdventureTraitEngine.ACQUISITION_RULES_VERSION}").also { Files.createDirectories(it) }
    }

    @Test(timeout = 600_000L) fun `measure fresh and legacy C02 cohorts through seven active days`() {
        val rows = mutableListOf<String>()
        val changes = mutableListOf<String>()
        val finalEvidence = mutableListOf<String>()
        rows += "cohort,class,start_level,sample,roster,hour,owned,acquired,replaced,lost,weakened,recovered,c02_owned,c02_shaky,c02_removed,ids"
        changes += "cohort,class,start_level,sample,hour,kind,trait,replaced_trait,reason"
        finalEvidence += "class,start_level,sample,c02_owned,c02_shaky,positive,negative,negative_contexts,c01_positive"
        listOf(false, true).forEach { legacy ->
            HeroClass.entries.forEach { heroClass ->
                listOf(1L, 20L, 100L).forEach { level ->
                    repeat(8) { sample ->
                        val cohort = if (legacy) "legacy_c02" else "fresh"
                        val engine = SimpleGameEngine(enableAdventureEvents = true, enableAdventureRelationships = true, enableAdventureTraits = true)
                        val seed = AdventureQaFixtures.finalSeeds[sample] xor (heroClass.ordinal * 97409L + level * 1009L)
                        val game = AdventureQaFixtures.game(engine, heroClass, seed, level, if (level == 100L) 100L else 0L)
                        if (legacy) {
                            game.adventureTraits.acquisitionRulesVersion = 1
                            game.adventureTraits.owned = listOf(AdventureOwnedTrait("C02", epoch - 48 * hour, 1L))
                            game.adventureTraits.changeSequence = 1L
                            game.adventureTraits.stableStartedAtByTrait = mapOf("C02" to epoch - 48 * hour)
                            game.adventureTraits.lastFormationAt = epoch - 48 * hour
                            game.adventureTraits.evidence = mapOf("C02" to List(12) {
                                AdventureTraitEvidence("combat:old:$it", "normal:$it", true, "combat:grade")
                            })
                            AdventureTraitEngine.initialize(game)
                            assertTrue(game.adventureTraits.owned.any { it.traitId == "C02" })
                        }
                        val counts = AdventureTraitChangeKind.entries.associateWith { 0 }.toMutableMap()
                        var sequence = game.adventureTraits.changeSequence
                        var c02Removed = false
                        for (h in 1..168) {
                            if (sample < 4 && (h - 1) % 24 == 0) game.adventureRelationships.roster = AdventureQaFixtures.roster(
                                game, epoch + (h - 1L) * hour, hours = 24L)
                            engine.settleOffline(game, epoch + h * hour)
                            val new = game.adventureTraits.recentChanges.filter { it.sequence > sequence }
                            assertEquals(game.adventureTraits.changeSequence - sequence, new.size.toLong())
                            sequence = game.adventureTraits.changeSequence
                            new.forEach { change ->
                                counts[change.kind] = counts.getValue(change.kind) + 1
                                if ((change.kind == AdventureTraitChangeKind.LOST && change.traitId == "C02") ||
                                    (change.kind == AdventureTraitChangeKind.REPLACED && change.replacedTraitId == "C02")) c02Removed = true
                                changes += "$cohort,${heroClass.name},$level,$sample,${(change.occurredAt - epoch).toDouble() / hour},${change.kind},${change.traitId},${change.replacedTraitId},${change.reasonKey}"
                            }
                            val owned = game.adventureTraits.owned
                            assertEquals((if (legacy) 1 else 0) + counts.getValue(AdventureTraitChangeKind.ACQUIRED) - counts.getValue(AdventureTraitChangeKind.LOST), owned.size)
                            assertTrue(owned.none { trait -> owned.any { it.traitId == AdventureTraitCatalog.definition(trait.traitId).oppositeId } })
                            if (h in setOf(8, 12, 24, 48, 72, 120, 168)) {
                                rows += listOf(cohort, heroClass.name, level, sample, sample < 4, h, owned.size,
                                    counts.getValue(AdventureTraitChangeKind.ACQUIRED), counts.getValue(AdventureTraitChangeKind.REPLACED),
                                    counts.getValue(AdventureTraitChangeKind.LOST), counts.getValue(AdventureTraitChangeKind.WEAKENED),
                                    counts.getValue(AdventureTraitChangeKind.RECOVERED), owned.any { it.traitId == "C02" },
                                    owned.any { it.traitId == "C02" && it.shaky }, c02Removed,
                                    owned.joinToString("|") { it.traitId }).joinToString(",")
                            }
                        }
                        if (legacy) {
                            val evidence = game.adventureTraits.evidence["C02"].orEmpty()
                            finalEvidence += listOf(heroClass.name, level, sample, game.adventureTraits.owned.any { it.traitId == "C02" },
                                game.adventureTraits.owned.any { it.traitId == "C02" && it.shaky }, evidence.count { it.positive },
                                evidence.count { !it.positive }, evidence.filterNot { it.positive }.map { it.contextKey }.distinct().size,
                                game.adventureTraits.evidence["C01"].orEmpty().count { it.positive }).joinToString(",")
                        }
                    }
                }
            }
        }
        val directory = output()
        Files.write(directory.resolve("checkpoints.csv"), rows)
        Files.write(directory.resolve("changes.csv"), changes)
        Files.write(directory.resolve("legacy-final-evidence.csv"), finalEvidence)
        println("Ownership analysis: 288 characters x 168 active hours. Evidence: $directory")
    }

    @Test fun `C02 can be lost after authored risk choices but not mandatory combat or idle time`() {
        fun game(): SimpleGameState {
            val engine = SimpleGameEngine(enableAdventureEvents = true, enableAdventureTraits = true)
            return AdventureQaFixtures.game(engine, HeroClass.WARRIOR, 71L, 20L).also {
                it.adventureTraits.owned = listOf(AdventureOwnedTrait("C02", epoch, 1L))
                it.adventureTraits.stableStartedAtByTrait = mapOf("C02" to epoch)
                // Isolate deterministic loss from the separate probabilistic replacement path.
                it.adventureTraits.lastFormationAt = epoch + 1000 * hour
            }
        }
        val combatOnly = game()
        repeat(100) { index ->
            val at = epoch + index * hour
            combatOnly.monster.grade = MonsterGrade.entries[index % MonsterGrade.entries.size]
            AdventureTraitEngine.beginSource(combatOnly, "combat", "monster:$index", at)
            AdventureTraitEngine.observeCombat(combatOnly, at)
            AdventureTraitEngine.finalizeEvidence(combatOnly, at)
        }
        assertFalse(combatOnly.adventureTraits.owned.single { it.traitId == "C02" }.shaky)
        AdventureTraitEngine.pause(combatOnly, 1000 * hour)
        assertTrue(combatOnly.adventureTraits.owned.any { it.traitId == "C02" })

        val risks = AdventureEventEngine.all.filter { it.context == AdventureEventContext.PRE_COMBAT }
            .flatMap { definition -> definition.approaches.filter { AdventureBehaviorSignal.TAKE_RISK in it.behaviorSignals }
                .map { definition to it } }.distinctBy { it.first.storyFamily }.take(3)
        assertEquals(3, risks.size)
        val game = game()
        listOf(0L, 4L, 8L, 12L, 24L, 32L, 71L, 72L).forEachIndexed { index, h ->
            val (definition, approach) = risks[index % risks.size]
            val at = epoch + h * hour
            val run = AdventureEventEngine.beginForQa(game, at, definition.id, actionMillis = 1000L)
                .copy(approachId = approach.id, context = definition.context)
            AdventureTraitEngine.planEvent(game, run)
            AdventureTraitEngine.observeEvent(game, at)
            AdventureTraitEngine.finalizeEvidence(game, at)
            game.adventureJourney.pending = null
            if (h < 24) assertFalse(game.adventureTraits.owned.single { it.traitId == "C02" }.shaky)
            if (h in 24..71) assertTrue(game.adventureTraits.owned.single { it.traitId == "C02" }.shaky)
        }
        assertFalse(game.adventureTraits.owned.any { it.traitId == "C02" })
        assertEquals(listOf(AdventureTraitChangeKind.WEAKENED, AdventureTraitChangeKind.LOST),
            game.adventureTraits.recentChanges.filter { it.traitId == "C02" }.map { it.kind })
        println("Controlled C02: unchanged by mandatory combat/idle; weakened at 24h and lost at 72h of authored risk choices.")
    }
}
