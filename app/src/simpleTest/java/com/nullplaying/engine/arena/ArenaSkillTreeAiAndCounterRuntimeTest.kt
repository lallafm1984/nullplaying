package com.nullplaying.engine.arena

import com.nullplaying.engine.SkillCatalog
import com.nullplaying.model.HeroClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for V6 attack utility scoring and frozen A19 counter payloads. */
class ArenaSkillTreeAiAndCounterRuntimeTest {
    @Test
    fun `automatic choice values the frozen A02 bleed while an enemy attack is preparing`() {
        val punishId = attackId(HeroClass.WARRIOR, 2)
        val neutralId = attackId(HeroClass.WARRIOR, 1)
        val longAttackId = attackId(HeroClass.MAGE, 16)
        val target = fighterWithAttacks(
            HeroClass.MAGE,
            "target",
            listOf(AttackSpec(16, rank = 1)),
        )
        fun actor(bleedTick: Double) = fighterWithAttacks(
            HeroClass.WARRIOR,
            "actor",
            listOf(
                AttackSpec(1) { resolved -> resolved.neutralDirect() },
                AttackSpec(2) { resolved ->
                    resolved.neutralDirect().copy(
                        effectKey = "PREPARATION_PUNISH",
                        effectValues = mapOf(
                            "bonus" to 0.0,
                            "masterBleedTick" to bleedTick,
                            "masterBleedTurns" to if (bleedTick > 0.0) 2.0 else 0.0,
                        ),
                    )
                },
            ),
        )
        val schedule = mapOf(
            ("actor" to 1) to "BASIC_ATTACK",
            ("target" to 1) to longAttackId,
        )

        val withFrozenBleed = ArenaSupportTurnEngine.simulateScripted(
            actor(18.0),
            target,
            seed = 101L,
            schedule = schedule,
            rules = rules(safetyTurnLimit = 2),
        )
        val withoutFrozenBleed = ArenaSupportTurnEngine.simulateScripted(
            actor(0.0),
            target,
            seed = 101L,
            schedule = schedule,
            rules = rules(safetyTurnLimit = 2),
        )

        assertEquals(punishId, startedAction(withFrozenBleed, "actor", turn = 2))
        assertEquals("BASIC_ATTACK", startedAction(withoutFrozenBleed, "actor", turn = 2))
        assertTrue(neutralId != punishId)
    }

    @Test
    fun `automatic choice values the frozen Mage A19 burn`() {
        val counterId = attackId(HeroClass.MAGE, 19)
        fun actor(burn: Double) = fighterWithAttacks(
            HeroClass.MAGE,
            "actor",
            listOf(
                AttackSpec(1) { resolved -> resolved.neutralDirect() },
                AttackSpec(19) { resolved ->
                    resolved.neutralDirect().copy(
                        effectKey = "COUNTER_RUSH",
                        effectValues = mapOf("prepareReduction" to 0.0, "burn" to burn),
                        durationTurns = 2,
                        maxApplications = 0,
                    )
                },
            ),
        )

        val withFrozenBurn = ArenaSupportTurnEngine.simulate(
            actor(55.0),
            basicOnly(HeroClass.WARRIOR, "target"),
            seed = 103L,
            rules = rules(safetyTurnLimit = 1),
        )
        val withoutFrozenBurn = ArenaSupportTurnEngine.simulate(
            actor(0.0),
            basicOnly(HeroClass.WARRIOR, "target"),
            seed = 103L,
            rules = rules(safetyTurnLimit = 1),
        )

        assertEquals(counterId, startedAction(withFrozenBurn, "actor", turn = 1))
        assertEquals("BASIC_ATTACK", startedAction(withoutFrozenBurn, "actor", turn = 1))
    }

    @Test
    fun `Ranger A19 is not scored as fully bypassing a four image mirror`() {
        val counterId = attackId(HeroClass.RANGER, 19)
        val aimId = "ARENA_SUP_RANGER_01"
        val mirrorId = "ARENA_SUP_MAGE_02"
        val actor = fighterWithAttacks(
            HeroClass.RANGER,
            "actor",
            listOf(
                AttackSpec(1) { resolved -> resolved.neutralDirect() },
                AttackSpec(19) { resolved ->
                    resolved.neutralDirect().copy(
                        effectKey = "COUNTER_ILLUSION_HEAL",
                        effectValues = mapOf("remove" to 1.0, "bonus" to 0.0, "window" to 2.0),
                    )
                },
            ),
            supportRanks = mapOf(aimId to 1),
        )
        val target = supportFighter(HeroClass.MAGE, "target", mapOf(mirrorId to 10))
        val result = ArenaSupportTurnEngine.simulateScripted(
            actor,
            target,
            seed = 107L,
            schedule = mapOf(
                ("actor" to 1) to "BASIC_ATTACK",
                ("actor" to 2) to aimId,
                ("target" to 1) to mirrorId,
            ),
            rules = rules(safetyTurnLimit = 3),
        )

        assertEquals("BASIC_ATTACK", startedAction(result, "actor", turn = 3))
        assertTrue(result.events.none {
            it.type == ArenaSupportEventType.CAST_START && it.actorId == "actor" &&
                it.turn == 3 && it.actionId == counterId
        })
    }

