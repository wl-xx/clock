package com.example.pinkschedule.domain

import com.example.pinkschedule.model.CourseItem
import com.example.pinkschedule.model.LessonTimeSlot
import java.time.LocalDate
import java.time.LocalDateTime

/** 前台通知展示的当前状态：无课 / 距上课倒计时 / 正在上课。 */
sealed interface ReminderState {
    data class NoCourse(val today: LocalDate, val reason: NoCourseReason) : ReminderState

    data class CountingDown(
        val course: CourseItem,
        val slot: LessonTimeSlot,
        val startAt: LocalDateTime,
        val endAt: LocalDateTime
    ) : ReminderState

    data class InClass(
        val course: CourseItem,
        val slot: LessonTimeSlot,
        val startAt: LocalDateTime,
        val endAt: LocalDateTime
    ) : ReminderState
}

enum class NoCourseReason {
    NO_COURSES_TODAY,
    COMPLETED_TODAY
}

/** 一次待排的课程提醒（AlarmManager 下发单元）。 */
data class PlannedReminder(
    val course: CourseItem,
    val slot: LessonTimeSlot,
    val triggerAt: LocalDateTime,
    val lessonStart: LocalDateTime
) {
    /** 排程签名：含触发时刻，用于识别"同一节课但提前分钟数变了"。 */
    fun signature(): String {
        return listOf(
            course.dayOfWeek.name,
            course.period.toString(),
            lessonStart.toLocalDate().toString(),
            triggerAt.toLocalTime().toString(),
            course.className
        ).joinToString("|")
    }

    /** 去重签名：不含触发时刻，同一节课只响一次。格式与历史 delivered 集合兼容。 */
    fun deliverySignature(): String {
        return listOf(
            course.dayOfWeek.name,
            course.period.toString(),
            lessonStart.toLocalDate().toString(),
            course.className
        ).joinToString("|")
    }
}

/**
 * 一次完整的调度快照。所有消费方（前台通知渲染、课程闹钟下发、心跳续排、
 * 到点兜底触发）共享同一次计算结果，保证状态一致。
 */
data class ScheduleSnapshot(
    val state: ReminderState,
    /** 下一个状态边界：min(下一提醒点, 下一上课, 下一下课, 明日 00:00)，恒非空。 */
    val nextTransitionAt: LocalDateTime,
    /** 未来待排提醒：已过滤 delivered、按触发时间排序、按去重签名去重、截取 maxAlarms。 */
    val upcomingReminders: List<PlannedReminder>,
    /** 当前处于提醒窗口内（triggerAt <= now < lessonStart）且未投递的提醒，用于兜底触发。 */
    val dueReminder: PlannedReminder?
)

/**
 * 提醒调度的唯一时间推演实现（纯函数，可单测）。
 *
 * 状态与排程都是 (课表, 作息, 提前分钟数, delivered, now) 的纯函数，
 * 进程被杀重建后重算即恢复，无需持久化任何运行时状态。
 */
object CourseScheduleCalculator {
    const val MAX_SCHEDULED_ALARMS = 12

    // 每节课向后枚举多少周的上课时刻。需 ≥ MAX_SCHEDULED_ALARMS，
    // 保证即使只有 1 节课也能凑满 12 个未来不同日期的闹钟。
    const val WEEKS_LOOKAHEAD = 14
    const val IMMEDIATE_TRIGGER_DELAY_SECONDS = 2L

    fun snapshot(
        items: List<CourseItem>,
        lessonTimes: List<LessonTimeSlot>,
        reminderMinutesBefore: Int,
        delivered: Set<String>,
        now: LocalDateTime,
        maxAlarms: Int = MAX_SCHEDULED_ALARMS,
        weeksLookahead: Int = WEEKS_LOOKAHEAD
    ): ScheduleSnapshot {
        val minutesBefore = reminderMinutesBefore.coerceAtLeast(0)
        val occurrences = enumerateOccurrences(items, lessonTimes, now, weeksLookahead)

        val state = currentState(occurrences, now)
        val upcoming = upcomingReminders(occurrences, minutesBefore, delivered, now, maxAlarms)
        val due = dueFromWindow(occurrences, minutesBefore, delivered, now)
        val nextTransition = nextTransitionAt(state, upcoming, now)

        return ScheduleSnapshot(
            state = state,
            nextTransitionAt = nextTransition,
            upcomingReminders = upcoming,
            dueReminder = due
        )
    }

    /** 一次课程发生：某节课在某个具体日期的上下课时刻。 */
    private data class Occurrence(
        val course: CourseItem,
        val slot: LessonTimeSlot,
        val startAt: LocalDateTime,
        val endAt: LocalDateTime
    )

