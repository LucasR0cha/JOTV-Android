package com.joabtv.app.data.update

import org.json.JSONArray
import org.json.JSONObject

data class AppRelease(
    val version: String,
    val assetId: Long,
    val url: String,
    val page: String,
    val size: Long,
    val sha256: String,
    val notes: String,
)

internal fun compareAppVersions(a: String, b: String): Int {
    val pattern = Regex("\\d{1,8}\\.\\d{1,8}\\.\\d{1,8}")
    require(a.matches(pattern) && b.matches(pattern)) { "Versão inválida" }
    val left = a.split('.').map(String::toInt)
    val right = b.split('.').map(String::toInt)
    for (i in 0..2) if (left[i] != right[i]) return left[i].compareTo(right[i])
    return 0
}

internal fun selectAndroidRelease(releases: JSONArray, current: String, repository: String): AppRelease? {
    require(repository.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}")))
    var selected: AppRelease? = null
    val encoder = { value: String -> java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20") }
    for (i in 0 until releases.length()) {
        val release = releases.optJSONObject(i) ?: continue
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
        val tag = release.optString("tag_name")
        if (tag.isBlank() || tag.length > 200) continue
        val assets = release.optJSONArray("assets") ?: continue
        for (j in 0 until assets.length()) {
            val asset = assets.optJSONObject(j) ?: continue
            if (asset.optString("state") != "uploaded") continue
            val name = asset.optString("name")
            val version = Regex("JOTV-v(\\d{1,8}\\.\\d{1,8}\\.\\d{1,8})\\.apk").matchEntire(name)?.groupValues?.get(1) ?: continue
            if (compareAppVersions(version, current) <= 0 || selected?.let { compareAppVersions(version, it.version) <= 0 } == true) continue
            val size = asset.optLong("size")
            val id = asset.optLong("id")
            if (size !in 1..300L * 1024 * 1024 || id <= 0) continue
            val url = "https://github.com/$repository/releases/download/${encoder(tag)}/$name"
            if (asset.optString("browser_download_url") != url) continue
            selected = AppRelease(version, id, url, "https://github.com/$repository/releases/tag/${encoder(tag)}", size,
                asset.optString("digest").takeIf { it.matches(Regex("sha256:[a-fA-F0-9]{64}")) }?.substring(7)?.lowercase().orEmpty(),
                release.optString("body").takeUnless { it == "null" }.orEmpty().take(3000))
        }
    }
    return selected
}

internal fun AppRelease.toJson() = JSONObject().put("version", version).put("assetId", assetId).put("url", url)
    .put("page", page).put("size", size).put("sha256", sha256).put("notes", notes)

internal fun cachedAppRelease(text: String, current: String, repository: String): AppRelease? = runCatching {
    val item = JSONObject(text)
    val prefix = "https://github.com/$repository/releases/tag/"
    require(item.getString("page").startsWith(prefix))
    val tag = java.net.URLDecoder.decode(item.getString("page").removePrefix(prefix), "UTF-8")
    selectAndroidRelease(JSONArray().put(JSONObject().put("tag_name", tag).put("body", item.optString("notes"))
        .put("assets", JSONArray().put(JSONObject().put("name", item.getString("url").substringAfterLast('/'))
            .put("id", item.getLong("assetId")).put("size", item.getLong("size")).put("state", "uploaded")
            .put("browser_download_url", item.getString("url")).put("digest", "sha256:" + item.optString("sha256"))))), current, repository)
}.getOrNull()
