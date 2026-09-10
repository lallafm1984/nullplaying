package com.nullplaying.ui

import android.content.Intent
import android.os.SystemClock
import com.nullplaying.BuildConfig
import com.nullplaying.engine.BattleQaMatchFactory
import com.nullplaying.engine.BattleTraitCatalog
import com.nullplaying.engine.HeroPathCatalog
import com.nullplaying.engine.ProjectionBattleEngine
import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.engine.SkillCatalog
import com.nullplaying.engine.arena.ARENA_SUPPORT_RULES_VERSION
import com.nullplaying.engine.arena.ArenaProgressionCatalog
import com.nullplaying.engine.arena.ArenaSupportCatalog
import com.nullplaying.engine.arena.ArenaServerMatchSelectionResult
import com.nullplaying.engine.arena.ArenaCharacterPointRules
import com.nullplaying.engine.arena.ArenaProgressionRules
import com.nullplaying.engine.arena.ArenaProgressionState
import com.nullplaying.engine.arena.ArenaSkillTreeRules
import com.nullplaying.engine.arena.ArenaSkillTreeState
import com.nullplaying.engine.arena.ArenaSupportTurnEngine
import com.nullplaying.engine.arena.ArenaTurnInputAdapter
import com.nullplaying.engine.arena.arenaServerMatchingEnabled
import com.nullplaying.engine.arena.selectArenaOpponent
import com.nullplaying.engine.arena.withArenaServerOpponent
import com.nullplaying.localization.AppLanguage
import com.nullplaying.localization.GameLocalization
import com.nullplaying.model.ActiveBattleTrait
import com.nullplaying.model.BattleActionKind
import com.nullplaying.model.BattleActionResolution
import com.nullplaying.model.BattleEquipmentSlot
import com.nullplaying.model.BattleEquipmentSnapshot
import com.nullplaying.model.BattleGuidance
import com.nullplaying.model.BattleHeroClass
import com.nullplaying.model.BattleOutcome
import com.nullplaying.model.BattleRound
import com.nullplaying.model.BattleRoundAction
import com.nullplaying.model.BattleSeasonStanding
import com.nullplaying.model.BattleSide
import com.nullplaying.model.BattleSkillKind
import com.nullplaying.model.BattleSkillSnapshot
import com.nullplaying.model.BattleStandingUpdate
import com.nullplaying.model.BattleTicketState
import com.nullplaying.model.BattleTraitDefinition
import com.nullplaying.model.BattleTraitCategory
import com.nullplaying.model.HERO_PATH_TREE_VERSION
import com.nullplaying.model.HeroPathChoiceStance
import com.nullplaying.model.HeroPathNodeDefinition
import com.nullplaying.model.HeroPathNodeSlot
import com.nullplaying.model.NormalizedBattleProjection
import com.nullplaying.model.ProjectionBattleResult
import com.nullplaying.model.PublicPlayerRoster
import com.nullplaying.model.SimpleGameState
import com.nullplaying.remote.ArenaServerQaRuntimeConfig
import com.nullplaying.remote.ArenaRankingLocalStanding
import com.nullplaying.remote.BattleQaNarrative
import com.nullplaying.remote.BattleQaScene
import com.nullplaying.remote.BattleQaUsage
import com.nullplaying.remote.OUTSIDE_DISPLAYED_ARENA_RANK
import com.nullplaying.remote.RankingRefreshPolicy
import com.nullplaying.remote.RemoteArenaRankingEntry
import com.nullplaying.remote.RemoteArenaRankingSnapshot
import com.nullplaying.remote.buildArenaRankingDisplaySnapshot
import com.nullplaying.remote.stripBattleDialogueText
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil

internal const val BATTLE_START_SCORE = 1_000
internal const val BATTLE_K_FACTOR = 24
internal const val BATTLE_DAILY_ENTRIES = BATTLE_ENTRY_CAPACITY
internal const val BATTLE_PLACEMENT_REQUIRED = 10
internal const val BATTLE_TRAIT_REMOVAL_MINUTES = 60
internal const val BATTLE_RESONANCE_MIN_INDEX = 100
internal const val BATTLE_RESONANCE_MAX_INDEX = 108
internal const val BATTLE_RESONANCE_DAMAGE_TENTHS_PER_POINT = 9
internal const val BATTLE_MIN_ATTACK_INTERVAL_MILLIS = 1_500
internal const val BATTLE_ENERGY_IMPACT_MILLIS = 180
internal const val BATTLE_ARENA_TINT_HOLD_MILLIS = 350
internal const val BATTLE_ARENA_TINT_FADE_MILLIS = 250
internal const val BATTLE_BASIC_ATTACK_MOTION_MILLIS = 1_500
internal const val BATTLE_GUARD_MOTION_MILLIS = 1_000
internal const val BATTLE_SKILL_MOTION_MILLIS = 1_700
internal const val BATTLE_POWER_ATTACK_MOTION_MILLIS = 1_850
internal const val BATTLE_FINISHER_MOTION_MILLIS = 2_100
internal const val BATTLE_INTRO_DISPLAY_MILLIS = 1_500
internal const val BATTLE_TRAIT_DISPLAY_MILLIS = 1_200
internal const val BATTLE_MAX_MID_TRAIT_ACTIVATIONS = 2

internal data class BattleSkillTreeReviewRequest(
    val activityClassName: String,
    val arenaLevel: Int,
    val allocatedPoints: Int,
    val languageTag: String,
)

internal fun battleSkillTreeReviewRequest(
    enabled: Boolean,
    language: AppLanguage,
): BattleSkillTreeReviewRequest? = if (enabled) {
    BattleSkillTreeReviewRequest(
        activityClassName = "com.nullplaying.ui.ArenaProgressionQaActivity",
        arenaLevel = ArenaSkillTreeRules.maxArenaLevel,
        allocatedPoints = 0,
        languageTag = language.languageTag,
    )
} else {
    null
}
internal const val BATTLE_FINAL_ENDPOINT_CONFIRM_MILLIS = 650
internal const val BATTLE_RESULT_ENTER_MILLIS = 450
internal const val BATTLE_RESULT_EXIT_MILLIS = 250
internal const val BATTLE_MATCH_READY_TIMEOUT_MILLIS = 10L * 60L * 1_000L
internal const val BATTLE_ENTRY_BUTTON_FONT_SIZE_SP = 18
internal const val BATTLE_ENTRY_BUTTON_LINE_HEIGHT_SP = 22
internal const val BATTLE_NARRATIVE_MIN_ANCHORS = 3
internal const val BATTLE_NARRATIVE_MAX_ANCHORS = 5
internal const val BATTLE_MENU_STAGE_HEIGHT_DP = 218
internal const val BATTLE_RECENT_HISTORY_LIMIT = 10

private val BattleCrimson = Color(0xFFB94B58)
private val BattleBlue = Color(0xFF78A8D8)
private val BattleGreen = Color(0xFF8BCB84)
private val BattleNeutral = Color(0xFFB6BBC4)
private val BattleSkillAccent = Color(0xFFC9A7FF)
private val BattleTraitAccent = Color(0xFF55E6C1)

internal enum class BattleNarrativeRole {
    USER,
    OPPONENT,
    SKILL,
    ITEM,
    TRAIT,
}

internal data class BattleNarrativeSpan(
    val start: Int,
    val endExclusive: Int,
    val role: BattleNarrativeRole,
    val stableId: String = "",
    val rarity: String = "",
    val skillKind: BattleSkillKind? = null,
)

internal data class BattleLogEntry(
    val ordinal: Int,
    val text: String,
)

internal enum class BattleParagraphState {
    ATTACK,
    DRAW,
    HIT,
}

internal enum class BattlePlaybackBeatKind {
    OPENING,
    OPENING_TRAIT,
    MID_BATTLE_TRAIT,
    ACTION,
}

internal data class BattlePlaybackBeat(
    val text: String,
    val sceneIndex: Int,
    val roundIndex: Int,
    val actorSide: BattleSide,
    val userState: BattleParagraphState,
    val opponentState: BattleParagraphState,
    val userPower: Int,
    val opponentPower: Int,
    val userEnergyBefore: Int,
    val userEnergyAfter: Int,
    val opponentEnergyBefore: Int,
    val opponentEnergyAfter: Int,
    val arenaTintSide: BattleSide?,
    val effectKey: String,
    val motionDurationMillis: Int,
    val kind: BattlePlaybackBeatKind = BattlePlaybackBeatKind.ACTION,
    val traitId: String = "",
)

internal data class BattlePlaybackPlan(
    val turns: List<BattlePlaybackBeat>,
    val sequence: List<BattlePlaybackBeat> = turns,
)

internal fun battleLogEntries(lines: List<String>): List<BattleLogEntry> =
    lines.mapIndexed { index, text -> BattleLogEntry(index + 1, text) }.asReversed()

internal fun battleNarrativeSpans(
    text: String,
    userName: String,
    opponentName: String,
    skills: List<BattleSkillSnapshot>,
    equipment: List<BattleEquipmentSnapshot>,
    traits: List<BattleTraitDefinition> = emptyList(),
): List<BattleNarrativeSpan> {
    data class Candidate(
        val text: String,
        val role: BattleNarrativeRole,
        val stableId: String = "",
        val rarity: String = "",
        val skillKind: BattleSkillKind? = null,
        val priority: Int,
    )

    val candidates = buildList {
        equipment.forEach { item ->
            add(
                Candidate(
                    text = item.displayName,
                    role = BattleNarrativeRole.ITEM,
                    stableId = item.itemId,
                    rarity = item.rarity,
                    priority = 3,
                ),
            )
        }
        skills.forEach { skill ->
            add(
                Candidate(
                    text = skill.displayName,
                    role = BattleNarrativeRole.SKILL,
                    stableId = skill.skillId,
                    skillKind = skill.kind,
                    priority = 2,
                ),
            )
        }
        traits.forEach { trait ->
            add(
                Candidate(
                    text = trait.nameKo,
                    role = BattleNarrativeRole.TRAIT,
                    stableId = trait.id,
                    priority = 2,
                ),
            )
        }
        add(Candidate(userName, BattleNarrativeRole.USER, priority = 1))
        add(Candidate(opponentName, BattleNarrativeRole.OPPONENT, priority = 1))
    }
        .filter { it.text.isNotBlank() }
        .groupBy { it.text }
        .values
        .mapNotNull { matches ->
            val styles = matches.map { listOf(it.role, it.stableId, it.rarity, it.skillKind) }.distinct()
            matches.firstOrNull().takeIf { styles.size == 1 }
        }
        .sortedWith(compareByDescending<Candidate> { it.text.length }.thenByDescending { it.priority })

    val occupied = BooleanArray(text.length)
    val spans = mutableListOf<BattleNarrativeSpan>()
    candidates.forEach { candidate ->
        var start = text.indexOf(candidate.text)
        while (start >= 0) {
            val end = start + candidate.text.length
            if ((start until end).none { occupied[it] }) {
                (start until end).forEach { occupied[it] = true }
                spans += BattleNarrativeSpan(
                    start = start,
                    endExclusive = end,
                    role = candidate.role,
                    stableId = candidate.stableId,
                    rarity = candidate.rarity,
                    skillKind = candidate.skillKind,
                )
            }
            start = text.indexOf(candidate.text, startIndex = end)
        }
    }
    return spans.sortedBy(BattleNarrativeSpan::start)
}

internal fun battleSkillAccent(@Suppress("UNUSED_PARAMETER") skill: BattleSkillSnapshot): Color =
    BattleSkillAccent

internal fun battleTraitAccent(@Suppress("UNUSED_PARAMETER") trait: BattleTraitDefinition): Color =
    BattleTraitAccent

internal enum class BattleStance(
    val label: String,
    val summary: String,
) {
    ASSAULT("맹공", "초반 주도권을 노립니다"),
    BALANCED("균형", "공격과 수비를 고르게 운용합니다"),
    GUARD("수호", "버틴 뒤 반격의 순간을 기다립니다"),
}

internal enum class BattleFlowStep {
    MATCHING,
    MATCH_READY,
    PLAYING,
    RESULT,
}

internal enum class BattleSessionPhase {
    IDLE,
    MATCHING,
    MATCH_READY,
    IN_BATTLE,
}

internal fun battleOutcomeVisible(step: BattleFlowStep): Boolean = step == BattleFlowStep.RESULT

internal fun battleSessionPhase(
    step: BattleFlowStep?,
    preparing: Boolean,
): BattleSessionPhase = when {
    preparing || step == BattleFlowStep.MATCHING -> BattleSessionPhase.MATCHING
    step == BattleFlowStep.MATCH_READY -> BattleSessionPhase.MATCH_READY
    step == BattleFlowStep.PLAYING || step == BattleFlowStep.RESULT -> BattleSessionPhase.IN_BATTLE
    else -> BattleSessionPhase.IDLE
}

internal enum class BattleEntryPrimaryAction {
    OPEN_SKILL_TREE,
    FIND_OPPONENT,
    START_BATTLE,
    REFILL_TICKETS,
    NONE,
}

/** A persisted unlock cannot bypass the real hero-level gate after a QA projection is removed. */
internal fun arenaEntryUnlocked(
    progressionUnlocked: Boolean,
    heroLevel: Long,
    ignoreHeroLevelGate: Boolean,
): Boolean = progressionUnlocked &&
    (ignoreHeroLevelGate || heroLevel >= ArenaProgressionRules.MIN_HERO_LEVEL)

internal data class BattleLoadoutSummary(
    val attackSkillCount: Int,
    val supportSkillCount: Int,
    val availablePoints: Int,
) {
    init {
        require(attackSkillCount >= 0 && supportSkillCount >= 0 && availablePoints >= 0)
    }

    val hasAttackSkill: Boolean get() = attackSkillCount > 0
}

internal fun battleLoadoutSummary(model: ArenaSkillTreeUiModel): BattleLoadoutSummary =
    BattleLoadoutSummary(
        attackSkillCount = model.nodes.count {
            it.kind == ArenaSkillTreeNodeKind.ATTACK && it.rank > 0
        },
        supportSkillCount = model.nodes.count {
            it.kind == ArenaSkillTreeNodeKind.SUPPORT && it.rank > 0
        },
        availablePoints = model.availablePoints,
    )

internal fun battleLoadoutDetail(
    summary: BattleLoadoutSummary,
    language: AppLanguage,
): String = when (language) {
    AppLanguage.KOREAN ->
        "공격 ${summary.attackSkillCount} · 보조 ${summary.supportSkillCount} · ${summary.availablePoints}포인트 남음"
    AppLanguage.ENGLISH ->
        "Attack ${summary.attackSkillCount} · Support ${summary.supportSkillCount} · ${summary.availablePoints} points left"
    AppLanguage.JAPANESE ->
        "攻撃${summary.attackSkillCount}・補助${summary.supportSkillCount}・残り${summary.availablePoints}ポイント"
}

internal fun battleEntryPrimaryAction(
    entriesRemaining: Int,
    step: BattleFlowStep?,
    unlimitedEntries: Boolean,
    entryAllowed: Boolean,
    hasAttackSkill: Boolean,
    availablePoints: Int = 0,
    rewardedRefillAvailable: Boolean = false,
    dailyLimitReached: Boolean = false,
): BattleEntryPrimaryAction = when {
    step == BattleFlowStep.MATCHING -> BattleEntryPrimaryAction.NONE
    !entryAllowed -> BattleEntryPrimaryAction.NONE
    availablePoints > 0 -> BattleEntryPrimaryAction.OPEN_SKILL_TREE
    !hasAttackSkill -> BattleEntryPrimaryAction.OPEN_SKILL_TREE
    !unlimitedEntries && dailyLimitReached -> BattleEntryPrimaryAction.NONE
    step == BattleFlowStep.MATCH_READY &&
        entryAllowed && hasAttackSkill && (unlimitedEntries || entriesRemaining > 0) ->
        BattleEntryPrimaryAction.START_BATTLE
    step == BattleFlowStep.MATCH_READY -> BattleEntryPrimaryAction.NONE
    !unlimitedEntries && entriesRemaining <= 0 && rewardedRefillAvailable ->
        BattleEntryPrimaryAction.REFILL_TICKETS
    !unlimitedEntries && entriesRemaining <= 0 -> BattleEntryPrimaryAction.NONE
    else -> BattleEntryPrimaryAction.FIND_OPPONENT
}

internal fun battleEntryButtonLabel(
    entriesRemaining: Int,
    step: BattleFlowStep?,
    unlimitedEntries: Boolean = false,
    arenaUnlocked: Boolean = true,
    entryAllowed: Boolean = true,
    hasAttackSkill: Boolean = true,
    availablePoints: Int = 0,
    rewardedRefillAvailable: Boolean = false,
    rewardedRefillCount: Int = BATTLE_ENTRY_CAPACITY,
    rewardedRefillLimitReached: Boolean = false,
    dailyLimitReached: Boolean = false,
    language: AppLanguage = AppLanguage.KOREAN,
): String = when {
    step == BattleFlowStep.MATCHING -> when (language) {
        AppLanguage.KOREAN -> "상대 찾는 중…"
        AppLanguage.ENGLISH -> "Finding opponent…"
        AppLanguage.JAPANESE -> "対戦相手を検索中…"
    }
    !arenaUnlocked -> when (language) {
        AppLanguage.KOREAN -> "Lv.${ArenaProgressionRules.MIN_HERO_LEVEL} 필요"
        AppLanguage.ENGLISH -> "Requires Lv.${ArenaProgressionRules.MIN_HERO_LEVEL}"
        AppLanguage.JAPANESE -> "Lv.${ArenaProgressionRules.MIN_HERO_LEVEL}が必要"
    }
    !entryAllowed -> when (language) {
        AppLanguage.KOREAN -> "준비 중…"
        AppLanguage.ENGLISH -> "Preparing…"
        AppLanguage.JAPANESE -> "準備中…"
    }
    availablePoints > 0 -> when (language) {
        AppLanguage.KOREAN -> "${availablePoints}포인트 사용"
        AppLanguage.ENGLISH -> "Spend $availablePoints ${if (availablePoints == 1) "point" else "points"}"
        AppLanguage.JAPANESE -> "${availablePoints}ポイント使用"
    }
    !hasAttackSkill -> when (language) {
        AppLanguage.KOREAN -> "공격 스킬 선택"
        AppLanguage.ENGLISH -> "Choose attack skill"
        AppLanguage.JAPANESE -> "攻撃スキルを選択"
    }
    !unlimitedEntries && dailyLimitReached -> when (language) {
        AppLanguage.KOREAN -> "오늘 출전 완료 $BATTLE_ENTRY_DAILY_LIMIT/$BATTLE_ENTRY_DAILY_LIMIT"
        AppLanguage.ENGLISH -> "Daily entries complete $BATTLE_ENTRY_DAILY_LIMIT/$BATTLE_ENTRY_DAILY_LIMIT"
        AppLanguage.JAPANESE -> "本日の出場完了 $BATTLE_ENTRY_DAILY_LIMIT/$BATTLE_ENTRY_DAILY_LIMIT"
    }
    step == BattleFlowStep.MATCH_READY -> when (language) {
        AppLanguage.KOREAN -> "결투 시작"
        AppLanguage.ENGLISH -> "Start duel"
        AppLanguage.JAPANESE -> "決闘開始"
    }
    !unlimitedEntries && entriesRemaining <= 0 && rewardedRefillAvailable -> when (language) {
        AppLanguage.KOREAN -> "광고로 ${rewardedRefillCount}회 충전"
        AppLanguage.ENGLISH -> "Watch ad for $rewardedRefillCount ${if (rewardedRefillCount == 1) "entry" else "entries"}"
        AppLanguage.JAPANESE -> "広告で${rewardedRefillCount}回分回復"
    }
    !unlimitedEntries && entriesRemaining <= 0 && rewardedRefillLimitReached -> when (language) {
        AppLanguage.KOREAN -> "오늘 광고 충전 1/1"
        AppLanguage.ENGLISH -> "Ad refill today 1/1"
        AppLanguage.JAPANESE -> "本日の広告回復 1/1"
    }
    !unlimitedEntries && entriesRemaining <= 0 -> when (language) {
        AppLanguage.KOREAN -> "출전권 회복 중"
        AppLanguage.ENGLISH -> "Entries recharging"
        AppLanguage.JAPANESE -> "出場券を回復中"
    }
    else -> when (language) {
        AppLanguage.KOREAN -> "상대 찾기"
        AppLanguage.ENGLISH -> "Find opponent"
        AppLanguage.JAPANESE -> "対戦相手を探す"
    }
}

internal fun arenaTicketValueLabel(
    entriesRemaining: Int,
    unlimitedEntries: Boolean,
    language: AppLanguage,
): String = if (unlimitedEntries) {
    when (language) {
        AppLanguage.ENGLISH -> "Unlimited"
        AppLanguage.JAPANESE -> "無制限"
        else -> "무제한"
    }
} else {
    entriesRemaining.coerceIn(0, BATTLE_ENTRY_CAPACITY).toString()
}

internal fun arenaTicketStatusLabel(
    entriesRemaining: Int,
    unlimitedEntries: Boolean,
    entryRecoveryCountdown: String?,
    language: AppLanguage,
    dailyLimitReached: Boolean = false,
    dailyResetCountdown: String? = null,
): String = when {
    unlimitedEntries -> when (language) {
        AppLanguage.ENGLISH -> "QA only"
        AppLanguage.JAPANESE -> "QA専用"
        else -> "QA 전용"
    }
    dailyLimitReached -> when (language) {
        AppLanguage.ENGLISH -> dailyResetCountdown?.let { "Reset in $it" }
            ?: "Checking server time…"
        AppLanguage.JAPANESE -> dailyResetCountdown?.let { "リセットまで $it" }
            ?: "サーバー時刻を確認中…"
        else -> dailyResetCountdown?.let { "초기화까지 $it" }
            ?: "서버 시간 확인 중…"
    }
    entriesRemaining >= BATTLE_ENTRY_CAPACITY -> when (language) {
        AppLanguage.ENGLISH -> "Fully charged"
        AppLanguage.JAPANESE -> "充填完了"
        else -> "충전 완료"
    }
    entryRecoveryCountdown != null -> when (language) {
        AppLanguage.ENGLISH -> "Next entry: $entryRecoveryCountdown"
        AppLanguage.JAPANESE -> "1枚回復中：$entryRecoveryCountdown"
        else -> "1장 충전중 : $entryRecoveryCountdown"
    }
    else -> when (language) {
        AppLanguage.ENGLISH -> "Refilling"
        AppLanguage.JAPANESE -> "回復中"
        else -> "충전 중"
    }
}

internal fun arenaTicketRecoveryProgress(
    entriesRemaining: Int,
    unlimitedEntries: Boolean,
    recoveryRemainingMillis: Long?,
): Float {
    if (unlimitedEntries || entriesRemaining >= BATTLE_ENTRY_CAPACITY) return 1f

    return recoveryRemainingMillis?.let { remainingMillis ->
        1f - (
            remainingMillis.coerceIn(0L, BATTLE_ENTRY_RECOVERY_INTERVAL_MILLIS).toFloat() /
                BATTLE_ENTRY_RECOVERY_INTERVAL_MILLIS.toFloat()
            )
    }?.coerceIn(0f, 1f) ?: 0f
}

internal fun battleStageStatusLabel(
    step: BattleFlowStep?,
    arenaUnlocked: Boolean,
    entryAllowed: Boolean,
    hasAttackSkill: Boolean,
    practice: Boolean,
    entriesRemaining: Int = 1,
    unlimitedEntries: Boolean = false,
): String = when {
    step == BattleFlowStep.MATCHING -> "상대 찾는 중"
    !arenaUnlocked -> "Lv.${ArenaProgressionRules.MIN_HERO_LEVEL} 필요"
    !entryAllowed -> "준비 중"
    step == BattleFlowStep.MATCH_READY && !hasAttackSkill -> "스킬 필요"
    step == BattleFlowStep.MATCH_READY && !unlimitedEntries && entriesRemaining <= 0 -> "출전권 필요"
    step == BattleFlowStep.MATCH_READY -> "시작 가능"
    !hasAttackSkill -> "스킬 필요"
    practice -> "오늘 성장 완료"
    else -> "준비 완료"
}

internal fun battleEntryCountForSession(
    storedEntries: Int,
    unlimitedEntries: Boolean,
): Int = if (unlimitedEntries) BATTLE_DAILY_ENTRIES else storedEntries

internal fun battleMatchReadyExpiresAt(readyAtMillis: Long): Long =
    if (readyAtMillis > Long.MAX_VALUE - BATTLE_MATCH_READY_TIMEOUT_MILLIS) {
        Long.MAX_VALUE
    } else {
        readyAtMillis + BATTLE_MATCH_READY_TIMEOUT_MILLIS
    }

internal fun battleMatchReadyExpired(
    expiresAtMillis: Long,
    nowMillis: Long,
): Boolean = nowMillis >= expiresAtMillis

internal fun battleMatchReadyCountdownLabel(
    expiresAtMillis: Long,
    nowMillis: Long,
    language: AppLanguage = AppLanguage.KOREAN,
): String {
    val remainingMillis = (expiresAtMillis - nowMillis).coerceAtLeast(0L)
    val remainingSeconds = (remainingMillis + 999L) / 1_000L
    return if (remainingSeconds >= 60L) {
        val minutes = (remainingSeconds + 59L) / 60L
        when (language) {
            AppLanguage.KOREAN -> "${minutes}분 후 자동 취소"
            AppLanguage.ENGLISH -> "Auto-cancels in $minutes ${if (minutes == 1L) "min" else "mins"}"
            AppLanguage.JAPANESE -> "${minutes}分後に自動キャンセル"
        }
    } else {
        when (language) {
            AppLanguage.KOREAN -> "${remainingSeconds}초 후 자동 취소"
            AppLanguage.ENGLISH -> "Auto-cancels in $remainingSeconds ${if (remainingSeconds == 1L) "sec" else "secs"}"
            AppLanguage.JAPANESE -> "${remainingSeconds}秒後に自動キャンセル"
        }
    }
}

internal fun battleMatchReadyTitle(language: AppLanguage): String = when (language) {
    AppLanguage.KOREAN -> "준비 완료"
    AppLanguage.ENGLISH -> "Ready"
    AppLanguage.JAPANESE -> "準備完了"
}

internal fun battleMatchReadyAccessibilityLabel(
    countdownLabel: String,
    language: AppLanguage,
): String = when (language) {
    AppLanguage.KOREAN -> "결투 준비 완료, $countdownLabel"
    AppLanguage.ENGLISH -> "Duel ready, $countdownLabel"
    AppLanguage.JAPANESE -> "決闘準備完了、$countdownLabel"
}

internal fun battleSettlementAllowed(step: BattleFlowStep): Boolean =
    step == BattleFlowStep.PLAYING || step == BattleFlowStep.RESULT

internal fun battlePlaybackProgressLabel(
    lineIndex: Int,
    lineCount: Int,
    language: AppLanguage = AppLanguage.KOREAN,
): String {
    val safeCount = lineCount.coerceAtLeast(1)
    val safeIndex = lineIndex.coerceIn(0, safeCount - 1)
    return when (language) {
        AppLanguage.KOREAN -> "전투 문장 ${safeIndex + 1} / $safeCount"
        AppLanguage.ENGLISH -> "Battle line ${safeIndex + 1} / $safeCount"
        AppLanguage.JAPANESE -> "戦闘文 ${safeIndex + 1} / $safeCount"
    }
}

internal fun arenaBattleProgressDescription(language: AppLanguage): String = when (language) {
    AppLanguage.KOREAN -> "결투장 전투 진행"
    AppLanguage.ENGLISH -> "Arena battle in progress"
    AppLanguage.JAPANESE -> "闘技場で戦闘中"
}

internal fun arenaBattleProgressStateLabel(
    opening: Boolean,
    openingTrait: Boolean,
    trait: Boolean,
    lineIndex: Int,
    lineCount: Int,
    language: AppLanguage,
): String = when {
    opening -> when (language) {
        AppLanguage.KOREAN -> "결투 시작 안내"
        AppLanguage.ENGLISH -> "Duel introduction"
        AppLanguage.JAPANESE -> "決闘開始案内"
    }
    openingTrait -> when (language) {
        AppLanguage.KOREAN -> "결투 특성 준비"
        AppLanguage.ENGLISH -> "Trait ready"
        AppLanguage.JAPANESE -> "特性準備"
    }
    trait -> when (language) {
        AppLanguage.KOREAN -> "결투 특성 발동"
        AppLanguage.ENGLISH -> "Trait activated"
        AppLanguage.JAPANESE -> "特性発動"
    }
    else -> battlePlaybackProgressLabel(lineIndex, lineCount, language)
}

internal fun arenaBattleMomentumLabel(
    side: BattleSide?,
    userName: String,
    opponentName: String,
    language: AppLanguage,
): String = when (side) {
    BattleSide.USER -> when (language) {
        AppLanguage.KOREAN -> "${userName}의 공세"
        AppLanguage.ENGLISH -> "$userName attacks"
        AppLanguage.JAPANESE -> "${userName}の攻勢"
    }
    BattleSide.OPPONENT -> when (language) {
        AppLanguage.KOREAN -> "${opponentName}의 공세"
        AppLanguage.ENGLISH -> "$opponentName attacks"
        AppLanguage.JAPANESE -> "${opponentName}の攻勢"
    }
    null -> when (language) {
        AppLanguage.KOREAN -> "호각의 공방"
        AppLanguage.ENGLISH -> "Even exchange"
        AppLanguage.JAPANESE -> "互角の攻防"
    }
}

internal fun arenaBattleEnergyAccessibilityLabel(
    userName: String,
    opponentName: String,
    language: AppLanguage,
): String = when (language) {
    AppLanguage.KOREAN -> "$userName / $opponentName 결투 에너지"
    AppLanguage.ENGLISH -> "$userName / $opponentName duel energy"
    AppLanguage.JAPANESE -> "$userName / $opponentName 決闘エネルギー"
}

internal fun battleNarrationSentences(text: String): List<String> {
    val sanitized = stripBattleDialogueText(text)
        .replace(Regex("\\s+"), " ")
        .trim()
    if (sanitized.isBlank()) return emptyList()

    return Regex("[^.!?…。！？]+(?:[.!?…。！？]+|$)")
        .findAll(sanitized)
        .map { it.value.trim() }
        .filter(String::isNotBlank)
        .toList()
}

internal fun battlePlaybackLines(narrative: BattleQaNarrative): List<String> =
    if (narrative.source.startsWith("arena_")) {
        narrative.scenes.map { it.text }.filter(String::isNotBlank)
    } else battleNarrationSentences(narrative.scenes.joinToString(" ") { scene -> scene.text })
        .ifEmpty { listOf(battleFallbackNarrativeLine(narrative.languageTag)) }

internal fun battleFallbackNarrativeLine(languageTag: String): String =
    when (languageTag.lowercase().substringBefore('-')) {
        "en" -> "The exchange continued."
        "ja" -> "攻防が続いた。"
        else -> "공방이 이어졌다."
    }

internal fun battleParagraphStateLabel(state: BattleParagraphState): String = when (state) {
    BattleParagraphState.ATTACK -> "공격"
    BattleParagraphState.DRAW -> "무승부"
    BattleParagraphState.HIT -> "피격"
}

private fun distributedBattleValue(total: Int, index: Int, count: Int): Int {
    val safeCount = count.coerceAtLeast(1)
    val before = total.toLong() * index / safeCount
    val after = total.toLong() * (index + 1) / safeCount
    return (after - before).toInt()
}

private fun interpolatedBattleValue(start: Int, end: Int, index: Int, count: Int): Int {
    val safeCount = count.coerceAtLeast(1)
    return (
        start.toLong() +
            (end.toLong() - start.toLong()) * index / safeCount
        ).toInt()
}

internal fun battlePlaybackBeats(
    battle: ProjectionBattleResult,
    narrative: BattleQaNarrative,
): List<BattlePlaybackBeat> {
    if (battle.rounds.isEmpty()) {
        return battlePlaybackLines(narrative).mapIndexed { index, text ->
            BattlePlaybackBeat(
                text = text,
                sceneIndex = index,
                roundIndex = 0,
                actorSide = BattleSide.USER,
                userState = BattleParagraphState.DRAW,
                opponentState = BattleParagraphState.DRAW,
                userPower = 0,
                opponentPower = 0,
                userEnergyBefore = ProjectionBattleEngine.PROJECTION_MAX_HP,
                userEnergyAfter = ProjectionBattleEngine.PROJECTION_MAX_HP,
                opponentEnergyBefore = ProjectionBattleEngine.PROJECTION_MAX_HP,
                opponentEnergyAfter = ProjectionBattleEngine.PROJECTION_MAX_HP,
                arenaTintSide = null,
                effectKey = "CLASH",
                motionDurationMillis = BATTLE_BASIC_ATTACK_MOTION_MILLIS,
            )
        }
    }

    val scenes = narrative.scenes.ifEmpty {
        listOf(
            BattleQaScene(
                text = battleFallbackNarrativeLine(narrative.languageTag),
                effectKey = "CLASH",
            ),
        )
    }
    val roundIndexes = expandedLocalBattleRoundIndexes(
        rounds = battle.rounds,
        phaseCount = scenes.size,
        skipOpeningRound = battleStartsWithSilentExchange(
            battleId = battle.battleId,
            roundCount = battle.rounds.size,
            phaseCount = scenes.size,
        ),
    )
    val scenesByRound = mutableMapOf<Int, MutableList<Pair<Int, BattleQaScene>>>()
    scenes.forEachIndexed { sceneIndex, scene ->
        val roundIndex = roundIndexes[sceneIndex].coerceIn(0, battle.rounds.lastIndex)
        scenesByRound.getOrPut(roundIndex, ::mutableListOf).add(sceneIndex to scene)
    }
    return battle.rounds.flatMapIndexed { roundIndex, round ->
        val attachedScenes = scenesByRound[roundIndex].orEmpty()
        val isFinalRound = roundIndex == battle.rounds.lastIndex
        val narratedSide = battleNarratedAction(round).actor
        val orderedActions = when {
            isFinalRound &&
                battle.outcome == BattleOutcome.USER_WIN &&
                round.opponentAction.selfDamage > 0 ->
                listOf(round.userAction, round.opponentAction)
            isFinalRound &&
                battle.outcome == BattleOutcome.USER_LOSS &&
                round.userAction.selfDamage > 0 ->
                listOf(round.opponentAction, round.userAction)
            isFinalRound && battle.outcome == BattleOutcome.USER_WIN ->
                listOf(round.opponentAction, round.userAction)
            isFinalRound && battle.outcome == BattleOutcome.USER_LOSS ->
                listOf(round.userAction, round.opponentAction)
            round.number % 2 == 0 -> listOf(round.opponentAction, round.userAction)
            else -> listOf(round.userAction, round.opponentAction)
        }
        val visibleActions = orderedActions.filter { action ->
            action.damage > 0 || action.healing > 0 || action.selfDamage > 0 ||
                battleActionTextVisible(action)
        }.ifEmpty { listOf(orderedActions.last()) }
        val roundTargetUserEnergy =
            if (isFinalRound && battle.outcome == BattleOutcome.USER_LOSS) 0 else round.userHpAfter
        val roundTargetOpponentEnergy =
            if (isFinalRound && battle.outcome == BattleOutcome.USER_WIN) 0 else round.opponentHpAfter
        val playbackActions = if (
            isFinalRound && roundTargetUserEnergy == 0 && roundTargetOpponentEnergy == 0
        ) {
            listOf(visibleActions.maxBy { it.damage + it.healing + it.selfDamage })
        } else {
            visibleActions
        }
        var userEnergy = round.userHpBefore
        var opponentEnergy = round.opponentHpBefore

        playbackActions.mapIndexed { actionIndex, action ->
            val userEnergyBefore = userEnergy
            val opponentEnergyBefore = opponentEnergy
            val isLastAction = actionIndex == playbackActions.lastIndex
            val nominalUserEnergy = when (action.actor) {
                BattleSide.USER -> userEnergy - action.selfDamage + action.healing
                BattleSide.OPPONENT -> userEnergy - action.damage
            }.coerceIn(0, battle.user.maxHp)
            val nominalOpponentEnergy = when (action.actor) {
                BattleSide.USER -> opponentEnergy - action.damage
                BattleSide.OPPONENT -> opponentEnergy - action.selfDamage + action.healing
            }.coerceIn(0, battle.opponent.maxHp)
            userEnergy = if (isLastAction) {
                roundTargetUserEnergy
            } else {
                nominalUserEnergy.coerceAtLeast(1)
            }
            opponentEnergy = if (isLastAction) {
                roundTargetOpponentEnergy
            } else {
                nominalOpponentEnergy.coerceAtLeast(1)
            }

            val userLostEnergy = (userEnergyBefore - userEnergy).coerceAtLeast(0)
            val opponentLostEnergy = (opponentEnergyBefore - opponentEnergy).coerceAtLeast(0)
            val (userState, opponentState) = when {
                action.actor == BattleSide.USER && opponentLostEnergy > 0 ->
                    BattleParagraphState.ATTACK to BattleParagraphState.HIT
                action.actor == BattleSide.OPPONENT && userLostEnergy > 0 ->
                    BattleParagraphState.HIT to BattleParagraphState.ATTACK
                action.actor == BattleSide.USER && userLostEnergy > 0 ->
                    BattleParagraphState.HIT to BattleParagraphState.DRAW
                action.actor == BattleSide.OPPONENT && opponentLostEnergy > 0 ->
                    BattleParagraphState.DRAW to BattleParagraphState.HIT
                else -> BattleParagraphState.DRAW to BattleParagraphState.DRAW
            }
            val textVisible = battleActionTextVisible(action)
            val actionScenes = if (
                action.actor == narratedSide &&
                action.resolution !in setOf(
                    BattleActionResolution.BLOCKED,
                    BattleActionResolution.EVADED,
                    BattleActionResolution.MISSED,
                )
            ) {
                attachedScenes
            } else {
                emptyList()
            }
            val actorProjection = when (action.actor) {
                BattleSide.USER -> battle.user
                BattleSide.OPPONENT -> battle.opponent
            }
            val opponentProjection = when (action.actor) {
                BattleSide.USER -> battle.opponent
                BattleSide.OPPONENT -> battle.user
            }
            val defenderDefeated = when (action.actor) {
                BattleSide.USER -> opponentEnergy == 0
                BattleSide.OPPONENT -> userEnergy == 0
            }
            val actorDefeated = when (action.actor) {
                BattleSide.USER -> userEnergy == 0
                BattleSide.OPPONENT -> opponentEnergy == 0
            }
            val fallbackText = if (
                textVisible && actionScenes.isEmpty()
            ) {
                val language = AppLanguage.fromLanguageTag(narrative.languageTag) ?: AppLanguage.KOREAN
                if (action.resolution in setOf(
                        BattleActionResolution.BLOCKED,
                        BattleActionResolution.EVADED,
                        BattleActionResolution.MISSED,
                    )
                ) {
                    battleZeroDamageActionText(
                        name = actorProjection.displayName,
                        opponentName = opponentProjection.displayName,
                        action = action,
                        skills = actorProjection.skills,
                        language = language,
                        variation = round.number * 2 + action.actor.ordinal,
                        defenderDefeated = defenderDefeated,
                        actorDefeated = actorDefeated,
                    )
                } else {
                    BattleLocalNarrativeEngine.actionTextForTest(
                        name = actorProjection.displayName,
                        action = action,
                        skills = actorProjection.skills,
                        equipment = actorProjection.equipment,
                        language = language,
                        variation = round.number * 2 + action.actor.ordinal,
                        heroClass = actorProjection.heroClass,
                        opponentName = opponentProjection.displayName,
                    )
                }
            } else {
                ""
            }
            BattlePlaybackBeat(
                text = actionScenes.joinToString(" ") { it.second.text }.trim().ifEmpty { fallbackText },
                sceneIndex = actionScenes.firstOrNull()?.first ?: -1,
                roundIndex = roundIndex,
                actorSide = action.actor,
                userState = userState,
                opponentState = opponentState,
                userPower = opponentLostEnergy,
                opponentPower = userLostEnergy,
                userEnergyBefore = userEnergyBefore,
                userEnergyAfter = userEnergy,
                opponentEnergyBefore = opponentEnergyBefore,
                opponentEnergyAfter = opponentEnergy,
                arenaTintSide = battleArenaTintSide(action),
                effectKey = when {
                    action.finisher -> "FINISHER"
                    action.powerAttack -> "IMPACT"
                    else -> actionScenes.firstOrNull()?.second?.effectKey ?: "CLASH"
                },
                motionDurationMillis = battleActionMotionDurationMillis(action),
            )
        }
    }
}

internal fun battleActionMotionDurationMillis(action: BattleRoundAction): Int = when {
    action.finisher -> BATTLE_FINISHER_MOTION_MILLIS
    action.powerAttack -> BATTLE_POWER_ATTACK_MOTION_MILLIS
    action.kind == BattleActionKind.SKILL -> BATTLE_SKILL_MOTION_MILLIS
    action.kind == BattleActionKind.GUARD -> BATTLE_GUARD_MOTION_MILLIS
    else -> BATTLE_BASIC_ATTACK_MOTION_MILLIS
}

internal fun battleRoundMotionDurationMillis(round: BattleRound): Int = maxOf(
    battleActionMotionDurationMillis(round.userAction),
    battleActionMotionDurationMillis(round.opponentAction),
)

internal fun battleNextAttackDelayMillis(motionDurationMillis: Int): Int =
    (BATTLE_MIN_ATTACK_INTERVAL_MILLIS - motionDurationMillis).coerceAtLeast(0)

internal fun battleEnergyImpactDurationMillis(motionDurationMillis: Int): Int =
    minOf(BATTLE_ENERGY_IMPACT_MILLIS, motionDurationMillis.coerceAtLeast(0))

internal fun battleActionTextVisible(action: BattleRoundAction): Boolean =
    action.kind == BattleActionKind.SKILL ||
        action.resolution in setOf(
            BattleActionResolution.BLOCKED,
            BattleActionResolution.EVADED,
            BattleActionResolution.MISSED,
            BattleActionResolution.RECOVERED,
        )

internal fun battleArenaTintSide(action: BattleRoundAction): BattleSide? = when {
    action.kind == BattleActionKind.GUARD -> null
    action.damage == 0 && action.healing > 0 && action.selfDamage == 0 -> null
    action.damage == 0 && action.selfDamage > 0 -> when (action.actor) {
        BattleSide.USER -> BattleSide.OPPONENT
        BattleSide.OPPONENT -> BattleSide.USER
    }
    else -> action.actor
}

internal fun battleArenaTintDurationMillis(): Int =
    BATTLE_ARENA_TINT_HOLD_MILLIS + BATTLE_ARENA_TINT_FADE_MILLIS

internal fun battleArenaTintColor(side: BattleSide?): Color = when (side) {
    BattleSide.USER -> AqRed
    BattleSide.OPPONENT -> BattleBlue
    null -> Color(0xFF514461)
}

internal fun battlePostVisualEffectsDelayMillis(motionDurationMillis: Int): Int =
    (
        motionDurationMillis - maxOf(
            battleEnergyImpactDurationMillis(motionDurationMillis),
            battleArenaTintDurationMillis(),
        )
        ).coerceAtLeast(0) +
        battleNextAttackDelayMillis(motionDurationMillis)

internal fun battlePlaybackPlan(
    battle: ProjectionBattleResult,
    narrative: BattleQaNarrative,
): BattlePlaybackPlan {
    val turns = battlePlaybackBeats(battle, narrative)
    val firstTurn = turns.firstOrNull() ?: return BattlePlaybackPlan(turns = turns)
    val opening = firstTurn.copy(
        text = battleOpeningAnnouncement(
            userName = battle.user.displayName,
            opponentName = battle.opponent.displayName,
            languageTag = narrative.languageTag,
        ),
        sceneIndex = -1,
        roundIndex = -1,
        actorSide = BattleSide.USER,
        userState = BattleParagraphState.DRAW,
        opponentState = BattleParagraphState.DRAW,
        userPower = 0,
        opponentPower = 0,
        userEnergyBefore = battle.user.maxHp,
        userEnergyAfter = battle.user.maxHp,
        opponentEnergyBefore = battle.opponent.maxHp,
        opponentEnergyAfter = battle.opponent.maxHp,
        arenaTintSide = null,
        effectKey = "INTRO",
        motionDurationMillis = BATTLE_INTRO_DISPLAY_MILLIS,
        kind = BattlePlaybackBeatKind.OPENING,
    )
    val openingTraits = buildList {
        listOf(BattleSide.USER to battle.user, BattleSide.OPPONENT to battle.opponent).forEach { (side, owner) ->
            if (owner.hasHeroPathPlayback()) {
                battleHeroPathPreparedNodes(owner).forEach { node ->
                    add(opening.copy(
                        text = battleHeroPathAnnouncement(owner.displayName, node, narrative.languageTag, preparation = true),
                        actorSide = side,
                        effectKey = "TRAIT",
                        motionDurationMillis = BATTLE_TRAIT_DISPLAY_MILLIS,
                        kind = BattlePlaybackBeatKind.OPENING_TRAIT,
                        traitId = node.nodeId,
                    ))
                }
            } else if (side == BattleSide.USER) {
                owner.activeTraitIds.mapNotNull(BattleTraitCatalog.byId::get).forEach { trait ->
                    add(opening.copy(
                        text = battleOpeningTraitAnnouncement(owner.displayName, trait, narrative.languageTag),
                        effectKey = "TRAIT",
                        motionDurationMillis = BATTLE_TRAIT_DISPLAY_MILLIS,
                        kind = BattlePlaybackBeatKind.OPENING_TRAIT,
                        traitId = trait.id,
                    ))
                }
            }
        }
    }
    val triggersByTurn = battleMidBattleTraitTriggers(battle, turns).associateBy { it.turnIndex }
    val actualTriggers = battleHeroPathTriggers(battle, turns).groupBy { it.turnIndex }
    val combatSequence = buildList {
        turns.forEachIndexed { turnIndex, turn ->
            actualTriggers[turnIndex].orEmpty().filterNot { it.afterAction }.forEach { trigger ->
                add(battleHeroPathAnnouncementBeat(turn, trigger, battle, narrative.languageTag))
            }
            triggersByTurn[turnIndex]?.let { trigger ->
                add(
                    turn.copy(
                        text = battleTraitActivationAnnouncement(
                            ownerName = if (trigger.ownerSide == BattleSide.USER) {
                                battle.user.displayName
                            } else {
                                battle.opponent.displayName
                            },
                            trait = trigger.trait,
                            languageTag = narrative.languageTag,
                        ),
                        actorSide = trigger.ownerSide,
                        userState = BattleParagraphState.DRAW,
                        opponentState = BattleParagraphState.DRAW,
                        userPower = 0,
                        opponentPower = 0,
                        userEnergyAfter = turn.userEnergyBefore,
                        opponentEnergyAfter = turn.opponentEnergyBefore,
                        arenaTintSide = null,
                        effectKey = "TRAIT",
                        motionDurationMillis = BATTLE_TRAIT_DISPLAY_MILLIS,
                        kind = BattlePlaybackBeatKind.MID_BATTLE_TRAIT,
                        traitId = trigger.trait.id,
                    ),
                )
            }
            val survivalTriggers = actualTriggers[turnIndex].orEmpty().filter { it.afterAction }
            if (turn.userEnergyAfter == 0 || turn.opponentEnergyAfter == 0) {
                // Never append a new activation after the final endpoint. The survivor's
                // real round-end result belongs to the final attack, not a ghost action.
                add(turn.copy(text = (listOf(turn.text) + survivalTriggers.map { trigger ->
                    val owner = if (trigger.ownerSide == BattleSide.USER) battle.user else battle.opponent
                    battleHeroPathAnnouncement(owner.displayName, trigger.node, narrative.languageTag, survival = true)
                }).filter(String::isNotBlank).joinToString(" ")))
            } else {
                add(turn)
                survivalTriggers.forEach { trigger ->
                    add(battleHeroPathAnnouncementBeat(turn, trigger, battle, narrative.languageTag))
                }
            }
        }
    }
    return BattlePlaybackPlan(
        turns = turns,
        sequence = listOf(opening) + openingTraits + combatSequence,
    )
}

private fun NormalizedBattleProjection.hasHeroPathPlayback(): Boolean =
    heroPathBattleSnapshot.treeVersion >= HERO_PATH_TREE_VERSION

/** Only battle-owned, effective passive ranks are prepared; specials have not fired yet. */
private fun battleHeroPathPreparedNodes(owner: NormalizedBattleProjection): List<HeroPathNodeDefinition> {
    val snapshot = owner.heroPathBattleSnapshot
    val usedRanks = mutableMapOf<HeroPathNodeSlot, Int>()
    return snapshot.nodes.sortedWith(compareBy({ it.branch != snapshot.dominantBranch }, { it.nodeId }))
        .mapNotNull { owned ->
            val node = HeroPathCatalog.byNodeId[owned.nodeId]?.takeIf {
                it.heroClassAffinity == owner.heroClass && it.slot == owned.slot && owned.rank > 0
            } ?: return@mapNotNull null
            when (node.slot) {
                HeroPathNodeSlot.FOUNDATION_A, HeroPathNodeSlot.FOUNDATION_B, HeroPathNodeSlot.ADVANCED_TACTIC -> {
                    val used = usedRanks[node.slot] ?: 0
                    usedRanks[node.slot] = used + owned.rank
                    node.takeIf { used < 2 }
                }
                HeroPathNodeSlot.CHOICE_A -> node.takeIf {
                    node.branch == snapshot.dominantBranch && snapshot.choiceStance == HeroPathChoiceStance.A
                }
                HeroPathNodeSlot.CHOICE_B -> node.takeIf {
                    node.branch == snapshot.dominantBranch && snapshot.choiceStance == HeroPathChoiceStance.B
                }
                else -> null
            }
        }
}

private data class BattleHeroPathTrigger(
    val turnIndex: Int,
    val ownerSide: BattleSide,
    val node: HeroPathNodeDefinition,
    val afterAction: Boolean = false,
)

/** The action owns its proc. The round aggregate duplicates those traces except lethal survival. */
private fun battleHeroPathTriggers(
    battle: ProjectionBattleResult,
    turns: List<BattlePlaybackBeat>,
): List<BattleHeroPathTrigger> = buildList {
    turns.forEachIndexed { index, turn ->
        if (turn.userEnergyBefore <= 0 || turn.opponentEnergyBefore <= 0) return@forEachIndexed
        val round = battle.rounds.getOrNull(turn.roundIndex) ?: return@forEachIndexed
        val owner = if (turn.actorSide == BattleSide.USER) battle.user else battle.opponent
        val action = if (turn.actorSide == BattleSide.USER) round.userAction else round.opponentAction
        if (owner.hasHeroPathPlayback()) {
            action.talentEffects.filterNot { it.lethalSurvival }.distinctBy { it.sourceNodeId }.forEach { trace ->
                val owned = owner.heroPathBattleSnapshot.nodes.firstOrNull {
                    it.nodeId == trace.sourceNodeId && it.rank > 0 && it.effectFamily == trace.effectFamily
                } ?: return@forEach
                val node = HeroPathCatalog.byNodeId[owned.nodeId]?.takeIf {
                    it.heroClassAffinity == owner.heroClass && it.slot in setOf(
                        HeroPathNodeSlot.SPECIAL_A, HeroPathNodeSlot.SPECIAL_B, HeroPathNodeSlot.CORE,
                    )
                } ?: return@forEach
                add(BattleHeroPathTrigger(index, turn.actorSide, node))
            }
        }
        if (turns.getOrNull(index + 1)?.roundIndex == turn.roundIndex) return@forEachIndexed
        listOf(BattleSide.USER to battle.user, BattleSide.OPPONENT to battle.opponent).forEach { (side, survivor) ->
            if (!survivor.hasHeroPathPlayback()) return@forEach
            val ownAction = if (side == BattleSide.USER) round.userAction else round.opponentAction
            val incoming = if (side == BattleSide.USER) round.opponentAction else round.userAction
            val before = if (side == BattleSide.USER) round.userHpBefore else round.opponentHpBefore
            val after = if (side == BattleSide.USER) round.userHpAfter else round.opponentHpAfter
            if (after != 1 || before - incoming.damage - ownAction.selfDamage + ownAction.healing > 0) return@forEach
            val coreId = survivor.heroPathBattleSnapshot.activeCoreNodeId
            val trace = round.talentEffects.firstOrNull { it.lethalSurvival && it.sourceNodeId == coreId }
                ?: return@forEach
            val owned = survivor.heroPathBattleSnapshot.nodes.firstOrNull {
                it.nodeId == coreId && it.rank > 0 && it.effectFamily == trace.effectFamily
            } ?: return@forEach
            val node = HeroPathCatalog.byNodeId[owned.nodeId]?.takeIf {
                it.heroClassAffinity == survivor.heroClass && it.slot == HeroPathNodeSlot.CORE
            } ?: return@forEach
            add(BattleHeroPathTrigger(index, side, node, afterAction = true))
        }
    }
}

private fun battleHeroPathAnnouncementBeat(
    turn: BattlePlaybackBeat,
    trigger: BattleHeroPathTrigger,
    battle: ProjectionBattleResult,
    languageTag: String,
): BattlePlaybackBeat {
    val owner = if (trigger.ownerSide == BattleSide.USER) battle.user else battle.opponent
    val userEnergy = if (trigger.afterAction) turn.userEnergyAfter else turn.userEnergyBefore
    val opponentEnergy = if (trigger.afterAction) turn.opponentEnergyAfter else turn.opponentEnergyBefore
    return turn.copy(
        text = battleHeroPathAnnouncement(owner.displayName, trigger.node, languageTag, survival = trigger.afterAction),
        actorSide = trigger.ownerSide,
        userState = BattleParagraphState.DRAW,
        opponentState = BattleParagraphState.DRAW,
        userPower = 0,
        opponentPower = 0,
        userEnergyBefore = userEnergy, userEnergyAfter = userEnergy,
        opponentEnergyBefore = opponentEnergy, opponentEnergyAfter = opponentEnergy,
        arenaTintSide = null,
        effectKey = "TRAIT",
        motionDurationMillis = BATTLE_TRAIT_DISPLAY_MILLIS,
        kind = BattlePlaybackBeatKind.MID_BATTLE_TRAIT,
        traitId = trigger.node.nodeId,
    )
}

private fun battleHeroPathAnnouncement(
    ownerName: String,
    node: HeroPathNodeDefinition,
    languageTag: String,
    preparation: Boolean = false,
    survival: Boolean = false,
): String {
    val language = AppLanguage.fromLanguageTag(languageTag) ?: AppLanguage.KOREAN
    val name = heroPathShortNodeName(node).resolve(language)
    return when (language) {
        AppLanguage.ENGLISH -> when {
            preparation -> "$ownerName readied $name."
            survival -> "$ownerName survived with $name."
            else -> "$ownerName's $name activated."
        }
        AppLanguage.JAPANESE -> when {
            preparation -> "${ownerName}は${name}を備えた。"
            survival -> "${ownerName}は${name}で踏みとどまった。"
            else -> "${ownerName}の${name}が発動した。"
        }
        AppLanguage.KOREAN -> when {
            preparation -> "${battleKoreanSubject(ownerName)} ${battleKoreanObject(name)} 준비했다."
            survival -> "${battleKoreanSubject(ownerName)} ${battleKoreanDirection(name)} 버텨냈다."
            else -> "${battleKoreanPossessive(ownerName)} ${battleKoreanSubject(name)} 발동했다."
        }
    }
}

internal data class BattleMidTraitTrigger(
    val turnIndex: Int,
    val ownerSide: BattleSide,
    val trait: BattleTraitDefinition,
)

internal fun battleMidBattleTraitTriggers(
    battle: ProjectionBattleResult,
    turns: List<BattlePlaybackBeat>,
): List<BattleMidTraitTrigger> {
    if (turns.size < 2) return emptyList()
    val triggerIndexes = listOf(turns.size / 3, turns.size * 2 / 3)
        .map { it.coerceIn(1, turns.lastIndex) }
        .distinct()
        .take(BATTLE_MAX_MID_TRAIT_ACTIVATIONS)
    val alreadyUsed = mutableSetOf<String>()
    return buildList {
        triggerIndexes.forEach { turnIndex ->
            val turn = turns[turnIndex]
            val candidates = buildList {
                battle.user.activeTraitIds.takeUnless { battle.user.hasHeroPathPlayback() }.orEmpty()
                    .mapNotNull(BattleTraitCatalog.byId::get).forEach { trait ->
                    if (battleTraitMatchesTurn(trait, BattleSide.USER, turn, battle)) {
                        add(BattleSide.USER to trait)
                    }
                }
                battle.opponent.activeTraitIds.takeUnless { battle.opponent.hasHeroPathPlayback() }.orEmpty()
                    .mapNotNull(BattleTraitCatalog.byId::get).forEach { trait ->
                    if (battleTraitMatchesTurn(trait, BattleSide.OPPONENT, turn, battle)) {
                        add(BattleSide.OPPONENT to trait)
                    }
                }
            }.filterNot { (side, trait) -> "${side.name}:${trait.id}" in alreadyUsed }
            val selected = candidates.minByOrNull { (side, trait) ->
                Math.floorMod("${battle.battleId}:$turnIndex:${side.name}:${trait.id}".hashCode(), Int.MAX_VALUE)
            } ?: return@forEach
            alreadyUsed += "${selected.first.name}:${selected.second.id}"
            add(BattleMidTraitTrigger(turnIndex, selected.first, selected.second))
        }
    }
}

private fun battleTraitMatchesTurn(
    trait: BattleTraitDefinition,
    ownerSide: BattleSide,
    turn: BattlePlaybackBeat,
    battle: ProjectionBattleResult,
): Boolean {
    val ownerAttacks = turn.actorSide == ownerSide
    val ownerEnergyBefore = if (ownerSide == BattleSide.USER) turn.userEnergyBefore else turn.opponentEnergyBefore
    val ownerEnergyAfter = if (ownerSide == BattleSide.USER) turn.userEnergyAfter else turn.opponentEnergyAfter
    val ownerMaxEnergy = if (ownerSide == BattleSide.USER) battle.user.maxHp else battle.opponent.maxHp
    val targetEnergyBefore = if (ownerSide == BattleSide.USER) turn.opponentEnergyBefore else turn.userEnergyBefore
    val targetEnergyAfter = if (ownerSide == BattleSide.USER) turn.opponentEnergyAfter else turn.userEnergyAfter
    val targetMaxEnergy = if (ownerSide == BattleSide.USER) battle.opponent.maxHp else battle.user.maxHp
    val dealtDamage = targetEnergyAfter < targetEnergyBefore
    return when (trait.category) {
        BattleTraitCategory.OPENING -> false
        BattleTraitCategory.OFFENSE -> ownerAttacks && dealtDamage
        BattleTraitCategory.DEFENSE -> !ownerAttacks
        BattleTraitCategory.REVERSAL -> ownerEnergyBefore * 2 <= ownerMaxEnergy
        BattleTraitCategory.ENDURANCE -> ownerEnergyAfter > 0 && ownerEnergyBefore * 10 <= ownerMaxEnergy * 7
        BattleTraitCategory.PRECISION -> ownerAttacks && dealtDamage
        BattleTraitCategory.TACTICS -> turn.roundIndex >= 1
        BattleTraitCategory.TEMPERAMENT -> turn.roundIndex >= 1 && ownerEnergyAfter > 0
        BattleTraitCategory.MOMENTUM -> ownerAttacks && dealtDamage && turn.roundIndex >= 1
        BattleTraitCategory.FINISH -> ownerAttacks && targetEnergyAfter * 10 <= targetMaxEnergy * 3
    }
}

internal fun battleOpeningAnnouncement(
    userName: String,
    opponentName: String,
    languageTag: String,
): String = when (languageTag.lowercase().substringBefore('-')) {
    "en" -> "The battle between $userName and $opponentName began."
    "ja" -> "${userName}と${opponentName}の戦いが始まった。"
    else -> "${battleKoreanWith(userName)} ${battleKoreanPossessive(opponentName)} 전투가 시작됐다."
}

internal fun battleOpeningTraitAnnouncement(
    userName: String,
    trait: BattleTraitDefinition,
    languageTag: String,
): String = when (languageTag.lowercase().substringBefore('-')) {
    "en" -> {
        val label = when (trait.category) {
            BattleTraitCategory.OPENING -> "opening instinct"
            BattleTraitCategory.OFFENSE -> "offensive instinct"
            BattleTraitCategory.DEFENSE -> "defensive discipline"
            BattleTraitCategory.REVERSAL -> "comeback instinct"
            BattleTraitCategory.ENDURANCE -> "endurance"
            BattleTraitCategory.PRECISION -> "precision"
            BattleTraitCategory.TACTICS -> "tactical sense"
            BattleTraitCategory.TEMPERAMENT -> "composure"
            BattleTraitCategory.MOMENTUM -> "momentum"
            BattleTraitCategory.FINISH -> "finishing instinct"
        }
        "$userName entered with $label."
    }
    "ja" -> {
        val label = when (trait.category) {
            BattleTraitCategory.OPENING -> "先手の勘"
            BattleTraitCategory.OFFENSE -> "攻勢本能"
            BattleTraitCategory.DEFENSE -> "守備の規律"
            BattleTraitCategory.REVERSAL -> "逆転の勘"
            BattleTraitCategory.ENDURANCE -> "持久力"
            BattleTraitCategory.PRECISION -> "精密さ"
            BattleTraitCategory.TACTICS -> "戦術眼"
            BattleTraitCategory.TEMPERAMENT -> "平常心"
            BattleTraitCategory.MOMENTUM -> "勢い"
            BattleTraitCategory.FINISH -> "決着の勘"
        }
        "${userName}は${label}を携えて臨んだ。"
    }
    else -> "${battleKoreanPossessive(userName)} 특성 ${battleKoreanSubject(trait.nameKo)} 드러났다."
}

internal fun battleTraitActivationAnnouncement(
    ownerName: String,
    trait: BattleTraitDefinition,
    languageTag: String,
): String = when (languageTag.lowercase().substringBefore('-')) {
    "en" -> "$ownerName's ${battleTraitCategoryLabel(trait.category, languageTag)} activated."
    "ja" -> "${ownerName}の${battleTraitCategoryLabel(trait.category, languageTag)}が発動した。"
    else -> "${battleKoreanPossessive(ownerName)} 특성 ${battleKoreanSubject(trait.nameKo)} 발동했다."
}

private fun battleTraitCategoryLabel(
    category: BattleTraitCategory,
    languageTag: String,
): String = when (languageTag.lowercase().substringBefore('-')) {
    "ja" -> when (category) {
        BattleTraitCategory.OPENING -> "先手の勘"
        BattleTraitCategory.OFFENSE -> "攻勢本能"
        BattleTraitCategory.DEFENSE -> "守備の規律"
        BattleTraitCategory.REVERSAL -> "逆転の勘"
        BattleTraitCategory.ENDURANCE -> "持久力"
        BattleTraitCategory.PRECISION -> "精密さ"
        BattleTraitCategory.TACTICS -> "戦術眼"
        BattleTraitCategory.TEMPERAMENT -> "平常心"
        BattleTraitCategory.MOMENTUM -> "勢い"
        BattleTraitCategory.FINISH -> "決着の勘"
    }
    else -> when (category) {
        BattleTraitCategory.OPENING -> "opening instinct"
        BattleTraitCategory.OFFENSE -> "offensive instinct"
        BattleTraitCategory.DEFENSE -> "defensive discipline"
        BattleTraitCategory.REVERSAL -> "comeback instinct"
        BattleTraitCategory.ENDURANCE -> "endurance"
        BattleTraitCategory.PRECISION -> "precision"
        BattleTraitCategory.TACTICS -> "tactical sense"
        BattleTraitCategory.TEMPERAMENT -> "composure"
        BattleTraitCategory.MOMENTUM -> "momentum"
        BattleTraitCategory.FINISH -> "finishing instinct"
    }
}

internal fun battleOpeningLabel(languageTag: String): String =
    when (languageTag.lowercase().substringBefore('-')) {
        "en" -> "Duel begins"
        "ja" -> "決闘開始"
        else -> "결투 개시"
    }

internal fun battleTraitActivationLabel(languageTag: String): String =
    when (languageTag.lowercase().substringBefore('-')) {
        "en" -> "Trait activated"
        "ja" -> "特性発動"
        else -> "특성 발동"
    }

internal fun battleArenaTintAlpha(
    state: BattleParagraphState,
    progress: Float,
): Float = battleArenaTintAlpha(
    tintSide = when (state) {
        BattleParagraphState.ATTACK -> BattleSide.USER
        BattleParagraphState.HIT -> BattleSide.OPPONENT
        BattleParagraphState.DRAW -> null
    },
    progress = progress,
)

internal fun battleArenaTintAlpha(
    tintSide: BattleSide?,
    progress: Float,
): Float = if (tintSide == null) {
    0f
} else {
    (1f - progress.coerceIn(0f, 1f)) * 0.38f
}

internal fun battleNarrativeAnchorCount(roundCount: Int): Int =
    roundCount.coerceIn(BATTLE_NARRATIVE_MIN_ANCHORS, BATTLE_NARRATIVE_MAX_ANCHORS)

internal sealed interface BattleOverlayState {
    val result: BattlePreviewResult

    data class Matching(
        override val result: BattlePreviewResult,
        val requestId: String,
    ) : BattleOverlayState

    data class MatchReady(
        override val result: BattlePreviewResult,
        val requestId: String,
        val expiresAtMillis: Long,
        val narrative: BattleQaNarrative,
    ) : BattleOverlayState

    data class Playing(
        override val result: BattlePreviewResult,
        val narrative: BattleQaNarrative,
        val playbackPlan: BattlePlaybackPlan,
        val playbackIndex: Int,
    ) : BattleOverlayState

    data class Result(
        override val result: BattlePreviewResult,
        val narrative: BattleQaNarrative,
        val playbackPlan: BattlePlaybackPlan,
    ) : BattleOverlayState
}

/** Kept above the app's temporary foreground-loading boundary, scoped to one character visit. */
internal class ArenaPanelRuntime {
    var overlayState by mutableStateOf<BattleOverlayState?>(null)
    var skillTreeVisible by mutableStateOf(false)
    val playbackPosition = mutableIntStateOf(0)
    val playbackElapsed = androidx.compose.runtime.mutableLongStateOf(0L)
}

internal sealed interface BattlePreparationFailure {
    data object General : BattlePreparationFailure
    data class ServerOpponentUnavailable(
        val reason: com.nullplaying.engine.arena.ArenaServerMatchUnavailableReason,
    ) : BattlePreparationFailure
}

private fun BattleOverlayState.flowStep(): BattleFlowStep = when (this) {
    is BattleOverlayState.Matching -> BattleFlowStep.MATCHING
    is BattleOverlayState.MatchReady -> BattleFlowStep.MATCH_READY
    is BattleOverlayState.Playing -> BattleFlowStep.PLAYING
    is BattleOverlayState.Result -> BattleFlowStep.RESULT
}

private sealed interface BattleQaNarrativeUiState {
    data object Idle : BattleQaNarrativeUiState
    data class Loading(val requestId: String) : BattleQaNarrativeUiState
    data class Ready(val narrative: BattleQaNarrative) : BattleQaNarrativeUiState
    data class Failed(val requestId: String, val message: String) : BattleQaNarrativeUiState
}

internal data class BattlePreviewResult(
    val outcome: BattleOutcome,
    val userName: String,
    val userClass: BattleHeroClass,
    val userLevel: Long,
    val userPower: Long,
    val opponentName: String,
    val opponentClass: BattleHeroClass,
    val opponentLevel: Long,
    val opponentPower: Long,
    val opponentScore: Int,
    val pointDelta: Int,
    val scoreBefore: Int,
    val standingAfter: BattleSeasonStanding,
    val entriesAfter: Int,
    val playerStance: BattleStance,
    val opponentStance: BattleStance,
    val decisiveMoment: String,
    val match: BattleQaMatchFactory.Match,
    val battle: ProjectionBattleResult,
    val supportBattle: ArenaLiveBattle? = null,
)

@Serializable
internal data class BattlePreviewHistory(
    val battleId: String,
    val userName: String,
    val opponentName: String,
    val opponentClass: String,
    val opponentLevel: Long,
    val resultLabel: String,
    val pointDelta: Int,
    val summary: String,
    val narrativeLines: List<String>,
    val skillNames: List<String>,
    val equipment: List<BattlePreviewHistoryEquipment>,
    val traitNames: List<String>,
    val completedAtMillis: Long,
    val narrativeSource: String = "local_template",
    val narrativeModelValid: Boolean = false,
    val narrativeTemplateIds: List<String> = emptyList(),
    val arenaSnapshot: ArenaSavedBattleContract? = null,
    /** Stable server projection ID for daily-roster rotation and local audit. */
    val opponentProjectionId: String = "",
    /** Compatibility-safe audit label: PUBLIC_ROSTER, LOCAL_RESERVE, or SYNTHETIC. */
    val opponentSource: String = "SYNTHETIC",
)

@Serializable
internal data class BattlePreviewHistoryEquipment(
    val name: String,
    val rarity: String,
)

internal data class BattleHistoryLocalizedContent(
    val summary: String,
    val narrativeLines: List<String>,
    val skillNames: List<String>,
    val equipment: List<BattlePreviewHistoryEquipment>,
    val traitNames: List<String>,
)

internal data class BattlePreviewTrait(
    val id: String,
    val name: String,
    val description: String,
    val evidence: String,
)

internal data class BattlePreviewEntryState(
    val entriesRemaining: Int = BATTLE_DAILY_ENTRIES,
    val resultVisible: Boolean = false,
    val lastBattleStance: BattleStance = BattleStance.BALANCED,
)

internal fun battlePlacementLabel(
    completed: Int,
    required: Int = BATTLE_PLACEMENT_REQUIRED,
): String = "${completed.coerceIn(0, required)}/$required"

internal fun arenaRankingLoadingLabel(language: AppLanguage): String = when (language) {
    AppLanguage.KOREAN -> "결투장 랭킹을 불러오는 중입니다."
    AppLanguage.ENGLISH -> "Loading Arena rankings."
    AppLanguage.JAPANESE -> "闘技場ランキングを読み込み中です。"
}

internal fun arenaRankingLoadFailureLabel(language: AppLanguage): String = when (language) {
    AppLanguage.KOREAN -> "결투장 랭킹을 불러오지 못했습니다."
    AppLanguage.ENGLISH -> "Couldn't load Arena rankings."
    AppLanguage.JAPANESE -> "闘技場ランキングを読み込めませんでした。"
}

internal fun arenaRankingErrorDetailLabel(language: AppLanguage): String = when (language) {
    AppLanguage.KOREAN -> "네트워크 연결을 확인한 뒤 다시 시도해 주세요."
    AppLanguage.ENGLISH -> "Check your connection and try again."
    AppLanguage.JAPANESE -> "通信状態を確認して、もう一度お試しください。"
}

internal fun arenaRankingRetryLabel(language: AppLanguage): String = when (language) {
    AppLanguage.KOREAN -> "다시 시도"
    AppLanguage.ENGLISH -> "Retry"
    AppLanguage.JAPANESE -> "再試行"
}

internal fun arenaRankingEmptyTitle(language: AppLanguage): String = when (language) {
    AppLanguage.KOREAN -> "랭킹 집계 중"
    AppLanguage.ENGLISH -> "Compiling rankings"
    AppLanguage.JAPANESE -> "ランキング集計中"
}

internal fun arenaRankingPlacementTitle(language: AppLanguage): String = when (language) {
    AppLanguage.KOREAN -> "배치 진행 중"
    AppLanguage.ENGLISH -> "Placement in progress"
    AppLanguage.JAPANESE -> "順位決定戦進行中"
}

internal fun arenaBattleResultLabel(outcome: BattleOutcome, language: AppLanguage): String = when (outcome) {
    BattleOutcome.USER_WIN -> when (language) {
        AppLanguage.KOREAN -> "승리"
        AppLanguage.ENGLISH -> "Victory"
        AppLanguage.JAPANESE -> "勝利"
    }
    BattleOutcome.USER_LOSS -> when (language) {
        AppLanguage.KOREAN -> "패배"
        AppLanguage.ENGLISH -> "Defeat"
        AppLanguage.JAPANESE -> "敗北"
    }
    BattleOutcome.DRAW -> when (language) {
        AppLanguage.KOREAN -> "무승부"
        AppLanguage.ENGLISH -> "Draw"
        AppLanguage.JAPANESE -> "引き分け"
    }
}

internal fun arenaBattlePointTitle(language: AppLanguage): String = when (language) {
    AppLanguage.KOREAN -> "결투장 포인트"
    AppLanguage.ENGLISH -> "Arena points"
    AppLanguage.JAPANESE -> "闘技場ポイント"
}

internal fun arenaBattleSeasonScoreTitle(language: AppLanguage): String = when (language) {
    AppLanguage.KOREAN -> "시즌 점수"
    AppLanguage.ENGLISH -> "Season score"
    AppLanguage.JAPANESE -> "シーズンスコア"
}

internal fun arenaBattleSeasonRecordTitle(language: AppLanguage): String = when (language) {
    AppLanguage.KOREAN -> "시즌 전적"
    AppLanguage.ENGLISH -> "Season record"
    AppLanguage.JAPANESE -> "シーズン戦績"
}

internal fun arenaSeasonPlacementTitle(language: AppLanguage): String = when (language) {
    AppLanguage.KOREAN -> "시즌 배치"
    AppLanguage.ENGLISH -> "Season placement"
    AppLanguage.JAPANESE -> "シーズン順位決定戦"
}

internal fun arenaRecentHistoryTitle(language: AppLanguage): String = when (language) {
    AppLanguage.KOREAN -> "최근 전적"
    AppLanguage.ENGLISH -> "Recent results"
    AppLanguage.JAPANESE -> "最近の戦績"
}

internal fun arenaRecentHistoryEmptyLabel(language: AppLanguage): String = when (language) {
    AppLanguage.KOREAN -> "아직 전적이 없습니다"
    AppLanguage.ENGLISH -> "No battle record yet"
    AppLanguage.JAPANESE -> "まだ対戦記録がありません"
}

internal fun arenaFindingOpponentLabel(language: AppLanguage): String = when (language) {
    AppLanguage.KOREAN -> "상대 찾는 중…"
    AppLanguage.ENGLISH -> "Finding opponent…"
    AppLanguage.JAPANESE -> "対戦相手を検索中…"
}

internal fun arenaCloseLabel(language: AppLanguage): String = when (language) {
    AppLanguage.KOREAN -> "닫기"
    AppLanguage.ENGLISH -> "Close"
    AppLanguage.JAPANESE -> "閉じる"
}

internal fun arenaConfirmLabel(language: AppLanguage): String = when (language) {
    AppLanguage.KOREAN -> "확인"
    AppLanguage.ENGLISH -> "OK"
    AppLanguage.JAPANESE -> "確認"
}

internal fun arenaNarrativePreparingLabel(language: AppLanguage): String = when (language) {
    AppLanguage.KOREAN -> "첫 문장을 준비하고 있습니다."
    AppLanguage.ENGLISH -> "Preparing the first battle line."
    AppLanguage.JAPANESE -> "最初の戦闘メッセージを準備中です。"
}

internal fun arenaRecentHistoryCountLabel(count: Int, language: AppLanguage): String {
    val safeCount = count.coerceIn(0, BATTLE_RECENT_HISTORY_LIMIT)
    return when (language) {
        AppLanguage.KOREAN -> "${safeCount}회"
        AppLanguage.ENGLISH -> if (safeCount == 1) "1 match" else "$safeCount matches"
        AppLanguage.JAPANESE -> "${safeCount}戦"
    }
}

internal fun battleNarrativeSourceLabel(
    source: String,
    modelValid: Boolean,
    language: AppLanguage = AppLanguage.KOREAN,
): String = when {
    source.startsWith("arena_") -> when (language) {
        AppLanguage.KOREAN -> "결투장 전투 기록"
        AppLanguage.ENGLISH -> "Arena battle record"
        AppLanguage.JAPANESE -> "闘技場バトル記録"
    }
    source == "qwen" && modelValid -> when (language) {
        AppLanguage.KOREAN -> "이전 AI 서사"
        AppLanguage.ENGLISH -> "Previous AI narrative"
        AppLanguage.JAPANESE -> "以前のAI戦記"
    }
    else -> when (language) {
        AppLanguage.KOREAN -> "로컬 템플릿 서사"
        AppLanguage.ENGLISH -> "Local template narrative"
        AppLanguage.JAPANESE -> "ローカルテンプレート戦記"
    }
}

internal fun arenaHistoryOutcome(storedLabel: String): BattleOutcome? =
    BattleOutcome.entries.firstOrNull { candidate ->
        AppLanguage.entries.any { storedLanguage ->
            arenaBattleResultLabel(candidate, storedLanguage) == storedLabel
        }
    }

internal fun arenaHistoryOutcomeForPresentation(
    storedLabel: String,
    pointDelta: Int,
): BattleOutcome = arenaHistoryOutcome(storedLabel) ?: when {
    pointDelta > 0 -> BattleOutcome.USER_WIN
    pointDelta < 0 -> BattleOutcome.USER_LOSS
    else -> BattleOutcome.DRAW
}

internal fun arenaHistoryResultLabel(storedLabel: String, language: AppLanguage): String {
    val outcome = arenaHistoryOutcome(storedLabel)
    return outcome?.let { arenaBattleResultLabel(it, language) } ?: storedLabel
}

internal fun arenaHistoryOpponentSourceLabel(source: String, language: AppLanguage): String = when (source) {
    "PUBLIC_ROSTER" -> when (language) {
        AppLanguage.KOREAN -> "실제 모험가"
        AppLanguage.ENGLISH -> "Player"
        AppLanguage.JAPANESE -> "プレイヤー"
    }
    "LOCAL_RESERVE" -> when (language) {
        AppLanguage.KOREAN -> "로컬 도전자"
        AppLanguage.ENGLISH -> "Local challenger"
        AppLanguage.JAPANESE -> "ローカル挑戦者"
    }
    else -> ""
}

internal fun arenaHistoryOpponentMetaLabel(
    storedClassLabel: String,
    level: Long,
    source: String,
    language: AppLanguage,
): String {
    val heroClass = BattleHeroClass.entries.firstOrNull { candidate ->
        AppLanguage.entries.any { storedLanguage ->
            battleHeroClassLabel(candidate, storedLanguage) == storedClassLabel
        }
    }
    val classLabel = heroClass?.let { battleHeroClassLabel(it, language) } ?: storedClassLabel
    val sourceLabel = arenaHistoryOpponentSourceLabel(source, language)
    return buildString {
        append("$classLabel · Lv.$level")
        if (sourceLabel.isNotBlank()) append(" · $sourceLabel")
    }
}

internal fun arenaHistoryDetailTitle(
    opponentName: String,
    storedResultLabel: String,
    language: AppLanguage,
): String = "$opponentName · ${arenaHistoryResultLabel(storedResultLabel, language)}"

internal fun arenaHistoryAccessibilityLabel(opponentName: String, language: AppLanguage): String = when (language) {
    AppLanguage.KOREAN -> "지난 $opponentName 결투장 기록 보기"
    AppLanguage.ENGLISH -> "View the previous Arena record against $opponentName"
    AppLanguage.JAPANESE -> "${opponentName}との過去の闘技場記録を表示"
}

internal fun battleResonanceIndex(rawIndex: Int): Int =
    rawIndex.coerceIn(BATTLE_RESONANCE_MIN_INDEX, BATTLE_RESONANCE_MAX_INDEX)

internal fun battleResonanceDamageBonusTenths(rawIndex: Int): Int =
    (battleResonanceIndex(rawIndex) - BATTLE_RESONANCE_MIN_INDEX) *
        BATTLE_RESONANCE_DAMAGE_TENTHS_PER_POINT

internal fun battleResonanceDamageBonusLabel(rawIndex: Int): String {
    val tenths = battleResonanceDamageBonusTenths(rawIndex)
    val percent = if (tenths % 10 == 0) {
        (tenths / 10).toString()
    } else {
        "${tenths / 10}.${tenths % 10}"
    }
    return "+$percent%"
}

internal fun battleResonanceRuleLabel(): String =
    "지수 1점당 최종 피해 +0.9% · 최대 +7.2%"

internal fun battleRankingEntryDetail(
    score: Int,
    placementCompleted: Int,
    language: AppLanguage = AppLanguage.KOREAN,
): String =
    if (battleCompetitiveDataVisible(placementCompleted)) {
        battleArenaScoreLabel(score, language)
    } else {
        when (language) {
            AppLanguage.KOREAN -> "배치 ${battlePlacementLabel(placementCompleted)}"
            AppLanguage.ENGLISH -> "Placement ${battlePlacementLabel(placementCompleted)}"
            AppLanguage.JAPANESE -> "順位決定 ${battlePlacementLabel(placementCompleted)}"
        }
    }

internal fun arenaRankingEntryAccessibilityLabel(
    detail: String,
    language: AppLanguage,
): String = when (language) {
    AppLanguage.KOREAN -> "결투장 랭킹, $detail, 보기 버튼"
    AppLanguage.ENGLISH -> "Arena rankings, $detail, view button"
    AppLanguage.JAPANESE -> "闘技場ランキング、$detail、表示ボタン"
}

internal fun recentBattleHistory(history: List<BattlePreviewHistory>): List<BattlePreviewHistory> =
    history.take(BATTLE_RECENT_HISTORY_LIMIT)

internal fun battleTraitRemovalLabel(remainingMinutes: Int): String = when {
    remainingMinutes <= 0 -> "제거 완료"
    remainingMinutes >= 60 && remainingMinutes % 60 == 0 -> "제거까지 ${remainingMinutes / 60}시간"
    else -> "제거까지 ${remainingMinutes}분"
}

