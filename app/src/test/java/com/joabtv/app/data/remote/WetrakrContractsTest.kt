package com.joabtv.app.data.remote

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** WeTrakr conforme a doc em api.wetrakr.com. As linhas saem dos exemplos e das tabelas da doc. */
class WetrakrContractsTest {
    private fun at(iso: String) = trackerDate(iso)

    @Test fun minhaListaTrazTituloEPosterDaPropriaLista() {
        val body = """[
            {"type":"movie","id":126,"title":"The Dark Knight","release_date":"2008-07-18T12:00:00.000Z","poster_path":"/qJ2tW6WMUDux911r6m7haRef0WH.jpg","ids":{"tmdb":155,"imdb":"tt0468569"},"status":"planning","tracked_at":"2026-09-20T18:02:00.000Z"},
            {"type":"movie","id":7,"title":"Sem TMDB","ids":{"imdb":"tt1"}}]"""
        val items = wetrakrListItems(body, false)
        assertEquals(1, items.size)
        with(items[0]) {
            assertEquals(155, tmdbId)
            assertFalse(series)
            assertEquals("The Dark Knight", title)
            assertEquals("https://image.tmdb.org/t/p/w342/qJ2tW6WMUDux911r6m7haRef0WH.jpg", poster)
            assertEquals(at("2026-09-20T18:02:00.000Z"), changedAt)
        }
        val show = wetrakrListItems("""[{"type":"show","id":2,"title":"Breaking Bad","ids":{"tmdb":1396}}]""", true).single()
        assertTrue(show.series)
        assertEquals(1396, show.tmdbId)
        assertEquals("", show.poster)
        assertTrue(wetrakrListItems("not json", false).isEmpty())
    }

    @Test fun pontoOndeParouVemDoBlocoPlayback() {
        val movie = """[{"type":"movie","id":5,"title":"Pizza Movie","poster_path":"/p.jpg","ids":{"tmdb":1053809},
            "playback":{"status":"paused","progress_percent":7,"runtime_seconds":5880,"tracked_at":"2026-08-15T21:31:34.956Z"}}]"""
        val m = wetrakrPlaybackItems(movie).single()
        assertEquals(1053809, m.tmdbId)
        assertFalse(m.series)
        assertEquals(7.0, m.progress, 0.0)
        assertEquals(5_880_000L, m.durationMs)
        assertEquals(at("2026-08-15T21:31:34.956Z"), m.changedAt)
        assertEquals("https://image.tmdb.org/t/p/w342/p.jpg", m.poster)
        val episode = """[{"type":"episode","id":9,"ids":{"tmdb":777},"season_number":2,"number":4,
            "show":{"id":3,"title":"Série B","ids":{"tmdb":1399}},
            "playback":{"status":"paused","progress_percent":25,"runtime_seconds":3000,"tracked_at":"2026-08-15T22:00:00.000Z"}}]"""
        val e = wetrakrPlaybackItems(episode).single()
        assertEquals(1399, e.tmdbId)
        assertTrue(e.series)
        assertEquals("Série B", e.title)
        assertEquals(2, e.season)
        assertEquals(4, e.episode)
        assertEquals(25.0, e.progress, 0.0)
        // Sem o bloco playback ou sem data não há onde retomar.
        assertTrue(wetrakrPlaybackItems("""[{"type":"movie","ids":{"tmdb":1}}]""").isEmpty())
        assertTrue(wetrakrPlaybackItems("""[{"type":"movie","ids":{"tmdb":1},"playback":{"progress_percent":5}}]""").isEmpty())
    }

