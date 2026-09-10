package com.nullplaying.ui

import com.nullplaying.engine.arena.ArenaIdentityCopy

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nullplaying.R
import com.nullplaying.engine.arena.ArenaProgressionRules
import com.nullplaying.engine.arena.ArenaSkillNodeKind as EngineArenaSkillNodeKind
import com.nullplaying.engine.arena.ArenaSkillTreeCatalog
import com.nullplaying.engine.arena.ArenaSkillTreeNodeDefinition
import com.nullplaying.engine.arena.ArenaSkillTreeNodeView
import com.nullplaying.engine.arena.ArenaSkillTreeView
import com.nullplaying.engine.arena.ArenaSupportCatalog
import com.nullplaying.localization.AppLanguage
import com.nullplaying.model.HeroClass
import kotlin.math.roundToInt

internal enum class ArenaSkillTreeNodeKind {
    ATTACK,
    SUPPORT,
}

internal enum class ArenaSkillTreeNodeStatus {
    LOCKED,
    UNAVAILABLE,
    AVAILABLE,
    INVESTED,
    MAX,
}

internal enum class ArenaSkillRankBonusStatus {
    LOCKED,
    ACTIVE,
}

internal enum class ArenaSkillRequirementKind {
    PREREQUISITE,
    INVESTMENT,
    OWNERSHIP,
    STATE,
}

@Immutable
internal data class ArenaSkillRequirementUiModel(
    val kind: ArenaSkillRequirementKind,
    val label: String,
    val value: String,
    /** Whether this individual acquisition condition is currently satisfied. */
    val satisfied: Boolean,
) {
    init {
        require(label.isNotBlank() && value.isNotBlank())
    }
}

@Immutable
internal data class ArenaSkillRankBonusUiModel(
    val rank: Int,
    val title: String,
    val effect: String,
    val status: ArenaSkillRankBonusStatus,
) {
    init {
        require(rank == 5 || rank == 10)
        require(title.isNotBlank() && effect.isNotBlank())
    }
}

/** Presentation-only snapshot. The caller remains responsible for validation and atomic saving. */
@Immutable
internal data class ArenaSkillTreeUiModel(
    val heroClass: HeroClass,
    val unlocked: Boolean,
    val availablePoints: Int,
    val spentPoints: Int,
    val editingEnabled: Boolean,
    val nodes: List<ArenaSkillTreeNodeUiModel>,
) {
    init {
        require(availablePoints >= 0 && spentPoints >= 0)
        require(nodes.map(ArenaSkillTreeNodeUiModel::id).distinct().size == nodes.size)
        require(nodes.map { it.row to it.column }.distinct().size == nodes.size)
    }
}

@Immutable
internal data class ArenaSkillTreeNodeUiModel(
    val id: String,
    val name: String,
    val kind: ArenaSkillTreeNodeKind,
    val row: Int,
    val column: Int,
    val parentIds: List<String>,
    val rank: Int,
    val maxRank: Int = 10,
    val iconIndex: Int,
    val timing: String,
    val currentEffect: String,
    val nextEffect: String,
    /** Compact compatibility copy. New UI should render [requirementRows] separately. */
    val requirement: String,
    val status: ArenaSkillTreeNodeStatus,
    val canRankUp: Boolean,
    val canReset: Boolean = false,
    val resetBlockedMessage: String = "",
    val rankBonuses: List<ArenaSkillRankBonusUiModel> = emptyList(),
    val requirementRows: List<ArenaSkillRequirementUiModel> = emptyList(),
) {
    init {
        require(id.isNotBlank() && name.isNotBlank())
        require(row in 0 until ARENA_SKILL_TREE_ROWS)
        require(column in 0 until ARENA_SKILL_TREE_COLUMNS)
        require(maxRank > 0 && rank in 0..maxRank)
        require(iconIndex >= 0)
        if (kind == ArenaSkillTreeNodeKind.ATTACK) require(iconIndex < ATTACK_ATLAS_TILE_COUNT)
        if (kind == ArenaSkillTreeNodeKind.SUPPORT) require(iconIndex < SUPPORT_ATLAS_VISIBLE_TILE_COUNT)
        require(rankBonuses.map(ArenaSkillRankBonusUiModel::rank).distinct().size == rankBonuses.size)
        require(rankBonuses.all { it.rank <= maxRank })
        require(rankBonuses.zipWithNext().all { (before, after) -> before.rank < after.rank })
        require(requirementRows.map(ArenaSkillRequirementUiModel::kind).distinct().size == requirementRows.size)
    }
}

/**
 * Shared V6 engine-to-presentation mapping for BattlePanel and isolated QA. Engine definitions stay
 * authoritative; this function only localizes and compacts their already validated view.
 */
internal fun arenaSkillTreeUiModel(
    treeView: ArenaSkillTreeView,
    unlocked: Boolean,
    editingEnabled: Boolean,
    language: AppLanguage,
): ArenaSkillTreeUiModel {
    val definitionsById = treeView.nodes.associate { it.definition.id to it.definition }
    val ranksById = treeView.nodes.associate { it.definition.id to it.rank }
    return ArenaSkillTreeUiModel(
        heroClass = treeView.state.heroClass,
        unlocked = unlocked,
        availablePoints = treeView.availablePoints,
        spentPoints = treeView.spentPoints,
        editingEnabled = editingEnabled,
        nodes = treeView.nodes.map { node ->
            val definition = node.definition
            val spentBeforeRow = treeView.nodes.sumOf { candidate ->
                if (candidate.definition.row < definition.row) candidate.rank else 0
            }
            val requirementRows = arenaSkillTreeRequirementRows(
                node = node,
                definitionsById = definitionsById,
                ranksById = ranksById,
                spentBeforeRow = spentBeforeRow,
                language = language,
            )
            val status = when {
                node.rank >= definition.maxRank -> ArenaSkillTreeNodeStatus.MAX
                node.rank > 0 -> ArenaSkillTreeNodeStatus.INVESTED
                !unlocked -> ArenaSkillTreeNodeStatus.UNAVAILABLE
                !editingEnabled && node.canAllocate -> ArenaSkillTreeNodeStatus.UNAVAILABLE
                unlocked && node.canAllocate -> ArenaSkillTreeNodeStatus.AVAILABLE
                else -> ArenaSkillTreeNodeStatus.LOCKED
            }
            ArenaSkillTreeNodeUiModel(
                id = definition.id,
                name = definition.arenaTreeName(language),
                kind = when (definition.kind) {
                    EngineArenaSkillNodeKind.ATTACK -> ArenaSkillTreeNodeKind.ATTACK
                    EngineArenaSkillNodeKind.SUPPORT -> ArenaSkillTreeNodeKind.SUPPORT
                },
                row = definition.row,
                column = definition.column,
                parentIds = definition.parentAnyOf.sortedWith(
                    compareBy({ definitionsById[it]?.row ?: Int.MAX_VALUE },
                        { definitionsById[it]?.column ?: Int.MAX_VALUE }, { it }),
                ),
                rank = node.rank,
                maxRank = definition.maxRank,
                iconIndex = definition.slotKey.drop(1).toInt() - 1,
                timing = arenaTreeTiming(node, language),
                currentEffect = if (node.rank == 0) {
                    arenaTreeCopy(language, "미습득", "Not learned", "未習得")
                } else {
                    arenaTreeEffect(node, node.rank, language)
                },
                nextEffect = if (node.rank >= definition.maxRank) {
                    "MAX"
                } else {
                    arenaTreeNextEffect(node, node.rank + 1, language)
                },
                requirement = requirementRows.joinToString(" · ") { it.value },
                status = status,
                canRankUp = unlocked && editingEnabled && node.canAllocate,
                canReset = unlocked && editingEnabled && node.canReset,
                resetBlockedMessage = if (node.resetFailure == "dependent_skills") arenaTreeCopy(language,
                    "다른 스킬의 습득 조건에 필요합니다. 관련 스킬을 먼저 초기화하세요.",
                    "Other skills require this investment. Reset those skills first.",
                    "ほかのスキルの習得条件に必要です。該当するスキルを先にリセットしてください。") else "",

                rankBonuses = arenaTreeRankBonuses(definition, node.rank, language),
                requirementRows = requirementRows,
            )
        },
    )
}

