package com.flossypickle.poweroutagemonitor.configuration

import android.content.Context
import com.flossypickle.poweroutagemonitor.storage.SecureSecretStore

/** Stores the automatic-backup plan; its password remains encrypted by Android Keystore. */
internal class BackupScheduleStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val secrets = SecureSecretStore(context)

    data class Settings(
        val enabled: Boolean = false,
        val folderUri: String? = null,
        val folderLabel: String? = null,
        val intervalHours: Long = DEFAULT_INTERVAL_HOURS,
        val retainedCopies: Int = DEFAULT_RETAINED_COPIES,
        val categories: Set<BackupCategory> = BackupCategory.entries.toSet(),
        val hasPassword: Boolean = false
    )

    data class Status(
        val lastAttemptAtEpochMs: Long? = null,
        val lastSuccessAtEpochMs: Long? = null,
        val lastError: String? = null
    )

    fun settings(): Settings {
        val categories = preferences.getStringSet(KEY_CATEGORIES, null)
            ?.mapNotNull { name -> BackupCategory.entries.firstOrNull { it.name == name } }
            ?.toSet()
            ?.takeIf(Set<BackupCategory>::isNotEmpty)
            ?: BackupCategory.entries.toSet()
        return Settings(
            enabled = preferences.getBoolean(KEY_ENABLED, false),
            folderUri = preferences.getString(KEY_FOLDER_URI, null),
            folderLabel = preferences.getString(KEY_FOLDER_LABEL, null),
            intervalHours = preferences.getLong(KEY_INTERVAL, DEFAULT_INTERVAL_HOURS)
                .takeIf { it in ALLOWED_INTERVAL_HOURS } ?: DEFAULT_INTERVAL_HOURS,
            retainedCopies = preferences.getInt(KEY_RETAINED, DEFAULT_RETAINED_COPIES)
                .takeIf { it in ALLOWED_RETAINED_COPIES } ?: DEFAULT_RETAINED_COPIES,
            categories = categories,
            hasPassword = secrets.contains(PASSWORD_SECRET)
        )
    }

    fun password(): String? = secrets.get(PASSWORD_SECRET)

    fun save(
        enabled: Boolean,
        folderUri: String?,
        folderLabel: String?,
        intervalHours: Long,
        retainedCopies: Int,
        categories: Set<BackupCategory>,
        password: String?
    ) {
        require(intervalHours in ALLOWED_INTERVAL_HOURS)
        require(retainedCopies in ALLOWED_RETAINED_COPIES)
        require(categories.isNotEmpty())
        require(!enabled || !folderUri.isNullOrBlank())
        val existingPassword = this.password()
        val effectivePassword = password ?: existingPassword
        require(!enabled || (effectivePassword?.length ?: 0) >= PasswordBackupCipher.MIN_PASSWORD_LENGTH)
        if (password != null) {
            require(password.length >= PasswordBackupCipher.MIN_PASSWORD_LENGTH)
            secrets.put(PASSWORD_SECRET, password)
        }
        check(preferences.edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_FOLDER_URI, folderUri)
            .putString(KEY_FOLDER_LABEL, folderLabel)
            .putLong(KEY_INTERVAL, intervalHours)
            .putInt(KEY_RETAINED, retainedCopies)
            .putStringSet(KEY_CATEGORIES, categories.mapTo(mutableSetOf(), BackupCategory::name))
            .commit()
        ) { "Unable to save automatic backup settings" }
    }

    /** Restores portable choices and the secret, but never assumes another device owns the folder grant. */
    fun restorePortable(settings: Settings, password: String?) {
        save(
            enabled = false,
            folderUri = null,
            folderLabel = settings.folderLabel,
            intervalHours = settings.intervalHours,
            retainedCopies = settings.retainedCopies,
            categories = settings.categories,
            password = password
        )
    }

    fun status() = Status(
        lastAttemptAtEpochMs = optionalLong(KEY_LAST_ATTEMPT),
        lastSuccessAtEpochMs = optionalLong(KEY_LAST_SUCCESS),
        lastError = preferences.getString(KEY_LAST_ERROR, null)
    )

    fun recordSuccess(nowEpochMs: Long = System.currentTimeMillis()) {
        preferences.edit()
            .putLong(KEY_LAST_ATTEMPT, nowEpochMs)
            .putLong(KEY_LAST_SUCCESS, nowEpochMs)
            .remove(KEY_LAST_ERROR)
            .commit()
    }

    fun recordFailure(message: String, nowEpochMs: Long = System.currentTimeMillis()) {
        preferences.edit()
            .putLong(KEY_LAST_ATTEMPT, nowEpochMs)
            .putString(KEY_LAST_ERROR, message.take(180))
            .commit()
    }

    private fun optionalLong(key: String): Long? =
        if (preferences.contains(key)) preferences.getLong(key, 0) else null

    companion object {
        val ALLOWED_INTERVAL_HOURS = listOf(6L, 12L, 24L, 72L, 168L)
        val ALLOWED_RETAINED_COPIES = listOf(3, 7, 14, 30)
        const val DEFAULT_INTERVAL_HOURS = 24L
        const val DEFAULT_RETAINED_COPIES = 7
        private const val FILE_NAME = "backup_schedule"
        private const val PASSWORD_SECRET = "automatic_backup_password"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_FOLDER_URI = "folder_uri"
        private const val KEY_FOLDER_LABEL = "folder_label"
        private const val KEY_INTERVAL = "interval_hours"
        private const val KEY_RETAINED = "retained_copies"
        private const val KEY_CATEGORIES = "categories"
        private const val KEY_LAST_ATTEMPT = "last_attempt"
        private const val KEY_LAST_SUCCESS = "last_success"
        private const val KEY_LAST_ERROR = "last_error"
    }
}
