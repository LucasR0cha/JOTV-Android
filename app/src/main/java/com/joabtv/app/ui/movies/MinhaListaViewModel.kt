package com.joabtv.app.ui.movies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.joabtv.app.data.local.entity.MovieHistoryEntity
import com.joabtv.app.data.local.entity.WatchlistEntity
import com.joabtv.app.data.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel da tela "Minha Lista" do modo Filmes & Séries.
 *
 * Expõe os dois Flows da BIBLIOTECA usados pela tela:
 *  - [watchlist]: itens salvos (B — Minha Lista)
 *  - [recentlyPlayed]: reproduções recentes (C — Continuar assistindo)
 */
@HiltViewModel
class MinhaListaViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val trakt: com.joabtv.app.data.remote.TraktService,
) : ViewModel() {

    val watchlist: StateFlow<List<WatchlistEntity>> =
        library.watchlist().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val recentlyPlayed: StateFlow<List<MovieHistoryEntity>> =
        library.recentlyPlayed().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /** Remove um item de "Continuar assistindo". */
    fun removeFromContinue(tmdbId: Int, type: String) {
        viewModelScope.launch { library.removePlayback(tmdbId, type) }
    }

    /** Tira um título salvo da Minha Lista (segurar no card do grid). */
    fun removeFromWatchlist(tmdbId: Int, type: String) {
        viewModelScope.launch { library.removeFromList(tmdbId, type) }
        trakt.watchlistChanged(tmdbId, type, added = false)
    }
}
