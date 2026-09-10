package com.nullplaying.engine

import com.nullplaying.model.AdventureEventOutcome
import com.nullplaying.model.AdventureEventStat
import com.nullplaying.model.AdventurePhase
import com.nullplaying.model.CombatPhase
import com.nullplaying.model.HeroClass
import com.nullplaying.model.LearnedSkill
import com.nullplaying.model.MonsterGrade
import com.nullplaying.model.SimpleGameState
import com.nullplaying.model.TaleKind
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import kotlin.math.max
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Paired, synthetic starting states; this is not a replay of production player saves.
 * Writes only to the surrounding preintegration staging directory. No service, DB or Android API.
 * Economic differences are reported, not accepted by widening an expected-value assertion.
 */
class AdventureBalanceProbeTest {
    private data class Scenario(val id: String, val level: Long, val depth: Long = 0L)

    private data class Row(
        val scenario: String,
        val heroClass: String,
        val seed: Long,
        val mode: String,
        val startLevel: Long,
        val endLevel: Long,
        val startDepth: Long,
        val endDepth: Long,
        val hours: Double,
        val xp: Long,
        val items: Long,
        val equipment: Long,
        val lootUpgrades: Long,
        val shopUpgrades: Long,
        val earnedGold: Long,
        val events: Long,
        val kills: Long,
        val progress: Long,
        val returns: Long,
        val acts: Long,
        val successes: Long,
        val partials: Long,
        val failures: Long,
        val primaryCounts: Map<AdventureEventStat, Long>,
        val equipmentRarityCounts: Map<String, Long>,
        val endingPower: Long,
        val actionSteps: Long,
    ) {
        fun metrics(): List<Double> = listOf(
            xp / hours, items / hours, equipment / hours, lootUpgrades / hours,
            shopUpgrades / hours, earnedGold / hours, events / hours, kills / hours,
            progress / hours, returns / hours,
        )

        fun cells(): List<Any> = listOf(
            scenario, heroClass, seed, mode, startLevel, endLevel, startDepth, endDepth,
            hours, xp, items, equipment, lootUpgrades, shopUpgrades, earnedGold, events,
            kills, progress, returns, acts, successes, partials, failures,
        ) + metrics() + AdventureEventStat.entries.map { primaryCounts[it] ?: 0L } +
            RARITIES.map { equipmentRarityCounts[it] ?: 0L } + listOf(endingPower, actionSteps)
    }

    @Test(timeout = 300_000L)
    fun `paired adventure time cohort reports growth drops upgrades and event exposure`() {
        val qa = stagingQaDirectory()
        val hours = System.getProperty("adventure.balance.hours", "2").toInt()
        require(hours in 1..6) { "Use one to six bounded adventure hours." }
        val duration = hours * HOUR_MILLIS
        val rows = mutableListOf<Row>()
        try {
            SCENARIOS.forEach { scenario ->
                HeroClass.entries.forEach { heroClass ->
                    SEEDS.forEach { seed ->
                        listOf(false, true).forEach { enabled ->
                            val row = measure(scenario, heroClass, seed, enabled, duration)
                            rows += row
                            assertTrue("${row.scenario}/${row.mode}: finite rates", row.metrics().all { it.isFinite() && it >= 0.0 })
                            assertTrue("${row.scenario}/${row.mode}: real combat still occurs", row.kills > 0L)
                            assertTrue("${row.scenario}/${row.mode}: item accounting", row.equipment <= row.items)
                            assertTrue("${row.scenario}/${row.mode}: drops pay at most once", row.items <= row.kills + row.events)
                            assertEquals("${row.scenario}/${row.mode}: every event has one outcome",
                                row.events, row.successes + row.partials + row.failures)
                            if (!enabled) assertEquals("OFF must not resolve events", 0L, row.events)
                            if (enabled && scenario.depth == 0L) {
                                assertTrue("${row.scenario}: ordinary adventure reaches events", row.events > 0L)
                            }
                            // This bounds event spam; it is not an approval threshold for content pacing.
                            assertTrue("${row.scenario}: event schedule respects its minimum gap",
                                row.events <= duration / AdventureEventEngine.MIN_INTERVAL_MILLIS + 1L)
                        }
                    }
                }
            }
            assertEquals(SCENARIOS.size * HeroClass.entries.size * SEEDS.size * 2, rows.size)
        } finally {
            writeReports(qa, rows)
        }
        println("Adventure balance probe: ${rows.size} paired-mode rows; ${hours}h per row; ${qa.resolve("adventure-balance-probe.csv")}")
    }

