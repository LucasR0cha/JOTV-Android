package com.joabtv.app.ui.movies

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joabtv.app.ui.common.QualityBox
import com.joabtv.app.ui.common.SidePanel
import com.joabtv.app.ui.common.qualityTier
import com.joabtv.app.ui.theme.*
import com.joabtv.app.ui.tv.TvScaled
import coil.compose.AsyncImage

// Painel lateral do player (fontes, legendas, áudio) no desenho do .side-panel do webOS.

/** Ação do painel do player. Episódio anterior ou próximo, uma fonte, uma legenda ou um ajuste. */
internal data class TvPlayerAction(
    val label: String,
    val isSource: Boolean,
    val active: Boolean,
    /** Addon de origem da fonte (nome do manifest). "" = fonte própria do app. */
    val addon: String = "",
    /** Linha de baixo (valor atual do ajuste, motivo de não servir). */
    val sub: String = "",
    /** Título de seção mostrado acima da linha. */
    val header: String = "",
    /** Ajuste que muda no lugar, o painel continua aberto. */
    val keepOpen: Boolean = false,
    /** Aparece apagada (o toque só explica o motivo). */
    val disabled: Boolean = false,
    val image: String = "",
    val detail: String = "",
    val episode: Boolean = false,
    val seasonTabs: List<Int> = emptyList(),
    val selectedSeason: Int = 0,
    val onSeasonPick: ((Int) -> Unit)? = null,
    val stream: com.joabtv.app.data.remote.StremioStream? = null,
    val providers: List<String> = emptyList(),
    /** Barra de filtros do painel de fontes (qualidade, idioma e addon). Só a primeira linha leva isto. */
    val chips: List<PanelChip> = emptyList(),
    /** Pílula em que o foco do controle está. Só vale com [chips], e a seleção é outra coisa (OK escolhe). */
    val chipCursor: Int = 0,
    /** Contagem acima da barra ("12 versões em 20 fontes"). */
    val summary: String = "",
    /** Os filtros ligados ao lado da contagem ("Dublado e Torrentio"), na cor das pílulas ligadas. */
    val summaryActive: String = "",
    /** Rótulo da fileira de pílulas ("Mostrar", "Addon"). Sem ele a fileira ocupa a largura toda. */
    val chipLabel: String = "",
    val fixed: Boolean = false,
    val onRun: () -> Unit,
)

/** Uma pílula da barra de filtros do painel. [selected] diz se o filtro está ligado. */
internal data class PanelChip(val label: String, val selected: Boolean, val onPick: () -> Unit)

/** Painel aberto com a lista do tipo pedido. [focusIdx] é o item com foco do D-pad. */
@Composable
internal fun PlayerActionPanel(
    title: String,
    showCount: Boolean,
    actions: List<TvPlayerAction>,
    focusIdx: Int,
    pinned: Boolean,
    movieTitle: String,
    onPick: (TvPlayerAction) -> Unit,
    onDismiss: () -> Unit,
    television: Boolean = false,
) {
    if (television) TvScaled { PlayerActionPanelBody(title, showCount, actions, focusIdx, pinned, movieTitle, onPick, onDismiss, true) }
    else PlayerActionPanelBody(title, showCount, actions, focusIdx, pinned, movieTitle, onPick, onDismiss, false)
}

