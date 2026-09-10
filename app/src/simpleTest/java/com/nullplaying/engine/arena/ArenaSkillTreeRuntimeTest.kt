package com.nullplaying.engine.arena

import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.engine.SkillCatalog
import com.nullplaying.model.HeroClass
import com.nullplaying.model.SimpleGameState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Focused executable proofs for the Arena skill-tree V6 snapshot contract. */
class ArenaSkillTreeRuntimeTest {
    @Test
    fun `bleed damage is named bleed in the player battle log`() {
        val line = requireNotNull(ArenaSupportEventText.text(
            event = ArenaSupportEvent(
                sequence = 1,
                turn = 2,
                type = ArenaSupportEventType.DOT_DAMAGE,
                actorId = "actor",
                targetId = "target",
                amount = 18.0,
                reason = "bleed",
            ),
            names = mapOf("actor" to "파이터", "target" to "상대"),
            language = "ko",
        ))

        assertTrue(line.contains("출혈"))
        assertFalse(line.contains("화상"))
        assertFalse(line.contains("18"))
        assertEquals("상대가 출혈로 피해를 입었다.", line)
    }

    @Test
    fun `damage prose hides amounts in every locale without stripping nickname digits`() {
        val names = mapOf("actor" to "Hero7", "target" to "Rival8")
        val attacks = listOf("BASIC_ATTACK") + HeroClass.entries.flatMap { heroClass ->
            SkillCatalog.forClass(heroClass).map { it.catalogId }
        }
        val types = listOf(ArenaSupportEventType.ATTACK_HIT, ArenaSupportEventType.DOT_DAMAGE,
            ArenaSupportEventType.SHIELD_ABSORBED, ArenaSupportEventType.DAMAGE_REDUCED)
        for (language in listOf("ko", "en", "ja")) {
            for (type in types) {
                for (attack in attacks) {
                    for (reason in if (type == ArenaSupportEventType.DOT_DAMAGE) listOf("bleed", "poison", "burn") else listOf(null)) {
                        val event = ArenaSupportEvent(1, 1, type, "actor", "target",
                            actionId = attack, amount = 9876.5, reason = reason)
                        val line = requireNotNull(ArenaSupportEventText.text(event, names, language))
                        assertTrue(line.contains("Hero7") || line.contains("Rival8"))
                        assertFalse(line.contains("9876"))
                        // Presentation changes must not mutate the damage ledger.
                        assertEquals(9876.5, event.amount, 0.0)
                    }
                }
            }
        }
    }

    @Test
    fun `v6 adapter discards adventure mastery and source damage`() {
        val state = stateAtLevel(HeroClass.WARRIOR, 95L)
        val attackId = SkillCatalog.forClass(HeroClass.WARRIOR).first().catalogId
        val rank = 7

        state.skills = state.skills.map { learned ->
            if (learned.catalogId == attackId) learned.copy(usageCount = 0L) else learned
        }.toMutableList()
        val noviceSource = ArenaTurnInputAdapter.fromState(state, "fighter", mapOf(attackId to rank))

        state.skills = state.skills.map { learned ->
            if (learned.catalogId == attackId) learned.copy(usageCount = Long.MAX_VALUE) else learned
        }.toMutableList()
        val masteredSource = ArenaTurnInputAdapter.fromState(state, "fighter", mapOf(attackId to rank))

        val novice = noviceSource.fighter.attacks.single()
        val mastered = masteredSource.fighter.attacks.single()
        assertEquals(novice, mastered)
        assertEquals(0, mastered.masteryBonusPercent)
        assertEquals(0, mastered.sourceDamagePercentMin)
        assertEquals(0, mastered.sourceDamagePercentMax)
        assertEquals(rank, mastered.arena?.rank)
        assertEquals(ArenaSkillTreeCatalog.attackProfile(1).damagePercent(HeroClass.WARRIOR, rank),
            mastered.arena?.damagePercent)
        assertTrue(noviceSource.rejectedSkills.isEmpty())
        assertTrue(masteredSource.rejectedSkills.isEmpty())
    }

    @Test
    fun `v6 adapter freezes every attack timing cost cooldown and rank damage from catalog`() {
        val state = stateAtLevel(HeroClass.RANGER, 95L)
        val definitions = SkillCatalog.forClass(HeroClass.RANGER)
        val ranks = definitions.mapIndexed { index, definition ->
            definition.catalogId to (index % 10 + 1)
        }.toMap()

        val built = ArenaTurnInputAdapter.fromState(state, "fighter", ranks)

        assertTrue(built.rejectedSkills.isEmpty())
        assertEquals(20, built.fighter.attacks.size)
        built.fighter.attacks.forEachIndexed { index, attack ->
            val profile = ArenaSkillTreeCatalog.attackProfile(index + 1)
            val rank = ranks.getValue(attack.id)
            val arena = requireNotNull(attack.arena)
            assertEquals(profile.prepareTurns, arena.prepareTurns)
            assertEquals(profile.effectiveMpCost(rank), arena.mpCost)
            assertEquals(profile.effectiveCooldownTurns(rank), arena.cooldownTurns)
            assertEquals(profile.damagePercent(HeroClass.RANGER, rank), arena.damagePercent)
            assertEquals(profile.effectiveMaxHpDamageCapPercent(rank).toDouble(),
                arena.hpDamageCapPercent, 0.0)
            assertEquals(profile.effectFor(HeroClass.RANGER).kind.name, arena.effectKey)
        }
    }

    @Test
    fun `existing resolved rank ten fields are replayed without retroactive milestone derivation`() {
        val attackId = attackId(HeroClass.WARRIOR, 1)
        val target = basicOnly(HeroClass.MAGE, "target")
        val schedule = buildMap {
            for (turn in 1..5) {
                put("actor" to turn, attackId)
                put("target" to turn, "BASIC_ATTACK")
            }
        }
        val current = ArenaSupportTurnEngine.simulateScripted(
            fighter(HeroClass.WARRIOR, "actor", slot = 1, rank = 10),
            target,
            19L,
            schedule,
            rules(safetyTurnLimit = 5, healthBase = 5_000.0, attackBase = 10.0),
        )
        val savedZeroCooldown = ArenaSupportTurnEngine.simulateScripted(
            fighter(HeroClass.WARRIOR, "actor", slot = 1, rank = 10,
                mutate = { it.copy(cooldownTurns = 0) }),
            target, 19L, schedule,
            rules(safetyTurnLimit = 5, healthBase = 5_000.0, attackBase = 10.0),
        )
        val retained = ArenaSupportTurnEngine.simulateScripted(
            fighter(HeroClass.WARRIOR, "actor", slot = 1, rank = 10,
                mutate = { it.copy(mpCost = 9, cooldownTurns = 2) }),
            target,
            19L,
            schedule,
            rules(safetyTurnLimit = 5, healthBase = 5_000.0, attackBase = 10.0),
        )

        fun starts(result: ArenaSupportResult) = result.events.filter {
            it.type == ArenaSupportEventType.CAST_START &&
                it.actorId == "actor" && it.actionId == attackId
        }
        assertEquals((1..5).toList(), starts(savedZeroCooldown).map { it.turn })
        assertEquals(listOf(1, 3, 5), starts(current).map { it.turn })
        assertTrue(starts(current).all { it.castTurns == 1 && it.amount == 18.0 })
        assertEquals(listOf(1, 4), starts(retained).map { it.turn })
        assertTrue(starts(retained).all { it.castTurns == 1 && it.amount == 9.0 })
    }

