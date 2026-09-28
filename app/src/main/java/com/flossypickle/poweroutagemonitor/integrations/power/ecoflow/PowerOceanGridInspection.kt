package com.flossypickle.poweroutagemonitor.integrations.power.ecoflow

import com.flossypickle.poweroutagemonitor.integrations.power.SourceBatteryReading

/** Per-inspection observations, kept separate from the outage state machine. */
internal class PowerOceanGridInspection(profile: PowerOceanGridCorrelation.Profile? = null) {
    data class Change(val code: Long, val receivedUtcMillis: Long)
    data class Snapshot(val lastCode: Long?, val lastReceivedUtcMillis: Long?, val changes: List<Change>,
        val correlation: PowerOceanGridCorrelation.Snapshot? = null,
        val lastCodeFromDevicePush: Boolean = false, val meterValue: Double? = null,
        val meterReceivedUtcMillis: Long? = null, val meterFromDevicePush: Boolean = false,
        val battery: SourceBatteryReading? = null)
    private val correlation = profile?.let { PowerOceanGridCorrelation(it) }

    private val meterKey = profile?.meterKey ?: PowerOceanGridCorrelation.Profile().meterKey
    private var meterValue: Double? = null
    private var meterReceived: Long? = null
    private var meterFromDevicePush = false
    private var lastCodeFromDevicePush = false
    private var lastCode: Long? = null
    private var lastReceivedUtcMillis: Long? = null
    private val changes = ArrayDeque<Change>()
    private var battery: SourceBatteryReading? = null

    fun resumeOffGridEpisode(receivedAt: Long) { correlation?.resumeOffGridEpisode(receivedAt) }

    fun observe(report: PowerOceanPushDecoder.Report, receivedUtcMillis: Long, retained: Boolean, fromDevicePush: Boolean = true, allowSnapshotBaseline: Boolean = false) {
        correlation?.observe(report, receivedUtcMillis, retained, fromDevicePush, allowSnapshotBaseline)
        if (retained || receivedUtcMillis <= 0) return
        if (report.command in setOf(8, 33, 50) && receivedUtcMillis >= (battery?.receivedAtEpochMs ?: 0)) {
            (report.values["bpSoc"] as? Number)?.toDouble()?.takeIf { it.isFinite() && it in 0.0..100.0 && it % 1.0 == 0.0 }
                ?.let { battery = SourceBatteryReading(it.toInt(), receivedUtcMillis, fromDevicePush) }
        }
        if (report.command == 1 && receivedUtcMillis >= (meterReceived ?: 0)) {
            (report.values[meterKey] as? Number)?.toDouble()?.takeIf(Double::isFinite)?.let {
                meterValue = it; meterReceived = receivedUtcMillis; meterFromDevicePush = fromDevicePush
            }
        }
        // Neither a retained cloud value nor an omitted protobuf field is a new grid observation.
        if (report.command != 8 || retained || receivedUtcMillis <= 0) return
        val code = report.values["sysGridSta"] as? Long ?: return
        if (code !in 0..0xFFFFFFFFL || receivedUtcMillis < (lastReceivedUtcMillis ?: 0)) return
        if (code != lastCode) {
            changes.addLast(Change(code, receivedUtcMillis))
            if (changes.size > 8) changes.removeFirst()
        }
        lastCode = code
        lastReceivedUtcMillis = receivedUtcMillis; lastCodeFromDevicePush = fromDevicePush
    }

    fun corroborateSampledOffGrid(live: PowerOceanLiveCheck.Status, minimumPowerReports: Int, now: Long) {
        val start = live.requestedAt ?: return
        val code = lastCode ?: return
        val codeAt = lastReceivedUtcMillis ?: return
        val meterAt = meterReceived ?: return
        if (lastCodeFromDevicePush || meterValue != 0.0 || !live.hasCurrentReport(now) ||
            !live.valuesChanged || live.powerUpdates < minimumPowerReports ||
            (live.lastDevicePushAt ?: 0) <= maxOf(codeAt, meterAt) ||
            codeAt <= start || meterAt <= start || codeAt > now || meterAt > now ||
            now - codeAt > 90_000 || now - meterAt > 90_000) return
        correlation?.corroborateOffGridReply(code, codeAt, meterAt)
    }

    fun snapshot(nowUtcMillis: Long = System.currentTimeMillis()) = Snapshot(lastCode, lastReceivedUtcMillis,
        changes.toList(), correlation?.snapshot(nowUtcMillis), lastCodeFromDevicePush, meterValue, meterReceived, meterFromDevicePush, battery)
}
