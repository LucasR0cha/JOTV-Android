package com.joabtv.app.data.local

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class PosterQualityFlowTest {
    @Test fun atualizacaoDeOutroTituloNaoReemiteOSeloDoCartao() = runBlocking {
        withTimeout(2000) {
            val cache = MutableStateFlow<Map<String, SourceQuality>>(emptyMap())
            val visible = SourceQuality(1080, false, false, false, true, 3, 100)
            val changes = mutableListOf<SourceQuality?>()
            val collect = launch(start = CoroutineStart.UNDISPATCHED) {
                cache.qualityFor("movie:1").take(3).toList(changes)
            }
            cache.value = mapOf("movie:1" to visible)
            yield()
            repeat(30) { index ->
                cache.value = cache.value + ("movie:${index + 2}" to visible.copy(at = index.toLong()))
                yield()
            }
            assertEquals(listOf(null, visible), changes)
            cache.value = emptyMap()
            collect.join()
            assertEquals(listOf(null, visible, null), changes)
        }
    }

    @Test fun mudancaNaQualidadeDoProprioTituloContinuaChegando() = runBlocking {
        withTimeout(2000) {
            val initial = SourceQuality(1080, false, false, false, false, 2, 100)
            val updated = initial.copy(q = 2160, hdr = true, ptAudio = true, at = 200)
            val cache = MutableStateFlow(mapOf("movie:1" to initial))
            val changes = mutableListOf<SourceQuality?>()
            val collect = launch(start = CoroutineStart.UNDISPATCHED) {
                cache.qualityFor("movie:1").take(2).toList(changes)
            }
            cache.value = mapOf("movie:1" to updated)
            collect.join()
            assertEquals(listOf(initial, updated), changes)
        }
    }
}
