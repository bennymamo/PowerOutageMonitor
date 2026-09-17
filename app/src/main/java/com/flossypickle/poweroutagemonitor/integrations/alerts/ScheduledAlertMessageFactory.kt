package com.flossypickle.poweroutagemonitor.integrations.alerts

import com.flossypickle.poweroutagemonitor.OutageEngine
import com.flossypickle.poweroutagemonitor.integrations.power.PowerSourceStore
import com.flossypickle.poweroutagemonitor.monitoring.PowerSnapshot
import com.flossypickle.poweroutagemonitor.storage.MonitorStore
import java.text.DateFormat
import java.util.Date

internal object ScheduledAlertMessageFactory {
    fun create(
        notice: ScheduledAlertPolicy.Notice,
        monitorSettings: MonitorStore.Settings,
        monitorState: OutageEngine.State,
        snapshot: PowerSnapshot,
        selectedSource: PowerSourceStore.Source,
        sourceReadable: Boolean,
        nowEpochMs: Long
    ): AlertMessage = when (notice) {
        is ScheduledAlertPolicy.Notice.SourceUnavailable -> AlertMessage(
            eventId = "source-unavailable-${notice.sinceEpochMs}",
            kind = AlertKind.SOURCE_UNAVAILABLE,
            title = "POWER SOURCE UNAVAILABLE",
            body = buildString {
                appendLine("Device: ${monitorSettings.deviceName}")
                appendLine("Source: ${sourceName(selectedSource)}")
                appendLine("Unavailable since: ${formatTime(notice.sinceEpochMs)}")
                appendLine("Checked: ${formatTime(nowEpochMs)}")
                snapshot.batteryPercent?.let { appendLine("Monitor battery: $it%") }
                append("The app cannot currently determine grid state. This is not an outage confirmation.")
            }
        )
        is ScheduledAlertPolicy.Notice.SourceAvailableAgain -> AlertMessage(
            eventId = "source-unavailable-${notice.sinceEpochMs}",
            kind = AlertKind.SOURCE_RESTORED,
            title = "POWER SOURCE AVAILABLE AGAIN",
            body = buildString {
                appendLine("Device: ${monitorSettings.deviceName}")
                appendLine("Source: ${sourceName(selectedSource)}")
                appendLine("Reading restored: ${formatTime(nowEpochMs)}")
                appendLine("Unavailable for: ${AlertMessageFactory.formatDuration(nowEpochMs - notice.sinceEpochMs)}")
                append("Grid status: ${gridStatus(monitorState, sourceReadable)}")
            }
        )
        ScheduledAlertPolicy.Notice.Heartbeat -> AlertMessage(
            eventId = "heartbeat-$nowEpochMs",
            kind = AlertKind.HEARTBEAT,
            title = "MONITOR HEARTBEAT",
            body = buildString {
                appendLine("Device: ${monitorSettings.deviceName}")
                appendLine("Checked: ${formatTime(nowEpochMs)}")
                appendLine("Monitoring: Active")
                appendLine("Grid status: ${gridStatus(monitorState, sourceReadable)}")
                snapshot.batteryPercent?.let { append("Monitor battery: $it%") }
            }.trimEnd()
        )
        is ScheduledAlertPolicy.Notice.OutageUpdate -> AlertMessage(
            eventId = "outage-update-${notice.outageStartedEpochMs}-$nowEpochMs",
            kind = AlertKind.OUTAGE_UPDATE,
            title = "POWER OUTAGE STILL ACTIVE",
            body = buildString {
                appendLine("Device: ${monitorSettings.deviceName}")
                appendLine("Power lost: ${formatTime(notice.outageStartedEpochMs)}")
                appendLine("Update: ${formatTime(nowEpochMs)}")
                appendLine("Outage duration: ${AlertMessageFactory.formatDuration(nowEpochMs - notice.outageStartedEpochMs)}")
                snapshot.batteryPercent?.let { appendLine("Monitor battery: $it%") }
                append("The confirmed outage is still open.")
            }
        )
    }

    private fun sourceName(source: PowerSourceStore.Source) = when (source) {
        PowerSourceStore.Source.ANDROID_CHARGER -> "Android charger"
        PowerSourceStore.Source.ECOFLOW_MODBUS -> "EcoFlow local"
        PowerSourceStore.Source.ECOFLOW_ACCOUNT -> "PowerOcean account (experimental)"
    }

    private fun gridStatus(state: OutageEngine.State, sourceReadable: Boolean): String = when {
        !sourceReadable -> "Source unavailable"
        state.phase == OutageEngine.Phase.POWERED -> "Grid power online"
        state.phase == OutageEngine.Phase.PENDING_OUTAGE -> "Possible outage being checked"
        state.phase == OutageEngine.Phase.OUTAGE -> "Outage confirmed"
        state.phase == OutageEngine.Phase.PENDING_RESTORE -> "Restoration being checked"
        else -> "Waiting to arm"
    }

    private fun formatTime(epochMs: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))
}
