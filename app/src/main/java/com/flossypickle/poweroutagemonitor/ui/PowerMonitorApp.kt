package com.flossypickle.poweroutagemonitor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flossypickle.poweroutagemonitor.OutageEngine
import com.flossypickle.poweroutagemonitor.monitoring.PowerSnapshot
import com.flossypickle.poweroutagemonitor.storage.EventHistoryStore
import com.flossypickle.poweroutagemonitor.storage.MonitorStore
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertDeliverySummary
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertMessage
import com.flossypickle.poweroutagemonitor.diagnostics.SystemHealthSnapshot
import com.flossypickle.poweroutagemonitor.audible.AudibleAlarmStore
import com.flossypickle.poweroutagemonitor.storage.OperationalHistoryStore

private enum class AppScreen(val label: String) {
    STATUS("Status"),
    HISTORY("History"),
    SETTINGS("Settings"),
    DIAGNOSTICS("Diagnostics"),
    TEST_MODE("Test mode"),
    TELEGRAM("Telegram"),
    SMS("SMS"),
    EMAIL("Email"),
    GMAIL_EMAIL("Gmail"),
    RESEND_EMAIL("Resend")
}

private val primaryScreens = listOf(AppScreen.STATUS, AppScreen.HISTORY, AppScreen.SETTINGS)

@Composable
internal fun PowerMonitorApp(
    snapshot: PowerSnapshot?,
    monitorState: OutageEngine.State,
    settings: MonitorStore.Settings,
    history: List<EventHistoryStore.Record>,
    operationalHistory: List<OperationalHistoryStore.Record>,
    audibleSettings: AudibleAlarmStore.Settings,
    audibleAlarmActive: Boolean,
    exactAlarmAccessGranted: Boolean,
    lastObservationEpochMs: Long,
    deliveryWarning: String?,
    alertChannels: String,
    systemHealth: SystemHealthSnapshot,
    deliverySummaries: Map<String, AlertDeliverySummary.Event>,
    onMonitoringEnabledChange: (Boolean) -> Unit,
    onSettingsChange: (Long, Long, Boolean, String) -> Unit,
    onCompleteSetup: (String, Long, Long) -> Unit,
    onRetryFailedDeliveries: () -> Unit,
    onClearDeliveryRecords: () -> Unit,
    onHistoryLimitChange: (Int) -> Unit,
    onThemeModeChange: (MonitorStore.ThemeMode) -> Unit,
    onAudibleSettingsChange: (AudibleAlarmStore.Settings) -> Unit,
    onDismissAudibleAlarm: () -> Unit,
    onTestAudibleAlarm: () -> Unit,
    onClearHistory: () -> Unit,
    onSendTestAlert: (AlertMessage) -> Boolean,
    onAlertConfigurationChanged: () -> Unit
) {
    if (!settings.setupCompleted) {
        SetupWizardScreen(settings = settings, snapshot = snapshot, onComplete = onCompleteSetup)
        return
    }
    var screen by rememberSaveable { mutableStateOf(AppScreen.STATUS) }
    BackHandler(enabled = screen != AppScreen.STATUS) {
        screen = when (screen) {
            AppScreen.DIAGNOSTICS, AppScreen.TEST_MODE, AppScreen.TELEGRAM, AppScreen.SMS,
            AppScreen.EMAIL -> AppScreen.SETTINGS
            AppScreen.GMAIL_EMAIL, AppScreen.RESEND_EMAIL -> AppScreen.EMAIL
            AppScreen.HISTORY, AppScreen.SETTINGS -> AppScreen.STATUS
            AppScreen.STATUS -> AppScreen.STATUS
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().height(52.dp)) {
                    primaryScreens.forEach { item ->
                        val selected = screen == item ||
                            item == AppScreen.SETTINGS && screen in listOf(
                                        AppScreen.DIAGNOSTICS,
                                        AppScreen.TEST_MODE,
                                        AppScreen.TELEGRAM,
                                        AppScreen.SMS,
                                        AppScreen.EMAIL,
                                        AppScreen.GMAIL_EMAIL,
                                        AppScreen.RESEND_EMAIL
                            )
                        TextButton(onClick = { screen = item }, modifier = Modifier.weight(1f)) {
                            Text(
                                item.label,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        when (screen) {
            AppScreen.STATUS -> DashboardScreen(
                snapshot, monitorState, settings, history, lastObservationEpochMs, deliveryWarning,
                alertChannels, systemHealth, audibleAlarmActive, padding,
                onMonitoringEnabledChange, onDismissAudibleAlarm
            )
            AppScreen.HISTORY -> HistoryScreen(
                history, operationalHistory, monitorState, deliverySummaries, padding
            )
            AppScreen.SETTINGS -> SettingsScreen(
                settings,
                padding,
                onSettingsChange,
                onHistoryLimitChange,
                onThemeModeChange,
                audibleSettings,
                onAudibleSettingsChange,
                audibleAlarmActive,
                exactAlarmAccessGranted,
                onDismissAudibleAlarm,
                onTestAudibleAlarm,
                onClearHistory,
                onOpenDiagnostics = { screen = AppScreen.DIAGNOSTICS },
                onOpenTestMode = { screen = AppScreen.TEST_MODE },
                onOpenTelegram = { screen = AppScreen.TELEGRAM },
                onOpenSms = { screen = AppScreen.SMS },
                onOpenEmail = { screen = AppScreen.EMAIL }
            )
            AppScreen.DIAGNOSTICS -> DiagnosticsScreen(
                settings = settings,
                state = monitorState,
                snapshot = snapshot,
                lastObservationEpochMs = lastObservationEpochMs,
                deliverySummaries = deliverySummaries,
                padding = padding,
                onRetryFailedDeliveries = onRetryFailedDeliveries,
                onClearDeliveryRecords = onClearDeliveryRecords,
                onBack = { screen = AppScreen.SETTINGS }
            )
            AppScreen.TEST_MODE -> TestModeScreen(
                settings = settings,
                padding = padding,
                onSendTestAlert = onSendTestAlert,
                onBack = { screen = AppScreen.SETTINGS }
            )
            AppScreen.TELEGRAM -> TelegramSetupScreen(
                deviceName = settings.deviceName,
                padding = padding,
                onConfigurationChanged = onAlertConfigurationChanged,
                onBack = { screen = AppScreen.SETTINGS }
            )
            AppScreen.SMS -> SmsSetupScreen(
                deviceName = settings.deviceName,
                padding = padding,
                onConfigurationChanged = onAlertConfigurationChanged,
                onBack = { screen = AppScreen.SETTINGS }
            )
            AppScreen.EMAIL -> EmailProvidersScreen(
                padding = padding,
                onOpenGmail = { screen = AppScreen.GMAIL_EMAIL },
                onOpenResend = { screen = AppScreen.RESEND_EMAIL },
                onBack = { screen = AppScreen.SETTINGS }
            )
            AppScreen.GMAIL_EMAIL -> GmailEmailSetupScreen(
                deviceName = settings.deviceName,
                padding = padding,
                onConfigurationChanged = onAlertConfigurationChanged,
                onBack = { screen = AppScreen.EMAIL }
            )
            AppScreen.RESEND_EMAIL -> ResendEmailSetupScreen(
                deviceName = settings.deviceName,
                padding = padding,
                onConfigurationChanged = onAlertConfigurationChanged,
                onBack = { screen = AppScreen.EMAIL }
            )
        }
    }
}
