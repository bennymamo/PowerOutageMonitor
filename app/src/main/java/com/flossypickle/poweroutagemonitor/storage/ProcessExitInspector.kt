package com.flossypickle.poweroutagemonitor.storage

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

/** Adds Android's own process-exit evidence to an otherwise unexplained restart. */
internal class ProcessExitInspector(private val context: Context) {
    fun recentExit(nowEpochMs: Long, previousStartEpochMs: Long): ProcessExitInsight? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        return runCatching {
            manager.getHistoricalProcessExitReasons(null, 0, 10)
                .firstOrNull { exit ->
                    exit.processName == context.packageName &&
                        ProcessExitWindow.includes(
                            exit.timestamp,
                            nowEpochMs,
                            previousStartEpochMs
                        )
                }
                ?.let { ProcessExitClassifier.classify(it.reason, it.description) }
        }.getOrNull()
    }
}

/** An older process death cannot explain a newer service or dashboard session. */
internal object ProcessExitWindow {
    private const val MAX_EXIT_AGE_MS = 120_000L

    fun includes(exitEpochMs: Long, nowEpochMs: Long, previousStartEpochMs: Long): Boolean =
        exitEpochMs > previousStartEpochMs &&
            nowEpochMs - exitEpochMs in 0L..MAX_EXIT_AGE_MS
}

internal data class ProcessExitInsight(val appUpdated: Boolean, val explanation: String)

/** Older Android versions also use USER_REQUESTED for updates, so require install evidence. */
internal object ProcessExitClassifier {
    fun classify(reason: Int, description: String?): ProcessExitInsight? = when {
        reason == ApplicationExitInfo.REASON_PACKAGE_UPDATED ->
            ProcessExitInsight(true, "Android reports that the app was updated.")
        reason == ApplicationExitInfo.REASON_USER_REQUESTED &&
            description?.contains("installPackageLI", ignoreCase = true) == true ->
            ProcessExitInsight(true, "Android reports that the app was updated.")
        reason == ApplicationExitInfo.REASON_CRASH ->
            ProcessExitInsight(false, "Android reports that the previous app process crashed.")
        reason == ApplicationExitInfo.REASON_CRASH_NATIVE ->
            ProcessExitInsight(false, "Android reports that the previous app process crashed in native code.")
        reason == ApplicationExitInfo.REASON_ANR ->
            ProcessExitInsight(false, "Android reports that the previous app process stopped responding.")
        reason == ApplicationExitInfo.REASON_LOW_MEMORY ->
            ProcessExitInsight(false, "Android reports that the device ran low on memory.")
        reason == ApplicationExitInfo.REASON_USER_REQUESTED ->
            ProcessExitInsight(false, "Android reports that the previous app process was stopped by a user or system request.")
        else -> null
    }
}
