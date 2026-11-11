package com.joabtv.app.ui.tv

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
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
import com.joabtv.app.ui.common.shimmer
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.joabtv.app.data.remote.YoutubeTrailer
import com.joabtv.app.domain.model.MediaType
import com.joabtv.app.domain.model.MovieItem
import com.joabtv.app.data.local.SystemRow
import com.joabtv.app.data.local.posterKey
import com.joabtv.app.data.collections.MediaCollection
import com.joabtv.app.ui.movies.heroCandidates
import com.joabtv.app.ui.movies.MoviesHomeViewModel
import com.joabtv.app.ui.movies.MoviesHomeViewModel.CatalogEndpoint
import com.joabtv.app.ui.movies.MoviesHomeViewModel.CatalogQuery
import com.joabtv.app.ui.movies.MoviesHomeViewModel.CatalogType
import com.joabtv.app.ui.common.rememberReducedMotion
import com.joabtv.app.ui.navigation.TvMenuClosed
import com.joabtv.app.ui.navigation.TvMenuTop
import com.joabtv.app.data.local.MenuStyle
import com.joabtv.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Arte em tela cheia com informações sobre o degradê e fileiras na parte inferior.
 * O destaque acompanha o pôster focado e pausa quando o menu cobre a tela.
 */
/** Catálogo pronto para a Início. Só a revisão de debug fornece, o app usa o ViewModel normal. */
val LocalTvHomeViewModel = staticCompositionLocalOf<MoviesHomeViewModel?> { null }

@Composable
fun TvHomeScreen(
    onItemClick: (MovieItem) -> Unit,
    viewModel: MoviesHomeViewModel = LocalTvHomeViewModel.current ?: hiltViewModel(),
    navigationOpen: Boolean = false,
    onFolderClick: (String, String) -> Unit = { _, _ -> },
    onCustomizeHome: () -> Unit = {},
    onExploreAddons: () -> Unit = {},
) {
    TvText { TvHomeScreenBody(onItemClick, viewModel, navigationOpen, onFolderClick, onCustomizeHome, onExploreAddons) }
}

/** Dá à Início vazia o jeito de religar as sugestões do JOTV sem passar pelo ViewModel da Início. */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
internal interface TvHomePrefsEntry { fun prefs(): com.joabtv.app.data.local.PlatformPrefs }

