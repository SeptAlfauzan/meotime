package com.septaalfauzan.wanci

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform