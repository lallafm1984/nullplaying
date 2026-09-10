package com.nullplaying.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nullplaying.model.HeroClass
import com.nullplaying.engine.arena.ArenaSupportCatalog
import com.nullplaying.engine.arena.ArenaProgressionCatalog

internal data class ArenaPreviewConfig(
    val leftClass: HeroClass,
    val rightClass: HeroClass,
    val heroLevel: Int,
    val traitIndex: Int,
    val traitRank: Int,
    val enhancement: Int,
    val arenaLevel: Int,
)

internal data class ArenaPreviewFighterUi(
    val id: String,
    val name: String,
    val heroClass: HeroClass,
    val level: Int,
    val hpFraction: Float,
    val maxHp: Double,
    val mpUnits: Int,
    val maxMpUnits: Int,
    val shield: Double,
    val castLabel: String,
)

internal data class ArenaPreviewUiState(
    val language: String,
    val config: ArenaPreviewConfig,
    val left: ArenaPreviewFighterUi,
    val right: ArenaPreviewFighterUi,
    val turn: Int,
    val message: String,
    val resultShown: Boolean,
    val resultText: String,
    val running: Boolean,
    val started: Boolean,
    val inspectionPaused: Boolean,
    val diagnostic: String,
    val error: Boolean,
    val skillCatalogId: String?,
    val skillElapsedMillis: Int,
    val skillMirrored: Boolean,
)

internal val ArenaPreviewBlue = Color(0xFF78A8D8)
internal val ArenaPreviewRed = AqRed

