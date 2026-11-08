package com.joabtv.app.ui.movies

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joabtv.app.ui.common.QualityBox
import com.joabtv.app.ui.common.SidePanel
import com.joabtv.app.ui.common.qualityTier
import com.joabtv.app.ui.theme.*

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
    val onRun: () -> Unit,
)

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
) {
    val sourceCount = actions.count { it.isSource }
    SidePanel(
        title = title,
        onDismiss = onDismiss,
        // A frase de fonte só vale no painel de fontes. Legendas e Áudio ganham a dica das teclas.
        sub = when {
            !showCount -> "Setas escolhem, OK aplica, Voltar fecha"
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
        LaunchedEffect(focusIdx) { runCatching { listState.animateScrollToItem(focusIdx) } }
        LazyColumn(state = listState, modifier = Modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(actions) { i, act ->
                SourcePanelRow(act, focused = i == focusIdx, movieTitle = movieTitle, onClick = { onPick(act) })
            }
        }
    }
}

private data class ParsedSource(val provider: String, val quality: String?, val language: String?)

/** Remove emojis e símbolos, mantém letras acentuadas, números e pontuação básica. */
private fun stripSymbols(s: String) =
    s.replace(Regex("[^\\p{L}\\p{N}\\s()./+:-]"), "").replace(Regex("\\s+"), " ").trim()

private val QUALITY_RE = Regex("(?i)\\b(4k|2160p?|1080p?|fhd|uhd|hd|720p?|480p?|cam|ts)\\b")

/** Quebra o label bruto ("Michael · CDMoviesDB · Português") em provedor, qualidade e idioma. */
private fun parseSource(raw: String, movieTitle: String): ParsedSource {
    val parts = raw.split("·", "|", "•").map { stripSymbols(it) }.filter { it.isNotBlank() }
    var quality: String? = null
    var language: String? = null
    val provs = mutableListOf<String>()
    for (p in parts) {
        val q = QUALITY_RE.find(p)?.value
        when {
            q != null && p.length <= q.length + 4 -> quality = q.uppercase()
            Regex("(?i)portug|dublado|legendado|\\bdual\\b|nacional|ingl|espanhol|\\bleg\\b|\\bdub\\b").containsMatchIn(p) -> language = p
            else -> provs.add(p)
        }
    }
    val titleNorm = movieTitle.lowercase().trim()
    val provider = provs.firstOrNull {
        val n = it.lowercase().replace(Regex("\\(\\d{4}\\)"), "").trim()
        n.isNotBlank() && n != titleNorm
    } ?: provs.firstOrNull() ?: parts.firstOrNull() ?: "Fonte"
    val lang = language?.let {
        when {
            Regex("(?i)dublado|\\bdub\\b|nacional").containsMatchIn(it) -> "Dublado"
            Regex("(?i)legendado|\\bleg\\b").containsMatchIn(it) -> "Legendado"
            Regex("(?i)dual").containsMatchIn(it) -> "Dual"
            Regex("(?i)portug").containsMatchIn(it) -> "Português"
            Regex("(?i)ingl").containsMatchIn(it) -> "Inglês"
            Regex("(?i)espanhol").containsMatchIn(it) -> "Espanhol"
            else -> it
        }
    }
    return ParsedSource(provider.trim(), quality, lang)
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
        if (!action.isSource) {
            // Episódio, legenda, áudio ou ajuste. Linha simples.
            Row(rowMod, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(action.label, color = fg, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (action.sub.isNotBlank()) Text(action.sub, color = subColor, fontSize = 13.sp, maxLines = 2)
                }
                val tint = if (focused) TextOnLight else if (action.active) Accent else TextTertiary
                Icon(
                    when { action.active -> Icons.Default.Check; action.keepOpen -> Icons.Default.Tune; else -> Icons.Default.SkipNext },
                    null, tint = tint, modifier = Modifier.size(20.dp),
                )
            }
            return@Column
        }

        val parsed = remember(action.label, movieTitle) { parseSource(action.label, movieTitle) }
        Row(rowMod, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Caixa de qualidade (.pi-quality). Sem qualidade no nome fica a inicial do provedor.
            val q = parsed.quality
            QualityBox(
                q ?: (parsed.provider.firstOrNull()?.uppercase() ?: "?"),
                qualityTier(if (q != null) sourceQualityRank(q) else 0),
                width = 52.dp, height = 38.dp,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(parsed.provider, color = fg, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = Inter, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Addon de origem e idioma na linha de baixo.
                val line = listOfNotNull(action.addon.takeIf { it.isNotBlank() }, parsed.language).joinToString("  ·  ")
                if (line.isNotBlank()) Text(line, color = if (focused) Color(0xFF444444) else TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (action.sub.isNotBlank()) Text(action.sub, color = subColor, fontSize = 12.sp, maxLines = 2)
            }
            if (action.active) Icon(Icons.Default.Check, "Tocando", tint = if (focused) TextOnLight else Accent, modifier = Modifier.size(22.dp))
        }
    }
}
