package com.nullplaying.ui

import android.Manifest
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nullplaying.BuildConfig
import com.nullplaying.localization.AppLanguage
import com.nullplaying.localization.GameLanguageStore
import com.nullplaying.model.SimpleGameState
import com.nullplaying.notifications.GameNotificationPreferencesStore
import com.nullplaying.notifications.findActivity
import com.nullplaying.notifications.openAppNotificationSettings
import com.nullplaying.notifications.readNotificationAccessState
import com.nullplaying.notifications.resolveNotificationSettingsUiState

@Composable
internal fun GameSettingsScreen(
    state: SimpleGameState,
    notificationPreferencesStore: GameNotificationPreferencesStore,
    gameLanguageStore: GameLanguageStore,
    onBack: () -> Unit,
    onExitToRoster: () -> Unit,
    privacyOptionsRequired: Boolean = false,
    onOpenPrivacyOptions: () -> Unit = {},
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val preferences by notificationPreferencesStore.preferences.collectAsState()
    val language by gameLanguageStore.language.collectAsState()
    var notificationAccess by remember(context, preferences.permissionRequestAttempted) {
        mutableStateOf(
            readNotificationAccessState(
                context = context,
                permissionRequestAttempted = preferences.permissionRequestAttempted,
            ),
        )
    }
    val refreshNotificationAccess = {
        notificationAccess = readNotificationAccessState(
            context = context,
            permissionRequestAttempted =
                notificationPreferencesStore.preferences.value.permissionRequestAttempted,
        )
    }
    val notificationUiState = resolveNotificationSettingsUiState(
        preferenceEnabled = preferences.enabled,
        access = notificationAccess,
    )
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        refreshNotificationAccess()
    }
    val setNotificationsEnabled: (Boolean) -> Unit = { enabled ->
        if (!enabled) {
            notificationPreferencesStore.setEnabled(false)
        } else {
            notificationPreferencesStore.setEnabled(true)
            val currentAccess = readNotificationAccessState(
                context = context,
                permissionRequestAttempted = preferences.permissionRequestAttempted,
            )
            notificationAccess = currentAccess
            val currentUiState = resolveNotificationSettingsUiState(
                preferenceEnabled = true,
                access = currentAccess,
            )
            if (currentUiState.shouldRequestPermission) {
                notificationPreferencesStore.markPermissionRequestAttempted()
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    val activity = context.findActivity() as? ComponentActivity
    DisposableEffect(activity, preferences.permissionRequestAttempted) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshNotificationAccess()
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AqBackground)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(48.dp)) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.CenterStart).size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = localized("게임 화면으로 돌아가기"),
                    tint = AqText,
                )
            }
            Text(
                text = "설정",
                modifier = Modifier.align(Alignment.Center).semantics { heading() },
                color = AqText,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
            )
        }
        Spacer(Modifier.height(10.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Notifications,
                        contentDescription = null,
                        tint = AqGold,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.size(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "오프라인 모험 소진 알림",
                            color = AqText,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = when {
                                notificationUiState.checked ->
                                    "잔여 시간이 0이 되면 기기 알림"
                                notificationUiState.permissionPermanentlyDenied ->
                                    "시스템 설정에서 기기 알림 권한이 꺼져 있습니다."
                                notificationUiState.permissionDenied ->
                                    "기기 알림 권한이 필요합니다."
                                notificationUiState.appNotificationsDisabled ->
                                    "시스템 설정에서 앱 알림이 꺼져 있습니다."
                                else -> "소진 알림 꺼짐"
                            },
                            color = AqMuted,
                            fontSize = 12.sp,
                        )
                    }
                    NotificationSwitch(
                        checked = notificationUiState.checked,
                        onCheckedChange = setNotificationsEnabled,
                        contentDescription = localized("오프라인 모험 소진 알림"),
                    )
                }
                if (notificationUiState.showSystemSettingsAction) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            if (!openAppNotificationSettings(context)) {
                                Toast.makeText(
                                    context,
                                    localized("시스템 알림 설정을 열지 못했습니다."),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        border = BorderStroke(1.dp, AqGoldSoft),
                    ) {
                        Text(
                            text = "시스템 알림 설정 열기",
                            color = AqGold,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Language,
                        contentDescription = null,
                        tint = AqGold,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.size(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "언어",
                            color = AqText,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                LanguageSelector(
                    selectedLanguage = language,
                    onSelectLanguage = gameLanguageStore::setLanguage,
                )
            }
            Spacer(Modifier.height(10.dp))
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Person,
                        contentDescription = null,
                        tint = AqGold,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.size(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "모험가 관리",
                            color = AqText,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        UnlocalizedText(
                            text = localizedPreserving(
                                "${state.hero.name} · ${state.hero.heroClass.labelKo} · Lv.${state.hero.level}",
                                state.hero.name,
                            ),
                            color = AqMuted,
                            fontSize = 12.sp,
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                OutlinedButton(
                    onClick = onExitToRoster,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    border = BorderStroke(1.dp, AqGoldSoft),
                ) {
                    Text("모험가 선택 화면으로", color = AqGold, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(10.dp))
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                        tint = AqGold,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.size(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "게임 정보",
                            color = AqText,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "버전 ${BuildConfig.VERSION_NAME}",
                            color = AqMuted,
                            fontSize = 12.sp,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                GameInfoActionButton(
                    label = "개인정보처리방침",
                    onClick = { openPrivacyPolicy(context, language) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (privacyOptionsRequired) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onOpenPrivacyOptions,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        border = BorderStroke(1.dp, AqGoldSoft),
                    ) {
                        Text(
                            text = "광고 개인정보 선택",
                            color = AqGold,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = "설정 화면에서도 자동 모험은 계속 진행됩니다.",
                modifier = Modifier.fillMaxWidth(),
                color = AqMuted,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun GameInfoActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, AqGoldSoft),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Icon(
                imageVector = Icons.Filled.Policy,
                contentDescription = null,
                tint = AqGold,
                modifier = Modifier.align(Alignment.CenterStart).size(20.dp),
            )
            Text(
                text = label,
                modifier = Modifier.align(Alignment.Center),
                color = AqText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                tint = AqMuted,
                modifier = Modifier.align(Alignment.CenterEnd).size(16.dp),
            )
        }
    }
}

@Composable
private fun LanguageSelector(
    selectedLanguage: AppLanguage,
    onSelectLanguage: (AppLanguage) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        AppLanguage.entries.forEach { option ->
            val selected = option == selectedLanguage
            OutlinedButton(
                onClick = { onSelectLanguage(option) },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .semantics {
                        this.selected = selected
                        role = Role.RadioButton
                    },
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(
                    1.dp,
                    if (selected) AqGold else AqGoldSoft,
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (selected) AqGold.copy(alpha = 0.12f) else Color.Transparent,
                ),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = AqGold,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.size(2.dp))
                }
                UnlocalizedText(
                    text = option.selfName,
                    color = if (selected) AqGold else AqText,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.Black else FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun NotificationSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    contentDescription: String,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = Modifier.semantics { this.contentDescription = contentDescription },
        colors = SwitchDefaults.colors(
            checkedThumbColor = AqBackground,
            checkedTrackColor = AqGold,
            uncheckedThumbColor = AqMuted,
            uncheckedTrackColor = AqSurfaceHigh,
            disabledCheckedTrackColor = AqGold.copy(alpha = 0.35f),
            disabledUncheckedTrackColor = AqSurfaceHigh.copy(alpha = 0.55f),
        ),
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AqSurface),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.Center,
            content = content,
        )
    }
}
