package com.nullplaying.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.nullplaying.BuildConfig
import com.nullplaying.engine.arena.ArenaSkillNodeKind
import com.nullplaying.engine.arena.ArenaProgressionRules
import com.nullplaying.engine.arena.ArenaSkillTreeCatalog
import com.nullplaying.engine.arena.ArenaSkillTreeRules
import com.nullplaying.engine.arena.ArenaSkillTreeState
import com.nullplaying.localization.AppLanguage
import com.nullplaying.model.HeroClass
import java.util.Locale

/**
 * Isolated arena skill-tree renderer. It opens no repository, preference, database, file, bridge, or
 * remote service. The default launch creates a deterministic, valid partial auto-allocation with
 * points left to exercise rank-up and reset interactions entirely in memory.
 */
class ArenaProgressionQaActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(BuildConfig.DEBUG && !BuildConfig.REMOTE_SERVICES_ENABLED)
        check(BuildConfig.SUPABASE_URL.isBlank() && BuildConfig.SUPABASE_PUBLISHABLE_KEY.isBlank())
        check(!BuildConfig.BATTLE_QA_BRIDGE_ENABLED)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        enterImmersiveMode()

        val language = AppLanguage.fromLanguageTag(
            intent.getStringExtra("language") ?: intent.getStringExtra("qa_language"),
        ) ?: AppLanguage.KOREAN
        val initialHeroClass = intent.getStringExtra("hero_class")?.uppercase(Locale.ROOT)?.let { key ->
            HeroClass.entries.firstOrNull { it.name == key }
        } ?: HeroClass.WARRIOR
        val arenaLevel = intent.getIntExtra("arena_level", DEFAULT_ARENA_LEVEL)
            .coerceIn(1, ArenaSkillTreeRules.maxArenaLevel)
        val defaultAllocatedPoints = minOf(DEFAULT_ALLOCATED_POINTS, (arenaLevel - 1).coerceAtLeast(1))
        val allocatedPoints = intent.getIntExtra("allocated_points", defaultAllocatedPoints)
            .coerceIn(0, arenaLevel)
        val seed = intent.getLongExtra("seed", DEFAULT_AUTO_ALLOCATE_SEED)
        val focusedRootSlot = intent.getStringExtra("qa_root_slot")?.uppercase(Locale.ROOT)
        val focusedRootRank = intent.getIntExtra("qa_root_rank", 0)
        val ownedAttackIdsByClass = HeroClass.entries.associateWith(::qaOwnedAttackIds)
        val initialStates = HeroClass.entries.associateWith { heroClass ->
            arenaProgressionQaInitialState(
                heroClass = heroClass,
                arenaLevel = arenaLevel,
                allocatedPoints = allocatedPoints,
                seed = seed,
                focusedRootSlot = focusedRootSlot.takeIf { heroClass == initialHeroClass },
                focusedRootRank = focusedRootRank.takeIf { heroClass == initialHeroClass } ?: 0,
                ownedAttackIds = requireNotNull(ownedAttackIdsByClass[heroClass]),
            )
        }
        initialStates.forEach { (heroClass, state) ->
            check(
                ArenaSkillTreeRules.validate(
                    state,
                    heroClass,
                    arenaLevel,
                    requireNotNull(ownedAttackIdsByClass[heroClass]),
                ),
            )
        }

        setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 1f),
                LocalAppLanguage provides language,
            ) {
                AlarmQuestTheme {
                    ArenaSkillTreeQaPreview(
                        initialHeroClass = initialHeroClass,
                        arenaLevel = arenaLevel,
                        ownedAttackIdsByClass = ownedAttackIdsByClass,
                        initialStates = initialStates,
                        seed = seed,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

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

    @Composable
    private fun ArenaSkillTreeQaPreview(
        initialHeroClass: HeroClass,
        arenaLevel: Int,
        ownedAttackIdsByClass: Map<HeroClass, Set<String>>,
        initialStates: Map<HeroClass, ArenaSkillTreeState>,
        seed: Long,
    ) {
        val language = LocalAppLanguage.current
        var selectedHeroClass by rememberSaveable { mutableStateOf(initialHeroClass) }
        val treeStates = remember(initialStates, seed) {
            mutableStateMapOf<HeroClass, ArenaSkillTreeState>().apply { putAll(initialStates) }
        }
        val ownedAttackIds = requireNotNull(ownedAttackIdsByClass[selectedHeroClass])
        val treeState = requireNotNull(treeStates[selectedHeroClass])
        var dialogShown by remember(seed) { mutableStateOf(true) }
        var rejectionKey by remember(selectedHeroClass) { mutableStateOf<String?>(null) }
        val treeView = ArenaSkillTreeRules.view(treeState, arenaLevel, ownedAttackIds)
        val model = arenaSkillTreeUiModel(
            treeView = treeView,
            unlocked = true,
            editingEnabled = true,
            language = language,
        )
        val ready = "ARENA_SKILL_TREE_QA_READY version=${ArenaSkillTreeRules.rulesVersion} " +
            "class=${selectedHeroClass.name} classes=${HeroClass.entries.size} level=$arenaLevel spent=${treeView.spentPoints} " +
            "available=${treeView.availablePoints} nodes=${treeView.nodes.size} revision=${treeState.revision} " +
            "valid=${treeView.valid} seed=$seed"
        LaunchedEffect(ready, rejectionKey) {
            Log.i(QA_LOG_TAG, "$ready rejected=${rejectionKey ?: "none"}")
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(AqBackground)
                .padding(horizontal = 16.dp, vertical = 24.dp)
                .testTag("arena-skill-tree-qa-host")
                .semantics { contentDescription = ready },
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "OFFLINE QA · Skill Setup ${ArenaSkillTreeRules.rulesVersion.substringAfterLast('-').uppercase(Locale.ROOT)}",
                color = AqGold,
                fontSize = 13.sp,
            )
            Text(
                qaCopy(
                    language,
                    "직업 버튼으로 6개 트리를 전환할 수 있습니다. 모든 변경은 메모리에만 남습니다.",
                    "Switch between all 6 class trees. Changes remain in memory only.",
                    "職業ボタンで6職のツリーを切り替えられます。変更はメモリ内だけに残ります。",
                ),
                color = AqMuted,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
            ArenaSkillTreeEntryCard(model = model) { dialogShown = true }
            rejectionKey?.let {
                Text(
                    qaCopy(
                        language,
                        "배분을 적용하지 못했습니다. 조건과 포인트를 확인하세요.",
                        "Allocation was rejected. Check requirements and points.",
                        "配分できませんでした。条件とポイントを確認してください。",
                    ),
                    modifier = Modifier.testTag("arena-skill-tree-qa-error"),
                    color = AqRed,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
            }
        }

        if (dialogShown) {
            ArenaSkillTreeDialog(
                model = model,
                onDismiss = { dialogShown = false },
                onRankUp = { nodeId ->
                    // Read the map at click time so rapid taps apply one rank each instead of
                    // replaying a stale composition snapshot before the next frame is drawn.
                    val latestState = requireNotNull(treeStates[selectedHeroClass])
                    val latestView = ArenaSkillTreeRules.view(latestState, arenaLevel, ownedAttackIds)
                    val node = latestView.nodes.firstOrNull { it.definition.id == nodeId }
                    val mutation = if (node == null) null else ArenaSkillTreeRules.allocate(
                        state = latestState,
                        heroClass = selectedHeroClass,
                        arenaLevel = arenaLevel,
                        ownedAttackIds = ownedAttackIds,
                        nodeId = nodeId,
                        targetRank = node.rank + 1,
                        editingEnabled = true,
                    )
                    val error = mutation?.error ?: if (mutation == null) "unknown_node" else null
                    val boundaryNoOp = isArenaSkillTreeBoundaryNoOp(error)
                    rejectionKey = error.takeUnless { boundaryNoOp }
                    if (mutation?.accepted == true) {
                        treeStates[selectedHeroClass] = mutation.state
                    } else if (!boundaryNoOp) {
                        Log.w(QA_LOG_TAG, "Rank-up rejected: ${rejectionKey.orEmpty()}")
                    }
                },
                onReset = {
                    val mutation = ArenaSkillTreeRules.reset(
                        state = treeState,
                        heroClass = selectedHeroClass,
                        editingEnabled = true,
                    )
                    rejectionKey = mutation.error
                    if (mutation.accepted) treeStates[selectedHeroClass] = mutation.state
                    else Log.w(QA_LOG_TAG, "Reset rejected: ${mutation.error.orEmpty()}")
                },
                onResetSkill = { nodeId ->
                    val latestState = requireNotNull(treeStates[selectedHeroClass])
                    val mutation = ArenaSkillTreeRules.resetNode(latestState, selectedHeroClass,
                        arenaLevel, ownedAttackIds, nodeId, editingEnabled = true)
                    rejectionKey = mutation.error
                    if (mutation.accepted) treeStates[selectedHeroClass] = mutation.state
                },
                reviewHeroClasses = HeroClass.entries,
                onReviewHeroClassSelected = { selectedHeroClass = it },
            )
        }
    }

    private fun qaOwnedAttackIds(heroClass: HeroClass): Set<String> = ArenaSkillTreeCatalog.forClass(heroClass)
        .filter { it.kind == ArenaSkillNodeKind.ATTACK }
        .mapTo(linkedSetOf()) { it.id }

    private fun qaCopy(language: AppLanguage, ko: String, en: String, ja: String): String = when (language) {
        AppLanguage.KOREAN -> ko
        AppLanguage.ENGLISH -> en
        AppLanguage.JAPANESE -> ja
    }

    private companion object {
        const val QA_LOG_TAG = "ARENA_SKILL_TREE_QA"
        const val DEFAULT_ARENA_LEVEL = 48
        const val DEFAULT_ALLOCATED_POINTS = 24
        const val DEFAULT_AUTO_ALLOCATE_SEED = 0x4152454E415633L
    }
}
