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
import com.flossypickle.poweroutagemonitor.integrations.alerts.ScheduledAlertStore
import com.flossypickle.poweroutagemonitor.diagnostics.SystemHealthSnapshot
import com.flossypickle.poweroutagemonitor.audible.AudibleAlarmStore
import com.flossypickle.poweroutagemonitor.storage.OperationalHistoryStore
import com.flossypickle.poweroutagemonitor.integrations.power.PowerSourceStore

private enum class AppScreen(val label: String) {
    STATUS("Status"),
    HISTORY("History"),
    SETTINGS("Settings"),
    POWER_SOURCES("Power sources"),
    SETUP_CHECKLIST("Setup checklist"),
    DIAGNOSTICS("Diagnostics"),
    TEST_MODE("Test mode"),
    TELEGRAM("Telegram"),
    SMS("SMS"),
    EMAIL("Email"),
    GMAIL_EMAIL("Gmail"),
    RESEND_EMAIL("Resend"),
    ECOFLOW_LOCAL("EcoFlow local"),
    ECOFLOW_CLOUD("EcoFlow Cloud")
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
    hasEnabledAlertChannel: Boolean,
    hasSentTestAlert: Boolean,
    systemHealth: SystemHealthSnapshot,
    selectedPowerSource: PowerSourceStore.Source,
    powerSourceStatus: PowerSourceStore.Status?,
    scheduledAlertSettings: ScheduledAlertStore.Settings,
    scheduledAlertState: ScheduledAlertStore.State,
    deliverySummaries: Map<String, AlertDeliverySummary.Event>,
    onMonitoringEnabledChange: (Boolean) -> Unit,
    onSettingsChange: (Long, Long, Boolean, String) -> Unit,
    onCompleteSetup: (String, Long, Long, MonitorStore.HelpLevel) -> Unit,
    onRetryFailedDeliveries: () -> Unit,
    onClearDeliveryRecords: () -> Unit,
    onHistoryLimitChange: (Int) -> Unit,
    onThemeModeChange: (MonitorStore.ThemeMode) -> Unit,
    onHelpLevelChange: (MonitorStore.HelpLevel) -> Unit,
    onBatteryLowAlertChange: (Boolean, Int) -> Unit,
    onScheduledAlertSettingsChange: (ScheduledAlertStore.Settings) -> Unit,
    onAudibleSettingsChange: (AudibleAlarmStore.Settings) -> Unit,
    onDismissAudibleAlarm: () -> Unit,
    onTestAudibleAlarm: () -> Unit,
    onPowerSourceChanged: () -> Unit,
    onClearHistory: () -> Unit,
    onSendTestAlert: (AlertMessage) -> Boolean,
    onAlertConfigurationChanged: () -> Unit
) {
    if (!settings.setupCompleted) {
        SetupWizardScreen(settings = settings, snapshot = snapshot, onComplete = onCompleteSetup)
        return
    }
    var screen by rememberSaveable { mutableStateOf(AppScreen.STATUS) }
    var returnToChecklist by rememberSaveable { mutableStateOf(false) }
    val returnFromChecklistChild: () -> Unit = {
        if (returnToChecklist) {
            returnToChecklist = false
            screen = AppScreen.SETUP_CHECKLIST
        } else {
            screen = AppScreen.SETTINGS
        }
    }
    BackHandler(enabled = screen != AppScreen.STATUS) {
        when (screen) {
            AppScreen.SETUP_CHECKLIST -> {
                returnToChecklist = false
                screen = AppScreen.SETTINGS
            }
            AppScreen.DIAGNOSTICS, AppScreen.TEST_MODE, AppScreen.TELEGRAM,
            AppScreen.SMS, AppScreen.EMAIL -> returnFromChecklistChild()
            AppScreen.GMAIL_EMAIL, AppScreen.RESEND_EMAIL -> screen = AppScreen.EMAIL
            AppScreen.ECOFLOW_LOCAL, AppScreen.ECOFLOW_CLOUD -> screen = AppScreen.POWER_SOURCES
            AppScreen.POWER_SOURCES -> screen = AppScreen.SETTINGS
            AppScreen.HISTORY, AppScreen.SETTINGS -> screen = AppScreen.STATUS
            AppScreen.STATUS -> Unit
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
                                        AppScreen.SETUP_CHECKLIST,
                                        AppScreen.TEST_MODE,
                                        AppScreen.TELEGRAM,
                                        AppScreen.SMS,
                                        AppScreen.EMAIL,
                                        AppScreen.GMAIL_EMAIL,
                                        AppScreen.RESEND_EMAIL,
                                        AppScreen.POWER_SOURCES,
                                        AppScreen.ECOFLOW_LOCAL,
                                        AppScreen.ECOFLOW_CLOUD
                            )
                        TextButton(
                            onClick = {
                                returnToChecklist = false
                                screen = item
                            },
                            modifier = Modifier.weight(1f)
                        ) {
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
                alertChannels, systemHealth, audibleAlarmActive, selectedPowerSource,
                powerSourceStatus, scheduledAlertSettings, scheduledAlertState, padding,
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
                onHelpLevelChange,
                onBatteryLowAlertChange,
                scheduledAlertSettings,
                onScheduledAlertSettingsChange,
                audibleSettings,
                onAudibleSettingsChange,
                audibleAlarmActive,
                exactAlarmAccessGranted,
                onDismissAudibleAlarm,
                onTestAudibleAlarm,
                selectedPowerSource,
                onClearHistory,
                onOpenPowerSources = {
                    returnToChecklist = false
                    screen = AppScreen.POWER_SOURCES
                },
                onOpenSetupChecklist = {
                    returnToChecklist = false
                    screen = AppScreen.SETUP_CHECKLIST
                },
                onOpenDiagnostics = {
                    returnToChecklist = false
                    screen = AppScreen.DIAGNOSTICS
                },
                onOpenTestMode = {
                    returnToChecklist = false
                    screen = AppScreen.TEST_MODE
                },
                onOpenTelegram = {
                    returnToChecklist = false
                    screen = AppScreen.TELEGRAM
                },
                onOpenSms = {
                    returnToChecklist = false
                    screen = AppScreen.SMS
                },
                onOpenEmail = {
                    returnToChecklist = false
                    screen = AppScreen.EMAIL
                }
            )
            AppScreen.POWER_SOURCES -> PowerSourceSettingsScreen(
                selectedSource = selectedPowerSource,
                sourceStatus = powerSourceStatus,
                helpLevel = settings.helpLevel,
                padding = padding,
                onOpenEcoFlowLocal = {
                    returnToChecklist = false
                    screen = AppScreen.ECOFLOW_LOCAL
                },
                onOpenEcoFlowCloud = {
                    returnToChecklist = false
                    screen = AppScreen.ECOFLOW_CLOUD
                },
                onPowerSourceChanged = onPowerSourceChanged,
                onBack = { screen = AppScreen.SETTINGS }
            )
            AppScreen.SETUP_CHECKLIST -> SetupChecklistScreen(
                settings = settings,
                monitorState = monitorState,
                systemHealth = systemHealth,
                alertChannels = alertChannels,
                hasEnabledAlertChannel = hasEnabledAlertChannel,
                hasSentTestAlert = hasSentTestAlert,
                padding = padding,
                onOpenStatus = {
                    returnToChecklist = false
                    screen = AppScreen.STATUS
                },
                onOpenDiagnostics = {
                    returnToChecklist = true
                    screen = AppScreen.DIAGNOSTICS
                },
                onOpenTestMode = {
                    returnToChecklist = true
                    screen = AppScreen.TEST_MODE
                },
                onOpenEmail = {
                    returnToChecklist = true
                    screen = AppScreen.EMAIL
                },
                onOpenTelegram = {
                    returnToChecklist = true
                    screen = AppScreen.TELEGRAM
                },
                onOpenSms = {
                    returnToChecklist = true
                    screen = AppScreen.SMS
                },
                onBack = {
                    returnToChecklist = false
                    screen = AppScreen.SETTINGS
                }
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
                onBack = returnFromChecklistChild
            )
            AppScreen.TEST_MODE -> TestModeScreen(
                settings = settings,
                padding = padding,
                onSendTestAlert = onSendTestAlert,
                onBack = returnFromChecklistChild
            )
            AppScreen.TELEGRAM -> TelegramSetupScreen(
                deviceName = settings.deviceName,
                helpLevel = settings.helpLevel,
                padding = padding,
                onConfigurationChanged = onAlertConfigurationChanged,
                onBack = returnFromChecklistChild
            )
            AppScreen.SMS -> SmsSetupScreen(
                deviceName = settings.deviceName,
                helpLevel = settings.helpLevel,
                padding = padding,
                onConfigurationChanged = onAlertConfigurationChanged,
                onBack = returnFromChecklistChild
            )
            AppScreen.EMAIL -> EmailProvidersScreen(
                padding = padding,
                helpLevel = settings.helpLevel,
                onOpenGmail = { screen = AppScreen.GMAIL_EMAIL },
                onOpenResend = { screen = AppScreen.RESEND_EMAIL },
                onBack = returnFromChecklistChild
            )
            AppScreen.GMAIL_EMAIL -> GmailEmailSetupScreen(
                deviceName = settings.deviceName,
                helpLevel = settings.helpLevel,
                padding = padding,
                onConfigurationChanged = onAlertConfigurationChanged,
                onBack = { screen = AppScreen.EMAIL }
            )
            AppScreen.RESEND_EMAIL -> ResendEmailSetupScreen(
                deviceName = settings.deviceName,
                helpLevel = settings.helpLevel,
                padding = padding,
                onConfigurationChanged = onAlertConfigurationChanged,
                onBack = { screen = AppScreen.EMAIL }
            )
            AppScreen.ECOFLOW_LOCAL -> EcoFlowLocalSetupScreen(
                selectedSource = selectedPowerSource,
                sourceStatus = powerSourceStatus,
                helpLevel = settings.helpLevel,
                padding = padding,
                onPowerSourceChanged = onPowerSourceChanged,
                onBack = { screen = AppScreen.POWER_SOURCES }
            )
            AppScreen.ECOFLOW_CLOUD -> EcoFlowCloudSetupScreen(
                helpLevel = settings.helpLevel,
                padding = padding,
                onBack = { screen = AppScreen.POWER_SOURCES }
            )
        }
    }
}
