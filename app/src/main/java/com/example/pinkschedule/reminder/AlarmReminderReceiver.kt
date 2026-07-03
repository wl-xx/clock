package com.example.pinkschedule.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.pinkschedule.data.ScheduleRepository
import java.time.LocalDateTime

class AlarmReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!shouldDeliver(context, intent)) {
            return
        }
        // 先拿 startup wakelock 保证 CPU 不再回到休眠，再用 goAsync 让广播在
        // 前台服务真正启动完成前保持存活——Doze 唤醒窗口很短，否则系统可能在
        // startForegroundService 生效前回收进程，导致息屏时闹钟“没响”。
        WakeLockManager.acquireStartup(context)
        logTrigger(intent)
        val pendingResult = goAsync()
        try {
            val serviceIntent = Intent(context, AlarmRingingService::class.java).apply {
                if (intent != null) {
                    putExtras(intent)
                }
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (t: Throwable) {
            Log.e(TAG, "failed to start ringing service", t)
            WakeLockManager.releaseStartup()
        } finally {
            pendingResult.finish()
        }
    }

    private fun shouldDeliver(context: Context, intent: Intent?): Boolean {
        val isDebug = intent?.getBooleanExtra(EXTRA_IS_DEBUG, false) == true
        val signature = intent?.getStringExtra(EXTRA_SIGNATURE)
        if (isDebug || signature.isNullOrBlank()) {
            return true
        }
        if (isAlreadyInLesson(intent)) {
            ScheduleRepository.markAlarmDelivered(context, signature)
            Log.i(TAG, "drop late alarm because lesson already started signature=$signature")
            return false
        }
        if (ScheduleRepository.loadDeliveredAlarmSignatures(context).contains(signature)) {
            Log.i(TAG, "drop delivered alarm signature=$signature")
            return false
        }
        synchronized(inFlightSignatures) {
            if (inFlightSignatures.contains(signature)) {
                Log.i(TAG, "drop duplicate in-flight alarm signature=$signature")
                return false
            }
            inFlightSignatures += signature
        }
        return true
    }

    private fun isAlreadyInLesson(intent: Intent?): Boolean {
        val lessonStart = runCatching {
            intent?.getStringExtra(EXTRA_LESSON_START)?.let(LocalDateTime::parse)
        }.getOrNull() ?: return false
        return !LocalDateTime.now().isBefore(lessonStart)
    }

    private fun logTrigger(intent: Intent?) {
        val scheduledAt = intent?.getLongExtra(EXTRA_TRIGGER_EPOCH_MILLIS, 0L) ?: 0L
        val drift = if (scheduledAt > 0L) {
            System.currentTimeMillis() - scheduledAt
        } else {
            null
        }
        Log.i(
            TAG,
            "alarm broadcast action=${intent?.action} source=${intent?.getStringExtra(EXTRA_SOURCE)} driftMs=$drift"
        )
    }

    companion object {
        private const val TAG = "AlarmReminderReceiver"

        const val ALARM_CHANNEL_ID = "lesson_alarm_v5"
        const val VIBRATION_CHANNEL_ID = "lesson_course_notice_v2"
        const val EXTRA_CLASS_NAME = "extra_class_name"
        const val EXTRA_TIME_RANGE = "extra_time_range"
        const val EXTRA_PERIOD = "extra_period"
        const val EXTRA_TRIGGER_AT = "extra_trigger_at"
        const val EXTRA_TRIGGER_EPOCH_MILLIS = "extra_trigger_epoch_millis"
        const val EXTRA_LESSON_START = "extra_lesson_start"
        const val EXTRA_IS_DEBUG = "extra_is_debug"
        const val EXTRA_SIGNATURE = "extra_signature"
        const val EXTRA_SOURCE = "extra_source"
        const val NOTIFICATION_ID = 1001
        const val ACTION_FIRE_ALARM = "com.example.pinkschedule.reminder.action.FIRE_ALARM"
        const val ACTION_FIRE_ALARM_BACKUP = "com.example.pinkschedule.reminder.action.FIRE_ALARM_BACKUP"

        private val inFlightSignatures = mutableSetOf<String>()
    }
}
