package com.nullplaying.engine

import com.nullplaying.model.AdventureEncounterCandidate
import com.nullplaying.model.AdventureEncounterRoster
import com.nullplaying.model.AdventureEventRewardKind
import com.nullplaying.model.AdventurePhase
import com.nullplaying.model.AdventureRelationshipBattleKind
import com.nullplaying.model.AdventureRelationshipEquipmentSnapshot
import com.nullplaying.model.AdventureRelationshipResult
import com.nullplaying.model.AdventureRelationshipTier
import com.nullplaying.model.EquipmentSlot
import com.nullplaying.model.HeroClass
import com.nullplaying.model.HeroStats
import com.nullplaying.model.LearnedSkill
import com.nullplaying.model.MonsterGrade
import com.nullplaying.model.SimpleGameState
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic public candidates, never a claim about actual server availability or account data. */
class AdventureRelationshipFrequencyProbeTest {
    private data class Sample(
        val heroClass: HeroClass,
        val seed: Long,
        val game: SimpleGameState,
        val results: List<AdventureRelationshipResult>,
        val sourceValidActiveMillis: Long,
    )

    @Test(timeout = 300_000L)
    fun `natural encounters and remembered contacts are measured across forty eight active hours`() {
        val qa = stagingQaDirectory()
        val samples = mutableListOf<Sample>()
        try {
            HeroClass.entries.forEach { heroClass ->
                listOf(19L, 131L).forEach { seed ->
                    val engine = enabledEngine()
                    val game = fixture(engine, heroClass, seed)
                    val results = mutableListOf<AdventureRelationshipResult>()
                    var sourceValidActiveMillis = 0L
                    // One daily publication keeps a controlled pool available. Settle first,
                    // then install each publication; it cannot cause an encounter in past time.
                    repeat(2) { publication ->
                        val receivedAt = EPOCH + publication * DAY
                        assertEquals(receivedAt, game.lastSettledAt)
                        game.adventureRelationships.roster = roster(game, receivedAt, DAY, 12)
                        sourceValidActiveMillis += collectThrough(engine, game, receivedAt + DAY, results)
                    }
                    val sample = Sample(heroClass, seed, game, results, sourceValidActiveMillis)
                    samples += sample
                    assertEquals(game.adventureRelationships.totalEncounters, results.size.toLong())
                    assertTrue("At least one encounter with an available pool", results.isNotEmpty())
                    assertTrue("Minimum cadence prevents encounter spam", results.size <= 48L * HOUR / AdventureRelationshipEngine.MIN_INTERVAL_MILLIS + 1L)
                    assertTrue("Available pool does not silently starve", results.size >= sourceValidActiveMillis / AdventureRelationshipEngine.MAX_INTERVAL_MILLIS - 2L)
                    assertEquals("Every sequence is awarded once", results.size, results.map { it.run.sequence }.distinct().size)
                    results.zipWithNext().forEach { (before, after) ->
                        assertTrue("Global cadence uses active time", after.run.startedActiveMillis - before.run.startedActiveMillis >= AdventureRelationshipEngine.MIN_INTERVAL_MILLIS)
                    }
                    results.groupBy { it.run.candidate.characterId }.values.forEach { meetings ->
                        meetings.zipWithNext().forEach { (before, after) ->
                            assertTrue("Same character keeps its twelve-hour active cooldown",
                                after.run.startedActiveMillis - before.run.startedActiveMillis >= AdventureRelationshipEngine.REUNION_COOLDOWN_MILLIS)
                        }
                    }
                    results.forEach { result ->
                        assertTrue("Only actual level plus or minus one", result.run.candidate.level in
                            (result.run.heroLevel - 1L)..(result.run.heroLevel + 1L))
                        assertTrue("Snapshot must already be received", result.run.startedAt >=
                            EPOCH + result.run.snapshotId.substringAfterLast('-').toLong() * DAY)
                        assertEquals(1, actualRewardKinds(result))
                        if (result.run.battleKind != AdventureRelationshipBattleKind.NONE) {
                            assertTrue(result.run.battleOutcome != null)
                            assertEquals(null, result.run.localBattleSnapshot)
                            assertEquals(null, result.run.opponentBattleSnapshot)
                        }
                    }
                }
            }
        } finally {
            writeReports(qa, samples)
        }
    }