internal fun battleScoreRuleLabel(
    startScore: Int = BATTLE_START_SCORE,
    kFactor: Int = BATTLE_K_FACTOR,
): String = "시작 ${startScore}점 · K $kFactor"

internal fun battleCompetitiveDataVisible(
    placementCompleted: Int,
    placementRequired: Int = BATTLE_PLACEMENT_REQUIRED,
): Boolean = placementCompleted >= placementRequired

internal fun battlePlacementProgressVisible(
    placementCompleted: Int,
    placementRequired: Int = BATTLE_PLACEMENT_REQUIRED,
): Boolean = !battleCompetitiveDataVisible(placementCompleted, placementRequired)

internal fun battleScoreDisplay(
    score: Int,
    placementCompleted: Int,
    language: AppLanguage = AppLanguage.KOREAN,
): String = if (battleCompetitiveDataVisible(placementCompleted)) {
    score.coerceAtLeast(0).toString()
} else {
    when (language) {
        AppLanguage.KOREAN -> "배치 중"
        AppLanguage.ENGLISH -> "Placement"
        AppLanguage.JAPANESE -> "順位決定中"
    }
}

internal fun battlePointDeltaDisplay(
    pointDelta: Int,
    placementCompleted: Int,
    language: AppLanguage = AppLanguage.KOREAN,
): String = when {
    !battleCompetitiveDataVisible(placementCompleted) -> when (language) {
        AppLanguage.KOREAN -> "비공개"
        AppLanguage.ENGLISH -> "Hidden"
        AppLanguage.JAPANESE -> "非公開"
    }
    pointDelta > 0 -> "+$pointDelta"
    else -> pointDelta.toString()
}

internal fun battleSettledPointDeltaDisplay(pointDelta: Int): String =
    if (pointDelta > 0) "+$pointDelta" else pointDelta.toString()

internal fun canStartPreviewBattle(state: BattlePreviewEntryState): Boolean =
    state.entriesRemaining > 0 && !state.resultVisible

internal fun startPreviewBattle(
    state: BattlePreviewEntryState,
    selectedStance: BattleStance,
): BattlePreviewEntryState =
    if (canStartPreviewBattle(state)) {
        state.copy(
            resultVisible = true,
            lastBattleStance = selectedStance,
        )
    } else {
        state
    }

internal fun acknowledgePreviewBattleResult(
    state: BattlePreviewEntryState,
): BattlePreviewEntryState = if (state.resultVisible) {
    state.copy(
        entriesRemaining = (state.entriesRemaining - 1).coerceAtLeast(0),
        resultVisible = false,
    )
} else {
    state
}

internal const val BATTLE_RANKING_SORT_NOTICE =
    "순위는 시즌 점수, 전적, 해당 점수 도달 시각 순으로 정렬됩니다."

internal fun battlePreviewMenuEnabled(
    isDebugBuild: Boolean,
    serverMatchingEnabled: Boolean = BuildConfig.ARENA_SERVER_MATCHING_ENABLED,
): Boolean = isDebugBuild || serverMatchingEnabled

internal fun battleUserOnlyScoreNotice(): String =
    "내가 출전한 공식전에서만 내 점수가 변합니다. 매칭된 참가자의 점수는 변하지 않습니다."

internal fun battleGuidanceLabel(
    userName: String,
    userStance: BattleStance,
    opponentName: String,
    opponentStance: BattleStance,
    language: AppLanguage = AppLanguage.KOREAN,
): String = when (language) {
    AppLanguage.KOREAN -> "$userName 지침 ${userStance.label} · $opponentName 지침 ${opponentStance.label}"
    AppLanguage.ENGLISH ->
        "$userName: ${battleStanceLabel(userStance, language)} · " +
            "$opponentName: ${battleStanceLabel(opponentStance, language)}"
    AppLanguage.JAPANESE ->
        "${userName}：${battleStanceLabel(userStance, language)}・" +
            "${opponentName}：${battleStanceLabel(opponentStance, language)}"
}

internal fun battleStanceLabel(stance: BattleStance, language: AppLanguage): String = when (stance) {
    BattleStance.ASSAULT -> when (language) {
        AppLanguage.KOREAN -> "맹공"
        AppLanguage.ENGLISH -> "Assault"
        AppLanguage.JAPANESE -> "猛攻"
    }
    BattleStance.BALANCED -> when (language) {
        AppLanguage.KOREAN -> "균형"
        AppLanguage.ENGLISH -> "Balanced"
        AppLanguage.JAPANESE -> "均衡"
    }
    BattleStance.GUARD -> when (language) {
        AppLanguage.KOREAN -> "수호"
        AppLanguage.ENGLISH -> "Guard"
        AppLanguage.JAPANESE -> "守勢"
    }
}

internal fun battleOfficialResultTitle(outcome: BattleOutcome, language: AppLanguage): String = when (language) {
    AppLanguage.KOREAN -> "공식전 · ${arenaBattleResultLabel(outcome, language)}"
    AppLanguage.ENGLISH -> "Official match · ${arenaBattleResultLabel(outcome, language)}"
    AppLanguage.JAPANESE -> "公式戦・${arenaBattleResultLabel(outcome, language)}"
}

internal fun battleResultOpponentMetaLabel(
    opponentName: String,
    opponentScore: Int,
    competitiveDataVisible: Boolean,
    language: AppLanguage,
): String = when (language) {
    AppLanguage.KOREAN -> if (competitiveDataVisible) {
        "$opponentName · ${opponentScore}점 · 시즌 참가자"
    } else {
        "$opponentName · 배치전 참가자"
    }
    AppLanguage.ENGLISH -> if (competitiveDataVisible) {
        "$opponentName · $opponentScore pts · Season participant"
    } else {
        "$opponentName · Placement participant"
    }
    AppLanguage.JAPANESE -> if (competitiveDataVisible) {
        "${opponentName}・${opponentScore}点・シーズン参加者"
    } else {
        "${opponentName}・順位決定戦参加者"
    }
}

internal fun battleResultFighterMetaLabel(
    name: String,
    heroClass: BattleHeroClass,
    level: Long,
    power: Long,
    opponent: Boolean,
    language: AppLanguage,
): String {
    val prefix = if (opponent) "VS " else ""
    val classLabel = battleHeroClassLabel(heroClass, language)
    return when (language) {
        AppLanguage.KOREAN -> "$prefix$name · $classLabel · Lv.$level · 전투력 $power"
        AppLanguage.ENGLISH -> "$prefix$name · $classLabel · Lv.$level · Power $power"
        AppLanguage.JAPANESE -> "${prefix}${name}・${classLabel}・Lv.${level}・戦闘力 ${power}"
    }
}

internal fun battleDecisiveMomentLabel(
    outcome: BattleOutcome,
    rounds: Int,
    userName: String,
    userHp: Int,
    opponentName: String,
    opponentHp: Int,
    language: AppLanguage,
): String {
    val safeRounds = rounds.coerceAtLeast(1)
    return when (outcome) {
        BattleOutcome.DRAW -> when (language) {
            AppLanguage.KOREAN ->
                "${safeRounds}합 끝에 ${battleKoreanWith(userName)} ${battleKoreanTopic(opponentName)} " +
                    "승부를 가리지 못했습니다. 남은 체력 $userName $userHp · $opponentName $opponentHp"
            AppLanguage.ENGLISH ->
                "After $safeRounds rounds, $userName and $opponentName remained tied. " +
                    "Remaining HP: $userName $userHp · $opponentName $opponentHp"
            AppLanguage.JAPANESE ->
                "${safeRounds}合の末、${userName}と${opponentName}の勝負は引き分けとなった。" +
                    "残りHP：${userName} ${userHp}・${opponentName} ${opponentHp}"
        }
        BattleOutcome.USER_WIN, BattleOutcome.USER_LOSS -> {
            val winner = if (outcome == BattleOutcome.USER_WIN) userName else opponentName
            when (language) {
                AppLanguage.KOREAN ->
                    "${safeRounds}합 끝에 ${battleKoreanSubject(winner)} 승리를 확정했습니다. " +
                        "남은 체력 $userName $userHp · $opponentName $opponentHp"
                AppLanguage.ENGLISH ->
                    "$winner secured victory after $safeRounds rounds. " +
                        "Remaining HP: $userName $userHp · $opponentName $opponentHp"
                AppLanguage.JAPANESE ->
                    "${safeRounds}合の末、${winner}が勝利を決めた。" +
                        "残りHP：${userName} ${userHp}・${opponentName} ${opponentHp}"
            }
        }
    }
}

internal fun battlePreviewResult(
    match: BattleQaMatchFactory.Match,
    battle: ProjectionBattleResult,
    standing: BattleStandingUpdate,
    ticketsAfter: BattleTicketState,
    playerStance: BattleStance,
): BattlePreviewResult {
    val lastRound = battle.rounds.last()
    val winnerName = when (battle.outcome) {
        BattleOutcome.USER_WIN -> match.request.user.displayName
        BattleOutcome.USER_LOSS -> match.request.opponent.displayName
        BattleOutcome.DRAW -> null
    }
    val decisiveMoment = if (winnerName == null) {
        "${battle.rounds.size}합 끝에 ${battleKoreanWith(match.request.user.displayName)} " +
            "${battleKoreanTopic(match.request.opponent.displayName)} 승부를 가리지 못했습니다. " +
            "남은 체력 ${match.request.user.displayName} ${lastRound.userHpAfter} · " +
            "${match.request.opponent.displayName} ${lastRound.opponentHpAfter}"
    } else {
        "${battle.rounds.size}합 끝에 ${battleKoreanSubject(winnerName)} 승리를 확정했습니다. " +
            "남은 체력 ${match.request.user.displayName} ${lastRound.userHpAfter} · " +
            "${match.request.opponent.displayName} ${lastRound.opponentHpAfter}"
    }
    return BattlePreviewResult(
        outcome = battle.outcome,
        userName = match.request.user.displayName,
        userClass = match.request.user.heroClass,
        userLevel = match.request.user.level,
        userPower = match.userEffectiveCombatPower,
        opponentName = match.request.opponent.displayName,
        opponentClass = match.request.opponent.heroClass,
        opponentLevel = match.request.opponent.level,
        opponentPower = match.opponentEffectiveCombatPower,
        opponentScore = match.request.opponentReferenceScore,
        pointDelta = standing.scoreDelta,
        scoreBefore = standing.before.score,
        standingAfter = standing.after,
        entriesAfter = ticketsAfter.remaining,
        playerStance = playerStance,
        opponentStance = match.request.opponent.guidance.toBattleStance(),
        decisiveMoment = decisiveMoment,
        match = match,
        battle = battle,
    )
}

internal fun BattlePreviewResult.toHistory(
    narrative: BattleQaNarrative,
    completedAtMillis: Long,
): BattlePreviewHistory {
    val narrativeLanguage = com.nullplaying.localization.AppLanguage
        .fromLanguageTag(narrative.languageTag)
        ?: com.nullplaying.localization.AppLanguage.KOREAN
    val finalRound = battle.rounds.lastOrNull()
    val localizedSummary = finalRound?.let { round ->
        battleDecisiveMomentLabel(
            outcome = outcome,
            rounds = supportBattle?.simulation?.turns ?: battle.rounds.size,
            userName = userName,
            userHp = round.userHpAfter,
            opponentName = opponentName,
            opponentHp = round.opponentHpAfter,
            language = narrativeLanguage,
        )
    } ?: decisiveMoment
    return BattlePreviewHistory(
        battleId = battle.battleId,
        userName = userName,
        opponentName = opponentName,
        opponentClass = battleHeroClassLabel(opponentClass),
        opponentLevel = opponentLevel,
        resultLabel = when (outcome) {
            BattleOutcome.USER_WIN -> "승리"
            BattleOutcome.USER_LOSS -> "패배"
            BattleOutcome.DRAW -> "무승부"
        },
        pointDelta = pointDelta,
        summary = localizedSummary,
        narrativeLines = battlePlaybackLines(narrative),
        skillNames = (battle.user.skills + battle.opponent.skills)
            .map { localized(it.displayName, narrativeLanguage) }
            .plus(supportBattle?.let { live ->
                (live.user.supportIds + live.opponent.supportIds).mapNotNull(ArenaSupportCatalog::find)
                    .map { it.name(narrativeLanguage.languageTag) }
            }.orEmpty()).distinct(),
        equipment = (battle.user.equipment + battle.opponent.equipment)
            .distinctBy(BattleEquipmentSnapshot::itemId)
            .map {
                BattlePreviewHistoryEquipment(
                    // Keep the existing field in its canonical catalog form. Presentation applies
                    // the current language when history is opened, so no save-schema expansion is needed.
                    name = it.displayName,
                    rarity = it.rarity,
                )
            },
        traitNames = supportBattle?.let { live ->
            (live.user.traits + live.opponent.traits).mapNotNull { ArenaProgressionCatalog.find(it.id) }
                .distinctBy { it.id }.map { arenaProgressionTraitName(it, narrativeLanguage) }
        } ?: (battle.user.activeTraitIds + battle.opponent.activeTraitIds)
            .distinct()
            .mapNotNull(BattleTraitCatalog.byId::get)
            .map { localized(it.nameKo, narrativeLanguage) },
        completedAtMillis = completedAtMillis,
        narrativeSource = narrative.source,
        narrativeModelValid = narrative.modelValid,
        narrativeTemplateIds = narrative.scenes.flatMap { it.templateIds }.distinct(),
        arenaSnapshot = supportBattle?.savedContract(),
        opponentProjectionId = match.request.opponent.projectionId,
        opponentSource = when (narrative.source) {
            "arena_public_roster" -> "PUBLIC_ROSTER"
            "arena_local_reserve" -> "LOCAL_RESERVE"
            else -> "SYNTHETIC"
        },
    )
}

private data class LocalizedArenaHistoryReplay(
    val summary: String,
    val narrativeLines: List<String>,
    val skillNames: List<String>,
    val traitNames: List<String>,
)

/**
 * Rebuilds a saved Arena ledger in the language selected when the record is opened.
 *
 * The frozen participant/rule snapshot is the source of truth, so changing the app language does
 * not mutate the persisted record. Legacy and corrupt snapshots retain their saved text; Korean
 * system copy is translated opportunistically while player-authored names remain protected.
 */
internal fun battleHistoryLocalizedContent(
    history: BattlePreviewHistory,
    language: AppLanguage,
): BattleHistoryLocalizedContent {
    val replay = history.arenaSnapshot?.let { snapshot ->
        runCatching {
            require(snapshot.rulesVersion in setOf(ARENA_SUPPORT_RULES_VERSION,
                com.nullplaying.engine.arena.ARENA_IDENTITY_V1_RULES_VERSION,
                com.nullplaying.engine.arena.ARENA_IDENTITY_V2_RULES_VERSION,
                com.nullplaying.engine.arena.ARENA_IDENTITY_V3_RULES_VERSION,
                com.nullplaying.engine.arena.ARENA_IDENTITY_V4_RULES_VERSION,
                com.nullplaying.engine.arena.ARENA_IDENTITY_RULES_VERSION)) {
                "Unsupported saved Arena rules: ${snapshot.rulesVersion}"
            }
            val simulation = ArenaSupportTurnEngine.simulate(
                left = snapshot.user,
                right = snapshot.opponent,
                seed = snapshot.seed,
                rules = snapshot.rules,
                ignoreHeroLevelGate = snapshot.ignoreHeroLevelGate,
            )
            require(simulation.rulesVersion == snapshot.rulesVersion) {
                "Saved Arena rules differ from replay rules"
            }
            val userId = snapshot.user.fighter.id
            val opponentId = snapshot.opponent.fighter.id
            val names = linkedMapOf(
                userId to history.userName,
                opponentId to history.opponentName,
            )
            val timeline = buildArenaLiveTimeline(simulation, names, language.languageTag)
            val outcome = when (simulation.winnerId) {
                userId -> BattleOutcome.USER_WIN
                opponentId -> BattleOutcome.USER_LOSS
                null -> BattleOutcome.DRAW
                else -> error("Saved Arena winner does not match either participant")
            }
            LocalizedArenaHistoryReplay(
                summary = battleDecisiveMomentLabel(
                    outcome = outcome,
                    rounds = simulation.turns,
                    userName = history.userName,
                    userHp = arenaHistoryDisplayHp(simulation.fighters.getValue(userId).hp),
                    opponentName = history.opponentName,
                    opponentHp = arenaHistoryDisplayHp(simulation.fighters.getValue(opponentId).hp),
                    language = language,
                ),
                narrativeLines = timeline.logs.map { it.text },
                skillNames = buildList {
                    (snapshot.user.fighter.attacks + snapshot.opponent.fighter.attacks).forEach { attack ->
                        add(localized(SkillCatalog.find(attack.id)?.name ?: attack.name, language))
                    }
                    (snapshot.user.supportIds + snapshot.opponent.supportIds).forEach { supportId ->
                        ArenaSupportCatalog.find(supportId)?.let { add(it.name(language.languageTag)) }
                    }
                }.filter(String::isNotBlank).distinct(),
                traitNames = (snapshot.user.traits + snapshot.opponent.traits)
                    .mapNotNull { ArenaProgressionCatalog.find(it.id) }
                    .distinctBy { it.id }
                    .map { arenaProgressionTraitName(it, language) },
            )
        }.getOrNull()
    }
    val protectedNames = listOf(history.userName, history.opponentName).filter(String::isNotBlank)
    fun localizeLegacy(value: String): String = GameLocalization.translatePreserving(
        text = value,
        language = language,
        protectedValues = protectedNames,
    )
    return BattleHistoryLocalizedContent(
        summary = replay?.summary ?: localizeLegacy(history.summary),
        narrativeLines = replay?.narrativeLines ?: history.narrativeLines.map(::localizeLegacy),
        skillNames = replay?.skillNames ?: history.skillNames.map { localized(it, language) },
        equipment = history.equipment.map { equipment ->
            equipment.copy(name = localizedEquipmentName(equipment.name, language))
        },
        traitNames = replay?.traitNames ?: history.traitNames.map { localized(it, language) },
    )
}

private fun arenaHistoryDisplayHp(value: Double): Int =
    if (value <= 0.0) 0 else ceil(value).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()

private fun battleKoreanHasFinalConsonant(value: String): Boolean {
    val last = value.trim().lastOrNull() ?: return false
    val code = last.code
    return if (code in 0xAC00..0xD7A3) {
        (code - 0xAC00) % 28 != 0
    } else {
        last in "013678lLmMnNrR"
    }
}

private fun battleKoreanTopic(value: String): String =
    value + if (battleKoreanHasFinalConsonant(value)) "은" else "는"

private fun battleKoreanSubject(value: String): String {
    return value + if (battleKoreanHasFinalConsonant(value)) "이" else "가"
}

private fun battleKoreanWith(value: String): String {
    return value + if (battleKoreanHasFinalConsonant(value)) "과" else "와"
}

private fun battleKoreanObject(value: String): String =
    value + if (battleKoreanHasFinalConsonant(value)) "을" else "를"

private fun battleKoreanPossessive(value: String): String = "${value}의"

private fun battleKoreanDirection(value: String): String {
    val last = value.trim().lastOrNull() ?: return value
    val finalIndex = if (last.code in 0xAC00..0xD7A3) (last.code - 0xAC00) % 28 else 0
    return value + if (finalIndex != 0 && finalIndex != 8) "으로" else "로"
}

private fun localBattleEquipmentForAction(
    equipment: List<BattleEquipmentSnapshot>,
    action: BattleRoundAction,
    variation: Int,
): BattleEquipmentSnapshot? {
    if (equipment.isEmpty()) return null
    val slots = if (action.kind.name == "GUARD") {
        listOf(
            BattleEquipmentSlot.BODY,
            BattleEquipmentSlot.HANDS,
            BattleEquipmentSlot.HEAD,
            BattleEquipmentSlot.FEET,
            BattleEquipmentSlot.ACCESSORY,
            BattleEquipmentSlot.WEAPON,
        )
    } else {
        listOf(
            BattleEquipmentSlot.WEAPON,
            BattleEquipmentSlot.HANDS,
            BattleEquipmentSlot.ACCESSORY,
            BattleEquipmentSlot.FEET,
            BattleEquipmentSlot.BODY,
            BattleEquipmentSlot.HEAD,
        )
    }
    val ordered = slots.mapNotNull { slot -> equipment.firstOrNull { it.slot == slot } }
    return ordered[Math.floorMod(variation, ordered.size)]
}

private fun localBattleEquipmentMotion(item: BattleEquipmentSnapshot?): String = when (item?.slot) {
    BattleEquipmentSlot.WEAPON -> "${battleKoreanObject(item.displayName)} 비스듬히 세워"
    BattleEquipmentSlot.HEAD -> "${item.displayName} 아래로 시선을 고정한 채"
    BattleEquipmentSlot.BODY -> "${item.displayName}에 스친 충격을 흘리며"
    BattleEquipmentSlot.HANDS -> "${battleKoreanDirection(item.displayName)} 손목의 흔들림을 눌러"
    BattleEquipmentSlot.FEET -> "${battleKoreanDirection(item.displayName)} 바닥을 힘껏 밀어"
    BattleEquipmentSlot.ACCESSORY -> "${item.displayName}의 흔들림을 가라앉히며"
    null -> "발끝에 힘을 모아"
}

internal fun localBattleActionText(
    name: String,
    action: BattleRoundAction,
    skills: List<BattleSkillSnapshot>,
    equipment: List<BattleEquipmentSnapshot>,
    variation: Int,
): String {
    val subject = battleKoreanTopic(name)
    val skillName = skills.firstOrNull { it.skillId == action.skillId }?.displayName
    val equipmentMotion = localBattleEquipmentMotion(
        localBattleEquipmentForAction(equipment, action, variation),
    )
    return when {
        !skillName.isNullOrBlank() -> when (Math.floorMod(variation, 6)) {
            0 -> "$subject $equipmentMotion ${battleKoreanObject(skillName)} 낮은 궤도로 펼쳐 발밑의 빈틈을 먼저 흔들었다"
            1 -> "$subject $equipmentMotion ${battleKoreanObject(skillName)} 마지막 순간까지 감췄다가 짧은 틈에 밀어 넣었다"
            2 -> "$subject $equipmentMotion ${battleKoreanObject(skillName)} 정면에 보인 뒤 몸을 틀어 전혀 다른 각도로 이었다"
            3 -> "$subject $equipmentMotion ${battleKoreanObject(skillName)} 맞부딪친 힘에 겹쳐 상대의 다음 동작을 늦췄다"
            4 -> "$subject $equipmentMotion ${battleKoreanObject(skillName)} 끊어 쓰며 빠르던 공방의 박자를 갑자기 바꾸었다"
            else -> "$subject $equipmentMotion ${battleKoreanObject(skillName)} 반걸음 늦게 펼쳐 먼저 움직인 쪽의 빈틈을 파고들었다"
        }
        action.kind.name == "GUARD" -> when (Math.floorMod(variation, 3)) {
            0 -> "$subject $equipmentMotion 몸을 반걸음 틀어 들어오는 충격을 옆으로 흘려냈다"
            1 -> "$subject $equipmentMotion 한 치만 물러나 공격 끝이 지나갈 길을 비워 두었다"
            else -> "$subject $equipmentMotion 급한 반격을 참으며 다음 움직임이 시작될 자리를 지켰다"
        }
        else -> when (Math.floorMod(variation, 4)) {
            0 -> "$subject $equipmentMotion 짧은 공격으로 상대의 반응부터 끌어냈다"
            1 -> "$subject $equipmentMotion 어깨를 노리는 척하다가 비어 있는 옆선으로 파고들었다"
            2 -> "$subject $equipmentMotion 닿기 직전 공격을 거두고 반대쪽 발을 묶었다"
            else -> "$subject $equipmentMotion 연속된 견제로 안전한 거리를 조금씩 지워 갔다"
        }
    }
}

private fun localBattleActionName(
    action: BattleRoundAction,
    skills: List<BattleSkillSnapshot>,
): String = skills.firstOrNull { it.skillId == action.skillId }?.displayName
    ?: when (action.kind.name) {
        "GUARD" -> "마지막 방어"
        else -> "마지막 일격"
    }

private fun localBattleOutcomeSentence(result: BattlePreviewResult): String {
    val finalRound = result.battle.rounds.last()
    val userAction = localBattleActionName(finalRound.userAction, result.battle.user.skills)
    val opponentAction = localBattleActionName(
        finalRound.opponentAction,
        result.battle.opponent.skills,
    )
    return when (result.outcome) {
        BattleOutcome.USER_WIN ->
            "${result.userName}의 ${battleKoreanSubject(userAction)} ${result.opponentName}의 마지막 방어를 무너뜨리며 " +
                "${battleKoreanSubject(result.userName)} 승리하고 ${battleKoreanSubject(result.opponentName)} 패배했다."
        BattleOutcome.USER_LOSS ->
            "${result.opponentName}의 ${battleKoreanSubject(opponentAction)} ${result.userName}의 마지막 방어를 무너뜨리며 " +
                "${battleKoreanSubject(result.opponentName)} 승리하고 ${battleKoreanSubject(result.userName)} 패배했다."
        BattleOutcome.DRAW ->
            "${result.userName}의 ${battleKoreanWith(userAction)} ${result.opponentName}의 ${battleKoreanSubject(opponentAction)} 마지막까지 맞부딪치며 " +
                "승부는 무승부로 끝났다."
    }
}

private fun localBattleNarrativeIndex(
    result: BattlePreviewResult,
    lane: Int,
    size: Int,
): Int = Math.floorMod(
    result.battle.battleId.hashCode() * 31 + result.battle.serverSeed.hashCode() + lane * 1_009,
    size,
)

