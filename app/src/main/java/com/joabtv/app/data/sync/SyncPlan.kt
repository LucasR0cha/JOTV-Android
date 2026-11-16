package com.joabtv.app.data.sync

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Linha de progresso do outro serviço no formato comum. [key] é o progress_key do Nuvio (no Stremio vale o id do título). */
data class RemoteRow(
    val contentId: String,
    val series: Boolean,
    val season: Int,
    val episode: Int,
    val positionMs: Long,
    val durationMs: Long,
    val watchedAt: Long,
    val key: String,
)

/** Item visto do outro serviço. Filme vem com [season] e [episode] zerados. */
data class RemoteSeen(val contentId: String, val series: Boolean, val title: String, val season: Int, val episode: Int, val at: Long)

/**
 * Linha de progresso a enviar ao Nuvio. [key] é o progress_key que a própria conta já usa para esse episódio ou filme.
 * Sem ele vale a chave calculada, e se a conta usar outra o servidor criaria uma segunda linha.
 */
data class PushRow(
    val contentId: String, val series: Boolean, val season: Int, val episode: Int,
    val positionMs: Long, val durationMs: Long, val at: Long, val key: String? = null,
)

/** Item visto a enviar ao Nuvio. */
data class PushSeen(val contentId: String, val series: Boolean, val title: String, val season: Int, val episode: Int, val at: Long)

/** O que o sync guarda do remoto na última puxada. Serve para inferir remoção e para não pisar em dado mais novo. */
data class SnapEntry(
    val key: String, val contentId: String, val series: Boolean, val at: Long,
    val season: Int = 0, val episode: Int = 0,
    /** Linha que este aparelho acabou de enviar e ainda não apareceu numa puxada. Não vale para inferir remoção. */
    val pushed: Boolean = false,
)

/**
 * Decisões do sync de histórico que não dependem de rede nem de tela. Fica separado do motor para dar para
 * testar tudo isso na JVM. O comportamento é o mesmo da versão web (sync.js), conforme a especificação.
 */
object SyncPlan {
    /** Acima disso o servidor pode ter cortado a lista, então remoção não se infere. */
    const val SNAPSHOT_CAP = 900
    const val BATCH = 100
    const val LOOKUPS_PER_RUN = 60
    const val ID_MAP_CAP = 3000

    fun progressKey(contentId: String, season: Int, episode: Int): String =
        if (season > 0 && episode > 0) "${contentId}_s${season}e${episode}" else contentId

    fun videoId(contentId: String, series: Boolean, season: Int, episode: Int): String =
        if (series && season > 0 && episode > 0) "$contentId:$season:$episode" else contentId

    /** Id do título sem temporada e episódio ("tt1:2:3" vira "tt1", "tmdb:5:1:2" vira "tmdb:5"). */
    fun baseId(id: String): String =
        if (id.startsWith("tmdb:")) id.split(':').take(2).joinToString(":") else id.substringBefore(':')

    /** "T1:E3" vira (1, 3). Qualquer outra coisa vira (0, 0). */
    fun parseEpisode(label: String): Pair<Int, Int> {
        val m = Regex("T(\\d+):E(\\d+)", RegexOption.IGNORE_CASE).matchEntire(label.trim()) ?: return 0 to 0
        return (m.groupValues[1].toIntOrNull() ?: 0) to (m.groupValues[2].toIntOrNull() ?: 0)
    }

    /** Uma linha por título, a de maior hora (série tem uma linha por episódio). */
    fun latestPerTitle(rows: List<RemoteRow>): List<RemoteRow> =
        rows.groupBy { it.series to baseId(it.contentId) }.values.map { g -> g.maxBy { it.watchedAt } }

    /** O retrato leva a hora já limitada ([clampRemote]), a mesma que a linha daqui recebe na importação. Assim as duas se comparam. */
    fun snapshotOf(rows: List<RemoteRow>): List<SnapEntry> =
        rows.map { SnapEntry(it.key, baseId(it.contentId), it.series, clampRemote(it.watchedAt), it.season, it.episode) }

