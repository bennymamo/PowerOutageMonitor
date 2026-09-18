package com.flossypickle.poweroutagemonitor.ui

import androidx.compose.foundation.BorderStroke
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.OutlinedCard
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
import com.flossypickle.poweroutagemonitor.diagnostics.formatMinutes
import com.flossypickle.poweroutagemonitor.guidance.DeviceGuidance
import com.flossypickle.poweroutagemonitor.monitoring.PowerSnapshot
import com.flossypickle.poweroutagemonitor.storage.MonitorStore
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertDeliverySummary

@Composable
internal fun DiagnosticsScreen(
    settings: MonitorStore.Settings,
    state: OutageEngine.State,
    snapshot: PowerSnapshot?,
    lastObservationEpochMs: Long,
    deliverySummaries: Map<String, AlertDeliverySummary.Event>,
    padding: PaddingValues,
    onRetryFailedDeliveries: () -> Unit,
    onClearDeliveryRecords: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val collector = remember(context) { DiagnosticsCollector(context) }
    var report by remember(settings, state, snapshot, lastObservationEpochMs, deliverySummaries) {
        mutableStateOf(collector.collect(settings, state, snapshot, lastObservationEpochMs))
    }
    var confirmClearDeliveries by remember { mutableStateOf(false) }
    val refresh = {
        report = collector.collect(settings, state, snapshot, lastObservationEpochMs)
    }
    val guidance = remember { DeviceGuidance.forManufacturer(Build.MANUFACTURER) }
    val exportReport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri, "w")?.bufferedWriter()?.use {
                    it.write(report.asPlainText())
                } ?: error("The selected file could not be opened.")
            }.fold(
                onSuccess = {
                    Toast.makeText(context, "Diagnostics exported", Toast.LENGTH_SHORT).show()
                },
                onFailure = {
                    Toast.makeText(context, "Diagnostics could not be exported", Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp).widthIn(max = 600.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TextButton(onClick = onBack) { Text(if (LocalDashboardReturn.current != null) "‹ Status" else "‹ Settings") }
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
        if (report.monitoringEnabled && report.backgroundRestricted) {
            DiagnosticCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
                Text("Background activity is restricted", fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer)
                Text("Android can block monitoring after this screen closes. Change this app's battery setting from Restricted.",
                    color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        if (report.monitoringEnabled && !report.notificationsAllowed) {
            DiagnosticCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
                Text("Monitoring notification is blocked", fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer)
                Text("Android may still run the service, but its ongoing notification and local alerts are hidden.",
                    color = MaterialTheme.colorScheme.onErrorContainer)
                OutlinedButton(
                    onClick = { openNotificationSettings(context) },
                    modifier = Modifier
                ) { Text("Open notification settings") }
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
            DiagnosticRow("Battery temperature", report.batteryTemperature)
            DiagnosticRow("Last observation", report.lastObservation)
        }

        ExpandableSettingsSection("Delivery readiness", "Channels, connection and delivery results") {
            DiagnosticRow("Internet", if (report.internetAvailable) "Available" else "Unavailable")
            DiagnosticRow("Notifications", if (report.notificationsAllowed) "Allowed" else "Blocked")
            DiagnosticRow("Power source", report.configuredPowerProviders)
            DiagnosticRow("Alert channels", report.configuredAlertProviders)
            DiagnosticRow("Low battery warning", report.batteryLowAlert)
            DiagnosticRow("Queued", report.queuedDeliveries.toString())
            DiagnosticRow("Waiting to retry", report.retryingDeliveries.toString())
            DiagnosticRow("Sent", report.sentDeliveries.toString())
            DiagnosticRow("Failed", report.failedDeliveries.toString())
            report.lastDeliveryError?.let { error ->
                Text("Last delivery error: $error", color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp)
            }
            if (report.failedDeliveries > 0) {
                Button(
                    onClick = {
                        onRetryFailedDeliveries()
                        refresh()
                    },
                    modifier = Modifier
                ) { Text("Retry failed alerts") }
            }
            if (report.sentDeliveries + report.failedDeliveries > 0) {
                if (!confirmClearDeliveries) {
                    TextButton(onClick = { confirmClearDeliveries = true }) {
                        Text("Clear delivery records")
                    }
                } else {
                    Text("This removes sent and failed delivery details. Outage history is kept.",
                        color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompactActions {
                            Button(onClick = {
                                onClearDeliveryRecords()
                                confirmClearDeliveries = false
                                refresh()
                            }) { Text("Clear") }
                            TextButton(onClick = { confirmClearDeliveries = false }) { Text("Cancel") }
                        }
                    }
                }
            }
        }

        ExpandableSettingsSection("Background reliability", "Battery settings, boot startup and interruptions") {
            DiagnosticRow("Boot startup", if (report.bootStartupConfigured) "Configured" else "Unavailable")
            DiagnosticRow("Battery optimization",
                if (report.batteryOptimizationExcluded) "Unrestricted" else "System managed")
            DiagnosticRow("Background restriction",
                if (report.backgroundRestricted) "Restricted" else "Not reported")
            DiagnosticRow("Unrecorded interruptions", report.operationalInterruptions.toString())
            report.lastOperationalInterruption?.let {
                DiagnosticRow("Latest interruption", it)
            }
            CompactActions {
                OutlinedButton(
                    onClick = { openAppSettings(context) },
                    modifier = Modifier
                ) { Text("Open this app's system settings") }
                TextButton(
                    onClick = { openBatteryOptimizationSettings(context) },
                    modifier = Modifier
                ) { Text("View battery optimization list") }
            }
        }

        ExpandableSettingsSection("Local audible alarm", "Sound, repeats and current alarm state") {
            DiagnosticRow("Enabled", yesNo(report.audibleAlarmEnabled))
            DiagnosticRow("Currently sounding", yesNo(report.audibleAlarmActive))
            DiagnosticRow("Repeat interval", formatMinutes(report.audibleAlarmRepeatMinutes))
            DiagnosticRow("Repeat timing", report.audibleAlarmScheduleMode)
            DiagnosticRow("Sound", report.audibleAlarmSound)
            if (report.audibleAlarmScheduleMode == "Exact") {
                DiagnosticRow(
                    "Exact alarm access",
                    if (report.exactAlarmAccessGranted) "Allowed" else "Using best effort"
                )
            }
        }

        ExpandableSettingsSection("Recovery backups", "Schedule, destination and latest result") {
            DiagnosticRow("Automatic backups", yesNo(report.automaticBackupEnabled))
            DiagnosticRow("Destination", report.automaticBackupDestination)
            DiagnosticRow("Frequency", report.automaticBackupFrequency)
            DiagnosticRow("Copies kept", report.automaticBackupRetainedCopies.toString())
            DiagnosticRow("Last success", report.lastBackupSuccess)
            report.lastBackupError?.let {
                Text("Last backup error: $it", color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp)
            }
        }

        ExpandableSettingsSection(guidance.title, "Device-specific instructions") {
            Text(guidance.summary, color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp)
            guidance.steps.forEachIndexed { index, step ->
                Text("${index + 1}. $step", style = MaterialTheme.typography.bodyMedium)
            }
        }

        ExpandableSettingsSection("Device", "App version, Android and model") {
            DiagnosticRow("App version", report.appVersion)
            DiagnosticRow("Android", report.androidVersion)
            DiagnosticRow("Device", report.device)
        }

        CompactActions {
            Button(onClick = refresh) { Text("Refresh") }
            OutlinedButton(
                onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText("Flockle Grid Outage Monitor diagnostics", report.asPlainText()))
                    Toast.makeText(context, "Diagnostics copied", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier
            ) { Text("Copy report") }
        }
        OutlinedButton(
            onClick = { exportReport.launch(diagnosticsFileName()) },
            modifier = Modifier
        ) { Text("Export report as text file") }
    }
}

private fun diagnosticsFileName(): String = "flockle-grid-outage-monitor-diagnostics-${
    java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
}.txt"

@Composable
private fun DiagnosticCard(
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit
) {
    OutlinedCard(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape = RoundedCornerShape(20.dp),
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

private fun openAppSettings(context: Context) {
    val primary = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val fallback = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(primary) }
        .recoverCatching { context.startActivity(fallback) }
}

private fun openNotificationSettings(context: Context) {
    val primary = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val fallback = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(primary) }
        .recoverCatching { context.startActivity(fallback) }
}
