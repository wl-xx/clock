package com.example.pinkschedule.data

import com.example.pinkschedule.model.CourseItem
import com.example.pinkschedule.model.LessonTimeSlot
import com.example.pinkschedule.model.ScheduleDefaults
import com.example.pinkschedule.model.WeeklySchedule
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalTime

data class ScheduleTransferPayload(
    val schedule: WeeklySchedule,
    val lessonTimes: List<LessonTimeSlot>
)

object ScheduleTransfer {
    fun toJson(schedule: WeeklySchedule, lessonTimes: List<LessonTimeSlot>): String {
        val root = JSONObject()
            .put("version", 1)
            .put("app", "湘约一课")
            .put("teacher", schedule.teacher)
            .put("lessonTimes", JSONArray().apply {
                lessonTimes.sortedBy { it.period }.forEach { slot ->
                    put(
                        JSONObject()
                            .put("period", slot.period)
                            .put("startTime", slot.startTime.toString())
                            .put("endTime", slot.endTime.toString())
                    )
                }
            })
            .put("courses", JSONArray().apply {
                schedule.items.sortedWith(compareBy({ it.dayOfWeek.value }, { it.period }, { it.className }))
                    .forEach { item ->
                        put(
                            JSONObject()
                                .put("teacher", item.teacher)
                                .put("courseName", item.courseName)
                                .put("className", item.className)
                                .put("dayOfWeek", item.dayOfWeek.name)
                                .put("period", item.period)
                        )
                    }
            })
        return root.toString(2)
    }

    fun fromJson(raw: String): ScheduleTransferPayload {
        val root = JSONObject(raw)
        val teacher = root.optString("teacher").ifBlank { ScheduleDefaults.DEFAULT_TEACHER }
        val lessonTimes = parseLessonTimes(root.optJSONArray("lessonTimes"))
        val courses = parseCourses(root.optJSONArray("courses"), teacher)
        val mergedLessonTimes = ScheduleDefaults.mergeLessonTimeSlots(
            current = lessonTimes.ifEmpty { ScheduleDefaults.defaultLessonTimeSlots() },
            requiredPeriods = courses.map { it.period }
        )
        return ScheduleTransferPayload(
            schedule = WeeklySchedule(
                teacher = courses.firstOrNull()?.teacher?.ifBlank { teacher } ?: teacher,
                items = courses
            ),
            lessonTimes = mergedLessonTimes
        )
    }

    private fun parseLessonTimes(array: JSONArray?): List<LessonTimeSlot> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val period = item.optInt("period", 0).takeIf { it > 0 } ?: continue
                val startTime = runCatching { LocalTime.parse(item.optString("startTime")) }.getOrNull() ?: continue
                val endTime = runCatching { LocalTime.parse(item.optString("endTime")) }.getOrNull() ?: continue
                if (endTime.isAfter(startTime)) {
                    add(LessonTimeSlot(period = period, startTime = startTime, endTime = endTime))
                }
            }
        }.distinctBy { it.period }.sortedBy { it.period }
    }

    private fun parseCourses(array: JSONArray?, defaultTeacher: String): List<CourseItem> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val dayOfWeek = runCatching {
                    DayOfWeek.valueOf(item.optString("dayOfWeek"))
                }.getOrNull() ?: continue
                val period = item.optInt("period", 0).takeIf { it > 0 } ?: continue
                add(
                    CourseItem(
                        teacher = item.optString("teacher").ifBlank { defaultTeacher },
                        className = item.optString("className"),
                        dayOfWeek = dayOfWeek,
                        period = period,
                        courseName = item.optString("courseName").ifBlank { ScheduleDefaults.DEFAULT_COURSE_NAME }
                    )
                )
            }
        }.sortedWith(compareBy({ it.dayOfWeek.value }, { it.period }, { it.className }))
    }
}