    /** A puxada veio inteira? */
    fun complete(count: Int): Boolean = count < SNAPSHOT_CAP

    /**
     * Sumiço em massa não é remoção do usuário. É resposta cortada, conta trocada ou erro do servidor, e apagar o
     * histórico daqui por causa disso não tem volta. [min] é o menor número de sumidos que já conta como massa.
     */
    fun massLoss(total: Int, gone: Int, min: Int): Boolean = gone >= min && gone * 2 > total

    /**
     * Títulos que estavam na última puxada e não estão mais. É remoção feita no outro app. Só vale com puxada
     * completa e com algo na puxada nova (lista vazia nunca apaga nada). A remoção é do título inteiro, porque série
     * com outro episódio ainda na lista continua viva. Sumiço em massa também não apaga ([massLoss]).
     */
    fun goneTitles(prev: List<SnapEntry>, now: List<SnapEntry>, complete: Boolean): Set<Pair<Boolean, String>> {
        if (!complete || now.isEmpty()) return emptySet()
        val alive = now.map { it.series to it.contentId }.toSet()
        // Linha que só existe porque este aparelho a enviou ainda não foi confirmada pela conta. Se o servidor respondeu
        // 2xx e não gravou, ela sumiria da puxada seguinte e o sync apagaria daqui um histórico que só existia aqui.
        val before = prev.filter { !it.pushed }.map { it.series to it.contentId }.toSet()
        val gone = before - alive
        return if (massLoss(before.size, gone.size, 5)) emptySet() else gone
    }

    /**
     * Episódios que estavam na lista de vistos e sumiram dela (chave "id|temporada|episódio"). Só com puxada completa,
     * com algo na puxada nova e sem sumiço em massa. Chave de filme (temporada 0) não entra, porque filme visto não se desmarca.
     */
    fun goneSeen(prev: Set<String>, now: Set<String>, complete: Boolean): Set<String> {
        if (!complete || now.isEmpty()) return emptySet()
        val before = prev.filter { isEpisodeKey(it) }.toSet()
        val gone = before - now
        return if (massLoss(before.size, gone.size, 20)) emptySet() else gone
    }

    /** Chave de visto de episódio ("id|temporada|episódio" com os dois números maiores que zero). */
    fun isEpisodeKey(key: String): Boolean {
        val p = key.split('|')
        return p.size == 3 && (p[1].toIntOrNull() ?: 0) > 0 && (p[2].toIntOrNull() ?: 0) > 0
    }

    fun seenKey(contentId: String, season: Int, episode: Int) = "${baseId(contentId)}|$season|$episode"

    /**
     * Identificador da ligação entre um perfil daqui e uma conta (e, no Nuvio, o perfil dela). Vira hash para o
     * e-mail não ficar em texto nas preferências. Quando muda, o que se sabia do remoto não vale mais.
     */
    fun linkId(email: String, nuvioProfile: Int?): String {
        val raw = email.trim().lowercase() + "#" + (nuvioProfile ?: 0)
        return java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
    }

    /** Duração mínima para o progresso subir ao outro serviço. Abaixo disso é marcador de "visto" (1 ms) e não vira linha lá. */
    const val MIN_PUSH_MS = 1_000L

    fun pushable(durationMs: Long): Boolean = durationMs >= MIN_PUSH_MS

    /**
     * Hora que vale para um dado vindo de outro aparelho. Hora no futuro é relógio adiantado de lá, e o que foi gravado só pode ter
     * acontecido antes de agora. Vale o instante em que esse valor foi visto pela primeira vez ([firstSeen]), e ele fica parado nas
     * puxadas seguintes, mesmo depois que o relógio daqui alcança a hora que veio. Se ela voltasse a valer inteira nesse momento,
     * passaria por cima do que a pessoa assistiu aqui entre uma coisa e outra. Com um teto que acompanha o relógio, a hora remota
     * andaria junto e sempre venceria o que a pessoa acabou de assistir aqui (a posição velha voltaria por cima da nova).
     */
    fun effectiveRemote(raw: Long, now: Long, firstSeen: Long?): Long = firstSeen ?: if (raw <= now) raw else now

