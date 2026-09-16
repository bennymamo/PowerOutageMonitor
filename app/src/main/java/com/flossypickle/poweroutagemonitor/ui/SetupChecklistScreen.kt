package com.flossypickle.poweroutagemonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flossypickle.poweroutagemonitor.OutageEngine
import com.flossypickle.poweroutagemonitor.diagnostics.SystemHealthSnapshot
import com.flossypickle.poweroutagemonitor.storage.MonitorStore

@Composable
internal fun SetupChecklistScreen(
    settings: MonitorStore.Settings,
    monitorState: OutageEngine.State,
    systemHealth: SystemHealthSnapshot,
    alertChannels: String,
    hasEnabledAlertChannel: Boolean,
    hasSentTestAlert: Boolean,
    padding: PaddingValues,
    onOpenStatus: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenTestMode: () -> Unit,
    onOpenEmail: () -> Unit,
    onOpenTelegram: () -> Unit,
    onOpenSms: () -> Unit,
    onBack: () -> Unit,
    backLabel: String = "Settings"
) {
    val powerBaselineKnown = monitorState.phase != OutageEngine.Phase.WAITING
    val coreChecks = listOf(
        settings.monitoringEnabled,
        powerBaselineKnown,
        systemHealth.notificationsAllowed,
        !systemHealth.backgroundRestricted,
        hasEnabledAlertChannel,
        hasSentTestAlert
    )
    val completed = coreChecks.count { it }
    val guided = settings.helpLevel.isGuided

    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp).widthIn(max = 600.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        TextButton(onClick = onBack) { Text("‹ $backLabel") }
        SetupGuidanceCaption(settings.helpLevel)
        Text(
            "Setup checklist",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            if (guided) {
                "Work down this list once. FP Grid Monitor checks each result and shows where to fix anything unfinished."
            } else {
                "Live readiness checks for monitoring, Android restrictions and alert delivery."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (completed == coreChecks.size) {
                    MaterialTheme.colorScheme.primaryContainer
                } else MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    if (completed == coreChecks.size) "Ready for a real unplug test" else "$completed of ${coreChecks.size} checks complete",
                    fontWeight = FontWeight.Bold
                )
                LinearProgressIndicator(
                    progress = { completed.toFloat() / coreChecks.size },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    if (completed == coreChecks.size) {
                        "The checks available inside the app pass. A physical-phone test is still needed before relying on alerts."
                    } else {
                        "Tap the action under an unfinished check. The result updates when you return."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
        }

        ChecklistItem(
            number = 1,
            complete = settings.monitoringEnabled,
            title = "Monitoring is on",
            explanation = if (guided) {
                "The master switch keeps the foreground monitor and its small ongoing notification running."
            } else "Foreground monitoring is enabled.",
            actionLabel = "Open Status",
            onAction = onOpenStatus
        )
        ChecklistItem(
            number = 2,
            complete = powerBaselineKnown,
            title = "Charger baseline detected",
            explanation = if (guided) {
                "Connect the phone to its normal charger. The app waits for this first powered reading so starting it while unplugged cannot create a false outage."
            } else "The state machine has observed external power at least once.",
            actionLabel = "Check live power reading",
            onAction = onOpenDiagnostics
        )
        ChecklistItem(
            number = 3,
            complete = systemHealth.notificationsAllowed,
            title = "Notifications are allowed",
            explanation = if (guided) {
                "Android must allow the small ongoing notification. It tells you the monitor is still running and is required for reliable background work."
            } else "Notification permission and app-level notifications are enabled.",
            actionLabel = "Open Android checks",
            onAction = onOpenDiagnostics
        )
        ChecklistItem(
            number = 4,
            complete = !systemHealth.backgroundRestricted,
            title = "Background activity is not restricted",
            explanation = if (guided) {
                "Some phones stop apps to save power. Diagnostics links to Android's app and battery screens so you can allow reliable background operation."
            } else "Android does not currently report this app as background restricted.",
            actionLabel = "Review background settings",
            onAction = onOpenDiagnostics
        )
        ChecklistItem(
            number = 5,
            complete = hasEnabledAlertChannel,
            title = "An alert channel is enabled",
            explanation = if (guided) {
                "Choose where outage messages should go. Gmail is the easiest internet option; Telegram and SIM-based SMS are also available. Current: $alertChannels."
            } else "Enabled destinations: $alertChannels.",
            actionLabel = "Set up Gmail or email",
            onAction = onOpenEmail,
            extraActions = listOf(
                "Telegram" to onOpenTelegram,
                "Device SMS" to onOpenSms
            )
        )
        ChecklistItem(
            number = 6,
            complete = hasSentTestAlert,
            title = "A test alert was delivered",
            explanation = if (guided) {
                "Test mode creates a clearly marked simulation. Send one and check that it reaches the person or device that should receive a real outage alert."
            } else "At least one simulated alert has a recorded SENT result.",
            actionLabel = "Open test mode",
            onAction = onOpenTestMode
        )

        NetworkBackupGuidance()

        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Recommended reliability review", fontWeight = FontWeight.SemiBold)
                Text(
                    if (systemHealth.batteryOptimizationExcluded) {
                        "✓ Android battery optimization is disabled for FP Grid Monitor."
                    } else if (guided) {
                        "Android battery optimization is still active. Open Diagnostics and follow Keep FP Grid Monitor Running. Phone makers may add their own battery controls too."
                    } else {
                        "Battery-optimization exemption is not granted. Review Diagnostics and OEM controls."
                    },
                    color = if (systemHealth.batteryOptimizationExcluded) {
                        MaterialTheme.colorScheme.primary
                    } else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) {
                    Text("Open reliability guidance")
                }
            }
        }
    }
}

@Composable
private fun ChecklistItem(
    number: Int,
    complete: Boolean,
    title: String,
    explanation: String,
    actionLabel: String,
    onAction: () -> Unit,
    extraActions: List<Pair<String, () -> Unit>> = emptyList()
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    if (complete) "✓" else number.toString(),
                    color = if (complete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (complete) "Complete" else "Needs attention",
                        color = if (complete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Text(explanation, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            if (!complete) {
                Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) {
                    Text(actionLabel)
                }
            }
            extraActions.forEach { (label, action) ->
                OutlinedButton(onClick = action, modifier = Modifier.fillMaxWidth()) {
                    Text(label)
                }
            }
        }
    }
}
