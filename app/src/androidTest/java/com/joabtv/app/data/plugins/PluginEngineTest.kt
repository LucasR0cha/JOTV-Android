package com.joabtv.app.data.plugins

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PluginEngineTest {
    private fun input(code: String, method: String = "settings", timeout: Long = 15_000) = JSONObject().put("code", code).put("method", method).put("timeout", timeout).put("args", JSONArray()).put("settings", JSONObject())
    @Test fun configurationRunsInNativeWebViewWorker() = runBlocking {
        val result = engine.execute(input("""module.exports.onSettings = async () => [{type:'select',key:'audio',label:'Áudio',defaultValue:'all',options:[{label:'Todos',value:'all'}]}];"""))
        assertEquals("audio", result.getJSONArray("value").getJSONObject(0).getString("key"))
    }
    @Test fun isolatedLibrariesAndApplicationBoundary() = runBlocking {
        val result = engine.execute(input("""module.exports.onSettings = () => {
            const cheerio = require('cheerio');
            const hash = require('crypto-js').SHA256('abc').toString();
            let blocked = false; try { require('fs'); } catch (_) { blocked = true; }
            return [{type:'text',key:'result',label:cheerio.load('<b>JOTV</b>')('b').text(),defaultValue:[typeof App,typeof document,typeof process,typeof JotvPluginBridge,blocked,hash].join(',')}];
        };"""))
        val field = result.getJSONArray("value").getJSONObject(0)
        assertEquals("JOTV", field.getString("label"))
        assertEquals("undefined,undefined,undefined,undefined,true,ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", field.getString("defaultValue"))
    }
    @Test fun sourceHeadersAndSubtitlesCrossNativeBridge() = runBlocking {
        val result = engine.execute(input("""module.exports.getStreams=async()=>[{name:'Teste',url:'https://media.example/video.m3u8',quality:'1080p',headers:{Referer:'https://media.example/'},subtitles:[{url:'https://media.example/sub.vtt',language:'pt-BR'}]}];""", "streams"))
        val source = result.getJSONArray("value").getJSONObject(0)
        assertEquals(1080, source.getInt("qualityRank"))
        assertEquals("https://media.example/", source.getJSONObject("headers").getString("Referer"))
        assertEquals("pt-BR", source.getJSONArray("subtitles").getJSONObject(0).getString("language"))
    }
    @Test fun infiniteProviderStopsAndNextProviderStillWorks() = runBlocking {
        assertTrue(runCatching { engine.execute(input("while(true){}", timeout = 3000)) }.isFailure)
        val result = engine.execute(input("module.exports.onSettings=()=>[{type:'toggle',key:'enabled',label:'Pronto'}];"))
        assertEquals("enabled", result.getJSONArray("value").getJSONObject(0).getString("key"))
    }
    companion object {
        private val engine by lazy { PluginEngine(ApplicationProvider.getApplicationContext<Context>(), PluginNetwork()) }
    }
}
