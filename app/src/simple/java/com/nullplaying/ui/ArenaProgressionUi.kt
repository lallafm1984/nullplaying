package com.nullplaying.ui

import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text as MaterialText
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.DialogProperties
import com.nullplaying.engine.arena.ArenaProgressionCatalog
import com.nullplaying.engine.arena.ArenaProgressionRules
import com.nullplaying.engine.arena.ArenaProgressionTraitDefinition
import com.nullplaying.engine.arena.ArenaSupportCatalog
import com.nullplaying.engine.arena.ArenaSupportDefinition
import com.nullplaying.engine.arena.ArenaSupportTraitRank
import com.nullplaying.localization.AppLanguage
import com.nullplaying.model.HeroClass

internal data class ArenaProgressionTraitUiModel(
    val traitId: String,
    val rank: Int,
    val enhancement: Int,
    val available: Boolean = true,
)

/** A presentation snapshot; its owner revalidates and atomically saves every purchase. */
internal data class ArenaProgressionUiModel(
    val heroClass: HeroClass,
    val unlocked: Boolean,
    val arenaLevel: Int,
    val xpIntoLevel: Long,
    val xpToNext: Long,
    val baseAvailable: Int,
    val baseSpent: Int,
    val enhancementAvailable: Int,
    val enhancementSpent: Int,
    val rank: Int,
    val enhancement: Int,
    val growthRemaining: Int,
    val editingEnabled: Boolean,
    val traitAvailable: Boolean = true,
    val traits: List<ArenaProgressionTraitUiModel> = emptyList(),
    val qaHeroLevelOverride: Int = 0,
    val savedHeroLevel: Long = 0L,
    /** Null supports legacy callers; an explicit empty set means no owned supports. */
    val ownedSupportIds: Set<String>? = null,
)

internal fun progressionCopy(language: AppLanguage, ko: String, en: String, ja: String): String = when (language) {
    AppLanguage.KOREAN -> ko
    AppLanguage.ENGLISH -> en
    AppLanguage.JAPANESE -> ja
}

internal val AppLanguage.arenaCode: String get() = when (this) {
    AppLanguage.KOREAN -> "ko"
    AppLanguage.ENGLISH -> "en"
    AppLanguage.JAPANESE -> "ja"
}

internal fun arenaProgressionUiDefinitions(heroClass: HeroClass): List<ArenaProgressionTraitDefinition> =
    ArenaProgressionCatalog.forClass(heroClass).sortedBy(ArenaProgressionTraitDefinition::id)

internal fun arenaProgressionTraitName(heroClass: HeroClass, language: AppLanguage): String =
    arenaProgressionTraitName(ArenaProgressionCatalog.initialTrait(heroClass), language)

internal fun arenaProgressionTraitName(definition: ArenaProgressionTraitDefinition, language: AppLanguage): String =
    when (language) {
        AppLanguage.KOREAN -> definition.nameKo
        AppLanguage.ENGLISH -> definition.nameEn
        AppLanguage.JAPANESE -> definition.nameJa
    }

internal fun arenaProgressionTraitEffect(heroClass: HeroClass, rank: Int, enhancement: Int, language: AppLanguage): String =
    arenaProgressionTraitEffect(ArenaProgressionCatalog.initialTrait(heroClass), rank, enhancement, language)

/** Display the executable catalogue's final value and unit, including core replacements. */
internal fun arenaProgressionTraitEffect(definition: ArenaProgressionTraitDefinition, rank: Int, enhancement: Int, language: AppLanguage): String =
    if (rank <= 0) progressionCopy(language, "미습득", "Not learned", "未習得")
    else definition.effectText(language.arenaCode, rank, enhancement)

internal fun arenaProgressionTraitSummary(definition: ArenaProgressionTraitDefinition, rank: Int, enhancement: Int, language: AppLanguage): String =
    definition.summaryText(language.arenaCode, maxOf(1, rank), enhancement)

internal fun ArenaProgressionUiModel.effectiveHeroLevel(): Long = maxOf(savedHeroLevel, qaHeroLevelOverride.toLong())

internal fun ArenaProgressionUiModel.supportIds(): Set<String> =
    ownedSupportIds ?: ArenaSupportCatalog.unlockedIds(heroClass, effectiveHeroLevel())

internal fun ArenaProgressionUiModel.traitState(definition: ArenaProgressionTraitDefinition): ArenaProgressionTraitUiModel {
    val saved = traits.firstOrNull { it.traitId == definition.id }
        ?: if (definition.id == ArenaProgressionCatalog.initialTrait(heroClass).id)
            ArenaProgressionTraitUiModel(definition.id, rank, enhancement, traitAvailable)
        else ArenaProgressionTraitUiModel(definition.id, 0, 0)
    return saved.copy(available = saved.available && effectiveHeroLevel() >= definition.minHeroLevel &&
        definition.requiredSupportIds.all { it in supportIds() })
}

internal data class ArenaTraitPurchaseState(
    val rankCost: Int,
    val enhancementCost: Int,
    val canRankUp: Boolean,
    val canEnhance: Boolean,
    val anotherCoreLearned: Boolean,
)