private const val ARENA_SKILL_TREE_ROWS = 10
private const val ARENA_SKILL_TREE_COLUMNS = 3
private const val ATTACK_ATLAS_TILE_COUNT = 24
private const val SUPPORT_ATLAS_VISIBLE_TILE_COUNT = 10
private val ArenaTreeBackdrop = Color(0xFF100D17)
private val ArenaTreePanel = Color(0xE61A1422)
private val ArenaTreeLocked = Color(0xFF8E8496)
private val ArenaTreeAvailable = Color(0xFFE9C66F)
private val ArenaTreeInvested = Color(0xFFCEA64F)
private val ArenaTreeMaximum = Color(0xFFF5DC96)
private val ArenaTreeLineLocked = Color(0xFF51495A)
private val ArenaTreeNodeShape = CutCornerShape(8.dp)

/** Compact BattleHome entry; opening remains useful even while allocation itself is locked. */
internal fun arenaSkillTreeEntryDetail(
    model: ArenaSkillTreeUiModel,
    language: AppLanguage,
): String {
    if (!model.unlocked) {
        val level = ArenaProgressionRules.MIN_HERO_LEVEL
        return arenaTreeCopy(language, "Lv.$level 필요", "Requires Lv.$level", "Lv.${level}で解放")
    }
    val attackCount = model.nodes.count { it.kind == ArenaSkillTreeNodeKind.ATTACK && it.rank > 0 }
    val supportCount = model.nodes.count { it.kind == ArenaSkillTreeNodeKind.SUPPORT && it.rank > 0 }
    return arenaTreeCopy(
        language,
        "공격 $attackCount · 보조 $supportCount · ${model.availablePoints}포인트 남음",
        "Attack $attackCount · Support $supportCount · ${model.availablePoints} ${if (model.availablePoints == 1) "point" else "points"} left",
        "攻撃$attackCount・補助$supportCount・残り${model.availablePoints}ポイント",
    )
}

internal fun arenaSkillTreeEntryTitle(language: AppLanguage): String = arenaTreeCopy(
    language,
    "결투장 스킬 설정",
    "Arena Skill Setup",
    "闘技場スキル設定",
)

internal fun arenaSkillTreeScreenTitle(language: AppLanguage): String = arenaTreeCopy(
    language,
    "스킬 설정",
    "Skill Setup",
    "スキル設定",
)

@Composable
internal fun ArenaSkillTreeEntryCard(
    model: ArenaSkillTreeUiModel,
    onOpen: () -> Unit,
) {
    val language = LocalAppLanguage.current
    val highlighted = model.unlocked && model.availablePoints > 0
    val detail = arenaSkillTreeEntryDetail(model, language)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .testTag("arena-skill-tree-entry")
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = arenaTreeCopy(
                    language,
                    "${arenaSkillTreeEntryTitle(AppLanguage.KOREAN)}, $detail, 보기",
                    "${arenaSkillTreeEntryTitle(AppLanguage.ENGLISH)}, $detail, open",
                    "${arenaSkillTreeEntryTitle(AppLanguage.JAPANESE)}、$detail、開く",
                )
            },
        color = if (highlighted) AqSurfaceHigh else AqSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (highlighted) AqGold else AqGoldSoft.copy(alpha = 0.58f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CutCornerShape(7.dp))
                    .background(ArenaTreeBackdrop)
                    .border(1.dp, if (highlighted) AqGold else AqGoldSoft, CutCornerShape(7.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.AccountTree,
                    contentDescription = null,
                    tint = if (model.unlocked) AqGold else AqMuted,
                    modifier = Modifier.size(25.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 11.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    arenaSkillTreeEntryTitle(language),
                    color = AqText,
                    fontSize = 15.sp,
                    lineHeight = 19.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                )
                Text(
                    detail,
                    color = if (highlighted) AqGold else AqMuted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                arenaTreeCopy(language, "보기", "View", "表示"),
                color = AqGold,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
            )
            Spacer(Modifier.width(2.dp))
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = AqGold, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun ArenaSkillTreeFixedFontScale(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density = density.density, fontScale = 1f),
        content = content,
    )
}

/** A single ten-row arena tree. Optional class switching is reserved for isolated review surfaces. */
@Composable
internal fun ArenaSkillTreeOverlay(
    model: ArenaSkillTreeUiModel,
    onDismiss: () -> Unit,
    onRankUp: (String) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    reviewHeroClasses: List<HeroClass> = emptyList(),
    onReviewHeroClassSelected: (HeroClass) -> Unit = {},
    onResetSkill: ((String) -> Unit)? = null,
) {
    val language = LocalAppLanguage.current
    var selectedNodeId by rememberSaveable(model.heroClass) { mutableStateOf<String?>(null) }
    var confirmReset by rememberSaveable(model.heroClass) { mutableStateOf(false) }
    var classPickerShown by rememberSaveable { mutableStateOf(false) }
    val selectedNode = model.nodes.firstOrNull { it.id == selectedNodeId }
    val listState = rememberLazyListState()
    val canReset = model.unlocked && model.editingEnabled && model.spentPoints > 0
    val reviewClasses = remember(model.heroClass, reviewHeroClasses) {
        arenaSkillTreeReviewClasses(model.heroClass, reviewHeroClasses)
    }
    LaunchedEffect(model.heroClass) {
        listState.scrollToItem(0)
    }

    BackHandler {
        when {
            confirmReset -> confirmReset = false
            classPickerShown -> classPickerShown = false
            selectedNodeId != null -> selectedNodeId = null
            else -> onDismiss()
        }
    }

    ArenaSkillTreeFixedFontScale {
        val scrimInteractionSource = remember { MutableInteractionSource() }
        Box(
            modifier = modifier
                .fillMaxSize()
                .testTag("arena-skill-tree")
                .semantics {
                    dialog()
                    paneTitle = arenaSkillTreeScreenTitle(language)
                },
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.68f))
                    .clickable(
                        interactionSource = scrimInteractionSource,
                        indication = null,
                        onClick = {},
                    )
                    .clearAndSetSemantics { },
            )

            Surface(
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .fillMaxSize(),
                color = ArenaTreeBackdrop,
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(2.dp, AqGoldSoft),
                shadowElevation = 18.dp,
            ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ArenaTreeBackdrop),
            ) {
                Image(
                    painter = painterResource(R.drawable.talent_tree_backdrop_v2),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    ArenaTreeBackdrop.copy(alpha = 0.32f),
                                    ArenaTreeBackdrop.copy(alpha = 0.12f),
                                    ArenaTreeBackdrop.copy(alpha = 0.76f),
                                ),
                            ),
                        ),
                )

                // This overlay is already hosted below the app-level banner and system inset.
                // Reapplying the top safe inset here leaves an empty strip above the header.
                Column(Modifier.fillMaxSize()) {
                    ArenaSkillTreeHeader(
                        model = model,
                        language = language,
                        canReset = canReset,
                        onDismiss = onDismiss,
                        onReset = {
                            selectedNodeId = null
                            classPickerShown = false
                            confirmReset = true
                        },
                        reviewHeroClasses = reviewClasses,
                        onOpenClassPicker = {
                            selectedNodeId = null
                            confirmReset = false
                            classPickerShown = true
                        },
                    )
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .testTag("arena-tree-list"),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            top = 8.dp,
                            bottom = 32.dp,
                        ),
                    ) {
                        items((0 until ARENA_SKILL_TREE_ROWS).toList(), key = { "arena-tree-row-$it" }) { row ->
                            ArenaSkillTreeRow(
                                row = row,
                                allNodes = model.nodes,
                                heroClass = model.heroClass,
                                language = language,
                                onNodeSelected = {
                                    classPickerShown = false
                                    confirmReset = false
                                    selectedNodeId = it.id
                                },
                            )
                        }
                    }
                }
            }
        }

            when {
                confirmReset -> ArenaSkillTreeResetOverlay(
                    model = model,
                    language = language,
                    onDismiss = { confirmReset = false },
                    onReset = {
                        confirmReset = false
                        onReset()
                    },
                )
                classPickerShown -> ArenaSkillTreeClassPickerOverlay(
                    selectedHeroClass = model.heroClass,
                    heroClasses = reviewClasses,
                    language = language,
                    onDismiss = { classPickerShown = false },
                    onHeroClassSelected = { heroClass ->
                        classPickerShown = false
                        selectedNodeId = null
                        confirmReset = false
                        onReviewHeroClassSelected(heroClass)
                    },
                )
                selectedNode != null -> ArenaSkillNodeOverlay(
                    model = model,
                    node = selectedNode,
                    language = language,
                    onDismiss = { selectedNodeId = null },
                    onRankUp = { onRankUp(selectedNode.id) },
                    onResetSkill = onResetSkill?.let { reset -> { reset(selectedNode.id) } },
                )
            }
        }
    }
}

