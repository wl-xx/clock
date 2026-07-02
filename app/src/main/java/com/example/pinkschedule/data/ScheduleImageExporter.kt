package com.example.pinkschedule.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.example.pinkschedule.model.LessonTimeSlot
import com.example.pinkschedule.model.ScheduleDefaults
import com.example.pinkschedule.model.WeeklySchedule
import java.io.File
import java.io.FileOutputStream
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object ScheduleImageExporter {
    private val fileTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    fun exportPngFile(
        context: Context,
        schedule: WeeklySchedule,
        lessonTimes: List<LessonTimeSlot>
    ): File {
        val bitmap = createBitmap(schedule, lessonTimes)
        val directory = File(context.cacheDir, "schedule_exports").apply { mkdirs() }
        val file = File(directory, "schedule_${LocalDateTime.now().format(fileTimeFormatter)}.png")
        FileOutputStream(file).use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        bitmap.recycle()
        return file
    }

    private fun createBitmap(schedule: WeeklySchedule, lessonTimes: List<LessonTimeSlot>): Bitmap {
        val periods = lessonTimes.sortedBy { it.period }
        val leftWidth = 170
        val topHeight = 86
        val columnWidth = 220
        val rowHeight = 132
        val padding = 42
        val width = padding * 2 + leftWidth + columnWidth * 7
        val height = padding * 2 + topHeight + rowHeight * periods.size
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 243, 247)
            style = Paint.Style.FILL
        }
        val surface = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(242, 220, 214)
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        val headerPaint = textPaint(28f, Color.rgb(226, 103, 134), Typeface.BOLD)
        val periodPaint = textPaint(24f, Color.rgb(92, 78, 76), Typeface.BOLD)
        val timePaint = textPaint(20f, Color.rgb(143, 135, 140), Typeface.NORMAL)
        val coursePaint = textPaint(24f, Color.rgb(75, 47, 44), Typeface.BOLD)
        val classPaint = textPaint(20f, Color.rgb(112, 94, 91), Typeface.NORMAL)

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)
        val table = RectF(
            padding.toFloat(),
            padding.toFloat(),
            (width - padding).toFloat(),
            (height - padding).toFloat()
        )
        canvas.drawRoundRect(table, 30f, 30f, surface)

        val tableLeft = padding
        val tableTop = padding + topHeight
        val dayHeaderTop = padding + 22
        DayOfWeek.entries.forEachIndexed { index, day ->
            val left = tableLeft + leftWidth + index * columnWidth
            drawCenteredText(
                canvas = canvas,
                text = dayShortLabel(day),
                paint = headerPaint,
                centerX = left + columnWidth / 2f,
                centerY = dayHeaderTop + 28f
            )
        }

        val coursesByCell = schedule.items.associateBy { it.dayOfWeek to it.period }
        periods.forEachIndexed { rowIndex, slot ->
            val top = tableTop + rowIndex * rowHeight
            val periodCenterX = tableLeft + leftWidth / 2f
            drawCenteredText(canvas, ScheduleDefaults.periodLabel(slot.period), periodPaint, periodCenterX, top + 45f)
            drawCenteredText(canvas, slot.displayRange(), timePaint, periodCenterX, top + 78f)

            DayOfWeek.entries.forEachIndexed { colIndex, day ->
                val course = coursesByCell[day to slot.period]
                if (course != null) {
                    val left = tableLeft + leftWidth + colIndex * columnWidth
                    val centerX = left + columnWidth / 2f
                    drawCenteredText(
                        canvas = canvas,
                        text = fitText(course.courseName.ifBlank { "课程" }, coursePaint, columnWidth - 30f),
                        paint = coursePaint,
                        centerX = centerX,
                        centerY = top + 48f
                    )
                    drawCenteredText(
                        canvas = canvas,
                        text = fitText(course.className.ifBlank { "未填写班级" }, classPaint, columnWidth - 30f),
                        paint = classPaint,
                        centerX = centerX,
                        centerY = top + 82f
                    )
                }
            }
        }

        val startX = tableLeft.toFloat()
        val endX = (tableLeft + leftWidth + columnWidth * 7).toFloat()
        val startY = padding.toFloat()
        val endY = (tableTop + rowHeight * periods.size).toFloat()
        canvas.drawLine(startX, tableTop.toFloat(), endX, tableTop.toFloat(), grid)
        for (index in 0..7) {
            val x = (tableLeft + leftWidth + index * columnWidth).toFloat()
            canvas.drawLine(x, startY, x, endY, grid)
        }
        periods.indices.forEach { row ->
            val y = (tableTop + row * rowHeight).toFloat()
            canvas.drawLine(startX, y, endX, y, grid)
        }
        canvas.drawLine(startX, endY, endX, endY, grid)
        canvas.drawLine(startX, startY, endX, startY, grid)
        canvas.drawLine(startX, startY, startX, endY, grid)
        canvas.drawLine(endX, startY, endX, endY, grid)
        return bitmap
    }

    private fun textPaint(size: Float, textColor: Int, weight: Int): Paint {
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = size
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, weight)
        }
    }

    private fun drawCenteredText(
        canvas: Canvas,
        text: String,
        paint: Paint,
        centerX: Float,
        centerY: Float
    ) {
        val metrics = paint.fontMetrics
        val baseline = centerY - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(text, centerX, baseline, paint)
    }

    private fun fitText(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var current = text
        while (current.length > 1 && paint.measureText("$current...") > maxWidth) {
            current = current.dropLast(1)
        }
        return "$current..."
    }

    private fun dayShortLabel(day: DayOfWeek): String {
        return when (day) {
            DayOfWeek.MONDAY -> "周一"
            DayOfWeek.TUESDAY -> "周二"
            DayOfWeek.WEDNESDAY -> "周三"
            DayOfWeek.THURSDAY -> "周四"
            DayOfWeek.FRIDAY -> "周五"
            DayOfWeek.SATURDAY -> "周六"
            DayOfWeek.SUNDAY -> "周日"
        }
    }
}
