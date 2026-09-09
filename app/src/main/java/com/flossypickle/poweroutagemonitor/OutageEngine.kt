package com.flossypickle.poweroutagemonitor

/** Deterministic rules. Caller supplies monotonic milliseconds from the same boot. */
internal object OutageEngine {
    enum class Phase { WAITING, POWERED, PENDING_OUTAGE, OUTAGE, PENDING_RESTORE }
    data class State(val phase: Phase = Phase.WAITING, val since: Long = 0)

    fun update(state: State, powered: Boolean?, now: Long, outageDelay: Long, restoreDelay: Long): State {
        require(outageDelay >= 0 && restoreDelay >= 0)
        require(now >= state.since)
        if (powered == null) return state
        return when (state.phase) {
            Phase.WAITING -> if (powered) State(Phase.POWERED, now) else state
            Phase.POWERED -> if (powered) state else State(
                if (outageDelay == 0L) Phase.OUTAGE else Phase.PENDING_OUTAGE, now)
            Phase.PENDING_OUTAGE -> when {
                powered -> State(Phase.POWERED, now)
                now - state.since >= outageDelay -> State(Phase.OUTAGE, state.since)
                else -> state
            }
            Phase.OUTAGE -> if (!powered) state else State(
                if (restoreDelay == 0L) Phase.POWERED else Phase.PENDING_RESTORE, now)
            Phase.PENDING_RESTORE -> when {
                !powered -> State(Phase.OUTAGE, now)
                now - state.since >= restoreDelay -> State(Phase.POWERED, now)
                else -> state
            }
        }
    }
}
