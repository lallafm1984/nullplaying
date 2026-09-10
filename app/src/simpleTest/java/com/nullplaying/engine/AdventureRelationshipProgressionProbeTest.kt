package com.nullplaying.engine

import com.nullplaying.model.AdventureEncounterCandidate
import com.nullplaying.model.AdventureEncounterRoster
import com.nullplaying.model.AdventureEventRewardKind
import com.nullplaying.model.AdventurePhase
import com.nullplaying.model.AdventureRelationshipBattleKind
import com.nullplaying.model.AdventureRelationshipEquipmentSnapshot
import com.nullplaying.model.AdventureRelationshipMemory
import com.nullplaying.model.AdventureRelationshipResult
import com.nullplaying.model.AdventureRelationshipTier
import com.nullplaying.model.EquipmentSlot
import com.nullplaying.model.HeroClass
import com.nullplaying.model.HeroStats
import com.nullplaying.model.LearnedSkill
import com.nullplaying.model.SimpleGameState
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Natural main-engine progression. This never forces an approach, score, outcome, or reunion. */
class AdventureRelationshipProgressionProbeTest {
    private data class Checkpoint(val values: List<Any>)
    private data class Meeting(val heroClass: HeroClass, val seed: Long, val pool: Int, val result: AdventureRelationshipResult)

    @Test(timeout = 300_000L)
    fun `single and twelve candidate pools reveal natural relationship progression over two active weeks`() {
        val qa = stagingQaDirectory()
        val checkpoints = mutableListOf<Checkpoint>()
        val meetings = mutableListOf<Meeting>()
        try {
            HeroClass.entries.forEach { heroClass ->
                SEEDS.forEach { seed ->
                    listOf(1, 12).forEach { pool ->
                        val engine = SimpleGameEngine(enableAdventureEvents = true, enableAdventureRelationships = true)
                        val game = fixture(engine, heroClass, seed)
                        val results = mutableListOf<AdventureRelationshipResult>()
                        var sourceValidMillis = 0L
                        val initialExpectedDelta = expectedDelta(game)
                        repeat(14) { publication ->
                            val receivedAt = EPOCH + publication * DAY
                            assertEquals(receivedAt, game.lastSettledAt)
                            game.adventureRelationships.roster = roster(game, receivedAt, publication, pool)
                            val end = receivedAt + DAY
                            var steps = 0L
                            while (game.actionEndsAt <= end) {
                                val at = game.actionEndsAt
                                sourceValidMillis += sourceValidTimeThrough(game, at)
                                val before = game.adventureRelationships.totalEncounters
                                engine.settleOffline(game, at)
                                assertTrue("Action clock advances", game.actionEndsAt > at)
                                assertTrue("Bounded daily segment", ++steps < 500_000L)
                                val awarded = game.adventureRelationships.totalEncounters - before
                                assertTrue(awarded in 0L..1L)
                                if (awarded == 1L) {
                                    val result = requireNotNull(game.adventureRelationships.lastResult)
                                    results += result
                                    meetings += Meeting(heroClass, seed, pool, result)
                                    assertTrue(result.run.candidate.level in
                                        (result.run.heroLevel - 1L)..(result.run.heroLevel + 1L))
                                    assertTrue(result.scoreAfter in -100..100)
                                    assertEquals(1, actualRewardKinds(result))
                                    if (result.run.battleKind != AdventureRelationshipBattleKind.NONE) {
                                        assertTrue(result.run.battleOutcome != null)
                                        assertEquals(null, result.run.localBattleSnapshot)
                                        assertEquals(null, result.run.opponentBattleSnapshot)
                                    }
                                }
                            }
                            sourceValidMillis += sourceValidTimeThrough(game, end)
                            engine.settleOffline(game, end)
                            val hours = (publication + 1L) * 24L
                            if (hours in CHECKPOINT_HOURS) {
                                checkpoints += checkpoint(game, heroClass, seed, pool, hours, sourceValidMillis,
                                    results, initialExpectedDelta)
                            }
                        }
                        assertEquals(game.adventureRelationships.totalEncounters, results.size.toLong())
                        assertTrue("A continuously replenished pool produces meetings", results.isNotEmpty())
                        assertEquals(results.size, results.map { it.run.sequence }.distinct().size)
                        assertTrue(results.size <= 336L * HOUR / AdventureRelationshipEngine.MIN_INTERVAL_MILLIS + 1L)
                        assertTrue(game.adventureRelationships.contacts.size <= pool)
                        assertTrue(game.adventureRelationships.recentResults.size <= AdventureRelationshipEngine.HISTORY_LIMIT)
                        game.adventureRelationships.contacts.forEach { contact ->
                            val history = results.filter { it.run.candidate.characterId == contact.characterId }
                            assertEquals(history.size.toLong(), contact.meetings)
                            assertEquals(history.first().occurredAt, contact.firstMetAt)
                            assertEquals(history.last().scoreAfter, contact.score)
                            assertEquals(history.takeLast(AdventureRelationshipEngine.MAX_MEMORIES_PER_CONTACT).map { result ->
                                AdventureRelationshipMemory(result.run.sequence, result.run.sceneId, result.run.approachId,
                                    result.run.outcome, result.occurredAt, result.run.scoreBefore,
                                    result.scoreAfter - result.run.scoreBefore, result.scoreAfter)
                            }, contact.memories)
                            history.zipWithNext().forEach { (before, after) ->
                                assertTrue("Twelve-hour cooldown follows completion, not merely start",
                                    after.run.startedActiveMillis - before.run.startedActiveMillis >=
                                        AdventureRelationshipEngine.REUNION_COOLDOWN_MILLIS + before.run.durationMillis)
                            }
                        }
                    }
                }
            }
        } finally {
            writeReports(qa, checkpoints, meetings)
        }
        assertEquals(6 * SEEDS.size * 2 * CHECKPOINT_HOURS.size, checkpoints.size)
        assertTrue("Complete public snapshots exercise authored relationship combat", meetings.any {
            it.result.run.battleKind != AdventureRelationshipBattleKind.NONE
        })
    }

