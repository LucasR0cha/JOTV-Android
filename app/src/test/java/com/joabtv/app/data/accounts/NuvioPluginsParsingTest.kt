package com.joabtv.app.data.accounts

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NuvioPluginsParsingTest {
    @Test fun linhasDePluginsNaOrdemSemRepetirESoHttps() {
        val body = """[
            {"url":"https://exemplo.test/b/manifest.json","name":"B","enabled":false,"sort_order":2},
            {"url":"https://exemplo.test/a/manifest.json","name":"A","enabled":true,"sort_order":1},
            {"url":"https://exemplo.test/a/manifest.json","name":"A de novo","sort_order":3},
            {"url":"http://inseguro.test/manifest.json","name":"C","sort_order":0},
            {"url":null,"name":"D"}
        ]"""
        val rows = NuvioAccountService.parsePluginRows(body)!!
        assertEquals(listOf("A", "B"), rows.map { it.name })
        assertFalse(rows[1].enabled)
    }

    @Test fun corpoQueNaoELista() {
        assertNull(NuvioAccountService.parsePluginRows("""{"message":"JWT expired"}"""))
        assertNull(NuvioAccountService.parsePluginRows("<html></html>"))
        assertEquals(0, NuvioAccountService.parsePluginRows("[]")!!.size)
    }

    @Test fun perfilLeUsesPrimaryPlugins() {
        val p = NuvioAccountService.profileFromRow(
            JSONObject("""{"profile_index":2,"name":"Ana","uses_primary_addons":false,"uses_primary_plugins":true,"avatar_color_hex":null}"""), "",
        )
        assertEquals(2, p.index)
        assertTrue(p.usesPrimaryPlugins)
        assertFalse(p.usesPrimaryAddons)
        assertEquals("", p.colorHex)
    }

    @Test fun cadaParteDizOQueGravaNoPerfil() {
        val C = com.joabtv.app.data.local.ProfileCategory.entries.associateBy { it.key }
        assertEquals(listOf(C["addons"]), ImportPart.ADDONS.writes)
        assertEquals(listOf(C["plugins"], C["addons"]), ImportPart.PLUGINS.writes)
        assertEquals(listOf(C["catalog"]), ImportPart.COLLECTIONS.writes)
        assertEquals(listOf(C["debrid"]), ImportPart.DEBRID.writes)
        assertTrue(ImportPart.LIBRARY.writes.isEmpty() && ImportPart.PROFILE.writes.isEmpty())
    }

    @Test fun escolhaGuardaPluginsDoPrincipal() {
        val log = ImportLog().withPick(NuvioPick(2, "Ana", primaryAddons = true, primaryPlugins = true))
        assertEquals(log.pick, ImportLog.decode(log.encode()).pick)
    }
}
