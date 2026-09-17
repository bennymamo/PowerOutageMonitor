package com.flossypickle.poweroutagemonitor.integrations.power.ecoflow

/** Bounded listening: enough changing power reports, or a hard maximum. No overlapping sessions. */
internal class PowerOceanCheckCyclePolicy(val maximumSeconds: Int = 120, val extraUpdates: Int = 2) {
    init { require(maximumSeconds in 30..300 && extraUpdates in 1..10) }
    fun enough(status: PowerOceanLiveCheck.Status, gridAndMeterReceived: Boolean): Boolean =
        gridAndMeterReceived && status.powerUpdates >= extraUpdates + 1 && status.valuesChanged
}