    @Test
    fun `due events cannot replace a final chapter boss or labyrinth gate boss`() {
        listOf(Scenario("chapter_final_gate", 50L), Scenario("labyrinth_final_gate", 100L, 100L)).forEach { scenario ->
            val engine = SimpleGameEngine(enableAdventureEvents = true)
            val state = fixture(scenario, HeroClass.WARRIOR, SEEDS.first())
            val tale = state.adventureTale
            tale.currentActIndex = tale.acts.lastIndex
            tale.acts.dropLast(1).forEach { it.progress = it.target; it.completed = true }
            tale.activeAct().progress = tale.activeAct().target - 1L
            state.adventureJourney.initialized = true
            state.adventureJourney.nextEventAt = 0L
            state.adventurePhase = AdventurePhase.LOOTING
            state.actionStartedAt = 0L
            state.actionEndsAt = 1L
            val progress = tale.activeAct().progress
            val talesBefore = state.totalTales

            engine.settleOffline(state, 1L)

            assertEquals(scenario.id, AdventurePhase.COMBAT, state.adventurePhase)
            assertEquals(scenario.id, CombatPhase.REVEAL, state.combatPhase)
            assertEquals(scenario.id, MonsterGrade.BOSS, state.monster.grade)
            assertTrue(scenario.id, state.monster.isFinalBoss)
            assertEquals(scenario.id, progress, state.adventureTale.activeAct().progress)
            assertEquals(scenario.id, talesBefore, state.totalTales)
            assertEquals(scenario.id, 0L, state.adventureJourney.completedEvents)
            assertEquals(scenario.id, 0L, state.totalKills)
            if (scenario.depth > 0L) assertTrue(scenario.id, state.monster.isLabyrinthGateBoss)
        }
    }

    private fun measure(
        scenario: Scenario, heroClass: HeroClass, seed: Long, enabled: Boolean, duration: Long,
    ): Row {
        val engine = SimpleGameEngine(enableAdventureEvents = enabled)
        val state = fixture(scenario, heroClass, seed)
        var xp = 0L
        var earnedGold = 0L
        var progress = 0L
        var equipment = 0L
        var steps = 0L
        val outcomes = AdventureEventOutcome.entries.associateWith { 0L }.toMutableMap()
        val primary = AdventureEventStat.entries.associateWith { 0L }.toMutableMap()
        val rarity = RARITIES.associateWith { 0L }.toMutableMap()

        while (state.actionEndsAt <= duration) {
            val at = state.actionEndsAt
            val beforeLevel = state.hero.level
            val beforeXp = state.hero.experience
            val beforeGold = state.hero.gold
            val beforeItems = state.totalItemsFound
            val beforeEvents = state.adventureJourney.completedEvents
            val beforeTale = state.adventureTale
            val beforeAct = beforeTale.activeAct()
            val beforeActKey = "${beforeTale.sequence}:${beforeTale.definitionId}:${beforeTale.currentActIndex}:${beforeAct.id}"
            val beforeProgress = beforeAct.progress
            val beforeTarget = beforeAct.target
            val delta = engine.settleOffline(state, at)
            steps += 1L
            assertTrue("$scenario/$heroClass/$seed: bounded action loop", steps < 500_000L)
            assertTrue("$scenario/$heroClass/$seed: action clock advances at $at", state.actionEndsAt > at)
            assertEquals("$scenario: one boundary settled", at, state.lastSettledAt)
            assertTrue("$scenario: nonnegative gold", state.hero.gold >= 0L)

            xp += state.hero.experience - beforeXp
            for (level in beforeLevel until state.hero.level) xp += engine.experienceRequired(level)
            earnedGold += max(0L, state.hero.gold - beforeGold)

            val itemDelta = state.totalItemsFound - beforeItems
            // Stage 1 permits one item per settlement. A future multi-loot stage must update this probe.
            assertTrue("$scenario: stage-1 item event cardinality", itemDelta in 0L..1L)
            if (itemDelta == 1L && state.lastLootKind == "장비") {
                equipment += 1L
                rarity[state.lastLootRarity] = (rarity[state.lastLootRarity] ?: 0L) + 1L
            }

            val afterTale = state.adventureTale
            val afterAct = afterTale.activeAct()
            val afterActKey = "${afterTale.sequence}:${afterTale.definitionId}:${afterTale.currentActIndex}:${afterAct.id}"
            progress += if (beforeActKey == afterActKey) max(0L, afterAct.progress - beforeProgress)
                else max(0L, beforeTarget - beforeProgress) + afterAct.progress

            val eventDelta = state.adventureJourney.completedEvents - beforeEvents
            assertTrue("$scenario: event reward cardinality", eventDelta in 0L..1L)
            if (eventDelta == 1L) {
                val result = requireNotNull(state.adventureJourney.lastResult)
                outcomes[result.run.outcome] = outcomes.getValue(result.run.outcome) + 1L
                primary[result.run.primaryStat] = primary.getValue(result.run.primaryStat) + 1L
                assertEquals("$scenario: an event is not a monster kill", 0L, delta.defeatedMonsters)
                assertTrue("$scenario: event cannot complete a boss-gated act", afterAct.progress < afterAct.target)
            }
        }
        // Include the uncompleted tail in the denominator, without fabricating another action.
        engine.settleOffline(state, duration)
        assertEquals(duration, state.lastSettledAt)
        assertTrue(state.adventureJourney.recentResults.size <= AdventureEventEngine.HISTORY_LIMIT)
        return Row(
            scenario.id, heroClass.name, seed, if (enabled) "ON" else "OFF", scenario.level,
            state.hero.level, scenario.depth, state.adventureTale.labyrinthDepth,
            duration.toDouble() / HOUR_MILLIS, xp, state.totalItemsFound, equipment,
            state.totalLootEquipmentEquips, state.totalEquipmentPurchases, earnedGold,
            state.adventureJourney.completedEvents, state.totalKills, progress, state.totalReturns,
            state.totalActs, outcomes.getValue(AdventureEventOutcome.SUCCESS),
            outcomes.getValue(AdventureEventOutcome.PARTIAL), outcomes.getValue(AdventureEventOutcome.FAILURE),
            primary, rarity, engine.displayCombatPower(state), steps,
        )
    }

