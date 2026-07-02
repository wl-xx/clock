package com.example.pinkschedule.model

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class CourseItem(
    val teacher: String,
    val className: String,
    val dayOfWeek: DayOfWeek,
    val period: Int,
    val courseName: String = ScheduleDefaults.DEFAULT_COURSE_NAME
) {
    fun displayTimeLabel(): String = ScheduleDefaults.periodLabel(period)
}

data class LessonTimeSlot(
    val period: Int,
    val startTime: LocalTime,
    val endTime: LocalTime
) {
    fun displayLabel(): String = ScheduleDefaults.periodLabel(period)
    fun displayRange(): String = "${startTime.format(HH_MM)} - ${endTime.format(HH_MM)}"
}

data class LessonTimeProfile(
    val id: String,
    val name: String,
    val slots: List<LessonTimeSlot>
)

data class WeeklySchedule(
    val teacher: String,
    val items: List<CourseItem>
)

data class ReminderSettings(
    val alarmModeEnabled: Boolean = false,
    val reminderMinutesBefore: Int = ScheduleDefaults.DEFAULT_REMINDER_MINUTES
) {
    fun normalized(): ReminderSettings {
        return copy(reminderMinutesBefore = reminderMinutesBefore.coerceAtLeast(0))
    }
}

object ScheduleDefaults {
    const val DEFAULT_TEACHER = "吴林湘"
    const val DEFAULT_COURSE_NAME = "数学"
    const val DEFAULT_REMINDER_MINUTES = 10
    const val EARLY_STUDY_PERIOD = 0
    const val LATE_STUDY_PERIOD_BASE = 100
    const val DEFAULT_LESSON_TIME_PROFILE_ID = "default"
    const val DEFAULT_LESSON_TIME_PROFILE_NAME = "默认时间"

    private val defaultTimePairs = mapOf(
        EARLY_STUDY_PERIOD to (LocalTime.of(7, 20) to LocalTime.of(7, 50)),
        1 to (LocalTime.of(8, 0) to LocalTime.of(8, 45)),
        2 to (LocalTime.of(8, 55) to LocalTime.of(9, 40)),
        3 to (LocalTime.of(10, 0) to LocalTime.of(10, 45)),
        4 to (LocalTime.of(10, 55) to LocalTime.of(11, 40)),
        5 to (LocalTime.of(14, 30) to LocalTime.of(15, 15)),
        6 to (LocalTime.of(15, 25) to LocalTime.of(16, 10)),
        7 to (LocalTime.of(16, 30) to LocalTime.of(17, 15)),
        8 to (LocalTime.of(17, 25) to LocalTime.of(18, 10)),
        9 to (LocalTime.of(19, 0) to LocalTime.of(19, 45)),
        10 to (LocalTime.of(19, 55) to LocalTime.of(20, 40)),
        LATE_STUDY_PERIOD_BASE to (LocalTime.of(20, 50) to LocalTime.of(21, 35))
    )

    fun defaultLessonTimeSlots(): List<LessonTimeSlot> {
        return defaultTimePairs.map { (period, times) ->
            LessonTimeSlot(period = period, startTime = times.first, endTime = times.second)
        }.sortedBy { it.period }
    }

    fun defaultLessonTimeProfile(): LessonTimeProfile {
        return LessonTimeProfile(
            id = DEFAULT_LESSON_TIME_PROFILE_ID,
            name = DEFAULT_LESSON_TIME_PROFILE_NAME,
            slots = defaultLessonTimeSlots()
        )
    }

    fun defaultLessonTimeSlotFor(period: Int): LessonTimeSlot {
        val normalizedPeriod = normalizePeriod(period)
        val default = defaultTimePairs[normalizedPeriod] ?: run {
            LocalTime.of(8, 0) to LocalTime.of(8, 45)
        }
        return LessonTimeSlot(period = normalizedPeriod, startTime = default.first, endTime = default.second)
    }

    fun isEarlyStudyPeriod(period: Int): Boolean = period == EARLY_STUDY_PERIOD

    fun isLateStudyPeriod(period: Int): Boolean = period == LATE_STUDY_PERIOD_BASE

    fun isLegacyLateStudyPeriod(period: Int): Boolean = period >= LATE_STUDY_PERIOD_BASE

    fun isRegularCoursePeriod(period: Int): Boolean = period > EARLY_STUDY_PERIOD && period < LATE_STUDY_PERIOD_BASE

    fun normalizePeriod(period: Int): Int {
        return if (isLegacyLateStudyPeriod(period)) LATE_STUDY_PERIOD_BASE else period
    }

    fun periodLabel(period: Int): String {
        return when {
            isEarlyStudyPeriod(period) -> "早自习"
            isLateStudyPeriod(normalizePeriod(period)) -> "晚自习"
            else -> "第${period}节"
        }
    }

    fun tablePeriodLabel(period: Int): String {
        val normalizedPeriod = normalizePeriod(period)
        return when {
            isEarlyStudyPeriod(normalizedPeriod) -> "早"
            isLateStudyPeriod(normalizedPeriod) -> "晚"
            else -> period.toString()
        }
    }

    fun mergeLessonTimeSlots(
        current: List<LessonTimeSlot>,
        requiredPeriods: Iterable<Int>
    ): List<LessonTimeSlot> {
        val periodSet = (current.map { normalizePeriod(it.period) } + requiredPeriods.map(::normalizePeriod) + defaultTimePairs.keys)
            .toSet()
            .sorted()
        val indexed = current.associateBy { it.period }
        return periodSet.map { period ->
            indexed[period] ?: current.firstOrNull { normalizePeriod(it.period) == period }?.copy(period = period)
                ?: defaultLessonTimeSlotFor(period)
        }
    }
}

private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
