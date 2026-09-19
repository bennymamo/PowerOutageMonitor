package com.flossypickle.poweroutagemonitor.integrations.power.ecoflow

/** Independent request timing: zero means manual; connection keepalives are not reading requests. */
internal data class PowerOceanAssistedSettings(val enabled: Boolean = false,
    val normalSeconds: Int = 3600, val outageSeconds: Int = 60,
    val warnOnUnchanged: Boolean = true, val ignoreUnchanged: Boolean = false,
    val checkWindowSeconds: Int = 120, val extraPowerUpdates: Int = 2,
    val notifyOnUnknown: Boolean = true, val notifyOnChargerReturn: Boolean = true,
    val poweredFailureThreshold: Int = 5) {
    init {
        require(valid(normalSeconds) && valid(outageSeconds))
        require(checkWindowSeconds in 30..300 && extraPowerUpdates in 1..10)
        require(poweredFailureThreshold in 1..20)
    }
    companion object { fun valid(seconds: Int) = seconds == 0 || seconds in 5..86_400 }
}

/** Routine cloud failures are retried quickly while local charger evidence still proves power. */
internal object PowerOceanFailureRetryPolicy {
    fun nextStreak(previous: Int, chargerPowered: Boolean?, failed: Boolean): Int = when {
        !failed || chargerPowered != true -> 0
        else -> (previous + 1).coerceAtMost(20)
    }

    fun deferPoweredWarning(chargerPowered: Boolean?, failed: Boolean, streak: Int, threshold: Int) =
        failed && chargerPowered == true && streak < threshold
}

internal data class PowerOceanReadSchedule(val intervalSeconds: Int?, val incident: Boolean,
    val manualRevision: Long, val liveOnEachRead: Boolean, val paused: Boolean = false, val incidentDetectedDuringCheck: Boolean = false) {
    fun needsLiveActivation(continuousEnabled: Boolean, readDue: Boolean, periodicDue: Boolean, boundedWindow: Boolean = false): Boolean =
        !paused && if (liveOnEachRead) readDue || boundedWindow && periodicDue else continuousEnabled && periodicDue
}

/** Monotonic clock avoids wall-clock jumps affecting request frequency. */
internal class PowerOceanSamplingSchedule {
    private var previous: PowerOceanReadSchedule? = null
    private var lastRead: Long? = null
    private var nextRead = 0L
    private var manualSeen = 0L

    val nextDueAt: Long? get() = nextRead.takeUnless { it == Long.MAX_VALUE || previous?.paused == true || previous?.intervalSeconds == null }

    /** Finish an overdue check before the next one, with a short closed interval and no catch-up burst. */
    fun finishCheck(now: Long, after: PowerOceanReadSchedule? = null) {
        if (after != null) {
            require(after.intervalSeconds == null || after.intervalSeconds in 5..86_400)
            previous = after
            nextRead = after.intervalSeconds?.let { (lastRead ?: now) + it * 1000L } ?: Long.MAX_VALUE
        }
        if (previous?.intervalSeconds == null) return
        if (nextRead <= now) nextRead = now + 5_000
    }

    fun due(schedule: PowerOceanReadSchedule, now: Long): Boolean {
        require(schedule.intervalSeconds == null || schedule.intervalSeconds in 5..86_400)
        val before = previous
        if (before != null && !before.incident && schedule.incident && schedule.incidentDetectedDuringCheck && lastRead != null) {
            // The check that detected EcoFlow loss is already the first incident check.
            nextRead = schedule.intervalSeconds?.let { lastRead!! + it * 1000L } ?: Long.MAX_VALUE
        } else if (before == null || before.paused && !schedule.paused || before.incident != schedule.incident) nextRead = now
        else if (before.intervalSeconds != schedule.intervalSeconds) {
            nextRead = lastRead?.let { it + (schedule.intervalSeconds ?: 86_400) * 1000L } ?: now
        }
        previous = schedule
        val manual = schedule.manualRevision > manualSeen
        manualSeen = schedule.manualRevision
        if (schedule.paused || !manual && (schedule.intervalSeconds == null || now < nextRead)) return false
        lastRead = now
        nextRead = schedule.intervalSeconds?.let { now + it * 1000L } ?: Long.MAX_VALUE
        return true
    }
}