internal fun localBattleFlowSentence(
    anchorIndex: Int,
    result: BattlePreviewResult,
): String {
    val options = when (anchorIndex) {
        0 -> listOf(
            "비가 그친 돌바닥에 두 사람의 발자국이 엇갈리며 첫 수의 방향을 서로에게 드러냈다.",
            "바람에 흔들린 먼지가 잠깐 시야를 가리자 ${battleKoreanSubject(result.userName)} 먼저 멈추고 ${battleKoreanSubject(result.opponentName)} 그 정적을 시험했다.",
            "좁은 통로의 메아리가 실제 발소리보다 반 박자 늦게 돌아오며 첫 접근부터 판단을 어렵게 했다.",
            "길게 늘어진 그림자 사이에서 ${battleKoreanWith(result.userName)} ${battleKoreanSubject(result.opponentName)} 정면 대신 서로의 옆선을 먼저 노렸다.",
            "마른 낙엽이 밟히는 소리를 감추지 못해 두 사람은 기습 대신 상대의 첫 반응을 이용하기로 했다.",
            "발밑의 얕은 물웅덩이가 작은 체중 이동까지 비추며 첫 공방을 숨김없는 수 싸움으로 바꾸었다.",
        )
        1 -> listOf(
            "앞 장면에서 벌어진 간격이 곧 퇴로가 되었고, 따라붙는 쪽은 공격보다 먼저 그 길을 닫았다.",
            "같은 박자가 두 번 이어진 뒤 세 번째 움직임만 반 박자 늦어지며 예상했던 방어가 허공을 갈랐다.",
            "한쪽이 벽 쪽으로 밀린 듯 보였지만 남겨 둔 옆 공간이 새로운 공격 각도로 바뀌었다.",
            "빠른 공세가 이어질수록 서두르지 않은 한 걸음이 오히려 다음 충돌의 중심을 차지했다.",
            "무기와 장비가 낸 서로 다른 소리가 겹치며 눈보다 귀가 먼저 다음 공격의 방향을 좇았다.",
            "짧은 견제가 반복되자 반응의 습관이 드러났고, 두 번째 선택부터는 서로가 그 습관을 노렸다.",
        )
        2 -> listOf(
            "밀리던 움직임은 단순한 후퇴가 아니었고, 미리 남겨 둔 반걸음이 되받아칠 공간을 만들었다.",
            "정면의 힘이 팽팽히 맞선 순간 발의 위치가 바뀌며 우세하던 공격선이 옆으로 흘러 버렸다.",
            "누적된 견제가 한순간 멎자 먼저 참지 못한 움직임이 나타났고 그 선택이 흐름을 뒤집었다.",
            "깨진 잔해를 사이에 둔 우회가 직선 공격을 무디게 만들며 공방의 주도권을 다시 나누었다.",
            "숨을 고르는 듯한 정적 뒤에 짧은 연계가 폭발하며 앞서던 쪽도 급히 안전한 간격을 되찾아야 했다.",
            "처음에는 실수처럼 보였던 빈틈이 추격을 끌어들이자 준비된 반격의 길이 선명해졌다.",
        )
        else -> listOf(
            "앞선 공방에서 반복된 선택이 마지막 빈틈으로 돌아오며 어느 쪽도 쉽게 물러설 수 없는 거리가 되었다.",
            "거의 맞닿은 거리에서 회피와 추격이 한 호흡에 이어져 다음 한 번이 모든 흐름을 거두게 되었다.",
            "빠르게 오가던 발소리가 동시에 멎었고, 두 사람은 앞서 읽어 둔 습관을 마지막 수에 걸었다.",
            "흩어진 먼지가 가라앉기도 전에 공격과 방어가 다시 겹치며 결말로 향하는 길이 하나만 남았다.",
            "처음 벌어졌던 거리가 완전히 사라지고, 앞서 아껴 둔 움직임이 마지막 교환을 향해 모였다.",
            "몇 차례 바뀐 주도권 끝에 작은 체중 이동 하나가 마지막 방어의 방향을 먼저 결정했다.",
        )
    }
    return options[localBattleNarrativeIndex(result, anchorIndex, options.size)]
}

internal fun localBattleTraitSentence(
    name: String,
    trait: BattleTraitDefinition,
    variation: Int,
): String {
    val behavior = trait.descriptionKo.trim().removeSuffix(".")
    return when (Math.floorMod(variation, 4)) {
        0 -> "${battleKoreanTopic(name)} ${trait.nameKo}답게 $behavior."
        1 -> "공방이 거칠어질수록 ${battleKoreanPossessive(name)} 움직임에는 ${trait.nameKo}의 면모가 선명해져, $behavior."
        2 -> "${trait.nameKo}의 습관이 ${battleKoreanPossessive(name)} 다음 선택에 묻어나, $behavior."
        else -> "${battleKoreanTopic(name)} 짧은 판단에서 ${trait.nameKo}의 면모를 보이며 $behavior."
    }
}

internal fun localBattleRoundIndexes(
    rounds: List<BattleRound>,
    phaseCount: Int,
): List<Int> {
    if (rounds.size <= 1) return List(phaseCount) { 0 }
    if (rounds.size < phaseCount) {
        return List(phaseCount) { index ->
            (index * rounds.lastIndex.toDouble() / (phaseCount - 1).coerceAtLeast(1))
                .toInt()
                .coerceIn(0, rounds.lastIndex)
        }
    }
    val selected = linkedSetOf(0, rounds.lastIndex)
    val representedSkills = selected
        .flatMap { index -> listOf(rounds[index].userAction.skillId, rounds[index].opponentAction.skillId) }
        .filter(String::isNotBlank)
        .toMutableSet()
    while (selected.size < phaseCount) {
        val next = rounds.indices
            .filterNot(selected::contains)
            .maxWithOrNull(
                compareBy<Int> { index ->
                    (if (rounds[index].userAction.finisher) 1 else 0) +
                        (if (rounds[index].opponentAction.finisher) 1 else 0)
                }.thenBy { index ->
                    (if (rounds[index].userAction.powerAttack) 1 else 0) +
                        (if (rounds[index].opponentAction.powerAttack) 1 else 0)
                }.thenBy { index ->
                    listOf(rounds[index].userAction.skillId, rounds[index].opponentAction.skillId)
                        .count { it.isNotBlank() && it !in representedSkills }
                }.thenBy { index ->
                    listOf(rounds[index].userAction.skillId, rounds[index].opponentAction.skillId)
                        .count(String::isNotBlank)
                }.thenBy { index ->
                    (if (rounds[index].userAction.critical) 1 else 0) +
                        (if (rounds[index].opponentAction.critical) 1 else 0)
                }.thenBy { index ->
                    rounds[index].userAction.damage + rounds[index].opponentAction.damage
                }.thenByDescending { it },
            ) ?: break
        selected += next
        listOf(rounds[next].userAction.skillId, rounds[next].opponentAction.skillId)
            .filter(String::isNotBlank)
            .forEach(representedSkills::add)
    }
    return selected.sorted()
}

/** Network failure never traps the non-dismissible QA battle; it falls back to engine facts. */
private fun buildLocalBattleNarrative(
    result: BattlePreviewResult,
): BattleQaNarrative {
    val phaseCount = battleNarrativeAnchorCount(result.battle.rounds.size)
    val rounds = result.battle.rounds
    val indexes = localBattleRoundIndexes(rounds, phaseCount)
    val userTraits = result.battle.user.activeTraitIds.mapNotNull(BattleTraitCatalog.byId::get)
    val opponentTraits = result.battle.opponent.activeTraitIds.mapNotNull(BattleTraitCatalog.byId::get)
    val narratedTraits = buildList {
        val count = maxOf(userTraits.size, opponentTraits.size)
        repeat(count) { index ->
            userTraits.getOrNull(index)?.let { add(result.userName to it) }
            opponentTraits.getOrNull(index)?.let { add(result.opponentName to it) }
        }
    }
    val scenes = indexes.mapIndexed { sceneIndex, roundIndex ->
        val round = rounds[roundIndex]
        val variation = localBattleNarrativeIndex(result, sceneIndex + 20, 10_000)
        val first = localBattleActionText(
            name = result.userName,
            action = round.userAction,
            skills = result.battle.user.skills,
            equipment = result.battle.user.equipment,
            variation = variation,
        )
        val second = localBattleActionText(
            name = result.opponentName,
            action = round.opponentAction,
            skills = result.battle.opponent.skills,
            equipment = result.battle.opponent.equipment,
            variation = variation + 1,
        )
        val traitSentence = narratedTraits.getOrNull(sceneIndex % narratedTraits.size.coerceAtLeast(1))
            ?.let { (ownerName, trait) ->
                localBattleTraitSentence(ownerName, trait, variation + sceneIndex)
            }
        BattleQaScene(
            phaseId = "P${sceneIndex + 1}",
            title = "전투 흐름 ${sceneIndex + 1}",
            text = if (sceneIndex == phaseCount - 1) {
                listOfNotNull("$first.", "$second.", traitSentence, localBattleOutcomeSentence(result))
                    .joinToString(" ")
            } else {
                listOfNotNull("$first.", "$second.", localBattleFlowSentence(sceneIndex, result), traitSentence)
                    .joinToString(" ")
            },
            dialogue = null,
            effectKey = when {
                sceneIndex == phaseCount - 1 -> "FINISH"
                round.userAction.critical || round.opponentAction.critical -> "HEAVY_HIT"
                round.userAction.kind.name == "GUARD" || round.opponentAction.kind.name == "GUARD" -> "GUARD"
                else -> "CLASH"
            },
        )
    }
    return BattleQaNarrative(
        schemaVersion = 2,
        requestId = UUID.randomUUID().toString(),
        battleId = result.battle.battleId,
        userName = result.userName,
        opponentName = result.opponentName,
        phaseCount = phaseCount,
        source = "local_template",
        modelValid = false,
        model = "deterministic-local-fallback",
        syntheticOnly = true,
        productionDatabaseTouched = false,
        attemptCount = 1,
        latencyMs = 0L,
        usage = BattleQaUsage(),
        estimatedCostUsd = 0.0,
        scenes = scenes,
    )
}

internal fun battleHeroClassLabel(
    heroClass: BattleHeroClass,
    language: AppLanguage = AppLanguage.KOREAN,
): String = when (language) {
    AppLanguage.KOREAN -> when (heroClass) {
        BattleHeroClass.WARRIOR -> "파이터"
        BattleHeroClass.ROGUE -> "시프"
        BattleHeroClass.RANGER -> "레인저"
        BattleHeroClass.MAGE -> "메이지"
        BattleHeroClass.CLERIC -> "클래릭"
        BattleHeroClass.PALADIN -> "팔라딘"
    }
    AppLanguage.ENGLISH -> when (heroClass) {
        BattleHeroClass.WARRIOR -> "Fighter"
        BattleHeroClass.ROGUE -> "Thief"
        BattleHeroClass.RANGER -> "Ranger"
        BattleHeroClass.MAGE -> "Mage"
        BattleHeroClass.CLERIC -> "Cleric"
        BattleHeroClass.PALADIN -> "Paladin"
    }
    AppLanguage.JAPANESE -> when (heroClass) {
        BattleHeroClass.WARRIOR -> "ファイター"
        BattleHeroClass.ROGUE -> "シーフ"
        BattleHeroClass.RANGER -> "レンジャー"
        BattleHeroClass.MAGE -> "メイジ"
        BattleHeroClass.CLERIC -> "クレリック"
        BattleHeroClass.PALADIN -> "パラディン"
    }
}

internal fun battleHeroClassLevelLabel(
    heroClass: BattleHeroClass,
    level: Long,
    language: AppLanguage,
): String = "${battleHeroClassLabel(heroClass, language)} · Lv.$level"

private fun BattleStance.toGuidance(): BattleGuidance = BattleGuidance.valueOf(name)

private fun BattleGuidance.toBattleStance(): BattleStance = BattleStance.valueOf(name)

internal fun battlePreviewTraits(activeTraits: List<ActiveBattleTrait>): List<BattlePreviewTrait> =
    activeTraits.mapNotNull { active ->
        BattleTraitCatalog.byId[active.traitId]?.let { definition ->
            BattlePreviewTrait(
                id = definition.id,
                name = definition.nameKo,
                description = definition.descriptionKo,
                evidence = "전투 근거 ${active.evidenceCount.coerceAtLeast(1)}회",
            )
        }
    }

