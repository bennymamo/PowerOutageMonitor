package com.flossypickle.poweroutagemonitor.integrations.alerts.email

import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertMessage
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertProvider
import com.flossypickle.poweroutagemonitor.integrations.alerts.DeliveryResult

internal class ResendEmailAlertProvider(
    private val apiKey: String,
    private val sender: String,
    private val recipient: String,
    private val client: ResendEmailClient = ResendEmailClient()
) : AlertProvider {
    override val id = ResendEmailConfigStore.PROVIDER_ID
    override val displayName = "Email · $recipient"

    override fun send(message: AlertMessage): DeliveryResult =
        client.send(apiKey, sender, recipient, message)
}
