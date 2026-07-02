package com.example.pinkschedule.ui

import android.Manifest
import android.app.TimePickerDialog
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.clip
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
import com.example.pinkschedule.model.CourseItem
import com.example.pinkschedule.model.LessonTimeSlot
import com.example.pinkschedule.model.ScheduleDefaults
import com.example.pinkschedule.model.WeeklySchedule
import com.example.pinkschedule.viewmodel.ScheduleViewModel
import kotlinx.coroutines.delay
import java.io.OutputStreamWriter
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

@Composable
fun PinkScheduleAppScreen(viewModel: ScheduleViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var selectedPage by rememberSaveable { mutableStateOf(AppPage.SCHEDULE) }
    var selectedDay by rememberSaveable { mutableStateOf(LocalDate.now().dayOfWeek.value) }
    var managementDay by rememberSaveable { mutableStateOf(LocalDate.now().dayOfWeek.value) }
    var editingCourse by remember { mutableStateOf<CourseItem?>(null) }
    var addingCourse by remember { mutableStateOf(false) }
    var addingCourseDay by remember { mutableStateOf(LocalDate.now().dayOfWeek) }
    var errorDialogMessage by remember { mutableStateOf<String?>(null) }
    var settingsAlarmModeEnabled by rememberSaveable { mutableStateOf(uiState.reminderSettings.alarmModeEnabled) }
    var settingsReminderMinutesText by rememberSaveable { mutableStateOf(uiState.reminderSettings.reminderMinutesBefore.toString()) }
    val importScheduleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            importScheduleFromUri(context = context, uri = it, onImport = viewModel::importScheduleJson)
                ?.takeIf(::isErrorMessage)
                ?.let { message -> errorDialogMessage = message }
        }
    }
    val exportJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            exportScheduleJsonToUri(context = context, uri = it, json = viewModel.exportScheduleJson())
                .takeIf(::isErrorMessage)
                ?.let { message -> errorDialogMessage = message }
        }
    }
    LaunchedEffect(uiState.reminderSettings) {
        settingsAlarmModeEnabled = uiState.reminderSettings.alarmModeEnabled
        settingsReminderMinutesText = uiState.reminderSettings.reminderMinutesBefore.toString()
    }

    LaunchedEffect(uiState.message) {
        uiState.message
            ?.takeIf(::isErrorMessage)
            ?.let { errorDialogMessage = it }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFFFF3F7))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 22.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                when (selectedPage) {
                    AppPage.SCHEDULE -> SchedulePage(
                        schedule = uiState.schedule,
                        lessonTimes = uiState.lessonTimes,
                        selectedDay = selectedDay,
                        onSelectDay = { selectedDay = it },
                        onEditCourse = {
                            editingCourse = it
                            selectedPage = AppPage.COURSES
                        },
                        onAddCourse = {
                            addingCourseDay = DayOfWeek.of(selectedDay)
                            addingCourse = true
                        }
                    )
                    AppPage.COURSES -> CourseManagementPage(
                        schedule = uiState.schedule,
                        lessonTimes = uiState.lessonTimes,
                        selectedDay = managementDay,
                        onSelectDay = { managementDay = it },
                        onAddCourse = {
                            addingCourseDay = DayOfWeek.of(managementDay)
                            addingCourse = true
                        },
                        onEditCourse = { editingCourse = it },
                        onDeleteCourse = viewModel::deleteCourse
                    )
                    AppPage.SETTINGS -> SettingsPage(
                        lessonTimes = uiState.lessonTimes,
                        alarmModeEnabled = settingsAlarmModeEnabled,
                        reminderMinutesText = settingsReminderMinutesText,
                        onAlarmModeEnabledChange = { settingsAlarmModeEnabled = it },
                        onReminderMinutesTextChange = { settingsReminderMinutesText = it },
                        onSaveSettings = viewModel::updateReminderSettings,
                        onUpdateLessonTime = viewModel::updateLessonTime,
                        onAddLessonTime = viewModel::addLessonTime,
                        onDeleteLessonTime = viewModel::deleteLessonTime,
                        onImportSchedule = {
                            importScheduleLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                        },
                        onExportJson = {
                            exportJsonLauncher.launch("湘约一课课程表.json")
                        },
                        onExportImage = {
                            shareScheduleImage(
                                context = context,
                                schedule = uiState.schedule,
                                lessonTimes = uiState.lessonTimes
                            ).takeIf(::isErrorMessage)
                                ?.let { message -> errorDialogMessage = message }
                        },
                        onErrorMessage = { errorDialogMessage = it },
                        onRefreshPermission = viewModel::refreshExactAlarmPermission
                    )
                }

                Spacer(Modifier.height(4.dp))
            }
            AppBottomBar(selected = selectedPage, onSelect = { selectedPage = it })
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
    onSelectDay: (Int) -> Unit,
    onEditCourse: (CourseItem) -> Unit,
    onAddCourse: () -> Unit
) {
    val weekdays = remember { buildWeekdays() }
    val filteredCourses = schedule.items.filter { it.dayOfWeek.value == selectedDay }.sortedBy { it.period }

    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(
            text = "课程表",
            style = MaterialTheme.typography.titleLarge,
            color = Color(0xFF202023),
            fontWeight = FontWeight.Black
        )
        WeekdayStrip(weekdays = weekdays, selectedDay = selectedDay, onSelectDay = onSelectDay)
        CourseStatsRow(count = filteredCourses.size)
        if (filteredCourses.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                filteredCourses.forEachIndexed { index, course ->
                    CourseTimelineItem(
                        course = course,
                        lessonTimes = lessonTimes,
                        isLast = index == filteredCourses.lastIndex,
                        onClick = { onEditCourse(course) }
                    )
                }
            }
        } else {
            EmptyScheduleCard(onAddCourse = onAddCourse)
        }
    }
}

