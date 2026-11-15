package com.joabtv.app.data.remote

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AnimeSkipTest {
    @Test fun marcasDeVersaoMaisLongaNaoContaminamOsPosCreditos() {
        val wrongCut = SkipSegments(intro = SkipSegment(90000, 150000), outro = SkipSegment(1434960, 1524920))
        val exact = SkipSegments(intro = SkipSegment(123465, 215083), outro = SkipSegment(1400416, 1432000), postCredits = SkipSegment(1432000, 1449992))
        val merged = mergeSkipSegments(listOf(wrongCut, null, exact), 1450.0)
        assertEquals(exact, merged)
        val overlapping = exact.copy(outro = SkipSegment(1400416, 1450000))
        assertEquals(1432000L, mergeSkipSegments(listOf(overlapping), 1450.0).outro?.end)
    }

    @Test fun episodioDaSegundaTemporadaNaoUsaAberturaDaPrimeira() {
        val shows = JSONArray("""[{"name":"Anime Teste","episodes":[{"id":"s1","season":"1","number":"1","baseDuration":1440},{"id":"s2","season":"2","number":"1","baseDuration":1440},{"id":"especial","season":"2","number":"1.5","baseDuration":1440}]}]""")
        assertEquals("s2", selectAnimeEpisode(shows, "Anime Teste", 2, 1, 1440.0)?.optString("id"))
        assertNull(selectAnimeEpisode(shows, "Anime Teste", 3, 1, 1440.0))
        assertNull(selectAnimeEpisode(shows, "Outro Anime", 2, 1, 1440.0))
        assertNull(selectAnimeEpisode(shows, "Anime Teste", 2, 1, 2800.0))
    }

    @Test fun cenaCanonicaDepoisDosCreditosViraPosCreditos() {
        val episode = JSONObject("""{"baseDuration":1450,"timestamps":[{"at":0,"type":{"name":"Canon"}},{"at":123.465,"type":{"name":"New Intro"}},{"at":215.083,"type":{"name":"Canon"}},{"at":1400.416,"type":{"name":"Mixed Credits"}},{"at":1432,"type":{"name":"Canon"}}]}""")
        val result = animeSkipSegments(episode, 1450.0)
        assertEquals(123465L, result.intro?.start)
        assertEquals(1432000L, result.outro?.end)
        assertEquals(1432000L, result.postCredits?.start)
        assertEquals(1450000L, result.postCredits?.end)
    }
}