    @Test
    fun `runtime honors authored preparation mp and cooldown instead of the legacy common cooldown`() {
        val actor = fighter(HeroClass.WARRIOR, "actor", slot = 3, rank = 1)
        val target = basicOnly(HeroClass.MAGE, "target")
        val schedule = buildMap {
            for (turn in 1..7) {
                put("actor" to turn, attackId(HeroClass.WARRIOR, 3))
                put("target" to turn, "BASIC_ATTACK")
            }
        }

        val result = ArenaSupportTurnEngine.simulateScripted(
            actor,
            target,
            seed = 17L,
            schedule = schedule,
            rules = rules(safetyTurnLimit = 7, healthBase = 5_000.0, attackBase = 10.0),
        )

        val starts = result.events.filter {
            it.type == ArenaSupportEventType.CAST_START &&
                it.actorId == "actor" && it.actionId == attackId(HeroClass.WARRIOR, 3)
        }
        assertEquals(listOf(1, 5), starts.map { it.turn })
        assertTrue(starts.all { it.castTurns == 2 })
        assertTrue(starts.all { it.amount == 13.0 })
        assertTrue(starts.all { it.mpBeforeUnits!! - it.mpAfterUnits!! == 13_000 })
    }

    @Test
    fun `ten ranks produce a clearly larger direct hit than one rank`() {
        val target = basicOnly(HeroClass.MAGE, "target")
        val low = firstHit(
            ArenaSupportTurnEngine.simulateScripted(
                fighter(HeroClass.WARRIOR, "actor", slot = 1, rank = 1),
                target,
                23L,
                mapOf(("actor" to 1) to attackId(HeroClass.WARRIOR, 1)),
                rules(safetyTurnLimit = 1, healthBase = 1_000.0, attackBase = 100.0),
            ),
            "actor",
            attackId(HeroClass.WARRIOR, 1),
        )
        val high = firstHit(
            ArenaSupportTurnEngine.simulateScripted(
                fighter(HeroClass.WARRIOR, "actor", slot = 1, rank = 10),
                target,
                23L,
                mapOf(("actor" to 1) to attackId(HeroClass.WARRIOR, 1)),
                rules(safetyTurnLimit = 1, healthBase = 1_000.0, attackBase = 100.0),
            ),
            "actor",
            attackId(HeroClass.WARRIOR, 1),
        )

        val profile = ArenaSkillTreeCatalog.attackProfile(1)
        assertEquals(106, profile.damagePercent(HeroClass.WARRIOR, 1))
        assertEquals(212, profile.damagePercent(HeroClass.WARRIOR, 10))
        assertEquals(profile.damagePercent(HeroClass.WARRIOR, 1).toDouble(), low.amount, 1e-9)
        assertEquals(profile.damagePercent(HeroClass.WARRIOR, 10).toDouble(), high.amount, 1e-9)
        assertTrue("rank 10 must feel materially stronger", high.amount >= low.amount * 1.9)
    }

    @Test
    fun `storm strike drains target mp on any hit including a shield-only hit`() {
        val attackId = attackId(HeroClass.WARRIOR, 6)
        val shieldId = "ARENA_SUP_MAGE_01"
        val shield = ArenaSkillTreeCatalog.effectiveSupport(
            requireNotNull(ArenaSupportCatalog.find(shieldId)),
            1,
        ).copy(
            mp = 0,
            magnitude = 100.0,
            durationTurns = 10,
        ).resolved(1)
        val targetFixture = ArenaSupportQaFixtures.fullFighter(HeroClass.MAGE, 95, "target")
        val target = ArenaSupportInput(
            fighter = targetFixture.fighter.copy(attacks = emptyList()),
            supportIds = setOf(shieldId),
            supportRanks = mapOf(shieldId to 1),
            resolvedSupports = mapOf(shieldId to shield),
            arenaLevel = 100,
        )

        val result = ArenaSupportTurnEngine.simulateScripted(
            fighter(HeroClass.WARRIOR, "actor", slot = 6, rank = 10),
            target,
            seed = 29L,
            schedule = mapOf(
                ("actor" to 1) to attackId,
                ("target" to 1) to shieldId,
                ("target" to 2) to "BASIC_ATTACK",
            ),
            rules = rules(safetyTurnLimit = 2, healthBase = 5_000.0, attackBase = 10.0),
        )

        assertTrue(result.events.any {
            it.type == ArenaSupportEventType.SHIELD_ABSORBED &&
                it.actorId == "target" && it.amount > 0.0
        })
        val drained = result.events.single {
            it.type == ArenaSupportEventType.MP_DRAINED &&
                it.actionId == attackId && it.reason == "arena_mana_pressure"
        }
        assertEquals("target", drained.actorId)
        assertEquals("actor", drained.targetId)
        assertEquals(12.0, drained.amount, 1e-9)
        val targetAfter = result.fighters.getValue("target")
        assertEquals(targetAfter.maxMpUnits - 12_000, targetAfter.mpUnits)
    }

