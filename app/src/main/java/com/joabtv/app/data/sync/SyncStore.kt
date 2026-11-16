package com.joabtv.app.data.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/** Episódio marcado ([on] verdadeiro) ou desmarcado, e quando. */
data class PendingSeen(val on: Boolean, val ts: Long)

/**
 * Alterações locais ainda não enviadas. Cada entrada leva a hora em que entrou, e o fim de um envio só tira da fila
 * as entradas que continuam iguais ao que ele leu no começo ([SyncStore.settle]). Assim não se perde o que a pessoa
 * fez durante a conversa com o servidor, e relógio que anda para trás não deixa entrada presa na fila.
 * Chave de progresso e de remoção é "TIPO|tmdbId" e a de visto é "tmdbId|temporada|episódio".
 */
data class Pending(
    val progress: Map<String, Long> = emptyMap(),
    val seen: Map<String, PendingSeen> = emptyMap(),
    val removed: Map<String, Long> = emptyMap(),
) {
    val isEmpty get() = progress.isEmpty() && seen.isEmpty() && removed.isEmpty()
}

/**
 * Tudo que o sync lembra, em preferências próprias (joabtv_sync). Por perfil e conta ficam o interruptor,
 * a última puxada, as chaves que o remoto tinha e a fila de pendências. Do aparelho ficam o mapa de ids
 * e o identificador do cliente.
 */
