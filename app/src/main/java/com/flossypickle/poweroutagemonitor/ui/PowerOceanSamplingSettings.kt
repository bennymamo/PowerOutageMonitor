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
        SettingSwitch("Charger-first assistance", "Charger loss starts an EcoFlow check. Current grid-connected evidence cancels the suspected outage; failed or inconclusive checks fall back to the charger. EcoFlow can also detect loss while backup power keeps the charger on.", settings.enabled,
            { onChange(settings.copy(enabled = it)) })
        if (settings.enabled) Text("A charger that loses grid power gives the earliest local alert. If backup power keeps it on, EcoFlow can still detect an outage at its next check. A powered charger cannot clear an EcoFlow-detected outage.", style = MaterialTheme.typography.bodySmall)
    }
    SettingsCard {
        SettingSwitch("Notify when grid status is unknown", "Send an alert if the charger has no power and an EcoFlow check fails or cannot verify the grid. Quiet time still applies. Confirmed outage alerts are unaffected.", settings.notifyOnUnknown,
            { onChange(settings.copy(notifyOnUnknown = it)) })
        SettingSwitch("Notify when charger power returns", "Send a charger update when EcoFlow already confirmed the grid was online. This does not report a grid restoration.", settings.notifyOnChargerReturn,
            { onChange(settings.copy(notifyOnChargerReturn = it)) })
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
            Text("Starts when charger power is lost or EcoFlow reports a possible outage. Continues while the charger remains disconnected or an outage/recovery is still in progress.", style = MaterialTheme.typography.bodySmall)
        }
    }
        ExpandableSettingsSection("Check duration & updates", "Up to ${settings.checkWindowSeconds}s · ${settings.extraPowerUpdates} extra power reports") {
            Text("Listen to the first power report and extra reports to see whether values change. A check ends early when enough changing reports and usable grid/meter evidence arrive; otherwise it ends at the time limit. The connection is then closed. Missed schedule slots are skipped, so checks never overlap.", style = MaterialTheme.typography.bodySmall)
            var window by remember(settings.checkWindowSeconds) { mutableStateOf(settings.checkWindowSeconds.toString()) }
            var extra by remember(settings.extraPowerUpdates) { mutableStateOf(settings.extraPowerUpdates.toString()) }
            OutlinedTextField(window, { window = it.take(3) }, label = { Text("Maximum listening time · seconds") }, singleLine = true,
                supportingText = { Text("30–300 seconds; default 120") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(extra, { extra = it.take(2) }, label = { Text("Extra power reports") }, singleLine = true,
                supportingText = { Text("1–10; default 2 after the first report") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            val w = window.toIntOrNull(); val e = extra.toIntOrNull()
            Button({ if (w != null && e != null) onChange(settings.copy(checkWindowSeconds = w, extraPowerUpdates = e)) },
                enabled = w != null && w in 30..300 && e != null && e in 1..10 && (w != settings.checkWindowSeconds || e != settings.extraPowerUpdates)) { Text("Save check limits") }
        }
        ExpandableSettingsSection("Connection & live data", "Closed between checks; saved login reused") {
            Text("A verified grid reading remains valid for the current normal or outage interval plus one minute, measured from when the device report arrived. Failed or inconclusive checks become Unknown immediately. Manual-only readings expire after one minute.", style = MaterialTheme.typography.bodySmall)
            Text("Each check opens a secure connection, sends one reading request and activates temporary live reporting. Activation is renewed every 20 seconds only while that short check is collecting data. Default schedules are hourly normally and every minute during an outage. Manual-only leaves that phase disconnected until Check now. Very short intervals can leave little time between checks.", style = MaterialTheme.typography.bodySmall)
            Text("The saved login session and broker credentials are reused until access fails or account settings change. Closing the connection stops this app receiving updates; it does not control other EcoFlow apps. This is unofficial access and EcoFlow has not confirmed permitted quotas.", style = MaterialTheme.typography.bodySmall)
            Text("New changing device values support an unchanged grid code. Missing device data stays Unknown for EcoFlow decisions. Charger detection continues while the connection is closed, unavailable or paused.", style = MaterialTheme.typography.bodySmall)
        }
}

internal fun samplingSummary(seconds: Int) = if (seconds == 0) "Manual only" else "Every ${formatCustomDelay(seconds * 1000L)}"

@Composable
private fun SamplingIntervalEditor(seconds: Int, defaultSeconds: Int, onSave: (Int) -> Unit) {
    SettingSwitch("Manual checks only", "Stay disconnected until you choose Check now in this phase.", seconds == 0,
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
    Button({ value?.let(onSave) }, enabled = value != null && value != seconds, modifier = Modifier) { Text("Save interval") }
}
