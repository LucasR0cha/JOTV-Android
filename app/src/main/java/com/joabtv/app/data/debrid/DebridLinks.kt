package com.joabtv.app.data.debrid

/**
 * Link que o próprio addon manda resolver no debrid dele (Torrentio, Comet, AIOStreams e parecidos). Abrir esse
 * endereço, até para testar, faz o addon adicionar o torrent na conta e, sem cache, começar o download. Por isso ele
 * nunca entra em teste, só na hora de tocar. Cada addon monta o endereço de um jeito. O que aparece sempre é o nome
 * do serviço no caminho, o hash do torrent no caminho ou a etiqueta ([RD+], TB⚡) no nome da fonte.
 *
 * Mesmo critério do webOS, Tizen, Windows e Linux (isDebridLink em sources.js).
 */
object DebridLinks {
    private const val SVC = "RD|AD|PM|TB|DL|ED|OC|PK|PIO|DB|ST|EZ"
    private val TAG = Regex("""\[($SVC)([^\]]*)\]""")
    private val MARK = Regex("""\b($SVC)\b\s*(⚡|⏳)|(⚡|⏳)️?\s*($SVC)\b""")
    private val DOWNLOADING = Regex("""uncached|download|⬇|⏳""", RegexOption.IGNORE_CASE)
    private val READY = Regex("""\+|⚡|instant|cached""", RegexOption.IGNORE_CASE)
    private val WORD = Regex("""(?:^|[^a-z0-9])(?:real-?debrid|all-?debrid|debrid(?:-?link)?|easydebrid|torbox|premiumize|offcloud|pikpak|put\.?io|seedr)(?:[^a-z0-9]|$)""")
    private val HASH = Regex("""(?:^|[^0-9a-f])[0-9a-f]{40}(?:[^0-9a-f]|$)""")
    private val URL_PARTS = Regex("""^https?://[^/?#]+([^?#]*)(\?[^#]*)?""", RegexOption.IGNORE_CASE)

    /** O que a etiqueta do addon diz do arquivo. true = pronto, false = vai baixar, null = etiqueta sem estado. */
    private class Tag(val cached: Boolean?)

    private fun tagOf(name: String): Tag? {
        TAG.find(name)?.let { m ->
            val tail = m.groupValues[2]
            return Tag(if (DOWNLOADING.containsMatchIn(tail)) false else if (READY.containsMatchIn(tail)) true else null)
        }
        MARK.find(name)?.let { m -> return Tag(m.groupValues[2].ifEmpty { m.groupValues[3] } == "⚡") }
        if (name.contains("torbox", ignoreCase = true) && name.contains("⚡")) return Tag(true)
        return null
    }

    /** O nome da fonte traz a etiqueta de um serviço de debrid ([RD+], [TB download], RD⚡)? */
    fun tagged(name: String): Boolean = tagOf(name) != null

    /** O addon marca a fonte como pronta no debrid dele? Sem teste, é assim que ela conta como rápida. */
    fun cachedTag(name: String): Boolean = tagOf(name)?.cached == true

    /** O endereço é de resolução de debrid? Nome do serviço no caminho ou na consulta, ou hash de torrent no caminho. */
    fun urlLooksDebrid(url: String): Boolean {
        val m = URL_PARTS.find(url) ?: return false
        val rawPath = m.groupValues[1]
        val query = m.groupValues[2].lowercase()
        // "+" fica como está, só %xx vira caractere (igual ao decodeURIComponent do web).
        val path = runCatching { java.net.URLDecoder.decode(rawPath.replace("+", "%2B"), "UTF-8") }.getOrDefault(rawPath).lowercase()
        return WORD.containsMatchIn(path + query) || HASH.containsMatchIn(path)
    }

    /** A fonte é um link de resolução do debrid do addon? Pelo endereço ou pela etiqueta no nome. */
    fun isDebridLink(name: String, url: String): Boolean = tagged(name) || urlLooksDebrid(url)
}
