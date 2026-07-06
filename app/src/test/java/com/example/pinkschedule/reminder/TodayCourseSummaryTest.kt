package com.example.pinkschedule.reminder

import com.example.pinkschedule.domain.NoCourseReason
import com.example.pinkschedule.domain.ReminderState
import com.example.pinkschedule.domain.ScheduleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class TodayCourseSummaryTest {

    @Test
    fun `当天课程结束后显示休息提示`() {
        val summary = TodayCourseSummary.foregroundNotificationSnapshot(
            noCourseSnapshot(NoCourseReason.COMPLETED_TODAY)
        )

        assertEquals("今天课程都结束了，辛苦啦，好好休息吧~", summary.text)
        assertEquals(false, summary.showCourseHeader)
    }

    @Test
    fun `当天没有课程时显示无课提示`() {
        val summary = TodayCourseSummary.foregroundNotificationSnapshot(
            noCourseSnapshot(NoCourseReason.NO_COURSES_TODAY)
        )

        assertEquals("今天没有课程安排，好好休息吧~", summary.text)
        assertEquals(false, summary.showCourseHeader)
    }

    private fun noCourseSnapshot(reason: NoCourseReason): ScheduleSnapshot {
        val today = LocalDate.of(2026, 7, 6)
        return ScheduleSnapshot(
            state = ReminderState.NoCourse(today, reason),
            nextTransitionAt = today.plusDays(1).atStartOfDay(),
            upcomingReminders = emptyList(),
            dueReminder = null
        )
    }
}
