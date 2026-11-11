package com.joabtv.app.ui.movies

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewFrameCacheTest {
    @Test fun trocarDeFilmeNuncaReaproveitaAImagemDaMesmaPosicaoDoAnterior() {
        val cache = PreviewFrameCache<String>()
        cache.selectSource("filme-a")
        cache[10] = "quadro-a"
        cache.selectSource("filme-b")
        assertFalse(10L in cache)
        cache[10] = "quadro-b"
        assertEquals("quadro-b", cache[10])
    }

    @Test fun repetirAFonteMantemOsQuadrosJaLidos() {
        val cache = PreviewFrameCache<String>()
        cache.selectSource("filme-a")
        cache[10] = "quadro"
        cache.selectSource("filme-a")
        assertEquals("quadro", cache[10])
    }

    @Test fun resultadoSemQuadroTambemFicaEmCache() {
        val cache = PreviewFrameCache<String>()
        cache.selectSource("fonte-sem-previa")
        cache[10] = null
        assertTrue(10L in cache)
        assertNull(cache[10])
    }

    @Test fun limiteRemoveOQuadroMenosUsadoEmVezDoMaisRecente() {
        val cache = PreviewFrameCache<String>(2)
        cache.selectSource("filme-a")
        cache[1] = "um"
        cache[2] = "dois"
        assertEquals("um", cache[1])
        cache[3] = "tres"
        assertTrue(1L in cache)
        assertFalse(2L in cache)
        assertEquals("tres", cache[3])
    }

    @Test fun limpezaNaoRetemImagensDoPlayerAnterior() {
        val cache = PreviewFrameCache<String>()
        cache.selectSource("filme-a")
        cache[1] = "um"
        cache.clear()
        assertFalse(1L in cache)
    }

    @Test fun miniaturaDe4kMantemAProporcaoSemAlocarOQuadroInteiro() {
        assertEquals(160 to 90, previewFrameSize(3840, 2160, 0))
        assertEquals(160 to 284, previewFrameSize(1920, 1080, 90))
        assertEquals(120 to 80, previewFrameSize(120, 80, 0))
        assertNull(previewFrameSize(0, 1080, 0))
    }
}
