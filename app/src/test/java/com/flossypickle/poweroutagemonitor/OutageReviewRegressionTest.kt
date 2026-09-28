package com.flossypickle.poweroutagemonitor

import com.flossypickle.poweroutagemonitor.integrations.alerts.*
import com.flossypickle.poweroutagemonitor.integrations.alerts.telegram.TelegramRemotePolicy
import com.flossypickle.poweroutagemonitor.integrations.power.*
import com.flossypickle.poweroutagemonitor.integrations.power.ecoflow.*
import com.flossypickle.poweroutagemonitor.monitoring.PowerSnapshot
import org.junit.Assert.*
import org.junit.Test

class OutageReviewRegressionTest {
    private fun check(code: String = "0") = PowerSourceCheck(2000, 3000, true,
        observations = listOf(SourceReportedValue("Reported grid code", code, "", 2500, false, true)),
        cycleState = PowerSourceCheck.CycleState.WAITING, finishedAtEpochMs = 3100,
        evidenceValidUntilEpochMs = 3_662_500, ecoFlowAvailability = GridAvailability.AVAILABLE)
    private fun status(code: String = "0", pending: Boolean = false) = PowerSourceStore.Status(
        PowerSourceStore.Source.ECOFLOW_ACCOUNT, GridAvailability.AVAILABLE, 3100, "", pending,
        check = check(code), evidenceReceivedAtEpochMs = 2500)

    @Test fun reconnectionRequiresNewQualifiedCodeZeroWithLiveSupport() {
        assertTrue(GridReconnectionPolicy.confirmed(status(), 1500, 4000))
        assertFalse(GridReconnectionPolicy.confirmed(status("1"), 1500, 4000))
        assertFalse(GridReconnectionPolicy.confirmed(status(pending = true), 1500, 4000))
        assertFalse(GridReconnectionPolicy.confirmed(status(), 2500, 4000))
        assertFalse(GridReconnectionPolicy.confirmed(status(), 1500, 100_000))
        assertFalse(GridReconnectionPolicy.confirmed(status().copy(check = check().copy(gridEvidenceAvailable = false)), 1500, 4000))
        assertFalse(GridReconnectionPolicy.confirmed(status().copy(check = check().copy(liveReportAtEpochMs = null)), 1500, 4000))
    }

    @Test fun reconnectionFollowUpIsDeduplicatedAndCannotOvertakeRestoration() {
        val restore = AlertQueueEngine.Item("r", "telegram", "test", AlertMessage("power-event-1000", AlertKind.RESTORED, "", ""), createdAtEpochMs = 2000)
        val connected = restore.copy(id = "c", message = restore.message.copy(kind = AlertKind.GRID_RECONNECTED), createdAtEpochMs = 3000)
        val queue = AlertQueueEngine.enqueue(listOf(restore), connected)
        assertEquals(2, AlertQueueEngine.enqueue(queue, connected.copy(id = "duplicate")).size)
        assertEquals(listOf(restore), AlertQueueEngine.due(queue, 4000))
        assertEquals(listOf(connected), AlertQueueEngine.due(listOf(AlertQueueEngine.complete(restore, DeliveryResult.Sent(), 3500), connected), 4000))
    }

    @Test fun homeBatteryUsesAggregateReportsAndKeepsActualReceipt() {
        val inspection = PowerOceanGridInspection()
        inspection.observe(PowerOceanPushDecoder.Report(33, mapOf("bpSoc" to 76L)), 1000, false, true)
        inspection.observe(PowerOceanPushDecoder.Report(33, mapOf("sysLoadPwr" to 200.0)), 2000, false, true)
        assertEquals(SourceBatteryReading(76, 1000, true), inspection.snapshot().battery)
        inspection.observe(PowerOceanPushDecoder.Report(8, mapOf("bpSoc" to 70L)), 3000, false, false)
        assertEquals(SourceBatteryReading(70, 3000, false), inspection.snapshot().battery)
    }

    @Test fun retainedOldInvalidAndIndividualBatteryFieldsCannotOverwriteAggregateCharge() {
        val inspection = PowerOceanGridInspection()
        inspection.observe(PowerOceanPushDecoder.Report(33, mapOf("bpSoc" to 76L)), 1000, false)
        inspection.observe(PowerOceanPushDecoder.Report(33, mapOf("bpSoc" to 90L)), 2000, true)
        inspection.observe(PowerOceanPushDecoder.Report(33, mapOf("bpSoc" to 70L)), 500, false)
        for (value in listOf(-1.0, 101.0, Double.NaN, 70.5))
            inspection.observe(PowerOceanPushDecoder.Report(33, mapOf("bpSoc" to value)), 3000, false)
        inspection.observe(PowerOceanPushDecoder.Report(7, mapOf("BP_STA_REPORT[0].bpSoc" to 20L)), 4000, false)
        assertEquals(SourceBatteryReading(76, 1000, true), inspection.snapshot().battery)
    }

