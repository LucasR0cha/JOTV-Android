package com.joabtv.app.ui.movies

import org.junit.Assert.*
import org.junit.Test

class SubtitleParserTest {
    @Test fun srtComBomFracaoCurtaELinhasSemBlocoVazio() {
        val cues = parseSubs("\uFEFF1\r\n00:00:01,5 --> 00:00:03,25\r\nOlá\r\n2\r\n00:00:04,000 --> 00:00:05,000\r\nMundo")
        assertEquals(2, cues.size)
        assertEquals(1500L, cues[0].start)
        assertEquals(3250L, cues[0].end)
        assertEquals("Olá", cues[0].text)
        assertEquals("Mundo", cueAt(cues, 4500, 0))
    }

    @Test fun vttComPosicaoMarcacaoEEntidades() {
        val cues = parseSubs("WEBVTT\n\nidentificador\n01:02.100 --> 01:04.900 align:center\n<i>Ação &amp; emoção</i>\n")
        assertEquals("Ação & emoção", cues.single().text)
        assertEquals(62100L, cues.single().start)
    }

    @Test fun assDeAddonComVirgulaELinhaNova() {
        val cues = parseSubs("[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\nDialogue: 0,0:00:03.50,0:00:05.00,Default,,0,0,0,,{\\i1}Oi, mundo\\NDe novo")
        assertEquals("Oi, mundo\nDe novo", cues.single().text)
        assertEquals(3500L, cues.single().start)
        assertEquals("", cueAt(cues, 3500, 1000))
    }

    @Test fun erroHtmlENenhumTrechoValidoNaoViraramLegenda() {
        assertTrue(parseSubs("<html>Forbidden</html>").isEmpty())
        assertTrue(parseSubs("1\n00:00:03,000 --> 00:00:02,000\nInválida").isEmpty())
    }
}
