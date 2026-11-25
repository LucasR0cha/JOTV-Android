package com.joabtv.app.ui.movies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.joabtv.app.data.local.PlatformPrefs
import com.joabtv.app.data.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/**
 * Salva o progresso de reprodução (posição + fonte) do player de filme/episódio.
 * Usado pelo MoviePlayerScreen para o "Continuar assistindo" retomar no ponto e fonte exatos.
 */
@HiltViewModel
class PlayerProgressViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val prefs: PlatformPrefs,
    private val trakt: com.joabtv.app.data.remote.TraktService,
) : ViewModel() {
    private val progressWrites = Mutex()

    /** Avisa o Trakt e os outros serviços (start, pause ou stop). Não faz nada sem conta conectada. */
    fun scrobble(action: String, tmdbId: Int, type: String, season: Int, episode: Int, positionMs: Long, durationMs: Long) {
        // Pause sem nada tocado (posição 0) não tem progresso para salvar.
        if (durationMs <= 0 || (action == "pause" && positionMs <= 0)) return
        trakt.scrobble(action, tmdbId, type, season, episode, positionMs * 100.0 / durationMs, positionMs, durationMs)
    }

    /** Abertura aprendida da série (inicioMs, fimMs). null = ainda não aprendeu. */
    fun introMarker(tmdbId: Int): Pair<Long, Long>? = prefs.introMarker(tmdbId)

    /**
     * Aprende a abertura a partir de um pulo manual do usuário. Só aceita salto pra frente
     * de 20s a 4min que começa nos primeiros 8min do episódio — é o formato de abertura.
     * Volta true quando gravou.
     */
    fun learnIntro(tmdbId: Int, fromMs: Long, toMs: Long): Boolean {
        val jump = toMs - fromMs
        if (tmdbId <= 0 || fromMs < 0 || fromMs > 8 * 60_000L) return false
        if (jump < 20_000L || jump > 4 * 60_000L) return false
        prefs.saveIntroMarker(tmdbId, fromMs, toMs)
        return true
    }

    var subSize: Float
        get() = prefs.subSize
        set(v) { prefs.subSize = v }
    var subBg: Boolean
        get() = prefs.subBg
        set(v) { prefs.subBg = v }
    /** Melhorar imagem deste perfil, no formato de [ImageEnhance.encode]. Vazio é desligado. */
    var imageEnhance: String
        get() = prefs.imageEnhance
        set(v) { prefs.imageEnhance = v }
    var autoSwitchSources: Boolean
        get() = prefs.autoSwitchSources
        set(v) { prefs.autoSwitchSources = v }
    var sourceTimeoutSec: Int
        get() = prefs.sourceTimeoutSec
        set(v) { prefs.sourceTimeoutSec = v }
    val autoUpgradeQuality: Boolean get() = prefs.autoUpgradeQuality
    val debridCachedOnly: Boolean get() = prefs.debridCachedOnly
    var subtitleOn: Boolean
        get() = prefs.subtitleOn
        set(v) { prefs.subtitleOn = v }
    val subLang: String get() = prefs.subLang
    val forcedSubs: Boolean get() = prefs.forcedSubs

    /** Som de outra fonte salvo para o título, ou null. */
    fun extAudio(key: String): com.joabtv.app.data.local.ExtAudioPick? = prefs.extAudioPicks[key]

    /** Grava (ou apaga, com [sig] null) a escolha de som do título. Guarda no máximo 60 títulos. */
    fun saveExtAudio(key: String, sig: String?, delaySec: Double) {
        val all = prefs.extAudioPicks.toMutableMap()
        if (sig == null) all.remove(key)
        else all[key] = com.joabtv.app.data.local.ExtAudioPick(sig, delaySec, System.currentTimeMillis())
        prefs.extAudioPicks = keepNewest(all)
    }

    fun save(
        tmdbId: Int,
        type: String,
        title: String,
        posterUrl: String,
        positionMs: Long,
        durationMs: Long,
        lastEpisode: String,
        sourceUrl: String,
        addon: String = "",
    ) {
        if (tmdbId <= 0 || sourceUrl.isBlank() || positionMs <= 0) return
        val profile = prefs.profileId.value
        viewModelScope.launch {
            withContext(NonCancellable) {
                progressWrites.withLock {
                    library.saveProgress(
                        tmdbId = tmdbId, type = type, title = title, posterUrl = posterUrl,
                        positionMs = positionMs, durationMs = durationMs,
                        lastEpisode = lastEpisode, lastSourceUrl = sourceUrl, lastAddon = addon, profileId = profile,
                    )
                }
            }
        }
    }

    suspend fun complete(tmdbId: Int, type: String, title: String, posterUrl: String, durationMs: Long, lastEpisode: String, sourceUrl: String, addon: String) {
        if (tmdbId <= 0 || sourceUrl.isBlank()) return
        val profile = prefs.profileId.value
        val duration = durationMs.coerceAtLeast(1L)
        withContext(NonCancellable) {
            progressWrites.withLock {
                library.saveProgress(tmdbId, type, title, posterUrl, duration, duration, lastEpisode, sourceUrl, addon, profile)
            }
        }
    }

    /** Addon escolhido da última vez que essa série/filme tocou, ou "" se nunca. Para o próximo
     * episódio abrir já na mesma fonte, sem o usuário escolher de novo a cada troca. */
    suspend fun lastAddon(tmdbId: Int, type: String): String {
        if (tmdbId <= 0) return ""
        return runCatching { library.getPlayback(tmdbId, type)?.lastAddon }.getOrNull().orEmpty()
    }

    /**
     * Posição salva deste conteúdo, ou 0.
     *
     * Serve pra quando o player é aberto por um caminho que não informa o tempo (clique direto
     * numa fonte da lista, botão dublado/legendado, atalho da TV). Antes só o botão principal
     * mandava a posição, e por qualquer outro caminho o título abria do zero.
     *
     * [lastEpisode] vazio = filme (vale qualquer progresso salvo). Preenchido = só retoma se o
     * progresso salvo for DESSE episódio, senão T2:E1 herdaria o tempo de T1:E8.
     */
    suspend fun savedPosition(tmdbId: Int, type: String, lastEpisode: String): Long {
        if (tmdbId <= 0) return 0
        val pb = runCatching { library.getPlayback(tmdbId, type) }.getOrNull() ?: return 0
        if (lastEpisode.isNotBlank() && pb.lastEpisode != lastEpisode) return 0
        // Quase no fim conta como visto: recomeça em vez de abrir nos créditos.
        if (com.joabtv.app.data.local.playbackCompleted(pb.positionMs, pb.durationMs)) return 0
        return pb.positionMs.coerceAtLeast(0L)
    }

    /** Marca o episódio como VISTO (chamado ao passar de 90% da duração). */
    fun markWatched(tmdbId: Int, season: Int, episode: Int) {
        if (tmdbId <= 0 || season <= 0 || episode <= 0) return
        viewModelScope.launch { library.markEpisodeWatched(tmdbId, season, episode) }
    }
}
