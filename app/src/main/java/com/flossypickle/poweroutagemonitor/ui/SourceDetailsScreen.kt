package com.flossypickle.poweroutagemonitor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flossypickle.poweroutagemonitor.integrations.power.SourceTelemetryReading
import com.flossypickle.poweroutagemonitor.integrations.power.SourceTelemetrySnapshot
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/** Shared, vendor-independent dashboard; each source supplies its own field mapping. */
@Composable
internal fun SourceDetailsScreen(
    snapshot: SourceTelemetrySnapshot,
    padding: PaddingValues,
    refreshing: Boolean,
    refreshError: String?,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    dashboardControls: (@Composable () -> Unit)? = null,
    refreshLabel: String = "Refresh device readings"
) {
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable(snapshot.deviceName) {
        mutableStateOf(ArrayList(snapshot.sections.filter { it.initiallyExpanded }.map { it.id }))
    }
    var now by remember(snapshot.receivedAtEpochMs) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(snapshot.receivedAtEpochMs) {
        while (true) {
            delay(10_000)
            now = System.currentTimeMillis()
        }
    }
    val allReadings = snapshot.sections.flatMap { it.readings }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding).testTag("source-details-list"),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            TextButton(onClick = onBack) { Text("‹ Source setup") }
            Text(snapshot.sourceName, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
            Text(snapshot.deviceName, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        }
        item {
            OutlinedCard(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Read-only device snapshot", fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface)
                    Text("Received ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(snapshot.receivedAtEpochMs))} · ${requestAge(now, snapshot.receivedAtEpochMs)}",
                        color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                    Text(
                        if (snapshot.deviceReportedAtEpochMs == null) "Device data age is not verified; readings may be cached."
                        else "Device timestamp: ${DateFormat.getDateTimeInstance().format(Date(snapshot.deviceReportedAtEpochMs))}. A timestamp alone does not prove a trustworthy outage signal.",
                        color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp
                    )
                }
            }
        }
        if (dashboardControls != null) item { dashboardControls() }
        if (refreshError != null) {
            item {
                Text("Refresh failed: $refreshError. Showing the previous snapshot.",
                    color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
        }
        item {
            Button(onClick = onRefresh, enabled = !refreshing, modifier = Modifier) {
                if (refreshing) CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp, modifier = Modifier.padding(end = 12.dp).size(20.dp))
                Text(if (refreshing) "Reading device…" else refreshLabel)
            }
        }
        if (snapshot.summary.isNotEmpty()) {
            items(snapshot.summary.chunked(2), key = { row -> "summary:${row.first().key}" }) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { reading ->
                        OutlinedCard(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.weight(1f), shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(reading.label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                                Text(displayValue(reading), style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
        item {
            Text("${allReadings.size} reported readings", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(value = query, onValueChange = { query = it.take(120) },
                label = { Text("Find a reading") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        snapshot.sections.forEach { section ->
            val matches = section.readings.filter {
                query.isBlank() || it.label.contains(query, ignoreCase = true) || it.key.contains(query, ignoreCase = true)
            }
            if (matches.isNotEmpty()) {
                item(key = "section:${section.id}") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(section.title, fontWeight = FontWeight.SemiBold)
                            Text("${matches.size} readings", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                        TextButton(onClick = {
                            expanded = ArrayList(if (section.id in expanded) expanded - section.id else expanded + section.id)
                        }) { Text(if (section.id in expanded) "Collapse" else "Expand") }
                    }
                }
                if (section.id in expanded || query.isNotBlank()) {
                    items(matches, key = { "reading:${it.key}" }) { reading ->
                        SettingsCard {
                            SettingText(reading.label, displayValue(reading))
                            if (reading.key != reading.label) {
                                Text(reading.key, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }
        if (query.isNotBlank() && allReadings.none { it.label.contains(query, true) || it.key.contains(query, true) }) {
            item { Text("No matching readings in this snapshot.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (allReadings.isEmpty()) {
            item { Text("This device returned no displayable readings. Availability alone is not evidence of grid power.") }
        }
        item {
            Text("These readings do not change your selected outage detector.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            snapshot.acquisitionNote?.let { note ->
                ExpandableSettingsSection("Reading information", "Connection method and data limitations") {
                    Text(note, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
            Text("Power-flow signs are shown as reported by the device. Import/export and charging direction need validation for your equipment.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            if (snapshot.omittedValues > 0) {
                Text("${snapshot.omittedValues} private, oversized or unsupported values were omitted.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
    }
}

private fun displayValue(reading: SourceTelemetryReading): String =
    reading.value + if (reading.unit.isEmpty()) "" else " ${reading.unit}"

private fun requestAge(now: Long, received: Long): String {
    val seconds = (now - received) / 1_000
    return when {
        seconds < 0 -> "check device clock"
        seconds < 60 -> "received just now"
        seconds < 3_600 -> "received ${seconds / 60} min ago"
        else -> "received ${seconds / 3_600} h ago"
    }
}
