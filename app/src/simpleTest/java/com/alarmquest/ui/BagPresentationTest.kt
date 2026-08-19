package com.alarmquest.ui

import com.alarmquest.model.EquipmentSlot
import com.alarmquest.model.InventoryItem
import com.alarmquest.model.ShopEquipmentOffer
import org.junit.Assert.assertEquals
import org.junit.Test

class BagPresentationTest {
    @Test
    fun `bag items are displayed two per row and keep their order`() {
        val items = (1L..5L).map { id ->
            InventoryItem(
                id = id,
                name = "전리품 $id",
                rarity = "일반",
                kind = "전리품",
                foundAtLevel = id,
            )
        }

        val rows = bagItemRows(items)

        assertEquals(BAG_ITEMS_PER_ROW, rows[0].size)
        assertEquals(BAG_ITEMS_PER_ROW, rows[1].size)
        assertEquals(1, rows[2].size)
        assertEquals(items, rows.flatten())
    }

    @Test
    fun `equipped combat loot shows previous and new power`() {
        assertEquals("124 → 126", equipmentReplacementPowerLabel(124L, 126L))
        assertEquals(
            "124 → 124 · 등급 상승",
            equipmentReplacementPowerLabel(124L, 124L),
        )
    }

    @Test
    fun `shop replacement shows slot and values without the equipment power word`() {
        val offer = ShopEquipmentOffer(
            slot = EquipmentSlot.HEAD,
            name = "수호자의 기사 투구 +2",
            rarity = "희귀",
            previousPower = 150L,
            newPower = 153L,
            price = 620L,
        )

        assertEquals("머리 150 → 153", shopEquipmentChangeLabel(offer))
    }
}
