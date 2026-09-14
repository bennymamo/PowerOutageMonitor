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

    data class Record(
        val kind: String,
        val timestampEpochMs: Long,
        val detail: String
    )

    fun recordAppOpened(maxRecords: Int, nowEpochMs: Long = System.currentTimeMillis()) {
        synchronized(LOCK) {
            val previousSessionUnclosed = preferences.getBoolean(KEY_APP_ACTIVE, false)
            appendLocked(
                Record(
                    kind = if (previousSessionUnclosed) KIND_APP_RECOVERED else KIND_APP_OPENED,
                    timestampEpochMs = nowEpochMs,
                    detail = if (previousSessionUnclosed) {
                        "The app opened after no close was recorded. The previous UI process may have been killed or crashed."
                    } else "Dashboard opened"
                ),
                maxRecords
            )
            preferences.edit().putBoolean(KEY_APP_ACTIVE, true).commit()
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

    fun recordMonitoringStarted(maxRecords: Int, nowEpochMs: Long = System.currentTimeMillis()) {
        synchronized(LOCK) {
            val previousSessionUnclosed = preferences.getBoolean(KEY_SERVICE_ACTIVE, false)
            appendLocked(
                Record(
                    kind = startKind(previousSessionUnclosed),
                    timestampEpochMs = nowEpochMs,
                    detail = if (previousSessionUnclosed) {
                        "Monitoring started after no stop was recorded. Android, a crash, or device power management may have ended the previous process."
                    } else {
                        "Background grid monitoring started"
                    }
                ),
                maxRecords
            )
            preferences.edit().putBoolean(KEY_SERVICE_ACTIVE, true).commit()
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
        const val KIND_APP_CLOSED = "app_closed"
        const val KIND_MONITORING_STARTED = "monitoring_started"
        const val KIND_MONITORING_RECOVERED = "monitoring_recovered"
        const val KIND_MONITORING_STOPPED = "monitoring_stopped"
        private const val FILE_NAME = "operational_history_state"
        private const val KEY_SERVICE_ACTIVE = "service_active"
        private const val KEY_APP_ACTIVE = "app_active"
        private val LOCK = Any()

        internal fun startKind(previousSessionUnclosed: Boolean) =
            if (previousSessionUnclosed) KIND_MONITORING_RECOVERED else KIND_MONITORING_STARTED
    }
}
