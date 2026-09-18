package com.flossypickle.poweroutagemonitor

import com.flossypickle.poweroutagemonitor.integrations.alerts.ScheduledAlertPolicy
import com.flossypickle.poweroutagemonitor.integrations.alerts.ScheduledAlertStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledAlertPolicyTest {
    private val settings = ScheduledAlertStore.Settings(
        sourceUnavailableDelayMs = 5 * 60_000L,
        heartbeatIntervalMs = 24 * 60 * 60_000L,
        outageUpdateIntervalMs = 6 * 60 * 60_000L
    )

    @Test
    fun `source problem alerts only after continuous delay then reports recovery`() {
        val started = ScheduledAlertPolicy.update(
            ScheduledAlertStore.State(), settings, false, powered(), 1_000L
        )
        val early = ScheduledAlertPolicy.update(
            started.state, settings, false, powered(), 300_999L
        )
        val due = ScheduledAlertPolicy.update(
            early.state, settings, false, powered(), 301_000L
        )
        val repeated = ScheduledAlertPolicy.update(
            due.state, settings, false, powered(), 900_000L
        )
        val restored = ScheduledAlertPolicy.update(
            repeated.state, settings, true, powered(), 901_000L
        )

        assertTrue(started.notices.isEmpty())
        assertTrue(early.notices.isEmpty())
        assertEquals(
            listOf(ScheduledAlertPolicy.Notice.SourceUnavailable(1_000L)),
            due.notices
        )
        assertTrue(repeated.notices.isEmpty())
        assertEquals(
            listOf(ScheduledAlertPolicy.Notice.SourceAvailableAgain(1_000L)),
            restored.notices
        )
        assertNull(restored.state.sourceUnavailableSinceEpochMs)
        assertFalse(restored.state.sourceUnavailableAlerted)
    }

    @Test
    fun `brief unreadable source clears without sending either message`() {
        val started = ScheduledAlertPolicy.update(
            ScheduledAlertStore.State(), settings, false, powered(), 10_000L
        )
        val restored = ScheduledAlertPolicy.update(
            started.state, settings, true, powered(), 20_000L
        )

        assertTrue(restored.notices.isEmpty())
        assertNull(restored.state.sourceUnavailableSinceEpochMs)
    }

    @Test
    fun `source warning waits when there is no configured alert destination`() {
        val before = ScheduledAlertStore.State(sourceUnavailableSinceEpochMs = 1_000L)
        val withoutDestination = ScheduledAlertPolicy.update(
            before = before,
            settings = settings,
            sourceReadable = false,
            monitorState = powered(),
            nowEpochMs = 1_000L + settings.sourceUnavailableDelayMs,
            canNotify = false
        )
        val afterDestinationAdded = ScheduledAlertPolicy.update(
            before = withoutDestination.state,
            settings = settings,
            sourceReadable = false,
            monitorState = powered(),
            nowEpochMs = 1_001L + settings.sourceUnavailableDelayMs,
            canNotify = true
        )

        assertTrue(withoutDestination.notices.isEmpty())
        assertFalse(withoutDestination.state.sourceUnavailableAlerted)
        assertTrue(afterDestinationAdded.state.sourceUnavailableAlerted)
        assertEquals(1, afterDestinationAdded.notices.size)
    }

    @Test
    fun `heartbeat establishes baseline and repeats from actual send time`() {
        val initial = ScheduledAlertPolicy.update(
            ScheduledAlertStore.State(), settings, true, powered(), 1_000L
        )
        val dueAt = 1_000L + settings.heartbeatIntervalMs
        val due = ScheduledAlertPolicy.update(initial.state, settings, true, powered(), dueAt)
        val immediateRepeat = ScheduledAlertPolicy.update(
            due.state, settings, true, powered(), dueAt + 1
        )

        assertTrue(initial.notices.isEmpty())
        assertTrue(due.notices.contains(ScheduledAlertPolicy.Notice.Heartbeat))
        assertTrue(immediateRepeat.notices.isEmpty())
        assertEquals(dueAt + settings.heartbeatIntervalMs,
            ScheduledAlertPolicy.nextDeadline(due.state, settings, powered()))
    }

    @Test
    fun `long outage waits six hours and resets after restoration`() {
        val confirmedAt = 60_000L
        val outage = OutageEngine.State(
            phase = OutageEngine.Phase.OUTAGE,
            phaseSinceEpochMs = confirmedAt,
            outageStartedEpochMs = 1_000L,
            confirmedAtEpochMs = confirmedAt
        )
        val initial = ScheduledAlertPolicy.update(
            ScheduledAlertStore.State(), settings, true, outage, confirmedAt
        )
        val dueAt = confirmedAt + settings.outageUpdateIntervalMs
        val due = ScheduledAlertPolicy.update(initial.state, settings, true, outage, dueAt)
        val restored = ScheduledAlertPolicy.update(due.state, settings, true, powered(), dueAt + 1)

        assertTrue(initial.notices.isEmpty())
        assertEquals(
            listOf(ScheduledAlertPolicy.Notice.OutageUpdate(1_000L)),
            due.notices.filterIsInstance<ScheduledAlertPolicy.Notice.OutageUpdate>()
        )
        assertNull(restored.state.trackedOutageStartedEpochMs)
        assertNull(restored.state.lastOutageUpdateEpochMs)
    }

    @Test
    fun `pending restoration remains an active outage`() {
        val state = OutageEngine.State(
            phase = OutageEngine.Phase.PENDING_RESTORE,
            phaseSinceEpochMs = 100L,
            outageStartedEpochMs = 10L,
            confirmedAtEpochMs = 20L
        )
        val before = ScheduledAlertStore.State(
            lastHeartbeatEpochMs = 100L,
            trackedOutageStartedEpochMs = 10L,
            lastOutageUpdateEpochMs = 20L
        )
        val due = ScheduledAlertPolicy.update(
            before, settings, true, state, 20L + settings.outageUpdateIntervalMs
        )

        assertTrue(due.notices.any { it is ScheduledAlertPolicy.Notice.OutageUpdate })
    }


    @Test fun `overdue source warning waits for restart check without fabricating recovery`() {
        val before = ScheduledAlertStore.State(sourceUnavailableSinceEpochMs = 1_000)
        val connecting = ScheduledAlertPolicy.update(before, settings, false, powered(), 400_000,
            deferSourceWarningUntilEpochMs = 580_000)
        assertTrue(connecting.notices.isEmpty())
        assertFalse(connecting.state.sourceUnavailableAlerted)
        assertEquals(580_000L, ScheduledAlertPolicy.nextDeadline(connecting.state, settings, powered(), 580_000))
        val restored = ScheduledAlertPolicy.update(connecting.state, settings, true, powered(), 420_000)
        assertTrue(restored.notices.isEmpty())
        assertNull(restored.state.sourceUnavailableSinceEpochMs)
        val failed = ScheduledAlertPolicy.update(connecting.state, settings, false, powered(), 420_000)
        assertEquals(listOf(ScheduledAlertPolicy.Notice.SourceUnavailable(1_000)), failed.notices)
        val hung = ScheduledAlertPolicy.update(connecting.state, settings, false, powered(), 580_000,
            deferSourceWarningUntilEpochMs = 580_000)
        assertEquals(listOf(ScheduledAlertPolicy.Notice.SourceUnavailable(1_000)), hung.notices)
    }

    private fun powered() = OutageEngine.State(
        phase = OutageEngine.Phase.POWERED,
        phaseSinceEpochMs = 1L
    )
}
