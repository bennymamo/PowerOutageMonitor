package com.flossypickle.poweroutagemonitor.integrations.alerts

import android.content.Context
import android.os.Build
import android.os.UserManager
import com.flossypickle.poweroutagemonitor.storage.AlertQueueStore
import com.flossypickle.poweroutagemonitor.storage.EnabledAlertProvidersStore
import com.flossypickle.poweroutagemonitor.storage.PendingAlertEventStore
import java.util.UUID

/** Bridges Direct Boot events into the credential-protected per-destination delivery queue. */
internal class AlertDeliveryCoordinator(private val context: Context) {
    private val pending = PendingAlertEventStore(context)
    private val enabledProviders = EnabledAlertProvidersStore(context)

    fun persistForEnabledProviders(message: AlertMessage) {
        if (enabledProviders.hasAny()) pending.enqueue(message)
    }

    fun materializePending() {
        if (!isUserUnlocked()) return
        val registry = AlertProviderRegistry(context)
        val destinations = registry.enabledDestinations()
        if (destinations.isEmpty()) {
            pending.clear()
            return
        }
        val queue = AlertQueueStore(context)
        pending.read().forEach { message ->
            destinations.forEach { destination ->
                val item = AlertQueueEngine.Item(
                    id = stableItemId(message, destination),
                    providerId = destination.providerId,
                    destinationId = destination.destinationId,
                    message = message,
                    createdAtEpochMs = System.currentTimeMillis()
                )
                queue.enqueue(item)
                AlertDeliveryScheduler(context).scheduleNow(item.id)
            }
            pending.remove(message)
        }
    }

    private fun isUserUnlocked(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.N ||
        context.getSystemService(UserManager::class.java).isUserUnlocked

    private fun stableItemId(message: AlertMessage, destination: AlertProviderRegistry.Destination): String {
        val identity = "${message.eventId}|${message.kind}|${destination.providerId}|${destination.destinationId}"
        return UUID.nameUUIDFromBytes(identity.toByteArray(Charsets.UTF_8)).toString()
    }
}
