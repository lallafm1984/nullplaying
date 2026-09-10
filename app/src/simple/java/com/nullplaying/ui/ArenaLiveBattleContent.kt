package com.nullplaying.ui

import android.animation.ValueAnimator
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nullplaying.BuildConfig
import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.engine.SkillCatalog
import com.nullplaying.engine.SkillDefinition
import com.nullplaying.engine.arena.ArenaRunStatus
import com.nullplaying.engine.arena.ArenaSupportEvent
import com.nullplaying.engine.arena.ArenaSupportEventText
import com.nullplaying.engine.arena.ArenaSupportEventType
import com.nullplaying.engine.arena.ArenaSupportResult
import com.nullplaying.localization.AppLanguage
import kotlinx.coroutines.delay
import kotlin.math.abs

internal data class ArenaLiveFighterSnapshot(
    val hp: Double,
    val maxHp: Double,
    val mpUnits: Int,
    val maxMpUnits: Int,
    val shield: Double,
) {
    val hpFraction: Float get() = (hp / maxHp).toFloat().coerceIn(0f, 1f)
    val mpFraction: Float get() = if (maxMpUnits > 0) (mpUnits.toFloat() / maxMpUnits).coerceIn(0f, 1f) else 0f
    val shieldFraction: Float get() = (shield / maxHp).toFloat().coerceAtLeast(0f)
}

internal data class ArenaLiveLogLine(val sequence: Int, val text: String)

internal data class ArenaLiveVisibleLogEntry(
    val sequence: Int,
    val ordinal: Int,
    val text: String,
)

internal data class ArenaLiveSkillPresentation(
    val sequence: Int,
    val catalogId: String,
    val actorId: String,
    val targetId: String,
)

internal const val ARENA_LIVE_GAUGE_MOTION_MILLIS = 120L
internal const val ARENA_LIVE_SILENT_MP_BEAT_MILLIS = 240L
internal const val ARENA_LIVE_TURN_MILLIS = SimpleGameEngine.ATTACK_PRESENTATION_MILLIS

internal fun arenaLiveGaugeFraction(before: Float, after: Float, elapsedMillis: Long): Float {
    val start = if (before.isFinite()) before.coerceIn(0f, 1f) else 0f
    val end = if (after.isFinite()) after.coerceIn(0f, 1f) else 0f
    val progress = (elapsedMillis.toFloat() / ARENA_LIVE_GAUGE_MOTION_MILLIS.toFloat()).coerceIn(0f, 1f)
    return start + (end - start) * progress
}

internal fun arenaLiveDisplayedGaugeFraction(
    before: Float,
    after: Float,
    elapsedMillis: Long,
    skillDefinition: SkillDefinition? = null,
    reducedMotion: Boolean = false,
): Float = if (skillDefinition != null) {
    skillEnergyFraction(
        elapsedMillis = elapsedMillis.toInt(),
        startFraction = before,
        endFraction = after,
        definition = skillDefinition,
        reducedMotion = reducedMotion,
    )
} else {
    arenaLiveGaugeFraction(before, after, elapsedMillis)
}

internal fun arenaLiveGaugeMotionMillis(skillDefinition: SkillDefinition?): Long =
    skillDefinition?.hitTimingsMillis?.lastOrNull()?.plus(96)?.toLong()
        ?.coerceAtLeast(ARENA_LIVE_GAUGE_MOTION_MILLIS)
        ?: ARENA_LIVE_GAUGE_MOTION_MILLIS

internal fun arenaLiveImpactTintAlpha(elapsedMillis: Long, skillDefinition: SkillDefinition?): Float {
    val impactStart = skillDefinition?.hitTimingsMillis?.firstOrNull()?.toLong() ?: 0L
    if (elapsedMillis < impactStart) return 0f
    val fade = ((elapsedMillis - impactStart).toFloat() / 240f).coerceIn(0f, 1f)
    return .25f * (1f - fade)
}

