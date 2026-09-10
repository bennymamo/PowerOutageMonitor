package com.flossypickle.poweroutagemonitor.ui

import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flossypickle.poweroutagemonitor.OutageEngine
import com.flossypickle.poweroutagemonitor.monitoring.PowerSnapshot
import com.flossypickle.poweroutagemonitor.storage.MonitorStore
import java.text.DateFormat
import java.util.Date

@Composable
internal fun DashboardScreen(
    snapshot: PowerSnapshot?,
    monitorState: OutageEngine.State,
    settings: MonitorStore.Settings,
    lastObservationEpochMs: Long,
    padding: PaddingValues
) {
    val colors = MaterialTheme.colorScheme
    val powered = snapshot?.externallyPowered
    val accent = when (powered) {
        true -> colors.primary
        false -> Color(0xFFF0C580)
        null -> colors.onSurfaceVariant
    }
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("FLOSSY PICKLE", color = colors.primary, fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                Text("Power monitor", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                Text(settings.deviceName, color = colors.onSurfaceVariant, fontSize = 14.sp)
            }
            Card(shape = RoundedCornerShape(26.dp),
                colors = CardDefaults.cardColors(containerColor = colors.surface)) {
                Column(Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(8.dp).background(accent, CircleShape))
                        Text(powerHeadline(powered), color = accent, fontSize = 11.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    }
                    Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
                            val stroke = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round)
                            drawArc(colors.surfaceVariant, 135f, 270f, false, style = stroke)
                            snapshot?.batteryPercent?.let {
                                drawArc(accent, 135f, 270f * it / 100f, false, style = stroke)
                            }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(snapshot?.batteryPercent?.let { "$it%" } ?: "—",
                                fontSize = 34.sp, fontWeight = FontWeight.Light)
                            Text("BATTERY", color = colors.onSurfaceVariant, fontSize = 9.sp,
                                letterSpacing = 2.sp)
                        }
                    }
                    Text(powerDescription(powered), style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Medium)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ReadingTile("POWER SOURCE", sourceText(snapshot?.plugged), Modifier.weight(1f))
                ReadingTile("BATTERY STATE", statusText(snapshot?.batteryStatus), Modifier.weight(1f))
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Monitoring", fontWeight = FontWeight.Medium)
                    Text(monitorLabel(settings.monitoringEnabled, monitorState.phase),
                        color = if (settings.monitoringEnabled) colors.primary else colors.onSurfaceVariant,
                        fontSize = 13.sp)
                }
                Text(monitorExplanation(settings.monitoringEnabled, monitorState.phase),
                    color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                if (lastObservationEpochMs > 0) {
                    Text("Last observation: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(lastObservationEpochMs))}",
                        color = colors.onSurfaceVariant, fontSize = 11.sp)
                }
            }
            HorizontalDivider(color = colors.outline)
            Text("POWER DETECTION", color = colors.primary, fontSize = 10.sp,
                letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
            Text("Unplug your charger, then reconnect it. The indicator follows Android’s external-power reading.",
                color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ReadingTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, fontSize = 9.sp, letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleSmall)
        }
    }
}

private fun powerHeadline(powered: Boolean?) = when (powered) {
    true -> "EXTERNAL POWER"
    false -> "ON BATTERY"
    null -> "AWAITING READING"
}

private fun powerDescription(powered: Boolean?) = when (powered) {
    true -> "External power connected"
    false -> "Running on battery"
    null -> "Power status unavailable"
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

private fun monitorLabel(enabled: Boolean, phase: OutageEngine.Phase) = when {
    !enabled -> "Off"
    phase == OutageEngine.Phase.WAITING -> "Waiting"
    phase == OutageEngine.Phase.PENDING_OUTAGE -> "Checking outage"
    phase == OutageEngine.Phase.OUTAGE -> "Outage"
    phase == OutageEngine.Phase.PENDING_RESTORE -> "Checking restore"
    else -> "Active"
}

private fun monitorExplanation(enabled: Boolean, phase: OutageEngine.Phase) = when {
    !enabled -> "Open Settings to start reliable background monitoring."
    phase == OutageEngine.Phase.WAITING -> "Connect external power once to arm outage detection."
    phase == OutageEngine.Phase.PENDING_OUTAGE -> "Power is absent; waiting for the confirmation delay."
    phase == OutageEngine.Phase.OUTAGE -> "A sustained power outage has been confirmed."
    phase == OutageEngine.Phase.PENDING_RESTORE -> "Power returned; checking that it remains stable."
    else -> "Background monitoring is active."
}
