package com.joabtv.app.data.repository

import com.joabtv.app.data.local.dao.MovieHistoryDao
import com.joabtv.app.data.local.dao.WatchedEpisodeDao
import com.joabtv.app.data.local.dao.WatchlistDao
import com.joabtv.app.data.local.entity.MovieHistoryEntity
import com.joabtv.app.data.local.entity.WatchedEpisodeEntity
import com.joabtv.app.domain.model.MediaType
import com.joabtv.app.data.local.entity.WatchlistEntity
import com.joabtv.app.domain.model.MovieItem
import com.joabtv.app.data.local.playbackKey
import com.joabtv.app.data.local.playbackHiddenBefore
import com.joabtv.app.data.local.hidePlayback
import com.joabtv.app.data.local.unhidePlayback
import androidx.room.withTransaction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
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

    suspend fun completePlayback(tmdbId: Int, type: String, episodeLabel: String = "") {
        val profile = pid
        db.withTransaction {
            val current = movieHistoryDao.get(profile, tmdbId, type) ?: return@withTransaction
            val episode = episodeLabel.ifBlank { current.lastEpisode }
            if (type == MediaType.SERIES.name) {
                val parts = Regex("T(\\d+):E(\\d+)", RegexOption.IGNORE_CASE).matchEntire(episode) ?: return@withTransaction
                val season = parts.groupValues[1].toIntOrNull() ?: return@withTransaction
                val number = parts.groupValues[2].toIntOrNull() ?: return@withTransaction
                if (season <= 0 || number <= 0) return@withTransaction
                markEpisodeWatched(tmdbId, season, number, profile)
            }
            val duration = current.durationMs.takeIf { episode == current.lastEpisode && it > 0 } ?: 1L
            movieHistoryDao.upsert(current.copy(lastEpisode = episode, positionMs = duration, durationMs = duration, lastWatchedAt = System.currentTimeMillis()))
        }
    }

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
        prefs.setHistoryHidden(unhidePlayback(prefs.historyHidden(targetPid), playbackKey(tmdbId, type)), targetPid)
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

    /**
     * Progresso vindo de outra conta (Stremio, Nuvio). Só grava quando é mais novo que o do
     * aparelho, para a importação nunca apagar o que a pessoa assistiu aqui. Devolve se gravou.
     */
    suspend fun importPlayback(
        tmdbId: Int, type: String, title: String, posterUrl: String,
        positionMs: Long, durationMs: Long, lastEpisode: String, watchedAt: Long, profileId: String? = null,
        lastSourceUrl: String? = null, lastAddon: String? = null,
    ): Boolean {
        val target = profileId ?: pid
        if (watchedAt <= playbackHiddenBefore(prefs.historyHidden(target), playbackKey(tmdbId, type))) return false
        val prev = movieHistoryDao.get(target, tmdbId, type)
        if (prev != null && prev.lastWatchedAt >= watchedAt) return false
        movieHistoryDao.upsert(
            MovieHistoryEntity(
                profileId = target, tmdbId = tmdbId, type = type,
                title = title.ifBlank { prev?.title.orEmpty() }, posterUrl = posterUrl.ifBlank { prev?.posterUrl.orEmpty() },
                lastWatchedAt = watchedAt, positionMs = positionMs, durationMs = durationMs,
                lastEpisode = lastEpisode,
                lastSourceUrl = lastSourceUrl ?: if (prev?.lastEpisode.orEmpty() == lastEpisode) prev?.lastSourceUrl.orEmpty() else "",
                lastAddon = lastAddon ?: if (prev?.lastEpisode.orEmpty() == lastEpisode) prev?.lastAddon.orEmpty() else "",
            )
        )
        return true
    }

    /** Item de outra conta na Minha Lista. Não mexe no que já estava salvo. Devolve se entrou. */
    suspend fun importToList(
        tmdbId: Int, type: String, title: String, posterUrl: String, year: String, addedAt: Long, profileId: String? = null,
    ): Boolean {
        val target = profileId ?: pid
        if (watchlistDao.isSaved(target, tmdbId, type).first()) return false
        watchlistDao.add(WatchlistEntity(target, tmdbId, type, title, posterUrl, year, addedAt.takeIf { it > 0 } ?: System.currentTimeMillis()))
        return true
    }

    /** Remove UM item do "continuar assistindo". */
    suspend fun removePlayback(tmdbId: Int, type: String) {
        val profile = pid
        val previous = movieHistoryDao.get(profile, tmdbId, type)
        prefs.setHistoryHidden(hidePlayback(prefs.historyHidden(profile), playbackKey(tmdbId, type), maxOf(System.currentTimeMillis(), previous?.lastWatchedAt ?: 0)), profile)
        movieHistoryDao.delete(profile, tmdbId, type)
    }

    /** Limpa todo o historico de reproducoes. */
    suspend fun clearHistory() {
        val profile = pid
        prefs.setHistoryHidden(org.json.JSONObject().put("allBefore", System.currentTimeMillis()).put("items", org.json.JSONObject()).toString(), profile)
        movieHistoryDao.clear(profile)
    }

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

    suspend fun markEpisodesWatched(seriesTmdbId: Int, episodes: List<Pair<Int, Int>>, profileId: String? = null) {
        val profile = profileId ?: pid
        val now = System.currentTimeMillis()
        val entries = episodes.distinct().filter { it.first > 0 && it.second > 0 }.map { (season, episode) ->
            WatchedEpisodeEntity(profileId = profile, seriesTmdbId = seriesTmdbId, season = season, episode = episode, watchedAt = now)
        }
        if (entries.isEmpty()) return
        db.withTransaction {
            watchedEpisodeDao.markWatchedBatch(entries)
            val current = movieHistoryDao.get(profile, seriesTmdbId, MediaType.SERIES.name)
            if (current != null && entries.any { current.lastEpisode.equals("T${it.season}:E${it.episode}", ignoreCase = true) }) {
                val duration = current.durationMs.coerceAtLeast(1)
                movieHistoryDao.upsert(current.copy(positionMs = duration, durationMs = duration, lastWatchedAt = now))
            }
        }
    }

    /** Desmarca um episodio (remove de vistos). */
    suspend fun unmarkEpisodeWatched(seriesTmdbId: Int, season: Int, episode: Int) =
        watchedEpisodeDao.unmark(pid, seriesTmdbId, season, episode)

    suspend fun allWatchedEpisodes(): List<WatchedEpisodeEntity> =
        watchedEpisodeDao.getAll(pid)
}
