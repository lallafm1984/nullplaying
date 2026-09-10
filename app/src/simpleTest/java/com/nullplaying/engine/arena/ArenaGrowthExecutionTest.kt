package com.nullplaying.engine.arena

import com.nullplaying.model.HeroClass
import org.junit.Assert.*
import org.junit.Test

/** Search legal deterministic action schedules for concrete modifier application, never injected statuses. */
class ArenaGrowthExecutionTest {
    @Test fun `every growth definition reaches an actual engine effect under a legal ownership fixture`() {
        val missing = mutableListOf<String>()
        val proofs = linkedMapOf<String, String>()
        for (definition in ArenaProgressionCatalog.values) {
            val rank = ArenaSupportTraitRank(definition.id, definition.maxRank, definition.maxEnhancement)
            val actor = ArenaSupportQaFixtures.fullFighter(definition.heroClass,
                if (definition.id == "AT9_RANGER_C02") 10 else 90, "actor", listOf(rank))
            var found = false
            search@ for (enemyClass in enemyOrder(definition)) for (mode in listOf(6, 0, 1, 2, 3, 4, 5)) {
                val target = ArenaSupportQaFixtures.fullFighter(enemyClass, 90, "target")
                val schedule = schedule(definition, actor, target, mode)
                for (hitChance in if (definition.effectUnit == ArenaProgressionEffectUnit.ACCURACY_PERCENTAGE_POINTS) listOf(.3, .7) else listOf(.7, 1.0))
                    for (attackBase in when (definition.id) {
                        "AT9_RANGER_C02" -> listOf(18.0, 26.0, 65.0)
                        "AT9_WARRIOR_C02" -> listOf(70.0, 18.0, 65.0)
                        else -> listOf(18.0, 65.0)
                    })
                    for (seed in 1L..if (definition.effectUnit == ArenaProgressionEffectUnit.ACCURACY_PERCENTAGE_POINTS) 24L else 3L) {
                    val rules = ArenaTurnRules(safetyTurnLimit = 180, hitChance = hitChance, damageVariance = 0.0,
                        tierScaling = false, masteryScaling = false,
                        formula = ArenaStatFormula(healthBase = 600.0, healthScale = 0.0,
                            attackBase = attackBase, attackScale = if (definition.id == "AT9_RANGER_C02") 5.0 else 0.0))
                    val result = ArenaSupportTurnEngine.simulateScripted(actor, target, seed, schedule, rules)
                    val applied = result.events.firstOrNull { event ->
                        event.traitId == definition.id && event.type == ArenaSupportEventType.TRAIT_TRIGGERED &&
                            event.reason != "prepared" && event.reason?.endsWith("applied") == true ||
                            (event.traitId == definition.id && event.reason == "discount_paid") ||
                            (event.type == ArenaSupportEventType.CAST_START && event.traitId == definition.id &&
                                event.traitValue?.let { it > 0 } == true)
                    } ?: continue
                    assertTrue("${definition.id}: no effective scalar", applied.traitValue?.let { it > 0 } == true)
                    val without = ArenaSupportTurnEngine.simulateScripted(actor.copy(traits = emptyList()), target, seed, schedule, rules)
                    // An accuracy opportunity can produce the same seeded outcome, and a cap can absorb
                    // a scalar gain. Continue until a concrete numeric effect is observed.
                    if (numericEvents(result) == numericEvents(without)) continue
                    assertEquals(result, ArenaSupportTurnEngine.simulateScripted(actor, target, seed, schedule, rules))
                    assertEquals(result, ArenaSupportTurnEngine.simulateScripted(target, actor, seed, schedule, rules))
                    proofs[definition.id] = "$enemyClass/mode$mode/hit$hitChance/attack$attackBase/seed$seed/${applied.reason}"
                    found = true
                    break@search
                }
            }
            if (!found) missing += definition.id
        }
        println("ARENA_GROWTH_EXECUTION_PROOFS=" + proofs.entries.joinToString(";") { "${it.key}=${it.value}" })
        assertTrue("No actual modifier application in legal current-state schedules: $missing", missing.isEmpty())
        assertEquals(144, proofs.size)
    }

