package com.joabtv.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.*
import androidx.navigation.compose.*
import com.joabtv.app.domain.model.MediaType
import com.joabtv.app.domain.model.MovieItem
import com.joabtv.app.ui.common.vodNavItems
import com.joabtv.app.ui.common.DeviceLayout
import com.joabtv.app.ui.theme.Background
import com.joabtv.app.ui.movies.*
import com.joabtv.app.ui.settings.SettingsScreen

private sealed class MoviesRoute(val route: String) {
    object Home     : MoviesRoute("movies_home")
    object Detail   : MoviesRoute("movies_detail/{embedId}/{tmdbId}/{type}") {
        // embedId vazio (item vindo de Minha Lista/histórico, sem addon de origem) quebra o
        // path do Nav (regex de argumento não casa segmento vazio = IllegalArgumentException
        // ao navegar). Ninguém lê embedId de verdade (tmdbId é a chave real em tudo), então o
        // próprio tmdbId serve de placeholder estável.
        fun create(embedId: String, tmdbId: Int, type: MediaType) =
            "movies_detail/${embedId.ifBlank { tmdbId.toString() }}/$tmdbId/${type.name}"
    }
    // Player usa ID numérico simples. A URL real fica no playerCache (evita problema de encoding)
    object Player   : MoviesRoute("movies_player/{playId}") {
        fun create(playId: Int) = "movies_player/$playId"
    }
    object Search   : MoviesRoute("movies_search")
    object Settings : MoviesRoute("movies_settings")
    object Addons : MoviesRoute("movies_addons")
    object AddonStore : MoviesRoute("movies_addon_store")
    object MinhaLista : MoviesRoute("movies_minha_lista")
    object Descobrir : MoviesRoute("movies_descobrir")
    object Downloads : MoviesRoute("movies_downloads")
    object Profiles : MoviesRoute("movies_profiles")
}

/** Na TV a tela entra na escala do webOS ([com.joabtv.app.ui.tv.TvScaled]). No celular fica igual. */
@Composable
private fun tvScale(isTV: Boolean, content: @Composable () -> Unit) {
    if (isTV) com.joabtv.app.ui.tv.TvScaled(content) else content()
}

// Cache compartilhado — MovieItem e PlayerData sobrevivem à serialização do NavController.
// Chave por tipo+tmdbId: filme e série têm IDs do TMDB em espaços separados e podem repetir.
private val movieCache  = mutableMapOf<String, MovieItem>()
private fun cacheKey(tmdbId: Int, type: MediaType) = "${type.name}:$tmdbId"
private typealias MoviePlayerSource = com.joabtv.app.ui.movies.MoviePlayerSource
private data class PlayerData(
    val embedUrl: String, val title: String,
    val tmdbId: Int = 0, val type: String = "MOVIE", val posterUrl: String = "",
    val lastEpisode: String = "", val startPositionMs: Long = 0,
    val backdropUrl: String = "",
    // Episódio (séries) — p/ o botão "Próximo episódio" no player
    val season: Int = 0, val episode: Int = 0, val episodeTotal: Int = 0,
    // Todas as fontes do filme/episódio — p/ trocar dentro do player
    val sources: List<MoviePlayerSource> = emptyList(),
)
private val playerCache = mutableMapOf<Int, PlayerData>()
private var playerIdCounter = 0

