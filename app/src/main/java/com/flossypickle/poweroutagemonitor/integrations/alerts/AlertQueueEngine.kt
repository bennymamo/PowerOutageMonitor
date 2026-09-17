package com.flossypickle.poweroutagemonitor.integrations.alerts

/** Pure delivery-state rules shared by every future alert provider. */
internal object AlertQueueEngine {
    enum class Status { PENDING, IN_FLIGHT, RETRYING, SENT, FAILED, SKIPPED }

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
        val timeDue = when (item.status) {
            Status.PENDING, Status.RETRYING -> item.nextAttemptAtEpochMs <= nowEpochMs
            Status.IN_FLIGHT -> (item.leaseUntilEpochMs ?: Long.MAX_VALUE) <= nowEpochMs
            Status.SENT, Status.FAILED, Status.SKIPPED -> false
        }
        timeDue && !hasUnfinishedPredecessor(items, item)
    }

    /** Keeps messages for one power event and destination in user-meaningful order. */
    fun hasUnfinishedPredecessor(items: List<Item>, item: Item): Boolean = items.any { other ->
        other.id != item.id &&
            other.providerId == item.providerId &&
            other.destinationId == item.destinationId &&
            other.message.eventId == item.message.eventId &&
            other.status !in terminalStatuses &&
            deliveryOrder(other.message.kind) < deliveryOrder(item.message.kind)
    }

    fun nextUnfinishedForEvent(items: List<Item>, item: Item): Item? = items
        .asSequence()
        .filter { other ->
            other.id != item.id &&
                other.providerId == item.providerId &&
                other.destinationId == item.destinationId &&
                other.message.eventId == item.message.eventId &&
                other.status !in terminalStatuses
        }
        .minWithOrNull(compareBy<Item>({ deliveryOrder(it.message.kind) }, { it.createdAtEpochMs }))

    fun sequenceHeads(items: List<Item>): List<Item> = items.filter { item ->
        item.status !in terminalStatuses && !hasUnfinishedPredecessor(items, item)
    }

    fun nextRunnableAt(item: Item): Long? = when (item.status) {
        Status.PENDING, Status.RETRYING -> item.nextAttemptAtEpochMs
        Status.IN_FLIGHT -> item.leaseUntilEpochMs
        Status.SENT, Status.FAILED, Status.SKIPPED -> null
    }

    fun markInFlight(item: Item, nowEpochMs: Long): Item = item.copy(
        status = Status.IN_FLIGHT,
        attemptCount = item.attemptCount + 1,
        lastAttemptAtEpochMs = nowEpochMs,
        leaseUntilEpochMs = nowEpochMs + DELIVERY_LEASE_MS
    )

    fun complete(item: Item, result: DeliveryResult, nowEpochMs: Long): Item = when (result) {
        is DeliveryResult.Skipped -> item.copy(status = Status.SKIPPED, nextAttemptAtEpochMs = Long.MAX_VALUE,
            leaseUntilEpochMs = null, lastError = result.reason.take(MAX_ERROR_LENGTH))
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

    fun retryFailed(item: Item, nowEpochMs: Long): Item = if (item.status == Status.FAILED) {
        item.copy(
            status = Status.PENDING,
            attemptCount = 0,
            lastAttemptAtEpochMs = null,
            nextAttemptAtEpochMs = nowEpochMs,
            leaseUntilEpochMs = null,
            lastError = null,
            providerMessageId = null
        )
    } else item

    fun retryDelayMs(attemptCount: Int): Long = RETRY_DELAYS_MS[
        (attemptCount - 1).coerceIn(0, RETRY_DELAYS_MS.lastIndex)
    ]

    private const val DELIVERY_LEASE_MS = 5 * 60_000L
    private const val MAX_ERROR_LENGTH = 500
    private val terminalStatuses = setOf(Status.SENT, Status.FAILED, Status.SKIPPED)
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

    private fun deliveryOrder(kind: AlertKind): Int = when (kind) {
        AlertKind.OUTAGE -> 0
        AlertKind.OUTAGE_UPDATE, AlertKind.BATTERY_LOW -> 1
        AlertKind.RESTORED -> 2
        AlertKind.SOURCE_UNAVAILABLE, AlertKind.SOURCE_DATA_WARNING -> 0
        AlertKind.SOURCE_RESTORED -> 1
        AlertKind.HEARTBEAT, AlertKind.TEST -> 0
    }
}