    @Test
    fun `storm strike never drains on a miss and cannot drain below zero`() {
        val attackId = attackId(HeroClass.WARRIOR, 6)
        val actor = fighter(HeroClass.WARRIOR, "actor", slot = 6, rank = 10)
        val missed = ArenaSupportTurnEngine.simulateScripted(
            actor,
            basicOnly(HeroClass.MAGE, "miss-target"),
            seed = 31L,
            schedule = mapOf(("actor" to 1) to attackId),
            rules = rules(
                safetyTurnLimit = 2,
                healthBase = 5_000.0,
                attackBase = 10.0,
                hitChance = 0.0,
            ),
        )
        assertTrue(missed.events.any {
            it.type == ArenaSupportEventType.ATTACK_MISS && it.actionId == attackId
        })
        assertFalse(missed.events.any {
            it.type == ArenaSupportEventType.MP_DRAINED && it.reason == "arena_mana_pressure"
        })

        val targetAttackId = attackId(HeroClass.MAGE, 1)
        val lowManaTarget = fighter(HeroClass.MAGE, "low-mp-target", slot = 1, rank = 1) { resolved ->
            resolved.copy(
                prepareTurns = 0,
                mpCost = 95,
                cooldownTurns = 0,
                damagePercent = 1,
                effectKey = "NONE",
                effectValues = emptyMap(),
            )
        }.let { input ->
            input.copy(fighter = input.fighter.copy(
                stats = input.fighter.stats.copy(rawMaxMana = 0.0),
            ))
        }
        val clamped = ArenaSupportTurnEngine.simulateScripted(
            actor,
            lowManaTarget,
            seed = 37L,
            schedule = mapOf(
                ("actor" to 1) to attackId,
                ("low-mp-target" to 1) to targetAttackId,
            ),
            rules = rules(safetyTurnLimit = 2, healthBase = 5_000.0, attackBase = 10.0),
        )
        val drain = clamped.events.single {
            it.type == ArenaSupportEventType.MP_DRAINED && it.reason == "arena_mana_pressure"
        }
        assertEquals(5.0, drain.amount, 1e-9)
        assertEquals(0, clamped.fighters.getValue("low-mp-target").mpUnits)
    }

