package com.flossypickle.poweroutagemonitor.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertDeliveryCoordinator
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertKind
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertMessage
import com.flossypickle.poweroutagemonitor.integrations.alerts.DeliveryResult
import com.flossypickle.poweroutagemonitor.integrations.alerts.sms.SmsCapability
import com.flossypickle.poweroutagemonitor.integrations.alerts.sms.SmsClient
import com.flossypickle.poweroutagemonitor.integrations.alerts.sms.SmsConfigStore
import com.flossypickle.poweroutagemonitor.integrations.alerts.sms.SmsProtocol
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SmsSetupScreen(
    deviceName: String,
    padding: PaddingValues,
    onConfigurationChanged: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val store = remember(context) { SmsConfigStore(context) }
    val client = remember(context) { SmsClient(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(store.config()) }
    var capability by remember { mutableStateOf(SmsCapability.capture(context)) }
    var recipientText by remember { mutableStateOf(config.recipients.joinToString("\n")) }
    var enabled by remember { mutableStateOf(config.enabled) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val requestSmsPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        capability = SmsCapability.capture(context)
        feedback = if (granted) {
            "SMS permission allowed."
        } else {
            "SMS permission was not allowed. Recipients can still be saved, but SMS remains off."
        }
    }
    val openAppSettings = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        capability = SmsCapability.capture(context)
        feedback = if (capability.permissionGranted) {
            "SMS permission allowed."
        } else {
            "SMS permission is still not allowed."
        }
    }

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
        TextButton(onClick = onBack) { Text("‹ Settings") }
        Text("Device SMS", style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold)
        Text(
            "Send through this phone's SIM and mobile network when internet service is unavailable.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SmsSectionTitle("Availability")
        SmsCard {
            SmsStatusRow("SMS hardware", if (capability.supported) "Available" else "Not available")
            SmsStatusRow("Permission", if (capability.permissionGranted) "Allowed" else "Not allowed")
            SmsStatusRow(
                "Default SMS SIM",
                if (capability.hasDefaultSubscription) "Selected" else "Not selected"
            )
            if (!capability.supported) {
                Text(
                    "Android reports that this phone or tablet cannot send SMS. The rest of the app still works normally.",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp
                )
            } else if (!capability.permissionGranted) {
                Text(
                    "FP Grid Monitor needs permission only to send the outage messages you configure. It does not read messages, contacts, call logs or phone identity.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                Text(
                    "Android may label its dialog “send and view SMS messages” for the whole permission group. This app requests SEND_SMS only and cannot read the SMS inbox.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                Button(
                    onClick = { requestSmsPermission.launch(Manifest.permission.SEND_SMS) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Allow SMS sending") }
                OutlinedButton(
                    onClick = {
                        openAppSettings.launch(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Open app permission settings") }
            } else if (!capability.hasDefaultSubscription) {
                Text(
                    "Choose a default SMS SIM in Android's SIM settings, then return here. This prevents an unattended alert from using an arbitrary SIM.",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp
                )
                OutlinedButton(
                    onClick = { capability = SmsCapability.capture(context) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Check again") }
            }
        }

        SmsSectionTitle("Recipients")
        SmsCard {
            OutlinedTextField(
                value = recipientText,
                onValueChange = { recipientText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Phone numbers") },
                supportingText = { Text("One per line; include country code, for example +356…") },
                minLines = 3,
                enabled = !loading
            )
            Text(
                "Each number is sent and retried independently. Normal carrier SMS charges may apply.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }

        SmsSectionTitle("Activation")
        SmsCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Enable SMS alerts", fontWeight = FontWeight.Medium)
                    Text(
                        "Send confirmed outage and restoration alerts to every saved number.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                    enabled = capability.supported && capability.permissionGranted &&
                        capability.hasDefaultSubscription && !loading
                )
            }
            Button(
                onClick = {
                    val validation = validateSmsRecipients(recipientText)
                    if (validation.error != null) {
                        feedback = validation.error
                        return@Button
                    }
                    val canEnable = capability.supported && capability.permissionGranted &&
                        capability.hasDefaultSubscription
                    val requestedEnabled = enabled
                    store.save(requestedEnabled && canEnable, validation.recipients)
                    config = store.config()
                    enabled = config.enabled
                    AlertDeliveryCoordinator(context).materializePending()
                    onConfigurationChanged()
                    feedback = if (!canEnable && requestedEnabled) {
                        "Recipients saved. SMS remains off until the availability checks pass."
                    } else "SMS configuration saved."
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading
            ) { Text("Save configuration") }
            OutlinedButton(
                onClick = {
                    runAsync {
                        val validation = validateSmsRecipients(recipientText)
                        if (validation.error != null) {
                            feedback = validation.error
                            return@runAsync
                        }
                        capability = SmsCapability.capture(context)
                        if (!capability.supported) {
                            feedback = "This device does not support SMS messaging."
                            return@runAsync
                        }
                        if (!capability.permissionGranted) {
                            feedback = "Allow SMS sending first."
                            return@runAsync
                        }
                        if (!capability.hasDefaultSubscription) {
                            feedback = "Choose a default SMS SIM in Android settings first."
                            return@runAsync
                        }
                        val message = AlertMessage(
                            eventId = "sms-test-${UUID.randomUUID()}",
                            kind = AlertKind.TEST,
                            title = "FP GRID MONITOR TEST",
                            body = "SIMULATION\n\nDevice: $deviceName\nSMS alerts can reach this number."
                        )
                        val results = withContext(Dispatchers.IO) {
                            validation.recipients.map { client.send(it, message) }
                        }
                        val sent = results.count { it is DeliveryResult.Sent }
                        val failure = results.firstOrNull { it !is DeliveryResult.Sent }
                        feedback = if (sent == results.size) {
                            "Test SMS accepted for $sent number(s)."
                        } else {
                            "Accepted for $sent of ${results.size} numbers. ${smsFailureText(failure)}"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading && capability.supported
            ) { Text("Send test SMS") }
        }

        if (loading) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
        }
        feedback?.let {
            SmsCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                Text(it, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }

        SmsSectionTitle("Privacy & distribution")
        SmsCard {
            Text(
                "Phone numbers stay on this device and are excluded from backup. Android reports when each SMS part is accepted by the phone's radio; carrier delivery receipts are not guaranteed.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            Text(
                "Direct APK installs can request SEND_SMS normally. Google Play applies a separate restricted-permission review, so a later Play release may omit this provider.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            if (!confirmRemove) {
                TextButton(onClick = { confirmRemove = true }, enabled = !loading) {
                    Text("Remove SMS configuration", color = MaterialTheme.colorScheme.error)
                }
            } else {
                Text("This removes every saved recipient and disables SMS.",
                    color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        store.clear()
                        config = store.config()
                        enabled = false
                        recipientText = ""
                        confirmRemove = false
                        feedback = "SMS configuration removed."
                        onConfigurationChanged()
                    }) { Text("Remove") }
                    TextButton(onClick = { confirmRemove = false }) { Text("Cancel") }
                }
            }
        }
    }
}

private data class SmsInputValidation(val recipients: List<String>, val error: String?)

private fun validateSmsRecipients(value: String): SmsInputValidation {
    val entries = value.split('\n', ',', ';').map(String::trim).filter(String::isNotEmpty)
    if (entries.isEmpty()) return SmsInputValidation(emptyList(), "Enter at least one phone number.")
    val invalid = entries.firstOrNull { !SmsProtocol.isValidNumber(it) }
    if (invalid != null) return SmsInputValidation(emptyList(), "Fix the invalid phone number: $invalid")
    return SmsInputValidation(entries.map(SmsProtocol::normalizeNumber).distinct(), null)
}

@Composable
private fun SmsSectionTitle(value: String) {
    Text(value, style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SmsStatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SmsCard(
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

private fun smsFailureText(result: DeliveryResult?): String = when (result) {
    is DeliveryResult.RetryableFailure -> result.reason
    is DeliveryResult.PermanentFailure -> result.reason
    else -> "Unknown SMS error"
}
