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

    @Test fun `expected package update and reboot are distinguished from process loss`() {
        assertEquals(
            OperationalHistoryStore.KIND_MONITORING_UPDATED,
            OperationalHistoryStore.startKind(
                previousSessionUnclosed = true,
                cause = OperationalHistoryStore.RestartCause.APP_UPDATE
            )
        )
        assertEquals(
            OperationalHistoryStore.KIND_MONITORING_REBOOTED,
            OperationalHistoryStore.startKind(
                previousSessionUnclosed = true,
                cause = OperationalHistoryStore.RestartCause.DEVICE_REBOOT
            )
        )
        assertEquals(
            OperationalHistoryStore.KIND_MONITORING_RECOVERED,
            OperationalHistoryStore.startKind(previousSessionUnclosed = true)
        )
        assertEquals(
            OperationalHistoryStore.KIND_APP_UPDATED,
            OperationalHistoryStore.appStartKind(
                previousSessionUnclosed = true,
                cause = OperationalHistoryStore.RestartCause.APP_UPDATE
            )
        )
    }
}
