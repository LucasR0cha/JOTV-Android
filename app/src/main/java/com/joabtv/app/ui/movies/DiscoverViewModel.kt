package com.joabtv.app.ui.movies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.joabtv.app.data.local.PlatformPrefs
import com.joabtv.app.data.remote.AddonCatalogOption
import com.joabtv.app.data.remote.AddonMetaResolver
import com.joabtv.app.data.remote.StremioAddonService
import com.joabtv.app.data.remote.TmdbApiService
import com.joabtv.app.data.remote.dto.TmdbGenreDto
import com.joabtv.app.data.remote.dto.toMovieItem
import com.joabtv.app.domain.model.MovieItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Página "Descobrir". Tem duas fontes. Os filtros do TMDB (tipo, gêneros, ordenação, classificação
 * indicativa, nota mínima e ano) e os catálogos dos addons, que trazem os próprios filtros (gênero,
 * ano e o que o manifest declarar), como no Nuvio. Nos dois casos a lista é infinita.
 *
 * Qualquer mudança de filtro reinicia na página 1 com um pequeno atraso, senão bater várias
 * chips seguidas dispararia uma requisição por toque.
 */
@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val tmdb: TmdbApiService,
    private val prefs: PlatformPrefs,
    private val stremio: StremioAddonService,
    private val addonMetas: AddonMetaResolver,
) : ViewModel() {

    enum class Kind { MOVIE, SERIES }

    data class UiState(
        val kind: Kind = Kind.MOVIE,
        val genres: List<TmdbGenreDto> = emptyList(),
        val selectedGenres: Set<Int> = emptySet(),
        val sort: String = SORT_POPULAR,
        /** Classificação indicativa BR ("" = qualquer). Só vale pra filme. */
        val cert: String = "",
        /** Nota mínima (null = qualquer). */
        val minRating: Float? = null,
        /** Ano de lançamento (null = qualquer). */
        val year: Int? = null,
        val items: List<MovieItem> = emptyList(),
        val page: Int = 1,
        val totalPages: Int = 1,
        val totalResults: Int = 0,
        val loading: Boolean = false,
        val loadingMore: Boolean = false,
        /** Catálogos de filme e série dos addons ligados. */
        val catalogs: List<AddonCatalogOption> = emptyList(),
        /** Chave ([catalogKey]) do catálogo de addon em uso. Vazio é o Descobrir do TMDB. */
        val catalogKey: String = "",
        /** Filtro do catálogo que a pessoa escolheu (nome do extra para o valor). */
        val extraValues: Map<String, String> = emptyMap(),
        /** Quantos itens o addon já entregou, que é o skip da próxima página. */
        val skip: Int = 0,
        val endReached: Boolean = false,
        /** O addon não respondeu na primeira página. */
        val failed: Boolean = false,
        /** O addon respondeu, mas nenhum item tem par no TMDB. */
        val unresolved: Boolean = false,
    ) {
        val type: String get() = if (kind == Kind.MOVIE) "movie" else "series"

        /** Catálogos dos addons para o tipo escolhido. */
        val kindCatalogs: List<AddonCatalogOption> get() = catalogs.filter { it.type == type }

        /** Catálogo de addon em uso, null quando vale o Descobrir do TMDB. */
        val catalog: AddonCatalogOption?
            get() = catalogKey.takeIf { it.isNotEmpty() }?.let { key -> kindCatalogs.firstOrNull { it.catalogKey() == key } }

        /** Filtros enviados ao addon. Filtro obrigatório sem escolha usa a primeira opção. */
        val addonFilters: Map<String, String>
            get() = catalog?.extras.orEmpty().mapNotNull { e ->
                val v = extraValues[e.name]?.takeIf { it in e.options } ?: e.options.first().takeIf { e.required }
                v?.let { e.name to it }
            }.toMap()

        /** Quantos filtros o usuário mexeu (fora tipo e ordenação). Vira o contador do botão limpar. */
        val activeFilterCount: Int
            get() = if (catalog != null) extraValues.size
            else selectedGenres.size + (if (cert.isNotBlank()) 1 else 0) +
                (if (minRating != null) 1 else 0) + (if (year != null) 1 else 0)
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var reloadJob: Job? = null
    private var queryRevision = 0

    /** Ids do addon já recebidos, para saber quando ele só repete a mesma página (ignora o skip). */
    private val seenMetas = HashSet<String>()

    init {
        loadGenres()
        reload(immediate = true)
        watchCatalogs()
    }

    // ── Ações de filtro ───────────────────────────────────────────────────────

    fun setKind(kind: Kind) {
        if (_uiState.value.kind == kind) return
        // Gêneros de filme e de série têm ids diferentes, então a seleção não sobrevive à troca.
        _uiState.update {
            val type = if (kind == Kind.MOVIE) "movie" else "series"
            // Mesmo catálogo do mesmo addon no outro tipo, quando existe. Senão volta aos filtros do TMDB.
            val twin = it.catalog?.let { c -> it.catalogs.firstOrNull { o -> o.type == type && o.base == c.base && o.id == c.id } }
            it.copy(
                kind = kind, genres = emptyList(), selectedGenres = emptySet(), cert = "",
                sort = if (it.sort == SORT_RELEASE_MOVIE || it.sort == SORT_RELEASE_TV)
                    (if (kind == Kind.MOVIE) SORT_RELEASE_MOVIE else SORT_RELEASE_TV) else it.sort,
                catalogKey = twin?.catalogKey().orEmpty(), extraValues = emptyMap(),
            )
        }
        loadGenres()
        reload()
    }

    /** Escolhe o catálogo do addon pela chave. "" volta aos filtros do TMDB. */
    fun selectCatalog(key: String) {
        if (_uiState.value.catalogKey == key) return
        _uiState.update { it.copy(catalogKey = key, extraValues = emptyMap()) }
        reload()
    }

    /** Valor de um filtro do catálogo. "" tira o filtro. */
    fun setExtra(name: String, value: String) {
        if (_uiState.value.extraValues[name].orEmpty() == value) return
        _uiState.update { it.copy(extraValues = if (value.isEmpty()) it.extraValues - name else it.extraValues + (name to value)) }
        reload()
    }

    fun toggleGenre(id: Int) {
        _uiState.update {
            val sel = it.selectedGenres
            it.copy(selectedGenres = if (id in sel) sel - id else sel + id)
        }
        reload()
    }

    fun setGenres(ids: Set<Int>) {
        if (_uiState.value.selectedGenres == ids) return
        _uiState.update { it.copy(selectedGenres = ids) }
        reload()
    }

    fun setSort(sort: String) {
        if (_uiState.value.sort == sort) return
        _uiState.update { it.copy(sort = sort) }
        reload()
    }

    /** "" limpa. Só tem efeito em filme (o TMDB não classifica série). */
    fun setCert(cert: String) {
        _uiState.update { it.copy(cert = if (it.cert == cert) "" else cert) }
        reload()
    }

    fun setMinRating(rating: Float?) {
        _uiState.update { it.copy(minRating = if (it.minRating == rating) null else rating) }
        reload()
    }

    fun setYear(year: Int?) {
        _uiState.update { it.copy(year = if (it.year == year) null else year) }
        reload()
    }

    fun clearFilters() {
        _uiState.update { it.copy(selectedGenres = emptySet(), cert = "", minRating = null, year = null, extraValues = emptyMap()) }
        reload()
    }

    // ── Carregamento ──────────────────────────────────────────────────────────

    private fun loadGenres() {
        val apiKey = prefs.tmdbApiKey
        if (apiKey.isBlank()) return
        val kind = _uiState.value.kind
        viewModelScope.launch {
            val list = runCatching {
                if (kind == Kind.MOVIE) tmdb.getMovieGenres(apiKey = apiKey).genres
                else tmdb.getTvGenres(apiKey = apiKey).genres
            }.getOrDefault(emptyList())
            _uiState.update { if (it.kind == kind) it.copy(genres = list) else it }
        }
    }

    /**
     * Lê os catálogos dos addons ligados e relê quando a lista de addons muda. Perfil infantil não vê
     * catálogo de addon, porque o addon não classifica idade.
     */
    private fun watchCatalogs() {
        viewModelScope.launch {
            // O perfil entra na conta para o infantil esconder os catálogos assim que a pessoa troca de perfil.
            combine(prefs.addons.map { list -> list.filter { it.enabled }.map { it.url } }.distinctUntilChanged(), prefs.profileId) { urls, _ -> urls }.collectLatest { urls ->
                val list = if (prefs.isKids) emptyList() else coroutineScope {
                    urls.map { u -> async { runCatching { stremio.discoverCatalogs(u) }.getOrDefault(emptyList()) } }.awaitAll().flatten()
                }
                var dropped = false
                _uiState.update { st ->
                    val keep = st.catalogKey.isEmpty() || list.any { it.type == st.type && it.catalogKey() == st.catalogKey }
                    dropped = !keep
                    st.copy(
                        catalogs = list,
                        catalogKey = if (keep) st.catalogKey else "",
                        extraValues = if (keep) st.extraValues else emptyMap(),
                    )
                }
                // O addon da lista aberta saiu. A tela passa a mostrar o TMDB e precisa recarregar.
                if (dropped) reload()
            }
        }
    }

    private fun reload(immediate: Boolean = false) {
        val revision = ++queryRevision
        reloadJob?.cancel()
        seenMetas.clear()
        _uiState.update {
            it.copy(
                loading = true, loadingMore = false, items = emptyList(), totalResults = 0, page = 1,
                skip = 0, endReached = false, failed = false, unresolved = false,
            )
        }
        reloadJob = viewModelScope.launch {
            if (!immediate) delay(280) // agrupa toques seguidos em chips
            val s = _uiState.value
            if (s.catalog != null) {
                val resp = fetchAddon(s, skip = 0)
                if (revision != queryRevision) return@launch
                resp?.let { seenMetas.addAll(it.rawIds) }
                _uiState.update {
                    if (resp == null) it.copy(loading = false, endReached = true, failed = true)
                    else it.copy(
                        loading = false, items = resp.items.distinctBy { m -> m.tmdbId }, skip = resp.rawIds.size,
                        endReached = resp.rawIds.isEmpty(), unresolved = resp.items.isEmpty() && resp.rawIds.isNotEmpty(),
                    )
                }
                return@launch
            }
            val resp = fetch(s, page = 1)
            if (revision != queryRevision) return@launch
            _uiState.update {
                if (resp == null) it.copy(loading = false)
                else it.copy(
                    loading = false, items = resp.items, page = 1,
                    totalPages = resp.totalPages, totalResults = resp.totalResults,
                )
            }
        }
    }

    /** Próxima página (scroll infinito). Ignora quando já está no fim ou carregando. */
    fun loadMore() {
        val s = _uiState.value
        if (s.loading || s.loadingMore) return
        if (s.catalog != null) { loadMoreAddon(s); return }
        if (s.page >= s.totalPages) return
        val next = s.page + 1
        val revision = queryRevision
        viewModelScope.launch {
            _uiState.update { it.copy(loadingMore = true) }
            val resp = fetch(s, page = next)
            if (revision != queryRevision) return@launch
            _uiState.update { cur ->
                if (resp == null) cur.copy(loadingMore = false)
                else {
                    // Dedupe: o TMDB às vezes repete título entre páginas quando a ordenação empata.
                    val seen = cur.items.mapTo(HashSet()) { it.tmdbId }
                    cur.copy(
                        loadingMore = false,
                        items = cur.items + resp.items.filter { seen.add(it.tmdbId) },
                        page = next,
                        totalPages = resp.totalPages,
                    )
                }
            }
        }
    }

    /**
     * Página seguinte do catálogo do addon. Acaba quando ele devolve vazio ou só repete o que já
     * mandou (alguns ignoram o skip). Falha no meio da lista também para, senão a rolagem insistiria.
     */
    private fun loadMoreAddon(s: UiState) {
        if (s.endReached) return
        val revision = queryRevision
        viewModelScope.launch {
            _uiState.update { it.copy(loadingMore = true) }
            val resp = fetchAddon(s, skip = s.skip)
            if (revision != queryRevision) return@launch
            val fresh = resp?.rawIds?.count { seenMetas.add(it) } ?: 0
            _uiState.update { cur ->
                if (resp == null) cur.copy(loadingMore = false, endReached = true)
                else {
                    val seen = cur.items.mapTo(HashSet()) { it.tmdbId }
                    cur.copy(
                        loadingMore = false,
                        items = cur.items + resp.items.filter { seen.add(it.tmdbId) },
                        skip = cur.skip + resp.rawIds.size,
                        endReached = fresh == 0,
                    )
                }
            }
        }
    }

    private class Page(val items: List<MovieItem>, val totalPages: Int, val totalResults: Int)

    /** Uma página do addon. [rawIds] são os ids que ele mandou, mesmo os que não viraram título. */
    private class AddonPage(val items: List<MovieItem>, val rawIds: List<String>)

    private suspend fun fetchAddon(s: UiState, skip: Int): AddonPage? {
        val c = s.catalog ?: return null
        val metas = stremio.catalogMetas(c.base, c.type, c.id, skip, extra = s.addonFilters) ?: return null
        return AddonPage(addonMetas.resolve(metas, c.type == "series"), metas.map { it.id })
    }

    private suspend fun fetch(s: UiState, page: Int): Page? {
        val apiKey = prefs.tmdbApiKey
        if (apiKey.isBlank()) return null
        val genres = s.selectedGenres.takeIf { it.isNotEmpty() }?.joinToString(",")
        return runCatching {
            if (s.kind == Kind.MOVIE) {
                val r = tmdb.discoverMoviesFiltered(
                    withGenres = genres,
                    sort = s.sort,
                    page = page,
                    voteGte = s.minRating,
                    year = s.year,
                    certCountry = if (s.cert.isNotBlank()) "BR" else null,
                    certification = s.cert.takeIf { it.isNotBlank() },
                    apiKey = apiKey,
                )
                Page(
                    r.results.filterNot { it.adult }.map { it.toMovieItem() }
                        .filter { it.posterUrl.isNotBlank() && it.title.isNotBlank() },
                    r.totalPages, r.totalResults,
                )
            } else {
                val r = tmdb.discoverTvFiltered(
                    withGenres = genres,
                    // discover/tv não conhece title.asc, o campo lá é name.
                    sort = if (s.sort == SORT_TITLE) "name.asc" else s.sort,
                    page = page,
                    voteGte = s.minRating,
                    year = s.year,
                    apiKey = apiKey,
                )
                Page(
                    r.results.filterNot { it.adult }.map { it.toMovieItem() }
                        .filter { it.posterUrl.isNotBlank() && it.title.isNotBlank() },
                    r.totalPages, r.totalResults,
                )
            }
        }.getOrNull()
    }

    companion object {
        const val SORT_POPULAR = "popularity.desc"
        const val SORT_TOP_RATED = "vote_average.desc"
        const val SORT_RELEASE_MOVIE = "primary_release_date.desc"
        const val SORT_RELEASE_TV = "first_air_date.desc"
        const val SORT_TITLE = "title.asc"

        /** Classificação indicativa brasileira, na ordem oficial. */
        val CERTS = listOf("L", "10", "12", "14", "16", "18")
    }
}

/** Chave estável do catálogo de um addon (base, tipo e id). */
fun AddonCatalogOption.catalogKey(): String = "$base|$type|$id"

/** Título do filtro do catálogo, a partir do nome do extra que o manifest declara. */
fun addonFilterTitle(name: String): String = when (name.lowercase()) {
    "genre" -> "Gênero"
    "year" -> "Ano"
    "rating" -> "Nota"
    "sort", "order", "orderby" -> "Ordenar por"
    "country" -> "País"
    "language", "lang" -> "Idioma"
    else -> name.replaceFirstChar { it.uppercase() }
}
