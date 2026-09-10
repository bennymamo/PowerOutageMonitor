package com.flossypickle.poweroutagemonitor.diagnostics

import android.Manifest
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

internal data class SystemHealthSnapshot(
    val internetAvailable: Boolean = false,
    val notificationsAllowed: Boolean = true,
    val backgroundRestricted: Boolean = false,
    val batteryOptimizationExcluded: Boolean = false
) {
    fun monitoringAttention(monitoringEnabled: Boolean): String? = when {
        !monitoringEnabled -> null
        backgroundRestricted ->
            "Android is restricting background activity. Open Settings › Diagnostics to fix monitoring reliability."
        !notificationsAllowed ->
            "Monitoring is running, but Android is hiding its ongoing notification. Open Settings › Diagnostics to allow it."
        else -> null
    }

    companion object {
        fun capture(context: Context): SystemHealthSnapshot {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            val notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            return SystemHealthSnapshot(
                internetAvailable = capabilities?.let {
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                } == true,
                notificationsAllowed = notificationsGranted &&
                    NotificationManagerCompat.from(context).areNotificationsEnabled(),
                backgroundRestricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    context.getSystemService(ActivityManager::class.java).isBackgroundRestricted,
                batteryOptimizationExcluded = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                    context.getSystemService(PowerManager::class.java)
                        .isIgnoringBatteryOptimizations(context.packageName)
            )
        }
    }
}

/** Keeps the visible app's system-health reading current without polling. */
internal class SystemHealthMonitor(
    context: Context,
    private val onChanged: (SystemHealthSnapshot) -> Unit
) {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var registered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = dispatch()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = dispatch()
        override fun onLost(network: Network) = dispatch()
    }

    private val legacyConnectivityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = dispatch()
    }

    fun start() {
        if (registered) return
        registered = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connectivity.registerDefaultNetworkCallback(networkCallback)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(
                legacyConnectivityReceiver,
                IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
            )
        }
        refresh()
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivity.unregisterNetworkCallback(networkCallback)
            } else {
                appContext.unregisterReceiver(legacyConnectivityReceiver)
            }
        }
        mainHandler.removeCallbacksAndMessages(null)
    }

    fun refresh() = dispatch()

    private fun dispatch() {
        mainHandler.post {
            if (registered) onChanged(SystemHealthSnapshot.capture(appContext))
        }
    }
}
