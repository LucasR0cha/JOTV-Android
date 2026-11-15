package com.joabtv.app.data.remote

import com.joabtv.app.BuildConfig
import com.joabtv.app.data.local.PlatformPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MultiTrackerService @Inject constructor(private val prefs: PlatformPrefs) {
    private val client = OkHttpClient.Builder().dns(AppDns.cloudflare)
        .callTimeout(20, TimeUnit.SECONDS).connectTimeout(8, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jsonType = "application/json".toMediaType()
    private val sessionLock = Mutex()
    private val publicListLock = Mutex()
    private val _status = MutableStateFlow(TrackerProvider.entries.associateWith { TrackerStatus() })
    val status = _status.asStateFlow()
    private val queues = TrackerProvider.entries.associateWith { Channel<suspend () -> Unit>(Channel.UNLIMITED) }

    init {
        queues.forEach { (_, queue) -> scope.launch { for (work in queue) work() } }
        scope.launch { prefs.profileId.collect {
            _status.value = TrackerProvider.entries.associateWith { provider ->
                TrackerStatus(message = if (credentials(provider).token.isBlank()) "Desconectado" else "Credencial salva. Verifique a conexão")
            }
        } }
    }

    private fun token(provider: TrackerProvider): String = when (provider) {
        TrackerProvider.SIMKL -> prefs.simklToken
        TrackerProvider.WETRAKR -> prefs.wetrakrKey
        TrackerProvider.PUBLICMETA -> prefs.publicmetadbKey
        TrackerProvider.MDBLIST -> prefs.mdblistKey
    }

    private fun setToken(provider: TrackerProvider, value: String) = when (provider) {
        TrackerProvider.SIMKL -> { prefs.simklToken = value }
        TrackerProvider.WETRAKR -> { prefs.wetrakrKey = value }
        TrackerProvider.PUBLICMETA -> { prefs.publicmetadbKey = value }
        TrackerProvider.MDBLIST -> { prefs.mdblistKey = value }
    }

    private fun sessions() = runCatching { JSONObject(prefs.trackerSessions) }.getOrElse { JSONObject() }
    internal fun credentials(provider: TrackerProvider): TrackerCredentials {
        val saved = sessions().optJSONObject(provider.name)
        val builtIn = when (provider) {
            TrackerProvider.SIMKL -> BuildConfig.SIMKL_CLIENT_ID
            TrackerProvider.WETRAKR -> BuildConfig.WETRAKR_CLIENT_ID
            else -> ""
        }
        return TrackerCredentials(token(provider), saved?.optString("client_id").orEmpty().ifBlank { builtIn },
            saved?.optString("refresh_token").orEmpty(), saved?.optLong("expires_at") ?: 0)
    }

    fun clientId(provider: TrackerProvider): String = credentials(provider).clientId
    fun savedToken(provider: TrackerProvider): String = token(provider)

    private suspend fun save(provider: TrackerProvider, credentials: TrackerCredentials, profile: String) = sessionLock.withLock {
        check(prefs.profileId.value == profile) { "O perfil mudou. Abra a conexão novamente" }
        prefs.trackerSessions = sessions().put(provider.name, JSONObject().put("client_id", credentials.clientId)
            .put("refresh_token", credentials.refresh).put("expires_at", credentials.expiresAt)).toString()
        setToken(provider, credentials.token)
    }

    fun disconnect(provider: TrackerProvider) {
        setToken(provider, "")
        prefs.trackerSessions = sessions().apply { remove(provider.name) }.toString()
        _status.update { it + (provider to TrackerStatus()) }
    }

    private data class Reply(val code: Int, val body: String) {
        fun json() = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
    }

    private suspend fun raw(provider: TrackerProvider, path: String, credentials: TrackerCredentials, body: RequestBody? = null, authenticate: Boolean = true, method: String? = null): Reply = withContext(Dispatchers.IO) {
        val url = (provider.baseUrl + path).toHttpUrl().newBuilder().apply {
            if (authenticate && provider == TrackerProvider.MDBLIST) addQueryParameter("apikey", credentials.token)
        }.build()
        val request = Request.Builder().url(url).header("User-Agent", "JOTV/${BuildConfig.VERSION_NAME}")
            .header("Accept", "application/json").apply {
                if (authenticate && provider != TrackerProvider.MDBLIST) header("Authorization", "Bearer ${credentials.token}")
                if (provider == TrackerProvider.SIMKL && credentials.clientId.isNotBlank()) header("simkl-api-key", credentials.clientId)
                if (provider == TrackerProvider.WETRAKR) {
                    header("wetrakr-api-version", "1")
                    if (credentials.clientId.isNotBlank()) header("wetrakr-api-key", credentials.clientId)
                }
                if (body != null) post(body)
                if (method != null) method(method, body)
            }.build()
        client.newCall(request).execute().use { response ->
            val contentType = response.body?.contentType()?.subtype.orEmpty()
            val text = response.body?.string().orEmpty()
            if (response.isSuccessful && response.code != 204 &&
                ("json" !in contentType || text.trimStart().firstOrNull() !in listOf('{', '[')))
                error("O serviço devolveu uma resposta inválida")
            Reply(response.code, text)
        }
    }

    private fun JSONObject.body() = toString().toRequestBody(jsonType)
    private class ServiceError(val status: Int, message: String) : Exception(message)
    private fun requireSuccess(reply: Reply) {
        if (reply.code !in 200..299) throw ServiceError(reply.code, when (reply.code) {
            401, 403 -> "Credencial recusada ou sem permissão. Conecte novamente"
            429 -> "Limite do serviço atingido. Tente mais tarde"
            else -> "O serviço não concluiu a solicitação (HTTP ${reply.code})"
        })
        val json = reply.json()
        if (json.optBoolean("ignored") || !json.optBoolean("success", true) || json.optString("error").isNotBlank() ||
            listOf("not_found", "notFound", "errored").any { key ->
                val value = json.opt(key)
                value is org.json.JSONArray && value.length() > 0 ||
                    value is JSONObject && value.keys().asSequence().any { (value.opt(it) as? org.json.JSONArray)?.length()?.let { n -> n > 0 } == true }
            }) error("O serviço não reconheceu o título ou não aceitou a alteração")
    }

    private suspend fun guarded(provider: TrackerProvider, profile: String, action: suspend () -> Unit): Boolean {
        if (prefs.profileId.value != profile) return false
        _status.update { it + (provider to (it[provider] ?: TrackerStatus()).copy(busy = true)) }
        return try {
            action()
            if (prefs.profileId.value == profile) _status.update { it + (provider to TrackerStatus(true, false, "Conexão verificada")) }
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (prefs.profileId.value == profile) _status.update { it + (provider to TrackerStatus(
                connected = it[provider]?.connected == true && (error as? ServiceError)?.status !in listOf(401, 403),
                message = error.message?.takeIf { text -> !text.contains("http", true) }.orEmpty().ifBlank { "Não foi possível conectar ao serviço" })) }
            false
        } finally {
            if (prefs.profileId.value == profile) _status.update { it + (provider to (it[provider] ?: TrackerStatus()).copy(busy = false)) }
        }
    }

    suspend fun connect(provider: TrackerProvider, accessToken: String, clientId: String): Boolean {
        val profile = prefs.profileId.value
        return guarded(provider, profile) {
            val credentials = TrackerCredentials(accessToken.trim(), clientId.trim())
            require(credentials.token.isNotBlank()) { "Informe a chave ou conecte com código" }
            require(!provider.needsClientId || credentials.clientId.isNotBlank()) { "Informe o ID de aplicativo registrado no serviço" }
            validate(provider, credentials)
            save(provider, credentials, profile)
        }
    }

    private suspend fun validate(provider: TrackerProvider, credentials: TrackerCredentials) {
        val path = when (provider) {
            TrackerProvider.SIMKL -> "/users/settings"
            TrackerProvider.WETRAKR -> "/account/settings"
            TrackerProvider.PUBLICMETA -> "/api/external/resume?perPage=1"
            TrackerProvider.MDBLIST -> "/user"
        }
        requireSuccess(raw(provider, path, credentials, if (provider == TrackerProvider.SIMKL) JSONObject().body() else null))
    }

    suspend fun verify(provider: TrackerProvider): Boolean {
        val profile = prefs.profileId.value
        return guarded(provider, profile) { validate(provider, readyCredentials(provider, profile)) }
    }

    private suspend fun readyCredentials(provider: TrackerProvider, profile: String): TrackerCredentials = sessionLock.withLock {
        check(prefs.profileId.value == profile) { "O perfil mudou" }
        val saved = credentials(provider)
        require(saved.token.isNotBlank()) { "Desconectado" }
        require(!provider.needsClientId || saved.clientId.isNotBlank()) { "Informe o ID de aplicativo registrado no serviço" }
        if (saved.refresh.isBlank() || saved.expiresAt > System.currentTimeMillis() + 60_000) return@withLock saved
        val request = if (provider == TrackerProvider.SIMKL) FormBody.Builder()
            .add("grant_type", "refresh_token").add("client_id", saved.clientId).add("refresh_token", saved.refresh).build()
        else JSONObject().put("refresh_token", saved.refresh).body()
        val reply = raw(provider, if (provider == TrackerProvider.SIMKL) "/oauth2/token" else "/oauth/token/refresh", saved, request, false)
        requireSuccess(reply)
        val data = reply.json()
        val next = saved.copy(token = data.getString("access_token"), refresh = data.optString("refresh_token", saved.refresh),
            expiresAt = System.currentTimeMillis() + data.optLong("expires_in", 604800) * 1000)
        check(prefs.profileId.value == profile) { "O perfil mudou" }
        prefs.trackerSessions = sessions().put(provider.name, JSONObject().put("client_id", next.clientId)
            .put("refresh_token", next.refresh).put("expires_at", next.expiresAt)).toString()
        setToken(provider, next.token)
        next
    }

    data class DeviceCode(val code: String, val url: String, val device: String, val interval: Int, val expiresAt: Long, val clientId: String, val profile: String)

    suspend fun startLogin(provider: TrackerProvider, clientId: String): DeviceCode {
        require(provider.needsClientId && clientId.isNotBlank()) { "Informe o ID de aplicativo registrado no serviço" }
        val profile = prefs.profileId.value
        val saved = TrackerCredentials("", clientId.trim())
        val body = if (provider == TrackerProvider.SIMKL) FormBody.Builder().add("client_id", saved.clientId).add("scope", "media:read media:write").build()
        else JSONObject().put("client_id", saved.clientId).body()
        val reply = raw(provider, if (provider == TrackerProvider.SIMKL) "/oauth2/device" else "/oauth/device/code", saved, body, false)
        requireSuccess(reply)
        val data = reply.json()
        return DeviceCode(data.getString("user_code"), data.optString("verification_uri_complete")
            .ifBlank { data.optString("verification_uri") }.ifBlank { data.getString("verification_url") },
            data.getString("device_code"), data.optInt("interval", 5).coerceAtLeast(5),
            System.currentTimeMillis() + data.optLong("expires_in", 600) * 1000, saved.clientId, profile)
    }

    suspend fun waitLogin(provider: TrackerProvider, code: DeviceCode): Boolean = guarded(provider, code.profile) {
        var interval = code.interval * 1000L
        while (System.currentTimeMillis() < code.expiresAt) {
            delay(interval)
            check(prefs.profileId.value == code.profile) { "O perfil mudou" }
            val body = if (provider == TrackerProvider.SIMKL) FormBody.Builder()
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .add("client_id", code.clientId).add("device_code", code.device).build()
            else JSONObject().put("client_id", code.clientId).put("code", code.device).body()
            val reply = raw(provider, if (provider == TrackerProvider.SIMKL) "/oauth2/token" else "/oauth/device/token", TrackerCredentials("", code.clientId), body, false)
            val data = reply.json()
            if (reply.code == 200 && data.has("access_token")) {
                val credentials = TrackerCredentials(data.getString("access_token"), code.clientId,
                    data.optString("refresh_token"), System.currentTimeMillis() + data.optLong("expires_in", 604800) * 1000)
                validate(provider, credentials)
                save(provider, credentials, code.profile)
                return@guarded
            }
            when (data.optString("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> interval += 5000
                else -> { requireSuccess(reply); error("O código não foi autorizado. Gere outro código") }
            }
        }
        error("O código expirou. Gere outro código")
    }

    fun scrobble(action: String, title: String, imdb: String, tmdbId: Int, isSeries: Boolean, season: Int, episode: Int, progress: Double, positionMs: Long = 0, durationMs: Long = 0) {
        if (tmdbId <= 0 || action !in listOf("start", "pause", "stop")) return
        val profile = prefs.profileId.value
        TrackerProvider.entries.filter { token(it).isNotBlank() }.forEach { provider ->
            if (provider == TrackerProvider.PUBLICMETA && (action == "start" || durationMs <= 0)) return@forEach
            queues.getValue(provider).trySend {
                guarded(provider, profile) {
                    val credentials = readyCredentials(provider, profile)
                    if (provider == TrackerProvider.PUBLICMETA) {
                        if (action == "start" || durationMs <= 0) return@guarded
                        val body = JSONObject().put("tmdb_id", tmdbId).put("media_type", if (isSeries) "tv" else "movie")
                            .put("position_ms", positionMs).put("runtime_ms", durationMs)
                        if (isSeries) body.put("season", season).put("episode", episode)
                        requireSuccess(raw(provider, "/api/external/resume", credentials, body.body()))
                        if (action == "stop" && progress >= .8) requireSuccess(raw(provider, "/api/external/watched?dedupe=true", credentials, body.body()))
                    } else requireSuccess(raw(provider, "/scrobble/$action", credentials,
                        trackerScrobbleBody(provider, tmdbId, isSeries, season, episode, progress * 100).body()))
                }
            }
        }
    }

    fun watchlist(title: String, imdb: String, tmdbId: Int, isSeries: Boolean, inList: Boolean) {
        val profile = prefs.profileId.value
        TrackerProvider.entries.filter { token(it).isNotBlank() }.forEach { provider ->
            if (provider == TrackerProvider.PUBLICMETA) {
                queues.getValue(provider).trySend { guarded(provider, profile) { publicListChanged(tmdbId, isSeries, inList, profile) } }
                return@forEach
            }
            val request = trackerWatchlistRequest(provider, tmdbId, isSeries, inList) ?: return@forEach
            queues.getValue(provider).trySend { guarded(provider, profile) {
                requireSuccess(raw(provider, request.path, readyCredentials(provider, profile), request.body.body()))
            } }
        }
    }

    internal suspend fun read(provider: TrackerProvider, path: String, profile: String): String {
        val reply = raw(provider, path, readyCredentials(provider, profile))
        requireSuccess(reply)
        return reply.body
    }

    internal suspend fun publicListId(profile: String, create: Boolean = false): String? = publicListLock.withLock {
        val provider = TrackerProvider.PUBLICMETA
        var page = 1
        while (page <= 100) {
            val data = JSONObject(read(provider, "/api/external/lists?page=$page&perPage=100", profile))
            val items = data.optJSONArray("items") ?: org.json.JSONArray()
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                if (item.optString("type") == "watchlist" || item.optString("name") == "JOTV") return@withLock item.getString("id")
            }
            if (items.length() < 100) break
            page++
        }
        if (!create) return@withLock null
        val reply = raw(provider, "/api/external/lists", readyCredentials(provider, profile),
            JSONObject().put("name", "JOTV").put("description", "Minha Lista do JOTV").put("type", "custom").put("is_public", false).body())
        requireSuccess(reply)
        reply.json().getJSONObject("item").getString("id")
    }

    private suspend fun publicListChanged(tmdbId: Int, series: Boolean, added: Boolean, profile: String) {
        val listId = publicListId(profile, added) ?: return
        val path = "/api/external/lists/$listId/items"
        val provider = TrackerProvider.PUBLICMETA
        val credentials = readyCredentials(provider, profile)
        if (added) {
            requireSuccess(raw(provider, path, credentials, JSONObject().put("tmdb_id", tmdbId).put("media_type", if (series) "tv" else "movie").body()))
            return
        }
        for (page in 1..100) {
            val items = JSONObject(read(provider, "$path?page=$page&perPage=100", profile)).optJSONArray("items") ?: break
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                if (item.optInt("tmdb_id") == tmdbId && item.optString("media_type") == if (series) "tv" else "movie") {
                    requireSuccess(raw(provider, "$path/${item.getString("id")}", credentials, method = "DELETE"))
                    return
                }
            }
            if (items.length() < 100) break
        }
    }

    internal fun syncResult(provider: TrackerProvider, profile: String, success: Boolean) {
        if (prefs.profileId.value == profile) _status.update { it + (provider to TrackerStatus(success || it[provider]?.connected == true, false,
            if (success) "Conectado. Histórico sincronizado" else "A sincronização falhou. Verifique a conexão e tente novamente")) }
    }
}
