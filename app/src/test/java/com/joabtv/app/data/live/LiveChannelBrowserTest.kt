package com.joabtv.app.data.live

import org.junit.Assert.*
import org.junit.Test

class LiveChannelBrowserTest {
    private val a = LiveChannel("a:one", "a", "Esporte São Paulo", "https://example.com/a", group = "Esportes;Brasil", number = 10)
    private val b = LiveChannel("a:two", "a", "Notícias", "https://example.com/b", group = "Jornalismo", number = 20)
    private val c = LiveChannel("b:one", "b", "Outro esporte", "https://example.com/c", group = "Esportes", number = 10)
    private val channels = listOf(a, b, c)
    private val sources = listOf(LiveSource("a", "NTC", LiveSourceType.ADDON), LiveSource("b", "Meu Futebol", LiveSourceType.ADDON))
    private fun filter(filter: LiveBrowseFilter, recents: List<String> = emptyList()) = LiveChannelBrowser.filter(channels, sources, mapOf(b.id to listOf(LiveProgram(90, 200, "Campeonato nacional"))), setOf(c.id), recents, filter, 100)

    @Test fun `busca encontra nome sem acento fonte e programa sem trocar o canal`() {
        assertEquals(listOf(a), filter(LiveBrowseFilter(query = "sao paulo")))
        assertEquals(listOf(c), filter(LiveBrowseFilter(query = "meu futebol")))
        assertEquals(listOf(b), filter(LiveBrowseFilter(query = "campeonato")))
        assertEquals(channels, filter(LiveBrowseFilter()))
    }
    @Test fun `combina fonte grupo e favoritos sem fundir provedores`() {
        assertEquals(listOf(a), filter(LiveBrowseFilter(sourceId = "a", group = "Esportes")))
        assertEquals(listOf(c), filter(LiveBrowseFilter(mode = "fav", group = "Esportes")))
        assertTrue(filter(LiveBrowseFilter(mode = "fav", sourceId = "a")).isEmpty())
    }
    @Test fun `recentes preservam ordem e ignoram canais removidos`() {
        assertEquals(listOf(c, a), filter(LiveBrowseFilter(mode = "recent"), listOf("removed", c.id, a.id)))
    }
    @Test fun `numero repetido prioriza fonte escolhida e favoritos`() {
        assertEquals(c, LiveChannelBrowser.byNumber(channels, 10, "", setOf(c.id)))
        assertEquals(a, LiveChannelBrowser.byNumber(channels, 10, "a", setOf(c.id)))
        assertEquals(a, LiveChannelBrowser.byNumber(channels, 10, "", emptySet()))
        assertNull(LiveChannelBrowser.byNumber(channels, 999, "", emptySet()))
    }
    @Test fun `numeros ausentes seguem a ordem de cada fonte`() {
        val list = channels.map { it.copy(number = 0) }
        assertEquals(mapOf(a.id to 1, b.id to 2, c.id to 1), LiveChannelBrowser.numbers(list))
    }
    @Test fun `troca acumula deslocamento da a volta e aceita lista vazia`() {
        assertEquals(c, LiveChannelBrowser.step(channels, a.id, -1))
        assertEquals(a, LiveChannelBrowser.step(channels, a.id, 3))
        assertEquals(c, LiveChannelBrowser.step(channels, a.id, 2))
        assertNull(LiveChannelBrowser.step(emptyList(), a.id, 1))
    }
}
