package com.flossypickle.poweroutagemonitor.ui

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flossypickle.poweroutagemonitor.storage.MonitorStore
import java.text.DateFormat
import java.util.Date

private enum class SimulationStage { READY, OUTAGE, RESTORED }

@Composable
internal fun TestModeScreen(
    settings: MonitorStore.Settings,
    padding: PaddingValues,
    onBack: () -> Unit
) {
    var stage by rememberSaveable { mutableStateOf(SimulationStage.READY) }
    var lostAt by rememberSaveable { mutableLongStateOf(0L) }
    var restoredAt by rememberSaveable { mutableLongStateOf(0L) }

    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp).widthIn(max = 600.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TextButton(onClick = onBack) { Text("‹ Settings") }
        Text("Test mode", style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold)

        TestCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
            Text("SIMULATION", fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer, letterSpacing = 2.sp)
            Text("These controls only preview the user-visible flow. They do not change monitoring state, history, alarms or real power readings.",
                color = MaterialTheme.colorScheme.onPrimaryContainer)
        }

        Text("Power event", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary)
        TestCard {
            Button(
                onClick = {
                    lostAt = System.currentTimeMillis()
                    restoredAt = 0L
                    stage = SimulationStage.OUTAGE
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Simulate confirmed outage") }
            OutlinedButton(
                onClick = {
                    restoredAt = System.currentTimeMillis()
                    stage = SimulationStage.RESTORED
                },
                enabled = stage == SimulationStage.OUTAGE,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Simulate restored power") }
            Text("No alert channel is configured yet, so this milestone previews the exact message content locally.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }

        Text("Message preview", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary)
        TestCard {
            when (stage) {
                SimulationStage.READY -> Text("Run a simulation to preview an alert.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                SimulationStage.OUTAGE -> AlertPreview(outageMessage(settings, lostAt))
                SimulationStage.RESTORED -> AlertPreview(restoredMessage(settings, lostAt, restoredAt))
            }
        }

        if (stage != SimulationStage.READY) {
            OutlinedButton(
                onClick = {
                    stage = SimulationStage.READY
                    lostAt = 0L
                    restoredAt = 0L
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Reset simulation") }
        }
    }
}

@Composable
private fun TestCard(
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)) {
        Column(Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun AlertPreview(message: String) {
    Text(message, style = MaterialTheme.typography.bodyLarge)
}

private fun outageMessage(settings: MonitorStore.Settings, lostAt: Long) = buildString {
    appendLine("POWER OUTAGE DETECTED")
    appendLine()
    appendLine("Device: ${settings.deviceName}")
    appendLine("Power lost: ${formatTime(lostAt)}")
    appendLine("Alert delay: ${formatDuration(settings.outageDelayMs)}")
    append("Status: Running on battery")
}

private fun restoredMessage(settings: MonitorStore.Settings, lostAt: Long, restoredAt: Long) = buildString {
    appendLine("POWER RESTORED")
    appendLine()
    appendLine("Device: ${settings.deviceName}")
    appendLine("Power restored: ${formatTime(restoredAt)}")
    append("Outage duration: ${formatDuration((restoredAt - lostAt).coerceAtLeast(0))}")
}

private fun formatTime(epochMs: Long): String = DateFormat.getDateTimeInstance().format(Date(epochMs))

private fun formatDuration(durationMs: Long): String {
    val seconds = durationMs / 1_000
    val hours = seconds / 3_600
    val minutes = seconds % 3_600 / 60
    val remainingSeconds = seconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m ${remainingSeconds}s"
        minutes > 0 -> "${minutes}m ${remainingSeconds}s"
        else -> "${remainingSeconds}s"
    }
}
