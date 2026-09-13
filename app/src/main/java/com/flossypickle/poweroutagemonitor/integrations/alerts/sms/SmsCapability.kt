package com.flossypickle.poweroutagemonitor.integrations.alerts.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

internal data class SmsCapability(
    val supported: Boolean,
    val permissionGranted: Boolean,
    val hasDefaultSubscription: Boolean
) {
    companion object {
        @Suppress("DEPRECATION")
        fun capture(context: Context): SmsCapability {
            val supported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)
            } else {
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) &&
                    context.getSystemService(TelephonyManager::class.java).isSmsCapable
            }
            val permission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.SEND_SMS
            ) == PackageManager.PERMISSION_GRANTED
            val subscription = runCatching { SmsManager.getDefaultSmsSubscriptionId() }
                .getOrDefault(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
            return SmsCapability(
                supported = supported,
                permissionGranted = permission,
                hasDefaultSubscription = subscription != SubscriptionManager.INVALID_SUBSCRIPTION_ID
            )
        }
    }
}
