package com.flossypickle.poweroutagemonitor.audible

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Owns the one active alarm sound so every stop path can silence it immediately. */
internal class AudibleAlarmPlayer(private val context: Context) {
    fun play(
        useMaximumVolume: Boolean,
        soundUri: String?,
        maximumDurationMs: Long = ALARM_SOUND_DURATION_MS
    ) {
        require(maximumDurationMs > 0)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            playOnMainThread(useMaximumVolume, soundUri, maximumDurationMs)
        } else {
            mainHandler.post { playOnMainThread(useMaximumVolume, soundUri, maximumDurationMs) }
        }
    }

    fun stop() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            stopOnMainThread()
        } else {
            mainHandler.post(::stopOnMainThread)
        }
    }

    private fun playOnMainThread(
        useMaximumVolume: Boolean,
        soundUri: String?,
        maximumDurationMs: Long
    ) {
        stopOnMainThread()
        val audio = context.getSystemService(AudioManager::class.java)
        val originalVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        val restoreVolume = {
            if (useMaximumVolume) {
                runCatching {
                    audio.setStreamVolume(AudioManager.STREAM_ALARM, originalVolume, 0)
                }.onFailure { Log.w(TAG, "Unable to restore alarm volume", it) }
            }
        }
        runCatching {
            if (useMaximumVolume) {
                audio.setStreamVolume(
                    AudioManager.STREAM_ALARM,
                    audio.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                    0
                )
            }
            val ringtone = soundUri?.let { selected ->
                runCatching {
                    RingtoneManager.getRingtone(context, Uri.parse(selected))?.apply {
                        audioAttributes = AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                        play()
                    }
                }.onFailure {
                    Log.w(TAG, "Selected alarm sound is unavailable; using built-in beep", it)
                }.getOrNull()
            }
            if (ringtone != null) {
                beginPlayback(
                    stopSound = { if (ringtone.isPlaying) ringtone.stop() },
                    restoreVolume = restoreVolume,
                    maximumDurationMs = maximumDurationMs,
                    isStillPlaying = ringtone::isPlaying
                )
            } else {
                playBuiltInBeep(restoreVolume)
            }
        }.onFailure {
            restoreVolume()
            Log.e(TAG, "Unable to play audible outage alarm", it)
        }
    }

    private fun playBuiltInBeep(onFinished: () -> Unit) {
        val tone = ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME)
        tone.startTone(ToneGenerator.TONE_PROP_BEEP2, TONE_DURATION_MS)
        beginPlayback(
            stopSound = {
                tone.stopTone()
                tone.release()
            },
            restoreVolume = onFinished,
            maximumDurationMs = TONE_DURATION_MS + 200L
        )
    }

    private fun beginPlayback(
        stopSound: () -> Unit,
        restoreVolume: () -> Unit,
        maximumDurationMs: Long,
        isStillPlaying: (() -> Boolean)? = null
    ) {
        lateinit var stopAtLimit: Runnable
        stopAtLimit = Runnable {
            if (activePlayback?.stopAtLimit === stopAtLimit) stopOnMainThread()
        }
        lateinit var checkFinished: Runnable
        checkFinished = Runnable {
            if (activePlayback?.checkFinished !== checkFinished) return@Runnable
            if (runCatching { isStillPlaying?.invoke() == true }.getOrDefault(false)) {
                mainHandler.postDelayed(checkFinished, PLAYBACK_CHECK_INTERVAL_MS)
            } else {
                stopOnMainThread()
            }
        }
        activePlayback = ActivePlayback(
            stopSound,
            restoreVolume,
            stopAtLimit,
            checkFinished.takeIf { isStillPlaying != null }
        )
        mainHandler.postDelayed(stopAtLimit, maximumDurationMs)
        if (isStillPlaying != null) {
            mainHandler.postDelayed(checkFinished, PLAYBACK_CHECK_INTERVAL_MS)
        }
    }

    private fun stopOnMainThread() {
        val playback = activePlayback ?: return
        activePlayback = null
        mainHandler.removeCallbacks(playback.stopAtLimit)
        playback.checkFinished?.let(mainHandler::removeCallbacks)
        runCatching(playback.stopSound)
            .onFailure { Log.w(TAG, "Unable to stop audible outage alarm", it) }
        playback.restoreVolume()
    }

    private data class ActivePlayback(
        val stopSound: () -> Unit,
        val restoreVolume: () -> Unit,
        val stopAtLimit: Runnable,
        val checkFinished: Runnable?
    )

    companion object {
        const val PREVIEW_DURATION_MS = 5_000L
        private const val ALARM_SOUND_DURATION_MS = 30_000L
        const val TONE_DURATION_MS = 1_200
        private const val PLAYBACK_CHECK_INTERVAL_MS = 250L
        private const val TAG = "AudibleAlarmPlayer"
        private val mainHandler = Handler(Looper.getMainLooper())
        private var activePlayback: ActivePlayback? = null
    }
}
