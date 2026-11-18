package com.joabtv.app.data.local

fun playbackCompleted(positionMs: Long, durationMs: Long): Boolean =
    durationMs > 0 && positionMs > 0 && positionMs.toDouble() >= durationMs.toDouble() * 0.9
