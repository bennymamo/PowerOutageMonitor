package com.flossypickle.poweroutagemonitor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flossypickle.poweroutagemonitor.integrations.alerts.telegram.TelegramConfigStore
import com.flossypickle.poweroutagemonitor.integrations.alerts.email.ResendEmailConfigStore
import com.flossypickle.poweroutagemonitor.integrations.alerts.email.GmailSmtpConfigStore
import com.flossypickle.poweroutagemonitor.integrations.alerts.sms.SmsConfigStore
import com.flossypickle.poweroutagemonitor.audible.AudibleAlarmStore
import com.flossypickle.poweroutagemonitor.storage.MonitorStore
import com.flossypickle.poweroutagemonitor.integrations.power.PowerSourceStore
import com.flossypickle.poweroutagemonitor.integrations.alerts.ScheduledAlertStore
import com.flossypickle.poweroutagemonitor.configuration.BackupCategory
import com.flossypickle.poweroutagemonitor.configuration.BackupDocument

internal enum class SettingsSection(val title: String) {
    HOME("Settings"),
    MONITORING("Monitoring"),
    MESSAGES("Alerts & sound"),
    PERSONAL("Appearance & device"),
    SUPPORT("Help & app"),
    SETUP("Setup & testing"),
    ALERTS("Alert channels"),
    AUDIBLE("Audible alarm"),
    BATTERY_ALERTS("Battery alerts"),
    SCHEDULED_UPDATES("Scheduled updates"),
    DEVICE("Device"),
    OUTAGE("Outage timing"),
    RESTORATION("Restoration"),
    APPEARANCE("Appearance"),
    HELP("Help & guidance"),
    RELIABILITY("Reliability"),
    HISTORY("History"),
    DATA_BACKUP("Data & recovery"),
    BACKUP_CREATE("Create backup"),
    BACKUP_AUTOMATIC("Automatic backups"),
    BACKUP_RESTORE("Restore backup"),
    SAFETY("Safety & privacy"),
    ABOUT("About")
}

