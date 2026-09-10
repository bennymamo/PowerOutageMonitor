package com.flossypickle.poweroutagemonitor

import android.os.BatteryManager
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertKind
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertMessageFactory
import com.flossypickle.poweroutagemonitor.monitoring.PowerSnapshot
import com.flossypickle.poweroutagemonitor.storage.MonitorStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertMessageFactoryTest {
    @Test
    fun `confirmed outage creates a stable provider-neutral message`() {
        val before = state(OutageEngine.Phase.PENDING_OUTAGE, confirmedAt = null)
        val after = state(OutageEngine.Phase.OUTAGE, confirmedAt = 70_000L)

        val message = AlertMessageFactory.forTransition(before, after, snapshot(false), settings(), 70_000L)

        assertEquals(AlertKind.OUTAGE, message?.kind)
        assertEquals("power-event-10000", message?.eventId)
        assertTrue(message?.body?.contains("Garage monitor") == true)
        assertTrue(message?.body?.contains("82%") == true)
    }

    @Test
    fun `return to outage during restore delay does not duplicate outage alert`() {
        val before = state(OutageEngine.Phase.PENDING_RESTORE, confirmedAt = 70_000L)
        val after = state(OutageEngine.Phase.OUTAGE, confirmedAt = 70_000L)

        assertNull(AlertMessageFactory.forTransition(before, after, snapshot(false), settings(), 90_000L))
    }

    @Test
    fun `stable restoration respects restoration alert setting`() {
        val before = state(OutageEngine.Phase.PENDING_RESTORE, confirmedAt = 70_000L)
        val after = OutageEngine.State(OutageEngine.Phase.POWERED, 190_000L)

        val message = AlertMessageFactory.forTransition(before, after, snapshot(true), settings(), 190_000L)
        assertEquals(AlertKind.RESTORED, message?.kind)
        assertTrue(message?.body?.contains("3 min") == true)

        assertNull(AlertMessageFactory.forTransition(
            before, after, snapshot(true), settings().copy(sendRestoreNotification = false), 190_000L
        ))
    }

    @Test
    fun `duration formatter remains readable for short and long outages`() {
        assertEquals("59 sec", AlertMessageFactory.formatDuration(59_999L))
        assertEquals("4 min", AlertMessageFactory.formatDuration(4 * 60_000L))
        assertEquals("2 h 5 min", AlertMessageFactory.formatDuration(125 * 60_000L))
    }

    private fun state(phase: OutageEngine.Phase, confirmedAt: Long?) = OutageEngine.State(
        phase = phase,
        phaseSinceEpochMs = if (phase == OutageEngine.Phase.PENDING_OUTAGE) 10_000L else 80_000L,
        outageStartedEpochMs = 10_000L,
        outageStartBatteryPercent = 82,
        confirmedAtEpochMs = confirmedAt
    )

    private fun settings() = MonitorStore.Settings(
        monitoringEnabled = true,
        outageDelayMs = 60_000L,
        restoreDelayMs = 30_000L,
        sendRestoreNotification = true,
        deviceName = "Garage monitor"
    )

    private fun snapshot(powered: Boolean) = PowerSnapshot(
        plugged = if (powered) BatteryManager.BATTERY_PLUGGED_AC else 0,
        batteryPercent = if (powered) 78 else 82,
        batteryStatus = BatteryManager.BATTERY_STATUS_DISCHARGING,
        batteryTemperatureTenthsCelsius = null
    )
}
