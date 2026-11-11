package com.joabtv.app.data.live

import java.text.Normalizer
import java.util.Locale

data class LiveBrowseFilter(val mode: String = "all", val sourceId: String = "", val group: String = "", val query: String = "")

/** Regras do guia do player sem dependência de interface ou reprodução. */
object LiveChannelBrowser {
    private val marks = Regex("\\p{M}+")
    private fun folded(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD).replace(marks, "").lowercase(Locale.ROOT)

    fun groups(channel: LiveChannel): List<String> = channel.group.split(';').map(String::trim).filter(String::isNotEmpty).ifEmpty { listOf("Sem grupo") }

    fun filter(
        channels: List<LiveChannel>, sources: List<LiveSource>, epg: Map<String, List<LiveProgram>>,
        favorites: Set<String>, recents: List<String>, filter: LiveBrowseFilter, now: Long,
    ): List<LiveChannel> {
        val names = sources.associate { it.id to it.name }
        val query = folded(filter.query.trim())
        val recentOrder = recents.withIndex().associate { it.value to it.index }
        val list = channels.filter { c ->
            (filter.sourceId.isBlank() || c.sourceId == filter.sourceId) &&
                (filter.group.isBlank() || filter.group in groups(c)) &&
                (filter.mode != "fav" || c.id in favorites) && (filter.mode != "recent" || c.id in recentOrder) &&
                (query.isBlank() || query in folded("${c.name} ${c.group} ${names[c.sourceId].orEmpty()}") || epg[c.id].orEmpty().any { it.stop > now && query in folded(it.title) })
        }
        return if (filter.mode == "recent") list.sortedBy { recentOrder[it.id] ?: Int.MAX_VALUE } else list
    }

    fun numbers(channels: List<LiveChannel>): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        return channels.associate { c ->
            val n = (counts[c.sourceId] ?: 0) + 1
            counts[c.sourceId] = n
            c.id to (if (c.number > 0) c.number else n)
        }
    }

    fun byNumber(channels: List<LiveChannel>, number: Int, sourceId: String, favorites: Set<String>): LiveChannel? {
        val numbers = numbers(channels)
        val matches = channels.filter { numbers[it.id] == number && (sourceId.isBlank() || it.sourceId == sourceId) }
        return matches.firstOrNull { it.id in favorites } ?: matches.firstOrNull()
    }

    fun step(channels: List<LiveChannel>, currentId: String, offset: Int): LiveChannel? {
        if (channels.isEmpty()) return null
        val index = channels.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        return channels[((index + offset) % channels.size + channels.size) % channels.size]
    }
}
