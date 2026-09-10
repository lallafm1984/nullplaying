package com.nullplaying.engine.arena

import com.nullplaying.engine.BattleQaMatchFactory
import com.nullplaying.engine.PublicPlayerBattleDerivation
import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.model.BattleGuidance
import com.nullplaying.model.HeroClass
import com.nullplaying.model.HeroStats
import com.nullplaying.model.PublicPlayerRoster
import com.nullplaying.model.PublicPlayerSnapshot
import com.nullplaying.model.PublicPlayerStats
import com.nullplaying.model.SHARED_PLAYER_RULES_VERSION
import com.nullplaying.model.SHARED_PLAYER_SNAPSHOT_VERSION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

class ArenaServerMatchmakingTest {
    @Test
    fun `production gate requires arena flag shared transport and remote services`() {
        assertFalse(arenaServerMatchingEnabled(false, true, true))
        assertFalse(arenaServerMatchingEnabled(true, false, true))
        assertFalse(arenaServerMatchingEnabled(true, true, false))
        assertTrue(arenaServerMatchingEnabled(true, true, true))
        assertTrue(arenaServerMatchingEnabled(
            true,
            true,
            false,
            sharedPlayerQaTransportEnabled = true,
        ))
        assertFalse(arenaServerMatchingEnabled(
            true,
            true,
            false,
            sharedPlayerQaTransportEnabled = false,
        ))
        assertTrue(arenaServerMatchingEnabled(
            false,
            false,
            false,
            qaFixtureEnabled = true,
        ))
    }

    @Test
    fun `stable roster rotation visits every valid opponent before repeating`() {
        val source = roster(
            snapshot("11111111-1111-4111-8111-111111111111", "한별", HeroClass.RANGER),
            snapshot("22222222-2222-4222-8222-222222222222", "두별", HeroClass.MAGE),
            snapshot("33333333-3333-4333-8333-333333333333", "세별", HeroClass.WARRIOR),
        )

        fun ready(sequence: Long, value: PublicPlayerRoster = source) =
            selectArenaServerOpponent(
                enabled = true,
                roster = value,
                requesterCharacterId = REQUESTER,
                requesterLevel = 10L,
                nowEpochMillis = NOW,
                completedMatchSequence = sequence,
            ) as ArenaServerMatchSelectionResult.Ready

        val cycle = (0L..2L).map(::ready)
        assertTrue(cycle.all { it.source == ArenaOpponentSource.PUBLIC_ROSTER })
        assertTrue(cycle.all { it.serverPoolSize == 3 && it.localPoolSize == 0 })
        assertEquals(3, cycle.map { it.opponent.projection.projectionId }.distinct().size)
        assertEquals(cycle.first().opponent, ready(0L).opponent)
        assertEquals(cycle.first().battleSeed, ready(0L).battleSeed)
        assertEquals(cycle.first().opponent, ready(3L).opponent)
        assertNotEquals(cycle[0].battleSeed, cycle[1].battleSeed)
        assertEquals(
            cycle.map { it.opponent.projection.projectionId },
            (0L..2L).map { ready(it, source.copy(snapshots = source.snapshots.reversed())) }
                .map { it.opponent.projection.projectionId },
        )
    }