@Composable
internal fun BattlePanel(
    state: SimpleGameState,
    characterSlotId: Int,
    combatPower: Long,
    heroPathEntry: HeroPathArenaEntryUiModel,
    runtime: ArenaPanelRuntime,
    modifier: Modifier = Modifier,
    isActive: Boolean = true,
    onSessionPhaseChanged: (BattleSessionPhase) -> Unit = {},
    onOpenRanking: (ArenaRankingLocalStanding) -> Unit = {},
    onArenaPlacementRecovered: (ArenaRankingLocalStanding) -> Unit = {},
    onArenaStandingCommitted: (ArenaRankingLocalStanding) -> Unit = {},
    onOpenHeroPath: () -> Unit = {},
    arenaRewardedRefillGrant: ArenaRewardedRefillGrant? = null,
    onRequestArenaRewardedRefill: (identity: String) -> Unit = {},
    rewardedAdPreloadStarted: Boolean = false,
    onRewardedAdPreloadAvailabilityChanged: (Boolean) -> Unit = {},
    /** Explicit in-memory QA fixture. It never enables or invokes a remote transport. */
    arenaServerMatchingQaFixture: PublicPlayerRoster? = null,
) {
    val heroName = state.hero.name
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    val unlimitedEntries = BuildConfig.BATTLE_UNLIMITED_ENTRIES
    val ignoreHeroLevelGate = BuildConfig.BATTLE_IGNORE_HERO_LEVEL_GATE
    val qaHeroLevelOverride = BuildConfig.BATTLE_QA_HERO_LEVEL_OVERRIDE
    val serverMatchingAllowed = arenaServerMatchingEnabled(
        arenaFlagEnabled = BuildConfig.ARENA_SERVER_MATCHING_ENABLED,
        sharedTransportEnabled = BuildConfig.SHARED_PLAYER_SYNC_ENABLED,
        remoteServicesEnabled = BuildConfig.REMOTE_SERVICES_ENABLED,
        sharedPlayerQaTransportEnabled = ArenaServerQaRuntimeConfig.fromBuildConfig().enabled,
        qaFixtureEnabled = arenaServerMatchingQaFixture != null,
    )
    val conditionIdentity = state.rankingCharacterId.ifBlank { "local-slot:$characterSlotId" }
    val localStateStore = remember(context.applicationContext) { BattleLocalStateStore(context.applicationContext) }
    val arenaBootCount = remember(context.applicationContext) {
        currentArenaBootCount(context.applicationContext)
    }
    val loadedState = remember(conditionIdentity) { runCatching { localStateStore.load(conditionIdentity) } }
    val persistedState = remember(conditionIdentity) {
        val wallNow = System.currentTimeMillis()
        val elapsedNow = SystemClock.elapsedRealtime()
        localStateStore.refreshBattleEntriesTrusted(
            identity = conditionIdentity,
            roster = state.publicPlayerRoster,
            deviceWallNowMillis = wallNow,
            elapsedRealtimeMillis = elapsedNow,
            bootCount = arenaBootCount,
        ) ?: refreshTrustedBattleEntrySnapshot(
                snapshot = loadedState.getOrDefault(BattleLocalSnapshot()),
                roster = state.publicPlayerRoster,
                deviceWallNowMillis = wallNow,
                elapsedRealtimeMillis = elapsedNow,
                bootCount = arenaBootCount,
            )
    }
    val ticketClockSnapshot = remember(conditionIdentity) { AtomicReference(persistedState) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var appInForeground by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> appInForeground = true
                Lifecycle.Event.ON_STOP -> appInForeground = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val stance = BattleStance.BALANCED
    var entriesRemaining by rememberSaveable(conditionIdentity, unlimitedEntries) {
        mutableIntStateOf(battleEntryCountForSession(persistedState.entriesRemaining, unlimitedEntries))
    }
    var entryRecoveryStartedAtMillis by rememberSaveable(conditionIdentity) {
        mutableStateOf(persistedState.entryRecoveryStartedAtMillis)
    }
    var rewardedRefillsUsed by rememberSaveable(conditionIdentity) {
        mutableIntStateOf(persistedState.rewardedRefillsUsed)
    }
    var dailyBattlesUsed by rememberSaveable(conditionIdentity) {
        mutableIntStateOf(persistedState.dailyBattlesUsed)
    }
    var dailyBattleDay by rememberSaveable(conditionIdentity) {
        mutableStateOf(persistedState.dailyBattleDay)
    }
    var entryClockMillis by remember(conditionIdentity) {
        mutableStateOf(persistedState.arenaTrustedEpochMillis)
    }
    var placementCompleted by rememberSaveable(conditionIdentity) {
        mutableIntStateOf(persistedState.placementCompleted)
    }
    var score by rememberSaveable(conditionIdentity) {
        mutableIntStateOf(persistedState.score.coerceAtLeast(0))
    }
    var scoreAchievedAtMillis by rememberSaveable(conditionIdentity) {
        mutableStateOf(persistedState.scoreAchievedAtMillis)
    }
    var wins by rememberSaveable(conditionIdentity) { mutableIntStateOf(persistedState.wins) }
    var losses by rememberSaveable(conditionIdentity) { mutableIntStateOf(persistedState.losses) }
    var draws by rememberSaveable(conditionIdentity) { mutableIntStateOf(persistedState.draws) }

    fun currentArenaRankingStanding(
        currentScore: Int = score,
        currentPlacement: Int = placementCompleted,
        currentWins: Int = wins,
        currentLosses: Int = losses,
        currentDraws: Int = draws,
        achievedAtMillis: Long = scoreAchievedAtMillis,
    ) = ArenaRankingLocalStanding(
        characterId = state.rankingCharacterId,
        displayName = state.hero.name,
        heroClass = state.hero.heroClass,
        level = state.hero.level,
        score = currentScore.coerceAtLeast(0),
        completedBattles = currentPlacement,
        wins = currentWins,
        losses = currentLosses,
        draws = currentDraws,
        observedAtEpochMillis = achievedAtMillis,
    )
    LaunchedEffect(conditionIdentity, persistedState.arenaRankingPlacement) {
        val placement = persistedState.arenaRankingPlacement
        if (placement != null) {
            onArenaPlacementRecovered(
                placement.toArenaRankingStanding(
                    characterId = state.rankingCharacterId,
                    displayName = state.hero.name,
                    heroClass = state.hero.heroClass,
                    level = state.hero.level,
                ),
            )
        }
        if (persistedState.placementCompleted >= BATTLE_PLACEMENT_REQUIRED) {
            onArenaStandingCommitted(
                currentArenaRankingStanding(
                    currentScore = persistedState.score,
                    currentPlacement = persistedState.placementCompleted,
                    currentWins = persistedState.wins,
                    currentLosses = persistedState.losses,
                    currentDraws = persistedState.draws,
                    achievedAtMillis = persistedState.scoreAchievedAtMillis,
                ),
            )
        }
    }
    val sessionHistory = remember(conditionIdentity) {
        mutableStateListOf<BattlePreviewHistory>().apply { addAll(persistedState.history) }
    }
    var selectedHistory by remember { mutableStateOf<BattlePreviewHistory?>(null) }
    var overlayState by runtime::overlayState
    var progression by remember(conditionIdentity) { mutableStateOf(persistedState.arenaProgression) }
    var skillTree by remember(conditionIdentity) {
        mutableStateOf(runCatching {
            ArenaSkillTreeRules.initialize(persistedState.arenaSkillTree, state.hero.heroClass)
        }.getOrElse { ArenaSkillTreeState(state.hero.heroClass) })
    }
    var supportOwnership by remember(conditionIdentity) { mutableStateOf(persistedState.arenaSupportOwnership) }
    var growthReady by remember(conditionIdentity) { mutableStateOf(false) }
    var growthStorageFailed by remember(conditionIdentity) { mutableStateOf(false) }
    var showSkillTree by runtime::skillTreeVisible
    var growthToday by remember { mutableStateOf(persistedState.gameEpochDay) }
    val preparationScope = rememberCoroutineScope()
    var preparing by remember { mutableStateOf(false) }
    var preparationFailure by remember { mutableStateOf<BattlePreparationFailure?>(null) }
    var showBattleStartConfirmation by remember(conditionIdentity) { mutableStateOf(false) }
    var autoStartReadyRequestId by remember(conditionIdentity) { mutableStateOf<String?>(null) }

    fun refreshGrowth(): Boolean {
        // A level-up cannot replace the allocation captured by a preparing or playing battle.
        if (preparing || runtime.overlayState != null) return growthReady
        val updated = localStateStore.update(conditionIdentity) { stored ->
            require(ArenaProgressionRules.isValid(stored.arenaProgression))
            // A temporary foreground reload must not settle a replay that is still running.
            val recovered = if (runtime.overlayState == null) recoverArenaProgression(stored) else stored
            require(recovered.arenaProgression.pending == null || runtime.overlayState != null)
            val initialized = initializeArenaSkillTree(recovered.copy(arenaProgression = ArenaCharacterPointRules.migrate(
                recovered.arenaProgression, state.hero.level, ignoreHeroLevelGate),
                arenaSupportOwnership = reconcileArenaSupportOwnership(
                    recovered.arenaSupportOwnership, state.hero.heroClass, state.hero.level)),
                state.hero.heroClass)
            reconcileArenaSkillTreeForFighter(
                snapshot = initialized,
                heroClass = state.hero.heroClass,
                ownedAttackIds = ArenaTurnInputAdapter.ownedAttackIds(projectArenaStateForQa(state, qaHeroLevelOverride)),
                heroLevel = maxOf(state.hero.level, qaHeroLevelOverride.toLong()),
                allowLowLevelQa = ignoreHeroLevelGate,
            )
        }
        growthReady = updated != null
        growthStorageFailed = updated == null
        if (updated != null) {
            progression = updated.arenaProgression
            skillTree = requireNotNull(updated.arenaSkillTree)
            supportOwnership = updated.arenaSupportOwnership
        }
        return updated != null
    }
    LaunchedEffect(conditionIdentity, state.hero.level, preparing, overlayState == null) { refreshGrowth() }
    fun applyTicketSnapshot(snapshot: BattleLocalSnapshot) {
        ticketClockSnapshot.set(snapshot)
        entryClockMillis = snapshot.arenaTrustedEpochMillis
        growthToday = snapshot.gameEpochDay
        entriesRemaining = battleEntryCountForSession(snapshot.entriesRemaining, unlimitedEntries)
        entryRecoveryStartedAtMillis = snapshot.entryRecoveryStartedAtMillis
        rewardedRefillsUsed = snapshot.rewardedRefillsUsed
        dailyBattlesUsed = snapshot.dailyBattlesUsed
        dailyBattleDay = snapshot.dailyBattleDay
    }
    LaunchedEffect(
        conditionIdentity,
        isActive,
        appInForeground,
        state.publicPlayerRoster?.rosterId,
        state.publicPlayerRoster?.receivedAtMonotonicMillis,
        state.publicPlayerRoster?.receivedAtBootCount,
    ) {
        // Entering/leaving the arena or app foreground is a durable boundary. It also catches
        // recovery completed while this composable was paused without polling preferences.
        val restored = localStateStore.refreshBattleEntriesTrusted(
            identity = conditionIdentity,
            roster = state.publicPlayerRoster,
            deviceWallNowMillis = System.currentTimeMillis(),
            elapsedRealtimeMillis = SystemClock.elapsedRealtime(),
            bootCount = arenaBootCount,
        )
        if (restored != null) applyTicketSnapshot(restored)
        if (!isActive || !appInForeground) return@LaunchedEffect

        var lastPersistenceAttemptElapsed = Long.MIN_VALUE
        while (true) {
            val ticketSnapshot = ticketClockSnapshot.get()
            delay(
                if (
                    !unlimitedEntries &&
                    (ticketSnapshot.entriesRemaining < BATTLE_ENTRY_CAPACITY ||
                        ticketSnapshot.dailyBattlesUsed >= BATTLE_ENTRY_DAILY_LIMIT)
                ) 1_000L else 60_000L,
            )
            val elapsedNow = SystemClock.elapsedRealtime()
            val tick = advanceArenaTicketUiClock(
                snapshot = ticketClockSnapshot.get(),
                roster = state.publicPlayerRoster,
                elapsedRealtimeMillis = elapsedNow,
                bootCount = arenaBootCount,
            )
            ticketClockSnapshot.set(tick.snapshot)
            entryClockMillis = tick.nowEpochMillis
            val retryReady = lastPersistenceAttemptElapsed == Long.MIN_VALUE ||
                elapsedNow - lastPersistenceAttemptElapsed >= ARENA_TICKET_PERSIST_RETRY_MILLIS
            if (tick.persistenceRequired && retryReady) {
                lastPersistenceAttemptElapsed = elapsedNow
                val persisted = localStateStore.refreshBattleEntriesTrusted(
                    identity = conditionIdentity,
                    roster = state.publicPlayerRoster,
                    deviceWallNowMillis = System.currentTimeMillis(),
                    elapsedRealtimeMillis = elapsedNow,
                    bootCount = arenaBootCount,
                )
                if (persisted != null) applyTicketSnapshot(persisted)
            }
        }
    }
    LaunchedEffect(
        conditionIdentity, state.hero.level, rewardedRefillsUsed, dailyBattlesUsed,
        entriesRemaining, rewardedAdPreloadStarted,
    ) {
        onRewardedAdPreloadAvailabilityChanged(arenaRewardedAdCanPreload(
            heroLevel = state.hero.level,
            refillsUsed = rewardedRefillsUsed,
            dailyBattlesUsed = dailyBattlesUsed,
            entriesRemaining = entriesRemaining,
            preloadAlreadyStarted = rewardedAdPreloadStarted,
        ))
    }

    LaunchedEffect(conditionIdentity, arenaRewardedRefillGrant?.requestId) {
        val grant = arenaRewardedRefillGrant ?: return@LaunchedEffect
        if (grant.identity != conditionIdentity || grant.requestId.isBlank()) return@LaunchedEffect
        val refilled = localStateStore.applyRewardedBattleEntryRefillTrusted(
            identity = conditionIdentity,
            roster = state.publicPlayerRoster,
            deviceWallNowMillis = System.currentTimeMillis(),
            elapsedRealtimeMillis = SystemClock.elapsedRealtime(),
            bootCount = arenaBootCount,
            requestId = grant.requestId,
        )
        if (refilled != null) {
            applyTicketSnapshot(refilled)
        } else {
            growthStorageFailed = true
        }
    }
    val ownedSupports = supportOwnership?.ids.orEmpty() +
        if (qaHeroLevelOverride > 0) ArenaSupportCatalog.unlockedIds(
            state.hero.heroClass, maxOf(state.hero.level, qaHeroLevelOverride.toLong())) else emptySet()
    val skillTreeArenaState = remember(state, qaHeroLevelOverride) {
        projectArenaStateForQa(state, qaHeroLevelOverride)
    }
    val ownedAttackIds = remember(skillTreeArenaState) {
        ArenaTurnInputAdapter.ownedAttackIds(skillTreeArenaState)
    }
    val arenaLevel = ArenaCharacterPointRules.combatBudget(
        skillTreeArenaState.hero.level, ignoreHeroLevelGate).coerceAtLeast(1)
    val skillTreeView = ArenaSkillTreeRules.view(skillTree, arenaLevel, ownedAttackIds)
    val arenaUnlockedForUi = arenaEntryUnlocked(
        progressionUnlocked = progression.unlocked,
        heroLevel = state.hero.level,
        ignoreHeroLevelGate = ignoreHeroLevelGate,
    )
    val skillTreeUi = arenaSkillTreeUiModel(
        treeView = skillTreeView,
        unlocked = arenaUnlockedForUi,
        editingEnabled = growthReady && overlayState == null && !preparing && progression.pending == null,
        language = language,
    )
    val dailyEntryLimitReached = !unlimitedEntries &&
        dailyBattlesUsed >= BATTLE_ENTRY_DAILY_LIMIT
    val entryRecoveryRemainingMillis = if (
        unlimitedEntries || entriesRemaining >= BATTLE_ENTRY_CAPACITY
    ) {
        null
    } else {
        battleEntryRecoveryRemainingMillis(
            BattleEntryRecoveryState(entriesRemaining, entryRecoveryStartedAtMillis),
            entryClockMillis,
        )
    }
    val entryRecoveryCountdown = entryRecoveryRemainingMillis?.let(
        ::battleEntryRecoveryCountdownLabel,
    )
    val dailyResetCountdown = if (dailyEntryLimitReached) {
        arenaDailyResetRemainingMillis(
            dailyBattleDay = dailyBattleDay,
            trustedNowMillis = entryClockMillis,
        ).takeIf { it > 0L }?.let(::arenaDailyResetCountdownLabel)
    } else null
    val rewardedRefillCount = battleRewardedRefillCount(dailyBattlesUsed)
    val rewardedRefillAvailable = !unlimitedEntries && battleRewardedRefillAvailable(
        entriesRemaining = entriesRemaining,
        refillsUsed = rewardedRefillsUsed,
        dailyBattlesUsed = dailyBattlesUsed,
    )
    val rewardedRefillLimitReached = !unlimitedEntries &&
        entriesRemaining <= 0 &&
        rewardedRefillsUsed >= BATTLE_REWARDED_REFILL_DAILY_LIMIT
    fun changeSkillTree(nodeId: String?, resetOnly: Boolean = false) {
        if (!skillTreeUi.editingEnabled) return
        val updated = localStateStore.update(conditionIdentity) { stored ->
            val current = ArenaSkillTreeRules.initialize(stored.arenaSkillTree, state.hero.heroClass)
            val currentArenaLevel = ArenaCharacterPointRules.combatBudget(
                skillTreeArenaState.hero.level, ignoreHeroLevelGate).coerceAtLeast(1)
            val mutation = if (nodeId == null) {
                ArenaSkillTreeRules.reset(current, state.hero.heroClass, editingEnabled = true)
            } else if (resetOnly) {
                ArenaSkillTreeRules.resetNode(current, state.hero.heroClass, currentArenaLevel,
                    ownedAttackIds, nodeId, editingEnabled = stored.arenaProgression.pending == null)
            } else {
                val currentRank = current.allocations.firstOrNull { it.nodeId == nodeId }?.rank ?: 0
                ArenaSkillTreeRules.allocate(
                    state = current,
                    heroClass = state.hero.heroClass,
                    arenaLevel = currentArenaLevel,
                    ownedAttackIds = ownedAttackIds,
                    nodeId = nodeId,
                    targetRank = currentRank + 1,
                    editingEnabled = true,
                )
            }
            applyArenaSkillTreeMutation(stored, mutation)
        }
        if (updated != null) {
            skillTree = requireNotNull(updated.arenaSkillTree)
            growthStorageFailed = false
        } else {
            growthStorageFailed = true
        }
    }
    val sessionPhase = battleSessionPhase(overlayState?.flowStep(), preparing)
    LaunchedEffect(sessionPhase) {
        onSessionPhaseChanged(sessionPhase)
    }

    val matchReadyState = overlayState as? BattleOverlayState.MatchReady
    LaunchedEffect(matchReadyState?.requestId, matchReadyState?.expiresAtMillis) {
        val ready = matchReadyState ?: return@LaunchedEffect
        delay((ready.expiresAtMillis - System.currentTimeMillis()).coerceAtLeast(1L))
        val current = overlayState as? BattleOverlayState.MatchReady
        if (
            current?.requestId == ready.requestId &&
            battleMatchReadyExpired(current.expiresAtMillis, System.currentTimeMillis())
        ) {
            overlayState = null
        }
    }

    val participate = participate@ {
        if (growthReady && arenaUnlockedForUi && progression.pending == null &&
            skillTreeUi.availablePoints == 0 && battleLoadoutSummary(skillTreeUi).hasAttackSkill &&
            (unlimitedEntries || (entriesRemaining > 0 && !dailyEntryLimitReached)) &&
            overlayState == null && !preparing) {
            val capturedScore = score
            val capturedMatchSequence = placementCompleted
            val capturedRecentMatches = arenaRecentMatchHistory(sessionHistory.toList())
            val capturedOwnedSupports = ownedSupports.toSet()
            val capturedProgression = progression.copy(allocations = progression.allocations.toList())
            val capturedSkillTree = skillTree.copy(allocations = skillTree.allocations.toList())
            val arenaState = projectArenaStateForQa(state, qaHeroLevelOverride)
            val arenaCombatPower = if (qaHeroLevelOverride > 0) {
                SimpleGameEngine().displayCombatPower(arenaState)
            } else {
                combatPower
            }
            val battleId = UUID.randomUUID().toString()
            val requestedAt = entryClockMillis
            val poolRequesterCharacterId = arenaServerMatchingQaFixture
                ?.requesterCharacterId
                ?: conditionIdentity
            val gameEpochDay = growthToday
            val standing = BattleSeasonStanding(
                    score = score,
                    completedBattles = placementCompleted,
                    wins = wins,
                    losses = losses,
                    draws = draws,
                )
            val tickets = BattleTicketState(
                    gameEpochDay = gameEpochDay,
                    remaining = battleEntryCountForSession(entriesRemaining, unlimitedEntries),
                )
            preparing = true
            preparationScope.launch {
                var readyForAutoStart = false
                try {
                    // Candidate generation can build many skill presets; keep it off the UI thread.
                    val selectedOpponent = withContext(Dispatchers.Default) {
                        selectArenaOpponent(
                            serverRosterEnabled = serverMatchingAllowed,
                            roster = if (serverMatchingAllowed) {
                                arenaServerMatchingQaFixture ?: arenaState.publicPlayerRoster
                            } else {
                                null
                            },
                            requesterCharacterId = poolRequesterCharacterId,
                            requesterLevel = arenaState.hero.level,
                            nowEpochMillis = requestedAt,
                            completedMatchSequence = capturedMatchSequence.toLong(),
                            requesterCombatPower = arenaCombatPower,
                            recentMatches = capturedRecentMatches,
                            requesterStats = arenaState.hero.stats.copy(),
                        )
                    }
                    if (selectedOpponent !is ArenaServerMatchSelectionResult.Ready) {
                        preparationFailure = BattlePreparationFailure.ServerOpponentUnavailable(
                            (selectedOpponent as ArenaServerMatchSelectionResult.Unavailable).reason,
                        )
                        return@launch
                    }
                    // One immutable simulation supplies presentation, winner and local settlement.
                    val prepared = withContext(Dispatchers.Default) {
                        val baseMatch = BattleQaMatchFactory.createMatch(
                            state = arenaState,
                            combatPower = arenaCombatPower,
                            guidance = stance.toGuidance(),
                            userScore = capturedScore,
                            matchSequence = capturedMatchSequence,
                            battleId = battleId,
                            serverSeed = requestedAt xor System.nanoTime(),
                            requestedAtMillis = requestedAt,
                        )
                        val match = baseMatch.withArenaServerOpponent(
                            selectedOpponent,
                            opponentReferenceScore = capturedScore,
                        )
                        prepareArenaBattle(arenaState, match, standing, tickets, stance, language,
                            capturedProgression, ignoreHeroLevelGate, capturedOwnedSupports,
                            skillTree = capturedSkillTree,
                            preparedOpponent = selectedOpponent.opponent.combat,
                            preparedOpponentSource = selectedOpponent.source,
                            matchmakingProfile = selectedOpponent.matchmakingProfile)
                    }
                    if (prepared == null) {
                        preparationFailure = BattlePreparationFailure.General
                    } else {
                        val (result, narrative) = prepared
                        overlayState = BattleOverlayState.MatchReady(
                            result = result.copy(entriesAfter = battleEntryCountForSession(result.entriesAfter, unlimitedEntries)),
                            requestId = battleId,
                            expiresAtMillis = battleMatchReadyExpiresAt(System.currentTimeMillis()),
                            narrative = narrative,
                        )
                        autoStartReadyRequestId = battleId
                        readyForAutoStart = true
                    }
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    preparationFailure = BattlePreparationFailure.General
                } finally {
                    if (!readyForAutoStart) preparing = false
                }
            }
        }
    }

    fun settleStartedBattle(
        result: BattlePreviewResult,
        narrative: BattleQaNarrative,
    ): Boolean {
        if (sessionHistory.any { it.battleId == result.battle.battleId }) return true
        val deviceWallAtSettlementMillis = System.currentTimeMillis()
        val live = result.supportBattle ?: return false
        val saved = localStateStore.update(conditionIdentity) { stored ->
            require(arenaBattleRevisionsMatch(stored, live))
            val entrySnapshot = refreshTrustedBattleEntrySnapshot(
                snapshot = stored,
                roster = state.publicPlayerRoster,
                deviceWallNowMillis = deviceWallAtSettlementMillis,
                elapsedRealtimeMillis = SystemClock.elapsedRealtime(),
                bootCount = arenaBootCount,
            )
            val nextHistory = (
                listOf(result.toHistory(narrative, entrySnapshot.arenaTrustedEpochMillis)) +
                    sessionHistory
                )
                .distinctBy(BattlePreviewHistory::battleId)
                .take(10)
            val ticketToday = entrySnapshot.gameEpochDay
            val spentSnapshot = if (unlimitedEntries) {
                BattleEntrySnapshotSpend(
                    accepted = true,
                    snapshot = entrySnapshot,
                )
            } else {
                spendBattleEntrySnapshot(
                    snapshot = entrySnapshot,
                    today = ticketToday,
                    nowMillis = entrySnapshot.arenaTrustedEpochMillis,
                )
            }
            require(spentSnapshot.accepted) { "No arena entry available or daily limit reached" }
            val reserved = ArenaProgressionRules.reserveBattle(stored.arenaProgression,
                result.battle.battleId, live.issuedDay,
                allowGrowth = live.growthEligible,
                outcome = result.outcome)
            val nextScoreAchievedAtMillis = arenaScoreAchievedAtMillis(
                previousScore = stored.score,
                nextScore = result.standingAfter.score,
                previousAchievedAtMillis = stored.scoreAchievedAtMillis,
                trustedNowMillis = entrySnapshot.arenaTrustedEpochMillis,
            )
            spentSnapshot.snapshot.copy(
                gameEpochDay = ticketToday,
                placementCompleted = result.standingAfter.completedBattles,
                score = result.standingAfter.score,
                scoreAchievedAtMillis = nextScoreAchievedAtMillis,
                wins = result.standingAfter.wins,
                losses = result.standingAfter.losses,
                draws = result.standingAfter.draws,
                arenaRankingPlacement = captureArenaRankingPlacement(
                    existing = stored.arenaRankingPlacement,
                    completedBattles = result.standingAfter.completedBattles,
                    score = result.standingAfter.score,
                    wins = result.standingAfter.wins,
                    losses = result.standingAfter.losses,
                    draws = result.standingAfter.draws,
                    observedAtEpochMillis = nextScoreAchievedAtMillis,
                ),
                history = nextHistory,
                arenaProgression = reserved,
            )
        }
        if (saved == null) {
            growthStorageFailed = true
            return false
        }
        progression = saved.arenaProgression
        skillTree = requireNotNull(saved.arenaSkillTree)

        applyTicketSnapshot(saved)
        placementCompleted = result.standingAfter.completedBattles
        score = result.standingAfter.score
        scoreAchievedAtMillis = saved.scoreAchievedAtMillis
        wins = result.standingAfter.wins
        losses = result.standingAfter.losses
        draws = result.standingAfter.draws
        sessionHistory.clear()
        sessionHistory.addAll(saved.history)
        onArenaStandingCommitted(
            currentArenaRankingStanding(
                currentScore = saved.score,
                currentPlacement = saved.placementCompleted,
                currentWins = saved.wins,
                currentLosses = saved.losses,
                currentDraws = saved.draws,
                achievedAtMillis = saved.scoreAchievedAtMillis,
            ),
        )
        return true
    }

    val startReadyBattle = {
        val ready = overlayState as? BattleOverlayState.MatchReady
        if (ready != null && settleStartedBattle(ready.result, ready.narrative)) {
            runtime.playbackPosition.intValue = 0
            runtime.playbackElapsed.longValue = 0L
            val playbackPlan = if (ready.result.supportBattle != null) BattlePlaybackPlan(emptyList())
                else battlePlaybackPlan(ready.result.battle, ready.narrative)
            overlayState = BattleOverlayState.Playing(
                ready.result,
                ready.narrative,
                playbackPlan = playbackPlan,
                playbackIndex = 0,
            )
        }
    }
    LaunchedEffect(matchReadyState?.requestId, autoStartReadyRequestId) {
        val ready = matchReadyState
        if (ready != null && ready.requestId == autoStartReadyRequestId) {
            autoStartReadyRequestId = null
            startReadyBattle()
            preparing = false
        }
    }
    fun settleGrowth(result: BattlePreviewResult): Boolean {
        val saved = localStateStore.update(conditionIdentity) { stored ->
            require(stored.history.any { it.battleId == result.battle.battleId })
            require(stored.arenaProgression.pending?.battleId.let { it == null || it == result.battle.battleId })
            recoverArenaProgression(stored)
        }
        if (saved != null) {
            progression = saved.arenaProgression
            growthReady = true
        }
        growthStorageFailed = saved == null
        return saved != null
    }
    val advanceBattle = {
        val playing = overlayState as? BattleOverlayState.Playing
        if (playing != null) {
            overlayState = if (
                playing.playbackIndex < playing.playbackPlan.sequence.lastIndex
            ) {
                playing.copy(playbackIndex = playing.playbackIndex + 1)
            } else {
                settleGrowth(playing.result)
                BattleOverlayState.Result(
                    playing.result,
                    playing.narrative,
                    playbackPlan = playing.playbackPlan,
                )
            }
        }
    }
    val completeBattle = {
        val completed = overlayState as? BattleOverlayState.Result
        if (completed != null && settleGrowth(completed.result)) {
            overlayState = null
        }
    }
    val backgroundResourceId = battleBackgroundResource(
        definitionId = state.adventureTale.definitionId,
        chapterNumber = state.adventureTale.chapterNumber,
    )
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (preparing || showSkillTree) Modifier.clearAndSetSemantics { } else Modifier),
        ) {
            when (val current = overlayState) {
                is BattleOverlayState.Playing,
                is BattleOverlayState.Result,
                -> BattleDedicatedPage(
                    state = requireNotNull(current),
                    placementCompleted = placementCompleted,
                    backgroundResourceId = backgroundResourceId,
                    modifier = Modifier.fillMaxSize(),
                    onAdvance = advanceBattle,
                    onComplete = completeBattle,
                    runtime = runtime,
                )
                else -> BattleMenuPage(
                    overlayState = current,
                    entriesRemaining = entriesRemaining,
                    unlimitedEntries = unlimitedEntries,
                    entryRecoveryCountdown = entryRecoveryCountdown,
                    dailyResetCountdown = dailyResetCountdown,
                    entryRecoveryRemainingMillis = entryRecoveryRemainingMillis,
                    rewardedRefillAvailable = rewardedRefillAvailable,
                    rewardedRefillCount = rewardedRefillCount,
                    rewardedRefillLimitReached = rewardedRefillLimitReached,
                    dailyLimitReached = dailyEntryLimitReached,
                    placementCompleted = placementCompleted,
                    score = score,
                    history = sessionHistory,
                    backgroundResourceId = backgroundResourceId,
                    modifier = Modifier.fillMaxSize(),
                    onParticipate = { showBattleStartConfirmation = true },
                    onStartBattle = startReadyBattle,
                    onRefillTickets = { onRequestArenaRewardedRefill(conditionIdentity) },
                    onSelectHistory = { selectedHistory = it },
                    onOpenRanking = { onOpenRanking(currentArenaRankingStanding()) },
                    heroPathEntry = heroPathEntry,
                    heroPathEditingEnabled = current == null,
                    onOpenHeroPath = onOpenHeroPath,
                    onOpenSupportPreview = null,
                    skillTreeUi = skillTreeUi,
                    onOpenSkillTree = { showSkillTree = true },
                    entryAllowed = growthReady && arenaUnlockedForUi && progression.pending == null,
                )
            }
        }
        if (preparing) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(AqBackground.copy(alpha = 0.6f))
                    .clickable(enabled = true, onClick = {})
                    .clearAndSetSemantics {
                        contentDescription = localized("상대 찾는 중", language)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = AqGold)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = arenaFindingOpponentLabel(language),
                        color = AqText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Black,
                    )
                }
            }
        }
        if (showSkillTree) {
            ArenaSkillTreeOverlay(
                model = skillTreeUi,
                modifier = Modifier.fillMaxSize(),
                onDismiss = { showSkillTree = false },
                onRankUp = { nodeId -> changeSkillTree(nodeId) },
                onReset = { changeSkillTree(null) },
                onResetSkill = { nodeId -> changeSkillTree(nodeId, resetOnly = true) },
            )
        }
    }
    if (showBattleStartConfirmation) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showBattleStartConfirmation = false },
            containerColor = AqSurface,
            title = {
                Text(
                    text = when (language) {
                        AppLanguage.ENGLISH -> "Start the duel?"
                        AppLanguage.JAPANESE -> "決闘を始めますか？"
                        else -> "결투를 시작할까요?"
                    },
                    modifier = Modifier.testTag("battle-start-confirmation-title"),
                    color = AqText,
                    fontWeight = FontWeight.Black,
                )
            },
            text = {
                Text(
                    text = when {
                        unlimitedEntries -> when (language) {
                            AppLanguage.ENGLISH -> "QA · No entry consumed"
                            AppLanguage.JAPANESE -> "QA・出場券は消費されません"
                            else -> "QA · 출전권 차감 없음"
                        }
                        language == AppLanguage.ENGLISH -> "Uses 1 entry"
                        language == AppLanguage.JAPANESE -> "出場券を1枚使用"
                        else -> "출전권 1개 사용"
                    },
                    color = AqMuted,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showBattleStartConfirmation = false
                        participate()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AqGold,
                        contentColor = AqBackground,
                    ),
                    modifier = Modifier.testTag("battle-start-confirm"),
                ) {
                    Text(
                        when (language) {
                            AppLanguage.ENGLISH -> "Start duel"
                            AppLanguage.JAPANESE -> "決闘開始"
                            else -> "결투 시작"
                        },
                        fontWeight = FontWeight.Black,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showBattleStartConfirmation = false },
                    modifier = Modifier.testTag("battle-start-cancel"),
                ) {
                    Text(
                        when (language) {
                            AppLanguage.ENGLISH -> "Cancel"
                            AppLanguage.JAPANESE -> "キャンセル"
                            else -> "취소"
                        },
                        color = AqText,
                    )
                }
            },
        )
    }
    if (growthStorageFailed) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { growthStorageFailed = false },
            containerColor = AqSurface,
            text = { ArenaFixedFont { MaterialText(when (language) {
                AppLanguage.ENGLISH -> "Arena progression could not be saved or loaded. Your saved data was not reset. Please try again."
                AppLanguage.JAPANESE -> "闘技場育成を保存・読み込みできませんでした。保存データは初期化されていません。もう一度お試しください。"
                else -> "결투장 성장을 저장하거나 불러오지 못했습니다. 저장 데이터는 초기화하지 않았습니다. 다시 시도해 주세요."
            }, color = AqText, fontSize = 14.sp) } },
            confirmButton = { ArenaFixedFont { TextButton(onClick = {
                val completed = overlayState as? BattleOverlayState.Result
                if (completed != null) settleGrowth(completed.result) else refreshGrowth()
            }) { Text(when(language) { AppLanguage.ENGLISH -> "Retry"; AppLanguage.JAPANESE -> "再試行"; else -> "다시 시도" }) } } },
            dismissButton = { ArenaFixedFont { TextButton(onClick = { growthStorageFailed = false }) {
                Text(arenaCloseLabel(language))
            } } },
        )
    }
    preparationFailure?.let { failure ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { preparationFailure = null },
            containerColor = AqSurface,
            text = { Text(
                when (failure) {
                    is BattlePreparationFailure.ServerOpponentUnavailable -> when (language) {
                        AppLanguage.ENGLISH -> "No eligible opponent is available. No entry was used."
                        AppLanguage.JAPANESE -> "条件に合う対戦相手がいません。出場回数は消費していません。"
                        else -> "조건에 맞는 상대가 없습니다. 출전권은 사용되지 않았습니다."
                    }
                    BattlePreparationFailure.General -> when (language) {
                        AppLanguage.ENGLISH -> "Battle could not be prepared. No entry or points were used."
                        AppLanguage.JAPANESE -> "対戦を準備できませんでした。出場回数やポイントは消費していません。"
                        else -> "전투를 준비하지 못했습니다. 출전과 점수는 차감되지 않았습니다."
                    }
                },
                color = AqText,
            ) },
            confirmButton = { TextButton(onClick = { preparationFailure = null }) {
                Text(arenaConfirmLabel(language))
            } },
        )
    }
    selectedHistory?.let { history ->
        BattleHistoryDetailDialog(
            history = history,
            onDismiss = { selectedHistory = null },
        )
    }

    BackHandler(
        enabled = isActive && sessionPhase == BattleSessionPhase.IN_BATTLE,
    ) {
        // Dedicated battle play remains active until the explicit completion action.
    }
}

@Composable
private fun BattleMenuPage(
    overlayState: BattleOverlayState?,
    entriesRemaining: Int,
    unlimitedEntries: Boolean,
    entryRecoveryCountdown: String?,
    dailyResetCountdown: String?,
    entryRecoveryRemainingMillis: Long?,
    rewardedRefillAvailable: Boolean,
    rewardedRefillCount: Int,
    rewardedRefillLimitReached: Boolean,
    dailyLimitReached: Boolean,
    placementCompleted: Int,
    score: Int,
    history: List<BattlePreviewHistory>,
    backgroundResourceId: Int,
    modifier: Modifier,
    onParticipate: () -> Unit,
    onStartBattle: () -> Unit,
    onRefillTickets: () -> Unit,
    onSelectHistory: (BattlePreviewHistory) -> Unit,
    onOpenRanking: () -> Unit,
    heroPathEntry: HeroPathArenaEntryUiModel,
    heroPathEditingEnabled: Boolean,
    onOpenHeroPath: () -> Unit,
    onOpenSupportPreview: (() -> Unit)?,
    skillTreeUi: ArenaSkillTreeUiModel,
    onOpenSkillTree: () -> Unit,
    entryAllowed: Boolean,
) {
    Column(modifier = modifier.background(AqBackground)) {
        BattleMenuStage(
            state = overlayState,
            entriesRemaining = entriesRemaining,
            unlimitedEntries = unlimitedEntries,
            entryRecoveryCountdown = entryRecoveryCountdown,
            dailyResetCountdown = dailyResetCountdown,
            entryRecoveryRemainingMillis = entryRecoveryRemainingMillis,
            rewardedRefillAvailable = rewardedRefillAvailable,
            rewardedRefillCount = rewardedRefillCount,
                    rewardedRefillLimitReached = rewardedRefillLimitReached,
            dailyLimitReached = dailyLimitReached,
            placementCompleted = placementCompleted,
            score = score,
            backgroundResourceId = backgroundResourceId,
            onParticipate = onParticipate,
            onStartBattle = onStartBattle,
            onRefillTickets = onRefillTickets,
            entryAllowed = entryAllowed,
            skillTreeUi = skillTreeUi,
            onOpenSkillTree = onOpenSkillTree,
        )
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            colors = CardDefaults.cardColors(containerColor = AqSurface),
            shape = RoundedCornerShape(22.dp),
        ) {
            BattleHome(
                placementCompleted = placementCompleted,
                score = score,
                history = history,
                onSelectHistory = onSelectHistory,
                onOpenRanking = onOpenRanking,
                heroPathEntry = heroPathEntry,
                heroPathEditingEnabled = heroPathEditingEnabled,
                onOpenHeroPath = onOpenHeroPath,
                onOpenSupportPreview = onOpenSupportPreview,
                skillTreeUi = skillTreeUi,
                onOpenSkillTree = onOpenSkillTree,
            )
        }
    }
}

@Composable
private fun BattleMenuStage(
    state: BattleOverlayState?,
    entriesRemaining: Int,
    unlimitedEntries: Boolean,
    entryRecoveryCountdown: String?,
    dailyResetCountdown: String?,
    entryRecoveryRemainingMillis: Long?,
    rewardedRefillAvailable: Boolean,
    rewardedRefillCount: Int,
    rewardedRefillLimitReached: Boolean,
    dailyLimitReached: Boolean,
    placementCompleted: Int,
    score: Int,
    backgroundResourceId: Int,
    onParticipate: () -> Unit,
    onStartBattle: () -> Unit,
    onRefillTickets: () -> Unit,
    entryAllowed: Boolean,
    skillTreeUi: ArenaSkillTreeUiModel,
    onOpenSkillTree: () -> Unit,
) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    val loadout = battleLoadoutSummary(skillTreeUi)
    val ticketRecoveryProgress = arenaTicketRecoveryProgress(
        entriesRemaining = entriesRemaining,
        unlimitedEntries = unlimitedEntries,
        recoveryRemainingMillis = entryRecoveryRemainingMillis,
    )
    val skillTreeReviewRequest = battleSkillTreeReviewRequest(
        enabled = BuildConfig.BATTLE_SKILL_TREE_REVIEW_ENABLED,
        language = language,
    )
    val openSkillTreeReview = skillTreeReviewRequest?.let { request ->
        {
            context.startActivity(
                Intent()
                    .setClassName(context.packageName, request.activityClassName)
                    .putExtra("arena_level", request.arenaLevel)
                    .putExtra("allocated_points", request.allocatedPoints)
                    .putExtra("language", request.languageTag),
            )
        }
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(BATTLE_MENU_STAGE_HEIGHT_DP.dp)
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(22.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(1.dp, AqGoldSoft, RoundedCornerShape(22.dp)),
        ) {
            Image(
                painter = painterResource(backgroundResourceId),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(Color(0xD5161020), Color(0xE01A1222), Color(0xF015101B)),
                    ),
                ),
            )
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    color = Color(0xB317111F),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, AqGoldSoft.copy(alpha = 0.38f)),
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1.12f).testTag("battle-season-standing"),
                            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            val placing = battlePlacementProgressVisible(placementCompleted)
                            Text(
                                text = if (placing) arenaSeasonPlacementTitle(language) else when (language) {
                                    AppLanguage.ENGLISH -> "Season score"
                                    AppLanguage.JAPANESE -> "シーズンスコア"
                                    else -> "시즌 점수"
                                },
                                color = AqMuted,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                            if (placing) {
                                UnlocalizedText(
                                    text = battlePlacementLabel(placementCompleted),
                                    color = BattleBlue,
                                    fontSize = 38.sp,
                                    lineHeight = 44.sp,
                                    fontWeight = FontWeight.Black,
                                )
                                LinearProgressIndicator(
                                    progress = {
                                        placementCompleted.coerceIn(0, BATTLE_PLACEMENT_REQUIRED).toFloat() /
                                            BATTLE_PLACEMENT_REQUIRED.toFloat()
                                    },
                                    modifier = Modifier.fillMaxWidth().height(4.dp)
                                        .clip(RoundedCornerShape(99.dp)).testTag("battle-placement-progress"),
                                    color = BattleBlue,
                                    trackColor = Color(0xFF493A52),
                                )
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.Bottom,
                                ) {
                                    val scoreText = score.coerceAtLeast(0).toString()
                                    UnlocalizedText(
                                        text = scoreText,
                                        color = AqGold,
                                        fontSize = when {
                                            scoreText.length >= 9 -> 20.sp
                                            scoreText.length >= 7 -> 24.sp
                                            scoreText.length >= 5 -> 30.sp
                                            else -> 38.sp
                                        },
                                        lineHeight = 44.sp,
                                        fontWeight = FontWeight.Black,
                                        maxLines = 1,
                                        modifier = Modifier.testTag("battle-season-score"),
                                    )
                                    Text(
                                        text = when (language) {
                                            AppLanguage.ENGLISH -> " pts"
                                            AppLanguage.JAPANESE -> " 点"
                                            else -> "점"
                                        },
                                        color = AqGoldSoft,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(bottom = 2.dp),
                                    )
                                }
                            }
                        }
                        Box(
                            Modifier
                                .padding(horizontal = 12.dp)
                                .width(1.dp)
                                .height(80.dp)
                                .background(AqGoldSoft.copy(alpha = 0.35f)),
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .testTag("battle-entry-status")
                                .then(
                                    if (openSkillTreeReview == null) Modifier else Modifier
                                        .testTag("battle-qa-skill-tree-entry")
                                        .clickable(role = Role.Button, onClick = openSkillTreeReview)
                                        .semantics {
                                            contentDescription = when (language) {
                                                AppLanguage.ENGLISH -> "Open all 6 class skill trees with maximum points"
                                                AppLanguage.JAPANESE -> "全6職業の最大ポイントスキルツリーを開く"
                                                else -> "6직업 최대 포인트 스킬트리 검수 열기"
                                            }
                                        },
                            ),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            ArenaGuideButton()
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = when (language) {
                                    AppLanguage.ENGLISH -> "Entries"
                                    AppLanguage.JAPANESE -> "出場券"
                                    else -> "출전권"
                                },
                                color = AqMuted,
                                fontSize = 10.sp,
                                lineHeight = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                            UnlocalizedText(
                                text = arenaTicketValueLabel(
                                    entriesRemaining = entriesRemaining,
                                    unlimitedEntries = unlimitedEntries,
                                    language = language,
                                ),
                                color = if (entriesRemaining <= 0 && !unlimitedEntries) AqGold else BattleGreen,
                                fontSize = if (unlimitedEntries) 16.sp else 22.sp,
                                lineHeight = 26.sp,
                                fontWeight = FontWeight.Black,
                            )
                            MaterialText(
                                text = arenaTicketStatusLabel(
                                    entriesRemaining = entriesRemaining,
                                    unlimitedEntries = unlimitedEntries,
                                    entryRecoveryCountdown = entryRecoveryCountdown,
                                    language = language,
                                    dailyLimitReached = dailyLimitReached,
                                    dailyResetCountdown = dailyResetCountdown,
                                ),
                                color = AqMuted,
                                fontSize = 9.sp,
                                lineHeight = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                            Spacer(Modifier.height(2.dp))
                            LinearProgressIndicator(
                                progress = { ticketRecoveryProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(99.dp))
                                    .testTag("battle-entry-recovery-progress")
                                    .semantics {
                                        contentDescription = buildString {
                                            append(
                                                arenaTicketValueLabel(
                                                    entriesRemaining = entriesRemaining,
                                                    unlimitedEntries = unlimitedEntries,
                                                    language = language,
                                                ),
                                            )
                                            append(" · ")
                                            append(
                                                arenaTicketStatusLabel(
                                                    entriesRemaining = entriesRemaining,
                                                    unlimitedEntries = unlimitedEntries,
                                                    entryRecoveryCountdown = entryRecoveryCountdown,
                                                    language = language,
                                                    dailyLimitReached = dailyLimitReached,
                                                    dailyResetCountdown = dailyResetCountdown,
                                                ),
                                            )
                                        }
                                        progressBarRangeInfo = ProgressBarRangeInfo(
                                            current = ticketRecoveryProgress,
                                            range = 0f..1f,
                                        )
                                    },
                                color = if (entriesRemaining <= 0 && !unlimitedEntries) {
                                    AqGold
                                } else {
                                    BattleGreen
                                },
                                trackColor = AqSurfaceHigh,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                BattleEntryCard(
                    entriesRemaining = entriesRemaining,
                    unlimitedEntries = unlimitedEntries,
                    flowStep = state?.flowStep(),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    onParticipate = onParticipate,
                    onStartBattle = onStartBattle,
                    onRefillTickets = onRefillTickets,
                    entryAllowed = entryAllowed,
                    arenaUnlocked = skillTreeUi.unlocked,
                    hasAttackSkill = loadout.hasAttackSkill,
                    availablePoints = loadout.availablePoints,
                    rewardedRefillAvailable = rewardedRefillAvailable,
                    rewardedRefillCount = rewardedRefillCount,
                    rewardedRefillLimitReached = rewardedRefillLimitReached,
                    dailyLimitReached = dailyLimitReached,
                    onOpenSkillTree = onOpenSkillTree,
                )
            }
        }
    }
}

