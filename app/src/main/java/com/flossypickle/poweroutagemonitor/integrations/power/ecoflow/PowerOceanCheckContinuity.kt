package com.flossypickle.poweroutagemonitor.integrations.power.ecoflow

import com.flossypickle.poweroutagemonitor.integrations.power.*

/** A planned check interval is not a source failure. Keep the original evidence receipt time. */
internal object PowerOceanCheckContinuity {
    fun availability(previous: PowerSignal?, check: PowerSourceCheck?, now: Long, windowMs: Long, intervalSeconds: Int?): GridAvailability {
        val evidence = previous?.evidenceReceivedAtEpochMs ?: return GridAvailability.UNKNOWN
        if (previous.availability == GridAvailability.UNKNOWN || evidence !in 1..now || check == null)
            return GridAvailability.UNKNOWN
        val validForMs = ((intervalSeconds ?: 0) + 60) * 1000L
        if (now - evidence > validForMs) return GridAvailability.UNKNOWN
        val usable = when {
            check.active -> check.requestedAtEpochMs >= evidence &&
                now - check.requestedAtEpochMs in 0..(windowMs + 60_000)
            check.cycleState == PowerSourceCheck.CycleState.WAITING && check.gridEvidenceAvailable &&
                check.finishedAtEpochMs != null && now >= check.finishedAtEpochMs &&
                check.requestedAtEpochMs == previous.check?.requestedAtEpochMs ->
                true
            else -> false
        }
        return if (usable) previous.availability else GridAvailability.UNKNOWN
    }
}
