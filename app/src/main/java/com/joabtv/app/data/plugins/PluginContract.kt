package com.joabtv.app.data.plugins

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

data class PluginField(val key: String, val label: String, val type: String, val description: String = "", val secret: Boolean = false, val required: Boolean = false, val defaultValue: Any? = null, val options: List<Pair<String, Any>> = emptyList(), val min: Double? = null, val max: Double? = null)
data class PluginStatus(val message: String, val ok: Boolean, val at: Long = System.currentTimeMillis(), val ms: Long = 0, val count: Int = 0)
data class PluginProvider(val id: String, val name: String, val url: String, val version: String = "", val description: String = "", val types: List<String> = listOf("movie", "tv"), val languages: List<String> = emptyList(), val formats: List<String> = emptyList(), val available: Boolean = true, val enabled: Boolean = false, val integrity: String = "", val settings: String = "{}", val fields: String = "[]", val status: PluginStatus? = null)
data class PluginRepo(val url: String, val name: String, val kind: String = "nuvio", val version: String = "", val description: String = "", val author: String = "", val enabled: Boolean = true, val checkedAt: Long = 0, val providers: List<PluginProvider> = emptyList(), val addonId: String = "")
data class PluginState(val enabled: Boolean = true, val autoUpdate: Boolean = true, val timeout: Long = 45_000, val concurrency: Int = 1, val repos: List<PluginRepo> = emptyList())

