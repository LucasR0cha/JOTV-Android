package com.joabtv.app.ui.movies

import com.joabtv.app.domain.model.EpisodeItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EpisodeWatchRangeTest {
    private fun ep(season: Int, number: Int) = EpisodeItem(number, season, "", "", "")

    @Test fun anterioresIncluemTemporadasAnterioresSemIncluirEpisodioAtual() = runBlocking {
        val result = collectEpisodeWatchRange(ep(2, 2), true) { season -> listOf(ep(season, 1), ep(season, 2), ep(season, 3)) }
        assertEquals(listOf(1 to 1, 1 to 2, 1 to 3, 2 to 1), result)
    }

    @Test fun temporadaInteiraNaoIncluiEspeciaisNemTemporadaErrada() = runBlocking {
        val result = collectEpisodeWatchRange(ep(2, 2), false) { listOf(ep(0, 1), ep(2, 1), ep(2, 2), ep(2, 3), ep(3, 1)) }
        assertEquals(listOf(2 to 1, 2 to 2, 2 to 3), result)
    }

    @Test fun falhaAoCarregarTemporadaInterrompeSelecao() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking { collectEpisodeWatchRange(ep(2, 2), true) { season -> if (season == 2) error("offline") else listOf(ep(1, 1)) } }
        }
    }
}
