package com.flossypickle.poweroutagemonitor.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertDeliveryCoordinator
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertKind
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertMessage
import com.flossypickle.poweroutagemonitor.integrations.alerts.DeliveryResult
import com.flossypickle.poweroutagemonitor.integrations.alerts.email.GmailSmtpClient
import com.flossypickle.poweroutagemonitor.integrations.alerts.email.GmailSmtpConfigStore
import com.flossypickle.poweroutagemonitor.integrations.alerts.email.GmailSmtpProtocol
import com.flossypickle.poweroutagemonitor.integrations.alerts.email.ResendEmailProtocol
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun GmailEmailSetupScreen(
    deviceName: String,
    padding: PaddingValues,
    onConfigurationChanged: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val store = remember(context) { GmailSmtpConfigStore(context) }
    val client = remember { GmailSmtpClient() }
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(store.config()) }
    var account by remember { mutableStateOf(config.account) }
    var appPasswordInput by remember { mutableStateOf("") }
    var recipientText by remember { mutableStateOf(config.recipients.joinToString("\n")) }
    var enabled by remember { mutableStateOf(config.enabled) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    fun passwordForOperation() = appPasswordInput.takeIf(String::isNotBlank) ?: store.appPassword()
    fun runAsync(operation: suspend () -> Unit) {
        if (loading) return
        scope.launch {
            loading = true
            feedback = null
            try { operation() } finally { loading = false }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp).widthIn(max = 600.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TextButton(onClick = onBack) { Text("‹ Email providers") }
        Text("Gmail", style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold)
        Text(
            "Send alerts from a Gmail or Google Workspace account without buying a domain.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SectionTitle("Setup")
        GmailCard {
            Text("1. Turn on 2-Step Verification for the Google account.")
            Text("2. Create an App Password named FP Grid Monitor.")
            Text("3. Enter the account, 16-character App Password and recipients below.")
            Text("4. Save, send a test, then enable Gmail alerts.")
            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(APP_PASSWORDS_URL)))
                    }.onFailure { feedback = "No browser is available to open Google Account settings." }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Open Google App Passwords") }
            Text(
                "Google may hide App Passwords for organization accounts, Advanced Protection, or security-key-only 2-Step Verification.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }

        SectionTitle("Credentials")
        GmailCard {
            OutlinedTextField(
                value = account,
                onValueChange = { if (it.length <= 254) account = it.trim() },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Google account email") },
                supportingText = { Text("Example: gridmonitor@gmail.com") },
                singleLine = true,
                enabled = !loading
            )
            OutlinedTextField(
                value = appPasswordInput,
                onValueChange = { if (it.length <= 32) appPasswordInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(if (config.hasAppPassword) {
                        "New App Password (stored password unchanged if blank)"
                    } else "16-character App Password")
                },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                enabled = !loading
            )
            if (config.hasAppPassword) {
                Text(
                    "An App Password is stored with Android Keystore encryption.",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp
                )
            }
        }

        SectionTitle("Recipients")
        GmailCard {
            OutlinedTextField(
                value = recipientText,
                onValueChange = { recipientText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Email addresses") },
                supportingText = { Text("One per line, or separated by commas") },
                minLines = 3,
                enabled = !loading
            )
            Text(
                "Each address is delivered and retried independently. Recipients are never exposed to each other.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }

        SectionTitle("Activation")
        GmailCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Enable Gmail alerts", fontWeight = FontWeight.Medium)
                    Text(
                        "Send confirmed outage and restoration alerts to every saved address.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
                Switch(checked = enabled, onCheckedChange = { enabled = it }, enabled = !loading)
            }
            Button(
                onClick = {
                    runAsync {
                        val validation = validateGmailInputs(account, recipientText)
                        if (validation.error != null) {
                            feedback = validation.error
                            return@runAsync
                        }
                        if (appPasswordInput.isNotBlank() &&
                            !GmailSmtpProtocol.isValidAppPassword(appPasswordInput)) {
                            feedback = "The Google App Password must contain 16 characters. Spaces are allowed."
                            return@runAsync
                        }
                        val requestedEnabled = enabled
                        withContext(Dispatchers.IO) {
                            store.save(appPasswordInput, enabled, account, validation.recipients)
                        }
                        config = withContext(Dispatchers.IO) { store.config() }
                        withContext(Dispatchers.IO) {
                            AlertDeliveryCoordinator(context).materializePending()
                        }
                        onConfigurationChanged()
                        enabled = config.enabled
                        appPasswordInput = ""
                        feedback = if (requestedEnabled && !config.enabled) {
                            "Saved, but Gmail remains disabled until the account, App Password and recipient are present."
                        } else "Gmail configuration saved."
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading
            ) { Text("Save configuration") }
            OutlinedButton(
                onClick = {
                    runAsync {
                        val validation = validateGmailInputs(account, recipientText)
                        val password = withContext(Dispatchers.IO) { passwordForOperation() }
                        if (validation.error != null) {
                            feedback = validation.error
                            return@runAsync
                        }
                        if (password == null || !GmailSmtpProtocol.isValidAppPassword(password)) {
                            feedback = "Enter the 16-character Google App Password first."
                            return@runAsync
                        }
                        val message = AlertMessage(
                            eventId = "gmail-test-${UUID.randomUUID()}",
                            kind = AlertKind.TEST,
                            title = "FP GRID MONITOR TEST",
                            body = "SIMULATION\n\nDevice: $deviceName\nGmail alerts can reach this address."
                        )
                        val results = withContext(Dispatchers.IO) {
                            validation.recipients.map { recipient ->
                                client.send(account.trim(), password, recipient, message)
                            }
                        }
                        val sent = results.count { it is DeliveryResult.Sent }
                        val failure = results.firstOrNull { it !is DeliveryResult.Sent }
                        feedback = if (sent == results.size) {
                            "Test email sent to $sent address(es)."
                        } else {
                            "Sent to $sent of ${results.size} addresses. ${gmailFailureText(failure)}"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading
            ) { Text("Send test email") }
        }

        if (loading) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
        }
        feedback?.let {
            GmailCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                Text(it, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }

        SectionTitle("Security")
        GmailCard {
            Text(
                "The App Password is encrypted with Android Keystore, excluded from backup and never shown again. It can be revoked at any time from the Google account. A dedicated sending account limits exposure.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            if (!confirmRemove) {
                TextButton(onClick = { confirmRemove = true }, enabled = !loading) {
                    Text("Remove Gmail configuration", color = MaterialTheme.colorScheme.error)
                }
            } else {
                Text("This removes the stored App Password, account and all recipients.",
                    color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        runAsync {
                            withContext(Dispatchers.IO) { store.clear() }
                            config = store.config()
                            enabled = false
                            account = ""
                            appPasswordInput = ""
                            recipientText = ""
                            confirmRemove = false
                            feedback = "Gmail configuration removed."
                            onConfigurationChanged()
                        }
                    }) { Text("Remove") }
                    TextButton(onClick = { confirmRemove = false }) { Text("Cancel") }
                }
            }
        }
    }
}

private data class GmailInputValidation(val recipients: List<String>, val error: String?)

private fun validateGmailInputs(account: String, recipientText: String): GmailInputValidation {
    if (!GmailSmtpProtocol.isValidAccount(account.trim())) {
        return GmailInputValidation(emptyList(), "Enter a valid Google account email address.")
    }
    val entries = recipientText.split('\n', ',', ';').map(String::trim).filter(String::isNotEmpty)
    if (entries.isEmpty()) return GmailInputValidation(emptyList(), "Enter at least one recipient.")
    val invalid = entries.firstOrNull { !ResendEmailProtocol.isValidEmailAddress(it) }
    if (invalid != null) return GmailInputValidation(emptyList(), "Fix the invalid recipient: $invalid")
    return GmailInputValidation(entries.distinctBy { it.lowercase() }, null)
}

@Composable
private fun SectionTitle(value: String) {
    Text(value, style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun GmailCard(
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

private fun gmailFailureText(result: DeliveryResult?): String = when (result) {
    is DeliveryResult.RetryableFailure -> result.reason
    is DeliveryResult.PermanentFailure -> result.reason
    else -> "Unknown delivery error"
}

private const val APP_PASSWORDS_URL = "https://myaccount.google.com/apppasswords"
