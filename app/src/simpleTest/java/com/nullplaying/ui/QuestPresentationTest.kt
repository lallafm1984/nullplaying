package com.nullplaying.ui

import com.nullplaying.model.CompletedTaleRecord
import com.nullplaying.model.TaleKind
import org.junit.Assert.assertEquals
import org.junit.Test

class QuestPresentationTest {
    @Test
    fun `past tales are displayed from the first story to the latest completion`() {
        val history = listOf(
            completedTale(sequence = 31L, kind = TaleKind.EPILOGUE, chapter = 1),
            completedTale(sequence = 2L, kind = TaleKind.MAIN, chapter = 2),
            completedTale(sequence = 25L, kind = TaleKind.EPILOGUE, chapter = 1),
            completedTale(sequence = 1L, kind = TaleKind.MAIN, chapter = 1),
        )

        val displayed = completedTalesInReadingOrder(history)

        assertEquals(listOf(1L, 2L, 25L, 31L), displayed.map { it.taleSequence })
        assertEquals(listOf(1, 2, 1, 1), displayed.map { it.chapterNumber })
    }

    @Test
    fun `authored story keeps its volume labels while labyrinth is a separate system`() {
        assertEquals(
            "프롤로그 · 첫 발걸음",
            taleVolumeLabel(TaleKind.PROLOGUE, 0, "첫 발걸음"),
        )
        assertEquals(
            "제2권 · 유리 숲의 백야",
            taleVolumeLabel(TaleKind.MAIN, 2, "유리 숲의 백야"),
        )
        assertEquals(
            "제3권 · 국경 수호록",
            taleVolumeLabel(TaleKind.EPILOGUE, 3, "국경 수호록"),
        )
        assertEquals(
            "표층 미궁",
            taleVolumeLabel(TaleKind.LABYRINTH, 4, "무한 미궁", heroLevel = 99L),
        )
        assertEquals(
            "심층 미궁",
            taleVolumeLabel(TaleKind.LABYRINTH, 4, "무한 미궁", heroLevel = 100L),
        )
    }

    @Test
    fun `authored story keeps chapter labels while labyrinth uses its depth`() {
        assertEquals(
            "직업 이야기  재의 경계선",
            taleChapterLabel(TaleKind.PROLOGUE, 1, "재의 경계선"),
        )
        assertEquals(
            "제24장  국경에 다시 뜨는 해",
            taleChapterLabel(TaleKind.MAIN, 24, "국경에 다시 뜨는 해"),
        )
        assertEquals(
            "제18장  미궁의 문턱",
            taleChapterLabel(TaleKind.EPILOGUE, 18, "미궁의 문턱"),
        )
        assertEquals(
            "제37구역 · 깊은 재의 회랑",
            taleChapterLabel(
                TaleKind.LABYRINTH,
                chapterNumber = 1,
                chapterTitle = "제37구역 · 깊은 재의 회랑",
                labyrinthDepth = 37L,
            ),
        )
        assertEquals(
            "제38구역 · 깊은 재의 회랑",
            taleChapterLabel(
                TaleKind.LABYRINTH,
                chapterNumber = 1,
                chapterTitle = "제37구역 · 깊은 재의 회랑",
                labyrinthDepth = 38L,
            ),
        )
    }

    @Test
    fun `active labyrinth subtitle follows the current level without a stale surface label`() {
        assertEquals(
            "심층 원정 · 끝문을 찾는 계단",
            taleSubtitleLabel(
                TaleKind.LABYRINTH,
                subtitle = "표층 원정 · 끝문을 찾는 계단",
                heroLevel = 100L,
            ),
        )
        assertEquals(
            "표층 원정 · 끝문을 찾는 계단",
            taleSubtitleLabel(
                TaleKind.LABYRINTH,
                subtitle = "심층 원정 · 끝문을 찾는 계단",
                heroLevel = 99L,
            ),
        )
        assertEquals(
            "심층 관문 · 끝문을 찾는 계단",
            taleSubtitleLabel(
                TaleKind.LABYRINTH,
                subtitle = "표층 관문 · 끝문을 찾는 계단",
                heroLevel = 100L,
            ),
        )
        assertEquals(
            "국경을 지키는 마지막 기록",
            taleSubtitleLabel(
                TaleKind.EPILOGUE,
                subtitle = "국경을 지키는 마지막 기록",
                heroLevel = 100L,
            ),
        )
    }