@Composable
internal fun SettingsScreen(
    settings: MonitorStore.Settings,
    padding: PaddingValues,
    onSettingsChange: (Long, Long, Boolean, String) -> Unit,
    onHistoryLimitChange: (Int) -> Unit,
    onThemeModeChange: (MonitorStore.ThemeMode) -> Unit,
    onHelpLevelChange: (MonitorStore.HelpLevel) -> Unit,
    onBatteryLowAlertChange: (Boolean, Int) -> Unit,
    scheduledAlertSettings: ScheduledAlertStore.Settings,
    onScheduledAlertSettingsChange: (ScheduledAlertStore.Settings) -> Unit,
    audibleSettings: AudibleAlarmStore.Settings,
    onAudibleSettingsChange: (AudibleAlarmStore.Settings) -> Unit,
    audibleAlarmActive: Boolean,
    exactAlarmAccessGranted: Boolean,
    onDismissAudibleAlarm: () -> Unit,
    onTestAudibleAlarm: () -> Unit,
    selectedPowerSource: PowerSourceStore.Source,
    onClearHistory: () -> Unit,
    onBackupRestore: (BackupDocument, Set<BackupCategory>, Boolean) -> String?,
    onOpenPowerSources: () -> Unit,
    onOpenSetupChecklist: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenTestMode: () -> Unit,
    onOpenTelegram: () -> Unit,
    onOpenSms: () -> Unit,
    onOpenEmail: () -> Unit,
    requestedSection: SettingsSection? = null,
    onSectionOpened: () -> Unit = {}
) {
    var section by rememberSaveable { mutableStateOf(SettingsSection.HOME) }
    LaunchedEffect(requestedSection) {
        requestedSection?.let { section = it; onSectionOpened() }
    }

    BackHandler(enabled = section != SettingsSection.HOME) {
        section = parentSection(section)
    }
    val context = LocalContext.current
    val telegramConfig = TelegramConfigStore(context).config()
    val gmailConfig = GmailSmtpConfigStore(context).config()
    val resendConfig = ResendEmailConfigStore(context).config()
    val emailEnabled = gmailConfig.enabled || resendConfig.enabled
    val emailSaved = gmailConfig.hasAppPassword || resendConfig.hasApiKey
    val smsConfig = SmsConfigStore(context).config()
    SettingsPage(
        title = section.title,
        resetScrollKey = section.name,
        padding = padding,
        backTitle = parentSection(section).title,
        onBack = if (section == SettingsSection.HOME) null else {
            { section = parentSection(section) }
        }
    ) {
        when (section) {
            SettingsSection.HOME -> {
                SettingsCategoryCard("Power sources", "Charger and optional grid integrations") { onOpenPowerSources() }
                SettingsCategoryCard("Monitoring", "Timing, reminders and reliability") { section = SettingsSection.MONITORING }
                SettingsCategoryCard("Alerts & sound", "Alert destinations and the local alarm") { section = SettingsSection.MESSAGES }
                SettingsCategoryCard("Data & recovery", "History, encrypted backups and restore") { section = SettingsSection.DATA_BACKUP }
                SettingsCategoryCard("Appearance & device", "Theme, device name and setup guidance") { section = SettingsSection.PERSONAL }
                SettingsCategoryCard("Help & app", "Setup checklist, testing, safety and About") { section = SettingsSection.SUPPORT }
            }
            SettingsSection.MONITORING -> {
                SettingsCategoryCard(
                    "Outage timing",
                    "Confirm after ${formatCustomDelay(settings.outageDelayMs)}"
                ) { section = SettingsSection.OUTAGE }
                SettingsCategoryCard(
                    "Restoration",
                    "Confirm after ${formatCustomDelay(settings.restoreDelayMs)}"
                ) { section = SettingsSection.RESTORATION }
                SettingsCategoryCard(
                    "Battery alerts",
                    if (settings.batteryLowAlertEnabled) {
                        "Warn once per outage at ${settings.batteryLowAlertThreshold}%"
                    } else "Off"
                ) { section = SettingsSection.BATTERY_ALERTS }
                SettingsCategoryCard(
                    "Scheduled updates",
                    buildList {
                        if (scheduledAlertSettings.sourceUnavailableEnabled) add("source health")
                        if (scheduledAlertSettings.heartbeatEnabled) add("heartbeat")
                        if (scheduledAlertSettings.outageUpdatesEnabled) add("outage updates")
                    }.joinToString(" · ").ifEmpty { "Off" }
                ) { section = SettingsSection.SCHEDULED_UPDATES }
                SettingsCategoryCard("Reliability", "Boot startup and background guidance") {
                    section = SettingsSection.RELIABILITY
                }
            }
            SettingsSection.MESSAGES -> {
                SettingsCategoryCard(
                    "Alert channels",
                    when {
                        listOf(telegramConfig.enabled, emailEnabled, smsConfig.enabled).count { it } > 1 ->
                            "Multiple alert channels are enabled"
                        telegramConfig.enabled -> "Telegram is enabled"
                        emailEnabled -> "Email is enabled"
                        smsConfig.enabled -> "SMS is enabled"
                        telegramConfig.hasToken || emailSaved || smsConfig.recipients.isNotEmpty() ->
                            "Alert setup is saved but disabled"
                        else -> "No alert channel is configured"
                    }
                ) { section = SettingsSection.ALERTS }
                SettingsCategoryCard(
                    "Audible alarm",
                    if (audibleSettings.enabled) {
                        "On · repeats every ${formatCustomDelay(audibleSettings.repeatIntervalMs)}"
                    } else "Off"
                ) { section = SettingsSection.AUDIBLE }
            }
            SettingsSection.PERSONAL -> {
                SettingsCategoryCard("Device", "Name used in alerts") {
                    section = SettingsSection.DEVICE
                }
                SettingsCategoryCard(
                    "Appearance",
                    settings.themeMode.name.lowercase().replaceFirstChar(Char::titlecase)
                ) { section = SettingsSection.APPEARANCE }
                SettingsCategoryCard(
                    "Help & guidance",
                    if (settings.helpLevel == MonitorStore.HelpLevel.GUIDED) {
                        "Guided setup instructions"
                    } else "Concise setup instructions"
                ) { section = SettingsSection.HELP }
            }
            SettingsSection.SUPPORT -> {
                SettingsCategoryCard(
                    "Setup & testing",
                    "Diagnostics, power checks and safe alert simulations"
                ) { section = SettingsSection.SETUP }
                SettingsCategoryCard("Safety & privacy", "Battery care and data use") {
                    section = SettingsSection.SAFETY
                }
                SettingsCategoryCard("About", "Version, Android and package details") {
                    section = SettingsSection.ABOUT
                }
            }

            SettingsSection.SETUP -> SetupTestingSettingsContent(
                onOpenSetupChecklist = onOpenSetupChecklist,
                onOpenDiagnostics = onOpenDiagnostics,
                onOpenTestMode = onOpenTestMode
            )

            SettingsSection.ALERTS -> AlertChannelsSettingsContent(
                onOpenTelegram = onOpenTelegram,
                onOpenSms = onOpenSms,
                onOpenEmail = onOpenEmail
            )

            SettingsSection.AUDIBLE -> AudibleAlarmSettingsContent(
                audibleSettings = audibleSettings,
                audibleAlarmActive = audibleAlarmActive,
                exactAlarmAccessGranted = exactAlarmAccessGranted,
                onAudibleSettingsChange = onAudibleSettingsChange,
                onDismissAudibleAlarm = onDismissAudibleAlarm,
                onTestAudibleAlarm = onTestAudibleAlarm
            )
            SettingsSection.BATTERY_ALERTS -> BatteryAlertSettingsContent(
                settings = settings,
                onChange = onBatteryLowAlertChange
            )
            SettingsSection.SCHEDULED_UPDATES -> ScheduledUpdatesSettingsContent(
                settings = scheduledAlertSettings,
                onChange = onScheduledAlertSettingsChange
            )

            SettingsSection.DEVICE -> DeviceSettingsContent(
                settings = settings,
                onSave = onSettingsChange
            )

            SettingsSection.OUTAGE -> OutageTimingSettingsContent(
                settings = settings,
                onSave = onSettingsChange
            )

            SettingsSection.RESTORATION -> RestorationSettingsContent(
                settings = settings,
                onSave = onSettingsChange
            )

            SettingsSection.APPEARANCE -> AppearanceSettingsContent(
                settings = settings,
                onThemeModeChange = onThemeModeChange
            )

            SettingsSection.HELP -> HelpSettingsContent(
                settings = settings,
                onHelpLevelChange = onHelpLevelChange
            )

            SettingsSection.RELIABILITY -> ReliabilitySettingsContent(onOpenDiagnostics)

            SettingsSection.HISTORY -> HistorySettingsContent(
                settings = settings,
                onHistoryLimitChange = onHistoryLimitChange,
                onClearHistory = onClearHistory
            )

            SettingsSection.DATA_BACKUP -> {
                SettingsCategoryCard("History", "Retention and local data controls") { section = SettingsSection.HISTORY }
                SettingsCategoryCard(
                    "Create encrypted backup",
                    "Choose data and save a password-protected recovery file"
                ) { section = SettingsSection.BACKUP_CREATE }
                SettingsCategoryCard(
                    "Automatic backups",
                    "Schedule encrypted copies in a folder you choose"
                ) { section = SettingsSection.BACKUP_AUTOMATIC }
                SettingsCategoryCard(
                    "Restore backup",
                    "Unlock, inspect and selectively restore a recovery file"
                ) { section = SettingsSection.BACKUP_RESTORE }
            }
            SettingsSection.BACKUP_CREATE -> DataBackupSettingsContent(
                settings = settings,
                onRestore = onBackupRestore,
                panel = BackupPanel.CREATE
            )
            SettingsSection.BACKUP_AUTOMATIC -> DataBackupSettingsContent(
                settings = settings,
                onRestore = onBackupRestore,
                panel = BackupPanel.AUTOMATIC
            )
            SettingsSection.BACKUP_RESTORE -> DataBackupSettingsContent(
                settings = settings,
                onRestore = onBackupRestore,
                panel = BackupPanel.RESTORE
            )

            SettingsSection.SAFETY -> SafetyPrivacySettingsContent()

            SettingsSection.ABOUT -> AboutSettingsContent()
        }
    }
}