@Composable
private fun TvHomeScreenBody(
    onItemClick: (MovieItem) -> Unit,
    viewModel: MoviesHomeViewModel = hiltViewModel(),
    navigationOpen: Boolean = false,
    onFolderClick: (String, String) -> Unit,
    onCustomizeHome: () -> Unit = {},
    onExploreAddons: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val collections by viewModel.collectionsFlow.collectAsStateWithLifecycle()
    val grid = state.gridQuery
    if (grid != null) {
        androidx.activity.compose.BackHandler { viewModel.closeCatalog() }
        Box(Modifier.fillMaxSize().padding(start = TvMenuClosed, top = TvMenuTop)) {
            TvCatalogGrid(state, viewModel, grid.title, onItemClick)
        }
        return
    }

    val scope = rememberCoroutineScope()
    val candidates = remember(state) { heroCandidates(state) }
    LaunchedEffect(candidates.map { posterKey(it) }) {
        candidates.take(8).forEach { if (it.backdropUrl.isBlank()) viewModel.ensureHeroBackdrop(it.tmdbId, it.type == MediaType.SERIES) }
    }
    val heroItems = remember(candidates, state.heroBackdrops) {
        candidates.mapNotNull { item ->
            val backdrop = item.backdropUrl.ifBlank { state.heroBackdrops[item.tmdbId].orEmpty() }
            if (backdrop.isBlank()) null else item.copy(backdropUrl = backdrop)
        }.take(8)
    }
    var page by remember { mutableIntStateOf(0) }
    // O título selecionado continua no destaque ao voltar para os botões.
    var focusedItem by remember { mutableStateOf<MovieItem?>(null) }
    val pendingFocus = remember { mutableStateOf<MovieItem?>(null) }
    val focusSettled by remember {
        derivedStateOf { pendingFocus.value == focusedItem }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { pendingFocus.value }.collectLatest { item ->
            if (item != null) {
                delay(80)
                focusedItem = item
            }
        }
    }
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
    LaunchedEffect(page, focusedItem, heroItems.size, trailer, homeActive, focusSettled) {
        if (!homeActive || !focusSettled || focusedItem != null || heroItems.size < 2 || trailer != null) return@LaunchedEffect
        delay(8000)
        page = (page + 1) % heroItems.size
    }
    // Logo, arte e faixa etária chegam depois de uma pausa no foco, como no webOS.
    LaunchedEffect(hero?.tmdbId, focusSettled) {
        val h = hero ?: return@LaunchedEffect
        if (!focusSettled) return@LaunchedEffect
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
    val genresOn = state.layout.any { it.system == SystemRow.GENRES }
    LaunchedEffect(loadMore, genresOn) { if (loadMore && genresOn) viewModel.loadMoreSections() }

    Box(Modifier.fillMaxSize().background(Background)) {
        if (state.isLoading) {
            TvSpinner("Carregando catálogo", Modifier.align(Alignment.Center))
            return@Box
        }
        // Sem rede ou sem chave TMDB: nenhuma fileira carrega e a tela ficava preta, sem nada
        // focável pro D-pad. Mesmo aviso da versão celular (JoabEmptyState), em layout de TV.
        val nothingToShow = heroItems.isEmpty() && state.continueWatching.isEmpty() && state.newEpisodes.isEmpty() &&
            state.addonRows.isEmpty() && state.recommended.isEmpty() && state.nowPlaying.isEmpty() &&
            state.releases2026.isEmpty() && state.homeSections.isEmpty() && collections.none { c -> c.folders.isNotEmpty() && state.layout.any { it.collectionId == c.id } }
        if (nothingToShow) {
            // Início vazia diz por que e oferece o próximo passo em botão, nunca em caminho de menu.
            val why = when {
                !state.catalogOn && state.layout.none { it.ref != null || it.collectionId != null } -> HomeEmpty.SUGGESTIONS_OFF
                state.layout.isEmpty() -> HomeEmpty.ALL_HIDDEN
                else -> HomeEmpty.FEED
            }
            val ctx = androidx.compose.ui.platform.LocalContext.current
            TvHomeFeedEmpty(
                why = why, hasTmdbKey = state.hasTmdbKey, onRetry = viewModel::load,
                onSuggestions = {
                    dagger.hilt.android.EntryPointAccessors.fromApplication(ctx.applicationContext, TvHomePrefsEntry::class.java).prefs().catalogOn = true
                },
                onExplore = onExploreAddons, onOrganize = onCustomizeHome,
            )
            return@Box
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val pageTop = 32.r.coerceAtMost(maxHeight * .5f)
            val pageHeight = maxHeight - pageTop
            // A área das fileiras começa acima dos botões do destaque (cada fileira é uma página
            // que vai até o alto). A busca de foco do Compose não liga dois grupos que se
            // sobrepõem, então o caminho entre os botões e a primeira fileira é feito aqui.
            val focusManager = LocalFocusManager.current
            val railsEntry = remember { FocusRequester() }
            val heroEntry = remember { FocusRequester() }
            var railContentHeight by remember { mutableStateOf<Dp?>(null) }
            val reduced = rememberReducedMotion()
            // Menu Superior. A fileira focada fica inteira e a seguinte espia embaixo. As fileiras
            // têm altura natural e a área delas começa no topo da fileira focada.
            val peekLayout = LocalMenuStyle.current == MenuStyle.TOP
            // A espiada da fileira seguinte encolhe entre 1,1 e 1,2 do Tamanho da interface. Sem isso o destaque não cabe e passa por baixo da barra do menu Superior.
            val peek = 6.r * ((1.2f - LocalUiScale.current) / 0.1f).coerceIn(0f, 1f)
            val density = androidx.compose.ui.platform.LocalDensity.current
            val firstBlock by remember {
                derivedStateOf { rails.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }?.size ?: 0 }
            }
            val block = railContentHeight ?: with(density) { firstBlock.toDp() }.takeIf { it > 0.r } ?: 27.r
            val railsTopTarget = if (peekLayout) (maxHeight - peek - block).coerceAtLeast(maxHeight * .3f) else pageTop
            val railsTop = animateDpAsState(
                targetValue = railsTopTarget,
                animationSpec = tween(if (reduced) 0 else 140), label = "railsTop",
            )
            val heroBottom = animateDpAsState(
                targetValue = if (peekLayout) maxHeight - railsTopTarget + 1.2.r
                    else railContentHeight?.plus(4.5.r) ?: 30.6.r,
                animationSpec = tween(if (reduced) 0 else 140), label = "heroPosition",
            )
            TvHeroBanner(
                item = hero,
                state = state,
                height = maxHeight,
                contentBottom = heroBottom,
                heroEntry = heroEntry,
                onDown = { focusManager.moveFocus(FocusDirection.Down) || runCatching { railsEntry.requestFocus(); true }.getOrDefault(false) },
                pageCount = if (focusedItem == null) heroItems.size else 0,
                page = page,
                trailerOn = homeActive && state.heroTrailer && trailer == null && focusSettled,
                trailerFor = { viewModel.heroTrailerStream(it, background = true) },
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
                    .then(if (peekLayout) Modifier.onFocusChanged { if (it.hasFocus) railContentHeight = null } else Modifier)
                    .tvScrollTop { if (reduced) rails.scrollToItem(0) else rails.animateScrollToItem(0) },
            )
            val railsArea: @Composable (@Composable () -> Unit) -> Unit = { content ->
                if (peekLayout) TvPeekScrollArea(peek, content) else TvScrollArea(inset = 0.r, content)
            }
            railsArea {
                LazyColumn(
                    Modifier.fillMaxSize().padding(start = TvMenuClosed).tvTopInset(railsTop)
                        .focusRequester(railsEntry).focusGroup()
                        .onKeyEvent { ev ->
                            // Cima na primeira fileira sobe para o destaque. Entre fileiras segue o foco normal.
                            if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionUp) {
                                focusManager.moveFocus(FocusDirection.Up) || runCatching { heroEntry.requestFocus(); true }.getOrDefault(false)
                            } else false
                        },
                    state = rails,
                    // Superior. Sobra embaixo o tamanho da espiada, assim a última fileira também sobe
                    // até o alto da área e a faixa de baixo fica vazia (nada para espiar).
                    contentPadding = PaddingValues(bottom = if (peekLayout) peek else 0.r),
                ) {
                    homeRails(state, viewModel, onItemClick, onFocus = { pendingFocus.value = it }, onTrailer = ::playCard,
                        collections = collections, onFolderClick = onFolderClick,
                        pageHeight = if (peekLayout) Dp.Unspecified else pageHeight, onPageContentHeight = { railContentHeight = it })
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

/** Por que a Início não tem nada para mostrar. */
private enum class HomeEmpty { FEED, SUGGESTIONS_OFF, ALL_HIDDEN }

/**
 * Início sem nada na tela. Sem rede ou sem chave TMDB oferece Tentar de novo. Com as sugestões do JOTV
 * desligadas e sem addon, ou com tudo escondido, oferece o botão que resolve. O foco nasce no primeiro.
 */
@Composable
private fun TvHomeFeedEmpty(
    why: HomeEmpty,
    hasTmdbKey: Boolean,
    onRetry: () -> Unit,
    onSuggestions: () -> Unit,
    onExplore: () -> Unit,
    onOrganize: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(why) { runCatching { firstFocus.requestFocus() } }
    Column(
        Modifier.fillMaxSize().padding(start = TvMenuClosed).padding(horizontal = tvPad),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            when (why) {
                HomeEmpty.FEED -> if (hasTmdbKey) "Não deu para carregar agora" else "Configure sua chave TMDB"
                else -> "Sua Início está vazia"
            },
            color = TextPrimary, fontSize = 1.8.rs, fontWeight = FontWeight.Bold, fontFamily = DisplayFont,
        )
        Text(
            when (why) {
                HomeEmpty.FEED -> if (hasTmdbKey) "Confira sua conexão e tente de novo." else "Vá em Configurações e cole sua chave TMDB para ver o catálogo."
                HomeEmpty.SUGGESTIONS_OFF -> "As sugestões do JOTV estão desligadas e não há addon."
                HomeEmpty.ALL_HIDDEN -> "Todas as fileiras estão escondidas."
            },
            color = TextTertiary, fontSize = 1.1.rs, modifier = Modifier.padding(top = 0.6.r).widthIn(max = 44.r),
        )
        Spacer(Modifier.height(1.4.r))
        Row(horizontalArrangement = Arrangement.spacedBy(0.8.r)) {
            when (why) {
                HomeEmpty.FEED -> TvButton("Tentar de novo", onRetry, Modifier.focusRequester(firstFocus))
                HomeEmpty.SUGGESTIONS_OFF -> {
                    TvButton("Ligar sugestões", onSuggestions, Modifier.focusRequester(firstFocus), primary = true)
                    TvButton("Explorar addons", onExplore)
                    TvButton("Organizar a Início", onOrganize)
                }
                HomeEmpty.ALL_HIDDEN -> TvButton("Organizar a Início", onOrganize, Modifier.focusRequester(firstFocus), primary = true)
            }
        }
    }
}

/** Destaque (.home-hero). Arte com troca suave, trailer mudo depois de 5 s parado, texto e botões. */
@Composable
private fun TvHeroBanner(
    item: MovieItem?,
    state: MoviesHomeViewModel.UiState,
    height: Dp,
    contentBottom: State<Dp>,
    heroEntry: FocusRequester,
    onDown: () -> Boolean,
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
            Crossfade(backdrop, animationSpec = tween(if (reduced) 0 else 140), label = "heroBg") { url ->
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
        Box(Modifier.fillMaxSize().heroScrim())
        if (item == null) return@Box
        Column(
            Modifier.align(Alignment.BottomStart)
                .offset { IntOffset(0, -contentBottom.value.roundToPx()) }
                .padding(start = TvMenuClosed + tvPad)
                .width(45.r),
        ) {
            TvTitleArtwork(item.title, state.heroLogos[item.tmdbId].orEmpty(),
                Modifier.padding(bottom = 1.1.r), hero = true)
            TvMetaRow(
                rating = item.rating, cert = item.certification.ifBlank { state.heroCerts[item.tmdbId].orEmpty() },
                year = item.year, extra = listOf(if (item.type == MediaType.SERIES) "Série" else "Filme"), genres = item.genres,
            )
            if (item.overview.isNotBlank()) Text(
                item.overview, color = TextSecondary, fontSize = 1.15.rs, lineHeight = 1.84.rs, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.r).widthIn(max = 40.r),
            )
            Row(
                Modifier.padding(top = 1.4.r).offset(x = (-0.2).r)
                    .onKeyEvent { ev -> ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionDown && onDown() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeroAction("Assistir", onWatch, primary = true, modifier = Modifier.focusRequester(heroEntry))
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

/**
 * Escurece o destaque para o texto ler. São dois degradês, um da esquerda para a direita e outro de baixo para cima,
 * que antes eram duas camadas do tamanho da tela por cima da arte. Aqui viram uma imagem de 128 por 72 com a soma dos
 * dois, desenhada de uma vez, e a placa de vídeo pinta uma camada a menos por quadro.
 */
private fun Modifier.heroScrim(): Modifier = drawBehind {
    drawImage(
        HeroScrim, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
        filterQuality = androidx.compose.ui.graphics.FilterQuality.Low,
    )
}

private val HeroScrim: ImageBitmap by lazy {
    val w = 128
    val h = 72
    val px = IntArray(w * h)
    for (y in 0 until h) {
        val av = scrimAlpha((y + 0.5f) / h, VERTICAL_SCRIM_TOP, floatArrayOf(0f, .37f, .68f, .92f), floatArrayOf(0f, .4f, .94f, 1f))
        for (x in 0 until w) {
            val ah = scrimAlpha((x + 0.5f) / w, 0f, floatArrayOf(0f, .28f, .6f, .82f), floatArrayOf(.93f, .75f, .2f, 0f))
            val a = 1f - (1f - ah) * (1f - av)
            px[y * w + x] = Color(Background.red, Background.green, Background.blue, a).toArgb()
        }
    }
    android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** O degradê vertical cobre os 30% de baixo para cima, a parte de cima da tela fica sem ele. */
private const val VERTICAL_SCRIM_TOP = 0.3f

/** Opacidade do degradê em [pos] (0 a 1 da tela). [from] é onde o degradê começa e as paradas valem sobre o resto. */
private fun scrimAlpha(pos: Float, from: Float, stops: FloatArray, alphas: FloatArray): Float {
    if (pos < from) return 0f
    val t = (pos - from) / (1f - from)
    if (t <= stops.first()) return alphas.first()
    for (i in 1 until stops.size) {
        if (t <= stops[i]) {
            val k = (t - stops[i - 1]) / (stops[i] - stops[i - 1])
            return alphas[i - 1] + (alphas[i] - alphas[i - 1]) * k
        }
    }
    return alphas.last()
}

@Composable
private fun HeroAction(
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    busy: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val scale = tvFocusScaleState(focused, 1.025f)
    val shape = RoundedCornerShape(50)
    val foreground = if (primary) PrimaryActionText else Color.White
    Row(
        modifier.padding(0.2.r).tvFocusLayer(scale)
            .height(3.5.r)
            .then(if (focused) Modifier.border(0.18.r, Color.White, shape) else Modifier)
            .clip(shape).then(if (primary) Modifier.background(PrimaryActionBrush) else Modifier.background(Color.White.copy(alpha = .14f)))
            .onFocusChanged { focused = it.isFocused }
            .tvClick({ if (!busy) onClick() })
            .padding(horizontal = 1.7.r),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) com.joabtv.app.ui.common.SpinningIcon(tint = foreground, size = 1.5.r)
        else Icon(
            if (primary) Icons.Default.PlayArrow else Icons.Default.Theaters,
            null, tint = foreground, modifier = Modifier.size(1.5.r),
        )
        Spacer(Modifier.width(0.6.r))
        Text(label, color = foreground, fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 1.1.rs, maxLines = 1)
    }
}

/** Fileiras da Início, na ordem do webOS. */
private fun androidx.compose.foundation.lazy.LazyListScope.homeRails(
    state: MoviesHomeViewModel.UiState,
    viewModel: MoviesHomeViewModel,
    onItemClick: (MovieItem) -> Unit,
    onFocus: (MovieItem) -> Unit,
    onTrailer: (MoviesHomeViewModel.TrailerCard) -> Unit,
    collections: List<MediaCollection>,
    onFolderClick: (String, String) -> Unit,
    pageHeight: Dp,
    onPageContentHeight: (Dp) -> Unit,
) {
    state.layout.forEach { row ->
        when (row.system) {
            SystemRow.CONTINUE -> if (state.continueWatching.isNotEmpty()) item(key = row.key) {
                TvRail(row.displayTitle, preserveColumnOnVertical = true, pageHeight = pageHeight, onPageContentHeight = onPageContentHeight) {
                    items(state.continueWatching, key = { posterKey(it) }) { item ->
                        TvContinueCard(
                            item, { onItemClick(item) }, artwork = state.continueArtwork[posterKey(item)],
                            progress = state.continueProgress[item.tmdbId], remaining = state.continueRemaining[item.tmdbId],
                            episode = state.continueEpisode[item.tmdbId].orEmpty(), onFocus = { onFocus(item) },
                        )
                    }
                }
            }
            SystemRow.NEW_EPISODES -> if (state.newEpisodes.isNotEmpty()) item(key = row.key) {
                // Lista e mapas guardados, assim a fileira não recompõe (e os pôsteres com ela) a cada rodada da Início.
                val episodes = state.newEpisodes
                val shown = remember(episodes) { episodes.map { it.item } }
                val badges = remember(episodes) { episodes.associate { it.item.tmdbId to it.badge } }
                val subs = remember(episodes) { episodes.associate { it.item.tmdbId to it.sub } }
                PosterRail(
                    row.displayTitle, shown, onItemClick, onFocus,
                    badges = badges, subs = subs,
                    pageHeight = pageHeight, onPageContentHeight = onPageContentHeight,
                )
            }
            SystemRow.RECOMMENDED -> if (state.recommended.isNotEmpty()) item(key = row.key) {
                PosterRail(row.title.ifBlank { "Porque você assistiu ${state.recommendedBecause}" }, state.recommended, onItemClick, onFocus,
                    pageHeight = pageHeight, onPageContentHeight = onPageContentHeight)
            }
            SystemRow.TRENDING_MOVIES -> if (state.trendingMovies.isNotEmpty()) item(key = row.key) {
                PosterRail(row.displayTitle, state.trendingMovies, onItemClick, onFocus,
                    pageHeight = pageHeight, onPageContentHeight = onPageContentHeight,
                    seeAll = { viewModel.openCatalog(CatalogQuery(row.displayTitle, CatalogType.MOVIE, CatalogEndpoint.TRENDING)) })
            }
            SystemRow.TRENDING_TV -> if (state.trendingTv.isNotEmpty()) item(key = row.key) {
                PosterRail(row.displayTitle, state.trendingTv, onItemClick, onFocus,
                    pageHeight = pageHeight, onPageContentHeight = onPageContentHeight,
                    seeAll = { viewModel.openCatalog(CatalogQuery(row.displayTitle, CatalogType.SERIES, CatalogEndpoint.TRENDING)) })
            }
            SystemRow.NOW_PLAYING -> if (state.nowPlaying.isNotEmpty()) item(key = row.key) {
                PosterRail(row.displayTitle, state.nowPlaying, onItemClick, onFocus,
                    pageHeight = pageHeight, onPageContentHeight = onPageContentHeight,
                    seeAll = { viewModel.openCatalog(CatalogQuery(row.displayTitle, CatalogType.MOVIE, CatalogEndpoint.NOW_PLAYING)) })
            }
            SystemRow.RELEASES -> if (state.releases2026.isNotEmpty()) item(key = row.key) {
                val title = row.title.ifBlank { "Lançamentos ${state.releasesYear}" }
                PosterRail(title, state.releases2026, onItemClick, onFocus,
                    pageHeight = pageHeight, onPageContentHeight = onPageContentHeight,
                    seeAll = { viewModel.openCatalog(CatalogQuery(title, CatalogType.MOVIE, genreId = null, sort = MoviesHomeViewModel.SORT_RELEASE_MOVIE, year = state.releasesYear)) })
            }
            SystemRow.TRAILERS -> if (state.trailersLoading || state.latestTrailers.isNotEmpty()) item(key = row.key) {
                TrailersRail(state, viewModel, onFocus, onTrailer, row.displayTitle, pageHeight, onPageContentHeight)
            }
            SystemRow.GENRES -> {
                items(state.homeSections, key = { it.id }) { sec ->
                    PosterRail(sec.title, sec.items, onItemClick, onFocus,
                        pageHeight = pageHeight, onPageContentHeight = onPageContentHeight,
                        onNearEnd = { viewModel.loadMoreSectionItems(sec.id) }, seeAll = { viewModel.openCatalog(sec.query) })
                }
                if (state.isLoadingSections) item(key = "more") { TvSpinner() }
            }
            null -> if (row.collectionId != null) {
                collections.firstOrNull { it.id == row.collectionId }?.takeIf { it.folders.isNotEmpty() }?.let { col ->
                    item(key = row.key) {
                        TvRailPage(pageHeight, onContentHeight = onPageContentHeight) {
                            TvScaled {
                                com.joabtv.app.ui.collections.CollectionFolderRow(col, row.displayTitle, eyebrow = null) { f -> onFolderClick(col.id, f.id) }
                            }
                        }
                    }
                }
            } else state.addonRows.firstOrNull { it.ref.key == row.key }?.takeIf { it.loading || it.items.isNotEmpty() }?.let { ar ->
                item(key = row.key) {
                    if (ar.items.isEmpty()) TvSkeletonRail()
                    else PosterRail(ar.title, ar.items, onItemClick, onFocus,
                        pageHeight = pageHeight, onPageContentHeight = onPageContentHeight,
                        onNearEnd = if (ar.more) ({ viewModel.loadAddonRow(ar.ref.key) }) else null)
                }
            }
        }
    }
}

@Composable
private fun PosterRail(
    title: String,
    items: List<MovieItem>,
    onItemClick: (MovieItem) -> Unit,
    onFocus: (MovieItem) -> Unit,
    progress: Map<Int, Float>? = null,
    subs: Map<Int, String>? = null,
    badges: Map<Int, String>? = null,
    onNearEnd: (() -> Unit)? = null,
    seeAll: (() -> Unit)? = null,
    pageHeight: Dp = Dp.Unspecified,
    onPageContentHeight: (Dp) -> Unit = {},
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
    TvRail(title, state = row, preserveColumnOnVertical = true, pageHeight = pageHeight, onPageContentHeight = onPageContentHeight) {
        items(items.size, key = { "${items[it].type}:${items[it].tmdbId}" }) { index ->
            val item = items[index]
            TvPoster(
                item = item, onClick = { onItemClick(item) }, width = 12.5.r,
                progress = progress?.get(item.tmdbId), subtitle = subs?.get(item.tmdbId), badge = badges?.get(item.tmdbId),
                onFocus = { onFocus(item) }, qualityNeighbors = { items.subList(index + 1, minOf(items.size, index + 8)) },
            )
        }
        if (seeAll != null) item(key = "seeAll") { SeeAllCard(seeAll) }
    }
}

/** Cartão "Ver tudo" no fim da fileira (.see-all). */
@Composable
private fun SeeAllCard(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val s = tvFocusScaleState(focused, 1.06f)
    val shape = RoundedCornerShape(0.9.r)
    Column(
        Modifier.width(12.5.r).aspectRatio(2f / 3f).tvFocusLayer(s).clip(shape).background(BackgroundCard)
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
    title: String = "Últimos trailers",
    pageHeight: Dp = Dp.Unspecified,
    onPageContentHeight: (Dp) -> Unit = {},
) {
    TvRailPage(pageHeight, onContentHeight = onPageContentHeight) {
        TvRailHead(title)
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
            else -> TvRow(preserveColumnOnVertical = true) {
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
    val brush = com.joabtv.app.ui.common.rememberShimmer()
    Column(Modifier.padding(top = 1.3.r)) {
        Box(Modifier.padding(start = tvPad).size(16.r, 1.8.r).clip(RoundedCornerShape(0.5.r)).shimmer(brush))
        Row(Modifier.padding(start = tvPad, top = 1.r), horizontalArrangement = Arrangement.spacedBy(1.25.r)) {
            repeat(8) { Box(Modifier.size(12.5.r, 18.75.r).clip(RoundedCornerShape(0.9.r)).shimmer(brush)) }
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
            GridCells.Fixed(tvColumns(8)), Modifier.fillMaxSize().background(Background), state = gs,
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
                TvPoster(it, { onItemClick(it) }, width = Dp.Unspecified)
            }
            if (state.gridLoadingMore) item(span = { GridItemSpan(maxLineSpan) }) { TvSpinner() }
        }
    }
}
