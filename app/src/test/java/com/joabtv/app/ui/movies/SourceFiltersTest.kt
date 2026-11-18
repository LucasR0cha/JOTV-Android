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
    fun listaPequenaDeUmAddonFicaSemBarra() {
        assertTrue(sourceChips(groups.take(4).map { group(it.members.first().copy(addon = "Brazuca")) }, fact, "all", "all").isEmpty())
    }

    @Test
    fun contagemNaoEncolheComFiltroLigado() {
        val chips = sourceChips(groups, fact, "dub", "Brazuca").associate { it.key to it }
        assertEquals("Todas 6", chips["f:all"]!!.label)
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
    fun textoDeContagem() {
        assertEquals("12 versões em 20 fontes", sourceSummary(12, 12, 20, false))
        assertEquals("1 versão em 1 fonte", sourceSummary(1, 1, 1, false))
        assertEquals("5 de 12 versões", sourceSummary(12, 5, 20, true))
    }
}