    @Test
    fun `public candidates are preserved and local reserve fills pool to exactly twenty`() {
        listOf(
            0 to (20 to 20),
            1 to (20 to 19),
            19 to (20 to 1),
            20 to (20 to 0),
            24 to (20 to 0),
        ).forEach { (serverCount, expected) ->
            val source = rosterCount(serverCount)
            val expectedServerCount = minOf(serverCount, 20)
            val cycle = (0L until expected.first.toLong()).map { sequence ->
                selectPool(source, sequence)
            }

            assertTrue(cycle.all { it.opponent.combat.arenaLevel == ArenaCharacterPointRules.budget(it.opponent.projection.level) })
            assertTrue(cycle.all { it.poolSize == expected.first })
            assertTrue(cycle.all { it.serverPoolSize == expectedServerCount })
            assertTrue(cycle.all { it.localPoolSize == expected.second })
            assertEquals(expected.first, cycle.map { it.opponent.projection.projectionId }.distinct().size)
            assertEquals(expectedServerCount, cycle.count { it.source == ArenaOpponentSource.PUBLIC_ROSTER })
            assertEquals(expected.second, cycle.count { it.source == ArenaOpponentSource.LOCAL_RESERVE })
            val expectedServerIds = source.snapshots.map { it.projectionId }.sorted().take(20).toSet()
            val selectedServerIds = cycle.filter { it.source == ArenaOpponentSource.PUBLIC_ROSTER }
                .map { it.opponent.projection.projectionId }
                .toSet()
            assertEquals(expectedServerIds, selectedServerIds)
        }
    }

    @Test
    fun `missing expired and disabled public rosters use the same deterministic local cycle`() {
        fun cycle(enabled: Boolean, value: PublicPlayerRoster?) = (0L until 20L).map { sequence ->
            selectArenaOpponent(
                serverRosterEnabled = enabled,
                roster = value,
                requesterCharacterId = REQUESTER,
                requesterLevel = 10L,
                nowEpochMillis = NOW,
                completedMatchSequence = sequence,
            ) as ArenaServerMatchSelectionResult.Ready
        }

        val missing = cycle(true, null)
        val expired = cycle(true, rosterCount(3).copy(validUntilEpochMillis = NOW))
        val disabled = cycle(false, rosterCount(24))
        listOf(missing, expired, disabled).forEach { values ->
            assertEquals(20, values.map { it.opponent.projection.projectionId }.distinct().size)
            assertTrue(values.all {
                it.source == ArenaOpponentSource.LOCAL_RESERVE &&
                    it.serverPoolSize == 0 && it.localPoolSize == 20
            })
        }
        assertEquals(
            missing.map { it.opponent.projection.projectionId },
            cycle(true, null).map { it.opponent.projection.projectionId },
        )
    }

    @Test
    fun `malformed self and duplicate projections are removed before reserve count`() {
        val duplicateId = serverId(1)
        val source = roster(
            snapshot(id = REQUESTER, name = "Self"),
            snapshot(id = serverId(2), name = "\u0000invalid"),
            snapshot(id = duplicateId, name = "ServerOne"),
            snapshot(id = duplicateId, name = "ServerOneAgain"),
        )
        val cycle = (0L until 20L).map { selectPool(source, it) }

        assertTrue(cycle.all { it.serverPoolSize == 1 && it.localPoolSize == 19 })
        assertEquals(1, cycle.count { it.source == ArenaOpponentSource.PUBLIC_ROSTER })
        assertTrue(cycle.none { it.opponent.projection.projectionId == REQUESTER })
        assertTrue(cycle.none { it.opponent.projection.displayName.contains("invalid") })
    }

