package com.joabtv.app.data.local

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSetsTest {
    private val A = ProfileCategory.ADDONS

    @Test fun cincoCategoriasSemContas() {
        assertEquals(listOf("addons", "plugins", "catalog", "live", "debrid"), ProfileCategory.entries.map { it.key })
        assertNull(ProfileCategory.of("accounts"))
    }

    @Test fun chaveSabeACategoria() {
        assertEquals(ProfileCategory.ADDONS, ProfileSets.categoryOfKey("stremio_addons_v2"))
        assertEquals(ProfileCategory.CATALOG, ProfileSets.categoryOfKey("jotv_catalog_on"))
        assertNull(ProfileSets.categoryOfKey("pref_audio"))
        assertNull(ProfileSets.categoryOfKey("intro_marks_v1"))
    }

    @Test fun donoSegueOVinculo() {
        val links = mapOf("main" to emptyMap(), "a" to mapOf("addons" to "b"), "b" to mapOf("addons" to "main"))
        assertEquals("main", ProfileSets.ownerOf(links, "a", A))
        assertEquals("a", ProfileSets.ownerOf(links, "a", ProfileCategory.LIVE))
        assertEquals("main", ProfileSets.ownerOf(links, "main", A))
    }

    @Test fun cicloOuPerfilQueNaoExisteDevolveOProprio() {
        val ciclo = mapOf("a" to mapOf("addons" to "b"), "b" to mapOf("addons" to "a"))
        assertEquals("a", ProfileSets.ownerOf(ciclo, "a", A))
        val sumiu = mapOf("a" to mapOf("addons" to "x"))
        assertEquals("a", ProfileSets.ownerOf(sumiu, "a", A))
    }

    @Test fun maisDeQuatroSaltosDevolveOProprio() {
        val links = mapOf(
            "p0" to mapOf("addons" to "p1"), "p1" to mapOf("addons" to "p2"), "p2" to mapOf("addons" to "p3"),
            "p3" to mapOf("addons" to "p4"), "p4" to mapOf("addons" to "p5"), "p5" to emptyMap(),
        )
        assertEquals("p0", ProfileSets.ownerOf(links, "p0", A))
        assertEquals("p5", ProfileSets.ownerOf(links, "p1", A))
    }

    @Test fun vinculoQueFechariaCicloERecusado() {
        val links = mapOf("main" to emptyMap(), "a" to mapOf("addons" to "main"))
        assertFalse(ProfileSets.canLink(links, "main", A, "a"))
        assertFalse(ProfileSets.canLink(links, "a", A, "a"))
        assertTrue(ProfileSets.canLink(links, "main", ProfileCategory.LIVE, "a"))
    }

    @Test fun dependentesDoDono() {
        val links = mapOf("main" to emptyMap(), "a" to mapOf("addons" to "main", "live" to "main"), "b" to mapOf("debrid" to "a"))
        assertEquals(mapOf("a" to listOf(ProfileCategory.ADDONS, ProfileCategory.LIVE)), ProfileSets.dependentsOf(links, "main"))
        assertEquals(setOf("b"), ProfileSets.dependentsOf(links, "a").keys)
    }

    private fun addons(vararg urls: String) = JSONArray().apply { urls.forEach { put(JSONObject().put("u", it).put("e", true)) } }.toString()
    private fun urls(raw: Any?) = JSONArray(raw as String).let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("u") } }

    @Test fun copiaReplaceTrocaTudoEApagaOQueAOrigemNaoTem() {
        val keys = ProfileSets.PREF_KEYS.getValue(A)
        val out = ProfileSets.copyValues(A, mapOf("stremio_addons_v2" to addons("x")), mapOf("stremio_addons_v2" to addons("y"), "home_auto_on_v1" to setOf("y")), CopyMode.REPLACE, keys)
        assertEquals(listOf("x"), urls(out["stremio_addons_v2"]))
        assertTrue(out.containsKey("home_auto_on_v1"))
        assertNull(out["home_auto_on_v1"])
    }

    @Test fun mergeAcrescentaSemMudarAOrdemDoDestino() {
        val keys = ProfileSets.PREF_KEYS.getValue(A)
        val out = ProfileSets.copyValues(
            A, mapOf("stremio_addons_v2" to addons("c", "A"), "home_auto_on_v1" to setOf("c")),
            mapOf("stremio_addons_v2" to addons("a", "b"), "home_auto_on_v1" to setOf("a")), CopyMode.MERGE, keys,
        )
        assertEquals(listOf("a", "b", "c"), urls(out["stremio_addons_v2"]))
        assertEquals(setOf("a", "c"), out["home_auto_on_v1"])
    }

    @Test fun mergeDeFontesAoVivoEPlugins() {
        val src = JSONArray().put(JSONObject().put("id", "s1").put("url", "https://x/lista.m3u")).put(JSONObject().put("id", "s2").put("url", "https://y/l.m3u")).toString()
        val dst = JSONArray().put(JSONObject().put("id", "s9").put("url", "https://x/lista.m3u")).toString()
        val live = ProfileSets.copyValues(ProfileCategory.LIVE, mapOf("sources" to src), mapOf("sources" to dst), CopyMode.MERGE, ProfileSets.LIVE_KEYS)
        assertEquals(2, JSONArray(live["sources"] as String).length())
        val ps = JSONObject().put("enabled", false).put("repos", JSONArray().put(JSONObject().put("url", "https://r/2"))).toString()
        val pd = JSONObject().put("enabled", true).put("repos", JSONArray().put(JSONObject().put("url", "https://r/1"))).toString()
        val pl = JSONObject(ProfileSets.copyValues(ProfileCategory.PLUGINS, mapOf("state" to ps), mapOf("state" to pd), CopyMode.MERGE, listOf("state"))["state"] as String)
        assertTrue(pl.getBoolean("enabled"))
        assertEquals("https://r/2", pl.getJSONArray("repos").getJSONObject(1).getString("url"))
    }

    @Test fun debridSoSubstituiMesmoEmMerge() {
        val out = ProfileSets.copyValues(ProfileCategory.DEBRID, mapOf("accounts" to "[1]"), mapOf("accounts" to "[2]", "enabled" to false), CopyMode.MERGE, ProfileSets.DEBRID_KEYS)
        assertEquals("[1]", out["accounts"])
        assertNull(out["enabled"])
    }

    @Test fun migracaoCopiaSoOQueFaltaENaoMexeNoMain() {
        val present = hashSetOf("a|jotv_catalog_on")
        val copies = ProfileSets.migrationCopies(listOf("main", "a", "b"), listOf("stremio_addons_v2", "jotv_catalog_on"), mapOf("stremio_addons_v2" to "[]", "jotv_catalog_on" to false)) { p, k -> "$p|$k" in present }
        assertEquals(setOf(Triple("a", "stremio_addons_v2", "[]"), Triple("b", "stremio_addons_v2", "[]"), Triple("b", "jotv_catalog_on", false)), copies.toSet())
        assertTrue(copies.none { it.first == "main" })
        copies.forEach { present += "${it.first}|${it.second}" }
        assertTrue(ProfileSets.migrationCopies(listOf("main", "a", "b"), listOf("stremio_addons_v2", "jotv_catalog_on"), mapOf("stremio_addons_v2" to "[]", "jotv_catalog_on" to false)) { p, k -> "$p|$k" in present }.isEmpty())
    }

    @Test fun instalacaoNovaNaoMigraNada() {
        assertTrue(ProfileSets.migrationCopies(listOf("main"), ProfileSets.MIGRATED_PREF_KEYS, mapOf("stremio_addons_v2" to "[]")) { _, _ -> false }.isEmpty())
    }

    @Test fun planoReaproveitaUsaOAtualECria() {
        val jotv = listOf(JotvProfileLink("main", null, ""), JotvProfileLink("p2", 3, "pessoa@exemplo.test"))
        val plan = ProfileSets.planNuvioAll(listOf(3, 1, 2), jotv, "main", "Pessoa@exemplo.test", 5)
        assertEquals(
            listOf(NuvioPlanStep(1, NuvioPlanKind.CURRENT, "main"), NuvioPlanStep(2, NuvioPlanKind.CREATE), NuvioPlanStep(3, NuvioPlanKind.REUSE, "p2")),
            plan.steps,
        )
        assertEquals(0, plan.leftOut)
    }

    @Test fun planoParaNoLimiteEContaOsDeFora() {
        val jotv = listOf(JotvProfileLink("main", 1, "outro@exemplo.test"), JotvProfileLink("p2", null, ""), JotvProfileLink("p3", null, ""))
        val plan = ProfileSets.planNuvioAll(listOf(1, 2, 3, 4, 5), jotv, "main", "pessoa@exemplo.test", 5)
        assertEquals(2, plan.steps.count { it.kind == NuvioPlanKind.CREATE })
        assertTrue(plan.steps.none { it.kind == NuvioPlanKind.CURRENT })
        assertEquals(3, plan.leftOut)
    }

    @Test fun planoSemEmailNaoReaproveitaOutroPerfil() {
        val plan = ProfileSets.planNuvioAll(listOf(1, 2), listOf(JotvProfileLink("main", null, ""), JotvProfileLink("p2", 2, "")), "main", "", 5)
        assertEquals(listOf(NuvioPlanKind.CURRENT, NuvioPlanKind.CREATE), plan.steps.map { it.kind })
    }

    @Test fun atualComEscolhaESemEmailViraOPrimeiroPasso() {
        // Escolheu um perfil da Nuvio (ou importou antes do e-mail ir para o registro), mas nunca importou com e-mail.
        val plan = ProfileSets.planNuvioAll(listOf(1, 2), listOf(JotvProfileLink("main", 2, "")), "main", "pessoa@exemplo.test", 5)
        assertEquals(NuvioPlanStep(1, NuvioPlanKind.CURRENT, "main"), plan.steps.first())
        assertEquals(NuvioPlanKind.CREATE, plan.steps[1].kind)
        assertEquals(0, plan.leftOut)
    }

    @Test fun destinoVinculadoViraCopiaEODonoNaoMuda() {
        val links = mapOf("main" to emptyMap(), "bia" to mapOf("addons" to "main", "live" to "main"))
        val cats = listOf(ProfileCategory.ADDONS, ProfileCategory.PLUGINS, ProfileCategory.ADDONS)
        val detach = ProfileSets.linkedCategories(links, "bia", cats)
        assertEquals(listOf(ProfileCategory.ADDONS), detach)
        // Depois de deixar de usar, gravar nos addons da Bia cai no espaço dela e não no Principal.
        val after = links + ("bia" to (links.getValue("bia") - detach.map { it.key }.toSet()))
        assertEquals("bia", ProfileSets.ownerOf(after, "bia", ProfileCategory.ADDONS))
        assertEquals("main", ProfileSets.ownerOf(after, "bia", ProfileCategory.LIVE))
        assertEquals("main", ProfileSets.ownerOf(after, "main", ProfileCategory.ADDONS))
    }

    @Test fun vinculosIdaEVolta() {
        val links = mapOf("addons" to "main", "live" to "a")
        assertEquals(links, ProfileSets.decodeLinks(ProfileSets.encodeLinks(links)))
        assertEquals(emptyMap<String, String>(), ProfileSets.decodeLinks(JSONObject().put("accounts", "main")))
    }
}
