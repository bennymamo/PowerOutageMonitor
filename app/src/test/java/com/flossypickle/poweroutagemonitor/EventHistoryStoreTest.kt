package com.flossypickle.poweroutagemonitor

import com.flossypickle.poweroutagemonitor.storage.EventHistoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class EventHistoryStoreTest {
    @Test
    fun `immediate restoration completes confirmed outage history`() {
        val before = OutageEngine.State(
            phase = OutageEngine.Phase.OUTAGE,
            phaseSinceEpochMs = 20,
            outageStartedEpochMs = 10,
            confirmedAtEpochMs = 20
        )
        val after = OutageEngine.State(OutageEngine.Phase.POWERED, 30)

        assertEquals(
            EventHistoryStore.KIND_CONFIRMED_OUTAGE,
            EventHistoryStore.completedKind(before, after)
        )
    }

    @Test
    fun `stable delayed restoration completes confirmed outage history`() {
        val before = OutageEngine.State(
            phase = OutageEngine.Phase.PENDING_RESTORE,
            phaseSinceEpochMs = 25,
            outageStartedEpochMs = 10,
            confirmedAtEpochMs = 20
        )
        val after = OutageEngine.State(OutageEngine.Phase.POWERED, 30)

        assertEquals(
            EventHistoryStore.KIND_CONFIRMED_OUTAGE,
            EventHistoryStore.completedKind(before, after)
        )
    }

    @Test
    fun `replayed completion does not duplicate history event`() {
        val original = record(restoredAt = 30)
        val replayed = record(restoredAt = 35)
        val records = listOf(original)

        val updated = EventHistoryStore.appendUnique(records, replayed, maxRecords = 200)

        assertSame(records, updated)
        assertEquals(listOf(original), updated)
    }

    private fun record(restoredAt: Long) = EventHistoryStore.Record(
        kind = EventHistoryStore.KIND_CONFIRMED_OUTAGE,
        powerLostAtEpochMs = 10,
        confirmedAtEpochMs = 20,
        restoredAtEpochMs = restoredAt,
        startingBatteryPercent = 90,
        endingBatteryPercent = 89
    )
}
