package com.joabtv.app.data.live

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LiveAddonParserTest {
    @Test fun `detecta Meu Futebol NTC e catalogo misto do Veyra`() {
        val manifest = JSONObject("""{"resources":["catalog",{"name":"stream","types":["movie","series","tv"]}],"catalogs":[{"type":"movie","id":"filmes"},{"type":"tv","id":"canais","extra":[{"name":"skip"},{"name":"genre","options":["Esportes"]}]}]}""")
        val catalogs = LiveAddonParser.catalogs(manifest)
        assertEquals(1, catalogs.size)
        assertEquals("tv", catalogs.single().type)
        assertTrue(catalogs.single().paginated)
        assertFalse(catalogs.single().genreRequired)
        for (id in listOf("meufutebol_channels", "ntctv-live")) {
            assertEquals(id, LiveAddonParser.catalogs(JSONObject("""{"resources":["catalog","stream"],"catalogs":[{"type":"tv","id":"$id"}]}""")).single().id)
        }
    }

    @Test fun `respeita tipo do recurso e filtros obrigatorios`() {
        assertTrue(LiveAddonParser.catalogs(JSONObject("""{"resources":[{"name":"stream","types":["movie"]}],"catalogs":[{"type":"tv","id":"tv"}]}""")).isEmpty())
        assertTrue(LiveAddonParser.catalogs(JSONObject("""{"resources":["stream"],"catalogs":[{"type":"tv","id":"tv","extraRequired":["search"]}]}""")).isEmpty())
        assertTrue(LiveAddonParser.catalogs(JSONObject("""{"resources":["stream"],"catalogs":[{"type":"tv","id":"tv","extraRequired":["genre"]}]}""")).isEmpty())
        val catalog = LiveAddonParser.catalogs(JSONObject("""{"resources":["stream"],"catalogs":[{"type":"channel","id":"tv","extra":[{"name":"genre","isRequired":true,"options":["Brasil","Esportes"]}]}]}""")).single()
        assertTrue(catalog.genreRequired)
        assertEquals(listOf("Brasil", "Esportes"), catalog.genres)
    }

    @Test fun `preserva identificadores e separa canal de URL expirada`() {
        val source = LiveSource("addon", "Veyra", LiveSourceType.ADDON, "https://addon.example/config/manifest.json")
        val catalog = LiveAddonCatalog("tv", "canais", "Canais")
        val channel = LiveAddonParser.channels(JSONObject("""{"metas":[null,{"id":"veyra:5:c:519","name":"SporTV 2","type":"tv","genres":["Esportes"],"logo":"javascript:alert(1)","poster":"https://example.com/logo.png"}]}"""), source, catalog).single()
        assertEquals("addon:veyra:5:c:519", channel.id)
        assertEquals("veyra:5:c:519", channel.addonId)
        assertEquals(source.url, channel.addonBase)
        assertEquals("", channel.url)
        assertEquals("https://example.com/logo.png", channel.logo)
        assertEquals("Esportes", channel.group)
    }

    @Test fun `preserva cabecalhos do Meu Futebol mesmo com notWebReady falso`() {
        val streams = LiveAddonParser.streams(JSONObject("""{"streams":[{"url":"https://cdn.example/live.m3u8","behaviorHints":{"notWebReady":false,"proxyHeaders":{"request":{"User-Agent":"Firefox","Referer":"https://player.example","Origin":"https://player.example"}}}}]}"""))
        assertEquals(mapOf("User-Agent" to "Firefox", "Referer" to "https://player.example", "Origin" to "https://player.example"), streams.single().headers)
    }

    @Test fun `ignora transmissao vazia protocolo invalido e injecao de cabecalho`() {
        assertTrue(LiveAddonParser.streams(JSONObject("""{"streams":[]}""")).isEmpty())
        val streams = LiveAddonParser.streams(JSONObject("""{"streams":[null,{"url":"file:///etc/passwd"},{"url":"https://cdn.example/live","headers":{"Host":"localhost","Origin":"x\r\nInjected: y","X-Token":"safe","Other":true}},{"url":"https://cdn.example/backup"}]}"""))
        assertEquals(2, streams.size)
        assertEquals(mapOf("X-Token" to "safe"), streams.first().headers)
    }
}
