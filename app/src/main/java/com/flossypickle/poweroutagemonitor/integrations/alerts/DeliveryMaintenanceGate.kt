package com.flossypickle.poweroutagemonitor.integrations.alerts

/** Serializes restore with queue mutations. Network sends hold a reservation, not this lock. */
internal object DeliveryMaintenanceGate {
    val lock = Any()
    private var activeSends = 0

    fun reserveSend() = synchronized(lock) { activeSends++ }
    fun finishSend() = synchronized(lock) { activeSends-- }

    fun requireIdle() = synchronized(lock) {
        check(activeSends == 0) {
            "An alert is still being sent. Wait for delivery to finish, then retry restore. No backup data was changed."
        }
    }
}