@Composable
fun MoviesNavigation(
    deviceLayout: DeviceLayout = DeviceLayout.PHONE,
    onRestartApp: () -> Unit,
) {
    val isTV = deviceLayout == DeviceLayout.TV
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val isFullscreen = currentRoute?.startsWith("movies_player") == true ||
        currentRoute?.startsWith("movies_detail") == true ||
        currentRoute == MoviesRoute.Search.route ||
        currentRoute == MoviesRoute.Settings.route ||
        currentRoute == MoviesRoute.Profiles.route ||
        currentRoute == MoviesRoute.AddonStore.route
    val showTabletRail = deviceLayout == DeviceLayout.TABLET && !isFullscreen

    fun navigateToPlayer(
        embedUrl: String, title: String,
        tmdbId: Int = 0, type: String = "MOVIE", posterUrl: String = "",
        lastEpisode: String = "", startPositionMs: Long = 0, backdropUrl: String = "",
        season: Int = 0, episode: Int = 0, episodeTotal: Int = 0,
        sources: List<MoviePlayerSource> = emptyList(),
    ) {
        val playId = ++playerIdCounter
        playerCache[playId] = PlayerData(
            embedUrl, title, tmdbId, type, posterUrl, lastEpisode, startPositionMs, backdropUrl,
            season, episode, episodeTotal, sources,
        )
        navController.navigate(MoviesRoute.Player.create(playId))
    }

    // Navegação da barra inferior (mantém único topo e restaura estado)
    fun navTab(route: String) = navController.navigate(route) {
        popUpTo(MoviesRoute.Home.route) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    var menuOpen by remember { mutableStateOf(false) }
    val navHost: @Composable () -> Unit = {
        // Troca de tela de 220 ms com opacidade e deslocamento curto, como o .screen-enter do webOS.
        val reduced = com.joabtv.app.ui.common.rememberReducedMotion()
        val dur = if (reduced) 0 else 220
        NavHost(
            navController, startDestination = MoviesRoute.Home.route,
            enterTransition = {
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(dur)) +
                    androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(dur)) { if (reduced) 0 else 48 }
            },
            exitTransition = { androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(if (reduced) 0 else 150)) },
            popEnterTransition = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(dur)) },
            popExitTransition = { androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(if (reduced) 0 else 150)) },
        ) {

            composable(MoviesRoute.Home.route) {
                val openDetail: (MovieItem) -> Unit = { item ->
                    movieCache[cacheKey(item.tmdbId, item.type)] = item
                    navController.navigate(MoviesRoute.Detail.create(item.embedId, item.tmdbId, item.type))
                }
                // TV tem Início própria no desenho do webOS. Celular segue com a dele.
                if (isTV) com.joabtv.app.ui.tv.TvHomeScreen(onItemClick = openDetail, navigationOpen = menuOpen)
                else MoviesHomeScreen(
                    onItemClick     = openDetail,
                    onSearchClick   = { navController.navigate(MoviesRoute.Search.route) },
                    onSettingsClick = { navController.navigate(MoviesRoute.Settings.route) },
                    onLibraryClick  = { navController.navigate(MoviesRoute.MinhaLista.route) },
                )
            }

            composable(
                MoviesRoute.Detail.route,
                arguments = listOf(
                    navArgument("embedId") { type = NavType.StringType },
                    navArgument("tmdbId")  { type = NavType.IntType },
                    navArgument("type")    { type = NavType.StringType },
                ),
            ) { entry ->
                val embedId   = entry.arguments?.getString("embedId") ?: return@composable
                val tmdbId    = entry.arguments?.getInt("tmdbId") ?: 0
                val mediaType = runCatching {
                    MediaType.valueOf(entry.arguments?.getString("type") ?: "MOVIE")
                }.getOrDefault(MediaType.MOVIE)
                val stub = movieCache[cacheKey(tmdbId, mediaType)] ?: MovieItem(
                    tmdbId = tmdbId, embedId = embedId, title = "",
                    overview = "", posterUrl = "", backdropUrl = "",
                    year = "", rating = 0.0, type = mediaType,
                )
                val detailVm: com.joabtv.app.ui.movies.MovieDetailViewModel = androidx.hilt.navigation.compose.hiltViewModel()
                // Episódio abre o player sem URL. O player busca as fontes do episódio nos addons.
                val onPlayEpisodeCb: (Int, Int, Int, Int) -> Unit = { tid, season, episode, epTotal ->
                    detailVm.markEpisodeWatched(season, episode)
                    navigateToPlayer(
                        "", stub.title, tmdbId = tid, type = "SERIES",
                        posterUrl = stub.posterUrl, lastEpisode = "T$season:E$episode", backdropUrl = stub.backdropUrl,
                        season = season, episode = episode, episodeTotal = epTotal,
                    )
                }
                val onPlayDirectCb: (String, Long, String, List<MoviePlayerSource>, Int, Int, Int) -> Unit = { url, startPos, lastEp, sources, season, episode, epTotal ->
                    navigateToPlayer(
                        url, stub.title, tmdbId = stub.tmdbId, type = stub.type.name,
                        posterUrl = stub.posterUrl, lastEpisode = lastEp, startPositionMs = startPos, backdropUrl = stub.backdropUrl,
                        season = season, episode = episode, episodeTotal = epTotal,
                        sources = sources,
                    )
                }
                val onBackCb: () -> Unit = { navController.popBackStack() }
                val onRelatedCb: (MovieItem) -> Unit = { rel ->
                    movieCache[cacheKey(rel.tmdbId, rel.type)] = rel
                    navController.navigate(MoviesRoute.Detail.create(rel.embedId, rel.tmdbId, rel.type))
                }
                KidsGate(tmdbId, mediaType.name, onBack = onBackCb) {
                    if (isTV) com.joabtv.app.ui.tv.TvDetailScreen(
                        item = stub,
                        onPlayEpisode = onPlayEpisodeCb,
                        onPlayDirect = onPlayDirectCb,
                        onRelatedClick = onRelatedCb,
                        viewModel = detailVm,
                    ) else MovieDetailScreen(
                        item = stub,
                        onPlayEpisode = onPlayEpisodeCb,
                        onPlayDirect = onPlayDirectCb,
                        onBack = onBackCb,
                        onRelatedClick = onRelatedCb,
                        viewModel = detailVm,
                    )
                }
            }

            composable(
                MoviesRoute.Player.route,
                arguments = listOf(navArgument("playId") { type = NavType.IntType }),
            ) { entry ->
                val playId = entry.arguments?.getInt("playId") ?: -1
                val data   = playerCache[playId]
                if (data == null) {
                    Box(
                        Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Erro: playId=$playId cache=${playerCache.keys}", color = androidx.compose.ui.graphics.Color.White)
                        TextButton(onClick = { navController.popBackStack() }, modifier = Modifier.align(Alignment.BottomCenter)) {
                            Text("Voltar", color = com.joabtv.app.ui.theme.AccentFocus)
                        }
                    }
                    return@composable
                }
                KidsGate(data.tmdbId, data.type, onBack = { navController.popBackStack() }) {
                MoviePlayerScreen(
                    embedUrl    = data.embedUrl,
                    title       = data.title,
                    tmdbId      = data.tmdbId,
                    type        = data.type,
                    posterUrl   = data.posterUrl,
                    lastEpisode = data.lastEpisode,
                    startPositionMs = data.startPositionMs,
                    backdropUrl = data.backdropUrl,
                    season      = data.season,
                    episode     = data.episode,
                    episodeTotal = data.episodeTotal,
                    sources     = data.sources,
                    onBack      = { navController.popBackStack() },
                )
                }
            }

            composable(MoviesRoute.Search.route) {
                val onSearchItem: (MovieItem) -> Unit = { item ->
                    movieCache[cacheKey(item.tmdbId, item.type)] = item
                    navController.navigate(MoviesRoute.Detail.create(item.embedId, item.tmdbId, item.type))
                }
                tvScale(isTV) {
                    MediaSearchScreen(
                        onItemClick = onSearchItem,
                        onBack = { navController.popBackStack() },
                    )
                }
            }

            composable(MoviesRoute.Descobrir.route) {
                val openItem: (MovieItem) -> Unit = { item ->
                    movieCache[cacheKey(item.tmdbId, item.type)] = item
                    navController.navigate(MoviesRoute.Detail.create(item.embedId, item.tmdbId, item.type))
                }
                if (isTV) com.joabtv.app.ui.tv.TvDiscoverScreen(onItemClick = openItem)
                else DiscoverScreen(
                        onItemClick = openItem,
                        onBack = { navController.popBackStack() },
                        columns = 3,
                    )
            }

            composable(MoviesRoute.MinhaLista.route) {
                val onListItem: (MovieItem) -> Unit = { item ->
                    movieCache[cacheKey(item.tmdbId, item.type)] = item
                    navController.navigate(MoviesRoute.Detail.create(item.embedId, item.tmdbId, item.type))
                }
                if (isTV) com.joabtv.app.ui.tv.TvMyListScreen(onItemClick = { onListItem(movieCache[cacheKey(it.tmdbId, it.type)] ?: it) })
                else MinhaListaScreen(onItemClick = onListItem)
            }

            composable(MoviesRoute.Settings.route) {
                tvScale(isTV) {
                    SettingsScreen(
                        onBack            = { navController.popBackStack() },
                        onPlatformChanged = onRestartApp,
                        onOpenAddons      = { navController.navigate(MoviesRoute.Addons.route) },
                        onOpenProfiles    = { navController.navigate(MoviesRoute.Profiles.route) },
                    )
                }
            }

            // Quem está assistindo. Escolher outro perfil remonta a navegação (key no MainActivity).
            composable(MoviesRoute.Profiles.route) {
                tvScale(isTV) { com.joabtv.app.ui.settings.ProfilesScreen(onPicked = { navController.popBackStack() }) }
            }

            // Gerenciador de addons (celular) — instala por manifest URL, ordena e ativa/desativa.
            composable(MoviesRoute.Addons.route) {
                tvScale(isTV) {
                    com.joabtv.app.ui.settings.AddonManagerScreen(
                        onBack = { navController.popBackStack() },
                        onExplore = { navController.navigate(MoviesRoute.AddonStore.route) },
                    )
                }
            }

            // Explorar addons do catálogo da comunidade (celular). A tela de TV fica para a etapa 7.
            composable(MoviesRoute.AddonStore.route) {
                tvScale(isTV) { com.joabtv.app.ui.settings.AddonStoreScreen(onBack = { navController.popBackStack() }) }
            }

            composable(MoviesRoute.Downloads.route) {
                Box(Modifier.fillMaxSize()) {
                DownloadsScreen(
                    onPlayLocal = { d ->
                        // Toca o arquivo local (file://...) pelo mesmo player. Extrai season/episode
                        // do "T1:E3" — sem isso o player trata como filme e o progresso embaralha
                        // com o de outro episódio da mesma série.
                        val m = if (d.lastEpisode.isNotBlank()) Regex("T(\\d+):E(\\d+)").find(d.lastEpisode) else null
                        navigateToPlayer(
                            d.localUri, d.title,
                            tmdbId = d.tmdbId, type = d.type, posterUrl = d.posterUrl,
                            lastEpisode = d.lastEpisode,
                            season = m?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                            episode = m?.groupValues?.get(2)?.toIntOrNull() ?: 0,
                        )
                    },
                )
                }
            }
        }
    }

    // Barra inferior do celular (Início, Descobrir, Minha Lista, Downloads, Busca e Configurações).
    // "Cinema" foi removido: apontava para a mesma rota de "Início" (destaque duplo + aba morta).
    val vodItems = vodNavItems(
        onInicio     = { navTab(MoviesRoute.Home.route) },
        onMinhaLista = { navTab(MoviesRoute.MinhaLista.route) },
        onBusca      = { navTab(MoviesRoute.Search.route) },
        onDownloads  = { navTab(MoviesRoute.Downloads.route) },
        onDescobrir  = { navTab(MoviesRoute.Descobrir.route) },
        inicioRoute     = MoviesRoute.Home.route,
        minhaListaRoute = MoviesRoute.MinhaLista.route,
        buscaRoute      = MoviesRoute.Search.route,
        downloadsRoute  = MoviesRoute.Downloads.route,
        descobrirRoute  = MoviesRoute.Descobrir.route,
    )

    // IMPORTANTE: o NavHost precisa ficar SEMPRE no mesmo slot de composição. Se ele
    // trocar de pai (ex.: dentro do Scaffold vs. direto) quando isFullscreen muda, o
    // Compose descarta e recria o NavHost, reiniciando a back stack no Home — foi a causa
    // de clicar no poster "não abrir" o detalhe (a navegação era cancelada na mesma frame).
    // Mantemos o NavHost como primeiro filho fixo de um Box e apenas sobrepomos a barra.
    // Telas da TV com o menu lateral, como as do webOS. Detalhe, player e loja ficam sem.
    val tvMenuRoutes = setOf(
        MoviesRoute.Home.route, MoviesRoute.Search.route, MoviesRoute.Descobrir.route,
        MoviesRoute.MinhaLista.route, MoviesRoute.Addons.route, MoviesRoute.Profiles.route,
        MoviesRoute.Settings.route,
    )
    val showTvMenu = isTV && currentRoute in tvMenuRoutes
    val profVm: com.joabtv.app.ui.settings.ProfilesViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val profiles by profVm.profiles.collectAsStateWithLifecycle()
    val pid by profVm.profileId.collectAsStateWithLifecycle()
    val profile = profiles.firstOrNull { it.id == pid } ?: profiles.first()

    // Foco da TV. Cada tela nova recebe o foco no conteúdo e Voltar na Início vai ao menu e
    // depois pergunta se quer sair, igual ao back() do webOS.
    val menuRequesters = remember { mutableMapOf<String, androidx.compose.ui.focus.FocusRequester>() }
    fun menuRequester(route: String) = menuRequesters.getOrPut(route) { androidx.compose.ui.focus.FocusRequester() }
    val contentFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    var contentHasFocus by remember { mutableStateOf(false) }
    var askExit by remember { mutableStateOf(false) }
    // Hora da última tecla. O foco automático não briga com quem já está usando o menu.
    var lastKeyAt by remember { mutableLongStateOf(0L) }
    // Última seta para a esquerda ou Voltar. Só essas abrem o menu de propósito.
    var lastMenuKeyAt by remember { mutableLongStateOf(0L) }
    if (isTV) {
        LaunchedEffect(currentRoute) {
            // Tela nova (pelo menu ou pelo Voltar) começa no conteúdo, como o showEntry do webOS.
            // ponytail: vigia por até 20 s (Início espera a rede, e o Compose às vezes devolve o foco
            // ao menu quando a tela de trás some). Para na primeira tecla depois da troca de tela.
            val start = android.os.SystemClock.uptimeMillis()
            repeat(80) {
                kotlinx.coroutines.delay(250)
                if (lastKeyAt > start) return@LaunchedEffect
                if (!contentHasFocus) runCatching { contentFocus.requestFocus() }
            }
        }
        // Só a Início, registrado AQUI (antes do navHost()) de propósito: a Início tem seu
        // próprio BackHandler interno pra fechar a grade do "Ver tudo" (TvHomeScreen.kt), e
        // esse precisa continuar ganhando prioridade sobre este (o dispatcher chama o callback
        // mais recente primeiro, e o da Início é registrado depois do nosso por estar dentro
        // do navHost()). Não dá pra juntar com o handler de "fora da Início" lá embaixo sem
        // quebrar essa ordem.
        androidx.activity.compose.BackHandler(enabled = currentRoute == MoviesRoute.Home.route) {
            if (menuOpen) askExit = true
            else runCatching { menuRequester(MoviesRoute.Home.route).requestFocus() }
        }
        if (askExit) {
            val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
            TvExitDialog(onExit = { activity?.finish() }, onStay = { askExit = false })
        }
    }

    com.joabtv.app.ui.common.ProvideTvMode(isTV) {
    Box(
        Modifier.fillMaxSize().background(Background)
            .then(if (isTV) Modifier.onPreviewKeyEvent {
                if (it.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) {
                    lastKeyAt = android.os.SystemClock.uptimeMillis()
                    val kc = it.nativeKeyEvent.keyCode
                    if (kc == android.view.KeyEvent.KEYCODE_DPAD_LEFT || kc == android.view.KeyEvent.KEYCODE_BACK) lastMenuKeyAt = lastKeyAt
                }
                false
            } else Modifier),
    ) {
        // Reserva p/ a barra: altura do conteúdo (~62dp) + inset da navigation bar do
        // sistema, para a última linha do grid não ficar escondida atrás da barra.
        val barReserve = 64.dp + WindowInsets.navigationBars.asPaddingValues()
            .calculateBottomPadding()
        // Na TV a barra vira o menu lateral próprio (TvSideMenu).
        val hideBar = isFullscreen || isTV || showTabletRail
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    when {
                        showTvMenu && currentRoute != MoviesRoute.Home.route -> Modifier.padding(start = TvMenuClosed)
                        showTabletRail -> Modifier.padding(start = 80.dp)
                        hideBar -> Modifier
                        else -> Modifier.padding(bottom = barReserve)
                    }
                )
                .then(
                    if (isTV) Modifier
                        .onFocusChanged { contentHasFocus = it.hasFocus }
                        .focusRequester(contentFocus)
                        .focusGroup()
                    else Modifier
                ),
        ) {
            navHost()
        }
        if (isTV) {
            // Fora da Início, com o menu aberto. Registrado DEPOIS do navHost() de propósito:
            // o NavHost da navigation-compose 2.8.3 registra o próprio OnBackPressedCallback de
            // pop quando a pilha tem mais de 1 tela (sempre o caso aqui, pilha [Home, X]), e o
            // dispatcher chama o callback mais recente primeiro. Registrado antes do navHost(),
            // o pop dele ganhava do nosso e Voltar saía trocando de tela (ou nada, dependendo da
            // versão) em vez de só fechar o menu e devolver o foco ao conteúdo.
            androidx.activity.compose.BackHandler(enabled = currentRoute != MoviesRoute.Home.route && menuOpen) {
                runCatching { contentFocus.requestFocus() }
            }
        }
        if (showTvMenu) {
            val reduced = com.joabtv.app.ui.common.rememberReducedMotion()
            val scrim by androidx.compose.animation.core.animateFloatAsState(
                if (menuOpen) .62f else 0f,
                com.joabtv.app.ui.common.joabTween(220, reduced), label = "menuScrim",
            )
            Box(
                Modifier.fillMaxSize().graphicsLayer { alpha = scrim }
                    .background(androidx.compose.ui.graphics.Color(0xFF02060E))
                    .focusProperties { canFocus = false }
                    .then(if (menuOpen) Modifier.clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                    ) { runCatching { contentFocus.requestFocus() } } else Modifier),
            )
            // Selecionar a própria rota ativa só fecha o menu (launchSingleTop não muda currentRoute,
            // então nada reabriria o foco no conteúdo sozinho).
            val go: (String) -> () -> Unit = { r -> {
                if (r == currentRoute) runCatching { contentFocus.requestFocus() } else navTab(r)
            } }
            TvSideMenu(
                main = listOf(
                    TvMenuItem(MoviesRoute.Home.route, "Início", androidx.compose.material.icons.Icons.Default.Home, go(MoviesRoute.Home.route)),
                    TvMenuItem(MoviesRoute.Search.route, "Buscar", androidx.compose.material.icons.Icons.Default.Search, go(MoviesRoute.Search.route)),
                    TvMenuItem(MoviesRoute.Descobrir.route, "Descobrir", androidx.compose.material.icons.Icons.Default.Explore, go(MoviesRoute.Descobrir.route)),
                    TvMenuItem(MoviesRoute.MinhaLista.route, "Minha Lista", androidx.compose.material.icons.Icons.Default.BookmarkBorder, go(MoviesRoute.MinhaLista.route)),
                    TvMenuItem(MoviesRoute.Addons.route, "Addons", androidx.compose.material.icons.Icons.Default.Extension, go(MoviesRoute.Addons.route)),
                ),
                bottom = listOf(
                    TvMenuItem(MoviesRoute.Profiles.route, profile.name, null, go(MoviesRoute.Profiles.route), avatar = profile),
                    TvMenuItem(MoviesRoute.Settings.route, "Configurações", androidx.compose.material.icons.Icons.Default.Settings, go(MoviesRoute.Settings.route)),
                ),
                currentRoute = currentRoute,
                requesters = tvMenuRoutes.associateWith { menuRequester(it) },
                open = menuOpen,
                onOpenChange = { open ->
                    menuOpen = open
                    // O Compose às vezes joga o foco no menu quando o item focado some (perfil
                    // removido, janela fechada). Sem seta para a esquerda nem Voltar, volta ao conteúdo.
                    if (open && android.os.SystemClock.uptimeMillis() - lastMenuKeyAt > 600)
                        runCatching { contentFocus.requestFocus() }
                },
            )
        }
        if (!hideBar) {
            Box(Modifier.align(Alignment.BottomCenter)) {
                MoviesBottomBar(
                    currentRoute = currentRoute,
                    items = vodItems,
                    onSettings = { navTab(MoviesRoute.Settings.route) },
                    settingsRoute = MoviesRoute.Settings.route,
                    profile = profile,
                    onProfile = { navController.navigate(MoviesRoute.Profiles.route) },
                )
            }
        }
        if (showTabletRail) {
            MoviesTabletRail(
                currentRoute = currentRoute,
                items = vodItems,
                onSettings = { navTab(MoviesRoute.Settings.route) },
                settingsRoute = MoviesRoute.Settings.route,
                profile = profile,
                onProfile = { navController.navigate(MoviesRoute.Profiles.route) },
            )
        }
    }
    }
}

