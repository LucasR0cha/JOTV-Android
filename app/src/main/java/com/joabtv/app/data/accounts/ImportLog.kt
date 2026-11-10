package com.joabtv.app.data.accounts

import org.json.JSONObject

/** Uma parte do que uma conta traz. A ordem do enum é a ordem em que as partes rodam. */
enum class ImportPart(
    val key: String,
    /** Nome da parte no painel Mais. */
    val label: String,
    /** Linha do passo Trazer tudo. */
    val line: String,
    /** Entra em "Trazendo X" e "Trazer X". */
    val what: String,
    /** Entra em "mas as coleções não vieram". */
    val the: String,
    val many: Boolean,
    val miss: String,
) {
    ADDONS("addons", "Addons", "Addons e as fileiras deles", "addons", "os addons", true, "Addons não vieram"),
    LIBRARY("library", "Minha Lista e progresso", "Minha Lista e o que você estava assistindo", "Minha Lista", "a Minha Lista", false, "Minha Lista não veio"),
    COLLECTIONS("collections", "Coleções", "Coleções", "coleções", "as coleções", true, "Coleções não vieram"),
    DEBRID("debrid", "Chaves de debrid", "Chaves de debrid", "chaves de debrid", "o debrid", false, "Debrid não veio");

    companion object {
        fun of(key: String): ImportPart? = entries.firstOrNull { it.key == key }
    }
}

/** Conta de outro serviço de onde o JOTV traz coisas. */
enum class Account(
    val key: String,
    val label: String,
    /** Letra do selo no cartão. */
    val mark: String,
    /** Endereço que a pessoa abre no celular para confirmar o código. */
    val site: String,
    /** Texto do cartão sem conta. */
    val hint: String,
    val parts: List<ImportPart>,
) {
    NUVIO("nuvio", "Nuvio", "N", "nuvio.tv/link", "Traz addons, Minha Lista, progresso, coleções e debrid", ImportPart.entries),
    STREMIO("stremio", "Stremio", "S", "link.stremio.com", "Traz addons, Minha Lista e progresso", listOf(ImportPart.ADDONS, ImportPart.LIBRARY));

    companion object {
        fun of(key: String): Account? = entries.firstOrNull { it.key == key }
    }
}

/** O que uma parte trouxe. Campo que a parte não usa fica zerado. [why] só vem quando [ok] é falso. */
data class PartRecord(
    val ok: Boolean,
    val at: Long = 0L,
    /** Addons novos ou coleções trazidas. */
    val n: Int = 0,
    /** Addons que já estavam. */
    val skip: Int = 0,
    /** Addons que não responderam. */
    val failed: Int = 0,
    /** Addons que só rodam no outro app. */
    val unusable: Int = 0,
    /** Fileiras novas que os addons trouxeram para a Início e quantas entraram ligadas. */
    val rows: Int = 0,
    val rowsOn: Int = 0,
    /** Títulos da Minha Lista, do Continuar assistindo e os que não acharam par no TMDB. */
    val list: Int = 0,
    val progress: Int = 0,
    val nomatch: Int = 0,
    /** Debrid conectado, "Real-Debrid, TorBox". Vazio quando a conta não tem chave. */
    val label: String = "",
    val why: String = "",
)

/** Último registro de uma conta neste perfil. [profile] é o perfil do Nuvio de onde veio. */
data class ImportEntry(val at: Long, val email: String, val profile: String, val parts: Map<String, PartRecord>) {
    fun part(p: ImportPart): PartRecord? = parts[p.key]

    /** Partes que falharam, na ordem em que rodam. */
    val failedParts: List<ImportPart> get() = ImportPart.entries.filter { parts[it.key]?.ok == false }
}

/** Perfil do Nuvio escolhido para trazer as coisas. */
data class NuvioPick(val index: Int, val name: String, val primaryAddons: Boolean = false)

