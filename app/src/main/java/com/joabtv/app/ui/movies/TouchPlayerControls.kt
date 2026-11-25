package com.joabtv.app.ui.movies

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.joabtv.app.ui.common.DolbyIcon
import com.joabtv.app.ui.theme.*

/** Tamanho do botão de play do centro e meia altura do conjunto, com a folga que a parte de baixo precisa deixar. */
private val CenterPlaySize = 72.dp
private val CenterSideSize = 56.dp
internal val CenterClearance = CenterPlaySize / 2 + 8.dp

/**
 * Composição do player no celular. No centro da tela ficam voltar 10 segundos, play e pausa e avançar 10 segundos, e nas séries o
 * episódio anterior e o próximo ao lado deles. Embaixo ficam o título, a barra de tempo e as opções, e a parte de baixo nunca sobe
 * até o conjunto do meio (o título vai para o canto de cima quando não sobra altura).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TouchPlayerControls(
    title: String, subtitle: String, clock: String, resolution: String, tags: List<String>, info: String,
    isPlaying: Boolean, position: Long, duration: Long, buffered: Long, videoUrl: String,
    onToggle: () -> Unit, onBack: () -> Unit, onForward: () -> Unit, onSeek: (Long) -> Unit,
    actions: List<PlayerChipItem>, focusedIndex: Int,
    onControlsHeight: (Dp) -> Unit = {},
    loading: Boolean = false,
    episodeBusy: Boolean = false,
) {
    var more by remember { mutableStateOf(false) }
    var controlsHeight by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val font = density.fontScale.coerceAtLeast(1f)
    // Os dois botões de episódio saem das opções e vão para o centro.
    val options = actions.withIndex().filter { !it.value.main && it.value.episodeStep == 0 }
    val previous = actions.firstOrNull { it.episodeStep < 0 }
    val next = actions.firstOrNull { it.episodeStep > 0 }
    val optionState = rememberLazyListState()
    LaunchedEffect(focusedIndex, options.size) {
        val index = options.indexOfFirst { it.index == focusedIndex }
        if (index >= 0 && optionState.layoutInfo.visibleItemsInfo.none { it.index == index }) optionState.animateScrollToItem(index)
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Brush.verticalGradient(
        0f to Color.Black.copy(alpha = .5f), .24f to Color.Transparent, 1f to Color.Transparent,
    ))) {
        val safeArea = WindowInsets.safeDrawing.asPaddingValues()
        val contentWidth = maxWidth - safeArea.calculateLeftPadding(layoutDirection) - safeArea.calculateRightPadding(layoutDirection)
        val landscape = maxWidth > maxHeight
        val compact = maxHeight < 360.dp || maxHeight.value / font < 280f
        val singleRow = landscape && contentWidth.value / font >= 600f && !compact
        val optionRow = !singleRow && !compact
        val showMore = !singleRow && !optionRow && options.isNotEmpty()
        val side = if (contentWidth >= 600.dp) 24.dp else 16.dp
        val times = formatTime(position) + " / " + if (duration > 0) formatTime(duration) else "AO VIVO"
        val estimatedTimeWidth = with(density) { 12.sp.toDp() } * (times.length * .6f)
        val timeSeparate = contentWidth - side * 2 < 180.dp + (if (showMore) 56.dp else 0.dp) + estimatedTimeWidth
        val timeHeight = if (timeSeparate) with(density) { 18.sp.toDp() } + 4.dp else 0.dp
        // A fileira de baixo leva só as opções e o tempo, então tem 48dp. O play e os 10 segundos moravam nela antes.
        val fixedHeight = 48.dp + 48.dp + 20.dp + timeHeight + (if (optionRow) 56.dp else 0.dp)
        val topReserve = 64.dp + WindowInsets.displayCutout.asPaddingValues().calculateTopPadding()
        // A parte de baixo só cresce até logo abaixo do conjunto do meio (mais 12dp do espaço entre as linhas dela).
        val centerRoom = (maxHeight / 2 - CenterClearance - fixedHeight - 12.dp).coerceAtLeast(0.dp)
        val metadataHeight = minOf((maxHeight - fixedHeight - topReserve).coerceIn(0.dp, if (landscape) 140.dp else 220.dp), centerRoom)
        val titleLine = with(density) { 26.sp.toDp() }
        val tagHeight = with(density) { 15.sp.toDp() } + 6.dp
        val detailLine = with(density) { 17.sp.toDp() }
        val titleLines = if (!landscape && metadataHeight >= titleLine * 2 + tagHeight + 6.dp) 2 else 1
        var available = metadataHeight - titleLine * titleLines
        val showTags = available >= tagHeight + 6.dp
        if (showTags) available -= tagHeight + 6.dp
        val showDetail = available >= detailLine + 6.dp
        val controlsTop = (1f - controlsHeight / with(density) { maxHeight.toPx() }.coerceAtLeast(1f)).coerceIn(0f, 1f)
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            0f to Color.Transparent, (controlsTop - .14f).coerceAtLeast(0f) to Color.Transparent,
            controlsTop to Color.Black.copy(alpha = .58f), 1f to Color.Black.copy(alpha = .86f),
        )))
        // Sem altura para o título embaixo, ele sobe para o canto de cima e fica ao lado dos botões do topo.
        if (title.isNotBlank() && metadataHeight < titleLine) Text(title, color = Color.White, fontSize = 18.sp, lineHeight = 22.sp,
            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(start = side, top = 21.dp)
                .widthIn(max = (contentWidth - topActionsWidth(false) - side * 2).coerceAtLeast(120.dp)))
        // Centro da tela. Fica escondido enquanto carrega, porque a tela de carregamento cobre o meio.
        if (!loading) TouchCenterControls(
            isPlaying = isPlaying, previous = previous, next = next, busy = episodeBusy,
            gap = if (contentWidth < 420.dp) 10.dp else 22.dp,
            onToggle = onToggle, onBack = onBack, onForward = onForward,
            modifier = Modifier.align(Alignment.Center).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        )
        if (maxHeight.value / font >= 340f) Column(
            Modifier.align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(top = 66.dp, end = side), horizontalAlignment = Alignment.End,
        ) {
            Text(clock.substringBefore('·').trim(), color = Color.White, fontSize = 16.sp,
                lineHeight = 20.sp, fontWeight = FontWeight.Bold)
            val end = clock.substringAfter('·', "").trim().replaceFirst("termina", "Termina")
            if (end.isNotBlank()) Text(end, color = TextSecondary, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1)
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .onSizeChanged { controlsHeight = it.height.toFloat(); onControlsHeight(with(density) { it.height.toDp() }) }
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(horizontal = side, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (metadataHeight >= titleLine) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, color = Color.White, fontSize = 22.sp, lineHeight = 26.sp,
                    fontWeight = FontWeight.Bold, maxLines = titleLines, overflow = TextOverflow.Ellipsis)
                if (showTags) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (listOf(resolution).filter { it.isNotBlank() } + tags.take(5)).distinct().forEach { label ->
                        Row(Modifier.background(Color.White.copy(alpha = .12f), RoundedCornerShape(5.dp))
                            .padding(horizontal = 7.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (label in listOf("Dolby Vision", "Atmos", "TrueHD", "DD+")) {
                                Icon(DolbyIcon, null, tint = Color.White, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(if (label == "Dolby Vision") "Vision" else label, color = Color.White,
                                fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                val detail = listOf(subtitle, info).filter { it.isNotBlank() }.joinToString("  ·  ")
                if (showDetail && detail.isNotBlank()) Text(detail, color = TextSecondary, fontSize = 12.sp,
                    lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            PlayerSeekRow(position, duration, onSeek, videoUrl, buffered, showTimes = false)
            if (timeSeparate) Text(times, color = Color.White, fontSize = 12.sp, lineHeight = 18.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.align(Alignment.End))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (singleRow) LazyRow(Modifier.weight(1f), state = optionState,
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    items(options, key = { it.index }) { (index, action) -> TouchPlayerActionIcon(action, index == focusedIndex) }
                } else Spacer(Modifier.weight(1f))
                if (showMore) CtlButton(Icons.Default.MoreHoriz, "Opções de reprodução", 48.dp, onClick = { more = true })
                if (!timeSeparate) Text(times, color = Color.White, fontSize = 12.sp, lineHeight = 18.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            if (optionRow) LazyRow(Modifier.fillMaxWidth(), state = optionState,
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                items(options, key = { it.index }) { (index, action) -> TouchPlayerActionIcon(action, index == focusedIndex) }
            }
        }
    }
    if (more) ModalBottomSheet(onDismissRequest = { more = false }, containerColor = BackgroundElevated,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text("Reprodução", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 22.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(options) { (_, action) ->
                TouchPlayerAction(action.copy(onRun = { more = false; action.onRun() }), false, Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * Conjunto do centro da tela. Voltar 10 segundos, play e pausa e avançar 10 segundos, e nas séries o episódio anterior e o próximo
 * nas pontas. Todos têm 56dp ou mais de toque. Os botões de episódio apagam e ignoram o toque enquanto o episódio troca, para um
 * toque duplo não pular dois.
 */