internal fun arenaLivePlaybackClockDelta(
    foreground: Boolean,
    dialogVisible: Boolean,
    inspectionPaused: Boolean,
    wallDeltaMillis: Long,
): Long = if (foreground && !dialogVisible && !inspectionPaused) {
    wallDeltaMillis.coerceIn(0L, 32L)
} else {
    0L
}

internal fun arenaLiveSuccessfulSkill(event: ArenaSupportEvent): ArenaLiveSkillPresentation? {
    if (event.type != ArenaSupportEventType.ATTACK_HIT || event.actionId == "BASIC_ATTACK") return null
    val catalogId = event.actionId?.takeIf { SkillCatalog.find(it) != null } ?: return null
    return ArenaLiveSkillPresentation(
        sequence = event.sequence,
        catalogId = catalogId,
        actorId = event.actorId ?: return null,
        targetId = event.targetId ?: return null,
    )
}

/** Every beat contains its complete before/after ledger; playing never simulates mechanics. */
internal data class ArenaLiveBeat(
    val turn: Int,
    val type: ArenaSupportEventType?,
    val sequences: List<Int>,
    val before: Map<String, ArenaLiveFighterSnapshot>,
    val after: Map<String, ArenaLiveFighterSnapshot>,
    val message: String?,
    val logs: List<ArenaLiveLogLine>,
    val durationMillis: Long,
    val damageTargetId: String? = null,
    val skill: ArenaLiveSkillPresentation? = null,
    val terminal: Boolean = false,
)

internal data class ArenaLiveTimeline(val beats: List<ArenaLiveBeat>) {
    val logs: List<ArenaLiveLogLine> get() = beats.flatMap { it.logs }
}

/** The active beat remains on stage until it completes; only settled beats enter the live log. */
internal fun arenaLiveVisibleLogEntries(
    timeline: ArenaLiveTimeline?,
    playbackIndex: Int,
    finished: Boolean,
): List<ArenaLiveVisibleLogEntry> {
    if (timeline == null) return emptyList()
    val chronological = if (finished) {
        timeline.logs
    } else {
        timeline.beats
            .take(playbackIndex.coerceIn(0, timeline.beats.size))
            .flatMap(ArenaLiveBeat::logs)
    }
    return chronological.mapIndexed { index, log ->
        ArenaLiveVisibleLogEntry(
            sequence = log.sequence,
            ordinal = index + 1,
            text = log.text,
        )
    }.asReversed()
}

