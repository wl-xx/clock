package com.example.pinkschedule.data

import com.example.pinkschedule.model.CourseItem
import com.example.pinkschedule.model.LessonTimeProfile
import com.example.pinkschedule.model.LessonTimeSlot
import com.example.pinkschedule.model.ScheduleDefaults
import com.example.pinkschedule.model.WeeklySchedule
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalTime

data class ScheduleTransferPayload(
    val schedule: WeeklySchedule
)

data class LessonTimeProfilesTransferPayload(
    val profiles: List<LessonTimeProfile>
)

object ScheduleTransfer {
    fun toJson(schedule: WeeklySchedule): String {
        val root = JSONObject()
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
        val teacher = ScheduleDefaults.DEFAULT_TEACHER
        val courses = parseCourses(root.optJSONArray("courses"), teacher)
        return ScheduleTransferPayload(
            schedule = WeeklySchedule(
                teacher = courses.firstOrNull()?.teacher?.ifBlank { teacher } ?: teacher,
                items = courses
            )
        )
    }

    private fun parseCourses(array: JSONArray?, defaultTeacher: String): List<CourseItem> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val dayOfWeek = runCatching {
                    DayOfWeek.valueOf(item.optString("dayOfWeek"))
                }.getOrNull() ?: continue
                val period = item.optInt("period", -1).takeIf { it >= 0 } ?: continue
                add(
                    CourseItem(
                        teacher = item.optString("teacher").ifBlank { defaultTeacher },
                        className = item.optString("className"),
                        dayOfWeek = dayOfWeek,
                        period = ScheduleDefaults.normalizePeriod(period),
                        courseName = item.optString("courseName").ifBlank { ScheduleDefaults.DEFAULT_COURSE_NAME }
                    )
                )
            }
        }.sortedWith(compareBy({ it.dayOfWeek.value }, { it.period }, { it.className }))
    }
}

object LessonTimeProfilesTransfer {
    fun toJson(profiles: List<LessonTimeProfile>): String {
        return JSONObject()
            .put("version", 1)
            .put("app", "湘约一课")
            .put("type", "lessonTimeProfiles")
            .put("profiles", JSONArray().apply {
                profiles.forEach { profile ->
                    put(
                        JSONObject()
                            .put("id", profile.id)
                            .put("name", profile.name)
                            .put("lessonTimes", JSONArray().apply {
                                profile.slots.sortedBy { it.period }.forEach { slot ->
                                    put(
                                        JSONObject()
                                            .put("period", slot.period)
                                            .put("startTime", slot.startTime.toString())
                                            .put("endTime", slot.endTime.toString())
                                    )
                                }
                            })
                    )
                }
            })
            .toString(2)
    }

    fun fromJson(raw: String): LessonTimeProfilesTransferPayload {
        val root = JSONObject(raw)
        val profilesArray = root.optJSONArray("profiles")
        val profiles = if (profilesArray != null) {
            val firstProfile = buildList {
                for (index in 0 until profilesArray.length()) {
                    val item = profilesArray.optJSONObject(index) ?: continue
                    val slots = parseProfileLessonTimes(
                        item.optJSONArray("lessonTimes") ?: item.optJSONArray("slots")
                    )
                    if (slots.isNotEmpty()) {
                        add(
                            LessonTimeProfile(
                                id = item.optString("id").ifBlank { "imported-${System.currentTimeMillis()}-$index" },
                                name = item.optString("name").ifBlank { "导入作息表 ${index + 1}" },
                                slots = slots
                            )
                        )
                    }
                }
            }.firstOrNull()
            if (firstProfile == null) emptyList() else listOf(firstProfile)
        } else {
            val slots = parseProfileLessonTimes(root.optJSONArray("lessonTimes"))
            if (slots.isEmpty()) emptyList() else listOf(
                LessonTimeProfile(
                    id = root.optString("id").ifBlank { "imported-${System.currentTimeMillis()}" },
                    name = root.optString("name").ifBlank { "导入作息表" },
                    slots = slots
                )
            )
        }
        if (profiles.isEmpty()) {
            error("文件中没有有效作息时间。")
        }
        return LessonTimeProfilesTransferPayload(profiles = profiles)
    }

    private fun parseProfileLessonTimes(array: JSONArray?): List<LessonTimeSlot> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val period = item.optInt("period", -1).takeIf { it >= 0 } ?: continue
                val startTime = runCatching { LocalTime.parse(item.optString("startTime")) }.getOrNull() ?: continue
                val endTime = runCatching { LocalTime.parse(item.optString("endTime")) }.getOrNull() ?: continue
                if (endTime.isAfter(startTime)) {
                    add(LessonTimeSlot(period = ScheduleDefaults.normalizePeriod(period), startTime = startTime, endTime = endTime))
                }
            }
        }.distinctBy { it.period }.sortedBy { it.period }
    }
}
