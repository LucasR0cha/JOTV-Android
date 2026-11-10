package com.joabtv.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regras de dados de Minha Início e das fileiras dos addons (teto de 10, órfãs, Mover e textos). */
class HomeLayoutRowsTest {

    private val a = "https://a.com"
    private val b = "https://b.com"
    private fun ref(base: String, id: String, type: String = "movie") = HomeCatalogRef(base, type, id, "Cat $id", "Addon $base")
    private fun many(base: String, n: Int) = (1..n).map { ref(base, "c$it") }
    private fun on(list: List<HomeRowPref>, base: String) = list.filter { it.ref?.base == base && it.enabled }

    @Test
    fun `addon novo com 40 catalogos liga so os 10 primeiros e esconde os 30 do fim`() {
        val out = HomeLayout.normalize(HomeLayout.default(emptyList()), setOf(a), many(a, 40), autoOnBases = setOf(a))
        val rows = out.filter { it.ref?.base == a }
        assertEquals(40, rows.size)
        assertEquals(10, on(out, a).size)
        // Os ligados são os 10 primeiros do manifest, na ordem dele.
        assertEquals((1..10).map { "c$it" }, on(out, a).map { it.ref!!.id })
        assertFalse(rows.last().enabled)
    }

    @Test
    fun `o teto vale por addon e nao soma entre eles`() {
        val out = HomeLayout.normalize(HomeLayout.default(emptyList()), setOf(a, b), many(a, 12) + many(b, 3), autoOnBases = setOf(a, b))
        assertEquals(10, on(out, a).size)
        assertEquals(3, on(out, b).size)
    }

    @Test
    fun `quem ja tem 10 fileiras do addon recebe as novas escondidas e nada some`() {
        val saved = HomeLayout.default(many(a, 10))
        val out = HomeLayout.normalize(saved, setOf(a), many(a, 13), autoOnBases = setOf(a))
        assertEquals(10, on(out, a).size)
        assertEquals(13, out.count { it.ref?.base == a })
        assertFalse(out.first { it.key == ref(a, "c13").key }.enabled)
    }

    @Test
    fun `addon antigo fora do autoOn nao liga nada sozinho`() {
        val out = HomeLayout.normalize(HomeLayout.default(emptyList()), setOf(a), many(a, 5), autoOnBases = emptySet())
        assertEquals(0, on(out, a).size)
    }

    @Test
    fun `fileira de addon removido fica como orfa so quando pedido`() {
        val saved = HomeLayout.default(listOf(ref(a, "top"), ref(b, "z")))
        val kept = HomeLayout.normalize(saved, setOf(b), emptyList(), emptySet(), keepOrphans = true)
        assertTrue(kept.any { it.key == ref(a, "top").key })
        assertNull(HomeLayout.normalize(saved, setOf(b), emptyList(), emptySet()).firstOrNull { it.key == ref(a, "top").key })
    }

    @Test
    fun `remover o addon limpa as fileiras dele e deixa as dos outros`() {
        val list = HomeLayout.default(listOf(ref(a, "top"), ref(a, "novos"), ref(b, "z")))
        val out = HomeLayout.dropBase(list, a)
        assertEquals(0, out.count { it.ref?.base == a })
        assertEquals(1, out.count { it.ref?.base == b })
        assertEquals(list.count { it.system != null }, out.count { it.system != null })
    }

    @Test
    fun `mover ao topo respeita as fixadas e ao fim respeita Generos`() {
        val pinned = setOf("col:p")
        val list = listOf(
            HomeRowPref("col:p"),
            HomeRowPref(SystemRow.CONTINUE.key), HomeRowPref(SystemRow.NEW_EPISODES.key),
            HomeRowPref(ref(a, "x").key, true, "", ref(a, "x")),
            HomeRowPref(SystemRow.GENRES.key),
        )
        val key = ref(a, "x").key
        val top = HomeLayout.moveTo(list, key, 0, pinned)
        assertEquals(listOf("col:p", key), top.take(2).map { it.key })
        val end = HomeLayout.moveTo(top, SystemRow.CONTINUE.key, Int.MAX_VALUE, pinned)
        assertEquals(SystemRow.GENRES.key, end.last().key)
        assertEquals(SystemRow.CONTINUE.key, end[end.size - 2].key)
        // Quem já está no lugar, Generos e a fixada não se movem.
        assertSame(top, HomeLayout.moveTo(top, key, 0, pinned))
        assertSame(list, HomeLayout.moveTo(list, SystemRow.GENRES.key, 0, pinned))
        assertSame(list, HomeLayout.moveTo(list, "col:p", Int.MAX_VALUE, pinned))
    }

    @Test
    fun `frases de instalacao dizem quantas fileiras entraram`() {
        assertEquals("10 fileiras novas na Início.", HomeLayout.rowsText(10, 10))
        assertEquals("1 fileira nova na Início.", HomeLayout.rowsText(1, 1))
        assertEquals("10 de 40 fileiras na Início. Escolha outras em Fileiras.", HomeLayout.rowsText(10, 40))
        assertEquals("FenixFlix instalado. 4 fileiras novas na Início.", HomeLayout.installText("FenixFlix", 4, 4))
        assertEquals("Torrentio instalado. 10 de 40 fileiras na Início. Escolha outras em Fileiras.", HomeLayout.installText("Torrentio", 10, 40))
        assertEquals("So fontes instalado.", HomeLayout.installText("So fontes", 0, 0))
    }

    @Test
    fun `aviso de addon grande so passa de 40 fileiras ligadas e some depois de manter todas`() {
        val rows = HomeLayout.normalize(
            HomeLayout.default(emptyList()), setOf(a), many(a, 45), autoOnBases = emptySet(),
        ).map { if (it.ref != null) it.copy(enabled = true) else it }
        val big = HomeLayout.bigAddon(rows, setOf(a), emptySet())
        assertEquals(45, big?.n)
        assertEquals(a, big?.base)
        assertNull(HomeLayout.bigAddon(rows, setOf(a), setOf(a)))
        assertNull(HomeLayout.bigAddon(rows, emptySet(), emptySet()))
        // Com 40 ligadas ainda não pergunta, só passa do limite com 41.
        val five = rows.filter { it.ref != null }.take(5).mapTo(HashSet()) { it.key }
        assertNull(HomeLayout.bigAddon(rows.map { if (it.key in five) it.copy(enabled = false) else it }, setOf(a), emptySet()))
    }

    @Test
    fun `deixar 10 esconde so o que passa das 10 primeiras ligadas do addon`() {
        val rows = HomeLayout.normalize(HomeLayout.default(emptyList()), setOf(a), many(a, 15), autoOnBases = emptySet())
            .map { if (it.ref != null) it.copy(enabled = true) else it }
        val out = HomeLayout.keepFirst(rows, a)
        assertEquals(10, on(out, a).size)
        assertEquals((1..10).map { "c$it" }, on(out, a).map { it.ref!!.id })
        assertEquals(rows.size, out.size)
    }

    @Test
    fun `fileira nova e a que entrou depois da ultima visita`() {
        assertEquals(emptySet<String>(), HomeLayout.fresh(null, listOf("a", "b")))
        assertEquals(setOf("c"), HomeLayout.fresh(setOf("a", "b"), listOf("a", "b", "c")))
    }
}
