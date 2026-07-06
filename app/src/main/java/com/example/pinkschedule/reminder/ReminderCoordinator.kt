package com.example.pinkschedule.reminder

import android.content.Context
import android.util.Log
import com.example.pinkschedule.data.AlarmDiagnostics
import com.example.pinkschedule.data.ScheduleRepository
import com.example.pinkschedule.domain.CourseScheduleCalculator
import com.example.pinkschedule.domain.DeliveredSignaturePolicy
import com.example.pinkschedule.domain.ScheduleSnapshot
import com.example.pinkschedule.model.CourseItem
import com.example.pinkschedule.model.LessonTimeSlot
import java.time.LocalDateTime

/**
 * 提醒调度的唯一门面。所有"课表/作息/设置变了""到点了""系统事件"都汇聚到这里，
 * 统一执行：计算快照 → 下发课程闹钟（三层冗余之一）→ 续排心跳 → 启停前台服务 → 写诊断。
 *
 * 排程入口不再散落各组件；delivered 签名的清理策略也收敛于此：
 * 仅当课表内容实质变化时清除未来日期的签名，永不清除今天已投递的，
 * 避免"提醒窗口内改课导致已响过的课再响一次"。
 */
object ReminderCoordinator {
    private const val TAG = "ReminderCoordinator"
    private const val DEBOUNCE_MS = 5_000L

    /** 心跳自续上限：即使长时间无状态边界（如周末），也每 6 小时续一次链，断链可自愈。 */
    private const val HEARTBEAT_MAX_INTERVAL_HOURS = 6L

    data class SyncResult(val launched: Boolean, val message: String)

    @Volatile private var lastContentHash: Int? = null
    @Volatile private var lastDebounceHash: Int? = null
    @Volatile private var lastSyncAtMs: Long = 0L
    @Volatile private var lastResult: SyncResult? = null
    @Volatile private var lastHeartbeatTarget: LocalDateTime? = null

    // 上次排程时的课表/作息快照，用于内容变化时定向作废 delivered 签名
    //（只作废时间被实际修改的课，避免误伤无关课程导致重复响铃）。
    @Volatile private var lastItems: List<CourseItem>? = null
    @Volatile private var lastLessonTimes: List<LessonTimeSlot>? = null
    private val lock = Any()

    /**
     * 课表/作息/设置变化、App 启动、开机、看门狗兜底等一切需要重排的场景统一入口。
     * 5 秒内容去抖：连续写操作与响铃后补排合并为一次实际排程。
     */
    fun onScheduleChanged(context: Context, reason: String): SyncResult {
        synchronized(lock) {
            val appContext = context.applicationContext
            val items = ScheduleRepository.load(appContext)
            val lessonTimes = ScheduleRepository.loadLessonTimes(appContext)
            val settings = ScheduleRepository.loadReminderSettings(appContext).normalized()
            val now = LocalDateTime.now()

            val contentHash = listOf(items, lessonTimes, settings).hashCode()
            val deliveredHash = ScheduleRepository.loadDeliveredAlarmSignatures(appContext).hashCode()
            val debounceHash = 31 * contentHash + deliveredHash
            val previousHash = lastContentHash
            if (lastDebounceHash == debounceHash &&
                System.currentTimeMillis() - lastSyncAtMs < DEBOUNCE_MS
            ) {
                lastResult?.let {
                    Log.i(TAG, "debounced sync reason=$reason")
                    return it
                }
            }

            ScheduleRepository.pruneDeliveredAlarmSignatures(appContext, now.toLocalDate())
            if (previousHash != null && previousHash != contentHash) {
                // 课表实质变化：定向作废受影响的签名——未来日期的全部作废；
                // 今天的只作废"时间被实际修改的课"，已响过的无关课程保留（防重复响铃），
                // 被修改的课则按新时间重新提醒（防改课后静默漏提醒）。
                val delivered = ScheduleRepository.loadDeliveredAlarmSignatures(appContext)
                val toClear = DeliveredSignaturePolicy.signaturesToClear(
                    delivered = delivered,
                    oldItems = lastItems,
                    oldLessonTimes = lastLessonTimes,
                    newItems = items,
                    newLessonTimes = lessonTimes,
                    reminderMinutesBefore = settings.reminderMinutesBefore,
                    now = now
                )
                if (toClear.isNotEmpty()) {
                    Log.i(TAG, "schedule content changed, re-arming ${toClear.size} delivered signature(s)")
                    ScheduleRepository.saveDeliveredAlarmSignatures(appContext, delivered - toClear)
                }
            }

            val result = sync(appContext, reason, now)
            lastContentHash = contentHash
            lastDebounceHash = debounceHash
            lastSyncAtMs = System.currentTimeMillis()
            lastResult = result
            lastItems = items
            lastLessonTimes = lessonTimes
            return result
        }
    }

    /** 响铃后补排：让 12 项预排窗口向后滑动一格。 */
    fun onReminderFired(context: Context, firedAt: LocalDateTime) {
        onScheduleChanged(context, "after_ring")
    }

