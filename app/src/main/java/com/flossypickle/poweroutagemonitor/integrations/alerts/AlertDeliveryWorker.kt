package com.flossypickle.poweroutagemonitor.integrations.alerts

import android.content.Context
import android.content.Intent
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.flossypickle.poweroutagemonitor.storage.AlertQueueStore

/** Executes one leased queue item and records the provider result before scheduling a retry. */
internal class AlertDeliveryWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {
    override fun doWork(): Result {
        val itemId = inputData.getString(KEY_ITEM_ID) ?: return Result.failure()
        val queue = AlertQueueStore(applicationContext)
        val now = System.currentTimeMillis()
        val claimed = queue.claim(itemId, now)
        if (claimed == null) {
            queue.find(itemId)?.nextRunnableAt()?.let {
                AlertDeliveryScheduler(applicationContext).scheduleRetry(itemId, it)
            }
            return Result.success()
        }

        val result = runCatching {
            AlertProviderRegistry(applicationContext)
                .resolve(claimed.providerId, claimed.destinationId)
                ?.send(claimed.message)
                ?: DeliveryResult.PermanentFailure("Alert channel is disabled or incomplete")
        }.getOrElse {
            DeliveryResult.RetryableFailure("Alert provider stopped unexpectedly")
        }
        val completed = AlertQueueEngine.complete(claimed, result, System.currentTimeMillis())
        queue.replace(completed)
        applicationContext.sendBroadcast(
            Intent(ACTION_ALERT_DELIVERY_CHANGED).setPackage(applicationContext.packageName)
        )
        if (completed.status == AlertQueueEngine.Status.RETRYING) {
            AlertDeliveryScheduler(applicationContext)
                .scheduleRetry(completed.id, completed.nextAttemptAtEpochMs)
        }
        return Result.success()
    }

    private fun AlertQueueEngine.Item.nextRunnableAt(): Long? = when (status) {
        AlertQueueEngine.Status.PENDING, AlertQueueEngine.Status.RETRYING -> nextAttemptAtEpochMs
        AlertQueueEngine.Status.IN_FLIGHT -> leaseUntilEpochMs
        AlertQueueEngine.Status.SENT, AlertQueueEngine.Status.FAILED -> null
    }

    companion object {
        const val KEY_ITEM_ID = "queue_item_id"
        const val ACTION_ALERT_DELIVERY_CHANGED =
            "com.flossypickle.poweroutagemonitor.ALERT_DELIVERY_CHANGED"
    }
}