    @Test(timeout = 120_000L)
    fun `disabled missing empty expired future and plus or minus two pools cannot create contacts`() {
        listOf("disabled", "missing", "empty", "expired", "future", "plus_two", "minus_two", "self").forEach { mode ->
            val engine = if (mode == "disabled") SimpleGameEngine() else enabledEngine()
            val game = fixture(engine, HeroClass.WARRIOR, 41L)
            val valid = roster(game, EPOCH, 24L * HOUR, 12)
            game.adventureRelationships.roster = when (mode) {
                "missing" -> null
                "empty" -> valid.copy(candidates = emptyList())
                "expired" -> valid.copy(receivedAt = EPOCH - HOUR, validUntil = EPOCH)
                "future" -> valid.copy(receivedAt = EPOCH + 25L * HOUR, validUntil = EPOCH + 48L * HOUR)
                "plus_two" -> valid.copy(candidates = valid.candidates.map { it.copy(level = game.hero.level + 2L) })
                "minus_two" -> valid.copy(candidates = valid.candidates.map { it.copy(level = game.hero.level - 2L) })
                "self" -> valid.copy(candidates = listOf(valid.candidates.first().copy(characterId = game.rankingCharacterId)))
                else -> valid
            }
            // Bound out-of-range cases before any possible level transition into their pool.
            val duration = if (mode == "plus_two" || mode == "minus_two") 4L * HOUR else 24L * HOUR
            engine.settleOffline(game, EPOCH + duration)
            assertEquals(mode, 0L, game.adventureRelationships.totalEncounters)
            assertTrue(mode, game.adventureRelationships.contacts.isEmpty())
            assertEquals(mode, null, game.adventureRelationships.pending)
        }
    }

    @Test(timeout = 120_000L)
    fun `split online and offline settlement preserve encounters and do not pay twice`() {
        val engine = enabledEngine()
        val bulk = fixture(engine, HeroClass.RANGER, 503L)
        bulk.adventureRelationships.roster = roster(bulk, EPOCH, 24L * HOUR, 12)
        val split = restored(bulk)
        val online = restored(bulk)
        val end = EPOCH + 24L * HOUR - 1L
        engine.settleOffline(bulk, end)
        for (minute in 17L..(24L * 60L) step 17L) {
            val at = (EPOCH + minute * 60_000L).coerceAtMost(end)
            engine.settleOffline(split, at)
            engine.settle(online, at)
        }
        engine.settleOffline(split, end)
        engine.settleOffline(online, end)
        assertEquals("Partitioning time cannot reroll relationships", bulk, split)
        assertEquals("Online presentation does not change relationships", bulk.adventureRelationships, online.adventureRelationships)
        val saved = Json.encodeToString(bulk)
        repeat(3) { engine.settleOffline(bulk, end) }
        assertEquals("Repeated same-now settlement has no new award", saved, Json.encodeToString(bulk))
        val rebooted = restored(bulk)
        engine.settleOffline(rebooted, end)
        assertEquals("Reload cannot duplicate a completed result", bulk, rebooted)
        assertTrue(bulk.adventureRelationships.totalEncounters > 0L)
    }

