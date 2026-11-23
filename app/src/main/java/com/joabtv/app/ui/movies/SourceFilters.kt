package com.joabtv.app.ui.movies

import com.joabtv.app.data.remote.SourceFacts
import com.joabtv.app.data.remote.StremioStream
import com.joabtv.app.data.remote.hasDolbyVision
import com.joabtv.app.data.remote.hasHdr

/**
 * Filtros compartilhados pelo detalhe, pelos episódios e pelo player.
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
    return groups.mapNotNull { g ->
        val matching = g.members.filter { (addon == "all" || it.addon == addon) && test(fact(it)) }
        matching.takeIf { it.isNotEmpty() }?.let { ReleaseGroup(it.sortedBy { s -> fact(s).dead || fact(s).wrongTitle }) }
    }
}

/**
 * Pílulas com contagens por addon. Idioma e qualidade ficam disponíveis também em listas pequenas.
 */
internal fun sourceChips(
    groups: List<ReleaseGroup>, fact: (StremioStream) -> SourceFacts, filter: String, addon: String,
): List<SourceChip> = buildList {
    if (groups.isNotEmpty()) SOURCE_FILTERS.forEach { (key, name, test) ->
        val n = groups.count { g -> g.members.any { (addon == "all" || it.addon == addon) && test(fact(it)) } }
        if (key == "all" || n > 0 || key == filter) add(SourceChip("f:$key", "$name $n", filter == key))
    }
    val addons = groups.flatMap { g -> g.members.mapNotNull { it.addon.takeIf { a -> a.isNotBlank() } } }.distinct()
    if (addons.size > 1) add(SourceChip("a:all", "Todos os addons", addon == "all"))
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

/** Os filtros ligados em uma frase, como "Dublado e Beta". Vazio quando nada está filtrado. */
internal fun activeFilters(filter: String, addon: String): String = listOfNotNull(
    SOURCE_FILTERS.firstOrNull { it.first == filter && it.first != "all" }?.second,
    addon.takeIf { it != "all" && it.isNotBlank() },
).joinToString(" e ")

/**
 * Posição de cada addon na lista, na ordem em que chegam. É a ordem da configuração (Gerenciar addons), porque
 * streamsProgressive monta a lista pelos slots dos addons. Quem chama passa a lista antes de qualquer reordenação.
 */
internal fun addonRanks(groups: List<ReleaseGroup>): Map<String, Int> {
    val ranks = LinkedHashMap<String, Int>()
    groups.forEach { g -> g.members.forEach { if (it.addon.isNotBlank() && it.addon !in ranks) ranks[it.addon] = ranks.size } }
    return ranks
}

/** A seção da versão, o addon de melhor posição entre os que têm o arquivo. */
internal fun sectionOf(group: ReleaseGroup, ranks: Map<String, Int>): String =
    group.members.filter { it.addon.isNotBlank() }.minByOrNull { ranks[it.addon] ?: Int.MAX_VALUE }?.addon ?: group.lead.addon

/** Versões juntas por seção, a do addon de melhor posição primeiro. Dentro da seção vale a ordem recebida. */
internal fun byAddon(groups: List<ReleaseGroup>, ranks: Map<String, Int>): List<ReleaseGroup> =
    groups.withIndex().sortedWith(compareBy({ ranks[sectionOf(it.value, ranks)] ?: Int.MAX_VALUE }, { it.index })).map { it.value }

/** "4 versões" ou, com parte atrás do Mostrar mais, "3 de 5 versões". */
internal fun sectionCount(shown: Int, total: Int): String =
    if (shown >= total) (if (total == 1) "1 versão" else "$total versões") else "$shown de $total versões"
