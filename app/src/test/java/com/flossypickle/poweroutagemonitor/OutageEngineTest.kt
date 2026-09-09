package com.flossypickle.poweroutagemonitor

import org.junit.Assert.assertEquals
import org.junit.Test
import com.flossypickle.poweroutagemonitor.OutageEngine.Phase
import com.flossypickle.poweroutagemonitor.OutageEngine.State

class OutageEngineTest {
    private fun step(s: State, power: Boolean?, time: Long) = OutageEngine.update(s, power, time, 60, 30)
    @Test fun waitsForFirstConnection() {
        assertEquals(Phase.WAITING, step(State(), false, 10).phase)
        assertEquals(Phase.POWERED, step(State(), true, 10).phase)
    }
    @Test fun confirmsOnlyAtDeadline() {
        val pending = step(State(Phase.POWERED), false, 10)
        assertEquals(Phase.PENDING_OUTAGE, step(pending, false, 69).phase)
        assertEquals(Phase.OUTAGE, step(pending, false, 70).phase)
    }
    @Test fun briefInterruptionCancels() {
        val pending = step(State(Phase.POWERED), false, 10)
        assertEquals(Phase.POWERED, step(pending, true, 40).phase)
    }
    @Test fun restorationMustRemainStable() {
        val restoring = step(State(Phase.OUTAGE), true, 100)
        assertEquals(Phase.PENDING_RESTORE, step(restoring, true, 129).phase)
        assertEquals(Phase.POWERED, step(restoring, true, 130).phase)
        assertEquals(Phase.OUTAGE, step(restoring, false, 120).phase)
    }
    @Test fun unknownDoesNotCreateTransition() {
        val state = State(Phase.POWERED)
        assertEquals(state, step(state, null, 100))
    }
    @Test fun zeroDelayConfirmsImmediately() {
        assertEquals(Phase.OUTAGE, OutageEngine.update(State(Phase.POWERED), false, 1, 0, 0).phase)
    }
}
