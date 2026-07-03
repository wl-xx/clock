package com.example.pinkschedule.reminder

import com.example.pinkschedule.model.CourseItem
import com.example.pinkschedule.model.LessonTimeSlot
import com.example.pinkschedule.model.ScheduleDefaults
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

object TodayCourseSummary {
    data class ForegroundNotificationSnapshot(
        val title: String,
        val text: String,
        val stableKey: String,
        val countdownTargetEpochMillis: Long?
    )

    fun notificationText(
        items: List<CourseItem>,
        lessonTimes: List<LessonTimeSlot>,
        now: LocalDateTime = LocalDateTime.now()
    ): String {
        return nearestNotEndedCourse(items, lessonTimes, now)?.let { summary ->
            val status = if (summary.started) {
                "正在上课"
            } else {
                "倒计时${formatDuration(summary.millisUntilStart)}"
            }
            listOf(
                status,
                summary.course.courseName.ifBlank { "课程" },
                summary.course.className.ifBlank { "未填写班级" },
                ScheduleDefaults.periodLabel(summary.course.period),
                summary.slot.displayRange()
            ).joinToString(" · ")
        } ?: "暂无未结束课程"
    }

    fun resultMessage(
        items: List<CourseItem>,
        lessonTimes: List<LessonTimeSlot>,
        now: LocalDateTime = LocalDateTime.now()
    ): String {
        return "今日课程：${notificationText(items, lessonTimes, now)}。"
    }

    fun foregroundNotificationSnapshot(
        items: List<CourseItem>,
        lessonTimes: List<LessonTimeSlot>,
        now: LocalDateTime = LocalDateTime.now()
    ): ForegroundNotificationSnapshot {
        val summary = nearestNotEndedCourse(items, lessonTimes, now)
            ?: return ForegroundNotificationSnapshot(
                title = "今日课程",
                text = "暂无未结束课程",
                stableKey = "empty|${now.toLocalDate()}",
                countdownTargetEpochMillis = null
            )
        val courseText = listOf(
            summary.course.courseName.ifBlank { "课程" },
            summary.course.className.ifBlank { "未填写班级" },
            ScheduleDefaults.periodLabel(summary.course.period),
            summary.slot.displayRange()
        ).joinToString(" · ")
        val courseKey = listOf(
            summary.course.dayOfWeek.name,
            summary.course.period.toString(),
            summary.startAt.toLocalDate().toString(),
            summary.course.className
        ).joinToString("|")
        return if (summary.started) {
            ForegroundNotificationSnapshot(
                title = "正在上课",
                text = courseText,
                stableKey = "started|$courseKey",
                countdownTargetEpochMillis = null
            )
        } else {
            ForegroundNotificationSnapshot(
                title = "距离上课",
                text = courseText,
                stableKey = "upcoming|$courseKey|${summary.startAt}",
                countdownTargetEpochMillis = summary.startAt
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            )
        }
    }

    private fun nearestNotEndedCourse(
        items: List<CourseItem>,
        lessonTimes: List<LessonTimeSlot>,
        now: LocalDateTime
    ): CourseSummary? {
        val timeIndex = lessonTimes.associateBy { it.period }
        return items.asSequence()
            .filter { it.dayOfWeek == now.dayOfWeek }
            .mapNotNull { course ->
                val slot = timeIndex[course.period] ?: return@mapNotNull null
                val startAt = LocalDateTime.of(now.toLocalDate(), slot.startTime)
                val endAt = LocalDateTime.of(now.toLocalDate(), slot.endTime)
                if (now.isAfter(endAt)) return@mapNotNull null
                CourseSummary(
                    course = course,
                    slot = slot,
                    startAt = startAt,
                    endAt = endAt
                )
            }
            .minWithOrNull(compareBy<CourseSummary> { if (it.startedAt(now)) 0L else Duration.between(now, it.startAt).toMillis() }
                .thenBy { it.startAt })
            ?.withNow(now)
    }

    private fun formatDuration(rawMillis: Long): String {
        val totalSeconds = (rawMillis.coerceAtLeast(0) + 999) / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    private data class CourseSummary(
        val course: CourseItem,
        val slot: LessonTimeSlot,
        val startAt: LocalDateTime,
        val endAt: LocalDateTime,
        val started: Boolean = false,
        val millisUntilStart: Long = 0
    ) {
        fun startedAt(now: LocalDateTime): Boolean = !now.isBefore(startAt) && !now.isAfter(endAt)

        fun withNow(now: LocalDateTime): CourseSummary {
            val isStarted = startedAt(now)
            return copy(
                started = isStarted,
                millisUntilStart = if (isStarted) 0 else Duration.between(now, startAt).toMillis()
            )
        }
    }
}
