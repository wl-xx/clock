package com.example.pinkschedule.reminder

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

object AlarmPlaybackManager {
    private const val TAG = "AlarmPlaybackManager"

    private var mediaPlayer: MediaPlayer? = null
    private var fallbackRingtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    fun start(context: Context) {
        stop()
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return

        mediaPlayer = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setWakeMode(context.applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
                setDataSource(context.applicationContext, uri)
                isLooping = true
                setOnErrorListener { player, what, extra ->
                    Log.e(TAG, "media player error what=$what extra=$extra")
                    runCatching {
                        player.reset()
                        player.release()
                    }
                    if (mediaPlayer === player) {
                        mediaPlayer = null
                    }
                    true
                }
                prepare()
                start()
            }
        }.onFailure {
            Log.e(TAG, "failed to start alarm playback", it)
        }.getOrNull()

        if (mediaPlayer == null) {
            runCatching {
                fallbackRingtone = RingtoneManager.getRingtone(context.applicationContext, uri)?.apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        isLooping = true
                    }
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    play()
                }
            }.onFailure {
                Log.e(TAG, "failed to start fallback ringtone", it)
            }
        }

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator?.let { vib ->
            if (vib.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 300, 600), 0))
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(longArrayOf(0, 600, 300, 600), 0)
                }
            }
        }
    }

    fun stop() {
        runCatching {
            mediaPlayer?.stop()
        }
        runCatching {
            mediaPlayer?.release()
        }
        mediaPlayer = null
        fallbackRingtone?.stop()
        fallbackRingtone = null
        vibrator?.cancel()
        vibrator = null
    }
}
