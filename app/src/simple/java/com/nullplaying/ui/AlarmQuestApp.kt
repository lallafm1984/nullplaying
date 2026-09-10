package com.nullplaying.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Backpack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nullplaying.data.SimpleGameRepository
import com.nullplaying.data.StartupPhase
import com.nullplaying.data.adventureQaTriggerBuildAllowed
import com.nullplaying.engine.LabyrinthProgression
import com.nullplaying.engine.HeroPathCatalog
import com.nullplaying.engine.SimpleGameEngine
import com.nullplaying.engine.SkillCatalog
import com.nullplaying.engine.SkillDefinition
import com.nullplaying.R
import com.nullplaying.BuildConfig
import com.nullplaying.model.AdventureEventResult
import com.nullplaying.model.AdventureEventRewardKind
import com.nullplaying.model.AdventurePhase
import com.nullplaying.model.AdventureRelationshipResult
import com.nullplaying.model.CombatPhase
import com.nullplaying.model.CompletedTaleRecord
import com.nullplaying.model.HeroClass
import com.nullplaying.model.HeroPathAllocationTarget
import com.nullplaying.model.HeroPathNodeType
import com.nullplaying.model.HeroStats
import com.nullplaying.model.InventoryItem
import com.nullplaying.model.LearnedSkill
import com.nullplaying.model.MonsterGrade
import com.nullplaying.model.ProjectionRelationshipStage
import com.nullplaying.model.ShopEquipmentOffer
import com.nullplaying.model.SimpleGameState
import com.nullplaying.model.TaleActState
import com.nullplaying.model.TaleKind
import com.nullplaying.ads.AdsConsentState
import com.nullplaying.ads.MobileAdsRuntimeState
import com.nullplaying.ads.actions
import com.nullplaying.notifications.GameNotificationPreferencesStore
import com.nullplaying.remote.AppAnnouncement
import com.nullplaying.remote.ArenaRankingLocalStanding
import com.nullplaying.remote.forCharacter
import com.nullplaying.localization.GameLanguageStore
import com.nullplaying.localization.AppLanguage
import com.nullplaying.remote.RemoteRankingSnapshot
import com.nullplaying.remote.AppUpdateNotice
import com.nullplaying.remote.ARENA_LIVE_SERVER_QA_APPLICATION_ID
import com.nullplaying.remote.SupabaseConnectionState
import com.nullplaying.remote.SupabaseGameService
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.rewarded.OnUserEarnedRewardListener
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardItem
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAd
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAdEventCallback
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID
import kotlin.math.PI
import kotlin.math.sin

internal enum class MenuTab(
    val label: String,
    val icon: ImageVector? = null,
    val iconResourceId: Int? = null,
) {
    MAIN("메인", Icons.Filled.Explore),
    CHARACTER("모험가", Icons.Filled.Person),
    // Retired route retained only to decode an old saved navigation state.
    CORRESPONDENCE(""),
    ITEMS("아이템", Icons.Filled.Backpack),
    BATTLE("결투장", iconResourceId = R.drawable.ic_swords),
    EQUIPMENT("장비", Icons.Filled.Shield),
    BAG("가방", Icons.Filled.Backpack),
    QUEST("퀘스트", Icons.Filled.Flag),
}

private enum class RankingPageKind {
    ADVENTURER,
    ARENA,
}

internal fun visibleMenuTabsForBuild(
    isDebugBuild: Boolean,
    serverMatchingEnabled: Boolean = BuildConfig.ARENA_SERVER_MATCHING_ENABLED,
): List<MenuTab> =
    if (battlePreviewMenuEnabled(isDebugBuild, serverMatchingEnabled)) {
        listOf(
            MenuTab.MAIN,
            MenuTab.CHARACTER,
            MenuTab.BATTLE,
            MenuTab.ITEMS,
            MenuTab.QUEST,
        )
    } else {
        listOf(
            MenuTab.MAIN,
            MenuTab.CHARACTER,
            MenuTab.EQUIPMENT,
            MenuTab.BAG,
            MenuTab.QUEST,
        )
    }

internal fun menuTabAfterBuildRedirect(
    selectedTab: MenuTab,
    isDebugBuild: Boolean,
    serverMatchingEnabled: Boolean = BuildConfig.ARENA_SERVER_MATCHING_ENABLED,
): MenuTab = if (selectedTab == MenuTab.CORRESPONDENCE) {
    MenuTab.MAIN
} else if (
    battlePreviewMenuEnabled(isDebugBuild, serverMatchingEnabled) &&
    selectedTab in setOf(MenuTab.EQUIPMENT, MenuTab.BAG)
) {
    MenuTab.ITEMS
} else {
    selectedTab
}

internal fun adventurePanelVisibleForBuild(
    selectedTab: MenuTab,
    isDebugBuild: Boolean,
    serverMatchingEnabled: Boolean = BuildConfig.ARENA_SERVER_MATCHING_ENABLED,
): Boolean = !(battlePreviewMenuEnabled(isDebugBuild, serverMatchingEnabled) && selectedTab == MenuTab.BATTLE)

internal val visibleMenuTabs = visibleMenuTabsForBuild(BuildConfig.DEBUG)

internal fun offlineAdventurePercent(progress: Float): Int =
    (progress.coerceIn(0f, 1f) * 100f).toInt()

internal fun offlineAdventureColor(percent: Int): Color = when (percent.coerceIn(0, 100)) {
    in 0..24 -> Color(0xFFFF5C6C)
    in 25..49 -> Color(0xFFF29A49)
    in 50..74 -> Color(0xFFE7C55A)
    else -> Color(0xFF8BCB84)
}

internal fun entrySceneUsesSharedBanner(scene: EntryScene): Boolean =
    scene != EntryScene.TITLE

internal enum class EntrySceneTransitionDirection {
    BACKWARD,
    FORWARD,
    FADE,
}

internal fun entrySceneTransitionDirection(
    initialScene: EntryScene,
    targetScene: EntryScene,
): EntrySceneTransitionDirection = when {
    initialScene == EntryScene.GAME && targetScene == EntryScene.ROSTER ->
        EntrySceneTransitionDirection.BACKWARD
    initialScene == EntryScene.ROSTER && targetScene == EntryScene.GAME ->
        EntrySceneTransitionDirection.FORWARD
    else -> EntrySceneTransitionDirection.FADE
}

internal fun entrySceneFor(@Suppress("UNUSED_PARAMETER") state: SimpleGameState): EntryScene =
    EntryScene.GAME

@Composable
fun AlarmQuestApp(
    repository: SimpleGameRepository,
    notificationPreferencesStore: GameNotificationPreferencesStore,
    gameLanguageStore: GameLanguageStore,
    supabaseGameService: SupabaseGameService,
    gameNow: () -> Long,
    onRetryGameInitialization: () -> Unit,
    mobileAdsReady: Boolean = false,
    adsConsentState: AdsConsentState = AdsConsentState(),
    mobileAdsRuntimeState: MobileAdsRuntimeState = MobileAdsRuntimeState.WAITING_FOR_CONSENT,
    onRetryAdsSetup: () -> Unit = {},
    onOpenPrivacyOptions: () -> Unit = {},
    onBeginRewardedAdSession: (String, Long) -> Boolean,
    onFinishRewardedAdSession: (String, Long) -> Boolean,
    onEarnedOfflineAdventureReward: (String, String) -> Unit,
) {
    val systemDensity = LocalDensity.current
    val context = LocalContext.current
    val appLanguage by gameLanguageStore.language.collectAsState()
    var updateNotice by remember { mutableStateOf<AppUpdateNotice?>(null) }
    var versionCheckPassed by remember { mutableStateOf(false) }
    var announcement by remember { mutableStateOf<AppAnnouncement?>(null) }
    LaunchedEffect(supabaseGameService) {
        versionCheckPassed = false
        announcement = null
        supabaseGameService.checkForAppUpdate()
            .onSuccess { notice ->
                updateNotice = notice
                versionCheckPassed = com.nullplaying.remote.appVersionAllowsAnnouncement(notice)
            }
            .onFailure {
                updateNotice = null
                versionCheckPassed = false
            }
    }
    LaunchedEffect(supabaseGameService, appLanguage, versionCheckPassed) {
        if (!versionCheckPassed) {
            announcement = null
            return@LaunchedEffect
        }
        announcement = null
        supabaseGameService.fetchAppAnnouncement(appLanguage)
            .onSuccess { announcement = it }
    }
    CompositionLocalProvider(
        LocalDensity provides Density(
            density = systemDensity.density,
            fontScale = 1f,
        ),
        LocalAppLanguage provides appLanguage,
    ) {
        AlarmQuestAppContent(
            repository,
            notificationPreferencesStore,
            gameLanguageStore,
            supabaseGameService,
            gameNow,
            onRetryGameInitialization,
            startupAnnouncement = if (updateNotice == null) announcement else null,
            startupDialogsBlocked = updateNotice != null,
            onStartupAnnouncementShown = supabaseGameService::markAppAnnouncementDisplayed,
            onDismissStartupAnnouncement = { announcement = null },
            mobileAdsReady = mobileAdsReady,
            adsConsentState = adsConsentState,
            mobileAdsRuntimeState = mobileAdsRuntimeState,
            onRetryAdsSetup = onRetryAdsSetup,
            onOpenPrivacyOptions = onOpenPrivacyOptions,
            onBeginRewardedAdSession = onBeginRewardedAdSession,
            onFinishRewardedAdSession = onFinishRewardedAdSession,
            onEarnedOfflineAdventureReward = onEarnedOfflineAdventureReward,
        )
        updateNotice?.let { notice ->
            AppUpdateDialog(
                notice = notice,
                onDismiss = { if (!notice.isRequired) updateNotice = null },
                onUpdate = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(notice.updateUrl)))
                    }.onFailure {
                        Toast.makeText(
                            context,
                            localized("업데이트 페이지를 열지 못했습니다"),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            )
        }
    }
}

@Composable
private fun AppUpdateDialog(
    notice: AppUpdateNotice,
    onDismiss: () -> Unit,
    onUpdate: () -> Unit,
) {
    val language = LocalAppLanguage.current
    val localizedTitle = if (language == com.nullplaying.localization.AppLanguage.KOREAN) {
        notice.title
    } else {
        localized("새 버전이 준비되었습니다")
    }
    val localizedMessage = if (language == com.nullplaying.localization.AppLanguage.KOREAN) {
        notice.message
    } else {
        localized("더 안정적인 모험을 위해 앱을 업데이트해 주세요.")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = localizedTitle,
                color = AqText,
                fontWeight = FontWeight.Black,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(localizedMessage, color = AqMuted)
                Text(
                    text = "새 버전 ${notice.latestVersionName}",
                    color = AqGold,
                    fontWeight = FontWeight.Bold,
                )
                if (notice.isRequired) {
                    Text(
                        text = "계속하려면 업데이트가 필요합니다.",
                        color = AqRed,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onUpdate,
                colors = ButtonDefaults.buttonColors(containerColor = AqGold),
            ) {
                Text("업데이트하기", color = Color(0xFF211808), fontWeight = FontWeight.Black)
            }
        },
        dismissButton = if (notice.isRequired) {
            null
        } else {
            {
                TextButton(onClick = onDismiss) {
                    Text("나중에", color = AqMuted)
                }
            }
        },
        containerColor = AqSurface,
    )
}

@Composable
private fun AlarmQuestAppContent(
    repository: SimpleGameRepository,
    notificationPreferencesStore: GameNotificationPreferencesStore,
    gameLanguageStore: GameLanguageStore,
    supabaseGameService: SupabaseGameService,
    gameNow: () -> Long,
    onRetryGameInitialization: () -> Unit,
    startupAnnouncement: AppAnnouncement?,
    startupDialogsBlocked: Boolean,
    onStartupAnnouncementShown: (AppAnnouncement) -> Unit,
    onDismissStartupAnnouncement: () -> Unit,
    mobileAdsReady: Boolean,
    adsConsentState: AdsConsentState,
    mobileAdsRuntimeState: MobileAdsRuntimeState,
    onRetryAdsSetup: () -> Unit,
    onOpenPrivacyOptions: () -> Unit,
    onBeginRewardedAdSession: (String, Long) -> Boolean,
    onFinishRewardedAdSession: (String, Long) -> Boolean,
    onEarnedOfflineAdventureReward: (String, String) -> Unit,
) {
    val snapshot by repository.snapshots.collectAsState()
    val context = LocalContext.current
    val whatsNewStore = remember(context) {
        com.nullplaying.remote.AppAnnouncementDisplayStore(context)
    }
    val whatsNew = whatsNewNotice(LocalAppLanguage.current)
    var whatsNewPending by remember {
        mutableStateOf(
            whatsNewNoticeEligible(BuildConfig.VERSION_CODE, isUpdatedInstallation(context)) &&
                whatsNewStore.shouldDisplay(whatsNew),
        )
    }
    val rosterCharacters = snapshot.characters.map { character ->
        CharacterRosterEntry(
            slotId = character.slotId,
            state = character.state,
            combatPower = repository.displayCombatPower(character.state),
            offlineAdventureProgress = repository.offlineAdventureFraction(character.state),
        )
    }
    val entryScope = rememberCoroutineScope()
    val deleteCharacterAndSyncRanking: suspend (Int) -> Boolean = { slotId ->
        val deleted = repository.deleteCharacter(slotId).isSuccess
        if (deleted) {
            supabaseGameService.syncPlayerNetworkProfileAfterRosterMutation(
                repository.snapshots.value,
            )
        }
        deleted
    }
    var initializeAttempt by remember { mutableIntStateOf(0) }
    var minimumLoadingFinished by remember { mutableStateOf(false) }
    val liveArenaQa = BuildConfig.APPLICATION_ID == ARENA_LIVE_SERVER_QA_APPLICATION_ID
    var entryScene by rememberSaveable {
        mutableStateOf(if (liveArenaQa) EntryScene.GAME else EntryScene.TITLE)
    }
    var gameSceneVisitId by rememberSaveable { mutableIntStateOf(0) }
    // Resume settlement temporarily removes GameScreen. Keep navigation above that loading
    // boundary, but reset it for a different character or an explicit roster re-entry.
    val selectedGameTab = rememberSaveable(snapshot.activeSlotId, gameSceneVisitId) {
        mutableStateOf(if (liveArenaQa) MenuTab.BATTLE else MenuTab.MAIN)
    }
    val arenaRuntime = remember(snapshot.activeSlotId, gameSceneVisitId) { ArenaPanelRuntime() }
    var pendingEnter by rememberSaveable { mutableStateOf(false) }
    val titleIntroClaimed = remember { ProcessTitleIntroGate.claim() }
    var playTitleIntro by remember {
        mutableStateOf(titleIntroClaimed && ValueAnimator.areAnimatorsEnabled())
    }

    LaunchedEffect(initializeAttempt) {
        if (initializeAttempt > 0) onRetryGameInitialization()
    }
    LaunchedEffect(initializeAttempt) {
        minimumLoadingFinished = false
        // Start the minimum duration after an actual loading frame has been presented. This
        // prevents a cold-start layout pass from consuming the whole loading duration.
        withFrameNanos { }
        delay(MINIMUM_LOADING_MILLIS)
        minimumLoadingFinished = true
    }

    val entryReady = snapshot.ready && minimumLoadingFinished
    LaunchedEffect(entryReady, pendingEnter, whatsNewPending) {
        if (entryReady && pendingEnter && !whatsNewPending) {
            pendingEnter = false
            entryScene = EntryScene.ROSTER
        }
    }

    when {
        snapshot.startupPhase == StartupPhase.FAILED -> StartupFailureScreen(
            message = snapshot.startupError ?: "모험 기록을 불러오지 못했습니다.",
            onRetry = {
                entryScene = EntryScene.TITLE
                pendingEnter = false
                minimumLoadingFinished = false
                initializeAttempt += 1
            }
        )
        entryScene != EntryScene.TITLE && !entryReady -> LoadingScreen(snapshot.startupPhase)
        else -> Column(modifier = Modifier.fillMaxSize().background(AqBackground)) {
            if (entrySceneUsesSharedBanner(entryScene)) {
                StandardBannerAd(mobileAdsReady = mobileAdsReady)
            }
            AnimatedContent(
                targetState = entryScene,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    val enterDuration = if (ValueAnimator.areAnimatorsEnabled()) 240 else 0
                    val exitDuration = if (ValueAnimator.areAnimatorsEnabled()) 200 else 0
                    val enterFade = fadeIn(
                        tween(durationMillis = enterDuration, easing = FastOutSlowInEasing),
                    )
                    val exitFade = fadeOut(
                        tween(durationMillis = exitDuration, easing = FastOutSlowInEasing),
                    )
                    when (entrySceneTransitionDirection(initialState, targetState)) {
                        EntrySceneTransitionDirection.BACKWARD ->
                            (slideInHorizontally(
                                animationSpec = tween(
                                    durationMillis = enterDuration,
                                    easing = FastOutSlowInEasing,
                                ),
                                initialOffsetX = { width -> -width / 12 },
                            ) + enterFade).togetherWith(
                                slideOutHorizontally(
                                    animationSpec = tween(
                                        durationMillis = exitDuration,
                                        easing = FastOutSlowInEasing,
                                    ),
                                    targetOffsetX = { width -> width / 10 },
                                ) + exitFade,
                            )
                        EntrySceneTransitionDirection.FORWARD ->
                            (slideInHorizontally(
                                animationSpec = tween(
                                    durationMillis = enterDuration,
                                    easing = FastOutSlowInEasing,
                                ),
                                initialOffsetX = { width -> width / 12 },
                            ) + enterFade).togetherWith(
                                slideOutHorizontally(
                                    animationSpec = tween(
                                        durationMillis = exitDuration,
                                        easing = FastOutSlowInEasing,
                                    ),
                                    targetOffsetX = { width -> -width / 10 },
                                ) + exitFade,
                            )
                        EntrySceneTransitionDirection.FADE ->
                            enterFade.togetherWith(exitFade)
                    }
                },
                label = "entry-scene-transition",
            ) { scene ->
                when (scene) {
                    EntryScene.TITLE -> TitleScene(
                        ready = entryReady,
                        pendingEnter = pendingEnter,
                        startupPhase = snapshot.startupPhase,
                        playIntro = playTitleIntro,
                        onIntroFinished = { playTitleIntro = false },
                        onEnterRequested = {
                            if (entryReady && !whatsNewPending) {
                                entryScene = EntryScene.ROSTER
                            } else {
                                pendingEnter = true
                            }
                        },
                    )
                    EntryScene.ROSTER -> CharacterRosterScreen(
                        characters = rosterCharacters,
                        unlockedCharacterSlotCount = snapshot.unlockedCharacterSlotCount,
                        onContinue = { slotId ->
                            entryScope.launch {
                                val selectedState = rosterCharacters
                                    .firstOrNull { it.slotId == slotId }
                                    ?.state
                                if (
                                    repository.selectCharacter(
                                        slotId = slotId,
                                        now = gameNow(),
                                    ).isSuccess
                                ) {
                                    gameSceneVisitId += 1
                                    entryScene = selectedState?.let(::entrySceneFor) ?: EntryScene.GAME
                                }
                            }
                        },
                        onCreate = { entryScene = EntryScene.CREATION },
                        onDelete = deleteCharacterAndSyncRanking,
                        onBack = { entryScene = EntryScene.TITLE },
                    )
                    EntryScene.CREATION -> CharacterCreation(
                        repository = repository,
                        gameNow = gameNow,
                        onBack = { entryScene = EntryScene.ROSTER },
                        onCreated = {
                            gameSceneVisitId += 1
                            entryScene = EntryScene.GAME
                        },
                    )
                    EntryScene.GAME -> {
                        val state = snapshot.state
                        if (state == null) {
                            CharacterRosterScreen(
                                characters = rosterCharacters,
                                unlockedCharacterSlotCount = snapshot.unlockedCharacterSlotCount,
                                onContinue = { slotId ->
                                    entryScope.launch {
                                        val selectedState = rosterCharacters
                                            .firstOrNull { it.slotId == slotId }
                                            ?.state
                                        if (
                                            repository.selectCharacter(
                                                slotId = slotId,
                                                now = gameNow(),
                                            ).isSuccess
                                        ) {
                                            gameSceneVisitId += 1
                                            entryScene = selectedState?.let(::entrySceneFor) ?: EntryScene.GAME
                                        }
                                    }
                                },
                                onCreate = { entryScene = EntryScene.CREATION },
                                onDelete = deleteCharacterAndSyncRanking,
                                onBack = { entryScene = EntryScene.TITLE },
                            )
                        } else {
                            GameScreen(
                                repository = repository,
                                state = state,
                                activeSlotId = snapshot.activeSlotId ?: 1,
                                presentationVisitId = gameSceneVisitId,
                                selectedTabState = selectedGameTab,
                                arenaRuntime = arenaRuntime,
                                notificationPreferencesStore = notificationPreferencesStore,
                                gameLanguageStore = gameLanguageStore,
                                supabaseGameService = supabaseGameService,
                                gameNow = gameNow,
                                mobileAdsReady = mobileAdsReady,
                                adsConsentState = adsConsentState,
                                mobileAdsRuntimeState = mobileAdsRuntimeState,
                                onRetryAdsSetup = onRetryAdsSetup,
                                onOpenPrivacyOptions = onOpenPrivacyOptions,
                                onBeginRewardedAdSession = onBeginRewardedAdSession,
                                onFinishRewardedAdSession = onFinishRewardedAdSession,
                                onEarnedOfflineAdventureReward = onEarnedOfflineAdventureReward,
                                onExitToRoster = { entryScene = EntryScene.ROSTER },
                            )
                        }
                    }
                }
            }
        }
    }
    val showWhatsNew = entryScene == EntryScene.TITLE && entryReady &&
        !playTitleIntro && !startupDialogsBlocked && whatsNewPending &&
        snapshot.startupPhase != StartupPhase.FAILED
    if (showWhatsNew) {
        AppAnnouncementDialog(
            announcement = whatsNew,
            explicitCloseOnly = true,
            onDismiss = {
                whatsNewStore.markDisplayed(whatsNew)
                whatsNewPending = false
            },
        )
    } else if (
        entryScene == EntryScene.TITLE &&
        snapshot.startupPhase != StartupPhase.FAILED &&
        !startupDialogsBlocked && !whatsNewPending &&
        startupAnnouncement != null
    ) {
        LaunchedEffect(startupAnnouncement.id) {
            onStartupAnnouncementShown(startupAnnouncement)
        }
        AppAnnouncementDialog(
            announcement = startupAnnouncement,
            onDismiss = onDismissStartupAnnouncement,
        )
    }
}

@Composable
private fun AppAnnouncementDialog(
    announcement: AppAnnouncement,
    onDismiss: () -> Unit,
    explicitCloseOnly: Boolean = false,
) {
    val language = LocalAppLanguage.current
    AlertDialog(
        onDismissRequest = { if (!explicitCloseOnly) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !explicitCloseOnly,
            dismissOnClickOutside = !explicitCloseOnly,
        ),
        title = {
            MaterialText(
                text = announcement.title,
                color = AqText,
                fontWeight = FontWeight.Black,
            )
        },
        text = {
            if (explicitCloseOnly) {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    announcement.message.split("\n\n").forEach { paragraph ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            MaterialText(
                                text = paragraph.substringBefore('\n'),
                                modifier = Modifier.semantics { heading() },
                                color = AqText,
                                fontSize = 14.sp,
                                lineHeight = 21.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            MaterialText(
                                text = paragraph.substringAfter('\n', ""),
                                color = AqMuted,
                                fontSize = 13.sp,
                                lineHeight = 20.sp,
                            )
                        }
                    }
                }
            } else {
                MaterialText(
                    text = announcement.message,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    color = AqMuted,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = AqGold),
            ) {
                MaterialText(
                    text = if (explicitCloseOnly) when (language) {
                        com.nullplaying.localization.AppLanguage.KOREAN -> "닫기"
                        com.nullplaying.localization.AppLanguage.ENGLISH -> "Close"
                        com.nullplaying.localization.AppLanguage.JAPANESE -> "閉じる"
                    } else when (language) {
                        com.nullplaying.localization.AppLanguage.KOREAN -> "확인"
                        com.nullplaying.localization.AppLanguage.ENGLISH -> "OK"
                        com.nullplaying.localization.AppLanguage.JAPANESE -> "確認"
                    },
                    color = Color(0xFF211808),
                    fontWeight = FontWeight.Black,
                )
            }
        },
        containerColor = AqSurface,
    )
}

