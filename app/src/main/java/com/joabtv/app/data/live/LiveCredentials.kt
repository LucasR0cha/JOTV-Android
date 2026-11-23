package com.joabtv.app.data.live

/**
 * Credenciais que muita lista M3U e muito guia levam no próprio endereço, na consulta (get.php?username=...&password=...) ou antes
 * do arroba. O backup sem segredos tira isso, para o arquivo poder ser compartilhado.
 */
object LiveCredentials {
    private val PARAMS = setOf(
        "user", "username", "login", "u", "pass", "password", "passwd", "pswd", "pwd", "pw", "p",
        "token", "key", "apikey", "api_key", "auth", "access_token", "secret", "uid", "sid", "session", "sig", "signature",
    )
    private val USER_INFO = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*://)[^/?#@]*@")

    /** O endereço sem usuário, senha e chave. Endereço sem nada disso volta igual. */
    fun strip(url: String): String {
        if (url.isEmpty()) return url
        val bare = url.replace(USER_INFO, "\$1")
        val q = bare.indexOf('?')
        if (q < 0) return bare
        val head = bare.substring(0, q)
        var tail = bare.substring(q + 1)
        var hash = ""
        val h = tail.indexOf('#')
        if (h >= 0) {
            hash = tail.substring(h)
            tail = tail.substring(0, h)
        }
        val kept = tail.split('&').filter { part ->
            val raw = part.substringBefore('=')
            val name = runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
            part.isNotEmpty() && name.lowercase() !in PARAMS
        }
        return head + (if (kept.isEmpty()) "" else "?" + kept.joinToString("&")) + hash
    }
}
