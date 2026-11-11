package com.joabtv.app.data.plugins

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PluginContractTest {
    @Test fun normalizesGithubAndStremioLinks() {
        assertEquals("https://raw.githubusercontent.com/example/repository/main/manifest.json", PluginContract.normalize("https://github.com/example/repository"))
        assertEquals("https://raw.githubusercontent.com/example/repository/main/manifest.json", PluginContract.normalize("https://github.com/example/repository/blob/main/manifest.json"))
        assertEquals("https://addon.example/manifest.json", PluginContract.normalize("stremio://addon.example/manifest.json"))
    }
    @Test fun rejectsLocalAndPrivilegedAddresses() {
        listOf("http://127.0.0.1/", "http://10.1.2.3/", "http://localhost/", "http://[::1]/", "file:///etc/passwd", "https://user:pass@example.org/").forEach { assertEquals(it, "", PluginContract.publicUrl(it)) }
    }
    @Test fun parsesRepositoryAndKeepsTvSeparateFromLiveChannels() {
        val repo = PluginContract.parseRepository("""{"name":"Teste","scrapers":[{"id":"anime","filename":"providers/anime.js","supportedTypes":["tv"],"version":"2"}]}""", "https://plugins.example/main/manifest.json")
        assertEquals("https://plugins.example/main/providers/anime.js", repo.providers.single().url)
        assertEquals(listOf("tv"), repo.providers.single().types)
        assertFalse(repo.providers.single().enabled)
        assertTrue(repo.providers.single().available)
    }
    @Test fun detectsIndividualScriptsAndStremioAddons() {
        assertEquals("script", PluginContract.parseRepository("module.exports={}", "https://plugins.example/source.js").kind)
        assertEquals("stremio", PluginContract.parseRepository("""{"id":"a","resources":["stream"],"types":["movie"]}""", "https://addon.example/manifest.json").kind)
    }
    @Test fun validatesHeadersAndSettings() {
        val headers = PluginContract.headers(JSONObject().put("Referer", "https://source.example/").put("Host", "private").put("Authorization", "secret").put("Bad", "x\r\ny"))
        assertEquals("https://source.example/", headers["Referer"])
        assertEquals("secret", headers["Authorization"])
        assertFalse(headers.containsKey("Host")); assertFalse(headers.containsKey("Bad"))
        val fields = listOf(PluginField("audio", "Áudio", "select", defaultValue = "all", options = listOf("Todos" to "all", "Dublado" to "dub")), PluginField("quality", "Qualidade", "number", min = 0.0, max = 2160.0), PluginField("secret", "Sessão", "text", secret = true))
        val values = JSONObject(PluginContract.validate(fields, JSONObject().put("audio", "dub").put("quality", "1080").put("secret", "private")))
        assertEquals("dub", values.getString("audio")); assertEquals(1080.0, values.getDouble("quality"), 0.0); assertEquals("private", values.getString("secret"))
    }
    @Test fun duplicateProvidersAndNativeExtensionsAreRejected() {
        for (body in listOf("""{"scrapers":[{"id":"a","filename":"a.js"},{"id":"a","filename":"b.js"}]}""", """{"plugins":[{"id":"a","url":"a.cs3"}]}""")) {
            assertTrue(runCatching { PluginContract.parseRepository(body, "https://plugins.example/manifest.json") }.isFailure)
        }
    }
}
