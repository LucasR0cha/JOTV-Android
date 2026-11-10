package com.joabtv.app.data.accounts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportLogTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun `cada parte entra no registro quando termina e as outras ficam`() {
        var log = ImportLog()
        log = log.put("nuvio", "addons", PartRecord(ok = true, n = 5, skip = 2), "a@b.com", "Principal", t0)
        log = log.put("nuvio", "collections", PartRecord(ok = false, why = "O Nuvio não respondeu"), "a@b.com", "Principal", t0 + 1000)
        val e = log[Account.NUVIO]!!
        assertEquals(setOf("addons", "collections"), e.parts.keys)
        assertEquals(t0 + 1000, e.at)
        assertEquals(t0, e.part(ImportPart.ADDONS)!!.at)
        assertEquals(listOf(ImportPart.COLLECTIONS), e.failedParts)
        assertNull(log[Account.STREMIO])
    }

    @Test
    fun `parte falha nunca apaga o que ja veio de outra`() {
        val log = ImportLog()
            .put("nuvio", "library", PartRecord(ok = true, list = 42, progress = 18), "a@b.com", "Principal", t0)
            .put("nuvio", "addons", PartRecord(ok = false, why = "Sem conexão com o Nuvio"), "a@b.com", "Principal", t0 + 5)
        assertEquals(42, log[Account.NUVIO]!!.part(ImportPart.LIBRARY)!!.list)
        assertFalse(log[Account.NUVIO]!!.part(ImportPart.ADDONS)!!.ok)
    }

    @Test
    fun `partes que falharam saem na ordem em que rodam`() {
        var log = ImportLog()
        log = log.put("nuvio", "debrid", PartRecord(ok = false, why = "x"), "", "", t0)
        log = log.put("nuvio", "addons", PartRecord(ok = false, why = "y"), "", "", t0)
        log = log.put("nuvio", "library", PartRecord(ok = true), "", "", t0)
        assertEquals(listOf(ImportPart.ADDONS, ImportPart.DEBRID), log[Account.NUVIO]!!.failedParts)
    }

    @Test
    fun `registro volta igual depois de gravar e ler`() {
        val log = ImportLog()
            .put("nuvio", "addons", PartRecord(ok = true, n = 7, skip = 2, failed = 1, unusable = 3, rows = 40, rowsOn = 10), "pessoa@teste.com", "Filhos", t0)
            .put("nuvio", "library", PartRecord(ok = true, list = 42, progress = 18, nomatch = 4), "pessoa@teste.com", "Filhos", t0 + 1)
            .put("nuvio", "debrid", PartRecord(ok = true, label = "Real-Debrid"), "pessoa@teste.com", "Filhos", t0 + 2)
            .put("stremio", "addons", PartRecord(ok = false, why = "O Stremio recusou o pedido"), "x@y.com", "", t0 + 3)
            .withPick(NuvioPick(2, "Filhos", true))
        val back = ImportLog.decode(log.encode())
        assertEquals(log, back)
        assertEquals(NuvioPick(2, "Filhos", true), back.pick)
        assertEquals("Filhos", back[Account.NUVIO]!!.profile)
    }

    @Test
    fun `texto estragado ou vazio vira registro vazio e nao derruba`() {
        assertEquals(ImportLog(), ImportLog.decode(null))
        assertEquals(ImportLog(), ImportLog.decode(""))
        assertEquals(ImportLog(), ImportLog.decode("{ nao e json"))
        assertEquals(ImportLog(), ImportLog.decode("""{"nuvio":{"at":1}}"""))
    }

    @Test
    fun `email e perfil ficam quando a parte nova chega sem eles`() {
        val log = ImportLog()
            .put("nuvio", "addons", PartRecord(ok = true), "a@b.com", "Principal", t0)
            .put("nuvio", "library", PartRecord(ok = true), "", "", t0 + 1)
        assertEquals("a@b.com", log[Account.NUVIO]!!.email)
        assertEquals("Principal", log[Account.NUVIO]!!.profile)
    }

    @Test
    fun `o stremio so traz addons e lista`() {
        assertEquals(listOf(ImportPart.ADDONS, ImportPart.LIBRARY), Account.STREMIO.parts)
        assertEquals(ImportPart.entries, Account.NUVIO.parts)
        assertTrue(ImportPart.COLLECTIONS.many)
    }
}
