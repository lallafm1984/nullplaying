package com.nullplaying.ui

import androidx.compose.ui.graphics.Color
import com.nullplaying.model.HeroClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterCreationPresentationTest {
    @Test
    fun `creation preserves the ordered primary and secondary class stat roles`() {
        val expectedIndices = mapOf(
            HeroClass.WARRIOR to (0 to 1),
            HeroClass.ROGUE to (2 to 0),
            HeroClass.RANGER to (2 to 4),
            HeroClass.MAGE to (3 to 4),
            HeroClass.CLERIC to (4 to 5),
            HeroClass.PALADIN to (0 to 5),
        )

        expectedIndices.forEach { (heroClass, expected) ->
            assertEquals(expected.first, heroClass.primaryStatIndex)
            assertEquals(expected.second, heroClass.secondaryStatIndex)
            assertEquals(setOf(expected.first, expected.second), primaryStatIndices(heroClass))
            assertEquals(
                CharacterCreationStatRole.PRIMARY,
                characterCreationStatRole(heroClass, expected.first),
            )
            assertEquals(
                CharacterCreationStatRole.SECONDARY,
                characterCreationStatRole(heroClass, expected.second),
            )
        }
    }

    @Test
    fun `creation distinguishes primary and secondary stats by color`() {
        assertNotEquals(CharacterCreationPrimaryAccent, CharacterCreationSecondaryAccent)
        assertEquals(AqRed, CharacterCreationPrimaryAccent)
        assertEquals(Color(0xFFB69ACB), CharacterCreationSecondaryAccent)
        assertEquals(
            CharacterCreationPrimaryAccent,
            characterCreationStatAccent(CharacterCreationStatRole.PRIMARY),
        )
        assertEquals(
            CharacterCreationSecondaryAccent,
            characterCreationStatAccent(CharacterCreationStatRole.SECONDARY),
        )
        assertEquals(
            CharacterCreationStatRole.RESOURCE,
            characterCreationStatRole(HeroClass.PALADIN, 6),
        )
        assertEquals(
            CharacterCreationStatRole.STANDARD,
            characterCreationStatRole(HeroClass.PALADIN, 4),
        )
        assertEquals(
            "주 능력치",
            characterCreationStatRoleLabel(CharacterCreationStatRole.PRIMARY),
        )
        assertEquals(
            "보조 능력치",
            characterCreationStatRoleLabel(CharacterCreationStatRole.SECONDARY),
        )
        assertEquals(null, characterCreationStatRoleLabel(CharacterCreationStatRole.STANDARD))
    }

    @Test
    fun `disabled creation action keeps readable theme colors`() {
        assertEquals(Color(0xFF211808), CharacterCreationActionContent)
        assertEquals(AqSurfaceHigh, CharacterCreationDisabledActionContainer)
        assertEquals(AqMuted, CharacterCreationDisabledActionContent)
    }

    @Test
    fun `adventurer names are limited to two through eight characters`() {
        assertFalse(isValidAdventurerName(""))
        assertFalse(isValidAdventurerName("한"))
        assertTrue(isValidAdventurerName("한글"))
        assertTrue(isValidAdventurerName("여덟글자이름임요"))
        assertFalse(isValidAdventurerName(" 한 "))
        assertEquals("여덟글자이름임요", limitAdventurerNameInput("여덟글자이름임요초과"))
        assertEquals("🚀🚀🚀🚀🚀🚀🚀🚀", limitAdventurerNameInput("🚀🚀🚀🚀🚀🚀🚀🚀🚀"))
        assertEquals("루나", normalizedAdventurerName("  루나  "))
    }
}
