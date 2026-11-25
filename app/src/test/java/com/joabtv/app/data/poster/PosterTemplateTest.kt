package com.joabtv.app.data.poster

import com.joabtv.app.data.poster.PosterTemplate.Ctx
import com.joabtv.app.domain.model.MediaType
import com.joabtv.app.domain.model.MovieItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mesmos casos do web e do Roku. Se mudar aqui, mudar lá. */
class PosterTemplateTest {
    private val full = "https://pp.example/poster?tmdb_id={tmdb_id?}&imdb_id={imdb_id?}&stremio_id={id}&type={type}&shape={shape}"
    private val short = "https://pp.example/poster?tmdb_id={tmdb_id}&type={type}"

    @Test fun filme_com_todos_os_ids() {
        assertEquals(
            "https://pp.example/poster?tmdb_id=603&imdb_id=tt0133093&stremio_id=tt0133093&type=movie&shape=poster",
            PosterTemplate.resolve(full, Ctx("603", "tt0133093", "tt0133093", "movie", "poster")),
        )
    }

    @Test fun serie_so_com_id_tmdb_opcional_vazio_e_id_codificado() {
        assertEquals(
            "https://pp.example/poster?tmdb_id=1399&imdb_id=&stremio_id=tmdb%3A1399&type=series&shape=poster",
            PosterTemplate.resolve(full, Ctx("1399", "", "tmdb:1399", "series", "poster")),
        )
    }

    @Test fun tipo_tv_e_anime_viram_series() {
        assertEquals(
            "https://pp.example/poster?tmdb_id=1&imdb_id=&stremio_id=tmdb%3A1&type=series&shape=poster",
            PosterTemplate.resolve(full, Ctx("1", "", "tmdb:1", "tv", "poster")),
        )
        assertEquals(
            "https://pp.example/poster?tmdb_id=&imdb_id=&stremio_id=kitsu%3A7442&type=series&shape=poster",
            PosterTemplate.resolve(full, Ctx("", "", "kitsu:7442", "anime", "poster")),
        )
    }

    @Test fun paisagem_usa_shape_landscape() {
        assertEquals(
            "https://pp.example/poster?tmdb_id=603&imdb_id=tt0133093&stremio_id=tt0133093&type=movie&shape=landscape",
            PosterTemplate.resolve(full, Ctx("603", "tt0133093", "tt0133093", "movie", "landscape")),
        )
    }

    @Test fun quadrado_nao_aplica_o_modelo() {
        assertNull(PosterTemplate.resolve(full, Ctx("603", "tt0133093", "tt0133093", "movie", "square")))
    }

    @Test fun obrigatorio_sem_valor_descarta_a_url() {
        assertNull(PosterTemplate.resolve(full, Ctx("603", "tt0133093", "", "movie", "poster")))
        assertNull(PosterTemplate.resolve(full, Ctx("603", "tt0133093", "tt0133093", null, "poster")))
        assertNull(PosterTemplate.resolve(short, Ctx("", "tt0133093", "tt0133093", "movie", "poster")))
    }

    @Test fun id_com_varios_dois_pontos() {
        assertEquals(
            "https://pp.example/poster?tmdb_id=&imdb_id=tt13491734&stremio_id=had%3Aepisode%3Att13491734%3A6%3A6&type=series&shape=poster",
            PosterTemplate.resolve(full, Ctx("", "tt13491734", "had:episode:tt13491734:6:6", "series", "poster")),
        )
    }

    @Test fun modelo_sem_shape_troca_so_o_retrato() {
        assertEquals(
            "https://pp.example/poster?tmdb_id=603&type=movie",
            PosterTemplate.resolve(short, Ctx("603", null, "tt0133093", "movie", "poster")),
        )
        assertNull(PosterTemplate.resolve(short, Ctx("603", null, "tt0133093", "movie", "landscape")))
        assertFalse(PosterTemplate.hasShape(short))
        assertTrue(PosterTemplate.hasShape(full))
    }

