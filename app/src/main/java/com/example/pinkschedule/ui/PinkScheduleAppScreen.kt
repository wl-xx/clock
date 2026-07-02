package com.example.pinkschedule.ui

import android.Manifest
import android.app.TimePickerDialog
import android.content.Context
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.pinkschedule.model.CourseItem
import com.example.pinkschedule.model.LessonTimeSlot
import com.example.pinkschedule.model.ScheduleDefaults
import com.example.pinkschedule.model.WeeklySchedule
import com.example.pinkschedule.reminder.SystemAlarmScheduler
import com.example.pinkschedule.viewmodel.ScheduleViewModel
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private enum class AppPage(
    val title: String,
    val icon: ImageVector
) {
    SCHEDULE("课程表", Icons.Filled.DateRange),
    COURSES("课程管理", Icons.Filled.Edit),
    SETTINGS("设置", Icons.Filled.Settings)
}

@Composable
fun PinkScheduleAppScreen(viewModel: ScheduleViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()
    var selectedPage by rememberSaveable { mutableStateOf(AppPage.SCHEDULE) }
    var editingCourse by remember { mutableStateOf<CourseItem?>(null) }
    var addingCourse by remember { mutableStateOf(false) }
    var settingsAlarmModeEnabled by rememberSaveable { mutableStateOf(uiState.reminderSettings.alarmModeEnabled) }
    var settingsReminderMinutesText by rememberSaveable {
        mutableStateOf(uiState.reminderSettings.reminderMinutesBefore.toString())
    }

    LaunchedEffect(uiState.reminderSettings) {
        settingsAlarmModeEnabled = uiState.reminderSettings.alarmModeEnabled
        settingsReminderMinutesText = uiState.reminderSettings.reminderMinutesBefore.toString()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(220.dp)
                .clip(RoundedCornerShape(bottomStart = 220.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f))
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 110.dp)
                .size(150.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f))
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 80.dp, end = 10.dp)
                .size(180.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.13f))
        )
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                HeroCard(title = "今日小课表")
                Spacer(Modifier.height(18.dp))

                when (selectedPage) {
                    AppPage.SCHEDULE -> SchedulePage(
                        schedule = uiState.schedule,
                        lessonTimes = uiState.lessonTimes
                    )
                    AppPage.COURSES -> CourseManagementPage(
                        schedule = uiState.schedule,
                        lessonTimes = uiState.lessonTimes,
                        onAddCourse = { addingCourse = true },
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
                        onRefreshPermission = viewModel::refreshExactAlarmPermission
                    )
                }

                uiState.message?.let { message ->
                    Spacer(Modifier.height(20.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f),
                        shape = RoundedCornerShape(22.dp),
                        tonalElevation = 0.dp
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))
            }

            AppNavigationBar(
                selected = selectedPage,
                onSelect = { selectedPage = it }
            )
        }
    }

    if (addingCourse) {
        CourseEditorDialog(
            teacher = uiState.schedule.teacher,
            lessonTimes = uiState.lessonTimes,
            course = null,
            onDismiss = { addingCourse = false },
            onSave = {
                val error = viewModel.upsertCourse(null, it)
                if (error == null) {
                    addingCourse = false
                }
                error
            }
        )
    }

    editingCourse?.let { course ->
        CourseEditorDialog(
            teacher = uiState.schedule.teacher,
            lessonTimes = uiState.lessonTimes,
            course = course,
            onDismiss = { editingCourse = null },
            onSave = {
                val error = viewModel.upsertCourse(course, it)
                if (error == null) {
                    editingCourse = null
                }
                error
            }
        )
    }
}

