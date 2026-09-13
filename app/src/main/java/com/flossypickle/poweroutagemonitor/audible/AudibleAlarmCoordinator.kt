package com.flossypickle.poweroutagemonitor.audible

import android.content.Context
import com.flossypickle.poweroutagemonitor.OutageEngine
import com.flossypickle.poweroutagemonitor.monitoring.PowerSnapshot
import com.flossypickle.poweroutagemonitor.storage.MonitorStore

/** Connects pure audible-alarm decisions to persistent state, scheduling and sound. */
internal class AudibleAlarmCoordinator(private val context: Context) {
    private val alarmStore = AudibleAlarmStore(context)
    private val scheduler = AudibleAlarmScheduler(context)

    fun reconcile(
        state: OutageEngine.State,
        snapshot: PowerSnapshot?,
        nowEpochMs: Long = System.currentTimeMillis(),
        scheduledTick: Boolean = false
    ) {
        val settings = alarmStore.settings()
        val decision = AudibleAlarmEngine.evaluate(
            config = AudibleAlarmEngine.Config(
                enabled = settings.enabled,
                repeatIntervalMs = settings.repeatIntervalMs,
                stopBatteryPercent = settings.stopBatteryPercent
            ),
            monitoringEnabled = MonitorStore(context).settings().monitoringEnabled,
            confirmedOutage = state.phase == OutageEngine.Phase.OUTAGE,
            outageId = state.outageStartedEpochMs,
            batteryPercent = snapshot?.batteryPercent,
            runtime = alarmStore.runtime(),
            nowEpochMs = nowEpochMs,
            scheduledTick = scheduledTick
        )
        alarmStore.saveRuntime(decision.runtime)
        decision.nextAlarmAtEpochMs?.let { scheduler.schedule(it, settings.scheduleMode) }
            ?: scheduler.cancel()
        if (decision.playNow) {
            AudibleAlarmPlayer(context).play(settings.useMaximumVolume, settings.soundUri)
        } else if (decision.nextAlarmAtEpochMs == null) {
            AudibleAlarmPlayer(context).stop()
        }
    }

    fun dismissCurrent() {
        val runtime = alarmStore.runtime()
        val outageId = runtime.activeOutageId ?: MonitorStore(context).state().outageStartedEpochMs
        alarmStore.saveRuntime(
            AudibleAlarmEngine.Runtime(
                activeOutageId = outageId,
                dismissedOutageId = outageId
            )
        )
        scheduler.cancel()
        AudibleAlarmPlayer(context).stop()
        broadcastChange()
    }

    fun stop() {
        alarmStore.saveRuntime(AudibleAlarmEngine.Runtime())
        scheduler.cancel()
        AudibleAlarmPlayer(context).stop()
        broadcastChange()
    }

    fun isActive(state: OutageEngine.State, snapshot: PowerSnapshot?): Boolean {
        val settings = alarmStore.settings()
        val runtime = alarmStore.runtime()
        val outageId = state.outageStartedEpochMs
        return settings.enabled &&
            MonitorStore(context).settings().monitoringEnabled &&
            state.phase == OutageEngine.Phase.OUTAGE &&
            outageId != null && runtime.activeOutageId == outageId &&
            runtime.dismissedOutageId != outageId &&
            snapshot?.batteryPercent?.let { it > settings.stopBatteryPercent } != false
    }

    fun exactAccessGranted(): Boolean = scheduler.exactAccessGranted()

    private fun broadcastChange() {
        context.sendBroadcast(
            android.content.Intent(ACTION_AUDIBLE_ALARM_CHANGED).setPackage(context.packageName)
        )
    }

    companion object {
        const val ACTION_AUDIBLE_ALARM_CHANGED =
            "com.flossypickle.poweroutagemonitor.AUDIBLE_ALARM_CHANGED"
    }
}
