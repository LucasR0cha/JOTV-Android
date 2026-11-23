package com.joabtv.app.data.local

import org.json.JSONArray
import org.json.JSONObject

/**
 * Conjunto de dados que cada perfil tem (contrato de perfis independentes). As sessões da Nuvio e do Stremio não
 * entram, ficam do aparelho, porque o token de renovação gira a cada uso e uma cópia derrubaria o login dos outros.
 */
enum class ProfileCategory(val key: String) {
    ADDONS("addons"), PLUGINS("plugins"), CATALOG("catalog"), LIVE("live"), DEBRID("debrid");

    companion object {
        fun of(key: String): ProfileCategory? = entries.firstOrNull { it.key == key }
    }
}

/** REPLACE troca tudo pelo do outro perfil. MERGE só acrescenta o que falta nas listas, sem mexer na ordem do destino. */
enum class CopyMode { REPLACE, MERGE }

/** O que fazer com um perfil da Nuvio no Trazer todos. */
enum class NuvioPlanKind { REUSE, CURRENT, CREATE }

/** [profileId] vem preenchido em REUSE e CURRENT. Em CREATE o perfil ainda vai nascer. */
data class NuvioPlanStep(val nuvioIndex: Int, val kind: NuvioPlanKind, val profileId: String? = null)

/** Plano do Trazer todos. [leftOut] conta os perfis da Nuvio que não couberam no limite. */
data class NuvioPlan(val steps: List<NuvioPlanStep>, val leftOut: Int)

/** Perfil do JOTV visto pelo plano. [pickIndex] e [pickEmail] vêm do registro de importação dele (null sem escolha). */
data class JotvProfileLink(val id: String, val pickIndex: Int?, val pickEmail: String)

/** Funções puras do conjunto por perfil. O PlatformPrefs faz a leitura e a gravação em volta delas. */
object ProfileSets {
    /** Saltos de vínculo seguidos antes de desistir. */
    const val MAX_HOPS = 4

    /** Versão da migração que separou os dados por perfil (marca no aparelho). */
    const val MIGRATION_VERSION = 1

    /** Chaves do joabtv_prefs de cada categoria. As outras categorias moram em arquivo próprio. */
    val PREF_KEYS: Map<ProfileCategory, List<String>> = mapOf(
        ProfileCategory.ADDONS to listOf("stremio_addons_v2", "addon_meta_v1", "home_auto_on_v1"),
        ProfileCategory.CATALOG to listOf(
            "home_catalogs_v1", "jotv_catalog_on", "home_layout_v1", "home_hero_v1", "collections_v1", "home_keep_v1", "home_seen_v1",
        ),
    )

    /** Chaves do joabtv_live. */
    val LIVE_KEYS = listOf("sources", "favorites", "recents", "show_in_bar", "addon_off", "player_filter")

    /** Chaves do joabtv_debrid. */
    val DEBRID_KEYS = listOf("accounts", "enabled", "preferred", "hide_uncached")

    /** Estado do gerenciador de plugins no jotv_plugins (gravado como state.idDoPerfil). */
    const val PLUGIN_KEY = "state"

    /** Chaves do joabtv_prefs que eram do aparelho e passam a ser do perfil. A migração copia estas do main. */
    val MIGRATED_PREF_KEYS = listOf("stremio_addons_v2", "addon_meta_v1", "home_auto_on_v1", "jotv_catalog_on", "intro_marks_v1")

    /** Categoria de uma chave do joabtv_prefs. null para as chaves que não são vinculáveis. */
    fun categoryOfKey(key: String): ProfileCategory? = PREF_KEYS.entries.firstOrNull { key in it.value }?.key

