package com.joabtv.app.data.backup

import org.json.JSONObject

internal fun portableTraktSession(raw: String): JSONObject? {
    val data = runCatching { JSONObject(raw) }.getOrNull() ?: return null
    val token = data.optString("access_token").ifBlank { data.optString("a") }
    if (token.isBlank()) return null
    return JSONObject().put("access_token", token)
        .put("refresh_token", data.optString("refresh_token").ifBlank { data.optString("r") })
        .put("expires_at", data.optLong("expires_at", data.optLong("e")))
        .put("user", data.optString("user").ifBlank { data.optString("u") })
}

internal fun androidTraktSession(data: JSONObject): String? {
    val portable = portableTraktSession(data.toString()) ?: return null
    return JSONObject().put("a", portable.getString("access_token")).put("r", portable.optString("refresh_token"))
        .put("e", portable.optLong("expires_at")).put("u", portable.optString("user")).toString()
}

internal fun portableTrackers(tokens: Map<String, String>, sessions: String): JSONObject {
    val metadata = runCatching { JSONObject(sessions) }.getOrElse { JSONObject() }
    val names = mapOf("simkl" to "SIMKL", "wetrakr" to "WETRAKR", "mdblist" to "MDBLIST", "publicmetadb" to "PUBLICMETA")
    return JSONObject().apply {
        tokens.filterValues { it.isNotBlank() }.forEach { (id, token) ->
            val saved = metadata.optJSONObject(names[id].orEmpty())
            // MDBList por código sai como "token" com auth oauth, para ninguém ler como chave da API.
            val oauth = com.joabtv.app.data.remote.trackerSessionIsOAuth(saved)
            put(id, JSONObject().put(if (id == "simkl" || id == "wetrakr" || oauth) "token" else "key", token)
                .put("client", saved?.optString("client_id").orEmpty())
                .put("refresh", saved?.optString("refresh_token").orEmpty()).put("expires", saved?.optLong("expires_at") ?: 0)
                .apply { if (oauth) put("auth", "oauth") })
        }
    }
}

internal fun backupTrackerToken(data: JSONObject?): String = data?.optString("token").orEmpty()
    .ifBlank { data?.optString("key").orEmpty() }.ifBlank { data?.optString("apiKey").orEmpty() }

internal fun androidTrackerSessions(data: JSONObject, previous: String): String {
    val sessions = runCatching { JSONObject(previous) }.getOrElse { JSONObject() }
    mapOf("simkl" to "SIMKL", "wetrakr" to "WETRAKR", "mdblist" to "MDBLIST", "publicmetadb" to "PUBLICMETA").forEach { (id, name) ->
        data.optJSONObject(id)?.let { item ->
            if (backupTrackerToken(item).isNotBlank()) sessions.put(name, JSONObject().put("client_id", item.optString("client"))
                .put("refresh_token", item.optString("refresh")).put("expires_at", item.optLong("expires"))
                .put("oauth", item.optString("auth") == "oauth" || item.optString("refresh").isNotBlank()))
        }
    }
    return sessions.toString()
}
