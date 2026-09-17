package com.flossypickle.poweroutagemonitor.integrations.alerts

/** Provider-neutral delivery outcome grouped by power event for History and Diagnostics. */
internal object AlertDeliverySummary {
    data class Event(
        val eventId: String,
        val sent: Int,
        val pending: Int,
        val retrying: Int,
        val failed: Int,
        val skipped: Int = 0
    ) {
        fun label(): String = buildList {
            if (sent > 0) add("$sent sent")
            if (pending > 0) add("$pending queued")
            if (retrying > 0) add("$retrying retrying")
            if (failed > 0) add("$failed failed")
            if (skipped > 0) add("$skipped skipped")
        }.joinToString(" · ").ifEmpty { "No alert attempts" }
    }

    fun byEvent(items: List<AlertQueueEngine.Item>): Map<String, Event> = items
        .groupBy { it.message.eventId }
        .mapValues { (eventId, deliveries) ->
            Event(
                eventId = eventId,
                sent = deliveries.count { it.status == AlertQueueEngine.Status.SENT },
                pending = deliveries.count {
                    it.status == AlertQueueEngine.Status.PENDING ||
                        it.status == AlertQueueEngine.Status.IN_FLIGHT
                },
                retrying = deliveries.count { it.status == AlertQueueEngine.Status.RETRYING },
                failed = deliveries.count { it.status == AlertQueueEngine.Status.FAILED },
                skipped = deliveries.count { it.status == AlertQueueEngine.Status.SKIPPED }
            )
        }
}