@Composable
private fun BattleDedicatedPage(
    state: BattleOverlayState,
    placementCompleted: Int,
    backgroundResourceId: Int,
    modifier: Modifier,
    onAdvance: () -> Unit,
    onComplete: () -> Unit,
    runtime: ArenaPanelRuntime,
) {
    if (state.result.supportBattle != null) {
        val narrative = when (state) {
            is BattleOverlayState.Playing -> state.narrative
            is BattleOverlayState.Result -> state.narrative
            else -> return
        }
        ArenaLiveBattleContent(
            result = state.result,
            backgroundResourceId = backgroundResourceId,
            finished = state is BattleOverlayState.Result,
            playbackPosition = runtime.playbackPosition,
            playbackElapsed = runtime.playbackElapsed,
            onFinished = onAdvance,
            resultContent = {
                BattleResultArenaStage(state.result, narrative, placementCompleted, backgroundResourceId, onComplete)
            },
            modifier = modifier,
        )
        return
    }
    val beats = when (state) {
        is BattleOverlayState.Playing -> state.playbackPlan.sequence
        is BattleOverlayState.Result -> state.playbackPlan.sequence
        else -> emptyList()
    }
    val completedBeatCount = when (state) {
        is BattleOverlayState.Playing -> state.playbackIndex.coerceIn(0, beats.size)
        is BattleOverlayState.Result -> beats.size
        else -> 0
    }
    val lines = remember(beats, completedBeatCount) {
        beats.take(completedBeatCount)
            .filter { beat -> beat.kind !in setOf(BattlePlaybackBeatKind.OPENING, BattlePlaybackBeatKind.OPENING_TRAIT) }
            .map(BattlePlaybackBeat::text)
            .filter(String::isNotBlank)
    }
    Column(modifier = modifier.background(AqBackground)) {
        AnimatedContent(
            targetState = state,
            transitionSpec = {
                fadeIn(tween(BATTLE_RESULT_ENTER_MILLIS)).togetherWith(
                    fadeOut(tween(BATTLE_RESULT_EXIT_MILLIS)),
                )
            },
            contentKey = { it is BattleOverlayState.Result },
            label = "battle-result-transition",
        ) { stageState ->
            when (stageState) {
                is BattleOverlayState.Playing -> BattleNarrativeLineContent(
                    result = stageState.result,
                    narrative = stageState.narrative,
                    beats = stageState.playbackPlan.sequence,
                    playbackIndex = stageState.playbackIndex,
                    backgroundResourceId = backgroundResourceId,
                    onAdvance = onAdvance,
                )
                is BattleOverlayState.Result -> BattleResultArenaStage(
                    result = stageState.result,
                    narrative = stageState.narrative,
                    placementCompleted = placementCompleted,
                    backgroundResourceId = backgroundResourceId,
                    onComplete = onComplete,
                )
                else -> Unit
            }
        }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            colors = CardDefaults.cardColors(containerColor = AqSurface),
            shape = RoundedCornerShape(22.dp),
        ) {
            BattleNarrativeLog(
                result = state.result,
                lines = lines,
                preparing = false,
                completed = state is BattleOverlayState.Result,
            )
        }
    }
}

@Composable
private fun BattleResultArenaStage(
    result: BattlePreviewResult,
    narrative: BattleQaNarrative,
    placementCompleted: Int,
    backgroundResourceId: Int,
    onComplete: () -> Unit,
) {
    val language = LocalAppLanguage.current
    val resultLabel = arenaBattleResultLabel(result.outcome, language)
    val resultColor = when (result.outcome) {
        BattleOutcome.USER_WIN -> BattleGreen
        BattleOutcome.USER_LOSS -> AqRed
        BattleOutcome.DRAW -> BattleBlue
    }
    val completed = maxOf(placementCompleted, result.standingAfter.completedBattles)
    Card(
        modifier = Modifier.fillMaxWidth().height(BATTLE_MENU_STAGE_HEIGHT_DP.dp).padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(22.dp),
    ) {
        Box(Modifier.fillMaxSize().border(1.dp, resultColor.copy(alpha = 0.75f), RoundedCornerShape(22.dp))) {
            Image(
                painter = painterResource(backgroundResourceId),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(
                        listOf(Color(0xE8161020), Color(0xF21A1222), Color(0xFA15101B)),
                    ),
                ),
            )
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    UnlocalizedText(
                        text = resultLabel,
                        color = resultColor,
                        fontSize = 25.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        UnlocalizedText(arenaBattlePointTitle(language), color = AqMuted, fontSize = 12.sp)
                        UnlocalizedText(
                            battleSettledPointDeltaDisplay(result.pointDelta),
                            color = resultColor,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Black,
                        )
                    }
                }
                UnlocalizedText(
                    text = "${result.userName}  VS  ${result.opponentName}",
                    color = BattleNeutral,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    BattleResultMetric(
                        modifier = Modifier.weight(1f),
                        label = arenaBattleSeasonScoreTitle(language),
                        value = battleScoreDisplay(result.standingAfter.score, completed, language),
                        accent = resultColor,
                    )
                    BattleResultMetric(
                        modifier = Modifier.weight(1f),
                        label = arenaBattleSeasonRecordTitle(language),
                        value = battleArenaRecordLabel(
                            result.standingAfter.wins,
                            result.standingAfter.losses,
                            result.standingAfter.draws,
                            language,
                        ),
                        accent = AqText,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onComplete,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = resultColor,
                        contentColor = Color(0xFF17151B),
                    ),
                    shape = RoundedCornerShape(13.dp),
                ) {
                    MaterialText(
                        text = when (LocalAppLanguage.current) {
                            AppLanguage.ENGLISH -> "Done"
                            AppLanguage.JAPANESE -> "完了"
                            else -> "완료"
                        },
                        fontSize = BATTLE_ENTRY_BUTTON_FONT_SIZE_SP.sp,
                        lineHeight = BATTLE_ENTRY_BUTTON_LINE_HEIGHT_SP.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun BattleStatusStage(
    backgroundResourceId: Int,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().height(218.dp).padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(22.dp),
    ) {
        Box(Modifier.fillMaxSize().border(1.dp, AqGoldSoft, RoundedCornerShape(22.dp))) {
            Image(
                painter = painterResource(backgroundResourceId),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(Color(0xD8161020), Color(0xE51A1222), Color(0xF515101B)),
                    ),
                ),
            )
            Column(
                modifier = Modifier.fillMaxSize().padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                content = content,
            )
        }
    }
}

@Composable
private fun BattleNarrativeLog(
    result: BattlePreviewResult,
    lines: List<String>,
    preparing: Boolean,
    completed: Boolean = false,
) {
    val language = LocalAppLanguage.current
    val entries = remember(lines) { battleLogEntries(lines) }
    val listState = rememberLazyListState()
    var previousEntryCount by remember { mutableIntStateOf(entries.size) }
    LaunchedEffect(entries.firstOrNull()?.ordinal, completed) {
        // LazyColumn preserves the old first item's key when a newer event is prepended.
        // Follow only when the reader was at the latest event; preserve manual browsing.
        val insertedCount = (entries.size - previousEntryCount).coerceAtLeast(0)
        val wasAtLatest = listState.firstVisibleItemIndex <= insertedCount &&
            listState.firstVisibleItemScrollOffset == 0
        if (entries.isNotEmpty() && (completed || (!listState.isScrollInProgress && wasAtLatest))) {
            listState.scrollToItem(0)
        }
        previousEntryCount = entries.size
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "battle-history-heading") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Timeline, contentDescription = null, tint = AqGold, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                UnlocalizedText(
                    arenaLiveBattleLogTitle(language),
                    color = AqText,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Black,
                )
            }
        }
        if (preparing) {
            item {
                UnlocalizedText(
                    arenaNarrativePreparingLabel(language),
                    color = AqMuted,
                    fontSize = 12.sp,
                )
            }
        }
        items(entries, key = BattleLogEntry::ordinal) { entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1B1424))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = "${entry.ordinal}",
                    color = AqGold,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                )
                Spacer(Modifier.width(9.dp))
                BattleNarrativeRichText(
                    text = entry.text,
                    result = result,
                    modifier = Modifier.weight(1f),
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    maxLines = Int.MAX_VALUE,
                )
            }
        }
    }
}

@Composable
private fun BattleHome(
    placementCompleted: Int,
    score: Int,
    history: List<BattlePreviewHistory>,
    onSelectHistory: (BattlePreviewHistory) -> Unit,
    onOpenRanking: () -> Unit,
    heroPathEntry: HeroPathArenaEntryUiModel,
    heroPathEditingEnabled: Boolean,
    onOpenHeroPath: () -> Unit,
    onOpenSupportPreview: (() -> Unit)?,
    skillTreeUi: ArenaSkillTreeUiModel,
    onOpenSkillTree: () -> Unit,
) {
    val language = LocalAppLanguage.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (onOpenSupportPreview != null) {
            item(key = "arena-support-preview") {
                BattleSupportPreviewEntry(onClick = onOpenSupportPreview)
            }
        }
        item(key = "arena-growth") {
            ArenaSkillTreeEntryCard(model = skillTreeUi, onOpen = onOpenSkillTree)
        }
        item {
            val rankingDetail = battleRankingEntryDetail(score, placementCompleted, language)
            RankingEntryMenuButton(
                title = "결투장 랭킹",
                detail = rankingDetail,
                detailHighlighted = battleCompetitiveDataVisible(placementCompleted),
                accessibilityLabel = arenaRankingEntryAccessibilityLabel(rankingDetail, language),
                onClick = onOpenRanking,
            )
        }
        item { BattleRecentHistoryHeader(history.size) }
        if (history.isEmpty()) {
            item { BattleRecentHistoryEmpty() }
        } else {
            items(
                items = recentBattleHistory(history),
                key = BattlePreviewHistory::battleId,
            ) { battle ->
                BattleHistoryRow(battle = battle, onSelect = onSelectHistory)
            }
        }
    }
}

