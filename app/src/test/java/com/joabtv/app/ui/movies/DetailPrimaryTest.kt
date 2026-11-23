package com.joabtv.app.ui.movies

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Quando o botão principal do detalhe vira Continuar. O card do episódio mostra Vendo pelo progresso, o botão segue o mesmo. */
class DetailPrimaryTest {
    private val min = 60_000L
    private val ep = 25 * min

    @Test
    fun serieComEpisodioEmAndamentoContinuaMesmoSemFonteSalva() {
        // O caso do Linux com o E5 em 25 por cento. O progresso veio de outro aparelho e não traz a fonte.
        assertTrue(canResumePlayback(isSeries = true, hasSavedSource = false, season = 1, episode = 5, positionMs = 6 * min, durationMs = ep))
    }

    @Test
    fun serieComFonteSalvaSegueContinuando() {
        assertTrue(canResumePlayback(true, true, 1, 5, 6 * min, ep))
        // Sem o episódio gravado a fonte salva ainda toca direto, como sempre foi.
        assertTrue(canResumePlayback(true, true, 0, 0, 6 * min, ep))
    }

    @Test
    fun serieSemEpisodioGravadoENemFonteNaoTemComoRetomar() {
        assertFalse(canResumePlayback(true, false, 0, 0, 6 * min, ep))
        assertFalse(canResumePlayback(true, false, 1, 0, 6 * min, ep))
    }

    @Test
    fun filmeSoContinuaComAFonteSalva() {
        assertTrue(canResumePlayback(false, true, 0, 0, 40 * min, 120 * min))
        assertFalse(canResumePlayback(false, false, 0, 0, 40 * min, 120 * min))
    }

    @Test
    fun menosDeDezSegundosOuQuaseNoFimNaoContam() {
        assertFalse(canResumePlayback(true, true, 1, 5, 9_000, ep))
        assertFalse(canResumePlayback(true, false, 1, 5, 10_000, ep))
        assertTrue(canResumePlayback(true, false, 1, 5, 10_001, ep))
        assertFalse(canResumePlayback(true, true, 1, 5, (ep * 0.95).toLong(), ep))
        assertTrue(canResumePlayback(true, true, 1, 5, (ep * 0.94).toLong(), ep))
    }

    @Test
    fun duracaoDesconhecidaNaoImpedeContinuar() {
        assertTrue(canResumePlayback(true, false, 1, 5, 6 * min, 0))
    }
}
