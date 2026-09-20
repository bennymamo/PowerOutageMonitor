package com.flossypickle.poweroutagemonitor.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import java.util.WeakHashMap

/** Multiple revealed fields must not clear each other's screen-capture protection. */
private val secureWindows = WeakHashMap<Window, Pair<Int, Boolean>>()

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.takeUnless { it === this }?.activity()
    else -> null
}

@Composable
internal fun SecureScreen(enabled: Boolean) {
    val window = LocalContext.current.activity()?.window
    DisposableEffect(window, enabled) {
        if (enabled && window != null) {
            val previous = secureWindows[window] ?: (0 to
                (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0))
            secureWindows[window] = previous.first + 1 to previous.second
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            if (enabled && window != null) {
                secureWindows[window]?.let { (count, originallySecure) ->
                    if (count > 1) secureWindows[window] = count - 1 to originallySecure
                    else {
                        secureWindows.remove(window)
                        if (!originallySecure) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }
            }
        }
    }
}
