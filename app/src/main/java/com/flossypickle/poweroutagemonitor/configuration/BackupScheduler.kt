package com.flossypickle.poweroutagemonitor.configuration

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

internal class BackupScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun apply(settings: BackupScheduleStore.Settings) {
        if (!settings.enabled) {
            workManager.cancelUniqueWork(PERIODIC_WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<ScheduledBackupWorker>(
            settings.intervalHours,
            TimeUnit.HOURS
        ).build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun runNow() {
        workManager.enqueueUniqueWork(
            MANUAL_WORK,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ScheduledBackupWorker>().build()
        )
    }

    companion object {
        private const val PERIODIC_WORK = "encrypted-data-backup"
        private const val MANUAL_WORK = "encrypted-data-backup-now"
    }
}