    @Test fun `core numerical changes propagate through Korean English and Japanese display`() {
        val keys = mapOf(
            "WARRIOR_A" to "replacementShoutDamageBonusPercent", "WARRIOR_B" to "replacementApplications",
            "WARRIOR_C" to "replacementRecentHpDamagePercent", "ROGUE_A" to "replacementDamageMultiplier",
            "ROGUE_B" to "replacementLayerDurationTurns", "ROGUE_C" to "replacementMaximumManaLoss",
            "RANGER_A" to "conditionalDamageBonusPercent", "RANGER_B" to "sourceSupportMp",
            "RANGER_C" to "sourceSupportMp", "MAGE_A" to "damageBonusPercent", "MAGE_B" to "burnTotalMultiplier",
            "MAGE_C" to "sourceSupportMp", "CLERIC_A" to "instantHealMaxHpPercent", "CLERIC_B" to "actualHpDamageHealPercent",
            "CLERIC_C" to "sourceSupportMp", "PALADIN_A" to "replacementSkillDamageReductionPercent",
            "PALADIN_B" to "replacementExecutionMpCost", "PALADIN_C" to "maximumShieldPercentOwnMaxHp")
        for ((suffix, key) in keys) {
            val d = requireNotNull(ArenaProgressionCatalog.find("AT9_${suffix}_CORE"))
            val changed = d.copy(coreParameters = d.coreParameters + (key to 987.0))
            for (language in listOf("ko", "en", "ja")) assertTrue("${d.id}/$language", changed.effectText(language, 1, 0).contains("987"))
        }
    }

    private fun numericEvents(result: ArenaSupportResult) = result.events.filter {
        it.type in setOf(ArenaSupportEventType.CAST_START, ArenaSupportEventType.ATTACK_HIT,
            ArenaSupportEventType.SHIELD_ABSORBED, ArenaSupportEventType.HEAL_APPLIED, ArenaSupportEventType.DOT_DAMAGE,
            ArenaSupportEventType.MP_DRAINED, ArenaSupportEventType.STATUS_APPLIED)
    }.map { listOf(it.type, it.turn, it.actorId, it.actionId, it.amount, it.castTurns, it.hpAfter, it.mpAfterUnits, it.shieldAfter) }

    private fun enemyOrder(d: ArenaProgressionTraitDefinition): List<HeroClass> {
        val first = when {
            d.id in setOf("AT9_WARRIOR_A06", "AT9_ROGUE_B07", "AT9_PALADIN_B07", "AT9_CLERIC_C04", "AT9_CLERIC_C_CORE") -> HeroClass.ROGUE
            d.id.startsWith("AT9_RANGER_C") -> HeroClass.CLERIC
            d.id.contains("_B0") && d.heroClass in setOf(HeroClass.WARRIOR, HeroClass.RANGER) -> HeroClass.MAGE
            d.id in setOf("AT9_MAGE_C06", "AT9_CLERIC_C05", "AT9_PALADIN_A07", "AT9_CLERIC_C06") -> HeroClass.MAGE
            else -> HeroClass.WARRIOR
        }
        return listOf(first) + HeroClass.entries.filter { it != first }
    }

