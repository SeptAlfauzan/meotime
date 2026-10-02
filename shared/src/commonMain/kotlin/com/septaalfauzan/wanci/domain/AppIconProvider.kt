package com.septaalfauzan.wanci.domain

import androidx.compose.ui.graphics.ImageBitmap

fun interface AppIconProvider {
    suspend fun getIcon(packageName: String): ImageBitmap?
}
