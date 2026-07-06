package com.example.pinkschedule.reminder

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import com.example.pinkschedule.data.ScheduleRepository
import java.util.concurrent.TimeUnit

/**
 * ColorOS / MIUI 等激进省电 ROM 会周期性冻结或清理 app，导致已注册的 AlarmManager 闹钟
 * 被清掉、前台服务被杀。WorkManager 走 JobScheduler，OEM 更不敢干预，是最难被杀的一层。
 *
 * 本 Worker 周期性（最短 15 分钟）重新全量预排课程提醒：
 * - 若闹钟仍在，rescheduleStoredCourseAlarms 用相同 requestCode + FLAG_UPDATE_CURRENT 覆盖，无副作用；
 * - 若被 ROM 清掉，这里把它们补回来。
 *
 * 这是冗余兜底的一环，不替代 setAlarmClock —— 单一 API 在国产 ROM 上都不可靠，靠多层叠加求稳。
 */
class AlarmWatchdogWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        return runCatching {
            val settings = ScheduleRepository.loadReminderSettings(applicationContext)
            if (!settings.hasEnabledReminder()) {
                Log.i(TAG, "watchdog: course reminders disabled, skip")
                return@runCatching Result.success()
            }
            val result = ReminderCoordinator.onScheduleChanged(applicationContext, "watchdog")
            Log.i(TAG, "watchdog reschedule: ${result.message}")
            Result.success()
        }.getOrElse {
            Log.e(TAG, "watchdog failed", it)
            // 交给 WorkManager 重试；失败不应导致看门狗永久停摆。
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "AlarmWatchdogWorker"
        private const val UNIQUE_NAME = "alarm_watchdog"
        private const val REPEAT_INTERVAL_MINUTES = 15L

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<AlarmWatchdogWorker>(
                REPEAT_INTERVAL_MINUTES, TimeUnit.MINUTES
            )
                .setConstraints(Constraints.NONE)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                // KEEP：已存在则保留原有周期任务，避免每次重排都重置 15 分钟计时。
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
