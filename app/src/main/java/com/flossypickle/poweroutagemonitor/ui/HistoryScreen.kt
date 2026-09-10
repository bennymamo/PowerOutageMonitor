package com.flossypickle.poweroutagemonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flossypickle.poweroutagemonitor.OutageEngine
import com.flossypickle.poweroutagemonitor.storage.EventHistoryStore
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertDeliverySummary
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import java.util.Locale

@Composable
internal fun HistoryScreen(
    records: List<EventHistoryStore.Record>,
    monitorState: OutageEngine.State,
    deliverySummaries: Map<String, AlertDeliverySummary.Event>,
    padding: PaddingValues
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text("History", style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold) }
        if (monitorState.phase == OutageEngine.Phase.OUTAGE ||
            monitorState.phase == OutageEngine.Phase.PENDING_RESTORE
        ) {
            item {
                EventCard("Ongoing outage", monitorState.outageStartedEpochMs ?: 0,
                    null, monitorState.outageStartBatteryPercent, null,
                    monitorState.outageStartBatteryTemperatureTenthsCelsius, null,
                    deliverySummaries[eventId(monitorState.outageStartedEpochMs ?: 0)])
            }
        }
        if (records.isEmpty()) {
            item {
                Card(shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("No power events yet", fontWeight = FontWeight.Medium)
                        Text("Confirmed outages and brief interruptions will appear here.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        items(records) { record ->
            EventCard(
                title = if (record.kind == EventHistoryStore.KIND_BRIEF_INTERRUPTION) {
                    "Brief interruption"
                } else "Confirmed outage",
                startedAt = record.powerLostAtEpochMs,
                restoredAt = record.restoredAtEpochMs,
                startBattery = record.startingBatteryPercent,
                endBattery = record.endingBatteryPercent,
                startTemperature = record.startingBatteryTemperatureTenthsCelsius,
                endTemperature = record.endingBatteryTemperatureTenthsCelsius,
                deliverySummary = deliverySummaries[eventId(record.powerLostAtEpochMs)]
            )
        }
    }
}

@Composable
private fun EventCard(
    title: String,
    startedAt: Long,
    restoredAt: Long?,
    startBattery: Int?,
    endBattery: Int?,
    startTemperature: Int?,
    endTemperature: Int?,
    deliverySummary: AlertDeliverySummary.Event?
) {
    val formatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)
    Card(shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            if (startedAt > 0) Text("Power lost: ${formatter.format(Date(startedAt))}")
            if (restoredAt != null) {
                Text("Restored: ${formatter.format(Date(restoredAt))}")
                Text("Duration: ${formatDuration(restoredAt - startedAt)}")
            }
            val batteryText = when {
                startBattery != null && endBattery != null -> "$startBattery% → $endBattery%"
                startBattery != null -> "$startBattery% at power loss"
                else -> null
            }
            batteryText?.let { Text("Battery: $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            val temperatureText = when {
                startTemperature != null && endTemperature != null ->
                    "${formatTemperature(startTemperature)} → ${formatTemperature(endTemperature)}"
                startTemperature != null -> "${formatTemperature(startTemperature)} at power loss"
                else -> null
            }
            temperatureText?.let {
                Text("Temperature: $it", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            deliverySummary?.let {
                Text("Alerts: ${it.label()}", color = when {
                    it.failed > 0 -> MaterialTheme.colorScheme.error
                    it.retrying > 0 || it.pending > 0 -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.primary
                })
            }
        }
    }
}

private fun formatTemperature(tenthsCelsius: Int): String =
    String.format(Locale.getDefault(), "%.1f °C", tenthsCelsius / 10.0)

private fun eventId(powerLostAtEpochMs: Long) = "power-event-$powerLostAtEpochMs"

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(durationMs.coerceAtLeast(0))
    val hours = totalSeconds / 3600
    val minutes = totalSeconds % 3600 / 60
    val seconds = totalSeconds % 60
    return buildList {
        if (hours > 0) add("${hours}h")
        if (minutes > 0 || hours > 0) add("${minutes}m")
        add("${seconds}s")
    }.joinToString(" ")
}
