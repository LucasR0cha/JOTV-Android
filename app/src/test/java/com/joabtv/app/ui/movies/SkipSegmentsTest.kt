package com.joabtv.app.ui.movies

import com.joabtv.app.data.remote.SkipSegment
import com.joabtv.app.data.remote.SkipSegments
import org.junit.Assert.*
import org.junit.Test

class SkipSegmentsTest {
    private val segments = SkipSegments(SkipSegment(0, 30000), SkipSegment(40000, 130000), SkipSegment(500000, 600000), SkipSegment(570000, 590000))

    @Test fun tiposDeTrechoMantemDestinoEPosCreditosPrevalecemSobreEncerramento() {
        assertEquals("Pular resumo", calculateActiveSegment(segments, null, "MOVIE", 0, -1, 10000)?.label)
        assertEquals(130000L, calculateActiveSegment(segments, null, "MOVIE", 0, -1, 50000)?.targetMs)
        assertEquals(570000L, calculateActiveSegment(segments, null, "MOVIE", 0, -1, 510000)?.targetMs)
        assertEquals("Ir para pós-créditos", calculateActiveSegment(segments, null, "MOVIE", 0, -1, 510000)?.label)
        assertEquals("Pular pós-créditos", calculateActiveSegment(segments, null, "MOVIE", 0, -1, 575000)?.label)
        assertEquals(590000L, calculateActiveSegment(segments, null, "MOVIE", 0, -1, 575000)?.targetMs)
        assertFalse(calculateActiveSegment(segments, null, "MOVIE", 0, -1, 575000)!!.isNext)
        assertTrue(calculateActiveSegment(segments, null, "SERIES", 1, -1, 575000)!!.isNext)
    }

    @Test fun trechoDeAberturaPuladoNaoReapareceAoMostrarControles() {
        assertNull(calculateActiveSegment(segments, null, "SERIES", 1, 1, 50000))
        assertNull(calculateActiveSegment(segments, null, "SERIES", 1, -1, 200000))
    }
}
