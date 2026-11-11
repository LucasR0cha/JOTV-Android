package com.joabtv.app.ui.live

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.joabtv.app.data.live.LiveChannel
import com.joabtv.app.data.live.LiveBrowseFilter
import com.joabtv.app.data.live.LiveChannelBrowser
import com.joabtv.app.data.live.LiveProgram
import com.joabtv.app.data.live.LiveSource
import com.joabtv.app.data.live.LiveStream
import com.joabtv.app.data.live.LiveTvRepository
import com.joabtv.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import android.view.KeyEvent as AndroidKey

/**
 * Player de canal ao vivo. Toca HLS ou TS com os cabeçalhos que a lista pede, mostra o que está
 * passando e troca de canal pela lista lateral ou pelas setas.
 */
@OptIn(UnstableApi::class)
@Composable
fun LivePlayerScreen(channelId: String, onBack: () -> Unit, viewModel: LivePlayerViewModel = hiltViewModel()) {
    com.joabtv.app.ui.common.ImmersiveFullscreen()
    com.joabtv.app.ui.common.KeepScreenOn()
    val context = LocalContext.current
    val st by viewModel.state.collectAsStateWithLifecycle()
    val isTv = com.joabtv.app.ui.common.LocalIsTv.current
    var currentId by rememberSaveable { mutableStateOf(channelId) }
    val channel = st.channels.firstOrNull { it.id == currentId }
    var browserFilter by remember { mutableStateOf(viewModel.playerFilter()) }
    val browsed = remember(st.channels, st.sources, st.epg, st.favorites, st.recents, browserFilter) {
        LiveChannelBrowser.filter(st.channels, st.sources, st.epg, st.favorites, st.recents, browserFilter, System.currentTimeMillis())
    }
    val siblings = browsed.ifEmpty { st.channels }
    val numbers = remember(st.channels) { LiveChannelBrowser.numbers(st.channels) }
    var overlay by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf(false) }
    var wideGuide by remember { mutableStateOf(false) }
    var choice by remember { mutableStateOf<String?>(null) }
    var sourceOptions by remember { mutableStateOf<List<LiveStream>>(emptyList()) }
    var sourceBusy by remember { mutableStateOf(false) }
    var sourceError by remember { mutableStateOf<String?>(null) }
    var sourceLabel by remember { mutableStateOf("") }
    var aspect by rememberSaveable { mutableStateOf("fit") }
    var lastId by rememberSaveable { mutableStateOf(st.recents.firstOrNull { it != channelId }.orEmpty()) }
    var zapOffset by remember { mutableIntStateOf(0) }
    var digits by remember { mutableStateOf("") }
    var zapJob by remember { mutableStateOf<Job?>(null) }
    var digitJob by remember { mutableStateOf<Job?>(null) }
    var typing by remember { mutableStateOf(false) }
    var controlsFocused by remember { mutableStateOf(false) }
    var uiActivity by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var programInfo by remember { mutableStateOf<Pair<LiveChannel, LiveProgram>?>(null) }
    val rootFocus = remember { FocusRequester() }
    val channelsFocus = remember { FocusRequester() }
    var failed by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var retries by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var errorMessage by remember { mutableStateOf("A fonte não respondeu. Tente de novo ou mude de canal.") }
    val playbackScope = rememberCoroutineScope()
    var playJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }

    val activity = context as? android.app.Activity
    val player = remember {
        ExoPlayer.Builder(context).build().apply { playWhenReady = true }
    }
    // Ao sair, solta o player e volta a tela para o automático (caso tenha travado em paisagem).
    DisposableEffect(Unit) {
        onDispose {
            player.release()
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
    }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) { failed = false; retries = 0 }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { paused = !player.playWhenReady }
            override fun onTracksChanged(value: Tracks) { tracks = value }
            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition(); player.prepare(); player.play()
                } else failed = true
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }

    fun play(c: LiveChannel, selected: LiveStream? = null) {
        uiActivity++
        playJob?.cancel()
        player.stop()
        failed = false
        buffering = true
        errorMessage = "A fonte não respondeu. Tente de novo ou mude de canal."
        playJob = playbackScope.launch {
            try {
                val stream = selected ?: viewModel.stream(c, retries)
                sourceLabel = stream.label
                val http = OkHttpDataSource.Factory(com.joabtv.app.data.remote.AppDns.mediaClient)
                    .setDefaultRequestProperties(stream.headers)
                    .setUserAgent(stream.headers["User-Agent"] ?: "Mozilla/5.0 (Linux; Android) JOTV")
                val item = MediaItem.Builder().setUri(stream.url).apply {
                    val path = stream.url.substringBefore('?').lowercase()
                    if (path.endsWith(".m3u8") || path.endsWith(".m3u") || (c.addonBase.isNotBlank() && !Regex("\\.(ts|mp4|mkv|webm)$").containsMatchIn(path))) setMimeType(MimeTypes.APPLICATION_M3U8)
                }.build()
                player.setMediaSource(DefaultMediaSourceFactory(http).createMediaSource(item))
                player.playWhenReady = true
                player.prepare()
                viewModel.watched(c.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage = e.message ?: "O addon não respondeu. Tente novamente."
                failed = true
            }
        }
    }

    LaunchedEffect(channel?.id) { retries = 0; channel?.let { play(it) } }
    LaunchedEffect(channel?.id, retries, buffering) {
        if (buffering && !failed) { delay(30_000); failed = true }
    }
    // Canal caiu. Tenta de novo duas vezes antes de mostrar o aviso.
    LaunchedEffect(failed) {
        if (failed && retries < 2 && channel != null) {
            delay(2_500); retries++; play(channel)
        }
    }
    LaunchedEffect(overlay, panel, choice, paused, currentId, uiActivity, st.favorites) {
        if (overlay && !panel && choice == null && !paused) {
            delay(5_000)
            if (isTv) rootFocus.requestFocus()
            overlay = false
        }
    }
    LaunchedEffect(Unit) { if (isTv) rootFocus.requestFocus() }

    fun cancelPending() { zapJob?.cancel(); digitJob?.cancel(); zapOffset = 0; digits = "" }
    fun tune(id: String) {
        if (st.channels.none { it.id == id }) return
        cancelPending()
        if (id != currentId) { lastId = currentId; currentId = id; retries = 0 }
        panel = false; choice = null; overlay = true
        if (isTv) rootFocus.requestFocus()
    }
    fun recall() { uiActivity++; if (lastId.isNotBlank()) tune(lastId) else android.widget.Toast.makeText(context, "Escolha outro canal para poder voltar ao anterior.", android.widget.Toast.LENGTH_SHORT).show() }
    fun step(delta: Int) {
        if (siblings.isEmpty()) return
        uiActivity++
        digitJob?.cancel(); digits = ""
        zapOffset = (zapOffset + delta).coerceIn(-200, 200)
        val target = LiveChannelBrowser.step(siblings, currentId, zapOffset) ?: return
        zapJob?.cancel()
        zapJob = playbackScope.launch { delay(600); if (zapOffset != 0) tune(target.id) else cancelPending() }
    }
    fun confirmNumber() {
        val target = digits.toIntOrNull()?.let { LiveChannelBrowser.byNumber(st.channels, it, browserFilter.sourceId, st.favorites) }
        cancelPending()
        if (target != null) tune(target.id) else android.widget.Toast.makeText(context, "Número de canal não encontrado.", android.widget.Toast.LENGTH_SHORT).show()
    }
    fun digit(d: Int) {
        if (d == 0 && digits.isEmpty()) { recall(); return }
        if (digits.length >= 5) return
        zapJob?.cancel(); zapOffset = 0
        digits += d.toString()
        digitJob?.cancel(); digitJob = playbackScope.launch { delay(1_500); confirmNumber() }
    }
    fun showPanel(wide: Boolean) { cancelPending(); wideGuide = wide; panel = true; overlay = true }
    fun closeLayer() {
        when {
            choice != null -> choice = null
            digits.isNotEmpty() || zapOffset != 0 -> cancelPending()
            panel -> { panel = false; overlay = true; if (isTv) rootFocus.requestFocus() }
            overlay && !failed -> { overlay = false; if (isTv) rootFocus.requestFocus() }
            else -> onBack()
        }
    }
    fun pause() { if (player.playWhenReady) player.pause() else player.play(); paused = !player.playWhenReady; overlay = true }
    fun sources() {
        if (sourceBusy || channel == null) return
        sourceBusy = true; sourceError = null; sourceOptions = emptyList(); choice = "sources"
        val id = channel.id
        playbackScope.launch {
            try { val list = viewModel.streams(channel); if (id == currentId) sourceOptions = list }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { sourceError = "O addon não disponibilizou fontes para este canal agora." }
            finally { sourceBusy = false }
        }
    }
    BackHandler(onBack = ::closeLayer)

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) uiActivity++
                if (!isTv || event.type != KeyEventType.KeyDown || typing || choice != null) return@onPreviewKeyEvent false
                val k = event.nativeKeyEvent.keyCode
                val d = when (k) { in AndroidKey.KEYCODE_0..AndroidKey.KEYCODE_9 -> k - AndroidKey.KEYCODE_0; in AndroidKey.KEYCODE_NUMPAD_0..AndroidKey.KEYCODE_NUMPAD_9 -> k - AndroidKey.KEYCODE_NUMPAD_0; else -> -1 }
                when {
                    d >= 0 -> { digit(d); true }
                    k == AndroidKey.KEYCODE_ESCAPE -> { closeLayer(); true }
                    digits.isNotEmpty() && k in listOf(AndroidKey.KEYCODE_DPAD_CENTER, AndroidKey.KEYCODE_ENTER) -> { confirmNumber(); true }
                    k in listOf(AndroidKey.KEYCODE_CHANNEL_UP, AndroidKey.KEYCODE_PAGE_UP) -> { step(1); true }
                    k in listOf(AndroidKey.KEYCODE_CHANNEL_DOWN, AndroidKey.KEYCODE_PAGE_DOWN) -> { step(-1); true }
                    k == AndroidKey.KEYCODE_LAST_CHANNEL -> { recall(); true }
                    k == AndroidKey.KEYCODE_GUIDE -> { showPanel(true); true }
                    k == AndroidKey.KEYCODE_INFO -> { choice = "info"; true }
                    k == AndroidKey.KEYCODE_MEDIA_PLAY_PAUSE -> { pause(); true }
                    panel -> false
                    k == AndroidKey.KEYCODE_DPAD_DOWN -> { showPanel(false); true }
                    k == AndroidKey.KEYCODE_DPAD_UP -> { overlay = true; playbackScope.launch { withFrameNanos { }; runCatching { channelsFocus.requestFocus() } }; true }
                    k in listOf(AndroidKey.KEYCODE_DPAD_CENTER, AndroidKey.KEYCODE_ENTER) && !controlsFocused -> { showPanel(false); true }
                    else -> false
                }
            }
            .focusable()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { overlay = !overlay; if (!overlay) panel = false },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    isFocusable = false
                    isFocusableInTouchMode = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                }
            },
            update = { view -> view.resizeMode = when (aspect) { "fill" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM; "stretch" -> AspectRatioFrameLayout.RESIZE_MODE_FILL; else -> AspectRatioFrameLayout.RESIZE_MODE_FIT }; view.scaleX = if (aspect == "zoom") 1.33f else 1f; view.scaleY = if (aspect == "zoom") 1.33f else 1f },
            modifier = Modifier.fillMaxSize(),
        )

        if (buffering && !failed) CircularProgressIndicator(color = Accent, strokeWidth = 3.dp, modifier = Modifier.align(Alignment.Center).size(44.dp))

        if (failed && retries >= 2) Column(
            Modifier.align(Alignment.Center).padding(24.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xCC0B0F17)).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Canal fora do ar agora", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
            Text(errorMessage, color = TextSecondary, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.joabtv.app.ui.common.JoabButton("Tentar de novo", { retries = 0; channel?.let { play(it) } }, icon = Icons.Default.Refresh, compact = true)
                com.joabtv.app.ui.common.JoabButton("Fontes", ::sources, busy = sourceBusy, compact = true)
                com.joabtv.app.ui.common.JoabButton("Canais", { showPanel(false) }, compact = true)
            }
        }

        AnimatedVisibility(overlay && !panel, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                // Topo com o canal.
                Row(
                    Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", tint = Color.White) }
                    if (!channel?.logo.isNullOrBlank()) AsyncImage(channel!!.logo, null, contentScale = ContentScale.Fit, modifier = Modifier.size(40.dp))
                    Column(Modifier.weight(1f)) {
                        Text(channel?.name ?: "Canal", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(st.sources.firstOrNull { it.id == channel?.sourceId }?.name, channel?.group?.takeIf { it.isNotBlank() }).joinToString(" · "), color = TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val fav = channel != null && channel.id in st.favorites
                    IconButton(onClick = { channel?.let { viewModel.toggleFavorite(it.id) } }, modifier = Modifier.size(48.dp)) {
                        Icon(if (fav) Icons.Default.Star else Icons.Default.StarBorder, if (fav) "Tirar dos favoritos" else "Favoritar", tint = if (fav) Gold else Color.White)
                    }
                    IconButton(onClick = {
                        val cur = activity?.requestedOrientation
                        activity?.requestedOrientation =
                            if (cur == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER
                            else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        overlay = true
                    }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.ScreenRotation, "Girar a tela", tint = Color.White)
                    }
                    IconButton(onClick = { showPanel(false) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.List, "Lista de canais", tint = Color.White)
                    }
                }
                // Base com agora e a seguir, e as setas de canal.
                Column(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000))))
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val (n, nx) = channel?.let { viewModel.nowNext(it.id, now) } ?: (null to null)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "AO VIVO", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(LiveRed).padding(horizontal = 7.dp, vertical = 2.dp),
                            )
                            Text(n?.title ?: "Sem guia para este canal", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (n != null) {
                            com.joabtv.app.ui.common.ProgressBar(n.progress(now), Modifier.fillMaxWidth(0.7f), height = 3.dp)
                            Text("${hhmm(n.start)} até ${hhmm(n.stop)}", color = TextSecondary, fontSize = 12.sp)
                        }
                        nx?.let { Text("A seguir ${hhmm(it.start)} ${it.title}", color = TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    LazyRow(Modifier.fillMaxWidth().onFocusChanged { controlsFocused = it.hasFocus }.focusGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { com.joabtv.app.ui.common.FilterChipPill("Canais", false, { showPanel(false) }, Modifier.focusRequester(channelsFocus), Icons.Default.LiveTv) }
                        item { com.joabtv.app.ui.common.FilterChipPill("Guia", false, { showPanel(true) }, icon = Icons.Default.GridView) }
                        item { com.joabtv.app.ui.common.FilterChipPill("Canal anterior", false, { step(-1) }, icon = Icons.Default.SkipPrevious) }
                        item { com.joabtv.app.ui.common.FilterChipPill("Próximo canal", false, { step(1) }, icon = Icons.Default.SkipNext) }
                        item { com.joabtv.app.ui.common.FilterChipPill("Último canal", false, ::recall, icon = Icons.Default.History) }
                        item { com.joabtv.app.ui.common.FilterChipPill(if (paused) "Reproduzir" else "Pausar", false, ::pause, icon = if (paused) Icons.Default.PlayArrow else Icons.Default.Pause) }
                        item { com.joabtv.app.ui.common.FilterChipPill("Voltar ao vivo", false, { uiActivity++; player.seekToDefaultPosition(); player.play(); paused = false }, icon = Icons.Default.LiveTv) }
                        item { com.joabtv.app.ui.common.FilterChipPill("Fontes", false, ::sources, icon = Icons.Default.Layers, busy = sourceBusy) }
                        item { com.joabtv.app.ui.common.FilterChipPill("Áudio", false, { choice = "audio" }, icon = Icons.Default.VolumeUp) }
                        item { com.joabtv.app.ui.common.FilterChipPill("Legendas", false, { choice = "text" }, icon = Icons.Default.Subtitles) }
                        item { com.joabtv.app.ui.common.FilterChipPill("Tela", false, { choice = "aspect" }, icon = Icons.Default.AspectRatio) }
                        item { com.joabtv.app.ui.common.FilterChipPill("Informações", false, { choice = "info" }, icon = Icons.Default.Info) }
                        item { com.joabtv.app.ui.common.FilterChipPill("Recarregar", false, { if (!buffering) { retries = 0; channel?.let { play(it) } } }, icon = Icons.Default.Refresh, busy = buffering) }
                    }
                }
            }
        }

        AnimatedVisibility(panel, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterEnd)) {
            LivePlayerChannelPanel(
                state = st, rows = browsed, filter = browserFilter, currentId = currentId, numbers = numbers,
                now = now, wide = wideGuide, buffering = buffering,
                onFilter = { browserFilter = it; viewModel.savePlayerFilter(it) }, onPlay = ::tune,
                onFavorite = viewModel::toggleFavorite, onWide = { wideGuide = !wideGuide },
                onClose = { panel = false; overlay = true; if (isTv) rootFocus.requestFocus() },
                onSource = { choice = "filterSource" }, onGroup = { choice = "filterGroup" }, onTyping = { typing = it },
                onProgram = { c, p -> programInfo = c to p; choice = "program" },
            )
        }

        if (digits.isNotEmpty() || zapOffset != 0) {
            val target = if (digits.isNotEmpty()) digits.toIntOrNull()?.let { LiveChannelBrowser.byNumber(st.channels, it, browserFilter.sourceId, st.favorites) }
                else LiveChannelBrowser.step(siblings, currentId, zapOffset)
            Text(
                if (digits.isNotEmpty()) "$digits  ${target?.name ?: "Canal não encontrado"}" else "${target?.let { numbers[it.id] } ?: ""}  ${target?.name.orEmpty()}",
                color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopStart).padding(24.dp).background(Surface2, RoundedCornerShape(12.dp)).padding(16.dp),
            )
        }
    }

    choice?.let { menu ->
        val options = when (menu) {
            "filterSource" -> listOf(LivePlayerOption("Todas as fontes", selected = browserFilter.sourceId.isBlank()) { browserFilter = browserFilter.copy(sourceId = "", group = ""); viewModel.savePlayerFilter(browserFilter); choice = null }) +
                st.sources.filter { it.enabled }.map { source -> LivePlayerOption(source.name, selected = browserFilter.sourceId == source.id) { browserFilter = browserFilter.copy(sourceId = source.id, group = ""); viewModel.savePlayerFilter(browserFilter); choice = null } }
            "filterGroup" -> listOf(LivePlayerOption("Todos os grupos", selected = browserFilter.group.isBlank()) { browserFilter = browserFilter.copy(group = ""); viewModel.savePlayerFilter(browserFilter); choice = null }) +
                st.channels.filter { browserFilter.sourceId.isBlank() || it.sourceId == browserFilter.sourceId }.flatMap(LiveChannelBrowser::groups).distinct().map { group -> LivePlayerOption(group, selected = browserFilter.group == group) { browserFilter = browserFilter.copy(group = group); viewModel.savePlayerFilter(browserFilter); choice = null } }
            "sources" -> sourceOptions.map { stream -> LivePlayerOption(stream.label, selected = sourceLabel == stream.label) { choice = null; channel?.let { retries = 0; play(it, stream) } } }
            "aspect" -> listOf("fit" to "Ajustar", "fill" to "Preencher", "zoom" to "Aproximar", "stretch" to "Esticar").map { (key, label) -> LivePlayerOption(label, selected = aspect == key) { aspect = key; choice = null } }
            "audio", "text" -> {
                val type = if (menu == "audio") C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
                val choices = tracks.groups.filter { it.type == type }.flatMap { group -> (0 until group.length).filter { group.isTrackSupported(it) }.map { index ->
                    val format = group.getTrackFormat(index)
                    LivePlayerOption(format.label ?: format.language ?: "Faixa ${index + 1}", selected = group.isTrackSelected(index)) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index)).build()
                        choice = null
                    }
                } }
                if (menu == "text") listOf(LivePlayerOption("Sem legendas", selected = choices.none { it.selected }) { player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build(); choice = null }) + choices else choices
            }
            "program" -> programInfo?.let { (c, p) -> listOf(
                LivePlayerOption(p.title, p.desc.ifBlank { "A fonte não informou uma descrição." }),
                LivePlayerOption("${hhmm(p.start)} até ${hhmm(p.stop)}", c.name),
                LivePlayerOption("Assistir canal") { tune(c.id) },
            ) }.orEmpty()
            else -> listOf(
                LivePlayerOption(channel?.name ?: "Canal", st.sources.firstOrNull { it.id == channel?.sourceId }?.name.orEmpty()),
                LivePlayerOption("Fonte selecionada", sourceLabel.ifBlank { "Transmissão principal" }),
                LivePlayerOption("Resolução", player.videoSize.let { if (it.width > 0 && it.height > 0) "${it.width} × ${it.height}" else "Ainda não informada" }),
                LivePlayerOption("Reprodução", if (buffering) "Carregando" else if (paused) "Pausada" else "Em andamento"),
            )
        }
        val title = when (menu) { "filterSource" -> "Fontes de canais"; "filterGroup" -> "Grupos"; "sources" -> "Fontes da transmissão"; "aspect" -> "Proporção da imagem"; "audio" -> "Áudio"; "text" -> "Legendas"; "program" -> "Programação"; else -> "Informações do canal" }
        LivePlayerOptions(title, options, menu == "sources" && sourceBusy, if (menu == "sources") sourceError else null) { choice = null }
    }
}