@Composable
private fun AppNavigationBar(
    selected: AppPage,
    onSelect: (AppPage) -> Unit
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp
    ) {
        AppPage.entries.forEach { page ->
            NavigationBarItem(
                selected = page == selected,
                onClick = { onSelect(page) },
                icon = {
                    Icon(
                        imageVector = page.icon,
                        contentDescription = page.title
                    )
                },
                label = { Text(page.title) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}

@Composable
private fun BiliPrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFFFB7299),
            contentColor = Color.White
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp)
    ) {
        Text(text = text, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BiliSecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    onClick: () -> Unit
) {
    val container = if (emphasized) Color(0xFFFFE3EC) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
    val content = if (emphasized) Color(0xFFFB7299) else MaterialTheme.colorScheme.onSurface
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp)
    ) {
        Text(text = text, fontWeight = FontWeight.SemiBold)
    }
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
    onRefreshPermission: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        onRefreshPermission()
    }
    var errorText by remember { mutableStateOf<String?>(null) }
    var requestPermissionTick by remember { mutableStateOf(0) }
    var requestNotificationPermissionTick by remember { mutableStateOf(0) }
    var requestFullScreenIntentPermissionTick by remember { mutableStateOf(0) }
    var requestBatteryOptimizationTick by remember { mutableStateOf(0) }
    var requestAutoStartTick by remember { mutableStateOf(0) }
    var lastSavedAlarmModeEnabled by remember { mutableStateOf(alarmModeEnabled) }
    var lastSavedReminderMinutesText by remember { mutableStateOf(reminderMinutesText) }

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
            SystemAlarmScheduler.openBatteryOptimizationSettings(context)
        }
    }

    LaunchedEffect(requestAutoStartTick) {
        if (requestAutoStartTick > 0) {
            SystemAlarmScheduler.openAutoStartSettings(context)
        }
    }

    LaunchedEffect(alarmModeEnabled) {
        if (alarmModeEnabled == lastSavedAlarmModeEnabled) return@LaunchedEffect
        val minutes = reminderMinutesText.toIntOrNull()
        if (minutes == null) {
            errorText = "请输入有效的分钟数。"
            return@LaunchedEffect
        }
        val action = onSaveSettings(alarmModeEnabled, minutes)
        errorText = action.message
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
                errorText = "请输入有效的分钟数。"
                return@LaunchedEffect
            }
            val action = onSaveSettings(alarmModeEnabled, minutes)
            errorText = action.message
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

    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
        // —— 提醒模式 ——
        SettingsSectionCard(title = "提醒模式") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "课程闹钟",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "开启后会像系统闹钟一样，在上课前准时提醒你。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = alarmModeEnabled,
                    onCheckedChange = onAlarmModeEnabledChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        checkedBorderColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                        uncheckedBorderColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }
            OutlinedTextField(
                value = reminderMinutesText,
                onValueChange = { onReminderMinutesTextChange(it.filter(Char::isDigit)) },
                label = { Text("提前提醒分钟数") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("例如 10") },
                supportingText = {
                    Text("上课前多少分钟提醒，修改后自动保存。")
                },
                shape = RoundedCornerShape(22.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                )
            )
        }

        // —— 节次时间（原独立 Tab，现并入设置）——
        SettingsSectionCard(title = "节次时间") {
            Text(
                text = "调整每节课的上课与下课时间，闹钟会按新时间自动重排。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            lessonTimes.sortedBy { it.period }.forEach { slot ->
                LessonTimeEditorRow(
                    slot = slot,
                    onSave = { start, end -> onUpdateLessonTime(slot.period, start, end) }
                )
            }
        }

        errorText?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(30.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            content()
        }
    }
}

private fun openExactAlarmPermission(context: Context) {
    SystemAlarmScheduler.openExactAlarmPermissionSettings(context)
}

private fun openFullScreenIntentPermission(context: Context) {
    SystemAlarmScheduler.openFullScreenIntentPermissionSettings(context)
}

