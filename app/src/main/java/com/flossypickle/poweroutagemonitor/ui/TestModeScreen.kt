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
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertMessage
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertMessageFactory

private enum class SimulationStage { READY, OUTAGE, RESTORED }

@Composable
internal fun TestModeScreen(
    settings: MonitorStore.Settings,
    padding: PaddingValues,
    onSendTestAlert: (AlertMessage) -> Boolean,
    onBack: () -> Unit
) {
    var stage by rememberSaveable { mutableStateOf(SimulationStage.READY) }
    var lostAt by rememberSaveable { mutableLongStateOf(0L) }
    var restoredAt by rememberSaveable { mutableLongStateOf(0L) }
    var deliveryFeedback by rememberSaveable { mutableStateOf<String?>(null) }

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
            Text("These controls never change monitoring state, history, alarms or real power readings. Sending is always a separate explicit action.",
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
                    deliveryFeedback = null
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Simulate confirmed outage") }
            OutlinedButton(
                onClick = {
                    restoredAt = System.currentTimeMillis()
                    stage = SimulationStage.RESTORED
                    deliveryFeedback = null
                },
                enabled = stage == SimulationStage.OUTAGE,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Simulate restored power") }
            Text("Previewing is local. Use the separate send button below to exercise configured channels.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }

        Text("Message preview", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary)
        TestCard {
            when (stage) {
                SimulationStage.READY -> Text("Run a simulation to preview an alert.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                SimulationStage.OUTAGE -> AlertPreview(AlertMessageFactory.testOutage(settings, lostAt))
                SimulationStage.RESTORED -> AlertPreview(
                    AlertMessageFactory.testRestored(settings, lostAt, restoredAt)
                )
            }
        }

        if (stage != SimulationStage.READY) {
            val message = when (stage) {
                SimulationStage.OUTAGE -> AlertMessageFactory.testOutage(settings, lostAt)
                SimulationStage.RESTORED -> AlertMessageFactory.testRestored(settings, lostAt, restoredAt)
                SimulationStage.READY -> null
            }
            Button(
                onClick = {
                    deliveryFeedback = if (message != null && onSendTestAlert(message)) {
                        "Simulated alert queued for every enabled channel."
                    } else {
                        "No alert channel is enabled. Configure one in Settings first."
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Send simulated alert") }
            deliveryFeedback?.let {
                TestCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(it, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }

        if (stage != SimulationStage.READY) {
            OutlinedButton(
                onClick = {
                    stage = SimulationStage.READY
                    lostAt = 0L
                    restoredAt = 0L
                    deliveryFeedback = null
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
private fun AlertPreview(message: AlertMessage) {
    Text("${message.title}\n\n${message.body}", style = MaterialTheme.typography.bodyLarge)
}
