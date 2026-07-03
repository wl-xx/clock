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
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.pinkschedule.data.ScheduleRepository
import com.example.pinkschedule.model.CourseItem
import com.example.pinkschedule.model.LessonTimeSlot
import com.example.pinkschedule.model.ReminderSettings
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

object SystemAlarmScheduler {
    private const val TAG = "SystemAlarmScheduler"
    private const val MAX_SCHEDULED_ALARMS = 12
    // 每节课向后枚举多少周的上课时刻。需 ≥ MAX_SCHEDULED_ALARMS，
    // 保证即使只有 1 节课也能凑满 12 个未来不同日期的闹钟。
    private const val WEEKS_LOOKAHEAD = 14
    private const val IMMEDIATE_TRIGGER_DELAY_SECONDS = 2L

    data class AlarmResult(
        val launched: Boolean,
        val message: String
    )

    fun syncCourseAlarms(
        context: Context,
        items: List<CourseItem>,
        lessonTimes: List<LessonTimeSlot>,
        settings: ReminderSettings,
        now: LocalDateTime = LocalDateTime.now()
    ): AlarmResult {
        val normalized = settings.normalized()
        if (!normalized.alarmModeEnabled) {
            cancelAllScheduledAlarms(context)
            AlarmWatchdogWorker.cancel(context)
            // 关闭课程闹钟只停止响铃调度；今日课程前台通知仍保持运行。
            AlarmForegroundService.start(context)
            ScheduleRepository.saveLastAlarmSignature(context, null)
            ScheduleRepository.clearDeliveredAlarmSignatures(context)
            return AlarmResult(false, "闹钟模式未开启。")
        }
        // 闹钟模式开启：启动常驻前台守护服务（不休眠，绕过 ColorOS 对 AlarmManager 的压制）。
        AlarmForegroundService.start(context)
        val reminders = buildUpcomingReminders(
            context = context,
            items = items,
            lessonTimes = lessonTimes,
            minutesBefore = normalized.reminderMinutesBefore,
            now = now
        )
        if (reminders.isEmpty()) {
            cancelAllScheduledAlarms(context)
            // 闹钟模式仍开启，只是暂无后续课程；保留看门狗，让它稍后重新评估。
            AlarmWatchdogWorker.enqueue(context)
            ScheduleRepository.saveLastAlarmSignature(context, null)
            ScheduleRepository.clearDeliveredAlarmSignatures(context)
            return AlarmResult(false, "暂无需要提醒的后续课程。")
        }

        // 一次性预排未来多节课程闹钟，每节使用独立 requestCode。
        // 这样任一次触发在息屏/Doze 下失败或延迟都不会影响其余闹钟，
        // 不再依赖“上一节响完再补排”的脆弱链条。
        cancelAllScheduledAlarms(context)
        var scheduledCount = 0
        var firstFailure: String? = null
        reminders.forEachIndexed { index, reminder ->
            val attempt = scheduleAlarm(
                context = context,
                reminder = reminder,
                requestCode = alarmRequestCode(index)
            )
            if (attempt.scheduled) {
                scheduledCount++
            } else if (firstFailure == null) {
                firstFailure = attempt.messageSuffix
            }
        }

        if (scheduledCount == 0) {
            return AlarmResult(false, "设置闹钟失败。${firstFailure.orEmpty()}")
        }

        // 注册 WorkManager 看门狗：作为激进省电 ROM（如 ColorOS）清掉闹钟后的兜底补排层。
        AlarmWatchdogWorker.enqueue(context)

        val next = reminders.first()
        ScheduleRepository.saveLastAlarmSignature(context, next.signature())
        return AlarmResult(
            true,
            "提醒已同步。"
        )
    }

    fun canUseExactAlarms(context: Context): Boolean {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return canUseExactAlarms(alarmManager)
    }

