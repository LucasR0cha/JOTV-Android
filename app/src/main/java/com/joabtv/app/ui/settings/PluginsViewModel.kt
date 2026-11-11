package com.joabtv.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.joabtv.app.data.plugins.PluginField
import com.joabtv.app.data.plugins.PluginRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

@HiltViewModel
class PluginsViewModel @Inject constructor(val repository: PluginRepository) : ViewModel() {
    data class Editor(val id: String, val title: String, val fields: List<PluginField>, val values: String)
    data class Ui(val busy: Set<String> = emptySet(), val message: String? = null, val error: String? = null, val editor: Editor? = null)
    val state = repository.state
    private val _ui = MutableStateFlow(Ui())
    val ui = _ui.asStateFlow()
    init { repository.refreshDue() }
    fun perform(id: String, action: suspend () -> String?) {
        if (id in _ui.value.busy) return
        _ui.value = _ui.value.copy(busy = _ui.value.busy + id, message = null, error = null)
        viewModelScope.launch {
            try { val message = action(); _ui.value = _ui.value.copy(message = message) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _ui.value = _ui.value.copy(error = e.message?.take(300) ?: "Não foi possível concluir. Tente novamente.") }
            finally { _ui.value = _ui.value.copy(busy = _ui.value.busy - id) }
        }
    }
    fun install(url: String) = perform("install") { val r = repository.install(url); if (r.kind == "stremio") "Addon instalado. Ele está disponível em Addons Stremio." else "${r.name} instalado. Ative os provedores que deseja usar." }
    fun refresh(url: String) = perform(url) { repository.refresh(url); "Repositório atualizado." }
    fun refreshAll() = perform("refresh") { var failed = 0; repository.state.value.repos.forEach { try { repository.refresh(it.url) } catch (e: CancellationException) { throw e } catch (_: Exception) { failed++ } }; if (failed == 0) "Repositórios atualizados." else "$failed repositórios não responderam. Os dados anteriores foram preservados." }
    fun edit(id: String) = perform("settings:$id") { val fields = repository.fields(id); val p = repository.find(id)?.second ?: return@perform null; _ui.value = _ui.value.copy(editor = Editor(id, p.name, fields, p.settings)); null }
    fun test(id: String) = perform("test:$id") { repository.fields(id); "Plugin carregado e configurações disponíveis." }
    fun save(values: JSONObject) { val editor = _ui.value.editor ?: return; try { repository.saveSettings(editor.id, values); _ui.value = _ui.value.copy(editor = null, message = "Configurações salvas.", error = null) } catch (e: Exception) { _ui.value = _ui.value.copy(error = e.message) } }
    fun closeEditor() { _ui.value = _ui.value.copy(editor = null) }
    fun backup() = repository.backup()
    fun restore(value: String) = perform("restore") { val count = repository.restore(value); if (count == 0) "Backup restaurado. Configure novamente suas senhas e sessões." else "Backup restaurado com $count repositórios não disponíveis." }
}
