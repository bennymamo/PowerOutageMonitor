package com.flossypickle.poweroutagemonitor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.flossypickle.poweroutagemonitor.integrations.alerts.telegram.*
import com.flossypickle.poweroutagemonitor.monitoring.MonitoringService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun TelegramRemoteSettingsContent(onOpenTelegram: () -> Unit) {
    val context = LocalContext.current
    val store = remember { TelegramRemoteStore(context) }
    var saved by remember { mutableStateOf(store.settings()) }
    var enabled by remember { mutableStateOf(saved.enabled) }
    var trusted by remember { mutableStateOf(saved.trustedChatIds) }
    var longPolling by remember { mutableStateOf(saved.longPolling) }
    var seconds by remember { mutableStateOf(saved.pollSeconds.toString()) }
    var quietMinutes by remember { mutableStateOf(saved.quietMinutes.toString()) }
    var warnings by remember { mutableStateOf(saved.checkWarnings) }
    var step by rememberSaveable { mutableIntStateOf(if (saved.enabled) 3 else 0) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var health by remember { mutableStateOf(store.health()) }
    val scope = rememberCoroutineScope()
    val telegram = TelegramConfigStore(context)
    val privateChats = (telegram.config().destinations.filter { it.chatId.toLongOrNull()?.let { id -> id > 0 } == true } +
        saved.trustedChatIds.map { TelegramConfigStore.ChatDestination(it, "Previously allowed chat ($it)") }).distinctBy { it.chatId }
    val canSave = (!enabled || telegram.config().hasToken && trusted.isNotEmpty()) &&
        seconds.toIntOrNull() in 2..60 && quietMinutes.toIntOrNull() in 1..1440
    LaunchedEffect(Unit) { while (true) { health = store.health(); saved = store.settings(); delay(2000) } }
    SettingsCard {
        Text("Control this phone from your Telegram bot", style = MaterialTheme.typography.titleMedium)
        Text("Step ${step + 1} of 4 · ${listOf("Prepare your bot", "Allow your private chat", "Choose behavior", "Finish & try it")[step]}")
        when (step) {
            0 -> {
                Text("Set up Telegram alerts first. Open a private chat with your bot, send /start, then save that chat in Telegram setup.")
                CompactActions {
                    OutlinedButton(onClick = onOpenTelegram) { Text("Telegram setup") }
                    TextButton(onClick = { step = 2 }) { Text("I know my way around") }
                }
            }
            1 -> {
                Text("Only people in the private chats you select can control this phone. Groups can still receive alerts, but cannot control it.")
                if (privateChats.isEmpty()) {
                    Text("No private destination saved yet.")
                    OutlinedButton(onClick = onOpenTelegram) { Text("Add a private chat") }
                }
                privateChats.forEach { chat ->
                    SettingSwitch(chat.label, "Allow this person to control monitoring", chat.chatId in trusted) {
                        trusted = if (it) trusted + chat.chatId else trusted - chat.chatId
                    }
                }
            }
            2 -> {
                SettingSwitch("Remote control", "Keep commands available even while monitoring is off", enabled) { enabled = it }
                SettingSwitch("Long polling (recommended)", "Commands arrive promptly without a public server", longPolling) { longPolling = it }
                if (!longPolling) OutlinedTextField(seconds, { seconds = it }, label = { Text("Check every 2–60 seconds") }, singleLine = true)
                OutlinedTextField(quietMinutes, { quietMinutes = it }, label = { Text("Default quiet time · 1–1440 minutes") }, singleLine = true)
                SettingSwitch("EcoFlow check warnings", "Routine failed-check warnings while the charger is powered. Notifications when the charger has no power are controlled in EcoFlow monitoring settings.", warnings) { warnings = it }
                if (trusted.isEmpty()) TextButton(onClick = { step = 1 }) { Text("Choose a trusted chat") }
            }
            3 -> {
                if (enabled && !telegram.config().hasToken) Text("Save your bot token in Telegram setup first.")
                if (enabled && trusted.isEmpty()) Text("Choose at least one trusted private chat before enabling remote control.")
                Text("Save, then install the command menu. Open your bot and send /status. Try /stop_sound or /quiet when needed.")
                Text("${trusted.size} trusted private chat(s) · Remote control ${if(enabled) "on" else "off"}")
                CompactActions {
                    Button(enabled = canSave && !busy, onClick = {
                        store.save(TelegramRemoteStore.Settings(enabled, trusted, longPolling, seconds.toInt(), quietMinutes.toInt(), warnings,
                            store.settings().quietUntilEpochMs))
                        saved = store.settings(); MonitoringService.syncHosting(context)
                        message = if (enabled) "Saved. The receiver is starting; install the menu next." else "Saved. Remote control is off."
                    }) { Text("Save") }
                    OutlinedButton(enabled = saved.enabled && !busy, onClick = {
                        busy = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                val token = telegram.botToken()
                                if (token == null) "Save a bot token first." else {
                                    val api = TelegramClient()
                                    val results = saved.trustedChatIds.map { api.installCommandMenu(token, it) }
                                    val failure = results.filterIsInstance<TelegramClient.ApiResult.Failure>().firstOrNull()
                                    failure?.message ?: "Command menu installed. Open your bot and send /status."
                                }
                            }
                            message = result; busy = false
                        }
                    }) { Text(if(busy) "Installing…" else "Install menu") }
                }
            }
        }
        CompactActions {
            if (step > 0) TextButton(onClick = { step-- }) { Text("Previous") }
            if (step < 3) Button(onClick = { step++ }) { Text("Next") }
            if (step == 3) TextButton(onClick = { step = 1 }) { Text("Edit access") }
        }
        message?.let { Text(it) }
    }
    if (saved.enabled) SettingsCard {
        Text("Receiver", style = MaterialTheme.typography.titleMedium)
        Text(health)
        Text("Monitoring and remote control share one small ongoing notification.")
        if (store.isQuiet()) {
            Text("Automatic Telegram alerts are temporarily quiet.")
            TextButton(onClick = { store.quiet(0); saved = store.settings() }) { Text("Resume Telegram alerts") }
        }
    }
    ExpandableSettingsSection("Commands", "Status, sound, alerts and power sources") {
        TelegramRemotePolicy.commands.forEach { (name, explanation) ->
            Text("/$name", style = MaterialTheme.typography.labelLarge)
            Text(explanation, style = MaterialTheme.typography.bodySmall)
        }
    }
    ExpandableSettingsSection("How it works", "Privacy, battery use and reconnecting") {
        Text("Use one command receiver per bot. Another phone, Home Assistant receiver or webhook can prevent commands arriving. A bot dedicated to this phone is simplest.")
        Text("To find a new chat later, disable remote control here and save, then send /start and use Find chats in Telegram setup. Return here to allow the chat and enable control again.")
        Text("Unlock the phone once after a reboot so protected account credentials become available.")
        Text("Long polling waits up to 25 seconds per request and returns immediately when a command arrives. There is no incoming server to expose. Android can still delay networking; unrestricted battery use and reliable Wi-Fi help.")
        Text("Turning monitoring off closes power checks and stops alarms. Remote control keeps the same notification available so /monitor_on works. Disable both to remove it.")
        Text("Quiet skips automatic Telegram alerts without replaying them later. Command replies, other alert channels and sound still work. /quiet 30 sets 30 minutes; /unquiet resumes alerts.")
        Text("Old commands are discarded when first enabled or restored. Commands older than five minutes, forwarded messages and untrusted chats cannot change monitoring. Protect your Telegram account and bot token.")
        Text("/charger_off requires ready EcoFlow monitoring. /ecoflow_off keeps charger watching on; use /monitor_off to stop all monitoring. None of these commands controls inverter charging or output.")
    }
}
