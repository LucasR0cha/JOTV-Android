package com.joabtv.app.data.local.dao

import androidx.room.*
import com.joabtv.app.data.local.entity.MovieHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MovieHistoryDao {
    @Query("SELECT * FROM movie_history WHERE profileId = :profileId ORDER BY lastWatchedAt DESC LIMIT 250")
    fun getAll(profileId: String): Flow<List<MovieHistoryEntity>>

    @Query("SELECT tmdbId FROM movie_history WHERE profileId = :profileId AND type = 'MOVIE' AND durationMs > 0 AND positionMs >= durationMs * 0.9")
    fun getWatchedMovies(profileId: String): Flow<List<Int>>

    @Query("SELECT * FROM movie_history WHERE profileId = :profileId AND tmdbId = :tmdbId AND type = :type LIMIT 1")
    suspend fun get(profileId: String, tmdbId: Int, type: String): MovieHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MovieHistoryEntity)

    @Query("DELETE FROM movie_history WHERE profileId = :profileId AND tmdbId = :tmdbId AND type = :type")
    suspend fun delete(profileId: String, tmdbId: Int, type: String)

    @Query("DELETE FROM movie_history WHERE profileId = :profileId")
    suspend fun clear(profileId: String)

    /** Todas as linhas do perfil, sem o teto da lista. Serve para o sync saber o que a limpeza do histórico levou. */
    @Query("SELECT * FROM movie_history WHERE profileId = :profileId")
    suspend fun allOf(profileId: String): List<MovieHistoryEntity>
}
