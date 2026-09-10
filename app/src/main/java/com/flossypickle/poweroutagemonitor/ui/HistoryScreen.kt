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
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

@Composable
internal fun HistoryScreen(
    records: List<EventHistoryStore.Record>,
    monitorState: OutageEngine.State,
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
                    null, monitorState.outageStartBatteryPercent, null)
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
                endBattery = record.endingBatteryPercent
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
    endBattery: Int?
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
        }
    }
}

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
