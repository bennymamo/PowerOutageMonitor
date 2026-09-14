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

private enum class SettingsSection(val title: String) {
    HOME("Settings"),
    SETUP("Setup & testing"),
    POWER_SOURCES("Power sources"),
    ALERTS("Alert channels"),
    AUDIBLE("Audible alarm"),
    BATTERY_ALERTS("Battery alerts"),
    DEVICE("Device"),
    OUTAGE("Outage timing"),
    RESTORATION("Restoration"),
    APPEARANCE("Appearance"),
    HELP("Help & guidance"),
    RELIABILITY("Reliability"),
    HISTORY("History"),
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
    audibleSettings: AudibleAlarmStore.Settings,
    onAudibleSettingsChange: (AudibleAlarmStore.Settings) -> Unit,
    audibleAlarmActive: Boolean,
    exactAlarmAccessGranted: Boolean,
    onDismissAudibleAlarm: () -> Unit,
    onTestAudibleAlarm: () -> Unit,
    selectedPowerSource: PowerSourceStore.Source,
    powerSourceStatus: PowerSourceStore.Status?,
    onPowerSourceChanged: () -> Unit,
    onClearHistory: () -> Unit,
    onOpenSetupChecklist: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenTestMode: () -> Unit,
    onOpenTelegram: () -> Unit,
    onOpenSms: () -> Unit,
    onOpenEmail: () -> Unit
) {
    var section by rememberSaveable { mutableStateOf(SettingsSection.HOME) }

    BackHandler(enabled = section != SettingsSection.HOME) {
        section = SettingsSection.HOME
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
        padding = padding,
        onBack = if (section == SettingsSection.HOME) null else {
            { section = SettingsSection.HOME }
        }
    ) {
        when (section) {
            SettingsSection.HOME -> {
                SettingsCategoryCard(
                    "Setup & testing",
                    "Diagnostics, power checks and safe alert simulations"
                ) { section = SettingsSection.SETUP }
                SettingsCategoryCard(
                    "Power sources",
                    if (selectedPowerSource == PowerSourceStore.Source.ECOFLOW_MODBUS) {
                        "EcoFlow PowerOcean is active"
                    } else "Android charger is active"
                ) { section = SettingsSection.POWER_SOURCES }
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
                SettingsCategoryCard(
                    "Battery alerts",
                    if (settings.batteryLowAlertEnabled) {
                        "Warn once per outage at ${settings.batteryLowAlertThreshold}%"
                    } else "Off"
                ) { section = SettingsSection.BATTERY_ALERTS }
                SettingsCategoryCard("Device", "Name used in alerts") {
                    section = SettingsSection.DEVICE
                }
                SettingsCategoryCard(
                    "Outage timing",
                    "Confirm after ${formatCustomDelay(settings.outageDelayMs)}"
                ) { section = SettingsSection.OUTAGE }
                SettingsCategoryCard(
                    "Restoration",
                    "Confirm after ${formatCustomDelay(settings.restoreDelayMs)}"
                ) { section = SettingsSection.RESTORATION }
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
                SettingsCategoryCard("Reliability", "Boot startup and background guidance") {
                    section = SettingsSection.RELIABILITY
                }
                SettingsCategoryCard("History", "Retention and local data controls") {
                    section = SettingsSection.HISTORY
                }
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

            SettingsSection.POWER_SOURCES -> PowerSourceSettingsContent(
                selectedSource = selectedPowerSource,
                sourceStatus = powerSourceStatus,
                helpLevel = settings.helpLevel,
                onPowerSourceChanged = onPowerSourceChanged
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

            SettingsSection.SAFETY -> SafetyPrivacySettingsContent()

            SettingsSection.ABOUT -> AboutSettingsContent()
        }
    }
}

@Composable
private fun SettingsPage(
    title: String,
    padding: PaddingValues,
    onBack: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 600.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (onBack != null) TextButton(onClick = onBack) { Text("‹ Settings") }
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold
            )
            content()
        }
    }
}
