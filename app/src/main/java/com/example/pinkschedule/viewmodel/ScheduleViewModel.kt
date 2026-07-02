package com.example.pinkschedule.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.pinkschedule.data.ScheduleRepository
import com.example.pinkschedule.data.ScheduleTransfer
import com.example.pinkschedule.model.CourseItem
import com.example.pinkschedule.model.LessonTimeSlot
import com.example.pinkschedule.model.ReminderSettings
import com.example.pinkschedule.model.ScheduleDefaults
import com.example.pinkschedule.model.WeeklySchedule
import com.example.pinkschedule.reminder.SystemAlarmScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime

data class ReminderSettingsAction(
    val message: String?,
    val requestExactAlarmPermission: Boolean = false,
    val requestNotificationPermission: Boolean = false,
    val requestFullScreenIntentPermission: Boolean = false,
    val requestBatteryOptimization: Boolean = false,
    val requestAutoStart: Boolean = false
)

data class ScheduleUiState(
    val schedule: WeeklySchedule = WeeklySchedule(
        teacher = ScheduleDefaults.DEFAULT_TEACHER,
        items = emptyList()
    ),
    val lessonTimes: List<LessonTimeSlot> = ScheduleDefaults.defaultLessonTimeSlots(),
    val reminderSettings: ReminderSettings = ReminderSettings(),
    val exactAlarmPermissionGranted: Boolean = true,
    val notificationPermissionGranted: Boolean = true,
    val fullScreenIntentPermissionGranted: Boolean = true,
    val message: String? = null
)

