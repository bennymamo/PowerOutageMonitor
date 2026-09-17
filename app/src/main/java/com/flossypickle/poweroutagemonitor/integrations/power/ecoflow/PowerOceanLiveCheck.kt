package com.flossypickle.poweroutagemonitor.integrations.power.ecoflow

/** Per-request live receipt and comparisons across checks; never a measurement timestamp guarantee. */
internal class PowerOceanLiveCheck {
    data class Status(val requestedAt: Long?, val lastDevicePushAt: Long?, val unchangedChecks: Int, val comparedPower: Boolean = false,
        val powerValues: Map<String, Double> = emptyMap(), val deviceUpdates: Int = 0,
        val powerUpdates: Int = 0, val valuesChanged: Boolean = false) {
        fun hasCurrentReport(now: Long) = requestedAt != null && lastDevicePushAt != null &&
            lastDevicePushAt > requestedAt && now - lastDevicePushAt in 0..90_000
        val possiblyStalled get() = unchangedChecks >= 2
    }
    private var requestedAt: Long? = null
    private var lastPushAt: Long? = null
    private var currentPower: Map<String, Double> = emptyMap()
    private var previousPower: Map<String, Double>? = null
    private var comparedThisCheck = false
    private var unchangedChecks = 0
    private var deviceUpdates = 0
    private var powerUpdates = 0
    private var lastPowerAt = 0L
    private var firstCyclePower: Map<String, Double>? = null
    private var valuesChanged = false
    private val powerKeys = setOf("sysLoadPwr", "sysGridPwr", "mpptPwr", "bpPwr")

    fun begin(requestedAt: Long) {
        this.requestedAt = requestedAt; lastPushAt = null; comparedThisCheck = false
        currentPower = emptyMap(); deviceUpdates = 0; powerUpdates = 0; lastPowerAt = 0; firstCyclePower = null; valuesChanged = false
    }
    fun observe(report: PowerOceanPushDecoder.Report, receivedAt: Long, retained: Boolean, fromDevicePush: Boolean) {
        val start = requestedAt ?: return
        if (retained || !fromDevicePush || report.command !in setOf(1, 8, 33) ||
            report.values.values.none { it is Number } || receivedAt <= start || receivedAt < (lastPushAt ?: start)) return
        if (receivedAt > (lastPushAt ?: start)) { lastPushAt = receivedAt; deviceUpdates++ }
        if (report.command != 33) return
        val powers = report.values.mapNotNull { (key, value) ->
            (value as? Number)?.toDouble()?.takeIf { key in powerKeys && it.isFinite() }?.let { key to it }
        }.toMap()
        if (powers.isEmpty() || receivedAt <= lastPowerAt) return
        currentPower = powers; lastPowerAt = receivedAt; powerUpdates++
        val first = firstCyclePower
        if (first == null) firstCyclePower = powers
        else if (first.keys.intersect(powers.keys).any { first[it] != powers[it] }) valuesChanged = true
        val previous = previousPower
        val comparable = previous != null && previous.keys == powers.keys
        val changed = comparable && previous != powers
        if (changed) unchangedChecks = 0
        else if (!comparedThisCheck) unchangedChecks = if (comparable) unchangedChecks + 1 else 0
        previousPower = powers; comparedThisCheck = true
    }
    fun status() = Status(requestedAt, lastPushAt, unchangedChecks, currentPower.isNotEmpty(), currentPower, deviceUpdates, powerUpdates, valuesChanged)
}