@Composable
private fun MoviesTabletRail(
    currentRoute: String?,
    items: List<com.joabtv.app.ui.common.PhoneNavItem>,
    onSettings: () -> Unit,
    settingsRoute: String,
    profile: com.joabtv.app.data.local.Profile,
    onProfile: () -> Unit,
) {
    val colors = NavigationRailItemDefaults.colors(
        selectedIconColor = com.joabtv.app.ui.theme.Accent,
        selectedTextColor = com.joabtv.app.ui.theme.TextPrimary,
        unselectedIconColor = com.joabtv.app.ui.theme.TextSecondary,
        unselectedTextColor = com.joabtv.app.ui.theme.TextSecondary,
        indicatorColor = com.joabtv.app.ui.theme.AccentSoft,
    )
    NavigationRail(
        modifier = Modifier.fillMaxHeight().width(80.dp),
        containerColor = com.joabtv.app.ui.theme.BackgroundElevated,
    ) {
        Column(
            Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items.forEach { item ->
                NavigationRailItem(
                    selected = currentRoute == item.route, onClick = item.onClick,
                    icon = { Icon(item.icon, null, Modifier.size(26.dp)) },
                    label = { Text(item.label, fontSize = 11.sp, maxLines = 2) },
                    colors = colors,
                )
            }
            NavigationRailItem(
                selected = currentRoute == settingsRoute, onClick = onSettings,
                icon = { Icon(Icons.Default.Settings, null, Modifier.size(26.dp)) },
                label = { Text("Configurações", fontSize = 11.sp, maxLines = 2) },
                colors = colors,
            )
            NavigationRailItem(
                selected = false, onClick = onProfile,
                icon = { com.joabtv.app.ui.settings.ProfileAvatar(profile, 28.dp) },
                label = { Text("Perfil", fontSize = 11.sp) }, colors = colors,
            )
        }
    }
}