@Singleton
class SyncStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("joabtv_sync", Context.MODE_PRIVATE)

    private fun k(profile: String, account: String, part: String) = "$profile.$account.$part"

    // ── Interruptor e marcas de tempo ───────────────────────────────────────────

    /** Valor gravado, ou null quando a pessoa nunca mexeu (vale a ligação pela importação). */
    fun enabled(profile: String, account: String): Boolean? =
        if (prefs.contains(k(profile, account, "on"))) prefs.getBoolean(k(profile, account, "on"), false) else null

    fun setEnabled(profile: String, account: String, on: Boolean) {
        prefs.edit().putBoolean(k(profile, account, "on"), on).apply()
    }

    /** Volta ao "nunca mexeu" (liga de novo só por importação daquela conta ou pelo interruptor). */
    fun clearEnabled(profile: String, account: String) {
        prefs.edit().remove(k(profile, account, "on")).apply()
    }

    fun lastPull(profile: String, account: String) = prefs.getLong(k(profile, account, "pull"), 0L)
    fun setLastPull(profile: String, account: String, v: Long) { prefs.edit().putLong(k(profile, account, "pull"), v).apply() }
    fun lastOk(profile: String, account: String) = prefs.getLong(k(profile, account, "ok"), 0L)
    fun setLastOk(profile: String, account: String, v: Long) { prefs.edit().putLong(k(profile, account, "ok"), v).apply() }

    /**
     * Hora (_mtime) que cada item da biblioteca do Stremio tinha na última leitura. Só baixa o item cujo valor mudou.
     * Comparar com o valor guardado, e não com a maior hora vista, evita perder mudança quando o relógio daqui
     * está adiantado (o _mtime que este aparelho escreve ficaria à frente de tudo que o Stremio gravar depois).
     */
    fun metaMap(profile: String, account: String): Map<String, Long> = runCatching {
        val o = JSONObject(prefs.getString(k(profile, account, "meta"), "{}"))
        o.keys().asSequence().associateWith { o.optLong(it) }
    }.getOrDefault(emptyMap())

    fun setMetaMap(profile: String, account: String, m: Map<String, Long>) {
        prefs.edit().putString(k(profile, account, "meta"), JSONObject(m).toString()).apply()
    }

    /**
     * Hora de um dado vindo de outra conta, já corrigida para relógio adiantado de lá ([SyncPlan.effectiveRemote]).
     * Guarda quando cada hora futura foi vista pela primeira vez. A consulta ao que já foi guardado vem antes da comparação com
     * agora, senão a hora congelada trocaria pela hora crua assim que o relógio daqui a alcançasse. Esquece o que tem mais
     * de 30 dias.
     */
    fun sight(profile: String, account: String, now: Long) = Sight(k(profile, account, "fut"), now)

    /** Uma passada de correção de hora em muitas linhas. Lê o que está guardado uma vez e grava uma vez só, em [commit]. */
    inner class Sight(private val key: String, private val now: Long) {
        private val o = runCatching { JSONObject(prefs.getString(key, "{}")) }.getOrDefault(JSONObject())
        private var dirty = false

        fun of(raw: Long): Long {
            val rk = raw.toString()
            if (o.has(rk)) return SyncPlan.effectiveRemote(raw, now, o.optLong(rk))
            if (raw <= now) return raw
            o.put(rk, now)
            dirty = true
            return now
        }

        @Synchronized
        fun commit() {
            if (!dirty) return
            val old = o.keys().asSequence().filter { (it.toLongOrNull() ?: 0L) < now - 30L * 86_400_000L }.toList()
            old.forEach { o.remove(it) }
            prefs.edit().putString(key, o.toString()).apply()
            dirty = false
        }
    }

    // ── Qual conta está ligada a este perfil ────────────────────────────────────

    /** Identificador (hash, ver [SyncPlan.linkId]) da conta que o sync atendeu da última vez. Vazio se nunca rodou. */
    fun who(profile: String, account: String): String = prefs.getString(k(profile, account, "who"), "").orEmpty()
    fun setWho(profile: String, account: String, v: String) { prefs.edit().putString(k(profile, account, "who"), v).apply() }

    /**
     * Esquece o que se sabia do remoto (chaves, marcas e fila), mas mantém o interruptor. Roda quando a conta ligada
     * ao perfil mudou, e também quando o interruptor é religado. Sem isso, o retrato velho faria o sync achar que sumiu
     * tudo da conta nova e apagar histórico daqui.
     */
    @Synchronized
    fun resetLink(profile: String, account: String) {
        val ed = prefs.edit()
        listOf("snap", "seen", "pseen", "sseed", "pull", "ok", "cur", "meta", "fut", "pend").forEach { ed.remove(k(profile, account, it)) }
        ed.apply()
    }

    // ── O que o remoto tinha na última puxada ───────────────────────────────────

    fun snap(profile: String, account: String): List<SnapEntry> = runCatching {
        val arr = JSONArray(prefs.getString(k(profile, account, "snap"), "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val a = arr.optJSONArray(i) ?: return@mapNotNull null
            SnapEntry(a.optString(0), a.optString(1), a.optInt(2) == 1, a.optLong(3), a.optInt(4), a.optInt(5), a.optInt(6) == 1)
        }
    }.getOrDefault(emptyList())

    fun setSnap(profile: String, account: String, list: List<SnapEntry>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONArray().put(it.key).put(it.contentId).put(if (it.series) 1 else 0).put(it.at).put(it.season).put(it.episode)
                    .put(if (it.pushed) 1 else 0),
            )
        }
        prefs.edit().putString(k(profile, account, "snap"), arr.toString()).apply()
    }

    /**
     * Chaves de visto que este aparelho enviou e que ainda não apareceram numa puxada. Entram no retrato (para desmarcar logo
     * depois funcionar) mas não valem para inferir remoção, pela mesma razão de [SnapEntry.pushed].
     */
    fun pushedSeen(profile: String, account: String): Set<String> = runCatching {
        val arr = JSONArray(prefs.getString(k(profile, account, "pseen"), "[]"))
        (0 until arr.length()).map { arr.optString(it) }.toSet()
    }.getOrDefault(emptySet())

    fun setPushedSeen(profile: String, account: String, keys: Set<String>) {
        prefs.edit().putString(k(profile, account, "pseen"), JSONArray(keys.toList()).toString()).apply()
    }

    /** Os episódios vistos daqui ainda precisam subir (a puxada dos vistos falhou na ligação e ficou para a próxima boa). */
    fun seedSeenPending(profile: String, account: String): Boolean = prefs.getBoolean(k(profile, account, "sseed"), false)
    fun setSeedSeenPending(profile: String, account: String, v: Boolean) { prefs.edit().putBoolean(k(profile, account, "sseed"), v).apply() }

    fun seenSnap(profile: String, account: String): Set<String> = runCatching {
        val arr = JSONArray(prefs.getString(k(profile, account, "seen"), "[]"))
        (0 until arr.length()).map { arr.optString(it) }.toSet()
    }.getOrDefault(emptySet())

    fun setSeenSnap(profile: String, account: String, keys: Set<String>) {
        prefs.edit().putString(k(profile, account, "seen"), JSONArray(keys.toList()).toString()).apply()
    }

    // ── Fila de pendências ──────────────────────────────────────────────────────

    @Synchronized
    fun pending(profile: String, account: String): Pending = runCatching {
        val o = JSONObject(prefs.getString(k(profile, account, "pend"), "{}"))
        fun longs(name: String): Map<String, Long> =
            o.optJSONObject(name)?.let { m -> m.keys().asSequence().associateWith { m.optLong(it) } } ?: emptyMap()
        val seen = o.optJSONObject("s")?.let { m -> m.keys().asSequence().associateWith { v -> m.optLong(v).let { PendingSeen(it > 0, kotlin.math.abs(it)) } } } ?: emptyMap()
        Pending(longs("p"), seen, longs("r"))
    }.getOrDefault(Pending())

    private fun write(profile: String, account: String, p: Pending) {
        val o = JSONObject()
        o.put("p", JSONObject(p.progress)).put("r", JSONObject(p.removed))
        o.put("s", JSONObject(p.seen.mapValues { (_, v) -> if (v.on) v.ts else -v.ts }))
        prefs.edit().putString(k(profile, account, "pend"), o.toString()).apply()
    }

    @Synchronized
    fun addProgress(profile: String, account: String, key: String, ts: Long) = addProgressAll(profile, account, listOf(key), ts)

    /**
     * Vários de uma vez, com uma leitura e uma gravação só (a ligação da conta enfileira o histórico inteiro). Com
     * [keepExisting] a chave que já está na fila fica como está, com a hora dela. A semente da ligação usa isso, senão
     * o que a pessoa tocou durante a primeira puxada perderia o "tocou agora" e, com relógio atrasado, não subiria.
     */
    @Synchronized
    fun addProgressAll(profile: String, account: String, keys: Collection<String>, ts: Long, keepExisting: Boolean = false) {
        if (keys.isEmpty()) return
        val p = pending(profile, account)
        val add = keys.filter { !keepExisting || it !in p.progress }
        if (add.isEmpty()) return
        // Voltou a tocar depois de remover. A remoção velha não vale mais.
        write(profile, account, p.copy(progress = p.progress + add.associateWith { ts }, removed = p.removed - add.toSet()))
    }

    @Synchronized
    fun addSeen(profile: String, account: String, key: String, on: Boolean, ts: Long) = addSeenAll(profile, account, listOf(key), on, ts)

    /** Com [keepExisting] o episódio que já tem pedido na fila (marcar ou desmarcar de verdade) não é trocado pela semente. */
    @Synchronized
    fun addSeenAll(profile: String, account: String, keys: Collection<String>, on: Boolean, ts: Long, keepExisting: Boolean = false) {
        if (keys.isEmpty()) return
        val p = pending(profile, account)
        val add = keys.filter { !keepExisting || it !in p.seen }
        if (add.isEmpty()) return
        write(profile, account, p.copy(seen = p.seen + add.associateWith { PendingSeen(on, ts) }))
    }

    @Synchronized
    fun addRemoved(profile: String, account: String, key: String, ts: Long) {
        val p = pending(profile, account)
        write(profile, account, p.copy(removed = p.removed + (key to ts), progress = p.progress - key))
    }

    /**
     * Tira da fila o que o envio levou ([taken] é a fila que ele leu no começo). Entrada que a pessoa mexeu durante
     * a conversa com o servidor tem outra hora e fica para a próxima. Comparar a entrada inteira (e não só a hora com
     * o começo do envio) também resolve o relógio que andou para trás, que deixava entrada presa e o envio em laço.
     */
    @Synchronized
    fun settle(profile: String, account: String, taken: Pending) {
        val p = pending(profile, account)
        write(
            profile, account,
            Pending(
                p.progress.filter { (k, v) -> taken.progress[k] != v }, p.seen.filter { (k, v) -> taken.seen[k] != v },
                p.removed.filter { (k, v) -> taken.removed[k] != v },
            ),
        )
    }

    // ── Mapa de ids (do aparelho) ───────────────────────────────────────────────

    private var toTmdb: LinkedHashMap<String, Int>? = null
    private var toRemote: LinkedHashMap<String, String>? = null
    private var idsDirty = false

    private fun typeKey(series: Boolean) = if (series) "s" else "m"

    @Synchronized
    private fun loadIds() {
        if (toTmdb != null) return
        val a = LinkedHashMap<String, Int>()
        val b = LinkedHashMap<String, String>()
        runCatching {
            val o = JSONObject(prefs.getString("ids", "{}"))
            o.optJSONObject("t")?.let { m -> m.keys().forEach { a[it] = m.optInt(it) } }
            o.optJSONObject("r")?.let { m -> m.keys().forEach { b[it] = m.optString(it) } }
        }
        toTmdb = a
        toRemote = b
    }

    /** Id do TMDB que já se descobriu para esse id do outro serviço. */
    @Synchronized
    fun idToTmdb(series: Boolean, remote: String): Int? { loadIds(); return toTmdb!![typeKey(series) + "|" + remote] }

    /** Id que o outro serviço usa para esse título do TMDB. */
    @Synchronized
    fun idToRemote(series: Boolean, tmdbId: Int): String? { loadIds(); return toRemote!![typeKey(series) + "|" + tmdbId] }

    /** Guarda os dois sentidos. Id "tt" nunca é trocado por "tmdb:N". */
    @Synchronized
    fun putId(series: Boolean, remote: String, tmdbId: Int) {
        loadIds()
        val t = typeKey(series) + "|" + remote
        val r = typeKey(series) + "|" + tmdbId
        if (toTmdb!![t] != tmdbId) { toTmdb!!.remove(t); toTmdb!![t] = tmdbId; idsDirty = true }
        val cur = toRemote!![r]
        if (cur == null || (!cur.startsWith("tt") && remote.startsWith("tt"))) { toRemote!!.remove(r); toRemote!![r] = remote; idsDirty = true }
        SyncPlan.cap(toTmdb!!); SyncPlan.cap(toRemote!!)
    }

    /** Grava o mapa de ids no disco se mudou. Chamado no fim de cada rodada. */
    @Synchronized
    fun commitIds() {
        if (!idsDirty) return
        idsDirty = false
        val o = JSONObject().put("t", JSONObject(toTmdb!! as Map<*, *>)).put("r", JSONObject(toRemote!! as Map<*, *>))
        prefs.edit().putString("ids", o.toString()).apply()
    }

    // ── Do aparelho ─────────────────────────────────────────────────────────────

    /** Identificador deste cliente para o parâmetro p_origin_client_id do Nuvio. */
    @Synchronized
    fun clientId(): String {
        prefs.getString("client", null)?.let { return it }
        val rnd = SecureRandom()
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        val id = "jotv-" + buildString { repeat(32) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
        prefs.edit().putString("client", id).apply()
        return id
    }

    /** O servidor aceitou o p_origin_client_id? null enquanto não se sabe. */
    fun originAccepted(): Boolean? = if (prefs.contains("origin_ok")) prefs.getBoolean("origin_ok", true) else null
    fun setOriginAccepted(v: Boolean) { prefs.edit().putBoolean("origin_ok", v).apply() }

    /** Apaga o que o sync guardou de um perfil removido. */
    @Synchronized
    fun drop(profile: String) {
        val ed = prefs.edit()
        prefs.all.keys.filter { it.startsWith("$profile.") }.forEach { ed.remove(it) }
        ed.apply()
    }
}