    @Test fun marcador_desconhecido_e_chave_em_maiuscula() {
        val c = Ctx("7", null, "a", "movie", "poster")
        assertNull(PosterTemplate.resolve("https://pp.example/p?x={foo}", c))
        assertEquals("https://pp.example/p?x=&t=movie", PosterTemplate.resolve("https://pp.example/p?x={foo?}&t={type}", c))
        assertEquals("https://pp.example/p?t=7", PosterTemplate.resolve("https://pp.example/p?t={TMDB_ID?}", c))
    }

    @Test fun modelo_vazio_ou_com_esquema_que_nao_e_http() {
        val c = Ctx("1", null, "a", "movie", "poster")
        assertNull(PosterTemplate.resolve("", c))
        assertNull(PosterTemplate.resolve(null, c))
        assertNull(PosterTemplate.resolve("javascript:alert({id})", c))
        assertNull(PosterTemplate.resolve("file:///c:/x/{id}.png", c))
    }

    @Test fun forma_do_addon() {
        assertEquals("landscape", PosterTemplate.shapeOf("landscape"))
        assertEquals("landscape", PosterTemplate.shapeOf(" Wide "))
        assertEquals("square", PosterTemplate.shapeOf("SQUARE"))
        assertEquals("poster", PosterTemplate.shapeOf("poster"))
        assertEquals("poster", PosterTemplate.shapeOf(null))
        assertEquals("poster", PosterTemplate.shapeOf("regular"))
    }

    @Test fun detecta_poster_que_ja_vem_com_selos() {
        assertTrue(PosterTemplate.isCustomUrl("https://postersplus.slokker.cc/poster?tmdb_id=1&type=movie"))
        assertTrue(PosterTemplate.isCustomUrl("HTTPS://PostersPlus.Example/poster?imdb_id=tt1&type=series&shape=poster"))
        assertTrue(PosterTemplate.isCustomUrl("https://meu-servidor.example/poster?stremio_id=tt1&type=series"))
        assertTrue(PosterTemplate.isCustomUrl("https://api.ratingposterdb.com/CHAVE/imdb/poster-default/tt1.jpg?fallback=true"))
        assertFalse(PosterTemplate.isCustomUrl("https://image.tmdb.org/t/p/w500/abc.jpg"))
        assertFalse(PosterTemplate.isCustomUrl("https://images.metahub.space/poster/small/tt0111161/img"))
        assertFalse(PosterTemplate.isCustomUrl("https://theposterdb.com/api/assets/123"))
        assertFalse(PosterTemplate.isCustomUrl("https://exemplo.com/poster"))
        assertFalse(PosterTemplate.isCustomUrl(""))
        assertFalse(PosterTemplate.isCustomUrl(null))
    }

    private val tmdbPoster = "https://image.tmdb.org/t/p/w500/a.jpg"
    private fun item(
        tmdbId: Int = 603, poster: String = tmdbPoster, backdrop: String = "https://image.tmdb.org/t/p/w1280/b.jpg",
        type: MediaType = MediaType.MOVIE, sourceId: String = "", imdbId: String = "",
    ) = MovieItem(
        tmdbId = tmdbId, embedId = tmdbId.toString(), title = "Matrix", overview = "", posterUrl = poster, backdropUrl = backdrop,
        year = "1999", rating = 8.0, type = type, sourceId = sourceId, imdbId = imdbId,
    )

    @Test fun arte_do_modelo_vence_a_do_tmdb_e_guarda_a_propria_para_a_falha() {
        val art = PosterTemplate.artFor(item(), full, "poster")
        assertEquals("https://pp.example/poster?tmdb_id=603&imdb_id=&stremio_id=tmdb%3A603&type=movie&shape=poster", art.url)
        assertTrue(art.plain)
        assertEquals(tmdbPoster, art.fallback)
    }

    @Test fun sem_modelo_a_arte_e_a_propria_e_leva_os_selos_do_app() {
        val art = PosterTemplate.artFor(item(), "", "poster")
        assertEquals(PosterTemplate.Art(tmdbPoster, plain = false), art)
        assertEquals(PosterTemplate.Art(tmdbPoster, plain = false), PosterTemplate.artFor(item(), null, "poster"))
    }

