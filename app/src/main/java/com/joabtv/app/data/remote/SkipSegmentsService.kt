package com.joabtv.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class SkipSegment(val start: Long, val end: Long)
data class SkipSegments(
    val recap: SkipSegment? = null,
    val intro: SkipSegment? = null,
    val outro: SkipSegment? = null,
    val postCredits: SkipSegment? = null,
)

internal fun mergeSkipSegments(list: List<SkipSegments?>, durationSec: Double): SkipSegments {
    val duration = (durationSec * 1000).toLong().takeIf { durationSec.isFinite() && it > 0 }
    fun bounded(segment: SkipSegment?): SkipSegment? = segment?.let {
        val end = minOf(it.end, duration ?: it.end)
        if (end - it.start >= 3000) SkipSegment(it.start, end) else null
    }
    val sources = list.filterNotNull().filter { source ->
        duration == null || listOf(source.recap, source.intro, source.outro, source.postCredits).filterNotNull().none { it.end > duration + 5000 }
    }.map { source -> SkipSegments(bounded(source.recap), bounded(source.intro), bounded(source.outro), bounded(source.postCredits)) }
    val post = sources.firstNotNullOfOrNull { it.postCredits }
    val outro = sources.firstNotNullOfOrNull { source -> source.outro?.let { segment ->
        bounded(if (post != null) segment.copy(end = minOf(segment.end, post.start)) else segment)
    } }
    return SkipSegments(sources.firstNotNullOfOrNull { it.recap }, sources.firstNotNullOfOrNull { it.intro }, outro, post)
}

@Singleton
class SkipSegmentsService @Inject constructor() {
    private val client = OkHttpClient.Builder()
        .dns(AppDns.cloudflare)
        .callTimeout(8, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .build()

    private val cache = ConcurrentHashMap<String, SkipSegments>()

    private fun seg(a: Double, b: Double): SkipSegment? {
        if (!a.isFinite() || !b.isFinite() || b - a < 3000) return null
        return SkipSegment(maxOf(0L, a.toLong()), b.toLong())
    }

    private fun ms(o: JSONObject?): SkipSegment? {
        if (o == null) return null
        if (o.optString("match") == "out-of-range") return null
        if (o.has("start_ms") && o.has("end_ms")) {
            return seg(o.optDouble("start_ms"), o.optDouble("end_ms"))
        }
        if (o.has("start_sec") && o.has("end_sec")) {
            return seg(o.optDouble("start_sec") * 1000.0, o.optDouble("end_sec") * 1000.0)
        }
        return null
    }

    private suspend fun getJson(url: String, headers: Map<String, String> = emptyMap(), postBody: String? = null): JSONObject? =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url(url).apply {
                    headers.forEach { (k, v) -> header(k, v) }
                    if (postBody != null) {
                        post(postBody.toRequestBody("application/json".toMediaType()))
                    }
                }.build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) null
                    else resp.body?.string()?.let { JSONObject(it) }
                }
            }.getOrNull()
        }

    private suspend fun fromIntroDb(imdb: String, isSeries: Boolean, season: Int, episode: Int): SkipSegments? {
        if (imdb.isBlank()) return null
        val url = "https://api.introdb.app/segments?imdb_id=$imdb" +
            (if (isSeries) "&season=$season&episode=$episode" else "&is_movie=true")
        val j = getJson(url) ?: return null
        return SkipSegments(
            recap = ms(j.optJSONObject("recap")),
            intro = ms(j.optJSONObject("intro")),
            outro = ms(j.optJSONObject("outro")),
            postCredits = ms(j.optJSONObject("post_credits")),
        )
    }

    private suspend fun fromSkipDb(imdb: String, isSeries: Boolean, season: Int, episode: Int, durationSec: Double): SkipSegments? {
        if (imdb.isBlank()) return null
        val url = "https://api.skipdb.tv/api/segments?imdb_id=$imdb" +
            (if (isSeries) "&season=$season&episode=$episode" else "") +
            (if (durationSec > 0) "&duration=${durationSec.toLong()}" else "")
        val j = getJson(url) ?: return null
        val s = j.optJSONObject("segments") ?: return null
        return SkipSegments(
            recap = ms(s.optJSONObject("recap")),
            intro = ms(s.optJSONObject("intro")),
            outro = ms(s.optJSONObject("outro")),
        )
    }

    private suspend fun fromAnimeSkip(title: String, isSeries: Boolean, season: Int, episode: Int, durationSec: Double): SkipSegments? {
        if (!isSeries || title.isBlank() || episode <= 0) return null
        val gql = "query(\$s:String!){searchShows(search:\$s,limit:5){name originalName episodes{id season number baseDuration}}}"
        val body = JSONObject().put("query", gql).put("variables", JSONObject().put("s", title)).toString()
        val headers = mapOf("X-Client-ID" to "ZGfO0sMF3eCwLYf8yMSCJjlynwNGRXWE")
        val j = getJson("https://api.anime-skip.com/graphql", headers, body) ?: return null
        val shows = j.optJSONObject("data")?.optJSONArray("searchShows") ?: return null
        val candidate = selectAnimeEpisode(shows, title, season, episode, durationSec) ?: return null
        val id = candidate.optString("id").takeIf { it.isNotBlank() } ?: return null
        val detailQuery = "query(\$e:ID!){findEpisode(episodeId:\$e){baseDuration timestamps{at type{name}}}}"
        val detailBody = JSONObject().put("query", detailQuery).put("variables", JSONObject().put("e", id)).toString()
        val detail = getJson("https://api.anime-skip.com/graphql", headers, detailBody)?.optJSONObject("data")?.optJSONObject("findEpisode") ?: return null
        return animeSkipSegments(detail, durationSec)
    }

    suspend fun get(
        imdb: String,
        title: String,
        isSeries: Boolean,
        season: Int,
        episode: Int,
        durationSec: Double = 0.0,
    ): SkipSegments = coroutineScope {
        val key = "$imdb|$title|${if (isSeries) "$season:$episode" else "movie"}|${kotlin.math.round(durationSec).toLong()}"
        cache[key]?.let { return@coroutineScope it }

        val a = async { fromIntroDb(imdb, isSeries, season, episode) }
        val b = async { fromSkipDb(imdb, isSeries, season, episode, durationSec) }
        val c = async { fromAnimeSkip(title, isSeries, season, episode, durationSec) }

        val res = mergeSkipSegments(listOf(a.await(), b.await(), c.await()), durationSec)
        if (res.recap != null || res.intro != null || res.outro != null || res.postCredits != null) {
            cache[key] = res
        }
        res
    }
}
