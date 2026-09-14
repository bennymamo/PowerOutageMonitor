package com.flossypickle.poweroutagemonitor.storage

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import com.flossypickle.poweroutagemonitor.OutageEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Atomic, bounded history suitable for the app's low event volume. */
internal class EventHistoryStore(context: Context) {
    private val storageContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        context.createDeviceProtectedStorageContext()
    } else context.applicationContext
    private val file = AtomicFile(File(storageContext.filesDir, "power_event_history.json"))

    data class Record(
        val kind: String,
        val powerLostAtEpochMs: Long,
        val confirmedAtEpochMs: Long?,
        val restoredAtEpochMs: Long,
        val startingBatteryPercent: Int?,
        val endingBatteryPercent: Int?,
        val startingBatteryTemperatureTenthsCelsius: Int? = null,
        val endingBatteryTemperatureTenthsCelsius: Int? = null
    )

    @Synchronized
    fun append(record: Record, maxRecords: Int = MonitorStore.DEFAULT_HISTORY_LIMIT) {
        val records = read()
        val updated = appendUnique(records, record, maxRecords)
        if (updated !== records) write(updated)
    }

    @Synchronized
    fun trimTo(maxRecords: Int) {
        write(read().take(maxRecords.coerceIn(MonitorStore.HISTORY_LIMIT_RANGE)))
    }

    @Synchronized
    fun clear() = write(emptyList())

    @Synchronized
    fun replaceAll(records: List<Record>, maxRecords: Int) {
        write(records.take(maxRecords.coerceIn(MonitorStore.HISTORY_LIMIT_RANGE)))
    }

    private fun write(records: List<Record>) {
        val array = JSONArray()
        records.forEach { item ->
            array.put(JSONObject().apply {
                put("kind", item.kind)
                put("lostAt", item.powerLostAtEpochMs)
                put("confirmedAt", item.confirmedAtEpochMs ?: JSONObject.NULL)
                put("restoredAt", item.restoredAtEpochMs)
                put("startBattery", item.startingBatteryPercent ?: JSONObject.NULL)
                put("endBattery", item.endingBatteryPercent ?: JSONObject.NULL)
                put("startTemperature", item.startingBatteryTemperatureTenthsCelsius ?: JSONObject.NULL)
                put("endTemperature", item.endingBatteryTemperatureTenthsCelsius ?: JSONObject.NULL)
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

    @Synchronized
    fun read(): List<Record> = runCatching {
        if (!file.baseFile.exists()) return emptyList()
        val array = JSONArray(file.openRead().bufferedReader().use { it.readText() })
        val records = mutableListOf<Record>()
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            records += Record(
                kind = item.getString("kind"),
                powerLostAtEpochMs = item.getLong("lostAt"),
                confirmedAtEpochMs = item.optLongOrNull("confirmedAt"),
                restoredAtEpochMs = item.getLong("restoredAt"),
                startingBatteryPercent = item.optIntOrNull("startBattery"),
                endingBatteryPercent = item.optIntOrNull("endBattery"),
                startingBatteryTemperatureTenthsCelsius = item.optIntOrNull("startTemperature"),
                endingBatteryTemperatureTenthsCelsius = item.optIntOrNull("endTemperature")
            )
        }
        records
    }.getOrDefault(emptyList())

    private fun JSONObject.optLongOrNull(key: String): Long? = if (isNull(key)) null else optLong(key)
    private fun JSONObject.optIntOrNull(key: String): Int? = if (isNull(key)) null else optInt(key)

    companion object {
        const val KIND_BRIEF_INTERRUPTION = "brief_interruption"
        const val KIND_CONFIRMED_OUTAGE = "confirmed_outage"

        internal fun completedKind(
            before: OutageEngine.State,
            after: OutageEngine.State
        ): String? = when {
            before.phase == OutageEngine.Phase.PENDING_OUTAGE &&
                after.phase == OutageEngine.Phase.POWERED -> KIND_BRIEF_INTERRUPTION
            before.phase in setOf(
                OutageEngine.Phase.OUTAGE,
                OutageEngine.Phase.PENDING_RESTORE
            ) && after.phase == OutageEngine.Phase.POWERED -> KIND_CONFIRMED_OUTAGE
            else -> null
        }

        internal fun appendUnique(
            records: List<Record>,
            record: Record,
            maxRecords: Int
        ): List<Record> {
            if (records.any {
                    it.kind == record.kind &&
                        it.powerLostAtEpochMs == record.powerLostAtEpochMs
                }
            ) return records
            return (listOf(record) + records)
                .take(maxRecords.coerceIn(MonitorStore.HISTORY_LIMIT_RANGE))
        }
    }
}