/** Presentation only: receives resolved events and never changes combat or saved characters. */
@Composable
internal fun ArenaSupportPreviewScreen(
    state: ArenaPreviewUiState,
    logs: List<Pair<Int, String>>,
    tint: Color,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onReplay: () -> Unit,
    onConfigure: (ArenaPreviewConfig) -> Unit,
    onResumeInspection: () -> Unit,
) {
    val copy = PreviewCopy(state.language)
    var configureOpen by remember { mutableStateOf(false) }
    var diagnosticsOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(AqBackground).systemBarsPadding()) {
        RankingPageTopBar(
            title = copy.text("새 전투 체험", "Combat preview", "新バトル体験"),
            subtitle = copy.text("테스트 캐릭터 · 로컬 전투", "Test characters · local battle", "テストキャラ・ローカル対戦"),
            backContentDescription = copy.text("결투장으로 돌아가기", "Back to arena", "闘技場に戻る"),
            onBack = onBack,
        )
        // One scroll surface keeps every action reachable at large font sizes and on short screens.
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.started) {
                item(key = "scope") {
                    PreviewCard {
                        MaterialText(copy.text("새로운 공방을 가볍게", "Try the new combat", "新しい駆け引きを体験"), color = AqText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        MaterialText(
                            copy.text("직업과 레벨을 골라 자동 전투를 살펴보세요. 실제 캐릭터의 전적과 보상에는 영향을 주지 않습니다.", "Choose classes and a level to watch an automatic battle. Your character, record and rewards are unchanged.", "職業とレベルを選んで自動バトルを確認できます。実際のキャラ・戦績・報酬には影響しません。"),
                            color = AqMuted, fontSize = 13.sp, lineHeight = 20.sp,
                        )
                        MaterialText(copy.text("전체 카탈로그 · 보조 ${ArenaSupportCatalog.values.size}개 · 성장 특성 ${ArenaProgressionCatalog.values.size}개", "Complete catalog · ${ArenaSupportCatalog.values.size} supports · ${ArenaProgressionCatalog.values.size} growth traits", "全カタログ・補助スキル${ArenaSupportCatalog.values.size}種・成長特性${ArenaProgressionCatalog.values.size}種"), color = AqGold, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                }
            }
            item(key = "battle") { PreviewBattleCard(state, tint, copy) }
            item(key = "actions") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.inspectionPaused) {
                        PreviewPrimaryButton(copy.text("검수 계속", "Resume inspection", "確認を続ける"), onResumeInspection)
                    } else if (!state.running) {
                        PreviewPrimaryButton(
                            if (!state.started) copy.text("전투 시작", "Start battle", "対戦開始")
                            else copy.text("다시 대전", "Battle again", "もう一度対戦"),
                            if (!state.started) onStart else onReplay,
                        )
                    }
                    if (!state.running) {
                        OutlinedButton(
                            onClick = { configureOpen = true },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, AqGoldSoft),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AqGold),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        ) {
                            MaterialText(copy.text("체험 설정", "Preview settings", "体験設定"), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (state.started) {
                        MaterialText(copy.text("테스트 전투 · 실제 전적과 보상 미반영", "Test battle · no record or reward changes", "テスト対戦・実際の戦績や報酬に影響なし"), color = AqMuted, fontSize = 11.sp, lineHeight = 17.sp)
                    }
                }
            }
            item(key = "diagnostics") {
                Column {
                    TextButton(onClick = { diagnosticsOpen = !diagnosticsOpen }, modifier = Modifier.heightIn(min = 48.dp)) {
                        MaterialText(
                            if (diagnosticsOpen) copy.text("검수 정보 접기", "Hide test details", "検証情報を閉じる")
                            else copy.text("검수 정보 보기", "Test details", "検証情報を見る"),
                            color = AqMuted, fontSize = 12.sp,
                        )
                    }
                    if (diagnosticsOpen) {
                        PreviewCard {
                            MaterialText(copy.text("전체 보조·성장·지속 피해·제어 엔진을 사용합니다. 실제 입력은 선택한 레벨과 검수 프리셋으로 제한되며, 이 한 전투가 전체 효과의 발동 검증을 대신하지 않습니다.", "This uses the complete support, growth, damage-over-time and control engine. Captured ownership follows the selected level and test preset; one battle does not prove every effect.", "補助・成長・継続ダメージ・行動阻害の全エンジンを使用します。実際の構成は選択したレベルと検証プリセットに従います。この1戦だけで全効果を検証したことにはなりません。"), color = AqMuted, fontSize = 12.sp, lineHeight = 18.sp)
                            MaterialText("${copy.text("아레나", "Arena", "闘技場")} Lv.${state.config.arenaLevel} · ${copy.traitPreset(state.config)}", color = AqGold, fontSize = 12.sp)
                            if (state.diagnostic.isNotBlank()) MaterialText(state.diagnostic, color = if (state.error) AqRed else AqMuted, fontSize = 11.sp, lineHeight = 17.sp)
                        }
                    }
                }
            }
            item(key = "log-heading") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    MaterialText(copy.text("전투 기록", "Battle log", "戦闘記録"), modifier = Modifier.weight(1f).semantics { heading() }, color = AqText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    MaterialText(copy.text("최신순", "Newest first", "新しい順"), color = AqMuted, fontSize = 11.sp)
                }
            }
            if (logs.isEmpty()) {
                item(key = "empty-log") {
                    PreviewCard {
                        MaterialText(copy.text("공격과 기술, 회피·방어 결과가 여기에 기록됩니다.", "Basic attacks, skills, dodges and defenses appear here.", "通常攻撃・スキル・回避・防御の結果をここに記録します。"), color = AqMuted, fontSize = 13.sp, lineHeight = 20.sp)
                    }
                }
            } else {
                items(logs, key = { "event-${it.first}" }) { (sequence, line) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(AqSurface).padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        MaterialText(sequence.toString(), color = AqGold, fontSize = 11.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
                        MaterialText(line, modifier = Modifier.weight(1f), color = AqText, fontSize = 13.sp, lineHeight = 20.sp)
                    }
                }
            }
        }
    }
    if (configureOpen && !state.running) {
        PreviewSettingsDialog(state.config, copy, onDismiss = { configureOpen = false }, onApply = {
            configureOpen = false
            onConfigure(it)
        })
    }
}