/** Shared by the fixed action bar and copy contracts; costs always come from growth rules. */
internal fun arenaTraitPurchaseState(model: ArenaProgressionUiModel, definition: ArenaProgressionTraitDefinition): ArenaTraitPurchaseState {
    val trait = model.traitState(definition)
    val rankCost = if (trait.rank < definition.maxRank)
        definition.basePointCost(trait.rank + 1) - definition.basePointCost(trait.rank) else 0
    val enhancementCost = if (!definition.isCore && trait.enhancement < definition.maxEnhancement)
        ArenaProgressionRules.enhancementCost(trait.enhancement + 1) - ArenaProgressionRules.enhancementCost(trait.enhancement) else 0
    val anotherCore = definition.isCore && arenaProgressionUiDefinitions(model.heroClass).any {
        it.isCore && it.id != definition.id && model.traitState(it).rank > 0
    }
    val editable = model.unlocked && model.editingEnabled && trait.available
    return ArenaTraitPurchaseState(
        rankCost, enhancementCost,
        editable && !anotherCore && rankCost > 0 && model.baseAvailable >= rankCost,
        editable && !definition.isCore && model.arenaLevel >= ArenaProgressionRules.ENHANCEMENT_START_LEVEL && trait.rank > 0 &&
            enhancementCost > 0 && model.enhancementAvailable >= enhancementCost,
        anotherCore,
    )
}

private fun ArenaProgressionUiModel.qaProjectionCopy(language: AppLanguage): String? {
    if (qaHeroLevelOverride <= 0) return null
    return progressionCopy(language,
        "QA 영웅 Lv.${effectiveHeroLevel()} · 저장 Lv.$savedHeroLevel",
        "QA Hero Lv.${effectiveHeroLevel()} · Saved Lv.$savedHeroLevel",
        "QA英雄Lv.${effectiveHeroLevel()}・保存Lv.$savedHeroLevel")
}

private fun arenaClassName(heroClass: HeroClass, language: AppLanguage): String = when (heroClass) {
    HeroClass.WARRIOR -> progressionCopy(language, "파이터", "Fighter", "ファイター")
    HeroClass.ROGUE -> progressionCopy(language, "시프", "Thief", "シーフ")
    HeroClass.RANGER -> progressionCopy(language, "레인저", "Ranger", "レンジャー")
    HeroClass.MAGE -> progressionCopy(language, "메이지", "Mage", "メイジ")
    HeroClass.CLERIC -> progressionCopy(language, "클래릭", "Cleric", "クレリック")
    HeroClass.PALADIN -> progressionCopy(language, "팔라딘", "Paladin", "パラディン")
}

/** The existing dark/gold entry treatment used by the neighbouring ranking control. */
@Composable
internal fun ArenaProgressionEntryCard(model: ArenaProgressionUiModel, onOpen: () -> Unit) {
    val language = LocalAppLanguage.current
    val title = progressionCopy(language, "결투장 성장", "Arena Progression", "闘技場育成")
    val available = model.baseAvailable.coerceAtLeast(0) + model.enhancementAvailable.coerceAtLeast(0)
    val detail = when {
        !model.unlocked -> progressionCopy(language, "영웅 Lv.${ArenaProgressionRules.MIN_HERO_LEVEL}에 개방", "Opens at Hero Lv.${ArenaProgressionRules.MIN_HERO_LEVEL}", "英雄Lv.${ArenaProgressionRules.MIN_HERO_LEVEL}で解放")
        available > 0 -> progressionCopy(language, "Lv.${model.arenaLevel} · 미사용 $available", "Lv.${model.arenaLevel} · $available unspent", "Lv.${model.arenaLevel}・未使用 $available")
        else -> progressionCopy(language, "Lv.${model.arenaLevel} · 특성과 보조 스킬", "Lv.${model.arenaLevel} · Traits & support skills", "Lv.${model.arenaLevel}・特性と補助スキル")
    }
    Surface(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().heightIn(min = RANKING_ENTRY_MENU_MIN_HEIGHT_DP.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$title, $detail" },
        shape = RoundedCornerShape(14.dp), color = AqGold.copy(alpha = 0.12f), border = BorderStroke(1.dp, AqGoldSoft),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = AqGold, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                MaterialText(title, color = AqText, fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.Black)
                MaterialText(detail, color = if (available > 0) AqGold else AqMuted, fontSize = 12.sp, lineHeight = 16.sp)
                if (model.unlocked) ProgressionXpBar(model, Modifier.fillMaxWidth())
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = AqGold, modifier = Modifier.size(24.dp))
        }
    }
}

