package com.joabtv.app.data.local

import org.json.JSONObject

internal fun playbackKey(tmdbId: Int, type: String): String =
    (if (type.equals("SERIES", true) || type.equals("tv", true)) "series" else "movie") + ":" + tmdbId

internal fun playbackHiddenBefore(raw: String, key: String): Long {
    val data = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
    return maxOf(data.optLong("allBefore"), data.optJSONObject("items")?.optLong(key) ?: 0)
}

internal fun hidePlayback(raw: String, key: String, at: Long): String {
    val data = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
    val items = data.optJSONObject("items") ?: JSONObject()
    items.put(key, maxOf(items.optLong(key), at))
    return data.put("items", items).toString()
}

internal fun unhidePlayback(raw: String, key: String): String {
    val data = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
    data.optJSONObject("items")?.remove(key)
    return data.toString()
}

internal fun mergePlaybackVisibility(current: String, incoming: String): String {
    val left = runCatching { JSONObject(current) }.getOrElse { JSONObject() }
    val right = runCatching { JSONObject(incoming) }.getOrElse { JSONObject() }
    val items = left.optJSONObject("items") ?: JSONObject()
    right.optJSONObject("items")?.let { other -> other.keys().forEach { key -> items.put(key, maxOf(items.optLong(key), other.optLong(key))) } }
    return left.put("allBefore", maxOf(left.optLong("allBefore"), right.optLong("allBefore"))).put("items", items).toString()
}
