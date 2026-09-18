package com.flossypickle.poweroutagemonitor.integrations.alerts

import com.flossypickle.poweroutagemonitor.OutageEngine
import com.flossypickle.poweroutagemonitor.monitoring.PowerSnapshot
import com.flossypickle.poweroutagemonitor.storage.MonitorStore
import java.text.DateFormat
import java.util.Date
import kotlin.math.max

/** Creates provider-neutral user messages from confirmed state transitions. */
internal object AlertMessageFactory {
    fun testOutage(settings: MonitorStore.Settings, simulatedAtEpochMs: Long): AlertMessage =
        AlertMessage(
            eventId = "test-outage-$simulatedAtEpochMs",
            kind = AlertKind.TEST,
            title = "TEST · POWER OUTAGE DETECTED",
            body = buildString {
                appendLine("SIMULATION: no real outage was detected.")
                appendLine()
                appendLine("Device: ${settings.deviceName}")
                appendLine("Simulated power loss: ${formatTime(simulatedAtEpochMs)}")
                appendLine("Configured alert delay: ${formatDuration(settings.outageDelayMs)}")
                append("Test status: Grid power unavailable")
            }
        )

    fun testRestored(
        settings: MonitorStore.Settings,
        simulatedLostAtEpochMs: Long,
        simulatedRestoredAtEpochMs: Long
    ): AlertMessage = AlertMessage(
        eventId = "test-restored-$simulatedRestoredAtEpochMs",
        kind = AlertKind.TEST,
        title = "TEST · POWER RESTORED",
        body = buildString {
            appendLine("SIMULATION: no real restoration was detected.")
            appendLine()
            appendLine("Device: ${settings.deviceName}")
            appendLine("Simulated restoration: ${formatTime(simulatedRestoredAtEpochMs)}")
            append("Simulated outage duration: ${formatDuration(simulatedRestoredAtEpochMs - simulatedLostAtEpochMs)}")
        }
    )

    fun testBatteryLow(
        settings: MonitorStore.Settings,
        simulatedLostAtEpochMs: Long,
        simulatedAtEpochMs: Long,
        batteryPercent: Int = 20
    ): AlertMessage = AlertMessage(
        eventId = "test-battery-low-$simulatedAtEpochMs",
        kind = AlertKind.TEST,
        title = "TEST · MONITOR BATTERY LOW",
        body = buildString {
            appendLine("SIMULATION: the real device battery is unchanged.")
            appendLine()
            appendLine("Device: ${settings.deviceName}")
            appendLine("Simulated power loss: ${formatTime(simulatedLostAtEpochMs)}")
            appendLine("Simulated battery warning: ${formatTime(simulatedAtEpochMs)}")
            appendLine("Battery: $batteryPercent%")
            append("Test status: The grid outage is still active.")
        }
    )

    fun forTransition(
        before: OutageEngine.State,
        after: OutageEngine.State,
        snapshot: PowerSnapshot,
        settings: MonitorStore.Settings,
        nowEpochMs: Long
    ): AlertMessage? {
        val outageConfirmed = after.phase == OutageEngine.Phase.OUTAGE &&
            before.phase in setOf(OutageEngine.Phase.POWERED, OutageEngine.Phase.PENDING_OUTAGE)
        if (outageConfirmed) return outage(after, settings)

        val restored = after.phase == OutageEngine.Phase.POWERED &&
            before.phase in setOf(OutageEngine.Phase.OUTAGE, OutageEngine.Phase.PENDING_RESTORE)
        if (restored && settings.sendRestoreNotification) {
            return restored(before, snapshot, settings, nowEpochMs)
        }
        return null
    }

    fun batteryLowForObservation(
        state: OutageEngine.State,
        snapshot: PowerSnapshot,
        settings: MonitorStore.Settings,
        nowEpochMs: Long,
        alertedOutageStartedEpochMs: Long?
    ): AlertMessage? {
        if (!BatteryLowAlertPolicy.shouldSend(
                state = state,
                externallyPowered = snapshot.externallyPowered,
                batteryPercent = snapshot.batteryPercent,
                enabled = settings.batteryLowAlertEnabled,
                threshold = settings.batteryLowAlertThreshold,
                alertedOutageStartedEpochMs = alertedOutageStartedEpochMs
            )
        ) return null

        val lostAt = state.outageStartedEpochMs ?: return null
        return AlertMessage(
            eventId = eventId(lostAt),
            kind = AlertKind.BATTERY_LOW,
            title = "MONITOR BATTERY LOW",
            body = buildString {
                appendLine("Device: ${settings.deviceName}")
                appendLine("Power lost: ${formatTime(lostAt)}")
                appendLine("Battery warning: ${formatTime(nowEpochMs)}")
                appendLine("Battery: ${snapshot.batteryPercent}%")
                append("The grid outage is still active. This monitoring device may shut down soon.")
            }
        )
    }

    private fun outage(state: OutageEngine.State, settings: MonitorStore.Settings): AlertMessage {
        val lostAt = state.outageStartedEpochMs ?: state.phaseSinceEpochMs
        val confirmedAt = state.confirmedAtEpochMs ?: state.phaseSinceEpochMs
        return AlertMessage(
            eventId = eventId(lostAt),
            kind = AlertKind.OUTAGE,
            title = "POWER OUTAGE DETECTED",
            body = buildString {
                appendLine("Device: ${settings.deviceName}")
                appendLine("Power lost: ${formatTime(lostAt)}")
                appendLine("Confirmed: ${formatTime(confirmedAt)}")
                appendLine("Alert delay: ${formatDuration(settings.outageDelayMs)}")
                state.outageStartBatteryPercent?.let { appendLine("Battery at power loss: $it%") }
                append("The selected grid source reports that mains power is unavailable.")
            }
        )
    }

    private fun restored(
        state: OutageEngine.State,
        snapshot: PowerSnapshot,
        settings: MonitorStore.Settings,
        restoredAt: Long
    ): AlertMessage {
        val lostAt = state.outageStartedEpochMs ?: state.phaseSinceEpochMs
        return AlertMessage(
            eventId = eventId(lostAt),
            kind = AlertKind.RESTORED,
            title = "POWER RESTORED",
            body = buildString {
                appendLine("Device: ${settings.deviceName}")
                appendLine("Power lost: ${formatTime(lostAt)}")
                appendLine("Power restored: ${formatTime(restoredAt)}")
                appendLine("Outage duration: ${formatDuration(max(0, restoredAt - lostAt))}")
                val start = state.outageStartBatteryPercent
                val end = snapshot.batteryPercent
                if (start != null && end != null) appendLine("Battery: $start% to $end%")
                append("The selected grid source reports stable mains power again.")
            }
        )
    }

    private fun eventId(lostAt: Long) = "power-event-$lostAt"

    private fun formatTime(epochMs: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))

    internal fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs.coerceAtLeast(0) / 1_000L
        val hours = totalSeconds / 3_600
        val minutes = totalSeconds % 3_600 / 60
        val seconds = totalSeconds % 60
        return buildList {
            if (hours > 0) add("$hours h")
            if (minutes > 0) add("$minutes min")
            if (seconds > 0 || isEmpty()) add("$seconds sec")
        }.joinToString(" ")
    }
}