    /**
     * A remoção feita aqui ainda vale? Se o remoto foi mexido depois dela (a pessoa viu em outro aparelho, por exemplo),
     * apagar lá levaria o dado mais novo. É a mesma regra com que a puxada traz o título de volta (hora maior que a da
     * remoção), então remoção e importação nunca discordam. Com tolerância, uma linha um pouco mais nova seria importada
     * aqui e apagada lá na mesma rodada, e os dois lados ficariam diferentes.
     */
    fun removalStands(remoteAt: Long?, removedAt: Long): Boolean = remoteAt == null || remoteAt <= removedAt

    /** Diferença de relógio entre aparelhos que ainda se trata como a mesma hora (TV com hora atrasada, por exemplo). */
    const val SKEW_MS = 5 * 60_000L

    /**
     * Envia quando a hora local é maior que a do remoto na última puxada. Nunca envia posição zero. Quando a pessoa
     * tocou depois da última puxada ([real]), a hora local ainda vale se o relógio daqui estiver até [SKEW_MS] atrás
     * do remoto. Sem isso, um aparelho com a hora atrasada nunca mandaria o que viu. Item que veio de ligar a conta
     * (não é [real]) segue a regra simples, para o histórico velho daqui nunca cobrir o mais novo do outro lado.
     */
    fun shouldPush(localAt: Long, positionMs: Long, remoteAt: Long?, real: Boolean = false): Boolean =
        positionMs > 0 && (remoteAt == null || localAt > remoteAt || (real && remoteAt - localAt <= SKEW_MS))

    /** Hora que vai no envio. Nunca menor que a do remoto, senão o outro app e a próxima puxada achariam o dado velho. */
    fun pushAt(localAt: Long, remoteAt: Long?): Long = if (remoteAt != null && remoteAt >= localAt) remoteAt + 1 else localAt

    /** Hora vinda de fora com no máximo uma hora de adiantamento, para relógio errado de outro aparelho não prender o título no topo. */
    fun clampRemote(at: Long, now: Long = System.currentTimeMillis()): Long = minOf(at, now + 3_600_000L)

    /** Passou de 90 por cento da duração. */
    fun finished(positionMs: Long, durationMs: Long): Boolean = durationMs > 0 && positionMs >= durationMs * 0.9

    /** Id que o outro serviço entende. O que ele mesmo já usou, depois o IMDb, depois tmdb:N. */
    fun pickRemoteId(remembered: String?, imdb: String?, tmdbId: Int): String =
        remembered?.takeIf { it.isNotBlank() } ?: imdb?.takeIf { it.startsWith("tt") } ?: "tmdb:$tmdbId"

    /** Tira os mais antigos até caber no teto. */
    fun <K, V> cap(map: LinkedHashMap<K, V>, cap: Int = ID_MAP_CAP) {
        while (map.size > cap) map.remove(map.keys.first())
    }

    // ── Corpos enviados ao Nuvio ────────────────────────────────────────────────

    private fun orNull(on: Boolean, v: Int): Any = if (on && v > 0) v else JSONObject.NULL

    private fun JSONObject.origin(id: String?) = apply { if (id != null) put("p_origin_client_id", id) }

    fun progressBody(profile: Int, rows: List<PushRow>, origin: String?): JSONObject {
        val arr = JSONArray()
        rows.forEach { r ->
            val s = if (r.series) r.season else 0
            val e = if (r.series) r.episode else 0
            arr.put(
                JSONObject()
                    .put("content_id", r.contentId)
                    .put("content_type", if (r.series) "series" else "movie")
                    .put("video_id", videoId(r.contentId, r.series, s, e))
                    .put("season", orNull(r.series, s))
                    .put("episode", orNull(r.series, e))
                    .put("position", r.positionMs)
                    .put("duration", r.durationMs)
                    .put("last_watched", r.at)
                    .put("progress_key", r.key?.takeIf { it.isNotBlank() } ?: progressKey(r.contentId, s, e)),
            )
        }
        return JSONObject().put("p_profile_id", profile).put("p_entries", arr).origin(origin)
    }