@Composable
private fun TouchCenterControls(
    isPlaying: Boolean, previous: PlayerChipItem?, next: PlayerChipItem?, busy: Boolean, gap: Dp,
    onToggle: () -> Unit, onBack: () -> Unit, onForward: () -> Unit, modifier: Modifier = Modifier,
) {
    // Numa série os dois cantos existem sempre. Quando falta um (primeiro ou último episódio) o espaço dele fica vazio, e o play
    // continua no meio exato da tela em vez de andar de um episódio para o outro.
    val series = previous != null || next != null
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
        if (previous != null) Box(Modifier.alpha(if (busy) .4f else 1f)) {
            CtlButton(Icons.Default.SkipPrevious, "Episódio anterior", CenterSideSize, onClick = { if (!busy) previous.onRun() })
        } else if (series) Spacer(Modifier.size(CenterSideSize))
        CtlButton(Icons.Default.Replay10, "Voltar 10 segundos", CenterSideSize, onClick = onBack)
        Box(Modifier.size(CenterPlaySize).clip(CircleShape).background(Color.White).clickable(role = Role.Button, onClick = onToggle),
            contentAlignment = Alignment.Center) {
            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                if (isPlaying) "Pausar" else "Assistir", tint = TextOnLight, modifier = Modifier.size(40.dp))
        }
        CtlButton(Icons.Default.Forward10, "Avançar 10 segundos", CenterSideSize, onClick = onForward)
        if (next != null) Box(Modifier.alpha(if (busy) .4f else 1f)) {
            CtlButton(Icons.Default.SkipNext, "Próximo episódio", CenterSideSize, onClick = { if (!busy) next.onRun() })
        } else if (series) Spacer(Modifier.size(CenterSideSize))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TouchPlayerActionIcon(action: PlayerChipItem, focused: Boolean) {
    val foreground = if (focused) TextOnLight else if (action.active) Accent else Color.White
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(action.label) } }, state = rememberTooltipState()) {
        Box(contentAlignment = Alignment.Center) {
            Row(Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clip(CircleShape)
                .background(if (focused) Color.White else Color.Transparent)
                .clickable(role = Role.Button, onClick = action.onRun).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally)) {
                Icon(action.icon, action.label, tint = foreground, modifier = Modifier.size(24.dp))
                if (action.value.isNotBlank()) Text(action.value, color = foreground, fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            if (action.active && !focused) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp)
                .size(4.dp).background(Accent, CircleShape))
        }
    }
}

@Composable
private fun TouchPlayerAction(action: PlayerChipItem, focused: Boolean, modifier: Modifier = Modifier) {
    val foreground = if (focused) TextOnLight else if (action.active) Accent else Color.White
    Row(modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
        .background(if (focused) Color.White else Color.White.copy(alpha = .09f))
        .clickable(role = Role.Button, onClick = action.onRun).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(action.icon, null, tint = foreground, modifier = Modifier.size(22.dp))
        Text(action.label, color = foreground, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
