package com.septaalfauzan.meotime

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform