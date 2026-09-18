package com.flossypickle.poweroutagemonitor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flossypickle.poweroutagemonitor.integrations.power.PowerSourceCheck
import java.text.DateFormat
import java.util.Date

internal fun checkReceiptTime(received: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(received))

@Composable
internal fun PowerSourceCheckSummary(check: PowerSourceCheck, paused: Boolean = false, enabled: Boolean = true) {
    val connection = when {
        !enabled -> "Monitoring off"
        paused -> "Paused · connection closed"
        check.cycleState == PowerSourceCheck.CycleState.CONNECTING -> "Connecting…"
        check.active -> "Collecting · ${check.deviceUpdates} updates"
        check.cycleState == PowerSourceCheck.CycleState.FAILED -> "Check failed · connection closed"
        else -> "Closed between checks"
    }
    Text(connection, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CheckTime("Last check", checkReceiptTime(check.requestedAtEpochMs), Modifier.weight(1f))
        CheckTime("Next check", when {
            !enabled || paused -> "Paused"
            check.active -> "After this check"
            else -> check.nextCheckAtEpochMs?.let(::checkReceiptTime) ?: "Manual only"
        }, Modifier.weight(1f))
    }
}

@Composable
private fun CheckTime(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

/** Provider-neutral check evidence, separated from the main grid status. All times are phone receipt times. */
@Composable
internal fun PowerSourceCheckDetails(check: PowerSourceCheck, title: String) {
    val colors = MaterialTheme.colorScheme
    val grid = check.observations.firstOrNull { it.label == "Reported grid code" }
    val meter = check.observations.firstOrNull { it.label == "Meter 1 reading" }
    val health = when (check.dataHealth) {
        PowerSourceCheck.DataHealth.CHANGING -> "Power values changing"
        PowerSourceCheck.DataHealth.UNCHANGED -> "Values unchanged so far"
        PowerSourceCheck.DataHealth.NO_UPDATES -> "No device updates received"
    }
    ExpandableSettingsSection(title, "Grid ${grid?.value ?: "?"} · Meter 1 ${meter?.value ?: "?"} · $health") {
        SettingText("Last device update", check.liveReportAtEpochMs?.let(::checkReceiptTime) ?: "Not received")
        SettingText("Updates this check", "${check.deviceUpdates} total · ${check.powerUpdates} power reports")
        SettingText("Data health", health)
        SettingText("Check finished", check.finishedAtEpochMs?.let(::checkReceiptTime) ?: "In progress")
        listOf("Reported grid code", "Meter 1 reading").forEach { label ->
            val value = check.observations.firstOrNull { it.label == label }
            SettingsCard {
                SettingText(label, value?.value ?: "Not received")
                value?.let {
                    Text(it.explanation, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    val origin = when {
                        it.fromDevicePush -> "Device update"
                        it.supportedByLiveFeed -> "Unchanged code; device data updating"
                        else -> "Reported reply; needs current device data"
                    }
                    Text("${checkReceiptTime(it.receivedAtEpochMs)} · $origin", style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant)
                }
            }
        }
        if (check.readings.isNotEmpty()) ExpandableSettingsSection("Power readings", "Latest values received in this check") {
            check.readings.forEach { SettingText(it.label, "${it.value} ${it.unit}") }
        }
        ExpandableSettingsSection("How to read this check", "Freshness, timing and safe interpretation") {
            Text("Each scheduled or manual check opens a connection, requests live reporting and listens for changing device data. It closes after enough updates or the time limit. No reading requests or open connection remain between checks.", style = MaterialTheme.typography.bodySmall)
            Text("Changing power values support apparent freshness, even when the grid code stays the same. Unchanged values can mean a steady load or a stuck feed. A reply alone does not verify a new grid transition. Times show when this phone received data; EcoFlow does not provide a verified measurement time.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
