package com.joabtv.app.data.remote

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

internal data class TrackerRemoteItem(
    val tmdbId: Int, val series: Boolean, val title: String,
    val season: Int = 0, val episode: Int = 0, val progress: Double = 0.0,
    val positionMs: Long = 0, val durationMs: Long = 0, val changedAt: Long = 0,
)

internal fun trackerDate(value: String): Long = runCatching {
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(value.take(19))?.time ?: 0
}.getOrDefault(0)

internal fun trackerItems(body: String, series: Boolean? = null): List<TrackerRemoteItem> {
    val raw = org.json.JSONTokener(body).nextValue()
    val rows = when (raw) {
        is JSONArray -> (0 until raw.length()).mapNotNull { raw.optJSONObject(it) to series }
        is JSONObject -> {
            val items = raw.optJSONArray("items") ?: raw.optJSONArray("data")
            if (items != null) (0 until items.length()).map { items.optJSONObject(it) to series }
            else listOf("movies" to false, "shows" to true, "anime" to true).flatMap { (key, tv) ->
                val list = raw.optJSONArray(key) ?: JSONArray()
                (0 until list.length()).map { list.optJSONObject(it) to tv }
            }
        }
        else -> emptyList()
    }
    return rows.mapNotNull { (entry, defaultSeries) ->
        val e = entry ?: return@mapNotNull null
        val episode = e.optJSONObject("episode")
        val isSeries = defaultSeries ?: (e.has("show") || episode != null || e.optString("type") in listOf("episode", "show", "tv", "series") || e.optString("media_type") == "tv")
        val media = e.optJSONObject(if (isSeries) "show" else "movie") ?: e
        val id = media.optJSONObject("ids")?.optInt("tmdb") ?: media.optInt("tmdb_id", media.optInt("tmdbid"))
        if (id <= 0) return@mapNotNull null
        val nestedSeason = media.optJSONObject("season")
        val nestedEpisode = nestedSeason?.optJSONObject("episode")
        val season = episode?.optInt("season") ?: e.optInt("season", nestedSeason?.optInt("number") ?: 0)
        val number = episode?.optInt("number") ?: e.optInt("episode", nestedEpisode?.optInt("number") ?: e.optInt("number"))
        val durationMs = e.optLong("runtime_ms", media.optLong("runtime_ms", media.optLong("runtime") * 60_000))
        val positionMs = e.optLong("position_ms")
        val progress = e.optDouble("progress", if (durationMs > 0) positionMs * 100.0 / durationMs else 0.0)
        val at = listOf("paused_at", "updated", "last_watched_at", "watched_at", "added_at", "listed_at", "list_item_created_at").firstNotNullOfOrNull { field ->
            e.optString(field).takeIf { it.isNotBlank() }?.let { trackerDate(it).takeIf { date -> date > 0 } }
        } ?: 0
        TrackerRemoteItem(id, isSeries, media.optString("title").ifBlank { media.optString("name") }, season, number, progress,
            positionMs, durationMs, at)
    }
}