/**
 * Registro do que veio de cada conta, um por perfil do JOTV (chave import_log_v1 nas preferências).
 * Cada parte entra quando termina, então uma falha adiante não apaga o que já veio.
 * Guarda por conta (nuvio e stremio) a hora, o e-mail, o perfil do Nuvio e o resultado de cada parte, mais o perfil do Nuvio escolhido.
 */
data class ImportLog(val entries: Map<String, ImportEntry> = emptyMap(), val pick: NuvioPick? = null) {

    operator fun get(a: Account): ImportEntry? = entries[a.key]?.takeIf { it.parts.isNotEmpty() }

    fun put(a: String, part: String, rec: PartRecord, email: String, profile: String, now: Long): ImportLog {
        val e = entries[a]
        val next = ImportEntry(
            at = now,
            email = email.ifBlank { e?.email.orEmpty() },
            profile = profile.ifBlank { e?.profile.orEmpty() },
            parts = (e?.parts ?: emptyMap()) + (part to rec.copy(at = now)),
        )
        return copy(entries = entries + (a to next))
    }

    fun withPick(p: NuvioPick) = copy(pick = p)

    fun encode(): String {
        val root = JSONObject()
        entries.forEach { (a, e) ->
            val parts = JSONObject()
            e.parts.forEach { (k, r) -> parts.put(k, r.toJson()) }
            root.put(a, JSONObject().put("at", e.at).put("email", e.email).put("nuvioProfile", e.profile).put("parts", parts))
        }
        pick?.let { root.put("nuvioPick", JSONObject().put("index", it.index).put("name", it.name).put("primaryAddons", it.primaryAddons)) }
        return root.toString()
    }

    companion object {
        fun decode(raw: String?): ImportLog {
            if (raw.isNullOrBlank()) return ImportLog()
            return runCatching {
                val root = JSONObject(raw)
                val entries = LinkedHashMap<String, ImportEntry>()
                Account.entries.forEach { a ->
                    val o = root.optJSONObject(a.key) ?: return@forEach
                    val p = o.optJSONObject("parts") ?: return@forEach
                    val parts = LinkedHashMap<String, PartRecord>()
                    p.keys().forEach { k -> p.optJSONObject(k)?.let { parts[k] = recordOf(it) } }
                    if (parts.isNotEmpty()) entries[a.key] = ImportEntry(o.optLong("at"), o.optString("email"), o.optString("nuvioProfile"), parts)
                }
                val pick = root.optJSONObject("nuvioPick")?.let { NuvioPick(it.optInt("index", 1), it.optString("name", "Principal"), it.optBoolean("primaryAddons")) }
                ImportLog(entries, pick)
            }.getOrDefault(ImportLog())
        }

        private fun recordOf(o: JSONObject) = PartRecord(
            ok = o.optBoolean("ok"), at = o.optLong("at"), n = o.optInt("n"), skip = o.optInt("skip"),
            failed = o.optInt("failed"), unusable = o.optInt("unusable"), rows = o.optInt("rows"), rowsOn = o.optInt("rowsOn"),
            list = o.optInt("list"), progress = o.optInt("progress"), nomatch = o.optInt("nomatch"),
            label = o.optString("label"), why = o.optString("why"),
        )
    }
}

/** Só grava o que a parte usa, o registro fica pequeno. */
private fun PartRecord.toJson(): JSONObject {
    val o = JSONObject().put("ok", ok).put("at", at)
    if (n != 0) o.put("n", n)
    if (skip != 0) o.put("skip", skip)
    if (failed != 0) o.put("failed", failed)
    if (unusable != 0) o.put("unusable", unusable)
    if (rows != 0) o.put("rows", rows)
    if (rowsOn != 0) o.put("rowsOn", rowsOn)
    if (list != 0) o.put("list", list)
    if (progress != 0) o.put("progress", progress)
    if (nomatch != 0) o.put("nomatch", nomatch)
    if (label.isNotEmpty()) o.put("label", label)
    if (why.isNotEmpty()) o.put("why", why)
    return o
}
