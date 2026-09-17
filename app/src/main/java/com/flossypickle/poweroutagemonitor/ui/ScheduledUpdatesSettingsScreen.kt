package com.flossypickle.poweroutagemonitor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flossypickle.poweroutagemonitor.integrations.alerts.ScheduledAlertStore

private enum class DurationUnit(val label: String, val milliseconds: Long) {
    MINUTES("Minutes", 60_000L),
    HOURS("Hours", 60 * 60_000L),
    DAYS("Days", 24 * 60 * 60_000L)
}

/** Independent controls for messages generated on a schedule rather than a transition. */
@Composable
internal fun ScheduledUpdatesSettingsContent(
    settings: ScheduledAlertStore.Settings,
    onChange: (ScheduledAlertStore.Settings) -> Unit
) {
    var expandedEditor by rememberSaveable { mutableStateOf<String?>(null) }
    ExpandableSettingsSection("Source unavailable", if (settings.sourceUnavailableEnabled) "Enabled · tap to adjust" else "Off · tap to configure") {
        SettingSwitch(
            title = "Power source unavailable",
            explanation = "Notify contacts when the selected grid source cannot provide a trustworthy reading for long enough.",
            checked = settings.sourceUnavailableEnabled,
            onCheckedChange = { onChange(settings.copy(sourceUnavailableEnabled = it)) }
        )
        Text(
            "This warns about a monitoring problem. It never declares a grid outage. A recovery message follows only after an unavailable warning was queued.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
        if (settings.sourceUnavailableEnabled) {
            SettingText("Notify after", formatCustomDelay(settings.sourceUnavailableDelayMs))
            IntervalEditorToggle(
                expanded = expandedEditor == "source",
                onClick = {
                    expandedEditor = if (expandedEditor == "source") null else "source"
                }
            )
            if (expandedEditor == "source") {
                DurationEditor(
                    selectedMs = settings.sourceUnavailableDelayMs,
                    presets = SOURCE_PRESETS,
                    onSelect = { onChange(settings.copy(sourceUnavailableDelayMs = it)) }
                )
            }
        }
    }

    ExpandableSettingsSection("Monitor heartbeat", if (settings.heartbeatEnabled) "Enabled · tap to adjust" else "Off · tap to configure") {
        SettingSwitch(
            title = "Monitor heartbeat",
            explanation = "Send a periodic message proving that the app and at least one alert route are still working.",
            checked = settings.heartbeatEnabled,
            onCheckedChange = { onChange(settings.copy(heartbeatEnabled = it)) }
        )
        Text(
            "The first heartbeat is sent after a complete interval. Every enabled alert channel receives it; normal SMS charges may apply.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
        if (settings.heartbeatEnabled) {
            SettingText("Send every", formatCustomDelay(settings.heartbeatIntervalMs))
            IntervalEditorToggle(
                expanded = expandedEditor == "heartbeat",
                onClick = {
                    expandedEditor = if (expandedEditor == "heartbeat") null else "heartbeat"
                }
            )
            if (expandedEditor == "heartbeat") {
                DurationEditor(
                    selectedMs = settings.heartbeatIntervalMs,
                    presets = HEARTBEAT_PRESETS,
                    onSelect = { onChange(settings.copy(heartbeatIntervalMs = it)) }
                )
            }
        }
    }

    ExpandableSettingsSection("Long outage updates", if (settings.outageUpdatesEnabled) "Enabled · tap to adjust" else "Off · tap to configure") {
        SettingSwitch(
            title = "Long-outage updates",
            explanation = "While a confirmed outage remains open, periodically tell contacts that it is still active.",
            checked = settings.outageUpdatesEnabled,
            onCheckedChange = { onChange(settings.copy(outageUpdatesEnabled = it)) }
        )
        Text(
            "The first update waits for a complete interval after outage confirmation. The normal outage alert is still sent immediately after its confirmation delay.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
        if (settings.outageUpdatesEnabled) {
            SettingText("Repeat every", formatCustomDelay(settings.outageUpdateIntervalMs))
            IntervalEditorToggle(
                expanded = expandedEditor == "outage",
                onClick = {
                    expandedEditor = if (expandedEditor == "outage") null else "outage"
                }
            )
            if (expandedEditor == "outage") {
                DurationEditor(
                    selectedMs = settings.outageUpdateIntervalMs,
                    presets = OUTAGE_UPDATE_PRESETS,
                    onSelect = { onChange(settings.copy(outageUpdateIntervalMs = it)) }
                )
            }
        }
    }
}

@Composable
private fun IntervalEditorToggle(expanded: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier) {
        Text(if (expanded) "Done" else "Change interval")
    }
}

@Composable
private fun DurationEditor(
    selectedMs: Long,
    presets: List<Pair<Long, String>>,
    onSelect: (Long) -> Unit
) {
    presets.forEach { (duration, label) ->
        Row(
            Modifier.fillMaxWidth().clickable { onSelect(duration) }.padding(vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selectedMs == duration, onClick = { onSelect(duration) })
            Text(label)
        }
    }
    val initialUnit = preferredUnit(selectedMs)
    var customValue by remember(selectedMs) {
        mutableStateOf((selectedMs / initialUnit.milliseconds).coerceAtLeast(1).toString())
    }
    var customUnit by remember(selectedMs) { mutableStateOf(initialUnit) }
    OutlinedTextField(
        value = customValue,
        onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) customValue = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Custom interval") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true
    )
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        DurationUnit.entries.forEach { unit ->
            Row(
                Modifier.clickable { customUnit = unit },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = customUnit == unit, onClick = { customUnit = unit })
                Text(unit.label, fontSize = 12.sp)
            }
        }
    }
    val customMs = customValue.toLongOrNull()?.let { value ->
        runCatching { Math.multiplyExact(value, customUnit.milliseconds) }.getOrNull()
    }
    Button(
        onClick = { customMs?.let(onSelect) },
        enabled = customMs in ScheduledAlertStore.INTERVAL_RANGE_MS,
        modifier = Modifier
    ) { Text("Save custom interval") }
    Text(
        "Custom range: 1 minute to 30 days",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp
    )
}

private fun preferredUnit(milliseconds: Long): DurationUnit = when {
    milliseconds % DurationUnit.DAYS.milliseconds == 0L -> DurationUnit.DAYS
    milliseconds % DurationUnit.HOURS.milliseconds == 0L -> DurationUnit.HOURS
    else -> DurationUnit.MINUTES
}

private val SOURCE_PRESETS = listOf(
    60_000L to "1 minute",
    5 * 60_000L to "5 minutes (recommended)",
    15 * 60_000L to "15 minutes",
    60 * 60_000L to "1 hour"
)

private val HEARTBEAT_PRESETS = listOf(
    6 * 60 * 60_000L to "6 hours",
    12 * 60 * 60_000L to "12 hours",
    24 * 60 * 60_000L to "1 day (recommended)",
    7 * 24 * 60 * 60_000L to "7 days"
)

private val OUTAGE_UPDATE_PRESETS = listOf(
    15 * 60_000L to "15 minutes",
    60 * 60_000L to "1 hour",
    6 * 60 * 60_000L to "6 hours (recommended)",
    12 * 60 * 60_000L to "12 hours",
    24 * 60 * 60_000L to "1 day"
)