private fun parentSection(section: SettingsSection): SettingsSection = when (section) {
    SettingsSection.BACKUP_CREATE, SettingsSection.BACKUP_AUTOMATIC, SettingsSection.BACKUP_RESTORE,
    SettingsSection.HISTORY -> SettingsSection.DATA_BACKUP
    SettingsSection.OUTAGE, SettingsSection.RESTORATION, SettingsSection.BATTERY_ALERTS,
    SettingsSection.SCHEDULED_UPDATES, SettingsSection.RELIABILITY -> SettingsSection.MONITORING
    SettingsSection.ALERTS, SettingsSection.AUDIBLE -> SettingsSection.MESSAGES
    SettingsSection.DEVICE, SettingsSection.APPEARANCE, SettingsSection.HELP -> SettingsSection.PERSONAL
    SettingsSection.SETUP, SettingsSection.SAFETY, SettingsSection.ABOUT -> SettingsSection.SUPPORT
    else -> SettingsSection.HOME
}

@Composable
private fun SettingsPage(
    title: String,
    resetScrollKey: String,
    padding: PaddingValues,
    backTitle: String,
    onBack: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit
) {
    val scrollState = rememberScrollState()
    LaunchedEffect(resetScrollKey) { scrollState.scrollTo(0) }
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 600.dp).fillMaxWidth().verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (onBack != null) TextButton(onClick = onBack) { Text("‹ $backTitle") }
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold
            )
            content()
        }
    }
}