    @Test
    fun `local reserve has twenty authored ASCII names all classes exact level and bounded power`() {
        val values = requireNotNull(ArenaLocalReserveMatchmaking.build(
            requesterLevel = 37L,
            count = 20,
        ))

        assertEquals(20, values.size)
        assertEquals(20, values.map { it.projection.projectionId }.distinct().size)
        assertEquals(
            // Center-out fill keeps every partial server shortfall close to average power while
            // visiting all ten preset pairs before either member repeats.
            listOf(
                "Juniper", "Ilyan", "Rowan", "Gwyn", "Petra", "Elara", "Nolan",
                "Celeste", "Lyra", "Alden", "Tristan", "Sylvie", "Hazel", "Quinn",
                "Felix", "Orin", "Dorian", "Mira", "Briar", "Kael",
            ),
            values.map { it.projection.displayName },
        )
        assertEquals(
            ArenaLocalReserveMatchmaking.definitions.map { it.name }.toSet(),
            values.map { it.projection.displayName }.toSet(),
        )
        assertTrue(values.all { it.projection.displayName.matches(Regex("^[A-Za-z]+$")) })
        assertEquals(HeroClass.entries.toSet(), values.map { it.combat.fighter.heroClass }.toSet())
        assertTrue(HeroClass.entries.all { heroClass ->
            values.count { it.combat.fighter.heroClass == heroClass } in 3..4
        })
        assertTrue(values.all { it.projection.level == 37L && it.combat.fighter.level == 37L })
        assertEquals(
            20,
            values.map { it.combat.fighter.stats.values() }.distinct().size,
        )
        assertTrue(values.all {
            PublicPlayerBattleDerivation.powerScalePermille(37L, it.projection.verifiedPower) in 900..1_100
        })
        assertEquals(20_000, ArenaLocalReserveMatchmaking.definitions.sumOf { it.powerPermille })
        assertTrue(values.all { value ->
            value.projection.equipment.isEmpty() &&
                value.projection.skills.all { it.masteryLevel == 0 } &&
                value.combat.fighter.attacks.all {
                    it.masteryBonusPercent == 0 && it.sourceDamagePercentMin == 0 &&
                        it.sourceDamagePercentMax == 0
                }
        })
    }

    @Test
    fun `local reserve keeps varied raw profiles while every class primary stays ordinary`() {
        val engine = SimpleGameEngine()
        listOf(10L, 20L, 25L, 30L, 100L).forEach { level ->
            val profiles = ArenaLocalReserveMatchmaking.definitions.associateWith { definition ->
                ArenaLocalReserveMatchmaking.representativeRawStats(engine, definition, level)
            }
            assertEquals(
                "Lv.$level local identities must not share an averaged stat block",
                ArenaLocalReserveMatchmaking.reserveSize,
                profiles.values.map { it.values() }.distinct().size,
            )

            val growthSteps = level - 1L
            val primaryMean = 10.5 + growthSteps * (2.0 / 3.0)
            val primaryDeviation = sqrt(8.75 + growthSteps * (7.0 / 18.0))
            val primaryRange = ceil(primaryMean - 0.50 * primaryDeviation).toLong()..
                floor(primaryMean + 0.50 * primaryDeviation).toLong()
            val totalMean = 63.0 + growthSteps * 2.0
            val totalDeviation = sqrt(52.5)
            val totalRange = ceil(totalMean - 1.15 * totalDeviation).toLong()..
                floor(totalMean + 1.15 * totalDeviation).toLong()

            profiles.forEach { (definition, stats) ->
                val primary = stats.values()[definition.heroClass.primaryStatIndex]
                val total = stats.values().take(6).sum()
                assertTrue(
                    "Lv.$level ${definition.name} primary=$primary expected=$primaryRange",
                    primary in primaryRange,
                )
                assertTrue(
                    "Lv.$level ${definition.name} total=$total expected=$totalRange",
                    total in totalRange,
                )
            }
        }
    }