    fun openExactAlarmPermissionSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return false
        }
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = android.net.Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val canHandle = intent.resolveActivity(context.packageManager) != null
        if (canHandle) {
            context.startActivity(intent)
        }
        return canHandle
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
        if (manager.getNotificationChannel(AlarmReminderReceiver.CHANNEL_ID) != null) {
            return
        }
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val alarmAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        manager.createNotificationChannel(
            NotificationChannel(
                AlarmReminderReceiver.CHANNEL_ID,
                "课程提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "课程闹钟提醒，请开启锁屏通知和横幅通知。"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 600, 300, 600)
                setSound(alarmSound, alarmAttributes)
            }
        )
    }

    fun openAlarmNotificationChannelSettings(context: Context): Boolean {
        ensureAlarmNotificationChannel(context)
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, AlarmReminderReceiver.CHANNEL_ID)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val canHandle = intent.resolveActivity(context.packageManager) != null
        if (canHandle) {
            context.startActivity(intent)
        }
        return canHandle || openAppNotificationSettings(context)
    }

    fun openAppNotificationSettings(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val canHandle = intent.resolveActivity(context.packageManager) != null
        if (canHandle) {
            context.startActivity(intent)
        }
        return canHandle || openAppDetailsSettings(context)
    }

    fun openBatteryOptimizationSettings(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val canHandle = intent.resolveActivity(context.packageManager) != null
        if (canHandle) {
            context.startActivity(intent)
        }
        return canHandle
    }

    /** 打开各主流 ROM 的自启动/后台启动管理页，失败时回退到本应用详情页。 */
    fun openAutoStartSettings(context: Context): Boolean {
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        val candidates = buildList {
            when {
                manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> {
                    add(componentIntent("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
                    add(componentIntent("com.miui.securitycenter", "com.miui.powercenter.PowerSettings"))
                }
                manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus") -> {
                    add(componentIntent("com.oplus.battery", "com.oplus.startupapp.view.StartupAppListActivity"))
                    add(componentIntent("com.oplus.battery", "com.oplus.startupapp.view.OptimizationAutoStartActivity"))
                    add(componentIntent("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"))
                    add(componentIntent("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"))
                    add(componentIntent("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"))
                    add(componentIntent("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"))
                    add(componentIntent("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"))
                    add(componentIntent("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"))
                }
                manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> {
                    add(componentIntent("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"))
                    add(componentIntent("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity"))
                    add(componentIntent("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"))
                    add(componentIntent("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"))
                }
                manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                    add(componentIntent("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))
                    add(componentIntent("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"))
                    add(componentIntent("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"))
                }
                manufacturer.contains("meizu") -> {
                    add(componentIntent("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"))
                    add(componentIntent("com.meizu.safe", "com.meizu.safe.security.HomeActivity"))
                }
                manufacturer.contains("asus") -> {
                    add(componentIntent("com.asus.mobilemanager", "com.asus.mobilemanager.entry.FunctionActivity"))
                    add(componentIntent("com.asus.mobilemanager", "com.asus.mobilemanager.MainActivity"))
                }
                manufacturer.contains("samsung") -> {
                    add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
            addAll(commonAutoStartIntents())
        }
        if (launchFirstAvailable(context, candidates)) return true
        return openAppDetailsSettings(context)
    }

    private fun componentIntent(packageName: String, className: String): Intent {
        return Intent().apply {
            setClassName(packageName, className)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun commonAutoStartIntents(): List<Intent> {
        return listOf(
            componentIntent("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            componentIntent("com.oplus.battery", "com.oplus.startupapp.view.StartupAppListActivity"),
            componentIntent("com.oplus.battery", "com.oplus.startupapp.view.OptimizationAutoStartActivity"),
            componentIntent("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
            componentIntent("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            componentIntent("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            componentIntent("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            componentIntent("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity")
        )
    }

    private fun launchFirstAvailable(context: Context, intents: List<Intent>): Boolean {
        intents.forEach { intent ->
            val launched = runCatching {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }.onFailure {
                Log.d(TAG, "auto-start settings intent failed: ${intent.component ?: intent.action}", it)
            }.isSuccess
            if (launched) return true
        }
        return false
    }

    /** 打开本应用的系统“应用详情”页（自启动、后台运行、耗电管理等入口通常在此页内）。 */
    fun openAppDetailsSettings(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val canHandle = intent.resolveActivity(context.packageManager) != null
        if (canHandle) {
            context.startActivity(intent)
        }
        return canHandle
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true
        }
    }

    private fun buildUpcomingReminders(
        context: Context,
        items: List<CourseItem>,
        lessonTimes: List<LessonTimeSlot>,
        minutesBefore: Int,
        now: LocalDateTime
    ): List<PendingReminder> {
        if (items.isEmpty()) return emptyList()
        val timeIndex = lessonTimes.associateBy { it.period }
        val delivered = ScheduleRepository.loadDeliveredAlarmSignatures(context)

        // 直接为每节课枚举未来若干周的真实上课时刻，收集所有候选后统一排序取最近 N 个。
        // 不再用 cursor 逐步推进 + minByOrNull 选择，避免 immediate 分支把 triggerAt 与
        // lessonStart 解耦导致 cursor 停滞、反复排同一节课（12 项重复）的老 bug。
        val candidates = mutableListOf<PendingReminder>()
        items.forEach { item ->
            val slot = timeIndex[item.period] ?: return@forEach
            repeat(WEEKS_LOOKAHEAD) { weekOffset ->
                val lessonStart = weeklyOccurrence(now, item.dayOfWeek, slot, weekOffset)
                // 只保留上课时刻还没过去的（未来的课）。已过去的整节课直接跳过。
                if (!lessonStart.isAfter(now)) return@repeat
                val rawTriggerAt = lessonStart.minusMinutes(minutesBefore.toLong())
                // 若提前提醒时刻已过（但课还没开始），改为立即提醒；否则用正常提前时刻。
                val adjustedToImmediate = !rawTriggerAt.isAfter(now)
                val triggerAt = if (adjustedToImmediate) {
                    now.plusSeconds(IMMEDIATE_TRIGGER_DELAY_SECONDS)
                } else {
                    rawTriggerAt
                }
                candidates += PendingReminder(
                    item = item,
                    slot = slot,
                    triggerAt = triggerAt,
                    lessonStart = lessonStart,
                    className = item.className,
                    timeRange = formatCourseTime(slot),
                    isDebug = false,
                    triggerAdjustedToImmediate = adjustedToImmediate
                )
            }
        }

        // 过滤已投递、按去重键去重（保留最早触发的那个）、按触发时间排序、取最近 N 个。
        return candidates
            .filterNot { delivered.contains(it.deliverySignature()) }
            .sortedBy { it.triggerAt }
            .distinctBy { it.deliverySignature() }
            .take(MAX_SCHEDULED_ALARMS)
    }

    /** 返回指定星期几课程在“第 weekOffset 周”的上课时刻（weekOffset=0 表示 now 当天或之后最近的一次）。 */
    private fun weeklyOccurrence(
        now: LocalDateTime,
        dayOfWeek: DayOfWeek,
        slot: LessonTimeSlot,
        weekOffset: Int
    ): LocalDateTime {
        val baseDate = now.toLocalDate().with(TemporalAdjusters.nextOrSame(dayOfWeek))
        return LocalDateTime.of(baseDate.plusWeeks(weekOffset.toLong()), slot.startTime)
    }

    private fun scheduleAlarm(
        context: Context,
        reminder: PendingReminder,
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
            if (!reminder.isDebug && canUseExactAlarms(alarmManager)) {
                scheduleBackupExactAlarm(
                    context = context,
                    alarmManager = alarmManager,
                    reminder = reminder,
                    requestCode = requestCode + BACKUP_REQUEST_CODE_OFFSET,
                    triggerMillis = triggerMillis
                )
            }
            Log.i(TAG, "scheduled alarm class=${reminder.className} triggerMillis=$triggerMillis")
            ScheduleAttempt(true, "使用系统闹钟唤醒。")
        }.getOrElse {
            ScheduleAttempt(false, "设置提醒失败：${it.message ?: "未知错误"}")
        }
    }

    private fun buildAlarmIntent(
        context: Context,
        reminder: PendingReminder,
        action: String,
        triggerMillis: Long,
        source: String
    ): Intent {
        return Intent(context, AlarmReminderReceiver::class.java).apply {
            this.action = action
            putExtra(AlarmReminderReceiver.EXTRA_CLASS_NAME, reminder.className)
            putExtra(AlarmReminderReceiver.EXTRA_TIME_RANGE, reminder.timeRange)
            putExtra(AlarmReminderReceiver.EXTRA_PERIOD, reminder.item?.period ?: 0)
            putExtra(AlarmReminderReceiver.EXTRA_TRIGGER_AT, reminder.triggerAt.toString())
            putExtra(AlarmReminderReceiver.EXTRA_TRIGGER_EPOCH_MILLIS, triggerMillis)
            putExtra(AlarmReminderReceiver.EXTRA_LESSON_START, reminder.lessonStart.toString())
            putExtra(AlarmReminderReceiver.EXTRA_IS_DEBUG, reminder.isDebug)
            putExtra(AlarmReminderReceiver.EXTRA_SIGNATURE, reminder.deliverySignature())
            putExtra(AlarmReminderReceiver.EXTRA_SOURCE, source)
        }
    }

    private fun scheduleBackupExactAlarm(
        context: Context,
        alarmManager: AlarmManager,
        reminder: PendingReminder,
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

    private fun cancelAllScheduledAlarms(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        repeat(MAX_SCHEDULED_ALARMS) { index ->
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

    private fun buildAppLaunchIntent(context: Context): Intent {
        return (context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }

    private data class PendingReminder(
        val item: CourseItem?,
        val slot: LessonTimeSlot?,
        val triggerAt: LocalDateTime,
        val lessonStart: LocalDateTime,
        val className: String,
        val timeRange: String,
        val isDebug: Boolean,
        val triggerAdjustedToImmediate: Boolean
    ) {
        fun signature(): String {
            if (isDebug) {
                return "debug|${triggerAt}"
            }
            return listOf(
                item?.dayOfWeek?.name.orEmpty(),
                item?.period?.toString().orEmpty(),
                lessonStart.toLocalDate().toString(),
                triggerAt.toLocalTime().toString(),
                className
            ).joinToString("|")
        }

        fun deliverySignature(): String {
            if (isDebug) {
                return signature()
            }
            return listOf(
                item?.dayOfWeek?.name.orEmpty(),
                item?.period?.toString().orEmpty(),
                lessonStart.toLocalDate().toString(),
                className
            ).joinToString("|")
        }
    }

    private data class ScheduleAttempt(
        val scheduled: Boolean,
        val messageSuffix: String
    )

    private const val REQUEST_CODE_BASE = 1001
    private const val SHOW_REQUEST_CODE_OFFSET = 10_000
    private const val BACKUP_REQUEST_CODE_OFFSET = 20_000

    fun rescheduleStoredCourseAlarms(
        context: Context,
        now: LocalDateTime = LocalDateTime.now()
    ): AlarmResult {
        return syncCourseAlarms(
            context = context,
            items = ScheduleRepository.load(context),
            lessonTimes = ScheduleRepository.loadLessonTimes(context),
            settings = ScheduleRepository.loadReminderSettings(context),
            now = now
        )
    }

    fun formatCourseTime(slot: LessonTimeSlot): String {
        return "${slot.displayRange()}"
    }

    private fun alarmRequestCode(index: Int): Int = REQUEST_CODE_BASE + index
}
