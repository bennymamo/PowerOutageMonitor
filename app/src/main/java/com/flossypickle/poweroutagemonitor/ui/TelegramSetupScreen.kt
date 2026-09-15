package com.flossypickle.poweroutagemonitor.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.verticalScroll
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
import com.flossypickle.poweroutagemonitor.integrations.alerts.DeliveryResult
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertDeliveryCoordinator
import com.flossypickle.poweroutagemonitor.integrations.alerts.telegram.TelegramClient
import com.flossypickle.poweroutagemonitor.integrations.alerts.telegram.TelegramConfigStore
import com.flossypickle.poweroutagemonitor.storage.MonitorStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class TelegramFeedbackArea { SETUP, CREDENTIALS, RECIPIENTS, ACTIVATION, SECURITY }

@Composable
internal fun TelegramSetupScreen(
    deviceName: String,
    helpLevel: MonitorStore.HelpLevel,
    padding: PaddingValues,
    onConfigurationChanged: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val store = remember(context) { TelegramConfigStore(context) }
    val client = remember { TelegramClient() }
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(store.config()) }
    var tokenInput by remember { mutableStateOf("") }
    var destinationText by remember {
        mutableStateOf(config.destinations.joinToString("\n") { "${it.chatId} | ${it.label}" })
    }
    var enabled by remember { mutableStateOf(config.enabled) }
    var botName by remember { mutableStateOf(config.botDisplayName) }
    var discovered by remember { mutableStateOf(emptyList<TelegramClient.Chat>()) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var feedbackArea by remember { mutableStateOf<TelegramFeedbackArea?>(null) }
    var loading by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    fun tokenForOperation(): String? = tokenInput.trim().takeIf(String::isNotEmpty) ?: store.botToken()
    fun runAsync(area: TelegramFeedbackArea, operation: suspend () -> Unit) {
        if (loading) return
        scope.launch {
            feedbackArea = area
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
        TextButton(onClick = onBack) { Text("‹ Settings") }
        Text("Telegram", style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold)
        SetupGuidanceCaption(helpLevel)
        Text("Use your own Telegram bot to send alerts directly from this device.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        Text("Setup", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary)
        TelegramCard {
            if (helpLevel.isGuided) {
                Text("1. Tap Open BotFather below. Telegram will open a verified bot that creates other bots.")
                Text("2. Send /newbot and follow its prompts for a name and username.")
                Text("3. Copy the token BotFather gives you into the secure field below.")
                Text("4. Open your new bot, tap Start or send /start, then return here and tap Find chats.")
                Text("5. Save, send a test, then enable Telegram alerts.")
            } else {
                Text("Create a bot with BotFather, paste its token, send /start to it, then discover chats.")
            }
            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/BotFather")))
                    }.onFailure {
                        feedbackArea = TelegramFeedbackArea.SETUP
                        feedback = "No app is available to open BotFather."
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Open BotFather") }
            TelegramOperationStatus(
                area = TelegramFeedbackArea.SETUP,
                activeArea = feedbackArea,
                loading = loading,
                feedback = feedback
            )
        }

        Text("Bot credentials", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary)
        TelegramCard {
            OutlinedTextField(
                value = tokenInput,
                onValueChange = { tokenInput = it.trim() },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (config.hasToken) "New bot token (stored token unchanged if blank)" else "Bot token") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                enabled = !loading
            )
            if (config.hasToken) {
                Text("A bot token is stored with Android Keystore encryption.",
                    color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
            }
            OutlinedButton(
                onClick = {
                    runAsync(TelegramFeedbackArea.CREDENTIALS) {
                        val token = withContext(Dispatchers.IO) { tokenForOperation() }
                        if (token == null) {
                            feedback = "Enter a bot token first."
                            return@runAsync
                        }
                        when (val result = withContext(Dispatchers.IO) { client.identifyBot(token) }) {
                            is TelegramClient.ApiResult.Success -> {
                                botName = result.value.displayName
                                feedback = "Connected to ${result.value.displayName}${result.value.username?.let { " (@$it)" }.orEmpty()}."
                            }
                            is TelegramClient.ApiResult.Failure -> feedback = result.message
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading
            ) { Text("Check bot token") }
            TelegramOperationStatus(
                area = TelegramFeedbackArea.CREDENTIALS,
                activeArea = feedbackArea,
                loading = loading,
                feedback = feedback
            )
        }

        Text("Recipients", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary)
        TelegramCard {
            OutlinedTextField(
                value = destinationText,
                onValueChange = { destinationText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Chat IDs, one per line") },
                supportingText = { Text("Optional label: chat-id | Garage") },
                minLines = 3,
                enabled = !loading
            )
            OutlinedButton(
                onClick = {
                    runAsync(TelegramFeedbackArea.RECIPIENTS) {
                        val token = withContext(Dispatchers.IO) { tokenForOperation() }
                        if (token == null) {
                            feedback = "Enter a bot token first."
                            return@runAsync
                        }
                        when (val result = withContext(Dispatchers.IO) { client.discoverChats(token) }) {
                            is TelegramClient.ApiResult.Success -> {
                                discovered = result.value
                                feedback = if (result.value.isEmpty()) {
                                    "No chats found yet. Open the new bot itself in Telegram, send a new message such as hello, then return and tap Find chats again."
                                } else "Found ${result.value.size} chat(s)."
                            }
                            is TelegramClient.ApiResult.Failure -> feedback = result.message
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading
            ) { Text("Find chats") }
            TelegramOperationStatus(
                area = TelegramFeedbackArea.RECIPIENTS,
                activeArea = feedbackArea,
                loading = loading,
                feedback = feedback
            )
            discovered.forEach { chat ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(chat.label, fontWeight = FontWeight.Medium)
                        Text("${chat.chatId} · ${chat.type}", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = {
                        val current = parseDestinations(destinationText)
                        if (current.none { it.chatId == chat.chatId }) {
                            destinationText = (current + TelegramConfigStore.ChatDestination(chat.chatId, chat.label))
                                .joinToString("\n") { "${it.chatId} | ${it.label}" }
                        }
                    }) { Text("Add") }
                }
            }
        }

        Text("Activation", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary)
        TelegramCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Enable Telegram alerts", fontWeight = FontWeight.Medium)
                    Text("Send confirmed outage and stable-restoration alerts to every saved chat.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
                Switch(checked = enabled, onCheckedChange = { enabled = it }, enabled = !loading)
            }
            Button(
                onClick = {
                    runAsync(TelegramFeedbackArea.ACTIVATION) {
                        val requestedEnabled = enabled
                        val destinations = parseDestinations(destinationText)
                        runCatching {
                            withContext(Dispatchers.IO) {
                                store.save(tokenInput, enabled, botName, destinations)
                            }
                        }.onSuccess {
                            config = withContext(Dispatchers.IO) { store.config() }
                            withContext(Dispatchers.IO) {
                                AlertDeliveryCoordinator(context).materializePending()
                            }
                            onConfigurationChanged()
                            enabled = config.enabled
                            tokenInput = ""
                            feedback = if (requestedEnabled && !config.enabled) {
                                "Saved, but Telegram remains disabled until a token and chat are present."
                            } else "Telegram configuration saved."
                        }.onFailure {
                            feedback = "Telegram configuration could not be saved."
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading
            ) { Text("Save configuration") }
            OutlinedButton(
                onClick = {
                    runAsync(TelegramFeedbackArea.ACTIVATION) {
                        val token = withContext(Dispatchers.IO) { tokenForOperation() }
                        val destinations = parseDestinations(destinationText)
                        if (token == null || destinations.isEmpty()) {
                            feedback = "Enter a token and at least one chat first."
                            return@runAsync
                        }
                        val results = withContext(Dispatchers.IO) {
                            destinations.map { destination ->
                                client.sendMessage(
                                    token,
                                    destination.chatId,
                                    "FP GRID MONITOR TEST\n\nDevice: $deviceName\nTelegram alerts can reach this chat.\n\nThis is a simulation."
                                )
                            }
                        }
                        val sent = results.count { it is DeliveryResult.Sent }
                        val firstFailure = results.filterNot { it is DeliveryResult.Sent }.firstOrNull()
                        feedback = if (sent == results.size) {
                            "Test message sent to $sent chat(s)."
                        } else {
                            "Sent to $sent of ${results.size} chats. ${failureText(firstFailure)}"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading
            ) { Text("Send test message") }
            TelegramOperationStatus(
                area = TelegramFeedbackArea.ACTIVATION,
                activeArea = feedbackArea,
                loading = loading,
                feedback = feedback
            )
        }

        Text("Security", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary)
        TelegramCard {
            Text("The bot token is encrypted with Android Keystore and never shown again. Android's automatic device backup excludes it; a password-encrypted FP Grid Monitor recovery archive includes it only when you select Alert channels and keys.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            if (!confirmRemove) {
                TextButton(onClick = { confirmRemove = true }, enabled = !loading) {
                    Text("Remove Telegram configuration", color = MaterialTheme.colorScheme.error)
                }
            } else {
                Text("This removes the stored token and all chat IDs.", color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        runAsync(TelegramFeedbackArea.SECURITY) {
                            withContext(Dispatchers.IO) { store.clear() }
                            config = store.config()
                            enabled = false
                            tokenInput = ""
                            destinationText = ""
                            botName = null
                            discovered = emptyList()
                            confirmRemove = false
                            feedback = "Telegram configuration removed."
                            onConfigurationChanged()
                        }
                    }) { Text("Remove") }
                    TextButton(onClick = { confirmRemove = false }) { Text("Cancel") }
                }
            }
            TelegramOperationStatus(
                area = TelegramFeedbackArea.SECURITY,
                activeArea = feedbackArea,
                loading = loading,
                feedback = feedback
            )
        }
    }
}

@Composable
private fun TelegramOperationStatus(
    area: TelegramFeedbackArea,
    activeArea: TelegramFeedbackArea?,
    loading: Boolean,
    feedback: String?
) {
    if (activeArea != area) return
    if (loading) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator()
            Text("Working…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else if (feedback != null) {
        Text(
            feedback,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun TelegramCard(
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)) {
        Column(Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

private fun parseDestinations(text: String): List<TelegramConfigStore.ChatDestination> = text
    .lineSequence()
    .mapNotNull { line ->
        val parts = line.split('|', limit = 2)
        val chatId = parts.firstOrNull()?.trim().orEmpty()
        if (chatId.isEmpty()) null else TelegramConfigStore.ChatDestination(
            chatId = chatId,
            label = parts.getOrNull(1)?.trim().orEmpty().ifEmpty { chatId }
        )
    }
    .distinctBy(TelegramConfigStore.ChatDestination::chatId)
    .toList()

private fun failureText(result: DeliveryResult?): String = when (result) {
    is DeliveryResult.RetryableFailure -> result.reason
    is DeliveryResult.PermanentFailure -> result.reason
    else -> "Unknown delivery error"
}
