package com.nullplaying.ui

import com.nullplaying.engine.SkillCatalog
import com.nullplaying.engine.arena.*
import com.nullplaying.localization.AppLanguage
import com.nullplaying.model.HeroClass
import org.junit.Assert.*
import org.junit.Test

class ArenaSkillResetPresentationTest {
    @Test fun `selected reset labels include returned points in all supported languages`() {
        assertEquals("이 스킬 초기화 · 3포인트 반환", arenaSkillResetLabel(3, AppLanguage.KOREAN))
        assertEquals("Reset this skill · Refund 1 point", arenaSkillResetLabel(1, AppLanguage.ENGLISH))
        assertEquals("Reset this skill · Refund 3 points", arenaSkillResetLabel(3, AppLanguage.ENGLISH))
        assertEquals("このスキルをリセット · 3ポイント返還", arenaSkillResetLabel(3, AppLanguage.JAPANESE))
    }

    @Test fun `node UI exposes prerequisite refusal and never enables refunds during a battle`() {
        val heroClass = HeroClass.RANGER
        val child = ArenaSkillTreeCatalog.forClass(heroClass).first { it.row == 1 }
        val parent = child.parentAnyOf.first()
        val state = ArenaSkillTreeState(heroClass, allocations = listOf(
            ArenaSkillAllocation(parent, 3), ArenaSkillAllocation(child.id, 1)))
        val owned = SkillCatalog.forClass(heroClass).mapTo(linkedSetOf()) { it.catalogId }
        val view = ArenaSkillTreeRules.view(state, 20, owned)
        for (language in AppLanguage.entries) {
            val model = arenaSkillTreeUiModel(view, true, true, language)
            val blocked = model.nodes.first { it.id == parent }
            assertFalse(blocked.canReset)
            assertTrue(blocked.resetBlockedMessage.isNotBlank())
            assertTrue(model.nodes.first { it.id == child.id }.canReset)
            val playing = arenaSkillTreeUiModel(view, true, false, language)
            assertTrue(playing.nodes.none { it.canReset })
        }
    }
}
