package com.nullplaying.ui

import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nullplaying.BuildConfig
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.nullplaying.engine.arena.*
import com.nullplaying.model.HeroClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Local preview and emulator diagnostics in battleQa only, never in release manifests. */
class ArenaSupportQaActivity : ComponentActivity() {
    private val outputDir by lazy { File(filesDir, "arena-support-qa").apply { mkdirs() } }
    private var scenario = "last"
    private var foreground by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        enterImmersiveMode()
        check(BuildConfig.DEBUG && !BuildConfig.REMOTE_SERVICES_ENABLED)
        check(BuildConfig.SUPABASE_URL.isBlank() && BuildConfig.SUPABASE_PUBLISHABLE_KEY.isBlank())
        check(!BuildConfig.BATTLE_QA_BRIDGE_ENABLED)
        scenario = intent.getStringExtra("qa_scenario")?.takeIf { it.matches(Regex("[a-z0-9_-]{1,64}")) } ?: "last"
        val auditMode = intent.getStringExtra("qa_mode") == "audit"
        setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1f)) {
                AlarmQuestTheme { if (auditMode) AuditPage() else BattlePage() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    override fun onResume() { super.onResume(); foreground = true }
    override fun onPause() { foreground = false; super.onPause() }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private suspend fun save(name: String, value: String) = withContext(Dispatchers.IO) {
        File(outputDir, "$scenario-$name.json").writeText(value)
    }

    @Composable
    private fun AuditPage() {
        var headline by remember { mutableStateOf("에뮬레이터에서 대전 검사 중") }
        var detail by remember { mutableStateOf("이 기기의 Android 런타임에서 새 지원 엔진을 실행합니다.") }
        var finished by remember { mutableStateOf(false) }
        var passed by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            try {
                val samples = intent.getIntExtra("qa_samples", 10).coerceIn(1, 100)
                val levels = intent.getStringExtra("qa_levels")?.split(',')?.mapNotNull { it.toIntOrNull()?.takeIf { level -> level in 10..100 } }
                    ?.distinct()?.sorted()?.takeIf { it.isNotEmpty() } ?: (10..100 step 10).toList()
                val started = SystemClock.elapsedRealtime()
                val result = withContext(Dispatchers.Default) { ArenaSupportAudit.run(samples, levels) }
                val audit = JSONObject(result.json)
                    .put("executionLocation", "Android APK process")
                    .put("deviceModel", Build.MODEL).put("androidApi", Build.VERSION.SDK_INT)
                    .put("packageName", packageName).put("versionCode", BuildConfig.VERSION_CODE)
                    .put("remoteServicesEnabled", BuildConfig.REMOTE_SERVICES_ENABLED)
                save("audit", audit.toString(2))
                passed = result.passed
                headline = if (passed) "기기 실행 검사 통과" else "기기 실행 검사 실패"
                detail = "${result.executions}회 엔진 실행 · ${(SystemClock.elapsedRealtime() - started) / 1000.0}초\n" +
                    "기계적 오류 ${audit.optInt("failureCount", result.failures.size)}건\n" + result.failures.take(8).joinToString("\n") +
                    "\n보조 ${ArenaSupportCatalog.values.size}개·성장 특성 ${ArenaProgressionCatalog.values.size}개 카탈로그.\n이 표본의 기계 검증은 최종 직업 밸런스 인증과 별개입니다."
                Log.i("ARENA_SUPPORT_QA", "audit completed passed=$passed executions=${result.executions} scenario=$scenario")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                headline = "검사 실행 오류"
                detail = error.stackTraceToString()
                save("error", JSONObject().put("error", detail).toString(2))
                Log.e("ARENA_SUPPORT_QA", "audit failed", error)
            }
            finished = true
        }
        Column(Modifier.fillMaxSize().background(LabBackground).systemBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("OFFLINE QA · 새 지원 전투 엔진", color = LabGold, fontSize = 15.sp)
            Text(headline, color = if (finished && !passed) LabRed else Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            if (!finished) CircularProgressIndicator(color = LabBlue)
            Text(detail, color = Color(0xFFCCC5D6), fontSize = 14.sp)
            Text("로컬 검사 기록: $scenario-audit.json\n서버·광고·AI API 연결 없음", color = Color.Gray, fontSize = 12.sp)
        }
    }

    @Composable
    private fun BattlePage() {
        val language = remember { intent.getStringExtra("qa_language")?.takeIf { it in listOf("ko", "en", "ja") } ?: "ko" }
        val preview = remember { intent.getStringExtra("qa_mode") == "preview" }
        val requestedArenaLevel = remember {
            intent.getIntExtra("qa_arena_level", if (preview) 1 else 60).coerceIn(1, 100)
        }
        val defaultEnhancement = remember(requestedArenaLevel, preview) {
            if (preview) 0 else (3 downTo 0).first { stage ->
                ArenaProgressionRules.enhancementCost(stage) <=
                    (requestedArenaLevel - 50).coerceAtLeast(0)
            }
        }
        var config by remember { mutableStateOf(ArenaPreviewConfig(
            leftClass = parseClass(intent.getStringExtra("qa_left"), HeroClass.ROGUE),
            rightClass = parseClass(intent.getStringExtra("qa_right"), HeroClass.MAGE),
            heroLevel = intent.getIntExtra("qa_level", 10).coerceIn(
                if (BuildConfig.BATTLE_IGNORE_HERO_LEVEL_GATE) 1 else 10, 100),
            traitIndex = intent.getIntExtra("qa_trait_index", 0).coerceIn(0, 1),
            traitRank = intent.getIntExtra("qa_rank", if (preview) 1 else 5).coerceIn(0, 5),
            enhancement = intent.getIntExtra("qa_enhancement", defaultEnhancement).coerceIn(0, 3),
            arenaLevel = requestedArenaLevel,
        )) }
        var started by remember { mutableStateOf(!preview) }
        var leaveDialog by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }
        val pace = remember { intent.getIntExtra("qa_pace_ms", ARENA_LIVE_TURN_MILLIS.toInt()).coerceIn(100, 2000) }
        val pauseOn = remember { intent.getStringExtra("qa_pause_on") }
        val names = remember { when (language) { "en" -> mapOf("qa-blue" to "Noah", "qa-red" to "Eve"); "ja" -> mapOf("qa-blue" to "ノア", "qa-red" to "イヴ"); else -> mapOf("qa-blue" to "노아", "qa-red" to "이브") } }
        var seed by remember { mutableLongStateOf(intent.getLongExtra("qa_seed", 41L)) }
        var running by remember { mutableStateOf(!preview) }
        var paused by remember { mutableStateOf(false) }
        var resultShown by remember { mutableStateOf(false) }
        var resultText by remember { mutableStateOf("") }
        var turn by remember { mutableIntStateOf(0) }
        var message by remember { mutableStateOf("") }
        var status by remember { mutableStateOf("전투 계산 중") }
        val health = remember { mapOf("qa-blue" to Animatable(1f), "qa-red" to Animatable(1f)) }
        val maxHp = remember { mutableStateMapOf<String, Double>() }
        val mana = remember { mapOf("qa-blue" to Animatable(1f), "qa-red" to Animatable(1f)) }
        val maxMana = remember { mutableStateMapOf<String, Int>() }
        val shields = remember { mutableStateMapOf<String, Double>() }
        val casting = remember { mutableStateMapOf<String, String>() }
        val logs = remember { mutableStateListOf<Pair<Int, String>>() }
        val tint = remember { Animatable(0f) }
        var tintColor by remember { mutableStateOf(LabBlue) }
        var skillCatalogId by remember { mutableStateOf<String?>(null) }
        var skillElapsedMillis by remember { mutableIntStateOf(SKILL_PRESENTATION_DURATION_MILLIS) }
        var skillMirrored by remember { mutableStateOf(false) }

        LaunchedEffect(seed, config, started) {
            running = started; paused = false; failed = false; turn = 0; resultShown = false; resultText = ""; logs.clear(); casting.clear(); shields.clear()
            skillCatalogId = null; skillElapsedMillis = SKILL_PRESENTATION_DURATION_MILLIS; skillMirrored = false
            tint.snapTo(0f)
            val trace = JSONArray()
            suspend fun awaitPlayback() { while (!foreground || leaveDialog) delay(32) }
            suspend fun holdActive(millis: Long) {
                var remaining = millis.coerceAtLeast(0)
                while (remaining > 0) {
                    awaitPlayback()
                    val slice = remaining.coerceAtMost(32)
                    delay(slice)
                    if (foreground && !leaveDialog) remaining -= slice
                }
            }
            try {
                val inputs = if (intent.getBooleanExtra("qa_full_catalog", false)) {
                    listOf(config.leftClass to "qa-blue", config.rightClass to "qa-red").map { (heroClass, id) ->
                        ArenaSupportQaFixtures.fullFighter(heroClass, config.heroLevel, id,
                            arenaNpcTraitAllocation(heroClass, config.arenaLevel, config.heroLevel.toLong()), config.arenaLevel)
                    }
                } else listOf(
                    ArenaSupportQaFixtures.fighter(config.leftClass, config.heroLevel, "qa-blue", config.traitRank, config.enhancement, config.arenaLevel,
                        traitIndex = config.traitIndex,
                        ignoreHeroLevelGate = BuildConfig.BATTLE_IGNORE_HERO_LEVEL_GATE),
                    ArenaSupportQaFixtures.fighter(config.rightClass, config.heroLevel, "qa-red", config.traitRank, config.enhancement, config.arenaLevel,
                        traitIndex = config.traitIndex,
                        ignoreHeroLevelGate = BuildConfig.BATTLE_IGNORE_HERO_LEVEL_GATE),
                )
                val result = withContext(Dispatchers.Default) { ArenaSupportTurnEngine.simulate(inputs[0], inputs[1], seed,
                    ignoreHeroLevelGate = BuildConfig.BATTLE_IGNORE_HERO_LEVEL_GATE) }
                for ((id, fighter) in result.fighters) {
                    maxHp[id] = fighter.maxHp; maxMana[id] = fighter.maxMpUnits
                    health.getValue(id).snapTo(1f); mana.getValue(id).snapTo(1f); shields[id] = 0.0
                }
                if (!started) {
                    message = ""; status = "READY"; running = false
                    return@LaunchedEffect
                }
                save("battle", resultJson(result, inputs, names).toString(2))
                val manaFrames = buildArenaLiveManaFrames(result.events, result.fighters.mapValues { it.value.maxMpUnits })
                awaitPlayback()
                message = when (language) { "en" -> "${names["qa-blue"]} and ${names["qa-red"]} begin their duel."; "ja" -> "${names["qa-blue"]}と${names["qa-red"]}の決闘が始まった。"; else -> "${names["qa-blue"]}와 ${names["qa-red"]}의 전투가 시작됐다." }
                status = "시작 안내 · seed $seed"
                holdActive(pace.toLong())
                for ((currentTurn, events) in result.events.groupBy { it.turn }) {
                    awaitPlayback()
                    turn = currentTurn
                    var actionShown = false
                    val pendingDefense = mutableListOf<ArenaSupportEvent>()
                    for (event in events) {
                        awaitPlayback()
                        val actor = event.actorId
                        val target = event.targetId
                        // Absorption, reduction and residual HP loss are one resolved attack,
                        // not separate pauses or separate damage applications.
                        if (event.type in setOf(ArenaSupportEventType.SHIELD_ABSORBED, ArenaSupportEventType.DAMAGE_REDUCED) ||
                            (pendingDefense.isNotEmpty() && event.type in setOf(ArenaSupportEventType.EFFECT_EXPIRED,
                                ArenaSupportEventType.TRAIT_TRIGGERED, ArenaSupportEventType.SUPPORT_TRIGGERED))) {
                            pendingDefense += event
                            continue
                        }
                        if (event.type == ArenaSupportEventType.START && actor != null) {
                            event.hpAfter?.let { health.getValue(actor).snapTo((it / maxHp.getValue(actor)).toFloat()) }
                            event.mpAfterUnits?.let {
                                mana.getValue(actor).snapTo(if (maxMana.getValue(actor) > 0) it.toFloat() / maxMana.getValue(actor) else 0f)
                            }
                            shields[actor] = event.shieldAfter ?: 0.0
                        }
                        if (event.type == ArenaSupportEventType.CAST_START && actor != null) {
                            casting[actor] = if (event.castTurns > 1) castLabel(event.castTurns, language) else ""
                        }
                        if (event.type == ArenaSupportEventType.CAST_PROGRESS && actor != null) {
                            casting[actor] = if (event.remainingTurns > 0) castLabel(event.remainingTurns, language) else ""
                        }
                        val line = ArenaSupportEventText.text(event, names, language)
                        val appliesDamage = event.type == ArenaSupportEventType.ATTACK_HIT || event.type == ArenaSupportEventType.DOT_DAMAGE
                        val details = if (appliesDamage) pendingDefense.toList() else emptyList()
                        val settledDetailLogs = details.mapNotNull { detail ->
                            ArenaSupportEventText.text(detail, names, language)?.let { detail.sequence to it }
                        }
                        val shieldTargets = linkedMapOf<String, Double>()
                        if (details.isNotEmpty()) {
                            pendingDefense.clear()
                            for (detail in details) {
                                detail.shieldAfter?.let { shieldTargets[checkNotNull(detail.actorId)] = it }
                            }
                        }
                        if (line != null && event.type != ArenaSupportEventType.END) {
                            message = line
                        }
                        if (appliesDamage) {
                            val explanation = details.filter { it.type != ArenaSupportEventType.EFFECT_EXPIRED }
                                .mapNotNull { ArenaSupportEventText.text(it, names, language) }.joinToString("\n")
                            message = listOfNotNull(line, explanation.takeIf { it.isNotBlank() }).joinToString("\n")
                            // Every resolved hit replaces an earlier miss, heal or trait message.
                        }
                        val hpOwner = when (event.type) {
                            ArenaSupportEventType.ATTACK_HIT, ArenaSupportEventType.DOT_DAMAGE -> target
                            ArenaSupportEventType.HEAL_APPLIED, ArenaSupportEventType.KO -> actor
                            else -> null
                        }
                        if (event.shieldAfter != null && actor != null) shieldTargets[actor] = event.shieldAfter
                        val impact = event.type in setOf(ArenaSupportEventType.ATTACK_HIT, ArenaSupportEventType.ATTACK_EVADED,
                            ArenaSupportEventType.ATTACK_MISS, ArenaSupportEventType.HEAL_APPLIED, ArenaSupportEventType.DOT_DAMAGE) ||
                            (event.type == ArenaSupportEventType.SUPPORT_APPLIED && line != null)
                        val traitBeat = event.type in setOf(ArenaSupportEventType.TRAIT_TRIGGERED,
                            ArenaSupportEventType.SUPPORT_TRIGGERED) && line != null
                        val displayedMana = manaFrames.getValue(event.sequence)
                        val resourceOwners = displayedMana.keys.filter { id ->
                            val maximum = maxMana.getValue(id)
                            val fraction = if (maximum > 0) displayedMana.getValue(id).toFloat() / maximum else 0f
                            kotlin.math.abs(mana.getValue(id).value - fraction) > .0000001f
                        }
                        check(resourceOwners.size <= 1) { "An arena action changed both displayed MP bars" }
                        val resourceOwner = resourceOwners.singleOrNull()
                        val resourceBeat = resourceOwner != null
                        if (resourceBeat && line == null) message = ""
                        if (impact || traitBeat || resourceBeat) {
                            val frameStarted = SystemClock.elapsedRealtime()
                            actionShown = true
                            status = "${event.type.name} · #${event.sequence}"
                            val damaging = appliesDamage && (event.amount > 0 || details.isNotEmpty())
                            val successfulSkill = arenaLiveSuccessfulSkill(event)
                            val successfulSkillDefinition = successfulSkill?.catalogId?.let(::skillDefinition)
                            skillCatalogId = successfulSkill?.catalogId
                            skillElapsedMillis = if (successfulSkill == null) SKILL_PRESENTATION_DURATION_MILLIS else 0
                            skillMirrored = successfulSkill?.actorId == "qa-red"
                            tintColor = if (target == "qa-blue") LabRed else LabBlue
                            val hpStartFraction = hpOwner?.let { health.getValue(it).value }
                            val hpEndFraction = if (hpOwner != null && event.hpAfter != null) {
                                (event.hpAfter / maxHp.getValue(hpOwner)).toFloat().coerceIn(0f, 1f)
                            } else null
                            val mpStartFraction = resourceOwner?.let { mana.getValue(it).value }
                            val mpEndFraction = resourceOwner?.let {
                                val maximum = maxMana.getValue(it)
                                if (maximum > 0) displayedMana.getValue(it).toFloat() / maximum else 0f
                            }?.coerceIn(0f, 1f)
                            val shieldMotions = shieldTargets.mapValues { (id, endShield) ->
                                val maximum = maxHp.getValue(id)
                                val start = ((shields[id] ?: 0.0) / maximum).toFloat().coerceIn(0f, 1f)
                                val end = (endShield / maximum).toFloat().coerceIn(0f, 1f)
                                start to end
                            }
                            val reducedMotion = !ValueAnimator.areAnimatorsEnabled()
                            val targetUsesSkillClock = successfulSkill?.targetId
                            val gaugeMotionMillis = arenaLiveGaugeMotionMillis(successfulSkillDefinition)
                            val tintMotionMillis = if (damaging) {
                                (successfulSkillDefinition?.hitTimingsMillis?.firstOrNull()?.toLong() ?: 0L) + 240L
                            } else 0L
                            val hasSkillTargetMotion = successfulSkill != null &&
                                ((hpOwner == targetUsesSkillClock && hpStartFraction != hpEndFraction) ||
                                    (targetUsesSkillClock in shieldMotions && shieldMotions[targetUsesSkillClock]?.let { it.first != it.second } == true))
                            val motionCompletionMillis = maxOf(
                                if (hpStartFraction != null && hpEndFraction != null) {
                                    if (hasSkillTargetMotion) gaugeMotionMillis else ARENA_LIVE_GAUGE_MOTION_MILLIS
                                } else 0L,
                                if (mpStartFraction != null && mpEndFraction != null) ARENA_LIVE_GAUGE_MOTION_MILLIS else 0L,
                                if (shieldMotions.isNotEmpty()) {
                                    if (hasSkillTargetMotion) gaugeMotionMillis else ARENA_LIVE_GAUGE_MOTION_MILLIS
                                } else 0L,
                                if (successfulSkill != null) gaugeMotionMillis else 0L,
                                tintMotionMillis,
                            )
                            val motionSamples = JSONArray()
                            var impactActiveMillis = 0L
                            coroutineScope {
                                fun recordMotionSample() {
                                    motionSamples.put(JSONObject()
                                        .put("elapsedMillis", impactActiveMillis)
                                        .put("blueHpFraction", health.getValue("qa-blue").value.toDouble())
                                        .put("redHpFraction", health.getValue("qa-red").value.toDouble())
                                        .put("blueMpFraction", mana.getValue("qa-blue").value.toDouble())
                                        .put("redMpFraction", mana.getValue("qa-red").value.toDouble())
                                        .put("blueShield", shields["qa-blue"] ?: 0.0)
                                        .put("redShield", shields["qa-red"] ?: 0.0)
                                        .put("tintAlpha", tint.value.toDouble()))
                                }
                                recordMotionSample()
                                val activeClock = launch {
                                    var previous = SystemClock.elapsedRealtime()
                                    while (isActive) {
                                        delay(16)
                                        val now = SystemClock.elapsedRealtime()
                                        val activeDelta = arenaLivePlaybackClockDelta(
                                            foreground = foreground,
                                            dialogVisible = leaveDialog,
                                            inspectionPaused = paused,
                                            wallDeltaMillis = now - previous,
                                        )
                                        if (activeDelta > 0L) {
                                            impactActiveMillis += activeDelta
                                            if (successfulSkill != null) {
                                                skillElapsedMillis = impactActiveMillis.toInt()
                                                    .coerceAtMost(SKILL_PRESENTATION_DURATION_MILLIS)
                                            }
                                            if (hpOwner != null && hpStartFraction != null && hpEndFraction != null) {
                                                val clock = successfulSkillDefinition.takeIf { successfulSkill?.targetId == hpOwner }
                                                health.getValue(hpOwner).snapTo(arenaLiveDisplayedGaugeFraction(
                                                    hpStartFraction, hpEndFraction, impactActiveMillis, clock, reducedMotion))
                                            }
                                            if (resourceOwner != null && mpStartFraction != null && mpEndFraction != null) {
                                                mana.getValue(resourceOwner).snapTo(arenaLiveGaugeFraction(
                                                    mpStartFraction, mpEndFraction, impactActiveMillis))
                                            }
                                            for ((id, fractions) in shieldMotions) {
                                                val clock = successfulSkillDefinition.takeIf { successfulSkill?.targetId == id }
                                                val fraction = arenaLiveDisplayedGaugeFraction(
                                                    fractions.first, fractions.second, impactActiveMillis, clock, reducedMotion)
                                                shields[id] = fraction * maxHp.getValue(id)
                                            }
                                            tint.snapTo(if (damaging) {
                                                arenaLiveImpactTintAlpha(impactActiveMillis, successfulSkillDefinition)
                                            } else 0f)
                                            if (impactActiveMillis <= motionCompletionMillis + 32L) recordMotionSample()
                                        }
                                        previous = now
                                    }
                                }
                                try {
                                    while (impactActiveMillis < motionCompletionMillis) delay(16)
                                    if (hpOwner != null && hpEndFraction != null) health.getValue(hpOwner).snapTo(hpEndFraction)
                                    if (resourceOwner != null && mpEndFraction != null) mana.getValue(resourceOwner).snapTo(mpEndFraction)
                                    for ((id, endShield) in shieldTargets) shields[id] = endShield
                                    if (damaging) tint.snapTo(0f)
                                    recordMotionSample()
                                    val shouldPause = pauseOn == event.type.name || details.any { it.type.name == pauseOn }
                                    val state = JSONObject().put("sequence", event.sequence).put("turn", event.turn).put("type", event.type.name)
                                        .put("message", message).put("resultShown", resultShown).put("elapsedRealtime", SystemClock.elapsedRealtime())
                                        .put("impactStartedAt", frameStarted).put("paceMillis", pace).put("inspectionPause", shouldPause)
                                        .put("relatedSequences", JSONArray(details.map { it.sequence }))
                                        .put("blueShield", shields["qa-blue"] ?: 0.0).put("redShield", shields["qa-red"] ?: 0.0)
                                        .put("blueHpFraction", health.getValue("qa-blue").value.toDouble()).put("redHpFraction", health.getValue("qa-red").value.toDouble())
                                        .put("blueMpFraction", mana.getValue("qa-blue").value.toDouble()).put("redMpFraction", mana.getValue("qa-red").value.toDouble())
                                        .put("skillVfx", successfulSkill?.catalogId ?: JSONObject.NULL)
                                        .put("skillSide", if (skillMirrored) "right" else "left")
                                        .put("skillElapsedMillis", skillElapsedMillis)
                                        .put("skillFirstHitMillis", successfulSkillDefinition?.hitTimingsMillis?.firstOrNull() ?: JSONObject.NULL)
                                        .put("motionSamples", motionSamples)
                                    trace.put(state)
                                    if (shouldPause) {
                                        paused = true; status = "검수 일시정지 · $pauseOn"
                                        save("ui-trace", trace.toString(2))
                                        while (paused) delay(50)
                                    }
                                    val requestedHold = (if (traitBeat) pace / 2 else pace).coerceAtLeast(100)
                                    holdActive((requestedHold - impactActiveMillis).coerceAtLeast(0))
                                } finally {
                                    activeClock.cancel()
                                }
                            }
                        } else {
                            for ((id, endShield) in shieldTargets) shields[id] = endShield
                        }
                        // Match the live arena: the active sentence stays on stage, then enters the log once settled.
                        settledDetailLogs.forEach { logs.add(0, it) }
                        if (line != null && event.type != ArenaSupportEventType.END) {
                            logs.add(0, event.sequence to line)
                        }
                        if (event.type in setOf(ArenaSupportEventType.KO, ArenaSupportEventType.CAST_CANCELLED_KO) && actor != null) {
                            health.getValue(actor).snapTo(0f); casting[actor] = ""
                        }
                        if (event.type == ArenaSupportEventType.END) {
                            check(result.fighters.filterKeys { it != result.winnerId }.all { it.value.hp == 0.0 })
                            resultShown = true
                            resultText = if (result.winnerId == null) when (language) {
                                "en" -> "Draw"
                                "ja" -> "引き分け"
                                else -> "무승부"
                            } else when (language) {
                                "en" -> "${names[result.winnerId]} wins"
                                "ja" -> "${names[result.winnerId]}の勝利"
                                else -> "${names[result.winnerId]} 승리"
                            }
                            status = "END · HP 0 확인"
                            trace.put(JSONObject().put("type", "END").put("resultShown", true)
                                .put("elapsedRealtime", SystemClock.elapsedRealtime())
                                .put("blueHpFraction", health.getValue("qa-blue").value.toDouble()).put("redHpFraction", health.getValue("qa-red").value.toDouble())
                                .put("blueMpFraction", mana.getValue("qa-blue").value.toDouble()).put("redMpFraction", mana.getValue("qa-red").value.toDouble()))
                        }
                    }
                    if (!actionShown && currentTurn > 0 && !resultShown) holdActive(pace.toLong())
                }
                if (result.status != ArenaRunStatus.COMPLETED) {
                    failed = true; status = "SAFETY_ABORT"; message = when (language) {
                        "en" -> "The battle stopped safely. No result was recorded."
                        "ja" -> "戦闘を安全に中止しました。結果は記録されません。"
                        else -> "전투를 안전하게 중단했습니다. 승패는 기록되지 않습니다."
                    }
                }
                save("ui-trace", trace.toString(2))
                Log.i("ARENA_SUPPORT_QA", "playback complete scenario=$scenario seed=$seed winner=${result.winnerId}")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                failed = true; status = "${error.javaClass.simpleName}: ${error.message}"
                message = when (language) {
                    "en" -> "Unable to play this battle. Please try again."
                    "ja" -> "戦闘を再生できませんでした。もう一度お試しください。"
                    else -> "전투를 재생하지 못했습니다. 다시 시도해 주세요."
                }
                save("error", JSONObject().put("error", error.stackTraceToString()).toString(2))
                Log.e("ARENA_SUPPORT_QA", "battle failed", error)
            }
            running = false
        }

        fun fighterUi(id: String, heroClass: HeroClass) = ArenaPreviewFighterUi(
            id, names.getValue(id), heroClass, config.heroLevel, health.getValue(id).value,
            maxHp[id] ?: 0.0,
            ((maxMana[id] ?: 0) * mana.getValue(id).value).roundToInt(),
            maxMana[id] ?: 0, shields[id] ?: 0.0, casting[id] ?: "",
        )
        val requestBack = { if (started && running) leaveDialog = true else finish() }
        BackHandler(onBack = requestBack)
        ArenaSupportPreviewScreen(
            state = ArenaPreviewUiState(language, config, fighterUi("qa-blue", config.leftClass), fighterUi("qa-red", config.rightClass),
                turn, message, resultShown, resultText, running, started, paused, "$status · seed $seed", failed,
                skillCatalogId, skillElapsedMillis, skillMirrored),
            logs = logs, tint = tintColor.copy(alpha = tint.value), onBack = requestBack,
            onStart = { started = true }, onReplay = { seed++; started = true },
            onConfigure = { if (!running) { started = false; config = it } },
            onResumeInspection = { paused = false },
        )
        if (leaveDialog) AlertDialog(
            onDismissRequest = { leaveDialog = false },
            containerColor = AqSurface,
            title = { Text(when (language) { "en" -> "Leave this battle?"; "ja" -> "戦闘を終了しますか？"; else -> "전투를 나갈까요?" }) },
            text = { Text(when (language) { "en" -> "This is a test battle. Your record and rewards will not change."; "ja" -> "テスト戦闘です。戦績や報酬は変わりません。"; else -> "체험 전투이므로 전적과 보상은 바뀌지 않습니다." }) },
            confirmButton = { TextButton(onClick = { finish() }) { Text(when (language) { "en" -> "Leave"; "ja" -> "終了"; else -> "나가기" }) } },
            dismissButton = { TextButton(onClick = { leaveDialog = false }) { Text(when (language) { "en" -> "Continue"; "ja" -> "続ける"; else -> "계속 보기" }) } },
        )
    }

    private fun resultJson(result: ArenaSupportResult, inputs: List<ArenaSupportInput>, names: Map<String,String>) = JSONObject()
        .put("scope", if (intent.getBooleanExtra("qa_full_catalog", false))
            "Android APK complete catalogue with captured level-gated ownership" else "Android APK baseline support and trait fixture")
        .put("supportCatalogCount", ArenaSupportCatalog.values.size).put("traitCatalogCount", ArenaProgressionCatalog.values.size)
        .put("rulesVersion", result.rulesVersion).put("seed", result.seed).put("status", result.status.name).put("turns", result.turns)
        .put("winnerId", result.winnerId ?: JSONObject.NULL).put("names", JSONObject(names))
        .put("inputs", JSONArray(inputs.map { input -> JSONObject().put("id", input.fighter.id).put("heroClass", input.fighter.heroClass.name)
            .put("heroLevel", input.fighter.level).put("arenaLevel", input.arenaLevel).put("stats", JSONArray(input.fighter.stats.values()))
            .put("attacks", JSONArray(input.fighter.attacks.map { it.id })).put("supports", JSONArray(input.supportIds.toList()))
            .put("traits", JSONArray(input.traits.map { JSONObject().put("id", it.id).put("rank", it.rank).put("enhancement", it.enhancement) })) }))
        .put("fighters", JSONObject(result.fighters.mapValues { (_,f) -> JSONObject().put("hp",f.hp).put("maxHp",f.maxHp)
            .put("mpUnits",f.mpUnits).put("maxMpUnits",f.maxMpUnits).put("shield",f.shield) }))
        .put("events", JSONArray(result.events.map { e -> JSONObject().put("sequence",e.sequence).put("turn",e.turn).put("type",e.type.name)
            .put("actorId",e.actorId ?: JSONObject.NULL).put("targetId",e.targetId ?: JSONObject.NULL).put("actionId",e.actionId ?: JSONObject.NULL)
            .put("traitId",e.traitId ?: JSONObject.NULL).put("hpBefore",e.hpBefore ?: JSONObject.NULL).put("hpAfter",e.hpAfter ?: JSONObject.NULL)
            .put("mpBeforeUnits",e.mpBeforeUnits ?: JSONObject.NULL).put("mpAfterUnits",e.mpAfterUnits ?: JSONObject.NULL)
            .put("amount",e.amount).put("shieldBefore",e.shieldBefore ?: JSONObject.NULL).put("shieldAfter",e.shieldAfter ?: JSONObject.NULL)
            .put("castTurns",e.castTurns).put("remainingTurns",e.remainingTurns).put("castId",e.castId ?: JSONObject.NULL)
            .put("causeSequence",e.causeSequence ?: JSONObject.NULL).put("traitValue",e.traitValue ?: JSONObject.NULL)
            .put("reason",e.reason ?: JSONObject.NULL).put("effectExpiresAtTurn",e.effectExpiresAtTurn ?: JSONObject.NULL) }))

    private fun parseClass(value: String?, fallback: HeroClass) = HeroClass.entries.firstOrNull { it.name == value } ?: fallback
    private fun castLabel(turns: Int, language: String) = when (language) {
        "en" -> "Casting · $turns turns"
        "ja" -> "詠唱中 · ${turns}ターン"
        else -> "시전 중 · ${turns}턴"
    }
}

private val LabBackground = AqBackground
private val LabBlue = Color(0xFF89BDF8)
private val LabRed = Color(0xFFF27D91)
private val LabGold = AqGold
