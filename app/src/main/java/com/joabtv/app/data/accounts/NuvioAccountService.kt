package com.joabtv.app.data.accounts

import android.content.Context
import com.joabtv.app.data.sync.PushRow
import com.joabtv.app.data.sync.PushSeen
import com.joabtv.app.data.sync.RemoteRow
import com.joabtv.app.data.sync.RemoteSeen
import com.joabtv.app.data.sync.SyncPlan
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Sessão da conta Nuvio. [server] é o endereço do servidor e [key] a chave pública dele. */
data class NuvioSession(
    val server: String,
    val key: String,
    val access: String,
    val refresh: String,
    val expiresAt: Long,
    val email: String,
)

/**
 * Perfil da conta Nuvio. [index] é o número que as consultas usam (1 é o principal). [avatarUrl] é o endereço da
 * foto (o próprio do perfil ou a imagem do catálogo do Nuvio) e fica vazio sem foto. [colorHex] é a cor do perfil.
 */
data class NuvioProfile(
    val index: Int, val name: String, val usesPrimaryAddons: Boolean,
    val avatarUrl: String = "", val colorHex: String = "",
    /** O perfil usa os plugins do principal (uses_primary_plugins). */
    val usesPrimaryPlugins: Boolean = false,
)

/** Repositório de plugins de um perfil da Nuvio. [url] é o manifesto do repositório. */
data class RemotePluginRepo(val url: String, val name: String, val enabled: Boolean, val sortOrder: Int)

/** Foto pronta do catálogo do Nuvio. */
data class NuvioAvatar(val id: String, val name: String, val category: String, val url: String)

/** Código para entrar pelo site do Nuvio (nuvio.tv/link). */
data class NuvioDeviceCode(val deviceCode: String, val userCode: String, val url: String, val intervalSec: Int, val nonce: String, val server: String, val key: String)

/**
 * Conta Nuvio. O servidor oficial publica o próprio endereço e a chave pública em
 * /.well-known/nuvio, então nada fica escondido no app. Só leitura dos dados da pessoa.
 */
