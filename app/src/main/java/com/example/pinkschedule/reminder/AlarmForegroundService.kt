package com.example.pinkschedule.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.pm.ServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.pinkschedule.MainActivity
import com.example.pinkschedule.R
import com.example.pinkschedule.data.ScheduleRepository
import com.example.pinkschedule.model.CourseItem
import com.example.pinkschedule.model.LessonTimeSlot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters

/**
 * 常驻前台服务：像滴答清单那样，用一个不休眠的前台服务 + 低优先级常驻通知，
 * 自己每隔一小段时间检查“最近的课程提醒时刻是否已到”，到点直接拉起响铃流程。
 *
 * 为什么需要它：ColorOS 等激进省电 ROM 会压制/清理 AlarmManager 闹钟，导致息屏时不触发。
 * 前台服务受系统保护、进程常驻不休眠，是国产 ROM 上息屏提醒最可靠的一层。
 * 它与 setAlarmClock + WorkManager 看门狗叠加，形成多层冗余——任一层失效仍有兜底。
 *
 * 注意：本服务始终负责展示今日课程；仅在课程提醒开启时检测并触发到点提醒。
 * 去重复用 ScheduleRepository 的 delivered 签名，避免与 AlarmManager 路径重复响铃。
 */
class AlarmForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickerJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastForegroundNotificationKey: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel()
        startAsForeground()
        val remindersEnabled = ScheduleRepository.loadReminderSettings(applicationContext).hasEnabledReminder()
        if (remindersEnabled && wakeLock?.isHeld != true) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        } else if (!remindersEnabled && wakeLock?.isHeld == true) {
            runCatching { wakeLock?.release() }
            wakeLock = null
        }
        if (tickerJob?.isActive != true) {
            tickerJob = scope.launch { runTicker() }
        }
        // START_STICKY：进程被杀后系统尽量重启服务，保持常驻。
        return START_STICKY
    }

    private suspend fun runTicker() {
        while (scope.isActive) {
            runCatching {
                refreshForegroundNotification()
                checkAndFire()
            }
                .onFailure { Log.e(TAG, "ticker check failed", it) }
            delay(CHECK_INTERVAL_MS)
        }
    }

    /** 检查最近一节课的提醒时刻是否已到；到点则触发响铃并标记已投递。 */
    private fun checkAndFire() {
        val settings = ScheduleRepository.loadReminderSettings(applicationContext)
        if (!settings.hasEnabledReminder()) {
            return
        }
        val items = ScheduleRepository.load(applicationContext)
        val lessonTimes = ScheduleRepository.loadLessonTimes(applicationContext)
        if (items.isEmpty() || lessonTimes.isEmpty()) return

        val now = LocalDateTime.now()
        val delivered = ScheduleRepository.loadDeliveredAlarmSignatures(applicationContext)
        val timeIndex = lessonTimes.associateBy { it.period }

        // 找出“已到提醒时刻、但尚未上课、且还没投递过”的最近一节课。
        data class Due(val item: CourseItem, val slot: LessonTimeSlot, val lessonStart: LocalDateTime)

        val due = items.mapNotNull { item ->
            val slot = timeIndex[item.period] ?: return@mapNotNull null
            val lessonStart = thisOrNextOccurrence(now, item, slot)
            val triggerAt = lessonStart.minusMinutes(settings.reminderMinutesBefore.toLong())
            // 触发窗口：提醒时刻已到，但课程尚未开始；已在课中则不再补响。
            if (now.isBefore(triggerAt) || !now.isBefore(lessonStart)) return@mapNotNull null
            val signature = deliverySignatureOf(item, lessonStart)
            if (delivered.contains(signature)) return@mapNotNull null
            Due(item, slot, lessonStart)
        }.minByOrNull { it.lessonStart } ?: return

        val signature = deliverySignatureOf(due.item, due.lessonStart)
        Log.i(TAG, "foreground ticker firing class=${due.item.className} lessonStart=${due.lessonStart}")

        val fireIntent = Intent(applicationContext, AlarmRingingService::class.java).apply {
            putExtra(AlarmReminderReceiver.EXTRA_CLASS_NAME, due.item.className)
            putExtra(AlarmReminderReceiver.EXTRA_TIME_RANGE, due.slot.displayRange())
            putExtra(AlarmReminderReceiver.EXTRA_PERIOD, due.item.period)
            putExtra(AlarmReminderReceiver.EXTRA_SIGNATURE, signature)
            putExtra(AlarmReminderReceiver.EXTRA_TRIGGER_AT, now.toString())
            putExtra(AlarmReminderReceiver.EXTRA_LESSON_START, due.lessonStart.toString())
            putExtra(AlarmReminderReceiver.EXTRA_IS_DEBUG, false)
            putExtra(AlarmReminderReceiver.EXTRA_SOURCE, "foreground_ticker")
        }
        // 标记已投递，避免 AlarmManager 路径或下一轮 tick 重复响铃。
        ScheduleRepository.markAlarmDelivered(applicationContext, signature)
        androidx.core.content.ContextCompat.startForegroundService(applicationContext, fireIntent)
    }

    private fun thisOrNextOccurrence(
        now: LocalDateTime,
        item: CourseItem,
        slot: LessonTimeSlot
    ): LocalDateTime {
        val date = now.toLocalDate().with(TemporalAdjusters.nextOrSame(item.dayOfWeek))
        val candidate = LocalDateTime.of(date, slot.startTime)
        // 若本周这节课的结束时间已过，看下周同一节。
        val lessonEnd = LocalDateTime.of(date, slot.endTime)
        return if (now.isAfter(lessonEnd)) {
            LocalDateTime.of(date.plusWeeks(1), slot.startTime)
        } else {
            candidate
        }
    }

    private fun deliverySignatureOf(item: CourseItem, lessonStart: LocalDateTime): String {
        return listOf(
            item.dayOfWeek.name,
            item.period.toString(),
            lessonStart.toLocalDate().toString(),
            item.className
        ).joinToString("|")
    }

    private fun startAsForeground() {
        val snapshot = todayCourseSnapshot()
        val notification = buildForegroundNotification(snapshot)
        lastForegroundNotificationKey = snapshot.stableKey
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun refreshForegroundNotification() {
        val snapshot = todayCourseSnapshot()
        if (snapshot.stableKey == lastForegroundNotificationKey) {
            return
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildForegroundNotification(snapshot))
        lastForegroundNotificationKey = snapshot.stableKey
    }

    private fun buildForegroundNotification(
        snapshot: TodayCourseSummary.ForegroundNotificationSnapshot
    ): android.app.Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("今日课程")
            .setContentText(fallbackContentText(snapshot))
            .setStyle(NotificationCompat.BigTextStyle().bigText(snapshot.text))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .setCustomContentView(buildForegroundRemoteViews(snapshot))
            .setCustomBigContentView(buildForegroundRemoteViews(snapshot))
            .setContentIntent(contentIntent)
            .build()
    }

    private fun buildForegroundRemoteViews(
        snapshot: TodayCourseSummary.ForegroundNotificationSnapshot
    ): RemoteViews {
        val views = RemoteViews(packageName, R.layout.notification_today_course)
        views.setTextViewText(R.id.notification_title, "今日课程")
        views.setTextViewText(R.id.notification_status, snapshot.title)
        views.setTextViewText(R.id.notification_detail, snapshot.text)
        val countdownTarget = snapshot.countdownTargetEpochMillis
        if (countdownTarget == null) {
            views.setViewVisibility(R.id.notification_countdown, View.GONE)
        } else {
            val base = SystemClock.elapsedRealtime() + (countdownTarget - System.currentTimeMillis())
            views.setViewVisibility(R.id.notification_countdown, View.VISIBLE)
            views.setChronometer(R.id.notification_countdown, base, null, true)
            views.setChronometerCountDown(R.id.notification_countdown, true)
        }
        return views
    }

    private fun fallbackContentText(
        snapshot: TodayCourseSummary.ForegroundNotificationSnapshot
    ): String {
        return if (snapshot.countdownTargetEpochMillis == null) {
            "${snapshot.title} · ${snapshot.text}"
        } else {
            "距离上课 · ${snapshot.text}"
        }
    }

    private fun todayCourseSnapshot(): TodayCourseSummary.ForegroundNotificationSnapshot {
        return runCatching {
            TodayCourseSummary.foregroundNotificationSnapshot(
                items = ScheduleRepository.load(applicationContext),
                lessonTimes = ScheduleRepository.loadLessonTimes(applicationContext),
                now = LocalDateTime.now()
            )
        }.getOrDefault(
            TodayCourseSummary.ForegroundNotificationSnapshot(
                title = "今日课程",
                text = "暂无未结束课程",
                stableKey = "error",
                countdownTargetEpochMillis = null
            )
        )
    }

    private fun ensureChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "今日课程",
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    description = "显示今天最近一节尚未结束的课程。"
                    setShowBadge(false)
                }
            )
        }
    }

    override fun onDestroy() {
        tickerJob?.cancel()
        scope.cancel()
        if (wakeLock?.isHeld == true) {
            runCatching { wakeLock?.release() }
        }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AlarmForegroundService"
        private const val CHANNEL_ID = "today_course_foreground_v1"
        private const val NOTIFICATION_ID = 2100
        private const val WAKELOCK_TAG = "PinkSchedule:ForegroundGuardian"
        private const val CHECK_INTERVAL_MS = 1_000L
        const val ACTION_STOP = "com.example.pinkschedule.reminder.action.STOP_GUARDIAN"

        fun start(context: Context) {
            val intent = Intent(context, AlarmForegroundService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, AlarmForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            // 用 startService 送 stop action，让服务自行 stopSelf（已在前台时可安全停止）。
            runCatching { context.startService(intent) }
        }
    }
}
