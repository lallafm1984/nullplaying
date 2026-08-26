package com.alarmquest.ui

import com.alarmquest.model.AdventurePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketPresentationTest {
    @Test
    fun `no upgrade result uses a separate progress panel instead of the market card`() {
        assertTrue(usesMarketActionPanel(AdventurePhase.SELLING))
        assertTrue(usesMarketActionPanel(AdventurePhase.SHOPPING))
        assertTrue(usesMarketActionPanel(AdventurePhase.SHOPPING_RESULT))
        assertFalse(usesMarketActionPanel(AdventurePhase.SHOPPING_EMPTY))
    }

    @Test
    fun `sold and purchased item names use the same rarity color as the bag`() {
        listOf("일반", "고급", "희귀", "영웅", "전설", "신화").forEach { rarity ->
            assertEquals(
                rarityColor(rarity),
                marketActionDescriptionColor(
                    itemName = "판매 아이템",
                    rarity = rarity,
                ),
            )
            assertEquals(
                rarityColor(rarity),
                marketActionDescriptionColor(
                    itemName = "구매 아이템",
                    rarity = rarity,
                ),
            )
        }
    }

    @Test
    fun `descriptions without an item or rarity keep the normal text color`() {
        assertEquals(
            AqText,
            marketActionDescriptionColor(
                itemName = "상점 아이템",
                rarity = "",
            ),
        )
        assertEquals(
            AqText,
            marketActionDescriptionColor(
                itemName = "",
                rarity = "희귀",
            ),
        )
    }
}
