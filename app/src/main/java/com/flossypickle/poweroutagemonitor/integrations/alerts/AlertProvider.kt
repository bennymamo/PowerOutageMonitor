package com.flossypickle.poweroutagemonitor.integrations.alerts

internal data class AlertMessage(
    val eventId: String,
    val kind: AlertKind,
    val title: String,
    val body: String
)

internal enum class AlertKind {
    OUTAGE,
    OUTAGE_UPDATE,
    RESTORED,
    BATTERY_LOW,
    SOURCE_UNAVAILABLE,
    SOURCE_RESTORED,
    HEARTBEAT,
    TEST
}

internal sealed interface DeliveryResult {
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
