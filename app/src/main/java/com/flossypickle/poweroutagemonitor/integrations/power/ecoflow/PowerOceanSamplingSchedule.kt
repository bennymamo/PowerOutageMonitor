package com.flossypickle.poweroutagemonitor.integrations.power.ecoflow

/** Independent request timing: zero means manual; connection keepalives are not reading requests. */
internal data class PowerOceanAssistedSettings(val enabled: Boolean = false,
    val normalSeconds: Int = 3600, val outageSeconds: Int = 60) {
    init { require(valid(normalSeconds) && valid(outageSeconds)) }
    companion object { fun valid(seconds: Int) = seconds == 0 || seconds in 5..86_400 }
}

internal data class PowerOceanReadSchedule(val intervalSeconds: Int?, val incident: Boolean,
    val manualRevision: Long, val liveOnEachRead: Boolean) {
    fun needsLiveActivation(continuousEnabled: Boolean, readDue: Boolean, periodicDue: Boolean): Boolean =
        if (liveOnEachRead) readDue else continuousEnabled && periodicDue
}

/** Monotonic clock avoids wall-clock jumps affecting request frequency. */
internal class PowerOceanSamplingSchedule {
    private var previous: PowerOceanReadSchedule? = null
    private var lastRead: Long? = null
    private var nextRead = 0L
    private var manualSeen = 0L

    fun due(schedule: PowerOceanReadSchedule, now: Long): Boolean {
        require(schedule.intervalSeconds == null || schedule.intervalSeconds in 5..86_400)
        val before = previous
        if (before == null || before.incident != schedule.incident) nextRead = now
        else if (before.intervalSeconds != schedule.intervalSeconds) {
            nextRead = lastRead?.let { it + (schedule.intervalSeconds ?: 86_400) * 1000L } ?: now
        }
        previous = schedule
        val manual = schedule.manualRevision > manualSeen
        manualSeen = schedule.manualRevision
        if (!manual && (schedule.intervalSeconds == null || now < nextRead)) return false
        lastRead = now
        nextRead = schedule.intervalSeconds?.let { now + it * 1000L } ?: Long.MAX_VALUE
        return true
    }
}