@Composable
private fun PreviewBattleCard(state: ArenaPreviewUiState, tint: Color, copy: PreviewCopy) {
    fun fighter(value: ArenaPreviewFighterUi) = ArenaCombatFighterUi(
        name = value.name,
        classLabel = copy.heroClass(value.heroClass),
        level = value.level.toLong(),
        hpFraction = value.hpFraction,
        mpFraction = if (value.maxMpUnits > 0) value.mpUnits.toFloat() / value.maxMpUnits else 0f,
        shieldFraction = if (value.maxHp > 0.0) (value.shield / value.maxHp).toFloat() else 0f,
    )
    val visibleMessage = when {
        state.resultShown -> state.resultText
        state.started -> state.message.takeIf(String::isNotBlank)
        else -> null
    }
    ArenaCombatStage(
        left = fighter(state.left), right = fighter(state.right), message = visibleMessage, tint = tint,
        backgroundResourceId = battleBackgroundResource(definitionId = "", chapterNumber = 1),
        // The surrounding preview list already has the standard 16dp horizontal inset.
        horizontalInset = 0.dp,
        skillCatalogId = state.skillCatalogId,
        skillElapsedMillis = state.skillElapsedMillis,
        skillMirrored = state.skillMirrored,
        modifier = Modifier.semantics {
            contentDescription = "arena-preview skillVfx=${state.skillCatalogId ?: "none"} " +
                "skillSide=${if (state.skillMirrored) "right" else "left"} skillElapsed=${state.skillElapsedMillis}"
        },
    )
}

@Composable
private fun PreviewCard(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = AqSurface)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun PreviewPrimaryButton(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        MaterialText(label, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PreviewSettingsDialog(config: ArenaPreviewConfig, copy: PreviewCopy, onDismiss: () -> Unit, onApply: (ArenaPreviewConfig) -> Unit) {
    var draft by remember(config) { mutableStateOf(config) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AqSurface,
        shape = RoundedCornerShape(22.dp),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MaterialText(copy.text("체험 설정", "Preview settings", "体験設定"), color = AqText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                MaterialText(
                    copy.text("아래로 내려 레벨·특성도 설정하세요.", "Scroll down to set the level and traits too.", "下へスクロールしてレベル・特性も設定できます。"),
                    color = AqMuted, fontSize = 12.sp, lineHeight = 18.sp,
                )
            }
        },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                MaterialText(copy.text("테스트 캐릭터에만 적용됩니다. 실제 스킬 습득이나 포인트 배분은 아닙니다.", "Applies only to test characters. This does not learn skills or spend real points.", "テストキャラ専用です。実際のスキル習得やポイント配分は行いません。"), color = AqMuted, fontSize = 12.sp, lineHeight = 18.sp)
                PreviewClassPicker(copy.text("파란 쪽 직업", "Blue side class", "青側の職業"), draft.leftClass, copy) { draft = draft.copy(leftClass = it) }
                PreviewClassPicker(copy.text("붉은 쪽 직업", "Red side class", "赤側の職業"), draft.rightClass, copy) { draft = draft.copy(rightClass = it) }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PreviewSettingHeading(copy.text("캐릭터 레벨", "Character level", "キャラレベル"))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { draft = draft.copy(heroLevel = (draft.heroLevel - 10).coerceAtLeast(10)) }, enabled = draft.heroLevel > 10, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp), contentPadding = PaddingValues(8.dp)) { MaterialText("−10", fontSize = 14.sp) }
                        MaterialText("Lv.${draft.heroLevel}", modifier = Modifier.weight(1f), color = AqGold, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        OutlinedButton(onClick = { draft = draft.copy(heroLevel = (draft.heroLevel + 10).coerceAtMost(100)) }, enabled = draft.heroLevel < 100, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp), contentPadding = PaddingValues(8.dp)) { MaterialText("+10", fontSize = 14.sp) }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PreviewSettingHeading(copy.text("특성 체험", "Trait preset", "特性プリセット"))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PreviewChoice("A01", draft.traitIndex == 0, Modifier.weight(1f)) {
                            draft = draft.copy(traitIndex = 0)
                        }
                        PreviewChoice("A02", draft.traitIndex == 1, Modifier.weight(1f)) {
                            draft = draft.copy(traitIndex = 1)
                        }
                    }
                    val presets = listOf(Triple(0, 0, 1), Triple(1, 0, 1), Triple(5, 3, 60))
                    presets.forEach { (rank, enhancement, arena) ->
                        val preset = draft.copy(traitRank = rank, enhancement = enhancement, arenaLevel = arena)
                        PreviewChoice(copy.traitPreset(preset), draft.traitRank == rank && draft.enhancement == enhancement, Modifier.fillMaxWidth()) { draft = preset }
                    }
                    MaterialText(copy.text("양쪽에 선택한 번호의 특성을 같은 단계로 적용합니다.", "Both sides use the selected trait at the same stage.", "両者に選択した特性を同じ段階で適用します。"), color = AqMuted, fontSize = 11.sp, lineHeight = 17.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(draft) }, modifier = Modifier.heightIn(min = 48.dp)) { MaterialText(copy.text("적용", "Apply", "適用"), color = AqGold, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { MaterialText(copy.text("취소", "Cancel", "キャンセル"), color = AqMuted, fontSize = 14.sp) }
        },
    )
}

