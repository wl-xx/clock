package com.example.pinkschedule.ui

import android.Manifest
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.ViewTimeline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.FileProvider
import com.example.pinkschedule.data.ScheduleImageExporter
import com.example.pinkschedule.model.LessonTimeProfile
import com.example.pinkschedule.model.CourseItem
import com.example.pinkschedule.model.LessonTimeSlot
import com.example.pinkschedule.model.ReminderTone
import com.example.pinkschedule.model.ScheduleDefaults
import com.example.pinkschedule.model.WeeklySchedule
import com.example.pinkschedule.reminder.AlarmPlaybackManager
import com.example.pinkschedule.reminder.SystemAlarmScheduler
import com.example.pinkschedule.viewmodel.ScheduleViewModel
import kotlinx.coroutines.delay
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

private enum class AppPage(val title: String, val icon: ImageVector) {
    SCHEDULE("课程表", Icons.Filled.CalendarMonth),
    COURSES("课程管理", Icons.Filled.Edit),
    SETTINGS("设置", Icons.Filled.Settings)
}

private enum class ScheduleViewMode {
    TIMELINE,
    TABLE
}

@Composable
fun PinkScheduleAppScreen(
    viewModel: ScheduleViewModel,
    incomingImportIntent: Intent? = null,
    onIncomingImportIntentConsumed: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var selectedPage by rememberSaveable { mutableStateOf(AppPage.SCHEDULE) }
    var scheduleViewMode by rememberSaveable { mutableStateOf(ScheduleViewMode.TIMELINE) }
    var selectedDay by rememberSaveable { mutableStateOf(LocalDate.now().dayOfWeek.value) }
    var managementDay by rememberSaveable { mutableStateOf(LocalDate.now().dayOfWeek.value) }
    var editingCourse by remember { mutableStateOf<CourseItem?>(null) }
    var addingCourse by remember { mutableStateOf(false) }
    var addingCourseDay by remember { mutableStateOf(LocalDate.now().dayOfWeek) }
    var errorDialogMessage by remember { mutableStateOf<String?>(null) }
    var settingsAtTopLevel by rememberSaveable { mutableStateOf(true) }
    var settingsNotificationsEnabled by rememberSaveable { mutableStateOf(uiState.reminderSettings.notificationsEnabled) }
    var settingsAlarmModeEnabled by rememberSaveable { mutableStateOf(uiState.reminderSettings.alarmModeEnabled) }
    var settingsVibrationReminderEnabled by rememberSaveable { mutableStateOf(uiState.reminderSettings.vibrationReminderEnabled) }
    var settingsSoundReminderEnabled by rememberSaveable { mutableStateOf(uiState.reminderSettings.soundReminderEnabled) }
    var settingsSoundReminderToneId by rememberSaveable { mutableStateOf(uiState.reminderSettings.soundReminderToneId) }
    var settingsReminderMinutesText by rememberSaveable { mutableStateOf(uiState.reminderSettings.reminderMinutesBefore.toString()) }
    val importDataLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            importDataFromUri(context = context, uri = it, onImport = viewModel::importDataJson)
                ?.let { message -> errorDialogMessage = message }
        }
    }
    LaunchedEffect(uiState.reminderSettings) {
        settingsNotificationsEnabled = uiState.reminderSettings.notificationsEnabled
        settingsAlarmModeEnabled = uiState.reminderSettings.alarmModeEnabled
        settingsVibrationReminderEnabled = uiState.reminderSettings.vibrationReminderEnabled
        settingsSoundReminderEnabled = uiState.reminderSettings.soundReminderEnabled
        settingsSoundReminderToneId = uiState.reminderSettings.soundReminderToneId
        settingsReminderMinutesText = uiState.reminderSettings.reminderMinutesBefore.toString()
    }

    LaunchedEffect(uiState.message) {
        uiState.message
            ?.takeIf(::isErrorMessage)
            ?.let { errorDialogMessage = it }
    }

    LaunchedEffect(selectedPage) {
        if (selectedPage != AppPage.SETTINGS) {
            settingsAtTopLevel = true
        }
    }

    LaunchedEffect(incomingImportIntent) {
        incomingImportIntent?.let { intent ->
            importDataFromIntent(context = context, intent = intent, onImport = viewModel::importDataJson)
                ?.let { message -> errorDialogMessage = message }
            onIncomingImportIntentConsumed()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFFFF3F7))
    ) {
        val bottomBarVisible = when (selectedPage) {
            AppPage.SETTINGS -> settingsAtTopLevel
            else -> true
        }
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .then(if (bottomBarVisible) Modifier else Modifier.navigationBarsPadding())
                    .verticalScroll(scrollState)
                    .padding(horizontal = 22.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                when (selectedPage) {
                    AppPage.SCHEDULE -> SchedulePage(
                        schedule = uiState.schedule,
                        lessonTimes = uiState.lessonTimes,
                        selectedDay = selectedDay,
                        viewMode = scheduleViewMode,
                        onSelectDay = { selectedDay = it },
                        onToggleViewMode = {
                            scheduleViewMode = if (scheduleViewMode == ScheduleViewMode.TIMELINE) {
                                ScheduleViewMode.TABLE
                            } else {
                                ScheduleViewMode.TIMELINE
                            }
                        }
                    )
                    AppPage.COURSES -> CourseManagementPage(
                        schedule = uiState.schedule,
                        lessonTimes = uiState.lessonTimes,
                        selectedDay = managementDay,
                        onSelectDay = { managementDay = it },
                        onAddCourse = {
                            if (uiState.lessonTimeProfiles.isEmpty() || uiState.lessonTimes.isEmpty()) {
                                errorDialogMessage = "请先在设置中新增并设置一个作息表，再新增或编辑课程。"
                            } else {
                                addingCourseDay = DayOfWeek.of(managementDay)
                                addingCourse = true
                            }
                        },
                        onEditCourse = {
                            if (uiState.lessonTimeProfiles.isEmpty() || uiState.lessonTimes.isEmpty()) {
                                errorDialogMessage = "请先在设置中新增并设置一个作息表，再新增或编辑课程。"
                            } else {
                                editingCourse = it
                            }
                        },
                        onDeleteCourse = viewModel::deleteCourse
                    )
                    AppPage.SETTINGS -> SettingsPage(
                        lessonTimes = uiState.lessonTimes,
                        notificationsEnabled = settingsNotificationsEnabled,
                        alarmModeEnabled = settingsAlarmModeEnabled,
                        vibrationReminderEnabled = settingsVibrationReminderEnabled,
                        soundReminderEnabled = settingsSoundReminderEnabled,
                        soundReminderToneId = settingsSoundReminderToneId,
                        reminderMinutesText = settingsReminderMinutesText,
                        exactAlarmPermissionGranted = uiState.exactAlarmPermissionGranted,
                        notificationPermissionGranted = uiState.notificationPermissionGranted,
                        classPresets = uiState.classPresets,
                        coursePeriods = uiState.schedule.items.map { it.period }.toSet(),
                        lessonTimeProfiles = uiState.lessonTimeProfiles,
                        activeLessonTimeProfileId = uiState.activeLessonTimeProfileId,
                        onNotificationsEnabledChange = { settingsNotificationsEnabled = it },
                        onAlarmModeEnabledChange = { settingsAlarmModeEnabled = it },
                        onVibrationReminderEnabledChange = { settingsVibrationReminderEnabled = it },
                        onSoundReminderEnabledChange = { settingsSoundReminderEnabled = it },
                        onSoundReminderToneChange = { settingsSoundReminderToneId = it },
                        onReminderMinutesTextChange = { settingsReminderMinutesText = it },
                        onSaveSettings = viewModel::updateReminderSettings,
                        onUpdateLessonTimeForProfile = viewModel::updateLessonTimeForProfile,
                        onDeleteLessonTimeFromProfile = viewModel::deleteLessonTimeFromProfile,
                        onAddLessonTimeProfile = viewModel::addLessonTimeProfile,
                        onRenameLessonTimeProfile = viewModel::renameLessonTimeProfile,
                        onDeleteLessonTimeProfiles = viewModel::deleteLessonTimeProfiles,
                        onSelectLessonTimeProfile = viewModel::selectLessonTimeProfile,
                        onAddClassPreset = viewModel::addClassPreset,
                        onRenameClassPreset = viewModel::renameClassPreset,
                        onDeleteClassPreset = viewModel::deleteClassPreset,
                        onImportData = {
                            importDataLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                        },
                        onExportData = {
                            shareDataJson(
                                context = context,
                                json = viewModel.exportDataJson()
                            ).takeIf(::isErrorMessage)
                                ?.let { message -> errorDialogMessage = message }
                        },
                        onShareScheduleImage = {
                            shareScheduleImage(
                                context = context,
                                schedule = uiState.schedule,
                                lessonTimes = uiState.lessonTimes
                            ).takeIf(::isErrorMessage)
                                ?.let { message -> errorDialogMessage = message }
                        },
                        onErrorMessage = { errorDialogMessage = it },
                        onRefreshPermission = viewModel::refreshExactAlarmPermission,
                        onTopLevelChange = { settingsAtTopLevel = it }
                    )
                }

                Spacer(Modifier.height(4.dp))
            }
            if (bottomBarVisible) {
                AppBottomBar(selected = selectedPage, onSelect = { selectedPage = it })
            }
        }
    }

    errorDialogMessage?.let { message ->
        AppErrorDialog(
            message = message,
            onDismiss = {
                errorDialogMessage = null
                viewModel.clearMessage()
            }
        )
    }

    if (addingCourse) {
        CourseEditorDialog(
            teacher = uiState.schedule.teacher,
            lessonTimes = uiState.lessonTimes,
            classPresets = uiState.classPresets,
            course = null,
            defaultDay = addingCourseDay,
            onDismiss = { addingCourse = false },
            onSave = {
                val error = viewModel.upsertCourse(null, it)
                if (error == null) addingCourse = false
                error
            }
        )
    }

    editingCourse?.let { course ->
        CourseEditorDialog(
            teacher = uiState.schedule.teacher,
            lessonTimes = uiState.lessonTimes,
            classPresets = uiState.classPresets,
            course = course,
            defaultDay = course.dayOfWeek,
            onDismiss = { editingCourse = null },
            onSave = {
                val error = viewModel.upsertCourse(course, it)
                if (error == null) editingCourse = null
                error
            }
        )
    }
}

