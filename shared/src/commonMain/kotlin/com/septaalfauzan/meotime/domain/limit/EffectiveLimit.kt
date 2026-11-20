package com.septaalfauzan.meotime.domain.limit

import com.septaalfauzan.meotime.domain.model.MonitoredApp
import kotlinx.datetime.LocalDate

/**
 * The limit that applies on [date] for [app]: the weekly value for [date]'s weekday when
 * [MonitoredApp.usesWeeklyLimits] is on, otherwise the daily limit.
 *
 * A `null` result means there is no limit today. When `usesWeeklyLimits` is on, an all-`null`
 * [MonitoredApp.weeklyLimits] is a legitimate schedule meaning "no limit on any day" — it is
 * not a fallback to the daily limit.
 */
fun effectiveLimitMinutes(app: MonitoredApp, date: LocalDate): Int? =
    if (app.usesWeeklyLimits) app.weeklyLimits.forDay(date.dayOfWeek) else app.dailyLimitMinutes
