package com.flossypickle.poweroutagemonitor.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flossypickle.poweroutagemonitor.audible.AudibleAlarmStore

/** The complete local-alarm settings feature, isolated from the settings navigator. */
@Composable
internal fun AudibleAlarmSettingsContent(
    audibleSettings: AudibleAlarmStore.Settings,
    audibleAlarmActive: Boolean,
    exactAlarmAccessGranted: Boolean,
    onAudibleSettingsChange: (AudibleAlarmStore.Settings) -> Unit,
    onDismissAudibleAlarm: () -> Unit,
    onTestAudibleAlarm: () -> Unit
) {
    val context = LocalContext.current
    val soundPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pickedRingtoneUri(result.data)?.let { selected ->
                onAudibleSettingsChange(audibleSettings.copy(soundUri = selected.toString()))
            }
        }
    }

    SettingsCard {
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
        ) { Text("Play 5-second test") }
        Text("Sound", fontWeight = FontWeight.Medium)
        Text(
            "An active outage alarm shows a speaker notification from FP Grid Monitor. Expand it and tap Stop sound to silence this outage; monitoring and message alerts continue.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
        SettingText(
            "Selected",
            audibleSettings.soundUri?.let { ringtoneTitle(context, it) } ?: "Built-in beep"
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
                    onAudibleSettingsChange(audibleSettings.copy(repeatIntervalMs = value))
                }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = audibleSettings.repeatIntervalMs == value,
                    onClick = {
                        onAudibleSettingsChange(audibleSettings.copy(repeatIntervalMs = value))
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
                AudibleAlarmStore.ScheduleMode.BEST_EFFORT -> "Best effort (recommended)"
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
                        onAudibleSettingsChange(audibleSettings.copy(scheduleMode = mode))
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
                    onAudibleSettingsChange(audibleSettings.copy(stopBatteryPercent = value))
                }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = audibleSettings.stopBatteryPercent == value,
                    onClick = {
                        onAudibleSettingsChange(audibleSettings.copy(stopBatteryPercent = value))
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
}

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
