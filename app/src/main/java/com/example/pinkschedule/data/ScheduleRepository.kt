package com.example.pinkschedule.data

import android.content.Context
import com.example.pinkschedule.model.CourseItem
import com.example.pinkschedule.model.LessonTimeSlot
import com.example.pinkschedule.model.ReminderSettings
import com.example.pinkschedule.model.ScheduleDefaults
import com.example.pinkschedule.model.WeeklySchedule
import java.time.DayOfWeek
import java.time.LocalTime

object ScheduleRepository {
    private const val PREFS_NAME = "schedule_store"
    private const val KEY_SCHEDULE = "teacher_schedule"
    private const val KEY_LESSON_TIMES = "lesson_times"
    private const val KEY_ALARM_MODE_ENABLED = "alarm_mode_enabled"
    private const val KEY_REMINDER_MINUTES = "reminder_minutes"
    private const val KEY_LAST_ALARM_SIGNATURE = "last_alarm_signature"
    private const val KEY_DELIVERED_ALARM_SIGNATURES = "delivered_alarm_signatures"
    private const val KEY_AUTO_START_PROMPTED = "auto_start_prompted"

    fun save(context: Context, schedule: WeeklySchedule) {
        val serialized = schedule.items.joinToString("\n") { item ->
            listOf(
                item.teacher,
                item.className,
                item.dayOfWeek.name,
                item.period.toString(),
                item.courseName
            ).joinToString("|") { escape(it) }
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SCHEDULE, serialized)
            .apply()
    }

    fun load(context: Context): List<CourseItem> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SCHEDULE, null) ?: return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val parts = splitEscaped(line)
            if (parts.size < 4) return@mapNotNull null
            val dayOfWeek = runCatching { DayOfWeek.valueOf(parts[2]) }.getOrNull() ?: return@mapNotNull null
            val period = parts[3].toIntOrNull() ?: return@mapNotNull null
            CourseItem(
                teacher = parts[0],
                className = parts[1],
                dayOfWeek = dayOfWeek,
                period = period,
                courseName = parts.getOrNull(4).orEmpty().ifBlank { ScheduleDefaults.DEFAULT_COURSE_NAME }
            )
        }.toList()
    }

    fun saveLessonTimes(context: Context, lessonTimes: List<LessonTimeSlot>) {
        val serialized = lessonTimes
            .sortedBy { it.period }
            .joinToString("\n") { slot ->
                listOf(
                    slot.period.toString(),
                    slot.startTime.toString(),
                    slot.endTime.toString()
                ).joinToString("|")
            }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LESSON_TIMES, serialized)
            .apply()
    }

    fun loadLessonTimes(context: Context): List<LessonTimeSlot> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LESSON_TIMES, null)
            ?: return ScheduleDefaults.defaultLessonTimeSlots()
        val parsed = raw.lineSequence().mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size < 3) return@mapNotNull null
            val period = parts[0].toIntOrNull() ?: return@mapNotNull null
            val startTime = runCatching { LocalTime.parse(parts[1]) }.getOrNull() ?: return@mapNotNull null
            val endTime = runCatching { LocalTime.parse(parts[2]) }.getOrNull() ?: return@mapNotNull null
            LessonTimeSlot(period = period, startTime = startTime, endTime = endTime)
        }.toList()
        return if (parsed.isEmpty()) ScheduleDefaults.defaultLessonTimeSlots() else parsed.sortedBy { it.period }
    }

    fun saveReminderSettings(context: Context, settings: ReminderSettings) {
        val normalized = settings.normalized()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ALARM_MODE_ENABLED, normalized.alarmModeEnabled)
            .putInt(KEY_REMINDER_MINUTES, normalized.reminderMinutesBefore)
            .apply()
    }

    fun loadReminderSettings(context: Context): ReminderSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return ReminderSettings(
            alarmModeEnabled = prefs.getBoolean(KEY_ALARM_MODE_ENABLED, false),
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

    private fun escape(value: String): String {
        return value.replace("\\", "\\\\").replace("|", "\\|")
    }

    private fun splitEscaped(line: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var escaping = false
        line.forEach { ch ->
            when {
                escaping -> {
                    current.append(ch)
                    escaping = false
                }
                ch == '\\' -> escaping = true
                ch == '|' -> {
                    parts += current.toString()
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        if (escaping) {
            current.append('\\')
        }
        parts += current.toString()
        return parts
    }
}
