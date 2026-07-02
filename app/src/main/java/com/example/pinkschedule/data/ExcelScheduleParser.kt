package com.example.pinkschedule.data

import android.content.Context
import com.example.pinkschedule.model.CourseItem
import com.example.pinkschedule.model.WeeklySchedule
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.io.File
import java.io.FileInputStream
import java.time.DayOfWeek

class ExcelScheduleParser(
    private val context: Context
) {
    fun loadDefaultTeacherSchedule(): WeeklySchedule {
        val teacher = "吴林湘"
        val file = locateWorkbook()
        if (file == null) {
            return sampleSchedule(teacher)
        }
        FileInputStream(file).use { input ->
            val workbook = WorkbookFactory.create(input)
            workbook.use {
                val items = buildList {
                    workbook.getSheet("课表")?.let { sheet ->
                        addAll(parseMainSheet(sheet, teacher))
                    }
                    workbook.getSheet("辅导表")?.let { sheet ->
                        addAll(parseTutorSheet(sheet, teacher))
                    }
                }.sortedWith(compareBy({ it.dayOfWeek.value }, { it.period }, { it.className }))
                return WeeklySchedule(teacher = teacher, items = items.ifEmpty { sampleSchedule(teacher).items })
            }
        }
    }

    private fun locateWorkbook(): File? {
        val internal = File(context.filesDir, WORKBOOK_NAME)
        if (internal.exists()) return internal

        val external = File(context.getExternalFilesDir(null), WORKBOOK_NAME)
        if (external.exists()) return external

        return null
    }

    private fun parseMainSheet(sheet: org.apache.poi.ss.usermodel.Sheet, teacher: String): List<CourseItem> {
        val dayHeaders = mapOf(
            "星期一" to DayOfWeek.MONDAY,
            "星期二" to DayOfWeek.TUESDAY,
            "星期三" to DayOfWeek.WEDNESDAY,
            "星期四" to DayOfWeek.THURSDAY,
            "星期五" to DayOfWeek.FRIDAY,
            "星期六" to DayOfWeek.SATURDAY,
            "星期日" to DayOfWeek.SUNDAY
        )

        val headerRow = sheet.getRow(1) ?: return emptyList()
        val periodRow = sheet.getRow(3) ?: return emptyList()
        val lastColumn = sheet.getRow(4)?.lastCellNum?.toInt()?.minus(1) ?: 0
        val classes = mutableListOf<CourseItem>()
        for (r in 4..sheet.lastRowNum) {
            val row = sheet.getRow(r) ?: continue
            val className = row.getCell(0)?.toStringCell().orEmpty().trim()
            if (className.isBlank()) continue
            for (c in 1..lastColumn) {
                val cell = row.getCell(c) ?: continue
                val cellTeacher = cell.toStringCell().trim()
                if (cellTeacher != teacher) continue
                val dayHeader = headerAt(headerRow, c)
                val periodText = periodRow.getCell(c)?.toStringCell().orEmpty().trim()
                val day = dayHeaders[dayHeader] ?: continue
                val period = periodText.toIntOrNull() ?: continue
                classes += CourseItem(
                    teacher = teacher,
                    className = className,
                    dayOfWeek = day,
                    period = period
                )
            }
        }
        return classes
    }

    private fun parseTutorSheet(sheet: org.apache.poi.ss.usermodel.Sheet, teacher: String): List<CourseItem> {
        val dayHeaders = mapOf(
            "星期一" to DayOfWeek.MONDAY,
            "星期二" to DayOfWeek.TUESDAY,
            "星期三" to DayOfWeek.WEDNESDAY,
            "星期四" to DayOfWeek.THURSDAY,
            "星期五" to DayOfWeek.FRIDAY,
            "星期六" to DayOfWeek.SATURDAY,
            "星期日" to DayOfWeek.SUNDAY
        )

        val headerRow = sheet.getRow(1) ?: return emptyList()
        val periodRow = sheet.getRow(2) ?: return emptyList()
        val lastColumn = sheet.getRow(3)?.lastCellNum?.toInt()?.minus(1) ?: 0
        val classes = mutableListOf<CourseItem>()
        for (r in 3..sheet.lastRowNum) {
            val row = sheet.getRow(r) ?: continue
            val className = row.getCell(0)?.toStringCell().orEmpty().trim()
            if (className.isBlank()) continue
            for (c in 2..lastColumn) {
                val cellTeacher = row.getCell(c)?.toStringCell().orEmpty().trim()
                if (cellTeacher != teacher) continue
                val dayHeader = headerAt(headerRow, c)
                val periodText = periodRow.getCell(c)?.toStringCell().orEmpty().trim()
                val day = dayHeaders[dayHeader] ?: continue
                val period = periodText.toIntOrNull() ?: continue
                classes += CourseItem(
                    teacher = teacher,
                    className = className,
                    dayOfWeek = day,
                    period = period
                )
            }
        }
        return classes
    }

    private fun headerAt(row: org.apache.poi.ss.usermodel.Row, columnIndex: Int): String {
        var c = columnIndex
        while (c >= 0) {
            val text = row.getCell(c)?.toStringCell().orEmpty().trim()
            if (text.startsWith("星期")) return text
            c--
        }
        return ""
    }

    private fun org.apache.poi.ss.usermodel.Cell.toStringCell(): String = when (cellType) {
        org.apache.poi.ss.usermodel.CellType.STRING -> stringCellValue
        org.apache.poi.ss.usermodel.CellType.NUMERIC -> numericCellValue.toInt().toString()
        org.apache.poi.ss.usermodel.CellType.BOOLEAN -> booleanCellValue.toString()
        org.apache.poi.ss.usermodel.CellType.FORMULA -> when (cachedFormulaResultType) {
            org.apache.poi.ss.usermodel.CellType.STRING -> stringCellValue
            org.apache.poi.ss.usermodel.CellType.NUMERIC -> numericCellValue.toInt().toString()
            else -> ""
        }
        else -> ""
    }

    companion object {
        const val WORKBOOK_NAME = "teacher_schedule.xlsx"
    }

    private fun sampleSchedule(teacher: String): WeeklySchedule {
        return WeeklySchedule(
            teacher = teacher,
            items = listOf(
                CourseItem(teacher, "2403", DayOfWeek.TUESDAY, 2),
                CourseItem(teacher, "2403", DayOfWeek.TUESDAY, 3),
                CourseItem(teacher, "2403", DayOfWeek.WEDNESDAY, 5),
                CourseItem(teacher, "2403", DayOfWeek.WEDNESDAY, 6),
                CourseItem(teacher, "2403", DayOfWeek.THURSDAY, 5),
                CourseItem(teacher, "2403", DayOfWeek.FRIDAY, 4),
                CourseItem(teacher, "2410", DayOfWeek.MONDAY, 2),
                CourseItem(teacher, "2410", DayOfWeek.TUESDAY, 4),
                CourseItem(teacher, "2410", DayOfWeek.WEDNESDAY, 7),
                CourseItem(teacher, "2410", DayOfWeek.THURSDAY, 1),
                CourseItem(teacher, "2410", DayOfWeek.THURSDAY, 2),
                CourseItem(teacher, "2410", DayOfWeek.FRIDAY, 1),
                CourseItem(teacher, "2403", DayOfWeek.WEDNESDAY, 2),
                CourseItem(teacher, "2403", DayOfWeek.SUNDAY, 2),
                CourseItem(teacher, "2410", DayOfWeek.WEDNESDAY, 1),
                CourseItem(teacher, "2410", DayOfWeek.SUNDAY, 1)
            )
        )
    }
}
