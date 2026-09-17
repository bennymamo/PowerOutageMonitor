package com.flossypickle.poweroutagemonitor.integrations.power.ecoflow

/** Explains activation prerequisites without treating cached readings as a live test. */
internal object PowerOceanActivationPolicy {
    const val LIVE_TEST_FRESH_MS = 90_000L
    enum class Stage { ACCOUNT_REQUIRED, UNSUPPORTED_MODEL, PROFILE_REQUIRED, LIVE_TEST_REQUIRED, READY }

    fun evaluate(accountConfigured: Boolean, supportedModel: Boolean, profileVerified: Boolean,
        lastLiveTestAt: Long, now: Long, previousTestAccepted: Boolean = false): Stage = when {
        !accountConfigured -> Stage.ACCOUNT_REQUIRED
        !supportedModel -> Stage.UNSUPPORTED_MODEL
        !profileVerified -> Stage.PROFILE_REQUIRED
        previousTestAccepted -> Stage.READY
        lastLiveTestAt <= 0 || now - lastLiveTestAt !in 0..LIVE_TEST_FRESH_MS -> Stage.LIVE_TEST_REQUIRED
        else -> Stage.READY
    }
}
