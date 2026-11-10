package com.joabtv.app.ui.collections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.joabtv.app.data.accounts.Account
import com.joabtv.app.data.accounts.AccountsHub
import com.joabtv.app.data.accounts.ImportPart
import com.joabtv.app.data.collections.CollectionsRepository
import com.joabtv.app.data.collections.MediaCollection
import com.joabtv.app.data.local.PlatformPrefs
import com.joabtv.app.ui.settings.whenText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

@HiltViewModel
class CollectionsViewModel @Inject constructor(
    private val repo: CollectionsRepository,
    private val hub: AccountsHub,
    private val prefs: PlatformPrefs,
    private val http: OkHttpClient,
) : ViewModel() {

    /**
     * [busy] é a importação em andamento ("paste", "url" ou "nuvio"). [nuvioPulled] é o "Trazidas do Nuvio hoje às
     * 14h32, perfil Principal" quando o registro tem as coleções. [nuvioRunning] vale também quando a importação
     * veio de outra tela (o cartão do catálogo ou o diálogo de entrada).
     */
    data class Ui(
        val collections: List<MediaCollection> = emptyList(),
        val busy: String? = null,
        val message: String? = null,
        val error: String? = null,
        val nuvioLogged: Boolean = false,
        val nuvioPulled: String? = null,
        val nuvioPulledOk: Boolean = false,
        val nuvioRunning: Boolean = false,
        val profileName: String = "",
    ) {
        /** O primeiro botão segue a conta Nuvio. Entrar, Trazer (nunca trouxe) ou Atualizar (já trouxe). */
        val nuvioLabel: String
            get() = when {
                !nuvioLogged -> "Entrar no Nuvio"
                nuvioPulledOk -> "Atualizar do Nuvio"
                else -> "Trazer do Nuvio"
            }
    }

    private val local = MutableStateFlow(Ui())

    private fun fold(l: Ui): Ui {
        val log = prefs.importLog[Account.NUVIO]
        val rec = log?.part(ImportPart.COLLECTIONS)
        val logged = hub.nuvioState.value.signedIn
        return l.copy(
            collections = repo.list, nuvioLogged = logged,
            nuvioPulled = if (logged && rec != null && rec.ok && rec.n > 0) {
                "Trazidas do Nuvio ${whenText(rec.at.takeIf { it > 0 } ?: log.at)}" + if (log.profile.isNotBlank()) ", perfil ${log.profile}" else ""
            } else null,
            nuvioPulledOk = logged && rec != null && rec.ok,
            nuvioRunning = hub.running.value[Account.NUVIO.key]?.parts?.contains(ImportPart.COLLECTIONS) == true,
            profileName = prefs.profile.name,
        )
    }

    val ui: StateFlow<Ui> = combine(repo.flow, local, hub.nuvioState, prefs.importLogFlow, hub.running) { _, l, _, _, _ -> fold(l) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, fold(Ui()))

    fun move(id: String, delta: Int) = repo.move(id, delta)

    fun remove(id: String) {
        val title = repo.get(id)?.title.orEmpty()
        repo.remove(id)
        local.update { it.copy(message = "Coleção $title apagada", error = null) }
    }

    fun togglePin(id: String) { repo.get(id)?.let { repo.upsert(it.copy(pinToTop = !it.pinToTop)) } }

    fun exportJson(): String = repo.exportJson()

    fun importText(text: String) = act("paste") { imported(repo.importJson(text).getOrThrow()) }

    fun importUrl(url: String) = act("url") {
        val u = url.trim()
        require(u.startsWith("https://", true) || u.startsWith("http://", true)) { "O link precisa começar com https://" }
        val text = fetch(u) ?: error("Não deu para abrir esse link")
        imported(repo.importJson(text).getOrThrow())
    }

    /** Traz do perfil do Nuvio escolhido na conta (o mesmo que o cartão e o diálogo de entrada usam). Grava no registro. */
    fun importNuvio() = act("nuvio") {
        val rec = hub.runImport(Account.NUVIO, listOf(ImportPart.COLLECTIONS)).await()[ImportPart.COLLECTIONS]
            ?: error("Não deu para trazer agora")
        if (!rec.ok) error(rec.why.ifBlank { "As coleções do Nuvio não vieram" })
        if (rec.n == 0) "O Nuvio não tem coleções neste perfil" else imported(rec.n) + " do perfil ${hub.nuvioPick().name} do Nuvio"
    }

    private fun imported(n: Int) = if (n == 1) "1 coleção trazida" else "$n coleções trazidas"

    fun clearMessage() = local.update { it.copy(message = null, error = null) }

    private fun act(key: String, block: suspend () -> String) {
        if (local.value.busy != null) return
        local.update { it.copy(busy = key, message = null, error = null) }
        viewModelScope.launch {
            val r = runCatching { block() }
            local.update { it.copy(busy = null, message = r.getOrNull(), error = r.exceptionOrNull()?.let { e -> e.message ?: "Não deu certo" }) }
        }
    }

    /** Baixa o texto com teto de tamanho. O texto de coleção é pequeno, e arquivo enorme derrubaria o app. */
    private suspend fun fetch(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val src = r.body?.source() ?: return@use null
                if (src.request(MAX_BYTES + 1L)) return@use null
                src.readUtf8()
            }
        }.getOrNull()
    }

    private companion object {
        const val MAX_BYTES = 2L * 1024 * 1024
    }
}