private data class LivePlayerOption(val label: String, val sub: String = "", val selected: Boolean = false, val action: (() -> Unit)? = null)

@Composable
private fun LivePlayerOptions(title: String, items: List<LivePlayerOption>, busy: Boolean, error: String?, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(.85f).widthIn(max = 680.dp).fillMaxHeight(.8f).background(BackgroundCard, RoundedCornerShape(16.dp)).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose, modifier = Modifier.focusRequester(focus)) { Icon(Icons.Default.Close, "Fechar", tint = TextPrimary) }
            }
            if (busy) Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp), color = Accent)
                Text("Buscando fontes…", color = TextSecondary)
            }
            error?.let { Text(it, color = TextSecondary, modifier = Modifier.padding(vertical = 12.dp)) }
            if (!busy && error == null && items.isEmpty()) Text("A transmissão não informou outras faixas.", color = TextSecondary, modifier = Modifier.padding(vertical = 12.dp))
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(items) { _, item ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (item.selected) AccentSoft else Surface2)
                        .then(if (item.action != null) Modifier.clickable { item.action.invoke() } else Modifier)
                        .padding(16.dp)) {
                        Text(item.label + if (item.selected) " · Selecionada" else "", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                        if (item.sub.isNotBlank()) Text(item.sub, color = TextSecondary, fontSize = 15.sp, modifier = Modifier.padding(top = 5.dp))
                    }
                }
            }
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
}