@Composable
private fun AppBottomBar(
    selected: AppPage,
    onSelect: (AppPage) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 28.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppPage.entries.forEach { page ->
            val active = page == selected
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (active) Color(0xFFE86C8F) else Color.Transparent)
                    .clickable { onSelect(page) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = page.icon,
                    contentDescription = page.title,
                    tint = if (active) Color.White else Color(0xFF8F878C),
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

@Composable
private fun SchedulePage(
    schedule: WeeklySchedule,
    lessonTimes: List<LessonTimeSlot>,
    selectedDay: Int,
    viewMode: ScheduleViewMode,
    onSelectDay: (Int) -> Unit,
    onToggleViewMode: () -> Unit
) {
    val weekdays = remember { buildWeekdays() }
    val courseDays = remember(schedule.items) { schedule.items.map { it.dayOfWeek.value }.toSet() }
    val filteredCourses = schedule.items.filter { it.dayOfWeek.value == selectedDay }.sortedBy { it.period }

    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "课程表",
                style = MaterialTheme.typography.titleLarge,
                color = Color(0xFF202023),
                fontWeight = FontWeight.Black
            )
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.White.copy(alpha = 0.78f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        onToggleViewMode()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (viewMode == ScheduleViewMode.TIMELINE) {
                        Icons.Filled.TableChart
                    } else {
                        Icons.Filled.ViewTimeline
                    },
                    contentDescription = if (viewMode == ScheduleViewMode.TIMELINE) {
                        "切换为表格模式"
                    } else {
                        "切换为时间线模式"
                    },
                    tint = Color(0xFFE26786),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        if (viewMode == ScheduleViewMode.TIMELINE) {
            WeekdayStrip(
                weekdays = weekdays,
                selectedDay = selectedDay,
                courseDays = courseDays,
                onSelectDay = onSelectDay
            )
            CourseStatsRow(count = filteredCourses.size)
            if (filteredCourses.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    filteredCourses.forEachIndexed { index, course ->
                        CourseTimelineItem(
                            course = course,
                            lessonTimes = lessonTimes,
                            isLast = index == filteredCourses.lastIndex
                        )
                    }
                }
            } else {
                EmptyScheduleCard()
            }
        } else {
            CourseStatsRow(count = schedule.items.size)
            ScheduleTableView(
                schedule = schedule,
                lessonTimes = lessonTimes
            )
        }
    }
}

@Composable
private fun WeekdayStrip(
    weekdays: List<WeekdayUiItem>,
    selectedDay: Int,
    courseDays: Set<Int>,
    onSelectDay: (Int) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = Color.White.copy(alpha = 0.78f),
        shadowElevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            weekdays.forEach { day ->
                val selected = day.id == selectedDay
                val hasCourse = day.id in courseDays
                val themeColor = MaterialTheme.colorScheme.primary
                val selectedContentColor = MaterialTheme.colorScheme.onPrimary
                val dotShape = RoundedCornerShape(999.dp)
                val dateColor = when {
                    selected -> selectedContentColor
                    day.isToday -> themeColor
                    else -> Color(0xFF8F878C)
                }
                val courseDotColor = if (hasCourse && !selected) themeColor else Color.Transparent
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = if (selected) themeColor else Color.Transparent,
                    shadowElevation = 0.dp,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            onSelectDay(day.id)
                        }
                ) {
                    Column(modifier = Modifier.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            day.short,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) selectedContentColor else Color(0xFF8F878C),
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            day.num.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = dateColor,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .size(4.dp)
                                .background(courseDotColor, dotShape)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CourseStatsRow(
    count: Int,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("$count 节课程", style = MaterialTheme.typography.titleMedium, color = Color(0xFF202023), fontWeight = FontWeight.Black)
        trailing?.invoke()
    }
}

@Composable
private fun CourseTimelineItem(
    course: CourseItem,
    lessonTimes: List<LessonTimeSlot>,
    isLast: Boolean
) {
    val slot = lessonTimes.firstOrNull { it.period == course.period }
    val timeText = slot?.displayRange().orEmpty()
    val isCompletedToday = course.dayOfWeek == LocalDate.now().dayOfWeek &&
        slot?.endTime?.isBefore(LocalTime.now()) == true
    val accent = if (isCompletedToday) Color(0xFFD7CCC8) else courseAccentColor()
    val cardColor = if (isCompletedToday) Color.White.copy(alpha = 0.48f) else Color.White.copy(alpha = 0.82f)
    val timeColor = if (isCompletedToday) Color(0xFFB8AFAE) else Color(0xFF8E7975)
    val titleColor = if (isCompletedToday) Color(0xFF9B8F8C) else Color(0xFF4B2F2C)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(
            modifier = Modifier.width(54.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = slotLabel(course.period),
                style = MaterialTheme.typography.labelSmall,
                color = if (isCompletedToday) Color(0xFFB8AFAE) else Color(0xFFE26786),
                fontWeight = FontWeight.Black
            )
            Box(modifier = Modifier.size(12.dp).clip(RoundedCornerShape(999.dp)).background(accent))
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(86.dp)
                        .background(if (isCompletedToday) Color(0xFFE6DEDC) else Color(0xFFF0D9D1))
                )
            }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(22.dp))
                .background(cardColor)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(timeText.ifBlank { "时间待定" }, style = MaterialTheme.typography.labelSmall, color = timeColor)
                Text(
                    course.courseName,
                    style = MaterialTheme.typography.titleMedium,
                    color = titleColor,
                    fontWeight = if (isCompletedToday) FontWeight.Medium else FontWeight.Black,
                    textDecoration = if (isCompletedToday) TextDecoration.LineThrough else TextDecoration.None
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CourseMetaChip(label = "班级", value = course.className.ifBlank { "未填写" }, muted = isCompletedToday)
                    if (timeText.isNotBlank()) {
                        CourseMetaChip(label = "时间", value = timeText, muted = isCompletedToday)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyScheduleCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Filled.Schedule, null, tint = Color(0xFFE26786), modifier = Modifier.size(30.dp))
        Text("今天没有排课哦", style = MaterialTheme.typography.titleMedium, color = Color(0xFF202023), fontWeight = FontWeight.Black)
        Text("放松一下吧", style = MaterialTheme.typography.bodySmall, color = Color(0xFF8F878C))
    }
}

@Composable
private fun ScheduleTableView(
    schedule: WeeklySchedule,
    lessonTimes: List<LessonTimeSlot>
) {
    val sortedSlots = lessonTimes.sortedBy { it.period }
    val weekdays = DayOfWeek.entries
    val courseIndex = remember(schedule.items) {
        schedule.items.associateBy { it.dayOfWeek to it.period }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White.copy(alpha = 0.86f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
            ) {
                ScheduleTableHeader(weekdays = weekdays)
                sortedSlots.forEach { slot ->
                    ScheduleTablePeriodRow(
                        slot = slot,
                        weekdays = weekdays,
                        courseAt = { day -> courseIndex[day to slot.period] }
                    )
                }
            }
        }
    }
}

private val TableGridLineColor = Color(0xFFD9C9C3)
private val TableGridStrokeWidth = 0.5.dp

@Composable
private fun ScheduleTableHeader(weekdays: List<DayOfWeek>) {
    Row(modifier = Modifier.fillMaxWidth()) {
        ScheduleTableHeaderCell(text = "节", modifier = Modifier.width(28.dp))
        weekdays.forEach { day ->
            ScheduleTableHeaderCell(
                text = dayShortLabel(day),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ScheduleTablePeriodRow(
    slot: LessonTimeSlot,
    weekdays: List<DayOfWeek>,
    courseAt: (DayOfWeek) -> CourseItem?
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        ScheduleTablePeriodCell(slot = slot, modifier = Modifier.width(28.dp))
        weekdays.forEach { day ->
            val course = courseAt(day)
            ScheduleTableCourseCell(
                course = course,
                isToday = day == LocalDate.now().dayOfWeek,
                isCompletedToday = day == LocalDate.now().dayOfWeek && slot.endTime.isBefore(LocalTime.now()),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ScheduleTableHeaderCell(
    text: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(30.dp)
            .background(Color(0xFFFFF3F7)),
        contentAlignment = Alignment.Center
    ) {
        TableCellBorder(color = TableGridLineColor)
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFFE26786),
            fontWeight = FontWeight.Black
        )
    }
}

@Composable
private fun ScheduleTablePeriodCell(
    slot: LessonTimeSlot,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(58.dp)
            .background(Color(0xFFFFF7F4)),
        contentAlignment = Alignment.Center
    ) {
        TableCellBorder(color = TableGridLineColor)
        Text(
            text = ScheduleDefaults.tablePeriodLabel(slot.period),
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF4B2F2C),
            fontWeight = FontWeight.Black,
            maxLines = 1
        )
    }
}

@Composable
private fun ScheduleTableCourseCell(
    course: CourseItem?,
    isToday: Boolean,
    isCompletedToday: Boolean,
    modifier: Modifier = Modifier
) {
    val background = when {
        course == null -> Color.White.copy(alpha = 0.66f)
        isCompletedToday -> Color(0xFFEDE7E4)
        isToday -> Color(0xFFFFDDE6)
        else -> Color(0xFFFFEDF2)
    }
    val titleColor = if (isCompletedToday) Color(0xFF9B8F8C) else Color(0xFF4B2F2C)
    Box(
        modifier = modifier
            .height(58.dp)
            .background(background)
    ) {
        TableCellBorder(color = TableGridLineColor)
        if (course != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 3.dp, vertical = 5.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = course.courseName,
                    style = MaterialTheme.typography.labelSmall,
                    color = titleColor,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    textDecoration = if (isCompletedToday) TextDecoration.LineThrough else TextDecoration.None
                )
                Text(
                    text = course.className.ifBlank { "未填写" },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isCompletedToday) Color(0xFFB8AFAE) else Color(0xFF8F878C),
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun TableCellBorder(color: Color) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(
            color = color,
            size = size,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = TableGridStrokeWidth.toPx())
        )
    }
}

@Composable
private fun CourseManagementPage(
    schedule: WeeklySchedule,
    lessonTimes: List<LessonTimeSlot>,
    selectedDay: Int,
    onSelectDay: (Int) -> Unit,
    onAddCourse: () -> Unit,
    onEditCourse: (CourseItem) -> Unit,
    onDeleteCourse: (CourseItem) -> Unit
) {
    val weekdays = remember { buildWeekdays() }
    val courseDays = remember(schedule.items) { schedule.items.map { it.dayOfWeek.value }.toSet() }
    val day = DayOfWeek.of(selectedDay)
    val grouped = schedule.items.groupBy { it.dayOfWeek }
    val dayItems = grouped[day].orEmpty().sortedWith(compareBy({ it.period }, { it.className }))
    var pendingDeleteCourse by remember { mutableStateOf<CourseItem?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = "课程管理",
            style = MaterialTheme.typography.titleLarge,
            color = Color(0xFF202023),
            fontWeight = FontWeight.Black
        )

        WeekdayStrip(
            weekdays = weekdays,
            selectedDay = selectedDay,
            courseDays = courseDays,
            onSelectDay = onSelectDay
        )
        CourseStatsRow(count = dayItems.size) {
            CompactAddButton(onClick = onAddCourse)
        }
        DayGroupEditorCard(
            items = dayItems,
            lessonTimes = lessonTimes,
            onEditCourse = onEditCourse,
            onDeleteCourse = { pendingDeleteCourse = it }
        )
    }

    pendingDeleteCourse?.let { course ->
        AlertDialog(
            onDismissRequest = { pendingDeleteCourse = null },
            shape = RoundedCornerShape(24.dp),
            containerColor = Color(0xFFFFFCFA),
            title = { DialogTitle("删除课程？", onDismiss = { pendingDeleteCourse = null }) },
            text = {
                Text(
                    "确定删除 ${slotLabel(course.period)} · ${course.courseName} · ${course.className.ifBlank { "未填写班级" }} 吗？",
                    color = Color(0xFF4B2F2C)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteCourse = null
                        onDeleteCourse(course)
                    }
                ) {
                    Text("删除", color = Color(0xFFE26786), fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

@Composable
private fun DayGroupEditorCard(
    items: List<CourseItem>,
    lessonTimes: List<LessonTimeSlot>,
    onEditCourse: (CourseItem) -> Unit,
    onDeleteCourse: (CourseItem) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (items.isEmpty()) {
            Text(
                text = "暂无课程",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF8F878C),
                modifier = Modifier.padding(vertical = 12.dp)
            )
        } else {
            items.forEachIndexed { index, item ->
                EditableCourseRow(
                    item = item,
                    lessonTimes = lessonTimes,
                    onEdit = { onEditCourse(item) },
                    onDelete = { onDeleteCourse(item) }
                )
                if (index != items.lastIndex) {
                    HorizontalDivider(color = Color(0xFFEEDFE5))
                }
            }
        }
    }
}

@Composable
private fun EditableCourseRow(
    item: CourseItem,
    lessonTimes: List<LessonTimeSlot>,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val timeText = lessonTimes.firstOrNull { it.period == item.period }?.displayRange().orEmpty()
    val accent = courseAccentColor()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            Box(modifier = Modifier.size(12.dp).clip(RoundedCornerShape(999.dp)).background(accent))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "${slotLabel(item.period)} · ${item.courseName}",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color(0xFF4B2F2C),
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CourseMetaChip(label = "班级", value = item.className.ifBlank { "未填写" })
                    if (timeText.isNotBlank()) {
                        CourseMetaChip(label = "时间", value = timeText)
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onEdit, colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFE26786))) {
                Icon(Icons.Filled.Edit, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("编辑")
            }
            Spacer(Modifier.width(6.dp))
            TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFE26786))) {
                Icon(Icons.Filled.Delete, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("删除")
            }
        }
    }
}

