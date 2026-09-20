package com.flossypickle.poweroutagemonitor.integrations.alerts

import com.flossypickle.poweroutagemonitor.diagnostics.MonitoringEvidenceStore
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
        val monitor = com.flossypickle.poweroutagemonitor.storage.MonitorStore(applicationContext)
        val deliveryGeneration = inputData.getLong(KEY_GENERATION, 0L)
        val queue = AlertQueueStore(applicationContext)
        val claimed = synchronized(DeliveryMaintenanceGate.lock) {
            if (isStopped || monitor.restoredDeliveriesPaused() ||
                monitor.deliveryGeneration() != deliveryGeneration) return Result.success()
            val item = queue.claim(itemId, System.currentTimeMillis())
            if (item == null) {
                if (!queue.isBlockedByEarlierMessage(itemId)) {
                    queue.find(itemId)?.let(AlertQueueEngine::nextRunnableAt)?.let {
                        AlertDeliveryScheduler(applicationContext).scheduleRetry(itemId, it)
                    }
                }
                return Result.success()
            }
            DeliveryMaintenanceGate.reserveSend()
            item
        }
        try {
        MonitoringEvidenceStore(applicationContext).record(MonitoringEvidenceStore.Event.ALERT_STARTED)
        val result = runCatching {
            if (monitor.restoredDeliveriesPaused() ||
                monitor.deliveryGeneration() != deliveryGeneration
            ) return Result.success()
            if (claimed.providerId == "telegram" && claimed.message.kind != AlertKind.TEST &&
                com.flossypickle.poweroutagemonitor.integrations.alerts.telegram.TelegramRemoteStore(applicationContext).isQuiet()) {
                DeliveryResult.Skipped("Automatic Telegram alerts are quiet")
            } else AlertProviderRegistry(applicationContext)
                .resolve(claimed.providerId, claimed.destinationId)
                ?.send(claimed.message)
                ?: DeliveryResult.PermanentFailure("Alert channel is disabled or incomplete")
        }.getOrElse {
            DeliveryResult.RetryableFailure("Alert provider stopped unexpectedly")
        }
        synchronized(DeliveryMaintenanceGate.lock) {
        if (monitor.restoredDeliveriesPaused() ||
            monitor.deliveryGeneration() != deliveryGeneration
        ) return Result.success()
        val completed = queue.completeClaim(claimed,
            AlertQueueEngine.complete(claimed, result, System.currentTimeMillis()))
            ?: return Result.success()
        MonitoringEvidenceStore(applicationContext).record(MonitoringEvidenceStore.Event.ALERT_FINISHED, delivery = completed.status)
        applicationContext.sendBroadcast(
            Intent(ACTION_ALERT_DELIVERY_CHANGED).setPackage(applicationContext.packageName)
        )
        if (completed.status == AlertQueueEngine.Status.RETRYING) {
            AlertDeliveryScheduler(applicationContext)
                .scheduleRetry(completed.id, completed.nextAttemptAtEpochMs)
        } else {
            queue.nextUnfinishedForEvent(completed)?.let { next ->
                AlertQueueEngine.nextRunnableAt(next)?.let { runAt ->
                    AlertDeliveryScheduler(applicationContext).scheduleRetry(next.id, runAt)
                }
            }
        }
        }
        return Result.success()
        } finally {
            DeliveryMaintenanceGate.finishSend()
        }
    }

    companion object {
        const val KEY_GENERATION = "delivery_generation"
        const val KEY_ITEM_ID = "queue_item_id"
        const val ACTION_ALERT_DELIVERY_CHANGED =
            "com.flossypickle.poweroutagemonitor.ALERT_DELIVERY_CHANGED"
    }
}