    @Test
    fun `rank ten preparation punish inflicts executable bleed only against an attack cast`() {
        val attackId = attackId(HeroClass.WARRIOR, 2)
        val longAttackId = attackId(HeroClass.MAGE, 16)
        val positive = ArenaSupportTurnEngine.simulateScripted(
            fighter(HeroClass.WARRIOR, "actor", slot = 2, rank = 10),
            fighter(HeroClass.MAGE, "target", slot = 16, rank = 1),
            seed = 71L,
            schedule = mapOf(
                ("actor" to 1) to attackId,
                ("target" to 1) to longAttackId,
            ),
            rules = rules(safetyTurnLimit = 3, healthBase = 5_000.0, attackBase = 100.0),
        )
        val supportId = "ARENA_SUP_MAGE_02"
        val negative = ArenaSupportTurnEngine.simulateScripted(
            fighter(HeroClass.WARRIOR, "actor", slot = 2, rank = 10),
            supportOnly(HeroClass.MAGE, "target", supportId, rank = 10),
            seed = 71L,
            schedule = mapOf(
                ("actor" to 1) to attackId,
                ("target" to 1) to supportId,
            ),
            rules = rules(safetyTurnLimit = 3, healthBase = 5_000.0, attackBase = 100.0),
        )
        val frozenBeforeMastery = ArenaSupportTurnEngine.simulateScripted(
            fighter(HeroClass.WARRIOR, "actor", slot = 2, rank = 10, mutate = { resolved ->
                resolved.copy(effectValues = resolved.effectValues + mapOf(
                    "masterBleedTick" to 0.0,
                    "masterBleedTurns" to 0.0,
                ))
            }),
            fighter(HeroClass.MAGE, "target", slot = 16, rank = 1),
            seed = 71L,
            schedule = mapOf(
                ("actor" to 1) to attackId,
                ("target" to 1) to longAttackId,
            ),
            rules = rules(safetyTurnLimit = 3, healthBase = 5_000.0, attackBase = 100.0),
        )

        assertTrue(positive.events.any {
            it.type == ArenaSupportEventType.STATUS_APPLIED && it.actorId == "actor" &&
                it.actionId == attackId && it.reason == "arena_mastery_bleed"
        })
        val ticks = positive.events.filter {
            it.type == ArenaSupportEventType.DOT_DAMAGE && it.actorId == "actor" &&
                it.actionId == attackId && it.reason == "bleed"
        }
        assertEquals(listOf(2, 3), ticks.map { it.turn })
        assertTrue(ticks.all { it.amount == 18.0 })
        assertTrue(negative.events.none {
            it.actionId == attackId &&
                (it.reason == "arena_mastery_bleed" || it.type == ArenaSupportEventType.DOT_DAMAGE)
        })
        assertTrue(frozenBeforeMastery.events.none {
            it.actionId == attackId &&
                (it.reason == "arena_mastery_bleed" || it.type == ArenaSupportEventType.DOT_DAMAGE)
        })
    }

    @Test
    fun `rank ten suppression weakens exactly two later direct hits`() {
        val suppressionId = attackId(HeroClass.WARRIOR, 5)
        val schedule = buildMap {
            for (turn in 1..4) {
                put("actor" to turn, if (turn == 1) suppressionId else "BASIC_ATTACK")
                put("target" to turn, "BASIC_ATTACK")
            }
        }
        fun targetHits(rank: Int) = ArenaSupportTurnEngine.simulateScripted(
            fighter(HeroClass.WARRIOR, "actor", slot = 5, rank = rank),
            basicOnly(HeroClass.MAGE, "target"),
            seed = 73L,
            schedule = schedule,
            rules = rules(safetyTurnLimit = 4, healthBase = 5_000.0, attackBase = 100.0),
        ).events.filter {
            it.type == ArenaSupportEventType.ATTACK_HIT &&
                it.actorId == "target" && it.actionId == "BASIC_ATTACK"
        }.associate { it.turn to it.amount }

        val rankNine = targetHits(9)
        val rankTen = targetHits(10)
        assertEquals(75.0, rankTen.getValue(2), 1e-9)
        assertEquals(75.0, rankTen.getValue(3), 1e-9)
        assertEquals(100.0, rankTen.getValue(4), 1e-9)
        assertTrue(rankNine.getValue(2) < 100.0)
        assertEquals(100.0, rankNine.getValue(3), 1e-9)
    }

