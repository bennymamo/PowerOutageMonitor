package com.flossypickle.poweroutagemonitor.storage

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.flossypickle.poweroutagemonitor.OutageEngine
import com.flossypickle.poweroutagemonitor.monitoring.PowerSnapshot

/** Small, synchronous, device-protected store for state needed during Direct Boot. */
internal class MonitorStore(context: Context) {
    private val storageContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        context.createDeviceProtectedStorageContext()
    } else context.applicationContext
    private val preferences = storageContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    enum class ThemeMode { SYSTEM, DARK, LIGHT }

    data class Settings(
        val setupCompleted: Boolean,
        val monitoringEnabled: Boolean,
        val outageDelayMs: Long,
        val restoreDelayMs: Long,
        val sendRestoreNotification: Boolean,
        val deviceName: String,
        val historyLimit: Int = DEFAULT_HISTORY_LIMIT,
        val themeMode: ThemeMode = ThemeMode.SYSTEM
    )

    fun settings(): Settings = Settings(
        setupCompleted = preferences.getBoolean(KEY_SETUP_COMPLETED, preferences.contains(KEY_ENABLED)),
        monitoringEnabled = preferences.getBoolean(KEY_ENABLED, false),
        outageDelayMs = preferences.getLong(KEY_OUTAGE_DELAY, DEFAULT_OUTAGE_DELAY_MS),
        restoreDelayMs = preferences.getLong(KEY_RESTORE_DELAY, DEFAULT_RESTORE_DELAY_MS),
        sendRestoreNotification = preferences.getBoolean(KEY_SEND_RESTORE, true),
        deviceName = preferences.getString(KEY_DEVICE_NAME, DEFAULT_DEVICE_NAME) ?: DEFAULT_DEVICE_NAME,
        historyLimit = preferences.getInt(KEY_HISTORY_LIMIT, DEFAULT_HISTORY_LIMIT),
        themeMode = runCatching {
            ThemeMode.valueOf(preferences.getString(KEY_THEME_MODE, null) ?: "")
        }.getOrDefault(ThemeMode.SYSTEM)
    )

    fun state(): OutageEngine.State {
        val phase = runCatching {
            OutageEngine.Phase.valueOf(preferences.getString(KEY_PHASE, null) ?: "")
        }.getOrDefault(OutageEngine.Phase.WAITING)
        return OutageEngine.State(
            phase = phase,
            phaseSinceEpochMs = preferences.getLong(KEY_PHASE_SINCE, 0),
            outageStartedEpochMs = preferences.optionalLong(KEY_OUTAGE_STARTED),
            outageStartBatteryPercent = preferences.optionalInt(KEY_OUTAGE_START_BATTERY),
            confirmedAtEpochMs = preferences.optionalLong(KEY_CONFIRMED_AT),
            outageStartBatteryTemperatureTenthsCelsius =
                preferences.optionalInt(KEY_OUTAGE_START_TEMPERATURE)
        )
    }

    fun lastSnapshot(): PowerSnapshot? {
        if (!preferences.contains(KEY_LAST_PLUGGED)) return null
        return PowerSnapshot(
            plugged = preferences.getInt(KEY_LAST_PLUGGED, -1),
            batteryPercent = preferences.optionalInt(KEY_LAST_BATTERY),
            batteryStatus = preferences.getInt(KEY_LAST_STATUS, 1),
            batteryTemperatureTenthsCelsius = preferences.optionalInt(KEY_LAST_TEMPERATURE)
        )
    }

    fun lastObservationEpochMs(): Long = preferences.getLong(KEY_LAST_OBSERVATION, 0)

    fun setMonitoringEnabled(enabled: Boolean) {
        val editor = preferences.edit().putBoolean(KEY_ENABLED, enabled)
        if (!enabled) writeState(editor, OutageEngine.State())
        editor.commit()
    }

    fun setSetupCompleted() {
        check(preferences.edit().putBoolean(KEY_SETUP_COMPLETED, true).commit()) {
            "Unable to persist setup completion"
        }
    }

    fun updateSettings(
        outageDelayMs: Long,
        restoreDelayMs: Long,
        sendRestoreNotification: Boolean,
        deviceName: String
    ) {
        require(outageDelayMs >= 0 && restoreDelayMs >= 0)
        preferences.edit()
            .putLong(KEY_OUTAGE_DELAY, outageDelayMs)
            .putLong(KEY_RESTORE_DELAY, restoreDelayMs)
            .putBoolean(KEY_SEND_RESTORE, sendRestoreNotification)
            .putString(KEY_DEVICE_NAME, deviceName.trim().ifEmpty { DEFAULT_DEVICE_NAME })
            .commit()
    }

    fun setHistoryLimit(limit: Int) {
        require(limit in HISTORY_LIMIT_RANGE)
        check(preferences.edit().putInt(KEY_HISTORY_LIMIT, limit).commit()) {
            "Unable to persist history retention"
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        check(preferences.edit().putString(KEY_THEME_MODE, mode.name).commit()) {
            "Unable to persist appearance setting"
        }
    }

    fun save(state: OutageEngine.State, snapshot: PowerSnapshot, observedAtEpochMs: Long) {
        val editor = preferences.edit()
        writeState(editor, state)
        editor.putInt(KEY_LAST_PLUGGED, snapshot.plugged)
            .putInt(KEY_LAST_STATUS, snapshot.batteryStatus)
            .putLong(KEY_LAST_OBSERVATION, observedAtEpochMs)
            .putOptionalInt(KEY_LAST_BATTERY, snapshot.batteryPercent)
            .putOptionalInt(KEY_LAST_TEMPERATURE, snapshot.batteryTemperatureTenthsCelsius)
            .commit()
    }

    private fun writeState(editor: SharedPreferences.Editor, state: OutageEngine.State) {
        editor.putString(KEY_PHASE, state.phase.name)
            .putLong(KEY_PHASE_SINCE, state.phaseSinceEpochMs)
            .putOptionalLong(KEY_OUTAGE_STARTED, state.outageStartedEpochMs)
            .putOptionalInt(KEY_OUTAGE_START_BATTERY, state.outageStartBatteryPercent)
            .putOptionalLong(KEY_CONFIRMED_AT, state.confirmedAtEpochMs)
            .putOptionalInt(
                KEY_OUTAGE_START_TEMPERATURE,
                state.outageStartBatteryTemperatureTenthsCelsius
            )
    }

    private fun SharedPreferences.optionalLong(key: String): Long? =
        if (contains(key)) getLong(key, 0) else null

    private fun SharedPreferences.optionalInt(key: String): Int? =
        if (contains(key)) getInt(key, 0) else null

    private fun SharedPreferences.Editor.putOptionalLong(key: String, value: Long?) = apply {
        if (value == null) remove(key) else putLong(key, value)
    }

    private fun SharedPreferences.Editor.putOptionalInt(key: String, value: Int?) = apply {
        if (value == null) remove(key) else putInt(key, value)
    }

    companion object {
        const val DEFAULT_OUTAGE_DELAY_MS = 60_000L
        const val DEFAULT_RESTORE_DELAY_MS = 30_000L
        const val DEFAULT_DEVICE_NAME = "Power monitor"
        const val DEFAULT_HISTORY_LIMIT = 200
        val HISTORY_LIMIT_RANGE = 10..1_000
        private const val FILE_NAME = "monitor_state"
        private const val KEY_ENABLED = "monitoring_enabled"
        private const val KEY_SETUP_COMPLETED = "setup_completed"
        private const val KEY_OUTAGE_DELAY = "outage_delay_ms"
        private const val KEY_RESTORE_DELAY = "restore_delay_ms"
        private const val KEY_SEND_RESTORE = "send_restore"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_HISTORY_LIMIT = "history_limit"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_PHASE = "phase"
        private const val KEY_PHASE_SINCE = "phase_since"
        private const val KEY_OUTAGE_STARTED = "outage_started"
        private const val KEY_OUTAGE_START_BATTERY = "outage_start_battery"
        private const val KEY_OUTAGE_START_TEMPERATURE = "outage_start_temperature"
        private const val KEY_CONFIRMED_AT = "confirmed_at"
        private const val KEY_LAST_PLUGGED = "last_plugged"
        private const val KEY_LAST_BATTERY = "last_battery"
        private const val KEY_LAST_STATUS = "last_status"
        private const val KEY_LAST_TEMPERATURE = "last_temperature"
        private const val KEY_LAST_OBSERVATION = "last_observation"
    }
}
