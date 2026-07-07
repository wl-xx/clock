package com.example.pinkschedule.data

import com.example.pinkschedule.model.CourseItem
import com.example.pinkschedule.model.LessonTimeProfile
import com.example.pinkschedule.model.LessonTimeSlot
import com.example.pinkschedule.model.ReminderSettings
import com.example.pinkschedule.model.ScheduleDefaults
import com.example.pinkschedule.model.WeeklySchedule
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalTime

data class AppDataTransferPayload(
    val schedule: WeeklySchedule,
    val profiles: List<LessonTimeProfile>,
    val activeProfileId: String,
    val classPresets: List<String> = emptyList(),
    val reminderVolumePercent: Int? = null
)

object AppDataTransfer {
    private const val FORMAT_VERSION = 1
    private const val FORMAT_TYPE = "appData"

    fun toJson(
        schedule: WeeklySchedule,
        profiles: List<LessonTimeProfile>,
        activeProfileId: String,
        classPresets: List<String>,
        reminderVolumePercent: Int
    ): String {
        return JSONObject()
            .put("version", FORMAT_VERSION)
            .put("app", "湘约一课")
            .put("type", FORMAT_TYPE)
            .put("activeLessonTimeProfileId", activeProfileId)
            .put("classPresets", classPresetsToJson(classPresets))
            .put("reminderVolumePercent", reminderVolumePercent.coerceIn(0, 100))
            .put("courses", coursesToJson(schedule.items))
            .put("lessonTimeProfiles", profilesToJson(profiles))
            .toString(2)
    }

    fun fromJson(raw: String): AppDataTransferPayload {
        val root = JSONObject(raw)
        if (root.optString("type") != FORMAT_TYPE) {
            error("文件不是湘约一课数据文件。")
        }
        if (!root.has("courses") || !root.has("lessonTimeProfiles")) {
            error("数据文件缺少课程或作息表信息。")
        }
        val profiles = parseProfiles(root.optJSONArray("lessonTimeProfiles"))
        val courses = parseCourses(root.optJSONArray("courses"))
        val requestedActiveId = root.optString("activeLessonTimeProfileId")
        val activeProfileId = profiles.firstOrNull { it.id == requestedActiveId }?.id
            ?: profiles.firstOrNull()?.id
            ?: ""
        return AppDataTransferPayload(
            schedule = WeeklySchedule(
                teacher = courses.firstOrNull()?.teacher?.ifBlank { ScheduleDefaults.DEFAULT_TEACHER }
                    ?: ScheduleDefaults.DEFAULT_TEACHER,
                items = courses
            ),
            profiles = profiles,
            activeProfileId = activeProfileId,
            classPresets = parseClassPresets(root.optJSONArray("classPresets")),
            reminderVolumePercent = root.optReminderVolumePercent()
        )
    }

    private fun JSONObject.optReminderVolumePercent(): Int? {
        if (!has("reminderVolumePercent")) return null
        return optInt("reminderVolumePercent", ReminderSettings.DEFAULT_VOLUME_PERCENT).coerceIn(0, 100)
    }

    private fun classPresetsToJson(presets: List<String>): JSONArray {
        return JSONArray().apply {
            ScheduleRepository.normalizeClassPresets(presets).forEach { put(it) }
        }
    }

    private fun coursesToJson(items: List<CourseItem>): JSONArray {
        return JSONArray().apply {
            items.sortedWith(compareBy({ it.dayOfWeek.value }, { it.period }, { it.className }))
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
        }
    }

    private fun profilesToJson(profiles: List<LessonTimeProfile>): JSONArray {
        return JSONArray().apply {
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
        }
    }

    private fun parseCourses(array: JSONArray?): List<CourseItem> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val dayOfWeek = runCatching { DayOfWeek.valueOf(item.optString("dayOfWeek")) }.getOrNull() ?: continue
                val period = item.optInt("period", -1).takeIf { it >= 0 } ?: continue
                add(
                    CourseItem(
                        teacher = item.optString("teacher").ifBlank { ScheduleDefaults.DEFAULT_TEACHER },
                        className = item.optString("className"),
                        dayOfWeek = dayOfWeek,
                        period = period,
                        courseName = item.optString("courseName").ifBlank { ScheduleDefaults.DEFAULT_COURSE_NAME }
                    )
                )
            }
        }.sortedWith(compareBy({ it.dayOfWeek.value }, { it.period }, { it.className }))
    }

    private fun parseProfiles(array: JSONArray?): List<LessonTimeProfile> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    LessonTimeProfile(
                        id = item.optString("id").ifBlank { "profile-${System.currentTimeMillis()}-$index" },
                        name = item.optString("name").ifBlank { "作息表 ${index + 1}" },
                        slots = parseLessonTimes(item.optJSONArray("lessonTimes"))
                    )
                )
            }
        }.distinctBy { it.id }
    }

    private fun parseClassPresets(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                add(array.optString(index))
            }
        }.let(ScheduleRepository::normalizeClassPresets)
    }

    private fun parseLessonTimes(array: JSONArray?): List<LessonTimeSlot> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val period = item.optInt("period", -1).takeIf { it >= 0 } ?: continue
                val startTime = runCatching { LocalTime.parse(item.optString("startTime")) }.getOrNull() ?: continue
                val endTime = runCatching { LocalTime.parse(item.optString("endTime")) }.getOrNull() ?: continue
                if (endTime.isAfter(startTime)) {
                    add(LessonTimeSlot(period = period, startTime = startTime, endTime = endTime))
                }
            }
        }.distinctBy { it.period }.sortedBy { it.period }
    }
}