    private fun schedule(d: ArenaProgressionTraitDefinition, actor: ArenaSupportInput,
        target: ArenaSupportInput, mode: Int): Map<Pair<String, Int>, String> {
        val map = linkedMapOf<Pair<String, Int>, String>()
        val ownSkills = (1..3).map { tier -> actor.fighter.attacks.first { it.tier == tier }.id }
        val enemySkills = (1..3).map { tier -> target.fighter.attacks.first { it.tier == tier }.id }
        val ownSupports = (d.requiredSupportIds + when (actor.fighter.heroClass) {
            HeroClass.WARRIOR -> listOf("ARENA_SUP_FIGHTER_01", "ARENA_SUP_FIGHTER_02", "ARENA_SUP_FIGHTER_06", "ARENA_SUP_FIGHTER_04")
            HeroClass.ROGUE -> listOf("ARENA_SUP_ROGUE_04", "ARENA_SUP_ROGUE_01", "ARENA_SUP_ROGUE_11", "ARENA_SUP_ROGUE_03")
            HeroClass.RANGER -> listOf("ARENA_SUP_RANGER_01", "ARENA_SUP_RANGER_06", "ARENA_SUP_RANGER_10", "ARENA_SUP_RANGER_11")
            HeroClass.MAGE -> listOf("ARENA_SUP_MAGE_01", "ARENA_SUP_MAGE_07", "ARENA_SUP_MAGE_11", "ARENA_SUP_MAGE_08")
            HeroClass.CLERIC -> listOf("ARENA_SUP_CLERIC_01", "ARENA_SUP_CLERIC_06", "ARENA_SUP_CLERIC_04", "ARENA_SUP_CLERIC_03")
            HeroClass.PALADIN -> listOf("ARENA_SUP_PALADIN_01", "ARENA_SUP_PALADIN_09", "ARENA_SUP_PALADIN_02", "ARENA_SUP_PALADIN_08")
        }).distinct()
        val enemySupports = when (target.fighter.heroClass) {
            HeroClass.WARRIOR -> listOf("ARENA_SUP_FIGHTER_02", "ARENA_SUP_FIGHTER_01")
            HeroClass.ROGUE -> listOf("ARENA_SUP_ROGUE_02", "ARENA_SUP_ROGUE_11", "ARENA_SUP_ROGUE_10", "ARENA_SUP_ROGUE_01")
            HeroClass.RANGER -> listOf("ARENA_SUP_RANGER_03", "ARENA_SUP_RANGER_06", "ARENA_SUP_RANGER_11")
            HeroClass.MAGE -> listOf("ARENA_SUP_MAGE_01", "ARENA_SUP_MAGE_11", "ARENA_SUP_MAGE_02", "ARENA_SUP_MAGE_13")
            HeroClass.CLERIC -> listOf("ARENA_SUP_CLERIC_01", "ARENA_SUP_CLERIC_04", "ARENA_SUP_CLERIC_02")
            HeroClass.PALADIN -> listOf("ARENA_SUP_PALADIN_01", "ARENA_SUP_PALADIN_06", "ARENA_SUP_PALADIN_07")
        }
        for (turn in 1..180) {
            val ownSupport = if (mode % 3 == 0 && d.requiredSupportIds.isNotEmpty())
                d.requiredSupportIds[((turn - 1) / 4) % d.requiredSupportIds.size]
                else ownSupports[((turn - 1) / 4) % ownSupports.size]
            val ownAttack = when (mode) { 0, 3 -> "BASIC_ATTACK"; 1 -> ownSkills[0]; 2 -> ownSkills[1]; 4 -> ownSkills[2]; else -> ownSkills[(turn - 1) % 3] }
            map["actor" to turn] = if (turn % 4 == 1) ownSupport else ownAttack
            map["target" to turn] = if ((turn + mode) % 4 == 1) enemySupports[((turn - 1) / 4) % enemySupports.size]
                else if (mode == 3) "BASIC_ATTACK" else enemySkills[if (mode % 2 == 0) 2 else 1]
        }
        if (mode == 6) {
            when (d.id) {
                "AT9_WARRIOR_C02" -> for (turn in 1..180) {
                    map["actor" to turn] = if (turn % 12 == 7) "ARENA_SUP_FIGHTER_01" else "BASIC_ATTACK"
                    map["target" to turn] = if (turn % 12 in setOf(1, 6)) enemySkills[2] else "BASIC_ATTACK"
                }
                "AT9_WARRIOR_C04" -> for (turn in 1..180) {
                    map["target" to turn] = "BASIC_ATTACK"
                    map["actor" to turn] = if (turn % 5 == 3) ownSkills[1] else "BASIC_ATTACK"
                }
                "AT9_PALADIN_B04" -> for (turn in 1..180) {
                    map["target" to turn] = "BASIC_ATTACK"
                    map["actor" to turn] = when ((turn - 1) % 12) {
                        0 -> "ARENA_SUP_PALADIN_01"
                        5 -> "ARENA_SUP_PALADIN_09"
                        6 -> ownSkills[1]
                        else -> "BASIC_ATTACK"
                    }
                }
                "AT9_RANGER_B06" -> if (target.fighter.heroClass == HeroClass.MAGE) for (turn in 1..180) {
                    map["target" to turn] = if (turn % 12 == 3) "ARENA_SUP_MAGE_01" else "BASIC_ATTACK"
                    map["actor" to turn] = when (turn % 12) {
                        4 -> "ARENA_SUP_RANGER_08"
                        5 -> ownSkills[0]
                        else -> "BASIC_ATTACK"
                    }
                }
                "AT9_RANGER_C02" -> if (target.fighter.heroClass == HeroClass.CLERIC) for (turn in 1..180) {
                    map["actor" to turn] = "BASIC_ATTACK"
                    map["target" to turn] = when ((turn - 1) % 8) {
                        0 -> enemySkills[2]
                        3 -> "ARENA_SUP_CLERIC_01"
                        6 -> "ARENA_SUP_CLERIC_06"
                        else -> "BASIC_ATTACK"
                    }
                }
                "AT9_RANGER_C_CORE" -> if (target.fighter.heroClass == HeroClass.CLERIC) for (turn in 1..180) {
                    map["target" to turn] = if (turn % 10 == 4) "ARENA_SUP_CLERIC_01" else "BASIC_ATTACK"
                    map["actor" to turn] = when (turn % 10) {
                        6 -> "ARENA_SUP_RANGER_10"
                        7 -> ownSkills[0]
                        else -> "BASIC_ATTACK"
                    }
                }
                "AT9_MAGE_A07" -> if (target.fighter.heroClass == HeroClass.ROGUE) for (turn in 1..180) {
                    map["actor" to turn] = if (turn % 12 == 10) "ARENA_SUP_MAGE_03" else "BASIC_ATTACK"
                    map["target" to turn] = when (turn % 12) {
                        1, 9 -> "ARENA_SUP_ROGUE_02"
                        2, 6 -> enemySkills[2]
                        else -> "BASIC_ATTACK"
                    }
                }
                "AT9_ROGUE_C05" -> for (turn in 1..180) {
                    map["actor" to turn] = if (turn % 12 == 1) "ARENA_SUP_ROGUE_01" else if (turn % 12 == 2) ownSkills[2] else "BASIC_ATTACK"
                    map["target" to turn] = "BASIC_ATTACK"
                }
            }
        }
        return map
    }
}
