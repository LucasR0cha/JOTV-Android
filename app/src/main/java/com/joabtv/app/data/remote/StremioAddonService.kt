package com.joabtv.app.data.remote

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Metadados do manifest de um addon (p/ exibir na tela de gerenciamento). */
data class AddonManifest(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val logo: String = "",
    val version: String = "",
    val types: List<String> = emptyList(),
    val resources: List<String> = emptyList(),
    /** Catálogos que podem virar fileira na Início (filme e série sem filtro obrigatório). */
    val catalogs: List<AddonCatalog> = emptyList(),
)

/** Item de um catálogo de addon, ainda com o id do Stremio (tt... ou tmdb:...). */
data class AddonMeta(
    val id: String,
    val name: String,
    val poster: String,
    val background: String,
    val year: String,
    val description: String,
    val rating: Double,
)

/** Stream HTTP resolvido de um addon Stremio (toca direto no ExoPlayer). */
data class StremioStream(
    val label: String,
    val url: String,
    val addon: String = "",
    val qualityRank: Int = 0, // 2160=4K, 1080, 720… p/ ordenar (maior primeiro)
)

private data class StremioStreamDto(
    @SerializedName("name") val name: String? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("url") val url: String? = null,
    @SerializedName("infoHash") val infoHash: String? = null,
    @SerializedName("fileIdx") val fileIdx: Int? = null,
    @SerializedName("sources") val sources: List<String>? = null,
)

private data class StremioStreamResponse(
    @SerializedName("streams") val streams: List<StremioStreamDto> = emptyList(),
)

/**
 * Cliente de addon Stremio. Lê {base}/stream/{type}/{id}.json e devolve as fontes.
 * Filme: id = imdbId. Série: id = "imdbId:temporada:episodio".
 */
