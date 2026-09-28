package com.flossypickle.poweroutagemonitor.integrations.alerts

internal data class AlertMessage(
    val eventId: String,
    val kind: AlertKind,
    val title: String,
    val body: String,
    /** Groups separately deduplicated messages that belong to one ordered incident. */
    val orderingKey: String = legacyAlertOrderingKey(eventId, kind)
)

internal enum class AlertKind {
    OUTAGE,
    OUTAGE_UPDATE,
    RESTORED,
    GRID_RECONNECTED,
    BATTERY_LOW,
    SOURCE_UNAVAILABLE,
    SOURCE_RESTORED,
    CHARGER_RESTORED,
    SOURCE_DATA_WARNING,
    HEARTBEAT,
    TEST
}

internal sealed interface DeliveryResult {
    data class Skipped(val reason: String) : DeliveryResult
    data class Sent(val providerMessageId: String? = null) : DeliveryResult
    data class RetryableFailure(val reason: String) : DeliveryResult
    data class PermanentFailure(val reason: String) : DeliveryResult
}

/** One implementation per independent destination such as Telegram or SMS. */
internal interface AlertProvider {
    val id: String
    val displayName: String
    fun send(message: AlertMessage): DeliveryResult
}

/** Version-one queues and archives predate the separate ordering key. */
internal fun legacyAlertOrderingKey(eventId: String, kind: AlertKind): String =
    if (kind == AlertKind.OUTAGE_UPDATE) {
        Regex("outage-update-([0-9]+)-[0-9]+").matchEntire(eventId)
            ?.groupValues?.get(1)?.let { "power-event-$it" } ?: eventId
    } else eventId