    @Test fun arte_que_o_addon_ja_personalizou_vale_mais_que_o_modelo() {
        val own = "https://postersplus.cc/poster?tmdb_id=603&type=movie&rv=1"
        val art = PosterTemplate.artFor(item(poster = own), full, "poster")
        assertEquals(PosterTemplate.Art(own, plain = true), art)
    }

    @Test fun modelo_que_nao_se_aplica_deixa_a_arte_propria_com_selos() {
        // Modelo curto pede o id do TMDB e o título não tem. Quadrado nunca usa o modelo. Paisagem pede {shape} no modelo.
        assertEquals(PosterTemplate.Art(tmdbPoster, plain = false), PosterTemplate.artFor(item(tmdbId = 0), short, "poster"))
        assertEquals(PosterTemplate.Art(tmdbPoster, plain = false), PosterTemplate.artFor(item(), full, "square"))
        assertEquals(PosterTemplate.Art(tmdbPoster, plain = false), PosterTemplate.artFor(item(), short, "landscape"))
    }

    @Test fun arte_paisagem_sem_poster_cai_no_fundo() {
        val own = "https://image.tmdb.org/t/p/w780/land.jpg"
        val art = PosterTemplate.artFor(item(poster = "", backdrop = own), short, "landscape")
        assertEquals(PosterTemplate.Art(own, plain = false), art)
    }

    @Test fun id_do_addon_e_o_imdb_chegam_no_modelo() {
        val ctx = PosterTemplate.ctxOf(item(tmdbId = 7442, type = MediaType.SERIES, sourceId = "kitsu:7442"), "poster")
        assertEquals(Ctx("7442", null, "kitsu:7442", "series", "poster"), ctx)
        val imdb = PosterTemplate.ctxOf(item(sourceId = "tt0133093"), "landscape")
        assertEquals(Ctx("603", "tt0133093", "tt0133093", "movie", "landscape"), imdb)
        val fromMeta = PosterTemplate.ctxOf(item(sourceId = "tmdb:603", imdbId = "tt0133093"), "poster")
        assertEquals("tt0133093", fromMeta.imdbId)
        assertEquals("tmdb:603", fromMeta.id)
    }

    @Test fun modelo_so_vale_se_for_link_http() {
        assertTrue(PosterTemplate.isValid(""))
        assertTrue(PosterTemplate.isValid("  "))
        assertTrue(PosterTemplate.isValid(PosterTemplate.PRESET_PUBLIC))
        assertTrue(PosterTemplate.isValid(" HTTP://x.example/p?id={id} "))
        assertFalse(PosterTemplate.isValid("postersplus.cc/poster"))
        assertFalse(PosterTemplate.isValid("https://x.example/p com espaco"))
        assertFalse(PosterTemplate.isValid("javascript:alert(1)"))
        // O modelo pronto do servidor público resolve para um link de pôster de verdade.
        assertEquals(
            "https://postersplus.cc/poster?tmdb_id=603&imdb_id=tt0133093&stremio_id=tt0133093&type=movie&shape=poster",
            PosterTemplate.resolve(PosterTemplate.PRESET_PUBLIC, Ctx("603", "tt0133093", "tt0133093", "movie", "poster")),
        )
    }

    @Test fun largura_e_proporcao_por_forma() {
        assertEquals(1f, PosterTemplate.widthFactor("poster"), 0f)
        assertEquals(1.72f, PosterTemplate.widthFactor("landscape"), 0f)
        assertEquals(1.17f, PosterTemplate.widthFactor("square"), 0f)
        assertEquals(16f / 9f, PosterTemplate.aspectOf("landscape"), 0f)
        assertEquals(1f, PosterTemplate.aspectOf("square"), 0f)
        assertEquals(2f / 3f, PosterTemplate.aspectOf("poster"), 0f)
        assertEquals(2f / 3f, PosterTemplate.aspectOf("qualquer"), 0f)
    }
}
