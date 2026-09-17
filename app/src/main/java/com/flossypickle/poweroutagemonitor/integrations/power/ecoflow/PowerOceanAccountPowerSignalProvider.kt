package com.flossypickle.poweroutagemonitor.integrations.power.ecoflow

import android.content.Context
import com.flossypickle.poweroutagemonitor.integrations.power.*
import kotlinx.coroutines.*

/** Service-owned, opt-in account feed. Reuses login and broker access; never logs secrets. */
internal class PowerOceanAccountPowerSignalProvider(context: Context) : PowerSignalProvider {
    private val appContext = context.applicationContext
    override val id = PowerSourceStore.POWEROCEAN_PROVIDER_ID
    override val displayName = "PowerOcean account"
    private var scope: CoroutineScope? = null
    private var worker: Job? = null
    @Volatile private var chargerPowered: Boolean? = null
    private val manualRevision = java.util.concurrent.atomic.AtomicLong(0)
    fun updateCharger(powered: Boolean?) { chargerPowered = powered }
    fun requestCheck(): Boolean {
        if (worker?.isActive != true) return false
        manualRevision.incrementAndGet()
        return true
    }
    private fun incident() = chargerPowered == false || PowerSourceStore(appContext).assistedEcoFlowOutageStartedAt() > 0 || com.flossypickle.poweroutagemonitor.storage.MonitorStore(appContext).state().phase in
        setOf(com.flossypickle.poweroutagemonitor.OutageEngine.Phase.PENDING_OUTAGE, com.flossypickle.poweroutagemonitor.OutageEngine.Phase.OUTAGE, com.flossypickle.poweroutagemonitor.OutageEngine.Phase.PENDING_RESTORE)


