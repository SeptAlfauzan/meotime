package com.septaalfauzan.wanci.monitoring

object LimitChecker {
    fun exceeds(usageMinutes: Long, limitMinutes: Int): Boolean =
        usageMinutes >= limitMinutes
}
