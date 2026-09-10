package com.flossypickle.poweroutagemonitor.integrations.alerts

/** Pure delivery-state rules shared by every future alert provider. */
internal object AlertQueueEngine {
    enum class Status { PENDING, IN_FLIGHT, RETRYING, SENT, FAILED }

    data class Item(
        val id: String,
        val providerId: String,
        val destinationId: String,
        val message: AlertMessage,
        val status: Status = Status.PENDING,
        val createdAtEpochMs: Long,
        val attemptCount: Int = 0,
        val lastAttemptAtEpochMs: Long? = null,
        val nextAttemptAtEpochMs: Long = createdAtEpochMs,
        val leaseUntilEpochMs: Long? = null,
        val lastError: String? = null,
        val providerMessageId: String? = null
    )

    fun enqueue(items: List<Item>, item: Item): List<Item> {
        val duplicate = items.any {
            it.message.eventId == item.message.eventId &&
                it.message.kind == item.message.kind &&
                it.providerId == item.providerId &&
                it.destinationId == item.destinationId
        }
        return if (duplicate) items else items + item
    }

    fun due(items: List<Item>, nowEpochMs: Long): List<Item> = items.filter { item ->
        when (item.status) {
            Status.PENDING, Status.RETRYING -> item.nextAttemptAtEpochMs <= nowEpochMs
            Status.IN_FLIGHT -> (item.leaseUntilEpochMs ?: Long.MAX_VALUE) <= nowEpochMs
            Status.SENT, Status.FAILED -> false
        }
    }

    fun markInFlight(item: Item, nowEpochMs: Long): Item = item.copy(
        status = Status.IN_FLIGHT,
        attemptCount = item.attemptCount + 1,
        lastAttemptAtEpochMs = nowEpochMs,
        leaseUntilEpochMs = nowEpochMs + DELIVERY_LEASE_MS
    )

    fun complete(item: Item, result: DeliveryResult, nowEpochMs: Long): Item = when (result) {
        is DeliveryResult.Sent -> item.copy(
            status = Status.SENT,
            nextAttemptAtEpochMs = Long.MAX_VALUE,
            leaseUntilEpochMs = null,
            lastError = null,
            providerMessageId = result.providerMessageId
        )
        is DeliveryResult.RetryableFailure -> item.copy(
            status = Status.RETRYING,
            nextAttemptAtEpochMs = nowEpochMs + retryDelayMs(item.attemptCount),
            leaseUntilEpochMs = null,
            lastError = result.reason.take(MAX_ERROR_LENGTH)
        )
        is DeliveryResult.PermanentFailure -> item.copy(
            status = Status.FAILED,
            nextAttemptAtEpochMs = Long.MAX_VALUE,
            leaseUntilEpochMs = null,
            lastError = result.reason.take(MAX_ERROR_LENGTH)
        )
    }

    fun retryDelayMs(attemptCount: Int): Long = RETRY_DELAYS_MS[
        (attemptCount - 1).coerceIn(0, RETRY_DELAYS_MS.lastIndex)
    ]

    private const val DELIVERY_LEASE_MS = 5 * 60_000L
    private const val MAX_ERROR_LENGTH = 500
    private val RETRY_DELAYS_MS = longArrayOf(
        60_000L,
        5 * 60_000L,
        15 * 60_000L,
        30 * 60_000L,
        60 * 60_000L,
        2 * 60 * 60_000L,
        4 * 60 * 60_000L,
        6 * 60 * 60_000L
    )
}
