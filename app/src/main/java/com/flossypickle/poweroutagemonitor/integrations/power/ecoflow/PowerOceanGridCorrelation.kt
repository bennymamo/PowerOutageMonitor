package com.flossypickle.poweroutagemonitor.integrations.power.ecoflow

/** Optional installation-specific comparison, never an automatic outage decision. */
internal class PowerOceanGridCorrelation(private val profile: Profile) {
    data class Profile(
        val connectedCode: Long = 0,
        val offGridCode: Long = 1,
        val meterKey: String = "meterHeartBeat[0].meterData[0]",
        val staleAfterMs: Long = 90_000,
        val minimumSampleSpacingMs: Long = 2_000
    ) {
        init {
            require(connectedCode != offGridCode)
            require(staleAfterMs > 0 && minimumSampleSpacingMs > 0)
        }
    }

    enum class State { UNKNOWN, INVERTER_CONNECTED, INVERTER_OFF_GRID, GRID_RETURN_LIKELY }
    data class Snapshot(val state: State, val zeroFlowSeen: Boolean, val returnEvidenceAtUtcMillis: Long?,
        val currentMeterValue: Double? = null)

    private var gridCode: Long? = null
    private var gridReceived: Long? = null
    private var meterReceived: Long? = null
    private var meterValue: Double? = null
    private var offGridStarted: Long? = null
    private var zeroFlowReceived: Long? = null
    private var candidateValue: Double? = null
    private var candidateReceived: Long? = null
    private var returnEvidence: Long? = null
    private var lastLivePush: Long? = null
    private val preZeroValues = ArrayDeque<Double>()

    fun observe(report: PowerOceanPushDecoder.Report, receivedUtcMillis: Long,
        retained: Boolean, fromDevicePush: Boolean) {
        if (retained || receivedUtcMillis <= 0) return
        val previousLivePush = lastLivePush
        if (fromDevicePush && report.command in setOf(1, 8, 33)) {
            if (receivedUtcMillis < (lastLivePush ?: 0)) return
            if (previousLivePush != null && receivedUtcMillis - previousLivePush > profile.staleAfterMs) {
                gridCode = null; gridReceived = null; offGridStarted = null
                resetMeterSequence()
            }
            lastLivePush = receivedUtcMillis
        }
        // A request reply alone cannot prove that the inverter is still reporting.
        val live = lastLivePush?.let { receivedUtcMillis - it in 0..profile.staleAfterMs } == true
        if (!fromDevicePush && (!live || report.command != 1)) return
        if (report.command == 8) {
            val code = report.values["sysGridSta"] as? Long ?: return
            if (receivedUtcMillis < (gridReceived ?: 0)) return
            val previousExpired = previousLivePush?.let { receivedUtcMillis - it > profile.staleAfterMs } == true
            if (code != gridCode || previousExpired) {
                resetMeterSequence()
                offGridStarted = if (code == profile.offGridCode) receivedUtcMillis else null
            }
            gridCode = code
            gridReceived = receivedUtcMillis
        } else if (report.command == 1) {
            val value = (report.values[profile.meterKey] as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: return
            if (receivedUtcMillis <= (meterReceived ?: 0)) return
            meterReceived = receivedUtcMillis
            meterValue = value
            if (zeroFlowReceived == null && value != 0.0 && value !in preZeroValues) {
                preZeroValues.addLast(value)
                if (preZeroValues.size > 32) preZeroValues.removeFirst()
            }
            val started = offGridStarted ?: return
            if (gridReceived == null) return
            if (gridCode != profile.offGridCode || receivedUtcMillis < started ||
                !live) return
            if (value == 0.0) {
                zeroFlowReceived = receivedUtcMillis
                if (returnEvidence == null) { candidateValue = null; candidateReceived = null }
            } else if (zeroFlowReceived != null) {
                // An old pre-outage snapshot may arrive after zero; it cannot establish return.
                if (!fromDevicePush && value in preZeroValues) return
                val first = candidateReceived
                if (first == null) {
                    candidateValue = value; candidateReceived = receivedUtcMillis
                } else if (value != candidateValue && receivedUtcMillis - first >= profile.minimumSampleSpacingMs) {
                    returnEvidence = returnEvidence ?: receivedUtcMillis
                }
            }
        }
    }

    fun snapshot(nowUtcMillis: Long): Snapshot {
        fun fresh(time: Long?) = time != null && nowUtcMillis >= time && nowUtcMillis - time <= profile.staleAfterMs
        val state = when {
            !fresh(lastLivePush) || gridReceived == null -> State.UNKNOWN
            gridCode == profile.connectedCode -> State.INVERTER_CONNECTED
            gridCode != profile.offGridCode -> State.UNKNOWN
            returnEvidence != null && fresh(meterReceived) -> State.GRID_RETURN_LIKELY
            else -> State.INVERTER_OFF_GRID
        }
        return Snapshot(state, zeroFlowReceived != null, returnEvidence, meterValue.takeIf { fresh(meterReceived) })
    }

    private fun resetMeterSequence() {
        meterReceived = null; meterValue = null; zeroFlowReceived = null
        candidateValue = null; candidateReceived = null; returnEvidence = null
    }
}
