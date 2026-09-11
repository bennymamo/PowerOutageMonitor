package com.flossypickle.poweroutagemonitor.ui

import android.os.BatteryManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flossypickle.poweroutagemonitor.OutageEngine
import com.flossypickle.poweroutagemonitor.diagnostics.SystemHealthSnapshot
import com.flossypickle.poweroutagemonitor.monitoring.PowerSnapshot
import com.flossypickle.poweroutagemonitor.storage.EventHistoryStore
import com.flossypickle.poweroutagemonitor.storage.MonitorStore
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

private const val RESTORED_STATUS_DURATION_MS = 60_000L

private enum class GridTone { GOOD, CAUTION, DANGER, MUTED }

private data class GridStatus(
    val symbol: String,
    val title: String,
    val description: String,
    val tone: GridTone
)

@Composable
internal fun DashboardScreen(
    snapshot: PowerSnapshot?,
    monitorState: OutageEngine.State,
    settings: MonitorStore.Settings,
    history: List<EventHistoryStore.Record>,
    lastObservationEpochMs: Long,
    deliveryWarning: String?,
    alertChannels: String,
    systemHealth: SystemHealthSnapshot,
    audibleAlarmActive: Boolean,
    padding: PaddingValues,
    onMonitoringEnabledChange: (Boolean) -> Unit,
    onDismissAudibleAlarm: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val lastEvent = history.firstOrNull()
    var statusClock by remember(lastEvent?.restoredAtEpochMs) {
        mutableLongStateOf(System.currentTimeMillis())
    }
    LaunchedEffect(lastEvent?.restoredAtEpochMs, monitorState.phase) {
        statusClock = System.currentTimeMillis()
        val restoredAt = lastEvent?.takeIf {
            it.kind == EventHistoryStore.KIND_CONFIRMED_OUTAGE
        }?.restoredAtEpochMs ?: return@LaunchedEffect
        val remaining = restoredAt + RESTORED_STATUS_DURATION_MS - statusClock
        if (remaining > 0) {
            delay(remaining)
            statusClock = System.currentTimeMillis()
        }
    }
    val recentlyRestored = lastEvent?.kind == EventHistoryStore.KIND_CONFIRMED_OUTAGE &&
        statusClock - lastEvent.restoredAtEpochMs in 0 until RESTORED_STATUS_DURATION_MS
    val status = gridStatus(
        powered = snapshot?.externallyPowered,
        phase = monitorState.phase,
        enabled = settings.monitoringEnabled,
        recentlyRestored = recentlyRestored,
        outageDelayMs = settings.outageDelayMs
    )
    val statusColor = when (status.tone) {
        GridTone.GOOD -> colors.primary
        GridTone.CAUTION -> Color(0xFFF0C580)
        GridTone.DANGER -> colors.error
        GridTone.MUTED -> colors.onSurfaceVariant
    }

    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "FLOSSY PICKLE",
                    color = colors.primary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp
                )
                Text("Grid outage monitor", fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
                Text(settings.deviceName, color = colors.onSurfaceVariant, fontSize = 14.sp)
            }

            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = colors.surface)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        Modifier.size(54.dp).background(statusColor.copy(alpha = 0.16f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(status.symbol, color = statusColor, fontSize = 27.sp,
                            fontWeight = FontWeight.Bold)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            status.title,
                            color = statusColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.4.sp
                        )
                        Text(status.description, style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium)
                    }
                }
            }

            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = colors.surface)
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Device battery", color = colors.onSurfaceVariant)
                        Text(snapshot?.batteryPercent?.let { "$it%" } ?: "Unknown",
                            fontWeight = FontWeight.SemiBold)
                    }
                    LinearProgressIndicator(
                        progress = { (snapshot?.batteryPercent ?: 0).coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = statusColor,
                        trackColor = colors.surfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ReadingTile("POWER INPUT", sourceText(snapshot?.plugged), Modifier.weight(1f))
                        ReadingTile("BATTERY STATE", statusText(snapshot?.batteryStatus), Modifier.weight(1f))
                    }
                }
            }

            systemHealth.monitoringAttention(settings.monitoringEnabled)?.let { WarningCard(it) }
            deliveryWarning?.let { WarningCard(it) }

            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = colors.surface)
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Monitoring", fontWeight = FontWeight.Medium)
                            Text(
                                monitorLabel(settings.monitoringEnabled, monitorState.phase),
                                color = if (settings.monitoringEnabled) colors.primary
                                else colors.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                        Switch(
                            checked = settings.monitoringEnabled,
                            onCheckedChange = onMonitoringEnabledChange
                        )
                    }
                    Text(
                        monitorExplanation(settings.monitoringEnabled, monitorState.phase),
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (audibleAlarmActive) {
                        OutlinedButton(
                            onClick = onDismissAudibleAlarm,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Dismiss audible alarm") }
                    }
                    StatusRow(
                        "Internet",
                        if (systemHealth.internetAvailable) "Available" else "Unavailable",
                        if (systemHealth.internetAvailable) colors.primary else Color(0xFFF0C580)
                    )
                    StatusRow(
                        "Alert channels",
                        alertChannels,
                        if (alertChannels == "None configured") Color(0xFFF0C580) else colors.primary
                    )
                    if (lastObservationEpochMs > 0) {
                        StatusRow(
                            "Last grid reading",
                            DateFormat.getTimeInstance(DateFormat.SHORT)
                                .format(Date(lastObservationEpochMs)),
                            colors.onSurfaceVariant
                        )
                    }
                }
            }

            HorizontalDivider(color = colors.outline)
            lastEvent?.let {
                Text(
                    "LAST POWER EVENT",
                    color = colors.primary,
                    fontSize = 10.sp,
                    letterSpacing = 2.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (it.kind == EventHistoryStore.KIND_CONFIRMED_OUTAGE) {
                        "Confirmed outage"
                    } else {
                        "Brief interruption"
                    },
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it.powerLostAtEpochMs))} · ${formatEventDuration(it.restoredAtEpochMs - it.powerLostAtEpochMs)}",
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            } ?: run {
                Text(
                    "GRID DETECTION",
                    color = colors.primary,
                    fontSize = 10.sp,
                    letterSpacing = 2.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Connect external power once to arm grid-outage detection.",
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun WarningCard(warning: String) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Text(
            warning,
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ReadingTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, fontSize = 9.sp, letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun StatusRow(label: String, value: String, valueColor: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Text(value, color = valueColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

private fun gridStatus(
    powered: Boolean?,
    phase: OutageEngine.Phase,
    enabled: Boolean,
    recentlyRestored: Boolean,
    outageDelayMs: Long
): GridStatus = when {
    !enabled -> GridStatus(
        "Ⅱ", "MONITORING PAUSED", "Grid changes are not being recorded.", GridTone.MUTED
    )
    phase == OutageEngine.Phase.PENDING_OUTAGE -> GridStatus(
        "⚠", "POSSIBLE OUTAGE", "External power disappeared; confirming the outage.", GridTone.CAUTION
    )
    phase == OutageEngine.Phase.OUTAGE -> GridStatus(
        "!", "OUTAGE CONFIRMED", "External power remains unavailable.", GridTone.DANGER
    )
    phase == OutageEngine.Phase.PENDING_RESTORE -> GridStatus(
        "↻", "CHECKING RESTORATION", "Power returned; checking that it remains stable.", GridTone.CAUTION
    )
    phase == OutageEngine.Phase.WAITING -> GridStatus(
        "○", "WAITING TO ARM", "Connect external power once to start detection.", GridTone.MUTED
    )
    recentlyRestored -> GridStatus(
        "✓", "POWER RESTORED", "Stable external power returned after the outage.", GridTone.GOOD
    )
    powered == true -> GridStatus(
        "⚡", "GRID POWER ONLINE", "External power is reaching this device.", GridTone.GOOD
    )
    powered == false -> GridStatus(
        "⚠", "ON BATTERY", if (outageDelayMs == 0L) "Confirming grid status."
        else "Waiting for the outage confirmation rule.", GridTone.CAUTION
    )
    else -> GridStatus(
        "?", "GRID STATE UNKNOWN", "Waiting for Android's power reading.", GridTone.MUTED
    )
}

private fun sourceText(plugged: Int?) = when (plugged) {
    0 -> "None"
    BatteryManager.BATTERY_PLUGGED_AC -> "AC charger"
    BatteryManager.BATTERY_PLUGGED_USB -> "USB"
    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
    BatteryManager.BATTERY_PLUGGED_DOCK -> "Dock"
    null, -1 -> "Unknown"
    else -> "Other"
}

private fun statusText(status: Int?) = when (status) {
    BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
    BatteryManager.BATTERY_STATUS_FULL -> "Full"
    BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
    else -> "Unknown"
}

private fun formatEventDuration(durationMs: Long): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(durationMs.coerceAtLeast(0))
    val hours = totalSeconds / 3_600
    val minutes = totalSeconds % 3_600 / 60
    val seconds = totalSeconds % 60
    return buildList {
        if (hours > 0) add("${hours}h")
        if (minutes > 0 || hours > 0) add("${minutes}m")
        add("${seconds}s")
    }.joinToString(" ")
}

private fun monitorLabel(enabled: Boolean, phase: OutageEngine.Phase) = when {
    !enabled -> "Off"
    phase == OutageEngine.Phase.WAITING -> "Waiting"
    phase == OutageEngine.Phase.PENDING_OUTAGE -> "Checking outage"
    phase == OutageEngine.Phase.OUTAGE -> "Outage"
    phase == OutageEngine.Phase.PENDING_RESTORE -> "Checking restore"
    else -> "Active"
}

private fun monitorExplanation(enabled: Boolean, phase: OutageEngine.Phase) = when {
    !enabled -> "Use the switch above to resume background grid monitoring."
    phase == OutageEngine.Phase.WAITING -> "Connect external power once to arm outage detection."
    phase == OutageEngine.Phase.PENDING_OUTAGE -> "Power is absent; waiting for the confirmation delay."
    phase == OutageEngine.Phase.OUTAGE -> "A sustained grid outage has been confirmed."
    phase == OutageEngine.Phase.PENDING_RESTORE -> "Power returned; checking that it remains stable."
    else -> "Background grid monitoring is active."
}
