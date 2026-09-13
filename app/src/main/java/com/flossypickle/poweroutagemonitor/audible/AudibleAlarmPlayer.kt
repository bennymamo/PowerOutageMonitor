package com.flossypickle.poweroutagemonitor.audible

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Plays the selected sound, falling back to a built-in beep, then restores alarm volume. */
internal class AudibleAlarmPlayer(private val context: Context) {
    fun play(useMaximumVolume: Boolean, soundUri: String?) {
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
                waitForRingtone(ringtone, restoreVolume)
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
        Handler(Looper.getMainLooper()).postDelayed({
            tone.release()
            onFinished()
        }, TONE_DURATION_MS + 200L)
    }

    private fun waitForRingtone(ringtone: Ringtone, onFinished: () -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        var checksRemaining = MAX_RINGTONE_DURATION_MS / RINGTONE_CHECK_INTERVAL_MS
        lateinit var check: Runnable
        check = Runnable {
            checksRemaining--
            if (ringtone.isPlaying && checksRemaining > 0) {
                handler.postDelayed(check, RINGTONE_CHECK_INTERVAL_MS)
            } else {
                if (ringtone.isPlaying) ringtone.stop()
                onFinished()
            }
        }
        handler.postDelayed(check, RINGTONE_CHECK_INTERVAL_MS)
    }

    private companion object {
        const val TONE_DURATION_MS = 1_200
        const val RINGTONE_CHECK_INTERVAL_MS = 250L
        const val MAX_RINGTONE_DURATION_MS = 30_000L
        const val TAG = "AudibleAlarmPlayer"
    }
}
