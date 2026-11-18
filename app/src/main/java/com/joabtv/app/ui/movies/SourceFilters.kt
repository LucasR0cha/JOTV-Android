package com.joabtv.app.ui.movies

import com.joabtv.app.data.remote.SourceFacts
import com.joabtv.app.data.remote.StremioStream
import com.joabtv.app.data.remote.hasDolbyVision
import com.joabtv.app.data.remote.hasHdr

/**
 * Filtros da lista de versões, as mesmas pílulas do detalhe (Todas, Dublado, Legendado, 4K, 1080p, HDR,
 * Instantâneo e Usenet). O painel de fontes do player usa estes.
 * ponytail: o detalhe ainda tem a cópia dele (`SRC_FILTERS` em MovieDetailScreen.kt). Trocar por esta lista
 * quando aquele arquivo estiver livre, para as duas telas nunca discordarem.
 */
internal val SOURCE_FILTERS = listOf<Triple<String, String, (SourceFacts) -> Boolean>>(
    Triple("all", "Todas") { true },
    Triple("dub", "Dublado") { it.lang == "dub" },
    Triple("leg", "Legendado") { it.lang == "leg" },
    // Qualidade do nome primeiro, igual ao detalhe. Filme 1080p em scope tem 800 linhas reais.
    Triple("4k", "4K") { (it.labelRank.takeIf { q -> q > 0 } ?: it.rank) >= 2000 },
    Triple("1080", "1080p") { (it.labelRank.takeIf { q -> q > 0 } ?: it.rank) in 1000..1199 },
    Triple("hdr", "HDR") { hasHdr(it.label) || hasDolbyVision(it.label) },
    Triple("instant", "Instantâneo") { it.debrid >= 2 },
    Triple("usenet", "Usenet") { it.isUsenet },
)

/** Uma pílula da barra. [key] começa com "f:" (qualidade e idioma) ou "a:" (addon). */
internal class SourceChip(val key: String, val label: String, val selected: Boolean)

/** Versões que passam no filtro e no addon escolhidos. "all" nos dois é a lista inteira. */
internal fun filterReleases(
    groups: List<ReleaseGroup>, fact: (StremioStream) -> SourceFacts, filter: String, addon: String,
): List<ReleaseGroup> {
    val test = SOURCE_FILTERS.firstOrNull { it.first == filter }?.third ?: { true }
    return groups.filter { g -> g.members.any { test(fact(it)) } && (addon == "all" || g.members.any { it.addon == addon }) }
}

/**
 * Pílulas da barra, contadas sobre todas as versões (o número não encolhe quando um filtro está ligado).
 * Qualidade e idioma só com mais de 4 versões, addon só com mais de um addon. Vazio quando nada disso vale,
 * e aí o painel fica como era, sem barra.
 */
internal fun sourceChips(
    groups: List<ReleaseGroup>, fact: (StremioStream) -> SourceFacts, filter: String, addon: String,
): List<SourceChip> = buildList {
    if (groups.size > 4) SOURCE_FILTERS.forEach { (key, name, test) ->
        val n = groups.count { g -> g.members.any { test(fact(it)) } }
        if (key == "all" || n > 0) add(SourceChip("f:$key", "$name $n", filter == key))
    }
    val addons = groups.flatMap { g -> g.members.mapNotNull { it.addon.takeIf { a -> a.isNotBlank() } } }.distinct()
    if (addons.size > 1) addons.forEach { adn ->
        val n = groups.count { g -> g.members.any { it.addon == adn } }
        if (n > 0) add(SourceChip("a:$adn", "$adn $n", addon == adn))
    }
}

/** "12 versões em 20 fontes", ou "5 de 12 versões" com filtro ligado. */
internal fun sourceSummary(total: Int, shown: Int, sources: Int, filtered: Boolean): String {
    fun versoes(n: Int) = if (n == 1) "1 versão" else "$n versões"
    return if (filtered) "$shown de ${versoes(total)}" else "${versoes(total)} em $sources ${if (sources == 1) "fonte" else "fontes"}"
}
