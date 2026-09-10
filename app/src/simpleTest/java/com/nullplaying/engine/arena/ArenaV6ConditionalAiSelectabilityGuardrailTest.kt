package com.nullplaying.engine.arena

import com.nullplaying.model.HeroClass
import org.junit.Assert.assertTrue
import org.junit.Test

class ArenaV6ConditionalAiSelectabilityGuardrailTest {
    private val ids = listOf(
        "ARENA_SUP_FIGHTER_06", "ARENA_SUP_FIGHTER_03", "ARENA_SUP_FIGHTER_04",
        "ARENA_SUP_FIGHTER_05", "ARENA_SUP_FIGHTER_10", "ARENA_SUP_ROGUE_02",
        "ARENA_SUP_ROGUE_10", "ARENA_SUP_ROGUE_12", "ARENA_SUP_RANGER_06",
        "ARENA_SUP_RANGER_07", "ARENA_SUP_RANGER_03", "ARENA_SUP_RANGER_05",
        "ARENA_SUP_RANGER_04", "ARENA_SUP_RANGER_10", "ARENA_SUP_MAGE_02",
        "ARENA_SUP_MAGE_03", "ARENA_SUP_CLERIC_10", "ARENA_SUP_PALADIN_09",
        "ARENA_SUP_PALADIN_02", "ARENA_SUP_PALADIN_05", "ARENA_SUP_PALADIN_10",
    )

    @Test fun `conditional support nodes remain selectable by the normal AI when their conditions are met`() {
        val missing = mutableListOf<String>()
        ids.forEach { id ->
            val support = requireNotNull(ArenaSupportCatalog.find(id))
            var proof: String? = null
            search@ for (enemyClass in HeroClass.entries) {
                for (hitChance in listOf(.25, .55, .85, 1.0)) {
                    for (attackBase in listOf(12.0, 35.0, 90.0, 180.0)) {
                        for (healthBase in listOf(350.0, 600.0, 1200.0)) {
                            for (seed in 1L..12L) {
                                val actor = focused(support, "actor")
                                val target = tailoredTarget(id, enemyClass)
                                val schedule = setupSchedule(id, actor, target)
                                val rules = ArenaTurnRules(
                                    safetyTurnLimit = 150,
                                    hitChance = hitChance,
                                    damageVariance = 0.0,
                                    formula = ArenaStatFormula(
                                        healthBase = healthBase, healthScale = 0.0,
                                        attackBase = attackBase, attackScale = 0.0,
                                    ),
                                )
                                val result = ArenaSupportTurnEngine.simulateScripted(actor, target, seed, schedule, rules)
                                val cast = result.events.any { it.type == ArenaSupportEventType.CAST_START && it.actorId == "actor" && it.actionId == id }
                                val applied = result.events.any { it.type == ArenaSupportEventType.SUPPORT_APPLIED && it.actorId == "actor" && it.actionId == id }
                                if (cast && applied) {
                                    val castTurn = result.events.first {
                                        it.type == ArenaSupportEventType.CAST_START &&
                                            it.actorId == "actor" && it.actionId == id
                                    }.turn
                                    proof = "enemy=$enemyClass hit=$hitChance atk=$attackBase hp=$healthBase seed=$seed turn=$castTurn"
                                    break@search
                                }
                            }
                        }
                    }
                }
            }
            println("arena-ai-selectable id=$id condition=${support.conditionKey} proof=${proof ?: "MISSING"}")
            if (proof == null) missing += id
        }
        println("arena-ai-selectable missing=${missing.size} ids=$missing")
        assertTrue("AI unreachable: $missing", missing.isEmpty())
    }

