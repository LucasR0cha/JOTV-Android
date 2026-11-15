package com.joabtv.app.ui.tv

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.joabtv.app.domain.model.EpisodeItem
import com.joabtv.app.ui.movies.MovieDetailViewModel
import com.joabtv.app.ui.theme.LiveRed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun TvContinueOptions(
    title: String,
    episode: String,
    canMark: Boolean,
    onDetails: () -> Unit,
    onMark: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(100); runCatching { first.requestFocus() } }
    TvSourcePanel(title, episode.replace(':', ' '), onDismiss) {
        TvButton("Ir para detalhes", onDetails, Modifier.fillMaxWidth().focusRequester(first), icon = Icons.Default.Info)
        if (canMark) TvButton("Marcar como assistido", onMark, Modifier.fillMaxWidth(), icon = Icons.Default.CheckCircle)
        TvButton("Remover de Continuar assistindo", onRemove, Modifier.fillMaxWidth(), icon = Icons.Default.DeleteOutline)
        TvButton("Fechar", onDismiss, Modifier.fillMaxWidth())
    }
}

@Composable
internal fun TvEpisodeOptions(
    episode: EpisodeItem,
    viewModel: MovieDetailViewModel,
    onSources: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    var busy by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var task by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(Unit) { delay(100); runCatching { first.requestFocus() } }
    fun close() { task?.cancel(); onDismiss() }
    fun save(kind: String, operation: suspend () -> Unit) {
        if (busy.isNotEmpty()) return
        busy = kind
        error = null
        task = scope.launch {
            try { operation(); onDismiss() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Não foi possível atualizar os episódios. Confira a conexão e tente novamente" }
            finally { busy = ""; task = null }
        }
    }
    TvSourcePanel(episode.name.ifBlank { "Episódio ${episode.episodeNumber}" }, "T${episode.seasonNumber} E${episode.episodeNumber}", ::close) {
        TvButton("Ver fontes", onSources, Modifier.fillMaxWidth().focusRequester(first), icon = Icons.Default.PlayArrow, enabled = busy.isEmpty())
        TvButton(if (busy == "episode") "Salvando…" else "Marcar como assistido", {
            save("episode") { viewModel.setEpisodeViewed(episode.seasonNumber, episode.episodeNumber) }
        }, Modifier.fillMaxWidth(), icon = Icons.Default.CheckCircle, enabled = busy.isEmpty(), busy = busy == "episode")
        TvButton(if (busy == "previous") "Salvando…" else "Marcar anteriores como assistidos", {
            save("previous") { viewModel.markEpisodeRange(episode, true) }
        }, Modifier.fillMaxWidth(), icon = Icons.Default.CheckCircle, enabled = busy.isEmpty(), busy = busy == "previous")
        TvButton(if (busy == "season") "Salvando…" else "Marcar temporada como assistida", {
            save("season") { viewModel.markEpisodeRange(episode, false) }
        }, Modifier.fillMaxWidth(), icon = Icons.Default.CheckCircle, enabled = busy.isEmpty(), busy = busy == "season")
        error?.let { Text(it, color = LiveRed, fontSize = 1.rs, modifier = Modifier.padding(vertical = 0.5.r)) }
        TvButton(if (busy.isEmpty()) "Fechar" else "Cancelar", ::close, Modifier.fillMaxWidth())
    }
}
