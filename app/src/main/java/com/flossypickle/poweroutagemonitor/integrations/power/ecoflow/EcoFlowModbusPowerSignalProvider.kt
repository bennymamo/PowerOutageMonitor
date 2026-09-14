package com.flossypickle.poweroutagemonitor.integrations.power.ecoflow

import com.flossypickle.poweroutagemonitor.integrations.power.GridAvailability
import com.flossypickle.poweroutagemonitor.integrations.power.PowerSignal
import com.flossypickle.poweroutagemonitor.integrations.power.PowerSignalProvider
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

internal class EcoFlowModbusPowerSignalProvider(
    private val host: String,
    private val port: Int = EcoFlowModbusProtocol.DEFAULT_PORT,
    private val unitId: Int = EcoFlowModbusProtocol.DEFAULT_UNIT_ID,
    private val client: EcoFlowModbusClient = EcoFlowModbusClient(),
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS
) : PowerSignalProvider {
    override val id = EcoFlowGridSignalMapper.PROVIDER_ID
    override val displayName = "EcoFlow PowerOcean"
    private var executor: ScheduledExecutorService? = null
    private var callback: ((PowerSignal) -> Unit)? = null

    @Synchronized
    override fun start(onSignal: (PowerSignal) -> Unit) {
        if (executor != null) return
        callback = onSignal
        executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "ecoflow-modbus-reader").apply { isDaemon = true }
        }.also { service ->
            service.scheduleWithFixedDelay(::poll, 0, pollIntervalMs, TimeUnit.MILLISECONDS)
        }
    }

    @Synchronized
    fun refresh() {
        executor?.execute(::poll)
    }

    @Synchronized
    override fun stop() {
        callback = null
        executor?.shutdownNow()
        executor = null
    }

    private fun poll() {
        val observedAt = System.currentTimeMillis()
        val signal = runCatching {
            EcoFlowGridSignalMapper.toSignal(
                EcoFlowGridSignalMapper.decode(client.readGridRegisters(host, port, unitId)),
                observedAt
            )
        }.getOrElse { error ->
            PowerSignal(
                availability = GridAvailability.UNKNOWN,
                observedAtEpochMs = observedAt,
                providerId = id,
                detail = error.message?.take(120) ?: error.javaClass.simpleName
            )
        }
        callback?.invoke(signal)
    }

    companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 5_000L
    }
}
