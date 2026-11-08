package com.joabtv.app.ui.movies

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joabtv.app.ui.common.pressScale
import com.joabtv.app.ui.theme.*

/**
 * Uma fonte já traduzida pro que a tela precisa mostrar. O label cru dos addons Stremio vem
 * cheio de emoji, repete o título do filme e mistura tudo numa linha só. [parseSourceLabel]
 * quebra isso em pedaços e a linha desenha cada pedaço no seu lugar.
 */
data class SourceInfo(
    /** Nome do addon de origem ("FrostStream"). Linha principal. */
    val addon: String,
    /** Servidor/provedor dentro do addon ("Servidor Athena"). "" se não houver. */
    val server: String,
    /** "4K", "1080p", "720p"… "" se desconhecido. */
    val quality: String,
    /** "Dublado", "Legendado", "Original"… "" se desconhecido. */
    val language: String,
    /** Pills técnicas extras (tamanho, MP4, HTTP, verificado…). */
    val tags: List<String>,
)

private val EMOJI = Regex(
    "[\\p{So}\\p{Cn}\\uFE0F\\u200D]|[\\uD83C-\\uDBFF\\uDC00-\\uDFFF]+"
)

/** Tira emoji e separadores soltos que sobram no meio ("· ·" vira "·"). */
private fun clean(s: String): String = s
    .replace(EMOJI, " ")
    .replace(Regex("\\s*·\\s*(·\\s*)+"), " · ")
    .replace(Regex("\\s{2,}"), " ")
    .trim()
    .trim('·', '-', '|', ' ')

private val SIZE = Regex("""\b\d+([.,]\d+)?\s?(GB|MB)\b""", RegexOption.IGNORE_CASE)
private val SEEDS = Regex("""\b(\d+)\s*(seeds?|seeders?)\b""", RegexOption.IGNORE_CASE)
private val QUALITY_WORDS = Regex("""\b(4K|2160p?|1440p|1080p|720p|480p|360p|FHD|UHD|HDR|HD)\b""", RegexOption.IGNORE_CASE)

/**
 * Traduz o label cru de uma fonte em [SourceInfo].
 *
 * [mediaTitle] é o nome do filme/série aberto. Aparece repetido em toda linha de alguns addons,
 * então some daqui: a tela inteira já é sobre esse título.
 */
fun parseSourceLabel(
    rawLabel: String,
    addonName: String,
    qualityRank: Int,
    mediaTitle: String = "",
): SourceInfo {
    val label = clean(rawLabel)

    // Normaliza pra comparar título: sem acento, sem ano entre parênteses, sem pontuação.
    fun chave(t: String) = java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("\\(\\s*\\d{4}\\s*\\)"), " ")
        .replace(Regex("[^A-Za-z0-9]+"), " ")
        .trim().lowercase()

    val tituloChave = chave(mediaTitle)
    // Quebra pelos separadores comuns dos addons.
    val parts = label.split("·", "|", "•", "\n")
        .map { clean(it) }
        .filter { it.isNotBlank() }
        // Tira o nome do título. Comparação tolerante porque o addon escreve
        // "Tron O Legado (2010)" e o TMDB "Tron O Legado" — igualdade exata não pegava, e o
        // nome do filme acabava aparecendo como se fosse o servidor.
        .filterNot { p -> tituloChave.isNotBlank() && chave(p).let { it == tituloChave || it.startsWith(tituloChave) } }

    val quality = when {
        qualityRank >= 2160 -> "4K"
        qualityRank >= 1440 -> "1440p"
        qualityRank >= 1080 -> "1080p"
        qualityRank >= 720 -> "720p"
        qualityRank >= 480 -> "480p"
        else -> QUALITY_WORDS.find(label)?.value?.uppercase()?.replace("2160P", "4K") ?: ""
    }

    val language = parts.firstOrNull { p ->
        p.contains("dubl", true) || p.contains("legend", true) ||
            p.equals("dub", true) || p.equals("leg", true) || p.contains("nacional", true)
    }?.let {
        when {
            it.contains("dubl", true) || it.equals("dub", true) || it.contains("nacional", true) -> "Dublado"
            else -> "Legendado"
        }
    } ?: ""

    // Servidor: pedaço que fala de servidor, senão o primeiro que sobrar sem ser qualidade/idioma.
    val server = parts.firstOrNull { it.contains("servidor", true) || it.contains("sevidor", true) || it.contains("server", true) }
        ?: parts.firstOrNull { p ->
            !QUALITY_WORDS.matches(p) && !p.contains("dubl", true) && !p.contains("legend", true) &&
                !p.contains("filme", true) && !p.contains("série", true) && !p.contains("serie", true) &&
                !SIZE.containsMatchIn(p) && p.length in 3..28 &&
                // Pedaço que começa igual ao título é o nome do filme, não servidor.
                !(tituloChave.isNotBlank() && chave(p).startsWith(tituloChave.take(8)))
        }
        ?: ""

    val tags = buildList {
        SIZE.find(label)?.value?.let { add(it.uppercase()) }
        SEEDS.find(label)?.let { add("${it.groupValues[1]} seeds") }
        if (label.contains("HDR", true)) add("HDR")
        if (label.contains("verificad", true)) add("Verificado")
        if (label.contains("mp4", true)) add("MP4")
    }.distinct().take(3)

    return SourceInfo(
        addon = addonName.ifBlank { "Fonte" },
        server = if (server.equals(addonName, true)) "" else server,
        quality = quality,
        language = language,
        tags = tags,
    )
}