    private fun fixture(scenario: Scenario, heroClass: HeroClass, seed: Long): SimpleGameState {
        val engine = SimpleGameEngine()
        val rolled = engine.rollStats(seed, heroClass)
        val state = engine.newGame("격리 균형 표본", heroClass, rolled.stats, rolled.nextSeed, 0L)
        var growthSeed = seed xor 0x62A4_1875L
        repeat((scenario.level - 1L).toInt()) {
            growthSeed = engine.applyClassGuidedGrowth(state.hero.stats, heroClass, growthSeed)
        }
        state.hero.level = scenario.level
        state.classGuidedLevelGrowths = scenario.level - 1L
        state.hero.experience = 0L
        state.hero.gold = 0L
        state.equipment.forEach { item ->
            val spread = (seed * 17L + item.slot.ordinal * 7L) % 9L - 4L
            item.power = max(1L, engine.expectedEquipmentCombatPower(scenario.level) + spread)
            item.acquiredAtLevel = scenario.level
            item.rarity = "일반"
        }
        state.skills = (1..(scenario.level / 5L + 1L).coerceAtMost(SimpleGameEngine.MAX_SKILLS.toLong()).toInt()).map { tier ->
            val definition = SkillCatalog.select(state.skillCatalogSeed, heroClass, tier)
            LearnedSkill(tier, definition.name, if (tier == 1) 1L else (tier - 1L) * 5L,
                definition.description, definition.catalogId, usageCount = 0L)
        }.toMutableList()
        val definition = when {
            scenario.depth > 0L -> LabyrinthTaleCatalog.definitionForDepth(scenario.depth)
            scenario.level == 1L -> StarterPrologueCatalog.forClass(heroClass)
            else -> AdventureTaleCatalog.mainTales[((scenario.level - 1L) / 2L).toInt()
                .coerceIn(0, AdventureTaleCatalog.mainTales.lastIndex)]
        }
        state.adventureTale = AdventureTaleCatalog.instantiate(
            definition, if (scenario.depth > 0L) 42L + scenario.depth else 1L,
            state.hero.name, scenario.level, AdventureTaleCatalog.variantAt((seed % 6L).toInt()), scenario.depth,
        )
        state.labyrinthDepthCompleted = (scenario.depth - 1L).coerceAtLeast(0L)
        // Enter the shared ordinary-next-step path; avoid a fabricated already-running monster.
        state.adventurePhase = AdventurePhase.DEPARTING
        state.actionStartedAt = 0L
        state.actionEndsAt = 1L
        state.lastSettledAt = 0L
        return state
    }