    @Test
    fun `Ranger A19 removes the frozen number of illusion charges before mirror checks`() {
        val counterId = attackId(HeroClass.RANGER, 19)
        val aimId = "ARENA_SUP_RANGER_01"
        val mirrorId = "ARENA_SUP_MAGE_02"
        val actor = fighterWithAttacks(
            HeroClass.RANGER,
            "actor",
            listOf(AttackSpec(19) { resolved ->
                resolved.copy(effectValues = resolved.effectValues + ("remove" to 2.0))
            }),
            supportRanks = mapOf(aimId to 1),
        )
        val result = ArenaSupportTurnEngine.simulateScripted(
            actor,
            supportFighter(HeroClass.MAGE, "target", mapOf(mirrorId to 10)),
            seed = 109L,
            schedule = mapOf(
                ("actor" to 1) to "BASIC_ATTACK",
                ("actor" to 2) to aimId,
                ("actor" to 3) to counterId,
                ("target" to 1) to mirrorId,
            ),
            rules = rules(safetyTurnLimit = 5),
        )

        val removedCharges = result.events.filter {
            it.type == ArenaSupportEventType.STATUS_REMOVED && it.turn == 5 &&
                it.actorId == "target" && it.actionId == mirrorId &&
                it.reason == "illusion_charge_removed"
        }
        assertEquals(2, removedCharges.size)
    }

    @Test
    fun `Mage A19 preparation reduction uses frozen strength and application count`() {
        val counterId = attackId(HeroClass.MAGE, 19)
        val instantId = attackId(HeroClass.WARRIOR, 1)
        val actor = fighterWithAttacks(
            HeroClass.MAGE,
            "actor",
            listOf(AttackSpec(19) { resolved ->
                resolved.copy(
                    effectValues = resolved.effectValues + ("prepareReduction" to 2.0),
                    maxApplications = 2,
                )
            }),
        )
        val target = fighterWithAttacks(
            HeroClass.WARRIOR,
            "target",
            listOf(AttackSpec(1) { resolved ->
                resolved.copy(mpCost = 0, cooldownTurns = 0, damagePercent = 1)
            }),
        )
        val result = ArenaSupportTurnEngine.simulateScripted(
            actor,
            target,
            seed = 113L,
            schedule = mapOf(
                ("actor" to 1) to "BASIC_ATTACK",
                ("actor" to 2) to counterId,
                ("actor" to 8) to "BASIC_ATTACK",
                ("actor" to 9) to counterId,
                ("actor" to 15) to "BASIC_ATTACK",
                ("actor" to 16) to counterId,
                ("target" to 1) to instantId,
                ("target" to 8) to instantId,
                ("target" to 15) to instantId,
            ),
            rules = rules(safetyTurnLimit = 18, attackBase = 1.0),
        )

        val starts = result.events.filter {
            it.type == ArenaSupportEventType.CAST_START && it.actorId == "actor" &&
                it.actionId == counterId
        }
        assertEquals(listOf(2, 9, 16), starts.map { it.turn })
        assertEquals(listOf(1, 1, 3), starts.map { it.castTurns })
    }

