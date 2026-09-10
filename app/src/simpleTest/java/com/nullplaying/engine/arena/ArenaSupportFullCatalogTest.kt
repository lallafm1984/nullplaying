package com.nullplaying.engine.arena

import com.nullplaying.model.HeroClass
import org.junit.Assert.*
import org.junit.Test

/** Real engine fixtures: schedule chooses legal actions only, never mutates HP/MP/statuses. */
class ArenaSupportFullCatalogTest {
    @Test fun `all sixty definitions use current ids localized numeric contracts and exact unlock boundaries`() {
        assertEquals(60, ArenaSupportCatalog.values.size)
        assertEquals(60, ArenaSupportTurnEngine.supportedSupportIds.size)
        for (cls in HeroClass.entries) {
            val supports = ArenaSupportCatalog.forClass(cls)
            assertEquals(listOf(5L,10L,20L,30L,40L,50L,60L,70L,80L,90L), supports.map { it.unlockLevel })
            supports.forEach { s ->
                assertFalse(s.id in ArenaSupportCatalog.unlockedIds(cls, s.unlockLevel - 1))
                assertTrue(s.id in ArenaSupportCatalog.unlockedIds(cls, s.unlockLevel))
                for (language in listOf("ko", "en", "ja")) {
                    assertTrue(s.name(language).isNotBlank())
                    assertTrue(s.effectText(language).isNotBlank())
                    assertFalse(s.effectText(language).contains("{v}"))
                    assertTrue(s.conditionText(language).isNotBlank())
                    assertTrue(s.limitationText(language).isNotBlank())
                }
            }
        }
        val retired = listOf("FIGHTER_07", "ROGUE_06", "ROGUE_08", "RANGER_09", "MAGE_06", "MAGE_09", "MAGE_10")
        retired.forEach { assertNull(ArenaSupportCatalog.find("ARENA_SUP_$it")) }
    }

    @Test fun `all support mechanics complete under legal current state schedules with deterministic mirror ledgers`() {
        val missing = mutableListOf<String>()
        val observedTypes = mutableSetOf<ArenaSupportEventType>()
        for (support in ArenaSupportCatalog.values) {
            var proof: ArenaSupportResult? = null
            search@ for (enemyClass in HeroClass.entries) for (hitChance in listOf(1.0, .55)) for (attackBase in listOf(35.0, 90.0)) {
                val left = ArenaSupportQaFixtures.fullFighter(support.heroClass, 90, "actor")
                val rightBase = ArenaSupportQaFixtures.fullFighter(enemyClass, 90, "target")
                // Wrist Check is legal only when the opponent has committed to, or can only use,
                // a basic attack. Keep the real legality rule active and create that current state.
                val right = if (support.id == "ARENA_SUP_ROGUE_10") {
                    rightBase.copy(
                        fighter = rightBase.fighter.copy(attacks = emptyList()),
                        supportIds = emptySet(),
                    )
                } else {
                    rightBase
                }
                val schedule = schedule(support.id, left, right)
                val rules = ArenaTurnRules(safetyTurnLimit = 150, hitChance = hitChance, damageVariance = 0.0,
                    formula = ArenaStatFormula(healthBase = 600.0, healthScale = 0.0, attackBase = attackBase, attackScale = 0.0))
                for (seed in 1L..4L) {
                    val result = ArenaSupportTurnEngine.simulateScripted(left, right, seed, schedule, rules)
                    observedTypes += result.events.map { it.type }
                    if (result.events.any { it.type == ArenaSupportEventType.SUPPORT_APPLIED && it.actorId == "actor" && it.actionId == support.id } && effectProof(result, support)) {
                        assertEquals(result, ArenaSupportTurnEngine.simulateScripted(left, right, seed, schedule, rules))
                        assertEquals(result, ArenaSupportTurnEngine.simulateScripted(right, left, seed, schedule, rules))
                        assertLedgers(result)
                        proof = result
                        break@search
                    }
                }
            }
            if (proof == null) missing += support.id
        }
        assertTrue("Never legally completed: $missing", missing.isEmpty())
        assertTrue(observedTypes.containsAll(listOf(ArenaSupportEventType.DOT_DAMAGE,
            ArenaSupportEventType.HEAL_APPLIED, ArenaSupportEventType.MP_DRAINED,
            ArenaSupportEventType.STATUS_BLOCKED, ArenaSupportEventType.CONTROL_APPLIED,
            ArenaSupportEventType.STATUS_REMOVED, ArenaSupportEventType.CAST_DELAYED)))
    }

