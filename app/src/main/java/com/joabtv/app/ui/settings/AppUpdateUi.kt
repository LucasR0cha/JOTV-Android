package com.joabtv.app.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.joabtv.app.BuildConfig
import com.joabtv.app.data.update.AppUpdateRepository
import com.joabtv.app.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppUpdateViewModel @Inject constructor(private val repository: AppUpdateRepository) : ViewModel() {
    val state = repository.state
    private var downloadJob: Job? = null
    fun check(manual: Boolean) { viewModelScope.launch { repository.check(manual) } }
    fun dismiss() = repository.dismiss()
    fun download() { if (downloadJob?.isActive != true) downloadJob = viewModelScope.launch { repository.download() } }
    fun cancel() { downloadJob?.cancel(); repository.cancelDownload() }

    fun install(context: Context): String? {
        val file = repository.packageFile() ?: return "Baixe a atualização novamente"
        return try {
            if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                "Permita a instalação pelo JOTV e toque em Instalar novamente"
            } else {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                null
            }
        } catch (_: ActivityNotFoundException) { "Este aparelho não oferece um instalador. Abra a atualização no GitHub" }
        catch (_: SecurityException) { "O Android não permitiu abrir a instalação. Confira as permissões do JOTV" }
    }
}

@Composable
fun AppUpdateSettingsRow(viewModel: AppUpdateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    com.joabtv.app.ui.common.SettingsRow(
        icon = Icons.Default.Refresh,
        title = if (state.checking) "Verificando atualizações…" else "Verificar atualizações",
        sub = "JOTV ${BuildConfig.VERSION_NAME}. Consulta as versões publicadas no GitHub e pergunta antes de atualizar",
        busy = state.checking || state.downloading,
        onClick = { viewModel.check(true) },
    )
}

@Composable
fun AppUpdateHost(allowPrompt: Boolean, viewModel: AppUpdateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var actionMessage by remember { mutableStateOf<String?>(null) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(Unit) { viewModel.check(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) viewModel.check(false)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    if (!state.show || !allowPrompt) return
    val cancelFocus = remember { FocusRequester() }
    val television = com.joabtv.app.ui.common.LocalIsTv.current
    val height = LocalConfiguration.current.screenHeightDp.dp * .65f
    val release = state.release
    AlertDialog(
        onDismissRequest = { if (state.downloading) viewModel.cancel() else viewModel.dismiss() },
        containerColor = BackgroundCard,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(.9f),
        title = { Text(if (release == null) "Atualizações do JOTV" else "Atualizar JOTV para ${release.version}", color = TextPrimary) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = height).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Versão instalada ${BuildConfig.VERSION_NAME}", color = TextSecondary)
                if (state.checking) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Consultando as versões no GitHub…", color = TextPrimary)
                } else if (release != null && !state.ready && !state.downloading) {
                    Text("Uma nova versão está disponível para celular e Android TV. Deseja baixar a atualização? O Android pedirá sua confirmação para instalar.", color = TextPrimary)
                    if (release.notes.isNotBlank()) Text(release.notes, color = TextSecondary)
                }
                if (state.downloading) LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                if (state.message.isNotBlank()) Text(state.message, color = TextPrimary)
                actionMessage?.let { Text(it, color = TextPrimary) }
                if (release != null && !state.downloading && (!state.ready || actionMessage != null)) TextButton(onClick = {
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.page))) }
                    catch (_: ActivityNotFoundException) { actionMessage = "Abra github.com/${BuildConfig.UPDATE_REPOSITORY}/releases no celular ou PC" }
                }, colors = ButtonDefaults.textButtonColors(contentColor = TextPrimary), modifier = Modifier.heightIn(min = 48.dp)) { Text("Abrir no GitHub") }
            }
        },
        confirmButton = {
            if (state.ready) com.joabtv.app.ui.common.JoabButton("Instalar", { actionMessage = viewModel.install(context) },
                tone = com.joabtv.app.ui.common.ButtonTone.PRIMARY, compact = true)
            else if (release != null && release.sha256.isNotBlank()) com.joabtv.app.ui.common.JoabButton("Baixar atualização",
                { actionMessage = null; viewModel.download() }, tone = com.joabtv.app.ui.common.ButtonTone.PRIMARY,
                enabled = !state.checking, busy = state.downloading, busyLabel = "Baixando…", compact = true)
        },
        dismissButton = {
            val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
            com.joabtv.app.ui.common.JoabButton(if (state.downloading) "Cancelar download" else if (release == null || state.ready) "Fechar" else "Agora não",
                { if (state.downloading) viewModel.cancel() else viewModel.dismiss() },
                modifier = Modifier.focusRequester(cancelFocus), compact = true)
            LaunchedEffect(Unit) {
                if (television) {
                    inputMode.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
                    withFrameNanos { }
                    runCatching { cancelFocus.requestFocus() }
                }
            }
        },
    )
}
