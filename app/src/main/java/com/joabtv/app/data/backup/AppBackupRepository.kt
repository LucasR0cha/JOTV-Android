package com.joabtv.app.data.backup

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.joabtv.app.data.debrid.DebridService
import com.joabtv.app.data.local.AddonEntry
import com.joabtv.app.data.local.InterfaceMotion
import com.joabtv.app.data.local.PlatformPrefs
import com.joabtv.app.data.local.SubLangs
import com.joabtv.app.data.plugins.PluginRepository
import com.joabtv.app.data.repository.LibraryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class BackupPreview(
    val platform: String,
    val profileName: String,
    val exportedAt: Long,
    val addons: Int,
    val pluginRepos: Int,
    val watchlist: Int,
    val history: Int,
    val watchedSeries: Int,
    val watchedEpisodes: Int,
    val recentSearches: Int,
    val collections: Int,
    val hasSecrets: Boolean,
    val rawText: String,
)

data class BackupSummary(
    val addons: Int,
    val watchlist: Int,
    val history: Int,
    val watchedEpisodes: Int,
    val collections: Int,
    val pluginErrors: Int,
    val secretsRestored: Boolean,
)

@Singleton
class AppBackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: PlatformPrefs,
    private val library: LibraryRepository,
    private val debrid: DebridService,
    private val plugins: PluginRepository,
) {

    fun suggestedFilename(): String {
        val fmt = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)
        return "joabtv-backup-${fmt.format(Date())}.json"
    }

    suspend fun exportJson(includeSecrets: Boolean): String = withContext(Dispatchers.IO) {
        val profile = prefs.profileId.value
        val currentProfile = prefs.profiles.value.firstOrNull { it.id == prefs.profileId.value }
            ?: prefs.profiles.value.firstOrNull()

        val root = JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("exportedAt", System.currentTimeMillis())
            put("platform", "android")
            put("profile", JSONObject().apply {
                put("id", currentProfile?.id ?: "main")
                put("name", currentProfile?.name ?: "Principal")
            })
        }

        val addonsArr = JSONArray()
        prefs.addonEntries.forEach { a ->
            addonsArr.put(
                JSONObject()
                    .put("u", a.url)
                    .put("e", a.enabled)
                    .put("id", a.id)
            )
        }
        root.put("addons", addonsArr)

        val pluginState = runCatching {
            JSONObject(plugins.backup()).optJSONObject("state")
        }.getOrNull()
        root.put("plugins", pluginState ?: JSONObject.NULL)

        val wlArr = JSONArray()
        library.watchlist().first().forEach { w ->
            wlArr.put(
                JSONObject()
                    .put("tmdbId", w.tmdbId)
                    .put("type", toWebMediaType(w.type))
                    .put("title", w.title)
                    .put("posterUrl", w.posterUrl)
                    .put("backdropUrl", "")
                    .put("year", w.year)
                    .put("addedAt", w.addedAt)
            )
        }
        root.put("watchlist", wlArr)

        val histArr = JSONArray()
        library.recentlyPlayed().first().forEach { h ->
            histArr.put(
                JSONObject()
                    .put("tmdbId", h.tmdbId)
                    .put("type", toWebMediaType(h.type))
                    .put("title", h.title)
                    .put("posterUrl", h.posterUrl)
                    .put("backdropUrl", "")
                    .put("lastWatchedAt", h.lastWatchedAt)
                    .put("positionMs", h.positionMs)
                    .put("durationMs", h.durationMs)
                    .put("lastEpisode", h.lastEpisode)
                    .put("lastSourceUrl", h.lastSourceUrl)
                    .put("lastAddon", h.lastAddon)
            )
        }
        root.put("history", histArr)

        val watchedObj = JSONObject()
        library.allWatchedEpisodes().groupBy { it.seriesTmdbId }.forEach { (seriesId, eps) ->
            val arr = JSONArray()
            eps.forEach { arr.put("${it.season}:${it.episode}") }
            watchedObj.put(seriesId.toString(), arr)
        }
        root.put("watched", watchedObj)
        root.put("watchedMovies", JSONArray(library.watchedMovies().first().toList()))

        val searchesArr = JSONArray()
        prefs.recentSearches.forEach { searchesArr.put(it) }
        root.put("recentSearches", searchesArr)

        root.put("introMarks", prefs.exportIntroMarksJson())
        root.put("historyHidden", runCatching { JSONObject(prefs.historyHidden()) }.getOrElse { JSONObject() })
        root.put("collections", prefs.exportCollectionsJson())
        root.put("homeCatalogs", prefs.exportHomeCatalogsJson())
        root.put("homeLayout", prefs.exportHomeLayoutJson())

        val app = prefs.appearance
        root.put("prefs", JSONObject().apply {
            put("audio", prefs.audioPref)
            put("maxQuality", prefs.maxQuality)
            put("subSize", prefs.subSize.toDouble())
            put("subBg", prefs.subBg)
            put("subOn", prefs.subtitleOn)
            put("subLang", prefs.subLang)
            put("forcedSubs", prefs.forcedSubs)
            put("heroTrailer", prefs.heroTrailer)
            put("autoSwitch", prefs.autoSwitchSources)
            put("switchTimeout", prefs.sourceTimeoutSec)
            put("autoUpgrade", prefs.autoUpgradeQuality)
            put("debridCachedOnly", prefs.debridCachedOnly)
            put("motion", if (app.motion == InterfaceMotion.REDUCED) "reduced" else "full")
            put("theme", app.theme.key)
            put("menuStyle", app.menu.key)
            put("uiSize", app.uiSize.key)
        })

        if (includeSecrets) {
            val trackersObj = portableTrackers(mapOf("simkl" to prefs.simklToken, "mdblist" to prefs.mdblistKey,
                "wetrakr" to prefs.wetrakrKey, "publicmetadb" to prefs.publicmetadbKey), prefs.trackerSessions)
            val traktObj = portableTraktSession(prefs.traktSession)
            root.put("secrets", JSONObject().apply {
                put("tmdbKey", prefs.customTmdbApiKey)
                put("trakt", traktObj ?: JSONObject.NULL)
                put("trackers", trackersObj)
                put("debrid", debrid.exportBackupJson())
            })
        }

        check(prefs.profileId.value == profile) { "O perfil mudou. Exporte novamente" }
        root.toString(2)
    }

    fun inspect(text: String): Result<BackupPreview> = runCatching {
        require(text.isNotBlank()) { "Arquivo vazio." }
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES) { "Backup grande demais (limite de 8 MB)." }
        val root = JSONObject(text)
        require(root.optString("format") == FORMAT) { "Este arquivo não é um backup do JoabTV." }
        val version = root.optInt("version", 0)
        require(version in 1..VERSION) { "Backup de versão mais nova. Atualize o app." }

        val watchedObj = root.optJSONObject("watched")
        var watchedSeries = 0
        var watchedEpisodes = 0
        if (watchedObj != null) {
            val keys = watchedObj.keys()
            while (keys.hasNext()) {
                val arr = watchedObj.optJSONArray(keys.next())
                if (arr != null && arr.length() > 0) {
                    watchedSeries++
                    watchedEpisodes += arr.length()
                }
            }
        }

        val pluginRepos = root.optJSONObject("plugins")?.optJSONArray("repos")?.length() ?: 0
        val sec = root.optJSONObject("secrets")
        val hasSecrets = sec != null && (
            sec.optString("tmdbKey").isNotBlank() ||
                sec.optJSONObject("trakt")?.optString("access_token")?.isNotBlank() == true ||
                (sec.optJSONObject("debrid")?.optJSONArray("accounts")?.length() ?: 0) > 0 ||
                hasAnyTrackerKey(sec.optJSONObject("trackers"))
            )

        BackupPreview(
            platform = root.optString("platform"),
            profileName = root.optJSONObject("profile")?.optString("name")?.ifBlank { "Principal" } ?: "Principal",
            exportedAt = root.optLong("exportedAt", 0L),
            addons = root.optJSONArray("addons")?.length() ?: 0,
            pluginRepos = pluginRepos,
            watchlist = root.optJSONArray("watchlist")?.length() ?: 0,
            history = root.optJSONArray("history")?.length() ?: 0,
            watchedSeries = watchedSeries,
            watchedEpisodes = watchedEpisodes,
            recentSearches = root.optJSONArray("recentSearches")?.length() ?: 0,
            collections = root.optJSONArray("collections")?.length() ?: 0,
            hasSecrets = hasSecrets,
            rawText = text,
        )
    }

    suspend fun importJson(text: String, restoreSecrets: Boolean = true): Result<BackupSummary> =
        withContext(Dispatchers.IO) {
            runCatching {
                inspect(text).getOrThrow()
                val profile = prefs.profileId.value
                fun checkProfile() = check(prefs.profileId.value == profile) { "O perfil mudou. Restaure novamente no perfil desejado" }
                val root = JSONObject(text)
                root.optJSONObject("historyHidden")?.let { incoming ->
                    prefs.setHistoryHidden(com.joabtv.app.data.local.mergePlaybackVisibility(prefs.historyHidden(), incoming.toString()))
                }

                var addonsCount = 0
                root.optJSONArray("addons")?.let { arr ->
                    val list = (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        val u = o.optString("u").ifBlank { o.optString("url") }.trim()
                        if (!u.startsWith("http")) return@mapNotNull null
                        val enabled = if (o.has("e")) o.optBoolean("e", true) else o.optBoolean("enabled", true)
                        AddonEntry(u, enabled, o.optString("id"))
                    }
                    prefs.addonEntries = list
                    addonsCount = list.size
                }

                var wlCount = 0
                root.optJSONArray("watchlist")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        checkProfile()
                        val o = arr.optJSONObject(i) ?: continue
                        val id = o.optInt("tmdbId", 0)
                        if (id <= 0) continue
                        val type = toAndroidMediaType(o.optString("type"))
                        val added = library.importToList(
                            tmdbId = id,
                            type = type,
                            title = o.optString("title"),
                            posterUrl = o.optString("posterUrl"),
                            year = o.optString("year"),
                            addedAt = o.optLong("addedAt", System.currentTimeMillis()),
                            profileId = profile,
                        )
                        if (added) wlCount++
                    }
                }

                var histCount = 0
                root.optJSONArray("history")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        checkProfile()
                        val o = arr.optJSONObject(i) ?: continue
                        val id = o.optInt("tmdbId", 0)
                        if (id <= 0) continue
                        val type = toAndroidMediaType(o.optString("type"))
                        val updated = library.importPlayback(
                            tmdbId = id,
                            type = type,
                            title = o.optString("title"),
                            posterUrl = o.optString("posterUrl"),
                            positionMs = o.optLong("positionMs", 0L),
                            durationMs = o.optLong("durationMs", 0L),
                            lastEpisode = o.optString("lastEpisode"),
                            watchedAt = o.optLong("lastWatchedAt", System.currentTimeMillis()),
                            profileId = profile,
                            lastSourceUrl = o.optString("lastSourceUrl"),
                            lastAddon = o.optString("lastAddon"),
                        )
                        if (updated) histCount++
                    }
                }

                var epCount = 0
                root.optJSONArray("watchedMovies")?.let { movies ->
                    checkProfile()
                    val ids = (0 until movies.length()).mapNotNull { movies.optInt(it).takeIf { id -> id > 0 } }.toSet()
                    library.markMoviesWatched(ids, profile)
                }
                root.optJSONObject("watched")?.let { wObj ->
                    val keys = wObj.keys()
                    while (keys.hasNext()) {
                        val sid = keys.next().toIntOrNull() ?: continue
                        if (sid <= 0) continue
                        val eps = wObj.optJSONArray(sid.toString()) ?: continue
                        for (i in 0 until eps.length()) {
                            checkProfile()
                            val parts = eps.optString(i).split(":")
                            val season = parts.getOrNull(0)?.toIntOrNull() ?: continue
                            val episode = parts.getOrNull(1)?.toIntOrNull() ?: continue
                            if (season > 0 && episode > 0) {
                                library.markEpisodeWatched(sid, season, episode, profile)
                                epCount++
                            }
                        }
                    }
                }

                checkProfile()
                root.optJSONArray("recentSearches")?.let { arr ->
                    val incoming = (0 until arr.length()).mapNotNull { i ->
                        arr.optString(i).trim().takeIf { it.length >= 2 }
                    }
                    if (incoming.isNotEmpty()) {
                        val merged = (incoming + prefs.recentSearches)
                            .distinctBy { it.lowercase() }
                            .take(10)
                        prefs.recentSearches = merged
                    }
                }

                root.optJSONObject("introMarks")?.let { prefs.importIntroMarksJson(it) }

                var collectionsCount = 0
                root.optJSONArray("collections")?.let { arr ->
                    prefs.importCollectionsJson(arr)
                    collectionsCount = arr.length()
                }

                root.optJSONArray("homeCatalogs")?.let { prefs.importHomeCatalogsJson(it) }
                root.optJSONArray("homeLayout")?.let { prefs.importHomeLayoutJson(it) }

                root.optJSONObject("prefs")?.let { p ->
                    if (p.has("audio")) prefs.audioPref = p.optString("audio", prefs.audioPref)
                    if (p.has("maxQuality")) prefs.maxQuality = p.optInt("maxQuality", prefs.maxQuality)
                    if (p.has("subSize")) prefs.subSize = p.optDouble("subSize", prefs.subSize.toDouble()).toFloat()
                    if (p.has("subBg")) prefs.subBg = p.optBoolean("subBg", prefs.subBg)
                    if (p.has("subOn")) prefs.subtitleOn = p.optBoolean("subOn", prefs.subtitleOn)
                    if (p.has("forcedSubs")) prefs.forcedSubs = p.optBoolean("forcedSubs", prefs.forcedSubs)
                    if (p.has("subLang")) {
                        val lang = p.optString("subLang")
                        if (SubLangs.of(lang) != null) prefs.subLang = lang
                    }
                    if (p.has("heroTrailer")) prefs.heroTrailer = p.optBoolean("heroTrailer", prefs.heroTrailer)
                    if (p.has("autoSwitch")) prefs.autoSwitchSources = p.optBoolean("autoSwitch", prefs.autoSwitchSources)
                    if (p.has("switchTimeout")) prefs.sourceTimeoutSec = p.optInt("switchTimeout", prefs.sourceTimeoutSec)
                    if (p.has("autoUpgrade")) prefs.autoUpgradeQuality = p.optBoolean("autoUpgrade", prefs.autoUpgradeQuality)
                    if (p.has("debridCachedOnly")) prefs.debridCachedOnly = p.optBoolean("debridCachedOnly", prefs.debridCachedOnly)
                    if (p.has("motion")) {
                        val m = InterfaceMotion.fromKey(p.optString("motion"))
                        prefs.appearance = prefs.appearance.copy(motion = m)
                    }
                    if (p.has("theme")) prefs.appearance = prefs.appearance.copy(theme = com.joabtv.app.data.local.AppTheme.fromKey(p.optString("theme")))
                    if (p.has("menuStyle")) prefs.appearance = prefs.appearance.copy(menu = com.joabtv.app.data.local.MenuStyle.fromKey(p.optString("menuStyle")))
                    if (p.has("uiSize")) prefs.appearance = prefs.appearance.copy(uiSize = com.joabtv.app.data.local.InterfaceSize.fromKey(p.optString("uiSize")))
                }

                var secretsRestored = false
                if (restoreSecrets) {
                    root.optJSONObject("secrets")?.let { sec ->
                        val tmdb = sec.optString("tmdbKey").trim()
                        if (tmdb.isNotEmpty()) prefs.customTmdbApiKey = tmdb
                        sec.optJSONObject("trakt")?.let { tr ->
                            androidTraktSession(tr)?.let { prefs.traktSession = it }
                        }
                        sec.optJSONObject("trackers")?.let { trk ->
                            backupTrackerToken(trk.optJSONObject("simkl")).takeIf { it.isNotBlank() }?.let { prefs.simklToken = it }
                            backupTrackerToken(trk.optJSONObject("mdblist")).takeIf { it.isNotBlank() }?.let { prefs.mdblistKey = it }
                            backupTrackerToken(trk.optJSONObject("wetrakr")).takeIf { it.isNotBlank() }?.let { prefs.wetrakrKey = it }
                            backupTrackerToken(trk.optJSONObject("publicmetadb")).takeIf { it.isNotBlank() }?.let { prefs.publicmetadbKey = it }
                            prefs.trackerSessions = androidTrackerSessions(trk, prefs.trackerSessions)
                        }
                        sec.optJSONObject("debrid")?.let { deb -> debrid.importBackupJson(deb) }
                        secretsRestored = true
                    }
                }

                var pluginErrors = 0
                val pluginState = root.optJSONObject("plugins")
                if (pluginState != null && pluginState.optJSONArray("repos") != null) {
                    val wrapped = JSONObject()
                        .put("format", "jotv.plugins")
                        .put("version", 1)
                        .put("state", pluginState)
                    pluginErrors = runCatching { plugins.restore(wrapped.toString()) }.getOrDefault(0)
                }

                BackupSummary(
                    addons = addonsCount,
                    watchlist = wlCount,
                    history = histCount,
                    watchedEpisodes = epCount,
                    collections = collectionsCount,
                    pluginErrors = pluginErrors,
                    secretsRestored = secretsRestored,
                )
            }
        }

    suspend fun saveToDownloads(filename: String, content: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val safeName = filename.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                        put(MediaStore.Downloads.MIME_TYPE, "application/json")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: error("Não foi possível criar arquivo em Downloads.")
                    resolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                        ?: error("Falha ao gravar arquivo em Downloads.")
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    "Downloads/$safeName"
                } else {
                    @Suppress("DEPRECATION")
                    val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    require(publicDir != null && (publicDir.exists() || publicDir.mkdirs()) && publicDir.canWrite()) {
                        "Permita salvar em Downloads ou use o backup pelo Wi-Fi"
                    }
                    val dir = publicDir
                    val file = File(dir, safeName)
                    file.writeText(content, Charsets.UTF_8)
                    file.absolutePath
                }
            }
        }

    private fun hasAnyTrackerKey(obj: JSONObject?): Boolean {
        if (obj == null) return false
        val keys = obj.keys()
        while (keys.hasNext()) {
            val child = obj.optJSONObject(keys.next()) ?: continue
            if (backupTrackerToken(child).isNotBlank()) return true
        }
        return false
    }

    private fun toWebMediaType(type: String): String =
        if (type.equals("SERIES", ignoreCase = true) || type.equals("tv", ignoreCase = true)) "series" else "movie"

    private fun toAndroidMediaType(type: String): String =
        if (type.equals("series", ignoreCase = true) || type.equals("tv", ignoreCase = true)) "SERIES" else "MOVIE"

    companion object {
        const val FORMAT = "jotv.backup"
        const val VERSION = 1
        const val MAX_JSON_BYTES = 8 * 1024 * 1024
    }
}
