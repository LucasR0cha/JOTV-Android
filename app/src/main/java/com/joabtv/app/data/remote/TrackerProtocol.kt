package com.joabtv.app.data.remote

import org.json.JSONArray
import org.json.JSONObject

enum class TrackerProvider(val label: String, val baseUrl: String, val needsClientId: Boolean = false) {
    SIMKL("Simkl", "https://api.simkl.com", true),
    WETRAKR("WeTrakr", "https://api.wetrakr.com", true),
    PUBLICMETA("PublicMetaDB", "https://publicmetadb.com"),
    MDBLIST("MDBList", "https://api.mdblist.com", true),
}

data class TrackerStatus(val connected: Boolean = false, val busy: Boolean = false, val message: String = "Desconectado")
/** [oauth] diz que o token é de login por código (vai como Bearer). Sem ele o MDBList usa a chave da API. */
internal data class TrackerCredentials(val token: String, val clientId: String = "", val refresh: String = "", val expiresAt: Long = 0, val oauth: Boolean = false)
internal data class TrackerRequest(val path: String, val body: JSONObject)
internal data class TrackerForm(val path: String, val fields: Map<String, String>)

/** Só o MDBList conectado pela chave da API manda `apikey` na URL. Todo o resto vai como Bearer. */
internal fun trackerUsesApiKey(provider: TrackerProvider, credentials: TrackerCredentials) =
    provider == TrackerProvider.MDBLIST && !credentials.oauth

// Sessão salva é OAuth quando tem o marcador ou um refresh (chave da API nunca tem refresh).
internal fun trackerSessionIsOAuth(saved: JSONObject?) =
    saved != null && (saved.optBoolean("oauth") || saved.optString("refresh_token").isNotBlank())

private const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"

/** Pedidos OAuth por formulário (Simkl e MDBList). O WeTrakr usa JSON e devolve null. */
internal fun trackerDeviceForm(provider: TrackerProvider, clientId: String): TrackerForm? = when (provider) {
    TrackerProvider.SIMKL -> TrackerForm("/oauth2/device", mapOf("client_id" to clientId, "scope" to "media:read media:write"))
    TrackerProvider.MDBLIST -> TrackerForm("/oauth/device-authorization/", mapOf("client_id" to clientId, "scope" to "write"))
    else -> null
}

internal fun trackerPollForm(provider: TrackerProvider, clientId: String, device: String): TrackerForm? = when (provider) {
    TrackerProvider.SIMKL, TrackerProvider.MDBLIST -> TrackerForm(if (provider == TrackerProvider.SIMKL) "/oauth2/token" else "/oauth/token/",
        mapOf("grant_type" to DEVICE_GRANT, "client_id" to clientId, "device_code" to device))
    else -> null
}

internal fun trackerRefreshForm(provider: TrackerProvider, clientId: String, refresh: String): TrackerForm? = when (provider) {
    TrackerProvider.SIMKL, TrackerProvider.MDBLIST -> TrackerForm(if (provider == TrackerProvider.SIMKL) "/oauth2/token" else "/oauth/token/",
        mapOf("grant_type" to "refresh_token", "client_id" to clientId, "refresh_token" to refresh))
    else -> null
}

/** Renova com folga. O MDBList dura 30 dias e renova 1 dia antes. Os outros mantêm 1 minuto. */
internal fun trackerNeedsRefresh(provider: TrackerProvider, credentials: TrackerCredentials, now: Long): Boolean =
    credentials.refresh.isNotBlank() && credentials.expiresAt <= now + if (provider == TrackerProvider.MDBLIST) 86_400_000L else 60_000L

internal sealed class DevicePoll {
    object Token : DevicePoll()
    object Pending : DevicePoll()
    object SlowDown : DevicePoll()
    data class Fail(val message: String) : DevicePoll()
}

/** Lê o JSON antes do status. O MDBList responde 400 enquanto espera a aprovação. */
internal fun trackerDevicePoll(code: Int, data: JSONObject): DevicePoll = when {
    code in 200..299 && data.has("access_token") -> DevicePoll.Token
    else -> when (data.optString("error")) {
        "authorization_pending" -> DevicePoll.Pending
        "slow_down" -> DevicePoll.SlowDown
        "access_denied" -> DevicePoll.Fail("O acesso foi recusado no serviço. Gere outro código para tentar de novo")
        "expired_token" -> DevicePoll.Fail("O código expirou. Gere outro código")
        else -> DevicePoll.Fail(if (code == 429) "Limite do serviço atingido. Tente mais tarde" else "O código não foi autorizado. Gere outro código")
    }
}

/** Espera pedida pelo serviço depois de um 429, em ms. Sem cabeçalho legível vale o teto de 60 s. */
internal fun trackerRetryAfterMs(header: String?): Long = (header?.trim()?.toLongOrNull() ?: 60L).coerceIn(0L, 60L) * 1000

internal fun trackerScrobbleBody(provider: TrackerProvider, tmdbId: Int, series: Boolean, season: Int, episode: Int, progress: Double,
                                 appVersion: String = com.joabtv.app.BuildConfig.VERSION_NAME): JSONObject {
    val item = JSONObject().put("ids", JSONObject().put("tmdb", tmdbId))
    val body = JSONObject().put("progress", progress.coerceIn(0.0, 100.0))
    // O MDBList mostra ao usuário de qual app veio o scrobble.
    if (provider == TrackerProvider.MDBLIST) body.put("app_name", "JOTV").put("app_version", appVersion)
    if (series) {
        if (provider == TrackerProvider.MDBLIST) item.put("season", JSONObject().put("number", season).put("episode", JSONObject().put("number", episode)))
        else body.put("episode", JSONObject().put("season", season).put("number", episode))
        if (provider == TrackerProvider.SIMKL) item.put("type", "show")
        body.put("show", item)
    } else body.put("movie", item)
    return body
}

internal fun trackerWatchlistRequest(provider: TrackerProvider, tmdbId: Int, series: Boolean, added: Boolean): TrackerRequest? {
    val item = JSONObject().put("ids", JSONObject().put("tmdb", tmdbId))
    val key = if (series) "shows" else "movies"
    val path = when (provider) {
        TrackerProvider.SIMKL -> {
            // Retirar de Minha Lista não pode apagar os episódios vistos no Simkl.
            if (!added) return null
            item.put("to", "plantowatch")
            "/sync/add-to-list"
        }
        TrackerProvider.WETRAKR -> {
            item.put("status", "planning")
            if (added) "/sync/tracking" else "/sync/tracking/remove"
        }
        TrackerProvider.MDBLIST -> "/watchlist/items/${if (added) "add" else "remove"}"
        TrackerProvider.PUBLICMETA -> return null
    }
    return TrackerRequest(path, JSONObject().put(key, JSONArray().put(item)))
}