/** Summary, navigation and actions stay outside the scrolling list/detail viewport. */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun ArenaProgressionDialog(
    model: ArenaProgressionUiModel,
    onDismiss: () -> Unit,
    onAllocate: (traitId: String, rank: Int, enhancement: Int) -> Unit,
    onReset: () -> Unit,
) {
    val language = LocalAppLanguage.current
    fun copy(ko: String, en: String, ja: String) = progressionCopy(language, ko, en, ja)
    var supportTab by remember { mutableStateOf(false) }
    var branch by remember { mutableStateOf<String?>(null) }
    var learnedOnly by remember { mutableStateOf(false) }
    var unlockedOnly by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    val definitions = arenaProgressionUiDefinitions(model.heroClass)
    val supports = ArenaSupportCatalog.forClass(model.heroClass).sortedWith(compareBy({ it.unlockLevel }, { it.id }))
    val selectedTrait = definitions.firstOrNull { !supportTab && it.id == selectedId }
    val selectedSupport = supports.firstOrNull { supportTab && it.id == selectedId }
    val editable = model.unlocked && model.editingEnabled
    val canReset = editable && (model.baseSpent > 0 || model.enhancementSpent > 0)
    val listState = rememberLazyListState()
    val supportListState = rememberLazyListState()
    val hostDensity = LocalDensity.current
    val safeInsets = WindowInsets.safeDrawing
    val viewportHeight = LocalConfiguration.current.screenHeightDp.dp - with(hostDensity) {
        (safeInsets.getTop(this) + safeInsets.getBottom(this)).toDp()
    } - 24.dp
    Dialog(onDismissRequest = { if (selectedId != null) selectedId = null else onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(dialogWindow) {
            // Compose's default dialog keeps a wrap-content native window even when its
            // child fills the display. Match the actual window before sizing the content.
            dialogWindow?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            onDispose { }
        }
        ArenaFixedFont {
            Box(Modifier.fillMaxWidth().height(viewportHeight.coerceAtLeast(200.dp))
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 8.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth()
                    .heightIn(max = viewportHeight.coerceAtLeast(200.dp)).fillMaxHeight()
                    .semantics { testTagsAsResourceId = true },
                shape = RoundedCornerShape(20.dp), color = AqSurface, border = BorderStroke(1.dp, AqGoldSoft),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MaterialText(copy("결투장 성장", "Arena Progression", "闘技場育成"),
                            modifier = Modifier.weight(1f).semantics { heading() },
                            color = AqText, fontSize = 18.sp, lineHeight = 23.sp, fontWeight = FontWeight.Black)
                        IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp).testTag("arena-growth-close")) {
                            Icon(Icons.Filled.Close, copy("닫기", "Close", "閉じる"), modifier = Modifier.size(24.dp), tint = AqMuted)
                        }
                    }
                    ProgressionSummary(model)
                    if (!editable) MaterialText(
                        if (!model.unlocked) copy("영웅 Lv.${ArenaProgressionRules.MIN_HERO_LEVEL}부터 성장 포인트를 사용할 수 있습니다.", "Growth points unlock at Hero Lv.${ArenaProgressionRules.MIN_HERO_LEVEL}.", "成長ポイントは英雄Lv.${ArenaProgressionRules.MIN_HERO_LEVEL}で解放。")
                        else copy("대전 준비·진행 중에는 변경할 수 없습니다.", "Changes are locked during a duel.", "対戦の準備・進行中は変更できません。"),
                        color = AqGold, fontSize = 12.sp, lineHeight = 16.sp)
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ProgressionTab(copy("성장 특성", "Traits", "成長特性") + " ${definitions.size}", !supportTab,
                            Modifier.weight(1f).testTag("arena-growth-traits-tab")) { supportTab = false; selectedId = null }
                        ProgressionTab(copy("보조 스킬", "Support Skills", "補助スキル") + " ${supports.size}", supportTab,
                            Modifier.weight(1f).testTag("arena-growth-supports-tab")) { supportTab = true; selectedId = null }
                    }
                    if (selectedTrait != null || selectedSupport != null) {
                        TextButton(onClick = { selectedId = null }, modifier = Modifier.heightIn(min = 48.dp).testTag("arena-growth-back")) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(20.dp), tint = AqGold)
                            Spacer(Modifier.width(6.dp))
                            MaterialText(copy("목록으로", "Back to list", "一覧に戻る"), color = AqGold, fontSize = 13.sp)
                        }
                        key(selectedId) {
                            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                                .testTag("arena-growth-details"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (selectedTrait != null) TraitDetails(model, selectedTrait, language)
                                if (selectedSupport != null) SupportDetails(model, selectedSupport, language)
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (!supportTab) {
                                ProgressionFilter(copy("전체", "All", "すべて"), branch == null) { branch = null }
                                listOf("A", "B", "C").forEach { filter ->
                                    ProgressionFilter(filter, branch == filter) { branch = filter }
                                }
                            }
                            ProgressionFilter(if (supportTab) copy("사용 가능", "Unlocked", "使用可能") else copy("습득", "Learned", "習得済み"),
                                if (supportTab) unlockedOnly else learnedOnly) {
                                if (supportTab) unlockedOnly = !unlockedOnly else learnedOnly = !learnedOnly
                            }
                            if (supportTab) MaterialText(arenaClassName(model.heroClass, language), color = AqMuted, fontSize = 12.sp)
                        }
                        val filteredTraits = definitions.filter { (branch == null || it.branch == branch) && (!learnedOnly || model.traitState(it).rank > 0) }
                        val filteredSupports = supports.filter { !unlockedOnly || it.id in model.supportIds() }
                        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().testTag("arena-growth-list"),
                            state = if (supportTab) supportListState else listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (supportTab) {
                                items(filteredSupports, key = { it.id }) { definition ->
                                    ProgressionListRow(definition.name(language.arenaCode), supportStatus(model, definition, language),
                                        arenaSupportEffectiveDefinition(model, definition).summaryText(language.arenaCode), "arena-support-${definition.id}",
                                        definition.id in model.supportIds()) { selectedId = definition.id }
                                }
                            } else {
                                items(filteredTraits, key = { it.id }) { definition ->
                                    val trait = model.traitState(definition)
                                    ProgressionListRow(arenaProgressionTraitName(definition, language), traitStatus(model, definition, language),
                                        arenaProgressionTraitSummary(definition, trait.rank, trait.enhancement, language),
                                        "arena-trait-${definition.id}", trait.rank > 0) { selectedId = definition.id }
                                }
                            }
                            if ((supportTab && filteredSupports.isEmpty()) || (!supportTab && filteredTraits.isEmpty())) item {
                                MaterialText(copy("해당 항목이 없습니다. 필터를 바꾸어 보세요.", "No matches. Change the filter to see more.", "該当する項目はありません。フィルターを変更してください。"),
                                    color = AqMuted, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.padding(vertical = 20.dp))
                            }
                        }
                    }
                    if (selectedTrait != null) TraitActions(model, selectedTrait, language, onAllocate)
                    HorizontalDivider(color = AqGoldSoft.copy(alpha = 0.2f), modifier = Modifier.padding(top = 6.dp))
                    TextButton(onClick = { confirmReset = true }, enabled = canReset,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("arena-growth-reset")) {
                        MaterialText(copy("무료 초기화", "Reset for Free", "無料リセット"),
                            color = if (canReset) AqGold else AqMuted.copy(alpha = 0.6f), fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            }
        }
    }
    if (confirmReset) {
        AlertDialog(onDismissRequest = { confirmReset = false }, containerColor = AqSurface,
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            title = { ArenaFixedFont { MaterialText(copy("특성을 초기화할까요?", "Reset all traits?", "特性をリセットしますか？"),
                color = AqText, fontSize = 18.sp, fontWeight = FontWeight.Bold) } },
            text = { ArenaFixedFont { MaterialText(copy(
                "기본 ${model.baseSpent}·강화 ${model.enhancementSpent}포인트를 돌려받습니다. 레벨·경험치·보조 스킬은 유지됩니다.",
                "Returns ${model.baseSpent} base and ${model.enhancementSpent} enhancement points. Level, XP and support skills stay unchanged.",
                "基本${model.baseSpent}・強化${model.enhancementSpent}ポイントが戻ります。レベル・経験値・補助スキルは維持されます。"),
                color = AqMuted, fontSize = 13.sp, lineHeight = 20.sp) } },
            confirmButton = { ArenaFixedFont { TextButton(onClick = { confirmReset = false; onReset() }, enabled = editable,
                modifier = Modifier.heightIn(min = 48.dp).testTag("arena-growth-reset-confirm")) {
                MaterialText(copy("초기화", "Reset", "リセット"), color = AqGold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            } } },
            dismissButton = { ArenaFixedFont { TextButton(onClick = { confirmReset = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                MaterialText(copy("취소", "Cancel", "キャンセル"), color = AqMuted, fontSize = 13.sp)
            } } },
        )
    }
}

@Composable
private fun ProgressionTab(label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier.heightIn(min = 48.dp).semantics { role = Role.Tab; selected = active },
        shape = RoundedCornerShape(10.dp), color = if (active) AqGold.copy(alpha = 0.17f) else AqSurfaceHigh,
        border = BorderStroke(1.dp, if (active) AqGoldSoft else AqGoldSoft.copy(alpha = 0.2f))) {
        Box(Modifier.padding(horizontal = 6.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
            MaterialText(label, color = if (active) AqGold else AqMuted, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ProgressionFilter(label: String, active: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).semantics { selected = active }) {
        MaterialText(label, color = if (active) AqGold else AqMuted, fontSize = 12.sp,
            fontWeight = if (active) FontWeight.Black else FontWeight.Normal)
    }
}

@Composable
private fun ProgressionListRow(name: String, status: String, effect: String, tag: String, learned: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag(tag)
        .semantics(mergeDescendants = true) { contentDescription = "$name. $status. $effect" },
        shape = RoundedCornerShape(12.dp), color = AqSurfaceHigh,
        border = BorderStroke(1.dp, AqGoldSoft.copy(alpha = if (learned) 0.35f else 0.14f))) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                MaterialText(name, color = if (learned) AqGold else AqText, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Bold)
                MaterialText(status, color = AqMuted, fontSize = 11.sp, lineHeight = 15.sp)
                MaterialText(effect, color = AqText.copy(alpha = 0.85f), fontSize = 12.sp, lineHeight = 17.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = AqGoldSoft, modifier = Modifier.size(22.dp))
        }
    }
}