@Composable
private fun WeekdayStrip(
    weekdays: List<WeekdayUiItem>,
    selectedDay: Int,
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
                val dateColor = when {
                    selected -> Color.White
                    day.isToday -> Color(0xFFE86C8F)
                    else -> Color(0xFF8F878C)
                }
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = if (selected) Color(0xFFE86C8F) else Color.Transparent,
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
                            color = if (selected) Color.White else Color(0xFF8F878C),
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            day.num.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = dateColor,
                            fontWeight = FontWeight.Bold
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
    isLast: Boolean,
    onClick: () -> Unit
) {
    val slot = lessonTimes.firstOrNull { it.period == course.period }
    val timeText = slot?.displayRange().orEmpty()
    val isCompletedToday = course.dayOfWeek == LocalDate.now().dayOfWeek &&
        slot?.endTime?.isBefore(LocalTime.now()) == true
    val accent = if (isCompletedToday) Color(0xFFD7CCC8) else courseAccentColor(course.period)
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
                text = "第${course.period}节",
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
                .clickable { onClick() }
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
private fun EmptyScheduleCard(onAddCourse: () -> Unit) {
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
        Button(
            onClick = onAddCourse,
            shape = RoundedCornerShape(999.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE26786), contentColor = Color.White),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
        ) {
            Icon(Icons.Filled.AddCircle, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("添加课程", fontWeight = FontWeight.Bold)
        }
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
    val day = DayOfWeek.of(selectedDay)
    val grouped = schedule.items.groupBy { it.dayOfWeek }
    val dayItems = grouped[day].orEmpty().sortedWith(compareBy({ it.period }, { it.className }))

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = "课程管理",
            style = MaterialTheme.typography.titleLarge,
            color = Color(0xFF202023),
            fontWeight = FontWeight.Black
        )

        WeekdayStrip(weekdays = weekdays, selectedDay = selectedDay, onSelectDay = onSelectDay)
        CourseStatsRow(count = dayItems.size) {
            CompactAddButton(onClick = onAddCourse)
        }
        DayGroupEditorCard(
            items = dayItems,
            lessonTimes = lessonTimes,
            onEditCourse = onEditCourse,
            onDeleteCourse = onDeleteCourse
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
    val accent = courseAccentColor(item.period)
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
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = Color(0xFFFFFCFA),
        title = {
            Text(
                text = "出错了",
                style = MaterialTheme.typography.titleLarge,
                color = Color(0xFF202023),
                fontWeight = FontWeight.Black
            )
        },
        text = {
            Text(
                text = message,
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
    alarmModeEnabled: Boolean,
    reminderMinutesText: String,
    onAlarmModeEnabledChange: (Boolean) -> Unit,
    onReminderMinutesTextChange: (String) -> Unit,
    onSaveSettings: (Boolean, Int) -> com.example.pinkschedule.viewmodel.ReminderSettingsAction,
    onUpdateLessonTime: (Int, LocalTime, LocalTime) -> Unit,
    onAddLessonTime: () -> Unit,
    onDeleteLessonTime: (Int) -> Unit,
    onImportSchedule: () -> Unit,
    onExportJson: () -> Unit,
    onExportImage: () -> Unit,
    onErrorMessage: (String) -> Unit,
    onRefreshPermission: () -> Unit
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
    var requestFullScreenIntentPermissionTick by remember { mutableStateOf(0) }
    var requestBatteryOptimizationTick by remember { mutableStateOf(0) }
    var requestAutoStartTick by remember { mutableStateOf(0) }
    var lastSavedAlarmModeEnabled by remember { mutableStateOf(alarmModeEnabled) }
    var lastSavedReminderMinutesText by remember { mutableStateOf(reminderMinutesText) }
    var activeSettingsPanel by rememberSaveable { mutableStateOf<String?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = activeSettingsPanel != null) {
        activeSettingsPanel = null
    }

    LaunchedEffect(requestPermissionTick) {
        if (requestPermissionTick > 0) {
            openExactAlarmPermission(context)
        }
    }

    LaunchedEffect(requestNotificationPermissionTick) {
        if (requestNotificationPermissionTick > 0) {
            requestNotificationPermission(notificationPermissionLauncher)
        }
    }

    LaunchedEffect(requestFullScreenIntentPermissionTick) {
        if (requestFullScreenIntentPermissionTick > 0) {
            openFullScreenIntentPermission(context)
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

    LaunchedEffect(alarmModeEnabled) {
        if (alarmModeEnabled == lastSavedAlarmModeEnabled) return@LaunchedEffect
        val minutes = reminderMinutesText.toIntOrNull()
        if (minutes == null) {
            onErrorMessage("请输入有效的分钟数。")
            return@LaunchedEffect
        }
        val action = onSaveSettings(alarmModeEnabled, minutes)
        action.message?.takeIf(::isErrorMessage)?.let(onErrorMessage)
        if (action.requestExactAlarmPermission) {
            onAlarmModeEnabledChange(false)
            requestPermissionTick += 1
        } else if (action.requestNotificationPermission) {
            onAlarmModeEnabledChange(false)
            requestNotificationPermissionTick += 1
        } else if (action.requestFullScreenIntentPermission) {
            onAlarmModeEnabledChange(false)
            requestFullScreenIntentPermissionTick += 1
        } else {
            lastSavedAlarmModeEnabled = alarmModeEnabled
            lastSavedReminderMinutesText = reminderMinutesText
            if (action.requestBatteryOptimization) {
                requestBatteryOptimizationTick += 1
            }
            if (action.requestAutoStart) {
                requestAutoStartTick += 1
            }
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
            val action = onSaveSettings(alarmModeEnabled, minutes)
            action.message?.takeIf(::isErrorMessage)?.let(onErrorMessage)
            if (action.requestExactAlarmPermission) {
                onAlarmModeEnabledChange(false)
                requestPermissionTick += 1
            } else if (action.requestNotificationPermission) {
                onAlarmModeEnabledChange(false)
                requestNotificationPermissionTick += 1
            } else if (action.requestFullScreenIntentPermission) {
                onAlarmModeEnabledChange(false)
                requestFullScreenIntentPermissionTick += 1
            } else {
                lastSavedAlarmModeEnabled = alarmModeEnabled
                lastSavedReminderMinutesText = reminderMinutesText
                if (action.requestBatteryOptimization) {
                    requestBatteryOptimizationTick += 1
                }
                if (action.requestAutoStart) {
                    requestAutoStartTick += 1
                }
            }
        }
    }

    DisposableEffect(lifecycleOwner, onRefreshPermission) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                onRefreshPermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.fillMaxWidth()) {
        SettingsTitle(
            title = when (activeSettingsPanel) {
                "alarm" -> "闹钟设置"
                "time" -> "课程时间设置"
                else -> "设置"
            },
            showBack = activeSettingsPanel != null,
            onBack = { activeSettingsPanel = null }
        )

        when (activeSettingsPanel) {
            null -> {
                SettingsListGroup {
                    SettingsListRow(
                        icon = Icons.Filled.NotificationsActive,
                        title = "闹钟设置",
                        onClick = { activeSettingsPanel = "alarm" }
                    )
                    SettingsListRow(
                        icon = Icons.Filled.AccessTime,
                        title = "课程时间设置",
                        onClick = { activeSettingsPanel = "time" }
                    )
                    SettingsListRow(
                        icon = Icons.Filled.FileUpload,
                        title = "导入课程表",
                        onClick = onImportSchedule
                    )
                    SettingsListRow(
                        icon = Icons.Filled.FileDownload,
                        title = "导出课程表",
                        onClick = { showExportDialog = true }
                    )
                }
            }
            "alarm" -> {
                SettingsListGroup {
                    SettingsListRow(
                        icon = Icons.Filled.NotificationsActive,
                        title = "课程闹钟",
                        trailing = {
                            Switch(
                                checked = alarmModeEnabled,
                                onCheckedChange = onAlarmModeEnabledChange,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = Color(0xFFE26786),
                                    checkedBorderColor = Color(0xFFE26786),
                                    uncheckedThumbColor = Color.White,
                                    uncheckedTrackColor = Color(0xFFF1DBD3),
                                    uncheckedBorderColor = Color(0xFFF1DBD3)
                                )
                            )
                        }
                    )
                    SettingsListRow(
                        icon = Icons.Filled.Schedule,
                        title = "提前提醒",
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ReminderMinuteField(
                                    value = reminderMinutesText,
                                    onValueChange = { onReminderMinutesTextChange(it.filter(Char::isDigit)) },
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("分钟", color = Color(0xFF8F878C), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    )
                }
            }
            "time" -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${lessonTimes.size} 节课",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF202023),
                        fontWeight = FontWeight.Black
                    )
                    CompactAddButton(onClick = onAddLessonTime)
                }
                SettingsListGroup {
                    lessonTimes.sortedBy { it.period }.forEach { slot ->
                        LessonTimeSlotRow(
                            slot = slot,
                            lessonCount = lessonTimes.size,
                            onUpdateLessonTime = onUpdateLessonTime,
                            onDeleteLessonTime = onDeleteLessonTime,
                            context = context
                        )
                    }
                }
            }
        }
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            shape = RoundedCornerShape(24.dp),
            containerColor = Color(0xFFFFFCFA),
            title = {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "导出课程表",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color(0xFF202023),
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.align(Alignment.CenterStart)
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(32.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                showExportDialog = false
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        ThinCloseIcon(modifier = Modifier.size(18.dp), color = Color(0xFF8F878C))
                    }
                }
            },
            text = { Text("选择导出为 JSON 数据或课程表图片。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExportDialog = false
                        onExportImage()
                    }
                ) {
                    Text("图片", color = Color(0xFFE26786))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showExportDialog = false
                        onExportJson()
                    }
                ) {
                    Text("JSON", color = Color(0xFFE26786))
                }
            }
        )
    }
}

