package com.example.pinkschedule.domain

import com.example.pinkschedule.model.CourseItem
import com.example.pinkschedule.model.LessonTimeSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class CourseScheduleCalculatorTest {

    // 2026-07-06 是周一。
    private val monday: LocalDate = LocalDate.of(2026, 7, 6)

    private val slot1 = LessonTimeSlot(1, LocalTime.of(8, 0), LocalTime.of(8, 45))
    private val slot2 = LessonTimeSlot(2, LocalTime.of(8, 55), LocalTime.of(9, 40))
    private val slots = listOf(slot1, slot2)

    private fun course(
        day: DayOfWeek = DayOfWeek.MONDAY,
        period: Int = 1,
        className: String = "一班"
    ) = CourseItem(teacher = "老师", className = className, dayOfWeek = day, period = period)

    private fun snapshot(
        items: List<CourseItem>,
        now: LocalDateTime,
        minutesBefore: Int = 10,
        delivered: Set<String> = emptySet()
    ) = CourseScheduleCalculator.snapshot(
        items = items,
        lessonTimes = slots,
        reminderMinutesBefore = minutesBefore,
        delivered = delivered,
        now = now
    )

    // ---- 状态判定 ----

    @Test
    fun `无课表时为 NoCourse 且午夜为唯一边界`() {
        val now = monday.atTime(12, 0)
        val snap = snapshot(emptyList(), now)
        val state = snap.state as ReminderState.NoCourse
        assertEquals(NoCourseReason.NO_COURSES_TODAY, state.reason)
        assertEquals(monday.plusDays(1).atStartOfDay(), snap.nextTransitionAt)
        assertTrue(snap.upcomingReminders.isEmpty())
        assertNull(snap.dueReminder)
    }

    @Test
    fun `上课前为 CountingDown 且边界取提醒点`() {
        val now = monday.atTime(7, 0)
        val snap = snapshot(listOf(course()), now)
        val state = snap.state as ReminderState.CountingDown
        assertEquals(monday.atTime(8, 0), state.startAt)
        // 下一边界 = 提醒点 07:50（早于上课 08:00 和午夜）。
        assertEquals(monday.atTime(7, 50), snap.nextTransitionAt)
    }

    @Test
    fun `课中为 InClass 且边界取下课时刻`() {
        val now = monday.atTime(8, 20)
        val snap = snapshot(listOf(course()), now)
        val state = snap.state as ReminderState.InClass
        assertEquals(monday.atTime(8, 46), state.endAt)
        assertEquals(monday.atTime(8, 46), snap.nextTransitionAt)
    }

    @Test
    fun `上课整点时刻算 InClass 不算 CountingDown`() {
        val now = monday.atTime(8, 0)
        val snap = snapshot(listOf(course()), now)
        assertTrue(snap.state is ReminderState.InClass)
    }

    @Test
    fun `终点时间所在分钟仍算 InClass 下一分钟才结束`() {
        val duringEndMinute = snapshot(listOf(course()), monday.atTime(8, 45, 30))
        val state = duringEndMinute.state as ReminderState.InClass
        assertEquals(monday.atTime(8, 46), state.endAt)
        assertEquals(monday.atTime(8, 46), duringEndMinute.nextTransitionAt)

        val afterEndMinute = snapshot(listOf(course()), monday.atTime(8, 46))
        val noCourse = afterEndMinute.state as ReminderState.NoCourse
        assertEquals(NoCourseReason.COMPLETED_TODAY, noCourse.reason)
    }

    @Test
    fun `今日课程全部结束后为 NoCourse 而非显示明天的课`() {
        val now = monday.atTime(10, 0)
        val snap = snapshot(listOf(course(), course(day = DayOfWeek.TUESDAY)), now)
        val state = snap.state as ReminderState.NoCourse
        assertEquals(NoCourseReason.COMPLETED_TODAY, state.reason)
        // 但 upcoming 排的是明天的课。
        assertEquals(monday.plusDays(1).atTime(8, 0), snap.upcomingReminders.first().lessonStart)
    }

    @Test
    fun `跨天后次日凌晨恢复 CountingDown`() {
        val now = monday.plusDays(1).atTime(0, 1)
        val snap = snapshot(listOf(course(day = DayOfWeek.TUESDAY)), now)
        val state = snap.state as ReminderState.CountingDown
        assertEquals(monday.plusDays(1).atTime(8, 0), state.startAt)
    }

    @Test
    fun `本周该天已过时取下周同天`() {
        val now = monday.atTime(12, 0) // 周一中午，周一的课已结束
        val snap = snapshot(listOf(course()), now)
        assertEquals(monday.plusWeeks(1).atTime(8, 0), snap.upcomingReminders.first().lessonStart)
    }

    // ---- 提醒排程 ----

    @Test
    fun `单节课预排满 12 个未来周次且互不重复`() {
        val now = monday.atTime(6, 0)
        val snap = snapshot(listOf(course()), now)
        assertEquals(12, snap.upcomingReminders.size)
        assertEquals(12, snap.upcomingReminders.map { it.deliverySignature() }.toSet().size)
        // 递增的每周日期。
        snap.upcomingReminders.forEachIndexed { i, r ->
            assertEquals(monday.plusWeeks(i.toLong()).atTime(8, 0), r.lessonStart)
        }
    }

    @Test
    fun `提前提醒时刻已过但未上课时改为立即提醒`() {
        val now = monday.atTime(7, 55) // 已过 07:50 提醒点，未到 08:00
        val snap = snapshot(listOf(course()), now)
        val first = snap.upcomingReminders.first()
        assertEquals(now.plusSeconds(2), first.triggerAt)
        assertEquals(monday.atTime(8, 0), first.lessonStart)
    }

    @Test
    fun `提醒窗口内 dueReminder 非空`() {
        val now = monday.atTime(7, 55)
        val snap = snapshot(listOf(course()), now)
        assertNotNull(snap.dueReminder)
        assertEquals(monday.atTime(8, 0), snap.dueReminder!!.lessonStart)
    }

    @Test
    fun `已投递签名的课不再出现在 upcoming 和 due 中`() {
        val now = monday.atTime(7, 55)
        val sig = "MONDAY|1|$monday|一班"
        val snap = snapshot(listOf(course()), now, delivered = setOf(sig))
        assertNull(snap.dueReminder)
        // 本周被过滤，第一个是下周。
        assertEquals(monday.plusWeeks(1).atTime(8, 0), snap.upcomingReminders.first().lessonStart)
    }

    @Test
    fun `提醒窗口外 dueReminder 为空`() {
        val now = monday.atTime(7, 0)
        val snap = snapshot(listOf(course()), now)
        assertNull(snap.dueReminder)
    }

    @Test
    fun `提前 0 分钟时提醒点即上课时刻`() {
        val now = monday.atTime(7, 0)
        val snap = snapshot(listOf(course()), now, minutesBefore = 0)
        assertEquals(monday.atTime(8, 0), snap.upcomingReminders.first().triggerAt)
        assertNull(snap.dueReminder)
    }

    @Test
    fun `两节课时 upcoming 按触发时间交错排序`() {
        val now = monday.atTime(6, 0)
        val snap = snapshot(listOf(course(period = 1), course(period = 2, className = "二班")), now)
        assertEquals(monday.atTime(7, 50), snap.upcomingReminders[0].triggerAt)
        assertEquals(monday.atTime(8, 45), snap.upcomingReminders[1].triggerAt)
        assertTrue(snap.upcomingReminders.zipWithNext().all { (a, b) -> !a.triggerAt.isAfter(b.triggerAt) })
    }

    @Test
    fun `缺少节次作息的课程被忽略`() {
        val now = monday.atTime(6, 0)
        val snap = snapshot(listOf(course(period = 99)), now)
        val state = snap.state as ReminderState.NoCourse
        assertEquals(NoCourseReason.NO_COURSES_TODAY, state.reason)
        assertTrue(snap.upcomingReminders.isEmpty())
    }

    // ---- 边界计算 ----

    @Test
    fun `nextTransitionAt 恒在 now 之后`() {
        val times = listOf(
            monday.atTime(0, 0), monday.atTime(7, 50), monday.atTime(8, 0),
            monday.atTime(8, 45), monday.atTime(8, 46), monday.atTime(23, 59, 59)
        )
        times.forEach { now ->
            val snap = snapshot(listOf(course()), now)
            assertTrue("now=$now → ${snap.nextTransitionAt}", snap.nextTransitionAt.isAfter(now))
        }
    }

    @Test
    fun `课中时边界不会取到明天午夜之外`() {
        val now = monday.atTime(21, 0)
        val snap = snapshot(listOf(course(day = DayOfWeek.FRIDAY)), now)
        // 周五的课很远，但午夜边界保证跨天刷新。
        assertEquals(monday.plusDays(1).atStartOfDay(), snap.nextTransitionAt)
    }

    // ---- 签名格式兼容 ----

    @Test
    fun `deliverySignature 与历史格式兼容`() {
        val now = monday.atTime(6, 0)
        val r = snapshot(listOf(course()), now).upcomingReminders.first()
        assertEquals("MONDAY|1|2026-07-06|一班", r.deliverySignature())
    }

    @Test
    fun `signature 含触发时刻用于识别提前分钟数变化`() {
        val now = monday.atTime(6, 0)
        val r = snapshot(listOf(course()), now).upcomingReminders.first()
        assertEquals("MONDAY|1|2026-07-06|07:50|一班", r.signature())
    }
}
