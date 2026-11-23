package com.joabtv.app.data.live

import org.json.JSONArray
import org.json.JSONObject

data class LiveAddonCatalog(
    val type: String,
    val id: String,
    val name: String,
    val genres: List<String> = emptyList(),
    val genreRequired: Boolean = false,
    val paginated: Boolean = false,
)

data class LiveStream(val url: String, val headers: Map<String, String> = emptyMap(), val label: String = "Transmissão")

/** Leitura do contrato de canais sem depender do Android. */
object LiveAddonParser {
    private val liveTypes = setOf("tv", "channel", "live")

    fun catalogs(manifest: JSONObject): List<LiveAddonCatalog> {
        val resources = manifest.optJSONArray("resources") ?: return emptyList()
        val streamTypes = (0 until resources.length()).flatMap { i ->
            when (val r = resources.opt(i)) {
                "stream" -> liveTypes.toList()
                is JSONObject -> if (r.optString("name") == "stream") strings(r.optJSONArray("types")).ifEmpty { liveTypes.toList() } else emptyList()
                else -> emptyList()
            }
        }.toSet()
        val cats = manifest.optJSONArray("catalogs") ?: return emptyList()
        return (0 until cats.length()).mapNotNull { i ->
            val c = cats.optJSONObject(i) ?: return@mapNotNull null
            val type = c.optString("type")
            val id = c.optString("id")
            if (type !in liveTypes || type !in streamTypes || id.isBlank()) return@mapNotNull null
            val extra = c.optJSONArray("extra")
            val fields = (0 until (extra?.length() ?: 0)).mapNotNull { extra?.optJSONObject(it) }
            val required = strings(c.optJSONArray("extraRequired")) + fields.filter { it.optBoolean("isRequired") }.map { it.optString("name") }
            val genres = strings(fields.firstOrNull { it.optString("name") == "genre" }?.optJSONArray("options"))
                .ifEmpty { strings(c.optJSONArray("genres")) }
            if (required.any { it != "genre" } || ("genre" in required && genres.isEmpty())) return@mapNotNull null
            LiveAddonCatalog(type, id, c.optString("name").ifBlank { id }, genres, "genre" in required, fields.any { it.optString("name") == "skip" })
        }
    }

    fun channels(body: JSONObject, source: LiveSource, catalog: LiveAddonCatalog, genre: String = ""): List<LiveChannel> {
        val metas = body.optJSONArray("metas") ?: return emptyList()
        return (0 until metas.length()).mapNotNull { i ->
            val m = metas.optJSONObject(i) ?: return@mapNotNull null
            val id = m.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            LiveChannel(
                id = "${source.id}:$id", sourceId = source.id, name = m.optString("name").ifBlank { id }, url = "",
                logo = webUrl(m.optString("logo")).ifBlank { webUrl(m.optString("poster")) },
                group = strings(m.optJSONArray("genres")).joinToString(";").ifBlank { genre.ifBlank { catalog.name } },
                addonBase = source.url, addonId = id, addonType = catalog.type,
            )
        }
    }

    fun programs(meta: JSONObject, now: Long = System.currentTimeMillis()): List<LiveProgram> {
        val videos = meta.optJSONArray("videos")
        val native = (0 until minOf(videos?.length() ?: 0, 500)).mapNotNull { i ->
            val v = videos?.optJSONObject(i) ?: return@mapNotNull null
            val start = isoTime(v.optString("startTime")) ?: return@mapNotNull null
            val stop = isoTime(v.optString("endTime")) ?: return@mapNotNull null
            val title = v.optString("title").ifBlank { v.optString("name") }.take(300)
            if (stop <= start || stop - start > 172800000L || title.isBlank()) return@mapNotNull null
            LiveProgram(start, stop, title, v.optString("overview").ifBlank { v.optString("description") }.take(2000))
        }
        if (native.isNotEmpty()) return native.sortedBy { it.start }
        val pattern = Regex("(?:AGORA\\s*\\()?([0-2]?\\d):([0-5]\\d)\\s*[–—-]\\s*([0-2]?\\d):([0-5]\\d)\\)?\\s*[:·|]\\s*(.+)", RegexOption.IGNORE_CASE)
        var last = 0L
        return meta.optString("description").take(30000).lineSequence().mapNotNull { line ->
            val m = pattern.find(line) ?: return@mapNotNull null
            val h = m.groupValues[1].toInt(); val endH = m.groupValues[3].toInt()
            if (h > 23 || endH > 23) return@mapNotNull null
            val startMin = h * 60 + m.groupValues[2].toInt()
            val stopMin = endH * 60 + m.groupValues[4].toInt()
            val length = (stopMin - startMin + 1440) % 1440
            if (length == 0) return@mapNotNull null
            val day = java.util.Calendar.getInstance().apply { timeInMillis = if (last > 0) last else now; set(java.util.Calendar.HOUR_OF_DAY, h); set(java.util.Calendar.MINUTE, startMin % 60); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }
            var start = day.timeInMillis
            if (last == 0L) {
                while (start > now + 43200000L) start -= 86400000L
                while (start + length * 60000L < now - 43200000L) start += 86400000L
            } else if (start < last) start += 86400000L
            last = start
            LiveProgram(start, start + length * 60000L, m.groupValues[5].trim().take(300))
        }.toList()
    }

    private fun isoTime(value: String): Long? {
        val normalized = value.take(80).replace(Regex("Z$"), "+0000").replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2")
        for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ss.SSSZ", "yyyy-MM-dd'T'HH:mm:ssZ")) {
            val time = runCatching { java.text.SimpleDateFormat(pattern, java.util.Locale.ROOT).apply { isLenient = false }.parse(normalized)?.time }.getOrNull()
            if (time != null) return time
        }
        return null
    }

    fun streams(body: JSONObject): List<LiveStream> {
        val streams = body.optJSONArray("streams") ?: return emptyList()
        return (0 until streams.length()).mapNotNull { i ->
            val s = streams.optJSONObject(i) ?: return@mapNotNull null
            val url = webUrl(s.optString("url")).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val raw = s.optJSONObject("behaviorHints")?.optJSONObject("proxyHeaders")?.optJSONObject("request")
                ?: s.optJSONObject("headers")
            val headers = linkedMapOf<String, String>()
            raw?.keys()?.asSequence()?.take(20)?.forEach { key ->
                val value = raw.opt(key) as? String ?: return@forEach
                if (Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]{1,64}$").matches(key) && value.length <= 4096 && value.none { it == '\r' || it == '\n' || it == '\u0000' } &&
                    key.lowercase() !in setOf("host", "content-length", "connection", "transfer-encoding")) {
                    headers[when (key.lowercase()) { "user-agent" -> "User-Agent"; "referer", "referrer" -> "Referer"; "origin" -> "Origin"; else -> key }] = value
                }
            }
            LiveStream(url, headers, s.optString("name").ifBlank { s.optString("title") }.ifBlank { "Fonte ${i + 1}" }.replace(Regex("\\s+"), " ").take(160))
        }.distinctBy { it.url to it.headers }
    }

    private fun webUrl(value: String) = value.takeIf { Regex("^https?://[^\\s]+$", RegexOption.IGNORE_CASE).matches(it) }.orEmpty()
    private fun strings(array: JSONArray?): List<String> = (0 until (array?.length() ?: 0)).mapNotNull { (array?.opt(it) as? String)?.takeIf(String::isNotBlank) }
}
