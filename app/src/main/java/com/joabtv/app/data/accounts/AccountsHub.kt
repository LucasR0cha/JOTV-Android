package com.joabtv.app.data.accounts

import com.joabtv.app.data.collections.CollectionsRepository
import com.joabtv.app.data.debrid.DebridProvider
import com.joabtv.app.data.debrid.DebridService
import com.joabtv.app.data.local.PlatformPrefs
import com.joabtv.app.data.local.JotvProfileLink
import com.joabtv.app.data.local.MAX_PROFILES
import com.joabtv.app.data.local.NuvioPlan
import com.joabtv.app.data.local.ProfileSets
import com.joabtv.app.data.local.nearestProfileColor
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

/** Para qual perfil do JOTV vai uma importação. */
sealed interface ImportTarget {
    /** O perfil ativo. */
    object Current : ImportTarget
    /** Um perfil novo, criado na hora (com nome, foto e cor da Nuvio quando vem de lá). */
    object NewProfile : ImportTarget
    data class Existing(val profileId: String) : ImportTarget
}

enum class BatchState { WAITING, RUNNING, DONE, FAILED }

/** Um perfil dentro da importação em lote. [nuvioIndex] fica null no Stremio. */
data class BatchItem(
    val nuvioIndex: Int?,
    val nuvioName: String,
    val profileId: String,
    val profileName: String,
    val state: BatchState = BatchState.WAITING,
    val results: Map<ImportPart, PartRecord> = emptyMap(),
)

/**
 * Importação em lote que a tela observa. [leftOut] conta os perfis da Nuvio que não couberam no limite de perfis.
 * [error] vem quando o plano nem começou (sem lista de perfis, perfil de destino sumiu, limite atingido).
 */
