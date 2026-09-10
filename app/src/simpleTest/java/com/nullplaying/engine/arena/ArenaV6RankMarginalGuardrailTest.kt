package com.nullplaying.engine.arena

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArenaV6RankMarginalGuardrailTest {
    @Test fun `all 180 V6 nodes gain an executable benefit at rank ten`() {
        val noMarginal = mutableListOf<String>()
        var attackCount = 0
        var supportCount = 0
        ArenaSkillTreeCatalog.values.forEach { node ->
            when (node.kind) {
                ArenaSkillNodeKind.ATTACK -> {
                    attackCount++
                    val p = requireNotNull(node.attackProfile)
                    val r9Damage = p.damagePercent(node.heroClass, 9)
                    val r10Damage = p.damagePercent(node.heroClass, 10)
                    val r9Effect = p.effectFor(node.heroClass).parameters.associate { it.key to it.value(9) }
                    val r10Effect = p.effectFor(node.heroClass).parameters.associate { it.key to it.value(10) }
                    val benefits = buildList {
                        if (r10Damage > r9Damage) add("damage")
                        if (p.effectiveCooldownTurns(10) < p.effectiveCooldownTurns(9)) add("cooldown")
                        if (r10Effect != r9Effect) add("effect")
                    }
                    if (benefits.isEmpty()) noMarginal += node.id
                }
                ArenaSkillNodeKind.SUPPORT -> {
                    supportCount++
                    val base = requireNotNull(ArenaSupportCatalog.find(node.id))
                    val r9 = ArenaSkillTreeCatalog.effectiveSupport(base, 9)
                    val r10 = ArenaSkillTreeCatalog.effectiveSupport(base, 10)
                    val benefits = buildList {
                        if (r10.magnitude != r9.magnitude) add("magnitude")
                        if (r10.secondary != r9.secondary) add("secondary")
                        if (r10.charges > r9.charges) add("charges")
                        if (r10.cooldownTurns < r9.cooldownTurns) add("cooldown")
                        if (r10.castTurns < r9.castTurns) add("cast")
                    }
                    if (benefits.isEmpty()) noMarginal += node.id
                }
            }
        }
        assertEquals(120, attackCount)
        assertEquals(60, supportCount)
        println("arena-marginal-summary attacks=$attackCount supports=$supportCount noMarginal=${noMarginal.size} ids=$noMarginal")
        assertTrue("rank ten must add an executable benefit: $noMarginal", noMarginal.isEmpty())
    }
}
