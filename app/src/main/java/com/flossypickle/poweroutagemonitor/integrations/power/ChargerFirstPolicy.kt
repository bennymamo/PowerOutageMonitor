package com.flossypickle.poweroutagemonitor.integrations.power

/** Either source can establish loss; a powered UPS charger cannot veto a verified grid outage. */
internal object ChargerFirstPolicy {
    data class Result(val availability: GridAvailability, val recovered: Boolean = false,
        val recoveryPending: Boolean = false, val detail: String, val ecoFlowOutageStartedAt: Long = 0)

    fun evaluate(chargerPowered: Boolean?, ecoFlow: PowerSignal?, lossStartedAt: Long,
        outageConfirmed: Boolean, recovered: Boolean, now: Long, ecoFlowOutageStartedAt: Long = 0): Result {
        val fresh = ecoFlow?.takeIf { PowerSignalPolicy.evaluate(it, now, 15_000).health == PowerSignalHealth.FRESH }
        val evidence = fresh?.evidenceReceivedAtEpochMs?.takeIf { it in 1..now }
        if (fresh?.availability == GridAvailability.UNAVAILABLE && evidence != null) {
            return Result(GridAvailability.UNAVAILABLE, detail = "EcoFlow grid and meter readings report an outage. " +
                if (chargerPowered == true) "The charger is still powered; backup power may be keeping it on." else "Local charger watching continues.",
                ecoFlowOutageStartedAt = ecoFlowOutageStartedAt.takeIf { it > 0 } ?: evidence)
        }
        val ecoReturned = ecoFlowOutageStartedAt > 0 && fresh?.availability == GridAvailability.AVAILABLE &&
            evidence != null && evidence > ecoFlowOutageStartedAt
        if (ecoReturned && (chargerPowered != false || outageConfirmed || recovered)) {
            return Result(GridAvailability.AVAILABLE, chargerPowered == false, fresh!!.recoveryPending,
                "EcoFlow reports grid recovery. " + (fresh.detail ?: "Checking grid stability."),
                ecoFlowOutageStartedAt = if (fresh.recoveryPending) ecoFlowOutageStartedAt else 0)
        }
        val ecoLoss = if (ecoReturned) 0 else ecoFlowOutageStartedAt
        if (ecoLoss > 0 && chargerPowered != false) return Result(GridAvailability.UNKNOWN, recovered,
            detail = "The EcoFlow grid episode is not fully resolved. A powered charger cannot prove grid stability; waiting for updated readings.",
            ecoFlowOutageStartedAt = ecoLoss)
        if (chargerPowered == true) return Result(GridAvailability.AVAILABLE,
            detail = "Charger watcher active. " + (ecoFlow?.detail ?: "EcoFlow assistance is waiting for a check."))
        if (chargerPowered == null) return Result(GridAvailability.UNKNOWN, recovered,
            detail = "Android charger state is unavailable.")
        val newRecovery = fresh?.availability == GridAvailability.AVAILABLE &&
            evidence?.let { it > lossStartedAt } == true
        if (newRecovery && (outageConfirmed || recovered)) return Result(GridAvailability.AVAILABLE, true,
            fresh!!.recoveryPending, "Charger is off. " + (fresh.detail ?: "EcoFlow reports grid recovery."))
        if (recovered && fresh?.availability != GridAvailability.UNAVAILABLE) return Result(GridAvailability.UNKNOWN, true,
            detail = "Charger is still off; waiting for updated EcoFlow readings. Reconnect the charger to rearm local watching.",
            ecoFlowOutageStartedAt = ecoLoss)
        return Result(GridAvailability.UNAVAILABLE,
            detail = "Charger power lost. " + (ecoFlow?.detail ?: "EcoFlow assistance unavailable; local alerts still work."),
            ecoFlowOutageStartedAt = ecoLoss)
    }
}