/** Pure presentation projection: reject a corrupt ledger rather than invent a final HP drop. */
internal fun buildArenaLiveTimeline(
    simulation: ArenaSupportResult,
    names: Map<String, String>,
    language: String = "ko",
): ArenaLiveTimeline {
    require(simulation.status == ArenaRunStatus.COMPLETED) { "Unfinished arena simulation" }
    require(simulation.fighters.size == 2 && (simulation.winnerId in simulation.fighters ||
        simulation.winnerId == null && simulation.fighters.values.all { it.hp == 0.0 })) { "Invalid arena participants" }
    require(simulation.events.map { it.sequence }.zipWithNext().all { (a, b) -> a < b }) { "Unordered arena ledger" }
    val manaFrames = buildArenaLiveManaFrames(simulation.events, simulation.fighters.mapValues { it.value.maxMpUnits })
    val current = simulation.fighters.mapValues { (_, fighter) ->
        require(fighter.maxHp.isFinite() && fighter.maxHp > 0 && fighter.maxMpUnits >= 0)
        ArenaLiveFighterSnapshot(fighter.maxHp, fighter.maxHp, fighter.maxMpUnits, fighter.maxMpUnits, 0.0)
    }.toMutableMap()
    fun apply(event: ArenaSupportEvent) {
        val hpOwner = when (event.type) {
            ArenaSupportEventType.ATTACK_HIT, ArenaSupportEventType.DOT_DAMAGE -> event.targetId
            ArenaSupportEventType.START, ArenaSupportEventType.HEAL_APPLIED, ArenaSupportEventType.KO -> event.actorId
            else -> null
        }
        if (hpOwner != null && event.hpAfter != null) {
            val prior = current.getValue(hpOwner)
            require(event.hpBefore == null || abs(event.hpBefore - prior.hp) < 0.000001) { "HP before differs from ledger" }
            require(event.hpAfter.isFinite() && event.hpAfter in 0.0..prior.maxHp) { "Invalid HP ledger" }
            current[hpOwner] = prior.copy(hp = event.hpAfter)
        }
        event.actorId?.let { id ->
            var fighter = current.getValue(id)
            if (event.mpAfterUnits != null) {
                require(event.mpBeforeUnits == null || event.mpBeforeUnits == fighter.mpUnits) { "MP before differs from ledger" }
                require(event.mpAfterUnits in 0..fighter.maxMpUnits) { "Invalid MP ledger" }
                fighter = fighter.copy(mpUnits = event.mpAfterUnits)
            }
            if (event.shieldAfter != null) {
                require(event.shieldBefore == null || abs(event.shieldBefore - fighter.shield) < 0.000001) { "Shield before differs from ledger" }
                require(event.shieldAfter.isFinite() && event.shieldAfter >= 0.0) { "Invalid shield ledger" }
                fighter = fighter.copy(shield = event.shieldAfter)
            }
            current[id] = fighter
        }
    }
    simulation.events.filter { it.type == ArenaSupportEventType.START }.forEach(::apply)
    var visibleMana = current.mapValues { it.value.mpUnits }
    fun displayedSnapshot() = current.mapValues { (id, fighter) -> fighter.copy(mpUnits = visibleMana.getValue(id)) }
    val participants = names.values.toList()
    require(participants.size == 2)
    val introduction = when (language) {
        "en" -> "${participants[0]} and ${participants[1]} begin their duel."
        "ja" -> "${participants[0]}と${participants[1]}の決闘が始まった。"
        else -> "${participants[0]} · ${participants[1]}의 전투가 시작됐다."
    }
    val beats = mutableListOf(ArenaLiveBeat(0, null, emptyList(), current.toMap(), current.toMap(), introduction, emptyList(), 1_500L))
    val knockedOut = mutableSetOf<String>()
    var ended = false
    for ((turn, events) in simulation.events.groupBy { it.turn }) {
        val pendingDefense = mutableListOf<ArenaSupportEvent>()
        var turnHasPause = false
        for (event in events) {
            require(!ended) { "Events after arena END" }
            if (event.type == ArenaSupportEventType.START) continue
            require(event.type != ArenaSupportEventType.SAFETY_ABORT) { "Arena safety abort" }
            if (event.type in setOf(ArenaSupportEventType.CAST_START, ArenaSupportEventType.ATTACK_HIT,
                    ArenaSupportEventType.ATTACK_MISS, ArenaSupportEventType.ATTACK_EVADED)) {
                require(event.actorId !in knockedOut && current.getValue(checkNotNull(event.actorId)).hp > 0.0) { "Action after KO" }
                event.targetId?.let { require(it !in knockedOut && current.getValue(it).hp > 0.0) { "Attack targets defeated fighter" } }
            }
            if (event.type in setOf(ArenaSupportEventType.SHIELD_ABSORBED, ArenaSupportEventType.DAMAGE_REDUCED) ||
                (pendingDefense.isNotEmpty() && event.type in setOf(ArenaSupportEventType.EFFECT_EXPIRED,
                    ArenaSupportEventType.TRAIT_TRIGGERED, ArenaSupportEventType.SUPPORT_TRIGGERED))) {
                pendingDefense += event
                continue
            }
            val before = displayedSnapshot()
            val appliesDamage = event.type == ArenaSupportEventType.ATTACK_HIT || event.type == ArenaSupportEventType.DOT_DAMAGE
            val details = if (appliesDamage) pendingDefense.toList() else emptyList()
            if (details.isNotEmpty()) {
                require(details.filter { it.type in setOf(ArenaSupportEventType.SHIELD_ABSORBED,
                    ArenaSupportEventType.DAMAGE_REDUCED) }.all { it.actorId == event.targetId }) {
                    "Shield owner differs from hit target"
                }
                details.forEach(::apply)
                pendingDefense.clear()
            }
            apply(event)
            visibleMana = manaFrames.getValue(event.sequence)
            val after = displayedSnapshot()
            if (event.type == ArenaSupportEventType.KO) {
                val loser = checkNotNull(event.actorId)
                require(before.getValue(loser).hp == 0.0) { "KO must follow the actual lethal hit" }
                knockedOut += loser
            }
            val text = ArenaSupportEventText.text(event, names, language)
            val detailLogs = details.mapNotNull { detail ->
                ArenaSupportEventText.text(detail, names, language)?.let { ArenaLiveLogLine(detail.sequence, it) }
            }
            val logs = detailLogs + listOfNotNull(text?.let { ArenaLiveLogLine(event.sequence, it) })
            val message = if (appliesDamage) {
                // Every resolved hit owns the stage message, with defense details kept on the same impact.
                listOfNotNull(text, detailLogs.firstOrNull()?.text).joinToString("\n").ifBlank { null }
            } else text
            val isImpact = event.type in setOf(ArenaSupportEventType.ATTACK_HIT, ArenaSupportEventType.ATTACK_MISS,
                ArenaSupportEventType.ATTACK_EVADED, ArenaSupportEventType.HEAL_APPLIED, ArenaSupportEventType.DOT_DAMAGE)
            val manaChanged = before.any { (id, fighter) -> fighter.mpUnits != after.getValue(id).mpUnits }
            val duration = when {
                isImpact -> ARENA_LIVE_TURN_MILLIS
                event.type in setOf(ArenaSupportEventType.TRAIT_TRIGGERED,
                    ArenaSupportEventType.SUPPORT_TRIGGERED) && text != null -> 750L
                event.type == ArenaSupportEventType.KO -> 750L
                event.type == ArenaSupportEventType.CAST_START && text != null -> ARENA_LIVE_TURN_MILLIS
                event.type == ArenaSupportEventType.SUPPORT_APPLIED && text != null -> ARENA_LIVE_TURN_MILLIS
                manaChanged -> ARENA_LIVE_SILENT_MP_BEAT_MILLIS
                else -> 0L
            }
            val terminal = event.type == ArenaSupportEventType.END
            if (terminal) {
                require(event.actorId == simulation.winnerId && knockedOut.isNotEmpty()) { "Invalid terminal winner" }
                if (simulation.winnerId == null) {
                    require(knockedOut.size == 2 && current.values.all { it.hp == 0.0 }) { "Draw requires simultaneous defeat" }
                } else {
                    require(current.getValue(simulation.winnerId).hp > 0.0) { "Winner has no HP" }
                    require(current.filterKeys { it != simulation.winnerId }.all { it.value.hp == 0.0 }) { "END before HP zero" }
                }
                simulation.fighters.forEach { (id, fighter) ->
                    val replay = current.getValue(id)
                    require(abs(replay.hp - fighter.hp) < 0.000001 && replay.mpUnits == fighter.mpUnits &&
                        abs(replay.shield - fighter.shield) < 0.000001) { "Final arena ledger mismatch" }
                }
                ended = true
            }
            beats += ArenaLiveBeat(turn, event.type, details.map { it.sequence } + event.sequence, before, after,
                message, logs, duration,
                damageTargetId = event.targetId.takeIf { appliesDamage && (event.amount > 0.0 || details.isNotEmpty()) },
                skill = arenaLiveSuccessfulSkill(event),
                terminal = terminal)
            if (duration > 0) turnHasPause = true
        }
        require(pendingDefense.isEmpty()) { "Defense has no resolved attack" }
        if (turn > 0 && !turnHasPause && !ended) {
            beats += ArenaLiveBeat(turn, null, emptyList(), displayedSnapshot(), displayedSnapshot(), null, emptyList(), ARENA_LIVE_TURN_MILLIS)
        }
    }
    require(ended) { "Missing arena END" }
    return ArenaLiveTimeline(beats)
}