    private fun stagingQaDirectory(): Path {
        val cwd = Paths.get(System.getProperty("user.dir")).toRealPath()
        val project = generateSequence(cwd) { it.parent }.firstOrNull { candidate ->
            candidate.fileName?.toString() == "project" &&
                candidate.parent?.fileName?.toString() == "adventure-20260906" &&
                candidate.parent?.parent?.fileName?.toString() == "preintegration" &&
                Files.isRegularFile(candidate.resolve("app/build.gradle.kts"))
        } ?: error("Run only in output/preintegration/adventure-20260906/project; never write into original audits.")
        val staging = project.parent.toRealPath()
        val qa = staging.resolve("qa")
        require(!Files.isSymbolicLink(qa)) { "QA output must not be redirected outside staging." }
        Files.createDirectories(qa)
        require(qa.toRealPath().parent == staging) { "QA output escaped staging." }
        return qa
    }

    private fun writeReports(qa: Path, rows: List<Row>) {
        val header = listOf(
            "scenario", "class", "seed", "mode", "start_level", "end_level", "start_depth", "end_depth",
            "adventure_hours", "xp", "items", "equipment", "loot_upgrades", "shop_upgrades", "earned_gold",
            "events", "kills", "progress_units", "returns", "acts", "successes", "partials", "failures",
        ) + RATE_COLUMNS + AdventureEventStat.entries.map { "primary_${it.name.lowercase()}" } +
            listOf("equipment_common", "equipment_uncommon", "equipment_rare", "equipment_epic", "equipment_legendary", "equipment_mythic") +
            listOf("ending_combat_power", "action_steps")
        qa.resolve("adventure-balance-probe.csv").toFile().writeText(
            (listOf(header.joinToString(",")) + rows.map { row -> row.cells().joinToString(",", transform = ::cell) }).joinToString("\n") + "\n")
        val summary = rows.groupBy { it.scenario to it.mode }.toSortedMap(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
            .map { (key, samples) ->
                val averages = RATE_COLUMNS.indices.map { index -> samples.map { it.metrics()[index] }.average() }
                (listOf<Any>(key.first, key.second, samples.size) + averages).joinToString(",", transform = ::cell)
            }
        qa.resolve("adventure-balance-summary.csv").toFile().writeText(
            (listOf((listOf("scenario", "mode", "samples") + RATE_COLUMNS).joinToString(",")) + summary).joinToString("\n") + "\n")
        qa.resolve("adventure-balance-probe-notes.txt").toFile().writeText(
            "Synthetic paired starting states, not production saves. Six classes and eight fixed seeds.\n" +
                "Stats use class-guided growth; all unlocked skill tiers start at zero mastery usage.\n" +
                "Equipment starts near the level benchmark with a fixed small slot spread; gold and bag start empty.\n" +
                "Main chapters are selected by level; labyrinth scenarios start at depth 1 and 100.\n" +
                "Time is raw settled adventure time, including town actions; calendar charging is not modeled.\n" +
                "Equipment is counted at one-item acquisition boundaries using lastLootKind, including non-upgrades.\n" +
                "Loot and shop upgrade counts are separate engine counters. Rates are not approval thresholds.\n" +
                "Rows written: ${rows.size}; expected complete matrix: ${SCENARIOS.size * HeroClass.entries.size * SEEDS.size * 2}.\n")
    }

    private fun cell(value: Any): String = when (value) {
        is Double -> String.format(Locale.ROOT, "%.8f", value)
        else -> value.toString()
    }

    companion object {
        private const val HOUR_MILLIS = 3_600_000L
        private val SEEDS = listOf(7L, 19L, 41L, 77L, 131L, 503L, 1_021L, 2_047L)
        private val SCENARIOS = listOf(Scenario("level_1", 1L), Scenario("level_20", 20L),
            Scenario("level_50", 50L), Scenario("labyrinth_1", 60L, 1L), Scenario("labyrinth_100", 100L, 100L))
        private val RARITIES = listOf("일반", "고급", "희귀", "영웅", "전설", "신화")
        private val RATE_COLUMNS = listOf("xp_per_hour", "items_per_hour", "equipment_per_hour",
            "loot_upgrades_per_hour", "shop_upgrades_per_hour", "earned_gold_per_hour",
            "events_per_hour", "kills_per_hour", "progress_per_hour", "returns_per_hour")
    }
}
