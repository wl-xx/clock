package com.example.pinkschedule.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.pinkschedule.data.ScheduleRepository
import com.example.pinkschedule.data.AppDataTransfer
import com.example.pinkschedule.model.CourseItem
import com.example.pinkschedule.model.LessonTimeProfile
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
    val requestBatteryOptimization: Boolean = false,
    val requestAutoStart: Boolean = false
)

data class ScheduleUiState(
    val schedule: WeeklySchedule = WeeklySchedule(
        teacher = ScheduleDefaults.DEFAULT_TEACHER,
        items = emptyList()
    ),
    val lessonTimes: List<LessonTimeSlot> = emptyList(),
    val lessonTimeProfiles: List<LessonTimeProfile> = emptyList(),
    val activeLessonTimeProfileId: String = "",
    val classPresets: List<String> = emptyList(),
    val reminderSettings: ReminderSettings = ReminderSettings(),
    val exactAlarmPermissionGranted: Boolean = true,
    val notificationPermissionGranted: Boolean = true,
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
            notificationPermissionGranted = SystemAlarmScheduler.canPostNotifications(context)
        )
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    fun upsertCourse(original: CourseItem?, edited: CourseItem): String? {
        if (_uiState.value.lessonTimeProfiles.isEmpty() || _uiState.value.lessonTimes.isEmpty()) {
            return "请先在设置中新增并设置一个作息表，再新增或编辑课程。"
        }
        val currentItems = _uiState.value.schedule.items.toMutableList()
        val normalized = edited.copy(period = ScheduleDefaults.normalizePeriod(edited.period))
        if (_uiState.value.lessonTimes.none { it.period == normalized.period }) {
            return "当前作息表未设置${ScheduleDefaults.periodLabel(normalized.period)}，请先设置作息时间。"
        }
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
        updateLessonTimeForProfile(_uiState.value.activeLessonTimeProfileId, period, startTime, endTime)
    }

    fun updateLessonTimeForProfile(profileId: String, period: Int, startTime: LocalTime, endTime: LocalTime) {
        if (!endTime.isAfter(startTime)) {
            _uiState.value = _uiState.value.copy(message = "结束时间必须晚于开始时间。")
            return
        }
        val current = profileSlots(profileId)
        val updated = current
            .filterNot { it.period == period }
            .plus(LessonTimeSlot(period = period, startTime = startTime, endTime = endTime))
            .sortedBy { it.period }
        saveProfileLessonTimes(profileId, updated, null)
    }

    fun addLessonTime() {
        addLessonTimeToProfile(_uiState.value.activeLessonTimeProfileId)
    }

    fun addLessonTimeToProfile(profileId: String) {
        val current = profileSlots(profileId)
        val nextPeriod = (current
            .filter { ScheduleDefaults.isRegularCoursePeriod(it.period) }
            .maxOfOrNull { it.period } ?: 0) + 1
        val previousEnd = current.lastOrNull()?.endTime ?: LocalTime.of(20, 40)
        val startTime = previousEnd.plusMinutes(10)
        val endTime = startTime.plusMinutes(45)
        val updated = current
            .plus(LessonTimeSlot(period = nextPeriod, startTime = startTime, endTime = endTime))
            .sortedBy { it.period }
        saveProfileLessonTimes(profileId, updated, "已新增${nextPeriod}节作息时间。")
    }

    fun deleteLessonTime(period: Int) {
        deleteLessonTimeFromProfile(_uiState.value.activeLessonTimeProfileId, period)
    }

    fun deleteLessonTimeFromProfile(profileId: String, period: Int) {
        val current = profileSlots(profileId)
        val schedule = _uiState.value.schedule.copy(
            items = normalizeCourses(_uiState.value.schedule.items.filterNot { it.period == period })
        )
        val updated = current.filterNot { it.period == period }.sortedBy { it.period }
        saveProfileLessonTimes(profileId, updated, "已删除${ScheduleDefaults.periodLabel(period)}作息时间。", schedule)
    }

    fun updateReminderSettings(
        notificationsEnabled: Boolean,
        alarmModeEnabled: Boolean,
        vibrationReminderEnabled: Boolean,
        soundReminderEnabled: Boolean,
        soundReminderToneId: String,
        minutesBefore: Int
    ): ReminderSettingsAction {
        if (minutesBefore < 0) {
            return ReminderSettingsAction(
                message = "提醒前时间不能小于 0 分钟。"
            )
        }
        val anyReminderEnabled = notificationsEnabled &&
            (alarmModeEnabled || vibrationReminderEnabled || soundReminderEnabled)
        val hasExactAlarmPermission = SystemAlarmScheduler.canUseExactAlarms(context)
        if (anyReminderEnabled && !hasExactAlarmPermission) {
            val disabledSettings = ReminderSettings(
                notificationsEnabled = notificationsEnabled,
                alarmModeEnabled = false,
                vibrationReminderEnabled = false,
                soundReminderEnabled = false,
                soundReminderToneId = soundReminderToneId,
                reminderMinutesBefore = minutesBefore
            ).normalized()
            return runCatching {
                ScheduleRepository.saveReminderSettings(context, disabledSettings)
                syncLocalState(
                    schedule = _uiState.value.schedule,
                    lessonTimes = _uiState.value.lessonTimes,
                    lessonTimeProfiles = _uiState.value.lessonTimeProfiles,
                    activeLessonTimeProfileId = _uiState.value.activeLessonTimeProfileId,
                    reminderSettings = disabledSettings,
                    exactAlarmPermissionGranted = false,
                    notificationPermissionGranted = SystemAlarmScheduler.canPostNotifications(context),
                    message = "通知功能无法正常使用，请先完成必要的系统权限授权。"
                )
                ReminderSettingsAction(
                    message = "通知功能无法正常使用，请先完成必要的系统权限授权。",
                    requestExactAlarmPermission = true
                )
            }.getOrElse {
                ReminderSettingsAction(message = "保存提醒设置失败：${it.message ?: "未知错误"}")
            }
        }
        val hasNotificationPermission = SystemAlarmScheduler.canPostNotifications(context)
        if (anyReminderEnabled && !hasNotificationPermission) {
            val disabledSettings = ReminderSettings(
                notificationsEnabled = notificationsEnabled,
                alarmModeEnabled = false,
                vibrationReminderEnabled = false,
                soundReminderEnabled = false,
                soundReminderToneId = soundReminderToneId,
                reminderMinutesBefore = minutesBefore
            ).normalized()
            return runCatching {
                ScheduleRepository.saveReminderSettings(context, disabledSettings)
                syncLocalState(
                    schedule = _uiState.value.schedule,
                    lessonTimes = _uiState.value.lessonTimes,
                    lessonTimeProfiles = _uiState.value.lessonTimeProfiles,
                    activeLessonTimeProfileId = _uiState.value.activeLessonTimeProfileId,
                    reminderSettings = disabledSettings,
                    exactAlarmPermissionGranted = hasExactAlarmPermission,
                    notificationPermissionGranted = false,
                    message = "通知功能无法正常使用，请先完成必要的系统权限授权。"
                )
                ReminderSettingsAction(
                    message = "通知功能无法正常使用，请先完成必要的系统权限授权。",
                    requestNotificationPermission = true
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
            notificationsEnabled = notificationsEnabled,
            alarmModeEnabled = alarmModeEnabled,
            vibrationReminderEnabled = vibrationReminderEnabled,
            soundReminderEnabled = soundReminderEnabled,
            soundReminderToneId = soundReminderToneId,
            reminderMinutesBefore = minutesBefore
        ).normalized()
        return runCatching {
            ScheduleRepository.clearDeliveredAlarmSignatures(context)
            ScheduleRepository.saveReminderSettings(context, settings)
            val alarmMessage = if (settings.hasEnabledReminder()) {
                refreshScheduledAlarms(
                    schedule = _uiState.value.schedule,
                    lessonTimes = _uiState.value.lessonTimes,
                    settings = settings
                ).message
            } else {
                "已关闭课程提醒。"
            }
            val needBatteryPrompt = settings.hasEnabledReminder() && !ignoringBatteryOptimizations
            // 自启动/后台冻结等保活项无法用 API 检测，只在首次开启闹钟、且电池优化不需要弹框时引导一次，
            // 避免与电池优化系统框叠加。
            val needAutoStartPrompt = settings.hasEnabledReminder() &&
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
                lessonTimeProfiles = _uiState.value.lessonTimeProfiles,
                activeLessonTimeProfileId = _uiState.value.activeLessonTimeProfileId,
                reminderSettings = settings,
                exactAlarmPermissionGranted = hasExactAlarmPermission,
                notificationPermissionGranted = hasNotificationPermission,
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

    fun exportDataJson(): String {
        val state = _uiState.value
        return AppDataTransfer.toJson(
            schedule = state.schedule,
            profiles = state.lessonTimeProfiles,
            activeProfileId = state.activeLessonTimeProfileId,
            classPresets = state.classPresets
        )
    }

    fun importDataJson(raw: String): String? {
        return runCatching {
            val payload = AppDataTransfer.fromJson(raw)
            val activeSlots = payload.profiles.firstOrNull { it.id == payload.activeProfileId }?.slots
                ?: emptyList()
            val validPeriods = activeSlots.map { it.period }.toSet()
            val importedItems = normalizeCourses(payload.schedule.items)
            val normalizedItems = importedItems.filter { it.period in validPeriods }
            val removedCount = importedItems.size - normalizedItems.size
            val schedule = WeeklySchedule(
                teacher = payload.schedule.teacher.ifBlank { ScheduleDefaults.DEFAULT_TEACHER },
                items = normalizedItems
            )
            val reminderSettings = _uiState.value.reminderSettings
            ScheduleRepository.saveLessonTimeProfiles(context, payload.profiles, payload.activeProfileId)
            ScheduleRepository.save(context, schedule)
            ScheduleRepository.saveClassPresets(context, payload.classPresets)
            ScheduleRepository.clearDeliveredAlarmSignatures(context)
            ScheduleRepository.saveLastAlarmSignature(context, null)
            val importMessage = "数据已导入。"
            val alarmMessage = if (reminderSettings.hasEnabledReminder()) {
                refreshScheduledAlarms(schedule, activeSlots, reminderSettings).message
            } else {
                null
            }
            val removalMessage = if (removedCount > 0) {
                "已移除 ${removedCount} 条当前作息表未设置时间的无效课程。"
            } else {
                null
            }
            syncLocalState(
                schedule = schedule,
                lessonTimes = activeSlots,
                lessonTimeProfiles = payload.profiles,
                activeLessonTimeProfileId = payload.activeProfileId,
                classPresets = payload.classPresets,
                reminderSettings = reminderSettings,
                exactAlarmPermissionGranted = _uiState.value.exactAlarmPermissionGranted,
                notificationPermissionGranted = _uiState.value.notificationPermissionGranted,
                message = listOfNotNull(importMessage, alarmMessage, removalMessage).joinToString(" ")
            )
            listOfNotNull(importMessage, alarmMessage, removalMessage).joinToString(" ")
        }.getOrElse {
            "导入失败：${it.message ?: "文件格式不正确"}"
        }
    }

    fun selectLessonTimeProfile(profileId: String) {
        val target = _uiState.value.lessonTimeProfiles.firstOrNull { it.id == profileId } ?: return
        runCatching {
            ScheduleRepository.setActiveLessonTimeProfileId(context, target.id)
            ScheduleRepository.clearDeliveredAlarmSignatures(context)
            ScheduleRepository.saveLastAlarmSignature(context, null)
            val merged = mergedLessonTimesFor(_uiState.value.schedule.items, target.slots)
            syncLocalState(
                schedule = _uiState.value.schedule,
                lessonTimes = merged,
                lessonTimeProfiles = _uiState.value.lessonTimeProfiles.map {
                    if (it.id == target.id) it.copy(slots = merged) else it
                },
                activeLessonTimeProfileId = target.id,
                reminderSettings = _uiState.value.reminderSettings,
                exactAlarmPermissionGranted = _uiState.value.exactAlarmPermissionGranted,
                notificationPermissionGranted = _uiState.value.notificationPermissionGranted,
                message = if (_uiState.value.reminderSettings.hasEnabledReminder()) {
                    refreshScheduledAlarms(_uiState.value.schedule, merged, _uiState.value.reminderSettings).message
                } else {
                    "已切换到${target.name}。"
                }
            )
            ScheduleRepository.saveLessonTimeProfiles(context, _uiState.value.lessonTimeProfiles, target.id)
        }.onFailure {
            _uiState.value = _uiState.value.copy(message = "切换作息表失败：${it.message ?: "未知错误"}")
        }
    }

    fun addLessonTimeProfile(
        name: String = "新作息表",
        slots: List<LessonTimeSlot> = _uiState.value.lessonTimes
    ): String? {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            _uiState.value = _uiState.value.copy(message = "作息表名称不能为空。")
            return null
        }
        val source = slots.sortedBy { it.period }
        val profile = ScheduleRepository.newLessonTimeProfile(trimmed, source)
        val updatedProfiles = _uiState.value.lessonTimeProfiles + profile
        val activeId = _uiState.value.activeLessonTimeProfileId.ifBlank { profile.id }
        ScheduleRepository.saveLessonTimeProfiles(context, updatedProfiles, activeId)
        _uiState.value = _uiState.value.copy(
            lessonTimeProfiles = updatedProfiles,
            activeLessonTimeProfileId = activeId,
            lessonTimes = updatedProfiles.firstOrNull { it.id == activeId }?.slots ?: _uiState.value.lessonTimes,
            message = "已新增${profile.name}。"
        )
        return profile.id
    }

    fun renameLessonTimeProfile(profileId: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            _uiState.value = _uiState.value.copy(message = "作息表名称不能为空。")
            return
        }
        val updated = _uiState.value.lessonTimeProfiles.map {
            if (it.id == profileId) it.copy(name = trimmed) else it
        }
        ScheduleRepository.saveLessonTimeProfiles(context, updated, _uiState.value.activeLessonTimeProfileId)
        _uiState.value = _uiState.value.copy(lessonTimeProfiles = updated, message = "已重命名作息表。")
    }

    fun deleteLessonTimeProfile(profileId: String) {
        val current = _uiState.value.lessonTimeProfiles
        if (current.size <= 1) {
            _uiState.value = _uiState.value.copy(message = "至少保留一套作息表。")
            return
        }
        if (_uiState.value.activeLessonTimeProfileId == profileId) {
            _uiState.value = _uiState.value.copy(message = "当前作息表不能删除，请先切换到其他作息表。")
            return
        }
        val updated = current.filterNot { it.id == profileId }
        val activeId = _uiState.value.activeLessonTimeProfileId
        val activeSlots = updated.first { it.id == activeId }.slots
        val reminderSettings = _uiState.value.reminderSettings
        ScheduleRepository.saveLessonTimeProfiles(context, updated, activeId)
        _uiState.value = _uiState.value.copy(
            lessonTimeProfiles = updated,
            activeLessonTimeProfileId = activeId,
            lessonTimes = activeSlots,
            message = if (reminderSettings.hasEnabledReminder()) {
                ScheduleRepository.clearDeliveredAlarmSignatures(context)
                ScheduleRepository.saveLastAlarmSignature(context, null)
                refreshScheduledAlarms(_uiState.value.schedule, activeSlots, reminderSettings).message
            } else {
                "已删除作息表。"
            }
        )
    }

    fun deleteLessonTimeProfiles(profileIds: Set<String>) {
        val current = _uiState.value.lessonTimeProfiles
        val activeId = _uiState.value.activeLessonTimeProfileId
        val removableIds = profileIds - activeId
        if (removableIds.isEmpty()) {
            _uiState.value = _uiState.value.copy(message = "当前作息表不能删除，请先切换到其他作息表。")
            return
        }
        val updated = current.filterNot { it.id in removableIds }
        if (updated.isEmpty()) {
            _uiState.value = _uiState.value.copy(message = "至少保留一套作息表。")
            return
        }
        ScheduleRepository.saveLessonTimeProfiles(context, updated, activeId)
        _uiState.value = _uiState.value.copy(
            lessonTimeProfiles = updated,
            message = if (profileIds.contains(activeId)) {
                "已删除可删除的作息表，当前作息表已保留。"
            } else {
                "已删除作息表。"
            }
        )
    }

    fun addClassPreset(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            return "班级名称不能为空。"
        }
        if (_uiState.value.classPresets.any { it.equals(trimmed, ignoreCase = true) }) {
            return "班级名称已存在。"
        }
        val updated = ScheduleRepository.normalizeClassPresets(_uiState.value.classPresets + trimmed)
        ScheduleRepository.saveClassPresets(context, updated)
        _uiState.value = _uiState.value.copy(classPresets = updated, message = "已新增班级。")
        return null
    }

    fun renameClassPreset(original: String, updatedName: String): String? {
        val trimmed = updatedName.trim()
        if (trimmed.isBlank()) {
            return "班级名称不能为空。"
        }
        if (_uiState.value.classPresets.any { !it.equals(original, ignoreCase = true) && it.equals(trimmed, ignoreCase = true) }) {
            return "班级名称已存在。"
        }
        val updated = ScheduleRepository.normalizeClassPresets(
            _uiState.value.classPresets.map { if (it == original) trimmed else it }
        )
        ScheduleRepository.saveClassPresets(context, updated)
        _uiState.value = _uiState.value.copy(classPresets = updated, message = "已更新班级。")
        return null
    }

    fun deleteClassPreset(name: String) {
        val updated = _uiState.value.classPresets.filterNot { it == name }
        ScheduleRepository.saveClassPresets(context, updated)
        _uiState.value = _uiState.value.copy(classPresets = updated, message = "已删除班级。")
    }

    private suspend fun hydrateLocalState() {
        val storedSchedule = withContext(Dispatchers.IO) {
            normalizeCourses(ScheduleRepository.load(context))
        }
        val storedProfiles = withContext(Dispatchers.IO) {
            ScheduleRepository.loadLessonTimeProfiles(context)
        }
        val activeLessonTimeProfileId = withContext(Dispatchers.IO) {
            ScheduleRepository.loadActiveLessonTimeProfileId(context)
        }
        val lessonTimes = withContext(Dispatchers.IO) {
            if (storedProfiles.isEmpty()) {
                emptyList()
            } else {
                mergedLessonTimesFor(storedSchedule, ScheduleRepository.loadLessonTimes(context))
            }
        }
        val lessonTimeProfiles = storedProfiles.map { profile ->
            if (profile.id == activeLessonTimeProfileId) {
                profile.copy(slots = lessonTimes)
            } else {
                profile
            }
        }
        val reminderSettings = withContext(Dispatchers.IO) {
            ScheduleRepository.loadReminderSettings(context)
        }
        val classPresets = withContext(Dispatchers.IO) {
            ScheduleRepository.loadClassPresets(context)
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
                lessonTimeProfiles = lessonTimeProfiles,
                activeLessonTimeProfileId = activeLessonTimeProfileId,
                classPresets = classPresets,
                reminderSettings = reminderSettings,
                exactAlarmPermissionGranted = SystemAlarmScheduler.canUseExactAlarms(context),
                notificationPermissionGranted = SystemAlarmScheduler.canPostNotifications(context),
            )
        } else {
            _uiState.value = ScheduleUiState(
                lessonTimes = lessonTimes,
                lessonTimeProfiles = lessonTimeProfiles,
                activeLessonTimeProfileId = activeLessonTimeProfileId,
                classPresets = classPresets,
                reminderSettings = reminderSettings,
                exactAlarmPermissionGranted = SystemAlarmScheduler.canUseExactAlarms(context),
                notificationPermissionGranted = SystemAlarmScheduler.canPostNotifications(context),
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
                lessonTimeProfiles = updateActiveProfileSlots(lessonTimes),
                activeLessonTimeProfileId = _uiState.value.activeLessonTimeProfileId,
                reminderSettings = reminderSettings,
                exactAlarmPermissionGranted = _uiState.value.exactAlarmPermissionGranted,
                notificationPermissionGranted = _uiState.value.notificationPermissionGranted,
                message = alarmMessage
            )
        }
    }

    private fun syncLocalState(
        schedule: WeeklySchedule,
        lessonTimes: List<LessonTimeSlot>,
        lessonTimeProfiles: List<LessonTimeProfile>,
        activeLessonTimeProfileId: String,
        classPresets: List<String> = _uiState.value.classPresets,
        reminderSettings: ReminderSettings,
        exactAlarmPermissionGranted: Boolean,
        notificationPermissionGranted: Boolean,
        message: String?
    ) {
        _uiState.value = _uiState.value.copy(
            schedule = schedule,
            lessonTimes = lessonTimes,
            lessonTimeProfiles = lessonTimeProfiles,
            activeLessonTimeProfileId = activeLessonTimeProfileId,
            classPresets = classPresets,
            reminderSettings = reminderSettings,
            exactAlarmPermissionGranted = exactAlarmPermissionGranted,
            notificationPermissionGranted = notificationPermissionGranted,
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

    private fun saveLessonTimes(
        updated: List<LessonTimeSlot>,
        message: String?,
        schedule: WeeklySchedule = _uiState.value.schedule
    ) {
        runCatching {
            ScheduleRepository.saveLessonTimes(context, updated)
            ScheduleRepository.save(context, schedule)
            ScheduleRepository.clearDeliveredAlarmSignatures(context)
            ScheduleRepository.saveLastAlarmSignature(context, null)
            val reminderSettings = _uiState.value.reminderSettings
            syncLocalState(
                schedule = schedule,
                lessonTimes = updated,
                lessonTimeProfiles = updateActiveProfileSlots(updated),
                activeLessonTimeProfileId = _uiState.value.activeLessonTimeProfileId,
                reminderSettings = reminderSettings,
                exactAlarmPermissionGranted = _uiState.value.exactAlarmPermissionGranted,
                notificationPermissionGranted = _uiState.value.notificationPermissionGranted,
                message = if (reminderSettings.hasEnabledReminder()) {
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

    private fun saveProfileLessonTimes(
        profileId: String,
        updated: List<LessonTimeSlot>,
        message: String?,
        schedule: WeeklySchedule = _uiState.value.schedule
    ) {
        runCatching {
            val activeId = _uiState.value.activeLessonTimeProfileId
            val updatedProfiles = _uiState.value.lessonTimeProfiles.map { profile ->
                if (profile.id == profileId) {
                    profile.copy(slots = updated.sortedBy { it.period })
                } else {
                    profile
                }
            }
            ScheduleRepository.saveLessonTimeProfiles(context, updatedProfiles, activeId)
            ScheduleRepository.save(context, schedule)
            ScheduleRepository.clearDeliveredAlarmSignatures(context)
            ScheduleRepository.saveLastAlarmSignature(context, null)
            val activeSlots = updatedProfiles.firstOrNull { it.id == activeId }?.slots ?: _uiState.value.lessonTimes
            val reminderSettings = _uiState.value.reminderSettings
            syncLocalState(
                schedule = schedule,
                lessonTimes = activeSlots,
                lessonTimeProfiles = updatedProfiles,
                activeLessonTimeProfileId = activeId,
                reminderSettings = reminderSettings,
                exactAlarmPermissionGranted = _uiState.value.exactAlarmPermissionGranted,
                notificationPermissionGranted = _uiState.value.notificationPermissionGranted,
                message = if (reminderSettings.hasEnabledReminder()) {
                    refreshScheduledAlarms(
                        schedule = schedule,
                        lessonTimes = activeSlots,
                        settings = reminderSettings
                    ).message
                } else {
                    message
                }
            )
        }.onFailure {
            _uiState.value = _uiState.value.copy(message = "保存作息时间失败：${it.message ?: "未知错误"}")
        }
    }

    private fun updateActiveProfileSlots(slots: List<LessonTimeSlot>): List<LessonTimeProfile> {
        val activeId = _uiState.value.activeLessonTimeProfileId
        return _uiState.value.lessonTimeProfiles.map { profile ->
            if (profile.id == activeId) {
                profile.copy(slots = slots.sortedBy { it.period })
            } else {
                profile
            }
        }
    }

    private fun profileSlots(profileId: String): List<LessonTimeSlot> {
        return (_uiState.value.lessonTimeProfiles.firstOrNull { it.id == profileId }?.slots
            ?: _uiState.value.lessonTimes).sortedBy { it.period }
    }

    private fun mergedLessonTimesFor(
        items: List<CourseItem>,
        current: List<LessonTimeSlot> = _uiState.value.lessonTimes
    ): List<LessonTimeSlot> {
        if (current.isEmpty() && _uiState.value.lessonTimeProfiles.isEmpty()) {
            return emptyList()
        }
        return ScheduleDefaults.mergeLessonTimeSlots(current, items.map { it.period })
    }

    private fun normalizeCourses(items: List<CourseItem>): List<CourseItem> {
        return items
            .map { it.copy(period = ScheduleDefaults.normalizePeriod(it.period)) }
            .sortedWith(compareBy({ it.dayOfWeek.value }, { it.period }, { it.className }))
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
