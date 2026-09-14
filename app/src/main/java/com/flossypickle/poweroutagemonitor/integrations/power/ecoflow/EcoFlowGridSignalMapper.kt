package com.flossypickle.poweroutagemonitor.integrations.power.ecoflow

import com.flossypickle.poweroutagemonitor.integrations.power.GridAvailability
import com.flossypickle.poweroutagemonitor.integrations.power.PowerSignal
import java.util.Locale

internal data class EcoFlowGridReading(
    val systemModes: Long,
    val gridVoltageL1: Float,
    val frequencyHz: Float
) {
    val islanded: Boolean get() = systemModes and 1L != 0L
}

/** Converts PowerOcean registers into conservative grid evidence. */
internal object EcoFlowGridSignalMapper {
    const val PROVIDER_ID = "ecoflow_modbus"
    private const val GRID_PRESENT_MINIMUM_VOLTS = 100f
    private const val GRID_ABSENT_MAXIMUM_VOLTS = 50f

    fun decode(registers: IntArray): EcoFlowGridReading {
        require(registers.size == EcoFlowModbusProtocol.GRID_BLOCK_REGISTER_COUNT) {
            "Unexpected EcoFlow register block"
        }
        fun word(address: Int) = registers[address - EcoFlowModbusProtocol.GRID_BLOCK_START]
        return EcoFlowGridReading(
            systemModes = EcoFlowModbusProtocol.decodeLowWordFirstUInt32(
                word(SYSTEM_MODES_ADDRESS), word(SYSTEM_MODES_ADDRESS + 1)
            ),
            gridVoltageL1 = EcoFlowModbusProtocol.decodeLowWordFirstFloat(
                word(GRID_VOLTAGE_L1_ADDRESS), word(GRID_VOLTAGE_L1_ADDRESS + 1)
            ),
            frequencyHz = EcoFlowModbusProtocol.decodeLowWordFirstFloat(
                word(FREQUENCY_ADDRESS), word(FREQUENCY_ADDRESS + 1)
            )
        )
    }

    fun toSignal(reading: EcoFlowGridReading, observedAtEpochMs: Long): PowerSignal {
        val voltage = reading.gridVoltageL1
        val frequency = reading.frequencyHz
        val valuesValid = voltage.isFinite() && voltage in 0f..300f &&
            frequency.isFinite() && frequency in 0f..70f
        val availability = when {
            !valuesValid -> GridAvailability.UNKNOWN
            reading.islanded && voltage <= GRID_ABSENT_MAXIMUM_VOLTS -> GridAvailability.UNAVAILABLE
            !reading.islanded && voltage >= GRID_PRESENT_MINIMUM_VOLTS -> GridAvailability.AVAILABLE
            else -> GridAvailability.UNKNOWN
        }
        val mode = if (reading.islanded) "islanded" else "grid-connected"
        return PowerSignal(
            availability = availability,
            observedAtEpochMs = observedAtEpochMs,
            providerId = PROVIDER_ID,
            detail = "$mode · ${format(voltage)} V · ${format(frequency)} Hz"
        )
    }

    private fun format(value: Float) = if (value.isFinite()) {
        String.format(Locale.ROOT, "%.1f", value)
    } else "invalid"

    private const val SYSTEM_MODES_ADDRESS = 40_530
    private const val GRID_VOLTAGE_L1_ADDRESS = 40_580
    private const val FREQUENCY_ADDRESS = 40_594
}
