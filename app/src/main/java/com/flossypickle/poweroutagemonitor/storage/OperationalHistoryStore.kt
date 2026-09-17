package com.flossypickle.poweroutagemonitor.storage

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Records app and monitoring lifecycle evidence separately from grid events. */
internal class OperationalHistoryStore(context: Context) {
    private val storageContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        context.createDeviceProtectedStorageContext()
    } else context.applicationContext
    private val file = AtomicFile(File(storageContext.filesDir, "operational_history.json"))
    private val preferences = storageContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val processExitInspector = ProcessExitInspector(context.applicationContext)

    data class Record(
        val kind: String,
        val timestampEpochMs: Long,
        val detail: String
    )

    enum class RestartCause { APP_UPDATE, DEVICE_REBOOT }

    /** A system update or reboot can end an open UI without calling onDestroy. */
    fun expectAppRestart(cause: RestartCause) = synchronized(LOCK) {
        if (preferences.getBoolean(KEY_APP_ACTIVE, false)) {
            preferences.edit().putString(KEY_EXPECTED_APP_RESTART, cause.name).commit()
        }
    }

    fun recordAppOpened(maxRecords: Int, nowEpochMs: Long = System.currentTimeMillis()) {
        synchronized(LOCK) {
            val previousSessionUnclosed = preferences.getBoolean(KEY_APP_ACTIVE, false)
            val expectedCause = runCatching {
                RestartCause.valueOf(preferences.getString(KEY_EXPECTED_APP_RESTART, null) ?: "")
            }.getOrNull()
            val exitInsight = if (previousSessionUnclosed && expectedCause == null) {
                processExitInspector.recentExit(
                    nowEpochMs,
                    preferences.getLong(KEY_APP_LAST_OPENED_AT, 0L)
                )
            } else null
            val cause = expectedCause ?: exitInsight?.takeIf { it.appUpdated }
                ?.let { RestartCause.APP_UPDATE }
            appendLocked(
                Record(
                    kind = appStartKind(previousSessionUnclosed, cause),
                    timestampEpochMs = nowEpochMs,
                    detail = when {
                        !previousSessionUnclosed -> "Dashboard opened"
                        cause == RestartCause.APP_UPDATE -> "Dashboard reopened after an app update"
                        cause == RestartCause.DEVICE_REBOOT -> "Dashboard opened after a device reboot"
                        exitInsight != null -> "The dashboard reopened without a recorded close. ${exitInsight.explanation}"
                        else -> "The app opened after no close was recorded. The previous UI process may have been killed or crashed."
                    }
                ),
                maxRecords
            )
            preferences.edit().putBoolean(KEY_APP_ACTIVE, true)
                .putLong(KEY_APP_LAST_OPENED_AT, nowEpochMs)
                .remove(KEY_EXPECTED_APP_RESTART).commit()
        }
    }

    fun recordAppClosed(maxRecords: Int, nowEpochMs: Long = System.currentTimeMillis()) {
        synchronized(LOCK) {
            if (preferences.getBoolean(KEY_APP_ACTIVE, false)) {
                appendLocked(Record(KIND_APP_CLOSED, nowEpochMs, "Dashboard closed"), maxRecords)
            }
            preferences.edit().putBoolean(KEY_APP_ACTIVE, false).commit()
        }
    }

    fun recordMonitoringStarted(
        maxRecords: Int,
        restartCause: RestartCause? = null,
        nowEpochMs: Long = System.currentTimeMillis()
    ) {
        synchronized(LOCK) {
            val previousSessionUnclosed = preferences.getBoolean(KEY_SERVICE_ACTIVE, false)
            val exitInsight = if (previousSessionUnclosed && restartCause == null) {
                processExitInspector.recentExit(
                    nowEpochMs,
                    preferences.getLong(KEY_SERVICE_LAST_STARTED_AT, 0L)
                )
            } else null
            val cause = restartCause ?: exitInsight?.takeIf { it.appUpdated }
                ?.let { RestartCause.APP_UPDATE }
            appendLocked(
                Record(
                    kind = startKind(previousSessionUnclosed, cause),
                    timestampEpochMs = nowEpochMs,
                    detail = when (cause) {
                        RestartCause.APP_UPDATE -> "Background monitoring resumed after an app update"
                        RestartCause.DEVICE_REBOOT -> "Background monitoring resumed after a device reboot"
                        null -> if (previousSessionUnclosed) {
                            if (exitInsight != null) {
                                "Monitoring restarted without a recorded stop. ${exitInsight.explanation}"
                            } else {
                                "Monitoring started after no stop was recorded. Android, a crash, or device power management may have ended the previous process."
                            }
                        } else "Background grid monitoring started"
                    }
                ),
                maxRecords
            )
            preferences.edit().putBoolean(KEY_SERVICE_ACTIVE, true)
                .putLong(KEY_SERVICE_LAST_STARTED_AT, nowEpochMs).commit()
        }
    }

    fun recordMonitoringStopped(
        maxRecords: Int,
        userDisabled: Boolean,
        nowEpochMs: Long = System.currentTimeMillis()
    ) {
        synchronized(LOCK) {
            if (preferences.getBoolean(KEY_SERVICE_ACTIVE, false)) {
                appendLocked(
                    Record(
                        KIND_MONITORING_STOPPED,
                        nowEpochMs,
                        if (userDisabled) "Monitoring disabled from the dashboard"
                        else "Background monitoring service stopped"
                    ),
                    maxRecords
                )
            }
            preferences.edit().putBoolean(KEY_SERVICE_ACTIVE, false).commit()
        }
    }

    /** Marks the boundary between imported History and this device's new activity. */
    fun recordBackupRestored(maxRecords: Int, nowEpochMs: Long = System.currentTimeMillis()) {
        append(
            Record(
                KIND_BACKUP_RESTORED,
                nowEpochMs,
                "A recovery archive was restored on this device. Review Android permissions and reconnect anything the device cannot transfer."
            ),
            maxRecords
        )
    }

    fun recordRemoteCommand(command: String, maxRecords: Int) {
        append(Record("REMOTE_COMMAND", System.currentTimeMillis(), "Telegram control: /$command"), maxRecords)
    }

    fun read(): List<Record> = synchronized(LOCK) { readLocked() }

    fun trimTo(maxRecords: Int) = synchronized(LOCK) {
        writeLocked(readLocked().take(maxRecords.coerceIn(MonitorStore.HISTORY_LIMIT_RANGE)))
    }

    fun clear() = synchronized(LOCK) { writeLocked(emptyList()) }

    fun replaceAll(records: List<Record>, maxRecords: Int) = synchronized(LOCK) {
        writeLocked(records.take(maxRecords.coerceIn(MonitorStore.HISTORY_LIMIT_RANGE)))
    }

    private fun append(record: Record, maxRecords: Int) = synchronized(LOCK) {
        appendLocked(record, maxRecords)
    }

    private fun appendLocked(record: Record, maxRecords: Int) {
        writeLocked(
            (listOf(record) + readLocked())
                .take(maxRecords.coerceIn(MonitorStore.HISTORY_LIMIT_RANGE))
        )
    }

    private fun readLocked(): List<Record> = runCatching {
        if (!file.baseFile.exists()) return emptyList()
        val array = JSONArray(file.openRead().bufferedReader().use { it.readText() })
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(Record(item.getString("kind"), item.getLong("at"), item.getString("detail")))
            }
        }
    }.getOrDefault(emptyList())

    private fun writeLocked(records: List<Record>) {
        val array = JSONArray()
        records.forEach { record ->
            array.put(JSONObject().apply {
                put("kind", record.kind)
                put("at", record.timestampEpochMs)
                put("detail", record.detail)
            })
        }
        val output = file.startWrite()
        try {
            output.write(array.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    companion object {
        const val KIND_APP_OPENED = "app_opened"
        const val KIND_APP_RECOVERED = "app_recovered"
        const val KIND_APP_UPDATED = "app_reopened_after_update"
        const val KIND_APP_REBOOTED = "app_opened_after_reboot"
        const val KIND_APP_CLOSED = "app_closed"
        const val KIND_MONITORING_STARTED = "monitoring_started"
        const val KIND_MONITORING_RECOVERED = "monitoring_recovered"
        const val KIND_MONITORING_UPDATED = "monitoring_resumed_after_update"
        const val KIND_MONITORING_REBOOTED = "monitoring_resumed_after_reboot"
        const val KIND_MONITORING_STOPPED = "monitoring_stopped"
        const val KIND_BACKUP_RESTORED = "backup_restored"
        private const val FILE_NAME = "operational_history_state"
        private const val KEY_SERVICE_ACTIVE = "service_active"
        private const val KEY_SERVICE_LAST_STARTED_AT = "service_last_started_at"
        private const val KEY_APP_ACTIVE = "app_active"
        private const val KEY_APP_LAST_OPENED_AT = "app_last_opened_at"
        private const val KEY_EXPECTED_APP_RESTART = "expected_app_restart"
        private val LOCK = Any()

        internal fun startKind(previousSessionUnclosed: Boolean, cause: RestartCause? = null) =
            when (cause) {
                RestartCause.APP_UPDATE -> KIND_MONITORING_UPDATED
                RestartCause.DEVICE_REBOOT -> KIND_MONITORING_REBOOTED
                null -> if (previousSessionUnclosed) KIND_MONITORING_RECOVERED
                    else KIND_MONITORING_STARTED
            }

        internal fun appStartKind(previousSessionUnclosed: Boolean, cause: RestartCause? = null) =
            when {
                !previousSessionUnclosed -> KIND_APP_OPENED
                cause == RestartCause.APP_UPDATE -> KIND_APP_UPDATED
                cause == RestartCause.DEVICE_REBOOT -> KIND_APP_REBOOTED
                else -> KIND_APP_RECOVERED
            }
    }
}
