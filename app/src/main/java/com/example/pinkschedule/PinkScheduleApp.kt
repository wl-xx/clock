package com.example.pinkschedule

import android.app.Application
import com.example.pinkschedule.reminder.ReminderCoordinator

class PinkScheduleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ReminderCoordinator.onScheduleChanged(this, "app_start")
    }
}