    @Test
    fun `labyrinth completion location changes from surface to deep at level one hundred`() {
        val prologue = completedTale(
            sequence = 0L,
            kind = TaleKind.PROLOGUE,
            chapter = 1,
        )
        val main = completedTale(
            sequence = 24L,
            kind = TaleKind.MAIN,
            chapter = 24,
        )
        val epilogue = completedTale(
            sequence = 42L,
            kind = TaleKind.EPILOGUE,
            chapter = 18,
        )
        val surface = completedTale(
            sequence = 43L,
            kind = TaleKind.LABYRINTH,
            chapter = 1,
            completedAtLevel = 99L,
            labyrinthDepth = 7L,
            title = "제7구역 · 깊은 재의 회랑",
        )
        val deep = completedTale(
            sequence = 44L,
            kind = TaleKind.LABYRINTH,
            chapter = 1,
            completedAtLevel = 100L,
            labyrinthDepth = 8L,
        )

        assertEquals("직업 프롤로그", completedTaleLocationLabel(prologue))
        assertEquals("제1권 제24장", completedTaleLocationLabel(main))
        assertEquals("제3권 제18장", completedTaleLocationLabel(epilogue))
        assertEquals("표층 미궁 · 제7구역", completedTaleLocationLabel(surface))
        assertEquals("심층 미궁 · 제8구역", completedTaleLocationLabel(deep))
        assertEquals("깊은 재의 회랑", completedTaleTitleLabel(surface))
        assertEquals("모험담 24", completedTaleTitleLabel(main))
    }

    @Test
    fun `labyrinth accessibility description includes current and highest completed depth`() {
        assertEquals(
            "표층 미궁, 현재 깊이 제1구역, 최고 완주 깊이 기록 없음, " +
                "칭호 · 아직 없음, 다음 관문 · 제10구역, 위협 1단계 · 기본 보상",
            labyrinthDepthContentDescription(
                heroLevel = 99L,
                currentDepth = 0L,
                highestCompletedDepth = 0L,
            ),
        )
        assertEquals(
            "심층 미궁, 현재 깊이 제42구역, 최고 완주 깊이 제41구역, " +
                "칭호 · 제4관문 정복자, 다음 관문 · 제50구역, 위협 5단계 · 보상 +4%",
            labyrinthDepthContentDescription(
                heroLevel = 100L,
                currentDepth = 42L,
                highestCompletedDepth = 41L,
            ),
        )
    }

    @Test
    fun `gate records and progression labels expose boss reward and title milestones`() {
        val gate = completedTale(
            sequence = 52L,
            kind = TaleKind.LABYRINTH,
            chapter = 10,
            completedAtLevel = 60L,
            labyrinthDepth = 10L,
            title = "제10구역 · 제1관문 · 종 없는 주조장",
        )

        assertEquals("관문 돌파", completedTaleStatusLabel(gate))
        assertEquals("표층 미궁 · 제10구역 · 제1관문 기록", completedTaleLocationLabel(gate))
        assertEquals("제1관문 · 종 없는 주조장", completedTaleTitleLabel(gate))
        assertEquals("칭호 · 제4관문 정복자", labyrinthTitleLabel(49L))
        assertEquals("다음 관문 · 제50구역", labyrinthNextGateLabel(49L))
        assertEquals("제5관문 보스 자동 출현 · 보상 +19%", labyrinthDepthRuleLabel(50L))
    }

    private fun completedTale(
        sequence: Long,
        kind: TaleKind,
        chapter: Int,
        completedAtLevel: Long = sequence,
        labyrinthDepth: Long = 0L,
        title: String = "모험담 $sequence",
    ) = CompletedTaleRecord(
        taleSequence = sequence,
        taleId = "tale-$sequence",
        kind = kind,
        volumeNumber = if (kind == TaleKind.MAIN) 1 else 3,
        chapterNumber = chapter,
        title = title,
        summary = "완결 $sequence",
        nextHook = "",
        actMemories = emptyList(),
        completedAtLevel = completedAtLevel,
        labyrinthDepth = labyrinthDepth,
    )
}