/**
 * A barra compacta divide a largura entre as ações e mantém os ícones dentro da tela.
 */
@Composable
private fun MoviesBottomBar(
    currentRoute: String?,
    items: List<com.joabtv.app.ui.common.PhoneNavItem>,
    onSettings: () -> Unit,
    settingsRoute: String,
    profile: com.joabtv.app.data.local.Profile,
    onProfile: () -> Unit,
) {
    val entries: @Composable RowScope.() -> Unit = {
        items.forEach { item ->
            MoviesBottomBarItem(item.icon, item.label, currentRoute == item.route, item.onClick, Modifier.weight(1f))
        }
        MoviesBottomBarItem(Icons.Default.Settings, "Configurações", currentRoute == settingsRoute, onSettings, Modifier.weight(1f))
        Box(
            Modifier.weight(1f).height(56.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .clickable(onClickLabel = "Trocar perfil", role = androidx.compose.ui.semantics.Role.Button, onClick = onProfile),
            contentAlignment = Alignment.Center,
        ) { com.joabtv.app.ui.settings.ProfileAvatar(profile, 32.dp) }
    }
    val bg = androidx.compose.ui.graphics.Color(0xF5080808)
        androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().background(bg)) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(com.joabtv.app.ui.theme.Border.copy(alpha = 0.6f)))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp).navigationBarsPadding(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) { entries() }
        }
}

