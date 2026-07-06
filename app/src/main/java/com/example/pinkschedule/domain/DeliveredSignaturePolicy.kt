package com.example.pinkschedule.domain

import com.example.pinkschedule.model.CourseItem
import com.example.pinkschedule.model.LessonTimeSlot
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 课表内容变化后，delivered 签名的作废策略（纯函数，可单测）。
 *
 * 原则：已经提醒过的课不因无关改动重复响铃；但用户实际修改了某节课的时间后，
 * 这节课应按新时间重新提醒。签名格式 dayOfWeek|period|lessonDate|className 不含
 * 具体时刻，无法从签名本身区分"时间变没变"，因此需要新旧课表对比。
 */
object DeliveredSignaturePolicy {

    /**
     * 返回应作废（从 delivered 集合移除）的签名：
     * - 未来日期（> today）的签名：基于旧课表，一律作废；
     * - 今天的签名：仅当该课的节次时间被实际修改、该课程本身被新增/修改，
     *   或按新课表计算的提醒时刻仍在未来（时间被挪后，重新武装无重复响铃风险）时作废；
     * - 过去日期的签名不在此处理（由 prune 清理）。
     *
     * oldItems/oldLessonTimes 为 null 表示没有旧数据可比（如进程刚重启），
     * 此时只做保守清理（未来日期 + 新触发时刻在未来的今日签名）。
     */
    fun signaturesToClear(
        delivered: Set<String>,
        oldItems: List<CourseItem>?,
        oldLessonTimes: List<LessonTimeSlot>?,
        newItems: List<CourseItem>,
        newLessonTimes: List<LessonTimeSlot>,
        reminderMinutesBefore: Int,
        now: LocalDateTime
    ): Set<String> {
        if (delivered.isEmpty()) return emptySet()
        val today = now.toLocalDate()
        val newTimeIndex = newLessonTimes.associateBy { it.period }

        val changedPeriods: Set<Int> = if (oldLessonTimes != null) {
            val oldIndex = oldLessonTimes.associateBy { it.period }
            (oldIndex.keys + newTimeIndex.keys)
                .filterTo(mutableSetOf()) { period -> oldIndex[period] != newTimeIndex[period] }
        } else {
            emptySet()
        }
        val changedCourseKeys: Set<Triple<DayOfWeek, Int, String>> = if (oldItems != null) {
            newItems.filterNot { oldItems.contains(it) }
                .mapTo(mutableSetOf()) { Triple(it.dayOfWeek, it.period, it.className) }
        } else {
            emptySet()
        }

        return delivered.filterTo(mutableSetOf()) { signature ->
            val parts = signature.split("|")
            if (parts.size < 4) return@filterTo false
            val day = runCatching { DayOfWeek.valueOf(parts[0]) }.getOrNull() ?: return@filterTo false
            val period = parts[1].toIntOrNull() ?: return@filterTo false
            val lessonDate = runCatching { LocalDate.parse(parts[2]) }.getOrNull() ?: return@filterTo false
            val className = parts.drop(3).joinToString("|")

            if (lessonDate.isAfter(today)) return@filterTo true
            if (lessonDate.isBefore(today)) return@filterTo false

            val timeChanged = period in changedPeriods ||
                Triple(day, period, className) in changedCourseKeys
            val newTriggerInFuture = newTimeIndex[period]?.let { slot ->
                LocalDateTime.of(lessonDate, slot.startTime)
                    .minusMinutes(reminderMinutesBefore.toLong())
                    .isAfter(now)
            } ?: false
            timeChanged || newTriggerInFuture
        }
    }
}
