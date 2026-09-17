package com.flossypickle.poweroutagemonitor.integrations.power

/** Non-secret request feedback, separate from grid availability and measurement freshness. */
internal data class PowerSourceCheck(val requestedAtEpochMs: Long, val liveReportAtEpochMs: Long?,
    val gridEvidenceAvailable: Boolean, val readings: List<SourceTelemetryReading> = emptyList(),
    val observations: List<SourceReportedValue> = emptyList()) {
    enum class Phase { CHECKING, LIVE_RECEIVED, GRID_VERIFIED, TIMED_OUT }
    fun phase(now: Long): Phase = when {
        liveReportAtEpochMs != null && liveReportAtEpochMs > requestedAtEpochMs ->
            if (gridEvidenceAvailable) Phase.GRID_VERIFIED else Phase.LIVE_RECEIVED
        now - requestedAtEpochMs >= 45_000 -> Phase.TIMED_OUT
        else -> Phase.CHECKING
    }
}

internal data class SourceReportedValue(val label: String, val value: String, val explanation: String,
    val receivedAtEpochMs: Long, val fromDevicePush: Boolean, val supportedByLiveFeed: Boolean = false)
