package com.nullplaying.engine.arena

import com.nullplaying.engine.SkillCatalog
import com.nullplaying.model.HeroClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArenaSkillTreeCatalogTest {
    @Test
    fun `repeatable attacks retain one cooldown turn at every rank and describe the real milestone`() {
        ArenaSkillTreeCatalog.values.filter { it.kind == ArenaSkillNodeKind.ATTACK }.forEach { node ->
            val profile = requireNotNull(node.attackProfile)
            if (!profile.oncePerBattle) (1..10).forEach { rank ->
                assertTrue("${node.id} rank $rank", profile.effectiveCooldownTurns(rank) >= 1)
            }
        }
        ArenaSupportCatalog.values.forEach { support ->
            (1..10).forEach { rank ->
                val effective = ArenaSkillTreeCatalog.effectiveSupport(support, rank)
                assertTrue("${support.id} cast at rank $rank", effective.castTurns >= 1)
                assertTrue("${support.id} cost at rank $rank", effective.mp > 0)
                if (!support.oncePerBattle) assertTrue("${support.id} cooldown at rank $rank", effective.cooldownTurns >= 2)
            }
        }
        val first = ArenaSkillTreeCatalog.attackProfile(1)
        assertEquals(1, first.effectiveCooldownTurns(10))
        assertEquals("명중률 +20%", first.milestoneText("ko", 10))
        assertEquals("Accuracy +20%", first.milestoneText("en", 10))
        assertEquals("命中率+20%", first.milestoneText("ja", 10))
    }

    @Test
    fun `all reviewed arena attack names stay aligned with their skill ids and language`() {
        val reviewed = ArenaAttackNameLocalization.values
        assertEquals(120, reviewed.size)
        assertEquals(SkillCatalog.all.map { it.name }.toSet(), reviewed.keys)
        assertEquals(120, reviewed.values.map(ArenaLocalizedAttackName::english).distinct().size)
        assertEquals(120, reviewed.values.map(ArenaLocalizedAttackName::japanese).distinct().size)

        HeroClass.entries.forEach { heroClass ->
            val skills = SkillCatalog.forClass(heroClass)
            val attackNodes = ArenaSkillTreeCatalog.forClass(heroClass)
                .filter { it.kind == ArenaSkillNodeKind.ATTACK }
            assertEquals(20, skills.size)
            assertEquals(20, attackNodes.size)
            skills.zip(attackNodes).forEachIndexed { index, (skill, node) ->
                val localized = ArenaAttackNameLocalization.get(skill.name)
                val label = "$heroClass A${(index + 1).toString().padStart(2, '0')}"
                assertEquals("$label skill id", skill.catalogId, node.sourceAttackId)
                assertEquals("$label numeric profile slot", index + 1, requireNotNull(node.attackProfile).slot)
                assertEquals("$label Korean", skill.name, node.nameKo)
                assertEquals("$label English", localized.english, node.nameEn)
                assertEquals("$label Japanese", localized.japanese, node.nameJa)
                assertFalse("$label English leaked Korean", node.nameEn.contains(Regex("[가-힣]")))
                assertFalse("$label Japanese leaked Korean", node.nameJa.contains(Regex("[가-힣]")))
            }
        }
    }

    @Test
    fun `v6 catalog has six exact ten by three class trees`() {
        assertEquals(3, ARENA_SKILL_TREE_SCHEMA_VERSION)
        assertEquals(13, ARENA_SKILL_TREE_CATALOG_VERSION)
        assertEquals("arena-skill-tree-v13", ArenaSkillTreeRules.rulesVersion)
        assertEquals("arena-support-full-v6", ARENA_SUPPORT_RULES_VERSION)
        assertEquals(180, ArenaSkillTreeCatalog.values.size)
        assertEquals(180, ArenaSkillTreeCatalog.values.map { it.id }.distinct().size)
        assertEquals(listOf(0, 3, 7, 12, 18, 25, 33, 42, 52, 63),
            ArenaSkillTreeCatalog.rowSpendThresholds)

        HeroClass.entries.forEach { heroClass ->
            val nodes = ArenaSkillTreeCatalog.forClass(heroClass)
            assertEquals("$heroClass node count", 30, nodes.size)
            assertEquals("$heroClass attack count", 20,
                nodes.count { it.kind == ArenaSkillNodeKind.ATTACK })
            assertEquals("$heroClass support count", 10,
                nodes.count { it.kind == ArenaSkillNodeKind.SUPPORT })
            assertEquals((0..9).flatMap { row -> (0..2).map { column -> row to column } },
                nodes.map { it.row to it.column })
            assertEquals(setOf("A01", "A02", "A03"),
                nodes.filter { it.isRoot }.map { it.slotKey }.toSet())
            assertTrue(nodes.all { it.maxRank == 10 })
            assertEquals(SkillCatalog.forClass(heroClass).map { it.catalogId },
                nodes.filter { it.kind == ArenaSkillNodeKind.ATTACK }.map { it.sourceAttackId })
            assertEquals(ArenaSupportCatalog.forClass(heroClass).map { it.id },
                nodes.filter { it.kind == ArenaSkillNodeKind.SUPPORT }.map { it.supportId })
        }
    }

    @Test
    fun `reported parent graph is exact acyclic and fully reachable`() {
        val expectedParents = mapOf(
            "A01" to emptySet(), "A02" to emptySet(), "A03" to emptySet(),
            "A04" to setOf("A01"), "S01" to setOf("A01", "A02"), "A05" to setOf("A03"),
            "S02" to setOf("A04"), "A06" to setOf("S01"), "A07" to setOf("A05"),
            "A08" to setOf("S02"), "A09" to setOf("A06", "A07"), "S03" to setOf("A07"),
            "A10" to setOf("A08"), "S04" to setOf("A09"), "A11" to setOf("S03"),
            "S05" to setOf("A10", "S04"), "A12" to setOf("S04"), "A13" to setOf("A11", "S04"),
            "A14" to setOf("S05"), "A15" to setOf("A12"), "S06" to setOf("A13"),
            "S07" to setOf("A14", "A15"), "A16" to setOf("A15"), "A17" to setOf("S06"),
            "A18" to setOf("S07"), "S08" to setOf("A16", "A17"), "S09" to setOf("A17"),
            "A19" to setOf("A18", "S08"), "S10" to setOf("S08", "S09"), "A20" to setOf("S09", "S10"),
        )
        HeroClass.entries.forEach { heroClass ->
            val nodes = ArenaSkillTreeCatalog.forClass(heroClass)
            val byId = nodes.associateBy { it.id }
            nodes.forEach { node ->
                assertEquals("$heroClass ${node.slotKey}", expectedParents.getValue(node.slotKey),
                    node.parentAnyOf.map { byId.getValue(it).slotKey }.toSet())
                assertEquals(ArenaSkillTreeCatalog.rowThreshold(node.row), node.minimumSpentPoints)
                if (!node.isRoot) {
                    assertEquals(3, node.minParentRank)
                    assertTrue(node.parentAnyOf.all {
                        val parent = byId.getValue(it)
                        parent.row < node.row || parent.row == node.row && parent.column < node.column
                    })
                }
            }
            val reached = nodes.filter { it.isRoot }.mapTo(mutableSetOf()) { it.id }
            repeat(nodes.size) {
                nodes.filter { it.id !in reached && it.parentAnyOf.any(reached::contains) }
                    .forEach { reached += it.id }
            }
            assertEquals(nodes.map { it.id }.toSet(), reached)
        }
    }

    @Test
    fun `all twenty attack profiles preserve authored numbers and rank curve`() {
        val profiles = ArenaSkillTreeCatalog.attackProfiles
        assertEquals((1..20).toList(), profiles.map { it.slot })
        assertEquals(listOf(0, 0, 1, 0, 1, 1, 0, 2, 1, 2, 0, 1, 1, 2, 0, 3, 1, 0, 2, 3),
            profiles.map { it.prepareTurns })
        assertEquals(listOf(4, 6, 9, 7, 11, 12, 8, 17, 13, 19, 9, 14, 15, 21, 12, 26, 20, 13, 27, 32),
            profiles.map { it.mpCost })
        assertEquals(listOf(1, 2, 2, 2, 3, 3, 3, 4, 3, 5, 3, 3, 4, 5, 4, 6, 5, 4, 6, 0),
            profiles.map { it.cooldownTurns })
        assertEquals(listOf(105, 115, 210, 85, 200, 225, 105, 340, 240, 390, 120, 280, 310, 440, 155, 520, 380, 170, 560, 720),
            profiles.map { it.baseDamagePercent })
        assertEquals(listOf(206, 261, 466, 233, 549, 617, 288, 933, 659, 1070, 329, 768, 851, 1207, 425, 1427, 1043, 466, 1537, 1976),
            profiles.map { it.rankTenDamagePercent })
        profiles.forEach { profile ->
            assertEquals(profile.baseDamagePercent, profile.rankOneDamagePercent)
            assertTrue((1..10).zipWithNext().all { (a, b) -> profile.damagePercent(a) <= profile.damagePercent(b) })
            assertEquals((1..10).map(profile::damagePercent), profile.damagePercentByRank)
            assertEquals(listOf(25, 35, 45, 55)[profile.prepareTurns], profile.maxHpDamageCapPercent)
            assertEquals(profile.maxHpDamageCapPercent, profile.effectiveMaxHpDamageCapPercent(9))
            assertEquals(profile.maxHpDamageCapPercent, profile.effectiveMaxHpDamageCapPercent(10))
            assertEquals(profile.mpCost + 4, profile.effectiveMpCost(1))
            assertTrue((1..10).zipWithNext().all { (before, after) ->
                profile.effectiveMpCost(after) > profile.effectiveMpCost(before)
            })
            assertEquals(profile.cooldownTurns, profile.effectiveCooldownTurns(9))
            val dedicatedMastery = profile.slot in setOf(
                2, 3, 5, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 20,
            )
            assertEquals(
                if (profile.oncePerBattle || dedicatedMastery) profile.cooldownTurns else
                    (profile.cooldownTurns - 1).coerceAtLeast(1),
                profile.effectiveCooldownTurns(10),
            )
            HeroClass.entries.forEach { heroClass ->
                listOf("ko", "en", "ja").forEach { language ->
                    assertFalse(profile.compactText(language, 1, heroClass).contains('{'))
                    assertFalse(profile.currentToNextText(language, 9, heroClass).contains('{'))
                    assertTrue(requireNotNull(profile.milestoneText(language, 5)).isNotBlank())
                    assertTrue(requireNotNull(profile.milestoneText(language, 10)).isNotBlank())
                }
            }
            assertNull(profile.milestoneText("ko", 4))
        }
        assertTrue(profiles.last().oncePerBattle)
        assertEquals(6, profiles.last().earliestTurn)
        assertEquals(56, profiles.last().effectiveMpCost(10))
        assertEquals(0, profiles.last().effectiveCooldownTurns(10))
        assertEquals(3, profiles.last().prepareTurns)
        assertEquals("스킬 피해 +12%", profiles.first().milestoneText("ko-KR", 5))
        assertEquals("Accuracy +20%", profiles.first().milestoneText("en-US", 10))
        assertEquals("相手のダメージ軽減効果を40%無視", profiles.last().milestoneText("ja-JP", 10))
        profiles.forEach { profile ->
            listOf("ko", "en", "ja").forEach { language ->
                val adept = requireNotNull(profile.milestoneText(language, 5))
                assertFalse("R5 must not present mana savings as the main bonus", adept.contains("MP"))
                assertFalse("R5 must not present mana savings as the main bonus", adept.contains("마나"))
                assertFalse("R5 must not present mana savings as the main bonus", adept.contains("マナ"))
            }
        }
        assertEquals(18.0, profiles[1].effect.parameter("masterBleedTick", 10), 0.0)
        assertEquals(0.0, profiles[1].effect.parameter("masterBleedTick", 9), 0.0)
        assertEquals(15.0, profiles[2].effect.parameter("masterRupture", 10), 0.0)
        assertEquals(55.0, profiles[7].effect.parameter("ignore", 10), 0.0)
        assertEquals(2.0, profiles[13].effect.parameter("delay", 10), 0.0)
        assertEquals(40.0, profiles[16].effect.parameter("threshold", 10), 0.0)
        assertEquals(2.0, profiles[17].effect.parameter("dispel", 10), 0.0)
        assertEquals(55.0, profiles.last().effect.parameter("cap", 10), 0.0)
        assertEquals(40.0, profiles.last().effect.parameter("ignore", 10), 0.0)

        val accurate = ArenaSkillTreeCatalog.attackProfile(1)
        assertTrue(accurate.effectText("ko", 5, HeroClass.WARRIOR).contains("명중률 +13%"))
        assertFalse(accurate.effectText("ko", 5, HeroClass.WARRIOR).contains("13.3"))

        val storm = ArenaSkillTreeCatalog.attackProfile(6)
        assertEquals(ArenaAttackEffectKind.MANA_PRESSURE, storm.effect.kind)
        assertEquals(4.0, storm.effect.parameter("drain", 1), 0.0)
        assertEquals(12.0, storm.effect.parameter("drain", 10), 0.0)
        assertEquals("적중 시 상대 MP -12", storm.effectText("ko", 10, HeroClass.WARRIOR))
        assertEquals("재사용 대기 1턴 감소", storm.milestoneText("ko", 10))

        val intercept = ArenaSkillTreeCatalog.attackProfile(7)
        assertEquals(10.0, intercept.effect.parameter("accuracy", 1), 0.0)
        assertEquals(30.0, intercept.effect.parameter("accuracy", 10), 0.0)
        assertEquals(
            "준비 중인 상대에게 피해 +20%\n명중률 +10%",
            intercept.effectText("ko", 1, HeroClass.WARRIOR),
        )
        assertEquals(
            "Against a preparing target, damage +60%\nAccuracy +30%",
            intercept.effectText("en", 10, HeroClass.WARRIOR),
        )
        assertEquals(
            "準備中の相手へのダメージ+60%\n命中率+30%",
            intercept.effectText("ja", 10, HeroClass.WARRIOR),
        )
        assertFalse(ArenaSkillTreeCatalog.attackProfile(2).effect.parameters.any { it.key == "accuracy" })
    }

    @Test
    fun `a19 owns six localized mechanic overrides`() {
        val profile = ArenaSkillTreeCatalog.attackProfile(19)
        assertEquals(HeroClass.entries.toSet(), profile.classOverrides.keys)
        assertEquals(6, profile.classOverrides.values.map { it.effect.kind }.distinct().size)
        HeroClass.entries.forEach { heroClass ->
            val effect = profile.effectFor(heroClass)
            assertNotEquals(ArenaAttackEffectKind.CLASS_COUNTER, effect.kind)
            listOf("ko", "en", "ja").forEach { language ->
                val text = profile.effectText(language, 10, heroClass)
                assertTrue("$heroClass $language", text.isNotBlank())
                assertFalse("$heroClass $language unresolved parameter", text.contains('{'))
            }
        }
        assertEquals(200.0,
            profile.effectFor(HeroClass.WARRIOR).parameter("shatter", 10), 0.0)
        assertEquals(45.0,
            profile.effectFor(HeroClass.ROGUE).parameter("bonus", 10), 0.0)
        assertEquals(35.0,
            profile.effectFor(HeroClass.RANGER).parameter("bonus", 10), 0.0)
        assertEquals(55.0,
            profile.effectFor(HeroClass.MAGE).parameter("burn", 10), 0.0)
        assertEquals(30.0,
            profile.effectFor(HeroClass.CLERIC).parameter("bonus", 10), 0.0)
        assertEquals(30.0,
            profile.effectFor(HeroClass.PALADIN).parameter("pierceReduction", 10), 0.0)
    }

    @Test
    fun `support ranks apply continuous milestones caps and discrete exceptions`() {
        val shout = requireNotNull(ArenaSupportCatalog.find("ARENA_SUP_FIGHTER_02"))
        val shoutBefore = shout.copy()
        assertEquals(11.0, effectiveSupport(shout, 1).magnitude, 0.0)
        assertEquals(20.0, effectiveSupport(shout, 5).magnitude, 0.0)
        assertEquals(30.0, effectiveSupport(shout, 10).magnitude, 0.0)
        assertEquals(listOf(24, 29, 34, 40), listOf(1, 4, 7, 10).map { effectiveSupport(shout, it).mp })
        assertEquals(listOf(6, 6, 5, 5, 4),
            listOf(1, 4, 5, 9, 10).map { effectiveSupport(shout, it).cooldownTurns })
        assertEquals(shoutBefore, shout)

        ArenaSupportCatalog.values.forEach { base ->
            (1..10).forEach { rank ->
                val effective = effectiveSupport(base, rank)
                assertEquals(base.id, effective.id)
                assertEquals(base.heroClass, effective.heroClass)
                assertEquals(base.kind, effective.kind)
                assertEquals(
                    if (rank >= 5 && base.oncePerBattle && base.castTurns > 1) {
                        base.castTurns - 1
                    } else {
                        base.castTurns
                    },
                    effective.castTurns,
                )
                assertEquals(base.durationTurns, effective.durationTurns)
                assertEquals(base.threshold, effective.threshold, 0.0)
                assertEquals(arenaRankedMpCost(base.mp, rank), effective.mp)
                if (!base.oncePerBattle) assertTrue(effective.cooldownTurns >= 2)
                assertTrue(effective.magnitude.isFinite() && effective.secondary.isFinite())
            }
            val executableRanks = (1..10).map { rank ->
                effectiveSupport(base, rank).let { effective ->
                    listOf(
                        effective.mp.toDouble(),
                        effective.cooldownTurns.toDouble(),
                        effective.charges.toDouble(),
                        effective.magnitude,
                        effective.secondary,
                    )
                }
            }
            assertNotEquals("${base.id} must have a material rank-five milestone",
                executableRanks[3], executableRanks[4])
            assertNotEquals("${base.id} must have a material rank-ten milestone",
                executableRanks[8], executableRanks[9])
        }

        val cap = requireNotNull(ArenaSupportCatalog.find("ARENA_SUP_FIGHTER_09"))
        assertEquals(listOf(28.0, 26.0, 24.0, 22.0, 20.0, 19.0, 18.0, 17.0, 16.0, 15.0),
            (1..10).map { effectiveSupport(cap, it).magnitude })
        val mirror = requireNotNull(ArenaSupportCatalog.find("ARENA_SUP_MAGE_02"))
        assertEquals(listOf(1, 1, 2, 2, 3, 3, 3, 3, 3, 4),
            (1..10).map { effectiveSupport(mirror, it).charges })
        val charged = requireNotNull(ArenaSupportCatalog.find("ARENA_SUP_FIGHTER_03"))
        assertEquals(1, effectiveSupport(charged, 9).charges)
        assertEquals(2, effectiveSupport(charged, 10).charges)
        assertEquals(3, effectiveSupport(
            requireNotNull(ArenaSupportCatalog.find("ARENA_SUP_FIGHTER_04")), 10,
        ).cooldownTurns)
        listOf("ko", "en", "ja").forEach { language ->
            assertTrue(requireNotNull(ArenaSkillTreeCatalog.supportMilestoneText(
                charged, language, 5,
            )).isNotBlank())
            assertTrue(requireNotNull(ArenaSkillTreeCatalog.supportMilestoneText(
                charged, language, 10,
            )).isNotBlank())
        }
        assertNull(ArenaSkillTreeCatalog.supportMilestoneText(charged, "ko", 9))
        assertEquals("재사용 대기 1턴 감소",
            ArenaSkillTreeCatalog.supportMilestoneText(charged, "ko-KR", 5))
        assertEquals("1 additional use",
            ArenaSkillTreeCatalog.supportMilestoneText(charged, "en-US", 10))
        val once = requireNotNull(ArenaSupportCatalog.find("ARENA_SUP_FIGHTER_08"))
        assertTrue(once.oncePerBattle)
        assertEquals("시전 시간 1턴 감소",
            ArenaSkillTreeCatalog.supportMilestoneText(once, "ko", 5))
        assertEquals("사용 횟수 1회 증가", ArenaSkillTreeCatalog.supportMilestoneText(once, "ko", 10))
        listOf(5, 10).forEach { milestoneRank ->
            ArenaSupportCatalog.values.forEach { definition ->
                ArenaSkillTreeCatalog.supportMilestoneText(definition, "ko", milestoneRank)
                    ?.let { assertTrue("${definition.id} R$milestoneRank", it.isNotBlank()) }
            }
        }
        val cleanse = requireNotNull(ArenaSupportCatalog.find("ARENA_SUP_CLERIC_03"))
        assertEquals(1.0, effectiveSupport(cleanse, 6).magnitude, 0.0)
        assertEquals(2.0, effectiveSupport(cleanse, 7).magnitude, 0.0)
        val statusGuard = requireNotNull(ArenaSupportCatalog.find("ARENA_SUP_MAGE_08"))
        assertEquals(1, effectiveSupport(statusGuard, 6).charges)
        assertEquals(2, effectiveSupport(statusGuard, 7).charges)
        listOf("ARENA_SUP_PALADIN_03", "ARENA_SUP_CLERIC_10").forEach { supportId ->
            val oneHitRemoval = requireNotNull(ArenaSupportCatalog.find(supportId))
            assertEquals(2.0, effectiveSupport(oneHitRemoval, 7).magnitude, 0.0)
            assertEquals(1, effectiveSupport(oneHitRemoval, 7).charges)
        }
        val control = requireNotNull(ArenaSupportCatalog.find("ARENA_SUP_MAGE_13"))
        assertEquals(1.0, effectiveSupport(control, 10).magnitude, 0.0)
        assertEquals(18.0, effectiveSupport(control, 10).secondary, 0.0)
        assertEquals(control.durationTurns, effectiveSupport(control, 10).durationTurns)
        assertEquals(control.cooldownTurns - 2, effectiveSupport(control, 10).cooldownTurns)
        assertEquals("재사용 대기 1턴 추가 감소",
            ArenaSkillTreeCatalog.supportMilestoneText(control, "ko", 10))
        val poison = requireNotNull(ArenaSupportCatalog.find("ARENA_SUP_ROGUE_12"))
        assertEquals(1.0, effectiveSupport(poison, 10).magnitude, 0.0)
        assertEquals(45.0, effectiveSupport(poison, 10).secondary, 0.0)

        val poisonCoat = requireNotNull(ArenaSupportCatalog.find("ARENA_SUP_ROGUE_11"))
        assertEquals(poisonCoat.secondary, effectiveSupport(poisonCoat, 1).secondary, 0.0)
        assertEquals(poisonCoat.secondary, effectiveSupport(poisonCoat, 10).secondary, 0.0)
        val restraint = requireNotNull(ArenaSupportCatalog.find("ARENA_SUP_CLERIC_06"))
        assertEquals(10.0, effectiveSupport(restraint, 5).secondary, 0.0)
        assertTrue(effectiveSupport(restraint, 10).secondary < effectiveSupport(restraint, 1).secondary)
    }

    @Test
    fun `invalid ranks and foreign support copies are rejected`() {
        val support = requireNotNull(ArenaSupportCatalog.find("ARENA_SUP_RANGER_01"))
        assertIllegal { effectiveSupport(support, 0) }
        assertIllegal { effectiveSupport(support, 11) }
        assertIllegal { effectiveSupport(support.copy(id = "not-catalog"), 1) }
        assertNotNull(ArenaSkillTreeCatalog.find("ranger_t01_c01"))
    }

    private fun assertIllegal(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue("Expected IllegalArgumentException", failed)
    }
}
