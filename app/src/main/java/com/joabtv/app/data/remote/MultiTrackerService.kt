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
    private val cooldown = java.util.concurrent.ConcurrentHashMap<TrackerProvider, Long>()
    /** Passou da cota do dia no WeTrakr. Até esse instante nenhuma chamada sai, em vez de gastar mais cota. */
    private val quotaUntil = java.util.concurrent.ConcurrentHashMap<TrackerProvider, Long>()
    private val holdLock = Any()
    private var held: HeldPause? = null

    /** Pausa do WeTrakr esperando 3 s por um start do mesmo título (o batimento de 5 minutos do player). */
    private class HeldPause(val key: String, val dispatch: () -> Unit) { var timer: kotlinx.coroutines.Job? = null }

    init {
        // Depois de um 429 a fila espera o Retry-After antes do próximo item, sem repetir o que falhou.
        queues.forEach { (provider, queue) -> scope.launch { for (work in queue) {
            val wait = (cooldown[provider] ?: 0L) - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            work()
        } } }
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
            TrackerProvider.MDBLIST -> BuildConfig.MDBLIST_CLIENT_ID
            else -> ""
        }
        // Token sem sessão (backup antigo ou chave colada) continua como chave da API no MDBList.
        return TrackerCredentials(token(provider), saved?.optString("client_id").orEmpty().ifBlank { builtIn },
            saved?.optString("refresh_token").orEmpty(), saved?.optLong("expires_at") ?: 0, trackerSessionIsOAuth(saved))
    }

    fun clientId(provider: TrackerProvider): String = credentials(provider).clientId
    fun savedToken(provider: TrackerProvider): String = token(provider)
    fun usesCode(provider: TrackerProvider): Boolean = credentials(provider).oauth

    // [keep] mantém os outros campos da sessão (carimbos da sincronia) quando só o token foi renovado.
    // Login novo começa do zero, porque pode ser outra conta.
    private fun writeSession(provider: TrackerProvider, credentials: TrackerCredentials, keep: Boolean = false) {
        val all = sessions()
        val base = if (keep) all.optJSONObject(provider.name) ?: JSONObject() else JSONObject()
        prefs.trackerSessions = all.put(provider.name, base.put("client_id", credentials.clientId)
            .put("refresh_token", credentials.refresh).put("expires_at", credentials.expiresAt).put("oauth", credentials.oauth)).toString()
        setToken(provider, credentials.token)
    }

    /** Carimbo da última sincronia completa guardado na sessão do serviço (por perfil). */
    internal fun syncMark(provider: TrackerProvider, key: String): String =
        sessions().optJSONObject(provider.name)?.optJSONObject("sync")?.optString(key).orEmpty()

    internal suspend fun setSyncMark(provider: TrackerProvider, key: String, value: String, profile: String) = sessionLock.withLock {
        if (prefs.profileId.value != profile) return@withLock
        val all = sessions()
        if (token(provider).isBlank()) return@withLock
        // Chave antiga sem sessão ganha uma só com o carimbo. Sem refresh nem marcador, segue como chave da API.
        val session = all.optJSONObject(provider.name) ?: JSONObject()
        session.put("sync", (session.optJSONObject("sync") ?: JSONObject()).put(key, value))
        prefs.trackerSessions = all.put(provider.name, session).toString()
    }

    private suspend fun save(provider: TrackerProvider, credentials: TrackerCredentials, profile: String) = sessionLock.withLock {
        check(prefs.profileId.value == profile) { "O perfil mudou. Abra a conexão novamente" }
        writeSession(provider, credentials)
    }

    fun disconnect(provider: TrackerProvider) {
        val old = credentials(provider)
        setToken(provider, "")
        prefs.trackerSessions = sessions().apply { remove(provider.name) }.toString()
        _status.update { it + (provider to TrackerStatus()) }
        // O WeTrakr deixa encerrar a sessão no servidor. Se falhar, o token vence sozinho em 7 dias.
        if (provider == TrackerProvider.WETRAKR && old.token.isNotBlank() && old.refresh.isNotBlank())
            scope.launch { runCatching { raw(provider, "/oauth/logout", old, JSONObject().put("refresh_token", old.refresh).body()) } }
    }

    private data class Reply(val code: Int, val body: String) {
        fun json() = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
    }

    private suspend fun raw(provider: TrackerProvider, path: String, credentials: TrackerCredentials, body: RequestBody? = null, authenticate: Boolean = true, method: String? = null): Reply = withContext(Dispatchers.IO) {
        if ((quotaUntil[provider] ?: 0L) > System.currentTimeMillis()) throw ServiceError(429, "O limite diário do WeTrakr foi atingido. Volta a sincronizar sozinho")
        val url = (provider.baseUrl + path).toHttpUrl().newBuilder().apply {
            if (authenticate && trackerUsesApiKey(provider, credentials)) addQueryParameter("apikey", credentials.token)
        }.build()
        val request = Request.Builder().url(url).header("User-Agent", "JOTV/${BuildConfig.VERSION_NAME}")
            .header("Accept", "application/json").apply {
                if (authenticate && !trackerUsesApiKey(provider, credentials)) header("Authorization", "Bearer ${credentials.token}")
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
            if (provider == TrackerProvider.WETRAKR && wetrakrQuotaExceeded(response.code, text)) quotaUntil[provider] = System.currentTimeMillis() + 30 * 60_000L
            else if (response.code == 429) cooldown[provider] = System.currentTimeMillis() + trackerRetryAfterMs(response.header("Retry-After"))
            if (response.isSuccessful && response.code != 204 &&
                ("json" !in contentType || text.trimStart().firstOrNull() !in listOf('{', '[')))
                error("O serviço devolveu uma resposta inválida")
            Reply(response.code, text)
        }
    }

    private fun JSONObject.body() = toString().toRequestBody(jsonType)
    private fun TrackerForm.body() = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
    private class ServiceError(val status: Int, message: String) : Exception(message)
    /**
     * [strict] falso deixa passar notFound e errored, que o WeTrakr devolve como informação de uma escrita que deu certo
     * (título que ele não conhece, play que já existia). Os outros serviços tratam isso como falha.
     */
    private fun requireSuccess(reply: Reply, strict: Boolean = true) {
        if (reply.code !in 200..299) throw ServiceError(reply.code, when (reply.code) {
            401, 403 -> "Credencial recusada ou sem permissão. Conecte novamente"
            429 -> "Limite do serviço atingido. Tente mais tarde"
            else -> "O serviço não concluiu a solicitação (HTTP ${reply.code})"
        })
        val json = reply.json()
        if (json.optBoolean("ignored") || !json.optBoolean("success", true) || json.optString("error").isNotBlank() ||
            strict && listOf("not_found", "notFound", "errored").any { key ->
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
        return guarded(provider, profile) {
            val credentials = readyCredentials(provider, profile)
            // O WeTrakr confere a sessão na primeira chamada da sincronia (last_activities). Outra chamada só gastaria cota.
            if (provider != TrackerProvider.WETRAKR) validate(provider, credentials)
        }
    }

    /**
     * Faz a chamada com as credenciais prontas. O WeTrakr recusa (401) às vezes o token que ainda parecia valer
     * (relógio do aparelho, sessão girada em outro lugar), então renova uma vez e repete.
     */
    private suspend fun send(provider: TrackerProvider, profile: String, call: suspend (TrackerCredentials) -> Reply): Reply {
        val first = call(readyCredentials(provider, profile))
        if (provider != TrackerProvider.WETRAKR || first.code != 401) return first
        return call(readyCredentials(provider, profile, force = true))
    }

    private suspend fun readyCredentials(provider: TrackerProvider, profile: String, force: Boolean = false): TrackerCredentials = sessionLock.withLock {
        check(prefs.profileId.value == profile) { "O perfil mudou" }
        val saved = credentials(provider)
        require(saved.token.isNotBlank()) { "Desconectado" }
        require(!provider.needsClientId || saved.clientId.isNotBlank()) { "Informe o ID de aplicativo registrado no serviço" }
        if (!(force && saved.refresh.isNotBlank()) && !trackerNeedsRefresh(provider, saved, System.currentTimeMillis())) return@withLock saved
        val form = trackerRefreshForm(provider, saved.clientId, saved.refresh)
        val reply = if (form != null) raw(provider, form.path, saved, form.body(), false)
        else raw(provider, "/oauth/token/refresh", saved, JSONObject().put("refresh_token", saved.refresh).body(), false)
        // Refresh recusado (invalid_grant e afins) pede novo login em vez de mostrar o código HTTP.
        if (reply.code in 400..401 || reply.json().optString("error") == "invalid_grant")
            throw ServiceError(401, "A sessão expirou. Conecte novamente")
        requireSuccess(reply)
        val data = reply.json()
        // O refresh pode girar (o WeTrakr gira a cada renovação e aposenta o antigo em um minuto). Guarda o novo quando vier.
        val next = saved.copy(token = data.getString("access_token"),
            refresh = data.optString("new_refresh_token").ifBlank { data.optString("refresh_token") }.ifBlank { saved.refresh },
            expiresAt = System.currentTimeMillis() + data.optLong("expires_in", 604800) * 1000, oauth = true)
        check(prefs.profileId.value == profile) { "O perfil mudou" }
        writeSession(provider, next, keep = true)
        next
    }

    data class DeviceCode(val code: String, val url: String, val device: String, val interval: Int, val expiresAt: Long, val clientId: String, val profile: String)

    suspend fun startLogin(provider: TrackerProvider, clientId: String): DeviceCode {
        require(provider.needsClientId && clientId.isNotBlank()) { "Informe o ID de aplicativo registrado no serviço" }
        val profile = prefs.profileId.value
        val saved = TrackerCredentials("", clientId.trim())
        val form = trackerDeviceForm(provider, saved.clientId)
        val reply = if (form != null) raw(provider, form.path, saved, form.body(), false)
        else raw(provider, "/oauth/device/code", saved, JSONObject().put("client_id", saved.clientId).body(), false)
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
            val form = trackerPollForm(provider, code.clientId, code.device)
            val anonymous = TrackerCredentials("", code.clientId)
            val reply = if (form != null) raw(provider, form.path, anonymous, form.body(), false)
            else raw(provider, "/oauth/device/token", anonymous, JSONObject().put("client_id", code.clientId).put("code", code.device).body(), false)
            val data = reply.json()
            when (val step = trackerDevicePoll(reply.code, data)) {
                DevicePoll.Token -> {
                    val credentials = TrackerCredentials(data.getString("access_token"), code.clientId,
                        data.optString("refresh_token"), System.currentTimeMillis() + data.optLong("expires_in", 604800) * 1000, oauth = true)
                    validate(provider, credentials)
                    save(provider, credentials, code.profile)
                    return@guarded
                }
                DevicePoll.Pending -> Unit
                DevicePoll.SlowDown -> interval += 5000
                is DevicePoll.Fail -> error(step.message)
            }
        }
        error("O código expirou. Gere outro código")
    }

    fun scrobble(action: String, title: String, imdb: String, tmdbId: Int, isSeries: Boolean, season: Int, episode: Int, progress: Double, positionMs: Long = 0, durationMs: Long = 0) {
        if (tmdbId <= 0 || action !in listOf("start", "pause", "stop")) return
        val profile = prefs.profileId.value
        TrackerProvider.entries.filter { token(it).isNotBlank() }.forEach { provider ->
            if (provider == TrackerProvider.PUBLICMETA && (action == "start" || durationMs <= 0)) return@forEach
            if (provider == TrackerProvider.WETRAKR) { wetrakrScrobble(action, tmdbId, isSeries, season, episode, progress, profile); return@forEach }
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

    /**
     * Scrobble do WeTrakr. A doc pede eventos e nunca temporizador, e quem manda batimento estoura o limite e pode perder a chave.
     * O player salva o ponto a cada 5 minutos com uma pausa e, 1,5 s depois, um start. A pausa espera 3 s por esse start
     * do mesmo título e, se ele vier, os dois somem. Pausa de verdade segue para o serviço depois dos 3 s.
     */
    private fun wetrakrScrobble(action: String, tmdbId: Int, isSeries: Boolean, season: Int, episode: Int, progress: Double, profile: String) {
        val provider = TrackerProvider.WETRAKR
        val key = "$isSeries:$tmdbId:$season:$episode"
        val dispatch: () -> Unit = {
            queues.getValue(provider).trySend {
                guarded(provider, profile) {
                    val body = trackerScrobbleBody(provider, tmdbId, isSeries, season, episode, progress * 100)
                    val reply = send(provider, profile) { raw(provider, "/scrobble/$action", it, body.body()) }
                    // 404 é título que o WeTrakr não conhece. Não é falha da conta, então fica quieto.
                    if (reply.code != 404) requireSuccess(reply, strict = false)
                }
            }
        }
        synchronized(holdLock) {
            val previous = held
            held = null
            if (previous != null) {
                previous.timer?.cancel()
                if (previous.key == key && action == "start") return
                // O stop do mesmo título já diz tudo que a pausa diria. Qualquer outro evento solta a pausa antes.
                if (!(previous.key == key && action == "stop")) previous.dispatch()
            }
            if (action == "pause") {
                val mine = HeldPause(key, dispatch)
                mine.timer = scope.launch {
                    delay(3000)
                    synchronized(holdLock) { if (held === mine) { held = null; mine.dispatch() } }
                }
                held = mine
                return
            }
            dispatch()
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
                requireSuccess(send(provider, profile) { raw(provider, request.path, it, request.body.body()) }, strict = provider != TrackerProvider.WETRAKR)
            } }
        }
    }

    internal suspend fun read(provider: TrackerProvider, path: String, profile: String): String {
        val reply = send(provider, profile) { raw(provider, path, it) }
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
            when {
                success -> "Conectado. Histórico sincronizado"
                (quotaUntil[provider] ?: 0L) > System.currentTimeMillis() -> "O limite diário do WeTrakr foi atingido. Volta a sincronizar sozinho"
                (cooldown[provider] ?: 0L) > System.currentTimeMillis() -> "Limite do serviço atingido. Tente mais tarde"
                else -> "A sincronização falhou. Verifique a conexão e tente novamente"
            })) }
    }
}
