package com.example.pinkschedule.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.pm.ServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.pinkschedule.MainActivity
import com.example.pinkschedule.R
import com.example.pinkschedule.domain.ScheduleSnapshot
import java.time.Duration
import java.time.LocalDateTime

/**
 * 常驻前台服务：展示"今日课程"通知（倒计时 / 正在上课 / 暂无课程）。
 *
 * 事件驱动而非轮询：秒级倒计时由系统 Chronometer 控件自走（零耗电、不受进程冻结影响），
 * 服务只在状态边界被唤醒时重算并刷新通知——边界由 ReminderCoordinator 的心跳闹钟
 * （提醒点/上课/下课/午夜，最长 6 小时自续）精确驱动，亮屏广播做即时校正。
 * 无 ticker、无 wakelock：状态是课表的纯函数，START_STICKY 重建后重算即恢复。
 *
 * 它同时是提醒的第三层保活冗余：作为前台服务提高进程存活率，让心跳到点时
 * ReminderCoordinator.onHeartbeat 的兜底触发（checkAndFire 的替代者）能够执行。
 */
class AlarmForegroundService : Service() {
    private var lastForegroundNotificationKey: String? = null
    private var screenOnReceiver: BroadcastReceiver? = null
    private val handler = Handler(Looper.getMainLooper())

    // 进程内边界回调：进程存活期间在 nextTransitionAt 精确触发一次
    //（消除心跳闹钟投递抖动带来的负数倒计时窗口，并在前台兜底触发到点提醒）。
    // 进程被冻结时该回调随之挂起，无副作用——此时通知不可见，心跳闹钟仍是兜底。
    private val boundaryRunnable = Runnable {
        runCatching { ReminderCoordinator.onHeartbeat(applicationContext, source = "local") }
            .onFailure { Log.e(TAG, "local boundary callback failed", it) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 亮屏即校正：用户只有亮屏才看得到通知，此广播只在亮屏时到达，零耗电。
        screenOnReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                runCatching {
                    refreshForegroundNotification()
                    ReminderCoordinator.ensureHeartbeat(context)
                }.onFailure { Log.e(TAG, "screen-on refresh failed", it) }
            }
        }
        registerReceiver(screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // 统一恢复协议：无论首次启动、ACTION_REFRESH 还是 STICKY 重建（intent == null），
        // 都是"重算快照 → 渲染 → 续排心跳"，无状态残留。
        ensureChannel()
        startAsForeground()
        ReminderCoordinator.ensureHeartbeat(applicationContext)
        return START_STICKY
    }

    private fun startAsForeground() {
        val domain = domainSnapshotOrNull()
        val snapshot = notificationSnapshot(domain)
        val notification = buildForegroundNotification(snapshot)
        lastForegroundNotificationKey = snapshot.stableKey
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        armLocalBoundaryCallback(domain)
    }

    private fun refreshForegroundNotification() {
        val domain = domainSnapshotOrNull()
        val snapshot = notificationSnapshot(domain)
        armLocalBoundaryCallback(domain)
        if (snapshot.stableKey == lastForegroundNotificationKey) {
            return
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildForegroundNotification(snapshot))
        lastForegroundNotificationKey = snapshot.stableKey
    }

    private fun armLocalBoundaryCallback(domain: ScheduleSnapshot?) {
        handler.removeCallbacks(boundaryRunnable)
        val nextTransitionAt = domain?.nextTransitionAt ?: return
        val delayMs = Duration.between(LocalDateTime.now(), nextTransitionAt).toMillis()
        // 太远的边界交给心跳闹钟；已过/过近的至少 500ms 防零延迟自旋。
        if (delayMs <= MAX_LOCAL_BOUNDARY_MS) {
            handler.postDelayed(boundaryRunnable, delayMs.coerceAtLeast(500L))
        }
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
        val contentTitle = if (snapshot.showCourseHeader) "今日课程" else snapshot.text
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(contentTitle)
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
        if (snapshot.showCourseHeader) {
            views.setViewVisibility(R.id.notification_title, View.VISIBLE)
            views.setViewVisibility(R.id.notification_countdown_row, View.VISIBLE)
            views.setTextViewText(R.id.notification_title, "今日课程")
            views.setTextViewText(R.id.notification_status, snapshot.title)
        } else {
            views.setViewVisibility(R.id.notification_title, View.GONE)
            views.setViewVisibility(R.id.notification_countdown_row, View.GONE)
        }
        views.setTextViewText(R.id.notification_detail, snapshot.text)
        val countdownTarget = snapshot.countdownTargetEpochMillis
        val remainingMs = countdownTarget?.let { it - System.currentTimeMillis() } ?: -1L
        if (countdownTarget == null || remainingMs <= 0L) {
            // 目标缺失或已到点：绝不渲染可能为负的倒计时。
            // 到点后的状态切换由 startAt 时刻的心跳闹钟精确驱动。
            views.setViewVisibility(R.id.notification_countdown, View.GONE)
        } else {
            views.setViewVisibility(R.id.notification_countdown, View.VISIBLE)
            views.setChronometer(
                R.id.notification_countdown,
                SystemClock.elapsedRealtime() + remainingMs,
                null,
                true
            )
            views.setChronometerCountDown(R.id.notification_countdown, true)
        }
        return views
    }

    private fun fallbackContentText(
        snapshot: TodayCourseSummary.ForegroundNotificationSnapshot
    ): String {
        return if (!snapshot.showCourseHeader) {
            snapshot.text
        } else if (snapshot.countdownTargetEpochMillis == null) {
            "${snapshot.title} · ${snapshot.text}"
        } else {
            "距离上课 · ${snapshot.text}"
        }
    }

    private fun domainSnapshotOrNull(): ScheduleSnapshot? {
        return runCatching {
            ReminderCoordinator.computeSnapshot(applicationContext, LocalDateTime.now())
        }.onFailure { Log.e(TAG, "compute snapshot failed", it) }.getOrNull()
    }

    private fun notificationSnapshot(
        domain: ScheduleSnapshot?
    ): TodayCourseSummary.ForegroundNotificationSnapshot {
        return domain?.let { runCatching { TodayCourseSummary.foregroundNotificationSnapshot(it) }.getOrNull() }
            ?: TodayCourseSummary.ForegroundNotificationSnapshot(
                title = "今日课程",
                text = "今天没有课程安排，好好休息吧~",
                stableKey = "error",
                countdownTargetEpochMillis = null,
                showCourseHeader = false
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
        handler.removeCallbacks(boundaryRunnable)
        screenOnReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenOnReceiver = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AlarmForegroundService"
        private const val CHANNEL_ID = "today_course_foreground_v1"
        private const val NOTIFICATION_ID = 2100
        // 进程内边界回调只处理 6 小时内的边界，更远的交给心跳闹钟链。
        private const val MAX_LOCAL_BOUNDARY_MS = 6 * 60 * 60 * 1000L
        const val ACTION_STOP = "com.example.pinkschedule.reminder.action.STOP_GUARDIAN"
        const val ACTION_REFRESH = "com.example.pinkschedule.reminder.action.REFRESH_GUARDIAN"

        fun start(context: Context) {
            val intent = Intent(context, AlarmForegroundService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        /** 心跳/系统事件到达时刷新通知；服务已死则借此复活。 */
        fun refresh(context: Context) {
            val intent = Intent(context, AlarmForegroundService::class.java).apply {
                action = ACTION_REFRESH
            }
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(context, intent)
            }.onFailure { Log.e(TAG, "refresh guardian failed", it) }
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