    @Test
    fun `rank ten follow up mark empowers exactly two later direct hits`() {
        val markId = attackId(HeroClass.WARRIOR, 9)
        val schedule = buildMap {
            for (turn in 1..5) {
                put("actor" to turn, if (turn == 1) markId else "BASIC_ATTACK")
                put("target" to turn, "BASIC_ATTACK")
            }
        }
        fun actorHits(rank: Int) = ArenaSupportTurnEngine.simulateScripted(
            fighter(HeroClass.WARRIOR, "actor", slot = 9, rank = rank),
            basicOnly(HeroClass.MAGE, "target"),
            seed = 79L,
            schedule = schedule,
            rules = rules(safetyTurnLimit = 5, healthBase = 5_000.0, attackBase = 100.0),
        ).events.filter {
            it.type == ArenaSupportEventType.ATTACK_HIT &&
                it.actorId == "actor" && it.actionId == "BASIC_ATTACK"
        }.associate { it.turn to it.amount }

        val rankNine = actorHits(9)
        val rankTen = actorHits(10)
        assertEquals(125.0, rankTen.getValue(3), 1e-9)
        assertEquals(125.0, rankTen.getValue(4), 1e-9)
        assertEquals(100.0, rankTen.getValue(5), 1e-9)
        assertTrue(rankNine.getValue(3) > 100.0)
        assertEquals(100.0, rankNine.getValue(4), 1e-9)
    }

    @Test
    fun `a07 accuracy bonus applies only while the target is preparing an attack`() {
        val interceptId = attackId(HeroClass.WARRIOR, 7)
        val longAttackId = attackId(HeroClass.MAGE, 16)
        val supportId = "ARENA_SUP_FIGHTER_01"
        val halfAccuracyRules = rules(
            safetyTurnLimit = 1,
            healthBase = 5_000.0,
            attackBase = 10.0,
        ).copy(hitChance = 0.5)
        fun run(rank: Int, seed: Long, target: ArenaSupportInput, targetAction: String) =
            ArenaSupportTurnEngine.simulateScripted(
                fighter(HeroClass.WARRIOR, "actor", slot = 7, rank = rank),
                target,
                seed = seed,
                schedule = mapOf(
                    ("actor" to 1) to interceptId,
                    ("target" to 1) to targetAction,
                ),
                rules = halfAccuracyRules,
            )
        fun resultType(result: ArenaSupportResult): ArenaSupportEventType = result.events.single {
            it.actorId == "actor" && it.actionId == interceptId &&
                it.type in setOf(ArenaSupportEventType.ATTACK_HIT, ArenaSupportEventType.ATTACK_MISS)
        }.type

        val rankOnePrepared = run(
            rank = 1,
            seed = 7L,
            target = fighter(HeroClass.MAGE, "target", slot = 16, rank = 1),
            targetAction = longAttackId,
        )
        val rankOneSupport = run(
            rank = 1,
            seed = 7L,
            target = supportOnly(HeroClass.WARRIOR, "target", supportId, rank = 1),
            targetAction = supportId,
        )
        val rankOneAtHighRoll = run(
            rank = 1,
            seed = 1L,
            target = fighter(HeroClass.MAGE, "target", slot = 16, rank = 1),
            targetAction = longAttackId,
        )
        val rankTenAtHighRoll = run(
            rank = 10,
            seed = 1L,
            target = fighter(HeroClass.MAGE, "target", slot = 16, rank = 1),
            targetAction = longAttackId,
        )

        assertEquals(ArenaSupportEventType.ATTACK_HIT, resultType(rankOnePrepared))
        assertEquals(ArenaSupportEventType.ATTACK_MISS, resultType(rankOneSupport))
        assertEquals(ArenaSupportEventType.ATTACK_MISS, resultType(rankOneAtHighRoll))
        assertEquals(ArenaSupportEventType.ATTACK_HIT, resultType(rankTenAtHighRoll))
    }

