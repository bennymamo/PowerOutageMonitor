package com.flossypickle.poweroutagemonitor

/** Pure, deterministic outage rules. All times are UTC epoch milliseconds. */
internal object OutageEngine {
    enum class Phase { WAITING, POWERED, PENDING_OUTAGE, OUTAGE, PENDING_RESTORE }

    data class State(
        val phase: Phase = Phase.WAITING,
        val phaseSinceEpochMs: Long = 0,
        val outageStartedEpochMs: Long? = null,
        val outageStartBatteryPercent: Int? = null,
        val confirmedAtEpochMs: Long? = null
    )

    fun update(
        state: State,
        powered: Boolean?,
        nowEpochMs: Long,
        batteryPercent: Int?,
        outageDelayMs: Long,
        restoreDelayMs: Long
    ): State {
        require(outageDelayMs >= 0 && restoreDelayMs >= 0)
        if (powered == null) return state

        return when (state.phase) {
            Phase.WAITING -> if (powered) State(Phase.POWERED, nowEpochMs) else state
            Phase.POWERED -> if (powered) state else State(
                phase = if (outageDelayMs == 0L) Phase.OUTAGE else Phase.PENDING_OUTAGE,
                phaseSinceEpochMs = nowEpochMs,
                outageStartedEpochMs = nowEpochMs,
                outageStartBatteryPercent = batteryPercent,
                confirmedAtEpochMs = if (outageDelayMs == 0L) nowEpochMs else null
            )
            Phase.PENDING_OUTAGE -> when {
                powered -> State(Phase.POWERED, nowEpochMs)
                nowEpochMs - state.phaseSinceEpochMs >= outageDelayMs -> state.copy(
                    phase = Phase.OUTAGE,
                    phaseSinceEpochMs = nowEpochMs,
                    confirmedAtEpochMs = nowEpochMs
                )
                else -> state
            }
            Phase.OUTAGE -> if (!powered) state else if (restoreDelayMs == 0L) {
                State(Phase.POWERED, nowEpochMs)
            } else {
                state.copy(phase = Phase.PENDING_RESTORE, phaseSinceEpochMs = nowEpochMs)
            }
            Phase.PENDING_RESTORE -> when {
                !powered -> state.copy(phase = Phase.OUTAGE, phaseSinceEpochMs = nowEpochMs)
                nowEpochMs - state.phaseSinceEpochMs >= restoreDelayMs -> State(Phase.POWERED, nowEpochMs)
                else -> state
            }
        }
    }

    fun deadlineEpochMs(state: State, outageDelayMs: Long, restoreDelayMs: Long): Long? =
        when (state.phase) {
            Phase.PENDING_OUTAGE -> state.phaseSinceEpochMs + outageDelayMs
            Phase.PENDING_RESTORE -> state.phaseSinceEpochMs + restoreDelayMs
            else -> null
        }
}
