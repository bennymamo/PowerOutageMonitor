package com.flossypickle.poweroutagemonitor.integrations.alerts

import android.content.Context
import android.os.Build

/** Device-protected settings and timer state for operational and repeated alerts. */
internal class ScheduledAlertStore(context: Context) {
    private val storageContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        context.createDeviceProtectedStorageContext()
    } else context.applicationContext
    private val preferences = storageContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    data class Settings(
        val sourceUnavailableEnabled: Boolean = true,
        val sourceUnavailableDelayMs: Long = DEFAULT_SOURCE_UNAVAILABLE_DELAY_MS,
        val heartbeatEnabled: Boolean = true,
        val heartbeatIntervalMs: Long = DEFAULT_HEARTBEAT_INTERVAL_MS,
        val outageUpdatesEnabled: Boolean = true,
        val outageUpdateIntervalMs: Long = DEFAULT_OUTAGE_UPDATE_INTERVAL_MS
    )

    data class State(
        val sourceUnavailableSinceEpochMs: Long? = null,
        val sourceUnavailableAlerted: Boolean = false,
        val lastHeartbeatEpochMs: Long? = null,
        val trackedOutageStartedEpochMs: Long? = null,
        val lastOutageUpdateEpochMs: Long? = null
    )

    fun settings() = Settings(
        sourceUnavailableEnabled = preferences.getBoolean(KEY_SOURCE_ENABLED, true),
        sourceUnavailableDelayMs = preferences.getLong(
            KEY_SOURCE_DELAY,
            DEFAULT_SOURCE_UNAVAILABLE_DELAY_MS
        ).coerceIn(INTERVAL_RANGE_MS),
        heartbeatEnabled = preferences.getBoolean(KEY_HEARTBEAT_ENABLED, true),
        heartbeatIntervalMs = preferences.getLong(
            KEY_HEARTBEAT_INTERVAL,
            DEFAULT_HEARTBEAT_INTERVAL_MS
        ).coerceIn(INTERVAL_RANGE_MS),
        outageUpdatesEnabled = preferences.getBoolean(KEY_OUTAGE_UPDATES_ENABLED, true),
        outageUpdateIntervalMs = preferences.getLong(
            KEY_OUTAGE_UPDATE_INTERVAL,
            DEFAULT_OUTAGE_UPDATE_INTERVAL_MS
        ).coerceIn(INTERVAL_RANGE_MS)
    )

    fun updateSettings(value: Settings) {
        require(value.sourceUnavailableDelayMs in INTERVAL_RANGE_MS)
        require(value.heartbeatIntervalMs in INTERVAL_RANGE_MS)
        require(value.outageUpdateIntervalMs in INTERVAL_RANGE_MS)
        check(preferences.edit()
            .putBoolean(KEY_SOURCE_ENABLED, value.sourceUnavailableEnabled)
            .putLong(KEY_SOURCE_DELAY, value.sourceUnavailableDelayMs)
            .putBoolean(KEY_HEARTBEAT_ENABLED, value.heartbeatEnabled)
            .putLong(KEY_HEARTBEAT_INTERVAL, value.heartbeatIntervalMs)
            .putBoolean(KEY_OUTAGE_UPDATES_ENABLED, value.outageUpdatesEnabled)
            .putLong(KEY_OUTAGE_UPDATE_INTERVAL, value.outageUpdateIntervalMs)
            .commit()
        ) { "Unable to save scheduled alert settings" }
    }

    fun state() = State(
        sourceUnavailableSinceEpochMs = optionalLong(KEY_SOURCE_UNAVAILABLE_SINCE),
        sourceUnavailableAlerted = preferences.getBoolean(KEY_SOURCE_ALERTED, false),
        lastHeartbeatEpochMs = optionalLong(KEY_LAST_HEARTBEAT),
        trackedOutageStartedEpochMs = optionalLong(KEY_TRACKED_OUTAGE),
        lastOutageUpdateEpochMs = optionalLong(KEY_LAST_OUTAGE_UPDATE)
    )

    fun save(value: State) {
        val editor = preferences.edit()
            .putBoolean(KEY_SOURCE_ALERTED, value.sourceUnavailableAlerted)
        editor.putOptionalLong(KEY_SOURCE_UNAVAILABLE_SINCE, value.sourceUnavailableSinceEpochMs)
        editor.putOptionalLong(KEY_LAST_HEARTBEAT, value.lastHeartbeatEpochMs)
        editor.putOptionalLong(KEY_TRACKED_OUTAGE, value.trackedOutageStartedEpochMs)
        editor.putOptionalLong(KEY_LAST_OUTAGE_UPDATE, value.lastOutageUpdateEpochMs)
        check(editor.commit()) { "Unable to save scheduled alert timer state" }
    }

    fun resetRuntimeState() {
        preferences.edit()
            .remove(KEY_SOURCE_UNAVAILABLE_SINCE)
            .remove(KEY_SOURCE_ALERTED)
            .remove(KEY_LAST_HEARTBEAT)
            .remove(KEY_TRACKED_OUTAGE)
            .remove(KEY_LAST_OUTAGE_UPDATE)
            .commit()
    }

    private fun optionalLong(key: String): Long? =
        if (preferences.contains(key)) preferences.getLong(key, 0) else null

    private fun android.content.SharedPreferences.Editor.putOptionalLong(key: String, value: Long?) {
        if (value == null) remove(key) else putLong(key, value)
    }

    companion object {
        const val DEFAULT_SOURCE_UNAVAILABLE_DELAY_MS = 5 * 60_000L
        const val DEFAULT_HEARTBEAT_INTERVAL_MS = 24 * 60 * 60_000L
        const val DEFAULT_OUTAGE_UPDATE_INTERVAL_MS = 6 * 60 * 60_000L
        val INTERVAL_RANGE_MS = 60_000L..30L * 24 * 60 * 60_000L

        private const val FILE_NAME = "scheduled_alerts"
        private const val KEY_SOURCE_ENABLED = "source_unavailable_enabled"
        private const val KEY_SOURCE_DELAY = "source_unavailable_delay"
        private const val KEY_HEARTBEAT_ENABLED = "heartbeat_enabled"
        private const val KEY_HEARTBEAT_INTERVAL = "heartbeat_interval"
        private const val KEY_OUTAGE_UPDATES_ENABLED = "outage_updates_enabled"
        private const val KEY_OUTAGE_UPDATE_INTERVAL = "outage_update_interval"
        private const val KEY_SOURCE_UNAVAILABLE_SINCE = "source_unavailable_since"
        private const val KEY_SOURCE_ALERTED = "source_unavailable_alerted"
        private const val KEY_LAST_HEARTBEAT = "last_heartbeat"
        private const val KEY_TRACKED_OUTAGE = "tracked_outage"
        private const val KEY_LAST_OUTAGE_UPDATE = "last_outage_update"
    }
}
