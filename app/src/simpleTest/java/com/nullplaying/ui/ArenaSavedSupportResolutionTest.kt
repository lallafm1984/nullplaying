package com.nullplaying.ui

import com.nullplaying.engine.arena.ArenaSkillTreeCatalog
import com.nullplaying.engine.arena.ArenaStatFormula
import com.nullplaying.engine.arena.ArenaSupportCatalog
import com.nullplaying.engine.arena.ArenaSupportEventType
import com.nullplaying.engine.arena.ArenaSupportInput
import com.nullplaying.engine.arena.ArenaSupportQaFixtures
import com.nullplaying.engine.arena.ArenaSupportTraitRank
import com.nullplaying.engine.arena.ArenaSupportTurnEngine
import com.nullplaying.engine.arena.ArenaTurnRules
import com.nullplaying.engine.arena.freezeResolvedSupports
import com.nullplaying.model.HeroClass
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArenaSavedSupportResolutionTest {
    @Test
    fun `saved v4 battle freezes resolved support execution values and round trips them`() {
        val user = rankedSupportInput()
        val opponent = basicInput(HeroClass.MAGE, "opponent")
        val rules = rules(2)
        val schedule = mapOf(
            ("user" to 1) to SUPPORT_ID,
            ("user" to 2) to "BASIC_ATTACK",
            ("opponent" to 1) to "BASIC_ATTACK",
            ("opponent" to 2) to "BASIC_ATTACK",
        )
        val simulation = ArenaSupportTurnEngine.simulateScripted(
            user,
            opponent,
            seed = 301L,
            schedule = schedule,
            rules = rules,
        )
        val saved = ArenaLiveBattle(
            user = user,
            opponent = opponent,
            simulation = simulation,
            progressionRevision = 7L,
            issuedDay = 55L,
            skillTreeRevision = 9L,
            skillTreeCatalogVersion = 4,
            skillTreeRulesVersion = "arena-skill-tree-v4",
        ).savedContract()

        assertTrue(user.resolvedSupports.isEmpty())
        val frozen = saved.user.resolvedSupports.getValue(SUPPORT_ID)
        val current = ArenaSkillTreeCatalog.effectiveSupport(
            requireNotNull(ArenaSupportCatalog.find(SUPPORT_ID)),
            SUPPORT_RANK,
        )
        assertEquals(SUPPORT_RANK, frozen.rank)
        assertEquals(current.mp, frozen.mp)
        assertEquals(current.cooldownTurns, frozen.cooldownTurns)
        assertEquals(current.charges, frozen.charges)
        assertEquals(current.magnitude, frozen.magnitude, 1e-9)

        val codec = Json { encodeDefaults = true }
        val restored = codec.decodeFromString<ArenaSavedBattleContract>(
            codec.encodeToString(saved),
        )
        assertEquals(saved, restored)
        assertEquals(simulation, ArenaSupportTurnEngine.simulateScripted(
            restored.user,
            restored.opponent,
            restored.seed,
            schedule,
            restored.rules,
        ))
    }

    @Test
    fun `resolved payload replays independently of current rank resolver`() {
        val currentInput = rankedSupportInput().let { input ->
            val opponent = basicInput(HeroClass.MAGE, "opponent")
            ArenaLiveBattle(
                user = input,
                opponent = opponent,
                simulation = ArenaSupportTurnEngine.simulate(
                    input,
                    opponent,
                    seed = 307L,
                    rules = rules(1),
                ),
                progressionRevision = 1L,
                issuedDay = 1L,
            ).savedContract().user
        }
        val currentResolved = currentInput.resolvedSupports.getValue(SUPPORT_ID)
        val historicalResolved = currentResolved.copy(
            mp = 3,
            cooldownTurns = 1,
            durationTurns = 1,
            magnitude = 7.0,
        )
        assertNotEquals(currentResolved.mp, historicalResolved.mp)
        assertNotEquals(currentResolved.magnitude, historicalResolved.magnitude)

        val historicalInput = currentInput.copy(
            resolvedSupports = mapOf(SUPPORT_ID to historicalResolved),
        )
        val codec = Json { encodeDefaults = true }
        val restored = codec.decodeFromString<ArenaSupportInput>(
            codec.encodeToString(historicalInput),
        )
        val opponent = basicInput(HeroClass.MAGE, "opponent")
        val result = ArenaSupportTurnEngine.simulateScripted(
            restored,
            opponent,
            seed = 311L,
            schedule = mapOf(
                ("user" to 1) to SUPPORT_ID,
                ("user" to 2) to "BASIC_ATTACK",
                ("user" to 3) to SUPPORT_ID,
                ("opponent" to 1) to "BASIC_ATTACK",
                ("opponent" to 2) to "BASIC_ATTACK",
                ("opponent" to 3) to "BASIC_ATTACK",
            ),
            rules = rules(3),
        )
        val casts = result.events.filter {
            it.type == ArenaSupportEventType.CAST_START &&
                it.actorId == "user" && it.actionId == SUPPORT_ID
        }
        val applied = result.events.first {
            it.type == ArenaSupportEventType.SUPPORT_APPLIED &&
                it.actorId == "user" && it.actionId == SUPPORT_ID
        }
        assertEquals(listOf(1, 3), casts.map { it.turn })
        assertTrue(casts.all { it.amount == 3.0 })
        assertEquals(2, applied.effectExpiresAtTurn)
        assertEquals(historicalResolved, restored.resolvedSupports.getValue(SUPPORT_ID))
    }

    @Test
    fun `compatibility json without resolved supports remains readable and uses the current resolver`() {
        val legacy = rankedSupportInput()
        val opponent = basicInput(HeroClass.MAGE, "opponent")
        val codec = Json { encodeDefaults = true }
        val encoded = codec.encodeToString(ArenaSavedBattleContract(
            seed = 313L,
            user = legacy,
            opponent = opponent,
            rules = rules(1),
            progressionRevision = 4L,
            issuedDay = 22L,
        ))
        val oldJson = encoded
            .replace(",\"resolvedSupports\":{}", "")
            .replace(",\"arenaClassBalanceEnabled\":false", "")
        assertFalse(oldJson.contains("resolvedSupports"))
        assertFalse(oldJson.contains("arenaClassBalanceEnabled"))

        val restored = codec.decodeFromString<ArenaSavedBattleContract>(oldJson)
        assertTrue(restored.user.resolvedSupports.isEmpty())
        assertTrue(restored.opponent.resolvedSupports.isEmpty())
        assertFalse(restored.user.arenaClassBalanceEnabled)
        assertFalse(restored.opponent.arenaClassBalanceEnabled)
        val result = ArenaSupportTurnEngine.simulateScripted(
            restored.user,
            restored.opponent,
            seed = restored.seed,
            schedule = mapOf(
                ("user" to 1) to SUPPORT_ID,
                ("opponent" to 1) to "BASIC_ATTACK",
            ),
            rules = restored.rules,
        )
        val expectedMp = ArenaSkillTreeCatalog.effectiveSupport(
            requireNotNull(ArenaSupportCatalog.find(SUPPORT_ID)),
            SUPPORT_RANK,
        ).mp.toDouble()
        assertEquals(expectedMp, result.events.single {
            it.type == ArenaSupportEventType.CAST_START && it.actorId == "user"
        }.amount, 1e-9)
    }

    @Test
    fun `freezing a legacy trait contract captures its effective support replacement`() {
        val supportId = "ARENA_SUP_FIGHTER_02"
        val core = ArenaSupportTraitRank("AT9_WARRIOR_A_CORE")
        val legacy = basicInput(HeroClass.WARRIOR, "legacy-user").copy(
            supportIds = setOf(supportId),
            traits = listOf(core),
        )
        val base = requireNotNull(ArenaSupportCatalog.find(supportId))
        val transformed = requireNotNull(ArenaSupportCatalog.effectiveDefinition(
            supportId,
            listOf(core),
        ))

        val frozen = legacy.freezeResolvedSupports().resolvedSupports.getValue(supportId)

        assertEquals(0, frozen.rank)
        assertEquals(transformed.mp, frozen.mp)
        assertEquals(transformed.castTurns, frozen.castTurns)
        assertEquals(base.magnitude, frozen.magnitude, 1e-9)
        assertEquals(base.secondary, frozen.secondary, 1e-9)
    }

    @Test
    fun `legacy core support replay is identical after values are frozen`() {
        val shatterId = "ARENA_SUP_FIGHTER_03"
        val shieldId = "ARENA_SUP_MAGE_01"
        val actor = basicInput(HeroClass.WARRIOR, "legacy-actor").copy(
            supportIds = setOf(shatterId),
            traits = listOf(ArenaSupportTraitRank("AT9_WARRIOR_B_CORE")),
        )
        val target = basicInput(HeroClass.MAGE, "legacy-target").copy(
            supportIds = setOf(shieldId),
        )
        val schedule = mapOf(
            ("legacy-actor" to 1) to "BASIC_ATTACK",
            ("legacy-actor" to 2) to shatterId,
            ("legacy-actor" to 3) to "BASIC_ATTACK",
            ("legacy-target" to 1) to shieldId,
            ("legacy-target" to 2) to "BASIC_ATTACK",
            ("legacy-target" to 3) to "BASIC_ATTACK",
        )
        val rules = rules(3)

        val live = ArenaSupportTurnEngine.simulateScripted(actor, target, 317L, schedule, rules)
        val replay = ArenaSupportTurnEngine.simulateScripted(
            actor.freezeResolvedSupports(),
            target.freezeResolvedSupports(),
            317L,
            schedule,
            rules,
        )

        assertEquals(live, replay)
        assertTrue(live.events.any {
            it.type == ArenaSupportEventType.SHIELD_ABSORBED &&
                it.actionId == shatterId && it.reason == "extra_shatter_no_hp"
        })
    }

    private fun rankedSupportInput(): ArenaSupportInput =
        basicInput(HeroClass.WARRIOR, "user").copy(
            supportIds = setOf(SUPPORT_ID),
            supportRanks = mapOf(SUPPORT_ID to SUPPORT_RANK),
        )

    private fun basicInput(heroClass: HeroClass, id: String): ArenaSupportInput {
        val fixture = ArenaSupportQaFixtures.fullFighter(heroClass, 95, id)
        return ArenaSupportInput(
            fighter = fixture.fighter.copy(attacks = emptyList()),
            arenaLevel = 100,
        )
    }

    private fun rules(turns: Int) = ArenaTurnRules(
        safetyTurnLimit = turns,
        hitChance = 1.0,
        damageVariance = 0.0,
        tierScaling = false,
        masteryScaling = false,
        formula = ArenaStatFormula(
            healthBase = 1_000.0,
            healthScale = 0.0,
            attackBase = 10.0,
            attackScale = 0.0,
        ),
    )

    private companion object {
        const val SUPPORT_ID = "ARENA_SUP_FIGHTER_01"
        const val SUPPORT_RANK = 10
    }
}
