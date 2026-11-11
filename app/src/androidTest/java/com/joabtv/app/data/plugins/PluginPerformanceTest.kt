package com.joabtv.app.data.plugins

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PluginPerformanceTest {
    @Test fun coldAndWarmSources() = runBlocking {
        val engine = PluginEngine(ApplicationProvider.getApplicationContext<Context>(), PluginNetwork())
        val gate = Semaphore(PluginState().concurrency)
        val runs = JSONArray()
        repeat(2) { run ->
            val started = SystemClock.elapsedRealtime()
            var first: Long? = null
            val results = (0 until 8).map { index -> async {
                gate.withPermit {
                    val wait = if (index < 2) 1200 else 60
                    val code = "module.exports.getStreams=async()=>{await new Promise(resolve=>setTimeout(resolve,$wait));return [{name:'Provider $index',url:'https://media.example/$run/$index.m3u8',quality:'1080p'}]}"
                    val result = engine.execute(JSONObject().put("code", code).put("method", "streams").put("timeout", 20_000).put("args", JSONArray()).put("settings", JSONObject()))
                    synchronized(runs) { if (first == null) first = SystemClock.elapsedRealtime() - started }
                    assertEquals(1, result.getJSONArray("value").length())
                }
            } }.awaitAll()
            assertEquals(8, results.size)
            runs.put(JSONObject().put("firstMs", first).put("totalMs", SystemClock.elapsedRealtime() - started))
        }
        println("JOTV_PLUGIN_PERF " + JSONObject().put("concurrency", PluginState().concurrency).put("runs", runs))
        engine.cancel()
    }
}
