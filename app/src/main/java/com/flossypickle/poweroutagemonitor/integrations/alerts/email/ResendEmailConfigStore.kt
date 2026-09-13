package com.flossypickle.poweroutagemonitor.integrations.alerts.email

import android.content.Context
import com.flossypickle.poweroutagemonitor.storage.EnabledAlertProvidersStore
import com.flossypickle.poweroutagemonitor.storage.SecureSecretStore
import org.json.JSONArray

/** Non-secret email settings plus a Keystore-encrypted, user-supplied Resend API key. */
internal class ResendEmailConfigStore(context: Context) {
    data class Config(
        val enabled: Boolean,
        val sender: String,
        val recipients: List<String>,
        val hasApiKey: Boolean
    )

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
    private val secrets = SecureSecretStore(context)
    private val enabledProviders = EnabledAlertProvidersStore(context)

    fun config() = Config(
        enabled = preferences.getBoolean(KEY_ENABLED, false),
        sender = preferences.getString(KEY_SENDER, "").orEmpty(),
        recipients = readRecipients(),
        hasApiKey = secrets.contains(SECRET_API_KEY)
    )

    fun apiKey(): String? = secrets.get(SECRET_API_KEY)

    fun save(newApiKey: String?, enabled: Boolean, sender: String, recipients: List<String>) {
        val key = newApiKey?.trim()?.takeIf(String::isNotEmpty)
        if (key != null) secrets.put(SECRET_API_KEY, key)
        val cleanSender = sender.trim()
        val cleanRecipients = recipients.map(String::trim)
            .filter(ResendEmailProtocol::isValidEmailAddress)
            .distinctBy(String::lowercase)
        val actuallyEnabled = enabled && (key != null || apiKey() != null) &&
            ResendEmailProtocol.isValidSender(cleanSender) && cleanRecipients.isNotEmpty()
        check(preferences.edit()
            .putBoolean(KEY_ENABLED, actuallyEnabled)
            .putString(KEY_SENDER, cleanSender)
            .putString(KEY_RECIPIENTS, JSONArray(cleanRecipients).toString())
            .commit()) { "Unable to persist email configuration" }
        enabledProviders.setEnabled(PROVIDER_ID, actuallyEnabled)
    }

    fun clear() {
        preferences.edit().clear().commit()
        secrets.remove(SECRET_API_KEY)
        enabledProviders.setEnabled(PROVIDER_ID, false)
    }

    private fun readRecipients(): List<String> = runCatching {
        val array = JSONArray(preferences.getString(KEY_RECIPIENTS, "[]"))
        buildList {
            for (index in 0 until array.length()) add(array.getString(index))
        }
    }.getOrDefault(emptyList())

    companion object {
        const val PROVIDER_ID = "resend_email"
        private const val PREFERENCES_FILE = "resend_email_config"
        private const val SECRET_API_KEY = "resend_email_api_key"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_SENDER = "sender"
        private const val KEY_RECIPIENTS = "recipients"
    }
}
