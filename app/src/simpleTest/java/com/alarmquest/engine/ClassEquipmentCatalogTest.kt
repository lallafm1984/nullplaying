package com.alarmquest.engine

import com.alarmquest.model.EquipmentSlot
import com.alarmquest.model.HeroClass
import com.alarmquest.model.HeroStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassEquipmentCatalogTest {
    @Test
    fun `every class and slot has six distinct base variants`() {
        HeroClass.entries.forEach { heroClass ->
            EquipmentSlot.entries.forEach { slot ->
                val bases = SimpleContent.equipmentBases(slot, 1L, heroClass)

                assertEquals(6, bases.size)
                assertEquals(6, bases.toSet().size)
            }
        }
    }

    @Test
    fun `equipment progression names change only at three level boundaries`() {
        HeroClass.entries.forEach { heroClass ->
            EquipmentSlot.entries.forEach { slot ->
                assertEquals(
                    SimpleContent.equipmentBases(slot, 1L, heroClass),
                    SimpleContent.equipmentBases(slot, 3L, heroClass),
                )
                assertNotEquals(
                    SimpleContent.equipmentBases(slot, 3L, heroClass),
                    SimpleContent.equipmentBases(slot, 4L, heroClass),
                )
                assertEquals(
                    SimpleContent.equipmentBases(slot, 4L, heroClass),
                    SimpleContent.equipmentBases(slot, 6L, heroClass),
                )
                assertNotEquals(
                    SimpleContent.equipmentBases(slot, 6L, heroClass),
                    SimpleContent.equipmentBases(slot, 7L, heroClass),
                )
            }
        }

        assertEquals(1L, ClassEquipmentCatalog.bandStart(1L))
        assertEquals(1L, ClassEquipmentCatalog.bandStart(3L))
        assertEquals(4L, ClassEquipmentCatalog.bandStart(4L))
        assertEquals(100L, ClassEquipmentCatalog.bandStart(102L))
        assertEquals(103L, ClassEquipmentCatalog.bandStart(103L))
        assertTrue(
            SimpleContent.equipmentBase(
                EquipmentSlot.WEAPON,
                103L,
                HeroClass.WARRIOR,
                0,
            ).startsWith("초월 1단식 "),
        )
    }

    @Test
    fun `levels one through one hundred expose two hundred four bases per class and slot`() {
        HeroClass.entries.forEach { heroClass ->
            EquipmentSlot.entries.forEach { slot ->
                val bases = (1L..100L).flatMap { level ->
                    SimpleContent.equipmentBases(slot, level, heroClass)
                }.toSet()

                assertEquals(204, bases.size)
            }
        }
    }

    @Test
    fun `same level equipment archetypes remain distinct across classes`() {
        EquipmentSlot.entries.forEach { slot ->
            val allClassBases = HeroClass.entries.flatMap { heroClass ->
                SimpleContent.equipmentBases(slot, 1L, heroClass)
            }

            assertEquals(36, allClassBases.toSet().size)
        }
    }

    @Test
    fun `new characters begin with their own class equipment names`() {
        val engine = SimpleGameEngine()
        val stats = HeroStats(10L, 10L, 10L, 10L, 10L, 10L, 10L, 10L)

        HeroClass.entries.forEach { heroClass ->
            val game = engine.newGame(
                name = "테스터",
                heroClass = heroClass,
                rolledStats = stats.copy(),
                seed = 123L,
                now = 0L,
            )

            EquipmentSlot.entries.forEach { slot ->
                assertEquals(
                    SimpleContent.equipmentBase(slot, 1L, heroClass, 0),
                    game.equipment.first { it.slot == slot }.name,
                )
            }
        }
    }

    @Test
    fun `class labels and class equipment use the approved terminology`() {
        assertEquals(
            listOf("파이터", "시프", "레인져", "메이지", "클래릭", "팔라딘"),
            HeroClass.entries.map { it.labelKo },
        )
        assertTrue(SimpleContent.equipmentBases(EquipmentSlot.HEAD, 1L, HeroClass.WARRIOR).first().contains("파이터"))
        assertTrue(SimpleContent.equipmentBases(EquipmentSlot.HEAD, 1L, HeroClass.ROGUE).first().contains("시프"))
        assertTrue(SimpleContent.equipmentBases(EquipmentSlot.HEAD, 1L, HeroClass.RANGER).first().contains("레인져"))
        assertTrue(SimpleContent.equipmentBases(EquipmentSlot.HEAD, 1L, HeroClass.MAGE).first().contains("메이지"))
        assertTrue(SimpleContent.equipmentBases(EquipmentSlot.HEAD, 1L, HeroClass.CLERIC)[1].contains("클래릭"))
        assertTrue(SimpleContent.equipmentBases(EquipmentSlot.HEAD, 1L, HeroClass.PALADIN).first().contains("팔라딘"))
    }

    @Test
    fun `legacy class equipment terms modernize without touching narrative words`() {
        assertEquals("훈련식 파이터 투구", ClassEquipmentCatalog.modernizeName("훈련식 전사 투구"))
        assertEquals("견습식 시프 가죽옷 +1", ClassEquipmentCatalog.modernizeName("견습식 도적 가죽옷 +1"))
        assertEquals("모험식 레인져 창", ClassEquipmentCatalog.modernizeName("모험식 순찰자 창"))
        assertEquals("철제 메이지 로브", ClassEquipmentCatalog.modernizeName("철제 마도사 로브"))
        assertEquals("강철 클래릭 장화", ClassEquipmentCatalog.modernizeName("강철 성직 장화"))
        assertEquals("왕실제 팔라딘 갑옷", ClassEquipmentCatalog.modernizeName("왕실제 성기사 갑옷"))
        assertEquals("유리전사 들쥐", ClassEquipmentCatalog.modernizeName("유리전사 들쥐"))
        assertEquals("전사자의 이름", ClassEquipmentCatalog.modernizeName("전사자의 이름"))
    }
}
