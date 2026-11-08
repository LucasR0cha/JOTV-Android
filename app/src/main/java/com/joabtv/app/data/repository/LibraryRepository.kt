package com.joabtv.app.data.repository

import com.joabtv.app.data.local.dao.MovieHistoryDao
import com.joabtv.app.data.local.dao.WatchedEpisodeDao
import com.joabtv.app.data.local.dao.WatchlistDao
import com.joabtv.app.data.local.entity.MovieHistoryEntity
import com.joabtv.app.data.local.entity.WatchedEpisodeEntity
import com.joabtv.app.data.local.entity.WatchlistEntity
import com.joabtv.app.domain.model.MovieItem
import androidx.room.withTransaction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Agrega os 3 DAOs da BIBLIOTECA do JoabTV (Minha Lista, Continuar Assistindo, Episodios Vistos).
 * Expoe metodos convenientes baseados em [MovieItem] (dominio) para a UI consumir.
 * Tudo vale para o perfil ativo. As listas observadas trocam sozinhas quando o perfil muda.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class LibraryRepository @Inject constructor(
    private val watchlistDao: WatchlistDao,
    private val movieHistoryDao: MovieHistoryDao,
    private val watchedEpisodeDao: WatchedEpisodeDao,
    private val prefs: com.joabtv.app.data.local.PlatformPrefs,
    private val db: com.joabtv.app.data.local.AppDatabase,
) {
    private val pid get() = prefs.profileId.value

    /** Apaga Minha Lista, histórico e episódios vistos de um perfil removido (tudo ou nada). */
    suspend fun dropProfile(profileId: String) = db.withTransaction {
        watchlistDao.dropProfile(profileId)
        movieHistoryDao.clear(profileId)
        watchedEpisodeDao.dropProfile(profileId)
    }

    // ----- Minha Lista (Watchlist) -----

    /** Itens salvos na Minha Lista, mais recentes primeiro. Com [profileId] lê o perfil pedido,
     * em vez do ativo (usado pela sincronia do Trakt, que captura o perfil uma vez no início). */
    fun watchlist(profileId: String? = null): Flow<List<WatchlistEntity>> =
        if (profileId != null) watchlistDao.getAll(profileId) else prefs.profileId.flatMapLatest { watchlistDao.getAll(it) }

    /** Reativo: o item esta salvo na Minha Lista? */
    fun isSaved(tmdbId: Int, type: String): Flow<Boolean> =
        prefs.profileId.flatMapLatest { watchlistDao.isSaved(it, tmdbId, type) }

    /** Reativo: o item esta salvo na Minha Lista? (sobrecarga p/ MovieItem) */
    fun isSaved(item: MovieItem): Flow<Boolean> = isSaved(item.tmdbId, item.type.name)

    /** Salva um [MovieItem] na Minha Lista. Com [profileId], grava nesse perfil em vez do ativo. */
    suspend fun saveToList(item: MovieItem, profileId: String? = null) {
        watchlistDao.add(
            WatchlistEntity(
                profileId = profileId ?: pid,
                tmdbId = item.tmdbId,
                type = item.type.name,
                title = item.title,
                posterUrl = item.posterUrl,
                year = item.year,
                addedAt = System.currentTimeMillis(),
            )
        )
    }

    /** Remove um [MovieItem] da Minha Lista. */
    suspend fun removeFromList(item: MovieItem) =
        watchlistDao.remove(pid, item.tmdbId, item.type.name)

    /** Remove da Minha Lista por id + tipo. */
    suspend fun removeFromList(tmdbId: Int, type: String) =
        watchlistDao.remove(pid, tmdbId, type)

    // ----- Continuar Assistindo / Reproducoes recentes (MovieHistory) -----

    /** Reproducoes recentes (max 30), mais recentes primeiro. */
    fun recentlyPlayed(): Flow<List<MovieHistoryEntity>> = prefs.profileId.flatMapLatest { movieHistoryDao.getAll(it) }

    /** Progresso salvo de um item (posição/fonte/episódio), ou null. Com [profileId], lê desse
     * perfil em vez do ativo. */
    suspend fun getPlayback(tmdbId: Int, type: String, profileId: String? = null): MovieHistoryEntity? =
        movieHistoryDao.get(profileId ?: pid, tmdbId, type)

    /**
     * Registra uma reproducao (upsert). [lastEpisode] ex "T1:E3" para serie, vazio p/ filme.
     * [lastSourceUrl] = fonte usada (p/ retomar na mesma). Preserva valores antigos quando 0/vazio.
     */
    suspend fun recordPlayback(
        item: MovieItem,
        positionMs: Long = 0,
        durationMs: Long = 0,
        lastEpisode: String = "",
        lastSourceUrl: String = "",
        lastAddon: String = "",
    ) {
        val prev = movieHistoryDao.get(pid, item.tmdbId, item.type.name)
        movieHistoryDao.upsert(
            MovieHistoryEntity(
                profileId = pid,
                tmdbId = item.tmdbId,
                type = item.type.name,
                title = item.title.ifBlank { prev?.title ?: "" },
                posterUrl = item.posterUrl.ifBlank { prev?.posterUrl ?: "" },
                lastWatchedAt = System.currentTimeMillis(),
                positionMs = if (positionMs > 0) positionMs else prev?.positionMs ?: 0,
                durationMs = if (durationMs > 0) durationMs else prev?.durationMs ?: 0,
                lastEpisode = lastEpisode.ifBlank { prev?.lastEpisode ?: "" },
                lastSourceUrl = lastSourceUrl.ifBlank { prev?.lastSourceUrl ?: "" },
                lastAddon = lastAddon.ifBlank { prev?.lastAddon ?: "" },
            )
        )
    }

    /** Salva SÓ a posição/fonte (chamado pelo player periodicamente e ao sair). */
    suspend fun saveProgress(
        tmdbId: Int, type: String, title: String, posterUrl: String,
        positionMs: Long, durationMs: Long, lastEpisode: String, lastSourceUrl: String,
        lastAddon: String = "",
        profileId: String? = null,
    ) {
        val targetPid = profileId ?: pid
        val prev = movieHistoryDao.get(targetPid, tmdbId, type)
        movieHistoryDao.upsert(
            MovieHistoryEntity(
                profileId = targetPid,
                tmdbId = tmdbId,
                type = type,
                title = title.ifBlank { prev?.title ?: "" },
                posterUrl = posterUrl.ifBlank { prev?.posterUrl ?: "" },
                lastWatchedAt = System.currentTimeMillis(),
                positionMs = positionMs,
                durationMs = durationMs,
                lastEpisode = lastEpisode.ifBlank { prev?.lastEpisode ?: "" },
                lastSourceUrl = lastSourceUrl.ifBlank { prev?.lastSourceUrl ?: "" },
                lastAddon = lastAddon.ifBlank { prev?.lastAddon ?: "" },
            )
        )
    }

    /** Remove UM item do "continuar assistindo". */
    suspend fun removePlayback(tmdbId: Int, type: String) = movieHistoryDao.delete(pid, tmdbId, type)

    /** Limpa todo o historico de reproducoes. */
    suspend fun clearHistory() = movieHistoryDao.clear(pid)

    // ----- Episodios vistos (WatchedEpisode) -----

    /** Episodios ja vistos de uma serie. */
    fun watchedEpisodes(seriesTmdbId: Int): Flow<List<WatchedEpisodeEntity>> =
        prefs.profileId.flatMapLatest { watchedEpisodeDao.getForSeries(it, seriesTmdbId) }

    /** O perfil ativo já viu esse episódio? Com [profileId], confere nesse perfil em vez do ativo. */
    suspend fun isEpisodeWatched(seriesTmdbId: Int, season: Int, episode: Int, profileId: String? = null): Boolean =
        watchedEpisodeDao.isWatched(profileId ?: pid, seriesTmdbId, season, episode)

    /** Marca um episodio como visto. Com [profileId], grava nesse perfil em vez do ativo. */
    suspend fun markEpisodeWatched(seriesTmdbId: Int, season: Int, episode: Int, profileId: String? = null) {
        watchedEpisodeDao.markWatched(
            WatchedEpisodeEntity(
                profileId = profileId ?: pid,
                seriesTmdbId = seriesTmdbId,
                season = season,
                episode = episode,
                watchedAt = System.currentTimeMillis(),
            )
        )
    }

    /** Desmarca um episodio (remove de vistos). */
    suspend fun unmarkEpisodeWatched(seriesTmdbId: Int, season: Int, episode: Int) =
        watchedEpisodeDao.unmark(pid, seriesTmdbId, season, episode)
}
