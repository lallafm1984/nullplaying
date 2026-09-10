package com.nullplaying.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nullplaying.BuildConfig
import com.nullplaying.localization.AppLanguage
import com.nullplaying.model.HeroClass
import com.nullplaying.model.HeroStats
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Dedicated launcher compiled only into the offline side-by-side arenaLab APK. */
class ArenaLabActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("arena_lab_profiles_v1", MODE_PRIVATE) }
    private val codec = Json { ignoreUnknownKeys = true }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(BuildConfig.DEBUG && BuildConfig.APPLICATION_ID == "com.nullplaying.arenalab")
        check(BuildConfig.BATTLE_UNLIMITED_ENTRIES && !BuildConfig.REMOTE_SERVICES_ENABLED)
        check(!BuildConfig.ARENA_SERVER_MATCHING_ENABLED && !BuildConfig.SHARED_PLAYER_SYNC_ENABLED)
        check(BuildConfig.SUPABASE_URL.isBlank() && BuildConfig.SUPABASE_PUBLISHABLE_KEY.isBlank())
        (application as com.nullplaying.AlarmQuestApplication).gameLanguageStore.setLanguage(AppLanguage.KOREAN)
        enableEdgeToEdge()
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.KOREAN) {
                AlarmQuestTheme { Lab() }
            }
        }
    }

    private fun loadProfiles(): Map<String, ArenaLabProfile> = runCatching {
        codec.decodeFromString<Map<String, ArenaLabProfile>>(prefs.getString("profiles", "{}")!!)
            .filter { (key, profile) -> key == profile.key && ArenaLabProfiles.validate(profile) }
    }.getOrDefault(emptyMap())

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Composable
    private fun Lab() {
        val profiles = remember { mutableStateMapOf<String, ArenaLabProfile>().apply { putAll(loadProfiles()) } }
        var selected by remember { mutableStateOf(profiles[prefs.getString("selected", "")] ?: ArenaLabProfiles.generate(HeroClass.RANGER, 15)) }
        var editing by remember { mutableStateOf(true) }
        var revision by remember { mutableIntStateOf(0) }
        var phase by remember { mutableStateOf(BattleSessionPhase.IDLE) }
        val runtime = remember(selected.key, revision) { ArenaPanelRuntime() }
        val busy = phase != BattleSessionPhase.IDLE || runtime.overlayState != null || runtime.skillTreeVisible
        val focus = LocalFocusManager.current

        fun save(profile: ArenaLabProfile): Boolean {
            val updated = profiles.toMap() + (profile.key to profile)
            val saved = prefs.edit().putString("profiles", codec.encodeToString(updated))
                .putString("selected", profile.key).commit()
            if (saved) { profiles[profile.key] = profile; selected = profile }
            else Toast.makeText(this, "설정을 저장하지 못했습니다. 다시 시도해 주세요.", Toast.LENGTH_SHORT).show()
            return saved
        }
        BackHandler(enabled = !editing && !busy) { editing = true; focus.clearFocus() }
        Column(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }.background(AqBackground).safeDrawingPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("결투장 QA", fontSize = 21.sp, color = AqGold, fontWeight = FontWeight.Bold)
                    Text("서버 연결 없음 · 출전 무제한", fontSize = 12.sp, color = AqMuted)
                }
                if (!editing) OutlinedButton(onClick = { editing = true }, enabled = !busy) { Text("캐릭터 설정") }
            }
            if (editing) {
                key(selected.key, revision) { ProfileEditor(selected,
                    onSwitch = { old, heroClass, level ->
                        if (save(old)) {
                            save(profiles["${heroClass.name}:$level"] ?: ArenaLabProfiles.generate(heroClass, level))
                            revision++
                        }
                    },
                    onGenerated = { generated -> save(generated); revision++ },
                    onStart = { profile ->
                        if (save(profile)) { focus.clearFocus(); revision++; phase = BattleSessionPhase.IDLE; editing = false }
                    }) }
            } else {
                val state = remember(selected, revision) { ArenaLabProfiles.state(selected) }
                Text("${selected.heroClass.labelKo} · Lv.${selected.level} · 전투력 ${ArenaLabProfiles.combatPower(selected)}",
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
                    color = AqText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                key(selected.key, revision) {
                    BattlePanel(state = state, characterSlotId = selected.heroClass.ordinal + 1,
                        combatPower = ArenaLabProfiles.combatPower(selected),
                        heroPathEntry = heroPathArenaEntryModel(state), runtime = runtime,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        onSessionPhaseChanged = { phase = it },
                        onOpenRanking = { Toast.makeText(this@ArenaLabActivity,
                            "연습 기록은 이 앱에만 저장되며 서버 랭킹에 반영되지 않습니다.", Toast.LENGTH_SHORT).show() })
                }
            }
        }
    }

    @Composable
    private fun ColumnScope.ProfileEditor(
        profile: ArenaLabProfile,
        onSwitch: (ArenaLabProfile, HeroClass, Int) -> Unit,
        onGenerated: (ArenaLabProfile) -> Unit,
        onStart: (ArenaLabProfile) -> Unit,
    ) {
        var values by remember(profile) { mutableStateOf(profile.stats.values().map(Long::toString)) }
        var power by remember(profile) { mutableStateOf(profile.combatPowerOverride?.toString().orEmpty()) }
        var levelText by remember(profile.key) { mutableStateOf(profile.level.toString()) }
        var error by remember(profile) { mutableStateOf<String?>(null) }
        fun draft(): ArenaLabProfile? {
            val stats = ArenaLabProfiles.parseStats(values)
            val cp = power.takeIf { it.isNotBlank() }?.toLongOrNull()
            if (stats == null || (power.isNotBlank() && (cp == null || cp !in 1L..1_000_000L))) {
                error = "능력치는 1~10,000, HP·MP는 1~10,000,000 범위로 입력해 주세요."
                return null
            }
            return profile.copy(stats = stats, combatPowerOverride = cp)
        }
        fun switch(heroClass: HeroClass, level: Int) {
            draft()?.let { onSwitch(it, heroClass, level) }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("직업", color = AqText, fontWeight = FontWeight.Bold)
            HeroClass.entries.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { heroClass ->
                        FilterChip(selected = profile.heroClass == heroClass,
                            onClick = { switch(heroClass, profile.level) },
                            label = { Text(heroClass.labelKo) },
                            modifier = Modifier.weight(1f).testTag("lab-class-${heroClass.name}"))
                    }
                }
            }
            Text("레벨 · 실제 게임의 성장 규칙으로 계산", color = AqText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf(10, 15, 20, 25, 30, 50, 100).forEach { level ->
                    FilterChip(selected = profile.level == level,
                        onClick = { switch(profile.heroClass, level) }, label = { Text("Lv.$level") },
                        modifier = Modifier.testTag("lab-level-$level"))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField("레벨 직접 입력 (10~100)", levelText, { levelText = it }, Modifier.weight(1f), "lab-level-input")
                OutlinedButton(onClick = {
                    val next = levelText.toIntOrNull()
                    if (next == null || next !in ArenaLabProfiles.MIN_LEVEL..ArenaLabProfiles.MAX_LEVEL) error = "레벨은 10~100으로 입력해 주세요."
                    else switch(profile.heroClass, next)
                }) { Text("레벨 적용") }
            }
            Text("능력치 수정", color = AqText, fontWeight = FontWeight.Bold)
            listOf(listOf(0, 1, 2), listOf(3, 4, 5), listOf(6, 7)).forEach { indices ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    indices.forEach { index ->
                        NumberField(HeroStats.labels[index], values[index], { new ->
                            values = values.toMutableList().also { it[index] = new }; error = null
                        }, Modifier.weight(1f), "lab-stat-$index")
                    }
                }
            }
            NumberField("전투력 직접 지정 (선택)", power, { power = it; error = null }, Modifier.fillMaxWidth(), "lab-power")
            Text("비우면 능력치와 기본 장비로 자동 계산합니다. 실제 캐릭터와 비교할 때는 표시된 전투력을 입력하세요.",
                color = AqMuted, fontSize = 12.sp)
            OutlinedButton(onClick = { onGenerated(ArenaLabProfiles.generate(profile.heroClass, profile.level)) },
                modifier = Modifier.fillMaxWidth().testTag("lab-restore")) { Text("자동 생성 능력치로 복원") }
            Text("직업·레벨별 능력치와 스킬 배분을 따로 보관합니다. 스킬 포인트와 습득 조건은 실제 앱과 같습니다.",
                color = AqMuted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
        }
        error?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
        Button(onClick = { draft()?.let(onStart) },
            modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 52.dp).testTag("lab-start")) {
            Text("적용하고 결투장 열기", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
    }

    @Composable
    private fun NumberField(label: String, value: String, onValue: (String) -> Unit,
        modifier: Modifier, tag: String) {
        OutlinedTextField(value = value, onValueChange = { next ->
            if (next.length <= 8 && next.all(Char::isDigit)) onValue(next)
        }, label = { Text(label, fontSize = 12.sp) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = modifier.testTag(tag))
    }
}
