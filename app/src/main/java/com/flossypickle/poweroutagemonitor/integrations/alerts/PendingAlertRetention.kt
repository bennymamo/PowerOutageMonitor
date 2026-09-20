package com.flossypickle.poweroutagemonitor.integrations.alerts

internal object PendingAlertRetention {
    private const val SOFT_LIMIT = 500
    private const val OVERFLOW_ID = "pending-alert-overflow"
    /** Coalesces replaceable status chatter while never evicting incident transitions. */
    fun retain(messages: List<AlertMessage>): List<AlertMessage> {
        val coalesced = messages.asReversed().distinctBy { message ->
            when (message.kind) {
                AlertKind.HEARTBEAT -> "heartbeat"
                AlertKind.OUTAGE_UPDATE -> "outage-update:${message.orderingKey}"
                else -> "message:${message.eventId}:${message.kind}"
            }
        }.asReversed()
        if (coalesced.size <= SOFT_LIMIT) return coalesced
        val essentialKinds = setOf(
            AlertKind.OUTAGE,
            AlertKind.RESTORED,
            AlertKind.BATTERY_LOW,
            AlertKind.SOURCE_UNAVAILABLE,
            AlertKind.SOURCE_RESTORED,
            AlertKind.CHARGER_RESTORED,
            AlertKind.SOURCE_DATA_WARNING
        )
        val essential = coalesced.filter { it.kind in essentialKinds }
        val room = (SOFT_LIMIT - essential.size).coerceAtLeast(0)
        val replaceable = coalesced.filterNot { it.kind in essentialKinds }.takeLast(room)
        val retained = (essential + replaceable).toSet()
        val dropped = coalesced.size - retained.size
        val result = coalesced.filter { it in retained }
        if (dropped == 0) return result
        return result.filterNot { it.eventId == OVERFLOW_ID } + AlertMessage(
            OVERFLOW_ID, AlertKind.SOURCE_DATA_WARNING, "Pending alert backlog",
            "Older replaceable status/test messages were omitted because the pending backlog exceeded $SOFT_LIMIT events. Outage, restoration and warning transitions were retained."
        )
    }

}
