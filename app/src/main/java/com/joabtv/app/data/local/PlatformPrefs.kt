package com.joabtv.app.data.local

import android.content.Context
import com.joabtv.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class PlatformMode { AUTO, TV, PHONE }

/** Fonte que dá o som de um título ([sig] reconhece a fonte, o link muda) e o atraso em segundos. */
data class ExtAudioPick(val sig: String, val delaySec: Double, val at: Long)

/**
 * Um addon Stremio instalado. A ordem da lista é a prioridade (o 1º é tentado primeiro).
 * [id] é o id do manifest, serve para reconhecer o mesmo addon com URL configurada diferente.
 */
data class AddonEntry(val url: String, val enabled: Boolean = true, val id: String = "")

/** Catálogo de addon marcado para virar fileira na Início. */
data class HomeCatalogRef(val base: String, val type: String, val id: String, val name: String, val addon: String) {
    val key get() = "$base|$type|$id"
}

/** Perfil de quem assiste. [color] é o índice em [PROFILE_COLORS]. */
data class Profile(val id: String, val name: String, val color: Int = 0, val kids: Boolean = false)

/** Cores dos perfis, as mesmas do webOS. */
val PROFILE_COLORS = listOf(0xFF2E8BFF, 0xFFE5484D, 0xFF3DDC84, 0xFFF5A524, 0xFFA970FF, 0xFFFF6FB5)
const val MAX_PROFILES = 5
const val MAIN_PROFILE = "main"

/**
 * Chave de preferência de um perfil. O principal usa a chave antiga, então quem já usava o app
 * não perde nada. Os outros ganham o prefixo "p.<id>." (igual ao fullKey do webOS).
 */
fun scopedKey(key: String, profileId: String): String =
    if (profileId == MAIN_PROFILE) key else "p.$profileId.$key"

/** Preferências que são de cada perfil. O resto (addons, modo, chave TMDB, aberturas) é do aparelho. */
internal val SCOPED_KEYS = listOf(
    "recent_searches", "ext_audio_v1", "home_catalogs_v1", "sub_size", "sub_bg",
    "pref_audio", "pref_max_quality", "pref_hero_trailer", "trakt_v1",
)

