package com.flossypickle.poweroutagemonitor.integrations.alerts.sms

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertMessage
import com.flossypickle.poweroutagemonitor.integrations.alerts.DeliveryResult
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Sends through the device radio and waits for Android's per-part sent result. */
internal class SmsClient(private val context: Context) {
    @Suppress("DEPRECATION")
    fun send(recipient: String, message: AlertMessage): DeliveryResult {
        if (!SmsProtocol.isValidNumber(recipient)) {
            return DeliveryResult.PermanentFailure("SMS recipient is not valid")
        }
        val capability = SmsCapability.capture(context)
        if (!capability.supported) {
            return DeliveryResult.PermanentFailure("This device does not support SMS messaging")
        }
        if (!capability.permissionGranted || ContextCompat.checkSelfPermission(
                context, Manifest.permission.SEND_SMS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return DeliveryResult.PermanentFailure("SMS permission is not allowed")
        }
        val subscriptionId = SmsManager.getDefaultSmsSubscriptionId()
        if (subscriptionId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            return DeliveryResult.PermanentFailure("Choose a default SMS SIM in Android settings")
        }
        val manager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java).createForSubscriptionId(subscriptionId)
        } else {
            SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        }
        val text = SmsProtocol.messageText(message)
        val parts = manager.divideMessage(text).takeIf(List<String>::isNotEmpty) ?: listOf(text)
        val action = "${context.packageName}.SMS_SENT.${UUID.randomUUID()}"
        val latch = CountDownLatch(parts.size)
        val results = java.util.Collections.synchronizedList(mutableListOf<DeliveryResult>())
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiveContext: Context?, intent: Intent?) {
                results += SmsProtocol.resultForPart(
                    resultCode,
                    intent?.getBooleanExtra(EXTRA_NO_DEFAULT, false) == true
                )
                latch.countDown()
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(action),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        return try {
            val sentIntents = ArrayList<PendingIntent>(parts.size)
            parts.indices.forEach { index ->
                val intent = Intent(action).setPackage(context.packageName)
                sentIntents += PendingIntent.getBroadcast(
                    context,
                    action.hashCode() + index,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            if (parts.size == 1) {
                manager.sendTextMessage(recipient, null, parts.first(), sentIntents.first(), null)
            } else {
                manager.sendMultipartTextMessage(
                    recipient,
                    null,
                    ArrayList(parts),
                    sentIntents,
                    null
                )
            }
            if (!latch.await(SENT_RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                DeliveryResult.RetryableFailure("Android did not report the SMS result")
            } else {
                SmsProtocol.combinedResult(results.toList())
            }
        } catch (_: SecurityException) {
            DeliveryResult.PermanentFailure("SMS permission is not allowed")
        } catch (_: UnsupportedOperationException) {
            DeliveryResult.PermanentFailure("This device does not support SMS messaging")
        } catch (_: Exception) {
            DeliveryResult.RetryableFailure("Android could not start SMS delivery")
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private companion object {
        const val EXTRA_NO_DEFAULT = "noDefault"
        const val SENT_RESULT_TIMEOUT_SECONDS = 45L
    }
}