/** Cor da faixa de qualidade. 4K em dourado, o resto no azul do app, baixa resolução apagada. */
private fun qualityColor(q: String): Color = when {
    q == "4K" || q == "1440p" -> Gold
    q == "1080p" -> Accent
    q == "720p" -> TextSecondary
    else -> TextTertiary
}

/**
 * Linha de fonte do detalhe. Faixa de qualidade à esquerda, addon em destaque, servidor e
 * idioma embaixo, pills técnicas e os botões de tocar e baixar à direita.
 */
@Composable
fun SourceCard(
    info: SourceInfo,
    /** Rótulo do transporte ("HTTP", "DUB", "LEG"). */
    tag: String,
    tagColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onDownload: (() -> Unit)? = null,
    /** Aviso no lugar da linha de servidor e idioma (fonte morta, outro filme). */
    note: String? = null,
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    val borderColor = if (focused) Color.White else Border

    Row(
        modifier
            .fillMaxWidth()
            .pressScale(src)
            .shadow(if (focused) 16.dp else 0.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(if (focused) AccentSoft else BackgroundCard2)
            .border(if (focused) 2.dp else 1.dp, borderColor, RoundedCornerShape(16.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Caixa de qualidade (.sc-quality). É o que decide a escolha, então vem primeiro.
        com.joabtv.app.ui.common.QualityBox(
            info.quality.ifBlank { "HD" },
            com.joabtv.app.ui.common.qualityTier(com.joabtv.app.ui.movies.sourceQualityRank(info.quality)),
            width = 58.dp, height = 48.dp,
        )

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            // Linha 1: de qual addon veio.
            Text(
                info.addon,
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = SoraFontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Linha 2: servidor e idioma.
            val sub = note ?: listOf(info.server, info.language).filter { it.isNotBlank() }.joinToString("  ·  ")
            if (sub.isNotBlank()) {
                Text(
                    sub,
                    color = if (note != null) Gold else TextSecondary,
                    fontSize = 13.sp,
                    fontFamily = ManropeFontFamily,
                    maxLines = if (note != null) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Linha 3: pills técnicas.
            if (info.tags.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    info.tags.forEach { t -> SourcePill(t) }
                }
            }
        }

        // Transporte + ações.
        com.joabtv.app.ui.common.Badge(tag, tagColor)
        if (onDownload != null) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDownload),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(Surface2),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Download, "Baixar", tint = TextPrimary, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun SourcePill(text: String) {
    Text(
        text,
        color = TextSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = ManropeFontFamily,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(Color(0x12FFFFFF))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * Pôster de um filme da coleção. Traz o NÚMERO da ordem cronológica no canto e marca o
 * título que está aberto agora, pra pessoa se localizar dentro da franquia.
 */
@Composable
fun CollectionPoster(
    order: Int,
    movie: com.joabtv.app.domain.model.MovieItem,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    Column(Modifier.width(128.dp)) {
        Box {
            com.joabtv.app.ui.common.MediaPosterCard(
                title = movie.title,
                imageUrl = movie.posterUrl,
                onClick = onClick,
                modifier = Modifier.fillMaxWidth(),
                cleanPoster = true,
            )
            // Selo da ordem. Accent no filme atual, escuro nos outros.
            Box(
                Modifier
                    .padding(8.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(if (isCurrent) Accent else Color.Black.copy(alpha = 0.72f))
                    .border(1.dp, if (isCurrent) Accent else Color.White.copy(alpha = 0.25f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    order.toString(),
                    color = if (isCurrent) Color(0xFF06070A) else Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = SoraFontFamily,
                )
            }
        }
        com.joabtv.app.ui.common.PosterCaption(movie.title, if (isCurrent) "Assistindo" else movie.year.ifBlank { "Sem data" }, highlight = isCurrent)
    }
}
