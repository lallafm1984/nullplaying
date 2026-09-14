package com.nullplaying.engine

import com.nullplaying.model.AdventureEncounterCandidate
import com.nullplaying.model.AdventureEncounterRoster
import com.nullplaying.model.AdventurePhase
import com.nullplaying.model.HeroClass
import com.nullplaying.model.InventoryItem
import com.nullplaying.model.LearnedSkill
import com.nullplaying.model.SimpleGameState
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Shared only by isolated QA probes; no network, database, charge-policy or live save input. */
internal object AdventureQaFixtures {
    const val EPOCH = 1_800_000_000_000L
    const val HOUR = 3_600_000L

    // Fresh, frozen seed domains, separate from the stage-1 eight seeds and relationship probes.
    val finalSeeds: List<Long> = (1L..16L).map { index ->
        var value = 0x5B17_24D9_38A6_0C1FL + index * -7_046_029_254_386_353_131L
        value = (value xor (value ushr 30)) * -4_658_895_280_553_007_687L
        value = (value xor (value ushr 27)) * -7_723_592_293_110_705_685L
        (value xor (value ushr 31)).let { if (it == 0L) index else it }
    }

    fun game(engine: SimpleGameEngine, heroClass: HeroClass, seed: Long, level: Long, depth: Long = 0L): SimpleGameState {
        val rolled = engine.rollStats(seed, heroClass)
        val game = engine.newGame("통합 경제 표본", heroClass, rolled.stats, rolled.nextSeed, EPOCH)
        var growthSeed = seed xor 0x62A4_1875L
        repeat((level - 1L).toInt()) { growthSeed = engine.applyClassGuidedGrowth(game.hero.stats, heroClass, growthSeed) }
        game.hero.level = level
        game.classGuidedLevelGrowths = level - 1L
        game.hero.experience = 0L
        game.hero.gold = 0L
        game.inventory.clear()
        game.rankingCharacterId = "qa-own-$seed"
        game.equipment.forEach { item ->
            val spread = Math.floorMod(seed + item.slot.ordinal * 7L, 9L) - 4L
            item.power = (engine.expectedEquipmentCombatPower(level) + spread).coerceAtLeast(1L)
            item.acquiredAtLevel = level
            item.rarity = "일반"
        }
        game.skills = (1..(level / 5L + 1L).coerceAtMost(SimpleGameEngine.MAX_SKILLS.toLong()).toInt()).map { tier ->
            val skill = SkillCatalog.select(game.skillCatalogSeed, heroClass, tier)
            LearnedSkill(tier, skill.name, if (tier == 1) 1L else (tier - 1L) * 5L,
                skill.description, skill.catalogId, usageCount = 0L)
        }.toMutableList()
        val tale = if (depth > 0L) LabyrinthTaleCatalog.definitionForDepth(depth)
            else AdventureTaleCatalog.mainTales[((level - 1L) / 2L).toInt().coerceIn(0, AdventureTaleCatalog.mainTales.lastIndex)]
        game.adventureTale = AdventureTaleCatalog.instantiate(tale, if (depth > 0L) 42L + depth else 1L,
            game.hero.name, level, AdventureTaleCatalog.variantAt(Math.floorMod(seed, 6L).toInt()), depth)
        game.labyrinthDepthCompleted = (depth - 1L).coerceAtLeast(0L)
        game.adventurePhase = AdventurePhase.DEPARTING
        game.actionStartedAt = EPOCH
        game.actionEndsAt = EPOCH + 1L
        game.lastSettledAt = EPOCH
        return game
    }

    fun roster(game: SimpleGameState, receivedAt: Long = EPOCH, hours: Long = 12L, count: Int = 12) =
        AdventureEncounterRoster("economy-fixture-$receivedAt", receivedAt, receivedAt + hours * HOUR,
            (0 until count).map { index -> AdventureEncounterCandidate("economy-person-$index", "경제 표본 $index",
                HeroClass.entries[index % HeroClass.entries.size], game.hero.level, game.hero.level * 10L) })

    fun baselineSaleValue(engine: SimpleGameEngine, game: SimpleGameState, item: InventoryItem): Long =
        StatBonusRules.saleValue(game, engine.saleValueForTest(item))

    fun sourceValidTimeThrough(game: SimpleGameState, end: Long): Long {
        val roster = game.adventureRelationships.roster ?: return 0L
        if (roster.candidates.none { it.characterId != game.rankingCharacterId &&
                it.level in (game.hero.level - 1L)..(game.hero.level + 1L) }) return 0L
        return (minOf(end, roster.validUntil) - maxOf(game.lastSettledAt, roster.receivedAt)).coerceAtLeast(0L)
    }

    fun stagingQaDirectory(): Path {
        val cwd = Paths.get(System.getProperty("user.dir")).toRealPath()
        val root = generateSequence(cwd) { it.parent }.firstOrNull {
            Files.isRegularFile(it.resolve("docs/SESSION_HANDOFF.md")) &&
                Files.isRegularFile(it.resolve("app/build.gradle.kts"))
        }
        if (root != null) {
            // Current-root regression also writes synthetic backup evidence. Keep it local,
            // separate from both historical audits and the archived preintegration source.
            val qa = root.resolve("output/current-root-qa")
            Files.createDirectories(qa)
            require(qa.toRealPath().startsWith(root.resolve("output").toRealPath()))
            return qa
        }
        val project = generateSequence(cwd) { it.parent }.firstOrNull {
            it.fileName?.toString() == "project" && it.parent?.fileName?.toString() == "adventure-20260906" &&
                it.parent?.parent?.fileName?.toString() == "preintegration" && Files.isRegularFile(it.resolve("app/build.gradle.kts"))
        } ?: error("Probe output is restricted to the isolated preintegration staging directory.")
        val staging = project.parent.toRealPath()
        val qa = staging.resolve("qa")
        require(!Files.isSymbolicLink(qa))
        Files.createDirectories(qa)
        require(qa.toRealPath().parent == staging)
        return qa
    }
}