@Composable
private fun CourseMetaChip(
    label: String,
    value: String,
    accent: Color = Color(0xFFE26786),
    muted: Boolean = false
) {
    val dotColor = if (muted) Color(0xFFD7CCC8) else accent
    val labelColor = if (muted) Color(0xFFB8AFAE) else Color(0xFF8F878C)
    val valueColor = if (muted) Color(0xFF9B8F8C) else Color(0xFF4B2F2C)
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(5.dp).clip(RoundedCornerShape(999.dp)).background(dotColor))
        Text(label, style = MaterialTheme.typography.labelSmall, color = labelColor, fontWeight = FontWeight.SemiBold)
        Text(value, style = MaterialTheme.typography.labelSmall, color = valueColor, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun BannerMessage(message: String) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color.White.copy(alpha = 0.82f),
        border = BorderStroke(1.dp, Color(0xFFF0DDD2)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.NotificationsActive,
                contentDescription = null,
                tint = Color(0xFFE26786),
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF4B2F2C),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun AppErrorDialog(
    message: String,
    onDismiss: () -> Unit
) {
    val isPermissionGuide = isPermissionGuideMessage(message)
    val title = dialogTitleForMessage(message, isPermissionGuide)
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = Color(0xFFFFFCFA),
        title = { DialogTitle(title, onDismiss = onDismiss) },
        text = {
            Text(
                text = if (isPermissionGuide) {
                    "请将系统权限中的必要权限都打开，否则通知功能无法正常使用。"
                } else {
                    message
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF4B2F2C)
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("知道了", color = Color(0xFFE26786))
            }
        }
    )
}

