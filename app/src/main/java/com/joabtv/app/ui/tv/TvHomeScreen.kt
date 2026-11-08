package com.joabtv.app.ui.tv

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.joabtv.app.data.remote.YoutubeTrailer
import com.joabtv.app.domain.model.MediaType
import com.joabtv.app.domain.model.MovieItem
import com.joabtv.app.ui.movies.MoviesHomeViewModel
import com.joabtv.app.ui.movies.MoviesHomeViewModel.CatalogEndpoint
import com.joabtv.app.ui.movies.MoviesHomeViewModel.CatalogQuery
import com.joabtv.app.ui.movies.MoviesHomeViewModel.CatalogType
import com.joabtv.app.ui.common.rememberReducedMotion
import com.joabtv.app.ui.navigation.TvMenuClosed
import com.joabtv.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Arte em tela cheia com informações sobre o degradê e fileiras na parte inferior.
 * O destaque acompanha o pôster focado e pausa quando o menu cobre a tela.
 */
@Composable
fun TvHomeScreen(
    onItemClick: (MovieItem) -> Unit,
    viewModel: MoviesHomeViewModel = hiltViewModel(),
    navigationOpen: Boolean = false,
) {
    TvText { TvHomeScreenBody(onItemClick, viewModel, navigationOpen) }
}

