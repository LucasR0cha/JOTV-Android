package com.joabtv.app.data.remote

import com.joabtv.app.BuildConfig

import com.joabtv.app.data.local.PlatformPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MultiTrackerService @Inject constructor(
    private val prefs: PlatformPrefs,
) {
    private val client = OkHttpClient.Builder()
        .dns(AppDns.cloudflare)
        .callTimeout(10, TimeUnit.SECONDS)
        .connectTimeout(6, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jsonType = "application/json".toMediaType()

    private suspend fun postJson(url: String, headers: Map<String, String>, body: JSONObject): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url(url).apply {
                    headers.forEach { (k, v) -> header(k, v) }
                    post(body.toString().toRequestBody(jsonType))
                }.build()
                client.newCall(req).execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }

    fun scrobble(action: String, title: String, imdb: String, tmdbId: Int, isSeries: Boolean, season: Int, episode: Int, progress: Double) {
        scope.launch {
            val prog = (progress * 100).coerceIn(0.0, 100.0)

            // 1. Simkl
            val simklToken = prefs.simklToken
            if (simklToken.isNotBlank()) {
                val simklPath = if (action == "stop" && prog >= 90.0) "/sync/history" else "/sync/playback"
                val body = JSONObject()
                val itemObj = JSONObject()
                val idsObj = JSONObject()
                if (imdb.isNotBlank()) idsObj.put("imdb", imdb)
                if (tmdbId > 0) idsObj.put("tmdb", tmdbId)
                itemObj.put("ids", idsObj)
                itemObj.put("title", title)
                if (isSeries && episode > 0) {
                    val epObj = JSONObject().put("season", season).put("number", episode)
                    itemObj.put("episodes", JSONArray().put(epObj))
                    body.put("shows", JSONArray().put(itemObj))
                } else {
                    body.put("movies", JSONArray().put(itemObj))
                }
                if (action != "stop" || prog < 90.0) body.put("progress", prog.toInt())
                postJson("https://api.simkl.com$simklPath", mapOf(
                    "Authorization" to "Bearer $simklToken",
                    "simkl-api-key" to BuildConfig.SIMKL_CLIENT_ID,
                    "Content-Type" to "application/json"
                ), body)
            }

            // 2. WeTrakr
            val wetrakrKey = prefs.wetrakrKey
            if (wetrakrKey.isNotBlank()) {
                val body = JSONObject()
                    .put("action", action)
                    .put("progress", prog)
                    .put("title", title)
                    .put("imdb", imdb)
                    .put("tmdb", tmdbId)
                    .put("series", isSeries)
                    .put("season", season)
                    .put("episode", episode)
                postJson("https://api.wetrakr.com/v1/scrobble", mapOf(
                    "Authorization" to "Bearer $wetrakrKey",
                    "Content-Type" to "application/json"
                ), body)
            }

            // 3. PublicMetaDB
            val pmdbKey = prefs.publicmetadbKey
            if (pmdbKey.isNotBlank()) {
                val body = JSONObject()
                    .put("event", action)
                    .put("imdb_id", imdb)
                    .put("progress", prog)
                    .put("season", season)
                    .put("episode", episode)
                postJson("https://publicmetadb.com/api/v1/playback", mapOf(
                    "X-Api-Key" to pmdbKey,
                    "Content-Type" to "application/json"
                ), body)
            }

            // 4. MDBList
            val mdbKey = prefs.mdblistKey
            if (mdbKey.isNotBlank() && action == "stop" && prog >= 90.0) {
                val body = JSONObject()
                    .put("imdb", imdb)
                    .put("tmdb", tmdbId)
                    .put("season", season)
                    .put("episode", episode)
                    .put("watched", true)
                postJson("https://api.mdblist.com/watchlist/watch?apikey=$mdbKey", mapOf(
                    "Content-Type" to "application/json"
                ), body)
            }
        }
    }

    fun watchlist(title: String, imdb: String, tmdbId: Int, isSeries: Boolean, inList: Boolean) {
        scope.launch {
            val simklToken = prefs.simklToken
            if (simklToken.isNotBlank()) {
                val path = if (inList) "/sync/add-to-list" else "/sync/delete-from-list"
                val body = JSONObject()
                val itemObj = JSONObject()
                val idsObj = JSONObject()
                if (imdb.isNotBlank()) idsObj.put("imdb", imdb)
                if (tmdbId > 0) idsObj.put("tmdb", tmdbId)
                itemObj.put("ids", idsObj)
                itemObj.put("title", title)
                if (isSeries) body.put("shows", JSONArray().put(itemObj))
                else body.put("movies", JSONArray().put(itemObj))
                postJson("https://api.simkl.com$path", mapOf(
                    "Authorization" to "Bearer $simklToken",
                    "simkl-api-key" to BuildConfig.SIMKL_CLIENT_ID,
                    "Content-Type" to "application/json"
                ), body)
            }

            val mdbKey = prefs.mdblistKey
            if (mdbKey.isNotBlank()) {
                val act = if (inList) "add" else "remove"
                val body = JSONObject().put("imdb", imdb).put("tmdb", tmdbId).put("mediatype", if (isSeries) "show" else "movie")
                postJson("https://api.mdblist.com/watchlist/$act?apikey=$mdbKey", mapOf("Content-Type" to "application/json"), body)
            }
        }
    }
}