@Composable
private fun SettingsPage(
    lessonTimes: List<LessonTimeSlot>,
    notificationsEnabled: Boolean,
    alarmModeEnabled: Boolean,
    vibrationReminderEnabled: Boolean,
    soundReminderEnabled: Boolean,
    soundReminderToneId: String,
    reminderMinutesText: String,
    exactAlarmPermissionGranted: Boolean,
    notificationPermissionGranted: Boolean,
    classPresets: List<String>,
    coursePeriods: Set<Int>,
    lessonTimeProfiles: List<LessonTimeProfile>,
    activeLessonTimeProfileId: String,
    onNotificationsEnabledChange: (Boolean) -> Unit,
    onAlarmModeEnabledChange: (Boolean) -> Unit,
    onVibrationReminderEnabledChange: (Boolean) -> Unit,
    onSoundReminderEnabledChange: (Boolean) -> Unit,
    onSoundReminderToneChange: (String) -> Unit,
    onReminderMinutesTextChange: (String) -> Unit,
    onSaveSettings: (Boolean, Boolean, Boolean, Boolean, String, Int) -> com.example.pinkschedule.viewmodel.ReminderSettingsAction,
    onUpdateLessonTimeForProfile: (String, Int, LocalTime, LocalTime) -> Unit,
    onDeleteLessonTimeFromProfile: (String, Int) -> Unit,
    onAddLessonTimeProfile: (String, List<LessonTimeSlot>) -> String?,
    onRenameLessonTimeProfile: (String, String) -> Unit,
    onDeleteLessonTimeProfiles: (Set<String>) -> Unit,
    onSelectLessonTimeProfile: (String) -> Unit,
    onAddClassPreset: (String) -> String?,
    onRenameClassPreset: (String, String) -> String?,
    onDeleteClassPreset: (String) -> Unit,
    onImportData: () -> Unit,
    onExportData: () -> Unit,
    onShareScheduleImage: () -> Unit,
    onErrorMessage: (String) -> Unit,
    onRefreshPermission: () -> Unit,
    onTopLevelChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        onRefreshPermission()
    }
    var requestPermissionTick by remember { mutableStateOf(0) }
    var requestNotificationPermissionTick by remember { mutableStateOf(0) }
    var requestNotificationStyleTick by remember { mutableStateOf(0) }
    var requestBatteryOptimizationTick by remember { mutableStateOf(0) }
    var requestAutoStartTick by remember { mutableStateOf(0) }
    var lastSavedNotificationsEnabled by remember { mutableStateOf(notificationsEnabled) }
    var lastSavedAlarmModeEnabled by remember { mutableStateOf(alarmModeEnabled) }
    var lastSavedVibrationReminderEnabled by remember { mutableStateOf(vibrationReminderEnabled) }
    var lastSavedSoundReminderEnabled by remember { mutableStateOf(soundReminderEnabled) }
    var lastSavedSoundReminderToneId by remember { mutableStateOf(soundReminderToneId) }
    var lastSavedReminderMinutesText by remember { mutableStateOf(reminderMinutesText) }
    var showSoundToneDialog by remember { mutableStateOf(false) }
    var batteryOptimizationIgnored by remember {
        mutableStateOf(SystemAlarmScheduler.isIgnoringBatteryOptimizations(context))
    }
    var activeSettingsPanel by rememberSaveable { mutableStateOf<String?>(null) }
    var permissionBackTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var permissionGuideReason by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedLessonTimeProfileId by rememberSaveable { mutableStateOf(activeLessonTimeProfileId) }
    var draftLessonTimeProfile by remember { mutableStateOf<LessonTimeProfile?>(null) }

    fun refreshManualPermissionState() {
        onRefreshPermission()
        batteryOptimizationIgnored = SystemAlarmScheduler.isIgnoringBatteryOptimizations(context)
    }

    fun goBack() {
        if (activeSettingsPanel == "time-edit" && draftLessonTimeProfile?.id == selectedLessonTimeProfileId) {
            draftLessonTimeProfile = null
        }
        activeSettingsPanel = when (activeSettingsPanel) {
            "permission" -> permissionBackTarget
            "time-view" -> "time-view-list"
            "time-view-list" -> "time"
            "time-current" -> "time"
            "time-edit" -> "time-edit-list"
            "time-edit-list" -> "time"
            "alarm", "time", "class" -> null
            else -> null
        }
        if (activeSettingsPanel != "permission") {
            permissionBackTarget = null
            permissionGuideReason = null
        }
    }

    fun markDisabledReminderSettingsAsSaved() {
        lastSavedNotificationsEnabled = notificationsEnabled
        lastSavedAlarmModeEnabled = false
        lastSavedVibrationReminderEnabled = false
        lastSavedSoundReminderEnabled = false
        lastSavedSoundReminderToneId = soundReminderToneId
        lastSavedReminderMinutesText = reminderMinutesText
    }

    LaunchedEffect(activeLessonTimeProfileId, lessonTimeProfiles, draftLessonTimeProfile?.id) {
        val selectedIsDraft = draftLessonTimeProfile?.id == selectedLessonTimeProfileId
        if (!selectedIsDraft && lessonTimeProfiles.none { it.id == selectedLessonTimeProfileId }) {
            selectedLessonTimeProfileId = activeLessonTimeProfileId
        }
    }

    BackHandler(enabled = activeSettingsPanel != null) {
        goBack()
    }

    LaunchedEffect(activeSettingsPanel) {
        onTopLevelChange(activeSettingsPanel == null)
    }

    DisposableEffect(Unit) {
        onDispose {
            onTopLevelChange(true)
        }
    }

    LaunchedEffect(requestPermissionTick) {
        if (requestPermissionTick > 0) {
            openExactAlarmPermission(context)
        }
    }

    LaunchedEffect(requestNotificationPermissionTick) {
        if (requestNotificationPermissionTick > 0) {
            requestNotificationPermission(context, notificationPermissionLauncher)
        }
    }

    LaunchedEffect(requestNotificationStyleTick) {
        if (requestNotificationStyleTick > 0) {
            SystemAlarmScheduler.openAlarmNotificationChannelSettings(context)
        }
    }

    LaunchedEffect(requestBatteryOptimizationTick) {
        if (requestBatteryOptimizationTick > 0) {
            com.example.pinkschedule.reminder.SystemAlarmScheduler.openBatteryOptimizationSettings(context)
        }
    }

    LaunchedEffect(requestAutoStartTick) {
        if (requestAutoStartTick > 0) {
            com.example.pinkschedule.reminder.SystemAlarmScheduler.openAutoStartSettings(context)
        }
    }

    LaunchedEffect(
        notificationsEnabled,
        alarmModeEnabled,
        vibrationReminderEnabled,
        soundReminderEnabled,
        soundReminderToneId
    ) {
        if (
            notificationsEnabled == lastSavedNotificationsEnabled &&
            alarmModeEnabled == lastSavedAlarmModeEnabled &&
            vibrationReminderEnabled == lastSavedVibrationReminderEnabled &&
            soundReminderEnabled == lastSavedSoundReminderEnabled &&
            soundReminderToneId == lastSavedSoundReminderToneId
        ) return@LaunchedEffect
        val minutes = reminderMinutesText.toIntOrNull()
        if (minutes == null) {
            onErrorMessage("请输入有效的分钟数。")
            return@LaunchedEffect
        }
        val action = onSaveSettings(
            notificationsEnabled,
            alarmModeEnabled,
            vibrationReminderEnabled,
            soundReminderEnabled,
            soundReminderToneId,
            minutes
        )
        action.message?.takeIf(::isErrorMessage)?.let(onErrorMessage)
        if (action.requestExactAlarmPermission) {
            onAlarmModeEnabledChange(false)
            onVibrationReminderEnabledChange(false)
            onSoundReminderEnabledChange(false)
            markDisabledReminderSettingsAsSaved()
            permissionBackTarget = "alarm"
            permissionGuideReason = "exact"
            activeSettingsPanel = "permission"
        } else if (action.requestNotificationPermission) {
            onAlarmModeEnabledChange(false)
            onVibrationReminderEnabledChange(false)
            onSoundReminderEnabledChange(false)
            markDisabledReminderSettingsAsSaved()
            permissionBackTarget = "alarm"
            permissionGuideReason = "notification"
            activeSettingsPanel = "permission"
        } else {
            lastSavedNotificationsEnabled = notificationsEnabled
            lastSavedAlarmModeEnabled = alarmModeEnabled
            lastSavedVibrationReminderEnabled = vibrationReminderEnabled
            lastSavedSoundReminderEnabled = soundReminderEnabled
            lastSavedSoundReminderToneId = soundReminderToneId
            lastSavedReminderMinutesText = reminderMinutesText
        }
    }

    LaunchedEffect(reminderMinutesText) {
        if (reminderMinutesText == lastSavedReminderMinutesText) return@LaunchedEffect
        delay(500)
        if (reminderMinutesText != lastSavedReminderMinutesText) {
            val minutes = reminderMinutesText.toIntOrNull()
            if (minutes == null) {
                onErrorMessage("请输入有效的分钟数。")
                return@LaunchedEffect
            }
            val action = onSaveSettings(
                notificationsEnabled,
                alarmModeEnabled,
                vibrationReminderEnabled,
                soundReminderEnabled,
                soundReminderToneId,
                minutes
            )
            action.message?.takeIf(::isErrorMessage)?.let(onErrorMessage)
            if (action.requestExactAlarmPermission) {
                onAlarmModeEnabledChange(false)
                onVibrationReminderEnabledChange(false)
                onSoundReminderEnabledChange(false)
                markDisabledReminderSettingsAsSaved()
                permissionBackTarget = "alarm"
                permissionGuideReason = "exact"
                activeSettingsPanel = "permission"
            } else if (action.requestNotificationPermission) {
                onAlarmModeEnabledChange(false)
                onVibrationReminderEnabledChange(false)
                onSoundReminderEnabledChange(false)
                markDisabledReminderSettingsAsSaved()
                permissionBackTarget = "alarm"
                permissionGuideReason = "notification"
                activeSettingsPanel = "permission"
            } else {
                lastSavedNotificationsEnabled = notificationsEnabled
                lastSavedAlarmModeEnabled = alarmModeEnabled
                lastSavedVibrationReminderEnabled = vibrationReminderEnabled
                lastSavedSoundReminderEnabled = soundReminderEnabled
                lastSavedSoundReminderToneId = soundReminderToneId
                lastSavedReminderMinutesText = reminderMinutesText
            }
        }
    }

    DisposableEffect(lifecycleOwner, onRefreshPermission) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshManualPermissionState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(if (activeSettingsPanel == null) 24.dp else 16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        SettingsTitle(
            title = when (activeSettingsPanel) {
                "permission" -> "系统权限"
                "alarm" -> "通知"
                "time" -> "作息时间"
                "class" -> "班级"
                "time-view-list" -> "查看作息表"
                "time-view" -> lessonTimeProfiles.firstOrNull { it.id == selectedLessonTimeProfileId }?.name ?: "作息时间"
                "time-current" -> "设置当前作息表"
                "time-edit-list" -> "编辑作息表"
                "time-edit" -> if (draftLessonTimeProfile?.id == selectedLessonTimeProfileId) "新增作息表" else "编辑作息时间"
                else -> "设置"
            },
            showBack = activeSettingsPanel != null,
            onBack = { goBack() }
        )

        when (activeSettingsPanel) {
            null -> {
                SettingsListGroup {
                    SettingsListRow(
                        icon = Icons.Filled.Security,
                        title = "系统权限",
                        onClick = {
                            refreshManualPermissionState()
                            permissionBackTarget = null
                            permissionGuideReason = null
                            activeSettingsPanel = "permission"
                        }
                    )
                    SettingsListRow(
                        icon = Icons.Filled.NotificationsActive,
                        title = "通知",
                        onClick = { activeSettingsPanel = "alarm" }
                    )
                    SettingsListRow(
                        icon = Icons.Filled.AccessTime,
                        title = "作息时间",
                        onClick = { activeSettingsPanel = "time" }
                    )
                    SettingsListRow(
                        icon = Icons.Filled.TableChart,
                        title = "班级",
                        onClick = { activeSettingsPanel = "class" }
                    )
                    SettingsListRow(
                        icon = Icons.Filled.FileUpload,
                        title = "导入数据",
                        onClick = onImportData
                    )
                    SettingsListRow(
                        icon = Icons.Filled.FileDownload,
                        title = "导出数据",
                        onClick = onExportData
                    )
                    SettingsListRow(
                        icon = Icons.Filled.Share,
                        title = "分享课程表",
                        onClick = onShareScheduleImage
                    )
                }
            }
            "permission" -> {
                PermissionSettingsPage(
                    exactAlarmPermissionGranted = exactAlarmPermissionGranted,
                    notificationPermissionGranted = notificationPermissionGranted,
                    batteryOptimizationIgnored = batteryOptimizationIgnored,
                    onRequestExactAlarmPermission = { requestPermissionTick += 1 },
                    onRequestNotificationPermission = { requestNotificationPermissionTick += 1 },
                    onRequestNotificationStyle = { requestNotificationStyleTick += 1 },
                    onRequestBatteryOptimization = { requestBatteryOptimizationTick += 1 },
                    onRequestAutoStart = { requestAutoStartTick += 1 },
                    onRefresh = { refreshManualPermissionState() },
                    onBackToAlarmSettings = {
                        permissionGuideReason = null
                        activeSettingsPanel = "alarm"
                    },
                    guideText = permissionGuideText(
                        reason = permissionGuideReason,
                        exactAlarmPermissionGranted = exactAlarmPermissionGranted,
                        notificationPermissionGranted = notificationPermissionGranted,
                        batteryOptimizationIgnored = batteryOptimizationIgnored
                    )
                )
            }
            "alarm" -> {
                val notificationControlsEnabled = notificationsEnabled
                SettingsListGroup {
                    SettingsListRow(
                        title = "关闭通知",
                        description = if (notificationsEnabled) "关闭后不显示任何课程提醒" else "已关闭",
                        trailing = {
                            SettingsSwitch(
                                checked = !notificationsEnabled,
                                onCheckedChange = { checked -> onNotificationsEnabledChange(!checked) }
                            )
                        }
                    )
                    SettingsListRow(
                        title = "闹钟提醒",
                        description = if (alarmModeEnabled) "开启" else "关闭",
                        enabled = notificationControlsEnabled,
                        trailing = {
                            SettingsSwitch(
                                checked = alarmModeEnabled,
                                onCheckedChange = onAlarmModeEnabledChange,
                                enabled = notificationControlsEnabled
                            )
                        }
                    )
                    SettingsListRow(
                        title = "震动提醒",
                        description = if (vibrationReminderEnabled) "开启" else "关闭",
                        enabled = notificationControlsEnabled,
                        trailing = {
                            SettingsSwitch(
                                checked = vibrationReminderEnabled,
                                onCheckedChange = onVibrationReminderEnabledChange,
                                enabled = notificationControlsEnabled
                            )
                        }
                    )
                    SettingsListRow(
                        title = "提示音",
                        description = if (soundReminderEnabled) "开启" else "关闭",
                        enabled = notificationControlsEnabled,
                        trailing = {
                            SettingsSwitch(
                                checked = soundReminderEnabled,
                                onCheckedChange = onSoundReminderEnabledChange,
                                enabled = notificationControlsEnabled
                            )
                        }
                    )
                    if (soundReminderEnabled) {
                        SettingsListRow(
                            title = "提示音设置",
                            description = ReminderTone.resolve(soundReminderToneId).label,
                            enabled = notificationControlsEnabled,
                            onClick = { showSoundToneDialog = true }
                        )
                    }
                    SettingsListRow(
                        title = "提前提醒",
                        enabled = notificationControlsEnabled,
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ReminderMinuteField(
                                    value = reminderMinutesText,
                                    onValueChange = { onReminderMinutesTextChange(it.filter(Char::isDigit)) },
                                    enabled = notificationControlsEnabled
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("分钟", color = Color(0xFF8F878C), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    )
                }
                if (showSoundToneDialog) {
                    SoundTonePickerDialog(
                        selectedToneId = soundReminderToneId,
                        onSelectTone = onSoundReminderToneChange,
                        onDismiss = { showSoundToneDialog = false }
                    )
                }
            }
            "time" -> {
                RestScheduleSettingsPage(
                    onViewSchedules = { activeSettingsPanel = "time-view-list" },
                    onSetCurrentSchedule = { activeSettingsPanel = "time-current" },
                    onEditSchedules = { activeSettingsPanel = "time-edit-list" }
                )
            }
            "class" -> {
                ClassPresetSettingsPage(
                    presets = classPresets,
                    onAddPreset = onAddClassPreset,
                    onRenamePreset = onRenameClassPreset,
                    onDeletePreset = onDeleteClassPreset,
                    onErrorMessage = onErrorMessage
                )
            }
            "time-current" -> {
                RestScheduleCurrentPage(
                    profiles = lessonTimeProfiles,
                    activeProfileId = activeLessonTimeProfileId,
                    onSelectProfile = { profileId ->
                        selectedLessonTimeProfileId = profileId
                        onSelectLessonTimeProfile(profileId)
                    }
                )
            }
            "time-view-list" -> {
                RestScheduleListPage(
                    profiles = lessonTimeProfiles,
                    activeProfileId = activeLessonTimeProfileId,
                    onSelectProfile = { profileId ->
                        selectedLessonTimeProfileId = profileId
                        activeSettingsPanel = "time-view"
                    }
                )
            }
            "time-view" -> {
                RestScheduleViewPage(
                    profile = lessonTimeProfiles.firstOrNull { it.id == selectedLessonTimeProfileId }
                )
            }
            "time-edit-list" -> {
                RestScheduleEditListPage(
                    profiles = lessonTimeProfiles,
                    activeProfileId = activeLessonTimeProfileId,
                    onSelectProfile = { profileId ->
                        selectedLessonTimeProfileId = profileId
                        activeSettingsPanel = "time-edit"
                    },
                    onAddProfile = {
                        val draft = LessonTimeProfile(
                            id = "draft-${System.currentTimeMillis()}",
                            name = "作息表 ${lessonTimeProfiles.size + 1}",
                            slots = lessonTimes.sortedBy { it.period }
                        )
                        draftLessonTimeProfile = draft
                        selectedLessonTimeProfileId = draft.id
                        activeSettingsPanel = "time-edit"
                    },
                    onDeleteProfiles = onDeleteLessonTimeProfiles
                )
            }
            "time-edit" -> {
                val editingProfiles = draftLessonTimeProfile?.let { lessonTimeProfiles + it } ?: lessonTimeProfiles
                val isNewProfile = draftLessonTimeProfile?.id == selectedLessonTimeProfileId
                LessonTimeEditPage(
                    profiles = editingProfiles,
                    selectedProfileId = selectedLessonTimeProfileId,
                    isNewProfile = isNewProfile,
                    lessonTimes = lessonTimes,
                    coursePeriods = coursePeriods,
                    onRenameProfile = onRenameLessonTimeProfile,
                    onDraftProfileChange = { updatedDraft ->
                        draftLessonTimeProfile = updatedDraft
                    },
                    onSaveNewProfile = { name, slots ->
                        val createdId = onAddLessonTimeProfile(name, slots)
                        if (createdId != null) {
                            draftLessonTimeProfile = null
                            selectedLessonTimeProfileId = createdId
                        }
                        createdId
                    },
                    onUpdateLessonTime = { period, start, end ->
                        onUpdateLessonTimeForProfile(selectedLessonTimeProfileId, period, start, end)
                    },
                    onDeleteLessonTime = { period ->
                        onDeleteLessonTimeFromProfile(selectedLessonTimeProfileId, period)
                    },
                    context = context
                )
            }
        }
    }
}

@Composable
private fun RestScheduleSettingsPage(
    onViewSchedules: () -> Unit,
    onSetCurrentSchedule: () -> Unit,
    onEditSchedules: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
        SettingsListGroup {
            SettingsListRow(title = "查看作息表", onClick = onViewSchedules)
            SettingsListRow(title = "设置当前作息表", onClick = onSetCurrentSchedule)
            SettingsListRow(title = "编辑作息表", onClick = onEditSchedules)
        }
    }
}