    @Test fun episodiosVistosAceitamLinhaCompactaECompleta() {
        val compact = """[{"type":"episode","id":5092071,"ids":{"tmdb":5119022},"show_id":1333953,"show_ids":{"tmdb":245318},
            "season_id":426081,"season_number":1,"number":3,"last_watched_at":"2026-09-15T21:09:53.772Z","plays":1}]"""
        val c = wetrakrWatchedItems(compact).single()
        assertEquals(245318, c.tmdbId)
        assertTrue(c.series)
        assertEquals(1, c.season)
        assertEquals(3, c.episode)
        assertEquals(at("2026-09-15T21:09:53.772Z"), c.changedAt)
        val full = """[{"type":"episode","id":1,"ids":{"tmdb":1},"season_number":0,"number":2,"watched_at":"2026-09-15T21:09:53.772Z",
            "watched_count":2,"show":{"id":3,"title":"X","ids":{"tmdb":1399}}}]"""
        val f = wetrakrWatchedItems(full).single()
        assertEquals(1399, f.tmdbId)
        assertEquals(0, f.season)
        assertEquals(2, f.episode)
        // Linha sem número do episódio ou sem a série não entra.
        assertTrue(wetrakrWatchedItems("""[{"type":"episode","show_ids":{"tmdb":1},"season_number":1}]""").isEmpty())
        assertTrue(wetrakrWatchedItems("""[{"type":"episode","season_number":1,"number":1}]""").isEmpty())
    }

    // Linhas copiadas das respostas reais do WeTrakr em 17/11/2026. A lista de retomar não traz runtime_seconds,
    // e nem a de planning nem a de playing traz poster_path. O Android completa os dois com o TMDB.
    @Test fun linhasReaisDoWetrakrEntramNosParsers() {
        val planning = """[{"type":"movie","ids":{"tmdb":603,"imdb":"tt0133093"},"release_date":"1999-03-31T12:00:00.000Z","title":"The Matrix","id":483,"release_date_country":"US"}]"""
        val lista = wetrakrListItems(planning, false).single()
        assertEquals(603, lista.tmdbId)
        assertEquals("The Matrix", lista.title)
        assertEquals("", lista.poster)
        assertEquals(0L, lista.changedAt)

        val playingMovie = """[{"type":"movie","ids":{"tmdb":603,"imdb":"tt0133093"},"release_date":"1999-03-31T12:00:00.000Z","title":"The Matrix","id":483,"release_date_country":"US","playback":{"status":"paused","progress_percent":30,"tracked_at":"2026-11-18T02:32:54.019Z"}}]"""
        val filme = wetrakrPlaybackItems(playingMovie).single()
        assertEquals(603, filme.tmdbId)
        assertFalse(filme.series)
        assertEquals(30.0, filme.progress, 0.0)
        assertEquals(0L, filme.durationMs)
        assertEquals(at("2026-11-18T02:32:54.019Z"), filme.changedAt)

        val playingEpisode = """[{"id":174658,"media_id":1391953,"season_id":135905,"ids":{"tmdb":62085,"imdb":"tt0959621","tvdb":349232},"air_date":"2008-01-20T00:00:00.000Z","season_number":1,"number":1,"type":"episode","milestone":"series_premiere","season_poster_path":"/1BP4xYv9ZG4ZVHkL7ocOziBbSYH.jpg","air_datetime":"2008-01-21T02:00:00.000Z","show":{"id":1391953,"title":"Breaking Bad","ids":{"tmdb":1396,"imdb":"tt0903747","tvdb":81189}},"playback":{"status":"paused","progress_percent":33,"tracked_at":"2026-11-18T02:33:13.388Z"}}]"""
        val episodio = wetrakrPlaybackItems(playingEpisode).single()
        assertEquals(1396, episodio.tmdbId)
        assertTrue(episodio.series)
        assertEquals("Breaking Bad", episodio.title)
        assertEquals(1, episodio.season)
        assertEquals(1, episodio.episode)
        assertEquals(33.0, episodio.progress, 0.0)
        assertEquals(0L, episodio.durationMs)

        val watched = """[{"type":"episode","id":174658,"ids":{"tmdb":62085,"imdb":"tt0959621","tvdb":349232},"show_id":1391953,"show_ids":{"tmdb":1396,"imdb":"tt0903747","tvdb":81189},"season_id":135905,"season_number":1,"number":1,"last_watched_at":"2026-11-18T02:33:14.924Z","plays":1}]"""
        val visto = wetrakrWatchedItems(watched).single()
        assertEquals(1396, visto.tmdbId)
        assertEquals(1, visto.season)
        assertEquals(1, visto.episode)
        assertEquals(at("2026-11-18T02:33:14.924Z"), visto.changedAt)
    }

