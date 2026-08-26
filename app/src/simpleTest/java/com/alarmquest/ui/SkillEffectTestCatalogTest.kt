package com.alarmquest.ui

import com.alarmquest.model.HeroClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillEffectTestCatalogTest {
    @Test
    fun `warrior effect test list preserves the twenty refined skills`() {
        val definitions = warriorSignatureSkillDefinitions()

        assertEquals(20, definitions.size)
        assertEquals(WARRIOR_SIGNATURE_SKILL_IDS, definitions.map { it.catalogId })
        assertEquals(20, definitions.map { it.catalogId }.distinct().size)
        assertTrue(definitions.all { it.heroClass == HeroClass.WARRIOR })
        assertEquals(
            listOf(
                "칼날 베기", "강철 베기", "파쇄격", "대지 가르기", "십자 참격",
                "폭풍 베기", "철갑 돌진", "전장의 돌격", "회오리 참격", "대지 분쇄",
                "폭풍검", "섬광 일섬", "무영 연참", "용살검", "멸천 일섬", "무극일섬",
                "파멸의 검", "천지 가르기", "천하대양단", "최후의 일격",
            ),
            definitions.map { it.name },
        )
    }

    @Test
    fun `effect test exposes twenty signature skills for every hero class`() {
        HeroClass.entries.forEach { heroClass ->
            val definitions = signatureSkillDefinitions(heroClass)

            assertEquals("$heroClass skill count", 20, definitions.size)
            assertEquals("$heroClass unique IDs", 20, definitions.map { it.catalogId }.distinct().size)
            assertTrue("$heroClass ownership", definitions.all { it.heroClass == heroClass })
            assertEquals(
                "$heroClass unlock levels",
                listOf(1) + (5..95 step 5),
                definitions.map { it.unlockLevel },
            )
        }
    }
}
