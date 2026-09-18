package com.flossypickle.poweroutagemonitor.integrations.alerts

import com.flossypickle.poweroutagemonitor.OutageEngine

/** Pure timing rules. Persisting its state makes each timer survive process death and reboot. */
internal object ScheduledAlertPolicy {
    sealed interface Notice {
        data class SourceUnavailable(val sinceEpochMs: Long) : Notice
        data class SourceAvailableAgain(val sinceEpochMs: Long) : Notice
        data object Heartbeat : Notice
        data class OutageUpdate(val outageStartedEpochMs: Long) : Notice
    }

    data class Result(
        val state: ScheduledAlertStore.State,
        val notices: List<Notice>
    )

    fun update(
        before: ScheduledAlertStore.State,
        settings: ScheduledAlertStore.Settings,
        sourceReadable: Boolean,
        monitorState: OutageEngine.State,
        nowEpochMs: Long,
        canNotify: Boolean = true,
        deferSourceWarningUntilEpochMs: Long? = null
    ): Result {
        var state = before
        val notices = mutableListOf<Notice>()

        state = when {
            !settings.sourceUnavailableEnabled -> state.copy(
                sourceUnavailableSinceEpochMs = null,
                sourceUnavailableAlerted = false
            )
            !sourceReadable -> {
                val since = state.sourceUnavailableSinceEpochMs ?: nowEpochMs
                val due = !state.sourceUnavailableAlerted &&
                    canNotify && (deferSourceWarningUntilEpochMs == null || nowEpochMs >= deferSourceWarningUntilEpochMs) &&
                    elapsed(nowEpochMs, since) >= settings.sourceUnavailableDelayMs
                if (due) notices += Notice.SourceUnavailable(since)
                state.copy(
                    sourceUnavailableSinceEpochMs = since,
                    sourceUnavailableAlerted = state.sourceUnavailableAlerted || due
                )
            }
            else -> {
                if (state.sourceUnavailableAlerted) {
                    state.sourceUnavailableSinceEpochMs?.let {
                        notices += Notice.SourceAvailableAgain(it)
                    }
                }
                state.copy(sourceUnavailableSinceEpochMs = null, sourceUnavailableAlerted = false)
            }
        }

        state = if (!settings.heartbeatEnabled) {
            state.copy(lastHeartbeatEpochMs = null)
        } else {
            val baseline = state.lastHeartbeatEpochMs
            if (baseline == null || nowEpochMs < baseline) {
                state.copy(lastHeartbeatEpochMs = nowEpochMs)
            } else if (elapsed(nowEpochMs, baseline) >= settings.heartbeatIntervalMs) {
                notices += Notice.Heartbeat
                state.copy(lastHeartbeatEpochMs = nowEpochMs)
            } else state
        }

        val outageActive = monitorState.phase == OutageEngine.Phase.OUTAGE ||
            monitorState.phase == OutageEngine.Phase.PENDING_RESTORE
        val outageStarted = monitorState.outageStartedEpochMs
        state = if (!settings.outageUpdatesEnabled || !outageActive || outageStarted == null) {
            state.copy(trackedOutageStartedEpochMs = null, lastOutageUpdateEpochMs = null)
        } else if (state.trackedOutageStartedEpochMs != outageStarted) {
            state.copy(
                trackedOutageStartedEpochMs = outageStarted,
                lastOutageUpdateEpochMs = monitorState.confirmedAtEpochMs ?: nowEpochMs
            )
        } else {
            val baseline = state.lastOutageUpdateEpochMs ?: nowEpochMs
            if (nowEpochMs >= baseline &&
                elapsed(nowEpochMs, baseline) >= settings.outageUpdateIntervalMs
            ) {
                notices += Notice.OutageUpdate(outageStarted)
                state.copy(lastOutageUpdateEpochMs = nowEpochMs)
            } else if (nowEpochMs < baseline) {
                state.copy(lastOutageUpdateEpochMs = nowEpochMs)
            } else state
        }

        return Result(state, notices)
    }

    fun nextDeadline(
        state: ScheduledAlertStore.State,
        settings: ScheduledAlertStore.Settings,
        monitorState: OutageEngine.State,
        deferSourceWarningUntilEpochMs: Long? = null
    ): Long? = buildList {
        if (settings.sourceUnavailableEnabled && !state.sourceUnavailableAlerted) {
            state.sourceUnavailableSinceEpochMs?.let {
                add(maxOf(safeAdd(it, settings.sourceUnavailableDelayMs), deferSourceWarningUntilEpochMs ?: 0))
            }
        }
        if (settings.heartbeatEnabled) {
            state.lastHeartbeatEpochMs?.let { add(safeAdd(it, settings.heartbeatIntervalMs)) }
        }
        val outageActive = monitorState.phase == OutageEngine.Phase.OUTAGE ||
            monitorState.phase == OutageEngine.Phase.PENDING_RESTORE
        if (settings.outageUpdatesEnabled && outageActive) {
            state.lastOutageUpdateEpochMs?.let {
                add(safeAdd(it, settings.outageUpdateIntervalMs))
            }
        }
    }.minOrNull()

    private fun elapsed(now: Long, since: Long) = (now - since).coerceAtLeast(0)

    private fun safeAdd(value: Long, interval: Long): Long =
        if (Long.MAX_VALUE - value < interval) Long.MAX_VALUE else value + interval
}