@Composable
private fun LoadingScreen(phase: StartupPhase) {
    val status = when (phase) {
        StartupPhase.LOADING_RECORD -> "모험 기록을 불러오는 중"
        StartupPhase.SETTLING_OFFLINE -> "지난 시간의 모험을 계산하는 중"
        StartupPhase.SAVING_RESULT -> "모험 기록을 정리하는 중"
        StartupPhase.READY -> "모험을 시작하는 중"
        StartupPhase.FAILED -> "모험 기록을 확인하는 중"
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF24192E), AqBackground, Color(0xFF100C16)),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NullPlayingWordmark(
                modifier = Modifier
                    .widthIn(max = 228.dp)
                    .fillMaxWidth()
                    .height(50.dp),
                contentDescription = localized("널 플레이잉"),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "플레이하지 않아도, 모험은 진행 중.",
                color = AqMuted,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(34.dp))
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(99.dp)),
                color = AqGold,
                trackColor = AqSurfaceHigh,
            )
            Spacer(Modifier.height(14.dp))
            Text(status, color = AqMuted, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
        Text(
            text = "모험 기록을 안전하게 준비하고 있습니다",
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            color = AqMuted.copy(alpha = 0.72f),
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun StartupFailureScreen(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(AqBackground).padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = AqSurface),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("모험 기록 확인 필요", color = AqText, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Text(message, color = AqMuted, fontSize = 14.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AqGold),
                ) {
                    Text("다시 불러오기", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private const val MINIMUM_LOADING_MILLIS = 1_500L

internal const val MIN_ADVENTURER_NAME_LENGTH = 2
internal const val MAX_ADVENTURER_NAME_LENGTH = 8

internal fun limitAdventurerNameInput(value: String): String {
    val characterCount = value.codePointCount(0, value.length)
    if (characterCount <= MAX_ADVENTURER_NAME_LENGTH) return value
    val endIndex = value.offsetByCodePoints(0, MAX_ADVENTURER_NAME_LENGTH)
    return value.substring(0, endIndex)
}

internal fun normalizedAdventurerName(value: String): String = value.trim()

internal fun isValidAdventurerName(value: String): Boolean {
    val normalized = normalizedAdventurerName(value)
    val characterCount = normalized.codePointCount(0, normalized.length)
    return characterCount in MIN_ADVENTURER_NAME_LENGTH..MAX_ADVENTURER_NAME_LENGTH
}

@Composable
private fun CharacterCreation(
    repository: SimpleGameRepository,
    gameNow: () -> Long,
    onBack: () -> Unit,
    onCreated: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var selectedClass by remember { mutableStateOf(HeroClass.WARRIOR) }
    val firstRoll = remember { repository.rollStats(gameNow() xor System.nanoTime()) }
    var currentRoll by remember { mutableStateOf(firstRoll) }
    var creating by remember { mutableStateOf(false) }
    var creationError by remember { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val isKeyboardVisible = WindowInsets.ime.getBottom(density) > 0
    val scope = rememberCoroutineScope()
    val creationStats = repository.initialStatsForClass(currentRoll.stats, selectedClass)

    BackHandler(enabled = !creating, onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF24192E), AqBackground, Color(0xFF100C16)),
                ),
            ),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 20.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 8.dp, bottom = 104.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onBack,
                        enabled = !creating,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = localized("모험가 선택으로 돌아가기"),
                            tint = AqText,
                        )
                    }
                    Column(modifier = Modifier.padding(start = 4.dp)) {
                        Text("새로운 모험", color = AqGold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("모험가 생성", color = AqText, fontSize = 27.sp, fontWeight = FontWeight.Black)
                    }
                }
                Spacer(Modifier.height(18.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = limitAdventurerNameInput(it)
                        creationError = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("모험가 이름 (2~8자)") },
                    singleLine = true,
                    isError = creationError != null,
                    supportingText = creationError?.let { message ->
                        { Text(message) }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "직업 선택",
                    modifier = Modifier.fillMaxWidth(),
                    color = AqText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(9.dp))
                ClassGrid(selectedClass = selectedClass, onSelect = { selectedClass = it })
                Spacer(Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = AqSurface),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        StatSectionHeading(
                            title = "능력치 굴림",
                            titleFontSize = 16.sp,
                            trailingText = "합계 ${currentRoll.stats.values().take(6).sum()}",
                            enabled = !creating,
                        )
                        Spacer(Modifier.height(10.dp))
                        StatGrid(creationStats, heroClass = selectedClass)
                        Spacer(Modifier.height(10.dp))
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = {
                                    currentRoll = repository.rollStats(
                                        currentRoll.nextSeed,
                                        selectedClass,
                                    )
                                },
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .width((maxWidth - 8.dp) / 2),
                            ) { Text("다시 굴리기") }
                        }
                    }
                }
            }

            if (!isKeyboardVisible) {
                Button(
                    onClick = {
                        if (!isValidAdventurerName(name)) {
                            creationError = "이름은 2자 이상 8자 이하로 입력해 주세요."
                            return@Button
                        }
                        creating = true
                        creationError = null
                        focusManager.clearFocus()
                        scope.launch {
                            try {
                                repository.createCharacter(
                                    name = normalizedAdventurerName(name),
                                    heroClass = selectedClass,
                                    stats = creationStats,
                                    seed = currentRoll.nextSeed,
                                    now = gameNow(),
                                )
                                onCreated()
                            } catch (_: Exception) {
                                creating = false
                                creationError = "모험 기록을 저장하지 못했습니다. 다시 시도해 주세요."
                            }
                        }
                    },
                    enabled = name.isNotBlank() && !creating,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                        .height(54.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AqGold,
                        contentColor = CharacterCreationActionContent,
                        disabledContainerColor = CharacterCreationDisabledActionContainer,
                        disabledContentColor = CharacterCreationDisabledActionContent,
                    ),
                ) {
                    Text(
                        if (creating) "저장하는 중…" else "이 능력치로 모험 시작",
                        fontWeight = FontWeight.Black,
                    )
                }
            }
        }
    }
}

@Composable
private fun ClassGrid(selectedClass: HeroClass, onSelect: (HeroClass) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        HeroClass.entries.chunked(3).forEach { rowClasses ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                rowClasses.forEach { heroClass ->
                    val selected = heroClass == selectedClass
                    OutlinedButton(
                        onClick = { onSelect(heroClass) },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .semantics {
                                this.selected = selected
                                role = Role.RadioButton
                            },
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (selected) AqGold.copy(alpha = 0.18f) else Color.Transparent,
                            contentColor = if (selected) AqGold else AqMuted,
                        ),
                    ) { Text(heroClass.labelKo, maxLines = 1, fontSize = 13.sp) }
                }
            }
        }
    }
}

