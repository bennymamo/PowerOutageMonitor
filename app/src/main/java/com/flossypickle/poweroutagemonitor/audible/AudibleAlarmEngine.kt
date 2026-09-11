package com.flossypickle.poweroutagemonitor.audible

/** Pure decision rules for the optional local audible outage alarm. */
internal object AudibleAlarmEngine {
    data class Config(
        val enabled: Boolean,
        val repeatIntervalMs: Long,
        val stopBatteryPercent: Int
    )

    data class Runtime(
        val activeOutageId: Long? = null,
        val dismissedOutageId: Long? = null,
        val lastPlayedAtEpochMs: Long? = null
    )

    data class Decision(
        val runtime: Runtime,
        val playNow: Boolean = false,
        val nextAlarmAtEpochMs: Long? = null
    )

    fun evaluate(
        config: Config,
        monitoringEnabled: Boolean,
        confirmedOutage: Boolean,
        outageId: Long?,
        batteryPercent: Int?,
        runtime: Runtime,
        nowEpochMs: Long,
        scheduledTick: Boolean = false
    ): Decision {
        require(config.repeatIntervalMs > 0)
        require(config.stopBatteryPercent in 1..99)

        if (!monitoringEnabled || !config.enabled) return Decision(Runtime())

        if (!confirmedOutage || outageId == null) {
            return if (outageId == null) {
                Decision(Runtime())
            } else {
                Decision(runtime.copy(activeOutageId = null, lastPlayedAtEpochMs = null))
            }
        }

        if (runtime.dismissedOutageId == outageId ||
            batteryPercent?.let { it <= config.stopBatteryPercent } == true
        ) {
            return Decision(
                Runtime(activeOutageId = outageId, dismissedOutageId = outageId)
            )
        }

        val firstForOutage = runtime.activeOutageId != outageId ||
            runtime.lastPlayedAtEpochMs == null
        val due = scheduledTick &&
            nowEpochMs >= (runtime.lastPlayedAtEpochMs ?: 0L) + config.repeatIntervalMs
        val play = firstForOutage || due
        val lastPlayed = if (play) nowEpochMs else runtime.lastPlayedAtEpochMs
        return Decision(
            runtime = Runtime(
                activeOutageId = outageId,
                dismissedOutageId = runtime.dismissedOutageId,
                lastPlayedAtEpochMs = lastPlayed
            ),
            playNow = play,
            nextAlarmAtEpochMs = lastPlayed?.plus(config.repeatIntervalMs)
        )
    }
}