private fun requestNotificationPermission(
    launcher: androidx.activity.result.ActivityResultLauncher<String>
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroCard(title: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(34.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f)
                        )
                    )
                )
        ) {
            Column(Modifier.padding(22.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "SCHEDULE",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "用更清晰的课表和闹钟提醒管理每天的课程节奏。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HeroBadge()
                }
                Spacer(Modifier.height(16.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HeroPill(
                        text = "清晰课表",
                        accentColor = MaterialTheme.colorScheme.secondary
                    )
                    HeroPill(
                        text = "自动保存",
                        accentColor = MaterialTheme.colorScheme.primary
                    )
                    HeroPill(
                        text = "系统闹钟",
                        accentColor = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroBadge() {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.secondary)
            )
            Text(
                text = "闹钟守护中",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun HeroPill(
    text: String,
    accentColor: androidx.compose.ui.graphics.Color
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(accentColor)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun SchedulePage(
    schedule: WeeklySchedule,
    lessonTimes: List<LessonTimeSlot>
) {
    DayScheduleList(items = schedule.items, lessonTimes = lessonTimes)
}

@Composable
private fun CourseManagementPage(
    schedule: WeeklySchedule,
    lessonTimes: List<LessonTimeSlot>,
    onAddCourse: () -> Unit,
    onEditCourse: (CourseItem) -> Unit,
    onDeleteCourse: (CourseItem) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "课程管理",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                BiliPrimaryButton(text = "新增课程", onClick = onAddCourse)
            }
            val grouped = schedule.items.groupBy { it.dayOfWeek }.toSortedMap(compareBy { it.value })
            if (grouped.isEmpty()) {
                Text("当前还没有课程，可直接新增。", style = MaterialTheme.typography.bodyMedium)
            } else {
                grouped.forEach { (day, dayItems) ->
                    DayGroupEditorCard(
                        day = day,
                        items = dayItems.sortedWith(compareBy({ it.period }, { it.className })),
                        lessonTimes = lessonTimes,
                        onEditCourse = onEditCourse,
                        onDeleteCourse = onDeleteCourse
                    )
                }
            }
        }
    }
}

@Composable
private fun DayGroupEditorCard(
    day: DayOfWeek,
    items: List<CourseItem>,
    lessonTimes: List<LessonTimeSlot>,
    onEditCourse: (CourseItem) -> Unit,
    onDeleteCourse: (CourseItem) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(26.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = dayLabel(day),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "${items.size} 节安排",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items.forEachIndexed { index, item ->
                EditableCourseRow(
                    item = item,
                    lessonTimes = lessonTimes,
                    onEdit = { onEditCourse(item) },
                    onDelete = { onDeleteCourse(item) }
                )
                if (index != items.lastIndex) {
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun DayScheduleList(items: List<CourseItem>, lessonTimes: List<LessonTimeSlot>) {
    val grouped = items.groupBy { it.dayOfWeek }.toSortedMap(compareBy { it.value })
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (grouped.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(30.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "当前还没有课程。",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            grouped.forEach { (day, dayItems) ->
                DayCard(
                    day = day,
                    items = dayItems.sortedWith(compareBy({ it.period }, { it.className })),
                    lessonTimes = lessonTimes
                )
            }
        }
    }
}

@Composable
private fun DayCard(day: DayOfWeek, items: List<CourseItem>, lessonTimes: List<LessonTimeSlot>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(30.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = dayLabel(day),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "${items.size} 节课程",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))
            items.forEachIndexed { index, item ->
                CourseRow(
                    item = item,
                    lessonTimes = lessonTimes,
                    isLast = index == items.lastIndex
                )
            }
        }
    }
}

@Composable
private fun CourseRow(item: CourseItem, lessonTimes: List<LessonTimeSlot>, isLast: Boolean) {
    val timeText = lessonTimes.firstOrNull { it.period == item.period }?.displayRange().orEmpty()
    val accent = courseAccentColor(item.period)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = item.displayTimeLabel(),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(accent)
            )
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(if (isLast) 0.dp else 88.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f))
            )
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.22f)),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.weight(1f)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = if (timeText.isNotBlank()) timeText else "时间待定",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = item.className,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CourseMetaChip(label = "班级", value = item.className, accent = accent)
                    if (timeText.isNotBlank()) {
                        CourseMetaChip(label = "时间", value = timeText, accent = accent.copy(alpha = 0.82f))
                    }
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
    Card(
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.18f)),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "${slotLabel(item.period)} · ${item.className}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CourseMetaChip(label = "班级", value = item.className, accent = accent)
                        if (timeText.isNotBlank()) {
                            CourseMetaChip(label = "时间", value = timeText, accent = accent.copy(alpha = 0.82f))
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BiliSecondaryButton(text = "编辑", emphasized = true, onClick = onEdit)
                Spacer(Modifier.width(8.dp))
                BiliSecondaryButton(text = "删除", onClick = onDelete)
            }
        }
    }
}

