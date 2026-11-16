package com.joabtv.app.data.sync

import com.joabtv.app.data.accounts.Account
import com.joabtv.app.data.accounts.AccountImporter
import com.joabtv.app.data.accounts.NuvioAccountService
import com.joabtv.app.data.accounts.NuvioProfile
import com.joabtv.app.data.accounts.RemoteProgress
import com.joabtv.app.data.accounts.StremioAccountService
import com.joabtv.app.data.debrid.isoMs
import com.joabtv.app.data.local.PlatformPrefs
import com.joabtv.app.data.local.entity.MovieHistoryEntity
import com.joabtv.app.data.repository.LibraryRepository
import com.joabtv.app.domain.model.MediaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mantém o histórico do JOTV igual ao das contas Nuvio e Stremio, nos dois sentidos (ver sync-spec).
 * Puxa quando o app volta para a frente, a cada 5 min com o app aberto, ao trocar de perfil e ao conectar a conta.
 * Envia pouco depois de a pessoa tocar, concluir, marcar visto ou remover (no máximo a cada 45 s durante a reprodução,
 * e logo que ela para). Quem manda aqui é a regra do mais recente. O que chega de fora nunca gera envio (eco).
 *
 * O sync de um perfil e uma conta só roda com ligação explícita, que nasce da importação (autoLink) ou do interruptor.
 * Roda em segundo plano e uma falha de uma conta nunca atrapalha a outra.
 *
 * Regra de ouro. Nada aqui apaga histórico em massa. Remoção só vale título a título, com puxada completa e
 * sem sumiço em massa (ver [SyncPlan.goneTitles]), e limpar o histórico daqui vira uma remoção por linha.
 */
