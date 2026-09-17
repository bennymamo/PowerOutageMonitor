package com.flossypickle.poweroutagemonitor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.PowerOceanAssistedSettings

@Composable
internal fun PowerOceanSamplingSettings(settings: PowerOceanAssistedSettings, onChange: (PowerOceanAssistedSettings) -> Unit) {
    SettingsCard {
        SettingSwitch("Charger-first assistance", "Charger alerts work offline. Verified EcoFlow grid/meter loss also counts as an outage if backup power keeps the charger on. Each source can detect loss independently.", settings.enabled,
            { onChange(settings.copy(enabled = it)) })
        if (settings.enabled) Text("A charger that loses grid power gives the earliest local alert. If backup power keeps it on, EcoFlow can still detect an outage at its next check. A powered charger cannot clear an EcoFlow-detected outage.", style = MaterialTheme.typography.bodySmall)
    }
    ExpandableSettingsSection("Stuck-reading safeguards", "Warning after three identical checks") {
        Text("Power readings are compared between checks, ignoring request IDs and timestamps. A steady load can legitimately produce identical values. Changing power values clears the warning.", style = MaterialTheme.typography.bodySmall)
        SettingSwitch("Send stuck-reading warning", "Send one warning per episode through every enabled alert method. Dashboard warnings always remain visible.", settings.warnOnUnchanged,
            { onChange(settings.copy(warnOnUnchanged = it)) })
        if (settings.enabled) SettingSwitch("Ignore stuck EcoFlow readings", "Keep checking, but exclude unchanged EcoFlow readings from confirmation and recovery until power values change. Charger detection continues.", settings.ignoreUnchanged,
            { onChange(settings.copy(ignoreUnchanged = it)) })
    }
    if (settings.enabled) {
        ExpandableSettingsSection("Normal EcoFlow checks", samplingSummary(settings.normalSeconds)) {
            SamplingIntervalEditor(settings.normalSeconds, 3600) { onChange(settings.copy(normalSeconds = it)) }
        }
        ExpandableSettingsSection("Outage EcoFlow checks", samplingSummary(settings.outageSeconds)) {
            SamplingIntervalEditor(settings.outageSeconds, 60) { onChange(settings.copy(outageSeconds = it)) }
            Text("Starts when charger power is lost or EcoFlow reports a possible outage. Continues until the charger reconnects and restoration finishes, even if EcoFlow has already reported grid recovery.", style = MaterialTheme.typography.bodySmall)
        }
        ExpandableSettingsSection("Traffic and live data", "One session; fewer requests; unofficial access") {
            Text("Sessions and broker credentials are reused; changing intervals does not log in again. Normal requests default to hourly and outage requests to once a minute. Manual-only stops scheduled reading requests for that phase; use Check EcoFlow now on Status. A secure connection can still receive device pushes and send keepalives.")
            Text("Every assisted check sends one temporary live-report activation plus one reading request. Live reporting is automatic in this mode; EcoFlow’s app does not need to stay open. It does not run a separate 20-second activation loop in this mode. Short intervals increase traffic; EcoFlow has not confirmed permitted quotas.")
            Text("The same device-push grid/meter comparison is used as in the live inspection. Cached request replies cannot prove a new grid transition. Missing or stale evidence stays Unknown. Reconnect the charger to rearm local detection.")
        }
    }
}

internal fun samplingSummary(seconds: Int) = if (seconds == 0) "Manual only" else "Every ${formatCustomDelay(seconds * 1000L)}"

@Composable
private fun SamplingIntervalEditor(seconds: Int, defaultSeconds: Int, onSave: (Int) -> Unit) {
    SettingSwitch("Manual checks only", "No scheduled reading requests in this phase.", seconds == 0,
        { onSave(if (it) 0 else defaultSeconds) })
    if (seconds == 0) return
    var unit by remember(seconds) { mutableIntStateOf(if (seconds % 3600 == 0) 3600 else if (seconds % 60 == 0) 60 else 1) }
    var quantity by remember(seconds) { mutableStateOf((seconds / unit).toString()) }
    OutlinedTextField(quantity, { quantity = it.take(8) }, label = { Text("Interval") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(1 to "Seconds", 60 to "Minutes", 3600 to "Hours").forEach { (value, title) ->
            FilterChip(unit == value, { unit = value }, label = { Text(title) })
        }
    }
    val value = quantity.toLongOrNull()?.let { it * unit }?.takeIf { it in 5..86_400 }?.toInt()
    Text("Choose 5 seconds to 24 hours. Intervals below one minute create substantially more traffic.", style = MaterialTheme.typography.bodySmall)
    Button({ value?.let(onSave) }, enabled = value != null && value != seconds, modifier = Modifier.fillMaxWidth()) { Text("Save interval") }
}
