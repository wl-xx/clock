package com.example.pinkschedule.reminder

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.pinkschedule.domain.CourseScheduleCalculator
import com.example.pinkschedule.domain.PlannedReminder
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * AlarmManager 操作层：只负责把 Coordinator 算好的提醒列表下发/取消，不做时间推演。
 *
 * 每个课程提醒双路冗余下发（ColorOS 等激进 ROM 上单一 API 不可靠）：
 * - 主闹钟 setAlarmClock（最高优先级、免疫 Doze）
 * - 备份 setExactAndAllowWhileIdle（requestCode + 20000）
 * 另有独立的心跳边界闹钟（requestCode 3001），驱动前台通知的状态切换与到点兜底。
 */
object SystemAlarmScheduler {
    private const val TAG = "SystemAlarmScheduler"

    private const val REQUEST_CODE_BASE = 1001
    private const val SHOW_REQUEST_CODE_OFFSET = 10_000
    private const val BACKUP_REQUEST_CODE_OFFSET = 20_000
    private const val HEARTBEAT_REQUEST_CODE = 3001

    /** 一次性预排未来多节课程提醒，每节独立 requestCode，任一失败不影响其余。 */
    fun scheduleReminders(
        context: Context,
        reminders: List<PlannedReminder>
    ): ReminderCoordinator.SyncResult {
        cancelAllScheduledAlarms(context)
        var scheduledCount = 0
        var firstFailure: String? = null
        reminders.forEachIndexed { index, reminder ->
            val attempt = scheduleAlarm(context, reminder, alarmRequestCode(index))
            if (attempt.scheduled) {
                scheduledCount++
            } else if (firstFailure == null) {
                firstFailure = attempt.messageSuffix
            }
        }
        return if (scheduledCount == 0) {
            ReminderCoordinator.SyncResult(false, "设置闹钟失败。${firstFailure.orEmpty()}")
        } else {
            ReminderCoordinator.SyncResult(true, "提醒已同步。")
        }
    }

