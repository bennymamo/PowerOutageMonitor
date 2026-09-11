package com.flossypickle.poweroutagemonitor

import com.flossypickle.poweroutagemonitor.audible.AudibleAlarmEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudibleAlarmEngineTest {
    private val config = AudibleAlarmEngine.Config(true, 300_000L, 20)

    @Test fun `first confirmed outage sounds and schedules a repeat`() {
        val decision = evaluate(outageId = 100L, now = 200L)

        assertTrue(decision.playNow)
        assertEquals(300_200L, decision.nextAlarmAtEpochMs)
        assertEquals(100L, decision.runtime.activeOutageId)
    }

    @Test fun `ordinary readings do not repeat the alarm early`() {
        val runtime = AudibleAlarmEngine.Runtime(100L, null, 200L)
        val decision = evaluate(outageId = 100L, now = 1_000L, runtime = runtime)

        assertFalse(decision.playNow)
        assertEquals(300_200L, decision.nextAlarmAtEpochMs)
    }

    @Test fun `scheduled tick sounds only when due`() {
        val runtime = AudibleAlarmEngine.Runtime(100L, null, 200L)

        assertFalse(evaluate(100L, 300_199L, runtime, scheduled = true).playNow)
        assertTrue(evaluate(100L, 300_200L, runtime, scheduled = true).playNow)
    }

    @Test fun `dismissal remains attached to the current outage`() {
        val runtime = AudibleAlarmEngine.Runtime(100L, 100L, null)
        val decision = evaluate(100L, 500L, runtime, scheduled = true)

        assertFalse(decision.playNow)
        assertNull(decision.nextAlarmAtEpochMs)
        assertEquals(100L, decision.runtime.dismissedOutageId)
    }

    @Test fun `battery threshold silences the rest of the outage`() {
        val decision = evaluate(100L, 500L, battery = 20)

        assertFalse(decision.playNow)
        assertEquals(100L, decision.runtime.dismissedOutageId)
    }

    @Test fun `power return cancels repeats but preserves a dismissal during unstable restore`() {
        val runtime = AudibleAlarmEngine.Runtime(100L, 100L, 200L)
        val decision = AudibleAlarmEngine.evaluate(
            config, true, false, 100L, 80, runtime, 500L
        )

        assertNull(decision.runtime.activeOutageId)
        assertEquals(100L, decision.runtime.dismissedOutageId)
        assertNull(decision.nextAlarmAtEpochMs)
    }

    @Test fun `completed outage clears old dismissal for the next outage`() {
        val completed = AudibleAlarmEngine.evaluate(
            config, true, false, null, 80,
            AudibleAlarmEngine.Runtime(100L, 100L, 200L), 500L
        )
        val next = evaluate(200L, 600L, completed.runtime)

        assertTrue(next.playNow)
        assertNull(next.runtime.dismissedOutageId)
    }

    private fun evaluate(
        outageId: Long,
        now: Long,
        runtime: AudibleAlarmEngine.Runtime = AudibleAlarmEngine.Runtime(),
        scheduled: Boolean = false,
        battery: Int = 80
    ) = AudibleAlarmEngine.evaluate(
        config = config,
        monitoringEnabled = true,
        confirmedOutage = true,
        outageId = outageId,
        batteryPercent = battery,
        runtime = runtime,
        nowEpochMs = now,
        scheduledTick = scheduled
    )
}
