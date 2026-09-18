package com.flossypickle.poweroutagemonitor.integrations.power

import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.PowerOceanCheckContinuity

/** A delayed screen refresh does not invalidate evidence during a bounded EcoFlow check. */
internal object DashboardSourceReadingPolicy {
    private fun evidenceInterval(status: PowerSourceStore.Status, configured: Int?): Int? {
        val check = status.check
        val receipt = status.evidenceReceivedAtEpochMs
        val until = check?.evidenceValidUntilEpochMs
        return if (receipt != null && until != null && until >= receipt + 60_000)
            ((until - receipt) / 1000 - 60).toInt() else configured
    }

    fun previousOnlineDuringCheck(status: PowerSourceStore.Status?, now: Long,
        intervalSeconds: Int?, windowMs: Long): Boolean {
        val check = status?.check ?: return false
        val onlineAt = check.lastConfirmedOnlineAtEpochMs ?: return false
        if (status.source != PowerSourceStore.Source.ECOFLOW_ACCOUNT || !check.active ||
            check.gridEvidenceAvailable || status.availability == GridAvailability.UNAVAILABLE ||
            onlineAt != status.evidenceReceivedAtEpochMs) return false
        val previous = PowerSignal(GridAvailability.AVAILABLE, status.observedAtEpochMs,
            PowerSourceStore.POWEROCEAN_PROVIDER_ID, evidenceReceivedAtEpochMs = onlineAt, check = check)
        return PowerOceanCheckContinuity.availability(previous, check, now, windowMs, evidenceInterval(status, intervalSeconds)) == GridAvailability.AVAILABLE
    }

    fun isCurrent(status: PowerSourceStore.Status?, selected: PowerSourceStore.Source, now: Long,
        intervalSeconds: Int?, windowMs: Long): Boolean {
        if (status == null || status.source != selected || status.observedAtEpochMs > now) return false
        if (now - status.observedAtEpochMs <= 15_000) return true
        if (selected != PowerSourceStore.Source.ECOFLOW_ACCOUNT || status.check?.active != true) return false
        val signal = PowerSignal(status.availability, status.observedAtEpochMs,
            PowerSourceStore.POWEROCEAN_PROVIDER_ID, evidenceReceivedAtEpochMs = status.evidenceReceivedAtEpochMs,
            check = status.check)
        return PowerOceanCheckContinuity.availability(signal, status.check, now, windowMs, evidenceInterval(status, intervalSeconds)) != GridAvailability.UNKNOWN
    }
}
