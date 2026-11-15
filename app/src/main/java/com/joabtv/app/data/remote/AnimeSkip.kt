package com.joabtv.app.data.remote

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

private fun animeName(value: String) = value.lowercase().replace(Regex("[^a-z0-9]"), "")

internal fun selectAnimeEpisode(shows: JSONArray, title: String, season: Int, episode: Int, durationSec: Double): JSONObject? {
    val wanted = animeName(title)
    val candidates = mutableListOf<JSONObject>()
    for (i in 0 until shows.length()) {
        val show = shows.optJSONObject(i) ?: continue
        if (listOf(show.optString("name"), show.optString("originalName")).none { animeName(it) == wanted }) continue
        val episodes = show.optJSONArray("episodes") ?: continue
        for (n in 0 until episodes.length()) {
            val item = episodes.optJSONObject(n) ?: continue
            val seasonText = item.optString("season").trim()
            val itemSeason = seasonText.toDoubleOrNull()
            if (item.optString("number").toDoubleOrNull() != episode.toDouble()) continue
            if (itemSeason != season.toDouble() && !((seasonText.isBlank() || seasonText == "null") && season == 1)) continue
            val length = item.optDouble("baseDuration", 0.0)
            if (length > 0 && durationSec > 0 && abs(length - durationSec) > 60) continue
            candidates += item
        }
    }
    return candidates.minByOrNull {
        (if (it.optString("season").toDoubleOrNull() == season.toDouble()) 0 else 1_000_000) +
            (if (durationSec > 0 && it.optDouble("baseDuration", 0.0) > 0) abs(it.optDouble("baseDuration") - durationSec) else 1000.0)
    }
}

internal fun animeSkipSegments(episode: JSONObject, durationSec: Double): SkipSegments {
    data class Timestamp(val at: Double, val name: String)
    val raw = episode.optJSONArray("timestamps") ?: return SkipSegments()
    val timestamps = (0 until raw.length()).mapNotNull { index ->
        val entry = raw.optJSONObject(index) ?: return@mapNotNull null
        val at = entry.optDouble("at")
        if (!at.isFinite() || at < 0) null else Timestamp(at, entry.optJSONObject("type")?.optString("name").orEmpty().lowercase())
    }.sortedBy { it.at }
    val baseDuration = episode.optDouble("baseDuration", 0.0)
    val end = listOf(baseDuration, durationSec).filter { it > 0 }.minOrNull()
    var recap: SkipSegment? = null
    var intro: SkipSegment? = null
    var outro: SkipSegment? = null
    var post: SkipSegment? = null
    timestamps.forEachIndexed { index, timestamp ->
        val next = timestamps.getOrNull(index + 1)?.at ?: end ?: timestamp.at
        val stop = minOf(next, end ?: next)
        val segment = if (stop - timestamp.at >= 3) SkipSegment((timestamp.at * 1000).toLong(), (stop * 1000).toLong()) else null
        when {
            "recap" in timestamp.name -> if (recap == null) recap = segment
            "intro" in timestamp.name || "opening" in timestamp.name -> if (intro == null) intro = segment
            Regex("(?:post|after)[ -]*credits").containsMatchIn(timestamp.name) -> if (post == null) post = segment
            "credit" in timestamp.name || "ending" in timestamp.name -> if (outro == null) outro = segment
            outro != null && timestamp.name in listOf("canon", "filler") -> if (post == null) post = segment
        }
    }
    return SkipSegments(recap, intro, outro, post)
}