@Composable
private fun ClassPresetSettingsPage(
    presets: List<String>,
    onAddPreset: (String) -> String?,
    onRenamePreset: (String, String) -> String?,
    onDeletePreset: (String) -> Unit,
    onErrorMessage: (String) -> Unit
) {
    var editingPreset by remember { mutableStateOf<String?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
        SettingsListGroup {
            if (presets.isEmpty()) {
                EmptySettingsState(
                    title = "暂无班级",
                    description = "点击下方按钮新增常用班级。"
                )
            } else {
                presets.forEach { preset ->
                    SettingsListRow(
                        title = preset,
                        trailing = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(
                                    onClick = { editingPreset = preset },
                                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFE26786)),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("编辑", fontWeight = FontWeight.SemiBold)
                                }
                                TextButton(
                                    onClick = { onDeletePreset(preset) },
                                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFE26786)),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("删除", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    )
                }
            }
        }
        Button(
            onClick = { showAddDialog = true },
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE26786), contentColor = Color.White),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("新增班级", fontWeight = FontWeight.Bold)
        }
    }

    if (showAddDialog) {
        ClassPresetDialog(
            title = "新增班级",
            initialValue = "",
            onDismiss = { showAddDialog = false },
            onSave = { name ->
                val error = onAddPreset(name)
                if (error == null) {
                    showAddDialog = false
                } else {
                    onErrorMessage(error)
                }
            }
        )
    }
    editingPreset?.let { preset ->
        ClassPresetDialog(
            title = "编辑班级",
            initialValue = preset,
            onDismiss = { editingPreset = null },
            onSave = { name ->
                val error = onRenamePreset(preset, name)
                if (error == null) {
                    editingPreset = null
                } else {
                    onErrorMessage(error)
                }
            }
        )
    }
}