@Composable
internal fun ArenaLiveBattleContent(
    result: BattlePreviewResult,
    backgroundResourceId: Int,
    finished: Boolean,
    playbackPosition: MutableIntState,
    playbackElapsed: MutableLongState,
    onFinished: () -> Unit,
    resultContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val live = checkNotNull(result.supportBattle)
    val language = LocalAppLanguage.current
    val battleId = result.battle.battleId
    val names = remember(battleId) { linkedMapOf(live.user.fighter.id to result.userName, live.opponent.fighter.id to result.opponentName) }
    val timelineResult = remember(battleId, language) { runCatching { buildArenaLiveTimeline(live.simulation, names, language.languageTag) } }
    val timeline = timelineResult.getOrNull()
    var index by playbackPosition
    var activeElapsed by playbackElapsed
    val onFinishedLatest by rememberUpdatedState(onFinished)
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumed by remember(lifecycleOwner) { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(battleId, timeline, finished) {
        if (finished || timeline == null) return@LaunchedEffect
        while (index < timeline.beats.size) {
            while (!resumed) delay(32)
            val beat = timeline.beats[index]
            if (beat.terminal) {
                onFinishedLatest()
                return@LaunchedEffect
            }
            while (activeElapsed < beat.durationMillis) {
                while (!resumed) delay(32)
                val previous = SystemClock.elapsedRealtime()
                delay(16)
                activeElapsed = (activeElapsed + arenaLivePlaybackClockDelta(
                    foreground = resumed,
                    dialogVisible = false,
                    inspectionPaused = false,
                    wallDeltaMillis = SystemClock.elapsedRealtime() - previous,
                ))
                    .coerceAtMost(beat.durationMillis)
            }
            activeElapsed = 0L
            index += 1
        }
    }
    val beat = timeline?.beats?.getOrNull(if (finished) timeline.beats.lastIndex else index.coerceAtMost(timeline.beats.lastIndex))
    val reducedMotion = !ValueAnimator.areAnimatorsEnabled()
    val gaugeElapsed = if (finished) ARENA_LIVE_GAUGE_MOTION_MILLIS else activeElapsed
    val activeSkillDefinition = beat?.skill?.catalogId?.let(::skillDefinition)
    fun fighter(id: String, name: String, classLabel: String, level: Long): ArenaCombatFighterUi {
        val after = beat?.after?.get(id)
        val before = beat?.before?.get(id) ?: after
        val targetSkillDefinition = activeSkillDefinition.takeIf { beat?.skill?.targetId == id }
        val hp = if (before != null && after != null) {
            arenaLiveDisplayedGaugeFraction(
                before.hpFraction,
                after.hpFraction,
                gaugeElapsed,
                targetSkillDefinition,
                reducedMotion,
            )
        } else 1f
        val mp = if (before != null && after != null) {
            arenaLiveGaugeFraction(before.mpFraction, after.mpFraction, gaugeElapsed)
        } else 1f
        val shield = if (before != null && after != null) {
            arenaLiveDisplayedGaugeFraction(
                before.shieldFraction,
                after.shieldFraction,
                gaugeElapsed,
                targetSkillDefinition,
                reducedMotion,
            )
        } else 0f
        return ArenaCombatFighterUi(name, classLabel, level, hp, mp, shield)
    }
    val tint = if (!finished && beat?.damageTargetId != null) {
        val color = if (beat.damageTargetId == live.user.fighter.id) AqRed else Color(0xFF78A8D8)
        color.copy(alpha = arenaLiveImpactTintAlpha(activeElapsed, activeSkillDefinition))
    } else Color.Transparent
    val errorMessage = when (language.languageTag) {
        "en" -> "The battle record could not be verified."
        "ja" -> "戦闘記録を確認できませんでした。"
        else -> "전투 기록을 확인할 수 없습니다."
    }
    val logEntries = remember(timeline, index, finished) {
        arenaLiveVisibleLogEntries(timeline, index, finished)
    }
    Column(modifier.fillMaxSize()) {
        if (finished && timeline != null) resultContent() else ArenaCombatStage(
            left = fighter(
                live.user.fighter.id,
                result.userName,
                battleHeroClassLabel(result.userClass, language),
                result.userLevel,
            ),
            right = fighter(
                live.opponent.fighter.id,
                result.opponentName,
                battleHeroClassLabel(result.opponentClass, language),
                result.opponentLevel,
            ),
            message = if (timeline == null) errorMessage else beat?.message,
            tint = tint,
            backgroundResourceId = backgroundResourceId,
            skillCatalogId = beat?.skill?.catalogId,
            skillElapsedMillis = activeElapsed.toInt(),
            skillMirrored = beat?.skill?.actorId == live.opponent.fighter.id,
            modifier = Modifier.fillMaxWidth().semantics {
                if (BuildConfig.BUILD_TYPE == "battleQa") contentDescription = "arena-live index=$index type=${beat?.type ?: "INTRO"} " +
                    "eventSequence=${beat?.sequences?.lastOrNull() ?: -1} " +
                    "userHP=${beat?.after?.get(live.user.fighter.id)?.hp} userMP=${beat?.after?.get(live.user.fighter.id)?.mpUnits} " +
                    "userShield=${beat?.after?.get(live.user.fighter.id)?.shield} " +
                    "opponentHP=${beat?.after?.get(live.opponent.fighter.id)?.hp} opponentMP=${beat?.after?.get(live.opponent.fighter.id)?.mpUnits} " +
                    "opponentShield=${beat?.after?.get(live.opponent.fighter.id)?.shield} " +
                    "skillVfx=${beat?.skill?.catalogId ?: "none"} skillSide=${if (beat?.skill?.actorId == live.opponent.fighter.id) "right" else "left"} " +
                    "skillElapsed=$activeElapsed"
            },
        )
        Card(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 10.dp).semantics {
                if (BuildConfig.BUILD_TYPE == "battleQa") {
                    contentDescription = "arena-live-log count=${logEntries.size} sequences=${logEntries.joinToString(",") { it.sequence.toString() }}"
                }
            },
            colors = CardDefaults.cardColors(containerColor = AqSurface),
            shape = RoundedCornerShape(22.dp),
        ) {
            ArenaLiveBattleLog(logEntries, result.userName, result.opponentName, language)
        }
    }
}

