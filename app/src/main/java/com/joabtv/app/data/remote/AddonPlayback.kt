package com.joabtv.app.data.remote

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun JsonElement?.objectOrNull(): JsonObject? = this?.takeIf { it.isJsonObject }?.asJsonObject
internal fun JsonElement?.textOrEmpty(): String = this?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

internal fun requestHeaders(value: JsonElement?): Map<String, String> =
    value.objectOrNull()?.entrySet().orEmpty().mapNotNull { (name, raw) ->
        val text = raw.textOrEmpty()
        if (!name.matches(Regex("[!#$%&'*+.^_`|~0-9a-zA-Z-]+")) ||
            text.any { it != '\t' && (it < ' ' || it >= '\u007f') }) null else name to text
    }.toMap()

internal fun streamHeaders(hints: JsonElement?): Map<String, String> =
    requestHeaders(hints.objectOrNull()?.get("proxyHeaders").objectOrNull()?.get("request"))

/** Lê legendas sem reflexão, inclusive no APK otimizado. */
internal fun parseAddonSubtitles(body: String, baseUrl: String, addon: String = ""): List<ExternalSub> =
    runCatching { parseStreamSubtitles(JsonParser.parseString(body).objectOrNull()?.get("subtitles"), baseUrl, addon) }
        .getOrDefault(emptyList())

internal fun parseStreamSubtitles(value: JsonElement?, baseUrl: String, addon: String): List<ExternalSub> {
    val array = value?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
    return array.mapNotNull { raw ->
        val obj = raw.objectOrNull() ?: return@mapNotNull null
        val link = obj.get("url").textOrEmpty()
        val url = link.toHttpUrlOrNull() ?: baseUrl.toHttpUrlOrNull()?.resolve(link)
        if (link.isBlank() || url == null) return@mapNotNull null
        val lang = obj.get("lang").textOrEmpty().ifBlank { obj.get("language").textOrEmpty() }.lowercase()
        val label = obj.get("label").textOrEmpty().ifBlank { com.joabtv.app.data.local.SubLangs.label(lang) }
        ExternalSub(url.toString(), lang, listOf(label, addon).filter { it.isNotBlank() }.joinToString(" · "),
            requestHeaders(obj.get("headers")) + streamHeaders(obj.get("behaviorHints")))
    }
}
