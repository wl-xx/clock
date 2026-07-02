package com.example.pinkschedule.reminder

import com.example.pinkschedule.model.CourseItem
import com.example.pinkschedule.model.LessonTimeSlot
import com.example.pinkschedule.model.ScheduleDefaults
import java.time.Duration
import java.time.LocalDateTime

object TodayCourseSummary {
    fun notificationText(
        items: List<CourseItem>,
        lessonTimes: List<LessonTimeSlot>,
        now: LocalDateTime = LocalDateTime.now()
    ): String {
        return nearestNotEndedCourse(items, lessonTimes, now)?.let { summary ->
            val status = if (summary.started) {
                "正在上课"
            } else {
                "还有${formatMinutes(summary.minutesUntilStart)}"
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

    private fun formatMinutes(rawMinutes: Long): String {
        val minutes = rawMinutes.coerceAtLeast(0)
        if (minutes < 60) return "${minutes}分钟"
        val hours = minutes / 60
        val remainingMinutes = minutes % 60
        return if (remainingMinutes == 0L) {
            "${hours}小时"
        } else {
            "${hours}小时${remainingMinutes}分钟"
        }
    }

    private data class CourseSummary(
        val course: CourseItem,
        val slot: LessonTimeSlot,
        val startAt: LocalDateTime,
        val endAt: LocalDateTime,
        val started: Boolean = false,
        val minutesUntilStart: Long = 0
    ) {
        fun startedAt(now: LocalDateTime): Boolean = !now.isBefore(startAt) && !now.isAfter(endAt)

        fun withNow(now: LocalDateTime): CourseSummary {
            val isStarted = startedAt(now)
            return copy(
                started = isStarted,
                minutesUntilStart = if (isStarted) 0 else Duration.between(now, startAt).toMinutes()
            )
        }
    }
}
