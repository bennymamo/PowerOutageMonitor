package com.flossypickle.poweroutagemonitor.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flossypickle.poweroutagemonitor.storage.MonitorStore

@Composable
internal fun SettingsScreen(
    settings: MonitorStore.Settings,
    padding: PaddingValues,
    onMonitoringEnabledChange: (Boolean) -> Unit,
    onSettingsChange: (Long, Long, Boolean, String) -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenTestMode: () -> Unit
) {
    var deviceName by remember { mutableStateOf(settings.deviceName) }
    LaunchedEffect(settings.deviceName) { deviceName = settings.deviceName }
    val save: (Long, Long, Boolean, String) -> Unit = onSettingsChange

    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp).widthIn(max = 600.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text("Monitoring", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        SettingsCard {
            SettingSwitch(
                title = "Background monitoring",
                explanation = "Watch external power while the screen is off or the app is closed.",
                checked = settings.monitoringEnabled,
                onCheckedChange = onMonitoringEnabledChange
            )
            Text(
                if (settings.monitoringEnabled) "A small ongoing notification shows that monitoring is alive."
                else "Turn this on while the device is connected to its permanent charger.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }

        Text("Setup & testing", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary)
        SettingsCard {
            Text("Check that the monitor is ready and preview outage messages without changing real monitoring data.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) {
                Text("Open diagnostics")
            }
            OutlinedButton(onClick = onOpenTestMode, modifier = Modifier.fillMaxWidth()) {
                Text("Open test mode")
            }
        }

        Text("Device", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        SettingsCard {
            Text("Friendly device name", fontWeight = FontWeight.Medium)
            Text("This name will identify the monitor in future alerts.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            OutlinedTextField(
                value = deviceName,
                onValueChange = { if (it.length <= 50) deviceName = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Device name") }
            )
            Button(onClick = {
                save(settings.outageDelayMs, settings.restoreDelayMs,
                    settings.sendRestoreNotification, deviceName)
            }) { Text("Save name") }
        }

        Text("Outage timing", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary)
        SettingsCard {
            Text("Confirm power loss after", fontWeight = FontWeight.Medium)
            Text("Short interruptions that end before this delay are recorded without declaring an outage.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            DelayOptions(OUTAGE_DELAYS, settings.outageDelayMs) { value ->
                save(value, settings.restoreDelayMs, settings.sendRestoreNotification, settings.deviceName)
            }
        }

        Text("Restoration", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary)
        SettingsCard {
            Text("Confirm restored power after", fontWeight = FontWeight.Medium)
            Text("Wait for power to remain stable before closing an outage.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            DelayOptions(RESTORE_DELAYS, settings.restoreDelayMs) { value ->
                save(settings.outageDelayMs, value, settings.sendRestoreNotification, settings.deviceName)
            }
            SettingSwitch(
                title = "Send restoration alerts",
                explanation = "Notify configured alert channels when stable power returns.",
                checked = settings.sendRestoreNotification,
                onCheckedChange = { enabled ->
                    save(settings.outageDelayMs, settings.restoreDelayMs, enabled, settings.deviceName)
                }
            )
        }

        Text("Reliability", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary)
        SettingsCard {
            SettingText("Restart after reboot", "Enabled whenever background monitoring is on")
            SettingText("Before first unlock", if (Build.VERSION.SDK_INT >= 24) "Supported" else "Not available on this Android version")
            SettingText("Outage state", "Saved after every power observation")
            Text("Some manufacturers can still stop background apps. Device-specific guidance will be added before release.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }

        Text("Safety & privacy", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary)
        SettingsCard {
            Text("Do not leave an old, swollen, hot or damaged lithium battery charging unattended.",
                fontWeight = FontWeight.Medium)
            Text("The app has no analytics, advertising or trackers. Current monitoring stays on this device.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }

        Text("About", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        SettingsCard {
            val context = LocalContext.current
            SettingText("App version", appVersionName(context))
            SettingText("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            SettingText("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
            SettingText("Package", context.packageName)
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
    Card(shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    explanation: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(explanation, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun DelayOptions(options: List<Pair<Long, String>>, selected: Long, onSelect: (Long) -> Unit) {
    options.forEach { (value, label) ->
        Row(
            Modifier.fillMaxWidth().clickable { onSelect(value) }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = value == selected, onClick = { onSelect(value) })
            Text(label)
        }
    }
}

@Composable
private fun SettingText(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
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
