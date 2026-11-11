package com.joabtv.app.data.plugins

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PluginSourceCacheTest {
    @Test fun signedLinksExpireBeforeTheirDeclaredDeadline() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var now = 1_800_000_000_000L
            val cache = PluginSourceCache(scope) { now }
            val epoch = cache.begin("movie|1")
            cache.get("signed", epoch) { "[{\"url\":\"https://media.example/v.m3u8?exp=1800000006\"}]" }
            now += 1100
            assertEquals("[2]", cache.get("signed", epoch) { "[2]" })
            cache.get("expired", epoch) { "[{\"url\":\"https://media.example/v.m3u8?exp=1800000000\"}]" }
            assertEquals("[3]", cache.get("expired", epoch) { "[3]" })
        } finally { scope.cancel() }
    }
    @Test fun simultaneousRequestsShareOneLoadAndExpire() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var now = 1000L
            val cache = PluginSourceCache(scope) { now }
            val epoch = cache.begin("movie|1")
            var calls = 0
            val first = async { cache.get("provider|movie|1", epoch) { calls++; delay(50); "[1]" } }
            val second = async { cache.get("provider|movie|1", epoch) { error("Duplicate lookup") } }
            assertEquals("[1]", first.await()); assertEquals("[1]", second.await()); assertEquals(1, calls)
            assertEquals("[1]", cache.get("provider|movie|1", epoch) { error("Cached lookup must not run") })
            now += 30_001
            assertEquals("[2]", cache.get("provider|movie|1", epoch) { "[2]" })
        } finally { scope.cancel() }
    }
    @Test fun switchingTitlesCancelsOldWorkAndPreservesRecentResults() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val cache = PluginSourceCache(scope)
            val first = cache.begin("movie|1")
            assertEquals("[1]", cache.get("ready", first) { "[1]" })
            val slow = async { runCatching { cache.get("slow", first) { delay(10_000); "[9]" } } }
            delay(30)
            cache.begin("movie|2")
            assertTrue(slow.await().isFailure)
            assertEquals("[]", cache.get("late", first) { error("Obsolete request must not start") })
            val back = cache.begin("movie|1")
            assertEquals("[1]", cache.get("ready", back) { error("Recent result must remain cached") })
        } finally { scope.cancel() }
    }
    @Test fun configurationAndProfileChangesDiscardPrivateResults() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val cache = PluginSourceCache(scope)
            val before = cache.begin("movie|1")
            cache.get("private", before) { "[\"private-header\"]" }
            cache.clear()
            val after = cache.begin("movie|1")
            assertEquals("[\"new-profile\"]", cache.get("private", after) { "[\"new-profile\"]" })
            assertEquals("[]", cache.get("private", before) { error("Old profile request") })
        } finally { scope.cancel() }
    }
    @Test fun emptyResultsExpireQuicklyAndLargeValuesAreNotRetained() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var now = 0L
            val cache = PluginSourceCache(scope) { now }
            val epoch = cache.begin("movie|1")
            cache.get("empty", epoch) { "[]" }
            now = 2001
            assertEquals("[1]", cache.get("empty", epoch) { "[1]" })
            cache.get("huge", epoch) { "x".repeat(200_000) }
            assertEquals("[2]", cache.get("huge", epoch) { "[2]" })
        } finally { scope.cancel() }
    }
}
