package com.joabtv.app.ui.movies

import com.joabtv.app.data.remote.StremioStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Os mesmos cenários de tests/sources-stable-order.test.cjs das versões web. */
class StableOrderTest {
    // Versões de um addon, na ordem em que ele devolve.
    private fun versions(addon: String, n: Int, tag: String = addon) =
        (0 until n).map { StremioStream("$tag $it", "https://$tag.invalid/$it.mp4", addon, qualityRank = 1080) }

    // Lista plana na ordem de prioridade dos addons, só com os que já responderam.
    private fun flat(priority: List<String>, got: Map<String, List<StremioStream>>) = priority.flatMap { got[it].orEmpty() }

    private fun ids(p: StablePage) = p.list.map { it.lead.addon + ":" + it.lead.url.substringAfterLast('/') }

    @Test fun prioritarioAtrasadoEntraEmbaixoENoFimValeAPrioridade() {
        val priority = mutableListOf("A", "B", "C")
        val got = mutableMapOf<String, List<StremioStream>>()
        val order = StableOrder()
        got["B"] = versions("B", 3)
        var r = order.update(groupReleases(flat(priority, got)), 24, loading = true)
        assertEquals(listOf("B:0.mp4", "B:1.mp4", "B:2.mp4"), ids(r))
        got["C"] = versions("C", 2)
        r = order.update(groupReleases(flat(priority, got)), 24, loading = true)
        assertEquals(listOf("B:0.mp4", "B:1.mp4", "B:2.mp4", "C:0.mp4", "C:1.mp4"), ids(r))
        got["A"] = versions("A", 2)
        r = order.update(groupReleases(flat(priority, got)), 24, loading = true)
        assertEquals(listOf("B:0.mp4", "B:1.mp4", "B:2.mp4", "C:0.mp4", "C:1.mp4", "A:0.mp4", "A:1.mp4"), ids(r))
        r = order.update(groupReleases(flat(priority, got)), 24, loading = false)
        assertEquals(listOf("A:0.mp4", "A:1.mp4", "B:0.mp4", "B:1.mp4", "B:2.mp4", "C:0.mp4", "C:1.mp4"), ids(r))
        assertFalse(order.waiting)
        // Depois do fim nada se mexe, nem com resposta nova de outro addon.
        got["D"] = versions("D", 1)
        priority.add(0, "D")
        r = order.update(groupReleases(flat(priority, got)), 24, loading = false)
        assertEquals(listOf("A:0.mp4", "A:1.mp4", "B:0.mp4", "B:1.mp4", "B:2.mp4", "C:0.mp4", "C:1.mp4", "D:0.mp4"), ids(r))
    }

    @Test fun listaTocadaMantemAOrdemVista() {
        val priority = listOf("A", "B")
        val got = mutableMapOf("B" to versions("B", 2))
        val order = StableOrder()
        order.update(groupReleases(flat(priority, got)), 24, loading = true)
        order.touch()
        got["A"] = versions("A", 2)
        order.update(groupReleases(flat(priority, got)), 24, loading = true)
        val r = order.update(groupReleases(flat(priority, got)), 24, loading = false)
        assertEquals(listOf("B:0.mp4", "B:1.mp4", "A:0.mp4", "A:1.mp4"), ids(r))
        assertEquals(null, order.featured)
    }

    @Test fun pessoaOcupadaAdiaAOrdemFinal() {
        val priority = listOf("A", "B")
        val got = mapOf("B" to versions("B", 2), "A" to versions("A", 1))
        val order = StableOrder()
        order.update(groupReleases(flat(priority, mapOf("B" to got.getValue("B")))), 24, loading = true)
        var r = order.update(groupReleases(flat(priority, got)), 24, loading = false, busy = true)
        assertEquals(listOf("B:0.mp4", "B:1.mp4", "A:0.mp4"), ids(r))
        assertTrue(order.waiting)
        r = order.update(groupReleases(flat(priority, got)), 24, loading = false, busy = false)
        assertEquals(listOf("A:0.mp4", "B:0.mp4", "B:1.mp4"), ids(r))
    }

    @Test fun addonComCentenasDeVersoesNaoTomaAPagina() {
        val priority = listOf("A", "B", "C", "D")
        val got = mutableMapOf("A" to versions("A", 200))
        val order = StableOrder()
        var r = order.update(groupReleases(flat(priority, got)), 24, loading = true)
        assertEquals(24, r.list.size)
        got["B"] = versions("B", 100); got["C"] = versions("C", 100); got["D"] = versions("D", 100)
        r = order.update(groupReleases(flat(priority, got)), 24, loading = true)
        val page = r
        fun byAddon(a: String) = page.list.count { it.lead.addon == a }
        assertTrue(byAddon("B") >= 4 && byAddon("C") >= 4 && byAddon("D") >= 4)
        assertTrue(r.list.size <= 48)
        assertEquals((0 until 24).map { "A:$it.mp4" }, ids(r).take(24))
        r = order.update(groupReleases(flat(priority, got)), 24, loading = false)
        assertEquals(24, r.list.size)
        listOf("A", "B", "C", "D").forEach { a -> assertEquals(6, r.list.count { it.lead.addon == a }) }
        assertTrue(r.rest > 0)
    }

