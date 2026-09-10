package com.nullplaying.engine.arena

import com.nullplaying.engine.PublicPlayerBattleDerivation
import com.nullplaying.engine.SkillCatalog
import com.nullplaying.model.HeroClass
import com.nullplaying.model.PublicPlayerRoster
import com.nullplaying.model.PublicPlayerSnapshot
import com.nullplaying.model.PublicPlayerStats
import com.nullplaying.model.SHARED_PLAYER_RULES_VERSION
import com.nullplaying.model.SHARED_PLAYER_SNAPSHOT_VERSION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicPlayerArenaInputAdapterTest {
    @Test
    fun `average power preserves public identity and locally builds a frozen V6 tree`() {
        val snapshot = snapshot(combatPower = 92L)

        val input = PublicPlayerArenaInputAdapter.fromSnapshot(
            snapshot = snapshot,
            arenaLevel = 10,
            stableSeed = 73L,
        )

        assertNotNull(input)
        val combat = input!!.combat
        assertEquals(snapshot.projectionId, combat.fighter.id)
        assertEquals(snapshot.heroClass, combat.fighter.heroClass)
        assertEquals(snapshot.level, combat.fighter.level)
        assertEquals(
            listOf(10.0, 11.0, 12.0, 13.0, 14.0, 15.0, 180.0, 90.0),
            combat.fighter.stats.values(),
        )
        val definitions = SkillCatalog.forClass(HeroClass.RANGER)
            .filter { it.unlockLevel <= snapshot.level }
        val expectedSkills = definitions.map { it.catalogId }.toSet()
        assertTrue(combat.fighter.attacks.map { it.id }.all { it in expectedSkills })
        assertTrue(combat.fighter.attacks.all {
            it.arena != null && it.masteryBonusPercent == 0 &&
                it.sourceDamagePercentMin == 0 && it.sourceDamagePercentMax == 0
        })
        assertEquals(10, combat.arenaLevel)
        assertEquals(combat.supportIds, combat.supportRanks.keys)
        assertEquals(combat.supportIds, combat.resolvedSupports.keys)
        assertTrue(combat.traits.isEmpty())
        ArenaSupportTurnEngine.validate(combat)

        val projection = input.projection
        assertEquals(snapshot.combatPower, projection.verifiedPower)
        assertEquals(snapshot.stats.strength, projection.build.strength)
        assertEquals(combat.fighter.attacks.map { it.id }, projection.skills.map { it.skillId })
        assertTrue(projection.skills.all { it.masteryLevel == 0 })
        assertTrue(projection.equipment.isEmpty())
        assertTrue(projection.activeTraitIds.isEmpty())
    }

    @Test
    fun `power deviation is bounded deterministic and never fabricates equipment`() {
        val high = snapshot(combatPower = 120L)
        val first = PublicPlayerArenaInputAdapter.fromSnapshot(high)
        val second = PublicPlayerArenaInputAdapter.fromSnapshot(high)

        assertEquals(first, second)
        assertNotNull(first)
        assertEquals(1_200, PublicPlayerBattleDerivation.powerScalePermille(10L, 10_000L))
        assertNotEquals(high.stats.maxHealth.toDouble(), first!!.combat.fighter.stats.rawMaxHealth)
        assertTrue(first.projection.equipment.isEmpty())
    }

    @Test
    fun `daily roster resolves same projection and current level band`() {
        val snapshot = snapshot()
        val roster = PublicPlayerRoster(
            requesterCharacterId = "99999999-9999-4999-8999-999999999999",
            requesterLevel = 10L,
            rosterId = "88888888-8888-4888-8888-888888888888",
            rosterDateUtc = "2026-09-07",
            rulesVersion = SHARED_PLAYER_RULES_VERSION,
            receivedAtEpochMillis = 1_000L,
            validUntilEpochMillis = 2_000L,
            snapshots = listOf(snapshot),
        )

        val stableSeed = arenaStableHash64("${roster.rosterId}|${snapshot.projectionId}|1")
        val direct = PublicPlayerArenaInputAdapter.fromSnapshot(snapshot, stableSeed = stableSeed)!!
        val fromRoster = PublicPlayerArenaInputAdapter.fromRoster(
            roster,
            snapshot.projectionId,
            1_500L,
            stableSeed = stableSeed,
        )!!
        assertEquals(direct.combat, fromRoster.combat)
        assertEquals(direct.projection.copy(issuedAtMillis = roster.receivedAtEpochMillis), fromRoster.projection)
        assertNull(PublicPlayerArenaInputAdapter.fromRoster(
            roster,
            "77777777-7777-4777-8777-777777777777",
            1_500L,
        ))
        assertNull(PublicPlayerArenaInputAdapter.fromRoster(roster, snapshot.projectionId, 2_000L))
    }

    @Test
    fun `roster refresh metadata cannot reroll one opponent build`() {
        val opponent = snapshot(
            combatPower = 292L,
            level = 30L,
        )
        fun roster(rosterId: String, receivedAt: Long) = PublicPlayerRoster(
            requesterCharacterId = "99999999-9999-4999-8999-999999999999",
            requesterLevel = 30L,
            rosterId = rosterId,
            rosterDateUtc = "2026-09-07",
            rulesVersion = SHARED_PLAYER_RULES_VERSION,
            receivedAtEpochMillis = receivedAt,
            validUntilEpochMillis = 5_000L,
            snapshots = listOf(opponent),
        )

        val first = requireNotNull(PublicPlayerArenaInputAdapter.fromRoster(
            roster("88888888-8888-4888-8888-888888888888", 1_000L),
            opponent.projectionId,
            nowEpochMillis = 3_000L,
            arenaLevel = 30,
        ))
        val refreshed = requireNotNull(PublicPlayerArenaInputAdapter.fromRoster(
            roster("77777777-7777-4777-8777-777777777777", 2_000L),
            opponent.projectionId,
            nowEpochMillis = 3_000L,
            arenaLevel = 30,
        ))

        assertEquals(first.autoBuildPresetId, refreshed.autoBuildPresetId)
        assertEquals(first.combat, refreshed.combat)
        assertNotEquals(first.projection.issuedAtMillis, refreshed.projection.issuedAtMillis)
    }

    @Test
    fun `same projection keeps one preset and extends one allocation route across requesters and arena levels`() {
        val opponent = snapshot(combatPower = 292L, level = 30L)
        val requesters = listOf(
            "99999999-9999-4999-8999-999999999999",
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        )
        val inputs = requesters.flatMapIndexed { requesterIndex, requesterId ->
            val roster = PublicPlayerRoster(
                requesterCharacterId = requesterId,
                requesterLevel = 30L,
                rosterId = "88888888-8888-4888-8888-88888888888$requesterIndex",
                rosterDateUtc = "2026-09-07",
                rulesVersion = SHARED_PLAYER_RULES_VERSION,
                receivedAtEpochMillis = 1_000L + requesterIndex,
                validUntilEpochMillis = 5_000L,
                snapshots = listOf(opponent),
            )
            listOf(20, 25, 30).map { arenaLevel ->
                requireNotNull(PublicPlayerArenaInputAdapter.fromRoster(
                    roster = roster,
                    projectionId = opponent.projectionId,
                    nowEpochMillis = 3_000L,
                    arenaLevel = arenaLevel,
                ))
            }
        }
        assertEquals(1, inputs.map { it.autoBuildPresetId }.distinct().size)
        val byRequester = inputs.chunked(3)
        assertEquals(
            byRequester.first().map(::rankMap),
            byRequester.last().map(::rankMap),
        )
        byRequester.forEach { route ->
            route.map(::rankMap).zipWithNext().forEach { (before, after) ->
                assertTrue(before.all { (nodeId, rank) -> (after[nodeId] ?: 0) >= rank })
            }
        }
    }

    @Test
    fun `invalid health fails and oversized raw stats are normalized before battle`() {
        val snapshot = snapshot()
        assertNull(PublicPlayerArenaInputAdapter.fromSnapshot(
            snapshot.copy(stats = snapshot.stats.copy(maxHealth = 0L)),
        ))
        val oversized = snapshot.copy(stats = snapshot.stats.copy(
            strength = 1_000_000L,
            maxHealth = 1_000_000L,
        ))
        val normalized = PublicPlayerArenaInputAdapter.fromSnapshot(oversized)
        assertNotNull(normalized)
        assertTrue(normalized!!.combat.fighter.stats.strength < 1_000_000.0)
        assertTrue(normalized.combat.fighter.stats.rawMaxHealth < 1_000_000.0)
    }

    private fun snapshot(
        combatPower: Long = 92L,
        level: Long = 10L,
    ): PublicPlayerSnapshot = PublicPlayerSnapshot(
        projectionId = "11111111-1111-4111-8111-111111111111",
        displayName = "바람길🌟여행자",
        heroClass = HeroClass.RANGER,
        level = level,
        combatPower = combatPower,
        rulesVersion = SHARED_PLAYER_RULES_VERSION,
        snapshotVersion = SHARED_PLAYER_SNAPSHOT_VERSION,
        stats = PublicPlayerStats(10L, 11L, 12L, 13L, 14L, 15L, 180L, 90L),
        adventureTraitIds = listOf("R03", "T02"),
    )

    private fun rankMap(input: PublicPlayerArenaMatchInput): Map<String, Int> = buildMap {
        input.combat.fighter.attacks.forEach { attack ->
            put(attack.id, requireNotNull(attack.arena).rank)
        }
        putAll(input.combat.supportRanks)
    }
}
