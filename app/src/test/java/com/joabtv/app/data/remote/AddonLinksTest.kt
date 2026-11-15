package com.joabtv.app.data.remote

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonLinksTest {

    @Test fun normaliza_stremio_e_sem_manifest() {
        assertEquals("https://torrentio.strem.fun/manifest.json", AddonLinks.normalizeManifestUrl("stremio://torrentio.strem.fun"))
        assertEquals("https://a.com/x/manifest.json", AddonLinks.normalizeManifestUrl("  a.com/x/ "))
        assertEquals("http://a.com/manifest.json", AddonLinks.normalizeManifestUrl("http://a.com/manifest.json"))
        assertEquals("https://a.com/Canais%20|%2024h/manifest.json", AddonLinks.normalizeManifestUrl("https://a.com/Canais | 24h"))
        assertEquals("https://a.com/a%7Cb/manifest.json", AddonLinks.normalizeManifestUrl("https://a.com/a%7Cb"))
        assertEquals("", AddonLinks.normalizeManifestUrl("   "))
    }

    @Test fun configuredUrl_igual_ao_webos() {
        // encodeURIComponent(JSON.stringify({"token":"a b","hd":"on","n":"5"})) no navegador.
        val url = AddonLinks.configuredUrl(
            "https://x.dev/manifest.json",
            linkedMapOf("token" to "a b", "hd" to "on", "n" to "5"),
        )
        assertEquals("https://x.dev/%7B%22token%22%3A%22a%20b%22%2C%22hd%22%3A%22on%22%2C%22n%22%3A%225%22%7D/manifest.json", url)
        // Sem escape de HTML do Gson ("=" continuaria "=" no JSON, e vira %3D no link).
        assertTrue(AddonLinks.configuredUrl("https://x.dev", mapOf("k" to "a=b")).contains("%22a%3Db%22"))
    }

    @Test fun catalogos_so_filme_e_serie_sem_extra_obrigatorio() {
        val m = JsonParser.parseString(
            """{"catalogs":[
              {"type":"movie","id":"top","name":"Populares"},
              {"type":"series","id":"s1"},
              {"type":"tv","id":"canais","name":"Canais"},
              {"type":"movie","id":"busca","extra":[{"name":"search","isRequired":true}]},
              {"type":"movie","id":"genero","extraRequired":["genre"]},
              {"type":"movie","id":"opcional","name":"Opc","extra":[{"name":"skip"}]},
              {"type":"movie","name":"sem id"}
            ]}""",
        ).asJsonObject
        val c = AddonLinks.homeCatalogs(m)
        assertEquals(listOf("top", "s1", "opcional"), c.map { it.id })
        assertEquals("s1", c[1].name) // sem nome usa o id
    }

    @Test fun catalogos_do_descobrir_trazem_os_filtros_do_manifest() {
        val m = JsonParser.parseString(
            """{"id":"org.x","name":"Streaming","catalogs":[
              {"type":"movie","id":"top","name":"Top 10","extra":[{"name":"genre","options":["Ação","Drama"]},{"name":"skip"}]},
              {"type":"movie","id":"ano","name":"Por ano","extra":[{"name":"year","options":["2025","2024"],"isRequired":true},{"name":"genre","options":["Ação"]}]},
              {"type":"series","id":"antigo","name":"Antigo","extraSupported":["genre","skip"],"extraRequired":["genre"],"genres":["Anime","Comédia"]},
              {"type":"movie","id":"busca","extra":[{"name":"search","isRequired":true}]},
              {"type":"movie","id":"sem-opcoes","extra":[{"name":"studio","isRequired":true}]},
              {"type":"tv","id":"canais"},
              {"type":"series","id":"simples"}
            ]}""",
        ).asJsonObject
        val c = AddonLinks.discoverCatalogs(m, "https://x.dev")
        // Busca obrigatória, filtro obrigatório sem opções e tipo tv ficam de fora.
        assertEquals(listOf("top", "ano", "antigo", "simples"), c.map { it.id })
        assertEquals("https://x.dev", c[0].base)
        assertEquals(listOf("Ação", "Drama"), c[0].genres)
        assertFalse(c[0].extras.single().required)
        // Filtro obrigatório com opções entra e fica sem a escolha "Todos".
        assertEquals(listOf("year", "genre"), c[1].extras.map { it.name })
        assertTrue(c[1].extras[0].required)
        // Formato antigo, com genres solto no catálogo e extraRequired.
        assertEquals(listOf("Anime", "Comédia"), c[2].genres)
        assertTrue(c[2].genreRequired)
        assertTrue(c[3].extras.isEmpty())
    }

    @Test fun manifest_do_cinemeta_no_descobrir() {
        // Estrutura do manifest real do Cinemeta (listas encurtadas). O genre do catálogo New são anos e é obrigatório.
        val m = JsonParser.parseString(
            """{"id":"com.linvo.cinemeta","name":"Cinemeta","catalogs":[
              {"id":"top","type":"movie","name":"Popular","genres":["Action","Drama"],"extra":[{"name":"genre","options":["Action","Drama"]},{"name":"search"},{"name":"skip"}]},
              {"id":"year","type":"movie","name":"New","genres":["2026","2025"],"extra":[{"name":"genre","options":["2026","2025"],"isRequired":true},{"name":"skip"}],"extraRequired":["genre"]},
              {"id":"last-videos","type":"series","name":"Last videos","extra":[{"name":"lastVideosIds","isRequired":true}],"extraRequired":["lastVideosIds"]}
            ]}""",
        ).asJsonObject
        val c = AddonLinks.discoverCatalogs(m, "https://v3-cinemeta.strem.io")
        assertEquals(listOf("top", "year"), c.map { it.id })
        // Busca sem opções não vira filtro, e o gênero opcional fica com a lista do extra.
        assertEquals(listOf("genre"), c[0].extras.map { it.name })
        assertEquals(listOf("Action", "Drama"), c[0].extras[0].options)
        assertFalse(c[0].extras[0].required)
        assertTrue(c[1].extras[0].required)
        assertEquals("2026", c[1].extras[0].options.first())
        assertEquals("Cinemeta", c[0].addonName)
    }

    @Test fun filtro_do_catalogo_tem_teto_de_opcoes() {
        val many = (1..1500).joinToString(",") { "\"o$it\"" }
        val m = JsonParser.parseString(
            """{"id":"org.x","name":"X","catalogs":[
              {"type":"movie","id":"a","extra":[{"name":"genre","options":[$many]}]},
              {"type":"series","id":"b","genres":[$many]}
            ]}""",
        ).asJsonObject
        val c = AddonLinks.discoverCatalogs(m, "https://x.dev")
        assertEquals(1000, c[0].extras.single().options.size)
        assertEquals("o1", c[0].extras.single().options.first())
        // O formato antigo tem o mesmo teto.
        assertEquals(1000, c[1].extras.single().options.size)
    }

    @Test fun endereco_do_catalogo_com_filtros_e_skip() {
        assertEquals("https://x.dev/catalog/movie/top.json", AddonLinks.catalogUrl("https://x.dev/manifest.json", "movie", "top"))
        assertEquals(
            "https://x.dev/catalog/movie/top/genre=A%C3%A7%C3%A3o&year=2024&skip=40.json",
            AddonLinks.catalogUrl("https://x.dev/", "movie", "top", linkedMapOf("genre" to "Ação", "year" to "2024", "skip" to "9", "vazio" to ""), 40),
        )
        assertEquals("https://x.dev/catalog/series/a%2Fb.json", AddonLinks.catalogUrl("https://x.dev", "series", "a/b"))
    }

    @Test fun link_capturado_valido() {
        assertEquals(
            "https://torrentio.strem.fun/providers=yts%7Csort=qualitysize/manifest.json",
            AddonLinks.capturedLink("stremio://torrentio.strem.fun/providers=yts%7Csort=qualitysize/manifest.json"),
        )
        assertEquals(
            "https://x.dev/abc/manifest.json",
            AddonLinks.capturedLink("Copie o link: https://x.dev/abc/manifest.json e cole no Stremio"),
        )
    }

    @Test fun link_capturado_invalido() {
        assertNull(AddonLinks.capturedLink(null))
        assertNull(AddonLinks.capturedLink("https://x.dev/configure"))
        assertNull(AddonLinks.capturedLink("javascript:alert(1)//manifest.json"))
        assertNull(AddonLinks.capturedLink("file:///sdcard/manifest.json"))
        assertNull(AddonLinks.capturedLink("intent://x.dev/manifest.json#Intent;end"))
        assertNull(AddonLinks.capturedLink("https://user@evil/manifest.json"))
        assertNull(AddonLinks.capturedLink("texto sem link"))
    }

    @Test fun esquemas_permitidos() {
        assertTrue(AddonLinks.allowedScheme("https://a.com"))
        assertTrue(AddonLinks.allowedScheme("stremio://a.com/manifest.json"))
        assertFalse(AddonLinks.allowedScheme("intent://x"))
        assertFalse(AddonLinks.allowedScheme("file:///etc"))
    }

    @Test fun catalogo_da_comunidade() {
        val json = """{"addons":[
          {"transportUrl":"https://a.dev/manifest.json","manifest":{"id":"a","name":"A","types":["movie"],
            "resources":["stream",{"name":"subtitles"}],"behaviorHints":{"configurationRequired":true},
            "config":[{"key":"token","type":"password","required":true},{"key":"q","type":"select","options":["1080p","720p"]}]}},
          {"transportUrl":"stremio://b.dev/manifest.json","manifest":{"id":"b"}},
          {"transportUrl":"https://c.dev/manifest.json"}
        ]}"""
        val l = AddonLinks.parseCommunity(json)
        assertEquals(1, l.size)
        val a = l[0]
        assertEquals(listOf("stream", "subtitles"), a.resources)
        assertTrue(a.configRequired && a.configurable)
        assertEquals(listOf("token", "q"), a.config.map { it.key })
        assertEquals("token", a.config[0].title)
        assertEquals(listOf("1080p", "720p"), a.config[1].options)
    }

    @Test
    fun siteOfIgnoraSubdominio() {
        assertEquals("strem.fun", AddonLinks.siteOf("https://torrentio.strem.fun/configure"))
        assertEquals("strem.fun", AddonLinks.siteOf("stremio://torrentio.strem.fun/abc/manifest.json"))
        assertEquals("exemplo.com.br", AddonLinks.siteOf("https://a.b.exemplo.com.br/manifest.json"))
        assertEquals("localhost", AddonLinks.siteOf("http://localhost:7000/manifest.json"))
        assertEquals("", AddonLinks.siteOf("javascript:alert(1)"))
    }

    @Test
    fun capturedLinkRecusaHostEstranho() {
        assertNull(AddonLinks.capturedLink("""https://strem.fun\.evil.com/manifest.json"""))
        assertNull(AddonLinks.capturedLink("https://evil.com%2Fstrem.fun/manifest.json"))
        org.junit.Assert.assertNotNull(AddonLinks.capturedLink("stremio://torrentio.strem.fun:443/x/manifest.json"))
    }
}
