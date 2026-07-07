package com.example.pinkschedule.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.pm.ServiceInfo
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.example.pinkschedule.MainActivity
import com.example.pinkschedule.R
import com.example.pinkschedule.data.ScheduleRepository
import com.example.pinkschedule.model.ScheduleDefaults
import java.time.LocalDateTime

class AlarmRingingService : Service() {
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var alarmPlaybackStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALARM) {
            WakeLockManager.releaseStartup()
            stopAlarmAndSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP_KEEP_INFO) {
            // 点击锁屏通知：停止响铃，但用一条静态（无声、可划掉）通知保留课程信息。
            WakeLockManager.releaseStartup()
            AlarmPlaybackManager.stop()
            alarmPlaybackStarted = false
            postStaticInfoNotification(
                className = intent.getStringExtra(AlarmReminderReceiver.EXTRA_CLASS_NAME) ?: "课程提醒",
                detailText = intent.getStringExtra(EXTRA_DETAIL_TEXT) ?: ""
            )
            // 前台服务撤下但不移除通知（静态通知已用同 ID 覆盖）。
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
            return START_NOT_STICKY
        }

        if (wakeLock?.isHeld != true) {
            wakeLock = WakeLockManager.acquire(this)
        }

        try {
            WakeLockManager.releaseStartup()
            val className = intent?.getStringExtra(AlarmReminderReceiver.EXTRA_CLASS_NAME)?.takeIf { it.isNotBlank() }
                ?: "课程提醒"
            val timeRange = intent?.getStringExtra(AlarmReminderReceiver.EXTRA_TIME_RANGE)?.takeIf { it.isNotBlank() }
                ?: "课程时间未知"
            val period = intent?.getIntExtra(AlarmReminderReceiver.EXTRA_PERIOD, 0) ?: 0
            val isDebug = intent?.getBooleanExtra(AlarmReminderReceiver.EXTRA_IS_DEBUG, false) == true
            val signature = intent?.getStringExtra(AlarmReminderReceiver.EXTRA_SIGNATURE)
            val lessonStart = runCatching {
                intent?.getStringExtra(AlarmReminderReceiver.EXTRA_LESSON_START)?.let(LocalDateTime::parse)
            }.getOrNull()

            Log.d(TAG, "start alarm service class=$className time=$timeRange debug=$isDebug")

            if (!isDebug && lessonStart != null && !LocalDateTime.now().isBefore(lessonStart)) {
                Log.i(TAG, "skip ringing because lesson already started signature=$signature lessonStart=$lessonStart")
                markDelivered(signature, isDebug)
                replenishAlarms(intent, isDebug)
                stopSelf()
                return START_NOT_STICKY
            }

            val settings = ScheduleRepository.loadReminderSettings(this)
            if (!isDebug && !settings.hasEnabledReminder()) {
                Log.i(TAG, "skip ringing because reminders are disabled signature=$signature")
                stopSelf()
                return START_NOT_STICKY
            }

            ensureChannel()

            // 锁屏通知展示：班级 · 第N节 · 时间（VISIBILITY_PUBLIC 保证锁屏完整显示）。
            val periodText = if (period >= 0) ScheduleDefaults.periodLabel(period) else null
            val detailText = listOfNotNull(className, periodText, timeRange).joinToString(" · ")
            val shouldPlayAlarm = isDebug || settings.alarmModeEnabled
            val shouldVibrate = !isDebug && settings.vibrationReminderEnabled
            val shouldPlayPromptSound = !isDebug && !settings.alarmModeEnabled && settings.soundReminderEnabled
            val channelId = if (shouldPlayAlarm) {
                AlarmReminderReceiver.ALARM_CHANNEL_ID
            } else {
                AlarmReminderReceiver.VIBRATION_CHANNEL_ID
            }

            // 点击通知 = 停止响铃但保留信息（换成静态通知），走 ACTION_STOP_KEEP_INFO。
            val stopKeepIntent = Intent(this, AlarmRingingService::class.java).apply {
                action = ACTION_STOP_KEEP_INFO
                putExtra(AlarmReminderReceiver.EXTRA_CLASS_NAME, className)
                putExtra(EXTRA_DETAIL_TEXT, detailText)
            }
            val stopKeepPendingIntent = PendingIntent.getService(
                this,
                STOP_REQUEST_CODE,
                stopKeepIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val openAppPendingIntent = PendingIntent.getActivity(
                this,
                OPEN_APP_REQUEST_CODE,
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // 通过高优先级通知渠道争取横幅/锁屏展示，实际长响铃由 AlarmPlaybackManager 负责。
            val notificationBuilder = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("上课提醒")
                .setContentText(detailText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(detailText))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setLights(Color.TRANSPARENT, 0, 0)
                .setContentIntent(if (shouldPlayAlarm) stopKeepPendingIntent else openAppPendingIntent)
                .setOngoing(shouldPlayAlarm)
                .setAutoCancel(!shouldPlayAlarm)
                .setOnlyAlertOnce(false)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis())
            if (shouldPlayAlarm) {
                notificationBuilder.addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "关闭闹钟",
                    stopKeepPendingIntent
                )
            }
            val notification = notificationBuilder.build()

            startAsForeground(notification)
            if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                NotificationManagerCompat.from(this).notify(AlarmReminderReceiver.NOTIFICATION_ID, notification)
            }
            if (shouldPlayAlarm) {
                alarmPlaybackStarted = true
                AlarmPlaybackManager.start(
                    context = this,
                    vibrate = isDebug || settings.vibrationReminderEnabled,
                    volumePercent = settings.reminderVolumePercent
                )
            } else if (shouldVibrate) {
                AlarmPlaybackManager.vibrateReminder(this)
            }
            if (shouldPlayPromptSound) {
                AlarmPlaybackManager.playReminderTone(
                    context = this,
                    toneId = settings.soundReminderToneId,
                    volumePercent = settings.reminderVolumePercent
                )
            }
            markDelivered(signature, isDebug)
            replenishAlarms(intent, isDebug)
            if (!shouldPlayAlarm) {
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
                return START_NOT_STICKY
            }
        } catch (t: Throwable) {
            Log.e(TAG, "alarm service failed", t)
            stopSelf()
            throw t
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        AlarmPlaybackManager.stop(includePromptTones = alarmPlaybackStarted)
        alarmPlaybackStarted = false
        WakeLockManager.releaseStartup()
        WakeLockManager.release(wakeLock)
        wakeLock = null
        super.onDestroy()
    }

    private fun stopAlarmAndSelf() {
        AlarmPlaybackManager.stop()
        alarmPlaybackStarted = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startAsForeground(notification: android.app.Notification) {
        val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            AlarmReminderReceiver.NOTIFICATION_ID,
            notification,
            foregroundServiceType
        )
    }

    private fun ensureChannel() {
        SystemAlarmScheduler.ensureAlarmNotificationChannel(this)
    }

    /** 停止响铃后，用一条静态（无声、低优先级、可手动划掉）通知保留课程信息。 */
    private fun postStaticInfoNotification(className: String, detailText: String) {
        ensureStaticChannel()
        val notification = NotificationCompat.Builder(this, STATIC_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("上课提醒")
            .setContentText(detailText.ifBlank { className })
            .setStyle(NotificationCompat.BigTextStyle().bigText(detailText.ifBlank { className }))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            NotificationManagerCompat.from(this).notify(AlarmReminderReceiver.NOTIFICATION_ID, notification)
        }
    }

    private fun ensureStaticChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(STATIC_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    STATIC_CHANNEL_ID,
                    "课程提醒记录",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "已关闭的课程提醒信息记录"
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    enableVibration(false)
                    setSound(null, null)
                }
            )
        }
    }

    private fun replenishAlarms(intent: Intent?, isDebug: Boolean) {
        if (isDebug) return
        ReminderCoordinator.onReminderFired(this, LocalDateTime.now())
    }

    private fun markDelivered(signature: String?, isDebug: Boolean) {
        if (isDebug || signature.isNullOrBlank()) return
        ScheduleRepository.markAlarmDelivered(this, signature)
    }

    companion object {
        private const val TAG = "AlarmRingingService"
        private const val STOP_REQUEST_CODE = 2001
        private const val OPEN_APP_REQUEST_CODE = 2002
        private const val STATIC_CHANNEL_ID = "lesson_alarm_static_v1"
        const val EXTRA_DETAIL_TEXT = "extra_detail_text"
        const val ACTION_STOP_ALARM = "com.example.pinkschedule.reminder.action.STOP_ALARM"
        const val ACTION_STOP_KEEP_INFO = "com.example.pinkschedule.reminder.action.STOP_KEEP_INFO"
    }
}
