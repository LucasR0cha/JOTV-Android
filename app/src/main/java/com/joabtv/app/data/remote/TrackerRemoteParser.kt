package com.joabtv.app.data.remote

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Linhas por página compacta do WeTrakr. É o teto da API. */
internal const val WETRAKR_COMPACT = 5000

internal data class TrackerRemoteItem(
    val tmdbId: Int, val series: Boolean, val title: String,
    val season: Int = 0, val episode: Int = 0, val progress: Double = 0.0,
    val positionMs: Long = 0, val durationMs: Long = 0, val changedAt: Long = 0,
    /** Endereço completo do pôster quando o serviço já manda (o WeTrakr manda). Vazio manda buscar no TMDB. */
    val poster: String = "",
)

internal fun trackerDate(value: String): Long = runCatching {
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(value.take(19))?.time ?: 0
}.getOrDefault(0)

/** Próximo cursor da paginação do MDBList, ou null no fim. */
internal fun mdblistNextCursor(body: String): String? = runCatching {
    JSONObject(body).optJSONObject("pagination")?.optString("next_cursor")?.takeIf { it.isNotBlank() && it != "null" }
}.getOrNull()

/** Episódios vistos do MDBList (série TMDB, temporada, episódio). Aceita ids_only e o formato completo. */
internal fun mdblistWatchedEpisodes(body: String): List<Triple<Int, Int, Int>> {
    val rows = runCatching { JSONObject(body).optJSONArray("episodes") }.getOrNull() ?: return emptyList()
    return (0 until rows.length()).mapNotNull { i ->
        val e = rows.optJSONObject(i) ?: return@mapNotNull null
        val full = e.optJSONObject("episode")
        val show = full?.optJSONObject("show")?.optJSONObject("ids")?.optInt("tmdb") ?: e.optInt("show")
        val season = full?.optInt("season", -1) ?: e.optInt("season", -1)
        val episode = full?.optInt("number") ?: e.optInt("episode")
        if (show > 0 && season >= 0 && episode > 0) Triple(show, season, episode) else null
    }
}

/** Só puxa o histórico quando o carimbo do last_activities mudou. Sem carimbo puxa sempre. */
internal fun mdblistWatchedChanged(stamp: String, saved: String) = stamp.isBlank() || stamp != saved

// ── WeTrakr ──────────────────────────────────────────────────────────────────
// Formatos conforme a doc em api.wetrakr.com. A sincronia lê last_activities primeiro e só puxa a seção que mudou.

private fun wetrakrRows(body: String): List<JSONObject> {
    val array = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
    return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
}

