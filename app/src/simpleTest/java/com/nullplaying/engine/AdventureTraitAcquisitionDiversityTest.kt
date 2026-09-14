package com.nullplaying.engine

import com.nullplaying.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AdventureTraitAcquisitionDiversityTest {
    private val hour = 3_600_000L
    private fun game(seed: Long = 71L): SimpleGameState {
        val engine = SimpleGameEngine(enableAdventureEvents = true, enableAdventureTraits = true)
        val rolled = engine.rollStats(seed, HeroClass.WARRIOR)
        return engine.newGame("trait-qa", HeroClass.WARRIOR, rolled.stats, rolled.nextSeed, 0L)
    }

    @Test fun `mandatory combat never forms grade traits and legacy ownership survives migration`() {
        val game = game()
        game.adventureTraits.acquisitionRulesVersion = 1
        game.adventureTraits.owned = listOf(AdventureOwnedTrait("C02"))
        game.adventureTraits.evidence = mapOf("C01" to List(12) {
            AdventureTraitEvidence("combat:$it", "grade:$it", true, "combat:grade")
        })
        AdventureTraitEngine.initialize(game)
        assertEquals(listOf("C02"), game.adventureTraits.owned.map { it.traitId })
        assertTrue(game.adventureTraits.evidence.getValue("C01").isEmpty())
        game.adventureTraits.owned = emptyList()
        repeat(300) { index ->
            game.monster.grade = MonsterGrade.entries[index % MonsterGrade.entries.size]
            AdventureTraitEngine.beginSource(game, "combat", "monster:${index % 8}", index * hour)
            AdventureTraitEngine.observeCombat(game, index * hour)
            AdventureTraitEngine.finalizeEvidence(game, index * hour)
        }
        assertTrue(game.adventureTraits.evidence["C01"].orEmpty().isEmpty())
        assertTrue(game.adventureTraits.evidence["C02"].orEmpty().isEmpty())
        assertFalse(game.adventureTraits.owned.any { it.traitId in setOf("C01", "C02") })
    }

    @Test fun `mature accumulated candidate can form on another source and keeps its own reason`() {
        val game = game()
        game.adventureTraits.formationStartedAtByTrait = mapOf("R01" to 0L)
        game.adventureTraits.evidence = mapOf("R01" to List(8) {
            AdventureTraitEvidence("old:$it", "help:${it % 3}", true, "relationship:help", hour)
        })
        AdventureTraitEngine.beginSource(game, "combat", "monster", 8 * hour)
        val key = game.adventureTraits.source!!.key
        game.adventureTraits.seed = (1L..1000L).first { AdventureTraitEngine.random(it, "FORMATION:$key") < 2500 }
        AdventureTraitEngine.observe(game, "$key:style", "combat", setOf("C03"), at = 8 * hour)
        AdventureTraitEngine.finalizeEvidence(game, 8 * hour)
        assertEquals("R01", game.adventureTraits.owned.single().traitId)
        assertEquals("relationship:help", game.adventureTraits.owned.single().lastChange!!.reasonKey)
        assertEquals(AdventureTraitEngine.ACQUISITION_RULES_VERSION, game.adventureTraits.owned.single().acquisitionRulesVersion)
        assertEquals(game, Json.decodeFromString<SimpleGameState>(Json.encodeToString(game)))
    }

    @Test fun `stale candidate and one repeated context cannot acquire`() {
        listOf(true, false).forEach { stale ->
            val game = game()
            game.adventureTraits.formationStartedAtByTrait = mapOf("R01" to 0L)
            game.adventureTraits.evidence = mapOf("R01" to List(12) {
                AdventureTraitEvidence("old:$it", if (stale) "help:${it % 3}" else "help:one", true,
                    "relationship:help", if (stale) 0L else 50 * hour)
            })
            AdventureTraitEngine.observe(game, "new", "other", setOf("C03"), at = 50 * hour)
            AdventureTraitEngine.finalizeEvidence(game, 50 * hour)
            assertTrue(game.adventureTraits.owned.isEmpty())
        }
    }

    @Test fun `grade formation uses diverse precombat decisions and never generic risky incidents`() {
        listOf(AdventureBehaviorSignal.TAKE_RISK to "C01", AdventureBehaviorSignal.CHECK_SAFETY to "C02").forEach { (signal, id) ->
            val contexts = mutableSetOf<String>()
            AdventureEventEngine.all.forEach { definition ->
                definition.approaches.filter { signal in it.behaviorSignals }.forEach { approach ->
                    val game = game()
                    val run = AdventureEventEngine.beginForQa(game, 0L, definition.id, actionMillis = 1000L)
                        .copy(approachId = approach.id, context = definition.context)
                    AdventureTraitEngine.planEvent(game, run)
                    AdventureTraitEngine.observeEvent(game, 0L)
                    AdventureTraitEngine.finalizeEvidence(game, 0L)
                    val signs = game.adventureTraits.evidence[id].orEmpty()
                    if (definition.context == AdventureEventContext.PRE_COMBAT) {
                        assertTrue(signs.single().positive)
                        contexts += signs.single().contextKey
                    } else assertTrue(signs.isEmpty())
                }
            }
            assertTrue("$id needs three real acquisition contexts: $contexts", contexts.size >= 3)
        }
    }

    @Test fun `automatic equipment alone is bounded corroboration without opposite evidence`() {
        val game = game()
        repeat(100) { index ->
            AdventureTraitEngine.beginSource(game, "combat", "monster:$index", index * hour)
            AdventureTraitEngine.observeEquipment(game, "gear:$index", EquipmentSlot.entries[index % EquipmentSlot.entries.size],
                1L, false, index * hour)
            AdventureTraitEngine.finalizeEvidence(game, index * hour)
        }
        assertEquals(4, game.adventureTraits.evidence.getValue("L05").size)
        assertTrue(game.adventureTraits.evidence["L06"].orEmpty().isEmpty())
        assertFalse(game.adventureTraits.owned.any { it.traitId == "L05" })
    }

    @Test fun `deliberate approach overrides automatic gear evidence in the same source`() {
        val game = game()
        AdventureTraitEngine.beginSource(game, "event", "gear", 0L)
        AdventureTraitEngine.observeEquipment(game, "auto", EquipmentSlot.WEAPON, 1L, false, 0L)
        AdventureTraitEngine.observe(game, "approach", "FIELD:gear", setOf("L06"), setOf("L05"), 0L, "adventure:signal")
        AdventureTraitEngine.finalizeEvidence(game, 0L)
        assertFalse(game.adventureTraits.evidence.getValue("L05").single().positive)
        assertEquals("adventure:signal", game.adventureTraits.evidence.getValue("L05").single().reasonKey)
        assertTrue(game.adventureTraits.evidence.getValue("L06").single().positive)
    }

    @Test fun `failed checks cannot reroll within thirty active minutes including save and pause`() {
        var game = game()
        game.adventureTraits.formationStartedAtByTrait = mapOf("R01" to 0L)
        game.adventureTraits.evidence = mapOf("R01" to List(8) {
            AdventureTraitEvidence("old:$it", "help:${it % 3}", true, "relationship:help", 0L)
        })
        game.adventureTraits.seed = (1L..1000L).first { AdventureTraitEngine.random(it, "FORMATION:check:0") >= 2500 }
        fun record(key: String, at: Long) {
            AdventureTraitEngine.observe(game, key, "other", setOf("C03"), at = at)
            AdventureTraitEngine.finalizeEvidence(game, at)
        }
        record("check:0", 8 * hour)
        assertEquals(1L, game.adventureTraits.opportunityCounts["FORMATION"])
        game = Json.decodeFromString(Json.encodeToString(game))
        AdventureTraitEngine.pause(game, 100 * hour)
        assertEquals(108 * hour, game.adventureTraits.lastFormationCheckAt)
        assertEquals(100 * hour, game.adventureTraits.evidence.getValue("R01").first().observedAt)
        record("check:1", 108 * hour + 1)
        assertEquals(1L, game.adventureTraits.opportunityCounts["FORMATION"])
        record("check:2", 108 * hour + AdventureTraitEngine.FORMATION_CHECK_INTERVAL_MILLIS)
        assertEquals(2L, game.adventureTraits.opportunityCounts["FORMATION"])
    }

    @Test fun `natural acquisition replay is identical through restarts and settlement splitting`() {
        val engine = SimpleGameEngine(enableAdventureEvents = true, enableAdventureTraits = true)
        val bulk = game()
        var split = Json.decodeFromString<SimpleGameState>(Json.encodeToString(bulk))
        val bulkEvents = engine.settleOffline(bulk, 24 * hour).recentEvents
        val splitEvents = mutableListOf<RecentAdventureEvent>()
        repeat(144) { step ->
            splitEvents += engine.settleOffline(split, (step + 1L) * 10 * 60_000L).recentEvents
            if (step % 12 == 0) split = Json.decodeFromString(Json.encodeToString(split))
        }
        assertTrue(bulk.adventureTraits.owned.isNotEmpty())
        assertEquals(bulk, split)
        assertEquals(bulkEvents, splitEvents)
    }

    @Test(timeout = 240_000L) fun `natural cohort first acquisitions are diverse across classes levels and seeds`() {
        val firsts = mutableListOf<String>()
        val all = mutableSetOf<String>()
        val byLevel = mutableMapOf<Long, MutableList<String>>()
        HeroClass.entries.forEach { heroClass ->
            listOf(1L, 20L, 100L).forEach { level ->
                repeat(8) { sample ->
                    val engine = SimpleGameEngine(enableAdventureEvents = true, enableAdventureRelationships = true, enableAdventureTraits = true)
                    val seed = AdventureQaFixtures.finalSeeds[sample] xor (heroClass.ordinal * 97409L + level * 1009L)
                    val game = AdventureQaFixtures.game(engine, heroClass, seed, level, if (level == 100L) 100L else 0L)
                    var first: String? = null
                    repeat(48) { step ->
                        if (sample < 4 && step % 24 == 0) game.adventureRelationships.roster = AdventureQaFixtures.roster(game,
                            AdventureQaFixtures.EPOCH + step * hour, hours = 24L)
                        engine.settleOffline(game, AdventureQaFixtures.EPOCH + (step + 1L) * hour)
                        val acquisitions = game.adventureTraits.recentChanges.filter {
                            it.kind == AdventureTraitChangeKind.ACQUIRED || it.kind == AdventureTraitChangeKind.REPLACED
                        }
                        if (first == null) first = acquisitions.firstOrNull()?.traitId
                        all += acquisitions.map { it.traitId }
                        assertTrue(acquisitions.all { it.occurredAt >= AdventureQaFixtures.EPOCH + 8 * hour })
                        val owned = game.adventureTraits.owned.map { it.traitId }.toSet()
                        assertTrue(owned.none { AdventureTraitCatalog.definition(it).oppositeId in owned })
                    }
                    first?.let { firsts += it; byLevel.getOrPut(level) { mutableListOf() } += it }
                }
            }
        }
        val counts = firsts.groupingBy { it }.eachCount().toSortedMap()
        println("TRAIT_DIVERSITY characters=144 firsts=${firsts.size} distribution=$counts all=$all")
        byLevel.forEach { (level, ids) ->
            val distribution = ids.groupingBy { it }.eachCount().toSortedMap()
            println("TRAIT_LEVEL level=$level firsts=${ids.size} distribution=$distribution")
            assertTrue("Level $level concentrated: $distribution", distribution.values.max() * 100 <= ids.size * 60)
        }
        assertTrue("Too few first acquisitions: $counts", firsts.size >= 50)
        assertTrue("Insufficient diversity: $counts", counts.size >= 4)
        assertTrue("Concentrated first acquisitions: $counts", counts.values.max() * 100 <= firsts.size * 60)
    }
}