private fun traitStatus(model: ArenaProgressionUiModel, definition: ArenaProgressionTraitDefinition, language: AppLanguage): String {
    val trait = model.traitState(definition)
    val branch = progressionCopy(language, "분기 ${definition.branch}", "Path ${definition.branch}", "系統${definition.branch}")
    val status = when {
        trait.rank > 0 && definition.isCore -> progressionCopy(language, "핵심 습득", "Core learned", "コア習得済み")
        trait.rank > 0 -> progressionCopy(language, "랭크 ${trait.rank}/${definition.maxRank}", "Rank ${trait.rank}/${definition.maxRank}", "ランク${trait.rank}/${definition.maxRank}") +
            if (trait.enhancement > 0) " · +${trait.enhancement}" else ""
        !trait.available -> progressionCopy(language, "해금 조건 확인", "Requirements not met", "解放条件を確認")
        definition.isCore -> progressionCopy(language, "핵심 · 기본 ${definition.basePointCost(1)}점", "Core · ${definition.basePointCost(1)} base pts", "コア・基本${definition.basePointCost(1)}pt")
        else -> progressionCopy(language, "미습득", "Not learned", "未習得")
    }
    return "$branch · $status"
}

internal fun supportStatus(model: ArenaProgressionUiModel, definition: ArenaSupportDefinition, language: AppLanguage): String =
    if (definition.id in model.supportIds()) progressionCopy(language, "사용 가능 · 자동 선택", "Unlocked · Chosen automatically", "使用可能・自動選択")
    else progressionCopy(language, "영웅 Lv.${definition.unlockLevel} 해금", "Unlocks at Hero Lv.${definition.unlockLevel}", "英雄Lv.${definition.unlockLevel}で解放")

