package com.joabtv.app.data.accounts

import com.joabtv.app.data.local.PlatformPrefs
import com.joabtv.app.data.remote.StremioAddonService
import com.joabtv.app.data.remote.TmdbApiService
import com.joabtv.app.data.remote.dto.toMovieItem
import com.joabtv.app.data.repository.LibraryRepository
import com.joabtv.app.domain.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Addon de outra conta. [canUse] diz se ele serve no JOTV (fonte ou catálogo, fora do aparelho). */
data class RemoteAddon(val url: String, val name: String, val canUse: Boolean)

/** Item da biblioteca de outra conta. [id] é o id do Stremio ("tt...", "tmdb:..."). */
data class RemoteLibraryItem(val id: String, val series: Boolean, val name: String, val poster: String, val addedAt: Long)

/** Progresso de outra conta. Tempos em ms. [season] e [episode] 0 em filme. */
data class RemoteProgress(
    val id: String, val series: Boolean, val name: String, val poster: String,
    val season: Int, val episode: Int, val positionMs: Long, val durationMs: Long, val watchedAt: Long,
)

data class ImportReport(val added: Int, val skipped: Int, val failed: Int) {
    val total get() = added + skipped + failed
}

/**
 * Traz para o JOTV o que veio de outra conta. Addon entra no fim da fila, título vira o id do
 * TMDB (o app inteiro usa esse id) e progresso só entra quando é mais novo que o do aparelho.
 */
