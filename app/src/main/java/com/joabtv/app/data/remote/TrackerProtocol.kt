package com.joabtv.app.data.remote

import org.json.JSONArray
import org.json.JSONObject

enum class TrackerProvider(val label: String, val baseUrl: String, val needsClientId: Boolean = false) {
    SIMKL("Simkl", "https://api.simkl.com", true),
    WETRAKR("WeTrakr", "https://api.wetrakr.com", true),
    PUBLICMETA("PublicMetaDB", "https://publicmetadb.com"),
    MDBLIST("MDBList", "https://api.mdblist.com"),
}

data class TrackerStatus(val connected: Boolean = false, val busy: Boolean = false, val message: String = "Desconectado")
internal data class TrackerCredentials(val token: String, val clientId: String = "", val refresh: String = "", val expiresAt: Long = 0)
internal data class TrackerRequest(val path: String, val body: JSONObject)

internal fun trackerScrobbleBody(provider: TrackerProvider, tmdbId: Int, series: Boolean, season: Int, episode: Int, progress: Double): JSONObject {
    val item = JSONObject().put("ids", JSONObject().put("tmdb", tmdbId))
    val body = JSONObject().put("progress", progress.coerceIn(0.0, 100.0))
    if (series) {
        if (provider == TrackerProvider.MDBLIST) item.put("season", JSONObject().put("number", season).put("episode", JSONObject().put("number", episode)))
        else body.put("episode", JSONObject().put("season", season).put("number", episode))
        if (provider == TrackerProvider.SIMKL) item.put("type", "show")
        body.put("show", item)
    } else body.put("movie", item)
    return body
}

internal fun trackerWatchlistRequest(provider: TrackerProvider, tmdbId: Int, series: Boolean, added: Boolean): TrackerRequest? {
    val item = JSONObject().put("ids", JSONObject().put("tmdb", tmdbId))
    val key = if (series) "shows" else "movies"
    val path = when (provider) {
        TrackerProvider.SIMKL -> {
            // Retirar de Minha Lista não pode apagar os episódios vistos no Simkl.
            if (!added) return null
            item.put("to", "plantowatch")
            "/sync/add-to-list"
        }
        TrackerProvider.WETRAKR -> {
            item.put("status", "planning")
            if (added) "/sync/tracking" else "/sync/tracking/remove"
        }
        TrackerProvider.MDBLIST -> "/watchlist/items/${if (added) "add" else "remove"}"
        TrackerProvider.PUBLICMETA -> return null
    }
    return TrackerRequest(path, JSONObject().put(key, JSONArray().put(item)))
}
