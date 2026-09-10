package com.flossypickle.poweroutagemonitor.integrations.alerts.telegram

import android.content.Context
import com.flossypickle.poweroutagemonitor.storage.SecureSecretStore
import org.json.JSONArray
import org.json.JSONObject

internal class TelegramConfigStore(context: Context) {
    data class ChatDestination(val chatId: String, val label: String)
    data class Config(
        val enabled: Boolean,
        val botDisplayName: String?,
        val destinations: List<ChatDestination>,
        val hasToken: Boolean
    )

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
    private val secrets = SecureSecretStore(context)

    fun config(): Config = Config(
        enabled = preferences.getBoolean(KEY_ENABLED, false),
        botDisplayName = preferences.getString(KEY_BOT_NAME, null),
        destinations = readDestinations(),
        hasToken = secrets.contains(SECRET_BOT_TOKEN)
    )

    fun botToken(): String? = secrets.get(SECRET_BOT_TOKEN)

    fun save(
        newToken: String?,
        enabled: Boolean,
        botDisplayName: String?,
        destinations: List<ChatDestination>
    ) {
        val token = newToken?.trim()?.takeIf(String::isNotEmpty)
        if (token != null) secrets.put(SECRET_BOT_TOKEN, token)
        val hasUsableToken = token != null || botToken() != null
        val cleanDestinations = destinations
            .mapNotNull { destination ->
                val id = destination.chatId.trim()
                if (id.isEmpty()) null else ChatDestination(
                    chatId = id,
                    label = destination.label.trim().ifEmpty { id }
                )
            }
            .distinctBy(ChatDestination::chatId)
        val json = JSONArray().apply {
            cleanDestinations.forEach { destination ->
                put(JSONObject().apply {
                    put("chatId", destination.chatId)
                    put("label", destination.label)
                })
            }
        }
        check(preferences.edit()
            .putBoolean(KEY_ENABLED, enabled && hasUsableToken && cleanDestinations.isNotEmpty())
            .putString(KEY_BOT_NAME, botDisplayName?.trim()?.takeIf(String::isNotEmpty))
            .putString(KEY_DESTINATIONS, json.toString())
            .commit()) { "Unable to persist Telegram configuration" }
    }

    fun clear() {
        preferences.edit().clear().commit()
        secrets.remove(SECRET_BOT_TOKEN)
    }

    private fun readDestinations(): List<ChatDestination> = runCatching {
        val array = JSONArray(preferences.getString(KEY_DESTINATIONS, "[]"))
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(ChatDestination(item.getString("chatId"), item.getString("label")))
            }
        }
    }.getOrDefault(emptyList())

    companion object {
        const val PROVIDER_ID = "telegram"
        private const val PREFERENCES_FILE = "telegram_config"
        private const val SECRET_BOT_TOKEN = "telegram_bot_token"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_BOT_NAME = "bot_name"
        private const val KEY_DESTINATIONS = "destinations"
    }
}
