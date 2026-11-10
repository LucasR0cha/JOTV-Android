package com.joabtv.app.data.accounts

import com.joabtv.app.data.collections.CollectionsRepository
import com.joabtv.app.data.debrid.DebridProvider
import com.joabtv.app.data.debrid.DebridService
import com.joabtv.app.data.local.PlatformPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Uma importação em andamento. [done] guarda o que as partes já terminadas trouxeram. */
data class ImportRun(
    val parts: List<ImportPart>,
    val index: Int = 0,
    val detail: String = "",
    val done: Map<ImportPart, PartRecord> = emptyMap(),
) {
    val current: ImportPart get() = parts[index.coerceIn(0, parts.lastIndex)]

    /** "Trazendo coleções, 3 de 4 partes". Com uma parte só, "Trazendo coleções". */
    val text: String get() = "Trazendo ${current.what}" + if (parts.size > 1) ", ${index + 1} de ${parts.size} partes" else ""
}

enum class Load { IDLE, LOADING, OK, FAIL }

/** Perfil principal do Nuvio, o que vale quando a lista ainda não chegou ou não deu para buscar. */
val NUVIO_MAIN = NuvioProfile(1, "Principal", false)

/** Perfis da conta Nuvio. [list] fica no Principal enquanto a busca não termina ou quando ela falha. */
data class NuvioProfiles(val state: Load = Load.IDLE, val list: List<NuvioProfile> = listOf(NUVIO_MAIN))

/** O que a tela sabe de uma conta. [since] é a entrada feita depois do registro de importação. */
data class AccountSnap(val signedIn: Boolean = false, val email: String = "", val lost: Boolean = false, val since: Boolean = false)

/**
 * Traz o que está nas contas Stremio e Nuvio para o perfil ativo, parte por parte. Vive fora das telas
 * (escopo próprio), então sair das Configurações no meio não cancela a importação. Cada parte grava o
 * resultado no registro de importação do perfil assim que termina. Importar só soma, nada é apagado.
 */
