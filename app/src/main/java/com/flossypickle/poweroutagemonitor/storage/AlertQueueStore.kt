package com.flossypickle.poweroutagemonitor.storage

import android.content.Context
import android.util.AtomicFile
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertKind
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertMessage
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertQueueEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Credential-protected durable queue. Provider credentials and destinations stay unavailable before unlock. */
internal class AlertQueueStore(context: Context) {
    private val file = AtomicFile(File(context.applicationContext.filesDir, "alert_delivery_queue.json"))

    fun read(): List<AlertQueueEngine.Item> = synchronized(lock) { readUnlocked() }

    fun enqueue(item: AlertQueueEngine.Item): Boolean = synchronized(lock) {
        val before = readUnlocked()
        val after = AlertQueueEngine.enqueue(before, item)
        if (after === before) return@synchronized false
        writeUnlocked(after)
        true
    }

    fun replace(item: AlertQueueEngine.Item) = synchronized(lock) {
        val items = readUnlocked()
        val index = items.indexOfFirst { it.id == item.id }
        if (index < 0) return@synchronized
        writeUnlocked(items.toMutableList().apply { set(index, item) })
    }

    fun find(id: String): AlertQueueEngine.Item? = synchronized(lock) {
        readUnlocked().firstOrNull { it.id == id }
    }

    /** Atomically leases one item so overlapping workers cannot send it twice. */
    fun claim(id: String, nowEpochMs: Long): AlertQueueEngine.Item? = synchronized(lock) {
        val items = readUnlocked()
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized null
        val item = items[index]
        if (item !in AlertQueueEngine.due(items, nowEpochMs)) return@synchronized null
        val claimed = AlertQueueEngine.markInFlight(item, nowEpochMs)
        writeUnlocked(items.toMutableList().apply { set(index, claimed) })
        claimed
    }

    fun isBlockedByEarlierMessage(id: String): Boolean = synchronized(lock) {
        val items = readUnlocked()
        val item = items.firstOrNull { it.id == id } ?: return@synchronized false
        AlertQueueEngine.hasUnfinishedPredecessor(items, item)
    }

    fun nextUnfinishedForEvent(item: AlertQueueEngine.Item): AlertQueueEngine.Item? =
        synchronized(lock) { AlertQueueEngine.nextUnfinishedForEvent(readUnlocked(), item) }

    fun sequenceHeads(): List<AlertQueueEngine.Item> = synchronized(lock) {
        AlertQueueEngine.sequenceHeads(readUnlocked())
    }

    fun retryFailed(nowEpochMs: Long): List<AlertQueueEngine.Item> = synchronized(lock) {
        val items = readUnlocked()
        val retried = items.map { AlertQueueEngine.retryFailed(it, nowEpochMs) }
        writeUnlocked(retried)
        retried.filter { retriedItem ->
            items.any { original ->
                original.id == retriedItem.id && original.status == AlertQueueEngine.Status.FAILED
            }
        }
    }

    fun clearTerminal() = synchronized(lock) {
        writeUnlocked(readUnlocked().filter { it.status !in terminalStates })
    }

    private fun readUnlocked(): List<AlertQueueEngine.Item> = runCatching {
        if (!file.baseFile.exists()) return emptyList()
        val array = JSONArray(file.openRead().bufferedReader().use { it.readText() })
        buildList {
            for (index in 0 until array.length()) add(array.getJSONObject(index).toQueueItem())
        }
    }.getOrDefault(emptyList())

    private fun writeUnlocked(items: List<AlertQueueEngine.Item>) {
        val active = items.filter { it.status !in terminalStates }
        val terminal = items.filter { it.status in terminalStates }
            .sortedByDescending { it.lastAttemptAtEpochMs ?: it.createdAtEpochMs }
            .take(MAX_TERMINAL_RECORDS)
        val retained = (active + terminal).sortedBy { it.createdAtEpochMs }
        val array = JSONArray().apply { retained.forEach { put(it.toJson()) } }
        val output = file.startWrite()
        try {
            output.write(array.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    private fun AlertQueueEngine.Item.toJson() = JSONObject().apply {
        put("id", id)
        put("providerId", providerId)
        put("destinationId", destinationId)
        put("eventId", message.eventId)
        put("kind", message.kind.name)
        put("title", message.title)
        put("body", message.body)
        put("status", status.name)
        put("createdAt", createdAtEpochMs)
        put("attemptCount", attemptCount)
        putNullable("lastAttemptAt", lastAttemptAtEpochMs)
        put("nextAttemptAt", nextAttemptAtEpochMs)
        putNullable("leaseUntil", leaseUntilEpochMs)
        putNullable("lastError", lastError)
        putNullable("providerMessageId", providerMessageId)
    }

    private fun JSONObject.toQueueItem() = AlertQueueEngine.Item(
        id = getString("id"),
        providerId = getString("providerId"),
        destinationId = getString("destinationId"),
        message = AlertMessage(
            eventId = getString("eventId"),
            kind = AlertKind.valueOf(getString("kind")),
            title = getString("title"),
            body = getString("body")
        ),
        status = AlertQueueEngine.Status.valueOf(getString("status")),
        createdAtEpochMs = getLong("createdAt"),
        attemptCount = getInt("attemptCount"),
        lastAttemptAtEpochMs = optLongOrNull("lastAttemptAt"),
        nextAttemptAtEpochMs = getLong("nextAttemptAt"),
        leaseUntilEpochMs = optLongOrNull("leaseUntil"),
        lastError = optStringOrNull("lastError"),
        providerMessageId = optStringOrNull("providerMessageId")
    )

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun JSONObject.optLongOrNull(key: String): Long? = if (isNull(key)) null else getLong(key)
    private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else getString(key)

    companion object {
        private const val MAX_TERMINAL_RECORDS = 200
        private val terminalStates = setOf(AlertQueueEngine.Status.SENT, AlertQueueEngine.Status.FAILED)
        private val lock = Any()
    }
}
