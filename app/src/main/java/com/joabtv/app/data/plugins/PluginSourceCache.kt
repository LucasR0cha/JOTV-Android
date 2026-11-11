package com.joabtv.app.data.plugins

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

class PluginSourceCache(private val scope: CoroutineScope, private val now: () -> Long = System::currentTimeMillis) {
    private data class Entry(val value: String, val until: Long)
    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>(16, .75f, true)
    private val flights = mutableMapOf<String, Deferred<String>>()
    private var epoch = 0L
    private var query = ""
    fun snapshot(): Long = synchronized(lock) { epoch }
    fun begin(next: String): Long = synchronized(lock) {
        if (query != next) { query = next; epoch++; val old = flights.values.toList(); flights.clear(); old.forEach { it.cancel() } }
        epoch
    }
    fun clear() = synchronized(lock) { query = ""; epoch++; entries.clear(); val old = flights.values.toList(); flights.clear(); old.forEach { it.cancel() } }
    fun invalidate() = synchronized(lock) { entries.clear() }
    suspend fun get(key: String, expected: Long, loader: suspend () -> String): String {
        val task = synchronized(lock) {
            if (expected != epoch) return "[]"
            entries[key]?.let { if (it.until > now()) return it.value else entries.remove(key) }
            flights[key]?.let { return@synchronized it }
            val job = scope.async(start = CoroutineStart.LAZY) {
                val value = loader()
                synchronized(lock) {
                    var ttl = if (value == "[]") 2000L else 30_000L
                    EXPIRY.findAll(value).forEach { match -> val raw = match.groupValues[1].toLongOrNull() ?: return@forEach; val time = if (raw < 100_000_000_000L) raw * 1000 else raw; ttl = minOf(ttl, time - now() - 5000) }
                    if (expected == epoch && ttl > 0 && value.length <= 128 * 1024) {
                        entries[key] = Entry(value, now() + ttl)
                        var bytes = entries.values.sumOf { it.value.length * 2 }
                        val cursor = entries.iterator()
                        while (cursor.hasNext()) { val item = cursor.next(); if (item.value.until <= now() || entries.size > 48 || bytes > 2 * 1024 * 1024) { bytes -= item.value.value.length * 2; cursor.remove() } }
                    }
                }
                value
            }
            flights[key] = job
            job.invokeOnCompletion { synchronized(lock) { if (flights[key] === job) flights.remove(key) } }
            job
        }
        task.start()
        return task.await()
    }
    private companion object { val EXPIRY = Regex("[?&](?:expires?|exp|expiry)=([0-9]{10,13})(?:[&\"]|$)", RegexOption.IGNORE_CASE) }
}
