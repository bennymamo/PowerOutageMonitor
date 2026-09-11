package com.flossypickle.poweroutagemonitor

import com.flossypickle.poweroutagemonitor.storage.OperationalHistoryStore
import org.junit.Assert.assertEquals
import org.junit.Test

class OperationalHistoryStoreTest {
    @Test fun `normal first service start is recorded normally`() {
        assertEquals(
            OperationalHistoryStore.KIND_MONITORING_STARTED,
            OperationalHistoryStore.startKind(previousSessionUnclosed = false)
        )
    }

    @Test fun `start without recorded stop is flagged`() {
        assertEquals(
            OperationalHistoryStore.KIND_MONITORING_RECOVERED,
            OperationalHistoryStore.startKind(previousSessionUnclosed = true)
        )
    }
}