@Composable
private fun CourseMetaChip(
    label: String,
    value: String,
    accent: Color
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = accent.copy(alpha = 0.18f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private fun courseAccentColor(period: Int): Color {
    return when ((period - 1).mod(4)) {
        0 -> Color(0xFFFFB3C7)
        1 -> Color(0xFFFFD0A8)
        2 -> Color(0xFFBEE7D3)
        else -> Color(0xFFD7C8FF)
    }
}

@Composable
private fun LessonTimeEditorRow(
    slot: LessonTimeSlot,
    onSave: (LocalTime, LocalTime) -> Unit
) {
    val context = LocalContext.current
    var startTime by rememberSaveable(slot.period) { mutableStateOf(slot.startTime) }
    var endTime by rememberSaveable(slot.period) { mutableStateOf(slot.endTime) }

    LaunchedEffect(startTime, endTime) {
        if (startTime != slot.startTime || endTime != slot.endTime) {
            onSave(startTime, endTime)
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = slot.displayLabel(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                TimePickerButton(
                    label = "开始时间",
                    time = startTime,
                    modifier = Modifier.weight(1f),
                    onTimeSelected = { startTime = it },
                    context = context
                )
                TimePickerButton(
                    label = "结束时间",
                    time = endTime,
                    modifier = Modifier.weight(1f),
                    onTimeSelected = { endTime = it },
                    context = context
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "时间：${startTime.format(HH_MM)} - ${endTime.format(HH_MM)}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
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
    BiliSecondaryButton(
        text = "$label：${time.format(HH_MM)}",
        modifier = modifier,
        onClick = {
            TimePickerDialog(
                context,
                { _, hour, minute -> onTimeSelected(LocalTime.of(hour, minute)) },
                time.hour,
                time.minute,
                true
            ).show()
        }
    )
}

@Composable
private fun CourseEditorDialog(
    teacher: String,
    lessonTimes: List<LessonTimeSlot>,
    course: CourseItem?,
    onDismiss: () -> Unit,
    onSave: (CourseItem) -> String?
) {
    var className by rememberSaveable(course) { mutableStateOf(course?.className.orEmpty()) }
    var selectedDay by rememberSaveable(course) { mutableStateOf(course?.dayOfWeek ?: DayOfWeek.MONDAY) }
    var selectedPeriod by rememberSaveable(course) { mutableStateOf(course?.period ?: lessonTimes.minOfOrNull { it.period } ?: 1) }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(30.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.primary,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        confirmButton = {
            Button(
                onClick = {
                    if (className.isBlank()) {
                        errorText = "班级不能为空。"
                    } else {
                        errorText = onSave(
                            CourseItem(
                                teacher = teacher.ifBlank { ScheduleDefaults.DEFAULT_TEACHER },
                                className = className.trim(),
                                dayOfWeek = selectedDay,
                                period = selectedPeriod
                            )
                        )
                    }
                },
                shape = RoundedCornerShape(18.dp)
            ) {
                Text(if (course == null) "新增" else "保存")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text("取消")
            }
        },
        title = { Text(if (course == null) "新增课程" else "编辑课程") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = className,
                    onValueChange = { className = it },
                    label = { Text("班级") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                )
                DayDropdown(selectedDay = selectedDay, onSelect = { selectedDay = it })
                PeriodDropdown(
                    selectedPeriod = selectedPeriod,
                    lessonTimes = lessonTimes,
                    onSelect = { selectedPeriod = it }
                )
                errorText?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    )
}

@Composable
private fun DayDropdown(selectedDay: DayOfWeek, onSelect: (DayOfWeek) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        BiliSecondaryButton(
            text = "星期：${dayLabel(selectedDay)}",
            modifier = Modifier.fillMaxWidth(),
            onClick = { expanded = true }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DayOfWeek.entries.forEach { day ->
                DropdownMenuItem(
                    text = { Text(dayLabel(day)) },
                    onClick = {
                        onSelect(day)
                        expanded = false
                    }
                )
            }
        }
    }
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
        BiliSecondaryButton(
            text = options.firstOrNull { it.period == selectedPeriod }?.let { "节次：${slotLabel(it.period)} ${it.displayRange()}" }
                ?: "节次：${slotLabel(selectedPeriod)}",
            modifier = Modifier.fillMaxWidth(),
            onClick = { expanded = true }
        )
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

private fun dayLabel(day: DayOfWeek): String {
    return day.getDisplayName(TextStyle.FULL, Locale.CHINA)
}

private fun slotLabel(period: Int): String = "第${period}节"

private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