    @Test(timeout = 120_000L)
    fun `seventy two empty charge hours expire the roster but do not clear a remembered cooldown`() {
        val engine = enabledEngine()
        val game = fixture(engine, HeroClass.PALADIN, 77L)
        game.adventureRelationships.roster = roster(game, EPOCH, 24L * HOUR, 1)
        while (game.adventureRelationships.totalEncounters == 0L) {
            assertTrue("A natural first meeting must occur", game.actionEndsAt < EPOCH + 9L * HOUR)
            engine.settleOffline(game, game.actionEndsAt)
        }
        val before = game.adventureRelationships.contacts.single()
        val activeBefore = AdventureRelationshipEngine.activeMillisAt(game.adventureRelationships, game.lastSettledAt)
        val scheduledBefore = game.adventureRelationships.nextEncounterAt
        val oldRoster = requireNotNull(game.adventureRelationships.roster)
        game.offlineAdventureMillis = 0L
        engine.settleOfflineWithOfflineAdventure(game, game.lastSettledAt + 72L * HOUR)
        assertEquals(1L, game.adventureRelationships.totalEncounters)
        assertEquals(before, game.adventureRelationships.contacts.single())
        assertEquals(activeBefore, AdventureRelationshipEngine.activeMillisAt(game.adventureRelationships, game.lastSettledAt))
        assertEquals(scheduledBefore + 72L * HOUR, game.adventureRelationships.nextEncounterAt)
        assertEquals("Real source expiry cannot move with the adventure clock", oldRoster, game.adventureRelationships.roster)
        assertTrue(AdventureRelationshipEngine.eligibleCandidates(game, game.lastSettledAt).isEmpty())
        game.adventureRelationships.roster = roster(game, game.lastSettledAt, 24L * HOUR, 1)
        assertTrue("Fresh data cannot bypass the active-time cooldown",
            AdventureRelationshipEngine.eligibleCandidates(game, game.lastSettledAt).isEmpty())
        val remaining = before.nextEligibleActiveMillis - activeBefore
        assertEquals(AdventureRelationshipEngine.REUNION_COOLDOWN_MILLIS, remaining)
        assertTrue(AdventureRelationshipEngine.eligibleCandidates(game, game.lastSettledAt + remaining - 1L).isEmpty())
        assertEquals(1, AdventureRelationshipEngine.eligibleCandidates(game, game.lastSettledAt + remaining).size)
    }

    @Test(timeout = 120_000L)
    fun `late roster receipt cannot alter a previously selected ordinary incident`() {
        val engine = enabledEngine()
        val game = fixture(engine, HeroClass.MAGE, 1_021L)
        while (game.adventureJourney.pending == null) {
            assertTrue(game.actionEndsAt < EPOCH + HOUR)
            engine.settleOffline(game, game.actionEndsAt)
        }
        val incident = requireNotNull(game.adventureJourney.pending)
        val completion = game.actionEndsAt
        engine.settleOffline(game, game.actionStartedAt + 1_000L)
        game.adventureRelationships.roster = roster(game, game.lastSettledAt, 24L * HOUR, 12)
        game.adventureRelationships.nextEncounterAt = game.lastSettledAt
        assertEquals(incident, game.adventureJourney.pending)
        engine.settleOffline(game, completion)
        assertEquals(incident, game.adventureJourney.lastResult?.run)
        assertEquals(0L, game.adventureRelationships.totalEncounters)
        assertEquals(null, game.adventureRelationships.pending)
        assertTrue(game.adventureRelationships.contacts.isEmpty())
    }

