package com.example.pinkschedule.model

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class CourseItem(
    val teacher: String,
    val className: String,
    val dayOfWeek: DayOfWeek,
    val period: Int
) {
    fun displayTimeLabel(): String = "第${period}节"
}

data class LessonTimeSlot(
    val period: Int,
    val startTime: LocalTime,
    val endTime: LocalTime
) {
    fun displayLabel(): String = "第${period}节"
    fun displayRange(): String = "${startTime.format(HH_MM)} - ${endTime.format(HH_MM)}"
}

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
    const val DEFAULT_REMINDER_MINUTES = 10

    private val defaultTimePairs = mapOf(
        1 to (LocalTime.of(8, 0) to LocalTime.of(8, 45)),
        2 to (LocalTime.of(8, 55) to LocalTime.of(9, 40)),
        3 to (LocalTime.of(10, 0) to LocalTime.of(10, 45)),
        4 to (LocalTime.of(10, 55) to LocalTime.of(11, 40)),
        5 to (LocalTime.of(14, 30) to LocalTime.of(15, 15)),
        6 to (LocalTime.of(15, 25) to LocalTime.of(16, 10)),
        7 to (LocalTime.of(16, 30) to LocalTime.of(17, 15)),
        8 to (LocalTime.of(17, 25) to LocalTime.of(18, 10)),
        9 to (LocalTime.of(19, 0) to LocalTime.of(19, 45)),
        10 to (LocalTime.of(19, 55) to LocalTime.of(20, 40))
    )

    fun defaultLessonTimeSlots(): List<LessonTimeSlot> {
        return defaultTimePairs.map { (period, times) ->
            LessonTimeSlot(period = period, startTime = times.first, endTime = times.second)
        }.sortedBy { it.period }
    }

    fun defaultLessonTimeSlotFor(period: Int): LessonTimeSlot {
        val default = defaultTimePairs[period] ?: (LocalTime.of(8, 0) to LocalTime.of(8, 45))
        return LessonTimeSlot(period = period, startTime = default.first, endTime = default.second)
    }

    fun mergeLessonTimeSlots(
        current: List<LessonTimeSlot>,
        requiredPeriods: Iterable<Int>
    ): List<LessonTimeSlot> {
        val periodSet = (requiredPeriods.toSet() + defaultTimePairs.keys).sorted()
        val indexed = current.associateBy { it.period }
        return periodSet.map { period -> indexed[period] ?: defaultLessonTimeSlotFor(period) }
    }
}

private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
