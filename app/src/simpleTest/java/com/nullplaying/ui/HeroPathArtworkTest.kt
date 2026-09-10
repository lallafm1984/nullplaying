package com.nullplaying.ui

import com.nullplaying.engine.HeroPathCatalog
import com.nullplaying.model.BattleHeroClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeroPathArtworkTest {
    @Test fun `every talent has its own original artwork tile`() {
        val tiles = HeroPathCatalog.nodes.map { heroPathArtworkTile(it.traitId) }
        assertEquals(144, tiles.size)
        tiles.forEach { assertNotNull(it) }
        assertEquals(144, tiles.toSet().size)
    }

    @Test fun `each class fills exactly one four by six atlas`() {
        BattleHeroClass.entries.forEach { heroClass ->
            val tiles = HeroPathCatalog.nodesFor(heroClass).map { requireNotNull(heroPathArtworkTile(it.traitId)) }
            assertEquals(1, tiles.map { it.resourceId }.distinct().size)
            assertEquals((0..23).toList(), tiles.map { it.index }.sorted())
        }
    }

    @Test fun `each specialization occupies two consecutive artwork rows`() {
        HeroPathCatalog.branches.forEach { branch ->
            val tiles = HeroPathCatalog.nodesFor(branch.branch).map { requireNotNull(heroPathArtworkTile(it.traitId)) }
            assertEquals(8, tiles.size)
            assertTrue(tiles.all { it.index / 8 == tiles.first().index / 8 })
        }
    }

    @Test fun `unknown legacy trait never borrows an unrelated image`() {
        assertNull(heroPathArtworkTile("TRAIT_001"))
        assertNull(heroPathArtworkTile("unknown"))
    }
}