    @Test
    fun `shield shatter removes extra shield without converting it to hp damage`() {
        val attackId = attackId(HeroClass.WARRIOR, 3)
        val target = supportOnly(HeroClass.MAGE, "target", "ARENA_SUP_MAGE_01", rank = 5)
        val schedule = mapOf(
            ("actor" to 1) to attackId,
            ("actor" to 2) to "BASIC_ATTACK",
            ("target" to 1) to "ARENA_SUP_MAGE_01",
            ("target" to 2) to "BASIC_ATTACK",
        )
        val withEffect = ArenaSupportTurnEngine.simulateScripted(
            fighter(HeroClass.WARRIOR, "actor", slot = 3, rank = 1),
            target,
            31L,
            schedule,
            rules(safetyTurnLimit = 2, healthBase = 1_000.0, attackBase = 10.0),
        )
        val withoutEffect = ArenaSupportTurnEngine.simulateScripted(
            fighter(HeroClass.WARRIOR, "actor", slot = 3, rank = 1,
                mutate = { it.copy(effectKey = "NONE") }),
            target,
            31L,
            schedule,
            rules(safetyTurnLimit = 2, healthBase = 1_000.0, attackBase = 10.0),
        )

        assertTrue(withEffect.fighters.getValue("target").shield <
            withoutEffect.fighters.getValue("target").shield)
        assertEquals(withoutEffect.fighters.getValue("target").hp,
            withEffect.fighters.getValue("target").hp, 1e-9)
        assertTrue(withEffect.events.any {
            it.type == ArenaSupportEventType.SHIELD_ABSORBED &&
                it.actionId == attackId && it.reason == "arena_shield_shatter" && it.amount > 0
        })
    }

    @Test
    fun `healing reduction lowers a later heal for its authored window`() {
        val attackId = attackId(HeroClass.WARRIOR, 11)
        val target = supportOnly(HeroClass.CLERIC, "target", "ARENA_SUP_CLERIC_01", rank = 5)
        val schedule = buildMap {
            for (turn in 1..3) put("actor" to turn, if (turn == 1) attackId else "BASIC_ATTACK")
            put("target" to 1, "BASIC_ATTACK")
            put("target" to 2, "ARENA_SUP_CLERIC_01")
            put("target" to 3, "BASIC_ATTACK")
        }
        val withEffect = ArenaSupportTurnEngine.simulateScripted(
            fighter(HeroClass.WARRIOR, "actor", slot = 11, rank = 10),
            target,
            41L,
            schedule,
            rules(safetyTurnLimit = 3, healthBase = 1_000.0, attackBase = 100.0),
        )
        val withoutEffect = ArenaSupportTurnEngine.simulateScripted(
            fighter(HeroClass.WARRIOR, "actor", slot = 11, rank = 10,
                mutate = { it.copy(effectKey = "NONE") }),
            target,
            41L,
            schedule,
            rules(safetyTurnLimit = 3, healthBase = 1_000.0, attackBase = 100.0),
        )

        val reducedHeal = withEffect.events.single {
            it.type == ArenaSupportEventType.HEAL_APPLIED && it.actorId == "target"
        }
        val ordinaryHeal = withoutEffect.events.single {
            it.type == ArenaSupportEventType.HEAL_APPLIED && it.actorId == "target"
        }
        assertTrue(reducedHeal.amount < ordinaryHeal.amount)
        assertEquals(110.0, reducedHeal.amount, 1e-9)
        assertEquals(200.0, ordinaryHeal.amount, 1e-9)
        assertTrue(withEffect.events.any {
            it.type == ArenaSupportEventType.STATUS_APPLIED &&
                it.actionId == attackId && it.reason == "arena_healing_reduction"
        })
    }

