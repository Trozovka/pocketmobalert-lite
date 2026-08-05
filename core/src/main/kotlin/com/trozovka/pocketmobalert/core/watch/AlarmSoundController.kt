package com.trozovka.pocketmobalert.core.watch

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Max-volume tone + continuous vibration for as long as any alarm (direct or relayed from
 * another Watch device) is active on this device. Uses STREAM_ALARM specifically, not
 * STREAM_NOTIFICATION/RING -- the alarm stream is the one Android does not let silent/DND modes
 * mute, which matters for a safety alarm.
 */
class AlarmSoundController(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private var toneGenerator: ToneGenerator? = null

    fun start() {
        if (loopJob?.isActive == true) return

        val audioManager = context.getSystemService(AudioManager::class.java)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)
        toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME)

        val vibrator = context.getSystemService(Vibrator::class.java)
        val pattern = longArrayOf(0, 800, 300, 800, 300)
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))

        loopJob = scope.launch {
            while (isActive) {
                toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, TONE_DURATION_MILLIS)
                delay(TONE_REPEAT_INTERVAL_MILLIS)
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        toneGenerator?.release()
        toneGenerator = null
        context.getSystemService(Vibrator::class.java)?.cancel()
    }

    val isSounding: Boolean
        get() = loopJob?.isActive == true

    companion object {
        private const val TONE_DURATION_MILLIS = 1500
        private const val TONE_REPEAT_INTERVAL_MILLIS = 2000L
    }
}
