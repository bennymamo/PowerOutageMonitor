package com.flossypickle.poweroutagemonitor.integrations.alerts

import com.flossypickle.poweroutagemonitor.integrations.power.*

/** Follow-up for a restoration previously based on meter activity with code 1 still present. */
internal object GridReconnectionPolicy {
    fun confirmed(status: PowerSourceStore.Status?, restoredAt: Long, now: Long): Boolean {
        val check = status?.check ?: return false
        val code = check.observations.firstOrNull { it.label == "Reported grid code" } ?: return false
        return status.availability == GridAvailability.AVAILABLE && !status.recoveryPending &&
            check.gridEvidenceAvailable && check.ecoFlowAvailability == GridAvailability.AVAILABLE && code.value == "0" &&
            code.receivedAtEpochMs > restoredAt && code.receivedAtEpochMs > check.requestedAtEpochMs &&
            check.liveReportAtEpochMs?.let { it > check.requestedAtEpochMs && now - it in 0..90_000 } == true
    }
}
