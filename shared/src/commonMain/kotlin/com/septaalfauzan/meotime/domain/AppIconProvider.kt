package com.septaalfauzan.meotime.domain

import androidx.compose.ui.graphics.ImageBitmap

fun interface AppIconProvider {
    suspend fun getIcon(packageName: String): ImageBitmap?
}