    @Test fun sincroniaSoPuxaASecaoQueMudou() {
        val activities = JSONObject("""{"all":"x",
            "movies":{"last_tracking_planning_at":"2026-11-17T10:00:00.000Z","last_playback_at":"2026-11-17T11:00:00.000Z"},
            "shows":{"last_tracking_planning_at":"2026-11-17T08:00:00.000Z"},
            "episodes":{"last_tracking_watched_at":"2026-11-17T12:00:00.000Z","last_playback_at":"2026-11-17T13:00:00.000Z"}}""")
        val sections = wetrakrSections(activities).associateBy { it.key }
        assertEquals(listOf("list", "playing", "watched"), wetrakrSections(activities).map { it.key })
        assertEquals("2026-11-17T10:00:00.000Z|2026-11-17T08:00:00.000Z", sections.getValue("list").signature)
        assertEquals("2026-11-17T10:00:00.000Z", sections.getValue("list").newest)
        assertEquals("2026-11-17T13:00:00.000Z", sections.getValue("playing").newest)
        assertEquals("2026-11-17T12:00:00.000Z", sections.getValue("watched").signature)
        // Um carimbo que anda muda só a assinatura da própria seção.
        val moved = JSONObject(activities.toString()).also { it.getJSONObject("episodes").put("last_tracking_watched_at", "2026-11-18T08:00:00.000Z") }
        val after = wetrakrSections(moved).associateBy { it.key }
        assertEquals(sections.getValue("list").signature, after.getValue("list").signature)
        assertEquals(sections.getValue("playing").signature, after.getValue("playing").signature)
        assertNotEquals(sections.getValue("watched").signature, after.getValue("watched").signature)
        // Conta que nunca usou o WeTrakr não tem o que puxar.
        val empty = wetrakrSections(JSONObject("""{"all":"2026-11-17T10:00:00.000Z"}""")).first()
        assertEquals("|", empty.signature)
        assertEquals("", empty.newest)
    }

    @Test fun cotaDoDiaEstouradaSeReconhecePeloCodigo() {
        assertTrue(wetrakrQuotaExceeded(429, """{"error":"APP_QUOTA_EXCEEDED","message":"This app has a development key"}"""))
        assertTrue(wetrakrQuotaExceeded(429, """{"error":"QUOTA_EXCEEDED"}"""))
        assertFalse(wetrakrQuotaExceeded(429, """{"message":"Too many requests. Please slow down."}"""))
        assertFalse(wetrakrQuotaExceeded(200, "QUOTA_EXCEEDED"))
    }

    @Test fun scrobbleDoWetrakrLevaIdsDoTmdbEAVersao() {
        val filme = trackerScrobbleBody(TrackerProvider.WETRAKR, 603, false, 0, 0, 12.5, "1.2.3")
        assertEquals(603, filme.getJSONObject("movie").getJSONObject("ids").getInt("tmdb"))
        assertEquals(12.5, filme.getDouble("progress"), 0.0)
        assertEquals("1.2.3", filme.getString("app_version"))
        assertFalse(filme.has("show"))
        val episodio = trackerScrobbleBody(TrackerProvider.WETRAKR, 1399, true, 2, 4, 96.0, "1.2.3")
        assertEquals(1399, episodio.getJSONObject("show").getJSONObject("ids").getInt("tmdb"))
        assertEquals(2, episodio.getJSONObject("episode").getInt("season"))
        assertEquals(4, episodio.getJSONObject("episode").getInt("number"))
        assertFalse(episodio.has("movie"))
        // Só o MDBList mostra o nome do app.
        assertFalse(episodio.has("app_name"))
    }

    @Test fun listaEnviaStatusPlanningNasDuasDirecoes() {
        val entrar = trackerWatchlistRequest(TrackerProvider.WETRAKR, 12, false, true)!!
        assertEquals("/sync/tracking", entrar.path)
        val item = entrar.body.getJSONArray("movies").getJSONObject(0)
        assertEquals("planning", item.getString("status"))
        assertEquals(12, item.getJSONObject("ids").getInt("tmdb"))
        val sair = trackerWatchlistRequest(TrackerProvider.WETRAKR, 1399, true, false)!!
        assertEquals("/sync/tracking/remove", sair.path)
        assertEquals("planning", sair.body.getJSONArray("shows").getJSONObject(0).getString("status"))
    }
}