@Singleton
class StremioAddonService @Inject constructor() {

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .dns(AppDns.cloudflare)
        // FenixFlix migrou p/ AWS (rápido) — sem mais cold start de 75s. Timeouts curtos = addons
        // carregam rápido e um addon lento/morto não segura os outros.
        .callTimeout(15, TimeUnit.SECONDS)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    // UA de BROWSER: FrostStream (WAF Cloudflare) BLOQUEIA qualquer UA com "Stremio"
                    // (retorna reset/000) → não puxava nenhum filme. Chrome passa (200).
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36")
                    .header("Accept", "application/json")
                    .build()
            )
        }
        .build()

    /** Normaliza o link colado. Regra em [AddonLinks.normalizeManifestUrl]. */
    fun normalizeManifestUrl(input: String): String = AddonLinks.normalizeManifestUrl(input)

    private fun encodePath(url: String) = AddonLinks.encodePath(url)

    /** Nome do addon por base URL (preenchido ao ler o manifest) — usado p/ rotular as fontes. */
    private val addonNames = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun baseOf(manifestOrBase: String) = manifestOrBase.trim()
        .removeSuffix("/manifest.json").removeSuffix("/")

    /** Baixa e lê o manifest do addon. null se não responder ou não for um manifest válido. */
    suspend fun getManifest(manifestUrl: String): AddonManifest? = withContext(Dispatchers.IO) {
        val url = normalizeManifestUrl(manifestUrl)
        if (url.isBlank()) return@withContext null
        val body = runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use {
                if (it.isSuccessful) it.body?.string() else null
            }
        }.getOrNull() ?: return@withContext null
        runCatching {
            val o = org.json.JSONObject(body)
            val json = com.google.gson.JsonParser.parseString(body).asJsonObject
            // "resources" aceita string ou objeto. Antes o objeto virava JSON cru no selo.
            val res = AddonLinks.resourcesOf(json)
            val types = o.optJSONArray("types")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
            }.orEmpty()
            val id = o.optString("id")
            // Sem id ou sem resources não é manifest de addon de verdade (ex.: addon com config
            // errada que devolve {"error":"..."} com HTTP 200).
            if (id.isBlank() || res.isEmpty()) return@runCatching null
            AddonManifest(
                id = id,
                name = o.optString("name").ifBlank { hostOf(url) },
                description = o.optString("description"),
                logo = o.optString("logo").ifBlank { o.optString("icon") },
                version = o.optString("version"),
                types = types,
                resources = res,
                catalogs = AddonLinks.homeCatalogs(json),
            ).also { m -> if (m.name.isNotBlank()) addonNames[baseOf(url)] = m.name }
        }.getOrNull()
    }

    /** Busca streams de UM addon. baseOrManifest pode ser a URL do manifest ou a base. */
    suspend fun getStreams(
        baseOrManifest: String,
        type: String,           // "movie" | "series"
        imdbId: String,
        season: Int? = null,
        episode: Int? = null,
        /** TMDB id do título. Usado como 2ª tentativa quando o addon não indexa por IMDB. */
        tmdbId: Int = 0,
    ): List<StremioStream> = withContext(Dispatchers.IO) { coroutineScope {
        // Aceita "tt..." (IMDB) e "tmdb:{id}" (TMDB).
        if (imdbId.isBlank() && tmdbId <= 0) return@coroutineScope emptyList()
        val base = baseOrManifest.trim()
            .removeSuffix("/manifest.json")
            .removeSuffix("/")
        // Nome REAL do addon (do manifest). Se ainda não leu, lê AGORA em paralelo com os
        // streams, então não custa tempo nenhum. Sem isso a lista mostrava o host cru, e no
        // BestCine (configuração embutida na URL) mostrava a URL inteira como nome.
        val nomeD = async {
            addonNames[base] ?: getManifest("$base/manifest.json")?.name?.takeIf { it.isNotBlank() }
        }

        fun idFor(root: String) =
            if (type == "series" && season != null && episode != null) "$root:$season:$episode" else root

        // Catálogo de addon varia: FrostStream só responde a "tt", UnioFlix devolve MAIS
        // streams por "tmdb:" do que por IMDB, e tem addon que só conhece um dos dois.
        // Tenta IMDB primeiro e, sem resultado, repete com o id do TMDB.
        val candidates = buildList {
            if (imdbId.isNotBlank()) add(idFor(imdbId))
            if (tmdbId > 0) add(idFor("tmdb:$tmdbId"))
        }

        var streams: List<StremioStreamDto> = emptyList()
        for (id in candidates) {
            val body = fetchStreams(encodePath("$base/stream/$type/$id.json")) ?: continue
            val resp = runCatching { gson.fromJson(body, StremioStreamResponse::class.java) }.getOrNull()
            if (!resp?.streams.isNullOrEmpty()) { streams = resp!!.streams; break }
        }
        if (streams.isEmpty()) return@coroutineScope emptyList()

        val addonName = nomeD.await() ?: hostOf(base)
        streams.mapNotNull { s ->
            val raw = (s.title ?: s.name ?: "Stream")
            val label = raw.replace("\n", " · ").take(80)
            // Resolução costuma vir no "name" ("FrostStream 1080p") e o resto no "title" —
            // rankeia pelos DOIS, senão qualityRank fica 0.
            val rank = qualityRank("${s.name.orEmpty()} $raw")
            // Só URL HTTP direta (debrid já resolvido / hoster http). Magnet/torrent
            // foi removido — exigia debrid (pago) ou P2P instável em TV box.
            if (!s.url.isNullOrBlank() && s.url.startsWith("http"))
                StremioStream(label, s.url, addon = addonName, qualityRank = rank)
            else null
        }.sortedByDescending { it.qualityRank } // 4K primeiro, depois 1080p, 720p…
    } }

    private fun hostOf(url: String): String = AddonLinks.hostOf(url)

    /**
     * GET do endpoint de stream. UMA repetição quando o host devolve 502/503/504, que é o
     * cold start de hospedagem grátis (UnioFlix no baby-beamup responde 504 na 1ª chamada e
     * entrega normal na 2ª). Erro de rede ou 404 NÃO repete, pra addon morto não segurar a lista.
     */
    private fun fetchStreams(url: String): String? {
        repeat(2) { attempt ->
            val r = runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use {
                    if (it.isSuccessful) it.body?.string() to it.code else null to it.code
                }
            }.getOrNull() ?: return null
            val (body, code) = r
            if (body != null) return body
            val coldStart = code == 502 || code == 503 || code == 504
            if (!coldStart || attempt == 1) return null
            Thread.sleep(2_500)
        }
        return null
    }

    /** Extrai um rank numérico de qualidade do label (4K=2160 > 1080 > 720 > 0). */
    private fun qualityRank(text: String): Int {
        val t = text.lowercase()
        return when {
            "2160" in t || "4k" in t || "uhd" in t -> 2160
            "1440" in t || "2k" in t -> 1440
            "1080" in t || "fhd" in t -> 1080
            "720" in t || "hd" in t -> 720
            "480" in t -> 480
            else -> 0
        }
    }

    /** Busca em vários addons EM PARALELO — um addon morto/lento não bloqueia os outros. */
    suspend fun getStreamsFrom(
        addons: List<String>,
        type: String,
        imdbId: String,
        season: Int? = null,
        episode: Int? = null,
        tmdbId: Int = 0,
    ): List<StremioStream> = coroutineScope {
        addons.map { it.trim() }.filter { it.isNotBlank() }
            .map { a ->
                async {
                    runCatching { getStreams(a, type, imdbId, season, episode, tmdbId) }.getOrDefault(emptyList())
                }
            }
            .awaitAll()
            .flatten()
    }

    /**
     * Igual [getStreamsFrom], mas ENTREGA CONFORME CHEGA. Cada addon que responde já dispara
     * [onPartial] com tudo que existe até ali, ordenado por qualidade. Quem chama pode tocar a
     * primeira fonte sem esperar o addon mais lento (ou morto) da lista.
     *
     * Roda no dispatcher de quem chamou (getStreams faz o próprio withContext(IO)), então na
     * UI o [onPartial] cai na Main e pode escrever estado do Compose direto.
     */
    suspend fun streamsProgressive(
        addons: List<String>,
        type: String,
        imdbId: String,
        season: Int? = null,
        episode: Int? = null,
        tmdbId: Int = 0,
        onPartial: (List<StremioStream>) -> Unit,
    ) = coroutineScope {
        val list = addons.map { it.trim() }.filter { it.isNotBlank() }
        if (list.isEmpty()) { onPartial(emptyList()); return@coroutineScope }
        val slots = arrayOfNulls<List<StremioStream>>(list.size)
        list.mapIndexed { i, a ->
            launch {
                slots[i] = runCatching { getStreams(a, type, imdbId, season, episode, tmdbId) }.getOrDefault(emptyList())
                // Ordem = a do GERENCIADOR DE ADDONS (slots seguem a lista do usuário) e, dentro
                // de cada addon, a maior qualidade primeiro. Antes ordenava só por qualidade e
                // a prioridade que a pessoa montou não valia de nada.
                onPartial(slots.filterNotNull().flatMap { it.sortedByDescending { s -> s.qualityRank } })
            }
        }.joinAll()
    }

    // ── Catálogo da comunidade (stremio-addons.net) ──────────────────────────
    // O site é ele mesmo um addon com o recurso addon_catalog. A lista muda todo dia, então é
    // lida ao vivo com cache curto de 10 minutos.
    private var communityCache: Pair<Long, List<CommunityAddon>>? = null

    suspend fun communityCatalog(force: Boolean = false): List<CommunityAddon>? = withContext(Dispatchers.IO) {
        communityCache?.let { (at, list) ->
            if (!force && System.currentTimeMillis() - at < 10 * 60_000) return@withContext list
        }
        val body = runCatching {
            client.newBuilder().callTimeout(40, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
                .newCall(Request.Builder().url(COMMUNITY_CATALOG).build()).execute()
                .use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull() ?: return@withContext null
        AddonLinks.parseCommunity(body).takeIf { it.isNotEmpty() }
            ?.also { communityCache = System.currentTimeMillis() to it }
    }

    /**
     * Uma página de um catálogo do addon. Lê {base}/catalog/{type}/{id}[/skip=N].json.
     * Devolve null quando o addon não respondeu.
     */
    suspend fun catalogMetas(base: String, type: String, id: String, skip: Int): List<AddonMeta>? = withContext(Dispatchers.IO) {
        val path = "${AddonLinks.baseOf(base)}/catalog/$type/${AddonLinks.encodeURIComponent(id)}" +
            (if (skip > 0) "/skip=$skip" else "") + ".json"
        val body = runCatching {
            client.newCall(Request.Builder().url(encodePath(path)).build()).execute()
                .use { if (it.isSuccessful) it.body?.string() else null }
        }.getOrNull() ?: return@withContext null
        runCatching {
            val metas = com.google.gson.JsonParser.parseString(body).asJsonObject.getAsJsonArray("metas")
            metas.mapNotNull { el ->
                val m = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                fun str(k: String) = m.get(k)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                AddonMeta(
                    id = str("id"),
                    name = str("name"),
                    poster = str("poster").takeIf { it.startsWith("http", true) }.orEmpty(),
                    background = str("background").takeIf { it.startsWith("http", true) }.orEmpty(),
                    year = str("releaseInfo").ifBlank { str("year") }.take(4),
                    description = str("description"),
                    rating = str("imdbRating").toDoubleOrNull() ?: 0.0,
                )
            }
        }.getOrNull()
    }

    private companion object {
        const val COMMUNITY_CATALOG = "https://stremio-addons.net/api/addon_catalog/all/stremio-addons.net.json"
    }
}
