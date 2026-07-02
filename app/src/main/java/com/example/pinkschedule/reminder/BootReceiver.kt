package com.example.pinkschedule.reminder

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.pinkschedule.data.ScheduleRepository

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in supportedActions) return

        val items = ScheduleRepository.load(context)
        val lessonTimes = ScheduleRepository.loadLessonTimes(context)
        val settings = ScheduleRepository.loadReminderSettings(context)

        SystemAlarmScheduler.syncCourseAlarms(
            context = context,
            items = items,
            lessonTimes = lessonTimes,
            settings = settings
        )
    }

    companion object {
        private val supportedActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        )
    }
}
