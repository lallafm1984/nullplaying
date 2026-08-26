package com.alarmquest.engine

import com.alarmquest.model.MonsterGrade
import com.alarmquest.model.MonsterState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestMonsterCatalogTest {
    @Test
    fun `every quest owns ten normals two elites and five act bosses`() {
        val taleIds = AdventureTaleCatalog.all.map { it.id }.toSet()

        assertEquals(emptyList<String>(), QuestMonsterCatalog.validationErrors(taleIds))
        assertEquals(taleIds, QuestMonsterCatalog.groups.map { it.taleId }.toSet())
        assertEquals(taleIds.size, QuestMonsterCatalog.groups.size)
        assertEquals(6, QuestMonsterCatalog.groups.count { it.taleId.startsWith("prologue.") })
        assertEquals(12, QuestMonsterCatalog.groups.count { group ->
            group.taleId.substringAfterLast('c').toIntOrNull() in 13..24
        })
        QuestMonsterCatalog.groups.forEach { group ->
            assertEquals(10, group.normals.size)
            assertEquals(2, group.elites.size)
            assertEquals(5, group.bosses.size)
            assertTrue(group.adjectives.size >= 20)
            assertTrue((group.normals + group.elites + group.bosses).all {
                it.trophyNames.size == 16 && it.trophyNames.all(String::isNotBlank)
            })
        }
    }

    @Test
    fun `normal species accumulate four six eight ten ten across acts`() {
        assertEquals(listOf(4, 6, 8, 10, 10), (0..4).map(QuestMonsterCatalog::normalPoolSize))
    }

    @Test
    fun `postgame owns twelve guardian and six labyrinth monster groups`() {
        val guardianIds = setOf(
            "ash_border.epilogue_missing_cart",
            "ash_border.epilogue_buried_bell",
            "ash_border.epilogue_root_stair",
            "ash_border.epilogue_reversed_channel",
            "ash_border.epilogue_thirteenth_marker",
            "ash_border.epilogue_fourth_light",
            "ash_border.epilogue_sealed_arch",
            "ash_border.epilogue_returning_tracks",
            "ash_border.epilogue_empty_ledger",
            "ash_border.epilogue_ash_glass_spiral",
            "ash_border.epilogue_last_surface_camp",
            "ash_border.epilogue_star_below",
        )
        val labyrinthIds = setOf(
            "labyrinth.root_gate",
            "labyrinth.drowned_archive",
            "labyrinth.glass_cavern",
            "labyrinth.bell_forge",
            "labyrinth.ash_garden",
            "labyrinth.starless_stair",
        )

        assertEquals(guardianIds + labyrinthIds, PostgameMonsterCatalog.groups.map { it.taleId }.toSet())
        assertEquals(12, PostgameMonsterCatalog.groups.count { it.taleId.startsWith("ash_border.") })
        assertEquals(6, PostgameMonsterCatalog.groups.count { it.taleId.startsWith("labyrinth.") })

        val monsterIds = QuestMonsterCatalog.groups.flatMap { group ->
            (group.normals + group.elites + group.bosses).map { it.id }
        }
        assertEquals(monsterIds.size, monsterIds.distinct().size)
    }

    @Test
    fun `quest monster names do not reuse the unfamiliar bell term`() {
        val authoredText = QuestMonsterCatalog.groups.flatMap { group ->
            group.normals.map { it.baseName } +
                group.elites.map { it.baseName } +
                group.bosses.map { it.baseName } +
                group.adjectives
        }

        assertFalse(authoredText.any { "종혀" in it })
    }

    @Test
    fun `generated modifiers respect creature anatomy and behavior`() {
        assertFalse(MonsterModifierCompatibility.isCompatible("종이 깃털새", "거친 털의"))
        assertFalse(MonsterModifierCompatibility.isCompatible("재 속 지렁이", "노련한"))
        assertFalse(MonsterModifierCompatibility.isCompatible("수레 거미", "성벽 밖에서 포효하는"))
        assertFalse(MonsterModifierCompatibility.isCompatible("동굴 거미", "검은갈기"))
        assertFalse(MonsterModifierCompatibility.isCompatible("잿빛 슬라임", "강철발톱"))
        assertFalse(MonsterModifierCompatibility.isCompatible("황혼 밴시", "수정 껍질의"))
        assertTrue(MonsterModifierCompatibility.isCompatible("충성 사냥개", "거친 털의"))
        assertTrue(MonsterModifierCompatibility.isCompatible("뿔늑대", "검은갈기"))
        val repairedPaperBird = MonsterModifierCompatibility.repairedName(
            sourceName = "거친 털의 종이 깃털새",
            baseName = "종이 깃털새",
            candidates = listOf("소리 없는", "거친 털의", "흔적을 감춘"),
        )
        val repairedModifier = repairedPaperBird.removeSuffix("종이 깃털새").trim()
        assertTrue(MonsterModifierCompatibility.isCompatible("종이 깃털새", repairedModifier))

        SimpleContent.monsterKinds.forEach { baseName ->
            val candidates = MonsterModifierCompatibility.compatibleModifiers(
                baseName = baseName,
                candidates = SimpleContent.monsterAdjectives,
            )
            assertTrue(baseName, candidates.isNotEmpty())
            SimpleContent.monsterAdjectives.forEach { modifier ->
                val repaired = MonsterModifierCompatibility.repairedName(
                    sourceName = "$modifier $baseName",
                    baseName = baseName,
                    candidates = SimpleContent.monsterAdjectives,
                )
                val repairedModifier = repaired.removeSuffix(baseName).trim()
                assertTrue(
                    "$modifier $baseName -> $repaired",
                    MonsterModifierCompatibility.isCompatible(baseName, repairedModifier),
                )
            }
        }

        QuestMonsterCatalog.groups.forEach { group ->
            group.normals.forEach { definition ->
                assertTrue(
                    "${group.taleId}: ${definition.baseName}",
                    MonsterModifierCompatibility.compatibleModifiers(
                        baseName = definition.baseName,
                        candidates = group.adjectives,
                    ).isNotEmpty(),
                )
            }
        }
    }

    @Test
    fun `elite encounters are fixed at ceiling thirty and sixty percent and boss is last`() {
        assertEquals(MonsterGrade.NORMAL, QuestMonsterCatalog.encounterGrade(28L, 100L))
        assertEquals(MonsterGrade.ELITE, QuestMonsterCatalog.encounterGrade(29L, 100L))
        assertEquals(0, QuestMonsterCatalog.eliteIndex(29L, 100L))
        assertEquals(MonsterGrade.ELITE, QuestMonsterCatalog.encounterGrade(59L, 100L))
        assertEquals(1, QuestMonsterCatalog.eliteIndex(59L, 100L))
        assertEquals(MonsterGrade.NORMAL, QuestMonsterCatalog.encounterGrade(98L, 100L))
        assertEquals(MonsterGrade.BOSS, QuestMonsterCatalog.encounterGrade(99L, 100L))

        assertEquals(MonsterGrade.ELITE, QuestMonsterCatalog.encounterGrade(36L, 123L))
        assertEquals(MonsterGrade.ELITE, QuestMonsterCatalog.encounterGrade(73L, 123L))
        assertEquals(MonsterGrade.BOSS, QuestMonsterCatalog.encounterGrade(122L, 123L))
        assertEquals(MonsterGrade.ELITE, QuestMonsterCatalog.encounterGrade(299L, 1_000L))
        assertEquals(MonsterGrade.ELITE, QuestMonsterCatalog.encounterGrade(599L, 1_000L))
        assertEquals(MonsterGrade.BOSS, QuestMonsterCatalog.encounterGrade(999L, 1_000L))
    }

    @Test
    fun `equipment drop rates are exact by encounter grade`() {
        val engine = SimpleGameEngine()
        fun monster(grade: MonsterGrade, finalBoss: Boolean = false) = MonsterState(
            id = 1L,
            name = "test",
            level = 1L,
            maxEnergy = 100L,
            grade = grade,
            isFinalBoss = finalBoss,
        )

        assertEquals(2, engine.equipmentDropPercent(monster(MonsterGrade.NORMAL)))
        assertEquals(10, engine.equipmentDropPercent(monster(MonsterGrade.ELITE)))
        assertEquals(50, engine.equipmentDropPercent(monster(MonsterGrade.BOSS)))
        assertEquals(100, engine.equipmentDropPercent(monster(MonsterGrade.BOSS, finalBoss = true)))
    }
}
