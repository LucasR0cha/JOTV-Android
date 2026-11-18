package com.joabtv.app.data.local

import org.junit.Assert.*
import org.junit.Test

class PlaybackCompletionTest {
    @Test fun `creditos contam como concluido sem exigir o ultimo quadro`() {
        assertFalse(playbackCompleted(89_999, 100_000))
        assertTrue(playbackCompleted(90_000, 100_000))
        assertTrue(playbackCompleted(100_000, 100_000))
        assertTrue(playbackCompleted(101_000, 100_000))
    }

    @Test fun `sem duracao ou sem progresso nao conclui por engano`() {
        assertFalse(playbackCompleted(0, 0))
        assertFalse(playbackCompleted(0, 100_000))
        assertFalse(playbackCompleted(99_000, 0))
        assertFalse(playbackCompleted(-1, 100_000))
        assertFalse(playbackCompleted(99_000, -1))
    }

    @Test fun `fim explicito sem duracao usa marcador minimo e valores grandes nao transbordam`() {
        assertTrue(playbackCompleted(1, 1))
        assertTrue(playbackCompleted(Long.MAX_VALUE, Long.MAX_VALUE))
        assertFalse(playbackCompleted(Long.MAX_VALUE / 2, Long.MAX_VALUE))
    }
}
