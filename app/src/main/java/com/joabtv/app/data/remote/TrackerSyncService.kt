package com.joabtv.app.data.remote

import com.joabtv.app.data.local.PlatformPrefs
import com.joabtv.app.data.repository.LibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrackerSyncService @Inject constructor(
    private val trackers: MultiTrackerService,
    private val prefs: PlatformPrefs,
    private val library: LibraryRepository,
    private val tmdb: TmdbApiService,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val locks = TrackerProvider.entries.associateWith { Mutex() }

    fun syncInBackground(only: TrackerProvider? = null) {
        val profile = prefs.profileId.value
        TrackerProvider.entries.filter { (only == null || only == it) && trackers.savedToken(it).isNotBlank() }.forEach { provider ->
            scope.launch { sync(provider, profile) }
        }
    }

    suspend fun sync(provider: TrackerProvider, profile: String = prefs.profileId.value): Boolean = locks.getValue(provider).withLock {
        if (prefs.profileId.value != profile || trackers.savedToken(provider).isBlank()) return@withLock false
        try {
            if (!trackers.verify(provider)) return@withLock false
            when (provider) {
                TrackerProvider.PUBLICMETA -> {
                    paged(provider, "/api/external/resume", profile) { importPlayback(it, profile) }
                    paged(provider, "/api/external/watched", profile) { importWatched(it, profile) }
                    trackers.publicListId(profile)?.let { listId -> paged(provider, "/api/external/lists/$listId/items", profile) { importList(it, profile) } }
                }
                TrackerProvider.MDBLIST -> {
                    val playback = trackers.read(provider, "/sync/playback", profile)
                    trackerItems(playback).forEach { importPlayback(it, profile) }
                    // Na lista a série vem sem o bloco ids e o id do item já é o do TMDB.
                    mdblistPages("/watchlist/items?limit=1000", profile) { body -> trackerItems(body, plainId = true).forEach { importList(it, profile) } }
                    importMdblistWatched(profile)
                }
                TrackerProvider.SIMKL -> {
                    trackerItems(trackers.read(provider, "/sync/playback", profile)).forEach { importPlayback(it, profile) }
                    val all = trackers.read(provider, "/sync/all-items/?extended=full", profile)
                    val root = JSONObject(all)
                    for (key in listOf("movies", "shows", "anime")) {
                        val array = root.optJSONArray(key) ?: continue
                        val planned = JSONArray()
                        for (i in 0 until array.length()) array.optJSONObject(i)?.takeIf { it.optString("status") == "plantowatch" }?.let { planned.put(it) }
                        trackerItems(planned.toString(), key != "movies").forEach { importList(it, profile) }
                    }
                    importSeasonHistory(all, profile)
                }
                TrackerProvider.WETRAKR -> syncWetrakr(profile)
            }
            trackers.syncResult(provider, profile, true)
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { trackers.syncResult(provider, profile, false); false }
    }

    private suspend fun paged(provider: TrackerProvider, path: String, profile: String, series: Boolean? = null, onItem: suspend (TrackerRemoteItem) -> Unit) =
        pagedPages(provider, path, profile, { trackerItems(it, series) }) { items -> items.forEach { onItem(it) } }

    /** Anda pelas páginas de 100 e entrega cada página já lida por [parse]. Para no teto de 100 páginas. */
    private suspend fun pagedPages(provider: TrackerProvider, path: String, profile: String, parse: (String) -> List<TrackerRemoteItem>, onPage: suspend (List<TrackerRemoteItem>) -> Unit) {
        var page = 1
        do {
            val suffix = if ('?' in path) "&" else "?"
            val text = trackers.read(provider, "$path${suffix}page=$page&${if (provider == TrackerProvider.PUBLICMETA) "perPage" else "limit"}=100", profile)
            val raw = org.json.JSONTokener(text).nextValue()
            val length = when (raw) {
                is JSONArray -> raw.length()
                is JSONObject -> (raw.optJSONArray("items") ?: raw.optJSONArray("data"))?.length() ?: 0
                else -> 0
            }
            onPage(parse(text))
            if (length < 100) break
            page++
        } while (page <= 100 && prefs.profileId.value == profile)
        check(page <= 100) { "A lista é muito grande para uma única sincronização" }
    }

    /**
     * Sincronia econômica do WeTrakr. A doc cobra isso e a cota é curta (1000 chamadas por dia para cada usuário grátis,
     * 2000 no total da chave de desenvolvimento). Primeiro last_activities, uma chamada só, e depois só as seções que mudaram,
     * com from_date. Sem mudança nenhuma é uma chamada por sincronia.
     */
    private suspend fun syncWetrakr(profile: String) {
        val provider = TrackerProvider.WETRAKR
        val activities = JSONObject(trackers.read(provider, "/sync/last_activities", profile))
        for (section in wetrakrSections(activities)) {
            if (section.signature == trackers.syncMark(provider, section.key)) continue
            // Seção que a pessoa nunca usou não tem o que puxar.
            if (section.newest.isNotBlank()) {
                val from = trackers.syncMark(provider, section.key + "From")
                val query = if (from.isBlank()) "" else "from_date=" + java.net.URLEncoder.encode(from, "UTF-8")
                when (section.key) {
                    "list" -> {
                        val q = if (query.isBlank()) "" else "?$query"
                        pagedPages(provider, "/sync/tracking/planning/movies$q", profile, { wetrakrListItems(it, false) }) { items -> items.forEach { importList(it, profile) } }
                        pagedPages(provider, "/sync/tracking/planning/shows$q", profile, { wetrakrListItems(it, true) }) { items -> items.forEach { importList(it, profile) } }
                    }
                    "playing" -> for (target in listOf("movies", "episodes")) {
                        pagedPages(provider, "/sync/tracking/playing/$target" + if (query.isBlank()) "" else "?$query", profile, ::wetrakrPlaybackItems) { items ->
                            items.forEach { importPlayback(it, profile) }
                        }
                    }
                    else -> importWetrakrWatched(query, profile)
                }
            }
            // O carimbo só é guardado depois de puxar tudo, senão uma falha no meio pularia linhas para sempre.
            trackers.setSyncMark(provider, section.key, section.signature, profile)
            trackers.setSyncMark(provider, section.key + "From", section.newest, profile)
        }
    }

    /** Episódios vistos em página compacta (até 5000 linhas). Se vier cheia, repete nas páginas de 100 para não perder linha. */
    private suspend fun importWetrakrWatched(query: String, profile: String) {
        val provider = TrackerProvider.WETRAKR
        val path = "/sync/tracking/watched/episodes"
        val text = trackers.read(provider, "$path?compact=true&limit=$WETRAKR_COMPACT" + if (query.isBlank()) "" else "&$query", profile)
        markWatched(wetrakrWatchedItems(text), profile)
        if (runCatching { JSONArray(text).length() }.getOrDefault(0) >= WETRAKR_COMPACT)
            pagedPages(provider, path + if (query.isBlank()) "" else "?$query", profile, ::wetrakrWatchedItems) { markWatched(it, profile) }
    }

    /** Marca os episódios por série em lote e em silêncio, porque vieram de fora e não podem mexer na ordem do Continuar assistindo. */
    private suspend fun markWatched(items: List<TrackerRemoteItem>, profile: String) {
        for ((show, rows) in items.filter { it.series && it.season >= 0 && it.episode > 0 }.groupBy { it.tmdbId }) {
            if (prefs.profileId.value != profile) return
            library.markEpisodesWatched(show, rows.map { it.season to it.episode }, profile, silent = true,
                times = rows.filter { it.changedAt > 0 }.associate { (it.season to it.episode) to it.changedAt })
        }
    }

    /** Segue o next_cursor do MDBList até acabar (teto de 100 páginas, como o paged). */
    private suspend fun mdblistPages(path: String, profile: String, onPage: suspend (String) -> Unit) {
        var cursor: String? = null
        for (page in 1..100) {
            if (prefs.profileId.value != profile) error("O perfil mudou")
            val suffix = cursor?.let { "&cursor=" + java.net.URLEncoder.encode(it, "UTF-8") }.orEmpty()
            val body = trackers.read(TrackerProvider.MDBLIST, path + suffix, profile)
            onPage(body)
            cursor = mdblistNextCursor(body) ?: return
        }
        error("A lista é muito grande para uma única sincronização")
    }

    /** O /sync/watched do MDBList não tem o formato do Trakt. Só puxa quando o last_activities mudou. */
    private suspend fun importMdblistWatched(profile: String) {
        val provider = TrackerProvider.MDBLIST
        val stamp = JSONObject(trackers.read(provider, "/sync/last_activities", profile)).optString("episode_watched_at")
        if (!mdblistWatchedChanged(stamp, trackers.syncMark(provider, "episode_watched_at"))) return
        mdblistPages("/sync/watched?mediatype=episode&extended=ids_only&limit=1000", profile) { body ->
            mdblistWatchedEpisodes(body).forEach { (id, season, episode) -> importWatched(TrackerRemoteItem(id, true, "", season, episode), profile) }
        }
        // O carimbo só é guardado depois de puxar tudo, senão uma falha no meio pularia episódios para sempre.
        if (stamp.isNotBlank()) trackers.setSyncMark(provider, "episode_watched_at", stamp, profile)
    }

    private suspend fun importList(item: TrackerRemoteItem, profile: String) {
        if (prefs.profileId.value != profile) return
        val type = if (item.series) "SERIES" else "MOVIE"
        if (library.watchlist(profile).first().any { it.tmdbId == item.tmdbId && it.type == type }) return
        var title = item.title
        var poster = item.poster
        // O WeTrakr já manda título e pôster. Os outros serviços mandam só o título, e o pôster vem do TMDB.
        if ((title.isBlank() || poster.isBlank()) && prefs.tmdbApiKey.isNotBlank()) {
            if (item.series) {
                val detail = tmdb.getSeriesDetail(item.tmdbId, apiKey = prefs.tmdbApiKey)
                title = title.ifBlank { detail.name.orEmpty() }
                poster = poster.ifBlank { detail.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }.orEmpty() }
            } else {
                val detail = tmdb.getMovieDetail(item.tmdbId, apiKey = prefs.tmdbApiKey)
                title = title.ifBlank { detail.title.orEmpty() }
                poster = poster.ifBlank { detail.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }.orEmpty() }
            }
        }
        library.importToList(item.tmdbId, type, title.ifBlank { item.tmdbId.toString() }, poster, "", item.changedAt, profile)
    }

    private suspend fun importWatched(item: TrackerRemoteItem, profile: String) {
        if (prefs.profileId.value != profile || !item.series || item.season < 0 || item.episode <= 0) return
        library.markEpisodeWatched(item.tmdbId, item.season, item.episode, profile)
    }

    private suspend fun importPlayback(item: TrackerRemoteItem, profile: String) {
        if (prefs.profileId.value != profile || item.changedAt <= 0) return
        val type = if (item.series) "SERIES" else "MOVIE"
        if ((library.getPlayback(item.tmdbId, type, profile)?.lastWatchedAt ?: 0) >= item.changedAt) return
        var duration = item.durationMs
        var title = item.title
        var poster = item.poster
        if (duration <= 0 && prefs.tmdbApiKey.isNotBlank()) {
            if (item.series) {
                val detail = tmdb.getSeriesDetail(item.tmdbId, apiKey = prefs.tmdbApiKey)
                title = title.ifBlank { detail.name.orEmpty() }
                poster = poster.ifBlank { detail.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }.orEmpty() }
                val episode = tmdb.getSeason(item.tmdbId, item.season, apiKey = prefs.tmdbApiKey).episodes.firstOrNull { it.episodeNumber == item.episode }
                duration = (episode?.runtime ?: 0) * 60_000L
            } else {
                val detail = tmdb.getMovieDetail(item.tmdbId, apiKey = prefs.tmdbApiKey)
                title = title.ifBlank { detail.title.orEmpty() }
                duration = (detail.runtime ?: 0) * 60_000L
                poster = poster.ifBlank { detail.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }.orEmpty() }
            }
        }
        if (duration <= 0) return
        val position = item.positionMs.takeIf { it > 0 } ?: (duration * item.progress.coerceIn(0.0, 100.0) / 100).toLong()
        library.importPlayback(item.tmdbId, type, title, poster, position, duration,
            if (item.series) "T${item.season}:E${item.episode}" else "", item.changedAt, profile)
    }

    private suspend fun importSeasonHistory(body: String, profile: String) {
        val root = JSONObject(body)
        for (key in listOf("shows", "anime")) {
            val shows = root.optJSONArray(key) ?: continue
            for (i in 0 until shows.length()) {
                val show = shows.optJSONObject(i) ?: continue
                val media = show.optJSONObject("show") ?: show
                val id = media.optJSONObject("ids")?.optInt("tmdb") ?: continue
                val seasons = show.optJSONArray("seasons") ?: continue
                for (s in 0 until seasons.length()) {
                    val season = seasons.optJSONObject(s) ?: continue
                    val episodes = season.optJSONArray("episodes") ?: continue
                    for (e in 0 until episodes.length()) {
                        val episode = episodes.optJSONObject(e) ?: continue
                        importWatched(TrackerRemoteItem(id, true, "", season.optInt("number"), episode.optInt("number")), profile)
                    }
                }
            }
        }
    }
}