@Composable
private fun MoviesBottomBarItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = com.joabtv.app.ui.theme.Accent
    Box(
        modifier
            .height(56.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .background(if (selected) com.joabtv.app.ui.theme.AccentSoft else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(role = androidx.compose.ui.semantics.Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, contentDescription = label,
            tint = if (selected) accent else com.joabtv.app.ui.theme.TextTertiary,
            modifier = Modifier.size(26.dp),
        )
        // Traço do item ativo, como o .side-item.is-active do webOS.
        if (selected) Box(
            Modifier.align(Alignment.BottomCenter).size(22.dp, 3.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp)).background(accent),
        )
    }
}

/**
 * Tela "Minha Lista" do modo Filmes & Séries.
 *
 * Duas seções. "Continuar assistindo" (recentlyPlayed) num rail horizontal com barra de
 * progresso, selo do episódio e tempo restante. "Salvos" (watchlist) num grid de 3 colunas
 * com filtro por tipo. Nas duas o título fica ABAIXO da arte, nunca sobre o pôster, então
 * nada some no fundo claro da imagem nem corta no meio da palavra.
 *
 * Clicar reconstrói o [MovieItem] a partir da entidade (mesclando com o movieCache quando
 * houver) e navega ao detalhe via [onItemClick]. Segurar remove, com confirmação.
 */