    @Test fun `all eighteen cores have concrete preview replacements and legal engine validation`() {
        for (core in ArenaProgressionCatalog.values.filter { it.isCore }) {
            val rank = ArenaSupportTraitRank(core.id)
            val actor = ArenaSupportQaFixtures.fullFighter(core.heroClass, 90, "actor", listOf(rank))
            ArenaSupportTurnEngine.validate(actor)
            assertTrue(core.coreParameters.isNotEmpty())
            core.requiredSupportIds.forEach { id ->
                val preview = requireNotNull(ArenaSupportCatalog.effectiveDefinition(id, listOf(rank)))
                assertTrue(preview.castTurns >= 1)
                assertTrue(preview.mp > 0)
                for (language in listOf("ko", "en", "ja")) assertTrue(preview.effectText(language).isNotBlank())
            }
            val target = ArenaSupportQaFixtures.fullFighter(HeroClass.MAGE, 90, "target")
            for(seed in 1L..3L) assertLedgers(ArenaSupportTurnEngine.simulate(actor, target, seed))
        }
    }

    @Test fun `observed cast traits never inspect the opponents simultaneous new choice`() {
        for (actorId in listOf("a", "z")) {
            val targetId = if (actorId == "a") "z" else "a"
            val actor = ArenaSupportQaFixtures.fullFighter(HeroClass.RANGER, 90, actorId,
                listOf(ArenaSupportTraitRank("AT9_RANGER_A03", 5))).copy(supportIds = emptySet())
            val target = ArenaSupportQaFixtures.fullFighter(HeroClass.MAGE, 90, targetId).copy(supportIds = emptySet())
            val schedule = mapOf(
                (actorId to 1) to actor.fighter.attacks.first { it.tier == 1 }.id,
                (actorId to 2) to actor.fighter.attacks.first { it.tier == 4 }.id,
                (targetId to 1) to target.fighter.attacks.first { it.tier == 3 }.id,
            )
            val result = ArenaSupportTurnEngine.simulateScripted(actor, target, 3L, schedule,
                ArenaTurnRules(hitChance = .5, damageVariance = 0.0,
                    formula = ArenaStatFormula(healthBase = 1000.0, healthScale = 0.0, attackBase = 10.0, attackScale = 0.0)))
            assertFalse("Same-turn hidden cast leaked for $actorId", result.events.any {
                it.turn == 1 && it.traitId == "AT9_RANGER_A03" && it.reason == "accuracy_applied"
            })
            assertTrue("Previously observable cast missing for $actorId", result.events.any {
                it.turn == 2 && it.traitId == "AT9_RANGER_A03" && it.reason == "accuracy_applied"
            })
        }
    }

