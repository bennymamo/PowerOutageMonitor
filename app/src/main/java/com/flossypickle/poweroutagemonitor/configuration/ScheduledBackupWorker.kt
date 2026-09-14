package com.flossypickle.poweroutagemonitor.configuration

import android.content.Context
import android.provider.DocumentsContract
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Writes one encrypted archive into the folder explicitly granted by the user. */
internal class ScheduledBackupWorker(
    appContext: Context,
    parameters: WorkerParameters
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = BackupScheduleStore(applicationContext)
        val settings = store.settings()
        val folder = settings.folderUri
        val passwordText = store.password()
        if (!settings.enabled || folder.isNullOrBlank() || passwordText.isNullOrEmpty()) {
            return@withContext Result.success()
        }
        val password = passwordText.toCharArray()
        runCatching {
            val treeUri = android.net.Uri.parse(folder)
            val parent = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            )
            val name = automaticBackupFileName()
            val file = DocumentsContract.createDocument(
                applicationContext.contentResolver,
                parent,
                MIME_TYPE,
                name
            ) ?: error("The backup folder did not create a file.")
            val archive = BackupManager(applicationContext).create(settings.categories, password)
            try {
                applicationContext.contentResolver.openOutputStream(file, "w")?.use {
                    it.write(archive)
                } ?: error("The new backup file could not be opened.")
            } catch (error: Exception) {
                runCatching { DocumentsContract.deleteDocument(applicationContext.contentResolver, file) }
                throw error
            } finally {
                archive.fill(0)
            }
            removeOldCopies(treeUri, settings.retainedCopies)
            store.recordSuccess()
        }.fold(
            onSuccess = { Result.success() },
            onFailure = {
                store.recordFailure(it.message ?: it.javaClass.simpleName)
                Result.failure()
            }
        ).also { password.fill('\u0000') }
    }

    private fun removeOldCopies(treeUri: android.net.Uri, keep: Int) {
        val resolver = applicationContext.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri)
        )
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val files = mutableListOf<BackupFile>()
        resolver.query(children, columns, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(columns[0])
            val nameIndex = cursor.getColumnIndexOrThrow(columns[1])
            val modifiedIndex = cursor.getColumnIndexOrThrow(columns[2])
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex) ?: continue
                if (name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX)) {
                    files += BackupFile(cursor.getString(idIndex), name, cursor.getLong(modifiedIndex))
                }
            }
        }
        files.sortedWith(compareByDescending<BackupFile> { it.modified }.thenByDescending { it.name })
            .drop(keep)
            .forEach { old ->
                runCatching {
                    DocumentsContract.deleteDocument(
                        resolver,
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, old.id)
                    )
                }
            }
    }

    private data class BackupFile(val id: String, val name: String, val modified: Long)

    companion object {
        const val MIME_TYPE = "application/octet-stream"
        const val FILE_PREFIX = "fp-grid-monitor-auto-"
        const val FILE_SUFFIX = ".fpgrid"

        internal fun automaticBackupFileName(now: java.util.Date = java.util.Date()): String =
            "$FILE_PREFIX${java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(now)}$FILE_SUFFIX"
    }
}