    private fun checkpoint(
        game: SimpleGameState, heroClass: HeroClass, seed: Long, pool: Int, hours: Long,
        sourceValidMillis: Long, results: List<AdventureRelationshipResult>, initialExpectedDelta: Double,
    ): Checkpoint {
        val contacts = game.adventureRelationships.contacts
        val sourceHours = sourceValidMillis.toDouble() / HOUR
        val positiveChoices = results.count { result ->
            AdventureRelationshipEngine.definition(result.run.sceneId).approaches.single {
                it.id == result.run.approachId
            }.affinityBias > 0
        }
        val cells = listOf<Any>(heroClass.name, seed, pool, hours, sourceHours, results.size,
            results.size / hours.toDouble(), if (sourceHours > 0.0) results.size / sourceHours else 0.0,
            results.count { it.run.reunion }, contacts.size, game.hero.level,
            results.count { it.rewardKind == AdventureEventRewardKind.EXPERIENCE },
            results.count { it.rewardKind == AdventureEventRewardKind.GOLD },
            results.count { it.rewardKind == AdventureEventRewardKind.ITEM },
            results.count { it.run.battleKind != AdventureRelationshipBattleKind.NONE },
            results.map { it.run.scoreDelta }.average().takeIf(Double::isFinite) ?: 0.0,
            results.map { it.scoreAfter - it.run.scoreBefore }.average().takeIf(Double::isFinite) ?: 0.0,
            if (results.isNotEmpty()) positiveChoices.toDouble() / results.size else 0.0,
            initialExpectedDelta, expectedDelta(game), contacts.minOfOrNull { it.score } ?: 0,
            contacts.maxOfOrNull { it.score } ?: 0, contacts.sumOf { it.memories.size },
            Json.encodeToString(game.adventureRelationships).toByteArray(Charsets.UTF_8).size)
        val currentTiers = AdventureRelationshipTier.entries.map { tier -> contacts.count { it.tier == tier } }
        val everTiers = AdventureRelationshipTier.entries.map { tier -> results.filter { it.tier == tier }
            .map { it.run.candidate.characterId }.distinct().size }
        val firstHours = AdventureRelationshipTier.entries.map { tier ->
            results.firstOrNull { it.tier == tier }?.let { (it.occurredAt - EPOCH).toDouble() / HOUR } ?: ""
        }
        return Checkpoint(cells + currentTiers + everTiers + firstHours)
    }

    /** Analytical expectation of the implemented natural selector, not a forced test outcome. */
    private fun expectedDelta(game: SimpleGameState): Double {
        val social = (game.hero.stats.charisma.coerceIn(0L, 1_000_000L) +
            game.hero.stats.wisdom.coerceIn(0L, 1_000_000L)) / 2L
        val expected = 10L + (game.hero.level.coerceIn(1L, 1_000_000L) - 1L) / 3L
        val adjustment = ((social - expected) / 10L).coerceIn(-1L, 1L)
        return AdventureRelationshipEngine.all.map { definition ->
            val weights = definition.approaches.map {
                ((AdventureEventEngine.stat(game.hero.stats, it.primaryStat).coerceIn(0L, 1_000_000L) * 7L +
                    AdventureEventEngine.stat(game.hero.stats, it.secondaryStat).coerceIn(0L, 1_000_000L) * 3L) / 10L)
                    .coerceIn(1L, 1_000_000L)
            }
            definition.approaches.indices.sumOf { index ->
                val approach = definition.approaches[index]
                val success = AdventureRelationshipEngine.successBasisPoints(game.hero.stats, game.hero.level, approach)
                val partial = minOf(2_500, 9_500 - success)
                val failure = 10_000 - success - partial
                val outcomeAffinity = (success * 3.0 + partial * 1.0 - failure * 3.0) / 10_000.0
                weights[index].toDouble() / weights.sum() *
                    (outcomeAffinity + approach.affinityBias.coerceIn(-2, 2) + adjustment)
            }
        }.average()
    }

