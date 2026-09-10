package com.flossypickle.poweroutagemonitor.integrations.alerts.telegram

import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertMessage
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertProvider
import com.flossypickle.poweroutagemonitor.integrations.alerts.DeliveryResult

internal class TelegramAlertProvider(
    private val token: String,
    private val destination: TelegramConfigStore.ChatDestination,
    private val client: TelegramClient = TelegramClient()
) : AlertProvider {
    override val id: String = TelegramConfigStore.PROVIDER_ID
    override val displayName: String = "Telegram · ${destination.label}"

    override suspend fun send(message: AlertMessage): DeliveryResult = client.sendMessage(
        token = token,
        chatId = destination.chatId,
        text = "${message.title}\n\n${message.body}"
    )
}
