package com.joabtv.app.data.debrid

import com.joabtv.app.data.remote.PROBE_SKIPPED
import com.joabtv.app.data.remote.probeSkipped
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Link que o addon manda resolver no debrid dele não pode ser aberto para teste, porque abrir esse endereço faz o addon
 * adicionar o torrent na conta (e, sem cache, começar o download).
 */
class DebridLinksTest {
    private val hash = "a".repeat(40)

    @Test
    fun `link de resolucao do addon e reconhecido pelo endereco`() {
        assertTrue(DebridLinks.isDebridLink("Torrentio\n1080p", "https://torrentio.example/resolve/realdebrid/CHAVE/$hash/null/0/Filme.2024.1080p.mkv"))
        assertTrue(DebridLinks.isDebridLink("Comet", "https://comet.example/abc/playback/$hash/0/n/n/Filme.mkv"))
        assertTrue(DebridLinks.isDebridLink("AIOStreams", "https://aio.example/api/v1/debrid/playback/xyz/Filme.mkv"))
        assertTrue(DebridLinks.isDebridLink("Addon", "https://addon.example/play?service=torbox&id=1"))
    }

    @Test
    fun `etiqueta de debrid no nome basta`() {
        assertTrue(DebridLinks.isDebridLink("[RD+] Torrentio\n4k", "https://cdn.example/a.mkv"))
        assertTrue(DebridLinks.isDebridLink("[RD download] Torrentio\n1080p", "https://cdn.example/a.mkv"))
        assertTrue(DebridLinks.isDebridLink("TB⚡ AIOStreams\n2160p", "https://cdn.example/a.mkv"))
        assertTrue(DebridLinks.isDebridLink("⏳ RD AIOStreams", "https://cdn.example/a.mkv"))
    }

    @Test
    fun `arquivo direto nao e link de debrid`() {
        assertFalse(DebridLinks.isDebridLink("FrostStream 1080p", "https://cdn.example/videos/Filme.2024.mp4"))
        assertFalse(DebridLinks.isDebridLink("Servidor Nyx", "https://cdn.example/hls/master.m3u8"))
        // Token longo só na consulta é de autenticação, não de torrent.
        assertFalse(DebridLinks.isDebridLink("Direto", "https://cdn.example/v/Filme.mkv?token=$hash"))
        // Link final do próprio serviço já foi liberado, abrir não adiciona nada.
        assertFalse(DebridLinks.isDebridLink("x", "https://real-debrid.com/d/ABCDEFG"))
        // Magnet não é link http e nunca vai para teste.
        assertFalse(DebridLinks.urlLooksDebrid("magnet:?xt=urn:btih:$hash"))
    }

    @Test
    fun `pronto no debrid so com a etiqueta de pronto`() {
        assertTrue(DebridLinks.cachedTag("[RD+] Torrentio\n4k"))
        assertTrue(DebridLinks.cachedTag("TB⚡ AIOStreams\n2160p"))
        assertFalse(DebridLinks.cachedTag("[RD download] Torrentio\n1080p"))
        assertFalse(DebridLinks.cachedTag("⏳ RD AIOStreams"))
        assertFalse(DebridLinks.cachedTag("FrostStream 1080p"))
    }

    @Test
    fun `teste de fonte pula o link de debrid e deixa o resultado neutro`() {
        val resolver = "https://torrentio.example/resolve/realdebrid/CHAVE/$hash/null/0/Filme.mkv"
        assertEquals(PROBE_SKIPPED, probeSkipped(resolver))
        // Neutro: vivo, sem duração, sem resolução e sem passar por rápido (o Assistir só confia em até 2,5 s).
        assertTrue(PROBE_SKIPPED.ok)
        assertEquals(0.0, PROBE_SKIPPED.sec, 0.0)
        assertEquals(0, PROBE_SKIPPED.height)
        assertTrue(PROBE_SKIPPED.ms > 2_500)
        assertNull(probeSkipped("https://cdn.example/videos/Filme.2024.mp4"))
    }
}
