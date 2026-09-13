package com.flossypickle.poweroutagemonitor

import com.flossypickle.poweroutagemonitor.integrations.alerts.BatteryLowAlertPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryLowAlertPolicyTest {
    private val outage = OutageEngine.State(
        phase = OutageEngine.Phase.OUTAGE,
        phaseSinceEpochMs = 70_000L,
        outageStartedEpochMs = 10_000L,
        confirmedAtEpochMs = 70_000L
    )

    @Test fun `sends at threshold during confirmed outage`() {
        assertTrue(shouldSend(outage, powered = false, battery = 20))
    }

    @Test fun `sends below threshold`() {
        assertTrue(shouldSend(outage, powered = false, battery = 9))
    }

    @Test fun `does not send above threshold or with unknown battery`() {
        assertFalse(shouldSend(outage, powered = false, battery = 21))
        assertFalse(shouldSend(outage, powered = false, battery = null))
    }

    @Test fun `does not send before outage confirmation or while power is back`() {
        assertFalse(shouldSend(
            outage.copy(phase = OutageEngine.Phase.PENDING_OUTAGE),
            powered = false,
            battery = 20
        ))
        assertFalse(shouldSend(outage, powered = true, battery = 20))
    }

    @Test fun `disabled setting prevents alert`() {
        assertFalse(shouldSend(outage, powered = false, battery = 20, enabled = false))
    }

    @Test fun `persisted outage marker prevents a duplicate after restart`() {
        assertFalse(shouldSend(
            outage,
            powered = false,
            battery = 15,
            alertedOutage = outage.outageStartedEpochMs
        ))
        assertTrue(shouldSend(
            outage.copy(outageStartedEpochMs = 200_000L),
            powered = false,
            battery = 15,
            alertedOutage = outage.outageStartedEpochMs
        ))
    }

    private fun shouldSend(
        state: OutageEngine.State,
        powered: Boolean?,
        battery: Int?,
        enabled: Boolean = true,
        alertedOutage: Long? = null
    ) = BatteryLowAlertPolicy.shouldSend(
        state = state,
        externallyPowered = powered,
        batteryPercent = battery,
        enabled = enabled,
        threshold = 20,
        alertedOutageStartedEpochMs = alertedOutage
    )
}
