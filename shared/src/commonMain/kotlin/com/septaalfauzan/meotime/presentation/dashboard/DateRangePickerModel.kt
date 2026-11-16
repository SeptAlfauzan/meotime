package com.septaalfauzan.meotime.presentation.dashboard

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

enum class PickerMode {
    SINGLE_DAY,
    DATE_RANGE,
}

enum class PickerPreset {
    TODAY,
    YESTERDAY,
    LAST_7_DAYS,
}

data class DatePickerState(
    val mode: PickerMode,
    val start: LocalDate,
    val end: LocalDate,
    val anchor: LocalDate?,
    val visibleMonth: LocalDate,
)

fun initialDatePickerState(selection: StatsFilter, today: LocalDate): DatePickerState =
    when (selection) {
        StatsFilter.Today -> DatePickerState(
            mode = PickerMode.SINGLE_DAY,
            start = today,
            end = today,
            anchor = null,
            visibleMonth = firstOfMonth(today),
        )
        is StatsFilter.SingleDay -> DatePickerState(
            mode = PickerMode.SINGLE_DAY,
            start = selection.date,
            end = selection.date,
            anchor = null,
            visibleMonth = firstOfMonth(selection.date),
        )
        is StatsFilter.DateRange -> DatePickerState(
            mode = PickerMode.DATE_RANGE,
            start = minOf(selection.start, selection.end),
            end = maxOf(selection.start, selection.end),
            anchor = null,
            visibleMonth = firstOfMonth(selection.end),
        )
    }

fun DatePickerState.selectMode(mode: PickerMode, today: LocalDate): DatePickerState = when (mode) {
    PickerMode.SINGLE_DAY -> copy(
        mode = mode,
        start = start,
        end = start,
        anchor = null,
        visibleMonth = firstOfMonth(start),
    )
    PickerMode.DATE_RANGE -> copy(
        mode = mode,
        anchor = null,
        visibleMonth = firstOfMonth(end),
    )
}

fun DatePickerState.tapDate(date: LocalDate, today: LocalDate): DatePickerState {
    if (!isSelectable(date, today)) return this
    return when (mode) {
        PickerMode.SINGLE_DAY -> copy(
            start = date,
            end = date,
            anchor = null,
            visibleMonth = firstOfMonth(date),
        )
        PickerMode.DATE_RANGE -> {
            val anchorDate = anchor
            if (anchorDate == null) {
                copy(
                    start = date,
                    end = date,
                    anchor = date,
                    visibleMonth = firstOfMonth(date),
                )
            } else {
                copy(
                    start = minOf(anchorDate, date),
                    end = maxOf(anchorDate, date),
                    anchor = null,
                    visibleMonth = firstOfMonth(date),
                )
            }
        }
    }
}

fun DatePickerState.selectPreset(preset: PickerPreset, today: LocalDate): DatePickerState = when (preset) {
    PickerPreset.TODAY -> DatePickerState(
        mode = PickerMode.SINGLE_DAY,
        start = today,
        end = today,
        anchor = null,
        visibleMonth = firstOfMonth(today),
    )
    PickerPreset.YESTERDAY -> {
        val yesterday = today.plus(-1, DateTimeUnit.DAY)
        DatePickerState(
            mode = PickerMode.SINGLE_DAY,
            start = yesterday,
            end = yesterday,
            anchor = null,
            visibleMonth = firstOfMonth(yesterday),
        )
    }
    PickerPreset.LAST_7_DAYS -> DatePickerState(
        mode = PickerMode.DATE_RANGE,
        start = today.plus(-6, DateTimeUnit.DAY),
        end = today,
        anchor = null,
        visibleMonth = firstOfMonth(today),
    )
}

fun DatePickerState.previousMonth(today: LocalDate): DatePickerState =
    copy(visibleMonth = visibleMonth.plus(-1, DateTimeUnit.MONTH))

fun DatePickerState.nextMonth(today: LocalDate): DatePickerState {
    val next = visibleMonth.plus(1, DateTimeUnit.MONTH)
    return if (next > firstOfMonth(today)) this else copy(visibleMonth = next)
}

fun DatePickerState.toFilter(today: LocalDate): StatsFilter =
    if (mode == PickerMode.SINGLE_DAY) {
        if (start == today) StatsFilter.Today else StatsFilter.SingleDay(start)
    } else {
        StatsFilter.DateRange(start, end)
    }

fun DatePickerState.isSelectable(date: LocalDate, today: LocalDate): Boolean = date <= today

fun monthGrid(firstOfMonth: LocalDate): List<LocalDate?> {
    val leading = (firstOfMonth.dayOfWeek.ordinal + 1) % 7
    val nextMonth = firstOfMonth.plus(1, DateTimeUnit.MONTH)
    val daysInMonth = nextMonth.plus(-1, DateTimeUnit.DAY).day
    val cells = mutableListOf<LocalDate?>()
    repeat(leading) { cells += null }
    for (day in 1..daysInMonth) {
        cells += LocalDate(firstOfMonth.year, firstOfMonth.month, day)
    }
    while (cells.size % 7 != 0) cells += null
    return cells
}

fun formatPickerMonth(firstOfMonth: LocalDate): String {
    val months = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )
    return "${months[firstOfMonth.month.ordinal]} ${firstOfMonth.year}"
}

private fun firstOfMonth(date: LocalDate): LocalDate = LocalDate(date.year, date.month, 1)