    fun cancelAllScheduledAlarms(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        repeat(CourseScheduleCalculator.MAX_SCHEDULED_ALARMS) { index ->
            val intent = Intent(context, AlarmReminderReceiver::class.java).apply {
                action = AlarmReminderReceiver.ACTION_FIRE_ALARM
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                alarmRequestCode(index),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()

            val backupIntent = Intent(context, AlarmReminderReceiver::class.java).apply {
                action = AlarmReminderReceiver.ACTION_FIRE_ALARM_BACKUP
            }
            val backupPendingIntent = PendingIntent.getBroadcast(
                context,
                alarmRequestCode(index) + BACKUP_REQUEST_CODE_OFFSET,
                backupIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(backupPendingIntent)
            backupPendingIntent.cancel()

            // 同步撤销 setAlarmClock 使用的 show PendingIntent。
            val showIntent = buildAppLaunchIntent(context)
            val showPendingIntent = PendingIntent.getActivity(
                context,
                alarmRequestCode(index) + SHOW_REQUEST_CODE_OFFSET,
                showIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(showPendingIntent)
            showPendingIntent.cancel()
        }
    }

    /**
     * 排状态边界心跳闹钟。全局只有一个（固定 requestCode），FLAG_UPDATE_CURRENT
     * 幂等覆盖——任意时刻重复调用都安全，这是断链自愈的前提。
     * 不用 setAlarmClock：心跳（含午夜刷新）不应出现在系统闹钟 UI 中。
     */
    fun scheduleHeartbeat(context: Context, triggerAt: LocalDateTime) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerMillis = triggerAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pendingIntent = heartbeatPendingIntent(context)
        runCatching {
            if (canUseExactAlarms(alarmManager)) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
            } else {
                // 无精确闹钟权限时退化为不精确心跳：状态切换可能延迟，
                // 但课程提醒主链路 setAlarmClock 不受影响。
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
            }
        }.onFailure {
            Log.e(TAG, "schedule heartbeat failed", it)
        }
    }

    fun cancelHeartbeat(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = heartbeatPendingIntent(context)
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    /** 心跳兜底路径拉起响铃服务（与 AlarmManager 广播路径的 extras 一致）。 */
    fun startRingingService(context: Context, reminder: PlannedReminder, source: String) {
        val intent = Intent(context, AlarmRingingService::class.java).apply {
            putExtra(AlarmReminderReceiver.EXTRA_CLASS_NAME, reminder.course.className)
            putExtra(AlarmReminderReceiver.EXTRA_TIME_RANGE, reminder.slot.displayRange())
            putExtra(AlarmReminderReceiver.EXTRA_PERIOD, reminder.course.period)
            putExtra(AlarmReminderReceiver.EXTRA_SIGNATURE, reminder.deliverySignature())
            putExtra(AlarmReminderReceiver.EXTRA_TRIGGER_AT, LocalDateTime.now().toString())
            putExtra(AlarmReminderReceiver.EXTRA_LESSON_START, reminder.lessonStart.toString())
            putExtra(AlarmReminderReceiver.EXTRA_IS_DEBUG, false)
            putExtra(AlarmReminderReceiver.EXTRA_SOURCE, source)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun canUseExactAlarms(context: Context): Boolean {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return canUseExactAlarms(alarmManager)
    }

    fun canPostNotifications(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun ensureAlarmNotificationChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val alarmAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        if (manager.getNotificationChannel(AlarmReminderReceiver.ALARM_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    AlarmReminderReceiver.ALARM_CHANNEL_ID,
                    "闹钟提醒",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "闹钟提醒，请开启锁屏通知和横幅通知。"
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    enableVibration(false)
                    setSound(alarmSound, alarmAttributes)
                }
            )
        }
        if (manager.getNotificationChannel(AlarmReminderReceiver.VIBRATION_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    AlarmReminderReceiver.VIBRATION_CHANNEL_ID,
                    "上课提醒",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "到点时显示上课提醒通知，震动和提示音由应用设置控制。"
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    enableVibration(false)
                    setSound(null, null)
                }
            )
        }
    }

    private fun heartbeatPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, AlarmReminderReceiver::class.java).apply {
            action = AlarmReminderReceiver.ACTION_STATE_TRANSITION
        }
        return PendingIntent.getBroadcast(
            context,
            HEARTBEAT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun scheduleAlarm(
        context: Context,
        reminder: PlannedReminder,
        requestCode: Int
    ): ScheduleAttempt {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val showIntent = buildAppLaunchIntent(context)
        val showPendingIntent = PendingIntent.getActivity(
            context,
            requestCode + SHOW_REQUEST_CODE_OFFSET,
            showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerMillis = reminder.triggerAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val intent = buildAlarmIntent(
            context = context,
            reminder = reminder,
            action = AlarmReminderReceiver.ACTION_FIRE_ALARM,
            triggerMillis = triggerMillis,
            source = "alarm_clock"
        )
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return runCatching {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerMillis, showPendingIntent),
                pendingIntent
            )
            if (canUseExactAlarms(alarmManager)) {
                scheduleBackupExactAlarm(
                    context = context,
                    alarmManager = alarmManager,
                    reminder = reminder,
                    requestCode = requestCode + BACKUP_REQUEST_CODE_OFFSET,
                    triggerMillis = triggerMillis
                )
            }
            Log.i(TAG, "scheduled alarm class=${reminder.course.className} triggerMillis=$triggerMillis")
            ScheduleAttempt(true, "使用系统闹钟唤醒。")
        }.getOrElse {
            ScheduleAttempt(false, "设置提醒失败：${it.message ?: "未知错误"}")
        }
    }

    private fun buildAlarmIntent(
        context: Context,
        reminder: PlannedReminder,
        action: String,
        triggerMillis: Long,
        source: String
    ): Intent {
        return Intent(context, AlarmReminderReceiver::class.java).apply {
            this.action = action
            putExtra(AlarmReminderReceiver.EXTRA_CLASS_NAME, reminder.course.className)
            putExtra(AlarmReminderReceiver.EXTRA_TIME_RANGE, reminder.slot.displayRange())
            putExtra(AlarmReminderReceiver.EXTRA_PERIOD, reminder.course.period)
            putExtra(AlarmReminderReceiver.EXTRA_TRIGGER_AT, reminder.triggerAt.toString())
            putExtra(AlarmReminderReceiver.EXTRA_TRIGGER_EPOCH_MILLIS, triggerMillis)
            putExtra(AlarmReminderReceiver.EXTRA_LESSON_START, reminder.lessonStart.toString())
            putExtra(AlarmReminderReceiver.EXTRA_IS_DEBUG, false)
            putExtra(AlarmReminderReceiver.EXTRA_SIGNATURE, reminder.deliverySignature())
            putExtra(AlarmReminderReceiver.EXTRA_SOURCE, source)
        }
    }

    private fun scheduleBackupExactAlarm(
        context: Context,
        alarmManager: AlarmManager,
        reminder: PlannedReminder,
        requestCode: Int,
        triggerMillis: Long
    ) {
        val backupIntent = buildAlarmIntent(
            context = context,
            reminder = reminder,
            action = AlarmReminderReceiver.ACTION_FIRE_ALARM_BACKUP,
            triggerMillis = triggerMillis,
            source = "exact_backup"
        )
        val backupPendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            backupIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerMillis,
            backupPendingIntent
        )
    }

    private fun canUseExactAlarms(alarmManager: AlarmManager): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    private fun buildAppLaunchIntent(context: Context): Intent {
        return (context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }

    private data class ScheduleAttempt(
        val scheduled: Boolean,
        val messageSuffix: String
    )

    private fun alarmRequestCode(index: Int): Int = REQUEST_CODE_BASE + index
}
