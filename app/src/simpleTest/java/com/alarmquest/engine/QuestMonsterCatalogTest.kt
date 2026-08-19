package com.alarmquest.engine

import com.alarmquest.model.MonsterGrade
import com.alarmquest.model.MonsterState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestMonsterCatalogTest {
    @Test
    fun `every quest owns ten normals two elites and five act bosses`() {
        val taleIds = AdventureTaleCatalog.all.map { it.id }.toSet()

        assertEquals(emptyList<String>(), QuestMonsterCatalog.validationErrors(taleIds))
        assertEquals(taleIds, QuestMonsterCatalog.groups.map { it.taleId }.toSet())
        assertEquals(30, QuestMonsterCatalog.groups.size)
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
