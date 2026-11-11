package com.joabtv.app.data.plugins

import android.content.Context
import android.util.Base64
import com.google.gson.Gson
import com.joabtv.app.data.local.AddonEntry
import com.joabtv.app.data.local.PlatformPrefs
import com.joabtv.app.data.remote.ExternalSub
import com.joabtv.app.data.remote.StremioStream
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@Singleton
class PluginRepository @Inject constructor(@ApplicationContext private val context: Context, private val prefs: PlatformPrefs, private val network: PluginNetwork, private val engine: PluginEngine) {
    private val storage = context.getSharedPreferences("jotv_plugins", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serial = Semaphore(1)
    private val parallel = Semaphore(2)
    private val lock = Any()
    private val codeLock = Mutex()
    private var loadedProfile = prefs.profileId.value
    private var lastUpdateCheck = 0L
    @Volatile private var generation = 0
    private fun read(): PluginState = runCatching { gson.fromJson(storage.getString("state.${prefs.profileId.value}", ""), PluginState::class.java) }.getOrNull()?.takeIf { it.repos.size <= 40 } ?: PluginState()
    private val _state = MutableStateFlow(read())
    val state: StateFlow<PluginState> = _state
    init {
        scope.launch { prefs.profileId.drop(1).collect { synchronized(lock) { generation++; engine.cancel(); loadedProfile = it; _state.value = read(); lastUpdateCheck = 0 } } }
        scope.launch { prefs.profiles.collect { profiles ->
            val allowed = profiles.map { "state.${it.id}" }.toSet()
            storage.all.keys.filter { it.startsWith("state.") && it !in allowed }.forEach { storage.edit().remove(it).apply() }
        } }
    }
    private fun update(change: (PluginState) -> PluginState) = synchronized(lock) {
        if (loadedProfile != prefs.profileId.value) { loadedProfile = prefs.profileId.value; _state.value = read() }
        val value = change(_state.value); storage.edit().putString("state.$loadedProfile", gson.toJson(value)).apply(); _state.value = value
    }
    fun key(repo: PluginRepo, provider: PluginProvider) = "plugin:" + java.net.URLEncoder.encode(repo.url, "UTF-8") + "|" + java.net.URLEncoder.encode(provider.id, "UTF-8")
    fun find(id: String): Pair<PluginRepo, PluginProvider>? = _state.value.repos.firstNotNullOfOrNull { r -> r.providers.firstOrNull { key(r, it) == id }?.let { r to it } }
    fun sourceKeys(type: String): List<String> {
        if (loadedProfile != prefs.profileId.value) update { it }
        refreshDue()
        val s = _state.value
        if (!s.enabled || prefs.profile.kids) return emptyList()
        val target = if (type == "series") "tv" else type
        return s.repos.filter { it.enabled }.flatMap { r -> r.providers.filter { it.enabled && it.available && (target in it.types || type in it.types) }.map { key(r, it) } }
    }
    private fun changeProvider(id: String, fn: (PluginProvider) -> PluginProvider) = update { s -> s.copy(repos = s.repos.map { r -> r.copy(providers = r.providers.map { if (key(r, it) == id) fn(it) else it }) }) }
    private fun cancel() { generation++; engine.cancel() }
    fun toggle(id: String, enabled: Boolean) { cancel(); changeProvider(id) { it.copy(enabled = enabled && it.available) } }
    fun toggleRepo(url: String, enabled: Boolean) { cancel(); update { it.copy(repos = it.repos.map { r -> if (r.url == url) r.copy(enabled = enabled) else r }) } }
    fun toggleAll(url: String, enabled: Boolean) { cancel(); update { it.copy(repos = it.repos.map { r -> if (r.url == url) r.copy(providers = r.providers.map { p -> p.copy(enabled = enabled && p.available) }) else r }) } }
    fun remove(url: String) { cancel(); update { it.copy(repos = it.repos.filterNot { r -> r.url == url }) } }
    fun configure(enabled: Boolean? = null, autoUpdate: Boolean? = null, timeout: Long? = null, concurrency: Int? = null) { cancel(); update { it.copy(enabled = enabled ?: it.enabled, autoUpdate = autoUpdate ?: it.autoUpdate, timeout = timeout?.coerceIn(10_000, 120_000) ?: it.timeout, concurrency = concurrency?.coerceIn(1, 2) ?: it.concurrency) } }
    fun move(url: String, providerId: String?, direction: Int) {
        cancel()
        update { s ->
            fun <T> shifted(list: List<T>, index: Int): List<T> { val next = index + direction; if (index !in list.indices || next !in list.indices) return list; return list.toMutableList().apply { val old = this[index]; this[index] = this[next]; this[next] = old } }
            if (providerId == null) s.copy(repos = shifted(s.repos, s.repos.indexOfFirst { it.url == url }))
            else s.copy(repos = s.repos.map { r -> if (r.url == url) r.copy(providers = shifted(r.providers, r.providers.indexOfFirst { it.id == providerId })) else r })
        }
    }
    private suspend fun download(url: String, max: Int = 1024 * 1024): String {
        val session = network.session()
        try {
            val response = session.fetch(JSONObject().put("url", url), max)
            val status = response.optInt("status")
            if (status !in 200..299) throw PluginHttpException(status)
            return String(Base64.decode(response.optString("bodyBase64"), Base64.DEFAULT), Charsets.UTF_8)
        } finally { session.cancel() }
    }
    private class PluginHttpException(val status: Int) : IllegalStateException("O repositório respondeu com HTTP $status.")
    suspend fun inspect(input: String): PluginRepo {
        var url = PluginContract.normalize(input)
        val body = try { download(url) } catch (e: PluginHttpException) {
            if (e.status != 404 || !url.matches(Regex("https://raw\\.githubusercontent\\.com/[^/]+/[^/]+/main/manifest\\.json"))) throw e
            url = url.replace("/main/manifest.json", "/master/manifest.json"); download(url)
        }
        return PluginContract.parseRepository(body, url)
    }
    suspend fun install(input: String): PluginRepo {
        check(!prefs.profile.kids) { "Plugins disponíveis no perfil adulto." }
        val owner = prefs.profileId.value
        val repo = inspect(input)
        check(owner == prefs.profileId.value) { "O perfil mudou. Tente novamente." }
        if (repo.kind == "stremio") {
            if (prefs.addonEntries.none { it.url == repo.url }) prefs.addonEntries = prefs.addonEntries + AddonEntry(repo.url, true, repo.addonId)
            return repo
        }
        update { s -> require(s.repos.none { it.url == repo.url }) { "Este repositório já está instalado. Use Atualizar para buscar mudanças." }; require(s.repos.size < 40) { "O limite de repositórios foi atingido." }; s.copy(repos = s.repos + repo.copy(checkedAt = System.currentTimeMillis())) }
        return repo
    }
    suspend fun refresh(url: String) {
        val owner = prefs.profileId.value
        val next = inspect(url)
        check(next.kind != "stremio") { "O repositório mudou de formato. Revise o endereço." }
        if (owner != prefs.profileId.value) return
        update { s -> s.copy(repos = s.repos.map { old ->
            if (old.url != url) old else next.copy(enabled = old.enabled, checkedAt = System.currentTimeMillis(), providers = (next.providers.map { p -> old.providers.firstOrNull { it.id == p.id }?.let { p.copy(enabled = it.enabled, settings = it.settings, fields = it.fields, status = it.status) } ?: p } + old.providers.filter { p -> next.providers.none { it.id == p.id } }.map { it.copy(available = false) }).sortedBy { p -> old.providers.indexOfFirst { it.id == p.id }.takeIf { it >= 0 } ?: Int.MAX_VALUE })
        }) }
    }
    fun refreshDue() {
        val s = _state.value
        synchronized(lock) { if (System.currentTimeMillis() - lastUpdateCheck < 120_000) return; lastUpdateCheck = System.currentTimeMillis() }
        if (s.autoUpdate && !prefs.profile.kids) scope.launch { s.repos.filter { System.currentTimeMillis() - it.checkedAt > 24 * 60 * 60_000L }.forEach { r -> runCatching { refresh(r.url) } } }
    }
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private suspend fun code(provider: PluginProvider): String = codeLock.withLock { withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "plugin-code").apply { mkdirs() }
        val file = File(directory, digest(provider.url + "\n" + provider.version) + ".js")
        if (file.isFile) {
            val old = file.readText(Charsets.UTF_8)
            if (old.length <= 1024 * 1024 && (provider.integrity.isBlank() || digest(old) == provider.integrity)) { file.setLastModified(System.currentTimeMillis()); return@withContext old }
        }
        val text = download(provider.url)
        require(provider.integrity.isBlank() || digest(text) == provider.integrity) { "O arquivo não corresponde ao checksum do repositório." }
        android.util.AtomicFile(file).apply { val output = startWrite(); try { output.write(text.toByteArray(Charsets.UTF_8)); finishWrite(output) } catch (e: Exception) { failWrite(output); throw e } }
        var size = 0L
        directory.listFiles().orEmpty().sortedByDescending { it.lastModified() }.forEach { cached -> size += cached.length(); if (size > 16 * 1024 * 1024) cached.delete() }
        text
    } }
    suspend fun clearCache() = codeLock.withLock { withContext(Dispatchers.IO) { File(context.cacheDir, "plugin-code").listFiles().orEmpty().filter { it.isFile }.forEach { it.delete() } } }
    private suspend fun execute(id: String, method: String, tmdbId: Int = 0, type: String = "movie", season: Int? = null, episode: Int? = null): JSONObject {
        val (repo, provider) = find(id) ?: error("Provedor não encontrado.")
        check(provider.available && !prefs.profile.kids) { "Provedor indisponível neste perfil." }
        val before = generation
        val owner = prefs.profileId.value
        suspend fun work(): JSONObject {
            check(before == generation && owner == prefs.profileId.value) { "Pedido cancelado." }
            val started = System.currentTimeMillis()
            try {
                val args = if (method == "settings") JSONArray() else JSONArray().put(tmdbId.toString()).put(if (type == "movie") "movie" else "tv").put(season).put(episode).put(JSONObject(provider.settings))
                val input = JSONObject().put("code", code(provider)).put("integrity", provider.integrity).put("name", provider.name).put("method", method).put("args", args).put("settings", JSONObject(provider.settings)).put("tmdbKey", prefs.tmdbApiKey).put("timeout", _state.value.timeout)
                check(before == generation && owner == prefs.profileId.value) { "Pedido cancelado." }
                val result = engine.execute(input)
                if (before != generation || owner != prefs.profileId.value) return JSONObject().put("value", JSONArray())
                val values = result.optJSONArray("value") ?: JSONArray()
                changeProvider(id) { p -> p.copy(fields = if (method == "settings") values.toString() else p.fields, status = PluginStatus(if (method == "settings") "Plugin carregado e configurações disponíveis." else if (values.length() > 0) "${values.length()} fontes encontradas." else "Nenhuma fonte para este título.", true, ms = System.currentTimeMillis() - started, count = if (method == "streams") values.length() else 0)) }
                return result
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (before == generation && owner == prefs.profileId.value) changeProvider(id) { it.copy(status = PluginStatus(e.message?.take(240) ?: "O plugin não respondeu.", false, ms = System.currentTimeMillis() - started)) }; throw e }
        }
        return if (_state.value.concurrency == 1) serial.withPermit { work() } else parallel.withPermit { work() }
    }
    suspend fun fields(id: String): List<PluginField> = PluginContract.fields((execute(id, "settings").optJSONArray("value") ?: JSONArray()).toString())
    suspend fun testSources(id: String, type: String, tmdbId: Int, season: Int?, episode: Int?): Int {
        require(tmdbId > 0 && (type == "movie" || (season ?: 0) > 0 && (episode ?: 0) > 0)) { "Informe um TMDB válido e os dados do episódio." }
        return execute(id, "streams", tmdbId, type, season, episode).optJSONArray("value")?.length() ?: 0
    }
    fun saveSettings(id: String, values: JSONObject) { val provider = find(id)?.second ?: return; val validated = PluginContract.validate(PluginContract.fields(provider.fields), values); cancel(); changeProvider(id) { it.copy(settings = validated) } }
    suspend fun streams(id: String, type: String, tmdbId: Int, season: Int?, episode: Int?): List<StremioStream> {
        if (tmdbId <= 0 || id !in sourceKeys(type)) return emptyList()
        val (repo, provider) = find(id) ?: return emptyList()
        val result = execute(id, "streams", tmdbId, type, season, episode).optJSONArray("value") ?: JSONArray()
        return (0 until result.length()).mapNotNull { index ->
            val s = result.optJSONObject(index) ?: return@mapNotNull null
            val url = PluginContract.publicUrl(s.optString("url")); if (url.isBlank()) return@mapNotNull null
            val label = s.optString("label", provider.name).take(240)
            val description = s.optString("description").take(1400)
            val text = "$label $description ${s.optString("language")}".lowercase()
            val subs = s.optJSONArray("subtitles") ?: JSONArray()
            StremioStream(label + if (description.isBlank()) "" else " · $description", url, "${repo.name} · ${provider.name}", s.optInt("qualityRank"), hdr = "hdr" in text, dolbyVision = "dolby vision" in text || "dovi" in text, atmos = "atmos" in text, ptAudio = "dublado" in text || "pt-br" in text, releaseText = description,
                headers = PluginContract.headers(s.optJSONObject("headers")), subtitles = (0 until subs.length().coerceAtMost(40)).mapNotNull { i -> val sub = subs.optJSONObject(i) ?: return@mapNotNull null; val u = PluginContract.publicUrl(sub.optString("url")); if (u.isBlank()) null else ExternalSub(u, sub.optString("language"), sub.optString("label", "Legenda"), PluginContract.headers(sub.optJSONObject("headers"))) })
        }
    }
    fun backup(): String {
        val state = JSONObject(gson.toJson(_state.value))
        val repos = state.getJSONArray("repos")
        for (i in 0 until repos.length()) { val providers = repos.getJSONObject(i).getJSONArray("providers"); for (j in 0 until providers.length()) { val p = providers.getJSONObject(j); p.put("settings", "{}"); p.remove("fields"); p.remove("status") } }
        return JSONObject().put("format", "jotv.plugins").put("version", 1).put("state", state).toString(2)
    }
    suspend fun restore(text: String): Int {
        val owner = prefs.profileId.value
        fun guard() { check(owner == prefs.profileId.value) { "O perfil mudou. Tente novamente." } }
        require(text.length <= 2 * 1024 * 1024) { "Backup muito grande." }
        val data = JSONObject(text)
        require(data.optString("format") == "jotv.plugins") { "Este arquivo não é um backup de plugins do JOTV." }
        val repos = data.getJSONObject("state").getJSONArray("repos")
        require(repos.length() <= 40) { "Backup com muitos repositórios." }
        var failed = 0
        for (i in 0 until repos.length()) {
            guard()
            val original = repos.getJSONObject(i)
            try {
                val url = PluginContract.normalize(original.getString("url"))
                if (_state.value.repos.none { it.url == url }) install(url)
                guard()
                toggleRepo(url, original.optBoolean("enabled", true))
                val providers = original.optJSONArray("providers") ?: JSONArray()
                for (j in 0 until providers.length()) { val p = providers.getJSONObject(j); val repo = _state.value.repos.firstOrNull { it.url == url } ?: continue; repo.providers.firstOrNull { it.id == p.optString("id") }?.let { toggle(key(repo, it), p.optBoolean("enabled")) } }
                val order = (0 until providers.length()).map { providers.getJSONObject(it).optString("id") }
                update { s -> s.copy(repos = s.repos.map { r -> if (r.url != url) r else r.copy(providers = r.providers.sortedBy { order.indexOf(it.id).takeIf { it >= 0 } ?: Int.MAX_VALUE }) }) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { guard(); failed++ }
        }
        guard()
        val saved = data.getJSONObject("state")
        val order = (0 until repos.length()).mapNotNull { runCatching { PluginContract.normalize(repos.getJSONObject(it).getString("url")) }.getOrNull() }
        update { s -> s.copy(repos = s.repos.sortedBy { order.indexOf(it.url).takeIf { it >= 0 } ?: Int.MAX_VALUE }) }
        configure(enabled = saved.optBoolean("enabled", true), autoUpdate = saved.optBoolean("autoUpdate", true), timeout = saved.optLong("timeout", 45000), concurrency = saved.optInt("concurrency", 1))
        return failed
    }
}
