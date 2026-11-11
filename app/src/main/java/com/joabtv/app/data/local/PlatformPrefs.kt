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
    "home_layout_v1", "home_hero_v1", "pref_subtitle_on", "pref_sub_lang", "collections_v1",
    "pref_theme", "pref_motion", "pref_menu_style", "pref_ui_size", "home_keep_v1", "home_seen_v1", "import_log_v1",
)

@Singleton
class PlatformPrefs @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        // Chave TMDB do app (do usuário, uso pessoal). Prefere BuildConfig (gradle property)
        // se preenchida; senão usa o fallback embutido p/ o app funcionar out-of-the-box.
        private val DEFAULT_TMDB_KEY =
            BuildConfig.TMDB_API_KEY

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
        refreshLayout()
        _appearance.value = readAppearance()
        _heroTrailer.value = prefs.getBoolean(sk("pref_hero_trailer"), true)
        _importLog.value = readImportLog()
    }

    private fun readAppearance() = AppearancePreferences(
        theme = AppTheme.fromKey(prefs.getString(sk("pref_theme"), null)),
        motion = InterfaceMotion.fromKey(prefs.getString(sk("pref_motion"), null)),
        menu = MenuStyle.fromKey(prefs.getString(sk("pref_menu_style"), null)),
        uiSize = InterfaceSize.fromKey(prefs.getString(sk("pref_ui_size"), null)),
    )

    // Registro do que veio de cada conta (por perfil). Troca junto com o perfil, em selectProfile.
    private fun readImportLog() = com.joabtv.app.data.accounts.ImportLog.decode(prefs.getString(sk("import_log_v1"), null))

    private val _importLog = MutableStateFlow(readImportLog())
    val importLogFlow: StateFlow<com.joabtv.app.data.accounts.ImportLog> = _importLog.asStateFlow()
    val importLog: com.joabtv.app.data.accounts.ImportLog get() = _importLog.value

    private fun writeImportLog(v: com.joabtv.app.data.accounts.ImportLog) {
        prefs.edit().putString(sk("import_log_v1"), v.encode()).apply()
        _importLog.value = v
    }

    /** Grava o resultado de uma parte assim que ela termina. Duas contas podem trazer ao mesmo tempo, daí o synchronized. */
    @Synchronized
    fun recordImport(
        account: String, part: String, rec: com.joabtv.app.data.accounts.PartRecord,
        email: String, profile: String, now: Long = System.currentTimeMillis(),
    ) = writeImportLog(importLog.put(account, part, rec, email, profile, now))

    @Synchronized
    fun setNuvioPick(p: com.joabtv.app.data.accounts.NuvioPick) = writeImportLog(importLog.withPick(p))

    private val _appearance = MutableStateFlow(readAppearance())
    val appearanceFlow: StateFlow<AppearancePreferences> = _appearance.asStateFlow()

    var appearance: AppearancePreferences
        get() = _appearance.value
        set(value) {
            prefs.edit().putString(sk("pref_theme"), value.theme.key)
                .putString(sk("pref_motion"), value.motion.key)
                .putString(sk("pref_menu_style"), value.menu.key)
                .putString(sk("pref_ui_size"), value.uiSize.key).apply()
            _appearance.value = value
        }

    // ── Diagnóstico (do aparelho) ─────────────────────────────────────────────

    /** Medidor de quadros no canto da tela, para medir o desempenho no aparelho real. */
    private val _frameMeter = MutableStateFlow(prefs.getBoolean("pref_frame_meter", false))
    val frameMeterFlow: StateFlow<Boolean> = _frameMeter.asStateFlow()
    var frameMeter: Boolean
        get() = _frameMeter.value
        set(value) { prefs.edit().putBoolean("pref_frame_meter", value).apply(); _frameMeter.value = value }

    // ── Reprodução (por perfil) ───────────────────────────────────────────────

    /** Áudio preferido das fontes. "any", "dub" ou "leg". */
    var audioPref: String
        get() = prefs.getString(sk("pref_audio"), "any") ?: "any"
        set(value) { prefs.edit().putString(sk("pref_audio"), value).apply() }

    /** Qualidade máxima (0 sem limite, 2160, 1080 ou 720). */
    var maxQuality: Int
        get() = prefs.getInt(sk("pref_max_quality"), 0)
        set(value) { prefs.edit().putInt(sk("pref_max_quality"), value).apply() }

    /**
     * Trailer mudo no destaque da Início. Observável porque a Início fica viva enquanto a pessoa
     * muda a opção em Configurações ou em Personalizar Início, e antes só via a troca ao reabrir o app.
     */
    private val _heroTrailer = MutableStateFlow(prefs.getBoolean(sk("pref_hero_trailer"), true))
    val heroTrailerFlow: StateFlow<Boolean> = _heroTrailer.asStateFlow()
    var heroTrailer: Boolean
        get() = _heroTrailer.value
        set(value) { prefs.edit().putBoolean(sk("pref_hero_trailer"), value).apply(); _heroTrailer.value = value }

    /** Legenda ligada ou não, lembrada entre episódios e sessões do mesmo perfil. */
    var subtitleOn: Boolean
        get() = prefs.getBoolean(sk("pref_subtitle_on"), false)
        set(value) { prefs.edit().putBoolean(sk("pref_subtitle_on"), value).apply() }

    /** Idioma em que a legenda liga sozinha, código do OpenSubtitles (ver [SubLangs]). */
    var subLang: String
        get() = prefs.getString(sk("pref_sub_lang"), null)?.takeIf { SubLangs.of(it) != null } ?: SubLangs.DEFAULT
        set(value) { prefs.edit().putString(sk("pref_sub_lang"), value).apply() }

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
            refreshLayout()
        }

    private val _addons = MutableStateFlow(addonEntries)
    /** Lista de addons que avisa quem observa. A loja instala e o gerenciador já vê. */
    val addons: StateFlow<List<AddonEntry>> = _addons.asStateFlow()

    /** Posição do addon pela URL ou pelo id do manifest. -1 se não está instalado. */
    fun addonIndex(url: String, id: String = ""): Int = addonEntries.indexOfFirst {
        it.url.equals(url, true) || (id.isNotBlank() && it.id == id)
    }

    /**
     * Instala no fim da fila, ou troca a URL no lugar quando o mesmo addon já existe.
     * Os catálogos de addon instalado daqui em diante entram ligados na Início.
     */
    fun installAddon(url: String, id: String = "") {
        // Primeira vez deste perfil. O que já existe não vira Nova em Minha Início, só o que o addon trouxer.
        if (homeSeen == null) homeSeen = homeLayout.mapTo(HashSet()) { it.key }
        val list = addonEntries.toMutableList()
        val i = addonIndex(url, id)
        if (i >= 0) list[i] = AddonEntry(url, true, id.ifBlank { list[i].id })
        else list += AddonEntry(url, true, id)
        autoOnBases = autoOnBases + baseOf(url)
        addonEntries = list
    }

    /** Remove o addon. As fileiras dele saem da Início de todos os perfis, depois da confirmação da tela. */
    fun removeAddon(url: String, id: String = "") {
        val i = addonIndex(url, id)
        if (i < 0) return
        val gone = addonEntries[i]
        val base = baseOf(gone.url)
        autoOnBases = autoOnBases - base
        // As fileiras dele saem da ordem guardada de todos os perfis, assim uma reinstalação volta
        // com o teto de 10 e não com o liga e desliga de antes.
        val ed = prefs.edit()
        _profiles.value.forEach { p ->
            val k = scopedKey("home_layout_v1", p.id)
            HomeLayout.decode(prefs.getString(k, null))?.let { list -> ed.putString(k, HomeLayout.encode(HomeLayout.dropBase(list, base))) }
        }
        ed.apply()
        addonEntries = addonEntries.filterIndexed { idx, _ -> idx != i }
    }

    private fun baseOf(url: String) = url.trim().removeSuffix("/manifest.json").trimEnd('/')

    /**
     * Addon que mudou de URL (token novo, host novo). As fileiras dele na Início de todos os
     * perfis passam para a base nova, com o liga e desliga e o nome que cada perfil escolheu.
     */
    fun rebaseAddon(oldUrl: String, newUrl: String) {
        val old = baseOf(oldUrl)
        val new = baseOf(newUrl)
        if (old == new) return
        val ed = prefs.edit()
        _profiles.value.forEach { p ->
            val k = scopedKey("home_layout_v1", p.id)
            HomeLayout.decode(prefs.getString(k, null))?.let { list ->
                ed.putString(k, HomeLayout.encode(list.map { r ->
                    val ref = r.ref
                    if (ref != null && ref.base == old) ref.copy(base = new).let { nr -> r.copy(key = nr.key, ref = nr) } else r
                }))
            }
            val hk = scopedKey("home_hero_v1", p.id)
            val hero = prefs.getString(hk, "").orEmpty()
            if (hero.startsWith("$old|")) ed.putString(hk, new + hero.removePrefix(old))
        }
        ed.apply()
        if (old in autoOnBases) autoOnBases = autoOnBases - old + new
        refreshLayout()
    }

    /** Mensagem de addon instalado, a mesma na loja, no link e na importação das contas. */
    fun installText(name: String, url: String): String {
        val rows = homeLayout.filter { it.ref?.base == baseOf(url) }
        return HomeLayout.installText(name, rows.count { it.enabled }, rows.size)
    }

    // ── Catálogo JOTV (o catálogo próprio do app, fixo e desligável como um addon) ──
    private val _catalogOn = MutableStateFlow(prefs.getBoolean("jotv_catalog_on", true))
    /** Catálogo JOTV ligado. Desligado, as fileiras do TMDB somem da Início de todos os perfis. */
    val catalogOnFlow: StateFlow<Boolean> = _catalogOn.asStateFlow()
    var catalogOn: Boolean
        get() = _catalogOn.value
        set(value) { prefs.edit().putBoolean("jotv_catalog_on", value).apply(); _catalogOn.value = value }

    // ── Manifests guardados (nome e catálogos de cada addon, para a Início abrir sem rede) ──
    private val _addonMeta = MutableStateFlow(HomeLayout.decodeAddons(prefs.getString("addon_meta_v1", null)))
    val addonMetaFlow: StateFlow<Map<String, CachedAddon>> = _addonMeta.asStateFlow()

    /**
     * Guarda o que o manifest diz. Chamado toda vez que um manifest é lido com sucesso, às vezes
     * vários ao mesmo tempo (o gerenciador lê todos em paralelo), daí o synchronized.
     */
    fun cacheManifest(url: String, name: String, catalogs: List<com.joabtv.app.data.remote.AddonCatalog>, id: String = "") {
        val base = baseOf(url)
        val now = CachedAddon(name, catalogs, id)
        synchronized(_addonMeta) {
            if (_addonMeta.value[base] == now) return
            val map = _addonMeta.value + (base to now)
            prefs.edit().putString("addon_meta_v1", HomeLayout.encodeAddons(map)).apply()
            _addonMeta.value = map
        }
        refreshLayout()
    }

    /** Bases de addon instalado depois da Início personalizável. Catálogo novo deles entra ligado. */
    private var autoOnBases: Set<String>
        get() = prefs.getStringSet("home_auto_on_v1", emptySet()).orEmpty()
        set(value) { prefs.edit().putStringSet("home_auto_on_v1", value.toSet()).apply() }

    // ── Início personalizável (por perfil) ────────────────────────────────────
    /** De onde vem o destaque do topo. Vazio = automático (em alta do JOTV ou a 1ª fileira). */
    private val _heroSource = MutableStateFlow(prefs.getString(sk("home_hero_v1"), "").orEmpty())
    val heroSourceFlow: StateFlow<String> = _heroSource.asStateFlow()
    var heroSource: String
        get() = _heroSource.value
        set(value) { prefs.edit().putString(sk("home_hero_v1"), value).apply(); _heroSource.value = value }

    // ── Coleções (formato do Nuvio), por perfil. Antes do layout, que conta com elas. ──
    private fun readCollections(): List<com.joabtv.app.data.collections.MediaCollection> =
        prefs.getString(sk("collections_v1"), null)
            ?.let { com.joabtv.app.data.collections.CollectionsJson.parse(it).getOrNull() }
            .orEmpty()

    private val _collections = MutableStateFlow(readCollections())
    val collectionsFlow: StateFlow<List<com.joabtv.app.data.collections.MediaCollection>> = _collections.asStateFlow()

    fun saveCollections(list: List<com.joabtv.app.data.collections.MediaCollection>) {
        prefs.edit().putString(sk("collections_v1"), com.joabtv.app.data.collections.CollectionsJson.encode(list)).apply()
        refreshLayout()
    }

    private val _homeLayout = MutableStateFlow(computeLayout())
    /** Todas as fileiras do perfil na ordem escolhida, ligadas ou não. */
    val homeLayoutFlow: StateFlow<List<HomeRowPref>> = _homeLayout.asStateFlow()
    val homeLayout: List<HomeRowPref> get() = _homeLayout.value

    /** Liga ou desliga a fileira. [ref] serve para catálogo que ainda não estava na lista. */
    fun setHomeRowEnabled(key: String, on: Boolean, ref: HomeCatalogRef? = null) {
        val cur = homeLayout
        saveLayout(
            if (cur.any { it.key == key }) cur.map { if (it.key == key) it.copy(enabled = on) else it }
            else if (ref != null) cur + HomeRowPref(key, on, "", ref) else cur
        )
    }

    /** Dá outro nome à fileira. Vazio volta ao nome original. */
    fun renameHomeRow(key: String, title: String) =
        saveLayout(homeLayout.map { if (it.key == key) it.copy(title = title.trim().take(60)) else it })

    fun moveHomeRow(key: String, delta: Int, visible: (HomeRowPref) -> Boolean = { true }) {
        val cur = homeLayout
        val moved = HomeLayout.move(cur, key, delta, visible)
        if (moved !== cur) saveLayout(moved)
    }

    /** Volta ao padrão. Ordem original, nomes e destaque limpos e as sugestões do JOTV religadas. */
    fun resetHomeLayout() {
        saveLayout(HomeLayout.reset(homeLayout))
        heroSource = ""
        catalogOn = true
    }

    /** Foto do que Voltar ao padrão muda, para o Desfazer. */
    class HomeSnapshot internal constructor(val profileId: String, val layout: String?, val hero: String, val catalogOn: Boolean)

    fun homeSnapshot() = HomeSnapshot(_profileId.value, prefs.getString(sk("home_layout_v1"), null), heroSource, catalogOn)

    /** Devolve a foto de [homeSnapshot]. Ignora quando o perfil ativo já é outro. */
    fun restoreHome(s: HomeSnapshot) {
        if (s.profileId != _profileId.value) return
        val ed = prefs.edit()
        if (s.layout == null) ed.remove(sk("home_layout_v1")) else ed.putString(sk("home_layout_v1"), s.layout)
        ed.apply()
        heroSource = s.hero
        catalogOn = s.catalogOn
        refreshLayout()
    }

    /** Liga ou desliga várias fileiras de uma vez, gravando uma vez só. */
    fun setHomeRowsEnabled(keys: Set<String>, on: Boolean) =
        saveLayout(homeLayout.map { if (it.key in keys) it.copy(enabled = on) else it })

    /** Tira da lista a fileira de addon removido (a pessoa apertou Tirar). */
    fun dropHomeRow(key: String) = saveLayout(homeLayout.filter { it.key != key })

    /** Grava a ordem que a pessoa escolheu no modo Mover. */
    fun saveHomeOrder(list: List<HomeRowPref>) = saveLayout(list)

    /** Leva uma fileira a uma posição, sem passar das coleções fixadas nem de Gêneros. */
    fun moveHomeRowTo(key: String, to: Int) {
        val cur = homeLayout
        val pinned = _collections.value.filter { it.pinToTop }.mapTo(HashSet()) { HomeLayout.COL_PREFIX + it.id }
        val moved = HomeLayout.moveTo(cur, key, to, pinned)
        if (moved !== cur) saveLayout(moved)
    }

    /** Deixa ligadas só as primeiras fileiras de um addon grande neste perfil. */
    fun keepFirstHomeRows(base: String) = saveLayout(HomeLayout.keepFirst(homeLayout, base))

    /** Addons grandes cuja pergunta Deixar 10 o perfil já respondeu com Manter todas. */
    var homeKeep: Set<String>
        get() = prefs.getStringSet(sk("home_keep_v1"), emptySet()).orEmpty()
        set(value) { prefs.edit().putStringSet(sk("home_keep_v1"), value.toSet()).apply() }

    /** Chaves das fileiras que o perfil já viu em Minha Início. null antes da primeira visita. */
    var homeSeen: Set<String>?
        get() = prefs.getStringSet(sk("home_seen_v1"), null)
        set(value) {
            val ed = prefs.edit()
            if (value == null) ed.remove(sk("home_seen_v1")) else ed.putStringSet(sk("home_seen_v1"), value.toSet())
            ed.apply()
        }

    /** Pedido de uma vez só, em memória, para a Minha Início abrir na primeira fileira nova. */
    var homeFocusNew = false

    private fun saveLayout(list: List<HomeRowPref>) {
        prefs.edit().putString(sk("home_layout_v1"), HomeLayout.encode(list)).apply()
        refreshLayout()
    }

    private fun refreshLayout() {
        // Proteção para chamada durante a construção, antes do fluxo existir.
        @Suppress("SENSELESS_COMPARISON")
        if (_homeLayout == null) return
        // Coleções são do perfil. Troca de perfil passa por aqui e recarrega as dele.
        _collections.value = readCollections()
        _homeLayout.value = computeLayout()
        _heroSource.value = prefs.getString(sk("home_hero_v1"), "").orEmpty()
    }

    private fun computeLayout(): List<HomeRowPref> {
        val entries = addonEntries
        val meta = _addonMeta.value
        val available = entries.flatMap { e ->
            val base = baseOf(e.url)
            val m = meta[base] ?: return@flatMap emptyList()
            m.catalogs.map { c -> HomeCatalogRef(base, c.type, c.id, c.name, m.name) }
        }
        val saved = HomeLayout.decode(prefs.getString(sk("home_layout_v1"), null)) ?: HomeLayout.default(readHomeCatalogs())
        return HomeLayout.normalize(saved, entries.mapTo(HashSet()) { baseOf(it.url) }, available, autoOnBases, _collections.value, keepOrphans = true)
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
