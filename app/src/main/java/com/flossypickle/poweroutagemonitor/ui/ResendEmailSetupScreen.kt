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
import com.flossypickle.poweroutagemonitor.integrations.alerts.email.ResendEmailClient
import com.flossypickle.poweroutagemonitor.integrations.alerts.email.ResendEmailConfigStore
import com.flossypickle.poweroutagemonitor.integrations.alerts.email.ResendEmailProtocol
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ResendEmailSetupScreen(
    deviceName: String,
    padding: PaddingValues,
    onConfigurationChanged: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val store = remember(context) { ResendEmailConfigStore(context) }
    val client = remember { ResendEmailClient() }
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(store.config()) }
    var apiKeyInput by remember { mutableStateOf("") }
    var sender by remember { mutableStateOf(config.sender) }
    var recipientText by remember { mutableStateOf(config.recipients.joinToString("\n")) }
    var enabled by remember { mutableStateOf(config.enabled) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    fun apiKeyForOperation() = apiKeyInput.trim().takeIf(String::isNotEmpty) ?: store.apiKey()
    fun runAsync(operation: suspend () -> Unit) {
        if (loading) return
        scope.launch {
            loading = true
            feedback = null
            try {
                operation()
            } finally {
                loading = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp).widthIn(max = 600.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TextButton(onClick = onBack) { Text("‹ Email providers") }
        Text(
            "Resend",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            "Advanced email delivery through a Resend account and a domain you control.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text("Setup", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary)
        EmailCard {
            Text("1. Create a Resend account and verify a sending domain or subdomain.")
            Text("2. Create a sending-only API key restricted to that domain.")
            Text("3. Enter the sender and recipient addresses below.")
            Text("4. Save, send a test, then enable email alerts.")
            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RESEND_SETUP_URL)))
                    }.onFailure { feedback = "No browser is available to open Resend." }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Open Resend setup") }
            Text(
                "There is no reliable public no-login mail relay. The account is used once for setup; outage sends do not show a login screen.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }

        Text("Credentials", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary)
        EmailCard {
            OutlinedTextField(
                value = apiKeyInput,
                onValueChange = { apiKeyInput = it.trim() },
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(if (config.hasApiKey) {
                        "New API key (stored key unchanged if blank)"
                    } else "Resend API key")
                },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                enabled = !loading
            )
            if (config.hasApiKey) {
                Text(
                    "An API key is stored with Android Keystore encryption.",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp
                )
            }
            OutlinedTextField(
                value = sender,
                onValueChange = { if (it.length <= 254) sender = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Sender") },
                supportingText = { Text("Example: FP Grid Monitor <alerts@alerts.example.com>") },
                singleLine = true,
                enabled = !loading
            )
        }

        Text("Recipients", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary)
        EmailCard {
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

        Text("Activation", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary)
        EmailCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Enable email alerts", fontWeight = FontWeight.Medium)
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
                        val validation = validateEmailInputs(sender, recipientText)
                        if (validation.error != null) {
                            feedback = validation.error
                            return@runAsync
                        }
                        val requestedEnabled = enabled
                        runCatching {
                            withContext(Dispatchers.IO) {
                                store.save(
                                    apiKeyInput,
                                    enabled,
                                    sender,
                                    validation.recipients
                                )
                            }
                        }.onSuccess {
                            config = withContext(Dispatchers.IO) { store.config() }
                            withContext(Dispatchers.IO) {
                                AlertDeliveryCoordinator(context).materializePending()
                            }
                            onConfigurationChanged()
                            enabled = config.enabled
                            apiKeyInput = ""
                            feedback = if (requestedEnabled && !config.enabled) {
                                "Saved, but email remains disabled until the API key, sender and recipient are present."
                            } else "Email configuration saved."
                        }.onFailure {
                            feedback = "Email configuration could not be saved."
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading
            ) { Text("Save configuration") }
            OutlinedButton(
                onClick = {
                    runAsync {
                        val validation = validateEmailInputs(sender, recipientText)
                        val apiKey = withContext(Dispatchers.IO) { apiKeyForOperation() }
                        if (apiKey == null) {
                            feedback = "Enter a Resend API key first."
                            return@runAsync
                        }
                        if (!ResendEmailProtocol.isValidApiKey(apiKey)) {
                            feedback = "The Resend API key format is not valid."
                            return@runAsync
                        }
                        if (validation.error != null || validation.recipients.isEmpty()) {
                            feedback = validation.error ?: "Enter at least one recipient."
                            return@runAsync
                        }
                        val message = AlertMessage(
                            eventId = "email-test-${UUID.randomUUID()}",
                            kind = AlertKind.TEST,
                            title = "FP GRID MONITOR TEST",
                            body = "SIMULATION\n\nDevice: $deviceName\nEmail alerts can reach this address."
                        )
                        val results = withContext(Dispatchers.IO) {
                            validation.recipients.map { recipient ->
                                client.send(apiKey, sender.trim(), recipient, message)
                            }
                        }
                        val sent = results.count { it is DeliveryResult.Sent }
                        val failure = results.firstOrNull { it !is DeliveryResult.Sent }
                        feedback = if (sent == results.size) {
                            "Test email sent to $sent address(es)."
                        } else {
                            "Sent to $sent of ${results.size} addresses. ${emailFailureText(failure)}"
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
            EmailCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                Text(it, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }

        Text("Security", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary)
        EmailCard {
            Text(
                "The API key is supplied by you, encrypted with Android Keystore, excluded from backup and never shown again. Use a sending-only key restricted to the verified domain.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            if (!confirmRemove) {
                TextButton(onClick = { confirmRemove = true }, enabled = !loading) {
                    Text("Remove email configuration", color = MaterialTheme.colorScheme.error)
                }
            } else {
                Text(
                    "This removes the stored API key, sender and all recipients.",
                    color = MaterialTheme.colorScheme.error
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        runAsync {
                            withContext(Dispatchers.IO) { store.clear() }
                            config = store.config()
                            enabled = false
                            apiKeyInput = ""
                            sender = ""
                            recipientText = ""
                            confirmRemove = false
                            feedback = "Email configuration removed."
                            onConfigurationChanged()
                        }
                    }) { Text("Remove") }
                    TextButton(onClick = { confirmRemove = false }) { Text("Cancel") }
                }
            }
        }
    }
}

private data class EmailInputValidation(val recipients: List<String>, val error: String?)

private fun validateEmailInputs(sender: String, recipientText: String): EmailInputValidation {
    if (sender.isNotBlank() && !ResendEmailProtocol.isValidSender(sender)) {
        return EmailInputValidation(emptyList(), "Enter a valid sender email address.")
    }
    val entries = recipientText.split('\n', ',', ';').map(String::trim).filter(String::isNotEmpty)
    val invalid = entries.firstOrNull { !ResendEmailProtocol.isValidEmailAddress(it) }
    if (invalid != null) {
        return EmailInputValidation(emptyList(), "Fix the invalid recipient: $invalid")
    }
    return EmailInputValidation(entries.distinctBy(String::lowercase), null)
}

@Composable
private fun EmailCard(
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

private fun emailFailureText(result: DeliveryResult?): String = when (result) {
    is DeliveryResult.RetryableFailure -> result.reason
    is DeliveryResult.PermanentFailure -> result.reason
    else -> "Unknown delivery error"
}

private const val RESEND_SETUP_URL = "https://resend.com/domains"