class ScheduleViewModel(
    application: Application
) : AndroidViewModel(application) {
    private val context: Application = getApplication()

    private val _uiState = MutableStateFlow(ScheduleUiState())
    val uiState: StateFlow<ScheduleUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            hydrateLocalState()
        }
    }

    fun refreshExactAlarmPermission() {
        _uiState.value = _uiState.value.copy(
            exactAlarmPermissionGranted = SystemAlarmScheduler.canUseExactAlarms(context),
            notificationPermissionGranted = SystemAlarmScheduler.canPostNotifications(context),
            fullScreenIntentPermissionGranted = SystemAlarmScheduler.canUseFullScreenIntent(context)
        )
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    fun upsertCourse(original: CourseItem?, edited: CourseItem): String? {
        val currentItems = _uiState.value.schedule.items.toMutableList()
        val normalized = edited
        val conflictingItem = currentItems.firstOrNull { item ->
            item != original &&
                item.dayOfWeek == normalized.dayOfWeek &&
                item.period == normalized.period
        }
        if (conflictingItem != null) {
            return "${dayOfWeekDisplay(conflictingItem.dayOfWeek)} ${conflictingItem.displayTimeLabel()} 已存在课程。"
        }
        val index = if (original == null) -1 else currentItems.indexOf(original)
        if (index >= 0) {
            currentItems[index] = normalized
        } else {
            currentItems += normalized
        }
        val sorted = normalizeCourses(currentItems)
        val schedule = _uiState.value.schedule.copy(items = sorted)
        val lessonTimes = mergedLessonTimesFor(sorted)
        return runCatching {
            ScheduleRepository.clearDeliveredAlarmSignatures(context)
            ScheduleRepository.saveLastAlarmSignature(context, null)
            persistSchedule(
                schedule = schedule,
                lessonTimes = lessonTimes,
                message = null
            )
            null
        }.getOrElse { "保存失败：${it.message ?: "未知错误"}" }
    }

    fun deleteCourse(course: CourseItem) {
        val remaining = normalizeCourses(_uiState.value.schedule.items.filterNot { it == course })
        val schedule = _uiState.value.schedule.copy(items = remaining)
        val lessonTimes = mergedLessonTimesFor(remaining)
        runCatching {
            persistSchedule(schedule, lessonTimes, null)
        }.onFailure {
            _uiState.value = _uiState.value.copy(message = "删除失败：${it.message ?: "未知错误"}")
        }
    }

    fun updateLessonTime(period: Int, startTime: LocalTime, endTime: LocalTime) {
        if (!endTime.isAfter(startTime)) {
            _uiState.value = _uiState.value.copy(message = "结束时间必须晚于开始时间。")
            return
        }
        val updated = _uiState.value.lessonTimes
            .filterNot { it.period == period }
            .plus(LessonTimeSlot(period = period, startTime = startTime, endTime = endTime))
            .sortedBy { it.period }
        runCatching {
            ScheduleRepository.saveLessonTimes(context, updated)
            ScheduleRepository.clearDeliveredAlarmSignatures(context)
            ScheduleRepository.saveLastAlarmSignature(context, null)
            val reminderSettings = _uiState.value.reminderSettings
            syncLocalState(
                schedule = _uiState.value.schedule,
                lessonTimes = updated,
                reminderSettings = reminderSettings,
                exactAlarmPermissionGranted = _uiState.value.exactAlarmPermissionGranted,
                notificationPermissionGranted = _uiState.value.notificationPermissionGranted,
                fullScreenIntentPermissionGranted = _uiState.value.fullScreenIntentPermissionGranted,
                message = if (reminderSettings.alarmModeEnabled) {
                    refreshScheduledAlarms(
                        schedule = _uiState.value.schedule,
                        lessonTimes = updated,
                        settings = reminderSettings
                    ).message
                } else {
                    null
                }
            )
        }.onFailure {
            _uiState.value = _uiState.value.copy(message = "保存节次时间失败：${it.message ?: "未知错误"}")
        }
    }

    fun addLessonTime() {
        val current = _uiState.value.lessonTimes.sortedBy { it.period }
        val nextPeriod = (current.maxOfOrNull { it.period } ?: ScheduleDefaults.MIN_LESSON_COUNT) + 1
        val previousEnd = current.lastOrNull()?.endTime ?: LocalTime.of(20, 40)
        val startTime = previousEnd.plusMinutes(10)
        val endTime = startTime.plusMinutes(45)
        val updated = current
            .plus(LessonTimeSlot(period = nextPeriod, startTime = startTime, endTime = endTime))
            .sortedBy { it.period }
        saveLessonTimes(updated, "已新增${nextPeriod}节课时间。")
    }

    fun deleteLessonTime(period: Int) {
        val current = _uiState.value.lessonTimes.sortedBy { it.period }
        if (period <= ScheduleDefaults.MIN_LESSON_COUNT || current.size <= ScheduleDefaults.MIN_LESSON_COUNT) {
            _uiState.value = _uiState.value.copy(message = "课程时间最少保留 ${ScheduleDefaults.MIN_LESSON_COUNT} 节。")
            return
        }
        if (_uiState.value.schedule.items.any { it.period == period }) {
            _uiState.value = _uiState.value.copy(message = "第${period}节已有课程，不能删除该节次时间。")
            return
        }
        val updated = current.filterNot { it.period == period }.sortedBy { it.period }
        saveLessonTimes(updated, "已删除第${period}节课时间。")
    }

    fun updateReminderSettings(alarmModeEnabled: Boolean, minutesBefore: Int): ReminderSettingsAction {
        if (minutesBefore < 0) {
            return ReminderSettingsAction(
                message = "提醒前时间不能小于 0 分钟。"
            )
        }
        val hasExactAlarmPermission = SystemAlarmScheduler.canUseExactAlarms(context)
        if (alarmModeEnabled && !hasExactAlarmPermission) {
            val disabledSettings = ReminderSettings(
                alarmModeEnabled = false,
                reminderMinutesBefore = minutesBefore
            ).normalized()
            return runCatching {
                ScheduleRepository.saveReminderSettings(context, disabledSettings)
                syncLocalState(
                    schedule = _uiState.value.schedule,
                    lessonTimes = _uiState.value.lessonTimes,
                    reminderSettings = disabledSettings,
                    exactAlarmPermissionGranted = false,
                    notificationPermissionGranted = SystemAlarmScheduler.canPostNotifications(context),
                    fullScreenIntentPermissionGranted = SystemAlarmScheduler.canUseFullScreenIntent(context),
                    message = "未授予精确闹钟权限，课程提醒无法保证准时，请先完成授权。"
                )
                ReminderSettingsAction(
                    message = "未授予精确闹钟权限，课程提醒无法保证准时，请先完成授权。",
                    requestExactAlarmPermission = true
                )
            }.getOrElse {
                ReminderSettingsAction(message = "保存提醒设置失败：${it.message ?: "未知错误"}")
            }
        }
        val hasNotificationPermission = SystemAlarmScheduler.canPostNotifications(context)
        if (alarmModeEnabled && !hasNotificationPermission) {
            val disabledSettings = ReminderSettings(
                alarmModeEnabled = false,
                reminderMinutesBefore = minutesBefore
            ).normalized()
            return runCatching {
                ScheduleRepository.saveReminderSettings(context, disabledSettings)
                syncLocalState(
                    schedule = _uiState.value.schedule,
                    lessonTimes = _uiState.value.lessonTimes,
                    reminderSettings = disabledSettings,
                    exactAlarmPermissionGranted = hasExactAlarmPermission,
                    notificationPermissionGranted = false,
                    fullScreenIntentPermissionGranted = SystemAlarmScheduler.canUseFullScreenIntent(context),
                    message = "未授予通知权限，提醒无法显示，请先完成授权。"
                )
                ReminderSettingsAction(
                    message = "未授予通知权限，提醒无法显示，请先完成授权。",
                    requestNotificationPermission = true
                )
            }.getOrElse {
                ReminderSettingsAction(message = "保存提醒设置失败：${it.message ?: "未知错误"}")
            }
        }
        val hasFullScreenIntentPermission = SystemAlarmScheduler.canUseFullScreenIntent(context)
        if (alarmModeEnabled && !hasFullScreenIntentPermission) {
            val disabledSettings = ReminderSettings(
                alarmModeEnabled = false,
                reminderMinutesBefore = minutesBefore
            ).normalized()
            return runCatching {
                ScheduleRepository.saveReminderSettings(context, disabledSettings)
                syncLocalState(
                    schedule = _uiState.value.schedule,
                    lessonTimes = _uiState.value.lessonTimes,
                    reminderSettings = disabledSettings,
                    exactAlarmPermissionGranted = hasExactAlarmPermission,
                    notificationPermissionGranted = hasNotificationPermission,
                    fullScreenIntentPermissionGranted = false,
                    message = "未授予全屏闹钟权限，锁屏时无法像系统闹钟一样弹出，请先完成授权。"
                )
                ReminderSettingsAction(
                    message = "未授予全屏闹钟权限，锁屏时无法像系统闹钟一样弹出，请先完成授权。",
                    requestFullScreenIntentPermission = true
                )
            }.getOrElse {
                ReminderSettingsAction(message = "保存提醒设置失败：${it.message ?: "未知错误"}")
            }
        }
        // 电池优化白名单是 Doze 深度休眠下闹钟能否准时触发的决定性因素。
        // 与其它权限不同，它不阻止闹钟排程，但未加入白名单时必须主动弹系统申请框，
        // 否则息屏放置时系统会推迟 setAlarmClock 的投递（表现为“亮屏才响”）。
        val ignoringBatteryOptimizations = SystemAlarmScheduler.isIgnoringBatteryOptimizations(context)
        val settings = ReminderSettings(
            alarmModeEnabled = alarmModeEnabled,
            reminderMinutesBefore = minutesBefore
        ).normalized()
        return runCatching {
            ScheduleRepository.clearDeliveredAlarmSignatures(context)
            ScheduleRepository.saveReminderSettings(context, settings)
            val alarmMessage = if (settings.alarmModeEnabled) {
                refreshScheduledAlarms(
                    schedule = _uiState.value.schedule,
                    lessonTimes = _uiState.value.lessonTimes,
                    settings = settings
                ).message
            } else {
                "已关闭闹钟模式。"
            }
            val needBatteryPrompt = settings.alarmModeEnabled && !ignoringBatteryOptimizations
            // 自启动/后台冻结等保活项无法用 API 检测，只在首次开启闹钟、且电池优化不需要弹框时引导一次，
            // 避免与电池优化系统框叠加。
            val needAutoStartPrompt = settings.alarmModeEnabled &&
                !needBatteryPrompt &&
                !ScheduleRepository.hasPromptedAutoStart(context)
            if (needAutoStartPrompt) {
                ScheduleRepository.setAutoStartPrompted(context)
            }
            val finalMessage = when {
                needBatteryPrompt ->
                    "${alarmMessage.orEmpty()} 为保证息屏时准时响铃，请在弹出的系统对话框中允许忽略电池优化。"
                needAutoStartPrompt ->
                    "${alarmMessage.orEmpty()} 为防止系统在息屏时冻结应用，请在打开的设置页允许本应用自启动与后台运行。"
                else -> alarmMessage
            }
            syncLocalState(
                schedule = _uiState.value.schedule,
                lessonTimes = _uiState.value.lessonTimes,
                reminderSettings = settings,
                exactAlarmPermissionGranted = hasExactAlarmPermission,
                notificationPermissionGranted = hasNotificationPermission,
                fullScreenIntentPermissionGranted = hasFullScreenIntentPermission,
                message = finalMessage
            )
            ReminderSettingsAction(
                message = finalMessage,
                requestBatteryOptimization = needBatteryPrompt,
                requestAutoStart = needAutoStartPrompt
            )
        }.getOrElse { ReminderSettingsAction(message = "保存提醒设置失败：${it.message ?: "未知错误"}") }
    }

    fun refreshScheduledAlarmsNow() {
        _uiState.value = _uiState.value.copy(
            message = runCatching {
                refreshScheduledAlarms(
                    schedule = _uiState.value.schedule,
                    lessonTimes = _uiState.value.lessonTimes,
                    settings = _uiState.value.reminderSettings
                ).message
            }.getOrElse {
                "同步提醒失败：${it.message ?: "未知错误"}"
            }
        )
    }

    fun exportScheduleJson(): String {
        val state = _uiState.value
        return ScheduleTransfer.toJson(state.schedule, state.lessonTimes)
    }

    fun importScheduleJson(raw: String): String? {
        return runCatching {
            val payload = ScheduleTransfer.fromJson(raw)
            val normalizedItems = normalizeCourses(payload.schedule.items)
            val lessonTimes = mergedLessonTimesFor(normalizedItems, payload.lessonTimes)
            val schedule = WeeklySchedule(
                teacher = payload.schedule.teacher.ifBlank { ScheduleDefaults.DEFAULT_TEACHER },
                items = normalizedItems
            )
            ScheduleRepository.saveLessonTimes(context, lessonTimes)
            ScheduleRepository.save(context, schedule)
            ScheduleRepository.clearDeliveredAlarmSignatures(context)
            ScheduleRepository.saveLastAlarmSignature(context, null)
            val reminderSettings = _uiState.value.reminderSettings
            val message = if (reminderSettings.alarmModeEnabled) {
                refreshScheduledAlarms(
                    schedule = schedule,
                    lessonTimes = lessonTimes,
                    settings = reminderSettings
                ).message
            } else {
                "课程表已导入。"
            }
            syncLocalState(
                schedule = schedule,
                lessonTimes = lessonTimes,
                reminderSettings = reminderSettings,
                exactAlarmPermissionGranted = _uiState.value.exactAlarmPermissionGranted,
                notificationPermissionGranted = _uiState.value.notificationPermissionGranted,
                fullScreenIntentPermissionGranted = _uiState.value.fullScreenIntentPermissionGranted,
                message = message
            )
            null
        }.getOrElse {
            "导入失败：${it.message ?: "文件格式不正确"}"
        }
    }

    private suspend fun hydrateLocalState() {
        val storedSchedule = withContext(Dispatchers.IO) {
            normalizeCourses(ScheduleRepository.load(context))
        }
        val lessonTimes = withContext(Dispatchers.IO) {
            mergedLessonTimesFor(storedSchedule, ScheduleRepository.loadLessonTimes(context))
        }
        val reminderSettings = withContext(Dispatchers.IO) {
            ScheduleRepository.loadReminderSettings(context)
        }
        if (storedSchedule.isNotEmpty()) {
            val schedule = WeeklySchedule(
                teacher = storedSchedule.first().teacher.ifBlank { ScheduleDefaults.DEFAULT_TEACHER },
                items = storedSchedule
            )
            withContext(Dispatchers.IO) {
                ScheduleRepository.save(context, schedule)
            }
            _uiState.value = ScheduleUiState(
                schedule = schedule,
                lessonTimes = lessonTimes,
                reminderSettings = reminderSettings,
                exactAlarmPermissionGranted = SystemAlarmScheduler.canUseExactAlarms(context),
                notificationPermissionGranted = SystemAlarmScheduler.canPostNotifications(context),
                fullScreenIntentPermissionGranted = SystemAlarmScheduler.canUseFullScreenIntent(context),
            )
        } else {
            _uiState.value = ScheduleUiState(
                lessonTimes = lessonTimes,
                reminderSettings = reminderSettings,
                exactAlarmPermissionGranted = SystemAlarmScheduler.canUseExactAlarms(context),
                notificationPermissionGranted = SystemAlarmScheduler.canPostNotifications(context),
                fullScreenIntentPermissionGranted = SystemAlarmScheduler.canUseFullScreenIntent(context),
            )
        }
    }

    private fun persistSchedule(
        schedule: WeeklySchedule,
        lessonTimes: List<LessonTimeSlot>,
        message: String?
    ) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                ScheduleRepository.saveLessonTimes(context, lessonTimes)
                ScheduleRepository.save(context, schedule)
            }
            val reminderSettings = _uiState.value.reminderSettings
            val alarmMessage = message ?: refreshScheduledAlarms(
                schedule = schedule,
                lessonTimes = lessonTimes,
                settings = reminderSettings
            ).message
            syncLocalState(
                schedule = schedule,
                lessonTimes = lessonTimes,
                reminderSettings = reminderSettings,
                exactAlarmPermissionGranted = _uiState.value.exactAlarmPermissionGranted,
                notificationPermissionGranted = _uiState.value.notificationPermissionGranted,
                fullScreenIntentPermissionGranted = _uiState.value.fullScreenIntentPermissionGranted,
                message = alarmMessage
            )
        }
    }

    private fun syncLocalState(
        schedule: WeeklySchedule,
        lessonTimes: List<LessonTimeSlot>,
        reminderSettings: ReminderSettings,
        exactAlarmPermissionGranted: Boolean,
        notificationPermissionGranted: Boolean,
        fullScreenIntentPermissionGranted: Boolean,
        message: String?
    ) {
        _uiState.value = _uiState.value.copy(
            schedule = schedule,
            lessonTimes = lessonTimes,
            reminderSettings = reminderSettings,
            exactAlarmPermissionGranted = exactAlarmPermissionGranted,
            notificationPermissionGranted = notificationPermissionGranted,
            fullScreenIntentPermissionGranted = fullScreenIntentPermissionGranted,
            message = message
        )
    }

    private fun refreshScheduledAlarms(
        schedule: WeeklySchedule,
        lessonTimes: List<LessonTimeSlot>,
        settings: ReminderSettings
    ): SystemAlarmScheduler.AlarmResult {
        return SystemAlarmScheduler.syncCourseAlarms(
            context = context,
            items = schedule.items,
            lessonTimes = lessonTimes,
            settings = settings
        )
    }

    private fun saveLessonTimes(updated: List<LessonTimeSlot>, message: String?) {
        runCatching {
            ScheduleRepository.saveLessonTimes(context, updated)
            ScheduleRepository.clearDeliveredAlarmSignatures(context)
            ScheduleRepository.saveLastAlarmSignature(context, null)
            val reminderSettings = _uiState.value.reminderSettings
            syncLocalState(
                schedule = _uiState.value.schedule,
                lessonTimes = updated,
                reminderSettings = reminderSettings,
                exactAlarmPermissionGranted = _uiState.value.exactAlarmPermissionGranted,
                notificationPermissionGranted = _uiState.value.notificationPermissionGranted,
                fullScreenIntentPermissionGranted = _uiState.value.fullScreenIntentPermissionGranted,
                message = if (reminderSettings.alarmModeEnabled) {
                    refreshScheduledAlarms(
                        schedule = _uiState.value.schedule,
                        lessonTimes = updated,
                        settings = reminderSettings
                    ).message
                } else {
                    message
                }
            )
        }.onFailure {
            _uiState.value = _uiState.value.copy(message = "保存节次时间失败：${it.message ?: "未知错误"}")
        }
    }

    private fun mergedLessonTimesFor(
        items: List<CourseItem>,
        current: List<LessonTimeSlot> = _uiState.value.lessonTimes
    ): List<LessonTimeSlot> {
        return ScheduleDefaults.mergeLessonTimeSlots(current, items.map { it.period })
    }

    private fun normalizeCourses(items: List<CourseItem>): List<CourseItem> {
        return items.sortedWith(compareBy({ it.dayOfWeek.value }, { it.period }, { it.className }))
    }

    private fun dayOfWeekDisplay(dayOfWeek: java.time.DayOfWeek): String {
        return when (dayOfWeek.value) {
            1 -> "星期一"
            2 -> "星期二"
            3 -> "星期三"
            4 -> "星期四"
            5 -> "星期五"
            6 -> "星期六"
            else -> "星期日"
        }
    }
}