@Composable
private fun MinhaListaScreen(
    onItemClick: (MovieItem) -> Unit,
) {
    val vm: com.joabtv.app.ui.movies.MinhaListaViewModel =
        androidx.hilt.navigation.compose.hiltViewModel()
    val watchlist by vm.watchlist.collectAsStateWithLifecycle()
    val recent by vm.recentlyPlayed.collectAsStateWithLifecycle()

    // Itens pendentes de remoção (segurar → confirma), um por seção.
    var pendingRemove by remember { mutableStateOf<com.joabtv.app.data.local.entity.MovieHistoryEntity?>(null) }
    var pendingUnsave by remember { mutableStateOf<com.joabtv.app.data.local.entity.WatchlistEntity?>(null) }
    // Filtro do grid de salvos. "" = tudo.
    var filter by remember { mutableStateOf("") }

    pendingRemove?.let { h ->
        ConfirmRemoveDialog(
            title = "Remover de Continuar assistindo?",
            body = h.title,
            onConfirm = { vm.removeFromContinue(h.tmdbId, h.type); pendingRemove = null },
            onDismiss = { pendingRemove = null },
        )
    }
    pendingUnsave?.let { w ->
        ConfirmRemoveDialog(
            title = "Tirar da Minha Lista?",
            body = w.title,
            onConfirm = { vm.removeFromWatchlist(w.tmdbId, w.type); pendingUnsave = null },
            onDismiss = { pendingUnsave = null },
        )
    }

    // Reconstrói um MovieItem completo: usa o do cache (tem embedId/backdrop/overview)
    // quando existir; senão monta um stub mínimo a partir da entidade salva.
    fun rebuild(tmdbId: Int, type: MediaType, title: String, posterUrl: String, year: String): MovieItem =
        movieCache[cacheKey(tmdbId, type)] ?: MovieItem(
            tmdbId = tmdbId,
            embedId = "",
            title = title,
            overview = "",
            posterUrl = posterUrl,
            backdropUrl = "",
            year = year,
            rating = 0.0,
            type = type,
        )

    fun typeOf(raw: String): MediaType =
        runCatching { MediaType.valueOf(raw) }.getOrDefault(MediaType.MOVIE)

    val hasBothTypes = remember(watchlist) {
        watchlist.any { it.type == "MOVIE" } && watchlist.any { it.type == "SERIES" }
    }
    val saved = remember(watchlist, filter) {
        if (filter.isBlank()) watchlist else watchlist.filter { it.type == filter }
    }

    androidx.compose.foundation.layout.Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background),
    ) {
        com.joabtv.app.ui.common.PageHeader(
            title = "Minha Lista",
            sub = if (watchlist.isEmpty() && recent.isEmpty()) null else "${watchlist.size} salvos  ·  ${recent.size} em andamento",
        )
        val pad = com.joabtv.app.ui.common.pagePadding()
        val perRow = ((androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp - 84) / 140).coerceIn(3, 8)

        if (watchlist.isEmpty() && recent.isEmpty()) {
            // Estado vazio premium centralizado.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                com.joabtv.app.ui.common.JoabEmptyState(
                    icon = androidx.compose.material.icons.Icons.Default.BookmarkBorder,
                    title = "Sua lista está vazia",
                    message = "Salve filmes e séries no detalhe para vê-los aqui. Os títulos que você começar a assistir aparecem em \"Continuar assistindo\".",
                )
            }
            return@Column
        }

        androidx.compose.foundation.lazy.LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = WindowInsets.navigationBars.asPaddingValues(),
        ) {
            // ── (C) Continuar assistindo — rail horizontal ───────────────────
            if (recent.isNotEmpty()) {
                item {
                    com.joabtv.app.ui.common.SectionHeader(
                        title = "Continuar assistindo",
                        eyebrow = "Recentes",
                        action = { CountPill(recent.size) },
                    )
                }
                item {
                    androidx.compose.foundation.lazy.LazyRow(
                        contentPadding = PaddingValues(horizontal = pad),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(
                            recent,
                            key = { "${it.tmdbId}:${it.type}" },
                        ) { h ->
                            val pct = if (h.durationMs > 0L)
                                (h.positionMs.toFloat() / h.durationMs).coerceIn(0f, 1f) else null
                            LibraryPoster(
                                title = h.title,
                                posterUrl = h.posterUrl,
                                subtitle = remainingLabel(h.positionMs, h.durationMs)
                                    ?: h.lastEpisode.ifBlank { null },
                                badge = h.lastEpisode.ifBlank { null },
                                progress = pct,
                                modifier = Modifier.width(128.dp),
                                onClick = {
                                    onItemClick(rebuild(h.tmdbId, typeOf(h.type), h.title, h.posterUrl, ""))
                                },
                                onLongClick = { pendingRemove = h }, // segurar → remover
                            )
                        }
                    }
                }
            }

            // ── (B) Minha Lista — grid 3 colunas ─────────────────────────────
            item {
                com.joabtv.app.ui.common.SectionHeader(
                    title = "Salvos",
                    eyebrow = "Minha Lista",
                    action = { CountPill(watchlist.size) },
                )
            }
            // Filtro só aparece quando há os dois tipos, senão é chip inútil na tela.
            if (hasBothTypes) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = pad, end = pad, bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        com.joabtv.app.ui.common.FilterChipPill("Tudo", filter.isBlank(), onClick = { filter = "" })
                        com.joabtv.app.ui.common.FilterChipPill("Filmes", filter == "MOVIE", onClick = { filter = "MOVIE" })
                        com.joabtv.app.ui.common.FilterChipPill("Séries", filter == "SERIES", onClick = { filter = "SERIES" })
                    }
                }
            }
            if (saved.isEmpty()) {
                item {
                    EmptySavedHint(
                        if (watchlist.isEmpty())
                            "Nada salvo ainda. Abra um título e toque no marcador para guardá-lo aqui."
                        else
                            "Nenhum título salvo nesse filtro."
                    )
                }
            }
            // Linhas de 3 colunas dentro da LazyColumn (evita aninhar grid rolável).
            val rows = saved.chunked(perRow)
            items(rows) { row ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = pad, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    row.forEach { w ->
                        LibraryPoster(
                            title = w.title,
                            posterUrl = w.posterUrl,
                            subtitle = w.year.ifBlank { null },
                            badge = null,
                            progress = null,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onItemClick(rebuild(w.tmdbId, typeOf(w.type), w.title, w.posterUrl, w.year))
                            },
                            onLongClick = { pendingUnsave = w }, // segurar → tirar da lista
                        )
                    }
                    // Preenche colunas faltantes p/ manter o alinhamento da última linha.
                    repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/**
 * Card da biblioteca: pôster limpo (arte sem texto por cima) + título e subtítulo ABAIXO.
 * Resolve o problema antigo de nome ilegível sobre pôster claro. [progress] desenha a barra
 * no rodapé da arte e [badge] o selo do episódio no canto superior direito.
 */
