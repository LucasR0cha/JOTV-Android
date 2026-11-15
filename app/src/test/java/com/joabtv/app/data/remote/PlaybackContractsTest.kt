package com.joabtv.app.data.remote

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PlaybackContractsTest {
    @Test fun headersDoAddonChegamSemPerderAutenticacao() {
        val hints = JsonParser.parseString("""{"proxyHeaders":{"request":{"Referer":"https://video.example/","Authorization":"Bearer teste","User-Agent":"customizado"}}}""")
        assertEquals("https://video.example/", streamHeaders(hints)["Referer"])
        assertEquals("Bearer teste", streamHeaders(hints)["Authorization"])
        assertEquals("customizado", streamHeaders(hints)["User-Agent"])
        assertTrue(streamHeaders(JsonParser.parseString("""{"proxyHeaders":"inválido"}""")).isEmpty())
    }

    @Test fun respostaDeLegendaNaoDependeDeNomeDeCampoOfuscado() {
        val subs = parseAddonSubtitles("""{"subtitles":[{"url":"/pt.srt","lang":"pob","headers":{"Cookie":"teste"}},{"url":"javascript:alert(1)","lang":"eng"},null,{"lang":"eng"}]}""", "https://addon.example/manifest.json", "Addon")
        assertEquals(1, subs.size)
        assertEquals("https://addon.example/pt.srt", subs.single().url)
        assertEquals("teste", subs.single().headers["Cookie"])
        assertTrue(subs.single().label.contains("Addon"))
        assertTrue(parseAddonSubtitles("<html>Erro</html>", "https://addon.example").isEmpty())
    }

    @Test fun legendasAnexadasAoStreamAceitamCabecalhosProprios() {
        val raw = JsonParser.parseString("""[{"url":"https://subtitle.example/pt.vtt","language":"pt-BR","label":"Português","behaviorHints":{"proxyHeaders":{"request":{"Referer":"https://addon.example/"}}}}]""")
        val sub = parseStreamSubtitles(raw, "https://video.example/movie", "Addon").single()
        assertEquals("pt-br", sub.lang)
        assertEquals("https://addon.example/", sub.headers["Referer"])
    }

    @Test fun recusaInjecaoDeCabecalho() {
        val headers = requestHeaders(JsonParser.parseString("""{"X-Test":"valor\r\nAuthorization: outro","Normal":"ok"}"""))
        assertEquals(mapOf("Normal" to "ok"), headers)
    }

    @Test fun arquivosComprimidosEUnicodeMantemTexto() {
        val text = "1\n00:00:01,000 --> 00:00:03,000\nAção e coração\n"
        val gzip = ByteArrayOutputStream().apply { GZIPOutputStream(this).use { it.write(text.toByteArray()) } }.toByteArray()
        assertEquals(text, decodeSubtitle(gzip))
        val zip = ByteArrayOutputStream().apply { ZipOutputStream(this).use { it.putNextEntry(ZipEntry("sub/pt.srt")); it.write(text.toByteArray()); it.closeEntry() } }.toByteArray()
        assertEquals(text, decodeSubtitle(zip))
        assertEquals(text, decodeSubtitle(byteArrayOf(0xff.toByte(), 0xfe.toByte()) + text.toByteArray(Charsets.UTF_16LE)))
        assertEquals(text, decodeSubtitle(text.toByteArray(java.nio.charset.Charset.forName("windows-1252"))))
    }

    @Test fun zipNaoDescompactaEntradaIgnoradaSemLimite() {
        val zip = ByteArrayOutputStream().apply {
            ZipOutputStream(this).use {
                it.putNextEntry(ZipEntry("readme.txt"))
                repeat(6) { _ -> it.write(ByteArray(1024 * 1024)) }
                it.closeEntry()
                it.putNextEntry(ZipEntry("pt.srt"))
                it.write("1\n00:00:01,000 --> 00:00:02,000\nTeste".toByteArray())
                it.closeEntry()
            }
        }.toByteArray()
        assertTrue(zip.size < 10_000)
        assertThrows(IllegalArgumentException::class.java) { decodeSubtitle(zip) }
    }

    @Test fun identificaHlsEDashSemExtensaoNoLink() {
        assertEquals("application/x-mpegURL", playbackMimeType("https://video.example/play/1", "application/octet-stream", "\uFEFF#EXTM3U\n"))
        assertEquals("application/x-mpegURL", playbackMimeType("https://video.example/v?key=teste", "application/vnd.apple.mpegurl"))
        assertEquals("application/dash+xml", playbackMimeType("https://video.example/v", "", "<?xml version=\"1.0\"?><MPD type=\"static\">"))
        assertNull(playbackMimeType("https://video.example/a.mp4"))
    }

    @Test fun trackersUsamFormatoProprioDeEpisodio() {
        for (provider in listOf(TrackerProvider.SIMKL, TrackerProvider.WETRAKR, TrackerProvider.MDBLIST)) {
            val body = trackerScrobbleBody(provider, 1399, true, 2, 4, 35.5)
            assertEquals(1399, body.getJSONObject("show").getJSONObject("ids").getInt("tmdb"))
            if (provider == TrackerProvider.MDBLIST) assertEquals(4, body.getJSONObject("show").getJSONObject("season").getJSONObject("episode").getInt("number"))
            else assertEquals(4, body.getJSONObject("episode").getInt("number"))
            assertEquals(35.5, body.getDouble("progress"), .001)
            assertFalse(body.has("shows"))
        }
    }

    @Test fun listaNaoUsaRotasInventadasNemApagaHistorico() {
        assertEquals("/watchlist/items/add", trackerWatchlistRequest(TrackerProvider.MDBLIST, 12, false, true)?.path)
        assertEquals("plantowatch", trackerWatchlistRequest(TrackerProvider.SIMKL, 12, false, true)?.body?.getJSONArray("movies")?.getJSONObject(0)?.getString("to"))
        assertEquals("/sync/tracking/remove", trackerWatchlistRequest(TrackerProvider.WETRAKR, 12, true, false)?.path)
        assertNull(trackerWatchlistRequest(TrackerProvider.SIMKL, 12, false, false))
        assertFalse(TrackerStatus().connected)
    }
}