    @Test fun `qa entry bypass cannot grant advanced support and core ranks cannot bypass budget`() {
        val actor = ArenaSupportQaFixtures.fighter(HeroClass.WARRIOR, 10, "actor")
        assertThrows(IllegalArgumentException::class.java) {
            ArenaSupportTurnEngine.validate(actor.copy(supportIds = setOf("ARENA_SUP_FIGHTER_09")), true)
        }
        val full = ArenaSupportQaFixtures.fullFighter(HeroClass.WARRIOR, 90, "actor")
        assertThrows(IllegalArgumentException::class.java) {
            ArenaSupportTurnEngine.validate(full.copy(traits = listOf(ArenaSupportTraitRank("AT9_WARRIOR_A_CORE", 1, 1))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArenaSupportTurnEngine.validate(full.copy(traits = listOf(ArenaSupportTraitRank("AT9_WARRIOR_A_CORE"), ArenaSupportTraitRank("AT9_WARRIOR_B_CORE"))))
        }
    }

    private fun effectProof(result: ArenaSupportResult, support: ArenaSupportDefinition): Boolean {
        val direct = result.events.any { e -> e.actionId == support.id && when(e.type) {
            ArenaSupportEventType.SUPPORT_TRIGGERED -> e.traitValue?.let { it > 0 } == true
            ArenaSupportEventType.HEAL_APPLIED, ArenaSupportEventType.SHIELD_ABSORBED,
            ArenaSupportEventType.DAMAGE_REDUCED, ArenaSupportEventType.MP_DRAINED -> e.amount > 0
            ArenaSupportEventType.STATUS_APPLIED, ArenaSupportEventType.STATUS_BLOCKED,
            ArenaSupportEventType.CONTROL_APPLIED -> true
            ArenaSupportEventType.STATUS_REMOVED -> e.actorId == "actor"
            else -> false
        } }
        if (direct) return true
        if (support.kind in setOf(ArenaSupportKind.EVASION, ArenaSupportKind.MIRROR)) return result.events.any {
            it.type == ArenaSupportEventType.ATTACK_EVADED && it.actorId == "actor" &&
                it.reason == if(support.kind == ArenaSupportKind.EVASION) "body_movement" else "mirror"
        }
        return false
    }

    private fun schedule(desired: String, left: ArenaSupportInput, right: ArenaSupportInput): Map<Pair<String, Int>, String> {
        val map = linkedMapOf<Pair<String, Int>, String>()
        val enemyCycle = when(right.fighter.heroClass) {
            HeroClass.WARRIOR -> listOf("ARENA_SUP_FIGHTER_02", "ARENA_SUP_FIGHTER_01")
            HeroClass.ROGUE -> listOf("ARENA_SUP_ROGUE_02", "ARENA_SUP_ROGUE_11", "ARENA_SUP_ROGUE_10", "ARENA_SUP_ROGUE_01")
            HeroClass.RANGER -> listOf("ARENA_SUP_RANGER_03", "ARENA_SUP_RANGER_06")
            HeroClass.MAGE -> listOf("ARENA_SUP_MAGE_01", "ARENA_SUP_MAGE_11", "ARENA_SUP_MAGE_02")
            HeroClass.CLERIC -> listOf("ARENA_SUP_CLERIC_02", "ARENA_SUP_CLERIC_01", "ARENA_SUP_CLERIC_04")
            HeroClass.PALADIN -> listOf("ARENA_SUP_PALADIN_01", "ARENA_SUP_PALADIN_06")
        }
        val attack = right.fighter.attacks.firstOrNull { it.tier == 3 }?.id ?: "BASIC_ATTACK"
        val skillFollowup = requireNotNull(ArenaSupportCatalog.find(desired)).kind in setOf(
            ArenaSupportKind.SKILL_SHATTER, ArenaSupportKind.STABILIZE, ArenaSupportKind.CONDENSE,
            ArenaSupportKind.FOCUS, ArenaSupportKind.RETRIBUTION, ArenaSupportKind.EXECUTE, ArenaSupportKind.BURN_PREP)
        val followup = if (skillFollowup) left.fighter.attacks.first { it.tier == 1 }.id else "BASIC_ATTACK"
        for(turn in 1..150) {
            map["actor" to turn] = if (turn % 2 == 1) desired else followup
            map["target" to turn] = if (turn % 4 == 1) enemyCycle[((turn - 1) / 4) % enemyCycle.size] else attack
        }
        if (desired == "ARENA_SUP_ROGUE_12") map["actor" to 1] = "ARENA_SUP_ROGUE_11"
        if (desired == "ARENA_SUP_RANGER_08") {
            map["actor" to 1] = "BASIC_ATTACK"
            map["actor" to 2] = "BASIC_ATTACK"
        }
        return map
    }

    private fun assertLedgers(result: ArenaSupportResult) {
        val hp = mutableMapOf<String, Double>()
        val mp = mutableMapOf<String, Int>()
        val shields = mutableMapOf<String, Double>()
        val dead = mutableSetOf<String>()
        for (e in result.events) {
            assertTrue("future cause $e", e.causeSequence == null || e.causeSequence < e.sequence)
            assertTrue(e.amount.isFinite() && e.amount >= 0)
            if(e.type == ArenaSupportEventType.START) {
                hp[e.actorId!!] = e.hpAfter!!; mp[e.actorId] = e.mpAfterUnits!!; shields[e.actorId] = e.shieldAfter!!
            }
            val hpOwner = if(e.type in setOf(ArenaSupportEventType.ATTACK_HIT, ArenaSupportEventType.ATTACK_MISS, ArenaSupportEventType.DOT_DAMAGE)) e.targetId else e.actorId
            if(e.hpBefore != null && e.hpAfter != null && hpOwner != null) {
                assertEquals("HP $e", hp[hpOwner]!!, e.hpBefore, .000001)
                hp[hpOwner] = e.hpAfter
            }
            if(e.mpBeforeUnits != null && e.mpAfterUnits != null && e.actorId != null) {
                assertEquals("MP $e", mp[e.actorId], e.mpBeforeUnits)
                assertTrue(e.mpAfterUnits <= e.mpBeforeUnits)
                mp[e.actorId] = e.mpAfterUnits
            }
            if(e.shieldBefore != null && e.shieldAfter != null && e.actorId != null) {
                assertEquals("shield $e", shields[e.actorId]!!, e.shieldBefore, .000001)
                shields[e.actorId] = e.shieldAfter
            }
            if(e.type == ArenaSupportEventType.KO) dead += e.actorId!!
            if(e.type in setOf(ArenaSupportEventType.CAST_START, ArenaSupportEventType.HEAL_APPLIED, ArenaSupportEventType.SUPPORT_APPLIED))
                assertFalse("Post KO action: $e", e.actorId in dead)
        }
        result.fighters.forEach { (id, f) ->
            assertEquals(hp[id]!!, f.hp, .000001)
            assertEquals(mp[id], f.mpUnits)
            assertEquals(shields[id]!!, f.shield, .000001)
        }
    }
}