@Composable
private fun TvHomeScreenBody(
    onItemClick: (MovieItem) -> Unit,
    viewModel: MoviesHomeViewModel = hiltViewModel(),
    navigationOpen: Boolean = false,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val grid = state.gridQuery
    if (grid != null) {
        androidx.activity.compose.BackHandler { viewModel.closeCatalog() }
        Box(Modifier.fillMaxSize().padding(start = TvMenuClosed)) {
            TvCatalogGrid(state, viewModel, grid.title, onItemClick)
        }
        return
    }

    val scope = rememberCoroutineScope()
    val heroItems = remember(state.trendingMovies, state.trendingTv) {
        (state.trendingMovies + state.trendingTv).filter { it.backdropUrl.isNotBlank() }.take(8)
    }
    var page by remember { mutableIntStateOf(0) }
    // O título selecionado continua no destaque ao voltar para os botões.
    var focusedItem by remember { mutableStateOf<MovieItem?>(null) }
    val carouselItem = heroItems.getOrNull(page.coerceAtMost((heroItems.size - 1).coerceAtLeast(0)))
    val hero = focusedItem ?: carouselItem

    // Trailer aberto pelo botão do destaque ou pela fileira de trailers.
    var trailer by remember { mutableStateOf<YoutubeTrailer.TrailerStream?>(null) }
    var trailerCard by remember { mutableStateOf<MoviesHomeViewModel.TrailerCard?>(null) }
    var trailerTitle by remember { mutableStateOf("") }
    var trailerBusy by remember { mutableStateOf(false) }
    fun playCard(card: MoviesHomeViewModel.TrailerCard) {
        if (trailerBusy) return
        trailerBusy = true
        trailerCard = card
        scope.launch {
            trailer = viewModel.trailerStream(card.videoKey)
            trailerTitle = card.title
            trailerBusy = false
            if (trailer == null) trailerCard = null
        }
    }

    // Carrossel avança a cada 8 s com o foco no destaque (ou antes de qualquer foco).
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val lifecycleState by lifecycle.lifecycle.currentStateFlow.collectAsState()
    val homeActive = !navigationOpen && lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    LaunchedEffect(page, focusedItem, heroItems.size, trailer, homeActive) {
        if (!homeActive || focusedItem != null || heroItems.size < 2 || trailer != null) return@LaunchedEffect
        delay(8000)
        page = (page + 1) % heroItems.size
    }
    // Logo, arte e faixa etária chegam depois de uma pausa no foco, como no webOS.
    LaunchedEffect(hero?.tmdbId) {
        val h = hero ?: return@LaunchedEffect
        delay(380)
        val series = h.type == MediaType.SERIES
        viewModel.ensureHeroLogo(h.tmdbId, series)
        viewModel.ensureHeroCert(h.tmdbId, series)
        if (h.backdropUrl.isBlank()) viewModel.ensureHeroBackdrop(h.tmdbId, series)
    }

    val rails = rememberLazyListState()
    val loadMore by remember {
        derivedStateOf {
            val li = rails.layoutInfo
            li.totalItemsCount > 0 && (li.visibleItemsInfo.lastOrNull()?.index ?: 0) >= li.totalItemsCount - 2
        }
    }
    LaunchedEffect(loadMore) { if (loadMore) viewModel.loadMoreSections() }

    Box(Modifier.fillMaxSize().background(Background)) {
        if (state.isLoading) {
            TvSpinner("Carregando catálogo", Modifier.align(Alignment.Center))
            return@Box
        }
        // Sem rede ou sem chave TMDB: nenhuma fileira carrega e a tela ficava preta, sem nada
        // focável pro D-pad. Mesmo aviso da versão celular (JoabEmptyState), em layout de TV.
        val nothingToShow = heroItems.isEmpty() && state.continueWatching.isEmpty() && state.newEpisodes.isEmpty() &&
            state.addonRows.isEmpty() && state.recommended.isEmpty() && state.nowPlaying.isEmpty() &&
            state.releases2026.isEmpty() && state.homeSections.isEmpty()
        if (nothingToShow) {
            TvHomeFeedEmpty(hasTmdbKey = state.hasTmdbKey, onRetry = viewModel::load)
            return@Box
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val heroH = maxHeight * 0.62f
            TvHeroBanner(
                item = hero,
                state = state,
                height = maxHeight,
                pageCount = if (focusedItem == null) heroItems.size else 0,
                page = page,
                trailerOn = homeActive && state.heroTrailer && trailer == null,
                trailerFor = viewModel::heroTrailerStream,
                onTrailerEnd = { if (focusedItem == null && heroItems.size > 1) page = (page + 1) % heroItems.size },
                trailerBusy = trailerBusy && trailerCard == null,
                onWatch = { hero?.let(onItemClick) },
                onTrailer = {
                    val h = hero ?: return@TvHeroBanner
                    if (trailerBusy) return@TvHeroBanner
                    trailerBusy = true
                    scope.launch {
                        trailer = viewModel.heroTrailerStream(h)
                        trailerTitle = h.title
                        trailerBusy = false
                    }
                },
                modifier = Modifier
                    .tvScrollTop { rails.animateScrollToItem(0) },
            )
            TvScrollArea(inset = 1.25.r) {
                LazyColumn(
                    Modifier.fillMaxSize().padding(start = TvMenuClosed, top = heroH),
                    state = rails,
                    contentPadding = PaddingValues(bottom = 4.r),
                ) {
                    homeRails(state, viewModel, onItemClick, onFocus = { focusedItem = it }, onTrailer = ::playCard)
                }
            }
        }
        trailer?.let { st ->
            val card = trailerCard
            val fila = state.latestTrailers
            val idx = fila.indexOfFirst { it.tmdbId == card?.tmdbId && it.videoKey == card.videoKey }
            val next = if (card != null && idx >= 0 && idx < fila.lastIndex) fila[idx + 1] else null
            com.joabtv.app.ui.common.TrailerExoOverlay(
                videoUrl = st.videoUrl,
                audioUrl = st.audioUrl,
                onClose = { trailer = null; trailerCard = null },
                title = trailerTitle,
                onOpenDetail = card?.let { c ->
                    {
                        trailer = null; trailerCard = null
                        onItemClick(
                            MovieItem(
                                tmdbId = c.tmdbId, embedId = c.tmdbId.toString(), title = c.title, overview = "",
                                posterUrl = c.posterUrl, backdropUrl = c.backdropUrl, year = "", rating = 0.0, type = c.type,
                            )
                        )
                    }
                },
                onNext = next?.let { n -> { trailer = null; playCard(n) } },
            )
        }
    }
}

/** Sem rede ou sem chave TMDB: aviso focável com botão de tentar de novo (em vez de tela preta). */
@Composable
private fun TvHomeFeedEmpty(hasTmdbKey: Boolean, onRetry: () -> Unit) {
    val retryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { retryFocus.requestFocus() } }
    Column(
        Modifier.fillMaxSize().padding(start = TvMenuClosed).padding(horizontal = tvPad),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            if (hasTmdbKey) "Não deu para carregar agora" else "Configure sua chave TMDB",
            color = TextPrimary, fontSize = 1.8.rs, fontWeight = FontWeight.Bold, fontFamily = DisplayFont,
        )
        Text(
            if (hasTmdbKey) "Confira sua conexão e tente de novo." else "Vá em Configurações e cole sua chave TMDB para ver o catálogo.",
            color = TextTertiary, fontSize = 1.1.rs, modifier = Modifier.padding(top = 0.6.r).widthIn(max = 44.r),
        )
        Spacer(Modifier.height(1.4.r))
        TvButton("Tentar de novo", onRetry, Modifier.focusRequester(retryFocus))
    }
}

