package com.flossypickle.poweroutagemonitor.integrations.power

import java.util.Locale

/** Receipt age and origin are explicit; receipt is not a manufacturer measurement timestamp. */
internal object SourceEvidencePresentation {
    fun value(observation: SourceReportedValue): String = if (observation.label == "Meter 1 reading")
        observation.value.toDoubleOrNull()?.takeIf(Double::isFinite)?.let { String.format(Locale.ROOT, "%.1f W", it) }
            ?: observation.value else observation.value

    fun age(receivedAt: Long, now: Long): String {
        val seconds = ((now - receivedAt).coerceAtLeast(0) / 1000)
        return when {
            seconds < 60 -> "${seconds}s ago"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s ago"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m ago"
        }
    }

    fun origin(observation: SourceReportedValue, check: PowerSourceCheck): String =
        (if (observation.receivedAtEpochMs > check.requestedAtEpochMs) "this check" else "earlier check") +
            " · " + if (observation.fromDevicePush) "device push" else "sampled reply"

    fun progress(check: PowerSourceCheck, now: Long): String {
        val seconds = (now - check.startedAtEpochMs).coerceAtLeast(0) / 1000
        val limit = check.deadlineAtEpochMs?.let { (it - check.startedAtEpochMs).coerceAtLeast(0) / 1000 }
        val stage = if (check.cycleState == PowerSourceCheck.CycleState.CONNECTING) "connecting" else "collecting"
        return "$stage for ${seconds}s" + (limit?.let { " · limit ${it}s" } ?: "") +
            if (check.deadlineAtEpochMs?.let { now > it } == true) " · overdue; recovery required" else ""
    }

    fun qualification(check: PowerSourceCheck, now: Long): String = when {
        !check.gridEvidenceAvailable -> "not qualified"
        check.evidenceValidUntilEpochMs?.let { now > it } == true -> "previously qualified; validity expired"
        check.active && check.liveReportAtEpochMs?.let { it > check.requestedAtEpochMs } != true -> "retained evidence"
        else -> "qualified"
    }
}

internal data class SourceBatteryReading(val percent: Int, val receivedAtEpochMs: Long, val fromDevicePush: Boolean) {
    init { require(percent in 0..100 && receivedAtEpochMs > 0) }
}
