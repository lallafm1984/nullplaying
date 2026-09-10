package com.nullplaying.ui

import com.nullplaying.engine.arena.ArenaIdentityCopy
import com.nullplaying.engine.arena.ArenaSkillAllocation
import com.nullplaying.engine.arena.ArenaSkillNodeKind
import com.nullplaying.engine.arena.ArenaProgressionRules
import com.nullplaying.engine.arena.ArenaSkillTreeCatalog
import com.nullplaying.engine.arena.ArenaSkillTreeRules
import com.nullplaying.engine.arena.ArenaSkillTreeState
import com.nullplaying.localization.AppLanguage
import com.nullplaying.model.HeroClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArenaSkillTreeUiCopyTest {
    @Test
    fun `arena entry summarizes learned skill counts and remaining points`() {
        val empty = model(AppLanguage.KOREAN, arenaLevel = 4)
        assertEquals(
            "공격 0 · 보조 0 · 4포인트 남음",
            arenaSkillTreeEntryDetail(empty, AppLanguage.KOREAN),
        )
        assertEquals(
            "Attack 0 · Support 0 · 4 points left",
            arenaSkillTreeEntryDetail(model(AppLanguage.ENGLISH, arenaLevel = 4), AppLanguage.ENGLISH),
        )
        assertEquals(
            "Attack 0 · Support 0 · 1 point left",
            arenaSkillTreeEntryDetail(model(AppLanguage.ENGLISH, arenaLevel = 1), AppLanguage.ENGLISH),
        )
        assertEquals(
            "攻撃0・補助0・残り4ポイント",
            arenaSkillTreeEntryDetail(model(AppLanguage.JAPANESE, arenaLevel = 4), AppLanguage.JAPANESE),
        )

        val rootId = definition("A01").id
        val learned = model(
            language = AppLanguage.KOREAN,
            state = ArenaSkillTreeState(
                heroClass = HERO_CLASS,
                allocations = listOf(ArenaSkillAllocation(rootId, 1)),
            ),
            arenaLevel = 4,
        )
        assertEquals("공격 1 · 보조 0 · 3포인트 남음", arenaSkillTreeEntryDetail(learned, AppLanguage.KOREAN))
    }

    @Test
    fun `arena entry and overlay use the requested localized setup titles`() {
        assertEquals("결투장 스킬 설정", arenaSkillTreeEntryTitle(AppLanguage.KOREAN))
        assertEquals("Arena Skill Setup", arenaSkillTreeEntryTitle(AppLanguage.ENGLISH))
        assertEquals("闘技場スキル設定", arenaSkillTreeEntryTitle(AppLanguage.JAPANESE))
        assertEquals("스킬 설정", arenaSkillTreeScreenTitle(AppLanguage.KOREAN))
        assertEquals("Skill Setup", arenaSkillTreeScreenTitle(AppLanguage.ENGLISH))
        assertEquals("スキル設定", arenaSkillTreeScreenTitle(AppLanguage.JAPANESE))
    }

    @Test
    fun `arena entry uses the progression rule unlock level`() {
        val locked = model(AppLanguage.KOREAN, arenaLevel = 4).copy(unlocked = false)
        val level = ArenaProgressionRules.MIN_HERO_LEVEL

        assertEquals("Lv.$level 필요", arenaSkillTreeEntryDetail(locked, AppLanguage.KOREAN))
        assertEquals("Requires Lv.$level", arenaSkillTreeEntryDetail(locked, AppLanguage.ENGLISH))
        assertEquals("Lv.${level}で解放", arenaSkillTreeEntryDetail(locked, AppLanguage.JAPANESE))
    }

    @Test
    fun `tier two shows only a literal single or any-parent prerequisite`() {
        val korean = model(AppLanguage.KOREAN)
        val single = korean.node("A04")
        val anyParent = korean.node("S01")

        assertEquals(listOf(ArenaSkillRequirementKind.PREREQUISITE),
            single.requirementRows.map(ArenaSkillRequirementUiModel::kind))
        assertEquals("칼날 베기 3랭크", single.requirementRows.single().value)
        assertEquals(listOf(ArenaSkillRequirementKind.PREREQUISITE),
            anyParent.requirementRows.map(ArenaSkillRequirementUiModel::kind))
        assertEquals("칼날 베기·강철 베기 중 1개를 3랭크까지 올리기", anyParent.requirementRows.single().value)

        listOf(single, anyParent).forEach { node ->
            assertFalse(node.requirement.contains("중 하나"))
            assertFalse(node.requirement.contains("상위"))
            assertFalse(node.requirement.contains(Regex("\\d+P")))
        }
    }

    @Test
    fun `later tiers split prerequisite and cumulative investment into localized rows`() {
        val expected = mapOf(
            AppLanguage.KOREAN to Pair(
                "철벽 자세 3랭크",
                "이전 단계에 총 7포인트 투자",
            ),
            AppLanguage.ENGLISH to Pair(
                "Ironwall Stance at rank 3",
                "Invest 7 points total in earlier tiers",
            ),
            AppLanguage.JAPANESE to Pair(
                "鉄壁の構えをランク3まで習得",
                "前段階に合計7ポイント投資",
            ),
        )

        expected.forEach { (language, copy) ->
            val rows = model(language).node("A06").requirementRows
            assertEquals(
                listOf(ArenaSkillRequirementKind.PREREQUISITE, ArenaSkillRequirementKind.INVESTMENT),
                rows.map(ArenaSkillRequirementUiModel::kind),
            )
            assertEquals(copy.first, rows[0].value)
            assertEquals(copy.second, rows[1].value)
        }
    }

    @Test
    fun `each acquisition condition reports whether it is already satisfied`() {
        val a01 = definition("A01").id
        val a02 = definition("A02").id
        val a04 = definition("A04").id
        val state = ArenaSkillTreeState(
            heroClass = HERO_CLASS,
            allocations = listOf(
                ArenaSkillAllocation(a01, 3),
                ArenaSkillAllocation(a02, 1),
                ArenaSkillAllocation(a04, 3),
            ),
        )
        val korean = model(AppLanguage.KOREAN, state = state)

        val available = korean.node("S02")
        assertEquals(ArenaSkillTreeNodeStatus.AVAILABLE, available.status)
        assertEquals(listOf(true, true), available.requirementRows.map { it.satisfied })

        val locked = korean.node("A07")
        assertEquals(ArenaSkillTreeNodeStatus.LOCKED, locked.status)
        assertEquals(
            listOf(false, true),
            locked.requirementRows.map { it.satisfied },
        )
    }

    @Test
    fun `multiple any-parent copy names one clear choice without point shorthand`() {
        val expected = mapOf(
            AppLanguage.KOREAN to "폭풍 베기·철갑 돌진 중 1개를 3랭크까지 올리기",
            AppLanguage.ENGLISH to "Raise Storm Slash or Ironclad Charge to rank 3",
            AppLanguage.JAPANESE to "嵐の斬撃・鉄甲突進のいずれか1つをランク3まで習得",
        )
        expected.forEach { (language, text) ->
            val node = model(language).node("A09")
            assertEquals(text, node.requirementRows.first().value)
            assertEquals(ArenaSkillRequirementKind.INVESTMENT, node.requirementRows[1].kind)
            assertFalse(node.requirement.contains("중 하나"))
            assertFalse(node.requirement.contains("One of"))
            assertFalse(node.requirement.contains(Regex("\\d+P")))
        }
    }

    @Test
    fun `root hides empty prerequisite but exposes a real ownership blocker`() {
        val learnedRootId = definition("A01").id
        val learned = model(
            language = AppLanguage.KOREAN,
            state = ArenaSkillTreeState(
                heroClass = HERO_CLASS,
                allocations = listOf(ArenaSkillAllocation(learnedRootId, 1)),
            ),
        ).node("A01")
        assertTrue(learned.requirementRows.isEmpty())
        assertTrue(learned.requirement.isEmpty())

        val expected = mapOf(
            AppLanguage.KOREAN to "본게임에서 해당 공격 스킬 보유 필요",
            AppLanguage.ENGLISH to "Own the matching main-game attack skill",
            AppLanguage.JAPANESE to "本編で対応する攻撃スキルを習得",
        )
        expected.forEach { (language, text) ->
            val root = model(language, ownedAttackIds = emptySet()).node("A01")
            assertEquals(listOf(ArenaSkillRequirementKind.OWNERSHIP),
                root.requirementRows.map(ArenaSkillRequirementUiModel::kind))
            assertEquals(text, root.requirementRows.single().value)
            assertFalse(root.requirementRows.single().satisfied)
        }
    }

    @Test
    fun `ownership blocker stays visible when no points remain`() {
        val ownedRootId = definition("A01").id
        val state = ArenaSkillTreeState(
            heroClass = HERO_CLASS,
            allocations = listOf(ArenaSkillAllocation(ownedRootId, 1)),
        )
        val noPoints = model(
            language = AppLanguage.KOREAN,
            state = state,
            ownedAttackIds = setOf(ownedRootId),
            arenaLevel = 1,
        )
        val unownedRoot = noPoints.node("A02")

        assertEquals(0, noPoints.availablePoints)
        assertEquals(
            listOf(ArenaSkillRequirementKind.OWNERSHIP),
            unownedRoot.requirementRows.map(ArenaSkillRequirementUiModel::kind),
        )
        assertFalse(unownedRoot.requirementRows.single().satisfied)
    }

    @Test
    fun `point shortage stays on the action and never repeats as a detail row`() {
        val rootId = definition("A01").id
        val state = ArenaSkillTreeState(
            heroClass = HERO_CLASS,
            allocations = listOf(ArenaSkillAllocation(rootId, 1)),
        )
        val expected = mapOf(
            AppLanguage.KOREAN to "포인트 부족",
            AppLanguage.ENGLISH to "No points",
            AppLanguage.JAPANESE to "ポイント不足",
        )

        expected.forEach { (language, copy) ->
            val noPoints = model(language, state = state, arenaLevel = 1)
            listOf(noPoints.node("A01"), noPoints.node("A02")).forEach { node ->
                assertTrue(node.requirementRows.none { row ->
                    row.label in setOf("포인트", "Points", "ポイント") ||
                        row.value in setOf("사용 가능한 포인트 없음", "No points available", "使用できるポイントなし")
                })
                assertEquals(copy, arenaSkillTreeActionLabel(noPoints, node, language))
            }
        }
    }

    @Test
    fun `locked status and disabled action use a generic unmet-condition label`() {
        val expected = mapOf(
            AppLanguage.KOREAN to "조건 미충족",
            AppLanguage.ENGLISH to "Requirements not met",
            AppLanguage.JAPANESE to "条件未達",
        )
        expected.forEach { (language, text) ->
            val model = model(language)
            val locked = model.node("S01")
            assertEquals(ArenaSkillTreeNodeStatus.LOCKED, locked.status)
            assertEquals(text, arenaTreeStatusLabel(locked.status, language))
            assertEquals(text, arenaSkillTreeActionLabel(model, locked, language))
        }
    }

    @Test
    fun `a duel lock is distinct from an unmet skill requirement`() {
        AppLanguage.entries.forEach { language ->
            val lockedForDuel = model(language, editingEnabled = false)
            val root = lockedForDuel.node("A01")
            assertEquals(ArenaSkillTreeNodeStatus.UNAVAILABLE, root.status)
            assertFalse(root.canRankUp)
            assertEquals(
                when (language) {
                    AppLanguage.KOREAN -> "현재 변경 불가"
                    AppLanguage.ENGLISH -> "Changes unavailable"
                    AppLanguage.JAPANESE -> "現在変更不可"
                },
                arenaTreeStatusLabel(root.status, language),
            )
            assertEquals(
                when (language) {
                    AppLanguage.KOREAN -> "대전 중 변경 불가"
                    AppLanguage.ENGLISH -> "Unavailable during duel"
                    AppLanguage.JAPANESE -> "対戦中は変更不可"
                },
                arenaSkillTreeActionLabel(lockedForDuel, root, language),
            )

            val maxedRootId = definition("A01").id
            val maxedDuringDuel = model(
                language = language,
                state = ArenaSkillTreeState(
                    heroClass = HERO_CLASS,
                    allocations = listOf(ArenaSkillAllocation(maxedRootId, 10)),
                ),
                editingEnabled = false,
            )
            val maxedRoot = maxedDuringDuel.node("A01")
            assertEquals(ArenaSkillTreeNodeStatus.MAX, maxedRoot.status)
            assertEquals("MAX", arenaSkillTreeActionLabel(maxedDuringDuel, maxedRoot, language))
        }
    }

    @Test
    fun `rank up actions say exactly what one point does`() {
        val korean = model(AppLanguage.KOREAN)
        val root = korean.node("A01")
        assertEquals("1포인트 투자", arenaSkillTreeActionLabel(korean, root, AppLanguage.KOREAN))

        val rootId = definition("A01").id
        val adeptModel = model(
            language = AppLanguage.KOREAN,
            state = ArenaSkillTreeState(
                heroClass = HERO_CLASS,
                allocations = listOf(ArenaSkillAllocation(rootId, 4)),
            ),
        )
        assertEquals(
            "1포인트 투자 · 숙련",
            arenaSkillTreeActionLabel(adeptModel, adeptModel.node("A01"), AppLanguage.KOREAN),
        )

        val masterModel = model(
            language = AppLanguage.KOREAN,
            state = ArenaSkillTreeState(
                heroClass = HERO_CLASS,
                allocations = listOf(ArenaSkillAllocation(rootId, 9)),
            ),
        )
        assertEquals(
            "1포인트 투자 · 마스터",
            arenaSkillTreeActionLabel(masterModel, masterModel.node("A01"), AppLanguage.KOREAN),
        )
    }

    @Test
    fun `identity timings use real action turns and positive repeat cooldowns`() {
        val english=model(AppLanguage.ENGLISH)
        assertTrue(english.node("A01").timing.startsWith("1-turn action"))
        assertTrue(english.node("A01").timing.contains("Cooldown: 2 turns"))
        assertTrue(english.node("S01").timing.startsWith("1-turn action"))
        assertTrue(english.node("S01").timing.contains("Cooldown: 8 turns"))
        assertTrue(model(AppLanguage.KOREAN).node("S01").timing.startsWith("행동 1턴"))
    }

    @Test
    fun `timing previews identity MP values before investing`() {
        val state=ArenaSkillTreeState(HERO_CLASS,allocations=listOf(
            ArenaSkillAllocation(definition("A01").id,3),ArenaSkillAllocation(definition("S01").id,1)))
        val korean=model(AppLanguage.KOREAN,state=state)
        assertTrue(korean.node("A01").timing.contains("MP 4→5 ·"))
        assertTrue(korean.node("S01").timing.contains("MP 8→10 ·"))
        assertTrue(model(AppLanguage.KOREAN).node("A01").timing.contains("MP 3"))
    }

    @Test
    fun `ultimate and bandage expose their real use limits in all languages`() {
        val facts=mapOf(
            AppLanguage.KOREAN to listOf("4턴","6턴부터","전투당 1회"),
            AppLanguage.ENGLISH to listOf("4-turn action","From turn 6","Once per battle"),
            AppLanguage.JAPANESE to listOf("行動4ターン","6ターン目から","1戦につき1回"))
        facts.forEach { (language,expected) ->
            val ui=model(language)
            expected.forEach { assertTrue(ui.node("A20").timing.contains(it)) }
            val id="ARENA_SUP_FIGHTER_08"
            val bandage=ui.nodes.single { it.id==id }
            assertTrue(bandage.timing.contains("MP ${ArenaIdentityCopy.preview(id, 1).mp} ·"))
            val rankTen=model(language,state=ArenaSkillTreeState(HERO_CLASS,allocations=listOf(ArenaSkillAllocation(id,10))))
                .nodes.single { it.id==id }
            assertTrue(rankTen.timing.contains("MP ${ArenaIdentityCopy.preview(id, 10).mp} ·"))
            assertTrue(rankTen.timing.contains(expected.last()))
        }
    }

    @Test
    fun `every class owns a support atlas with ten stable visible tiles`() {
        val resources = HeroClass.entries.map(::arenaSupportAtlasResource)
        assertEquals(HeroClass.entries.size, resources.distinct().size)
        HeroClass.entries.forEach { heroClass ->
            val indices = (0 until 10).map { arenaSupportAtlasTileIndex(heroClass, it) }
            assertEquals(10, indices.distinct().size)
            assertTrue(indices.all { it in 0 until 12 })
        }
        assertEquals(
            listOf(0, 1, 5, 7, 6, 2, 3, 4, 9, 8),
            (0 until 10).map { arenaSupportAtlasTileIndex(HeroClass.WARRIOR, it) },
        )
    }

    @Test
    fun `review selector exposes all six classes in catalog order with localized labels`() {
        val reviewClasses = arenaSkillTreeReviewClasses(HeroClass.WARRIOR, HeroClass.entries.reversed())
        assertEquals(HeroClass.entries, reviewClasses)
        AppLanguage.entries.forEach { language ->
            val labels = reviewClasses.map { arenaTreeClassName(it, language) }
            assertEquals(HeroClass.entries.size, labels.size)
            assertEquals(labels.size, labels.distinct().size)
            assertTrue(labels.all(String::isNotBlank))
        }
    }

    @Test
    fun `review selector stays hidden for one class or an invalid current class`() {
        assertTrue(arenaSkillTreeReviewClasses(HeroClass.WARRIOR, listOf(HeroClass.WARRIOR)).isEmpty())
        assertTrue(
            arenaSkillTreeReviewClasses(
                HeroClass.WARRIOR,
                listOf(HeroClass.MAGE, HeroClass.CLERIC),
            ).isEmpty(),
        )
    }

    @Test
    fun `ultimate copy no longer advertises the retired HP one rule`() {
        for(language in AppLanguage.entries) {
            val ultimate=model(language).node("A20")
            assertTrue(ultimate.nextEffect.contains("580%"))
            assertFalse(ultimate.nextEffect.contains("HP 1"))
            assertFalse(ultimate.nextEffect.contains("1 HP"))
            assertFalse(ultimate.nextEffect.contains("HP1"))
        }
    }

    @Test
    fun `learned non-root nodes hide first-acquisition prerequisite and spend rows`() {
        val rootId = definition("A01").id
        val learnedId = definition("A04").id
        val learned = model(
            language = AppLanguage.KOREAN,
            state = ArenaSkillTreeState(
                heroClass = HERO_CLASS,
                allocations = listOf(
                    ArenaSkillAllocation(rootId, 3),
                    ArenaSkillAllocation(learnedId, 1),
                ),
            ),
        ).node("A04")

        assertEquals(ArenaSkillTreeNodeStatus.INVESTED, learned.status)
        assertTrue(learned.requirementRows.none {
            it.kind == ArenaSkillRequirementKind.PREREQUISITE ||
                it.kind == ArenaSkillRequirementKind.INVESTMENT
        })
        assertTrue(learned.requirement.isBlank())
    }

    private fun model(
        language: AppLanguage,
        heroClass: HeroClass = HERO_CLASS,
        state: ArenaSkillTreeState = ArenaSkillTreeState(heroClass),
        ownedAttackIds: Set<String> = allAttackIds(heroClass),
        editingEnabled: Boolean = true,
        arenaLevel: Int = 100,
    ): ArenaSkillTreeUiModel {
        val view = ArenaSkillTreeRules.view(
            state = state,
            arenaLevel = arenaLevel,
            ownedAttackIds = ownedAttackIds,
        )
        return arenaSkillTreeUiModel(
            treeView = view,
            unlocked = true,
            editingEnabled = editingEnabled,
            language = language,
        )
    }

    private fun ArenaSkillTreeUiModel.node(slotKey: String): ArenaSkillTreeNodeUiModel =
        nodes.single { it.id == definition(slotKey).id }

    private fun definition(slotKey: String) = ArenaSkillTreeCatalog.forClass(HERO_CLASS)
        .single { it.slotKey == slotKey }

    private companion object {
        val HERO_CLASS = HeroClass.WARRIOR
        fun allAttackIds(heroClass: HeroClass): Set<String> = ArenaSkillTreeCatalog.forClass(heroClass)
            .filter { it.kind == ArenaSkillNodeKind.ATTACK }
            .mapTo(linkedSetOf()) { it.id }
    }
}
