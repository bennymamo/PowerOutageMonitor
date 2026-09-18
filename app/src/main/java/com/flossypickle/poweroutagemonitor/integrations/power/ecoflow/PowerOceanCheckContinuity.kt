package com.flossypickle.poweroutagemonitor.integrations.power.ecoflow

import com.flossypickle.poweroutagemonitor.integrations.power.*

/** A planned check interval is not a source failure. Keep the original evidence receipt time. */
internal object PowerOceanCheckContinuity {
    /** A later partial packet cannot discard qualified evidence from this same check. */
    fun completedEvidence(latest: PowerSignal?, verified: PowerSignal?, cycleStartedAt: Long, failed: Boolean): PowerSignal? {
        val check = latest?.check ?: return null
        // The probe starts its reading request after the connection has opened.
        val requestedAt = check.requestedAtEpochMs
        if (failed || requestedAt < cycleStartedAt ||
            check.observations.any { it.label == "Reported grid code" && it.value !in setOf("0", "1") }) return null
        val qualified = listOfNotNull(latest, verified).firstOrNull {
            it.check?.requestedAtEpochMs == requestedAt && it.check.gridEvidenceAvailable &&
                it.availability != GridAvailability.UNKNOWN &&
                it.evidenceReceivedAtEpochMs?.let { receipt -> receipt > requestedAt } == true
        } ?: return null
        return qualified.copy(check = check.copy(gridEvidenceAvailable = true,
            ecoFlowAvailability = qualified.availability))
    }

    fun intervalDuringCheck(previous: PowerSignal?, check: PowerSourceCheck?, verifiedInterval: Int?, configuredInterval: Int?): Int? =
        if (check?.active == true && previous != null && check.requestedAtEpochMs != previous.check?.requestedAtEpochMs)
            verifiedInterval else configuredInterval

    fun availability(previous: PowerSignal?, check: PowerSourceCheck?, now: Long, windowMs: Long, intervalSeconds: Int?): GridAvailability {
        val evidence = previous?.evidenceReceivedAtEpochMs ?: return GridAvailability.UNKNOWN
        if (previous.availability == GridAvailability.UNKNOWN || evidence !in 1..now || check == null)
            return GridAvailability.UNKNOWN
        val validForMs = ((intervalSeconds ?: 0) + 60) * 1000L
        if (now - evidence > validForMs) return GridAvailability.UNKNOWN
        val usable = when {
            check.active -> (check.requestedAtEpochMs >= evidence ||
                check.requestedAtEpochMs == previous.check?.requestedAtEpochMs) &&
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
