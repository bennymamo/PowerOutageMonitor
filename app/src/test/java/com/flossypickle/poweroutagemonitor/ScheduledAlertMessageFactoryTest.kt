package com.flossypickle.poweroutagemonitor

import com.flossypickle.poweroutagemonitor.integrations.alerts.*
import com.flossypickle.poweroutagemonitor.integrations.power.PowerSourceStore
import com.flossypickle.poweroutagemonitor.monitoring.PowerSnapshot
import com.flossypickle.poweroutagemonitor.storage.MonitorStore
import org.junit.Assert.*
import org.junit.Test

class ScheduledAlertMessageFactoryTest {
    @Test fun combinedSourceWarningDescribesUnknownGridStatusRatherThanGridLoss() {
        val message = ScheduledAlertMessageFactory.create(ScheduledAlertPolicy.Notice.SourceUnavailable(1000),
            MonitorStore.Settings(true, true, 30_000, 30_000, true, "Sample monitor"), OutageEngine.State(OutageEngine.Phase.POWERED), PowerSnapshot(0, 90, 3, null),
            PowerSourceStore.Source.ECOFLOW_ACCOUNT, false, 400_000, chargerAssisted = true)
        assertEquals("GRID STATUS UNKNOWN", message.title)
        assertTrue(message.body.contains("Source: Charger with EcoFlow assistance"))
        assertTrue(message.body.contains("Please confirm manually."))
        assertFalse(message.body.contains("Unavailable since:"))
    }
    @Test fun readableAgainThroughChargerDoesNotClaimEcoFlowRecovered() {
        val message = ScheduledAlertMessageFactory.create(ScheduledAlertPolicy.Notice.SourceAvailableAgain(1000),
            MonitorStore.Settings(true, true, 30_000, 30_000, true, "Sample monitor"), OutageEngine.State(OutageEngine.Phase.POWERED), PowerSnapshot(1, 90, 2, null),
            PowerSourceStore.Source.ECOFLOW_ACCOUNT, true, 400_000, chargerAssisted = true)
        assertEquals("GRID STATUS READABLE AGAIN", message.title)
        assertTrue(message.body.contains("Charger power is available."))
        assertTrue(message.body.contains("does not confirm the EcoFlow connection recovered"))
        assertFalse(message.body.contains("Unavailable for:"))
    }
}
