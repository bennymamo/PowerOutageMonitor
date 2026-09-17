package com.flossypickle.poweroutagemonitor.integrations.power

/** An auxiliary charger can corroborate loss; it never vetoes restoration or replaces missing grid data. */
internal object ChargerConfirmationPolicy {
    fun apply(primary: PowerSignal, chargerPowered: Boolean?, required: Boolean): PowerSignal =
        if (required && primary.availability == GridAvailability.UNAVAILABLE && chargerPowered != false) {
            primary.copy(availability = GridAvailability.UNKNOWN,
                detail = "Grid source reports loss; waiting for charger-loss confirmation.")
        } else primary
}
