package com.nullplaying.engine

import com.nullplaying.model.HeroClass
import com.nullplaying.model.TaleKind
import com.nullplaying.model.TaleVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdventureTaleCatalogTest {
    @Test
    fun `dynamic hero and monster names receive the correct Korean particle`() {
        fun render(
            definition: AdventureTaleDefinition,
            heroName: String,
            enemyName: String,
        ): String {
            val tale = AdventureTaleCatalog.instantiate(
                definition = definition,
                sequence = 1L,
                heroName = heroName,
                heroLevel = 53L,
                variant = TaleVariant(enemyName),
            )
            return buildList {
                add(tale.opening)
                add(tale.ending)
                add(tale.nextHook)
                tale.acts.forEach {
                    add(it.body)
                    add(it.completionBody)
                }
            }.joinToString("\n")
        }

        val topicAndEnemySubject = AdventureTaleCatalog.epilogues.first()
        assertTrue(render(topicAndEnemySubject, "하나", "박쥐").contains("하나는"))
        assertTrue(render(topicAndEnemySubject, "하나", "박쥐").contains("박쥐가"))
        assertTrue(render(topicAndEnemySubject, "해온", "이끼골렘").contains("해온은"))
        assertTrue(render(topicAndEnemySubject, "해온", "이끼골렘").contains("이끼골렘이"))

        val enemyObject = AdventureTaleCatalog.epilogues[1]
        assertTrue(render(enemyObject, "하나", "박쥐").contains("박쥐를"))
        assertTrue(render(enemyObject, "해온", "이끼골렘").contains("이끼골렘을"))

        val heroSubject = StarterPrologueCatalog.forClass(HeroClass.ROGUE)
        assertTrue(render(heroSubject, "하나", "박쥐").contains("하나가"))
        assertTrue(render(heroSubject, "해온", "박쥐").contains("해온이"))

        val heroWith = AdventureTaleCatalog.mainTales.first { definition ->
            definition.endingTemplate.contains("{heroWith}")
        }
        assertTrue(render(heroWith, "하나", "박쥐").contains("하나와"))
        assertTrue(render(heroWith, "해온", "박쥐").contains("해온과"))
    }

    @Test
    fun `catalog contains two twelve chapter volumes eighteen epilogues and six labyrinth templates`() {
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
            listOf(1_250L, 3_000L, 5_000L, 5_100L, 5_200L, 5_300L),
            AdventureTaleCatalog.mainTales.take(6).map { tale -> tale.acts.sumOf { it.target } },
        )
        assertEquals(7_100L, AdventureTaleCatalog.mainTales.last().acts.sumOf { it.target })
        assertEquals(137_350L, AdventureTaleCatalog.mainTales.sumOf { tale ->
            tale.acts.sumOf { it.target }
        })
        AdventureTaleCatalog.mainTales.forEach { tale ->
            val targets = tale.acts.map { it.target }
            val rhythmUnit = targets.first() / AdventureTaleCatalog.MAIN_ACT_RHYTHM_WEIGHTS.first()
            assertEquals(
                AdventureTaleCatalog.MAIN_ACT_RHYTHM_WEIGHTS.map { it * rhythmUnit },
                targets,
            )
            assertEquals(
                AdventureTaleCatalog.mainChapterTotalTarget(tale.chapterNumber),
                targets.sum(),
            )
            assertTrue(targets[0] < targets[3])
            assertTrue(targets[3] < targets[1])
            assertTrue(targets[1] < targets[2])
            assertTrue(targets[2] < targets[4])
        }
        assertEquals(
            1,
            AdventureTaleCatalog.mainTales.flatMap { it.acts }.count { it.target == 1_000L },
        )
        assertEquals(18, AdventureTaleCatalog.epilogues.size)
        assertEquals(
            listOf(
                "다시 열린 국경길",
                "한 번 늦게 울린 종",
                "유리 잎이 가리킨 곳",
                "물길 아래 남은 재",
                "이름을 되찾은 묘표",
                "북쪽에서 온 세 불빛",
            ),
            AdventureTaleCatalog.epilogues.take(6).map { it.title },
        )
        assertEquals(18, AdventureTaleCatalog.epilogues.map { it.title }.distinct().size)
        assertTrue(AdventureTaleCatalog.epilogues.all { it.kind == TaleKind.EPILOGUE })
        assertTrue(AdventureTaleCatalog.epilogues.all { it.volumeNumber == 3 })
        assertTrue(AdventureTaleCatalog.epilogues.all { it.acts.size == 5 })
        assertTrue(AdventureTaleCatalog.epilogues.all { tale ->
            tale.acts.all { it.target == AdventureTaleCatalog.REPEAT_ACT_TARGET }
        })
        val epilogueActTitles = AdventureTaleCatalog.epilogues.flatMap { tale ->
            tale.acts.map { it.titleTemplate }
        }
        assertEquals(epilogueActTitles.size, epilogueActTitles.distinct().size)
        assertEquals(6, AdventureTaleCatalog.labyrinths.size)
        assertTrue(AdventureTaleCatalog.labyrinths.all { it.kind == TaleKind.LABYRINTH })
        assertTrue(AdventureTaleCatalog.labyrinths.all { it.acts.size == 5 })
        val firstDepth = AdventureTaleCatalog.instantiate(
            definition = AdventureTaleCatalog.labyrinths.first(),
            sequence = 43L,
            heroName = "해온",
            heroLevel = 53L,
            variant = AdventureTaleCatalog.variantAt(0),
            labyrinthDepth = 1L,
        )
        assertEquals(listOf(800L, 950L, 1_100L, 900L, 1_250L), firstDepth.acts.map { it.target })
        assertEquals(5_000L, firstDepth.acts.sumOf { it.target })
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
                        definition.kind == TaleKind.PROLOGUE -> "첫 발걸음"
                        definition.kind == TaleKind.EPILOGUE -> "국경 수호록"
                        definition.kind == TaleKind.LABYRINTH -> "표층 미궁"
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
                    addAll(state.openingSlides)
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
    fun `each class owns a playable five act prologue leading to chapter one`() {
        assertEquals(HeroClass.entries.size, AdventureTaleCatalog.prologues.size)
        assertTrue(AdventureTaleCatalog.prologues.all { it.kind == TaleKind.PROLOGUE })
        assertEquals(
            HeroClass.entries.map(StarterPrologueCatalog::idFor).toSet(),
            AdventureTaleCatalog.prologues.map { it.id }.toSet(),
        )
        assertEquals(
            setOf(listOf(7L, 8L, 9L, 10L, 12L)),
            AdventureTaleCatalog.prologues.map { tale -> tale.acts.map { it.target } }.toSet(),
        )
        assertTrue(AdventureTaleCatalog.prologues.all { it.nextId == AdventureTaleCatalog.firstMain.id })
        assertEquals(6, AdventureTaleCatalog.prologues.map { it.title }.distinct().size)
        assertTrue(AdventureTaleCatalog.prologues.all { it.openingSlideTemplates.size == 3 })
        assertTrue(AdventureTaleCatalog.prologues.flatMap { it.openingSlideTemplates }.all {
            it.isNotBlank() && it.length <= 50
        })
    }

    @Test
    fun `the hidden chapel path opens by ringing the first dawn bell`() {
        val chapel = AdventureTaleCatalog.mainTales[3]
        val openingAct = chapel.acts[1]
        val authoredText = AdventureTaleCatalog.mainTales.flatMap { tale ->
            listOf(
                tale.title,
                tale.subtitle,
                tale.openingTemplate,
                tale.endingTemplate,
                tale.nextHookTemplate,
            ) + tale.acts.flatMap { act ->
                listOf(act.titleTemplate, act.bodyTemplate, act.completionTemplate)
            }
        }

        assertEquals("종소리가 여는 길", openingAct.titleTemplate)
        assertTrue(openingAct.bodyTemplate.contains("첫 번째 새벽종을 울리자"))
        assertFalse(authoredText.any { "종혀" in it })
        assertFalse(authoredText.any { "홈에 대자" in it })
    }

    @Test
    fun `main ending enters every authored epilogue once then opens the labyrinth`() {
        var current = AdventureTaleCatalog.mainTales.last()
        val epilogueIds = mutableListOf<String>()
        var sequence = 24L

        repeat(AdventureTaleCatalog.epilogues.size) {
            current = AdventureTaleCatalog.nextDefinition(current, sequence)
            sequence += 1L
            epilogueIds += current.id
        }

        assertEquals(AdventureTaleCatalog.epilogues.map { it.id }, epilogueIds)
        assertEquals(42L, sequence)

        val firstLabyrinth = AdventureTaleCatalog.nextDefinition(current, sequence)
        val state = AdventureTaleCatalog.instantiate(
            definition = firstLabyrinth,
            sequence = 43L,
            heroName = "해온",
            heroLevel = 53L,
            variant = AdventureTaleCatalog.variantAt(0),
            labyrinthDepth = 1L,
        )

        assertEquals(AdventureTaleCatalog.labyrinths.first().id, firstLabyrinth.id)
        assertEquals(TaleKind.LABYRINTH, state.kind)
        assertEquals(1L, state.labyrinthDepth)
    }

    @Test
    fun `authored epilogue headings never present themselves as cycles or repeat expeditions`() {
        AdventureTaleCatalog.epilogues.forEachIndexed { index, definition ->
            val state = AdventureTaleCatalog.instantiate(
                definition = definition,
                sequence = 25L + index,
                heroName = "해온",
                heroLevel = 40L + index,
                variant = AdventureTaleCatalog.variantAt(index),
            )
            val headings = listOf(definition.title, definition.subtitle, state.title, state.subtitle)

            assertEquals(definition.title, state.title)
            assertEquals(definition.subtitle, state.subtitle)
            assertFalse(
                "${definition.id}: $headings",
                headings.any { "순환" in it || "반복 원정" in it },
            )
        }
    }

    @Test
    fun `six labyrinth templates rotate forever while active depth increases from one`() {
        var current = AdventureTaleCatalog.epilogues.last()
        var sequence = 42L
        val ids = mutableListOf<String>()
        val depths = mutableListOf<Long>()

        repeat(AdventureTaleCatalog.labyrinths.size * 2) { index ->
            current = AdventureTaleCatalog.nextDefinition(current, sequence)
            sequence += 1L
            ids += current.id
            depths += AdventureTaleCatalog.instantiate(
                definition = current,
                sequence = sequence,
                heroName = "해온",
                heroLevel = 53L + index,
                variant = AdventureTaleCatalog.variantAt(index),
                labyrinthDepth = index + 1L,
            ).labyrinthDepth
        }

        assertEquals(
            AdventureTaleCatalog.labyrinths.map { it.id } +
                AdventureTaleCatalog.labyrinths.map { it.id },
            ids,
        )
        assertEquals((1L..12L).toList(), depths)
    }
}
