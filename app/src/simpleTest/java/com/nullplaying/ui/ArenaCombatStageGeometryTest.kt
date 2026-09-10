package com.nullplaying.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArenaCombatStageGeometryTest {
    @Test
    fun `stage and gauge dimensions preserve native footprint`() {
        assertEquals(218, ARENA_COMBAT_STAGE_HEIGHT_DP)
        assertEquals(58, ARENA_COMBAT_HEADER_HEIGHT_DP)
        assertEquals(160, ARENA_COMBAT_VIEWPORT_HEIGHT_DP)
        assertEquals(ARENA_COMBAT_STAGE_HEIGHT_DP,
            ARENA_COMBAT_HEADER_HEIGHT_DP + ARENA_COMBAT_VIEWPORT_HEIGHT_DP)
        assertEquals(4, ARENA_COMBAT_HP_HEIGHT_DP)
        assertEquals(2, ARENA_COMBAT_MP_HEIGHT_DP)
        assertEquals(2, ARENA_COMBAT_GAUGE_GAP_DP)
        assertEquals(4, ARENA_COMBAT_IDENTITY_GAUGE_GAP_DP)
    }

    @Test
    fun `both HP and MP retain their center anchors`() {
        val left = arenaCombatGaugeGeometry(.6f, .3f, 0f, false)
        val right = arenaCombatGaugeGeometry(.6f, .3f, 0f, true)
        segment(left.hp, .4f, 1f)
        segment(left.mp, .7f, 1f)
        segment(right.hp, 0f, .6f)
        segment(right.mp, 0f, .3f)
        assertNull(left.shield)
        assertNull(right.shield)
    }

    @Test
    fun `shield extends into missing HP without rescaling HP`() {
        val left = arenaCombatGaugeGeometry(.6f, .3f, .2f, false)
        val right = arenaCombatGaugeGeometry(.6f, .3f, .2f, true)
        segment(left.hp, .4f, 1f)
        segment(checkNotNull(left.shield), .2f, .4f)
        segment(right.hp, 0f, .6f)
        segment(checkNotNull(right.shield), .6f, .8f)
    }

    @Test
    fun `full health shield overlays the outer tip not the center`() {
        val left = arenaCombatGaugeGeometry(1f, 1f, .2f, false)
        val right = arenaCombatGaugeGeometry(1f, 1f, .2f, true)
        segment(left.hp, 0f, 1f)
        segment(right.hp, 0f, 1f)
        segment(checkNotNull(left.shield), 0f, .2f)
        segment(checkNotNull(right.shield), .8f, 1f)
    }

    @Test
    fun `overflow still uses the original shield and HP scales`() {
        val left = arenaCombatGaugeGeometry(.8f, .3f, .4f, false)
        val right = arenaCombatGaugeGeometry(.8f, .3f, .4f, true)
        segment(left.hp, .2f, 1f)
        segment(right.hp, 0f, .8f)
        segment(checkNotNull(left.shield), 0f, .4f)
        segment(checkNotNull(right.shield), .6f, 1f)
    }

    @Test
    fun `shield change never changes HP or MP positions`() {
        for (right in listOf(false, true)) for (step in 0..100) {
            val withShield = arenaCombatGaugeGeometry(.37f, .19f, step / 100f, right)
            val withoutShield = arenaCombatGaugeGeometry(.37f, .19f, 0f, right)
            assertEquals(withoutShield.hp, withShield.hp)
            assertEquals(withoutShield.mp, withShield.mp)
        }
    }

    @Test
    fun `zero invalid and overfull inputs stay within each half`() {
        for (right in listOf(false, true)) {
            val invalid = arenaCombatGaugeGeometry(Float.NaN, Float.POSITIVE_INFINITY, -1f, right)
            assertEquals(0f, invalid.hp.length, .00001f)
            assertEquals(0f, invalid.mp.length, .00001f)
            assertNull(invalid.shield)
            val overfull = arenaCombatGaugeGeometry(2f, 2f, 2f, right)
            segment(overfull.hp, 0f, 1f)
            segment(overfull.mp, 0f, 1f)
            segment(checkNotNull(overfull.shield), 0f, 1f)
        }
    }

    @Test
    fun `mirrored geometry has equal lengths for every shield threshold`() {
        for (hpStep in 0..20) for (shieldStep in 0..20) {
            val hp = hpStep / 20f
            val shield = shieldStep / 20f
            val left = arenaCombatGaugeGeometry(hp, .5f, shield, false)
            val right = arenaCombatGaugeGeometry(hp, .5f, shield, true)
            assertEquals(left.hp.length, right.hp.length, .00001f)
            left.shield?.let {
                val counterpart = checkNotNull(right.shield)
                assertEquals(it.length, counterpart.length, .00001f)
                assertEquals(1f - it.end, counterpart.start, .00001f)
                assertEquals(1f - it.start, counterpart.end, .00001f)
                assertTrue(it.start >= 0f && it.end <= 1f)
            }
        }
    }

    private fun segment(actual: ArenaGaugeSegment, start: Float, end: Float) {
        assertEquals(start, actual.start, .00001f)
        assertEquals(end, actual.end, .00001f)
    }
}