    @Test
    fun `ultimate waits until turn six applies its hp cap and cannot start twice`() {
        val attackId = attackId(HeroClass.WARRIOR, 20)
        val actor = fighter(HeroClass.WARRIOR, "actor", slot = 20, rank = 10)
        val target = basicOnly(HeroClass.MAGE, "target")
        val schedule = buildMap {
            for (turn in 1..12) {
                put("actor" to turn, attackId)
                put("target" to turn, "BASIC_ATTACK")
            }
        }

        val result = ArenaSupportTurnEngine.simulateScripted(
            actor,
            target,
            53L,
            schedule,
            rules(safetyTurnLimit = 12, healthBase = 1_000.0, attackBase = 40.0),
        )

        val starts = result.events.filter {
            it.type == ArenaSupportEventType.CAST_START && it.actorId == "actor" && it.actionId == attackId
        }
        assertEquals(listOf(6), starts.map { it.turn })
        assertEquals(4, starts.single().castTurns)
        assertEquals(56.0, starts.single().amount, 1e-9)
        val hit = firstHit(result, "actor", attackId)
        val targetMaxHp = result.fighters.getValue("target").maxHp
        assertTrue(hit.amount <= targetMaxHp * .55 + 1e-9)
        assertTrue(requireNotNull(hit.hpAfter) >= 1.0)
        assertTrue(result.events.any {
            it.type == ArenaSupportEventType.DAMAGE_REDUCED &&
                it.actionId == attackId && it.reason == "arena_hp_damage_cap" && it.amount > 0
        })
    }

    private fun firstHit(result: ArenaSupportResult, actorId: String, actionId: String): ArenaSupportEvent =
        result.events.first {
            it.type == ArenaSupportEventType.ATTACK_HIT &&
                it.actorId == actorId && it.actionId == actionId
        }

    private fun fighter(
        heroClass: HeroClass,
        id: String,
        slot: Int,
        rank: Int,
        mutate: (ArenaResolvedAttack) -> ArenaResolvedAttack = { it },
    ): ArenaSupportInput {
        val fixture = ArenaSupportQaFixtures.fullFighter(heroClass, 95, id)
        val source = fixture.fighter.attacks.first { it.id == attackId(heroClass, slot) }
        val resolved = requireNotNull(
            ArenaTurnInputAdapter.resolveAttack(source, heroClass, rank).arena,
        )
        return ArenaSupportInput(
            fighter = fixture.fighter.copy(attacks = listOf(source.copy(
                masteryBonusPercent = 0,
                sourceDamagePercentMin = 0,
                sourceDamagePercentMax = 0,
                arena = mutate(resolved),
            ))),
            arenaLevel = 100,
        )
    }

    private fun basicOnly(heroClass: HeroClass, id: String): ArenaSupportInput {
        val fixture = ArenaSupportQaFixtures.fullFighter(heroClass, 95, id)
        return ArenaSupportInput(
            fighter = fixture.fighter.copy(attacks = emptyList()),
            arenaLevel = 100,
        )
    }

    private fun supportOnly(
        heroClass: HeroClass,
        id: String,
        supportId: String,
        rank: Int,
    ): ArenaSupportInput {
        val fixture = ArenaSupportQaFixtures.fullFighter(heroClass, 95, id)
        return ArenaSupportInput(
            fighter = fixture.fighter.copy(attacks = emptyList()),
            supportIds = setOf(supportId),
            arenaLevel = 100,
            supportRanks = mapOf(supportId to rank),
        )
    }

    private fun attackId(heroClass: HeroClass, slot: Int): String =
        SkillCatalog.forClass(heroClass)[slot - 1].catalogId

    private fun rules(
        safetyTurnLimit: Int,
        healthBase: Double,
        attackBase: Double,
        hitChance: Double = 1.0,
    ) = ArenaTurnRules(
        safetyTurnLimit = safetyTurnLimit,
        hitChance = hitChance,
        damageVariance = 0.0,
        tierScaling = false,
        masteryScaling = false,
        formula = ArenaStatFormula(
            healthBase = healthBase,
            healthScale = 0.0,
            attackBase = attackBase,
            attackScale = 0.0,
        ),
    )

    private fun stateAtLevel(heroClass: HeroClass, level: Long): SimpleGameState {
        val engine = SimpleGameEngine()
        val roll = engine.rollStats(73L, heroClass)
        val now = 1_000L
        val state = engine.newGame(
            name = "ArenaSkillTreeRuntime",
            heroClass = heroClass,
            rolledStats = roll.stats,
            seed = roll.nextSeed,
            now = now,
        )
        state.hero.level = level
        engine.settle(state, now + 1L)
        return state
    }
}
