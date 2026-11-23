package com.joabtv.app.data.remote

import org.junit.Assert.*
import org.junit.Test

class PortugueseAudioAliasesTest {
    @Test fun aliasesAndSubtitleLabels() {
        listOf("pt", "pt-br", "pt_br", "port", "por", "portuguese", "br", "dublado", "Dual Áudio").forEach {
            assertTrue(it, ptAudio("Filme 1080p $it"))
            assertEquals(it, "dub", langOf("Filme 1080p $it"))
        }
        listOf("BRRip", "Portal", "Transporter", "Legendado PT-BR", "Legendas PT-BR", "English dub", "Portuguese subtitles", "PT-BR Legendas").forEach { assertFalse(it, ptAudio(it)) }
        assertTrue(ptAudio("Áudio PT-BR\nLegendas Inglês"))
        assertEquals("leg", langOf("Audio ENG | Legendas PT-BR"))
        assertEquals("", langOf("English dub"))
    }
}
