package com.flossypickle.poweroutagemonitor.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.flossypickle.poweroutagemonitor.integrations.alerts.email.GmailSmtpConfigStore
import com.flossypickle.poweroutagemonitor.integrations.alerts.email.ResendEmailConfigStore
import com.flossypickle.poweroutagemonitor.integrations.alerts.sms.SmsCapability
import com.flossypickle.poweroutagemonitor.integrations.alerts.sms.SmsConfigStore
import com.flossypickle.poweroutagemonitor.integrations.alerts.telegram.TelegramConfigStore

/** Entrypoints for readiness, diagnostics, simulation and alert-destination setup. */
@Composable
internal fun SetupTestingSettingsContent(
    onOpenSetupChecklist: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenTestMode: () -> Unit
) {
    SettingsCard {
        Text(
            "Check that the monitor is ready and preview outage messages without changing real monitoring data.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
        Button(onClick = onOpenSetupChecklist, modifier = Modifier.fillMaxWidth()) {
            Text("Open setup checklist")
        }
        OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) {
            Text("Open diagnostics")
        }
        OutlinedButton(onClick = onOpenTestMode, modifier = Modifier.fillMaxWidth()) {
            Text("Open test mode")
        }
    }
}

@Composable
internal fun AlertChannelsSettingsContent(
    onOpenTelegram: () -> Unit,
    onOpenSms: () -> Unit,
    onOpenEmail: () -> Unit
) {
    val context = LocalContext.current
    val telegramConfig = TelegramConfigStore(context).config()
    val gmailConfig = GmailSmtpConfigStore(context).config()
    val resendConfig = ResendEmailConfigStore(context).config()
    val emailSaved = gmailConfig.hasAppPassword || resendConfig.hasApiKey
    val smsConfig = SmsConfigStore(context).config()
    val smsCapability = SmsCapability.capture(context)

    SettingsCard {
        SettingText("Telegram", when {
            telegramConfig.enabled -> "Enabled"
            telegramConfig.hasToken -> "Saved, disabled"
            else -> "Not configured"
        })
        Text(
            "Send outage and restoration messages through a bot you control. Multiple chats are supported.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
        OutlinedButton(onClick = onOpenTelegram, modifier = Modifier.fillMaxWidth()) {
            Text("Configure Telegram")
        }
        SettingText("Device SMS", when {
            !smsCapability.supported -> "Unavailable on this device"
            smsConfig.enabled -> "Enabled"
            smsConfig.recipients.isNotEmpty() -> "Saved, disabled"
            else -> "Not configured"
        })
        Text(
            "Send through the phone's SIM when internet service is unavailable. Carrier charges may apply.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
        OutlinedButton(onClick = onOpenSms, modifier = Modifier.fillMaxWidth()) {
            Text("Configure device SMS")
        }
        SettingText("Email", when {
            gmailConfig.enabled && resendConfig.enabled -> "Gmail and Resend enabled"
            gmailConfig.enabled -> "Gmail enabled"
            resendConfig.enabled -> "Resend enabled"
            emailSaved -> "Saved, disabled"
            else -> "Not configured"
        })
        Text(
            "Gmail is the easiest option and needs no domain. Resend remains available for users with a verified domain.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
        OutlinedButton(onClick = onOpenEmail, modifier = Modifier.fillMaxWidth()) {
            Text("Configure email")
        }
    }
}
