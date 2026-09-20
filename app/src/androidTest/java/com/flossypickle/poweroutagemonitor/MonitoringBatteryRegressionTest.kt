package com.flossypickle.poweroutagemonitor

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.flossypickle.poweroutagemonitor.audible.*
import com.flossypickle.poweroutagemonitor.integrations.alerts.*
import com.flossypickle.poweroutagemonitor.integrations.power.PowerSourceStore
import com.flossypickle.poweroutagemonitor.monitoring.*
import com.flossypickle.poweroutagemonitor.storage.*
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Run on a dedicated test device/emulator: the coordinator exercises Android alarm APIs. */
class MonitoringBatteryRegressionTest {
    private class QaContext : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        private val prefix = "battery_review_${UUID.randomUUID()}_"
        private val names = mutableSetOf<String>()
        private val directory = File(super.getFilesDir(), prefix).apply { mkdirs() }
        override fun getApplicationContext(): Context = this
        override fun createDeviceProtectedStorageContext(): Context = this
        override fun getFilesDir(): File = directory
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            names += name
            return super.getSharedPreferences(prefix + name, mode)
        }
        fun clear() {
            names.forEach { super.getSharedPreferences(prefix + it, 0).edit().clear().commit() }
            directory.deleteRecursively()
        }
    }

    @Test fun stableEcoFlowOutagePersistsBatteryDeclineWarnsOnceAndStopsSound() {
        for (source in listOf(PowerSourceStore.Source.ECOFLOW_ACCOUNT, PowerSourceStore.Source.ECOFLOW_MODBUS)) {
            val context = QaContext()
            try {
                // Seed the persisted selection without exercising account setup or network access.
                context.getSharedPreferences("power_sources", 0).edit().putString("selected_source", source.name).commit()
                val monitor = MonitorStore(context)
                monitor.setMonitoringEnabled(true)
                monitor.setRestoredDeliveriesPaused(true) // Exercise durable Direct Boot/pause retention, never send.
                monitor.setBatteryLowAlert(true, 30)
                EnabledAlertProvidersStore(context).setEnabled("qa", true)
                ScheduledAlertStore(context).updateSettings(ScheduledAlertStore.Settings(
                    sourceUnavailableEnabled = false, heartbeatEnabled = false, outageUpdatesEnabled = false))
                val state = OutageEngine.State(phase = OutageEngine.Phase.OUTAGE,
                    outageStartedEpochMs = 1_000, confirmedAtEpochMs = 2_000)
                monitor.save(state, PowerSnapshot(0, 50, 3, null), 3_000)
                val alarm = AudibleAlarmStore(context)
                alarm.updateSettings(AudibleAlarmStore.Settings(enabled = true, stopBatteryPercent = 20))
                alarm.saveRuntime(AudibleAlarmEngine.Runtime(activeOutageId = 1_000, lastPlayedAtEpochMs = 3_000))
                val coordinator = MonitoringCoordinator(context)
                coordinator.processScheduledOnly(PowerSnapshot(0, 25, 3, null), false, 4_000)
                coordinator.processScheduledOnly(PowerSnapshot(0, 20, 3, null), false, 5_000)
                coordinator.processScheduledOnly(PowerSnapshot(0, 19, 3, null), false, 6_000)
                assertEquals(state, monitor.state())
                assertEquals(19, monitor.lastSnapshot()!!.batteryPercent)
                assertEquals(6_000L, monitor.lastObservationEpochMs())
                assertEquals(1, PendingAlertEventStore(context).read().count { it.kind == AlertKind.BATTERY_LOW })
                assertEquals(1_000L, alarm.runtime().dismissedOutageId)
            } finally {
                AudibleAlarmCoordinator(context).stop()
                context.clear()
            }
        }
    }
}
