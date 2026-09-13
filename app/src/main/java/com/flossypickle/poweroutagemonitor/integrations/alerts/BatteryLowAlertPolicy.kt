package com.flossypickle.poweroutagemonitor.integrations.alerts

import com.flossypickle.poweroutagemonitor.OutageEngine

/** Pure rule for the optional once-per-outage device-battery warning. */
internal object BatteryLowAlertPolicy {
    fun shouldSend(
        state: OutageEngine.State,
        externallyPowered: Boolean?,
        batteryPercent: Int?,
        enabled: Boolean,
        threshold: Int,
        alertedOutageStartedEpochMs: Long?
    ): Boolean {
        val outageStarted = state.outageStartedEpochMs ?: return false
        return enabled &&
            state.phase == OutageEngine.Phase.OUTAGE &&
            externallyPowered == false &&
            batteryPercent != null &&
            batteryPercent <= threshold &&
            alertedOutageStartedEpochMs != outageStarted
    }
}
