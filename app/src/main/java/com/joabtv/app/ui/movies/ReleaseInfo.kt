package com.joabtv.app.ui.movies

import com.joabtv.app.data.remote.StremioStream
import com.joabtv.app.data.remote.hasAtmos
import com.joabtv.app.data.remote.hasDolbyVision
import com.joabtv.app.data.remote.hasHdr
import com.joabtv.app.data.remote.ptAudio
import com.joabtv.app.data.remote.qualityRankOf
import java.util.Locale

internal data class ReleaseGroup(val members: List<StremioStream>) {
    val lead get() = members.first()
    /** Chave do grupo, a do primeiro membro. Muda quando um addon de prioridade alta chega depois. */
    val key get() = fileKeyOf(lead) ?: "u${lead.url}"
    /** Link e arquivo de todos os membros. A identidade do cartão na tela vem daqui (ver [StableOrder]). */
    val keys get() = members.flatMap { listOfNotNull("u${it.url}", fileKeyOf(it)) }
}

private fun fileKeyOf(s: StremioStream) =
    if (s.fileName.isNotBlank() && s.fileSize > 0) "ffile:${s.fileName.lowercase(Locale.ROOT)}:${s.fileSize}" else null

/** Só agrupa arquivos cuja identidade foi informada. Qualidade igual não prova que é o mesmo vídeo. */
internal fun groupReleases(streams: List<StremioStream>): List<ReleaseGroup> = streams.groupBy { s ->
    fileKeyOf(s) ?: "url:${s.url}"
}.values.map { ReleaseGroup(it) }

/**
 * Versões que cabem em uma página. Mantém a ordem recebida (a prioridade dos addons), mas um addon
 * com centenas de versões não ocupa a página inteira. Na primeira volta cada addon entra com no
 * máximo limit dividido pelo número de addons, e o resto da página se completa na ordem original.
 */
internal fun pageOf(groups: List<ReleaseGroup>, limit: Int): List<ReleaseGroup> {
    val addons = groups.map { it.lead.addon }.distinct()
    if (addons.size < 2 || groups.size <= limit) return groups.take(limit)
    val cap = maxOf(1, limit / addons.size)
    val taken = HashMap<String, Int>()
    var used = 0
    val chosen = BooleanArray(groups.size)
    groups.forEachIndexed { i, g ->
        val n = taken[g.lead.addon] ?: 0
        if (used < limit && n < cap) { taken[g.lead.addon] = n + 1; chosen[i] = true; used++ }
    }
    groups.indices.forEach { i -> if (!chosen[i] && used < limit) { chosen[i] = true; used++ } }
    return groups.filterIndexed { i, _ -> chosen[i] }
}

private val RELEASE_CODEC = Regex("\\b(hevc|x265|h[ .]?265|av1|x264|h[ .]?264|avc)\\b", RegexOption.IGNORE_CASE)
private val RELEASE_KIND = Regex("\\b(web[ .-]?dl|web[ .-]?rip|blu[ .-]?ray|remux|hdtv|dvd[ .-]?rip)\\b", RegexOption.IGNORE_CASE)
private val RELEASE_SIZE = Regex("\\b(\\d{1,6}(?:[.,]\\d{1,3})?)\\s?(TiB|GiB|MiB|TB|GB|MB)\\b", RegexOption.IGNORE_CASE)
private val RELEASE_SEEDS = Regex("(?:👤|👥|🌱|\\bseed(?:er)?s?\\s?)\\s{0,3}[:=]?\\s{0,3}(\\d{1,8})", RegexOption.IGNORE_CASE)
private val RELEASE_FILE = Regex("(?:\\.mkv|\\.mp4|\\.avi|\\.S\\d{1,2}E\\d{1,3}\\.)", RegexOption.IGNORE_CASE)
// Compilados uma vez só. Dentro da função cada cartão refazia todos a cada recomposição.
private val RELEASE_HDR10P = Regex("HDR10\\+|HDR10Plus", RegexOption.IGNORE_CASE)
private val RELEASE_USENET = Regex("""\busenet\b|\beasynews\b|\bnzb\b""", RegexOption.IGNORE_CASE)
private val RELEASE_USENET_URL = Regex("""^nzb:|^usenet:|\.nzb(\?|$)""", RegexOption.IGNORE_CASE)
private val RELEASE_TRUEHD = Regex("\\btrue[ .-]?hd\\b", RegexOption.IGNORE_CASE)
private val RELEASE_DDP = Regex("\\bddp|e[ .-]?ac3|dd\\+", RegexOption.IGNORE_CASE)
private val RELEASE_DTS = Regex("\\bdts(?:[ .-]?hd)?\\b", RegexOption.IGNORE_CASE)
private val RELEASE_CHANNELS = Regex("\\b[257]\\.[01]\\b")
private val RELEASE_SUBBED = Regex("legendad|\\bleg\\b|\\bsubbed\\b", RegexOption.IGNORE_CASE)