    @Synchronized
    override fun start(onSignal: (PowerSignal) -> Unit) {
        if (scope != null) return
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = owner
        worker = owner.launch {
            fun emit(availability: GridAvailability, detail: String, pending: Boolean = false, evidenceAt: Long? = null, dataStalled: Boolean? = null, check: PowerSourceCheck? = null) {
                if (isActive) onSignal(PowerSignal(availability, System.currentTimeMillis(), id, detail, pending, evidenceAt, dataStalled, check))
            }
            val client = PowerOceanAccountClient()
            var session: PowerOceanAccountClient.Session? = null
            var credentials: PowerOceanAccountClient.PushCredentials? = null
            var backoff = 60_000L
            val liveCheck = PowerOceanLiveCheck()
            while (isActive) {
                val store = PowerSourceStore(appContext)
                val assisted = store.powerOceanAssistedSettings()
                val interval = if (incident()) assisted.outageSeconds else assisted.normalSeconds
                if (assisted.enabled && (store.powerOceanAssistancePaused() || interval == 0 && manualRevision.get() == 0L) && session == null) {
                    emit(GridAvailability.UNKNOWN, "EcoFlow checks are manual; the local charger watcher continues.")
                    delay(1000); continue
                }
                val account = runCatching { PowerOceanAccountStore(appContext).connection() }.getOrNull()
                if (account == null) {
                    emit(GridAvailability.UNKNOWN, "Unlock this device and check PowerOcean account settings.")
                    delay(60_000); continue
                }
                if (!store.powerOceanProfileVerified(account)) {
                    emit(GridAvailability.UNKNOWN, "Verify this installation's grid profile before monitoring.")
                    return@launch
                }
                emit(GridAvailability.UNKNOWN, "Connecting to PowerOcean live readings…")
                try {
                    ensureActive()
                    if (session == null) {
                        when (val result = client.login(account)) {
                            is EcoFlowCloudClient.Result.Success -> session = result.value
                            is EcoFlowCloudClient.Result.Failure -> {
                                emit(GridAvailability.UNKNOWN, result.message)
                                if (!result.retryable) return@launch
                            }
                        }
                    }
                    val current = session
                    ensureActive()
                    if (current != null && credentials == null) {
                        when (val result = client.pushCredentials(current)) {
                            is EcoFlowCloudClient.Result.Success -> credentials = result.value
                            is EcoFlowCloudClient.Result.Failure -> {
                                emit(GridAvailability.UNKNOWN, result.message)
                                if (!result.retryable) return@launch
                            }
                        }
                    }
                    val access = credentials
                    if (current != null && access != null) {
                        val connectedAt = android.os.SystemClock.elapsedRealtime()
                        val failure = PowerOceanPushProbe(appContext).inspect(current, access,
                            requestLiveReporting = store.powerOceanRequestsLiveReporting(),
                            correlationProfile = PowerOceanGridCorrelation.Profile(),
                            requireChargerConfirmation = store.powerOceanRequiresChargerConfirmation(),
                            continuous = true, readIntervalSeconds = account.refreshSeconds.coerceAtLeast(60),
                            readSchedule = {
                                val settings = store.powerOceanAssistedSettings()
                                val active = incident()
                                val seconds = if (settings.enabled) { if (active) settings.outageSeconds else settings.normalSeconds } else account.refreshSeconds.coerceAtLeast(60)
                                PowerOceanReadSchedule(seconds.takeIf { it > 0 }, active, manualRevision.get(), settings.enabled, settings.enabled && store.powerOceanAssistancePaused(),
                                    settings.enabled && chargerPowered != false && store.assistedEcoFlowOutageStartedAt() > 0)
                            }, liveCheck = liveCheck) { update ->
                            val result = update.confirmation ?: return@inspect
                            val detail = when (result.reason) {
                                PowerOceanLossConfirmation.Reason.CONNECTED -> "EcoFlow reports a grid connection."
                                PowerOceanLossConfirmation.Reason.RETURN_PENDING -> "Grid appears back; waiting for EcoFlow to reconnect."
                                PowerOceanLossConfirmation.Reason.ECOFLOW_AND_METER -> "EcoFlow off-grid; meter reports zero flow."
                                PowerOceanLossConfirmation.Reason.CHARGER_CORROBORATED -> "Grid and charger loss evidence agree."
                                PowerOceanLossConfirmation.Reason.WAITING_FOR_CHARGER -> "Waiting for charger-loss confirmation."
                                PowerOceanLossConfirmation.Reason.WAITING_FOR_LIVE_DATA -> "Waiting for EcoFlow to send updated readings; older saved readings cannot verify this check."
                                PowerOceanLossConfirmation.Reason.UNKNOWN -> "Waiting for updated grid and meter readings."
                            }
                            val dataWarning = if (update.liveCheck?.possiblyStalled == true) " Power readings are identical across successive checks; the feed may be stalled or the load steady." else ""
                            val check = update.liveCheck?.let { status -> status.requestedAt?.let { requested ->
                                val labels = mapOf("sysLoadPwr" to "Home load", "sysGridPwr" to "Grid power flow",
                                    "mpptPwr" to "Solar power", "bpPwr" to "Battery power flow")
                                val inspection = update.gridInspection
                                val observations = listOfNotNull(
                                    inspection.lastCode?.let { code -> inspection.lastReceivedUtcMillis?.let { received ->
                                        val explanation = when (code) {
                                            0L -> "In the tested Single Phase profile, 0 means the inverter reports grid connection."
                                            1L -> "In the tested Single Phase profile, 1 means off-grid; this can include the delay while the inverter reconnects."
                                            else -> "This grid code is unsupported by the selected tested profile."
                                        }
                                        SourceReportedValue("Reported grid code", code.toString(), explanation, received, inspection.lastCodeFromDevicePush)
                                    } },
                                    inspection.meterValue?.let { meter -> inspection.meterReceivedUtcMillis?.let { received ->
                                        SourceReportedValue("Meter 1 reading", meter.toString(),
                                            if (meter == 0.0) "Zero means no reported flow. Zero alone does not prove grid loss."
                                            else "Non-zero means reported meter activity. Changing activity can support grid return after a confirmed loss; sign/direction depends on the installation.",
                                            received, inspection.meterFromDevicePush)
                                    } })
                                PowerSourceCheck(requested, status.lastDevicePushAt,
                                    result.availability != GridAvailability.UNKNOWN,
                                    status.powerValues.map { (key, watts) -> SourceTelemetryReading(key, labels.getValue(key), watts.toString(), "W") }, observations)
                            } }
                            emit(result.availability, detail + dataWarning, result.reason == PowerOceanLossConfirmation.Reason.RETURN_PENDING, update.gridInspection.correlation?.evidenceReceivedAtUtcMillis,
                                update.liveCheck?.takeIf { it.comparedPower }?.possiblyStalled, check)
                        }
                        emit(GridAvailability.UNKNOWN, failure ?: "PowerOcean feed stopped.")
                        // Authentication/topic rejection stops automatic access attempts. User reconnects explicitly.
                        if (failure?.contains(Regex("MQTT code (4|5|128)\\)")) == true) return@launch
                        if (android.os.SystemClock.elapsedRealtime() - connectedAt > 5 * 60_000L) backoff = 60_000
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { emit(GridAvailability.UNKNOWN, "PowerOcean readings unavailable; waiting before reconnecting.") }
                delay(backoff + kotlin.random.Random.nextLong(0, 10_000))
                backoff = (backoff * 2).coerceAtMost(30 * 60_000L)
            }
        }
    }

    @Synchronized
    override fun stop() { scope?.cancel(); scope = null; worker = null }
}