    private fun enumerateOccurrences(
        items: List<CourseItem>,
        lessonTimes: List<LessonTimeSlot>,
        now: LocalDateTime,
        weeksLookahead: Int
    ): List<Occurrence> {
        if (items.isEmpty()) return emptyList()
        val timeIndex = lessonTimes.associateBy { it.period }
        val occurrences = mutableListOf<Occurrence>()
        items.forEach { item ->
            val slot = timeIndex[item.period] ?: return@forEach
            // 从本周（含今天）的这一天开始，逐周枚举真实上课日期。
            val firstDate = now.toLocalDate()
                .with(java.time.temporal.TemporalAdjusters.nextOrSame(item.dayOfWeek))
            repeat(weeksLookahead) { weekOffset ->
                val date = firstDate.plusWeeks(weekOffset.toLong())
                occurrences += Occurrence(
                    course = item,
                    slot = slot,
                    startAt = LocalDateTime.of(date, slot.startTime),
                    endAt = effectiveEndAt(date, slot)
                )
            }
        }
        return occurrences.sortedBy { it.startAt }
    }

    private fun currentState(occurrences: List<Occurrence>, now: LocalDateTime): ReminderState {
        // 正在上课优先。课表终点时间按整分钟展示，状态切换在终点时间的下一分钟发生。
        occurrences.firstOrNull { !now.isBefore(it.startAt) && now.isBefore(it.endAt) }?.let {
            return ReminderState.InClass(it.course, it.slot, it.startAt, it.endAt)
        }
        // 否则取今天还没开始的最近一节课；今天没有则为无课（跨天由午夜边界心跳刷新）。
        val today = now.toLocalDate()
        val todayOccurrences = occurrences.filter { it.startAt.toLocalDate() == today }
        todayOccurrences.firstOrNull { it.startAt.isAfter(now) }?.let {
            return ReminderState.CountingDown(it.course, it.slot, it.startAt, it.endAt)
        }
        val reason = if (todayOccurrences.isEmpty()) {
            NoCourseReason.NO_COURSES_TODAY
        } else {
            NoCourseReason.COMPLETED_TODAY
        }
        return ReminderState.NoCourse(today, reason)
    }

    private fun effectiveEndAt(date: LocalDate, slot: LessonTimeSlot): LocalDateTime {
        return LocalDateTime.of(date, slot.endTime).plusMinutes(1)
    }

    private fun upcomingReminders(
        occurrences: List<Occurrence>,
        minutesBefore: Int,
        delivered: Set<String>,
        now: LocalDateTime,
        maxAlarms: Int
    ): List<PlannedReminder> {
        return occurrences.asSequence()
            // 只保留上课时刻还没到的课；已开始/已结束的不再提醒。
            .filter { it.startAt.isAfter(now) }
            .map { occ ->
                val rawTriggerAt = occ.startAt.minusMinutes(minutesBefore.toLong())
                // 提前提醒时刻已过但课还没开始：改为立即提醒。
                val triggerAt = if (!rawTriggerAt.isAfter(now)) {
                    now.plusSeconds(IMMEDIATE_TRIGGER_DELAY_SECONDS)
                } else {
                    rawTriggerAt
                }
                PlannedReminder(occ.course, occ.slot, triggerAt, occ.startAt)
            }
            .filterNot { delivered.contains(it.deliverySignature()) }
            .sortedBy { it.triggerAt }
            .distinctBy { it.deliverySignature() }
            .take(maxAlarms)
            .toList()
    }

    /**
     * upcoming 列表里 immediate 分支会把 triggerAt 改成 now+2s（isAfter(now)），
     * 因此"已进入提醒窗口"的课在 upcoming 里查不出 due——单独按原始窗口判断。
     */
    private fun dueFromWindow(
        occurrences: List<Occurrence>,
        minutesBefore: Int,
        delivered: Set<String>,
        now: LocalDateTime
    ): PlannedReminder? {
        return occurrences.asSequence()
            .filter { it.startAt.isAfter(now) }
            .filter { !it.startAt.minusMinutes(minutesBefore.toLong()).isAfter(now) }
            .map { PlannedReminder(it.course, it.slot, it.startAt.minusMinutes(minutesBefore.toLong()), it.startAt) }
            .filterNot { delivered.contains(it.deliverySignature()) }
            .minByOrNull { it.lessonStart }
    }

    private fun nextTransitionAt(
        state: ReminderState,
        upcoming: List<PlannedReminder>,
        now: LocalDateTime
    ): LocalDateTime {
        val candidates = mutableListOf<LocalDateTime>()
        when (state) {
            is ReminderState.CountingDown -> {
                // 倒计时 → 上课的切换点。
                candidates += state.startAt
            }
            is ReminderState.InClass -> {
                // 上课 → 下课的切换点。
                candidates += state.endAt
            }
            is ReminderState.NoCourse -> Unit
        }
        // 下一个提醒点（心跳到点做兜底触发）。
        upcoming.firstOrNull { it.triggerAt.isAfter(now) }?.let { candidates += it.triggerAt }
        // 下一节课的上课时刻（NoCourse → 次日 CountingDown 等场景）。
        upcoming.firstOrNull()?.let { candidates += it.lessonStart }
        // 午夜边界：跨天刷新"今日课程"。
        candidates += now.toLocalDate().plusDays(1).atStartOfDay()
        return candidates.filter { it.isAfter(now) }.min()
    }
}
