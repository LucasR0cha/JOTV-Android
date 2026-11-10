package com.joabtv.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.joabtv.app.data.accounts.Account
import com.joabtv.app.data.accounts.AccountSnap
import com.joabtv.app.data.accounts.AccountsHub
import com.joabtv.app.data.accounts.ImportLog
import com.joabtv.app.data.accounts.ImportPart
import com.joabtv.app.data.accounts.ImportRun
import com.joabtv.app.data.accounts.NUVIO_MAIN
import com.joabtv.app.data.accounts.NuvioAccountService
import com.joabtv.app.data.accounts.NuvioProfile
import com.joabtv.app.data.accounts.NuvioProfiles
import com.joabtv.app.data.accounts.PartRecord
import com.joabtv.app.data.accounts.StremioAccountService
import com.joabtv.app.data.local.PlatformPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Contas Stremio e Nuvio nas Configurações e na tela Contas. Cuida de entrar (código no celular ou e-mail e
 * senha), que termina no passo Trazer tudo sem fechar o diálogo. Quem traz as partes é o [AccountsHub],
 * que segue trabalhando mesmo se a pessoa sair da tela.
 */
@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val stremio: StremioAccountService,
    private val nuvio: NuvioAccountService,
    private val hub: AccountsHub,
    private val prefs: PlatformPrefs,
) : ViewModel() {

    /**
     * O diálogo de entrada. Passo 1 é o código para confirmar no celular, depois vem o passo 2 (Trazer tudo)
     * no mesmo diálogo. [intent] é a parte que a pessoa veio buscar (Coleções, vindo do cartão de coleções).
     */
    sealed interface LoginFlow {
        val account: Account
        val intent: ImportPart?

        data class Code(
            override val account: Account, val code: String, val url: String, override val intent: ImportPart?, val expired: Boolean = false,
        ) : LoginFlow

        data class Email(override val account: Account, override val intent: ImportPart?, val error: String? = null) : LoginFlow

        data class Step(override val account: Account, override val intent: ImportPart?) : LoginFlow
    }

    data class Ui(
        val nuvio: AccountSnap = AccountSnap(),
        val stremio: AccountSnap = AccountSnap(),
        val log: ImportLog = ImportLog(),
        val running: Map<String, ImportRun> = emptyMap(),
        val profiles: NuvioProfiles = NuvioProfiles(),
        val pick: NuvioProfile = NUVIO_MAIN,
        /** Perfil do JOTV que recebe o que vem das contas. */
        val profileName: String = "",
        val login: LoginFlow? = null,
        /** Entrada em andamento ("nuvio_code", "stremio_login"...). Trava o segundo toque. */
        val busy: String? = null,
    ) {
        fun snap(a: Account) = if (a == Account.NUVIO) nuvio else stremio
    }

    private data class Local(val login: LoginFlow? = null, val busy: String? = null)

    private val local = MutableStateFlow(Local())

    private fun build(l: Local) = Ui(
        hub.nuvioState.value, hub.stremioState.value, prefs.importLog, hub.running.value, hub.nuvioProfiles.value,
        hub.nuvioPick(), prefs.profile.name, l.login, l.busy,
    )

    val ui: StateFlow<Ui> = combine(
        combine(hub.nuvioState, hub.stremioState, prefs.importLogFlow, hub.running, hub.nuvioProfiles) { n, s, log, run, pr ->
            Ui(n, s, log, run, pr, hub.nuvioPick(), prefs.profile.name)
        },
        local, prefs.profileId,
    ) { base, l, _ -> base.copy(profileName = prefs.profile.name, login = l.login, busy = l.busy) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, build(Local()))

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Avisos curtos para quem está olhando outra coisa. O resultado de verdade fica em O que veio. */
    val toasts: SharedFlow<String> = _toasts

    private var codeJob: Job? = null

    init { hub.loadNuvioProfiles() }

    // ── Entrar ──────────────────────────────────────────────────────────────────

    /** Passo 1. Pede o código e espera a pessoa confirmar no celular. */
    fun startCode(a: Account, intent: ImportPart? = null) {
        if (local.value.busy != null) return
        local.update { it.copy(busy = "${a.key}_code") }
        codeJob?.cancel()
        codeJob = viewModelScope.launch {
            if (a == Account.NUVIO) nuvioCodeLoop(intent) else stremioCodeLoop(intent)
        }
    }

    private suspend fun nuvioCodeLoop(intent: ImportPart?) {
        val code = nuvio.deviceStart(NuvioAccountService.OFFICIAL).getOrElse { e -> failed(e.message); return }
        local.update { it.copy(login = LoginFlow.Code(Account.NUVIO, code.userCode, code.url, intent)) }
        repeat(120) {
            delay(code.intervalSec * 1000L)
            val r = nuvio.devicePoll(code)
            if (r.isFailure) { expired(); return }
            if (r.getOrNull() != null) { signedIn(Account.NUVIO, intent); return }
        }
        expired()
    }

    private suspend fun stremioCodeLoop(intent: ImportPart?) {
        val link = stremio.linkStart().getOrElse { e -> failed(e.message); return }
        local.update { it.copy(login = LoginFlow.Code(Account.STREMIO, link.code, link.url, intent)) }
        val until = System.currentTimeMillis() + 5 * 60_000
        while (System.currentTimeMillis() < until) {
            delay(3_000)
            if (stremio.linkPoll(link) != null) { signedIn(Account.STREMIO, intent); return }
        }
        expired()
    }

    private fun failed(message: String?) {
        local.update { it.copy(busy = null, login = null) }
        _toasts.tryEmit(message ?: "Não deu para gerar o código agora")
    }

    /** Código vencido. O diálogo fica aberto com "Gerar outro". */
    private fun expired() {
        local.update { l -> l.copy(busy = null, login = (l.login as? LoginFlow.Code)?.copy(expired = true) ?: l.login) }
    }

    private fun signedIn(a: Account, intent: ImportPart?) {
        hub.accountChanged(a)
        local.update { it.copy(busy = null, login = LoginFlow.Step(a, intent)) }
    }

    /** "Usar e-mail e senha". Fecha o código e abre os campos. */
    fun startEmail(a: Account, intent: ImportPart? = null) {
        codeJob?.cancel()
        local.update { it.copy(busy = null, login = LoginFlow.Email(a, intent)) }
    }

    fun emailLogin(a: Account, email: String, password: String, server: String) {
        val cur = local.value.login as? LoginFlow.Email ?: return
        if (local.value.busy != null) return
        local.update { it.copy(busy = "${a.key}_login", login = cur.copy(error = null)) }
        viewModelScope.launch {
            val r = if (a == Account.NUVIO) nuvio.login(email, password, server.ifBlank { NuvioAccountService.OFFICIAL }).map { }
            else stremio.login(email, password).map { }
            r.fold(
                onSuccess = { signedIn(a, cur.intent) },
                onFailure = { e -> local.update { it.copy(busy = null, login = cur.copy(error = e.message ?: "Não deu para entrar")) } },
            )
        }
    }

    /** Fecha o diálogo de entrada, em qualquer passo. */
    fun closeLogin() {
        codeJob?.cancel()
        local.update { it.copy(busy = null, login = null) }
    }

    fun logout(a: Account) {
        if (a == Account.NUVIO) nuvio.logout() else stremio.logout()
        hub.accountChanged(a)
    }

    // ── Trazer ──────────────────────────────────────────────────────────────────

    /** Quem pede acompanha pelo resultado. Segunda chamada com a conta já trazendo devolve a mesma importação. */
    fun runImport(a: Account, parts: List<ImportPart> = a.parts): Deferred<Map<ImportPart, PartRecord>> = hub.runImport(a, parts)

    /** Traz e avisa no fim com um toast curto. É o jeito dos botões do cartão e do painel Mais. */
    fun runAndTell(a: Account, parts: List<ImportPart> = a.parts) {
        viewModelScope.launch { _toasts.tryEmit(toastOf(hub.runImport(a, parts).await())) }
    }

    /** "Ver minha Início". A Minha Início abre na primeira fileira nova, que acabou de chegar. */
    fun openHomeOnNew() { prefs.homeFocusNew = true }

    fun pickNuvioProfile(p: NuvioProfile) = hub.setNuvioPick(p)

    fun retryProfiles() = hub.loadNuvioProfiles()

    /** Manda os addons do JOTV para a conta Stremio. Devolve o texto do resultado. */
    suspend fun pushStremioAddons(): String {
        val n = stremio.pushAddons(prefs.addonEntries.filter { it.enabled }.map { it.url }).getOrThrow()
        return if (n == 0) "A conta Stremio já tinha todos os seus addons" else "$n addons enviados para a conta Stremio"
    }

    private fun toastOf(res: Map<ImportPart, PartRecord>): String {
        val bad = res.values.count { !it.ok }
        return when {
            bad == 0 -> "Pronto. Veja em O que veio"
            bad < res.size -> "Veio quase tudo. Veja em O que veio"
            else -> "Não veio nada. Veja em O que veio"
        }
    }
}
