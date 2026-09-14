package com.flossypickle.poweroutagemonitor.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

/** Appearance, guidance, reliability, local-data and app-information settings. */
@Composable
internal fun AppearanceSettingsContent(
    settings: MonitorStore.Settings,
    onThemeModeChange: (MonitorStore.ThemeMode) -> Unit
) {
    SettingsCard {
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
}

@Composable
internal fun HelpSettingsContent(
    settings: MonitorStore.Settings,
    onHelpLevelChange: (MonitorStore.HelpLevel) -> Unit
) {
    SettingsCard {
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
}

@Composable
internal fun ReliabilitySettingsContent(onOpenDiagnostics: () -> Unit) {
    SettingsCard {
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
}

@Composable
internal fun HistorySettingsContent(
    settings: MonitorStore.Settings,
    onHistoryLimitChange: (Int) -> Unit,
    onClearHistory: () -> Unit
) {
    var confirmClearHistory by remember { mutableStateOf(false) }

    SettingsCard {
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
}

@Composable
internal fun SafetyPrivacySettingsContent() {
    SettingsCard {
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
}

@Composable
internal fun AboutSettingsContent() {
    val context = LocalContext.current
    SettingsCard {
        SettingText("App version", appVersionName(context))
        SettingText("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        SettingText("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
        SettingText("Package", context.packageName)
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