internal fun releaseInfo(stream: StremioStream, title: String, providers: List<String> = emptyList()): SourceInfo {
    val text = stream.releaseText.ifBlank { stream.label }.take(3000)
    val base = parseSourceLabel(stream.label, stream.addon, stream.qualityRank, title)
    val quality = stream.qualityRank.takeIf { it > 0 } ?: qualityRankOf(text)
    val codec = RELEASE_CODEC.find(text)?.value?.uppercase(Locale.ROOT)
        ?.replace("X265", "HEVC")?.replace("H.265", "HEVC")?.replace("H265", "HEVC")
        ?.replace("X264", "H.264")?.replace("AVC", "H.264")
    val kind = RELEASE_KIND.find(text)?.value?.uppercase(Locale.ROOT)?.replace("WEBDL", "WEB-DL")
        ?.replace("WEBRIP", "WEBRip")?.replace("BLURAY", "BluRay")
    val badges = buildList {
        if (stream.dolbyVision || hasDolbyVision(text)) add("Dolby Vision")
        if (stream.hdr || hasHdr(text)) add(if (RELEASE_HDR10P.containsMatchIn(text)) "HDR10+"
            else if (text.contains("HDR10", true)) "HDR10" else "HDR")
        if (stream.atmos || hasAtmos(text)) add("Atmos")
        if (RELEASE_USENET.containsMatchIn(text) || RELEASE_USENET_URL.containsMatchIn(stream.url)) add("Usenet")
        if (RELEASE_TRUEHD.containsMatchIn(text)) add("TrueHD")
        if (RELEASE_DDP.containsMatchIn(text)) add("DD+")
        if (RELEASE_DTS.containsMatchIn(text)) add("DTS")
        RELEASE_CHANNELS.find(text)?.value?.let(::add)
    }
    val language = when {
        stream.ptAudio || ptAudio(text) -> "Dublado"
        RELEASE_SUBBED.containsMatchIn(text) -> "Legendado"
        else -> base.language
    }
    val bytes = stream.fileSize
    val size = if (bytes > 0) {
        val gb = bytes / (1024.0 * 1024 * 1024)
        if (gb >= 1) String.format(Locale.US, "%.2f GB", gb).replace('.', ',')
        else "${bytes / (1024 * 1024)} MB"
    } else RELEASE_SIZE.find(text)?.let { it.groupValues[1].replace('.', ',') + " " + it.groupValues[2].uppercase(Locale.ROOT) }.orEmpty()
    val file = stream.fileName.ifBlank { text.lineSequence().firstOrNull { RELEASE_FILE.containsMatchIn(it) }.orEmpty() }.take(512)
    return base.copy(
        quality = when { quality >= 2160 -> "4K"; quality >= 1440 -> "2K"; quality > 0 -> "${quality}p"; else -> "" },
        server = listOfNotNull(kind, codec).joinToString(" · ").ifBlank { base.server.takeUnless { it == file }.orEmpty() },
        language = language, tags = badges.distinct(), fileName = file, size = size,
        seeds = RELEASE_SEEDS.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
        providers = providers.ifEmpty { listOf(stream.addon).filter { it.isNotBlank() } },
    )
}