    @Test fun hourlyOverrideCannotSlowNewOutageOrFailureRetries() {
        val settings = PowerOceanAssistedSettings(enabled = true, outageSeconds = 60)
        fun interval(failed: Boolean = false, loss: Boolean = false, charger: Boolean? = false) =
            PowerOceanTemporarySchedule.interval(settings, true, failed, true, charger, loss)
        assertEquals(3600, interval())
        assertEquals(60, interval(loss = true))
        assertEquals(60, interval(failed = true))
        assertEquals(60, interval(charger = true))
    }

    @Test fun delayedReadOnlyStatusSurvivesWhileOldControlsRemainRejected() {
        val message = TelegramRemotePolicy.Message(12, "123", "123", true, false, false, 100_000, "/status")
        fun authorize(text: String, now: Long = 700_000) = TelegramRemotePolicy.authorize(message.copy(text = text), setOf("123"), 90_000, now, 12)
        assertEquals("status", authorize("/status")?.name)
        assertEquals("help", authorize("/help")?.name)
        assertNull(authorize("/restart_monitoring"))
        assertNull(authorize("/monitor_off"))
        assertNull(authorize("/status", 100_000 + 24 * 60 * 60_000L + 1))
        assertEquals("restart_monitoring", authorize("/restart_monitoring", 110_000)?.name)
    }

    @Test fun completedHourlyEvidenceIsVisibleUntilTheOriginalDeadlineWithoutRefreshingReceipt() {
        assertTrue(DashboardSourceReadingPolicy.isCurrent(status(), PowerSourceStore.Source.ECOFLOW_ACCOUNT, 40_000, 3600, 120_000))
        assertFalse(DashboardSourceReadingPolicy.isCurrent(status(), PowerSourceStore.Source.ECOFLOW_ACCOUNT, 3_662_501, 3600, 120_000))
        assertFalse(DashboardSourceReadingPolicy.isCurrent(status().copy(check = check().copy(cycleState = PowerSourceCheck.CycleState.FAILED)), PowerSourceStore.Source.ECOFLOW_ACCOUNT, 40_000, 3600, 120_000))
        assertEquals(2500L, status().evidenceReceivedAtEpochMs)
    }

    @Test fun alertBasisUsesDecisionProvenanceInsteadOfAnExplanationPrefix() {
        val message = AlertMessage("e", AlertKind.OUTAGE, "Outage", "")
        val snapshot = PowerSnapshot(0, 99, 3, null)
        val verified = status("1").copy(availability = GridAvailability.UNAVAILABLE,
            detail = "Charger power lost. Old wording", outageVerifiedByEcoFlow = true)
        assertTrue(AlertEvidenceDetails.append(message, PowerSourceStore.Source.ECOFLOW_ACCOUNT, verified, snapshot, 4000).body.contains("EcoFlow verified grid loss"))
        assertTrue(AlertEvidenceDetails.append(message, PowerSourceStore.Source.ECOFLOW_ACCOUNT, verified.copy(outageVerifiedByEcoFlow = false), snapshot, 4000).body.contains("Charger loss confirmed"))
    }

    @Test fun meterRoundingAndOverdueProgressPreserveMeaning() {
        val observation = SourceReportedValue("Meter 1 reading", "-23.600000381469727", "", 2000, false)
        assertEquals("-23.6 W", SourceEvidencePresentation.value(observation))
        assertEquals("0.0 W", SourceEvidencePresentation.value(observation.copy(value = "0.0")))
        val active = check().copy(cycleState = PowerSourceCheck.CycleState.CONNECTING, startedAtEpochMs = 1000, deadlineAtEpochMs = 181_000)
        assertTrue(SourceEvidencePresentation.progress(active, 30_000).contains("connecting for 29s"))
        assertTrue(SourceEvidencePresentation.progress(active, 181_001).contains("overdue"))
        assertTrue(SourceEvidencePresentation.qualification(check(), 3_662_501).contains("expired"))
    }

    @Test fun sessionRefreshDoesNotDependOnPhoneChargerPower() {
        var failures = 0
        repeat(2) { failures = PowerOceanFailureRetryPolicy.sessionFailureStreak(failures, true) }
        assertTrue(PowerOceanFailureRetryPolicy.refreshSession(failures, 2, true))
        assertEquals(0, PowerOceanFailureRetryPolicy.sessionFailureStreak(failures, false))
    }
}