@Composable
private fun ClassPresetDialog(
    title: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = Color(0xFFFFFCFA),
        title = { DialogTitle(title, onDismiss = onDismiss) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("班级名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFFE26786),
                    unfocusedBorderColor = Color(0xFFF0DDD2),
                    focusedContainerColor = Color(0xFFFFF7F4),
                    unfocusedContainerColor = Color(0xFFFFF7F4)
                )
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(value) }) {
                Text("保存", color = Color(0xFFE26786), fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun RestScheduleCurrentPage(
    profiles: List<LessonTimeProfile>,
    activeProfileId: String,
    onSelectProfile: (String) -> Unit
) {
    SettingsListGroup {
        if (profiles.isEmpty()) {
            EmptySettingsState(
                title = "暂无作息表",
                description = "请先在编辑作息表中新增一个作息表。"
            )
        } else {
            profiles.forEach { profile ->
                SettingsListRow(
                    title = if (profile.id == activeProfileId) "${profile.name}（当前）" else profile.name,
                    trailing = {
                        Text(
                            "${profile.slots.size} 节",
                            color = Color(0xFF8F878C),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    onClick = { onSelectProfile(profile.id) }
                )
            }
        }
    }

}

@Composable
private fun RestScheduleListPage(
    profiles: List<LessonTimeProfile>,
    activeProfileId: String,
    onSelectProfile: (String) -> Unit,
    trailing: (() -> Unit)? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
        trailing?.let {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                CompactAddButton(onClick = it)
            }
        }
        SettingsListGroup {
            if (profiles.isEmpty()) {
                EmptySettingsState(
                    title = "暂无作息表",
                    description = "请先新增一个作息表。"
                )
            } else {
                profiles.forEach { profile ->
                    SettingsListRow(
                        title = if (profile.id == activeProfileId) "${profile.name}（当前）" else profile.name,
                        trailing = {
                            Text(
                                "${profile.slots.size} 节",
                                color = Color(0xFF8F878C),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        onClick = { onSelectProfile(profile.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun RestScheduleEditListPage(
    profiles: List<LessonTimeProfile>,
    activeProfileId: String,
    onSelectProfile: (String) -> Unit,
    onAddProfile: () -> Unit,
    onDeleteProfiles: (Set<String>) -> Unit
) {
    var selectedForDelete by remember(profiles) { mutableStateOf(emptySet<String>()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val removableSelection = selectedForDelete - activeProfileId
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
        SettingsListGroup {
            if (profiles.isEmpty()) {
                EmptySettingsState(
                    title = "暂无作息表",
                    description = "点击下方按钮新增一个作息表。"
                )
            } else {
                profiles.forEach { profile ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = profile.id in selectedForDelete,
                            onCheckedChange = { checked ->
                                selectedForDelete = if (checked) {
                                    selectedForDelete + profile.id
                                } else {
                                    selectedForDelete - profile.id
                                }
                            },
                            modifier = Modifier.alpha(if (profile.id == activeProfileId) 0.42f else 1f),
                            enabled = profile.id != activeProfileId,
                            colors = CheckboxDefaults.colors(
                                checkedColor = Color(0xFFE26786),
                                uncheckedColor = Color(0xFFB8B1B5),
                                disabledUncheckedColor = Color(0xFFE3D5D0),
                                checkmarkColor = Color.White
                            )
                        )
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { onSelectProfile(profile.id) },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (profile.id == activeProfileId) "${profile.name}（当前）" else profile.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = Color(0xFF303036),
                                fontWeight = FontWeight.Normal
                            )
                            Text(
                                "${profile.slots.size} 节",
                                color = Color(0xFF8F878C),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
        Button(
            onClick = onAddProfile,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE26786), contentColor = Color.White),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("新增作息表", fontWeight = FontWeight.Bold)
        }
        if (profiles.isNotEmpty()) {
            OutlinedButton(
                onClick = {
                    showDeleteConfirm = true
                },
                enabled = removableSelection.isNotEmpty(),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color(0xFFF0DDD2)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("删除选中作息表", color = Color(0xFFE26786), fontWeight = FontWeight.SemiBold)
            }
        }
    }
    if (showDeleteConfirm) {
        val deleteCount = removableSelection.size
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            shape = RoundedCornerShape(24.dp),
            containerColor = Color(0xFFFFFCFA),
            title = { DialogTitle("删除作息表？", onDismiss = { showDeleteConfirm = false }) },
            text = {
                Text(
                    "确定删除选中的 ${deleteCount} 个作息表吗？此操作无法撤销。",
                    color = Color(0xFF4B2F2C)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteProfiles(removableSelection)
                        selectedForDelete = emptySet()
                    }
                ) {
                    Text("删除", color = Color(0xFFE26786), fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

@Composable
private fun RestScheduleViewPage(
    profile: LessonTimeProfile?
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
        SettingsListGroup {
            val slots = profile?.slots.orEmpty().sortedBy { it.period }
            if (profile == null) {
                EmptySettingsState(
                    title = "暂无作息表",
                    description = "请先新增一个作息表。"
                )
            } else if (slots.isEmpty()) {
                EmptySettingsState(
                    title = "暂无作息时间",
                    description = "请在编辑作息表中添加作息时间。"
                )
            } else {
                slots.forEach { slot ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(slotLabel(slot.period), color = Color(0xFF303036), style = MaterialTheme.typography.titleMedium)
                        Text(slot.displayRange(), color = Color(0xFF8F878C), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptySettingsState(
    title: String,
    description: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(126.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFF303036),
            fontWeight = FontWeight.Normal
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFAAA2A6),
            textAlign = TextAlign.Center
        )
    }
}

private enum class AddLessonTimeType(val label: String) {
    COURSE("课程"),
    EARLY_STUDY("早自习"),
    LATE_STUDY("晚自习")
}

private fun nextLessonTimeSlot(slots: List<LessonTimeSlot>, type: AddLessonTimeType): LessonTimeSlot {
    val sorted = slots.sortedBy { it.period }
    return when (type) {
        AddLessonTimeType.EARLY_STUDY -> {
            sorted.firstOrNull { ScheduleDefaults.isEarlyStudyPeriod(ScheduleDefaults.normalizePeriod(it.period)) }
                ?: ScheduleDefaults.defaultLessonTimeSlotFor(ScheduleDefaults.EARLY_STUDY_PERIOD)
        }
        AddLessonTimeType.LATE_STUDY -> {
            val lateSlots = sorted.filter { ScheduleDefaults.isLateStudyPeriod(it.period) }
            val nextPeriod = (lateSlots.maxOfOrNull { it.period } ?: (ScheduleDefaults.LATE_STUDY_PERIOD_BASE - 1)) + 1
            val startTime = lateSlots.lastOrNull()?.endTime?.plusMinutes(10)
                ?: ScheduleDefaults.defaultLessonTimeSlotFor(ScheduleDefaults.LATE_STUDY_PERIOD_BASE).startTime
            LessonTimeSlot(nextPeriod, startTime, startTime.plusMinutes(45))
        }
        AddLessonTimeType.COURSE -> {
            val regularSlots = sorted.filter { ScheduleDefaults.isRegularCoursePeriod(it.period) }
            val nextPeriod = (regularSlots.maxOfOrNull { it.period } ?: 0) + 1
            val startTime = regularSlots.lastOrNull()?.endTime?.plusMinutes(10)
                ?: ScheduleDefaults.defaultLessonTimeSlotFor(nextPeriod).startTime
            LessonTimeSlot(nextPeriod, startTime, startTime.plusMinutes(45))
        }
    }
}

private fun upsertLessonTimeSlot(slots: List<LessonTimeSlot>, slot: LessonTimeSlot): List<LessonTimeSlot> {
    return (slots.filterNot { it.period == slot.period } + slot).sortedBy { it.period }
}

private fun replaceLessonTimeSlot(
    slots: List<LessonTimeSlot>,
    period: Int,
    startTime: LocalTime,
    endTime: LocalTime
): List<LessonTimeSlot> {
    return slots
        .filterNot { it.period == period }
        .plus(LessonTimeSlot(period, startTime, endTime))
        .sortedBy { it.period }
}

private fun removeLessonTimeSlot(slots: List<LessonTimeSlot>, period: Int): List<LessonTimeSlot> {
    return slots.sortedBy { it.period }.filterNot { it.period == period }
}

@Composable
private fun LessonTimeEditPage(
    profiles: List<LessonTimeProfile>,
    selectedProfileId: String,
    isNewProfile: Boolean,
    lessonTimes: List<LessonTimeSlot>,
    coursePeriods: Set<Int>,
    onRenameProfile: (String, String) -> Unit,
    onDraftProfileChange: (LessonTimeProfile) -> Unit,
    onSaveNewProfile: (String, List<LessonTimeSlot>) -> String?,
    onUpdateLessonTime: (Int, LocalTime, LocalTime) -> Unit,
    onDeleteLessonTime: (Int) -> Unit,
    context: Context
) {
    val selectedProfile = profiles.firstOrNull { it.id == selectedProfileId } ?: profiles.firstOrNull()
    val editingLessonTimes = selectedProfile?.slots?.sortedBy { it.period } ?: lessonTimes
    var pendingDeletePeriod by remember { mutableStateOf<Int?>(null) }
    var pendingSelfStudyOverwrite by remember { mutableStateOf<Pair<AddLessonTimeType, LessonTimeSlot>?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showAddLessonTimeDialog by remember { mutableStateOf(false) }
    var profileName by remember(selectedProfile?.id, selectedProfile?.name) {
        mutableStateOf(selectedProfile?.name.orEmpty())
    }
    var localErrorText by remember(selectedProfile?.id) { mutableStateOf<String?>(null) }
    var timeValidationErrors by remember(selectedProfile?.id) { mutableStateOf(emptyMap<Int, String>()) }

    fun updateDraftProfile(slots: List<LessonTimeSlot>? = null, name: String? = null) {
        val profile = selectedProfile ?: return
        onDraftProfileChange(
            profile.copy(
                name = name ?: profile.name,
                slots = (slots ?: profile.slots).sortedBy { it.period }
            )
        )
    }

    fun applyAddedLessonTime(slot: LessonTimeSlot) {
        if (isNewProfile) {
            updateDraftProfile(slots = upsertLessonTimeSlot(editingLessonTimes, slot))
        } else {
            onUpdateLessonTime(slot.period, slot.startTime, slot.endTime)
        }
        showAddLessonTimeDialog = false
    }

    fun handleAddLessonTime(type: AddLessonTimeType, start: LocalTime, end: LocalTime) {
        if (!end.isAfter(start)) {
            localErrorText = "结束时间必须晚于开始时间。"
            return
        }
        localErrorText = null
        val targetSlot = nextLessonTimeSlot(editingLessonTimes, type).copy(startTime = start, endTime = end)
        if (type == AddLessonTimeType.EARLY_STUDY &&
            editingLessonTimes.any { it.period == targetSlot.period }
        ) {
            pendingSelfStudyOverwrite = type to targetSlot
            showAddLessonTimeDialog = false
            return
        }
        applyAddedLessonTime(targetSlot)
    }

    fun handleUpdateLessonTime(period: Int, start: LocalTime, end: LocalTime) {
        if (!end.isAfter(start)) {
            timeValidationErrors = timeValidationErrors + (period to "结束时间必须晚于开始时间。")
            return
        }
        timeValidationErrors = timeValidationErrors - period
        if (isNewProfile) {
            updateDraftProfile(slots = replaceLessonTimeSlot(editingLessonTimes, period, start, end))
        } else {
            onUpdateLessonTime(period, start, end)
        }
    }

    fun handleDeleteLessonTime(period: Int) {
        if (isNewProfile) {
            val updated = removeLessonTimeSlot(editingLessonTimes, period)
            localErrorText = null
            updateDraftProfile(slots = updated)
        } else {
            onDeleteLessonTime(period)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
        selectedProfile?.let { profile ->
            SettingsListGroup {
                SettingsListRow(
                    title = profile.name,
                    trailing = {
                        Text("修改名称", color = Color(0xFFE26786), style = MaterialTheme.typography.bodyMedium)
                    },
                    onClick = {
                        profileName = profile.name
                        localErrorText = null
                        showRenameDialog = true
                    }
                )
                if (isNewProfile) {
                    Button(
                        onClick = {
                            val trimmedName = profile.name.trim()
                            if (trimmedName.isBlank()) {
                                localErrorText = "作息表名称不能为空。"
                            } else if (onSaveNewProfile(trimmedName, editingLessonTimes) == null) {
                                localErrorText = "保存作息表失败。"
                            } else {
                                localErrorText = null
                            }
                        },
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE26786), contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        contentPadding = PaddingValues(vertical = 10.dp)
                    ) {
                        Text("保存作息表", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        localErrorText?.let {
            Text(
                text = it,
                color = Color(0xFFD33C63),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Text(
            text = "${editingLessonTimes.size} 节",
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFF202023),
            fontWeight = FontWeight.Black
        )
        SettingsListGroup {
            editingLessonTimes.forEach { slot ->
                LessonTimeSlotRow(
                    slot = slot,
                    validationErrorText = timeValidationErrors[slot.period],
                    onUpdateLessonTime = ::handleUpdateLessonTime,
                    onDeleteLessonTime = { period ->
                        pendingDeletePeriod = period
                    },
                    context = context
                )
            }
            Button(
                onClick = {
                    localErrorText = null
                    showAddLessonTimeDialog = true
                },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE26786), contentColor = Color.White),
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 10.dp)
            ) {
                Icon(Icons.Filled.AddCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("新增作息时间", fontWeight = FontWeight.Bold)
            }
        }
    }
    if (showRenameDialog && selectedProfile != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            shape = RoundedCornerShape(24.dp),
            containerColor = Color(0xFFFFFCFA),
            title = { DialogTitle("修改作息表名称", onDismiss = { showRenameDialog = false }) },
            text = {
                OutlinedTextField(
                    value = profileName,
                    onValueChange = { profileName = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFE26786),
                        unfocusedBorderColor = Color(0xFFF0DDD2),
                        focusedContainerColor = Color(0xFFFFF7F4),
                        unfocusedContainerColor = Color(0xFFFFF7F4)
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmedName = profileName.trim()
                        if (trimmedName.isBlank()) {
                            localErrorText = "作息表名称不能为空。"
                        } else {
                            if (isNewProfile) {
                                updateDraftProfile(name = trimmedName)
                            } else {
                                onRenameProfile(selectedProfile.id, trimmedName)
                            }
                            showRenameDialog = false
                            localErrorText = null
                        }
                    }
                ) {
                    Text("保存", color = Color(0xFFE26786), fontWeight = FontWeight.Bold)
                }
            }
        )
    }
    if (showAddLessonTimeDialog) {
        var selectedType by remember(selectedProfile?.id, editingLessonTimes.size, showAddLessonTimeDialog) {
            mutableStateOf(AddLessonTimeType.COURSE)
        }
        val defaultSlot = nextLessonTimeSlot(editingLessonTimes, selectedType)
        var startTime by remember(selectedProfile?.id, editingLessonTimes.size, showAddLessonTimeDialog, selectedType) {
            mutableStateOf(defaultSlot.startTime)
        }
        var endTime by remember(selectedProfile?.id, editingLessonTimes.size, showAddLessonTimeDialog, selectedType) {
            mutableStateOf(defaultSlot.endTime)
        }
        AddLessonTimeDialog(
            type = selectedType,
            period = defaultSlot.period,
            startTime = startTime,
            endTime = endTime,
            errorText = localErrorText,
            onTypeChange = { selectedType = it },
            onStartTimeChange = { startTime = it },
            onEndTimeChange = { endTime = it },
            onDismiss = {
                showAddLessonTimeDialog = false
                localErrorText = null
            },
            onSave = { handleAddLessonTime(selectedType, startTime, endTime) },
            context = context
        )
    }
    pendingSelfStudyOverwrite?.let { (type, slot) ->
        AlertDialog(
            onDismissRequest = { pendingSelfStudyOverwrite = null },
            shape = RoundedCornerShape(24.dp),
            containerColor = Color(0xFFFFFCFA),
            title = { DialogTitle("覆盖${type.label}？", onDismiss = { pendingSelfStudyOverwrite = null }) },
            text = {
                Text(
                    "当前作息表中已经有${type.label}。是否用新的时间覆盖原${type.label}？",
                    color = Color(0xFF4B2F2C)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingSelfStudyOverwrite = null
                        applyAddedLessonTime(slot)
                    }
                ) {
                    Text("覆盖", color = Color(0xFFE26786), fontWeight = FontWeight.Bold)
                }
            }
        )
    }
    pendingDeletePeriod?.let { period ->
        AlertDialog(
            onDismissRequest = { pendingDeletePeriod = null },
            shape = RoundedCornerShape(24.dp),
            containerColor = Color(0xFFFFFCFA),
            title = { DialogTitle("删除作息时间？", onDismiss = { pendingDeletePeriod = null }) },
            text = {
                Text(
                    if (!isNewProfile && period in coursePeriods) {
                        "${slotLabel(period)}已有课程。继续删除会同时删除所有${slotLabel(period)}课程，是否继续？"
                    } else {
                        "确定删除${slotLabel(period)}作息时间吗？"
                    },
                    color = Color(0xFF4B2F2C)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeletePeriod = null
                        handleDeleteLessonTime(period)
                    }
                ) {
                    Text("继续删除", color = Color(0xFFE26786), fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

@Composable
private fun PermissionSettingsPage(
    exactAlarmPermissionGranted: Boolean,
    notificationPermissionGranted: Boolean,
    batteryOptimizationIgnored: Boolean,
    onRequestExactAlarmPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onRequestNotificationStyle: () -> Unit,
    onRequestBatteryOptimization: () -> Unit,
    onRequestAutoStart: () -> Unit,
    onRefresh: () -> Unit,
    onBackToAlarmSettings: () -> Unit,
    guideText: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            text = guideText,
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF6F6468)
        )
        SettingsListGroup {
            PermissionListRow(
                title = "精确闹钟",
                description = "允许应用按作息时间创建准时闹钟。",
                granted = exactAlarmPermissionGranted,
                actionLabel = if (exactAlarmPermissionGranted) "查看" else "去授权",
                onClick = onRequestExactAlarmPermission
            )
            PermissionListRow(
                title = "通知权限",
                description = "允许到点时显示课程通知、播放提示音或震动。",
                granted = notificationPermissionGranted,
                actionLabel = if (notificationPermissionGranted) "查看" else "去授权",
                onClick = onRequestNotificationPermission
            )
            PermissionListRow(
                title = "锁屏和横幅通知",
                description = "请在闹钟提醒、上课提醒通知类别中打开锁屏通知、横幅或悬浮通知。",
                granted = null,
                actionLabel = "去设置",
                onClick = onRequestNotificationStyle
            )
            PermissionListRow(
                title = "忽略电池优化",
                description = "允许系统休眠时保持闹钟投递更稳定。",
                granted = batteryOptimizationIgnored,
                actionLabel = if (batteryOptimizationIgnored) "查看" else "去授权",
                onClick = onRequestBatteryOptimization
            )
            PermissionListRow(
                title = "自启动与后台运行",
                description = "部分手机需要在系统管家里允许自启动、后台运行或无限制耗电。",
                granted = null,
                actionLabel = "去设置",
                onClick = onRequestAutoStart
            )
        }
        OutlinedButton(
            onClick = onRefresh,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Color(0xFFF0DDD2)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("刷新授权状态", color = Color(0xFFE26786), fontWeight = FontWeight.SemiBold)
        }
        Button(
            onClick = onBackToAlarmSettings,
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE26786), contentColor = Color.White),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("返回通知", fontWeight = FontWeight.Bold)
        }
    }
}

private fun permissionGuideText(
    reason: String?,
    exactAlarmPermissionGranted: Boolean,
    notificationPermissionGranted: Boolean,
    batteryOptimizationIgnored: Boolean
): String {
    return when (reason) {
        "exact" -> "通知功能无法正常使用。请先完成必要的系统权限授权，授权后返回通知页，再打开需要的提醒方式。"
        "notification" -> "通知功能无法正常使用。请先完成必要的系统权限授权，否则到点时无法在锁屏和通知栏显示上课提醒。授权后返回通知页继续开启提醒。"
        else -> when {
            !exactAlarmPermissionGranted -> "课程提醒需要精确闹钟权限，才能按作息时间准时触发。"
            !notificationPermissionGranted -> "课程提醒需要通知权限，才能在锁屏和通知栏显示上课提醒。"
            !batteryOptimizationIgnored -> "基础权限已完成。为了减少息屏延迟，可继续允许忽略电池优化和后台运行。"
            else -> "系统权限状态正常。你也可以按手机系统要求检查锁屏通知、自启动和后台运行设置。"
        }
    }
}

@Composable
private fun PermissionListRow(
    title: String,
    description: String,
    granted: Boolean?,
    actionLabel: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF303036),
                fontWeight = FontWeight.Normal,
                modifier = Modifier.weight(1f)
            )
            PermissionStatusBadge(granted = granted)
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF8F878C),
            modifier = Modifier.padding(end = 4.dp)
        )
        TextButton(
            onClick = onClick,
            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFE26786)),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
            modifier = Modifier
                .align(Alignment.End)
                .height(30.dp)
        ) {
            Text(actionLabel, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun PermissionStatusBadge(granted: Boolean?) {
    val (text, background, content, icon) = when (granted) {
        true -> StatusBadgeStyle(
            text = "已允许",
            background = Color(0xFFEAF7F0),
            content = Color(0xFF2E8B57),
            icon = Icons.Filled.CheckCircle
        )
        false -> StatusBadgeStyle(
            text = "未授权",
            background = Color(0xFFFFEEF2),
            content = Color(0xFFD33C63),
            icon = Icons.Filled.ErrorOutline
        )
        null -> StatusBadgeStyle(
            text = "需确认",
            background = Color(0xFFFFF5E6),
            content = Color(0xFFC27819),
            icon = Icons.Filled.ErrorOutline
        )
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = background
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
            Text(text, color = content, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
    }
}

private data class StatusBadgeStyle(
    val text: String,
    val background: Color,
    val content: Color,
    val icon: ImageVector
)

@Composable
private fun ReminderMinuteField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean = true
) {
    Surface(
        modifier = Modifier
            .width(58.dp)
            .height(32.dp),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFFFFF3F7)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = Color(0xFF303036),
                    textAlign = TextAlign.Center
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun CompactAddButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFFE26786),
            contentColor = Color.White
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp),
        modifier = Modifier
            .height(32.dp)
            .defaultMinSize(minWidth = 1.dp, minHeight = 1.dp)
    ) {
        Icon(Icons.Filled.AddCircle, contentDescription = null, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(5.dp))
        Text("新增", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun LessonTimeSlotRow(
    slot: LessonTimeSlot,
    validationErrorText: String?,
    onUpdateLessonTime: (Int, LocalTime, LocalTime) -> Unit,
    onDeleteLessonTime: (Int) -> Unit,
    context: Context
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = slotLabel(slot.period),
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFF303036),
            fontWeight = FontWeight.Normal
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TimePickerButton(
                label = "开始",
                time = slot.startTime,
                modifier = Modifier.weight(1f),
                onTimeSelected = { start -> onUpdateLessonTime(slot.period, start, slot.endTime) },
                context = context
            )
            TimePickerButton(
                label = "结束",
                time = slot.endTime,
                modifier = Modifier.weight(1f),
                onTimeSelected = { end -> onUpdateLessonTime(slot.period, slot.startTime, end) },
                context = context
            )
            TextButton(
                onClick = { onDeleteLessonTime(slot.period) },
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFE26786)),
                contentPadding = PaddingValues(horizontal = 6.dp),
                modifier = Modifier
                    .height(34.dp)
                    .defaultMinSize(minWidth = 1.dp, minHeight = 1.dp)
            ) {
                Icon(Icons.Filled.Delete, contentDescription = "删除", modifier = Modifier.size(15.dp))
            }
        }
        validationErrorText?.let {
            Text(
                text = it,
                color = Color(0xFFD33C63),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 2.dp)
            )
        }
    }
}

@Composable
private fun SettingsTitle(
    title: String,
    showBack: Boolean,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showBack) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        onBack()
                    },
                contentAlignment = Alignment.Center
            ) {
                ThinBackIcon(modifier = Modifier.size(24.dp), color = Color(0xFF202023))
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = Color(0xFF202023),
            fontWeight = FontWeight.Black
        )
    }
}

@Composable
private fun SettingsListGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = Color.White.copy(alpha = 0.94f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
            content()
        }
    }
}

@Composable
private fun SettingsListRow(
    icon: ImageVector? = null,
    title: String,
    description: String? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick == null || !enabled) {
                    Modifier
                } else {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        onClick()
                    }
                }
            )
            .alpha(if (enabled) 1f else 0.45f)
            .padding(vertical = if (description == null) 18.dp else 14.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                tint = Color(0xFFE26786),
                modifier = Modifier.size(30.dp)
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF303036),
                fontWeight = FontWeight.Normal
            )
            description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFAAA2A6)
                )
            }
        }
        if (trailing == null) {
            ThinChevronRightIcon(modifier = Modifier.size(18.dp), color = Color(0xFFB8B1B5))
        } else {
            trailing()
        }
    }
}

