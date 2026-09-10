package com.nullplaying.engine.arena

import com.nullplaying.engine.PublicPlayerBattleDerivation
import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.model.HeroClass
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArenaAutoBuildPresetTest {
    @Test
    fun `ten authored preset ids are stable unique and evenly reachable`() {
        assertEquals(
            listOf(
                "balanced", "swift_assault", "heavy_assault", "control_pressure",
                "defensive_ward", "sustain_recovery", "defense_breaker",
                "status_attrition", "reactive_counter", "wild_tactics",
            ),
            ArenaAutoBuildPreset.entries.map(ArenaAutoBuildPreset::stableId),
        )
        val counts = ArenaAutoBuildPreset.entries.associateWith { 0 }.toMutableMap()
        repeat(10_000) { index ->
            val seed = arenaAutoBuildPresetIdentitySeed("opponent-$index")
            val preset = ArenaAutoBuildPreset.fromSeed(seed)
            counts[preset] = counts.getValue(preset) + 1
        }
        counts.forEach { (preset, count) ->
            assertTrue("$preset must not monopolize identity allocation: $count", count in 850..1_150)
        }
        println("arena-auto-build-selection samples=10000 counts=" +
            counts.entries.joinToString(",") { (preset, count) -> "${preset.stableId}:$count" })
    }

    @Test
    fun `level ten checkpoints preserve a competency floor signature and level twenty prefixes`() {
        HeroClass.entries.forEach { heroClass ->
            val owned = sameLevelOwned(heroClass, 100)
            val slotById = ArenaSkillTreeCatalog.forClass(heroClass)
                .associate { it.id to it.slotKey }
            val checkpoints = ArenaAutoBuildPreset.entries.map { preset ->
                val signature = ArenaAutoBuildProfiles.checkpointPlan(
                    heroClass = heroClass,
                    preset = preset,
                    arenaLevel = 3,
                ).toMap()
                val levelTen = ArenaAutoBuildProfiles.checkpointPlan(
                    heroClass = heroClass,
                    preset = preset,
                    arenaLevel = 10,
                ).toMap()
                val levelTwenty = ArenaAutoBuildProfiles.checkpointPlan(
                    heroClass = heroClass,
                    preset = preset,
                    arenaLevel = 20,
                ).toMap()

                assertEquals("$heroClass/$preset signature points", 3, signature.values.sum())
                assertEquals("$heroClass/$preset Lv.10 points", 10, levelTen.values.sum())
                val rootRanks = listOf("A01", "A02", "A03").associateWith(levelTen::getValue)
                val rowOnePoints = levelTen
                    .filterKeys { it !in rootRanks }
                    .values
                    .sum()
                assertTrue(
                    "$heroClass/$preset must retain a broad competency floor: $levelTen",
                    rootRanks.values.all { it in 1..6 } && rootRanks.values.sum() >= 8,
                )
                assertTrue(
                    "$heroClass/$preset may only make a small reviewed row-one opening: $levelTen",
                    rowOnePoints <= 2,
                )
                assertTrue(
                    "$heroClass/$preset Lv.10 must retain its three-point signature",
                    signature.all { (slot, rank) -> levelTen.getValue(slot) >= rank },
                )
                assertTrue(
                    "$heroClass/$preset Lv.20 must extend its Lv.10 checkpoint",
                    levelTen.all { (slot, rank) -> levelTwenty.getValue(slot) >= rank },
                )

                val allocated = ArenaSkillTreeRules.autoAllocateWithPreset(
                    heroClass = heroClass,
                    arenaLevel = 10,
                    ownedAttackIds = owned,
                    seed = arenaAutoBuildAllocationSeed("level-ten-$heroClass-${preset.stableId}"),
                    preset = preset,
                )
                assertEquals(10, ArenaSkillTreeRules.spentPoints(allocated))
                assertTrue(ArenaSkillTreeRules.validate(allocated, heroClass, 10, owned))
                assertEquals(
                    levelTen,
                    allocated.allocations.associate { slotById.getValue(it.nodeId) to it.rank },
                )
                levelTen
            }
            assertEquals(
                "$heroClass must expose all ten Lv.10 checkpoint structures",
                ArenaAutoBuildPreset.entries.size,
                checkpoints.distinct().size,
            )
        }
    }

    @Test
    fun `level ten partial ownership fallback also spends legally without a rank ten rush`() {
        HeroClass.entries.forEach { heroClass ->
            val owned = sameLevelOwned(heroClass, 10)
            val builds = ArenaAutoBuildPreset.entries.map { preset ->
                val state = ArenaSkillTreeRules.autoAllocateWithPreset(
                    heroClass = heroClass,
                    arenaLevel = 10,
                    ownedAttackIds = owned,
                    seed = arenaAutoBuildAllocationSeed(
                        "level-ten-partial-$heroClass-${preset.stableId}",
                    ),
                    preset = preset,
                )
                assertEquals("$heroClass/$preset partial spend", 10,
                    ArenaSkillTreeRules.spentPoints(state))
                assertTrue("$heroClass/$preset partial validity",
                    ArenaSkillTreeRules.validate(state, heroClass, 10, owned))
                assertTrue(
                    "$heroClass/$preset partial route must avoid rank ten",
                    state.allocations.all { it.rank <= 6 },
                )
                digest(heroClass, state)
            }
            assertEquals(
                "$heroClass partial ownership must keep all ten Lv.10 structures",
                ArenaAutoBuildPreset.entries.size,
                builds.distinct().size,
            )
        }
    }

    @Test
    fun `each preset is a distinct full legal build from level twenty onward`() {
        HeroClass.entries.forEach { heroClass ->
            listOf(20, 25, 30, 50, 100).forEach { level ->
                val owned = sameLevelOwned(heroClass, level)
                val builds = ArenaAutoBuildPreset.entries.map { preset ->
                    val seed = arenaAutoBuildAllocationSeed(
                        "${heroClass.name}-$level-${preset.stableId}",
                    )
                    val first = ArenaSkillTreeRules.autoAllocateWithPreset(
                        heroClass, level, owned, seed, preset,
                    )
                    val replay = ArenaSkillTreeRules.autoAllocateWithPreset(
                        heroClass, level, owned, seed, preset,
                    )
                    assertEquals("$heroClass Lv.$level $preset replay", first, replay)
                    assertEquals("$heroClass Lv.$level $preset full spend", level,
                        ArenaSkillTreeRules.spentPoints(first))
                    assertTrue("$heroClass Lv.$level $preset valid",
                        ArenaSkillTreeRules.validate(first, heroClass, level, owned))
                    first
                }
                assertEquals(
                    "$heroClass Lv.$level must expose all ten authored structures; duplicates=" +
                        duplicatePresets(builds.map { digest(heroClass, it) }),
                    ArenaAutoBuildPreset.entries.size,
                    builds.map { digest(heroClass, it) }.distinct().size,
                )
                if (level in 20..30) builds.forEachIndexed { index, state ->
                    val roots = ArenaSkillTreeCatalog.forClass(heroClass)
                        .filter(ArenaSkillTreeNodeDefinition::isRoot)
                        .mapTo(linkedSetOf()) { it.id }
                    val rootPoints = state.allocations.filter { it.nodeId in roots }.sumOf { it.rank }
                    assertTrue(
                        "$heroClass Lv.$level ${ArenaAutoBuildPreset.entries[index]} competency roots=$rootPoints",
                        rootPoints >= 12,
                    )
                }
            }
        }
    }

    @Test
    fun `public projections match local allocation maps and retain every reachable preset route`() {
        val engine = SimpleGameEngine()
        HeroClass.entries.forEach { heroClass ->
            listOf(10, 20, 25, 30, 50, 100).forEach { heroLevel ->
                val roll = engine.rollStats(710_000L + heroClass.ordinal * 100L + heroLevel, heroClass)
                val generated = engine.newGame(
                    "projection", heroClass, roll.stats.copy(), roll.nextSeed, 1_000L,
                )
                generated.rngState = ArenaSyntheticProfileGrowth.grow(
                    engine = engine,
                    stats = generated.hero.stats,
                    heroClass = heroClass,
                    targetLevel = heroLevel.toLong(),
                    identitySeed = generated.rngState,
                )
                generated.hero.level = heroLevel.toLong()
                val power = (1L + (heroLevel - 1L) * 5L) * 2L
                val stats = requireNotNull(PublicPlayerBattleDerivation.deriveStats(
                    heroClass, heroLevel.toLong(), power, generated.hero.stats,
                ))
                val learned = PublicPlayerBattleDerivation.learnedSkillsForClassAndLevel(
                    heroClass, heroLevel.toLong(),
                )
                listOf(1, 10, 20, 25, 30, 50, 100).forEach { arenaLevel ->
                    val builds = ArenaAutoBuildPreset.entries.map { preset ->
                        val input = requireNotNull(ArenaV6OpponentInputFactory.create(
                            projectionId = "projection-$heroClass-$heroLevel-${preset.stableId}",
                            displayName = "projection",
                            heroClass = heroClass,
                            level = heroLevel.toLong(),
                            combatPower = power,
                            stats = stats,
                            learnedSkills = learned,
                            arenaLevel = arenaLevel,
                            stableSeed = arenaAutoBuildAllocationSeed(
                                "$heroClass-$heroLevel-${preset.stableId}",
                            ),
                            buildPreset = preset,
                        ))
                        ArenaSupportTurnEngine.validate(input.combat)
                        assertTrue(input.combat.arenaClassBalanceEnabled)
                        val publicRanks = buildMap {
                            input.combat.fighter.attacks.forEach { attack ->
                                put(attack.id, requireNotNull(attack.arena).rank)
                            }
                            putAll(input.combat.supportRanks)
                        }
                        val owned = learned.mapTo(linkedSetOf()) { it.catalogId }
                        val direct = ArenaSkillTreeRules.autoAllocateWithPreset(
                            heroClass,
                            arenaLevel,
                            owned,
                            seed = arenaAutoBuildAllocationSeed(
                                "$heroClass-$heroLevel-${preset.stableId}",
                            ),
                            preset = preset,
                        )
                        assertEquals(
                            "$heroClass heroLv.$heroLevel arenaLv.$arenaLevel local/public allocation parity",
                            direct.allocations.associate { it.nodeId to it.rank },
                            publicRanks,
                        )
                        input
                    }
                    val slots = ArenaSkillTreeCatalog.forClass(heroClass)
                        .associate { it.id to it.slotKey }
                    val digests = builds.map { input ->
                        buildList {
                            input.combat.fighter.attacks.forEach { attack ->
                                add("${slots.getValue(attack.id)}:${requireNotNull(attack.arena).rank}")
                            }
                            input.combat.supportRanks.forEach { (id, rank) ->
                                add("${slots.getValue(id)}:$rank")
                            }
                        }.sorted().joinToString("|")
                    }
                    if (arenaLevel in listOf(20, 25, 30)) {
                        if (heroLevel == 10 && arenaLevel == 20) {
                            println(
                                "arena-partial-preset-structures class=$heroClass heroLevel=10 " +
                                    "arenaLevel=20 distinct=${digests.distinct().size} " +
                                    "duplicates=${duplicatePresets(digests)}",
                            )
                        }
                        assertEquals(
                            "$heroClass heroLv.$heroLevel arenaLv.$arenaLevel must retain broad variety; " +
                                "duplicates=${duplicatePresets(digests)}",
                            ArenaAutoBuildPreset.entries.size,
                            digests.distinct().size,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `partial ownership is never crossed and spends every legally reachable point`() {
        HeroClass.entries.forEach { heroClass ->
            val nodes = ArenaSkillTreeCatalog.forClass(heroClass)
            val roots = nodes.filter(ArenaSkillTreeNodeDefinition::isRoot).map { it.id }
            val nonRoot = nodes.first { it.kind == ArenaSkillNodeKind.ATTACK && !it.isRoot }.id
            val cases = listOf(
                emptySet(),
                setOf(roots.first()),
                roots.take(2).toSet(),
                setOf(nonRoot),
            )
            ArenaAutoBuildPreset.entries.forEach { preset ->
                cases.forEachIndexed { caseIndex, owned ->
                    listOf(20, 25, 30, 100).forEach { level ->
                        val state = ArenaSkillTreeRules.autoAllocateWithPreset(
                            heroClass,
                            level,
                            owned,
                            seed = 90_000L + level * 100L + preset.ordinal * 10L + caseIndex,
                            preset = preset,
                        )
                        assertTrue("$heroClass/$preset/$caseIndex/Lv$level valid",
                            ArenaSkillTreeRules.validate(state, heroClass, level, owned))
                        assertTrue("$heroClass/$preset/$caseIndex/Lv$level ownership", state.allocations.all {
                            val definition = requireNotNull(ArenaSkillTreeCatalog.find(it.nodeId))
                            definition.kind != ArenaSkillNodeKind.ATTACK || it.nodeId in owned
                        })
                        if (owned.isEmpty() || owned == setOf(nonRoot)) {
                            assertTrue(state.allocations.isEmpty())
                        }
                        if (ArenaSkillTreeRules.spentPoints(state) < level) {
                            assertTrue(
                                "$heroClass/$preset/$caseIndex/Lv$level may leave points only when no legal node remains",
                                ArenaSkillTreeRules.view(state, level, owned).nodes.none { it.canAllocate },
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `authored routes grow monotonically across every arena budget and canonical ownership band`() {
        HeroClass.entries.forEach { heroClass ->
            listOf(10, 20, 25, 30).forEach { heroLevel ->
                val owned = sameLevelOwned(heroClass, heroLevel)
                ArenaAutoBuildPreset.entries.forEach { preset ->
                    val seed = arenaAutoBuildAllocationSeed(
                        "monotonic-$heroClass-$heroLevel-${preset.stableId}",
                    )
                    var previous = emptyMap<String, Int>()
                    var previousSpent = 0
                    (1..ArenaSkillTreeRules.maxArenaLevel).forEach { arenaLevel ->
                        val state = ArenaSkillTreeRules.autoAllocateWithPreset(
                            heroClass = heroClass,
                            arenaLevel = arenaLevel,
                            ownedAttackIds = owned,
                            seed = seed,
                            preset = preset,
                        )
                        assertTrue(
                            "$heroClass heroLv.$heroLevel arenaLv.$arenaLevel $preset valid",
                            ArenaSkillTreeRules.validate(state, heroClass, arenaLevel, owned),
                        )
                        val current = state.allocations.associate { it.nodeId to it.rank }
                        assertTrue(
                            "$heroClass heroLv.$heroLevel $preset must preserve every earlier rank " +
                                "at Arena Lv.$arenaLevel: before=$previous after=$current",
                            previous.all { (nodeId, rank) -> (current[nodeId] ?: 0) >= rank },
                        )
                        val spent = ArenaSkillTreeRules.spentPoints(state)
                        assertTrue(spent >= previousSpent)
                        if (spent < arenaLevel) {
                            assertTrue(
                                "$heroClass heroLv.$heroLevel arenaLv.$arenaLevel $preset may stop only " +
                                    "after every owned route is exhausted",
                                ArenaSkillTreeRules.view(state, arenaLevel, owned).nodes.none { it.canAllocate },
                            )
                        }
                        previous = current
                        previousSpent = spent
                    }
                }
            }
        }
    }

    @Test
    fun `identity seed is stable for a season and excludes volatile battle inputs`() {
        val first = arenaAutoBuildPresetIdentitySeed("opponent-b", seasonId = "1")
        val reopened = arenaAutoBuildPresetIdentitySeed("opponent-b", seasonId = "1")
        assertEquals(first, reopened)
        assertEquals(ArenaAutoBuildPreset.fromSeed(first), ArenaAutoBuildPreset.fromSeed(reopened))
        assertNotEquals(first, arenaAutoBuildPresetIdentitySeed("opponent-b", seasonId = "2"))
        assertNotEquals(first, arenaAutoBuildPresetIdentitySeed("opponent-c", seasonId = "1"))
        assertNotEquals(first, arenaAutoBuildAllocationSeed("opponent-b", seasonId = "1"))

        val family = ArenaAutoBuildPreset.fromSeed(first)
        listOf("requester-a", "requester-b").forEach { requester ->
            listOf(1, 20, 30, 100).forEach { arenaLevel ->
                // Volatile inputs are intentionally absent from the public-family seed API.
                assertEquals("$requester/Lv.$arenaLevel", family, ArenaAutoBuildPreset.fromSeed(
                    arenaAutoBuildPresetIdentitySeed("opponent-b", seasonId = "1"),
                ))
            }
        }
    }

    @Test
    fun `twenty local reserves assign every preset exactly twice and replay identically`() {
        val first = requireNotNull(ArenaLocalReserveMatchmaking.build(
            requesterCharacterId = "local-requester",
            requesterLevel = 30,
            count = 20,
        ))
        val replay = requireNotNull(ArenaLocalReserveMatchmaking.build(
            requesterCharacterId = "local-requester",
            requesterLevel = 30,
            count = 20,
        ))
        assertEquals(first, replay)
        assertEquals(
            ArenaAutoBuildPreset.entries.associate { it.stableId to 2 },
            first.groupingBy { it.autoBuildPresetId }.eachCount(),
        )
        val powerByName = ArenaLocalReserveMatchmaking.definitions.associate {
            it.name to it.powerPermille
        }
        first.groupBy(PublicPlayerArenaMatchInput::autoBuildPresetId).forEach { (preset, entries) ->
            assertEquals("$preset average power", 1_000.0,
                entries.map { powerByName.getValue(it.projection.displayName) }.average(), 0.0)
        }
        assertTrue(first.all { it.combat.fighter.attacks.isNotEmpty() })

        (1..20).forEach { count ->
            val prefix = requireNotNull(ArenaLocalReserveMatchmaking.build(
                requesterCharacterId = "local-requester",
                requesterLevel = 30,
                count = count,
            ))
            assertEquals(count, prefix.size)
            assertEquals("count=$count maximum preset coverage", minOf(count, 10),
                prefix.map(PublicPlayerArenaMatchInput::autoBuildPresetId).distinct().size)
            assertTrue("count=$count power average", abs(prefix.map {
                powerByName.getValue(it.projection.displayName)
            }.average() - 1_000.0) <= 6.0)
        }
    }

    @Test
    fun `legacy npc trait allocator uses all ten families without breaking growth rules`() {
        HeroClass.entries.forEach { heroClass ->
            val builds = ArenaAutoBuildPreset.entries.map { preset ->
                val traits = com.nullplaying.ui.arenaNpcTraitAllocation(
                    heroClass = heroClass,
                    arenaLevel = 50,
                    heroLevel = 100,
                    preset = preset,
                )
                val state = ArenaProgressionState(
                    unlocked = true,
                    totalXp = ArenaProgressionRules.xpForLevel(50),
                    allocations = traits.map { ArenaTraitAllocation(it.id, it.rank, it.enhancement) },
                )
                assertTrue("$heroClass/$preset legacy validity", ArenaProgressionRules.isValidForFighter(
                    state, heroClass, 100, ArenaSupportCatalog.unlockedIds(heroClass, 100),
                ))
                traits.joinToString("|") { "${it.id}:${it.rank}:${it.enhancement}" }
            }
            assertTrue("$heroClass legacy families must produce broad real variety",
                builds.distinct().size >= 8)
        }
    }

    private fun sameLevelOwned(heroClass: HeroClass, level: Int): Set<String> =
        PublicPlayerBattleDerivation.learnedSkillsForClassAndLevel(heroClass, level.toLong())
            .mapTo(linkedSetOf()) { it.catalogId }

    private fun digest(heroClass: HeroClass, state: ArenaSkillTreeState): String {
        val slots = ArenaSkillTreeCatalog.forClass(heroClass).associate { it.id to it.slotKey }
        return state.allocations.joinToString("|") { "${slots.getValue(it.nodeId)}:${it.rank}" }
    }

    private fun duplicatePresets(digests: List<String>): Map<String, List<String>> =
        digests.mapIndexed { index, digest -> ArenaAutoBuildPreset.entries[index].stableId to digest }
            .groupBy({ it.second }, { it.first })
            .filterValues { it.size > 1 }
}