@Singleton
class PlatformPrefs @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        // Chave TMDB do app. Vem da gradle property TMDB_API_KEY (local.properties ou
        // ~/.gradle/gradle.properties, fora do controle de versão — veja o README).
        // Sem ela, fica em branco e cada pessoa informa a própria chave em Configurações.
        private val DEFAULT_TMDB_KEY = BuildConfig.TMDB_API_KEY

        /** Chave do formato novo (JSON com ordem + ativo). */
        private const val KEY_ADDONS_V2 = "stremio_addons_v2"

        /** Histórico de busca (uma consulta por linha, mais recente primeiro). */
        private const val KEY_RECENT_SEARCHES = "recent_searches"
        private const val MAX_RECENT_SEARCHES = 10

        /** Aberturas aprendidas por série (JSON tmdbId → [inicioMs, fimMs]). */
        private const val KEY_INTRO_MARKS = "intro_marks_v1"

        /** Catálogos de addon que viram fileira na Início (JSON, na ordem em que foram marcados). */
        private const val KEY_HOME_CATALOGS = "home_catalogs_v1"
    }

    private val prefs = context.getSharedPreferences("joabtv_prefs", Context.MODE_PRIVATE)

    // ── Perfis ────────────────────────────────────────────────────────────────
    private val _profiles = MutableStateFlow(readProfiles())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private val _profileId = MutableStateFlow(
        (prefs.getString("profile", MAIN_PROFILE) ?: MAIN_PROFILE).takeIf { id -> _profiles.value.any { it.id == id } }
            ?: _profiles.value.first().id,
    )
    /** Perfil ativo. Quem lê dado de perfil observa isso para trocar junto. */
    val profileId: StateFlow<String> = _profileId.asStateFlow()

    val profile: Profile get() = _profiles.value.firstOrNull { it.id == _profileId.value } ?: _profiles.value.first()
    val isKids: Boolean get() = profile.kids

    /** Chave já no escopo do perfil ativo. */
    private fun sk(key: String) = scopedKey(key, _profileId.value)

    private fun readProfiles(): List<Profile> = runCatching {
        val arr = org.json.JSONArray(prefs.getString("profiles", "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Profile(o.getString("id"), o.optString("name", "Perfil"), o.optInt("color", 0), o.optBoolean("kids", false))
        }
    }.getOrDefault(emptyList()).ifEmpty { listOf(Profile(MAIN_PROFILE, "Principal")) }

    private fun saveProfiles(list: List<Profile>) {
        val arr = org.json.JSONArray()
        list.forEach { arr.put(org.json.JSONObject().put("id", it.id).put("name", it.name).put("color", it.color).put("kids", it.kids)) }
        prefs.edit().putString("profiles", arr.toString()).apply()
        _profiles.value = list
    }

    /** Cria um perfil. null quando já tem o máximo. */
    fun addProfile(name: String, kids: Boolean): Profile? {
        val list = _profiles.value
        if (list.size >= MAX_PROFILES) return null
        val p = Profile(
            "p" + java.lang.Long.toString(System.currentTimeMillis(), 36),
            name.trim().take(20).ifBlank { "Perfil" }, list.size % PROFILE_COLORS.size, kids,
        )
        saveProfiles(list + p)
        return p
    }

    fun updateProfile(p: Profile) =
        saveProfiles(_profiles.value.map { if (it.id == p.id) p.copy(name = p.name.trim().take(20).ifBlank { "Perfil" }) else it })

    /**
     * Apaga o perfil e as preferências dele. Nunca apaga o último. O que fica no banco (lista,
     * histórico) quem apaga é o LibraryRepository. Devolve false quando não pôde remover.
     */
    fun removeProfile(id: String): Boolean {
        val list = _profiles.value
        if (list.size <= 1 || list.none { it.id == id }) return false
        val ed = prefs.edit()
        SCOPED_KEYS.forEach { ed.remove(scopedKey(it, id)) }
        ed.apply()
        saveProfiles(list.filterNot { it.id == id })
        if (_profileId.value == id) selectProfile(_profiles.value.first().id)
        return true
    }

    fun selectProfile(id: String) {
        if (_profiles.value.none { it.id == id }) return
        prefs.edit().putString("profile", id).apply()
        _profileId.value = id
        _homeCatalogs.value = readHomeCatalogs()
    }

    // ── Reprodução (por perfil) ───────────────────────────────────────────────

    /** Áudio preferido das fontes. "any", "dub" ou "leg". */
    var audioPref: String
        get() = prefs.getString(sk("pref_audio"), "any") ?: "any"
        set(value) { prefs.edit().putString(sk("pref_audio"), value).apply() }

    /** Qualidade máxima (0 sem limite, 2160, 1080 ou 720). */
    var maxQuality: Int
        get() = prefs.getInt(sk("pref_max_quality"), 0)
        set(value) { prefs.edit().putInt(sk("pref_max_quality"), value).apply() }

    /** Trailer mudo no destaque da Início. */
    var heroTrailer: Boolean
        get() = prefs.getBoolean(sk("pref_hero_trailer"), true)
        set(value) { prefs.edit().putBoolean(sk("pref_hero_trailer"), value).apply() }

    /** Legenda ligada ou não, lembrada entre episódios e sessões do mesmo perfil. */
    var subtitleOn: Boolean
        get() = prefs.getBoolean(sk("pref_subtitle_on"), false)
        set(value) { prefs.edit().putBoolean(sk("pref_subtitle_on"), value).apply() }

    /** Fontes na ordem da preferência do perfil (estável, igual ao sortSources do webOS). */
    fun sortStreams(list: List<com.joabtv.app.data.remote.StremioStream>) =
        com.joabtv.app.data.remote.sortByPrefs(list, audioPref, maxQuality, { it.label }, { it.qualityRank })

    /** Sessão do Trakt do perfil (JSON cru). Vazio sem login. */
    var traktSession: String
        get() = prefs.getString(sk("trakt_v1"), "") ?: ""
        set(value) { prefs.edit().putString(sk("trakt_v1"), value).apply() }

    var platformMode: PlatformMode
        get() = PlatformMode.valueOf(prefs.getString("platform_mode", PlatformMode.AUTO.name)!!)
        set(value) { prefs.edit().putString("platform_mode", value.name).apply() }

    /**
     * Já passou pela tela de nome + plataforma na primeira abertura. Só pergunta uma vez.
     * Sem a chave ainda (app nunca gravou nada), o padrão olha se já existe QUALQUER
     * preferência salva. Instalação zerada = prefs vazio = pergunta. Quem já usa o app
     * (ou restaurou backup) já tem alguma chave salva, então não pergunta de novo.
     */
    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", prefs.all.isNotEmpty())
        set(value) { prefs.edit().putBoolean("onboarded", value).apply() }

    // ── PIN dos pais ─────────────────────────────────────────────────────────
    // Vale pro aparelho inteiro (não é por perfil): trava sair do infantil e abrir
    // Addons/TMDB/Aparelho nas Configurações enquanto o perfil infantil está ativo.
    // Hash simples (SHA-256 com um sal fixo do app), suficiente pra um gate de uso doméstico,
    // não é cofre contra ataque sério. "" = nenhum PIN definido ainda (gate desligado).
    var parentalPinHash: String
        get() = prefs.getString("parental_pin_hash", "") ?: ""
        set(value) { prefs.edit().putString("parental_pin_hash", value).apply() }

    val hasParentalPin: Boolean get() = parentalPinHash.isNotBlank()

    fun hashPin(pin: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest(("jotv-parental-pin:$pin").toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun setParentalPin(pin: String) { parentalPinHash = hashPin(pin) }
    fun clearParentalPin() { parentalPinHash = "" }
    fun checkParentalPin(pin: String): Boolean = hasParentalPin && parentalPinHash == hashPin(pin)

    var customTmdbApiKey: String
        get() = prefs.getString("tmdb_api_key", "").orEmpty().trim().takeUnless { it == DEFAULT_TMDB_KEY }.orEmpty()
        set(value) {
            val key = value.trim()
            prefs.edit().apply {
                if (key.isBlank() || key == DEFAULT_TMDB_KEY) remove("tmdb_api_key")
                else putString("tmdb_api_key", key)
            }.apply()
        }

    var tmdbApiKey: String
        get() = customTmdbApiKey.ifBlank { DEFAULT_TMDB_KEY }
        set(value) { customTmdbApiKey = value }

    /**
     * Addons Stremio (uma URL de manifest por linha).
     * Ausente (nunca configurado) → padrões. Campo vazio ("") → usuário OPTOU POR NENHUM.
     * Mantido por compatibilidade; a fonte de verdade agora é [addonEntries] (com ordem e ativo).
     */
    var stremioAddons: String
        get() = addonEntries.joinToString("\n") { it.url }
        set(value) {
            val urls = value.split("\n", ",").map { it.trim() }.filter { it.startsWith("http") }
            // Preserva o estado "ativo" dos que já existiam.
            val prev = addonEntries.associateBy { it.url }
            addonEntries = urls.map { AddonEntry(it, prev[it]?.enabled ?: true) }
        }

    /** URLs dos addons ATIVOS, na ordem de prioridade definida pelo usuário. */
    fun stremioAddonList(): List<String> = addonEntries.filter { it.enabled }.map { it.url }

    /** Últimas buscas do usuário, mais recente primeiro. Alimenta a tela de busca vazia. */
    var recentSearches: List<String>
        get() = prefs.getString(sk(KEY_RECENT_SEARCHES), "").orEmpty()
            .split("\n").map { it.trim() }.filter { it.isNotBlank() }
        set(value) {
            val clean = value.map { it.trim() }.filter { it.isNotBlank() }
                .distinctBy { it.lowercase() }.take(MAX_RECENT_SEARCHES)
            prefs.edit().putString(sk(KEY_RECENT_SEARCHES), clean.joinToString("\n")).apply()
        }

    /** Empurra uma consulta para o topo do histórico (sem duplicar). */
    fun pushRecentSearch(query: String) {
        val q = query.trim()
        if (q.length < 2) return
        recentSearches = listOf(q) + recentSearches.filterNot { it.equals(q, ignoreCase = true) }
    }

    // ── Abertura das séries ───────────────────────────────────────────────────
    // Não existe API pública de timestamp de intro fora do AniSkip (só anime). Então o app
    // aprende sozinho: quando o usuário arrasta a barra pra frente no começo do episódio,
    // esse pulo vira o marcador da série e nos próximos episódios vira o botão "Pular abertura".

    /** Abertura aprendida da série (inicioMs, fimMs). null enquanto o usuário nunca pulou. */
    fun introMarker(tmdbId: Int): Pair<Long, Long>? {
        if (tmdbId <= 0) return null
        val raw = prefs.getString(KEY_INTRO_MARKS, null) ?: return null
        val arr = runCatching { org.json.JSONObject(raw).optJSONArray(tmdbId.toString()) }.getOrNull() ?: return null
        if (arr.length() < 2) return null
        val start = arr.optLong(0, -1L)
        val end = arr.optLong(1, -1L)
        return if (start >= 0 && end > start) start to end else null
    }

    /** Grava a abertura da série (sobrescreve a anterior, o último pulo é o mais confiável). */
    fun saveIntroMarker(tmdbId: Int, startMs: Long, endMs: Long) {
        if (tmdbId <= 0 || endMs <= startMs) return
        val root = runCatching { org.json.JSONObject(prefs.getString(KEY_INTRO_MARKS, null) ?: "{}") }
            .getOrDefault(org.json.JSONObject())
        root.put(tmdbId.toString(), org.json.JSONArray().put(startMs).put(endMs))
        prefs.edit().putString(KEY_INTRO_MARKS, root.toString()).apply()
    }

    // ── Legenda e som de outra fonte (player) ─────────────────────────────────

    /** Tamanho da legenda (0,8 pequena, 1 média, 1,25 grande, 1,5 muito grande). */
    var subSize: Float
        get() = prefs.getFloat(sk("sub_size"), 1f)
        set(value) { prefs.edit().putFloat(sk("sub_size"), value).apply() }

    /** Fundo escuro atrás da legenda. */
    var subBg: Boolean
        get() = prefs.getBoolean(sk("sub_bg"), true)
        set(value) { prefs.edit().putBoolean(sk("sub_bg"), value).apply() }

    /** Escolhas de som de outra fonte por título, chave "TIPO:tmdbId". */
    var extAudioPicks: Map<String, ExtAudioPick>
        get() = runCatching {
            val o = org.json.JSONObject(prefs.getString(sk("ext_audio_v1"), null) ?: "{}")
            o.keys().asSequence().associateWith { k ->
                val v = o.getJSONObject(k)
                ExtAudioPick(v.getString("s"), v.optDouble("d", 0.0), v.optLong("t", 0L))
            }
        }.getOrDefault(emptyMap())
        set(value) {
            val o = org.json.JSONObject()
            value.forEach { (k, p) -> o.put(k, org.json.JSONObject().put("s", p.sig).put("d", p.delaySec).put("t", p.at)) }
            prefs.edit().putString(sk("ext_audio_v1"), o.toString()).apply()
        }

    /**
     * Lista completa de addons (ordem = prioridade, 1º é tentado primeiro) com o flag de ativo.
     * Guardada em JSON. Migra automaticamente do formato antigo (uma URL por linha).
     */
    var addonEntries: List<AddonEntry>
        get() {
            val raw = prefs.getString(KEY_ADDONS_V2, null)
            if (raw != null) return decodeEntries(raw)
            // Migração do formato legado (texto com uma URL por linha). Instalação nova
            // entra sem addon nenhum — a pessoa escolhe e instala pela Loja.
            val legacy = prefs.getString("stremio_addons", null)
            return (legacy?.split("\n", ",") ?: emptyList()).map { it.trim() }
                .filter { it.startsWith("http") }
                .map { AddonEntry(it, true) }
        }
        set(value) {
            val clean = value.distinctBy { it.url }
            prefs.edit().putString(KEY_ADDONS_V2, encodeEntries(clean)).apply()
            _addons.value = clean
        }

    private val _addons = MutableStateFlow(addonEntries)
    /** Lista de addons que avisa quem observa. A loja instala e o gerenciador já vê. */
    val addons: StateFlow<List<AddonEntry>> = _addons.asStateFlow()

    /** Posição do addon pela URL ou pelo id do manifest. -1 se não está instalado. */
    fun addonIndex(url: String, id: String = ""): Int = addonEntries.indexOfFirst {
        it.url.equals(url, true) || (id.isNotBlank() && it.id == id)
    }

    /** Instala no fim da fila, ou troca a URL no lugar quando o mesmo addon já existe. */
    fun installAddon(url: String, id: String = "") {
        val list = addonEntries.toMutableList()
        val i = addonIndex(url, id)
        if (i >= 0) list[i] = AddonEntry(url, true, id.ifBlank { list[i].id })
        else list += AddonEntry(url, true, id)
        addonEntries = list
    }

    /** Remove o addon e tira da Início as fileiras que vinham dele. */
    fun removeAddon(url: String, id: String = "") {
        val i = addonIndex(url, id)
        if (i < 0) return
        val gone = addonEntries[i]
        addonEntries = addonEntries.filterIndexed { idx, _ -> idx != i }
        val base = gone.url.removeSuffix("/manifest.json").trimEnd('/')
        homeCatalogs = homeCatalogs.filterNot { it.base == base }
        // ponytail: só limpa as fileiras do perfil ativo. Nos outros a fileira do addon removido
        // fica vazia até a pessoa desmarcar. Limpar todos os perfis aqui se isso incomodar.
    }

    // ── Catálogos de addon na Início ──────────────────────────────────────────
    private val _homeCatalogs = MutableStateFlow(readHomeCatalogs())
    val homeCatalogsFlow: StateFlow<List<HomeCatalogRef>> = _homeCatalogs.asStateFlow()

    var homeCatalogs: List<HomeCatalogRef>
        get() = _homeCatalogs.value
        set(value) {
            val arr = org.json.JSONArray()
            value.distinctBy { it.key }.forEach {
                arr.put(org.json.JSONObject().put("b", it.base).put("t", it.type).put("i", it.id).put("n", it.name).put("a", it.addon))
            }
            prefs.edit().putString(sk(KEY_HOME_CATALOGS), arr.toString()).apply()
            _homeCatalogs.value = value.distinctBy { it.key }
        }

    /** Liga ou desliga a fileira. Devolve true quando ela passou a aparecer. */
    fun toggleHomeCatalog(ref: HomeCatalogRef): Boolean {
        val had = homeCatalogs.any { it.key == ref.key }
        homeCatalogs = if (had) homeCatalogs.filterNot { it.key == ref.key } else homeCatalogs + ref
        return !had
    }

    private fun readHomeCatalogs(): List<HomeCatalogRef> = runCatching {
        val arr = org.json.JSONArray(prefs.getString(sk(KEY_HOME_CATALOGS), "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            HomeCatalogRef(o.optString("b"), o.optString("t"), o.optString("i"), o.optString("n"), o.optString("a"))
                .takeIf { it.base.startsWith("http") && it.id.isNotBlank() }
        }
    }.getOrDefault(emptyList())

    private fun decodeEntries(raw: String): List<AddonEntry> = runCatching {
        val arr = org.json.JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val u = o.optString("u").trim()
            if (u.startsWith("http")) AddonEntry(u, o.optBoolean("e", true), o.optString("i")) else null
        }
    }.getOrDefault(emptyList())

    private fun encodeEntries(list: List<AddonEntry>): String {
        val arr = org.json.JSONArray()
        list.forEach {
            val o = org.json.JSONObject().put("u", it.url).put("e", it.enabled)
            if (it.id.isNotBlank()) o.put("i", it.id)
            arr.put(o)
        }
        return arr.toString()
    }
}