internal fun arenaSupportTimingLines(definition: ArenaSupportDefinition, language: AppLanguage): List<String> {
    fun copy(ko: String, en: String, ja: String) = progressionCopy(language, ko, en, ja)
    return buildList {
        add("MP ${definition.mp} · " + copy("시전 ${definition.castTurns}턴", "Cast ${definition.castTurns} ${if (definition.castTurns == 1) "turn" else "turns"}", "詠唱${definition.castTurns}ターン"))
        add(if (definition.durationTurns > 0)
            copy("유효 ${definition.durationTurns}턴", "Duration ${definition.durationTurns} ${if (definition.durationTurns == 1) "turn" else "turns"}", "有効${definition.durationTurns}ターン")
            else copy("완료 시 즉시 적용", "Applied on completion", "完了時に即適用"))
        add(if (definition.oncePerBattle) copy("재사용 불가 · 전투당 1회", "No reuse · Once per battle", "再使用不可・1戦に1回")
            else copy("재사용 대기 ${definition.cooldownTurns}턴", "Cooldown ${definition.cooldownTurns} ${if (definition.cooldownTurns == 1) "turn" else "turns"}", "再使用待機${definition.cooldownTurns}ターン"))
        if (definition.charges > 0) add(copy("사용권 ${definition.charges}회", "${definition.charges} ${if (definition.charges == 1) "use" else "uses"}", "使用回数${definition.charges}回"))
    }
}

internal fun arenaSupportEffectiveDefinition(model: ArenaProgressionUiModel, definition: ArenaSupportDefinition): ArenaSupportDefinition =
    ArenaSupportCatalog.effectiveDefinition(definition.id, arenaProgressionUiDefinitions(model.heroClass).mapNotNull { trait ->
        val state = model.traitState(trait)
        if (state.rank > 0) ArenaSupportTraitRank(trait.id, state.rank, state.enhancement) else null
    }) ?: definition

