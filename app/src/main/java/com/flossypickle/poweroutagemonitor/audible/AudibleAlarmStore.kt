package com.flossypickle.poweroutagemonitor.audible

import android.content.Context
import android.os.Build

/** Device-protected alarm preferences and small runtime record used across restarts. */
internal class AudibleAlarmStore(context: Context) {
    private val storageContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        context.createDeviceProtectedStorageContext()
    } else context.applicationContext
    private val preferences = storageContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    data class Settings(
        val enabled: Boolean = false,
        val repeatIntervalMs: Long = DEFAULT_REPEAT_INTERVAL_MS,
        val stopBatteryPercent: Int = DEFAULT_STOP_BATTERY_PERCENT,
        val useMaximumVolume: Boolean = true
    )

    fun settings() = Settings(
        enabled = preferences.getBoolean(KEY_ENABLED, false),
        repeatIntervalMs = preferences.getLong(KEY_REPEAT_INTERVAL, DEFAULT_REPEAT_INTERVAL_MS)
            .coerceAtLeast(MIN_REPEAT_INTERVAL_MS),
        stopBatteryPercent = preferences.getInt(
            KEY_STOP_BATTERY_PERCENT,
            DEFAULT_STOP_BATTERY_PERCENT
        ).coerceIn(1, 99),
        useMaximumVolume = preferences.getBoolean(KEY_MAXIMUM_VOLUME, true)
    )

    fun updateSettings(settings: Settings) {
        require(settings.repeatIntervalMs >= MIN_REPEAT_INTERVAL_MS)
        require(settings.stopBatteryPercent in 1..99)
        check(preferences.edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putLong(KEY_REPEAT_INTERVAL, settings.repeatIntervalMs)
            .putInt(KEY_STOP_BATTERY_PERCENT, settings.stopBatteryPercent)
            .putBoolean(KEY_MAXIMUM_VOLUME, settings.useMaximumVolume)
            .commit()) { "Unable to persist audible alarm settings" }
    }

    fun runtime() = AudibleAlarmEngine.Runtime(
        activeOutageId = preferences.optionalLong(KEY_ACTIVE_OUTAGE),
        dismissedOutageId = preferences.optionalLong(KEY_DISMISSED_OUTAGE),
        lastPlayedAtEpochMs = preferences.optionalLong(KEY_LAST_PLAYED)
    )

    fun saveRuntime(runtime: AudibleAlarmEngine.Runtime) {
        check(preferences.edit()
            .putOptionalLong(KEY_ACTIVE_OUTAGE, runtime.activeOutageId)
            .putOptionalLong(KEY_DISMISSED_OUTAGE, runtime.dismissedOutageId)
            .putOptionalLong(KEY_LAST_PLAYED, runtime.lastPlayedAtEpochMs)
            .commit()) { "Unable to persist audible alarm state" }
    }

    private fun android.content.SharedPreferences.optionalLong(key: String): Long? =
        if (contains(key)) getLong(key, 0L) else null

    private fun android.content.SharedPreferences.Editor.putOptionalLong(key: String, value: Long?) =
        apply { if (value == null) remove(key) else putLong(key, value) }

    companion object {
        const val MIN_REPEAT_INTERVAL_MS = 60_000L
        const val DEFAULT_REPEAT_INTERVAL_MS = 5 * 60_000L
        const val DEFAULT_STOP_BATTERY_PERCENT = 20
        private const val FILE_NAME = "audible_alarm"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_REPEAT_INTERVAL = "repeat_interval_ms"
        private const val KEY_STOP_BATTERY_PERCENT = "stop_battery_percent"
        private const val KEY_MAXIMUM_VOLUME = "maximum_volume"
        private const val KEY_ACTIVE_OUTAGE = "active_outage"
        private const val KEY_DISMISSED_OUTAGE = "dismissed_outage"
        private const val KEY_LAST_PLAYED = "last_played"
    }
}
