package com.flossypickle.poweroutagemonitor.integrations.alerts.sms

import android.content.Context
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertMessage
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertProvider
import com.flossypickle.poweroutagemonitor.integrations.alerts.DeliveryResult

internal class SmsAlertProvider(
    context: Context,
    private val recipient: String,
    private val client: SmsClient = SmsClient(context.applicationContext)
) : AlertProvider {
    override val id = SmsConfigStore.PROVIDER_ID
    override val displayName = "SMS · $recipient"

    override fun send(message: AlertMessage): DeliveryResult = client.send(recipient, message)
}