@Singleton
class HistorySync @Inject constructor(
    private val nuvio: NuvioAccountService,
    private val stremio: StremioAccountService,
    private val library: LibraryRepository,
    private val importer: AccountImporter,
    private val ids: SyncIds,
    private val store: SyncStore,
    private val prefs: PlatformPrefs,
) {
    enum class State { OFF, IDLE, RUNNING, OK, ERROR }

    /** O que a tela mostra de cada conta. [okAt] é a hora da última sincronia que deu certo (0 se nunca). */
    data class Status(
        val connected: Boolean = false,
        val enabled: Boolean = false,
        val state: State = State.OFF,
        val okAt: Long = 0L,
        val error: String = "",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _status = MutableStateFlow(Account.entries.associateWith { Status() })
    val status: StateFlow<Map<Account, Status>> = _status.asStateFlow()

    private val locks = Account.entries.associateWith { Mutex() }
    private val lastFull = ConcurrentHashMap<Account, Long>()

    /** Rodada pedida enquanto outra da mesma conta andava. O valor diz se precisa puxar (senão só envia). */
    private val again = ConcurrentHashMap<Account, Boolean>()

    @Volatile private var foreground = false

    /** A última mudança que entrou na fila veio da reprodução. O reagendamento depois de uma rodada respeita o piso de 45 s. */
    @Volatile private var lastPlaying = false
    private var started = false
    private var flushJob: Job? = null
    private var firstPendingAt = 0L
    private var lastFlushAt = 0L
    private var flushRunning = false
    private var dirty = false

    // ── Ligar ───────────────────────────────────────────────────────────────────

    /** Chamado uma vez na abertura do app. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        refreshStatus()
        scope.launch { library.changes.collect { onChange(it) } }
        scope.launch { prefs.profileId.drop(1).collect { refreshStatus(); syncAll(force = true) } }
        scope.launch { nuvio.session.map { it?.email }.distinctUntilChanged().drop(1).collect { refreshStatus(); sync(Account.NUVIO, force = true) } }
        scope.launch { stremio.session.map { it?.email }.distinctUntilChanged().drop(1).collect { refreshStatus(); sync(Account.STREMIO, force = true) } }
        scope.launch { prefs.importLogFlow.collect { autoLink() } }
        scope.launch { while (true) { delay(PERIOD); if (foreground) syncAll(force = false) } }
    }

    fun onForeground() { foreground = true; syncAll(force = false) }

    /** App saiu da frente. Manda o que estiver pendente agora, porque o sistema pode encerrar o processo. */
    fun onBackground() {
        foreground = false
        synchronized(this) {
            // Envio que já está conversando com o servidor termina sozinho. Só o que ainda espera o prazo é trocado por este.
            if (!flushRunning) { flushJob?.cancel(); firstPendingAt = 0L }
            lastFlushAt = System.currentTimeMillis()
        }
        scope.launch { flush() }
    }

    fun syncNow(account: Account? = null) {
        (account?.let { listOf(it) } ?: Account.entries).forEach { sync(it, force = true) }
    }

    /** Interruptor do perfil ativo. Ligar já sincroniza. */
    fun setEnabled(account: Account, on: Boolean) {
        val profile = prefs.profileId.value
        // Religar começa do zero, como a primeira ligação. O que se sabia do remoto de antes de desligar ficou velho.
        if (on && store.enabled(profile, account.key) != true) store.resetLink(profile, account.key)
        store.setEnabled(profile, account.key, on)
        refreshStatus()
        if (on) sync(account, force = true)
    }

    // ── Gatilhos ────────────────────────────────────────────────────────────────

    /** Perfil que importou da conta fica ligado a ela (é a ligação explícita), se a pessoa não escolheu o contrário. */
    private fun autoLink() {
        val profile = prefs.profileId.value
        val log = prefs.importLogOf(profile)
        Account.entries.forEach { a ->
            if (!connected(a)) return@forEach
            val rec = log[a] ?: return@forEach
            if (store.enabled(profile, a.key) != null) return@forEach
            // Importação feita com outra conta não liga esta.
            if (!rec.email.equals(email(a), ignoreCase = true)) return@forEach
            store.setEnabled(profile, a.key, true)
            refreshStatus()
            sync(a, force = true)
        }
    }

    private fun syncAll(force: Boolean) = Account.entries.forEach { sync(it, force) }

    private fun sync(account: Account, force: Boolean, pull: Boolean = true) {
        scope.launch { runOnce(account, force, pull) }
    }

    private fun onChange(c: HistoryChange) {
        if (c is HistoryChange.ProfileDropped) { store.drop(c.profileId); return }
        val now = System.currentTimeMillis()
        var any = false
        for (a in Account.entries) {
            if (!connected(a) || store.enabled(c.profileId, a.key) != true) continue
            any = true
            when (c) {
                is HistoryChange.Progress -> store.addProgress(c.profileId, a.key, "${c.type}|${c.tmdbId}", now)
                // O Stremio guarda episódios vistos num campo compactado que o JOTV não escreve. Fica de fora.
                is HistoryChange.Watched -> if (a == Account.NUVIO) store.addSeen(c.profileId, a.key, "${c.tmdbId}|${c.season}|${c.episode}", c.on, now)
                is HistoryChange.WatchedMany ->
                    if (a == Account.NUVIO) store.addSeenAll(c.profileId, a.key, c.episodes.map { "${c.tmdbId}|${it.first}|${it.second}" }, true, now)
                is HistoryChange.Removed -> store.addRemoved(c.profileId, a.key, "${c.type}|${c.tmdbId}", now)
                is HistoryChange.ProfileDropped -> Unit
            }
        }
        val playing = c is HistoryChange.Progress
        if (any) { lastPlaying = playing; schedule(if (playing) PLAY_DELAY else ACTION_DELAY, playing) }
    }

    /**
     * Espera a pessoa parar e manda. Em ação (marcar, remover) são 2 s. Em reprodução o player grava a cada 10 s, então
     * o prazo de 5 s sozinho viraria um envio por gravação. Por isso, tocando, o envio nunca vem antes de 45 s depois do
     * anterior ([lastFlushAt]). Quando a pessoa sai do app o que estiver na fila vai na hora ([onBackground]).
     * Um envio que já conversa com o servidor não é cancelado. O que chegar nesse meio tempo vai na rodada seguinte.
     */
    private fun schedule(trailing: Long, playing: Boolean = false) {
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (flushRunning) { dirty = true; return }
            if (firstPendingAt == 0L) firstPendingAt = now
            var wait = minOf(trailing, maxOf(0L, firstPendingAt + MAX_WAIT - now))
            if (playing) wait = maxOf(wait, lastFlushAt + MAX_WAIT - now)
            flushJob?.cancel()
            flushJob = scope.launch {
                delay(wait)
                // Job cancelado depois do prazo não pode ligar o "em andamento", senão ninguém o desliga e nada mais é agendado.
                ensureActive()
                synchronized(this@HistorySync) { firstPendingAt = 0L; lastFlushAt = System.currentTimeMillis(); flushRunning = true; dirty = false }
                try {
                    flush()
                } finally {
                    val more = synchronized(this@HistorySync) { flushRunning = false; dirty }
                    if (more) schedule(if (lastPlaying) PLAY_DELAY else ACTION_DELAY, lastPlaying)
                }
            }
        }
    }

    private suspend fun flush() = Account.entries.forEach { runOnce(it, force = true, pull = false) }

    // ── Uma rodada ──────────────────────────────────────────────────────────────

    private fun connected(a: Account) = if (a == Account.NUVIO) nuvio.session.value != null else stremio.session.value != null

    private fun email(a: Account) = (if (a == Account.NUVIO) nuvio.session.value?.email else stremio.session.value?.email).orEmpty()

    private suspend fun runOnce(account: Account, force: Boolean, pull: Boolean) {
        val profile = prefs.profileId.value
        if (!connected(account) || store.enabled(profile, account.key) != true) { refreshStatus(); return }
        val now = System.currentTimeMillis()
        if (pull && !force && now - (lastFull[account] ?: 0L) < MIN_PULL_GAP) return
        val lock = locks.getValue(account)
        // Já tem uma rodada desta conta andando (a troca de perfil, por exemplo, chega nesse meio tempo). Ela repete ao acabar.
        if (!lock.tryLock()) { again.merge(account, pull) { a, b -> a || b }; return }
        var ok = false
        try {
            setState(account, State.RUNNING)
            if (!guardIdentity(profile, account)) { refreshStatus(); return }
            if (account == Account.NUVIO) runNuvio(profile, pull) else runStremio(profile, pull)
            if (pull) lastFull[account] = now
            store.commitIds()
            store.setLastOk(profile, account.key, System.currentTimeMillis())
            ok = true
            setState(account, State.OK)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setState(account, State.ERROR, (e.message ?: "Falhou").take(120))
        } finally {
            lock.unlock()
            // Pedido que chegou com a conta ocupada. Roda no finally para não se perder se esta rodada for cancelada.
            again.remove(account)?.let { redo -> sync(account, force = true, pull = redo) }
        }
        // O que a pessoa fez durante a rodada vai na próxima. Só reagenda se esta deu certo, para não martelar o servidor.
        if (ok && !store.pending(profile, account.key).isEmpty) schedule(if (lastPlaying) PLAY_DELAY else ACTION_DELAY, lastPlaying)
    }

    /**
     * Confere se a ligação ainda é a mesma. Devolve falso quando a conta mudou, ou (no Nuvio) o perfil dela. Nesse caso
     * o interruptor volta a "nunca mexeu" e o retrato do remoto é esquecido, e a rodada não roda. Juntar o histórico daqui
     * com o de uma conta que a pessoa não escolheu seria perigoso (o do perfil seria enviado para ela). Só uma importação
     * daquela conta ou o interruptor ligam de novo, e aí a primeira sincronia junta as duas pontas.
     */
    private fun guardIdentity(profile: String, a: Account): Boolean {
        val mail = email(a).lowercase()
        val index = if (a == Account.NUVIO) prefs.importLogOf(profile).pick?.index ?: 1 else null
        val who = SyncPlan.linkId(mail, index)
        val saved = store.who(profile, a.key)
        // Versão antiga guardava o e-mail em texto. Mesmo e-mail é a mesma conta, e só passa para o formato novo.
        val same = saved.isEmpty() || saved == who || (saved.contains('@') && saved.equals(mail, ignoreCase = true))
        if (same) {
            if (saved != who) store.setWho(profile, a.key, who)
            return true
        }
        store.resetLink(profile, a.key)
        store.clearEnabled(profile, a.key)
        store.setWho(profile, a.key, who)
        return false
    }

    // ── Nuvio ───────────────────────────────────────────────────────────────────

    private suspend fun runNuvio(profile: String, pull: Boolean) {
        val acc = Account.NUVIO.key
        // O registro de importação lido pelo id do perfil que esta rodada atende. O ativo pode já ser outro (ver importLogOf).
        val pick = prefs.importLogOf(profile).pick
        val prof = NuvioProfile(pick?.index ?: 1, pick?.name ?: "Principal", false)
        val startedAt = System.currentTimeMillis()
        var snap = store.snap(profile, acc)
        var seenSnap = store.seenSnap(profile, acc)
        val before = store.lastPull(profile, acc)
        if (pull) {
            // Hora de outro aparelho com o relógio adiantado fica na hora em que foi vista (ver SyncPlan.effectiveRemote).
            val sight = store.sight(profile, acc, startedAt)
            val rows = nuvio.progressRows(prof).getOrThrow().map { it.copy(watchedAt = sight.of(it.watchedAt)) }
            // Os vistos são a parte menos crítica. Se a função falhar (assinatura diferente no servidor, por exemplo)
            // o sync segue só com o progresso, e o retrato dos vistos fica como estava.
            val watched = nuvio.watchedRows(prof).getOrNull()?.let { (list, complete) ->
                list.map { it.copy(at = sight.of(it.at)) } to complete
            }
            sight.commit()
            importer.importProgressSync(SyncPlan.latestPerTitle(rows).map { it.asProgress() }, profile)
            val nowSnap = SyncPlan.snapshotOf(rows)
            // Título que a conta ainda tem sob outro id (o do TMDB e o do IMDb, por exemplo) não foi removido.
            val alive = nowSnap.mapNotNull { e -> ids.tmdbFor(e.contentId, e.series)?.let { e.series to it } }.toSet()
            dropGone(profile, SyncPlan.goneTitles(snap, nowSnap, SyncPlan.complete(rows.size)), before, alive)
            store.setSnap(profile, acc, nowSnap)
            snap = nowSnap
            var seenComplete = false
            if (watched != null) {
                val (seen, complete) = watched
                importer.importSeen(seen, profile)
                // Filme visto entra com temporada e episódio 0. Assim o envio sabe que a conta já tem esse filme.
                val nowSeen = seen.filter { !it.series || (it.season > 0 && it.episode > 0) }
                    .map { SyncPlan.seenKey(it.contentId, if (it.series) it.season else 0, if (it.series) it.episode else 0) }.toSet()
                // Visto que este aparelho acabou de enviar e ainda não voltou numa puxada não conta para inferir remoção.
                dropGoneSeen(profile, SyncPlan.goneSeen(seenSnap - store.pushedSeen(profile, acc), nowSeen, complete), before)
                store.setSeenSnap(profile, acc, nowSeen)
                store.setPushedSeen(profile, acc, emptySet())
                seenSnap = nowSeen
                seenComplete = complete
            }
            store.setLastPull(profile, acc, startedAt)
            // Primeira vez. O que só existe aqui também sobe (as duas pontas se juntam).
            if (before == 0L) { seed(profile, acc, startedAt); store.setSeedSeenPending(profile, acc, true) }
            // Os vistos daqui só sobem com a lista de vistos da conta inteira nas mãos. Sem ela tudo pareceria novo para a conta
            // (e série guardada com outro id geraria linhas repetidas). Fica para a próxima puxada completa.
            if (seenComplete && store.seedSeenPending(profile, acc)) {
                seedSeen(profile, acc, startedAt)
                store.setSeedSeenPending(profile, acc, false)
            }
        }
        pushNuvio(profile, prof, snap, seenSnap)
    }

    private fun RemoteRow.asProgress() =
        RemoteProgress(SyncPlan.baseId(contentId), series, "", "", season, episode, positionMs, durationMs, SyncPlan.clampRemote(watchedAt))

    /**
     * Títulos que sumiram da conta (removidos lá). Só apaga o que não foi mexido aqui depois da última puxada e o que a
     * conta não tem mais sob nenhum id ([alive] são os (série, id do TMDB) que continuam lá).
     */
    private suspend fun dropGone(profile: String, titles: Set<Pair<Boolean, String>>, before: Long, alive: Set<Pair<Boolean, Int>>) {
        titles.forEach { (series, cid) ->
            val tmdbId = ids.tmdbFor(cid, series) ?: return@forEach
            if ((series to tmdbId) in alive) return@forEach
            val type = if (series) MediaType.SERIES.name else MediaType.MOVIE.name
            val local = library.getPlayback(tmdbId, type, profile) ?: return@forEach
            if (local.lastWatchedAt <= before) library.removePlayback(tmdbId, type, profile, silent = true)
        }
    }

    private suspend fun dropGoneSeen(profile: String, keys: Set<String>, before: Long) {
        if (keys.isEmpty()) return
        val mine = library.watchedRows(profile).associateBy { "${it.seriesTmdbId}|${it.season}|${it.episode}" }
        keys.forEach { k ->
            val p = k.split('|')
            val s = p.getOrNull(1)?.toIntOrNull() ?: return@forEach
            val e = p.getOrNull(2)?.toIntOrNull() ?: return@forEach
            val tmdbId = ids.tmdbFor(p[0], true) ?: return@forEach
            val local = mine["$tmdbId|$s|$e"] ?: return@forEach
            if (local.watchedAt <= before) library.unmarkEpisodeWatched(tmdbId, s, e, profile, silent = true)
        }
    }

    /**
     * Põe na fila de envio o histórico que já existia aqui antes da ligação. O que veio da própria puxada não sobe de
     * novo, porque o envio compara com o retrato do remoto (mesma hora, filme que a conta já tem como visto). O que a pessoa
     * já tocou durante a primeira puxada continua na fila com a hora dele.
     */
    private suspend fun seed(profile: String, acc: String, at: Long) {
        store.addProgressAll(profile, acc, library.historyRows(profile).map { "${it.type}|${it.tmdbId}" }, at - 1, keepExisting = true)
    }

    private suspend fun seedSeen(profile: String, acc: String, at: Long) {
        store.addSeenAll(profile, acc, library.watchedRows(profile).take(2000).map { "${it.seriesTmdbId}|${it.season}|${it.episode}" }, true, at - 1, keepExisting = true)
    }

    private suspend fun pushNuvio(profile: String, prof: NuvioProfile, snap: List<SnapEntry>, seenSnap: Set<String>) {
        val acc = Account.NUVIO.key
        val taken = store.pending(profile, acc)
        if (taken.isEmpty) return
        var origin: String? = if (store.originAccepted() == false) null else store.clientId()

        // Como a própria conta guarda cada título. Usa a chave e o id dela, senão o servidor criaria uma segunda linha.
        val byKey = snap.associateBy { it.key }
        val byEpisode = snap.associateBy { Triple(it.series to it.contentId, it.season, it.episode) }
        val used = HashMap<Pair<Boolean, Int>, String>()
        snap.forEach { e -> ids.tmdbFor(e.contentId, e.series)?.let { used.putIfAbsent(e.series to it, e.contentId) } }
        val memo = HashMap<Pair<Boolean, Int>, String>()
        suspend fun idOf(series: Boolean, tmdbId: Int): String = memo.getOrPut(series to tmdbId) {
            used[series to tmdbId]
                ?: "tmdb:$tmdbId".takeIf { alt -> seenSnap.any { it.startsWith("$alt|") && SyncPlan.isEpisodeKey(it) == series } }
                ?: ids.remoteFor(tmdbId, series)
        }

        val rows = ArrayList<PushRow>()
        val seenOut = ArrayList<PushSeen>()
        val unseen = ArrayList<Triple<String, Int, Int>>()
        // Títulos cuja hora subiu acima da local (relógio daqui atrás do remoto). A linha local acompanha depois do envio.
        val bumps = ArrayList<Triple<Int, String, Long>>()
        val lastPull = store.lastPull(profile, acc)
        for ((key, ts) in taken.progress) {
            val (type, tmdbId) = splitKey(key) ?: continue
            val local = library.getPlayback(tmdbId, type, profile) ?: continue
            val series = type == MediaType.SERIES.name
            val (s, e) = if (series) SyncPlan.parseEpisode(local.lastEpisode) else 0 to 0
            if (series && (s <= 0 || e <= 0)) continue
            val cid = idOf(series, tmdbId)
            val entry = byEpisode[Triple(series to cid, s, e)] ?: byKey[SyncPlan.progressKey(cid, s, e)]
            val remoteAt = entry?.at
            var at = local.lastWatchedAt
            // Tocou depois da última puxada (real) ou veio da ligação da conta (semente). Duração de marcador (1 ms) não sobe.
            if (SyncPlan.pushable(local.durationMs) && SyncPlan.shouldPush(local.lastWatchedAt, local.positionMs, remoteAt, real = ts > lastPull)) {
                at = SyncPlan.pushAt(local.lastWatchedAt, remoteAt)
                if (at != local.lastWatchedAt) bumps += Triple(tmdbId, type, at)
                rows += PushRow(cid, series, s, e, local.positionMs, local.durationMs, at, entry?.key)
            }
            // Filme concluído também vai para a lista de vistos, se a conta ainda não o tem.
            if (!series && SyncPlan.finished(local.positionMs, local.durationMs) && SyncPlan.seenKey(cid, 0, 0) !in seenSnap) {
                seenOut += PushSeen(cid, false, local.title, 0, 0, at)
            }
        }
        if (taken.seen.isNotEmpty()) {
            // A hora do episódio visto é a que ele tem aqui. A da fila é a de quando entrou nela, e na ligação da conta é "agora".
            val mine = library.watchedRows(profile).associate { "${it.seriesTmdbId}|${it.season}|${it.episode}" to it.watchedAt }
            val titles = HashMap<Int, String>()
            for ((key, p) in taken.seen) {
                val parts = key.split('|')
                val tmdbId = parts[0].toIntOrNull() ?: continue
                val s = parts.getOrNull(1)?.toIntOrNull() ?: continue
                val e = parts.getOrNull(2)?.toIntOrNull() ?: continue
                val cid = idOf(true, tmdbId)
                val has = SyncPlan.seenKey(cid, s, e) in seenSnap
                if (p.on && !has) {
                    val title = titles.getOrPut(tmdbId) { library.getPlayback(tmdbId, MediaType.SERIES.name, profile)?.title.orEmpty() }
                    seenOut += PushSeen(cid, true, title, s, e, mine[key] ?: p.ts)
                } else if (!p.on && has) unseen += Triple(cid, s, e)
            }
        }
        val del = LinkedHashSet<String>()
        for ((key, removedAt) in taken.removed) {
            val (type, tmdbId) = splitKey(key) ?: continue
            val series = type == MediaType.SERIES.name
            val cid = idOf(series, tmdbId)
            val known = snap.filter { it.series == series && it.contentId == cid }
            // Linha que o outro app mexeu bem depois da remoção fica, é um dado mais novo que o da remoção.
            if (known.isNotEmpty()) known.filter { SyncPlan.removalStands(it.at, removedAt) }.forEach { del += it.key } else if (!series) del += cid
        }

        fun note(r: Result<Boolean>) {
            val accepted = r.getOrThrow()
            store.setOriginAccepted(accepted)
            if (!accepted) origin = null
        }
        if (rows.isNotEmpty()) note(nuvio.pushProgress(prof, rows, origin))
        if (seenOut.isNotEmpty()) note(nuvio.pushWatched(prof, seenOut, origin))
        if (del.isNotEmpty()) note(nuvio.deleteProgress(prof, del.toList(), origin))
        if (unseen.isNotEmpty()) note(nuvio.deleteWatched(prof, unseen, origin))
        bumps.forEach { (id, type, at) -> library.bumpPlayback(id, type, profile, at) }

        // O retrato do remoto passa a incluir o que acabou de ser escrito (senão desmarcar logo depois não apagaria nada).
        val map = LinkedHashMap<String, SnapEntry>()
        snap.forEach { map[it.key] = it }
        rows.forEach { r ->
            val s = if (r.series) r.season else 0
            val e = if (r.series) r.episode else 0
            val k = r.key?.takeIf { it.isNotBlank() } ?: SyncPlan.progressKey(r.contentId, s, e)
            // Marcada como enviada. Só deixa de ser "não confirmada" quando aparecer numa puxada (SyncPlan.goneTitles).
            map[k] = SnapEntry(k, SyncPlan.baseId(r.contentId), r.series, r.at, s, e, pushed = true)
        }
        del.forEach { map.remove(it) }
        store.setSnap(profile, acc, map.values.toList())
        val seenNew = seenSnap.toMutableSet()
        val pushedNew = store.pushedSeen(profile, acc).toMutableSet()
        seenOut.forEach {
            val key = SyncPlan.seenKey(it.contentId, if (it.series) it.season else 0, if (it.series) it.episode else 0)
            seenNew += key
            pushedNew += key
        }
        unseen.forEach {
            val key = SyncPlan.seenKey(it.first, it.second, it.third)
            seenNew -= key
            pushedNew -= key
        }
        store.setSeenSnap(profile, acc, seenNew)
        store.setPushedSeen(profile, acc, pushedNew)
        store.settle(profile, acc, taken)
    }

    // ── Stremio ─────────────────────────────────────────────────────────────────

    private suspend fun runStremio(profile: String, pull: Boolean) {
        val acc = Account.STREMIO.key
        val startedAt = System.currentTimeMillis()
        val before = store.lastPull(profile, acc)
        if (pull) {
            val known = store.metaMap(profile, acc)
            // Primeiro a lista leve de horas. Só baixa os itens cuja hora mudou desde a última leitura.
            val meta = stremio.libraryMeta().getOrNull()
            val items = if (meta == null || known.isEmpty()) stremio.libraryAll().getOrThrow() else {
                val changed = meta.filter { (id, m) -> known[id] != m }.keys.toList()
                if (changed.isEmpty()) JSONArray() else stremio.libraryItems(changed).getOrThrow()
            }
            // Hora de outro aparelho com o relógio adiantado fica na hora em que foi vista (ver SyncPlan.effectiveRemote).
            val sight = store.sight(profile, acc, startedAt)
            val progress = StremioAccountService.parseLibrary(items).second.map { it.copy(watchedAt = sight.of(it.watchedAt)) }
            sight.commit()
            importer.importProgressSync(progress, profile)
            if (meta != null && meta != known) store.setMetaMap(profile, acc, meta)
            store.setLastPull(profile, acc, startedAt)
            if (before == 0L) seed(profile, acc, startedAt)
        }
        pushStremio(profile)
    }

    private class Want(val cid: String, val series: Boolean, val local: MovieHistoryEntity, val season: Int, val episode: Int, val real: Boolean)

    private suspend fun pushStremio(profile: String) {
        val acc = Account.STREMIO.key
        val taken = store.pending(profile, acc)
        if (taken.progress.isEmpty() && taken.removed.isEmpty()) { store.settle(profile, acc, taken); return }
        val wants = ArrayList<Want>()
        val lastPull = store.lastPull(profile, acc)
        for ((key, ts) in taken.progress) {
            val (type, tmdbId) = splitKey(key) ?: continue
            val local = library.getPlayback(tmdbId, type, profile) ?: continue
            // Duração de marcador (1 ms) não vira item no Stremio.
            if (!SyncPlan.pushable(local.durationMs)) continue
            val series = type == MediaType.SERIES.name
            val (s, e) = if (series) SyncPlan.parseEpisode(local.lastEpisode) else 0 to 0
            if (series && (s <= 0 || e <= 0)) continue
            val cid = ids.remoteFor(tmdbId, series)
            // O Stremio só entende id do IMDb.
            if (cid.startsWith("tt")) wants += Want(cid, series, local, s, e, real = ts > lastPull)
        }
        val clears = LinkedHashMap<String, Long>()
        for ((key, removedAt) in taken.removed) {
            val (type, tmdbId) = splitKey(key) ?: continue
            val cid = ids.remoteFor(tmdbId, type == MediaType.SERIES.name)
            if (cid.startsWith("tt")) clears[cid] = removedAt
        }
        if (wants.isEmpty() && clears.isEmpty()) { store.settle(profile, acc, taken); return }
        val got = stremio.libraryItems((wants.map { it.cid } + clears.keys).distinct()).getOrThrow()
        val byId = (0 until got.length()).mapNotNull { got.optJSONObject(it) }.associateBy { it.optString("_id") }
        val now = System.currentTimeMillis()
        // A hora que o item tem na conta passa pela mesma correção de relógio adiantado da puxada. Crua e no futuro, ela
        // faria o envio sair com hora no futuro e prenderia o título no topo da lista daqui.
        val sight = store.sight(profile, acc, now)
        val changes = JSONArray()
        val bumps = ArrayList<Triple<Int, String, Long>>()
        for (w in wants) {
            val cur = byId[w.cid]
            val curAt = isoMs(cur?.optJSONObject("state")?.optString("lastWatched")).takeIf { it > 0 }?.let { sight.of(it) }
            if (!SyncPlan.shouldPush(w.local.lastWatchedAt, w.local.positionMs, curAt, w.real)) continue
            val at = SyncPlan.pushAt(w.local.lastWatchedAt, curAt)
            if (at != w.local.lastWatchedAt) bumps += Triple(w.local.tmdbId, w.local.type, at)
            changes.put(
                SyncPlan.stremioItem(
                    cur, w.cid, w.series, w.local.title, w.local.posterUrl,
                    SyncPlan.videoId(w.cid, w.series, w.season, w.episode), w.local.positionMs, w.local.durationMs, at, now,
                ),
            )
        }
        for ((id, removedAt) in clears) {
            val cur = byId[id] ?: continue
            // Visto em outro aparelho depois da remoção. Zerar lá levaria o dado mais novo.
            val curAt = isoMs(cur.optJSONObject("state")?.optString("lastWatched")).takeIf { it > 0 }?.let { sight.of(it) }
            if (!SyncPlan.removalStands(curAt, removedAt)) continue
            SyncPlan.stremioCleared(cur, now)?.let { changes.put(it) }
        }
        sight.commit()
        if (changes.length() > 0) stremio.putLibrary(changes).getOrThrow()
        bumps.forEach { (id, type, at) -> library.bumpPlayback(id, type, profile, at) }
        store.settle(profile, acc, taken)
    }

    // ── Auxiliares ──────────────────────────────────────────────────────────────

    /** "SERIES|1399" vira ("SERIES", 1399). */
    private fun splitKey(key: String): Pair<String, Int>? {
        val i = key.indexOf('|')
        if (i <= 0) return null
        return key.substring(0, i) to (key.substring(i + 1).toIntOrNull() ?: return null)
    }

    private fun refreshStatus() {
        val profile = prefs.profileId.value
        _status.update { m ->
            Account.entries.associateWith { a ->
                val cur = m.getValue(a)
                val c = connected(a)
                val on = c && store.enabled(profile, a.key) == true
                cur.copy(
                    connected = c, enabled = on, okAt = store.lastOk(profile, a.key),
                    state = when { !on -> State.OFF; cur.state == State.OFF -> State.IDLE; else -> cur.state },
                    error = if (on) cur.error else "",
                )
            }
        }
    }

    private fun setState(a: Account, s: State, err: String = "") {
        val profile = prefs.profileId.value
        _status.update { m -> m + (a to m.getValue(a).copy(state = s, error = err, okAt = store.lastOk(profile, a.key))) }
    }

    private companion object {
        const val PERIOD = 5 * 60_000L
        const val MIN_PULL_GAP = 60_000L
        const val PLAY_DELAY = 5_000L
        const val ACTION_DELAY = 2_000L
        const val MAX_WAIT = 45_000L
    }
}
