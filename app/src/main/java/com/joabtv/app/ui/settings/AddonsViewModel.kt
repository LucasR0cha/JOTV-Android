package com.joabtv.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.joabtv.app.data.local.AddonEntry
import com.joabtv.app.data.local.PlatformPrefs
import com.joabtv.app.data.remote.AddonManifest
import com.joabtv.app.data.remote.StremioAddonService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Gerenciador de addons Stremio. A ORDEM da lista é a prioridade (o 1º é tentado primeiro),
 * e cada addon pode ser ativado/desativado sem precisar remover.
 */
@HiltViewModel
class AddonsViewModel @Inject constructor(
    private val prefs: PlatformPrefs,
    private val stremio: StremioAddonService,
) : ViewModel() {

    data class UiState(
        val addons: List<AddonEntry> = emptyList(),
        /** manifest por URL (nome, logo, descrição). Ausente = ainda carregando ou offline. */
        val manifests: Map<String, AddonManifest> = emptyMap(),
        val loading: Set<String> = emptySet(),
        /** URLs que não responderam (mostra aviso de offline no card). */
        val offline: Set<String> = emptySet(),
        val installing: Boolean = false,
        val error: String? = null,
        val installedNow: String? = null,
        /** Chaves (base|tipo|id) dos catálogos que viram fileira na Início. */
        val homeCatalogKeys: Set<String> = emptySet(),
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        _uiState.update { it.copy(addons = prefs.addonEntries) }
        refreshAll()
        // A loja instala e remove por fora. Observa a lista para o gerenciador acompanhar.
        viewModelScope.launch {
            prefs.addons.collect { list ->
                _uiState.update { it.copy(addons = list) }
                list.filter { it.url !in _uiState.value.manifests }.forEach { fetchManifest(it.url) }
            }
        }
        viewModelScope.launch {
            prefs.homeCatalogsFlow.collect { l -> _uiState.update { it.copy(homeCatalogKeys = l.mapTo(HashSet()) { c -> c.key }) } }
        }
    }

    private fun persist(list: List<AddonEntry>) {
        prefs.addonEntries = list
        _uiState.update { it.copy(addons = list) }
    }

    /** Marca ou desmarca um catálogo do addon como fileira da Início. Local, sem servidor. */
    fun toggleHomeCatalog(addonUrl: String, addonName: String, c: com.joabtv.app.data.remote.AddonCatalog) {
        prefs.toggleHomeCatalog(
            com.joabtv.app.data.local.HomeCatalogRef(
                com.joabtv.app.data.remote.AddonLinks.baseOf(addonUrl), c.type, c.id, c.name, addonName,
            )
        )
    }

    /** Baixa o manifest de cada addon instalado (nome/logo/descrição) em paralelo. */
    fun refreshAll() {
        _uiState.value.addons.forEach { fetchManifest(it.url) }
    }

    private fun fetchManifest(url: String) {
        if (_uiState.value.loading.contains(url)) return
        _uiState.update { it.copy(loading = it.loading + url) }
        viewModelScope.launch {
            val m = stremio.getManifest(url)
            _uiState.update { st ->
                st.copy(
                    manifests = if (m != null) st.manifests + (url to m) else st.manifests,
                    offline = if (m == null) st.offline + url else st.offline - url,
                    loading = st.loading - url,
                )
            }
        }
    }

    /** Instala por URL de manifest (aceita stremio:// e URL sem /manifest.json, igual o Stremio). */
    fun install(raw: String) {
        val url = stremio.normalizeManifestUrl(raw)
        if (url.isBlank()) {
            _uiState.update { it.copy(error = "Endereço inválido") }
            return
        }
        if (_uiState.value.addons.any { it.url.equals(url, ignoreCase = true) }) {
            _uiState.update { it.copy(error = "Esse addon já está instalado") }
            return
        }
        _uiState.update { it.copy(installing = true, error = null, installedNow = null) }
        viewModelScope.launch {
            val m = stremio.getManifest(url)
            if (m == null) {
                _uiState.update { it.copy(installing = false, error = "Não consegui ler o manifest desse endereço") }
                return@launch
            }
            // Mesmo critério que installAddon usa de verdade (URL OU id do manifest). Sem isso,
            // colar uma 2ª URL do mesmo addon (outra config) substituía o 1º calado, avisando
            // "instalado" quando na real trocou.
            val replacing = prefs.addonIndex(url, m.id) >= 0
            prefs.installAddon(url, m.id)
            _uiState.update {
                it.copy(
                    installing = false,
                    manifests = it.manifests + (url to m),
                    offline = it.offline - url,
                    installedNow = if (replacing) "Já tinha esse addon com outro endereço. Troquei para ${m.name.ifBlank { url }}"
                    else m.name.ifBlank { url },
                )
            }
        }
    }

    fun remove(url: String) = prefs.removeAddon(url)

    /**
     * Troca a URL de um addon já instalado, MANTENDO a posição na lista e o ligado/desligado.
     * Serve pra quando o endereço muda (token novo, host novo) sem ter que remover e recolocar
     * no fim da fila. Valida o manifest antes de gravar, igual a instalação.
     */
    fun editUrl(oldUrl: String, rawNew: String) {
        val url = stremio.normalizeManifestUrl(rawNew)
        if (url.isBlank()) {
            _uiState.update { it.copy(error = "Endereço inválido.") }
            return
        }
        if (url == oldUrl) return
        if (_uiState.value.addons.any { it.url == url }) {
            _uiState.update { it.copy(error = "Já existe um addon com esse endereço.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(installing = true, error = null) }
            val m = stremio.getManifest(url)
            if (m == null) {
                _uiState.update { it.copy(installing = false, error = "Esse endereço não respondeu como addon.") }
                return@launch
            }
            val list = _uiState.value.addons.map {
                if (it.url == oldUrl) it.copy(url = url, id = m.id.ifBlank { it.id }) else it
            }
            // A base antiga pode ter fileiras mortas na Início (token vencido na URL velha).
            // Some elas daqui; o usuário marca de novo se quiser (mesmo cálculo do removeAddon).
            val oldBase = com.joabtv.app.data.remote.AddonLinks.baseOf(oldUrl)
            if (oldBase != com.joabtv.app.data.remote.AddonLinks.baseOf(url)) {
                prefs.homeCatalogs = prefs.homeCatalogs.filterNot { it.base == oldBase }
            }
            _uiState.update {
                it.copy(
                    installing = false,
                    manifests = it.manifests - oldUrl + (url to m),
                    offline = it.offline - oldUrl,
                    installedNow = m.name.ifBlank { url },
                )
            }
            persist(list)
        }
    }

    fun toggle(url: String) = persist(
        _uiState.value.addons.map { if (it.url == url) it.copy(enabled = !it.enabled) else it }
    )

    /** Sobe o addon na prioridade (mais perto do topo = tentado antes). */
    fun moveUp(index: Int) {
        val list = _uiState.value.addons.toMutableList()
        if (index <= 0 || index >= list.size) return
        val t = list[index]; list[index] = list[index - 1]; list[index - 1] = t
        persist(list)
    }

    fun moveDown(index: Int) {
        val list = _uiState.value.addons.toMutableList()
        if (index < 0 || index >= list.size - 1) return
        val t = list[index]; list[index] = list[index + 1]; list[index + 1] = t
        persist(list)
    }

    fun clearError() = _uiState.update { it.copy(error = null, installedNow = null) }
}
