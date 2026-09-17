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
        val currentMeterValue: Double? = null, val evidenceReceivedAtUtcMillis: Long? = null)

    private data class InitialConnectedReply(val receivedAt: Long)
    private var pendingMeterReply: Pair<Double, Long>? = null
    private var initialConnectedReply: InitialConnectedReply? = null
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

    fun resumeOffGridEpisode(receivedAt: Long) {
        if (receivedAt <= 0) return
        gridCode = profile.offGridCode; gridReceived = receivedAt; offGridStarted = receivedAt; zeroFlowReceived = receivedAt
    }

    fun observe(report: PowerOceanPushDecoder.Report, receivedUtcMillis: Long,
        retained: Boolean, fromDevicePush: Boolean, allowSnapshotBaseline: Boolean = false) {
        if (retained || receivedUtcMillis <= 0) return
        // Bootstrap a normal connected state only when a new device update follows the reply.
        // Once a grid state is established, snapshot replies can never overwrite it.
        // Incident monitoring disables this baseline, protecting known outages from cached recovery.
        if (!fromDevicePush && report.command == 8 && allowSnapshotBaseline && (gridCode == null || gridCode == profile.connectedCode || returnEvidence != null) &&
            report.values["sysGridSta"] == profile.connectedCode) {
            initialConnectedReply = InitialConnectedReply(receivedUtcMillis)
        }
        if (!fromDevicePush && report.command == 1 && allowSnapshotBaseline) {
            (report.values[profile.meterKey] as? Number)?.toDouble()?.takeIf(Double::isFinite)?.let {
                pendingMeterReply = it to receivedUtcMillis
            }
        }
        val previousLivePush = lastLivePush
        if (fromDevicePush && report.command in setOf(1, 8, 33) && report.values.values.any { it is Number }) {
            if (receivedUtcMillis < (lastLivePush ?: 0)) return
            if (previousLivePush != null && receivedUtcMillis - previousLivePush > profile.staleAfterMs) {
                if (!allowSnapshotBaseline) {
                    gridCode = null; gridReceived = null; offGridStarted = null; resetMeterSequence()
                } else {
                    // Deliberate disconnection: retain known state, but require a new live feed.
                    meterReceived = null; meterValue = null
                    // A candidate from the previous bounded check can be compared with new meter activity.
                    // Zero flow or a changed grid code still resets it, so old cached flow cannot establish return.
                }
            }
            lastLivePush = receivedUtcMillis
            val initial = initialConnectedReply
            if (allowSnapshotBaseline && (gridCode == null || gridCode == profile.offGridCode && returnEvidence != null) && initial != null &&
                receivedUtcMillis - initial.receivedAt in 1..profile.staleAfterMs) {
                gridCode = profile.connectedCode; gridReceived = initial.receivedAt
            }
            initialConnectedReply = null
            pendingMeterReply?.let { (value, received) ->
                if (allowSnapshotBaseline && receivedUtcMillis - received in 0..profile.staleAfterMs) {
                    observeMeter(value, received, false, true)
                }
            }
            pendingMeterReply = null
        }
        // A request reply alone cannot prove that the inverter is still reporting.
        val live = lastLivePush?.let { receivedUtcMillis - it in 0..profile.staleAfterMs } == true
        if (!fromDevicePush && (!live || report.command != 1)) return
        if (report.command == 8) {
            val code = report.values["sysGridSta"] as? Long ?: return
            if (receivedUtcMillis < (gridReceived ?: 0)) return
            val previousExpired = previousLivePush?.let { receivedUtcMillis - it > profile.staleAfterMs } == true
            if (code != gridCode || previousExpired && !allowSnapshotBaseline) {
                resetMeterSequence()
                offGridStarted = if (code == profile.offGridCode) receivedUtcMillis else null
            }
            initialConnectedReply = null
            gridCode = code
            gridReceived = receivedUtcMillis
        } else if (report.command == 1) {
            val value = (report.values[profile.meterKey] as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: return
            observeMeter(value, receivedUtcMillis, fromDevicePush, live)
        }
    }

    private fun observeMeter(value: Double, receivedUtcMillis: Long, fromDevicePush: Boolean, live: Boolean) {
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

    fun snapshot(nowUtcMillis: Long): Snapshot {
        fun fresh(time: Long?) = time != null && nowUtcMillis >= time && nowUtcMillis - time <= profile.staleAfterMs
        val state = when {
            !fresh(lastLivePush) || gridReceived == null -> State.UNKNOWN
            gridCode == profile.connectedCode -> State.INVERTER_CONNECTED
            gridCode != profile.offGridCode -> State.UNKNOWN
            returnEvidence != null && fresh(meterReceived) -> State.GRID_RETURN_LIKELY
            else -> State.INVERTER_OFF_GRID
        }
        val evidence = when (state) {
            State.INVERTER_CONNECTED -> gridReceived
            State.GRID_RETURN_LIKELY -> returnEvidence
            State.INVERTER_OFF_GRID -> meterReceived ?: gridReceived
            State.UNKNOWN -> null
        }
        return Snapshot(state, zeroFlowReceived != null, returnEvidence, meterValue.takeIf { fresh(meterReceived) }, evidence)
    }

    private fun resetMeterSequence() {
        meterReceived = null; meterValue = null; zeroFlowReceived = null
        candidateValue = null; candidateReceived = null; returnEvidence = null
    }
}
