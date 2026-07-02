package com.example.pinkschedule

import android.app.Application
import com.example.pinkschedule.reminder.SystemAlarmScheduler

class PinkScheduleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SystemAlarmScheduler.rescheduleStoredCourseAlarms(this)
    }
}