@Singleton
class AccountsHub @Inject constructor(
    private val stremio: StremioAccountService,
    private val nuvio: NuvioAccountService,
    private val importer: AccountImporter,
    private val debrid: DebridService,
    private val prefs: PlatformPrefs,
    private val collections: CollectionsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _running = MutableStateFlow<Map<String, ImportRun>>(emptyMap())
    /** Importação de cada conta, por Account.key. Sem entrada, a conta está parada. */
    val running: StateFlow<Map<String, ImportRun>> = _running.asStateFlow()
    private val jobs = HashMap<String, Deferred<Map<ImportPart, PartRecord>>>()

    private val _profiles = MutableStateFlow(NuvioProfiles())
    val nuvioProfiles: StateFlow<NuvioProfiles> = _profiles.asStateFlow()

    private fun snapOf(s: Any?, email: String, lost: Boolean, since: Long) = AccountSnap(s != null, email, lost, since > 0)

    /** Estado de cada conta para as telas. Muda quando entra, sai ou o serviço derruba a sessão. */
    val nuvioState: StateFlow<AccountSnap> = combine(nuvio.session, nuvio.lost, nuvio.since) { s, l, t -> snapOf(s, s?.email.orEmpty(), l, t) }
        .stateIn(scope, SharingStarted.Eagerly, snapOf(nuvio.session.value, nuvio.session.value?.email.orEmpty(), nuvio.lost.value, nuvio.since.value))
    val stremioState: StateFlow<AccountSnap> = combine(stremio.session, stremio.lost, stremio.since) { s, l, t -> snapOf(s, s?.email.orEmpty(), l, t) }
        .stateIn(scope, SharingStarted.Eagerly, snapOf(stremio.session.value, stremio.session.value?.email.orEmpty(), stremio.lost.value, stremio.since.value))

    /**
     * Traz as partes pedidas, uma de cada vez. A parte que falha fica no registro com o motivo e não para as
     * seguintes. Segunda chamada com a conta já trazendo devolve o mesmo trabalho, então o segundo toque não
     * dispara outra importação.
     */
    @Synchronized
    fun runImport(a: Account, parts: List<ImportPart> = a.parts): Deferred<Map<ImportPart, PartRecord>> {
        jobs[a.key]?.takeIf { it.isActive }?.let { return it }
        val list = parts.filter { it in a.parts }.ifEmpty { a.parts }
        _running.update { it + (a.key to ImportRun(list)) }
        return scope.async { doRun(a, list) }.also { jobs[a.key] = it }
    }

    private suspend fun doRun(a: Account, parts: List<ImportPart>): Map<ImportPart, PartRecord> {
        val prof = if (a == Account.NUVIO) nuvioPick() else null
        val email = (if (a == Account.NUVIO) nuvio.session.value?.email else stremio.session.value?.email).orEmpty()
        val results = LinkedHashMap<ImportPart, PartRecord>()
        try {
            parts.forEachIndexed { i, p ->
                _running.update { it + (a.key to ImportRun(parts, i, "", results.toMap())) }
                // Cada item traz um aviso de andamento. A tela só precisa de uns poucos por segundo.
                var last = 0L
                val rec = try {
                    partJob(a, p, prof) { d ->
                        val t = System.nanoTime()
                        if (t - last >= 150_000_000L) {
                            last = t
                            _running.update { it + (a.key to ImportRun(parts, i, d, results.toMap())) }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    PartRecord(ok = false, why = e.message?.takeIf { it.isNotBlank() } ?: "Não respondeu")
                }
                val at = System.currentTimeMillis()
                results[p] = rec.copy(at = at)
                prefs.recordImport(a.key, p.key, rec, email, prof?.name.orEmpty(), at)
            }
        } finally {
            _running.update { it - a.key }
        }
        return results
    }

    private fun addonRows() = prefs.homeLayout.count { it.ref != null }
    private fun addonRowsOn() = prefs.homeLayout.count { it.ref != null && it.enabled }

    private suspend fun partJob(a: Account, p: ImportPart, prof: NuvioProfile?, step: (String) -> Unit): PartRecord = when (p) {
        ImportPart.ADDONS -> {
            val rows = addonRows()
            val rowsOn = addonRowsOn()
            val list = if (a == Account.NUVIO) nuvio.addons(prof!!).getOrThrow() else stremio.addons().getOrThrow()
            val r = importer.importAddons(list) { d, t -> step("$d de $t") }
            val unusable = list.count { !it.canUse }
            PartRecord(
                ok = true, n = r.added, skip = r.skipped - unusable, failed = r.failed, unusable = unusable,
                rows = (addonRows() - rows).coerceAtLeast(0), rowsOn = (addonRowsOn() - rowsOn).coerceAtLeast(0),
            )
        }
        ImportPart.LIBRARY -> {
            val (list, progress) =
                if (a == Account.NUVIO) nuvio.library(prof!!).getOrThrow() to nuvio.progress(prof).getOrThrow()
                else stremio.library().getOrThrow()
            val l = importer.importLibrary(list) { d, t -> step("$d de $t") }
            val w = importer.importProgress(progress) { d, t -> step("progresso $d de $t") }
            PartRecord(ok = true, list = l.added + l.skipped, progress = w.added + w.skipped, nomatch = l.failed + w.failed)
        }
        // Perfil do Nuvio sem coleção não é falha, só não tem o que trazer.
        ImportPart.COLLECTIONS -> {
            val json = nuvio.collectionsJson(prof!!).getOrThrow()
            PartRecord(ok = true, n = if (json == null) 0 else collections.importJson(json).getOrThrow())
        }
        // Cada chave é conferida no serviço antes de entrar.
        ImportPart.DEBRID -> {
            val keys = nuvio.debridKeys(prof!!).getOrThrow()
            if (keys.isEmpty()) PartRecord(ok = true)
            else {
                val ok = keys.mapNotNull { (id, key) -> DebridProvider.of(id)?.let { dp -> if (debrid.connectKey(dp, key).isSuccess) dp.label else null } }
                if (ok.isEmpty()) PartRecord(ok = false, why = "As chaves da conta Nuvio não foram aceitas pelos serviços")
                else PartRecord(ok = true, label = ok.joinToString(", "))
            }
        }
    }

    // ── Perfis do Nuvio ─────────────────────────────────────────────────────────

    /** Busca os perfis uma vez por sessão. Falha deixa a lista no Principal e o estado em FAIL (dá para tentar de novo). */
    @Synchronized
    fun loadNuvioProfiles() {
        val st = _profiles.value.state
        if (nuvio.session.value == null || st == Load.LOADING || st == Load.OK) return
        _profiles.value = NuvioProfiles(Load.LOADING)
        scope.launch {
            val r = nuvio.profiles()
            _profiles.value = r.fold(
                onSuccess = { l -> NuvioProfiles(Load.OK, l.ifEmpty { listOf(NUVIO_MAIN) }) },
                onFailure = { NuvioProfiles(Load.FAIL) },
            )
        }
    }

    fun resetNuvioProfiles() { _profiles.value = NuvioProfiles() }

    /** Perfil do Nuvio de onde vem o que é trazido. O escolhido, se ainda existe, senão o de número mais baixo. */
    fun nuvioPick(): NuvioProfile {
        val p = prefs.importLog.pick
        val cur = _profiles.value
        if (cur.state == Load.OK) return cur.list.firstOrNull { it.index == p?.index } ?: cur.list.first()
        return p?.let { NuvioProfile(it.index, it.name, it.primaryAddons) } ?: NUVIO_MAIN
    }

    fun setNuvioPick(p: NuvioProfile) = prefs.setNuvioPick(NuvioPick(p.index, p.name, p.usesPrimaryAddons))

    /** Chamado quando a pessoa entra ou sai de uma conta. A lista de perfis do Nuvio vale por sessão. */
    fun accountChanged(a: Account) {
        if (a != Account.NUVIO) return
        resetNuvioProfiles()
        loadNuvioProfiles()
    }
}
