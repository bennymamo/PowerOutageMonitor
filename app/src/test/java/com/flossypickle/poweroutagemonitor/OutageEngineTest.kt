package com.flossypickle.poweroutagemonitor

import com.flossypickle.poweroutagemonitor.OutageEngine.Phase
import com.flossypickle.poweroutagemonitor.OutageEngine.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutageEngineTest {
    private fun step(
        state: State,
        power: Boolean?,
        time: Long,
        battery: Int? = 90,
        temperature: Int? = 250
    ) = OutageEngine.update(
        state,
        power,
        time,
        battery,
        outageDelayMs = 60,
        restoreDelayMs = 30,
        batteryTemperatureTenthsCelsius = temperature
    )

    @Test fun waitsForFirstConnection() {
        assertEquals(Phase.WAITING, step(State(), false, 10).phase)
        assertEquals(Phase.POWERED, step(State(), true, 10).phase)
    }

    @Test fun confirmsOnlyAtDeadlineAndRetainsLossData() {
        val pending = step(State(Phase.POWERED), false, 10, 93, 287)
        assertEquals(Phase.PENDING_OUTAGE, step(pending, false, 69).phase)
        val confirmed = step(pending, false, 70)
        assertEquals(Phase.OUTAGE, confirmed.phase)
        assertEquals(10L, confirmed.outageStartedEpochMs)
        assertEquals(93, confirmed.outageStartBatteryPercent)
        assertEquals(287, confirmed.outageStartBatteryTemperatureTenthsCelsius)
        assertEquals(70L, confirmed.confirmedAtEpochMs)
    }

    @Test fun briefInterruptionCancels() {
        val pending = step(State(Phase.POWERED), false, 10)
        val powered = step(pending, true, 40)
        assertEquals(Phase.POWERED, powered.phase)
        assertNull(powered.outageStartedEpochMs)
    }

    @Test fun restorationMustRemainStable() {
        val outage = State(Phase.OUTAGE, 70, 10, 90, 70)
        val restoring = step(outage, true, 100)
        assertEquals(Phase.PENDING_RESTORE, step(restoring, true, 129).phase)
        assertEquals(Phase.POWERED, step(restoring, true, 130).phase)
        assertEquals(Phase.OUTAGE, step(restoring, false, 120).phase)
    }

    @Test fun unknownDoesNotCreateTransition() {
        val state = State(Phase.POWERED)
        assertEquals(state, step(state, null, 100))
    }

    @Test fun zeroDelayConfirmsAndRestoresImmediately() {
        val outage = OutageEngine.update(State(Phase.POWERED), false, 1, 80, 0, 0)
        assertEquals(Phase.OUTAGE, outage.phase)
        assertEquals(Phase.POWERED, OutageEngine.update(outage, true, 2, 79, 0, 0).phase)
    }

    @Test fun exposesOnlyPendingDeadline() {
        assertEquals(70L, OutageEngine.deadlineEpochMs(State(Phase.PENDING_OUTAGE, 10), 60, 30))
        assertEquals(130L, OutageEngine.deadlineEpochMs(State(Phase.PENDING_RESTORE, 100), 60, 30))
        assertNull(OutageEngine.deadlineEpochMs(State(Phase.POWERED), 60, 30))
    }
}
