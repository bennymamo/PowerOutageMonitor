package com.flossypickle.poweroutagemonitor.integrations.alerts.telegram

import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertMessage
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertProvider
import com.flossypickle.poweroutagemonitor.integrations.alerts.DeliveryResult

internal class TelegramAlertProvider(
    private val token: String,
    private val destination: TelegramConfigStore.ChatDestination,
    private val client: TelegramClient = TelegramClient(),
    private val remoteEnabled: Boolean = false
) : AlertProvider {
    override val id: String = TelegramConfigStore.PROVIDER_ID
    override val displayName: String = "Telegram · ${destination.label}"

    override fun send(message: AlertMessage): DeliveryResult = client.sendMessage(
        token = token,
        chatId = destination.chatId,
        text = "${message.title}\n\n${message.body}" + if (remoteEnabled && message.kind != com.flossypickle.poweroutagemonitor.integrations.alerts.AlertKind.TEST)
            "\n\n/status for details · /stop_sound to acknowledge sound · /quiet to quiet Telegram alerts" else ""
    )
}
