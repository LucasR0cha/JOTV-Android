package com.joabtv.app.data.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdateProtocolTest {
    private fun release(version: String = "1.0.19"): JSONObject {
        val name = "JOTV-v$version.apk"
        return JSONObject().put("tag_name", "lote-multiplataforma").put("body", "Correções")
            .put("assets", JSONArray().put(JSONObject().put("name", name).put("state", "uploaded").put("id", 123)
                .put("size", 100).put("digest", "sha256:" + "a".repeat(64))
                .put("browser_download_url", "https://github.com/example/JOTV/releases/download/lote-multiplataforma/$name")))
    }
    private fun select(vararg releases: JSONObject) = selectAndroidRelease(JSONArray(releases.toList()), "1.0.18", "example/JOTV")

    @Test fun versoesSaoComparadasNumericamente() {
        assertTrue(compareAppVersions("1.0.10", "1.0.9") > 0)
        assertEquals(0, compareAppVersions("1.0.19", "1.0.19"))
        assertTrue(compareAppVersions("1.0.19", "2.0.0") < 0)
    }
    @Test fun usaVersaoDoApkSemDependerDaTagDoLote() {
        assertEquals("1.0.20", select(release(), release("1.0.20"))?.version)
        assertEquals("a".repeat(64), select(release())?.sha256)
        assertNull(select(release("1.0.18")))
        val other = release(); other.getJSONArray("assets").getJSONObject(0).put("name", "JOTV-Tizen-v9.9.9.wgt")
        assertNull(select(other))
    }
    @Test fun previaRascunhoTamanhoEOrigemInvalidosNaoViraramAtualizacao() {
        for (flag in listOf("prerelease", "draft")) assertNull(select(release().put(flag, true)))
        val huge = release(); huge.getJSONArray("assets").getJSONObject(0).put("size", 301L * 1024 * 1024)
        assertNull(select(huge))
        val external = release(); external.getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://evil.example/update.apk")
        assertNull(select(external))
    }
    @Test fun semHashSoOfereceAPaginaPublica() {
        val value = release(); value.getJSONArray("assets").getJSONObject(0).remove("digest")
        assertEquals("", select(value)?.sha256)
    }
    @Test fun cacheValidoMantemAvisoECacheAlteradoNaoAbreOutroSite() {
        val original = select(release())!!
        assertEquals(original, cachedAppRelease(original.toJson().toString(), "1.0.18", "example/JOTV"))
        assertNull(cachedAppRelease(original.toJson().put("url", "https://evil.example/JOTV-v1.0.19.apk").toString(), "1.0.18", "example/JOTV"))
        assertNull(cachedAppRelease(original.toJson().toString(), "1.0.19", "example/JOTV"))
    }
}
