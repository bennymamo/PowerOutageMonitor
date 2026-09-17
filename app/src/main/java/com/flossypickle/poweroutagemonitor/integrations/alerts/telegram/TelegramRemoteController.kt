package com.flossypickle.poweroutagemonitor.integrations.alerts.telegram

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.UserManager
import java.security.MessageDigest

/** A single cancelable receiver hosted by the existing foreground service. */
internal class TelegramRemoteController(private val context: Context,
    private val execute: (TelegramRemotePolicy.Command) -> String,
    private val clientFactory: () -> TelegramClient = { TelegramClient() }) {
    @Volatile private var generation = 0
    private var signature: String? = null
    private var thread: Thread? = null
    private var client: TelegramClient? = null
    fun refresh() {
        if (Build.VERSION.SDK_INT >= 24 && !context.getSystemService(UserManager::class.java).isUserUnlocked) return
        val store = TelegramRemoteStore(context); val settings = store.settings()
        val token = TelegramConfigStore(context).botToken()
        val hash = token?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b.toInt() and 255) } }
        val key = if (settings.enabled && settings.trustedChatIds.isNotEmpty() && token != null)
            "$hash|${settings.trustedChatIds.sorted()}|${settings.longPolling}|${settings.pollSeconds}" else null
        if (signature == key && thread?.isAlive == true) return
        stop(); signature = key
        if (key == null || token == null || hash == null) {
            if (settings.enabled) store.health("Save a bot token and allow a private chat in remote-control setup.")
            return
        }
        val run = generation; val api = clientFactory(); client = api
        thread = Thread({ receive(run, token, hash, api) }, "telegram-remote").apply { isDaemon = true; start() }
    }
    fun stop() { generation++; client?.cancelPoll(); thread?.interrupt(); thread = null; client = null; signature = null }
    private fun receive(run: Int, token: String, hash: String, api: TelegramClient) {
        val store = TelegramRemoteStore(context)
        val wake = context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
            "${context.packageName}:telegram-remote").apply { setReferenceCounted(false) }
        var backoff = 5_000L
        try {
            while (generation == run && !Thread.currentThread().isInterrupted) {
                val config = store.settings()
                if (!config.enabled) break
                var offset = store.offset(hash) ?: -1L
                wake.acquire(90_000)
                try {
                    // Discard queued commands on initial enable, token replacement or restore.
                    // Telegram's negative offset reads the tail and forgets earlier updates.
                    if (offset < 0) {
                        when (val tail = api.pollUpdates(token, -1, 0, 1)) {
                            is TelegramClient.ApiResult.Failure -> { store.health(tail.message); throw Retry(tail.retryAfterSeconds) }
                            is TelegramClient.ApiResult.Success -> {
                                if (generation != run) return
                                offset = (tail.value.maxOfOrNull { it.id } ?: -1) + 1
                                store.checkpoint(hash, offset)
                            }
                        }
                    }
                    store.health("Listening for trusted private commands")
                    when (val result = api.pollUpdates(token, offset, if (config.longPolling) 25 else 0)) {
                        is TelegramClient.ApiResult.Failure -> {
                            store.health(if (result.message.contains("Conflict", true))
                                "Another receiver or webhook uses this bot. Use one command receiver per bot." else result.message)
                            throw Retry(result.retryAfterSeconds)
                        }
                        is TelegramClient.ApiResult.Success -> {
                            if (generation != run) return
                            for (update in result.value.sortedBy { it.id }) {
                                if (generation != run) return
                                val current = store.settings()
                                val command = update.message?.let { TelegramRemotePolicy.authorize(it, current.trustedChatIds,
                                    store.enabledAt(), System.currentTimeMillis(), offset) }
                                if (update.id < offset) continue
                                // Persist before acting: a killed process must never replay a control.
                                offset = update.id + 1; store.checkpoint(hash, offset)
                                if (!current.enabled || command == null || generation != run) continue
                                val reply = runCatching { execute(command) }.getOrElse { "The command could not be completed. Check the app before retrying." }
                                if (generation != run) return
                                val sent = api.sendMessage(token, update.message!!.chatId, reply)
                                if (sent !is com.flossypickle.poweroutagemonitor.integrations.alerts.DeliveryResult.Sent)
                                    store.health("Command handled; its reply could not be delivered. Send /status to check.")
                            }
                            backoff = 5_000
                        }
                    }
                } catch (failure: Retry) {
                    if (wake.isHeld) wake.release()
                    Thread.sleep(maxOf(backoff, (failure.afterSeconds ?: 0) * 1000L)); backoff = (backoff * 2).coerceAtMost(300_000)
                    continue
                } finally { if (wake.isHeld) wake.release() }
                if (!config.longPolling) Thread.sleep(config.pollSeconds * 1000L)
            }
        } catch (_: InterruptedException) {
            // Expected when settings change or both monitoring and controls are disabled.
        } catch (_: Exception) { if (generation == run) store.health("Remote receiver stopped. Reopen the app or save remote settings to retry.") }
        finally { if (wake.isHeld) wake.release(); api.cancelPoll() }
    }
    private class Retry(val afterSeconds: Int? = null) : Exception()
}
