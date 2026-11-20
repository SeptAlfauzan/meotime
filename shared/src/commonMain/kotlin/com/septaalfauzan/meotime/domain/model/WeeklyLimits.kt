package com.septaalfauzan.meotime.domain.model

import com.septaalfauzan.meotime.domain.limit.clampLimitMinutes
import kotlinx.datetime.DayOfWeek

/**
 * Which minutes a user allows per weekday, as the dashboard and the editor read them.
 *
 * A `null` weekday means the day is not limited. A schedule with every weekday `null` —
 * [disabledAll] — is a real "no limit anywhere" schedule, not the absence of a weekly
 * schedule: [com.septaalfauzan.meotime.domain.limit.effectiveLimitMinutes] keeps it that way
 * and never falls back to the daily limit for it.
 */
data class WeeklyLimits(
    val monday: Int? = null,
    val tuesday: Int? = null,
    val wednesday: Int? = null,
    val thursday: Int? = null,
    val friday: Int? = null,
    val saturday: Int? = null,
    val sunday: Int? = null,
) {
    fun forDay(day: DayOfWeek): Int? = when (day) {
        DayOfWeek.MONDAY -> monday
        DayOfWeek.TUESDAY -> tuesday
        DayOfWeek.WEDNESDAY -> wednesday
        DayOfWeek.THURSDAY -> thursday
        DayOfWeek.FRIDAY -> friday
        DayOfWeek.SATURDAY -> saturday
        DayOfWeek.SUNDAY -> sunday
    }

    /**
     * A schedule with [minutes] on [day], or with that day cleared when [minutes] is `null`.
     *
     * Values are routed through [clampLimitMinutes], so a stored limit is always within
     * [com.septaalfauzan.meotime.domain.limit.MIN_LIMIT_MINUTES]..[com.septaalfauzan.meotime.domain.limit.MAX_LIMIT_MINUTES]
     * and never zero.
     */
    fun withDay(day: DayOfWeek, minutes: Int?): WeeklyLimits = when (day) {
        DayOfWeek.MONDAY -> copy(monday = minutes?.let(::clampLimitMinutes))
        DayOfWeek.TUESDAY -> copy(tuesday = minutes?.let(::clampLimitMinutes))
        DayOfWeek.WEDNESDAY -> copy(wednesday = minutes?.let(::clampLimitMinutes))
        DayOfWeek.THURSDAY -> copy(thursday = minutes?.let(::clampLimitMinutes))
        DayOfWeek.FRIDAY -> copy(friday = minutes?.let(::clampLimitMinutes))
        DayOfWeek.SATURDAY -> copy(saturday = minutes?.let(::clampLimitMinutes))
        DayOfWeek.SUNDAY -> copy(sunday = minutes?.let(::clampLimitMinutes))
    }

    /** A schedule where every weekday carries [minutes], clamped to the legal range. */
    fun withAll(minutes: Int): WeeklyLimits = copy(
        monday = clampLimitMinutes(minutes),
        tuesday = clampLimitMinutes(minutes),
        wednesday = clampLimitMinutes(minutes),
        thursday = clampLimitMinutes(minutes),
        friday = clampLimitMinutes(minutes),
        saturday = clampLimitMinutes(minutes),
        sunday = clampLimitMinutes(minutes),
    )

    /** A schedule where every weekday is `null` — no day is limited. */
    fun disabledAll(): WeeklyLimits = WeeklyLimits()

    /** How many weekdays carry a limit. */
    fun limitedDays(): Int = listOfNotNull(monday, tuesday, wednesday, thursday, friday, saturday, sunday).size

    /** The sum of the limited weekdays' limits, in minutes. */
    fun weeklyTotalMinutes(): Long =
        listOfNotNull(monday, tuesday, wednesday, thursday, friday, saturday, sunday).sumOf { it.toLong() }

    companion object {
        fun DayOfWeek.displayName(): String = when (this) {
            DayOfWeek.MONDAY -> "Monday"
            DayOfWeek.TUESDAY -> "Tuesday"
            DayOfWeek.WEDNESDAY -> "Wednesday"
            DayOfWeek.THURSDAY -> "Thursday"
            DayOfWeek.FRIDAY -> "Friday"
            DayOfWeek.SATURDAY -> "Saturday"
            DayOfWeek.SUNDAY -> "Sunday"
        }
    }
}
