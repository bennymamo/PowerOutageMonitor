package com.flossypickle.poweroutagemonitor.integrations.power.ecoflow

/** Per-inspection observations, kept separate from the outage state machine. */
internal class PowerOceanGridInspection {
    data class Change(val code: Long, val receivedUtcMillis: Long)
    data class Snapshot(val lastCode: Long?, val lastReceivedUtcMillis: Long?, val changes: List<Change>)

    private var lastCode: Long? = null
    private var lastReceivedUtcMillis: Long? = null
    private val changes = ArrayDeque<Change>()

    fun observe(report: PowerOceanPushDecoder.Report, receivedUtcMillis: Long, retained: Boolean) {
        // Neither a retained cloud value nor an omitted protobuf field is a new grid observation.
        if (report.command != 8 || retained || receivedUtcMillis <= 0) return
        val code = report.values["sysGridSta"] as? Long ?: return
        if (code !in 0..0xFFFFFFFFL || receivedUtcMillis < (lastReceivedUtcMillis ?: 0)) return
        if (code != lastCode) {
            changes.addLast(Change(code, receivedUtcMillis))
            if (changes.size > 8) changes.removeFirst()
        }
        lastCode = code
        lastReceivedUtcMillis = receivedUtcMillis
    }

    fun snapshot() = Snapshot(lastCode, lastReceivedUtcMillis, changes.toList())
}
