package com.flossypickle.poweroutagemonitor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flossypickle.poweroutagemonitor.integrations.alerts.email.GmailSmtpConfigStore
import com.flossypickle.poweroutagemonitor.integrations.alerts.email.ResendEmailConfigStore
import com.flossypickle.poweroutagemonitor.storage.MonitorStore

@Composable
internal fun EmailProvidersScreen(
    padding: PaddingValues,
    helpLevel: MonitorStore.HelpLevel,
    onOpenGmail: () -> Unit,
    onOpenResend: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val gmail = remember(context) { GmailSmtpConfigStore(context) }.config()
    val resend = remember(context) { ResendEmailConfigStore(context) }.config()

    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp).widthIn(max = 600.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TextButton(onClick = onBack) { Text("‹ Settings") }
        Text(
            "Email providers",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold
        )
        SetupGuidanceCaption(helpLevel)
        Text(
            if (helpLevel.isGuided) {
                "Choose one or enable both. Each provider keeps its own credentials and delivery queue destinations. Open a provider for a step-by-step walkthrough."
            } else {
                "Choose one or enable both. Gmail is the default; each provider has separate credentials and destinations."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        ProviderCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Gmail", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold)
                Text("RECOMMENDED", color = MaterialTheme.colorScheme.primary,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                when {
                    gmail.enabled -> "Enabled for ${gmail.recipients.size} recipient(s)"
                    gmail.hasAppPassword -> "Saved, disabled"
                    else -> "Not configured"
                },
                color = if (gmail.enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                if (helpLevel.isGuided) {
                    "Uses your own Gmail or Google Workspace account. No domain purchase is needed. The walkthrough explains 2-Step Verification and App Passwords."
                } else {
                    "SMTP over TLS with a Google App Password. No sending domain required."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            OutlinedButton(onClick = onOpenGmail, modifier = Modifier) {
                Text("Configure Gmail")
            }
        }

        ProviderCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Resend", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold)
                Text("ADVANCED", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                when {
                    resend.enabled -> "Enabled for ${resend.recipients.size} recipient(s)"
                    resend.hasApiKey -> "Saved, disabled"
                    else -> "Not configured"
                },
                color = if (resend.enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                if (helpLevel.isGuided) {
                    "Uses a Resend API key. The walkthrough explains how to verify a domain you own before sending real email."
                } else {
                    "HTTPS API with idempotent retries. Requires a verified sending domain."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            OutlinedButton(onClick = onOpenResend, modifier = Modifier) {
                Text("Configure Resend")
            }
        }
    }
}

@Composable
private fun ProviderCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    OutlinedCard(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}
