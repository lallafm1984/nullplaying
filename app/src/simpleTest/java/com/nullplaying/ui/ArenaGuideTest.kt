package com.nullplaying.ui

import com.nullplaying.localization.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArenaGuideTest {
    @Test
    fun `Korean guide explains the arena in five short points`() {
        val content = arenaGuideContent(AppLanguage.KOREAN)

        assertEquals("결투장 안내", content.title)
        assertEquals("닫기", content.close)
        assertEquals(
            listOf(
                "결투장은 다른 플레이어의 캐릭터와 자동으로 결투하는 곳입니다.",
                "캐릭터의 레벨과 능력치를 사용합니다.",
                "캐릭터 10레벨에 스킬 포인트 10개를 받고, 이후 레벨마다 1개씩 늘어납니다. 최대 100개입니다.",
                "결투장 스킬 설정에서 포인트를 배분해 스킬 성능을 강화합니다.",
                "전투에는 출전권 1장을 사용하며, 결과는 시즌 점수와 랭킹에 반영됩니다.",
            ),
            content.lines,
        )
    }

    @Test
    fun `every supported language has complete concise guide copy`() {
        AppLanguage.entries.forEach { language ->
            val content = arenaGuideContent(language)
            assertTrue(content.title.isNotBlank())
            assertTrue(content.close.isNotBlank())
            assertEquals(5, content.lines.size)
            assertTrue(content.lines.all(String::isNotBlank))
        }
    }
}