@Composable
private fun PreviewClassPicker(title: String, selected: HeroClass, copy: PreviewCopy, onSelect: (HeroClass) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PreviewSettingHeading(title)
        HeroClass.entries.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { heroClass -> PreviewChoice(copy.heroClass(heroClass), selected == heroClass, Modifier.weight(1f)) { onSelect(heroClass) } }
            }
        }
    }
}

@Composable
private fun PreviewSettingHeading(title: String) {
    MaterialText(title, modifier = Modifier.semantics { heading() }, color = AqText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun PreviewChoice(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, modifier = modifier.heightIn(min = 48.dp).semantics { this.selected = selected },
        shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, if (selected) AqGold else AqGoldSoft.copy(alpha = .5f)),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (selected) AqGold.copy(alpha = .14f) else AqSurfaceHigh, contentColor = if (selected) AqGold else AqText),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
    ) {
        MaterialText(label, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center)
    }
}

private class PreviewCopy(private val language: String) {
    fun text(ko: String, en: String, ja: String): String = when (language) { "en" -> en; "ja" -> ja; else -> ko }
    fun heroClass(value: HeroClass): String = when (value) {
        HeroClass.WARRIOR -> text("파이터", "Fighter", "ファイター")
        HeroClass.ROGUE -> text("시프", "Thief", "シーフ")
        HeroClass.RANGER -> text("레인저", "Ranger", "レンジャー")
        HeroClass.MAGE -> text("메이지", "Mage", "メイジ")
        HeroClass.CLERIC -> text("클래릭", "Cleric", "クレリック")
        HeroClass.PALADIN -> text("팔라딘", "Paladin", "パラディン")
    }
    fun traitPreset(config: ArenaPreviewConfig): String = when {
        config.traitRank == 0 -> text("특성 미적용", "No traits", "特性なし")
        config.traitRank == 1 && config.enhancement == 0 -> "A0${config.traitIndex + 1} · " + text("기본 특성", "Base trait", "基本特性")
        config.traitRank == 5 && config.enhancement == 3 -> "A0${config.traitIndex + 1} · " + text("강화 특성", "Enhanced trait", "強化特性")
        else -> "A0${config.traitIndex + 1} · " + text("특성 ${config.traitRank} · 강화 ${config.enhancement}", "Trait ${config.traitRank} · enhancement ${config.enhancement}", "特性${config.traitRank}・強化${config.enhancement}")
    }
}
