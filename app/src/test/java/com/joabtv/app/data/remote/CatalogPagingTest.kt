package com.joabtv.app.data.remote

import com.joabtv.app.data.remote.CatalogPaging.Cursor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogPagingTest {
    private fun metas(from: Int, count: Int) = (from until from + count).map {
        AddonMeta(id = "tt$it", name = "T$it", poster = "https://x/$it.jpg", background = "", year = "", description = "", rating = 0.0)
    }

    @Test fun pagina_de_20_continua() {
        val m = metas(0, 20)
        val c = CatalogPaging.advance(Cursor(), m, CatalogPaging.take(m), resolved = 20)
        assertTrue(c.more)
        assertEquals(20, c.skip)
        assertEquals(20, c.seen.size)
    }

    @Test fun pagina_grande_leva_40_e_o_skip_anda_40() {
        val m = metas(0, 100)
        val taken = CatalogPaging.take(m)
        assertEquals(40, taken.size)
        val c = CatalogPaging.advance(Cursor(), m, taken, resolved = 40)
        assertTrue(c.more)
        assertEquals(40, c.skip)
    }

    @Test fun pagina_vazia_encerra() {
        val c = CatalogPaging.advance(Cursor(skip = 40, seen = setOf("tt1")), emptyList(), emptyList(), resolved = 0)
        assertFalse(c.more)
    }

    @Test fun addon_que_ignora_o_skip_e_repete_a_pagina_encerra() {
        val m = metas(0, 20)
        val first = CatalogPaging.advance(Cursor(), m, CatalogPaging.take(m), resolved = 20)
        assertTrue(first.more)
        val second = CatalogPaging.advance(first, m, CatalogPaging.take(m), resolved = 0)
        assertFalse(second.more)
    }

    @Test fun pagina_que_so_repete_em_parte_continua() {
        val first = CatalogPaging.advance(Cursor(), metas(0, 20), metas(0, 20), resolved = 20)
        val next = metas(10, 20)
        val second = CatalogPaging.advance(first, next, next, resolved = 10)
        assertTrue(second.more)
        assertEquals(40, second.skip)
    }

    @Test fun falha_de_rede_deixa_o_cursor_onde_estava() {
        val before = Cursor(skip = 40, seen = setOf("tt1"), empty = 1)
        assertEquals(before, CatalogPaging.advance(before, null, emptyList(), resolved = 0))
    }

    @Test fun tres_paginas_seguidas_sem_titulo_aproveitavel_encerram() {
        var c = Cursor()
        var start = 0
        repeat(2) {
            val m = metas(start, 20)
            c = CatalogPaging.advance(c, m, m, resolved = 0)
            start += 20
            assertTrue(c.more)
        }
        val m = metas(start, 20)
        c = CatalogPaging.advance(c, m, m, resolved = 0)
        assertFalse(c.more)
    }

    @Test fun titulo_aproveitavel_zera_a_contagem_de_vazias() {
        val a = metas(0, 20)
        var c = CatalogPaging.advance(Cursor(), a, a, resolved = 0)
        val b = metas(20, 20)
        c = CatalogPaging.advance(c, b, b, resolved = 3)
        assertEquals(0, c.empty)
        assertTrue(c.more)
    }
}