@Composable
private fun BattleSupportPreviewEntry(onClick: () -> Unit) {
    val (title, detail, action) = when (LocalAppLanguage.current) {
        AppLanguage.KOREAN -> Triple("새 전투 체험", "테스트 캐릭터 · 점수 미반영", "체험")
        AppLanguage.ENGLISH -> Triple("New battle preview", "Test fighters · No rating changes", "Try")
        AppLanguage.JAPANESE -> Triple("新バトル体験", "テストキャラ・スコア反映なし", "体験")
    }
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).semantics(mergeDescendants = true) {
            contentDescription = "$title. $detail. $action"
        },
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AqGold.copy(alpha = 0.12f), contentColor = AqText),
        border = BorderStroke(1.dp, AqGoldSoft),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Icon(Icons.Filled.Shield, contentDescription = null, tint = AqGold, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            MaterialText(title, color = AqText, fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.Black)
            MaterialText(detail, color = AqMuted, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Spacer(Modifier.width(8.dp))
        MaterialText(action, color = AqGold, fontSize = 12.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.width(4.dp))
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = AqGold, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun BattleRecentHistoryHeader(historySize: Int) {
    val language = LocalAppLanguage.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Timeline,
            contentDescription = null,
            tint = BattleBlue,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        UnlocalizedText(
            text = arenaRecentHistoryTitle(language),
            color = AqText,
            fontSize = 15.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.weight(1f))
        UnlocalizedText(
            text = arenaRecentHistoryCountLabel(historySize, language),
            color = AqMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun BattleRecentHistoryEmpty() {
    UnlocalizedText(
        text = arenaRecentHistoryEmptyLabel(LocalAppLanguage.current),
        color = AqMuted,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1B1424))
            .padding(horizontal = 12.dp, vertical = 18.dp),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun BattleMetric(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    accent: Color,
    onClick: (() -> Unit)? = null,
    accessibilityLabel: String? = null,
) {
    val interactionModifier = if (onClick == null) {
        Modifier
    } else {
        Modifier
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                accessibilityLabel?.let { contentDescription = it }
            }
    }
    Column(
        modifier = modifier
            .width(72.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1B1424))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .then(interactionModifier)
            .padding(horizontal = 8.dp, vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = AqMuted, fontSize = 10.sp, maxLines = 1)
        MaterialText(value, color = accent, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun BattleScoreNotice() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(AqGold.copy(alpha = 0.08f))
            .border(1.dp, AqGoldSoft.copy(alpha = 0.55f), RoundedCornerShape(13.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = AqGold,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = battleUserOnlyScoreNotice(),
                color = AqText,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "승패와 양쪽 점수 차이만 반영하며 연승·잔여 체력 보너스는 없습니다.",
                color = AqMuted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
    }
}

@Composable
private fun BattleOverviewActions(
    onOpenHistory: () -> Unit,
    onOpenRanking: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = onOpenHistory,
            modifier = Modifier.weight(1f).height(46.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = BattleBlue),
        ) {
            Icon(Icons.Filled.Timeline, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text("전적", fontSize = 13.sp, fontWeight = FontWeight.Black)
        }
        OutlinedButton(
            onClick = onOpenRanking,
            modifier = Modifier.weight(1f).height(46.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = AqGold),
        ) {
            Icon(Icons.Filled.EmojiEvents, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text("랭킹", fontSize = 13.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun BattleStanceSelector(
    stance: BattleStance,
    enabled: Boolean,
    onStanceChange: (BattleStance) -> Unit,
) {
    Column {
        Text(
            text = "지침",
            color = AqText,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(7.dp))
        Row(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            BattleStance.entries.forEach { item ->
                val selected = stance == item
                val icon = when (item) {
                    BattleStance.ASSAULT -> Icons.Filled.Bolt
                    BattleStance.BALANCED -> Icons.Filled.Timeline
                    BattleStance.GUARD -> Icons.Filled.Shield
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) BattleCrimson.copy(alpha = 0.17f) else Color(0xFF1B1424))
                        .border(
                            1.dp,
                            if (selected) BattleCrimson else Color(0xFF46394F),
                            RoundedCornerShape(12.dp),
                        )
                        .selectable(
                            selected = selected,
                            enabled = enabled,
                            role = Role.RadioButton,
                            onClick = { onStanceChange(item) },
                        )
                        .semantics {
                            this.selected = selected
                            contentDescription = localized("${item.label}. ${item.summary}")
                        }
                        .padding(horizontal = 6.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (selected) AqGold else AqMuted,
                        modifier = Modifier.size(19.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = item.label,
                        color = if (selected) AqGold else AqMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun BattleQaEnvironmentCard(
    timeControlEnabled: Boolean,
    enabled: Boolean,
    onAdvanceConditionHour: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BattleBlue.copy(alpha = 0.08f))
            .border(1.dp, BattleBlue.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = BattleBlue,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "QA",
            color = BattleBlue,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(34.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(BattleBlue.copy(alpha = 0.2f))
                .border(1.dp, BattleBlue, RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "긴 서사 · 문장별 재생",
                color = BattleBlue,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        if (timeControlEnabled) {
            Spacer(Modifier.width(6.dp))
            OutlinedButton(
                onClick = onAdvanceConditionHour,
                enabled = enabled,
                modifier = Modifier.width(58.dp).height(34.dp),
                shape = RoundedCornerShape(9.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = BattleBlue),
            ) {
                Text("+1시간", fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun BattleEntryCard(
    entriesRemaining: Int,
    unlimitedEntries: Boolean,
    flowStep: BattleFlowStep?,
    modifier: Modifier = Modifier,
    onParticipate: () -> Unit,
    onStartBattle: () -> Unit,
    onRefillTickets: () -> Unit,
    entryAllowed: Boolean = true,
    arenaUnlocked: Boolean = true,
    hasAttackSkill: Boolean = true,
    availablePoints: Int = 0,
    rewardedRefillAvailable: Boolean = false,
    rewardedRefillCount: Int = BATTLE_ENTRY_CAPACITY,
    rewardedRefillLimitReached: Boolean = false,
    dailyLimitReached: Boolean = false,
    onOpenSkillTree: () -> Unit = {},
) {
    val language = LocalAppLanguage.current
    val action = battleEntryPrimaryAction(
        entriesRemaining = entriesRemaining,
        step = flowStep,
        unlimitedEntries = unlimitedEntries,
        entryAllowed = entryAllowed,
        hasAttackSkill = hasAttackSkill,
        availablePoints = availablePoints,
        rewardedRefillAvailable = rewardedRefillAvailable,
        dailyLimitReached = dailyLimitReached,
    )
    val enabled = action != BattleEntryPrimaryAction.NONE
    val label = battleEntryButtonLabel(
        entriesRemaining = entriesRemaining,
        step = flowStep,
        unlimitedEntries = unlimitedEntries,
        arenaUnlocked = arenaUnlocked,
        entryAllowed = entryAllowed,
        hasAttackSkill = hasAttackSkill,
        availablePoints = availablePoints,
        rewardedRefillAvailable = rewardedRefillAvailable,
        rewardedRefillCount = rewardedRefillCount,
                    rewardedRefillLimitReached = rewardedRefillLimitReached,
        dailyLimitReached = dailyLimitReached,
        language = language,
    )
    Button(
        onClick = {
            when (action) {
                BattleEntryPrimaryAction.OPEN_SKILL_TREE -> onOpenSkillTree()
                BattleEntryPrimaryAction.FIND_OPPONENT -> onParticipate()
                BattleEntryPrimaryAction.START_BATTLE -> onStartBattle()
                BattleEntryPrimaryAction.REFILL_TICKETS -> onRefillTickets()
                BattleEntryPrimaryAction.NONE -> Unit
            }
        },
        enabled = enabled,
        modifier = modifier.testTag("battle-primary-action"),
        colors = ButtonDefaults.buttonColors(
            containerColor = AqGold,
            contentColor = AqBackground,
            disabledContainerColor = AqSurfaceHigh,
            disabledContentColor = AqMuted,
        ),
        shape = RoundedCornerShape(13.dp),
    ) {
        MaterialText(
            text = label,
            fontSize = BATTLE_ENTRY_BUTTON_FONT_SIZE_SP.sp,
            lineHeight = BATTLE_ENTRY_BUTTON_LINE_HEIGHT_SP.sp,
            fontWeight = FontWeight.Black,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun BattleMatchReadyContent(expiresAtMillis: Long) {
    val language = LocalAppLanguage.current
    var nowMillis by remember(expiresAtMillis) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(expiresAtMillis) {
        while (nowMillis < expiresAtMillis) {
            delay(1_000L)
            nowMillis = System.currentTimeMillis()
        }
    }
    val countdownLabel = battleMatchReadyCountdownLabel(expiresAtMillis, nowMillis, language)
    Column(
        modifier = Modifier.semantics {
            contentDescription = battleMatchReadyAccessibilityLabel(countdownLabel, language)
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.Shield,
            contentDescription = null,
            tint = AqGold,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(3.dp))
        UnlocalizedText(
            battleMatchReadyTitle(language),
            color = AqText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Black,
        )
        UnlocalizedText(countdownLabel, color = AqMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

private fun battleNarrativeAnnotatedText(
    text: String,
    result: BattlePreviewResult,
    language: com.nullplaying.localization.AppLanguage,
): AnnotatedString {
    val skills = (result.battle.user.skills + result.battle.opponent.skills).map {
        it.copy(displayName = localized(it.displayName, language))
    }
    val equipment = (result.battle.user.equipment + result.battle.opponent.equipment).map {
        it.copy(displayName = localizedEquipmentName(it.displayName, language))
    }
    val traits = (result.battle.user.activeTraitIds + result.battle.opponent.activeTraitIds)
        .distinct()
        .mapNotNull(BattleTraitCatalog.byId::get)
        .map { it.copy(nameKo = localized(it.nameKo, language)) }
    val skillById = skills.associateBy(BattleSkillSnapshot::skillId)
    val traitById = traits.associateBy(BattleTraitDefinition::id)
    val spans = battleNarrativeSpans(
        text = text,
        userName = result.userName,
        opponentName = result.opponentName,
        skills = skills,
        equipment = equipment,
        traits = traits,
    )
    return buildAnnotatedString {
        append(text)
        spans.forEach { span ->
            val accent = when (span.role) {
                BattleNarrativeRole.USER -> BattleBlue
                BattleNarrativeRole.OPPONENT -> AqRed
                BattleNarrativeRole.SKILL -> skillById[span.stableId]?.let(::battleSkillAccent) ?: AqText
                BattleNarrativeRole.ITEM -> rarityColor(span.rarity)
                BattleNarrativeRole.TRAIT -> traitById[span.stableId]?.let(::battleTraitAccent) ?: AqText
            }
            addStyle(
                style = SpanStyle(color = accent, fontWeight = FontWeight.Bold),
                start = span.start,
                end = span.endExclusive,
            )
        }
    }
}

@Composable
private fun BattleNarrativeRichText(
    text: String,
    result: BattlePreviewResult,
    modifier: Modifier = Modifier,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
) {
    val language = LocalAppLanguage.current
    val annotated = remember(text, result.battle, language) {
        battleNarrativeAnnotatedText(text, result, language)
    }
    MaterialText(
        text = annotated,
        modifier = modifier,
        color = AqText,
        fontSize = fontSize,
        lineHeight = lineHeight,
        fontWeight = fontWeight,
        maxLines = maxLines,
        overflow = overflow,
        textAlign = textAlign,
    )
}

internal fun battleDuelGaugeEnergyFraction(
    energy: Float,
    maxEnergy: Float = ProjectionBattleEngine.PROJECTION_MAX_HP.toFloat(),
): Float = (energy.coerceAtLeast(0f) / maxEnergy.coerceAtLeast(1f)).coerceIn(0f, 1f)

internal fun battleDuelGaugeEnergyFractionAtProgress(
    energyBefore: Int,
    energyAfter: Int,
    maxEnergy: Int,
    progress: Float,
): Float {
    val beforeFraction = battleDuelGaugeEnergyFraction(
        energy = energyBefore.toFloat(),
        maxEnergy = maxEnergy.toFloat(),
    )
    val afterFraction = battleDuelGaugeEnergyFraction(
        energy = energyAfter.toFloat(),
        maxEnergy = maxEnergy.toFloat(),
    )
    val movementProgress = progress.coerceIn(0f, 1f)
    return beforeFraction + (afterFraction - beforeFraction) * movementProgress
}

@Composable
private fun BattleDuelFighterIdentity(
    name: String,
    heroClass: BattleHeroClass,
    level: Long,
    rightSide: Boolean,
    modifier: Modifier = Modifier,
) {
    val language = LocalAppLanguage.current
    Column(
        modifier = modifier,
        horizontalAlignment = if (rightSide) Alignment.End else Alignment.Start,
    ) {
        UnlocalizedText(
            text = name,
            color = AqText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        UnlocalizedText(
            text = battleHeroClassLevelLabel(heroClass, level, language),
            color = BattleNeutral,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun BattleDuelEnergyGauge(
    userName: String,
    opponentName: String,
    userEnergyFraction: Float,
    opponentEnergyFraction: Float,
    modifier: Modifier = Modifier,
) {
    val language = LocalAppLanguage.current
    val safeUserFraction = userEnergyFraction.coerceIn(0f, 1f)
    val safeOpponentFraction = opponentEnergyFraction.coerceIn(0f, 1f)
    Row(
        modifier = modifier
            .height(4.dp)
            .clip(RoundedCornerShape(99.dp))
            .border(0.5.dp, AqGoldSoft.copy(alpha = 0.75f), RoundedCornerShape(99.dp))
            .background(Color(0xFF2A2431))
            .semantics {
                contentDescription = arenaBattleEnergyAccessibilityLabel(userName, opponentName, language)
                stateDescription = "$userName ${kotlin.math.round(safeUserFraction * 100f).toInt()}%, " +
                    "$opponentName ${kotlin.math.round(safeOpponentFraction * 100f).toInt()}%"
            },
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(safeUserFraction)
                    .fillMaxHeight()
                    .align(Alignment.CenterEnd)
                    .background(Brush.horizontalGradient(listOf(Color(0xFF2258A9), BattleBlue))),
            )
        }
        Box(
            Modifier
            .width(3.dp)
            .fillMaxHeight()
            .background(AqText),
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(safeOpponentFraction)
                    .fillMaxHeight()
                    .align(Alignment.CenterStart)
                    .background(Brush.horizontalGradient(listOf(BattleCrimson, Color(0xFF8C243A)))),
            )
        }
    }
}

@Composable
private fun BattleNarrativeLineContent(
    result: BattlePreviewResult,
    narrative: BattleQaNarrative,
    beats: List<BattlePlaybackBeat>,
    playbackIndex: Int,
    backgroundResourceId: Int,
    onAdvance: () -> Unit,
) {
    val narrativeLanguage = AppLanguage.fromLanguageTag(narrative.languageTag)
        ?: LocalAppLanguage.current
    val safeIndex = playbackIndex.coerceIn(0, beats.lastIndex)
    val beat = beats[safeIndex]
    val isOpening = beat.kind == BattlePlaybackBeatKind.OPENING
    val isTrait = beat.kind in setOf(
        BattlePlaybackBeatKind.OPENING_TRAIT,
        BattlePlaybackBeatKind.MID_BATTLE_TRAIT,
    )
    val isAction = beat.kind == BattlePlaybackBeatKind.ACTION
    val visibleLine = beat.text
    val narrativeFontSize = when {
        visibleLine.length >= 180 -> 10.sp
        visibleLine.length >= 130 -> 11.sp
        else -> 13.sp
    }
    val narrativeLineHeight = when {
        visibleLine.length >= 180 -> 14.sp
        visibleLine.length >= 130 -> 16.sp
        else -> 18.sp
    }
    val energyProgress = remember(narrative.battleId, playbackIndex) { Animatable(0f) }
    val arenaTintAlpha = remember(narrative.battleId, playbackIndex) {
        Animatable(battleArenaTintAlpha(beat.arenaTintSide, 0f))
    }
    val isFinal = isAction && safeIndex == beats.lastIndex
    val motionDurationMillis = beat.motionDurationMillis
    LaunchedEffect(narrative.battleId, playbackIndex) {
        energyProgress.snapTo(0f)
        arenaTintAlpha.snapTo(battleArenaTintAlpha(beat.arenaTintSide, 0f))
        if (!isAction) {
            energyProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = motionDurationMillis,
                    easing = LinearEasing,
                ),
            )
        } else {
            coroutineScope {
                launch {
                    energyProgress.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(
                            durationMillis = battleEnergyImpactDurationMillis(motionDurationMillis),
                            easing = LinearEasing,
                        ),
                    )
                }
                launch {
                    delay(BATTLE_ARENA_TINT_HOLD_MILLIS.toLong())
                    arenaTintAlpha.animateTo(
                        targetValue = 0f,
                        animationSpec = tween(
                            durationMillis = BATTLE_ARENA_TINT_FADE_MILLIS,
                            easing = LinearEasing,
                        ),
                    )
                }
            }
            delay(battlePostVisualEffectsDelayMillis(motionDurationMillis).toLong())
        }
        if (isFinal) {
            delay(BATTLE_FINAL_ENDPOINT_CONFIRM_MILLIS.toLong())
        }
        onAdvance()
    }
    val displayedUserEnergyFraction = battleDuelGaugeEnergyFractionAtProgress(
        energyBefore = beat.userEnergyBefore,
        energyAfter = beat.userEnergyAfter,
        maxEnergy = result.battle.user.maxHp,
        progress = energyProgress.value,
    )
    val displayedOpponentEnergyFraction = battleDuelGaugeEnergyFractionAtProgress(
        energyBefore = beat.opponentEnergyBefore,
        energyAfter = beat.opponentEnergyAfter,
        maxEnergy = result.battle.opponent.maxHp,
        progress = energyProgress.value,
    )
    val arenaTint = battleArenaTintColor(beat.arenaTintSide)
    Card(
        modifier = Modifier.fillMaxWidth().height(260.dp).padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(22.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(1.dp, AqGoldSoft, RoundedCornerShape(22.dp)),
        ) {
            Image(
                painter = painterResource(backgroundResourceId),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                    Brush.verticalGradient(
                        listOf(Color(0xB8120E19), Color(0xA8181220), Color(0xF015101B)),
                    ),
                ),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(arenaTint.copy(alpha = arenaTintAlpha.value)),
            )
            Column(
                modifier = Modifier.fillMaxSize(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(92.dp)
                        .background(Color(0xB817111F))
                        .border(1.dp, AqGoldSoft)
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.fillMaxWidth()) {
                            BattleDuelFighterIdentity(
                                name = result.userName,
                                heroClass = result.userClass,
                                level = result.userLevel,
                                rightSide = false,
                                modifier = Modifier.weight(1f),
                            )
                            BattleDuelFighterIdentity(
                                name = result.opponentName,
                                heroClass = result.opponentClass,
                                level = result.opponentLevel,
                                rightSide = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.height(7.dp))
                        BattleDuelEnergyGauge(
                            userName = result.userName,
                            opponentName = result.opponentName,
                            userEnergyFraction = displayedUserEnergyFraction,
                            opponentEnergyFraction = displayedOpponentEnergyFraction,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (visibleLine.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xB317111F), RoundedCornerShape(10.dp))
                                .border(0.8.dp, AqGoldSoft.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                                .semantics {
                                    contentDescription = arenaBattleProgressDescription(narrativeLanguage)
                                    stateDescription = arenaBattleProgressStateLabel(
                                        opening = isOpening,
                                        openingTrait = beat.kind == BattlePlaybackBeatKind.OPENING_TRAIT,
                                        trait = isTrait,
                                        lineIndex = safeIndex,
                                        lineCount = beats.size,
                                        language = narrativeLanguage,
                                    )
                                    progressBarRangeInfo = ProgressBarRangeInfo(energyProgress.value, 0f..1f)
                                }
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = when {
                                        isOpening -> battleOpeningLabel(narrative.languageTag)
                                        beat.kind == BattlePlaybackBeatKind.OPENING_TRAIT -> when (narrative.languageTag) {
                                            "en" -> "Talent ready"
                                            "ja" -> "特性準備"
                                            else -> "특성 준비"
                                        }
                                        isTrait -> battleTraitActivationLabel(narrative.languageTag)
                                        else -> arenaBattleMomentumLabel(
                                            side = beat.arenaTintSide,
                                            userName = result.userName,
                                            opponentName = result.opponentName,
                                            language = narrativeLanguage,
                                        )
                                    },
                                    color = AqGold,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black,
                                )
                                Spacer(Modifier.height(4.dp))
                                BattleNarrativeRichText(
                                    text = visibleLine,
                                    result = result,
                                    fontSize = narrativeFontSize,
                                    lineHeight = narrativeLineHeight,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 6,
                                    overflow = TextOverflow.Clip,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BattleResultMetric(
    modifier: Modifier,
    label: String,
    value: String,
    accent: Color,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF232129))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        UnlocalizedText(label, color = AqMuted, fontSize = 12.sp)
        Spacer(Modifier.height(2.dp))
        UnlocalizedText(value, color = accent, fontSize = 12.sp, fontWeight = FontWeight.Black, maxLines = 1)
    }
}

@Composable
private fun BattleQaNarrativeCard(
    state: BattleQaNarrativeUiState,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF17121F))
            .border(1.dp, BattleBlue.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        when (state) {
            BattleQaNarrativeUiState.Idle -> {
                Text("AI 장면 대기 중", color = AqMuted, fontSize = 11.sp)
            }

            is BattleQaNarrativeUiState.Loading -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = BattleBlue,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(9.dp))
                    Column {
                        Text("Qwen 장면 생성 중", color = BattleBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("전투 결과는 이미 확정되어 있습니다", color = AqMuted, fontSize = 10.sp)
                    }
                }
            }

            is BattleQaNarrativeUiState.Failed -> {
                Text("AI 장면 연결 실패", color = AqRed, fontSize = 12.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(4.dp))
                Text(state.message, color = AqMuted, fontSize = 10.sp, lineHeight = 15.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                    Text("장면 다시 생성", color = BattleBlue, fontWeight = FontWeight.Bold)
                }
            }

            is BattleQaNarrativeUiState.Ready -> {
                val narrative = state.narrative
                val playbackLines = battlePlaybackLines(narrative)
                val sourceLabel = if (narrative.source == "qwen" && narrative.modelValid) {
                    "Qwen 실응답"
                } else {
                    "로컬 안전 문구"
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "$sourceLabel · ${playbackLines.size}문장",
                            color = if (narrative.modelValid) BattleBlue else AqGold,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black,
                        )
                        Text(
                            text = "DB 접근 없음 · ${narrative.latencyMs}ms · " +
                                "토큰 ${narrative.usage.inputTokens}/${narrative.usage.outputTokens}",
                            color = AqMuted,
                            fontSize = 9.sp,
                            lineHeight = 14.sp,
                        )
                    }
                    Text(
                        text = "검증 통과",
                        color = BattleGreen,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(9.dp))
                playbackLines.forEachIndexed { index, line ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 9.dp),
                            color = Color(0xFF403548),
                        )
                    }
                    UnlocalizedText(
                        text = line,
                        color = AqText,
                        fontSize = 11.sp,
                        lineHeight = 17.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun BattleResultCard(
    result: BattlePreviewResult,
    placementCompleted: Int,
    qaNarrativeEnabled: Boolean,
    qaNarrativeState: BattleQaNarrativeUiState,
    onRetryQaNarrative: () -> Unit,
    onAcknowledge: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val language = LocalAppLanguage.current
    val scoreBefore = result.scoreBefore.coerceAtLeast(0)
    val scoreAfter = (scoreBefore.toLong() + result.pointDelta.toLong())
        .coerceIn(0L, Int.MAX_VALUE.toLong())
        .toInt()
    val competitiveDataVisible = battleCompetitiveDataVisible(placementCompleted)
    val resultColor = when (result.outcome) {
        BattleOutcome.USER_WIN -> BattleGreen
        BattleOutcome.USER_LOSS -> AqRed
        BattleOutcome.DRAW -> BattleBlue
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(resultColor.copy(alpha = 0.09f))
            .border(1.dp, resultColor.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    battleOfficialResultTitle(result.outcome, language),
                    color = resultColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Black,
                )
                UnlocalizedText(
                    text = battleResultOpponentMetaLabel(
                        opponentName = result.opponentName,
                        opponentScore = result.opponentScore,
                        competitiveDataVisible = competitiveDataVisible,
                        language = language,
                    ),
                    color = AqMuted,
                    fontSize = 11.sp,
                )
            }
            UnlocalizedText(
                text = battlePointDeltaDisplay(result.pointDelta, placementCompleted, language),
                color = resultColor,
                fontSize = if (competitiveDataVisible) 24.sp else 14.sp,
                fontWeight = FontWeight.Black,
            )
        }
        Spacer(Modifier.height(9.dp))
        if (competitiveDataVisible) {
            UnlocalizedText(
                text = "$scoreBefore → $scoreAfter",
                color = AqText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        } else {
            Text(
                text = "배치전이 끝나면 이 전투의 점수 변화가 공개됩니다.",
                color = AqText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(5.dp))
        UnlocalizedText(
            text = battleResultFighterMetaLabel(
                name = result.userName,
                heroClass = result.userClass,
                level = result.userLevel,
                power = result.userPower,
                opponent = false,
                language = language,
            ),
            color = AqText,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(2.dp))
        UnlocalizedText(
            text = battleResultFighterMetaLabel(
                name = result.opponentName,
                heroClass = result.opponentClass,
                level = result.opponentLevel,
                power = result.opponentPower,
                opponent = true,
                language = language,
            ),
            color = AqGold,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(5.dp))
        UnlocalizedText(
            text = battleGuidanceLabel(
                userName = result.userName,
                userStance = result.playerStance,
                opponentName = result.opponentName,
                opponentStance = result.opponentStance,
                language = language,
            ),
            color = AqText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        val lastRound = result.battle.rounds.lastOrNull()
        val decisiveMoment = if (lastRound == null) {
            localizedPreserving(
                result.decisiveMoment,
                result.userName,
                result.opponentName,
            )
        } else {
            battleDecisiveMomentLabel(
                outcome = result.outcome,
                rounds = result.battle.rounds.size,
                userName = result.userName,
                userHp = lastRound.userHpAfter,
                opponentName = result.opponentName,
                opponentHp = lastRound.opponentHpAfter,
                language = language,
            )
        }
        UnlocalizedText(decisiveMoment, color = AqMuted, fontSize = 12.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "결정론 엔진이 먼저 확정했으며 AI는 장면만 서술합니다.",
            color = AqMuted,
            fontSize = 10.sp,
        )
        if (qaNarrativeEnabled) {
            Spacer(Modifier.height(10.dp))
            BattleQaNarrativeCard(
                state = qaNarrativeState,
                onRetry = onRetryQaNarrative,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onOpenHistory) {
                Text("전적에서 보기", color = AqGold, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = onAcknowledge,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AqGold,
                    contentColor = AqBackground,
                ),
                shape = RoundedCornerShape(11.dp),
            ) {
                Text("확인하고 계속", fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
private fun BattleHistoryDialog(
    history: List<BattlePreviewHistory>,
    onSelect: (BattlePreviewHistory) -> Unit,
    onDismiss: () -> Unit,
) {
    val language = LocalAppLanguage.current
    BattlePopup(
        title = localized("전적", language),
        onDismiss = onDismiss,
    ) {
        BattleHistory(history = history, onSelect = onSelect)
    }
}

@Composable
internal fun BattleRankingScreen(
    localStanding: ArenaRankingLocalStanding,
    remoteSnapshot: RemoteArenaRankingSnapshot?,
    errorMessage: String?,
    refreshPolicy: RankingRefreshPolicy,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    val language = LocalAppLanguage.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AqBackground),
    ) {
        RankingPageTopBar(
            title = "결투장 랭킹",
            subtitle = rankingRefreshPeriodLabel(refreshPolicy, language),
            backContentDescription = "결투장 화면으로 돌아가기",
            onBack = onBack,
        )
        BattleRanking(
            localStanding = localStanding,
            remoteSnapshot = remoteSnapshot,
            errorMessage = errorMessage,
            onRetry = onRetry,
        )
    }
}

@Composable
private fun BattlePopup(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val language = LocalAppLanguage.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.82f)
                .padding(horizontal = 18.dp),
            colors = CardDefaults.cardColors(containerColor = AqSurface),
            shape = RoundedCornerShape(22.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    UnlocalizedText(title, color = AqText, fontSize = 18.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) {
                        UnlocalizedText(
                            arenaCloseLabel(language),
                            color = AqGold,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                HorizontalDivider(color = Color(0xFF46394F))
                Box(Modifier.fillMaxWidth().weight(1f)) { content() }
            }
        }
    }
}

@Composable
private fun BattleHistory(
    history: List<BattlePreviewHistory>,
    onSelect: (BattlePreviewHistory) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        if (history.isEmpty()) {
            item {
                Text(
                    text = "전적이 없습니다.",
                    color = AqMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        items(history) { battle -> BattleHistoryRow(battle = battle, onSelect = onSelect) }
    }
}

@Composable
private fun BattleHistoryRow(
    battle: BattlePreviewHistory,
    onSelect: (BattlePreviewHistory) -> Unit,
) {
    val language = LocalAppLanguage.current
    val storedOutcome = arenaHistoryOutcomeForPresentation(battle.resultLabel, battle.pointDelta)
    val won = storedOutcome == BattleOutcome.USER_WIN
    val draw = storedOutcome == BattleOutcome.DRAW
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1B1424))
            .clickable(role = Role.Button) { onSelect(battle) }
            .semantics { contentDescription = arenaHistoryAccessibilityLabel(battle.opponentName, language) }
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UnlocalizedText(
                text = arenaHistoryResultLabel(battle.resultLabel, language),
                color = if (draw) BattleBlue else if (won) BattleGreen else AqRed,
                fontSize = 13.sp,
                fontWeight = FontWeight.Black,
            )
            Spacer(Modifier.width(8.dp))
            UnlocalizedText(
                text = battle.opponentName,
                color = AqText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            UnlocalizedText(
                text = battleSettledPointDeltaDisplay(battle.pointDelta),
                color = if (draw) BattleBlue else if (won) BattleGreen else AqRed,
                fontSize = 14.sp,
                fontWeight = FontWeight.Black,
            )
        }
        Spacer(Modifier.height(4.dp))
        UnlocalizedText(
            text = arenaHistoryOpponentMetaLabel(
                storedClassLabel = battle.opponentClass,
                level = battle.opponentLevel,
                source = battle.opponentSource,
                language = language,
            ),
            color = AqMuted,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun BattleHistoryDetailDialog(
    history: BattlePreviewHistory,
    onDismiss: () -> Unit,
) {
    val language = LocalAppLanguage.current
    val localizedContent = remember(history, language) {
        battleHistoryLocalizedContent(history, language)
    }
    BattlePopup(
        title = arenaHistoryDetailTitle(history.opponentName, history.resultLabel, language),
        onDismiss = onDismiss,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column {
                    UnlocalizedText(
                        battleNarrativeSourceLabel(
                            history.narrativeSource,
                            history.narrativeModelValid,
                            language,
                        ),
                        color = if (history.narrativeModelValid) BattleBlue else AqGold,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    UnlocalizedText(localizedContent.summary, color = AqMuted, fontSize = 11.sp, lineHeight = 17.sp)
                }
            }
            items(battleLogEntries(localizedContent.narrativeLines), key = BattleLogEntry::ordinal) { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1B1424))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text("${entry.ordinal}", color = AqGold, fontSize = 12.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.width(9.dp))
                    MaterialText(
                        text = battleHistoryAnnotatedText(entry.text, history, localizedContent),
                        modifier = Modifier.weight(1f),
                        color = AqText,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                    )
                }
            }
        }
    }
}

private fun battleHistoryAnnotatedText(
    text: String,
    history: BattlePreviewHistory,
    localizedContent: BattleHistoryLocalizedContent,
): AnnotatedString = buildAnnotatedString {
    append(text)
    val candidates = buildList {
        add(history.userName to BattleBlue)
        add(history.opponentName to AqRed)
        localizedContent.skillNames.forEach { add(it to BattleSkillAccent) }
        localizedContent.traitNames.forEach { add(it to BattleTraitAccent) }
        localizedContent.equipment.forEach { add(it.name to rarityColor(it.rarity)) }
    }.filter { it.first.isNotBlank() }.sortedByDescending { it.first.length }
    val occupied = BooleanArray(text.length)
    candidates.forEach { (value, color) ->
        var start = text.indexOf(value)
        while (start >= 0) {
            val end = start + value.length
            if ((start until end).none { occupied[it] }) {
                (start until end).forEach { occupied[it] = true }
                addStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold), start, end)
            }
            start = text.indexOf(value, end)
        }
    }
}

@Composable
private fun BattleRanking(
    localStanding: ArenaRankingLocalStanding,
    remoteSnapshot: RemoteArenaRankingSnapshot?,
    errorMessage: String?,
    onRetry: () -> Unit,
) {
    if (!battleCompetitiveDataVisible(localStanding.completedBattles)) {
        BattleRankingPlacementGate(localStanding.completedBattles)
        return
    }
    val display = remoteSnapshot?.let { buildArenaRankingDisplaySnapshot(it, localStanding) }
    if (display == null) {
        BattleRankingUnavailable(
            errorMessage = errorMessage,
            loading = remoteSnapshot == null && errorMessage == null,
            onRetry = onRetry,
        )
        return
    }
    if (display.totalParticipants == 0 && display.entries.isEmpty() && display.myEntry == null) {
        BattleRankingEmptyState()
        return
    }
    RankingListContent(
        entryKeys = display.entries.map { "${it.accountId}:${it.characterId}" },
        currentPlayerKey = display.myEntry?.let { "${it.accountId}:${it.characterId}" },
        totalParticipants = display.totalParticipants,
        personalCard = display.myEntry?.let { mine -> { BattleRankingSelfRow(mine) } },
    ) { index -> BattleRankRow(display.entries[index]) }
}

@Composable
private fun BattleRankingUnavailable(
    errorMessage: String?,
    loading: Boolean,
    onRetry: () -> Unit,
) {
    val language = LocalAppLanguage.current
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (loading) {
            CircularProgressIndicator(color = AqGold, modifier = Modifier.size(30.dp))
            Spacer(Modifier.height(12.dp))
            UnlocalizedText(
                arenaRankingLoadingLabel(language),
                color = AqMuted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        } else {
            UnlocalizedText(
                arenaRankingLoadFailureLabel(language),
                color = AqText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            if (!errorMessage.isNullOrBlank()) {
                Spacer(Modifier.height(5.dp))
                UnlocalizedText(
                    arenaRankingErrorDetailLabel(language),
                    color = AqMuted,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onRetry) {
                UnlocalizedText(arenaRankingRetryLabel(language), color = AqGold)
            }
        }
    }
}

@Composable
private fun BattleRankingEmptyState() {
    val language = LocalAppLanguage.current
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.EmojiEvents,
            contentDescription = null,
            tint = AqGold,
            modifier = Modifier.size(34.dp),
        )
        Spacer(Modifier.height(10.dp))
        UnlocalizedText(
            text = arenaRankingEmptyTitle(language),
            modifier = Modifier.semantics { heading() },
            color = AqText,
            fontSize = 17.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun BattleRankingSelfRow(mine: RemoteArenaRankingEntry) {
    val language = LocalAppLanguage.current
    RankingPersonalCard(
        displayName = mine.displayName,
        rankLabel = if (mine.rank >= OUTSIDE_DISPLAYED_ARENA_RANK) {
            battleArenaRankLabel(mine.rank, language)
        } else if (mine.rank > 0) "#${rankingNumber(mine.rank.toLong())}" else "—",
        compactRank = mine.rank >= OUTSIDE_DISPLAYED_ARENA_RANK,
        accent = battleArenaScoreLabel(mine.score, language),
        detail = "${arenaTreeClassName(mine.heroClass, language)} · Lv.${rankingNumber(mine.level)}",
        accessibility = "${mine.displayName}, ${battleArenaRankLabel(mine.rank, language)}, " +
            battleArenaScoreLabel(mine.score, language),
    )
}

@Composable
private fun BattleRankingPlacementGate(placementCompleted: Int) {
    val language = LocalAppLanguage.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.EmojiEvents,
            contentDescription = null,
            tint = AqGold,
            modifier = Modifier.size(34.dp),
        )
        Spacer(Modifier.height(10.dp))
        UnlocalizedText(
            text = arenaRankingPlacementTitle(language),
            color = AqText,
            fontSize = 17.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(5.dp))
        UnlocalizedText(
            text = battlePlacementLabel(placementCompleted),
            color = AqGold,
            fontSize = 24.sp,
            fontWeight = FontWeight.Black,
        )
    }
}

@Composable
private fun BattleRankRow(entry: RemoteArenaRankingEntry) {
    val language = LocalAppLanguage.current
    RankingPlayerRow(
        rank = entry.rank,
        displayName = entry.displayName,
        detail = "${arenaTreeClassName(entry.heroClass, language)} · Lv.${rankingNumber(entry.level)}",
        metricLabel = when (language) {
            AppLanguage.KOREAN -> "시즌 점수"
            AppLanguage.ENGLISH -> "Season score"
            AppLanguage.JAPANESE -> "シーズンスコア"
        },
        metricValue = rankingNumber(entry.score.coerceAtLeast(0).toLong()),
        isMe = entry.isMe,
        accessibility = "${battleArenaRankLabel(entry.rank, language)}, ${entry.displayName}, " +
            battleArenaScoreLabel(entry.score, language),
    )
}

internal fun battleArenaRankLabel(rank: Int, language: AppLanguage): String = when (language) {
    AppLanguage.KOREAN -> if (rank >= OUTSIDE_DISPLAYED_ARENA_RANK) "1000위 밖" else "${rank}위"
    AppLanguage.ENGLISH -> if (rank >= OUTSIDE_DISPLAYED_ARENA_RANK) "Outside 1000" else "#$rank"
    AppLanguage.JAPANESE -> if (rank >= OUTSIDE_DISPLAYED_ARENA_RANK) "1000位圏外" else "${rank}位"
}

internal fun battleArenaRecordLabel(
    wins: Int,
    losses: Int,
    draws: Int,
    language: AppLanguage,
): String = when (language) {
    AppLanguage.KOREAN -> "${wins}승 ${losses}패 ${draws}무"
    AppLanguage.ENGLISH -> "$wins W $losses L $draws D"
    AppLanguage.JAPANESE -> "${wins}勝 ${losses}敗 ${draws}分"
}

internal fun battleArenaScoreLabel(score: Int, language: AppLanguage): String = when (language) {
    AppLanguage.KOREAN -> "${score.coerceAtLeast(0)}점"
    AppLanguage.ENGLISH -> "${score.coerceAtLeast(0)} pts"
    AppLanguage.JAPANESE -> "${score.coerceAtLeast(0)}点"
}

@Composable
internal fun CharacterBattleTraits(
    activeTraits: List<ActiveBattleTrait>,
    removalTraitName: String?,
    onToggleRemoval: (String) -> Unit,
) {
    val traits = battlePreviewTraits(activeTraits)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        BattleSectionIntro(
            title = "결투장 특성 · ${traits.size}/5",
            detail = "전투 성향과 핵심 장면에 반영",
            icon = Icons.Filled.Bolt,
        )
        traits.forEach { trait ->
            val removing = removalTraitName == trait.name
            val combatProfile = BattleTraitCatalog.byId[trait.id]?.combatProfile
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF1B1424))
                    .border(
                        1.dp,
                        if (removing) AqRed.copy(alpha = 0.6f) else Color(0xFF46394F),
                        RoundedCornerShape(14.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = trait.name,
                        color = BattleTraitCatalog.byId[trait.id]?.let(::battleTraitAccent) ?: AqText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Black,
                    )
                    Text(
                        text = trait.description,
                        color = AqMuted,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                    )
                    combatProfile?.let { profile ->
                        Text(
                            text = "공격성 ${profile.aggression} · 강공 ${profile.powerAttack} · 승부수 ${profile.gamble}",
                            color = BattleBlue,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "안정성 ${profile.stability} · 단기전 ${profile.shortFight} · 장기전 ${profile.longFight}",
                            color = BattleNeutral,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (removing) {
                        Text(
                            text = "1시간 후 제거",
                            color = AqRed,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                TextButton(onClick = { onToggleRemoval(trait.name) }) {
                    Text(
                        text = if (removing) "취소" else "제거",
                        color = if (removing) AqRed else AqMuted,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun BattleSectionIntro(
    title: String,
    detail: String? = null,
    icon: ImageVector,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = AqGold, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(9.dp))
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            color = AqText,
            fontSize = 14.sp,
            fontWeight = FontWeight.Black,
        )
        detail?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.weight(1f))
            Text(it, color = AqMuted, fontSize = 10.sp)
        }
    }
}
