package com.flossypickle.poweroutagemonitor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.flossypickle.poweroutagemonitor.integrations.power.PowerSourceCheck
import java.text.DateFormat
import java.util.Date

/** Expandable, provider-neutral request diagnostics; keeps the main dashboard uncluttered. */
@Composable
internal fun PowerSourceCheckDetails(check: PowerSourceCheck, title: String) {
    val colors = MaterialTheme.colorScheme
    val grid = check.observations.firstOrNull { it.label == "Reported grid code" }
    val meter = check.observations.firstOrNull { it.label == "Meter 1 reading" }
    val summary = "Grid code: ${grid?.value ?: "not received"} · Meter 1: ${meter?.value ?: "not received"}"
    fun time(received: Long) = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(received))
    ExpandableSettingsSection(title, summary) {
        Text("The schedule controls when we request readings. EcoFlow can send additional updates while connected. An unchanged grid code is kept while new device readings arrive; a gap in the feed makes grid evidence Unknown. Receipt times below are phone times, not equipment measurement times.",
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        listOf("Reported grid code", "Meter 1 reading").forEach { label ->
            val value = check.observations.firstOrNull { it.label == label }
            StatusRow(label, value?.value ?: "Not received", colors.onSurfaceVariant)
            value?.let {
                Text(it.explanation, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                val origin = when {
                    it.fromDevicePush -> "Device update"
                    it.supportedByLiveFeed -> "Last reported code; device feed updating"
                    else -> "EcoFlow reply; not confirmed by current device updates"
                }
                val age = if (it.receivedAtEpochMs < check.requestedAtEpochMs) " · from an earlier check" else ""
                Text("Received ${time(it.receivedAtEpochMs)} · $origin$age", style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant)
            }
        }
        StatusRow("Last check requested", time(check.requestedAtEpochMs), colors.onSurfaceVariant)
        check.liveReportAtEpochMs?.let { StatusRow("Last device update received", time(it), colors.onSurfaceVariant) }
        if (check.readings.isNotEmpty()) {
            Text("Last reported power values · EcoFlow does not supply a verified measurement time",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            check.readings.forEach { StatusRow(it.label, "${it.value} ${it.unit}", colors.onSurfaceVariant) }
        }
    }
}