/** Destaque (.home-hero). Arte com troca suave, trailer mudo depois de 5 s parado, texto e botões. */
@Composable
private fun TvHeroBanner(
    item: MovieItem?,
    state: MoviesHomeViewModel.UiState,
    height: Dp,
    pageCount: Int,
    page: Int,
    trailerOn: Boolean,
    trailerFor: suspend (MovieItem) -> YoutubeTrailer.TrailerStream?,
    onTrailerEnd: () -> Unit,
    trailerBusy: Boolean,
    onWatch: () -> Unit,
    onTrailer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backdrop = item?.backdropUrl?.ifBlank { state.heroBackdrops[item.tmdbId].orEmpty() }.orEmpty()
    val reduced = rememberReducedMotion()
    // Trailer mudo depois de 5 s no mesmo título, só com a tela na frente.
    var muted by remember { mutableStateOf<Pair<Int, YoutubeTrailer.TrailerStream>?>(null) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val resumed by owner.lifecycle.currentStateFlow.collectAsState()
    val active = trailerOn && resumed.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    LaunchedEffect(item?.tmdbId, active) {
        muted = null
        val it = item ?: return@LaunchedEffect
        if (!active) return@LaunchedEffect
        delay(5000)
        trailerFor(it)?.let { st -> muted = it.tmdbId to st }
    }
    Box(modifier.fillMaxWidth().height(height)) {
        Box(Modifier.fillMaxSize()) {
            Crossfade(backdrop, animationSpec = tween(if (reduced) 0 else 280), label = "heroBg") { url ->
                if (url.isNotBlank()) AsyncImage(
                    url, null, Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop, alignment = BiasAlignment(0f, -0.5f),
                )
            }
            muted?.takeIf { it.first == item?.tmdbId }?.let { (_, st) ->
                com.joabtv.app.ui.common.MutedTrailer(
                    st.videoUrl, st.audioUrl, onEnd = { muted = null; onTrailerEnd() }, modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
            0f to Background.copy(alpha = .93f), 0.28f to Background.copy(alpha = .75f),
            0.6f to Background.copy(alpha = .2f), 0.82f to Color.Transparent,
        )))
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().fillMaxHeight(0.7f).background(
            Brush.verticalGradient(
                0f to Color.Transparent, 0.37f to Background.copy(alpha = .4f),
                0.68f to Background.copy(alpha = .94f), 0.92f to Background,
            )
        ))
        if (item == null) return@Box
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = TvMenuClosed + tvPad, bottom = height * 0.42f).fillMaxWidth(0.53f),
        ) {
            Text(
                if (item.type == MediaType.SERIES) "SÉRIE EM DESTAQUE" else "FILME EM DESTAQUE",
                color = Accent, fontSize = 1.rs, lineHeight = 1.3.rs, fontWeight = FontWeight.Bold,
                fontFamily = DisplayFont, letterSpacing = 0.16.rs, modifier = Modifier.padding(bottom = 1.1.r),
            )
            val logo = state.heroLogos[item.tmdbId].orEmpty()
            var logoFailed by remember(logo) { mutableStateOf(false) }
            if (logo.isNotBlank() && !logoFailed) AsyncImage(
                logo, item.title, contentScale = ContentScale.Fit, alignment = Alignment.BottomStart,
                modifier = Modifier.padding(bottom = 1.1.r).height(8.r).widthIn(max = 32.r),
                onError = { logoFailed = true },
            ) else Text(
                item.title, color = TextPrimary, fontSize = 4.rs, fontWeight = FontWeight.Bold, lineHeight = 4.12.rs,
                fontFamily = DisplayFont, maxLines = 2, overflow = TextOverflow.Ellipsis, letterSpacing = (-0.18).rs,
                modifier = Modifier.padding(bottom = 1.r),
            )
            TvMetaRow(
                rating = item.rating, cert = item.certification.ifBlank { state.heroCerts[item.tmdbId].orEmpty() },
                year = item.year, extra = listOf(if (item.type == MediaType.SERIES) "Série" else "Filme"), genres = item.genres,
            )
            if (item.overview.isNotBlank()) Text(
                item.overview, color = TextSecondary, fontSize = 1.15.rs, lineHeight = 1.84.rs, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.r).widthIn(max = 40.r),
            )
            Row(Modifier.padding(top = 1.4.r).offset(x = (-0.2).r), verticalAlignment = Alignment.CenterVertically) {
                HeroAction("Assistir", onWatch, primary = true)
                Spacer(Modifier.width(0.8.r))
                HeroAction(if (trailerBusy) "Carregando…" else "Trailer", onTrailer, busy = trailerBusy)
                if (pageCount > 1) {
                    Spacer(Modifier.width(1.6.r))
                    // Indicador do carrossel.
                    Row(horizontalArrangement = Arrangement.spacedBy(0.5.r)) {
                        repeat(pageCount) { i ->
                            val on = i == page
                            Box(
                                Modifier.height(0.4.r).width(if (on) 1.6.r else 0.4.r).clip(RoundedCornerShape(50))
                                    .background(if (on) Accent else TextTertiary.copy(alpha = .6f)),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroAction(
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    busy: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val scale = tvFocusScale(focused, 1.025f)
    val shape = RoundedCornerShape(0.45.r)
    Row(
        Modifier.padding(0.2.r).graphicsLayer { scaleX = scale; scaleY = scale }
            .height(3.5.r)
            .then(if (focused) Modifier.border(0.18.r, Color.White, shape) else Modifier)
            .clip(shape).background(if (primary) AccentFill else Color.White.copy(alpha = .14f))
            .onFocusChanged { focused = it.isFocused }
            .tvClick({ if (!busy) onClick() })
            .padding(horizontal = 1.7.r),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) com.joabtv.app.ui.common.SpinningIcon(tint = Color.White, size = 1.5.r)
        else Icon(
            if (primary) Icons.Default.PlayArrow else Icons.Default.Theaters,
            null, tint = Color.White, modifier = Modifier.size(1.5.r),
        )
        Spacer(Modifier.width(0.6.r))
        Text(label, color = Color.White, fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 1.1.rs, maxLines = 1)
    }
}

/** Fileiras da Início, na ordem do webOS. */
private fun androidx.compose.foundation.lazy.LazyListScope.homeRails(
    state: MoviesHomeViewModel.UiState,
    viewModel: MoviesHomeViewModel,
    onItemClick: (MovieItem) -> Unit,
    onFocus: (MovieItem) -> Unit,
    onTrailer: (MoviesHomeViewModel.TrailerCard) -> Unit,
) {
    if (state.continueWatching.isNotEmpty()) item(key = "cw") {
        PosterRail(
            "Continuar assistindo", state.continueWatching, onItemClick, onFocus, eyebrow = "Retome de onde parou",
            progress = state.continueProgress,
            subs = state.continueRemaining.mapValues { "Faltam ${it.value} min" },
            badges = state.continueEpisode,
        )
    }
    if (state.newEpisodes.isNotEmpty()) item(key = "newEps") {
        PosterRail(
            "Novos episódios", state.newEpisodes.map { it.item }, onItemClick, onFocus, eyebrow = "Das suas séries",
            badges = state.newEpisodes.associate { it.item.tmdbId to it.badge },
            subs = state.newEpisodes.associate { it.item.tmdbId to it.sub },
        )
    }
    items(state.addonRows.filter { it.loading || it.items.isNotEmpty() }, key = { "addon:" + it.ref.key }) { row ->
        if (row.items.isEmpty()) TvSkeletonRail()
        else PosterRail(
            row.ref.name, row.items, onItemClick, onFocus, eyebrow = row.ref.addon,
            onNearEnd = if (row.more) ({ viewModel.loadAddonRow(row.ref.key) }) else null,
        )
    }
    if (state.recommended.isNotEmpty()) item(key = "rec") {
        PosterRail("Porque você assistiu ${state.recommendedBecause}", state.recommended, onItemClick, onFocus, eyebrow = "Para você")
    }
    if (state.trendingMovies.isNotEmpty()) item(key = "tm") {
        PosterRail(
            "Tops do momento", state.trendingMovies, onItemClick, onFocus, eyebrow = "Filmes",
            seeAll = { viewModel.openCatalog(CatalogQuery("Tops do momento · Filmes", CatalogType.MOVIE, CatalogEndpoint.TRENDING)) },
        )
    }
    if (state.trendingTv.isNotEmpty()) item(key = "ts") {
        PosterRail(
            "Em alta", state.trendingTv, onItemClick, onFocus, eyebrow = "Séries",
            seeAll = { viewModel.openCatalog(CatalogQuery("Em alta · Séries", CatalogType.SERIES, CatalogEndpoint.TRENDING)) },
        )
    }
    if (state.nowPlaying.isNotEmpty()) item(key = "np") {
        PosterRail(
            "Filmes nos cinemas", state.nowPlaying, onItemClick, onFocus,
            seeAll = { viewModel.openCatalog(CatalogQuery("Filmes nos cinemas", CatalogType.MOVIE, CatalogEndpoint.NOW_PLAYING)) },
        )
    }
    if (state.releases2026.isNotEmpty()) item(key = "rel") {
        PosterRail(
            "Lançamentos 2026", state.releases2026, onItemClick, onFocus,
            seeAll = { viewModel.openCatalog(CatalogQuery("Lançamentos 2026", CatalogType.MOVIE, genreId = null, sort = MoviesHomeViewModel.SORT_RELEASE_MOVIE, year = 2026)) },
        )
    }
    if (state.trailersLoading || state.latestTrailers.isNotEmpty()) item(key = "trailers") {
        TrailersRail(state, viewModel, onFocus, onTrailer)
    }
    items(state.homeSections, key = { it.id }) { sec ->
        PosterRail(sec.title, sec.items, onItemClick, onFocus, seeAll = { viewModel.openCatalog(sec.query) })
    }
    if (state.isLoadingSections) item(key = "more") { TvSpinner() }
}

@Composable
private fun PosterRail(
    title: String,
    items: List<MovieItem>,
    onItemClick: (MovieItem) -> Unit,
    onFocus: (MovieItem) -> Unit,
    eyebrow: String? = null,
    progress: Map<Int, Float>? = null,
    subs: Map<Int, String>? = null,
    badges: Map<Int, String>? = null,
    onNearEnd: (() -> Unit)? = null,
    seeAll: (() -> Unit)? = null,
) {
    val row = rememberLazyListState()
    if (onNearEnd != null) {
        val near by remember {
            derivedStateOf {
                val li = row.layoutInfo
                li.totalItemsCount > 0 && (li.visibleItemsInfo.lastOrNull()?.index ?: 0) >= li.totalItemsCount - 4
            }
        }
        LaunchedEffect(near, items.size) { if (near) onNearEnd() }
    }
    TvRail(title, eyebrow = eyebrow, state = row) {
        items(items, key = { "${it.type}:${it.tmdbId}" }) { item ->
            TvPoster(
                title = item.title, imageUrl = item.posterUrl, onClick = { onItemClick(item) }, width = 11.2.r,
                progress = progress?.get(item.tmdbId), subtitle = subs?.get(item.tmdbId), badge = badges?.get(item.tmdbId),
                onFocus = { onFocus(item) },
            )
        }
        if (seeAll != null) item(key = "seeAll") { SeeAllCard(seeAll) }
    }
}

/** Cartão "Ver tudo" no fim da fileira (.see-all). */
@Composable
private fun SeeAllCard(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val s = tvFocusScale(focused, 1.06f)
    val shape = RoundedCornerShape(0.9.r)
    Column(
        Modifier.width(12.5.r).aspectRatio(2f / 3f).scale(s).clip(shape).background(BackgroundCard)
            .border(if (focused) 0.22.r else 0.1.r, if (focused) Color.White else Border, shape)
            .onFocusChanged { focused = it.isFocused }.tvClick(onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = TextSecondary, modifier = Modifier.size(2.6.r))
        Text("Ver tudo", color = TextSecondary, fontSize = 1.05.rs, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 0.8.r))
    }
}

/** Últimos trailers, com abas em chips e cartões largos. */
@Composable
private fun TrailersRail(
    state: MoviesHomeViewModel.UiState,
    viewModel: MoviesHomeViewModel,
    onFocus: (MovieItem) -> Unit,
    onTrailer: (MoviesHomeViewModel.TrailerCard) -> Unit,
) {
    Column(Modifier.fillMaxWidth().tvRow().padding(top = 1.3.r)) {
        TvRailHead("Últimos trailers", "Novidades")
        TvRow(gap = 0.75.r, vertical = 0.6.r) {
            items(MoviesHomeViewModel.TrailerTab.entries.toList(), key = { it.name }) { tab ->
                TvChip(tab.label, state.trailerTab == tab, { viewModel.selectTrailerTab(tab) })
            }
        }
        when {
            state.trailersLoading -> TvSpinner("Carregando trailers")
            state.latestTrailers.isEmpty() -> Text(
                "Nenhum trailer nessa aba agora.", color = TextTertiary, fontSize = 1.rs,
                modifier = Modifier.padding(horizontal = tvPad, vertical = 1.r),
            )
            else -> TvRow {
                items(state.latestTrailers, key = { "${it.tmdbId}:${it.videoKey}" }) { c ->
                    TvWideCard(
                        image = c.backdropUrl, title = c.title, subtitle = c.videoName, playIcon = true, width = 24.r,
                        onClick = { onTrailer(c) },
                        onFocus = {
                            onFocus(MovieItem(c.tmdbId, c.tmdbId.toString(), c.title, "", c.posterUrl, c.backdropUrl, "", 0.0, type = c.type))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TvSkeletonRail() {
    val brush = com.joabtv.app.ui.common.skeletonBrush()
    Column(Modifier.padding(top = 1.3.r)) {
        Box(Modifier.padding(start = tvPad).size(16.r, 1.8.r).clip(RoundedCornerShape(0.5.r)).background(brush))
        Row(Modifier.padding(start = tvPad, top = 1.r), horizontalArrangement = Arrangement.spacedBy(1.25.r)) {
            repeat(8) { Box(Modifier.size(12.5.r, 18.75.r).clip(RoundedCornerShape(0.9.r)).background(brush)) }
        }
    }
}

/** Grade do "Ver tudo" (screens/catalog.js), 8 por linha e mais páginas conforme desce. */
@Composable
private fun TvCatalogGrid(
    state: MoviesHomeViewModel.UiState,
    viewModel: MoviesHomeViewModel,
    title: String,
    onItemClick: (MovieItem) -> Unit,
) {
    val gs = rememberLazyGridState()
    val near by remember {
        derivedStateOf {
            val li = gs.layoutInfo
            li.totalItemsCount > 0 && (li.visibleItemsInfo.lastOrNull()?.index ?: 0) >= li.totalItemsCount - 10
        }
    }
    LaunchedEffect(near) { if (near) viewModel.loadMoreGrid() }
    TvScrollArea(inset = 3.r) {
        LazyVerticalGrid(
            GridCells.Fixed(8), Modifier.fillMaxSize().background(Background), state = gs,
            contentPadding = PaddingValues(start = tvPad, end = tvPad, bottom = 5.r),
            horizontalArrangement = Arrangement.spacedBy(1.3.r),
            verticalArrangement = Arrangement.spacedBy(2.r),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "head") {
                Column(Modifier.padding(top = 2.6.r, bottom = 1.r)) {
                    Text(title, color = TextPrimary, fontSize = 2.6.rs, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.05).rs)
                    Text(
                        if (state.gridLoading) "Carregando" else "${state.gridItems.size} títulos",
                        color = TextTertiary, fontSize = 1.1.rs, modifier = Modifier.padding(top = 0.45.r),
                    )
                }
            }
            if (state.gridLoading) item(span = { GridItemSpan(maxLineSpan) }) { TvSpinner() }
            items(state.gridItems.size, key = { state.gridItems[it].tmdbId }) { i ->
                val it = state.gridItems[i]
                TvPoster(it.title, it.posterUrl, { onItemClick(it) }, width = Dp.Unspecified, subtitle = it.year.ifBlank { null })
            }
            if (state.gridLoadingMore) item(span = { GridItemSpan(maxLineSpan) }) { TvSpinner() }
        }
    }
}