@Singleton
class AccountImporter @Inject constructor(
    private val prefs: PlatformPrefs,
    private val tmdb: TmdbApiService,
    private val library: LibraryRepository,
    private val stremio: StremioAddonService,
    private val syncIds: com.joabtv.app.data.sync.SyncIds,
) {
    private val ids = ConcurrentHashMap<String, Pair<Int, com.joabtv.app.domain.model.MovieItem?>>()

    /** [profile] é o perfil de destino (o ativo por padrão). Vale para todas as importações daqui. */
    suspend fun importAddons(
        list: List<RemoteAddon>, profile: String = prefs.profileId.value, onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): ImportReport = withContext(Dispatchers.IO) {
        var added = 0; var skipped = 0; var failed = 0
        list.forEachIndexed { i, a ->
            onProgress(i + 1, list.size)
            val url = stremio.normalizeManifestUrl(a.url)
            when {
                !a.canUse || url.isBlank() -> skipped++
                prefs.addonIndex(url, profileId = profile) >= 0 -> skipped++
                else -> {
                    // Lê o manifest antes, que é o que guarda os catálogos para a Início do perfil de destino.
                    val m = stremio.getManifest(url)
                    if (m == null) failed++ else {
                        prefs.cacheManifest(url, m.name, m.catalogs, m.id, profile)
                        prefs.installAddon(url, m.id, profile)
                        added++
                    }
                }
            }
        }
        ImportReport(added, skipped, failed)
    }

    suspend fun importLibrary(
        items: List<RemoteLibraryItem>, profile: String = prefs.profileId.value, onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): ImportReport {
        val done = java.util.concurrent.atomic.AtomicInteger()
        val results = parallel(items) { it ->
            val (tmdbId, found) = resolve(it.id, it.series) ?: return@parallel 2.also { onProgress(done.incrementAndGet(), items.size) }
            val meta = found ?: if (it.name.isBlank() || it.poster.isBlank()) details(tmdbId, it.series) else null
            val ok = library.importToList(
                tmdbId, if (it.series) MediaType.SERIES.name else MediaType.MOVIE.name,
                it.name.ifBlank { meta?.title.orEmpty() }, meta?.posterUrl?.ifBlank { null } ?: it.poster, meta?.year.orEmpty(),
                it.addedAt, profile,
            )
            onProgress(done.incrementAndGet(), items.size)
            if (ok) 0 else 1
        }
        return ImportReport(results.count { it == 0 }, results.count { it == 1 }, results.count { it == 2 })
    }

    suspend fun importProgress(
        items: List<RemoteProgress>, profile: String = prefs.profileId.value, onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): ImportReport {
        // Só o mais recente de cada título (série tem um por episódio).
        val latest = items.filter { it.positionMs > 0 }
            .groupBy { baseId(it.id) + it.series }
            .map { (_, g) -> g.maxBy { it.watchedAt } }
            .sortedByDescending { it.watchedAt }
            .take(60)
        val done = java.util.concurrent.atomic.AtomicInteger()
        val results = parallel(latest) { p ->
            val (tmdbId, found) = resolve(baseId(p.id), p.series)
                ?: return@parallel 2.also { onProgress(done.incrementAndGet(), latest.size) }
            // O progresso do Nuvio às vezes chega sem nome e sem pôster (id tmdb:). Sem isso o
            // Continuar assistindo ganhava cartão preto.
            val meta = found ?: if (p.name.isBlank() || p.poster.isBlank()) details(tmdbId, p.series) else null
            val ok = library.importPlayback(
                tmdbId, if (p.series) MediaType.SERIES.name else MediaType.MOVIE.name,
                p.name.ifBlank { meta?.title.orEmpty() }, meta?.posterUrl?.ifBlank { null } ?: p.poster,
                p.positionMs, p.durationMs, if (p.series && p.season > 0) "T${p.season}:E${p.episode}" else "",
                p.watchedAt, profile,
            )
            onProgress(done.incrementAndGet(), latest.size)
            if (ok) 0 else 1
        }
        return ImportReport(results.count { it == 0 }, results.count { it == 1 }, results.count { it == 2 })
    }

    /** Nome, pôster e ano pelo id do TMDB, para item que chegou da conta sem eles. */
    private suspend fun details(id: Int, series: Boolean): com.joabtv.app.domain.model.MovieItem? {
        val apiKey = prefs.tmdbApiKey.ifBlank { return null }
        return runCatching {
            if (series) tmdb.getSeriesDetail(id, append = "", apiKey = apiKey).let { d ->
                com.joabtv.app.domain.model.MovieItem(
                    tmdbId = d.id, embedId = d.id.toString(), title = d.name, overview = d.overview.orEmpty(),
                    posterUrl = d.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }.orEmpty(), backdropUrl = "",
                    year = d.firstAirDate.orEmpty().take(4), rating = d.voteAverage, type = MediaType.SERIES,
                )
            } else tmdb.getMovieDetail(id, append = "", apiKey = apiKey).toMovieItem()
        }.getOrNull()
    }

    /** Id do título sem temporada e episódio ("tt1:2:3" vira "tt1", "tmdb:5:1:2" vira "tmdb:5"). */
    private fun baseId(id: String) = if (id.startsWith("tmdb:")) id.split(':').take(2).joinToString(":") else id.substringBefore(':')

    /** Id do Stremio para o do TMDB. Aceita "tt123" e "tmdb:123". null se não achar. */
    private suspend fun resolve(id: String, series: Boolean): Pair<Int, com.joabtv.app.domain.model.MovieItem?>? {
        val key = "$series|$id"
        ids[key]?.let { return it }
        // O mapa do aparelho vale entre uma abertura do app e a outra (a busca no TMDB só roda na primeira vez).
        syncIds.tmdbFor(id, series)?.let { n -> return (n to null).also { r -> ids[key] = r; syncIds.remember(id, series, n) } }
        val tt = Regex("^tt\\d+").find(id)?.value ?: return null
        val apiKey = prefs.tmdbApiKey.ifBlank { return null }
        val r = runCatching { tmdb.findByImdb(tt, apiKey = apiKey) }.getOrNull() ?: return null
        val hit = if (series) r.tvResults.firstOrNull() ?: r.movieResults.firstOrNull() else r.movieResults.firstOrNull() ?: r.tvResults.firstOrNull()
        val item = hit?.toMovieItem() ?: return null
        return (item.tmdbId to item).also { ids[key] = it; syncIds.remember(id, series, item.tmdbId) }
    }

    /**
     * Puxada do sync. Igual a [importProgress], mas sem rede quando já se sabe o id do título e o aparelho já tem o
     * mesmo progresso ou um mais novo (uma sincronia a cada minuto não pode virar 60 buscas no TMDB). O resultado
     * conta 0 para gravado, 1 para ignorado (já estava em dia) e 2 para título sem par.
     */
    suspend fun importProgressSync(items: List<RemoteProgress>, profile: String): ImportReport {
        val latest = items.filter { it.positionMs > 0 }
            .groupBy { baseId(it.id) + it.series }
            .map { (_, g) -> g.maxBy { it.watchedAt } }
            .sortedByDescending { it.watchedAt }
            .take(60)
        val budget = java.util.concurrent.atomic.AtomicInteger(com.joabtv.app.data.sync.SyncPlan.LOOKUPS_PER_RUN)
        val results = parallel(latest) { p ->
            val type = if (p.series) MediaType.SERIES.name else MediaType.MOVIE.name
            val known = syncIds.tmdbFor(baseId(p.id), p.series)
            if (known != null) {
                val local = library.getPlayback(known, type, profile)
                if (local != null && local.lastWatchedAt >= p.watchedAt) return@parallel 1
                if (p.watchedAt <= com.joabtv.app.data.local.playbackHiddenBefore(prefs.historyHidden(profile), com.joabtv.app.data.local.playbackKey(known, type))) return@parallel 1
            } else if (budget.decrementAndGet() < 0) return@parallel 1
            val (tmdbId, found) = resolve(baseId(p.id), p.series) ?: return@parallel 2
            val prev = library.getPlayback(tmdbId, type, profile)
            val meta = found ?: if ((prev == null || prev.title.isBlank() || prev.posterUrl.isBlank()) && (p.name.isBlank() || p.poster.isBlank())) details(tmdbId, p.series) else null
            val ok = library.importPlayback(
                tmdbId, type, p.name.ifBlank { meta?.title.orEmpty() }, meta?.posterUrl?.ifBlank { null } ?: p.poster,
                p.positionMs, p.durationMs, if (p.series && p.season > 0) "T${p.season}:E${p.episode}" else "",
                p.watchedAt, profile,
            )
            if (ok) 0 else 1
        }
        return ImportReport(results.count { it == 0 }, results.count { it == 1 }, results.count { it == 2 })
    }

    /**
     * Itens vistos de outra conta. Episódio entra como visto se ainda não estava. Filme visto vira concluído quando o
     * aparelho não tem nada mais novo. Só busca no TMDB o que ainda não tem par no mapa, até o orçamento da rodada
     * (os mais recentes primeiro). O resto entra nas próximas.
     */
    suspend fun importSeen(items: List<com.joabtv.app.data.sync.RemoteSeen>, profile: String): ImportReport {
        val sync = com.joabtv.app.data.sync.SyncPlan
        val groups = items.groupBy { it.series to sync.baseId(it.contentId) }.entries
            .sortedByDescending { e -> e.value.maxOf { it.at } }
        val mine = library.watchedRows(profile).map { "${it.seriesTmdbId}|${it.season}|${it.episode}" }.toHashSet()
        val budget = java.util.concurrent.atomic.AtomicInteger(sync.LOOKUPS_PER_RUN)
        val results = parallel(groups) { (key, list) ->
            val (series, cid) = key
            val known = syncIds.tmdbFor(cid, series)
            val tmdbId: Int
            var found: com.joabtv.app.domain.model.MovieItem? = null
            if (known != null) tmdbId = known else {
                if (budget.decrementAndGet() < 0) return@parallel 1
                val r = resolve(cid, series) ?: return@parallel 2
                tmdbId = r.first
                found = r.second
            }
            if (series) {
                val fresh = list.filter { it.season > 0 && it.episode > 0 && "$tmdbId|${it.season}|${it.episode}" !in mine }
                val todo = fresh.map { it.season to it.episode }.distinct()
                if (todo.isEmpty()) return@parallel 1
                // Cada episódio entra com a hora que a conta registrou. Com a hora da importação, desmarcar lá logo depois não chegaria aqui.
                library.markEpisodesWatched(tmdbId, todo, profile, silent = true, times = fresh.associate { (it.season to it.episode) to sync.clampRemote(it.at) })
                0
            } else {
                val type = MediaType.MOVIE.name
                val at = sync.clampRemote(list.maxOf { it.at })
                val local = library.getPlayback(tmdbId, type, profile)
                if (local != null && (local.lastWatchedAt >= at || sync.finished(local.positionMs, local.durationMs))) return@parallel 1
                if (at <= com.joabtv.app.data.local.playbackHiddenBefore(prefs.historyHidden(profile), com.joabtv.app.data.local.playbackKey(tmdbId, type))) return@parallel 1
                val meta = found ?: if (local == null || local.title.isBlank()) details(tmdbId, false) else null
                val d = local?.durationMs?.takeIf { it > 0 } ?: 1L
                val ok = library.importPlayback(
                    tmdbId, type, list.firstOrNull { it.title.isNotBlank() }?.title ?: meta?.title.orEmpty(),
                    meta?.posterUrl.orEmpty(), d, d, "", at, profile,
                )
                if (ok) 0 else 1
            }
        }
        return ImportReport(results.count { it == 0 }, results.count { it == 1 }, results.count { it == 2 })
    }

    private suspend fun <T> parallel(items: List<T>, block: suspend (T) -> Int): List<Int> = coroutineScope {
        val gate = Semaphore(6)
        items.map { async(Dispatchers.IO) { gate.withPermit { runCatching { block(it) }.getOrDefault(2) } } }.awaitAll()
    }
}
