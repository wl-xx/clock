package com.example.pinkschedule.domain

import com.example.pinkschedule.model.CourseItem
import com.example.pinkschedule.model.LessonTimeSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class DeliveredSignaturePolicyTest {

    // 2026-07-06 是周一。
    private val monday: LocalDate = LocalDate.of(2026, 7, 6)

    private val slot1 = LessonTimeSlot(1, LocalTime.of(8, 0), LocalTime.of(8, 45))
    private val slot2 = LessonTimeSlot(2, LocalTime.of(8, 55), LocalTime.of(9, 40))

    private val course1 = CourseItem("老师", "一班", DayOfWeek.MONDAY, 1)
    private val course2 = CourseItem("老师", "二班", DayOfWeek.MONDAY, 2)

    private fun sig(period: Int, className: String, date: LocalDate = monday) =
        "MONDAY|$period|$date|$className"

    @Test
    fun `未来日期的签名在任何内容变化后都作废`() {
        val futureSig = sig(1, "一班", monday.plusWeeks(1))
        val toClear = DeliveredSignaturePolicy.signaturesToClear(
            delivered = setOf(futureSig),
            oldItems = listOf(course1), oldLessonTimes = listOf(slot1),
            newItems = listOf(course1), newLessonTimes = listOf(slot1),
            reminderMinutesBefore = 10,
            now = monday.atTime(7, 0)
        )
        assertEquals(setOf(futureSig), toClear)
    }

    @Test
    fun `无关改动不作废今天已响过的课`() {
        // 一班 07:50 已响；07:55 用户改了第 2 节的时间。
        val ringedSig = sig(1, "一班")
        val toClear = DeliveredSignaturePolicy.signaturesToClear(
            delivered = setOf(ringedSig),
            oldItems = listOf(course1, course2),
            oldLessonTimes = listOf(slot1, slot2),
            newItems = listOf(course1, course2),
            newLessonTimes = listOf(slot1, slot2.copy(startTime = LocalTime.of(9, 0))),
            reminderMinutesBefore = 10,
            now = monday.atTime(7, 55)
        )
        assertTrue(toClear.isEmpty())
    }

    @Test
    fun `修改了该节次的时间则今天的签名作废重新提醒`() {
        // 用户测试场景：把第 1 节挪到几分钟后再测。
        val ringedSig = sig(1, "一班")
        val toClear = DeliveredSignaturePolicy.signaturesToClear(
            delivered = setOf(ringedSig),
            oldItems = listOf(course1),
            oldLessonTimes = listOf(slot1),
            newItems = listOf(course1),
            newLessonTimes = listOf(slot1.copy(startTime = LocalTime.of(10, 5), endTime = LocalTime.of(10, 50))),
            reminderMinutesBefore = 10,
            now = monday.atTime(10, 0)
        )
        assertEquals(setOf(ringedSig), toClear)
    }

    @Test
    fun `新增或修改课程本身也作废对应签名`() {
        val ringedSig = sig(1, "一班")
        val toClear = DeliveredSignaturePolicy.signaturesToClear(
            delivered = setOf(ringedSig),
            oldItems = listOf(course1.copy(teacher = "旧老师")),
            oldLessonTimes = listOf(slot1),
            newItems = listOf(course1),
            newLessonTimes = listOf(slot1),
            reminderMinutesBefore = 10,
            now = monday.atTime(7, 55)
        )
        assertEquals(setOf(ringedSig), toClear)
    }

    @Test
    fun `无旧数据时新触发时刻在未来的今日签名保守作废`() {
        // 进程重启后无旧课表可比：课挪到下午（新触发时刻在未来）→ 重新武装，
        // 到点按新时间提醒，无立即重复响铃风险。
        val ringedSig = sig(1, "一班")
        val movedSlot = slot1.copy(startTime = LocalTime.of(15, 0), endTime = LocalTime.of(15, 45))
        val toClear = DeliveredSignaturePolicy.signaturesToClear(
            delivered = setOf(ringedSig),
            oldItems = null, oldLessonTimes = null,
            newItems = listOf(course1), newLessonTimes = listOf(movedSlot),
            reminderMinutesBefore = 10,
            now = monday.atTime(8, 30)
        )
        assertEquals(setOf(ringedSig), toClear)
    }

    @Test
    fun `无旧数据且新触发时刻已过则保留防重复响铃`() {
        val ringedSig = sig(1, "一班")
        val toClear = DeliveredSignaturePolicy.signaturesToClear(
            delivered = setOf(ringedSig),
            oldItems = null, oldLessonTimes = null,
            newItems = listOf(course1), newLessonTimes = listOf(slot1),
            reminderMinutesBefore = 10,
            now = monday.atTime(7, 55)
        )
        assertTrue(toClear.isEmpty())
    }

    @Test
    fun `过去日期与无法解析的签名不在此作废`() {
        val pastSig = sig(1, "一班", monday.minusDays(1))
        val garbage = "debug|whatever"
        val toClear = DeliveredSignaturePolicy.signaturesToClear(
            delivered = setOf(pastSig, garbage),
            oldItems = listOf(course1), oldLessonTimes = listOf(slot1),
            newItems = listOf(course1), newLessonTimes = listOf(slot1.copy(startTime = LocalTime.of(9, 0))),
            reminderMinutesBefore = 10,
            now = monday.atTime(7, 0)
        )
        assertTrue(toClear.isEmpty())
    }
}
