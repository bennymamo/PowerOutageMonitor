package com.flossypickle.poweroutagemonitor.storage

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertKind
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertMessage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Non-secret alert events held in device-protected storage until credentials are available. */
internal class PendingAlertEventStore(context: Context) {
    private val storageContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        context.createDeviceProtectedStorageContext()
    } else context.applicationContext
    private val file = AtomicFile(File(storageContext.filesDir, "pending_alert_events.json"))

    fun read(): List<AlertMessage> = synchronized(lock) { readUnlocked() }

    fun enqueue(message: AlertMessage): Boolean = synchronized(lock) {
        val before = readUnlocked()
        if (before.any { it.eventId == message.eventId && it.kind == message.kind }) return@synchronized false
        writeUnlocked((before + message).takeLast(MAX_PENDING_EVENTS))
        true
    }

    fun remove(message: AlertMessage) = synchronized(lock) {
        writeUnlocked(readUnlocked().filterNot {
            it.eventId == message.eventId && it.kind == message.kind
        })
    }

    fun clear() = synchronized(lock) { writeUnlocked(emptyList()) }

    fun replaceAll(messages: List<AlertMessage>) = synchronized(lock) {
        writeUnlocked(messages.takeLast(MAX_PENDING_EVENTS))
    }

    private fun readUnlocked(): List<AlertMessage> = runCatching {
        if (!file.baseFile.exists()) return emptyList()
        val array = JSONArray(file.openRead().bufferedReader().use { it.readText() })
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(AlertMessage(
                    eventId = item.getString("eventId"),
                    kind = AlertKind.valueOf(item.getString("kind")),
                    title = item.getString("title"),
                    body = item.getString("body")
                ))
            }
        }
    }.getOrDefault(emptyList())

    private fun writeUnlocked(messages: List<AlertMessage>) {
        val array = JSONArray().apply {
            messages.forEach { message ->
                put(JSONObject().apply {
                    put("eventId", message.eventId)
                    put("kind", message.kind.name)
                    put("title", message.title)
                    put("body", message.body)
                })
            }
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
        private const val MAX_PENDING_EVENTS = 50
        private val lock = Any()
    }
}
