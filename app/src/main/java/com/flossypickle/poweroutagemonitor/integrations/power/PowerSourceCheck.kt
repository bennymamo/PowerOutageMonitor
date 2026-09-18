package com.flossypickle.poweroutagemonitor.integrations.power

/** Non-secret request feedback, separate from grid availability and measurement freshness. */
internal data class PowerSourceCheck(val requestedAtEpochMs: Long, val liveReportAtEpochMs: Long?,
    val gridEvidenceAvailable: Boolean, val readings: List<SourceTelemetryReading> = emptyList(),
    val observations: List<SourceReportedValue> = emptyList(),
    val cycleState: CycleState? = null, val finishedAtEpochMs: Long? = null, val nextCheckAtEpochMs: Long? = null,
    val deviceUpdates: Int = 0, val powerUpdates: Int = 0, val valuesChanged: Boolean = false,
    val lastConfirmedOnlineAtEpochMs: Long? = null) {
    enum class CycleState { CONNECTING, COLLECTING, WAITING, PAUSED, FAILED }
    val active get() = cycleState in setOf(CycleState.CONNECTING, CycleState.COLLECTING)
    enum class DataHealth { CHANGING, UNCHANGED, NO_UPDATES }
    val dataHealth get() = when { valuesChanged -> DataHealth.CHANGING; deviceUpdates > 0 -> DataHealth.UNCHANGED; else -> DataHealth.NO_UPDATES }
    enum class Phase { CHECKING, LIVE_RECEIVED, GRID_VERIFIED, TIMED_OUT }
    fun phase(now: Long): Phase = when {
        active -> Phase.CHECKING
        finishedAtEpochMs != null && liveReportAtEpochMs == null -> Phase.TIMED_OUT
        liveReportAtEpochMs != null && liveReportAtEpochMs > requestedAtEpochMs ->
            if (gridEvidenceAvailable) Phase.GRID_VERIFIED else Phase.LIVE_RECEIVED
        now - requestedAtEpochMs >= 45_000 -> Phase.TIMED_OUT
        else -> Phase.CHECKING
    }
}

internal data class SourceReportedValue(val label: String, val value: String, val explanation: String,
    val receivedAtEpochMs: Long, val fromDevicePush: Boolean, val supportedByLiveFeed: Boolean = false)