@Composable
private fun TraitDetails(model: ArenaProgressionUiModel, definition: ArenaProgressionTraitDefinition, language: AppLanguage) {
    fun copy(ko: String, en: String, ja: String) = progressionCopy(language, ko, en, ja)
    val trait = model.traitState(definition)
    DetailHeading(arenaProgressionTraitName(definition, language), traitStatus(model, definition, language))
    val supportNames = definition.requiredSupportIds.mapNotNull { ArenaSupportCatalog.find(it)?.name(language.arenaCode) }
    DetailSection(copy("해금 조건", "Requirements", "解放条件"),
        copy("영웅 Lv.${definition.minHeroLevel}", "Hero Lv.${definition.minHeroLevel}", "英雄Lv.${definition.minHeroLevel}") +
            if (supportNames.isEmpty()) copy(" · 연결 보조 스킬 없음", " · No support skill required", "・補助スキルの条件なし")
            else " · " + supportNames.joinToString(" / "))
    DetailSection(copy("발동 조건", "Trigger", "発動条件"), definition.conditionText(language.arenaCode))
    Surface(color = AqGold.copy(alpha = 0.07f), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ProgressionEffectRow(copy("현재", "Current", "現在"), arenaProgressionTraitEffect(definition, trait.rank, trait.enhancement, language), AqText)
            if (trait.rank < definition.maxRank) ProgressionEffectRow(copy("다음", "Next", "次"),
                arenaProgressionTraitEffect(definition, trait.rank + 1, trait.enhancement, language), AqGold)
            if (!definition.isCore && trait.rank > 0 && trait.enhancement < definition.maxEnhancement) ProgressionEffectRow(copy("강화 시", "Enhanced", "強化後"),
                arenaProgressionTraitEffect(definition, trait.rank, trait.enhancement + 1, language), AqGoldSoft)
        }
    }
    if (definition.windowTurns > 0 || definition.charges > 0 || definition.cooldownTurns > 0) {
        val details = buildList {
            if (definition.windowTurns > 0) add(copy("유효 ${definition.windowTurns}턴", "Lasts ${definition.windowTurns} turns", "有効${definition.windowTurns}ターン"))
            if (definition.charges > 0) add(copy("${definition.charges}회 적용", "${definition.charges} ${if (definition.charges == 1) "use" else "uses"}", "${definition.charges}回適用"))
            if (definition.cooldownTurns > 0) add(copy("재발동 대기 ${definition.cooldownTurns}턴", "Retrigger wait: ${definition.cooldownTurns} turns", "再発動待機${definition.cooldownTurns}ターン"))
        }
        DetailSection(copy("유효 기간·횟수", "Duration & uses", "期間・回数"), details.joinToString(" · "))
    }
    DetailSection(copy("약점·제약", "Limits & weaknesses", "弱点・制約"), definition.limitationText(language.arenaCode))
    if (definition.isCore) DetailSection(copy("핵심 선택", "Core choice", "コア選択"),
        copy("핵심은 하나만 습득할 수 있습니다. 무료 초기화 후 다른 핵심을 선택할 수 있으며 강화는 없습니다.",
            "Only one core can be learned. Reset for free to choose another. Cores cannot be enhanced.",
            "習得できるコアは1つです。無料リセットで変更できます。コアは強化できません。"))
    else if (model.arenaLevel < ArenaProgressionRules.ENHANCEMENT_START_LEVEL) DetailSection(copy("강화", "Enhancement", "強化"),
        copy("강화 포인트는 결투장 Lv.${ArenaProgressionRules.ENHANCEMENT_START_LEVEL}부터 얻습니다.", "Enhancement points begin at Arena Lv.${ArenaProgressionRules.ENHANCEMENT_START_LEVEL}.", "強化ポイントは闘技場Lv.${ArenaProgressionRules.ENHANCEMENT_START_LEVEL}から獲得。"))
}

