package com.septaalfauzan.meotime.presentation.overlay

import io.github.alexzhirkevich.compottie.LottieComposition
import io.github.alexzhirkevich.compottie.LottieCompositionSpec
import meotime.shared.generated.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi

object SleepingCatOverlayAnimation {
    var composition: LottieComposition? = null
        private set

    @OptIn(ExperimentalResourceApi::class)
    suspend fun preload() {
        if (composition != null) return
        val json = Res.readBytes("files/sleeping_cat.json").decodeToString()
        composition = LottieCompositionSpec.JsonString(json).load()
    }
}
