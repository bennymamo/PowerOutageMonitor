package com.flossypickle.poweroutagemonitor.integrations.power.ecoflow

import com.flossypickle.poweroutagemonitor.integrations.power.GridAvailability

/** Charger corroborates loss only; its continued absence must never block restoration. */
internal object PowerOceanLossConfirmation {
    enum class Reason { UNKNOWN, WAITING_FOR_LIVE_DATA, CONNECTED, RETURN_PENDING, ECOFLOW_AND_METER, CHARGER_CORROBORATED, WAITING_FOR_CHARGER }
    data class Result(val availability: GridAvailability, val reason: Reason)

    /** A new meter push or changing feed supports an unchanged code without altering its observation time. */
    fun evidenceReceivedAt(grid: PowerOceanGridCorrelation.Snapshot, live: PowerOceanLiveCheck.Status?,
        now: Long, minimumPowerReports: Int, connectedCodeFromDevicePush: Boolean = true,
        liveMeterReceivedAt: Long? = null): Long? {
        if (grid.state == PowerOceanGridCorrelation.State.INVERTER_CONNECTED && grid.currentMeterValue != null &&
            grid.currentMeterValue != 0.0 && live != null && live.hasCurrentReport(now)) {
            // Genuine fresh non-zero meter activity remains valid when household watts are steady.
            if (liveMeterReceivedAt != null && live.requestedAt != null && liveMeterReceivedAt > live.requestedAt &&
                liveMeterReceivedAt <= now && now - liveMeterReceivedAt <= 90_000) return liveMeterReceivedAt
            if (live.valuesChanged && live.powerUpdates >= minimumPowerReports) return live.lastDevicePushAt
        }
        return if (grid.state == PowerOceanGridCorrelation.State.INVERTER_CONNECTED && !connectedCodeFromDevicePush) null
            else grid.evidenceReceivedAtUtcMillis
    }

    fun evaluate(grid: PowerOceanGridCorrelation.Snapshot, chargerExternallyPowered: Boolean?, requireCharger: Boolean, liveDataVerified: Boolean = true): Result =
        if (!liveDataVerified) Result(GridAvailability.UNKNOWN, Reason.WAITING_FOR_LIVE_DATA) else when (grid.state) {
            PowerOceanGridCorrelation.State.UNKNOWN -> Result(GridAvailability.UNKNOWN, Reason.UNKNOWN)
            PowerOceanGridCorrelation.State.INVERTER_CONNECTED -> Result(GridAvailability.AVAILABLE, Reason.CONNECTED)
            PowerOceanGridCorrelation.State.GRID_RETURN_LIKELY -> Result(GridAvailability.AVAILABLE, Reason.RETURN_PENDING)
            PowerOceanGridCorrelation.State.INVERTER_OFF_GRID -> when {
                grid.currentMeterValue != 0.0 -> Result(GridAvailability.UNKNOWN, Reason.UNKNOWN)
                !requireCharger -> Result(GridAvailability.UNAVAILABLE, Reason.ECOFLOW_AND_METER)
                chargerExternallyPowered == false -> Result(GridAvailability.UNAVAILABLE, Reason.CHARGER_CORROBORATED)
                else -> Result(GridAvailability.UNKNOWN, Reason.WAITING_FOR_CHARGER)
            }
        }
}