/** Minha Lista (lista planning, formato completo). Já traz título, ano e pôster, então não precisa do TMDB. */
internal fun wetrakrListItems(body: String, series: Boolean): List<TrackerRemoteItem> = wetrakrRows(body).mapNotNull { row ->
    val id = row.optJSONObject("ids")?.optInt("tmdb") ?: 0
    if (id <= 0) return@mapNotNull null
    TrackerRemoteItem(id, series, row.optString("title").ifBlank { row.optString("name") },
        changedAt = trackerDate(row.optString("tracked_at")),
        poster = row.optString("poster_path").takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/w342$it" }.orEmpty())
}

/** Onde parou (lista playing). A posição é progress_percent vezes runtime_seconds, o WeTrakr não guarda posição absoluta. */
internal fun wetrakrPlaybackItems(body: String): List<TrackerRemoteItem> = wetrakrRows(body).mapNotNull { row ->
    val playback = row.optJSONObject("playback") ?: return@mapNotNull null
    val isEpisode = row.optString("type") == "episode" || row.has("show")
    val media = (if (isEpisode) row.optJSONObject("show") else row) ?: return@mapNotNull null
    val id = media.optJSONObject("ids")?.optInt("tmdb") ?: 0
    val at = trackerDate(playback.optString("tracked_at"))
    if (id <= 0 || at <= 0) return@mapNotNull null
    TrackerRemoteItem(id, isEpisode, media.optString("title"),
        season = if (isEpisode) row.optInt("season_number", row.optInt("season")) else 0,
        episode = if (isEpisode) row.optInt("number", row.optInt("episode_number")) else 0,
        progress = playback.optDouble("progress_percent", 0.0),
        durationMs = playback.optLong("runtime_seconds") * 1000, changedAt = at,
        poster = media.optString("poster_path").takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/w342$it" }.orEmpty())
}

/** Episódios vistos. Aceita a linha compacta (show_ids) e a completa (show.ids). [changedAt] leva a data do último play. */
internal fun wetrakrWatchedItems(body: String): List<TrackerRemoteItem> = wetrakrRows(body).mapNotNull { row ->
    val show = row.optJSONObject("show_ids")?.optInt("tmdb") ?: row.optJSONObject("show")?.optJSONObject("ids")?.optInt("tmdb") ?: 0
    val season = if (row.has("season_number")) row.optInt("season_number") else row.optInt("season", -1)
    val number = if (row.has("number")) row.optInt("number") else row.optInt("episode_number")
    if (show <= 0 || season < 0 || number <= 0) return@mapNotNull null
    TrackerRemoteItem(show, true, "", season, number,
        changedAt = trackerDate(row.optString("last_watched_at").ifBlank { row.optString("watched_at") }))
}

/** Seção da sincronia do WeTrakr. [stamps] são os carimbos do last_activities que dizem se ela mudou. */
internal data class WetrakrSection(val key: String, val stamps: List<String>) {
    val signature: String get() = stamps.joinToString("|")
    /** O carimbo mais novo (texto ISO), que vira o from_date da próxima puxada. Vazio quando a pessoa nunca usou a seção. */
    val newest: String get() = stamps.filter { it.isNotBlank() }.maxByOrNull { trackerDate(it) }.orEmpty()
}

internal fun wetrakrSections(activities: JSONObject): List<WetrakrSection> {
    fun stamp(section: String, field: String) = activities.optJSONObject(section)?.optString(field).orEmpty()
    return listOf(
        WetrakrSection("list", listOf(stamp("movies", "last_tracking_planning_at"), stamp("shows", "last_tracking_planning_at"))),
        WetrakrSection("playing", listOf(stamp("movies", "last_playback_at"), stamp("episodes", "last_playback_at"))),
        WetrakrSection("watched", listOf(stamp("episodes", "last_tracking_watched_at"))),
    )
}

/** O WeTrakr devolve 429 com QUOTA_EXCEEDED quando acaba a cota do dia. Esperar um minuto não adianta, só gasta mais cota. */
internal fun wetrakrQuotaExceeded(code: Int, body: String) = code == 429 && "QUOTA_EXCEEDED" in body

/** [plainId] usa o `id` do item como TMDB quando não há ids (lista do MDBList, onde a série vem sem o bloco ids). */
internal fun trackerItems(body: String, series: Boolean? = null, plainId: Boolean = false): List<TrackerRemoteItem> {
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
        val id = media.optJSONObject("ids")?.optInt("tmdb")?.takeIf { it > 0 }
            ?: media.optInt("tmdb_id", media.optInt("tmdbid")).takeIf { it > 0 }
            ?: if (plainId) media.optInt("id") else 0
        if (id <= 0) return@mapNotNull null
        val nestedSeason = media.optJSONObject("season")
        val nestedEpisode = nestedSeason?.optJSONObject("episode")
        val season = episode?.optInt("season") ?: e.optInt("season", nestedSeason?.optInt("number") ?: 0)
        val number = episode?.optInt("number") ?: e.optInt("episode", nestedEpisode?.optInt("number") ?: e.optInt("number"))
        // O playback do MDBList traz runtime (minutos) na linha, não dentro de movie ou show.
        val minutes = media.optLong("runtime").takeIf { it > 0 } ?: e.optLong("runtime")
        val durationMs = e.optLong("runtime_ms", media.optLong("runtime_ms", minutes * 60_000))
        val positionMs = e.optLong("position_ms")
        val progress = e.optDouble("progress", if (durationMs > 0) positionMs * 100.0 / durationMs else 0.0)
        val at = listOf("paused_at", "updated", "last_watched_at", "watched_at", "added_at", "listed_at", "list_item_created_at").firstNotNullOfOrNull { field ->
            e.optString(field).takeIf { it.isNotBlank() }?.let { trackerDate(it).takeIf { date -> date > 0 } }
        } ?: 0
        TrackerRemoteItem(id, isSeries, media.optString("title").ifBlank { media.optString("name") }, season, number, progress,
            positionMs, durationMs, at)
    }
}
