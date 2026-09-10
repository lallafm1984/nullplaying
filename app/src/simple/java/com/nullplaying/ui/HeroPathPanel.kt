package com.nullplaying.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nullplaying.localization.AppLanguage
import com.nullplaying.R
import com.nullplaying.model.HeroPathNodeSlot

/**
 * Trilingual copy owned by the Hero Path feature.
 *
 * The feature deliberately keeps copy next to its UI model until the permanent localization keys
 * are added. Callers can replace [HeroPathCopyResolver] with the app localization boundary without
 * changing any composable or model callback.
 */
@Immutable
internal data class HeroPathCopy(
    val ko: String,
    val en: String,
    val ja: String,
) {
    fun resolve(language: AppLanguage): String = when (language) {
        AppLanguage.KOREAN -> ko
        AppLanguage.ENGLISH -> en
        AppLanguage.JAPANESE -> ja
    }
}

internal typealias HeroPathCopyResolver = (HeroPathCopy, AppLanguage) -> String

internal enum class HeroPathFilter {
    ALL,
    LEARNED,
    AVAILABLE,
}

internal enum class HeroPathNodeStatus {
    LEARNED,
    DRAFT,
    AVAILABLE,
    LOCKED,
    MAX,
}

internal enum class HeroPathNodeKind {
    ACTIVE,
    MODIFIER,
    MATCHUP_TACTIC,
    KEYSTONE,
    CAPSTONE,
}

@Immutable
internal data class HeroPathLaneUiModel(
    val id: String,
    val name: HeroPathCopy,
    val description: HeroPathCopy,
    val accent: Color,
    val icon: ImageVector,
)

@Immutable
internal data class HeroPathMetricUiModel(
    val label: HeroPathCopy,
    val value: HeroPathCopy,
    val accent: Color = AqGold,
)

@Immutable
internal data class HeroPathNodeUiModel(
    val id: String,
    val level: Int,
    val laneId: String,
    val status: HeroPathNodeStatus,
    val kind: HeroPathNodeKind,
    val name: HeroPathCopy,
    val summary: HeroPathCopy,
    val requirement: HeroPathCopy? = null,
    val metrics: List<HeroPathMetricUiModel> = emptyList(),
    val canDraft: Boolean = false,
    val actionBlockReason: HeroPathCopy? = null,
    val currentRank: Int = 0,
    val maxRank: Int = 1,
    val choiceGroupId: String = "",
    val automaticRule: HeroPathCopy? = null,
    val currentEffect: HeroPathCopy? = null,
    val nextEffect: HeroPathCopy? = null,
    val slot: HeroPathNodeSlot = HeroPathNodeSlot.FOUNDATION_A,
    val shortName: HeroPathCopy = name,
)

@Immutable
internal data class HeroPathArenaEntryUiModel(
    val specialization: HeroPathCopy,
    val coreTrait: HeroPathCopy?,
    val spentPoints: Long,
    val unspentPoints: Long,
)

@Immutable
internal data class HeroPathChoiceOptionUiModel(
    val node: HeroPathNodeUiModel,
    val icon: ImageVector,
)

@Immutable
internal data class HeroPathChoiceUiModel(
    val eventId: String,
    val milestoneLevel: Int,
    val queuePosition: Int,
    val queueSize: Int,
    val unspentPoints: Long = 0L,
    val options: List<HeroPathChoiceOptionUiModel>,
) {
    init {
        require(options.size == 3) { "Hero Path choice must expose exactly three options" }
        require(queuePosition in 1..queueSize.coerceAtLeast(1))
    }
}

@Immutable
internal data class HeroPathPanelUiModel(
    val heroName: String,
    val heroClassName: HeroPathCopy,
    val heroLevel: Int,
    val learnedCount: Int,
    val totalChoiceCount: Int,
    val pendingChoiceCount: Int,
    val earnedPoints: Long = 0L,
    val spentPoints: Long = 0L,
    val unspentPoints: Long = 0L,
    val draftCount: Int = 0,
    val selectedFilter: HeroPathFilter,
    val lanes: List<HeroPathLaneUiModel>,
    val nodes: List<HeroPathNodeUiModel>,
) {
    init {
        require(lanes.size == 3) { "Tactical Hero Path requires exactly three lanes" }
    }
}

/**
 * Full-screen specialization tabs and a single connected investment-tier tree.
 *
 * This component owns presentation only. Selection, persistence, navigation, and locked-node
 * explanations stay in the caller through explicit callbacks.
 */
