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
                    val list = trackers.read(provider, "/watchlist/items", profile)
                    trackerItems(list).forEach { importList(it, profile) }
                    importSeasonHistory(trackers.read(provider, "/sync/watched", profile), profile)
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
                TrackerProvider.WETRAKR -> {
                    for ((status, target) in listOf("planning" to "movies", "planning" to "shows", "playing" to "movies", "playing" to "episodes", "watched" to "episodes")) {
                        paged(provider, "/sync/tracking/$status/$target?extended=show_level_1,episode_level_1", profile, target != "movies") { item ->
                            when (status) {
                                "planning" -> importList(item, profile)
                                "playing" -> importPlayback(item, profile)
                                else -> importWatched(item, profile)
                            }
                        }
                    }
                }
            }
            trackers.syncResult(provider, profile, true)
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { trackers.syncResult(provider, profile, false); false }
    }

    private suspend fun paged(provider: TrackerProvider, path: String, profile: String, series: Boolean? = null, onItem: suspend (TrackerRemoteItem) -> Unit) {
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
            trackerItems(text, series).forEach { onItem(it) }
            if (length < 100) break
            page++
        } while (page <= 100 && prefs.profileId.value == profile)
        check(page <= 100) { "A lista é muito grande para uma única sincronização" }
    }

    private suspend fun importList(item: TrackerRemoteItem, profile: String) {
        if (prefs.profileId.value != profile) return
        val type = if (item.series) "SERIES" else "MOVIE"
        if (library.watchlist(profile).first().any { it.tmdbId == item.tmdbId && it.type == type }) return
        var title = item.title
        var poster = ""
        if (prefs.tmdbApiKey.isNotBlank()) {
            if (item.series) {
                val detail = tmdb.getSeriesDetail(item.tmdbId, apiKey = prefs.tmdbApiKey)
                title = title.ifBlank { detail.name.orEmpty() }
                poster = detail.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }.orEmpty()
            } else {
                val detail = tmdb.getMovieDetail(item.tmdbId, apiKey = prefs.tmdbApiKey)
                title = title.ifBlank { detail.title.orEmpty() }
                poster = detail.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }.orEmpty()
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
        var poster = ""
        if (duration <= 0 && prefs.tmdbApiKey.isNotBlank()) {
            if (item.series) {
                val detail = tmdb.getSeriesDetail(item.tmdbId, apiKey = prefs.tmdbApiKey)
                title = title.ifBlank { detail.name.orEmpty() }
                val episode = tmdb.getSeason(item.tmdbId, item.season, apiKey = prefs.tmdbApiKey).episodes.firstOrNull { it.episodeNumber == item.episode }
                duration = (episode?.runtime ?: 0) * 60_000L
            } else {
                val detail = tmdb.getMovieDetail(item.tmdbId, apiKey = prefs.tmdbApiKey)
                title = title.ifBlank { detail.title.orEmpty() }
                duration = (detail.runtime ?: 0) * 60_000L
                poster = detail.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }.orEmpty()
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