@Composable
private fun PlayerActionPanelBody(
    title: String,
    showCount: Boolean,
    actions: List<TvPlayerAction>,
    focusIdx: Int,
    pinned: Boolean,
    movieTitle: String,
    onPick: (TvPlayerAction) -> Unit,
    onDismiss: () -> Unit,
    television: Boolean,
) {
    val sourceCount = actions.count { it.isSource }
    val shortWindow = !television && androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 480
    SidePanel(
        title = title,
        onDismiss = onDismiss,
        width = if (television && (showCount || actions.any { it.episode || it.isSource })) 800.dp else 440.dp,
        // A frase de fonte só vale no painel de fontes. Legendas e Áudio ganham a dica das teclas.
        sub = when {
            !showCount && television -> "Setas escolhem, OK aplica, Voltar fecha"
            !showCount && shortWindow -> null
            !showCount -> "Toque em uma opção para aplicar"
            pinned && shortWindow -> "Fonte fixada"
            shortWindow -> null
            pinned -> "Fonte fixada. O player não troca sozinho"
            else -> "A troca de fonte mantém o tempo"
        },
        subColor = if (pinned) Accent else TextTertiary,
        titleTrailing = if (showCount && sourceCount > 0) ({
            Text(
                "$sourceCount", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(AccentFill).padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }) else null,
    ) {
        val listState = rememberLazyListState()
        val scrolling = actions.withIndex().filterNot { it.value.fixed }
        LaunchedEffect(focusIdx, actions.size) {
            if (actions.isEmpty()) return@LaunchedEffect
            if (actions.getOrNull(focusIdx)?.fixed == true) return@LaunchedEffect
            // Com a barra de filtros no alto, o foco na primeira versão ainda deixa a barra visível acima dela.
            val bars = actions.takeWhile { it.chips.isNotEmpty() }.size
            val barAbove = bars > 0 && focusIdx == bars
            val index = scrolling.indexOfFirst { it.index == focusIdx }.coerceAtLeast(0)
            listState.animateScrollToItem(if (barAbove) 0 else index)
        }
        actions.forEachIndexed { i, act ->
            if (act.fixed) SourcePanelRow(act, focused = i == focusIdx, movieTitle = movieTitle, onClick = { onPick(act) })
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(scrolling) { _, entry ->
                SourcePanelRow(entry.value, focused = entry.index == focusIdx, movieTitle = movieTitle, onClick = { onPick(entry.value) })
            }
        }
    }
}

/** Linha do painel (.panel-item). Selecionada em azul suave, foco D-pad em branco. */
@Composable
private fun SourcePanelRow(action: TvPlayerAction, focused: Boolean, movieTitle: String, onClick: () -> Unit) {
    val bg = when {
        focused -> Color.White
        action.active -> AccentSoft
        else -> Color.White.copy(alpha = 0.05f)
    }
    val fg = if (focused) TextOnLight else Color.White
    val subColor = if (focused) Color(0xFF444444) else TextTertiary
    val rowMod = Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp)).background(bg)
        .then(if (action.active && !focused) Modifier.border(1.dp, Accent.copy(alpha = 0.45f), RoundedCornerShape(14.dp)) else Modifier)
        .focusProperties { canFocus = false }
        .clickable { onClick() }
        .padding(horizontal = 14.dp, vertical = 10.dp)

    Column(Modifier.alpha(if (action.disabled) 0.45f else 1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (action.header.isNotBlank()) com.joabtv.app.ui.common.Eyebrow(action.header, Modifier.padding(top = 10.dp, start = 4.dp))
        if (action.chips.isNotEmpty()) {
            if (action.summary.isNotBlank()) Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(action.summary, color = TextTertiary, fontSize = 13.sp)
                if (action.summaryActive.isNotBlank()) Text(" · ${action.summaryActive}", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            // No toque a pílula escolhe direto. O cursor branco é só do controle remoto.
            PanelChipRow(action.chips, cursor = if (focused && com.joabtv.app.ui.common.LocalIsTv.current) action.chipCursor else -1, label = action.chipLabel)
            return@Column
        }
        if (action.seasonTabs.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                action.seasonTabs.forEach { season ->
                    val selected = season == action.selectedSeason
                    Text("Temporada $season", color = if (focused && selected) TextOnLight else TextPrimary,
                        fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(30.dp))
                            .background(if (focused && selected) Color.White else if (selected) AccentFill else Surface2)
                            .focusProperties { canFocus = false }.clickable { action.onSeasonPick?.invoke(season) }
                            .padding(horizontal = 14.dp, vertical = 10.dp))
                }
            }
            return@Column
        }
        if (action.isSource) {
            val stream = action.stream ?: com.joabtv.app.data.remote.StremioStream(action.label, "", action.addon,
                qualityRank = com.joabtv.app.data.remote.qualityRankOf(action.label))
            SourceCard(releaseInfo(stream, movieTitle, action.providers), "", TextTertiary, onClick,
                selected = action.active, focusedByDpad = focused, manualFocus = true,
                note = action.sub.ifBlank { null })
            return@Column
        }
        val config = androidx.compose.ui.platform.LocalConfiguration.current
        val shortWindow = !com.joabtv.app.ui.common.LocalIsTv.current && config.screenHeightDp < 480
        val compactEpisode = action.episode && !com.joabtv.app.ui.common.LocalIsTv.current && !shortWindow &&
            (config.screenWidthDp < 600 ||
                androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f)
        val trailing: @Composable () -> Unit = {
            Icon(when { action.active -> Icons.Default.Check; action.keepOpen -> Icons.Default.Tune; else -> Icons.Default.SkipNext },
                null, tint = if (focused) TextOnLight else if (action.active) Accent else TextTertiary, modifier = Modifier.size(20.dp))
        }
        if (compactEpisode) Column(rowMod, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AsyncImage(action.image, null, Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp)).background(Surface2), contentScale = ContentScale.Crop)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PlayerActionCopy(action, fg, subColor, Modifier.weight(1f))
                trailing()
            }
        } else Row(rowMod, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (action.episode) AsyncImage(action.image, null, Modifier.width(if (shortWindow) 104.dp else 146.dp)
                .aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
            PlayerActionCopy(action, fg, subColor, Modifier.weight(1f))
            trailing()
        }

    }
}

/**
 * Barra de pílulas do painel de fontes, no desenho do FilterChipPill do detalhe (48 dp de altura). [cursor] é a
 * pílula do foco do controle, -1 sem foco. Ligada fica em azul e o cursor em branco, como os cartões do painel.
 */
@Composable
private fun PanelChipRow(chips: List<PanelChip>, cursor: Int, label: String = "") {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // O rótulo diz o que a fileira filtra. As duas fileiras valem juntas, então cada uma leva o seu.
        if (label.isNotBlank()) Text(label, color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.width(64.dp))
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            chips.forEachIndexed { i, chip ->
                val atCursor = i == cursor
                Box(
                    Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(999.dp))
                        .background(if (atCursor) Color.White else if (chip.selected) AccentFill else Surface2)
                        .semantics { selected = chip.selected }
                        .focusProperties { canFocus = false }
                        .clickable(role = Role.Button) { chip.onPick() }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(chip.label, color = if (atCursor) TextOnLight else if (chip.selected) Color.White else TextSecondary,
                        fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun PlayerActionCopy(action: TvPlayerAction, foreground: Color, secondary: Color, modifier: Modifier) {
    val shortWindow = !com.joabtv.app.ui.common.LocalIsTv.current && androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 480
    Column(modifier) {
        Text(action.label, color = foreground, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (action.sub.isNotBlank()) Text(action.sub, color = secondary, fontSize = 13.sp, lineHeight = 18.sp,
            maxLines = if (shortWindow) 1 else 3, overflow = TextOverflow.Ellipsis)
        if (action.detail.isNotBlank()) Text(action.detail, color = secondary, fontSize = 13.sp, lineHeight = 19.sp,
            maxLines = if (shortWindow) 1 else 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}