internal fun arenaLiveBattleLogTitle(language: AppLanguage): String = when (language) {
    AppLanguage.KOREAN -> "결투장 기록"
    AppLanguage.ENGLISH -> "Arena log"
    AppLanguage.JAPANESE -> "闘技場の記録"
}

@Composable
private fun ArenaLiveBattleLog(
    entries: List<ArenaLiveVisibleLogEntry>,
    userName: String,
    opponentName: String,
    language: AppLanguage,
) {
    val listState = rememberLazyListState()
    var previousCount by remember { mutableIntStateOf(entries.size) }
    LaunchedEffect(entries.firstOrNull()?.sequence) {
        val inserted = (entries.size - previousCount).coerceAtLeast(0)
        if (!listState.isScrollInProgress && listState.firstVisibleItemIndex <= inserted && listState.firstVisibleItemScrollOffset == 0) {
            listState.scrollToItem(0)
        }
        previousCount = entries.size
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "heading") {
            Text(
                arenaLiveBattleLogTitle(language),
                color = AqText,
                fontSize = 17.sp,
                fontWeight = FontWeight.Black,
            )
        }
        items(entries, key = { it.sequence }) { entry ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF1B1424)).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                MaterialText("${entry.ordinal}", color = AqGold, fontSize = 12.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.width(9.dp))
                val styled = remember(entry.text, userName, opponentName) {
                    buildAnnotatedString {
                        append(entry.text)
                        listOf(userName to Color(0xFF78A8D8), opponentName to AqRed).filter { it.first.isNotBlank() }.forEach { (name, color) ->
                            var start = entry.text.indexOf(name)
                            while (start >= 0) {
                                addStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold), start, start + name.length)
                                start = entry.text.indexOf(name, start + name.length)
                            }
                        }
                    }
                }
                MaterialText(styled, modifier = Modifier.weight(1f), color = AqText, fontSize = 13.sp, lineHeight = 20.sp)
            }
        }
    }
}
