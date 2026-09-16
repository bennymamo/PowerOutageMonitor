package com.flossypickle.poweroutagemonitor.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.flossypickle.poweroutagemonitor.configuration.BackupCategory
import com.flossypickle.poweroutagemonitor.configuration.BackupDocument
import com.flossypickle.poweroutagemonitor.configuration.BackupManager
import com.flossypickle.poweroutagemonitor.configuration.BackupScheduleStore
import com.flossypickle.poweroutagemonitor.configuration.BackupScheduler
import com.flossypickle.poweroutagemonitor.configuration.PasswordBackupCipher
import com.flossypickle.poweroutagemonitor.storage.MonitorStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal enum class BackupPanel {
    CREATE,
    AUTOMATIC,
    RESTORE
}

@Composable
internal fun DataBackupSettingsContent(
    settings: MonitorStore.Settings,
    onRestore: (BackupDocument, Set<BackupCategory>, Boolean) -> String?,
    panel: BackupPanel
) {
    val context = LocalContext.current
    val manager = remember(context) { BackupManager(context) }
    val scheduleStore = remember(context) { BackupScheduleStore(context) }
    val scheduler = remember(context) { BackupScheduler(context) }
    val scope = rememberCoroutineScope()
    var backupCategories by remember { mutableStateOf(BackupCategory.entries.toSet()) }
    var restoreCategories by remember { mutableStateOf(emptySet<BackupCategory>()) }
    var exportPassword by remember { mutableStateOf("") }
    var exportConfirmation by remember { mutableStateOf("") }
    var importPassword by remember { mutableStateOf("") }
    var pendingRestore by remember { mutableStateOf<BackupDocument?>(null) }
    var resumeMonitoring by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var feedbackIsError by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var scheduleSettings by remember { mutableStateOf(scheduleStore.settings()) }
    var scheduleStatus by remember { mutableStateOf(scheduleStore.status()) }
    var automaticEnabled by remember { mutableStateOf(scheduleSettings.enabled) }
    var automaticFolderUri by remember { mutableStateOf(scheduleSettings.folderUri) }
    var automaticFolderLabel by remember { mutableStateOf(scheduleSettings.folderLabel) }
    var automaticInterval by remember { mutableStateOf(scheduleSettings.intervalHours) }
    var retainedCopies by remember { mutableStateOf(scheduleSettings.retainedCopies) }
    var automaticCategories by remember { mutableStateOf(scheduleSettings.categories) }
    var automaticPassword by remember { mutableStateOf("") }
    var automaticConfirmation by remember { mutableStateOf("") }
    var editorText by remember { mutableStateOf<String?>(null) }
    var editorPassword by remember { mutableStateOf("") }
    var editorConfirmation by remember { mutableStateOf("") }

    fun result(message: String, isError: Boolean = false) {
        feedback = message
        feedbackIsError = isError
    }

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val password = exportPassword.toCharArray()
        scope.launch {
            busy = true
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val encrypted = manager.create(backupCategories, password)
                    try {
                        context.contentResolver.openOutputStream(uri, "w")?.use { it.write(encrypted) }
                            ?: error("The selected file could not be opened.")
                    } finally {
                        encrypted.fill(0)
                    }
                }
            }
            password.fill('\u0000')
            busy = false
            saved.fold(
                onSuccess = {
                    exportPassword = ""
                    exportConfirmation = ""
                    result("Encrypted backup saved.")
                },
                onFailure = { result("Backup could not be saved: ${it.safeMessage()}", true) }
            )
        }
    }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val password = importPassword.toCharArray()
        scope.launch {
            busy = true
            val opened = withContext(Dispatchers.IO) {
                runCatching {
                    val encrypted = readSmallBackup(context, uri)
                    try {
                        manager.open(encrypted, password)
                    } finally {
                        encrypted.fill(0)
                    }
                }
            }
            password.fill('\u0000')
            busy = false
            opened.fold(
                onSuccess = { document ->
                    pendingRestore = document
                    editorText = null
                    restoreCategories = document.categories
                    resumeMonitoring = false
                    importPassword = ""
                    result("Backup unlocked and checked. Choose what to restore below.")
                },
                onFailure = {
                    pendingRestore = null
                    result(it.safeMessage(), true)
                }
            )
        }
    }
    val chooseBackupFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            automaticFolderUri = uri.toString()
            automaticFolderLabel = uri.lastPathSegment
                ?.substringAfterLast(':')
                ?.ifBlank { "Selected folder" }
                ?: "Selected folder"
            result("Backup folder connected. Save the automatic backup plan to use it.")
        }.onFailure { result("That folder could not be connected: ${it.safeMessage()}", true) }
    }
    val createEditedDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val text = editorText
        if (uri == null || text == null) return@rememberLauncherForActivityResult
        val password = editorPassword.toCharArray()
        scope.launch {
            busy = true
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val encrypted = manager.createEdited(text, password)
                    try {
                        context.contentResolver.openOutputStream(uri, "w")?.use { it.write(encrypted) }
                            ?: error("The selected file could not be opened.")
                    } finally {
                        encrypted.fill(0)
                    }
                }
            }
            password.fill('\u0000')
            busy = false
            saved.fold(
                onSuccess = {
                    editorPassword = ""
                    editorConfirmation = ""
                    result("Edited backup validated and saved as a new encrypted file.")
                },
                onFailure = { result("Edited backup was not saved: ${it.safeMessage()}", true) }
            )
        }
    }

    if (panel == BackupPanel.CREATE) SettingsCard {
        Text("Create encrypted backup", fontWeight = FontWeight.Medium)
        Text(
            "Choose exactly what to include. Every selected item is encrypted before Android saves the file.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
        BackupCategory.entries.forEach { category ->
            SettingSwitch(
                title = category.title,
                explanation = categoryDescription(category),
                checked = category in backupCategories,
                onCheckedChange = { checked ->
                    backupCategories = if (checked) backupCategories + category
                    else backupCategories - category
                }
            )
        }
        PasswordField("Backup password", exportPassword) { exportPassword = it }
        PasswordField("Confirm password", exportConfirmation) { exportConfirmation = it }
        Text(
            "Use at least ${PasswordBackupCipher.MIN_PASSWORD_LENGTH} characters. FP Grid Monitor cannot recover a forgotten backup password.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
        Button(
            onClick = { createDocument.launch(backupFileName()) },
            enabled = !busy && backupCategories.isNotEmpty() &&
                exportPassword.length >= PasswordBackupCipher.MIN_PASSWORD_LENGTH &&
                exportPassword == exportConfirmation,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy) "Working…" else "Choose where to save backup") }
        if (exportConfirmation.isNotEmpty() && exportPassword != exportConfirmation) {
            Text("The two passwords do not match.", color = MaterialTheme.colorScheme.error)
        }
    }

    if (panel == BackupPanel.AUTOMATIC) SettingsCard {
        Text("Automatic encrypted backups", fontWeight = FontWeight.Medium)
        Text(
            "Android writes to one folder you choose. If Google Drive, OneDrive or Dropbox appears in the folder picker, selecting its folder lets that app sync the files without sharing your cloud password with FP Grid Monitor.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
        SettingSwitch(
            title = "Automatic backups",
            explanation = "Keep creating password-protected recovery copies in the selected folder.",
            checked = automaticEnabled,
            onCheckedChange = { automaticEnabled = it }
        )
        SettingText("Folder", automaticFolderLabel ?: "Not connected")
        OutlinedButton(
            onClick = { chooseBackupFolder.launch(automaticFolderUri?.let(Uri::parse)) },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (automaticFolderUri == null) "Choose backup folder" else "Change backup folder") }

        Text("Backup frequency", fontWeight = FontWeight.Medium)
        BackupScheduleStore.ALLOWED_INTERVAL_HOURS.forEach { hours ->
            RadioChoice(
                label = when (hours) {
                    24L -> "Every day (recommended)"
                    168L -> "Every 7 days"
                    else -> "Every $hours hours"
                },
                selected = automaticInterval == hours,
                onClick = { automaticInterval = hours }
            )
        }
        Text("Copies to keep", fontWeight = FontWeight.Medium)
        BackupScheduleStore.ALLOWED_RETAINED_COPIES.forEach { copies ->
            RadioChoice(
                label = "$copies newest copies${if (copies == 7) " (recommended)" else ""}",
                selected = retainedCopies == copies,
                onClick = { retainedCopies = copies }
            )
        }
        Text("Data in each automatic backup", fontWeight = FontWeight.Medium)
        BackupCategory.entries.forEach { category ->
            SettingSwitch(
                title = category.title,
                explanation = categoryDescription(category),
                checked = category in automaticCategories,
                onCheckedChange = { checked ->
                    automaticCategories = if (checked) automaticCategories + category
                    else automaticCategories - category
                }
            )
        }
        PasswordField(
            if (scheduleSettings.hasPassword) "New password (leave blank to keep current)"
            else "Automatic backup password",
            automaticPassword
        ) { automaticPassword = it }
        PasswordField("Confirm new password", automaticConfirmation) {
            automaticConfirmation = it
        }
        Text(
            "This password is kept in Android Keystore on this phone. The encrypted backup also contains the automatic-backup plan so a replacement phone can restore it; Android will still ask you to reconnect the destination folder.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
        val automaticPasswordValid = if (automaticPassword.isEmpty()) {
            scheduleSettings.hasPassword
        } else {
            automaticPassword.length >= PasswordBackupCipher.MIN_PASSWORD_LENGTH &&
                automaticPassword == automaticConfirmation
        }
        val enteredPasswordValid = automaticPassword.isEmpty() ||
            automaticPassword.length >= PasswordBackupCipher.MIN_PASSWORD_LENGTH &&
            automaticPassword == automaticConfirmation
        Button(
            onClick = {
                runCatching {
                    scheduleStore.save(
                        enabled = automaticEnabled,
                        folderUri = automaticFolderUri,
                        folderLabel = automaticFolderLabel,
                        intervalHours = automaticInterval,
                        retainedCopies = retainedCopies,
                        categories = automaticCategories,
                        password = automaticPassword.ifEmpty { null }
                    )
                    scheduleSettings = scheduleStore.settings()
                    scheduler.apply(scheduleSettings)
                    automaticPassword = ""
                    automaticConfirmation = ""
                    result(if (automaticEnabled) "Automatic backups scheduled."
                        else "Automatic backups turned off.")
                }.onFailure { result("The backup plan was not saved: ${it.safeMessage()}", true) }
            },
            enabled = automaticCategories.isNotEmpty() &&
                (!automaticEnabled || automaticFolderUri != null && automaticPasswordValid) &&
                enteredPasswordValid,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save automatic backup plan") }
        if (automaticPassword.isNotEmpty() && automaticPassword != automaticConfirmation) {
            Text("The two automatic-backup passwords do not match.", color = MaterialTheme.colorScheme.error)
        }
        OutlinedButton(
            onClick = {
                scheduler.runNow()
                result("Backup requested. Android will run it in the background.")
                scope.launch {
                    repeat(6) {
                        delay(1_000)
                        scheduleStatus = scheduleStore.status()
                    }
                }
            },
            enabled = scheduleSettings.enabled,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Create an automatic backup now") }
        scheduleStatus.lastSuccessAtEpochMs?.let {
            SettingText("Last successful copy", formatBackupDate(it))
        }
        scheduleStatus.lastError?.let {
            Text("Last attempt failed: $it", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
    }

    if (panel == BackupPanel.RESTORE) SettingsCard {
        Text("Restore encrypted backup", fontWeight = FontWeight.Medium)
        Text(
            "Unlock and validate a backup first. Nothing changes until you review it and tap Restore selected data.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
        PasswordField("Backup password", importPassword) { importPassword = it }
        OutlinedButton(
            onClick = {
                pendingRestore = null
                openDocument.launch(arrayOf("application/octet-stream", "*/*"))
            },
            enabled = !busy && !settings.monitoringEnabled &&
                importPassword.length >= PasswordBackupCipher.MIN_PASSWORD_LENGTH,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Choose backup to unlock") }
        if (settings.monitoringEnabled) {
            Text(
                "Turn off the dashboard master switch before restoring so live state cannot change halfway through.",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp
            )
        }
    }

    if (panel == BackupPanel.RESTORE) pendingRestore?.let { document ->
        SettingsCard {
            Text("Ready to restore", fontWeight = FontWeight.Medium)
            SettingText("Created", formatBackupDate(document.createdAtEpochMs))
            SettingText(
                "Created by app version",
                "${document.appVersionName} (${document.appVersionCode})"
            )
            document.categories.forEach { category ->
                SettingSwitch(
                    title = category.title,
                    explanation = categoryDescription(category),
                    checked = category in restoreCategories,
                    onCheckedChange = { checked ->
                        restoreCategories = if (checked) restoreCategories + category
                        else restoreCategories - category
                    }
                )
            }
            if (BackupCategory.ACTIVE_STATE in restoreCategories &&
                document.activeState?.monitoringWasEnabled == true
            ) {
                SettingSwitch(
                    title = "Resume monitoring after restore",
                    explanation = "Starts the restored live monitor and continues its saved pending alerts.",
                    checked = resumeMonitoring,
                    onCheckedChange = { resumeMonitoring = it }
                )
                Text(
                    "Only use resume when the old monitoring device is permanently offline. Two devices restoring the same pending alerts could send duplicates.",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp
                )
            }
            Button(
                onClick = {
                    val restoreError = onRestore(document, restoreCategories, resumeMonitoring)
                    if (restoreError == null) {
                        pendingRestore = null
                        editorText = null
                        restoreCategories = emptySet()
                        scheduleSettings = scheduleStore.settings()
                        automaticEnabled = scheduleSettings.enabled
                        automaticFolderUri = scheduleSettings.folderUri
                        automaticFolderLabel = scheduleSettings.folderLabel
                        automaticInterval = scheduleSettings.intervalHours
                        retainedCopies = scheduleSettings.retainedCopies
                        automaticCategories = scheduleSettings.categories
                        result(if (resumeMonitoring) "Backup restored and monitoring resumed."
                            else "Selected backup data restored.")
                    } else {
                        result(restoreError, true)
                    }
                },
                enabled = !settings.monitoringEnabled && restoreCategories.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Restore selected data") }
            OutlinedButton(
                onClick = {
                    editorText = if (editorText == null) manager.editableText(document) else null
                    editorPassword = ""
                    editorConfirmation = ""
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (editorText == null) "Open advanced backup editor" else "Close backup editor") }
            editorText?.let { editable ->
                Text(
                    "Advanced testing tool: this decrypted text may show passwords and API keys. Edit property values carefully. The original archive is never changed.",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                OutlinedTextField(
                    value = editable,
                    onValueChange = {
                        if (it.length <= PasswordBackupCipher.MAX_BACKUP_BYTES) editorText = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Decrypted backup document") },
                    minLines = 12,
                    maxLines = 24,
                    textStyle = MaterialTheme.typography.bodySmall
                )
                PasswordField("Password for edited copy", editorPassword) {
                    editorPassword = it
                }
                PasswordField("Confirm edited-copy password", editorConfirmation) {
                    editorConfirmation = it
                }
                Button(
                    onClick = { createEditedDocument.launch(editedBackupFileName()) },
                    enabled = !busy &&
                        editorPassword.length >= PasswordBackupCipher.MIN_PASSWORD_LENGTH &&
                        editorPassword == editorConfirmation,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Validate and save edited copy") }
                Text(
                    "The editor cannot bypass safety checks. Invalid or incomplete data will be rejected before a new file is written.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
            TextButton(
                onClick = {
                    pendingRestore = null
                    editorText = null
                    editorPassword = ""
                    editorConfirmation = ""
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cancel")
            }
        }
    }

    feedback?.let {
        Text(
            it,
            color = if (feedbackIsError) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primary
        )
    }
    Text(
        "Android system permissions and manufacturer battery settings cannot be copied. A restored local EcoFlow connection must pass a new read-only test before it can be selected.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp
    )
}

@Composable
private fun RadioChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PasswordField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= 200) onChange(it) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation()
    )
}

private fun categoryDescription(category: BackupCategory) = when (category) {
    BackupCategory.SETTINGS -> "Names, timing, appearance, alarms, and scheduled-update choices."
    BackupCategory.ALERTS -> "Telegram, Gmail, Resend and SMS setup, including keys and recipients."
    BackupCategory.POWER_SOURCES -> "Local and cloud EcoFlow setup, including cloud API keys."
    BackupCategory.HISTORY -> "Recorded outages, interruptions, app starts and monitoring activity."
    BackupCategory.ACTIVE_STATE -> "Current outage state, pending messages, retry state and alarm state."
}

private fun readSmallBackup(context: Context, uri: Uri): ByteArray {
    val input = context.contentResolver.openInputStream(uri)
        ?: error("The selected file could not be opened.")
    return input.use { stream ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        val limit = PasswordBackupCipher.MAX_BACKUP_BYTES + 128
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            if (output.size() + count > limit) error("The selected backup is too large.")
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
}

private fun backupFileName(): String = "fp-grid-monitor-${
    SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
}.fpgrid"

private fun editedBackupFileName(): String = "fp-grid-monitor-edited-${
    SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
}.fpgrid"

private fun formatBackupDate(epochMs: Long): String =
    java.text.DateFormat.getDateTimeInstance().format(Date(epochMs))

private fun Throwable.safeMessage(): String = message?.take(180) ?: javaClass.simpleName
