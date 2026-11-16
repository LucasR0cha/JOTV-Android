package com.joabtv.app.data.remote

import org.junit.Assert.*
import org.junit.Test

class TrackerMdblistSyncTest {
    @Test fun serieDaListaSoComIdEntra() {
        // Exemplo do esquema oficial de GET /watchlist/items.
        val body = """{"movies":[{"id":917496,"title":"Beetlejuice Beetlejuice","ids":{"mdblist":"m917496","imdb":"tt2049403","tmdb":917496,"tvdb":null},"mediatype":"movie"}],
            "shows":[{"id":258902,"title":"English Teacher","imdb_id":"tt20782190","tvdb_id":421968,"mediatype":"show"}],"pagination":{"next_cursor":"abc=="}}"""
        val items = trackerItems(body, plainId = true)
        assertEquals(listOf(917496 to false, 258902 to true), items.map { it.tmdbId to it.series })
        assertEquals("abc==", mdblistNextCursor(body))
        // Sem plainId (outros serviços) o id solto não vira TMDB.
        assertEquals(listOf(917496), trackerItems(body).map { it.tmdbId })
    }

    @Test fun episodiosVistosIdsOnlyEFormatoCompleto() {
        val idsOnly = """{"episodes":[{"tmdb":111111,"last_watched_at":"2026-10-17T21:30:00Z","season":2,"episode":5,"show":1000},
            {"tmdb":2,"season":0,"episode":1,"show":1000},{"tmdb":3,"season":1,"episode":0,"show":1000}],"pagination":{"limit":1000,"next_cursor":"c2"}}"""
        assertEquals(listOf(Triple(1000, 2, 5), Triple(1000, 0, 1)), mdblistWatchedEpisodes(idsOnly))
        assertEquals("c2", mdblistNextCursor(idsOnly))
        // Exemplo do esquema oficial no formato completo, última página.
        val full = """{"movies":[],"shows":[],"seasons":[],"episodes":[{"last_watched_at":"2026-10-17T21:30:00.000Z","episode":{"season":2,"number":5,"ids":{"tmdb":111111},
            "show":{"title":"Example Show","ids":{"tmdb":1000,"trakt":2000}}},"tvdb":{"season":23,"number":5}}],"pagination":{"limit":100,"next_cursor":null}}"""
        assertEquals(listOf(Triple(1000, 2, 5)), mdblistWatchedEpisodes(full))
        assertNull(mdblistNextCursor(full))
    }

    @Test fun lastActivitiesSoLiberaQuandoMuda() {
        assertTrue(mdblistWatchedChanged("2026-11-15T10:00:00Z", ""))
        assertFalse(mdblistWatchedChanged("2026-11-15T10:00:00Z", "2026-11-15T10:00:00Z"))
        assertTrue(mdblistWatchedChanged("2026-11-15T11:00:00Z", "2026-11-15T10:00:00Z"))
        assertTrue(mdblistWatchedChanged("", "2026-11-15T10:00:00Z"))
    }

    @Test fun playbackDoMdblistAchaTmdbETemporada() {
        val body = """[{"id":1,"progress":42.5,"paused_at":"2026-11-15T10:00:00Z","runtime":45,"type":"episode",
            "episode":{"season":3,"number":7,"ids":{"tmdb":999}},"show":{"title":"Série","ids":{"tmdb":1399}}},
            {"id":2,"progress":10,"paused_at":"2026-11-14T10:00:00Z","runtime":120,"type":"movie","movie":{"title":"Filme","ids":{"tmdb":550}}}]"""
        val (ep, movie) = trackerItems(body)
        assertEquals(1399, ep.tmdbId); assertTrue(ep.series); assertEquals(3, ep.season); assertEquals(7, ep.episode)
        assertEquals(42.5, ep.progress, .001)
        assertTrue(ep.changedAt > 0)
        assertEquals(45 * 60_000L, ep.durationMs)
        assertEquals(550, movie.tmdbId); assertFalse(movie.series)
    }
}
