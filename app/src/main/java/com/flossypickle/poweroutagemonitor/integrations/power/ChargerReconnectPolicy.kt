package com.flossypickle.poweroutagemonitor.integrations.power

import com.flossypickle.poweroutagemonitor.OutageEngine

/** A charger update is separate from a grid restoration. */
internal object ChargerReconnectPolicy {
    fun shouldNotify(beforePowered: Boolean?, powered: Boolean?, primary: PowerSignal?, lossAt: Long,
        phase: OutageEngine.Phase, now: Long, enabled: Boolean): Boolean = enabled &&
        beforePowered == false && powered == true && phase == OutageEngine.Phase.POWERED &&
        lossAt > 0 && primary?.evidenceReceivedAtEpochMs?.let { it in (lossAt + 1)..now } == true &&
        !primary.recoveryPending && PowerSignalPolicy.evaluate(primary, now, 15_000).availability == GridAvailability.AVAILABLE
}
