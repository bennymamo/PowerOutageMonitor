package com.flossypickle.poweroutagemonitor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flossypickle.poweroutagemonitor.storage.MonitorStore

/** Settings that directly affect detection and the monitoring device. */
@Composable
internal fun BatteryAlertSettingsContent(
    settings: MonitorStore.Settings,
    onChange: (Boolean, Int) -> Unit
) {
    SettingsCard {
        SettingSwitch(
            title = "Low battery during an outage",
            explanation = "Send one extra alert if this device's battery falls to the selected level while a confirmed grid outage is still active.",
            checked = settings.batteryLowAlertEnabled,
            onCheckedChange = { onChange(it, settings.batteryLowAlertThreshold) }
        )
        Text(
            "This warning uses every enabled alert channel and is sent at most once for each outage, even after a reboot.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
        if (settings.batteryLowAlertEnabled) {
            Text("Warn at", fontWeight = FontWeight.Medium)
            BATTERY_LOW_ALERT_THRESHOLDS.forEach { value ->
                Row(
                    Modifier.fillMaxWidth().clickable { onChange(true, value) }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = settings.batteryLowAlertThreshold == value,
                        onClick = { onChange(true, value) }
                    )
                    Text(if (value == 20) "$value% (recommended)" else "$value%")
                }
            }
        }
    }
}

@Composable
internal fun DeviceSettingsContent(
    settings: MonitorStore.Settings,
    onSave: (Long, Long, Boolean, String) -> Unit
) {
    var deviceName by remember { mutableStateOf(settings.deviceName) }
    LaunchedEffect(settings.deviceName) { deviceName = settings.deviceName }

    SettingsCard {
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
            onSave(
                settings.outageDelayMs,
                settings.restoreDelayMs,
                settings.sendRestoreNotification,
                deviceName
            )
        }) { Text("Save name") }
    }
}

@Composable
internal fun OutageTimingSettingsContent(
    settings: MonitorStore.Settings,
    onSave: (Long, Long, Boolean, String) -> Unit
) {
    SettingsCard {
        Text("Confirm grid outage after", fontWeight = FontWeight.Medium)
        Text(
            "Short power interruptions that end before this delay are logged without declaring an outage.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
        DelayOptions(OUTAGE_DELAYS, settings.outageDelayMs) { value ->
            onSave(
                value,
                settings.restoreDelayMs,
                settings.sendRestoreNotification,
                settings.deviceName
            )
        }
    }
}

@Composable
internal fun RestorationSettingsContent(
    settings: MonitorStore.Settings,
    onSave: (Long, Long, Boolean, String) -> Unit
) {
    SettingsCard {
        Text("Confirm grid restoration after", fontWeight = FontWeight.Medium)
        Text(
            "Wait for power to remain stable before closing an outage.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
        DelayOptions(RESTORE_DELAYS, settings.restoreDelayMs) { value ->
            onSave(
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
                onSave(
                    settings.outageDelayMs,
                    settings.restoreDelayMs,
                    enabled,
                    settings.deviceName
                )
            }
        )
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

private val BATTERY_LOW_ALERT_THRESHOLDS = listOf(10, 15, 20, 25, 30)
