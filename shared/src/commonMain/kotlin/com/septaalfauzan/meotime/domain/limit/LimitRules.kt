package com.septaalfauzan.meotime.domain.limit

/**
 * The range a limit value is stored, edited and displayed in: `1..1440` minutes.
 *
 * 1 minute is the floor because a limit of 0 would mean "warn immediately", which the limit
 * checker already reaches by *usage*, not by a zero budget
 * ([com.septaalfauzan.meotime.monitoring.LimitChecker] compares `usage >= limit`); a stored 0 is
 * therefore indistinguishable from 1 and would make the stepper's floor a lie. 1440 is the
 * ceiling because a budget larger than a day is not a budget.
 */
internal const val MIN_LIMIT_MINUTES = 1
internal const val MAX_LIMIT_MINUTES = 1440

/** What a new app starts on. Half an hour: long enough to be useful, short enough to notice. */
internal const val DEFAULT_LIMIT_MINUTES = 30

/**
 * Clamps a requested limit into the range the state can hold.
 *
 * The editor, the prefill and the formatter all go through this one function, so "1440" means
 * the same thing as a stored value, a rendered label and a stepper ceiling. A stored limit is
 * never zero: [MIN_LIMIT_MINUTES] is the floor.
 */
internal fun clampLimitMinutes(value: Int): Int = value.coerceIn(MIN_LIMIT_MINUTES, MAX_LIMIT_MINUTES)