    @Test fun fonteDeOutroFilmeFicaNaoEntraESaiNoFim() {
        val priority = listOf("A", "B")
        val got = mapOf("A" to versions("A", 2), "B" to versions("B", 2))
        val wrong = mutableSetOf<String>()
        val hide: (ReleaseGroup) -> Boolean = { g -> g.members.all { it.url in wrong } }
        val order = StableOrder()
        order.update(groupReleases(flat(priority, mapOf("A" to got.getValue("A")))), 24, loading = true, hide = hide)
        wrong += got.getValue("A")[0].url // o teste mostrou que a primeira é de outro filme
        wrong += got.getValue("B")[1].url // e esta chega já marcada
        var r = order.update(groupReleases(flat(priority, got)), 24, loading = true, hide = hide)
        assertEquals(listOf("A:0.mp4", "A:1.mp4", "B:0.mp4"), ids(r))
        r = order.update(groupReleases(flat(priority, got)), 24, loading = false, hide = hide)
        assertEquals(listOf("A:1.mp4", "B:0.mp4"), ids(r))
    }

    @Test fun mesmoArquivoDeOutroAddonViraOMesmoCartao() {
        val priority = listOf("A", "B")
        fun file(s: StremioStream) = s.copy(fileName = "Filme.2024.1080p.WEB-DL.mkv", fileSize = 4L * 1024 * 1024 * 1024)
        val b0 = file(StremioStream("B", "https://b.invalid/x", "B", qualityRank = 1080))
        val got = mutableMapOf("B" to listOf(b0) + versions("B", 2, "bb"))
        val dead = mutableSetOf<String>()
        val off: (StremioStream) -> Boolean = { it.url in dead }
        val order = StableOrder()
        var r = order.update(groupReleases(flat(priority, got)), 24, loading = true, off = off)
        val firstId = r.list[0].id
        got["A"] = listOf(file(StremioStream("A", "https://a.invalid/y", "A", qualityRank = 1080)))
        r = order.update(groupReleases(flat(priority, got)), 24, loading = true, off = off)
        assertEquals(3, r.list.size)
        assertEquals(firstId, r.list[0].id)
        assertEquals(listOf("A", "B"), r.list[0].group.members.map { it.addon }.sorted())
        assertEquals("https://b.invalid/x", r.list[0].lead.url)
        dead += "https://b.invalid/x"
        r = order.update(groupReleases(flat(priority, got)), 24, loading = true, off = off)
        assertEquals("https://a.invalid/y", r.list[0].lead.url)
    }

    @Test fun mostrarMaisEntraEmbaixoEResetRecomeca() {
        val all = versions("A", 60)
        val order = StableOrder()
        var r = order.update(groupReleases(all), 24, loading = false)
        assertEquals(listOf(24, 36), listOf(r.list.size, r.rest))
        r = order.update(groupReleases(all), 48, loading = false)
        assertEquals((0 until 24).map { "A:$it.mp4" }, ids(r).take(24))
        assertEquals(48, r.list.size)
        order.reset()
        r = order.update(groupReleases(all.reversed()), 24, loading = false)
        assertEquals("https://A.invalid/59.mp4", r.list[0].lead.url)
    }

    @Test fun melhorOpcaoAbreAListaUmaVezSo() {
        val all = versions("A", 3)
        val order = StableOrder()
        order.update(groupReleases(all), 24, loading = true)
        assertEquals(null, order.featured)
        val best: (ReleaseGroup) -> Boolean = { g -> g.members.any { it.url == all[2].url } }
        var r = order.update(groupReleases(all), 24, loading = false, first = best)
        assertEquals("A:2.mp4", ids(r).first())
        assertEquals(r.list[0].id, order.featured)
        // A melhor muda depois, mas o destaque e a ordem ficam.
        r = order.update(groupReleases(all), 24, loading = false, first = { g -> g.members.any { it.url == all[0].url } })
        assertEquals("A:2.mp4", ids(r).first())
        assertEquals(r.list[0].id, order.featured)
    }

    @Test fun updateRepetidoNaoMudaNada() {
        val got = mapOf("A" to versions("A", 30), "B" to versions("B", 30))
        val order = StableOrder()
        order.update(groupReleases(versions("B", 30)), 24, loading = true)
        val a = ids(order.update(groupReleases(flat(listOf("A", "B"), got)), 24, loading = true))
        val b = ids(order.update(groupReleases(flat(listOf("A", "B"), got)), 24, loading = true))
        assertEquals(a, b)
    }

    @Test fun pilulasEntramNoFimEArrumamNoFimDaBusca() {
        val chips = StableKeys()
        assertEquals(listOf("all", "1080"), chips.update(listOf("all", "1080"), loading = true))
        assertEquals(listOf("all", "1080", "4k", "hdr"), chips.update(listOf("all", "4k", "1080", "hdr"), loading = true))
        assertEquals(listOf("all", "4k", "1080", "hdr"), chips.update(listOf("all", "4k", "1080", "hdr"), loading = false))
        val touched = StableKeys()
        touched.update(listOf("all", "1080"), loading = true)
        touched.touch()
        assertEquals(listOf("all", "1080", "4k"), touched.update(listOf("all", "4k", "1080"), loading = false))
    }
}