    @Test
    fun `Cleric A19 removes the frozen number of harmful statuses on preparation start`() {
        val counterId = attackId(HeroClass.CLERIC, 19)
        val smokeId = "ARENA_SUP_ROGUE_02"
        val poisonId = "ARENA_SUP_ROGUE_11"
        val actor = fighterWithAttacks(
            HeroClass.CLERIC,
            "actor",
            listOf(AttackSpec(19) { resolved ->
                resolved.copy(effectValues = resolved.effectValues + ("cleanse" to 2.0))
            }),
        )
        val target = supportFighter(
            HeroClass.ROGUE,
            "target",
            mapOf(smokeId to 10, poisonId to 10),
        )
        val result = ArenaSupportTurnEngine.simulateScripted(
            actor,
            target,
            seed = 127L,
            schedule = mapOf(
                ("actor" to 1) to "BASIC_ATTACK",
                ("actor" to 2) to "BASIC_ATTACK",
                ("actor" to 3) to "BASIC_ATTACK",
                ("actor" to 4) to "BASIC_ATTACK",
                ("actor" to 5) to counterId,
                ("target" to 1) to smokeId,
                ("target" to 2) to "BASIC_ATTACK",
                ("target" to 3) to poisonId,
                ("target" to 4) to "BASIC_ATTACK",
            ),
            rules = rules(safetyTurnLimit = 5),
        )

        val removed = result.events.filter {
            it.type == ArenaSupportEventType.STATUS_REMOVED && it.turn == 5 &&
                it.actorId == "actor" && it.actionId == counterId
        }
        assertEquals(2, removed.size)
        assertEquals(setOf("accuracy", "poison"), removed.mapNotNull { it.reason }.toSet())
        assertTrue(result.events.any {
            it.type == ArenaSupportEventType.CAST_START && it.turn == 5 &&
                it.actorId == "actor" && it.actionId == counterId
        })
    }

    private class AttackSpec(
        val slot: Int,
        val rank: Int = 10,
        val mutate: (ArenaResolvedAttack) -> ArenaResolvedAttack = { it },
    )

    private fun ArenaResolvedAttack.neutralDirect(): ArenaResolvedAttack = copy(
        prepareTurns = 0,
        mpCost = 0,
        cooldownTurns = 0,
        damagePercent = 100,
        effectKey = "NONE",
        effectValues = emptyMap(),
        durationTurns = 0,
        maxApplications = 0,
        oncePerBattle = false,
        earliestTurn = 1,
        hpDamageCapPercent = 100.0,
        targetHpFloor = 0,
    )

    private fun fighterWithAttacks(
        heroClass: HeroClass,
        id: String,
        attacks: List<AttackSpec>,
        supportRanks: Map<String, Int> = emptyMap(),
    ): ArenaSupportInput {
        val fixture = ArenaSupportQaFixtures.fullFighter(heroClass, 95, id)
        val resolvedAttacks = attacks.map { spec ->
            val source = fixture.fighter.attacks.first { it.id == attackId(heroClass, spec.slot) }
            val resolved = requireNotNull(
                ArenaTurnInputAdapter.resolveAttack(source, heroClass, spec.rank).arena,
            )
            source.copy(
                masteryBonusPercent = 0,
                sourceDamagePercentMin = 0,
                sourceDamagePercentMax = 0,
                arena = spec.mutate(resolved),
            )
        }
        return ArenaSupportInput(
            fighter = fixture.fighter.copy(attacks = resolvedAttacks),
            supportIds = supportRanks.keys,
            arenaLevel = 100,
            supportRanks = supportRanks,
        )
    }

    private fun supportFighter(
        heroClass: HeroClass,
        id: String,
        supportRanks: Map<String, Int>,
    ): ArenaSupportInput {
        val fixture = ArenaSupportQaFixtures.fullFighter(heroClass, 95, id)
        return ArenaSupportInput(
            fighter = fixture.fighter.copy(attacks = emptyList()),
            supportIds = supportRanks.keys,
            arenaLevel = 100,
            supportRanks = supportRanks,
        )
    }

    private fun basicOnly(heroClass: HeroClass, id: String): ArenaSupportInput {
        val fixture = ArenaSupportQaFixtures.fullFighter(heroClass, 95, id)
        return ArenaSupportInput(
            fighter = fixture.fighter.copy(attacks = emptyList()),
            arenaLevel = 100,
        )
    }

    private fun startedAction(result: ArenaSupportResult, actorId: String, turn: Int): String =
        requireNotNull(result.events.single {
            it.type == ArenaSupportEventType.CAST_START && it.actorId == actorId && it.turn == turn
        }.actionId)

    private fun attackId(heroClass: HeroClass, slot: Int): String =
        SkillCatalog.forClass(heroClass)[slot - 1].catalogId

    private fun rules(
        safetyTurnLimit: Int,
        attackBase: Double = 100.0,
    ) = ArenaTurnRules(
        safetyTurnLimit = safetyTurnLimit,
        hitChance = 1.0,
        damageVariance = 0.0,
        tierScaling = false,
        masteryScaling = false,
        formula = ArenaStatFormula(
            healthBase = 100_000.0,
            healthScale = 0.0,
            attackBase = attackBase,
            attackScale = 0.0,
        ),
    )
}