@Composable
private fun ReminderMinuteField(
    value: String,
    onValueChange: (String) -> Unit
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
    lessonCount: Int,
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
            text = "第${slot.period}节课",
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
            if (slot.period > ScheduleDefaults.MIN_LESSON_COUNT && lessonCount > ScheduleDefaults.MIN_LESSON_COUNT) {
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
    icon: ImageVector,
    title: String,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick == null) {
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
            .padding(vertical = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color(0xFFE26786),
            modifier = Modifier.size(30.dp)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFF303036),
            fontWeight = FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
        if (trailing == null) {
            ThinChevronRightIcon(modifier = Modifier.size(18.dp), color = Color(0xFFB8B1B5))
        } else {
            trailing()
        }
    }
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
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = Color(0xFFE26786))
            }
        },
        title = { Text(if (course == null) "新增课程" else "编辑课程") },
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

private fun openExactAlarmPermission(context: Context) {
    com.example.pinkschedule.reminder.SystemAlarmScheduler.openExactAlarmPermissionSettings(context)
}

private fun openFullScreenIntentPermission(context: Context) {
    com.example.pinkschedule.reminder.SystemAlarmScheduler.openFullScreenIntentPermissionSettings(context)
}

private fun requestNotificationPermission(
    launcher: androidx.activity.result.ActivityResultLauncher<String>
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
    val keywords = listOf("失败", "错误", "无法", "不能", "未授予", "请输入", "不能为空", "不正确", "为空", "必须")
    return keywords.any { message.contains(it) }
}

private fun importScheduleFromUri(
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

private fun exportScheduleJsonToUri(
    context: Context,
    uri: Uri,
    json: String
): String {
    return runCatching {
        context.contentResolver.openOutputStream(uri)?.use { output ->
            OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
                writer.write(json)
            }
        } ?: error("无法写入文件")
        "JSON 课程表已导出。"
    }.getOrElse {
        "导出失败：${it.message ?: "无法写入文件"}"
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
private fun slotLabel(period: Int): String = "第${period}节"
private fun courseAccentColor(period: Int): Color {
    return when ((period - 1).mod(4)) {
        0 -> Color(0xFFE26786)
        1 -> Color(0xFFF7B36A)
        2 -> Color(0xFF59B995)
        else -> Color(0xFFC7A4FF)
    }
}
private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
