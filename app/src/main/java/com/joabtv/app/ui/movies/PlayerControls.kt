package com.joabtv.app.ui.movies

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joabtv.app.ui.theme.*
import kotlinx.coroutines.delay

// Controles do player no desenho do player do webOS. Topo com título, fonte e relógio,
// barra de progresso com os tempos dos lados e chips embaixo.

/** Chip da barra de baixo (Zoom, Velocidade, Legendas, Áudio, Fontes), navegável por D-pad na TV. */
internal data class PlayerChipItem(
    val icon: ImageVector,
    val label: String,
    val onRun: () -> Unit,
)

internal fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Largura que os botões do topo direito ocupam, para o título não passar por baixo deles. */
internal fun topActionsWidth(isTv: Boolean): Dp = if (isTv) (48 * 2 + 8 + 28).dp else (48 * 4 + 8 * 3 + 28).dp

@Composable
internal fun CustomPlayerControls(
    title: String,
    clock: String,
    subtitle: String,
    isPlaying: Boolean,
    position: Long,
    duration: Long,
    onToggle: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekFwd: () -> Unit,
    onSeekTo: (Long) -> Unit,
    videoUrl: String = "",
    isTv: Boolean = false,
    tvMode: Boolean = false,
    tvChips: List<PlayerChipItem> = emptyList(),
    tvChipIdx: Int = -1,
    chips: @Composable RowScope.() -> Unit,
) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.78f),
                    0.24f to Color.Transparent,
                    0.58f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.88f),
                )
            )
    ) {
        // Em tela estreita os botões do topo ocupam a largura toda, então o título desce para a
        // linha de baixo deles. Em tela larga fica ao lado, com espaço reservado.
        val narrow = maxWidth < 600.dp
        PlayerTitleBlock(
            title, subtitle, clock,
            Modifier.align(Alignment.TopStart).padding(
                start = 20.dp, top = if (narrow) 76.dp else 18.dp,
                end = if (narrow) 20.dp else topActionsWidth(isTv),
            ),
        )

        // Centro: voltar 10 s, tocar ou pausar, avançar 10 s.
        Row(
            Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(36.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CtlButton(Icons.Default.Replay10, "Voltar 10 segundos", 60.dp, onClick = onSeekBack)
            CtlButton(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                if (isPlaying) "Pausar" else "Reproduzir", 84.dp, big = true, onClick = onToggle,
            )
            CtlButton(Icons.Default.Forward10, "Avançar 10 segundos", 60.dp, onClick = onSeekFwd)
        }

        Column(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PlayerSeekRow(position, duration, onSeekTo, videoUrl)
            PlayerChipRow(tvMode, tvChips, tvChipIdx, chips)
        }
    }
}

@Composable
private fun PlayerTitleBlock(title: String, subtitle: String, clock: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            title, color = Color.White, fontSize = 22.sp, lineHeight = 27.sp, fontWeight = FontWeight.ExtraBold,
            fontFamily = Inter, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (subtitle.isNotBlank()) Text(
            subtitle, color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            fontFamily = Inter, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        // Hora atual e quando o vídeo termina, já contando a velocidade (.pl-clock).
        Text(clock, color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = Inter, maxLines = 1)
    }
}

/** Tempo, barra fina com bolinha e duração na mesma linha (.pl-bar-row). Toque ou arraste busca. */
@Composable
private fun PlayerSeekRow(position: Long, duration: Long, onSeekTo: (Long) -> Unit, videoUrl: String) {
    val total = if (duration > 0) duration else 0L
    val frac = if (total > 0) (position.toFloat() / total).coerceIn(0f, 1f) else 0f
    var barW by remember { mutableFloatStateOf(1f) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubX by remember { mutableFloatStateOf(0f) }
    var scrubMs by remember { mutableLongStateOf(0L) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }

    // Prévia do frame ao arrastar, tipo YouTube. Só busca quando a posição muda de janela de
    // 1.5s (não a cada pixel), e some se a fonte não devolver nada (ScrubThumbnailer explica).
    LaunchedEffect(scrubbing, scrubMs / 1500, videoUrl) {
        if (!scrubbing || total <= 0 || videoUrl.isBlank()) return@LaunchedEffect
        delay(120)
        preview = ScrubThumbnailer.frameAt(videoUrl, scrubMs)
    }
    LaunchedEffect(scrubbing) { if (!scrubbing) preview = null }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TimePill(formatTime(position))
        Box(
            Modifier
                .weight(1f)
                .height(32.dp)
                .onSizeChanged { barW = it.width.toFloat().coerceAtLeast(1f) }
                .pointerInput(total) {
                    detectTapGestures { off -> if (total > 0) onSeekTo(((off.x / barW).coerceIn(0f, 1f) * total).toLong()) }
                }
                .pointerInput(total) {
                    detectHorizontalDragGestures(
                        onDragStart = { scrubbing = true },
                        onDragEnd = { scrubbing = false },
                        onDragCancel = { scrubbing = false },
                    ) { change, _ ->
                        if (total <= 0) return@detectHorizontalDragGestures
                        val x = change.position.x.coerceIn(0f, barW)
                        scrubX = x
                        scrubMs = ((x / barW) * total).toLong()
                        onSeekTo(scrubMs)
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.22f)))
            Box(Modifier.fillMaxWidth(frac).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Accent))
            Box(Modifier.fillMaxWidth(frac), contentAlignment = Alignment.CenterEnd) {
                Box(Modifier.size(16.dp).clip(CircleShape).background(Color.White))
            }
            if (scrubbing) ScrubPreview(
                bitmap = preview, timeMs = scrubMs, xPx = scrubX, barWidthPx = barW,
                modifier = Modifier.align(Alignment.TopStart),
            )
        }
        TimePill(if (total > 0) formatTime(total) else "AO VIVO")
    }
}