object PluginContract {
    private val unsafeKey = setOf("__proto__", "constructor", "prototype")
    fun publicUrl(value: String): String {
        if (value.length > 8192 || value.any { it <= ' ' || it == '\\' }) return ""
        val url = value.toHttpUrlOrNull() ?: return ""
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return ""
        val host = url.host.lowercase().trimEnd('.')
        if (host == "localhost" || listOf(".localhost", ".local", ".internal").any(host::endsWith) || ':' in host || host.matches(Regex("(?:\\d+|0x[0-9a-f]+)"))) return ""
        if (host.firstOrNull()?.isDigit() == true && host.all { it.isDigit() || it == '.' }) {
            val p = host.split('.').mapNotNull(String::toIntOrNull)
            if (p.size != 4 || p.any { it !in 0..255 } || p[0] in setOf(0, 10, 127) || p[0] >= 224 ||
                p[0] == 169 && p[1] == 254 || p[0] == 172 && p[1] in 16..31 || p[0] == 192 && p[1] in setOf(0, 2, 168) ||
                p[0] == 100 && p[1] in 64..127 || p[0] == 198 && p[1] in setOf(18, 19, 51) || p[0] == 203 && p[1] == 0 && p[2] == 113) return ""
        }
        return url.toString()
    }
    fun normalize(input: String): String {
        var value = input.trim().replace(Regex("^stremio:", RegexOption.IGNORE_CASE), "https:")
        if (!value.contains("://")) value = "https://$value"
        val url = value.toHttpUrlOrNull() ?: error("Use um endereço HTTP ou HTTPS válido.")
        if (url.host in setOf("github.com", "www.github.com")) {
            val parts = url.pathSegments.filter(String::isNotBlank)
            require(parts.size >= 2) { "Cole o link do repositório ou de um arquivo." }
            val tail = when {
                parts.size == 2 -> "main/manifest.json"
                parts[2] in setOf("blob", "raw") -> parts.drop(3).joinToString("/")
                parts[2] == "tree" -> parts.drop(3).joinToString("/") + "/manifest.json"
                else -> error("Use o link de um arquivo JSON ou JavaScript.")
            }
            value = "https://raw.githubusercontent.com/${parts[0]}/${parts[1].removeSuffix(".git")}/$tail"
        } else {
            value = url.newBuilder().fragment(null).build().toString()
            if (!url.encodedPath.matches(Regex(".*\\.(?:json|js)$", RegexOption.IGNORE_CASE))) value = value.trimEnd('/') + "/manifest.json"
        }
        return publicUrl(value).ifBlank { error("Use um endereço HTTP ou HTTPS público válido.") }
    }
    fun headers(raw: JSONObject?): Map<String, String> = raw?.keys()?.asSequence()?.take(32)?.mapNotNull { key ->
        val value = raw.opt(key) as? String
        if (key in unsafeKey || !key.matches(Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]{1,64}$")) || key.lowercase() in setOf("host", "content-length", "connection", "transfer-encoding", "accept-encoding") || value == null || value.length > 8192 || value.any { it == '\r' || it == '\n' || it == '\u0000' }) null else key to value
    }?.toMap().orEmpty()
    private fun strings(raw: JSONArray?, fallback: List<String> = emptyList()) = if (raw == null) fallback else (0 until raw.length()).mapNotNull { raw.opt(it) as? String }.filter(String::isNotBlank).map { it.take(80) }.take(24)
    fun parseRepository(body: String, original: String): PluginRepo {
        val url = normalize(original)
        if (url.substringBefore('?').endsWith(".js", true)) return PluginRepo(url, url.substringBefore('?').substringAfterLast('/').removeSuffix(".js"), "script", providers = listOf(PluginProvider("script", url.substringBefore('?').substringAfterLast('/'), url)))
        require(body.length <= 1024 * 1024) { "O manifesto ultrapassou o tamanho permitido." }
        val raw = runCatching { JSONObject(body) }.getOrElse { error("O endereço não retornou um manifesto JSON válido.") }
        val entries = raw.optJSONArray("scrapers") ?: raw.optJSONArray("providers") ?: raw.optJSONArray("plugins")
        if (entries == null) {
            require(raw.optString("id").isNotBlank() && (raw.optJSONArray("resources")?.length() ?: 0) > 0) { "Formato não reconhecido. Use um repositório Nuvio, addon Stremio ou arquivo JavaScript." }
            return PluginRepo(url, raw.optString("name", raw.optString("id")).take(160), "stremio", raw.optString("version").take(80), raw.optString("description").take(1400), addonId = raw.optString("id").take(200))
        }
        require(entries.length() in 1..300) { "O repositório deve ter entre 1 e 300 provedores." }
        val base = url.toHttpUrlOrNull()!!
        val providers = (0 until entries.length()).map { index ->
            val p = entries.getJSONObject(index)
            val id = p.optString("id").ifBlank { p.optString("internalName", "provider$index") }.take(180)
            require(id.isNotBlank() && id !in unsafeKey && id.none { it <= ' ' }) { "Identificador de provedor inválido." }
            val file = p.optString("filename").ifBlank { p.optString("script").ifBlank { p.optString("url").ifBlank { p.optString("src") } } }
            require(!file.contains("\\") && !file.substringBefore('?').endsWith(".cs3", true)) { "Este formato usa extensões nativas. Use um provedor Nuvio JavaScript." }
            val script = base.resolve(file)?.toString().orEmpty()
            require(publicUrl(script).isNotBlank() && script.substringBefore('?').endsWith(".js", true)) { "O provedor $id não tem um arquivo JavaScript válido." }
            val integrity = p.optString("sha256").ifBlank { p.optString("integrity") }.replace(Regex("^sha256[:-]", RegexOption.IGNORE_CASE), "").lowercase()
            require(integrity.isBlank() || integrity.matches(Regex("[a-f0-9]{64}"))) { "Checksum de provedor inválido." }
            PluginProvider(id, p.optString("name", id).take(160), script, p.optString("version").take(80), p.optString("description").take(1400),
                strings(p.optJSONArray("supportedTypes") ?: p.optJSONArray("types"), listOf("movie", "tv")), strings(p.optJSONArray("contentLanguage") ?: p.optJSONArray("languages")), strings(p.optJSONArray("formats")), available = p.optBoolean("enabled", true) && p.optInt("status", 1) != 0, integrity = integrity)
        }
        require(providers.map { it.id }.distinct().size == providers.size) { "O manifesto repete um identificador de provedor." }
        return PluginRepo(url, raw.optString("name", "Repositório de plugins").take(160), version = raw.optString("version").take(80), description = raw.optString("description").take(1400), author = raw.optString("author").take(160), providers = providers)
    }
    fun fields(json: String): List<PluginField> {
        val raw = runCatching { JSONArray(json) }.getOrDefault(JSONArray())
        return (0 until raw.length().coerceAtMost(100)).mapNotNull { index ->
            val f = raw.optJSONObject(index) ?: return@mapNotNull null
            val key = f.optString("key"); if (key in unsafeKey) return@mapNotNull null
            val options = f.optJSONArray("options") ?: JSONArray()
            PluginField(key, f.optString("label"), f.optString("type", "text"), f.optString("description"), f.optBoolean("secret"), f.optBoolean("required"), f.opt("defaultValue")?.takeUnless { it == JSONObject.NULL },
                (0 until options.length()).mapNotNull { i -> options.optJSONObject(i)?.let { o -> o.optString("label") to (o.opt("value") ?: "") } },
                f.opt("min")?.let { (it as? Number)?.toDouble() }, f.opt("max")?.let { (it as? Number)?.toDouble() })
        }
    }
    fun validate(fields: List<PluginField>, values: JSONObject): String {
        val out = JSONObject()
        fields.filter { it.type != "header" && it.key.isNotBlank() && it.key !in unsafeKey }.forEach { f ->
            var value = values.opt(f.key)?.takeUnless { it == JSONObject.NULL } ?: f.defaultValue
            value = when (f.type) {
                "toggle" -> value == true || value == "true"
                "number", "slider" -> (if (value == null || value.toString().isBlank()) f.min ?: 0.0 else value.toString().toDoubleOrNull() ?: error("Revise o valor de ${f.label}.")).also { require(it.isFinite() && (f.min == null || it >= f.min) && (f.max == null || it <= f.max)) { "Revise o valor de ${f.label}." } }
                "select" -> (value ?: f.options.firstOrNull()?.second ?: "").also { v -> require(f.options.any { it.second.toString() == v.toString() }) { "Escolha uma opção em ${f.label}." } }
                "multiselect" -> (value as? JSONArray ?: JSONArray()).also { v -> require((0 until v.length()).all { i -> f.options.any { it.second.toString() == v.optString(i) } }) { "Revise as opções de ${f.label}." } }
                else -> (value?.toString() ?: "").also { require(it.length <= 16384) { "O campo ${f.label} está muito longo." } }
            }
            require(!f.required || value.toString().isNotBlank()) { "Preencha ${f.label}." }
            out.put(f.key, value)
        }
        return out.toString().also { require(it.length <= 32768) { "As configurações ultrapassaram o tamanho permitido." } }
    }
}
