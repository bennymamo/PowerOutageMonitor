package com.flossypickle.poweroutagemonitor

import com.flossypickle.poweroutagemonitor.integrations.alerts.DeliveryMaintenanceGate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class DeliveryMaintenanceGateTest {
    @Test fun `restore refuses mutation until every active send has finished`() {
        DeliveryMaintenanceGate.reserveSend()
        DeliveryMaintenanceGate.reserveSend()
        try {
            var mutated = false
            assertThrows(IllegalStateException::class.java) {
                synchronized(DeliveryMaintenanceGate.lock) {
                    DeliveryMaintenanceGate.requireIdle()
                    mutated = true
                }
            }
            assertFalse(mutated)
            DeliveryMaintenanceGate.finishSend()
            assertThrows(IllegalStateException::class.java) { DeliveryMaintenanceGate.requireIdle() }
        } finally { DeliveryMaintenanceGate.finishSend() }
        DeliveryMaintenanceGate.requireIdle()
    }

    @Test fun `a worker cannot reserve a send while restore owns the mutation lock`() {
        val started = CountDownLatch(1)
        val reserved = CountDownLatch(1)
        val worker: Thread
        synchronized(DeliveryMaintenanceGate.lock) {
            DeliveryMaintenanceGate.requireIdle()
            worker = Thread {
                started.countDown()
                DeliveryMaintenanceGate.reserveSend()
                try { reserved.countDown() } finally { DeliveryMaintenanceGate.finishSend() }
            }
            worker.start()
            assertTrue(started.await(1, TimeUnit.SECONDS))
            assertFalse(reserved.await(50, TimeUnit.MILLISECONDS))
        }
        assertTrue(reserved.await(1, TimeUnit.SECONDS))
        worker.join(1000)
        assertFalse(worker.isAlive)
    }
}