    @Test
    fun `local reserve stays inside independent generated character percentiles through release range`() {
        val startedAtNanos = System.nanoTime()
        val engine = SimpleGameEngine()
        val levels = listOf(10L, 20L, 25L, 30L, 100L)

        levels.forEach { level ->
            val referenceByClass = HeroClass.entries.associateWith { heroClass ->
                syntheticReferenceCohort(engine, heroClass, level, REFERENCE_COHORT_SIZE)
            }
            ArenaLocalReserveMatchmaking.definitions.forEach { definition ->
                val raw = ArenaLocalReserveMatchmaking.representativeRawStats(
                    engine,
                    definition,
                    level,
                )
                val reference = referenceByClass.getValue(definition.heroClass)
                val primaryIndex = definition.heroClass.primaryStatIndex

                assertBetweenPercentiles(
                    label = "Lv.$level ${definition.name} raw primary",
                    value = raw.values()[primaryIndex],
                    reference = reference.map { it.values()[primaryIndex] },
                    lowerPercentile = 0.30,
                    upperPercentile = 0.70,
                )
                assertBetweenPercentiles(
                    label = "Lv.$level ${definition.name} raw total",
                    value = raw.values().take(BASE_STAT_COUNT).sum(),
                    reference = reference.map { it.values().take(BASE_STAT_COUNT).sum() },
                    lowerPercentile = 0.10,
                    upperPercentile = 0.90,
                )
                assertBetweenPercentiles(
                    label = "Lv.$level ${definition.name} raw max HP",
                    value = raw.maxHealth,
                    reference = reference.map(HeroStats::maxHealth),
                    lowerPercentile = 0.10,
                    upperPercentile = 0.90,
                )
                assertBetweenPercentiles(
                    label = "Lv.$level ${definition.name} raw max MP",
                    value = raw.maxMana,
                    reference = reference.map(HeroStats::maxMana),
                    lowerPercentile = 0.10,
                    upperPercentile = 0.90,
                )

                val averagePower = ((1L + (level - 1L) * 5L) * 2L).coerceAtLeast(1L)
                val combatPower = ((averagePower * definition.powerPermille.toLong()) + 500L)
                    .div(1_000L)
                val finalStats = requireNotNull(PublicPlayerBattleDerivation.deriveStats(
                    heroClass = definition.heroClass,
                    level = level,
                    combatPower = combatPower,
                    rawStats = raw,
                ))
                val finalPrimaryReference = reference.map { referenceStats ->
                    requireNotNull(PublicPlayerBattleDerivation.deriveStats(
                        heroClass = definition.heroClass,
                        level = level,
                        combatPower = combatPower,
                        rawStats = referenceStats,
                    )).values()[primaryIndex]
                }
                val finalPrimaryUpper = empiricalPercentile(finalPrimaryReference, 0.90)
                assertTrue(
                    "Lv.$level ${definition.name} final primary=" +
                        "${finalStats.values()[primaryIndex]} exceeds P90=$finalPrimaryUpper",
                    finalStats.values()[primaryIndex].toDouble() <= finalPrimaryUpper,
                )
            }
        }

        println(
            "arena-local-reserve-percentiles levels=$levels cohort=$REFERENCE_COHORT_SIZE " +
                "runtimeMs=${(System.nanoTime() - startedAtNanos) / 1_000_000L}",
        )
    }

    @Test
    fun `release range local reserve remains at current level and invalid request fails closed`() {
        val releaseRange = requireNotNull(ArenaLocalReserveMatchmaking.build(
            requesterLevel = 100L,
            count = 1,
        )).single()
        assertEquals(100L, releaseRange.projection.level)
        assertEquals(100L, releaseRange.combat.fighter.level)

        val unavailable = selectArenaOpponent(
            serverRosterEnabled = true,
            roster = null,
            requesterCharacterId = REQUESTER,
            requesterLevel = 9L,
            nowEpochMillis = NOW,
            completedMatchSequence = 0L,
        ) as ArenaServerMatchSelectionResult.Unavailable
        assertEquals(ArenaServerMatchUnavailableReason.LOCAL_RESERVE_FAILED, unavailable.reason)
    }

    @Test
    fun `missing expired malformed and empty rosters fail closed`() {
        fun select(value: PublicPlayerRoster?) = selectArenaServerOpponent(
            enabled = true,
            roster = value,
            requesterCharacterId = REQUESTER,
            requesterLevel = 10L,
            nowEpochMillis = NOW,
            completedMatchSequence = 0L,
        ) as ArenaServerMatchSelectionResult.Unavailable

        assertEquals(ArenaServerMatchUnavailableReason.MISSING_ROSTER, select(null).reason)
        assertEquals(
            ArenaServerMatchUnavailableReason.INVALID_OR_EXPIRED_ROSTER,
            select(roster(snapshot()).copy(validUntilEpochMillis = NOW)).reason,
        )
        assertEquals(
            ArenaServerMatchUnavailableReason.INVALID_OR_EXPIRED_ROSTER,
            select(roster(snapshot()).copy(requesterCharacterId = "wrong-character")).reason,
        )
        assertEquals(
            ArenaServerMatchUnavailableReason.EMPTY_VALID_POOL,
            select(roster(snapshot().copy(displayName = "\u0000invalid"))).reason,
        )
        assertEquals(
            ArenaServerMatchUnavailableReason.EMPTY_VALID_POOL,
            select(roster()).reason,
        )
    }

