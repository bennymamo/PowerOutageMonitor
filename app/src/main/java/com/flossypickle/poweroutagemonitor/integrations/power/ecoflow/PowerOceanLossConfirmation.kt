package com.flossypickle.poweroutagemonitor.integrations.power.ecoflow

import com.flossypickle.poweroutagemonitor.integrations.power.GridAvailability

/** Charger corroborates loss only; its continued absence must never block restoration. */
internal object PowerOceanLossConfirmation {
    enum class Reason { UNKNOWN, WAITING_FOR_LIVE_DATA, CONNECTED, RETURN_PENDING, ECOFLOW_AND_METER, CHARGER_CORROBORATED, WAITING_FOR_CHARGER }
    data class Result(val availability: GridAvailability, val reason: Reason)

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