/** Source-compatible entry point for isolated QA screens; renders no platform dialog. */
@Deprecated("Use ArenaSkillTreeOverlay")
@Composable
internal fun ArenaSkillTreeDialog(
    model: ArenaSkillTreeUiModel,
    onDismiss: () -> Unit,
    onRankUp: (String) -> Unit,
    onReset: () -> Unit,
    reviewHeroClasses: List<HeroClass> = emptyList(),
    onReviewHeroClassSelected: (HeroClass) -> Unit = {},
    onResetSkill: ((String) -> Unit)? = null,
) {
    ArenaSkillTreeOverlay(
        model = model,
        onDismiss = onDismiss,
        onRankUp = onRankUp,
        onReset = onReset,
        reviewHeroClasses = reviewHeroClasses,
        onReviewHeroClassSelected = onReviewHeroClassSelected,
        onResetSkill = onResetSkill,
    )
}

@Composable
private fun ArenaSkillTreeModalLayer(
    paneTitleText: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .semantics {
                dialog()
                paneTitle = paneTitleText
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(Color.Black.copy(alpha = 0.72f))
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onDismiss,
                )
                .clearAndSetSemantics { },
        )
        Box(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

@Composable
private fun ArenaSkillTreeResetOverlay(
    model: ArenaSkillTreeUiModel,
    language: AppLanguage,
    onDismiss: () -> Unit,
    onReset: () -> Unit,
) {
    ArenaSkillTreeModalLayer(
        paneTitleText = arenaTreeCopy(
            language,
            "스킬 포인트 초기화",
            "Reset skill points",
            "スキルポイントをリセット",
        ),
        onDismiss = onDismiss,
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .testTag("arena-tree-reset-dialog"),
            color = AqSurface,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(2.dp, AqGoldSoft),
            shadowElevation = 18.dp,
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                Text(
                    arenaTreeCopy(
                        language,
                        "스킬 포인트 초기화",
                        "Reset skill points",
                        "スキルポイントをリセット",
                    ),
                    color = AqText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    lineHeight = 26.sp,
                )
                Text(
                    arenaTreeCopy(
                        language,
                        "${arenaTreeClassName(model.heroClass, language)}의 ${model.spentPoints}포인트를 모두 돌려받습니다.",
                        "Refund ${model.spentPoints} skill ${if (model.spentPoints == 1) "point" else "points"} spent on ${arenaTreeClassName(model.heroClass, language)}.",
                        "${arenaTreeClassName(model.heroClass, language)}の${model.spentPoints}ポイントをすべて戻します。",
                    ),
                    modifier = Modifier.padding(top = 12.dp),
                    color = AqMuted,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("arena-tree-reset-cancel"),
                        shape = CutCornerShape(8.dp),
                        border = BorderStroke(1.dp, AqGoldSoft),
                    ) {
                        Text(
                            arenaTreeCopy(language, "취소", "Cancel", "キャンセル"),
                            color = AqText,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Button(
                        onClick = onReset,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("arena-tree-reset-confirm"),
                        shape = CutCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AqGold,
                            contentColor = ArenaTreeBackdrop,
                        ),
                    ) {
                        Text(
                            arenaTreeCopy(language, "초기화", "Reset", "リセット"),
                            fontWeight = FontWeight.Black,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArenaSkillTreeHeader(
    model: ArenaSkillTreeUiModel,
    language: AppLanguage,
    canReset: Boolean,
    onDismiss: () -> Unit,
    onReset: () -> Unit,
    reviewHeroClasses: List<HeroClass>,
    onOpenClassPicker: () -> Unit,
) {
    Surface(
        color = ArenaTreePanel,
        border = BorderStroke(1.dp, AqGoldSoft.copy(alpha = 0.75f)),
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 9.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.width(80.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .width(80.dp)
                            .height(48.dp)
                            .testTag("arena-tree-close"),
                        shape = CutCornerShape(8.dp),
                        border = BorderStroke(1.dp, AqGoldSoft.copy(alpha = 0.72f)),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AqText),
                    ) {
                        Text(
                            arenaTreeCopy(language, "닫기", "Close", "閉じる"),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .then(
                            if (reviewHeroClasses.isNotEmpty()) {
                                Modifier
                                    .testTag("arena-tree-class-switcher")
                                    .clearAndSetSemantics {
                                        contentDescription = arenaTreeCopy(
                                            language,
                                            "직업 선택, 현재 ${arenaTreeClassName(model.heroClass, language)}, 스킬 포인트 ${model.spentPoints + model.availablePoints}",
                                            "Choose class, current ${arenaTreeClassName(model.heroClass, language)}, skill points ${model.spentPoints + model.availablePoints}",
                                            "職業選択、現在${arenaTreeClassName(model.heroClass, language)}、スキルポイント${model.spentPoints + model.availablePoints}",
                                        )
                                        stateDescription = arenaTreeCopy(language, "눌러서 변경", "Tap to change", "タップして変更")
                                        role = Role.Button
                                        onClick(
                                            label = arenaTreeCopy(language, "직업 선택창 열기", "Open class picker", "職業選択を開く"),
                                        ) {
                                            onOpenClassPicker()
                                            true
                                        }
                                    }
                                    .clickable(role = Role.Button, onClick = onOpenClassPicker)
                            } else {
                                Modifier
                            },
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        arenaSkillTreeScreenTitle(language),
                        modifier = Modifier.semantics { heading() },
                        color = AqText,
                        fontSize = 18.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                    )
                    Row(
                        modifier = Modifier
                            .testTag("arena-tree-current-class")
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            arenaTreeCopy(language,
                                "${arenaTreeClassName(model.heroClass, language)} · 총 ${model.spentPoints + model.availablePoints}포인트",
                                "${arenaTreeClassName(model.heroClass, language)} · ${model.spentPoints + model.availablePoints} points",
                                "${arenaTreeClassName(model.heroClass, language)} · 計${model.spentPoints + model.availablePoints}ポイント"),
                            color = if (reviewHeroClasses.isNotEmpty()) AqGold else AqMuted,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            fontWeight = if (reviewHeroClasses.isNotEmpty()) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                        )
                        if (reviewHeroClasses.isNotEmpty()) {
                            Icon(
                                Icons.Filled.ExpandMore,
                                contentDescription = null,
                                tint = AqGold,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                }
                OutlinedButton(
                    onClick = onReset,
                    enabled = canReset,
                    modifier = Modifier.width(80.dp).height(48.dp).testTag("arena-tree-reset"),
                    shape = CutCornerShape(8.dp),
                    border = BorderStroke(
                        1.dp,
                        if (canReset) AqGoldSoft else ArenaTreeLocked.copy(alpha = 0.45f),
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = AqGold,
                        disabledContentColor = ArenaTreeLocked.copy(alpha = 0.65f),
                    ),
                ) {
                    Text(
                        arenaTreeCopy(language, "초기화", "Reset", "リセット"),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArenaSkillPointCounter(
                    label = arenaTreeCopy(language, "사용 가능", "Available", "使用可能"),
                    value = model.availablePoints,
                    valueColor = AqGold,
                    accessibilityLabel = arenaTreeCopy(
                        language,
                        "사용 가능한 스킬 포인트 ${model.availablePoints}",
                        "${model.availablePoints} skill ${if (model.availablePoints == 1) "point" else "points"} available",
                        "使用可能なスキルポイント${model.availablePoints}",
                    ),
                    modifier = Modifier.weight(1f),
                )
                ArenaSkillPointCounter(
                    label = arenaTreeCopy(language, "투자", "Invested", "投資済み"),
                    value = model.spentPoints,
                    valueColor = AqText,
                    accessibilityLabel = arenaTreeCopy(
                        language,
                        "투자한 스킬 포인트 ${model.spentPoints}",
                        "${model.spentPoints} skill ${if (model.spentPoints == 1) "point" else "points"} invested",
                        "投資済みスキルポイント${model.spentPoints}",
                    ),
                    modifier = Modifier.weight(1f),
                )
            }
            if (!model.unlocked || !model.editingEnabled) {
                Text(
                    if (!model.unlocked) {
                        arenaTreeCopy(language, "결투장 성장 해금 전입니다.", "Arena progression is locked.", "闘技場育成は未解放です。")
                    } else {
                        arenaTreeCopy(language, "대전 준비·진행 중에는 변경할 수 없습니다.", "Changes are locked during a duel.", "対戦中は変更できません。")
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    color = AqGold,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ArenaSkillPointCounter(
    label: String,
    value: Int,
    valueColor: Color,
    accessibilityLabel: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .heightIn(min = 38.dp)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = accessibilityLabel
            },
        color = ArenaTreeBackdrop.copy(alpha = 0.72f),
        shape = CutCornerShape(topStart = 7.dp, bottomEnd = 7.dp),
        border = BorderStroke(1.dp, AqGoldSoft.copy(alpha = 0.45f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                modifier = Modifier.weight(1f),
                color = AqMuted,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                maxLines = 1,
            )
            Text(
                value.toString(),
                color = valueColor,
                fontSize = 16.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Black,
            )
        }
    }
}

@Composable
private fun ArenaSkillTreeClassPickerOverlay(
    selectedHeroClass: HeroClass,
    heroClasses: List<HeroClass>,
    language: AppLanguage,
    onDismiss: () -> Unit,
    onHeroClassSelected: (HeroClass) -> Unit,
) {
    ArenaSkillTreeModalLayer(
        paneTitleText = arenaTreeCopy(language, "직업 선택", "Choose class", "職業選択"),
        onDismiss = onDismiss,
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .testTag("arena-tree-class-picker"),
            color = ArenaTreePanel,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(2.dp, AqGoldSoft),
            shadowElevation = 18.dp,
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text(
                    arenaTreeCopy(language, "직업 선택", "Choose class", "職業選択"),
                    color = AqText,
                    fontWeight = FontWeight.Black,
                    fontSize = 20.sp,
                    lineHeight = 26.sp,
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .padding(top = 12.dp)
                        .verticalScroll(rememberScrollState())
                        .selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    heroClasses.chunked(3).forEach { rowClasses ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            rowClasses.forEach { heroClass ->
                                val isSelected = heroClass == selectedHeroClass
                                val className = arenaTreeClassName(heroClass, language)
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                        .testTag("arena-tree-class-option-${heroClass.name.lowercase()}")
                                        .clearAndSetSemantics {
                                            selected = isSelected
                                            role = Role.RadioButton
                                            stateDescription = arenaTreeCopy(
                                                language,
                                                if (isSelected) "선택됨" else "선택 안 됨",
                                                if (isSelected) "Selected" else "Not selected",
                                                if (isSelected) "選択中" else "未選択",
                                            )
                                            contentDescription = arenaTreeCopy(
                                                language,
                                                "$className 스킬 설정",
                                                "$className skill setup",
                                                "$className のスキル設定",
                                            )
                                            onClick(
                                                label = arenaTreeCopy(
                                                    language,
                                                    "$className 스킬 설정 보기",
                                                    "View $className skill setup",
                                                    "$className のスキル設定を表示",
                                                ),
                                            ) {
                                                onHeroClassSelected(heroClass)
                                                true
                                            }
                                        }
                                        .selectable(
                                            selected = isSelected,
                                            role = Role.RadioButton,
                                            onClick = { onHeroClassSelected(heroClass) },
                                        ),
                                    color = if (isSelected) AqGold.copy(alpha = 0.18f) else ArenaTreeBackdrop,
                                    shape = CutCornerShape(6.dp),
                                    border = BorderStroke(
                                        if (isSelected) 2.dp else 1.dp,
                                        if (isSelected) AqGold else ArenaTreeLineLocked,
                                    ),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 9.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (isSelected) {
                                            Icon(
                                                Icons.Filled.CheckCircle,
                                                contentDescription = null,
                                                tint = AqGold,
                                                modifier = Modifier.size(14.dp),
                                            )
                                            Spacer(Modifier.width(4.dp))
                                        }
                                        Text(
                                            className,
                                            color = if (isSelected) AqText else AqMuted,
                                            fontSize = 11.sp,
                                            lineHeight = 14.sp,
                                            fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            repeat(3 - rowClasses.size) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(arenaTreeCopy(language, "닫기", "Close", "閉じる"), color = AqGold)
                }
            }
            }
        }
    }
}

@Composable
private fun ArenaSkillTreeRow(
    row: Int,
    allNodes: List<ArenaSkillTreeNodeUiModel>,
    heroClass: HeroClass,
    language: AppLanguage,
    onNodeSelected: (ArenaSkillTreeNodeUiModel) -> Unit,
) {
    val rowNodes = remember(row, allNodes) { allNodes.filter { it.row == row }.associateBy { it.column } }
    val nodesById = remember(allNodes) { allNodes.associateBy(ArenaSkillTreeNodeUiModel::id) }
    val edges = remember(allNodes) {
        allNodes.flatMap { child ->
            child.parentIds.mapNotNull { parentId -> nodesById[parentId]?.let { parent -> parent to child } }
        }
    }
    Box(Modifier.fillMaxWidth().height(148.dp)) {
        Canvas(
            Modifier
                .matchParentSize()
                .clearAndSetSemantics { },
        ) {
            fun columnX(column: Int): Float = size.width * (column * 2f + 1f) / (ARENA_SKILL_TREE_COLUMNS * 2f)
            val frameTop = 20.dp.toPx()
            val frameBottom = frameTop + 64.dp.toPx()
            val elbow = 9.dp.toPx()
            edges.forEach { (parent, child) ->
                if (row !in parent.row..child.row) return@forEach
                val parentX = columnX(parent.column)
                val childX = columnX(child.column)
                val color = arenaTreeConnectorColor(child.status)
                val stroke = if (child.status == ArenaSkillTreeNodeStatus.INVESTED || child.status == ArenaSkillTreeNodeStatus.MAX) {
                    3.dp.toPx()
                } else {
                    2.dp.toPx()
                }
                if (parent.row == child.row) {
                    val halfFrame = 32.dp.toPx()
                    val centerY = frameTop + halfFrame
                    drawLine(
                        color,
                        Offset(parentX + halfFrame, centerY),
                        Offset(childX - halfFrame, centerY),
                        stroke,
                        StrokeCap.Round,
                    )
                    return@forEach
                }
                when (row) {
                    parent.row -> drawLine(color, Offset(parentX, frameBottom), Offset(parentX, size.height), stroke, StrokeCap.Round)
                    child.row -> {
                        drawLine(color, Offset(parentX, 0f), Offset(parentX, elbow), stroke, StrokeCap.Round)
                        drawLine(color, Offset(parentX, elbow), Offset(childX, elbow), stroke, StrokeCap.Round)
                        drawLine(color, Offset(childX, elbow), Offset(childX, frameTop), stroke, StrokeCap.Round)
                    }
                    else -> drawLine(color, Offset(parentX, 0f), Offset(parentX, size.height), stroke, StrokeCap.Round)
                }
            }
        }
        Text(
            arenaTreeCopy(language, "${row + 1}단계", "Tier ${row + 1}", "${row + 1}段階"),
            modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 2.dp),
            color = AqGold,
            fontSize = 9.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.Bold,
        )
        Row(Modifier.fillMaxWidth().padding(top = 16.dp)) {
            repeat(ARENA_SKILL_TREE_COLUMNS) { column ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                    val node = rowNodes[column]
                    if (node != null) {
                        ArenaSkillTreeNode(node, heroClass, language) { onNodeSelected(node) }
                    } else {
                        Spacer(Modifier.width(104.dp).height(128.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ArenaSkillTreeNode(
    node: ArenaSkillTreeNodeUiModel,
    heroClass: HeroClass,
    language: AppLanguage,
    onClick: () -> Unit,
) {
    val statusLabel = arenaTreeStatusLabel(node.status, language)
    val kindLabel = arenaTreeKindLabel(node.kind, language)
    val accent = arenaTreeStatusColor(node.status)
    val activeBonuses = node.rankBonuses.filter { it.status == ArenaSkillRankBonusStatus.ACTIVE }
    val milestoneState = when {
        activeBonuses.any { it.rank==10 } -> arenaTreeCopy(language, ", 마스터 보너스 활성", ", master bonus active", "、マスターボーナス有効")
        activeBonuses.isNotEmpty() -> arenaTreeCopy(language, ", 숙련 보너스 활성", ", adept bonus active", "、熟練ボーナス有効")
        else -> ""
    }
    Column(
        modifier = Modifier
            .width(104.dp)
            .heightIn(min = 128.dp)
            .testTag("arena-node-${node.id}")
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = arenaTreeCopy(
                    language,
                    "${node.name}, $kindLabel, 랭크 ${node.rank}/${node.maxRank}$milestoneState",
                    "${node.name}, $kindLabel, rank ${node.rank} of ${node.maxRank}$milestoneState",
                    "${node.name}、$kindLabel、ランク${node.rank}/${node.maxRank}$milestoneState",
                )
                stateDescription = statusLabel
                onClick(
                    label = arenaTreeCopy(
                        language,
                        "${node.name} 상세 보기",
                        "Open ${node.name} details",
                        "${node.name}の詳細を表示",
                    ),
                ) {
                    onClick()
                    true
                }
            }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ArenaSkillNodeArtwork(node = node, heroClass = heroClass, modifier = Modifier.size(64.dp))
        Text(
            node.name,
            modifier = Modifier
                .padding(top = 4.dp)
                .background(ArenaTreeBackdrop.copy(alpha = 0.86f), RoundedCornerShape(3.dp))
                .padding(horizontal = 3.dp),
            color = if (
                node.status == ArenaSkillTreeNodeStatus.LOCKED ||
                node.status == ArenaSkillTreeNodeStatus.UNAVAILABLE
            ) AqMuted else AqText,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Row(
            modifier = Modifier
                .padding(top = 2.dp)
                .background(ArenaTreeBackdrop.copy(alpha = 0.92f), RoundedCornerShape(4.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp),
            horizontalArrangement = Arrangement.spacedBy(1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${node.rank}/${node.maxRank}",
                color = accent,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.Black,
            )
            repeat(activeBonuses.size.coerceAtMost(2)) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = null,
                    tint = ArenaTreeMaximum,
                    modifier = Modifier.size(9.dp),
                )
            }
        }
    }
}

@Composable
private fun ArenaSkillNodeArtwork(
    node: ArenaSkillTreeNodeUiModel,
    heroClass: HeroClass,
    modifier: Modifier = Modifier,
) {
    val accent = arenaTreeStatusColor(node.status)
    val locked = node.status == ArenaSkillTreeNodeStatus.LOCKED ||
        node.status == ArenaSkillTreeNodeStatus.UNAVAILABLE
    val frameBrush = if (locked) {
        Brush.linearGradient(listOf(Color(0xFF39323F), ArenaTreeLocked, Color(0xFF39323F)))
    } else {
        Brush.linearGradient(listOf(Color(0xFF6E4D19), accent, Color(0xFF6A4714)))
    }
    Box(
        modifier = modifier
            .clip(ArenaTreeNodeShape)
            .background(Color(0xFF0C0910))
            .border(2.dp, frameBrush, ArenaTreeNodeShape)
            .padding(3.dp)
            .clip(CutCornerShape(5.dp)),
    ) {
        when (node.kind) {
            ArenaSkillTreeNodeKind.ATTACK -> ArenaAttackAtlasArtwork(
                heroClass = heroClass,
                iconIndex = node.iconIndex,
                desaturated = locked,
                modifier = Modifier.matchParentSize(),
            )
            ArenaSkillTreeNodeKind.SUPPORT -> ArenaSupportAtlasArtwork(
                heroClass = heroClass,
                iconIndex = node.iconIndex,
                desaturated = locked,
                modifier = Modifier.matchParentSize(),
            )
        }
        Text(
            if (node.kind == ArenaSkillTreeNodeKind.ATTACK) "ATK" else "SUP",
            modifier = Modifier
                .align(Alignment.BottomStart)
                .background(Color(0xD9100D17), RoundedCornerShape(topEnd = 3.dp))
                .padding(horizontal = 3.dp),
            color = if (locked) AqMuted else AqText,
            fontSize = 7.sp,
            lineHeight = 9.sp,
            fontWeight = FontWeight.Black,
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(20.dp)
                .background(Color(0xEC100D17), CircleShape)
                .border(1.dp, accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = arenaTreeStatusIcon(node.status),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

@Composable
private fun ArenaAttackAtlasArtwork(
    heroClass: HeroClass,
    iconIndex: Int,
    desaturated: Boolean,
    modifier: Modifier,
) {
    val bitmap = ImageBitmap.imageResource(arenaAttackAtlasResource(heroClass))
    val tileIndex = arenaAttackAtlasTileIndex(heroClass, iconIndex)
    val filter = remember(desaturated) {
        if (desaturated) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) else null
    }
    Canvas(modifier) {
        val tileWidth = bitmap.width / 4
        val tileHeight = bitmap.height / 6
        drawImage(
            image = bitmap,
            srcOffset = IntOffset((tileIndex % 4) * tileWidth + 2, (tileIndex / 4) * tileHeight + 2),
            srcSize = IntSize(tileWidth - 4, tileHeight - 4),
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            colorFilter = filter,
        )
    }
}

/** Reuse the unused blue ice paintings for the two mage ice attacks. */
internal fun arenaAttackAtlasTileIndex(heroClass: HeroClass, iconIndex: Int): Int =
    if (heroClass == HeroClass.MAGE) {
        when (iconIndex) {
            1 -> 20 // 얼음 창: crystal burst
            6 -> 21 // 서리 폭풍: ice vortex
            else -> iconIndex
        }
    } else {
        iconIndex
    }

@Composable
private fun ArenaSupportAtlasArtwork(
    heroClass: HeroClass,
    iconIndex: Int,
    desaturated: Boolean,
    modifier: Modifier,
) {
    val bitmap = ImageBitmap.imageResource(arenaSupportAtlasResource(heroClass))
    val filter = remember(desaturated) {
        if (desaturated) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) else null
    }
    val tileIndex = arenaSupportAtlasTileIndex(heroClass, iconIndex)
    Canvas(modifier) {
        val tileWidth = bitmap.width / 4
        val tileHeight = bitmap.height / 3
        drawImage(
            image = bitmap,
            srcOffset = IntOffset((tileIndex % 4) * tileWidth + 2, (tileIndex / 4) * tileHeight + 2),
            srcSize = IntSize(tileWidth - 4, tileHeight - 4),
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            colorFilter = filter,
        )
    }
}

@Composable
private fun ArenaSkillNodeOverlay(
    model: ArenaSkillTreeUiModel,
    node: ArenaSkillTreeNodeUiModel,
    language: AppLanguage,
    onDismiss: () -> Unit,
    onRankUp: () -> Unit,
    onResetSkill: (() -> Unit)?,
) {
    val rankUpEnabled = arenaSkillTreeRankUpEnabled(model, node)
    val actionLabel = arenaSkillTreeActionLabel(model, node, language)
    val reachedBonus = node.rankBonuses.firstOrNull {
        it.rank == node.rank && it.status == ArenaSkillRankBonusStatus.ACTIVE
    }
    val rankAnnouncement = buildString {
        append(arenaTreeCopy(
            language,
            "${arenaTreeKindLabel(node.kind, language)} · 랭크 ${node.rank}/${node.maxRank} · ${arenaTreeStatusLabel(node.status, language)}",
            "${arenaTreeKindLabel(node.kind, language)} · Rank ${node.rank}/${node.maxRank} · ${arenaTreeStatusLabel(node.status, language)}",
            "${arenaTreeKindLabel(node.kind, language)}・ランク${node.rank}/${node.maxRank}・${arenaTreeStatusLabel(node.status, language)}",
        ))
        reachedBonus?.let { bonus ->
            append(arenaTreeCopy(language, ". ${bonus.title} 보너스 활성: ", ". ${bonus.title} bonus active: ", "。${bonus.title}ボーナス有効: "))
            append(bonus.effect)
        }
    }
    ArenaSkillTreeModalLayer(
        paneTitleText = arenaTreeCopy(language, "스킬 상세", "Skill details", "スキル詳細"),
        onDismiss = onDismiss,
    ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 380.dp)
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .testTag("arena-node-dialog")
                    .semantics {
                        paneTitle = arenaTreeCopy(language, "스킬 상세", "Skill details", "スキル詳細")
                    },
                color = Color(0xFF1B1423),
                shape = CutCornerShape(14.dp),
                border = BorderStroke(2.dp, AqGoldSoft),
                shadowElevation = 18.dp,
            ) {
                Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    ArenaSkillNodeArtwork(node = node, heroClass = model.heroClass, modifier = Modifier.size(64.dp))
                    Column(
                        modifier = Modifier.weight(1f).padding(start = 12.dp, top = 3.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            node.name,
                            modifier = Modifier.semantics { heading() },
                            color = AqText,
                            fontSize = 19.sp,
                            lineHeight = 23.sp,
                            fontWeight = FontWeight.Black,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${arenaTreeKindLabel(node.kind, language)} · ${arenaTreeCopy(language, "랭크", "Rank", "ランク")} ${node.rank}/${node.maxRank} · ${arenaTreeStatusLabel(node.status, language)}",
                            modifier = Modifier.semantics {
                                liveRegion = LiveRegionMode.Polite
                                contentDescription = rankAnnouncement
                            },
                            color = arenaTreeStatusColor(node.status),
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(48.dp).testTag("arena-node-dialog-close"),
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = arenaTreeCopy(language, "닫기", "Close", "閉じる"),
                            tint = AqMuted,
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .padding(top = 14.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Text(
                        node.timing.ifBlank { "-" },
                        color = AqGold,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    ArenaSkillDetailLine(
                        label = arenaTreeCopy(language, "현재 효과", "Current", "現在効果"),
                        value = node.currentEffect.ifBlank { arenaTreeCopy(language, "미습득", "Not learned", "未習得") },
                        maxLines = Int.MAX_VALUE,
                    )
                    ArenaSkillDetailLine(
                        label = arenaTreeCopy(language, "다음 효과", "Next", "次の効果"),
                        value = node.nextEffect.ifBlank { if (node.rank >= node.maxRank) "MAX" else "-" },
                        maxLines = Int.MAX_VALUE,
                    )
                    if (node.rankBonuses.isNotEmpty()) {
                        ArenaSkillRankBonusSection(
                            bonuses = node.rankBonuses,
                            language = language,
                        )
                    }
                    if (node.requirementRows.isNotEmpty()) {
                        node.requirementRows.forEach { requirement ->
                            ArenaSkillRequirementLine(
                                requirement = requirement,
                                language = language,
                            )
                        }
                    } else if (node.requirement.isNotBlank()) {
                        ArenaSkillDetailLine(
                            label = arenaTreeCopy(language, "조건", "Condition", "条件"),
                            value = node.requirement,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("arena-node-dialog-dismiss"),
                        shape = CutCornerShape(8.dp),
                        border = BorderStroke(1.dp, AqGoldSoft),
                    ) {
                        Text(arenaTreeCopy(language, "닫기", "Close", "閉じる"), color = AqText, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = onRankUp,
                        enabled = rankUpEnabled,
                        modifier = Modifier
                            .weight(1.35f)
                            .heightIn(min = 48.dp)
                            .testTag("arena-node-rank-up"),
                        shape = CutCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AqGold,
                            contentColor = ArenaTreeBackdrop,
                            disabledContainerColor = AqGoldSoft.copy(alpha = 0.18f),
                            disabledContentColor = AqMuted,
                        ),
                    ) {
                        Text(
                            actionLabel,
                            fontSize = 12.sp,
                            lineHeight = 15.sp,
                            fontWeight = FontWeight.Black,
                            maxLines = 2,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                if (node.rank > 0 && onResetSkill != null) {
                    OutlinedButton(
                        onClick = onResetSkill,
                        enabled = model.unlocked && model.editingEnabled && node.canReset,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                            .heightIn(min = 48.dp).testTag("arena-node-reset"),
                        shape = CutCornerShape(8.dp),
                        border = BorderStroke(1.dp, AqGoldSoft.copy(alpha = 0.65f)),
                    ) {
                        Text(
                            arenaSkillResetLabel(node.rank, language),
                            color = if (node.canReset) AqGoldSoft else AqMuted,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                        )
                    }
                    if (node.resetBlockedMessage.isNotBlank()) {
                        Text(
                            node.resetBlockedMessage,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            color = AqMuted, fontSize = 11.sp, lineHeight = 15.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                }
            }
    }
}

@Composable
private fun ArenaSkillRankBonusSection(
    bonuses: List<ArenaSkillRankBonusUiModel>,
    language: AppLanguage,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AqSurfaceHigh.copy(alpha = 0.72f), RoundedCornerShape(7.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp)
            .testTag("arena-node-rank-bonuses"),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(
            arenaTreeCopy(language, "랭크 보너스", "Rank bonuses", "ランクボーナス"),
            modifier = Modifier.semantics { heading() },
            color = AqMuted,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.Bold,
        )
        bonuses.forEach { bonus ->
            val active = bonus.status == ArenaSkillRankBonusStatus.ACTIVE
            val stateLabel = if (active) {
                arenaTreeCopy(language, "활성", "Active", "有効")
            } else {
                arenaTreeCopy(language, "잠금", "Locked", "未解放")
            }
            val rankLabel = arenaTreeCopy(language, "${bonus.rank}랭크", "Rank ${bonus.rank}", "ランク${bonus.rank}")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("arena-node-rank-bonus-${bonus.rank}")
                    .clearAndSetSemantics {
                        contentDescription = "$rankLabel, ${bonus.title}, $stateLabel, ${bonus.effect}"
                    },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (active) Icons.Filled.CheckCircle else Icons.Filled.Lock,
                    contentDescription = null,
                    tint = if (active) ArenaTreeMaximum else ArenaTreeLocked,
                    modifier = Modifier.size(18.dp),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        "$rankLabel · ${bonus.title}",
                        color = if (active) ArenaTreeMaximum else AqMuted,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        bonus.effect,
                        color = if (active) AqText else AqMuted,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = Int.MAX_VALUE,
                    )
                }
                Text(
                    stateLabel,
                    color = if (active) ArenaTreeMaximum else ArenaTreeLocked,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun ArenaSkillDetailLine(label: String, value: String, maxLines: Int = 2) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AqSurfaceHigh.copy(alpha = 0.72f), RoundedCornerShape(7.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.width(82.dp),
            color = AqMuted,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            color = AqText,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ArenaSkillRequirementLine(
    requirement: ArenaSkillRequirementUiModel,
    language: AppLanguage,
) {
    val stateLabel = if (requirement.satisfied) {
        arenaTreeCopy(language, "충족", "Met", "達成")
    } else {
        arenaTreeCopy(language, "미충족", "Not met", "未達")
    }
    val stateColor = if (requirement.satisfied) ArenaTreeMaximum else ArenaTreeLocked
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AqSurfaceHigh.copy(alpha = 0.72f), RoundedCornerShape(7.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp)
            .clearAndSetSemantics {
                contentDescription = "${requirement.label}, ${requirement.value}, $stateLabel"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            requirement.label,
            modifier = Modifier.width(82.dp),
            color = AqMuted,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
        Text(
            requirement.value,
            modifier = Modifier.weight(1f),
            color = if (requirement.satisfied) AqText else AqMuted,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = if (requirement.satisfied) Icons.Filled.CheckCircle else Icons.Filled.Lock,
                contentDescription = null,
                tint = stateColor,
                modifier = Modifier.size(17.dp),
            )
            Text(
                stateLabel,
                color = stateColor,
                fontSize = 9.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

private fun arenaTreeStatusColor(status: ArenaSkillTreeNodeStatus): Color = when (status) {
    ArenaSkillTreeNodeStatus.LOCKED -> ArenaTreeLocked
    ArenaSkillTreeNodeStatus.UNAVAILABLE -> AqGoldSoft
    ArenaSkillTreeNodeStatus.AVAILABLE -> ArenaTreeAvailable
    ArenaSkillTreeNodeStatus.INVESTED -> ArenaTreeInvested
    ArenaSkillTreeNodeStatus.MAX -> ArenaTreeMaximum
}

private fun arenaTreeConnectorColor(status: ArenaSkillTreeNodeStatus): Color = when (status) {
    ArenaSkillTreeNodeStatus.LOCKED -> ArenaTreeLineLocked
    ArenaSkillTreeNodeStatus.UNAVAILABLE -> ArenaTreeAvailable.copy(alpha = 0.38f)
    ArenaSkillTreeNodeStatus.AVAILABLE -> ArenaTreeAvailable.copy(alpha = 0.75f)
    ArenaSkillTreeNodeStatus.INVESTED -> ArenaTreeInvested
    ArenaSkillTreeNodeStatus.MAX -> ArenaTreeMaximum
}

private fun arenaTreeStatusIcon(status: ArenaSkillTreeNodeStatus): ImageVector = when (status) {
    ArenaSkillTreeNodeStatus.LOCKED -> Icons.Filled.Lock
    ArenaSkillTreeNodeStatus.UNAVAILABLE -> Icons.Filled.Lock
    ArenaSkillTreeNodeStatus.AVAILABLE -> Icons.Filled.AddCircle
    ArenaSkillTreeNodeStatus.INVESTED -> Icons.Filled.CheckCircle
    ArenaSkillTreeNodeStatus.MAX -> Icons.Filled.EmojiEvents
}

private fun arenaAttackAtlasResource(heroClass: HeroClass): Int = when (heroClass) {
    HeroClass.WARRIOR -> R.drawable.talent_icons_warrior_v2
    HeroClass.ROGUE -> R.drawable.talent_icons_rogue_v2
    HeroClass.RANGER -> R.drawable.talent_icons_ranger_v2
    HeroClass.MAGE -> R.drawable.talent_icons_mage_v2
    HeroClass.CLERIC -> R.drawable.talent_icons_cleric_v2
    HeroClass.PALADIN -> R.drawable.talent_icons_paladin_v2
}

internal fun arenaSupportAtlasResource(heroClass: HeroClass): Int = when (heroClass) {
    HeroClass.WARRIOR -> R.drawable.arena_support_icons_warrior_v1
    HeroClass.ROGUE -> R.drawable.arena_support_icons_rogue_v1
    HeroClass.RANGER -> R.drawable.arena_support_icons_ranger_v1
    HeroClass.MAGE -> R.drawable.arena_support_icons_mage_v1
    HeroClass.CLERIC -> R.drawable.arena_support_icons_cleric_v1
    HeroClass.PALADIN -> R.drawable.arena_support_icons_paladin_v1
}

/**
 * S01–S10 are ordered by unlock level. The Warrior sheet was authored in conceptual order, so
 * its stable lookup keeps the intended picture attached to the same support skill.
 */
internal fun arenaSupportAtlasTileIndex(heroClass: HeroClass, iconIndex: Int): Int {
    require(iconIndex in 0 until SUPPORT_ATLAS_VISIBLE_TILE_COUNT)
    return if (heroClass == HeroClass.WARRIOR) {
        listOf(0, 1, 5, 7, 6, 2, 3, 4, 9, 8)[iconIndex]
    } else {
        iconIndex
    }
}

private fun ArenaSkillTreeNodeDefinition.arenaTreeName(language: AppLanguage): String = when (language) {
    AppLanguage.KOREAN -> nameKo
    AppLanguage.ENGLISH -> nameEn
    AppLanguage.JAPANESE -> nameJa
}

private fun arenaTreeRankBonuses(
    definition: ArenaSkillTreeNodeDefinition,
    currentRank: Int,
    language: AppLanguage,
): List<ArenaSkillRankBonusUiModel> {
    val languageCode = arenaTreeLanguageCode(language)
    return listOf(5,10).map { milestoneRank ->
        val effect = ArenaIdentityCopy.milestone(definition.id, milestoneRank, languageCode)
        ArenaSkillRankBonusUiModel(
            rank = milestoneRank,
            title = if(milestoneRank==5) arenaTreeCopy(language, "숙련", "Adept", "熟練")
                else arenaTreeCopy(language, "마스터", "Master", "マスター"),
            effect = effect,
            status = if (currentRank >= milestoneRank) {
                ArenaSkillRankBonusStatus.ACTIVE
            } else {
                ArenaSkillRankBonusStatus.LOCKED
            },
        )
    }
}

private fun arenaTreeLanguageCode(language: AppLanguage): String = when (language) {
    AppLanguage.KOREAN -> "ko"
    AppLanguage.ENGLISH -> "en"
    AppLanguage.JAPANESE -> "ja"
}

private fun arenaTreeTiming(node: ArenaSkillTreeNodeView, language: AppLanguage): String =
    ArenaIdentityCopy.timing(node.definition.id, node.rank.coerceAtLeast(1),
        arenaTreeLanguageCode(language), node.rank.takeIf { it in 1 until node.definition.maxRank }?.plus(1))

private fun arenaTreeEnglishTurns(turns: Int): String = if (turns == 1) "turn" else "turns"

private fun arenaTreeEffect(node: ArenaSkillTreeNodeView, rank: Int, language: AppLanguage): String =
    ArenaIdentityCopy.effect(node.definition.id, rank, arenaTreeLanguageCode(language))

private fun arenaTreeNextEffect(node: ArenaSkillTreeNodeView, rank: Int, language: AppLanguage): String =
    arenaTreeEffect(node, rank, language)

internal fun arenaSkillTreeRequirementRows(
    node: ArenaSkillTreeNodeView,
    definitionsById: Map<String, ArenaSkillTreeNodeDefinition>,
    ranksById: Map<String, Int>,
    spentBeforeRow: Int,
    language: AppLanguage,
): List<ArenaSkillRequirementUiModel> {
    val definition = node.definition
    if (node.lockReason == "rank_max") return emptyList()
    if (node.lockReason == "invalid_state") {
        return listOf(ArenaSkillRequirementUiModel(
            kind = ArenaSkillRequirementKind.STATE,
            label = arenaTreeCopy(language, "상태", "Status", "状態"),
            value = arenaTreeCopy(
                language,
                "스킬 설정 상태를 확인하세요",
                "Check the skill setup state",
                "スキル設定の状態を確認",
            ),
            satisfied = false,
        ))
    }

    val rows = mutableListOf<ArenaSkillRequirementUiModel>()
    if (node.lockReason == "attack_not_owned") {
        rows += ArenaSkillRequirementUiModel(
            kind = ArenaSkillRequirementKind.OWNERSHIP,
            label = arenaTreeCopy(language, "보유 조건", "Ownership", "習得条件"),
            value = arenaTreeCopy(
                language,
                "본게임에서 해당 공격 스킬 보유 필요",
                "Own the matching main-game attack skill",
                "本編で対応する攻撃スキルを習得",
            ),
            satisfied = false,
        )
    }

    // Parent-rank and earlier-tier spend gates only govern the first point. Once learned, showing
    // them again makes the popup look locked even though the player is simply raising its rank.
    if (node.rank == 0 && !definition.isRoot) {
        val parentNames = definition.parentAnyOf
            .mapNotNull(definitionsById::get)
            .sortedWith(compareBy(ArenaSkillTreeNodeDefinition::row, ArenaSkillTreeNodeDefinition::column))
            .map { it.arenaTreeName(language) }
        val parentRequirement = if (parentNames.size == 1) {
            when (language) {
                AppLanguage.KOREAN -> "${parentNames.single()} ${definition.minParentRank}랭크"
                AppLanguage.ENGLISH -> "${parentNames.single()} at rank ${definition.minParentRank}"
                AppLanguage.JAPANESE -> "${parentNames.single()}をランク${definition.minParentRank}まで習得"
            }
        } else {
            when (language) {
                AppLanguage.KOREAN -> "${parentNames.joinToString("·")} 중 1개를 ${definition.minParentRank}랭크까지 올리기"
                AppLanguage.ENGLISH -> "Raise ${parentNames.joinToString(" or ")} to rank ${definition.minParentRank}"
                AppLanguage.JAPANESE -> "${parentNames.joinToString("・")}のいずれか1つをランク${definition.minParentRank}まで習得"
            }
        }
        rows += ArenaSkillRequirementUiModel(
            kind = ArenaSkillRequirementKind.PREREQUISITE,
            label = arenaTreeCopy(language, "선행 기술", "Prerequisite", "前提スキル"),
            value = parentRequirement,
            satisfied = definition.parentAnyOf.any { parentId ->
                (ranksById[parentId] ?: 0) >= definition.minParentRank
            },
        )

        // Tier 2's three-point gate is already guaranteed by its rank-3 parent.
        if (definition.row > 1 && definition.minimumSpentPoints > 0) {
            rows += ArenaSkillRequirementUiModel(
                kind = ArenaSkillRequirementKind.INVESTMENT,
                label = arenaTreeCopy(language, "투자 조건", "Investment", "投資条件"),
                value = arenaTreeCopy(
                    language,
                    "이전 단계에 총 ${definition.minimumSpentPoints}포인트 투자",
                    "Invest ${definition.minimumSpentPoints} points total in earlier tiers",
                    "前段階に合計${definition.minimumSpentPoints}ポイント投資",
                ),
                satisfied = spentBeforeRow >= definition.minimumSpentPoints,
            )
        }
    }

    return rows
}

private fun arenaSkillTreeRankUpEnabled(
    model: ArenaSkillTreeUiModel,
    node: ArenaSkillTreeNodeUiModel,
): Boolean = model.unlocked && model.editingEnabled && model.availablePoints > 0 &&
    node.canRankUp && node.status != ArenaSkillTreeNodeStatus.LOCKED &&
    node.status != ArenaSkillTreeNodeStatus.UNAVAILABLE &&
    node.status != ArenaSkillTreeNodeStatus.MAX

internal fun arenaSkillTreeActionLabel(
    model: ArenaSkillTreeUiModel,
    node: ArenaSkillTreeNodeUiModel,
    language: AppLanguage,
): String {
    val rankUpEnabled = arenaSkillTreeRankUpEnabled(model, node)
    return when {
        rankUpEnabled && node.rank == 4 -> arenaTreeCopy(
            language, "1포인트 투자 · 숙련", "Invest 1 point · Adept", "1ポイント投資・熟練",
        )
        rankUpEnabled && node.rank == 9 -> arenaTreeCopy(
            language,
            "1포인트 투자 · 마스터",
            "Invest 1 point · Master",
            "1ポイント投資・マスター",
        )
        rankUpEnabled -> arenaTreeCopy(language, "1포인트 투자", "Invest 1 point", "1ポイント投資")
        !model.unlocked -> arenaTreeCopy(language, "해금 전", "Locked", "未解放")
        node.status == ArenaSkillTreeNodeStatus.MAX || node.rank >= node.maxRank -> "MAX"
        !model.editingEnabled -> arenaTreeCopy(language, "대전 중 변경 불가", "Unavailable during duel", "対戦中は変更不可")
        model.availablePoints <= 0 -> arenaTreeCopy(language, "포인트 부족", "No points", "ポイント不足")
        node.status == ArenaSkillTreeNodeStatus.LOCKED -> arenaTreeCopy(language, "조건 미충족", "Requirements not met", "条件未達")
        else -> arenaTreeCopy(language, "조건 미충족", "Requirements not met", "条件未達")
    }
}

internal fun arenaTreeStatusLabel(status: ArenaSkillTreeNodeStatus, language: AppLanguage): String = when (status) {
    ArenaSkillTreeNodeStatus.LOCKED -> arenaTreeCopy(language, "조건 미충족", "Requirements not met", "条件未達")
    ArenaSkillTreeNodeStatus.UNAVAILABLE -> arenaTreeCopy(language, "현재 변경 불가", "Changes unavailable", "現在変更不可")
    ArenaSkillTreeNodeStatus.AVAILABLE -> arenaTreeCopy(language, "투자 가능", "Available", "取得可能")
    ArenaSkillTreeNodeStatus.INVESTED -> arenaTreeCopy(language, "습득함", "Learned", "習得済み")
    ArenaSkillTreeNodeStatus.MAX -> arenaTreeCopy(language, "최대 랭크", "Maximum rank", "最大ランク")
}

private fun arenaTreeKindLabel(kind: ArenaSkillTreeNodeKind, language: AppLanguage): String = when (kind) {
    ArenaSkillTreeNodeKind.ATTACK -> arenaTreeCopy(language, "공격", "Attack", "攻撃")
    ArenaSkillTreeNodeKind.SUPPORT -> arenaTreeCopy(language, "보조", "Support", "補助")
}

internal fun arenaTreeClassName(heroClass: HeroClass, language: AppLanguage): String = when (language) {
    AppLanguage.KOREAN -> heroClass.labelKo
    AppLanguage.ENGLISH -> when (heroClass) {
        HeroClass.WARRIOR -> "Fighter"
        HeroClass.ROGUE -> "Thief"
        HeroClass.RANGER -> "Ranger"
        HeroClass.MAGE -> "Mage"
        HeroClass.CLERIC -> "Cleric"
        HeroClass.PALADIN -> "Paladin"
    }
    AppLanguage.JAPANESE -> when (heroClass) {
        HeroClass.WARRIOR -> "ファイター"
        HeroClass.ROGUE -> "シーフ"
        HeroClass.RANGER -> "レンジャー"
        HeroClass.MAGE -> "メイジ"
        HeroClass.CLERIC -> "クレリック"
        HeroClass.PALADIN -> "パラディン"
    }
}

internal fun arenaSkillTreeReviewClasses(
    currentHeroClass: HeroClass,
    requestedHeroClasses: List<HeroClass>,
): List<HeroClass> {
    if (currentHeroClass !in requestedHeroClasses) return emptyList()
    val requested = requestedHeroClasses.toSet()
    return HeroClass.entries.filter { it in requested }.takeIf { it.size > 1 }.orEmpty()
}

private fun arenaTreeCopy(language: AppLanguage, ko: String, en: String, ja: String): String = when (language) {
    AppLanguage.KOREAN -> ko
    AppLanguage.ENGLISH -> en
    AppLanguage.JAPANESE -> ja
}