    /**
     * Dono dos dados de [profile] em [cat]. Segue o vínculo até [MAX_HOPS] saltos e para no primeiro perfil sem
     * vínculo. Ciclo, salto demais ou perfil que não existe devolvem o próprio perfil. [links] tem uma entrada por
     * perfil existente (vazia quando ele não usa os dados de ninguém).
     */
    fun ownerOf(links: Map<String, Map<String, String>>, profile: String, cat: ProfileCategory): String {
        var cur = profile
        val seen = hashSetOf(profile)
        repeat(MAX_HOPS) {
            val next = links[cur]?.get(cat.key) ?: return cur
            if (next !in links || !seen.add(next)) return profile
            cur = next
        }
        return if (links[cur]?.get(cat.key) == null) cur else profile
    }

    /** Dá para [profile] usar os dados de [target] em [cat] sem criar ciclo. */
    fun canLink(links: Map<String, Map<String, String>>, profile: String, cat: ProfileCategory, target: String): Boolean {
        if (profile == target || target !in links || profile !in links) return false
        val next = links + (profile to (links[profile].orEmpty() + (cat.key to target)))
        return ownerOf(next, profile, cat) != profile
    }

    /** Perfis que apontam direto para [owner] em alguma categoria, com as categorias de cada um. */
    fun dependentsOf(links: Map<String, Map<String, String>>, owner: String): Map<String, List<ProfileCategory>> =
        links.mapNotNull { (id, l) ->
            val cats = l.filterValues { it == owner }.keys.mapNotNull { ProfileCategory.of(it) }
            if (id == owner || cats.isEmpty()) null else id to cats
        }.toMap()

    /**
     * Valores de uma categoria depois de copiar de [src] para [dst] (chave sem prefixo para valor cru das
     * preferências). No resultado, null quer dizer apagar a chave no destino. Debrid sempre substitui.
     */
    fun copyValues(cat: ProfileCategory, src: Map<String, Any?>, dst: Map<String, Any?>, mode: CopyMode, keys: Collection<String>): Map<String, Any?> {
        if (mode == CopyMode.REPLACE || cat == ProfileCategory.DEBRID) return keys.associateWith { src[it] }
        return keys.associateWith { k ->
            val s = src[k]
            val d = dst[k]
            when {
                s == null -> d
                d == null -> s
                else -> mergeValue(k, s, d)
            }
        }
    }

    private fun mergeValue(key: String, s: Any, d: Any): Any = runCatching {
        when (key) {
            "stremio_addons_v2" -> mergeArray(d as String, s as String) { it.optString("u").trim().lowercase() }
            "home_catalogs_v1" -> mergeArray(d as String, s as String) { "${it.optString("b")}|${it.optString("t")}|${it.optString("i")}" }
            "collections_v1" -> mergeArray(d as String, s as String) { it.optString("id") }
            "sources" -> mergeArray(d as String, s as String) { o ->
                o.optString("url").ifBlank { o.optString("server") + "|" + o.optString("user") }.ifBlank { o.optString("id") }.lowercase()
            }
            PLUGIN_KEY -> {
                val dj = JSONObject(d as String)
                val merged = mergeArray(dj.optJSONArray("repos")?.toString() ?: "[]", JSONObject(s as String).optJSONArray("repos")?.toString() ?: "[]") { it.optString("url") }
                dj.put("repos", JSONArray(merged)).toString()
            }
            "addon_meta_v1" -> {
                val dj = JSONObject(d as String)
                val sj = JSONObject(s as String)
                sj.keys().forEach { k -> if (!dj.has(k)) dj.put(k, sj.get(k)) }
                dj.toString()
            }
            "recents" -> ((d as String).split('\n') + (s as String).split('\n')).filter { it.isNotBlank() }.distinct().take(30).joinToString("\n")
            else -> if (d is Set<*> && s is Set<*>) d + s else d
        }
    }.getOrDefault(d)