    private fun focused(support: ArenaSupportDefinition, id: String): ArenaSupportInput {
        val template = ArenaSupportQaFixtures.fullFighter(support.heroClass, 95, id)
        val attackSources = template.fighter.attacks.associateBy { it.id }
        val attackNodes = ArenaSkillTreeCatalog.forClass(support.heroClass)
            .filter { it.kind == ArenaSkillNodeKind.ATTACK && it.slotKey in setOf("A01", "A02", "A03") }
        val attacks = attackNodes
            .filter { support.id != "ARENA_SUP_MAGE_02" }
            .map { ArenaTurnInputAdapter.resolveAttack(attackSources.getValue(it.id), support.heroClass, 10) }
        val supports = buildMap {
            put(support.id, 10)
            if (support.id == "ARENA_SUP_ROGUE_12") put("ARENA_SUP_ROGUE_11", 10)
        }
        return template.copy(
            fighter = template.fighter.copy(attacks = attacks),
            supportIds = supports.keys,
            supportRanks = supports,
            traits = emptyList(), arenaLevel = 100,
        ).freezeResolvedSupports()
    }

    private fun tailoredTarget(desired: String, enemyClass: HeroClass): ArenaSupportInput {
        val selectedClass = when (desired) {
            "ARENA_SUP_FIGHTER_03" -> HeroClass.MAGE
            "ARENA_SUP_FIGHTER_05" -> HeroClass.WARRIOR
            "ARENA_SUP_RANGER_06", "ARENA_SUP_MAGE_02" -> HeroClass.WARRIOR
            "ARENA_SUP_RANGER_10" -> HeroClass.CLERIC
            else -> enemyClass
        }
        val template = ArenaSupportQaFixtures.fullFighter(selectedClass, 95, "target")
        return when (desired) {
            "ARENA_SUP_FIGHTER_03" -> ranked(template.copy(
                supportIds = setOf("ARENA_SUP_MAGE_01"),
                supportRanks = mapOf("ARENA_SUP_MAGE_01" to 10),
            )).freezeResolvedSupports()
            "ARENA_SUP_FIGHTER_05" -> ranked(template.copy(
                supportIds = setOf("ARENA_SUP_FIGHTER_01"),
                supportRanks = mapOf("ARENA_SUP_FIGHTER_01" to 10),
            )).freezeResolvedSupports()
            "ARENA_SUP_ROGUE_10", "ARENA_SUP_RANGER_06" -> template.copy(
                fighter = template.fighter.copy(attacks = emptyList()), supportIds = emptySet(),
            )
            "ARENA_SUP_RANGER_10" -> ranked(template.copy(
                supportIds = setOf("ARENA_SUP_CLERIC_01"),
                supportRanks = mapOf("ARENA_SUP_CLERIC_01" to 10),
            )).freezeResolvedSupports()
            "ARENA_SUP_MAGE_02" -> template.copy(supportIds = emptySet())
            else -> template
        }
    }

    private fun ranked(input: ArenaSupportInput): ArenaSupportInput = input.copy(
        fighter = input.fighter.copy(attacks = input.fighter.attacks.map { attack ->
            ArenaTurnInputAdapter.resolveAttack(attack, input.fighter.heroClass, 10)
        }),
    )

    private fun setupSchedule(desired: String, actor: ArenaSupportInput, target: ArenaSupportInput): Map<Pair<String, Int>, String> {
        val map = linkedMapOf<Pair<String, Int>, String>()
        val targetSupports = target.supportIds.sorted()
        val targetAttack = target.fighter.attacks.firstOrNull { it.tier == 3 }?.id ?: "BASIC_ATTACK"
        for (turn in 1..150) {
            map["target" to turn] = when {
                turn % 4 == 1 && targetSupports.isNotEmpty() -> targetSupports[((turn - 1) / 4) % targetSupports.size]
                else -> targetAttack
            }
        }
        if (desired == "ARENA_SUP_ROGUE_12") {
            map["actor" to 1] = "ARENA_SUP_ROGUE_11"
            map["actor" to 2] = "BASIC_ATTACK"
        }
        if (desired == "ARENA_SUP_RANGER_10") {
            map["actor" to 1] = actor.fighter.attacks.last().id
            map["actor" to 3] = "BASIC_ATTACK"
            map["target" to 3] = "ARENA_SUP_CLERIC_01"
        }
        return map
    }
}