@Composable
private fun GameScreen(
    repository: SimpleGameRepository,
    state: SimpleGameState,
    activeSlotId: Int,
    presentationVisitId: Int,
    selectedTabState: MutableState<MenuTab>,
    arenaRuntime: ArenaPanelRuntime,
    notificationPreferencesStore: GameNotificationPreferencesStore,
    gameLanguageStore: GameLanguageStore,
    supabaseGameService: SupabaseGameService,
    gameNow: () -> Long,
    mobileAdsReady: Boolean,
    adsConsentState: AdsConsentState,
    mobileAdsRuntimeState: MobileAdsRuntimeState,
    onRetryAdsSetup: () -> Unit,
    onOpenPrivacyOptions: () -> Unit,
    onBeginRewardedAdSession: (String, Long) -> Boolean,
    onFinishRewardedAdSession: (String, Long) -> Boolean,
    onEarnedOfflineAdventureReward: (String, String) -> Unit,
    onExitToRoster: () -> Unit,
) {
    var selectedTab by selectedTabState
    var battleSessionPhase by remember(state.hero.name) {
        mutableStateOf(BattleSessionPhase.IDLE)
    }
    var rankingPage by rememberSaveable { mutableStateOf<RankingPageKind?>(null) }
    var arenaRankingStanding by remember(state.rankingCharacterId, state.hero.name) {
        mutableStateOf(
            ArenaRankingLocalStanding(
                characterId = state.rankingCharacterId,
                displayName = state.hero.name,
                heroClass = state.hero.heroClass,
                level = state.hero.level,
                score = BATTLE_START_SCORE,
                completedBattles = 0,
                wins = 0,
                losses = 0,
                draws = 0,
                observedAtEpochMillis = 0L,
            ),
        )
    }
    var showingSettings by rememberSaveable(activeSlotId, presentationVisitId) {
        mutableStateOf(false)
    }
    var showingRecentEvents by rememberSaveable(activeSlotId, presentationVisitId) {
        mutableStateOf(false)
    }
    var showingSkillEffectTest by remember { mutableStateOf(false) }
    var showingHeroPath by rememberSaveable(activeSlotId, presentationVisitId) {
        mutableStateOf(false)
    }
    var heroPathFilter by rememberSaveable(activeSlotId) { mutableStateOf(HeroPathFilter.ALL) }
    var openedHeroPathTokenId by rememberSaveable(activeSlotId, presentationVisitId) {
        mutableStateOf<String?>(null)
    }
    var deferredHeroPathTokenId by rememberSaveable(activeSlotId, presentationVisitId) {
        mutableStateOf<String?>(null)
    }
    var heroPathChoiceSubmitting by remember(activeSlotId) { mutableStateOf(false) }
    var heroPathDraftTraitIds by remember(activeSlotId) { mutableStateOf(emptySet<String>()) }
    var showingHeroPathDraftReview by remember(activeSlotId) { mutableStateOf(false) }
    var showingHeroPathResetConfirmation by remember(activeSlotId) { mutableStateOf(false) }
    var showingHeroPathExitConfirmation by remember(activeSlotId) { mutableStateOf(false) }
    var selectedHeroPathNodeId by remember(activeSlotId) { mutableStateOf<String?>(null) }
    val qaAdventureSequenceAvailable = adventureQaTriggerBuildAllowed(
        debug = BuildConfig.DEBUG,
        previewEnabled = BuildConfig.ADVENTURE_PREVIEW_ENABLED,
        remoteServicesEnabled = BuildConfig.REMOTE_SERVICES_ENABLED,
        applicationId = BuildConfig.APPLICATION_ID,
    )
    val context = LocalContext.current
    val arenaServerQaFixtureRequested = remember(context) {
        (context as? Activity)?.intent?.getBooleanExtra(
            ARENA_SERVER_MATCHING_QA_FIXTURE_EXTRA,
            false,
        ) == true
    }
    val arenaServerQaCandidateCount = remember(context) {
        (context as? Activity)?.intent?.getIntExtra(
            ARENA_SERVER_CANDIDATE_COUNT_QA_EXTRA,
            3,
        ) ?: 3
    }
    val arenaServerQaFixtureCreatedAt = remember(activeSlotId, presentationVisitId) {
        gameNow()
    }
    val arenaServerQaRoster = remember(
        arenaServerQaFixtureRequested,
        arenaServerQaCandidateCount,
        state.hero.level,
        state.rankingCharacterId,
        arenaServerQaFixtureCreatedAt,
    ) {
        arenaServerMatchingQaFixture(
            requested = arenaServerQaFixtureRequested,
            debugBuild = BuildConfig.DEBUG,
            remoteServicesEnabled = BuildConfig.REMOTE_SERVICES_ENABLED,
            state = state,
            nowEpochMillis = arenaServerQaFixtureCreatedAt,
            candidateCount = arenaServerQaCandidateCount,
        )
    }
    val battleLocalStateStore = remember(context.applicationContext) {
        BattleLocalStateStore(context.applicationContext)
    }
    val appLanguage = LocalAppLanguage.current
    val pathCopy: (String, String, String) -> String = { ko, en, ja ->
        when (appLanguage) {
            com.nullplaying.localization.AppLanguage.KOREAN -> ko
            com.nullplaying.localization.AppLanguage.ENGLISH -> en
            com.nullplaying.localization.AppLanguage.JAPANESE -> ja
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var offlineRewardedAd by remember { mutableStateOf<RewardedAd?>(null) }
    var offlineRewardedLoadState by remember { mutableStateOf(RewardedLoadState.WAITING) }
    var offlineRewardedLoadedAt by remember { mutableLongStateOf(-1L) }
    var offlineRewardedLoadGeneration by remember { mutableIntStateOf(0) }
    var offlineRewardedLoadRequestToken by remember { mutableIntStateOf(0) }
    var offlineRewardedRetryAttempt by remember { mutableIntStateOf(0) }
    var offlineRewardedLastLoadAttemptAt by remember { mutableLongStateOf(-1L) }
    var offlineRewardedLoadInFlight by remember { mutableStateOf(false) }
    var arenaRewardedAd by remember { mutableStateOf<RewardedAd?>(null) }
    var arenaRewardedLoadState by remember { mutableStateOf(RewardedLoadState.WAITING) }
    var arenaRewardedLoadedAt by remember { mutableLongStateOf(-1L) }
    var arenaRewardedLoadGeneration by remember { mutableIntStateOf(0) }
    var arenaRewardedLoadRequestToken by remember { mutableIntStateOf(0) }
    var arenaRewardedRetryAttempt by remember { mutableIntStateOf(0) }
    var arenaRewardedLastLoadAttemptAt by remember { mutableLongStateOf(-1L) }
    var arenaRewardedLoadInFlight by remember { mutableStateOf(false) }
    var arenaRewardPreloadEligible by remember(state.rankingCharacterId, activeSlotId) { mutableStateOf(false) }
    var rewardDialogBenefit by remember { mutableStateOf<RewardedBenefit?>(null) }
    var pendingArenaRefillCount by remember { mutableIntStateOf(BATTLE_ENTRY_CAPACITY) }
    var pendingArenaRewardIdentity by remember { mutableStateOf<String?>(null) }
    var arenaRewardedRefillGrant by remember { mutableStateOf<ArenaRewardedRefillGrant?>(null) }
    val showingRewardDialog = rewardDialogBenefit != null
    var appInForeground by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }

    LaunchedEffect(state.actionSequence, state.adventurePhase) {
        if (qaAdventureSequenceAvailable) {
            when (state.adventurePhase) {
                AdventurePhase.EVENT,
                AdventurePhase.EVENT_RESULT,
                -> {
                    val eventId = if (state.adventurePhase == AdventurePhase.EVENT) {
                        state.adventureJourney.pending?.eventId
                    } else {
                        state.adventureJourney.lastResult?.run?.eventId
                    }
                    Log.i(
                        "AdventureSequenceQA",
                        "APP_PHASE phase=${state.adventurePhase} event=$eventId " +
                            "durationMs=${state.actionEndsAt - state.actionStartedAt} sequence=${state.actionSequence}",
                    )
                }
                AdventurePhase.COMBAT -> if (state.monster.catalogId.startsWith("event:")) {
                    val eventId = state.monster.catalogId.removePrefix("event:")
                    if (state.combatPhase == CombatPhase.ATTACKING && state.monster.attacksCompleted == 1) {
                        Log.i(
                            "AdventureSequenceQA",
                            "EVENT_BATTLE_COMBAT event=$eventId " +
                                "grade=${state.monster.grade} monster=${state.monster.name} direct=true",
                        )
                    }
                }
                AdventurePhase.LOOTING -> state.adventureJourney.eventBattle?.result?.let { result ->
                    Log.i(
                        "AdventureSequenceQA",
                        "EVENT_BATTLE_REWARD event=${result.run.eventId} " +
                            "grade=${result.run.battleGrade} reward=${result.run.battleRewardKind} " +
                            "item=${result.itemName} gold=${result.goldAwarded} " +
                            "xp=${result.experienceAwarded} progress=${result.progressAdded} " +
                            "actualItems=${result.actualItemCount}",
                    )
                }
                else -> Unit
            }
        }
    }
    val offlineAdventureProgress = repository.offlineAdventureFraction(state)
    val offlineAdventureFull = repository.isOfflineAdventureFull(state)
    val recentEvents by remember(repository, activeSlotId) {
        repository.observeRecentAdventureEvents(activeSlotId)
    }.collectAsState(initial = emptyList())
    val newestRecentEventId = recentEvents.firstOrNull()?.id ?: 0L
    val unreadRecentEventCount = recentEvents.count {
        it.id > state.lastSeenRecentAdventureEventId
    }
    val pendingHeroPathToken = state.heroPath.milestoneTokens
        .firstOrNull { !it.resolved }
        ?.takeIf { state.heroPath.unspentPoints > 0L }
    val openedHeroPathToken = openedHeroPathTokenId?.let { tokenId ->
        state.heroPath.milestoneTokens.firstOrNull { it.tokenId == tokenId && !it.resolved }
    }
    DisposableEffect(lifecycleOwner, activeSlotId, presentationVisitId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                appInForeground = true
            } else if (event == Lifecycle.Event.ON_STOP) {
                appInForeground = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(selectedTab) {
        val redirectedTab = menuTabAfterBuildRedirect(selectedTab, BuildConfig.DEBUG)
        if (redirectedTab != selectedTab) selectedTab = redirectedTab
    }

    LaunchedEffect(showingRecentEvents, newestRecentEventId) {
        if (showingRecentEvents && newestRecentEventId > 0L) {
            repository.markRecentAdventureEventsSeen(
                eventId = newestRecentEventId,
                now = gameNow(),
            )
        }
    }

    // On character entry, warm the arena slot only if at most one ticket remains.
    // This is a local read; reward delivery still rechecks the trusted ticket ledger at show time.
    LaunchedEffect(state.rankingCharacterId, activeSlotId, state.hero.level >= 10L, state.lastSettledAt / 86_400_000L) {
        val identity = state.rankingCharacterId.ifBlank { "local-slot:$activeSlotId" }
        arenaRewardPreloadEligible = runCatching {
            val snapshot = refreshTrustedBattleEntrySnapshot(
                snapshot = battleLocalStateStore.load(identity),
                roster = state.publicPlayerRoster,
                deviceWallNowMillis = System.currentTimeMillis(),
                elapsedRealtimeMillis = SystemClock.elapsedRealtime(),
                bootCount = currentArenaBootCount(context.applicationContext),
            )
            arenaRewardedAdCanPreload(
                heroLevel = state.hero.level,
                refillsUsed = snapshot.rewardedRefillsUsed,
                dailyBattlesUsed = snapshot.dailyBattlesUsed,
                entriesRemaining = snapshot.entriesRemaining,
            )
        }.getOrDefault(false)
    }

    val offlineRewardedShouldLoad = rewardedAdShouldLoad(
        mobileAdsReady = mobileAdsReady && adsConsentState.canRequestAds,
        adUnitId = BuildConfig.REWARDED_AD_UNIT_ID,
        benefitEligible = !offlineAdventureFull,
    )

    val arenaRewardedShouldLoad = rewardedAdShouldLoad(
        mobileAdsReady = mobileAdsReady && adsConsentState.canRequestAds,
        adUnitId = BuildConfig.ARENA_REWARDED_AD_UNIT_ID,
        benefitEligible = arenaRewardPreloadEligible,
    )

    LaunchedEffect(offlineRewardedShouldLoad, offlineRewardedLoadGeneration, appInForeground) {
        if (offlineRewardedLoadState == RewardedLoadState.SHOWING) return@LaunchedEffect
        if (!offlineRewardedShouldLoad) {
            offlineRewardedLoadRequestToken += 1
            offlineRewardedLoadInFlight = false
            offlineRewardedAd = null
            offlineRewardedLoadedAt = -1L
            offlineRewardedLoadState = RewardedLoadState.WAITING
            offlineRewardedRetryAttempt = 0
            return@LaunchedEffect
        }
        // Preserve ready and in-flight ads across dialog closure or app pause.
        if (!appInForeground || offlineRewardedLoadInFlight) return@LaunchedEffect
        if (
            offlineRewardedAd != null &&
            rewardedAdCacheIsFresh(offlineRewardedLoadedAt, SystemClock.elapsedRealtime())
        ) {
            offlineRewardedLoadState = RewardedLoadState.READY
            return@LaunchedEffect
        }
        offlineRewardedAd = null
        offlineRewardedLoadedAt = -1L
        offlineRewardedLoadState = RewardedLoadState.LOADING
        val throttleDelay = rewardedAdLoadThrottleDelayMillis(
            offlineRewardedLastLoadAttemptAt,
            SystemClock.elapsedRealtime(),
        )
        if (throttleDelay > 0L) delay(throttleDelay)
        if (offlineRewardedAd != null && rewardedAdCacheIsFresh(offlineRewardedLoadedAt, SystemClock.elapsedRealtime())) {
            offlineRewardedLoadState = RewardedLoadState.READY
            return@LaunchedEffect
        }
        offlineRewardedLoadRequestToken += 1
        val requestToken = offlineRewardedLoadRequestToken
        offlineRewardedLoadInFlight = true
        offlineRewardedLastLoadAttemptAt = SystemClock.elapsedRealtime()
        try {
            RewardedAd.load(
                AdRequest.Builder(BuildConfig.REWARDED_AD_UNIT_ID).build(),
                object : AdLoadCallback<RewardedAd> {
                    override fun onAdLoaded(ad: RewardedAd) {
                        if (!rewardedAdCallbackIsCurrent(
                                requestToken,
                                offlineRewardedLoadRequestToken,
                            )
                        ) {
                            Log.d(REWARDED_AD_TAG, "Ignoring stale offline rewarded load success")
                            return
                        }
                        offlineRewardedLoadInFlight = false
                        Log.d(REWARDED_AD_TAG, "Offline standard rewarded ad loaded")
                        ad.setImmersiveMode(true)
                        offlineRewardedAd = ad
                        offlineRewardedLoadedAt = SystemClock.elapsedRealtime()
                        offlineRewardedLoadState = RewardedLoadState.READY
                        offlineRewardedRetryAttempt = 0
                    }

                    override fun onAdFailedToLoad(adError: LoadAdError) {
                        if (!rewardedAdCallbackIsCurrent(
                                requestToken,
                                offlineRewardedLoadRequestToken,
                            )
                        ) {
                            Log.d(REWARDED_AD_TAG, "Ignoring stale offline rewarded load failure")
                            return
                        }
                        offlineRewardedLoadInFlight = false
                        Log.w(REWARDED_AD_TAG, "Offline standard rewarded ad failed to load: $adError")
                        offlineRewardedAd = null
                        offlineRewardedLoadedAt = -1L
                        offlineRewardedLoadState = RewardedLoadState.LOAD_FAILED
                    }
                },
            )
        } catch (error: Exception) {
            offlineRewardedLoadInFlight = false
            offlineRewardedLoadState = RewardedLoadState.LOAD_FAILED
            Log.w(REWARDED_AD_TAG, "Offline rewarded request could not start", error)
        }
    }

    LaunchedEffect(offlineRewardedLoadInFlight, offlineRewardedLoadRequestToken, appInForeground) {
        if (!offlineRewardedLoadInFlight) return@LaunchedEffect
        val requestToken = offlineRewardedLoadRequestToken
        delay(rewardedAdLoadTimeoutRemainingMillis(
            offlineRewardedLastLoadAttemptAt, SystemClock.elapsedRealtime(),
        ))
        if (offlineRewardedLoadInFlight && rewardedAdCallbackIsCurrent(requestToken, offlineRewardedLoadRequestToken)) {
            // Offer retry without discarding a late success from this same request. A new
            // request or consent/eligibility loss invalidates the callback token instead.
            offlineRewardedLoadInFlight = false
            offlineRewardedLoadState = RewardedLoadState.LOAD_FAILED
            Log.w(REWARDED_AD_TAG, "Offline rewarded load timed out; retry available")
        }
    }

    LaunchedEffect(offlineRewardedLoadedAt, offlineRewardedShouldLoad) {
        if (offlineRewardedLoadedAt >= 0L && offlineRewardedShouldLoad) {
            val remaining = REWARDED_AD_CACHE_MAX_AGE_MILLIS -
                (SystemClock.elapsedRealtime() - offlineRewardedLoadedAt)
            if (remaining > 0L) delay(remaining)
            offlineRewardedLoadRequestToken += 1
            offlineRewardedAd = null
            offlineRewardedLoadedAt = -1L
            offlineRewardedLoadState = RewardedLoadState.LOADING
            offlineRewardedLoadGeneration += 1
        }
    }

    LaunchedEffect(offlineRewardedLoadState, offlineRewardedShouldLoad, appInForeground) {
        if (appInForeground && rewardedAdRetryAllowed(offlineRewardedLoadState, offlineRewardedShouldLoad)) {
            delay(rewardedAdRetryDelayMillis(offlineRewardedRetryAttempt))
            if (offlineRewardedShouldLoad) {
                offlineRewardedRetryAttempt += 1
                offlineRewardedLoadGeneration += 1
            }
        }
    }

    LaunchedEffect(arenaRewardedShouldLoad, arenaRewardedLoadGeneration, appInForeground) {
        if (arenaRewardedLoadState == RewardedLoadState.SHOWING) return@LaunchedEffect
        if (!arenaRewardedShouldLoad) {
            arenaRewardedLoadRequestToken += 1
            arenaRewardedLoadInFlight = false
            arenaRewardedAd = null
            arenaRewardedLoadedAt = -1L
            arenaRewardedLoadState = RewardedLoadState.WAITING
            arenaRewardedRetryAttempt = 0
            return@LaunchedEffect
        }
        // Preserve ready and in-flight ads across dialog closure or app pause.
        if (!appInForeground || arenaRewardedLoadInFlight) return@LaunchedEffect
        if (
            arenaRewardedAd != null &&
            rewardedAdCacheIsFresh(arenaRewardedLoadedAt, SystemClock.elapsedRealtime())
        ) {
            arenaRewardedLoadState = RewardedLoadState.READY
            return@LaunchedEffect
        }
        arenaRewardedAd = null
        arenaRewardedLoadedAt = -1L
        arenaRewardedLoadState = RewardedLoadState.LOADING
        val throttleDelay = rewardedAdLoadThrottleDelayMillis(
            arenaRewardedLastLoadAttemptAt,
            SystemClock.elapsedRealtime(),
        )
        if (throttleDelay > 0L) delay(throttleDelay)
        if (arenaRewardedAd != null && rewardedAdCacheIsFresh(arenaRewardedLoadedAt, SystemClock.elapsedRealtime())) {
            arenaRewardedLoadState = RewardedLoadState.READY
            return@LaunchedEffect
        }
        arenaRewardedLoadRequestToken += 1
        val requestToken = arenaRewardedLoadRequestToken
        arenaRewardedLoadInFlight = true
        arenaRewardedLastLoadAttemptAt = SystemClock.elapsedRealtime()
        try {
            RewardedAd.load(
                AdRequest.Builder(BuildConfig.ARENA_REWARDED_AD_UNIT_ID).build(),
                object : AdLoadCallback<RewardedAd> {
                    override fun onAdLoaded(ad: RewardedAd) {
                        if (!rewardedAdCallbackIsCurrent(requestToken, arenaRewardedLoadRequestToken)) {
                            Log.d(REWARDED_AD_TAG, "Ignoring stale Arena rewarded load success")
                            return
                        }
                        arenaRewardedLoadInFlight = false
                        Log.d(REWARDED_AD_TAG, "Arena standard rewarded ad loaded")
                        ad.setImmersiveMode(true)
                        arenaRewardedAd = ad
                        arenaRewardedLoadedAt = SystemClock.elapsedRealtime()
                        arenaRewardedLoadState = RewardedLoadState.READY
                        arenaRewardedRetryAttempt = 0
                    }

                    override fun onAdFailedToLoad(adError: LoadAdError) {
                        if (!rewardedAdCallbackIsCurrent(requestToken, arenaRewardedLoadRequestToken)) {
                            Log.d(REWARDED_AD_TAG, "Ignoring stale Arena rewarded load failure")
                            return
                        }
                        arenaRewardedLoadInFlight = false
                        Log.w(REWARDED_AD_TAG, "Arena standard rewarded ad failed to load: $adError")
                        arenaRewardedAd = null
                        arenaRewardedLoadedAt = -1L
                        arenaRewardedLoadState = RewardedLoadState.LOAD_FAILED
                    }
                },
            )
        } catch (error: Exception) {
            arenaRewardedLoadInFlight = false
            arenaRewardedLoadState = RewardedLoadState.LOAD_FAILED
            Log.w(REWARDED_AD_TAG, "Arena rewarded request could not start", error)
        }
    }

    LaunchedEffect(arenaRewardedLoadInFlight, arenaRewardedLoadRequestToken, appInForeground) {
        if (!arenaRewardedLoadInFlight) return@LaunchedEffect
        val requestToken = arenaRewardedLoadRequestToken
        delay(rewardedAdLoadTimeoutRemainingMillis(
            arenaRewardedLastLoadAttemptAt, SystemClock.elapsedRealtime(),
        ))
        if (arenaRewardedLoadInFlight && rewardedAdCallbackIsCurrent(requestToken, arenaRewardedLoadRequestToken)) {
            // Offer retry without discarding a late success from this same request. A new
            // request or consent/eligibility loss invalidates the callback token instead.
            arenaRewardedLoadInFlight = false
            arenaRewardedLoadState = RewardedLoadState.LOAD_FAILED
            Log.w(REWARDED_AD_TAG, "Arena rewarded load timed out; retry available")
        }
    }

    LaunchedEffect(arenaRewardedLoadedAt, arenaRewardedShouldLoad) {
        if (arenaRewardedLoadedAt >= 0L && arenaRewardedShouldLoad) {
            val remaining = REWARDED_AD_CACHE_MAX_AGE_MILLIS -
                (SystemClock.elapsedRealtime() - arenaRewardedLoadedAt)
            if (remaining > 0L) delay(remaining)
            arenaRewardedLoadRequestToken += 1
            arenaRewardedAd = null
            arenaRewardedLoadedAt = -1L
            arenaRewardedLoadState = RewardedLoadState.LOADING
            arenaRewardedLoadGeneration += 1
        }
    }

    LaunchedEffect(arenaRewardedLoadState, arenaRewardedShouldLoad, appInForeground) {
        if (appInForeground && rewardedAdRetryAllowed(arenaRewardedLoadState, arenaRewardedShouldLoad)) {
            delay(rewardedAdRetryDelayMillis(arenaRewardedRetryAttempt))
            if (arenaRewardedShouldLoad) {
                arenaRewardedRetryAttempt += 1
                arenaRewardedLoadGeneration += 1
            }
        }
    }

    LaunchedEffect(offlineRewardedLoadState, arenaRewardedLoadState) {
        while (
            offlineRewardedLoadState == RewardedLoadState.SHOWING ||
            arenaRewardedLoadState == RewardedLoadState.SHOWING
        ) {
            delay(REWARDED_SESSION_UI_RECOVERY_POLL_MILLIS)
            if (repository.isRewardAdInFlight()) continue
            if (offlineRewardedLoadState == RewardedLoadState.SHOWING) {
                offlineRewardedAd = null
                offlineRewardedLoadedAt = -1L
                offlineRewardedLoadState = RewardedLoadState.LOADING
                offlineRewardedLoadGeneration += 1
            }
            if (arenaRewardedLoadState == RewardedLoadState.SHOWING) {
                arenaRewardedAd = null
                arenaRewardedLoadedAt = -1L
                arenaRewardedLoadState = if (arenaRewardedShouldLoad) {
                    RewardedLoadState.LOADING
                } else {
                    RewardedLoadState.WAITING
                }
                arenaRewardedLoadGeneration += 1
            }
            break
        }
    }

    LaunchedEffect(offlineAdventureFull) {
        if (offlineAdventureFull && rewardDialogBenefit == RewardedBenefit.OFFLINE_ADVENTURE) {
            rewardDialogBenefit = null
        }
    }

    LaunchedEffect(repository) {
        while (true) {
            delay(90L)
            repository.tick(gameNow(), SystemClock.elapsedRealtime())
        }
    }

    val showRewardedAd: (RewardedBenefit) -> RewardedShowAttempt = showRewardedAd@{ benefit ->
        val activity = context as? Activity
        val loadedAd = when (benefit) {
            RewardedBenefit.OFFLINE_ADVENTURE -> offlineRewardedAd
            RewardedBenefit.ARENA_TICKETS -> arenaRewardedAd
        }
        val loadedAt = when (benefit) {
            RewardedBenefit.OFFLINE_ADVENTURE -> offlineRewardedLoadedAt
            RewardedBenefit.ARENA_TICKETS -> arenaRewardedLoadedAt
        }
        val arenaIdentity = pendingArenaRewardIdentity
        val benefitAvailable = when (benefit) {
            RewardedBenefit.OFFLINE_ADVENTURE -> !offlineAdventureFull
            RewardedBenefit.ARENA_TICKETS -> arenaIdentity != null &&
                battleLocalStateStore.rewardedBattleEntryRefillAvailableTrusted(
                    identity = arenaIdentity,
                    roster = state.publicPlayerRoster,
                    deviceWallNowMillis = System.currentTimeMillis(),
                    elapsedRealtimeMillis = SystemClock.elapsedRealtime(),
                    bootCount = currentArenaBootCount(context.applicationContext),
                )
        }
        if (!benefitAvailable) {
            return@showRewardedAd RewardedShowAttempt.ELIGIBILITY_LOST
        }
        if (
            activity == null ||
            loadedAd == null ||
            !mobileAdsReady ||
            !appInForeground ||
            !adsConsentState.canRequestAds ||
            !rewardedAdCacheIsFresh(loadedAt, SystemClock.elapsedRealtime())
        ) {
            if (loadedAd != null) {
                when (benefit) {
                    RewardedBenefit.OFFLINE_ADVENTURE -> offlineRewardedLoadGeneration += 1
                    RewardedBenefit.ARENA_TICKETS -> arenaRewardedLoadGeneration += 1
                }
            }
            return@showRewardedAd RewardedShowAttempt.NOT_READY
        }
        val requestId = UUID.randomUUID().toString()
        val showStartedAt = SystemClock.elapsedRealtime()
        if (!onBeginRewardedAdSession(requestId, showStartedAt)) {
            return@showRewardedAd RewardedShowAttempt.NOT_READY
        }
        val rewardedCharacterId = state.rankingCharacterId
        when (benefit) {
            RewardedBenefit.OFFLINE_ADVENTURE -> {
                offlineRewardedLoadRequestToken += 1
                offlineRewardedLoadState = RewardedLoadState.SHOWING
                offlineRewardedAd = null
                offlineRewardedLoadedAt = -1L
            }
            RewardedBenefit.ARENA_TICKETS -> {
                arenaRewardedLoadRequestToken += 1
                arenaRewardedLoadState = RewardedLoadState.SHOWING
                arenaRewardedAd = null
                arenaRewardedLoadedAt = -1L
            }
        }
        return@showRewardedAd runCatching {
            loadedAd.adEventCallback = object : RewardedAdEventCallback {
                override fun onAdDismissedFullScreenContent() {
                    if (!onFinishRewardedAdSession(requestId, SystemClock.elapsedRealtime())) return
                    when (benefit) {
                        RewardedBenefit.OFFLINE_ADVENTURE -> {
                            offlineRewardedAd = null
                            offlineRewardedLoadedAt = -1L
                            offlineRewardedLoadState = RewardedLoadState.LOADING
                            offlineRewardedLoadGeneration += 1
                        }
                        RewardedBenefit.ARENA_TICKETS -> {
                            arenaRewardedAd = null
                            arenaRewardedLoadedAt = -1L
                            arenaRewardedLoadState = RewardedLoadState.WAITING
                            arenaRewardedLoadGeneration += 1
                        }
                    }
                }

                override fun onAdFailedToShowFullScreenContent(
                    fullScreenContentError: FullScreenContentError,
                ) {
                    if (!onFinishRewardedAdSession(requestId, SystemClock.elapsedRealtime())) return
                    Log.w(REWARDED_AD_TAG, "Standard rewarded ad failed to show: $fullScreenContentError")
                    when (benefit) {
                        RewardedBenefit.OFFLINE_ADVENTURE -> {
                            offlineRewardedAd = null
                            offlineRewardedLoadedAt = -1L
                            offlineRewardedLoadState = RewardedLoadState.SHOW_FAILED
                        }
                        RewardedBenefit.ARENA_TICKETS -> {
                            arenaRewardedAd = null
                            arenaRewardedLoadedAt = -1L
                            arenaRewardedLoadState = RewardedLoadState.SHOW_FAILED
                        }
                    }
                }
            }
            loadedAd.show(
                activity,
                object : OnUserEarnedRewardListener {
                    override fun onUserEarnedReward(rewardItem: RewardItem) {
                        Log.d(
                            REWARDED_AD_TAG,
                            "Reward earned: ${rewardItem.amount} ${rewardItem.type}",
                        )
                        when (benefit) {
                            RewardedBenefit.OFFLINE_ADVENTURE -> {
                                onEarnedOfflineAdventureReward(rewardedCharacterId, requestId)
                            }
                            RewardedBenefit.ARENA_TICKETS -> {
                                battleLocalStateStore.applyRewardedBattleEntryRefillTrusted(
                                    identity = requireNotNull(arenaIdentity),
                                    roster = state.publicPlayerRoster,
                                    deviceWallNowMillis = System.currentTimeMillis(),
                                    elapsedRealtimeMillis = SystemClock.elapsedRealtime(),
                                    bootCount = currentArenaBootCount(context.applicationContext),
                                    requestId = requestId,
                                )
                                arenaRewardedRefillGrant = ArenaRewardedRefillGrant(
                                    identity = requireNotNull(arenaIdentity),
                                    requestId = requestId,
                                )
                            }
                        }
                    }
                },
            )
        }.fold(
            onSuccess = { RewardedShowAttempt.SHOWN },
            onFailure = { error ->
                onFinishRewardedAdSession(requestId, SystemClock.elapsedRealtime())
                Log.w(REWARDED_AD_TAG, "Standard rewarded ad show call failed", error)
                when (benefit) {
                    RewardedBenefit.OFFLINE_ADVENTURE ->
                        offlineRewardedLoadState = RewardedLoadState.SHOW_FAILED
                    RewardedBenefit.ARENA_TICKETS ->
                        arenaRewardedLoadState = RewardedLoadState.SHOW_FAILED
                }
                RewardedShowAttempt.NOT_READY
            },
        )
    }

    val retryRewardedAd: (RewardedBenefit) -> Unit = { benefit ->
        when (benefit) {
            RewardedBenefit.OFFLINE_ADVENTURE -> {
                if (rewardedAdRetryAllowed(offlineRewardedLoadState, offlineRewardedShouldLoad)) {
                    offlineRewardedAd = null
                    offlineRewardedLoadedAt = -1L
                    offlineRewardedLoadState = RewardedLoadState.LOADING
                    offlineRewardedRetryAttempt = 0
                    offlineRewardedLoadGeneration += 1
                }
            }
            RewardedBenefit.ARENA_TICKETS -> {
                if (rewardedAdRetryAllowed(arenaRewardedLoadState, arenaRewardedShouldLoad)) {
                    arenaRewardedAd = null
                    arenaRewardedLoadedAt = -1L
                    arenaRewardedLoadState = RewardedLoadState.LOADING
                    arenaRewardedRetryAttempt = 0
                    arenaRewardedLoadGeneration += 1
                }
            }
        }
    }

    val combatPower = repository.displayCombatPower(state)
    val supabaseConnection by supabaseGameService.connectionState.collectAsState()
    val remoteRanking by supabaseGameService.rankingSnapshot.collectAsState()
    val rankingError by supabaseGameService.rankingError.collectAsState()
    val remoteArenaRanking by supabaseGameService.arenaRankingSnapshot.collectAsState()
    val arenaRankingError by supabaseGameService.arenaRankingError.collectAsState()
    val rankingRefreshPolicy by supabaseGameService.rankingRefreshPolicy.collectAsState()
    val rankingUiState: RankingUiState = remember(
        supabaseConnection,
        remoteRanking,
        rankingError,
        state.rankingCharacterId,
        state.hero.name,
        state.hero.heroClass,
        state.hero.level,
        combatPower,
    ) {
        when {
            supabaseConnection is SupabaseConnectionState.Disabled ->
                RankingUiState.Empty("아직 집계된 순위가 없습니다")
            remoteRanking != null -> RankingUiState.Content(
                requireNotNull(remoteRanking).toUiSnapshot(
                    playerCharacterId = state.rankingCharacterId,
                    playerName = state.hero.name,
                    playerClass = state.hero.heroClass,
                    playerLevel = state.hero.level,
                    playerCombatPower = combatPower,
                ).withLocalPlayerPower(
                    characterId = state.rankingCharacterId,
                    displayName = state.hero.name,
                    heroClass = state.hero.heroClass,
                    level = state.hero.level,
                    combatPower = combatPower,
                    now = gameNow(),
                ),
            ).let { content ->
                rankingError?.let { RankingUiState.Error(it, content.snapshot) } ?: content
            }
            rankingError != null -> RankingUiState.Error(requireNotNull(rankingError))
            else -> RankingUiState.Loading
        }
    }
    LaunchedEffect(rankingPage, state.rankingCharacterId) {
        when (rankingPage) {
            RankingPageKind.ADVENTURER -> supabaseGameService.fetchRanking(state.rankingCharacterId)
            RankingPageKind.ARENA -> supabaseGameService.fetchArenaRanking(state.rankingCharacterId)
            null -> Unit
        }
    }
    LaunchedEffect(
        rankingPage,
        state.rankingCharacterId,
        remoteArenaRanking?.snapshotId,
        remoteArenaRanking?.nextSettlementAtEpochMillis,
        remoteArenaRanking?.nextCheckAtEpochMillis,
        rankingRefreshPolicy,
    ) {
        if (rankingPage != RankingPageKind.ARENA) return@LaunchedEffect
        val snapshot = remoteArenaRanking?.forCharacter(state.rankingCharacterId)
            ?: return@LaunchedEffect
        val waitMillis = supabaseGameService.nextArenaRankingRefreshDelayMillis(snapshot)
            ?: return@LaunchedEffect
        delay(waitMillis.coerceAtLeast(1L))
        supabaseGameService.fetchArenaRanking(state.rankingCharacterId)
    }
    val rankingTransition = updateTransition(
        targetState = rankingPage,
        label = "ranking-page-transition",
    )
    BackHandler(
        enabled = rankingPage == null &&
            rankingTransition.currentState == null &&
            !showingSettings &&
            !showingSkillEffectTest &&
            !showingRecentEvents &&
            !showingRewardDialog &&
            openedHeroPathTokenId == null &&
            !showingHeroPath &&
            !arenaRuntime.skillTreeVisible &&
            battleSessionPhase != BattleSessionPhase.IN_BATTLE,
        onBack = onExitToRoster,
    )
    BackHandler(enabled = openedHeroPathTokenId != null) {
        openedHeroPathTokenId = null
    }
    BackHandler(enabled = showingHeroPath && openedHeroPathTokenId == null) {
        when {
            selectedHeroPathNodeId != null -> selectedHeroPathNodeId = null
            heroPathDraftTraitIds.isNotEmpty() -> showingHeroPathExitConfirmation = true
            else -> showingHeroPath = false
        }
    }
    BackHandler(enabled = showingRecentEvents) {
        showingRecentEvents = false
    }
    BackHandler(enabled = showingSkillEffectTest) {
        showingSkillEffectTest = false
    }
    BackHandler(enabled = rankingPage != null || rankingTransition.currentState != null) {
        rankingPage = null
    }
    if (BuildConfig.DEBUG && showingSkillEffectTest) {
        SkillEffectTestScreen(
            baseState = state,
            offlineAdventureProgress = offlineAdventureProgress,
            offlineAdventureFull = offlineAdventureFull,
            onExit = { showingSkillEffectTest = false },
        )
        return
    }
    val submitHeroPathChoice: (String, String) -> Unit = { tokenId, offerId ->
        if (!heroPathChoiceSubmitting) {
            heroPathChoiceSubmitting = true
            val expectedRevision = state.heroPath.revision
            scope.launch {
                val status = repository.chooseHeroPath(
                    tokenId = tokenId,
                    offerId = offerId,
                    expectedRevision = expectedRevision,
                    now = gameNow(),
                )
                heroPathChoiceSubmitting = false
                if (status == com.nullplaying.model.HeroPathMutationStatus.APPLIED) {
                    openedHeroPathTokenId = null
                    deferredHeroPathTokenId = null
                } else {
                    Toast.makeText(
                        context,
                        pathCopy("선택을 저장하지 못했습니다.", "Could not save your choice.", "選択を保存できませんでした。"),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }
    val submitHeroPathDraft: () -> Unit = {
        if (
            !heroPathChoiceSubmitting &&
            heroPathDraftTraitIds.isNotEmpty() &&
            battleSessionPhase == BattleSessionPhase.IDLE
        ) {
            heroPathChoiceSubmitting = true
            val expectedRevision = state.heroPath.revision
            val selectedTraits = heroPathDraftTraitIds.toList().sorted()
            val committedTraits = state.heroPath.traits.associateBy { it.traitId }
            val targetNodeRanks = committedTraits
                .mapValues { (_, progress) -> progress.rank }
                .toMutableMap()
                .apply {
                    selectedTraits.forEach { nodeId ->
                        this[nodeId] = getOrDefault(nodeId, 0) + 1
                    }
                }
            val targetNodeChoices = committedTraits.values
                .mapNotNull { progress ->
                    progress.selectedChoiceId
                        .takeIf(String::isNotBlank)
                        ?.let { progress.traitId to it }
                }
                .toMap()
            val draftCoreNodeId = selectedTraits.firstOrNull { nodeId ->
                HeroPathCatalog.byNodeId[nodeId]?.nodeType == HeroPathNodeType.CORE
            }
            val target = HeroPathAllocationTarget(
                expectedRevision = expectedRevision,
                nodeRanks = targetNodeRanks,
                nodeChoices = targetNodeChoices,
                activeCoreNodeId = draftCoreNodeId ?: state.heroPath.activeCoreTraitId,
            )
            scope.launch {
                val status = repository.applyHeroPathAllocation(
                    target = target,
                    now = gameNow(),
                )
                heroPathChoiceSubmitting = false
                showingHeroPathDraftReview = false
                if (status == com.nullplaying.model.HeroPathMutationStatus.APPLIED) {
                    heroPathDraftTraitIds = emptySet()
                    openedHeroPathTokenId = null
                    deferredHeroPathTokenId = null
                } else {
                    Toast.makeText(context, pathCopy("선택을 저장하지 못했습니다.", "Could not save your choice.", "選択を保存できませんでした。"), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    val resetHeroPath: () -> Unit = {
        if (!heroPathChoiceSubmitting && battleSessionPhase == BattleSessionPhase.IDLE) {
            heroPathChoiceSubmitting = true
            val expectedRevision = state.heroPath.revision
            scope.launch {
                val status = repository.resetHeroPath(
                    expectedRevision = expectedRevision,
                    now = gameNow(),
                )
                heroPathChoiceSubmitting = false
                showingHeroPathResetConfirmation = false
                if (status == com.nullplaying.model.HeroPathMutationStatus.APPLIED ||
                    status == com.nullplaying.model.HeroPathMutationStatus.NOTHING_TO_RESET
                ) {
                    heroPathDraftTraitIds = emptySet()
                } else {
                    Toast.makeText(context, pathCopy("초기화하지 못했습니다.", "Could not reset traits.", "特性をリセットできませんでした。"), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    if (showingHeroPath) {
        val pathModel = remember(state.heroPath, state.hero.level, heroPathFilter, heroPathDraftTraitIds) {
            heroPathPanelModel(state, heroPathFilter, heroPathDraftTraitIds)
        }
        Box(modifier = Modifier.fillMaxSize().background(AqBackground)) {
            HeroPathPanel(
                model = pathModel,
                onBack = {
                    if (heroPathDraftTraitIds.isNotEmpty()) {
                        showingHeroPathExitConfirmation = true
                    } else {
                        showingHeroPath = false
                    }
                },
                onFilterSelected = { heroPathFilter = it },
                onNodeSelected = { node -> selectedHeroPathNodeId = node.id },
                onReviewDraft = { showingHeroPathDraftReview = true },
                onResetSelected = {
                    if (battleSessionPhase == BattleSessionPhase.IDLE) {
                        showingHeroPathResetConfirmation = true
                    } else {
                        Toast.makeText(
                            context,
                            pathCopy("전투가 끝난 뒤 초기화할 수 있습니다.", "Reset is available after the battle.", "戦闘終了後にリセットできます。"),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                editingEnabled = battleSessionPhase == BattleSessionPhase.IDLE,
            )
            val selectedNode = selectedHeroPathNodeId?.let { nodeId ->
                pathModel.nodes.firstOrNull { it.id == nodeId }
            }
            val selectedLane = selectedNode?.let { node ->
                pathModel.lanes.firstOrNull { it.id == node.laneId }
            }
            if (selectedNode != null && selectedLane != null) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(70f)
                        .background(AqBackground.copy(alpha = 0.24f))
                        .pointerInput(selectedNode.id) {
                            detectTapGestures(onTap = { selectedHeroPathNodeId = null })
                        }
                        .semantics {
                            dialog()
                            paneTitle = pathCopy("특성 상세", "Trait Details", "特性の詳細")
                        },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    HeroPathNodeDetailSheetContent(
                        node = selectedNode,
                        lane = selectedLane,
                        editingEnabled = battleSessionPhase == BattleSessionPhase.IDLE,
                        onToggleDraft = {
                            heroPathDraftTraitIds = if (selectedNode.id in heroPathDraftTraitIds) {
                                heroPathDraftTraitIds - selectedNode.id
                            } else if (
                                selectedNode.canDraft &&
                                heroPathDraftTraitIds.size.toLong() < state.heroPath.unspentPoints
                            ) {
                                heroPathDraftTraitIds + selectedNode.id
                            } else {
                                heroPathDraftTraitIds
                            }
                            selectedHeroPathNodeId = null
                        },
                        onDismiss = { selectedHeroPathNodeId = null },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = maxHeight * 0.88f)
                            .pointerInput(Unit) { detectTapGestures(onTap = {}) },
                    )
                }
            }
            if (showingHeroPathDraftReview) {
                AlertDialog(
                    onDismissRequest = { showingHeroPathDraftReview = false },
                    title = {
                        MaterialText(pathCopy("선택 검토", "Review Choices", "選択内容を確認"))
                    },
                    text = {
                        MaterialText(
                            pathCopy(
                                "초안 ${heroPathDraftTraitIds.size}건을 한 번에 확정할까요? 다음에 시작하는 결투부터 적용됩니다.",
                                "Confirm ${heroPathDraftTraitIds.size} drafted changes at once? They take effect in the next duel you start.",
                                "仮選択${heroPathDraftTraitIds.size}件をまとめて確定しますか？ 次に開始する決闘から適用されます。",
                            ),
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = submitHeroPathDraft, enabled = !heroPathChoiceSubmitting) {
                            MaterialText(
                                pathCopy(
                                    "${heroPathDraftTraitIds.size}건 일괄 확정",
                                    "Confirm ${heroPathDraftTraitIds.size} Changes",
                                    "${heroPathDraftTraitIds.size}件をまとめて確定",
                                ),
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showingHeroPathDraftReview = false }) {
                            MaterialText(pathCopy("취소", "Cancel", "キャンセル"))
                        }
                    },
                )
            }
            if (showingHeroPathResetConfirmation) {
                AlertDialog(
                    onDismissRequest = { showingHeroPathResetConfirmation = false },
                    title = {
                        MaterialText(pathCopy("전체 초기화", "Reset All Traits", "全特性をリセット"))
                    },
                    text = {
                        MaterialText(
                            pathCopy(
                                "습득한 ${state.heroPath.traits.size}개 특성을 초기화하고 ${state.heroPath.spentPoints}포인트를 돌려받습니다. 다음에 시작하는 결투부터 적용됩니다.",
                                "Reset ${state.heroPath.traits.size} traits and refund ${state.heroPath.spentPoints} points. The reset takes effect in the next duel you start.",
                                "${state.heroPath.traits.size}個の特性をリセットし、${state.heroPath.spentPoints}ポイントを返還します。次に開始する決闘から適用されます。",
                            ),
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = resetHeroPath, enabled = !heroPathChoiceSubmitting) {
                            MaterialText(pathCopy("포인트 전부 반환", "Refund All Points", "全ポイントを返還"))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showingHeroPathResetConfirmation = false }) {
                            MaterialText(pathCopy("취소", "Cancel", "キャンセル"))
                        }
                    },
                )
            }
            if (showingHeroPathExitConfirmation) {
                AlertDialog(
                    onDismissRequest = { showingHeroPathExitConfirmation = false },
                    title = { MaterialText(pathCopy("확정하지 않은 변경", "Unconfirmed Changes", "未確定の変更")) },
                    text = {
                        MaterialText(
                            pathCopy(
                                "초안 ${heroPathDraftTraitIds.size}개가 아직 확정되지 않았습니다. 초안을 유지하면 나중에 이어서 편집할 수 있습니다.",
                                "${heroPathDraftTraitIds.size} changes are not confirmed. Keep them to continue editing later.",
                                "${heroPathDraftTraitIds.size}件の変更はまだ確定されていません。保留すると後で編集を続けられます。",
                            ),
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showingHeroPathExitConfirmation = false
                                showingHeroPath = false
                            },
                        ) {
                            MaterialText(pathCopy("초안 유지", "Keep Draft", "仮選択を保留"))
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                heroPathDraftTraitIds = emptySet()
                                showingHeroPathExitConfirmation = false
                                showingHeroPath = false
                            },
                        ) {
                            MaterialText(pathCopy("변경 폐기", "Discard Changes", "変更を破棄"))
                        }
                    },
                )
            }
        }
        return
    }
    val gameScreenModifier = Modifier
        .fillMaxSize()
        .background(AqBackground)
    Box(modifier = gameScreenModifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            rankingTransition.AnimatedContent(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    val enterOffset: (Int) -> Int = { width ->
                        rankingPageEnterOffset(targetState != null, width)
                    }
                    val exitOffset: (Int) -> Int = { width ->
                        rankingPageExitOffset(targetState != null, width)
                    }
                    slideInHorizontally(
                        animationSpec = tween(
                            durationMillis = RANKING_PAGE_ENTER_DURATION_MILLIS,
                            easing = FastOutSlowInEasing,
                        ),
                        initialOffsetX = enterOffset,
                    ).togetherWith(
                        slideOutHorizontally(
                            animationSpec = tween(
                                durationMillis = RANKING_PAGE_EXIT_DURATION_MILLIS,
                                easing = FastOutSlowInEasing,
                            ),
                            targetOffsetX = exitOffset,
                        ),
                    )
                },
            ) { visibleRankingPage ->
                when (visibleRankingPage) {
                RankingPageKind.ADVENTURER -> Column(
                    modifier = Modifier.fillMaxSize().background(AqBackground),
                ) {
                    RankingScreen(
                        uiState = rankingUiState,
                        refreshPolicy = rankingRefreshPolicy,
                        onBack = { rankingPage = null },
                        onRetry = {
                            scope.launch {
                                supabaseGameService.fetchRanking(
                                    state.rankingCharacterId,
                                    forceRefresh = true,
                                )
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                RankingPageKind.ARENA -> BattleRankingScreen(
                    localStanding = arenaRankingStanding,
                    remoteSnapshot = remoteArenaRanking?.forCharacter(state.rankingCharacterId),
                    errorMessage = arenaRankingError,
                    refreshPolicy = rankingRefreshPolicy,
                    modifier = Modifier.fillMaxSize(),
                    onBack = { rankingPage = null },
                    onRetry = {
                        scope.launch { supabaseGameService.fetchArenaRanking(state.rankingCharacterId) }
                    },
                )
                    null -> {
                        if (showingSettings) {
                    GameSettingsScreen(
                        state = state,
                        notificationPreferencesStore = notificationPreferencesStore,
                        gameLanguageStore = gameLanguageStore,
                        onBack = { showingSettings = false },
                        onExitToRoster = onExitToRoster,
                        adsSetupNeedsRecovery = adsSetupNeedsRecovery(
                            adsConsentState,
                            mobileAdsRuntimeState,
                        ),
                        privacyOptionsRequired =
                            adsConsentState.actions().showPrivacyOptions,
                        onRetryAdsSetup = onRetryAdsSetup,
                        onOpenPrivacyOptions = onOpenPrivacyOptions,
                    )
                } else {
                    val contentTab = selectedTab.takeIf { it in visibleMenuTabs } ?: MenuTab.MAIN
                    Column(modifier = Modifier.fillMaxSize().background(AqBackground)) {
                        if (!arenaRuntime.skillTreeVisible) {
                            OfflineAdventureStrip(
                                progress = offlineAdventureProgress,
                                isFull = offlineAdventureFull,
                                onRewardClick = {
                                    rewardDialogBenefit = RewardedBenefit.OFFLINE_ADVENTURE
                                },
                            )
                            HeroHeader(
                            state = state,
                            combatPower = combatPower,
                            ranking = rankingHeaderPresentation(rankingUiState),
                            onLevelClick = if (
                                BuildConfig.DEBUG &&
                                (contentTab != MenuTab.BATTLE || battleSessionPhase == BattleSessionPhase.IDLE)
                            ) {
                                { showingSkillEffectTest = true }
                            } else {
                                null
                            },
                            onOpenSettings = if (
                                contentTab != MenuTab.BATTLE ||
                                battleSessionPhase == BattleSessionPhase.IDLE
                            ) {
                                {
                                    rankingPage = null
                                    showingSettings = true
                                }
                            } else {
                                null
                            },
                            )
                        }
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        ) {
                            Column(
                                modifier = if (contentTab == MenuTab.BATTLE) {
                                    Modifier.size(0.dp).clearAndSetSemantics { }
                                } else {
                                    Modifier.fillMaxSize()
                                },
                            ) {
                                if (adventurePanelVisibleForBuild(contentTab, BuildConfig.DEBUG)) {
                                    AdventurePanel(state, repository.monsterEnergyFraction(state), gameNow())
                                }
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                ) {
                                    when (contentTab) {
                                        MenuTab.MAIN -> MainPanel(
                                            state = state,
                                            repository = repository,
                                            rankingUiState = rankingUiState,
                                            onOpenRanking = {
                                                selectedTab = MenuTab.MAIN
                                                rankingPage = RankingPageKind.ADVENTURER
                                            },
                                            unreadRecentEventCount = unreadRecentEventCount,
                                            onOpenRecentEvents = { showingRecentEvents = true },
                                        )
                                        MenuTab.CHARACTER -> CharacterPanel(
                                            state = state,
                                        )
                                        MenuTab.CORRESPONDENCE, MenuTab.BATTLE -> Unit
                                        MenuTab.ITEMS -> ItemsPanel(state)
                                        MenuTab.EQUIPMENT -> EquipmentPanel(state)
                                        MenuTab.BAG -> BagPanel(state)
                                        MenuTab.QUEST -> QuestPanel(state)
                                    }
                                }
                            }
                            BattlePanel(
                                state = state,
                                characterSlotId = activeSlotId,
                                runtime = arenaRuntime,
                                combatPower = combatPower,
                                heroPathEntry = remember(state.heroPath) {
                                    heroPathArenaEntryModel(state)
                                },
                                modifier = if (contentTab == MenuTab.BATTLE) {
                                    Modifier.fillMaxSize()
                                } else {
                                    Modifier.size(0.dp).clearAndSetSemantics { }
                                },
                                isActive = contentTab == MenuTab.BATTLE,
                                onSessionPhaseChanged = { battleSessionPhase = it },
                                onOpenRanking = { standing ->
                                    arenaRankingStanding = standing
                                    supabaseGameService.queueArenaRankingSync(standing)
                                    scope.launch { supabaseGameService.flushPendingArenaRanking() }
                                    selectedTab = MenuTab.BATTLE
                                    rankingPage = RankingPageKind.ARENA
                                },
                                onArenaPlacementRecovered = { standing ->
                                    supabaseGameService.rememberArenaRankingPlacement(standing)
                                },
                                onArenaStandingCommitted = { standing ->
                                    arenaRankingStanding = standing
                                    if (supabaseGameService.queueArenaRankingSync(standing)) {
                                        scope.launch { supabaseGameService.flushPendingArenaRanking() }
                                    }
                                },
                                onOpenHeroPath = {
                                    if (battleSessionPhase == BattleSessionPhase.IDLE) {
                                        showingHeroPath = true
                                    } else {
                                        Toast.makeText(
                                            context,
                                            pathCopy(
                                                "매칭과 결투가 끝난 뒤 편집할 수 있습니다.",
                                                "Editing is available after matching and battle end.",
                                                "マッチングと決闘の終了後に編集できます。",
                                            ),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                },
                                arenaRewardedRefillGrant = arenaRewardedRefillGrant,
                                rewardedAdPreloadStarted = arenaRewardPreloadEligible,
                                onRewardedAdPreloadAvailabilityChanged = { arenaRewardPreloadEligible = it },
                                onRequestArenaRewardedRefill = { identity ->
                                    pendingArenaRewardIdentity = identity
                                    val ticketSnapshot = refreshTrustedBattleEntrySnapshot(
                                        snapshot = battleLocalStateStore.load(identity),
                                        roster = state.publicPlayerRoster,
                                        deviceWallNowMillis = System.currentTimeMillis(),
                                        elapsedRealtimeMillis = SystemClock.elapsedRealtime(),
                                        bootCount = currentArenaBootCount(context.applicationContext),
                                    )
                                    pendingArenaRefillCount = battleRewardedRefillCount(ticketSnapshot.dailyBattlesUsed)
                                    arenaRewardPreloadEligible = true
                                    rewardDialogBenefit = RewardedBenefit.ARENA_TICKETS
                                },
                                arenaServerMatchingQaFixture = arenaServerQaRoster,
                            )
                        }
                    }
                        }
                    }
                }
            }
            if (!showingSettings && !arenaRuntime.skillTreeVisible) {
                BottomMenu(
                    selectedTab = if (rankingPage == RankingPageKind.ADVENTURER) {
                        MenuTab.MAIN
                    } else {
                        selectedTab.takeIf { it in visibleMenuTabs } ?: MenuTab.MAIN
                    },
                    onSelect = { tab ->
                        selectedTab = tab
                        rankingPage = null
                    },
                )
            }
        }
        if (
            battleSessionPhase == BattleSessionPhase.MATCH_READY &&
            selectedTab != MenuTab.BATTLE &&
            rankingPage == null &&
            !showingSettings &&
            !showingSkillEffectTest &&
            !showingRecentEvents &&
            !showingRewardDialog &&
            !arenaRuntime.skillTreeVisible
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 82.dp)
                    .zIndex(21f),
            ) {
                BattleMatchFoundArrivalStrip(
                    onOpen = {
                        selectedTab = MenuTab.BATTLE
                        rankingPage = null
                    },
                )
            }
        }
    }

    rewardDialogBenefit?.let { benefit ->
        val dialogLoadState = when (benefit) {
            RewardedBenefit.OFFLINE_ADVENTURE -> offlineRewardedLoadState
            RewardedBenefit.ARENA_TICKETS -> arenaRewardedLoadState
        }
        RewardedBenefitDialog(
            benefit = benefit,
            consentState = adsConsentState,
            mobileAdsRuntimeState = mobileAdsRuntimeState,
            rewardedLoadState = dialogLoadState,
            arenaRefillCount = pendingArenaRefillCount,
            onDismiss = {
                rewardDialogBenefit = null
                if (benefit == RewardedBenefit.ARENA_TICKETS) {
                    pendingArenaRewardIdentity = null
                }
            },
            onRetryAdsSetup = onRetryAdsSetup,
            onRetryRewardedAd = { retryRewardedAd(benefit) },
            onWatchAd = {
                when (showRewardedAd(benefit)) {
                    RewardedShowAttempt.SHOWN -> {
                        rewardDialogBenefit = null
                        if (benefit == RewardedBenefit.ARENA_TICKETS) {
                            pendingArenaRewardIdentity = null
                        }
                    }
                    RewardedShowAttempt.ELIGIBILITY_LOST -> {
                        rewardDialogBenefit = null
                        if (benefit == RewardedBenefit.ARENA_TICKETS) {
                            pendingArenaRewardIdentity = null
                            Toast.makeText(
                                context,
                                pathCopy(
                                    "출전권이 자동 충전되어 광고를 재생하지 않았습니다.",
                                    "An entry recharged automatically, so the ad was not played.",
                                    "出場券が自動回復したため、広告は再生しませんでした。",
                                ),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                    RewardedShowAttempt.NOT_READY -> Unit
                }
            },
        )
    }
    if (showingRecentEvents) {
        RecentAdventureEventsDialog(
            records = recentEvents,
            onDismiss = { showingRecentEvents = false },
        )
    }
    if (openedHeroPathToken != null) {
        HeroPathChoiceSheet(
            state = state,
            token = openedHeroPathToken,
            onChoiceSelected = submitHeroPathChoice,
            onFullTreeSelected = {
                openedHeroPathTokenId = null
                showingHeroPath = true
            },
            onDeferred = {
                deferredHeroPathTokenId = openedHeroPathToken.tokenId
                openedHeroPathTokenId = null
            },
            onDismiss = { openedHeroPathTokenId = null },
        )
    }
}

internal val WARRIOR_SIGNATURE_SKILL_IDS = listOf(
    "warrior_t01_c01",
    "warrior_t02_c03",
    "warrior_t03_c02",
    "warrior_t04_c03",
    "warrior_t05_c02",
    "warrior_t06_c05",
    "warrior_t07_c01",
    "warrior_t08_c02",
    "warrior_t09_c03",
    "warrior_t10_c01",
    "warrior_t11_c02",
    "warrior_t12_c04",
    "warrior_t13_c03",
    "warrior_t14_c03",
    "warrior_t15_c01",
    "warrior_t16_c05",
    "warrior_t17_c01",
    "warrior_t18_c02",
    "warrior_t19_c02",
    "warrior_t20_c01",
)

internal fun warriorSignatureSkillDefinitions(): List<SkillDefinition> =
    signatureSkillDefinitions(HeroClass.WARRIOR)

internal fun signatureSkillDefinitions(heroClass: HeroClass): List<SkillDefinition> =
    SkillCatalog.forClass(heroClass)

internal fun skillEffectSummaryLabel(
    unlockLevel: Int,
    elementName: String,
    hitCount: Int,
    damagePercentMin: Int,
    damagePercentMax: Int,
    displayedDamage: String? = null,
    language: AppLanguage,
): String {
    val localizedElement = localized(elementName, language)
    val hitLabel = when (language) {
        AppLanguage.KOREAN -> "${hitCount}타"
        AppLanguage.ENGLISH -> "$hitCount ${if (hitCount == 1) "hit" else "hits"}"
        AppLanguage.JAPANESE -> "${hitCount}ヒット"
    }
    val parts = mutableListOf(
        "Lv.$unlockLevel",
        localizedElement,
        hitLabel,
        "$damagePercentMin~$damagePercentMax%",
    )
    displayedDamage?.takeIf(String::isNotBlank)?.let { damage ->
        parts += when (language) {
            AppLanguage.KOREAN -> "표시 피해 $damage"
            AppLanguage.ENGLISH -> "Display damage $damage"
            AppLanguage.JAPANESE -> "表示ダメージ $damage"
        }
    }
    return parts.joinToString(" · ")
}

@Composable
private fun SkillEffectTestScreen(
    baseState: SimpleGameState,
    offlineAdventureProgress: Float,
    offlineAdventureFull: Boolean,
    onExit: () -> Unit,
) {
    val language = LocalAppLanguage.current
    val engine = remember { SimpleGameEngine() }
    val frozenBaseState = remember { baseState.skillEffectTestCopy() }
    var selectedClass by remember { mutableStateOf(HeroClass.WARRIOR) }
    val definitions = remember(selectedClass) { signatureSkillDefinitions(selectedClass) }
    val initialDefinition = remember { warriorSignatureSkillDefinitions().first() }
    var selectedCatalogId by remember { mutableStateOf(initialDefinition.catalogId) }
    var previewSequence by remember { mutableLongStateOf(0L) }
    var previewState by remember {
        mutableStateOf(
            buildSkillEffectPreviewState(
                baseState = frozenBaseState,
                heroClass = HeroClass.WARRIOR,
                definition = initialDefinition,
                sequence = previewSequence,
                engine = engine,
                playAnimation = false,
            ),
        )
    }

    fun playSkill(definition: SkillDefinition, heroClass: HeroClass = selectedClass) {
        selectedCatalogId = definition.catalogId
        previewSequence = if (previewSequence == Long.MAX_VALUE) 1L else previewSequence + 1L
        previewState = buildSkillEffectPreviewState(
            baseState = frozenBaseState,
            heroClass = heroClass,
            definition = definition,
            sequence = previewSequence,
            engine = engine,
            playAnimation = true,
        )
    }

    fun selectClass(heroClass: HeroClass) {
        if (heroClass == selectedClass) return
        val firstDefinition = signatureSkillDefinitions(heroClass).first()
        selectedClass = heroClass
        playSkill(firstDefinition, heroClass)
    }

    LaunchedEffect(Unit) {
        // CombatPanel observes the next action sequence just like a real combat event.
        playSkill(initialDefinition)
    }

    val selectedDefinition = definitions.firstOrNull { it.catalogId == selectedCatalogId }
        ?: definitions.first()
    val selectedIndex = definitions.indexOfFirst { it.catalogId == selectedDefinition.catalogId }
        .coerceAtLeast(0)

    fun playRelativeSkill(offset: Int) {
        val nextIndex = (selectedIndex + offset).coerceIn(0, definitions.lastIndex)
        playSkill(definitions[nextIndex])
    }

    Column(modifier = Modifier.fillMaxSize().background(AqBackground)) {
        OfflineAdventureStrip(
            progress = offlineAdventureProgress,
            isFull = offlineAdventureFull,
            onRewardClick = {},
        )
        HeroHeader(
            state = previewState,
            combatPower = engine.displayCombatPower(previewState),
        )
        CombatPanel(
            state = previewState,
            energyFraction = previewState.monster.currentEnergy.toFloat() /
                previewState.monster.maxEnergy.coerceAtLeast(1L).toFloat(),
            now = previewState.lastSettledAt,
        )
        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = AqSurface),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "스킬 연출 테스트",
                                color = AqText,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black,
                            )
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .background(AqRed.copy(alpha = 0.14f), RoundedCornerShape(99.dp))
                                    .border(1.dp, AqRed.copy(alpha = 0.48f), RoundedCornerShape(99.dp))
                                    .padding(horizontal = 7.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    "DEBUG ONLY",
                                    color = AqRed,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 0.4.sp,
                                )
                            }
                        }
                        Text(
                            "연출 선택 결과는 실제 진행과 저장 데이터에 반영되지 않습니다",
                            color = AqMuted,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onExit, modifier = Modifier.size(38.dp)) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = localized("스킬 연출 테스트 종료"),
                            tint = AqText,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    HeroClass.entries.forEach { heroClass ->
                        val selected = heroClass == selectedClass
                        OutlinedButton(
                            onClick = { selectClass(heroClass) },
                            modifier = Modifier.height(34.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (selected) AqGold.copy(alpha = 0.16f) else Color.Transparent,
                                contentColor = if (selected) AqGold else AqMuted,
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (selected) AqGoldSoft else AqSurfaceHigh,
                            ),
                        ) {
                            Text(
                                heroClass.labelKo,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.Black else FontWeight.Bold,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedButton(
                        onClick = { playRelativeSkill(-1) },
                        enabled = selectedIndex > 0,
                        modifier = Modifier.weight(1f).height(34.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text("이전", fontSize = 11.sp)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1.2f)
                            .height(34.dp)
                            .clickable(
                                role = Role.Button,
                                onClickLabel = localized("마지막 스킬로 이동"),
                                onClick = { playSkill(definitions.last()) },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${selectedIndex + 1} / ${definitions.size}",
                            color = AqMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    OutlinedButton(
                        onClick = { playRelativeSkill(1) },
                        enabled = selectedIndex < definitions.lastIndex,
                        modifier = Modifier.weight(1f).height(34.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text("다음", fontSize = 11.sp)
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF191220))
                        .border(1.dp, AqSurfaceHigh, RoundedCornerShape(12.dp))
                        .padding(start = 12.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            selectedDefinition.name,
                            color = AqGold,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        UnlocalizedText(
                            skillEffectSummaryLabel(
                                unlockLevel = selectedDefinition.unlockLevel,
                                elementName = selectedDefinition.element.labelKo,
                                hitCount = selectedDefinition.hitCount,
                                damagePercentMin = selectedDefinition.damagePercentMin,
                                damagePercentMax = selectedDefinition.damagePercentMax,
                                displayedDamage = previewState.lastDamage.format(),
                                language = language,
                            ),
                            color = AqMuted,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(
                        onClick = { playSkill(selectedDefinition) },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = localized("선택한 스킬 다시 재생"),
                            tint = AqGold,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Spacer(Modifier.height(7.dp))
                Text(
                    "${selectedClass.labelKo} 스킬 ${definitions.size}개 · 스킬을 누르면 즉시 재생",
                    color = AqMuted,
                    fontSize = 10.sp,
                )
                Spacer(Modifier.height(5.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
                    items(definitions, key = { it.catalogId }) { definition ->
                        SkillEffectTestRow(
                            definition = definition,
                            selected = definition.catalogId == selectedCatalogId,
                            onClick = { playSkill(definition) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SkillEffectTestRow(
    definition: SkillDefinition,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val language = LocalAppLanguage.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) AqGold.copy(alpha = 0.12f) else Color(0xFF1B1423))
            .border(
                width = 1.dp,
                color = if (selected) AqGoldSoft else Color.Transparent,
                shape = RoundedCornerShape(11.dp),
            )
            .clickable(
                role = Role.Button,
                onClickLabel = localized("${definition.name} 연출 재생"),
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                definition.name,
                color = if (selected) AqGold else AqText,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            UnlocalizedText(
                skillEffectSummaryLabel(
                    unlockLevel = definition.unlockLevel,
                    elementName = definition.element.labelKo,
                    hitCount = definition.hitCount,
                    damagePercentMin = definition.damagePercentMin,
                    damagePercentMax = definition.damagePercentMax,
                    language = language,
                ),
                color = AqMuted,
                fontSize = 10.sp,
                maxLines = 1,
            )
        }
        Text(
            if (selected) "재생 중" else "재생",
            color = if (selected) AqGold else AqMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun buildSkillEffectPreviewState(
    baseState: SimpleGameState,
    heroClass: HeroClass,
    definition: SkillDefinition,
    sequence: Long,
    engine: SimpleGameEngine,
    playAnimation: Boolean,
): SimpleGameState {
    val preview = baseState.skillEffectTestCopy().copy(
        hero = baseState.hero.copy(
            name = "스킬 연출 테스트",
            heroClass = heroClass,
            stats = baseState.hero.stats.copyMutable(),
        ),
        monster = baseState.monster.copy(
            id = -1L,
            name = "연출 확인용 허수아비",
            level = baseState.hero.level,
            maxEnergy = SimpleGameEngine.MONSTER_ENERGY_SCALE,
            grade = MonsterGrade.ELITE,
            currentEnergy = 75L,
            expectedAttacks = 4,
            attacksCompleted = 1,
        ),
        adventurePhase = AdventurePhase.COMBAT,
        combatPhase = CombatPhase.ATTACKING,
        actionSequence = sequence,
        lastAttackName = if (playAnimation) definition.name else "",
        lastAttackType = "스킬 연출 테스트",
        lastAttackWasSkill = playAnimation,
        lastSkillCatalogId = if (playAnimation) definition.catalogId else "",
        lastDamage = 0L,
        lastMonsterEnergyBeforeAttack = if (playAnimation) {
            SimpleGameEngine.MONSTER_ENERGY_SCALE
        } else {
            0L
        },
        lastResult = "테스트 전용 미리보기",
    )
    if (playAnimation) {
        preview.lastDamage = engine.skillPreviewDamage(preview, definition.catalogId)
    }
    return preview
}

private fun SimpleGameState.skillEffectTestCopy(): SimpleGameState = copy(
    hero = hero.copy(stats = hero.stats.copyMutable()),
    equipment = equipment.map { it.copy() }.toMutableList(),
    skills = skills.toMutableList(),
    inventory = inventory.toMutableList(),
    adventureTale = adventureTale.copy(
        acts = adventureTale.acts.map { it.copy() }.toMutableList(),
    ),
    monster = monster.copy(),
    recentMonsterNames = recentMonsterNames.toMutableList(),
    recentItemNames = recentItemNames.toMutableList(),
    completedTaleHistory = completedTaleHistory.map { it.copy(actMemories = it.actMemories.toList()) }.toMutableList(),
)

@Composable
internal fun AdventurePanel(state: SimpleGameState, energyFraction: Float, now: Long) {
    if (state.adventurePhase == AdventurePhase.COMBAT) {
        CombatPanel(state, energyFraction, now)
    } else if (state.adventurePhase == AdventurePhase.EVENT || state.adventurePhase == AdventurePhase.EVENT_RESULT) {
        AdventureEventPanel(state, now)
    } else if (state.adventurePhase == AdventurePhase.RELATIONSHIP || state.adventurePhase == AdventurePhase.RELATIONSHIP_RESULT) {
        AdventureRelationshipPanel(state, now)
    } else {
        TownActionPanel(state, now)
    }
    if (BuildConfig.ADVENTURE_SYSTEM_ENABLED) AdventureTraitActivationStrip(state, now)
}

internal fun usesMarketActionPanel(phase: AdventurePhase): Boolean =
    phase == AdventurePhase.SELLING ||
        phase == AdventurePhase.SHOPPING ||
        phase == AdventurePhase.SHOPPING_RESULT

internal data class OpeningNarrativePresentation(
    val slideNumber: Int,
    val slideCount: Int,
    val slideProgress: Float,
    val text: String,
)

internal fun openingNarrativePresentation(
    slides: List<String>,
    totalProgress: Float,
): OpeningNarrativePresentation {
    val authoredSlides = slides.map(String::trim).filter(String::isNotBlank).ifEmpty { listOf("") }
    val clampedProgress = totalProgress.coerceIn(0f, 1f)
    val scaledProgress = clampedProgress * authoredSlides.size.toFloat()
    val slideIndex = if (clampedProgress >= 1f) {
        authoredSlides.lastIndex
    } else {
        scaledProgress.toInt().coerceIn(0, authoredSlides.lastIndex)
    }
    val slideProgress = if (clampedProgress >= 1f) {
        1f
    } else {
        (scaledProgress - slideIndex.toFloat()).coerceIn(0f, 1f)
    }
    return OpeningNarrativePresentation(
        slideNumber = slideIndex + 1,
        slideCount = authoredSlides.size,
        slideProgress = slideProgress,
        text = authoredSlides[slideIndex],
    )
}

@Composable
private fun TownActionPanel(state: SimpleGameState, now: Long) {
    val actionProgress = remember(state.actionStartedAt, state.actionEndsAt) { Animatable(0f) }
    LaunchedEffect(state.actionStartedAt, state.actionEndsAt, now) {
        val duration = (state.actionEndsAt - state.actionStartedAt).coerceAtLeast(1L)
        val elapsed = (now - state.actionStartedAt).coerceIn(0L, duration)
        actionProgress.snapTo(elapsed.toFloat() / duration.toFloat())
        val remaining = (state.actionEndsAt - now).coerceAtLeast(0L)
        if (remaining > 0L) {
            actionProgress.animateTo(
                1f,
                tween(
                    durationMillis = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    easing = LinearEasing,
                ),
            )
        }
    }
    if (state.adventurePhase == AdventurePhase.LOOTING) {
        LootResultPanel(
            state = state,
            progress = actionProgress.value,
            eventResult = state.adventureJourney.eventBattle?.result,
        )
        return
    }
    if (state.adventurePhase == AdventurePhase.OPENING) {
        OpeningNarrativePanel(state = state, progress = actionProgress.value)
        return
    }
    if (usesMarketActionPanel(state.adventurePhase)) {
        MarketActionPanel(state = state, progress = actionProgress.value)
        return
    }
    val title = when (state.adventurePhase) {
        AdventurePhase.OPENING -> "새로운 모험의 시작"
        AdventurePhase.LOOTING -> "아이템 획득"
        AdventurePhase.RETURNING -> "마을로 귀환 중"
        AdventurePhase.EQUIPPING -> "드롭 장비 선별 중"
        AdventurePhase.SELLING -> "전리품 판매 중"
        AdventurePhase.SHOPPING -> "새 장비를 고르는 중"
        AdventurePhase.SHOPPING_RESULT -> "새 장비 장착 완료"
        AdventurePhase.SHOPPING_EMPTY -> "지금 살 수 있는 더 좋은 장비를 찾지 못했습니다"
        AdventurePhase.DEPARTING -> "사냥터로 출정 중"
        AdventurePhase.EVENT, AdventurePhase.EVENT_RESULT -> "모험 중 만난 일"
        AdventurePhase.RELATIONSHIP, AdventurePhase.RELATIONSHIP_RESULT -> "길에서 만난 인연"
        AdventurePhase.COMBAT -> "전투 중"
    }
    val detail = when (state.adventurePhase) {
        AdventurePhase.OPENING -> "${state.hero.name}의 발걸음이 새로운 모험의 첫 장을 엽니다"
        AdventurePhase.LOOTING -> state.lastLootSummary
        AdventurePhase.RETURNING -> "가방 ${state.inventory.size}/${state.inventoryCapacity()}"
        AdventurePhase.EQUIPPING -> if (state.lastTownGold > 0L) {
            "${state.lastTownGold}부위 자동 교체"
        } else {
            "현재 장비보다 나은 전리품이 없음"
        }
        AdventurePhase.SELLING -> state.inventory.lastOrNull()?.name ?: state.lastTownItemName
        AdventurePhase.SHOPPING -> if (state.lastTownItemName.isBlank()) {
            "소지금으로 살 수 있는 장비를 살펴봅니다"
        } else {
            "${state.lastTownItemName} 구매 · -${state.lastTownGold.format()}G"
        }
        AdventurePhase.SHOPPING_RESULT -> state.lastShopPurchase?.let(::shopEquipmentChangeLabel).orEmpty()
        AdventurePhase.SHOPPING_EMPTY -> "잠시 후 사냥터로 출정합니다"
        AdventurePhase.DEPARTING -> "잔액 ${state.hero.gold.format()}G"
        AdventurePhase.EVENT, AdventurePhase.EVENT_RESULT -> ""
        AdventurePhase.RELATIONSHIP, AdventurePhase.RELATIONSHIP_RESULT -> ""
        AdventurePhase.COMBAT -> ""
    }
    val sectionTitle = when (state.adventurePhase) {
        AdventurePhase.OPENING -> "모험의 서막"
        AdventurePhase.LOOTING -> "전리품"
        AdventurePhase.SHOPPING,
        AdventurePhase.SHOPPING_RESULT,
        AdventurePhase.SHOPPING_EMPTY,
        -> "장비 상점"
        else -> "자동 모험"
    }
    Card(
        modifier = Modifier.fillMaxWidth().height(218.dp).padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(22.dp),
    ) {
        Box(Modifier.fillMaxSize().border(1.dp, AqGoldSoft, RoundedCornerShape(22.dp))) {
            Image(
                painter = painterResource(
                    battleBackgroundResource(
                        definitionId = state.adventureTale.definitionId,
                        chapterNumber = state.adventureTale.chapterNumber,
                    ),
                ),
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
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(58.dp)
                        .background(Color(0xEE17111F))
                        .border(1.dp, AqGoldSoft)
                        .padding(horizontal = 15.dp, vertical = 8.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(sectionTitle, color = AqText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Text(state.adventurePhase.labelKo, color = AqGold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { actionProgress.value },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .semantics {
                                contentDescription = localized(title)
                                stateDescription = localized("${(actionProgress.value * 100f).toInt()}퍼센트")
                            },
                        color = AqGold,
                        trackColor = Color(0xFF4A3B4F),
                    )
                }
                Column(
                    Modifier.fillMaxWidth().weight(1f).padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        title,
                        color = AqText,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(9.dp))
                    Text(
                        detail,
                        color = AqMuted,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun OpeningNarrativePanel(
    state: SimpleGameState,
    progress: Float,
) {
    val language = LocalAppLanguage.current
    val slides = state.adventureTale.openingSlides.ifEmpty {
        listOf(state.adventureTale.opening)
    }
    val presentation = openingNarrativePresentation(slides, progress)
    val narrativeText = localizedStoryText(
        text = presentation.text,
        heroName = state.hero.name,
        language = language,
    )
    Card(
        modifier = Modifier.fillMaxWidth().height(218.dp).padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(22.dp),
    ) {
        Box(Modifier.fillMaxSize().border(1.dp, AqGoldSoft, RoundedCornerShape(22.dp))) {
            Image(
                painter = painterResource(
                    battleBackgroundResource(
                        definitionId = state.adventureTale.definitionId,
                        chapterNumber = state.adventureTale.chapterNumber,
                    ),
                ),
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
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(58.dp)
                        .background(Color(0xEE17111F))
                        .border(1.dp, AqGoldSoft)
                        .padding(horizontal = 15.dp, vertical = 8.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("모험의 서막", color = AqText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${presentation.slideNumber}/${presentation.slideCount}",
                            color = AqGold,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { presentation.slideProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .semantics {
                                contentDescription = localized("프롤로그 ${presentation.slideNumber}번째 장면")
                                stateDescription = localized("${(presentation.slideProgress * 100f).toInt()}퍼센트")
                            },
                        color = AqGold,
                        trackColor = Color(0xFF4A3B4F),
                    )
                }
                Column(
                    Modifier.fillMaxWidth().weight(1f).padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "새로운 모험의 시작",
                        color = AqText,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(10.dp))
                    UnlocalizedText(
                        narrativeText,
                        color = AqMuted,
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun MarketActionPanel(
    state: SimpleGameState,
    progress: Float,
) {
    val language = LocalAppLanguage.current
    val isSelling = state.adventurePhase == AdventurePhase.SELLING
    val isPurchaseResult = state.adventurePhase == AdventurePhase.SHOPPING_RESULT
    val equipmentChange = if (isPurchaseResult) {
        state.lastShopPurchase
    } else {
        state.pendingShopOffer
    }
    val accent = if (isSelling) Color(0xFF8BCB84) else AqGold
    val sectionTitle = if (isSelling) "마을 상점" else "장비 상점"
    val phaseLabel = when {
        isSelling -> "판매 완료"
        isPurchaseResult -> "구매 완료"
        else -> "자동 구매 중"
    }
    val actionTitle = when {
        isSelling -> "판매 완료"
        isPurchaseResult -> "자동 장착 완료"
        else -> "자동 구매 예정"
    }
    val actionAmount = when {
        isSelling -> "+${state.lastTownGold.format()}G"
        isPurchaseResult -> "-${(equipmentChange?.price ?: state.lastTownGold).format()}G"
        else -> "가격 ${(equipmentChange?.price ?: state.lastTownGold).format()}G"
    }
    val sourceActionItemName = when {
        isSelling -> state.lastTownItemName.ifBlank { "판매할 전리품을 확인하고 있습니다" }
        equipmentChange != null -> equipmentChange.name
        else -> "소지금으로 살 수 있는 장비를 살펴봅니다"
    }
    val actionItemName = if (equipmentChange != null) {
        localizedEquipmentName(sourceActionItemName, language)
    } else {
        localizedItemName(sourceActionItemName, language)
    }
    val actionItemDetail = equipmentChange?.let(::shopEquipmentChangeLabel)
    val actionItemRarity = when {
        isSelling -> state.lastTownItemRarity
        equipmentChange != null -> equipmentChange.rarity
        else -> ""
    }
    val actionDescription = listOfNotNull(actionItemName, actionItemDetail).joinToString("\n")
    val actionDescriptionColor = marketActionDescriptionColor(
        itemName = actionItemName,
        rarity = actionItemRarity,
    )
    val semanticsDescription = buildString {
        append("보유 골드 ${state.hero.gold.format()}, ")
        append(actionTitle)
        append(", ")
        append(actionDescription.replace("\n", ", "))
        append(", ")
        append(actionAmount)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(218.dp)
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(22.dp),
    ) {
        Box(Modifier.fillMaxSize().border(1.dp, AqGoldSoft, RoundedCornerShape(22.dp))) {
            Image(
                painter = painterResource(
                    battleBackgroundResource(
                        definitionId = state.adventureTale.definitionId,
                        chapterNumber = state.adventureTale.chapterNumber,
                    ),
                ),
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
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .background(Color(0xEE17111F))
                        .border(1.dp, AqGoldSoft)
                        .padding(horizontal = 15.dp, vertical = 8.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(sectionTitle, color = AqText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Text(phaseLabel, color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .semantics {
                                contentDescription = localized(phaseLabel)
                                stateDescription = localized("${(progress * 100f).toInt()}퍼센트")
                            },
                        color = accent,
                        trackColor = Color(0xFF4A3B4F),
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 20.dp, vertical = 9.dp)
                        .semantics(mergeDescendants = true) {
                            contentDescription = localized(semanticsDescription)
                        },
                    verticalArrangement = Arrangement.Center,
                ) {
                    GoldBalanceCard(state.hero.gold)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            actionTitle,
                            color = accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            actionAmount,
                            color = AqGold,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        actionItemName,
                        color = actionDescriptionColor,
                        fontSize = marketActionItemFontSizeSp(actionItemName.length).sp,
                        lineHeight = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = MARKET_ACTION_ITEM_MAX_LINES,
                        overflow = MARKET_ACTION_ITEM_OVERFLOW,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (actionItemDetail != null) {
                        Text(
                            actionItemDetail,
                            color = AqMuted,
                            fontSize = 13.sp,
                            lineHeight = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GoldBalanceCard(gold: Long) {
    val formattedGold = gold.format()
    val valueFontSize = when {
        formattedGold.length > 20 -> 14.sp
        formattedGold.length > 15 -> 16.sp
        formattedGold.length > 11 -> 18.sp
        else -> 22.sp
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .background(
                brush = Brush.horizontalGradient(
                    listOf(Color(0x30E5B84D), Color(0x141D1726)),
                ),
                shape = RoundedCornerShape(13.dp),
            )
            .border(1.dp, Color(0x99E5B84D), RoundedCornerShape(13.dp))
            .padding(horizontal = 14.dp)
            .clearAndSetSemantics {
                contentDescription = localized("보유 골드 $formattedGold")
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(Color(0x24E5B84D), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.AccountBalanceWallet,
                contentDescription = null,
                tint = AqGold,
                modifier = Modifier.size(21.dp),
            )
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                "보유 골드",
                color = AqMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text(
                "$formattedGold G",
                color = AqGold,
                fontSize = valueFontSize,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

@Composable
internal fun LootResultPanel(
    state: SimpleGameState,
    progress: Float,
    eventResult: AdventureEventResult? = null,
    relationshipResult: AdventureRelationshipResult? = null,
) {
    val language = LocalAppLanguage.current
    require(eventResult == null || relationshipResult == null) { "Only one result receipt may be shown" }
    val relationshipReceipt = relationshipResult?.let { relationshipReceiptPresentation(it, language) }
    val eventReceipt = eventResult?.let { adventureEventReceiptPresentation(it, language) } ?: relationshipReceipt
    val recordedItemName = state.lastLootName
        .ifBlank { eventResult?.itemName.orEmpty() }
        .ifBlank { relationshipResult?.itemName.orEmpty() }
    val lootEquipped = relationshipResult?.itemEquipped ?: state.lastLootEquipped
    val relationshipEquipment = relationshipResult?.rewardKind == AdventureEventRewardKind.ITEM
    val equipmentLoot = relationshipEquipment || state.lastLootKind == "장비"
    val hasLoot = if (eventReceipt == null) {
        state.lastLootName.isNotBlank()
    } else {
        eventReceipt.itemState == AdventureEventItemReceiptState.ACQUIRED && recordedItemName.isNotBlank()
    }
    val omitted = if (eventReceipt != null) {
        eventReceipt.itemState == AdventureEventItemReceiptState.OMITTED
    } else {
        BuildConfig.ADVENTURE_SYSTEM_ENABLED && !hasLoot && state.adventureTraits.source?.itemOmitted == true
    }
    val bagFull = if (eventReceipt != null) {
        eventReceipt.itemState == AdventureEventItemReceiptState.BAG_FULL
    } else {
        !hasLoot && !omitted
    }
    val eventBattlePending = eventReceipt?.reward == AdventureEventReceiptReward.BATTLE
    val omittedName = state.adventureTraits.visibleActivations.lastOrNull {
        it.effectKind == com.nullplaying.model.AdventureTraitEffectKind.OMITTED_ITEM
    }?.subjectName.orEmpty()
    val rarity = state.lastLootRarity
        .ifBlank { eventResult?.itemRarity.orEmpty() }
        .ifBlank { relationshipResult?.itemRarity.orEmpty() }
        .ifBlank { "일반" }
    val accent = when {
        hasLoot -> rarityColor(rarity)
        eventReceipt?.reward == AdventureEventReceiptReward.EXPERIENCE -> Color(0xFF8FB9FF)
        eventReceipt?.reward == AdventureEventReceiptReward.GOLD -> AqGold
        eventReceipt?.reward == AdventureEventReceiptReward.ROUTE -> Color(0xFF84D3B0)
        eventBattlePending -> AqRed
        else -> AqMuted
    }
    val sourceItemName = when {
        recordedItemName.isNotBlank() -> recordedItemName
        omitted && omittedName.isNotBlank() -> omittedName
        eventReceipt != null -> eventReceipt.eventTitle
        else -> "전리품을 담지 못했습니다"
    }
    val eventAwareItemName = if (eventResult != null) {
        eventItemDisplayName(eventResult, sourceItemName, language)
    } else {
        sourceItemName
    }
    val eventNarrativeIsPrimary = eventReceipt != null && !hasLoot && !omitted && !bagFull
    val itemName = when {
        eventNarrativeIsPrimary -> eventReceipt?.narrative.orEmpty()
        hasLoot && equipmentLoot -> localizedEquipmentName(eventAwareItemName, language)
        hasLoot || eventReceipt == null -> localizedItemName(eventAwareItemName, language)
        else -> eventAwareItemName
    }
    val typeLabel = when {
        omitted -> journeyText(language, "남긴 전리품", "Loot left behind", "置いた戦利品")
        bagFull -> journeyText(language, "가방 가득 참", "Bag full", "バッグがいっぱい")
        eventReceipt?.reward == AdventureEventReceiptReward.BATTLE ->
            journeyText(language, "사건 전개", "Event development", "出来事の展開")
        relationshipReceipt != null && !hasLoot -> journeyText(language, "인연 보상", "Relationship reward", "縁の報酬")
        eventReceipt != null && !hasLoot -> journeyText(language, "사건 보상", "Event reward", "出来事報酬")
        equipmentLoot && state.lastLootEquipmentSlot != null ->
            "장비 · ${state.lastLootEquipmentSlot!!.labelKo}"
        else -> "전리품"
    }
    val primaryBadgeLabel = if (eventReceipt != null && !hasLoot) eventReceipt.rewardTypeLabel else rarity
    val power = state.lastLootEquipmentPower
    val previousPower = state.lastLootPreviousPower
    val itemDetail = when {
        eventNarrativeIsPrimary -> ""
        !hasLoot -> "가방 ${state.inventory.size}/${state.inventoryCapacity()}"
        !equipmentLoot ->
            "가방 ${state.inventory.size}/${state.inventoryCapacity()}"
        lootEquipped && power != null && previousPower != null ->
            equipmentReplacementPowerLabel(previousPower, power)
        lootEquipped && power != null -> power.format()
        power != null && previousPower != null ->
            "장비력 ${power.format()} · 장착 중 ${previousPower.format()}"
        power != null -> "장비력 ${power.format()}"
        else -> "장비"
    }
    val statusTitle = when {
        eventReceipt != null -> eventReceipt.rewardSummary
        omitted -> journeyText(language, "가방에 담지 않았습니다", "Left out of the bag", "バッグには収納しませんでした")
        bagFull -> journeyText(language, "가방이 가득 찼습니다", "The bag is full", "バッグがいっぱいです")
        lootEquipped -> "새 장비로 바로 장착했습니다"
        else -> "가방에 보관했습니다"
    }
    val statusDetail = when {
        eventReceipt?.reward == AdventureEventReceiptReward.BATTLE -> journeyText(
            language,
            "잠시 후 자동으로 전투를 시작합니다",
            "Battle begins automatically in a moment",
            "まもなく自動で戦闘を開始します",
        )
        eventReceipt != null && eventReceipt.penaltySummary.isNotBlank() -> eventReceipt.penaltySummary
        eventReceipt != null && omitted -> journeyText(language, "확인을 마치고 길을 재촉합니다", "The hero finishes checking and moves on", "確認を終え、道を急ぐ")
        eventReceipt != null && bagFull -> journeyText(language, "마을에 돌아가 가방을 정리합니다", "Returning to town to sort the bag", "町に戻ってバッグを整理する")
        relationshipReceipt != null -> relationshipReceipt.penaltySummary
        eventReceipt != null && hasLoot && lootEquipped -> journeyText(language, "새 장비로 바로 장착했습니다", "Equipped immediately", "すぐに新しい装備を装着した")
        eventReceipt != null && hasLoot && equipmentLoot -> journeyText(language, "현재 장비를 유지합니다", "Current equipment kept", "現在の装備を維持した")
        eventReceipt != null && hasLoot -> journeyText(language, "가방 ${state.inventory.size}/${state.inventoryCapacity()}", "Bag ${state.inventory.size}/${state.inventoryCapacity()}", "バッグ ${state.inventory.size}/${state.inventoryCapacity()}")
        eventReceipt != null -> journeyText(language, "보상 정산 완료", "Reward settled", "報酬精算完了")
        omitted -> journeyText(language, "확인을 마치고 길을 서두릅니다", "The hero finishes checking and moves on sooner", "確認を終え、早く道を進む")
        bagFull -> "마을로 돌아가 가방을 정리합니다"
        lootEquipped -> "기존 장비는 가방에 보관됩니다"
        equipmentLoot -> "현재 장비를 유지합니다"
        else -> ""
    }
    val highlightedStatus = lootEquipped || eventReceipt?.wasGranted() == true || eventBattlePending
    val statusColor = if (highlightedStatus) accent else AqText
    val statusBorder = if (highlightedStatus) accent.copy(alpha = 0.65f) else AqSurfaceHigh
    val statusBackground = if (highlightedStatus) {
        accent.copy(alpha = 0.16f)
    } else {
        Color(0xD922192C)
    }
    val usesDenseLootLayout = itemName.length > 18
    val itemFontSize = lootResultItemFontSizeSp(itemName.length).sp
    val semanticsDescription = buildList {
        eventReceipt?.subjectName?.takeIf(String::isNotBlank)?.let(::add)
        addAll(listOf(primaryBadgeLabel, typeLabel, itemName, itemDetail).filter(String::isNotBlank))
        add(statusTitle)
        if (statusDetail.isNotBlank()) add(statusDetail)
    }.joinToString(", ") { localized(it) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(LOOT_RESULT_PANEL_HEIGHT_DP.dp)
            .padding(horizontal = 16.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = semanticsDescription
            },
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(22.dp),
    ) {
        Box(Modifier.fillMaxSize().border(1.dp, AqGoldSoft, RoundedCornerShape(22.dp))) {
            Image(
                painter = painterResource(
                    battleBackgroundResource(
                        definitionId = state.adventureTale.definitionId,
                        chapterNumber = state.adventureTale.chapterNumber,
                    ),
                ),
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
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .background(Color(0xEE17111F))
                        .border(1.dp, AqGoldSoft)
                        .padding(horizontal = 15.dp, vertical = 8.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (relationshipReceipt?.subjectName?.isNotBlank() == true) {
                                relationshipReceipt.subjectName
                            } else if (relationshipReceipt != null) {
                                journeyText(language, "인연 보상", "Relationship reward", "縁の報酬")
                            } else if (eventReceipt == null) "전리품"
                            else if (eventReceipt.reward == AdventureEventReceiptReward.BATTLE) {
                                journeyText(language, "사건 전개", "Event development", "出来事の展開")
                            } else journeyText(language, "사건 보상", "Event reward", "出来事報酬"),
                            color = AqText,
                            fontSize = if ((relationshipReceipt?.subjectName?.length ?: 0) >= 8) 12.sp else 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            eventReceipt?.outcomeLabel
                                ?: if (omitted) journeyText(language, "가볍게 이동", "Moving on", "身軽に進む") else "획득 완료",
                            color = relationshipResult?.let { relationshipColor(it.tier) } ?: accent,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .semantics {
                                contentDescription = when {
                                    relationshipReceipt != null -> journeyText(language, "인연 보상 확인", "Reviewing relationship reward", "縁の報酬を確認")
                                    eventReceipt == null -> localized("전리품 확인")
                                    else -> journeyText(language, "사건 보상 확인", "Reviewing event reward", "出来事報酬を確認")
                                }
                                stateDescription = localized("${(progress * 100f).toInt()}퍼센트")
                            },
                        color = accent,
                        trackColor = Color(0xFF4A3B4F),
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(
                            horizontal = 20.dp,
                            vertical = lootResultContentVerticalPaddingDp(itemName.length).dp,
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Row(horizontalArrangement = Arrangement.Center) {
                        LootBadge(
                            label = primaryBadgeLabel,
                            textColor = accent,
                            backgroundColor = accent.copy(alpha = 0.15f),
                            borderColor = accent.copy(alpha = 0.7f),
                        )
                        Spacer(Modifier.width(7.dp))
                        LootBadge(
                            label = typeLabel,
                            textColor = AqMuted,
                            backgroundColor = Color(0xD922192C),
                            borderColor = AqSurfaceHigh,
                        )
                    }
                    Spacer(Modifier.height(if (usesDenseLootLayout) 2.dp else 5.dp))
                    Text(
                        itemName,
                        color = if (hasLoot || eventReceipt?.reward != AdventureEventReceiptReward.NONE) accent else AqText,
                        fontSize = itemFontSize,
                        lineHeight = lootResultItemLineHeightSp(itemName.length).sp,
                        fontWeight = FontWeight.Black,
                        maxLines = LOOT_RESULT_ITEM_MAX_LINES,
                        overflow = LOOT_RESULT_ITEM_OVERFLOW,
                        textAlign = TextAlign.Center,
                    )
                    if (itemDetail.isNotBlank()) {
                        Text(
                            itemDetail,
                            color = if (lootEquipped) accent else AqMuted,
                            fontSize = if (usesDenseLootLayout) 10.sp else 11.sp,
                            lineHeight = if (usesDenseLootLayout) 12.sp else 14.sp,
                            fontWeight = if (lootEquipped) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Spacer(
                        Modifier.height(
                            when {
                                eventNarrativeIsPrimary -> 6.dp
                                usesDenseLootLayout -> 2.dp
                                else -> 6.dp
                            },
                        ),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(statusBackground, RoundedCornerShape(11.dp))
                            .border(1.dp, statusBorder, RoundedCornerShape(11.dp))
                            .padding(
                                horizontal = 12.dp,
                                vertical = lootResultStatusVerticalPaddingDp(itemName.length).dp,
                            )
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = when {
                                eventBattlePending -> Icons.Filled.Shield
                                lootEquipped || eventReceipt?.wasGranted() == true -> Icons.Filled.CheckCircle
                                else -> Icons.Filled.Backpack
                            },
                            contentDescription = null,
                            tint = if (highlightedStatus) statusColor else AqMuted,
                            modifier = Modifier.size(if (usesDenseLootLayout) 16.dp else 18.dp),
                        )
                        Spacer(Modifier.width(if (usesDenseLootLayout) 6.dp else 8.dp))
                        Column(horizontalAlignment = Alignment.Start) {
                            Text(
                                statusTitle,
                                color = statusColor,
                                fontSize = if (usesDenseLootLayout) 11.sp else 12.sp,
                                lineHeight = if (usesDenseLootLayout) 13.sp else 15.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                            if (statusDetail.isNotBlank()) {
                                Text(
                                    statusDetail,
                                    color = AqMuted,
                                    fontSize = if (usesDenseLootLayout) 9.sp else 10.sp,
                                    lineHeight = if (usesDenseLootLayout) 11.sp else 13.sp,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun equipmentReplacementPowerLabel(previousPower: Long, newPower: Long): String =
    if (previousPower == newPower) {
        "${previousPower.format()} → ${newPower.format()} · 등급 상승"
    } else {
        "${previousPower.format()} → ${newPower.format()}"
    }

internal fun shopEquipmentChangeLabel(offer: ShopEquipmentOffer): String =
    "${offer.slot.labelKo} ${offer.previousPower.format()} → ${offer.newPower.format()}"

@Composable
private fun LootBadge(
    label: String,
    textColor: Color,
    backgroundColor: Color,
    borderColor: Color,
) {
    Box(
        modifier = Modifier
            .background(backgroundColor, RoundedCornerShape(99.dp))
            .border(1.dp, borderColor, RoundedCornerShape(99.dp))
            .padding(horizontal = 9.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = textColor,
            fontSize = 10.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun HeroHeader(
    state: SimpleGameState,
    combatPower: Long,
    ranking: RankingHeaderPresentation? = null,
    onLevelClick: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
) {
    val levelText = state.hero.level.format()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(90.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = buildList {
                        add(localized("레벨 $levelText"))
                        add(state.hero.name)
                        add(localized(state.hero.heroClass.labelKo))
                        ranking?.let { add(localized(it.accessibilityLabel)) }
                        add(localized("전투력 ${combatPower.format()}"))
                    }.joinToString(", ")
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .width(48.dp)
                    .then(
                        if (onLevelClick != null) {
                            Modifier.clickable(
                                onClickLabel = localized("스킬 연출 테스트 열기"),
                                role = Role.Button,
                                onClick = onLevelClick,
                            )
                        } else {
                            Modifier
                        },
                    ),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    "LV",
                    color = AqMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    maxLines = 1,
                )
                Text(
                    levelText,
                    modifier = Modifier.fillMaxWidth(),
                    color = AqGold,
                    fontSize = heroHeaderLevelFontSizeSp(levelText).sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .width(1.dp)
                    .height(62.dp)
                    .background(AqSurfaceHigh),
            )
            Spacer(Modifier.width(10.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                UnlocalizedText(
                    state.hero.name,
                    modifier = Modifier.padding(
                        end = if (onOpenSettings != null) 76.dp else 0.dp,
                    ),
                    color = AqText,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(7.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        state.hero.heroClass.labelKo,
                        color = AqMuted,
                        fontSize = 13.sp,
                        maxLines = 1,
                    )
                    if (ranking != null) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier
                                .width(1.dp)
                                .height(12.dp)
                                .background(AqSurfaceHigh),
                        )
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Filled.WorkspacePremium,
                            contentDescription = null,
                            tint = if (ranking.isRanked) AqGold else AqMuted,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = ranking.visualLabel,
                            modifier = Modifier.weight(1f),
                            color = if (ranking.isRanked) AqGold else AqMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(8.dp))
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    Text("전투력", color = AqMuted, fontSize = 11.sp, maxLines = 1)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        combatPower.format(),
                        color = AqGold,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
        }
        if (onOpenSettings != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onOpenSettings != null) {
                    OutlinedButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.height(40.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        border = BorderStroke(1.dp, AqGoldSoft),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = AqGold.copy(alpha = 0.12f),
                            contentColor = AqGold,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text("설정", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

internal fun heroHeaderLevelFontSizeSp(levelText: String): Int = when {
    levelText.length <= 2 -> 24
    levelText.length == 3 -> 21
    levelText.length == 4 -> 18
    else -> 15
}

@Composable
private fun OfflineAdventureStrip(
    progress: Float,
    isFull: Boolean,
    onRewardClick: () -> Unit,
) {
    val percent = offlineAdventurePercent(progress)
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(300, easing = LinearEasing),
        label = "offline-adventure-progress",
    )
    val animatedColor by animateColorAsState(
        targetValue = offlineAdventureColor(percent),
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "offline-adventure-color",
    )
    val message = if (isFull) {
        "오프라인 모험 시간이 가득 찼습니다"
    } else {
        "오프라인 모험 시간을 충전 중입니다."
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .background(Color(0xFF17121F))
            .border(1.dp, AqSurfaceHigh)
            .padding(horizontal = 16.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.HourglassBottom,
            contentDescription = null,
            tint = AqText,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.width(7.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) {
                    contentDescription = localized("오프라인 모험, ${percent}퍼센트, $message")
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = progress.coerceIn(0f, 1f),
                        range = 0f..1f,
                    )
                },
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                localized(message),
                color = if (isFull) Color(0xFF8BCB84) else AqText,
                fontSize = 10.sp,
                lineHeight = 10.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .clearAndSetSemantics { },
                    color = animatedColor,
                    trackColor = Color(0xFF4A3B4F),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "$percent%",
                    modifier = Modifier.width(36.dp),
                    color = animatedColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        OutlinedButton(
            onClick = onRewardClick,
            enabled = !isFull,
            modifier = Modifier
                .width(72.dp)
                .height(44.dp)
                .semantics {
                    contentDescription = localized(
                        if (isFull) {
                            "오프라인 모험 충전 완료"
                        } else {
                            "광고를 보고 오프라인 모험 모두 충전"
                        },
                    )
                },
            border = BorderStroke(1.dp, if (isFull) AqMuted else AqGold),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = AqGold,
                disabledContentColor = AqMuted,
            ),
            contentPadding = PaddingValues(horizontal = 4.dp),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(
                if (isFull) "충전 완료" else "바로 충전",
                fontSize = 11.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Visible,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun RewardedBenefitDialog(
    benefit: RewardedBenefit,
    consentState: AdsConsentState,
    mobileAdsRuntimeState: MobileAdsRuntimeState,
    rewardedLoadState: RewardedLoadState,
    arenaRefillCount: Int,
    onDismiss: () -> Unit,
    onRetryAdsSetup: () -> Unit,
    onRetryRewardedAd: () -> Unit,
    onWatchAd: () -> Unit,
) {
    val language = LocalAppLanguage.current
    val presentation = rewardDialogPresentation(
        consentState = consentState,
        mobileAdsRuntimeState = mobileAdsRuntimeState,
        rewardedLoadState = rewardedLoadState,
        benefit = benefit,
        arenaRefillCount = arenaRefillCount,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AqSurfaceHigh,
        icon = {
            Icon(Icons.Outlined.HourglassBottom, contentDescription = null, tint = AqGold)
        },
        title = {
            UnlocalizedText(
                when (benefit) {
                    RewardedBenefit.OFFLINE_ADVENTURE -> localized("오프라인 모험 충전", language)
                    RewardedBenefit.ARENA_TICKETS -> arenaRewardedRefillTitle(language)
                },
                color = AqText,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            val supportingMessage = presentation.supportingMessage
            if (supportingMessage == null) {
                Text(
                    localized(presentation.message),
                    color = AqMuted,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        localized(presentation.message),
                        modifier = Modifier.fillMaxWidth(),
                        color = AqText,
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                    val supportingShape = RoundedCornerShape(12.dp)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(supportingShape)
                            .background(AqBackground.copy(alpha = 0.48f))
                            .border(1.dp, AqGold.copy(alpha = 0.16f), supportingShape)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Text(
                            if (benefit == RewardedBenefit.ARENA_TICKETS) {
                                arenaRewardedRefillSupportingMessage(language)
                            } else {
                                localized(supportingMessage, language)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            color = AqMuted,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.height(48.dp)) {
                Text(localized("나중에"), color = AqMuted)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when (presentation.action) {
                        RewardDialogAction.RETRY_AD_SETUP -> onRetryAdsSetup()
                        RewardDialogAction.RETRY_AD_LOAD -> onRetryRewardedAd()
                        RewardDialogAction.WATCH_AD -> onWatchAd()
                        RewardDialogAction.NONE -> Unit
                    }
                },
                enabled = presentation.confirmEnabled,
                modifier = Modifier.height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AqGold,
                    contentColor = AqBackground,
                ),
            ) {
                Text(
                    localized(presentation.confirmLabel),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
    )
}

@Composable
private fun EncounterPanel(state: SimpleGameState, now: Long) {
    val actionProgress = remember(state.actionStartedAt, state.actionEndsAt) { Animatable(0f) }
    LaunchedEffect(state.actionStartedAt, state.actionEndsAt, now) {
        val duration = (state.actionEndsAt - state.actionStartedAt).coerceAtLeast(1L)
        val elapsed = (now - state.actionStartedAt).coerceIn(0L, duration)
        actionProgress.snapTo(elapsed.toFloat() / duration.toFloat())
        val remaining = (state.actionEndsAt - now).coerceAtLeast(0L)
        if (remaining > 0L) {
            actionProgress.animateTo(
                1f,
                tween(
                    durationMillis = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    easing = LinearEasing,
                ),
            )
        }
    }
    // Use the saved interval so an encounter already underway also survives a rules update.
    val encounterDuration = (state.actionEndsAt - state.actionStartedAt).coerceAtLeast(1L)
    val searchBoundary = (encounterDuration - SimpleGameEngine.ENCOUNTER_DISCOVERY_MILLIS)
        .coerceAtLeast(0L).toFloat() / encounterDuration.toFloat()
    val searching = actionProgress.value < searchBoundary
    val monsterName = localizedMonsterName(
        state.monster.name,
        state.monster.baseName,
        LocalAppLanguage.current,
        catalogId = state.monster.catalogId,
    )
    val monsterHeaderHeight = monsterHeaderHeightDp(monsterName.length).dp
    val monsterHeaderFontSize = monsterHeaderFontSizeSp(monsterName.length).sp
    val monsterHeaderLineHeight = monsterHeaderLineHeightSp(monsterName.length).sp
    val monsterHeaderMaxLines = monsterHeaderMaxLines(monsterName.length)
    val monsterHeaderSpacing = monsterHeaderProgressSpacingDp(monsterName.length).dp
    val header = if (searching) "다음 모험" else monsterName
    val badge = when {
        searching -> "탐색"
        state.monster.isLabyrinthGateBoss -> "관문 보스"
        else -> state.monster.grade.labelKo
    }
    val title = if (searching) "주변을 탐색 중" else "$monsterName 발견"
    Card(
        modifier = Modifier.fillMaxWidth().height(218.dp).padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(22.dp),
    ) {
        Box(Modifier.fillMaxSize().border(1.dp, AqGoldSoft, RoundedCornerShape(22.dp))) {
            Image(
                painter = painterResource(
                    battleBackgroundResource(
                        definitionId = state.adventureTale.definitionId,
                        chapterNumber = state.adventureTale.chapterNumber,
                    ),
                ),
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
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(monsterHeaderHeight)
                        .background(Color(0xEE17111F))
                        .border(1.dp, AqGoldSoft)
                        .padding(horizontal = 15.dp, vertical = 8.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            header,
                            color = AqText,
                            fontSize = monsterHeaderFontSize,
                            lineHeight = monsterHeaderLineHeight,
                            fontWeight = FontWeight.Bold,
                            maxLines = monsterHeaderMaxLines,
                            overflow = MONSTER_HEADER_OVERFLOW,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            badge,
                            color = if (searching) AqGold else monsterGradeColor(badge),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(monsterHeaderSpacing))
                    LinearProgressIndicator(
                        progress = {
                            if (searching) {
                                (actionProgress.value / searchBoundary).coerceIn(0f, 1f)
                            } else {
                                1f
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .semantics {
                                contentDescription = localized(title)
                                stateDescription = localized(if (searching) "탐색 중" else "몬스터 발견")
                            },
                        color = if (searching) AqGold else AqRed,
                        trackColor = if (searching) Color(0xFF4A3B4F) else Color(0xFF4A2636),
                    )
                }
                Box(
                    Modifier.fillMaxWidth().weight(1f).padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (searching) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "주변을 탐색 중",
                                color = AqText,
                                fontSize = 21.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(9.dp))
                            Text(
                                "다음 적의 흔적을 찾고 있습니다",
                                color = AqMuted,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .widthIn(max = 290.dp)
                                .background(Color(0xE61A1322), RoundedCornerShape(14.dp))
                                .border(1.dp, AqGoldSoft, RoundedCornerShape(14.dp))
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                monsterName,
                                color = AqText,
                                fontSize = 17.sp,
                                lineHeight = 22.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(5.dp))
                            Text(
                                "발견했습니다",
                                color = AqGold,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CombatPanel(
    state: SimpleGameState,
    energyFraction: Float,
    now: Long,
) {
    var presentationElapsedMillis by remember {
        mutableIntStateOf(SKILL_PRESENTATION_DURATION_MILLIS)
    }
    var observedActionSequence by remember {
        mutableLongStateOf(state.actionSequence)
    }
    LaunchedEffect(state.actionSequence) {
        if (state.actionSequence != observedActionSequence) {
            observedActionSequence = state.actionSequence
            presentationElapsedMillis = 0
            val startedAtNanos = withFrameNanos { it }
            do {
                val frameNanos = withFrameNanos { it }
                presentationElapsedMillis = combatPresentationElapsedMillis(
                    startedAtNanos = startedAtNanos,
                    frameNanos = frameNanos,
                )
            } while (presentationElapsedMillis < SKILL_PRESENTATION_DURATION_MILLIS)
        } else {
            presentationElapsedMillis = SKILL_PRESENTATION_DURATION_MILLIS
        }
    }
    if (state.combatPhase == CombatPhase.REVEAL) {
        EncounterPanel(state, now)
        return
    }
    val elapsedMillis = combatPresentationElapsedForCurrentFrame(
        currentActionSequence = state.actionSequence,
        observedActionSequence = observedActionSequence,
        elapsedMillis = presentationElapsedMillis,
    )
    val impactProgress = elapsedMillis.toFloat() / SKILL_PRESENTATION_DURATION_MILLIS.toFloat()
    val showAttackPresentation = attackPresentationVisible(
        progress = impactProgress,
        damage = state.lastDamage,
    )
    val definition = if (state.lastAttackWasSkill) {
        skillDefinition(state.lastSkillCatalogId)
    } else {
        null
    }
    val displayedAttackName = if (state.lastAttackWasSkill && state.lastAttackName.isNotBlank()) {
        state.skills.firstOrNull { it.catalogId == state.lastSkillCatalogId }?.displayName
            ?: "${state.lastAttackName} LV.1"
    } else {
        state.lastAttackName
    }
    val monsterName = localizedMonsterName(
        state.monster.name,
        state.monster.baseName,
        LocalAppLanguage.current,
        catalogId = state.monster.catalogId,
    )
    val monsterHeaderHeight = monsterHeaderHeightDp(monsterName.length).dp
    val monsterHeaderFontSize = monsterHeaderFontSizeSp(monsterName.length).sp
    val monsterHeaderLineHeight = monsterHeaderLineHeightSp(monsterName.length).sp
    val monsterHeaderMaxLines = monsterHeaderMaxLines(monsterName.length)
    val monsterHeaderSpacing = monsterHeaderProgressSpacingDp(monsterName.length).dp
    val palette = definition?.let { skillPalette(it.element) }
    val reducedMotion = !ValueAnimator.areAnimatorsEnabled()
    val cameraFrame = if (
        showAttackPresentation && definition != null && !reducedMotion
    ) {
        skillCameraFrame(elapsedMillis, definition)
    } else {
        SkillCameraFrame()
    }
    val fallbackShake = if (definition == null && !reducedMotion) {
        backgroundShake(impactProgress, isSkill = state.lastAttackWasSkill)
    } else {
        0f
    }
    val basicDamageMotion = damageMotion(impactProgress, isSkill = false)
    val damageFrame = definition?.let {
        skillDamageFrame(elapsedMillis, it, state.lastDamage)
    }
    val startEnergy = energyFractionBeforeLastAttack(state, energyFraction)
    val displayedEnergy = when {
        !showAttackPresentation -> energyFraction
        definition != null -> skillEnergyFraction(
            elapsedMillis = elapsedMillis,
            startFraction = startEnergy,
            endFraction = energyFraction,
            definition = definition,
            reducedMotion = reducedMotion,
        )
        else -> basicEnergyFraction(
            elapsedMillis = elapsedMillis,
            startFraction = startEnergy,
            endFraction = energyFraction,
        )
    }.coerceIn(0f, 1f)
    val labelAlpha = if (definition != null) {
        skillLabelAlpha(elapsedMillis, definition)
    } else {
        attackLabelMotion(impactProgress).alpha
    }
    val damageVisible = if (definition != null) {
        showAttackPresentation && damageFrame?.visible == true
    } else {
        showAttackPresentation
    }
    val displayedDamage = damageFrame?.damage ?: state.lastDamage
    val displayedDamageAlpha = if (reducedMotion && damageVisible) {
        1f
    } else {
        damageFrame?.alpha ?: basicDamageMotion.alpha
    }
    val displayedDamageScale = if (reducedMotion) 1f else damageFrame?.scale ?: basicDamageMotion.scale
    val displayedDamageY = if (reducedMotion) 0f else damageFrame?.translationY ?: basicDamageMotion.translationY
    val damageFontSize = skillDamageFontSize(
        hitCount = definition?.hitCount ?: 1,
        isFinal = damageFrame?.isFinal ?: true,
    )
    val damageStrokeWidth = with(LocalDensity.current) { DAMAGE_TEXT_STROKE_DP.dp.toPx() }
    Card(
        modifier = Modifier.fillMaxWidth().height(218.dp).padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(22.dp),
    ) {
        Box(Modifier.fillMaxSize().border(1.dp, AqGoldSoft, RoundedCornerShape(22.dp))) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    translationX = fallbackShake + cameraFrame.translationX
                    translationY = cameraFrame.translationY
                    scaleX = 1.02f * cameraFrame.scale
                    scaleY = 1.02f * cameraFrame.scale
                },
            ) {
                Image(
                    painter = painterResource(
                        battleBackgroundResource(
                            definitionId = state.adventureTale.definitionId,
                            chapterNumber = state.adventureTale.chapterNumber,
                        ),
                    ),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            listOf(Color(0xB8120E19), Color(0xA8181220), Color(0xF015101B)),
                        ),
                    ),
                )
            }
            if (showAttackPresentation && definition != null) {
                SkillEffectLayer(
                    definition = definition,
                    elapsedMillis = elapsedMillis,
                    reducedMotion = reducedMotion,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .align(Alignment.BottomCenter),
                )
            }
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(monsterHeaderHeight)
                        .background(Color(0xEE17111F))
                        .border(1.dp, AqGoldSoft)
                        .padding(horizontal = 15.dp, vertical = 8.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            monsterName,
                            color = AqText,
                            fontSize = monsterHeaderFontSize,
                            lineHeight = monsterHeaderLineHeight,
                            fontWeight = FontWeight.Bold,
                            maxLines = monsterHeaderMaxLines,
                            overflow = MONSTER_HEADER_OVERFLOW,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        val monsterGradeLabel = if (state.monster.isLabyrinthGateBoss) {
                            "관문 보스"
                        } else {
                            state.monster.grade.labelKo
                        }
                        Text(
                            monsterGradeLabel,
                            color = monsterGradeColor(monsterGradeLabel),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(monsterHeaderSpacing))
                    LinearProgressIndicator(
                        progress = { displayedEnergy },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(99.dp))
                            .semantics {
                                contentDescription = localized("몬스터 전투 진행")
                                stateDescription = localized("${(displayedEnergy * 100f).toInt()}퍼센트")
                            },
                        color = AqRed,
                        trackColor = Color(0xFF4A2636),
                    )
                }
                Box(
                    Modifier.fillMaxWidth().weight(1f).padding(horizontal = 18.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(29.dp)
                            .offset(y = SKILL_LABEL_OFFSET_DP.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (
                            attackLabelVisible(
                                showAttackPresentation = showAttackPresentation,
                                isSkill = state.lastAttackWasSkill,
                                attackName = displayedAttackName,
                            ) && labelAlpha > 0f
                        ) {
                            Text(
                                displayedAttackName,
                                modifier = Modifier
                                    .widthIn(max = 236.dp)
                                    .graphicsLayer { alpha = labelAlpha }
                                    .background(Color(0xB317111F), RoundedCornerShape(10.dp))
                                    .border(
                                        width = 0.8.dp,
                                        color = (palette?.primary ?: AqGoldSoft).copy(alpha = 0.45f),
                                        shape = RoundedCornerShape(10.dp),
                                    )
                                    .padding(horizontal = 10.dp, vertical = 3.dp),
                                color = AqText,
                                fontSize = 16.sp,
                                lineHeight = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    shadow = Shadow(
                                        color = Color.Black.copy(alpha = 0.65f),
                                        offset = Offset(0f, 2f),
                                        blurRadius = 5f,
                                    ),
                                ),
                            )
                        }
                    }
                }
            }
            // Last child plus an explicit z plane: damage always stays above VFX, monster UI and
            // the skill label. A stroke/fill pair stays readable without cutting VFX art.
            if (damageVisible) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .align(Alignment.BottomCenter)
                        .zIndex(DAMAGE_TEXT_Z_INDEX)
                        .clearAndSetSemantics { },
                    contentAlignment = Alignment.Center,
                ) {
                    val damageText = "-${displayedDamage.format()}"
                    val damageTransform = Modifier.graphicsLayer {
                        translationY = displayedDamageY
                        scaleX = displayedDamageScale
                        scaleY = displayedDamageScale
                        alpha = displayedDamageAlpha
                    }
                    Text(
                        damageText,
                        modifier = damageTransform,
                        textAlign = TextAlign.Center,
                        color = Color.Black,
                        fontSize = damageFontSize.sp,
                        fontWeight = FontWeight.Black,
                        style = MaterialTheme.typography.displayMedium.copy(
                            drawStyle = Stroke(width = damageStrokeWidth),
                        ),
                    )
                    Text(
                        damageText,
                        modifier = damageTransform,
                        textAlign = TextAlign.Center,
                        color = palette?.damage ?: lerp(
                            Color.White,
                            AqGold,
                            basicDamageMotion.colorProgress,
                        ),
                        fontSize = damageFontSize.sp,
                        fontWeight = FontWeight.Black,
                        style = MaterialTheme.typography.displayMedium.copy(
                            shadow = Shadow(
                                color = Color.Black.copy(alpha = 0.90f),
                                offset = Offset(0f, 8f),
                                blurRadius = 14f,
                            ),
                        ),
                    )
                }
            }
        }
    }
}

internal const val DAMAGE_TEXT_Z_INDEX = 100f
internal const val DAMAGE_TEXT_STROKE_DP = 3f

internal fun combatPresentationElapsedMillis(startedAtNanos: Long, frameNanos: Long): Int =
    ((frameNanos - startedAtNanos).coerceAtLeast(0L) / 1_000_000L)
        .coerceAtMost(SKILL_PRESENTATION_DURATION_MILLIS.toLong())
        .toInt()

internal fun combatPresentationElapsedForCurrentFrame(
    currentActionSequence: Long,
    observedActionSequence: Long,
    elapsedMillis: Int,
): Int = if (currentActionSequence != observedActionSequence) {
    0
} else {
    elapsedMillis.coerceIn(0, SKILL_PRESENTATION_DURATION_MILLIS)
}

internal fun energyFractionBeforeLastAttack(state: SimpleGameState, endFraction: Float): Float {
    val maxEnergy = state.monster.maxEnergy
    val previousEnergy = state.lastMonsterEnergyBeforeAttack
    if (maxEnergy <= 0L || previousEnergy <= 0L) return endFraction.coerceIn(0f, 1f)
    return (previousEnergy.toDouble() / maxEnergy.toDouble()).toFloat()
        .coerceIn(0f, 1f)
}

internal fun basicEnergyFraction(
    elapsedMillis: Int,
    startFraction: Float,
    endFraction: Float,
): Float {
    val start = startFraction.coerceIn(0f, 1f)
    val end = endFraction.coerceIn(0f, 1f)
    val hitAt = 125
    val duration = 96
    return when {
        elapsedMillis < hitAt -> start
        elapsedMillis >= hitAt + duration -> end
        else -> {
            val progress = (elapsedMillis - hitAt).toFloat() / duration.toFloat()
            val eased = 1f - (1f - progress) * (1f - progress)
            start + (end - start) * eased
        }
    }.coerceIn(0f, 1f)
}

internal fun skillDamageFontSize(hitCount: Int, isFinal: Boolean): Int = when (hitCount) {
    1 -> 46
    2 -> if (isFinal) 43 else 38
    3 -> if (isFinal) 42 else 34
    4 -> if (isFinal) 40 else 31
    else -> if (isFinal) 40 else 28
}

@Composable
private fun MainPanel(
    state: SimpleGameState,
    repository: SimpleGameRepository,
    rankingUiState: RankingUiState,
    onOpenRanking: () -> Unit,
    unreadRecentEventCount: Int,
    onOpenRecentEvents: () -> Unit,
) {
    val tale = state.adventureTale
    val act = tale.activeAct()
    val required = repository.experienceRequired(state.hero.level)
    val inventoryCapacity = state.inventoryCapacity()
    val experienceProgress = ratio(state.hero.experience, required)
    val sceneProgress = ratio(act.progress, act.target)
    val bagProgress = ratio(state.inventory.size.toLong(), inventoryCapacity)
    val taleProgress = mainTaleProgress(
        currentActIndex = tale.currentActIndex,
        actCount = tale.acts.size,
        currentActProgress = act.progress,
        currentActTarget = act.target,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactRankingEntryMenu(
                uiState = rankingUiState,
                onClick = onOpenRanking,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            RecentAdventureEventsButton(
                unreadCount = unreadRecentEventCount,
                onClick = onOpenRecentEvents,
                modifier = Modifier.height(COMPACT_RANKING_ENTRY_MENU_HEIGHT_DP.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        MainSceneProgress(
            title = act.title,
            progress = sceneProgress,
            value = "${act.progress}/${act.target}",
            backgroundResourceId = battleBackgroundResource(
                definitionId = tale.definitionId,
                chapterNumber = tale.chapterNumber,
            ),
        )
        Spacer(Modifier.height(6.dp))
        MainStatusRow(
            icon = Icons.Filled.WorkspacePremium,
            title = "경험치",
            detail = "${state.hero.experience.format()} / ${required.format()}",
            progress = experienceProgress,
            color = AqGold,
        )
        DividerLine()
        MainStatusRow(
            icon = Icons.Filled.Backpack,
            title = "가방",
            detail = when (state.adventurePhase) {
                AdventurePhase.OPENING -> "모험의 첫 장을 여는 중"
                AdventurePhase.RETURNING -> "가득 차서 마을로 귀환 중"
                AdventurePhase.EQUIPPING -> "드롭 장비를 부위별로 비교 중"
                AdventurePhase.SELLING -> "마을에서 전리품 판매 중"
                AdventurePhase.SHOPPING_EMPTY -> "살 수 있는 더 좋은 장비가 없어 출정 준비 중"
                else -> "가득 차면 자동 귀환"
            },
            progress = bagProgress,
            color = MainBagAccent,
            value = "${state.inventory.size}/$inventoryCapacity",
        )
        DividerLine()
        MainStatusRow(
            icon = Icons.AutoMirrored.Filled.MenuBook,
            title = "모험담",
            detail = taleVolumeLabel(
                kind = tale.kind,
                volumeNumber = tale.volumeNumber,
                volumeTitle = tale.volumeTitle,
                heroLevel = state.hero.level,
            ),
            progress = taleProgress,
            color = MainTaleAccent,
            value = mainTaleActCompletionLabel(
                currentActIndex = tale.currentActIndex,
                actCount = tale.acts.size,
                currentActCompleted = act.completed,
            ),
        )
    }
}

private val MainSceneAccent = Color(0xFFCF8BE8)
private val MainBagAccent = Color(0xFF70B7D8)
private val MainTaleAccent = Color(0xFF8BCB84)

@Composable
private fun MainSceneProgress(
    title: String,
    progress: Float,
    value: String,
    @DrawableRes backgroundResourceId: Int,
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(650),
        label = "현재 장면",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF281D32))
            .border(1.dp, MainSceneAccent.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
            .semantics(mergeDescendants = true) {
                contentDescription = localized("현재 장면 $title, $value")
                progressBarRangeInfo = ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f)
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(backgroundResourceId),
            contentDescription = null,
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .border(1.dp, MainSceneAccent.copy(alpha = 0.48f), CircleShape),
            alignment = Alignment.CenterEnd,
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    color = AqText,
                    fontSize = 16.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = value,
                    color = MainSceneAccent,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { animated },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .clearAndSetSemantics { },
                color = MainSceneAccent,
                trackColor = Color(0xFF493A52),
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}

@Composable
private fun MainStatusRow(
    icon: ImageVector,
    title: String,
    detail: String,
    progress: Float,
    color: Color,
    value: String? = null,
) {
    val safeProgress = progress.coerceIn(0f, 1f)
    val animated by animateFloatAsState(
        targetValue = safeProgress,
        animationSpec = tween(650),
        label = title,
    )
    val percent = mainStatusPercent(safeProgress)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = buildList {
                    add(localized(title))
                    add(localized(detail))
                    if (value != null) add(localized(value))
                    add(localized("${percent}퍼센트"))
                }.joinToString(", ")
                progressBarRangeInfo = ProgressBarRangeInfo(safeProgress, 0f..1f)
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = AqText,
                fontSize = 13.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text(
                text = detail,
                color = AqMuted,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                maxLines = 3,
                overflow = TextOverflow.Visible,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(
            modifier = Modifier.width(116.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.Center,
        ) {
            if (value != null) {
                Text(
                    text = value,
                    color = color,
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(2.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LinearProgressIndicator(
                    progress = { animated },
                    modifier = Modifier
                        .weight(1f)
                        .height(5.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .clearAndSetSemantics { },
                    color = color,
                    trackColor = Color(0xFF493A52),
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "$percent%",
                    color = color,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(30.dp),
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

internal fun mainStatusPercent(progress: Float): Int =
    (progress.coerceIn(0f, 1f) * 100f).toInt()

internal fun mainTaleProgress(
    currentActIndex: Int,
    actCount: Int,
    currentActProgress: Long,
    currentActTarget: Long,
): Float {
    if (actCount <= 0) return 0f
    if (currentActIndex >= actCount) return 1f

    val completedActCount = currentActIndex.coerceAtLeast(0)
    val currentActFraction = if (currentActTarget <= 0L) {
        0.0
    } else {
        (currentActProgress.toDouble() / currentActTarget.toDouble()).coerceIn(0.0, 1.0)
    }
    return ((completedActCount.toDouble() + currentActFraction) / actCount.toDouble())
        .toFloat()
        .coerceIn(0f, 1f)
}

internal fun mainTaleActCompletionLabel(
    currentActIndex: Int,
    actCount: Int,
    currentActCompleted: Boolean,
): String {
    if (actCount <= 0) return "0/0 완료"
    val completedActCount = (
        currentActIndex.coerceIn(0, actCount) +
            if (currentActIndex in 0 until actCount && currentActCompleted) 1 else 0
    ).coerceAtMost(actCount)
    return "$completedActCount/$actCount 완료"
}

@Composable
private fun CharacterPanel(
    state: SimpleGameState,
) {
    var detailTab by rememberSaveable(state.hero.name) { mutableStateOf(CharacterDetailTab.SKILLS) }
    PanelCard {
        StatSectionHeading(title = "능력치")
        Spacer(Modifier.height(10.dp))
        CharacterStatSheet(state.hero.stats)
        Spacer(Modifier.height(12.dp))
        DividerLine()
        Spacer(Modifier.height(14.dp))
        CharacterDetailTabs(
            selected = detailTab,
            onSelect = { tab -> detailTab = tab },
        )
        Spacer(Modifier.height(10.dp))
        when (detailTab) {
            CharacterDetailTab.SKILLS -> {
                if (state.skills.isEmpty()) {
                    EmptyText("보유한 스킬이 없습니다.")
                } else {
                    state.skills.forEachIndexed { index, skill ->
                        SkillListRow(skill)
                        if (index < state.skills.lastIndex) DividerLine()
                    }
                }
            }
            CharacterDetailTab.TRAITS -> AdventureTraitProfile(state)
            CharacterDetailTab.RELATIONSHIPS -> AdventureRelationshipProfile(state)
        }
    }
}

private enum class CharacterDetailTab(val label: String) {
    SKILLS("스킬"),
    TRAITS("특성"),
    RELATIONSHIPS("인연"),
}

@Composable
private fun CharacterDetailTabs(
    selected: CharacterDetailTab,
    onSelect: (CharacterDetailTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(AqSurfaceHigh)
            .selectableGroup()
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CharacterDetailTab.entries.filter {
            it == CharacterDetailTab.SKILLS || BuildConfig.ADVENTURE_SYSTEM_ENABLED
        }.forEach { tab ->
            val isSelected = tab == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (isSelected) AqGold.copy(alpha = 0.16f) else Color.Transparent)
                    .selectable(
                        selected = isSelected,
                        role = Role.Tab,
                        onClick = { onSelect(tab) },
                    )
                    .semantics {
                        this.selected = isSelected
                        stateDescription = localized(if (isSelected) "선택됨" else "선택 안 됨")
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = when (tab) {
                        CharacterDetailTab.SKILLS -> journeyText(LocalAppLanguage.current, "스킬", "Skills", "スキル")
                        CharacterDetailTab.TRAITS -> journeyText(LocalAppLanguage.current, "특성", "Traits", "特性")
                        CharacterDetailTab.RELATIONSHIPS -> journeyText(LocalAppLanguage.current, "인연", "Relationships", "縁")
                    },
                    color = if (isSelected) AqGold else AqMuted,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                )
            }
        }
    }
}

internal fun arenaMatchFoundMessage(language: AppLanguage): String = when (language) {
    AppLanguage.KOREAN -> "결투장 상대를 찾았습니다"
    AppLanguage.ENGLISH -> "Arena opponent found"
    AppLanguage.JAPANESE -> "闘技場の対戦相手が見つかりました"
}

internal fun arenaMatchFoundActionLabel(language: AppLanguage): String = when (language) {
    AppLanguage.KOREAN -> "이동"
    AppLanguage.ENGLISH -> "Go"
    AppLanguage.JAPANESE -> "移動"
}

internal fun arenaMatchFoundAccessibilityLabel(language: AppLanguage): String = when (language) {
    AppLanguage.KOREAN -> "결투장 상대를 찾았습니다. 결투장으로 이동."
    AppLanguage.ENGLISH -> "Arena opponent found. Go to Arena."
    AppLanguage.JAPANESE -> "闘技場の対戦相手が見つかりました。闘技場へ移動します。"
}

@Composable
private fun BattleMatchFoundArrivalStrip(
    onOpen: () -> Unit,
) {
    val language = LocalAppLanguage.current
    val shape = RoundedCornerShape(
        topStart = 4.dp,
        topEnd = 4.dp,
        bottomStart = 20.dp,
        bottomEnd = 20.dp,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .heightIn(min = 52.dp)
            .clip(shape)
            .background(AqSurface)
            .border(1.dp, AqGoldSoft, shape)
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = arenaMatchFoundAccessibilityLabel(language)
            }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Filled.Shield,
                contentDescription = null,
                tint = AqText,
                modifier = Modifier.size(23.dp),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(7.dp)
                    .background(AqGold, CircleShape),
            )
        }
        Spacer(Modifier.width(9.dp))
        UnlocalizedText(
            text = arenaMatchFoundMessage(language),
            modifier = Modifier.weight(1f),
            color = AqText,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.width(1.dp).height(24.dp).background(Color(0xFF4A3B4F)))
        Spacer(Modifier.width(10.dp))
        UnlocalizedText(
            arenaMatchFoundActionLabel(language),
            color = AqGold,
            fontSize = 13.sp,
            fontWeight = FontWeight.Black,
        )
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = AqGold,
            modifier = Modifier.size(19.dp),
        )
    }
}

@Composable
private fun HeroPathChoiceSheet(
    state: SimpleGameState,
    token: com.nullplaying.model.HeroPathMilestoneToken,
    onChoiceSelected: (eventId: String, offerId: String) -> Unit,
    onFullTreeSelected: () -> Unit,
    onDeferred: () -> Unit,
    onDismiss: () -> Unit,
) {
    val choice = remember(state.heroPath.revision, token.tokenId, token.offers) {
        heroPathChoiceModel(state, token)
    }
    val lanes = remember(state.heroPath.heroClass) {
        heroPathPanelModel(state, HeroPathFilter.ALL).lanes
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(60f)
            .background(AqBackground.copy(alpha = 0.78f))
            .pointerInput(token.tokenId) {
                detectTapGestures(onTap = { onDismiss() })
            }
            .semantics {
                dialog()
                paneTitle = localized("새로운 갈림길")
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        HeroPathChoiceSheetContent(
            choice = choice,
            lanes = lanes,
            onChoiceSelected = onChoiceSelected,
            onFullTreeSelected = onFullTreeSelected,
            onDeferred = onDeferred,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight * 0.88f)
                .pointerInput(Unit) { detectTapGestures(onTap = {}) },
        )
    }
}

private enum class ItemSection(val label: String) {
    EQUIPMENT("장비"),
    BAG("가방"),
}

@Composable
private fun ItemsPanel(state: SimpleGameState) {
    var selectedSection by rememberSaveable(state.hero.name) {
        mutableStateOf(ItemSection.EQUIPMENT)
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(AqSurface)
                .selectableGroup()
                .padding(3.dp),
        ) {
            ItemSection.entries.forEach { section ->
                val selected = selectedSection == section
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (selected) AqGold.copy(alpha = 0.16f) else Color.Transparent)
                        .selectable(
                            selected = selected,
                            role = Role.Tab,
                            onClick = { selectedSection = section },
                        )
                        .semantics { stateDescription = localized(if (selected) "선택됨" else "선택 안 됨") },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        section.label,
                        color = if (selected) AqGold else AqMuted,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when (selectedSection) {
                ItemSection.EQUIPMENT -> EquipmentPanel(state)
                ItemSection.BAG -> BagPanel(state)
            }
        }
    }
}

@Composable
private fun CharacterStatSheet(stats: HeroStats) {
    val values = stats.values()
    val dividerColor = Color(0xFF46394F)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AqSurfaceHigh),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(54.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            values.take(6).forEachIndexed { index, value ->
                CharacterStatCell(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    label = HeroStats.labels[index],
                    value = value,
                    valueColor = AqText,
                    valueFontSize = 14,
                )
                if (index < 5) {
                    Spacer(Modifier.width(1.dp).height(28.dp).background(dividerColor))
                }
            }
        }

        Spacer(Modifier.fillMaxWidth().height(1.dp).background(dividerColor))

        Row(
            modifier = Modifier.fillMaxWidth().height(50.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            values.drop(6).forEachIndexed { itemIndex, value ->
                val index = itemIndex + 6
                CharacterStatCell(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    label = HeroStats.labels[index],
                    value = value,
                    valueColor = AqGold,
                    valueFontSize = 17,
                )
                if (itemIndex == 0) {
                    Spacer(Modifier.width(1.dp).height(28.dp).background(dividerColor))
                }
            }
        }
    }
}

@Composable
private fun CharacterStatCell(
    modifier: Modifier,
    label: String,
    value: Long,
    valueColor: Color,
    valueFontSize: Int,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            color = AqMuted,
            fontSize = 9.sp,
            maxLines = 1,
            softWrap = false,
        )
        Text(
            text = value.format(),
            modifier = Modifier.fillMaxWidth(),
            color = valueColor,
            fontWeight = FontWeight.Bold,
            fontSize = valueFontSize.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EquipmentPanel(state: SimpleGameState) {
    val language = LocalAppLanguage.current
    PanelCard {
        Text("장착 장비", color = AqText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(7.dp))
        state.equipment.forEach { item ->
            val displayName = localizedEquipmentName(item.name, language)
            Row(
                Modifier.fillMaxWidth().height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    item.slot.labelKo,
                    color = AqMuted,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Visible,
                    modifier = Modifier.width(68.dp),
                )
                Text(
                    displayName,
                    color = rarityColor(item.rarity),
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Visible,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                Text("${item.power.format()}", color = AqGold, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun BagPanel(state: SimpleGameState) {
    ScrollablePanelCard {
        item {
            SectionHeader(
                title = "가방 ${state.inventory.size}/${state.inventoryCapacity()}",
                subtitle = "${state.hero.gold.format()} G",
            )
            Spacer(Modifier.height(6.dp))
        }
        if (state.inventory.isEmpty()) {
            item { EmptyText("첫 전리품을 기다리는 중입니다.") }
        } else {
            items(
                items = bagItemRows(state.inventory),
                key = { row -> row.joinToString(separator = ":") { it.id.toString() } },
            ) { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowItems.forEach { inventoryItem ->
                        BagItemCard(
                            item = inventoryItem,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (rowItems.size < BAG_ITEMS_PER_ROW) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

internal const val BAG_ITEMS_PER_ROW = 2

internal fun bagItemRows(items: List<InventoryItem>): List<List<InventoryItem>> =
    items.chunked(BAG_ITEMS_PER_ROW)

@Composable
private fun BagItemCard(
    item: InventoryItem,
    modifier: Modifier = Modifier,
) {
    val language = LocalAppLanguage.current
    val displayName = if (item.equipmentSlot != null) {
        localizedEquipmentName(item.name, language)
    } else {
        localizedItemName(item.name, language)
    }
    val detail = if (item.equipmentSlot != null && item.equipmentPower != null) {
        "${item.equipmentSlot.labelKo} · 장비력 ${item.equipmentPower.format()} · ${item.rarity}"
    } else {
        "${item.kind} · ${item.rarity} · Lv.${item.foundAtLevel}"
    }
    Column(
        modifier = modifier
            .height(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(AqSurfaceHigh)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = listOf(displayName, detail)
                    .joinToString(", ") { localized(it) }
            },
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = displayName,
            color = rarityColor(item.rarity),
            fontWeight = FontWeight.Bold,
            fontSize = 10.4f.sp,
            lineHeight = 11.2f.sp,
            maxLines = 3,
            overflow = TextOverflow.Visible,
        )
        Text(
            text = detail,
            color = AqMuted,
            fontSize = 10.sp,
            lineHeight = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun QuestPanel(state: SimpleGameState) {
    val tale = state.adventureTale
    val scrollState = rememberScrollState()
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    val completedHistory = completedTalesInReadingOrder(state.completedTaleHistory)

    Card(
        modifier = Modifier
            .fillMaxSize()
            .border(1.dp, Color(0xFF3C3045), RoundedCornerShape(22.dp)),
        colors = CardDefaults.cardColors(containerColor = AqSurface),
        shape = RoundedCornerShape(22.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 44.dp),
            ) {
            TaleHeader(
                kind = tale.kind,
                volumeNumber = tale.volumeNumber,
                chapterNumber = tale.chapterNumber,
                volumeTitle = tale.volumeTitle,
                chapterTitle = tale.title,
                chapterSubtitle = tale.subtitle,
                heroLevel = state.hero.level,
                labyrinthDepth = tale.labyrinthDepth,
                highestLabyrinthDepth = state.labyrinthDepthCompleted,
            )
            tale.acts.forEachIndexed { index, act ->
                TaleActTimelineRow(
                    act = act,
                    index = index,
                    currentActIndex = tale.currentActIndex,
                    totalActs = tale.acts.size,
                    heroName = state.hero.name,
                )
            }
            Spacer(Modifier.height(10.dp))
            PastTalesEntry(
                expanded = historyOpen,
                hasHistory = completedHistory.isNotEmpty(),
                onClick = { historyOpen = !historyOpen },
            )
            if (historyOpen) {
                if (completedHistory.isEmpty()) {
                    Text(
                        "아직 완결된 모험담이 없습니다.\n이 이야기의 5막을 끝내면 여기에 남습니다.",
                        color = AqMuted,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                    )
                } else {
                    completedHistory.forEach { record ->
                        CompletedTaleCard(record = record, heroName = state.hero.name)
                    }
                }
            }
            }
            if (scrollState.canScrollForward) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, AqSurface.copy(alpha = 0.96f), AqSurface),
                            ),
                        )
                        .clearAndSetSemantics {
                            contentDescription = localized(QUEST_SCROLL_HINT_DESCRIPTION)
                        }
                        .padding(top = 12.dp, bottom = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = QUEST_SCROLL_HINT_LABEL,
                        color = AqGold,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Icon(
                        imageVector = Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = AqGold,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

internal const val QUEST_SCROLL_HINT_LABEL = "아래로 스크롤"
internal const val QUEST_SCROLL_HINT_DESCRIPTION = "아래에 더 많은 모험 기록이 있습니다"

@Composable
private fun TaleHeader(
    kind: TaleKind,
    volumeNumber: Int,
    chapterNumber: Int,
    volumeTitle: String,
    chapterTitle: String,
    chapterSubtitle: String,
    heroLevel: Long,
    labyrinthDepth: Long,
    highestLabyrinthDepth: Long,
) {
    val currentLabyrinthDepth = resolvedLabyrinthDepth(labyrinthDepth, chapterNumber)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AqSurface)
            .padding(bottom = 8.dp),
    ) {
        Text(
            taleVolumeLabel(kind, volumeNumber, volumeTitle, heroLevel),
            color = AqText,
            fontSize = 20.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(2.dp))
        Text(
            taleChapterLabel(kind, chapterNumber, chapterTitle, labyrinthDepth),
            color = AqMuted,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            taleSubtitleLabel(kind, chapterSubtitle, heroLevel),
            color = AqMuted.copy(alpha = 0.82f),
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        if (kind == TaleKind.LABYRINTH) {
            Spacer(Modifier.height(10.dp))
            LabyrinthDepthSummary(
                heroLevel = heroLevel,
                currentDepth = currentLabyrinthDepth,
                highestCompletedDepth = highestLabyrinthDepth,
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF3C3045)))
    }
}

@Composable
private fun LabyrinthDepthSummary(
    heroLevel: Long,
    currentDepth: Long,
    highestCompletedDepth: Long,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 116.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(AqSurfaceHigh)
            .clearAndSetSemantics {
                contentDescription = localized(
                    labyrinthDepthContentDescription(
                        heroLevel = heroLevel,
                        currentDepth = currentDepth,
                        highestCompletedDepth = highestCompletedDepth,
                    ),
                )
            }
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("현재 깊이", color = AqMuted, fontSize = 11.sp, lineHeight = 16.sp)
                Text(
                    "제${currentDepth.coerceAtLeast(1L)}구역",
                    color = AqText,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Box(Modifier.width(1.dp).height(34.dp).background(Color(0xFF51445B)))
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.End,
            ) {
                Text("최고 완주 깊이", color = AqMuted, fontSize = 11.sp, lineHeight = 16.sp)
                Text(
                    labyrinthHighestDepthValueLabel(highestCompletedDepth),
                    color = AqGold,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF51445B)))
        Spacer(Modifier.height(7.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                labyrinthTitleLabel(highestCompletedDepth),
                color = AqGold,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(
                labyrinthNextGateLabel(highestCompletedDepth),
                color = AqMuted,
                fontSize = 11.sp,
                lineHeight = 18.sp,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            labyrinthDepthRuleLabel(currentDepth),
            color = if (LabyrinthProgression.isGateDepth(currentDepth)) AqGold else AqMuted,
            fontSize = 11.sp,
            lineHeight = 17.sp,
        )
    }
}

@Composable
private fun TaleActTimelineRow(
    act: TaleActState,
    index: Int,
    currentActIndex: Int,
    totalActs: Int,
    heroName: String,
) {
    val isCompleted = act.completed || index < currentActIndex
    val isCurrent = !isCompleted && index == currentActIndex
    val isScheduled = !isCompleted && index == currentActIndex + 1
    val isLocked = !isCompleted && !isCurrent && !isScheduled
    var completedExpanded by rememberSaveable(act.id) { mutableStateOf(true) }
    val status = when {
        isCompleted -> "완료"
        isCurrent -> "진행 중"
        isScheduled -> "예정"
        else -> "잠김"
    }
    val titleColor = when {
        isCompleted -> TaleCompleteGreen
        isCurrent -> AqGold
        else -> AqMuted.copy(alpha = if (isLocked) 0.62f else 0.82f)
    }
    val localizedActNumber = localized("${act.number}막")
    val localizedExpandedHint = localized("펼쳐짐. 두 번 탭하여 접기.")
    val localizedCollapsedHint = localized("접힘. 두 번 탭하여 펼치기.")
    val localizedProgress = localized("${act.target}회 중 ${act.progress}회 완료")
    val localizedAutoProgress = localized("자동 진행 중")
    val localizedNotStarted = localized("아직 시작되지 않음.")
    val localizedPreviousIncomplete = localized("앞의 장면이 아직 끝나지 않음.")
    val description = buildString {
        append("${localized(status)}, $localizedActNumber ${localized(act.title)}.")
        when {
            isCompleted && completedExpanded -> append(
                " ${localizedStoryText(act.completionBody, heroName)}. " +
                    localizedExpandedHint,
            )
            isCompleted -> append(" $localizedCollapsedHint")
            isCurrent -> append(
                " ${localizedStoryText(act.body, heroName)}. " +
                    "$localizedProgress. " +
                    "$localizedAutoProgress.",
            )
            isScheduled -> append(" $localizedNotStarted")
            else -> append(" $localizedPreviousIncomplete")
        }
    }
    val baseModifier = Modifier
        .fillMaxWidth()
        .heightIn(min = when {
            isCurrent -> 96.dp
            isCompleted && completedExpanded -> 62.dp
            else -> 52.dp
        })
        .drawBehind {
            val railX = 12.dp.toPx()
            val nodeCenterY = 24.dp.toPx()
            val mutedRail = Color(0xFF81768A).copy(alpha = 0.55f)
            if (index > 0) {
                drawLine(
                    color = if (index <= currentActIndex) TaleCompleteGreen.copy(alpha = 0.55f) else mutedRail,
                    start = Offset(railX, 0f),
                    end = Offset(railX, nodeCenterY),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            if (index < totalActs - 1) {
                drawLine(
                    color = if (index < currentActIndex) TaleCompleteGreen.copy(alpha = 0.55f) else mutedRail,
                    start = Offset(railX, nodeCenterY),
                    end = Offset(railX, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }
        .semantics(mergeDescendants = true) {
            contentDescription = description
            stateDescription = localized(when {
                isCompleted -> if (completedExpanded) "완료, 펼쳐짐" else "완료, 접힘"
                isCurrent -> "${act.target}회 중 ${act.progress}회 완료"
                isScheduled -> "예정"
                else -> "잠김"
            })
            if (isCurrent) {
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = act.progress.toFloat().coerceIn(0f, act.target.toFloat()),
                    range = 0f..act.target.toFloat().coerceAtLeast(1f),
                    steps = (act.target - 1L).coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                )
            }
        }
    val rowModifier = if (isCompleted) {
        baseModifier.clickable(
            role = Role.Button,
            onClickLabel = localized(if (completedExpanded) "완료 요약 접기" else "완료 요약 펼치기"),
            onClick = { completedExpanded = !completedExpanded },
        )
    } else {
        baseModifier
    }

    Row(rowModifier, verticalAlignment = Alignment.Top) {
        TaleActRail(
            completed = isCompleted,
            current = isCurrent,
            scheduled = isScheduled,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(top = 12.dp)) {
            Text(
                "$status · ${act.number}막  ${act.title}",
                color = titleColor,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (isCompleted && completedExpanded) {
                Spacer(Modifier.height(2.dp))
                UnlocalizedText(
                    localizedStoryText(act.completionBody, heroName),
                    color = AqMuted,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                )
            }
            if (isCurrent) {
                Spacer(Modifier.height(4.dp))
                UnlocalizedText(
                    localizedStoryText(act.body, heroName),
                    color = AqMuted,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Sync, contentDescription = null, tint = AqGold, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "자동 진행 중 · ${act.progress}/${act.target}",
                        color = AqGold,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            Spacer(Modifier.height(if (isLocked || isScheduled) 9.dp else 7.dp))
            if (index < totalActs - 1) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF3C3045)))
            }
        }
    }
}

@Composable
private fun TaleActRail(
    completed: Boolean,
    current: Boolean,
    scheduled: Boolean,
) {
    Box(Modifier.width(24.dp).height(48.dp)) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp)
                .size(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                completed -> Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = TaleCompleteGreen,
                    modifier = Modifier.fillMaxSize().background(AqSurface, RoundedCornerShape(99.dp)),
                )
                current -> {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(AqGold.copy(alpha = 0.14f), RoundedCornerShape(99.dp))
                            .border(2.dp, AqGold, RoundedCornerShape(99.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(9.dp).background(AqGold, RoundedCornerShape(99.dp)))
                    }
                }
                scheduled -> Box(
                    Modifier
                        .fillMaxSize()
                        .background(AqSurface, RoundedCornerShape(99.dp))
                        .border(1.5.dp, Color(0xFF81768A), RoundedCornerShape(99.dp)),
                )
                else -> Box(
                    Modifier
                        .fillMaxSize()
                        .background(AqSurfaceHigh, RoundedCornerShape(99.dp))
                        .border(1.dp, Color(0xFF81768A).copy(alpha = 0.7f), RoundedCornerShape(99.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = AqMuted.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

@Composable
private fun PastTalesEntry(
    expanded: Boolean,
    hasHistory: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(AqSurfaceHigh)
            .clickable(
                role = Role.Button,
                onClickLabel = localized(if (expanded) "지난 모험담 접기" else "지난 모험담 펼치기"),
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {
                stateDescription = localized(if (expanded) "펼쳐짐" else "접힘")
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("지난 모험담", color = AqText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (hasHistory) "완결된 이야기" else "첫 모험담의 결말을 기다리는 중",
                color = AqMuted,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        }
        Icon(
            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = AqGold,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun CompletedTaleCard(record: CompletedTaleRecord, heroName: String) {
    var expanded by rememberSaveable(record.taleSequence, record.taleId) { mutableStateOf(false) }
    val displayTitle = completedTaleTitleLabel(record)
    val completionStatus = completedTaleStatusLabel(record)
    val localizedCompletionStatus = localized(completionStatus)
    val localizedLocation = localized(completedTaleLocationLabel(record))
    val localizedDisplayTitle = localizedStoryText(displayTitle, heroName)
    val localizedSummary = localizedStoryText(record.summary, heroName)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1B1424))
            .clickable(
                role = Role.Button,
                onClickLabel = localized(if (expanded) "모험담 회고 접기" else "모험담 회고 펼치기"),
                onClick = { expanded = !expanded },
            )
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append("$localizedCompletionStatus, $localizedLocation. ")
                    append("$localizedDisplayTitle. $localizedSummary. ")
                    append(localized(if (expanded) "펼쳐짐" else "접힘"))
                    append(".")
                }
                stateDescription = localized(if (expanded) "펼쳐짐" else "접힘")
            }
            .padding(14.dp),
    ) {
        UnlocalizedText(
            "$localizedCompletionStatus · $localizedLocation",
            color = if (completionStatus == "관문 돌파") AqGold else TaleCompleteGreen,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(2.dp))
        UnlocalizedText(
            localizedDisplayTitle,
            color = AqText,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(5.dp))
        UnlocalizedText(
            localizedSummary,
            color = AqMuted,
            fontSize = 13.sp,
            lineHeight = 20.sp,
        )
        if (expanded) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF3C3045)))
            Spacer(Modifier.height(8.dp))
            record.actMemories.forEachIndexed { index, memory ->
                UnlocalizedText(
                    "${localized("${index + 1}막")} · ${localizedStoryText(memory, heroName)}",
                    color = AqMuted,
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(vertical = 3.dp),
                )
            }
        }
    }
}

private val TaleCompleteGreen = Color(0xFF8BCB84)

internal const val BOTTOM_MENU_HEIGHT_DP = 74
internal const val BOTTOM_MENU_LABEL_BOTTOM_PADDING_DP = 8
internal const val BOTTOM_MENU_ICON_TOP_OFFSET_DP = 9

@Composable
private fun BottomMenu(
    selectedTab: MenuTab,
    onSelect: (MenuTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(BOTTOM_MENU_HEIGHT_DP.dp)
            .background(AqSurface)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        visibleMenuTabs.forEach { tab ->
            val selected = tab == selectedTab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .semantics(mergeDescendants = true) {
                        contentDescription = localized(tab.label)
                    }
                    .selectable(
                        selected = selected,
                        role = Role.Tab,
                        onClick = { onSelect(tab) },
                    ),
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = BOTTOM_MENU_ICON_TOP_OFFSET_DP.dp)
                        .size(26.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val iconResourceId = tab.iconResourceId
                    if (iconResourceId != null) {
                        Icon(
                            painter = painterResource(iconResourceId),
                            contentDescription = null,
                            tint = if (selected) AqGold else AqMuted,
                            modifier = Modifier.size(22.dp),
                        )
                    } else {
                        Icon(
                            imageVector = requireNotNull(tab.icon),
                            contentDescription = null,
                            tint = if (selected) AqGold else AqMuted,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                Text(
                    text = localized(tab.label),
                    color = if (selected) AqGold else AqMuted,
                    fontSize = 10.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = BOTTOM_MENU_LABEL_BOTTOM_PADDING_DP.dp),
                )
            }
        }
    }
}

@Composable
private fun PanelCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxSize(),
        colors = CardDefaults.cardColors(containerColor = AqSurface),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            content = content,
        )
    }
}

@Composable
private fun ScrollablePanelCard(content: LazyListScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxSize(),
        colors = CardDefaults.cardColors(containerColor = AqSurface),
        shape = RoundedCornerShape(22.dp),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            content = content,
        )
    }
}

internal fun primaryStatIndices(heroClass: HeroClass): Set<Int> =
    setOf(heroClass.primaryStatIndex, heroClass.secondaryStatIndex)

internal enum class CharacterCreationStatRole {
    PRIMARY,
    SECONDARY,
    STANDARD,
    RESOURCE,
}

internal val CharacterCreationPrimaryAccent = AqRed
internal val CharacterCreationSecondaryAccent = Color(0xFFB69ACB)
internal val CharacterCreationActionContent = Color(0xFF211808)
internal val CharacterCreationDisabledActionContainer = AqSurfaceHigh
internal val CharacterCreationDisabledActionContent = AqMuted

internal fun characterCreationStatRole(
    heroClass: HeroClass,
    statIndex: Int,
): CharacterCreationStatRole = when (statIndex) {
    heroClass.primaryStatIndex -> CharacterCreationStatRole.PRIMARY
    heroClass.secondaryStatIndex -> CharacterCreationStatRole.SECONDARY
    in 6..7 -> CharacterCreationStatRole.RESOURCE
    else -> CharacterCreationStatRole.STANDARD
}

internal fun characterCreationStatAccent(role: CharacterCreationStatRole): Color = when (role) {
    CharacterCreationStatRole.PRIMARY -> CharacterCreationPrimaryAccent
    CharacterCreationStatRole.SECONDARY -> CharacterCreationSecondaryAccent
    CharacterCreationStatRole.RESOURCE -> AqGold
    CharacterCreationStatRole.STANDARD -> AqText
}

internal fun characterCreationStatRoleLabel(role: CharacterCreationStatRole): String? = when (role) {
    CharacterCreationStatRole.PRIMARY -> "주 능력치"
    CharacterCreationStatRole.SECONDARY -> "보조 능력치"
    CharacterCreationStatRole.RESOURCE,
    CharacterCreationStatRole.STANDARD,
    -> null
}

@Composable
private fun StatGrid(
    stats: HeroStats,
    heroClass: HeroClass,
    roomy: Boolean = false,
) {
    val values = stats.values()
    Column(verticalArrangement = Arrangement.spacedBy(if (roomy) 10.dp else 6.dp)) {
        values.chunked(4).forEachIndexed { rowIndex, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                row.forEachIndexed { itemIndex, value ->
                    val index = rowIndex * 4 + itemIndex
                    val role = characterCreationStatRole(heroClass, index)
                    val isClassStat = role == CharacterCreationStatRole.PRIMARY ||
                        role == CharacterCreationStatRole.SECONDARY
                    val accent = characterCreationStatAccent(role)
                    val roleLabel = characterCreationStatRoleLabel(role)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isClassStat) {
                                    lerp(AqSurfaceHigh, accent, 0.14f)
                                } else {
                                    AqSurfaceHigh
                                },
                            )
                            .border(
                                width = 1.dp,
                                color = if (isClassStat) {
                                    accent.copy(alpha = 0.62f)
                                } else {
                                    Color.Transparent
                                },
                                shape = RoundedCornerShape(10.dp),
                            )
                            .clearAndSetSemantics {
                                contentDescription = localized(
                                    listOfNotNull(
                                        HeroStats.labels[index],
                                        value.format(),
                                        roleLabel,
                                    ).joinToString(", "),
                                )
                            }
                            .padding(vertical = if (roomy) 12.dp else 7.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            HeroStats.labels[index],
                            color = if (isClassStat) lerp(AqMuted, accent, 0.55f) else AqMuted,
                            fontSize = 10.sp,
                        )
                        roleLabel?.let { label ->
                            Text(
                                text = label,
                                color = accent,
                                fontSize = 8.sp,
                                lineHeight = 10.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                        }
                        Text(
                            value.format(),
                            color = when {
                                index >= 6 -> AqGold
                                isClassStat -> accent
                                else -> AqText
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = if (roomy) 17.sp else 14.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SkillListRow(skill: LearnedSkill) {
    val animatedProgress by animateFloatAsState(
        targetValue = skill.masteryProgress,
        animationSpec = tween(350),
        label = "${skill.catalogId}-mastery",
    )
    val experienceLabel = if (skill.isMaxLevel) {
        "MAX"
    } else {
        "${skill.masteryExperience}/${LearnedSkill.USES_PER_LEVEL}"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clearAndSetSemantics {
                contentDescription = localized(
                    if (skill.isMaxLevel) {
                        "${skill.displayName}, 스킬 경험치 최대"
                    } else {
                        "${skill.displayName}, 스킬 경험치 $experienceLabel"
                    },
                )
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = skill.masteryProgress,
                    range = 0f..1f,
                )
            },
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = skill.displayName,
            color = AqGold,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(5.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(99.dp)),
                color = AqGold,
                trackColor = Color(0xFF44364D),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = experienceLabel,
                color = if (skill.isMaxLevel) AqGold else AqMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        Text(title, color = AqText, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                color = AqGold,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp)
                    .semantics { contentDescription = localized("보유 골드 $subtitle") },
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun EmptyText(message: String) {
    Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
        Text(message, color = AqMuted, textAlign = TextAlign.Center)
    }
}

@Composable
private fun DividerLine() {
    Spacer(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF3C3045)))
}

private fun ratio(value: Long, max: Long): Float =
    if (max <= 0L) 0f else (value.toDouble() / max.toDouble()).toFloat().coerceIn(0f, 1f)

private fun Long.format(): String = NumberFormat.getNumberInstance(Locale.KOREA).format(this)

internal fun marketActionDescriptionColor(
    itemName: String,
    rarity: String,
): Color = if (itemName.isNotBlank() && rarity.isNotBlank()) {
    rarityColor(rarity)
} else {
    AqText
}

internal const val MARKET_ACTION_ITEM_MAX_LINES = 2
internal val MARKET_ACTION_ITEM_OVERFLOW: TextOverflow = TextOverflow.Visible

internal const val MONSTER_HEADER_DENSE_THRESHOLD = 42
internal val MONSTER_HEADER_OVERFLOW: TextOverflow = TextOverflow.Visible

internal fun monsterHeaderHeightDp(nameLength: Int): Int =
    if (nameLength > MONSTER_HEADER_DENSE_THRESHOLD) 72 else 58

internal fun monsterHeaderFontSizeSp(nameLength: Int): Float =
    if (nameLength > MONSTER_HEADER_DENSE_THRESHOLD) 11f else 13f

internal fun monsterHeaderLineHeightSp(nameLength: Int): Float =
    if (nameLength > MONSTER_HEADER_DENSE_THRESHOLD) 14f else 16f

internal fun monsterHeaderMaxLines(nameLength: Int): Int =
    if (nameLength > MONSTER_HEADER_DENSE_THRESHOLD) 2 else 1

internal fun monsterHeaderProgressSpacingDp(nameLength: Int): Int =
    if (nameLength > MONSTER_HEADER_DENSE_THRESHOLD) 4 else 6

internal fun marketActionItemFontSizeSp(nameLength: Int): Float = when {
    nameLength > 34 -> 11f
    nameLength > 24 -> 12f
    else -> 14f
}

internal const val LOOT_RESULT_ITEM_MAX_LINES = 3
internal val LOOT_RESULT_ITEM_OVERFLOW: TextOverflow = TextOverflow.Visible
internal const val LOOT_RESULT_PANEL_HEIGHT_DP = 218

internal fun lootResultItemFontSizeSp(nameLength: Int): Float = when {
    nameLength > 38 -> 13f
    nameLength > 28 -> 15f
    nameLength > 18 -> 17f
    else -> 20f
}

internal fun lootResultContentVerticalPaddingDp(nameLength: Int): Float =
    if (nameLength > 18) 3f else 8f

internal fun lootResultStatusVerticalPaddingDp(nameLength: Int): Float =
    if (nameLength > 18) 4f else 7f

internal fun lootResultItemLineHeightSp(nameLength: Int): Float = when {
    nameLength > 28 -> 16f
    nameLength > 18 -> 17f
    else -> 18f
}

internal fun rarityColor(rarity: String): Color = when (rarity) {
    "신화" -> Color(0xFFFF6F91)
    "전설" -> Color(0xFFFFB25C)
    "영웅" -> Color(0xFFD897FF)
    "희귀" -> Color(0xFF75B9FF)
    "고급" -> Color(0xFF83D98C)
    else -> AqText
}

private fun monsterGradeColor(grade: String): Color = when (grade) {
    "관문 보스" -> AqGold
    "보스" -> Color(0xFFFF8A78)
    "정예" -> Color(0xFFD897FF)
    else -> AqMuted
}

private const val REWARDED_AD_TAG = "AlarmQuestRewarded"
private const val REWARDED_SESSION_UI_RECOVERY_POLL_MILLIS = 500L

internal data class DamageMotion(
    val alpha: Float,
    val translationY: Float,
    val scale: Float,
    val colorProgress: Float,
)

internal data class AttackLabelMotion(val alpha: Float)

internal const val ATTACK_IMPACT_DURATION_MILLIS = SKILL_PRESENTATION_DURATION_MILLIS
internal const val SKILL_LABEL_OFFSET_DP = -51

internal fun attackPresentationVisible(progress: Float, damage: Long): Boolean =
    damage > 0L && progress < 1f

internal fun attackLabelVisible(
    showAttackPresentation: Boolean,
    isSkill: Boolean,
    attackName: String,
): Boolean = showAttackPresentation && isSkill && attackName.isNotBlank()

internal fun attackLabelMotion(progress: Float): AttackLabelMotion {
    val current = progress.coerceIn(0f, 1f)
    val enter = (current / 0.07f).coerceIn(0f, 1f)
    val exit = ((current - 0.8f) / 0.18f).coerceIn(0f, 1f)
    return AttackLabelMotion(alpha = minOf(enter, 1f - exit))
}

internal fun backgroundShake(progress: Float, isSkill: Boolean): Float {
    val current = progress.coerceIn(0f, 1f)
    val end = if (isSkill) 0.14f else 0.1f
    if (current >= end) return 0f
    val amplitude = if (isSkill) 7f else 3.5f
    val phase = current / end
    return sin((phase * PI * 2f).toFloat()) * (1f - phase) * amplitude
}

internal fun damageMotion(progress: Float, isSkill: Boolean): DamageMotion {
    val current = progress.coerceIn(0f, 1f)
    val delayed = ((current - 0.1f) / 0.9f).coerceIn(0f, 1f)
    val enter = (delayed / 0.17f).coerceIn(0f, 1f)
    val settle = ((delayed - 0.17f) / 0.14f).coerceIn(0f, 1f)
    val exit = ((delayed - 0.72f) / 0.28f).coerceIn(0f, 1f)
    val overshoot = if (isSkill) 1.1f else 1.06f
    return DamageMotion(
        alpha = when {
            delayed < 0.17f -> enter
            delayed < 0.72f -> 1f
            else -> 1f - exit
        },
        translationY = if (delayed < 0.72f) {
            8f * (1f - enter)
        } else {
            -26f * exit
        },
        scale = when {
            delayed < 0.17f -> 0.88f + (overshoot - 0.88f) * enter
            delayed < 0.31f -> overshoot - (overshoot - 1f) * settle
            delayed < 0.72f -> 1f
            else -> 1f - 0.06f * exit
        },
        colorProgress = (delayed / 0.2f).coerceIn(0f, 1f),
    )
}
