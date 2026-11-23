package com.joabtv.app.ui.movies

import com.joabtv.app.data.remote.SourceFacts
import com.joabtv.app.data.remote.StremioStream
import com.joabtv.app.data.remote.qualityRankOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceFiltersTest {
    private fun stream(addon: String, label: String, n: Int) =
        StremioStream(label, "https://x/$n", addon, qualityRank = qualityRankOf(label))

    private fun group(vararg s: StremioStream) = ReleaseGroup(s.toList())
    private val fact = { s: StremioStream -> SourceFacts(s.url, s.addon, s.label, s.qualityRank) }

    // Seis versões de dois addons. Duas dubladas, três em 1080p, uma em 4K.
    private val groups = listOf(
        group(stream("Brazuca", "Filme 1080p Dublado", 1)),
        group(stream("Brazuca", "Filme 1080p Dublado", 2), stream("Torrentio", "Filme 1080p Dublado", 2)),
        group(stream("Torrentio", "Filme 1080p Legendado", 3)),
        group(stream("Torrentio", "Filme 2160p 4K", 4)),
        group(stream("Torrentio", "Filme 720p", 5)),
        group(stream("Brazuca", "Filme 480p", 6)),
    )

    @Test
    fun barraContaSobreTodasEMostraAddon() {
        val chips = sourceChips(groups, fact, "all", "all").associate { it.key to it.label }
        assertEquals("Todas 6", chips["f:all"])
        assertEquals("Dublado 2", chips["f:dub"])
        assertEquals("1080p 3", chips["f:1080"])
        assertEquals("4K 1", chips["f:4k"])
        assertEquals("Brazuca 3", chips["a:Brazuca"])
        assertEquals("Torrentio 4", chips["a:Torrentio"]) // o arquivo repetido conta nos dois addons
    }

    @Test
    fun pilulaSemVersaoNaoAparece() {
        val chips = sourceChips(groups, fact, "all", "all").map { it.key }
        assertTrue("f:usenet" !in chips)
        assertTrue("f:instant" !in chips)
    }

    @Test
    fun listaPequenaMantemFiltrosDeIdiomaEQualidade() {
        val chips = sourceChips(groups.take(4).map { group(it.members.first().copy(addon = "Brazuca")) }, fact, "all", "all")
        assertTrue(chips.any { it.key == "f:dub" })
        assertTrue(chips.any { it.key == "f:1080" })
    }

    @Test
    fun contagemNaoEncolheComFiltroLigado() {
        val chips = sourceChips(groups, fact, "dub", "Brazuca").associate { it.key to it }
        assertEquals("Todas 3", chips["f:all"]!!.label)
        assertTrue(chips["f:dub"]!!.selected)
        assertTrue(chips["a:Brazuca"]!!.selected)
        assertTrue(!chips["a:Torrentio"]!!.selected)
    }

    @Test
    fun filtroEAddonSeCombinam() {
        assertEquals(6, filterReleases(groups, fact, "all", "all").size)
        assertEquals(2, filterReleases(groups, fact, "dub", "all").size)
        assertEquals(1, filterReleases(groups, fact, "dub", "Torrentio").size) // o arquivo que os dois addons têm
        assertEquals(3, filterReleases(groups, fact, "all", "Brazuca").size)
        assertEquals(0, filterReleases(groups, fact, "4k", "Brazuca").size)
    }

    @Test
    fun addonEscolhidoViraFontePrincipalDoGrupo() {
        assertEquals("Torrentio", filterReleases(groups, fact, "dub", "Torrentio").single().lead.addon)
        val mixed = listOf(group(stream("A", "1080p Dublado", 1), stream("B", "1080p Legendado", 1)))
        assertTrue(filterReleases(mixed, fact, "dub", "B").isEmpty())
        assertEquals("B", filterReleases(mixed, fact, "leg", "B").single().lead.addon)
    }

    @Test
    fun textoDeContagem() {
        assertEquals("12 versões em 20 fontes", sourceSummary(12, 12, 20, false))
        assertEquals("1 versão em 1 fonte", sourceSummary(1, 1, 1, false))
        assertEquals("5 de 12 versões", sourceSummary(12, 5, 20, true))
    }

    @Test
    fun filtrosLigadosEmUmaFrase() {
        assertEquals("", activeFilters("all", "all"))
        assertEquals("Dublado", activeFilters("dub", "all"))
        assertEquals("Torrentio", activeFilters("all", "Torrentio"))
        assertEquals("4K e Torrentio", activeFilters("4k", "Torrentio"))
    }

    @Test
    fun secaoSegueAPosicaoDoAddonNaConfiguracao() {
        // A lista chega na ordem da configuração, com o Brazuca antes do Torrentio.
        val ranks = addonRanks(groups)
        assertEquals(mapOf("Brazuca" to 0, "Torrentio" to 1), ranks)
        // O arquivo que os dois addons têm fica na seção do Brazuca, que vem primeiro.
        assertEquals("Brazuca", sectionOf(groups[1], ranks))
        assertEquals("Torrentio", sectionOf(groups[2], ranks))
        // Mesmo quando o Brazuca não é o primeiro membro do grupo.
        assertEquals("Brazuca", sectionOf(group(stream("Torrentio", "Filme 1080p", 9), stream("Brazuca", "Filme 1080p", 9)), ranks))
    }

    @Test
    fun versoesFicamJuntasPorAddon() {
        val ranks = addonRanks(groups)
        assertEquals(
            listOf("Brazuca", "Brazuca", "Brazuca", "Torrentio", "Torrentio", "Torrentio"),
            byAddon(groups, ranks).map { sectionOf(it, ranks) },
        )
        // Chegando com o Torrentio na frente, a seção do Brazuca ainda vem primeiro e dentro de cada seção vale a ordem recebida.
        val shuffled = listOf(groups[2], groups[0], groups[3], groups[1])
        assertEquals(listOf(groups[0], groups[1], groups[2], groups[3]), byAddon(shuffled, ranks))
    }

    @Test
    fun contagemDaSecao() {
        assertEquals("4 versões", sectionCount(4, 4))
        assertEquals("1 versão", sectionCount(1, 1))
        assertEquals("3 de 5 versões", sectionCount(3, 5))
    }
}
