package com.flossypickle.poweroutagemonitor.monitoring

import android.content.Context
import android.content.Intent
import android.util.Log
import com.flossypickle.poweroutagemonitor.OutageEngine
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertDeliveryCoordinator
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertMessageFactory
import com.flossypickle.poweroutagemonitor.storage.EventHistoryStore
import com.flossypickle.poweroutagemonitor.storage.MonitorStore

/** Serializes observations, state transitions, durable history, and deadline scheduling. */
internal class MonitoringCoordinator(private val context: Context) {
    private val store = MonitorStore(context)
    private val history = EventHistoryStore(context)
    private val alerts = AlertDeliveryCoordinator(context)

    @Synchronized
    fun process(snapshot: PowerSnapshot, nowEpochMs: Long = System.currentTimeMillis()): OutageEngine.State {
        val settings = store.settings()
        if (!settings.monitoringEnabled) return store.state()

        val before = store.state()
        val after = OutageEngine.update(
            state = before,
            powered = snapshot.externallyPowered,
            nowEpochMs = nowEpochMs,
            batteryPercent = snapshot.batteryPercent,
            outageDelayMs = settings.outageDelayMs,
            restoreDelayMs = settings.restoreDelayMs
        )

        AlertMessageFactory.forTransition(before, after, snapshot, settings, nowEpochMs)
            ?.let(alerts::persistForEnabledProviders)
        recordCompletedEvent(before, after, snapshot, nowEpochMs, settings.historyLimit)
        store.save(after, snapshot, nowEpochMs)
        DeadlineScheduler(context).schedule(after, settings)
        alerts.materializePending()
        context.sendBroadcast(Intent(ACTION_MONITOR_STATE_CHANGED).setPackage(context.packageName))
        return after
    }

    private fun recordCompletedEvent(
        before: OutageEngine.State,
        after: OutageEngine.State,
        snapshot: PowerSnapshot,
        nowEpochMs: Long,
        historyLimit: Int
    ) {
        val wasBrief = before.phase == OutageEngine.Phase.PENDING_OUTAGE &&
            after.phase == OutageEngine.Phase.POWERED
        val wasRestored = before.phase == OutageEngine.Phase.PENDING_RESTORE &&
            after.phase == OutageEngine.Phase.POWERED
        if (!wasBrief && !wasRestored) return

        runCatching {
            history.append(EventHistoryStore.Record(
                kind = if (wasBrief) {
                    EventHistoryStore.KIND_BRIEF_INTERRUPTION
                } else {
                    EventHistoryStore.KIND_CONFIRMED_OUTAGE
                },
                powerLostAtEpochMs = before.outageStartedEpochMs ?: before.phaseSinceEpochMs,
                confirmedAtEpochMs = before.confirmedAtEpochMs,
                restoredAtEpochMs = nowEpochMs,
                startingBatteryPercent = before.outageStartBatteryPercent,
                endingBatteryPercent = snapshot.batteryPercent
            ), historyLimit)
        }.onFailure { Log.e(TAG, "Unable to store power event history", it) }
    }

    companion object {
        const val ACTION_MONITOR_STATE_CHANGED =
            "com.flossypickle.poweroutagemonitor.MONITOR_STATE_CHANGED"
        private const val TAG = "MonitoringCoordinator"
    }
}
