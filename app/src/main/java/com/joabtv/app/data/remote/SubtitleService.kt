package com.joabtv.app.data.remote

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Legenda externa (arquivo .srt) de um filme/episódio. */
data class ExternalSub(val url: String, val lang: String, val label: String)

private data class SubResponse(val subtitles: List<SubDto> = emptyList())
private data class SubDto(
    @SerializedName("url") val url: String? = null,
    @SerializedName("lang") val lang: String? = null,
)

/**
 * Legendas externas via addon Stremio OpenSubtitles v3 (sem API key). Prioriza Português.
 * Desativada por padrão — o player só carrega quando o usuário escolhe uma legenda.
 */
@Singleton
class SubtitleService @Inject constructor(
    private val prefs: com.joabtv.app.data.local.PlatformPrefs,
    private val stremio: StremioAddonService,
) {
    private val client = OkHttpClient.Builder()
        .dns(AppDns.cloudflare)
        .callTimeout(15, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    private val langLabel = mapOf(
        "pob" to "Português (BR)", "por" to "Português", "eng" to "Inglês",
        "spa" to "Espanhol", "spl" to "Espanhol (LA)", "fre" to "Francês",
        "ita" to "Italiano", "ger" to "Alemão", "jpn" to "Japonês",
    )
    // Ordem de preferência (PT no topo).
    private val langRank = mapOf("pob" to 0, "por" to 1, "eng" to 2, "spa" to 3, "spl" to 4)

    /** Texto do arquivo de legenda, ou null se não baixou. */
    suspend fun download(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull()
    }

    /** OpenSubtitles mais as legendas dos addons instalados que têm o recurso subtitles. */
    suspend fun subtitles(imdb: String, type: String, season: Int, episode: Int): List<ExternalSub> =
        kotlinx.coroutines.coroutineScope {
            val os = async { openSubtitles(imdb, type, season, episode) }
            val addons = async { addonSubtitles(imdb, type, season, episode) }
            (os.await() + addons.await()).distinctBy { it.url }
        }

    // Addon -> nome, ou null quando o manifest não tem o recurso subtitles. Lido uma vez só.
    private val subAddons = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<String>>()

    private suspend fun addonSubtitles(imdb: String, type: String, season: Int, episode: Int): List<ExternalSub> =
        kotlinx.coroutines.coroutineScope {
            if (!imdb.startsWith("tt")) return@coroutineScope emptyList()
            val t = if (type == "SERIES") "series" else "movie"
            val id = if (type == "SERIES" && season > 0 && episode > 0) "$imdb:$season:$episode" else imdb
            prefs.stremioAddonList().map { u ->
                async(Dispatchers.IO) {
                    // Manifest que não respondeu não entra no cache, tenta de novo na próxima.
                    val name = (subAddons[u] ?: stremio.getManifest(u)?.let { m ->
                        java.util.Optional.ofNullable(m.takeIf { "subtitles" in it.resources }?.name).also { subAddons[u] = it }
                    })?.orElse(null) ?: return@async emptyList()
                    val base = u.trim().removeSuffix("/manifest.json").removeSuffix("/")
                    val body = runCatching {
                        client.newCall(Request.Builder().url(stremio.normalizeManifestUrl(base).removeSuffix("manifest.json") + "subtitles/$t/$id.json").build())
                            .execute().use { if (it.isSuccessful) it.body?.string() else null }
                    }.getOrNull() ?: return@async emptyList()
                    val resp = runCatching { gson.fromJson(body, SubResponse::class.java) }.getOrNull()
                    resp?.subtitles.orEmpty().mapNotNull { s ->
                        val url = s.url?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
                        val lang = s.lang.orEmpty().lowercase()
                        ExternalSub(url, lang, (langLabel[lang] ?: lang.uppercase().ifBlank { "Legenda" }) + " · " + name)
                    }
                }
            }.awaitAll().flatten().sortedBy { langRank[it.lang] ?: 99 }.take(12)
        }

    private suspend fun openSubtitles(imdb: String, type: String, season: Int, episode: Int): List<ExternalSub> =
        withContext(Dispatchers.IO) {
            if (!imdb.startsWith("tt")) return@withContext emptyList()
            val t = if (type == "SERIES") "series" else "movie"
            val id = if (type == "SERIES" && season > 0 && episode > 0) "$imdb:$season:$episode" else imdb
            val url = "https://opensubtitles-v3.strem.io/subtitles/$t/$id.json"
            val body = runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use {
                    if (it.isSuccessful) it.body?.string() else null
                }
            }.getOrNull() ?: return@withContext emptyList()

            val resp = runCatching { gson.fromJson(body, SubResponse::class.java) }.getOrNull()
                ?: return@withContext emptyList()

            resp.subtitles
                .mapNotNull { s ->
                    val u = s.url?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
                    val lang = s.lang ?: return@mapNotNull null
                    ExternalSub(u, lang, langLabel[lang] ?: lang.uppercase())
                }
                // 1 por idioma (a 1ª costuma ser a melhor), PT primeiro.
                .distinctBy { it.lang }
                .sortedBy { langRank[it.lang] ?: 99 }
                .take(8)
        }
}
