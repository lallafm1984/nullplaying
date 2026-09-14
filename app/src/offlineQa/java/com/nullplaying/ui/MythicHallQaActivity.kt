package com.nullplaying.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nullplaying.BuildConfig
import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.engine.SimpleContent
import com.nullplaying.localization.GameLocalization
import com.nullplaying.localization.AppLanguage
import com.nullplaying.model.*
import com.nullplaying.remote.MythicDiscoverySnapshot

/** Preview-only fixture. No Internet permission, no production writes. */
class MythicHallQaActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(BuildConfig.DEBUG && !BuildConfig.REMOTE_SERVICES_ENABLED)
        val language = when (intent.getStringExtra("qa_language")) {
            "en" -> AppLanguage.ENGLISH
            "ja" -> AppLanguage.JAPANESE
            else -> AppLanguage.KOREAN
        }
        GameLocalization.setActiveLanguage(language)
        val engine = SimpleGameEngine()
        val rolled = engine.rollStats(71L, HeroClass.WARRIOR)
        val state = engine.newGame("별빛", HeroClass.WARRIOR, rolled.stats, rolled.nextSeed, 1_000L)
        val names = EquipmentSlot.entries.mapIndexed { index, slot ->
            "${SimpleContent.equipmentPrefixes(45L).last()} ${SimpleContent.equipmentBase(slot, 45L, HeroClass.entries[index], 0)} +5"
        }
        val players = when (language) {
            AppLanguage.KOREAN -> listOf("새벽을걷는자", "별빛", "푸른여명", "달그림자", "은빛순례자", "먼길의기록")
            AppLanguage.ENGLISH -> listOf("Dawnwalker", "Starlight", "AzureDawn", "Moonshade", "SilverPilgrim", "Wayfarer")
            AppLanguage.JAPANESE -> listOf("夜明けの旅人", "星明かり", "蒼い暁", "月影", "銀の巡礼者", "旅の記録")
        }
        val entries = (0 until 100).map { i -> MythicDiscovery("qa-$i", "qa-char-$i", players[i%6],
            HeroClass.entries[i%6], 45L + i, names[i%6], EquipmentSlot.entries[i%6], 240L + i*7,
            1_789_365_600_000L - i*3_600_000L) }
        val initialOpen = intent.getBooleanExtra("qa_open", false)
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides language) {
                AlarmQuestTheme {
                    var open by remember { mutableStateOf(initialOpen) }
                    Column(Modifier.fillMaxSize().background(AqBackground).systemBarsPadding()) {
                        AnimatedContent(open, modifier = Modifier.weight(1f), transitionSpec = {
                            slideInHorizontally(tween(RANKING_PAGE_ENTER_DURATION_MILLIS)) { rankingPageEnterOffset(targetState,it) }
                                .togetherWith(slideOutHorizontally(tween(RANKING_PAGE_EXIT_DURATION_MILLIS)) { rankingPageExitOffset(targetState,it) })
                        }, label = "mythic-qa") { shown ->
                            if (shown) MythicHallScreen(MythicDiscoverySnapshot(entries), onBack = { open = false })
                            else Column(Modifier.padding(16.dp)) { ItemsPanel(state) { open = true } }
                        }
                    }
                }
            }
        }
    }
}
