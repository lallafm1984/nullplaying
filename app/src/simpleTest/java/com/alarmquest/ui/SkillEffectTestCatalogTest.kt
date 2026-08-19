package com.alarmquest.ui

import com.alarmquest.model.HeroClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillEffectTestCatalogTest {
    @Test
    fun `effect test list contains only the twenty refined warrior skills`() {
        val definitions = warriorSignatureSkillDefinitions()

        assertEquals(20, definitions.size)
        assertEquals(WARRIOR_SIGNATURE_SKILL_IDS, definitions.map { it.catalogId })
        assertEquals(20, definitions.map { it.catalogId }.distinct().size)
        assertTrue(definitions.all { it.heroClass == HeroClass.WARRIOR })
        assertEquals(
            listOf(
                "칼날 베기", "방패 돌파", "철퇴 강타", "공성 돌격", "전투도끼 낙하",
                "광폭 연참", "맹호 참격", "성문 깨기", "불굴 진격", "성벽 양단",
                "파성 철퇴", "산맥 붕괴타", "철혈 관통", "군단 대돌파", "군왕의 일도",
                "용살 연속참", "용살 대참", "전쟁왕 대철퇴", "대륙 파쇄타", "천하대양단",
            ),
            definitions.map { it.name },
        )
    }
}
