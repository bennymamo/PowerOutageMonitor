package com.flossypickle.poweroutagemonitor.audible

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Plays one short alarm tone and restores the user's alarm volume afterward. */
internal class AudibleAlarmPlayer(private val context: Context) {
    fun play(useMaximumVolume: Boolean) {
        runCatching {
            val audio = context.getSystemService(AudioManager::class.java)
            val originalVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            if (useMaximumVolume) {
                audio.setStreamVolume(
                    AudioManager.STREAM_ALARM,
                    audio.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                    0
                )
            }
            val tone = ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, TONE_DURATION_MS)
            Handler(Looper.getMainLooper()).postDelayed({
                tone.release()
                if (useMaximumVolume) {
                    runCatching {
                        audio.setStreamVolume(AudioManager.STREAM_ALARM, originalVolume, 0)
                    }.onFailure { Log.w(TAG, "Unable to restore alarm volume", it) }
                }
            }, TONE_DURATION_MS + 200L)
        }.onFailure { Log.e(TAG, "Unable to play audible outage alarm", it) }
    }

    private companion object {
        const val TONE_DURATION_MS = 1_200
        const val TAG = "AudibleAlarmPlayer"
    }
}