    private fun sourceValidTimeThrough(game: SimpleGameState, end: Long): Long {
        val roster = game.adventureRelationships.roster ?: return 0L
        if (roster.candidates.none { it.characterId != game.rankingCharacterId &&
                it.level in (game.hero.level - 1L)..(game.hero.level + 1L) }) return 0L
        return (minOf(end, roster.validUntil) - maxOf(game.lastSettledAt, roster.receivedAt)).coerceAtLeast(0L)
    }

    private fun fixture(engine: SimpleGameEngine, heroClass: HeroClass, seed: Long): SimpleGameState {
        val rolled = engine.rollStats(seed, heroClass)
        val game = engine.newGame("장기 인연 표본", heroClass, rolled.stats, rolled.nextSeed, EPOCH)
        var growthSeed = seed xor 0x62A4_1875L
        repeat(49) { growthSeed = engine.applyClassGuidedGrowth(game.hero.stats, heroClass, growthSeed) }
        game.hero.level = 50L
        game.classGuidedLevelGrowths = 49L
        game.hero.experience = 0L
        game.hero.gold = 0L
        game.rankingCharacterId = "frequency-own-$seed"
        game.equipment.forEach { it.power = engine.expectedEquipmentCombatPower(50L); it.acquiredAtLevel = 50L }
        game.skills = (1..SimpleGameEngine.MAX_SKILLS).map { tier ->
            val skill = SkillCatalog.select(game.skillCatalogSeed, heroClass, tier)
            LearnedSkill(tier, skill.name, if (tier == 1) 1L else (tier - 1L) * 5L,
                skill.description, skill.catalogId, usageCount = 0L)
        }.toMutableList()
        val tale = AdventureTaleCatalog.mainTales[24.coerceAtMost(AdventureTaleCatalog.mainTales.lastIndex)]
        game.adventureTale = AdventureTaleCatalog.instantiate(tale, 1L, game.hero.name, game.hero.level,
            AdventureTaleCatalog.variantAt((seed % 6L).toInt()), 0L)
        game.adventurePhase = AdventurePhase.DEPARTING
        game.actionStartedAt = EPOCH
        game.actionEndsAt = EPOCH + 1L
        game.lastSettledAt = EPOCH
        return game
    }

    private fun roster(game: SimpleGameState, at: Long, publication: Int, pool: Int) = AdventureEncounterRoster(
        "long-fixture-$publication", at, at + DAY, (0 until pool).map { index ->
            val heroClass = HeroClass.entries[index % HeroClass.entries.size]
            AdventureEncounterCandidate(
                characterId = "fixture-person-${index.toString().padStart(2, '0')}",
                displayName = "표본 모험가 $index",
                heroClass = heroClass,
                level = game.hero.level,
                combatPower = game.hero.level * 10L,
                stats = candidateStats(game.hero.level, heroClass),
                equipment = EquipmentSlot.entries.map { slot ->
                    AdventureRelationshipEquipmentSnapshot(
                        slot,
                        "${slot.labelKo} 표본",
                        game.hero.level * 2L,
                        "일반",
                    )
                },
            )
        })

    private fun candidateStats(level: Long, heroClass: HeroClass): HeroStats {
        val base = 10L + level / 3L
        val values = LongArray(6) { base }
        values[heroClass.primaryStatIndex] += 5L
        values[heroClass.secondaryStatIndex] += 3L
        return HeroStats(
            values[0], values[1], values[2], values[3], values[4], values[5],
            100L + level * 5L, 40L + level * 2L,
        )
    }

    private fun actualRewardKinds(result: AdventureRelationshipResult): Int = listOf(
        result.rewardKind == AdventureEventRewardKind.EXPERIENCE && result.experienceAwarded > 0L,
        result.rewardKind == AdventureEventRewardKind.GOLD && result.goldAwarded > 0L,
        result.rewardKind == AdventureEventRewardKind.ITEM && result.itemName.isNotBlank(),
    ).count { it }

