package com.flossypickle.poweroutagemonitor.integrations.alerts

import android.content.Context
import com.flossypickle.poweroutagemonitor.integrations.alerts.telegram.TelegramAlertProvider
import com.flossypickle.poweroutagemonitor.integrations.alerts.telegram.TelegramConfigStore
import com.flossypickle.poweroutagemonitor.integrations.alerts.email.ResendEmailAlertProvider
import com.flossypickle.poweroutagemonitor.integrations.alerts.email.ResendEmailConfigStore
import com.flossypickle.poweroutagemonitor.integrations.alerts.email.GmailSmtpAlertProvider
import com.flossypickle.poweroutagemonitor.integrations.alerts.email.GmailSmtpConfigStore
import com.flossypickle.poweroutagemonitor.integrations.alerts.sms.SmsAlertProvider
import com.flossypickle.poweroutagemonitor.integrations.alerts.sms.SmsConfigStore

/** One registry point for independently replaceable alert-channel implementations. */
internal class AlertProviderRegistry(private val context: Context) {
    data class Destination(val providerId: String, val destinationId: String)

    private val telegram = TelegramConfigStore(context)
    private val resend = ResendEmailConfigStore(context)
    private val gmail = GmailSmtpConfigStore(context)
    private val sms = SmsConfigStore(context)

    fun statusSummary(): String {
        val telegramConfig = telegram.config()
        val resendConfig = resend.config()
        val gmailConfig = gmail.config()
        val smsConfig = sms.config()
        val active = buildList {
            if (telegramConfig.enabled) add("Telegram (${telegramConfig.destinations.size})")
            if (gmailConfig.enabled) add("Gmail (${gmailConfig.recipients.size})")
            if (resendConfig.enabled) add("Resend (${resendConfig.recipients.size})")
            if (smsConfig.enabled) add("SMS (${smsConfig.recipients.size})")
        }
        if (active.isNotEmpty()) return active.joinToString(" · ")
        val saved = buildList {
            if (telegramConfig.hasToken) add("Telegram")
            if (gmailConfig.hasAppPassword) add("Gmail")
            if (resendConfig.hasApiKey) add("Resend")
            if (smsConfig.recipients.isNotEmpty()) add("SMS")
        }
        return if (saved.isEmpty()) "None configured" else "${saved.joinToString(" + ")} saved, off"
    }

    fun enabledDestinations(): List<Destination> {
        val telegramConfig = telegram.config()
        val gmailConfig = gmail.config()
        val resendConfig = resend.config()
        val smsConfig = sms.config()
        return buildList {
            if (telegramConfig.enabled) {
                addAll(telegramConfig.destinations.map {
                    Destination(TelegramConfigStore.PROVIDER_ID, it.chatId)
                })
            }
            if (gmailConfig.enabled) {
                addAll(gmailConfig.recipients.map {
                    Destination(GmailSmtpConfigStore.PROVIDER_ID, it)
                })
            }
            if (resendConfig.enabled) {
                addAll(resendConfig.recipients.map {
                    Destination(ResendEmailConfigStore.PROVIDER_ID, it)
                })
            }
            if (smsConfig.enabled) {
                addAll(smsConfig.recipients.map {
                    Destination(SmsConfigStore.PROVIDER_ID, it)
                })
            }
        }
    }

    fun resolve(providerId: String, destinationId: String): AlertProvider? {
        return when (providerId) {
            TelegramConfigStore.PROVIDER_ID -> {
                val config = telegram.config()
                val token = telegram.botToken() ?: return null
                val destination = config.destinations
                    .firstOrNull { it.chatId == destinationId } ?: return null
                if (config.enabled) TelegramAlertProvider(token, destination, remoteEnabled =
                    com.flossypickle.poweroutagemonitor.integrations.alerts.telegram.TelegramRemoteStore(context).settings().let {
                        it.enabled && destination.chatId in it.trustedChatIds
                    }) else null
            }
            GmailSmtpConfigStore.PROVIDER_ID -> {
                val config = gmail.config()
                val appPassword = gmail.appPassword() ?: return null
                val recipient = config.recipients
                    .firstOrNull { it.equals(destinationId, ignoreCase = true) } ?: return null
                if (config.enabled) {
                    GmailSmtpAlertProvider(config.account, appPassword, recipient)
                } else null
            }
            ResendEmailConfigStore.PROVIDER_ID -> {
                val config = resend.config()
                val apiKey = resend.apiKey() ?: return null
                val recipient = config.recipients
                    .firstOrNull { it.equals(destinationId, ignoreCase = true) } ?: return null
                if (config.enabled) {
                    ResendEmailAlertProvider(apiKey, config.sender, recipient)
                } else null
            }
            SmsConfigStore.PROVIDER_ID -> {
                val config = sms.config()
                val recipient = config.recipients.firstOrNull { it == destinationId } ?: return null
                if (config.enabled) SmsAlertProvider(context, recipient) else null
            }
            else -> null
        }
    }

    companion object {
        fun requiresInternet(providerId: String?): Boolean =
            providerId != SmsConfigStore.PROVIDER_ID
    }
}
