package com.joabtv.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackMemoryTest {
    private val gb = 1024L * 1024 * 1024
    private val mb = 1024 * 1024

    @Test fun tvDeUmOuDoisGigabytesTemReservaControladaParaVideo() {
        assertEquals(64 * mb, playbackBufferBytes(gb, false, false))
        assertEquals(64 * mb, playbackBufferBytes(2 * gb, false, false))
    }

    @Test fun aparelhoComMaisMemoriaMantemOBufferPadraoDoPlayer() {
        assertNull(playbackBufferBytes(4 * gb, false, false))
        assertNull(playbackBufferBytes(0, false, false))
    }

    @Test fun sinalDePoucaMemoriaDoAndroidTambemControlaOBuffer() {
        assertEquals(64 * mb, playbackBufferBytes(4 * gb, true, false))
    }

    @Test fun previaTemReservaMenorQueOFilmeSemAlterarFaixas() {
        assertEquals(16 * mb, playbackBufferBytes(gb, false, true))
        assertEquals(16 * mb, playbackBufferBytes(8 * gb, true, true))
        assertEquals(32 * mb, playbackBufferBytes(4 * gb, false, true))
    }
}
