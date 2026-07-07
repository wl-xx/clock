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
import com.example.pinkschedule.R
import com.example.pinkschedule.model.ReminderSettings
import com.example.pinkschedule.model.ReminderTone

object AlarmPlaybackManager {
    private const val TAG = "AlarmPlaybackManager"

    private var mediaPlayer: MediaPlayer? = null
    private var fallbackRingtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private val promptTonePlayers = mutableSetOf<MediaPlayer>()

    fun start(
        context: Context,
        vibrate: Boolean,
        volumePercent: Int = ReminderSettings.DEFAULT_VOLUME_PERCENT
    ) {
        stop()
        val audioVolume = normalizedVolume(volumePercent)
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        if (uri != null) {
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
                    setVolume(audioVolume, audioVolume)
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
                            volume = audioVolume
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
        }

        if (vibrate) {
            vibrator = getVibrator(context)
            vibrate(vibrator, repeat = true)
        }
    }

    fun vibrateReminder(context: Context) {
        vibrate(getVibrator(context), repeat = false)
    }

    fun previewReminderTone(
        context: Context,
        toneId: String,
        volumePercent: Int = ReminderSettings.DEFAULT_VOLUME_PERCENT
    ) {
        playReminderTone(context = context, toneId = toneId, volumePercent = volumePercent, preview = true)
    }

    fun playReminderTone(
        context: Context,
        toneId: String,
        volumePercent: Int = ReminderSettings.DEFAULT_VOLUME_PERCENT
    ) {
        playReminderTone(context = context, toneId = toneId, volumePercent = volumePercent, preview = false)
    }

    private fun playReminderTone(
        context: Context,
        toneId: String,
        volumePercent: Int,
        preview: Boolean
    ) {
        val appContext = context.applicationContext
        val resId = reminderToneResId(toneId)
        val audioVolume = normalizedVolume(volumePercent)
        val usage = if (preview) {
            AudioAttributes.USAGE_MEDIA
        } else {
            AudioAttributes.USAGE_ALARM
        }
        val contentType = if (preview) {
            AudioAttributes.CONTENT_TYPE_MUSIC
        } else {
            AudioAttributes.CONTENT_TYPE_SONIFICATION
        }
        runCatching {
            appContext.resources.openRawResourceFd(resId).use { descriptor ->
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(usage)
                            .setContentType(contentType)
                            .build()
                    )
                    if (!preview) {
                        setWakeMode(appContext, PowerManager.PARTIAL_WAKE_LOCK)
                    }
                    setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
                    setVolume(audioVolume, audioVolume)
                    setOnCompletionListener { player ->
                        promptTonePlayers.remove(player)
                        runCatching { player.release() }
                    }
                    setOnErrorListener { player, what, extra ->
                        Log.e(TAG, "reminder tone error what=$what extra=$extra")
                        promptTonePlayers.remove(player)
                        runCatching { player.release() }
                        true
                    }
                    prepare()
                    promptTonePlayers += this
                    start()
                }
            }
        }.onFailure {
            Log.e(TAG, "failed to play reminder tone", it)
        }
    }

    private fun reminderToneResId(toneId: String): Int {
        return when (ReminderTone.resolve(toneId).id) {
            "happy_bells" -> R.raw.reminder_happy_bells
            "bell" -> R.raw.reminder_bell
            else -> R.raw.reminder_ding_dong
        }
    }

    private fun normalizedVolume(volumePercent: Int): Float {
        return volumePercent.coerceIn(0, 100) / 100f
    }

    private fun getVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    private fun vibrate(vibrator: Vibrator?, repeat: Boolean) {
        vibrator?.let { vib ->
            if (vib.hasVibrator()) {
                val pattern = longArrayOf(0, 450, 160, 450)
                val repeatIndex = if (repeat) 0 else -1
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib.vibrate(VibrationEffect.createWaveform(pattern, repeatIndex))
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(pattern, repeatIndex)
                }
            }
        }
    }

    fun stop(includePromptTones: Boolean = true) {
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
        if (includePromptTones) {
            promptTonePlayers.toList().forEach { player ->
                runCatching { player.stop() }
                runCatching { player.release() }
            }
            promptTonePlayers.clear()
        }
    }
}