@Composable
private fun LivePlayerChannelPanel(
    state: LiveTvRepository.State, rows: List<LiveChannel>, filter: LiveBrowseFilter, currentId: String, numbers: Map<String, Int>,
    now: Long, wide: Boolean, buffering: Boolean, onFilter: (LiveBrowseFilter) -> Unit, onPlay: (String) -> Unit,
    onFavorite: (String) -> Unit, onWide: () -> Unit, onClose: () -> Unit, onSource: () -> Unit, onGroup: () -> Unit,
    onTyping: (Boolean) -> Unit, onProgram: (LiveChannel, LiveProgram) -> Unit,
) {
    val listState = rememberLazyListState()
    val selectedFocus = remember { FocusRequester() }
    var focusedId by remember { mutableStateOf(currentId) }
    var searching by remember { mutableStateOf(false) }
    var searchHasFocus by remember { mutableStateOf(false) }
    val activeIndex = rows.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
    val initialId = rows.getOrNull(activeIndex)?.id
    val focused = rows.firstOrNull { it.id == focusedId } ?: rows.firstOrNull()
    val names = remember(state.sources) { state.sources.associate { it.id to it.name } }
    LaunchedEffect(rows.map { it.id }, wide) {
        if (rows.isNotEmpty() && !searchHasFocus) {
            listState.scrollToItem(activeIndex)
            withFrameNanos { }
            runCatching { selectedFocus.requestFocus() }
        }
    }
    DisposableEffect(Unit) { onDispose { onTyping(false) } }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        val width = if (wide) maxWidth * .96f else minOf(maxWidth * .92f, 460.dp)
        Column(Modifier.fillMaxHeight().width(width).background(BackgroundCard).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Horizontal)).padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (wide) "Guia de canais" else "Canais", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("${rows.size} canais", color = TextSecondary, fontSize = 14.sp)
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Fechar canais", tint = TextPrimary) }
            }
            LazyRow(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("all" to "Todos", "fav" to "Favoritos", "recent" to "Recentes").forEach { (key, label) -> item { com.joabtv.app.ui.common.FilterChipPill(label, filter.mode == key, { onFilter(filter.copy(mode = key)) }) } }
                item { com.joabtv.app.ui.common.FilterChipPill(names[filter.sourceId] ?: "Todas as fontes", filter.sourceId.isNotBlank(), onSource) }
                item { com.joabtv.app.ui.common.FilterChipPill(filter.group.ifBlank { "Todos os grupos" }, filter.group.isNotBlank(), onGroup) }
                item { com.joabtv.app.ui.common.FilterChipPill("Buscar", searching || filter.query.isNotBlank(), { searching = !searching }, icon = Icons.Default.Search) }
                item { com.joabtv.app.ui.common.FilterChipPill(if (wide) "Lista compacta" else "Guia completo", wide, onWide) }
                if (filter != LiveBrowseFilter()) item { com.joabtv.app.ui.common.FilterChipPill("Limpar filtros", false, { onFilter(LiveBrowseFilter()) }) }
            }
            if (searching || filter.query.isNotBlank()) OutlinedTextField(
                filter.query, { onFilter(filter.copy(query = it.take(100))) }, label = { Text("Canal ou programa") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).onFocusChanged { searchHasFocus = it.isFocused; onTyping(it.isFocused) },
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, focusedBorderColor = Accent, unfocusedBorderColor = Border, cursorColor = Accent),
            )
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (rows.isEmpty()) item { Text("Nenhum canal neste filtro. Use Limpar filtros para ver todos.", color = TextSecondary, modifier = Modifier.padding(16.dp)) }
                    itemsIndexed(rows, key = { _, c -> c.id }) { _, c ->
                        var focusedRow by remember(c.id) { mutableStateOf(false) }
                        val active = c.id == currentId
                        val programs = state.epg[c.id].orEmpty()
                        val current = programs.firstOrNull { it.isNow(now) }
                        val next = programs.firstOrNull { it.start > (current?.start ?: now) && !it.isNow(now) }
                        Row(Modifier.fillMaxWidth().heightIn(min = 88.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (active) AccentSoft else Surface2)
                            .border(if (focusedRow) 2.dp else 0.dp, if (focusedRow) Color.White else Color.Transparent, RoundedCornerShape(12.dp))
                            .then(if (c.id == initialId) Modifier.focusRequester(selectedFocus) else Modifier)
                            .onFocusChanged { focusedRow = it.isFocused; if (it.isFocused) focusedId = c.id }
                            .clickable { onPlay(c.id) }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("${numbers[c.id] ?: ""}", color = TextSecondary, fontSize = 13.sp, modifier = Modifier.widthIn(min = 24.dp))
                            if (c.logo.isNotBlank()) AsyncImage(c.logo, null, contentScale = ContentScale.Fit, modifier = Modifier.size(42.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(c.name, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(current?.title ?: "Programação não informada", color = TextSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(names[c.sourceId].orEmpty() + next?.let { " · A seguir ${hhmm(it.start)} ${it.title}" }.orEmpty(), color = TextTertiary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (active) Text(if (buffering) "Abrindo…" else "No ar", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                IconButton(onClick = { onFavorite(c.id) }, modifier = Modifier.size(48.dp)) { Icon(if (c.id in state.favorites) Icons.Default.Star else Icons.Default.StarBorder, if (c.id in state.favorites) "Tirar dos favoritos" else "Favoritar ${c.name}", tint = if (c.id in state.favorites) Gold else TextSecondary) }
                            }
                        }
                    }
                }
                if (wide && focused != null) {
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Text(focused.name, color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text(listOfNotNull(names[focused.sourceId], focused.group.takeIf { it.isNotBlank() }).joinToString(" · "), color = TextSecondary, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
                        com.joabtv.app.ui.common.JoabButton(if (focused.id == currentId) "Continuar assistindo" else "Assistir canal", { onPlay(focused.id) }, compact = true)
                        Text("Programação", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 14.dp))
                        val programs = state.epg[focused.id].orEmpty().filter { it.stop > now }
                        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (programs.isEmpty()) item { Text("A fonte ainda não informou a programação deste canal.", color = TextSecondary) }
                            itemsIndexed(programs) { _, p ->
                                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (p.isNow(now)) AccentSoft else Surface2).clickable { onProgram(focused, p) }.padding(14.dp)) {
                                    Text("${hhmm(p.start)} até ${hhmm(p.stop)}" + if (p.isNow(now)) " · Agora" else "", color = TextSecondary, fontSize = 14.sp)
                                    Text(p.title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(top = 5.dp))
                                    if (p.desc.isNotBlank()) Text(p.desc, color = TextSecondary, fontSize = 14.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
