package com.nullplaying.ui

import com.nullplaying.engine.HeroPathCatalog
import com.nullplaying.localization.AppLanguage
import com.nullplaying.model.HeroPathNodeType
import com.nullplaying.model.HeroPathNodeSlot
import com.nullplaying.model.HeroPathBranch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeroPathCopyTest {
    @Test
    fun `investment gates do not claim a particular prerequisite talent`() {
        listOf(3, 6, 9).forEach { points ->
            val requirement = requireNotNull(heroPathPrerequisiteRequirement(points, false, true))
            AppLanguage.entries.forEach { language ->
                val text = requirement.resolve(language)
                assertTrue(text.contains(points.toString()))
                assertFalse(text.contains("선행"))
                assertFalse(text.contains("Prerequisite"))
                assertFalse(text.contains("前提"))
                if (language != AppLanguage.KOREAN) assertFalse(text.contains(Regex("[가-힣]")))
            }
            assertEquals(requirement, heroPathPrerequisiteRequirement(points, false, false))
        }
        assertEquals(
            HeroPathCopy("선행 특성 필요", "Prerequisite required", "前提特性が必要"),
            heroPathPrerequisiteRequirement(3, true, false),
        )
        assertEquals(null, heroPathPrerequisiteRequirement(3, true, true))
        val ordinary = requireNotNull(heroPathPrerequisiteRequirement(6, false, true))
        val core = requireNotNull(heroPathPrerequisiteRequirement(9, false, true, excludesThisTalent = true))
        assertTrue(ordinary.ko.contains("습득 후"))
        assertTrue(ordinary.en.contains("after learning"))
        assertTrue(ordinary.ja.contains("習得後"))
        assertTrue(core.ko.contains("다른 특성"))
        assertTrue(core.en.contains("other talents"))
        assertTrue(core.ja.contains("他の特性"))
    }

    @Test
    fun `specialization tab symbols describe each branch rather than its index`() {
        val expected = mapOf(
            HeroPathBranch.WARRIOR_BERSERKER to "Filled.LocalFireDepartment",
            HeroPathBranch.WARRIOR_BULWARK to "Filled.Shield",
            HeroPathBranch.WARRIOR_WARLORD to "Filled.Campaign",
            HeroPathBranch.ROGUE_ASSASSIN to "Filled.FlashOn",
            HeroPathBranch.ROGUE_SHADOW_DANCER to "Filled.VisibilityOff",
            HeroPathBranch.ROGUE_TRICKSTER to "Filled.TheaterComedy",
            HeroPathBranch.RANGER_MARKSMAN to "Filled.MyLocation",
            HeroPathBranch.RANGER_WINDWALKER to "Filled.Pets",
            HeroPathBranch.RANGER_TRAPPER to "Filled.Forest",
            HeroPathBranch.MAGE_ELEMENTALIST to "Filled.LocalFireDepartment",
            HeroPathBranch.MAGE_ARCANIST to "Filled.AutoAwesome",
            HeroPathBranch.MAGE_FORBIDDEN to "Filled.AcUnit",
            HeroPathBranch.CLERIC_SANCTUARY to "Filled.WbSunny",
            HeroPathBranch.CLERIC_JUDGMENT to "Filled.Gavel",
            HeroPathBranch.CLERIC_PROVIDENCE to "Filled.SelfImprovement",
            HeroPathBranch.PALADIN_GUARDIAN to "Filled.Security",
            HeroPathBranch.PALADIN_AVENGER to "Filled.FlashOn",
            HeroPathBranch.PALADIN_DAWN to "Filled.LightMode",
        )
        assertEquals(HeroPathBranch.entries.toSet(), expected.keys)
        expected.forEach { (branch, name) -> assertEquals(branch.name, name, heroPathBranchIcon(branch).name) }
    }

    @Test
    fun `every real node has complete localized conditions and rank effects`() {
        HeroPathCatalog.nodes.forEach { node ->
            val detail = heroPathCombatDetail(node)
            AppLanguage.entries.forEach { language ->
                listOfNotNull(detail.summary, detail.rule, detail.current, detail.next).forEach { copy ->
                    val text = copy.resolve(language)
                    assertTrue("${node.traitId}: empty $language", text.isNotBlank())
                    assertFalse(text.contains('{'))
                    if (language != AppLanguage.KOREAN) assertFalse(text.contains(Regex("[가-힣]")))
                }
            }
            assertTrue("${node.traitId}: short summary", detail.summary.en.length <= 100)
            assertTrue("${node.traitId}: next effect", detail.next != null)
            assertEquals(null, heroPathCombatDetail(node, mapOf(node.traitId to node.maxRank)).next)
        }
    }

    @Test
    fun `foundation rule only mentions impact loss for the relevant class`() {
        HeroPathCatalog.nodes.filter { it.slot == HeroPathNodeSlot.FOUNDATION_A }.forEach { node ->
            val rule = heroPathCombatDetail(node).rule
            val needsLoss = node.heroClassAffinity in setOf(
                com.nullplaying.model.BattleHeroClass.ROGUE,
                com.nullplaying.model.BattleHeroClass.RANGER,
            )
            assertEquals(needsLoss, rule.ko.contains("피격 손실"))
            assertEquals(needsLoss, rule.en.contains("Impact loss"))
            assertEquals(needsLoss, rule.ja.contains("被弾による減少"))
        }
    }

    @Test
    fun `warrior foundation shows actual threshold and cross branch rank cap`() {
        val first = HeroPathCatalog.nodes.first { it.branch == HeroPathBranch.WARRIOR_BERSERKER && it.slot == HeroPathNodeSlot.FOUNDATION_A }
        val second = HeroPathCatalog.nodes.first { it.branch == HeroPathBranch.WARRIOR_BULWARK && it.slot == HeroPathNodeSlot.FOUNDATION_A }
        AppLanguage.entries.forEach { language ->
            val unlearned = heroPathCombatDetail(first)
            assertTrue(unlearned.current.resolve(language).contains("12%"))
            assertTrue(unlearned.next!!.resolve(language).contains("11%"))
            val fromOtherBranch = heroPathCombatDetail(first, mapOf(second.traitId to 1))
            assertTrue(fromOtherBranch.current.resolve(language).contains("11%"))
            assertTrue(fromOtherBranch.next!!.resolve(language).contains("10%"))
            val capped = heroPathCombatDetail(first, mapOf(second.traitId to 2))
            assertEquals(capped.current.resolve(language), capped.next!!.resolve(language))
        }
    }

    @Test
    fun `special and core conditions expose charge health round and survival limits`() {
        val special = HeroPathCatalog.nodes.first { it.slot == HeroPathNodeSlot.SPECIAL_B }
        val core = HeroPathCatalog.nodes.first { it.branch == HeroPathBranch.WARRIOR_BULWARK && it.slot == HeroPathNodeSlot.CORE }
        AppLanguage.entries.forEach { language ->
            val specialRule = heroPathCombatDetail(special).rule.resolve(language)
            assertTrue(specialRule.contains("3"))
            assertTrue(specialRule.contains("60%"))
            assertTrue(specialRule.contains("6"))
            val coreRule = heroPathCombatDetail(core).rule.resolve(language)
            assertTrue(coreRule.contains("3"))
            assertTrue(coreRule.contains("1"))
        }
    }

    @Test
    fun `all six classes show actual foundation rank changes in every language`() {
        HeroPathCatalog.nodes.filter { it.slot in setOf(HeroPathNodeSlot.FOUNDATION_A, HeroPathNodeSlot.FOUNDATION_B) }.forEach { node ->
            val zero = heroPathCombatDetail(node)
            val one = heroPathCombatDetail(node, mapOf(node.traitId to 1))
            AppLanguage.entries.forEach { language ->
                assertFalse("${node.traitId}: rank 1 $language", zero.current.resolve(language) == zero.next!!.resolve(language))
                assertFalse("${node.traitId}: rank 2 $language", one.current.resolve(language) == one.next!!.resolve(language))
            }
        }
    }

    @Test
    fun `frost advanced ranks expose damage and cap progression`() {
        val node = HeroPathCatalog.nodes.first { it.branch == HeroPathBranch.MAGE_FORBIDDEN && it.slot == HeroPathNodeSlot.ADVANCED_TACTIC }
        AppLanguage.entries.forEach { language ->
            val first = heroPathCombatDetail(node)
            assertTrue(first.current.resolve(language).contains("10%"))
            assertTrue(first.current.resolve(language).contains("4%"))
            assertTrue(first.next!!.resolve(language).contains("20%"))
            assertTrue(first.next.resolve(language).contains("6%"))
            val second = heroPathCombatDetail(node, mapOf(node.traitId to 1))
            assertTrue(second.next!!.resolve(language).contains("30%"))
            assertTrue(second.next.resolve(language).contains("8%"))
        }
    }

    @Test
    fun `learned specials show numeric effect rather than dramatic generic prose`() {
        val node = HeroPathCatalog.nodes.first { it.branch == HeroPathBranch.CLERIC_SANCTUARY && it.slot == HeroPathNodeSlot.SPECIAL_A }
        AppLanguage.entries.forEach { language ->
            val effect = heroPathCombatDetail(node, mapOf(node.traitId to 1)).current.resolve(language)
            listOf("25%", "5%", "15%", "30%").forEach { assertTrue("$language: $effect", effect.contains(it)) }
        }
    }

    @Test
    fun `choice and advanced tactics have distinct trilingual labels`() {
        val choice = HeroPathCatalog.nodes.first { it.nodeType == HeroPathNodeType.CHOICE }
        val advanced = HeroPathCatalog.nodes.first { it.nodeType == HeroPathNodeType.TACTICAL }

        assertEquals(HeroPathNodeKind.MATCHUP_TACTIC, choice.uiKind(3))
        assertEquals(HeroPathNodeKind.KEYSTONE, advanced.uiKind(7))
        assertEquals(HeroPathCopy("상성 전술", "Matchup Tactic", "相性戦術"), HeroPathNodeKind.MATCHUP_TACTIC.label)
        assertEquals(HeroPathCopy("고급 전술", "Advanced Tactic", "上級戦術"), HeroPathNodeKind.KEYSTONE.label)
    }

    @Test
    fun `matchup TalkBack reads type meaning automation exclusivity then state`() {
        val node = HeroPathNodeUiModel(
            id = "choice-a",
            level = 20,
            laneId = "lane",
            status = HeroPathNodeStatus.AVAILABLE,
            kind = HeroPathNodeKind.MATCHUP_TACTIC,
            name = HeroPathCopy("유리 상성 강화", "Press Advantage", "有利相性強化"),
            summary = HeroPathCopy(
                "유리 판정에서 상성 보너스를 더 키웁니다.",
                "In a favorable matchup, its bonus is increased.",
                "有利判定なら、相性ボーナスをさらに強化します。",
            ),
            requirement = HeroPathCopy("습득 가능", "Ready to learn", "習得可能"),
        )

        AppLanguage.entries.forEach { language ->
            val text = heroPathNodeAccessibilityDescription(node) { it.resolve(language) }
            val ordered = listOf(
                node.name.resolve(language),
                HeroPathNodeKind.MATCHUP_TACTIC.label.resolve(language),
                node.summary.resolve(language).trimEnd('.', '。'),
                HeroPathMatchupAutomaticCopy.resolve(language).trimEnd('.', '。'),
                HeroPathMatchupExclusiveCopy.resolve(language).trimEnd('.', '。'),
                node.status.label.resolve(language),
                node.requirement!!.resolve(language),
            )
            assertTrue("${language.languageTag}: $text", ordered.zipWithNext().all { (before, after) ->
                text.indexOf(before) < text.indexOf(after)
            })
        }
    }

    @Test
    fun `matchup help copy stays short complete and language safe`() {
        AppLanguage.entries.forEach { language ->
            val automatic = HeroPathMatchupAutomaticCopy.resolve(language)
            val exclusive = HeroPathMatchupExclusiveCopy.resolve(language)

            assertFalse(automatic.contains('{'))
            assertFalse(exclusive.contains('{'))
            assertTrue(automatic.length <= 90)
            assertTrue(exclusive.length <= 55)
            if (language != AppLanguage.KOREAN) {
                assertFalse(automatic.contains(Regex("[가-힣]")))
                assertFalse(exclusive.contains(Regex("[가-힣]")))
            }
        }
    }
}
