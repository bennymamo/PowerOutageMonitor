package com.flossypickle.poweroutagemonitor.integrations.alerts

import android.content.Context
import com.flossypickle.poweroutagemonitor.OutageEngine
import com.flossypickle.poweroutagemonitor.integrations.power.PowerSourceStore
import com.flossypickle.poweroutagemonitor.monitoring.PowerSnapshot
import com.flossypickle.poweroutagemonitor.storage.MonitorStore
import com.flossypickle.poweroutagemonitor.storage.EnabledAlertProvidersStore

/** Turns persisted operational timers into provider-neutral queued messages. */
internal class ScheduledAlertCoordinator(private val context: Context) {
    private val store = ScheduledAlertStore(context)
    private val alerts = AlertDeliveryCoordinator(context)

    @Synchronized
    fun process(
        monitorState: OutageEngine.State,
        snapshot: PowerSnapshot,
        gridPowered: Boolean?,
        selectedSource: PowerSourceStore.Source,
        nowEpochMs: Long
    ) {
        val sourceStore = PowerSourceStore(context)
        val assistance = sourceStore.powerOceanAssistedSettings()
        val chargerAssisted = selectedSource == PowerSourceStore.Source.ECOFLOW_ACCOUNT && assistance.enabled
        val configured = store.settings()
        val settings = configured.copy(sourceUnavailableEnabled = configured.sourceUnavailableEnabled &&
            !(chargerAssisted && snapshot.externallyPowered == false && !assistance.notifyOnUnknown))
        val canNotify = EnabledAlertProvidersStore(context).hasAny()
        val verifyUntil = if (selectedSource == PowerSourceStore.Source.ECOFLOW_ACCOUNT && gridPowered == null)
            sourceStore.lastStatus()?.check?.verificationDeadline(nowEpochMs, assistance.checkWindowSeconds * 1000L) else null
        val result = ScheduledAlertPolicy.update(
            before = store.state(),
            settings = settings,
            sourceReadable = gridPowered != null,
            monitorState = monitorState,
            nowEpochMs = nowEpochMs,
            canNotify = canNotify,
            deferSourceWarningUntilEpochMs = verifyUntil
        )
        // Save the timer advancement before queueing so a process restart cannot duplicate a notice.
        store.save(result.state)
        val monitorSettings = MonitorStore(context).settings()
        result.notices.forEach { notice ->
            alerts.persistForEnabledProviders(
                ScheduledAlertMessageFactory.create(
                    notice = notice,
                    monitorSettings = monitorSettings,
                    monitorState = monitorState,
                    snapshot = snapshot,
                    selectedSource = selectedSource,
                    sourceReadable = gridPowered != null,
                    nowEpochMs = nowEpochMs,
                    chargerAssisted = chargerAssisted
                )
            )
        }
        ScheduledAlertScheduler(context).schedule(if (canNotify) {
            ScheduledAlertPolicy.nextDeadline(result.state, settings, monitorState, verifyUntil)
        } else null)
        if (result.notices.isNotEmpty()) alerts.materializePending()
    }

    fun settingsChanged(
        monitorState: OutageEngine.State,
        snapshot: PowerSnapshot,
        gridPowered: Boolean?,
        selectedSource: PowerSourceStore.Source,
        nowEpochMs: Long = System.currentTimeMillis()
    ) = process(monitorState, snapshot, gridPowered, selectedSource, nowEpochMs)

    fun stop() {
        store.resetRuntimeState()
        ScheduledAlertScheduler(context).cancel()
    }
}
