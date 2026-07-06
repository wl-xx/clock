package com.example.pinkschedule.reminder

import com.example.pinkschedule.domain.NoCourseReason
import com.example.pinkschedule.domain.ReminderState
import com.example.pinkschedule.domain.ScheduleSnapshot
import com.example.pinkschedule.model.CourseItem
import com.example.pinkschedule.model.LessonTimeSlot
import com.example.pinkschedule.model.ScheduleDefaults
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 前台通知的文案格式化器：把 CourseScheduleCalculator 的快照渲染成通知内容。
 * 不做任何时间推演——那是 domain 层的职责。
 */
object TodayCourseSummary {
    data class ForegroundNotificationSnapshot(
        val title: String,
        val text: String,
        val stableKey: String,
        val countdownTargetEpochMillis: Long?,
        val showCourseHeader: Boolean = true
    )

    fun foregroundNotificationSnapshot(snapshot: ScheduleSnapshot): ForegroundNotificationSnapshot {
        return when (val state = snapshot.state) {
            is ReminderState.NoCourse -> ForegroundNotificationSnapshot(
                title = "今日课程",
                text = noCourseText(state.reason),
                stableKey = "empty|${state.today}|${state.reason}",
                countdownTargetEpochMillis = null,
                showCourseHeader = false
            )
            is ReminderState.InClass -> ForegroundNotificationSnapshot(
                title = "正在上课",
                text = courseText(state.course, state.slot),
                stableKey = "started|${courseKey(state.course, state.startAt)}",
                countdownTargetEpochMillis = null
            )
            is ReminderState.CountingDown -> ForegroundNotificationSnapshot(
                title = "距离上课",
                text = courseText(state.course, state.slot),
                stableKey = "upcoming|${courseKey(state.course, state.startAt)}|${state.startAt}",
                countdownTargetEpochMillis = state.startAt
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            )
        }
    }

    private fun courseText(course: CourseItem, slot: LessonTimeSlot): String {
        return listOf(
            course.courseName.ifBlank { "课程" },
            course.className.ifBlank { "未填写班级" },
            ScheduleDefaults.periodLabel(course.period),
            slot.displayRange()
        ).joinToString(" · ")
    }

    private fun noCourseText(reason: NoCourseReason): String {
        return when (reason) {
            NoCourseReason.COMPLETED_TODAY -> "今天课程都结束了，辛苦啦，好好休息吧~"
            NoCourseReason.NO_COURSES_TODAY -> "今天没有课程安排，好好休息吧~"
        }
    }

    private fun courseKey(course: CourseItem, startAt: LocalDateTime): String {
        return listOf(
            course.dayOfWeek.name,
            course.period.toString(),
            startAt.toLocalDate().toString(),
            course.className
        ).joinToString("|")
    }
}
