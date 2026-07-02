package com.example.pinkschedule.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.pm.ServiceInfo
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.pinkschedule.R
import com.example.pinkschedule.data.ScheduleRepository
import java.time.LocalDateTime

class AlarmRingingService : Service() {
    private var wakeLock: android.os.PowerManager.WakeLock? = null

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

            Log.d(TAG, "start alarm service class=$className time=$timeRange debug=$isDebug")

            ensureChannel()

            // 非锁屏时用它直接弹出对话框式提醒界面（像系统闹钟）；锁屏时不弹，只发通知。
            val alertIntent = Intent(this, AlarmAlertActivity::class.java).apply {
                putExtra(AlarmReminderReceiver.EXTRA_CLASS_NAME, className)
                putExtra(AlarmReminderReceiver.EXTRA_TIME_RANGE, timeRange)
                putExtra(AlarmReminderReceiver.EXTRA_PERIOD, period)
                putExtra(AlarmReminderReceiver.EXTRA_SIGNATURE, signature)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NO_USER_ACTION
                )
            }
            // 锁屏通知展示：班级 · 第N节 · 时间（VISIBILITY_PUBLIC 保证锁屏完整显示）。
            val periodText = if (period > 0) "第${period}节" else null
            val detailText = listOfNotNull(className, periodText, timeRange).joinToString(" · ")

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

            // 铃声与震动统一由 AlarmPlaybackManager 负责，通知本身不再触发第二路音源。
            val notification = NotificationCompat.Builder(this, AlarmReminderReceiver.CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
                .setContentTitle("课程提醒")
                .setContentText(detailText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(detailText))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setLights(Color.TRANSPARENT, 0, 0)
                .setContentIntent(stopKeepPendingIntent)
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(false)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis())
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "关闭闹钟",
                    stopKeepPendingIntent
                )
                .build()

            startAsForeground(notification)
            if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                NotificationManagerCompat.from(this).notify(AlarmReminderReceiver.NOTIFICATION_ID, notification)
            }
            AlarmPlaybackManager.start(this)
            // 息屏/锁屏时依赖全屏 Intent 由系统拉起 Activity（后台 startActivity 会被拦截）；
            // 仅当屏幕已亮且已解锁时直接拉起，避免多此一举被系统静默丢弃。
            if (isInteractiveAndUnlocked()) {
                runCatching {
                    ContextCompat.startActivity(this, alertIntent, null)
                }
            }
            markDelivered(signature, isDebug)
            replenishAlarms(intent, isDebug)
        } catch (t: Throwable) {
            Log.e(TAG, "alarm service failed", t)
            stopSelf()
            throw t
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        AlarmPlaybackManager.stop()
        WakeLockManager.releaseStartup()
        WakeLockManager.release(wakeLock)
        wakeLock = null
        super.onDestroy()
    }

    private fun isInteractiveAndUnlocked(): Boolean {
        val powerManager = getSystemService(POWER_SERVICE) as android.os.PowerManager
        val keyguardManager = getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager
        return powerManager.isInteractive && !keyguardManager.isKeyguardLocked
    }

    private fun stopAlarmAndSelf() {
        AlarmPlaybackManager.stop()
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
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(AlarmReminderReceiver.CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    AlarmReminderReceiver.CHANNEL_ID,
                    "课程提醒",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "课程与调试闹钟提醒"
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    // 铃声与震动统一由 AlarmPlaybackManager 播放，渠道自身不再触发音源/震动，避免叠加。
                    enableVibration(false)
                    setSound(null, null)
                }
            )
        }
    }

    /** 停止响铃后，用一条静态（无声、低优先级、可手动划掉）通知保留课程信息。 */
    private fun postStaticInfoNotification(className: String, detailText: String) {
        ensureStaticChannel()
        val notification = NotificationCompat.Builder(this, STATIC_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            .setContentTitle("课程提醒（已关闭）")
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
        val triggerAtRaw = intent?.getStringExtra(AlarmReminderReceiver.EXTRA_TRIGGER_AT)
        val now = runCatching {
            triggerAtRaw?.let(LocalDateTime::parse)?.plusSeconds(1)
        }.getOrNull() ?: LocalDateTime.now()
        SystemAlarmScheduler.rescheduleStoredCourseAlarms(this, now)
    }

    private fun markDelivered(signature: String?, isDebug: Boolean) {
        if (isDebug || signature.isNullOrBlank()) return
        ScheduleRepository.markAlarmDelivered(this, signature)
    }

    companion object {
        private const val TAG = "AlarmRingingService"
        private const val STOP_REQUEST_CODE = 2001
        private const val STATIC_CHANNEL_ID = "lesson_alarm_static_v1"
        const val EXTRA_DETAIL_TEXT = "extra_detail_text"
        const val ACTION_STOP_ALARM = "com.example.pinkschedule.reminder.action.STOP_ALARM"
        const val ACTION_STOP_KEEP_INFO = "com.example.pinkschedule.reminder.action.STOP_KEEP_INFO"
    }
}