    fun watchedBody(profile: Int, items: List<PushSeen>, origin: String?): JSONObject {
        val arr = JSONArray()
        items.forEach { i ->
            arr.put(
                JSONObject()
                    .put("content_id", i.contentId)
                    .put("content_type", if (i.series) "series" else "movie")
                    .put("title", i.title)
                    .put("season", orNull(i.series, i.season))
                    .put("episode", orNull(i.series, i.episode))
                    .put("watched_at", i.at),
            )
        }
        return JSONObject().put("p_profile_id", profile).put("p_items", arr).origin(origin)
    }

    fun deleteProgressBody(profile: Int, keys: List<String>, origin: String?): JSONObject =
        JSONObject().put("p_profile_id", profile).put("p_keys", JSONArray(keys)).origin(origin)

    /** [keys] são (id, temporada, episódio). Temporada e episódio 0 viram nulo (filme). */
    fun deleteWatchedBody(profile: Int, keys: List<Triple<String, Int, Int>>, origin: String?): JSONObject {
        val arr = JSONArray()
        keys.forEach { (id, s, e) ->
            arr.put(JSONObject().put("content_id", id).put("season", orNull(s > 0, s)).put("episode", orNull(e > 0, e)))
        }
        return JSONObject().put("p_profile_id", profile).put("p_keys", arr).origin(origin)
    }

    // ── Itens enviados ao Stremio ───────────────────────────────────────────────

    fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString()

    private fun newStremioItem(id: String, series: Boolean, name: String, poster: String, nowMs: Long): JSONObject =
        JSONObject()
            .put("_id", id).put("name", name).put("type", if (series) "series" else "movie")
            .put("poster", poster).put("posterShape", "poster")
            // Título tocado fora da biblioteca fica assim no Stremio (removido e temporário).
            .put("removed", true).put("temp", true)
            .put("_ctime", iso(nowMs)).put("_mtime", iso(nowMs))
            .put(
                "state",
                JSONObject().put("lastWatched", iso(nowMs)).put("timeWatched", 0).put("timeOffset", 0)
                    .put("overallTimeWatched", 0).put("timesWatched", 0).put("flaggedWatched", 0)
                    .put("duration", 0).put("video_id", "").put("watched", "").put("noNotif", false),
            )
            .put(
                "behaviorHints",
                JSONObject().put("defaultVideoId", JSONObject.NULL).put("featuredVideoId", JSONObject.NULL).put("hasScheduledVideos", false),
            )

    /**
     * Item da biblioteca com o progresso novo. Parte da cópia da conta, quando existe, e troca só o progresso
     * (lastWatched, timeOffset, duration, video_id) e o _mtime. Removed, temp, _ctime, watched e o resto ficam como estavam.
     */
    fun stremioItem(
        existing: JSONObject?, id: String, series: Boolean, name: String, poster: String,
        videoId: String, positionMs: Long, durationMs: Long, atMs: Long, nowMs: Long,
    ): JSONObject {
        val base = existing?.let { JSONObject(it.toString()) } ?: newStremioItem(id, series, name, poster, nowMs)
        val state = base.optJSONObject("state") ?: JSONObject().also { base.put("state", it) }
        state.put("lastWatched", iso(atMs)).put("timeOffset", positionMs).put("duration", durationMs).put("video_id", videoId)
        base.put("_mtime", iso(nowMs))
        return base
    }

    /** Tirar do Continuar assistindo no Stremio é zerar o timeOffset. Sem item na conta não há o que zerar (null). */
    fun stremioCleared(existing: JSONObject?, nowMs: Long): JSONObject? {
        val base = existing?.let { JSONObject(it.toString()) } ?: return null
        val state = base.optJSONObject("state") ?: return null
        state.put("timeOffset", 0)
        base.put("_mtime", iso(nowMs))
        return base
    }
}
