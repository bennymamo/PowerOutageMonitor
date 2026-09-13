package com.flossypickle.poweroutagemonitor.integrations.alerts.email

import android.content.Context
import com.flossypickle.poweroutagemonitor.storage.EnabledAlertProvidersStore
import com.flossypickle.poweroutagemonitor.storage.SecureSecretStore
import org.json.JSONArray

/** Gmail SMTP settings with the user-supplied App Password encrypted by Android Keystore. */
internal class GmailSmtpConfigStore(context: Context) {
    data class Config(
        val enabled: Boolean,
        val account: String,
        val recipients: List<String>,
        val hasAppPassword: Boolean
    )

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
    private val secrets = SecureSecretStore(context)
    private val enabledProviders = EnabledAlertProvidersStore(context)

    fun config() = Config(
        enabled = preferences.getBoolean(KEY_ENABLED, false),
        account = preferences.getString(KEY_ACCOUNT, "").orEmpty(),
        recipients = readRecipients(),
        hasAppPassword = secrets.contains(SECRET_APP_PASSWORD)
    )

    fun appPassword(): String? = secrets.get(SECRET_APP_PASSWORD)

    fun save(newAppPassword: String?, enabled: Boolean, account: String, recipients: List<String>) {
        val password = newAppPassword?.let(GmailSmtpProtocol::normalizedAppPassword)
            ?.takeIf(String::isNotEmpty)
        if (password != null) secrets.put(SECRET_APP_PASSWORD, password)
        val cleanAccount = account.trim()
        val cleanRecipients = recipients.map(String::trim)
            .filter(ResendEmailProtocol::isValidEmailAddress)
            .distinctBy { it.lowercase() }
        val savedPassword = password ?: appPassword()
        val actuallyEnabled = enabled && savedPassword != null &&
            GmailSmtpProtocol.isValidAppPassword(savedPassword) &&
            GmailSmtpProtocol.isValidAccount(cleanAccount) && cleanRecipients.isNotEmpty()
        check(preferences.edit()
            .putBoolean(KEY_ENABLED, actuallyEnabled)
            .putString(KEY_ACCOUNT, cleanAccount)
            .putString(KEY_RECIPIENTS, JSONArray(cleanRecipients).toString())
            .commit()) { "Unable to persist Gmail configuration" }
        enabledProviders.setEnabled(PROVIDER_ID, actuallyEnabled)
    }

    fun clear() {
        preferences.edit().clear().commit()
        secrets.remove(SECRET_APP_PASSWORD)
        enabledProviders.setEnabled(PROVIDER_ID, false)
    }

    private fun readRecipients(): List<String> = runCatching {
        val array = JSONArray(preferences.getString(KEY_RECIPIENTS, "[]"))
        buildList {
            for (index in 0 until array.length()) add(array.getString(index))
        }
    }.getOrDefault(emptyList())

    companion object {
        const val PROVIDER_ID = "gmail_smtp"
        private const val PREFERENCES_FILE = "gmail_email_config"
        private const val SECRET_APP_PASSWORD = "gmail_smtp_app_password"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_ACCOUNT = "account"
        private const val KEY_RECIPIENTS = "recipients"
    }
}