@Composable
private fun SettingsSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    val trackColor = if (checked) Color(0xFFF6D2DE) else Color(0xFFC9CCCD)
    val thumbColor = if (checked) Color(0xFFE86D8C) else Color(0xFFF8EEF2)
    Box(
        modifier = Modifier
            .size(width = 54.dp, height = 32.dp)
            .alpha(if (enabled) 1f else 0.55f)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                onCheckedChange(!checked)
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(width = 48.dp, height = 20.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(trackColor)
        )
        Box(
            modifier = Modifier
                .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                .size(30.dp)
                .shadow(
                    elevation = 2.dp,
                    shape = CircleShape,
                    clip = false,
                    ambientColor = Color(0x33000000),
                    spotColor = Color(0x33000000)
                )
                .clip(CircleShape)
                .background(thumbColor)
        )
    }
}

@Composable
private fun SoundTonePickerDialog(
    selectedToneId: String,
    onSelectTone: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = Color(0xFFFFFCFA),
        title = { DialogTitle("提示音设置", onDismiss = onDismiss) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ReminderTone.OPTIONS.forEach { tone ->
                    val selected = ReminderTone.resolve(selectedToneId).id == tone.id
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = if (selected) Color(0xFFFFEEF4) else Color.Transparent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                onSelectTone(tone.id)
                                onDismiss()
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = tone.label,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color(0xFF303036),
                                    fontWeight = FontWeight.Normal
                                )
                                if (selected) {
                                    Text(
                                        text = "当前使用",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFFE26786)
                                    )
                                }
                            }
                            TextButton(
                                onClick = { AlarmPlaybackManager.previewReminderTone(context, tone.id) },
                                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFE26786)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text("试听", fontWeight = FontWeight.SemiBold)
                            }
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = Color(0xFFE26786),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun ThinBackIcon(
    modifier: Modifier = Modifier,
    color: Color
) {
    Canvas(modifier = modifier) {
        val stroke = 1.7.dp.toPx()
        val centerY = size.height / 2f
        drawLine(
            color = color,
            start = Offset(size.width * 0.22f, centerY),
            end = Offset(size.width * 0.82f, centerY),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.24f, centerY),
            end = Offset(size.width * 0.48f, size.height * 0.26f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.24f, centerY),
            end = Offset(size.width * 0.48f, size.height * 0.74f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun ThinChevronRightIcon(
    modifier: Modifier = Modifier,
    color: Color
) {
    Canvas(modifier = modifier) {
        val stroke = 1.45.dp.toPx()
        drawLine(
            color = color,
            start = Offset(size.width * 0.36f, size.height * 0.24f),
            end = Offset(size.width * 0.64f, size.height * 0.5f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.64f, size.height * 0.5f),
            end = Offset(size.width * 0.36f, size.height * 0.76f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun ThinCloseIcon(
    modifier: Modifier = Modifier,
    color: Color
) {
    Canvas(modifier = modifier) {
        val stroke = 1.55.dp.toPx()
        drawLine(
            color = color,
            start = Offset(size.width * 0.28f, size.height * 0.28f),
            end = Offset(size.width * 0.72f, size.height * 0.72f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.72f, size.height * 0.28f),
            end = Offset(size.width * 0.28f, size.height * 0.72f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun DialogTitle(
    text: String,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleLarge,
            color = Color(0xFF202023),
            fontWeight = FontWeight.Black,
            modifier = Modifier.weight(1f)
        )
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    onDismiss()
                },
            contentAlignment = Alignment.Center
        ) {
            ThinCloseIcon(modifier = Modifier.size(18.dp), color = Color(0xFF8F878C))
        }
    }
}

@Composable
private fun AddLessonTimeDialog(
    type: AddLessonTimeType,
    period: Int,
    startTime: LocalTime,
    endTime: LocalTime,
    errorText: String?,
    onTypeChange: (AddLessonTimeType) -> Unit,
    onStartTimeChange: (LocalTime) -> Unit,
    onEndTimeChange: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    context: Context
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = Color(0xFFFFFCFA),
        title = { DialogTitle("新增作息时间", onDismiss = onDismiss) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "类型",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF303036),
                    fontWeight = FontWeight.Normal
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AddLessonTimeType.entries.forEach { option ->
                        val selected = option == type
                        val colors = if (selected) {
                            ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFE26786),
                                contentColor = Color.White
                            )
                        } else {
                            ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE26786))
                        }
                        val modifier = Modifier.weight(1f).height(34.dp).defaultMinSize(minWidth = 1.dp, minHeight = 1.dp)
                        if (selected) {
                            Button(
                                onClick = { onTypeChange(option) },
                                shape = RoundedCornerShape(16.dp),
                                colors = colors,
                                modifier = modifier,
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Text(option.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            }
                        } else {
                            OutlinedButton(
                                onClick = { onTypeChange(option) },
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, Color(0xFFF0DDD2)),
                                colors = colors,
                                modifier = modifier,
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Text(option.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            }
                        }
                    }
                }
                Text(
                    text = slotLabel(period),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF303036),
                    fontWeight = FontWeight.Normal
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TimePickerButton(
                        label = "开始",
                        time = startTime,
                        modifier = Modifier.weight(1f),
                        onTimeSelected = onStartTimeChange,
                        context = context
                    )
                    TimePickerButton(
                        label = "结束",
                        time = endTime,
                        modifier = Modifier.weight(1f),
                        onTimeSelected = onEndTimeChange,
                        context = context
                    )
                }
                errorText?.let {
                    Text(
                        text = it,
                        color = Color(0xFFD33C63),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave) {
                Text("保存", color = Color(0xFFE26786), fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun TimePickerButton(
    label: String,
    time: LocalTime,
    modifier: Modifier = Modifier,
    onTimeSelected: (LocalTime) -> Unit,
    context: Context
) {
    OutlinedButton(
        onClick = {
            TimePickerDialog(
                context,
                { _, hour, minute -> onTimeSelected(LocalTime.of(hour, minute)) },
                time.hour,
                time.minute,
                true
            ).show()
        },
        modifier = modifier
            .height(34.dp)
            .defaultMinSize(minWidth = 1.dp, minHeight = 1.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color(0xFFF0DDD2)),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
    ) {
        Text(
            text = "$label ${time.format(HH_MM)}",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1
        )
    }
}

@Composable
private fun CourseEditorDialog(
    teacher: String,
    lessonTimes: List<LessonTimeSlot>,
    classPresets: List<String>,
    course: CourseItem?,
    defaultDay: DayOfWeek,
    onDismiss: () -> Unit,
    onSave: (CourseItem) -> String?
) {
    var courseName by remember(course) { mutableStateOf(course?.courseName ?: ScheduleDefaults.DEFAULT_COURSE_NAME) }
    var className by remember(course) { mutableStateOf(course?.className.orEmpty()) }
    val selectedDay = course?.dayOfWeek ?: defaultDay
    var selectedPeriod by remember(course) { mutableStateOf(course?.period ?: lessonTimes.minOfOrNull { it.period } ?: 1) }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = Color(0xFFFFFCFA),
        confirmButton = {
            Button(
                onClick = {
                    errorText = if (courseName.isBlank()) {
                        "课程名称不能为空。"
                    } else if (className.isBlank()) {
                        "班级不能为空。"
                    } else {
                        onSave(
                            CourseItem(
                                teacher = teacher.ifBlank { ScheduleDefaults.DEFAULT_TEACHER },
                                className = className.trim(),
                                dayOfWeek = selectedDay,
                                period = selectedPeriod,
                                courseName = courseName.trim()
                            )
                        )
                    }
                },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE26786), contentColor = Color.White)
            ) {
                Text(if (course == null) "新增" else "保存", fontWeight = FontWeight.Bold)
            }
        },
        title = { DialogTitle(if (course == null) "新增课程" else "编辑课程", onDismiss = onDismiss) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = courseName,
                    onValueChange = { courseName = it },
                    label = { Text("课程名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFE26786),
                        unfocusedBorderColor = Color(0xFFF0DDD2),
                        focusedContainerColor = Color(0xFFFFF7F4),
                        unfocusedContainerColor = Color(0xFFFFF7F4)
                    )
                )
                OutlinedTextField(
                    value = className,
                    onValueChange = { className = it },
                    label = { Text("班级") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFE26786),
                        unfocusedBorderColor = Color(0xFFF0DDD2),
                        focusedContainerColor = Color(0xFFFFF7F4),
                        unfocusedContainerColor = Color(0xFFFFF7F4)
                    )
                )
                if (classPresets.isNotEmpty()) {
                    ClassPresetDropdown(
                        presets = classPresets,
                        onSelect = { className = it }
                    )
                }
                PeriodDropdown(
                    selectedPeriod = selectedPeriod,
                    lessonTimes = lessonTimes,
                    onSelect = { selectedPeriod = it }
                )
                errorText?.let {
                    Text(
                        text = it,
                        color = Color(0xFFD33C63),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    )
}

@Composable
private fun PeriodDropdown(
    selectedPeriod: Int,
    lessonTimes: List<LessonTimeSlot>,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val options = lessonTimes.sortedBy { it.period }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Color(0xFFF0DDD2))
        ) {
            Text(
                text = options.firstOrNull { it.period == selectedPeriod }?.let { "节次：${slotLabel(it.period)} ${it.displayRange()}" }
                    ?: "节次：${slotLabel(selectedPeriod)}",
                fontWeight = FontWeight.SemiBold
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { slot ->
                DropdownMenuItem(
                    text = { Text("${slotLabel(slot.period)} ${slot.displayRange()}") },
                    onClick = {
                        onSelect(slot.period)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun ClassPresetDropdown(
    presets: List<String>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, Color(0xFFF0DDD2))
        ) {
            Text(
                text = "选择预设班级",
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFE26786)
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            presets.forEach { preset ->
                DropdownMenuItem(
                    text = { Text(preset) },
                    onClick = {
                        onSelect(preset)
                        expanded = false
                    }
                )
            }
        }
    }
}

private fun openExactAlarmPermission(context: Context) {
    SystemAlarmScheduler.openExactAlarmPermissionSettings(context)
}

private fun requestNotificationPermission(
    context: Context,
    launcher: androidx.activity.result.ActivityResultLauncher<String>
) {
    if (SystemAlarmScheduler.canPostNotifications(context)) {
        SystemAlarmScheduler.openAppNotificationSettings(context)
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        SystemAlarmScheduler.openAppNotificationSettings(context)
    }
}

private data class WeekdayUiItem(val id: Int, val short: String, val num: Int, val isToday: Boolean)

private fun buildWeekdays(): List<WeekdayUiItem> {
    val today = LocalDate.now()
    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    return DayOfWeek.entries.mapIndexed { index, day ->
        val date = monday.plusDays(index.toLong())
        WeekdayUiItem(day.value, dayShortLabel(day), date.dayOfMonth, date == today)
    }
}

private fun dayShortLabel(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "周一"
    DayOfWeek.TUESDAY -> "周二"
    DayOfWeek.WEDNESDAY -> "周三"
    DayOfWeek.THURSDAY -> "周四"
    DayOfWeek.FRIDAY -> "周五"
    DayOfWeek.SATURDAY -> "周六"
    DayOfWeek.SUNDAY -> "周日"
}

private fun isErrorMessage(message: String): Boolean {
    val keywords = listOf("失败", "错误", "无法", "不能", "未授予", "请输入", "不能为空", "不正确", "为空", "必须", "已移除")
    return keywords.any { message.contains(it) }
}

private fun isPermissionGuideMessage(message: String): Boolean {
    val keywords = listOf("未授予", "权限", "电池优化", "自启动", "后台运行")
    return keywords.any { message.contains(it) }
}

private fun dialogTitleForMessage(message: String, isPermissionGuide: Boolean): String {
    return when {
        isPermissionGuide -> "请先完成系统权限"
        message.startsWith("导入失败") -> "导入失败"
        message.contains("数据已导入") || message.contains("已移除") -> "导入成功"
        else -> "出错了"
    }
}

private fun importDataFromUri(
    context: Context,
    uri: Uri,
    onImport: (String) -> String?
): String? {
    return runCatching {
        val raw = context.contentResolver.openInputStream(uri)?.use { input ->
            input.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.orEmpty()
        if (raw.isBlank()) {
            "导入失败：文件为空。"
        } else {
            onImport(raw)
        }
    }.getOrElse {
        "导入失败：${it.message ?: "无法读取文件"}"
    }
}

private fun importDataFromIntent(
    context: Context,
    intent: Intent,
    onImport: (String) -> String?
): String? {
    return runCatching {
        val raw = readImportTextsFromIntent(context, intent).firstOrNull { it.isNotBlank() }.orEmpty()
        if (raw.isBlank()) {
            "导入失败：未找到可导入的数据文件或文本。"
        } else {
            onImport(raw)
        }
    }.getOrElse {
        "导入失败：${it.message ?: "无法读取微信数据"}"
    }
}

private fun readImportTextsFromIntent(context: Context, intent: Intent): List<String> {
    val seenUris = linkedSetOf<Uri>()
    return buildList {
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let { add(it) }

        intent.streamUris().forEach { uri ->
            addImportTextFromUri(context, uri, seenUris)
        }

        intent.data?.let { uri ->
            addImportTextFromUri(context, uri, seenUris)
        }

        val clipData = intent.clipData
        if (clipData != null) {
            for (index in 0 until clipData.itemCount) {
                val item = clipData.getItemAt(index)
                item.text?.toString()?.let { add(it) }
                item.uri?.let { uri ->
                    addImportTextFromUri(context, uri, seenUris)
                }
            }
        }
    }
}

private fun MutableList<String>.addImportTextFromUri(
    context: Context,
    uri: Uri,
    seenUris: MutableSet<Uri>
) {
    if (!seenUris.add(uri)) return
    val raw = context.contentResolver.openInputStream(uri)?.use { input ->
        input.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.orEmpty()
    if (raw.isNotBlank()) {
        add(raw)
    }
}

private fun Intent.streamUris(): List<Uri> {
    val single = streamUri()
    val multiple = streamUriList()
    return (listOfNotNull(single) + multiple).distinct()
}

private fun Intent.streamUri(): Uri? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
    }
}

private fun Intent.streamUriList(): List<Uri> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
    } else {
        @Suppress("DEPRECATION")
        getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
    }
}

private fun shareDataJson(
    context: Context,
    json: String
): String {
    return runCatching {
        val exportDir = File(context.cacheDir, "schedule_exports").apply { mkdirs() }
        val file = File(exportDir, "湘约一课数据.json")
        file.writeText(json, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            clipData = ClipData.newUri(context.contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "选择微信或 QQ"))
        "已生成数据文件，请选择微信或 QQ 发送。"
    }.getOrElse {
        "导出数据失败：${it.message ?: "无法生成文件"}"
    }
}

private fun shareScheduleImage(
    context: Context,
    schedule: WeeklySchedule,
    lessonTimes: List<LessonTimeSlot>
): String {
    return runCatching {
        val file = ScheduleImageExporter.exportPngFile(context, schedule, lessonTimes)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "选择微信或 QQ"))
        "已生成课程表图片，请选择微信或 QQ 发送。"
    }.getOrElse {
        "导出图片失败：${it.message ?: "无法生成图片"}"
    }
}

private fun dayLabel(day: DayOfWeek): String = day.getDisplayName(TextStyle.FULL, Locale.CHINA)
private fun slotLabel(period: Int): String = ScheduleDefaults.periodLabel(period)
private fun courseAccentColor(): Color {
    return Color(0xFFE26786)
}
private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
