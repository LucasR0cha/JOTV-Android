package com.joabtv.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.joabtv.app.data.accounts.NuvioAccountService
import com.joabtv.app.data.local.AddonEntry
import com.joabtv.app.data.local.BigAddon
import com.joabtv.app.data.local.HomeLayout
import com.joabtv.app.data.local.HomeRowPref
import com.joabtv.app.data.local.PlatformPrefs
import com.joabtv.app.data.local.SystemRow
import com.joabtv.app.data.remote.AddonLinks
import com.joabtv.app.data.remote.StremioAddonService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Minha Início. Tudo é local e aplica na hora (a Início observa o mesmo fluxo). Ordem, nome e liga e
 * desliga valem para o perfil. As sugestões do JOTV valem para o aparelho.
 */
@HiltViewModel
class HomeCustomizeViewModel @Inject constructor(
    private val prefs: PlatformPrefs,
    private val stremio: StremioAddonService,
    nuvio: NuvioAccountService,
) : ViewModel() {

    /** O que a linha pede ao OK quando ela não é só um interruptor. */
    enum class Hint { NONE, ENABLE_SUGGESTIONS, OPEN_ADDONS }

    /** Estado da conta Nuvio para o convite do fim da tela. */
    enum class NuvioState { OUT, IN, DONE }

    /** Uma linha da lista. [appear] diz se ela está na Início agora. */
    data class Row(
        val pref: HomeRowPref,
        val appear: Boolean,
        val meta: String,
        val hint: Hint,
        val orphan: Boolean,
        val pinned: Boolean,
        val movable: Boolean,
        val hasMore: Boolean,
        val canHero: Boolean,
        val isNew: Boolean,
        val addonUrl: String,
        val addonName: String,
        val collectionId: String?,
        val firstFolder: String?,
        val folders: Int,
        val pinToTop: Boolean,
    ) {
        val key: String get() = pref.key
        val title: String get() = pref.displayTitle
        val original: String get() = pref.defaultTitle
        val renamed: Boolean get() = pref.title.isNotBlank() && pref.title != pref.defaultTitle
    }

    data class HeroOption(val key: String, val label: String, val tag: String = "")

    /** Um addon com fileiras. [on] de [all] aparecem na Início deste perfil. */
    data class AddonBox(val url: String, val name: String, val on: Int, val all: Int, val enabled: Boolean)

    data class UiState(
        val rows: List<Row> = emptyList(),
        val catalogOn: Boolean = true,
        val heroSource: String = "",
        val heroLabel: String = "Automático",
        val heroOptions: List<HeroOption> = emptyList(),
        val addonBoxes: List<AddonBox> = emptyList(),
        val big: BigAddon? = null,
        val kids: Boolean = false,
        val profile: String = "",
        val moving: String? = null,
        val sticky: Set<String> = emptySet(),
        val groupOpen: Boolean = false,
        val groupLimit: Int = PAGE,
        val focusNew: Boolean = false,
        val othersOn: Boolean = false,
        val nuvio: NuvioState = NuvioState.OUT,
    ) {
        /** Fileiras da lista. A escondida nesta visita fica no lugar até a pessoa sair ou abrir o grupo. */
        val main: List<Row> get() = rows.filter { it.pref.enabled || (!groupOpen && it.key in sticky) }
        val hidden: List<Row> get() = rows.filter { !(it.pref.enabled || (!groupOpen && it.key in sticky)) }

        /** Número da fileira na Início, na ordem da tela. Só quem aparece tem número. */
        val numbers: Map<String, Int>
            get() {
                var n = 0
                val out = HashMap<String, Int>()
                for (r in main + hidden) if (r.appear) out[r.key] = ++n
                return out
            }
        val appearing: List<Row> get() = (main + hidden).filter { it.appear }
        val hiddenCount: Int get() = rows.count { !it.pref.enabled }
    }

    /** Resultado de apertar a linha, para a tela dizer o que aconteceu. */
    sealed interface Tap {
        data class OpenAddons(val url: String) : Tap
        data object SuggestionsOn : Tap
        data class Toggled(val title: String, val key: String, val on: Boolean) : Tap
    }

    private class Draft(val key: String, val order: List<HomeRowPref>)

    private data class Local(
        val sticky: Set<String> = emptySet(),
        val groupOpen: Boolean = false,
        val groupLimit: Int = PAGE,
        val draft: Draft? = null,
        val keep: Set<String> = emptySet(),
    )

    private val local = MutableStateFlow(Local(keep = prefs.homeKeep))
    private val seenAtOpen: Set<String>? = prefs.homeSeen
    private val focusNew = prefs.homeFocusNew.also { prefs.homeFocusNew = false }

    val state: StateFlow<UiState> = combine(
        combine(prefs.homeLayoutFlow, prefs.catalogOnFlow, prefs.heroSourceFlow, prefs.addons, local, ::Inputs),
        nuvio.session, prefs.importLogFlow,
    ) { i, session, log ->
        build(i).copy(
            nuvio = when {
                session == null -> NuvioState.OUT
                log.entries["nuvio"] == null -> NuvioState.IN
                else -> NuvioState.DONE
            },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    private class Inputs(
        val layout: List<HomeRowPref>, val catalogOn: Boolean, val hero: String, val addons: List<AddonEntry>, val local: Local,
    )

    init {
        if (seenAtOpen == null) prefs.homeSeen = prefs.homeLayout.mapTo(HashSet()) { it.key }
        // Addon que ainda não tem o manifest guardado ganha as fileiras assim que ele responde.
        viewModelScope.launch {
            val meta = prefs.addonMetaFlow.value
            prefs.addonEntries.filter { AddonLinks.baseOf(it.url) !in meta }.forEach { stremio.getManifest(it.url) }
        }
    }

    override fun onCleared() {
        prefs.homeSeen = prefs.homeLayout.mapTo(HashSet()) { it.key }
    }

    private fun build(i: Inputs): UiState {
        val layout = i.layout
        val entries = i.addons.associateBy { AddonLinks.baseOf(it.url) }
        val cols = prefs.collectionsFlow.value.associateBy { HomeLayout.COL_PREFIX + it.id }
        val pinned = cols.filterValues { it.pinToTop }.keys
        val fresh = HomeLayout.fresh(seenAtOpen, layout.map { it.key })
        val order = i.local.draft?.order ?: layout
        val rows = order.map { r ->
            val sys = r.system
            val col = cols[r.key]
            val ref = r.ref
            val entry = ref?.let { entries[it.base] }
            val orphan = ref != null && entry == null
            val off = entry != null && !entry.enabled
            val blocked = sys?.catalog == true && !i.catalogOn
            val emptyCol = col != null && col.folders.isEmpty()
            val appear = r.enabled && !orphan && !off && !blocked && !emptyCol
            var hint = Hint.NONE
            val meta = when {
                orphan -> (ref?.addon?.ifBlank { null } ?: "O addon") + " foi removido"
                off -> { hint = Hint.OPEN_ADDONS; "Addon desligado" }
                blocked -> { hint = Hint.ENABLE_SUGGESTIONS; "Sugestões desligadas" }
                col != null -> when {
                    r.key in pinned -> "Coleção, fixada no topo"
                    emptyCol -> "Coleção sem pastas, não aparece"
                    else -> "Coleção, " + com.joabtv.app.ui.collections.countOf(col.folders.size, "pasta", "pastas")
                }
                r.key == SystemRow.GENRES.key -> "Sempre por último"
                ref != null -> ref.addon.ifBlank { "Addon" } + ", " + if (ref.type == "series") "séries" else "filmes"
                sys?.catalog == true -> "Sugestões do JOTV"
                else -> "Seu progresso"
            }
            Row(
                pref = r, appear = appear, meta = meta, hint = hint, orphan = orphan,
                pinned = r.key in pinned,
                movable = !orphan && r.key != SystemRow.GENRES.key && r.key !in pinned,
                hasMore = (ref != null && !orphan) || col != null,
                canHero = r.enabled && !orphan && col == null && sys != SystemRow.TRAILERS && sys != SystemRow.GENRES && sys != SystemRow.NEW_EPISODES,
                isNew = r.key in fresh,
                addonUrl = entry?.url.orEmpty(),
                addonName = ref?.addon?.ifBlank { null } ?: "Addon",
                collectionId = col?.id,
                firstFolder = col?.folders?.firstOrNull()?.id,
                folders = col?.folders?.size ?: 0,
                pinToTop = col?.pinToTop == true,
            )
        }
        val heroRows = rows.filter { it.canHero && layout.any { l -> l.key == it.key } }
        val heroOptions = listOf(HeroOption("", "Automático")) + heroRows.map { HeroOption(it.key, it.title, if (it.pref.ref != null) it.addonName else "") }
        val hero = i.hero.takeIf { h -> h.isBlank() || heroRows.any { it.key == h } }.orEmpty()
        val boxes = i.addons.mapNotNull { a ->
            val base = AddonLinks.baseOf(a.url)
            val mine = layout.filter { it.ref?.base == base }
            if (mine.isEmpty()) null
            else AddonBox(a.url, mine.first().ref?.addon?.ifBlank { null } ?: AddonLinks.hostOf(a.url), mine.count { it.enabled }, mine.size, a.enabled)
        }
        return UiState(
            rows = rows,
            catalogOn = i.catalogOn,
            heroSource = hero,
            heroLabel = heroOptions.firstOrNull { it.key == hero }?.label ?: "Automático",
            heroOptions = heroOptions,
            addonBoxes = boxes,
            big = HomeLayout.bigAddon(layout, entries.keys, i.local.keep),
            kids = prefs.isKids,
            profile = prefs.profile.name,
            moving = i.local.draft?.key,
            sticky = i.local.sticky,
            groupOpen = i.local.groupOpen,
            groupLimit = i.local.groupLimit,
            focusNew = focusNew && fresh.isNotEmpty(),
            othersOn = layout.any { (it.ref != null || it.collectionId != null) && it.enabled },
        )
    }

    // ── Liga e desliga ───────────────────────────────────────────────────────

    /** A linha inteira é o interruptor. Sugestões desligadas e addon desligado pedem outra coisa. */
    fun tap(row: Row): Tap {
        if (row.hint == Hint.OPEN_ADDONS) return Tap.OpenAddons(row.addonUrl)
        if (row.hint == Hint.ENABLE_SUGGESTIONS && row.pref.enabled) {
            prefs.catalogOn = true
            return Tap.SuggestionsOn
        }
        val on = !row.pref.enabled
        setEnabled(row.key, on)
        return Tap.Toggled(row.title, row.key, on)
    }

    fun setEnabled(key: String, on: Boolean) {
        prefs.setHomeRowEnabled(key, on)
        // Esconder nesta visita não tira a linha do lugar, para o foco não pular no meio da lista.
        if (!on) local.update { it.copy(sticky = it.sticky + key) }
    }

    fun rename(key: String, title: String) = prefs.renameHomeRow(key, title)

    fun drop(key: String) = prefs.dropHomeRow(key)

    fun setCatalogOn(on: Boolean) { prefs.catalogOn = on }

    fun setHero(key: String) { prefs.heroSource = key }

    /** Volta ao padrão e devolve a foto de antes, para o Desfazer. */
    fun reset(): PlatformPrefs.HomeSnapshot {
        val s = prefs.homeSnapshot()
        prefs.resetHomeLayout()
        local.update { it.copy(sticky = emptySet(), draft = null) }
        return s
    }

    fun undoReset(s: PlatformPrefs.HomeSnapshot) = prefs.restoreHome(s)

    // ── Grupo Escondidas ─────────────────────────────────────────────────────

    fun openGroup() = local.update { it.copy(groupOpen = true, sticky = emptySet(), groupLimit = PAGE) }
    fun closeGroup() = local.update { it.copy(groupOpen = false) }
    fun showMore() = local.update { it.copy(groupLimit = it.groupLimit + PAGE) }

    // ── Aviso de addon grande ────────────────────────────────────────────────

    fun keepTen(base: String) = prefs.keepFirstHomeRows(base)

    fun keepAll(base: String) {
        val keep = local.value.keep + base
        prefs.homeKeep = keep
        local.update { it.copy(keep = keep) }
    }

    // ── Modo Mover ───────────────────────────────────────────────────────────

    fun startMove(key: String) = local.update { it.copy(draft = Draft(key, prefs.homeLayout)) }

    private fun pinnedKeys(): Set<String> =
        prefs.collectionsFlow.value.filter { it.pinToTop }.mapTo(HashSet()) { HomeLayout.COL_PREFIX + it.id }

    /** Troca com a vizinha que a tela mostra. Cima e Baixo do controle. */
    fun moveStep(delta: Int) = local.update { l ->
        val d = l.draft ?: return@update l
        val shown = state.value.main.mapTo(HashSet()) { it.key }
        val pinned = pinnedKeys()
        val next = HomeLayout.move(d.order, d.key, delta) { it.key == d.key || (it.key in shown && it.key !in pinned) }
        if (next === d.order) l else l.copy(draft = Draft(d.key, next))
    }

    /** Esquerda leva ao topo possível, Direita ao fim possível. */
    fun moveEdge(top: Boolean) = local.update { l ->
        val d = l.draft ?: return@update l
        val next = HomeLayout.moveTo(d.order, d.key, if (top) 0 else Int.MAX_VALUE, pinnedKeys())
        if (next === d.order) l else l.copy(draft = Draft(d.key, next))
    }

    /** Grava o lugar novo e devolve o número da fileira na Início (ou null quando não mudou). */
    fun commitMove(): Int? {
        val d = local.value.draft ?: return null
        val moved = d.order.map { it.key } != prefs.homeLayout.map { it.key }
        val n = state.value.numbers[d.key] ?: (d.order.indexOfFirst { it.key == d.key } + 1)
        if (moved) prefs.saveHomeOrder(d.order)
        local.update { it.copy(draft = null) }
        return if (moved) n else null
    }

    fun cancelMove() = local.update { it.copy(draft = null) }

    /** Telefone, Para o topo ou Para o fim num toque só. */
    fun moveNow(key: String, top: Boolean) = prefs.moveHomeRowTo(key, if (top) 0 else Int.MAX_VALUE)

    /** Fixa ou solta a coleção do topo. */
    fun setPinned(collectionId: String, pin: Boolean) =
        prefs.saveCollections(prefs.collectionsFlow.value.map { if (it.id == collectionId) it.copy(pinToTop = pin) else it })

    companion object {
        const val PAGE = 40
    }
}
