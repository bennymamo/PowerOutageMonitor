package com.flossypickle.poweroutagemonitor.integrations.alerts.telegram

import android.content.Context
import android.os.Build

internal class TelegramRemoteStore(context: Context) {
    data class Settings(val enabled: Boolean = false, val trustedChatIds: Set<String> = emptySet(),
        val longPolling: Boolean = true, val pollSeconds: Int = 5, val quietMinutes: Int = 60,
        val checkWarnings: Boolean = true, val quietUntilEpochMs: Long = 0) {
        init { require(pollSeconds in 2..60 && quietMinutes in 1..1440 && quietUntilEpochMs >= 0)
            require(!enabled || trustedChatIds.isNotEmpty())
            require(trustedChatIds.all { it.toLongOrNull()?.let { id -> id > 0 } == true }) }
    }
    private val storage = if (Build.VERSION.SDK_INT >= 24) context.createDeviceProtectedStorageContext() else context
    private val prefs = storage.getSharedPreferences("telegram_remote", Context.MODE_PRIVATE)
    fun settings() = Settings(prefs.getBoolean("enabled", false),
        prefs.getStringSet("trusted", emptySet()).orEmpty().toSet(), prefs.getBoolean("long_poll", true),
        prefs.getInt("poll_seconds", 5).coerceIn(2, 60), prefs.getInt("quiet_minutes", 60).coerceIn(1, 1440),
        prefs.getBoolean("check_warnings", true), prefs.getLong("quiet_until", 0).coerceAtLeast(0))
    fun save(settings: Settings) {
        val previous = settings()
        val edit = prefs.edit().putBoolean("enabled", settings.enabled)
            .putStringSet("trusted", settings.trustedChatIds).putBoolean("long_poll", settings.longPolling)
            .putInt("poll_seconds", settings.pollSeconds).putInt("quiet_minutes", settings.quietMinutes)
            .putBoolean("check_warnings", settings.checkWarnings).putLong("quiet_until", settings.quietUntilEpochMs)
        if (previous.enabled != settings.enabled || previous.trustedChatIds != settings.trustedChatIds) {
            edit.putLong("enabled_at", System.currentTimeMillis()).remove("offset").remove("token_hash")
        }
        check(edit.commit()) { "Unable to save remote control settings" }
    }
    fun quiet(until: Long) { check(prefs.edit().putLong("quiet_until", until).commit()) }
    fun isQuiet(now: Long = System.currentTimeMillis()) = settings().quietUntilEpochMs > now
    fun enabledAt() = prefs.getLong("enabled_at", Long.MAX_VALUE)
    fun offset(tokenHash: String): Long? = if (prefs.getString("token_hash", null) == tokenHash && prefs.contains("offset")) prefs.getLong("offset", 0) else null
    fun checkpoint(tokenHash: String, offset: Long) { check(prefs.edit().putString("token_hash", tokenHash).putLong("offset", offset).commit()) }
    fun resetCheckpoint() { check(prefs.edit().remove("offset").remove("token_hash").putLong("enabled_at", System.currentTimeMillis()).commit()) }
    fun health(message: String) { prefs.edit().putString("health", message.take(300)).putLong("health_at", System.currentTimeMillis()).apply() }
    fun health() = prefs.getString("health", "Not connected yet").orEmpty()
}