    @Test
    fun `public match replacement keeps local fighter and uses stable roster battle seed`() {
        val source = roster(snapshot())
        val selected = selectArenaServerOpponent(
            enabled = true,
            roster = source,
            requesterCharacterId = REQUESTER,
            requesterLevel = 10L,
            nowEpochMillis = NOW,
            completedMatchSequence = 7L,
        ) as ArenaServerMatchSelectionResult.Ready
        val engine = SimpleGameEngine()
        val roll = engine.rollStats(77L, HeroClass.WARRIOR)
        val state = engine.newGame("로컬영웅", HeroClass.WARRIOR, roll.stats, roll.nextSeed, NOW).apply {
            hero.level = 10L
            rankingCharacterId = REQUESTER
        }
        val synthetic = BattleQaMatchFactory.createMatch(
            state = state,
            combatPower = 92L,
            guidance = BattleGuidance.BALANCED,
            userScore = 1_027,
            matchSequence = 7,
            battleId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            serverSeed = 999L,
            requestedAtMillis = NOW,
        )

        val actual = synthetic.withArenaServerOpponent(selected, 1_027)

        assertEquals(synthetic.request.user, actual.request.user)
        assertEquals(selected.opponent.projection, actual.request.opponent)
        assertEquals(selected.battleSeed, actual.request.serverSeed)
        assertEquals(selected.opponent.projection.verifiedPower, actual.opponentCombatPower)
        assertTrue(actual.request.opponent.equipment.isEmpty())
        assertTrue(actual.request.opponent.skills.all { it.masteryLevel == 0 })
    }

    @Test
    fun `adaptive eligibility fills weak roster gaps without altering accepted public fighters`() {
        // Public eligibility now compares the player's actual power; accepted snapshots stay unmodified.
        val weak = snapshot(id = serverId(1)).copy(combatPower = 62L)
        val normal = snapshot(id = serverId(2))
        val source = roster(weak, normal, snapshot(id=serverId(3)).copy(level=9L,combatPower=74L))
        val before = source.copy(snapshots = source.snapshots.toList())
        val selected = (0L until 20L).map { sequence ->
            selectArenaOpponent(true,source,REQUESTER,10L,NOW,sequence,requesterCombatPower=60L)
                as ArenaServerMatchSelectionResult.Ready
        }
        assertTrue(selected.all { it.serverPoolSize == 1 && it.localPoolSize == 19 })
        val public = selected.single { it.source == ArenaOpponentSource.PUBLIC_ROSTER }
        val original = PublicPlayerArenaInputAdapter.fromRosterSnapshot(weak,10,
            arenaAutoBuildAllocationSeed(weak.projectionId),source.receivedAtEpochMillis)!!
        assertEquals(original,public.opponent)
        assertEquals(before,source)
        // Even a full server roster is replaced only where it has no eligible strength band.
        val noFit = selectArenaOpponent(true,rosterCount(20),REQUESTER,10L,NOW,0,requesterCombatPower=60L)
            as ArenaServerMatchSelectionResult.Ready
        assertEquals(0,noFit.serverPoolSize); assertEquals(20,noFit.localPoolSize)
        val allFit = selectArenaOpponent(true,rosterCount(20),REQUESTER,10L,NOW,0,requesterCombatPower=92L)
            as ArenaServerMatchSelectionResult.Ready
        assertEquals(20,allFit.serverPoolSize); assertEquals(0,allFit.localPoolSize)
    }