data class BatchImport(
    val account: Account,
    val items: List<BatchItem> = emptyList(),
    val leftOut: Int = 0,
    val running: Boolean = true,
    val error: String = "",
) {
    /** Repositórios de plugins que entraram em todos os perfis (os provedores deles estão desligados). */
    val pluginRepos: Int get() = items.sumOf { it.results[ImportPart.PLUGINS]?.n ?: 0 }
}

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
    private val plugins: com.joabtv.app.data.plugins.PluginRepository,
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
    fun runImport(a: Account, parts: List<ImportPart> = a.parts, nuvioProfile: NuvioProfile? = null): Deferred<Map<ImportPart, PartRecord>> {
        jobs[a.key]?.takeIf { it.isActive }?.let { return it }
        batchJobs[a.key]?.takeIf { it.isActive }?.let { b -> return scope.async { b.await(); emptyMap() } }
        val target = prefs.profileId.value
        val list = partsFor(a, parts, target)
        // A escolha feita no diálogo só vira o perfil da Nuvio do perfil ativo quando a importação começa de verdade.
        val prof = if (a == Account.NUVIO) (nuvioProfile?.also { setNuvioPick(it, target) } ?: nuvioPick()) else null
        _running.update { it + (a.key to ImportRun(list)) }
        return scope.async { doRun(a, list, target, prof) }.also { jobs[a.key] = it }
    }

    private fun emailOf(a: Account) = (if (a == Account.NUVIO) nuvio.session.value?.email else stremio.session.value?.email).orEmpty()

    /** Nome e foto só entram no Trazer tudo da primeira vez com esta conta neste perfil. Depois só pedindo a parte sozinha. */
    private fun partsFor(a: Account, parts: List<ImportPart>, target: String): List<ImportPart> {
        val list = parts.filter { it in a.parts }.ifEmpty { a.parts }
        val before = prefs.importLogOf(target)[a]
        return if (list == a.parts && ImportPart.PROFILE in list && before?.part(ImportPart.PROFILE)?.ok == true &&
            before.email.equals(emailOf(a), true)
        ) list - ImportPart.PROFILE else list
    }

    private suspend fun doRun(a: Account, parts: List<ImportPart>, target: String, prof: NuvioProfile?): Map<ImportPart, PartRecord> {
        val email = emailOf(a)
        // Perfil que usa os dados de outro deixa de usar (vira cópia) antes de receber. Sem isso a importação
        // gravaria no dono, e o perfil importado não ficaria independente.
        prefs.detachForWrite(target, parts.flatMap { it.writes })
        val results = LinkedHashMap<ImportPart, PartRecord>()
        try {
            parts.forEachIndexed { i, p ->
                _running.update { it + (a.key to ImportRun(parts, i, "", results.toMap())) }
                // Cada item traz um aviso de andamento. A tela só precisa de uns poucos por segundo.
                var last = 0L
                val rec = try {
                    partJob(a, p, prof, target) { d ->
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
                prefs.recordImport(a.key, p.key, rec, email, prof?.name.orEmpty(), at, target)
            }
        } finally {
            _running.update { it - a.key }
        }
        return results
    }

    private fun addonRows(target: String) = prefs.homeLayoutOf(target).count { it.ref != null }
    private fun addonRowsOn(target: String) = prefs.homeLayoutOf(target).count { it.ref != null && it.enabled }

    private suspend fun partJob(a: Account, p: ImportPart, prof: NuvioProfile?, target: String, step: (String) -> Unit): PartRecord = when (p) {
        // Nome, foto e cor do perfil do Nuvio passam para o perfil de destino do JOTV.
        ImportPart.PROFILE -> {
            var np = prof!!
            // A lista de perfis pode não ter chegado ainda (escolha vinda do registro). Sem foto nem cor, busca de novo.
            if (np.avatarUrl.isBlank() && np.colorHex.isBlank()) np = nuvio.profiles().getOrNull()?.firstOrNull { it.index == np.index } ?: np
            val cur = prefs.profiles.value.firstOrNull { it.id == target } ?: error("O perfil de destino não existe mais")
            val next = cur.copy(
                name = np.name.trim().take(20).ifBlank { cur.name },
                avatar = np.avatarUrl.ifBlank { cur.avatar },
                color = nearestProfileColor(np.colorHex) ?: cur.color,
            )
            prefs.updateProfile(next)
            PartRecord(ok = true, n = if (np.avatarUrl.isNotBlank()) 1 else 0, label = next.name)
        }
        ImportPart.ADDONS -> {
            val rows = addonRows(target)
            val rowsOn = addonRowsOn(target)
            val list = if (a == Account.NUVIO) nuvio.addons(prof!!).getOrThrow() else stremio.addons().getOrThrow()
            val r = importer.importAddons(list, target) { d, t -> step("$d de $t") }
            val unusable = list.count { !it.canUse }
            PartRecord(
                ok = true, n = r.added, skip = r.skipped - unusable, failed = r.failed, unusable = unusable,
                rows = (addonRows(target) - rows).coerceAtLeast(0), rowsOn = (addonRowsOn(target) - rowsOn).coerceAtLeast(0),
            )
        }
        // Repositórios entram no gerenciador do perfil de destino com os provedores desligados.
        ImportPart.PLUGINS -> {
            val repos = nuvio.plugins(prof!!).getOrThrow()
            if (repos.isNotEmpty() && prefs.profiles.value.firstOrNull { it.id == target }?.kids == true) {
                PartRecord(ok = false, why = "Plugins só entram no perfil adulto")
            } else {
                var added = 0; var skip = 0; var failed = 0
                repos.forEachIndexed { i, r ->
                    step("${i + 1} de ${repos.size}")
                    try { if (plugins.installTo(target, r.url)) added++ else skip++ }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { failed++ }
                }
                PartRecord(ok = true, n = added, skip = skip, failed = failed)
            }
        }
        ImportPart.LIBRARY -> {
            val (list, progress) =
                if (a == Account.NUVIO) nuvio.library(prof!!).getOrThrow() to nuvio.progress(prof).getOrThrow()
                else stremio.library().getOrThrow()
            val l = importer.importLibrary(list, target) { d, t -> step("$d de $t") }
            val w = importer.importProgress(progress, target) { d, t -> step("progresso $d de $t") }
            PartRecord(ok = true, list = l.added + l.skipped, progress = w.added + w.skipped, nomatch = l.failed + w.failed)
        }
        // Perfil do Nuvio sem coleção não é falha, só não tem o que trazer.
        ImportPart.COLLECTIONS -> {
            val json = nuvio.collectionsJson(prof!!).getOrThrow()
            PartRecord(ok = true, n = if (json == null) 0 else collections.importJson(json, target).getOrThrow())
        }
        // Cada chave é conferida no serviço antes de entrar.
        ImportPart.DEBRID -> {
            val keys = nuvio.debridKeys(prof!!).getOrThrow()
            if (keys.isEmpty()) PartRecord(ok = true)
            else {
                val ok = keys.mapNotNull { (id, key) -> DebridProvider.of(id)?.let { dp -> if (debrid.connectKey(dp, key, target).isSuccess) dp.label else null } }
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
        return p?.let { NuvioProfile(it.index, it.name, it.primaryAddons, usesPrimaryPlugins = it.primaryPlugins) } ?: NUVIO_MAIN
    }

    fun setNuvioPick(p: NuvioProfile, profileId: String = prefs.profileId.value) =
        prefs.setNuvioPick(NuvioPick(p.index, p.name, p.usesPrimaryAddons, p.usesPrimaryPlugins), profileId)

    // ── Importar para outros perfis (Só um perfil, Todos os perfis e Stremio com destino) ──

    private val _batch = MutableStateFlow<BatchImport?>(null)
    /** Importação em lote em andamento ou a última que terminou. A parte em curso segue em [running]. */
    val batch: StateFlow<BatchImport?> = _batch.asStateFlow()
    private val batchJobs = HashMap<String, Deferred<BatchImport>>()

    /** Esquece o resultado da última importação em lote (a tela fechou o resumo). */
    fun clearBatch() { if (_batch.value?.running != true) _batch.value = null }

    /** Um perfil da Nuvio para um perfil do JOTV (o atual, um existente ou um novo com nome, foto e cor da Nuvio). */
    @Synchronized
    fun importNuvioOne(np: NuvioProfile, target: ImportTarget = ImportTarget.Current, parts: List<ImportPart> = Account.NUVIO.parts): Deferred<BatchImport> =
        startBatch(Account.NUVIO, parts) { listOf(Planned(np, target)) to 0 }

    /**
     * Plano do Todos os perfis para a lista de perfis da Nuvio (a já carregada por padrão). A tela usa para dizer
     * antes o que vai acontecer, e [importNuvioAll] usa o mesmo cálculo.
     */
    fun nuvioAllPlan(list: List<NuvioProfile> = _profiles.value.list): NuvioPlan {
        val jotv = prefs.profiles.value.map { p ->
            val log = prefs.importLogOf(p.id)
            JotvProfileLink(p.id, log.pick?.index, log[Account.NUVIO]?.email.orEmpty())
        }
        return ProfileSets.planNuvioAll(list.map { it.index }, jotv, prefs.profileId.value, emailOf(Account.NUVIO), MAX_PROFILES)
    }

    /**
     * Todos os perfis da Nuvio, cada um num perfil do JOTV (ver [ProfileSets.planNuvioAll]). Reaproveita o perfil
     * já ligado ao mesmo índice e e-mail, usa o atual quando ele ainda está livre e cria o resto até o limite.
     */
    @Synchronized
    fun importNuvioAll(parts: List<ImportPart> = Account.NUVIO.parts): Deferred<BatchImport> = startBatch(Account.NUVIO, parts) {
        val list = nuvio.profiles().getOrThrow().ifEmpty { listOf(NUVIO_MAIN) }
        _profiles.value = NuvioProfiles(Load.OK, list)
        val plan = nuvioAllPlan(list)
        plan.steps.map { s ->
            Planned(list.first { it.index == s.nuvioIndex }, s.profileId?.let { ImportTarget.Existing(it) } ?: ImportTarget.NewProfile)
        } to plan.leftOut
    }

    /** Stremio (conta única) para o perfil escolhido. O sync com o Stremio liga só no perfil que recebeu. */
    @Synchronized
    fun importStremio(target: ImportTarget = ImportTarget.Current, parts: List<ImportPart> = Account.STREMIO.parts): Deferred<BatchImport> =
        startBatch(Account.STREMIO, parts) { listOf(Planned(null, target)) to 0 }

    /** O destino só vira perfil de verdade (e o perfil novo só nasce) quando chega a vez dele no laço. */
    private class Planned(val nuvio: NuvioProfile?, val target: ImportTarget)

    /** Id do perfil de destino. Perfil novo nasce vazio, com nome, foto e cor da Nuvio quando vem de lá. */
    private fun resolve(target: ImportTarget, np: NuvioProfile?): String = when (target) {
        ImportTarget.Current -> prefs.profileId.value
        is ImportTarget.Existing -> target.profileId.takeIf { id -> prefs.profiles.value.any { it.id == id } }
            ?: error("O perfil de destino não existe mais")
        ImportTarget.NewProfile -> prefs.addProfile(
            np?.name ?: "Stremio", false, np?.colorHex?.let { nearestProfileColor(it) }, np?.avatarUrl.orEmpty(),
        )?.id ?: error("Já existem $MAX_PROFILES perfis")
    }

    private fun startBatch(
        a: Account, parts: List<ImportPart> = a.parts, plan: suspend () -> Pair<List<Planned>, Int>,
    ): Deferred<BatchImport> {
        batchJobs[a.key]?.takeIf { it.isActive }?.let { return it }
        jobs[a.key]?.takeIf { it.isActive }?.let { running -> return scope.async { running.await(); _batch.value ?: BatchImport(a) } }
        _batch.value = BatchImport(a)
        val job = scope.async {
            try {
                val (steps, leftOut) = plan()
                fun idOf(t: ImportTarget) = when (t) {
                    ImportTarget.Current -> prefs.profileId.value
                    is ImportTarget.Existing -> t.profileId
                    ImportTarget.NewProfile -> ""
                }
                var items = steps.map { s ->
                    val id = idOf(s.target)
                    BatchItem(s.nuvio?.index, s.nuvio?.name.orEmpty(), id, prefs.profiles.value.firstOrNull { it.id == id }?.name ?: s.nuvio?.name.orEmpty())
                }
                _batch.value = BatchImport(a, items, leftOut)
                steps.forEachIndexed { i, s ->
                    items = items.toMutableList().also { it[i] = it[i].copy(state = BatchState.RUNNING) }
                    _batch.value = BatchImport(a, items, leftOut)
                    val profileId = resolve(s.target, s.nuvio)
                    s.nuvio?.let { np ->
                        setNuvioPick(np, profileId)
                        // Perfil que acabou de nascer já tem nome, foto e cor da Nuvio. Gravar logo com o e-mail faz o
                        // próximo Todos reaproveitá-lo mesmo se a rede cair ou o app fechar antes do resto.
                        if (s.target == ImportTarget.NewProfile) {
                            prefs.recordImport(
                                a.key, ImportPart.PROFILE.key, PartRecord(ok = true, n = if (np.avatarUrl.isNotBlank()) 1 else 0, label = np.name),
                                emailOf(a), np.name, profileId = profileId,
                            )
                        }
                    }
                    items = items.toMutableList().also { it[i] = it[i].copy(profileId = profileId) }
                    val res = runCatching { doRun(a, partsFor(a, parts, profileId), profileId, s.nuvio) }
                    res.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                    val got = res.getOrDefault(emptyMap())
                    val name = prefs.profiles.value.firstOrNull { it.id == profileId }?.name ?: items[i].profileName
                    items = items.toMutableList().also {
                        it[i] = it[i].copy(profileName = name, results = got, state = if (res.isSuccess && got.values.all { r -> r.ok }) BatchState.DONE else BatchState.FAILED)
                    }
                    _batch.value = BatchImport(a, items, leftOut)
                }
                BatchImport(a, items, leftOut, running = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BatchImport(a, _batch.value?.items.orEmpty(), _batch.value?.leftOut ?: 0, running = false, error = e.message ?: "Não deu para importar")
            }.also { _batch.value = it }
        }
        batchJobs[a.key] = job
        return job
    }

    /** Chamado quando a pessoa entra ou sai de uma conta. A lista de perfis do Nuvio vale por sessão. */
    fun accountChanged(a: Account) {
        if (a != Account.NUVIO) return
        resetNuvioProfiles()
        loadNuvioProfiles()
    }
}
