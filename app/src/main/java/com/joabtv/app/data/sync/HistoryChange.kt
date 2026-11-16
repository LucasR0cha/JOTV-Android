package com.joabtv.app.data.sync

/**
 * Mudança de histórico feita no aparelho pela pessoa (tocar, concluir, marcar visto, remover). O LibraryRepository
 * avisa por aqui e o sync de contas escuta. Importação e puxada do sync nunca avisam, senão o dado voltaria ao
 * serviço de onde acabou de vir.
 */
sealed interface HistoryChange {
    val profileId: String

    data class Progress(override val profileId: String, val tmdbId: Int, val type: String) : HistoryChange
    data class Watched(override val profileId: String, val tmdbId: Int, val season: Int, val episode: Int, val on: Boolean) : HistoryChange

    /** Vários episódios de uma série marcados de uma vez (temporada inteira). Um aviso só, para não estourar o buffer. */
    data class WatchedMany(override val profileId: String, val tmdbId: Int, val episodes: List<Pair<Int, Int>>) : HistoryChange

    /** Remoção de um título. Limpar o histórico vira um destes para cada linha que existia, e nunca um "apaga tudo". */
    data class Removed(override val profileId: String, val tmdbId: Int, val type: String) : HistoryChange
    data class ProfileDropped(override val profileId: String) : HistoryChange
}
