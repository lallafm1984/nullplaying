package com.nullplaying.engine.arena

import com.nullplaying.model.HeroClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArenaSkillTreeCopyAuditTest {
    @Test
    fun `all 180 nodes render clean Korean English and Japanese copy`() {
        assertEquals(6, HeroClass.entries.size)
        assertEquals(180, ArenaSkillTreeCatalog.values.size)

        HeroClass.entries.forEach { heroClass ->
            val nodes = ArenaSkillTreeCatalog.forClass(heroClass)
            assertEquals(30, nodes.size)
            assertEquals(20, nodes.count { it.kind == ArenaSkillNodeKind.ATTACK })
            assertEquals(10, nodes.count { it.kind == ArenaSkillNodeKind.SUPPORT })

            nodes.forEach { node ->
                LANGUAGES.forEach { language ->
                    when (node.kind) {
                        ArenaSkillNodeKind.ATTACK -> {
                            val profile = requireNotNull(node.attackProfile)
                            AUDIT_RANKS.forEach { rank ->
                                assertPlayerCopy(
                                    label = "${node.id} R$rank $language attack",
                                    language = language,
                                    text = profile.compactText(language, rank, heroClass),
                                )
                            }
                            listOf(5, 10).forEach { milestoneRank ->
                                assertPlayerCopy(
                                    label = "${node.id} R$milestoneRank $language milestone",
                                    language = language,
                                    text = requireNotNull(profile.milestoneText(language, milestoneRank)),
                                )
                            }
                        }
                        ArenaSkillNodeKind.SUPPORT -> {
                            val base = requireNotNull(ArenaSupportCatalog.find(requireNotNull(node.supportId)))
                            AUDIT_RANKS.forEach { rank ->
                                assertPlayerCopy(
                                    label = "${node.id} R$rank $language support",
                                    language = language,
                                    text = ArenaSkillTreeCatalog.effectiveSupport(base, rank).summaryText(language),
                                )
                            }
                            listOf(5, 10).forEach { milestoneRank ->
                                ArenaSkillTreeCatalog.supportMilestoneText(base, language, milestoneRank)?.let { text ->
                                    assertPlayerCopy(
                                        label = "${node.id} R$milestoneRank $language milestone",
                                        language = language,
                                        text = text,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `rank dependent counts and secondary values are visible without plural errors`() {
        val cleanse = support(HeroClass.CLERIC, ArenaSupportKind.CLEANSE)
        assertEquals(
            "Remove 1 harmful status\nRemove all Poison stacks",
            effectiveSupport(cleanse, 1).summaryText("en"),
        )
        assertEquals(
            "Remove 2 harmful statuses\nRemove all Poison stacks",
            effectiveSupport(cleanse, 10).summaryText("en"),
        )

        val mirror = support(HeroClass.MAGE, ArenaSupportKind.MIRROR)
        assertEquals(
            "Create 1 image\nAttack negation chance 50%\nSpend 1 image per check",
            effectiveSupport(mirror, 1).summaryText("en"),
        )
        assertEquals(
            "Create 4 images\nAttack negation chance 20%→25%→33.33%→50%\nSpend 1 image per check",
            effectiveSupport(mirror, 10).summaryText("en"),
        )

        val statusGuard = support(HeroClass.MAGE, ArenaSupportKind.STATUS_GUARD)
        assertTrue(effectiveSupport(statusGuard, 1).summaryText("en").contains("1 new harmful status"))
        assertTrue(effectiveSupport(statusGuard, 10).summaryText("en").contains("2 new harmful statuses"))

        val sanctuary = support(HeroClass.CLERIC, ArenaSupportKind.SANCTUARY)
        assertTrue(effectiveSupport(sanctuary, 1).summaryText("en").contains("1 turn less"))
        assertTrue(effectiveSupport(sanctuary, 10).summaryText("en").contains("2 turns less"))

        val pursuit = support(HeroClass.WARRIOR, ArenaSupportKind.PURSUIT)
        assertTrue(effectiveSupport(pursuit, 1).summaryText("en").startsWith("Next basic attack deals"))
        assertTrue(effectiveSupport(pursuit, 10).summaryText("en").startsWith("Next 2 basic attacks deal"))
        assertTrue(effectiveSupport(pursuit, 10).summaryText("en").contains("Accuracy +20%"))
        assertTrue(effectiveSupport(pursuit, 10).summaryText("ko").startsWith("다음 2회의 공격 피해"))

        val condensed = support(HeroClass.MAGE, ArenaSupportKind.CONDENSE)
        assertTrue(effectiveSupport(condensed, 1).summaryText("en").startsWith("Next attack skill deals"))
        assertTrue(effectiveSupport(condensed, 10).summaryText("en").startsWith("Next 2 attack skills deal"))
        assertTrue(effectiveSupport(condensed, 10).summaryText("ko").startsWith("다음 2회의 공격 스킬 피해"))

        val poison = support(HeroClass.ROGUE, ArenaSupportKind.POISON_ACCELERATE)
        assertTrue(effectiveSupport(poison, 1).summaryText("en").contains("Poison damage +0%"))
        assertTrue(effectiveSupport(poison, 10).summaryText("en").contains("Poison damage +45%"))

        listOf(ArenaSupportKind.TAUNT, ArenaSupportKind.TRAP, ArenaSupportKind.SLEEP).forEach { kind ->
            val control = ArenaSupportCatalog.values.first { it.kind == kind }
            assertTrue("$kind R1", effectiveSupport(control, 1).summaryText("en")
                .contains("Control accuracy +0%"))
            assertTrue("$kind R10", effectiveSupport(control, 10).summaryText("en")
                .contains("Control accuracy +18%"))
        }
    }

    @Test
    fun `counter and ultimate copy states exact target trigger and safety limits`() {
        val counter = ArenaSkillTreeCatalog.attackProfile(19)
        val expectedCounterFacts = mapOf(
            HeroClass.WARRIOR to listOf("shield damage", "has no shield"),
            HeroClass.ROGUE to listOf("used a support skill", "within 2 turns"),
            HeroClass.RANGER to listOf("healed", "within 2 turns"),
            HeroClass.MAGE to listOf("damaged by an instant attack", "last turn"),
            HeroClass.CLERIC to listOf("preparation starts", "harmful status"),
            HeroClass.PALADIN to listOf("surviving shield-piercing damage", "Enemy piercing -30%"),
        )
        expectedCounterFacts.forEach { (heroClass, facts) ->
            val text = counter.effectText("en", 10, heroClass)
            facts.forEach { fact -> assertTrue("$heroClass missing '$fact': $text", text.contains(fact)) }
        }

        val ultimate = ArenaSkillTreeCatalog.attackProfile(20)
        val expected = mapOf(
            "ko" to listOf("55%", "HP 1"),
            "en" to listOf("55%", "1 HP"),
            "ja" to listOf("55%", "1"),
        )
        expected.forEach { (language, facts) ->
            val text = ultimate.effectText(language, 10, HeroClass.WARRIOR)
            facts.forEach { fact -> assertTrue("$language missing '$fact': $text", text.contains(fact)) }
            assertFalse("$language must not imply an undefined healthy state: $text",
                text.contains("healthy", ignoreCase = true) || text.contains("건강한"))
        }
    }

    @Test
    fun `attack copy names exact targets judgment shape and four-turn expiries`() {
        listOf(2, 7).forEach { slot ->
            val profile = ArenaSkillTreeCatalog.attackProfile(slot)
            assertTrue("A$slot Korean target is vague", profile.effectText("ko", 1, HeroClass.WARRIOR)
                .contains("준비 중인 상대"))
            assertTrue("A$slot English target is vague", profile.effectText("en", 1, HeroClass.WARRIOR)
                .contains("preparing target"))
            assertTrue("A$slot Japanese target is vague", profile.effectText("ja", 1, HeroClass.WARRIOR)
                .contains("準備中の相手"))
        }
        val earlyPunish = ArenaSkillTreeCatalog.attackProfile(2)
        val intercept = ArenaSkillTreeCatalog.attackProfile(7)
        assertFalse(earlyPunish.effectText("ko", 10, HeroClass.WARRIOR).contains("명중률"))
        assertTrue(intercept.effectText("ko", 1, HeroClass.WARRIOR).contains("명중률 +10%"))
        assertTrue(intercept.effectText("en", 10, HeroClass.WARRIOR)
            .contains("Accuracy +30%"))
        assertTrue(intercept.effectText("ja", 10, HeroClass.WARRIOR).contains("命中率+30%"))

        val heavy = ArenaSkillTreeCatalog.attackProfile(16)
        assertTrue(heavy.effectText("ko", 1, HeroClass.WARRIOR).contains("강력한 공격"))
        assertTrue(heavy.effectText("en", 1, HeroClass.WARRIOR).contains("powerful attack"))
        assertTrue(heavy.effectText("ja", 1, HeroClass.WARRIOR).contains("強力な攻撃"))
        assertFalse(heavy.effectText("ko", 1, HeroClass.WARRIOR).contains("연타"))
        assertFalse(heavy.effectText("en", 1, HeroClass.WARRIOR).contains("multi-hit"))
        assertFalse(heavy.effectText("ja", 1, HeroClass.WARRIOR).contains("連打"))

        listOf(5, 9, 13).forEach { slot ->
            val profile = ArenaSkillTreeCatalog.attackProfile(slot)
            assertTrue("A$slot Korean copy hides expiry", profile.effectText("ko", 1, HeroClass.WARRIOR)
                .contains("4턴"))
            assertTrue("A$slot English copy hides expiry", profile.effectText("en", 1, HeroClass.WARRIOR)
                .contains("4 turns"))
            assertTrue("A$slot Japanese copy hides expiry", profile.effectText("ja", 1, HeroClass.WARRIOR)
                .contains("4ターン"))
        }
    }

    @Test
    fun `support summaries distinguish trigger target and stat scaling`() {
        val restraint = support(HeroClass.CLERIC, ArenaSupportKind.RESTRAINT)
        assertEquals(
            "Damage taken -11%\nAttack damage dealt -12%",
            effectiveSupport(restraint, 1).summaryText("en"),
        )

        val bandage = support(HeroClass.WARRIOR, ArenaSupportKind.BANDAGE)
        val hands = support(HeroClass.PALADIN, ArenaSupportKind.LAY_HANDS)
        val lowShield = support(HeroClass.PALADIN, ArenaSupportKind.LOW_SHIELD)
        assertTrue(effectiveSupport(bandage, 10).summaryText("en").contains("scales with Constitution"))
        assertTrue(effectiveSupport(hands, 10).summaryText("en").contains("scales with Charisma"))
        assertTrue(effectiveSupport(lowShield, 10).summaryText("en").contains("scales with Charisma"))
        assertFalse(effectiveSupport(bandage, 10).summaryText("ko").contains("제곱근"))

        val suppression = ArenaSkillTreeCatalog.attackProfile(5).effectText("en", 10, HeroClass.WARRIOR)
        assertTrue(suppression.contains("2 times"))
        assertTrue(ArenaSkillTreeCatalog.attackProfile(5).effectText("ko", 9, HeroClass.WARRIOR)
            .contains("최대 1회"))
        assertTrue(ArenaSkillTreeCatalog.attackProfile(9).effectText("ko", 10, HeroClass.WARRIOR)
            .contains("2회"))
        assertTrue(ArenaSkillTreeCatalog.attackProfile(14).effectText("ko", 10, HeroClass.WARRIOR)
            .contains("준비 +2턴"))
        val healingReduction = ArenaSkillTreeCatalog.attackProfile(11).effectText("en", 10, HeroClass.WARRIOR)
        assertTrue(healingReduction.contains("Target healing"))
        val dispelOrDrain = ArenaSkillTreeCatalog.attackProfile(18).effectText("en", 10, HeroClass.WARRIOR)
        assertTrue(dispelOrDrain.contains("target buffs"))
    }

    private fun support(heroClass: HeroClass, kind: ArenaSupportKind): ArenaSupportDefinition =
        ArenaSupportCatalog.forClass(heroClass).single { it.kind == kind }

    private fun assertPlayerCopy(label: String, language: String, text: String) {
        assertTrue("$label is blank", text.isNotBlank())
        assertFalse("$label has an unresolved placeholder: $text", text.contains('{') || text.contains('}'))
        FORBIDDEN_SHORTHAND.forEach { pattern ->
            assertFalse("$label contains player-hostile shorthand ${pattern.pattern}: $text", pattern.containsMatchIn(text))
        }
        assertFalse("$label exposes a stable engine identifier: $text",
            text.contains("ARENA_") || text.contains("arena_"))
        if (language == "en") {
            assertFalse("$label exposes internal mitigation jargon: $text", text.contains("mitigation", ignoreCase = true))
            assertFalse("$label exposes internal vulnerability jargon: $text", text.contains("vulnerability", ignoreCase = true))
            assertFalse("$label uses an internal rank token: $text", Regex("\\bR\\d+\\b").containsMatchIn(text))
            assertFalse("$label has a singular count with a plural noun: $text",
                Regex("\\b1 (?:times|turns|actions|statuses|images|stacks|effects|penalties)\\b", RegexOption.IGNORE_CASE)
                    .containsMatchIn(text))
            assertFalse("$label has a plural count with a singular noun: $text",
                Regex("\\b(?:2|3|4|5|6|7|8|9|10) (?:time|turn|action|status|image|stack|effect|penalty)\\b", RegexOption.IGNORE_CASE)
                    .containsMatchIn(text))
        }
        if (language == "ko") {
            listOf("퍼센트포인트", "직격", "직접 공격", "직접 피해", "HP 타격", "HP 피격", "평타", "제곱근", "상한")
                .forEach { forbidden ->
                    assertFalse("$label contains inconsistent term '$forbidden': $text", text.contains(forbidden))
                }
            listOf(',', ';', '；').forEach { separator ->
                assertFalse("$label joins effects with '$separator' instead of a new line: $text",
                    text.contains(separator))
            }
        }
        assertFalse("$label joins separate effects on one line: $text", text.contains(" · "))
        val clauses = text.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        assertEquals("$label repeats a line: $text", clauses.size, clauses.distinct().size)
    }

    companion object {
        private val LANGUAGES = listOf("ko", "en", "ja")
        private val AUDIT_RANKS = (1..ARENA_SKILL_TREE_MAX_RANK).toList()
        private val FORBIDDEN_SHORTHAND = listOf(
            Regex("%p\\b", RegexOption.IGNORE_CASE),
            Regex("\\bpp\\b", RegexOption.IGNORE_CASE),
            Regex("\\d+t\\b"),
            Regex("\\d+P\\b"),
        )
    }
}
