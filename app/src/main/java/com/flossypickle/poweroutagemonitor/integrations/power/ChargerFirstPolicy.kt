package com.flossypickle.poweroutagemonitor.integrations.power

/** Local charger alerts survive cloud failure; only new grid evidence can resolve its loss. */
internal object ChargerFirstPolicy {
    data class Result(val availability: GridAvailability, val recovered: Boolean = false,
        val recoveryPending: Boolean = false, val detail: String)

    fun evaluate(chargerPowered: Boolean?, ecoFlow: PowerSignal?, lossStartedAt: Long,
        outageConfirmed: Boolean, recovered: Boolean, now: Long): Result {
        if (chargerPowered == true) return Result(GridAvailability.AVAILABLE,
            detail = "Charger watcher active. " + (ecoFlow?.detail ?: "EcoFlow assistance is waiting for a check."))
        if (chargerPowered == null) return Result(GridAvailability.UNKNOWN, recovered,
            detail = "Android charger state is unavailable.")
        val fresh = ecoFlow?.takeIf { PowerSignalPolicy.evaluate(it, now, 15_000).health == PowerSignalHealth.FRESH }
        val newRecovery = fresh?.availability == GridAvailability.AVAILABLE &&
            fresh.evidenceReceivedAtEpochMs?.let { it > lossStartedAt && it <= now } == true
        if (newRecovery && (outageConfirmed || recovered)) return Result(GridAvailability.AVAILABLE, true,
            fresh!!.recoveryPending, "Charger is off. " + (fresh.detail ?: "EcoFlow reports grid recovery."))
        if (recovered && fresh?.availability != GridAvailability.UNAVAILABLE) return Result(GridAvailability.UNKNOWN, true,
            detail = "Charger is still off; waiting for fresh EcoFlow evidence. Reconnect the charger to rearm local watching.")
        return Result(GridAvailability.UNAVAILABLE,
            detail = "Charger power lost. " + (ecoFlow?.detail ?: "EcoFlow assistance unavailable; local alerts still work."))
    }
}