    /**
     * 幂等续排心跳边界闹钟。挂在所有存活锚点上（前台服务启动、看门狗、开机、
     * 响铃、亮屏、App 启动），任一路径都能把断掉的链重新接上。
     */
    fun ensureHeartbeat(context: Context) {
        val appContext = context.applicationContext
        val settings = ScheduleRepository.loadReminderSettings(appContext).normalized()
        if (!settings.hasEnabledReminder()) {
            SystemAlarmScheduler.cancelHeartbeat(appContext)
            lastHeartbeatTarget = null
            return
        }
        val now = LocalDateTime.now()
        val snapshot = computeSnapshot(appContext, now)
        val target = heartbeatTarget(snapshot, now)
        SystemAlarmScheduler.scheduleHeartbeat(appContext, target)
        if (target != lastHeartbeatTarget) {
            lastHeartbeatTarget = target
            AlarmDiagnostics.recordHeartbeat(appContext, "scheduled", target)
        }
    }

    /**
     * 状态边界到达（心跳闹钟或前台服务的进程内边界回调）：重算快照 →
     * 到点未投递的提醒兜底触发（三层冗余的第三层）→
     * 刷新前台通知（服务被杀则顺带复活）→ 续排下一次心跳。
     */
    fun onHeartbeat(context: Context, source: String = "alarm") {
        val appContext = context.applicationContext
        AlarmDiagnostics.recordHeartbeat(appContext, "fired:$source", null)
        val settings = ScheduleRepository.loadReminderSettings(appContext).normalized()
        if (!settings.hasEnabledReminder()) {
            SystemAlarmScheduler.cancelHeartbeat(appContext)
            return
        }
        val now = LocalDateTime.now()
        val snapshot = computeSnapshot(appContext, now)

        snapshot.dueReminder?.let { due ->
            if (AlarmReminderReceiver.isInFlight(due.deliverySignature())) {
                // AlarmManager 广播路径已接手（正在启动响铃服务），兜底不再重复触发。
                Log.i(TAG, "boundary($source) due reminder already in flight, skip")
            } else {
                Log.i(TAG, "boundary($source) firing due reminder class=${due.course.className} lessonStart=${due.lessonStart}")
                // 先标记已投递，避免与 AlarmManager 主/备份路径重复响铃。
                ScheduleRepository.markAlarmDelivered(appContext, due.deliverySignature())
                SystemAlarmScheduler.startRingingService(appContext, due, source = source)
            }
        }

        AlarmForegroundService.refresh(appContext)

        val target = heartbeatTarget(computeSnapshot(appContext, LocalDateTime.now()), LocalDateTime.now())
        SystemAlarmScheduler.scheduleHeartbeat(appContext, target)
        lastHeartbeatTarget = target
        AlarmDiagnostics.recordHeartbeat(appContext, "scheduled", target)
    }

    fun computeSnapshot(context: Context, now: LocalDateTime): ScheduleSnapshot {
        val appContext = context.applicationContext
        return CourseScheduleCalculator.snapshot(
            items = ScheduleRepository.load(appContext),
            lessonTimes = ScheduleRepository.loadLessonTimes(appContext),
            reminderMinutesBefore = ScheduleRepository.loadReminderSettings(appContext)
                .normalized().reminderMinutesBefore,
            delivered = ScheduleRepository.loadDeliveredAlarmSignatures(appContext),
            now = now
        )
    }

    private fun sync(context: Context, reason: String, now: LocalDateTime): SyncResult {
        val settings = ScheduleRepository.loadReminderSettings(context).normalized()
        if (!settings.hasEnabledReminder()) {
            SystemAlarmScheduler.cancelAllScheduledAlarms(context)
            SystemAlarmScheduler.cancelHeartbeat(context)
            AlarmWatchdogWorker.cancel(context)
            // 前台服务跟随提醒开关：全部关闭时移除常驻通知。
            AlarmForegroundService.stop(context)
            lastHeartbeatTarget = null
            AlarmDiagnostics.recordScheduled(context, emptyList(), reason)
            return SyncResult(false, "课程提醒未开启。")
        }

        AlarmForegroundService.start(context)
        val snapshot = computeSnapshot(context, now)
        val reminders = snapshot.upcomingReminders

        val result = if (reminders.isEmpty()) {
            SystemAlarmScheduler.cancelAllScheduledAlarms(context)
            SyncResult(false, "暂无需要提醒的后续课程。")
        } else {
            SystemAlarmScheduler.scheduleReminders(context, reminders)
        }

        // 心跳与看门狗即使暂无课程也保留：负责跨天刷新与后续重新评估。
        val target = heartbeatTarget(snapshot, now)
        SystemAlarmScheduler.scheduleHeartbeat(context, target)
        lastHeartbeatTarget = target
        AlarmWatchdogWorker.enqueue(context)
        AlarmDiagnostics.recordScheduled(context, reminders, reason)
        Log.i(TAG, "sync reason=$reason scheduled=${reminders.size} nextTransition=${snapshot.nextTransitionAt}")
        return result
    }

    private fun heartbeatTarget(snapshot: ScheduleSnapshot, now: LocalDateTime): LocalDateTime {
        val cap = now.plusHours(HEARTBEAT_MAX_INTERVAL_HOURS)
        val target = if (snapshot.nextTransitionAt.isBefore(cap)) snapshot.nextTransitionAt else cap
        return if (target.isAfter(now.plusSeconds(1))) target else now.plusSeconds(1)
    }

}