    @Test(timeout = 120_000L)
    fun `serialized pending meeting settles its selected person and reward exactly once`() {
        val engine = enabledEngine()
        val game = fixture(engine, HeroClass.ROGUE, 7L)
        game.adventureRelationships.roster = roster(game, EPOCH, 24L * HOUR, 12)
        while (game.adventureRelationships.pending == null) {
            assertTrue(game.actionEndsAt < EPOCH + 9L * HOUR)
            engine.settleOffline(game, game.actionEndsAt)
        }
        val run = requireNotNull(game.adventureRelationships.pending)
        val completion = game.actionEndsAt
        engine.settleOffline(game, completion - 1L)
        val resumed = restored(game)
        // Expiration/new data after selection must not reroll the already stored meeting.
        resumed.adventureRelationships.roster = null
        engine.settleOffline(resumed, completion)
        val storedRun = requireNotNull(resumed.adventureRelationships.lastResult?.run)
        assertEquals(run.sequence, storedRun.sequence)
        assertEquals(run.candidate.characterId, storedRun.candidate.characterId)
        assertEquals(run.sceneId, storedRun.sceneId)
        assertEquals(1L, resumed.adventureRelationships.totalEncounters)
        assertEquals(resumed.adventureRelationships.lastResult?.experienceAwarded, resumed.adventureRelationships.totalExperience)
        assertEquals(resumed.adventureRelationships.lastResult?.goldAwarded, resumed.adventureRelationships.totalGold)
        assertEquals(1L, resumed.adventureRelationships.contacts.single().meetings)
        assertEquals(1, resumed.adventureRelationships.contacts.single().memories.size)
        assertEquals(null, resumed.adventureRelationships.pending)
        val paid = Json.encodeToString(resumed)
        engine.settleOffline(resumed, completion)
        assertEquals(paid, Json.encodeToString(resumed))
        val afterRewardRestart = restored(resumed)
        engine.settleOffline(afterRewardRestart, completion + 5_000L)
        assertEquals(1L, afterRewardRestart.adventureRelationships.totalEncounters)
        assertEquals(resumed.adventureRelationships.totalExperience, afterRewardRestart.adventureRelationships.totalExperience)
        assertEquals(resumed.adventureRelationships.totalGold, afterRewardRestart.adventureRelationships.totalGold)
        assertEquals(1, afterRewardRestart.adventureRelationships.contacts.single().memories.size)
    }

    @Test
    fun `a due social encounter cannot replace the last act boss`() {
        val engine = enabledEngine()
        val game = fixture(engine, HeroClass.CLERIC, 2_047L)
        game.adventureRelationships.roster = roster(game, EPOCH, 24L * HOUR, 12)
        game.adventureRelationships.nextEncounterAt = EPOCH
        val tale = game.adventureTale
        tale.currentActIndex = tale.acts.lastIndex
        tale.acts.dropLast(1).forEach { it.progress = it.target; it.completed = true }
        tale.activeAct().progress = tale.activeAct().target - 1L
        val progress = tale.activeAct().progress
        engine.settleOffline(game, EPOCH + 1L)
        assertEquals(AdventurePhase.COMBAT, game.adventurePhase)
        assertEquals(MonsterGrade.BOSS, game.monster.grade)
        assertTrue(game.monster.isFinalBoss)
        assertEquals(progress, game.adventureTale.activeAct().progress)
        assertEquals(0L, game.adventureRelationships.totalEncounters)
        assertEquals(null, game.adventureRelationships.pending)
    }

    private fun collectThrough(
        engine: SimpleGameEngine,
        game: SimpleGameState,
        end: Long,
        results: MutableList<AdventureRelationshipResult>,
    ): Long {
        var steps = 0L
        var sourceValidActiveMillis = 0L
        while (game.actionEndsAt <= end) {
            val at = game.actionEndsAt
            sourceValidActiveMillis += sourceValidTimeThrough(game, at)
            val before = game.adventureRelationships.totalEncounters
            val kills = game.totalKills
            val delta = engine.settleOffline(game, at)
            assertTrue("Action clock advances", game.actionEndsAt > at)
            assertTrue("Bounded daily action loop", ++steps < 500_000L)
            val awarded = game.adventureRelationships.totalEncounters - before
            assertTrue("At most one result at a single action boundary", awarded in 0L..1L)
            if (awarded == 1L) {
                val result = requireNotNull(game.adventureRelationships.lastResult)
                results += result
                assertEquals("A social encounter is not a kill", kills, game.totalKills)
                assertEquals(0L, delta.defeatedMonsters)
                assertEquals(result.run.experienceReward, result.experienceAwarded)
                assertEquals(result.run.goldReward, result.goldAwarded)
                assertEquals(1, actualRewardKinds(result))
                assertTrue("Encounter cannot finish the boss gate", game.adventureTale.activeAct().progress <
                    game.adventureTale.activeAct().target)
            }
        }
        sourceValidActiveMillis += sourceValidTimeThrough(game, end)
        engine.settleOffline(game, end)
        assertEquals(end, game.lastSettledAt)
        return sourceValidActiveMillis
    }