@Composable
private fun SupportDetails(model: ArenaProgressionUiModel, definition: ArenaSupportDefinition, language: AppLanguage) {
    fun copy(ko: String, en: String, ja: String) = progressionCopy(language, ko, en, ja)
    DetailHeading(definition.name(language.arenaCode), supportStatus(model, definition, language))
    DetailSection(copy("해금 조건", "Unlock", "解放条件"),
        "${arenaClassName(definition.heroClass, language)} · " + copy("영웅 Lv.${definition.unlockLevel}", "Hero Lv.${definition.unlockLevel}", "英雄Lv.${definition.unlockLevel}"))
    val linked = arenaProgressionUiDefinitions(model.heroClass).filter { definition.id in it.requiredSupportIds }
    val learned = linked.filter { model.traitState(it).rank > 0 }
    val effective = arenaSupportEffectiveDefinition(model, definition)
    MaterialText(copy("현재 수치", "Current values", "現在の値"), color = AqGoldSoft,
        fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
    Surface(color = AqGold.copy(alpha = 0.07f), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            arenaSupportTimingLines(effective, language).forEach { line ->
                MaterialText(line, color = AqText, fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
    if (learned.isNotEmpty()) MaterialText(copy(
        "고정 변경을 반영한 수치입니다. 조건부 성장 효과는 아래에서 확인할 수 있습니다.",
        "Fixed modifiers are included. See conditional growth effects below.",
        "固定変更を反映した値です。条件付きの成長効果は下に表示します。"),
        color = AqMuted, fontSize = 12.sp, lineHeight = 18.sp)
    if (arenaSupportTimingLines(effective, language) != arenaSupportTimingLines(definition, language)) {
        DetailSection(copy("핵심 적용 전", "Before core changes", "コア適用前"), arenaSupportTimingLines(definition, language).joinToString(" · "))
    }
    DetailSection(copy("효과", "Effect", "効果"), effective.effectText(language.arenaCode))
    if (learned.isNotEmpty()) {
        DetailSection(copy("습득한 성장 효과", "Learned growth effects", "習得済みの成長効果"),
            learned.joinToString("\n\n") { trait ->
                val state = model.traitState(trait)
                arenaProgressionTraitName(trait, language) + "\n" +
                    arenaProgressionTraitEffect(trait, state.rank, state.enhancement, language) + "\n" +
                    trait.conditionText(language.arenaCode)
            })
    }
    DetailSection(copy("자동 사용 조건", "Automatic use", "自動使用条件"), definition.conditionText(language.arenaCode))
    DetailSection(copy("약점·제약", "Limits & weaknesses", "弱点・制約"), definition.limitationText(language.arenaCode))
    if (linked.isNotEmpty()) DetailSection(copy("연결 성장 특성", "Linked traits", "関連する成長特性"),
        linked.joinToString(" · ") { arenaProgressionTraitName(it, language) })
    DetailSection(copy("자동 선택", "Automatic selection", "自動選択"), copy(
        "해금한 공격·보조 스킬 중 현재 HP·MP, 시전과 재사용 대기, 실제 효용을 보고 선택합니다. 조건을 만족해도 더 유리한 행동을 선택할 수 있습니다.",
        "The hero weighs unlocked attacks and supports using current HP, MP, casting, cooldowns and usefulness. Meeting a condition does not force a skill to be used.",
        "解放済みの攻撃・補助スキルから、現在のHP・MP、詠唱、再使用待機、効果の有用性を見て選びます。条件を満たしても必ず使うとは限りません。"))
}

@Composable
private fun DetailHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MaterialText(title, color = AqGold, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        MaterialText(subtitle, color = AqMuted, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun DetailSection(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MaterialText(title, color = AqGoldSoft, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        MaterialText(body, color = AqText, fontSize = 13.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun TraitActions(model: ArenaProgressionUiModel, definition: ArenaProgressionTraitDefinition, language: AppLanguage,
    onAllocate: (String, Int, Int) -> Unit) {
    fun copy(ko: String, en: String, ja: String) = progressionCopy(language, ko, en, ja)
    val trait = model.traitState(definition)
    val purchase = arenaTraitPurchaseState(model, definition)
    val rankAction = when {
        !model.editingEnabled -> copy("대전 중 변경 잠금", "Locked during duel", "対戦中は変更不可")
        !trait.available -> copy("해금 조건 필요", "Requirements needed", "解放条件が必要")
        purchase.anotherCoreLearned -> copy("핵심 변경은 초기화 후", "Reset to change core", "コア変更にはリセット")
        trait.rank >= definition.maxRank -> copy("최대 랭크", "Max rank", "最大ランク")
        model.baseAvailable < purchase.rankCost -> copy("기본 ${purchase.rankCost}점 필요", "Need ${purchase.rankCost} base pts", "基本${purchase.rankCost}pt必要")
        trait.rank == 0 -> copy("습득 · 기본 ${purchase.rankCost}점", "Learn · ${purchase.rankCost} base pts", "習得・基本${purchase.rankCost}pt")
        else -> copy("랭크 +1 · 기본 ${purchase.rankCost}점", "Rank +1 · ${purchase.rankCost} base pts", "ランク+1・基本${purchase.rankCost}pt")
    }
    val showEnhancement = !definition.isCore && model.arenaLevel >= ArenaProgressionRules.ENHANCEMENT_START_LEVEL
    val enhanceAction = when {
        !model.editingEnabled -> copy("대전 중 변경 잠금", "Locked during duel", "対戦中は変更不可")
        !trait.available -> copy("해금 조건 필요", "Requirements needed", "解放条件が必要")
        trait.rank <= 0 -> copy("습득 후 강화", "Learn before enhancing", "習得後に強化可能")
        trait.enhancement >= definition.maxEnhancement -> copy("최대 강화", "Max enhancement", "最大強化")
        else -> copy("강화 +${trait.enhancement + 1} · 강화 ${purchase.enhancementCost}점", "Enhance +${trait.enhancement + 1} · ${purchase.enhancementCost} enhance pts", "強化+${trait.enhancement + 1}・強化${purchase.enhancementCost}pt")
    }
    val compact = LocalConfiguration.current.fontScale >= 1.5f || LocalConfiguration.current.screenHeightDp < 600
    if (compact && showEnhancement) {
        val rankShort = when {
            !trait.available || !model.editingEnabled || purchase.anotherCoreLearned -> copy("기본 잠김", "Base locked", "基本ロック")
            trait.rank >= definition.maxRank -> copy("기본 최대", "Base max", "基本上限")
            else -> copy("${if (trait.rank == 0) "습득" else "랭크"} ${purchase.rankCost}점", "${if (trait.rank == 0) "Learn" else "Rank up"} ${purchase.rankCost}pt", "${if (trait.rank == 0) "習得" else "ランク"}${purchase.rankCost}pt")
        }
        val enhanceShort = when {
            !trait.available || !model.editingEnabled || trait.rank == 0 -> copy("강화 잠김", "Enhance locked", "強化ロック")
            trait.enhancement >= definition.maxEnhancement -> copy("강화 최대", "Enhance max", "強化上限")
            else -> copy("강화 ${purchase.enhancementCost}점", "Enhance ${purchase.enhancementCost}pt", "強化${purchase.enhancementCost}pt")
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ProgressionAction(rankShort, purchase.canRankUp, "arena-growth-rank-action",
                Modifier.weight(1f).semantics { contentDescription = rankAction }) {
                onAllocate(definition.id, trait.rank + 1, trait.enhancement)
            }
            ProgressionAction(enhanceShort, purchase.canEnhance, "arena-growth-enhance-action",
                Modifier.weight(1f).semantics { contentDescription = enhanceAction }) {
                onAllocate(definition.id, trait.rank, trait.enhancement + 1)
            }
        }
    } else {
        Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ProgressionAction(rankAction, purchase.canRankUp, "arena-growth-rank-action") {
                onAllocate(definition.id, trait.rank + 1, trait.enhancement)
            }
            if (showEnhancement) {
                ProgressionAction(enhanceAction, purchase.canEnhance, "arena-growth-enhance-action") {
                    onAllocate(definition.id, trait.rank, trait.enhancement + 1)
                }
            }
        }
    }
}

/** Kept for existing callers; restore the user's scale overridden by the host game UI. */
@Composable
internal fun ArenaFixedFont(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val systemFontScale = LocalConfiguration.current.fontScale
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = systemFontScale), content = content)
}

@Composable
private fun ProgressionSummary(model: ArenaProgressionUiModel) {
    val language = LocalAppLanguage.current
    fun copy(ko: String, en: String, ja: String) = progressionCopy(language, ko, en, ja)
    val compact = LocalConfiguration.current.fontScale >= 1.5f || LocalConfiguration.current.screenHeightDp < 600
    val summaryDescription = copy(
        "결투장 레벨 ${model.arenaLevel}. 경험치 ${model.xpIntoLevel}/${model.xpToNext}. 사용 가능한 기본 포인트 ${model.baseAvailable}, 강화 포인트 ${model.enhancementAvailable}. 오늘 성장 가능한 대전 ${model.growthRemaining}회.",
        "Arena Level ${model.arenaLevel}. XP ${model.xpIntoLevel} of ${model.xpToNext}. Available base points ${model.baseAvailable}, enhancement points ${model.enhancementAvailable}. Growth duels left today ${model.growthRemaining}.",
        "闘技場レベル${model.arenaLevel}。経験値${model.xpIntoLevel}/${model.xpToNext}。使用可能な基本ポイント${model.baseAvailable}、強化ポイント${model.enhancementAvailable}。本日の成長対象対戦は残り${model.growthRemaining}回。")
    Column(Modifier.fillMaxWidth().testTag("arena-growth-summary").semantics(mergeDescendants = true) {
        contentDescription = summaryDescription
    }, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MaterialText("Lv.${model.arenaLevel}", color = AqGold, fontSize = 20.sp, fontWeight = FontWeight.Black)
            MaterialText(if (model.arenaLevel >= ArenaProgressionRules.MAX_ARENA_LEVEL) "MAX" else "${model.xpIntoLevel.coerceAtLeast(0)} / ${model.xpToNext.coerceAtLeast(0)} XP",
                color = AqMuted, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.weight(1f))
            if (!compact) MaterialText(arenaClassName(model.heroClass, language), color = AqMuted, fontSize = 11.sp, lineHeight = 16.sp)
        }
        ProgressionXpBar(model, Modifier.fillMaxWidth())
        MaterialText(copy("기본 ${model.baseAvailable.coerceAtLeast(0)} · 강화 ${model.enhancementAvailable.coerceAtLeast(0)} · 오늘 성장 ${model.growthRemaining.coerceAtLeast(0)}회",
            "Base ${model.baseAvailable.coerceAtLeast(0)} · Enhance ${model.enhancementAvailable.coerceAtLeast(0)} · Growth left today ${model.growthRemaining.coerceAtLeast(0)}",
            "基本${model.baseAvailable.coerceAtLeast(0)}・強化${model.enhancementAvailable.coerceAtLeast(0)}・本日の成長あと${model.growthRemaining.coerceAtLeast(0)}回"),
            color = AqGoldSoft, fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold)
        if (!compact) model.qaProjectionCopy(language)?.let { MaterialText(it, color = AqMuted, fontSize = 10.sp, lineHeight = 14.sp) }
    }
}

@Composable
private fun ProgressionXpBar(model: ArenaProgressionUiModel, modifier: Modifier) {
    val language = LocalAppLanguage.current
    val xpLabel = if (model.arenaLevel >= ArenaProgressionRules.MAX_ARENA_LEVEL) progressionCopy(language, "결투장 최대 레벨", "Maximum Arena Level", "闘技場最大レベル")
        else progressionCopy(language, "결투장 경험치 ${model.xpIntoLevel} / ${model.xpToNext}", "Arena XP ${model.xpIntoLevel} of ${model.xpToNext}", "闘技場経験値 ${model.xpIntoLevel} / ${model.xpToNext}")
    val fraction = if (model.arenaLevel >= ArenaProgressionRules.MAX_ARENA_LEVEL) 1f else if (model.xpToNext > 0)
        (model.xpIntoLevel.toDouble() / model.xpToNext.toDouble()).toFloat().coerceIn(0f, 1f) else 0f
    Box(modifier.height(3.dp).clip(RoundedCornerShape(2.dp)).background(AqGoldSoft.copy(alpha = 0.25f)).semantics { contentDescription = xpLabel }) {
        if (fraction > 0f) Box(Modifier.fillMaxWidth(fraction).height(3.dp).background(AqGold))
    }
}

@Composable
private fun ProgressionEffectRow(label: String, effect: String, color: Color) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        MaterialText(label, color = AqMuted, fontSize = 11.sp, lineHeight = 16.sp)
        MaterialText(effect, color = color, fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ProgressionAction(label: String, enabled: Boolean, tag: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag),
        shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = AqGold, contentColor = AqBackground,
            disabledContainerColor = AqGoldSoft.copy(alpha = 0.2f), disabledContentColor = AqMuted)) {
        MaterialText(label, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold)
    }
}
