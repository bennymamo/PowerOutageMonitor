package com.flossypickle.poweroutagemonitor.ui

import androidx.activity.compose.BackHandler
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flossypickle.poweroutagemonitor.integrations.alerts.telegram.TelegramConfigStore
import com.flossypickle.poweroutagemonitor.integrations.alerts.email.ResendEmailConfigStore
import com.flossypickle.poweroutagemonitor.integrations.alerts.email.GmailSmtpConfigStore
import com.flossypickle.poweroutagemonitor.integrations.alerts.sms.SmsCapability
import com.flossypickle.poweroutagemonitor.integrations.alerts.sms.SmsConfigStore
import com.flossypickle.poweroutagemonitor.audible.AudibleAlarmStore
import com.flossypickle.poweroutagemonitor.storage.MonitorStore

private enum class SettingsSection(val title: String) {
    HOME("Settings"),
    SETUP("Setup & testing"),
    ALERTS("Alert channels"),
    AUDIBLE("Audible alarm"),
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
    audibleSettings: AudibleAlarmStore.Settings,
    onAudibleSettingsChange: (AudibleAlarmStore.Settings) -> Unit,
    audibleAlarmActive: Boolean,
    exactAlarmAccessGranted: Boolean,
    onDismissAudibleAlarm: () -> Unit,
    onTestAudibleAlarm: () -> Unit,
    onClearHistory: () -> Unit,
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
    var deviceName by remember { mutableStateOf(settings.deviceName) }
    LaunchedEffect(settings.deviceName) { deviceName = settings.deviceName }
    val save: (Long, Long, Boolean, String) -> Unit = onSettingsChange
    val context = LocalContext.current
    val telegramConfig = TelegramConfigStore(context).config()
    val gmailConfig = GmailSmtpConfigStore(context).config()
    val resendConfig = ResendEmailConfigStore(context).config()
    val emailEnabled = gmailConfig.enabled || resendConfig.enabled
    val emailSaved = gmailConfig.hasAppPassword || resendConfig.hasApiKey
    val smsConfig = SmsConfigStore(context).config()
    val smsCapability = SmsCapability.capture(context)
    var confirmClearHistory by remember { mutableStateOf(false) }
    val soundPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pickedRingtoneUri(result.data)?.let { selected ->
                onAudibleSettingsChange(
                    audibleSettings.copy(soundUri = selected.toString())
                )
            }
        }
    }

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

            SettingsSection.SETUP -> SettingsCard {
                Text(
                    "Check that the monitor is ready and preview outage messages without changing real monitoring data.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) {
                    Text("Open diagnostics")
                }
                OutlinedButton(onClick = onOpenTestMode, modifier = Modifier.fillMaxWidth()) {
                    Text("Open test mode")
                }
            }

            SettingsSection.ALERTS -> SettingsCard {
                SettingText("Telegram", when {
                    telegramConfig.enabled -> "Enabled"
                    telegramConfig.hasToken -> "Saved, disabled"
                    else -> "Not configured"
                })
                Text(
                    "Send outage and restoration messages through a bot you control. Multiple chats are supported.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                OutlinedButton(onClick = onOpenTelegram, modifier = Modifier.fillMaxWidth()) {
                    Text("Configure Telegram")
                }
                SettingText("Device SMS", when {
                    !smsCapability.supported -> "Unavailable on this device"
                    smsConfig.enabled -> "Enabled"
                    smsConfig.recipients.isNotEmpty() -> "Saved, disabled"
                    else -> "Not configured"
                })
                Text(
                    "Send through the phone's SIM when internet service is unavailable. Carrier charges may apply.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                OutlinedButton(onClick = onOpenSms, modifier = Modifier.fillMaxWidth()) {
                    Text("Configure device SMS")
                }
                SettingText("Email", when {
                    gmailConfig.enabled && resendConfig.enabled -> "Gmail and Resend enabled"
                    gmailConfig.enabled -> "Gmail enabled"
                    resendConfig.enabled -> "Resend enabled"
                    emailSaved -> "Saved, disabled"
                    else -> "Not configured"
                })
                Text(
                    "Gmail is the easiest option and needs no domain. Resend remains available for users with a verified domain.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                OutlinedButton(onClick = onOpenEmail, modifier = Modifier.fillMaxWidth()) {
                    Text("Configure email")
                }
            }

            SettingsSection.AUDIBLE -> SettingsCard {
                SettingSwitch(
                    title = "Enable audible outage alarm",
                    explanation = "Sound a repeating local alarm only after a grid outage is confirmed.",
                    checked = audibleSettings.enabled,
                    onCheckedChange = {
                        onAudibleSettingsChange(audibleSettings.copy(enabled = it))
                    }
                )
                if (audibleAlarmActive) {
                    OutlinedButton(
                        onClick = onDismissAudibleAlarm,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Dismiss current outage alarm") }
                }
                OutlinedButton(
                    onClick = onTestAudibleAlarm,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Play one test sound") }
                Text("Sound", fontWeight = FontWeight.Medium)
                SettingText(
                    "Selected",
                    audibleSettings.soundUri?.let { ringtoneTitle(context, it) }
                        ?: "Built-in beep"
                )
                OutlinedButton(
                    onClick = {
                        soundPicker.launch(
                            Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                                .putExtra(
                                    RingtoneManager.EXTRA_RINGTONE_TYPE,
                                    RingtoneManager.TYPE_ALARM
                                )
                                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                                .putExtra(
                                    RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                                    audibleSettings.soundUri?.let(Uri::parse)
                                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                                )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Choose Android alarm sound") }
                if (audibleSettings.soundUri != null) {
                    TextButton(
                        onClick = {
                            onAudibleSettingsChange(audibleSettings.copy(soundUri = null))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Use built-in beep") }
                }
                Text(
                    "If the selected sound cannot be opened, FP Grid Monitor uses its built-in beep.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                Text("Repeat interval", fontWeight = FontWeight.Medium)
                AUDIBLE_REPEAT_INTERVALS.forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            onAudibleSettingsChange(
                                audibleSettings.copy(repeatIntervalMs = value)
                            )
                        }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = audibleSettings.repeatIntervalMs == value,
                            onClick = {
                                onAudibleSettingsChange(
                                    audibleSettings.copy(repeatIntervalMs = value)
                                )
                            }
                        )
                        Text(label)
                    }
                }
                Text("Repeat timing", fontWeight = FontWeight.Medium)
                Text(
                    "Best effort saves battery but Android may delay a repeat while the phone is deeply idle. Exact asks Android to keep the selected timing.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                AudibleAlarmStore.ScheduleMode.entries.forEach { mode ->
                    val label = when (mode) {
                        AudibleAlarmStore.ScheduleMode.BEST_EFFORT ->
                            "Best effort (recommended)"
                        AudibleAlarmStore.ScheduleMode.EXACT -> "Exact"
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            onAudibleSettingsChange(audibleSettings.copy(scheduleMode = mode))
                        }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = audibleSettings.scheduleMode == mode,
                            onClick = {
                                onAudibleSettingsChange(
                                    audibleSettings.copy(scheduleMode = mode)
                                )
                            }
                        )
                        Text(label)
                    }
                }
                if (audibleSettings.scheduleMode == AudibleAlarmStore.ScheduleMode.EXACT &&
                    !exactAlarmAccessGranted
                ) {
                    Text(
                        "Android has not allowed exact alarms yet. Repeats will use best effort until you allow Alarms & reminders.",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp
                    )
                    OutlinedButton(
                        onClick = { openExactAlarmSettings(context) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Allow exact alarms") }
                }
                Text("Protect the device battery", fontWeight = FontWeight.Medium)
                Text(
                    "Stop sounding for the current outage when the battery reaches this level.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                AUDIBLE_BATTERY_LIMITS.forEach { value ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            onAudibleSettingsChange(
                                audibleSettings.copy(stopBatteryPercent = value)
                            )
                        }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = audibleSettings.stopBatteryPercent == value,
                            onClick = {
                                onAudibleSettingsChange(
                                    audibleSettings.copy(stopBatteryPercent = value)
                                )
                            }
                        )
                        Text("Stop at $value%")
                    }
                }
                SettingSwitch(
                    title = "Use maximum alarm volume",
                    explanation = "Temporarily raises alarm volume for each beep, then restores it. Do Not Disturb can still silence it.",
                    checked = audibleSettings.useMaximumVolume,
                    onCheckedChange = {
                        onAudibleSettingsChange(audibleSettings.copy(useMaximumVolume = it))
                    }
                )
                Text(
                    "The alarm stops when power returns, monitoring is disabled, the battery limit is reached, or you dismiss it.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }

            SettingsSection.DEVICE -> SettingsCard {
                Text("Friendly device name", fontWeight = FontWeight.Medium)
                Text(
                    "This name identifies the monitor in alerts.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                OutlinedTextField(
                    value = deviceName,
                    onValueChange = { if (it.length <= 50) deviceName = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Device name") }
                )
                Button(onClick = {
                    save(
                        settings.outageDelayMs,
                        settings.restoreDelayMs,
                        settings.sendRestoreNotification,
                        deviceName
                    )
                }) { Text("Save name") }
            }

            SettingsSection.OUTAGE -> SettingsCard {
                Text("Confirm grid outage after", fontWeight = FontWeight.Medium)
                Text(
                    "Short power interruptions that end before this delay are logged without declaring an outage.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                DelayOptions(OUTAGE_DELAYS, settings.outageDelayMs) { value ->
                    save(
                        value,
                        settings.restoreDelayMs,
                        settings.sendRestoreNotification,
                        settings.deviceName
                    )
                }
            }

            SettingsSection.RESTORATION -> SettingsCard {
                Text("Confirm grid restoration after", fontWeight = FontWeight.Medium)
                Text(
                    "Wait for power to remain stable before closing an outage.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                DelayOptions(RESTORE_DELAYS, settings.restoreDelayMs) { value ->
                    save(
                        settings.outageDelayMs,
                        value,
                        settings.sendRestoreNotification,
                        settings.deviceName
                    )
                }
                SettingSwitch(
                    title = "Send restoration alerts",
                    explanation = "Notify configured alert channels when stable power returns.",
                    checked = settings.sendRestoreNotification,
                    onCheckedChange = { enabled ->
                        save(
                            settings.outageDelayMs,
                            settings.restoreDelayMs,
                            enabled,
                            settings.deviceName
                        )
                    }
                )
            }

            SettingsSection.APPEARANCE -> SettingsCard {
                Text("Theme", fontWeight = FontWeight.Medium)
                Text(
                    "System follows the phone's light or dark appearance.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                THEME_OPTIONS.forEach { (mode, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onThemeModeChange(mode) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = settings.themeMode == mode,
                            onClick = { onThemeModeChange(mode) }
                        )
                        Text(label)
                    }
                }
            }

            SettingsSection.HELP -> SettingsCard {
                Text("Setup instructions", fontWeight = FontWeight.Medium)
                Text(
                    "This changes how much help setup pages show. It does not change monitoring or alert behavior.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                HELP_LEVEL_OPTIONS.forEach { (level, label, explanation) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onHelpLevelChange(level) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        RadioButton(
                            selected = settings.helpLevel == level,
                            onClick = { onHelpLevelChange(level) }
                        )
                        Column(Modifier.weight(1f).padding(top = 12.dp)) {
                            Text(label, fontWeight = FontWeight.Medium)
                            Text(
                                explanation,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            SettingsSection.RELIABILITY -> SettingsCard {
                SettingText("Restart after reboot", "Enabled with monitoring")
                SettingText(
                    "Before first unlock",
                    if (Build.VERSION.SDK_INT >= 24) "Supported" else "Unavailable"
                )
                SettingText("Outage state", "Saved after every reading")
                Text(
                    "Some manufacturers can still stop background apps. Diagnostics shows current health and the system settings to check.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) {
                    Text("Open reliability diagnostics")
                }
            }

            SettingsSection.HISTORY -> SettingsCard {
                Text("Keep recent power events", fontWeight = FontWeight.Medium)
                Text(
                    "Older entries are removed automatically. Alert delivery records are managed separately in Diagnostics.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                HISTORY_LIMITS.forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onHistoryLimitChange(value) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = settings.historyLimit == value,
                            onClick = { onHistoryLimitChange(value) }
                        )
                        Text(label)
                    }
                }
                if (!confirmClearHistory) {
                    OutlinedButton(
                        onClick = { confirmClearHistory = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Clear power history") }
                } else {
                    Text(
                        "This permanently removes local grid and app-operation history.",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            onClearHistory()
                            confirmClearHistory = false
                        }) { Text("Clear") }
                        OutlinedButton(onClick = { confirmClearHistory = false }) {
                            Text("Cancel")
                        }
                    }
                }
            }

            SettingsSection.SAFETY -> SettingsCard {
                Text(
                    "Do not leave an old, swollen, hot or damaged lithium battery charging unattended.",
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "The app has no analytics, advertising or trackers. Current monitoring stays on this device.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }

            SettingsSection.ABOUT -> SettingsCard {
                val context = LocalContext.current
                SettingText("App version", appVersionName(context))
                SettingText("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                SettingText("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
                SettingText("Package", context.packageName)
            }
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

@Composable
private fun SettingsCategoryCard(title: String, summary: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(summary, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            Text("›", color = MaterialTheme.colorScheme.primary, fontSize = 26.sp)
        }
    }
}

@Suppress("DEPRECATION")
private fun appVersionName(context: Context): String = try {
    val info = if (Build.VERSION.SDK_INT >= 33) {
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(0)
        )
    } else {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    info.versionName ?: "Unknown"
} catch (_: PackageManager.NameNotFoundException) {
    "Unknown"
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    explanation: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(explanation, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun DelayOptions(
    options: List<Pair<Long, String>>,
    selected: Long,
    onSelect: (Long) -> Unit
) {
    val isPreset = options.any { it.first == selected }
    var customSeconds by remember(selected) {
        mutableStateOf(if (isPreset) "" else (selected / 1_000L).toString())
    }
    options.forEach { (value, label) ->
        Row(
            Modifier.fillMaxWidth().clickable { onSelect(value) }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = value == selected, onClick = { onSelect(value) })
            Text(label)
        }
    }
    if (!isPreset) {
        Text(
            "Current custom delay: ${formatCustomDelay(selected)}",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
    OutlinedTextField(
        value = customSeconds,
        onValueChange = { value ->
            if (value.length <= 5 && value.all(Char::isDigit)) customSeconds = value
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Custom delay in seconds") },
        supportingText = { Text("0 to 86,400 seconds") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true
    )
    val parsedSeconds = customSeconds.toLongOrNull()
    OutlinedButton(
        onClick = {
            parsedSeconds
                ?.takeIf { it in 0L..86_400L }
                ?.let { onSelect(it * 1_000L) }
        },
        enabled = parsedSeconds != null && parsedSeconds in 0L..86_400L,
        modifier = Modifier.fillMaxWidth()
    ) { Text("Save custom delay") }
}

private fun formatCustomDelay(milliseconds: Long): String {
    val seconds = milliseconds / 1_000L
    val hours = seconds / 3_600L
    val minutes = seconds % 3_600L / 60L
    val remainingSeconds = seconds % 60L
    return buildList {
        if (hours > 0) add("${hours}h")
        if (minutes > 0) add("${minutes}m")
        if (remainingSeconds > 0 || isEmpty()) add("${remainingSeconds}s")
    }.joinToString(" ")
}

@Composable
private fun SettingText(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
    }
}

private val OUTAGE_DELAYS = listOf(
    0L to "Immediately",
    10_000L to "10 seconds",
    30_000L to "30 seconds",
    60_000L to "1 minute",
    120_000L to "2 minutes",
    300_000L to "5 minutes",
    600_000L to "10 minutes"
)

private val RESTORE_DELAYS = listOf(
    0L to "Immediately",
    10_000L to "10 seconds",
    30_000L to "30 seconds",
    60_000L to "1 minute",
    120_000L to "2 minutes"
)

private val HISTORY_LIMITS = listOf(
    50 to "Last 50 events",
    100 to "Last 100 events",
    200 to "Last 200 events (recommended)"
)

private val THEME_OPTIONS = listOf(
    MonitorStore.ThemeMode.SYSTEM to "System default",
    MonitorStore.ThemeMode.DARK to "Dark",
    MonitorStore.ThemeMode.LIGHT to "Light"
)

private val HELP_LEVEL_OPTIONS = listOf(
    Triple(
        MonitorStore.HelpLevel.GUIDED,
        "Guided (recommended)",
        "Show numbered walkthroughs, plain explanations and direct setup links."
    ),
    Triple(
        MonitorStore.HelpLevel.EXPERIENCED,
        "Experienced",
        "Show concise technical notes and fewer setup hints."
    )
)

private val AUDIBLE_REPEAT_INTERVALS = listOf(
    60_000L to "Every minute",
    5 * 60_000L to "Every 5 minutes (recommended)",
    15 * 60_000L to "Every 15 minutes",
    30 * 60_000L to "Every 30 minutes",
    60 * 60_000L to "Every hour"
)

private val AUDIBLE_BATTERY_LIMITS = listOf(10, 20, 30, 40)

private fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val primary = Intent(
        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
        Uri.parse("package:${context.packageName}")
    )
    val fallback = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}")
    )
    runCatching { context.startActivity(primary) }
        .recoverCatching { context.startActivity(fallback) }
}

@Suppress("DEPRECATION")
private fun pickedRingtoneUri(intent: Intent?): Uri? = if (Build.VERSION.SDK_INT >= 33) {
    intent?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
} else {
    intent?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
}

private fun ringtoneTitle(context: Context, uri: String): String = runCatching {
    RingtoneManager.getRingtone(context, Uri.parse(uri))?.getTitle(context)
}.getOrNull()?.takeIf(String::isNotBlank) ?: "Android alarm sound"
