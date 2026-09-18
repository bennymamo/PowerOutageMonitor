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
    @Synchronized
    fun updateCharger(powered: Boolean?) {
        val lostPower = chargerPowered == true && powered == false
        chargerPowered = powered
        // Each new unplug must check immediately, even during backoff or an existing incident.
        if (lostPower && PowerSourceStore(appContext).powerOceanAssistedSettings().let { it.enabled && it.outageSeconds > 0 })
            manualRevision.incrementAndGet()
    }
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
            var latest: PowerSignal? = null
            var lastVerified: PowerSignal? = null
            var lastVerifiedIntervalSeconds: Int? = null
            var lastConfirmedOnlineAt: Long? = null
            var savedRefreshSeconds: Int? = 60
            fun emit(availability: GridAvailability, detail: String, pending: Boolean = false, evidenceAt: Long? = null,
                dataStalled: Boolean? = null, check: PowerSourceCheck? = null) {
                if (isActive) {
                    val now = System.currentTimeMillis()
                    val samplingSettings = PowerSourceStore(appContext).powerOceanAssistedSettings()
                    val configuredInterval = if (samplingSettings.enabled)
                        (if (incident()) samplingSettings.outageSeconds else samplingSettings.normalSeconds).takeIf { it > 0 }
                        else savedRefreshSeconds
                    if (check?.active == true && check.gridEvidenceAvailable && evidenceAt != null && availability != GridAvailability.UNKNOWN) {
                        lastVerified = PowerSignal(availability, now, id, detail, pending, evidenceAt, dataStalled, check)
                        lastVerifiedIntervalSeconds = configuredInterval
                        lastConfirmedOnlineAt = evidenceAt.takeIf { availability == GridAvailability.AVAILABLE && !pending }
                    }
                    // A new check keeps the previous report's deadline until new evidence arrives.
                    val continuityInterval = PowerOceanCheckContinuity.intervalDuringCheck(lastVerified, check,
                        lastVerifiedIntervalSeconds, configuredInterval)
                    val carried = if (availability == GridAvailability.UNKNOWN)
                        PowerOceanCheckContinuity.availability(lastVerified, check, now,
                            samplingSettings.checkWindowSeconds * 1000L,
                            continuityInterval)
                    else GridAvailability.UNKNOWN
                    val signal = PowerSignal(if (carried != GridAvailability.UNKNOWN) carried else availability, now, id, detail,
                        if (carried != GridAvailability.UNKNOWN) lastVerified!!.recoveryPending else pending,
                        if (carried != GridAvailability.UNKNOWN) lastVerified!!.evidenceReceivedAtEpochMs else evidenceAt, dataStalled,
                        check?.copy(lastConfirmedOnlineAtEpochMs = lastConfirmedOnlineAt,
                            lastConfirmedOnlineValidUntilEpochMs = lastConfirmedOnlineAt?.let { it + ((continuityInterval ?: 0) + 60) * 1000L },
                            ecoFlowAvailability = if (carried != GridAvailability.UNKNOWN) carried else availability))
                    latest = signal; onSignal(signal)
                }
            }
            val client = PowerOceanAccountClient()
            var session: PowerOceanAccountClient.Session? = null
            var credentials: PowerOceanAccountClient.PushCredentials? = null
            val liveCheck = PowerOceanLiveCheck()
            val gridInspection = PowerOceanGridInspection(PowerOceanGridCorrelation.Profile())
            gridInspection.resumeOffGridEpisode(PowerSourceStore(appContext).assistedEcoFlowOutageStartedAt())
            val sampling = PowerOceanSamplingSchedule()
            var backoff = 60_000L
            var retryAt = 0L
            var attemptedManual = 0L
            var lastFailure: String? = null
            // Account editing requires switching away from this source; avoid decrypting secrets every idle tick.
            var savedAccount = runCatching { PowerOceanAccountStore(appContext).connection() }.getOrNull()
            savedRefreshSeconds = savedAccount?.refreshSeconds?.coerceAtLeast(60)
            var nextAccountRetry = 0L
            while (isActive) {
                if (savedAccount == null && android.os.SystemClock.elapsedRealtime() >= nextAccountRetry) {
                    savedAccount = runCatching { PowerOceanAccountStore(appContext).connection() }.getOrNull()
                    savedRefreshSeconds = savedAccount?.refreshSeconds?.coerceAtLeast(60)
                    nextAccountRetry = android.os.SystemClock.elapsedRealtime() + 10_000
                    // Credential-protected storage becomes available after the first unlock.
                    if (savedAccount != null) manualRevision.incrementAndGet()
                }
                val store = PowerSourceStore(appContext)
                val assisted = store.powerOceanAssistedSettings()
                val active = incident()
                val seconds = if (assisted.enabled) {
                    if (active) assisted.outageSeconds else assisted.normalSeconds
                } else savedAccount?.refreshSeconds?.coerceAtLeast(60) ?: 60
                val schedule = PowerOceanReadSchedule(seconds.takeIf { it > 0 }, active, manualRevision.get(), true,
                    store.powerOceanAssistancePaused(), assisted.enabled && chargerPowered != false && store.assistedEcoFlowOutageStartedAt() > 0)
                val monotonic = android.os.SystemClock.elapsedRealtime()
                val retryBlocked = monotonic < retryAt && schedule.manualRevision <= attemptedManual
                val due = !retryBlocked && sampling.due(schedule, monotonic)
                if (!due) {
                    val now = System.currentTimeMillis()
                    val next = if (schedule.paused) null else sampling.nextDueAt?.let {
                        now + (maxOf(it, retryAt) - monotonic).coerceAtLeast(0)
                    }
                    val last = latest
                    val check = last?.check?.copy(cycleState = when { schedule.paused -> PowerSourceCheck.CycleState.PAUSED; lastFailure != null -> PowerSourceCheck.CycleState.FAILED; else -> PowerSourceCheck.CycleState.WAITING },
                        nextCheckAtEpochMs = next)
                    val availability = if (!schedule.paused && lastFailure == null)
                        PowerOceanCheckContinuity.availability(lastVerified, check, now, assisted.checkWindowSeconds * 1000L, seconds.takeIf { it > 0 })
                    else GridAvailability.UNKNOWN
                    val detail = lastFailure ?: when {
                        schedule.paused -> "EcoFlow paused. Connection closed; charger watching continues."
                        check == null -> "EcoFlow connection closed. Waiting for a scheduled or manual check."
                        else -> "EcoFlow connection closed between checks. " +
                            if (availability != GridAvailability.UNKNOWN) "Last confirmed grid state retained until the next check." else "Last check is saved; charger watching continues."
                    }
                    // Reassess existing evidence without receiving or making any network request.
                    emit(availability, detail, last?.recoveryPending ?: false, last?.evidenceReceivedAtEpochMs,
                        last?.dataPossiblyStalled, check)
                    delay(1000); continue
                }
                attemptedManual = schedule.manualRevision
                val account = savedAccount
                val started = System.currentTimeMillis()
                liveCheck.begin(started)
                emit(GridAvailability.UNKNOWN, "Connecting for an EcoFlow check…", check = PowerSourceCheck(started, null, false,
                    cycleState = PowerSourceCheck.CycleState.CONNECTING))
                if (account == null || !store.powerOceanProfileVerified(account)) {
                    lastFailure = if (account == null) "Unlock this device and check PowerOcean account settings." else "Verify this installation's grid profile before monitoring."
                    emit(GridAvailability.UNKNOWN, lastFailure!!, check = latest?.check?.copy(cycleState = PowerSourceCheck.CycleState.FAILED, finishedAtEpochMs = System.currentTimeMillis()))
                    if (account != null) return@launch
                    sampling.finishCheck(android.os.SystemClock.elapsedRealtime()); delay(1000); continue
                }
                try {
                    if (session?.connection != account) { session = null; credentials = null }
                    if (session == null) {
                        when (val login = client.login(account)) {
                            is EcoFlowCloudClient.Result.Success -> session = login.value
                            is EcoFlowCloudClient.Result.Failure -> {
                                lastFailure = login.message
                                emit(GridAvailability.UNKNOWN, login.message, check = latest?.check?.copy(cycleState = PowerSourceCheck.CycleState.FAILED, finishedAtEpochMs = System.currentTimeMillis()))
                                if (!login.retryable) return@launch
                            }
                        }
                    }
                    val current = session
                    if (current != null && credentials == null) {
                        when (val access = client.pushCredentials(current)) {
                            is EcoFlowCloudClient.Result.Success -> credentials = access.value
                            is EcoFlowCloudClient.Result.Failure -> {
                                lastFailure = access.message
                                emit(GridAvailability.UNKNOWN, access.message, check = latest?.check?.copy(cycleState = PowerSourceCheck.CycleState.FAILED, finishedAtEpochMs = System.currentTimeMillis()))
                                if (!access.retryable) return@launch
                            }
                        }
                    }
                    val access = credentials
                    if (current != null && access != null) {
                        lastFailure = null
                        val failure = PowerOceanPushProbe(appContext).inspect(current, access,
                            requestLiveReporting = true, inspectionSeconds = assisted.checkWindowSeconds,
                            correlationProfile = PowerOceanGridCorrelation.Profile(),
                            requireChargerConfirmation = store.powerOceanRequiresChargerConfirmation(),
                            readIntervalSeconds = account.refreshSeconds.coerceAtLeast(60), singleCheck = true,
                            cyclePolicy = PowerOceanCheckCyclePolicy(assisted.checkWindowSeconds, assisted.extraPowerUpdates),
                            sharedGridInspection = gridInspection, liveCheck = liveCheck,
                            readSchedule = { PowerOceanReadSchedule(null, incident(), 1, true, store.powerOceanAssistancePaused()) }) { update ->
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
                                        SourceReportedValue("Reported grid code", code.toString(), explanation, received, inspection.lastCodeFromDevicePush,
                                            supportedByLiveFeed = code == 0L && inspection.correlation?.state == PowerOceanGridCorrelation.State.INVERTER_CONNECTED &&
                                                status.hasCurrentReport(System.currentTimeMillis()))
                                    } },
                                    inspection.meterValue?.let { meter -> inspection.meterReceivedUtcMillis?.let { received ->
                                        SourceReportedValue("Meter 1 reading", meter.toString(),
                                            if (meter == 0.0) "Zero means no reported flow. Zero alone does not prove grid loss."
                                            else "Non-zero means reported meter activity. Changing activity can support grid return after a confirmed loss; sign/direction depends on the installation.",
                                            received, inspection.meterFromDevicePush)
                                    } })
                                PowerSourceCheck(requested, status.lastDevicePushAt,
                                    result.availability != GridAvailability.UNKNOWN,
                                    status.powerValues.map { (key, watts) -> SourceTelemetryReading(key, labels.getValue(key), watts.toString(), "W") }, observations, cycleState = PowerSourceCheck.CycleState.COLLECTING,
                                    deviceUpdates = status.deviceUpdates, powerUpdates = status.powerUpdates, valuesChanged = status.valuesChanged)
                            } }
                            val evidenceAt = update.gridInspection.correlation?.let { grid ->
                                PowerOceanLossConfirmation.evidenceReceivedAt(grid, update.liveCheck,
                                    System.currentTimeMillis(), assisted.extraPowerUpdates + 1, update.gridInspection.lastCodeFromDevicePush,
                                    update.gridInspection.meterReceivedUtcMillis.takeIf { update.gridInspection.meterFromDevicePush })
                            }
                            emit(result.availability, detail + dataWarning, result.reason == PowerOceanLossConfirmation.Reason.RETURN_PENDING, evidenceAt,
                                update.liveCheck?.takeIf { it.comparedPower }?.possiblyStalled, check)

                        }
                        lastFailure = failure
                        if (failure?.contains(Regex("MQTT code (4|5)\\)")) == true) {
                            // Broker rejected previously saved access. Reauthenticate on the next bounded retry.
                            session = null; credentials = null
                        }
                        if (failure?.contains(Regex("MQTT code 128\\)")) == true) {
                            emit(GridAvailability.UNKNOWN, failure, check = latest?.check?.copy(cycleState = PowerSourceCheck.CycleState.FAILED, finishedAtEpochMs = System.currentTimeMillis()))
                            return@launch
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { lastFailure = "EcoFlow check unavailable. Connection closed; waiting before retrying." }
                val after = store.powerOceanAssistedSettings()
                val afterIncident = incident()
                val afterSeconds = if (after.enabled) { if (afterIncident) after.outageSeconds else after.normalSeconds } else account.refreshSeconds.coerceAtLeast(60)
                sampling.finishCheck(android.os.SystemClock.elapsedRealtime(), schedule.copy(intervalSeconds = afterSeconds.takeIf { it > 0 }, incident = afterIncident,
                    paused = store.powerOceanAssistancePaused()))
                if (lastFailure != null) {
                    retryAt = android.os.SystemClock.elapsedRealtime() + backoff
                    backoff = (backoff * 2).coerceAtMost(30 * 60_000L)
                } else { retryAt = 0; backoff = 60_000 }
                val now = System.currentTimeMillis()
                val next = sampling.nextDueAt?.let { now + (maxOf(it, retryAt) - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0) }
                val completed = latest
                if (lastFailure != null || completed?.check?.gridEvidenceAvailable != true) {
                    lastConfirmedOnlineAt = null; lastVerified = null
                }
                val completedCheck = completed?.check?.copy(
                    cycleState = if (lastFailure == null) PowerSourceCheck.CycleState.WAITING else PowerSourceCheck.CycleState.FAILED,
                    finishedAtEpochMs = now, nextCheckAtEpochMs = next)
                lastVerified = lastVerified?.copy(check = completedCheck)
                emit(if (lastFailure == null && completed?.check?.gridEvidenceAvailable == true) completed.availability else GridAvailability.UNKNOWN,
                    lastFailure ?: "EcoFlow check complete. Connection closed.", completed?.recoveryPending ?: false,
                    completed?.evidenceReceivedAtEpochMs, completed?.dataPossiblyStalled,
                    completedCheck)
                delay(1000)
            }
        }
    }

    @Synchronized
    override fun stop() { scope?.cancel(); scope = null; worker = null }
}
