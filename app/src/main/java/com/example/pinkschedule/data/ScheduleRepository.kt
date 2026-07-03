package com.example.pinkschedule.data

import android.content.Context
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
import java.util.UUID

object ScheduleRepository {
    private const val PREFS_NAME = "schedule_store"
    private const val KEY_SCHEDULE = "teacher_schedule"
    private const val KEY_CLASS_PRESETS = "class_presets"
    private const val KEY_LESSON_TIME_PROFILES = "lesson_time_profiles"
    private const val KEY_ACTIVE_LESSON_TIME_PROFILE_ID = "active_lesson_time_profile_id"
    private const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
    private const val KEY_ALARM_MODE_ENABLED = "alarm_mode_enabled"
    private const val KEY_VIBRATION_REMINDER_ENABLED = "vibration_reminder_enabled"
    private const val KEY_SOUND_REMINDER_ENABLED = "sound_reminder_enabled"
    private const val KEY_SOUND_REMINDER_TONE_ID = "sound_reminder_tone_id"
    private const val KEY_REMINDER_MINUTES = "reminder_minutes"
    private const val KEY_LAST_ALARM_SIGNATURE = "last_alarm_signature"
    private const val KEY_DELIVERED_ALARM_SIGNATURES = "delivered_alarm_signatures"
    private const val KEY_AUTO_START_PROMPTED = "auto_start_prompted"