/** Miniatura flutuante acima do ponto arrastado (.scrub-preview), tipo YouTube. */
@Composable
private fun ScrubPreview(bitmap: Bitmap?, timeMs: Long, xPx: Float, barWidthPx: Float, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val widthDp = 96.dp
    val widthPx = with(density) { widthDp.toPx() }
    val minX = widthPx / 2
    val maxX = (barWidthPx - widthPx / 2).coerceAtLeast(minX)
    val centerX = xPx.coerceIn(minX, maxX)
    Column(
        modifier
            .offset { IntOffset((centerX - widthPx / 2).toInt(), with(density) { (-92).dp.roundToPx() }) }
            .width(widthDp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black)
                .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Spacer(Modifier.height(4.dp))
        TimePill(formatTime(timeMs))
    }
}

/** Chips centralizados que rolam de lado quando não cabem. TV usa a lista com foco por D-pad. */
@Composable
private fun PlayerChipRow(tvMode: Boolean, tvChips: List<PlayerChipItem>, tvChipIdx: Int, chips: @Composable RowScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).widthIn(min = maxWidth),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (tvMode) tvChips.forEachIndexed { i, chip ->
                PlayerChip(chip.icon, chip.label, focusedByDpad = i == tvChipIdx, onClick = chip.onRun)
            } else chips()
        }
    }
}

/** Pílula de tempo (.pl-time). */
@Composable
internal fun TimePill(text: String) {
    Text(
        text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = Inter,
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** Botão redondo dos controles. Nunca menor que 48dp. */
@Composable
internal fun CtlButton(icon: ImageVector, desc: String, size: Dp, big: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(size.coerceAtLeast(48.dp))
            .onFocusChanged { focused = it.isFocused }
            .clip(CircleShape)
            .background(if (focused) Color.White else Color.Black.copy(alpha = if (big) 0.5f else 0.42f))
            .clickable(role = Role.Button) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = if (focused) TextOnLight else Color.White, modifier = Modifier.size(if (big) 44.dp else if (size >= 56.dp) 30.dp else 24.dp))
    }
}

/** Chip do player (.pl-chip). Ativo azul, foco branco com texto escuro. 48dp de altura. */
@Composable
internal fun PlayerChip(icon: ImageVector, label: String, active: Boolean = false, focusedByDpad: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val on = focused || focusedByDpad
    val bg = when { on -> Color.White; active -> AccentFill; else -> Color.White.copy(alpha = 0.14f) }
    val fg = if (on) TextOnLight else Color.White
    Row(
        Modifier
            .height(48.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .clickable(role = Role.Button) { onClick() }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
        Text(label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = Inter, maxLines = 1)
    }
}

/** Botões do topo direito. Transmitir, Girar e Travar (só celular) e Voltar. */
@Composable
internal fun PlayerTopActions(
    isTv: Boolean,
    onCast: () -> Unit,
    onRotate: () -> Unit,
    onLock: () -> Unit,
    onBack: () -> Unit,
) {
    Row(
        Modifier.padding(top = 14.dp, end = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CtlButton(Icons.Default.Cast, "Transmitir", 48.dp, onClick = onCast)
        // Girar e Travar só no celular, na TV o controle já faz.
        if (!isTv) {
            CtlButton(Icons.Default.ScreenRotation, "Girar a tela", 48.dp, onClick = onRotate)
            CtlButton(Icons.Default.Lock, "Travar a tela", 48.dp, onClick = onLock)
        }
        CtlButton(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", 48.dp, onClick = onBack)
    }
}