    /** Junta duas listas JSON. A ordem do destino fica, o que falta da origem entra no fim. */
    fun mergeArray(dst: String, src: String, keyOf: (JSONObject) -> String): String {
        val out = JSONArray(dst)
        val have = (0 until out.length()).mapNotNull { out.optJSONObject(it)?.let(keyOf) }.toHashSet()
        val incoming = JSONArray(src)
        for (i in 0 until incoming.length()) {
            val o = incoming.optJSONObject(i) ?: continue
            val k = keyOf(o)
            if (k.isNotBlank() && have.add(k)) out.put(o)
        }
        return out.toString()
    }

    /**
     * Cópias que a migração faz. Para cada perfil que não é o main, cada chave que o main tem e o perfil ainda não
     * tem recebe o valor do main. Nada do main sai. Rodar de novo não copia nada, porque o perfil já tem as chaves.
     */
    fun migrationCopies(
        profiles: List<String>, keys: List<String>, mainValues: Map<String, Any?>, has: (profile: String, key: String) -> Boolean,
    ): List<Triple<String, String, Any>> = profiles.filter { it != MAIN_PROFILE }.flatMap { p ->
        keys.mapNotNull { k -> mainValues[k]?.takeIf { !has(p, k) }?.let { Triple(p, k, it) } }
    }

    /**
     * Plano do Trazer todos da Nuvio. Para cada perfil da Nuvio, por ordem de índice, reaproveita o perfil do JOTV
     * já ligado àquele índice com o mesmo e-mail, senão usa o atual quando ele ainda não importou da Nuvio (sem
     * e-mail no registro, mesmo que já tenha um perfil escolhido) e o perfil da Nuvio é o primeiro da lista, senão
     * cria um novo. Passou do limite, conta como de fora.
     */
    fun planNuvioAll(nuvioIndexes: List<Int>, jotv: List<JotvProfileLink>, currentId: String, email: String, maxProfiles: Int): NuvioPlan {
        val used = HashSet<String>()
        var total = jotv.size
        var leftOut = 0
        val steps = ArrayList<NuvioPlanStep>()
        val ordered = nuvioIndexes.distinct().sorted()
        ordered.forEachIndexed { pos, idx ->
            val reuse = jotv.firstOrNull { it.id !in used && it.pickIndex == idx && it.pickEmail.equals(email, ignoreCase = true) && email.isNotBlank() }
            val current = jotv.firstOrNull { it.id == currentId }
            when {
                reuse != null -> { used += reuse.id; steps += NuvioPlanStep(idx, NuvioPlanKind.REUSE, reuse.id) }
                // Livre é não ter importação da Nuvio com e-mail. Um perfil escolhido sem importar não prende o atual.
                pos == 0 && current != null && current.pickEmail.isBlank() && current.id !in used -> {
                    used += current.id; steps += NuvioPlanStep(idx, NuvioPlanKind.CURRENT, current.id)
                }
                total < maxProfiles -> { total++; steps += NuvioPlanStep(idx, NuvioPlanKind.CREATE) }
                else -> leftOut++
            }
        }
        return NuvioPlan(steps, leftOut)
    }

    /** Vínculos guardados no perfil ("links" no JSON da lista de perfis). Categoria desconhecida é ignorada. */
    fun decodeLinks(o: JSONObject?): Map<String, String> {
        if (o == null) return emptyMap()
        return o.keys().asSequence().mapNotNull { k ->
            val v = o.optString(k)
            if (ProfileCategory.of(k) != null && v.isNotBlank()) k to v else null
        }.toMap()
    }

    /** Categorias, dentre [cats], em que [profile] usa os dados de outro perfil. São as que precisam virar cópia antes de gravar. */
    fun linkedCategories(links: Map<String, Map<String, String>>, profile: String, cats: Collection<ProfileCategory>): List<ProfileCategory> =
        cats.distinct().filter { it.key in links[profile].orEmpty() }

    fun encodeLinks(links: Map<String, String>): JSONObject = JSONObject().apply { links.forEach { (k, v) -> put(k, v) } }
}