    fun save(context: Context, schedule: WeeklySchedule) {
        val serialized = JSONArray().apply {
            schedule.items.forEach { item ->
                put(
                    JSONObject()
                        .put("teacher", item.teacher)
                        .put("courseName", item.courseName)
                        .put("className", item.className)
                        .put("dayOfWeek", item.dayOfWeek.name)
                        .put("period", item.period)
                )
            }
        }.toString()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SCHEDULE, serialized)
            .apply()
    }

    fun load(context: Context): List<CourseItem> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SCHEDULE, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
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
            }
        }.getOrDefault(emptyList())
    }

    fun saveClassPresets(context: Context, presets: List<String>) {
        val serialized = JSONArray().apply {
            normalizeClassPresets(presets).forEach { put(it) }
        }.toString()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CLASS_PRESETS, serialized)
            .apply()
    }

    fun loadClassPresets(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CLASS_PRESETS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    add(array.optString(index))
                }
            }
        }.getOrDefault(emptyList()).let(::normalizeClassPresets)
    }

    fun normalizeClassPresets(presets: List<String>): List<String> {
        return presets
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
    }

    fun saveLessonTimes(context: Context, lessonTimes: List<LessonTimeSlot>) {
        val activeId = loadActiveLessonTimeProfileId(context)
        val profiles = loadLessonTimeProfiles(context)
        val updated = profiles.map { profile ->
            if (profile.id == activeId) {
                profile.copy(slots = lessonTimes.sortedBy { it.period })
            } else {
                profile
            }
        }
        saveLessonTimeProfiles(context, updated, activeId)
    }

    fun loadLessonTimes(context: Context): List<LessonTimeSlot> {
        val activeId = loadActiveLessonTimeProfileId(context)
        return loadLessonTimeProfiles(context)
            .firstOrNull { it.id == activeId }
            ?.slots
            ?.sortedBy { it.period }
            ?: emptyList()
    }

    fun saveLessonTimeProfiles(
        context: Context,
        profiles: List<LessonTimeProfile>,
        activeProfileId: String = loadActiveLessonTimeProfileId(context)
    ) {
        val normalized = normalizeProfiles(profiles)
        val resolvedActiveId = normalized.firstOrNull { it.id == activeProfileId }?.id
            ?: normalized.firstOrNull()?.id.orEmpty()
        val serialized = JSONArray().apply {
            normalized.forEach { profile ->
                put(
                    JSONObject()
                        .put("id", profile.id)
                        .put("name", profile.name)
                        .put("lessonTimes", lessonTimesToJson(profile.slots))
                )
            }
        }.toString()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LESSON_TIME_PROFILES, serialized)
            .putString(KEY_ACTIVE_LESSON_TIME_PROFILE_ID, resolvedActiveId)
            .apply()
    }

    fun loadLessonTimeProfiles(context: Context): List<LessonTimeProfile> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_LESSON_TIME_PROFILES, null)
        val parsed = raw?.let(::parseLessonTimeProfiles).orEmpty()
        if (parsed.isNotEmpty()) {
            return normalizeProfiles(parsed)
        }
        return emptyList()
    }

    fun loadActiveLessonTimeProfileId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_ACTIVE_LESSON_TIME_PROFILE_ID, "") ?: ""
    }

    fun setActiveLessonTimeProfileId(context: Context, profileId: String) {
        val profiles = loadLessonTimeProfiles(context)
        val resolvedId = profiles.firstOrNull { it.id == profileId }?.id ?: profiles.firstOrNull()?.id.orEmpty()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_LESSON_TIME_PROFILE_ID, resolvedId)
            .apply()
    }

    fun newLessonTimeProfile(name: String, sourceSlots: List<LessonTimeSlot>): LessonTimeProfile {
        return LessonTimeProfile(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "新作息表" },
            slots = sourceSlots.sortedBy { it.period }
        )
    }

    private fun lessonTimesToJson(slots: List<LessonTimeSlot>): JSONArray {
        return JSONArray().apply {
            slots.sortedBy { it.period }.forEach { slot ->
                put(
                    JSONObject()
                        .put("period", slot.period)
                        .put("startTime", slot.startTime.toString())
                        .put("endTime", slot.endTime.toString())
                )
            }
        }
    }

    private fun parseLessonTimeProfiles(raw: String): List<LessonTimeProfile> {
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id").ifBlank { UUID.randomUUID().toString() }
                    val name = item.optString("name").ifBlank { "作息表 ${index + 1}" }
                    val slots = parseLessonTimesJson(item.optJSONArray("lessonTimes"))
                    add(LessonTimeProfile(id = id, name = name, slots = slots))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun parseLessonTimesJson(array: JSONArray?): List<LessonTimeSlot> {
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

    private fun normalizeProfiles(profiles: List<LessonTimeProfile>): List<LessonTimeProfile> {
        val normalized = profiles
            .mapIndexed { index, profile ->
                val name = profile.name.ifBlank { "作息表 ${index + 1}" }
                val slots = profile.slots
                    .filter { it.period >= 0 && it.endTime.isAfter(it.startTime) }
                    .map { it.copy(period = ScheduleDefaults.normalizePeriod(it.period)) }
                    .distinctBy { it.period }
                    .sortedBy { it.period }
                profile.copy(
                    id = profile.id.ifBlank { UUID.randomUUID().toString() },
                    name = name,
                    slots = slots
                )
            }
            .distinctBy { it.id }
        return normalized
    }

    fun saveReminderSettings(context: Context, settings: ReminderSettings) {
        val normalized = settings.normalized()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_NOTIFICATIONS_ENABLED, normalized.notificationsEnabled)
            .putBoolean(KEY_ALARM_MODE_ENABLED, normalized.alarmModeEnabled)
            .putBoolean(KEY_VIBRATION_REMINDER_ENABLED, normalized.vibrationReminderEnabled)
            .putBoolean(KEY_SOUND_REMINDER_ENABLED, normalized.soundReminderEnabled)
            .putString(KEY_SOUND_REMINDER_TONE_ID, normalized.soundReminderToneId)
            .putInt(KEY_REMINDER_MINUTES, normalized.reminderMinutesBefore)
            .apply()
    }

    fun loadReminderSettings(context: Context): ReminderSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return ReminderSettings(
            notificationsEnabled = prefs.getBoolean(KEY_NOTIFICATIONS_ENABLED, true),
            alarmModeEnabled = prefs.getBoolean(KEY_ALARM_MODE_ENABLED, false),
            vibrationReminderEnabled = prefs.getBoolean(KEY_VIBRATION_REMINDER_ENABLED, false),
            soundReminderEnabled = prefs.getBoolean(KEY_SOUND_REMINDER_ENABLED, false),
            soundReminderToneId = prefs.getString(KEY_SOUND_REMINDER_TONE_ID, null).orEmpty(),
            reminderMinutesBefore = prefs.getInt(
                KEY_REMINDER_MINUTES,
                ScheduleDefaults.DEFAULT_REMINDER_MINUTES
            )
        ).normalized()
    }

    fun saveLastAlarmSignature(context: Context, signature: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_ALARM_SIGNATURE, signature)
            .apply()
    }

    fun loadLastAlarmSignature(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_ALARM_SIGNATURE, null)
    }

    fun loadDeliveredAlarmSignatures(context: Context): Set<String> {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_DELIVERED_ALARM_SIGNATURES, emptySet())
            ?.toSet()
            ?: emptySet()
    }

    fun saveDeliveredAlarmSignatures(context: Context, signatures: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_DELIVERED_ALARM_SIGNATURES, signatures)
            .apply()
    }

    fun markAlarmDelivered(context: Context, signature: String) {
        val updated = loadDeliveredAlarmSignatures(context).toMutableSet().apply {
            add(signature)
        }
        saveDeliveredAlarmSignatures(context, updated)
    }

    fun clearDeliveredAlarmSignatures(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_DELIVERED_ALARM_SIGNATURES)
            .apply()
    }

    /** 自启动/后台冻结等保活项无法通过 API 检测，只在首次开启闹钟时引导一次，避免每次都打扰。 */
    fun hasPromptedAutoStart(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_START_PROMPTED, false)
    }

    fun setAutoStartPrompted(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_START_PROMPTED, true)
            .apply()
    }

}