    private fun stagingQaDirectory(): Path {
        val cwd = Paths.get(System.getProperty("user.dir")).toRealPath()
        val project = generateSequence(cwd) { it.parent }.firstOrNull {
            it.fileName?.toString() == "project" && it.parent?.fileName?.toString() == "adventure-20260906" &&
                it.parent?.parent?.fileName?.toString() == "preintegration" && Files.isRegularFile(it.resolve("app/build.gradle.kts"))
        } ?: error("Run only in the isolated preintegration project.")
        val staging = project.parent.toRealPath()
        val qa = staging.resolve("qa")
        require(!Files.isSymbolicLink(qa))
        Files.createDirectories(qa)
        require(qa.toRealPath().parent == staging)
        return qa
    }

    private fun writeReports(qa: Path, rows: List<Checkpoint>, meetings: List<Meeting>) {
        val header = listOf("class", "seed", "pool", "active_hours", "source_valid_active_hours", "encounters",
            "encounters_per_hour", "encounters_per_source_valid_hour", "reunions", "contacts", "hero_level",
            "xp_rewards", "gold_rewards", "item_rewards", "battle_encounters",
            "mean_raw_score_delta", "mean_applied_score_delta", "positive_approach_fraction",
            "initial_expected_delta", "checkpoint_expected_delta", "min_contact_score", "max_contact_score",
            "retained_memories", "relationship_json_bytes") +
            AdventureRelationshipTier.entries.map { "current_${it.name.lowercase()}" } +
            AdventureRelationshipTier.entries.map { "ever_${it.name.lowercase()}" } +
            AdventureRelationshipTier.entries.map { "first_${it.name.lowercase()}_hour" }
        qa.resolve("adventure-relationship-progression.csv").toFile().writeText(
            (listOf(header.joinToString(",")) + rows.map { it.values.joinToString(",", transform = ::cell) }).joinToString("\n") + "\n")
        qa.resolve("adventure-relationship-progression-meetings.csv").toFile().writeText(
            (listOf("class,seed,pool,sequence,candidate_id,active_hour,scene,approach,outcome,reunion,reward_kind,xp,gold,item,battle_kind,battle_outcome,score_before,score_delta,score_after,tier") +
                meetings.map { meeting ->
                    val run = meeting.result.run
                    listOf(meeting.heroClass.name, meeting.seed, meeting.pool, run.sequence, run.candidate.characterId,
                        (meeting.result.occurredAt - EPOCH).toDouble() / HOUR, run.sceneId, run.approachId, run.outcome,
                        run.reunion, meeting.result.rewardKind, meeting.result.experienceAwarded,
                        meeting.result.goldAwarded, meeting.result.itemName, run.battleKind,
                        run.battleOutcome ?: "", run.scoreBefore, run.scoreDelta,
                        meeting.result.scoreAfter, meeting.result.tier)
                        .joinToString(",", transform = ::cell)
                }).joinToString("\n") + "\n")
        qa.resolve("adventure-relationship-progression-notes.txt").toFile().writeText(
            "Synthetic main-engine replay. Six classes x three fixed seeds x pool sizes 1 and 12 x 336 active hours.\n" +
                "Fresh public fixture data follows old-data settlement once per 24h; fixture levels follow the current hero level.\n" +
                "The fixture supplies frozen public stats and all six equipment slots for battle eligibility; no remote behavior is inferred.\n" +
                "The current catalog has 50 situations: eight battle situations are available initially and two more require a reunion tier. Every completed meeting settles exactly one XP, gold, or item reward.\n" +
                "Checkpoints: 24,48,72,168,336h. Ever-tier columns count distinct IDs that entered that tier, so they overlap.\n" +
                "First-tier time is first actual completed meeting in that tier, not merely crossing a score threshold. Blank means not observed.\n" +
                "Approaches/outcomes/affinity remain natural. Expected delta is analytical weighting of the implemented selector; jitter has mean zero.\n" +
                "Single-candidate frequency is constrained by twelve active hours after completion, even though the global 4-8h schedule can become due earlier.\n" +
                "Score caps make mean applied delta differ from mean raw delta. Current tier need not equal the strongest tier ever reached.\n" +
                "Per-contact compact memories are checked against the independent full result stream, including actual clamped score delta.\n" +
                "Lifetime count/first meeting survive trimming; pending/lastResult/recent32 retain full results.\n" +
                "CSV checkpoint rows: ${rows.size}/180; actual meetings: ${meetings.size}. No assertion requires a natural extreme tier.\n")
    }

    private fun cell(value: Any): String = if (value is Double) String.format(Locale.ROOT, "%.8f", value) else value.toString()

    companion object {
        private const val EPOCH = 1_800_000_000_000L
        private const val HOUR = 3_600_000L
        private const val DAY = 24L * HOUR
        private val SEEDS = listOf(19L, 131L, 503L)
        private val CHECKPOINT_HOURS = setOf(24L, 48L, 72L, 168L, 336L)
    }
}
