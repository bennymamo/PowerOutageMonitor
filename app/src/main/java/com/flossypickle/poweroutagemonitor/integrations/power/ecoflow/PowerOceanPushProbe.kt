package com.flossypickle.poweroutagemonitor.integrations.power.ecoflow

import android.os.Build
import android.os.SystemClock
import com.flossypickle.poweroutagemonitor.integrations.power.SourceTelemetrySnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import javax.net.ssl.HttpsURLConnection

/** Shared secure feed for manual inspections and opt-in monitoring; never power controls. */
internal class PowerOceanPushProbe(private val context: android.content.Context? = null) {
    data class Update(val snapshot: SourceTelemetrySnapshot, val packets: Int, val unsupported: Int, val retained: Int,
        val gridInspection: PowerOceanGridInspection.Snapshot, val chargerExternallyPowered: Boolean? = null,
        val confirmation: PowerOceanLossConfirmation.Result? = null, val liveCheck: PowerOceanLiveCheck.Status? = null)
    private data class Packet(val reports: List<PowerOceanPushDecoder.Report>, val received: Long, val retained: Boolean,
        val json: JSONObject? = null, val receivedUtcMillis: Long = System.currentTimeMillis(), val fromDevicePush: Boolean = false)

    suspend fun inspect(session: PowerOceanAccountClient.Session, credentials: PowerOceanAccountClient.PushCredentials,
        requestLiveReporting: Boolean = false, inspectionSeconds: Int = 45,
        correlationProfile: PowerOceanGridCorrelation.Profile? = null,
        requireChargerConfirmation: Boolean = false,
        continuous: Boolean = false, readIntervalSeconds: Int = 60,
        readSchedule: (() -> PowerOceanReadSchedule)? = null,
        liveCheck: PowerOceanLiveCheck = PowerOceanLiveCheck(),
        onUpdate: suspend (Update) -> Unit): String? = withContext(Dispatchers.IO) {
        require(inspectionSeconds in setOf(45, 300, 900))
        require(readIntervalSeconds in 60..3600)
        val gridInspection = PowerOceanGridInspection(correlationProfile)
        // Private, bounded development capture for comparing utility loss and restoration.
        // Record only predefined decoded numeric/boolean fields; never raw payloads or account data.
        val validationTrace = context?.takeIf {
            it.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        }?.takeUnless { continuous }?.let { java.io.File(it.cacheDir, "powerocean-grid-validation.jsonl") }
        runCatching { validationTrace?.writeText(JSONObject().put("inspectionStartedUtcMillis", System.currentTimeMillis()).toString() + "\n") }
        val queue = ConcurrentLinkedQueue<Packet>()
        val client = runCatching {
            require(credentials.transport in setOf("ssl", "wss"))
            val id = if (credentials.transport == "ssl") "ANDROID_${UUID.randomUUID().toString().replace("-", "").uppercase(java.util.Locale.ROOT)}_${session.userId}" else clientId(session.userId)
            MqttClient("${credentials.transport}://${credentials.host}:${credentials.port}${credentials.path}", id, MemoryPersistence())
        }
            .getOrElse { return@withContext "The secure push client could not initialise." }
        client.timeToWait = 15_000
        var packets = 0
        var unsupported = 0
        var retained = 0
        val disconnected = java.util.concurrent.atomic.AtomicBoolean(false)
        val reports = linkedMapOf<Int, Packet>()
        var latestJson: Packet? = null
        var lastSnapshot: SourceTelemetrySnapshot? = null
        var nextUiUpdate = 0L
        var chargerPowered: Boolean? = null
        var stage = "connecting to the secure broker"
        client.setCallback(object : MqttCallback {
            override fun connectionLost(cause: Throwable?) { disconnected.set(true) }
            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
            override fun messageArrived(topic: String?, message: MqttMessage?) {
                if (message == null || message.payload.size > 262_144 || queue.size >= 128) return
                val json = if (message.payload.firstOrNull()?.toInt() == 123) runCatching {
                    val root = JSONObject(message.payload.toString(Charsets.UTF_8))
                    root.optJSONObject("params") ?: root.optJSONObject("data") ?: root.takeIf { it.has("quota") }
                }.getOrNull() else null
                val decoded = if (json == null) PowerOceanPushDecoder.decode(message.payload) else emptyList()
                queue.add(Packet(decoded, SystemClock.elapsedRealtime(), message.isRetained, json,
                    fromDevicePush = topic == "/app/device/property/${session.connection.serial}"))
            }
        })
        try {
            val options = MqttConnectOptions().apply {
                userName = credentials.account; password = credentials.password.toCharArray()
                isCleanSession = true; isAutomaticReconnect = false
                connectionTimeout = 15; keepAliveInterval = 60
                mqttVersion = MqttConnectOptions.MQTT_VERSION_3_1_1
                // Paho silently skips endpoint checks below API24; use explicit verification there.
                isHttpsHostnameVerificationEnabled = Build.VERSION.SDK_INT >= 24
                sslHostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
            }
            client.connect(options)
            ensureActive()
            stage = "subscribing to device readings"
            client.subscribe("/app/device/property/${session.connection.serial}", 0)
            client.subscribe("/app/${session.userId}/${session.connection.serial}/thing/property/get_reply", 1)
            // A broker login is not necessarily safe as one MQTT topic segment.
            if (credentials.account.none { it in "/+#" }) {
                try { client.subscribe("/open/${credentials.account}/${session.connection.serial}/quota", 1) }
                catch (failure: MqttException) { if (failure.reasonCode != 128) throw failure }
            }
            val deadline = if (continuous) Long.MAX_VALUE else SystemClock.elapsedRealtime() + inspectionSeconds * 1000L
            val sampling = PowerOceanSamplingSchedule()
            var nextLiveRequest = 0L
            stage = "requesting and receiving readings"
            while (SystemClock.elapsedRealtime() < deadline && !disconnected.get()) {
                ensureActive()
                val schedule = readSchedule?.invoke() ?: PowerOceanReadSchedule(readIntervalSeconds, false, 0, false)
                val readDue = sampling.due(schedule, SystemClock.elapsedRealtime())
                if (readDue) liveCheck.begin(System.currentTimeMillis())
                if (schedule.needsLiveActivation(requestLiveReporting, readDue, SystemClock.elapsedRealtime() >= nextLiveRequest)) {
                    client.publish("/app/${session.userId}/${session.connection.serial}/thing/property/set",
                        PowerOceanReadingRequests.liveReporting((System.currentTimeMillis() and 0x7FFFFFFF).toInt()), 1, false)
                    if (!schedule.liveOnEachRead) nextLiveRequest = SystemClock.elapsedRealtime() + 20_000
                }
                if (readDue) {
                    // GET-only request used by the app to ask for current observations.
                    val getTopic = "/app/${session.userId}/${session.connection.serial}/thing/property/get"
                    client.publish(getTopic, PowerOceanReadingRequests.allReadings((System.currentTimeMillis() and 0x7FFFFFFF).toInt()), 1, false)
                }
                var packet = queue.poll()
                var changed = false
                while (packet != null) {
                    val receivedPacket = packet
                    packets++; if (receivedPacket.retained) retained++
                    if (receivedPacket.reports.isEmpty() && receivedPacket.json == null) unsupported++
                    if (receivedPacket.json != null) { latestJson = receivedPacket; changed = true }
                    receivedPacket.reports.forEach {
                        reports[it.command] = receivedPacket; changed = true
                        gridInspection.observe(it, receivedPacket.receivedUtcMillis, receivedPacket.retained, receivedPacket.fromDevicePush)
                        liveCheck.observe(it, receivedPacket.receivedUtcMillis, receivedPacket.retained, receivedPacket.fromDevicePush)
                    }
                    runCatching {
                        validationTrace?.takeIf { it.length() < 2_000_000 }?.let { trace ->
                            receivedPacket.reports.forEach { report ->
                                val safeValues = report.values.filterValues { it is Number || it is Boolean }
                                trace.appendText(JSONObject().put("receivedUtcMillis", receivedPacket.receivedUtcMillis)
                                    .put("command", report.command).put("retained", receivedPacket.retained)
                                    .put("fromDevicePush", receivedPacket.fromDevicePush)
                                    .put("values", JSONObject(safeValues)).toString() + "\n")
                            }
                        }
                    }
                    packet = queue.poll()
                }
                if (changed) {
                    val data = JSONObject()
                    val quota = JSONObject()
                    latestJson?.let { packet ->
                        quota.put("Live_JSON_report", JSONObject().put("readings", packet.json)
                            .put("receivedAgeSeconds", (SystemClock.elapsedRealtime() - packet.received) / 1000)
                            .put("retainedMessage", packet.retained))
                    }
                    reports.forEach { (command, receivedPacket) ->
                        val report = receivedPacket.reports.first { it.command == command }
                        val json = JSONObject(report.values)
                        json.put("receivedAgeSeconds", (SystemClock.elapsedRealtime() - receivedPacket.received) / 1000)
                        json.put("retainedMessage", receivedPacket.retained)
                        if (command == 33 || command == 50) report.values.forEach { (key, value) -> data.put(key, value) }
                        quota.put("Live_report_$command", json)
                    }
                    data.put("quota", quota)
                    val snapshot = PowerOceanAccountTelemetry.snapshot(data, System.currentTimeMillis()).copy(
                        sourceName = "PowerOcean push feed · experimental",
                        acquisitionNote = (if (continuous) "Experimental background account feed. " else "$inspectionSeconds-second account push inspection. ") +
                            (if (requestLiveReporting || readSchedule?.invoke()?.liveOnEachRead == true) {
                                if (readSchedule?.invoke()?.liveOnEachRead == true) "Assisted mode requests temporary live reporting with each scheduled/manual check. "
                                else "Temporary live reporting is requested every 20 seconds using portal command 96/97. "
                            } else "Live-report activation is off; reading requests only. ") +
                            "No power-control commands are sent. Sections contain each command's latest report; see receivedAgeSeconds and retainedMessage. Packet receipt is not a verified measurement timestamp. Unsupported packets are counted, not logged. Grid comparisons use only explicitly selected, installation-tested profiles."
                    )
                    if (snapshot.sections.isNotEmpty()) lastSnapshot = snapshot
                }
                // Keep source health and auxiliary charger state current even during quiet periods.
                if (SystemClock.elapsedRealtime() >= nextUiUpdate) {
                    chargerPowered = runCatching {
                        com.flossypickle.poweroutagemonitor.monitoring.PowerSnapshot.from(context?.registerReceiver(
                            null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)))?.externallyPowered
                    }.getOrNull()
                    val displaySnapshot = lastSnapshot ?: SourceTelemetrySnapshot("PowerOcean push feed · experimental",
                        "PowerOcean", System.currentTimeMillis(), emptyList(), emptyList())
                    displaySnapshot.let { currentSnapshot ->
                        val comparison = gridInspection.snapshot()
                        val confirmation = comparison.correlation?.let {
                            PowerOceanLossConfirmation.evaluate(it, chargerPowered, requireChargerConfirmation, liveCheck.status().hasCurrentReport(System.currentTimeMillis()))
                        }
                        withContext(Dispatchers.Main) {
                            onUpdate(Update(currentSnapshot, packets, unsupported, retained, comparison, chargerPowered, confirmation, liveCheck.status()))
                        }
                    }
                    nextUiUpdate = SystemClock.elapsedRealtime() + 1000
                }
                delay(250)
            }
            when { disconnected.get() -> "The push connection disconnected. No outage was inferred."
                reports.isEmpty() && latestJson == null -> "Push connection opened, but no supported PowerOcean readings arrived. Packets: $packets; unsupported: $unsupported. EcoFlow may require live-report activation or a different report decoder."
                else -> null }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: MqttException) { "Live feed failed while $stage (MQTT code ${failure.reasonCode}). No outage was inferred." }
        catch (_: Exception) { "PowerOcean push inspection could not connect or read data. Check account access and internet connectivity." }
        finally {
            runCatching { if (client.isConnected) client.disconnectForcibly(0, 1000, true) }
            runCatching { client.close(true) }
            queue.clear()
        }
    }

    private fun clientId(userId: String): String {
        // Public portal protocol constants; adapted from shuette42 (MIT), see THIRD_PARTY_NOTICES.md.
        val appKey = "e80b6010a485434a806e5e531479a37c"
        val protocolSecret = "aed8900fd005458cb41a762ffe375e1b"
        val timestamp = System.currentTimeMillis()
        val base = "WEB_${UUID.randomUUID()}_$userId"
        val digest = MessageDigest.getInstance("MD5").digest("$protocolSecret$base$timestamp".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it.toInt() and 255) }
        return "${base}_${appKey}_${timestamp}_$digest"
    }
}
