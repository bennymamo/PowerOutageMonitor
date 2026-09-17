package com.flossypickle.poweroutagemonitor.integrations.power.ecoflow

import com.flossypickle.poweroutagemonitor.integrations.power.*

/** Excludes optional cloud assistance without disabling the independent local watcher. */
internal object PowerOceanAssistancePolicy {
    fun apply(signal: PowerSignal, paused: Boolean, ignoreUnchanged: Boolean): PowerSignal = when {
        paused -> signal.copy(availability = GridAvailability.UNKNOWN, recoveryPending = false,
            evidenceReceivedAtEpochMs = null, detail = "EcoFlow assistance paused. Charger monitoring continues.")
        ignoreUnchanged && signal.dataPossiblyStalled == true -> signal.copy(
            availability = GridAvailability.UNKNOWN, recoveryPending = false, evidenceReceivedAtEpochMs = null,
            detail = "Unchanged EcoFlow power readings excluded until they change. Charger monitoring continues.")
        else -> signal
    }
}
