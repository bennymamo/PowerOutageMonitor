package com.flossypickle.poweroutagemonitor.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flossypickle.poweroutagemonitor.OutageEngine
import com.flossypickle.poweroutagemonitor.diagnostics.DiagnosticsCollector
import com.flossypickle.poweroutagemonitor.diagnostics.DiagnosticsReport
import com.flossypickle.poweroutagemonitor.guidance.DeviceGuidance
import com.flossypickle.poweroutagemonitor.monitoring.PowerSnapshot
import com.flossypickle.poweroutagemonitor.storage.MonitorStore

@Composable
internal fun DiagnosticsScreen(
    settings: MonitorStore.Settings,
    state: OutageEngine.State,
    snapshot: PowerSnapshot?,
    lastObservationEpochMs: Long,
    padding: PaddingValues,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val collector = remember(context) { DiagnosticsCollector(context) }
    var report by remember(settings, state, snapshot, lastObservationEpochMs) {
        mutableStateOf(collector.collect(settings, state, snapshot, lastObservationEpochMs))
    }
    val refresh = {
        report = collector.collect(settings, state, snapshot, lastObservationEpochMs)
    }
    val guidance = remember { DeviceGuidance.forManufacturer(Build.MANUFACTURER) }

    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp).widthIn(max = 600.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TextButton(onClick = onBack) { Text("‹ Settings") }
        Text("Diagnostics", style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold)
        Text("A local health report for this monitoring device. It contains no credentials.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (report.monitoringEnabled && !report.serviceRunning) {
            DiagnosticCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
                Text("Monitoring needs attention", fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer)
                Text("Monitoring is enabled, but the service is not running. Reopen Status or restart monitoring.",
                    color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }

        Text("Monitoring", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary)
        DiagnosticCard {
            DiagnosticRow("Enabled", yesNo(report.monitoringEnabled))
            DiagnosticRow("Service running", yesNo(report.serviceRunning))
            DiagnosticRow("Current state", report.phase.name.replace('_', ' ').lowercase()
                .replaceFirstChar(Char::titlecase))
            DiagnosticRow("External power", report.externalPower?.let(::yesNo) ?: "Unknown")
            DiagnosticRow("Battery", report.batteryPercent?.let { "$it%" } ?: "Unknown")
            DiagnosticRow("Last observation", report.lastObservation)
        }

        Text("Delivery readiness", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary)
        DiagnosticCard {
            DiagnosticRow("Internet", if (report.internetAvailable) "Available" else "Unavailable")
            DiagnosticRow("Notifications", if (report.notificationsAllowed) "Allowed" else "Blocked")
            DiagnosticRow("Power source", report.configuredPowerProviders)
            DiagnosticRow("Alert channels", report.configuredAlertProviders)
        }

        Text("Background reliability", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary)
        DiagnosticCard {
            DiagnosticRow("Boot startup", if (report.bootStartupConfigured) "Configured" else "Unavailable")
            DiagnosticRow("Battery optimization",
                if (report.batteryOptimizationExcluded) "Unrestricted" else "System managed")
            DiagnosticRow("Background restriction",
                if (report.backgroundRestricted) "Restricted" else "Not reported")
            OutlinedButton(
                onClick = { openBatteryOptimizationSettings(context) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Open battery optimization settings") }
        }

        Text(guidance.title, style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary)
        DiagnosticCard {
            Text(guidance.summary, color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp)
            guidance.steps.forEachIndexed { index, step ->
                Text("${index + 1}. $step", style = MaterialTheme.typography.bodyMedium)
            }
        }

        Text("Device", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary)
        DiagnosticCard {
            DiagnosticRow("App version", report.appVersion)
            DiagnosticRow("Android", report.androidVersion)
            DiagnosticRow("Device", report.device)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = refresh, modifier = Modifier.weight(1f)) { Text("Refresh") }
            OutlinedButton(
                onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText("Power monitor diagnostics", report.asPlainText()))
                    Toast.makeText(context, "Diagnostics copied", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f)
            ) { Text("Copy report") }
        }
    }
}

@Composable
private fun DiagnosticCard(
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)) {
        Column(Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp), content = content)
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
    }
}

private fun yesNo(value: Boolean) = if (value) "Yes" else "No"

private fun openBatteryOptimizationSettings(context: Context) {
    val primary = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val fallback = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(primary) }
        .recoverCatching { context.startActivity(fallback) }
}
