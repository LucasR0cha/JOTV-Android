package com.joabtv.app.ui.movies

internal class PreviewFrameCache<T>(private val limit: Int = 12) {
    private var source: String? = null
    private val frames = object : LinkedHashMap<Long, T?>(limit + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, T?>) = size > limit
    }

    fun selectSource(url: String) {
        if (source == url) return
        frames.clear()
        source = url
    }

    operator fun contains(bucket: Long) = frames.containsKey(bucket)
    operator fun get(bucket: Long): T? = frames[bucket]
    operator fun set(bucket: Long, frame: T?) { frames[bucket] = frame }
    fun clear() { frames.clear(); source = null }
}

internal fun previewFrameSize(width: Int, height: Int, rotation: Int): Pair<Int, Int>? {
    if (width <= 0 || height <= 0) return null
    val rotated = rotation == 90 || rotation == 270
    val sourceWidth = if (rotated) height else width
    val sourceHeight = if (rotated) width else height
    val targetWidth = minOf(160, sourceWidth)
    return targetWidth to (sourceHeight.toLong() * targetWidth / sourceWidth).toInt().coerceAtLeast(1)
}
