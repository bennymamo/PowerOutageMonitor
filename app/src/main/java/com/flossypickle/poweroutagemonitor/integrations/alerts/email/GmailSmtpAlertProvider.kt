package com.flossypickle.poweroutagemonitor.integrations.alerts.email

import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertMessage
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertProvider
import com.flossypickle.poweroutagemonitor.integrations.alerts.DeliveryResult

internal class GmailSmtpAlertProvider(
    private val account: String,
    private val appPassword: String,
    private val recipient: String,
    private val client: GmailSmtpClient = GmailSmtpClient()
) : AlertProvider {
    override val id = GmailSmtpConfigStore.PROVIDER_ID
    override val displayName = "Gmail · $recipient"

    override fun send(message: AlertMessage): DeliveryResult =
        client.send(account, appPassword, recipient, message)
}