@Composable
internal fun HeroPathPanel(
    model: HeroPathPanelUiModel,
    onBack: () -> Unit,
    onFilterSelected: (HeroPathFilter) -> Unit,
    onNodeSelected: (HeroPathNodeUiModel) -> Unit,
    onReviewDraft: () -> Unit,
    onResetSelected: () -> Unit,
    editingEnabled: Boolean,
    modifier: Modifier = Modifier,
    copyResolver: HeroPathCopyResolver = { copy, language -> copy.resolve(language) },
) {
    val language = LocalAppLanguage.current
    val copy: (HeroPathCopy) -> String = { value -> copyResolver(value, language) }
    var selectedLaneId by rememberSaveable(model.heroClassName.en) { mutableStateOf(model.lanes.first().id) }
    val lane = model.lanes.firstOrNull { it.id == selectedLaneId } ?: model.lanes.first()
    val laneNodes = model.nodes.filter { it.laneId == lane.id }
    LaunchedEffect(model.selectedFilter) {
        if (model.selectedFilter != HeroPathFilter.ALL) onFilterSelected(HeroPathFilter.ALL)
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = HeroPathBackground,
        contentColor = AqText,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Image(
                painter = painterResource(R.drawable.talent_tree_backdrop_v2),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    bottom = if (model.draftCount > 0) 116.dp else 28.dp,
                ),
            ) {
            item(key = "hero-path-header") {
                HeroPathHeader(
                    model = model,
                    copy = copy,
                    onBack = onBack,
                    onResetSelected = onResetSelected,
                    editingEnabled = editingEnabled,
                )
            }
            item(key = "hero-path-lanes") {
                HeroPathSpecializationTabs(model.lanes, lane.id, copy) { selectedLaneId = it }
            }
            item(key = "hero-path-tree-${lane.id}") {
                HeroPathConnectedTree(
                    lane = lane,
                    nodes = laneNodes,
                    heroLevel = model.heroLevel,
                    copy = copy,
                    onNodeSelected = onNodeSelected,
                )
            }
            if (laneNodes.isEmpty()) {
                item(key = "hero-path-empty") {
                    HeroPathEmptyState(HeroPathFilter.ALL, copy)
                }
            }
            }
            if (model.draftCount > 0) {
                HeroPathDraftBar(
                    model = model,
                    copy = copy,
                    onReviewDraft = onReviewDraft,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

@Composable
private fun HeroPathHeader(
    model: HeroPathPanelUiModel,
    copy: (HeroPathCopy) -> String,
    onBack: () -> Unit,
    onResetSelected: () -> Unit,
    editingEnabled: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xA80B0910))
            .padding(top = 2.dp, bottom = 3.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(48.dp)
                    .semantics {
                        contentDescription = copy(HeroPathCopy("뒤로", "Back", "戻る"))
                    },
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = AqGold,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                UnlocalizedText(
                    text = copy(HeroPathCopy("결투 스킬트리", "Duel Skill Tree", "決闘スキルツリー")),
                    color = HeroPathTitleGold,
                    fontSize = 21.sp,
                    lineHeight = 27.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
            }
            IconButton(
                onClick = onResetSelected,
                enabled = editingEnabled && model.spentPoints > 0L,
                modifier = Modifier
                    .size(48.dp)
                    .semantics {
                        contentDescription = copy(
                            HeroPathCopy("전체 초기화", "Reset All Traits", "全特性をリセット"),
                        )
                    },
            ) {
                Icon(
                    imageVector = Icons.Filled.RestartAlt,
                    contentDescription = null,
                    tint = if (editingEnabled && model.spentPoints > 0L) AqGold else AqMuted,
                )
            }
        }
        HorizontalDivider(color = HeroPathDivider)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(AqGold.copy(alpha = 0.08f), RoundedCornerShape(7.dp))
                    .border(1.dp, AqGoldSoft, RoundedCornerShape(7.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Shield, contentDescription = null, tint = AqGold)
            }
            Spacer(Modifier.width(11.dp))
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                UnlocalizedText(
                    text = model.heroName,
                    color = AqText,
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                UnlocalizedText(
                    text = "${copy(model.heroClassName)} · Lv.${model.heroLevel}",
                    color = AqMuted,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            val summary = copy(
                HeroPathCopy(
                    "미사용 ${model.unspentPoints}",
                    "Unspent ${model.unspentPoints}",
                    "未使用 ${model.unspentPoints}",
                ),
            )
            Surface(
                shape = RoundedCornerShape(7.dp),
                color = if (model.unspentPoints > 0L) AqGold.copy(alpha = 0.12f) else Color.Transparent,
                border = if (model.unspentPoints > 0L) BorderStroke(1.dp, AqGoldSoft) else null,
                modifier = Modifier
                    .heightIn(min = 32.dp)
                    .semantics {
                        contentDescription = summary
                    },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    UnlocalizedText(
                        text = summary,
                        color = if (model.unspentPoints > 0L) AqGold else AqMuted,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
        HorizontalDivider(color = HeroPathDivider)
    }
}

@Composable
private fun HeroPathSpecializationTabs(
    lanes: List<HeroPathLaneUiModel>,
    selectedLaneId: String,
    copy: (HeroPathCopy) -> String,
    onSelected: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        lanes.forEach { lane ->
            val active = lane.id == selectedLaneId
            Surface(
                onClick = { onSelected(lane.id) },
                modifier = Modifier.weight(1f).heightIn(min = 44.dp).semantics {
                    role = Role.Tab
                    selected = active
                    contentDescription = copy(lane.name)
                },
                color = if (active) lane.accent.copy(alpha = 0.12f) else Color(0x600D0A12),
                shape = RoundedCornerShape(7.dp),
                border = BorderStroke(1.dp, if (active) lane.accent else HeroPathDivider),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(lane.icon, null, tint = if (active) lane.accent else AqMuted, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    UnlocalizedText(
                        copy(lane.name), color = if (active) lane.accent else AqMuted,
                        fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** The spine represents investment tiers, not invented per-node prerequisites. */
@Composable
private fun HeroPathConnectedTree(
    lane: HeroPathLaneUiModel,
    nodes: List<HeroPathNodeUiModel>,
    heroLevel: Int,
    copy: (HeroPathCopy) -> String,
    onNodeSelected: (HeroPathNodeUiModel) -> Unit,
) {
    val points = nodes.sumOf { it.currentRank + if (it.status == HeroPathNodeStatus.DRAFT) 1 else 0 }
    val tiers = listOf(
        Triple(5, 0, listOf(listOf(HeroPathNodeSlot.FOUNDATION_A, HeroPathNodeSlot.FOUNDATION_B))),
        Triple(20, 3, listOf(listOf(HeroPathNodeSlot.CHOICE_A, HeroPathNodeSlot.CHOICE_B), listOf(HeroPathNodeSlot.SPECIAL_A))),
        Triple(35, 6, listOf(listOf(HeroPathNodeSlot.SPECIAL_B, HeroPathNodeSlot.ADVANCED_TACTIC))),
        Triple(50, 9, listOf(listOf(HeroPathNodeSlot.CORE))),
    )
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        tiers.forEachIndexed { index, (level, gate, rows) ->
            val tierReady = heroLevel >= level && points >= gate
            val pathColor = AqGold.copy(alpha = if (tierReady) 0.85f else 0.42f)
            if (index > 0) {
                HeroPathTierBridge(
                    fromCount = tiers[index - 1].third.last().size,
                    toCount = rows.first().size,
                    color = pathColor,
                    modifier = Modifier.fillMaxWidth().padding(start = 58.dp).height(16.dp),
                )
            }
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Row(Modifier.width(58.dp).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(1.dp).fillMaxHeight().background(pathColor))
                    Column(Modifier.padding(start = 6.dp, end = 2.dp)) {
                        UnlocalizedText(
                            copy(HeroPathCopy("${index + 1}단계", "Tier ${index + 1}", "第${index + 1}段階")),
                            color = if (tierReady) HeroPathTitleGold else AqMuted,
                            fontSize = 10.sp, lineHeight = 15.sp, fontWeight = FontWeight.Bold,
                        )
                        UnlocalizedText("Lv.$level", color = AqMuted, fontSize = 10.sp, lineHeight = 15.sp)
                        UnlocalizedText(
                            copy(HeroPathCopy("투자 $gate", "$gate points", "投資$gate")),
                            color = if (points >= gate) AqGoldSoft else AqMuted,
                            fontSize = 9.sp, lineHeight = 14.sp,
                        )
                    }
                }
                Column(Modifier.weight(1f).padding(vertical = 2.dp)) {
                    rows.forEach { slots ->
                        Box(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.Top,
                            ) {
                                slots.mapNotNull { slot -> nodes.firstOrNull { it.slot == slot } }.forEach { node ->
                                    HeroPathArtNode(node, lane, copy, onClick = { onNodeSelected(node) })
                                }
                            }
                            if (slots.firstOrNull() == HeroPathNodeSlot.CHOICE_A) {
                                UnlocalizedText(
                                    copy(HeroPathCopy("택 1", "OR", "選択")),
                                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 22.dp),
                                    color = AqGoldSoft, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        }
        UnlocalizedText(
            copy(HeroPathCopy("연결선은 분야 투자 단계를 나타냅니다.", "Connections show specialization investment tiers.", "接続線は専門への投資段階を示します。")),
            color = AqMuted, fontSize = 9.sp, lineHeight = 13.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 3.dp), textAlign = TextAlign.Center,
        )
    }
}

/** Joins investment-tier groups collectively; no connector represents a node prerequisite. */
@Composable
private fun HeroPathTierBridge(fromCount: Int, toCount: Int, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 1.dp.toPx()
        val center = size.width / 2f
        val top = size.height * 0.28f
        val bottom = size.height * 0.72f
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
            drawLine(color, Offset(x1, y1), Offset(x2, y2), stroke, StrokeCap.Round)
        }
        val nodeWidth = 112.dp.toPx()
        val pairCenter = ((size.width - 2 * nodeWidth) / 3f).coerceAtLeast(0f) + nodeWidth / 2f
        val pairCenters = listOf(pairCenter, size.width - pairCenter)
        val upper = if (fromCount == 1) listOf(center) else pairCenters
        val lower = if (toCount == 1) listOf(center) else pairCenters
        upper.forEach { x -> line(x, 0f, x, top); line(x, top, center, top) }
        line(center, top, center, bottom)
        lower.forEach { x -> line(center, bottom, x, bottom); line(x, bottom, x, size.height) }
    }
}

@Composable
private fun HeroPathArtNode(
    node: HeroPathNodeUiModel,
    lane: HeroPathLaneUiModel,
    copy: (HeroPathCopy) -> String,
    onClick: () -> Unit,
) {
    val locked = node.status == HeroPathNodeStatus.LOCKED
    val learned = node.currentRank > 0
    val draft = node.status == HeroPathNodeStatus.DRAFT
    val accent = if (locked) HeroPathLocked else AqGold
    Column(
        modifier = Modifier.width(112.dp).heightIn(min = 92.dp)
            .clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = heroPathNodeAccessibilityDescription(node, copy)
                stateDescription = copy(node.status.label)
                selected = learned || draft
            }.padding(vertical = 3.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(62.dp), contentAlignment = Alignment.Center) {
            HeroPathTalentArtwork(
                nodeId = node.id,
                modifier = Modifier.size(56.dp).clip(CircleShape).border(if (draft) 2.dp else 1.dp, accent, CircleShape),
                desaturated = locked,
            )
            if (locked || learned || draft) {
                Box(
                    Modifier.align(Alignment.TopEnd).size(19.dp)
                        .background(Color(0xFF17131B), CircleShape).border(1.dp, accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (locked) Icons.Filled.Lock else if (draft) Icons.Filled.PushPin else Icons.Filled.CheckCircle,
                        contentDescription = null, tint = accent, modifier = Modifier.size(13.dp),
                    )
                }
            }
        }
        UnlocalizedText(
            copy(node.shortName), color = if (locked) AqMuted else AqText,
            fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
        UnlocalizedText(
            "${node.currentRank + if (draft) 1 else 0}/${node.maxRank}",
            color = accent, fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun HeroPathFilterBar(
    selectedFilter: HeroPathFilter,
    copy: (HeroPathCopy) -> String,
    onFilterSelected: (HeroPathFilter) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AqBackground)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HeroPathFilter.entries.forEach { filter ->
            val selected = selectedFilter == filter
            val label = copy(filter.label)
            OutlinedButton(
                onClick = { onFilterSelected(filter) },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .semantics {
                        role = Role.Button
                        this.selected = selected
                        stateDescription = copy(
                            if (selected) {
                                HeroPathCopy("선택됨", "Selected", "選択中")
                            } else {
                                HeroPathCopy("선택 안 됨", "Not selected", "未選択")
                            },
                        )
                    },
                shape = RoundedCornerShape(11.dp),
                border = BorderStroke(1.dp, if (selected) AqGold else HeroPathDivider),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (selected) AqGold.copy(alpha = 0.12f) else Color.Transparent,
                    contentColor = if (selected) AqGold else AqMuted,
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 8.dp),
            ) {
                UnlocalizedText(
                    text = label,
                    color = if (selected) AqGold else AqMuted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun HeroPathLaneHeaders(
    lanes: List<HeroPathLaneUiModel>,
    copy: (HeroPathCopy) -> String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(HeroPathBackground)
            .padding(start = HeroPathLevelRailWidth, end = 12.dp, top = 12.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(HeroPathLaneGap),
        verticalAlignment = Alignment.Top,
    ) {
        lanes.forEach { lane ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 112.dp)
                    .background(lane.accent.copy(alpha = 0.07f), RoundedCornerShape(18.dp))
                    .border(1.dp, lane.accent.copy(alpha = 0.25f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 7.dp, vertical = 11.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "${copy(lane.name)}. ${copy(lane.description)}"
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(lane.accent.copy(alpha = 0.16f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(lane.icon, contentDescription = null, tint = lane.accent, modifier = Modifier.size(21.dp))
                }
                Spacer(Modifier.height(7.dp))
                UnlocalizedText(
                    text = copy(lane.name),
                    color = lane.accent,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(3.dp))
                UnlocalizedText(
                    text = copy(lane.description),
                    color = AqMuted,
                    fontSize = 9.sp,
                    lineHeight = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun HeroPathLevelRow(
    level: Int,
    lanes: List<HeroPathLaneUiModel>,
    nodes: List<HeroPathNodeUiModel>,
    copy: (HeroPathCopy) -> String,
    onNodeSelected: (HeroPathNodeUiModel) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        HeroPathLevelBadge(level, copy)
        Spacer(Modifier.width(8.dp))
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(HeroPathLaneGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            lanes.forEach { lane ->
                val laneNodes = nodes.filter { it.laneId == lane.id }
                if (laneNodes.isEmpty()) {
                    Spacer(Modifier.weight(1f))
                } else {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        laneNodes.forEachIndexed { index, node ->
                            val previous = laneNodes.getOrNull(index - 1)
                            if (
                                previous != null &&
                                node.choiceGroupId.isNotBlank() &&
                                node.choiceGroupId == previous.choiceGroupId
                            ) {
                                UnlocalizedText(
                                    text = copy(HeroPathCopy("택 1", "Choose 1", "1つ選択")),
                                    color = lane.accent,
                                    fontSize = 8.sp,
                                    lineHeight = 10.sp,
                                    fontWeight = FontWeight.Black,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            HeroPathNodeCard(
                                node = node,
                                lane = lane,
                                copy = copy,
                                onClick = { onNodeSelected(node) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroPathLevelBadge(
    level: Int,
    copy: (HeroPathCopy) -> String,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .background(AqSurface, RoundedCornerShape(12.dp))
            .border(1.dp, AqGoldSoft.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .semantics {
                contentDescription = copy(
                    HeroPathCopy("레벨 $level", "Level $level", "レベル$level"),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        UnlocalizedText(
            text = level.toString(),
            color = AqText,
            fontSize = 14.sp,
            fontWeight = FontWeight.Black,
        )
    }
}

@Composable
private fun HeroPathNodeCard(
    node: HeroPathNodeUiModel,
    lane: HeroPathLaneUiModel,
    copy: (HeroPathCopy) -> String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusCopy = node.status.label
    val statusText = copy(statusCopy)
    val stateColor = when (node.status) {
        HeroPathNodeStatus.LEARNED -> lane.accent
        HeroPathNodeStatus.DRAFT -> AqGold
        HeroPathNodeStatus.AVAILABLE -> AqGold
        HeroPathNodeStatus.LOCKED -> HeroPathLocked
        HeroPathNodeStatus.MAX -> lane.accent
    }
    val icon = when (node.status) {
        HeroPathNodeStatus.LEARNED -> Icons.Filled.CheckCircle
        HeroPathNodeStatus.DRAFT -> Icons.Filled.PushPin
        HeroPathNodeStatus.AVAILABLE -> Icons.Filled.AutoAwesome
        HeroPathNodeStatus.LOCKED -> Icons.Filled.Lock
        HeroPathNodeStatus.MAX -> Icons.Filled.EmojiEvents
    }
    val description = heroPathNodeAccessibilityDescription(node, copy)
    Surface(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 96.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = description
                stateDescription = statusText
                selected = node.status == HeroPathNodeStatus.LEARNED ||
                    node.status == HeroPathNodeStatus.DRAFT || node.status == HeroPathNodeStatus.MAX
            },
        shape = RoundedCornerShape(18.dp),
        color = when (node.status) {
            HeroPathNodeStatus.LEARNED -> lane.accent.copy(alpha = 0.12f)
            HeroPathNodeStatus.DRAFT -> AqGold.copy(alpha = 0.16f)
            HeroPathNodeStatus.AVAILABLE -> AqGold.copy(alpha = 0.09f)
            HeroPathNodeStatus.LOCKED -> AqSurface.copy(alpha = 0.72f)
            HeroPathNodeStatus.MAX -> lane.accent.copy(alpha = 0.18f)
        },
        border = BorderStroke(
            width = if (node.status == HeroPathNodeStatus.AVAILABLE || node.status == HeroPathNodeStatus.DRAFT) 2.dp else 1.dp,
            color = stateColor.copy(alpha = if (node.status == HeroPathNodeStatus.LOCKED) 0.45f else 0.9f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, tint = stateColor, modifier = Modifier.size(25.dp))
            Spacer(Modifier.height(5.dp))
            UnlocalizedText(
                text = copy(node.name),
                color = if (node.status == HeroPathNodeStatus.LOCKED) AqMuted else AqText,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(2.dp))
            UnlocalizedText(
                text = node.requirement?.let(copy) ?: statusText,
                color = stateColor,
                fontSize = 9.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun HeroPathEmptyState(
    filter: HeroPathFilter,
    copy: (HeroPathCopy) -> String,
) {
    val message = when (filter) {
        HeroPathFilter.ALL -> HeroPathCopy("표시할 기술이 없습니다.", "No skills to show.", "表示できるスキルはありません。")
        HeroPathFilter.LEARNED -> HeroPathCopy("아직 습득한 기술이 없습니다.", "No skills learned yet.", "まだ習得したスキルはありません。")
        HeroPathFilter.AVAILABLE -> HeroPathCopy("현재 선택할 수 있는 기술이 없습니다.", "No skills are currently available.", "現在選択できるスキルはありません。")
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = AqGoldSoft, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(10.dp))
        UnlocalizedText(
            text = copy(message),
            color = AqMuted,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/** Compact entry point shown only in the duel lobby. */
@Composable
internal fun ArenaTalentTreeCard(
    model: HeroPathArenaEntryUiModel,
    editingEnabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    copyResolver: HeroPathCopyResolver = { copy, language -> copy.resolve(language) },
) {
    val language = LocalAppLanguage.current
    val copy: (HeroPathCopy) -> String = { value -> copyResolver(value, language) }
    val detail = if (editingEnabled) {
        copy(
            HeroPathCopy(
                "${copy(model.specialization)} · 미사용 ${model.unspentPoints}",
                "${copy(model.specialization)} · ${model.unspentPoints} unspent",
                "${copy(model.specialization)}・未使用 ${model.unspentPoints}",
            ),
        )
    } else {
        copy(
            HeroPathCopy(
                "매칭·결투 중에는 편집할 수 없습니다.",
                "Editing is locked during matching and battle.",
                "マッチング・決闘中は編集できません。",
            ),
        )
    }
    Surface(
        onClick = onClick,
        enabled = editingEnabled,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 84.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "${copy(HeroPathCopy("결투 스킬트리", "Duel Skill Tree", "決闘スキルツリー"))}. $detail"
                stateDescription = if (editingEnabled) {
                    copy(HeroPathCopy("편집 가능", "Editable", "編集可能"))
                } else {
                    copy(HeroPathCopy("편집 잠김", "Editing locked", "編集ロック中"))
                }
            },
        shape = RoundedCornerShape(16.dp),
        color = AqSurfaceHigh,
        border = BorderStroke(1.dp, if (editingEnabled) AqGoldSoft else HeroPathDivider),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .background(AqGold.copy(alpha = 0.13f), RoundedCornerShape(14.dp))
                    .border(1.dp, AqGoldSoft, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = AqGold)
                if (model.unspentPoints > 0L) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(9.dp)
                            .background(HeroPathBerserker, CircleShape),
                    )
                }
            }
            Spacer(Modifier.width(11.dp))
            Column(modifier = Modifier.weight(1f)) {
                UnlocalizedText(
                    text = copy(HeroPathCopy("결투 스킬트리", "Duel Skill Tree", "決闘スキルツリー")),
                    color = AqText,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Black,
                )
                UnlocalizedText(
                    text = detail,
                    color = if (editingEnabled) AqMuted else HeroPathLocked,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
                model.coreTrait?.let { core ->
                    UnlocalizedText(
                        text = copy(HeroPathCopy("핵심 · ${copy(core)}", "Core · ${copy(core)}", "中核・${copy(core)}")),
                        color = AqGold,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            UnlocalizedText(
                text = copy(HeroPathCopy("트리 보기", "View Tree", "ツリーを見る")),
                color = if (editingEnabled) AqGold else HeroPathLocked,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.End,
            )
            Spacer(Modifier.width(2.dp))
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = if (editingEnabled) AqGold else HeroPathLocked)
        }
    }
}

/** Dark-gold detail popup with independently scrolling rules and a persistent learn action. */
@Composable
internal fun HeroPathNodeDetailSheetContent(
    node: HeroPathNodeUiModel,
    lane: HeroPathLaneUiModel,
    editingEnabled: Boolean,
    onToggleDraft: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    copyResolver: HeroPathCopyResolver = { copy, language -> copy.resolve(language) },
) {
    val language = LocalAppLanguage.current
    val copy: (HeroPathCopy) -> String = { value -> copyResolver(value, language) }
    val nextRank = (node.currentRank + 1).coerceAtMost(node.maxRank)
    var detailsExpanded by rememberSaveable(node.id) { mutableStateOf(false) }
    val actionEnabled = editingEnabled && (node.status == HeroPathNodeStatus.DRAFT || node.canDraft)
    val actionCopy = when {
        !editingEnabled -> HeroPathCopy("매칭·결투 중 편집 불가", "Locked During Match", "マッチ中は編集不可")
        node.status == HeroPathNodeStatus.DRAFT -> HeroPathCopy("초안 취소", "Undo Draft", "仮選択を解除")
        node.status == HeroPathNodeStatus.MAX -> HeroPathCopy("최대 랭크", "Max Rank", "最大ランク")
        node.canDraft && node.currentRank > 0 -> HeroPathCopy("랭크 올리기 · 1포인트", "Rank Up · 1 Point", "ランクアップ・1ポイント")
        node.canDraft -> HeroPathCopy("습득 · 1포인트", "Learn · 1 Point", "習得・1ポイント")
        else -> node.actionBlockReason ?: node.requirement
            ?: HeroPathCopy("현재 습득할 수 없습니다.", "Currently unavailable.", "現在は習得できません。")
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF141119), RoundedCornerShape(8.dp))
            .border(1.dp, AqGoldSoft, RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HeroPathTalentArtwork(
                nodeId = node.id,
                modifier = Modifier.size(64.dp).clip(CircleShape).border(1.dp, AqGold, CircleShape),
                desaturated = node.status == HeroPathNodeStatus.LOCKED,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                UnlocalizedText(
                    text = copy(node.shortName),
                    color = HeroPathTitleGold,
                    fontSize = 18.sp,
                    lineHeight = 23.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                UnlocalizedText(
                    text = copy(
                        HeroPathCopy(
                            "${copy(lane.name)} · ${copy(node.kind.label)} · Lv.${node.level}",
                            "${copy(lane.name)} · ${copy(node.kind.label)} · Lv. ${node.level}",
                            "${copy(lane.name)}・${copy(node.kind.label)}・Lv.${node.level}",
                        ),
                    ),
                    color = lane.accent,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Close, copy(HeroPathCopy("닫기", "Close", "閉じる")), tint = AqMuted)
            }
        }
        Spacer(Modifier.height(7.dp))
        UnlocalizedText(
            text = copy(node.summary),
            color = AqMuted,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Normal,
        )
        Spacer(Modifier.height(7.dp))
        HeroPathEffectComparisonRow(
            label = copy(HeroPathCopy("현재", "Current", "現在")),
            rank = "${node.currentRank}/${node.maxRank}",
            effect = node.currentEffect?.let(copy) ?: copy(HeroPathCopy("미습득", "Not learned", "未習得")),
            accent = AqMuted,
        )
        HeroPathEffectComparisonRow(
            label = copy(HeroPathCopy("다음", "Next", "次")),
            rank = if (node.status == HeroPathNodeStatus.MAX) "MAX" else "$nextRank/${node.maxRank}",
            effect = node.nextEffect?.let(copy) ?: copy(HeroPathCopy("최대 랭크", "Max rank", "最大ランク")),
            accent = AqGold,
        )
        HorizontalDivider(color = AqGoldSoft.copy(alpha = 0.2f))
        Surface(
            color = Color.Transparent,
            shape = RoundedCornerShape(4.dp),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
                        .clickable { detailsExpanded = !detailsExpanded }
                        .semantics {
                            role = Role.Button
                            stateDescription = copy(if (detailsExpanded) HeroPathCopy("펼쳐짐", "Expanded", "展開中") else HeroPathCopy("접힘", "Collapsed", "折りたたみ"))
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                UnlocalizedText(
                    text = copy(
                        if (node.kind == HeroPathNodeKind.MATCHUP_TACTIC) {
                            HeroPathCopy("상성 자동 판정", "Automatic Matchup Check", "相性の自動判定")
                        } else {
                            HeroPathCopy("자동 발동 규칙", "Automatic Trigger", "自動発動ルール")
                        },
                    ),
                    color = AqGold,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (detailsExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null, tint = AqGoldSoft, modifier = Modifier.size(20.dp),
                )
                }
                if (detailsExpanded) {
                Spacer(Modifier.height(4.dp))
                UnlocalizedText(
                    text = copy(
                        node.automaticRule ?: if (node.kind == HeroPathNodeKind.MATCHUP_TACTIC) {
                            HeroPathMatchupAutomaticCopy
                        } else {
                            HeroPathCopy(
                                "결투 중 조건을 만족하면 영웅이 자동으로 사용합니다.",
                                "Your hero uses this automatically when its duel condition is met.",
                                "決闘中に条件を満たすと、英雄が自動で使用します。",
                            )
                        },
                    ),
                    color = AqMuted,
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                )
                if (node.kind == HeroPathNodeKind.MATCHUP_TACTIC) {
                    Spacer(Modifier.height(4.dp))
                    UnlocalizedText(
                        text = copy(HeroPathMatchupExclusiveCopy),
                        color = AqMuted,
                        fontSize = 11.sp,
                        lineHeight = 17.sp,
                    )
                }
                Spacer(Modifier.height(6.dp))
                }
            }
        }
        node.actionBlockReason?.let { reason ->
            UnlocalizedText(copy(reason), color = HeroPathTitleGold, fontSize = 11.sp, lineHeight = 16.sp)
        }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onToggleDraft,
            enabled = actionEnabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            border = BorderStroke(1.dp, if (actionEnabled) AqGold else HeroPathDivider),
            shape = RoundedCornerShape(7.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = if (actionEnabled) AqGold else Color(0xFF28232D),
                contentColor = if (actionEnabled) Color(0xFF201809) else AqMuted,
            ),
        ) {
            UnlocalizedText(copy(actionCopy), fontSize = 13.sp, fontWeight = FontWeight.Black)
        }
        UnlocalizedText(
            text = copy(HeroPathCopy("결투장에서만 적용 · 다음 결투부터 적용", "Duel only · Applies from the next duel", "決闘場のみ・次の決闘から適用")),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
            color = AqMuted,
            fontSize = 10.sp,
            lineHeight = 15.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun HeroPathEffectComparisonRow(
    label: String,
    rank: String,
    effect: String,
    accent: Color,
) {
    HorizontalDivider(color = AqGoldSoft.copy(alpha = 0.2f))
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(Modifier.padding(top = 5.dp).size(6.dp).background(accent, CircleShape))
        Column(Modifier.width(48.dp)) {
            UnlocalizedText(label, color = accent, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold)
            UnlocalizedText(rank, color = accent, fontSize = 10.sp, lineHeight = 15.sp)
        }
        UnlocalizedText(effect, color = accent, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun HeroPathRankPanel(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.heightIn(min = 68.dp),
        color = AqSurfaceHigh,
        shape = RoundedCornerShape(13.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
    ) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            UnlocalizedText(label, color = AqMuted, fontSize = 10.sp, lineHeight = 14.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            UnlocalizedText(value, color = accent, fontSize = 14.sp, fontWeight = FontWeight.Black)
        }
    }
}

/**
 * Content for the three-choice hero path bottom sheet.
 *
 * The caller owns the sheet container and supplies persistence/navigation callbacks. This keeps
 * the component reusable inside the hero path overlay or a Material sheet.
 */
@Composable
internal fun HeroPathChoiceSheetContent(
    choice: HeroPathChoiceUiModel,
    lanes: List<HeroPathLaneUiModel>,
    onChoiceSelected: (eventId: String, nodeId: String) -> Unit,
    onFullTreeSelected: () -> Unit,
    onDeferred: () -> Unit,
    modifier: Modifier = Modifier,
    copyResolver: HeroPathCopyResolver = { copy, language -> copy.resolve(language) },
) {
    val language = LocalAppLanguage.current
    val copy: (HeroPathCopy) -> String = { value -> copyResolver(value, language) }
    val lanesById = lanes.associateBy(HeroPathLaneUiModel::id)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(AqSurface, HeroPathSheetShape)
            .verticalScroll(rememberScrollState())
            .padding(start = 18.dp, end = 18.dp, bottom = 20.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 34.dp, height = 4.dp)
                    .background(AqMuted.copy(alpha = 0.55f), RoundedCornerShape(99.dp)),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(AqGold.copy(alpha = 0.14f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = AqGold, modifier = Modifier.size(21.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                UnlocalizedText(
                    text = copy(
                        HeroPathCopy(
                            "Lv.${choice.milestoneLevel} · 특성 포인트 +1",
                            "Lv. ${choice.milestoneLevel} · Trait Point +1",
                            "Lv.${choice.milestoneLevel}・特性ポイント +1",
                        ),
                    ),
                    color = AqText,
                    fontSize = 18.sp,
                    lineHeight = 23.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.semantics { heading() },
                )
                UnlocalizedText(
                    text = copy(
                        HeroPathCopy(
                            "바로 고르거나 모아 둘 수 있습니다.",
                            "Choose now or save it for later.",
                            "今選ぶことも、貯めておくこともできます。",
                        ),
                    ),
                    color = AqMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
            Surface(
                shape = RoundedCornerShape(99.dp),
                color = AqSurfaceHigh,
                border = BorderStroke(1.dp, HeroPathDivider),
            ) {
                UnlocalizedText(
                    text = copy(
                        HeroPathCopy(
                            "보유 ${choice.unspentPoints}",
                            "${choice.unspentPoints} held",
                            "保有 ${choice.unspentPoints}",
                        ),
                    ),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    color = AqGold,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        choice.options.forEachIndexed { index, option ->
            val lane = requireNotNull(lanesById[option.node.laneId]) {
                "Choice option ${option.node.id} references an unknown lane"
            }
            HeroPathChoiceCard(
                ordinal = index + 1,
                option = option,
                lane = lane,
                copy = copy,
                onClick = { onChoiceSelected(choice.eventId, option.node.id) },
            )
            if (index < choice.options.lastIndex) Spacer(Modifier.height(9.dp))
        }
        Spacer(Modifier.height(15.dp))
        HorizontalDivider(color = HeroPathDivider)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onFullTreeSelected,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
                border = BorderStroke(1.dp, AqGoldSoft),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AqGold),
            ) {
                UnlocalizedText(
                    text = copy(HeroPathCopy("전체 트리", "Full Tree", "ツリー全体")),
                    color = AqGold,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                )
            }
            OutlinedButton(
                onClick = onDeferred,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
                border = BorderStroke(1.dp, HeroPathDivider),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AqMuted),
            ) {
                UnlocalizedText(
                    text = copy(HeroPathCopy("나중에", "Later", "あとで")),
                    color = AqMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        UnlocalizedText(
            text = copy(
                HeroPathCopy(
                    "선택은 다음 새 결투부터 적용됩니다.",
                    "Your choice applies from the next new duel.",
                    "選択は次の新しい決闘から適用されます。",
                ),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            color = AqMuted,
            fontSize = 10.sp,
            lineHeight = 15.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun HeroPathChoiceCard(
    ordinal: Int,
    option: HeroPathChoiceOptionUiModel,
    lane: HeroPathLaneUiModel,
    copy: (HeroPathCopy) -> String,
    onClick: () -> Unit,
) {
    val node = option.node
    val nodeKind = copy(node.kind.label)
    val description = buildString {
        append(copy(HeroPathCopy("선택지 $ordinal/3", "Option $ordinal of 3", "選択肢 $ordinal/3")))
        append(". ")
        append(copy(node.name))
        append(". ")
        append(copy(lane.name))
        append(". ")
        append(nodeKind)
        append(". ")
        append(copy(node.summary).trimEnd('.', '。'))
        node.metrics.forEach { metric ->
            append(". ")
            append(copy(metric.label))
            append(" ")
            append(copy(metric.value))
        }
    }
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = description
                stateDescription = copy(HeroPathCopy("선택 가능", "Available", "選択可能"))
            },
        shape = RoundedCornerShape(16.dp),
        color = AqSurfaceHigh.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, lane.accent.copy(alpha = 0.72f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        ) {
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .fillMaxHeight()
                    .background(lane.accent),
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 13.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .background(lane.accent.copy(alpha = 0.13f), RoundedCornerShape(14.dp))
                        .border(1.dp, lane.accent.copy(alpha = 0.7f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(option.icon, contentDescription = null, tint = lane.accent, modifier = Modifier.size(27.dp))
                }
                Spacer(Modifier.width(11.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HeroPathSmallLabel(copy(lane.name), lane.accent)
                        HeroPathSmallLabel(nodeKind, AqMuted)
                    }
                    Spacer(Modifier.height(5.dp))
                    UnlocalizedText(
                        text = copy(node.name),
                        color = AqText,
                        fontSize = 16.sp,
                        lineHeight = 21.sp,
                        fontWeight = FontWeight.Black,
                    )
                    Spacer(Modifier.height(3.dp))
                    UnlocalizedText(
                        text = copy(node.summary),
                        color = AqMuted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                    if (node.metrics.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            node.metrics.take(2).forEach { metric ->
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .background(metric.accent, CircleShape),
                                    )
                                    Spacer(Modifier.width(5.dp))
                                    UnlocalizedText(
                                        text = "${copy(metric.label)} ${copy(metric.value)}",
                                        color = metric.accent,
                                        fontSize = 10.sp,
                                        lineHeight = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroPathSmallLabel(text: String, accent: Color) {
    Surface(
        shape = RoundedCornerShape(99.dp),
        color = accent.copy(alpha = 0.1f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.38f)),
    ) {
        UnlocalizedText(
            text = text,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            color = accent,
            fontSize = 9.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun HeroPathNodeUiModel.visibleFor(filter: HeroPathFilter): Boolean = when (filter) {
    HeroPathFilter.ALL -> true
    HeroPathFilter.LEARNED -> status == HeroPathNodeStatus.LEARNED || status == HeroPathNodeStatus.MAX
    HeroPathFilter.AVAILABLE -> status == HeroPathNodeStatus.AVAILABLE || status == HeroPathNodeStatus.DRAFT
}

private val HeroPathFilter.label: HeroPathCopy
    get() = when (this) {
        HeroPathFilter.ALL -> HeroPathCopy("전체", "All", "すべて")
        HeroPathFilter.LEARNED -> HeroPathCopy("습득", "Learned", "習得済み")
        HeroPathFilter.AVAILABLE -> HeroPathCopy("선택 가능", "Available", "選択可能")
    }

internal val HeroPathNodeStatus.label: HeroPathCopy
    get() = when (this) {
        HeroPathNodeStatus.LEARNED -> HeroPathCopy("습득", "Learned", "習得済み")
        HeroPathNodeStatus.DRAFT -> HeroPathCopy("초안", "Drafted", "仮選択")
        HeroPathNodeStatus.AVAILABLE -> HeroPathCopy("선택 가능", "Available", "選択可能")
        HeroPathNodeStatus.LOCKED -> HeroPathCopy("잠김", "Locked", "未解放")
        HeroPathNodeStatus.MAX -> HeroPathCopy("최대 랭크", "Max Rank", "最大ランク")
    }

@Composable
private fun HeroPathDraftBar(
    model: HeroPathPanelUiModel,
    copy: (HeroPathCopy) -> String,
    onReviewDraft: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = AqSurface,
        border = BorderStroke(1.dp, AqGoldSoft.copy(alpha = 0.7f)),
        shadowElevation = 12.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                UnlocalizedText(
                    text = copy(
                        HeroPathCopy(
                            "변경 ${model.draftCount}개 · 적용 후 미사용 ${model.unspentPoints - model.draftCount}",
                            "${model.draftCount} Changes · ${model.unspentPoints - model.draftCount} left",
                            "変更 ${model.draftCount}件・適用後 ${model.unspentPoints - model.draftCount}",
                        ),
                    ),
                    color = AqText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                )
                UnlocalizedText(
                    text = copy(
                        HeroPathCopy(
                            "아직 확정되지 않았습니다.",
                            "Not confirmed yet.",
                            "まだ確定されていません。",
                        ),
                    ),
                    color = AqMuted,
                    fontSize = 10.sp,
                )
            }
            OutlinedButton(
                onClick = onReviewDraft,
                modifier = Modifier.heightIn(min = 48.dp),
                border = BorderStroke(1.dp, AqGold),
                shape = RoundedCornerShape(12.dp),
            ) {
                UnlocalizedText(
                    text = copy(HeroPathCopy("변경 적용", "Apply Changes", "変更を適用")),
                    color = AqGold,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                )
            }
        }
    }
}

internal val HeroPathNodeKind.label: HeroPathCopy
    get() = when (this) {
        HeroPathNodeKind.ACTIVE -> HeroPathCopy("특수 기술", "Special Skill", "特殊スキル")
        HeroPathNodeKind.MODIFIER -> HeroPathCopy("기반 특성", "Foundation Trait", "基礎特性")
        HeroPathNodeKind.MATCHUP_TACTIC -> HeroPathCopy("상성 전술", "Matchup Tactic", "相性戦術")
        HeroPathNodeKind.KEYSTONE -> HeroPathCopy("고급 전술", "Advanced Tactic", "上級戦術")
        HeroPathNodeKind.CAPSTONE -> HeroPathCopy("핵심 특성", "Core Trait", "中核特性")
    }

internal val HeroPathMatchupAutomaticCopy = HeroPathCopy(
    "결투 시작 시 상대와 내 빌드를 비교해 상성을 자동 판정합니다.",
    "At duel start, the game compares both builds and evaluates the matchup automatically.",
    "決闘開始時、双方のビルドを比較して相性を自動判定します。",
)

internal val HeroPathMatchupExclusiveCopy = HeroPathCopy(
    "A/B 중 하나만 선택할 수 있습니다.",
    "Choose either A or B; they cannot be active together.",
    "A/Bのどちらか一方だけを選択できます。",
)

internal fun heroPathNodeAccessibilityDescription(
    node: HeroPathNodeUiModel,
    copy: (HeroPathCopy) -> String,
): String = buildList {
    add(copy(node.name))
    add(copy(node.kind.label))
    add(copy(node.summary))
    node.automaticRule?.let { add(copy(it)) }
    node.currentEffect?.let { add(copy(HeroPathCopy("현재: ", "Current: ", "現在：")) + copy(it)) }
    node.nextEffect?.let { add(copy(HeroPathCopy("다음: ", "Next: ", "次：")) + copy(it)) }
    if (node.kind == HeroPathNodeKind.MATCHUP_TACTIC) {
        add(copy(HeroPathMatchupAutomaticCopy))
        add(copy(HeroPathMatchupExclusiveCopy))
    }
    add(copy(node.status.label))
    node.requirement?.let { add(copy(it)) }
}.joinToString(". ") { it.trim().trimEnd('.', '。') }

/**
 * Realistic tactical-board data matching the selected visual direction. It is intentionally a
 * factory rather than hidden composable state, so previews, QA, and the eventual engine adapter
 * can exercise the same public UI model.
 */
internal fun heroPathTacticalSampleModel(
    selectedFilter: HeroPathFilter = HeroPathFilter.ALL,
): HeroPathPanelUiModel {
    val lanes = heroPathTacticalSampleLanes()
    val learned = listOf(
        sampleNode("berserker-15", 15, "berserker", HeroPathNodeStatus.LEARNED, "붉은 맹세", "Red Oath", "赤の誓い", "공세의 첫 박자를 잡습니다.", "Sets the opening rhythm of the assault.", "攻勢の最初の拍子を作る。"),
        sampleNode("bulwark-15", 15, "bulwark", HeroPathNodeStatus.LEARNED, "성벽 자세", "Rampart Stance", "城壁の構え", "중심을 지키며 강한 충격을 받습니다.", "Holds center against heavy impacts.", "軸を守り、強い衝撃を受け止める。"),
        sampleNode("warlord-15", 15, "warlord", HeroPathNodeStatus.LEARNED, "전술 기치", "Tactical Standard", "戦術の旗", "싸움의 흐름을 읽어 다음 합을 준비합니다.", "Reads the battle and prepares the next exchange.", "戦況を読み、次の攻防に備える。"),
    )
    val available = heroPathTacticalSampleChoice().options.map(HeroPathChoiceOptionUiModel::node)
    val locked = listOf(
        sampleNode("berserker-25", 25, "berserker", HeroPathNodeStatus.LOCKED, "광폭 해방", "Fury Unbound", "狂気解放", "쌓인 분노를 한꺼번에 해방합니다.", "Releases accumulated fury at once.", "蓄えた怒りを一気に解放する。"),
        sampleNode("bulwark-25", 25, "bulwark", HeroPathNodeStatus.LOCKED, "불굴의 성벽", "Unbroken Rampart", "不屈の城壁", "위태로울수록 방어의 축을 강화합니다.", "Fortifies the guard when danger rises.", "危機が迫るほど防御の軸を強化する。"),
        sampleNode("warlord-25", 25, "warlord", HeroPathNodeStatus.LOCKED, "승리의 진군", "Victory March", "勝利の進軍", "잡은 주도권을 다음 공세로 이어 갑니다.", "Carries momentum into the next assault.", "掴んだ主導権を次の攻勢へつなぐ。"),
    ).map { node ->
        node.copy(
            requirement = HeroPathCopy("Lv.25 필요", "Requires Lv. 25", "Lv.25必要"),
        )
    }
    return HeroPathPanelUiModel(
        heroName = "QA테스트",
        heroClassName = HeroPathCopy("파이터", "Fighter", "ファイター"),
        heroLevel = 20,
        learnedCount = 4,
        totalChoiceCount = 20,
        pendingChoiceCount = 1,
        selectedFilter = selectedFilter,
        lanes = lanes,
        nodes = learned + available + locked,
    )
}

internal fun heroPathTacticalSampleLanes(): List<HeroPathLaneUiModel> = listOf(
    HeroPathLaneUiModel(
        id = "berserker",
        name = HeroPathCopy("광전사", "Berserker", "狂戦士"),
        description = HeroPathCopy("압도적인 공격으로 적을 베어냅니다.", "Overwhelm foes with relentless attacks.", "圧倒的な攻撃で敵を斬り伏せる。"),
        accent = HeroPathBerserker,
        icon = Icons.Filled.LocalFireDepartment,
    ),
    HeroPathLaneUiModel(
        id = "bulwark",
        name = HeroPathCopy("철벽", "Bulwark", "鉄壁"),
        description = HeroPathCopy("견고한 방어로 아군을 지킵니다.", "Guard allies with an unbroken defense.", "堅牢な防御で仲間を守る。"),
        accent = HeroPathBulwark,
        icon = Icons.Filled.Shield,
    ),
    HeroPathLaneUiModel(
        id = "warlord",
        name = HeroPathCopy("전쟁군주", "Warlord", "軍略家"),
        description = HeroPathCopy("전장을 지휘하며 승리를 이끌어 냅니다.", "Command the field and direct the victory.", "戦場を指揮し、勝利へ導く。"),
        accent = HeroPathWarlord,
        icon = Icons.Filled.Campaign,
    ),
)

internal fun heroPathTacticalSampleChoice(): HeroPathChoiceUiModel {
    val counter = HeroPathNodeUiModel(
        id = "berserker-20-counter",
        level = 20,
        laneId = "berserker",
        status = HeroPathNodeStatus.AVAILABLE,
        kind = HeroPathNodeKind.KEYSTONE,
        name = HeroPathCopy("충격 저장", "Stored Impact", "衝撃蓄積"),
        summary = HeroPathCopy("방어 후 반격을 준비합니다.", "Prepares a counter after guarding.", "防御後の反撃に備える。"),
        metrics = listOf(
            HeroPathMetricUiModel(HeroPathCopy("반격", "Counter", "反撃"), HeroPathCopy("+8%", "+8%", "+8%"), HeroPathBerserker),
            HeroPathMetricUiModel(HeroPathCopy("방어", "Guard", "防御"), HeroPathCopy("유지", "Kept", "維持"), AqText),
        ),
    )
    val heat = HeroPathNodeUiModel(
        id = "bulwark-20-blood-heat",
        level = 20,
        laneId = "bulwark",
        status = HeroPathNodeStatus.AVAILABLE,
        kind = HeroPathNodeKind.KEYSTONE,
        name = HeroPathCopy("피의 열기", "Blood Heat", "血の熱"),
        summary = HeroPathCopy("피격될수록 강공이 빨라집니다.", "Heavy strikes quicken after taking hits.", "攻撃を受けるほど強攻撃が速まる。"),
        metrics = listOf(
            HeroPathMetricUiModel(HeroPathCopy("분노", "Fury", "怒り"), HeroPathCopy("+1", "+1", "+1"), HeroPathBerserker),
            HeroPathMetricUiModel(HeroPathCopy("방어", "Guard", "防御"), HeroPathCopy("-4%", "-4%", "-4%"), AqRed),
        ),
    )
    val harmony = HeroPathNodeUiModel(
        id = "warlord-20-harmony",
        level = 20,
        laneId = "warlord",
        status = HeroPathNodeStatus.AVAILABLE,
        kind = HeroPathNodeKind.KEYSTONE,
        name = HeroPathCopy("전장의 함성", "War Cry", "戦場の雄叫び"),
        summary = HeroPathCopy("높은 사기로 공세를 이어 갑니다.", "Sustains pressure while morale is high.", "高い士気で攻勢をつなぐ。"),
        metrics = listOf(
            HeroPathMetricUiModel(HeroPathCopy("사기", "Morale", "士気"), HeroPathCopy("+5", "+5", "+5"), HeroPathWarlord),
            HeroPathMetricUiModel(HeroPathCopy("기술", "Skills", "スキル"), HeroPathCopy("우선", "First", "優先"), AqText),
        ),
    )
    return HeroPathChoiceUiModel(
        eventId = "sample-lv20",
        milestoneLevel = 20,
        queuePosition = 1,
        queueSize = 1,
        options = listOf(
            HeroPathChoiceOptionUiModel(counter, Icons.Filled.Bolt),
            HeroPathChoiceOptionUiModel(heat, Icons.Filled.LocalFireDepartment),
            HeroPathChoiceOptionUiModel(harmony, Icons.Filled.Campaign),
        ),
    )
}

private fun sampleNode(
    id: String,
    level: Int,
    laneId: String,
    status: HeroPathNodeStatus,
    nameKo: String,
    nameEn: String,
    nameJa: String,
    summaryKo: String,
    summaryEn: String,
    summaryJa: String,
): HeroPathNodeUiModel = HeroPathNodeUiModel(
    id = id,
    level = level,
    laneId = laneId,
    status = status,
    kind = HeroPathNodeKind.ACTIVE,
    name = HeroPathCopy(nameKo, nameEn, nameJa),
    summary = HeroPathCopy(summaryKo, summaryEn, summaryJa),
)

private val HeroPathSheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
private val HeroPathBackground = Color(0xFF100D17)
private val HeroPathTitleGold = Color(0xFFE4C48A)
private val HeroPathDivider = Color(0xFF3A3042)
private val HeroPathLocked = Color(0xFF82778B)
private val HeroPathBerserker = Color(0xFFD35858)
private val HeroPathBulwark = Color(0xFFD7A84A)
private val HeroPathWarlord = Color(0xFF6685DE)
private val HeroPathLevelRailWidth = 54.dp
private val HeroPathLaneGap = 7.dp