    private fun roster(vararg values: PublicPlayerSnapshot) = PublicPlayerRoster(
        requesterCharacterId = REQUESTER,
        requesterLevel = 10L,
        rosterId = "88888888-8888-4888-8888-888888888888",
        rosterDateUtc = "2026-09-07",
        rulesVersion = SHARED_PLAYER_RULES_VERSION,
        receivedAtEpochMillis = NOW - 1_000L,
        validUntilEpochMillis = NOW + 10_000L,
        snapshots = values.toList(),
    )

    private fun rosterCount(count: Int): PublicPlayerRoster = roster(
        *(0 until count).map { index ->
            snapshot(
                id = serverId(index + 1),
                name = "Server${index + 1}",
                heroClass = HeroClass.entries[index % HeroClass.entries.size],
            )
        }.toTypedArray(),
    )

    private fun selectPool(source: PublicPlayerRoster, sequence: Long) =
        selectArenaOpponent(
            serverRosterEnabled = true,
            roster = source,
            requesterCharacterId = REQUESTER,
            requesterLevel = 10L,
            nowEpochMillis = NOW,
            completedMatchSequence = sequence,
        ) as ArenaServerMatchSelectionResult.Ready

    private fun serverId(index: Int): String =
        "10000000-0000-4000-8000-${index.toString().padStart(12, '0')}"

    private fun snapshot(
        id: String = "11111111-1111-4111-8111-111111111111",
        name: String = "서버모험가",
        heroClass: HeroClass = HeroClass.RANGER,
    ) = PublicPlayerSnapshot(
        projectionId = id,
        displayName = name,
        heroClass = heroClass,
        level = 10L,
        combatPower = 92L,
        rulesVersion = SHARED_PLAYER_RULES_VERSION,
        snapshotVersion = SHARED_PLAYER_SNAPSHOT_VERSION,
        stats = PublicPlayerStats(10, 11, 12, 13, 14, 15, 180, 90),
        adventureTraitIds = emptyList(),
    )

    private fun syntheticReferenceCohort(
        engine: SimpleGameEngine,
        heroClass: HeroClass,
        level: Long,
        size: Int,
    ): List<HeroStats> = List(size) { sample ->
        val prefix = "arena-reserve-percentile-reference-v1|$level|${heroClass.name}|$sample"
        val roll = engine.rollStats(arenaStableHash64("$prefix|roll"), heroClass)
        roll.stats.copy().also { stats ->
            ArenaSyntheticProfileGrowth.grow(
                engine = engine,
                stats = stats,
                heroClass = heroClass,
                targetLevel = level,
                identitySeed = arenaStableHash64("$prefix|growth"),
            )
        }
    }

    private fun assertBetweenPercentiles(
        label: String,
        value: Long,
        reference: List<Long>,
        lowerPercentile: Double,
        upperPercentile: Double,
    ) {
        val lower = empiricalPercentile(reference, lowerPercentile)
        val upper = empiricalPercentile(reference, upperPercentile)
        assertTrue(
            "$label=$value outside P${(lowerPercentile * 100).toInt()}.." +
                "P${(upperPercentile * 100).toInt()} ($lower..$upper)",
            value.toDouble() in lower..upper,
        )
    }

    private fun empiricalPercentile(values: List<Long>, percentile: Double): Double {
        require(values.isNotEmpty())
        require(percentile in 0.0..1.0)
        val sorted = values.sorted()
        val position = percentile * (sorted.lastIndex).toDouble()
        val lowerIndex = floor(position).toInt()
        val upperIndex = ceil(position).toInt()
        if (lowerIndex == upperIndex) return sorted[lowerIndex].toDouble()
        val upperWeight = position - lowerIndex.toDouble()
        return sorted[lowerIndex] * (1.0 - upperWeight) + sorted[upperIndex] * upperWeight
    }

    private companion object {
        const val REQUESTER = "99999999-9999-4999-8999-999999999999"
        const val NOW = 1_790_000_000_000L
        const val BASE_STAT_COUNT = 6
        const val REFERENCE_COHORT_SIZE = 512
    }
}