@Composable
private fun LibraryPoster(
    title: String,
    posterUrl: String,
    subtitle: String?,
    badge: String?,
    progress: Float?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    androidx.compose.foundation.layout.Column(modifier) {
        com.joabtv.app.ui.common.MediaPosterCard(
            title = title,
            imageUrl = posterUrl,
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = Modifier.fillMaxWidth(),
            cleanPoster = true,
            progress = progress,
            badge = badge?.let { { com.joabtv.app.ui.common.Badge(label = it) } },
        )
        com.joabtv.app.ui.common.PosterCaption(title, subtitle)
    }
}

/** Contador da seção (pílula discreta ao lado do título). */
@Composable
private fun CountPill(count: Int) {
    Text(
        if (count == 1) "1 título" else "$count títulos",
        color = com.joabtv.app.ui.theme.TextTertiary,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
            .background(androidx.compose.ui.graphics.Color(0x0DFFFFFF))
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

/** Aviso dentro da seção "Salvos" quando ela está vazia (evita meia tela em branco). */
@Composable
private fun EmptySavedHint(message: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = com.joabtv.app.ui.common.pagePadding())
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .background(androidx.compose.ui.graphics.Color(0x08FFFFFF))
            .border(
                1.dp,
                com.joabtv.app.ui.theme.Border,
                androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 18.dp, vertical = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            message,
            color = com.joabtv.app.ui.theme.TextTertiary,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** Diálogo de confirmação de remoção (mesmo visual nas duas seções). */
@Composable
private fun ConfirmRemoveDialog(
    title: String,
    body: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Remover", color = com.joabtv.app.ui.theme.LiveRed)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** "Faltam 42 min" a partir do progresso salvo. null quando não há duração conhecida. */
private fun remainingLabel(positionMs: Long, durationMs: Long): String? {
    if (durationMs <= 0L || positionMs <= 0L) return null
    val left = ((durationMs - positionMs) / 60_000L).toInt()
    return when {
        left <= 0 -> "Quase no fim"
        left < 60 -> "Faltam $left min"
        else -> "Faltam ${left / 60}h ${left % 60}min"
    }
}
