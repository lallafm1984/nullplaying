package com.alarmquest.engine

import com.alarmquest.model.TaleKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdventureTaleCatalogTest {
    @Test
    fun `catalog contains two twelve chapter volumes and six authored repeatable expeditions`() {
        assertEquals(emptyList<String>(), AdventureTaleCatalog.validationErrors())
        assertEquals(24, AdventureTaleCatalog.mainTales.size)
        assertEquals((1..24).toList(), AdventureTaleCatalog.mainTales.map { it.chapterNumber })
        assertEquals(listOf(1, 2), AdventureTaleCatalog.mainTales.map { it.volumeNumber }.distinct())
        assertEquals(24, AdventureTaleCatalog.mainTales.map { it.title }.distinct().size)
        assertEquals("돌아오지 않은 순찰대", AdventureTaleCatalog.mainTales.first().title)
        assertEquals("국경에 다시 뜬 해", AdventureTaleCatalog.mainTales[11].title)
        assertEquals("유리 잎이 가리킨 북쪽", AdventureTaleCatalog.mainTales[12].title)
        assertEquals("유리 숲의 첫 번째 봄", AdventureTaleCatalog.mainTales.last().title)
        assertTrue(AdventureTaleCatalog.mainTales.all { it.kind == TaleKind.MAIN })
        assertTrue(AdventureTaleCatalog.mainTales.all { it.acts.size == 5 })
        assertEquals(
            listOf(1_145L, 2_849L, 4_860L, 5_000L, 5_000L, 5_000L),
            AdventureTaleCatalog.mainTales.take(6).map { tale -> tale.acts.sumOf { it.target } },
        )
        assertEquals(113_854L, AdventureTaleCatalog.mainTales.sumOf { tale ->
            tale.acts.sumOf { it.target }
        })
        assertTrue(AdventureTaleCatalog.mainTales.flatMap { it.acts }.all {
            it.target <= AdventureTaleCatalog.MAX_ACT_TARGET
        })
        assertTrue(AdventureTaleCatalog.mainTales.drop(12).flatMap { it.acts }.all {
            it.target == AdventureTaleCatalog.MAX_ACT_TARGET
        })
        assertEquals(6, AdventureTaleCatalog.epilogues.size)
        assertTrue(AdventureTaleCatalog.epilogues.all { it.kind == TaleKind.EPILOGUE })
        assertTrue(AdventureTaleCatalog.epilogues.all { it.volumeNumber == 3 })
        assertTrue(AdventureTaleCatalog.epilogues.all { it.acts.size == 5 && it.nextId == null })
        assertTrue(AdventureTaleCatalog.epilogues.all { tale ->
            tale.acts.all { it.target == AdventureTaleCatalog.REPEAT_ACT_TARGET }
        })
        val repeatActTitles = AdventureTaleCatalog.epilogues.flatMap { tale ->
            tale.acts.map { it.titleTemplate }
        }
        assertEquals(repeatActTitles.size, repeatActTitles.distinct().size)
    }

    @Test
    fun `instantiation resolves every token and snapshots authored completion text`() {
        AdventureTaleCatalog.all.forEach { definition ->
            repeat(AdventureTaleCatalog.variantCount()) { variantIndex ->
                val state = AdventureTaleCatalog.instantiate(
                    definition = definition,
                    sequence = definition.chapterNumber.coerceAtLeast(1).toLong(),
                    heroName = "해온",
                    heroLevel = 37L,
                    variant = AdventureTaleCatalog.variantAt(variantIndex),
                )
                assertEquals(
                    when {
                        definition.kind == TaleKind.EPILOGUE -> "국경 수호록"
                        definition.volumeNumber == 1 -> "잿빛 국경"
                        else -> "유리 숲의 백야"
                    },
                    state.volumeTitle,
                )
                assertEquals(5, state.acts.size)
                assertTrue(state.acts.all { it.target > 0L })
                assertTrue(state.acts.all { it.rewardExperience > 0L && it.rewardGold > 0L })
                assertTrue(state.acts.all { it.body != it.completionBody })
                assertTrue(state.acts.all { it.completionBody.isNotBlank() })
                val rendered = buildList {
                    add(state.opening)
                    add(state.ending)
                    add(state.nextHook)
                    state.acts.forEach {
                        add(it.body)
                        add(it.completionBody)
                    }
                }
                assertTrue(rendered.all { it.isNotBlank() })
                assertFalse(rendered.any { '{' in it || '}' in it })
            }
        }
    }

    @Test
    fun `finished main story rotates through the epilogue pool forever`() {
        var current = AdventureTaleCatalog.mainTales.last()
        val epilogueIds = mutableListOf<String>()
        var sequence = 24L

        repeat(AdventureTaleCatalog.epilogues.size * 2) {
            current = AdventureTaleCatalog.nextDefinition(current, sequence)
            sequence += 1L
            epilogueIds += current.id
        }

        val expected = AdventureTaleCatalog.epilogues.map { it.id } +
            AdventureTaleCatalog.epilogues.map { it.id }
        assertEquals(expected, epilogueIds)
    }

    @Test
    fun `repeat expeditions retain an endless cycle number`() {
        val firstDefinition = AdventureTaleCatalog.epilogues.first()
        val firstCycle = AdventureTaleCatalog.instantiate(
            definition = firstDefinition,
            sequence = 25L,
            heroName = "해온",
            heroLevel = 100L,
            variant = AdventureTaleCatalog.variantAt(0),
        )
        val secondCycle = AdventureTaleCatalog.instantiate(
            definition = firstDefinition,
            sequence = 31L,
            heroName = "해온",
            heroLevel = 120L,
            variant = AdventureTaleCatalog.variantAt(1),
        )

        assertEquals("제1순환 · 다시 열린 국경길", firstCycle.title)
        assertEquals("반복 원정 1편 · 국경과 라움을 잇는 공동 보급로", firstCycle.subtitle)
        assertEquals("제2순환 · 다시 열린 국경길", secondCycle.title)
        assertEquals("반복 원정 7편 · 국경과 라움을 잇는 공동 보급로", secondCycle.subtitle)
        assertEquals(1L, AdventureTaleCatalog.postgameCycle(30L))
        assertEquals(2L, AdventureTaleCatalog.postgameCycle(31L))
    }
}