@Singleton
class NuvioAccountService @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("joabtv_accounts", Context.MODE_PRIVATE)
    private val client = com.joabtv.app.data.remote.AppDns.mediaClient.newBuilder()
        .callTimeout(30, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build()

    private val _session = MutableStateFlow(readSession())
    val session: StateFlow<NuvioSession?> = _session.asStateFlow()

    // Hora da entrada feita depois do registro de importação (0 nas contas que já vinham de antes, que o
    // cartão não trata como pendência) e se o serviço derrubou a sessão (o cartão pede para entrar de novo).
    private val _since = MutableStateFlow(prefs.getLong("nuvio_since", 0L))
    val since: StateFlow<Long> = _since.asStateFlow()
    private val _lost = MutableStateFlow(prefs.getBoolean("nuvio_lost", false))
    val lost: StateFlow<Boolean> = _lost.asStateFlow()

    /** Endereço e chave pública do servidor. Aceita "api.nuvio.tv" ou a URL de um servidor próprio. */
    suspend fun discover(input: String = OFFICIAL): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        val raw = input.trim().ifBlank { OFFICIAL }
        val base = (if ("://" in raw) raw else "https://$raw").trimEnd('/').removeSuffix("/.well-known/nuvio")
        if (!base.startsWith("https://") && !isLocal(base)) return@withContext Result.failure(IllegalArgumentException("Use um endereço https"))
        val o = getJson("$base/.well-known/nuvio") ?: return@withContext Result.failure(IllegalStateException("Esse endereço não é um servidor Nuvio"))
        if (!o.optString("service").equals("nuvio", true)) return@withContext Result.failure(IllegalStateException("Esse endereço não é um servidor Nuvio"))
        val backend = o.optString("backend_url").trimEnd('/').ifBlank { base }
        val key = o.optString("publishable_key")
        if (key.isBlank() || !o.optJSONObject("capabilities")?.optBoolean("email_password_auth", false).let { it == true }) {
            return@withContext Result.failure(IllegalStateException("Esse servidor Nuvio não aceita login por e-mail"))
        }
        Result.success(backend to key)
    }

    suspend fun login(email: String, password: String, server: String = OFFICIAL): Result<NuvioSession> = withContext(Dispatchers.IO) {
        val (backend, key) = discover(server).getOrElse { return@withContext Result.failure(it) }
        val body = JSONObject().put("email", email.trim()).put("password", password)
        val r = post("$backend/auth/v1/token?grant_type=password", body, key, null)
        val o = r.second ?: return@withContext Result.failure(IllegalStateException("Sem resposta do Nuvio"))
        val access = o.optString("access_token")
        if (r.first !in 200..299 || access.isBlank()) {
            val msg = o.optString("error_description").ifBlank { o.optString("msg").ifBlank { o.optString("message") } }
            return@withContext Result.failure(IllegalStateException(if (msg.contains("invalid", true)) "E-mail ou senha não conferem" else msg.ifBlank { "O Nuvio recusou o login" }))
        }
        val s = sessionOf(backend, key, o, email.trim())
        save(s, entered = true)
        Result.success(s)
    }

    suspend fun deviceStart(server: String = OFFICIAL): Result<NuvioDeviceCode> = withContext(Dispatchers.IO) {
        val (backend, key) = discover(server).getOrElse { return@withContext Result.failure(it) }
        val nonce = java.util.UUID.randomUUID().toString()
        val redirect = if (backend.contains("nuvio.tv", true)) "https://nuvio.tv/link" else "$backend/link"
        val params = JSONObject().put("p_device_nonce", nonce).put("p_redirect_base_url", redirect)
            .put("p_device_name", "JOTV").put("p_device_type", "mobile")
        val r = post("$backend/rest/v1/rpc/start_device_login_session", params, key, null)
        val row = firstRow(r.second, r.third) ?: return@withContext Result.failure(IllegalStateException("O Nuvio não deu o código agora"))
        val code = row.optString("device_code").ifBlank { return@withContext Result.failure(IllegalStateException("O Nuvio não deu o código agora")) }
        Result.success(
            NuvioDeviceCode(
                code, row.optString("user_code"), row.optString("verification_uri_complete").ifBlank { redirect },
                row.optInt("poll_interval_seconds", 3).coerceIn(2, 10), nonce, backend, key,
            )
        )
    }

    /** Uma consulta. null enquanto pendente. Falha quando venceu ou foi recusado. */
    suspend fun devicePoll(d: NuvioDeviceCode): Result<NuvioSession?> = withContext(Dispatchers.IO) {
        val r = post("${d.server}/rest/v1/rpc/poll_tv_login_session", JSONObject().put("p_code", d.deviceCode).put("p_device_nonce", d.nonce), d.key, null)
        val row = firstRow(r.second, r.third) ?: return@withContext Result.success(null)
        when (row.optString("status")) {
            "pending" -> return@withContext Result.success(null)
            "approved" -> Unit
            else -> return@withContext Result.failure(IllegalStateException("O código venceu. Peça outro."))
        }
        val ex = post("${d.server}/functions/v1/tv-logins-exchange", JSONObject().put("code", d.deviceCode).put("device_nonce", d.nonce), d.key, null)
        val o = ex.second ?: return@withContext Result.failure(IllegalStateException("O Nuvio não entregou a sessão"))
        if (o.optString("access_token").isBlank()) return@withContext Result.failure(IllegalStateException("O Nuvio não entregou a sessão"))
        val email = o.optJSONObject("user")?.optString("email")
            ?: getJson("${d.server}/auth/v1/user", d.key, o.optString("access_token"))?.optString("email").orEmpty()
        val s = sessionOf(d.server, d.key, o, email)
        save(s, entered = true)
        Result.success(s)
    }

    fun logout() {
        prefs.edit().remove("nuvio_session").remove("nuvio_since").remove("nuvio_lost").apply()
        _since.value = 0L
        _lost.value = false
        _session.value = null
    }

    /** O Nuvio recusou a renovação. Sai daqui, mas guarda que foi o serviço, para pedir login de novo. */
    private fun expire() {
        prefs.edit().remove("nuvio_session").putBoolean("nuvio_lost", true).apply()
        _lost.value = true
        _session.value = null
    }

    suspend fun profiles(): Result<List<NuvioProfile>> {
        val rows = rpcRows("sync_pull_profiles", JSONObject()).getOrElse { return Result.failure(it) }
        // O catálogo só é buscado quando algum perfil usa foto pronta (avatar_id) em vez de endereço próprio.
        val needCatalog = rows.any { it.text("avatar_url").isBlank() && it.text("avatar_id").isNotBlank() }
        val catalog = if (needCatalog) avatarCatalog().getOrNull().orEmpty().associateBy { it.id } else emptyMap()
        return Result.success(
            rows.map { o ->
                val own = validPhotoUrl(o.text("avatar_url"))
                profileFromRow(o, own ?: catalog[o.text("avatar_id")]?.url.orEmpty())
            }.sortedBy { it.index },
        )
    }

    /** Fotos prontas do Nuvio (get_avatar_catalog), na ordem do catálogo. */
    suspend fun avatarCatalog(): Result<List<NuvioAvatar>> {
        val server = _session.value?.server ?: return Result.failure(IllegalStateException("Entre na conta Nuvio"))
        return rpcRows("get_avatar_catalog", JSONObject()).map { rows ->
            rows.filter { it.optBoolean("is_active", true) }
                .sortedWith(compareBy<JSONObject>({ it.text("category") }, { it.optInt("sort_order") }))
                .mapNotNull { o ->
                    val path = o.text("storage_path").ifBlank { return@mapNotNull null }
                    val url = validPhotoUrl(if (path.startsWith("http")) path else "$server/storage/v1/object/public/avatars/$path")
                        ?: return@mapNotNull null
                    NuvioAvatar(o.text("id"), o.text("display_name").ifBlank { o.text("id") }, o.text("category"), url)
                }
        }
    }

    /** Addons do perfil. Perfil que usa os addons do principal lê os do perfil 1, como no Nuvio. */
    suspend fun addons(profile: NuvioProfile): Result<List<RemoteAddon>> = withContext(Dispatchers.IO) {
        val idx = if (profile.index != 1 && profile.usesPrimaryAddons) 1 else profile.index
        val s = fresh() ?: return@withContext Result.failure(IllegalStateException("Entre na conta Nuvio"))
        val r = getRaw("${s.server}/rest/v1/addons?select=*&profile_id=eq.$idx&order=sort_order.asc", s.key, s.access)
        val arr = runCatching { JSONArray(r.second) }.getOrNull()
            ?: return@withContext Result.failure(IllegalStateException(errorOf(r.second, r.first)))
        Result.success(
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                if (!o.optBoolean("enabled", true)) return@mapNotNull null
                val raw = o.optString("url").trim().ifBlank { return@mapNotNull null }
                val url = if (raw.endsWith("/manifest.json")) raw else raw.trimEnd('/') + "/manifest.json"
                val host = Regex("^https?://([^/:]+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.lowercase().orEmpty()
                RemoteAddon(url, o.optString("name").ifBlank { host }, url.startsWith("http") && host.isNotBlank() && host != "localhost" && !host.startsWith("127."))
            }.distinctBy { it.url }
        )
    }

    /**
     * Repositórios de plugins do perfil, na ordem da Nuvio (o mesmo que o PluginRepository.pullFromServer de lá lê).
     * Perfil que usa os plugins do principal lê os do perfil 1.
     */
    suspend fun plugins(profile: NuvioProfile): Result<List<RemotePluginRepo>> = withContext(Dispatchers.IO) {
        val idx = if (profile.index != 1 && profile.usesPrimaryPlugins) 1 else profile.index
        val s = fresh() ?: return@withContext Result.failure(IllegalStateException("Entre na conta Nuvio"))
        val r = getRaw("${s.server}/rest/v1/plugins?select=*&profile_id=eq.$idx&order=sort_order.asc", s.key, s.access)
        parsePluginRows(r.second)?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException(errorOf(r.second, r.first)))
    }

    suspend fun library(profile: NuvioProfile): Result<List<RemoteLibraryItem>> {
        val out = mutableListOf<RemoteLibraryItem>()
        var offset = 0
        while (offset < 5_000) {
            val rows = rpcRows("sync_pull_library", JSONObject().put("p_profile_id", profile.index).put("p_limit", 500).put("p_offset", offset))
                .getOrElse { return Result.failure(it) }
            rows.forEach { o ->
                val type = o.optString("content_type")
                if (type != "movie" && type != "series") return@forEach
                out += RemoteLibraryItem(o.optString("content_id"), type == "series", o.optString("name"), o.optString("poster"), o.optLong("added_at"))
            }
            if (rows.size < 500) break
            offset += 500
        }
        return Result.success(out)
    }

    suspend fun progress(profile: NuvioProfile): Result<List<RemoteProgress>> =
        rpcRows("sync_pull_watch_progress", JSONObject().put("p_profile_id", profile.index)).map { rows ->
            rows.mapNotNull { o ->
                val type = o.optString("content_type")
                if (type != "movie" && type != "series") return@mapNotNull null
                RemoteProgress(
                    o.optString("content_id"), type == "series", "", "",
                    o.optInt("season", 0), o.optInt("episode", 0),
                    o.optLong("position"), o.optLong("duration"), o.optLong("last_watched"),
                )
            }
        }

    /** Chaves de debrid salvas na conta ("torbox" → chave). */
    suspend fun debridKeys(profile: NuvioProfile): Result<Map<String, String>> =
        rpcRows("sync_pull_provider_credentials", JSONObject().put("p_profile_id", profile.index)).map { rows ->
            rows.mapNotNull { o ->
                val p = o.optString("provider")
                val key = o.optJSONObject("credential_json")?.optString("api_key").orEmpty()
                if (p.startsWith("debrid:") && key.isNotBlank()) p.removePrefix("debrid:") to key else null
            }.toMap()
        }

    /** Coleções do perfil (o mesmo collections_json que o Nuvio sincroniza). Null quando não tem. */
    suspend fun collectionsJson(profile: NuvioProfile): Result<String?> =
        rpcRows("sync_pull_collections", JSONObject().put("p_profile_id", profile.index)).map { rows ->
            rows.firstOrNull()?.opt("collections_json")?.takeIf { it != JSONObject.NULL }?.toString()?.takeIf { it.isNotBlank() && it != "[]" }
        }

    /** Perfil principal da conta, para quem importa sem escolher perfil (tela de Coleções). */
    suspend fun mainProfile(): NuvioProfile =
        profiles().getOrNull()?.minByOrNull { it.index } ?: NuvioProfile(1, "Perfil", false)

    // ── Histórico (sync nos dois sentidos) ──────────────────────────────────────

    /** Linhas de progresso da conta com chave e episódio, no formato comum do sync. */
    suspend fun progressRows(profile: NuvioProfile): Result<List<RemoteRow>> =
        rpcRows("sync_pull_watch_progress", JSONObject().put("p_profile_id", profile.index), array = true).map { rows ->
            rows.mapNotNull { o ->
                val type = o.text("content_type")
                if (type != "movie" && type != "series") return@mapNotNull null
                val id = o.text("content_id").ifBlank { return@mapNotNull null }
                val series = type == "series"
                val season = if (series) o.optInt("season", 0) else 0
                val episode = if (series) o.optInt("episode", 0) else 0
                RemoteRow(
                    id, series, season, episode, o.optLong("position"), o.optLong("duration"), o.optLong("last_watched"),
                    o.text("progress_key").ifBlank { SyncPlan.progressKey(id, season, episode) },
                )
            }
        }

    /** Itens vistos da conta (páginas de 500). O booleano diz se leu tudo (falso se passou do teto de páginas). */
    suspend fun watchedRows(profile: NuvioProfile): Result<Pair<List<RemoteSeen>, Boolean>> {
        val out = ArrayList<RemoteSeen>()
        var page = 1
        while (page <= 40) {
            val rows = rpcRows(
                "sync_pull_watched_items",
                JSONObject().put("p_profile_id", profile.index).put("p_page", page).put("p_page_size", 500),
                array = true,
            ).getOrElse { return Result.failure(it) }
            rows.forEach { o ->
                val type = o.text("content_type")
                val id = o.text("content_id")
                if ((type != "movie" && type != "series") || id.isBlank()) return@forEach
                val series = type == "series"
                out += RemoteSeen(
                    id, series, o.text("title"), if (series) o.optInt("season", 0) else 0,
                    if (series) o.optInt("episode", 0) else 0, normalizeWatchedAt(o.optLong("watched_at")),
                )
            }
            if (rows.size < 500) return Result.success(out to true)
            page++
        }
        return Result.success(out to false)
    }

    /**
     * Manda linhas de progresso em lotes. O resultado diz se o servidor aceitou o p_origin_client_id (falso quando
     * a função só existe sem ele, e então a chamada é refeita sem o parâmetro).
     */
    suspend fun pushProgress(profile: NuvioProfile, rows: List<PushRow>, origin: String?): Result<Boolean> =
        batched(rows, origin) { chunk, o -> "sync_push_watch_progress" to SyncPlan.progressBody(profile.index, chunk, o) }

    suspend fun pushWatched(profile: NuvioProfile, items: List<PushSeen>, origin: String?): Result<Boolean> =
        batched(items, origin) { chunk, o -> "sync_push_watched_items" to SyncPlan.watchedBody(profile.index, chunk, o) }

    suspend fun deleteProgress(profile: NuvioProfile, keys: List<String>, origin: String?): Result<Boolean> =
        batched(keys, origin) { chunk, o -> "sync_delete_watch_progress" to SyncPlan.deleteProgressBody(profile.index, chunk, o) }

    suspend fun deleteWatched(profile: NuvioProfile, keys: List<Triple<String, Int, Int>>, origin: String?): Result<Boolean> =
        batched(keys, origin) { chunk, o -> "sync_delete_watched_items" to SyncPlan.deleteWatchedBody(profile.index, chunk, o) }

    private suspend fun <T> batched(
        items: List<T>, origin: String?, build: (List<T>, String?) -> Pair<String, JSONObject>,
    ): Result<Boolean> {
        var useOrigin = origin
        var accepted = origin != null
        for (chunk in items.chunked(SyncPlan.BATCH)) {
            val (name, body) = build(chunk, useOrigin)
            val r = rpcRaw(name, body).getOrElse { return Result.failure(it) }
            if (r.code in 200..299) continue
            // O PostgREST casa a função pelo conjunto de nomes de parâmetros. Sem o p_origin_client_id é outra assinatura.
            if (useOrigin != null && functionMissing(r)) {
                useOrigin = null
                accepted = false
                val (n2, b2) = build(chunk, null)
                val r2 = rpcRaw(n2, b2).getOrElse { return Result.failure(it) }
                if (r2.code in 200..299) continue
                return Result.failure(IllegalStateException(errorOf(r2.text, r2.code)))
            }
            return Result.failure(IllegalStateException(errorOf(r.text, r.code)))
        }
        return Result.success(accepted)
    }

    private fun functionMissing(r: Raw) =
        r.code == 404 || r.text.contains("PGRST202") || r.text.contains("Could not find the function", true)

    /** O Nuvio às vezes grava a hora como número compacto AAAAMMDDhhmmss em vez de milissegundos. */
    private fun normalizeWatchedAt(v: Long): Long {
        if (v !in 20_000_101_000_000L..99_991_231_235_959L) return v
        val s = v.toString()
        return runCatching {
            java.time.LocalDateTime.of(
                s.substring(0, 4).toInt(), s.substring(4, 6).toInt(), s.substring(6, 8).toInt(),
                s.substring(8, 10).toInt(), s.substring(10, 12).toInt(), s.substring(12, 14).toInt(),
            ).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        }.getOrDefault(v)
    }

    // ── HTTP ────────────────────────────────────────────────────────────────────

    /** Sessão válida, renovada quando falta pouco para vencer. null sem login ou sem renovação. */
    private fun fresh(): NuvioSession? {
        val s = _session.value ?: return null
        if (s.expiresAt - System.currentTimeMillis() > 60_000) return s
        val r = post("${s.server}/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", s.refresh), s.key, null)
        val o = r.second
        if (r.first !in 200..299 || o == null || o.optString("access_token").isBlank()) {
            if (r.first in 400..499) expire()
            return null
        }
        return sessionOf(s.server, s.key, o, s.email).also { save(it) }
    }

    /** Resposta crua de uma chamada. [retryAfter] é o cabeçalho Retry-After, quando o servidor manda. */
    private class Raw(val code: Int, val text: String, val retryAfter: String?)

    private fun postRaw(url: String, body: JSONObject, key: String, bearer: String?): Raw = runCatching {
        val req = Request.Builder().url(url)
            .header("apikey", key).header("Authorization", "Bearer ${bearer ?: key}").header("User-Agent", "JOTV")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(req).execute().use { Raw(it.code, it.body?.string().orEmpty(), it.header("Retry-After")) }
    }.getOrElse { Raw(-1, "", null) }

    /**
     * Chamada de função do servidor com renovação de sessão (401) e uma espera educada quando o servidor
     * pede calma (429 ou 503 com Retry-After, no máximo 30 s). Quem chama decide o que fazer com o código.
     */
    private suspend fun rpcRaw(name: String, params: JSONObject): Result<Raw> = withContext(Dispatchers.IO) {
        val s = fresh() ?: return@withContext Result.failure(IllegalStateException("Entre na conta Nuvio"))
        var r = postRaw("${s.server}/rest/v1/rpc/$name", params, s.key, s.access)
        if (r.code == 401) {
            // Token recusado antes da hora. Força a renovação uma vez.
            _session.value = s.copy(expiresAt = 0)
            val again = fresh() ?: return@withContext Result.failure(IllegalStateException("A sessão do Nuvio acabou. Entre de novo."))
            r = postRaw("${again.server}/rest/v1/rpc/$name", params, again.key, again.access)
        }
        if (r.code == 429 || r.code == 503) {
            val wait = ((r.retryAfter?.trim()?.toLongOrNull() ?: 1L) * 1000L).coerceIn(500L, 30_000L)
            kotlinx.coroutines.delay(wait)
            val cur = _session.value ?: return@withContext Result.failure(IllegalStateException("A sessão do Nuvio acabou. Entre de novo."))
            r = postRaw("${cur.server}/rest/v1/rpc/$name", params, cur.key, cur.access)
        }
        Result.success(r)
    }

    /**
     * Linhas de uma função do servidor. Com [array] o corpo tem que ser uma lista JSON, senão é falha. Sem isso,
     * página de Wi-Fi com login (200 com HTML) ou corpo vazio viraria "lista vazia" e o sync acharia que tudo foi removido.
     */
    private suspend fun rpcRows(name: String, params: JSONObject, array: Boolean = false): Result<List<JSONObject>> {
        val r = rpcRaw(name, params).getOrElse { return Result.failure(it) }
        if (r.code !in 200..299) return Result.failure(IllegalStateException(errorOf(r.text, r.code)))
        val arr = runCatching { JSONArray(r.text) }.getOrElse {
            if (array) return Result.failure(IllegalStateException("Resposta inesperada do servidor"))
            runCatching { JSONArray().put(JSONObject(r.text)) }.getOrDefault(JSONArray())
        }
        return Result.success((0 until arr.length()).mapNotNull { arr.optJSONObject(it) })
    }

    /** Texto de um campo, vazio quando falta ou é nulo (o org.json devolve "null" como texto nesse caso). */
    private fun JSONObject.text(name: String): String = if (isNull(name)) "" else optString(name)

    /** Foto só por https, sem espaço e de tamanho razoável. */
    private fun validPhotoUrl(url: String): String? =
        url.trim().takeIf { it.length <= 2048 && it.startsWith("https://") && it.none(Char::isWhitespace) }

    /** POST com JSON. Devolve código, corpo como objeto (quando é objeto) e o texto cru. */
    private fun post(url: String, body: JSONObject, key: String, bearer: String?): Triple<Int, JSONObject?, String> = runCatching {
        val req = Request.Builder().url(url)
            .header("apikey", key).header("Authorization", "Bearer ${bearer ?: key}").header("User-Agent", "JOTV")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(req).execute().use {
            val text = it.body?.string().orEmpty()
            Triple(it.code, runCatching { JSONObject(text) }.getOrNull(), text)
        }
    }.getOrElse { Triple(-1, null, "") }

    private fun getRaw(url: String, key: String, bearer: String?): Pair<Int, String> = runCatching {
        val req = Request.Builder().url(url).header("apikey", key).header("Authorization", "Bearer ${bearer ?: key}").header("User-Agent", "JOTV").build()
        client.newCall(req).execute().use { it.code to it.body?.string().orEmpty() }
    }.getOrElse { -1 to "" }

    private fun getJson(url: String, key: String? = null, bearer: String? = null): JSONObject? = runCatching {
        val b = Request.Builder().url(url).header("User-Agent", "JOTV")
        if (key != null) b.header("apikey", key).header("Authorization", "Bearer ${bearer ?: key}")
        client.newCall(b.build()).execute().use { if (it.isSuccessful) JSONObject(it.body?.string().orEmpty()) else null }
    }.getOrNull()

    private fun firstRow(obj: JSONObject?, raw: String): JSONObject? =
        runCatching { JSONArray(raw).optJSONObject(0) }.getOrNull() ?: obj?.takeIf { it.has("device_code") || it.has("status") }

    private fun errorOf(raw: String, code: Int): String {
        val o = runCatching { JSONObject(raw) }.getOrNull()
        val msg = o?.optString("message")?.ifBlank { null } ?: o?.optString("msg")?.ifBlank { null }
        return when {
            code == 401 || code == 403 -> "A sessão do Nuvio acabou. Entre de novo."
            code == -1 -> "Sem conexão com o Nuvio"
            msg != null -> msg
            else -> "O Nuvio recusou o pedido ($code)"
        }
    }

    private fun sessionOf(server: String, key: String, o: JSONObject, email: String) = NuvioSession(
        server, key, o.optString("access_token"), o.optString("refresh_token"),
        System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000,
        o.optJSONObject("user")?.optString("email")?.ifBlank { null } ?: email,
    )

    /** [entered] é a entrada de verdade (e-mail ou código). A renovação do token não mexe em since nem em lost. */
    private fun save(s: NuvioSession, entered: Boolean = false) {
        val ed = prefs.edit().putString(
            "nuvio_session",
            JSONObject().put("s", s.server).put("k", s.key).put("a", s.access).put("r", s.refresh).put("x", s.expiresAt).put("e", s.email).toString(),
        )
        if (entered) {
            val now = System.currentTimeMillis()
            ed.putLong("nuvio_since", now).putBoolean("nuvio_lost", false)
            _since.value = now
            _lost.value = false
        }
        ed.apply()
        _session.value = s
    }

    private fun readSession(): NuvioSession? = runCatching {
        val o = JSONObject(prefs.getString("nuvio_session", null) ?: return null)
        NuvioSession(o.getString("s"), o.getString("k"), o.getString("a"), o.optString("r"), o.optLong("x"), o.optString("e"))
    }.getOrNull()

    private fun isLocal(url: String) = Regex("^http://(localhost|127\\.|10\\.|192\\.168\\.)").containsMatchIn(url)

    companion object {
        const val OFFICIAL = "https://api.nuvio.tv"

        /** Perfil de uma linha do sync_pull_profiles. [photo] já vem resolvida (própria ou do catálogo). */
        fun profileFromRow(o: JSONObject, photo: String): NuvioProfile {
            fun text(n: String) = if (o.isNull(n)) "" else o.optString(n)
            return NuvioProfile(
                o.optInt("profile_index", 1), text("name").ifBlank { "Perfil" }, o.optBoolean("uses_primary_addons"),
                photo, text("avatar_color_hex"), o.optBoolean("uses_primary_plugins"),
            )
        }

        /**
         * Linhas de rest/v1/plugins. null quando o corpo não é lista (erro ou página de login). Só endereço https
         * entra, sem repetir, na ordem de sort_order.
         */
        fun parsePluginRows(text: String): List<RemotePluginRepo>? {
            val arr = runCatching { JSONArray(text) }.getOrNull() ?: return null
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = (if (o.isNull("url")) "" else o.optString("url")).trim()
                if (!url.startsWith("https://") || url.any(Char::isWhitespace)) return@mapNotNull null
                RemotePluginRepo(url, if (o.isNull("name")) "" else o.optString("name"), o.optBoolean("enabled", true), o.optInt("sort_order", i))
            }.distinctBy { it.url }.sortedBy { it.sortOrder }
        }
    }
}
