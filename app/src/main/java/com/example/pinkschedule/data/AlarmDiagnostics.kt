package com.example.pinkschedule.data

import android.content.Context
import com.example.pinkschedule.domain.PlannedReminder
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 闹钟诊断落盘。AlarmManager 排程后无法查询，把排程结果与实际触发记录
 * 持久化，用于在设置页区分"没排上（排程 bug）"和"排了但被冻结（ROM 问题）"。
 */
object AlarmDiagnostics {
    private const val PREFS_NAME = "alarm_diagnostics"
    private const val KEY_SCHEDULED = "scheduled_alarms"
    private const val KEY_SCHEDULED_AT = "scheduled_at"
    private const val KEY_SCHEDULE_REASON = "schedule_reason"
    private const val KEY_FIRED = "fired_events"
    private const val KEY_HEARTBEAT = "heartbeat_events"
    private const val MAX_EVENTS = 30

    private val TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

    data class ScheduledEntry(
        val className: String,
        val periodLabel: String,
        val triggerAt: LocalDateTime
    )

    data class FiredEvent(
        val signature: String,
        val source: String,
        val driftMs: Long?,
        val at: LocalDateTime
    )

    data class HeartbeatEvent(
        val kind: String, // scheduled / fired
        val at: LocalDateTime,
        val targetAt: LocalDateTime?
    )

    data class Report(
        val scheduledAt: LocalDateTime?,
        val scheduleReason: String?,
        val scheduled: List<ScheduledEntry>,
        val fired: List<FiredEvent>,
        val heartbeats: List<HeartbeatEvent>
    )

    /** 每次排程后覆盖记录当前实际下发的闹钟列表。 */
    fun recordScheduled(context: Context, reminders: List<PlannedReminder>, reason: String) {
        val array = JSONArray()
        reminders.forEach { reminder ->
            array.put(
                JSONObject()
                    .put("className", reminder.course.className)
                    .put("periodLabel", com.example.pinkschedule.model.ScheduleDefaults.periodLabel(reminder.course.period))
                    .put("triggerAt", reminder.triggerAt.toString())
            )
        }
        prefs(context).edit()
            .putString(KEY_SCHEDULED, array.toString())
            .putString(KEY_SCHEDULED_AT, LocalDateTime.now().toString())
            .putString(KEY_SCHEDULE_REASON, reason)
            .apply()
    }

    /** 记录一次实际触发（含来源与相对预定时刻的漂移）。 */
    fun recordFired(context: Context, signature: String, source: String, driftMs: Long?) {
        appendEvent(context, KEY_FIRED) {
            it.put("signature", signature)
                .put("source", source)
                .put("driftMs", driftMs ?: JSONObject.NULL)
                .put("at", LocalDateTime.now().toString())
        }
    }

    /** 记录心跳链事件（续排/到达），用于排查断链。 */
    fun recordHeartbeat(context: Context, kind: String, targetAt: LocalDateTime?) {
        appendEvent(context, KEY_HEARTBEAT) {
            it.put("kind", kind)
                .put("at", LocalDateTime.now().toString())
                .put("targetAt", targetAt?.toString() ?: JSONObject.NULL)
        }
    }

    fun loadReport(context: Context): Report {
        val p = prefs(context)
        val scheduled = parseArray(p.getString(KEY_SCHEDULED, null)) { obj ->
            ScheduledEntry(
                className = obj.optString("className"),
                periodLabel = obj.optString("periodLabel"),
                triggerAt = LocalDateTime.parse(obj.getString("triggerAt"))
            )
        }
        val fired = parseArray(p.getString(KEY_FIRED, null)) { obj ->
            FiredEvent(
                signature = obj.optString("signature"),
                source = obj.optString("source"),
                driftMs = if (obj.isNull("driftMs")) null else obj.getLong("driftMs"),
                at = LocalDateTime.parse(obj.getString("at"))
            )
        }
        val heartbeats = parseArray(p.getString(KEY_HEARTBEAT, null)) { obj ->
            HeartbeatEvent(
                kind = obj.optString("kind"),
                at = LocalDateTime.parse(obj.getString("at")),
                targetAt = if (obj.isNull("targetAt")) null else LocalDateTime.parse(obj.getString("targetAt"))
            )
        }
        return Report(
            scheduledAt = p.getString(KEY_SCHEDULED_AT, null)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() },
            scheduleReason = p.getString(KEY_SCHEDULE_REASON, null),
            scheduled = scheduled,
            fired = fired.sortedByDescending { it.at },
            heartbeats = heartbeats.sortedByDescending { it.at }
        )
    }

    fun formatTime(time: LocalDateTime): String = time.format(TIME_FORMAT)

    private fun appendEvent(context: Context, key: String, fill: (JSONObject) -> JSONObject) {
        val p = prefs(context)
        val array = runCatching { JSONArray(p.getString(key, null) ?: "[]") }.getOrDefault(JSONArray())
        array.put(fill(JSONObject()))
        // 环形保留最近 MAX_EVENTS 条。
        val trimmed = if (array.length() > MAX_EVENTS) {
            JSONArray().also { out ->
                for (i in array.length() - MAX_EVENTS until array.length()) {
                    out.put(array.get(i))
                }
            }
        } else {
            array
        }
        p.edit().putString(key, trimmed.toString()).apply()
    }

    private fun <T> parseArray(json: String?, map: (JSONObject) -> T): List<T> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            buildList {
                for (i in 0 until array.length()) {
                    runCatching { add(map(array.getJSONObject(i))) }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
