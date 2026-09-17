package com.flossypickle.poweroutagemonitor.integrations.alerts.telegram

/** Authorization and replay rules are independent of Telegram networking and Android. */
internal object TelegramRemotePolicy {
    data class Message(val updateId: Long, val chatId: String, val senderId: String,
        val privateChat: Boolean, val senderIsBot: Boolean, val forwarded: Boolean,
        val sentAtEpochMs: Long, val text: String)
    data class Command(val name: String, val argument: String)
    fun authorize(message: Message, trusted: Set<String>, enabledAt: Long,
        now: Long, nextOffset: Long): Command? {
        if (message.updateId < nextOffset || !message.privateChat || message.senderIsBot ||
            message.forwarded || message.chatId !in trusted || message.chatId != message.senderId ||
            message.chatId.toLongOrNull()?.let { it > 0 } != true ||
            message.sentAtEpochMs < enabledAt || now - message.sentAtEpochMs !in -30_000..300_000 ||
            message.text.length !in 1..200) return null
        val parts = message.text.trim().split(Regex("\\s+"), limit = 2)
        val name = parts.first().substringBefore('@').lowercase()
        if (!name.startsWith('/') || name.drop(1) !in commands.map { it.first }) return null
        return Command(name.drop(1), parts.getOrElse(1) { "" })
    }
    val commands = listOf(
        "status" to "Show grid, charger, EcoFlow and alarm status",
        "check_ecoflow" to "Run an EcoFlow check now",
        "stop_sound" to "Acknowledge and stop the current audible alarm",
        "quiet" to "Quiet automatic Telegram alerts temporarily",
        "unquiet" to "Resume automatic Telegram alerts",
        "monitor_on" to "Enable power monitoring",
        "monitor_off" to "Disable power monitoring; keep remote control",
        "charger_on" to "Enable charger watching",
        "charger_off" to "Use EcoFlow only; requires a ready EcoFlow source",
        "ecoflow_on" to "Resume configured EcoFlow assistance",
        "ecoflow_off" to "Pause EcoFlow; keep charger watching",
        "help" to "Show commands and what they change"
    )
}
