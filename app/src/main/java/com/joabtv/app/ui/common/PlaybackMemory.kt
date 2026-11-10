package com.joabtv.app.ui.common

import android.app.ActivityManager
import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl

internal fun playbackBufferBytes(totalMemory: Long, lowRam: Boolean, preview: Boolean): Int? {
    val constrained = lowRam || totalMemory in 1..(2L * 1024 * 1024 * 1024)
    return when {
        preview -> (if (constrained) 16 else 32) * 1024 * 1024
        constrained -> 64 * 1024 * 1024
        else -> null
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun playbackLoadControl(context: Context, preview: Boolean = false): DefaultLoadControl {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
    val bytes = playbackBufferBytes(memory.totalMem, manager.isLowRamDevice, preview)
    return DefaultLoadControl.Builder().apply {
        if (preview) setBufferDurationsMs(15_000, 50_000, 500, 2_000)
        if (bytes != null) setTargetBufferBytes(bytes)
    }.build()
}
