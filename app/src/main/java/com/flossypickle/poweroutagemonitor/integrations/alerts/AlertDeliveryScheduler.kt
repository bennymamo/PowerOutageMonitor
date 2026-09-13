package com.flossypickle.poweroutagemonitor.integrations.alerts

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.flossypickle.poweroutagemonitor.storage.AlertQueueStore
import java.util.concurrent.TimeUnit

/** Schedules one persistent network-constrained work chain per queue item. */
internal class AlertDeliveryScheduler(private val context: Context) {
    fun scheduleNow(itemId: String) = enqueue(itemId, 0, ExistingWorkPolicy.REPLACE)

    fun scheduleRetry(itemId: String, runAtEpochMs: Long) = enqueue(
        itemId,
        (runAtEpochMs - System.currentTimeMillis()).coerceAtLeast(0),
        ExistingWorkPolicy.APPEND_OR_REPLACE
    )

    private fun enqueue(itemId: String, delayMs: Long, policy: ExistingWorkPolicy) {
        val constraints = Constraints.Builder().apply {
            val providerId = AlertQueueStore(context).find(itemId)?.providerId
            if (AlertProviderRegistry.requiresInternet(providerId)) {
                setRequiredNetworkType(NetworkType.CONNECTED)
            }
        }.build()
        val request = OneTimeWorkRequestBuilder<AlertDeliveryWorker>()
            .setInputData(Data.Builder().putString(AlertDeliveryWorker.KEY_ITEM_ID, itemId).build())
            .setConstraints(constraints)
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(itemId), policy, request)
    }

    private fun workName(itemId: String) = "alert-delivery-$itemId"

    companion object {
        const val TAG = "alert-delivery"
    }
}
