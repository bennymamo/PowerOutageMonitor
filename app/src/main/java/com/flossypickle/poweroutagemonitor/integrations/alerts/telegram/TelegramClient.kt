package com.flossypickle.poweroutagemonitor.integrations.alerts.telegram

import com.flossypickle.poweroutagemonitor.integrations.alerts.DeliveryResult
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal class TelegramClient {
    data class BotIdentity(val displayName: String, val username: String?)
    data class Chat(val chatId: String, val label: String, val type: String)

    sealed interface ApiResult<out T> {
        data class Success<T>(val value: T) : ApiResult<T>
        data class Failure(val message: String, val retryable: Boolean) : ApiResult<Nothing>
    }

    fun identifyBot(token: String): ApiResult<BotIdentity> {
        val response = request(token, "getMe", JSONObject())
        return response.mapSuccess { root ->
            val bot = root.getJSONObject("result")
            BotIdentity(
                displayName = bot.optString("first_name").ifBlank { "Telegram bot" },
                username = bot.optString("username").takeIf(String::isNotBlank)
            )
        }
    }

    fun discoverChats(token: String): ApiResult<List<Chat>> {
        val response = request(token, "getUpdates", JSONObject().apply {
            put("limit", 100)
            put("timeout", 0)
            put(
                "allowed_updates",
                org.json.JSONArray(
                    listOf(
                        "message",
                        "edited_message",
                        "channel_post",
                        "callback_query",
                        "my_chat_member",
                        "chat_member"
                    )
                )
            )
        })
        return response.mapSuccess { root ->
            val updates = root.getJSONArray("result")
            buildList {
                for (index in 0 until updates.length()) {
                    val update = updates.getJSONObject(index)
                    val chat = update.chatObject() ?: continue
                    val id = chat.optLong("id", Long.MIN_VALUE)
                    if (id == Long.MIN_VALUE) continue
                    val label = chat.optString("title").ifBlank {
                        listOf(chat.optString("first_name"), chat.optString("last_name"))
                            .filter(String::isNotBlank).joinToString(" ").ifBlank { id.toString() }
                    }
                    add(Chat(id.toString(), label, chat.optString("type", "unknown")))
                }
            }.distinctBy(Chat::chatId)
        }
    }

    private fun JSONObject.chatObject(): JSONObject? =
        optJSONObject("message")?.optJSONObject("chat")
            ?: optJSONObject("edited_message")?.optJSONObject("chat")
            ?: optJSONObject("channel_post")?.optJSONObject("chat")
            ?: optJSONObject("callback_query")
                ?.optJSONObject("message")
                ?.optJSONObject("chat")
            ?: optJSONObject("my_chat_member")?.optJSONObject("chat")
            ?: optJSONObject("chat_member")?.optJSONObject("chat")

    fun sendMessage(token: String, chatId: String, text: String): DeliveryResult {
        val response = request(token, "sendMessage", JSONObject().apply {
            put("chat_id", chatId)
            put("text", text.take(TELEGRAM_TEXT_LIMIT))
            put("disable_web_page_preview", true)
        })
        return when (response) {
            is ApiResult.Success -> DeliveryResult.Sent(
                response.value.optJSONObject("result")?.optLong("message_id")?.toString()
            )
            is ApiResult.Failure -> if (response.retryable) {
                DeliveryResult.RetryableFailure(response.message)
            } else {
                DeliveryResult.PermanentFailure(response.message)
            }
        }
    }

    private fun request(token: String, method: String, payload: JSONObject): ApiResult<JSONObject> {
        if (!isPlausibleToken(token)) {
            return ApiResult.Failure("Bot token format is not valid", retryable = false)
        }
        var connection: HttpsURLConnection? = null
        return try {
            connection = URL("https://api.telegram.org/bot$token/$method")
                .openConnection() as HttpsURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { output ->
                output.write(payload.toString().toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(body) }.getOrNull()
            if (status in 200..299 && json?.optBoolean("ok") == true) {
                ApiResult.Success(json)
            } else {
                val message = json?.optString("description")
                    ?.replace(token, "••••")
                    ?.take(MAX_ERROR_LENGTH)
                    ?.takeIf(String::isNotBlank)
                    ?: "Telegram returned HTTP $status"
                ApiResult.Failure(message, retryable = status == 429 || status >= 500 || status <= 0)
            }
        } catch (_: IOException) {
            ApiResult.Failure("Telegram could not be reached", retryable = true)
        } catch (_: Exception) {
            ApiResult.Failure("Telegram request could not be completed", retryable = false)
        } finally {
            connection?.disconnect()
        }
    }

    private inline fun <T> ApiResult<JSONObject>.mapSuccess(transform: (JSONObject) -> T): ApiResult<T> =
        when (this) {
            is ApiResult.Success -> runCatching { ApiResult.Success(transform(value)) }
                .getOrElse { ApiResult.Failure("Telegram response was incomplete", retryable = false) }
            is ApiResult.Failure -> this
        }

    private fun isPlausibleToken(token: String): Boolean =
        token.length in 20..200 && ':' in token && token.none { it.isWhitespace() || it in "/?#" }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val MAX_ERROR_LENGTH = 300
        private const val TELEGRAM_TEXT_LIMIT = 4_096
    }
}
