package com.joabtv.app.data.sync

import com.joabtv.app.data.local.PlatformPrefs
import com.joabtv.app.data.remote.TmdbApiService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ponte entre os ids do TMDB (que o app usa) e os ids dos outros serviços (IMDb "tt...", "tmdb:N").
 * O mapa fica guardado no aparelho, então depois da primeira vez quase nada vai à rede.
 */
@Singleton
class SyncIds @Inject constructor(
    private val store: SyncStore,
    private val tmdb: TmdbApiService,
    private val prefs: PlatformPrefs,
) {
    /** Id do TMDB sem rede. null enquanto não se sabe. */
    fun tmdbFor(contentId: String, series: Boolean): Int? {
        Regex("^tmdb:(\\d+)").find(contentId)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return store.idToTmdb(series, SyncPlan.baseId(contentId))
    }

    fun remember(contentId: String, series: Boolean, tmdbId: Int) = store.putId(series, SyncPlan.baseId(contentId), tmdbId)

    /**
     * Id que o outro serviço entende para um título. O que ele mesmo já usou, depois o IMDb do TMDB, depois tmdb:N.
     * Só guarda no mapa quando achou IMDb (tmdb:N é o plano B e pode melhorar numa próxima rodada).
     */
    suspend fun remoteFor(tmdbId: Int, series: Boolean): String {
        store.idToRemote(series, tmdbId)?.let { return it }
        val key = prefs.tmdbApiKey.ifBlank { return SyncPlan.pickRemoteId(null, null, tmdbId) }
        // Título sem IMDb (404) cai no tmdb:N. Falha de rede sobe, para o envio esperar e não criar um id diferente do que virá depois.
        val imdb = try {
            if (series) tmdb.getSeriesExternalIds(tmdbId, apiKey = key).imdbId else tmdb.getMovieExternalIds(tmdbId, apiKey = key).imdbId
        } catch (e: retrofit2.HttpException) {
            if (e.code() == 404) null else throw e
        }
        val id = SyncPlan.pickRemoteId(null, imdb, tmdbId)
        if (id.startsWith("tt")) store.putId(series, id, tmdbId)
        return id
    }
}
