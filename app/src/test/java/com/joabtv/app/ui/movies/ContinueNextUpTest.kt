package com.joabtv.app.ui.movies

import com.joabtv.app.data.remote.dto.TmdbEpisodeDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueNextUpTest {
    // Meio-dia UTC de hoje, o mesmo instante que a fileira Novos episódios usa para "saiu".
    private val now = airDateMs("2026-11-17")!!
    private fun ep(n: Int, date: String?) = TmdbEpisodeDto(episodeNumber = n, seasonNumber = 1, airDate = date)

    @Test
    fun proximoJaLancadoNaMesmaTemporadaAparece() {
        val season = listOf(ep(5, "2026-11-10"), ep(6, "2026-11-17"))
        assertEquals("T1:E6", nextUpEpisode(1, 5, season, emptyList(), now))
    }

    @Test
    fun proximoQueAindaNaoSaiuFazASerieSumir() {
        assertNull(nextUpEpisode(1, 5, listOf(ep(5, "2026-11-10"), ep(6, "2026-11-24")), emptyList(), now))
        assertNull(nextUpEpisode(1, 5, listOf(ep(5, "2026-11-10"), ep(6, null)), emptyList(), now))
        assertNull(nextUpEpisode(1, 5, listOf(ep(5, "2026-11-10"), ep(6, "")), emptyList(), now))
    }

    @Test
    fun fimDaTemporadaVaiParaOE1DaSeguinteSoQuandoSaiu() {
        val last = listOf(ep(9, "2026-10-01"), ep(10, "2026-10-08"))
        assertEquals("T2:E1", nextUpEpisode(1, 10, last, listOf(ep(1, "2026-11-01")), now))
        assertNull(nextUpEpisode(1, 10, last, listOf(ep(1, "2027-01-15")), now))
        assertNull(nextUpEpisode(1, 10, last, listOf(ep(1, null)), now))
    }

    @Test
    fun semTemporadaSeguinteOuSemEpisodiosASerieSome() {
        val last = listOf(ep(10, "2026-10-08"))
        assertNull(nextUpEpisode(1, 10, last, emptyList(), now))
        assertNull(nextUpEpisode(1, 10, last, listOf(ep(2, "2026-11-01")), now))
    }

    @Test
    fun episodioSaiEVoltaSemApagarNada() {
        // A mesma série, o mesmo "terminei o E5". Muda só o relógio.
        val season = listOf(ep(5, "2026-11-10"), ep(6, "2026-11-24"))
        assertNull(nextUpEpisode(1, 5, season, emptyList(), now))
        assertEquals("T1:E6", nextUpEpisode(1, 5, season, emptyList(), airDateMs("2026-11-24")!!))
    }

    @Test
    fun regraDeLancamentoSemData() {
        assertTrue(episodeReleased("2026-11-17", now))
        assertFalse(episodeReleased("2026-11-18", now))
        assertFalse(episodeReleased(null, now))
        assertFalse(episodeReleased("", now))
        assertFalse(episodeReleased("sem data", now))
    }

    @Test
    fun temporadaVistaEsperaEpisodioAindaNaoVistoLancado() {
        val season = listOf(ep(1, "2026-01-01"), ep(2, "2026-01-02"), ep(3, "2026-01-03"))
        assertNull(nextUpEpisode(1, 1, season, listOf(ep(1, "2027-01-01")), now, setOf("T1:E2", "T1:E3")))
        assertEquals("T1:E3", nextUpEpisode(1, 1, season, emptyList(), now, setOf("T1:E2")))
    }
}
