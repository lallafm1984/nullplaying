package com.nullplaying.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.nullplaying.R
import com.nullplaying.engine.HeroPathCatalog
import com.nullplaying.model.BattleHeroClass
import com.nullplaying.model.HeroPathNodeSlot
import kotlin.math.roundToInt

internal data class HeroPathArtworkTile(val resourceId: Int, val index: Int)

/** Each generated atlas is four columns by six rows: eight original paintings per branch. */
internal fun heroPathArtworkTile(nodeId: String): HeroPathArtworkTile? {
    val node = HeroPathCatalog.byTraitId[nodeId] ?: return null
    val resource = when (node.heroClassAffinity) {
        BattleHeroClass.WARRIOR -> R.drawable.talent_icons_warrior_v2
        BattleHeroClass.ROGUE -> R.drawable.talent_icons_rogue_v2
        BattleHeroClass.RANGER -> R.drawable.talent_icons_ranger_v2
        BattleHeroClass.MAGE -> R.drawable.talent_icons_mage_v2
        BattleHeroClass.CLERIC -> R.drawable.talent_icons_cleric_v2
        BattleHeroClass.PALADIN -> R.drawable.talent_icons_paladin_v2
    }
    val branchIndex = HeroPathCatalog.branchesFor(node.heroClassAffinity).indexOfFirst { it.branch == node.branch }
    val slotIndex = when (node.slot) {
        HeroPathNodeSlot.FOUNDATION_A -> 0
        HeroPathNodeSlot.FOUNDATION_B -> 1
        HeroPathNodeSlot.CHOICE_A -> 2
        HeroPathNodeSlot.CHOICE_B -> 3
        HeroPathNodeSlot.SPECIAL_A -> 4
        HeroPathNodeSlot.SPECIAL_B -> 5
        HeroPathNodeSlot.ADVANCED_TACTIC -> 6
        HeroPathNodeSlot.CORE -> 7
    }
    return HeroPathArtworkTile(resource, branchIndex * 8 + slotIndex)
}

@Composable
internal fun HeroPathTalentArtwork(
    nodeId: String,
    modifier: Modifier = Modifier,
    desaturated: Boolean = false,
) {
    val tile = remember(nodeId) { heroPathArtworkTile(nodeId) } ?: return
    val bitmap = ImageBitmap.imageResource(tile.resourceId)
    val filter = remember(desaturated) {
        if (desaturated) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) else null
    }
    Canvas(modifier.clip(CircleShape)) {
        val tileWidth = bitmap.width / 4
        val tileHeight = bitmap.height / 6
        // Trim only two texture-edge pixels, not the subject, to avoid atlas seams.
        drawImage(
            image = bitmap,
            srcOffset = IntOffset((tile.index % 4) * tileWidth + 2, (tile.index / 4) * tileHeight + 2),
            srcSize = IntSize(tileWidth - 4, tileHeight - 4),
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            colorFilter = filter,
            // Locking changes hue and badge, not the legibility of the painting.
            alpha = 1f,
        )
    }
}
