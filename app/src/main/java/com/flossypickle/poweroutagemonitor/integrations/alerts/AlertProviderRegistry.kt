package com.flossypickle.poweroutagemonitor.integrations.alerts

import android.content.Context
import com.flossypickle.poweroutagemonitor.integrations.alerts.telegram.TelegramAlertProvider
import com.flossypickle.poweroutagemonitor.integrations.alerts.telegram.TelegramConfigStore

/** One registry point for independently replaceable alert-channel implementations. */
internal class AlertProviderRegistry(context: Context) {
    data class Destination(val providerId: String, val destinationId: String)

    private val telegram = TelegramConfigStore(context)

    fun statusSummary(): String {
        val config = telegram.config()
        return when {
            config.enabled -> "Telegram active (${config.destinations.size})"
            config.hasToken -> "Telegram saved, off"
            else -> "None configured"
        }
    }

    fun enabledDestinations(): List<Destination> {
        val config = telegram.config()
        if (!config.enabled) return emptyList()
        return config.destinations.map { Destination(TelegramConfigStore.PROVIDER_ID, it.chatId) }
    }

    fun resolve(providerId: String, destinationId: String): AlertProvider? {
        if (providerId != TelegramConfigStore.PROVIDER_ID) return null
        val config = telegram.config()
        val token = telegram.botToken() ?: return null
        val destination = config.destinations.firstOrNull { it.chatId == destinationId } ?: return null
        return if (config.enabled) TelegramAlertProvider(token, destination) else null
    }
}