    /** Source availability excludes scheduling and reunion cooldown, which are measured separately. */
    private fun sourceValidTimeThrough(game: SimpleGameState, end: Long): Long {
        val roster = game.adventureRelationships.roster ?: return 0L
        if (roster.snapshotId.isBlank() || roster.candidates.none {
                it.characterId.isNotBlank() && it.characterId != game.rankingCharacterId &&
                    it.level in (game.hero.level - 1L)..(game.hero.level + 1L)
            }) return 0L
        return (minOf(end, roster.validUntil) - maxOf(game.lastSettledAt, roster.receivedAt)).coerceAtLeast(0L)
    }

    private fun enabledEngine() = SimpleGameEngine(enableAdventureEvents = true, enableAdventureRelationships = true)

    private fun fixture(engine: SimpleGameEngine, heroClass: HeroClass, seed: Long): SimpleGameState {
        val rolled = engine.rollStats(seed, heroClass)
        val game = engine.newGame("인연 빈도 표본", heroClass, rolled.stats, rolled.nextSeed, EPOCH)
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

    private fun roster(game: SimpleGameState, receivedAt: Long, ttl: Long, size: Int): AdventureEncounterRoster =
        AdventureEncounterRoster(
            snapshotId = "fixture-${(receivedAt - EPOCH) / DAY}",
            receivedAt = receivedAt,
            validUntil = receivedAt + ttl,
            candidates = (0 until size).map { index ->
                val heroClass = HeroClass.entries[index % HeroClass.entries.size]
                AdventureEncounterCandidate(
                    characterId = "fixture-person-${index.toString().padStart(2, '0')}",
                    displayName = "표본 모험가 $index",
                    heroClass = heroClass,
                    level = game.hero.level,
                    combatPower = game.hero.level * 10L,
                    stats = candidateStats(game.hero.level, heroClass),
                    equipment = EquipmentSlot.entries.map { slot ->
                        AdventureRelationshipEquipmentSnapshot(slot, "${slot.labelKo} 표본", game.hero.level * 2L, "일반")
                    },
                )
            },
        )

    private fun candidateStats(level: Long, heroClass: HeroClass): HeroStats {
        val base = 10L + level / 3L
        val values = LongArray(6) { base }
        values[heroClass.primaryStatIndex] += 5L
        values[heroClass.secondaryStatIndex] += 3L
        return HeroStats(values[0], values[1], values[2], values[3], values[4], values[5], 100L + level * 5L, 40L + level * 2L)
    }

    private fun actualRewardKinds(result: AdventureRelationshipResult): Int = listOf(
        result.rewardKind == AdventureEventRewardKind.EXPERIENCE && result.experienceAwarded > 0L,
        result.rewardKind == AdventureEventRewardKind.GOLD && result.goldAwarded > 0L,
        result.rewardKind == AdventureEventRewardKind.ITEM && result.itemName.isNotBlank(),
    ).count { it }

    private fun restored(game: SimpleGameState): SimpleGameState = Json.decodeFromString(Json.encodeToString(game))

    private fun stagingQaDirectory(): Path {
        val cwd = Paths.get(System.getProperty("user.dir")).toRealPath()
        val project = generateSequence(cwd) { it.parent }.firstOrNull {
            it.fileName?.toString() == "project" && it.parent?.fileName?.toString() == "adventure-20260906" &&
                it.parent?.parent?.fileName?.toString() == "preintegration" && Files.isRegularFile(it.resolve("app/build.gradle.kts"))
        } ?: error("Frequency outputs are allowed only in the isolated preintegration project.")
        val staging = project.parent.toRealPath()
        val qa = staging.resolve("qa")
        require(!Files.isSymbolicLink(qa))
        Files.createDirectories(qa)
        require(qa.toRealPath().parent == staging)
        return qa
    }

    private fun writeReports(qa: Path, samples: List<Sample>) {
        val header = listOf("class", "seed", "active_hours", "source_valid_active_hours", "encounters", "encounters_per_hour",
            "encounters_per_source_valid_hour", "reunions",
            "negative_score_reunions", "distinct_contacts", "start_level", "end_level") + AdventureRelationshipTier.entries.map { "tier_${it.name.lowercase()}" }
        val rows = samples.map { sample ->
            val sourceHours = sample.sourceValidActiveMillis.toDouble() / HOUR
            (listOf(sample.heroClass.name, sample.seed, 48, String.format(Locale.ROOT, "%.6f", sourceHours), sample.results.size,
                String.format(Locale.ROOT, "%.6f", sample.results.size / 48.0),
                String.format(Locale.ROOT, "%.6f", if (sourceHours > 0.0) sample.results.size / sourceHours else 0.0),
                sample.results.count { it.run.reunion },
                sample.results.count { it.run.reunion && it.run.scoreBefore < 0 }, sample.game.adventureRelationships.contacts.size,
                50, sample.game.hero.level) + AdventureRelationshipTier.entries.map { tier ->
                    sample.game.adventureRelationships.contacts.count { it.tier == tier }
                }).joinToString(",")
        }
        qa.resolve("adventure-relationship-frequency.csv").toFile().writeText((listOf(header.joinToString(",")) + rows).joinToString("\n") + "\n")
        val details = samples.flatMap { sample -> sample.results.map { result ->
            listOf(sample.heroClass.name, sample.seed, result.run.sequence, result.run.candidate.characterId,
                result.run.startedAt, result.run.startedActiveMillis, result.run.snapshotId, result.run.heroLevel,
                result.run.candidate.level, result.run.sceneId, result.run.outcome, result.run.reunion,
                result.run.scoreBefore, result.scoreAfter, result.tier, result.rewardKind, result.experienceAwarded,
                result.goldAwarded, result.itemName, result.run.battleKind, result.run.battleOutcome).joinToString(",")
        } }
        qa.resolve("adventure-relationship-meetings.csv").toFile().writeText(
            (listOf("class,seed,sequence,candidate_id,started_at,active_millis,snapshot_id,hero_level,candidate_level,scene,outcome,reunion,score_before,score_after,tier,reward_kind,xp,gold,item,battle_kind,battle_outcome") + details).joinToString("\n") + "\n")
        qa.resolve("adventure-relationship-frequency-notes.txt").toFile().writeText(
            "Synthetic local fixtures, not live server candidate counts. No network or database access.\n" +
                "Six classes x two deterministic seeds x 48 raw active adventure hours; town time included.\n" +
                "Twelve same-level public candidates; one daily fixture publication follows settlement of the old one.\n" +
                "Candidate fixture levels follow each publication's current hero level to measure cadence under controlled availability.\n" +
                "source_valid_active_hours counts settled active intervals with a received, unexpired, non-self level-plus-or-minus-one source candidate.\n" +
                "It excludes reunion cooldown and the global schedule from its definition; zero-charge wall-clock pauses occur only in a separate test.\n" +
                "Candidates contain frozen public battle stats and six equipment slots; no live opponent state is read.\n" +
                "Relationship results grant exactly one of XP, gold or equipment. Eight situations may open deterministic projection combat initially; two more require a qualifying reunion.\n" +
                "Reunion means a remembered character ID. Negative-score reunion is not a separately implemented rivalry system.\n" +
                "Tier counts are current contacts at end, not one count per encounter. Two seeds are shared across classes.\n" +
                "Natural samples need not reach an extreme tier; cadence bounds are invariants, not economic approval.\n" +
                "Rows completed: ${samples.size} / 12.\n")
    }

    companion object {
        private const val EPOCH = 1_800_000_000_000L
        private const val HOUR = 3_600_000L
        private const val DAY = 24L * HOUR
    }
}
